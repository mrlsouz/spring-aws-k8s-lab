#!/usr/bin/env bash
set -Eeuo pipefail

namespace="${K8S_NAMESPACE:-lab}"
api_service="${K8S_API_SERVICE:-notes-api}"
s3_service="${K8S_S3_SERVICE:-s3mock}"
api_port="${NOTES_API_TEST_PORT:-18080}"
s3_port="${S3MOCK_TEST_PORT:-19090}"
bucket="${S3MOCK_TEST_BUCKET:-notes-local}"
api_url="${API_BASE_URL:-http://127.0.0.1:${api_port}}"
s3_endpoint="${S3MOCK_ENDPOINT:-http://127.0.0.1:${s3_port}}"
note_id=""
export_id=""
api_log="$(mktemp)"
s3_log="$(mktemp)"
download_file="$(mktemp)"
headers_file="$(mktemp)"
response_file="$(mktemp)"

cleanup() {
  if [[ -n "$note_id" ]]; then
    curl --silent --max-time 3 --request DELETE "${api_url}/notes/${note_id}" >/dev/null || true
  fi
  if [[ -n "$export_id" ]]; then
    aws s3api delete-object --bucket "$bucket" --key "exports/${export_id}.json" \
      --endpoint-url "$s3_endpoint" >/dev/null 2>&1 || true
  fi
  for pid in "${api_port_forward_pid:-}" "${s3_port_forward_pid:-}"; do
    if [[ -n "$pid" ]]; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  rm -f "$api_log" "$s3_log" "$download_file" "$headers_file" "$response_file"
}
trap cleanup EXIT

command -v curl >/dev/null || { echo "curl é necessário para este smoke test" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq é necessário para este smoke test" >&2; exit 1; }
command -v aws >/dev/null || { echo "AWS CLI é necessário para limpar o objeto no S3Mock" >&2; exit 1; }

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
export AWS_EC2_METADATA_DISABLED=true

wait_for_endpoint() {
  local url="$1"
  local log_file="$2"
  local deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    if curl --silent --fail --connect-timeout 2 --max-time 4 "$url" >/dev/null; then
      return 0
    fi
    if [[ -n "$log_file" ]]; then
      local pid="${3:-}"
      if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then
        cat "$log_file" >&2
        return 1
      fi
    fi
    sleep 1
  done
  [[ -z "$log_file" ]] || cat "$log_file" >&2
  echo "Serviço não respondeu em 120 segundos: $url" >&2
  return 1
}

if [[ -z "${API_BASE_URL:-}" ]]; then
  kubectl --namespace "$namespace" port-forward --address 127.0.0.1 \
    "service/${api_service}" "${api_port}:8080" >"$api_log" 2>&1 &
  api_port_forward_pid=$!
  wait_for_endpoint "${api_url}/actuator/health/readiness" "$api_log" "$api_port_forward_pid"
else
  wait_for_endpoint "${api_url}/actuator/health/readiness" "" ""
fi

if [[ -z "${S3MOCK_ENDPOINT:-}" ]]; then
  kubectl --namespace "$namespace" port-forward --address 127.0.0.1 \
    "service/${s3_service}" "${s3_port}:9090" >"$s3_log" 2>&1 &
  s3_port_forward_pid=$!
  wait_for_endpoint "${s3_endpoint}/" "$s3_log" "$s3_port_forward_pid"
else
  wait_for_endpoint "${s3_endpoint}/" "" ""
fi

title="archive-smoke-$(date +%s)-$$"
content="snapshot local de notas"
payload="$(jq -n --arg title "$title" --arg content "$content" '{title: $title, content: $content}')"
status="$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' \
  --max-time 15 --header 'Content-Type: application/json' --data "$payload" "${api_url}/notes")"
if [[ "$status" != 201 ]]; then
  cat "$response_file" >&2
  echo "POST /notes retornou HTTP ${status}; esperado 201" >&2
  exit 1
fi
note_id="$(jq -er '.id' "$response_file")"

status="$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' \
  --max-time 15 --request POST "${api_url}/exports")"
if [[ "$status" != 201 ]]; then
  cat "$response_file" >&2
  echo "POST /exports retornou HTTP ${status}; esperado 201" >&2
  exit 1
fi
export_id="$(jq -er '.exportId' "$response_file")"

curl --silent --show-error --fail --max-time 15 --dump-header "$headers_file" \
  "${api_url}/exports/${export_id}" --output "$download_file"
grep -qi "attachment; filename=\"notes-${export_id}.json\"" "$headers_file"
jq -e --arg export_id "$export_id" --arg note_id "$note_id" --arg title "$title" --arg content "$content" \
  '.schemaVersion == 1 and .exportId == $export_id and any(.notes[]; .id == $note_id and .title == $title and .content == $content)' \
  "$download_file" >/dev/null
echo "Exportar e baixar snapshot JSON: OK"

status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --max-time 15 --request DELETE "${api_url}/notes/${note_id}")"
if [[ "$status" != 204 ]]; then
  echo "DELETE /notes/${note_id} retornou HTTP ${status}; esperado 204" >&2
  exit 1
fi

curl --silent --show-error --fail --max-time 15 --request POST \
  "${api_url}/imports/${export_id}" --output "$response_file"
jq -e '.imported == 1 and .skipped == 0 and (.conflictingIds | length) == 0 and (.failedIds | length) == 0' \
  "$response_file" >/dev/null
curl --silent --show-error --fail --max-time 15 "${api_url}/notes/${note_id}" \
  | jq -e --arg note_id "$note_id" --arg title "$title" --arg content "$content" \
      '.id == $note_id and .title == $title and .content == $content' >/dev/null
echo "Importar snapshot e restaurar nota: OK"

curl --silent --show-error --fail --max-time 15 --request POST \
  "${api_url}/imports/${export_id}" --output "$response_file"
jq -e '.imported == 0 and .skipped == 1' "$response_file" >/dev/null
echo "Repetir importação sem duplicar: OK"
