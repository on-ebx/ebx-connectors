package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
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
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** Azure Service Bus queues/topics over an isolated Azure SDK transport adapter. */
public final class AzureServiceBusConnector implements Connector
{
	private static final int HARD_MAX = 500;
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.write("publish", "Publish one JSON message to a queue or topic", "entity"),
		Operation.search("receive", "Receive a bounded batch with peek-lock or receive-and-delete settlement", "entity"),
		Operation.read("peek", "Peek a bounded batch without acquiring locks", "entity"),
		Operation.write("complete", "Complete a peek-locked message", "entity", "lockToken"),
		Operation.write("abandon", "Abandon a peek-locked message", "entity", "lockToken"));
	private static volatile AzureServiceBusTransport.Factory factory = serviceFactory();
	private ConnectorConfig config;

	@Override public String getType() { return "azure-service-bus"; }
	@Override public String getLabel() { return "Microsoft Azure Service Bus"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }
	@Override public void configure(final ConnectorConfig pConfig)
	{
		config = pConfig;
		if (pConfig != null)
		{
			if (pConfig.getProperty("auth.mode", null) == null) pConfig.setProperty("auth.mode", "CONNECTION_STRING");
			if (pConfig.getProperty("receive.mode", null) == null) pConfig.setProperty("receive.mode", "PEEK_LOCK");
			if (pConfig.getProperty("max.messages", null) == null) pConfig.setProperty("max.messages", "100");
			if (pConfig.getProperty("wait.ms", null) == null) pConfig.setProperty("wait.ms", "5000");
		}
	}

	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final List<ConfigurationValidationIssue> issues = new java.util.ArrayList<ConfigurationValidationIssue>();
		if (pConfig == null)
		{
			issues.add(new ConfigurationValidationIssue("configuration", "Configuration is required"));
			return new ConfigurationValidationResult(issues);
		}
		final String endpoint = pConfig.getBaseUrl();
		if (endpoint == null || !(endpoint.startsWith("sb://") || endpoint.startsWith("https://")) || !endpoint.contains(".servicebus.windows.net"))
			issues.add(new ConfigurationValidationIssue("baseUrl", "Namespace must be an sb:// or https://*.servicebus.windows.net endpoint"));
		final String mode = pConfig.getProperty("auth.mode", "CONNECTION_STRING");
		if ("CONNECTION_STRING".equals(mode))
		{
			final String secret = pConfig.getApiKey();
			if (secret == null || pConfig.isPlaceholder(secret) || !secret.contains("Endpoint=sb://") || !secret.contains("SharedAccessKeyName=") || !secret.contains("SharedAccessKey="))
				issues.add(new ConfigurationValidationIssue("apiKey", "A complete Service Bus connection string must be stored in the encrypted API key field"));
		}
		else if (!"AZURE_IDENTITY".equals(mode)) issues.add(new ConfigurationValidationIssue("auth.mode", "Use CONNECTION_STRING or AZURE_IDENTITY"));
		return new ConfigurationValidationResult(issues);
	}

	@Override public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final String entity = required(pInputs, "entity");
		final String subscription = value(pInputs, "subscription", config.getProperty("subscription", null));
		try (AzureServiceBusTransport transport = factory.open(config))
		{
			if ("publish".equals(pOperation))
			{
				transport.publish("TOPIC".equalsIgnoreCase(value(pInputs, "entityType", config.getProperty("entity.type", "QUEUE"))) ? "topic:" + entity : entity,
					Json.write(pPayload == null ? Json.object() : pPayload), applicationProperties(pInputs));
				final ObjectNode out = Json.object();
				out.put("entity", entity);
				out.put("published", 1);
				return out;
			}
			if ("receive".equals(pOperation))
			{
				final AzureServiceBusTransport.ReceiveMode mode = "RECEIVE_AND_DELETE".equals(value(pInputs, "mode", config.getProperty("receive.mode", "PEEK_LOCK")))
					? AzureServiceBusTransport.ReceiveMode.RECEIVE_AND_DELETE : AzureServiceBusTransport.ReceiveMode.PEEK_LOCK;
				return messages(entity, transport.receive(entity, subscription, bounded(pInputs, "max", "max.messages", 100), boundedLong(pInputs, "waitMs", "wait.ms", 5000), mode), mode.name());
			}
			if ("peek".equals(pOperation)) return messages(entity, transport.peek(entity, subscription, bounded(pInputs, "max", "max.messages", 100)), "PEEK");
			if ("complete".equals(pOperation) || "abandon".equals(pOperation))
			{
				final String lock = required(pInputs, "lockToken");
				if ("complete".equals(pOperation)) transport.complete(entity, subscription, lock);
				else transport.abandon(entity, subscription, lock);
				final ObjectNode out = Json.object();
				out.put("entity", entity);
				out.put("settlement", pOperation);
				out.put("lockToken", lock);
				return out;
			}
			throw new ConnectorException("Azure Service Bus: unknown operation '" + pOperation + "'");
		}
		catch (final ConnectorException ex) { throw ex; }
		catch (final Exception ex) { throw new ConnectorException("Azure Service Bus " + pOperation + " failed: " + ex.getMessage(), ex); }
	}

	private static JsonNode messages(final String pEntity, final List<AzureServiceBusTransport.Message> pMessages, final String pMode) throws Exception
	{
		if (pMessages == null || pMessages.isEmpty()) return null;
		final ObjectNode out = Json.object();
		out.put("entity", pEntity);
		out.put("mode", pMode);
		final ArrayNode rows = out.putArray("messages");
		for (final AzureServiceBusTransport.Message message : pMessages)
		{
			final ObjectNode row = rows.addObject();
			row.put("messageId", message.messageId);
			row.put("lockToken", message.lockToken);
			row.put("enqueuedAt", message.enqueuedAt);
			try
			{
				row.set("value", Json.parse(message.body));
			}
			catch (final Exception ex)
			{
				row.put("value", message.body);
			}
		}
		out.put("count", rows.size());
		return out;
	}
	private static Map<String, String> applicationProperties(final Map<String, String> pInputs)
	{
		final Map<String, String> values = new LinkedHashMap<String, String>(); if (pInputs != null) for (final Map.Entry<String, String> entry : pInputs.entrySet())
			if (entry.getKey().startsWith("property.")) values.put(entry.getKey().substring(9), entry.getValue());
			return values;
	}
	private int bounded(final Map<String, String> pInputs, final String pInput, final String pProperty, final int pDefault)
	{ return (int) Math.max(1L, Math.min(HARD_MAX, boundedLong(pInputs, pInput, pProperty, pDefault))); }
	private long boundedLong(final Map<String, String> pInputs, final String pInput, final String pProperty, final long pDefault)
	{
		try
		{
			return Math.max(1L, Long.parseLong(value(pInputs, pInput, config.getProperty(pProperty, String.valueOf(pDefault)))));
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}
	private static String required(final Map<String, String> pInputs, final String pName) throws ConnectorException
	{
		final String value = value(pInputs, pName, null);
		if (value == null || value.trim().isEmpty()) throw new ConnectorException("Azure Service Bus: input " + pName + " is required");
		return value.trim();
	}
	private static String value(final Map<String, String> pInputs, final String pName, final String pDefault) { return pInputs != null && pInputs.get(pName) != null ? pInputs.get(pName) : pDefault; }

	@Override public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder out = ConnectorDescriptor.builder(getType(), getLabel(), "MESSAGING")
			.description("Azure Service Bus queues and topics with bounded AMQP publishing, receive/peek, and explicit message settlement.")
			.authentication(AuthenticationMethod.CUSTOM).authentication(AuthenticationMethod.NONE)
			.capability(ConnectorCapability.STREAMING).capability(ConnectorCapability.INCREMENTAL_READ).capability(ConnectorCapability.BULK_READ)
			.apiVersion("Azure Service Bus AMQP 1.0").dependency(new ConnectorDependency("Azure Messaging Service Bus SDK", "com.azure:azure-messaging-servicebus:7.x", true, "Packaged transport adapter loaded through ServiceLoader"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.CURSOR, 100, HARD_MAX, "lockToken"))
			.configurationProperty(enumProperty("auth.mode", "CONNECTION_STRING", "AZURE_IDENTITY"))
			.configurationProperty(property("entity", ConfigurationPropertyType.STRING)).configurationProperty(property("subscription", ConfigurationPropertyType.STRING))
			.configurationProperty(enumProperty("entity.type", "QUEUE", "TOPIC"))
			.configurationProperty(enumProperty("receive.mode", "PEEK_LOCK", "RECEIVE_AND_DELETE"))
			.configurationProperty(property("max.messages", ConfigurationPropertyType.INTEGER)).configurationProperty(property("wait.ms", ConfigurationPropertyType.INTEGER));
		for (final Operation operation : OPERATIONS)
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			out.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(), operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return out.build();
	}
	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType) { return ConfigurationPropertyDefinition.builder(pName, pType).build(); }
	private static ConfigurationPropertyDefinition enumProperty(final String pName, final String... pValues) { return ConfigurationPropertyDefinition.builder(pName, ConfigurationPropertyType.ENUMERATION).enumerationValues(pValues).build(); }
	private static AzureServiceBusTransport.Factory serviceFactory()
	{
		return new AzureServiceBusTransport.Factory() { @Override public AzureServiceBusTransport open(final ConnectorConfig pConfig) throws Exception {
			final java.util.Iterator<AzureServiceBusTransport.Factory> providers = ServiceLoader.load(AzureServiceBusTransport.Factory.class, AzureServiceBusConnector.class.getClassLoader()).iterator();
			if (!providers.hasNext()) throw new ConnectorException("Azure Service Bus transport adapter is not installed");
			return providers.next().open(pConfig);
		}
	};
	}
	static AzureServiceBusTransport.Factory useFactoryForTest(final AzureServiceBusTransport.Factory pFactory)
	{
		final AzureServiceBusTransport.Factory previous = factory;
		factory = pFactory;
		return previous;
	}
}
