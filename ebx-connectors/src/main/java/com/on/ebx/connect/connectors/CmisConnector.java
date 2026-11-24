package com.on.ebx.connect.connectors;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AwsSigV4;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDefaults;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** Bounded CMIS 1.1 Browser Binding connector; independent of vendor-specific repository SDKs. */
public final class CmisConnector implements Connector
{
	private static final int MAX_RESULTS=500,MAX_CONTENT=10*1024*1024;
	private static final List<Operation> OPS=Arrays.asList(Operation.search("repositories","Discover CMIS repositories"),Operation.search("children","List a bounded page of folder children","repositoryId","objectId"),Operation.read("object","Read object metadata","repositoryId","objectId"),Operation.read("content","Read a bounded content stream as base64","repositoryId","objectId"),Operation.write("createDocument","Create one document in a folder","repositoryId","folderId","name","contentType"),Operation.write("deleteObject","Delete one document or empty folder","repositoryId","objectId"));
	private ConnectorConfig config;
	private final boolean allowLoopback;
	public CmisConnector()
	{
		this(false);
	}
	CmisConnector(final boolean loopback)
	{
		allowLoopback = loopback;
	}
	public String getType()
	{
		return "cmis";
	}
	public String getLabel()
	{
		return "CMIS Repository";
	}
	public List<Operation> getOperations()
	{
		return OPS;
	}
	public void configure(final ConnectorConfig c)
	{
		config = c;
	}
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig c)
	{
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>();
		if (c == null) return new ConfigurationValidationResult(issues);
		try
		{
			final URI u = URI.create(c.getBaseUrl());
			final boolean loop = allowLoopback && ("127.0.0.1".equals(u.getHost()) || "localhost".equalsIgnoreCase(u.getHost()));
			if (u.getUserInfo() != null || u.getFragment() != null || u.getHost() == null || (!loop && !"https".equalsIgnoreCase(u.getScheme()))) issues.add(new ConfigurationValidationIssue("baseUrl", "Use an HTTPS CMIS Browser Binding service URL without embedded credentials"));
		}
		catch (final RuntimeException e)
		{
			issues.add(new ConfigurationValidationIssue("baseUrl", "Use a valid CMIS Browser Binding service URL"));
		}
		if (c.getAuthType() != ConnectorConfig.AuthType.BASIC && c.getAuthType() != ConnectorConfig.AuthType.BEARER) issues.add(new ConfigurationValidationIssue("authType", "Use Basic or bearer authentication"));
		if (c.getAuthType() == ConnectorConfig.AuthType.BASIC && (c.isPlaceholder(c.getUsername()) || c.isPlaceholder(c.getPassword()))) issues.add(new ConfigurationValidationIssue("password", "Set CMIS username and password"));
		if (c.getAuthType() == ConnectorConfig.AuthType.BEARER && c.isPlaceholder(c.getApiKey())) issues.add(new ConfigurationValidationIssue("apiKey", "Set CMIS bearer token"));
		return new ConfigurationValidationResult(issues);
	}
	public JsonNode execute(final ConnectorContext context, final String operation, final Map<String, String> inputs, final JsonNode payload)throws ConnectorException
	{
		if (config == null || !validateConfiguration(config).isValid()) throw new ConnectorException("CMIS configuration is invalid");
		final Map<String, String> in = inputs == null ? Collections.<String, String>emptyMap() : inputs;
		if ("repositories".equals(operation))
		{
			final JsonNode body = json(request("GET", "", query("cmisselector", "repositories"), null, null), operation);
			final ArrayNode out = Json.mapper().createArrayNode();
			if (body.isObject()) for (final JsonNode repo:body) if (out.size() < MAX_RESULTS) out.add(repo);
			return out;
		}
		final String repository = identifier(in, "repositoryId"), object = identifier(in, "objectId");
		if ("children".equals(operation))
		{
			final int max = bound(in.get("max"), 100);
			final int skip = offset(in.get("offset"));
			final Map<String, String> q = query("cmisselector", "children");
			q.put("objectId", object);
			q.put("maxItems", String.valueOf(max));
			q.put("skipCount", String.valueOf(skip));
			final JsonNode body = json(request("GET", repository+"/root", q, null, null), operation);
			final ObjectNode out = Json.object();
			final ArrayNode rows = out.putArray("results");
			final JsonNode objects = body.path("objects");
			if (objects.isArray()) for (final JsonNode item:objects) if (rows.size() < max) rows.add(item);
			out.put("count", rows.size());
			out.put("hasMoreItems", body.path("hasMoreItems").asBoolean(false));
			if (out.path("hasMoreItems").asBoolean()) out.put("nextOffset", skip+rows.size());
			return out;
		}
		if ("object".equals(operation))
		{
			final Map<String, String> q = query("cmisselector", "object");
			q.put("objectId", object);
			return json(request("GET", repository+"/root", q, null, null), operation);
		}
		if ("content".equals(operation))
		{
			final Map<String, String> q = query("cmisselector", "content");
			q.put("objectId", object);
			final HttpJsonClient.Response r = request("GET", repository+"/root", q, null, null);
			success(r, operation);
			final byte[] bytes = r.getRawBody() == null ? new byte[0] : r.getRawBody().getBytes(StandardCharsets.ISO_8859_1);
			if (bytes.length > MAX_CONTENT) throw new ConnectorException("CMIS content exceeds 10 MiB");
			return Json.object().put("objectId", object).put("bytes", bytes.length).put("contentBase64", Base64.getEncoder().encodeToString(bytes));
		}
		if ("createDocument".equals(operation)) return create(repository, identifier(in, "folderId"), required(in, "name"), required(in, "contentType"), payload);
		if ("deleteObject".equals(operation))
		{
			final Map<String, String> form = new LinkedHashMap<String, String>();
			form.put("cmisaction", "delete");
			form.put("objectId", object);
			success(request("POST", repository+"/root", null, "application/x-www-form-urlencoded", form(form)), operation);
			return Json.object().put("deleted", true);
		}
		throw new ConnectorException("CMIS: unknown operation '"+operation+"'");
	}
	private JsonNode create(final String repo, final String folder, final String name, final String type, final JsonNode payload)throws ConnectorException
	{
		if (name.length() > 255 || name.contains("/") || name.contains("\\")) throw new ConnectorException("CMIS document name is invalid");
		if (payload == null || !payload.isObject() || !payload.has("contentBase64")) throw new ConnectorException("CMIS createDocument requires contentBase64");
		final byte[] content;
		try
		{
			content = Base64.getDecoder().decode(payload.path("contentBase64").asText());
		}
		catch (final RuntimeException e)
		{
			throw new ConnectorException("CMIS contentBase64 is invalid");
		}
		if (content.length > MAX_CONTENT) throw new ConnectorException("CMIS content exceeds 10 MiB");
		final String boundary = "ebxCmisBoundary";
		final ByteArrayBuilder b = new ByteArrayBuilder(MAX_CONTENT+16384);
		b.field(boundary, "cmisaction", "createDocument");
		b.field(boundary, "objectId", folder);
		b.field(boundary, "propertyId[0]", "cmis:objectTypeId");
		b.field(boundary, "propertyValue[0]", "cmis:document");
		b.field(boundary, "propertyId[1]", "cmis:name");
		b.field(boundary, "propertyValue[1]", name);
		b.file(boundary, "content", name, type, content);
		b.end(boundary);
		return json(request("POST", repo+"/root", null, "multipart/form-data; boundary="+boundary, b.bytes()), "createDocument");
	}
	private HttpJsonClient.Response request(final String method, final String path, final Map<String, String> query, final String contentType, final byte[] body)throws ConnectorException
	{
		try
		{
			final StringBuilder url = new StringBuilder(config.getBaseUrl().replaceAll("/+$", ""));
			if (path != null && !path.isEmpty()) url.append('/').append(path);
			char sep = '?';
			if (query != null) for (final Map.Entry<String, String> e:query.entrySet())
			{
				url.append(sep).append(AwsSigV4.encode(e.getKey())).append('=').append(AwsSigV4.encode(e.getValue()));
				sep = '&';
			}
			final Map<String, String> h = new LinkedHashMap<String, String>();
			h.put("Accept", "application/json");
			if (contentType != null) h.put("Content-Type", contentType);
			if (config.getAuthType() == ConnectorConfig.AuthType.BASIC) h.put("Authorization", "Basic "+Base64.getEncoder().encodeToString((config.getUsername()+":"+config.getPassword()).getBytes(StandardCharsets.UTF_8)));
			else h.put("Authorization", "Bearer "+config.getApiKey());
			return new HttpJsonClient(config.getConnectTimeoutMs(), config.getReadTimeoutMs(), "EBX-Connectors/1.0 (cmis)").sendBytesWithoutRedirects(method, url.toString(), h, body, MAX_CONTENT+65536);
		}
		catch (final Exception e)
		{
			if (e instanceof ConnectorException) throw (ConnectorException)e;
			throw new ConnectorException("CMIS request failed: "+e.getClass().getSimpleName(), e);
		}
	}
	private static JsonNode json(final HttpJsonClient.Response r, final String op)throws ConnectorException
	{
		success(r, op);
		try
		{
			return r.getRawBody() == null || r.getRawBody().trim().isEmpty() ? Json.object() : Json.parse(r.getRawBody());
		}
		catch (final Exception e)
		{
			throw new ConnectorException("CMIS "+op+" returned invalid JSON");
		}
	}
	private static void success(final HttpJsonClient.Response r, final String op)throws ConnectorException
	{
		if (!r.isSuccess()) throw new ConnectorException("CMIS "+op+" failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
	}
	private static Map<String, String> query(final String k, final String v)
	{
		final Map<String, String> m = new LinkedHashMap<String, String>();
		m.put(k, v);
		return m;
	}
	private static String identifier(final Map<String, String> in, final String key)throws ConnectorException
	{
		final String v = required(in, key);
		if (v.length() > 512 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) throw new ConnectorException("CMIS "+key+" is invalid");
		return v;
	}
	private static String required(final Map<String, String> in, final String key)throws ConnectorException
	{
		final String v = in.get(key);
		if (v == null || v.trim().isEmpty()) throw new ConnectorException("CMIS "+key+" is required");
		return v.trim();
	}
	private static int bound(final String v, final int d)throws ConnectorException
	{
		try
		{
			final int n = v == null ? d : Integer.parseInt(v);
			if (n < 1 || n > MAX_RESULTS) throw new ConnectorException("CMIS max must be between 1 and 500");
			return n;
		}
		catch (final NumberFormatException e)
		{
			throw new ConnectorException("CMIS max must be an integer");
		}
	}
	private static int offset(final String v)throws ConnectorException
	{
		try
		{
			final int n = v == null ? 0 : Integer.parseInt(v);
			if (n < 0 || n > 100000) throw new ConnectorException("CMIS offset must be between 0 and 100000");
			return n;
		}
		catch (final NumberFormatException e)
		{
			throw new ConnectorException("CMIS offset must be an integer");
		}
	}
	private static byte[] form(final Map<String, String> values)
	{
		final StringBuilder b = new StringBuilder();
		for (final Map.Entry<String, String> e:values.entrySet())
		{
			if (b.length() > 0) b.append('&');
			b.append(AwsSigV4.encode(e.getKey())).append('=').append(AwsSigV4.encode(e.getValue()));
		}
		return b.toString().getBytes(StandardCharsets.UTF_8);
	}
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "CLOUD_STORAGE").description("CMIS 1.1 Browser Binding repository discovery, bounded folder browsing, metadata/content reads and governed document writes.").authentication(AuthenticationMethod.BASIC).authentication(AuthenticationMethod.BEARER_TOKEN).capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).apiVersion("CMIS 1.1 Browser Binding; live repository acceptance required").pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 100, MAX_RESULTS, "offset")).defaults(ConnectorDefaults.standard());
		for (final Operation o:OPS)
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	private static final class ByteArrayBuilder
	{
		private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		private final int max;
		ByteArrayBuilder(int m)
		{
			max = m;
		}
		void field(String b, String n, String v)throws ConnectorException
		{
			write("--"+b+"\r\nContent-Disposition: form-data; name=\""+n+"\"\r\n\r\n"+v+"\r\n");
		}
		void file(String b, String field, String name, String type, byte[] value)throws ConnectorException
		{
			write("--"+b+"\r\nContent-Disposition: form-data; name=\""+field+"\"; filename=\""+name.replace("\"", "")+"\"\r\nContent-Type: "+type+"\r\n\r\n");
			raw(value);
			write("\r\n");
		}
		void end(String b)throws ConnectorException
		{
			write("--"+b+"--\r\n");
		}
		void write(String s)throws ConnectorException
		{
			raw(s.getBytes(StandardCharsets.UTF_8));
		}
		void raw(byte[] v)throws ConnectorException
		{
			if (out.size()+v.length > max) throw new ConnectorException("CMIS multipart request exceeds limit");
			out.write(v, 0, v.length);
		}
		byte[] bytes()
		{
			return out.toByteArray();
		}
	}
}
