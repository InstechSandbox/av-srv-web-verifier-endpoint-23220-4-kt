#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$repo_dir"

gradle_user_home=${GRADLE_USER_HOME:-$repo_dir/.gradle-validate}

if command -v /usr/libexec/java_home >/dev/null 2>&1; then
	if java17_home=$(/usr/libexec/java_home -v 17 2>/dev/null); then
		export JAVA_HOME="$java17_home"
		export PATH="$JAVA_HOME/bin:$PATH"
	else
		printf 'Java 17 is required for verifier backend validation. Install JDK 17 and rerun.\n' >&2
		exit 2
	fi
fi

export GRADLE_USER_HOME="$gradle_user_home"

bash -n scripts/start-local-verifier.sh scripts/stop-local-verifier.sh scripts/generate-emulator-deeplink.sh docker/local/generate-local-cert.sh
./gradlew --no-daemon clean test bootJar

printf 'Validated verifier backend build with GRADLE_USER_HOME=%s\n' "$GRADLE_USER_HOME"