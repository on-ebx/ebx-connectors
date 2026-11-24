package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorConfig;

public final class AzureServiceBusConnectorTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final Fake transport = new Fake();
		final AzureServiceBusTransport.Factory previous = AzureServiceBusConnector.useFactoryForTest(config -> transport);
		try
		{
			final AzureServiceBusConnector connector = new AzureServiceBusConnector(); final ConnectorConfig config = config(); connector.configure(config);
			check(connector.validateConfiguration(config).isValid(), "valid connection string rejected");
			final ConnectorConfig invalid = config(); invalid.setBaseUrl("http://insecure.example"); invalid.setApiKey("bad");
			check(hasIssue(connector.validateConfiguration(invalid), "baseUrl") && hasIssue(connector.validateConfiguration(invalid), "apiKey"), "secure validation missing");
			JsonNode result = connector.execute(null, "publish", map("entity", "orders", "property.correlation", "42"), Json.parse("{\"id\":1}"));
			check(result.get("published").asInt() == 1 && "42".equals(transport.properties.get("correlation")), "publish not delegated");
			result = connector.execute(null, "receive", map("entity", "orders", "max", "9999"), null);
			check(transport.max == 500 && result.get("messages").get(0).get("value").get("id").asInt() == 1, "bounded receive or JSON body failed");
			connector.execute(null, "peek", map("entity", "orders"), null);
			connector.execute(null, "complete", map("entity", "orders", "lockToken", "lock-1"), null);
			connector.execute(null, "abandon", map("entity", "orders", "lockToken", "lock-2"), null);
			check(transport.peeked && transport.settlements.equals(Arrays.asList("complete:lock-1", "abandon:lock-2")), "peek/settlement not delegated");
			check(ConnectorRegistry.get().types().get("azure-service-bus") == AzureServiceBusConnector.class, "registry entry missing");
			check(com.on.ebx.connect.ebx.ui.Logos.preferredFor("AzureServiceBus") != null, "logo not packaged");
			System.out.println("PASS: Azure Service Bus bounded publish, receive, peek, settlement, validation, registry, and logo");
		}
		finally { AzureServiceBusConnector.useFactoryForTest(previous); }
	}
	private static ConnectorConfig config()
	{
		final ConnectorConfig c = new ConnectorConfig(); c.setId("asb"); c.setType("azure-service-bus"); c.setBaseUrl("sb://demo.servicebus.windows.net");
		c.setApiKey("Endpoint=sb://demo.servicebus.windows.net/;SharedAccessKeyName=send;SharedAccessKey=abcdefghi"); return c;
	}
	private static Map<String, String> map(final String... pValues)
	{
		final Map<String, String> result = new java.util.LinkedHashMap<String, String>(); for (int i = 0; i < pValues.length; i += 2) result.put(pValues[i], pValues[i + 1]); return result;
	}
	private static boolean hasIssue(final ConfigurationValidationResult pResult, final String pField) { for (final ConfigurationValidationIssue i : pResult.getIssues()) if (pField.equals(i.getPropertyName())) return true; return false; }
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
	private static final class Fake implements AzureServiceBusTransport
	{
		int max; boolean peeked; Map<String, String> properties; final List<String> settlements = new ArrayList<String>();
		@Override public void publish(final String pEntity, final String pBody, final Map<String, String> pProperties) { properties = pProperties; }
		@Override public List<Message> receive(final String pEntity, final String pSubscription, final int pMax, final long pWait, final ReceiveMode pMode) { max = pMax; return Collections.singletonList(new Message("m1", "lock", "{\"id\":1}", 10L)); }
		@Override public List<Message> peek(final String pEntity, final String pSubscription, final int pMax) { peeked = true; return Collections.singletonList(new Message("m1", null, "text", 10L)); }
		@Override public void complete(final String pEntity, final String pSubscription, final String pLock) { settlements.add("complete:" + pLock); }
		@Override public void abandon(final String pEntity, final String pSubscription, final String pLock) { settlements.add("abandon:" + pLock); }
		@Override public void close() { }
	}
}
