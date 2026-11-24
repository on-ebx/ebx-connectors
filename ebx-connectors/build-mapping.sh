#!/usr/bin/env bash
# Builds dist/on-ebx-mapping.jar: the mapping engine (com.on.ebx.connect.core.MappingEngine and its
# transitive closure) on its own, for a sibling module (ebx-mdm-factory) to embed in its WEB-INF/lib
# and drive landing->staging transfers with Connectors' own rule pipelines, without depending on
# Connectors at runtime or compiling against the rest of com.on.ebx.connect. See
# docs/TARGET-MAPPING.md sec 1 and 5 (lot 0) in the ebx-mdm-factory-target-mapping worktree for why.
#
# The source list below is not the sec-1 list copied verbatim: that list was produced by a script
# that also matched class names inside comments, so it over-counted. This one was measured with
# javac itself (compile the seed classes with a jar-only classpath, so nothing else of the module can
# be silently pulled in as a source file; add a class only when javac's own "cannot find symbol"
# names it, drop nothing already required). Two classes the sec-1 list did NOT name turned out to be
# real, load-bearing dependencies:
#   - core/Expressions.java: MappingDefinition's constructor calls Expressions.isBlank(...). Pure
#     EBX-lib, no further com.on.ebx.connect reference beyond Transformations (already in the list).
#   - ebx/DatasetLocator.java: Transformations.table() (behind fk/lookup/golden/mdmState) calls
#     DatasetLocator.find(...) to resolve an explicit dataspace/dataset argument. Pure EBX-lib, no
#     further com.on.ebx.connect reference. It is the one file of this jar outside the core/spi
#     packages the sec-1 list expected.
# Nothing else needed adding: MappingResult, Transformation, Xpath, SampleValueSafety, SyncResult,
# TrustOracle, Hashing, GoldenRecords, MameGoldenRecords, spi/SynchronizationStrategy and RunIds
# (new in this lot, see below) compiled clean as pure EBX-lib/Jackson/JDK leaves.
#
# Decoupling runid (docs/TARGET-MAPPING.md sec 5, lot 0): Transformations used to call
# ConnectorService.currentRunId(), which would have dragged the whole EBX-integration surface (HTTP,
# secrets, the connector registry...) into this jar. core/RunIds.java is the tiny holder extracted
# out of ConnectorService for that one purpose; ConnectorService now delegates to it instead of
# keeping its own ThreadLocal. Transformations references RunIds only, never ConnectorService: this
# build FAILS if that ever regresses, because ConnectorService itself is not in the classpath (jars
# only, no other module source dir), so any accidental reference to it or to anything else of the
# module becomes a plain "cannot find symbol" — that failure is the point, exactly like build-agent.sh.
#
# Re-measured 2026-10-14 when the jar moved to Connectors main (the engine had grown since 2026-09-23:
# child-row preload, business-key index, per-run cache, watermarks, batch summaries). The new leaves,
# each named by javac's own "cannot find symbol": TransformationRunCache, TransformationPipeline,
# ImportBatchSummary, UnchangedRowDetector, RunLimits, ChildRowGroups, ChildRowPreload, FeedKeyLookup,
# BusinessKeyIndex, BusinessKeyPredicate, KeyPredicatePrimaryKey, Watermark, Redaction,
# spi/ConnectorConfig and spi/ConnectorException. Two edges were cut rather than followed, same idea as
# RunIds: FeedKeyLookup and ChildRowPreload called ConnectorService.locate/placeholderText/log (now in
# core/FeedLookups.java, ConnectorService delegates), and Redaction called Secrets.isPlaceholder (now in
# Redaction, Secrets delegates) - following either would have pulled every connector, or the secret
# resolvers with AWS signing, HTTP and JWT, into this jar.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FASTTRACK_DIR="$(cd "$HERE/../.." && pwd)"
LB="${EBX_LIB_DIR:-$FASTTRACK_DIR/ebx-ps-fasttrack-server/lib}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

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
if [[ ! -f "$RT/jackson-databind-2.18.12.jar" ]]; then
	printf 'Missing Jackson under %s (runtime-libs/jackson-*-2.18.12.jar)\n' "$RT" >&2
	exit 2
fi
# MameGoldenRecords (com.orchestranetworks.addon.mame.*) is part of the closure below, unconditionally:
# unlike the runtime LinkageError fallback in GoldenRecords, javac needs the add-on's classes to
# compile it at all, so this is not optional the way the doc's "only if required" might suggest for a
# jar that never referenced the add-on in the first place -- it is required precisely because
# MameGoldenRecords is in the list.
if [[ ! -f "$LB/ebx-addons.jar" ]]; then
	printf 'Missing ebx-addons.jar under %s (needed to compile MameGoldenRecords)\n' "$LB" >&2
	exit 2
fi

JAVA_HOME_VALUE="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME_VALUE" && -x /usr/libexec/java_home ]]; then
	JAVA_HOME_VALUE="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
JAVAC_BIN="${JAVA_HOME_VALUE:+$JAVA_HOME_VALUE/bin/}javac"
JAR_BIN="${JAVA_HOME_VALUE:+$JAVA_HOME_VALUE/bin/}jar"

# Jars only on the classpath -- no directory, and no sibling module source path -- so javac cannot
# silently pull in another .java file of this module to satisfy a reference: an accidental dependency
# on anything outside the explicit source list below fails the build with "cannot find symbol".
CP="$EBX_API_JAR:$LB/ebx-addons.jar:$RT/jackson-core-2.18.12.jar:$RT/jackson-databind-2.18.12.jar:$RT/jackson-annotations-2.18.12.jar"

SRC_DIR="$HERE/src/main/java/com/on/ebx/connect"
SOURCES=(
	"$SRC_DIR/core/MappingEngine.java"
	"$SRC_DIR/core/MappingDefinition.java"
	"$SRC_DIR/core/MappingRule.java"
	"$SRC_DIR/core/MappingResult.java"
	"$SRC_DIR/core/Transformations.java"
	"$SRC_DIR/core/Transformation.java"
	"$SRC_DIR/core/TransformationContext.java"
	"$SRC_DIR/core/TransformationPipeline.java"
	"$SRC_DIR/core/TransformationRunCache.java"
	"$SRC_DIR/core/RunIds.java"
	"$SRC_DIR/core/RunLimits.java"
	"$SRC_DIR/core/Json.java"
	"$SRC_DIR/core/Xpath.java"
	"$SRC_DIR/core/Expressions.java"
	"$SRC_DIR/core/SampleValueSafety.java"
	"$SRC_DIR/core/SyncResult.java"
	"$SRC_DIR/core/ImportBatchSummary.java"
	"$SRC_DIR/core/UnchangedRowDetector.java"
	"$SRC_DIR/core/TrustOracle.java"
	"$SRC_DIR/core/Hashing.java"
	"$SRC_DIR/core/GoldenRecords.java"
	"$SRC_DIR/core/MameGoldenRecords.java"
	"$SRC_DIR/core/ChildRowGroups.java"
	"$SRC_DIR/core/ChildRowPreload.java"
	"$SRC_DIR/core/FeedKeyLookup.java"
	"$SRC_DIR/core/FeedLookups.java"
	"$SRC_DIR/core/BusinessKeyIndex.java"
	"$SRC_DIR/core/BusinessKeyPredicate.java"
	"$SRC_DIR/core/KeyPredicatePrimaryKey.java"
	"$SRC_DIR/core/Watermark.java"
	"$SRC_DIR/core/Redaction.java"
	"$SRC_DIR/ebx/DatasetLocator.java"
	"$SRC_DIR/spi/ConnectorConfig.java"
	"$SRC_DIR/spi/ConnectorException.java"
	"$SRC_DIR/spi/SynchronizationStrategy.java"
)
for f in "${SOURCES[@]}"; do
	if [[ ! -f "$f" ]]; then
		printf 'Expected source file missing: %s\n' "$f" >&2
		exit 2
	fi
done

"$JAVAC_BIN" --release 8 -encoding UTF-8 -nowarn -Xlint:none -cp "$CP" -d "$OUT" "${SOURCES[@]}"

# The build is meant to fail loudly if the closure grew: verify every compiled .class file's TOP-LEVEL
# type (strip a $Nested/$1 suffix, which javac freely adds for enums, inner classes and the anonymous
# Transformation implementations registerBuiltins() declares) is one of the source files above. There
# is no sibling source path on the classpath so javac itself cannot pull in another .java file of the
# module, but a future edit could still widen one of the files above to import a sibling directly --
# catch that here too, on the compiled output, not only by re-reading the sources.
ALLOWED_TOP_LEVEL=""
for f in "${SOURCES[@]}"; do ALLOWED_TOP_LEVEL="$ALLOWED_TOP_LEVEL $(basename "$f" .java)"; done
UNEXPECTED=""
while IFS= read -r class_file; do
	rel="${class_file#"$OUT"/}"
	top_level="$(basename "$rel" .class | sed 's/\$.*//')"
	case " $ALLOWED_TOP_LEVEL " in
		*" $top_level "*) ;;
		*) UNEXPECTED="$UNEXPECTED$rel"$'\n' ;;
	esac
done < <(find "$OUT" -name '*.class')
if [[ -n "$UNEXPECTED" ]]; then
	printf 'Unexpected class(es) in the compiled output (closure grew beyond the documented list):\n%s\n' "$UNEXPECTED" >&2
	exit 2
fi

VERSION="$(date +%Y.%m.%d)"
mkdir -p "$HERE/dist"
printf 'Manifest-Version: 1.0\nImplementation-Title: on-ebx-mapping\nImplementation-Version: %s\n' "$VERSION" > "$OUT/MANIFEST.MF"
"$JAR_BIN" cfm "$HERE/dist/on-ebx-mapping.jar" "$OUT/MANIFEST.MF" -C "$OUT" com
echo "built $HERE/dist/on-ebx-mapping.jar ($VERSION): $(unzip -l "$HERE/dist/on-ebx-mapping.jar" | grep -c '\.class$') classes"
