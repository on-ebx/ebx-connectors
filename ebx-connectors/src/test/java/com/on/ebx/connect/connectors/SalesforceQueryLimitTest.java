package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.test.MockSaasHttpServer;

/** Audit C-16: Salesforce {@code query} stops at {@code max} and reports a failed page instead of returning what it had as if it were all. */
public final class SalesforceQueryLimitTest
{
	private static final String QUERY = "/services/data/v60.0/query";

	public static void main(final String[] pArgs) throws Exception
	{
		check(SalesforceConnector.recordLimit("200") == 201 && SalesforceConnector.recordLimit(null) == 0 && SalesforceConnector.recordLimit("0") == 0
			&& SalesforceConnector.recordLimit("x") == 0, "limit is max + 1 so the engine sees a truncation; none without a max");
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final String previous = System.getProperty(Egress.PROPERTY);
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));

			// no max: every page, as before
			script(server, false);
			JsonNode result = connector(server).execute(null, "query", inputs(null), null);
			check(result.get("records").size() == 5 && result.get("totalSize").asInt() == 5, "no max reads every page: " + result);

			// max 3: stops once it holds 4 (max + 1), does not ask for the last page
			script(server, false);
			final int pageTwoBefore = server.calls(QUERY + "/loc2");
			result = connector(server).execute(null, "query", inputs("3"), null);
			check(result.get("records").size() == 4 && result.get("totalSize").asInt() == 5, "max 3 delivers 4 records, one more than max, and the real total: " + result);
			check(server.calls(QUERY + "/loc2") - pageTwoBefore == 1, "the page after the cap is not fetched");

			// a failing page: an error, not a silent short result
			script(server, true);
			try
			{
				connector(server).execute(null, "query", inputs(null), null);
				throw new AssertionError("a failed page ended the read quietly");
			}
			catch (final ConnectorException expected)
			{
				check(expected.getMessage().contains("page 2") && expected.getMessage().contains("HTTP 503") && expected.getMessage().contains("incomplete"),
					"failed page named: " + expected.getMessage());
			}
		}
		finally
		{
			server.close();
			if (previous == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, previous);
		}
		System.out.println("PASS: Salesforce query (max, failed page reported)");
	}

	private static void script(final MockSaasHttpServer pServer, final boolean pFailPage)
	{
		pServer.enqueue("/services/oauth2/token", new MockSaasHttpServer.Response(200, "{\"access_token\":\"t\",\"instance_url\":\"" + pServer.baseUrl() + "\"}"));
		pServer.enqueue(QUERY, new MockSaasHttpServer.Response(200, "{\"totalSize\":5,\"done\":false,\"nextRecordsUrl\":\"/services/data/v60.0/query/loc2\",\"records\":[{\"Id\":1},{\"Id\":2}]}"));
		// the next page: the connector retries a 503 on GET, so the failing case scripts enough of them
		if (pFailPage)
		{
			for (int i = 0; i < 4; i++) pServer.enqueue(QUERY + "/loc2", new MockSaasHttpServer.Response(503, "{\"error\":\"down\"}"));
		}
		else
		{
			pServer.enqueue(QUERY + "/loc2", new MockSaasHttpServer.Response(200, "{\"totalSize\":5,\"done\":false,\"nextRecordsUrl\":\"/services/data/v60.0/query/loc3\",\"records\":[{\"Id\":3},{\"Id\":4}]}"));
			pServer.enqueue(QUERY + "/loc3", new MockSaasHttpServer.Response(200, "{\"totalSize\":5,\"done\":true,\"records\":[{\"Id\":5}]}"));
		}
	}

	private static SalesforceConnector connector(final MockSaasHttpServer pServer)
	{
		final ConnectorConfig cfg = new ConnectorConfig();
		cfg.setId("Salesforce");
		cfg.setType("salesforce");
		cfg.setBaseUrl(pServer.baseUrl());
		cfg.setAuthType(ConnectorConfig.AuthType.OAUTH2);
		cfg.setApiKey("consumer-secret");
		cfg.setProperty("clientId", "consumer-key");
		cfg.setProperty("oauth.flow", "client_credentials");
		final SalesforceConnector connector = new SalesforceConnector();
		connector.configure(cfg);
		return connector;
	}

	private static Map<String, String> inputs(final String pMax)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("soql", "SELECT Id FROM Account");
		if (pMax != null) inputs.put("max", pMax);
		return inputs;
	}

	private static void check(final boolean pValue, final String pMessage)
	{
		if (!pValue) throw new AssertionError(pMessage);
	}
}
