package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.TablePublisher;
import com.on.ebx.connect.spi.TablePublisher.ColumnSpec;
import com.on.ebx.connect.spi.TablePublisher.Kind;
import com.on.ebx.connect.spi.TablePublisher.PublicationSpec;
import com.on.ebx.connect.spi.TablePublisher.TableSpec;

/**
 * Offline checks of {@link ProductPublisher}'s EBX-free core: table-spec derivation, document
 * flattening, batching, commit, failure/cleanup and dry run — through {@link ProductPublisher#compute}
 * with a fake {@link TablePublisher} and a fake {@code RecordSource}, no repository involved.
 */
public final class ProductPublisherTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		testTableSpecDerivation();
		testDuplicateColumn();
		testDuplicateColumnAgainstTechnicalColumn();
		testConstantDetectedAnywhereInPipeline();
		testCrosswalkColumnKinds();
		testRowFlattening();
		testBatchingAndCommit();
		testFailureCallsCleanup();
		testDryRunExecutesNothing();
		testEbxTableUsesResolvedDataspace();
		testQualifiedTableNameKeepsLastSegment();
		testQualifiedTableSchemaMismatchReported();
		testQualifiedTableSchemaMatchIsNotReported();
		testUnqualifiedTableIsNeverASchemaMismatch();
		testQualifiedTableSchemaMismatchFailsPublish();
		testPublishesDecision();
		testMergedRecordsModeDefaultsAndCrosswalkOverride();
		testMergedRecordsErrorValidation();
		testInvalidMergedRecordsFailsPublishBeforeReadingRows();
		testSkippedCountsPropagateOnARealRun();
		testSkippedCountsPropagateOnADryRun();
		testSkippedNodeOmittedWhenNothingSkipped();
		testNoDefaultCapAboveTheOldTenThousand();
		testCutPublicationIsPartialNeverOk();
		testTableNeverHeldInMemory();
		testSourceFailureMidStreamIsAnErrorWithoutCommit();
		testRowErrorsBeyondTheListingLimitAreCounted();
		testDryRunReportsTheCut();
		testRequestMaxHasNoHardCap();
		System.out.println("PASS: ProductPublisher (table-spec derivation, flattening, batching, commit, failure/cleanup, dry run)");
	}

	// ------------------------------------------------------------------ table-spec derivation

	private static void testTableSpecDerivation() throws Exception
	{
		final MappingDefinition mapping = happyMapping();
		final FakeRecordSource source = new FakeRecordSource();
		source.kinds.put("./Identification/Id", Kind.INTEGER);
		source.keys.add("./Identification/Id");
		source.kinds.put("./Identification/Name", Kind.STRING);
		source.maxLengths.put("./Identification/Name", Integer.valueOf(120));

		final List<String> errors = new ArrayList<String>();
		final ProductPublisher.TableWork work = ProductPublisher.buildTableWork(mapping, source, "Companies", errors);
		check(errors.isEmpty(), "no error deriving the happy-path table: " + errors);
		check(work != null, "work built");
		check("COMPANY_360".equals(work.spec.getName()), "table name upper-cased: " + work.spec.getName());

		final Map<String, ColumnSpec> byName = new LinkedHashMap<String, ColumnSpec>();
		for (final ColumnSpec c : work.spec.getColumns())
		{
			byName.put(c.getName(), c);
		}
		check(byName.size() == 9, "ID, NAME, STATUS, ADDRESS, IDENTIFIERS + 4 technical columns = 9, got " + byName.keySet());
		check(byName.get("ID").getKind() == Kind.INTEGER && byName.get("ID").isKey(), "ID is the integer business key");
		check(byName.get("NAME").getKind() == Kind.STRING && Integer.valueOf(120).equals(byName.get("NAME").getMaxLength()), "NAME carries the static max length");
		check(byName.get("STATUS").getKind() == Kind.STRING && !byName.get("STATUS").isKey(), "STATUS (constant rule) is a plain string, never a key");
		check(byName.get("ADDRESS").getKind() == Kind.JSON, "ADDRESS is the single JSON column merged from two dotted-target rules");
		check(byName.get("IDENTIFIERS").getKind() == Kind.JSON, "IDENTIFIERS is the child's collection column");
		final ColumnSpec ebxKey = byName.get("_EBX_KEY");
		check(ebxKey.getKind() == Kind.STRING && Integer.valueOf(255).equals(ebxKey.getMaxLength()) && !ebxKey.isKey(),
			"_EBX_KEY is not the key when a business key (ID) already exists");
		check(byName.get("_RUN_ID").getKind() == Kind.STRING, "_RUN_ID present");
		check(byName.get("_PUBLISHED_AT").getKind() == Kind.DATETIME, "_PUBLISHED_AT present");
		check(byName.get("_CONTRACT_VERSION").getKind() == Kind.STRING, "_CONTRACT_VERSION present");
	}

	private static void testDuplicateColumn() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(300, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Dup", "Companies",
			"Companies", "/root/Companies", "", true);
		mapping.addInput("table", "DUP_TABLE");
		mapping.addRule(new MappingRule(1, "./A", "id", "", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(2, "./B", "id", "", MappingRule.Policy.ALWAYS, null));
		final List<String> errors = new ArrayList<String>();
		final ProductPublisher.TableWork work = ProductPublisher.buildTableWork(mapping, new FakeRecordSource(), "Companies", errors);
		check(work != null, "work is still built (only the offending column is skipped)");
		check(!errors.isEmpty() && errors.get(0).contains("duplicate column ID"), "duplicate column reported: " + errors);
	}

	private static void testDuplicateColumnAgainstTechnicalColumn() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(301, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "DupTechnical", "Companies",
			"Companies", "/root/Companies", "", true);
		mapping.addInput("table", "DUP_TABLE");
		mapping.addRule(new MappingRule(1, "./Identification/Id", "_ebx_key", "", MappingRule.Policy.ALWAYS, null));
		final List<String> errors = new ArrayList<String>();
		final ProductPublisher.TableWork work = ProductPublisher.buildTableWork(mapping, new FakeRecordSource(), "Companies", errors);
		check(work != null, "work is still built (only the offending column is skipped)");
		check(!errors.isEmpty() && errors.get(0).contains("duplicate column _EBX_KEY"),
			"a rule sanitizing to a technical column name is reported as a duplicate: " + errors);
		int technicalKeyCount = 0;
		for (final ColumnSpec c : work.spec.getColumns())
		{
			if ("_EBX_KEY".equals(c.getName()))
			{
				technicalKeyCount++;
			}
		}
		check(technicalKeyCount == 1, "exactly one _EBX_KEY column survives, got " + technicalKeyCount);
	}

	/** A {@code constant(...)} step anywhere in the pipeline (not only as the whole/first step) must still
	 * classify the rule as a constant: no key, no source-driven kind/max-length. */
	private static void testConstantDetectedAnywhereInPipeline() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(310, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "ConstantMidPipeline",
			"Companies", "Companies", "/root/Companies", "", true);
		mapping.addInput("table", "CONST_TABLE");
		mapping.addRule(new MappingRule(1, "./Identification/Id", "col", "upper|constant('X')", MappingRule.Policy.ALWAYS, null));
		final FakeRecordSource source = new FakeRecordSource();
		source.kinds.put("./Identification/Id", Kind.INTEGER);
		source.keys.add("./Identification/Id"); // would be a business key if the rule were mistaken for non-constant

		final List<String> errors = new ArrayList<String>();
		final ProductPublisher.TableWork work = ProductPublisher.buildTableWork(mapping, source, "Companies", errors);
		check(errors.isEmpty(), "no error: " + errors);
		final Map<String, ColumnSpec> byName = new LinkedHashMap<String, ColumnSpec>();
		for (final ColumnSpec c : work.spec.getColumns())
		{
			byName.put(c.getName(), c);
		}
		check(byName.get("COL").getKind() == Kind.STRING, "a constant step later in the pipeline still forces STRING: " + byName.get("COL").getKind());
		check(!byName.get("COL").isKey(), "a constant step later in the pipeline is never a business key");
	}

	/** {@code mdmState(...)} yields a state name whatever its source field is, so its column is text; {@code golden(...)}
	 * keeps the type of the key it resolves; neither is a key of the published table. Seen live: a key table typed
	 * its MDM_STATE column NUMBER, from the integer primary key the rule reads. */
	private static void testCrosswalkColumnKinds() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(320, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Keys",
			"Companies", "Companies", "/root/Companies", "", true);
		mapping.addInput("table", "COMPANY_KEYS");
		mapping.addRule(new MappingRule(1, "./Identification/Id", "ID", "", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(2, "./Identification/Id", "GOLDEN_ID", "golden('/root/Companies')", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(3, "./Identification/Id", "MDM_STATE", "MdmState('/root/Companies')", MappingRule.Policy.ALWAYS, null));
		final FakeRecordSource source = new FakeRecordSource();
		source.kinds.put("./Identification/Id", Kind.INTEGER);
		source.keys.add("./Identification/Id");

		final List<String> errors = new ArrayList<String>();
		final ProductPublisher.TableWork work = ProductPublisher.buildTableWork(mapping, source, "Companies", errors);
		check(errors.isEmpty(), "no error: " + errors);
		final Map<String, ColumnSpec> byName = new LinkedHashMap<String, ColumnSpec>();
		for (final ColumnSpec c : work.spec.getColumns())
		{
			byName.put(c.getName(), c);
		}
		check(byName.get("ID").getKind() == Kind.INTEGER && byName.get("ID").isKey(), "the plain key column stays an integer key");
		check(byName.get("GOLDEN_ID").getKind() == Kind.INTEGER, "golden keeps the type of the key it resolves: " + byName.get("GOLDEN_ID").getKind());
		check(!byName.get("GOLDEN_ID").isKey(), "the golden key is not a key of the published table");
		check(byName.get("MDM_STATE").getKind() == Kind.STRING, "a state name is text, whatever the source field: " + byName.get("MDM_STATE").getKind());
		check(!byName.get("MDM_STATE").isKey(), "the state is not a key of the published table");
	}

	/** A schema-qualified {@code table} input contributes only its last segment (then the usual sanitization). */
	private static void testQualifiedTableNameKeepsLastSegment() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(450, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Qualified",
			"Companies", "Companies", "/root/Companies", "", true);
		mapping.addInput("table", "SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS.company_360");
		mapping.addRule(new MappingRule(1, "./Identification/Id", "id", "", MappingRule.Policy.ALWAYS, null));
		final FakeRecordSource source = new FakeRecordSource();
		source.kinds.put("./Identification/Id", Kind.INTEGER);
		source.keys.add("./Identification/Id");
		final List<String> errors = new ArrayList<String>();
		final ProductPublisher.TableWork work = ProductPublisher.buildTableWork(mapping, source, "Companies", errors);
		check(errors.isEmpty(), "no error deriving a qualified table name: " + errors);
		check("COMPANY_360".equals(work.spec.getName()), "only the last segment, sanitized, is used: " + work.spec.getName());

		final MappingDefinition bareSchema = new MappingDefinition(451, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "QualifiedBareSchema",
			"Companies", "Companies", "/root/Companies", "", true);
		bareSchema.addInput("table", "DATA_PRODUCTS.company_360");
		final ProductPublisher.TableWork work2 = ProductPublisher.buildTableWork(bareSchema, source, "Companies", errors);
		check(errors.isEmpty(), "no error deriving a SCHEMA.TABLE name: " + errors);
		check("COMPANY_360".equals(work2.spec.getName()), "a two-part SCHEMA.TABLE input also keeps only the table segment: " + work2.spec.getName());
	}

	/** A qualified {@code table} input naming a different schema than the publication is reported, naming both. */
	private static void testQualifiedTableSchemaMismatchReported() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(452, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Mismatch",
			"Companies", "Companies", "/root/Companies", "", true);
		mapping.addInput("table", "OTHER_DB.OTHER_SCHEMA.COMPANY_360");
		final String mismatch = ProductPublisher.qualifiedTableSchemaMismatch(mapping, "SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS");
		check(mismatch != null, "a qualified table naming a different schema is reported");
		check(mismatch.contains("OTHER_DB.OTHER_SCHEMA") && mismatch.contains("SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS"), "message names both schemas: " + mismatch);
	}

	/** A qualified {@code table} input naming the same schema as the publication (exactly, or by its bare schema segment) is never reported. */
	private static void testQualifiedTableSchemaMatchIsNotReported() throws Exception
	{
		final MappingDefinition exact = new MappingDefinition(453, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Match",
			"Companies", "Companies", "/root/Companies", "", true);
		exact.addInput("table", "SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS.COMPANY_360");
		check(ProductPublisher.qualifiedTableSchemaMismatch(exact, "SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS") == null,
			"a qualified table naming exactly the publication's schema is not reported");

		final MappingDefinition bareSchema = new MappingDefinition(454, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "MatchBareSchema",
			"Companies", "Companies", "/root/Companies", "", true);
		bareSchema.addInput("table", "DATA_PRODUCTS.COMPANY_360");
		check(ProductPublisher.qualifiedTableSchemaMismatch(bareSchema, "SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS") == null,
			"a bare schema part matches when it equals the schema segment of the publication's DB.SCHEMA");
	}

	/** An unqualified {@code table} input is never a schema mismatch: there is nothing to compare. */
	private static void testUnqualifiedTableIsNeverASchemaMismatch() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(455, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Unqualified",
			"Companies", "Companies", "/root/Companies", "", true);
		mapping.addInput("table", "COMPANY_360");
		check(ProductPublisher.qualifiedTableSchemaMismatch(mapping, "SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS") == null,
			"an unqualified table input is never flagged");
	}

	/** End to end through {@link ProductPublisher#compute}: a schema mismatch fails the whole publication, naming both schemas. */
	private static void testQualifiedTableSchemaMismatchFailsPublish() throws Exception
	{
		final MappingDefinition mapping = batchMapping(456);
		mapping.addInput("table", "OTHER_DB.OTHER_SCHEMA.BATCH_TABLE");
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 1);
		final FakePublisher publisher = new FakePublisher();
		final ConnectorConfig config = new ConnectorConfig();

		final ProductPublisher.Request request = request("SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS", "COMPANY_360", true, false, 10000);
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, config, "Snowflake", "Companies", source,
			"R5", fakeContext());

		check("ERROR".equals(report.getStatus()), "publish fails on a qualified table/schema mismatch: " + report.getStatus());
		check(report.getMessage().contains("OTHER_DB.OTHER_SCHEMA") && report.getMessage().contains("SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS"),
			"message names both: " + report.getMessage());
	}

	// ------------------------------------------------------------------ merged filter / crosswalk

	/** {@link ProductPublisher#publishes}: pure truth table, no EBX involved. */
	private static void testPublishesDecision() throws Exception
	{
		check(ProductPublisher.publishes("GOLDEN", "exclude"), "GOLDEN published under exclude");
		check(ProductPublisher.publishes("UNSET", "exclude"), "UNSET published under exclude");
		check(ProductPublisher.publishes("PENDING_APPROVAL", "exclude"), "PENDING_APPROVAL published under exclude");
		check(ProductPublisher.publishes("UNMANAGED", "exclude"), "UNMANAGED published under exclude");
		check(!ProductPublisher.publishes("MERGED", "exclude"), "MERGED skipped under exclude");
		check(!ProductPublisher.publishes("DELETED", "exclude"), "DELETED skipped under exclude");
		check(ProductPublisher.publishes("MERGED", "include"), "MERGED published under include");
		check(ProductPublisher.publishes("DELETED", "include"), "DELETED published under include");
		check(!ProductPublisher.publishes("MERGED", "EXCLUDE"), "mode compared case-insensitively (upper-case exclude still excludes)");
	}

	/** {@link ProductPublisher#mergedRecordsMode}: the default, the explicit value, and the {@code
	 * crosswalk} override, including that an explicit {@code mergedRecords} always wins over {@code
	 * crosswalk}. */
	private static void testMergedRecordsModeDefaultsAndCrosswalkOverride() throws Exception
	{
		final MappingDefinition plain = batchMapping(500);
		check("exclude".equals(ProductPublisher.mergedRecordsMode(plain)), "default is exclude");

		final MappingDefinition crosswalk = batchMapping(501);
		crosswalk.addInput("crosswalk", "true");
		check("include".equals(ProductPublisher.mergedRecordsMode(crosswalk)), "crosswalk=true defaults mergedRecords to include");

		final MappingDefinition crosswalkFalse = batchMapping(502);
		crosswalkFalse.addInput("crosswalk", "false");
		check("exclude".equals(ProductPublisher.mergedRecordsMode(crosswalkFalse)), "crosswalk=false (or absent) keeps the exclude default");

		final MappingDefinition explicitInclude = batchMapping(503);
		explicitInclude.addInput("mergedRecords", "INCLUDE");
		check("include".equals(ProductPublisher.mergedRecordsMode(explicitInclude)), "explicit value is lower-cased");

		final MappingDefinition explicitWinsOverCrosswalk = batchMapping(504);
		explicitWinsOverCrosswalk.addInput("crosswalk", "true");
		explicitWinsOverCrosswalk.addInput("mergedRecords", "exclude");
		check("exclude".equals(ProductPublisher.mergedRecordsMode(explicitWinsOverCrosswalk)),
			"an explicit mergedRecords wins over crosswalk's own default");
	}

	/** {@link ProductPublisher#mergedRecordsError}: null for the default/valid values, an error naming
	 * the mapping and the offending value otherwise. */
	private static void testMergedRecordsErrorValidation() throws Exception
	{
		final MappingDefinition noInput = batchMapping(510);
		check(ProductPublisher.mergedRecordsError(noInput) == null, "no input: no error");

		final MappingDefinition exclude = batchMapping(511);
		exclude.addInput("mergedRecords", "exclude");
		check(ProductPublisher.mergedRecordsError(exclude) == null, "'exclude': no error");

		final MappingDefinition include = batchMapping(512);
		include.addInput("mergedRecords", "Include");
		check(ProductPublisher.mergedRecordsError(include) == null, "'Include' (case-insensitive): no error");

		final MappingDefinition invalid = batchMapping(513);
		invalid.addInput("mergedRecords", "maybe");
		final String error = ProductPublisher.mergedRecordsError(invalid);
		check(error != null && error.contains("513") && error.contains("maybe"), "invalid value reported, naming the mapping and the value: " + error);
	}

	/** End to end through {@link ProductPublisher#compute}: an invalid {@code mergedRecords} value fails
	 * the whole publication before any row is read ("reported like the other request errors, before
	 * anything is written"). */
	private static void testInvalidMergedRecordsFailsPublishBeforeReadingRows() throws Exception
	{
		final MappingDefinition mapping = batchMapping(520);
		mapping.addInput("mergedRecords", "maybe");
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 3);
		final FakePublisher publisher = new FakePublisher();
		final ConnectorConfig config = new ConnectorConfig();

		final ProductPublisher.Request request = request("DB.SCHEMA", "COMPANY_360", false, false, 10000);
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, config, "Snowflake", "Companies", source,
			"R6", fakeContext());

		check("ERROR".equals(report.getStatus()), "invalid mergedRecords fails the publication: " + report.getStatus());
		check(report.getMessage().contains("mergedRecords") && report.getMessage().contains("maybe"), "message names the bad input: " + report.getMessage());
		check(!source.rowsCalled, "rows() is never called: the error is reported before anything is read");
	}

	/** {@link ProductPublisher.RowBatch}'s skip counts flow through {@link ProductPublisher.TableWork} to
	 * {@link ProductPublisher.TableReport}, its {@code toJson()}, and the report's own message, on a real run. */
	private static void testSkippedCountsPropagateOnARealRun() throws Exception
	{
		final MappingDefinition mapping = batchMapping(530);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 10);
		source.skippedMerged = 4;
		source.skippedDeleted = 1;
		final FakePublisher publisher = new FakePublisher();
		final ConnectorConfig config = new ConnectorConfig();

		final ProductPublisher.Request request = request("DB.SCHEMA", "COMPANY_360", false, false, 10000);
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, config, "Snowflake", "Companies", source,
			"R7", fakeContext());

		check("OK".equals(report.getStatus()), "still a normal successful run: " + report.getMessage());
		final ProductPublisher.TableReport tr = report.getTables().get(0);
		check(tr.getSkippedMerged() == 4, "skippedMerged on the table report: " + tr.getSkippedMerged());
		check(tr.getSkippedDeleted() == 1, "skippedDeleted on the table report: " + tr.getSkippedDeleted());
		check(report.getMessage().contains("5 merged/deleted"), "message mentions the total (4+1=5): " + report.getMessage());

		final com.fasterxml.jackson.databind.JsonNode json = report.toJson();
		final com.fasterxml.jackson.databind.JsonNode skipped = json.get("tables").get(0).get("skipped");
		check(skipped != null, "toJson includes the skipped object when a count is > 0");
		check(skipped.get("merged").asInt() == 4 && skipped.get("deleted").asInt() == 1, "toJson skipped counts: " + skipped);
	}

	/** Same skip-count propagation, on a dry run (everything but execution is computed as a real run would). */
	private static void testSkippedCountsPropagateOnADryRun() throws Exception
	{
		final MappingDefinition mapping = batchMapping(540);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 5);
		source.skippedMerged = 2;
		final FakePublisher publisher = new FakePublisher();
		final ConnectorConfig config = new ConnectorConfig();

		final ProductPublisher.Request request = request("DB.SCHEMA", "COMPANY_360", true, false, 10000);
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, config, "Snowflake", "Companies", source,
			"R8", fakeContext());

		check("DRY_RUN".equals(report.getStatus()), "dry run status: " + report.getStatus());
		check(report.getTables().get(0).getSkippedMerged() == 2, "dry run also reports the skipped count");
		check(report.getMessage().contains("2 merged/deleted"), "dry run message mentions the total too: " + report.getMessage());
	}

	/** No record skipped: the {@code skipped} node is entirely absent from {@code toJson()}, and the
	 * message is the plain, unchanged one. */
	private static void testSkippedNodeOmittedWhenNothingSkipped() throws Exception
	{
		final MappingDefinition mapping = batchMapping(550);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 3);
		final FakePublisher publisher = new FakePublisher();
		final ConnectorConfig config = new ConnectorConfig();

		final ProductPublisher.Request request = request("DB.SCHEMA", "COMPANY_360", false, false, 10000);
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, config, "Snowflake", "Companies", source,
			"R9", fakeContext());

		check("published".equals(report.getMessage()), "plain message when nothing was skipped: " + report.getMessage());
		final com.fasterxml.jackson.databind.JsonNode json = report.toJson();
		check(json.get("tables").get(0).get("skipped") == null, "no skipped node when both counts are zero");
	}

	// ------------------------------------------------------------------ audit C-17: streaming, no hard cap, PARTIAL on a cut

	private static void testNoDefaultCapAboveTheOldTenThousand() throws Exception
	{
		final MappingDefinition mapping = batchMapping(560);
		final FakeRecordSource source = new FakeRecordSource();
		source.generated.put(Integer.valueOf(mapping.getId()), Integer.valueOf(25000));
		final FakePublisher publisher = new FakePublisher();
		publisher.retainBatches = false;
		final ProductPublisher.Request request = new ProductPublisher.Request(); // max left at its default
		request.setSchema("DB.SCHEMA");
		request.setProduct("COMPANY_360");
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, new ConnectorConfig(), "Snowflake",
			"Companies", source, "R30", fakeContext());
		check("OK".equals(report.getStatus()), "25000 rows, no cap: OK, got " + report.getStatus() + " " + report.getMessage());
		check(report.getRowCount() == 25000 && !report.isTruncated(), "every row published: " + report.getRowCount());
		check(Integer.valueOf(25000).equals(publisher.commitRows.get("BATCH_TABLE")), "commit receives 25000: " + publisher.commitRows);
	}

	/** A cut after an atomic full replace is PARTIAL, flagged on the table, in the report and in the message; it still commits. */
	private static void testCutPublicationIsPartialNeverOk() throws Exception
	{
		final MappingDefinition mapping = batchMapping(570);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 1200);
		final FakePublisher publisher = new FakePublisher();
		final ProductPublisher.Request request = request("DB.SCHEMA", "COMPANY_360", false, false, 500);
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, new ConnectorConfig(), "Snowflake",
			"Companies", source, "R31", fakeContext());
		check("PARTIAL".equals(report.getStatus()) && report.isPartial() && !report.isError(), "cut run is PARTIAL, got " + report.getStatus());
		check(report.isTruncated() && report.getTables().get(0).isTruncated(), "truncated on the report and on the table");
		check(report.getMessage().startsWith("PARTIAL") && report.getMessage().contains("500"), "message says PARTIAL and the cap: " + report.getMessage());
		check(report.getRowCount() == 500 && publisher.commitCalled, "the cut set was committed (atomic replace kept): " + report.getRowCount());
		check(report.toJson().get("tables").get(0).get("truncated").asBoolean(), "table JSON carries truncated");
		// exactly at the cap is not a cut
		final FakeRecordSource exact = idKeyedSource(mapping.getId(), 500);
		final ProductPublisher.Report whole = ProductPublisher.compute(request, Arrays.asList(mapping), new FakePublisher(), new ConnectorConfig(), "Snowflake",
			"Companies", exact, "R32", fakeContext());
		check("OK".equals(whole.getStatus()), "500 rows with max 500 is not cut: " + whole.getStatus());
	}

	/** One million rows through a fake that never holds them: loads happen batch by batch and the batch size is never exceeded. */
	private static void testTableNeverHeldInMemory() throws Exception
	{
		final MappingDefinition mapping = batchMapping(580);
		final FakeRecordSource source = new FakeRecordSource();
		source.generated.put(Integer.valueOf(mapping.getId()), Integer.valueOf(1000000));
		final FakePublisher publisher = new FakePublisher();
		publisher.retainBatches = false;
		final ConnectorConfig config = new ConnectorConfig();
		config.setProperty("publish.batchSize", "500");
		final ProductPublisher.Report report = ProductPublisher.compute(request("DB.SCHEMA", "COMPANY_360", false, false, 0), Arrays.asList(mapping), publisher,
			config, "Snowflake", "Companies", source, "R33", fakeContext());
		check("OK".equals(report.getStatus()) && report.getRowCount() == 1000000, "a million rows published: " + report.getMessage());
		check(publisher.batchSizes.size() == 2000, "1,000,000 / 500 = 2000 loads, got " + publisher.batchSizes.size());
		int biggest = 0;
		for (final Integer size : publisher.batchSizes)
		{
			biggest = Math.max(biggest, size.intValue());
		}
		check(biggest == 500, "no load larger than the batch size: " + biggest);
		check(report.getTables().get(0).getBatches() == 2000, "table report batches: " + report.getTables().get(0).getBatches());
	}

	private static void testSourceFailureMidStreamIsAnErrorWithoutCommit() throws Exception
	{
		final MappingDefinition mapping = batchMapping(590);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 1200);
		source.failAfterRows = new IllegalStateException("table gone");
		source.failAtRow = 700;
		final FakePublisher publisher = new FakePublisher();
		final ProductPublisher.Report report = ProductPublisher.compute(request("DB.SCHEMA", "COMPANY_360", false, false, 0), Arrays.asList(mapping), publisher,
			new ConnectorConfig(), "Snowflake", "Companies", source, "R34", fakeContext());
		check("ERROR".equals(report.getStatus()), "a read failure mid-stream is an ERROR, got " + report.getStatus());
		check(report.getMessage().contains("table gone") && report.getMessage().contains("nothing was replaced"), "message: " + report.getMessage());
		check(!publisher.commitCalled && publisher.cleanupCalled, "no commit, staging cleaned up");
	}

	private static void testRowErrorsBeyondTheListingLimitAreCounted() throws Exception
	{
		final MappingDefinition mapping = batchMapping(600);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 3);
		source.unlistedErrors = 7;
		final ProductPublisher.Report report = ProductPublisher.compute(request("DB.SCHEMA", "COMPANY_360", false, false, 0), Arrays.asList(mapping),
			new FakePublisher(), new ConnectorConfig(), "Snowflake", "Companies", source, "R35", fakeContext());
		check(report.getErrors().contains("7 more row error(s) not listed"), "unlisted row errors are counted: " + report.getErrors());
	}

	private static void testDryRunReportsTheCut() throws Exception
	{
		final MappingDefinition mapping = batchMapping(610);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 40);
		final ProductPublisher.Report report = ProductPublisher.compute(request("DB.SCHEMA", "COMPANY_360", true, false, 10), Arrays.asList(mapping),
			new FakePublisher(), new ConnectorConfig(), "Snowflake", "Companies", source, "R36", fakeContext());
		check("DRY_RUN".equals(report.getStatus()) && report.isTruncated(), "dry run flags the cut: " + report.getStatus());
		check(report.getMessage().contains("PARTIAL") && report.getTables().get(0).getRows() == 10, "dry run says a real run would be PARTIAL: " + report.getMessage());
	}

	private static void testRequestMaxHasNoHardCap() throws Exception
	{
		final ProductPublisher.Request request = new ProductPublisher.Request();
		check(request.getMax() == 0, "default max is 0 (every row)");
		request.setMax(5000000);
		check(request.getMax() == 5000000, "no 200000 hard cap any more: " + request.getMax());
		request.setMax(-3);
		check(request.getMax() == 0, "a negative max means every row");
	}

	// ------------------------------------------------------------------ document flattening

	private static void testRowFlattening() throws Exception
	{
		final MappingDefinition mapping = happyMapping();
		final FakeRecordSource source = new FakeRecordSource();
		source.kinds.put("./Identification/Id", Kind.INTEGER);
		source.keys.add("./Identification/Id");
		final List<String> errors = new ArrayList<String>();
		final ProductPublisher.TableWork work = ProductPublisher.buildTableWork(mapping, source, "Companies", errors);

		final ObjectNode document = Json.object();
		document.set("id", IntNode.valueOf(1));
		document.set("name", TextNode.valueOf("Acme"));
		document.set("status", TextNode.valueOf("ACTIVE"));
		final ObjectNode address = document.putObject("address");
		address.put("street", "1 Main");
		address.put("city", "Springfield");
		final ArrayNode identifiers = document.putArray("identifiers");
		identifiers.addObject().put("scheme", "DUNS");

		final ProductPublisher.Row row = new ProductPublisher.Row(document, "1");
		final ObjectNode built = ProductPublisher.buildRow(work, row, "R20260919-140000-0001", "2026-09-19T14:30:00", "1");

		check(built.get("ID").asInt() == 1, "ID flattened: " + built.get("ID"));
		check("Acme".equals(built.get("NAME").asText()), "NAME flattened");
		check("ACTIVE".equals(built.get("STATUS").asText()), "STATUS flattened");
		check(built.get("ADDRESS").isObject() && "1 Main".equals(built.get("ADDRESS").get("street").asText()), "ADDRESS kept as a nested object: " + built.get("ADDRESS"));
		check(built.get("IDENTIFIERS").isArray() && built.get("IDENTIFIERS").size() == 1, "IDENTIFIERS kept as an array");
		check("1".equals(built.get("_EBX_KEY").asText()), "_EBX_KEY is the record's formatted key");
		check("R20260919-140000-0001".equals(built.get("_RUN_ID").asText()), "_RUN_ID filled");
		check("2026-09-19T14:30:00".equals(built.get("_PUBLISHED_AT").asText()), "_PUBLISHED_AT filled");
		check("1".equals(built.get("_CONTRACT_VERSION").asText()), "_CONTRACT_VERSION filled");
	}

	// ------------------------------------------------------------------ batching, commit, failure, dry run

	private static void testBatchingAndCommit() throws Exception
	{
		final MappingDefinition mapping = batchMapping(400);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 1200);
		final FakePublisher publisher = new FakePublisher();
		final ConnectorConfig config = new ConnectorConfig();
		config.setProperty("publish.batchSize", "500");

		final ProductPublisher.Request request = request("DB.SCHEMA", "COMPANY_360", false, false, 10000);
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, config, "Snowflake", "Companies", source,
			"R1", fakeContext());

		check("OK".equals(report.getStatus()), "batching run succeeds: " + report.getMessage() + " " + report.getErrors());
		check(publisher.loadedBatches.size() == 3, "1200 rows / batch 500 -> 3 loads, got " + publisher.loadedBatches.size());
		check(publisher.loadedBatches.get(0).size() == 500 && publisher.loadedBatches.get(1).size() == 500 && publisher.loadedBatches.get(2).size() == 200,
			"batch sizes 500/500/200: " + sizes(publisher.loadedBatches));
		check(publisher.commitCalled, "commit called");
		check(Integer.valueOf(1200).equals(publisher.commitRows.get("BATCH_TABLE")), "commit receives the row count: " + publisher.commitRows);
		check(report.getRowCount() == 1200, "report row count: " + report.getRowCount());
		check(publisher.cleanupCalled, "cleanup called after a successful commit too");
		check(!report.isTruncated(), "not truncated (max 10000 > 1200)");
		check(report.getTables().get(0).getBatches() == 3, "table report batches: " + report.getTables().get(0).getBatches());
	}

	private static void testFailureCallsCleanup() throws Exception
	{
		final MappingDefinition mapping = batchMapping(410);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 10);
		final FakePublisher publisher = new FakePublisher();
		publisher.failOnLoad = true;
		final ConnectorConfig config = new ConnectorConfig();

		final ProductPublisher.Request request = request("DB.SCHEMA", "COMPANY_360", false, false, 10000);
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, config, "Snowflake", "Companies", source,
			"R2", fakeContext());

		check("ERROR".equals(report.getStatus()), "failure surfaces as ERROR");
		check(report.getMessage().contains("boom"), "message carries the connector's exception: " + report.getMessage());
		check(!report.getErrors().isEmpty(), "errors[] carries the failure");
		check(publisher.cleanupCalled, "cleanup still called after a load failure");
		check(!publisher.commitCalled, "commit never called after a load failure");
	}

	private static void testDryRunExecutesNothing() throws Exception
	{
		final MappingDefinition mapping = batchMapping(420);
		final FakeRecordSource source = idKeyedSource(mapping.getId(), 5);
		final FakePublisher publisher = new FakePublisher();
		final ConnectorConfig config = new ConnectorConfig();

		final ProductPublisher.Request request = request("DB.SCHEMA", "COMPANY_360", true, false, 10000);
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, config, "Snowflake", "Companies", source,
			"R3", fakeContext());

		check("DRY_RUN".equals(report.getStatus()), "dry run status: " + report.getStatus());
		check(!publisher.prepareCalled, "prepare never called on a dry run");
		check(publisher.loadedBatches.isEmpty(), "load never called on a dry run");
		check(!publisher.commitCalled, "commit never called on a dry run");
		check(!publisher.cleanupCalled, "cleanup never called on a dry run");
		check(report.getPlan().equals(publisher.planStatements), "the report plan is exactly what the publisher planned");
		check(report.getTables().get(0).getSample().size() == 3, "sample capped at 3, got " + report.getTables().get(0).getSample().size());
		check("PLANNED".equals(report.getTables().get(0).getAction()), "dry-run table action is PLANNED");
		check(report.getTables().get(0).getRows() == 5, "dry run still counts the rows it would publish");

		final com.fasterxml.jackson.databind.JsonNode json = report.toJson();
		check(json.get("plan").size() == publisher.planStatements.size(), "toJson includes the plan on a dry run");
		check(json.get("tables").get(0).get("sample").size() == 3, "toJson includes the sample on a dry run");
	}

	/** §2.3: the report's ebxTable (and the OpenLineage inputs it stands in for) must use the dataspace
	 * actually resolved for the run, not a mapping's own (often null) bound dataspace. */
	private static void testEbxTableUsesResolvedDataspace() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(430, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "AnyDataspace",
			null, "Companies", "/root/Companies", "", true);
		mapping.addInput("table", "company_360");
		mapping.addRule(new MappingRule(1, "./Identification/Id", "id", "", MappingRule.Policy.ALWAYS, null));
		final FakeRecordSource source = new FakeRecordSource();
		source.kinds.put("./Identification/Id", Kind.INTEGER);
		source.keys.add("./Identification/Id");
		final FakePublisher publisher = new FakePublisher();
		final ConnectorConfig config = new ConnectorConfig();

		final ProductPublisher.Request request = request("DB.SCHEMA", "COMPANY_360", true, false, 10000);
		request.setDataspace("Companies"); // what publish() resolves via MappingDefinition.runDataspace when the mapping itself is unbound
		final ProductPublisher.Report report = ProductPublisher.compute(request, Arrays.asList(mapping), publisher, config, "Snowflake", "Companies", source,
			"R4", fakeContext());

		check("DRY_RUN".equals(report.getStatus()), "dry run status: " + report.getStatus());
		final String ebxTable = report.toJson().get("tables").get(0).get("ebxTable").asText();
		check(ebxTable.startsWith("Companies/"), "ebxTable must use the resolved dataspace 'Companies', got " + ebxTable);
	}

	// ------------------------------------------------------------------ fixtures

	private static MappingDefinition happyMapping()
	{
		final MappingDefinition mapping = new MappingDefinition(210, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND,
			"Company 360 -> Snowflake", "Companies", "Companies", "/root/Companies", "", true);
		mapping.addInput("table", "company_360"); // lower-case on purpose: must be upper-cased by the publisher
		mapping.addRule(new MappingRule(1, "./Identification/Id", "id", "", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(2, "./Identification/Name", "name", "", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(3, "", "status", "constant('ACTIVE')", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(4, "./Address/Street", "address.street", "", MappingRule.Policy.ALWAYS, null));
		mapping.addRule(new MappingRule(5, "./Address/City", "address.city", "", MappingRule.Policy.ALWAYS, null));
		final MappingDefinition child = new MappingDefinition(211, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Identifiers",
			"Companies", "Companies", "/root/CompanyIdentifier", "", true);
		child.setParentId(Integer.valueOf(210));
		child.setSubObject("identifiers", "./company", "", "", false);
		mapping.addChild(child);
		return mapping;
	}

	private static MappingDefinition batchMapping(final int pId)
	{
		final MappingDefinition mapping = new MappingDefinition(pId, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Batch",
			"Companies", "Companies", "/root/Companies", "", true);
		mapping.addInput("table", "BATCH_TABLE");
		mapping.addRule(new MappingRule(1, "./Identification/Id", "id", "", MappingRule.Policy.ALWAYS, null));
		return mapping;
	}

	private static FakeRecordSource idKeyedSource(final int pMappingId, final int pRowCount)
	{
		final FakeRecordSource source = new FakeRecordSource();
		source.kinds.put("./Identification/Id", Kind.INTEGER);
		source.keys.add("./Identification/Id");
		final List<ProductPublisher.Row> rows = new ArrayList<ProductPublisher.Row>();
		for (int i = 1; i <= pRowCount; i++)
		{
			final ObjectNode document = Json.object();
			document.set("id", IntNode.valueOf(i));
			rows.add(new ProductPublisher.Row(document, String.valueOf(i)));
		}
		source.rowsByMapping.put(Integer.valueOf(pMappingId), rows);
		return source;
	}

	private static ProductPublisher.Request request(final String pSchema, final String pProduct, final boolean pDryRun, final boolean pRecreate, final int pMax)
	{
		final ProductPublisher.Request request = new ProductPublisher.Request();
		request.setMappings(new ArrayList<Integer>());
		request.setSchema(pSchema);
		request.setProduct(pProduct);
		request.setContractVersion("1");
		request.setDryRun(pDryRun);
		request.setRecreate(pRecreate);
		request.setMax(pMax);
		return request;
	}

	private static ConnectorContext fakeContext()
	{
		return null; // the fake publisher never touches it; the real EbxRecordSource/SnowflakeConnector are not exercised here
	}

	private static String sizes(final List<List<ObjectNode>> pBatches)
	{
		final StringBuilder sb = new StringBuilder();
		for (final List<ObjectNode> b : pBatches)
		{
			sb.append(sb.length() > 0 ? "," : "").append(b.size());
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ fakes

	private static final class FakeRecordSource implements ProductPublisher.RecordSource
	{
		final Map<String, Kind> kinds = new HashMap<String, Kind>();
		final Map<String, Integer> maxLengths = new HashMap<String, Integer>();
		final Set<String> keys = new HashSet<String>();
		final Map<Integer, List<ProductPublisher.Row>> rowsByMapping = new HashMap<Integer, List<ProductPublisher.Row>>();
		int skippedMerged;
		int skippedDeleted;
		boolean rowsCalled;
		/** When set for a mapping, rows are generated one at a time and never held (a table too big for memory). */
		final Map<Integer, Integer> generated = new HashMap<Integer, Integer>();
		RuntimeException failAfterRows;
		int failAtRow;
		int unlistedErrors;

		@Override
		public Kind kindOf(final MappingDefinition pMapping, final MappingRule pRule)
		{
			final Kind k = this.kinds.get(pRule.getSourcePath());
			return k == null ? Kind.STRING : k;
		}

		@Override
		public Integer maxLengthOf(final MappingDefinition pMapping, final MappingRule pRule)
		{
			return this.maxLengths.get(pRule.getSourcePath());
		}

		@Override
		public boolean isKeyPath(final MappingDefinition pMapping, final MappingRule pRule)
		{
			return this.keys.contains(pRule.getSourcePath());
		}

		@Override
		public ProductPublisher.StreamSummary stream(final MappingDefinition pMapping, final int pMax, final List<String> pErrors,
			final ProductPublisher.RowSink pSink) throws ConnectorException
		{
			this.rowsCalled = true;
			final Integer generatedCount = this.generated.get(Integer.valueOf(pMapping.getId()));
			final List<ProductPublisher.Row> all = generatedCount != null ? null : this.rowsByMapping.get(Integer.valueOf(pMapping.getId()));
			final int available = generatedCount != null ? generatedCount.intValue() : all == null ? 0 : all.size();
			final int sent = Math.min(available, pMax);
			for (int i = 0; i < sent; i++)
			{
				if (this.failAfterRows != null && i == this.failAtRow)
				{
					throw this.failAfterRows;
				}
				if (all != null)
				{
					pSink.accept(all.get(i));
				}
				else
				{
					final ObjectNode document = Json.object();
					document.set("id", IntNode.valueOf(i + 1));
					pSink.accept(new ProductPublisher.Row(document, String.valueOf(i + 1)));
				}
			}
			return new ProductPublisher.StreamSummary(sent, available > pMax, this.skippedMerged, this.skippedDeleted, this.unlistedErrors);
		}
	}

	private static final class FakePublisher implements TablePublisher
	{
		boolean prepareCalled;
		boolean commitCalled;
		boolean cleanupCalled;
		boolean failOnPrepare;
		boolean failOnLoad;
		/** false: only the batch sizes are kept, so a test over a huge table does not hold it either. */
		boolean retainBatches = true;
		final List<Integer> batchSizes = new ArrayList<Integer>();
		final List<String> planStatements = Arrays.asList("CREATE SCHEMA IF NOT EXISTS \"DB\".\"SCHEMA\"", "-- planned insert");
		final List<List<ObjectNode>> loadedBatches = new ArrayList<List<ObjectNode>>();
		Map<String, Integer> commitRows;

		@Override
		public String nativeType(final ColumnSpec pColumn)
		{
			return pColumn.getKind().name();
		}

		@Override
		public List<String> plan(final PublicationSpec pSpec, final boolean pRecreate)
		{
			return this.planStatements;
		}

		@Override
		public Map<String, String> prepare(final ConnectorContext pContext, final PublicationSpec pSpec, final boolean pRecreate) throws ConnectorException
		{
			this.prepareCalled = true;
			if (this.failOnPrepare)
			{
				throw new ConnectorException("boom (prepare)");
			}
			final Map<String, String> out = new LinkedHashMap<String, String>();
			for (final TableSpec t : pSpec.getTables())
			{
				out.put(t.getName(), "CREATED");
			}
			return out;
		}

		@Override
		public int load(final ConnectorContext pContext, final PublicationSpec pSpec, final TableSpec pTable, final List<ObjectNode> pDocuments)
			throws ConnectorException
		{
			if (this.failOnLoad)
			{
				throw new ConnectorException("boom (load)");
			}
			this.batchSizes.add(Integer.valueOf(pDocuments.size()));
			if (this.retainBatches)
			{
				this.loadedBatches.add(new ArrayList<ObjectNode>(pDocuments));
			}
			return pDocuments.size();
		}

		@Override
		public void commit(final ConnectorContext pContext, final PublicationSpec pSpec, final Map<String, Integer> pRowsPerTable) throws ConnectorException
		{
			this.commitCalled = true;
			this.commitRows = new LinkedHashMap<String, Integer>(pRowsPerTable);
		}

		@Override
		public void cleanup(final ConnectorContext pContext, final PublicationSpec pSpec)
		{
			this.cleanupCalled = true;
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
