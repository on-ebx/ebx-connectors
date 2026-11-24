package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;

/** Read-only CSV/JSON sample serving, write refusal, and connection-test reporting. */
public final class SampleFileConnectorTest
{
	private SampleFileConnectorTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final SampleFileConnector connector = new SampleFileConnector();

		// No sample uploaded yet: reads answer nothing, listing is empty, the connection test fails clearly.
		connector.configure(new ConnectorConfig());
		check(connector.execute(null, "read", inputs("sample.csv"), null) == null, "read with no sample must answer null");
		final JsonNode emptyList = connector.execute(null, "list", inputs("sample.csv"), null);
		check(emptyList == null, "list with no sample must answer null");
		final ConnectionTestResult noSample = connector.testConnection(null);
		check(noSample.isSupported() && !noSample.isSuccessful(), "connection test must fail with no sample uploaded");

		// CSV sample, using AbstractFileConnector's own file-connector default separator (';').
		final ConnectorConfig csvConfig = new ConnectorConfig();
		csvConfig.setSampleData(dataUrl("text/csv", "id;name\r\n1;Alpha\r\n2;Beta\r\n"));
		connector.configure(csvConfig);
		final JsonNode csvRead = connector.execute(null, "read", inputs("sample.csv"), null);
		check(csvRead != null && csvRead.path("count").asInt() == 2, "csv sample must yield 2 rows");
		check("Alpha".equals(csvRead.path("rows").path(0).path("name").asText()), "csv row value must round-trip");

		// The comma-separated "separator" override, exposed as an ordinary ConnectorProperty (not a
		// dedicated field): an explicit "separator" input reads a differently delimited sample correctly,
		// and content is always the stored sample regardless of the path value (no extension needed once
		// format/separator are given explicitly).
		final ConnectorConfig commaConfig = new ConnectorConfig();
		commaConfig.setSampleData(dataUrl("text/csv", "id,name\r\n1,Alpha\r\n2,Beta\r\n"));
		connector.configure(commaConfig);
		final Map<String, String> commaSeparated = inputs("sample");
		commaSeparated.put("format", "csv");
		commaSeparated.put("separator", ",");
		final JsonNode csvReadComma = connector.execute(null, "read", commaSeparated, null);
		check(csvReadComma != null && csvReadComma.path("count").asInt() == 2
			&& "Alpha".equals(csvReadComma.path("rows").path(0).path("name").asText()),
			"an explicit separator input must override the file-connector default (';') for a comma-separated sample");
		connector.configure(csvConfig);

		final java.util.List<FieldInfo> csvFields = connector.describeFields(null, "read", inputs("sample.csv"));
		check(hasField(csvFields, "rows[0].id") && hasField(csvFields, "rows[0].name"), "describeFields must infer id and name from the csv header");

		final JsonNode csvListing = connector.execute(null, "list", inputs("sample.csv"), null);
		check(csvListing != null && csvListing.path("count").asInt() == 1
			&& "sample.csv".equals(csvListing.path("results").path(0).path("path").asText()), "csv listing must expose one synthetic sample.csv entry");

		final ConnectionTestResult csvConnected = connector.testConnection(null);
		check(csvConnected.isSuccessful() && csvConnected.getMessage().contains("sample.csv"), "connection test must succeed once a csv sample is uploaded");

		// 2026-10-17: a comma-separated sample with NO separator configured is detected, not read as one column.
		final ConnectorConfig detectConfig = new ConnectorConfig();
		detectConfig.setSampleData(dataUrl("text/csv", "card_number,civilite,nom\r\nF1,M,Dupont\r\nF2,Mme,Martin\r\n"));
		connector.configure(detectConfig);
		final JsonNode detectRead = connector.execute(null, "read", inputs("sample.csv"), null);
		check(detectRead != null && detectRead.path("count").asInt() == 2 && "Dupont".equals(detectRead.path("rows").path(0).path("nom").asText()),
			"a comma csv sample must be split into columns without a configured separator");
		final JsonNode detectCatalog = connector.execute(null, "catalog", srcInputs(), null);
		check(detectCatalog.path("tables").path(0).path("columnsComplete").asBoolean(), "a fully inferred asset must be flagged columnsComplete");
		final Map<String, String> noColumns = srcInputs();
		noColumns.put("columnsMax", "0");
		final JsonNode notReread = connector.execute(null, "catalog", noColumns, null).path("tables").path(0);
		check(notReread.has("columnsComplete") && !notReread.path("columnsComplete").asBoolean() && notReread.path("columns").size() == 0,
			"an asset whose columns were not re-read must carry columnsComplete=false (the flow overwrites an old true)");
		// (2026-10-17) the wrong-separator rescan: the first scan (separator ';' configured) returns one merged column, the fixed one the real
		// columns only; every returned column carries the scan's own scannedAt, the merged one is simply absent from the second document
		// (it is the catalog that marks it gone from the complete flag and columnsScannedAt, not the connector)
		final ConnectorConfig wrongConfig = new ConnectorConfig();
		wrongConfig.setSampleData(dataUrl("text/csv", "card_number,civilite,nom\r\nF1,M,Dupont\r\n"));
		wrongConfig.setProperty("separator", ";");
		connector.configure(wrongConfig);
		final JsonNode firstScan = connector.execute(null, "catalog", srcInputs(), null).path("tables").path(0);
		check(firstScan.path("columns").size() == 1 && "card_number,civilite,nom".equals(firstScan.path("columns").path(0).path("name").asText()), "forced ';' gives one merged column: " + firstScan.path("columns"));
		final ConnectorConfig fixedConfig = new ConnectorConfig();
		fixedConfig.setSampleData(dataUrl("text/csv", "card_number,civilite,nom\r\nF1,M,Dupont\r\n"));
		fixedConfig.setProperty("separator", ",");
		connector.configure(fixedConfig);
		final JsonNode secondScan = connector.execute(null, "catalog", srcInputs(), null).path("tables").path(0);
		check(secondScan.path("columns").size() == 3 && secondScan.path("columnsComplete").asBoolean(), "the fixed rescan returns the 3 real columns, complete");
		for (final JsonNode column : secondScan.path("columns"))
		{
			check(!"card_number,civilite,nom".equals(column.path("name").asText()) && secondScan.path("scannedAt").asText().equals(column.path("scannedAt").asText()),
				"each returned column is stamped with the scan's own time and the merged column is not returned: " + column);
		}
		check(detectCatalog.path("tables").path(0).path("columns").size() == 3, "catalog must infer 3 columns from a comma csv, got "
			+ detectCatalog.path("tables").path(0).path("columns").size());
		// describe: "type" is the type, "sample" the value (they were swapped).
		final java.util.List<FieldInfo> described = connector.describeFields(null, "read", inputs("sample.csv"));
		FieldInfo civilite = null;
		for (final FieldInfo f : described) { if ("rows[0].civilite".equals(f.getPath())) { civilite = f; } }
		check(civilite != null && "string".equals(civilite.getType()) && "M".equals(civilite.getSample()), "describe field type/sample swapped");
		connector.configure(csvConfig);

		// 2026-10-17: a JSON envelope with a non-standard key ({"customers":[...]}) is the rows, in read AND in the catalog.
		final ConnectorConfig envelopeConfig = new ConnectorConfig();
		envelopeConfig.setSampleData(dataUrl("application/json", "{\"customers\":[{\"customerId\":\"C1\",\"city\":\"Paris\"},{\"customerId\":\"C2\",\"city\":\"Lyon\"}]}"));
		connector.configure(envelopeConfig);
		final JsonNode envelopeRead = connector.execute(null, "read", inputs("sample.json"), null);
		check(envelopeRead.path("count").asInt() == 2, "customers envelope must give 2 rows");
		final JsonNode envelopeCatalog = connector.execute(null, "catalog", srcInputs(), null);
		final JsonNode envelopeColumns = envelopeCatalog.path("tables").path(0).path("columns");
		check(envelopeColumns.size() == 2 && "customerId".equals(envelopeColumns.path(0).path("name").asText()),
			"catalog columns must be customerId, city (not customers[0].customerId): " + envelopeColumns);

		// JSON sample.
		final ConnectorConfig jsonConfig = new ConnectorConfig();
		jsonConfig.setSampleData(dataUrl("application/json", "[{\"id\":1,\"name\":\"Alpha\"},{\"id\":2,\"name\":\"Beta\"}]"));
		connector.configure(jsonConfig);
		final JsonNode jsonRead = connector.execute(null, "read", inputs("sample.json"), null);
		check(jsonRead != null && jsonRead.path("count").asInt() == 2, "json sample must yield 2 rows");
		check("Beta".equals(jsonRead.path("rows").path(1).path("name").asText()), "json row value must round-trip");
		final JsonNode jsonListing = connector.execute(null, "list", inputs("sample.json"), null);
		check("sample.json".equals(jsonListing.path("results").path(0).path("path").asText()), "json listing must expose sample.json");

		// Write is always refused, whatever the configuration.
		try
		{
			connector.execute(null, "write", inputs("sample.json"), com.on.ebx.connect.core.Json.object().put("id", 1));
			throw new AssertionError("write must be refused");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("read-only"), "write refusal must explain the connector is read-only");
		}

		check(!connector.getOperations().toString().contains("WRITE"), "no write operation must be advertised");
		check("CLOUD_STORAGE".equals(connector.getDescriptor().getCategory()), "category");
		check(connector.getDescriptor().getCapabilities().contains(com.on.ebx.connect.spi.ConnectorCapability.DISCOVER_OBJECTS),
			"descriptor must advertise object discovery (inherited preview/discovery)");

		// sample-file-name lot (2026-09-23): a stored sample can carry the uploaded file's ORIGINAL name
		// (RFC 2397 "name" parameter, see SampleDataUrl) instead of the generic "sample.<ext>" default.

		// Named JSON sample: list()/read()/the catalog operation all name the entry after the file,
		// extension stripped.
		final ConnectorConfig namedJsonConfig = new ConnectorConfig();
		namedJsonConfig.setSampleData(namedDataUrl("application/json", "Personnes_SAP_Sample.json", "[{\"id\":1,\"name\":\"Alpha\"}]"));
		connector.configure(namedJsonConfig);
		final JsonNode namedListing = connector.execute(null, "list", inputs("ignored"), null);
		check("Personnes_SAP_Sample".equals(namedListing.path("results").path(0).path("path").asText()),
			"a named sample must be listed under its own name, extension stripped");
		final JsonNode namedRead = connector.execute(null, "read", inputs("Personnes_SAP_Sample"), null);
		check(namedRead != null && namedRead.path("count").asInt() == 1, "a named sample must still read correctly (the path input is otherwise ignored)");
		final Map<String, String> catalogInputs = new LinkedHashMap<String, String>();
		catalogInputs.put("source", "SRC");
		final JsonNode namedCatalog = connector.execute(null, "catalog", catalogInputs, null);
		final JsonNode namedTable = namedCatalog.path("tables").path(0);
		check("Personnes_SAP_Sample".equals(namedTable.path("name").asText()), "the catalog asset must be named after the file, extension stripped");
		check("Personnes_SAP_Sample".equals(namedTable.path("qualifiedName").asText()), "qualifiedName must match the stripped name too");
		check("SRC:Personnes_SAP_Sample".equals(namedTable.path("assetKey").asText()), "assetKey must be source:<stripped name>");
		check(connector.testConnection(null).getMessage().contains("Personnes_SAP_Sample"), "the connection test must report the file's own name");

		// Absent name parameter (today's behaviour): still the generic sample.<ext> default, unchanged.
		final ConnectorConfig unnamedJsonConfig = new ConnectorConfig();
		unnamedJsonConfig.setSampleData(dataUrl("application/json", "[{\"id\":1}]"));
		connector.configure(unnamedJsonConfig);
		final JsonNode unnamedListing = connector.execute(null, "list", inputs("ignored"), null);
		check("sample.json".equals(unnamedListing.path("results").path(0).path("path").asText()), "no name parameter must keep today's generic sample.json default");

		// The name's own extension never decides the format: only the MIME does. A csv sample stored
		// under a name whose extension says "json" (deliberately wrong) must still parse as csv, even
		// when a caller's "path" input echoes that same wrong extension.
		final ConnectorConfig mismatchedExtensionConfig = new ConnectorConfig();
		mismatchedExtensionConfig.setSampleData(namedDataUrl("text/csv", "Weird.json", "id;name\r\n1;Alpha\r\n"));
		connector.configure(mismatchedExtensionConfig);
		final JsonNode mismatchedRead = connector.execute(null, "read", inputs("Weird.json"), null);
		check(mismatchedRead != null && mismatchedRead.path("count").asInt() == 1
			&& "Alpha".equals(mismatchedRead.path("rows").path(0).path("name").asText()),
			"the MIME must decide the format even when the file's own extension disagrees");

		// A tab-separated sample gets a default tab separator from its MIME whether or not its own name
		// ends in ".tsv".
		final ConnectorConfig namedTsvConfig = new ConnectorConfig();
		namedTsvConfig.setSampleData(namedDataUrl("text/tab-separated-values", "Export.data", "id\tname\r\n1\tAlpha\r\n"));
		connector.configure(namedTsvConfig);
		final JsonNode namedTsvRead = connector.execute(null, "read", inputs("Export"), null);
		check(namedTsvRead != null && namedTsvRead.path("count").asInt() == 1
			&& "Alpha".equals(namedTsvRead.path("rows").path(0).path("name").asText()),
			"a tab-separated sample must parse with a tab separator even when its own name carries no .tsv extension");

		// A name is sanitized: a path prefix is stripped to its base name, and spaces/accents/a literal
		// '%' decode correctly (percent-encoded exactly as the upload widget's encodeURIComponent would).
		final ConnectorConfig sanitizedConfig = new ConnectorConfig();
		sanitizedConfig.setSampleData("data:text/csv;name=..%2Fsub%2FDonn%C3%A9es%20client%20%25.csv;base64,"
			+ Base64.getEncoder().encodeToString("id;name\r\n1;Alpha\r\n".getBytes(StandardCharsets.UTF_8)));
		connector.configure(sanitizedConfig);
		final JsonNode sanitizedListing = connector.execute(null, "list", inputs("ignored"), null);
		check("Données client %".equals(sanitizedListing.path("results").path(0).path("path").asText()),
			"a path-prefixed, accented, space- and percent-carrying name must be sanitized down to its base name, extension stripped");

		// sample operation (2026-10-19): the contract of JdbcConnector.sample, for the text columns of the single table.
		boolean inDescriptor = false;
		for (final com.on.ebx.connect.spi.ConnectorOperationDescriptor d : connector.getDescriptor().getOperations()) { inDescriptor |= "sample".equals(d.getName()); }
		boolean inOperations = false;
		for (final com.on.ebx.connect.spi.Operation o : connector.getOperations()) { inOperations |= "sample".equals(o.getName()); }
		check(inDescriptor && inOperations, "sample must be declared in the operations and in the descriptor");
		final StringBuilder longValue = new StringBuilder();
		for (int i = 0; i < 250; i++) { longValue.append('x'); }
		final ConnectorConfig sampleCsv = new ConnectorConfig();
		sampleCsv.setSampleData(namedDataUrl("text/csv", "Persons.csv", "id,email,name,active,note\r\n1,a@x.fr,Alice,true,\r\n2,b@x.fr,Bob,false,\"line1\r\nline2\"\r\n"
			+ "3,a@x.fr,Alice,true,\r\n4,,Carl," + "true," + longValue + "\r\n"));
		connector.configure(sampleCsv);
		final JsonNode csvSample = connector.execute(null, "sample", srcInputs(), null);
		final JsonNode csvAsset = connector.execute(null, "catalog", srcInputs(), null).path("tables").path(0);
		check("S".equals(csvSample.path("source").asText()) && csvSample.path("sampledAt").asText().length() >= 19, "sample header: " + csvSample);
		// a CSV carries only strings: every column is text for the catalog too, so every column is sampled, in the catalog's order
		check(csvSample.path("columns").size() == 5 && csvSample.path("count").asInt() == 5, "5 text columns: " + csvSample.path("columns"));
		for (int i = 0; i < csvAsset.path("columns").size(); i++)
		{
			final JsonNode catalogColumn = csvAsset.path("columns").path(i);
			final JsonNode sampled = csvSample.path("columns").path(i);
			check(catalogColumn.path("columnKey").asText().equals(sampled.path("columnKey").asText()), "columnKey must be the catalog's: " + sampled);
			check(sampled.path("columnKey").asText().equals(csvAsset.path("assetKey").asText() + "." + sampled.path("name").asText()), "columnKey = assetKey.name");
			check(csvAsset.path("assetKey").asText().equals(sampled.path("assetKey").asText()) && csvAsset.path("qualifiedName").asText().equals(sampled.path("qualifiedName").asText()),
				"assetKey and qualifiedName must be the catalog's");
			check("STRING".equals(sampled.path("dataType").asText()) && "S".equals(sampled.path("source").asText()) && sampled.path("sampleSize").asInt() == 4, "column fields: " + sampled);
		}
		final JsonNode emailColumn = csvSample.path("columns").path(1);
		check("email".equals(emailColumn.path("name").asText()) && emailColumn.path("values").size() == 2
			&& "a@x.fr".equals(emailColumn.path("values").path(0).asText()) && "b@x.fr".equals(emailColumn.path("values").path(1).asText())
			&& "a@x.fr\nb@x.fr".equals(emailColumn.path("valuesText").asText()), "distinct values in order, blanks dropped: " + emailColumn);
		final JsonNode noteColumn = csvSample.path("columns").path(4);
		check(noteColumn.path("values").size() == 2 && "line1\r\nline2".equals(noteColumn.path("values").path(0).asText())
			&& noteColumn.path("values").path(1).asText().length() == 200 && noteColumn.path("valuesText").asText().startsWith("line1  line2\n"),
			"values cut at 200, line breaks replaced by spaces in valuesText only: " + noteColumn);
		check(csvAsset.path("assetKey").asText().equals("S:Persons"), "named sample asset key");
		// limit honoured (2 rows read: only a@x.fr and b@x.fr, sampleSize 2), garbage limit falls back to 50, 0 to 1
		final Map<String, String> limited = srcInputs();
		limited.put("limit", "2");
		final JsonNode twoRows = connector.execute(null, "sample", limited, null);
		check(twoRows.path("columns").path(0).path("sampleSize").asInt() == 2 && twoRows.path("columns").path(2).path("values").size() == 2, "limit 2: " + twoRows);
		limited.put("limit", "1");
		final JsonNode oneRow = connector.execute(null, "sample", limited, null);
		check(oneRow.path("columns").path(2).path("values").size() == 1 && oneRow.path("columns").path(2).path("values").path(0).asText().equals("Alice"), "limit 1");
		limited.put("limit", "0");
		check(connector.execute(null, "sample", limited, null).path("columns").path(0).path("sampleSize").asInt() == 1, "limit 0 is bounded to 1");
		limited.put("limit", "abc");
		check(connector.execute(null, "sample", limited, null).path("columns").path(0).path("sampleSize").asInt() == 4, "an invalid limit falls back to 50");
		// tables filtering: * / empty / own name / a list holding it select the table; anything else gives no column
		final Map<String, String> tables = srcInputs();
		for (final String ok : new String[] { "*", "", "Persons", "Other, Persons" })
		{
			tables.put("tables", ok);
			check(connector.execute(null, "sample", tables, null) != null, "tables '" + ok + "' must select the table");
		}
		for (final String other : new String[] { "Other", "persons2", "Persons.csv" })
		{
			tables.put("tables", other);
			check(connector.execute(null, "sample", tables, null) == null, "tables '" + other + "' must give no column");
		}
		try
		{
			connector.execute(null, "sample", new LinkedHashMap<String, String>(), null);
			throw new AssertionError("source is required");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("source"), "source required");
		}

		// JSON: only the columns the catalog types as text (a number or a boolean in the first row is not text).
		final ConnectorConfig sampleJson = new ConnectorConfig();
		sampleJson.setSampleData(dataUrl("application/json", "{\"customers\":[{\"id\":1,\"email\":\"a@x.fr\",\"active\":true,\"addr\":{\"city\":\"Paris\"},\"phone\":null},"
			+ "{\"id\":2,\"email\":\"b@x.fr\",\"active\":false,\"addr\":{\"city\":\"Lyon\"},\"phone\":\"0102030405\"},"
			+ "{\"id\":3,\"email\":\"a@x.fr\",\"active\":true,\"addr\":{\"city\":\"Paris\"},\"phone\":\"  \"}]}"));
		connector.configure(sampleJson);
		final JsonNode jsonSample = connector.execute(null, "sample", srcInputs(), null);
		final JsonNode jsonAsset = connector.execute(null, "catalog", srcInputs(), null).path("tables").path(0);
		final java.util.List<String> sampledKeys = new java.util.ArrayList<String>();
		for (final JsonNode c : jsonSample.path("columns")) { sampledKeys.add(c.path("columnKey").asText()); }
		final java.util.List<String> catalogTextKeys = new java.util.ArrayList<String>();
		for (final JsonNode c : jsonAsset.path("columns")) { if ("STRING".equals(c.path("dataType").asText())) { catalogTextKeys.add(c.path("columnKey").asText()); } }
		check(sampledKeys.equals(catalogTextKeys) && sampledKeys.size() == 3, "sampled columns = the catalog's STRING columns: " + sampledKeys + " vs " + catalogTextKeys);
		check("S:sample.json.email".equals(sampledKeys.get(0)) && "S:sample.json.addr.city".equals(sampledKeys.get(1)) && "S:sample.json.phone".equals(sampledKeys.get(2)), "keys " + sampledKeys);
		check(jsonSample.path("columns").path(1).path("values").size() == 2 && "Lyon".equals(jsonSample.path("columns").path(1).path("values").path(1).asText()), "nested values");
		check(jsonSample.path("columns").path(2).path("values").size() == 1 && "0102030405".equals(jsonSample.path("columns").path(2).path("valuesText").asText()), "null and blank values dropped");
		check(jsonSample.path("columns").path(0).path("sampleSize").asInt() == 3, "rows read");

		// No sample uploaded, or no text column: nothing (like the JDBC operation).
		connector.configure(new ConnectorConfig());
		check(connector.execute(null, "sample", srcInputs(), null) == null, "sample with no upload must answer null");
		final ConnectorConfig numbersOnly = new ConnectorConfig();
		numbersOnly.setSampleData(dataUrl("application/json", "[{\"a\":1,\"b\":true}]"));
		connector.configure(numbersOnly);
		check(connector.execute(null, "sample", srcInputs(), null) == null, "no text column gives null");

		System.out.println("PASS: sample-file csv/json read, describeFields, write refusal, listing, connection-test reporting, "
			+ "original-file-name display/format/asset-naming, and the sample operation");
	}

	private static String namedDataUrl(final String pMime, final String pName, final String pText)
	{
		return "data:" + pMime + ";name=" + pName + ";base64," + Base64.getEncoder().encodeToString(pText.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, String> srcInputs()
	{
		final Map<String, String> m = new LinkedHashMap<String, String>();
		m.put("source", "S");
		return m;
	}

	private static Map<String, String> inputs(final String pPath)
	{
		// "path" never selects between several objects (there is exactly one), but its extension still
		// drives format auto-detection, so a realistic value is what a prior list() call would return.
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("path", pPath);
		return inputs;
	}

	private static String dataUrl(final String pMime, final String pText)
	{
		return "data:" + pMime + ";base64," + Base64.getEncoder().encodeToString(pText.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean hasField(final java.util.List<FieldInfo> pFields, final String pPath)
	{
		for (final FieldInfo field : pFields)
		{
			if (pPath.equals(field.getPath())) { return true; }
		}
		return false;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) { throw new AssertionError(pMessage); }
	}
}
