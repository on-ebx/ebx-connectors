package com.on.ebx.connect.live;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.connectors.ClouderaHiveConnector;
import com.on.ebx.connect.connectors.ClouderaImpalaConnector;
import com.on.ebx.connect.connectors.JdbcConnector;
import com.on.ebx.connect.connectors.SparkSqlConnector;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.DiscoveredObject;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.ObjectDescription;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;
import com.on.ebx.connect.spi.PreviewRequest;
import com.on.ebx.connect.spi.PreviewResult;

/**
 * Plays every operation of the HiveServer2 profiles (Cloudera Hive, Cloudera Impala over the Hive driver, Apache Spark
 * SQL) against a real HiveServer2: argument 1 is the JDBC URL, argument 2 the schema {@code tools/live-hive/setup.sql}
 * created. It is compiled with the test sources and run by {@code tools/live-hive/run.sh} only, never by the offline
 * suite: it needs a server and the Hive JDBC driver on the class path, neither of which the module ships.
 * <p>
 * The generic JDBC engine is played first, on purpose: its failures on this driver (read-only mode and rollback refused)
 * are the reason for {@code JdbcConnector.withoutTransactions()}, and they are counted apart. Each step prints OK or FAIL
 * with what came back; the exit code is 1 when a step of a profile failed.
 */
public final class HiveServer2LiveCheck
{
	private static int ok;
	private static int failed;
	private static int genericFailed;
	private static boolean generic;

	public static void main(final String[] pArgs) throws Exception
	{
		final String url = pArgs[0];
		final String schema = pArgs.length > 1 ? pArgs[1] : "ebxtest";
		driver(url);
		generic = true;
		profile("generic jdbc engine (expected to fail on this driver)", new JdbcConnector(), url, schema, true);
		generic = false;
		profile("spark-sql", new SparkSqlConnector(), url, schema, true);
		profile("cloudera-hive", new ClouderaHiveConnector(), url, schema, true);
		profile("cloudera-impala over hive2", new ClouderaImpalaConnector(), url, schema, false);
		{
			System.out.println("\n== cloudera-hive, no schema property");
			final ClouderaHiveConnector connector = new ClouderaHiveConnector();
			final ConnectorConfig config = new ConnectorConfig();
			config.setId("LIVE");
			config.setBaseUrl(url);
			config.setAuthType(ConnectorConfig.AuthType.BASIC);
			config.setUsername("ebx");
			connector.configure(config);
			step("discoverObjects, every schema", () -> {
				final ObjectDiscoveryResult result = connector.discoverObjects(null, ObjectDiscoveryRequest.firstPage(null, 200));
				final java.util.TreeMap<String, Integer> perSchema = new java.util.TreeMap<String, Integer>();
				for (final DiscoveredObject object : result.getObjects())
				{
					final String schemaName = object.getId().contains(".") ? object.getId().substring(0, object.getId().indexOf('.')) : "(none)";
					perSchema.put(schemaName, perSchema.containsKey(schemaName) ? perSchema.get(schemaName) + 1 : 1);
				}
				return result.getObjects().size() + " object(s), truncated=" + result.isTruncated() + " per schema " + perSchema;
			});
			step("discoverObjects, search 'cust'", () -> {
				final StringBuilder names = new StringBuilder();
				for (final DiscoveredObject object : connector.discoverObjects(null, ObjectDiscoveryRequest.firstPage("cust", 50)).getObjects()) names.append(object.getId()).append(' ');
				return names.toString();
			});
			step("catalog, every schema", () -> {
				final JsonNode document = connector.execute(null, "catalog", inputs("source", "LIVE"), null);
				final java.util.TreeMap<String, Integer> perSchema = new java.util.TreeMap<String, Integer>();
				for (final JsonNode table : document.path("tables"))
				{
					final String schemaName = table.path("schema").asText();
					perSchema.put(schemaName, perSchema.containsKey(schemaName) ? perSchema.get(schemaName) + 1 : 1);
				}
				return document.path("count").asInt() + " table(s) per schema " + perSchema;
			});
		}
		System.out.println("\nTOTAL profiles ok=" + ok + " failed=" + failed + " | generic engine failed=" + genericFailed + " (expected)");
		if (failed > 0) System.exit(1);
	}

	/** What the real driver does on the calls the engine makes. */
	private static void driver(final String pUrl) throws Exception
	{
		System.out.println("== driver, direct");
		Class.forName("org.apache.hive.jdbc.HiveDriver");
		try (Connection connection = DriverManager.getConnection(pUrl, "ebx", ""))
		{
			System.out.println("product: " + connection.getMetaData().getDatabaseProductName() + " " + connection.getMetaData().getDatabaseProductVersion()
				+ " | driver " + connection.getMetaData().getDriverVersion());
			System.out.println("identifier quote: [" + connection.getMetaData().getIdentifierQuoteString() + "] supportsTransactions="
				+ connection.getMetaData().supportsTransactions() + " catalogTerm=" + connection.getMetaData().getCatalogTerm() + " schemaTerm="
				+ connection.getMetaData().getSchemaTerm());
			call("setReadOnly(true)", () -> { connection.setReadOnly(true); return "accepted, isReadOnly=" + connection.isReadOnly(); });
			call("setAutoCommit(false)", () -> { connection.setAutoCommit(false); return "accepted, getAutoCommit=" + connection.getAutoCommit(); });
			call("rollback()", () -> { connection.rollback(); return "accepted"; });
			call("commit()", () -> { connection.commit(); return "accepted"; });
		}
	}

	private interface Step { Object run() throws Exception; }

	private static void call(final String pName, final Step pStep)
	{
		try
		{
			System.out.println("  " + pName + " -> " + pStep.run());
		}
		catch (final Exception ex)
		{
			System.out.println("  " + pName + " -> THROWS " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
	}

	private static void step(final String pName, final Step pStep)
	{
		final long start = System.currentTimeMillis();
		try
		{
			final Object result = pStep.run();
			if (!generic) ok++;
			System.out.println("  OK   " + pName + " (" + (System.currentTimeMillis() - start) + " ms): " + shorten(String.valueOf(result)));
		}
		catch (final Throwable ex)
		{
			if (generic) genericFailed++;
			else failed++;
			Throwable root = ex;
			while (root.getCause() != null && root.getCause() != root) root = root.getCause();
			System.out.println("  FAIL " + pName + " (" + (System.currentTimeMillis() - start) + " ms): " + ex.getClass().getSimpleName() + ": "
				+ shorten(String.valueOf(ex.getMessage())) + (root == ex ? "" : " | root " + root.getClass().getSimpleName() + ": " + shorten(String.valueOf(root.getMessage()))));
		}
	}

	private static String shorten(final String pText)
	{
		final String flat = pText.replace('\n', ' ');
		return flat.length() > 900 ? flat.substring(0, 900) + " …(" + flat.length() + " chars)" : flat;
	}

	private static void profile(final String pName, final JdbcConnector pConnector, final String pUrl, final String pSchema, final boolean pProfile)
	{
		System.out.println("\n== " + pName + " (" + pConnector.getClass().getSimpleName() + ")");
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("LIVE");
		config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC);
		config.setUsername("ebx");
		config.setProperty("driver", "org.apache.hive.jdbc.HiveDriver");
		config.setProperty("schema", pSchema);
		pConnector.configure(config);
		System.out.println("  validation: " + (pConnector.validateConfiguration(config).isValid() ? "valid" : describe(pConnector, config)));

		step("testConnection", () -> pConnector.testConnection(null).getMessage());
		step("query", () -> pConnector.execute(null, "query", inputs("sql", "SELECT id, name, city, amount, born, updated_at FROM " + pSchema + ".customer ORDER BY id"), null));
		step("query with parameters", () -> pConnector.execute(null, "query",
			inputs("sql", "SELECT id, name FROM " + pSchema + ".customer WHERE city = :city AND id >= :min", "city", "L'Haÿ-les-Roses", "min", "1"), null));
		step("query, quote in a value", () -> pConnector.execute(null, "query",
			inputs("sql", "SELECT COUNT(*) AS n FROM " + pSchema + ".customer WHERE name = :name", "name", "x' OR '1'='1"), null));
		step("query max=2 (truncation)", () -> pConnector.execute(null, "query", inputs("sql", "SELECT id FROM " + pSchema + ".customer ORDER BY id", "max", "2"), null));
		step("streamed query", () -> {
			final List<JsonNode> rows = new ArrayList<JsonNode>();
			final Connector.RowStreamResult result = pConnector.readRows(null, "query", inputs("sql", "SELECT id, name FROM " + pSchema + ".customer"), "rows", 100,
				new Connector.RowSink() { public boolean accept(final JsonNode pRow) { rows.add(pRow); return true; } });
			return "count=" + result.count + " truncated=" + result.truncated + " first=" + (rows.isEmpty() ? null : rows.get(0));
		});
		step("preview of a query", () -> {
			final PreviewResult result = pConnector.preview(null, new PreviewRequest(null, "query", inputs("sql", "SELECT id, name FROM " + pSchema + ".customer"), 3));
			if (!result.isSupported()) throw new IllegalStateException("unsupported: " + result.getMessage());
			return result.getRecords().size() + " record(s), truncated=" + result.isTruncated() + " " + result.getRecords();
		});
		step("preview of a table", () -> {
			final PreviewResult result = pConnector.preview(null, new PreviewRequest(pSchema + ".customer", "get", null, 3));
			if (!result.isSupported()) throw new IllegalStateException("unsupported: " + result.getMessage());
			return result.getRecords().size() + " record(s) " + result.getRecords();
		});
		step("SELECT * query", () -> pConnector.execute(null, "query", inputs("sql", "SELECT * FROM " + pSchema + ".customer WHERE id = 3"), null));
		step("SELECT * streamed", () -> {
			final List<JsonNode> rows = new ArrayList<JsonNode>();
			pConnector.readRows(null, "query", inputs("sql", "SELECT * FROM " + pSchema + ".order_line"), "rows", 100,
				new Connector.RowSink() { public boolean accept(final JsonNode pRow) { rows.add(pRow); return true; } });
			return rows.get(0);
		});
		step("SELECT * of a join (same column names)", () -> pConnector.execute(null, "query",
			inputs("sql", "SELECT * FROM " + pSchema + ".customer c JOIN " + pSchema + ".customer d ON c.id = d.id WHERE c.id = 1"), null));
		step("describeFields of SELECT *", () -> {
			final StringBuilder fields = new StringBuilder();
			for (final FieldInfo field : pConnector.describeFields(null, "query", inputs("sql", "SELECT * FROM " + pSchema + ".order_line"))) fields.append(field.getPath()).append(':').append(field.getType()).append(' ');
			return fields.toString();
		});
		step("odd column names", () -> pConnector.execute(null, "query", inputs("sql", "SELECT `date`, `select`, `my col`, `ünï` FROM " + pSchema + ".odd_names"), null));
		step("date and decimal parameters", () -> pConnector.execute(null, "query",
			inputs("sql", "SELECT id, born, amount FROM " + pSchema + ".customer WHERE born >= :d AND amount > :a ORDER BY id", "d", "1900-01-01", "a", "50.5"), null));
		if (!generic) step("script statement refused", () -> {
			try
			{
				pConnector.execute(null, "query", inputs("sql", "SELECT TRANSFORM(name) USING '/bin/cat' AS n FROM " + pSchema + ".customer"), null);
			}
			catch (final Exception refused)
			{
				return "refused: " + refused.getMessage();
			}
			throw new IllegalStateException("the script statement RAN");
		});
		if (!generic) step("write refused", () -> {
			try
			{
				pConnector.execute(null, "execute", inputs("sql", "CREATE TABLE " + pSchema + ".should_not_exist (a INT)"), null);
			}
			catch (final Exception refused)
			{
				return "refused: " + refused.getMessage();
			}
			throw new IllegalStateException("the write RAN");
		});
		step("discoverObjects", () -> {
			final ObjectDiscoveryResult result = pConnector.discoverObjects(null, ObjectDiscoveryRequest.firstPage(null, 50));
			if (!result.isSupported()) throw new IllegalStateException("unsupported: " + result.getMessage());
			final StringBuilder names = new StringBuilder();
			for (final DiscoveredObject object : result.getObjects()) names.append(object.getId()).append('[').append(object.getType()).append("] ");
			return result.getObjects().size() + " object(s): " + names;
		});
		step("describeObject", () -> {
			final ObjectDescription description = pConnector.describeObject(null, pSchema + ".customer");
			final StringBuilder fields = new StringBuilder();
			for (final FieldInfo field : description.getFields()) fields.append(field.getPath()).append(':').append(field.getType()).append(' ');
			if (description.getFields().isEmpty()) throw new IllegalStateException("no field: " + description.getMessage());
			return fields.toString();
		});
		step("describeFields of a query", () -> {
			final StringBuilder fields = new StringBuilder();
			for (final FieldInfo field : pConnector.describeFields(null, "query", inputs("sql", "SELECT id, name, amount FROM " + pSchema + ".customer"))) fields.append(field.getPath()).append(':').append(field.getType()).append(' ');
			if (fields.length() == 0) throw new IllegalStateException("no field");
			return fields.toString();
		});
		step("catalog", () -> pConnector.execute(null, "catalog", inputs("source", "LIVE", "schemas", pSchema), null));
		step("catalog with row counts", () -> {
			final JsonNode document = pConnector.execute(null, "catalog", inputs("source", "LIVE", "schemas", pSchema, "rowCounts", "true"), null);
			final StringBuilder counts = new StringBuilder();
			for (final JsonNode table : document.findParents("rowCount")) counts.append(table.path("name").asText()).append('=').append(table.path("rowCount").asText()).append(' ');
			for (final JsonNode table : document.findParents("rowCountError")) counts.append(table.path("name").asText()).append(" ERROR ").append(table.path("rowCountError").asText()).append(' ');
			if (counts.length() == 0) throw new IllegalStateException("no row count in " + document);
			return counts.toString();
		});
		if (pProfile)
		{
			step("profile", () -> pConnector.execute(null, "profile", inputs("source", "LIVE", "schemas", pSchema, "tables", "*"), null));
		}
		step("sample", () -> pConnector.execute(null, "sample", inputs("source", "LIVE", "schemas", pSchema, "tables", "*", "limit", "5"), null));
	}

	private static String describe(final JdbcConnector pConnector, final ConnectorConfig pConfig)
	{
		final StringBuilder text = new StringBuilder("INVALID ");
		for (final com.on.ebx.connect.spi.ConfigurationValidationIssue issue : pConnector.validateConfiguration(pConfig).getIssues())
		{
			text.append('[').append(issue.getPropertyName()).append(": ").append(issue.getMessage()).append("] ");
		}
		return text.toString();
	}

	private static Map<String, String> inputs(final String... pPairs)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		for (int i = 0; i + 1 < pPairs.length; i += 2) inputs.put(pPairs[i], pPairs[i + 1]);
		return inputs;
	}
}
