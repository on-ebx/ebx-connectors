package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Offline contract of the data-catalog operations of the EBX connector: a fake Data Services answers
 * the listings, trees, metamodels, counts and records of a two-dataspace instance, and the documents
 * must have the shape the catalog flows consume, with the catalog's naming of EBX tables
 * ({@code dataspace/dataset/root/Table}, {@code source:qualifiedName}, {@code assetKey.Group/field}).
 */
public final class EbxCatalogTest
{
	static final class FakeDataServices implements EbxCatalog.Fetcher
	{
		// reads run on several threads (EbxCatalog.parallel)
		final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<String>());

		@Override
		public JsonNode get(final String pPath, final Map<String, String> pQuery) throws ConnectorException
		{
			this.calls.add(pPath + (pQuery == null ? "" : pQuery.toString()));
			try
			{
				if ("/".equals(pPath))
					return Json.parse("{\"rows\":[{\"key\":\"BReference\",\"hasChildren\":true,\"isSelectAllowed\":true},{\"key\":\"Bebx-validationReport\",\"hasChildren\":false}]}");
				if ("/BReference:children".equals(pPath))
					return Json.parse("{\"rows\":[{\"key\":\"BCompanies\",\"hasChildren\":false,\"isSelectAllowed\":true},{\"key\":\"BGeographies\",\"hasChildren\":false,\"isSelectAllowed\":true},{\"key\":\"BClosed\",\"closed\":true}]}");
				if ("/BCompanies".equals(pPath))
					return Json.parse("{\"rows\":[{\"key\":\"Companies\"},{\"key\":\"Companies_Companies_MDS\"},{\"key\":\"ebx-addon-dama-metadata\"}]}");
				if ("/BGeographies".equals(pPath))
					return Json.parse("{\"rows\":[{\"key\":\"Geographies\"}]}");
				if ("/BCompanies/Companies".equals(pPath))
					return Json.parse("{\"content\":{\"root\":{\"details\":\"x\",\"content\":{\"Companies\":{\"details\":\"x\"},\"ReferenceData\":{\"details\":\"x\",\"content\":{\"Site\":{\"details\":\"x\"}}},"
						+ "\"TechnicalData\":{\"details\":\"x\",\"content\":{\"Xref\":{\"details\":\"x\"}}}}}}}");
				if ("/BGeographies/Geographies".equals(pPath))
					return Json.parse("{\"content\":{\"root\":{\"details\":\"x\",\"content\":{\"Country\":{\"details\":\"x\"}}}}}");
				if (pPath.endsWith(":count"))
					return Json.parse(pPath.contains("Country") ? "{\"count\":249}" : "{\"count\":615}");
				if (pQuery != null && "true".equals(pQuery.get("includeMetamodel")))
				{
					if (pPath.endsWith("/root/Companies"))
						return Json.parse("{\"meta\":{\"name\":\"Companies\",\"label\":\"Companies\",\"description\":\"Golden records\",\"primaryKeyFields\":[\"/Identification/Id\"],\"fields\":["
							+ "{\"name\":\"Identification\",\"type\":\"group\",\"pathInRecord\":\"Identification\",\"fields\":["
							+ "{\"name\":\"Id\",\"label\":\"MDM Id\",\"type\":\"int\",\"isTerminal\":true,\"minOccurs\":1,\"maxOccurs\":1,\"autoIncrement\":true,\"pathInRecord\":\"Identification/Id\",\"constraints\":[{\"type\":\"mandatory\"}]},"
							+ "{\"name\":\"Name\",\"label\":\"Name\",\"type\":\"string\",\"isTerminal\":true,\"minOccurs\":1,\"maxOccurs\":1,\"pathInRecord\":\"Identification/Name\",\"description\":\"Legal name\"}]},"
							+ "{\"name\":\"Country\",\"label\":\"Country\",\"type\":\"string\",\"isTerminal\":true,\"minOccurs\":0,\"maxOccurs\":1,\"pathInRecord\":\"Country\",\"foreignKey\":{\"dataspace\":\"BGeographies\",\"dataset\":\"Geographies\",\"tablePath\":\"/root/Country\"}},"
							+ "{\"name\":\"Tags\",\"type\":\"string\",\"isTerminal\":true,\"minOccurs\":0,\"maxOccurs\":\"unbounded\",\"pathInRecord\":\"Tags\"},"
							+ "{\"name\":\"Association_Reverse\",\"type\":\"association\",\"pathInRecord\":\"Association_Reverse\"},"
							+ "{\"name\":\"Roles\",\"type\":\"association\",\"isTerminal\":false,\"pathInRecord\":\"Roles\"}]}}");
					if (pPath.endsWith("/root/ReferenceData/Site"))
						return Json.parse("{\"meta\":{\"name\":\"Site\",\"label\":\"Site\",\"primaryKeyFields\":[\"/code\"],\"fields\":[{\"name\":\"code\",\"type\":\"string\",\"isTerminal\":true,\"minOccurs\":1,\"pathInRecord\":\"code\"}]}}");
					if (pPath.endsWith("/root/Country"))
						return Json.parse("{\"meta\":{\"name\":\"Country\",\"label\":\"Country\",\"primaryKeyFields\":[\"/Alpha2Code\"],\"fields\":["
							+ "{\"name\":\"Alpha2Code\",\"type\":\"string\",\"isTerminal\":true,\"minOccurs\":1,\"pathInRecord\":\"Alpha2Code\"},"
							+ "{\"name\":\"Name\",\"type\":\"string\",\"isTerminal\":true,\"minOccurs\":0,\"pathInRecord\":\"Name\"},"
							+ "{\"name\":\"Population\",\"type\":\"decimal\",\"isTerminal\":true,\"minOccurs\":0,\"pathInRecord\":\"Population\"}]}}");
					return null;
				}
				if (pQuery != null && pQuery.containsKey("pageSize") && pPath.endsWith("/root/Country"))
					return Json.parse("{\"rows\":[{\"content\":{\"Alpha2Code\":{\"content\":\"FR\"},\"Name\":{\"content\":\"France\"},\"Population\":{\"content\":68}}},"
						+ "{\"content\":{\"Alpha2Code\":{\"content\":\"DE\"},\"Name\":{\"content\":\"Germany\"},\"Population\":{\"content\":84}}},"
						+ "{\"content\":{\"Alpha2Code\":{\"content\":\"XX\"},\"Name\":{\"content\":null},\"Population\":{\"content\":null}}}]}");
				if (pQuery != null && pQuery.containsKey("pageSize"))
					return Json.parse("{\"rows\":[]}");
				return null;
			}
			catch (final java.io.IOException ex)
			{
				throw new ConnectorException(ex.getMessage(), ex);
			}
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final FakeDataServices ds = new FakeDataServices();
		final EbxCatalog catalog = new EbxCatalog(ds, "B", "http://localhost:8080/ebx-dataservices/rest/data/v1");
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("source", "EBX_TEST");

		// --- catalog over every dataspace: technical dataspaces, MDS and add-on datasets, technical tables left out
		final JsonNode scan = catalog.catalog(inputs);
		check(scan != null && scan.get("count").asInt() == 3, "3 tables inventoried (Companies, Site, Country), not Xref / MDS / add-on: " + (scan == null ? null : scan.get("tables")));
		final JsonNode companies = scan.get("tables").get(0);
		check("Companies/Companies/root/Companies".equals(companies.get("qualifiedName").asText()) && "EBX_TEST:Companies/Companies/root/Companies".equals(companies.get("assetKey").asText()),
			"qualified name and asset key follow the catalog's EBX naming: " + companies.get("qualifiedName"));
		check("Companies/Companies".equals(companies.get("schema").asText()) && "/root/Companies".equals(companies.get("name").asText()) && "TABLE".equals(companies.get("kind").asText()), "schema = dataspace/dataset, name = table path");
		check(companies.get("rowCount").asLong() == 615 && "Golden records".equals(companies.get("remarks").asText()), "row count and model documentation");
		check(companies.get("url").asText().endsWith("/BCompanies/Companies/root/Companies"), "url = the Data Services URL of the table");
		final JsonNode columns = companies.get("columns");
		check(columns.size() == 4, "4 terminal columns (groups flattened, associations left out): " + columns);
		final JsonNode id = columns.get(0);
		check("Identification/Id".equals(id.get("name").asText()) && "EBX_TEST:Companies/Companies/root/Companies.Identification/Id".equals(id.get("columnKey").asText()), "column key = assetKey.pathInRecord");
		check("INTEGER".equals(id.get("dataType").asText()) && id.get("primaryKey").asBoolean() && !id.get("nullable").asBoolean() && id.get("nativeType").asText().contains("auto-increment"),
			"type, primary key, mandatory, native flags: " + id);
		check("MDM Id".equals(id.get("label").asText()) && "Legal name".equals(columns.get(1).get("remarks").asText()), "label and documentation of the fields");
		final JsonNode country = columns.get(2);
		check(country.get("nativeType").asText().startsWith("foreignKey -> BGeographies/Geographies/root/Country") && country.has("foreignKey"), "foreign key kept for the lineage: " + country.get("nativeType"));
		check(columns.get(3).get("nativeType").asText().equals("list<string>"), "multi-valued field: " + columns.get(3).get("nativeType"));
		check(!companies.has("readRecipe"), "no read recipe without a connector id");

		// --- read recipe + deduced scan (2026-09-24): only written when the connector passes its own id
		final EbxCatalog recipeCatalog = new EbxCatalog(ds, "B", "http://localhost:8080/ebx-dataservices/rest/data/v1", "EBX_CONNECTOR");
		final JsonNode recipeScan = recipeCatalog.catalog(inputs);
		final JsonNode recipeCompanies = recipeScan.get("tables").get(0);
		check("OFFICIAL".equals(recipeCompanies.get("scanOrigin").asText()) && "CONFIRMED".equals(recipeCompanies.get("scanState").asText()), "scan origin/state: " + recipeCompanies);
		final JsonNode recipe = com.on.ebx.connect.core.Json.parse(recipeCompanies.get("readRecipe").asText());
		check("EBX_CONNECTOR".equals(recipe.path("connector").asText()) && "search".equals(recipe.path("operation").asText()) && "rows".equals(recipe.path("itemsPath").asText()),
			"recipe shape: " + recipe);
		check("Companies".equals(recipe.path("inputs").path("dataspace").asText()) && "Companies".equals(recipe.path("inputs").path("dataset").asText())
			&& "/root/Companies".equals(recipe.path("inputs").path("table").asText()), "recipe inputs match the search operation's own contract: " + recipe.path("inputs"));

		// --- schema filter: one dataspace, or dataspace/dataset
		final Map<String, String> filtered = new LinkedHashMap<String, String>(inputs);
		filtered.put("schemas", "Geographies");
		filtered.put("rowCounts", "false");
		final JsonNode geo = catalog.catalog(filtered);
		check(geo.get("count").asInt() == 1 && !geo.get("tables").get(0).has("rowCount"), "schemas = a dataspace; rowCounts=false skips :count");
		filtered.put("schemas", "Companies/Companies");
		check(catalog.catalog(filtered).get("count").asInt() == 2, "schemas = dataspace/dataset");

		// --- tables = full qualified names: straight to those tables, no walk of the instance
		final Map<String, String> one = new LinkedHashMap<String, String>(inputs);
		one.put("tables", "Geographies/Geographies/root/Country");
		one.put("sampleSize", "10");
		ds.calls.clear();
		final JsonNode direct = catalog.profile(one);
		check(direct != null && direct.get("count").asInt() == 3, "direct profile of Country: " + direct);
		boolean listed = false;
		for (final String call : new ArrayList<String>(ds.calls)) listed |= call.startsWith("/{") || call.equals("/") || call.contains(":children") || call.endsWith("/BCompanies") || call.endsWith("/BGeographies/Geographies");
		check(!listed && ds.calls.size() == 3, "only the metamodel, the count and the records of Country are read: " + ds.calls);
		one.put("schemas", "Companies");
		check(catalog.profile(one) == null, "a named table outside schemas is not read");
		one.remove("schemas");
		one.put("tables", "Country");
		check(EbxCatalog.directTables(one, "*", Pattern.compile(EbxCatalog.DEFAULT_EXCLUDE)) == null, "a partial name falls back to the listing");

		// --- the parallel reads keep the order of the sequential ones
		System.setProperty("ebx.connectors.ebx.catalogThreads", "1");
		final String sequential = catalog.catalog(inputs).get("tables").toString();
		System.setProperty("ebx.connectors.ebx.catalogThreads", "8");
		check(sequential.equals(catalog.catalog(inputs).get("tables").toString()), "same catalog, same order, with 1 and 8 threads");
		System.clearProperty("ebx.connectors.ebx.catalogThreads");

		// --- profile: exact count, statistics on the sampled records
		final Map<String, String> profileInputs = new LinkedHashMap<String, String>(inputs);
		profileInputs.put("schemas", "Geographies");
		profileInputs.put("sampleSize", "10");
		final JsonNode profile = catalog.profile(profileInputs);
		check(profile != null && profile.get("count").asInt() == 3, "one profile item per column of Country: " + profile);
		final JsonNode name = profile.get("columns").get(1);
		check(name.get("rowCount").asLong() == 249 && name.get("sampleSize").asInt() == 3 && Math.abs(name.get("nullRate").asDouble() - 0.3333) < 1e-9 && name.get("nullCount").asLong() == 83,
			"null rate from the sample, null count scaled to the exact row count: " + name);
		check(name.get("distinctCount").asInt() == 2 && name.get("minLength").asInt() == 6 && name.get("maxLength").asInt() == 7 && "France".equals(name.get("minValue").asText()), "text statistics: " + name);
		check("SAMPLE".equals(name.get("method").asText()), "a profile computed on the records read says so: method = SAMPLE, " + name.get("method"));
		final JsonNode population = profile.get("columns").get(2);
		check("DECIMAL".equals(population.get("dataType").asText()) && population.get("meanValue").asDouble() == 76d && "84".equals(population.get("maxValue").asText()), "numeric statistics: " + population);
		check(profile.get("profileDate").asText().matches("\\d{4}-\\d{2}-\\d{2}"), "profileDate is a date");

		// --- sample: distinct values of the text columns
		final JsonNode sample = catalog.sample(profileInputs);
		check(sample != null && sample.get("count").asInt() == 2, "two text columns sampled: " + sample);
		final JsonNode alpha = sample.get("columns").get(0);
		check(alpha.get("values").size() == 3 && "FR\nDE\nXX".equals(alpha.get("valuesText").asText()) && alpha.get("sampleSize").asInt() == 3, "sampled values: " + alpha);

		// --- lineage: the cross-dataset foreign key Companies.Country -> Geographies.Country.Alpha2Code
		final JsonNode lineage = catalog.lineage(inputs);
		check(lineage != null && lineage.get("count").asInt() == 1, "one cross-dataset foreign key: " + lineage);
		final JsonNode edge = lineage.get("edges").get(0);
		check("EBX_TEST:Geographies/Geographies/root/Country.Alpha2Code".equals(edge.get("fromColumnKey").asText())
			&& "EBX_TEST:Companies/Companies/root/Companies.Country".equals(edge.get("toColumnKey").asText())
			&& edge.get("edgeKey").asText().equals(edge.get("fromColumnKey").asText() + ">" + edge.get("toColumnKey").asText()), "edge from the target's primary key to the foreign-key field: " + edge);
		check("IDENTITY".equals(edge.get("transformationType").asText()) && "PLATFORM".equals(edge.get("origin").asText()) && edge.get("confidence").asInt() == 100, "edge vocabulary");

		// --- a missing source is refused
		try
		{
			catalog.catalog(new LinkedHashMap<String, String>());
			check(false, "source is required");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("source"), "message names the input");
		}
		System.out.println("EbxCatalogTest: OK (" + ds.calls.size() + " Data Services calls)");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
