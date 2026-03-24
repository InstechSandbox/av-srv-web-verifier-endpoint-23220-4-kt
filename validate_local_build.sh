#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$repo_dir"

gradle_user_home=${GRADLE_USER_HOME:-$repo_dir/.gradle-validate}

export GRADLE_USER_HOME="$gradle_user_home"

bash -n scripts/start-local-verifier.sh scripts/stop-local-verifier.sh scripts/generate-emulator-deeplink.sh docker/local/generate-local-cert.sh
./gradlew --no-daemon clean test bootJar

printf 'Validated verifier backend build with GRADLE_USER_HOME=%s\n' "$GRADLE_USER_HOME"