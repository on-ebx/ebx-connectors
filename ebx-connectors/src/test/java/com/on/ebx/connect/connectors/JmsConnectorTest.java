package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MessagingTransport;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorConfig;

/** Deterministic provider-neutral JMS bounds, acknowledgement and profile contract. */
public final class JmsConnectorTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final FakeTransport transport = new FakeTransport(); final JmsConnector connector = new JmsConnector(transport);
		final ConnectorConfig config = generic(); connector.configure(config); check(connector.validateConfiguration(config).isValid(), "valid generic JMS rejected");
		final Map<String, String> receive = new java.util.LinkedHashMap<String, String>(); receive.put("destination", "queue://companies"); receive.put("max", "9999"); receive.put("timeoutMs", "999999");
		final JsonNode result = connector.execute(null, "receive", receive, null); check(result.path("count").asInt() == 1, "receive result");
		check(transport.maximum == 500 && transport.timeout == 60000L && transport.acknowledgement == MessagingTransport.Acknowledgement.CLIENT && !transport.peek, "receive bounds/ack");
		connector.execute(null, "peek", Collections.singletonMap("destination", "queue://companies"), null); check(transport.peek, "peek not delegated");
		connector.execute(null, "publish", Collections.singletonMap("destination", "queue://companies"), Json.object().put("id", 1)); check(transport.published.contains("\"id\":1"), "publish body");
		final ActiveMqConnector activeMq = new ActiveMqConnector(); final ConnectorConfig active = activeMq(); activeMq.configure(active);
		check(ActiveMqConnector.INITIAL_CONTEXT_FACTORY.equals(active.getProperty("initial.context.factory", null)), "ActiveMQ factory default");
		check(activeMq.validateConfiguration(active).isValid(), "valid ActiveMQ rejected");
		active.setBaseUrl("amqp://localhost:5672"); check(hasIssue(activeMq.validateConfiguration(active), "baseUrl"), "invalid ActiveMQ URL accepted");
		check("MESSAGING".equals(activeMq.getDescriptor().getCategory()) && activeMq.getDescriptor().getDependencies().get(0).getName().contains("ActiveMQ"), "ActiveMQ descriptor");
		check(ConnectorRegistry.get().describeType("jms") != null && ConnectorRegistry.get().describeType("activemq") != null, "messaging registry entries");
		System.out.println("PASS: generic JMS and Apache ActiveMQ messaging foundation");
	}
	private static ConnectorConfig generic() { final ConnectorConfig c = new ConnectorConfig(); c.setId("jms"); c.setType("jms"); c.setBaseUrl("tcp://broker:61616"); c.setAuthType(ConnectorConfig.AuthType.NONE); c.setProperty("initial.context.factory", "example.Factory"); return c; }
	private static ConnectorConfig activeMq() { final ConnectorConfig c = new ConnectorConfig(); c.setId("activemq"); c.setType("activemq"); c.setBaseUrl("tcp://localhost:61616"); c.setAuthType(ConnectorConfig.AuthType.NONE); return c; }
	private static boolean hasIssue(final ConfigurationValidationResult r, final String field) { for (final ConfigurationValidationIssue i : r.getIssues()) if (field.equals(i.getPropertyName())) return true; return false; }
	private static void check(final boolean value, final String message) { if (!value) throw new AssertionError(message); }
	private static final class FakeTransport implements MessagingTransport
	{
		int maximum; long timeout; Acknowledgement acknowledgement; boolean peek; String published;
		public PublishResult publish(final Configuration c, final String d, final String body, final Map<String, String> h, final boolean p, final int priority, final long ttl) { published = body; return new PublishResult("ID:1", 1L); }
		public List<Message> receive(final Configuration c, final String d, final int max, final long t, final Acknowledgement a, final boolean p, final String s) { maximum=max; timeout=t; acknowledgement=a; peek=p; return Arrays.asList(new Message("ID:1", null, "{\"name\":\"ACME\"}", 1L, false, null)); }
	}
}
