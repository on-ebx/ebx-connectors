package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Workday: REST API ({@code /ccx/api/v1/{tenant}}) with an OAuth 2.0 refresh token of an API
 * client, and Reports-as-a-Service (RaaS) with the integration user (BASIC).
 * <p>
 * {@code baseUrl} = {@code https://wd3-impl-services1.workday.com}; property {@code tenant};
 * OAuth: properties {@code clientId}, {@code refreshToken}, {@code apiKey} = client secret.
 * Operations: {@code workers} (search), {@code worker} (id), {@code resource} (any REST resource
 * path such as {@code /organizations}), {@code raas} (report path
 * {@code /ccx/service/customreport2/{tenant}/{owner}/{report}}, JSON {@code Report_Entry[]}).
 * REST collections are read page by page ({@code limit} 100 and {@code offset}) up to input {@code max}
 * (default 100, ceiling property {@code max.rows}, 1000 by default, 10000 at most); a 401 refreshes
 * the token once.
 */
public final class WorkdayConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("workers", "Workers matching a search (input search, max)", new String[0]),
		Operation.read("worker", "One worker by Workday id", "id"),
		Operation.search("resource", "Any REST resource collection (input path, e.g. /organizations, optional query)", "path"),
		Operation.search("raas", "Reports-as-a-Service report (input report = report path, extra inputs as prompts)", "report"));

	private String token;
	private long tokenExpiry;

	@Override
	public String getType()
	{
		return "workday";
	}

	@Override
	public String getLabel()
	{
		return "Workday (REST and RaaS)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("Workday REST and Reports-as-a-Service connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.OAUTH2_REFRESH_TOKEN).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC)
			.configurationProperty(prop("tenant", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null, true))
			.configurationProperty(prop("clientId", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null, true))
			.configurationProperty(prop("refreshToken", com.on.ebx.connect.spi.ConfigurationPropertyType.SECRET, null, true))
			.configurationProperty(prop("max.rows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, Integer.valueOf(1000), false));
		return operations(b).build();
	}
	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder operations(final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b)
	{
		for (final Operation o : getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b;
	}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition prop(final String n, final com.on.ebx.connect.spi.ConfigurationPropertyType t, final Object v, final boolean r) { return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n, t).required(r).defaultValue(v).build(); }

	@Override
	public List<Operation> getOperations()
	{
		return WorkdayConnector.OPERATIONS;
	}

	private synchronized void authenticate() throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		if (this.token != null && System.currentTimeMillis() < this.tokenExpiry)
		{
			return;
		}
		final String clientId = cfg.getProperty("clientId", null);
		final String refresh = cfg.getProperty("refreshToken", null);
		if (clientId == null || refresh == null || cfg.isPlaceholder(cfg.getApiKey()))
		{
			throw new ConnectorException("Workday REST: set properties clientId, refreshToken and the client secret in apiKey");
		}
		final HttpJsonClient client = new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(), "EBX-Connectors/1.0 (workday)");
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/x-www-form-urlencoded");
		headers.put("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
			(clientId + ":" + cfg.getApiKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		try
		{
			final HttpJsonClient.Response response = client.send("POST",
				HttpJsonClient.buildUrl(cfg.getBaseUrl(), "/ccx/oauth2/" + HttpJsonClient.encode(cfg.getProperty("tenant", "")) + "/token", null),
				headers, "grant_type=refresh_token&refresh_token=" + HttpJsonClient.encode(refresh));
			final JsonNode token = Json.at(response.getBody(), "access_token");
			if (!response.isSuccess() || token == null)
			{
				throw new ConnectorException("Workday token failed (HTTP " + response.getStatus() + "): " + response.getRawBody(), response.getStatus(), null);
			}
			this.token = token.asText();
			this.tokenExpiry = System.currentTimeMillis() + 50L * 60L * 1000L;
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Workday token failed: " + ex.getMessage(), ex);
		}
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		headers.put("Accept", "application/json");
		if (this.token != null)
		{
			headers.put("Authorization", "Bearer " + this.token);
		}
		return headers;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		final String tenant = cfg.getProperty("tenant", null);
		if (tenant == null)
		{
			throw new ConnectorException("Workday: set the property tenant");
		}
		if ("raas".equals(pOperation))
		{
			// RaaS uses the integration user (BASIC), not the OAuth token
			if (cfg.getUsername() == null || cfg.isPlaceholder(cfg.getPassword()))
			{
				throw new ConnectorException("Workday RaaS: set the integration user name and password (authentication BASIC)");
			}
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("format", "json");
			if (pInputs == null) throw new ConnectorException("Workday RaaS: input report is required");
			for (final Map.Entry<String, String> input : pInputs.entrySet())
			{
				if (!"report".equals(input.getKey()) && !"max".equals(input.getKey()) && input.getValue() != null && !input.getValue().isEmpty())
				{
					query.put(input.getKey(), input.getValue());
				}
			}
			final String report = AbstractRestConnector.require(pInputs, "report");
			final String path = report.startsWith("/") ? report : "/ccx/service/customreport2/" + tenant + "/" + report;
			final String previous = this.token;
			this.token = null; // Basic headers only
			try
			{
				final JsonNode reply = this.bodyOrNull(this.get(path, query), "raas");
				return AbstractCrudRestConnector.list(Json.at(reply, "Report_Entry"), null);
			}
			finally
			{
				this.token = previous;
			}
		}
		this.authenticate();
		final String api = "/ccx/api/v1/" + tenant;
		final Map<String, String> none = java.util.Collections.emptyMap();
		final Map<String, String> inputs = pInputs == null ? none : pInputs;
		if ("workers".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			if (inputs.get("search") != null && !inputs.get("search").trim().isEmpty())
			{
				query.put("search", inputs.get("search").trim());
			}
			return this.pages(api + "/workers", query, inputs.get("max"), "workers");
		}
		if ("worker".equals(pOperation))
		{
			return this.bodyOrNull(this.authorized(api + "/workers/" + AbstractRestConnector.segment(AbstractRestConnector.require(inputs, "id")), null), "worker");
		}
		if ("resource".equals(pOperation))
		{
			final String path = AbstractRestConnector.require(inputs, "path");
			final Map<String, String> query = new LinkedHashMap<String, String>();
			for (final String pair : (inputs.get("query") == null ? "" : inputs.get("query")).split("&"))
			{
				final int eq = pair.indexOf('=');
				if (eq > 0)
				{
					query.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
				}
			}
			if (path.contains("..") || path.contains("?") || path.contains("://"))
			{
				throw new ConnectorException("Workday resource: path must be a resource path such as /organizations");
			}
			return this.pages(api + (path.startsWith("/") ? path : "/" + path), query, inputs.get("max"), "resource");
		}
		throw new ConnectorException("Workday: unknown operation '" + pOperation + "'");
	}

	/** Workday REST pages with limit (at most 100) and offset; stops at total, at max, or at an empty page. */
	private JsonNode pages(final String pPath, final Map<String, String> pQuery, final String pMax, final String pWhat) throws ConnectorException
	{
		final int max = WorkdayConnector.bounded(pMax, 100, 1, WorkdayConnector.bounded(this.getConfig().getProperty("max.rows", "1000"), 1000, 1, 10000));
		final com.fasterxml.jackson.databind.node.ArrayNode data = Json.mapper().createArrayNode();
		JsonNode total = null;
		JsonNode single = null;
		for (int offset = 0, page = 0; data.size() < max && page < 100; page++)
		{
			final Map<String, String> query = new LinkedHashMap<String, String>(pQuery);
			query.put("limit", String.valueOf(Math.min(100, max - data.size())));
			query.put("offset", String.valueOf(offset));
			final JsonNode reply = this.bodyOrNull(this.authorized(pPath, query), pWhat);
			if (reply == null || !reply.path("data").isArray())
			{
				single = reply; // not a collection (a single resource)
				break;
			}
			total = reply.get("total");
			for (final JsonNode item : reply.get("data")) if (data.size() < max) data.add(item);
			offset += reply.get("data").size();
			if (reply.get("data").size() == 0 || total != null && total.canConvertToLong() && offset >= total.asLong()) break;
		}
		if (single != null && data.size() == 0) return single;
		return AbstractCrudRestConnector.list(data, total);
	}

	/** GET with the bearer token; a 401 (token revoked or expired early) refreshes it once. */
	private HttpJsonClient.Response authorized(final String pPath, final Map<String, String> pQuery) throws ConnectorException
	{
		final HttpJsonClient.Response response = this.get(pPath, pQuery);
		if (response.getStatus() != 401) return response;
		synchronized (this)
		{
			this.token = null;
		}
		this.authenticate();
		return this.get(pPath, pQuery);
	}

	private static int bounded(final String pValue, final int pDefault, final int pMinimum, final int pMaximum)
	{
		try
		{
			return pValue == null || pValue.trim().isEmpty() ? Math.min(pDefault, pMaximum) : Math.max(pMinimum, Math.min(pMaximum, Integer.parseInt(pValue.trim())));
		}
		catch (final NumberFormatException ex)
		{
			return Math.min(pDefault, pMaximum);
		}
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("workers".equals(pOperation) || "worker".equals(pOperation))
		{
			final String p = "workers".equals(pOperation) ? "results[0]." : "";
			return Arrays.asList(FieldInfo.of(p + "id", "Workday id", "string"), FieldInfo.of(p + "descriptor", "Name", "string"),
				FieldInfo.of(p + "primaryWorkEmail", "Work email", "string"), FieldInfo.of(p + "primaryWorkPhone", "Work phone", "string"),
				FieldInfo.of(p + "businessTitle", "Business title", "string"), FieldInfo.of(p + "primarySupervisoryOrganization.descriptor", "Organization", "string"),
				FieldInfo.of(p + "isManager", "Manager?", "boolean"), FieldInfo.of(p + "person.email", "Email", "string"));
		}
		return new java.util.ArrayList<FieldInfo>();
	}
}
