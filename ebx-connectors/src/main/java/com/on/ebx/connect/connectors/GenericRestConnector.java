package com.on.ebx.connect.connectors;

import java.util.ArrayList;
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
import com.on.ebx.connect.core.OpenApiImporter;
import com.on.ebx.connect.core.RestPageTraversal;
import com.on.ebx.connect.core.RestPayload;
import com.on.ebx.connect.core.MultipartBody;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Configuration-driven JSON REST connector: operations are declared as connector properties, no
 * Java needed for a new API.
 * <pre>
 * op.getCustomer.method = GET
 * op.getCustomer.path   = /customers/{customerId}
 * op.getCustomer.query  = expand=addresses&amp;lang={lang}
 * op.pushCustomer.method = PUT
 * op.pushCustomer.path   = /customers/{customerId}        (the outbound mapping builds the body)
 * </pre>
 * {@code {name}} placeholders are replaced by the mapping inputs of the same name (URL-encoded).
 * GET and DELETE are READ operations, POST and PUT are WRITE operations.
 */
public final class GenericRestConnector extends AbstractRestConnector
{
	@Override
	public String getType()
	{
		return "rest";
	}

	@Override
	public String getLabel()
	{
		return "Generic JSON REST";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "GENERIC_INTEGRATION")
			.description("Configuration-driven JSON REST connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC)
			.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN).authentication(com.on.ebx.connect.spi.AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS)
			.capability(com.on.ebx.connect.spi.ConnectorCapability.SCHEMA_DISCOVERY)
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("auth.apiKeyQuery", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).advanced(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("op.<name>.method", com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).required(true).enumerationValues("GET", "POST", "PUT", "PATCH", "DELETE").build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("op.<name>.path", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).required(true).build())
			.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("op.<name>.kind", com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).enumerationValues("READ", "SEARCH", "WRITE").build());
		for (final Operation o : getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		final List<Operation> operations = new ArrayList<Operation>();
		ConnectorConfig config;
		try
		{
			config = this.getConfig();
		}
		catch (final IllegalStateException ex)
		{
			return operations;
		}
		for (final Map.Entry<String, String> property : config.getProperties().entrySet())
		{
			final String key = property.getKey();
			if (!key.startsWith("op.") || !key.endsWith(".path"))
			{
				continue;
			}
			final String name = key.substring(3, key.length() - 5);
			final String method = config.getProperty("op." + name + ".method", "GET").toUpperCase();
			final List<String> placeholders = GenericRestConnector.placeholders(property.getValue());
			placeholders.addAll(GenericRestConnector.placeholders(config.getProperty("op." + name + ".query", "")));
			// op.<name>.required (OpenAPI import) narrows the mandatory inputs; without it every placeholder is required
			final String declared = config.getProperty("op." + name + ".required", null);
			final List<String> required = new ArrayList<String>();
			final List<String> optional = new ArrayList<String>();
			if (declared == null)
			{
				required.addAll(placeholders);
			}
			else
			{
				for (final String r : declared.split(","))
				{
					if (!r.trim().isEmpty() && !"-".equals(r.trim()))
					{
						required.add(r.trim()); // "-" = declared: no mandatory input
					}
				}
				for (final String p : placeholders)
				{
					if (!required.contains(p))
					{
						optional.add(p);
					}
				}
			}
			Operation.Kind kind = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) ? Operation.Kind.WRITE : Operation.Kind.READ;
			final String declaredKind = config.getProperty("op." + name + ".kind", null);
			if (declaredKind != null)
			{
				kind = Operation.Kind.valueOf(declaredKind.trim().toUpperCase());
			}
			else if (kind == Operation.Kind.READ)
			{
				for (final String[] field : OpenApiImporter.fields(config, "op." + name + ".fields"))
				{
					if (field[0].startsWith("[0]") || field[0].matches("[A-Za-z_]+\\[0\\]\\..*"))
					{
						kind = Operation.Kind.SEARCH; // the answer is (or wraps) a list
						break;
					}
				}
			}
			final String description = config.getProperty("op." + name + ".description", method + " " + property.getValue());
			// readOnly (2026-09-24): this connector already knows the HTTP method per operation, unlike most others -
			// pass it so Operation.isReadOnly() can tell GET from a mutating verb even when "kind" alone would not
			// (e.g. DELETE defaults to Kind.READ above when op.<name>.kind is not set).
			operations.add(new Operation(name, kind, description, required.toArray(new String[required.size()]),
				optional.isEmpty() ? null : optional.toArray(new String[optional.size()]), method));
		}
		return operations;
	}

	/** Fields known from an OpenAPI import: the answer for read operations, the body for write operations. */
	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> out = new ArrayList<FieldInfo>();
		ConnectorConfig config;
		try
		{
			config = this.getConfig();
		}
		catch (final IllegalStateException ex)
		{
			return out;
		}
		final String method = config.getProperty("op." + pOperation + ".method", "GET").toUpperCase();
		final boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
		for (final String[] field : OpenApiImporter.fields(config, "op." + pOperation + (write ? ".bodyFields" : ".fields")))
		{
			out.add(FieldInfo.of(field[0], field[0], field[1]));
		}
		return out;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation,
		final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final ConnectorConfig config = this.getConfig();
		final String path = GenericRestConnector.requiredPath(config, pOperation);
		final String method = config.getProperty("op." + pOperation + ".method", "GET").toUpperCase();
		final Map<String, String> query = this.query(config, pOperation, pInputs);
		final String resolvedPath = GenericRestConnector.fill(path, pInputs, true);
		final Map<String, String> callHeaders = this.operationHeaders(pOperation, pInputs);
		final String nextRoot = config.getProperty("op." + pOperation + ".pagination.next", null);
		if ("GET".equals(method) && nextRoot != null)
		{
			final String initialUrl = HttpJsonClient.buildUrl(this.baseUrl(), resolvedPath, query);
			final int maxPages = bounded(config.getProperty("op." + pOperation + ".pagination.maxPages", "20"), 1, 100);
			final int maxRecords = bounded(config.getProperty("op." + pOperation + ".pagination.maxRecords", "1000"), 1, 10000);
			try
			{
				return RestPageTraversal.collect(initialUrl, config.getProperty("op." + pOperation + ".responseRoot", ""), nextRoot,
					maxPages, maxRecords, this.fetcher(callHeaders));
			}
			catch (final java.io.IOException ex) { throw new ConnectorException(config.getId() + ": pagination failed: " + ex.getMessage(), ex); }
		}
		final HttpJsonClient.Response response;
		if ("multipart/form-data".equalsIgnoreCase(config.getProperty("op." + pOperation + ".contentType", "")))
		{
			final MultipartBody multipart = multipart(pPayload);
			callHeaders.put("Content-Type", multipart.contentType());
			response = this.sendBytes(method, resolvedPath, query, multipart.finish(), callHeaders);
		}
		else response = this.send(method, resolvedPath, query, "GET".equals(method) || "DELETE".equals(method) ? null : pPayload, callHeaders);
		final JsonNode body = this.bodyOrNull(response, method + " " + resolvedPath);
		return RestPayload.root(body, config.getProperty("op." + pOperation + ".responseRoot", ""));
	}

	/**
	 * Volume lot 7b: real streaming for a paginated GET operation ({@code op.<name>.pagination.next}
	 * set) - each page is fetched and its items pushed to {@code pSink} one at a time via
	 * {@link RestPageTraversal#stream}, dropped as soon as the next page is requested, instead of
	 * {@link RestPageTraversal#collect} accumulating every page's items into one {@code results}
	 * {@link com.fasterxml.jackson.databind.node.ArrayNode} first. Every other operation (no
	 * pagination configured, or not a GET) falls back to the default (execute + replay) implementation,
	 * unchanged - the same {@code op.<name>.pagination.next} gate {@link #execute} itself uses to
	 * choose {@link RestPageTraversal#collect} over a plain single call.
	 * <p>
	 * {@code pMax} bounds the total records delivered across every page (the same role
	 * {@code pagination.maxRecords} already played, now the tighter of the two - a caller asking for
	 * fewer than the connector's own configured ceiling gets fewer, never more); {@code pagination.maxPages}
	 * still bounds how many HTTP calls a single read may make regardless of {@code pMax}. {@code available}
	 * is always {@code -1}: nothing in this generic traversal's contract (a {@code next} link/cursor)
	 * ever reveals the collection's real total, unlike Snowflake's {@code resultSetMetaData.numRows} -
	 * same "genuinely unknown" honesty as JDBC's own native streaming. A sink-initiated stop (a batch/
	 * circuit-breaker/cancellation decision) stops fetching further pages at once and is reported as an
	 * ordinary, non-truncated read, matching every other native streaming override in this codebase.
	 * {@code itemsPath} is ignored - each item IS a row, the same convention the other native overrides
	 * use (the connector's own {@code responseRoot}/{@code pagination.next} properties already say where
	 * the items and the next link live inside each page).
	 */
	@Override
	public Connector.RowStreamResult readRows(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final String pItemsPath, final int pMax, final Connector.RowSink pSink) throws ConnectorException
	{
		final ConnectorConfig config = this.getConfig();
		final String method = config.getProperty("op." + pOperation + ".method", "GET").toUpperCase();
		final String nextRoot = config.getProperty("op." + pOperation + ".pagination.next", null);
		if (!"GET".equals(method) || nextRoot == null)
		{
			return super.readRows(pContext, pOperation, pInputs, pItemsPath, pMax, pSink);
		}
		final String path = GenericRestConnector.requiredPath(config, pOperation);
		final Map<String, String> query = this.query(config, pOperation, pInputs);
		final String resolvedPath = GenericRestConnector.fill(path, pInputs, true);
		final Map<String, String> callHeaders = this.operationHeaders(pOperation, pInputs);
		final String initialUrl = HttpJsonClient.buildUrl(this.baseUrl(), resolvedPath, query);
		final int maxPages = bounded(config.getProperty("op." + pOperation + ".pagination.maxPages", "20"), 1, 100);
		final int configuredMaxRecords = bounded(config.getProperty("op." + pOperation + ".pagination.maxRecords", "1000"), 1, 10000);
		final int maxRecords = Math.min(pMax, configuredMaxRecords);
		try
		{
			final com.fasterxml.jackson.databind.node.ObjectNode summary = RestPageTraversal.stream(initialUrl,
				config.getProperty("op." + pOperation + ".responseRoot", ""), nextRoot, maxPages, maxRecords,
				new RestPageTraversal.ItemSink()
				{
					@Override
					public boolean accept(final JsonNode pItem)
					{
						return pSink.accept(pItem);
					}
				}, this.fetcher(callHeaders));
			return new Connector.RowStreamResult(summary.path("count").asInt(), summary.path("truncated").asBoolean(false), -1);
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException(config.getId() + ": pagination failed: " + ex.getMessage(), ex);
		}
	}

	/** {@code op.<name>.path}, or a clear error naming the missing property - shared by {@link #execute} and {@link #readRows}. */
	private static String requiredPath(final ConnectorConfig pConfig, final String pOperation) throws ConnectorException
	{
		final String path = pConfig.getProperty("op." + pOperation + ".path", null);
		if (path == null)
		{
			throw new ConnectorException(pConfig.getId() + ": no property op." + pOperation + ".path");
		}
		return path;
	}

	/** {@code op.<name>.query} resolved against {@code pInputs}, plus an OpenAPI {@code apiKey in=query} scheme - shared by {@link #execute} and {@link #readRows}. */
	private Map<String, String> query(final ConnectorConfig pConfig, final String pOperation, final Map<String, String> pInputs)
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		final String rawQuery = pConfig.getProperty("op." + pOperation + ".query", "");
		for (final String pair : rawQuery.split("&"))
		{
			final int eq = pair.indexOf('=');
			if (eq > 0)
			{
				final String rawValue = pair.substring(eq + 1).trim();
				final java.util.regex.Matcher single = java.util.regex.Pattern.compile("^\\{([A-Za-z0-9_.-]+)\\}$").matcher(rawValue);
				if (single.matches() && (pInputs == null || pInputs.get(single.group(1)) == null || pInputs.get(single.group(1)).trim().isEmpty()))
				{
					continue; // an optional parameter without value is simply not sent
				}
				query.put(pair.substring(0, eq).trim(), GenericRestConnector.fill(rawValue, pInputs, false));
			}
		}
		final String apiKeyQuery = pConfig.getProperty("auth.apiKeyQuery", null);
		if (apiKeyQuery != null && !pConfig.isPlaceholder(pConfig.getApiKey()))
		{
			query.put(apiKeyQuery, pConfig.getApiKey()); // OpenAPI securityScheme apiKey in=query
		}
		return query;
	}

	/** A {@link RestPageTraversal.Fetcher} over this connector's own authenticated GET - shared by {@link #execute} and {@link #readRows}. */
	private RestPageTraversal.Fetcher fetcher(final Map<String, String> pCallHeaders)
	{
		return new RestPageTraversal.Fetcher()
		{
			@Override
			public HttpJsonClient.Response get(final String pUrl) throws java.io.IOException
			{
				try
				{
					return GenericRestConnector.this.getAbsolute(pUrl, pCallHeaders);
				}
				catch (final ConnectorException ex)
				{
					throw new java.io.IOException(ex.getMessage(), ex);
				}
			}
		};
	}

	private Map<String, String> operationHeaders(final String pOperation, final Map<String, String> pInputs)
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		final String prefix = "op." + pOperation + ".header.";
		final String cookiePrefix = "op." + pOperation + ".cookie.";
		final StringBuilder cookies = new StringBuilder();
		for (final Map.Entry<String, String> property : this.getConfig().getProperties().entrySet())
		{
			if (property.getKey().startsWith(prefix)) headers.put(property.getKey().substring(prefix.length()), GenericRestConnector.fill(property.getValue(), pInputs, false));
			else if (property.getKey().startsWith(cookiePrefix))
			{
				if (cookies.length() > 0) cookies.append("; ");
				cookies.append(property.getKey().substring(cookiePrefix.length())).append('=').append(GenericRestConnector.fill(property.getValue(), pInputs, false));
			}
		}
		if (cookies.length() > 0) headers.put("Cookie", cookies.toString());
		for (final Map.Entry<String, String> header : headers.entrySet())
		{
			if (!header.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
				throw new IllegalArgumentException("Invalid HTTP header name");
			for (int i = 0; i < header.getValue().length(); i++)
			{
				final char c = header.getValue().charAt(i);
				if (c < 32 || c == 127) throw new IllegalArgumentException("Control characters are not allowed in HTTP headers");
			}
		}
		return headers;
	}

	private static int bounded(final String pValue, final int pMinimum, final int pMaximum)
	{
		try { return Math.max(pMinimum, Math.min(pMaximum, Integer.parseInt(pValue))); }
		catch (final RuntimeException ex) { return pMinimum; }
	}

	private static MultipartBody multipart(final JsonNode pPayload) throws ConnectorException
	{
		final MultipartBody body = new MultipartBody();
		if (pPayload == null || !pPayload.isObject()) return body;
		final java.util.Iterator<Map.Entry<String, JsonNode>> fields = pPayload.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			final JsonNode value = field.getValue();
			if (value != null && value.isObject() && value.has("base64"))
			{
				try { body.file(field.getKey(), value.has("filename") ? value.get("filename").asText() : field.getKey(), value.has("contentType") ? value.get("contentType").asText() : "application/octet-stream", java.util.Base64.getDecoder().decode(value.get("base64").asText())); }
				catch (final IllegalArgumentException ex) { throw new ConnectorException("Invalid base64 multipart field '" + field.getKey() + "'", ex); }
			}
			else body.field(field.getKey(), value == null || value.isNull() ? "" : value.isValueNode() ? value.asText() : Json.write(value));
		}
		return body;
	}

	private static String fill(final String pTemplate, final Map<String, String> pInputs, final boolean pEncode)
	{
		String out = pTemplate;
		for (final String name : GenericRestConnector.placeholders(pTemplate))
		{
			final String value = pInputs == null || pInputs.get(name) == null ? "" : pInputs.get(name);
			out = out.replace("{" + name + "}", pEncode ? HttpJsonClient.encode(value) : value);
		}
		return out;
	}

	private static List<String> placeholders(final String pTemplate)
	{
		final List<String> names = new ArrayList<String>();
		int from = 0;
		while (pTemplate != null)
		{
			final int open = pTemplate.indexOf('{', from);
			final int close = open < 0 ? -1 : pTemplate.indexOf('}', open);
			if (open < 0 || close < 0)
			{
				break;
			}
			final String name = pTemplate.substring(open + 1, close).trim();
			if (!name.isEmpty() && !names.contains(name))
			{
				names.add(name);
			}
			from = close + 1;
		}
		return names;
	}
}
