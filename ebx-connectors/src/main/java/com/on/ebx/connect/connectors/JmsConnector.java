package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MessagingTransport;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** Generic JMS 1.1 connector using JNDI and a reflection-isolated provider client. */
public class JmsConnector implements Connector
{
	private static final int MAX_MESSAGES = 500;
	private static final long MAX_TIMEOUT_MILLIS = 60000L;
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.write("publish", "Publish a JSON or text message", "destination"),
		Operation.search("receive", "Receive and acknowledge a bounded message batch", "destination"),
		Operation.read("peek", "Inspect a bounded message batch without consuming it", "destination"));
	private final MessagingTransport transport;
	private ConnectorConfig config;
	public JmsConnector() { this(new ReflectiveJmsTransport()); }
	JmsConnector(final MessagingTransport pTransport)
	{
		if (pTransport == null) throw new IllegalArgumentException("Messaging transport is required");
		transport = pTransport;
	}
	@Override public String getType() { return "jms"; }
	@Override public String getLabel() { return "JMS messaging"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }
	@Override public void configure(final ConnectorConfig pConfig)
	{
		config = pConfig; if (pConfig != null) {
			defaultProperty(pConfig, "connection.factory", "ConnectionFactory");
			defaultProperty(pConfig, "destination.type", "QUEUE");
			defaultProperty(pConfig, "acknowledgement", "CLIENT");
			defaultProperty(pConfig, "max.messages", "100");
			defaultProperty(pConfig, "receive.timeout.ms", "3000");
			defaultProperty(pConfig, "redelivery.max", "6");
			defaultProperty(pConfig, "redelivery.delay.ms", "1000");
		}
	}
	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = Connector.super.validateConfiguration(pConfig);
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		if (pConfig == null) return common;
		required(issues, pConfig, "initial.context.factory");
		required(issues, pConfig, "connection.factory");
		bounded(issues, pConfig, "max.messages", 1, MAX_MESSAGES);
		bounded(issues, pConfig, "receive.timeout.ms", 1, MAX_TIMEOUT_MILLIS);
		bounded(issues, pConfig, "redelivery.max", 0, 100);
		bounded(issues, pConfig, "redelivery.delay.ms", 0, 3600000L);
		return new ConfigurationValidationResult(issues);
	}
	@Override public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "MESSAGING")
			.description(description())
			.authentication(AuthenticationMethod.NONE).authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.STREAMING).capability(ConnectorCapability.INCREMENTAL_READ)
			.apiVersion(apiVersion()).dependency(dependency())
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 100, MAX_MESSAGES, null));
		for (final String name : new String[] { "initial.context.factory", "connection.factory", "destination", "selector" }) b.configurationProperty(property(name, ConfigurationPropertyType.STRING, false));
		b.configurationProperty(enumeration("destination.type", "QUEUE", "TOPIC")).configurationProperty(enumeration("acknowledgement", "AUTO", "CLIENT", "DUPS_OK", "TRANSACTED"));
		for (final String name : new String[] { "max.messages", "receive.timeout.ms", "redelivery.max", "redelivery.delay.ms", "priority", "ttl.ms" }) b.configurationProperty(property(name, ConfigurationPropertyType.INTEGER, false));
		b.configurationProperty(property("persistent", ConfigurationPropertyType.BOOLEAN, false));
		for (final Operation operation : OPERATIONS) { final ConnectorDirection d = operation.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(), operation.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<ConnectorCapability>emptySet())); }
		return b.build();
	}
	protected String description() { return "Provider-neutral JMS queues and topics through JNDI with bounded receive, acknowledgement, peek, and redelivery controls."; }
	protected String apiVersion() { return "JMS 1.1 provider-defined"; }
	protected ConnectorDependency dependency() { return new ConnectorDependency("JMS API and provider client", null, true, "Package the selected provider client with EBX-Connectors"); }
	@Override public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		if (config == null) throw new ConnectorException("JMS connector is not configured");
		final String destination = value(pInputs, "destination", config.getProperty("destination", null));
		if (destination == null || destination.trim().isEmpty()) throw new ConnectorException("JMS: destination is required");
		try
		{
			if ("publish".equals(pOperation)) return publish(destination, pInputs, pPayload);
			if ("receive".equals(pOperation) || "peek".equals(pOperation)) return receive(destination, pInputs, "peek".equals(pOperation));
			throw new ConnectorException("JMS: unknown operation '" + pOperation + "'");
		}
		catch (final ConnectorException ex) { throw ex; }
		catch (final Exception ex) { throw new ConnectorException("JMS " + pOperation + " failed: " + ex.getMessage(), ex); }
	}
	private JsonNode publish(final String destination, final Map<String, String> inputs, final JsonNode payload) throws Exception
	{
		final String body = value(inputs, "body", payload == null ? "" : payload.isTextual() ? payload.asText() : Json.write(payload));
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		if (inputs != null) for (final Map.Entry<String, String> e : inputs.entrySet()) if (e.getKey().startsWith("header.")) headers.put(e.getKey().substring(7), e.getValue());
		final MessagingTransport.PublishResult result = transport.publish(configuration(), destination, body, headers,
			Boolean.parseBoolean(config.getProperty("persistent", "true")), bounded(config.getProperty("priority", "4"), 0, 9), boundedLong(config.getProperty("ttl.ms", "0"), 0, 86400000L));
		final ObjectNode out = Json.object();
		out.put("destination", destination);
		out.put("messageId", result.messageId);
		out.put("timestamp", result.timestamp);
		return out;
	}
	private JsonNode receive(final String destination, final Map<String, String> inputs, final boolean peek) throws Exception
	{
		final int max = bounded(value(inputs, "max", config.getProperty("max.messages", "100")), 1, MAX_MESSAGES);
		final long timeout = boundedLong(value(inputs, "timeoutMs", config.getProperty("receive.timeout.ms", "3000")), 1, MAX_TIMEOUT_MILLIS);
		final MessagingTransport.Acknowledgement acknowledgement = MessagingTransport.Acknowledgement.valueOf(config.getProperty("acknowledgement", "CLIENT").toUpperCase(java.util.Locale.ENGLISH));
		final List<MessagingTransport.Message> messages = transport.receive(configuration(), destination, max, timeout, acknowledgement, peek, value(inputs, "selector", config.getProperty("selector", null)));
		if (messages.isEmpty()) return null;
		final ArrayNode array = Json.mapper().createArrayNode();
		for (final MessagingTransport.Message m : messages)
		{
			final ObjectNode node = array.addObject();
			node.put("id", m.id);
			node.put("correlationId", m.correlationId);
			node.put("timestamp", m.timestamp);
			node.put("redelivered", m.redelivered);
			try
			{
				node.set("value", Json.parse(m.body));
			}
			catch (final Exception ex)
			{
				node.put("value", m.body);
			}
		}
		final ObjectNode out = Json.object();
		out.put("destination", destination);
		out.put("count", array.size());
		out.put("peek", peek);
		out.set("messages", array);
		return out;
	}
	private MessagingTransport.Configuration configuration()
	{
		return new MessagingTransport.Configuration(config.getBaseUrl(), config.getProperty("initial.context.factory", null), config.getProperty("connection.factory", "ConnectionFactory"),
			config.getUsername(), config.getPassword(), config.getProperty("destination.type", "QUEUE"), bounded(config.getProperty("redelivery.max", "6"), 0, 100), boundedLong(config.getProperty("redelivery.delay.ms", "1000"), 0, 3600000L));
	}
	@Override public List<FieldInfo> describeFields(final ConnectorContext c, final String operation, final Map<String, String> inputs)
	{ return Arrays.asList(FieldInfo.of("messages[0].id", "Message identifier", "string"), FieldInfo.of("messages[0].value", "Message payload", "object"), FieldInfo.of("messages[0].redelivered", "Redelivered", "boolean")); }
	static int bounded(final String value, final int min, final int max)
	{
		final long parsed = boundedLong(value, min, max);
		return (int) parsed;
	}
	static long boundedLong(final String value, final long min, final long max)
	{
		try
		{
			final long parsed = Long.parseLong(value);
			return Math.max(min, Math.min(max, parsed));
		}
		catch (final Exception ex)
		{
			return min;
		}
	}
	private static String value(final Map<String, String> inputs, final String key, final String fallback) { return inputs != null && inputs.get(key) != null ? inputs.get(key) : fallback; }
	private static void defaultProperty(final ConnectorConfig c, final String key, final String value) { if (c.getProperty(key, null) == null) c.setProperty(key, value); }
	private static void required(final List<ConfigurationValidationIssue> issues, final ConnectorConfig c, final String key) { if (c.getProperty(key, "").trim().isEmpty()) issues.add(new ConfigurationValidationIssue(key, "A value is required")); }
	private static void bounded(final List<ConfigurationValidationIssue> issues, final ConnectorConfig c, final String key, final long min, final long max)
	{
		final String value = c.getProperty(key, null);
		if (value == null) return;
		try
		{
			final long n = Long.parseLong(value);
			if (n < min || n > max) throw new NumberFormatException();
		}
		catch (final NumberFormatException ex)
		{
			issues.add(new ConfigurationValidationIssue(key, "Must be between " + min + " and " + max));
		}
	}
	private static ConfigurationPropertyDefinition property(final String n, final ConfigurationPropertyType t, final boolean r) { return ConfigurationPropertyDefinition.builder(n, t).required(r).build(); }
	private static ConfigurationPropertyDefinition enumeration(final String n, final String... v) { return ConfigurationPropertyDefinition.builder(n, ConfigurationPropertyType.ENUMERATION).enumerationValues(v).build(); }
}
