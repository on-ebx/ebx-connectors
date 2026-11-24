package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.CatalogOperations;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.RecordProfiler;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * The data-catalog operations of the EBX connector — {@code catalog}, {@code profile},
 * {@code sample}, {@code lineage} — read through Data Services, in the documents the catalog flows
 * consume (the same shape as {@code JdbcConnector} and {@code CatalogOperations}):
 * <ul>
 * <li>{@code catalog}: one table per EBX table of the selected dataspaces and datasets, with its
 * columns from the table's metamodel ({@code ?includeMetamodel=true}: type, primary key, mandatory,
 * label, documentation) and its row count ({@code :count}). Qualified name
 * {@code <dataspace>/<dataset><tablePath>} ({@code Companies/Companies/root/Companies}), schema
 * {@code <dataspace>/<dataset>}, name {@code <tablePath>}, {@code assetKey = source:qualifiedName},
 * {@code columnKey = assetKey.<pathInRecord>}.</li>
 * <li>{@code profile}: statistics of every column computed on the first {@code sampleSize} records
 * of each table (default 1000; the row count itself is exact), by {@link RecordProfiler}: {@code method = SAMPLE}.</li>
 * <li>{@code sample}: distinct values of the text columns from the first {@code limit} records.</li>
 * <li>{@code lineage}: the foreign keys of the data models as column edges, primary key of the
 * target → foreign-key field, origin {@code PLATFORM}; {@code fkScope = cross} (default) keeps
 * the keys pointing at another dataset, {@code all} every key.</li>
 * </ul>
 * Inputs common to the four: {@code source} (required), {@code schemas} (comma-separated
 * dataspace keys or {@code dataspace/dataset}; empty or {@code *} = every non-technical dataspace),
 * {@code exclude} (regular expression on {@code /qualifiedName}; default leaves out the technical
 * cross-reference tables, the Match and Merge datasets and the add-on datasets),
 * {@code rowCounts} ({@code false} skips the counts), {@code tables} (qualified names, or {@code *}).
 */
final class EbxCatalog
{
	/** One GET on Data Services, the path relative to the connector's base URL, null on 404. */
	interface Fetcher
	{
		JsonNode get(String pPath, Map<String, String> pQuery) throws ConnectorException;
	}

	static final List<String> OPERATIONS = Arrays.asList("catalog", "profile", "sample", "lineage");
	static final String DEFAULT_EXCLUDE = ".*(/TechnicalData/|_MDS/|/ebx-|/Bebx-).*";
	static final Set<String> SKIP_FIELDS = new LinkedHashSet<String>(Arrays.asList("Association_Reverse", "crossRef"));
	static final Map<String, String> TYPES = new LinkedHashMap<String, String>();
	static
	{
		for (final String t : new String[] { "string", "text", "email", "uri", "anyURI", "name", "color", "locale", "html", "password", "foreignKey", "select", "osd:text" })
			EbxCatalog.TYPES.put(t, "STRING");
		EbxCatalog.TYPES.put("int", "INTEGER");
		EbxCatalog.TYPES.put("integer", "INTEGER");
		EbxCatalog.TYPES.put("long", "INTEGER");
		EbxCatalog.TYPES.put("decimal", "DECIMAL");
		EbxCatalog.TYPES.put("date", "DATE");
		EbxCatalog.TYPES.put("dateTime", "TIMESTAMP");
		EbxCatalog.TYPES.put("time", "TIME");
		EbxCatalog.TYPES.put("boolean", "BOOLEAN");
	}

	private final Fetcher fetcher;
	private final String prefix;
	private final String baseUrl;
	private final String connectorId;

	EbxCatalog(final Fetcher pFetcher, final String pDataspacePrefix, final String pBaseUrl)
	{
		this(pFetcher, pDataspacePrefix, pBaseUrl, null);
	}

	EbxCatalog(final Fetcher pFetcher, final String pDataspacePrefix, final String pBaseUrl, final String pConnectorId)
	{
		this.fetcher = pFetcher;
		this.prefix = pDataspacePrefix == null || pDataspacePrefix.isEmpty() ? "B" : pDataspacePrefix;
		this.baseUrl = pBaseUrl == null ? "" : pBaseUrl.trim();
		this.connectorId = pConnectorId;
	}

	JsonNode execute(final String pOperation, final Map<String, String> pInputs) throws ConnectorException
	{
		if ("catalog".equals(pOperation)) return this.catalog(pInputs);
		if ("profile".equals(pOperation)) return this.profile(pInputs);
		if ("sample".equals(pOperation)) return this.sample(pInputs);
		if ("lineage".equals(pOperation)) return this.lineage(pInputs);
		throw new ConnectorException("unknown catalog operation '" + pOperation + "'");
	}

	// ------------------------------------------------------------------ discovery

	private String dataspacePath(final String pDataspace)
	{
		return "/" + HttpJsonClient.encode(this.prefix + pDataspace);
	}

	/** Key without its {@code B}/{@code V} prefix. */
	private String bare(final String pKey)
	{
		return pKey != null && pKey.length() > 1 && pKey.startsWith(this.prefix) ? pKey.substring(1) : pKey;
	}

	/** Every dataspace reachable from the roots, technical ones ({@code ebx-*}) left out. */
	List<String> allDataspaces() throws ConnectorException
	{
		final List<String> out = new ArrayList<String>();
		this.collectDataspaces(this.fetcher.get("/", null), out);
		return out;
	}

	private void collectDataspaces(final JsonNode pListing, final List<String> pOut) throws ConnectorException
	{
		if (pListing == null || !pListing.has("rows")) return;
		for (final JsonNode row : pListing.get("rows"))
		{
			final String key = this.bare(row.path("key").asText(null));
			if (key == null || key.isEmpty() || key.startsWith("ebx-") || row.path("closed").asBoolean(false)) continue;
			if (row.path("isSelectAllowed").asBoolean(true)) pOut.add(key);
			if (row.path("hasChildren").asBoolean(false))
			{
				this.collectDataspaces(this.fetcher.get(this.dataspacePath(key) + ":children", null), pOut);
			}
		}
	}

	/** Datasets of a dataspace ({@code ebx-*} left out). */
	List<String> datasets(final String pDataspace) throws ConnectorException
	{
		final List<String> out = new ArrayList<String>();
		final JsonNode listing = this.fetcher.get(this.dataspacePath(pDataspace), null);
		if (listing == null || !listing.has("rows")) return out;
		for (final JsonNode row : listing.get("rows"))
		{
			final String key = row.path("key").asText(null);
			if (key != null && !key.isEmpty() && !key.startsWith("ebx-") && row.path("isSelectAllowed").asBoolean(true)) out.add(key);
		}
		return out;
	}

	/** Table paths ({@code /root/...}) of a dataset, from its Data Services tree (a node without {@code content} is a table). */
	List<String> tablePaths(final String pDataspace, final String pDataset) throws ConnectorException
	{
		final List<String> out = new ArrayList<String>();
		final JsonNode tree = this.fetcher.get(this.dataspacePath(pDataspace) + "/" + HttpJsonClient.encode(pDataset), null);
		if (tree != null) EbxCatalog.walkTree(tree.get("content"), "", out);
		return out;
	}

	static void walkTree(final JsonNode pContent, final String pPath, final List<String> pOut)
	{
		if (pContent == null || !pContent.isObject()) return;
		final Iterator<Map.Entry<String, JsonNode>> it = pContent.fields();
		while (it.hasNext())
		{
			final Map.Entry<String, JsonNode> e = it.next();
			final String path = pPath + "/" + e.getKey();
			final JsonNode node = e.getValue();
			if (node != null && node.isObject() && node.has("content") && node.get("content").isObject())
			{
				EbxCatalog.walkTree(node.get("content"), path, pOut);
			}
			else if (node != null && node.isObject() && node.has("details"))
			{
				pOut.add(path);
			}
		}
	}

	/** {@code {dataspace, dataset, tablePath}} triples selected by the {@code schemas} and {@code exclude} inputs. */
	List<String[]> selectTables(final Map<String, String> pInputs) throws ConnectorException
	{
		final Pattern exclude = Pattern.compile(EbxCatalog.text(pInputs, "exclude", EbxCatalog.DEFAULT_EXCLUDE));
		final String requested = EbxCatalog.text(pInputs, "schemas", "*");
		final List<String[]> direct = EbxCatalog.directTables(pInputs, requested, exclude);
		if (direct != null)
		{
			return direct;
		}
		final List<String[]> scopes = new ArrayList<String[]>(); // {dataspace, dataset or null}
		if ("*".equals(requested))
		{
			for (final String ds : this.allDataspaces()) scopes.add(new String[] { ds, null });
		}
		else
		{
			for (final String entry : requested.split(","))
			{
				final String e = entry.trim();
				if (e.isEmpty()) continue;
				final int slash = e.indexOf('/');
				scopes.add(slash < 0 ? new String[] { e, null } : new String[] { e.substring(0, slash).trim(), e.substring(slash + 1).trim() });
			}
		}
		// the listings of every dataspace, then the tree of every dataset, each level read in parallel
		final List<List<String>> datasetLists = EbxCatalog.parallel(scopes,
			scope -> scope[1] == null ? this.datasets(scope[0]) : java.util.Collections.singletonList(scope[1]));
		final List<String[]> pairs = new ArrayList<String[]>(); // {dataspace, dataset}
		for (int i = 0; i < scopes.size(); i++)
		{
			for (final String dataset : datasetLists.get(i))
			{
				if (!exclude.matcher("/" + scopes.get(i)[0] + "/" + dataset + "/").matches()) pairs.add(new String[] { scopes.get(i)[0], dataset });
			}
		}
		final List<List<String>> trees = EbxCatalog.parallel(pairs, pair -> this.tablePaths(pair[0], pair[1]));
		final List<String[]> out = new ArrayList<String[]>();
		final Set<String> seen = new LinkedHashSet<String>();
		for (int i = 0; i < pairs.size(); i++)
		{
			for (final String path : trees.get(i))
			{
				final String qualified = pairs.get(i)[0] + "/" + pairs.get(i)[1] + path;
				if (exclude.matcher("/" + qualified).matches() || !seen.add(qualified)) continue;
				out.add(new String[] { pairs.get(i)[0], pairs.get(i)[1], path });
			}
		}
		return out;
	}

	/**
	 * The tables named by the {@code tables} input when every entry is a full qualified name
	 * ({@code dataspace/dataset/root/...}), kept only inside {@code schemas} and outside {@code exclude}:
	 * no walk of the instance. Before this, re-profiling one table read the metamodel and the count of
	 * every table of the instance first (seen 2026-09-14: 427 Data Services calls and 31 s for the
	 * 58 columns of Companies). Null when {@code tables} is {@code *} or holds a partial name, so that
	 * the listing applies as before.
	 */
	static List<String[]> directTables(final Map<String, String> pInputs, final String pSchemas, final Pattern pExclude)
	{
		final String requested = EbxCatalog.text(pInputs, "tables", "*");
		if ("*".equals(requested))
		{
			return null;
		}
		final List<String[]> out = new ArrayList<String[]>();
		final Set<String> seen = new LinkedHashSet<String>();
		boolean named = false;
		for (final String entry : requested.split(","))
		{
			final String e = entry.trim();
			if (e.isEmpty()) continue;
			final String[] parts = e.split("/", 3);
			if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty() || !parts[2].startsWith("root/"))
			{
				return null;
			}
			named = true;
			if (!EbxCatalog.inSchemas(pSchemas, parts[0], parts[1]) || pExclude.matcher("/" + parts[0] + "/" + parts[1] + "/").matches()
				|| pExclude.matcher("/" + e).matches() || !seen.add(e)) continue;
			out.add(new String[] { parts[0], parts[1], "/" + parts[2] });
		}
		return named ? out : null;
	}

	/** True when {@code schemas} ({@code *}, dataspace keys or {@code dataspace/dataset}, comma-separated) covers the dataset. */
	static boolean inSchemas(final String pSchemas, final String pDataspace, final String pDataset)
	{
		if (pSchemas == null || "*".equals(pSchemas.trim()))
		{
			return true;
		}
		for (final String entry : pSchemas.split(","))
		{
			final String e = entry.trim();
			if (e.isEmpty()) continue;
			final int slash = e.indexOf('/');
			if (slash < 0 ? e.equals(pDataspace) : e.substring(0, slash).trim().equals(pDataspace) && e.substring(slash + 1).trim().equals(pDataset)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ catalog

	JsonNode catalog(final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = EbxCatalog.required(pInputs, "source");
		final boolean counts = !"false".equalsIgnoreCase(EbxCatalog.text(pInputs, "rowCounts", "true"));
		final String scannedAt = EbxCatalog.now();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("scannedAt", scannedAt);
		final ArrayNode tables = out.putArray("tables");
		// one metamodel read and one count per table, several tables at a time, in the order of the selection
		for (final ObjectNode table : EbxCatalog.parallel(this.selectTables(pInputs), t -> this.describe(t, source, counts, scannedAt)))
		{
			if (table != null) tables.add(table);
		}
		out.put("count", tables.size());
		return tables.size() == 0 ? null : out;
	}

	/** The catalog document of one table ({@code {dataspace, dataset, tablePath}}); null when Data Services has no metamodel for it. */
	ObjectNode describe(final String[] t, final String source, final boolean counts, final String scannedAt) throws ConnectorException
	{
		{
			final String tablePath = this.dataspacePath(t[0]) + "/" + HttpJsonClient.encode(t[1]) + t[2];
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("pageSize", "2");
			query.put("includeMetamodel", "true");
			query.put("includeContent", "false");
			final JsonNode doc = this.fetcher.get(tablePath, query);
			final JsonNode meta = doc == null ? null : doc.get("meta");
			if (meta == null || !meta.isObject()) return null;
			final String qualified = t[0] + "/" + t[1] + t[2];
			final ObjectNode table = Json.object();
			table.put("source", source);
			table.put("assetKey", source + ":" + qualified);
			table.put("qualifiedName", qualified);
			table.put("schema", t[0] + "/" + t[1]);
			table.put("name", t[2]);
			table.put("kind", "TABLE");
			final String label = meta.path("label").asText(null);
			if (label != null && !label.trim().isEmpty()) table.put("label", label.trim());
			final String description = meta.path("description").asText(null);
			if (description != null && !description.trim().isEmpty()) table.put("remarks", EbxCatalog.cut(description.trim(), 4000));
			if (counts)
			{
				final JsonNode count = this.fetcher.get(tablePath + ":count", null);
				if (count != null && count.has("count") && count.get("count").isNumber()) table.put("rowCount", count.get("count").asLong());
			}
			table.put("url", this.baseUrl + tablePath);
			table.put("scannedAt", scannedAt);
			if (this.connectorId != null)
			{
				// Read recipe + deduced scan (2026-09-24): this connector's own "search" operation over the table,
				// itemsPath "rows" (what "search" returns). dataspace/dataset are bare (no B prefix): "search"
				// (EbxConnector.tablePath) adds it itself, the same way "read" and every other operation do.
				final Map<String, String> recipeInputs = new LinkedHashMap<String, String>();
				recipeInputs.put("dataspace", t[0]);
				recipeInputs.put("dataset", t[1]);
				recipeInputs.put("table", t[2]);
				table.put("readRecipe", CatalogOperations.readRecipe(this.connectorId, "search", recipeInputs, "rows"));
				table.put("scanOrigin", "OFFICIAL");
				table.put("scanState", "CONFIRMED");
			}
			final ArrayNode primaryKeys = table.putArray("primaryKeys");
			final Set<String> pk = new LinkedHashSet<String>();
			for (final JsonNode p : meta.path("primaryKeyFields"))
			{
				pk.add(p.asText());
				primaryKeys.add(p.asText());
			}
			final ArrayNode columns = table.putArray("columns");
			final int[] position = { 0 };
			EbxCatalog.walkFields(meta.get("fields"), pk, columns, source + ":" + qualified, scannedAt, position);
			return table;
		}
	}

	/** Terminal fields of a metamodel, groups flattened, associations and technical links left out. */
	static void walkFields(final JsonNode pFields, final Set<String> pPrimaryKeys, final ArrayNode pOut, final String pAssetKey, final String pScannedAt, final int[] pPosition)
	{
		if (pFields == null || !pFields.isArray()) return;
		for (final JsonNode f : pFields)
		{
			final String name = f.path("pathInRecord").asText(f.path("name").asText(null));
			if (name == null || name.isEmpty()) continue;
			final String last = name.substring(name.lastIndexOf('/') + 1);
			if (last.startsWith("Linked_Field_") || EbxCatalog.SKIP_FIELDS.contains(last)) continue;
			final String type = f.path("type").asText("string");
			if ("group".equals(type))
			{
				EbxCatalog.walkFields(f.get("fields"), pPrimaryKeys, pOut, pAssetKey, pScannedAt, pPosition);
				continue;
			}
			if ("association".equals(type) || "table".equals(type) || !f.path("isTerminal").asBoolean(true)) continue;
			String nativeType = type;
			final JsonNode fk = f.get("foreignKey");
			if (fk != null && fk.isObject())
			{
				final String ds = fk.path("dataspace").asText("");
				final String dataset = fk.path("dataset").asText("");
				nativeType = "foreignKey -> " + (ds.isEmpty() ? "" : ds + "/") + (dataset.isEmpty() ? "" : dataset) + fk.path("tablePath").asText("");
			}
			else if (f.has("enumeration"))
			{
				nativeType = type + " (enumeration)";
			}
			final JsonNode maxOccurs = f.get("maxOccurs");
			if (maxOccurs != null && !(maxOccurs.isNumber() && maxOccurs.asInt() == 1) && !"1".equals(maxOccurs.asText())) nativeType = "list<" + nativeType + ">";
			if (f.has("valueFunction")) nativeType += ", computed";
			if (f.path("autoIncrement").asBoolean(false)) nativeType += ", auto-increment";
			boolean mandatory = f.path("minOccurs").asInt(0) >= 1;
			for (final JsonNode c : f.path("constraints"))
			{
				if ("mandatory".equals(c.path("type").asText(""))) mandatory = true;
			}
			pPosition[0]++;
			final ObjectNode c = pOut.addObject();
			c.put("columnKey", pAssetKey + "." + name);
			c.put("name", name);
			c.put("position", pPosition[0]);
			c.put("dataType", EbxCatalog.TYPES.containsKey(type) ? EbxCatalog.TYPES.get(type) : "STRING");
			c.put("nativeType", nativeType);
			c.put("nullable", !mandatory);
			c.put("primaryKey", pPrimaryKeys.contains("/" + name));
			final String label = f.path("label").asText(null);
			if (label != null && !label.trim().isEmpty() && !label.trim().equals(last)) c.put("label", label.trim());
			final String description = f.path("description").asText(null);
			if (description != null && !description.trim().isEmpty()) c.put("remarks", EbxCatalog.cut(description.trim(), 4000));
			if (fk != null && fk.isObject()) c.set("foreignKey", fk);
			c.put("scannedAt", pScannedAt);
		}
	}

	// ------------------------------------------------------------------ profile

	JsonNode profile(final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = EbxCatalog.required(pInputs, "source");
		final int sampleSize = EbxCatalog.bounded(pInputs, "sampleSize", 1000, 3, 10000);
		final Set<String> wanted = EbxCatalog.wanted(pInputs);
		final JsonNode catalog = this.catalog(pInputs);
		final java.time.LocalDateTime nowTs = java.time.LocalDateTime.now().withNano(0);
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("profiledAt", nowTs.format(com.on.ebx.connect.core.CatalogOperations.TIMESTAMP));
		out.put("profileDate", nowTs.toLocalDate().toString());
		final ArrayNode columns = out.putArray("columns");
		if (catalog == null)
		{
			out.put("count", 0);
			return null;
		}
		final List<JsonNode> selected = new ArrayList<JsonNode>();
		for (final JsonNode table : catalog.get("tables"))
		{
			if (wanted.isEmpty() || wanted.contains(table.get("qualifiedName").asText().toUpperCase(java.util.Locale.ENGLISH))) selected.add(table);
		}
		// the records of several tables read at a time; the statistics are computed afterwards, in order
		final List<List<JsonNode>> read = EbxCatalog.parallel(selected, table -> this.records(table, sampleSize));
		for (int i = 0; i < selected.size(); i++)
		{
			final JsonNode table = selected.get(i);
			final String qualified = table.get("qualifiedName").asText();
			final List<JsonNode> records = read.get(i);
			final long exact = table.has("rowCount") ? table.get("rowCount").asLong() : -1L;
			for (final JsonNode col : table.get("columns"))
			{
				final ObjectNode c = columns.addObject();
				c.put("source", source);
				c.put("assetKey", table.get("assetKey").asText());
				c.put("qualifiedName", qualified);
				c.put("columnKey", col.get("columnKey").asText());
				c.put("name", col.get("name").asText());
				c.put("dataType", col.get("dataType").asText());
				c.put("profiledAt", nowTs.format(com.on.ebx.connect.core.CatalogOperations.TIMESTAMP));
				c.put("profileDate", nowTs.toLocalDate().toString());
				// estimates on the records read: RecordProfiler marks them method = SAMPLE with their sampleSize
				RecordProfiler.profile(c, records, col.get("name").asText(), col.get("dataType").asText(), exact);
			}
		}
		out.put("count", columns.size());
		return columns.size() == 0 ? null : out;
	}

	/** The first records of a table, envelopes removed. */
	private List<JsonNode> records(final JsonNode pTable, final int pMax) throws ConnectorException
	{
		final List<JsonNode> out = new ArrayList<JsonNode>();
		final String[] parts = pTable.get("qualifiedName").asText().split("/", 3); // dataspace, dataset, root/...
		if (parts.length < 3) return out;
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("pageSize", String.valueOf(pMax));
		final JsonNode doc = this.fetcher.get(this.dataspacePath(parts[0]) + "/" + HttpJsonClient.encode(parts[1]) + "/" + parts[2], query);
		if (doc == null || !doc.has("rows")) return out;
		for (final JsonNode row : doc.get("rows"))
		{
			final JsonNode record = EbxConnector.unwrap(row.get("content"));
			if (record != null && record.isObject()) out.add(record);
			if (out.size() >= pMax) break;
		}
		return out;
	}

	/** Text of a field ({@code Group/field}) of an unwrapped record; null when absent, null or an empty list. */
	static String valueText(final JsonNode pRecord, final String pPath)
	{
		return RecordProfiler.valueText(pRecord, pPath);
	}

	// ------------------------------------------------------------------ sample

	JsonNode sample(final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = EbxCatalog.required(pInputs, "source");
		final int limit = EbxCatalog.bounded(pInputs, "limit", 50, 3, 500);
		final Set<String> wanted = EbxCatalog.wanted(pInputs);
		final JsonNode catalog = this.catalog(pInputs);
		final String sampledAt = EbxCatalog.now();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("sampledAt", sampledAt);
		final ArrayNode columns = out.putArray("columns");
		if (catalog == null)
		{
			out.put("count", 0);
			return null;
		}
		final List<JsonNode> selected = new ArrayList<JsonNode>();
		for (final JsonNode table : catalog.get("tables"))
		{
			if (!wanted.isEmpty() && !wanted.contains(table.get("qualifiedName").asText().toUpperCase(java.util.Locale.ENGLISH))) continue;
			for (final JsonNode c : table.get("columns"))
			{
				if ("STRING".equals(c.get("dataType").asText()))
				{
					selected.add(table);
					break;
				}
			}
		}
		final List<List<JsonNode>> read = EbxCatalog.parallel(selected, table -> this.records(table, limit));
		for (int i = 0; i < selected.size(); i++)
		{
			final JsonNode table = selected.get(i);
			final String qualified = table.get("qualifiedName").asText();
			final List<JsonNode> textColumns = new ArrayList<JsonNode>();
			for (final JsonNode c : table.get("columns")) if ("STRING".equals(c.get("dataType").asText()) && textColumns.size() < 200) textColumns.add(c);
			final List<JsonNode> records = read.get(i);
			for (final JsonNode col : textColumns)
			{
				final List<String> values = RecordProfiler.distinctValues(records, col.get("name").asText(), 200);
				final ObjectNode c = columns.addObject();
				c.put("source", source);
				c.put("assetKey", table.get("assetKey").asText());
				c.put("qualifiedName", qualified);
				c.put("columnKey", col.get("columnKey").asText());
				c.put("name", col.get("name").asText());
				c.put("dataType", "STRING");
				c.put("sampledAt", sampledAt);
				c.put("sampleSize", records.size());
				final ArrayNode array = c.putArray("values");
				final StringBuilder joined = new StringBuilder();
				for (final String v : values)
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

	// ------------------------------------------------------------------ lineage (foreign keys)

	JsonNode lineage(final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = EbxCatalog.required(pInputs, "source");
		final boolean crossOnly = !"all".equalsIgnoreCase(EbxCatalog.text(pInputs, "fkScope", "cross"));
		final JsonNode catalog = this.catalog(pInputs);
		final String scannedAt = EbxCatalog.now();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("scannedAt", scannedAt);
		final ArrayNode edges = out.putArray("edges");
		if (catalog == null)
		{
			out.put("count", 0);
			return null;
		}
		final Map<String, JsonNode> byName = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode table : catalog.get("tables")) byName.put(table.get("qualifiedName").asText(), table);
		final Set<String> seen = new LinkedHashSet<String>();
		for (final JsonNode table : catalog.get("tables"))
		{
			final String qualified = table.get("qualifiedName").asText();
			final String[] parts = qualified.split("/", 3);
			for (final JsonNode col : table.get("columns"))
			{
				final JsonNode fk = col.get("foreignKey");
				if (fk == null || !fk.isObject()) continue;
				final String targetDataspace = fk.has("dataspace") && !fk.path("dataspace").asText("").isEmpty() ? this.bare(fk.get("dataspace").asText()) : parts[0];
				final String targetDataset = fk.has("dataset") && !fk.path("dataset").asText("").isEmpty() ? fk.get("dataset").asText() : parts[1];
				final String targetPath = fk.path("tablePath").asText("");
				if (targetPath.isEmpty()) continue;
				if (crossOnly && targetDataspace.equals(parts[0]) && targetDataset.equals(parts[1])) continue;
				final String targetName = targetDataspace + "/" + targetDataset + targetPath;
				final JsonNode target = byName.get(targetName);
				if (target == null || target.get("primaryKeys") == null || target.get("primaryKeys").size() != 1) continue;
				final String pk = target.get("primaryKeys").get(0).asText();
				final String fromColumnKey = target.get("assetKey").asText() + "." + (pk.startsWith("/") ? pk.substring(1) : pk);
				final String toColumnKey = col.get("columnKey").asText();
				final String edgeKey = fromColumnKey + ">" + toColumnKey;
				if (!seen.add(edgeKey)) continue;
				final ObjectNode e = edges.addObject();
				e.put("source", source);
				e.put("edgeKey", edgeKey);
				e.put("fromAssetKey", target.get("assetKey").asText());
				e.put("fromColumnKey", fromColumnKey);
				e.put("toAssetKey", table.get("assetKey").asText());
				e.put("toColumnKey", toColumnKey);
				e.put("transformationType", "IDENTITY");
				e.put("transformation", "foreign key " + col.get("name").asText() + " -> " + targetName + " (primary key " + pk + ")");
				e.put("origin", "PLATFORM");
				e.put("confidence", 100);
				e.put("seenAt", scannedAt);
			}
		}
		out.put("count", edges.size());
		return edges.size() == 0 ? null : out;
	}

	// ------------------------------------------------------------------ helpers

	static String required(final Map<String, String> pInputs, final String pName) throws ConnectorException
	{
		final String v = pInputs == null ? null : pInputs.get(pName);
		if (v == null || v.trim().isEmpty()) throw new ConnectorException("Missing input '" + pName + "'");
		return v.trim();
	}

	static String text(final Map<String, String> pInputs, final String pName, final String pDefault)
	{
		final String v = pInputs == null ? null : pInputs.get(pName);
		return v == null || v.trim().isEmpty() ? pDefault : v.trim();
	}

	static int bounded(final Map<String, String> pInputs, final String pName, final int pDefault, final int pMin, final int pMax)
	{
		try
		{
			return Math.max(pMin, Math.min(pMax, Integer.parseInt(EbxCatalog.text(pInputs, pName, String.valueOf(pDefault)))));
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	static Set<String> wanted(final Map<String, String> pInputs)
	{
		final Set<String> out = new LinkedHashSet<String>();
		final String requested = EbxCatalog.text(pInputs, "tables", "*");
		if (!"*".equals(requested)) for (final String t : requested.split(",")) if (!t.trim().isEmpty()) out.add(t.trim().toUpperCase(java.util.Locale.ENGLISH));
		return out;
	}

	/** One read of Data Services on one item (a dataspace, a dataset, a table). */
	interface Task<I, O>
	{
		O run(I pItem) throws ConnectorException;
	}

	/** Threads of the Data Services reads: {@code -Debx.connectors.ebx.catalogThreads}, default 4, 1 = one read at a time, at most 16. */
	static int threads()
	{
		try
		{
			return Math.max(1, Math.min(16, Integer.parseInt(System.getProperty("ebx.connectors.ebx.catalogThreads", "4").trim())));
		}
		catch (final NumberFormatException ex)
		{
			return 4;
		}
	}

	/**
	 * {@code pTask} on every item, several at a time, results in the order of the items. Every read is
	 * an independent HTTP GET on Data Services, which serves them concurrently; the first failure is
	 * rethrown and the remaining reads are cancelled.
	 */
	static <I, O> List<O> parallel(final List<I> pItems, final Task<I, O> pTask) throws ConnectorException
	{
		final int threads = Math.min(EbxCatalog.threads(), pItems.size());
		final List<O> out = new ArrayList<O>(pItems.size());
		if (threads <= 1)
		{
			for (final I item : pItems) out.add(pTask.run(item));
			return out;
		}
		// C2-13: tracked by BackgroundWork (stopped with the module, refused once it is stopped), threads on the module's loader
		final java.util.concurrent.ExecutorService pool = com.on.ebx.connect.core.BackgroundWork.track("catalog reads",
			java.util.concurrent.Executors.newFixedThreadPool(threads, com.on.ebx.connect.core.BackgroundWork.daemonThreads("ebx-connectors-catalog-read", true)));
		try
		{
			final List<java.util.concurrent.Future<O>> futures = new ArrayList<java.util.concurrent.Future<O>>(pItems.size());
			for (final I item : pItems)
			{
				futures.add(pool.submit(new java.util.concurrent.Callable<O>()
				{
					@Override
					public O call() throws Exception
					{
						return pTask.run(item);
					}
				}));
			}
			for (final java.util.concurrent.Future<O> future : futures) out.add(future.get());
			return out;
		}
		catch (final InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			throw new ConnectorException("Data Services reads interrupted", ex);
		}
		catch (final java.util.concurrent.ExecutionException ex)
		{
			final Throwable cause = ex.getCause();
			if (cause instanceof ConnectorException) throw (ConnectorException) cause;
			if (cause instanceof RuntimeException) throw (RuntimeException) cause;
			throw new ConnectorException(String.valueOf(cause), cause);
		}
		finally
		{
			pool.shutdownNow();
		}
	}

	static String now()
	{
		return com.on.ebx.connect.core.CatalogOperations.timestamp();
	}

	static String cut(final String pValue, final int pMax)
	{
		return pValue.length() > pMax ? pValue.substring(0, pMax) : pValue;
	}

	static double round4(final double pValue)
	{
		return Math.round(pValue * 10000d) / 10000d;
	}
}
