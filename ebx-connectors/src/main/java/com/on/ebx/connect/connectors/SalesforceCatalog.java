package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.RecordProfiler;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * The data-catalog operations of the Salesforce connector — {@code catalog}, {@code profile},
 * {@code sample} — in the documents the catalog flows consume (the shape of {@link KafkaCatalog} and
 * {@code JdbcConnector}). Salesforce declares its schema, so the inventory reads it; statistics are
 * computed by ON EBX on the latest records, SOQL having no aggregate per field:
 * <ul>
 * <li>{@code catalog}: one asset of kind {@code TABLE} per sObject ({@code qualifiedName} = the API name,
 * the name the lineage of the mappings uses), {@code rowCount} from {@code SELECT COUNT()}, the fields of
 * its describe: Salesforce types normalised ({@code currency} → {@code DECIMAL}, {@code datetime} →
 * {@code TIMESTAMP}, {@code address} → {@code JSON}...), {@code nativeType} with the length,
 * {@code nullable} from {@code nillable}, the Id as primary key, the label and the referenced objects in
 * {@code remarks}.</li>
 * <li>{@code profile}: {@link RecordProfiler} statistics on the {@code sampleSize} most recently modified
 * records ({@code method = SAMPLE}); compound and binary fields are left out.</li>
 * <li>{@code sample}: distinct values of the text fields (not ids, not references) of the {@code limit}
 * latest records.</li>
 * </ul>
 * Inputs: {@code source} (required), {@code schemas} (comma-separated sObject API names or patterns with
 * {@code *}; empty or {@code *} = the core standard objects and every custom object {@code __c}),
 * {@code tables} (API names, or {@code *}), {@code rowCounts}. Only queryable, visible sObjects are read;
 * one that cannot be read is reported under {@code errors} and the others go on.
 */
final class SalesforceCatalog
{
	/** What the catalog reads from the org: the REST API as it answers. */
	interface Org
	{
		/** {@code GET /sobjects}: {@code sobjects[]} with name, label, custom, queryable, deprecatedAndHidden. */
		JsonNode sobjects() throws ConnectorException;

		/** {@code GET /sobjects/{name}/describe}. */
		JsonNode describe(String pName) throws ConnectorException;

		/** A SOQL query: the query result ({@code totalSize}, {@code records[]}). */
		JsonNode query(String pSoql) throws ConnectorException;
	}

	static final List<String> OPERATIONS = Arrays.asList("catalog", "profile", "sample");
	/** Standard objects inventoried when no filter is given; custom objects ({@code __c}) always are. */
	static final List<String> STANDARD = Arrays.asList("Account", "Contact", "Lead", "Opportunity", "OpportunityLineItem", "Case", "Campaign", "CampaignMember",
		"Contract", "Order", "OrderItem", "Product2", "Pricebook2", "PricebookEntry", "Asset", "User", "Task", "Event");
	/** Compound and binary fields: no profile, and a compound field is read through its components. */
	static final Set<String> UNPROFILED = new HashSet<String>(Arrays.asList("address", "location", "base64", "complexvalue"));
	static final Set<String> TEXT = new HashSet<String>(Arrays.asList("string", "textarea", "picklist", "multipicklist", "email", "phone", "url", "combobox"));
	static final int MAX_FIELDS = 200;
	static final Pattern API_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

	private final Org org;
	private final String host;
	private final String connectorId;

	SalesforceCatalog(final Org pOrg, final String pBaseUrl, final String pConnectorId)
	{
		this.org = pOrg;
		this.connectorId = pConnectorId;
		String h = pBaseUrl == null ? "" : pBaseUrl.trim();
		try
		{
			final java.net.URI uri = new java.net.URI(h);
			h = uri.getHost() == null ? h : uri.getHost();
		}
		catch (final java.net.URISyntaxException ex)
		{
			// keep the text
		}
		this.host = h;
	}

	JsonNode execute(final String pOperation, final Map<String, String> pInputs) throws ConnectorException
	{
		if ("catalog".equals(pOperation)) return this.catalog(pInputs);
		if ("profile".equals(pOperation)) return this.profile(pInputs);
		if ("sample".equals(pOperation)) return this.sample(pInputs);
		throw new ConnectorException("unknown catalog operation '" + pOperation + "'");
	}

	// ------------------------------------------------------------------ selection

	/** sObjects selected by {@code schemas} (or the default rule), then by {@code tables} when {@code pWanted}. */
	List<String> selectObjects(final Map<String, String> pInputs, final boolean pWanted) throws ConnectorException
	{
		final List<Pattern> include = new ArrayList<Pattern>();
		final String requested = EbxCatalog.text(pInputs, "schemas", "*");
		if (!"*".equals(requested))
		{
			for (final String entry : requested.split(","))
			{
				if (!entry.trim().isEmpty()) include.add(KafkaCatalog.glob(entry.trim()));
			}
		}
		final Set<String> wanted = pWanted ? EbxCatalog.wanted(pInputs) : Collections.<String> emptySet();
		final List<String> out = new ArrayList<String>();
		for (final JsonNode o : this.org.sobjects().path("sobjects"))
		{
			final String name = o.path("name").asText("");
			if (!API_NAME.matcher(name).matches() || !o.path("queryable").asBoolean(false) || o.path("deprecatedAndHidden").asBoolean(false)) continue;
			if (include.isEmpty() ? !(STANDARD.contains(name) || name.endsWith("__c")) : !SalesforceCatalog.matchesAny(include, name)) continue;
			if (!wanted.isEmpty() && !wanted.contains(name.toUpperCase(Locale.ENGLISH))) continue;
			out.add(name);
		}
		return out;
	}

	static boolean matchesAny(final List<Pattern> pPatterns, final String pName)
	{
		for (final Pattern p : pPatterns)
		{
			if (p.matcher(pName).matches()) return true;
		}
		return false;
	}

	/** Salesforce field type → the catalog's normalised types. */
	static String dataType(final String pType)
	{
		final String t = pType == null ? "" : pType.toLowerCase(Locale.ENGLISH);
		if ("boolean".equals(t)) return "BOOLEAN";
		if ("int".equals(t) || "long".equals(t)) return "INTEGER";
		if ("double".equals(t) || "currency".equals(t) || "percent".equals(t)) return "DECIMAL";
		if ("date".equals(t)) return "DATE";
		if ("datetime".equals(t)) return "TIMESTAMP";
		if ("time".equals(t)) return "TIME";
		if ("base64".equals(t)) return "BINARY";
		if ("address".equals(t) || "location".equals(t) || "complexvalue".equals(t) || "json".equals(t)) return "JSON";
		return "STRING";
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
		final ArrayNode errors = Json.mapper().createArrayNode();
		for (final String name : this.selectObjects(pInputs, false))
		{
			try
			{
				final JsonNode describe = this.org.describe(name);
				final String assetKey = source + ":" + name;
				final ObjectNode table = Json.object();
				table.put("source", source);
				table.put("assetKey", assetKey);
				table.put("qualifiedName", name);
				table.put("name", name);
				table.put("kind", "TABLE");
				table.put("remarks", describe.path("label").asText(name) + (describe.path("custom").asBoolean(false) ? " (Salesforce custom object)" : " (Salesforce standard object)"));
				if (counts)
				{
					final long rows = this.count(name);
					if (rows >= 0) table.put("rowCount", rows);
				}
				table.put("url", "salesforce://" + this.host + "/" + name);
				table.put("scannedAt", scannedAt);
				final ArrayNode columns = table.putArray("columns");
				int position = 0;
				final StringBuilder soqlFields = new StringBuilder(); // read recipe: every catalogued field, compound ones excluded (they fail a SOQL SELECT with their parts present)
				for (final JsonNode f : describe.path("fields"))
				{
					final String field = f.path("name").asText("");
					if (field.isEmpty()) continue;
					final String type = f.path("type").asText("");
					final ObjectNode c = columns.addObject();
					c.put("columnKey", assetKey + "." + field);
					c.put("name", field);
					c.put("position", ++position);
					c.put("dataType", SalesforceCatalog.dataType(type));
					c.put("nativeType", f.path("length").asInt(0) > 0 ? type + "(" + f.path("length").asInt() + ")" : type);
					c.put("nullable", f.path("nillable").asBoolean(true));
					c.put("primaryKey", "id".equalsIgnoreCase(type));
					final StringBuilder remarks = new StringBuilder(f.path("label").asText(field));
					final JsonNode refs = f.path("referenceTo");
					if (refs.isArray() && refs.size() > 0)
					{
						remarks.append(" → ");
						for (int i = 0; i < refs.size(); i++)
						{
							remarks.append(i == 0 ? "" : ", ").append(refs.get(i).asText());
						}
					}
					c.put("remarks", remarks.toString());
					c.put("scannedAt", scannedAt);
					if (!"address".equalsIgnoreCase(type) && !"location".equalsIgnoreCase(type))
					{
						soqlFields.append(soqlFields.length() > 0 ? ", " : "").append(field);
					}
				}
				if (this.connectorId != null && soqlFields.length() > 0)
				{
					final Map<String, String> recipeInputs = Collections.singletonMap("soql", "SELECT " + soqlFields + " FROM " + name);
					table.put("readRecipe", com.on.ebx.connect.core.CatalogOperations.readRecipe(this.connectorId, "query", recipeInputs, "records"));
					table.put("scanOrigin", "OFFICIAL");
					table.put("scanState", "CONFIRMED");
				}
				tables.add(table);
			}
			catch (final ConnectorException ex)
			{
				errors.addObject().put("object", name).put("message", ex.getMessage());
			}
		}
		return SalesforceCatalog.finish(out, tables, errors);
	}

	/** {@code SELECT COUNT()}; -1 when the object refuses it. */
	long count(final String pName)
	{
		try
		{
			return this.org.query("SELECT COUNT() FROM " + pName).path("totalSize").asLong(-1L);
		}
		catch (final ConnectorException ex)
		{
			return -1L;
		}
	}

	/** The most recently modified records (most recently created when there is no LastModifiedDate), {@code attributes} removed. */
	List<JsonNode> latest(final String pName, final JsonNode pDescribe, final List<String> pFields, final int pMax) throws ConnectorException
	{
		boolean modified = false;
		for (final JsonNode f : pDescribe.path("fields"))
		{
			if ("LastModifiedDate".equals(f.path("name").asText())) modified = true;
		}
		final StringBuilder soql = new StringBuilder("SELECT ");
		for (int i = 0; i < pFields.size(); i++)
		{
			soql.append(i == 0 ? "" : ", ").append(pFields.get(i));
		}
		soql.append(" FROM ").append(pName).append(modified ? " ORDER BY LastModifiedDate DESC" : "").append(" LIMIT ").append(pMax);
		final List<JsonNode> out = new ArrayList<JsonNode>();
		for (final JsonNode r : this.org.query(soql.toString()).path("records"))
		{
			if (r.isObject())
			{
				final ObjectNode copy = ((ObjectNode) r).deepCopy();
				copy.remove("attributes");
				out.add(copy);
			}
		}
		return out;
	}

	/** Fields of a describe, in order, whose type passes the filter; at most {@link #MAX_FIELDS}. */
	static List<String[]> fields(final JsonNode pDescribe, final boolean pTextOnly)
	{
		final List<String[]> out = new ArrayList<String[]>();
		for (final JsonNode f : pDescribe.path("fields"))
		{
			final String name = f.path("name").asText("");
			final String type = f.path("type").asText("").toLowerCase(Locale.ENGLISH);
			if (name.isEmpty() || UNPROFILED.contains(type) || pTextOnly && !TEXT.contains(type)) continue;
			if (out.size() >= MAX_FIELDS) break;
			out.add(new String[] { name, type });
		}
		return out;
	}

	static List<String> names(final List<String[]> pFields)
	{
		final List<String> out = new ArrayList<String>();
		for (final String[] f : pFields)
		{
			out.add(f[0]);
		}
		return out;
	}

	// ------------------------------------------------------------------ profile

	JsonNode profile(final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = EbxCatalog.required(pInputs, "source");
		final int size = EbxCatalog.bounded(pInputs, "sampleSize", 1000, 3, 2000);
		final boolean counts = !"false".equalsIgnoreCase(EbxCatalog.text(pInputs, "rowCounts", "true"));
		final java.time.LocalDateTime nowTs = java.time.LocalDateTime.now().withNano(0);
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("profiledAt", nowTs.format(com.on.ebx.connect.core.CatalogOperations.TIMESTAMP));
		out.put("profileDate", nowTs.toLocalDate().toString());
		final ArrayNode columns = out.putArray("columns");
		final ArrayNode errors = Json.mapper().createArrayNode();
		for (final String name : this.selectObjects(pInputs, true))
		{
			try
			{
				final JsonNode describe = this.org.describe(name);
				final List<String[]> fields = SalesforceCatalog.fields(describe, false);
				if (fields.isEmpty()) continue;
				final List<JsonNode> records = this.latest(name, describe, SalesforceCatalog.names(fields), size);
				if (records.isEmpty()) continue;
				final long rows = counts ? this.count(name) : -1L;
				for (final String[] f : fields)
				{
					final ObjectNode c = columns.addObject();
					c.put("source", source);
					c.put("assetKey", source + ":" + name);
					c.put("qualifiedName", name);
					c.put("columnKey", source + ":" + name + "." + f[0]);
					c.put("name", f[0]);
					c.put("dataType", SalesforceCatalog.dataType(f[1]));
					c.put("profiledAt", nowTs.format(com.on.ebx.connect.core.CatalogOperations.TIMESTAMP));
					c.put("profileDate", nowTs.toLocalDate().toString());
					RecordProfiler.profile(c, records, f[0], SalesforceCatalog.dataType(f[1]), rows);
				}
			}
			catch (final ConnectorException ex)
			{
				errors.addObject().put("object", name).put("message", ex.getMessage());
			}
		}
		return SalesforceCatalog.finish(out, columns, errors);
	}

	// ------------------------------------------------------------------ sample

	JsonNode sample(final Map<String, String> pInputs) throws ConnectorException
	{
		final String source = EbxCatalog.required(pInputs, "source");
		final int limit = EbxCatalog.bounded(pInputs, "limit", 50, 3, 500);
		final String sampledAt = EbxCatalog.now();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("sampledAt", sampledAt);
		final ArrayNode columns = out.putArray("columns");
		final ArrayNode errors = Json.mapper().createArrayNode();
		for (final String name : this.selectObjects(pInputs, true))
		{
			try
			{
				final JsonNode describe = this.org.describe(name);
				final List<String[]> fields = SalesforceCatalog.fields(describe, true);
				if (fields.isEmpty()) continue;
				final List<JsonNode> records = this.latest(name, describe, SalesforceCatalog.names(fields), limit);
				for (final String[] f : fields)
				{
					final ObjectNode c = columns.addObject();
					c.put("source", source);
					c.put("assetKey", source + ":" + name);
					c.put("qualifiedName", name);
					c.put("columnKey", source + ":" + name + "." + f[0]);
					c.put("name", f[0]);
					c.put("dataType", "STRING");
					c.put("sampledAt", sampledAt);
					c.put("sampleSize", records.size());
					final ArrayNode array = c.putArray("values");
					final StringBuilder joined = new StringBuilder();
					for (final String v : RecordProfiler.distinctValues(records, f[0], 200))
					{
						array.add(v);
						joined.append(joined.length() > 0 ? "\n" : "").append(v.replace('\n', ' ').replace('\r', ' '));
					}
					c.put("valuesText", joined.toString());
				}
			}
			catch (final ConnectorException ex)
			{
				errors.addObject().put("object", name).put("message", ex.getMessage());
			}
		}
		return SalesforceCatalog.finish(out, columns, errors);
	}

	// ------------------------------------------------------------------ helpers

	/** Count and errors; null when nothing was produced, an exception when nothing could be read at all. */
	static JsonNode finish(final ObjectNode pOut, final ArrayNode pItems, final ArrayNode pErrors) throws ConnectorException
	{
		pOut.put("count", pItems.size());
		if (pErrors.size() > 0)
		{
			pOut.put("errorCount", pErrors.size());
			pOut.set("errors", pErrors);
		}
		if (pItems.size() == 0 && pErrors.size() > 0)
		{
			throw new ConnectorException("Salesforce: no object could be read (" + pErrors.size() + "); first: " + pErrors.get(0).path("object").asText() + ": "
				+ pErrors.get(0).path("message").asText());
		}
		return pItems.size() == 0 ? null : pOut;
	}
}
