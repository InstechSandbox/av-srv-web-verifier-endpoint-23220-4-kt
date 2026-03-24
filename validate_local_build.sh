#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$repo_dir"

gradle_user_home=${GRADLE_USER_HOME:-$repo_dir/.gradle-validate}

find_java17_home() {
	if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
		version=$($JAVA_HOME/bin/java -version 2>&1 | awk -F '[\".]' '/version/ {print $2; exit}')
		if [ "$version" = "17" ]; then
			printf '%s\n' "$JAVA_HOME"
			return
		fi
	fi

	if command -v /usr/libexec/java_home >/dev/null 2>&1; then
		if java17_home=$(/usr/libexec/java_home -v 17 2>/dev/null); then
			printf '%s\n' "$java17_home"
			return
		fi
	fi

	for candidate in \
		"$HOME/.jdk"/jdk-17*/jdk-17*+*/Contents/Home \
		"$HOME/.gradle/jdks"/*17*/Contents/Home; do
		if [ -x "$candidate/bin/java" ]; then
			printf '%s\n' "$candidate"
			return
		fi
	done

	printf 'Java 17 is required for verifier backend validation. Install JDK 17 and rerun.\n' >&2
	exit 2
}

JAVA_HOME=$(find_java17_home)
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

export GRADLE_USER_HOME="$gradle_user_home"

bash -n scripts/start-local-verifier.sh scripts/stop-local-verifier.sh scripts/generate-emulator-deeplink.sh docker/local/generate-local-cert.sh
./gradlew --no-daemon clean test bootJar

printf 'Validated verifier backend build with GRADLE_USER_HOME=%s\n' "$GRADLE_USER_HOME"