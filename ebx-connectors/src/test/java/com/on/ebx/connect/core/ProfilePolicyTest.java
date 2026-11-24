package com.on.ebx.connect.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.connectors.DatabricksConnector;
import com.on.ebx.connect.connectors.SnowflakeConnector;

/**
 * Offline checks of the profiling budget and cost controls (audit C-15): the pure policy decisions, the per-dialect
 * statements, and the profile answer of both statement-API platforms against a fake runner. No account, no network.
 */
public final class ProfilePolicyTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		testDefaults();
		testInputBeatsPropertyBeatsDefault();
		testDecisions();
		testSamplePercentAndText();
		testBudgetOnAFakeClock();
		for (final String platform : new String[] { "snowflake", "databricks" })
		{
			testDefaultProfileIsExactAndSaysSo(platform);
			testLargeTableIsSampledAndSaysSo(platform);
			testExactOverrideAndSkip(platform);
			testTableBudget(platform);
			testDryRunRunsNoProfileStatement(platform);
			testApproxDistinct(platform);
		}
		System.out.println("PASS: ProfilePolicy (defaults, decisions, budget, sampling statements per dialect, dry run, exactness markers)");
	}

	private static Map<String, String> inputs(final String... pPairs)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("source", "DWH");
		for (int i = 0; i < pPairs.length; i += 2)
		{
			inputs.put(pPairs[i], pPairs[i + 1]);
		}
		return inputs;
	}

	private static CatalogOperations.Dialect dialect(final String pPlatform)
	{
		return "snowflake".equals(pPlatform) ? new SnowflakeConnector.SnowflakeDialect("ANALYTICS") : new DatabricksConnector.DatabricksDialect("analytics");
	}

	private static int profileStatements(final CatalogOperationsTest.FakeRunner pRunner)
	{
		int n = 0;
		for (final String sql : pRunner.sql)
		{
			if (sql.startsWith("SELECT COUNT(*) AS N0")) n++;
		}
		return n;
	}

	private static void testDefaults() throws Exception
	{
		final ProfilePolicy policy = ProfilePolicy.of(inputs(), null);
		check(policy.maxTables() == 200 && policy.maxSeconds() == 900L, "budget defaults 200 tables / 900 s");
		check(policy.large() == ProfilePolicy.Large.SAMPLE && policy.largeTableRows() == 10000000L && policy.sampleRows() == 1000000L,
			"owner decision: exact below 10,000,000 rows, sampled above, by default");
		check(!policy.approxDistinct() && !policy.dryRun(), "approximate distinct and dry run are off by default");
	}

	private static void testInputBeatsPropertyBeatsDefault() throws Exception
	{
		final Map<String, String> properties = new HashMap<String, String>();
		properties.put("profile.maxTables", "50");
		properties.put("profile.largeTableRows", "5000000");
		properties.put("profile.maxSeconds", "lots");
		final Function<String, String> props = properties::get;
		final ProfilePolicy fromProperties = ProfilePolicy.of(inputs(), props);
		check(fromProperties.maxTables() == 50 && fromProperties.largeTableRows() == 5000000L, "property read");
		check(fromProperties.maxSeconds() == 900L, "an unreadable property keeps the default");
		final ProfilePolicy fromInput = ProfilePolicy.of(inputs("maxTables", "7", "largeTables", "exact", "maxSeconds", "0", "dryRun", "true", "approxDistinct", "true"), props);
		check(fromInput.maxTables() == 7 && fromInput.maxSeconds() == 0 && fromInput.large() == ProfilePolicy.Large.EXACT && fromInput.dryRun() && fromInput.approxDistinct(),
			"input wins over property and default; 0 lifts a limit");
		check(ProfilePolicy.of(inputs("maxTables", "-3"), null).maxTables() == 200, "a negative value keeps the default");
		check(ProfilePolicy.of(inputs("largeTables", "skip"), null).large() == ProfilePolicy.Large.SKIP, "skip");
	}

	private static void testDecisions() throws Exception
	{
		final ProfilePolicy policy = ProfilePolicy.of(inputs(), null);
		check(policy.decide(9999999L, true).mode() == ProfilePolicy.Mode.FULL_SCAN, "just below the threshold: exact");
		final ProfilePolicy.Decision big = policy.decide(10000000L, true);
		check(big.mode() == ProfilePolicy.Mode.SAMPLE && Math.abs(big.percent() - 10d) < 1e-9, "at the threshold: sampled, 1M of 10M rows = 10 percent: " + big.percent());
		check(policy.decide(-1L, true).mode() == ProfilePolicy.Mode.FULL_SCAN, "unknown size (a view): scanned in full, there is nothing to compare");
		check(policy.decide(50000000L, false).mode() == ProfilePolicy.Mode.SKIP && policy.decide(50000000L, false).reason().contains("no sampling"),
			"a platform without a sampling clause skips rather than scans a large table");
		check(ProfilePolicy.of(inputs("largeTables", "exact"), null).decide(5000000000L, true).mode() == ProfilePolicy.Mode.FULL_SCAN, "exact override scans anything");
		check(ProfilePolicy.of(inputs("largeTables", "skip"), null).decide(10000000L, true).mode() == ProfilePolicy.Mode.SKIP, "skip");
	}

	private static void testSamplePercentAndText() throws Exception
	{
		check(ProfilePolicy.samplePercent(1000L, 5000L) == 100d, "a sample larger than the table is the table");
		check(ProfilePolicy.samplePercent(0L, 5L) == 100d, "empty table");
		check(ProfilePolicy.samplePercent(2000000000L, 1000000L) == 0.05d, "1M of 2G rows = 0.05 percent");
		check(ProfilePolicy.samplePercent(Long.MAX_VALUE, 1L) >= 0.0001d, "never below the minimum");
		check("10".equals(ProfilePolicy.percentText(10d)) && "0.05".equals(ProfilePolicy.percentText(0.05d)) && "100".equals(ProfilePolicy.percentText(100d))
			&& "0.0001".equals(ProfilePolicy.percentText(0.0001d)), "percent text has no exponent and no trailing zeros: " + ProfilePolicy.percentText(0.05d));
	}

	private static void testBudgetOnAFakeClock() throws Exception
	{
		final long[] now = { 1000L };
		final ProfilePolicy policy = ProfilePolicy.of(inputs("maxTables", "2", "maxSeconds", "10"), null);
		final ProfilePolicy.Run run = new ProfilePolicy.Run(policy, () -> now[0]);
		check(run.mayStartTable(), "first table");
		run.profiled(false);
		now[0] += 9000L;
		check(run.mayStartTable(), "9 s in: still within 10 s");
		run.profiled(true);
		check(!run.mayStartTable() && "maxTables".equals(run.stoppedBy()), "third table: the table budget is spent first");
		run.skipForBudget("S.T3");
		now[0] += 5000L;
		check(!run.mayStartTable() && "maxTables".equals(run.stoppedBy()), "the first limit reached is the one remembered");
		check(run.partial() && run.tablesProfiled() == 2 && run.tablesSampled() == 1, "counts");
		final JsonNode json = run.toJson();
		check("maxTables".equals(json.get("stoppedBy").asText()) && json.get("skipped").get(0).get("table").asText().equals("S.T3")
			&& json.get("skipped").get(0).get("reason").asText().contains("maxTables"), "budget block names what was left out: " + json);

		final long[] later = { 0L };
		final ProfilePolicy.Run timed = new ProfilePolicy.Run(ProfilePolicy.of(inputs("maxSeconds", "3"), null), () -> later[0]);
		check(timed.mayStartTable(), "time budget: start");
		later[0] = 3000L;
		check(!timed.mayStartTable() && "maxSeconds".equals(timed.stoppedBy()), "time budget spent at 3 s");
		final ProfilePolicy.Run unlimited = new ProfilePolicy.Run(ProfilePolicy.of(inputs("maxTables", "0", "maxSeconds", "0"), null), () -> later[0] += 1000000L);
		for (int i = 0; i < 1000; i++)
		{
			unlimited.profiled(false);
		}
		check(unlimited.mayStartTable() && !unlimited.partial(), "0 lifts both limits");
	}

	private static void testDefaultProfileIsExactAndSaysSo(final String pPlatform) throws Exception
	{
		final CatalogOperationsTest.FakeRunner runner = new CatalogOperationsTest.FakeRunner(pPlatform);
		final JsonNode profile = CatalogOperations.profile(runner, dialect(pPlatform), inputs(), null, null);
		final JsonNode column = profile.get("columns").get(1);
		check("FULL_SCAN".equals(column.get("method").asText()) && column.get("exact").asBoolean() && "EXACT".equals(column.get("distinctMethod").asText()), pPlatform + " exact markers");
		check(!column.has("sampleSize") && profile.get("exact").asBoolean() && !profile.get("partial").asBoolean(), pPlatform + " answer is exact");
		check(profile.get("budget").get("tablesProfiled").asInt() == 2 && profile.get("budget").get("maxTables").asInt() == 200, pPlatform + " budget block");
		for (final String sql : runner.sql)
		{
			check(!sql.contains("SAMPLE") && !sql.contains("APPROX") && !sql.toLowerCase().contains("approx_count"), pPlatform + " no sampling or approximation by default: " + sql);
		}
	}

	private static void testLargeTableIsSampledAndSaysSo(final String pPlatform) throws Exception
	{
		final CatalogOperationsTest.FakeRunner runner = new CatalogOperationsTest.FakeRunner(pPlatform);
		// ORDERS holds 1000 rows in the fake catalog: threshold 500 makes it large, 100 sample rows = 10 percent
		final JsonNode profile = CatalogOperations.profile(runner, dialect(pPlatform), inputs("largeTableRows", "500", "sampleRows", "100"), null, null);
		String sampled = null;
		for (final String sql : runner.sql)
		{
			if (sql.startsWith("SELECT COUNT(*) AS N0") && sql.contains("STATUS")) sampled = sql;
		}
		final String clause = "snowflake".equals(pPlatform) ? " SAMPLE SYSTEM (10)" : " TABLESAMPLE (10 PERCENT)";
		check(sampled != null && sampled.contains(clause), pPlatform + " sampling clause right after the table: " + sampled);
		check(sampled.indexOf(clause) > sampled.indexOf(" FROM "), pPlatform + " clause in the FROM part");
		final JsonNode column = profile.get("columns").get(1);
		check("SAMPLE".equals(column.get("method").asText()) && !column.get("exact").asBoolean() && column.get("sampleSize").asLong() == 1000L
			&& Math.abs(column.get("samplePercent").asDouble() - 10d) < 1e-9, pPlatform + " sampled markers: " + column);
		check(column.get("rowCount").asLong() == 1000L && column.get("nullCount").asLong() == 40L, pPlatform + " counts scaled to the catalog row count: " + column);
		check(!profile.get("exact").asBoolean() && profile.get("budget").get("tablesSampled").asInt() == 1, pPlatform + " the answer says it is not exact");
		// the view has no known size: scanned in full whatever the threshold
		boolean viewExact = false;
		for (final JsonNode c : profile.get("columns"))
		{
			if ("NET_REVENUE".equals(c.get("name").asText())) viewExact = "FULL_SCAN".equals(c.get("method").asText());
		}
		check(viewExact, pPlatform + " a table of unknown size is profiled in full");
	}

	private static void testExactOverrideAndSkip(final String pPlatform) throws Exception
	{
		final CatalogOperationsTest.FakeRunner exact = new CatalogOperationsTest.FakeRunner(pPlatform);
		final JsonNode all = CatalogOperations.profile(exact, dialect(pPlatform), inputs("largeTableRows", "500", "largeTables", "exact"), null, null);
		check(all.get("exact").asBoolean() && all.get("columns").get(1).get("method").asText().equals("FULL_SCAN"), pPlatform + " exact override");
		final CatalogOperationsTest.FakeRunner skip = new CatalogOperationsTest.FakeRunner(pPlatform);
		final JsonNode skipped = CatalogOperations.profile(skip, dialect(pPlatform), inputs("largeTableRows", "500", "largeTables", "skip"), null, null);
		check(skipped.get("partial").asBoolean() && skipped.get("budget").get("tablesSkipped").asInt() == 1
			&& "SALES.ORDERS".equals(skipped.get("budget").get("skipped").get(0).get("table").asText())
			&& skipped.get("budget").get("skipped").get(0).get("reason").asText().contains("1000 row"), pPlatform + " skip is named: " + skipped.get("budget"));
		check(profileStatements(skip) == 1, pPlatform + " only the view was profiled");
	}

	private static void testTableBudget(final String pPlatform) throws Exception
	{
		final CatalogOperationsTest.FakeRunner runner = new CatalogOperationsTest.FakeRunner(pPlatform);
		final JsonNode profile = CatalogOperations.profile(runner, dialect(pPlatform), inputs("maxTables", "1"), null, null);
		check(profileStatements(runner) == 1, pPlatform + " one profile statement with maxTables=1");
		check(profile.get("partial").asBoolean() && !profile.get("exact").asBoolean() && "maxTables".equals(profile.get("budget").get("stoppedBy").asText())
			&& "FINANCE.REVENUE".equals(profile.get("budget").get("skipped").get(0).get("table").asText()), pPlatform + " the table left out is named: " + profile.get("budget"));
	}

	private static void testDryRunRunsNoProfileStatement(final String pPlatform) throws Exception
	{
		final CatalogOperationsTest.FakeRunner runner = new CatalogOperationsTest.FakeRunner(pPlatform);
		final JsonNode plan = CatalogOperations.profile(runner, dialect(pPlatform), inputs("dryRun", "true", "largeTableRows", "500", "sampleRows", "100"), null, null);
		check(profileStatements(runner) == 0, pPlatform + " no profile statement executed on a dry run: " + runner.sql);
		check(plan.get("dryRun").asBoolean() && plan.get("count").asInt() == 0 && plan.get("columns").size() == 0, pPlatform + " nothing to write");
		final JsonNode tables = plan.get("plan").get("tables");
		check(tables.size() == 2 && tables.get(0).get("sql").asText().startsWith("SELECT COUNT(*) AS N0") && "SAMPLE".equals(tables.get(0).get("method").asText())
			&& tables.get(0).get("rowCount").asLong() == 1000L && "FULL_SCAN".equals(tables.get(1).get("method").asText()), pPlatform + " plan: " + tables);
		check(plan.get("plan").get("statements").asInt() == 2 && plan.get("plan").get("estimatedRowsScanned").asLong() == 100L
			&& plan.get("plan").get("tablesOfUnknownSize").asInt() == 1, pPlatform + " estimated work: " + plan.get("plan"));
	}

	private static void testApproxDistinct(final String pPlatform) throws Exception
	{
		final CatalogOperationsTest.FakeRunner runner = new CatalogOperationsTest.FakeRunner(pPlatform);
		final JsonNode profile = CatalogOperations.profile(runner, dialect(pPlatform), inputs("approxDistinct", "true"), null, null);
		boolean found = false;
		for (final String sql : runner.sql)
		{
			if (sql.startsWith("SELECT COUNT(*) AS N0") && sql.toLowerCase().contains("approx_count_distinct(") && !sql.contains("COUNT(DISTINCT")) found = true;
		}
		check(found, pPlatform + " approximate distinct statement");
		final JsonNode column = profile.get("columns").get(1);
		check("APPROXIMATE".equals(column.get("distinctMethod").asText()) && !column.get("exact").asBoolean() && "FULL_SCAN".equals(column.get("method").asText()),
			pPlatform + " approximate figures say so: " + column);
	}

	private static void check(final boolean pValue, final String pMessage)
	{
		if (!pValue) throw new AssertionError(pMessage);
	}
}
