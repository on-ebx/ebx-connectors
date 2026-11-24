package com.on.ebx.connect.connectors;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Databricks SQL warehouses through the Statement Execution API ({@code /api/2.0/sql/statements}).
 * <p>
 * Connector row: {@code baseUrl} = workspace URL ({@code https://adb-....azuredatabricks.net} or
 * {@code https://....cloud.databricks.com}); property {@code warehouse_id} (SQL warehouse);
 * authentication BEARER with {@code apiKey} = personal access token, or OAuth machine-to-machine:
 * property {@code auth=oauth}, property {@code clientId} (service principal) and {@code apiKey} =
 * its secret. Context: properties {@code catalog}, {@code schema}, {@code wait} (seconds, max 50),
 * {@code max.rows}, {@code nested.function} ({@code parse_json} by default for VARIANT columns,
 * empty to store nested documents as JSON strings).
 * <p>
 * Documents: {@code {columns:[...], rows:[{col: value}], count}} with typed values, so an inbound
 * mapping reads {@code rows[0].name} and a feed uses {@code itemsPath = rows}. Outbound,
 * {@code merge} and {@code insert} bind the document's top-level fields as named parameters;
 * nested objects and arrays (sub-object mappings) go through the nested function.
 */
public final class DatabricksConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("query", "Run a SELECT (input sql with :b1..:bn markers, inputs b1..bn) and return its rows", "sql"),
		Operation.read("lookup", "First row of a table where keyColumn = value", "table", "keyColumn", "value"),
		Operation.write("merge", "MERGE the outbound document into a Delta table on keyColumn(s)", "table", "keyColumn"),
		Operation.write("insert", "INSERT the outbound document into a table", "table"),
		Operation.write("execute", "Run any statement (input sql, inputs b1..bn)", "sql"),
		Operation.read("describe", "Columns of a table", "table"),
		Operation.search("catalog", "Data catalog inventory: schemas, tables, views, columns from the catalog's information_schema (inputs source, schemas - CATALOG.SCHEMA for another catalog, types)", "source"),
		Operation.search("profile", "Data catalog column statistics, one aggregate query per table (inputs source, schemas, tables, where; budget and cost: maxTables, maxSeconds, largeTableRows, largeTables = sample|exact|skip, sampleRows, approxDistinct, dryRun = plan only)", "source"),
		Operation.search("sample", "Data catalog value samples of the text columns (inputs source, schemas, tables, limit)", "source"),
		Operation.search("usage", "Data catalog popularity: reads per table over 30 days from system.access.table_lineage (input source)", "source"),
		Operation.search("lineage", "Data catalog column lineage over 30 days from system.access.column_lineage (input source)", "source"));

	private String oauthToken;
	private long oauthExpiry;

	@Override
	public String getType()
	{
		return "databricks";
	}

	@Override
	public String getLabel()
	{
		return "Databricks (SQL Statement Execution API)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("DATA_PLATFORMS");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN).authentication(com.on.ebx.connect.spi.AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS).capability(com.on.ebx.connect.spi.ConnectorCapability.DESCRIBE_OBJECT).capability(com.on.ebx.connect.spi.ConnectorCapability.SCHEMA_DISCOVERY).apiVersion("Databricks SQL Statement Execution API 2.0").pagination(new com.on.ebx.connect.spi.PaginationDescriptor(com.on.ebx.connect.spi.PaginationDescriptor.Strategy.TOKEN, 1000, 10000, "next_chunk_internal_link")).configurationProperty(enumeration("auth", "token", "oauth")).configurationProperty(property("catalog", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("clientId", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("max.rows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.maxTables", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.maxSeconds", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.largeTableRows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.sampleRows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.approxDistinct", com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN, false)).configurationProperty(enumeration("profile.largeTables", "sample", "exact", "skip")).configurationProperty(property("nested.function", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("schema", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("wait", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("warehouse_id", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, true));
		return b.build();
	}
	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor(final String c)
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), c);
		for (final com.on.ebx.connect.spi.Operation op:getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = op.getKind() == com.on.ebx.connect.spi.Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b;
	}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition property(final String n,final com.on.ebx.connect.spi.ConfigurationPropertyType t,final boolean r){return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n,t).required(r).build();}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition enumeration(final String n,final String...v){return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n,com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).enumerationValues(v).build();}

	@Override
	public List<Operation> getOperations()
	{
		return DatabricksConnector.OPERATIONS;
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Accept", "application/json");
		headers.put("Authorization", "Bearer " + ("oauth".equalsIgnoreCase(this.getConfig().getProperty("auth", "token"))
				? this.oauthToken : this.getConfig().getApiKey()));
		return headers;
	}

	private void authenticate() throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		// TO_BE_SET / A_RENSEIGNER / empty: say so here instead of sending it to Databricks (HTTP 400 "not a valid endpoint id")
		if (cfg.isPlaceholder(cfg.getProperty("warehouse_id", null)))
		{
			throw new ConnectorException("Databricks: set the property warehouse_id (SQL warehouse)");
		}
		if (cfg.isPlaceholder(cfg.getApiKey()))
		{
			throw new ConnectorException("Databricks: set apiKey (personal access token, or the service principal secret with auth=oauth)");
		}
		if (!"oauth".equalsIgnoreCase(cfg.getProperty("auth", "token")))
		{
			return;
		}
		if (this.oauthToken != null && System.currentTimeMillis() < this.oauthExpiry)
		{
			return;
		}
		final String clientId = cfg.getProperty("clientId", null);
		if (clientId == null)
		{
			throw new ConnectorException("Databricks OAuth: set the property clientId (service principal application id)");
		}
		final HttpJsonClient client = new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(), "EBX-Connectors/1.0 (databricks)");
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/x-www-form-urlencoded");
		headers.put("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
			(clientId + ":" + cfg.getApiKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		try
		{
			final HttpJsonClient.Response response = client.send("POST", HttpJsonClient.buildUrl(cfg.getBaseUrl(), "/oidc/v1/token", null),
				headers, "grant_type=client_credentials&scope=all-apis");
			final JsonNode token = Json.at(response.getBody(), "access_token");
			if (!response.isSuccess() || token == null)
			{
				throw new ConnectorException("Databricks OAuth failed (HTTP " + response.getStatus() + "): "
						+ String.valueOf(Json.at(response.getBody(), "error_description")), response.getStatus(), null);
			}
			this.oauthToken = token.asText();
			final JsonNode expires = Json.at(response.getBody(), "expires_in");
			this.oauthExpiry = System.currentTimeMillis() + (expires == null ? 3000L : Math.max(60L, expires.asLong() - 60L)) * 1000L;
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Databricks OAuth failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Runs {@code SELECT 1} on the configured SQL warehouse through the Statement Execution API: checks the base URL, the
	 * token (or OAuth client) and the warehouse in one call. The generic REST test sent HEAD to the bare workspace URL, which
	 * Databricks answers 404 even when the connection works.
	 */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.nanoTime();
		try
		{
			this.authenticate();
			this.statement("SELECT 1", null, 1);
			return ConnectionTestResult.success("SQL warehouse " + this.getConfig().getProperty("warehouse_id", "") + " answered SELECT 1",
				(System.nanoTime() - started) / 1000000L);
		}
		catch (final ConnectorException ex)
		{
			return ConnectionTestResult.failure(ex.getMessage(), (System.nanoTime() - started) / 1000000L);
		}
	}

	// ------------------------------------------------------------------ operations

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.authenticate();
		final int max = DatabricksConnector.toInt(pInputs == null ? null : pInputs.get("max"),
			DatabricksConnector.toInt(this.getConfig().getProperty("max.rows", "1000"), 1000));
		if ("query".equals(pOperation) || "execute".equals(pOperation))
		{
			return this.statement(AbstractRestConnector.require(pInputs, "sql"), DatabricksConnector.bindings(pInputs), max);
		}
		if ("lookup".equals(pOperation))
		{
			final Map<String, String> binds = new LinkedHashMap<String, String>();
			binds.put("b1", AbstractRestConnector.require(pInputs, "value"));
			final JsonNode result = this.statement("SELECT * FROM " + DatabricksConnector.identifier(AbstractRestConnector.require(pInputs, "table"))
					+ " WHERE " + DatabricksConnector.identifier(AbstractRestConnector.require(pInputs, "keyColumn")) + " = :b1 LIMIT 1", binds, 1);
			final JsonNode rows = Json.at(result, "rows");
			return rows == null || rows.size() == 0 ? null : rows.get(0);
		}
		if ("describe".equals(pOperation))
		{
			return this.statement("DESCRIBE TABLE " + DatabricksConnector.identifier(AbstractRestConnector.require(pInputs, "table")), null, 1000);
		}
		if ("catalog".equals(pOperation) || "profile".equals(pOperation) || "sample".equals(pOperation) || "usage".equals(pOperation) || "lineage".equals(pOperation))
		{
			final int catalogMax = Math.max(max, 10000);
			final com.on.ebx.connect.core.CatalogOperations.SqlRunner runner = new com.on.ebx.connect.core.CatalogOperations.SqlRunner()
			{
				@Override
				public JsonNode run(final String pSql) throws ConnectorException
				{
					return DatabricksConnector.this.statement(pSql, null, catalogMax);
				}
			};
			final com.on.ebx.connect.core.CatalogOperations.Dialect dialect = new DatabricksDialect(this.getConfig().getProperty("catalog", null));
			final String schema = this.getConfig().getProperty("schema", null);
			switch (pOperation)
			{
				case "catalog": return com.on.ebx.connect.core.CatalogOperations.catalog(runner, dialect, pInputs, schema, this.getConfig().getId());
				case "profile": return com.on.ebx.connect.core.CatalogOperations.profile(runner, dialect, pInputs, schema, new java.util.function.Function<String, String>()
				{
					@Override
					public String apply(final String pName)
					{
						return DatabricksConnector.this.getConfig().getProperty(pName, null);
					}
				});
				case "sample": return com.on.ebx.connect.core.CatalogOperations.sample(runner, dialect, pInputs, schema);
				case "usage": return com.on.ebx.connect.core.CatalogOperations.usage(runner, dialect, pInputs);
				default: return com.on.ebx.connect.core.CatalogOperations.lineage(runner, dialect, pInputs);
			}
		}
		if ("merge".equals(pOperation) || "insert".equals(pOperation))
		{
			if (pPayload == null || !pPayload.isObject() || pPayload.size() == 0)
			{
				throw new ConnectorException("Databricks " + pOperation + ": the outbound document is empty");
			}
			final String table = DatabricksConnector.identifier(AbstractRestConnector.require(pInputs, "table"));
			final Map<String, String> binds = new LinkedHashMap<String, String>();
			final String nested = this.getConfig().getProperty("nested.function", "parse_json");
			final String sql = "merge".equals(pOperation)
					? DatabricksConnector.mergeSql(table, AbstractRestConnector.require(pInputs, "keyColumn"), (ObjectNode) pPayload, binds, nested)
					: DatabricksConnector.insertSql(table, (ObjectNode) pPayload, binds, nested);
			return this.statement(sql, binds, 1);
		}
		throw new ConnectorException("Databricks: unknown operation '" + pOperation + "'");
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		final String table = pInputs == null ? null : pInputs.get("table");
		if (table == null || table.trim().isEmpty())
		{
			return fields;
		}
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("table", table);
		final JsonNode rows = Json.at(this.execute(pContext, "describe", inputs, null), "rows");
		if (rows == null)
		{
			return fields;
		}
		final String prefix = "query".equals(pOperation) ? "rows[0]." : "";
		for (final JsonNode row : rows)
		{
			final JsonNode name = row.get("col_name");
			if (name == null || name.asText().isEmpty() || name.asText().startsWith("#"))
			{
				continue; // partition and detail sections of DESCRIBE
			}
			fields.add(FieldInfo.of(prefix + name.asText(), name.asText(), row.get("data_type") == null ? "" : row.get("data_type").asText()));
		}
		return fields;
	}

	// ------------------------------------------------------------------ Statement Execution API

	/**
	 * Databricks (Unity Catalog) SQL for the data-catalog operations. Names are {@code schema.table}
	 * inside the connector's catalog (property {@code catalog}); the system tables give
	 * {@code catalog.schema.table} and are cut down to the catalog. {@code system.access.*} needs
	 * the system schema enabled and SELECT on it.
	 */
	public static final class DatabricksDialect implements com.on.ebx.connect.core.CatalogOperations.Dialect
	{
		private final String catalog;

		public DatabricksDialect(final String pCatalog)
		{
			this.catalog = pCatalog == null ? null : pCatalog.trim();
		}

		private String infoSchema()
		{
			return this.infoSchema(null);
		}

		/** The information schema of {@code pCatalog}, or of the connector's own catalog when it is null. */
		private String infoSchema(final String pCatalog)
		{
			final String c = pCatalog == null || pCatalog.trim().isEmpty() ? this.catalog : pCatalog;
			return c == null ? "information_schema" : DatabricksConnector.identifier(c) + ".information_schema";
		}

		@Override public String quote(final String pIdentifier)
		{
			return DatabricksConnector.identifier(pIdentifier);
		}

		@Override public String schemasSql()
		{
			return "SELECT schema_name AS SCHEMA_NAME FROM " + this.infoSchema() + ".schemata WHERE schema_name NOT IN ('information_schema') ORDER BY 1";
		}

		@Override public String tablesSql(final String pDatabase, final List<String> pSchemas)
		{
			return "SELECT table_schema AS TABLE_SCHEMA, table_name AS TABLE_NAME, table_type AS TABLE_TYPE, comment AS TABLE_COMMENT, NULL AS ROW_COUNT FROM "
				+ this.infoSchema(pDatabase) + ".tables WHERE table_schema IN (" + DatabricksDialect.in(pSchemas) + ") ORDER BY 1, 2";
		}

		@Override public String columnsSql(final String pDatabase, final List<String> pSchemas)
		{
			return "SELECT table_schema AS TABLE_SCHEMA, table_name AS TABLE_NAME, column_name AS COLUMN_NAME, ordinal_position AS ORDINAL_POSITION, "
				+ "full_data_type AS DATA_TYPE, is_nullable AS IS_NULLABLE, comment AS COLUMN_COMMENT FROM " + this.infoSchema(pDatabase)
				+ ".columns WHERE table_schema IN (" + DatabricksDialect.in(pSchemas) + ") ORDER BY 1, 2, 4";
		}

		@Override public String usageSql()
		{
			return "SELECT source_table_full_name AS OBJECT_NAME, COUNT(DISTINCT COALESCE(entity_run_id, CAST(event_time AS STRING))) AS QUERY_COUNT "
				+ "FROM system.access.table_lineage WHERE event_time >= current_timestamp() - INTERVAL 30 DAYS AND source_table_full_name IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 5000";
		}

		@Override public String lineageSql()
		{
			return "SELECT source_table_full_name AS SOURCE_TABLE, source_column_name AS SOURCE_COLUMN, target_table_full_name AS TARGET_TABLE, target_column_name AS TARGET_COLUMN, "
				+ "MAX(event_time) AS SEEN_AT FROM system.access.column_lineage WHERE event_time >= current_timestamp() - INTERVAL 30 DAYS "
				+ "AND source_table_full_name IS NOT NULL AND target_table_full_name IS NOT NULL GROUP BY 1, 2, 3, 4 LIMIT 20000";
		}

		@Override public String localName(final String pFullName)
		{
			if (pFullName == null) return null;
			final String[] parts = pFullName.split("\\.");
			if (parts.length == 3)
			{
				if (this.catalog != null && !this.catalog.equalsIgnoreCase(parts[0])) return null;
				return parts[1] + "." + parts[2];
			}
			return pFullName;
		}

		@Override public String length(final String pColumn) { return "LENGTH(" + pColumn + ")"; }
		@Override public String toDouble(final String pColumn) { return "CAST(" + pColumn + " AS DOUBLE)"; }
		@Override public String limit(final int pRows) { return "LIMIT " + pRows; }
		@Override public String sampleClause(final double pPercent) { return "TABLESAMPLE (" + com.on.ebx.connect.core.ProfilePolicy.percentText(pPercent) + " PERCENT)"; }
		@Override public String approxDistinct(final String pColumn) { return "approx_count_distinct(" + pColumn + ")"; }

		private static String in(final List<String> pValues)
		{
			final StringBuilder sb = new StringBuilder();
			for (final String v : pValues) sb.append(sb.length() > 0 ? ", " : "").append('\'').append(v.replace("'", "''")).append('\'');
			return sb.toString();
		}
	}

	private JsonNode statement(final String pSql, final Map<String, String> pBindings, final int pMax) throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		final ObjectNode body = Json.object();
		body.put("warehouse_id", cfg.getProperty("warehouse_id", ""));
		body.put("statement", pSql);
		body.put("wait_timeout", Math.min(50, Math.max(5, DatabricksConnector.toInt(cfg.getProperty("wait", "30"), 30))) + "s");
		body.put("on_wait_timeout", "CONTINUE");
		body.put("disposition", "INLINE");
		body.put("format", "JSON_ARRAY");
		if (cfg.getProperty("catalog", null) != null)
		{
			body.put("catalog", cfg.getProperty("catalog", null));
		}
		if (cfg.getProperty("schema", null) != null)
		{
			body.put("schema", cfg.getProperty("schema", null));
		}
		if (pBindings != null && !pBindings.isEmpty())
		{
			final ArrayNode parameters = body.putArray("parameters");
			for (final Map.Entry<String, String> bind : pBindings.entrySet())
			{
				final ObjectNode p = parameters.addObject();
				p.put("name", bind.getKey());
				p.put("value", bind.getValue());
				p.put("type", "STRING");
			}
		}
		HttpJsonClient.Response response = this.send("POST", "/api/2.0/sql/statements", null, body);
		final long deadline = System.currentTimeMillis() + Math.max(cfg.getReadTimeoutMs(), 60000);
		while (response.isSuccess() && DatabricksConnector.isRunning(response.getBody()) && System.currentTimeMillis() < deadline)
		{
			final JsonNode id = Json.at(response.getBody(), "statement_id");
			if (id == null)
			{
				break;
			}
			try
			{
				Thread.sleep(2000L);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				break;
			}
			response = this.get("/api/2.0/sql/statements/" + AbstractRestConnector.segment(id.asText()), null);
		}
		if (!response.isSuccess())
		{
			final JsonNode message = Json.at(response.getBody(), "message");
			throw new ConnectorException("Databricks: HTTP " + response.getStatus() + " " + (message == null ? response.getRawBody() : message.asText()),
				response.getStatus(), null);
		}
		final JsonNode state = Json.at(response.getBody(), "status.state");
		if (state != null && !"SUCCEEDED".equals(state.asText()))
		{
			throw new ConnectorException("Databricks: statement " + state.asText() + " " + String.valueOf(Json.at(response.getBody(), "status.error.message")));
		}
		return this.collect(response.getBody(), pMax);
	}

	private static boolean isRunning(final JsonNode pBody)
	{
		final JsonNode state = Json.at(pBody, "status.state");
		return state != null && ("PENDING".equals(state.asText()) || "RUNNING".equals(state.asText()));
	}

	private JsonNode collect(final JsonNode pFirst, final int pMax) throws ConnectorException
	{
		final ObjectNode result = Json.object();
		final ArrayNode columns = result.putArray("columns");
		final ArrayNode rows = result.putArray("rows");
		final List<String> names = new ArrayList<String>();
		final List<String> types = new ArrayList<String>();
		final JsonNode schema = Json.at(pFirst, "manifest.schema.columns");
		if (schema != null)
		{
			for (final JsonNode column : schema)
			{
				names.add(column.get("name").asText());
				types.add(column.get("type_name") == null ? "STRING" : column.get("type_name").asText().toUpperCase(Locale.ROOT));
				final ObjectNode c = columns.addObject();
				c.put("name", column.get("name").asText());
				c.put("type", column.get("type_text") == null ? types.get(types.size() - 1) : column.get("type_text").asText());
			}
		}
		DatabricksConnector.appendRows(rows, Json.at(pFirst, "result.data_array"), names, types, pMax);
		JsonNode next = Json.at(pFirst, "result.next_chunk_internal_link");
		while (next != null && rows.size() < pMax)
		{
			final HttpJsonClient.Response chunk = this.get(next.asText(), null);
			if (!chunk.isSuccess())
			{
				break;
			}
			DatabricksConnector.appendRows(rows, Json.at(chunk.getBody(), "data_array"), names, types, pMax);
			next = Json.at(chunk.getBody(), "next_chunk_internal_link");
		}
		result.put("count", rows.size());
		final JsonNode total = Json.at(pFirst, "manifest.total_row_count");
		if (total != null)
		{
			result.put("totalRowCount", total.asLong());
		}
		return result;
	}

	private static void appendRows(final ArrayNode pRows, final JsonNode pData, final List<String> pNames, final List<String> pTypes, final int pMax)
	{
		if (pData == null || !pData.isArray())
		{
			return;
		}
		for (final JsonNode row : pData)
		{
			if (pRows.size() >= pMax)
			{
				return;
			}
			final ObjectNode out = pRows.addObject();
			for (int i = 0; i < pNames.size() && i < row.size(); i++)
			{
				out.set(pNames.get(i), DatabricksConnector.convert(pTypes.get(i), row.get(i)));
			}
		}
	}

	/** JSON_ARRAY rows carry strings: give them back their type from the manifest. */
	static JsonNode convert(final String pType, final JsonNode pValue)
	{
		if (pValue == null || pValue.isNull())
		{
			return com.fasterxml.jackson.databind.node.NullNode.getInstance();
		}
		final String text = pValue.asText();
		try
		{
			// whole numbers stay whole and decimals keep their scale: the default JSON node factory strips trailing zeros,
			// which turned 2010 into 2.01E+3 (and broke key predicates such as ./identifier=${id})
			if ("INT".equals(pType) || "LONG".equals(pType) || "SHORT".equals(pType) || "BYTE".equals(pType))
			{
				return com.fasterxml.jackson.databind.node.BigIntegerNode.valueOf(new BigInteger(text.trim()));
			}
			if ("DECIMAL".equals(pType) || "DOUBLE".equals(pType) || "FLOAT".equals(pType))
			{
				return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new BigDecimal(text.trim()));
			}
			if ("BOOLEAN".equals(pType))
			{
				return Json.fromJava(Boolean.valueOf("true".equalsIgnoreCase(text)));
			}
			if ("TIMESTAMP".equals(pType) || "TIMESTAMP_NTZ".equals(pType))
			{
				// 2024-01-01T10:20:30.123Z or 2024-01-01 10:20:30 -> ISO seconds
				String iso = text.replace(' ', 'T');
				if (iso.length() > 19)
				{
					iso = iso.substring(0, 19);
				}
				return Json.fromJava(iso);
			}
			if ("STRUCT".equals(pType) || "ARRAY".equals(pType) || "MAP".equals(pType) || "VARIANT".equals(pType))
			{
				final JsonNode parsed = Json.parse(text);
				return parsed == null ? pValue : parsed;
			}
		}
		catch (final Exception ex)
		{
			return pValue;
		}
		return pValue;
	}

	// ------------------------------------------------------------------ SQL building (named markers)

	static String mergeSql(final String pTable, final String pKeyColumns, final ObjectNode pDocument, final Map<String, String> pBindings,
		final String pNestedFunction)
	{
		final List<String> keys = new ArrayList<String>();
		for (final String k : pKeyColumns.split(","))
		{
			if (!k.trim().isEmpty())
			{
				keys.add(DatabricksConnector.identifier(k.trim()));
			}
		}
		final List<String> columns = new ArrayList<String>();
		final List<String> selects = new ArrayList<String>();
		DatabricksConnector.columnsAndValues(pDocument, pBindings, pNestedFunction, columns, selects, true);
		final StringBuilder sql = new StringBuilder("MERGE INTO ").append(pTable).append(" t USING (SELECT ")
				.append(DatabricksConnector.join(selects, ", ")).append(") s ON ");
		for (int i = 0; i < keys.size(); i++)
		{
			sql.append(i > 0 ? " AND " : "").append("t.").append(keys.get(i)).append(" = s.").append(keys.get(i));
		}
		final List<String> updates = new ArrayList<String>();
		for (final String column : columns)
		{
			if (!keys.contains(column))
			{
				updates.add("t." + column + " = s." + column);
			}
		}
		if (!updates.isEmpty())
		{
			sql.append(" WHEN MATCHED THEN UPDATE SET ").append(DatabricksConnector.join(updates, ", "));
		}
		final List<String> sources = new ArrayList<String>();
		for (final String column : columns)
		{
			sources.add("s." + column);
		}
		sql.append(" WHEN NOT MATCHED THEN INSERT (").append(DatabricksConnector.join(columns, ", ")).append(") VALUES (")
				.append(DatabricksConnector.join(sources, ", ")).append(")");
		return sql.toString();
	}

	static String insertSql(final String pTable, final ObjectNode pDocument, final Map<String, String> pBindings, final String pNestedFunction)
	{
		final List<String> columns = new ArrayList<String>();
		final List<String> values = new ArrayList<String>();
		DatabricksConnector.columnsAndValues(pDocument, pBindings, pNestedFunction, columns, values, false);
		return "INSERT INTO " + pTable + " (" + DatabricksConnector.join(columns, ", ") + ") SELECT " + DatabricksConnector.join(values, ", ");
	}

	private static void columnsAndValues(final ObjectNode pDocument, final Map<String, String> pBindings, final String pNestedFunction,
		final List<String> pColumns, final List<String> pValues, final boolean pAlias)
	{
		final Iterator<Map.Entry<String, JsonNode>> fields = pDocument.fields();
		int n = 1;
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			final String column = DatabricksConnector.identifier(field.getKey());
			pColumns.add(column);
			final JsonNode value = field.getValue();
			final String alias = pAlias ? " AS " + column : "";
			if (value == null || value.isNull())
			{
				pValues.add("NULL" + alias);
				continue;
			}
			final String marker = ":b" + n;
			pBindings.put("b" + n, value.isValueNode() ? value.asText() : Json.write(value));
			pValues.add((value.isValueNode() || pNestedFunction.isEmpty() ? marker : pNestedFunction + "(" + marker + ")") + alias);
			n++;
		}
	}

	private static Map<String, String> bindings(final Map<String, String> pInputs)
	{
		final Map<String, String> binds = new LinkedHashMap<String, String>();
		if (pInputs == null)
		{
			return binds;
		}
		for (int i = 1; i <= 50; i++)
		{
			final String value = pInputs.get("b" + i);
			if (value == null)
			{
				break;
			}
			binds.put("b" + i, value);
		}
		return binds;
	}

	static String identifier(final String pName)
	{
		return pName.trim().replaceAll("[^A-Za-z0-9_.`]", "");
	}

	private static String join(final List<String> pParts, final String pSeparator)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String part : pParts)
		{
			sb.append(sb.length() > 0 ? pSeparator : "").append(part);
		}
		return sb.toString();
	}

	private static int toInt(final String pValue, final int pDefault)
	{
		try
		{
			return Integer.parseInt(pValue.trim());
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}
}
