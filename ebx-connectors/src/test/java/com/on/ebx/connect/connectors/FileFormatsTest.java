package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.DocumentCodecProvider;

/** Deterministic, account-free fixtures for shared storage document formats. */
public final class FileFormatsTest
{
	private FileFormatsTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		pathsStayInsideRoot();
		csvHandlesQuotesAndRejectsMalformedInput();
		jsonAndNdjsonAreBounded();
		xmlRoundTripsRowsWithoutExternalEntities();
		optionalBinaryFormatsDegradeExplicitly();
		byteApiPreservesBinaryBoundaries();
		serviceLoadedBinaryProviderIsUsed();
		providerAliasesAndBoundariesAreEnforced();
		sizeLimitsAreEnforced();
		truncationIsReportedForEveryFormat();
		jsonEnvelopeWithNonStandardKey();
		csvSeparatorIsDetected();
		System.out.println("PASS: bounded CSV, JSON, NDJSON and XML storage document formats");
	}

	private static void pathsStayInsideRoot()
	{
		final SftpConnector connector = new SftpConnector();
		final com.on.ebx.connect.spi.ConnectorConfig config = new com.on.ebx.connect.spi.ConnectorConfig();
		config.setProperty("root", "/data/tenantA");
		connector.configure(config);
		check("/data/tenantA/report.csv".equals(connector.fullPath("report.csv")), "relative path not rooted");
		check("/data/tenantA/report.csv".equals(connector.fullPath("/data/tenantA/report.csv")), "rooted path changed");
		for (final String path : new String[] {"/data/tenantAllUsers/x", "/data/tenantA/../../etc/passwd", "../x", "a/../x", "a\\..\\x"})
		{
			try { connector.fullPath(path); throw new AssertionError("Unsafe path accepted: " + path); }
			catch (final IllegalArgumentException expected) { }
		}
	}

	private static void csvHandlesQuotesAndRejectsMalformedInput() throws Exception
	{
		final ObjectNode parsed = FileFormats.parse("name,note\r\nAda,\"line 1\nline 2\"\r\n", "csv", ',', 10);
		check(parsed.get("count").asInt() == 1, "csv row missing");
		check("line 1\nline 2".equals(Json.at(parsed, "rows[0].note").asText()), "quoted newline lost");
		expectFailure("unterminated", new Action() { public void run() throws Exception { FileFormats.parse("name\n\"Ada", "csv", ',', 10); } });
	}

	private static void jsonAndNdjsonAreBounded() throws Exception
	{
		check(FileFormats.parse("[{\"id\":1},{\"id\":2}]", "json", ',', 1).get("rows").size() == 1, "json row bound ignored");
		final ObjectNode ndjson = FileFormats.parse("{\"id\":1}\n\n{\"id\":2}\n", "ndjson", ',', 10);
		check(ndjson.get("count").asInt() == 2, "ndjson records missing");
		expectFailure("line 1", new Action() { public void run() throws Exception { FileFormats.parse("42\n", "ndjson", ',', 10); } });
	}

	private static void xmlRoundTripsRowsWithoutExternalEntities() throws Exception
	{
		final JsonNode source = Json.parse("{\"name\":\"A&B <Co>\",\"tags\":[\"one\",\"two\"]}");
		final String xml = FileFormats.serialise(Arrays.asList(source), "xml", ',', true);
		final ObjectNode parsed = FileFormats.parse(xml, "xml", ',', 10);
		check(parsed.get("count").asInt() == 1, "xml row missing");
		check("A&B <Co>".equals(Json.at(parsed, "rows[0].name").asText()), "xml escaping failed");
		check(Json.at(parsed, "rows[0].tags.item").isArray(), "xml repeated values not normalized");
		expectFailure("Invalid xml", new Action() { public void run() throws Exception { FileFormats.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>", "xml", ',', 10); } });
	}

	private static void optionalBinaryFormatsDegradeExplicitly() throws Exception
	{
		expectFailure("Apache Parquet", new Action() { public void run() throws Exception { FileFormats.parse("", "parquet", ',', 10); } });
	}

	private static void byteApiPreservesBinaryBoundaries() throws Exception
	{
		final byte[] json = "[{\"id\":1}]".getBytes(StandardCharsets.UTF_8);
		check(FileFormats.parse(json, "json", ',', 10, json.length).get("count").asInt() == 1, "byte parse failed");
		final byte[] xml = FileFormats.serialiseBytes(Arrays.asList(Json.parse("{\"id\":1}")), "xml", ',', true, 4096);
		check(xml.length > 0 && FileFormats.parse(xml, "xml", ',', 10, 4096).get("count").asInt() == 1, "byte serialization failed");
		expectFailure("max.bytes", new Action() { public void run() throws Exception { FileFormats.parse(json, "json", ',', 10, json.length - 1); } });
	}

	private static void serviceLoadedBinaryProviderIsUsed() throws Exception
	{
		final java.nio.file.Path root = java.nio.file.Files.createTempDirectory("ebx-codec-test");
		final java.nio.file.Path services = root.resolve("META-INF/services");
		java.nio.file.Files.createDirectories(services);
		java.nio.file.Files.write(services.resolve(DocumentCodecProvider.class.getName()),
			Collections.singletonList(FakeExcelProvider.class.getName()), StandardCharsets.UTF_8);
		final ClassLoader previous = Thread.currentThread().getContextClassLoader();
		final java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] { root.toUri().toURL() }, previous);
		try
		{
			Thread.currentThread().setContextClassLoader(loader);
			final byte[] binary = new byte[] { 1, 2, 3 };
			check(FileFormats.parse(binary, "fixture-binary", ',', 10, 10).get("count").asInt() == 1, "service-loaded codec was not used");
			check(FileFormats.serialiseBytes(Arrays.asList(Json.object()), "fixture-binary", ',', true, 10)[0] == 7, "provider serialization was not used");
			check("application/test-binary".equals(FileFormats.contentType("fixture-binary")), "provider content type was not used");
		}
		finally
		{
			Thread.currentThread().setContextClassLoader(previous);
			loader.close();
		}
	}

	public static final class FakeExcelProvider implements DocumentCodecProvider
	{
		public java.util.Set<String> getFormats() { return Collections.singleton("fixture-binary"); }
		public ObjectNode parse(final byte[] pContent, final String pFormat, final int pMaxRows, final int pMaxBytes)
		{
			final ObjectNode out = Json.object(); out.putArray("rows").addObject().put("binaryBytes", pContent.length); out.put("count", 1); return out;
		}
		public byte[] serialise(final java.util.List<JsonNode> pRows, final String pFormat, final int pMaxBytes) { return new byte[] { 7 }; }
		public String getContentType(final String pFormat) { return "application/test-binary"; }
	}

	private static void providerAliasesAndBoundariesAreEnforced() throws Exception
	{
		final java.nio.file.Path root = java.nio.file.Files.createTempDirectory("ebx-codec-boundary-test");
		final java.nio.file.Path services = root.resolve("META-INF/services");
		java.nio.file.Files.createDirectories(services);
		java.nio.file.Files.write(services.resolve(DocumentCodecProvider.class.getName()),
			Collections.singletonList(OversizedXlsxProvider.class.getName()), StandardCharsets.UTF_8);
		final ClassLoader previous = Thread.currentThread().getContextClassLoader();
		final java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] { root.toUri().toURL() }, previous);
		try
		{
			Thread.currentThread().setContextClassLoader(loader);
			expectFailure("row limit", new Action() { public void run() throws Exception {
				FileFormats.parse(new byte[] { 1 }, "bounded-fixture", ',', 1, 10);
			} });
		}
		finally
		{
			Thread.currentThread().setContextClassLoader(previous);
			loader.close();
		}
	}

	public static final class OversizedXlsxProvider implements DocumentCodecProvider
	{
		public java.util.Set<String> getFormats() { return Collections.singleton("bounded-fixture"); }
		public ObjectNode parse(final byte[] pContent, final String pFormat, final int pMaxRows, final int pMaxBytes)
		{
			final ObjectNode out = Json.object();
			final com.fasterxml.jackson.databind.node.ArrayNode rows = out.putArray("rows");
			rows.addObject().put("id", 1); rows.addObject().put("id", 2); return out;
		}
		public byte[] serialise(final java.util.List<JsonNode> pRows, final String pFormat, final int pMaxBytes) { return new byte[] { 1 }; }
		public String getContentType(final String pFormat) { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
	}

	private static void sizeLimitsAreEnforced() throws Exception
	{
		FileFormats.checkSize("1234".getBytes(StandardCharsets.UTF_8), 4, "fixture");
		expectFailure("max.bytes", new Action() { public void run() throws Exception { FileFormats.checkSize(new byte[5], 4, "fixture"); } });
		expectFailure("positive", new Action() { public void run() throws Exception { FileFormats.boundedRows(0); } });
	}

	/**
	 * Before this (2026-09-28), a file feed that hit its {@code max} came back looking like a clean, complete
	 * load: {@code FileFormats.parse} already set {@code truncated} for json/ndjson/xml but not csv, and
	 * {@code ConnectorService.importDocument} read neither field. This checks the flags themselves - every
	 * format must report the real {@code available} count and {@code truncated = available > count}.
	 */
	/** 2026-10-17: {"customers":[...]} is the rows; single documents and ambiguous objects stay one row. */
	private static void jsonEnvelopeWithNonStandardKey() throws Exception
	{
		final ObjectNode envelope = FileFormats.parse("{\"customers\":[{\"customerId\":\"A\"},{\"customerId\":\"B\"},{\"customerId\":\"C\"}]}", "json", ';', 10);
		check(envelope.get("count").asInt() == 3 && "B".equals(Json.at(envelope, "rows[1].customerId").asText()), "customers envelope not unwrapped");
		check(envelope.get("available").asInt() == 3 && !envelope.get("truncated").asBoolean(), "envelope availability wrong");
		check(FileFormats.parse("{\"customers\":[{\"id\":1},{\"id\":2}]}", "json", ';', 1).get("truncated").asBoolean(), "envelope truncation not reported");
		final ObjectNode paged = FileFormats.parse("{\"total\":2,\"page\":1,\"customers\":[{\"id\":1},{\"id\":2}]}", "json", ';', 10);
		check(paged.get("count").asInt() == 2, "pagination metadata beside the array must not prevent unwrapping");
		// Genuine single documents keep their behaviour: one row.
		final ObjectNode single = FileFormats.parse("{\"id\":1,\"name\":\"Ada\"}", "json", ';', 10);
		check(single.get("count").asInt() == 1 && "Ada".equals(Json.at(single, "rows[0].name").asText()), "single document changed");
		final ObjectNode withOrders = FileFormats.parse("{\"id\":1,\"orders\":[{\"n\":1},{\"n\":2}]}", "json", ';', 10);
		check(withOrders.get("count").asInt() == 1, "a document with business fields next to an array must stay one row");
		final ObjectNode twoArrays = FileFormats.parse("{\"a\":[{\"x\":1}],\"b\":[{\"y\":2}]}", "json", ';', 10);
		check(twoArrays.get("count").asInt() == 1, "two arrays of objects must stay one document");
		final ObjectNode scalars = FileFormats.parse("{\"tags\":[\"a\",\"b\"]}", "json", ';', 10);
		check(scalars.get("count").asInt() == 1, "an array of scalars is not a row collection");
		// A known key still wins over the heuristic.
		final ObjectNode known = FileFormats.parse("{\"rows\":[{\"id\":1}],\"other\":[{\"id\":2}]}", "json", ';', 10);
		check(known.get("count").asInt() == 1 && Json.at(known, "rows[0].id").asInt() == 1, "known envelope key lost priority");
	}

	/** 2026-10-17: separator detection from the header and the next lines; a configured separator wins. */
	private static void csvSeparatorIsDetected() throws Exception
	{
		check(detect("a,b,c\r\n1,2,3\r\n4,5,6\r\n") == ',', "comma not detected");
		check(detect("a;b;c\n1;2;3\n") == ';', "semicolon not detected");
		check(detect("a\tb\tc\n1\t2\t3\n") == '\t', "tab not detected");
		check(detect("a|b|c\n1|2|3\n") == '|', "pipe not detected");
		check(detect("\uFEFFcard_number,civilite,nom\n1,M,Dupont\n") == ',', "BOM header not detected");
		check(detect("a;b\n\"1,5\";\"x,y\"\n2,5;z\n") == ';', "quoted commas / decimal commas must not win in a semicolon file");
		check(detect("a,b\n\"x;y;z\",\"1;2\"\n") == ',', "quoted semicolons must not win in a comma file");
		check(detect("a,b;c\n1,2;3\n") == ';', "tie between candidates must go to the semicolon default");
		check(detect("name\nAda\n") == ';', "single column falls back to the default");
		check(FileFormats.separator(null, "x.tsv", "name\nAda\n".getBytes(StandardCharsets.UTF_8)) == '\t', "tsv default kept when nothing is detected");
		check(FileFormats.separator(",", "x.csv", "a;b\n1;2\n".getBytes(StandardCharsets.UTF_8)) == ',', "configured separator must win");
		check(FileFormats.separator("tab", "x.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)) == '\t', "configured 'tab' must win");
		final char detected = detect("card_number,civilite,nom\r\n1,M,Dupont\r\n");
		final ObjectNode parsed = FileFormats.parse("card_number,civilite,nom\r\n1,M,Dupont\r\n", "csv", detected, 10);
		check("Dupont".equals(Json.at(parsed, "rows[0].nom").asText()), "comma csv not split into columns");
	}

	private static char detect(final String pText)
	{
		return FileFormats.separator(null, "x.csv", pText.getBytes(StandardCharsets.UTF_8));
	}

	private static void truncationIsReportedForEveryFormat() throws Exception
	{
		final ObjectNode csv = FileFormats.parse("id\r\n1\r\n2\r\n3\r\n", "csv", ',', 2);
		check(csv.path("count").asInt() == 2 && csv.path("available").asInt() == 3 && csv.path("truncated").asBoolean(false),
			"csv must report truncation and the real available count: " + csv);
		final ObjectNode csvComplete = FileFormats.parse("id\r\n1\r\n2\r\n", "csv", ',', 10);
		check(csvComplete.path("available").asInt() == 2 && !csvComplete.path("truncated").asBoolean(true),
			"csv under the limit must not be reported truncated: " + csvComplete);

		final ObjectNode json = FileFormats.parse("[{\"id\":1},{\"id\":2},{\"id\":3}]", "json", ',', 2);
		check(json.path("available").asInt() == 3 && json.path("truncated").asBoolean(false), "json must report truncation: " + json);
		final ObjectNode jsonComplete = FileFormats.parse("[{\"id\":1}]", "json", ',', 10);
		check(jsonComplete.path("available").asInt() == 1 && !jsonComplete.path("truncated").asBoolean(true),
			"json under the limit must not be reported truncated: " + jsonComplete);

		final ObjectNode ndjson = FileFormats.parse("{\"id\":1}\n{\"id\":2}\n{\"id\":3}\n", "ndjson", ',', 2);
		check(ndjson.path("available").asInt() == 3 && ndjson.path("truncated").asBoolean(false), "ndjson must report truncation: " + ndjson);

		final String xml = "<rows><row><id>1</id></row><row><id>2</id></row><row><id>3</id></row></rows>";
		final ObjectNode xmlParsed = FileFormats.parse(xml, "xml", ',', 2);
		check(xmlParsed.path("available").asInt() == 3 && xmlParsed.path("truncated").asBoolean(false), "xml must report truncation: " + xmlParsed);
	}

	private static void expectFailure(final String pFragment, final Action pAction) throws Exception
	{
		try { pAction.run(); throw new AssertionError("Expected failure containing " + pFragment); }
		catch (final ConnectorException expected) { check(expected.getMessage().contains(pFragment), "unexpected failure: " + expected.getMessage()); }
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	private interface Action { void run() throws Exception; }
}
