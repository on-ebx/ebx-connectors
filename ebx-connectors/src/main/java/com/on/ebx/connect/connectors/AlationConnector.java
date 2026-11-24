package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.*;

/** Read-only, bounded Alation catalog and Search API connector. */
public final class AlationConnector extends AbstractRestConnector
{
	private static final List<Operation> OPS=Arrays.asList(Operation.search("search","Search catalog objects with bounded limit/offset"),Operation.search("catalog","Read a bounded relational catalog page (kind datasource, schema, table or column)","kind"));
	public String getType()
	{
		return "alation";
	}
	public String getLabel()
	{
		return "Alation Data Catalog";
	}
	public List<Operation> getOperations()
	{
		return OPS;
	}
	public ConnectorDescriptor getDescriptor()
	{
		ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "DATA_PLATFORMS").description("Read-only catalog search and relational metadata; logical-metadata writes and lineage ingestion are not enabled").authentication(AuthenticationMethod.API_KEY_HEADER).direction(ConnectorDirection.INBOUND).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.DESCRIBE_OBJECT).apiVersion("Alation Search API v1 / Relational Catalog API");
		for (Operation o:OPS) b.operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(ConnectorDirection.INBOUND), java.util.Collections.<ConnectorCapability>emptySet()));
		return b.build();
	}
	protected String baseUrl()throws ConnectorException
	{
		String u = getConfig().getBaseUrl();
		if (u == null || !u.matches("https?://[^/]+.*")) throw new ConnectorException("Alation: set a valid instance baseUrl");
		return u.replaceAll("/+$", "");
	}
	protected Map<String, String> headers()
	{
		Map<String, String> h = super.headers();
		h.remove(getConfig().getApiKeyHeader());
		h.put("TOKEN", getConfig().getApiKey());
		h.put("Accept", "application/json");
		return h;
	}
	public JsonNode execute(ConnectorContext c, String op, Map<String, String> in, JsonNode p)throws ConnectorException
	{
		requireSecret();
		Map<String, String> q = new LinkedHashMap<String, String>();
		int limit = bound(in, "limit", 100, 1, 1000);
		q.put("limit", String.valueOf(limit));
		if ("search".equals(op))
		{
			q.put("offset", String.valueOf(bound(in, "offset", 0, 0, 1000000)));
			if (in != null && in.get("q") != null) q.put("q", in.get("q"));
			return body(get("/integration/v1/search/", q), op);
		}
		if ("catalog".equals(op))
		{
			String kind = in == null ? null : in.get("kind");
			if (kind == null || !kind.matches("datasource|schema|table|column")) throw new ConnectorException("Alation: kind must be datasource, schema, table or column");
			q.put("skip", String.valueOf(bound(in, "skip", 0, 0, 1000000)));
			return body(get("/catalog/"+kind+"/", q), op);
		}
		throw new ConnectorException("Alation: unknown operation '"+op+"'");
	}
	private JsonNode body(HttpJsonClient.Response r, String what)throws ConnectorException
	{
		if (!r.isSuccess()) throw new ConnectorException("Alation "+what+" failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
		try
		{
			return Json.parse(r.getRawBody());
		}
		catch (Exception e)
		{
			throw new ConnectorException("Alation returned invalid JSON");
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
			throw new ConnectorException("Alation: "+k+" must be between "+min+" and "+max);
		}
	}
}
