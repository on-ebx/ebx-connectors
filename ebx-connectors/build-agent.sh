#!/usr/bin/env bash
# Builds dist/on-ebx-agent.jar: the package com.on.ebx.agent on its own (MCP client, and the tool-use
# loop to come), for the sibling modules that embed it in their WEB-INF/lib (ebx-mdm-factory). Pure JDK
# and Jackson: nothing of com.on.ebx.connect, no EBX class. Java 8 bytecode.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FASTTRACK_DIR="$(cd "$HERE/../.." && pwd)"
LB="${EBX_LIB_DIR:-$FASTTRACK_DIR/ebx-ps-fasttrack-server/lib}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# Jackson is compiled against the copies the war ships (runtime-libs/), not against whatever the server lib holds (audit X3-07)
RT="$HERE/runtime-libs"
if [[ ! -f "$RT/jackson-databind-2.18.12.jar" ]]; then
	printf 'Missing Jackson under %s (runtime-libs/jackson-*-2.18.12.jar)\n' "$RT" >&2
	exit 2
fi

JAVA_HOME_VALUE="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME_VALUE" && -x /usr/libexec/java_home ]]; then
	JAVA_HOME_VALUE="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
JAVAC_BIN="${JAVA_HOME_VALUE:+$JAVA_HOME_VALUE/bin/}javac"
JAR_BIN="${JAVA_HOME_VALUE:+$JAVA_HOME_VALUE/bin/}jar"
# Jackson only on the classpath: a dependency on anything else fails the build, which is the point
CP="$RT/jackson-core-2.18.12.jar:$RT/jackson-databind-2.18.12.jar:$RT/jackson-annotations-2.18.12.jar"
"$JAVAC_BIN" --release 8 -encoding UTF-8 -nowarn -Xlint:none -cp "$CP" -d "$OUT" "$HERE"/src/main/java/com/on/ebx/agent/*.java
# reproducible (audit F3-100, 1.4.2): see tools/jar_repro.sh. EBX_JAR_OUT_DIR is where the jar goes (default dist/); tools/package.sh sets it.
source "$HERE/tools/jar_repro.sh"
EPOCH="$(repro_epoch "$HERE")"
VERSION="$(repro_version "$EPOCH")"
DIST="${EBX_JAR_OUT_DIR:-$HERE/dist}"
mkdir -p "$DIST"
printf 'Manifest-Version: 1.0\nCreated-By: ebx-connectors tools/jar_repro.sh\nImplementation-Title: on-ebx-agent\nImplementation-Version: %s\n' "$VERSION" > "$OUT/MANIFEST.MF"
repro_jar "$JAR_BIN" "$DIST/on-ebx-agent.jar" "$OUT/MANIFEST.MF" "$EPOCH" "$OUT"
echo "built $DIST/on-ebx-agent.jar ($VERSION): $(unzip -l "$DIST/on-ebx-agent.jar" | grep -c '\.class$') classes"
