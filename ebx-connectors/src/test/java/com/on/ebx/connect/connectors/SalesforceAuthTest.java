package com.on.ebx.connect.connectors;

import java.util.Map;

import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * Salesforce keeps its own login with authType OAUTH2: neither the secret check nor the headers may go
 * through the generic OAuth 2.0 of AbstractRestConnector, which failed with "oauth.tokenUrl is not set"
 * on a real org (2026-09-15). No org, no network: no token is fetched before a call.
 */
public final class SalesforceAuthTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ConnectorConfig cfg = new ConnectorConfig();
		cfg.setId("Salesforce");
		cfg.setType("salesforce");
		cfg.setBaseUrl("https://example.my.salesforce.com");
		cfg.setAuthType(ConnectorConfig.AuthType.OAUTH2);
		cfg.setApiKey("consumer-secret");
		cfg.setProperty("clientId", "consumer-key");
		cfg.setProperty("oauth.flow", "client_credentials");
		final SalesforceConnector connector = new SalesforceConnector();
		connector.configure(cfg);
		connector.requireSecret();
		final Map<String, String> headers = connector.headers();
		check(!headers.containsKey("Authorization"), "no bearer before the Salesforce login");
		check("application/json".equals(headers.get("Accept")), "Accept header");
		System.out.println("PASS: Salesforce OAUTH2 without oauth.tokenUrl uses its own login, not the generic OAuth 2.0");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
