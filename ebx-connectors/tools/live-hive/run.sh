#!/usr/bin/env bash
# Plays the HiveServer2 profiles (Cloudera Hive, Cloudera Impala over the Hive driver, Apache Spark SQL) against a real
# Apache Hive started in Docker. Not part of the offline suite: it needs Docker, the apache/hive image (about 1.15 GB to
# download) and the Hive JDBC driver, which the module does not ship.
#
#   HIVE_JDBC_JAR=/path/to/hive-jdbc-4.0.1-standalone.jar tools/live-hive/run.sh
#
# Environment: HIVE_JDBC_JAR (required), EBX_BUILD_DIR (classes built by build-test.sh, default target/baseline),
# HIVE_IMAGE (default apache/hive:4.0.1), HIVE_PORT (default 10000, bound to 127.0.0.1), KEEP=1 to leave the container up.
set -euo pipefail

module_dir="$(cd "$(dirname "$0")/../.." && pwd)"
build_dir="${EBX_BUILD_DIR:-$module_dir/target/baseline}"
image="${HIVE_IMAGE:-apache/hive:4.0.1}"
port="${HIVE_PORT:-10000}"
name="ebx-hive-live"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"

[[ -f "${HIVE_JDBC_JAR:-}" ]] || { printf 'Set HIVE_JDBC_JAR to the Hive JDBC standalone jar\n' >&2; exit 2; }
[[ -d "$build_dir/classes" && -d "$build_dir/test-classes" ]] || { printf 'No classes under %s: run build-test.sh first (or set EBX_BUILD_DIR)\n' "$build_dir" >&2; exit 2; }

started=0
if [[ -z "$(docker ps -q --filter "name=^${name}$")" ]]; then
	docker rm -f "$name" >/dev/null 2>&1 || true
	docker run -d --name "$name" --memory 3g -p "127.0.0.1:${port}:10000" -e SERVICE_NAME=hiveserver2 "$image" >/dev/null
	started=1
fi
stop() { if [[ "$started" == 1 && "${KEEP:-0}" != 1 ]]; then docker rm -f "$name" >/dev/null 2>&1 || true; fi; }
trap stop EXIT

ready=0
for _ in $(seq 1 60); do
	if docker exec "$name" beeline -u 'jdbc:hive2://localhost:10000/' -n ebx -e 'select 1' >/dev/null 2>&1; then ready=1; break; fi
	sleep 5
done
[[ "$ready" == 1 ]] || { printf 'HiveServer2 did not answer within 5 minutes\n' >&2; exit 1; }

docker cp "$module_dir/tools/live-hive/setup.sql" "$name:/tmp/ebx-setup.sql"
docker exec "$name" beeline -u 'jdbc:hive2://localhost:10000/' -n ebx --silent=true -f /tmp/ebx-setup.sql >/dev/null 2>&1

"$java_bin" -cp "$build_dir/test-classes:$build_dir/classes:$module_dir/runtime-libs/*:$HIVE_JDBC_JAR" \
	com.on.ebx.connect.live.HiveServer2LiveCheck "jdbc:hive2://localhost:${port}/default" ebxtest
