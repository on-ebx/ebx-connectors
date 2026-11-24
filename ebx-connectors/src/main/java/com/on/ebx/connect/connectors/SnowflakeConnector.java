package com.on.ebx.connect.connectors;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.TablePublisher;

/**
 * Snowflake through the SQL REST API ({@code POST /api/v2/statements}), no JDBC driver.
 * <p>
 * Connector row: {@code baseUrl} = {@code https://<account>.snowflakecomputing.com}; authentication
 * BEARER with {@code apiKey} = a programmatic access token (property {@code tokenType=PAT}, default)
 * or an OAuth token ({@code tokenType=OAUTH}); or key-pair: property {@code auth=keypair},
 * {@code username} = the Snowflake user, {@code password} = the unencrypted PKCS#8 private key
 * (PEM), property {@code account} = account identifier as used in the JWT issuer. Context
 * properties: {@code warehouse}, {@code database}, {@code schema}, {@code role}, {@code timeout}
 * (seconds), {@code max.rows}.
 * <p>
 * Documents: a query returns {@code {columns:[...], rows:[{COL: value, ...}], count}} with typed
 * values (numbers, booleans, ISO dates and timestamps, VARIANT parsed as JSON), so an inbound
 * mapping reads {@code rows[0].NAME} and an import feed uses {@code itemsPath = rows}. Outbound,
 * {@code merge} and {@code insert} write the document's top-level fields as columns; nested objects
 * and arrays (sub-object mappings) go to VARIANT columns through {@code PARSE_JSON}.
 */
public final class SnowflakeConnector extends AbstractRestConnector implements TablePublisher
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("query", "Run a SELECT (input sql, optional bindings b1..bn) and return its rows", "sql"),
		Operation.read("lookup", "First row of a table where keyColumn = value", "table", "keyColumn", "value"),
		Operation.write("merge", "MERGE the outbound document into a table on keyColumn(s)", "table", "keyColumn"),
		Operation.write("insert", "INSERT the outbound document into a table", "table"),
		Operation.write("execute", "Run any statement (input sql, optional bindings b1..bn)", "sql"),
		Operation.read("describe", "Columns of a table", "table"),
		Operation.search("catalog", "Data catalog inventory: schemas, tables, views, columns from INFORMATION_SCHEMA (inputs source, schemas - DB.SCHEMA for another database, types)", "source"),
		Operation.search("profile", "Data catalog column statistics, one aggregate query per table (inputs source, schemas, tables, where; budget and cost: maxTables, maxSeconds, largeTableRows, largeTables = sample|exact|skip, sampleRows, approxDistinct, dryRun = plan only)", "source"),
		Operation.search("sample", "Data catalog value samples of the text columns (inputs source, schemas, tables, limit)", "source"),
		Operation.search("usage", "Data catalog popularity: reads per table over 30 days from SNOWFLAKE.ACCOUNT_USAGE.ACCESS_HISTORY (input source)", "source"),
		Operation.search("lineage", "Data catalog column lineage over 30 days from SNOWFLAKE.ACCOUNT_USAGE.ACCESS_HISTORY objects_modified (input source)", "source"));

	private String jwt;
	private long jwtExpiry;

	@Override
	public String getType()
	{
		return "snowflake";
	}

	@Override
	public String getLabel()
	{
		return "Snowflake (SQL REST API)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("DATA_PLATFORMS");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN).authentication(com.on.ebx.connect.spi.AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS).authentication(com.on.ebx.connect.spi.AuthenticationMethod.CERTIFICATE).capability(com.on.ebx.connect.spi.ConnectorCapability.DESCRIBE_OBJECT).capability(com.on.ebx.connect.spi.ConnectorCapability.SCHEMA_DISCOVERY).apiVersion("Snowflake SQL API v2").pagination(new com.on.ebx.connect.spi.PaginationDescriptor(com.on.ebx.connect.spi.PaginationDescriptor.Strategy.TOKEN, 1000, 10000, "partitionInfo")).configurationProperty(property("account", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(enumeration("auth", "token", "keypair")).configurationProperty(property("max.rows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.maxTables", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.maxSeconds", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.largeTableRows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.sampleRows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("profile.approxDistinct", com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN, false)).configurationProperty(enumeration("profile.largeTables", "sample", "exact", "skip")).configurationProperty(property("timeout", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(enumeration("tokenType", "PAT", "OAUTH")).configurationProperty(property("publish.batchSize", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("semanticView.enabled", com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN, false)).configurationProperty(property("listing.enabled", com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN, false)).configurationProperty(property("listing.supportContact", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("listing.region", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false));
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
		return SnowflakeConnector.OPERATIONS;
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		final ConnectorConfig cfg = this.getConfig();
		headers.put("Accept", "application/json");
		headers.put("User-Agent", "EBX-Connectors/1.0");
		if ("keypair".equalsIgnoreCase(cfg.getProperty("auth", "token")))
		{
			headers.put("Authorization", "Bearer " + this.jwt);
			headers.put("X-Snowflake-Authorization-Token-Type", "KEYPAIR_JWT");
		}
		else
		{
			headers.put("Authorization", "Bearer " + cfg.getApiKey());
			headers.put("X-Snowflake-Authorization-Token-Type",
				"OAUTH".equalsIgnoreCase(cfg.getProperty("tokenType", "PAT")) ? "OAUTH" : "PROGRAMMATIC_ACCESS_TOKEN");
		}
		return headers;
	}

	private void authenticate() throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		if ("keypair".equalsIgnoreCase(cfg.getProperty("auth", "token")))
		{
			if (this.jwt == null || System.currentTimeMillis() > this.jwtExpiry)
			{
				this.jwt = SnowflakeConnector.keyPairJwt(cfg.getProperty("account", ""), cfg.getUsername(), cfg.getPassword());
				this.jwtExpiry = System.currentTimeMillis() + 50L * 60L * 1000L;
			}
		}
		else if (cfg.isPlaceholder(cfg.getApiKey()))
		{
			throw new ConnectorException("Snowflake: set the token in apiKey (PAT or OAuth) or use key-pair authentication (property auth=keypair)");
		}
	}

	// ------------------------------------------------------------------ operations

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.authenticate();
		final int max = SnowflakeConnector.toInt(pInputs == null ? null : pInputs.get("max"),
			SnowflakeConnector.toInt(this.getConfig().getProperty("max.rows", "1000"), 1000));
		if ("query".equals(pOperation) || "execute".equals(pOperation))
		{
			return this.statement(AbstractRestConnector.require(pInputs, "sql"), SnowflakeConnector.bindings(pInputs), max);
		}
		if ("lookup".equals(pOperation))
		{
			final String sql = "SELECT * FROM " + SnowflakeConnector.identifier(AbstractRestConnector.require(pInputs, "table"))
					+ " WHERE " + SnowflakeConnector.identifier(AbstractRestConnector.require(pInputs, "keyColumn")) + " = ? LIMIT 1";
			final Map<String, String> binds = new LinkedHashMap<String, String>();
			binds.put("1", AbstractRestConnector.require(pInputs, "value"));
			final JsonNode result = this.statement(sql, binds, 1);
			final JsonNode rows = Json.at(result, "rows");
			return rows == null || rows.size() == 0 ? null : rows.get(0);
		}
		if ("describe".equals(pOperation))
		{
			return this.statement("DESCRIBE TABLE " + SnowflakeConnector.identifier(AbstractRestConnector.require(pInputs, "table")), null, 1000);
		}
		if ("catalog".equals(pOperation) || "profile".equals(pOperation) || "sample".equals(pOperation) || "usage".equals(pOperation) || "lineage".equals(pOperation))
		{
			final int catalogMax = Math.max(max, 10000);
			final com.on.ebx.connect.core.CatalogOperations.SqlRunner runner = new com.on.ebx.connect.core.CatalogOperations.SqlRunner()
			{
				@Override
				public JsonNode run(final String pSql) throws ConnectorException
				{
					return SnowflakeConnector.this.statement(pSql, null, catalogMax);
				}
			};
			final com.on.ebx.connect.core.CatalogOperations.Dialect dialect = new SnowflakeDialect(this.getConfig().getProperty("database", null));
			final String schema = this.getConfig().getProperty("schema", null);
			switch (pOperation)
			{
				case "catalog": return com.on.ebx.connect.core.CatalogOperations.catalog(runner, dialect, pInputs, schema, this.getConfig().getId());
				case "profile": return com.on.ebx.connect.core.CatalogOperations.profile(runner, dialect, pInputs, schema, new java.util.function.Function<String, String>()
				{
					@Override
					public String apply(final String pName)
					{
						return SnowflakeConnector.this.getConfig().getProperty(pName, null);
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
				throw new ConnectorException("Snowflake " + pOperation + ": the outbound document is empty");
			}
			final String table = SnowflakeConnector.identifier(AbstractRestConnector.require(pInputs, "table"));
			final Map<String, String> binds = new LinkedHashMap<String, String>();
			final String sql = "merge".equals(pOperation)
					? SnowflakeConnector.mergeSql(table, AbstractRestConnector.require(pInputs, "keyColumn"), (ObjectNode) pPayload, binds)
					: SnowflakeConnector.insertSql(table, (ObjectNode) pPayload, binds);
			return this.statement(sql, binds, 1);
		}
		throw new ConnectorException("Snowflake: unknown operation '" + pOperation + "'");
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
		final JsonNode described = this.execute(pContext, "describe", inputs, null);
		final JsonNode rows = Json.at(described, "rows");
		if (rows == null)
		{
			return fields;
		}
		final String prefix = "query".equals(pOperation) ? "rows[0]." : "";
		for (final JsonNode row : rows)
		{
			final JsonNode name = row.get("name");
			if (name != null)
			{
				fields.add(FieldInfo.of(prefix + name.asText(), name.asText(), row.get("type") == null ? "" : row.get("type").asText()));
			}
		}
		return fields;
	}

	// ------------------------------------------------------------------ SQL API

	/**
	 * Snowflake SQL for the data-catalog operations. Names are {@code SCHEMA.TABLE} inside the
	 * connector's database (property {@code database}); a schema requested as {@code DB.SCHEMA} is
	 * read from that database's INFORMATION_SCHEMA instead and keeps the prefix. ACCOUNT_USAGE objects come as
	 * {@code DB.SCHEMA.TABLE} and are cut down to the database. ACCOUNT_USAGE needs the
	 * IMPORTED PRIVILEGES on the SNOWFLAKE database and lags by up to 3 hours.
	 */
	public static final class SnowflakeDialect implements com.on.ebx.connect.core.CatalogOperations.Dialect
	{
		private final String database;

		public SnowflakeDialect(final String pDatabase)
		{
			this.database = pDatabase == null ? null : pDatabase.trim().toUpperCase(java.util.Locale.ENGLISH);
		}

		@Override public String quote(final String pIdentifier)
		{
			return SnowflakeConnector.identifier(pIdentifier);
		}

		private String infoSchema(final String pDatabase)
		{
			return pDatabase == null || pDatabase.trim().isEmpty() ? "INFORMATION_SCHEMA"
				: SnowflakeConnector.identifier(pDatabase) + ".INFORMATION_SCHEMA";
		}

		@Override public String schemasSql()
		{
			return "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME NOT IN ('INFORMATION_SCHEMA') ORDER BY 1";
		}

		@Override public String tablesSql(final String pDatabase, final List<String> pSchemas)
		{
			return "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, COMMENT AS TABLE_COMMENT, ROW_COUNT FROM " + this.infoSchema(pDatabase) + ".TABLES WHERE TABLE_SCHEMA IN ("
				+ SnowflakeDialect.in(pSchemas) + ") ORDER BY 1, 2";
		}

		@Override public String columnsSql(final String pDatabase, final List<String> pSchemas)
		{
			return "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, IS_NULLABLE, COMMENT AS COLUMN_COMMENT FROM " + this.infoSchema(pDatabase) + ".COLUMNS WHERE TABLE_SCHEMA IN ("
				+ SnowflakeDialect.in(pSchemas) + ") ORDER BY 1, 2, 4";
		}

		@Override public String usageSql()
		{
			return "SELECT f.value:objectName::string AS OBJECT_NAME, COUNT(DISTINCT ah.query_id) AS QUERY_COUNT FROM SNOWFLAKE.ACCOUNT_USAGE.ACCESS_HISTORY ah, "
				+ "LATERAL FLATTEN(input => ah.base_objects_accessed) f WHERE ah.query_start_time >= DATEADD(day, -30, CURRENT_TIMESTAMP()) "
				+ "AND f.value:objectDomain::string = 'Table' GROUP BY 1 ORDER BY 2 DESC LIMIT 5000";
		}

		@Override public String lineageSql()
		{
			return "SELECT s.value:objectName::string AS SOURCE_TABLE, s.value:columnName::string AS SOURCE_COLUMN, om.value:objectName::string AS TARGET_TABLE, "
				+ "c.value:columnName::string AS TARGET_COLUMN, MAX(ah.query_start_time) AS SEEN_AT FROM SNOWFLAKE.ACCOUNT_USAGE.ACCESS_HISTORY ah, "
				+ "LATERAL FLATTEN(input => ah.objects_modified) om, LATERAL FLATTEN(input => om.value:columns) c, LATERAL FLATTEN(input => c.value:directSources) s "
				+ "WHERE ah.query_start_time >= DATEADD(day, -30, CURRENT_TIMESTAMP()) GROUP BY 1, 2, 3, 4 LIMIT 20000";
		}

		@Override public String localName(final String pFullName)
		{
			if (pFullName == null) return null;
			final String[] parts = pFullName.split("\\.");
			if (parts.length == 3)
			{
				if (this.database != null && !this.database.equalsIgnoreCase(parts[0])) return null; // another database: not this source
				return parts[1] + "." + parts[2];
			}
			return pFullName;
		}

		@Override public String length(final String pColumn) { return "LENGTH(" + pColumn + ")"; }
		@Override public String toDouble(final String pColumn) { return "CAST(" + pColumn + " AS DOUBLE)"; }
		@Override public String limit(final int pRows) { return "LIMIT " + pRows; }
		/** Block sampling reads a share of the micro-partitions, so it costs a share of the warehouse time (audit C-15). */
		@Override public String sampleClause(final double pPercent) { return "SAMPLE SYSTEM (" + com.on.ebx.connect.core.ProfilePolicy.percentText(pPercent) + ")"; }
		@Override public String approxDistinct(final String pColumn) { return "APPROX_COUNT_DISTINCT(" + pColumn + ")"; }

		private static String in(final List<String> pValues)
		{
			final StringBuilder sb = new StringBuilder();
			for (final String v : pValues) sb.append(sb.length() > 0 ? ", " : "").append('\'').append(v.replace("'", "''")).append('\'');
			return sb.toString();
		}
	}

	/** Runs one statement, waits for it, fetches its partitions, returns {columns, rows, count}. */
	private JsonNode statement(final String pSql, final Map<String, String> pBindings, final int pMax) throws ConnectorException
	{
		return this.statement(pSql, pBindings, pMax, 0);
	}

	/**
	 * Like {@link #statement(String, Map, int)}, with {@code pMultiStatementCount} > 0 setting the
	 * Snowflake SQL API {@code parameters.MULTI_STATEMENT_COUNT}: {@code pSql} is then several
	 * statements separated by {@code ;} (the atomic publication commit, §2.2).
	 */
	/**
	 * Builds the {@code /api/v2/statements} request body (pure, offline-testable, no HTTP call).
	 * {@code parameters.MULTI_STATEMENT_COUNT} must be sent as a string: the Snowflake SQL API declares
	 * every entry of {@code parameters} as a session-parameter value, which the API rejects as the wrong
	 * type when serialized as a JSON number (Snowflake SQL API reference, POST /api/v2/statements,
	 * {@code parameters} object).
	 */
	static ObjectNode statementBody(final ConnectorConfig pConfig, final String pSql, final Map<String, String> pBindings, final int pMultiStatementCount)
	{
		final ObjectNode body = Json.object();
		body.put("statement", pSql);
		body.put("timeout", SnowflakeConnector.toInt(pConfig.getProperty("timeout", "60"), 60));
		for (final String key : new String[] { "warehouse", "database", "schema", "role" })
		{
			final String value = pConfig.getProperty(key, null);
			if (value != null)
			{
				body.put(key, value);
			}
		}
		final ObjectNode parameters = body.putObject("parameters");
		parameters.put("DATE_OUTPUT_FORMAT", "YYYY-MM-DD");
		parameters.put("TIMESTAMP_OUTPUT_FORMAT", "YYYY-MM-DD\"T\"HH24:MI:SS");
		parameters.put("TIMESTAMP_NTZ_OUTPUT_FORMAT", "YYYY-MM-DD\"T\"HH24:MI:SS");
		parameters.put("TIMESTAMP_LTZ_OUTPUT_FORMAT", "YYYY-MM-DD\"T\"HH24:MI:SS");
		parameters.put("TIMESTAMP_TZ_OUTPUT_FORMAT", "YYYY-MM-DD\"T\"HH24:MI:SS");
		if (pMultiStatementCount > 0)
		{
			parameters.put("MULTI_STATEMENT_COUNT", String.valueOf(pMultiStatementCount));
		}
		if (pBindings != null && !pBindings.isEmpty())
		{
			final ObjectNode bindings = body.putObject("bindings");
			for (final Map.Entry<String, String> bind : pBindings.entrySet())
			{
				final ObjectNode b = bindings.putObject(bind.getKey());
				b.put("type", "TEXT");
				b.put("value", bind.getValue());
			}
		}
		return body;
	}

	private JsonNode statement(final String pSql, final Map<String, String> pBindings, final int pMax, final int pMultiStatementCount) throws ConnectorException
	{
		return this.collect(this.runStatement(pSql, pBindings, pMultiStatementCount), pMax);
	}

	/**
	 * Sends the statement and polls until it is no longer running (volume lot 7b: factored out of
	 * {@link #statement(String, Map, int, int)} so the streaming {@link #readRows} override below can
	 * reuse the exact same submit/poll/error handling - the still-running-at-timeout and non-2xx checks
	 * included - without ever calling {@link #collect}, which is what actually materialises the whole
	 * result into one {@code rows} array). Returns the first response body once the statement has
	 * finished (its {@code resultSetMetaData}/{@code data}/{@code partitionInfo}/{@code statementHandle}
	 * - the same shape {@link #collect} and the new {@link #streamCollect} both read).
	 */
	private JsonNode runStatement(final String pSql, final Map<String, String> pBindings, final int pMultiStatementCount) throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		final ObjectNode body = SnowflakeConnector.statementBody(cfg, pSql, pBindings, pMultiStatementCount);
		HttpJsonClient.Response response = this.send("POST", "/api/v2/statements", null, body);
		final long deadline = System.currentTimeMillis() + cfg.getReadTimeoutMs();
		while (SnowflakeConnector.stillRunning(response.getStatus()) && System.currentTimeMillis() < deadline)
		{
			final JsonNode handle = Json.at(response.getBody(), "statementHandle");
			if (handle == null)
			{
				break;
			}
			try
			{
				Thread.sleep(1500L);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				break;
			}
			response = this.get("/api/v2/statements/" + AbstractRestConnector.segment(handle.asText()), null);
		}
		// HTTP 202 is in isSuccess()'s 2xx range, so a statement still running when the polling loop gave up
		// (read timeout, or a response missing its own statementHandle) must be raised explicitly - it used
		// to fall through to collect() and come back as a silent NO_DATA or partial "success" (audited 2026-09-28).
		if (SnowflakeConnector.stillRunning(response.getStatus()))
		{
			throw new ConnectorException("Snowflake: statement still running after " + cfg.getReadTimeoutMs()
				+ "ms (read timeout); raise the connector's read timeout or the statement's own timeout", response.getStatus(), null);
		}
		if (!response.isSuccess())
		{
			final JsonNode message = Json.at(response.getBody(), "message");
			throw new ConnectorException("Snowflake: HTTP " + response.getStatus() + " " + (message == null ? response.getRawBody() : message.asText()),
				response.getStatus(), null);
		}
		return response.getBody();
	}

	/**
	 * Volume lot 7b: real streaming for the {@code query}/{@code execute} operations - result
	 * partitions are fetched and pushed to {@code pSink} ONE AT A TIME (fetch partition p, push its
	 * rows, drop it, fetch p+1), never held together in one {@code ArrayNode} the way {@link #collect}
	 * does. Every other operation (lookup/describe/catalog/profile/sample/usage/lineage/merge/insert)
	 * falls back to the default (execute + replay) implementation, unchanged.
	 * <p>
	 * {@code max} is honoured exactly like {@link #collect}/{@link #appendRows} did: once {@code pMax}
	 * rows have been delivered, no further partition is fetched and {@code truncated} is reported.
	 * {@code available} comes from {@code resultSetMetaData.numRows} when the Snowflake SQL API reports
	 * it (the whole result set's real total, known from the FIRST response, before a single partition is
	 * even fetched - cheap and exact, unlike JDBC's own probe-row trick) - {@code -1} otherwise. A
	 * partition fetch that fails, or a statement still running past the read timeout, is an error either
	 * way (both go through {@link #runStatement}, unchanged from the non-streamed path - the "a partial
	 * read must never be mistaken for a complete one" fix of 2026-09-28 applies here too). A sink that
	 * returns {@code false} (a batch/circuit-breaker/cancellation stop, never a max-related one - the max
	 * cutoff is enforced here, before the sink ever sees more than {@code pMax} rows) stops fetching
	 * further partitions at once and is reported as an ordinary, non-truncated read of however many rows
	 * were delivered - the caller already knows why it stopped and reports that separately.
	 * <p>
	 * {@code itemsPath} is ignored, like JDBC's native path - each row IS an item, equivalent to the
	 * {@code itemsPath="rows"} convention already used for this connector (the class javadoc, the
	 * catalog scan's own read recipe).
	 */
	@Override
	public Connector.RowStreamResult readRows(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final String pItemsPath, final int pMax, final Connector.RowSink pSink) throws ConnectorException
	{
		if (!"query".equals(pOperation) && !"execute".equals(pOperation))
		{
			return super.readRows(pContext, pOperation, pInputs, pItemsPath, pMax, pSink);
		}
		this.authenticate();
		final JsonNode first = this.runStatement(AbstractRestConnector.require(pInputs, "sql"), SnowflakeConnector.bindings(pInputs), 0);
		return this.streamCollect(first, pMax, pSink);
	}

	/** Column names/types/scales/pivot state a streamed read carries across partitions - never the rows themselves. Package-private for {@code SnowflakeConnectorTest}. */
	static final class StreamState
	{
		final List<String> names = new ArrayList<String>();
		final List<String> types = new ArrayList<String>();
		final List<Integer> scales = new ArrayList<Integer>();
		int delivered;
		/** True once {@code pSink.accept} returned {@code false} - no further partition is fetched, and this is never reported as truncation. */
		boolean sinkStopped;
	}

	/**
	 * Streams {@code pFirst}'s own {@code data} array to {@code pSink}, then, unless the sink already
	 * stopped or {@code pMax} was already reached, fetches and streams each further partition in turn -
	 * exactly one partition ever held in memory at a time. See {@link #readRows} for the max/truncation/
	 * sink-stop contract.
	 */
	private Connector.RowStreamResult streamCollect(final JsonNode pFirst, final int pMax, final Connector.RowSink pSink) throws ConnectorException
	{
		final StreamState state = new StreamState();
		final JsonNode rowType = Json.at(pFirst, "resultSetMetaData.rowType");
		if (rowType != null)
		{
			for (final JsonNode column : rowType)
			{
				state.names.add(column.get("name").asText());
				state.types.add(column.get("type") == null ? "text" : column.get("type").asText().toLowerCase(Locale.ROOT));
				state.scales.add(Integer.valueOf(column.get("scale") == null ? 0 : column.get("scale").asInt()));
			}
		}
		boolean truncated = SnowflakeConnector.streamRows(Json.at(pFirst, "data"), pMax, pSink, state);
		final JsonNode partitions = Json.at(pFirst, "resultSetMetaData.partitionInfo");
		final JsonNode handle = Json.at(pFirst, "statementHandle");
		if (!state.sinkStopped && partitions != null && partitions.isArray() && handle != null)
		{
			int p = 1;
			for (; p < partitions.size() && state.delivered < pMax && !state.sinkStopped; p++)
			{
				final Map<String, String> query = new LinkedHashMap<String, String>();
				query.put("partition", String.valueOf(p));
				final HttpJsonClient.Response part = this.get("/api/v2/statements/" + AbstractRestConnector.segment(handle.asText()), query);
				if (!part.isSuccess())
				{
					// A partial read must never be mistaken for a complete one (same fix as collect(), 2026-09-28).
					final JsonNode message = Json.at(part.getBody(), "message");
					throw new ConnectorException("Snowflake: partition " + p + " of " + partitions.size() + " failed with HTTP "
						+ part.getStatus() + " " + (message == null ? part.getRawBody() : message.asText()), part.getStatus(), null);
				}
				if (SnowflakeConnector.streamRows(Json.at(part.getBody(), "data"), pMax, pSink, state)) truncated = true;
			}
			// Fetching stopped before every partition was read, either at pMax (truncated) or because
			// the sink itself asked to stop (not truncation - see the class javadoc above).
			if (!state.sinkStopped && p < partitions.size()) truncated = true;
		}
		final JsonNode numRows = Json.at(pFirst, "resultSetMetaData.numRows");
		final long available = numRows != null && numRows.isNumber() ? numRows.asLong() : -1;
		return new Connector.RowStreamResult(state.delivered, !state.sinkStopped && truncated, available);
	}

	/**
	 * Streams one partition's {@code data} rows to {@code pSink}, honouring {@code pMax} and an
	 * already-stopped sink; returns {@code true} when this partition itself held more rows than
	 * {@code pMax} let through (mirrors {@link #appendRows}'s own return contract for the materialised
	 * path). Package-private, pure (no HTTP): {@code SnowflakeConnectorTest} exercises it directly,
	 * feeding it partition-shaped {@code data} arrays and a fake sink the same way
	 * {@code ConnectorRowStreamTest} exercises {@code Connector.replay} - the HTTP fetch loop around it
	 * in {@link #streamCollect} is not verified live, same caveat as {@code JdbcConnector}'s own
	 * streaming (see docs/REFERENCE.md).
	 */
	static boolean streamRows(final JsonNode pData, final int pMax, final Connector.RowSink pSink, final StreamState pState)
	{
		if (pData == null || !pData.isArray() || pState.sinkStopped)
		{
			return false;
		}
		for (final JsonNode row : pData)
		{
			if (pState.sinkStopped)
			{
				return false;
			}
			if (pState.delivered >= pMax)
			{
				return true; // this partition held more rows than pMax let through
			}
			final ObjectNode out = Json.object();
			for (int i = 0; i < pState.names.size() && i < row.size(); i++)
			{
				out.set(pState.names.get(i), SnowflakeConnector.convert(pState.types.get(i), pState.scales.get(i).intValue(), row.get(i)));
			}
			pState.delivered++;
			if (!pSink.accept(out))
			{
				pState.sinkStopped = true;
				return false;
			}
		}
		return false;
	}

	/** The Snowflake SQL API keeps a still-executing statement at HTTP 202; pure so the poll-loop exit is unit-tested. */
	static boolean stillRunning(final int pStatus)
	{
		return pStatus == 202;
	}

	private JsonNode collect(final JsonNode pFirst, final int pMax) throws ConnectorException
	{
		final ObjectNode result = Json.object();
		final ArrayNode columns = result.putArray("columns");
		final ArrayNode rows = result.putArray("rows");
		final JsonNode rowType = Json.at(pFirst, "resultSetMetaData.rowType");
		final List<String> names = new ArrayList<String>();
		final List<String> types = new ArrayList<String>();
		final List<Integer> scales = new ArrayList<Integer>();
		if (rowType != null)
		{
			for (final JsonNode column : rowType)
			{
				names.add(column.get("name").asText());
				types.add(column.get("type") == null ? "text" : column.get("type").asText().toLowerCase(Locale.ROOT));
				scales.add(Integer.valueOf(column.get("scale") == null ? 0 : column.get("scale").asInt()));
				final ObjectNode c = columns.addObject();
				c.put("name", column.get("name").asText());
				c.put("type", column.get("type") == null ? "" : column.get("type").asText());
			}
		}
		boolean truncated = SnowflakeConnector.appendRows(rows, Json.at(pFirst, "data"), names, types, scales, pMax);
		final JsonNode partitions = Json.at(pFirst, "resultSetMetaData.partitionInfo");
		final JsonNode handle = Json.at(pFirst, "statementHandle");
		if (partitions != null && partitions.isArray() && handle != null)
		{
			int p = 1;
			for (; p < partitions.size() && rows.size() < pMax; p++)
			{
				final Map<String, String> query = new LinkedHashMap<String, String>();
				query.put("partition", String.valueOf(p));
				final HttpJsonClient.Response part = this.get("/api/v2/statements/" + AbstractRestConnector.segment(handle.asText()), query);
				if (!part.isSuccess())
				{
					// A partial read must never be mistaken for a complete one: this used to "break" and hand back
					// whatever rows had been collected so far as if the read had finished cleanly (audited 2026-09-28).
					final JsonNode message = Json.at(part.getBody(), "message");
					throw new ConnectorException("Snowflake: partition " + p + " of " + partitions.size() + " failed with HTTP "
						+ part.getStatus() + " " + (message == null ? part.getRawBody() : message.asText()), part.getStatus(), null);
				}
				if (SnowflakeConnector.appendRows(rows, Json.at(part.getBody(), "data"), names, types, scales, pMax)) truncated = true;
			}
			// The loop above stops as soon as rows.size() reaches pMax, possibly leaving partitions unfetched:
			// that is also a truncated read, even when the last partition read did not itself overflow pMax.
			if (p < partitions.size()) truncated = true;
		}
		result.put("count", rows.size());
		result.put("truncated", truncated);
		final JsonNode numRows = Json.at(pFirst, "resultSetMetaData.numRows");
		if (numRows != null && numRows.isNumber())
		{
			// Cheap and exact when the API reports it: the whole result set's total, not just this call's rows.
			result.put("available", numRows.asLong());
		}
		final JsonNode message = Json.at(pFirst, "message");
		if (message != null)
		{
			result.put("message", message.asText());
		}
		return result;
	}

	/** Appends up to {@code pMax - pRows.size()} rows; returns true when {@code pData} held more than that. */
	static boolean appendRows(final ArrayNode pRows, final JsonNode pData, final List<String> pNames, final List<String> pTypes,
		final List<Integer> pScales, final int pMax)
	{
		if (pData == null || !pData.isArray())
		{
			return false;
		}
		for (final JsonNode row : pData)
		{
			if (pRows.size() >= pMax)
			{
				return true;
			}
			final ObjectNode out = pRows.addObject();
			for (int i = 0; i < pNames.size() && i < row.size(); i++)
			{
				out.set(pNames.get(i), SnowflakeConnector.convert(pTypes.get(i), pScales.get(i).intValue(), row.get(i)));
			}
		}
		return false;
	}

	/** The SQL API returns every value as a string: give it back its type. */
	static JsonNode convert(final String pType, final int pScale, final JsonNode pValue)
	{
		if (pValue == null || pValue.isNull())
		{
			return com.fasterxml.jackson.databind.node.NullNode.getInstance();
		}
		final String text = pValue.asText();
		try
		{
			if ("fixed".equals(pType) || "real".equals(pType))
			{
				return Json.fromJava(new BigDecimal(text));
			}
			if ("boolean".equals(pType))
			{
				return Json.fromJava(Boolean.valueOf("true".equalsIgnoreCase(text) || "1".equals(text)));
			}
			if ("date".equals(pType) && text.matches("-?[0-9]+"))
			{
				return Json.fromJava(java.time.LocalDate.ofEpochDay(Long.parseLong(text)).toString());
			}
			if (pType.startsWith("timestamp") && text.matches("-?[0-9]+(\\.[0-9]+)?"))
			{
				final long seconds = new BigDecimal(text).longValue();
				return Json.fromJava(java.time.LocalDateTime.ofEpochSecond(seconds, 0, java.time.ZoneOffset.UTC).toString());
			}
			if ("variant".equals(pType) || "object".equals(pType) || "array".equals(pType))
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

	// ------------------------------------------------------------------ SQL building

	static String mergeSql(final String pTable, final String pKeyColumns, final ObjectNode pDocument, final Map<String, String> pBindings)
	{
		final List<String> keys = new ArrayList<String>();
		for (final String k : pKeyColumns.split(","))
		{
			if (!k.trim().isEmpty())
			{
				keys.add(SnowflakeConnector.identifier(k.trim()));
			}
		}
		final List<String> columns = new ArrayList<String>();
		final List<String> selects = new ArrayList<String>();
		final Iterator<Map.Entry<String, JsonNode>> fields = pDocument.fields();
		int n = 1;
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			final String column = SnowflakeConnector.identifier(field.getKey());
			columns.add(column);
			final JsonNode value = field.getValue();
			if (value == null || value.isNull())
			{
				selects.add("NULL AS " + column);
				continue;
			}
			pBindings.put(String.valueOf(n), value.isValueNode() ? value.asText() : Json.write(value));
			selects.add((value.isValueNode() ? "?" : "PARSE_JSON(?)") + " AS " + column);
			n++;
		}
		final StringBuilder sql = new StringBuilder("MERGE INTO ").append(pTable).append(" t USING (SELECT ")
				.append(SnowflakeConnector.join(selects, ", ")).append(") s ON ");
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
			sql.append(" WHEN MATCHED THEN UPDATE SET ").append(SnowflakeConnector.join(updates, ", "));
		}
		final List<String> sources = new ArrayList<String>();
		for (final String column : columns)
		{
			sources.add("s." + column);
		}
		sql.append(" WHEN NOT MATCHED THEN INSERT (").append(SnowflakeConnector.join(columns, ", ")).append(") VALUES (")
				.append(SnowflakeConnector.join(sources, ", ")).append(")");
		return sql.toString();
	}

	static String insertSql(final String pTable, final ObjectNode pDocument, final Map<String, String> pBindings)
	{
		final List<String> columns = new ArrayList<String>();
		final List<String> values = new ArrayList<String>();
		final Iterator<Map.Entry<String, JsonNode>> fields = pDocument.fields();
		int n = 1;
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			columns.add(SnowflakeConnector.identifier(field.getKey()));
			final JsonNode value = field.getValue();
			if (value == null || value.isNull())
			{
				values.add("NULL");
				continue;
			}
			pBindings.put(String.valueOf(n), value.isValueNode() ? value.asText() : Json.write(value));
			values.add(value.isValueNode() ? "?" : "PARSE_JSON(?)");
			n++;
		}
		return "INSERT INTO " + pTable + " (" + SnowflakeConnector.join(columns, ", ") + ") SELECT " + SnowflakeConnector.join(values, ", ");
	}

	/** Bindings b1..bn of the inputs, in order. */
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
			binds.put(String.valueOf(i), value);
		}
		return binds;
	}

	/** Column and table names: only letters, digits, underscore, dots and quotes survive. */
	static String identifier(final String pName)
	{
		return pName.trim().replaceAll("[^A-Za-z0-9_.\"$]", "");
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

	// ------------------------------------------------------------------ TablePublisher (product publication, §2.2)

	@Override
	public String nativeType(final ColumnSpec pColumn)
	{
		return SnowflakePublicationSql.nativeType(pColumn);
	}

	private String[] databaseAndSchema(final String pSchema) throws ConnectorException
	{
		return SnowflakePublicationSql.databaseAndSchema(pSchema, this.getConfig().getProperty("database", null));
	}

	/** Rows per table batch size for outbound publication loads: connector property {@code publish.batchSize}, default 500. */
	public int publishBatchSize()
	{
		return SnowflakeConnector.toInt(this.getConfig().getProperty("publish.batchSize", "500"), 500);
	}

	@Override
	public List<String> plan(final PublicationSpec pSpec, final boolean pRecreate)
	{
		final List<String> plan = new ArrayList<String>();
		try
		{
			final String[] ds = this.databaseAndSchema(pSpec.getSchema());
			plan.add(SnowflakePublicationSql.createSchemaSql(ds[0], ds[1]));
			plan.add(SnowflakePublicationSql.createControlTableSql(ds[0], ds[1]));
			final Map<String, Integer> zero = new LinkedHashMap<String, Integer>();
			for (final TableSpec table : pSpec.getTables())
			{
				plan.add(SnowflakePublicationSql.createTableSql(ds[0], ds[1], table, pRecreate));
				plan.add(SnowflakePublicationSql.createStagingTableSql(ds[0], ds[1], table.getName()));
				plan.add(SnowflakePublicationSql.insertFlattenSql(ds[0], ds[1], table));
				zero.put(table.getName(), Integer.valueOf(0));
			}
			plan.addAll(SnowflakePublicationSql.commitStatements(ds[0], ds[1], pSpec, zero));
		}
		catch (final ConnectorException ex)
		{
			plan.add("-- " + ex.getMessage());
		}
		return plan;
	}

	@Override
	public Map<String, String> prepare(final ConnectorContext pContext, final PublicationSpec pSpec, final boolean pRecreate) throws ConnectorException
	{
		this.authenticate(); // the publisher entry points do not go through execute(): without this the key-pair JWT is never built (HTTP 401)
		final String[] ds = this.databaseAndSchema(pSpec.getSchema());
		this.statement(SnowflakePublicationSql.createSchemaSql(ds[0], ds[1]), null, 1);
		this.statement(SnowflakePublicationSql.createControlTableSql(ds[0], ds[1]), null, 1);
		final Map<String, String> actions = new LinkedHashMap<String, String>();
		for (final TableSpec table : pSpec.getTables())
		{
			actions.put(table.getName(), this.prepareTable(ds[0], ds[1], table, pRecreate));
			this.statement(SnowflakePublicationSql.createStagingTableSql(ds[0], ds[1], table.getName()), null, 1);
		}
		return actions;
	}

	private String prepareTable(final String pDatabase, final String pSchema, final TableSpec pTable, final boolean pRecreate) throws ConnectorException
	{
		java.util.Set<String> existing = null;
		try
		{
			final JsonNode described = this.statement(SnowflakePublicationSql.describeTableSql(pDatabase, pSchema, pTable.getName()), null, 1000);
			existing = new java.util.LinkedHashSet<String>();
			final JsonNode rows = Json.at(described, "rows");
			if (rows != null)
			{
				for (final JsonNode row : rows)
				{
					final JsonNode name = row.get("name");
					if (name != null)
					{
						existing.add(name.asText().toUpperCase(Locale.ROOT));
					}
				}
			}
		}
		catch (final ConnectorException ex)
		{
			if (!SnowflakeConnector.isMissingTable(ex))
			{
				throw ex;
			}
		}
		if (existing == null)
		{
			this.statement(SnowflakePublicationSql.createTableSql(pDatabase, pSchema, pTable, false), null, 1);
			return "CREATED";
		}
		final java.util.Set<String> expected = new java.util.LinkedHashSet<String>();
		for (final ColumnSpec column : pTable.getColumns())
		{
			expected.add(column.getName().toUpperCase(Locale.ROOT));
		}
		if (existing.equals(expected))
		{
			return "EXISTS";
		}
		if (!pRecreate)
		{
			final java.util.Set<String> missing = new java.util.LinkedHashSet<String>(expected);
			missing.removeAll(existing);
			final java.util.Set<String> extra = new java.util.LinkedHashSet<String>(existing);
			extra.removeAll(expected);
			throw new ConnectorException(SnowflakePublicationSql.columnMismatchMessage(pTable.getName(), missing, extra));
		}
		this.statement(SnowflakePublicationSql.createTableSql(pDatabase, pSchema, pTable, true), null, 1);
		return "RECREATED";
	}

	private static boolean isMissingTable(final ConnectorException pEx)
	{
		final String message = pEx.getMessage() == null ? "" : pEx.getMessage().toUpperCase(Locale.ROOT);
		return message.contains("DOES NOT EXIST");
	}

	@Override
	public int load(final ConnectorContext pContext, final PublicationSpec pSpec, final TableSpec pTable, final List<ObjectNode> pDocuments) throws ConnectorException
	{
		this.authenticate(); // the publisher entry points do not go through execute(): without this the key-pair JWT is never built (HTTP 401)
		if (pDocuments == null || pDocuments.isEmpty())
		{
			return 0;
		}
		final String[] ds = this.databaseAndSchema(pSpec.getSchema());
		final ArrayNode array = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
		for (final ObjectNode document : pDocuments)
		{
			array.add(document);
		}
		final Map<String, String> bindings = new LinkedHashMap<String, String>();
		bindings.put("1", Json.write(array));
		this.statement(SnowflakePublicationSql.insertFlattenSql(ds[0], ds[1], pTable), bindings, 1);
		return pDocuments.size();
	}

	@Override
	public void commit(final ConnectorContext pContext, final PublicationSpec pSpec, final Map<String, Integer> pRowsPerTable) throws ConnectorException
	{
		this.authenticate(); // the publisher entry points do not go through execute(): without this the key-pair JWT is never built (HTTP 401)
		final String[] ds = this.databaseAndSchema(pSpec.getSchema());
		final List<String> statements = SnowflakePublicationSql.commitStatements(ds[0], ds[1], pSpec, pRowsPerTable);
		this.statement(SnowflakePublicationSql.joinStatements(statements), null, 1, statements.size());
	}

	@Override
	public List<String> annotationPlan(final PublicationSpec pSpec)
	{
		try
		{
			final String[] ds = this.databaseAndSchema(pSpec.getSchema());
			final List<String> plan = new ArrayList<String>(SnowflakePublicationSql.commentStatements(ds[0], ds[1], pSpec));
			plan.addAll(SnowflakePublicationSql.tagStatements(ds[0], ds[1], pSpec));
			final String semanticView = this.semanticViewEnabled() ? SnowflakePublicationSql.semanticViewSql(ds[0], ds[1], pSpec) : null;
			if (semanticView != null)
			{
				plan.add(semanticView);
			}
			if (this.listingEnabled())
			{
				plan.addAll(SnowflakePublicationSql.shareStatements(ds[0], ds[1], pSpec));
				plan.add(SnowflakePublicationSql.createListingSql(pSpec.getProduct(), SnowflakePublicationSql.listingManifest(pSpec,
					this.getConfig().getProperty("listing.supportContact", ""), this.getConfig().getProperty("listing.region", ""))));
			}
			return plan;
		}
		catch (final ConnectorException ex)
		{
			return new ArrayList<String>();
		}
	}

	@Override
	public List<String> annotate(final ConnectorContext pContext, final PublicationSpec pSpec)
	{
		final List<String> warnings = new ArrayList<String>();
		final String[] ds;
		try
		{
			this.authenticate();
			ds = this.databaseAndSchema(pSpec.getSchema());
		}
		catch (final ConnectorException ex)
		{
			warnings.add("comments and tags not applied: " + ex.getMessage());
			return warnings;
		}
		// comments and tags are separate batches: a target without tags (edition, missing privilege) keeps its comments
		this.annotateBatch("comments", SnowflakePublicationSql.commentStatements(ds[0], ds[1], pSpec), warnings);
		this.annotateBatch("tags", SnowflakePublicationSql.tagStatements(ds[0], ds[1], pSpec), warnings);
		// its own statement: an account without semantic views (or without CREATE SEMANTIC VIEW) keeps comments and tags
		final String semanticView = this.semanticViewEnabled() ? SnowflakePublicationSql.semanticViewSql(ds[0], ds[1], pSpec) : null;
		if (semanticView != null)
		{
			this.annotateBatch("semantic view", java.util.Collections.singletonList(semanticView), warnings);
		}
		if (this.listingEnabled())
		{
			try
			{
				this.publishListing(ds[0], ds[1], pSpec);
			}
			catch (final ConnectorException ex)
			{
				final String message = "Snowflake listing not published: " + ex.getMessage();
				com.on.ebx.connect.core.ConnectorService.log().warn("[connectors] " + message);
				warnings.add(message);
			}
		}
		return warnings;
	}

	/** Connector property {@code semanticView.enabled}: on unless set to false; the view only restates what the tables and the glossary already say. */
	private boolean semanticViewEnabled()
	{
		return !"false".equalsIgnoreCase(this.getConfig().getProperty("semanticView.enabled", "true").trim());
	}

	/** Connector property {@code listing.enabled}: off unless set to true, so a publication never creates a listing by surprise. */
	private boolean listingEnabled()
	{
		return "true".equalsIgnoreCase(this.getConfig().getProperty("listing.enabled", "false").trim());
	}

	/**
	 * Makes the published tables discoverable in the organization's Internal Marketplace: a share of the
	 * schema, then an organizational listing on it, created the first time and updated in place after.
	 * Needs CREATE SHARE and CREATE ORGANIZATION LISTING on the account and a database owned by the role.
	 */
	private void publishListing(final String pDatabase, final String pSchema, final PublicationSpec pSpec) throws ConnectorException
	{
		final String contact = this.getConfig().getProperty("listing.supportContact", "").trim();
		if (contact.isEmpty())
		{
			throw new ConnectorException("connector property listing.supportContact (an e-mail address) is required");
		}
		String region = this.getConfig().getProperty("listing.region", "").trim();
		if (region.isEmpty())
		{
			final JsonNode current = this.statement("SELECT CURRENT_REGION()", null, 1);
			final JsonNode rows = Json.at(current, "rows");
			if (rows != null && rows.size() > 0 && rows.get(0).fields().hasNext())
			{
				region = rows.get(0).fields().next().getValue().asText("");
			}
		}
		if (region.isEmpty())
		{
			throw new ConnectorException("cannot determine the Snowflake region: set the connector property listing.region");
		}
		final List<String> share = SnowflakePublicationSql.shareStatements(pDatabase, pSchema, pSpec);
		this.statement(SnowflakePublicationSql.joinStatements(share), null, 1, share.size());
		final String manifest = SnowflakePublicationSql.listingManifest(pSpec, contact, region);
		final String listing = SnowflakePublicationSql.listingName(pSpec.getProduct());
		boolean exists = false;
		final JsonNode shown = this.statement(SnowflakePublicationSql.showListingSql(pSpec.getProduct()), null, 100);
		final JsonNode listed = Json.at(shown, "rows");
		if (listed != null)
		{
			for (final JsonNode row : listed)
			{
				if (listing.equalsIgnoreCase(row.path("name").asText("")))
				{
					exists = true;
				}
			}
		}
		this.statement(exists ? SnowflakePublicationSql.alterListingSql(pSpec.getProduct(), manifest)
			: SnowflakePublicationSql.createListingSql(pSpec.getProduct(), manifest), null, 1);
	}

	private void annotateBatch(final String pName, final List<String> pStatements, final List<String> pWarnings)
	{
		if (pStatements.isEmpty())
		{
			return;
		}
		try
		{
			this.statement(SnowflakePublicationSql.joinStatements(pStatements), null, 1, pStatements.size());
		}
		catch (final ConnectorException ex)
		{
			final String message = "Snowflake " + pName + " not applied: " + ex.getMessage();
			com.on.ebx.connect.core.ConnectorService.log().warn("[connectors] " + message);
			pWarnings.add(message);
		}
	}

	@Override
	public void cleanup(final ConnectorContext pContext, final PublicationSpec pSpec)
	{
		try
		{
			this.authenticate(); // the publisher entry points do not go through execute(): without this the key-pair JWT is never built (HTTP 401)
			final String[] ds = this.databaseAndSchema(pSpec.getSchema());
			for (final TableSpec table : pSpec.getTables())
			{
				try
				{
					this.statement("DROP TABLE IF EXISTS "
						+ SnowflakePublicationSql.qualified(ds[0], ds[1], SnowflakePublicationSql.stagingName(table.getName())), null, 1);
				}
				catch (final ConnectorException ex)
				{
					com.on.ebx.connect.core.ConnectorService.log().warn("[connectors] Snowflake publish cleanup: " + ex.getMessage());
				}
			}
		}
		catch (final ConnectorException ex)
		{
			// invalid schema: nothing staged to clean up
		}
	}

	// ------------------------------------------------------------------ key-pair JWT

	/**
	 * JWT for Snowflake key-pair authentication: issuer {@code ACCOUNT.USER.SHA256:<fingerprint>},
	 * subject {@code ACCOUNT.USER}, RS256 over an unencrypted PKCS#8 private key.
	 */
	static String keyPairJwt(final String pAccount, final String pUser, final String pPrivateKeyPem) throws ConnectorException
	{
		if (pAccount == null || pAccount.trim().isEmpty() || pUser == null || pUser.trim().isEmpty() || pPrivateKeyPem == null)
		{
			throw new ConnectorException("Snowflake key-pair: property account, username and the private key (password) are required");
		}
		try
		{
			final String pem = pPrivateKeyPem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
			final PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
			final RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
			final byte[] publicDer = KeyFactory.getInstance("RSA")
					.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent())).getEncoded();
			final String fingerprint = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicDer));
			// account identifier without region or cloud (everything after the first dot), upper case;
			// the org-account form MYORG-MYACCOUNT has no dot and is kept as is
			String account = pAccount.trim().toUpperCase(Locale.ROOT);
			if (account.contains("."))
			{
				account = account.substring(0, account.indexOf('.'));
			}
			final String qualifiedUser = account + "." + pUser.trim().toUpperCase(Locale.ROOT);
			final long now = System.currentTimeMillis() / 1000L;
			final String header = SnowflakeConnector.base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
			final String payload = SnowflakeConnector.base64Url(("{\"iss\":\"" + qualifiedUser + ".SHA256:" + fingerprint + "\",\"sub\":\""
					+ qualifiedUser + "\",\"iat\":" + now + ",\"exp\":" + (now + 3540L) + "}").getBytes(StandardCharsets.UTF_8));
			final Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(privateKey);
			signature.update((header + "." + payload).getBytes(StandardCharsets.UTF_8));
			return header + "." + payload + "." + SnowflakeConnector.base64Url(signature.sign());
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Snowflake key-pair: cannot build the JWT: " + ex.getMessage(), ex);
		}
	}

	private static String base64Url(final byte[] pBytes)
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(pBytes);
	}
}
