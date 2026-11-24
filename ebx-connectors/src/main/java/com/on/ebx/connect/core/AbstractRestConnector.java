package com.on.ebx.connect.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PreviewRequest;
import com.on.ebx.connect.spi.PreviewResult;

/**
 * Base class for JSON REST connectors: applies the configured authentication, builds URLs from the
 * base URL, turns 404 into null and other errors into {@link ConnectorException}.
 */
public abstract class AbstractRestConnector implements Connector
{
	private ConnectorConfig config;
	private HttpJsonClient client;
	private final Map<String, RestSchemaRefresh> schemaRefreshes = new java.util.concurrent.ConcurrentHashMap<String, RestSchemaRefresh>();

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		this.config = pConfig;
		Redaction.remember(pConfig);
		this.client = new HttpJsonClient(pConfig.getConnectTimeoutMs(), pConfig.getReadTimeoutMs(),
			"EBX-Connectors/1.0 (" + pConfig.getId() + ")");
		final javax.net.ssl.SSLContext tls = Tls.contextFor(pConfig); // IllegalStateException when the stores are wrong
		if (tls != null)
		{
			this.client.setSslContext(tls);
		}
	}

	protected ConnectorConfig getConfig()
	{
		if (this.config == null)
		{
			throw new IllegalStateException("Connector " + this.getType() + " is not configured");
		}
		return this.config;
	}

	/** Headers derived from the configuration: authentication plus connector-specific extras. */
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		final ConnectorConfig cfg = this.getConfig();
		switch (cfg.getAuthType())
		{
		case BASIC:
			final String credentials = (cfg.getUsername() == null ? "" : cfg.getUsername()) + ":"
					+ (cfg.getPassword() == null ? "" : cfg.getPassword());
			headers.put("Authorization", "Basic "
					+ Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			break;
		case API_KEY_HEADER:
			if (cfg.getApiKeyHeader() != null && cfg.getApiKey() != null)
			{
				headers.put(cfg.getApiKeyHeader(), cfg.getApiKey());
			}
			break;
		case BEARER:
			if (cfg.getApiKey() != null)
			{
				headers.put("Authorization", "Bearer " + cfg.getApiKey());
			}
			break;
		case OAUTH2:
			headers.put(cfg.getProperty("oauth.tokenHeader", "Authorization"), OAuth2.bearer(cfg, this.client)); // TokenException when refused
			break;
		case NONE:
		default:
			break;
		}
		final String accept = cfg.getProperty("accept", null);
		if (accept != null)
		{
			headers.put("Accept", accept);
		}
		return headers;
	}

	/** Fails fast when a required secret is still the seed placeholder. */
	protected void requireSecret() throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		final boolean needsKey = cfg.getAuthType() == ConnectorConfig.AuthType.API_KEY_HEADER
				|| cfg.getAuthType() == ConnectorConfig.AuthType.BEARER;
		if (needsKey && cfg.isPlaceholder(cfg.getApiKey()))
		{
			throw new ConnectorException("Connector " + cfg.getId()
					+ ": API key not configured (Connectors > Connector > " + cfg.getId() + ")");
		}
		if (cfg.getAuthType() == ConnectorConfig.AuthType.BASIC && cfg.isPlaceholder(cfg.getPassword()))
		{
			throw new ConnectorException("Connector " + cfg.getId() + ": password not configured");
		}
		if (cfg.getAuthType() == ConnectorConfig.AuthType.OAUTH2)
		{
			final String problem = OAuth2.validate(cfg, false);
			if (problem != null)
			{
				throw new ConnectorException("Connector " + cfg.getId() + ": " + problem);
			}
		}
	}

	protected HttpJsonClient.Response get(final String pPath, final Map<String, String> pQuery)
		throws ConnectorException
	{
		return this.send("GET", pPath, pQuery, null);
	}

	/**
	 * Performs a side-effect-free HTTP probe against the configured endpoint. A connector can set
	 * {@code inspection.path} when its API root is not probeable; otherwise the configured base URL
	 * is used. HEAD deliberately avoids downloading business data or invoking an operation.
	 */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.nanoTime();
		this.requireSecret();
		final String path = this.getConfig().getProperty("inspection.path", "");
		final HttpJsonClient.Response response = this.send("HEAD", path,
			java.util.Collections.<String, String>emptyMap(), null);
		final long elapsed = (System.nanoTime() - started) / 1000000L;
		final int status = response.getStatus();
		if (status >= 200 && status < 400)
		{
			return ConnectionTestResult.success("HTTP endpoint responded with status " + status, elapsed);
		}
		return ConnectionTestResult.failure("HTTP endpoint responded with status " + status, elapsed);
	}

	/**
	 * Executes an explicitly selected logical read operation and returns a bounded sample.
	 *
	 * <p>This shared implementation deliberately refuses to choose an operation or invent inputs.
	 * WRITE operations are rejected before connector execution, and READ/SEARCH operations run only
	 * when every input declared as required by the operation is present. The normal connector HTTP
	 * implementation therefore retains responsibility for authentication and protocol-specific
	 * response handling while this method supplies the inspection safety boundary.</p>
	 */
	@Override
	public PreviewResult preview(final ConnectorContext pContext, final PreviewRequest pRequest)
		throws ConnectorException
	{
		if (pRequest == null)
		{
			return PreviewResult.unsupported("A preview request is required");
		}
		final String requested = pRequest.getOperation().isEmpty()
			? pRequest.getObjectId() : pRequest.getOperation();
		if (requested.isEmpty())
		{
			return PreviewResult.unsupported("Select a READ or SEARCH operation to preview");
		}

		Operation operation = null;
		final List<Operation> operations = this.getOperations();
		if (operations != null)
		{
			for (final Operation candidate : operations)
			{
				if (candidate != null && requested.equals(candidate.getName()))
				{
					operation = candidate;
					break;
				}
			}
		}
		if (operation == null)
		{
			return PreviewResult.unsupported("Unknown operation '" + requested + "'");
		}
		if (operation.getKind() == Operation.Kind.WRITE)
		{
			return PreviewResult.unsupported("Operation '" + requested
				+ "' writes data and cannot be used for preview");
		}

		final Map<String, String> inputs = pRequest.getInputs();
		final List<String> missing = new ArrayList<String>();
		for (final String name : operation.getRequiredInputs())
		{
			final String value = inputs.get(name);
			if (value == null || value.trim().isEmpty()) missing.add(name);
		}
		if (!missing.isEmpty())
		{
			return PreviewResult.unsupported("Operation '" + requested
				+ "' requires input(s): " + missing);
		}

		this.requireSecret();
		final JsonNode response = this.execute(pContext, requested, inputs, null);
		if (response == null || response.isNull() || response.isMissingNode())
		{
			return PreviewResult.of(Collections.<JsonNode>emptyList(), false);
		}
		final int limit = pRequest.getLimit();
		final List<JsonNode> records = new ArrayList<JsonNode>();
		boolean truncated = false;
		if (response.isArray())
		{
			for (final JsonNode record : response)
			{
				if (records.size() >= limit)
				{
					truncated = true;
					break;
				}
				records.add(record);
			}
		}
		else
		{
			records.add(response);
		}
		return PreviewResult.of(records, truncated);
	}

	/** True when the JDK offers java.net.http, i.e. PATCH can be sent as such (checked once). */
	private static Boolean NATIVE_PATCH;

	protected boolean nativePatch()
	{
		if (AbstractRestConnector.NATIVE_PATCH == null)
		{
			try
			{
				Class.forName("java.net.http.HttpClient");
				AbstractRestConnector.NATIVE_PATCH = Boolean.TRUE;
			}
			catch (final ClassNotFoundException ex)
			{
				AbstractRestConnector.NATIVE_PATCH = Boolean.FALSE;
			}
		}
		return AbstractRestConnector.NATIVE_PATCH.booleanValue();
	}

	/** Base URL of the calls; connectors with a discovered instance URL (Salesforce) override it. */
	protected String baseUrl() throws ConnectorException
	{
		return this.getConfig().getBaseUrl();
	}

	protected HttpJsonClient.Response send(final String pMethod, final String pPath,
		final Map<String, String> pQuery, final JsonNode pBody) throws ConnectorException
	{
		return this.send(pMethod, pPath, pQuery, pBody, null);
	}

	/** Same, with headers added to (or overriding) the configured ones for this call. */
	protected HttpJsonClient.Response send(final String pMethod, final String pPath,
		final Map<String, String> pQuery, final JsonNode pBody, final Map<String, String> pExtraHeaders) throws ConnectorException
	{
		String method = pMethod;
		Map<String, String> query = pQuery;
		if ("PATCH".equalsIgnoreCase(pMethod) && !this.nativePatch())
		{
			// HttpURLConnection refuses PATCH: Salesforce and most APIs accept the override
			method = "POST";
			query = new LinkedHashMap<String, String>();
			if (pQuery != null)
			{
				query.putAll(pQuery);
			}
			query.put("_HttpMethod", "PATCH");
		}
		final String url = HttpJsonClient.buildUrl(this.baseUrl(), pPath, query);
		return this.exchange(method, url, pBody == null ? null : Json.write(pBody), pExtraHeaders, pMethod);
	}

	/**
	 * Same as {@link #send(String, String, Map, JsonNode, Map)}, with a read timeout for this call only (the connector's
	 * own when {@code pReadTimeoutMs} is not positive). The connection timeout, the TLS context and the retry policy stay
	 * the connector's; the cached client is not changed.
	 */
	protected HttpJsonClient.Response send(final String pMethod, final String pPath,
		final Map<String, String> pQuery, final JsonNode pBody, final Map<String, String> pExtraHeaders, final int pReadTimeoutMs) throws ConnectorException
	{
		if (pReadTimeoutMs <= 0 || "PATCH".equalsIgnoreCase(pMethod))
		{
			return this.send(pMethod, pPath, pQuery, pBody, pExtraHeaders);
		}
		final String url = HttpJsonClient.buildUrl(this.baseUrl(), pPath, pQuery);
		return this.exchange(this.client.withReadTimeout(pReadTimeoutMs), pMethod, url, pBody == null ? null : Json.write(pBody), pExtraHeaders, pMethod);
	}

	/** The configured headers plus the extras of a call; an OAuth token refused raises a ConnectorException. */
	private Map<String, String> authHeaders(final Map<String, String> pExtraHeaders) throws ConnectorException
	{
		try
		{
			final Map<String, String> headers = this.headers();
			if (pExtraHeaders != null)
			{
				headers.putAll(pExtraHeaders);
			}
			return headers;
		}
		catch (final OAuth2.TokenException ex)
		{
			throw new ConnectorException(ex.getMessage(), ex);
		}
	}

	/** One HTTP exchange; with OAuth 2.0, a 401 drops the cached token and the call is retried once with a fresh one. */
	private HttpJsonClient.Response exchange(final String pMethod, final String pUrl, final String pBody, final Map<String, String> pExtraHeaders,
		final String pLabel) throws ConnectorException
	{
		return this.exchange(this.client, pMethod, pUrl, pBody, pExtraHeaders, pLabel);
	}

	private HttpJsonClient.Response exchange(final HttpJsonClient pClient, final String pMethod, final String pUrl, final String pBody,
		final Map<String, String> pExtraHeaders, final String pLabel) throws ConnectorException
	{
		try
		{
			HttpJsonClient.Response response = pClient.send(pMethod, pUrl, this.authHeaders(pExtraHeaders), pBody);
			if (response.getStatus() == 401 && this.getConfig().getAuthType() == ConnectorConfig.AuthType.OAUTH2)
			{
				OAuth2.invalidate(this.getConfig());
				response = pClient.send(pMethod, pUrl, this.authHeaders(pExtraHeaders), pBody);
			}
			return response;
		}
		catch (final IOException ex)
		{
			throw new ConnectorException(Redaction.redact(this.getConfig().getId() + ": " + pLabel + " " + pUrl + " failed: " + ex.getMessage()), ex);
		}
	}

	/** A call with a body that is not JSON (SOAP, form data...): {@code pHeaders} should carry the Content-Type. */
	protected HttpJsonClient.Response sendRaw(final String pMethod, final String pPath, final Map<String, String> pQuery, final String pBody,
		final Map<String, String> pHeaders) throws ConnectorException
	{
		final String url = HttpJsonClient.buildUrl(this.baseUrl(), pPath, pQuery);
		return this.exchange(pMethod, url, pBody, pHeaders, pMethod);
	}

	/** Multipart/binary exchange using the configured authentication and HTTP safety policy. */
	protected HttpJsonClient.Response sendBytes(final String pMethod, final String pPath,
		final Map<String, String> pQuery, final byte[] pBody, final Map<String, String> pHeaders) throws ConnectorException
	{
		final String url = HttpJsonClient.buildUrl(this.baseUrl(), pPath, pQuery);
		try
		{
			return this.client.sendBytes(pMethod, url, this.authHeaders(pHeaders), pBody);
		}
		catch (final IOException ex)
		{
			throw new ConnectorException(Redaction.redact(this.getConfig().getId() + ": " + pMethod + " " + url + " failed: " + ex.getMessage()), ex);
		}
	}

	/** Binary exchange whose response is stopped while streaming at the supplied byte ceiling. */
	protected HttpJsonClient.Response sendBytesBounded(final String pMethod, final String pPath,
		final Map<String, String> pQuery, final byte[] pBody, final Map<String, String> pHeaders,
		final int pMaximumResponseBytes) throws ConnectorException
	{
		final String url = HttpJsonClient.buildUrl(this.baseUrl(), pPath, pQuery);
		try { return this.client.sendBytes(pMethod, url, this.authHeaders(pHeaders), pBody, pMaximumResponseBytes); }
		catch (final IOException ex) { throw new ConnectorException(Redaction.redact(this.getConfig().getId() + ": " + pMethod + " " + url + " failed: " + ex.getMessage()), ex); }
	}

	/** Follows an already resolved URL through the same authentication/OAuth-renewal path. */
	protected HttpJsonClient.Response getAbsolute(final String pUrl, final Map<String, String> pHeaders) throws ConnectorException
	{
		return this.exchange("GET", pUrl, null, pHeaders, "GET");
	}

	/** Conditional, authenticated schema/OpenAPI refresh retained per remote URL. */
	protected RestSchemaRefresh.Result refreshSchema(final String pUrl, final Map<String, String> pHeaders) throws ConnectorException
	{
		RestSchemaRefresh refresh = this.schemaRefreshes.get(pUrl);
		if (refresh == null)
		{
			final RestSchemaRefresh candidate = new RestSchemaRefresh();
			final RestSchemaRefresh previous = this.schemaRefreshes.putIfAbsent(pUrl, candidate);
			refresh = previous == null ? candidate : previous;
		}
		try
		{
			final RestSchemaRefresh.Result result = refresh.refresh(this.client, pUrl, this.authHeaders(pHeaders));
			if (result.isChanged()) this.signalSchemaDrift(result.getCheckedAt());
			return result;
		}
		catch (final IOException ex) { throw new ConnectorException(this.getConfig().getId() + ": schema refresh failed: " + ex.getMessage(), ex); }
	}

	/**
	 * Conditional schema refresh with a restart-safe persistence and scheduling seam. EBX-specific
	 * code supplies a transaction-aware store (connector property or technical table); the shared
	 * connector layer remains repository-independent.
	 */
	protected RestSchemaRefresh.Result refreshSchema(final String pUrl, final Map<String, String> pHeaders,
		final RestSchemaRefresh.StateStore pStore, final String pStateKey, final long pMinimumIntervalMillis)
		throws ConnectorException
	{
		final String cacheKey = pUrl + "|" + String.valueOf(pStateKey);
		RestSchemaRefresh refresh = this.schemaRefreshes.get(cacheKey);
		if (refresh == null)
		{
			try
			{
				final RestSchemaRefresh candidate = new RestSchemaRefresh(pStore, pStateKey);
				final RestSchemaRefresh previous = this.schemaRefreshes.putIfAbsent(cacheKey, candidate);
				refresh = previous == null ? candidate : previous;
			}
			catch (final IOException ex)
			{
				throw new ConnectorException(this.getConfig().getId() + ": schema refresh state could not be loaded: " + ex.getMessage(), ex);
			}
		}
		try
		{
			final RestSchemaRefresh.Result result = refresh.refresh(this.client, pUrl, this.authHeaders(pHeaders), pMinimumIntervalMillis);
			if (result.isChanged()) this.signalSchemaDrift(result.getCheckedAt());
			return result;
		}
		catch (final IOException ex) { throw new ConnectorException(this.getConfig().getId() + ": schema refresh failed: " + ex.getMessage(), ex); }
	}

	/** Keeps the public REST extension SPI independent of the optional operations runtime. */
	private void signalSchemaDrift(final long pCheckedAt)
	{
		try
		{
			final Class<?> runtime = Class.forName("com.on.ebx.connect.core.ConnectorOperationsRuntime");
			runtime.getMethod("schemaDrift", String.class, java.util.Date.class).invoke(null,
				this.getConfig().getId(), new java.util.Date(pCheckedAt));
		}
		catch (final ReflectiveOperationException ignored)
		{
			// Standalone SPI consumers intentionally do not package the module operations runtime.
		}
	}

	/** EBX scheduler entry point; authentication follows the saved connector configuration. */
	public final RestSchemaRefresh.Result refreshPersistedSchema(final String pUrl,
		final RestSchemaRefresh.StateStore pStore, final String pStateKey, final long pMinimumIntervalMillis)
		throws ConnectorException
	{
		return this.refreshSchema(pUrl, null, pStore, pStateKey, pMinimumIntervalMillis);
	}

	/** 2xx: the body; 404: null; anything else: an exception carrying the status. */
	protected JsonNode bodyOrNull(final HttpJsonClient.Response pResponse, final String pWhat)
		throws ConnectorException
	{
		if (pResponse.isSuccess())
		{
			return pResponse.getBody();
		}
		if (pResponse.getStatus() == 404)
		{
			return null;
		}
		String detail = pResponse.getRawBody();
		if (detail != null && detail.length() > 300)
		{
			detail = detail.substring(0, 300) + "...";
		}
		throw new ConnectorException(this.getConfig().getId() + ": " + pWhat + " -> HTTP "
				+ pResponse.getStatus() + (detail == null || detail.isEmpty() ? "" : " " + detail),
			pResponse.getStatus(), null);
	}

	protected static String require(final Map<String, String> pInputs, final String pName)
		throws ConnectorException
	{
		final String value = pInputs == null ? null : pInputs.get(pName);
		if (value == null || value.trim().isEmpty())
		{
			throw new ConnectorException("Missing input '" + pName + "'");
		}
		return value.trim();
	}

	protected static String segment(final String pValue)
	{
		return HttpJsonClient.encode(pValue);
	}
}
