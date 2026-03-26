#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$repo_dir"

gradle_user_home=${GRADLE_USER_HOME:-$repo_dir/.gradle-validate}

java_major_version() {
	"$1/bin/java" -version 2>&1 | awk -F '[".]' '/version/ {print $2; exit}'
}

is_java17_home() {
	[ -x "$1/bin/java" ] && [ "$(java_major_version "$1")" = "17" ]
}

find_java17_home() {
	if [ -n "${JAVA_HOME:-}" ] && is_java17_home "$JAVA_HOME"; then
			printf '%s\n' "$JAVA_HOME"
			return
	fi

	for candidate in \
		"$HOME/.jdk"/jdk-17*/jdk-17*+*/Contents/Home \
		"$HOME/.gradle/jdks"/*17*/Contents/Home; do
		if is_java17_home "$candidate"; then
			printf '%s\n' "$candidate"
			return
		fi
	done

	if command -v /usr/libexec/java_home >/dev/null 2>&1; then
		if java17_home=$(/usr/libexec/java_home -v 17 2>/dev/null); then
			if is_java17_home "$java17_home"; then
				printf '%s\n' "$java17_home"
				return
			fi
		fi
	fi

	printf 'Java 17 is required for verifier backend validation. Install JDK 17 and rerun.\n' >&2
	exit 2
}

JAVA_HOME=$(find_java17_home)
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

export GRADLE_USER_HOME="$gradle_user_home"

wait_for_http() {
	url="$1"
	attempts="${2:-60}"
	while [ "$attempts" -gt 0 ]; do
		if response=$(curl -fsS "$url" 2>/dev/null); then
			printf '%s' "$response"
			return 0
		fi
		sleep 2
		attempts=$((attempts - 1))
	done
	return 1
}

kill_pid_if_running() {
	pid="$1"
	if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
		kill "$pid" 2>/dev/null || true
		wait "$pid" 2>/dev/null || true
	fi
}

kill_listener_on_port() {
	port="$1"
	pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
	if [ -n "$pids" ]; then
		kill $pids 2>/dev/null || true
	fi
}

bash -n scripts/start-local-verifier.sh scripts/stop-local-verifier.sh scripts/generate-verifier-deeplink.sh docker/local/generate-local-cert.sh
./gradlew --no-daemon clean test bootJar

verifier_smoke_port=${VERIFIER_SMOKE_PORT:-18080}
verifier_jar=$(find "$repo_dir/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*plain.jar' | head -n 1)

if [ -z "$verifier_jar" ]; then
	printf 'Verifier backend smoke test failed: bootJar artifact not found\n' >&2
	exit 1
fi

smoke_log=$(mktemp "$repo_dir/verifier-smoke.XXXXXX.log")
java -jar "$verifier_jar" --server.port="$verifier_smoke_port" --verifier.publicUrl="http://127.0.0.1:$verifier_smoke_port" >"$smoke_log" 2>&1 &
smoke_pid=$!

cleanup_smoke() {
	kill_pid_if_running "$smoke_pid"
	rm -f "$smoke_log"
}

trap cleanup_smoke EXIT INT TERM

if ! smoke_response=$(wait_for_http "http://127.0.0.1:$verifier_smoke_port/actuator/health" 90) || ! printf '%s' "$smoke_response" | grep -q '"status":"UP"'; then
	kill_listener_on_port "$verifier_smoke_port"
	cat "$smoke_log" >&2
	printf 'Verifier backend smoke test failed\n' >&2
	exit 1
fi

kill_pid_if_running "$smoke_pid"
trap - EXIT INT TERM
rm -f "$smoke_log"

printf 'Validated verifier backend build and smoke test with GRADLE_USER_HOME=%s\n' "$GRADLE_USER_HOME"