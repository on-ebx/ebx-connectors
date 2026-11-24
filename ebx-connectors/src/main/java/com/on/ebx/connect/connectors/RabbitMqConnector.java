package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.*;

/** RabbitMQ AMQP 0-9-1 connector with bounded manual acknowledgement semantics. */
public final class RabbitMqConnector implements Connector
{
	interface Factory { AmqpTransport open(ConnectorConfig pConfig) throws ConnectorException; }
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.write("publish", "Publish one JSON message", "routingKey"),
		Operation.search("receive", "Receive and acknowledge a bounded batch", new String[0]),
		Operation.read("peek", "Inspect a bounded batch and requeue every delivery", new String[0]));
	private final Factory factory;
	private ConnectorConfig config;
	public RabbitMqConnector()
	{
		this(new Factory()
		{
			public AmqpTransport open(final ConnectorConfig c) throws ConnectorException
			{
				return new ReflectiveRabbitMqTransport(c);
			}
		});
	}
	RabbitMqConnector(final Factory pFactory) { factory = pFactory; }
	public String getType() { return "rabbitmq"; }
	public String getLabel() { return "RabbitMQ"; }
	public List<Operation> getOperations() { return OPERATIONS; }
	public void configure(final ConnectorConfig pConfig) { config = pConfig; }
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final java.util.List<ConfigurationValidationIssue> issues = new java.util.ArrayList<ConfigurationValidationIssue>();
		if (pConfig == null || pConfig.getBaseUrl() == null || !(pConfig.getBaseUrl().startsWith("amqp://") || pConfig.getBaseUrl().startsWith("amqps://")))
			issues.add(new ConfigurationValidationIssue("baseUrl", "Must start with amqp:// or amqps://"));
		if (pConfig != null && pConfig.getProperty("queue", "").trim().isEmpty()) issues.add(new ConfigurationValidationIssue("queue", "Queue is required for receive and peek"));
		return new ConfigurationValidationResult(issues);
	}
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String,String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		if (config == null) throw new ConnectorException("RabbitMQ is not configured");
		final AmqpTransport transport = factory.open(config);
		try
		{
			if ("publish".equals(pOperation)) return publish(transport, pInputs, pPayload);
			if ("receive".equals(pOperation) || "peek".equals(pOperation)) return receive(transport, pInputs, "peek".equals(pOperation));
			throw new ConnectorException("RabbitMQ: unknown operation '" + pOperation + "'");
		}
		finally { transport.close(); }
	}
	private JsonNode publish(final AmqpTransport pTransport, final Map<String,String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final String exchange = value(pInputs, "exchange", config.getProperty("exchange", ""));
		final String routing = value(pInputs, "routingKey", config.getProperty("routing.key", config.getProperty("queue", "")));
		if (routing.isEmpty()) throw new ConnectorException("RabbitMQ routingKey or queue is required");
		final byte[] body = Json.write(pPayload == null ? Json.object() : pPayload).getBytes(StandardCharsets.UTF_8);
		pTransport.publish(exchange, routing, body);
		final ObjectNode out = Json.object();
		out.put("exchange", exchange);
		out.put("routingKey", routing);
		out.put("bytes", body.length);
		return out;
	}
	private JsonNode receive(final AmqpTransport pTransport, final Map<String,String> pInputs, final boolean pPeek) throws ConnectorException
	{
		final String queue = value(pInputs, "queue", config.getProperty("queue", ""));
		final int max = Math.max(1, Math.min(500, integer(value(pInputs, "max", config.getProperty("max.messages", "100")), 100)));
		final ArrayNode messages = Json.mapper().createArrayNode();
		for (int i=0; i<max; i++)
		{
			final AmqpTransport.Delivery delivery = pTransport.get(queue);
			if (delivery == null) break;
			try
			{
				final ObjectNode item = messages.addObject();
				item.put("deliveryTag", delivery.tag);
				item.put("redelivered", delivery.redelivered);
				item.put("remaining", delivery.remaining);
				final String text = new String(delivery.body, StandardCharsets.UTF_8);
				try
				{
					item.set("value", Json.parse(text));
				}
				catch (final Exception ex)
				{
					item.put("value", text);
				}
				if (pPeek) pTransport.nack(delivery.tag, true);
				else pTransport.ack(delivery.tag);
			}
			catch (final RuntimeException ex)
			{
				pTransport.nack(delivery.tag, true);
				throw new ConnectorException("RabbitMQ delivery processing failed", ex);
			}
		}
		if (messages.size() == 0) return null;
		final ObjectNode out = Json.object();
		out.put("queue", queue);
		out.put("count", messages.size());
		out.put("acknowledged", !pPeek);
		out.set("messages", messages);
		return out;
	}
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b=ConnectorDescriptor.builder(getType(),getLabel(),"MESSAGING").description("RabbitMQ AMQP 0-9-1 bounded publish, receive and non-destructive peek with manual acknowledgements.")
			.authentication(AuthenticationMethod.BASIC).authentication(AuthenticationMethod.NONE).capability(ConnectorCapability.STREAMING).capability(ConnectorCapability.INCREMENTAL_READ)
			.apiVersion("AMQP 0-9-1").dependency(new ConnectorDependency("RabbitMQ Java client","com.rabbitmq:amqp-client:5.x",true,"Packaged by EBX-Connectors under WEB-INF/lib"))
			.configurationProperty(prop("queue",ConfigurationPropertyType.STRING,true)).configurationProperty(prop("exchange",ConfigurationPropertyType.STRING,false))
			.configurationProperty(prop("routing.key",ConfigurationPropertyType.STRING,false)).configurationProperty(prop("prefetch",ConfigurationPropertyType.INTEGER,false))
			.configurationProperty(prop("max.messages",ConfigurationPropertyType.INTEGER,false));
		for (final Operation op:OPERATIONS)
		{
			final ConnectorDirection d = op.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	private static ConfigurationPropertyDefinition prop(final String n,final ConfigurationPropertyType t,final boolean r){return ConfigurationPropertyDefinition.builder(n,t).required(r).build();}
	private static String value(final Map<String, String> p, final String k, final String d)
	{
		final String v = p == null ? null : p.get(k);
		return v == null ? d : v;
	}
	private static int integer(final String v, final int d)
	{
		try
		{
			return Integer.parseInt(v);
		}
		catch (final Exception ex)
		{
			return d;
		}
	}
}
