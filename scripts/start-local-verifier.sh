#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
compose_file="$repo_dir/docker/docker-compose.local.yml"
cert_script="$repo_dir/docker/local/generate-local-cert.sh"
cert_file="$repo_dir/docker/local/certs/haproxy-local.pem"
cert_crt_file="$repo_dir/docker/local/certs/localhost.crt"
ui_repo_dir=$(CDPATH= cd -- "$repo_dir/.." && pwd)/eudi-web-verifier
issuer_frontend_repo_dir=$(CDPATH= cd -- "$repo_dir/.." && pwd)/eudi-srv-web-issuing-frontend-eudiw-py
issuer_shared_cert_default="$issuer_frontend_repo_dir/server.crt"
issuer_shared_key_default="$issuer_frontend_repo_dir/server.key"
detected_lan_ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)

if [ -n "$detected_lan_ip" ]; then
  public_host_default=$detected_lan_ip
else
  public_host_default=localhost
fi

VERIFIER_PUBLIC_HOST=${VERIFIER_PUBLIC_HOST:-$public_host_default}
VERIFIER_PUBLIC_URL=${VERIFIER_PUBLIC_URL:-https://$VERIFIER_PUBLIC_HOST}
VERIFIER_PUBLIC_IP=${VERIFIER_PUBLIC_IP:-$detected_lan_ip}

if [ -z "${VERIFIER_SHARED_CERT_FILE:-}" ] && [ -z "${VERIFIER_SHARED_KEY_FILE:-}" ]; then
  if [ -f "$issuer_shared_cert_default" ] && [ -f "$issuer_shared_key_default" ]; then
    VERIFIER_SHARED_CERT_FILE="$issuer_shared_cert_default"
    VERIFIER_SHARED_KEY_FILE="$issuer_shared_key_default"
  fi
fi

export VERIFIER_PUBLIC_HOST VERIFIER_PUBLIC_URL VERIFIER_PUBLIC_IP
export VERIFIER_SHARED_CERT_FILE VERIFIER_SHARED_KEY_FILE

if ! command -v docker >/dev/null 2>&1; then
  printf 'docker is required\n' >&2
  exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
  printf 'openssl is required\n' >&2
  exit 1
fi

if [ ! -d "$ui_repo_dir" ]; then
  printf 'UI repository not found at %s\n' "$ui_repo_dir" >&2
  exit 1
fi

needs_cert_regen=false

if [ ! -f "$cert_file" ] || [ ! -f "$cert_crt_file" ]; then
  needs_cert_regen=true
elif [ -n "${VERIFIER_SHARED_CERT_FILE:-}" ] && ! cmp -s "$VERIFIER_SHARED_CERT_FILE" "$cert_crt_file"; then
  needs_cert_regen=true
elif ! openssl x509 -in "$cert_crt_file" -noout -ext subjectAltName 2>/dev/null | grep -Fq "$VERIFIER_PUBLIC_HOST"; then
  needs_cert_regen=true
elif [ -n "$VERIFIER_PUBLIC_IP" ] && ! openssl x509 -in "$cert_crt_file" -noout -ext subjectAltName 2>/dev/null | grep -Fq "$VERIFIER_PUBLIC_IP"; then
  needs_cert_regen=true
fi

if [ "$needs_cert_regen" = true ]; then
  "$cert_script"
fi

cd "$repo_dir"

docker compose -f "$compose_file" up -d --build

if [ "$needs_cert_regen" = true ]; then
  docker restart verifier-haproxy >/dev/null
fi

cat <<EOF
Verifier stack is starting.

UI and public verifier URL:
  ${VERIFIER_PUBLIC_URL}

Direct service ports:
  http://localhost:8080
  http://localhost:4300

TLS certificate source:
  ${VERIFIER_SHARED_CERT_FILE:-$cert_crt_file}

If your browser or phone warns about the local certificate, trust docker/local/certs/localhost.crt for local development.
EOF