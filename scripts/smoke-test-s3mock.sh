#!/usr/bin/env bash
set -Eeuo pipefail

namespace="${K8S_NAMESPACE:-lab}"
service="${K8S_S3_SERVICE:-s3mock}"
local_port="${S3MOCK_TEST_PORT:-19090}"
endpoint="http://127.0.0.1:${local_port}"
bucket="${S3MOCK_TEST_BUCKET:-notes-local}"
key="s3mock-smoke-$(date +%s)-$$.txt"
input_file="$(mktemp)"
output_file="$(mktemp)"
log_file="$(mktemp)"

cleanup() {
  aws s3api delete-object --bucket "$bucket" --key "$key" --endpoint-url "$endpoint" >/dev/null 2>&1 || true
  if [[ -n "${port_forward_pid:-}" ]]; then
    kill "$port_forward_pid" 2>/dev/null || true
    wait "$port_forward_pid" 2>/dev/null || true
  fi
  rm -f "$input_file" "$output_file" "$log_file"
}
trap cleanup EXIT

command -v aws >/dev/null || { echo "AWS CLI é necessário para este smoke test" >&2; exit 1; }
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
export AWS_EC2_METADATA_DISABLED=true

start_port_forward() {
  : >"$log_file"
  kubectl --namespace "$namespace" port-forward --address 127.0.0.1 \
    "service/${service}" "${local_port}:9090" >"$log_file" 2>&1 &
  port_forward_pid=$!

  local ready=false
  local deadline=$((SECONDS + 120))
  # SECONDS is integer-valued, so reserve one second to keep curl within the deadline.
  while (( SECONDS < deadline )); do
    local remaining=$((deadline - SECONDS - 1))
    if (( remaining <= 0 )); then
      break
    fi
    if curl --silent --fail --connect-timeout 2 --max-time "$remaining" \
      "${endpoint}/" >/dev/null; then
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
    echo "S3Mock não respondeu em 120 segundos" >&2
    exit 1
  fi
}

stop_port_forward() {
  if [[ -n "${port_forward_pid:-}" ]]; then
    kill "$port_forward_pid" 2>/dev/null || true
    wait "$port_forward_pid" 2>/dev/null || true
    port_forward_pid=""
  fi
}

start_port_forward

aws s3api head-bucket --bucket "$bucket" --endpoint-url "$endpoint"
printf 'S3Mock local\n' >"$input_file"
aws s3api put-object --bucket "$bucket" --key "$key" --body "$input_file" --endpoint-url "$endpoint" >/dev/null
stop_port_forward
kubectl --namespace "$namespace" delete pod --selector=app=s3mock --wait=true --timeout=120s
kubectl --namespace "$namespace" rollout status "deployment/${service}" --timeout=120s
start_port_forward
aws s3api get-object --bucket "$bucket" --key "$key" "$output_file" --endpoint-url "$endpoint" >/dev/null
cmp "$input_file" "$output_file"
echo "S3Mock: objeto sobreviveu ao reinício do Pod e foi baixado com sucesso"
