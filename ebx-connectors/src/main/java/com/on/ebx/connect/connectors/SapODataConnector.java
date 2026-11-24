package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
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
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * SAP through OData (SAP Gateway, S/4HANA Cloud and on-premise APIs such as
 * {@code API_BUSINESS_PARTNER}), OData v2 by default or v4 (property {@code odata.version=4}).
 * <p>
 * Connector row: {@code baseUrl} = service root, e.g.
 * {@code https://host/sap/opu/odata/sap/API_BUSINESS_PARTNER}; authentication BASIC
 * (communication user), BEARER (OAuth token) or API_KEY_HEADER ({@code APIKey} for the SAP
 * Business Accelerator Hub sandbox). Properties: {@code odata.version}, {@code sap-client},
 * {@code entitySet} (default), {@code default.expand}.
 * <p>
 * Documents are normalised whatever the version: one entity is a flat object, a collection is
 * {@code {results:[...], count}}; v2 {@code {results:[...]}} wrappers of expanded navigations
 * become plain arrays (so a sub-object mapping uses {@code to_BusinessPartnerAddress} as its
 * collection), {@code __metadata} / {@code __deferred} are dropped and {@code /Date(ms)/} values
 * become ISO timestamps. Writes fetch the CSRF token first and keep the Gateway session cookies;
 * {@code create} accepts nested collections (deep insert) built by sub-object mappings.
 */
public final class SapODataConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("get", "One entity by key (input key = raw key predicate, e.g. '1000001' or BusinessPartner='1000001')", "entitySet", "key"),
		Operation.search("query", "Entities of a set ($filter, $select, $expand, $orderby, $top as inputs)", "entitySet"),
		Operation.write("create", "POST the outbound document (deep insert when it carries collections)", "entitySet"),
		Operation.write("update", "PATCH / MERGE the outbound document on a key", "entitySet", "key"),
		Operation.write("upsert", "Update the entity when it exists, create it otherwise", "entitySet", "key"),
		Operation.read("describe", "Properties of an entity set, from $metadata", "entitySet"));

	private String csrfToken;
	private String cookies;

	@Override
	public String getType()
	{
		return "sap-odata";
	}

	@Override
	public String getLabel()
	{
		return "SAP (OData v2 / v4)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("SAP OData v2 and v4 connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN).authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER)
			.capability(com.on.ebx.connect.spi.ConnectorCapability.SCHEMA_DISCOVERY).capability(com.on.ebx.connect.spi.ConnectorCapability.BULK_READ).capability(com.on.ebx.connect.spi.ConnectorCapability.BULK_WRITE).apiVersion("OData v2").apiVersion("OData v4")
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("odata.version", com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).defaultValue("2").enumerationValues("2", "4").build())
			.configurationProperty(prop("sap-client", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null))
			.configurationProperty(prop("entitySet", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null))
			.configurationProperty(prop("default.expand", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, null))
			.configurationProperty(prop("max.rows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, Integer.valueOf(500)));
		for (final Operation o : getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition prop(final String n, final com.on.ebx.connect.spi.ConfigurationPropertyType t, final Object v) { return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n, t).defaultValue(v).build(); }

	@Override
	public List<Operation> getOperations()
	{
		return SapODataConnector.OPERATIONS;
	}

	private boolean v4()
	{
		return "4".equals(this.getConfig().getProperty("odata.version", "2"));
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		headers.put("Accept", "application/json");
		final String client = this.getConfig().getProperty("sap-client", null);
		if (client != null)
		{
			headers.put("sap-client", client);
		}
		if (this.cookies != null)
		{
			headers.put("Cookie", this.cookies);
		}
		return headers;
	}

	// ------------------------------------------------------------------ operations

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.requireSecret();
		final String entitySet = this.entitySet(pInputs);
		if ("get".equals(pOperation))
		{
			final Map<String, String> query = this.query(pInputs, false);
			final HttpJsonClient.Response response = this.get("/" + entitySet + "(" + SapODataConnector.keyPredicate(AbstractRestConnector.require(pInputs, "key")) + ")", query);
			return this.entity(this.bodyOrNull(response, "get " + entitySet));
		}
		if ("query".equals(pOperation))
		{
			final Map<String, String> query = this.query(pInputs, true);
			final String deltaLink = pInputs == null ? null : pInputs.get("deltaLink");
			final JsonNode first = deltaLink == null || deltaLink.trim().isEmpty()
				? this.bodyOrNull(this.get("/" + entitySet, query), "query " + entitySet)
				: this.bodyOrNull(this.getAbsolute(ODataSupport.continuationUrl(this.baseUrl(), deltaLink), null), "delta query " + entitySet);
			return ODataSupport.collectPages(this.baseUrl(), first, this.maxPages(), this.maxRecords(pInputs), new ODataSupport.PageFetcher() {
				public JsonNode get(final String pUrl) throws ConnectorException { return SapODataConnector.this.bodyOrNull(SapODataConnector.this.getAbsolute(pUrl, null), "OData continuation"); }
			});
		}
		if ("describe".equals(pOperation))
		{
			return null; // the designer goes through describeFields
		}
		if ("create".equals(pOperation) || "update".equals(pOperation) || "upsert".equals(pOperation))
		{
			if (pPayload == null || !pPayload.isObject())
			{
				throw new ConnectorException("SAP " + pOperation + ": the outbound document is empty");
			}
			String operation = pOperation;
			if ("upsert".equals(pOperation))
			{
				final HttpJsonClient.Response existing = this.get("/" + entitySet + "(" + SapODataConnector.keyPredicate(AbstractRestConnector.require(pInputs, "key")) + ")", null);
				operation = existing.isSuccess() ? "update" : "create";
			}
			final String path = "create".equals(operation) ? "/" + entitySet
				: "/" + entitySet + "(" + SapODataConnector.keyPredicate(AbstractRestConnector.require(pInputs, "key")) + ")";
			// A token or session that expired on the Gateway answers 403 "CSRF token validation failed":
			// fetch a new token (and session cookies) and send once more, never more.
			for (int attempt = 0;; attempt++)
			{
				this.fetchCsrf(entitySet);
				final Map<String, String> extra = new LinkedHashMap<String, String>();
				extra.put("X-CSRF-Token", this.csrfToken == null ? "" : this.csrfToken);
				extra.put("Content-Type", "application/json");
				if (!"create".equals(operation)) extra.put("X-HTTP-Method", this.v4() ? "PATCH" : "MERGE");
				final HttpJsonClient.Response response = this.send("POST", path, null, pPayload, extra);
				if (attempt == 0 && SapODataConnector.csrfRejected(response))
				{
					this.csrfToken = null;
					this.cookies = null;
					continue;
				}
				return this.written(response, operation + " " + entitySet);
			}
		}
		throw new ConnectorException("SAP OData: unknown operation '" + pOperation + "'");
	}

	@Override
	public BatchResult executeBatch(final ConnectorContext pContext, final BatchRequest pRequest) throws ConnectorException
	{
		ConnectorBatchExecutor.validate(this, pRequest);
		final int maximum = bounded(this.getConfig().getProperty("batch.max.items", "50"), 1, BatchRequest.HARD_MAX_ITEMS);
		if (pRequest.getItems().size() > maximum) throw new ConnectorException("SAP OData batch exceeds configured item limit " + maximum);
		// Generic SAP upsert first probes existence. Preserve that behavior instead of silently changing
		// it to provider-dependent PATCH semantics inside a native batch.
		for (final BatchRequest.Item item : pRequest.getItems()) if ("upsert".equals(item.getOperation()))
			return ConnectorBatchExecutor.sequential(this, pContext, pRequest);
		this.requireSecret();
		boolean writes = false;
		final List<ODataSupport.BatchOperation> operations = new ArrayList<ODataSupport.BatchOperation>();
		for (final BatchRequest.Item item : pRequest.getItems())
		{
			final String set = this.entitySet(item.getInputs());
			final String name = item.getOperation();
			String method, path;
			JsonNode body = null;
			if ("get".equals(name))
			{
				method = "GET";
				path = ODataBatchAdapter.relativeUrl(set + "(" + keyPredicate(AbstractRestConnector.require(item.getInputs(), "key")) + ")", this.query(item.getInputs(), false));
			}
			else if ("query".equals(name))
			{
				method = "GET";
				path = ODataBatchAdapter.relativeUrl(set, this.query(item.getInputs(), true));
			}
			else if ("create".equals(name))
			{
				method = "POST";
				path = set;
				body = item.getPayload();
				writes = true;
			}
			else if ("update".equals(name))
			{
				method = this.v4() ? "PATCH" : "MERGE";
				path = set + "(" + keyPredicate(AbstractRestConnector.require(item.getInputs(), "key")) + ")";
				body = item.getPayload();
				writes = true;
			}
			else throw new ConnectorException("SAP OData: operation '" + name + "' cannot be batched");
			operations.add(new ODataSupport.BatchOperation(item.getId(), method, path, java.util.Collections.<String, String>emptyMap(), body, null));
		}
		final JsonNode v4Wire = this.v4() ? ODataSupport.v4Batch(operations) : null;
		final ODataSupport.V2Batch v2Wire = this.v4() ? null : ODataSupport.v2Batch(operations, "batch_ebx");
		if (v4Wire != null) this.requireWireBound(Json.write(v4Wire), pRequest.getMaximumBytes());
		else if (v2Wire.getBody().length > pRequest.getMaximumBytes()) throw new ConnectorException("SAP OData batch exceeds byte limit " + pRequest.getMaximumBytes());
		for (int attempt = 0;; attempt++)
		{
			if (writes) this.fetchCsrf(this.entitySet(pRequest.getItems().get(0).getInputs()));
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			if (writes) headers.put("X-CSRF-Token", this.csrfToken == null ? "" : this.csrfToken);
			headers.put("Content-Type", v4Wire != null ? "application/json" : v2Wire.getContentType());
			final HttpJsonClient.Response response = this.sendBytesBounded("POST", "/$batch", null,
				v4Wire != null ? Json.write(v4Wire).getBytes(java.nio.charset.StandardCharsets.UTF_8) : v2Wire.getBody(), headers, pRequest.getMaximumBytes());
			if (writes && SapODataConnector.csrfRejected(response))
			{
				this.csrfToken = null;
				this.cookies = null;
				if (attempt == 0) continue;
				throw new ConnectorException("SAP OData: CSRF token rejected again after a fresh token was fetched", 403, null);
			}
			if (!response.isSuccess()) this.bodyOrNull(response, "OData batch");
			final JsonNode normalized = v4Wire != null ? ODataSupport.v4BatchResponse(response.getBody())
				: ODataSupport.v2BatchResponse(response.getHeader("Content-Type"), response.getRawBody(), pRequest.getItems().size(), pRequest.getMaximumBytes());
			return ODataBatchAdapter.results(pRequest, normalized);
		}
	}

	private void requireWireBound(final String pWire, final int pMaximum) throws ConnectorException
	{
		try { if (pWire.getBytes("UTF-8").length > pMaximum) throw new ConnectorException("SAP OData batch exceeds byte limit " + pMaximum); }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
	}

	private JsonNode written(final HttpJsonClient.Response pResponse, final String pWhat) throws ConnectorException
	{
		if (SapODataConnector.csrfRejected(pResponse))
		{
			this.csrfToken = null;
			this.cookies = null;
			throw new ConnectorException("SAP OData: CSRF token rejected again after a fresh token was fetched", 403, null);
		}
		if (pResponse.getStatus() == 204)
		{
			return Json.object();
		}
		final JsonNode body = this.bodyOrNull(pResponse, pWhat);
		return body == null ? Json.object() : this.entity(body);
	}

	/** SAP answers a stale token with 403 and {@code x-csrf-token: Required}, or a body naming CSRF. */
	static boolean csrfRejected(final HttpJsonClient.Response pResponse)
	{
		if (pResponse.getStatus() != 403) return false;
		final String header = pResponse.getHeader("x-csrf-token");
		return "required".equalsIgnoreCase(header == null ? null : header.trim())
			|| pResponse.getRawBody() != null && pResponse.getRawBody().toLowerCase(java.util.Locale.ROOT).contains("csrf");
	}

	private String entitySet(final Map<String, String> pInputs) throws ConnectorException
	{
		final String set = pInputs == null || pInputs.get("entitySet") == null || pInputs.get("entitySet").trim().isEmpty()
				? this.getConfig().getProperty("entitySet", null) : pInputs.get("entitySet").trim();
		if (set == null)
		{
			throw new ConnectorException("SAP OData: no entity set (input 'entitySet' or connector property 'entitySet')");
		}
		if (!set.matches("[A-Za-z_][A-Za-z0-9_]*"))
		{
			throw new ConnectorException("SAP OData: invalid entity set name '" + set + "'");
		}
		return set;
	}

	private Map<String, String> query(final Map<String, String> pInputs, final boolean pCollection)
	{
		return ODataSupport.query(this.v4() ? ODataSupport.Version.V4 : ODataSupport.Version.V2, pInputs, pCollection,
			this.getConfig().getProperty("max.rows", "500"), this.getConfig().getProperty("default.expand", null));
	}
	private int maxPages() { return bounded(this.getConfig().getProperty("max.pages", "20"), 1, 100); }
	private int maxRecords(final Map<String, String> pInputs) { return bounded(pInputs == null ? null : pInputs.get("max"), 1, bounded(this.getConfig().getProperty("max.rows", "500"), 1, 10000)); }
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

	/** {@code 1000001} becomes {@code '1000001'}; {@code BusinessPartner='X',Y=2} is kept as is. */
	static String keyPredicate(final String pKey)
	{
		final String key = pKey.trim();
		if (key.contains("=") || key.startsWith("'"))
		{
			return key;
		}
		return "'" + key.replace("'", "''") + "'";
	}

	private void fetchCsrf(final String pEntitySet) throws ConnectorException
	{
		if (this.csrfToken != null)
		{
			return;
		}
		final Map<String, String> extra = new LinkedHashMap<String, String>();
		extra.put("X-CSRF-Token", "Fetch");
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("$top", "1");
		final HttpJsonClient.Response response = this.send("GET", "/" + pEntitySet, query, null, extra);
		final String token = response.getHeader("x-csrf-token");
		final List<String> setCookies = response.getHeaders("Set-Cookie");
		if (!setCookies.isEmpty())
		{
			final StringBuilder jar = new StringBuilder();
			for (final String cookie : setCookies)
			{
				final int semicolon = cookie.indexOf(';');
				jar.append(jar.length() > 0 ? "; " : "").append(semicolon < 0 ? cookie : cookie.substring(0, semicolon));
			}
			this.cookies = jar.toString();
		}
		this.csrfToken = token == null ? "" : token;
	}

	// ------------------------------------------------------------------ normalisation

	/** One entity, unwrapped from v2 {@code d} and cleaned. */
	private JsonNode entity(final JsonNode pBody)
	{
		return ODataSupport.entity(pBody);
	}

	/** A collection as {@code {results:[...], count}} whatever the OData version. */
	private JsonNode collection(final JsonNode pBody)
	{
		return ODataSupport.collection(pBody);
	}

	/** Drops __metadata / __deferred / @odata.*, unwraps nested {results:[]}, converts v2 dates. */
	static JsonNode normalise(final JsonNode pNode)
	{
		return ODataSupport.normalise(pNode);
	}

	// ------------------------------------------------------------------ $metadata

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		String set;
		try
		{
			set = this.entitySet(pInputs);
		}
		catch (final ConnectorException ex)
		{
			return fields;
		}
		final HttpJsonClient.Response response = this.send("GET", "/$metadata", null, null, java.util.Collections.singletonMap("Accept", "application/xml"));
		if (!response.isSuccess() || response.getRawBody() == null)
		{
			throw new ConnectorException("SAP OData: $metadata -> HTTP " + response.getStatus());
		}
		final String prefix = "query".equals(pOperation) ? "results[0]." : "";
		for (final ODataSupport.MetadataField property : ODataSupport.parseMetadata(response.getRawBody(), set))
		{
			fields.add(FieldInfo.of(prefix + property.getName(), property.getLabel(), property.isNavigation() ? "navigation (array)" : property.getType()));
		}
		return fields;
	}

	/** Properties (name, label, type) and navigation properties of the entity type behind a set. */
	static List<String[]> parseMetadata(final String pXml, final String pEntitySet) throws ConnectorException
	{
		final List<String[]> out = new ArrayList<String[]>();
		for (final ODataSupport.MetadataField property : ODataSupport.parseMetadata(pXml, pEntitySet))
			out.add(new String[] { property.getName(), property.getLabel(), property.isNavigation() ? "navigation (array)" : property.getType() });
		return out;
	}
}
