package com.on.ebx.connect.connectors;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
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

/** Azure Table Storage REST connector using a scoped SAS or Entra bearer token. */
public final class AzureTableStorageConnector implements Connector
{
	private static final int MAX_RESPONSE = 2 * 1024 * 1024;
	private static final int MAX_RESULTS = 1000;
	private static final int MAX_BATCH = 100;
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("tables", "Bounded table discovery with continuation token"),
		Operation.search("query", "Bounded entity query with OData filter and continuation token", "table"),
		Operation.read("get", "Entity by PartitionKey and RowKey", "table", "partitionKey", "rowKey"),
		Operation.write("insert", "Insert one entity", "table"),
		Operation.write("update", "Replace one entity selected by PartitionKey and RowKey", "table"),
		Operation.write("upsert", "Insert or replace one entity", "table"),
		Operation.write("delete", "Delete one entity by PartitionKey and RowKey", "table", "partitionKey", "rowKey"),
		Operation.write("batch", "Transactional batch of at most 100 entities in one partition", "table", "batchOperation"));

	private ConnectorConfig config;
	private final boolean allowLoopback;
	public AzureTableStorageConnector() { this(false); }
	AzureTableStorageConnector(final boolean pAllowLoopback) { this.allowLoopback = pAllowLoopback; }
	@Override public String getType() { return "azure-table"; }
	@Override public String getLabel() { return "Azure Table Storage"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }
	@Override public void configure(final ConnectorConfig pConfig) { this.config = pConfig; }

	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final java.util.ArrayList<ConfigurationValidationIssue> issues=new java.util.ArrayList<ConfigurationValidationIssue>();
		if(pConfig==null)return new ConfigurationValidationResult(issues);
		try
		{
			final URI u = URI.create(pConfig.getBaseUrl());
			final boolean loop = this.allowLoopback && ("localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost()));
			if(u.getHost()==null||(!loop&&(!"https".equalsIgnoreCase(u.getScheme())||!u.getHost().toLowerCase().matches("[a-z0-9-]+\\.table\\.core\\.windows\\.net"))))issues.add(new ConfigurationValidationIssue("baseUrl","Use https://ACCOUNT.table.core.windows.net"));
		}catch(final RuntimeException ex){issues.add(new ConfigurationValidationIssue("baseUrl","Use https://ACCOUNT.table.core.windows.net"));}
		if(pConfig.isPlaceholder(pConfig.getApiKey()))issues.add(new ConfigurationValidationIssue("apiKey","Set a scoped SAS token or Entra bearer token"));
		return new ConfigurationValidationResult(issues);
	}

	@Override public JsonNode execute(final ConnectorContext pContext,final String pOperation,final Map<String,String> pInputs,final JsonNode pPayload)throws ConnectorException
	{
		final Map<String,String> in=pInputs==null?Collections.<String,String>emptyMap():pInputs;
		if("tables".equals(pOperation))return collection("Tables",in,null);
		if("query".equals(pOperation))return collection(required(in,"table"),in,null);
		if ("get".equals(pOperation))
		{
			final HttpJsonClient.Response r = request("GET", entity(required(in, "table"), required(in, "partitionKey"), required(in, "rowKey")), null, null, null);
			if (r.getStatus() == 404) return null;
			return body(r, "get");
		}
		if("insert".equals(pOperation))return write("POST",required(in,"table"),pPayload,false,"insert");
		if("update".equals(pOperation))return write("PUT",required(in,"table"),pPayload,true,"update");
		if("upsert".equals(pOperation))return write("PUT",required(in,"table"),pPayload,true,"upsert");
		if ("delete".equals(pOperation))
		{
			final Map<String, String> h = new LinkedHashMap<String, String>();
			h.put("If-Match", in.get("etag") == null ? "*" : in.get("etag"));
			final HttpJsonClient.Response r = request("DELETE", entity(required(in, "table"), required(in, "partitionKey"), required(in, "rowKey")), null, h, null);
			success(r, "delete");
			return Json.object().put("deleted", true);
		}
		if("batch".equals(pOperation))return batch(required(in,"table"),required(in,"batchOperation"),pPayload);
		throw new ConnectorException("Azure Table: unknown operation '"+pOperation+"'");
	}

	private JsonNode collection(final String pPath,final Map<String,String> pInputs,final JsonNode ignored)throws ConnectorException
	{
		if(!"Tables".equals(pPath))safeTable(pPath);
		final int max = bound(pInputs.get("max"), 1, MAX_RESULTS, 100);
		final Map<String, String> q = new LinkedHashMap<String, String>();
		q.put("$top", String.valueOf(max));
		if (pInputs.get("filter") != null) q.put("$filter", pInputs.get("filter"));
		if (pInputs.get("select") != null) q.put("$select", pInputs.get("select"));
		final String token = pInputs.get("continuationToken");
		if (token != null && !token.isEmpty())
		{
			final JsonNode t = parseToken(token);
			for (final String n:new String[] {"NextTableName", "NextPartitionKey", "NextRowKey"}) if (t.has(n)) q.put(n, t.get(n).asText());
		}
		final HttpJsonClient.Response r = request("GET", pPath, q, null, null);
		final JsonNode json = body(r, "query");
		final ArrayNode values = json != null && json.path("value").isArray() ? (ArrayNode)json.path("value") : Json.mapper().createArrayNode();
		final ObjectNode out = Json.object();
		final ArrayNode rows = out.putArray("results");
		for (int i = 0; i < values.size() && i < max; i++) rows.add(values.get(i));
		out.put("count", rows.size());
		final ObjectNode next = Json.object();
		for (final String n:new String[] {"NextTableName", "NextPartitionKey", "NextRowKey"})
		{
			final String v = r.getHeader("x-ms-continuation-"+n);
			if (v != null) next.put(n, v);
		}
		if (next.size() > 0) out.put("continuationToken", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(next).getBytes(StandardCharsets.UTF_8)));
		return out;
	}

	private JsonNode write(final String pMethod,final String pTable,final JsonNode pPayload,final boolean pKeyed,final String pAction)throws ConnectorException
	{
		safeTable(pTable);
		entityPayload(pPayload);
		final String path = pKeyed ? entity(pTable, pPayload.path("PartitionKey").asText(), pPayload.path("RowKey").asText()) : pTable;
		final Map<String, String> h = new LinkedHashMap<String, String>();
		if ("update".equals(pAction)) h.put("If-Match", "*");
		final HttpJsonClient.Response r = request(pMethod, path, null, h, Json.write(pPayload).getBytes(StandardCharsets.UTF_8));
		success(r, pAction);
		return Json.object().put("written", true);
	}

	private JsonNode batch(final String pTable,final String pOperation,final JsonNode pPayload)throws ConnectorException
	{
		safeTable(pTable);
		if(pPayload==null||!pPayload.isArray()||pPayload.size()==0||pPayload.size()>MAX_BATCH)throw new ConnectorException("Azure Table batch requires 1 to 100 entities");
		if(!("insert".equals(pOperation)||"update".equals(pOperation)||"upsert".equals(pOperation)||"delete".equals(pOperation)))throw new ConnectorException("Azure Table batchOperation must be insert, update, upsert, or delete");
		String partition = null;
		for (final JsonNode e:pPayload)
		{
			entityPayload(e);
			if (partition == null) partition = e.path("PartitionKey").asText();
			else if (!partition.equals(e.path("PartitionKey").asText())) throw new ConnectorException("Azure Table transactional batch must use one PartitionKey");
		}
		final String boundary = "batch_ebx", change = "changeset_ebx";
		final StringBuilder b = new StringBuilder("--").append(boundary).append("\r\nContent-Type: multipart/mixed; boundary=").append(change).append("\r\n\r\n");
		int id = 1;
		for (final JsonNode e:pPayload)
		{
			final boolean insert = "insert".equals(pOperation);
			final boolean delete = "delete".equals(pOperation);
			final String method = insert ? "POST" : delete ? "DELETE" : "PUT";
			final String path = insert ? pTable : entity(pTable, e.path("PartitionKey").asText(), e.path("RowKey").asText());
			b.append("--").append(change).append("\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\nContent-ID: ").append(id++).append("\r\n\r\n").append(method).append(' ').append('/').append(path).append(" HTTP/1.1\r\nAccept: application/json\r\n");
			if ("update".equals(pOperation) || delete) b.append("If-Match: *\r\n");
			if (!delete) b.append("Content-Type: application/json\r\n\r\n").append(Json.write(e));
			b.append("\r\n");
		}
		b.append("--").append(change).append("--\r\n--").append(boundary).append("--\r\n");
		final Map<String, String> h = new LinkedHashMap<String, String>();
		h.put("Content-Type", "multipart/mixed; boundary="+boundary);
		final HttpJsonClient.Response r = request("POST", "$batch", null, h, b.toString().getBytes(StandardCharsets.UTF_8));
		success(r, "batch");
		return Json.object().put("processed", pPayload.size()).put("partitionKey", partition);
	}

	private HttpJsonClient.Response request(final String method,final String path,final Map<String,String> query,final Map<String,String> extra,final byte[] body)throws ConnectorException
	{
		if (this.config == null) throw new ConnectorException("Azure Table is not configured");
		final StringBuilder url = new StringBuilder(this.config.getBaseUrl().replaceAll("/+$", "")).append('/').append(path);
		char sep = '?';
		if (query != null) for (final Map.Entry<String, String> e:query.entrySet())
		{
			url.append(sep).append(AwsSigV4.encode(e.getKey())).append('=').append(AwsSigV4.encode(e.getValue()));
			sep = '&';
		}
		final boolean bearer = this.config.getAuthType() == ConnectorConfig.AuthType.BEARER;
		if (!bearer)
		{
			url.append(sep).append(this.config.getApiKey().replaceFirst("^\\?", ""));
		}
		final Map<String, String> h = new LinkedHashMap<String, String>();
		h.put("Accept", "application/json;odata=nometadata");
		h.put("Content-Type", "application/json");
		h.put("x-ms-version", this.config.getProperty("apiVersion", "2019-02-02"));
		if (bearer) h.put("Authorization", "Bearer "+this.config.getApiKey());
		if (extra != null) h.putAll(extra);
		try
		{
			final HttpJsonClient.Response r = new HttpJsonClient(this.config.getConnectTimeoutMs(), this.config.getReadTimeoutMs(), "EBX-Connectors/1.0 (azure-table)").sendBytesWithoutRedirects(method, url.toString(), h, body, MAX_RESPONSE);
			if (r.getStatus() >= 300 && r.getStatus() < 400) throw new ConnectorException("Azure Table refused redirect (HTTP "+r.getStatus()+")", r.getStatus(), null);
			return r;
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Azure Table request failed", ex);
		}
	}

	private static JsonNode body(final HttpJsonClient.Response r, final String action)throws ConnectorException
	{
		success(r, action);
		if (r.getRawBody() == null || r.getRawBody().trim().isEmpty()) return Json.object();
		try
		{
			return Json.parse(r.getRawBody());
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Azure Table "+action+" returned invalid JSON");
		}
	}
	private static void success(final HttpJsonClient.Response r,final String action)throws ConnectorException{if(!r.isSuccess())throw new ConnectorException("Azure Table "+action+" failed (HTTP "+r.getStatus()+")",r.getStatus(),null);}
	private static String entity(final String t, final String p, final String r)throws ConnectorException
	{
		safeTable(t);
		return t+"(PartitionKey='"+key(p)+"',RowKey='"+key(r)+"')";
	}
	private static String key(final String v)throws ConnectorException
	{
		if (v == null || v.isEmpty() || v.length() > 1024 || v.indexOf('/') >= 0 || v.indexOf('\\') >= 0 || v.indexOf('#') >= 0 || v.indexOf('?') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) throw new ConnectorException("Azure Table key is invalid");
		return v.replace("'", "''");
	}
	private static void safeTable(final String t)throws ConnectorException{if(t==null||!t.matches("[A-Za-z][A-Za-z0-9]{2,62}"))throw new ConnectorException("Azure Table name is invalid");}
	private static void entityPayload(final JsonNode e)throws ConnectorException
	{
		if (e == null || !e.isObject()) throw new ConnectorException("Azure Table entity object is required");
		key(e.path("PartitionKey").asText(null));
		key(e.path("RowKey").asText(null));
	}
	private static String required(final Map<String, String> in, final String n)throws ConnectorException
	{
		final String v = in.get(n);
		if (v == null || v.trim().isEmpty()) throw new ConnectorException("Azure Table input "+n+" is required");
		return v.trim();
	}
	private static int bound(final String v, final int min, final int max, final int d)
	{
		try
		{
			return Math.max(min, Math.min(max, Integer.parseInt(v)));
		}
		catch (final RuntimeException ex)
		{
			return d;
		}
	}
	private static JsonNode parseToken(final String token)throws ConnectorException
	{
		try
		{
			return Json.parse(new String(java.util.Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Azure Table continuation token is invalid");
		}
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "CLOUD_STORAGE").description("Azure Table Storage entities with bounded OData reads, PartitionKey/RowKey writes, and single-partition transactional batches.").authentication(AuthenticationMethod.API_KEY_HEADER).authentication(AuthenticationMethod.BEARER_TOKEN).capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.TRANSACTIONS).apiVersion("Azure Table REST 2019-02-02").pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.TOKEN, 100, MAX_RESULTS, "continuationToken")).defaults(ConnectorDefaults.standard()).configurationProperty(ConfigurationPropertyDefinition.builder("apiVersion", ConfigurationPropertyType.STRING).required(false).build());
		for (final Operation o:OPERATIONS)
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
}
