#!/usr/bin/env bash
# Offline build and connector contract checks. No EBX repository or vendor account is required.
# Environment: EBX_LIB_DIR (EBX server lib folder), EBX_BUILD_DIR (output folder, default target/baseline),
# EBX_BUILD_VERSION / EBX_BUILD_COMMIT (version and commit to stamp when the tree is not a git checkout; see
# tools/version.sh), SOURCE_DATE_EPOCH (reproducible build time).
set -euo pipefail

module_dir="$(cd "$(dirname "$0")" && pwd)"
# Metadata verification tools resolve their defaults relative to this module.
cd "$module_dir"
# Default server lib: the module sits in <workspace>/ebx-connectors, next to <workspace>/ebx-ps-fasttrack-server.
# From a clone elsewhere (a repository, a worktree) nothing is found there: set EBX_LIB_DIR.
workspace_dir="$(cd "$module_dir/.." && pwd)"
server_lib="${EBX_LIB_DIR:-$workspace_dir/ebx-ps-fasttrack-server/lib}"
# Parallel developer/agent waves may provide an isolated directory so one build
# cannot delete another build's classes. The release default remains unchanged.
build_dir="${EBX_BUILD_DIR:-$module_dir/target/baseline}"
main_classes="$build_dir/classes"
test_classes="$build_dir/test-classes"
runtime_lib="$module_dir/runtime-libs"

python3 "$module_dir/tools/test_scaffold_connector.py"
python3 -m unittest "$module_dir/src/test/python/test_generate_connector_parity.py"
python3 -m unittest "$module_dir/src/test/python/test_verify_release_readiness.py"
python3 -m unittest "$module_dir/src/test/python/test_generate_operational_readiness.py"
python3 -m unittest "$module_dir/src/test/python/test_schema_diff.py"
python3 -B -m unittest "$module_dir/src/test/python/test_itest_suite.py"
python3 -B -m unittest "$module_dir/src/test/python/test_check_war_selfcontained.py"
python3 -B -m unittest "$module_dir/src/test/python/test_deps_check.py"
python3 -B -m unittest "$module_dir/src/test/python/test_sync_runtime_libs.py"
python3 "$module_dir/tools/generate_connector_parity.py" --check
python3 "$module_dir/tools/generate_operational_readiness.py" --check
python3 "$module_dir/tools/verify_guide_index.py"
python3 "$module_dir/tools/test_theme_colours.py"
python3 "$module_dir/tools/build_help.py" --check
# audit C2-15: versions and library families of runtime-libs/ (no network here; CI and releases run it with OSV)
"$module_dir/tools/deps_check.sh" --offline
# audit C2-11, C3-14: the undocumented EBX and add-on APIs the module still calls are listed here (docs/OPERATIONS.md section 9), each isolated in
# ONE class; anything else fails. The third class reads the DAMA add-on's non-public packages (addon.dama.common, addon.dama.paths) by reflection.
internal_hits="$(grep -rnE '\.internal\.|ProfileListContextBridge|onwbp\.adaptation\.SessionImpl|addon\.dama\.(common|paths)\.' "$module_dir/src/main/java" \
	| grep -vE '/core/WorkerSessions\.java:|/ebx/ConnectorsRoleEnumeration\.java:|/core/ReflectiveDamaAssets\.java:' | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)' || true)"
if [[ -n "$internal_hits" ]]; then
	printf 'Undocumented EBX or add-on API outside the three documented classes (WorkerSessions, ConnectorsRoleEnumeration, ReflectiveDamaAssets):\n%s\n' "$internal_hits" >&2
	exit 1
fi

# The EBX API jar is ebx.jar on a standard ON EBX installation and ebx-lib.jar on a FastTrack one (same bytes): either is accepted (audit X3-07).
if [[ ! -f "$server_lib/ebx.jar" && ! -f "$server_lib/ebx-lib.jar" ]]; then
	printf 'Missing EBX runtime libraries under %s: neither ebx.jar (standard installation) nor ebx-lib.jar (FastTrack) is there (set EBX_LIB_DIR to the EBX server lib folder)\n' "$server_lib" >&2
	exit 2
fi

# GoldenRecords/MameGoldenRecords compile against the Match and
# Merge add-on's public API (com.orchestranetworks.addon.mame.*); it ships in ebx-addons.jar, next to
# ebx.jar / ebx-lib.jar in the same lib folder, already covered by the "$server_lib/*" classpath glob below.
if [[ ! -f "$server_lib/ebx-addons.jar" ]]; then
	printf 'Missing ebx-addons.jar under %s (needed to compile GoldenRecords/MameGoldenRecords)\n' "$server_lib" >&2
	exit 2
fi

java_home="${JAVA_HOME:-}"
if [[ -z "$java_home" && -x /usr/libexec/java_home ]]; then
	java_home="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
javac_bin="${java_home:+$java_home/bin/}javac"
java_bin="${java_home:+$java_home/bin/}java"
jar_bin="${java_home:+$java_home/bin/}jar"

for tool in "$javac_bin" "$java_bin" "$jar_bin"; do
	if ! command -v "$tool" >/dev/null 2>&1; then
		printf 'Required Java tool not found: %s\n' "$tool" >&2
		exit 2
	fi
done

rm -rf "$build_dir"
mkdir -p "$main_classes" "$test_classes"

main_sources=()
while IFS= read -r file; do
	main_sources+=("$file")
done < <(find "$module_dir/src/main/java" -name '*.java' -type f -print)
test_sources=()
while IFS= read -r file; do
	test_sources+=("$file")
done < <(find "$module_dir/src/test/java" -name '*.java' -type f -print)

if [[ ${#main_sources[@]} -eq 0 || ${#test_sources[@]} -eq 0 ]]; then
	printf 'Expected Java sources under src/main/java and src/test/java\n' >&2
	exit 2
fi

# runtime-libs comes FIRST: the war ships its own Jackson and SLF4J (a standard ON EBX lib/ has none), so the code is compiled and tested against the copies it
# ships. A FastTrack lib/ also holds jackson-*.jar and slf4j-api.jar: if their bytes differ from the pinned ones, say so rather than let the order hide it (audit X3-07).
for pinned_line in $(grep -E '^[0-9a-f]{64}  (jackson-(core|databind|annotations)|slf4j-api)-' "$runtime_lib/PINNED-WAR-JARS.txt" | tr -s ' ' ':'); do
	pinned_jar="${pinned_line#*:}"
	server_copy="$server_lib/${pinned_jar%%-[0-9]*}.jar"
	if [[ -f "$server_copy" ]]; then
		server_hash="$(shasum -a 256 "$server_copy" | cut -d' ' -f1)"
		if [[ "$server_hash" != "${pinned_line%%:*}" ]]; then
			printf 'NOTE: %s differs from the pinned %s of runtime-libs; this build uses the pinned one (the war ships it), the server copy is ignored.\n' "$server_copy" "$pinned_jar"
		fi
	fi
done
classpath="$runtime_lib/*:$server_lib/*:$server_lib/tomcat/*"
"$javac_bin" --release 8 -encoding UTF-8 -nowarn -Xlint:none \
	-cp "$classpath" -d "$main_classes" "${main_sources[@]}"

if [[ -d "$module_dir/src/main/resources" ]]; then
	cp -R "$module_dir/src/main/resources/." "$main_classes/"
fi

# The version this build is (audit C-08): derived from git by tools/version.sh, written into the classes where
# core/ModuleVersion reads it, and below into the war's manifest. A class tree compiled elsewhere (Eclipse) has no
# such file and reports "unknown". SOURCE_DATE_EPOCH, when set, fixes the build time for a reproducible package.
build_version="$("$module_dir/tools/version.sh" version)"
build_commit="$("$module_dir/tools/version.sh" commit)"
if [[ -n "${SOURCE_DATE_EPOCH:-}" ]]; then
	built_at="$(date -u -r "$SOURCE_DATE_EPOCH" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "@$SOURCE_DATE_EPOCH" +%Y-%m-%dT%H:%M:%SZ)"
else
	built_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
fi
mkdir -p "$main_classes/com/on/ebx/connect"
printf 'version=%s\ncommit=%s\nbuiltAt=%s\n' "$build_version" "$build_commit" "$built_at" \
	> "$main_classes/com/on/ebx/connect/version.properties"

"$javac_bin" --release 8 -encoding UTF-8 -nowarn -Xlint:none \
	-cp "$main_classes:$classpath" -d "$test_classes" "${test_sources[@]}"

# Audit decision 1 (2026-10-06): private hosts are refused and clear secrets are refused BY DEFAULT. The fixtures of
# this suite call local servers (127.0.0.1) and keep plain test secrets, so every test JVM below starts with both
# protections opted out; the tests that exercise the defaults (EgressDefaultsTest, SecretsDefaultsTest, EgressRedirectTest,
# ConnectorsStatusTest) clear or set those properties themselves.
export JAVA_TOOL_OPTIONS="-Debx.connectors.egress.blockPrivate=false -Debx.connectors.secrets.allowClear=true"
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.SharedDriveCatalogTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.GraphMetadataStateTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.SqlServerMetadataTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.XlsxParserSafetyTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.DocxExtractionTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.TextDocumentSafetyTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.DocumentFeedTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.DamaDocumentsTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.DocumentVisionTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.DocumentConfidenceTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.CdcApplyPlanTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.PreviewFeaturesTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.PreviewMappingRefusalTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.CdcMatchingTest "$module_dir"
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.CatalogPreviewMaskTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.CatalogPreviewBoundaryTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.PreviewConfigurationTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.SampleValueSafetyTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.SyncOutcomeTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.AsyncRunGuardTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.BookkeepingTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.RunVerdictTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.BusinessKeyPredicateTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.BusinessKeyIndexTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ValueTemplatesTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ChildRowGroupsTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.spi.ConnectorConfigSameTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.RunConnectorConfigTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ModuleVersionTest
# lot 1.4.1 (audit X3-08): which Jackson the tests loaded (the line starting JACKSON), the read bounds of every reader, the nasty documents; then the explicit string bound
"$java_bin" -Xmx2g -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.rest.JsonReadLimitsTest "$module_dir"
"$java_bin" -Debx.connectors.json.maxStringLength=1000 -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.rest.JsonReadLimitsTest "$module_dir" small
# audit C2-15, X3-07: the jars of runtime-libs/ ALONE (Jackson and SLF4J are among them now; no server lib on this class path) load together: one netty family, Azure Service Bus, kafka-clients + lz4
"$java_bin" -cp "$test_classes:$runtime_lib/*" com.on.ebx.connect.core.RuntimeLibsLoadTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.KafkaConsumeOffsetsTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.WatermarkFilterReportTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.BatchKeySegmentsTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.MappingRunLocksTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.SeedMarkersTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.InstallerEnsureTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.RunLogPageTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ListingPageTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.ConfigTransferFieldsTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.DatasetResolutionTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.ConnectorsStatusTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.FailureTextTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ErrorCatalogTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.LogMessageCodesTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.BatchMetricsTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.RunProgressTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.ui.RunPanelViewTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ConflictResolverPlanTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ConflictPageTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ScheduledFailureAlertTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.MappingSelectionTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.RecordBookkeepingTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.BackgroundWorkTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.RetentionTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ImportTruncationTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.CatalogCompletionTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.CatalogExecutionIdentityTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.WebhooksTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.NodeIdentityTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.ConnectorPropertyValueAccessRuleTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.ConnectorsTableAccessRuleTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.OutboundQueueTest
"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.ConnectorTableTriggerQueueTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.OriginValuesTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.JdbcGuardTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.MessagingGuardTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.EgressRedirectTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.EgressDefaultsTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.SecretsDefaultsTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.OpenApiImportFetchTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.SecretReferenceConfinementTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.GoogleAuthConfinementTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.EgressListReportTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.SftpHostKeyPolicyTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.GoogleLabelReconciliationTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.SourceLabelClearTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.TransformationsTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.KeyPredicatePrimaryKeyTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.UnchangedRowDetectorTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.TransformationRunCacheTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.BatchCommitTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.CircuitBreakerTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ImportBatchSummaryTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.spi.ConnectorRowStreamTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ImportOutcomeTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.ResultTallyTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.NotProcessedRemainderTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.SessionForAsyncWorkerTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.WatermarkTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.WatermarkFilterTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.core.WatermarkRunTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.JdbcWatermarkTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.agent.McpClientTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.agent.LlmClientTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.agent.ToolSetTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.agent.AgentLoopTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.connectors.McpConnectorTest
"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.ui.ScriptValueTest
"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.ui.SyncActivationTest
"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.AccessRefusalTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.ConnectorContractTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.ConnectorConfigurationStatusTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ConnectorRegistryValidationTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.coverage.ConnectorCoverageManifestTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.DescriptorConsumptionContractTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ConnectorFlowWorkspaceContractTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.DesignerAccessibilityContractTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" com.on.ebx.connect.ebx.ui.ThemeTokensTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.ThemeColourScanTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.DescriptorConfigurationTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.CredentialGuideTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.MappingCompatibilityTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.MappingSynchronizationPolicyTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.ConnectorInspectionSpiTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.CatalogOperationsTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ProfilePolicyTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.spi.OperationReadOnlyTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.GenericRestConnectorReadOnlyTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.OpenLineageEmitterTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.CatalogDetectionTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.CatalogScanStatusTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.OpenLineageDatasetNameTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.MappingDocumentTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.EbxCatalogTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.LlmConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.KafkaCatalogTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.KafkaPublishRecordTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SalesforceCatalogTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SalesforceAuthTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SalesforceQueryLimitTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.HttpJsonClientLimitTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.MappingRunDataspaceTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.PowerBiScanTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.PowerBiConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.CollibraConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SlackConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.WorkItemNoticesTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.SafetyFoundationsTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.SecurityReviewRegressionTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.SecretResolverRedactionTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.AwsSecretsManagerResolverTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.GoogleSecretManagerResolverTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.BoundedInspectionRuntimeTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.HttpConnectorInspectionTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.ReadOnlyConnectionProbeTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.GleifConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.DnbConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.ReadOnlyPreviewSafetyTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.EbxPreviewTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.RestFoundationTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.SaasHttpAcceptanceTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ODataSupportTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.JdbcFoundationTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.JdbcVolumeCapTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.JdbcProfileBudgetTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.PostgreSqlConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.GreenplumConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SingleStoreConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.FirebirdConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.Db2LuwConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.IrisConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SapAseConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SapHanaConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.TeradataConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.VerticaConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.RedshiftConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.ActianVectorConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.JdbcProcedureOperationTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.ActianIngresConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.MonetDbConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SqsConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SnsConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.GooglePubSubConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.AzureCosmosDbConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.FtpConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.SampleDataUrlTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SampleFileConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.AbstractFileConnectorCatalogTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.UploadedSampleFileWidgetFactoryTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.AzureQueueStorageConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.AzureTableStorageConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.GoogleSheetsConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SapODataConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.WorkdayConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.BigQueryConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.ElasticsearchConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.CassandraConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.MongoDbConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.CouchbaseConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.CouchbaseSdkSurfaceTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.Wave4DocumentCodecTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.CmisConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ConnectorConnectionStateServiceTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.ConnectorCatalogConnectionBadgeTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.ConnectorCatalogReturnContractTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.StorageScopeTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.ConnectorCatalogFacetsContractTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.FavoritesTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ConnectorsAccessTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.contract.InJvmContractTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.contract.ContractChatTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ContractFlowDocumentTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.rest.CsrfGuardTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.rest.BodyLimitTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.rest.ConnectorsResourceWiringTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.JsonForScriptTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ConnectorsAuditTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.rest.RestAuditTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.DiagnosticsLaunchTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.spi.ConnectorConfigPricingTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ConnectorsInstallerPricingTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.H2ConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.OracleDatabaseConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.MicrosoftSqlConnectorsTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.JmsConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.RabbitMqConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.AzureServiceBusConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.AzureServiceBusSdkTransportFactoryTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.DynamicsBusinessCentralConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SuccessFactorsConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SapAribaConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.NetSuiteConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SharePointConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.MySqlConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.SoapFoundationTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.FileFormatsTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.CsvStreamTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.FileConnectorStreamTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.ConnectorBatchContractTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.SoapRecursiveWsdlTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.EbxRestSchemaRefreshStoreTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ConnectorTemplateLifecycleTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.SynchronizationCheckpointTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.RecoverableExecutionTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ConnectorTemplatePersistenceTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.PackagedProductProfileInstallerTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.PerspectiveInstallerTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.AboutPageTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.WaveConnectorCatalogSeedTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.BusinessIntegrationConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.DebeziumKafkaCdcContractTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.HadoopWebHdfsConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SparkSqlConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.DominoRestConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.DurableExecutionStateStoreTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.CommittedIncrementalExecutionTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.release.ReleaseNotesManifestTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.PackagedReleaseNotesTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.ui.ReleaseNotesDatasetContractTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ConnectorOperationalMetricsTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.RepeatedFailureAlertHookTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ConnectorGovernanceServiceTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ConnectorOperationsRuntimeTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ConnectorGovernanceApplicationServiceTest

"$java_bin" -Debx.connectors.moduleDir="$module_dir" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.EbxOperationalPersistenceTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SnowflakePublicationSqlTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.connectors.SnowflakeConnectorTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ProductPublisherTest
"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.PublicationBatcherTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.ColumnMeaningTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.GoldenRecordsChainTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.TransformationsGoldenTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.ConnectorsPublishSurfaceContractTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.contract.ConnectorsRunRestSurfaceContractTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.mcp.ConnectorsToolsCallDocumentTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.mcp.ConnectorsToolsNoticesTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.ebx.mcp.ConnectorsToolsAsyncSendTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.OutboundRunTest

"$java_bin" -cp "$test_classes:$main_classes:$classpath" \
	com.on.ebx.connect.core.RunLimitsTest

python3 "$module_dir/src/test/python/verify_upgrade_safety.py"
python3 "$module_dir/src/test/python/verify_upgrade_snapshot.py"
python3 "$module_dir/src/test/python/verify_diagnostics_permissions.py"

mkdir -p "$build_dir/package"
cp -R "$module_dir/src/main/webapp/." "$build_dir/package/"
mkdir -p "$build_dir/package/WEB-INF/classes"
cp -R "$main_classes/." "$build_dir/package/WEB-INF/classes/"
if compgen -G "$runtime_lib/*.jar" >/dev/null; then
	mkdir -p "$build_dir/package/WEB-INF/lib"
	cp "$runtime_lib"/*.jar "$build_dir/package/WEB-INF/lib/"
fi

# Third-party licenses: every packaged JAR must be listed in THIRD-PARTY-NOTICES.md.
notices="$runtime_lib/THIRD-PARTY-NOTICES.md"
missing_notices=()
for jar in "$runtime_lib"/*.jar; do
	name="$(basename "$jar")"
	case "$name" in
		netty-*) grep -qF '`netty-*.jar`' "$notices" || missing_notices+=("$name") ;;
		*) grep -qF "\`$name\`" "$notices" || missing_notices+=("$name") ;;
	esac
done
if (( ${#missing_notices[@]} )); then
	printf 'Missing from %s: %s\n' "$notices" "${missing_notices[*]}" >&2
	exit 1
fi
licenses_dir="$build_dir/package/META-INF/licenses"
mkdir -p "$licenses_dir"
cp "$notices" "$runtime_lib"/licenses/* "$licenses_dir/"
for legal in "$runtime_lib"/*-LICENSE.txt "$runtime_lib"/*-NOTICE*; do
	if [[ -f "$legal" ]]; then cp "$legal" "$licenses_dir/"; fi
done
printf 'PASS: third-party notices cover every packaged JAR\n'
# Manifest lines stay under the jar tool's 72-byte limit: only short, fixed keys.
{
	printf 'Manifest-Version: 1.0\n'
	printf 'Implementation-Title: ebx-connectors\n'
	printf 'Implementation-Version: %s\n' "$build_version"
	printf 'Build-Commit: %s\n' "$build_commit"
	printf 'Built-At: %s\n' "$built_at"
} > "$build_dir/MANIFEST.MF"
"$jar_bin" cfm "$build_dir/ebx-connectors.war" "$build_dir/MANIFEST.MF" -C "$build_dir/package" .

# audit X3-07: the war must run on a standard ON EBX lib/ (ebx.jar, a database driver, jakarta.mail), which has neither Jackson nor SLF4J. Every class that the war's
# classes and jars reference must be found in the war, the JDK, Tomcat or an ebx.jar; the Jackson and SLF4J jars inside the war are compared with the pinned hashes.
# EBX_STANDARD_JAR (the ebx.jar of a standard distribution) and TOMCAT_LIB_DIR make the check exact; without them it says so and uses a list of package prefixes.
PATH="${java_home:+$java_home/bin:}$PATH" python3 -I "$module_dir/tools/check_war_selfcontained.py" "$build_dir/ebx-connectors.war" \
	--pinned "$runtime_lib/PINNED-WAR-JARS.txt" --allow "$module_dir/tools/war-optional-refs.txt"

python3 "$module_dir/tools/verify_release_readiness.py" \
	--repo "$module_dir" \
	--coverage connector-coverage.yaml \
	--war "$build_dir/ebx-connectors.war"

printf 'PASS: compiled main and test sources; packaged %s (version %s)\n' "$build_dir/ebx-connectors.war" "$build_version"
