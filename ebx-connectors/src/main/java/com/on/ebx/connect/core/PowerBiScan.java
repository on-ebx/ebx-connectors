package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Turns a Power BI Scanner API result ({@code GET admin/workspaces/scanResult/{id}}, or the
 * equivalent document assembled from the standard APIs) into the two documents the data-catalog
 * flows consume, with the same field names as {@link CatalogOperations}:
 * <ul>
 * <li>{@link #catalog}: {@code {tables:[{assetKey, qualifiedName, schema, name, kind, url, remarks,
 * columns:[{columnKey, name, position, dataType, nativeType, expression...}]}]}} — one asset per
 * report ({@code REPORT}), dashboard ({@code DASHBOARD}), semantic model ({@code SEMANTIC_MODEL}),
 * semantic model table ({@code TABLE}, columns = data columns + calculated columns + measures),
 * dataflow ({@code DATAFLOW}) and dataflow entity ({@code TABLE}); plus, unless a data source is
 * mapped to a catalog source through {@link Options#datasourceMap}, one {@code TABLE} asset per
 * external table read by Power Query.</li>
 * <li>{@link #lineage}: {@code {edges:[{edgeKey, fromAssetKey, fromColumnKey, toAssetKey,
 * toColumnKey, transformationType, transformation, confidence, seenAt}]}} — data source table →
 * model table (Power Query M, table level plus one column edge per pass-through column), column →
 * calculated column or measure (DAX), measure → measure, semantic model → report, report or model →
 * dashboard, dataflow → model, data source → dataflow entity, model → model (composite).</li>
 * </ul>
 * Asset naming: {@code <workspace>/reports/<name>}, {@code <workspace>/dashboards/<name>},
 * {@code <workspace>/models/<model>} and {@code <workspace>/models/<model>.<table>},
 * {@code <workspace>/dataflows/<dataflow>} and {@code <workspace>/dataflows/<dataflow>.<entity>},
 * {@code datasources/<server>[/<database>]/<schema>.<table>} for an unmapped external table. A
 * mapped data source ({@code server[/database]=SOURCE[:upper|:lower]}) gives
 * {@code SOURCE:<schema>.<table>}, the key the scan of that source produces itself.
 * <p>
 * Pure functions, no HTTP: the connector fetches, this class interprets. Power Query and DAX are
 * read with a small lexer and a few structural rules (navigation records, {@code Table.*} steps,
 * {@code Table[Column]} references), not with a full parser: an expression this class does not
 * understand yields a table-level edge of type {@code UNKNOWN}, never an exception.
 */
public final class PowerBiScan
{
	public static final String KIND_REPORT = "REPORT";
	public static final String KIND_DASHBOARD = "DASHBOARD";
	public static final String KIND_MODEL = "SEMANTIC_MODEL";
	public static final String KIND_DATAFLOW = "DATAFLOW";
	public static final String KIND_TABLE = "TABLE";

	/** Where a Power Query data source lives in the catalog: the source id, and how that source spells names. */
	public static final class DatasourceTarget
	{
		final String source;
		final String caseMode;

		public DatasourceTarget(final String pSource, final String pCaseMode)
		{
			this.source = pSource;
			this.caseMode = pCaseMode == null ? "" : pCaseMode.toLowerCase(Locale.ENGLISH);
		}

		String spell(final String pName)
		{
			if (pName == null) return null;
			if ("upper".equals(this.caseMode)) return pName.toUpperCase(Locale.ENGLISH);
			if ("lower".equals(this.caseMode)) return pName.toLowerCase(Locale.ENGLISH);
			return pName;
		}
	}

	public static final class Options
	{
		public String source = "POWERBI";
		public String scannedAt;
		/** Lower-case workspace names or ids; empty = every scanned workspace. */
		public Set<String> workspaces = Collections.emptySet();
		/** Asset kinds to emit; null = all. */
		public Set<String> kinds;
		/** {@code server} or {@code server/database} (lower case) → catalog source. */
		public Map<String, DatasourceTarget> datasourceMap = new LinkedHashMap<String, DatasourceTarget>();
		/** Emit an asset for each unmapped external table (otherwise its edges are dropped). */
		public boolean externalAssets = true;
		public boolean includeHidden = false;
		public boolean includePersonal = false;
		public String portalUrl = "https://app.powerbi.com";

		/** Parses {@code server[/database]=SOURCE[:upper|:lower]} entries separated by newlines or semicolons. */
		public static Map<String, DatasourceTarget> parseDatasourceMap(final String pText)
		{
			final Map<String, DatasourceTarget> map = new LinkedHashMap<String, DatasourceTarget>();
			if (pText == null) return map;
			for (final String entry : pText.split("[\\n;]"))
			{
				final int eq = entry.indexOf('=');
				if (eq <= 0) continue;
				final String key = entry.substring(0, eq).trim().toLowerCase(Locale.ENGLISH);
				String target = entry.substring(eq + 1).trim();
				String mode = null;
				final int colon = target.lastIndexOf(':');
				if (colon > 0)
				{
					mode = target.substring(colon + 1);
					target = target.substring(0, colon);
				}
				if (!key.isEmpty() && !target.isEmpty()) map.put(key, new DatasourceTarget(target, mode));
			}
			return map;
		}
	}

	/** The two feeds of one scan. */
	public static final class Result
	{
		public final ObjectNode catalog;
		public final ObjectNode lineage;
		public final List<String> notes = new ArrayList<String>();

		Result(final ObjectNode pCatalog, final ObjectNode pLineage)
		{
			this.catalog = pCatalog;
			this.lineage = pLineage;
		}
	}

	private PowerBiScan()
	{
	}

	public static JsonNode catalog(final JsonNode pScanResult, final Options pOptions)
	{
		final Result result = PowerBiScan.scan(pScanResult, pOptions);
		return result.catalog.get("count").asInt() == 0 ? null : result.catalog;
	}

	public static JsonNode lineage(final JsonNode pScanResult, final Options pOptions)
	{
		final Result result = PowerBiScan.scan(pScanResult, pOptions);
		return result.lineage.get("count").asInt() == 0 ? null : result.lineage;
	}

	// ------------------------------------------------------------------ scan

	/** One scanned object with its asset key and, for a semantic model, its tables and measures. */
	static final class Item
	{
		final String assetKey;
		final String workspaceName;
		final String workspaceId;
		final String name;
		final JsonNode node;
		final Map<String, String> tables = new LinkedHashMap<String, String>();
		/** measure name → column key, over every table of the model */
		final Map<String, String> measures = new LinkedHashMap<String, String>();
		/** table name → (column name → column key) */
		final Map<String, Map<String, String>> columns = new LinkedHashMap<String, Map<String, String>>();

		Item(final String pAssetKey, final String pWorkspaceName, final String pWorkspaceId, final String pName, final JsonNode pNode)
		{
			this.assetKey = pAssetKey;
			this.workspaceName = pWorkspaceName;
			this.workspaceId = pWorkspaceId;
			this.name = pName;
			this.node = pNode;
		}
	}

	static final class Scan
	{
		final Options options;
		final String scannedAt;
		final ObjectNode catalog = Json.object();
		final ArrayNode tables;
		final ObjectNode lineage = Json.object();
		final ArrayNode edges;
		final Map<String, ObjectNode> assets = new LinkedHashMap<String, ObjectNode>();
		final Set<String> edgeKeys = new LinkedHashSet<String>();
		final Map<String, Item> models = new LinkedHashMap<String, Item>();
		final Map<String, Item> reports = new LinkedHashMap<String, Item>();
		final Map<String, Item> dashboards = new LinkedHashMap<String, Item>();
		final Map<String, Item> dataflows = new LinkedHashMap<String, Item>();
		final Map<String, JsonNode> datasourceInstances = new LinkedHashMap<String, JsonNode>();
		final List<String> notes = new ArrayList<String>();

		Scan(final Options pOptions)
		{
			this.options = pOptions;
			this.scannedAt = pOptions.scannedAt == null ? CatalogOperations.timestamp() : pOptions.scannedAt;
			this.catalog.put("source", pOptions.source);
			this.catalog.put("scannedAt", this.scannedAt);
			this.tables = this.catalog.putArray("tables");
			this.lineage.put("source", pOptions.source);
			this.lineage.put("scannedAt", this.scannedAt);
			this.edges = this.lineage.putArray("edges");
		}

		boolean wanted(final String pKind)
		{
			return this.options.kinds == null || this.options.kinds.contains(pKind);
		}

		ObjectNode asset(final String pQualifiedName, final String pSchema, final String pName, final String pKind, final String pRemarks, final String pUrl)
		{
			final String key = this.options.source + ":" + pQualifiedName;
			ObjectNode t = this.assets.get(key);
			if (t != null) return t;
			t = Json.object();
			t.put("source", this.options.source);
			t.put("assetKey", key);
			t.put("qualifiedName", pQualifiedName);
			t.put("schema", pSchema == null ? "" : pSchema);
			t.put("name", pName);
			t.put("kind", pKind);
			if (pRemarks != null && !pRemarks.trim().isEmpty()) t.put("remarks", PowerBiScan.cut(pRemarks.trim(), 4000));
			if (pUrl != null) t.put("url", pUrl);
			t.put("scannedAt", this.scannedAt);
			t.putArray("columns");
			this.assets.put(key, t);
			if (this.wanted(pKind)) this.tables.add(t);
			return t;
		}

		ObjectNode column(final ObjectNode pAsset, final String pName, final String pDataType, final String pNativeType, final String pRemarks, final String pExpression)
		{
			final ArrayNode columns = (ArrayNode) pAsset.get("columns");
			for (final JsonNode existing : columns)
			{
				if (pName.equals(existing.path("name").asText())) return (ObjectNode) existing;
			}
			final ObjectNode c = columns.addObject();
			c.put("columnKey", pAsset.get("assetKey").asText() + "." + pName);
			c.put("name", pName);
			c.put("position", columns.size());
			c.put("dataType", pDataType == null ? "OTHER" : pDataType);
			c.put("nativeType", pNativeType == null ? "" : pNativeType);
			c.put("nullable", true);
			c.put("primaryKey", false);
			if (pRemarks != null && !pRemarks.trim().isEmpty()) c.put("remarks", PowerBiScan.cut(pRemarks.trim(), 4000));
			if (pExpression != null && !pExpression.trim().isEmpty()) c.put("expression", PowerBiScan.cut(pExpression.trim(), 4000));
			c.put("scannedAt", this.scannedAt);
			return c;
		}

		void edge(final String pFromAsset, final String pFromColumn, final String pToAsset, final String pToColumn, final String pType,
			final String pTransformation, final int pConfidence)
		{
			if (pFromAsset == null || pToAsset == null || pFromAsset.equals(pToAsset) && (pFromColumn == null || pFromColumn.equals(pToColumn))) return;
			final String edgeKey = (pFromColumn == null ? pFromAsset : pFromColumn) + ">" + (pToColumn == null ? pToAsset : pToColumn);
			if (!this.edgeKeys.add(edgeKey)) return;
			final ObjectNode e = this.edges.addObject();
			e.put("source", this.options.source);
			e.put("edgeKey", edgeKey);
			e.put("fromAssetKey", pFromAsset);
			if (pFromColumn != null) e.put("fromColumnKey", pFromColumn);
			e.put("toAssetKey", pToAsset);
			if (pToColumn != null) e.put("toColumnKey", pToColumn);
			e.put("transformationType", pType == null ? "UNKNOWN" : pType);
			if (pTransformation != null && !pTransformation.trim().isEmpty()) e.put("transformation", PowerBiScan.cut(pTransformation.trim(), 4000));
			e.put("origin", "PLATFORM");
			e.put("confidence", pConfidence);
			e.put("seenAt", this.scannedAt);
		}
	}

	public static Result scan(final JsonNode pScanResult, final Options pOptions)
	{
		final Scan s = new Scan(pOptions);
		if (pScanResult != null)
		{
			for (final JsonNode instance : pScanResult.path("datasourceInstances"))
			{
				final String id = instance.path("datasourceId").asText(null);
				if (id != null) s.datasourceInstances.put(id, instance);
			}
			final List<JsonNode> workspaces = new ArrayList<JsonNode>();
			for (final JsonNode ws : pScanResult.path("workspaces"))
			{
				if (PowerBiScan.selected(ws, pOptions)) workspaces.add(ws);
			}
			for (final JsonNode ws : workspaces) PowerBiScan.inventory(s, ws);
			for (final JsonNode ws : workspaces) PowerBiScan.links(s, ws);
		}
		s.catalog.put("count", s.tables.size());
		s.lineage.put("count", s.edges.size());
		final Result result = new Result(s.catalog, s.lineage);
		result.notes.addAll(s.notes);
		return result;
	}

	public static boolean selected(final JsonNode pWorkspace, final Options pOptions)
	{
		final String state = pWorkspace.path("state").asText("Active");
		if ("Deleted".equalsIgnoreCase(state) || "Removing".equalsIgnoreCase(state)) return false;
		if (!pOptions.includePersonal && "PersonalGroup".equalsIgnoreCase(pWorkspace.path("type").asText(""))) return false;
		if (pOptions.workspaces == null || pOptions.workspaces.isEmpty()) return true;
		final String name = pWorkspace.path("name").asText("").toLowerCase(Locale.ENGLISH);
		final String id = pWorkspace.path("id").asText("").toLowerCase(Locale.ENGLISH);
		return pOptions.workspaces.contains(name) || pOptions.workspaces.contains(id);
	}

	// ------------------------------------------------------------------ pass 1: assets

	static void inventory(final Scan s, final JsonNode pWorkspace)
	{
		final String ws = pWorkspace.path("name").asText("");
		final String wsId = pWorkspace.path("id").asText("");
		final String portal = s.options.portalUrl == null ? null : s.options.portalUrl.replaceAll("/+$", "");
		for (final JsonNode model : pWorkspace.path("datasets"))
		{
			final String name = model.path("name").asText("");
			if (name.isEmpty()) continue;
			final String qualified = ws + "/models/" + name;
			final ObjectNode asset = s.asset(qualified, ws, name, KIND_MODEL, PowerBiScan.modelRemarks(model),
				portal == null ? null : portal + "/groups/" + wsId + "/datasets/" + model.path("id").asText("") + "/details");
			final Item item = new Item(asset.get("assetKey").asText(), ws, wsId, name, model);
			s.models.put(model.path("id").asText(qualified), item);
			for (final JsonNode table : model.path("tables"))
			{
				final String tableName = table.path("name").asText("");
				if (tableName.isEmpty() || !s.options.includeHidden && table.path("isHidden").asBoolean(false)) continue;
				final ObjectNode t = s.asset(qualified + "." + tableName, qualified, tableName, KIND_TABLE, table.path("description").asText(null), null);
				item.tables.put(tableName, t.get("assetKey").asText());
				final Map<String, String> columns = new LinkedHashMap<String, String>();
				item.columns.put(tableName, columns);
				for (final JsonNode column : table.path("columns"))
				{
					final String columnName = column.path("name").asText("");
					if (columnName.isEmpty() || !s.options.includeHidden && column.path("isHidden").asBoolean(false)) continue;
					final boolean calculated = "Calculated".equalsIgnoreCase(column.path("columnType").asText("")) || column.hasNonNull("expression");
					final String dataType = column.path("dataType").asText("");
					final ObjectNode c = s.column(t, columnName, PowerBiScan.normalisedType(dataType), calculated ? "CALCULATED " + dataType : dataType,
						column.path("description").asText(null), calculated ? column.path("expression").asText(null) : null);
					columns.put(columnName, c.get("columnKey").asText());
				}
				for (final JsonNode measure : table.path("measures"))
				{
					final String measureName = measure.path("name").asText("");
					if (measureName.isEmpty() || !s.options.includeHidden && measure.path("isHidden").asBoolean(false)) continue;
					final ObjectNode c = s.column(t, measureName, "DECIMAL", "MEASURE", measure.path("description").asText(null), measure.path("expression").asText(null));
					item.measures.put(measureName, c.get("columnKey").asText());
					columns.put(measureName, c.get("columnKey").asText());
				}
			}
		}
		for (final JsonNode report : pWorkspace.path("reports"))
		{
			final String name = report.path("name").asText("");
			if (name.isEmpty()) continue;
			final ObjectNode asset = s.asset(ws + "/reports/" + name, ws, name, KIND_REPORT, PowerBiScan.reportRemarks(report),
				portal == null ? null : portal + "/groups/" + wsId + "/reports/" + report.path("id").asText(""));
			s.reports.put(report.path("id").asText(name), new Item(asset.get("assetKey").asText(), ws, wsId, name, report));
		}
		for (final JsonNode dashboard : pWorkspace.path("dashboards"))
		{
			final String name = dashboard.path("displayName").asText(dashboard.path("name").asText(""));
			if (name.isEmpty()) continue;
			final ObjectNode asset = s.asset(ws + "/dashboards/" + name, ws, name, KIND_DASHBOARD, null,
				portal == null ? null : portal + "/groups/" + wsId + "/dashboards/" + dashboard.path("id").asText(""));
			s.dashboards.put(dashboard.path("id").asText(name), new Item(asset.get("assetKey").asText(), ws, wsId, name, dashboard));
		}
		for (final JsonNode dataflow : pWorkspace.path("dataflows"))
		{
			final String name = dataflow.path("name").asText("");
			if (name.isEmpty()) continue;
			final String id = dataflow.path("objectId").asText(dataflow.path("id").asText(name));
			final String qualified = ws + "/dataflows/" + name;
			final ObjectNode asset = s.asset(qualified, ws, name, KIND_DATAFLOW, dataflow.path("description").asText(null),
				portal == null ? null : portal + "/groups/" + wsId + "/dataflows/" + id);
			final Item item = new Item(asset.get("assetKey").asText(), ws, wsId, name, dataflow);
			s.dataflows.put(id, item);
			for (final JsonNode entity : dataflow.path("definition").path("entities"))
			{
				final String entityName = entity.path("name").asText("");
				if (entityName.isEmpty()) continue;
				final ObjectNode t = s.asset(qualified + "." + entityName, qualified, entityName, KIND_TABLE, entity.path("description").asText(null), null);
				item.tables.put(entityName, t.get("assetKey").asText());
				final Map<String, String> columns = new LinkedHashMap<String, String>();
				item.columns.put(entityName, columns);
				for (final JsonNode attribute : entity.path("attributes"))
				{
					final String columnName = attribute.path("name").asText("");
					if (columnName.isEmpty()) continue;
					final String dataType = attribute.path("dataType").asText("");
					columns.put(columnName, s.column(t, columnName, PowerBiScan.normalisedType(dataType), dataType, attribute.path("description").asText(null), null).get("columnKey").asText());
				}
			}
		}
	}

	static String modelRemarks(final JsonNode pModel)
	{
		final StringBuilder b = new StringBuilder();
		final String description = pModel.path("description").asText("");
		if (!description.isEmpty()) b.append(description);
		final String by = pModel.path("configuredBy").asText("");
		if (!by.isEmpty()) b.append(b.length() == 0 ? "" : "\n").append("Configured by ").append(by);
		final String mode = pModel.path("contentProviderType").asText("");
		if (!mode.isEmpty()) b.append(b.length() == 0 ? "" : "\n").append("Storage mode: ").append(mode);
		return b.length() == 0 ? null : b.toString();
	}

	static String reportRemarks(final JsonNode pReport)
	{
		final StringBuilder b = new StringBuilder();
		final String description = pReport.path("description").asText("");
		if (!description.isEmpty()) b.append(description);
		final String by = pReport.path("modifiedBy").asText(pReport.path("createdBy").asText(""));
		if (!by.isEmpty()) b.append(b.length() == 0 ? "" : "\n").append("Last modified by ").append(by);
		return b.length() == 0 ? null : b.toString();
	}

	// ------------------------------------------------------------------ pass 2: edges

	static void links(final Scan s, final JsonNode pWorkspace)
	{
		for (final JsonNode report : pWorkspace.path("reports"))
		{
			final Item r = s.reports.get(report.path("id").asText(report.path("name").asText("")));
			final Item model = s.models.get(report.path("datasetId").asText(""));
			if (r != null && model != null) s.edge(model.assetKey, null, r.assetKey, null, "IDENTITY", "Report on semantic model " + model.name, 100);
		}
		for (final JsonNode dashboard : pWorkspace.path("dashboards"))
		{
			final Item d = s.dashboards.get(dashboard.path("id").asText(dashboard.path("displayName").asText("")));
			if (d == null) continue;
			for (final JsonNode tile : dashboard.path("tiles"))
			{
				final Item report = s.reports.get(tile.path("reportId").asText(""));
				if (report != null)
				{
					s.edge(report.assetKey, null, d.assetKey, null, "IDENTITY", "Tile " + tile.path("title").asText("") + " pinned from the report", 100);
					continue;
				}
				final Item model = s.models.get(tile.path("datasetId").asText(""));
				if (model != null) s.edge(model.assetKey, null, d.assetKey, null, "IDENTITY", "Tile " + tile.path("title").asText("") + " on the semantic model", 100);
			}
		}
		for (final JsonNode model : pWorkspace.path("datasets"))
		{
			final Item m = s.models.get(model.path("id").asText(model.path("name").asText("")));
			if (m == null) continue;
			for (final JsonNode upstream : model.path("upstreamDataflows"))
			{
				final Item dataflow = s.dataflows.get(upstream.path("targetDataflowId").asText(""));
				if (dataflow != null) s.edge(dataflow.assetKey, null, m.assetKey, null, "IDENTITY", "Semantic model loaded from the dataflow", 100);
			}
			for (final JsonNode upstream : model.path("upstreamDatasets"))
			{
				final Item other = s.models.get(upstream.path("targetDatasetId").asText(""));
				if (other != null) s.edge(other.assetKey, null, m.assetKey, null, "IDENTITY", "Composite model (DirectQuery to the semantic model)", 100);
			}
			final Map<String, String> parameters = PowerBiScan.parameters(model.path("expressions"));
			final JsonNode fallback = PowerBiScan.firstDatasource(s, model);
			for (final JsonNode table : model.path("tables"))
			{
				final String tableName = table.path("name").asText("");
				final String tableKey = m.tables.get(tableName);
				if (tableKey == null) continue;
				final StringBuilder powerQuery = new StringBuilder();
				for (final JsonNode source : table.path("source"))
				{
					final String expression = source.path("expression").asText("");
					if (!expression.isEmpty()) powerQuery.append(powerQuery.length() == 0 ? "" : "\n").append(expression);
				}
				final Map<String, String> tableColumns = m.columns.get(tableName);
				if (powerQuery.length() > 0) PowerBiScan.powerQuery(s, powerQuery.toString(), parameters, fallback, tableKey, tableName,
					tableColumns == null ? Collections.<String, String>emptyMap() : tableColumns, m);
				for (final JsonNode column : table.path("columns"))
				{
					final String expression = column.path("expression").asText("");
					final String columnKey = PowerBiScan.columnKey(m, tableName, column.path("name").asText(""));
					if (!expression.isEmpty() && columnKey != null) PowerBiScan.dax(s, m, tableName, columnKey, expression, false);
				}
				for (final JsonNode measure : table.path("measures"))
				{
					final String expression = measure.path("expression").asText("");
					final String columnKey = PowerBiScan.columnKey(m, tableName, measure.path("name").asText(""));
					if (!expression.isEmpty() && columnKey != null) PowerBiScan.dax(s, m, tableName, columnKey, expression, true);
				}
			}
		}
		for (final JsonNode dataflow : pWorkspace.path("dataflows"))
		{
			final Item d = s.dataflows.get(dataflow.path("objectId").asText(dataflow.path("id").asText(dataflow.path("name").asText(""))));
			if (d == null) continue;
			for (final JsonNode upstream : dataflow.path("upstreamDataflows"))
			{
				final Item other = s.dataflows.get(upstream.path("targetDataflowId").asText(""));
				if (other != null) s.edge(other.assetKey, null, d.assetKey, null, "IDENTITY", "Linked entities from the dataflow", 100);
			}
			final String document = dataflow.path("definition").path("pbi:mashup").path("document").asText("");
			if (document.isEmpty()) continue;
			final JsonNode fallback = PowerBiScan.firstDatasource(s, dataflow);
			for (final Map.Entry<String, String> entity : PowerBiScan.sharedQueries(document).entrySet())
			{
				final String entityKey = d.tables.get(entity.getKey());
				if (entityKey == null) continue;
				PowerBiScan.powerQuery(s, entity.getValue(), Collections.<String, String>emptyMap(), fallback, entityKey, entity.getKey(), d.columns.get(entity.getKey()), d);
			}
		}
	}

	static String columnKey(final Item pItem, final String pTable, final String pColumn)
	{
		final Map<String, String> columns = pItem == null ? null : pItem.columns.get(pTable);
		return columns == null ? null : columns.get(pColumn);
	}

	/** Connection details of the first data source instance a model or dataflow uses (server/database fallback for parameterised M). */
	static JsonNode firstDatasource(final Scan s, final JsonNode pOwner)
	{
		for (final JsonNode usage : pOwner.path("datasourceUsages"))
		{
			final JsonNode instance = s.datasourceInstances.get(usage.path("datasourceInstanceId").asText(""));
			if (instance != null) return instance;
		}
		return null;
	}

	/** Parameters and shared expressions of a model: name → literal value (or the whole expression when it is not a literal). */
	static Map<String, String> parameters(final JsonNode pExpressions)
	{
		final Map<String, String> map = new LinkedHashMap<String, String>();
		for (final JsonNode expression : pExpressions)
		{
			final String name = expression.path("name").asText("");
			final String text = expression.path("expression").asText("");
			if (name.isEmpty() || text.isEmpty()) continue;
			final List<Tok> tokens = PowerBiScan.lex(text);
			map.put(name, !tokens.isEmpty() && tokens.get(0).kind == 'S' ? tokens.get(0).text : text);
		}
		return map;
	}

	/** {@code section X; shared A = <expr>; shared B = <expr>;} → name → expression. */
	static Map<String, String> sharedQueries(final String pDocument)
	{
		final Map<String, String> queries = new LinkedHashMap<String, String>();
		final Matcher m = Pattern.compile("shared\\s+(#\"[^\"]+\"|[A-Za-z_][\\w.]*)\\s*=").matcher(pDocument);
		final List<int[]> positions = new ArrayList<int[]>();
		final List<String> names = new ArrayList<String>();
		while (m.find())
		{
			positions.add(new int[] { m.start(), m.end() });
			final String name = m.group(1);
			names.add(name.startsWith("#\"") ? name.substring(2, name.length() - 1) : name);
		}
		for (int i = 0; i < positions.size(); i++)
		{
			final int end = i + 1 < positions.size() ? positions.get(i + 1)[0] : pDocument.length();
			String body = pDocument.substring(positions.get(i)[1], end).trim();
			if (body.endsWith(";")) body = body.substring(0, body.length() - 1);
			queries.put(names.get(i), body);
		}
		return queries;
	}

	// ------------------------------------------------------------------ Power Query (M)

	static final String[] TYPE_ORDER = { "IDENTITY", "FILTER", "TRANSFORMATION", "AGGREGATION", "JOIN" };
	static final Set<String> SOURCE_PREFIXES = new LinkedHashSet<String>(java.util.Arrays.asList(
		"Sql", "Snowflake", "Databricks", "GoogleBigQuery", "PostgreSQL", "MySQL", "Oracle", "AmazonRedshift", "Teradata", "Odbc", "OleDb", "Db2",
		"Sybase", "Informix", "SapHana", "SapBusinessWarehouse", "Exasol", "Vertica", "Impala", "Spark", "AzureSynapse", "Kusto", "AzureDataExplorer",
		"PowerPlatform", "PowerBI", "AnalysisServices", "CommonDataService", "Salesforce", "OData", "Dynamics365BusinessCentral", "SharePoint", "Excel",
		"Csv", "Json", "Xml", "File", "Folder", "Web", "AzureStorage", "DataLake", "Parquet", "Cdm", "Lakehouse", "Fabric", "Access", "Text", "Html", "Pdf"));
	static final Set<String> HARMLESS_STEPS = new LinkedHashSet<String>(java.util.Arrays.asList(
		"Table.TransformColumnTypes", "Table.SelectColumns", "Table.ReorderColumns", "Table.Sort", "Table.Distinct", "Table.PromoteHeaders", "Table.Buffer",
		"Table.RemoveRowsWithErrors", "Table.Skip", "Table.FirstN", "Table.ReplaceErrors", "Table.RemoveColumns", "Table.RenameColumns", "Table.AddColumn",
		"Table.SelectRows", "Table.Group", "Table.NestedJoin", "Table.Join", "Table.FuzzyNestedJoin", "Table.FuzzyJoin", "Table.Combine", "Table.ExpandTableColumn",
		"Table.ExpandRecordColumn", "Table.AddIndexColumn", "Table.AddKey", "Table.StopFolding", "Table.View"));

	static final class MSource
	{
		String function = "";
		final List<String> args = new ArrayList<String>();
		final List<Map<String, String>> nav = new ArrayList<Map<String, String>>();
		String nativeSql;
	}

	static final class MQuery
	{
		final List<MSource> sources = new ArrayList<MSource>();
		/** new name → source name */
		final Map<String, String> renames = new LinkedHashMap<String, String>();
		final Set<String> added = new LinkedHashSet<String>();
		final Set<String> removed = new LinkedHashSet<String>();
		final Set<String> transformed = new LinkedHashSet<String>();
		final Set<String> referencedQueries = new LinkedHashSet<String>();
		int rank = 0;

		void raise(final String pType)
		{
			for (int i = 0; i < TYPE_ORDER.length; i++) if (TYPE_ORDER[i].equals(pType) && i > this.rank) this.rank = i;
		}

		String type()
		{
			return TYPE_ORDER[this.rank];
		}
	}

	static final class Tok
	{
		final char kind; // S string, I identifier, N number, P punctuation
		final String text;

		Tok(final char pKind, final String pText)
		{
			this.kind = pKind;
			this.text = pText;
		}

		boolean is(final String pPunct)
		{
			return this.kind == 'P' && this.text.equals(pPunct);
		}
	}

	static List<Tok> lex(final String pText)
	{
		final List<Tok> out = new ArrayList<Tok>();
		final int n = pText.length();
		int i = 0;
		while (i < n)
		{
			final char c = pText.charAt(i);
			if (Character.isWhitespace(c))
			{
				i++;
				continue;
			}
			if (c == '/' && i + 1 < n && pText.charAt(i + 1) == '/')
			{
				while (i < n && pText.charAt(i) != '\n') i++;
				continue;
			}
			if (c == '/' && i + 1 < n && pText.charAt(i + 1) == '*')
			{
				final int end = pText.indexOf("*/", i + 2);
				i = end < 0 ? n : end + 2;
				continue;
			}
			if (c == '"')
			{
				final StringBuilder b = new StringBuilder();
				i++;
				while (i < n)
				{
					final char d = pText.charAt(i);
					if (d == '"')
					{
						if (i + 1 < n && pText.charAt(i + 1) == '"')
						{
							b.append('"');
							i += 2;
							continue;
						}
						i++;
						break;
					}
					b.append(d);
					i++;
				}
				out.add(new Tok('S', b.toString()));
				continue;
			}
			if (c == '#' && i + 1 < n && pText.charAt(i + 1) == '"')
			{
				final StringBuilder b = new StringBuilder();
				i += 2;
				while (i < n)
				{
					final char d = pText.charAt(i);
					if (d == '"')
					{
						if (i + 1 < n && pText.charAt(i + 1) == '"')
						{
							b.append('"');
							i += 2;
							continue;
						}
						i++;
						break;
					}
					b.append(d);
					i++;
				}
				out.add(new Tok('I', b.toString()));
				continue;
			}
			if (Character.isLetter(c) || c == '_' || c == '#')
			{
				final int start = i;
				i++;
				while (i < n && (Character.isLetterOrDigit(pText.charAt(i)) || pText.charAt(i) == '_' || pText.charAt(i) == '.')) i++;
				out.add(new Tok('I', pText.substring(start, i)));
				continue;
			}
			if (Character.isDigit(c))
			{
				final int start = i;
				while (i < n && (Character.isLetterOrDigit(pText.charAt(i)) || pText.charAt(i) == '.')) i++;
				out.add(new Tok('N', pText.substring(start, i)));
				continue;
			}
			out.add(new Tok('P', String.valueOf(c)));
			i++;
		}
		return out;
	}

	static int depthDelta(final Tok pTok)
	{
		if (pTok.kind != 'P') return 0;
		if ("([{".contains(pTok.text)) return 1;
		if (")]}".contains(pTok.text)) return -1;
		return 0;
	}

	/** Steps of a {@code let ... in ...} expression (a bare expression is one step named Source). */
	static Map<String, List<Tok>> steps(final List<Tok> pTokens)
	{
		final Map<String, List<Tok>> steps = new LinkedHashMap<String, List<Tok>>();
		int start = -1;
		for (int i = 0; i < pTokens.size(); i++) if (pTokens.get(i).kind == 'I' && "let".equals(pTokens.get(i).text))
		{
			start = i + 1;
			break;
		}
		if (start < 0)
		{
			steps.put("Source", pTokens);
			return steps;
		}
		int depth = 0;
		int letDepth = 0;
		List<Tok> current = new ArrayList<Tok>();
		final List<List<Tok>> parts = new ArrayList<List<Tok>>();
		for (int i = start; i < pTokens.size(); i++)
		{
			final Tok t = pTokens.get(i);
			if (t.kind == 'I' && "let".equals(t.text)) letDepth++;
			if (t.kind == 'I' && "in".equals(t.text) && depth == 0)
			{
				if (letDepth == 0) break;
				letDepth--;
			}
			if (t.is(",") && depth == 0 && letDepth == 0)
			{
				parts.add(current);
				current = new ArrayList<Tok>();
				continue;
			}
			depth += PowerBiScan.depthDelta(t);
			current.add(t);
		}
		if (!current.isEmpty()) parts.add(current);
		for (final List<Tok> part : parts)
		{
			if (part.size() >= 3 && part.get(0).kind == 'I' && part.get(1).is("="))
			{
				steps.put(part.get(0).text, new ArrayList<Tok>(part.subList(2, part.size())));
			}
		}
		return steps;
	}

	/** End index (exclusive) of the balanced group opened at {@code pOpen}. */
	static int close(final List<Tok> pTokens, final int pOpen)
	{
		int depth = 0;
		for (int i = pOpen; i < pTokens.size(); i++)
		{
			depth += PowerBiScan.depthDelta(pTokens.get(i));
			if (depth == 0) return i + 1;
		}
		return pTokens.size();
	}

	/** Top-level comma-separated arguments of the group {@code pTokens[pOpen .. pEnd)}. */
	static List<List<Tok>> args(final List<Tok> pTokens, final int pOpen, final int pEnd)
	{
		final List<List<Tok>> args = new ArrayList<List<Tok>>();
		List<Tok> current = new ArrayList<Tok>();
		int depth = 0;
		for (int i = pOpen + 1; i < pEnd - 1; i++)
		{
			final Tok t = pTokens.get(i);
			if (t.is(",") && depth == 0)
			{
				args.add(current);
				current = new ArrayList<Tok>();
				continue;
			}
			depth += PowerBiScan.depthDelta(t);
			current.add(t);
		}
		if (!current.isEmpty() || !args.isEmpty()) args.add(current);
		return args;
	}

	static String literal(final List<Tok> pArg, final Map<String, String> pParameters)
	{
		if (pArg == null || pArg.isEmpty()) return null;
		final Tok t = pArg.get(0);
		if (t.kind == 'S') return t.text;
		if (t.kind == 'N') return t.text;
		if (t.kind == 'I' && pArg.size() == 1 && pParameters != null) return pParameters.get(t.text);
		if (t.kind == 'I' && "null".equals(t.text)) return null;
		// File.Contents("path") and similar nested calls: the first string inside
		for (final Tok inner : pArg) if (inner.kind == 'S') return inner.text;
		return null;
	}

	/** Record literal {@code [Key = value, ...]} at {@code pOpen} (the '[') → key → value (strings, numbers, resolved identifiers). */
	static Map<String, String> record(final List<Tok> pTokens, final int pOpen, final int pEnd, final Map<String, String> pParameters)
	{
		final Map<String, String> record = new LinkedHashMap<String, String>();
		for (final List<Tok> field : PowerBiScan.args(pTokens, pOpen, pEnd))
		{
			if (field.size() >= 3 && field.get(0).kind == 'I' && field.get(1).is("="))
			{
				final String value = PowerBiScan.literal(field.subList(2, field.size()), pParameters);
				record.put(field.get(0).text, value == null && field.get(2).kind == 'I' ? "${" + field.get(2).text + "}" : value);
			}
		}
		return record;
	}

	/** Navigation records ({@code {[Name="x"]}[Data]...}) following position {@code pFrom}; returns the index after the last one. */
	static int navigation(final List<Tok> pTokens, final int pFrom, final MSource pSource, final Map<String, String> pParameters)
	{
		int i = pFrom;
		while (i < pTokens.size())
		{
			final Tok t = pTokens.get(i);
			if (t.is("{") && i + 1 < pTokens.size() && pTokens.get(i + 1).is("["))
			{
				final int end = PowerBiScan.close(pTokens, i + 1);
				pSource.nav.add(PowerBiScan.record(pTokens, i + 1, end, pParameters));
				i = PowerBiScan.close(pTokens, i);
				continue;
			}
			if (t.is("[") && i + 2 < pTokens.size() && pTokens.get(i + 1).kind == 'I' && pTokens.get(i + 2).is("]"))
			{
				i += 3;
				continue;
			}
			if (t.is("{") && i + 1 < pTokens.size() && pTokens.get(i + 1).kind == 'N')
			{
				i = PowerBiScan.close(pTokens, i);
				continue;
			}
			break;
		}
		return i;
	}

	static MQuery parseM(final String pExpression, final Map<String, String> pParameters)
	{
		final MQuery q = new MQuery();
		final Map<String, List<Tok>> steps = PowerBiScan.steps(PowerBiScan.lex(pExpression));
		final Map<String, MSource> aliases = new LinkedHashMap<String, MSource>();
		for (final Map.Entry<String, List<Tok>> step : steps.entrySet())
		{
			List<Tok> tokens = step.getValue();
			if (tokens.isEmpty()) continue;
			// a step that is just a shared expression of the model: inline it (one level)
			if (tokens.size() == 1 && tokens.get(0).kind == 'I' && pParameters.containsKey(tokens.get(0).text) && !aliases.containsKey(tokens.get(0).text))
			{
				tokens = PowerBiScan.lex(pParameters.get(tokens.get(0).text));
			}
			final Tok first = tokens.get(0);
			if (first.kind == 'I' && first.text.contains(".") && tokens.size() > 1 && tokens.get(1).is("(")
				&& SOURCE_PREFIXES.contains(first.text.substring(0, first.text.indexOf('.'))))
			{
				final MSource source = new MSource();
				source.function = first.text;
				final int end = PowerBiScan.close(tokens, 1);
				for (final List<Tok> arg : PowerBiScan.args(tokens, 1, end))
				{
					if (!arg.isEmpty() && arg.get(0).is("["))
					{
						final Map<String, String> options = PowerBiScan.record(arg, 0, arg.size(), pParameters);
						if (options.get("Query") != null) source.nativeSql = options.get("Query");
						source.args.add(null);
						continue;
					}
					source.args.add(PowerBiScan.literal(arg, pParameters));
				}
				PowerBiScan.navigation(tokens, end, source, pParameters);
				q.sources.add(source);
				aliases.put(step.getKey(), source);
				continue;
			}
			if (first.kind == 'I' && aliases.containsKey(first.text))
			{
				final MSource source = aliases.get(first.text);
				final int after = PowerBiScan.navigation(tokens, 1, source, pParameters);
				if (after >= tokens.size())
				{
					aliases.put(step.getKey(), source);
					continue;
				}
			}
			// Table.* and Value.NativeQuery steps
			for (int i = 0; i < tokens.size(); i++)
			{
				final Tok t = tokens.get(i);
				if (t.kind != 'I' || i + 1 >= tokens.size() || !tokens.get(i + 1).is("(")) continue;
				final int end = PowerBiScan.close(tokens, i + 1);
				final List<List<Tok>> args = PowerBiScan.args(tokens, i + 1, end);
				final String fn = t.text;
				if ("Value.NativeQuery".equals(fn))
				{
					q.raise("TRANSFORMATION");
					final String sql = args.size() > 1 ? PowerBiScan.literal(args.get(1), pParameters) : null;
					if (sql != null && !q.sources.isEmpty()) q.sources.get(q.sources.size() - 1).nativeSql = sql;
				}
				else if ("Table.RenameColumns".equals(fn))
				{
					if (args.size() > 1) PowerBiScan.pairs(args.get(1), q.renames);
				}
				else if ("Table.AddColumn".equals(fn))
				{
					final String name = args.size() > 1 ? PowerBiScan.literal(args.get(1), pParameters) : null;
					if (name != null) q.added.add(name);
					q.raise("TRANSFORMATION");
				}
				else if ("Table.RemoveColumns".equals(fn))
				{
					if (args.size() > 1) for (final Tok a : args.get(1)) if (a.kind == 'S') q.removed.add(a.text);
				}
				else if ("Table.SelectRows".equals(fn)) q.raise("FILTER");
				else if ("Table.Group".equals(fn)) q.raise("AGGREGATION");
				else if ("Table.NestedJoin".equals(fn) || "Table.Join".equals(fn) || "Table.FuzzyNestedJoin".equals(fn) || "Table.FuzzyJoin".equals(fn))
				{
					q.raise("JOIN");
					if (args.size() > 2 && !args.get(2).isEmpty() && args.get(2).get(0).kind == 'I' && !steps.containsKey(args.get(2).get(0).text)) q.referencedQueries.add(args.get(2).get(0).text);
				}
				else if ("Table.Combine".equals(fn))
				{
					q.raise("JOIN");
					if (!args.isEmpty()) for (final Tok a : args.get(0)) if (a.kind == 'I' && !steps.containsKey(a.text)) q.referencedQueries.add(a.text);
				}
				else if ("Table.ExpandTableColumn".equals(fn) || "Table.ExpandRecordColumn".equals(fn))
				{
					if (q.rank < 4) q.raise("TRANSFORMATION");
					if (args.size() > 2) for (final Tok a : args.get(2)) if (a.kind == 'S') q.added.add(a.text);
				}
				else if (fn.startsWith("Table.") && !HARMLESS_STEPS.contains(fn))
				{
					q.raise("TRANSFORMATION");
					for (int a = 1; a < args.size(); a++) for (final Tok x : args.get(a)) if (x.kind == 'S') q.transformed.add(x.text);
				}
			}
		}
		return q;
	}

	/** {@code {{"old","new"}, {"old2","new2"}}} → new → old. */
	static void pairs(final List<Tok> pTokens, final Map<String, String> pOut)
	{
		final List<String> strings = new ArrayList<String>();
		for (final Tok t : pTokens) if (t.kind == 'S') strings.add(t.text);
		for (int i = 0; i + 1 < strings.size(); i += 2) pOut.put(strings.get(i + 1), strings.get(i));
	}

	/** A resolved data source table: where it lives and how it is named. */
	static final class Resolved
	{
		String platform = "OTHER";
		String server;
		String database;
		final List<String[]> items = new ArrayList<String[]>(); // {schema, item}
		String workspaceId;
		String dataflowId;
		String entity;
	}

	static Resolved resolve(final MSource pSource, final JsonNode pFallback)
	{
		final Resolved r = new Resolved();
		final String prefix = pSource.function.contains(".") ? pSource.function.substring(0, pSource.function.indexOf('.')) : pSource.function;
		final String arg0 = pSource.args.isEmpty() ? null : pSource.args.get(0);
		final String arg1 = pSource.args.size() < 2 ? null : pSource.args.get(1);
		if ("Sql".equals(prefix) || "AzureSynapse".equals(prefix))
		{
			r.platform = "SQLSERVER";
			r.server = arg0;
			r.database = "Sql.Databases".equals(pSource.function) ? null : arg1;
		}
		else if ("Snowflake".equals(prefix))
		{
			r.platform = "SNOWFLAKE";
			r.server = arg0;
		}
		else if ("Databricks".equals(prefix) || "Spark".equals(prefix))
		{
			r.platform = "DATABRICKS";
			r.server = arg0;
		}
		else if ("GoogleBigQuery".equals(prefix))
		{
			r.platform = "BIGQUERY";
			r.server = "bigquery";
		}
		else if ("PostgreSQL".equals(prefix))
		{
			r.platform = "POSTGRESQL";
			r.server = arg0;
			r.database = arg1;
		}
		else if ("MySQL".equals(prefix))
		{
			r.platform = "MYSQL";
			r.server = arg0;
			r.database = arg1;
		}
		else if ("Oracle".equals(prefix))
		{
			r.platform = "ORACLE";
			r.server = arg0;
		}
		else if ("PowerPlatform".equals(prefix) && pSource.function.contains("Dataflows")) { r.platform = "DATAFLOW"; }
		else if ("AnalysisServices".equals(prefix) || "PowerBI".equals(prefix))
		{
			r.platform = "BI";
			r.server = arg0;
			r.database = arg1;
		}
		else if ("SharePoint".equals(prefix) || "Excel".equals(prefix) || "Csv".equals(prefix) || "Json".equals(prefix) || "Xml".equals(prefix) || "File".equals(prefix)
			|| "Folder".equals(prefix) || "Web".equals(prefix) || "AzureStorage".equals(prefix) || "DataLake".equals(prefix) || "Parquet".equals(prefix) || "Text".equals(prefix)
			|| "Html".equals(prefix) || "Pdf".equals(prefix) || "Cdm".equals(prefix) || "Lakehouse".equals(prefix) || "Fabric".equals(prefix) || "Access".equals(prefix))
		{
			r.platform = "FILES";
			r.server = PowerBiScan.host(arg0);
		}
		else
		{
			r.server = arg0;
			r.database = arg1;
		}
		// navigation: {[Schema="x", Item="y"]} or {[Name="x", Kind="Database|Schema|Table|View"]}, positional otherwise
		String schema = null;
		String item = null;
		final List<String> unkinded = new ArrayList<String>();
		for (final Map<String, String> record : pSource.nav)
		{
			if (record.containsKey("workspaceId") || record.containsKey("dataflowId") || record.containsKey("entity"))
			{
				if (record.get("workspaceId") != null) r.workspaceId = record.get("workspaceId");
				if (record.get("dataflowId") != null) r.dataflowId = record.get("dataflowId");
				if (record.get("entity") != null) r.entity = record.get("entity");
				continue;
			}
			if (record.containsKey("Item"))
			{
				item = record.get("Item");
				if (record.get("Schema") != null) schema = record.get("Schema");
				continue;
			}
			final String name = record.get("Name");
			if (name == null) continue;
			final String kind = record.get("Kind") == null ? "" : record.get("Kind");
			if ("Database".equalsIgnoreCase(kind)) r.database = name;
			else if ("Schema".equalsIgnoreCase(kind)) schema = name;
			else if (!kind.isEmpty()) item = name;
			else unkinded.add(name);
		}
		if (!unkinded.isEmpty())
		{
			int i = unkinded.size() - 1;
			if (item == null) item = unkinded.get(i--);
			if (i >= 0 && schema == null) schema = unkinded.get(i--);
			if (i >= 0 && r.database == null) r.database = unkinded.get(i);
		}
		if (pSource.nativeSql != null && item == null)
		{
			final Matcher m = Pattern.compile("(?i)\\b(?:from|join)\\s+([A-Za-z_\"\\[][\\w.\"\\]\\[$]*)").matcher(pSource.nativeSql);
			while (m.find())
			{
				final String[] parts = m.group(1).replace("\"", "").replace("[", "").replace("]", "").split("\\.");
				final String table = parts[parts.length - 1];
				if (table.isEmpty()) continue;
				r.items.add(new String[] { parts.length > 1 ? parts[parts.length - 2] : schema, table });
			}
		}
		if (item != null) r.items.add(new String[] { schema, item });
		if (pFallback != null)
		{
			final JsonNode details = pFallback.path("connectionDetails");
			if (r.server == null || r.server.startsWith("${")) r.server = details.path("server").asText(details.path("url").asText(details.path("path").asText(null)));
			if (r.database == null || r.database.startsWith("${")) r.database = details.path("database").asText(null);
		}
		if (r.server != null && r.server.startsWith("${")) r.server = null;
		if (r.database != null && r.database.startsWith("${")) r.database = null;
		return r;
	}

	static String host(final String pValue)
	{
		if (pValue == null) return null;
		final Matcher m = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)").matcher(pValue.trim());
		if (m.find()) return m.group(1);
		final int slash = Math.max(pValue.lastIndexOf('/'), pValue.lastIndexOf('\\'));
		return slash >= 0 ? pValue.substring(0, slash) : pValue;
	}

	/**
	 * Edges of one Power Query expression: each data source table → the target table (table level,
	 * with the M as transformation) and, for pass-through columns, source column → target column.
	 */
	static void powerQuery(final Scan s, final String pExpression, final Map<String, String> pParameters, final JsonNode pFallback, final String pTargetKey,
		final String pTargetName, final Map<String, String> pTargetColumns, final Item pOwner)
	{
		final MQuery q = PowerBiScan.parseM(pExpression, pParameters);
		if (q.sources.isEmpty()) s.notes.add(pTargetName + ": no recognised data source in the Power Query expression (calculated or generated table?)");
		if (pOwner != null)
		{
			for (final String other : q.referencedQueries)
			{
				final String otherKey = pOwner.tables.get(other);
				if (otherKey != null) s.edge(otherKey, null, pTargetKey, null, "JOIN", PowerBiScan.cut(pExpression, 4000), 70);
			}
		}
		for (final MSource source : q.sources)
		{
			final Resolved r = PowerBiScan.resolve(source, pFallback);
			if ("DATAFLOW".equals(r.platform))
			{
				final Item dataflow = r.dataflowId == null ? null : s.dataflows.get(r.dataflowId);
				if (dataflow == null) continue;
				final String entityKey = r.entity == null ? null : dataflow.tables.get(r.entity);
				if (entityKey == null)
				{
					s.edge(dataflow.assetKey, null, pTargetKey, null, q.type(), PowerBiScan.cut(pExpression, 4000), 90);
					continue;
				}
				s.edge(entityKey, null, pTargetKey, null, q.type(), PowerBiScan.cut(pExpression, 4000), 90);
				PowerBiScan.columnEdges(s, q, entityKey, dataflow.columns.get(r.entity), null, null, pTargetColumns, 80);
				continue;
			}
			if (r.items.isEmpty())
			{
				s.notes.add(pTargetName + ": data source " + source.function + " not resolved to a table");
				continue;
			}
			final DatasourceTarget target = PowerBiScan.target(s.options, r);
			for (final String[] item : r.items)
			{
				final String schema = item[0];
				final String table = item[1];
				String fromKey;
				ObjectNode external = null;
				if (target != null)
				{
					fromKey = target.source + ":" + (schema == null || schema.isEmpty() ? "" : target.spell(schema) + ".") + target.spell(table);
				}
				else if (s.options.externalAssets)
				{
					final String folder = "datasources/" + (r.server == null ? "unknown" : r.server) + (r.database == null ? "" : "/" + r.database);
					final String physical = (schema == null || schema.isEmpty() ? "" : schema + ".") + table;
					external = s.asset(folder + "/" + physical, folder, physical, KIND_TABLE,
						r.platform + " data source read through Power Query (" + source.function + "); not scanned: columns inferred from the tables that read it", null);
					fromKey = external.get("assetKey").asText();
				}
				else
				{
					s.notes.add(pTargetName + ": unmapped data source " + r.server + " dropped");
					continue;
				}
				final boolean nativeQuery = source.nativeSql != null;
				s.edge(fromKey, null, pTargetKey, null, nativeQuery ? "TRANSFORMATION" : q.type(), PowerBiScan.cut(pExpression, 4000), target != null ? 90 : 80);
				if (!nativeQuery && r.items.size() == 1) PowerBiScan.columnEdges(s, q, fromKey, null, external, target, pTargetColumns, target != null ? 80 : 70);
			}
		}
	}

	static DatasourceTarget target(final Options pOptions, final Resolved r)
	{
		if (r.server == null) return null;
		final String server = r.server.toLowerCase(Locale.ENGLISH);
		if (r.database != null)
		{
			final DatasourceTarget t = pOptions.datasourceMap.get(server + "/" + r.database.toLowerCase(Locale.ENGLISH));
			if (t != null) return t;
		}
		return pOptions.datasourceMap.get(server);
	}

	/** Column → column edges for the pass-through columns of a Power Query step chain. */
	static void columnEdges(final Scan s, final MQuery q, final String pFromKey, final Map<String, String> pFromColumns, final ObjectNode pExternal,
		final DatasourceTarget pTarget, final Map<String, String> pTargetColumns, final int pConfidence)
	{
		if (pTargetColumns == null) return;
		for (final Map.Entry<String, String> column : pTargetColumns.entrySet())
		{
			final String name = column.getKey();
			final String toKey = column.getValue();
			if (q.added.contains(name) || toKey.endsWith("." + name) && PowerBiScan.isDerived(s, toKey)) continue;
			final String sourceName = q.renames.containsKey(name) ? q.renames.get(name) : name;
			String fromColumn;
			if (pFromColumns != null)
			{
				fromColumn = pFromColumns.get(sourceName);
				if (fromColumn == null) continue;
			}
			else if (pExternal != null)
			{
				final JsonNode dataType = PowerBiScan.columnNode(s, toKey);
				fromColumn = s.column(pExternal, sourceName, dataType == null ? "OTHER" : dataType.path("dataType").asText("OTHER"), "",
					"Inferred from Power Query (not scanned)", null).get("columnKey").asText();
			}
			else
			{
				fromColumn = pFromKey + "." + (pTarget == null ? sourceName : pTarget.spell(sourceName));
			}
			s.edge(pFromKey, fromColumn, toKey.substring(0, toKey.lastIndexOf('.')), toKey,
				q.transformed.contains(name) || q.transformed.contains(sourceName) ? "TRANSFORMATION" : "IDENTITY", null, pConfidence);
		}
	}

	static JsonNode columnNode(final Scan s, final String pColumnKey)
	{
		final String assetKey = pColumnKey.substring(0, pColumnKey.lastIndexOf('.'));
		final ObjectNode asset = s.assets.get(assetKey);
		if (asset == null) return null;
		for (final JsonNode c : asset.get("columns")) if (pColumnKey.equals(c.path("columnKey").asText())) return c;
		return null;
	}

	/** A measure or a calculated column has no source column: its lineage comes from its DAX. */
	static boolean isDerived(final Scan s, final String pColumnKey)
	{
		final JsonNode c = PowerBiScan.columnNode(s, pColumnKey);
		if (c == null) return false;
		final String nativeType = c.path("nativeType").asText("");
		return "MEASURE".equals(nativeType) || nativeType.startsWith("CALCULATED");
	}

	// ------------------------------------------------------------------ DAX

	static final Pattern AGGREGATE = Pattern.compile("(?i)\\b(SUM|SUMX|COUNT|COUNTA|COUNTX|COUNTAX|COUNTROWS|DISTINCTCOUNT|DISTINCTCOUNTNOBLANK|AVERAGE|AVERAGEX|AVERAGEA|MIN|MINX|MINA|MAX|MAXX|MAXA|MEDIAN|MEDIANX|PRODUCT|PRODUCTX|GEOMEAN|GEOMEANX|PERCENTILE\\.INC|PERCENTILE\\.EXC|CALCULATE|CALCULATETABLE|TOTALYTD|TOTALQTD|TOTALMTD|SUMMARIZE|SUMMARIZECOLUMNS|RANKX|TOPN)\\s*\\(");

	/** {@code Table[Column]}, {@code 'Table name'[Column]} and bare {@code [Measure]} references of a DAX expression → {table or null, name}. */
	static List<String[]> daxReferences(final String pDax)
	{
		final List<String[]> refs = new ArrayList<String[]>();
		final String clean = PowerBiScan.stripDax(pDax);
		int i = 0;
		while ((i = clean.indexOf('[', i)) >= 0)
		{
			final int end = clean.indexOf(']', i + 1);
			if (end < 0) break;
			final String name = clean.substring(i + 1, end).trim();
			int j = i - 1;
			while (j >= 0 && Character.isWhitespace(clean.charAt(j))) j--;
			String table = null;
			if (j >= 0 && clean.charAt(j) == '\'')
			{
				final int open = clean.lastIndexOf('\'', j - 1);
				if (open >= 0) table = clean.substring(open + 1, j);
			}
			else if (j >= 0 && (Character.isLetterOrDigit(clean.charAt(j)) || clean.charAt(j) == '_'))
			{
				int k = j;
				while (k >= 0 && (Character.isLetterOrDigit(clean.charAt(k)) || clean.charAt(k) == '_')) k--;
				table = clean.substring(k + 1, j + 1);
			}
			if (!name.isEmpty()) refs.add(new String[] { table, name });
			i = end + 1;
		}
		return refs;
	}

	/** Strings and comments removed (their brackets are not references). */
	static String stripDax(final String pDax)
	{
		final StringBuilder b = new StringBuilder();
		final int n = pDax.length();
		int i = 0;
		while (i < n)
		{
			final char c = pDax.charAt(i);
			if (c == '"')
			{
				i++;
				while (i < n && pDax.charAt(i) != '"') i++;
				i++;
				b.append(' ');
				continue;
			}
			if (c == '/' && i + 1 < n && pDax.charAt(i + 1) == '/' || c == '-' && i + 1 < n && pDax.charAt(i + 1) == '-')
			{
				while (i < n && pDax.charAt(i) != '\n') i++;
				continue;
			}
			if (c == '/' && i + 1 < n && pDax.charAt(i + 1) == '*')
			{
				final int end = pDax.indexOf("*/", i + 2);
				i = end < 0 ? n : end + 2;
				continue;
			}
			b.append(c);
			i++;
		}
		return b.toString();
	}

	/** Edges from the columns and measures a DAX expression references to the measure or calculated column it defines. */
	static void dax(final Scan s, final Item pModel, final String pTable, final String pTargetKey, final String pExpression, final boolean pMeasure)
	{
		if (pModel == null) return;
		final String type = pMeasure && AGGREGATE.matcher(pExpression).find() ? "AGGREGATION" : "TRANSFORMATION";
		final String targetAsset = pTargetKey.substring(0, pTargetKey.lastIndexOf('.'));
		for (final String[] ref : PowerBiScan.daxReferences(pExpression))
		{
			String fromKey = null;
			if (ref[0] != null)
			{
				final Map<String, String> columns = pModel.columns.get(ref[0]);
				if (columns != null) fromKey = columns.get(ref[1]);
			}
			else
			{
				fromKey = pModel.measures.get(ref[1]);
				if (fromKey == null)
				{
					final Map<String, String> own = pModel.columns.get(pTable);
					if (own != null) fromKey = own.get(ref[1]);
				}
			}
			if (fromKey == null || fromKey.equals(pTargetKey)) continue;
			s.edge(fromKey.substring(0, fromKey.lastIndexOf('.')), fromKey, targetAsset, pTargetKey, type, pExpression, 85);
		}
	}

	// ------------------------------------------------------------------ usage and refreshes

	/** Admin activity events (ViewReport, ViewDashboard) → {@code {items:[{assetKey, queryCount30d}]}}. */
	public static JsonNode usage(final Iterable<JsonNode> pEvents, final Options pOptions)
	{
		final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		for (final JsonNode event : pEvents)
		{
			final String activity = event.path("Activity").asText("");
			final String workspace = event.path("WorkSpaceName").asText(event.path("WorkspaceName").asText(""));
			String qualified = null;
			if ("ViewReport".equalsIgnoreCase(activity) && !event.path("ReportName").asText("").isEmpty()) qualified = workspace + "/reports/" + event.path("ReportName").asText();
			else if ("ViewDashboard".equalsIgnoreCase(activity) && !event.path("DashboardName").asText("").isEmpty()) qualified = workspace + "/dashboards/" + event.path("DashboardName").asText();
			else if ("ViewTile".equalsIgnoreCase(activity) && !event.path("DashboardName").asText("").isEmpty()) qualified = workspace + "/dashboards/" + event.path("DashboardName").asText();
			if (qualified == null || workspace.isEmpty()) continue;
			if (!pOptions.workspaces.isEmpty() && !pOptions.workspaces.contains(workspace.toLowerCase(Locale.ENGLISH))
				&& !pOptions.workspaces.contains(event.path("WorkspaceId").asText("").toLowerCase(Locale.ENGLISH))) continue;
			final String key = pOptions.source + ":" + qualified;
			counts.put(key, Integer.valueOf(counts.containsKey(key) ? counts.get(key).intValue() + 1 : 1));
		}
		final ObjectNode out = Json.object();
		out.put("source", pOptions.source);
		final ArrayNode items = out.putArray("items");
		for (final Map.Entry<String, Integer> e : counts.entrySet())
		{
			final ObjectNode item = items.addObject();
			item.put("assetKey", e.getKey());
			item.put("queryCount30d", e.getValue().intValue());
		}
		out.put("count", items.size());
		return items.size() == 0 ? null : out;
	}

	/** One refresh job with its runs, from a semantic model refresh history or a dataflow transaction list. */
	public static ObjectNode job(final Options pOptions, final String pWorkspace, final String pFolder, final String pName, final JsonNode pRuns, final String pScannedAt)
	{
		final ObjectNode job = Json.object();
		final String qualified = pWorkspace + "/" + pFolder + "/" + pName;
		job.put("source", pOptions.source);
		job.put("jobKey", pOptions.source + ":" + qualified);
		job.put("namespace", "powerbi://" + pWorkspace);
		job.put("name", pName + " refresh");
		job.put("orchestrator", "POWERBI");
		job.put("assetKey", pOptions.source + ":" + qualified);
		job.put("scannedAt", pScannedAt);
		final ArrayNode runs = job.putArray("runs");
		String lastAt = null;
		String lastStatus = null;
		for (final JsonNode r : pRuns)
		{
			final String id = r.path("requestId").asText(r.path("id").asText(""));
			if (id.isEmpty()) continue;
			final ObjectNode run = runs.addObject();
			run.put("externalRunId", id);
			final String started = r.path("startTime").asText(null);
			final String ended = r.path("endTime").asText(null);
			if (started != null) run.put("startedAt", started);
			if (ended != null) run.put("endedAt", ended);
			final String status = PowerBiScan.runStatus(r.path("status").asText(""));
			run.put("status", status);
			run.put("refreshType", r.path("refreshType").asText(""));
			final String message = r.path("serviceExceptionJson").asText(r.path("errorMessage").asText(r.path("exceptionMessage").asText(null)));
			if (message != null && !message.isEmpty()) run.put("message", PowerBiScan.cut(message, 2000));
			final String at = ended != null ? ended : started;
			if (at != null && (lastAt == null || at.compareTo(lastAt) > 0))
			{
				lastAt = at;
				lastStatus = status;
			}
		}
		if (lastAt != null) job.put("lastRunAt", lastAt);
		if (lastStatus != null) job.put("lastRunStatus", lastStatus);
		job.put("runCount", runs.size());
		return job;
	}

	static String runStatus(final String pStatus)
	{
		final String s = pStatus.toLowerCase(Locale.ENGLISH);
		if (s.startsWith("complete") || s.startsWith("succe")) return "COMPLETE";
		if (s.startsWith("fail")) return "FAIL";
		if (s.startsWith("cancel")) return "ABORT";
		if (s.startsWith("unknown") || s.startsWith("inprogress") || s.startsWith("running")) return "RUNNING";
		return "OTHER";
	}

	// ------------------------------------------------------------------ helpers

	/** Power BI / Tabular data types → the catalog's normalised vocabulary. */
	public static String normalisedType(final String pType)
	{
		final String t = pType == null ? "" : pType.trim().toLowerCase(Locale.ENGLISH);
		if (t.isEmpty()) return "OTHER";
		if (t.equals("int64") || t.equals("int32") || t.equals("int16") || t.equals("integer") || t.equals("whole number")) return "INTEGER";
		if (t.equals("double") || t.equals("decimal") || t.equals("currency") || t.equals("number") || t.equals("fixed decimal number")) return "DECIMAL";
		if (t.equals("string") || t.equals("text")) return "STRING";
		if (t.equals("boolean") || t.equals("true/false")) return "BOOLEAN";
		if (t.equals("datetime") || t.equals("date/time") || t.equals("datetimezone") || t.equals("datetimeoffset")) return "TIMESTAMP";
		if (t.equals("date")) return "DATE";
		if (t.equals("time") || t.equals("duration")) return "TIME";
		if (t.equals("binary") || t.equals("image")) return "BINARY";
		if (t.equals("variant") || t.equals("any") || t.equals("unknown") || t.equals("automatic")) return "OTHER";
		return CatalogOperations.normalisedType(pType);
	}

	static String cut(final String pValue, final int pMax)
	{
		return pValue == null || pValue.length() <= pMax ? pValue : pValue.substring(0, pMax - 3) + "...";
	}
}
