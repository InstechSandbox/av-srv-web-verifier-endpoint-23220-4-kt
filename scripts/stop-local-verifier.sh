#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
compose_file="$repo_dir/docker/docker-compose.local.yml"

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

if ! command -v docker >/dev/null 2>&1; then
  printf 'docker is required\n' >&2
  exit 1
fi

if [ -n "${COMPOSE_PROJECT_NAME:-}" ]; then
  export COMPOSE_PROJECT_NAME
fi

docker compose -f "$compose_file" down