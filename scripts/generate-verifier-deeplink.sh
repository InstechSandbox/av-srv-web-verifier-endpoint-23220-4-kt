#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
detected_lan_ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)
issuer_backend_repo_dir=$(CDPATH= cd -- "$repo_dir/.." && pwd)/eudi-srv-web-issuing-eudiw-py
pid_issuer_cert_dir_default="$issuer_backend_repo_dir/local/cert"

detect_adb_bin() {
  for candidate in \
    "${ADB_BIN:-}" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb"
  do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return
    fi
  done

  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return
  fi

  printf '%s\n' "$HOME/Library/Android/sdk/platform-tools/adb"
}

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

if [ -n "${VERIFIER_PUBLIC_HOST:-}" ]; then
  public_host=$VERIFIER_PUBLIC_HOST
elif [ -n "$detected_lan_ip" ]; then
  public_host=$detected_lan_ip
else
  public_host=localhost
fi

verifier_tls_host_port=${VERIFIER_TLS_HOST_PORT:-443}

if [ "$verifier_tls_host_port" = "443" ]; then
  verifier_public_port_suffix=
else
  verifier_public_port_suffix=:$verifier_tls_host_port
fi

VERIFIER_PUBLIC_URL=${VERIFIER_PUBLIC_URL:-https://$public_host$verifier_public_port_suffix}
ADB_BIN=${ADB_BIN:-$(detect_adb_bin)}
ANDROID_SERIAL=${ANDROID_SERIAL:-}
run_adb=false

if [ -z "${VERIFIER_PID_ISSUER_CERT_DIR:-}" ] && [ -d "$pid_issuer_cert_dir_default" ]; then
  VERIFIER_PID_ISSUER_CERT_DIR="$pid_issuer_cert_dir_default"
fi

issuer_chain_file=
if [ -n "${VERIFIER_PID_ISSUER_CERT_DIR:-}" ]; then
  issuer_chain_file=$(resolve_first_existing_path \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PIDIssuerCALocalUT.pem" \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PIDIssuerCALocalUT.pem" \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PIDIssuerCAUT01.pem" \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PID-DS-LOCAL-UT_cert.pem" \
    "$VERIFIER_PID_ISSUER_CERT_DIR/PID-DS-0001_UT_cert.pem")
fi

issuer_chain_payload=
if [ -n "$issuer_chain_file" ] && [ -f "$issuer_chain_file" ]; then
  issuer_chain_payload=$(cat "$issuer_chain_file")
fi

adb_cmd() {
  if [ -n "$ANDROID_SERIAL" ]; then
    "$ADB_BIN" -s "$ANDROID_SERIAL" "$@"
    return
  fi

  "$ADB_BIN" "$@"
}

if [ "${1:-}" = "--run" ]; then
  run_adb=true
fi

pid_presentation_format=${PID_PRESENTATION_FORMAT:-jwt}

case "$pid_presentation_format" in
  jwt|sdjwt)
    base_payload=$(cat <<'EOF'
{
  "dcql_query": {
    "credentials": [
      {
        "id": "query_sdjwt",
        "format": "dc+sd-jwt",
        "meta": {
          "vct_values": ["urn:eudi:pid:1"]
        },
        "claims": [
          {
            "path": ["family_name"]
          },
          {
            "path": ["given_name"]
          }
        ]
      }
    ]
  },
  "nonce": "nonce",
  "jar_mode": "by_reference",
  "request_uri_method": "get",
  "profile": "HAIP",
  "authorization_request_scheme": "haip-vp"
}
EOF
)
    ;;
  mdoc)
    base_payload=$(cat <<'EOF'
{
  "dcql_query": {
    "credentials": [
      {
        "id": "query_mdoc",
        "format": "mso_mdoc",
        "meta": {
          "doctype_value": "eu.europa.ec.eudi.pid.1"
        },
        "claims": [
          {
            "path": ["eu.europa.ec.eudi.pid.1", "family_name"]
          },
          {
            "path": ["eu.europa.ec.eudi.pid.1", "given_name"]
          }
        ]
      }
    ]
  },
  "nonce": "nonce",
  "jar_mode": "by_reference",
  "request_uri_method": "get",
  "profile": "HAIP",
  "authorization_request_scheme": "haip-vp"
}
EOF
)
    ;;
  dual)
    base_payload=$(cat <<'EOF'
{
  "dcql_query": {
    "credentials": [
      {
        "id": "query_mdoc",
        "format": "mso_mdoc",
        "meta": {
          "doctype_value": "eu.europa.ec.eudi.pid.1"
        },
        "claims": [
          {
            "path": ["eu.europa.ec.eudi.pid.1", "family_name"]
          },
          {
            "path": ["eu.europa.ec.eudi.pid.1", "given_name"]
          }
        ]
      },
      {
        "id": "query_sdjwt",
        "format": "dc+sd-jwt",
        "meta": {
          "vct_values": ["urn:eudi:pid:1"]
        },
        "claims": [
          {
            "path": ["family_name"]
          },
          {
            "path": ["given_name"]
          }
        ]
      }
    ],
    "credential_sets": [
      {
        "options": [["query_mdoc"], ["query_sdjwt"]]
      }
    ]
  },
  "nonce": "nonce",
  "jar_mode": "by_reference",
  "request_uri_method": "get",
  "profile": "HAIP",
  "authorization_request_scheme": "haip-vp"
}
EOF
)
    ;;
  *)
    printf 'Unsupported PID_PRESENTATION_FORMAT: %s\n' "$pid_presentation_format" >&2
    printf 'Expected one of: jwt, sdjwt, mdoc, dual\n' >&2
    exit 1
    ;;
esac

payload=$(python3 - <<'PY' "$base_payload" "$issuer_chain_payload"
import json
import sys

payload = json.loads(sys.argv[1])
issuer_chain = sys.argv[2]
if issuer_chain:
    payload["issuer_chain"] = issuer_chain
print(json.dumps(payload))
PY
)

response=$(curl -sk -H 'Content-Type: application/json' -d "$payload" "$VERIFIER_PUBLIC_URL/ui/presentations")

parsed=$(python3 -c '
import json
import shlex
import sys
import urllib.parse

obj = json.loads(sys.argv[1])
public_host = sys.argv[2]
required = ["transaction_id", "client_id", "request_uri"]
missing = [key for key in required if key not in obj]
if missing:
    raise SystemExit("Verifier response missing fields: %s\n%s" % (", ".join(missing), json.dumps(obj, indent=2)))

request_uri_method = obj.get("request_uri_method", "get")
deeplink = obj.get("authorization_request_uri")
if not deeplink:
  deeplink = "haip-vp://%s?" % public_host + urllib.parse.urlencode({
    "client_id": obj["client_id"],
    "response_type": "vp_token",
    "request_uri": obj["request_uri"],
    "request_uri_method": request_uri_method,
  })
adb_args = [
    "shell",
    "am",
    "start",
    "-W",
    "-a",
    "android.intent.action.VIEW",
    "-d",
    deeplink,
]

print("transaction_id=" + obj["transaction_id"])
print("client_id=" + obj["client_id"])
print("request_uri=" + obj["request_uri"])
print("request_uri_method=" + request_uri_method)
if obj.get("authorization_request_uri"):
  print("authorization_request_uri=" + obj["authorization_request_uri"])
print("pid_presentation_format=" + sys.argv[3])
print("issuer_chain_included=" + sys.argv[4])
print("deeplink=" + deeplink)
print("adb_args=" + shlex.join(adb_args))
' "$response" "$public_host" "$pid_presentation_format" "$( [ -n "$issuer_chain_payload" ] && printf true || printf false )")

printf '%s\n' "$parsed"

deeplink=$(printf '%s\n' "$parsed" | awk -F= '/^deeplink=/{print substr($0,10)}')

if [ -z "$deeplink" ]; then
  printf 'Failed to derive deeplink from verifier response\n' >&2
  exit 1
fi

if [ -n "$ANDROID_SERIAL" ]; then
  adb_prefix="$ADB_BIN -s $ANDROID_SERIAL"
else
  adb_prefix="$ADB_BIN"
fi

printf 'adb_shell_command=%s shell "am start -W -a android.intent.action.VIEW -d '\''%s'\''"\n' "$adb_prefix" "$deeplink"

if [ "$run_adb" = true ]; then
  adb_cmd shell "am start -W -a android.intent.action.VIEW -d '$deeplink'"
fi