package com.on.ebx.connect.ebx.rest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Locale;
import java.util.TreeMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.OpenApiImporter;
import com.on.ebx.connect.test.MockSaasHttpServer;

/**
 * Lot 1.4.1 (audit X3-08): the module runs on the Jackson its war carries (2.18, which reads with constraints), and the bounds of what it
 * reads are a written choice. Proves, offline:
 * <ul>
 * <li>which Jackson the tests loaded (a line "JACKSON ..." in the log: the jar, its version, and that it is the pinned one of runtime-libs/);</li>
 * <li>that EVERY place that creates a Jackson reader carries the string bound: the known holders by reflection, a scan of the sources for any
 * other creation site, and the factory of the YAML importer by behaviour;</li>
 * <li>that a string longer than Jackson's 20,000,000 characters but within the body bound is accepted on REST and in an HTTP answer;</li>
 * <li>that a document refused by Jackson (string, number, name) or by the module (depth) gives a coded 400, never a StackOverflowError or a 500;</li>
 * <li>with {@code small} as argument and {@code -Debx.connectors.json.maxStringLength=1000}: the explicit property is carried by the readers.</li>
 * </ul>
 * Arguments: the module folder, then optionally {@code small}.
 */
public final class JsonReadLimitsTest
{
	private JsonReadLimitsTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final File moduleDir = new File(pArgs.length > 0 ? pArgs[0] : ".");
		final boolean small = pArgs.length > 1 && "small".equals(pArgs[1]);
		versionLoaded(moduleDir);
		JsonReadLimits.apply();
		final int expected = JsonReadLimits.configuredMaxString();
		if (small)
		{
			check(expected == 1000, "the explicit property is the setting: " + expected);
		}
		else
		{
			check(expected >= 32 * 1024 * 1024 && expected >= HttpJsonClient.defaultMaxResponseBytes(),
				"by default the string bound follows the byte bounds (REST body 32 MiB, HTTP ceiling 128 MiB): " + expected);
		}
		everyReaderCarriesTheBound(moduleDir, expected);
		statusSaysWhatIsCarried(expected);
		if (small)
		{
			smallBoundRefusesCleanly();
		}
		else
		{
			longStringIsAccepted();
			nastyDocumentsAreRefusedCleanly();
			yamlImporter();
			serviceAnswers();
		}
		System.out.println("PASS: lot 1.4.1 Jackson read bounds (" + (small ? "explicit property 1000" : "default, string bound " + expected) + ")");
	}

	// ------------------------------------------------------------------ the Jackson that was loaded

	private static void versionLoaded(final File pModuleDir) throws Exception
	{
		String pinned = null;
		for (final String line : Files.readAllLines(new File(pModuleDir, "runtime-libs/PINNED-WAR-JARS.txt").toPath(), StandardCharsets.UTF_8))
		{
			final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[0-9a-f]{64}  jackson-core-(.+)\\.jar$").matcher(line.trim());
			if (m.matches())
			{
				pinned = m.group(1);
			}
		}
		check(pinned != null, "PINNED-WAR-JARS.txt pins a jackson-core");
		final String core = com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString();
		final String databind = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString();
		final String coreJar = String.valueOf(JsonFactory.class.getProtectionDomain().getCodeSource().getLocation());
		final String databindJar = String.valueOf(ObjectMapper.class.getProtectionDomain().getCodeSource().getLocation());
		System.out.println("JACKSON loaded by the tests: jackson-core " + core + " from " + coreJar + " ; jackson-databind " + databind + " from " + databindJar);
		check(core.equals(pinned) && databind.equals(pinned), "the tests ran on Jackson " + core + " / " + databind + ", runtime-libs pins " + pinned);
		check(coreJar.contains("/runtime-libs/") && databindJar.contains("/runtime-libs/"), "the Jackson of the tests comes from runtime-libs/, not from a server lib: " + coreJar);
		check(!pinned.startsWith("2.14"), "no Jackson 2.14");
	}

	// ------------------------------------------------------------------ every creation site

	/** The sources that create a Jackson reader, with the number of creation sites each one has. A new site fails the test until it is listed here and covered below. */
	private static final String[][] SITES = {
		{ "com/on/ebx/agent/McpClient.java", "1" },
		{ "com/on/ebx/agent/ContractLlmChat.java", "1" },
		{ "com/on/ebx/agent/LlmClient.java", "1" },
		{ "com/on/ebx/connect/ebx/mcp/ConnectorsMcpServer.java", "1" },
		{ "com/on/ebx/connect/core/Json.java", "1" },
		{ "com/on/ebx/connect/core/OpenApiImporter.java", "2" }, // its ObjectMapper, and the YAMLFactory it reaches by name
		{ "com/on/ebx/connect/spi/ConnectorConfigurationValidator.java", "1" },
		{ "com/on/ebx/connect/ebx/rest/JsonReadLimits.java", "1" },
	};

	private static void everyReaderCarriesTheBound(final File pModuleDir, final int pExpected) throws Exception
	{
		// 1. the scan: any file that creates a mapper or a factory must be in SITES with the same count (BodyLimit takes its factory from JsonReadLimits)
		final File src = new File(pModuleDir, "src/main/java");
		final Map<String, Integer> found = new TreeMap<String, Integer>();
		scan(src, src, found);
		final Map<String, Integer> listed = new TreeMap<String, Integer>();
		for (final String[] site : SITES)
		{
			listed.put(site[0], Integer.valueOf(site[1]));
		}
		check(found.equals(listed), "the places that create a Jackson reader changed: found " + found + ", covered " + listed);
		// 2. the static holders, loaded the way the module loads them
		final String[][] holders = { { "com.on.ebx.agent.McpClient", "MAPPER" }, { "com.on.ebx.agent.ContractLlmChat", "MAPPER" },
			{ "com.on.ebx.agent.LlmClient", "MAPPER" }, { "com.on.ebx.connect.ebx.mcp.ConnectorsMcpServer", "MAPPER" },
			{ "com.on.ebx.connect.core.Json", "MAPPER" }, { "com.on.ebx.connect.ebx.rest.BodyLimit", "FACTORY" } };
		for (final String[] holder : holders)
		{
			final Class<?> c = Class.forName(holder[0]);
			final Field f = c.getDeclaredField(holder[1]);
			f.setAccessible(true);
			final Object value = f.get(null);
			final JsonFactory factory = value instanceof ObjectMapper ? ((ObjectMapper) value).getFactory() : (JsonFactory) value;
			check(factory.streamReadConstraints().getMaxStringLength() == pExpected,
				holder[0] + "." + holder[1] + " carries " + factory.streamReadConstraints().getMaxStringLength() + ", expected " + pExpected);
			check(factory.streamReadConstraints().getMaxNestingDepth() == StreamReadConstraints.DEFAULT_MAX_DEPTH
				&& factory.streamReadConstraints().getMaxNumberLength() == StreamReadConstraints.DEFAULT_MAX_NUM_LEN
				&& factory.streamReadConstraints().getMaxNameLength() == StreamReadConstraints.DEFAULT_MAX_NAME_LEN, holder[0] + " keeps Jackson's depth, number and name bounds");
		}
		// 3. a mapper created now (ConnectorConfigurationValidator, the YAML importer, the libraries of the war) gets the same default
		check(new ObjectMapper().getFactory().streamReadConstraints().getMaxStringLength() == pExpected, "a new ObjectMapper carries the bound");
		check(new JsonFactory().streamReadConstraints().getMaxStringLength() == pExpected, "a new JsonFactory carries the bound");
		check(JsonReadLimits.newFactory().streamReadConstraints().getMaxStringLength() == pExpected, "JsonReadLimits.newFactory carries the bound");
		System.out.println("READERS " + found.size() + " source files create Jackson readers, " + holders.length + " static holders and a new mapper/factory all carry maxStringLength " + pExpected);
	}

	private static void scan(final File pRoot, final File pDir, final Map<String, Integer> pFound) throws Exception
	{
		for (final File f : pDir.listFiles())
		{
			if (f.isDirectory())
			{
				scan(pRoot, f, pFound);
			}
			else if (f.getName().endsWith(".java"))
			{
				int n = 0;
				for (final String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8))
				{
					final String t = line.trim();
					if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*"))
					{
						continue;
					}
					n += count(t, "new ObjectMapper(") + count(t, "new JsonFactory(") + count(t, "JsonFactory.builder(") + count(t, "new YAMLFactory(")
						+ count(t, "new MappingJsonFactory(") + count(t, "JsonMapper.builder(") + count(t, "new JsonMapper(") + count(t, "new SmileFactory(")
						+ count(t, "YAMLFactory\"");
				}
				if (n > 0)
				{
					pFound.put(pRoot.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/'), Integer.valueOf(n));
				}
			}
		}
	}

	private static int count(final String pText, final String pNeedle)
	{
		int n = 0;
		for (int i = pText.indexOf(pNeedle); i >= 0; i = pText.indexOf(pNeedle, i + 1))
		{
			n++;
		}
		return n;
	}

	private static void statusSaysWhatIsCarried(final int pExpected)
	{
		final com.fasterxml.jackson.databind.node.ObjectNode out = Json.object();
		JsonReadLimits.status(out);
		final JsonNode j = out.get("jackson");
		check(j.get("maxStringLength").asInt() == pExpected && j.get("readersCarryTheSetting").asBoolean(), "status reports what the mapper carries: " + j);
		check(j.get("maxNestingDepth").asInt() == 1000 && j.get("maxNumberLength").asInt() == 1000 && j.get("maxNameLength").asInt() == 50000, "status: Jackson defaults kept: " + j);
		check(j.get("version").asText().startsWith("2.18."), "status: version: " + j);
	}

	// ------------------------------------------------------------------ behaviour

	private static void longStringIsAccepted() throws Exception
	{
		// 21 million characters: over Jackson 2.15's default of 20 million, within the REST body bound of 32 MiB (a 15 MB file in base64 is 20 million)
		final int chars = 21000000;
		check(chars > StreamReadConstraints.DEFAULT_MAX_STRING_LEN, "the case is over Jackson's default");
		final StringBuilder doc = new StringBuilder(chars + 32).append("{\"file\":\"");
		final char[] pad = new char[1 << 16];
		java.util.Arrays.fill(pad, 'A');
		for (int i = 0; i < chars; i += pad.length)
		{
			doc.append(pad, 0, Math.min(pad.length, chars - i));
		}
		doc.append("\"}");
		final String[] out = new String[1];
		final jakarta.ws.rs.core.Response refusal = ConnectorsResource.readBounded(String.valueOf(doc.length()), new ByteArrayInputStream(doc.toString().getBytes(StandardCharsets.UTF_8)), out, Locale.ENGLISH);
		check(refusal == null && out[0] != null, "the REST entry reads a 21 MB document: " + (refusal == null ? "" : refusal.getStatus()));
		final JsonNode parsed = Json.parse(out[0]);
		check(parsed.get("file").asText().length() == chars, "the module's reader parses a 21,000,000-character string");
		System.out.println("LONG a JSON string of " + chars + " characters passes the REST entry and the module's reader");
	}

	private static void nastyDocumentsAreRefusedCleanly() throws Exception
	{
		// a number of 1100 digits and a name of 60,000 characters in a small body: Jackson's own refusals, answered as a coded parse error (400), not a 500
		final StringBuilder digits = new StringBuilder("{\"n\":1");
		for (int i = 0; i < 1100; i++)
		{
			digits.append('0');
		}
		digits.append('}');
		final StringBuilder name = new StringBuilder("{\"");
		for (int i = 0; i < 60000; i++)
		{
			name.append('k');
		}
		name.append("\":1}");
		for (final String doc : new String[] { digits.toString(), name.toString() })
		{
			final McpEndpoint.Parsed parsed = McpEndpoint.parse(null, stream(doc), Locale.ENGLISH);
			check(parsed.refusal != null && parsed.refusal.getStatus() == 400 && String.valueOf(parsed.refusal.getEntity()).contains("-32700"),
				"MCP: a document refused by Jackson is a coded parse error: " + (parsed.refusal == null ? "accepted" : parsed.refusal.getStatus() + " " + shorten(String.valueOf(parsed.refusal.getEntity()))));
			boolean refused = false;
			try
			{
				Json.parse(doc);
			}
			catch (final JsonProcessingException expected)
			{
				refused = true;
			}
			check(refused, "Json.parse throws a JsonProcessingException (an IOException the callers handle) for it");
		}
		// depth: the module's own bound (64) answers 400 CN_JSON_TOO_DEEP long before Jackson's (1000), at any depth, on the REST entry, the MCP entry and the in-JVM bridge
		final String[] out = new String[1];
		for (final int depth : new int[] { 65, 1001, 100000 })
		{
			final jakarta.ws.rs.core.Response rest = ConnectorsResource.readBounded(null, stream(nested(depth)), out, Locale.ENGLISH);
			check(rest != null && rest.getStatus() == 400 && String.valueOf(rest.getEntity()).contains("CN_JSON_TOO_DEEP"), "REST: " + depth + " levels: 400 CN_JSON_TOO_DEEP");
			final McpEndpoint.Parsed mcp = McpEndpoint.parse(null, stream(nested(depth)), Locale.ENGLISH);
			check(mcp.refusal != null && mcp.refusal.getStatus() == 400 && String.valueOf(mcp.refusal.getEntity()).contains("CN_JSON_TOO_DEEP"), "MCP: " + depth + " levels: 400 CN_JSON_TOO_DEEP");
			final String bridge = new InJvmMcp().handle(session(), nested(depth));
			check(bridge.contains("CN_JSON_TOO_DEEP"), "in-JVM bridge: " + depth + " levels refused: " + shorten(bridge));
		}
		// the same depth in the answer of a service (no module bound there, Jackson's 1000 applies): a clean IOException, never a StackOverflowError
		for (final int depth : new int[] { 1001, 100000 })
		{
			boolean refused = false;
			try
			{
				Json.parse(nested(depth));
			}
			catch (final JsonProcessingException expected)
			{
				refused = true;
			}
			check(refused, "Json.parse: " + depth + " levels is a JsonProcessingException, not a StackOverflowError");
		}
		check(Json.parse(nested(900)) != null, "900 levels still parse (under Jackson's 1000)");
		System.out.println("NASTY number of 1100 digits, name of 60000 characters, depth 65 / 1001 / 100000: coded 400 on REST, MCP and the in-JVM bridge; clean IOException from Json.parse");
	}

	private static void yamlImporter() throws Exception
	{
		// the YAML factory of jackson-dataformat-yaml is found in the war (runtime-libs) and runs on the module's Jackson: no ClassCastException across two loaders
		final JsonNode spec = OpenApiImporter.parse("openapi: 3.0.0\ninfo:\n  title: T\n  version: '1'\npaths:\n  /a:\n    get:\n      operationId: getA\n      responses:\n        '200':\n          description: ok\n");
		check(spec != null && spec.get("paths").get("/a").get("get").get("operationId").asText().equals("getA"), "a YAML OpenAPI specification is read: " + spec);
		check(Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory").getProtectionDomain().getCodeSource().getLocation().toString().contains("/runtime-libs/jackson-dataformat-yaml-"),
			"the YAML factory is the one of runtime-libs");
		check(Class.forName("org.yaml.snakeyaml.Yaml").getProtectionDomain().getCodeSource().getLocation().toString().contains("/runtime-libs/snakeyaml-2."), "SnakeYAML 2.x of runtime-libs");
		System.out.println("YAML an OpenAPI specification in YAML is read through jackson-dataformat-yaml " + com.fasterxml.jackson.dataformat.yaml.PackageVersion.VERSION + " and SnakeYAML 2.x");
	}

	private static void serviceAnswers() throws Exception
	{
		// the answer of a service is read by the same reader: 21 million characters in a string are kept, 5000 levels of nesting are not parsed (body null, no error thrown)
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final String previousEgress = System.getProperty(Egress.PROPERTY);
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
			final HttpJsonClient client = new HttpJsonClient(5000, 60000, "test");
			final StringBuilder big = new StringBuilder("{\"d\":\"");
			final char[] pad = new char[1 << 16];
			java.util.Arrays.fill(pad, 'B');
			for (int i = 0; i < 21000000; i += pad.length)
			{
				big.append(pad, 0, Math.min(pad.length, 21000000 - i));
			}
			big.append("\"}");
			server.enqueue("/big", new MockSaasHttpServer.Response(200, big.toString()));
			final HttpJsonClient.Response big1 = client.send("GET", server.baseUrl() + "/big", Collections.<String, String>emptyMap(), null);
			check(big1.isSuccess() && big1.getBody() != null && big1.getBody().get("d").asText().length() == 21000000, "an answer holding a 21,000,000-character string is parsed");
			server.enqueue("/deep", new MockSaasHttpServer.Response(200, nested(5000)));
			final HttpJsonClient.Response deep = client.send("GET", server.baseUrl() + "/deep", Collections.<String, String>emptyMap(), null);
			check(deep.getStatus() == 200 && deep.getBody() == null && deep.getRawBody().length() == 10000, "an answer nested 5000 levels is not parsed into a tree (body null), raw text kept");
		}
		finally
		{
			server.close();
			if (previousEgress == null)
			{
				System.clearProperty(Egress.PROPERTY);
			}
			else
			{
				System.setProperty(Egress.PROPERTY, previousEgress);
			}
		}
		System.out.println("ANSWERS a service answer with a 21,000,000-character string is parsed; one nested 5000 levels is left unparsed (no error, no stack overflow)");
	}

	private static void smallBoundRefusesCleanly() throws Exception
	{
		final StringBuilder s = new StringBuilder("{\"a\":\"");
		for (int i = 0; i < 1500; i++)
		{
			s.append('x');
		}
		s.append("\"}");
		final McpEndpoint.Parsed parsed = McpEndpoint.parse(null, stream(s.toString()), Locale.ENGLISH);
		check(parsed.refusal != null && parsed.refusal.getStatus() == 400 && String.valueOf(parsed.refusal.getEntity()).contains("-32700"),
			"a string over the explicit bound is a coded parse error: " + (parsed.refusal == null ? "accepted" : shorten(String.valueOf(parsed.refusal.getEntity()))));
		s.setLength(0);
		s.append("{\"a\":\"");
		for (int i = 0; i < 900; i++)
		{
			s.append('x');
		}
		s.append("\"}");
		check(McpEndpoint.parse(null, stream(s.toString()), Locale.ENGLISH).refusal == null, "a string under the explicit bound is accepted");
		System.out.println("SMALL -Debx.connectors.json.maxStringLength=1000: 1500 characters refused as a coded parse error, 900 accepted");
	}

	// ------------------------------------------------------------------ helpers

	/** A session that answers nothing: the bounds are checked before the caller's right is. */
	private static com.orchestranetworks.service.Session session()
	{
		return (com.orchestranetworks.service.Session) java.lang.reflect.Proxy.newProxyInstance(JsonReadLimitsTest.class.getClassLoader(),
			new Class<?>[] { com.orchestranetworks.service.Session.class }, new java.lang.reflect.InvocationHandler()
			{
				@Override
				public Object invoke(final Object pProxy, final java.lang.reflect.Method pMethod, final Object[] pArguments)
				{
					return "isSystemUser".equals(pMethod.getName()) ? Boolean.FALSE : null;
				}
			});
	}

	private static String shorten(final String pText)
	{
		return pText.length() > 300 ? pText.substring(0, 300) : pText;
	}

	private static String nested(final int pDepth)
	{
		final StringBuilder b = new StringBuilder(pDepth * 2);
		for (int i = 0; i < pDepth; i++)
		{
			b.append('[');
		}
		for (int i = 0; i < pDepth; i++)
		{
			b.append(']');
		}
		return b.toString();
	}

	private static InputStream stream(final String pText)
	{
		return new ByteArrayInputStream(pText.getBytes(StandardCharsets.UTF_8));
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
