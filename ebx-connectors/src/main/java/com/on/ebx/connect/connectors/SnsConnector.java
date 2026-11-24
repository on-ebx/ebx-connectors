package com.on.ebx.connect.connectors;

import java.util.*;
import java.util.regex.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.core.*;
import com.on.ebx.connect.spi.*;

/** Amazon SNS connector limited to publish and read-only topic discovery. */
public final class SnsConnector extends AwsQueryConnectorSupport implements Connector
{
	private static final List<Operation> OPERATIONS=Arrays.asList(Operation.write("publish","Publish one bounded message to a configured or supplied topic","message","topicArn","subject"),Operation.search("topics","Discover one bounded page of topics","nextToken"));
	@Override
	public String getType()
	{
		return "amazon-sns";
	}
	@Override
	public String getLabel()
	{
		return "Amazon SNS";
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
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "MESSAGING").description("Amazon SNS signed with the shared hardened AWS credential chain. Scope is bounded publish and topic discovery only; subscription administration is not exposed.").authentication(AuthenticationMethod.API_KEY_HEADER).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.BULK_WRITE).apiVersion("Amazon SNS Query API 2010-03-31; live acceptance required").defaults(ConnectorDefaults.standard()).configurationProperty(prop("topicArn", ConfigurationPropertyType.STRING, false)).configurationProperty(prop("region", ConfigurationPropertyType.STRING, false)).configurationProperty(prop("endpoint", ConfigurationPropertyType.URL, false));
		for (final Operation o:OPERATIONS)
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	@Override public JsonNode execute(final ConnectorContext c,final String op,final Map<String,String> in,final JsonNode payload)throws ConnectorException
	{
		final String region = config().getProperty("region", "us-east-1");
		final String endpoint = config().getProperty("endpoint", "https://sns."+region+".amazonaws.com/");
		final Map<String, String> p = new LinkedHashMap<String, String>();
		p.put("Version", "2010-03-31");
		if ("publish".equals(op))
		{
			String message = in.get("message");
			if (message == null && payload != null) message = payload.isTextual() ? payload.asText() : payload.toString();
			if (message == null || message.isEmpty()) throw new ConnectorException("SNS: set message");
			if (message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144) throw new ConnectorException("SNS message exceeds 256 KiB");
			final String arn = in.get("topicArn") != null ? in.get("topicArn") : config().getProperty("topicArn", null);
			if (arn == null || arn.isEmpty()) throw new ConnectorException("SNS: set topicArn");
			p.put("Action", "Publish");
			p.put("TopicArn", arn);
			p.put("Message", message);
			if (in.get("subject") != null) p.put("Subject", in.get("subject"));
		}
		else if ("topics".equals(op))
		{
			p.put("Action", "ListTopics");
			if (in.get("nextToken") != null) p.put("NextToken", in.get("nextToken"));
		}
		else throw new ConnectorException("SNS: unknown operation '"+op+"'");
		final HttpJsonClient.Response r = query("sns", endpoint, p);
		if (!r.isSuccess()) throw new ConnectorException("SNS "+op+" failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
		if ("publish".equals(op))
		{
			final ObjectNode out = Json.object();
			out.put("messageId", element(r.getRawBody(), "MessageId"));
			return out;
		}
		return topics(r.getRawBody());
	}
	static ObjectNode topics(final String xml)
	{
		final ObjectNode out = Json.object();
		final ArrayNode items = Json.mapper().createArrayNode();
		final Matcher m = Pattern.compile("<(?:[A-Za-z0-9_]+:)?TopicArn>(.*?)</(?:[A-Za-z0-9_]+:)?TopicArn>", Pattern.DOTALL).matcher(xml);
		int count = 0;
		while (m.find() && count++<100)
		{
			final ObjectNode x = Json.object();
			x.put("topicArn", unescape(m.group(1).trim()));
			items.add(x);
		}
		out.set("topics", items);
		out.put("nextToken", element(xml, "NextToken"));
		return out;
	}
	private static ConfigurationPropertyDefinition prop(final String n,final ConfigurationPropertyType t,final boolean r){return ConfigurationPropertyDefinition.builder(n,t).required(r).build();}
}
