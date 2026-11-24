package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.GoogleAuth;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Google BigQuery through {@code jobs.query} (REST v2). Properties {@code project}, {@code dataset}
 * (default dataset), {@code location}; authentication by service account key
 * ({@code serviceAccountJson} / {@code serviceAccountFile}) or bearer token.
 * <ul>
 * <li>{@code query} (SEARCH): {@code sql} with named parameters {@code @name} bound from the
 * other inputs → {@code {rows:[...], count}}.</li>
 * <li>{@code get} (READ): {@code table}, {@code key}, {@code value} → the row.</li>
 * <li>{@code insert} (WRITE): streaming insert of the outbound document into {@code table}.</li>
 * <li>{@code upsert} (WRITE): MERGE on {@code key} (comma-separated columns) from the outbound
 * document; sub-objects are written as JSON strings.</li>
 * <li>{@code catalog}, {@code profile}, {@code sample} (SEARCH): the data-catalog documents of
 * {@link com.on.ebx.connect.core.CatalogOperations} from {@code INFORMATION_SCHEMA}; a schema is a
 * dataset ({@code PROJECT.DATASET} for another project).</li>
 * </ul>
 * A query that is not complete within {@code timeoutMs} is polled with {@code jobs.getQueryResults}
 * (property {@code query.maxWaitSeconds}, 120 by default); its further pages are read with
 * {@code pageToken} up to {@code max} rows, and the answer says {@code truncated} when rows were left.
 */
public final class BigQueryConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("query", "SQL query with @named parameters bound from the inputs (input sql)", "sql"),
		Operation.read("get", "One row by key (inputs table, key, value)", "table", "key", "value"),
		Operation.write("insert", "Streaming insert of the outbound document (input table)", "table"),
		Operation.write("upsert", "MERGE the outbound document on key columns (inputs table, key)", "table", "key"),
		Operation.search("catalog", "Data catalog inventory: datasets, tables, views, columns from INFORMATION_SCHEMA (inputs source, schemas = datasets, PROJECT.DATASET for another project, types)", "source"),
		Operation.search("profile", "Data catalog column statistics, one aggregate query per table (inputs source, schemas, tables, where; budget: maxTables, maxSeconds, largeTableRows, largeTables = sample|exact|skip, sampleRows, approxDistinct, dryRun = plan only)", "source"),
		Operation.search("sample", "Data catalog value samples of the text columns (inputs source, schemas, tables, limit)", "source"));

	private final GoogleAuth auth = new GoogleAuth();

	@Override
	public String getType()
	{
		return "bigquery";
	}

	@Override
	public String getLabel()
	{
		return "Google BigQuery";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("DATA_PLATFORMS");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.SERVICE_ACCOUNT).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN).capability(com.on.ebx.connect.spi.ConnectorCapability.PREVIEW_RECORDS).capability(com.on.ebx.connect.spi.ConnectorCapability.SCHEMA_DISCOVERY).apiVersion("Google BigQuery REST API v2").pagination(new com.on.ebx.connect.spi.PaginationDescriptor(com.on.ebx.connect.spi.PaginationDescriptor.Strategy.TOKEN, 1000, 10000, "pageToken")).configurationProperty(property("dataset", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, true)).configurationProperty(property("location", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("max.rows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("project", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, true)).configurationProperty(property("serviceAccountJson", com.on.ebx.connect.spi.ConfigurationPropertyType.SECRET, false)).configurationProperty(property("serviceAccountFile", com.on.ebx.connect.spi.ConfigurationPropertyType.FILE, false));
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

	@Override
	public List<Operation> getOperations()
	{
		return BigQueryConnector.OPERATIONS;
	}

	@Override
	protected String baseUrl() throws ConnectorException
	{
		final String url = this.getConfig().getBaseUrl();
		return (url == null || url.trim().isEmpty() ? "https://bigquery.googleapis.com" : url.trim().replaceAll("/+$", "")) + "/bigquery/v2/projects/"
				+ AbstractRestConnector.segment(this.project());
	}

	private String project() throws ConnectorException
	{
		final String project = this.getConfig().getProperty("project", null);
		if (project == null)
		{
			throw new ConnectorException("BigQuery: set the property project");
		}
		return project;
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Accept", "application/json");
		try
		{
			headers.put("Authorization", "Bearer " + this.auth.token(this.getConfig(), "https://www.googleapis.com/auth/bigquery"));
		}
		catch (final ConnectorException ex)
		{
			headers.put("X-Connector-Auth-Error", ex.getMessage());
		}
		return headers;
	}

	private String qualify(final String pTable) throws ConnectorException
	{
		final String table = pTable.trim().replace("`", "");
		final String[] parts = table.split("\\.");
		final String dataset = this.getConfig().getProperty("dataset", null);
		if (parts.length == 3)
		{
			return "`" + table + "`";
		}
		if (parts.length == 2)
		{
			return "`" + this.project() + "." + table + "`";
		}
		if (dataset == null)
		{
			throw new ConnectorException("BigQuery: qualify the table as dataset.table or set the property dataset");
		}
		return "`" + this.project() + "." + dataset + "." + table + "`";
	}

	/** Runs a query with named parameters; returns {@code {rows:[...], count, totalRows}} or null. */
	JsonNode run(final String pSql, final Map<String, Object> pParameters, final int pMax) throws ConnectorException
	{
		final ObjectNode body = Json.object();
		body.put("query", pSql);
		body.put("useLegacySql", false);
		body.put("maxResults", pMax);
		body.put("timeoutMs", Math.max(1000, this.getConfig().getReadTimeoutMs() - 1000));
		final String location = this.getConfig().getProperty("location", null);
		if (location != null)
		{
			body.put("location", location);
		}
		if (pParameters != null && !pParameters.isEmpty())
		{
			body.put("parameterMode", "NAMED");
			final ArrayNode parameters = body.putArray("queryParameters");
			for (final Map.Entry<String, Object> parameter : pParameters.entrySet())
			{
				final ObjectNode p = parameters.addObject();
				p.put("name", parameter.getKey());
				p.putObject("parameterType").put("type", BigQueryConnector.typeOf(parameter.getValue()));
				final ObjectNode value = p.putObject("parameterValue");
				if (parameter.getValue() == null)
				{
					value.putNull("value");
				}
				else
				{
					value.put("value", String.valueOf(parameter.getValue()));
				}
			}
		}
		this.auth.token(this.getConfig(), "https://www.googleapis.com/auth/bigquery");
		JsonNode reply = this.bodyOrNull(this.send("POST", "/queries", null, body), "query");
		if (reply == null)
		{
			return null;
		}
		// not complete within timeoutMs: poll getQueryResults (each call waits server side) until the deadline
		final long deadline = System.currentTimeMillis() + 1000L * BigQueryConnector.bounded(this.getConfig().getProperty("query.maxWaitSeconds", "120"), 120, 1, 3600);
		while (Json.at(reply, "jobComplete") != null && !Json.at(reply, "jobComplete").asBoolean())
		{
			if (System.currentTimeMillis() >= deadline)
			{
				throw new ConnectorException("BigQuery: the query did not complete within query.maxWaitSeconds (job " + String.valueOf(Json.at(reply, "jobReference.jobId")) + ")");
			}
			reply = this.results(reply, null, pMax, location);
		}
		final JsonNode schema = Json.at(reply, "schema.fields");
		final ObjectNode out = Json.object();
		final ArrayNode list = out.putArray("rows");
		JsonNode page = reply;
		for (int pages = 0; page != null && pages < 1000; pages++)
		{
			final JsonNode rows = Json.at(page, "rows");
			if (schema != null && rows != null)
			{
				for (final JsonNode row : rows)
				{
					if (list.size() >= pMax) break;
					list.add(BigQueryConnector.convert(schema, row));
				}
			}
			final JsonNode token = Json.at(page, "pageToken");
			if (token == null || token.asText().isEmpty()) break;
			if (list.size() >= pMax)
			{
				out.put("truncated", true);
				break;
			}
			page = this.results(reply, token.asText(), pMax - list.size(), location);
		}
		out.put("count", list.size());
		if (Json.at(reply, "totalRows") != null)
		{
			out.put("totalRows", Json.at(reply, "totalRows").asLong());
		}
		if (Json.at(reply, "numDmlAffectedRows") != null)
		{
			out.put("affectedRows", Json.at(reply, "numDmlAffectedRows").asLong());
		}
		return out;
	}

	/** {@code jobs.getQueryResults} of the job of {@code pFirst}: the next page, or the status while it runs. */
	private JsonNode results(final JsonNode pFirst, final String pPageToken, final int pMax, final String pLocation) throws ConnectorException
	{
		final JsonNode job = Json.at(pFirst, "jobReference.jobId");
		if (job == null)
		{
			throw new ConnectorException("BigQuery: the answer has no job reference to read further results from");
		}
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("maxResults", String.valueOf(Math.max(1, Math.min(10000, pMax))));
		query.put("timeoutMs", String.valueOf(Math.max(1000, Math.min(10000, this.getConfig().getReadTimeoutMs() - 1000))));
		final JsonNode jobLocation = Json.at(pFirst, "jobReference.location");
		if (jobLocation != null && !jobLocation.asText().isEmpty()) query.put("location", jobLocation.asText());
		else if (pLocation != null) query.put("location", pLocation);
		if (pPageToken != null) query.put("pageToken", pPageToken);
		return this.bodyOrNull(this.get("/queries/" + AbstractRestConnector.segment(job.asText()), query), "getQueryResults");
	}

	static int bounded(final String pValue, final int pDefault, final int pMinimum, final int pMaximum)
	{
		try
		{
			return pValue == null || pValue.trim().isEmpty() ? pDefault : Math.max(pMinimum, Math.min(pMaximum, Integer.parseInt(pValue.trim())));
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	/**
	 * BigQuery's {@code INFORMATION_SCHEMA}: a catalog schema is a dataset. With a {@code location}, one
	 * region view covers every dataset of the project ({@code region-eu.INFORMATION_SCHEMA.TABLES});
	 * without one, each dataset's own view is read and the results joined with {@code UNION ALL}.
	 */
	public static final class BigQueryDialect implements com.on.ebx.connect.core.CatalogOperations.Dialect
	{
		private final String project;
		private final String region;

		public BigQueryDialect(final String pProject, final String pLocation)
		{
			this.project = pProject;
			this.region = pLocation == null || pLocation.trim().isEmpty() ? null : "region-" + pLocation.trim().toLowerCase(java.util.Locale.ROOT);
		}

		@Override public String quote(final String pIdentifier)
		{
			final StringBuilder out = new StringBuilder();
			for (final String part : pIdentifier.replace("`", "").split("\\."))
			{
				if (out.length() > 0) out.append('.');
				out.append('`').append(part).append('`');
			}
			return out.toString();
		}

		private String projectOf(final String pDatabase)
		{
			return pDatabase == null || pDatabase.trim().isEmpty() ? this.project : pDatabase.trim();
		}

		@Override public String schemasSql()
		{
			return "SELECT schema_name AS SCHEMA_NAME FROM " + this.quote(this.project) + "." + (this.region == null ? "" : "`" + this.region + "`.")
				+ "INFORMATION_SCHEMA.SCHEMATA ORDER BY 1";
		}

		/** One view per dataset, or the region view filtered on the datasets. */
		private String from(final String pDatabase, final List<String> pSchemas, final String pView, final String pSelect, final String pJoin)
		{
			final String project = this.projectOf(pDatabase);
			if (this.region != null)
			{
				return pSelect + " FROM " + this.quote(project) + ".`" + this.region + "`.INFORMATION_SCHEMA." + pView + " t"
					+ String.format(pJoin, this.quote(project) + ".`" + this.region + "`") + " WHERE t.table_schema IN (" + BigQueryDialect.in(pSchemas) + ")";
			}
			final StringBuilder out = new StringBuilder();
			for (final String dataset : pSchemas)
			{
				if (out.length() > 0) out.append(" UNION ALL ");
				final String scope = this.quote(project + "." + dataset);
				out.append(pSelect).append(" FROM ").append(scope).append(".INFORMATION_SCHEMA.").append(pView).append(" t").append(String.format(pJoin, scope));
			}
			return out.toString();
		}

		@Override public String tablesSql(final String pDatabase, final List<String> pSchemas)
		{
			return this.from(pDatabase, pSchemas, "TABLES", "SELECT t.table_schema AS TABLE_SCHEMA, t.table_name AS TABLE_NAME, t.table_type AS TABLE_TYPE, "
				+ "CAST(NULL AS STRING) AS TABLE_COMMENT, CAST(NULL AS INT64) AS ROW_COUNT", "");
		}

		@Override public String columnsSql(final String pDatabase, final List<String> pSchemas)
		{
			return this.from(pDatabase, pSchemas, "COLUMNS", "SELECT t.table_schema AS TABLE_SCHEMA, t.table_name AS TABLE_NAME, t.column_name AS COLUMN_NAME, "
				+ "t.ordinal_position AS ORDINAL_POSITION, t.data_type AS DATA_TYPE, t.is_nullable AS IS_NULLABLE, p.description AS COLUMN_COMMENT",
				" LEFT JOIN %s.INFORMATION_SCHEMA.COLUMN_FIELD_PATHS p ON p.table_schema = t.table_schema AND p.table_name = t.table_name"
					+ " AND p.column_name = t.column_name AND p.field_path = t.column_name");
		}

		@Override public String usageSql() { return null; }
		@Override public String lineageSql() { return null; }

		@Override public String localName(final String pFullName)
		{
			if (pFullName == null) return null;
			final String[] parts = pFullName.replace("`", "").split("\\.");
			if (parts.length == 3) return this.project.equals(parts[0]) ? parts[1] + "." + parts[2] : null;
			return pFullName;
		}

		@Override public String length(final String pColumn) { return "LENGTH(" + pColumn + ")"; }
		@Override public String toDouble(final String pColumn) { return "CAST(" + pColumn + " AS FLOAT64)"; }
		@Override public String limit(final int pRows) { return "LIMIT " + pRows; }
		@Override public String sampleClause(final double pPercent) { return "TABLESAMPLE SYSTEM (" + com.on.ebx.connect.core.ProfilePolicy.percentText(pPercent) + " PERCENT)"; }
		@Override public String approxDistinct(final String pColumn) { return "APPROX_COUNT_DISTINCT(" + pColumn + ")"; }

		private static String in(final List<String> pValues)
		{
			final StringBuilder sb = new StringBuilder();
			for (final String v : pValues) sb.append(sb.length() > 0 ? ", " : "").append('\'').append(v.replace("\\", "\\\\").replace("'", "\\'")).append('\'');
			return sb.toString();
		}
	}

	/** The BigQuery {@code {f:[{v}]}} row into an object, nested records and arrays included. */
	static JsonNode convert(final JsonNode pFields, final JsonNode pRow)
	{
		final ObjectNode out = Json.object();
		final JsonNode cells = pRow.get("f");
		for (int i = 0; i < pFields.size() && cells != null && i < cells.size(); i++)
		{
			final JsonNode field = pFields.get(i);
			out.set(field.get("name").asText(), BigQueryConnector.value(field, cells.get(i).get("v")));
		}
		return out;
	}

	private static JsonNode value(final JsonNode pField, final JsonNode pValue)
	{
		if (pValue == null || pValue.isNull())
		{
			return Json.mapper().nullNode();
		}
		final String type = pField.get("type") == null ? "STRING" : pField.get("type").asText();
		if ("REPEATED".equals(pField.get("mode") == null ? "" : pField.get("mode").asText()) && pValue.isArray())
		{
			final ArrayNode array = Json.mapper().createArrayNode();
			final ObjectNode scalar = pField.deepCopy();
			scalar.put("mode", "NULLABLE");
			for (final JsonNode item : pValue)
			{
				array.add(BigQueryConnector.value(scalar, item.get("v")));
			}
			return array;
		}
		if ("RECORD".equals(type) || "STRUCT".equals(type))
		{
			return BigQueryConnector.convert(pField.get("fields"), pValue);
		}
		final String text = pValue.asText();
		try
		{
			if ("INTEGER".equals(type) || "INT64".equals(type))
			{
				return Json.fromJava(Long.valueOf(text));
			}
			if ("FLOAT".equals(type) || "FLOAT64".equals(type) || "NUMERIC".equals(type) || "BIGNUMERIC".equals(type))
			{
				return Json.fromJava(new java.math.BigDecimal(text));
			}
			if ("BOOLEAN".equals(type) || "BOOL".equals(type))
			{
				return Json.fromJava(Boolean.valueOf(text));
			}
			if ("TIMESTAMP".equals(type))
			{
				final double seconds = Double.parseDouble(text);
				return Json.fromJava(java.time.Instant.ofEpochMilli((long) (seconds * 1000)).toString());
			}
			if ("JSON".equals(type))
			{
				return Json.parse(text);
			}
		}
		catch (final Exception ex)
		{
			// keep the text
		}
		return Json.fromJava(text);
	}

	private static String typeOf(final Object pValue)
	{
		if (pValue instanceof Boolean)
		{
			return "BOOL";
		}
		if (pValue instanceof Integer || pValue instanceof Long)
		{
			return "INT64";
		}
		if (pValue instanceof Number)
		{
			return "NUMERIC";
		}
		return "STRING";
	}

	private static String literal(final JsonNode pValue)
	{
		if (pValue == null || pValue.isNull())
		{
			return "NULL";
		}
		if (pValue.isBoolean())
		{
			return pValue.asBoolean() ? "TRUE" : "FALSE";
		}
		if (pValue.isNumber())
		{
			return pValue.asText();
		}
		return "'" + (pValue.isValueNode() ? pValue.asText() : Json.write(pValue)).replace("\\", "\\\\").replace("'", "\\'") + "'";
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		final Map<String, String> inputs = pInputs == null ? new LinkedHashMap<String, String>() : pInputs;
		final int ceiling = BigQueryConnector.bounded(this.getConfig().getProperty("max.rows", "1000"), 1000, 1, 100000);
		final int max = BigQueryConnector.bounded(inputs.get("max"), ceiling, 1, ceiling);
		if ("catalog".equals(pOperation) || "profile".equals(pOperation) || "sample".equals(pOperation))
		{
			final int catalogMax = Math.max(max, 10000);
			final com.on.ebx.connect.core.CatalogOperations.SqlRunner runner = new com.on.ebx.connect.core.CatalogOperations.SqlRunner()
			{
				@Override
				public JsonNode run(final String pSql) throws ConnectorException
				{
					return BigQueryConnector.this.run(pSql, null, catalogMax);
				}
			};
			final com.on.ebx.connect.core.CatalogOperations.Dialect dialect = new BigQueryDialect(this.project(), this.getConfig().getProperty("location", null));
			final String dataset = this.getConfig().getProperty("dataset", null);
			if ("catalog".equals(pOperation)) return com.on.ebx.connect.core.CatalogOperations.catalog(runner, dialect, inputs, dataset, this.getConfig().getId());
			if ("sample".equals(pOperation)) return com.on.ebx.connect.core.CatalogOperations.sample(runner, dialect, inputs, dataset);
			return com.on.ebx.connect.core.CatalogOperations.profile(runner, dialect, inputs, dataset, new java.util.function.Function<String, String>()
			{
				@Override
				public String apply(final String pName)
				{
					return BigQueryConnector.this.getConfig().getProperty(pName, null);
				}
			});
		}
		if ("query".equals(pOperation))
		{
			final String sql = AbstractRestConnector.require(inputs, "sql");
			final Map<String, Object> parameters = new LinkedHashMap<String, Object>();
			final Matcher m = Pattern.compile("@([A-Za-z_][A-Za-z0-9_]*)").matcher(sql);
			while (m.find())
			{
				final String value = inputs.get(m.group(1));
				parameters.put(m.group(1), value == null ? null : value.matches("-?[0-9]+") ? (Object) Long.valueOf(value) : value);
			}
			final JsonNode out = this.run(sql, parameters, max);
			return out == null || out.get("count").asInt() == 0 ? null : out;
		}
		if ("get".equals(pOperation))
		{
			final Map<String, Object> parameters = new LinkedHashMap<String, Object>();
			parameters.put("value", AbstractRestConnector.require(inputs, "value"));
			final String key = AbstractRestConnector.require(inputs, "key").replaceAll("[^A-Za-z0-9_]", "");
			final JsonNode out = this.run("SELECT * FROM " + this.qualify(AbstractRestConnector.require(inputs, "table")) + " WHERE CAST(" + key + " AS STRING) = @value LIMIT 1",
				parameters, 1);
			return Json.at(out, "rows[0]");
		}
		if ("insert".equals(pOperation))
		{
			final String[] parts = this.qualify(AbstractRestConnector.require(inputs, "table")).replace("`", "").split("\\.");
			final ObjectNode body = Json.object();
			final ArrayNode rows = body.putArray("rows");
			for (final JsonNode row : FileFormats.rows(pPayload))
			{
				final ObjectNode item = rows.addObject();
				item.set("json", row);
			}
			this.auth.token(this.getConfig(), "https://www.googleapis.com/auth/bigquery");
			final JsonNode reply = this.bodyOrNull(this.send("POST", "/datasets/" + AbstractRestConnector.segment(parts[1]) + "/tables/" + AbstractRestConnector.segment(parts[2])
					+ "/insertAll", null, body), "insertAll");
			if (Json.at(reply, "insertErrors") != null && Json.at(reply, "insertErrors").size() > 0)
			{
				throw new ConnectorException("BigQuery insertAll: " + Json.write(Json.at(reply, "insertErrors")));
			}
			final ObjectNode out = Json.object();
			out.put("inserted", rows.size());
			return out;
		}
		if ("upsert".equals(pOperation))
		{
			if (pPayload == null || !pPayload.isObject())
			{
				throw new ConnectorException("BigQuery upsert: the outbound document is required");
			}
			final List<String> keys = new ArrayList<String>();
			for (final String key : AbstractRestConnector.require(inputs, "key").split(","))
			{
				keys.add(key.trim().replaceAll("[^A-Za-z0-9_]", ""));
			}
			final StringBuilder columns = new StringBuilder();
			final StringBuilder values = new StringBuilder();
			final StringBuilder updates = new StringBuilder();
			final StringBuilder on = new StringBuilder();
			final java.util.Iterator<Map.Entry<String, JsonNode>> fields = pPayload.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				final String column = field.getKey().replaceAll("[^A-Za-z0-9_]", "");
				final String literal = BigQueryConnector.literal(field.getValue());
				columns.append(columns.length() > 0 ? ", " : "").append(column);
				values.append(values.length() > 0 ? ", " : "").append(literal);
				if (!keys.contains(column))
				{
					updates.append(updates.length() > 0 ? ", " : "").append(column).append(" = S.").append(column);
				}
			}
			for (final String key : keys)
			{
				on.append(on.length() > 0 ? " AND " : "").append("T.").append(key).append(" = S.").append(key);
			}
			final String sql = "MERGE " + this.qualify(AbstractRestConnector.require(inputs, "table")) + " T USING (SELECT " + BigQueryConnector.aliased(columns, values)
					+ ") S ON " + on + (updates.length() > 0 ? " WHEN MATCHED THEN UPDATE SET " + updates : "") + " WHEN NOT MATCHED THEN INSERT (" + columns
					+ ") VALUES (" + columns + ")";
			return this.run(sql, null, 1);
		}
		throw new ConnectorException("BigQuery: unknown operation '" + pOperation + "'");
	}

	private static String aliased(final StringBuilder pColumns, final StringBuilder pValues)
	{
		final String[] columns = pColumns.toString().split(", ");
		final List<String> values = BigQueryConnector.splitTop(pValues.toString());
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < columns.length && i < values.size(); i++)
		{
			sb.append(i > 0 ? ", " : "").append(values.get(i)).append(" AS ").append(columns[i]);
		}
		return sb.toString();
	}

	/** Splits {@code a, 'b, c', 3} on top-level commas. */
	private static List<String> splitTop(final String pText)
	{
		final List<String> out = new ArrayList<String>();
		final StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < pText.length(); i++)
		{
			final char c = pText.charAt(i);
			if (c == '\\' && quoted && i + 1 < pText.length())
			{
				current.append(c).append(pText.charAt(++i));
				continue;
			}
			if (c == '\'')
			{
				quoted = !quoted;
			}
			if (c == ',' && !quoted)
			{
				out.add(current.toString().trim());
				current.setLength(0);
				continue;
			}
			current.append(c);
		}
		if (current.length() > 0)
		{
			out.add(current.toString().trim());
		}
		return out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		final String table = pInputs == null ? null : pInputs.get("table");
		if (table != null && !table.trim().isEmpty())
		{
			final String[] parts = this.qualify(table).replace("`", "").split("\\.");
			final JsonNode reply = this.bodyOrNull(this.get("/datasets/" + AbstractRestConnector.segment(parts[1]) + "/tables/" + AbstractRestConnector.segment(parts[2]), null), "table");
			final String prefix = "get".equals(pOperation) ? "" : "rows[0].";
			if (Json.at(reply, "schema.fields") != null)
			{
				for (final JsonNode field : Json.at(reply, "schema.fields"))
				{
					fields.add(FieldInfo.of(prefix + field.get("name").asText(), field.get("description") == null ? field.get("name").asText() : field.get("description").asText(),
						field.get("type") == null ? "" : field.get("type").asText().toLowerCase()));
				}
			}
			return fields;
		}
		final String sql = pInputs == null ? null : pInputs.get("sql");
		if (sql != null && !sql.trim().isEmpty() && "query".equals(pOperation))
		{
			final Map<String, String> inputs = new LinkedHashMap<String, String>(pInputs);
			inputs.put("max", "1");
			final JsonNode row = Json.at(this.execute(pContext, "query", inputs, null), "rows[0]");
			if (row != null)
			{
				for (final String[] flat : Json.flatten(row, 200))
				{
					fields.add(new FieldInfo("rows[0]." + flat[0], flat[0], flat[2], flat[1]) /* type, sample: swap fixed (2026-10-17) */);
				}
			}
		}
		return fields;
	}
}
