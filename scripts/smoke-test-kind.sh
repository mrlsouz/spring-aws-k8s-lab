#!/usr/bin/env bash
set -Eeuo pipefail

namespace="${K8S_NAMESPACE:-lab}"
service="${K8S_SERVICE:-notes-api}"
local_port="${SMOKE_TEST_PORT:-18080}"
base_url="http://127.0.0.1:${local_port}"
note_id=""
log_file="$(mktemp)"
response_file="$(mktemp)"

cleanup() {
  if [[ -n "$note_id" ]]; then
    curl --silent --max-time 2 --request DELETE "${base_url}/notes/${note_id}" >/dev/null || true
  fi
  if [[ -n "${port_forward_pid:-}" ]]; then
    kill "$port_forward_pid" 2>/dev/null || true
    wait "$port_forward_pid" 2>/dev/null || true
  fi
  rm -f "$log_file" "$response_file"
}
trap cleanup EXIT

kubectl --namespace "$namespace" port-forward --address 127.0.0.1 \
  "service/${service}" "${local_port}:8080" >"$log_file" 2>&1 &
port_forward_pid=$!

ready=false
deadline=$((SECONDS + 120))
# SECONDS is integer-valued, so reserve one second to keep curl within the deadline.
while (( SECONDS < deadline )); do
  remaining=$((deadline - SECONDS - 1))
  if (( remaining <= 0 )); then
    break
  fi
  if curl --silent --fail --connect-timeout 2 --max-time "$remaining" \
    "${base_url}/actuator/health/readiness" >/dev/null; then
    ready=true
    break
  fi
  if ! kill -0 "$port_forward_pid" 2>/dev/null; then
    cat "$log_file" >&2
    exit 1
  fi
  remaining=$((deadline - SECONDS - 1))
  if (( remaining > 0 )); then
    sleep "$((remaining < 2 ? remaining : 2))"
  fi
done

if [[ "$ready" != true ]]; then
  cat "$log_file" >&2
  echo "notes-api não ficou pronta em 120 segundos" >&2
  exit 1
fi

curl --silent --fail --max-time 10 "${base_url}/actuator/health/liveness" | jq -e '.status == "UP"' >/dev/null
curl --silent --fail --max-time 10 "${base_url}/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null
echo "Health checks: OK"

title="kind-smoke-$(date +%s)-$$"
content="smoke test local"
payload="$(jq -n --arg title "$title" --arg content "$content" '{title: $title, content: $content}')"
status="$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' \
  --max-time 10 --header 'Content-Type: application/json' --data "$payload" "${base_url}/notes")"
if [[ "$status" != 201 ]]; then
  cat "$response_file" >&2
  echo "POST /notes retornou HTTP ${status}; esperado 201" >&2
  exit 1
fi
note_id="$(jq -er '.id' "$response_file")"
jq -e --arg title "$title" --arg content "$content" \
  '.title == $title and .content == $content' "$response_file" >/dev/null
echo "Criar nota: OK"

curl --silent --fail --max-time 10 "${base_url}/notes" \
  | jq -e --arg id "$note_id" 'any(.[]; .id == $id)' >/dev/null
curl --silent --fail --max-time 10 "${base_url}/notes/${note_id}" \
  | jq -e --arg id "$note_id" --arg title "$title" --arg content "$content" \
      '.id == $id and .title == $title and .content == $content' >/dev/null
echo "Listar e consultar nota: OK"

status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --max-time 10 --request DELETE "${base_url}/notes/${note_id}")"
if [[ "$status" != 204 ]]; then
  echo "DELETE /notes/${note_id} retornou HTTP ${status}; esperado 204" >&2
  exit 1
fi
status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --max-time 10 "${base_url}/notes/${note_id}")"
if [[ "$status" != 404 ]]; then
  echo "GET /notes após exclusão retornou HTTP ${status}; esperado 404" >&2
  exit 1
fi
note_id=""
echo "Excluir nota e confirmar 404: OK"
