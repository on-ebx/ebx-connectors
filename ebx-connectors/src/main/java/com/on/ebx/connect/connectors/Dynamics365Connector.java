package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.ODataSupport;
import com.on.ebx.connect.core.ODataBatchAdapter;
import com.on.ebx.connect.spi.BatchRequest;
import com.on.ebx.connect.spi.BatchResult;
import com.on.ebx.connect.spi.ConnectorBatchExecutor;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;

/**
 * Microsoft Dynamics 365 / Dataverse Web API (OData v4). {@code baseUrl} = environment URL
 * ({@code https://org.crm4.dynamics.com}); OAuth 2.0 client credentials on Microsoft Entra:
 * properties {@code tenantId}, {@code clientId}, {@code apiKey} = client secret,
 * {@code apiVersion} (v9.2). Inputs: {@code object} = entity set (accounts, contacts...),
 * {@code id} = GUID, {@code filter} = OData $filter, {@code select}, {@code expand};
 * {@code upsert} with {@code lookupField} + {@code value} uses an alternate key.
 */
public final class Dynamics365Connector extends AbstractCrudRestConnector
{
	private String token;
	private long tokenExpiry;

	@Override
	public String getType()
	{
		return "dynamics365";
	}

	@Override
	public String getLabel()
	{
		return "Microsoft Dynamics 365 (Dataverse)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("Microsoft Dataverse OData v4 connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS).capability(com.on.ebx.connect.spi.ConnectorCapability.BULK_READ).capability(com.on.ebx.connect.spi.ConnectorCapability.BULK_WRITE).apiVersion("v9.2")
			.configurationProperty(prop("tenantId", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null, true))
			.configurationProperty(prop("clientId", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null, true))
			.configurationProperty(prop("apiVersion", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, "v9.2", false))
			.configurationProperty(prop("max.rows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, Integer.valueOf(200), false));
		return operations(b).build();
	}
	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder operations(final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b)
	{
		for (final com.on.ebx.connect.spi.Operation o : getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == com.on.ebx.connect.spi.Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b;
	}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition prop(final String n, final com.on.ebx.connect.spi.ConfigurationPropertyType t, final Object v, final boolean r) { return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n, t).required(r).defaultValue(v).build(); }

	@Override
	protected String baseUrl() throws ConnectorException
	{
		return this.getConfig().getBaseUrl().replaceAll("/+$", "") + "/api/data/" + this.getConfig().getProperty("apiVersion", "v9.2");
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Accept", "application/json");
		headers.put("OData-MaxVersion", "4.0");
		headers.put("OData-Version", "4.0");
		headers.put("Prefer", "return=representation");
		if (this.token != null)
		{
			headers.put("Authorization", "Bearer " + this.token);
		}
		return headers;
	}

	private synchronized void authenticate() throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		if (this.token != null && System.currentTimeMillis() < this.tokenExpiry)
		{
			return;
		}
		final String tenant = cfg.getProperty("tenantId", null);
		final String clientId = cfg.getProperty("clientId", null);
		if (tenant == null || clientId == null || cfg.isPlaceholder(cfg.getApiKey()))
		{
			throw new ConnectorException("Dynamics 365: set properties tenantId and clientId, and the client secret in apiKey");
		}
		final HttpJsonClient client = new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(), "EBX-Connectors/1.0 (dynamics)");
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/x-www-form-urlencoded");
		final String scope = cfg.getBaseUrl().replaceAll("/+$", "") + "/.default";
		try
		{
			final HttpJsonClient.Response response = client.send("POST", "https://login.microsoftonline.com/" + HttpJsonClient.encode(tenant) + "/oauth2/v2.0/token",
				headers, "grant_type=client_credentials&client_id=" + HttpJsonClient.encode(clientId) + "&client_secret=" + HttpJsonClient.encode(cfg.getApiKey())
						+ "&scope=" + HttpJsonClient.encode(scope));
			final JsonNode token = Json.at(response.getBody(), "access_token");
			if (!response.isSuccess() || token == null)
			{
				throw new ConnectorException("Dynamics 365 login failed (HTTP " + response.getStatus() + "): "
						+ String.valueOf(Json.at(response.getBody(), "error_description")), response.getStatus(), null);
			}
			this.token = token.asText();
			final JsonNode expires = Json.at(response.getBody(), "expires_in");
			this.tokenExpiry = System.currentTimeMillis() + (expires == null ? 3000L : Math.max(60L, expires.asLong() - 120L)) * 1000L;
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Dynamics 365 login failed: " + ex.getMessage(), ex);
		}
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.authenticate();
		final String set = AbstractCrudRestConnector.safeName(AbstractRestConnector.require(pInputs, "object"));
		final Map<String, String> query = ODataSupport.query(ODataSupport.Version.V4, pInputs, false,
			this.getConfig().getProperty("max.rows", "200"), null);
		if ("get".equals(pOperation))
		{
			final JsonNode entity = this.bodyOrNull(this.get("/" + set + "(" + Dynamics365Connector.key(AbstractRestConnector.require(pInputs, "id")) + ")", query), "get");
			return ODataSupport.normalise(entity);
		}
		if ("query".equals(pOperation))
		{
			query.putAll(ODataSupport.query(ODataSupport.Version.V4, pInputs, true, this.getConfig().getProperty("max.rows", "200"), null));
			final String deltaLink = pInputs.get("deltaLink");
			final JsonNode reply = deltaLink == null || deltaLink.trim().isEmpty()
				? this.bodyOrNull(this.get("/" + set, query), "query")
				: this.bodyOrNull(this.getAbsolute(ODataSupport.continuationUrl(this.baseUrl(), deltaLink), null), "delta query");
			final int maximum = bounded(pInputs.get("max"), 1, bounded(this.getConfig().getProperty("max.rows", "200"), 1, 10000));
			return ODataSupport.collectPages(this.baseUrl(), reply, bounded(this.getConfig().getProperty("max.pages", "20"), 1, 100), maximum,
				new ODataSupport.PageFetcher()
				{
					public JsonNode get(final String pUrl) throws ConnectorException
					{
						return Dynamics365Connector.this.bodyOrNull(Dynamics365Connector.this.getAbsolute(pUrl, null), "OData continuation");
					}
				});
		}
		if ("create".equals(pOperation))
		{
			return ODataSupport.normalise(this.bodyOrNull(this.send("POST", "/" + set, null, pPayload == null ? Json.object() : pPayload), "create"));
		}
		if ("update".equals(pOperation))
		{
			final HttpJsonClient.Response response = this.send("PATCH", "/" + set + "(" + Dynamics365Connector.key(AbstractRestConnector.require(pInputs, "id")) + ")", null,
				pPayload == null ? Json.object() : pPayload);
			return response.getStatus() == 204 ? Json.object() : ODataSupport.normalise(this.bodyOrNull(response, "update"));
		}
		if ("upsert".equals(pOperation))
		{
			final String id = pInputs.get("id");
			final String key = id != null && !id.trim().isEmpty() ? Dynamics365Connector.key(id)
					: AbstractCrudRestConnector.safeName(AbstractRestConnector.require(pInputs, "lookupField")) + "='"
							+ AbstractRestConnector.require(pInputs, "value").replace("'", "''") + "'";
			final HttpJsonClient.Response response = this.send("PATCH", "/" + set + "(" + key + ")", null, pPayload == null ? Json.object() : pPayload);
			return response.getStatus() == 204 ? Json.object() : ODataSupport.normalise(this.bodyOrNull(response, "upsert"));
		}
		throw new ConnectorException("Dynamics 365: unknown operation '" + pOperation + "'");
	}

	@Override
	public BatchResult executeBatch(final ConnectorContext pContext, final BatchRequest pRequest) throws ConnectorException
	{
		ConnectorBatchExecutor.validate(this, pRequest);
		this.requireBatchLimits(pRequest);
		this.authenticate();
		final List<ODataSupport.BatchOperation> operations = new java.util.ArrayList<ODataSupport.BatchOperation>();
		for (final BatchRequest.Item item : pRequest.getItems())
		{
			final Map<String, String> inputs = item.getInputs();
			final String set = AbstractCrudRestConnector.safeName(AbstractRestConnector.require(inputs, "object"));
			final String name = item.getOperation();
			String method, path;
			JsonNode body = null;
			if ("get".equals(name))
			{
				method = "GET";
				path = ODataBatchAdapter.relativeUrl(set + "(" + key(AbstractRestConnector.require(inputs, "id")) + ")",
					ODataSupport.query(ODataSupport.Version.V4, inputs, false, this.getConfig().getProperty("max.rows", "200"), null));
			}
			else if ("query".equals(name))
			{
				method = "GET";
				path = ODataBatchAdapter.relativeUrl(set, ODataSupport.query(ODataSupport.Version.V4, inputs, true,
					this.getConfig().getProperty("max.rows", "200"), null));
			}
			else if ("create".equals(name))
			{
				method = "POST";
				path = set;
				body = item.getPayload();
			}
			else if ("update".equals(name))
			{
				method = "PATCH";
				path = set + "(" + key(AbstractRestConnector.require(inputs, "id")) + ")";
				body = item.getPayload();
			}
			else if ("upsert".equals(name))
			{
				method = "PATCH";
				body = item.getPayload();
				final String id = inputs.get("id");
				final String predicate = id != null && !id.trim().isEmpty() ? key(id)
					: AbstractCrudRestConnector.safeName(AbstractRestConnector.require(inputs, "lookupField")) + "='" + AbstractRestConnector.require(inputs, "value").replace("'", "''") + "'";
				path = set + "(" + predicate + ")";
			}
			else throw new ConnectorException("Dynamics 365: operation '" + name + "' cannot be batched");
			operations.add(new ODataSupport.BatchOperation(item.getId(), method, path, java.util.Collections.<String, String>emptyMap(), body, null));
		}
		final JsonNode wire = ODataSupport.v4Batch(operations);
		this.requireWireBound(Json.write(wire), pRequest.getMaximumBytes());
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/json");
		final HttpJsonClient.Response response = this.sendBytesBounded("POST", "/$batch", null,
			Json.write(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8), headers, pRequest.getMaximumBytes());
		if (!response.isSuccess()) this.bodyOrNull(response, "OData batch");
		return ODataBatchAdapter.results(pRequest, ODataSupport.v4BatchResponse(response.getBody()));
	}

	private void requireBatchLimits(final BatchRequest pRequest) throws ConnectorException
	{
		final int maximum = bounded(this.getConfig().getProperty("batch.max.items", "50"), 1, BatchRequest.HARD_MAX_ITEMS);
		if (pRequest.getItems().size() > maximum) throw new ConnectorException("Dynamics 365 batch exceeds configured item limit " + maximum);
	}
	private void requireWireBound(final String pWire, final int pMaximum) throws ConnectorException
	{
		try { if (pWire.getBytes("UTF-8").length > pMaximum) throw new ConnectorException("Dynamics 365 batch exceeds byte limit " + pMaximum); }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
	}

	private static String key(final String pId)
	{
		final String id = pId.trim();
		return id.matches("[0-9a-fA-F-]{36}") ? id : id.contains("=") ? id : "'" + id.replace("'", "''") + "'";
	}
	private static int bounded(final String pValue, final int pMinimum, final int pMaximum)
	{
		try
		{
			return Math.max(pMinimum, Math.min(pMaximum, Integer.parseInt(pValue)));
		}
		catch (final RuntimeException ex)
		{
			return pMaximum;
		}
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new java.util.ArrayList<FieldInfo>();
		final String set = pInputs == null ? null : pInputs.get("object");
		if (set == null || set.trim().isEmpty())
		{
			return fields;
		}
		this.authenticate();
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("$filter", "EntitySetName eq '" + AbstractCrudRestConnector.safeName(set) + "'");
		query.put("$select", "LogicalName");
		final JsonNode definition = Json.at(this.bodyOrNull(this.get("/EntityDefinitions", query), "EntityDefinitions"), "value[0].LogicalName");
		if (definition == null)
		{
			return fields;
		}
		final Map<String, String> attributes = new LinkedHashMap<String, String>();
		attributes.put("$select", "LogicalName,AttributeType,DisplayName");
		final JsonNode list = Json.at(this.bodyOrNull(this.get("/EntityDefinitions(LogicalName='" + definition.asText() + "')/Attributes", attributes), "Attributes"), "value");
		final String prefix = "query".equals(pOperation) ? "results[0]." : "";
		if (list != null)
		{
			for (final JsonNode attribute : list)
			{
				final JsonNode label = Json.at(attribute, "DisplayName.UserLocalizedLabel.Label");
				fields.add(FieldInfo.of(prefix + attribute.get("LogicalName").asText(), label == null ? attribute.get("LogicalName").asText() : label.asText(),
					attribute.get("AttributeType") == null ? "" : attribute.get("AttributeType").asText()));
			}
		}
		return fields;
	}
}
