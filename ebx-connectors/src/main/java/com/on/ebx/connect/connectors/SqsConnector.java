package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.*;

/** Amazon SQS Query API connector. Receive never deletes messages implicitly. */
public final class SqsConnector extends AwsQueryConnectorSupport implements Connector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.write("send", "Send one bounded message (input message or payload)", "message"),
		Operation.search("receive", "Receive 1-10 messages without deleting them", "max", "waitSeconds", "visibilitySeconds"),
		Operation.search("peek", "Inspect one immediately visible message with zero visibility timeout"),
		Operation.write("ack", "Explicitly delete one message by receiptHandle", "receiptHandle"));
	@Override
	public String getType()
	{
		return "amazon-sqs";
	}
	@Override
	public String getLabel()
	{
		return "Amazon SQS";
	}
	@Override
	public List<Operation> getOperations()
	{
		return OPERATIONS;
	}
	@Override
	public void configure(final ConnectorConfig c)
	{
		setConfig(c);
	}
	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "MESSAGING").description("Amazon SQS signed with the shared hardened AWS credential chain. Receive and peek never delete implicitly; acknowledgement is a separate explicit operation.").authentication(AuthenticationMethod.API_KEY_HEADER).capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).apiVersion("Amazon SQS Query API 2012-11-05; live acceptance required").defaults(ConnectorDefaults.standard()).configurationProperty(prop("queueUrl", ConfigurationPropertyType.URL, true)).configurationProperty(prop("region", ConfigurationPropertyType.STRING, false)).configurationProperty(prop("endpoint", ConfigurationPropertyType.URL, false));
		for (final Operation o:OPERATIONS)
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	@Override public JsonNode execute(final ConnectorContext c,final String op,final Map<String,String> in,final JsonNode payload)throws ConnectorException
	{
		final String queue=required(config().getProperty("queueUrl",null),"queueUrl");
		final Map<String, String> p = new LinkedHashMap<String, String>();
		p.put("Version", "2012-11-05");
		if ("send".equals(op))
		{
			String message = in.get("message");
			if (message == null && payload != null) message = payload.isTextual() ? payload.asText() : payload.toString();
			message = required(message, "message");
			if (message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144) throw new ConnectorException("SQS message exceeds 256 KiB");
			p.put("Action", "SendMessage");
			p.put("MessageBody", message);
		}
		else if ("receive".equals(op) || "peek".equals(op))
		{
			p.put("Action", "ReceiveMessage");
			p.put("MaxNumberOfMessages", String.valueOf("peek".equals(op) ? 1 : bounded(in.get("max"), 1, 1, 10, "max")));
			p.put("WaitTimeSeconds", String.valueOf("peek".equals(op) ? 0 : bounded(in.get("waitSeconds"), 0, 0, 20, "waitSeconds")));
			p.put("VisibilityTimeout", String.valueOf("peek".equals(op) ? 0 : bounded(in.get("visibilitySeconds"), 30, 0, 43200, "visibilitySeconds")));
		}
		else if ("ack".equals(op))
		{
			p.put("Action", "DeleteMessage");
			p.put("ReceiptHandle", required(in.get("receiptHandle"), "receiptHandle"));
		}
		else throw new ConnectorException("SQS: unknown operation '"+op+"'");
		final HttpJsonClient.Response r = query("sqs", queue, p);
		if (!r.isSuccess()) throw new ConnectorException("SQS "+op+" failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
		if ("receive".equals(op) || "peek".equals(op)) return messages(r.getRawBody());
		final ObjectNode out = Json.object();
		out.put("messageId", element(r.getRawBody(), "MessageId"));
		out.put("acknowledged", "ack".equals(op));
		return out;
	}
	static ArrayNode messages(final String xml)
	{
		final ArrayNode out = Json.mapper().createArrayNode();
		final Matcher m = Pattern.compile("<Message>(.*?)</Message>", Pattern.DOTALL).matcher(xml);
		while (m.find())
		{
			final ObjectNode x = Json.object();
			x.put("messageId", element(m.group(1), "MessageId"));
			x.put("receiptHandle", element(m.group(1), "ReceiptHandle"));
			x.put("body", element(m.group(1), "Body"));
			out.add(x);
		}
		return out;
	}
	private static String required(final String v, final String n)throws ConnectorException
	{
		if (v == null || v.trim().isEmpty()) throw new ConnectorException("SQS: set "+n);
		return v;
	}
	private static ConfigurationPropertyDefinition prop(final String n,final ConfigurationPropertyType t,final boolean r){return ConfigurationPropertyDefinition.builder(n,t).required(r).build();}
}
