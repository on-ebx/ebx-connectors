package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.*;

/** Microsoft Purview Data Map data-plane discovery connector. */
public final class MicrosoftPurviewConnector extends AbstractRestConnector
{
	private static final String API="2023-09-01";
	private static final List<Operation> OPS=Arrays.asList(Operation.search("search","Bounded Data Map search; accepts keywords, continuationToken and optional filter payload"),Operation.read("entity","Read one Atlas entity by GUID","guid"),Operation.search("types","Discover Atlas type definitions"));
	public String getType()
	{
		return "microsoft-purview";
	}
	public String getLabel()
	{
		return "Microsoft Purview Data Map";
	}
	public List<Operation> getOperations()
	{
		return OPS;
	}
	public ConnectorDescriptor getDescriptor()
	{
		ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "DATA_PLATFORMS").description("Read-only Purview Data Map search, entity and type discovery; Unified Catalog preview and mutations are excluded").authentication(AuthenticationMethod.BEARER_TOKEN).authentication(AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS).direction(ConnectorDirection.INBOUND).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.DESCRIBE_OBJECT).apiVersion(API);
		for (Operation o:OPS) b.operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(ConnectorDirection.INBOUND), java.util.Collections.<ConnectorCapability>emptySet()));
		return b.build();
	}
	public void configure(ConnectorConfig c)
	{
		if (c != null && c.getAuthType() == ConnectorConfig.AuthType.OAUTH2)
		{
			String t = c.getProperty("tenantId", null);
			if (t != null && c.getProperty("oauth.tokenUrl", null) == null) c.setProperty("oauth.tokenUrl", "https://login.microsoftonline.com/"+t+"/oauth2/v2.0/token");
			if (c.getProperty("oauth.scope", null) == null) c.setProperty("oauth.scope", "https://purview.azure.net/.default");
			if (c.getProperty("oauth.clientId", null) == null) c.setProperty("oauth.clientId", c.getProperty("clientId", null));
		}
		super.configure(c);
	}
	protected String baseUrl()throws ConnectorException
	{
		String u = getConfig().getBaseUrl();
		if (u == null || !u.matches("https?://[^/]+.*")) throw new ConnectorException("Microsoft Purview: set a valid account endpoint");
		return u.replaceAll("/+$", "");
	}
	public JsonNode execute(ConnectorContext c, String op, Map<String, String> in, JsonNode p)throws ConnectorException
	{
		requireSecret();
		if ("search".equals(op))
		{
			int limit = bound(in, "limit", 50, 1, 1000);
			ObjectNode b = p != null && p.isObject() ? ((ObjectNode)p).deepCopy() : Json.object();
			b.put("limit", limit);
			if (in != null && in.get("keywords") != null) b.put("keywords", in.get("keywords"));
			if (in != null && in.get("continuationToken") != null) b.put("continuationToken", in.get("continuationToken"));
			return body(send("POST", "/datamap/api/search/query", api(), b), op);
		}
		if ("entity".equals(op))
		{
			String g = in == null ? null : in.get("guid");
			if (g == null || !g.matches("[A-Za-z0-9-]{1,128}")) throw new ConnectorException("Microsoft Purview: valid guid is required");
			return body(get("/datamap/api/atlas/v2/entity/guid/"+segment(g), null), op);
		}
		if ("types".equals(op)) return body(get("/datamap/api/atlas/v2/types/typedefs", null), op);
		throw new ConnectorException("Microsoft Purview: unknown operation '"+op+"'");
	}
	private Map<String, String> api()
	{
		Map<String, String> q = new LinkedHashMap<String, String>();
		q.put("api-version", API);
		return q;
	}
	private JsonNode body(HttpJsonClient.Response r, String what)throws ConnectorException
	{
		if (!r.isSuccess()) throw new ConnectorException("Microsoft Purview "+what+" failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
		try
		{
			return Json.parse(r.getRawBody());
		}
		catch (Exception e)
		{
			throw new ConnectorException("Microsoft Purview returned invalid JSON");
		}
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
			throw new ConnectorException("Microsoft Purview: "+k+" must be between "+min+" and "+max);
		}
	}
}
