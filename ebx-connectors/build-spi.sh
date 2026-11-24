#!/usr/bin/env bash
# Builds dist/ebx-connectors-spi.jar: the compile-time contract for third-party connectors
# (package com.on.ebx.connect.spi + the AbstractRestConnector / HTTP / JSON helpers).
# Java 8 bytecode, so that any extension compiled against it runs on the module.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FASTTRACK_DIR="$(cd "$HERE/../.." && pwd)"
LB="${EBX_LIB_DIR:-$FASTTRACK_DIR/ebx-ps-fasttrack-server/lib}"
SRC="$HERE/src/main/java"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT" "${SRCOUT:-}"' EXIT

# the EBX API jar: ebx.jar on a standard ON EBX installation, ebx-lib.jar on a FastTrack one (same bytes) - audit X3-07
EBX_API_JAR=""
for candidate in "$LB/ebx.jar" "$LB/ebx-lib.jar"; do
	if [[ -f "$candidate" ]]; then EBX_API_JAR="$candidate"; break; fi
done
if [[ -z "$EBX_API_JAR" ]]; then
	printf 'Missing EBX runtime libraries under %s: neither ebx.jar (standard installation) nor ebx-lib.jar (FastTrack) is there (set EBX_LIB_DIR)\n' "$LB" >&2
	exit 2
fi
# Jackson is compiled against the copies the war ships (runtime-libs/), not against whatever the server lib holds
RT="$HERE/runtime-libs"

JAVA_HOME_VALUE="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME_VALUE" && -x /usr/libexec/java_home ]]; then
	JAVA_HOME_VALUE="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
JAVAC_BIN="${JAVA_HOME_VALUE:+$JAVA_HOME_VALUE/bin/}javac"
JAR_BIN="${JAVA_HOME_VALUE:+$JAVA_HOME_VALUE/bin/}jar"
CP="$EBX_API_JAR:$RT/jackson-core-2.18.12.jar:$RT/jackson-databind-2.18.12.jar:$RT/jackson-annotations-2.18.12.jar"
FILES=("$SRC"/com/on/ebx/connect/spi/*.java "$SRC/com/on/ebx/connect/core/AbstractRestConnector.java" "$SRC/com/on/ebx/connect/core/HttpJsonClient.java" "$SRC/com/on/ebx/connect/core/HttpExchangeStats.java" "$SRC/com/on/ebx/connect/core/HttpRetryPolicy.java" "$SRC/com/on/ebx/connect/core/RestSchemaRefresh.java" "$SRC/com/on/ebx/connect/core/Json.java" "$SRC/com/on/ebx/connect/core/OAuth2.java" "$SRC/com/on/ebx/connect/core/Tls.java" "$SRC/com/on/ebx/connect/core/Secrets.java" "$SRC/com/on/ebx/connect/core/SecretResolvers.java" "$SRC/com/on/ebx/connect/core/AwsCredentials.java" "$SRC/com/on/ebx/connect/core/AwsSigV4.java" "$SRC/com/on/ebx/connect/core/Jwt.java" "$SRC/com/on/ebx/connect/core/Hashing.java" "$SRC/com/on/ebx/connect/core/Webhooks.java" "$SRC/com/on/ebx/connect/core/Egress.java" "$SRC/com/on/ebx/connect/core/Redaction.java")
"$JAVAC_BIN" --release 8 -nowarn -Xlint:none -cp "$CP" -d "$OUT" "${FILES[@]}"
# reproducible (audit F3-100, 1.4.2): see tools/jar_repro.sh. EBX_JAR_OUT_DIR is where the jars go (default dist/); tools/package.sh sets it.
source "$HERE/tools/jar_repro.sh"
EPOCH="$(repro_epoch "$HERE")"
VERSION="$(repro_version "$EPOCH")"
DIST="${EBX_JAR_OUT_DIR:-$HERE/dist}"
mkdir -p "$DIST"
printf 'Manifest-Version: 1.0\nCreated-By: ebx-connectors tools/jar_repro.sh\nImplementation-Title: ebx-connectors-spi\nImplementation-Version: %s\n' "$VERSION" > "$OUT/MANIFEST.MF"
repro_jar "$JAR_BIN" "$DIST/ebx-connectors-spi.jar" "$OUT/MANIFEST.MF" "$EPOCH" "$OUT"
# sources, for the IDE of the extension developer
SRCOUT="$(mktemp -d)"; mkdir -p "$SRCOUT/com/on/ebx/connect/spi" "$SRCOUT/com/on/ebx/connect/core"
cp "$SRC"/com/on/ebx/connect/spi/*.java "$SRCOUT/com/on/ebx/connect/spi/"
cp "$SRC/com/on/ebx/connect/core/AbstractRestConnector.java" "$SRC/com/on/ebx/connect/core/HttpJsonClient.java" "$SRC/com/on/ebx/connect/core/HttpExchangeStats.java" "$SRC/com/on/ebx/connect/core/HttpRetryPolicy.java" "$SRC/com/on/ebx/connect/core/RestSchemaRefresh.java" "$SRC/com/on/ebx/connect/core/Json.java" "$SRC/com/on/ebx/connect/core/OAuth2.java" "$SRC/com/on/ebx/connect/core/Tls.java" "$SRC/com/on/ebx/connect/core/Secrets.java" "$SRC/com/on/ebx/connect/core/SecretResolvers.java" "$SRC/com/on/ebx/connect/core/AwsCredentials.java" "$SRC/com/on/ebx/connect/core/AwsSigV4.java" "$SRC/com/on/ebx/connect/core/Jwt.java" "$SRC/com/on/ebx/connect/core/Hashing.java" "$SRC/com/on/ebx/connect/core/Webhooks.java" "$SRC/com/on/ebx/connect/core/Egress.java" "$SRC/com/on/ebx/connect/core/Redaction.java" "$SRCOUT/com/on/ebx/connect/core/"
printf 'Manifest-Version: 1.0\nCreated-By: ebx-connectors tools/jar_repro.sh\n' > "$SRCOUT/MANIFEST.MF"
repro_jar "$JAR_BIN" "$DIST/ebx-connectors-spi-sources.jar" "$SRCOUT/MANIFEST.MF" "$EPOCH" "$SRCOUT"
echo "built $DIST/ebx-connectors-spi.jar ($VERSION): $(unzip -l "$DIST/ebx-connectors-spi.jar" | grep -c '\.class$') classes"
