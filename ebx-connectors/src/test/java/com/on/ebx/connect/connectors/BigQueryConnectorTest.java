package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.test.MockSaasHttpServer;

/**
 * Gartner lot C, mocked HTTP: BigQuery jobs.query, polling jobs.getQueryResults while the job runs,
 * pageToken paging up to max (truncated beyond), and the data-catalog operations through
 * INFORMATION_SCHEMA (region view with a location, per-dataset UNION ALL without).
 */
public final class BigQueryConnectorTest
{
	private BigQueryConnectorTest() { }

	private static final String SCHEMA = "{\"fields\":[{\"name\":\"id\",\"type\":\"INTEGER\"},{\"name\":\"name\",\"type\":\"STRING\"}]}";

	public static void main(final String[] pArgs) throws Exception
	{
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final String old = System.getProperty(Egress.PROPERTY);
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
			final BigQueryConnector eu = connector(server.baseUrl(), "EU");
			final String queries = "/bigquery/v2/projects/proj-1/queries";

			// running job: polled, then two pages joined
			server.enqueue(queries, new MockSaasHttpServer.Response(200, "{\"jobComplete\":false,\"jobReference\":{\"projectId\":\"proj-1\",\"jobId\":\"job_1\",\"location\":\"EU\"}}"));
			server.enqueue(queries + "/job_1", new MockSaasHttpServer.Response(200, "{\"jobComplete\":true,\"jobReference\":{\"jobId\":\"job_1\",\"location\":\"EU\"},\"schema\":" + SCHEMA
				+ ",\"totalRows\":\"3\",\"rows\":[" + row(1, "Acme") + "," + row(2, "Globex") + "],\"pageToken\":\"p2\"}"));
			server.enqueue(queries + "/job_1", new MockSaasHttpServer.Response(200, "{\"jobComplete\":true,\"jobReference\":{\"jobId\":\"job_1\"},\"rows\":[" + row(3, "Initech") + "]}"));
			final JsonNode all = eu.execute(null, "query", inputs("sql", "SELECT id, name FROM sales.customer WHERE region = @region", "region", "FR"), null);
			check(all.path("count").asInt() == 3 && all.path("totalRows").asLong() == 3 && !all.has("truncated"), "polled and paged: " + all);
			check(all.path("rows").get(2).path("id").asLong() == 3 && "Initech".equals(all.path("rows").get(2).path("name").asText()), "typed rows in order");
			final List<MockSaasHttpServer.Request> r = server.requests();
			final JsonNode posted = Json.parse(r.get(0).body);
			check("Bearer tok".equals(r.get(0).header("Authorization")) && "NAMED".equals(posted.path("parameterMode").asText())
				&& "FR".equals(posted.path("queryParameters").get(0).path("parameterValue").path("value").asText()) && "EU".equals(posted.path("location").asText()), "named parameter bound: " + posted);
			check("GET".equals(r.get(1).method) && r.get(1).query.contains("location=EU") && !r.get(1).query.contains("pageToken"), "status poll: " + r.get(1).query);
			check(r.get(2).query.contains("pageToken=p2"), "second page by token: " + r.get(2).query);

			// max reached with a token left: truncated, no further call
			final int before = server.requests().size();
			server.enqueue(queries, new MockSaasHttpServer.Response(200, "{\"jobComplete\":true,\"jobReference\":{\"jobId\":\"job_2\"},\"schema\":" + SCHEMA
				+ ",\"rows\":[" + row(1, "a") + "," + row(2, "b") + "],\"pageToken\":\"more\"}"));
			final JsonNode two = eu.execute(null, "query", inputs("sql", "SELECT 1", "max", "2"), null);
			check(two.path("count").asInt() == 2 && two.path("truncated").asBoolean() && server.requests().size() == before + 1, "truncated at max");
			check(Json.parse(server.requests().get(before).body).path("maxResults").asInt() == 2, "first page sized to max");

			// audit C2-05 (1.4.2): a feed's ${...} in the sql input is a bound parameter, never text in the statement
			check(eu.bindsWatermark("query", "sql") && !eu.bindsWatermark("query", "table") && !eu.bindsWatermark("get", "sql"), "only the sql input of query takes bound parameters");
			final Map<String, String> bound = new LinkedHashMap<String, String>();
			final String template = "SELECT id FROM sales.customer WHERE name = '${name}' AND zip = '${zip}' AND n > ${n} AND note = 'a :__b9 b' AND updated > ${watermark}";
			final String engineSql = com.on.ebx.connect.core.ValueTemplates.sql(template.replace("${watermark}", ":watermark"), new com.on.ebx.connect.core.ValueTemplates.Values()
			{
				@Override
				public String of(final String pExpression)
				{
					return "name".equals(pExpression) ? "O'Brien'; DROP TABLE x --" : "zip".equals(pExpression) ? "0012" : "7";
				}
			}, bound);
			check(!engineSql.contains("DROP") && engineSql.contains(":__b0") && engineSql.contains(":__b2"), "the engine bound the values: " + engineSql);
			final String bigQuerySql = BigQueryConnector.namedParameters(engineSql);
			check(bigQuerySql.contains("name = @__b0") && bigQuerySql.contains("n > @__b2") && bigQuerySql.contains("updated > @watermark") && bigQuerySql.contains("'a :__b9 b'"),
				"engine tokens rewritten outside quoted text: " + bigQuerySql);
			final Map<String, String> feedInputs = new LinkedHashMap<String, String>(bound);
			feedInputs.put("sql", engineSql);
			feedInputs.put("watermark", "2026-11-12T10:00:00Z");
			feedInputs.put("watermarkType", "TIMESTAMP");
			final int mark = server.requests().size();
			server.enqueue(queries, new MockSaasHttpServer.Response(200, "{\"jobComplete\":true,\"jobReference\":{\"jobId\":\"job_3\"},\"schema\":" + SCHEMA + ",\"rows\":[" + row(1, "a") + "]}"));
			eu.execute(null, "query", feedInputs, null);
			final JsonNode feedBody = Json.parse(server.requests().get(mark).body);
			check(feedBody.path("query").asText().contains("name = @__b0") && !feedBody.path("query").asText().contains("DROP") && !feedBody.path("query").asText().contains("Brien"),
				"the statement sent holds no value: " + feedBody.path("query").asText());
			final JsonNode sent = feedBody.path("queryParameters");
			check(sent.size() == 4 && "O'Brien'; DROP TABLE x --".equals(sent.get(0).path("parameterValue").path("value").asText()) && "STRING".equals(sent.get(0).path("parameterType").path("type").asText()),
				"the value travels as a parameter: " + sent);
			check("0012".equals(sent.get(1).path("parameterValue").path("value").asText()) && "STRING".equals(sent.get(1).path("parameterType").path("type").asText()), "a code stays text: " + sent);
			check("INT64".equals(sent.get(2).path("parameterType").path("type").asText()) && "TIMESTAMP".equals(sent.get(3).path("parameterType").path("type").asText())
				&& "@watermark".equals("@" + sent.get(3).path("name").asText()), "numbers and the watermark keep their type: " + sent);

			// catalog with a location: the region views, filtered on the dataset
			server.enqueue(queries, new MockSaasHttpServer.Response(200, result("TABLE_SCHEMA,TABLE_NAME,TABLE_TYPE,TABLE_COMMENT,ROW_COUNT",
				"[\"sales\",\"customer\",\"BASE TABLE\",null,null]", "[\"sales\",\"v_customer\",\"VIEW\",null,null]")));
			server.enqueue(queries, new MockSaasHttpServer.Response(200, result("TABLE_SCHEMA,TABLE_NAME,COLUMN_NAME,ORDINAL_POSITION,DATA_TYPE,IS_NULLABLE,COLUMN_COMMENT",
				"[\"sales\",\"customer\",\"id\",\"1\",\"INT64\",\"NO\",null]", "[\"sales\",\"customer\",\"name\",\"2\",\"STRING\",\"YES\",\"Legal name\"]",
				"[\"sales\",\"v_customer\",\"id\",\"1\",\"INT64\",\"YES\",null]")));
			final int catalogStart = server.requests().size();
			final JsonNode catalog = eu.execute(null, "catalog", inputs("source", "bq", "schemas", "sales"), null);
			final String tablesSql = Json.parse(server.requests().get(catalogStart).body).path("query").asText();
			final String columnsSql = Json.parse(server.requests().get(catalogStart + 1).body).path("query").asText();
			check(tablesSql.contains("`proj-1`.`region-eu`.INFORMATION_SCHEMA.TABLES") && tablesSql.contains("IN ('sales')"), tablesSql);
			check(columnsSql.contains("INFORMATION_SCHEMA.COLUMNS") && columnsSql.contains("COLUMN_FIELD_PATHS"), columnsSql);
			check(catalog.path("count").asInt() == 2 && "bq:sales.customer".equals(catalog.path("tables").get(0).path("assetKey").asText()), "catalog tables: " + catalog);
			final JsonNode customer = catalog.path("tables").get(0);
			check("VIEW".equals(catalog.path("tables").get(1).path("kind").asText()) && customer.path("columns").size() == 2
				&& "Legal name".equals(customer.path("columns").get(1).path("remarks").asText()) && !customer.path("columns").get(0).path("nullable").asBoolean(), "columns: " + customer);
			check(customer.path("readRecipe").asText().contains("`sales`.`customer`"), "read recipe through query: " + customer.path("readRecipe"));

			// no location: one view per dataset, joined
			final BigQueryConnector global = connector(server.baseUrl(), null);
			server.enqueue(queries, new MockSaasHttpServer.Response(200, result("TABLE_SCHEMA,TABLE_NAME,TABLE_TYPE,TABLE_COMMENT,ROW_COUNT", "[\"sales\",\"customer\",\"BASE TABLE\",null,null]")));
			server.enqueue(queries, new MockSaasHttpServer.Response(200, result("TABLE_SCHEMA,TABLE_NAME,COLUMN_NAME,ORDINAL_POSITION,DATA_TYPE,IS_NULLABLE,COLUMN_COMMENT",
				"[\"sales\",\"customer\",\"name\",\"1\",\"STRING\",\"YES\",null]")));
			server.enqueue(queries, new MockSaasHttpServer.Response(200, result("name", "[\"Acme\"]", "[\"Globex\"]", "[\"Acme\"]")));
			final int sampleStart = server.requests().size();
			final JsonNode sample = global.execute(null, "sample", inputs("source", "bq", "schemas", "sales,crm", "limit", "20"), null);
			final String perDataset = Json.parse(server.requests().get(sampleStart).body).path("query").asText();
			check(perDataset.contains("`proj-1`.`sales`.INFORMATION_SCHEMA.TABLES") && perDataset.contains(" UNION ALL ") && perDataset.contains("`proj-1`.`crm`.INFORMATION_SCHEMA.TABLES"), perDataset);
			final String sampleSql = Json.parse(server.requests().get(sampleStart + 2).body).path("query").asText();
			check(sampleSql.equals("SELECT `name` FROM `sales`.`customer` LIMIT 20"), sampleSql);
			check(sample.path("count").asInt() == 1 && sample.path("columns").get(0).path("values").size() == 2, "distinct sample values: " + sample);
			check(new BigQueryConnector.BigQueryDialect("p", "US").localName("p.sales.customer").equals("sales.customer")
				&& new BigQueryConnector.BigQueryDialect("p", "US").localName("other.sales.customer") == null, "local names");
			System.out.println("PASS: BigQuery named parameters, getQueryResults polling and pageToken paging, truncation at max, INFORMATION_SCHEMA catalog (region and per-dataset) and samples");
		}
		finally
		{
			server.close();
			if (old == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, old);
		}
	}

	private static BigQueryConnector connector(final String pBase, final String pLocation)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("bq");
		config.setType("bigquery");
		config.setBaseUrl(pBase);
		config.setAuthType(ConnectorConfig.AuthType.BEARER);
		config.setApiKey("tok");
		config.setProperty("project", "proj-1");
		config.setProperty("dataset", "sales");
		if (pLocation != null) config.setProperty("location", pLocation);
		final BigQueryConnector connector = new BigQueryConnector();
		connector.configure(config);
		return connector;
	}

	private static String row(final int pId, final String pName)
	{
		return "{\"f\":[{\"v\":\"" + pId + "\"},{\"v\":\"" + pName + "\"}]}";
	}

	/** A complete jobs.query answer whose STRING columns are named and whose rows are JSON arrays of values. */
	private static String result(final String pColumns, final String... pRows) throws java.io.IOException
	{
		final StringBuilder schema = new StringBuilder("{\"fields\":[");
		final String[] names = pColumns.split(",");
		for (int i = 0; i < names.length; i++) schema.append(i > 0 ? "," : "").append("{\"name\":\"").append(names[i]).append("\",\"type\":\"STRING\"}");
		final StringBuilder rows = new StringBuilder("[");
		for (int i = 0; i < pRows.length; i++)
		{
			final JsonNode values = Json.parse(pRows[i]);
			rows.append(i > 0 ? "," : "").append("{\"f\":[");
			for (int j = 0; j < values.size(); j++) rows.append(j > 0 ? "," : "").append("{\"v\":").append(Json.write(values.get(j))).append('}');
			rows.append("]}");
		}
		return "{\"jobComplete\":true,\"jobReference\":{\"jobId\":\"cat\"},\"schema\":" + schema + "]},\"rows\":" + rows + "]}";
	}

	private static Map<String, String> inputs(final String... pPairs)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (int i = 0; i + 1 < pPairs.length; i += 2) out.put(pPairs[i], pPairs[i + 1]);
		return out;
	}

	private static void check(final boolean pValue, final String pMessage)
	{
		if (!pValue) throw new AssertionError(pMessage);
	}
}
