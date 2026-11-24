package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * {@code AbstractFileConnector}'s {@code catalog} operation, backed by an in-memory fake store so
 * the logic under test (inventory + bounded column inference) is exercised independently of any
 * concrete remote store — the same reasoning {@code FtpConnectorTest} applies with its own fake
 * {@code Transport}.
 */
public final class AbstractFileConnectorCatalogTest
{
	private AbstractFileConnectorCatalogTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// Single csv file: one table, right identity fields, inferred columns.
		final FakeFileConnector single = new FakeFileConnector();
		single.configure(new ConnectorConfig());
		single.put("sample.csv", "id;name\r\n1;Alpha\r\n2;Beta\r\n");
		final JsonNode singleCatalog = single.execute(null, "catalog", inputs("SRC"), null);
		check(singleCatalog.path("source").asText().equals("SRC"), "source must round-trip");
		check(!singleCatalog.path("scannedAt").asText("").isEmpty(), "scannedAt must be set");
		check(singleCatalog.path("count").asInt() == 1, "one file must yield one table");
		final JsonNode table = singleCatalog.path("tables").path(0);
		check("SRC:sample.csv".equals(table.path("assetKey").asText()), "assetKey must be source:path");
		check("sample.csv".equals(table.path("qualifiedName").asText()), "qualifiedName must be the file path");
		check("sample.csv".equals(table.path("name").asText()), "name must be the file path");
		check("FILE".equals(table.path("kind").asText()), "kind must be FILE for a file source");
		check(!table.path("scannedAt").asText("").isEmpty(), "table scannedAt must be set");
		check(!table.has("rowCount") && !table.has("schema") && !table.has("url") && !table.has("remarks"),
			"fields with nothing meaningful to report must be omitted, not sent as placeholders");
		final JsonNode columns = table.path("columns");
		check(columns.size() == 2, "csv header id;name must yield 2 inferred columns");
		check("id".equals(columns.path(0).path("name").asText()) && "SRC:sample.csv.id".equals(columns.path(0).path("columnKey").asText()),
			"first column name/columnKey");
		check(columns.path(0).path("position").asInt() == 1 && columns.path(1).path("position").asInt() == 2, "1-based column position");
		check("STRING".equals(columns.path(0).path("dataType").asText()), "a csv cell value must map to STRING");
		check(!columns.path(0).path("nativeType").asText("").isEmpty(), "nativeType must carry the raw flatten() type");
		check(columns.path(0).path("nullable").asBoolean() && !columns.path(0).path("primaryKey").asBoolean(),
			"a single sampled row gives no key/nullability signal: default to nullable, not a key");

		// json sample: number/boolean/string columns map to the coarse catalog taxonomy.
		final FakeFileConnector jsonConn = new FakeFileConnector();
		jsonConn.configure(new ConnectorConfig());
		jsonConn.put("sample.json", "[{\"id\":1,\"active\":true,\"name\":\"Alpha\"}]");
		final JsonNode jsonCatalog = jsonConn.execute(null, "catalog", inputs("SRC"), null);
		final JsonNode jsonColumns = jsonCatalog.path("tables").path(0).path("columns");
		check(dataTypeOf(jsonColumns, "id").equals("NUMBER"), "a JSON number must map to NUMBER");
		check(dataTypeOf(jsonColumns, "active").equals("BOOLEAN"), "a JSON boolean must map to BOOLEAN");
		check(dataTypeOf(jsonColumns, "name").equals("STRING"), "a JSON string must map to STRING");

		// columnsMax=0: the table is still listed, but with no columns inferred.
		final Map<String, String> noColumns = inputs("SRC");
		noColumns.put("columnsMax", "0");
		final JsonNode boundedCatalog = single.execute(null, "catalog", noColumns, null);
		check(boundedCatalog.path("count").asInt() == 1, "columnsMax=0 must still list the table");
		check(boundedCatalog.path("tables").path(0).path("columns").size() == 0, "columnsMax=0 must leave columns empty");

		// Multi-file: columnsMax bounds which files get columns, the rest still appear (empty columns).
		final FakeFileConnector multi = new FakeFileConnector();
		multi.configure(new ConnectorConfig());
		multi.put("a.csv", "id;name\r\n1;Alpha\r\n");
		multi.put("b.csv", "id;name\r\n2;Beta\r\n");
		multi.put("c.csv", "id;name\r\n3;Gamma\r\n");
		final Map<String, String> multiInputs = inputs("SRC");
		multiInputs.put("columnsMax", "2");
		final JsonNode multiCatalog = multi.execute(null, "catalog", multiInputs, null);
		check(multiCatalog.path("count").asInt() == 3, "all three listed files must appear as tables");
		check(multiCatalog.path("tables").path(0).path("columns").size() == 2, "file 1 (within columnsMax) must have columns");
		check(multiCatalog.path("tables").path(1).path("columns").size() == 2, "file 2 (within columnsMax) must have columns");
		check(multiCatalog.path("tables").path(2).path("columns").size() == 0, "file 3 (beyond columnsMax) must be listed with no columns");

		// max bounds the file listing itself.
		final Map<String, String> boundedListing = inputs("SRC");
		boundedListing.put("max", "1");
		final JsonNode boundedListingCatalog = multi.execute(null, "catalog", boundedListing, null);
		check(boundedListingCatalog.path("count").asInt() == 1, "max must bound how many files are listed");

		// A file that fails to read/parse does not abort the scan: it is listed with columnsError, and
		// scanning continues for the remaining files.
		final FakeFileConnector failing = new FakeFileConnector();
		failing.configure(new ConnectorConfig());
		failing.put("broken.json", "not json");
		failing.put("ok.csv", "id;name\r\n1;Alpha\r\n");
		final JsonNode failingCatalog = failing.execute(null, "catalog", inputs("SRC"), null);
		check(failingCatalog.path("count").asInt() == 2, "a broken file must not remove it, or other files, from the scan");
		final JsonNode brokenTable = tableNamed(failingCatalog, "broken.json");
		check(brokenTable.path("columns").size() == 0, "the broken file's columns must stay empty");
		check(!brokenTable.path("columnsError").asText("").isEmpty(), "the broken file's read failure must be reported as columnsError");
		final JsonNode okTable = tableNamed(failingCatalog, "ok.csv");
		check(okTable.path("columns").size() == 2, "a later, valid file must still be scanned after an earlier failure");

		System.out.println("PASS: AbstractFileConnector catalog inventory, column inference, columnsMax/max bounds, and per-file error tolerance");
	}

	private static String dataTypeOf(final JsonNode pColumns, final String pName)
	{
		for (final JsonNode column : pColumns)
		{
			if (pName.equals(column.path("name").asText())) { return column.path("dataType").asText(); }
		}
		throw new AssertionError("Column not found: " + pName);
	}

	private static JsonNode tableNamed(final JsonNode pCatalog, final String pName)
	{
		for (final JsonNode table : pCatalog.path("tables"))
		{
			if (pName.equals(table.path("name").asText())) { return table; }
		}
		throw new AssertionError("Table not found: " + pName);
	}

	private static Map<String, String> inputs(final String pSource)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("source", pSource);
		return inputs;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) { throw new AssertionError(pMessage); }
	}

	/** In-memory {@code AbstractFileConnector}: no network, no filesystem — just a path→bytes map. */
	private static final class FakeFileConnector extends AbstractFileConnector
	{
		private final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();

		void put(final String pPath, final String pContent)
		{
			this.files.put(pPath, pContent.getBytes(StandardCharsets.UTF_8));
		}

		@Override public String getType() { return "fake-file-test"; }
		@Override public String getLabel() { return "Fake file (test only)"; }

		@Override
		protected byte[] readBytes(final String pPath) throws ConnectorException
		{
			return this.files.get(pPath);
		}

		@Override
		protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType) throws ConnectorException
		{
			this.files.put(pPath, pContent);
		}

		@Override
		protected List<Entry> listEntries(final String pPrefix, final int pMax) throws ConnectorException
		{
			final List<Entry> entries = new ArrayList<Entry>();
			for (final String path : this.files.keySet())
			{
				if (entries.size() >= pMax) { break; }
				if (pPrefix != null && !pPrefix.isEmpty() && !path.startsWith(pPrefix)) { continue; }
				entries.add(new Entry(path, this.files.get(path).length, null));
			}
			return entries;
		}
	}
}
