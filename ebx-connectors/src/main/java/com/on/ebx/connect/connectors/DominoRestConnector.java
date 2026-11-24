package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.ArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.*;

/** Bounded, read-only HCL Domino REST API basis connector; no Notes.jar dependency. */
public final class DominoRestConnector extends AbstractRestConnector
{
	private static final List<Operation> OPS=Arrays.asList(Operation.search("lists","Discover exposed views/folders for a configured scope"),Operation.search("query","Run a bounded DQL query against an exposed scope","query"));
	private final boolean loopbackFixture;
	public DominoRestConnector()
	{
		this(false);
	}
	DominoRestConnector(boolean pLoopbackFixture)
	{
		this.loopbackFixture = pLoopbackFixture;
	}
	public String getType()
	{
		return "domino-rest";
	}
	public String getLabel()
	{
		return "HCL Domino REST API";
	}
	public List<Operation> getOperations()
	{
		return OPS;
	}
	public ConfigurationValidationResult validateConfiguration(ConnectorConfig c)
	{
		List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>();
		if (c == null) return new ConfigurationValidationResult(issues);
		try
		{
			URI u = URI.create(c.getBaseUrl());
			boolean local = loopbackFixture && ("127.0.0.1".equals(u.getHost()) || "localhost".equalsIgnoreCase(u.getHost()));
			if (u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null || (!local && !"https".equalsIgnoreCase(u.getScheme()))) issues.add(new ConfigurationValidationIssue("baseUrl", "Use an HTTPS Domino REST API endpoint"));
		}
		catch (Exception e)
		{
			issues.add(new ConfigurationValidationIssue("baseUrl", "Use a valid Domino REST API endpoint"));
		}
		if (c.getAuthType() != ConnectorConfig.AuthType.BEARER) issues.add(new ConfigurationValidationIssue("authType", "Use a bearer token"));
		if (c.isPlaceholder(c.getApiKey())) issues.add(new ConfigurationValidationIssue("apiKey", "Set the Domino REST API bearer token"));
		String scope = c.getProperty("scope", null);
		if (scope == null || !scope.matches("[A-Za-z0-9._-]{1,128}")) issues.add(new ConfigurationValidationIssue("scope", "Set an exposed Domino REST API scope"));
		return new ConfigurationValidationResult(issues);
	}
	public ConnectorDescriptor getDescriptor()
	{
		ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS").description("Read-only Domino REST API Basis discovery and bounded DQL; avoids proprietary Notes.jar runtime coupling").authentication(AuthenticationMethod.BEARER_TOKEN).direction(ConnectorDirection.INBOUND).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.DESCRIBE_OBJECT).apiVersion("Domino REST API Basis 1.3.7");
		for (Operation o:OPS) b.operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(ConnectorDirection.INBOUND), java.util.Collections.<ConnectorCapability>emptySet()));
		return b.build();
	}
	protected String baseUrl()throws ConnectorException
	{
		if (!validateConfiguration(getConfig()).isValid()) throw new ConnectorException("Domino REST configuration is invalid");
		String u = getConfig().getBaseUrl().replaceAll("/+$", "");
		return u.endsWith("/api/v1") ? u : u+"/api/v1";
	}
	public JsonNode execute(ConnectorContext c, String op, Map<String, String> in, JsonNode payload)throws ConnectorException
	{
		requireSecret();
		String scope = value(in, "scope", getConfig().getProperty("scope", null));
		if (scope == null || !scope.matches("[A-Za-z0-9._-]{1,128}")) throw new ConnectorException("Domino REST: a valid scope is required");
		Map<String, String> q = new LinkedHashMap<String, String>();
		q.put("dataSource", scope);
		if ("lists".equals(op))
		{
			String type = value(in, "type", "all");
			if (!type.matches("all|views|folders")) throw new ConnectorException("Domino REST: type must be all, views or folders");
			q.put("type", type);
			q.put("columns", "false");
			if (in != null && in.get("filter") != null) q.put("filter", limited(in.get("filter"), 256, "filter"));
			return body(get("/lists", q), op);
		}
		if ("query".equals(op))
		{
			String dql = value(in, "query", null);
			if (dql == null || dql.trim().isEmpty()) throw new ConnectorException("Domino REST: query is required");
			limited(dql, 4096, "query");
			q.put("action", "execute");
			q.put("count", String.valueOf(bound(in, "count", 100, 1, 1000)));
			q.put("start", String.valueOf(bound(in, "start", 0, 0, 1000000)));
			ObjectNode b = payload != null && payload.isObject() ? ((ObjectNode)payload).deepCopy() : Json.object();
			b.put("query", dql);
			b.put("mode", "dql");
			b.put("timeoutSecs", bound(in, "timeoutSecs", 30, 1, 120));
			b.put("maxScanDocs", bound(in, "maxScanDocs", 10000, 1, 100000));
			b.put("maxScanEntries", bound(in, "maxScanEntries", 10000, 1, 100000));
			return body(send("POST", "/query", q, b), op);
		}
		throw new ConnectorException("Domino REST: unknown operation '"+op+"'");
	}
	private JsonNode body(HttpJsonClient.Response r, String what)throws ConnectorException
	{
		if (!r.isSuccess()) throw new ConnectorException("Domino REST "+what+" failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
		try
		{
			return Json.parse(r.getRawBody());
		}
		catch (Exception e)
		{
			throw new ConnectorException("Domino REST returned invalid JSON");
		}
	}
	private static String value(Map<String,String> in,String k,String d){return in!=null&&in.get(k)!=null?in.get(k):d;}
	private static String limited(String s, int max, String n)throws ConnectorException
	{
		if (s.length() > max) throw new ConnectorException("Domino REST: "+n+" exceeds "+max+" characters");
		return s;
	}
	private static int bound(Map<String, String> in, String k, int d, int min, int max)throws ConnectorException
	{
		try
		{
			int n = in == null || in.get(k) == null ? d : Integer.parseInt(in.get(k));
			if (n < min || n > max) throw new Exception();
			return n;
		}
		catch (Exception e)
		{
			throw new ConnectorException("Domino REST: "+k+" must be between "+min+" and "+max);
		}
	}
}
