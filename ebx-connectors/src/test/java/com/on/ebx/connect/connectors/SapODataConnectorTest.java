package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.test.MockSaasHttpServer;

/**
 * Gartner lot C, mocked HTTP: SAP OData v2 paging through {@code __next} / {@code $skiptoken}, v4
 * {@code @odata.nextLink}, the CSRF fetch with session cookies, and one fresh token after a 403
 * "CSRF token validation failed" - never a second retry.
 */
public final class SapODataConnectorTest
{
	private SapODataConnectorTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final String old = System.getProperty(Egress.PROPERTY);
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
			final String root = server.baseUrl() + "/sap/opu/odata/sap/API_BP";
			final SapODataConnector v2 = connector(root, "2");

			// v2 paging: absolute __next carrying $skiptoken, results unwrapped from d
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(200,
				"{\"d\":{\"results\":[{\"BusinessPartner\":\"1\",\"__metadata\":{\"uri\":\"x\"}}],\"__next\":\"" + root + "/A_BusinessPartner?$skiptoken=1\"}}"));
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(200,
				"{\"d\":{\"results\":[{\"BusinessPartner\":\"2\"}]}}"));
			final JsonNode page = v2.execute(null, "query", inputs("entitySet", "A_BusinessPartner"), null);
			check(page.path("count").asInt() == 2 && page.path("pages").asInt() == 2, "two v2 pages collected: " + page);
			check("2".equals(page.path("results").get(1).path("BusinessPartner").asText()) && !page.path("results").get(0).has("__metadata"), "v2 records normalised");
			check(server.requests().get(1).query.contains("skiptoken=1"), "continuation carries $skiptoken: " + server.requests().get(1).query);

			// a continuation to another origin is refused
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(200,
				"{\"d\":{\"results\":[{\"BusinessPartner\":\"1\"}],\"__next\":\"https://evil.example/A_BusinessPartner?$skiptoken=1\"}}"));
			try
			{
				v2.execute(null, "query", inputs("entitySet", "A_BusinessPartner"), null);
				throw new AssertionError("foreign continuation followed");
			}
			catch (final ConnectorException expected)
			{
				check(expected.getMessage().contains("origin"), expected.getMessage());
			}

			// create: fetch token + cookies, 403 CSRF, a fresh token and session, then success
			final int before = server.requests().size();
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(200, "{\"d\":{\"results\":[]}}",
				headers("x-csrf-token", "T1", "Set-Cookie", "SAP_SESSIONID_X=old; path=/; HttpOnly")));
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(403, "CSRF token validation failed",
				headers("x-csrf-token", "Required", "Content-Type", "text/plain")));
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(200, "{\"d\":{\"results\":[]}}",
				headers("x-csrf-token", "T2", "Set-Cookie", "SAP_SESSIONID_X=new; path=/")));
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(201,
				"{\"d\":{\"BusinessPartner\":\"9\",\"OrganizationBPName1\":\"Acme\"}}"));
			final JsonNode created = v2.execute(null, "create", inputs("entitySet", "A_BusinessPartner"),
				Json.object().put("OrganizationBPName1", "Acme"));
			check("9".equals(created.path("BusinessPartner").asText()), "created after one retry: " + created);
			final MockSaasHttpServer.Request fetch = server.requests().get(before);
			final MockSaasHttpServer.Request rejected = server.requests().get(before + 1);
			final MockSaasHttpServer.Request retried = server.requests().get(before + 3);
			check("GET".equals(fetch.method) && "Fetch".equals(fetch.header("X-CSRF-Token")), "token fetched first");
			check("POST".equals(rejected.method) && "T1".equals(rejected.header("X-CSRF-Token")) && "SAP_SESSIONID_X=old".equals(rejected.header("Cookie")), "first POST with token and session cookie");
			check("POST".equals(retried.method) && "T2".equals(retried.header("X-CSRF-Token")) && "SAP_SESSIONID_X=new".equals(retried.header("Cookie")), "retry with the fresh token and session");
			check(server.requests().size() == before + 4, "exactly one retry");

			// the token is reused while valid: a second write sends no fetch
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner('9')", new MockSaasHttpServer.Response(204, ""));
			v2.execute(null, "update", inputs("entitySet", "A_BusinessPartner", "key", "9"), Json.object().put("OrganizationBPName1", "Acme SA"));
			final MockSaasHttpServer.Request merge = server.requests().get(server.requests().size() - 1);
			check("MERGE".equals(merge.header("X-HTTP-Method")) && "T2".equals(merge.header("X-CSRF-Token")) && server.requests().size() == before + 5, "update reuses the token, v2 MERGE");

			// rejected twice: an error, no third attempt
			final int second = server.requests().size();
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(403, "CSRF token validation failed", headers("x-csrf-token", "Required")));
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(200, "{}", headers("x-csrf-token", "T3")));
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(403, "CSRF token validation failed", headers("x-csrf-token", "Required")));
			try
			{
				v2.execute(null, "create", inputs("entitySet", "A_BusinessPartner"), Json.object().put("OrganizationBPName1", "X"));
				throw new AssertionError("second CSRF rejection accepted");
			}
			catch (final ConnectorException expected)
			{
				check(expected.getMessage().contains("CSRF") && server.requests().size() == second + 3, "stops after one retry: " + expected.getMessage());
			}

			// a 403 that is not about CSRF is not retried
			final SapODataConnector plain = connector(root, "2");
			final int third = server.requests().size();
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(200, "{}", headers("x-csrf-token", "T4")));
			server.enqueue("/sap/opu/odata/sap/API_BP/A_BusinessPartner", new MockSaasHttpServer.Response(403, "{\"error\":{\"message\":{\"value\":\"No authorization\"}}}"));
			try
			{
				plain.execute(null, "create", inputs("entitySet", "A_BusinessPartner"), Json.object().put("OrganizationBPName1", "X"));
				throw new AssertionError("authorization failure accepted");
			}
			catch (final ConnectorException expected)
			{
				check(server.requests().size() == third + 2, "an authorization 403 is not retried");
			}

			// v4: @odata.nextLink, relative to the service root
			final String root4 = server.baseUrl() + "/sap/opu/odata4/sap/api_bp/srvd_a2x/sap/bp/0001";
			final SapODataConnector v4 = connector(root4, "4");
			server.enqueue("/sap/opu/odata4/sap/api_bp/srvd_a2x/sap/bp/0001/BusinessPartner", new MockSaasHttpServer.Response(200,
				"{\"@odata.context\":\"$metadata#BusinessPartner\",\"value\":[{\"id\":\"A\"},{\"id\":\"B\"}],\"@odata.nextLink\":\"BusinessPartner?$skiptoken=2\"}"));
			server.enqueue("/sap/opu/odata4/sap/api_bp/srvd_a2x/sap/bp/0001/BusinessPartner", new MockSaasHttpServer.Response(200,
				"{\"value\":[{\"id\":\"C\"}]}"));
			final JsonNode four = v4.execute(null, "query", inputs("entitySet", "BusinessPartner"), null);
			check(four.path("count").asInt() == 3 && "C".equals(four.path("results").get(2).path("id").asText()), "v4 pages collected: " + four);
			check(server.requests().get(server.requests().size() - 1).query.contains("skiptoken=2"), "v4 continuation followed");
			System.out.println("PASS: SAP OData v2 __next/$skiptoken and v4 nextLink paging, foreign origin refused, CSRF fetch with cookies, one retry with a fresh token, no retry on an authorization 403");
		}
		finally
		{
			server.close();
			if (old == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, old);
		}
	}

	private static SapODataConnector connector(final String pRoot, final String pVersion)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("sap");
		config.setType("sap-odata");
		config.setBaseUrl(pRoot);
		config.setAuthType(ConnectorConfig.AuthType.BASIC);
		config.setUsername("EBX");
		config.setPassword("secret");
		config.setProperty("odata.version", pVersion);
		final SapODataConnector connector = new SapODataConnector();
		connector.configure(config);
		return connector;
	}

	private static Map<String, String> inputs(final String... pPairs)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (int i = 0; i + 1 < pPairs.length; i += 2) out.put(pPairs[i], pPairs[i + 1]);
		return out;
	}

	private static Map<String, String> headers(final String... pPairs)
	{
		return inputs(pPairs);
	}

	private static void check(final boolean pValue, final String pMessage)
	{
		if (!pValue) throw new AssertionError(pMessage);
	}
}
