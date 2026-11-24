#!/usr/bin/env bash
# Compiles the extension against ebx-connectors-spi.jar and packages it with its META-INF/services file.
# Usage: ./build.sh [path/to/ebx-connectors-spi.jar]   (default: lib/ebx-connectors-spi.jar)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SPI="${1:-$HERE/lib/ebx-connectors-spi.jar}"
FASTTRACK_DIR="$(cd "$HERE/../.." && pwd)"
LB="${EBX_LIB_DIR:-$FASTTRACK_DIR/ebx-ps-fasttrack-server/lib}" # EBX + Jackson runtime jars

if [[ ! -f "$SPI" ]]; then
	printf 'Missing connector SPI jar: %s\n' "$SPI" >&2
	exit 2
fi
# the EBX API jar: ebx.jar on a standard ON EBX installation, ebx-lib.jar on a FastTrack one (same bytes) - audit X3-07
EBX_API_JAR=""
for candidate in "$LB/ebx.jar" "$LB/ebx-lib.jar"; do
	if [[ -f "$candidate" ]]; then EBX_API_JAR="$candidate"; break; fi
done
if [[ -z "$EBX_API_JAR" ]]; then
	printf 'Missing EBX runtime libraries under %s: neither ebx.jar (standard installation) nor ebx-lib.jar (FastTrack) is there (set EBX_LIB_DIR)\n' "$LB" >&2
	exit 2
fi
# Jackson: the copies the module's war ships when this folder sits beside ebx-connectors/, else the server lib's
RT="$HERE/../ebx-connectors/runtime-libs"
if [[ -f "$RT/jackson-databind-2.14.2.jar" ]]; then
	JACKSON="$RT/jackson-core-2.14.2.jar:$RT/jackson-databind-2.14.2.jar:$RT/jackson-annotations-2.14.2.jar"
else
	JACKSON="$LB/jackson-core.jar:$LB/jackson-databind.jar:$LB/jackson-annotations.jar"
fi

JAVA_HOME_VALUE="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME_VALUE" && -x /usr/libexec/java_home ]]; then
	JAVA_HOME_VALUE="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
JAVAC_BIN="${JAVA_HOME_VALUE:+$JAVA_HOME_VALUE/bin/}javac"
JAR_BIN="${JAVA_HOME_VALUE:+$JAVA_HOME_VALUE/bin/}jar"
CP="$SPI:$EBX_API_JAR:$JACKSON"
OUT="$HERE/target/classes"
rm -rf "$OUT"; mkdir -p "$OUT"
FILES=()
while IFS= read -r file; do
	FILES+=("$file")
done < <(find "$HERE/src/main/java" -name '*.java' -type f -print)
if [[ ${#FILES[@]} -eq 0 ]]; then
	printf 'No extension Java sources found under %s\n' "$HERE/src/main/java" >&2
	exit 2
fi
"$JAVAC_BIN" --release 8 -nowarn -Xlint:none -cp "$CP" -d "$OUT" "${FILES[@]}"
cp -R "$HERE/src/main/resources/." "$OUT/"
"$JAR_BIN" cf "$HERE/target/ebx-connectors-extension-sample.jar" -C "$OUT" .
echo "built $HERE/target/ebx-connectors-extension-sample.jar"
unzip -l "$HERE/target/ebx-connectors-extension-sample.jar" | grep -E 'class$|services/'
