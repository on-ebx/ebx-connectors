package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * The data-catalog operations ({@code catalog}, {@code profile}, {@code sample}, {@code usage},
 * {@code lineage}) for SQL platforms reached through a statement API rather than JDBC — Snowflake,
 * Databricks. The connector supplies a {@link SqlRunner} (its own {@code statement} method, which
 * returns {@code {columns, rows:[{NAME: value}], count}}) and a {@link Dialect}; this class writes
 * the same documents as {@code JdbcConnector}, so the same catalog flows apply:
 * <ul>
 * <li>{@code catalog} → {@code {source, scannedAt, count, tables:[{source, assetKey, qualifiedName, schema, name, kind, remarks, rowCount, scannedAt, columns:[{columnKey, name, position, dataType, nativeType, nullable, primaryKey, remarks, scannedAt}]}]}}</li>
 * <li>{@code profile} → {@code {source, profiledAt, profileDate, count, columns:[{source, assetKey, qualifiedName, columnKey, name, dataType, profiledAt, profileDate, rowCount, nullCount, nullRate, distinctCount, uniqueRate, minValue, maxValue, meanValue, minLength, maxLength, avgLength}]}}</li>
 * <li>{@code sample} → {@code {source, sampledAt, count, columns:[{source, assetKey, qualifiedName, columnKey, name, dataType, sampledAt, sampleSize, values, valuesText}]}}</li>
 * <li>{@code usage} → {@code {source, measuredAt, count, items:[{source, assetKey, qualifiedName, queryCount30d}]}}</li>
 * <li>{@code lineage} → {@code {source, scannedAt, count, edges:[{source, edgeKey, fromAssetKey, fromColumnKey, toAssetKey, toColumnKey, transformationType, origin, seenAt}]}}</li>
 * </ul>
 * Qualified names are {@code SCHEMA.TABLE} (the database or catalog is implied by the connector) —
 * a schema requested as {@code DB.SCHEMA} is read from that database's information schema instead,
 * and every name it yields keeps the prefix ({@code DB.SCHEMA.TABLE}),
 * {@code assetKey = source:SCHEMA.TABLE}, {@code columnKey = assetKey.COLUMN},
 * {@code edgeKey = fromColumnKey>toColumnKey} (or asset keys when column-level is unknown).
 */
public final class CatalogOperations
{
	private CatalogOperations()
	{
	}

	/** Runs one SELECT and returns the connector's {columns, rows, count} document. */
	public interface SqlRunner
	{
		JsonNode run(String pSql) throws ConnectorException;
	}

	/** The SQL of one platform. Result columns must carry the aliases named in each method's contract. */
	public interface Dialect
	{
		/** Quotes an identifier for a FROM clause; parts separated by dots. */
		String quote(String pIdentifier);

		/** SELECT SCHEMA_NAME ... (non-system schemas). */
		String schemasSql();

		/** SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, ROW_COUNT ... for the schemas of one database ({@code null}: the connector's own). */
		String tablesSql(String pDatabase, List<String> pSchemas);

		/** SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, IS_NULLABLE, COLUMN_COMMENT ... for the schemas of one database ({@code null}: the connector's own). */
		String columnsSql(String pDatabase, List<String> pSchemas);

		/** SELECT OBJECT_NAME, QUERY_COUNT ... reads of the last 30 days; null when the platform has no query history. */
		String usageSql();

		/** SELECT SOURCE_TABLE, SOURCE_COLUMN, TARGET_TABLE, TARGET_COLUMN, SEEN_AT ... column lineage of the last 30 days; null when unsupported. */
		String lineageSql();

		/** {@code DB.SCHEMA.TABLE} → {@code SCHEMA.TABLE} when the first segment is the connector's database/catalog. */
		String localName(String pFullName);

		/** Expression giving the length of a string column. */
		String length(String pColumn);

		/** Expression casting a numeric column to a double for AVG. */
		String toDouble(String pColumn);

		/** LIMIT clause appended to a SELECT. */
		String limit(int pRows);

		/**
		 * Block-sampling clause placed right after the table name in a FROM clause, taking about {@code pPercent}
		 * percent of the table, or null when the platform has none (audit C-15).
		 */
		default String sampleClause(final double pPercent)
		{
			return null;
		}

		/** The platform's approximate distinct-count expression for a column, or null when it has none (audit C-15). */
		default String approxDistinct(final String pColumn)
		{
			return null;
		}
	}

	private static String in(final List<String> pValues)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String v : pValues)
		{
			sb.append(sb.length() > 0 ? ", " : "").append('\'').append(v.replace("'", "''")).append('\'');
		}
		return sb.toString();
	}

	/** Case-insensitive field read (Snowflake answers upper-case aliases, Databricks lower-case). */
	static JsonNode field(final JsonNode pRow, final String pName)
	{
		if (pRow == null || !pRow.isObject())
		{
			return null;
		}
		JsonNode v = pRow.get(pName);
		if (v == null) v = pRow.get(pName.toUpperCase(java.util.Locale.ENGLISH));
		if (v == null) v = pRow.get(pName.toLowerCase(java.util.Locale.ENGLISH));
		if (v == null)
		{
			final Iterator<String> names = pRow.fieldNames();
			while (names.hasNext())
			{
				final String n = names.next();
				if (n.equalsIgnoreCase(pName)) return pRow.get(n);
			}
		}
		return v == null || v.isNull() ? null : v;
	}

	static String text(final JsonNode pRow, final String pName)
	{
		final JsonNode v = CatalogOperations.field(pRow, pName);
		return v == null ? null : v.asText();
	}

	static long number(final JsonNode pRow, final String pName, final long pDefault)
	{
		final JsonNode v = CatalogOperations.field(pRow, pName);
		if (v == null) return pDefault;
		if (v.isNumber()) return v.asLong();
		try
		{
			return (long) Double.parseDouble(v.asText());
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}

	static Double decimal(final JsonNode pRow, final String pName)
	{
		final JsonNode v = CatalogOperations.field(pRow, pName);
		if (v == null) return null;
		if (v.isNumber()) return Double.valueOf(v.asDouble());
		try
		{
			return Double.valueOf(Double.parseDouble(v.asText()));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	static List<JsonNode> rows(final JsonNode pResult)
	{
		final List<JsonNode> out = new ArrayList<JsonNode>();
		final JsonNode rows = pResult == null ? null : pResult.get("rows");
		if (rows != null && rows.isArray()) for (final JsonNode r : rows) out.add(r);
		return out;
	}

	/** Platform type name → the catalog's normalised vocabulary. */
	public static String normalisedType(final String pTypeName)
	{
		final String t = pTypeName == null ? "" : pTypeName.toUpperCase(java.util.Locale.ENGLISH);
		if (t.contains("JSON") || t.contains("VARIANT") || t.contains("OBJECT") || t.contains("STRUCT") || t.contains("MAP") || t.startsWith("ARRAY")) return "JSON";
		if (t.contains("CHAR") || t.contains("TEXT") || t.contains("STRING")) return "STRING";
		if (t.contains("BOOL")) return "BOOLEAN";
		if (t.contains("TIMESTAMP") || t.contains("DATETIME")) return "TIMESTAMP";
		if (t.equals("DATE") || t.startsWith("DATE ")) return "DATE";
		if (t.startsWith("TIME")) return "TIME";
		if (t.contains("BINARY") || t.contains("BLOB") || t.contains("BYTES")) return "BINARY";
		if (t.contains("INT") || t.contains("LONG") || t.contains("SHORT") || t.contains("BYTE")) return "INTEGER";
		if (t.contains("NUM") || t.contains("DEC") || t.contains("FLOAT") || t.contains("DOUBLE") || t.contains("REAL")) return "DECIMAL";
		return "OTHER";
	}

	/**
	 * {@code yyyy-MM-dd'T'HH:mm:ss}. {@link java.time.LocalDateTime#toString()} and
	 * {@code ISO_LOCAL_DATE_TIME} both drop the seconds when they are zero, and EBX then refuses the
	 * value as a dateTime — one scan a minute would fail on every asset it writes.
	 */
	public static final java.time.format.DateTimeFormatter TIMESTAMP = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	/**
	 * A date-time read from a source, as text ON EBX parses as a dateTime: the seconds are always written, a fraction is
	 * kept when there is one. {@code toString()} would give {@code 2026-11-20T10:15} for a whole minute, midnight included.
	 */
	public static String dateTime(final java.time.LocalDateTime pValue)
	{
		return pValue.getSecond() == 0 && pValue.getNano() == 0 ? pValue.format(CatalogOperations.TIMESTAMP) : pValue.toString();
	}

	/** The same rule for a date-time with an offset: {@code 2026-11-20T10:15:00+01:00}, never {@code 2026-11-20T10:15+01:00}. */
	public static String dateTime(final java.time.OffsetDateTime pValue)
	{
		return CatalogOperations.dateTime(pValue.toLocalDateTime()) + pValue.getOffset().getId();
	}

	/** The current local time as a dateTime EBX parses, seconds always written. */
	public static String timestamp()
	{
		return java.time.LocalDateTime.now().format(CatalogOperations.TIMESTAMP);
	}

	private static String now()
	{
		return CatalogOperations.timestamp();
	}

	/**
	 * Read recipe + deduced scan (2026-09-24): the JSON text an official catalog operation writes on every asset it
	 * finds, so the MDM Factory's landing knows how to read every row of that table without per-connector-type code —
	 * {@code {"connector","operation","inputs":{...},"itemsPath"}}. Shared by every official catalog writer (SQL
	 * family, Salesforce, files, EBX) so the shape never drifts between connector types.
	 */
	public static String readRecipe(final String pConnectorId, final String pOperation, final Map<String, String> pInputs, final String pItemsPath)
	{
		final ObjectNode recipe = Json.object();
		recipe.put("connector", pConnectorId);
		recipe.put("operation", pOperation);
		final ObjectNode inputs = recipe.putObject("inputs");
		if (pInputs != null)
		{
			for (final Map.Entry<String, String> input : pInputs.entrySet())
			{
				if (input.getValue() != null) inputs.put(input.getKey(), input.getValue());
			}
		}
		recipe.put("itemsPath", pItemsPath == null ? "" : pItemsPath);
		return Json.write(recipe);
	}

	private static List<String> schemas(final SqlRunner pRunner, final Dialect pDialect, final Map<String, String> pInputs, final String pDefault) throws ConnectorException
	{
		final List<String> schemas = new ArrayList<String>();
		final String requested = pInputs.get("schemas") == null || pInputs.get("schemas").trim().isEmpty() ? pDefault : pInputs.get("schemas");
		if (requested != null && !requested.trim().isEmpty() && !"*".equals(requested.trim()))
		{
			for (final String s : requested.split(",")) if (!s.trim().isEmpty()) schemas.add(s.trim());
			return schemas;
		}
		for (final JsonNode row : CatalogOperations.rows(pRunner.run(pDialect.schemasSql())))
		{
			final String s = CatalogOperations.text(row, "SCHEMA_NAME");
			if (s != null && !s.isEmpty()) schemas.add(s);
		}
		return schemas;
	}

	/**
	 * Groups the requested schemas by database: an entry spelled {@code DB.SCHEMA} is read from that
	 * database's information schema, a bare {@code SCHEMA} from the connector's own (key {@code ""}).
	 * Insertion order is the order of the filter, so the connector's own database keeps coming first
	 * when it is named first.
	 */
	private static Map<String, List<String>> byDatabase(final List<String> pSchemas)
	{
		final Map<String, List<String>> out = new java.util.LinkedHashMap<String, List<String>>();
		for (final String entry : pSchemas)
		{
			final int dot = entry.indexOf('.');
			final String database = dot > 0 && dot < entry.length() - 1 ? entry.substring(0, dot) : "";
			final String schema = database.isEmpty() ? entry : entry.substring(dot + 1);
			List<String> group = out.get(database);
			if (group == null)
			{
				group = new ArrayList<String>();
				out.put(database, group);
			}
			group.add(schema);
		}
		return out;
	}

	/** {@code SCHEMA} in the connector's own database, {@code DB.SCHEMA} in any other one. */
	private static String qualifiedSchema(final String pDatabase, final String pSchema)
	{
		if (pSchema == null || pSchema.isEmpty()) return pDatabase == null ? "" : pDatabase;
		return pDatabase == null ? pSchema : pDatabase + "." + pSchema;
	}

	// ------------------------------------------------------------------ catalog

	public static JsonNode catalog(final SqlRunner pRunner, final Dialect pDialect, final Map<String, String> pInputs, final String pDefaultSchema) throws ConnectorException
	{
		return CatalogOperations.catalog(pRunner, pDialect, pInputs, pDefaultSchema, null);
	}

	/**
	 * Same as {@link #catalog(SqlRunner, Dialect, Map, String)}, and also writes the read recipe (2026-09-24) of
	 * every table it finds: {@code SELECT <quoted columns> FROM <quoted qualified name>} through this connector's
	 * own {@code query} operation, {@code itemsPath = "rows"} (the shape {@link SqlRunner} already returns).
	 * {@code pConnectorId} is {@code null} when called only for the table list (profile, sample): no recipe needed.
	 */
	public static JsonNode catalog(final SqlRunner pRunner, final Dialect pDialect, final Map<String, String> pInputs, final String pDefaultSchema, final String pConnectorId) throws ConnectorException
	{
		final String source = AbstractRestConnector.require(pInputs, "source").trim();
		final List<String> schemas = CatalogOperations.schemas(pRunner, pDialect, pInputs, pDefaultSchema);
		final String types = pInputs.get("types") == null || pInputs.get("types").trim().isEmpty() ? "TABLE,VIEW" : pInputs.get("types").toUpperCase(java.util.Locale.ENGLISH);
		final String scannedAt = CatalogOperations.now();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("scannedAt", scannedAt);
		final ArrayNode tables = out.putArray("tables");
		if (schemas.isEmpty())
		{
			out.put("count", 0);
			return null;
		}
		final Map<String, ObjectNode> byName = new java.util.LinkedHashMap<String, ObjectNode>();
		for (final Map.Entry<String, List<String>> group : CatalogOperations.byDatabase(schemas).entrySet())
		{
			final String database = group.getKey().isEmpty() ? null : group.getKey();
			for (final JsonNode row : CatalogOperations.rows(pRunner.run(pDialect.tablesSql(database, group.getValue()))))
			{
				final String schema = CatalogOperations.qualifiedSchema(database, CatalogOperations.text(row, "TABLE_SCHEMA"));
				final String name = CatalogOperations.text(row, "TABLE_NAME");
				if (name == null) continue;
				final String type = CatalogOperations.text(row, "TABLE_TYPE") == null ? "TABLE" : CatalogOperations.text(row, "TABLE_TYPE").toUpperCase(java.util.Locale.ENGLISH);
				final String kind = type.contains("MATERIALIZED") ? "MATERIALIZED_VIEW" : type.contains("VIEW") ? "VIEW" : "TABLE";
				if (!types.contains(kind) && !("MATERIALIZED_VIEW".equals(kind) && types.contains("VIEW"))) continue;
				final String qualified = (schema.isEmpty() ? "" : schema + ".") + name;
				final ObjectNode t = tables.addObject();
				t.put("source", source);
				t.put("assetKey", source + ":" + qualified);
				t.put("qualifiedName", qualified);
				t.put("schema", schema);
				t.put("name", name);
				t.put("kind", kind);
				final String comment = CatalogOperations.text(row, "TABLE_COMMENT");
				if (comment != null && !comment.trim().isEmpty()) t.put("remarks", comment.trim());
				final long rowCount = CatalogOperations.number(row, "ROW_COUNT", -1);
				if (rowCount >= 0) t.put("rowCount", rowCount);
				t.put("scannedAt", scannedAt);
				t.putArray("columns");
				byName.put(qualified, t);
			}
			for (final JsonNode row : CatalogOperations.rows(pRunner.run(pDialect.columnsSql(database, group.getValue()))))
			{
				final String schema = CatalogOperations.qualifiedSchema(database, CatalogOperations.text(row, "TABLE_SCHEMA"));
				final String name = CatalogOperations.text(row, "TABLE_NAME");
				final String column = CatalogOperations.text(row, "COLUMN_NAME");
				final ObjectNode t = byName.get((schema.isEmpty() ? "" : schema + ".") + name);
				if (t == null || column == null) continue;
				final ObjectNode c = ((ArrayNode) t.get("columns")).addObject();
				c.put("columnKey", t.get("assetKey").asText() + "." + column);
				c.put("name", column);
				c.put("position", (int) CatalogOperations.number(row, "ORDINAL_POSITION", 0));
				final String nativeType = CatalogOperations.text(row, "DATA_TYPE") == null ? "" : CatalogOperations.text(row, "DATA_TYPE");
				c.put("dataType", CatalogOperations.normalisedType(nativeType));
				c.put("nativeType", nativeType);
				final String nullable = CatalogOperations.text(row, "IS_NULLABLE");
				c.put("nullable", nullable == null || !(nullable.equalsIgnoreCase("NO") || nullable.equalsIgnoreCase("false")));
				c.put("primaryKey", false);
				final String comment = CatalogOperations.text(row, "COLUMN_COMMENT");
				if (comment != null && !comment.trim().isEmpty()) c.put("remarks", comment.trim());
				c.put("scannedAt", scannedAt);
			}
		}
		if (pConnectorId != null)
		{
			for (final Map.Entry<String, ObjectNode> entry : byName.entrySet())
			{
				final ObjectNode t = entry.getValue();
				final StringBuilder columnList = new StringBuilder();
				for (final JsonNode column : t.get("columns"))
				{
					if (columnList.length() > 0) columnList.append(", ");
					columnList.append(pDialect.quote(column.get("name").asText()));
				}
				if (columnList.length() == 0) continue; // no column: no recipe (nothing a feed could read)
				final Map<String, String> recipeInputs = new java.util.LinkedHashMap<String, String>();
				recipeInputs.put("sql", "SELECT " + columnList + " FROM " + pDialect.quote(entry.getKey()));
				t.put("readRecipe", CatalogOperations.readRecipe(pConnectorId, "query", recipeInputs, "rows"));
				t.put("scanOrigin", "OFFICIAL");
				t.put("scanState", "CONFIRMED");
			}
		}
		out.put("count", tables.size());
		return tables.size() == 0 ? null : out;
	}

	// ------------------------------------------------------------------ profile

	public static JsonNode profile(final SqlRunner pRunner, final Dialect pDialect, final Map<String, String> pInputs, final String pDefaultSchema) throws ConnectorException
	{
		return CatalogOperations.profile(pRunner, pDialect, pInputs, pDefaultSchema, null);
	}

	/**
	 * Column statistics, one aggregate statement per table, within the budget of {@link ProfilePolicy} (audit C-15):
	 * at most {@code maxTables} tables and {@code maxSeconds} per run; a table whose catalog row count reaches
	 * {@code largeTableRows} is profiled on a block sample (or skipped, or scanned in full, as {@code largeTables} says);
	 * {@code dryRun=true} returns the statements and the estimated work and runs none of them. Every column says how
	 * its figures were obtained ({@code method} FULL_SCAN or SAMPLE with {@code sampleSize}/{@code samplePercent},
	 * {@code distinctMethod}, {@code exact}); the answer carries a {@code budget} block naming what was left out.
	 * {@code pProperties} answers connector properties ({@code profile.maxTables}...), may be null.
	 */
	public static JsonNode profile(final SqlRunner pRunner, final Dialect pDialect, final Map<String, String> pInputs, final String pDefaultSchema,
		final java.util.function.Function<String, String> pProperties) throws ConnectorException
	{
		final String source = AbstractRestConnector.require(pInputs, "source").trim();
		final ProfilePolicy policy = ProfilePolicy.of(pInputs, pProperties);
		final ProfilePolicy.Run budget = new ProfilePolicy.Run(policy);
		final JsonNode catalog = CatalogOperations.catalog(pRunner, pDialect, pInputs, pDefaultSchema);
		final java.time.LocalDateTime nowTs = java.time.LocalDateTime.now().withNano(0);
		final String profiledAt = nowTs.format(CatalogOperations.TIMESTAMP);
		final String profileDate = nowTs.toLocalDate().toString();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("profiledAt", profiledAt);
		out.put("profileDate", profileDate);
		final ArrayNode columns = out.putArray("columns");
		final String requested = pInputs.get("tables") == null || pInputs.get("tables").trim().isEmpty() ? "*" : pInputs.get("tables").trim();
		final Set<String> wanted = new LinkedHashSet<String>();
		if (!"*".equals(requested)) for (final String t : requested.split(",")) if (!t.trim().isEmpty()) wanted.add(t.trim().toUpperCase(java.util.Locale.ENGLISH));
		final String where = pInputs.get("where") == null || pInputs.get("where").trim().isEmpty() ? "" : " WHERE " + pInputs.get("where").trim();
		if (catalog == null)
		{
			out.put("count", 0);
			return null;
		}
		final boolean sampleSupported = pDialect.sampleClause(50d) != null;
		final boolean approxSupported = pDialect.approxDistinct("C") != null;
		final ArrayNode plan = policy.dryRun() ? out.putObject("plan").putArray("tables") : null;
		boolean allExact = true;
		long estimatedRows = 0L;
		int unknownSizes = 0;
		for (final JsonNode table : catalog.get("tables"))
		{
			final String qualified = table.get("qualifiedName").asText();
			if (!wanted.isEmpty() && !wanted.contains(qualified.toUpperCase(java.util.Locale.ENGLISH))) continue;
			final List<JsonNode> cols = new ArrayList<JsonNode>();
			for (final JsonNode c : table.get("columns")) if (cols.size() < 200) cols.add(c);
			if (cols.isEmpty()) continue;
			if (!budget.mayStartTable())
			{
				budget.skipForBudget(qualified);
				continue;
			}
			final long catalogRows = table.has("rowCount") ? table.get("rowCount").asLong(-1) : -1L;
			final ProfilePolicy.Decision decision = policy.decide(catalogRows, sampleSupported);
			if (decision.mode() == ProfilePolicy.Mode.SKIP)
			{
				budget.skip(qualified, decision.reason());
				continue;
			}
			final boolean sampled = decision.mode() == ProfilePolicy.Mode.SAMPLE;
			final boolean approx = policy.approxDistinct() && approxSupported;
			final String sql = CatalogOperations.profileSql(pDialect, qualified, cols, where, decision, approx);
			budget.profiled(sampled);
			allExact = allExact && !sampled && !approx;
			if (plan != null)
			{
				final ObjectNode item = plan.addObject();
				item.put("table", qualified);
				item.put("columns", cols.size());
				item.put("rowCount", catalogRows);
				item.put("method", sampled ? RecordProfiler.METHOD_SAMPLE : RecordProfiler.METHOD_FULL_SCAN);
				if (sampled) item.put("samplePercent", decision.percent());
				item.put("sql", sql);
				if (catalogRows < 0) unknownSizes++;
				else estimatedRows += sampled ? Math.min(catalogRows, policy.sampleRows()) : catalogRows;
				continue;
			}
			final List<JsonNode> rows = CatalogOperations.rows(pRunner.run(sql));
			if (rows.isEmpty()) continue;
			final JsonNode r = rows.get(0);
			final long scanned = CatalogOperations.number(r, "N0", 0);
			// a sample's own row count is the sample's; the table's is the catalog's when it knows it
			final long rowCount = sampled && catalogRows >= 0 ? catalogRows : scanned;
			int i = 0;
			for (final JsonNode col : cols)
			{
				final ObjectNode c = columns.addObject();
				c.put("source", source);
				c.put("assetKey", table.get("assetKey").asText());
				c.put("qualifiedName", qualified);
				c.put("columnKey", col.get("columnKey").asText());
				c.put("name", col.get("name").asText());
				c.put("dataType", col.get("dataType").asText());
				c.put("profiledAt", profiledAt);
				c.put("profileDate", profileDate);
				c.put("method", sampled ? RecordProfiler.METHOD_SAMPLE : RecordProfiler.METHOD_FULL_SCAN);
				if (sampled)
				{
					c.put("sampleSize", scanned);
					c.put("samplePercent", decision.percent());
				}
				c.put("distinctMethod", approx ? "APPROXIMATE" : "EXACT");
				c.put("exact", !sampled && !approx);
				c.put("rowCount", rowCount);
				final long nonNull = CatalogOperations.number(r, "C" + i, 0);
				final long distinct = CatalogOperations.number(r, "D" + i, 0);
				final double nullRate = scanned == 0 ? 0d : CatalogOperations.round4((double) (scanned - nonNull) / scanned);
				c.put("nullCount", sampled ? Math.round(nullRate * rowCount) : scanned - nonNull);
				c.put("nullRate", nullRate);
				c.put("distinctCount", distinct);
				c.put("uniqueRate", nonNull == 0 ? 0d : CatalogOperations.round4((double) distinct / nonNull));
				final String min = CatalogOperations.text(r, "MI" + i);
				final String max = CatalogOperations.text(r, "MA" + i);
				if (min != null) c.put("minValue", min.length() > 500 ? min.substring(0, 500) : min);
				if (max != null) c.put("maxValue", max.length() > 500 ? max.substring(0, 500) : max);
				final Double mean = CatalogOperations.decimal(r, "AV" + i);
				if (mean != null) c.put("meanValue", CatalogOperations.round4(mean.doubleValue()));
				final Double lmi = CatalogOperations.decimal(r, "LMI" + i);
				final Double lma = CatalogOperations.decimal(r, "LMA" + i);
				final Double lav = CatalogOperations.decimal(r, "LAV" + i);
				if (lmi != null) c.put("minLength", lmi.intValue());
				if (lma != null) c.put("maxLength", lma.intValue());
				if (lav != null) c.put("avgLength", CatalogOperations.round4(lav.doubleValue()));
				i++;
			}
		}
		out.put("exact", allExact && !budget.partial());
		out.put("partial", budget.partial());
		out.set("budget", budget.toJson());
		out.put("count", columns.size());
		if (policy.dryRun())
		{
			out.put("dryRun", true);
			final ObjectNode summary = (ObjectNode) out.get("plan");
			summary.put("statements", budget.tablesProfiled());
			summary.put("estimatedRowsScanned", estimatedRows);
			summary.put("tablesOfUnknownSize", unknownSizes);
			summary.put("note", "dry run: no profile statement was executed; estimatedRowsScanned counts the catalog row count of each table "
				+ "(the sample size for a sampled one), tables of unknown size excluded");
			return out;
		}
		return columns.size() == 0 && !budget.partial() ? null : out;
	}

	/**
	 * The aggregate statement of one table: counts, distinct counts, min/max, mean, lengths of up to 200 columns,
	 * on the whole table or on the block sample {@code pDecision} asks for, distinct counts exact or approximate.
	 * Pure: unit-tested per dialect without a platform.
	 */
	static String profileSql(final Dialect pDialect, final String pQualified, final List<JsonNode> pCols, final String pWhere,
		final ProfilePolicy.Decision pDecision, final boolean pApprox)
	{
		final StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS N0");
		int i = 0;
		for (final JsonNode col : pCols)
		{
			final String c = pDialect.quote(col.get("name").asText());
			final String type = col.get("dataType").asText();
			final String distinct = pApprox ? pDialect.approxDistinct(c) : "COUNT(DISTINCT " + c + ")";
			sql.append(", COUNT(").append(c).append(") AS C").append(i).append(", ").append(distinct).append(" AS D").append(i);
			if ("STRING".equals(type) || "INTEGER".equals(type) || "DECIMAL".equals(type) || "DATE".equals(type) || "TIMESTAMP".equals(type) || "TIME".equals(type))
			{
				sql.append(", MIN(").append(c).append(") AS MI").append(i).append(", MAX(").append(c).append(") AS MA").append(i);
			}
			else
			{
				sql.append(", NULL AS MI").append(i).append(", NULL AS MA").append(i);
			}
			sql.append("INTEGER".equals(type) || "DECIMAL".equals(type) ? ", AVG(" + pDialect.toDouble(c) + ") AS AV" + i : ", NULL AS AV" + i);
			if ("STRING".equals(type))
			{
				sql.append(", MIN(").append(pDialect.length(c)).append(") AS LMI").append(i).append(", MAX(").append(pDialect.length(c)).append(") AS LMA").append(i)
					.append(", AVG(").append(pDialect.toDouble(pDialect.length(c))).append(") AS LAV").append(i);
			}
			else
			{
				sql.append(", NULL AS LMI").append(i).append(", NULL AS LMA").append(i).append(", NULL AS LAV").append(i);
			}
			i++;
		}
		sql.append(" FROM ").append(pDialect.quote(pQualified));
		if (pDecision != null && pDecision.mode() == ProfilePolicy.Mode.SAMPLE)
		{
			sql.append(' ').append(pDialect.sampleClause(pDecision.percent()));
		}
		sql.append(pWhere);
		return sql.toString();
	}

	// ------------------------------------------------------------------ sample

	public static JsonNode sample(final SqlRunner pRunner, final Dialect pDialect, final Map<String, String> pInputs, final String pDefaultSchema) throws ConnectorException
	{
		final String source = AbstractRestConnector.require(pInputs, "source").trim();
		final JsonNode catalog = CatalogOperations.catalog(pRunner, pDialect, pInputs, pDefaultSchema);
		int limit = 50;
		try
		{
			limit = Math.max(1, Math.min(500, Integer.parseInt(pInputs.get("limit") == null ? "50" : pInputs.get("limit").trim())));
		}
		catch (final RuntimeException ex)
		{
			limit = 50;
		}
		final String requested = pInputs.get("tables") == null || pInputs.get("tables").trim().isEmpty() ? "*" : pInputs.get("tables").trim();
		final Set<String> wanted = new LinkedHashSet<String>();
		if (!"*".equals(requested)) for (final String t : requested.split(",")) if (!t.trim().isEmpty()) wanted.add(t.trim().toUpperCase(java.util.Locale.ENGLISH));
		final String sampledAt = CatalogOperations.now();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("sampledAt", sampledAt);
		final ArrayNode columns = out.putArray("columns");
		if (catalog == null)
		{
			out.put("count", 0);
			return null;
		}
		for (final JsonNode table : catalog.get("tables"))
		{
			final String qualified = table.get("qualifiedName").asText();
			if (!wanted.isEmpty() && !wanted.contains(qualified.toUpperCase(java.util.Locale.ENGLISH))) continue;
			final List<String> text = new ArrayList<String>();
			for (final JsonNode c : table.get("columns")) if ("STRING".equals(c.get("dataType").asText()) && text.size() < 200) text.add(c.get("name").asText());
			if (text.isEmpty()) continue;
			final StringBuilder sql = new StringBuilder("SELECT ");
			for (int i = 0; i < text.size(); i++) sql.append(i > 0 ? ", " : "").append(pDialect.quote(text.get(i)));
			sql.append(" FROM ").append(pDialect.quote(qualified)).append(' ').append(pDialect.limit(limit));
			final List<JsonNode> rows = CatalogOperations.rows(pRunner.run(sql.toString()));
			final List<LinkedHashSet<String>> values = new ArrayList<LinkedHashSet<String>>();
			for (int i = 0; i < text.size(); i++) values.add(new LinkedHashSet<String>());
			for (final JsonNode row : rows)
			{
				for (int i = 0; i < text.size(); i++)
				{
					final String v = CatalogOperations.text(row, text.get(i));
					if (v != null && !v.trim().isEmpty()) values.get(i).add(v.length() > 200 ? v.substring(0, 200) : v);
				}
			}
			for (int i = 0; i < text.size(); i++)
			{
				final ObjectNode c = columns.addObject();
				c.put("source", source);
				c.put("assetKey", table.get("assetKey").asText());
				c.put("qualifiedName", qualified);
				c.put("columnKey", table.get("assetKey").asText() + "." + text.get(i));
				c.put("name", text.get(i));
				c.put("dataType", "STRING");
				c.put("sampledAt", sampledAt);
				c.put("sampleSize", rows.size());
				final ArrayNode array = c.putArray("values");
				final StringBuilder joined = new StringBuilder();
				for (final String v : values.get(i))
				{
					array.add(v);
					joined.append(joined.length() > 0 ? "\n" : "").append(v.replace('\n', ' ').replace('\r', ' '));
				}
				c.put("valuesText", joined.toString());
			}
		}
		out.put("count", columns.size());
		return columns.size() == 0 ? null : out;
	}

	// ------------------------------------------------------------------ usage

	public static JsonNode usage(final SqlRunner pRunner, final Dialect pDialect, final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = AbstractRestConnector.require(pInputs, "source").trim();
		final String sql = pDialect.usageSql();
		if (sql == null)
		{
			throw new ConnectorException("usage: this platform exposes no query history");
		}
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("measuredAt", CatalogOperations.now());
		final ArrayNode items = out.putArray("items");
		for (final JsonNode row : CatalogOperations.rows(pRunner.run(sql)))
		{
			final String full = CatalogOperations.text(row, "OBJECT_NAME");
			if (full == null) continue;
			final String local = pDialect.localName(full);
			if (local == null) continue;
			final ObjectNode item = items.addObject();
			item.put("source", source);
			item.put("assetKey", source + ":" + local);
			item.put("qualifiedName", local);
			item.put("queryCount30d", CatalogOperations.number(row, "QUERY_COUNT", 0));
		}
		out.put("count", items.size());
		return items.size() == 0 ? null : out;
	}

	// ------------------------------------------------------------------ lineage

	public static JsonNode lineage(final SqlRunner pRunner, final Dialect pDialect, final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = AbstractRestConnector.require(pInputs, "source").trim();
		final String sql = pDialect.lineageSql();
		if (sql == null)
		{
			throw new ConnectorException("lineage: this platform exposes no lineage views");
		}
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("scannedAt", CatalogOperations.now());
		final ArrayNode edges = out.putArray("edges");
		final Set<String> seen = new LinkedHashSet<String>();
		for (final JsonNode row : CatalogOperations.rows(pRunner.run(sql)))
		{
			final String from = pDialect.localName(CatalogOperations.text(row, "SOURCE_TABLE"));
			final String to = pDialect.localName(CatalogOperations.text(row, "TARGET_TABLE"));
			if (from == null || to == null) continue;
			final String fromColumn = CatalogOperations.text(row, "SOURCE_COLUMN");
			final String toColumn = CatalogOperations.text(row, "TARGET_COLUMN");
			final String fromAssetKey = source + ":" + from;
			final String toAssetKey = source + ":" + to;
			final String fromColumnKey = fromColumn == null || fromColumn.isEmpty() ? null : fromAssetKey + "." + fromColumn;
			final String toColumnKey = toColumn == null || toColumn.isEmpty() ? null : toAssetKey + "." + toColumn;
			final String edgeKey = (fromColumnKey == null ? fromAssetKey : fromColumnKey) + ">" + (toColumnKey == null ? toAssetKey : toColumnKey);
			final String seenAt = CatalogOperations.text(row, "SEEN_AT");
			if (!seen.add(edgeKey)) continue;
			final ObjectNode e = edges.addObject();
			e.put("source", source);
			e.put("edgeKey", edgeKey);
			e.put("fromAssetKey", fromAssetKey);
			if (fromColumnKey != null) e.put("fromColumnKey", fromColumnKey);
			e.put("toAssetKey", toAssetKey);
			if (toColumnKey != null) e.put("toColumnKey", toColumnKey);
			e.put("transformationType", "UNKNOWN");
			e.put("origin", "PLATFORM");
			e.put("seenAt", seenAt == null ? CatalogOperations.now() : seenAt);
		}
		out.put("count", edges.size());
		return edges.size() == 0 ? null : out;
	}

	static double round4(final double pValue)
	{
		return Math.round(pValue * 10000d) / 10000d;
	}
}
