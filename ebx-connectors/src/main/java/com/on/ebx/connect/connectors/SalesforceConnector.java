package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Salesforce REST API (OAuth 2.0, bidirectional).
 * <p>
 * Connector row: {@code baseUrl} = login URL ({@code https://login.salesforce.com} or the My Domain
 * URL), {@code apiKey} = consumer secret, properties {@code clientId} (consumer key),
 * {@code oauth.flow} = {@code client_credentials} (default, connected app with the flow enabled and
 * a run-as user), {@code jwt_bearer} (certificate) or {@code password} (username / password + property
 * {@code securityToken}; deprecated: Salesforce retires that flow on 20 February 2027),
 * {@code apiVersion} (default v60.0), optional {@code instanceUrl}.
 * <p>
 * Inputs: {@code sobject} (Account, Contact, a custom object), {@code id}, {@code externalIdField}
 * + {@code value}, {@code soql}. Read operations return the sObject JSON as-is ({@code Name},
 * {@code BillingCity}...), so mapping rules use the field API names directly; {@code query} returns
 * the query result ({@code records[0].Name}).
 */
public final class SalesforceConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("get", "sObject by Salesforce id", "sobject", "id"),
		Operation.read("getByExternalId", "sObject by external id field", "sobject", "externalIdField", "value"),
		Operation.search("query", "SOQL query (records[] in the result)", "soql"),
		Operation.write("create", "Create an sObject from the outbound document", "sobject"),
		Operation.write("update", "Update an sObject by id from the outbound document", "sobject", "id"),
		Operation.write("upsert", "Upsert an sObject by external id from the outbound document", "sobject", "externalIdField", "value"),
		Operation.read("describe", "Field metadata of an sObject", "sobject"),
		Operation.search("catalog", "Data catalog inventory: the sObjects, their fields from the describe, the record count "
			+ "(input source; optional schemas = API names or patterns, default core standard objects + custom objects, rowCounts)", "source"),
		Operation.search("profile", "Data catalog statistics per field, computed by ON EBX on the most recently modified records (input source; optional schemas, tables, sampleSize)", "source"),
		Operation.search("sample", "Sampled values of the text fields of the latest records, for the data catalog's classification (input source; optional schemas, tables, limit)", "source"));

	/** Said wherever the password flow is configured or used (Salesforce retires it, release note of 2026). */
	static final String PASSWORD_FLOW_DEPRECATED = "Deprecated: Salesforce retires the OAuth 2.0 username-password flow on 20 February 2027 and logins will then fail."
		+ " Switch oauth.flow to client_credentials (connected app or external client app with a run-as user) or jwt_bearer (certificate).";

	private String accessToken;
	private String instanceUrl;
	private long tokenExpiry;

	@Override
	public String getType()
	{
		return "salesforce";
	}

	@Override
	public String getLabel()
	{
		return "Salesforce (REST, OAuth 2.0)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("BUSINESS_APPS", "Salesforce REST and SOQL connector")
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS)
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.CUSTOM)
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.CERTIFICATE)
			.capability(com.on.ebx.connect.spi.ConnectorCapability.SCHEMA_DISCOVERY).apiVersion("v60.0")
			.configurationProperty(prop("apiVersion", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, "v60.0", false))
			.configurationProperty(prop("clientId", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null, true))
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("oauth.flow", com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).label("OAuth flow").required(true).defaultValue("client_credentials").enumerationValues("client_credentials", "password", "jwt_bearer").deprecatedValue("password", PASSWORD_FLOW_DEPRECATED).description("client_credentials (default) or jwt_bearer. password is deprecated: Salesforce retires the username-password flow on 20 February 2027.").build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("securityToken", com.on.ebx.connect.spi.ConfigurationPropertyType.SECRET).visibleWhen(com.on.ebx.connect.spi.ConfigurationPropertyVisibility.equalTo("oauth.flow", "password")).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("oauth.privateKey", com.on.ebx.connect.spi.ConfigurationPropertyType.CERTIFICATE).secret(true).visibleWhen(com.on.ebx.connect.spi.ConfigurationPropertyVisibility.equalTo("oauth.flow", "jwt_bearer")).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("instanceUrl", com.on.ebx.connect.spi.ConfigurationPropertyType.URL).advanced(true).build());
		return operations(b).build();
	}

	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor(final String c, final String d) { return com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), c).description(d); }
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
		return SalesforceConnector.OPERATIONS;
	}

	@Override
	protected String baseUrl() throws ConnectorException
	{
		this.ensureToken();
		return this.instanceUrl + "/services/data/" + this.apiVersion();
	}

	@Override
	protected void requireSecret()
	{
		// ensureToken checks consumer key and secret; the generic OAuth 2.0 check would want oauth.tokenUrl
	}

	@Override
	protected Map<String, String> headers()
	{
		// Own login (ensureToken): never the generic OAuth 2.0 of AbstractRestConnector, which wants oauth.tokenUrl
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		if (this.accessToken != null)
		{
			headers.put("Authorization", "Bearer " + this.accessToken);
		}
		headers.put("Accept", "application/json");
		return headers;
	}

	private String apiVersion()
	{
		return this.getConfig().getProperty("apiVersion", "v60.0");
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation,
		final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		if (SalesforceCatalog.OPERATIONS.contains(pOperation))
		{
			return new SalesforceCatalog(new RestOrg(this), this.getConfig().getBaseUrl(), this.getConfig().getId()).execute(pOperation, pInputs);
		}
		HttpJsonClient.Response response = this.call(pOperation, pInputs, pPayload);
		if (response.getStatus() == 401)
		{
			this.accessToken = null; // expired or revoked: one fresh login, then give up
			response = this.call(pOperation, pInputs, pPayload);
		}
		if ("query".equals(pOperation) && response.isSuccess())
		{
			return this.followQuery(response.getBody(), SalesforceConnector.recordLimit(pInputs == null ? null : pInputs.get("max")));
		}
		if (response.getStatus() == 201 || response.getStatus() == 204)
		{
			return response.getBody() == null ? Json.object() : response.getBody();
		}
		return this.bodyOrNull(response, pOperation);
	}

	/**
	 * Read recipe (2026-09-24): a SOQL query result is capped at 2000 records with {@code done=false} and a
	 * {@code nextRecordsUrl} to fetch the rest; the "query" operation used to hand back only the first page.
	 * Since a catalogued table's read recipe is nothing more than this operation with a full-field SOQL, every
	 * caller of "query" — not only the catalog — now reads every row. {@code nextRecordsUrl} already carries the
	 * "/services/data/{version}" prefix that {@link #baseUrl()} also adds, so only the "/query/{locator}" tail is
	 * requested.
	 */
	private JsonNode followQuery(final JsonNode pFirstPage, final int pLimit) throws ConnectorException
	{
		if (pFirstPage == null)
		{
			return null;
		}
		JsonNode page = pFirstPage;
		final ArrayNode merged = Json.mapper().createArrayNode();
		int pages = 1;
		for (final JsonNode record : page.path("records"))
		{
			if (pLimit > 0 && merged.size() >= pLimit) break;
			merged.add(record);
		}
		while (!page.path("done").asBoolean(true) && page.hasNonNull("nextRecordsUrl") && (pLimit <= 0 || merged.size() < pLimit))
		{
			final String next = page.path("nextRecordsUrl").asText();
			final int tail = next.indexOf("/query/");
			final HttpJsonClient.Response response = this.get(tail >= 0 ? next.substring(tail) : next, null);
			if (!response.isSuccess() || response.getBody() == null)
			{
				// audit C-16: a failed page used to end the loop and hand back what was read as if it were all of it
				throw new ConnectorException("Salesforce query stopped at page " + (pages + 1) + " (HTTP " + response.getStatus() + ") after "
					+ merged.size() + " of " + pFirstPage.path("totalSize").asInt(-1) + " record(s): the result is incomplete, nothing was returned");
			}
			page = response.getBody();
			pages++;
			for (final JsonNode record : page.path("records"))
			{
				if (pLimit > 0 && merged.size() >= pLimit) break;
				merged.add(record);
			}
		}
		if (merged.size() == 0)
		{
			return null;
		}
		final ObjectNode out = Json.object();
		out.put("totalSize", pFirstPage.path("totalSize").asInt(merged.size()));
		out.put("done", true);
		out.set("records", merged);
		return out;
	}

	/**
	 * Records to read for a feed read of {@code max} items: one more than {@code max}, so the engine sees that the source held
	 * more and reports the read as truncated; 0 (no limit) when no usable {@code max} was given (audit C-16).
	 */
	static int recordLimit(final String pMax)
	{
		try
		{
			final int max = pMax == null ? 0 : Integer.parseInt(pMax.trim());
			return max > 0 && max < Integer.MAX_VALUE ? max + 1 : 0;
		}
		catch (final NumberFormatException ex)
		{
			return 0;
		}
	}

	private HttpJsonClient.Response call(final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if ("get".equals(pOperation))
		{
			return this.get("/sobjects/" + this.sobject(pInputs) + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "id")), null);
		}
		if ("getByExternalId".equals(pOperation))
		{
			return this.get("/sobjects/" + this.sobject(pInputs) + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "externalIdField"))
					+ "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "value")), null);
		}
		if ("query".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("q", AbstractRestConnector.require(pInputs, "soql"));
			return this.get("/query", query);
		}
		if ("describe".equals(pOperation))
		{
			return this.get("/sobjects/" + this.sobject(pInputs) + "/describe", null);
		}
		if ("create".equals(pOperation))
		{
			return this.send("POST", "/sobjects/" + this.sobject(pInputs), null, pPayload == null ? Json.object() : pPayload);
		}
		if ("update".equals(pOperation))
		{
			return this.send("PATCH", "/sobjects/" + this.sobject(pInputs) + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "id")), null,
				pPayload == null ? Json.object() : pPayload);
		}
		if ("upsert".equals(pOperation))
		{
			return this.send("PATCH", "/sobjects/" + this.sobject(pInputs) + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "externalIdField"))
					+ "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "value")), null, pPayload == null ? Json.object() : pPayload);
		}
		throw new ConnectorException("Salesforce: unknown operation '" + pOperation + "'");
	}

	/** GET on the org's REST API for the catalog operations, one fresh login on 401; the body, or an exception with the HTTP status. */
	JsonNode read(final String pPath, final Map<String, String> pQuery) throws ConnectorException
	{
		HttpJsonClient.Response response = this.get(pPath, pQuery);
		if (response.getStatus() == 401)
		{
			this.accessToken = null;
			response = this.get(pPath, pQuery);
		}
		if (!response.isSuccess())
		{
			final String body = String.valueOf(response.getRawBody());
			throw new ConnectorException("Salesforce: GET " + pPath + " -> HTTP " + response.getStatus() + " " + (body.length() > 300 ? body.substring(0, 300) : body),
				response.getStatus(), null);
		}
		return response.getBody() == null ? Json.object() : response.getBody();
	}

	/** The org as {@link SalesforceCatalog} reads it. */
	static final class RestOrg implements SalesforceCatalog.Org
	{
		final SalesforceConnector connector;

		RestOrg(final SalesforceConnector pConnector)
		{
			this.connector = pConnector;
		}

		@Override
		public JsonNode sobjects() throws ConnectorException
		{
			return this.connector.read("/sobjects", null);
		}

		@Override
		public JsonNode describe(final String pName) throws ConnectorException
		{
			return this.connector.read("/sobjects/" + pName + "/describe", null);
		}

		@Override
		public JsonNode query(final String pSoql) throws ConnectorException
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("q", pSoql);
			return this.connector.read("/query", query);
		}
	}

	private String sobject(final Map<String, String> pInputs) throws ConnectorException
	{
		final String name = AbstractRestConnector.require(pInputs, "sobject");
		if (!name.matches("[A-Za-z][A-Za-z0-9_]*"))
		{
			throw new ConnectorException("Salesforce: invalid sObject name '" + name + "'");
		}
		return name;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation,
		final Map<String, String> pInputs) throws ConnectorException
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		if ("catalog".equals(pOperation))
		{
			fields.add(FieldInfo.of("tables", "sObjects (feed items): source, assetKey, qualifiedName (API name), name, kind TABLE, remarks, rowCount, url, scannedAt, columns[]", "array"));
			fields.add(FieldInfo.of("tables[0].columns", "Fields from the describe: columnKey, name, position, dataType, nativeType, nullable, primaryKey, remarks, scannedAt", "array"));
			fields.add(FieldInfo.of("errors", "sObjects that could not be read: object, message", "array"));
			return fields;
		}
		if ("profile".equals(pOperation))
		{
			fields.add(FieldInfo.of("columns", "One item per field: columnKey, assetKey, qualifiedName, name, dataType, profiledAt, profileDate, rowCount, "
				+ "nullCount, nullRate, distinctCount, uniqueRate, method (SAMPLE), sampleSize, minValue, maxValue, meanValue, minLength, maxLength, avgLength", "array"));
			return fields;
		}
		if ("sample".equals(pOperation))
		{
			fields.add(FieldInfo.of("columns", "One item per text field: columnKey, assetKey, qualifiedName, name, sampledAt, sampleSize, values[], valuesText", "array"));
			return fields;
		}
		if (pInputs == null || pInputs.get("sobject") == null || pInputs.get("sobject").trim().isEmpty())
		{
			return fields;
		}
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("sobject", pInputs.get("sobject"));
		final JsonNode describe = this.execute(pContext, "describe", inputs, null);
		final JsonNode list = Json.at(describe, "fields");
		if (list == null)
		{
			return fields;
		}
		final String prefix = "query".equals(pOperation) ? "records[0]." : "";
		for (final JsonNode field : list)
		{
			final String name = Json.at(field, "name") == null ? null : Json.at(field, "name").asText();
			if (name == null)
			{
				continue;
			}
			final String label = Json.at(field, "label") == null ? name : Json.at(field, "label").asText();
			final String type = Json.at(field, "type") == null ? "" : Json.at(field, "type").asText();
			final boolean updateable = Json.at(field, "updateable") != null && Json.at(field, "updateable").asBoolean();
			final boolean createable = Json.at(field, "createable") != null && Json.at(field, "createable").asBoolean();
			final boolean write = "create".equals(pOperation) || "update".equals(pOperation) || "upsert".equals(pOperation);
			if (write && !(updateable || createable))
			{
				continue;
			}
			fields.add(FieldInfo.of(prefix + name, label, type));
		}
		return fields;
	}

	// ------------------------------------------------------------------ OAuth 2.0

	private synchronized void ensureToken() throws ConnectorException
	{
		if (this.accessToken != null && System.currentTimeMillis() < this.tokenExpiry)
		{
			return;
		}
		final ConnectorConfig cfg = this.getConfig();
		if ("jwt_bearer".equals(cfg.getProperty("oauth.flow", "client_credentials")))
		{
			// JWT bearer flow (connected app with a certificate): generic provider, audience = login URL
			final HttpJsonClient tokenClient = new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(), "EBX-Connectors/1.0 (salesforce)");
			try
			{
				final com.on.ebx.connect.core.OAuth2.Token token = com.on.ebx.connect.core.OAuth2.token(cfg, tokenClient,
					HttpJsonClient.buildUrl(cfg.getBaseUrl(), "/services/oauth2/token", null), cfg.getBaseUrl());
				this.accessToken = token.accessToken;
				this.tokenExpiry = token.expiry;
				final JsonNode instance = Json.at(token.raw, "instance_url");
				this.instanceUrl = cfg.getProperty("instanceUrl", instance == null ? cfg.getBaseUrl() : instance.asText());
				return;
			}
			catch (final com.on.ebx.connect.core.OAuth2.TokenException ex)
			{
				throw new ConnectorException(ex.getMessage(), ex);
			}
		}
		final String clientId = cfg.getProperty("clientId", null);
		final String secret = cfg.getApiKey();
		if (clientId == null || cfg.isPlaceholder(secret))
		{
			throw new ConnectorException("Salesforce: set the connected app consumer key (property clientId) and consumer secret (apiKey)");
		}
		final Map<String, String> form = new LinkedHashMap<String, String>();
		final String flow = cfg.getProperty("oauth.flow", "client_credentials");
		form.put("grant_type", flow);
		form.put("client_id", clientId);
		form.put("client_secret", secret);
		if ("password".equals(flow))
		{
			if (cfg.getUsername() == null || cfg.isPlaceholder(cfg.getPassword()))
			{
				throw new ConnectorException("Salesforce: the password flow needs username and password");
			}
			com.orchestranetworks.service.LoggingCategory.getKernel().warn("[connectors] Salesforce connector '" + cfg.getId() + "': "
				+ PASSWORD_FLOW_DEPRECATED);
			form.put("username", cfg.getUsername());
			form.put("password", cfg.getPassword() + cfg.getProperty("securityToken", ""));
		}
		final StringBuilder body = new StringBuilder();
		for (final Map.Entry<String, String> e : form.entrySet())
		{
			body.append(body.length() > 0 ? "&" : "").append(HttpJsonClient.encode(e.getKey())).append('=').append(HttpJsonClient.encode(e.getValue()));
		}
		final HttpJsonClient client = new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(), "EBX-Connectors/1.0 (salesforce)");
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/x-www-form-urlencoded");
		final String tokenUrl = HttpJsonClient.buildUrl(cfg.getBaseUrl(), "/services/oauth2/token", null);
		try
		{
			final HttpJsonClient.Response response = client.send("POST", tokenUrl, headers, body.toString());
			final JsonNode token = Json.at(response.getBody(), "access_token");
			if (!response.isSuccess() || token == null)
			{
				throw new ConnectorException("Salesforce login failed (HTTP " + response.getStatus() + "): "
						+ String.valueOf(Json.at(response.getBody(), "error_description")), response.getStatus(), null);
			}
			this.accessToken = token.asText();
			final JsonNode instance = Json.at(response.getBody(), "instance_url");
			this.instanceUrl = cfg.getProperty("instanceUrl", instance == null ? cfg.getBaseUrl() : instance.asText());
			this.tokenExpiry = System.currentTimeMillis() + 50L * 60L * 1000L;
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Salesforce login failed: " + ex.getMessage(), ex);
		}
	}
}
