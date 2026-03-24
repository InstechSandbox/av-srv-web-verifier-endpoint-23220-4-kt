#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
detected_lan_ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)

if [ -n "${VERIFIER_PUBLIC_HOST:-}" ]; then
  public_host=$VERIFIER_PUBLIC_HOST
elif [ -n "$detected_lan_ip" ]; then
  public_host=$detected_lan_ip
else
  public_host=localhost
fi

VERIFIER_PUBLIC_URL=${VERIFIER_PUBLIC_URL:-https://$public_host}
ADB_BIN=${ADB_BIN:-/Users/bg/Library/Android/sdk/platform-tools/adb}
run_adb=false

if [ "${1:-}" = "--run" ]; then
  run_adb=true
fi

payload='{
  "dcql_query": {
    "credentials": [
      {
        "id": "query_0",
        "format": "mso_mdoc",
        "meta": {
          "doctype_value": "eu.europa.ec.eudi.pid.1"
        },
        "claims": [
          {
            "path": ["eu.europa.ec.eudi.pid.1", "family_name"]
          }
        ]
      }
    ],
    "credential_sets": [
      {
        "options": [["query_0"]],
        "purpose": "We need to verify your identity"
      }
    ]
  },
  "nonce": "nonce",
  "jar_mode": "by_reference",
  "request_uri_method": "get",
  "profile": "HAIP",
  "authorization_request_scheme": "haip-vp"
}'

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
deeplink = "haip-vp://%s?" % public_host + urllib.parse.urlencode({
    "client_id": obj["client_id"],
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
print("deeplink=" + deeplink)
print("adb_args=" + shlex.join(adb_args))
' "$response" "$public_host")

printf '%s\n' "$parsed"

if [ "$run_adb" = true ]; then
  deeplink=$(printf '%s\n' "$parsed" | awk -F= '/^deeplink=/{print substr($0,10)}')

  if [ -z "$deeplink" ]; then
    printf 'Failed to derive deeplink from verifier response\n' >&2
    exit 1
  fi

  "$ADB_BIN" shell "am start -W -a android.intent.action.VIEW -d '$deeplink'"
fi