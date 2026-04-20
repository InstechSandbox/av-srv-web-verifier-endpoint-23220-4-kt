#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
compose_file="$repo_dir/docker/docker-compose.local.yml"
cert_script="$repo_dir/docker/local/generate-local-cert.sh"
cert_file="$repo_dir/docker/local/certs/haproxy-local.pem"
cert_crt_file="$repo_dir/docker/local/certs/localhost.crt"
haproxy_template="$repo_dir/docker/haproxy.conf"
haproxy_generated="$repo_dir/docker/local/haproxy.generated.cfg"
ui_repo_dir=$(CDPATH= cd -- "$repo_dir/.." && pwd)/eudi-web-verifier
issuer_frontend_repo_dir=$(CDPATH= cd -- "$repo_dir/.." && pwd)/eudi-srv-web-issuing-frontend-eudiw-py
issuer_backend_repo_dir=$(CDPATH= cd -- "$repo_dir/.." && pwd)/eudi-srv-web-issuing-eudiw-py
issuer_shared_cert_default="$issuer_frontend_repo_dir/server.crt"
issuer_shared_key_default="$issuer_frontend_repo_dir/server.key"
pid_issuer_cert_dir_default="$issuer_backend_repo_dir/local/cert"
detected_lan_ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)

resolve_first_existing_path() {
  fallback=$1
  shift

  for candidate in "$@"; do
    if [ -f "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  printf '%s\n' "$fallback"
}

if [ -n "$detected_lan_ip" ]; then
  public_host_default=$detected_lan_ip
else
  public_host_default=localhost
fi

VERIFIER_TLS_HOST_PORT=${VERIFIER_TLS_HOST_PORT:-443}
VERIFIER_BACKEND_HOST_PORT=${VERIFIER_BACKEND_HOST_PORT:-8080}
VERIFIER_UI_HOST_PORT=${VERIFIER_UI_HOST_PORT:-4300}
VERIFIER_STACK_SUFFIX=${VERIFIER_STACK_SUFFIX:-}
VERIFIER_BACKEND_CONTAINER_NAME=${VERIFIER_BACKEND_CONTAINER_NAME:-verifier-backend${VERIFIER_STACK_SUFFIX}}
VERIFIER_UI_CONTAINER_NAME=${VERIFIER_UI_CONTAINER_NAME:-verifier-ui${VERIFIER_STACK_SUFFIX}}
VERIFIER_HAPROXY_CONTAINER_NAME=${VERIFIER_HAPROXY_CONTAINER_NAME:-verifier-haproxy${VERIFIER_STACK_SUFFIX}}

derive_default_compose_project_name() {
  default_stack=true
  discriminator=${VERIFIER_STACK_SUFFIX:-}

  if [ "$VERIFIER_TLS_HOST_PORT" != "443" ] || [ "$VERIFIER_BACKEND_HOST_PORT" != "8080" ] || [ "$VERIFIER_UI_HOST_PORT" != "4300" ]; then
    default_stack=false
  fi

  if [ "$VERIFIER_BACKEND_CONTAINER_NAME" != "verifier-backend" ] || [ "$VERIFIER_UI_CONTAINER_NAME" != "verifier-ui" ] || [ "$VERIFIER_HAPROXY_CONTAINER_NAME" != "verifier-haproxy" ]; then
    default_stack=false
  fi

  if [ -z "$discriminator" ] && [ "$default_stack" != true ]; then
    discriminator="$VERIFIER_TLS_HOST_PORT-$VERIFIER_BACKEND_HOST_PORT-$VERIFIER_UI_HOST_PORT"
  fi

  if [ -n "$discriminator" ]; then
    printf '%s\n' "$discriminator" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' | awk 'NF { print "verifier-" $0 }'
  fi
}

if [ -z "${COMPOSE_PROJECT_NAME:-}" ]; then
  COMPOSE_PROJECT_NAME=$(derive_default_compose_project_name)
fi

if [ "$VERIFIER_TLS_HOST_PORT" = "443" ]; then
  verifier_public_port_suffix=
else
  verifier_public_port_suffix=:$VERIFIER_TLS_HOST_PORT
fi

VERIFIER_PUBLIC_HOST=${VERIFIER_PUBLIC_HOST:-$public_host_default}
VERIFIER_PUBLIC_URL=${VERIFIER_PUBLIC_URL:-https://$VERIFIER_PUBLIC_HOST$verifier_public_port_suffix}
VERIFIER_PUBLIC_IP=${VERIFIER_PUBLIC_IP:-$detected_lan_ip}
VERIFIER_IRISHLIFE_CUSTOMERBASEURL=${VERIFIER_IRISHLIFE_CUSTOMERBASEURL:-$VERIFIER_PUBLIC_URL}

if [ -z "${VERIFIER_SHARED_CERT_FILE:-}" ] && [ -z "${VERIFIER_SHARED_KEY_FILE:-}" ]; then
  if [ -f "$issuer_shared_cert_default" ] && [ -f "$issuer_shared_key_default" ]; then
    VERIFIER_SHARED_CERT_FILE="$issuer_shared_cert_default"
    VERIFIER_SHARED_KEY_FILE="$issuer_shared_key_default"
  fi
fi

if [ -z "${VERIFIER_PID_ISSUER_CERT_DIR:-}" ] && [ -d "$pid_issuer_cert_dir_default" ]; then
  VERIFIER_PID_ISSUER_CERT_DIR="$pid_issuer_cert_dir_default"
fi

if [ -n "${VERIFIER_PID_ISSUER_CERT_DIR:-}" ] && [ -z "${VERIFIER_IRISHLIFE_PIDISSUERCHAIN_PATH:-}" ]; then
  verifier_pid_issuer_chain_file=$(resolve_first_existing_path \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PIDIssuerCALocalUT.pem" \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PIDIssuerCALocalUT.pem" \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PIDIssuerCAUT01.pem" \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PID-DS-LOCAL-UT_cert.pem" \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PID-DS-0001_UT_cert.pem")
  VERIFIER_IRISHLIFE_PIDISSUERCHAIN_PATH="file:/opt/verifier/local-issuer-certs/$(basename "$verifier_pid_issuer_chain_file")"
fi

export VERIFIER_PUBLIC_HOST VERIFIER_PUBLIC_URL VERIFIER_PUBLIC_IP VERIFIER_IRISHLIFE_CUSTOMERBASEURL
export VERIFIER_PID_ISSUER_CERT_DIR VERIFIER_IRISHLIFE_PIDISSUERCHAIN_PATH
export VERIFIER_SHARED_CERT_FILE VERIFIER_SHARED_KEY_FILE
export VERIFIER_TLS_HOST_PORT VERIFIER_BACKEND_HOST_PORT VERIFIER_UI_HOST_PORT
export VERIFIER_BACKEND_CONTAINER_NAME VERIFIER_UI_CONTAINER_NAME VERIFIER_HAPROXY_CONTAINER_NAME
if [ -n "${COMPOSE_PROJECT_NAME:-}" ]; then
  export COMPOSE_PROJECT_NAME
fi

if [ ! -f "$haproxy_template" ]; then
  printf 'HAProxy template not found at %s\n' "$haproxy_template" >&2
  exit 1
fi

sed \
  -e "s/verifier-backend:8080/${VERIFIER_BACKEND_CONTAINER_NAME}:8080/g" \
  -e "s/verifier-ui:4300/${VERIFIER_UI_CONTAINER_NAME}:4300/g" \
  "$haproxy_template" > "$haproxy_generated"

VERIFIER_HAPROXY_CONFIG_FILE="$haproxy_generated"
export VERIFIER_HAPROXY_CONFIG_FILE

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
  docker restart "$VERIFIER_HAPROXY_CONTAINER_NAME" >/dev/null
fi

cat <<EOF
Verifier stack is starting.

Compose project:
  ${COMPOSE_PROJECT_NAME:-default}

UI and public verifier URL:
  ${VERIFIER_PUBLIC_URL}

Direct service ports:
  http://localhost:${VERIFIER_BACKEND_HOST_PORT}
  http://localhost:${VERIFIER_UI_HOST_PORT}

TLS certificate source:
  ${VERIFIER_SHARED_CERT_FILE:-$cert_crt_file}

Irish Life PID issuer chain source:
  ${VERIFIER_IRISHLIFE_PIDISSUERCHAIN_PATH:-not configured}

If your browser or phone warns about the local certificate, trust docker/local/certs/localhost.crt for local development.
EOF