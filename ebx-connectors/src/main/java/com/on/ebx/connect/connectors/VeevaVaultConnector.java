package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;

/** Bounded read-only access to the documented Veeva Vault REST and VQL APIs. */
public final class VeevaVaultConnector extends AbstractRestConnector
{
	private static final List<Operation> OPS=Arrays.asList(
		Operation.search("objects","Discover Vault object definitions"),
		Operation.search("query","Run one bounded VQL page (input q; optional pageSize/pageOffset)","q"));
	public String getType(){return "veeva-vault";}
	public String getLabel(){return "Veeva Vault CRM";}
	public List<Operation> getOperations(){return OPS;}
	public ConnectorDescriptor getDescriptor(){ConnectorDescriptor.Builder b=ConnectorDescriptor.builder(getType(),getLabel(),"BUSINESS_APPS")
		.description("Read-only Vault object discovery and bounded VQL queries; writes and bulk jobs remain outside the acceptance boundary")
		.authentication(AuthenticationMethod.BEARER_TOKEN).authentication(AuthenticationMethod.API_KEY_HEADER)
		.direction(ConnectorDirection.INBOUND).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.DESCRIBE_OBJECT)
		.apiVersion("Veeva Vault REST API v25.3+");
		for (Operation o:OPS) b.operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(ConnectorDirection.INBOUND), java.util.Collections.<ConnectorCapability>emptySet()));
		return b.build();
	}
	protected String baseUrl()throws ConnectorException
	{
		String u = getConfig().getBaseUrl();
		if (u == null || !u.matches("https?://[^/]+.*")) throw new ConnectorException("Veeva Vault: set a valid Vault baseUrl");
		return u.replaceAll("/+$", "");
	}
	protected Map<String, String> headers()
	{
		Map<String, String> h = super.headers();
		if (getConfig().getAuthType() == ConnectorConfig.AuthType.API_KEY_HEADER)
		{
			h.remove(getConfig().getApiKeyHeader());
			h.put("Authorization", getConfig().getApiKey());
		}
		h.put("Accept", "application/json");
		return h;
	}
	private String version()
	{
		String v = getConfig().getProperty("apiVersion", "v25.3");
		return v.matches("v\\d{2}\\.\\d+") ? v : "v25.3";
	}
	public ConnectionTestResult testConnection(ConnectorContext c)throws ConnectorException
	{
		long s = System.nanoTime();
		requireSecret();
		HttpJsonClient.Response r = get("/api/"+version()+"/metadata/vobjects", java.util.Collections.singletonMap("limit", "1"));
		long ms = (System.nanoTime()-s)/1000000L;
		return r.isSuccess() ? ConnectionTestResult.success("Vault object metadata reachable", ms) : ConnectionTestResult.failure("Vault metadata returned HTTP "+r.getStatus(), ms);
	}
	public JsonNode execute(ConnectorContext c, String op, Map<String, String> in, JsonNode payload)throws ConnectorException
	{
		requireSecret();
		if ("objects".equals(op))
		{
			Map<String, String> q = new LinkedHashMap<String, String>();
			q.put("limit", String.valueOf(bound(in, "limit", 100, 1, 500)));
			return body(get("/api/"+version()+"/metadata/vobjects", q), "objects");
		}
		if ("query".equals(op))
		{
			String v = in == null ? null : in.get("q");
			if (v == null || v.trim().isEmpty()) throw new ConnectorException("Veeva Vault: q is required");
			if (v.length() > 50000) throw new ConnectorException("Veeva Vault: q exceeds 50000 characters");
			Map<String, String> q = new LinkedHashMap<String, String>();
			q.put("pagesize", String.valueOf(bound(in, "pageSize", 100, 1, 1000)));
			q.put("pageoffset", String.valueOf(bound(in, "pageOffset", 0, 0, 1000000)));
			Map<String, String> h = new LinkedHashMap<String, String>();
			h.put("Content-Type", "application/x-www-form-urlencoded");
			return body(sendRaw("POST", "/api/"+version()+"/query", q, "q="+HttpJsonClient.encode(v), h), "query");
		}
		throw new ConnectorException("Veeva Vault: unknown operation '"+op+"'");
	}
	private JsonNode body(HttpJsonClient.Response r, String what)throws ConnectorException
	{
		if (!r.isSuccess()) throw new ConnectorException("Veeva Vault "+what+" failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
		try
		{
			return Json.parse(r.getRawBody());
		}
		catch (Exception e)
		{
			throw new ConnectorException("Veeva Vault returned invalid JSON");
		}
	}
	private static int bound(Map<String, String> in, String k, int d, int min, int max)throws ConnectorException
	{
		String s = in == null ? null : in.get(k);
		try
		{
			int n = s == null ? d : Integer.parseInt(s);
			if (n < min || n > max) throw new Exception();
			return n;
		}
		catch (Exception e)
		{
			throw new ConnectorException("Veeva Vault: "+k+" must be between "+min+" and "+max);
		}
	}
}
