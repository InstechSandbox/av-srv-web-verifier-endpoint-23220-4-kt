#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cert_dir="$script_dir/certs"
key_file="$cert_dir/localhost.key"
cert_file="$cert_dir/localhost.crt"
pem_file="$cert_dir/haproxy-local.pem"
shared_cert_file=${VERIFIER_SHARED_CERT_FILE:-}
shared_key_file=${VERIFIER_SHARED_KEY_FILE:-}

public_host=${VERIFIER_PUBLIC_HOST:-localhost}
lan_ip=${VERIFIER_PUBLIC_IP:-}

if [ -z "$lan_ip" ]; then
  lan_ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)
fi

san_entries="DNS:localhost,IP:127.0.0.1"

case "$public_host" in
  localhost)
    ;;
  *[!0-9.]*)
    san_entries="$san_entries,DNS:$public_host"
    ;;
  *)
    san_entries="$san_entries,IP:$public_host"
    ;;
esac

if [ -n "$lan_ip" ] && [ "$lan_ip" != "127.0.0.1" ] && [ "$lan_ip" != "$public_host" ]; then
  san_entries="$san_entries,IP:$lan_ip"
fi

mkdir -p "$cert_dir"

if [ -n "$shared_cert_file" ] || [ -n "$shared_key_file" ]; then
  if [ -z "$shared_cert_file" ] || [ -z "$shared_key_file" ]; then
    printf 'Both VERIFIER_SHARED_CERT_FILE and VERIFIER_SHARED_KEY_FILE must be set together\n' >&2
    exit 1
  fi

  if [ ! -f "$shared_cert_file" ]; then
    printf 'Shared certificate file not found: %s\n' "$shared_cert_file" >&2
    exit 1
  fi

  if [ ! -f "$shared_key_file" ]; then
    printf 'Shared key file not found: %s\n' "$shared_key_file" >&2
    exit 1
  fi

  cp "$shared_cert_file" "$cert_file"
  cp "$shared_key_file" "$key_file"
  cat "$key_file" "$cert_file" > "$pem_file"

  printf 'Using shared certificate %s\n' "$shared_cert_file"
  exit 0
fi

openssl req \
  -x509 \
  -nodes \
  -newkey rsa:2048 \
  -sha256 \
  -days 365 \
  -keyout "$key_file" \
  -out "$cert_file" \
  -subj "/CN=$public_host" \
  -addext "subjectAltName=$san_entries"

cat "$key_file" "$cert_file" > "$pem_file"

printf 'Generated %s\n' "$pem_file"