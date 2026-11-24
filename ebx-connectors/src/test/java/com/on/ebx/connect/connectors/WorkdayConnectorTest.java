package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.test.MockSaasHttpServer;

/**
 * Gartner lot C, mocked HTTP: Workday OAuth refresh-token grant, REST paging by limit/offset up to max
 * and total, one token refresh after a 401, RaaS with the integration user (Basic, never the bearer).
 */
public final class WorkdayConnectorTest
{
	private WorkdayConnectorTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final String old = System.getProperty(Egress.PROPERTY);
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
			final ConnectorConfig config = new ConnectorConfig();
			config.setId("workday");
			config.setType("workday");
			config.setBaseUrl(server.baseUrl());
			config.setAuthType(ConnectorConfig.AuthType.BASIC);
			config.setUsername("ISU_EBX");
			config.setPassword("isu-secret");
			config.setApiKey("client-secret");
			config.setProperty("tenant", "acme");
			config.setProperty("clientId", "client-1");
			config.setProperty("refreshToken", "refresh-1");
			final WorkdayConnector connector = new WorkdayConnector();
			connector.configure(config);

			final String token = "/ccx/oauth2/acme/token";
			final String workers = "/ccx/api/v1/acme/workers";
			server.enqueue(token, new MockSaasHttpServer.Response(200, "{\"access_token\":\"AT1\",\"token_type\":\"Bearer\"}"));
			server.enqueue(workers, new MockSaasHttpServer.Response(200, "{\"total\":150,\"data\":" + people(0, 100) + "}"));
			server.enqueue(workers, new MockSaasHttpServer.Response(200, "{\"total\":150,\"data\":" + people(100, 50) + "}"));
			final JsonNode all = connector.execute(null, "workers", inputs("search", "Smith", "max", "500"), null);
			check(all.path("count").asInt() == 150 && all.path("totalCount").asLong() == 150, "two pages up to total: " + all.path("count"));
			check("w149".equals(all.path("results").get(149).path("id").asText()), "pages in order");
			final MockSaasHttpServer.Request grant = server.requests().get(0);
			check("POST".equals(grant.method) && grant.body.contains("grant_type=refresh_token") && grant.body.contains("refresh_token=refresh-1"), "refresh-token grant");
			check(("Basic " + java.util.Base64.getEncoder().encodeToString("client-1:client-secret".getBytes("UTF-8"))).equals(grant.header("Authorization")), "client credentials on the token call");
			final MockSaasHttpServer.Request first = server.requests().get(1);
			final MockSaasHttpServer.Request second = server.requests().get(2);
			check("Bearer AT1".equals(first.header("Authorization")) && first.query.contains("search=Smith") && first.query.contains("limit=100") && first.query.contains("offset=0"), "first page: " + first.query);
			check(second.query.contains("limit=100") && second.query.contains("offset=100"), "second page from offset 100: " + second.query);

			// max stops before total; limit shrinks to what is left
			server.enqueue(workers, new MockSaasHttpServer.Response(200, "{\"total\":150,\"data\":" + people(0, 30) + "}"));
			final JsonNode thirty = connector.execute(null, "workers", inputs("max", "30"), null);
			check(thirty.path("count").asInt() == 30 && server.requests().get(server.requests().size() - 1).query.contains("limit=30"), "max respected");

			// a 401 refreshes the token once, then the call succeeds with the new one
			server.enqueue(workers + "/w1", new MockSaasHttpServer.Response(401, "{\"error\":\"invalid_token\"}"));
			server.enqueue(token, new MockSaasHttpServer.Response(200, "{\"access_token\":\"AT2\"}"));
			server.enqueue(workers + "/w1", new MockSaasHttpServer.Response(200, "{\"id\":\"w1\",\"descriptor\":\"Jane Smith\"}"));
			final JsonNode one = connector.execute(null, "worker", inputs("id", "w1"), null);
			check("Jane Smith".equals(one.path("descriptor").asText()) && "Bearer AT2".equals(server.requests().get(server.requests().size() - 1).header("Authorization")), "token refreshed after 401");
			check(server.calls(token) == 2, "one refresh, not one per call");

			// resource: a path, never a URL or a traversal
			try
			{
				connector.execute(null, "resource", inputs("path", "/../../oauth2"), null);
				throw new AssertionError("traversal accepted");
			}
			catch (final ConnectorException expected)
			{
				// refused
			}
			server.enqueue("/ccx/api/v1/acme/organizations", new MockSaasHttpServer.Response(200, "{\"total\":1,\"data\":[{\"id\":\"o1\"}]}"));
			check(connector.execute(null, "resource", inputs("path", "organizations", "query", "type=Supervisory"), null).path("count").asInt() == 1, "resource collection");
			check(server.requests().get(server.requests().size() - 1).query.contains("type=Supervisory"), "resource query kept");

			// RaaS: the integration user, report prompts as query parameters, JSON Report_Entry
			server.enqueue("/ccx/service/customreport2/acme/ISU_EBX/Suppliers", new MockSaasHttpServer.Response(200,
				"{\"Report_Entry\":[{\"Supplier_ID\":\"S-1\"},{\"Supplier_ID\":\"S-2\"}]}"));
			final JsonNode report = connector.execute(null, "raas", inputs("report", "ISU_EBX/Suppliers", "Effective_as_of_Date", "2026-10-30"), null);
			final MockSaasHttpServer.Request raas = server.requests().get(server.requests().size() - 1);
			check(report.path("count").asInt() == 2, "RaaS entries");
			check(raas.header("Authorization").startsWith("Basic ") && raas.query.contains("format=json") && raas.query.contains("Effective_as_of_Date=2026-10-30"), "RaaS with Basic and prompts: " + raas.query);
			System.out.println("PASS: Workday refresh-token grant, limit/offset paging to total and max, one refresh after 401, resource path guard, RaaS with the integration user");
		}
		finally
		{
			server.close();
			if (old == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, old);
		}
	}

	private static String people(final int pFrom, final int pCount)
	{
		final StringBuilder out = new StringBuilder("[");
		for (int i = pFrom; i < pFrom + pCount; i++) out.append(i > pFrom ? "," : "").append("{\"id\":\"w").append(i).append("\"}");
		return out.append(']').toString();
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
