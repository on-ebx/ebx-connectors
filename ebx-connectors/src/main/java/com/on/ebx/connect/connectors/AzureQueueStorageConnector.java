package com.on.ebx.connect.connectors;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.core.*;
import com.on.ebx.connect.spi.*;

/** Azure Queue Storage REST connector using scoped SAS or Entra bearer authentication. */
public final class AzureQueueStorageConnector implements Connector
{
	private static final int MAX_RESPONSE=1024*1024;
	private static final List<Operation> OPS=Arrays.asList(Operation.write("send","Send one bounded queue message","message"),Operation.search("peek","Peek 1-32 messages without changing visibility","max"),Operation.search("receive","Receive 1-32 messages without deleting them","max","visibilitySeconds"),Operation.write("ack","Explicitly delete a received message using messageId and popReceipt","messageId","popReceipt"),Operation.search("queues","Discover one bounded page of queues","marker","max"));
	private ConnectorConfig config;
	@Override
	public String getType()
	{
		return "azure-queue-storage";
	}
	@Override
	public String getLabel()
	{
		return "Azure Queue Storage";
	}
	@Override
	public List<Operation> getOperations()
	{
		return OPS;
	}
	@Override
	public void configure(final ConnectorConfig c)
	{
		this.config = c;
	}
	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig c)
	{
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(Connector.super.validateConfiguration(c).getIssues());
		if (c != null && c.getBaseUrl() != null && !safeEndpoint(c.getBaseUrl())) issues.add(new ConfigurationValidationIssue("baseUrl", "Use an HTTPS *.queue.core.windows.net account endpoint; localhost HTTP is allowed only for tests"));
		return new ConfigurationValidationResult(issues);
	}
	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "MESSAGING").description("Azure Queue Storage REST connector with bounded send, non-destructive peek, receive without implicit delete, explicit pop-receipt acknowledgement, and queue discovery. This is not Azure Service Bus.").authentication(AuthenticationMethod.API_KEY_HEADER).authentication(AuthenticationMethod.BEARER_TOKEN).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).apiVersion("Azure Queue Storage REST 2021-08-06; live acceptance required").defaults(ConnectorDefaults.standard()).pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.TOKEN, 100, 5000, "NextMarker")).configurationProperty(prop("queue", ConfigurationPropertyType.STRING, false)).configurationProperty(prop("apiVersion", ConfigurationPropertyType.STRING, false));
		for (final Operation o:OPS)
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	@Override public JsonNode execute(final ConnectorContext c,final String op,final Map<String,String> in,final JsonNode payload)throws ConnectorException
	{
		final String queue = config.getProperty("queue", null);
		final Map<String, String> q = new LinkedHashMap<String, String>();
		String method = "GET", path = "";
		byte[] body = null;
		if ("queues".equals(op))
		{
			q.put("comp", "list");
			q.put("maxresults", String.valueOf(bound(in.get("max"), 100, 1, 5000, "max")));
			if (in.get("marker") != null) q.put("marker", in.get("marker"));
		}
		else
		{
			if (queue == null || queue.trim().isEmpty()) throw new ConnectorException("Azure Queue Storage: set queue");
			path = "/"+encode(queue)+"/messages";
			if ("send".equals(op))
			{
				method = "POST";
				String message = in.get("message");
				if (message == null && payload != null) message = payload.isTextual() ? payload.asText() : payload.toString();
				if (message == null) throw new ConnectorException("Azure Queue Storage: set message");
				body = ("<QueueMessage><MessageText>"+xml(message)+"</MessageText></QueueMessage>").getBytes(StandardCharsets.UTF_8);
				if (body.length > 65536) throw new ConnectorException("Azure Queue Storage encoded message exceeds 64 KiB");
			}
			else if ("peek".equals(op))
			{
				q.put("peekonly", "true");
				q.put("numofmessages", String.valueOf(bound(in.get("max"), 1, 1, 32, "max")));
			}
			else if ("receive".equals(op))
			{
				q.put("numofmessages", String.valueOf(bound(in.get("max"), 1, 1, 32, "max")));
				q.put("visibilitytimeout", String.valueOf(bound(in.get("visibilitySeconds"), 30, 1, 604800, "visibilitySeconds")));
			}
			else if ("ack".equals(op))
			{
				method = "DELETE";
				path += "/"+encode(required(in.get("messageId"), "messageId"));
				q.put("popreceipt", required(in.get("popReceipt"), "popReceipt"));
			}
			else throw new ConnectorException("Azure Queue Storage: unknown operation '"+op+"'");
		}
		final HttpJsonClient.Response r = request(method, path, q, body);
		if (!r.isSuccess()) throw new ConnectorException("Azure Queue Storage "+op+" failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
		if ("queues".equals(op)) return queues(r.getRawBody());
		if ("peek".equals(op) || "receive".equals(op)) return messages(r.getRawBody());
		final ObjectNode out = Json.object();
		out.put("acknowledged", "ack".equals(op));
		out.put("messageId", tag(r.getRawBody(), "MessageId"));
		return out;
	}
	private HttpJsonClient.Response request(final String method, final String path, final Map<String, String> query, final byte[] body)throws ConnectorException
	{
		try
		{
			if (config == null || config.getBaseUrl() == null || !safeEndpoint(config.getBaseUrl())) throw new ConnectorException("Azure Queue Storage: invalid account endpoint");
			if (config.isPlaceholder(config.getApiKey())) throw new ConnectorException("Azure Queue Storage: set a SAS or bearer token in apiKey");
			final boolean bearer = config.getAuthType() == ConnectorConfig.AuthType.BEARER;
			final StringBuilder url = new StringBuilder(config.getBaseUrl().replaceAll("/+$", "")).append(path);
			char separator = '?';
			for (final Map.Entry<String, String> e:query.entrySet())
			{
				url.append(separator).append(encode(e.getKey())).append('=').append(encode(e.getValue()));
				separator = '&';
			}
			if (!bearer)
			{
				url.append(separator).append(config.getApiKey().replaceFirst("^\\?", ""));
			}
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("x-ms-version", config.getProperty("apiVersion", "2021-08-06"));
			headers.put("Accept", "application/xml");
			if (body != null) headers.put("Content-Type", "application/xml; charset=utf-8");
			if (bearer) headers.put("Authorization", "Bearer "+config.getApiKey());
			return new HttpJsonClient(config.getConnectTimeoutMs(), config.getReadTimeoutMs(), "EBX-Connectors/1.0 (azure-queue-storage)").sendBytesWithoutRedirects(method, url.toString(), headers, body, MAX_RESPONSE);
		}
		catch (final ConnectorException e)
		{
			throw e;
		}
		catch (final Exception e)
		{
			throw new ConnectorException("Azure Queue Storage request failed: "+e.getClass().getSimpleName(), e);
		}
	}
	static ArrayNode messages(final String xml)
	{
		final ArrayNode out = Json.mapper().createArrayNode();
		final Matcher m = Pattern.compile("<QueueMessage>(.*?)</QueueMessage>", Pattern.DOTALL).matcher(xml);
		while (m.find())
		{
			final ObjectNode x = Json.object();
			x.put("messageId", tag(m.group(1), "MessageId"));
			x.put("popReceipt", tag(m.group(1), "PopReceipt"));
			x.put("dequeueCount", tag(m.group(1), "DequeueCount"));
			x.put("message", tag(m.group(1), "MessageText"));
			out.add(x);
		}
		return out;
	}
	static ObjectNode queues(final String xml)
	{
		final ObjectNode out = Json.object();
		final ArrayNode items = Json.mapper().createArrayNode();
		final Matcher m = Pattern.compile("<Queue>(.*?)</Queue>", Pattern.DOTALL).matcher(xml);
		int count = 0;
		while (m.find() && count++<5000)
		{
			final ObjectNode x = Json.object();
			x.put("name", tag(m.group(1), "Name"));
			items.add(x);
		}
		out.set("queues", items);
		out.put("nextMarker", tag(xml, "NextMarker"));
		return out;
	}
	static String tag(final String xml, final String name)
	{
		if (xml == null) return null;
		final Matcher m = Pattern.compile("<"+name+">(.*?)</"+name+">", Pattern.DOTALL).matcher(xml);
		return m.find() ? unxml(m.group(1).trim()) : null;
	}
	private static String unxml(final String s)
	{
		return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
	}
	private static String xml(final String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
	}
	private static String encode(final String s)
	{
		return AwsSigV4.encode(s);
	}
	private static int bound(final String v, final int d, final int min, final int max, final String n)throws ConnectorException
	{
		try
		{
			final int x = v == null ? d : Integer.parseInt(v);
			if (x < min || x > max) throw new ConnectorException(n+" must be between "+min+" and "+max);
			return x;
		}
		catch (final NumberFormatException e)
		{
			throw new ConnectorException(n+" must be an integer");
		}
	}
	private static String required(final String v, final String n)throws ConnectorException
	{
		if (v == null || v.isEmpty()) throw new ConnectorException("Azure Queue Storage: set "+n);
		return v;
	}
	private static boolean safeEndpoint(final String value)
	{
		try
		{
			final URI u = URI.create(value);
			final String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
			return u.getUserInfo() == null && u.getFragment() == null && ("https".equalsIgnoreCase(u.getScheme()) && h.endsWith(".queue.core.windows.net") || "http".equalsIgnoreCase(u.getScheme()) && ("127.0.0.1".equals(h) || "localhost".equals(h)));
		}
		catch (final RuntimeException e)
		{
			return false;
		}
	}
	private static ConfigurationPropertyDefinition prop(final String n, final ConfigurationPropertyType t, final boolean r)
	{
		return ConfigurationPropertyDefinition.builder(n, t).required(r).build();
	}
}
