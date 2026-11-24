package com.on.ebx.connect.connectors;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.core.*;
import com.on.ebx.connect.spi.*;

/** Bounded Elasticsearch REST connector with cursor pagination and item-aware bulk writes. */
public final class ElasticsearchConnector implements Connector
{
	private static final int MAX_REQUEST=5*1024*1024,MAX_RESPONSE=5*1024*1024,MAX_BULK=500;
	private static final List<Operation> OPS=Arrays.asList(Operation.search("indices","Discover a bounded page of indices","max"),Operation.read("mapping","Read one index mapping","index"),Operation.search("search","Bounded search using search_after rather than deep from pagination","index","size","searchAfter"),Operation.read("get","Get one document by index and id","index","id"),Operation.write("bulk","Index at most 500 documents and report every item error","index"));
	private ConnectorConfig config;
	@Override
	public String getType()
	{
		return "elasticsearch";
	}
	@Override
	public String getLabel()
	{
		return "Elasticsearch";
	}
	@Override
	public List<Operation> getOperations()
	{
		return OPS;
	}
	@Override
	public void configure(final ConnectorConfig c)
	{
		config = c;
	}
	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig c)
	{
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(Connector.super.validateConfiguration(c).getIssues());
		if (c != null && c.getBaseUrl() != null && !safeEndpoint(c.getBaseUrl())) issues.add(new ConfigurationValidationIssue("baseUrl", "Elasticsearch endpoints must use HTTPS; localhost HTTP is allowed only for offline fixtures"));
		return new ConfigurationValidationResult(issues);
	}
	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "DATA_PLATFORMS").description("Elasticsearch REST connectivity with bounded index and mapping discovery, search_after pagination, document reads, and bounded item-aware bulk indexing.").authentication(AuthenticationMethod.API_KEY_HEADER).authentication(AuthenticationMethod.BASIC).authentication(AuthenticationMethod.BEARER_TOKEN).capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).apiVersion("Elasticsearch REST 8.x; live cluster acceptance required").pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.CURSOR, 100, 1000, "search_after")).defaults(ConnectorDefaults.standard());
		for (final Operation o:OPS)
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	@Override public JsonNode execute(final ConnectorContext c,final String op,final Map<String,String> in,final JsonNode payload)throws ConnectorException
	{
		if ("indices".equals(op))
		{
			final int max = bound(in.get("max"), 100, 1, 1000, "max");
			final JsonNode response = request("GET", "/_cat/indices?format=json&h=index,health,status,docs.count,store.size&s=index&bytes=b", null);
			final ArrayNode out = Json.mapper().createArrayNode();
			if (response != null && response.isArray()) for (final JsonNode item:response)
			{
				if (out.size() >= max) break;
				out.add(item);
			}
			return out;
		}
		if ("mapping".equals(op)) return request("GET", "/"+segment(required(in.get("index"), "index"))+"/_mapping", null);
		if ("get".equals(op)) return request("GET", "/"+segment(required(in.get("index"), "index"))+"/_doc/"+segment(required(in.get("id"), "id")), null);
		if ("search".equals(op)) return search(in, payload);
		if ("bulk".equals(op)) return bulk(in, payload);
		throw new ConnectorException("Elasticsearch: unknown operation '"+op+"'");
	}
	private JsonNode search(final Map<String, String> in, final JsonNode payload)throws ConnectorException
	{
		final String index = segment(required(in.get("index"), "index"));
		final int size = bound(in.get("size"), 100, 1, 1000, "size");
		final ObjectNode body = payload != null && payload.isObject() ? ((ObjectNode)payload).deepCopy() : Json.object();
		final JsonNode from = body.get("from");
		if (from != null && from.asInt(0) > 0) throw new ConnectorException("Elasticsearch deep from pagination is not allowed; use searchAfter");
		body.remove("from");
		body.put("size", size);
		if (in.get("searchAfter") != null)
		{
			try
			{
				final JsonNode cursor = Json.parse(in.get("searchAfter"));
				if (!cursor.isArray()) throw new Exception();
				body.set("search_after", cursor);
			}
			catch (final Exception e)
			{
				throw new ConnectorException("searchAfter must be a JSON array from the previous hit sort values");
			}
		}
		final JsonNode answer = request("POST", "/"+index+"/_search", bytes(body.toString()));
		final ObjectNode out = Json.object();
		out.set("hits", answer.path("hits").path("hits"));
		final JsonNode hits = answer.path("hits").path("hits");
		if (hits.isArray() && hits.size() > 0) out.set("nextSearchAfter", hits.get(hits.size()-1).path("sort"));
		return out;
	}
	private JsonNode bulk(final Map<String, String> in, final JsonNode payload)throws ConnectorException
	{
		final String index = segment(required(in.get("index"), "index"));
		if (payload == null || !payload.isArray() || payload.size() == 0) throw new ConnectorException("Elasticsearch bulk requires a non-empty document array");
		if (payload.size() > MAX_BULK) throw new ConnectorException("Elasticsearch bulk exceeds 500 documents");
		final StringBuilder ndjson = new StringBuilder();
		for (final JsonNode original:payload)
		{
			JsonNode doc = original;
			if (!doc.isObject()) throw new ConnectorException("Every Elasticsearch bulk item must be an object");
			final ObjectNode meta = Json.object(), idx = Json.object();
			idx.put("_index", index);
			if (doc.has("_id"))
			{
				idx.put("_id", doc.get("_id").asText());
				final ObjectNode clean = ((ObjectNode)doc).deepCopy();
				clean.remove("_id");
				doc = clean;
			}
			meta.set("index", idx);
			ndjson.append(meta).append('\n').append(doc).append('\n');
		}
		final JsonNode answer = request("POST", "/_bulk", bytes(ndjson.toString()));
		final ObjectNode out = Json.object();
		out.put("errors", answer.path("errors").asBoolean(false));
		final ArrayNode failures = Json.mapper().createArrayNode();
		final JsonNode items = answer.path("items");
		if (items.isArray()) for (final JsonNode item:items)
		{
			final JsonNode result = item.path("index");
			if (result.has("error"))
			{
				final ObjectNode failure = Json.object();
				failure.put("id", result.path("_id").asText(null));
				failure.put("status", result.path("status").asInt());
				failure.put("type", result.path("error").path("type").asText(null));
				failure.put("reason", result.path("error").path("reason").asText(null));
				failures.add(failure);
			}
		}
		out.set("failures", failures);
		out.put("items", items.isArray() ? items.size() : 0);
		return out;
	}
	private JsonNode request(final String method, final String path, final byte[] body)throws ConnectorException
	{
		try
		{
			if (config == null || config.getBaseUrl() == null || !safeEndpoint(config.getBaseUrl())) throw new ConnectorException("Elasticsearch: invalid endpoint");
			if (body != null && body.length > MAX_REQUEST) throw new ConnectorException("Elasticsearch request exceeds 5 MiB");
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("Accept", "application/json");
			if (body != null) headers.put("Content-Type", path.endsWith("_bulk") ? "application/x-ndjson" : "application/json");
			if (config.getAuthType() == ConnectorConfig.AuthType.BASIC)
			{
				if (config.getUsername() == null || config.isPlaceholder(config.getPassword())) throw new ConnectorException("Elasticsearch: set Basic credentials");
				headers.put("Authorization", "Basic "+Base64.getEncoder().encodeToString((config.getUsername()+":"+config.getPassword()).getBytes(StandardCharsets.UTF_8)));
			}
			else if (config.getAuthType() == ConnectorConfig.AuthType.BEARER)
			{
				if (config.isPlaceholder(config.getApiKey())) throw new ConnectorException("Elasticsearch: set bearer token");
				headers.put("Authorization", "Bearer "+config.getApiKey());
			}
			else if (config.getAuthType() == ConnectorConfig.AuthType.API_KEY_HEADER)
			{
				if (config.isPlaceholder(config.getApiKey())) throw new ConnectorException("Elasticsearch: set API key");
				headers.put("Authorization", "ApiKey "+config.getApiKey());
			}
			final HttpJsonClient.Response r = new HttpJsonClient(config.getConnectTimeoutMs(), config.getReadTimeoutMs(), "EBX-Connectors/1.0 (elasticsearch)").sendBytesWithoutRedirects(method, config.getBaseUrl().replaceAll("/+$", "")+path, headers, body, MAX_RESPONSE);
			if (!r.isSuccess()) throw new ConnectorException("Elasticsearch request failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
			return r.getRawBody() == null || r.getRawBody().trim().isEmpty() ? Json.object() : Json.parse(r.getRawBody());
		}
		catch (final ConnectorException e)
		{
			throw e;
		}
		catch (final Exception e)
		{
			throw new ConnectorException("Elasticsearch request failed: "+e.getClass().getSimpleName(), e);
		}
	}
	private static boolean safeEndpoint(final String value)
	{
		try
		{
			final URI u = URI.create(value);
			final String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
			return u.getUserInfo() == null && u.getFragment() == null && ("https".equalsIgnoreCase(u.getScheme()) && !h.isEmpty() || "http".equalsIgnoreCase(u.getScheme()) && ("localhost".equals(h) || "127.0.0.1".equals(h)));
		}
		catch (final RuntimeException e)
		{
			return false;
		}
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
		if (v == null || v.trim().isEmpty()) throw new ConnectorException("Elasticsearch: set "+n);
		return v;
	}
	private static String segment(final String v)throws ConnectorException
	{
		if (v.contains("/") || v.contains("\\") || v.contains("..")) throw new ConnectorException("Elasticsearch path identifier is invalid");
		return AwsSigV4.encode(v);
	}
	private static byte[] bytes(final String v)
	{
		return v.getBytes(StandardCharsets.UTF_8);
	}
}
