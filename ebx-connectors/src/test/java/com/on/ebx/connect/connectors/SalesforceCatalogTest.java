package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Offline contract of the data-catalog operations of the Salesforce connector: a fake org lists
 * standard, custom, history, metadata, hidden and non-queryable objects, one object whose describe fails;
 * the documents must have the shape the catalog flows consume, the Salesforce types normalised, the
 * statistics marked as a sample and the samples limited to text fields. No org, no network.
 */
public final class SalesforceCatalogTest
{
	static final class FakeOrg implements SalesforceCatalog.Org
	{
		final List<String> queries = new ArrayList<String>();

		@Override
		public JsonNode sobjects() throws ConnectorException
		{
			return parse("{\"sobjects\":["
				+ "{\"name\":\"Account\",\"queryable\":true},"
				+ "{\"name\":\"AccountHistory\",\"queryable\":true},"
				+ "{\"name\":\"Invoice__c\",\"queryable\":true,\"custom\":true},"
				+ "{\"name\":\"Setting__mdt\",\"queryable\":true,\"custom\":true},"
				+ "{\"name\":\"Legacy__c\",\"queryable\":true,\"deprecatedAndHidden\":true},"
				+ "{\"name\":\"ContentBody\",\"queryable\":false},"
				+ "{\"name\":\"Bad Name__c\",\"queryable\":true},"
				+ "{\"name\":\"Broken__c\",\"queryable\":true}]}");
		}

		@Override
		public JsonNode describe(final String pName) throws ConnectorException
		{
			if ("Broken__c".equals(pName)) throw new ConnectorException("Salesforce: GET /sobjects/Broken__c/describe -> HTTP 403 INSUFFICIENT_ACCESS", 403, null);
			if ("Account".equals(pName) || "AccountHistory".equals(pName))
			{
				return parse("{\"label\":\"Account\",\"custom\":false,\"fields\":["
					+ "{\"name\":\"Id\",\"type\":\"id\",\"label\":\"Account ID\",\"length\":18,\"nillable\":false},"
					+ "{\"name\":\"Name\",\"type\":\"string\",\"label\":\"Account Name\",\"length\":255,\"nillable\":false},"
					+ "{\"name\":\"AnnualRevenue\",\"type\":\"currency\",\"label\":\"Annual Revenue\",\"nillable\":true},"
					+ "{\"name\":\"BillingAddress\",\"type\":\"address\",\"label\":\"Billing Address\",\"nillable\":true},"
					+ "{\"name\":\"IsDeleted\",\"type\":\"boolean\",\"label\":\"Deleted\",\"nillable\":false},"
					+ "{\"name\":\"OwnerId\",\"type\":\"reference\",\"label\":\"Owner ID\",\"length\":18,\"nillable\":false,\"referenceTo\":[\"User\"]},"
					+ "{\"name\":\"Email__c\",\"type\":\"email\",\"label\":\"Email\",\"length\":80,\"nillable\":true},"
					+ "{\"name\":\"LastModifiedDate\",\"type\":\"datetime\",\"label\":\"Last Modified Date\",\"nillable\":false}]}");
			}
			return parse("{\"label\":\"Invoice\",\"custom\":true,\"fields\":["
				+ "{\"name\":\"Id\",\"type\":\"id\",\"nillable\":false},"
				+ "{\"name\":\"Amount__c\",\"type\":\"double\",\"nillable\":true},"
				+ "{\"name\":\"IssuedOn__c\",\"type\":\"date\",\"nillable\":true}]}");
		}

		@Override
		public JsonNode query(final String pSoql) throws ConnectorException
		{
			this.queries.add(pSoql);
			if (pSoql.startsWith("SELECT COUNT() FROM Account")) return parse("{\"totalSize\":120,\"records\":[]}");
			if (pSoql.startsWith("SELECT COUNT() FROM Invoice__c")) throw new ConnectorException("MALFORMED_QUERY", 400, null);
			if (pSoql.contains("FROM Account"))
			{
				return parse("{\"totalSize\":4,\"records\":["
					+ "{\"attributes\":{\"type\":\"Account\"},\"Id\":\"001A\",\"Name\":\"Acme SA\",\"AnnualRevenue\":1200.5,\"IsDeleted\":false,\"OwnerId\":\"005X\",\"Email__c\":\"contact@acme.example\",\"LastModifiedDate\":\"2026-09-15T10:00:00.000+0000\"},"
					+ "{\"attributes\":{\"type\":\"Account\"},\"Id\":\"001B\",\"Name\":\"Beta SARL\",\"AnnualRevenue\":null,\"IsDeleted\":false,\"OwnerId\":\"005X\",\"Email__c\":null,\"LastModifiedDate\":\"2026-09-14T10:00:00.000+0000\"},"
					+ "{\"attributes\":{\"type\":\"Account\"},\"Id\":\"001C\",\"Name\":\"Gamma AG\",\"AnnualRevenue\":88,\"IsDeleted\":false,\"OwnerId\":\"005Y\",\"Email__c\":\"info@gamma.example\",\"LastModifiedDate\":\"2026-09-13T10:00:00.000+0000\"},"
					+ "{\"attributes\":{\"type\":\"Account\"},\"Id\":\"001D\",\"Name\":\"Acme SA\",\"AnnualRevenue\":10,\"IsDeleted\":true,\"OwnerId\":\"005Y\",\"Email__c\":\"contact@acme.example\",\"LastModifiedDate\":\"2026-09-12T10:00:00.000+0000\"}]}");
			}
			return parse("{\"totalSize\":0,\"records\":[]}");
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final FakeOrg org = new FakeOrg();
		final SalesforceCatalog catalog = new SalesforceCatalog(org, "https://login.salesforce.com", "SF_CONNECTOR");
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("source", "SF");

		// --- selection: core standard objects + custom objects, queryable and visible only
		check(names(catalog.selectObjects(inputs, false)).equals("Account,Invoice__c,Broken__c"), "default selection: " + catalog.selectObjects(inputs, false));
		inputs.put("schemas", "Account*");
		check(names(catalog.selectObjects(inputs, false)).equals("Account,AccountHistory"), "patterns: " + catalog.selectObjects(inputs, false));
		inputs.remove("schemas");
		inputs.put("tables", "invoice__c");
		check(names(catalog.selectObjects(inputs, true)).equals("Invoice__c") && names(catalog.selectObjects(inputs, false)).equals("Account,Invoice__c,Broken__c"),
			"tables narrows profile and sample only");
		inputs.remove("tables");

		// --- catalog
		final JsonNode scan = catalog.execute("catalog", inputs);
		check(scan.path("count").asInt() == 2 && scan.path("errorCount").asInt() == 1 && "Broken__c".equals(scan.path("errors").get(0).path("object").asText()),
			"two objects read, the broken one reported: " + scan.path("errors"));
		final JsonNode account = scan.path("tables").get(0);
		check("SF:Account".equals(account.path("assetKey").asText()) && "Account".equals(account.path("qualifiedName").asText()) && "TABLE".equals(account.path("kind").asText())
			&& account.path("rowCount").asLong() == 120L && "salesforce://login.salesforce.com/Account".equals(account.path("url").asText()), "Account asset: " + account);
		check(account.path("remarks").asText().contains("standard object"), "remarks");
		final Map<String, JsonNode> cols = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode c : account.path("columns")) cols.put(c.path("name").asText(), c);
		check(cols.size() == 8 && "SF:Account.Name".equals(cols.get("Name").path("columnKey").asText()), "all describe fields listed: " + cols.keySet());
		check(cols.get("Id").path("primaryKey").asBoolean() && !cols.get("Name").path("primaryKey").asBoolean(), "Id is the key");
		check("DECIMAL".equals(cols.get("AnnualRevenue").path("dataType").asText()) && cols.get("AnnualRevenue").path("nullable").asBoolean(), "currency");
		check("JSON".equals(cols.get("BillingAddress").path("dataType").asText()) && "BOOLEAN".equals(cols.get("IsDeleted").path("dataType").asText())
			&& "TIMESTAMP".equals(cols.get("LastModifiedDate").path("dataType").asText()), "address, boolean, datetime");
		check("string(255)".equals(cols.get("Name").path("nativeType").asText()) && !cols.get("Name").path("nullable").asBoolean(), "native type with length, nillable");
		check(cols.get("OwnerId").path("remarks").asText().contains("User"), "reference in remarks");
		final JsonNode invoice = scan.path("tables").get(1);
		check(!invoice.has("rowCount") && invoice.path("remarks").asText().contains("custom object"), "no count when COUNT() is refused");

		// --- read recipe + deduced scan (2026-09-24): every catalogued table gets an OFFICIAL, CONFIRMED recipe,
		// compound fields (BillingAddress) left out of the SOQL, itemsPath "records" (what the query operation returns)
		check("OFFICIAL".equals(account.path("scanOrigin").asText()) && "CONFIRMED".equals(account.path("scanState").asText()), "scan origin/state: " + account);
		final JsonNode recipe = Json.parse(account.path("readRecipe").asText());
		check("SF_CONNECTOR".equals(recipe.path("connector").asText()) && "query".equals(recipe.path("operation").asText())
			&& "records".equals(recipe.path("itemsPath").asText()), "recipe shape: " + recipe);
		final String soql = recipe.path("inputs").path("soql").asText();
		check(soql.startsWith("SELECT Id, Name, AnnualRevenue") && !soql.contains("BillingAddress") && soql.endsWith("FROM Account"),
			"recipe SOQL over every catalogued field, compound excluded: " + soql);

		// --- profile
		inputs.put("tables", "Account");
		final JsonNode profile = catalog.execute("profile", inputs);
		final String select = org.queries.get(org.queries.size() - 1).startsWith("SELECT COUNT()") ? org.queries.get(org.queries.size() - 2) : org.queries.get(org.queries.size() - 1);
		check(select.startsWith("SELECT Id, Name, AnnualRevenue, IsDeleted, OwnerId, Email__c, LastModifiedDate FROM Account ORDER BY LastModifiedDate DESC LIMIT 1000"),
			"one SOQL per object, compound fields left out, latest first: " + select);
		final Map<String, JsonNode> stats = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode c : profile.path("columns")) stats.put(c.path("name").asText(), c);
		check(stats.size() == 7 && !stats.containsKey("BillingAddress"), "profiled fields: " + stats.keySet());
		final JsonNode revenue = stats.get("AnnualRevenue");
		check("SAMPLE".equals(revenue.path("method").asText()) && revenue.path("sampleSize").asInt() == 4 && "SF:Account.AnnualRevenue".equals(revenue.path("columnKey").asText())
			&& "SF:Account".equals(revenue.path("assetKey").asText()), "sample statistics: " + revenue);
		check(stats.get("Name").path("distinctCount").asInt() == 3, "distinct names on the sample: " + stats.get("Name"));

		// --- sample
		inputs.put("limit", "100");
		final JsonNode sample = catalog.execute("sample", inputs);
		final Map<String, JsonNode> samples = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode c : sample.path("columns")) samples.put(c.path("name").asText(), c);
		check(samples.keySet().toString().equals("[Name, Email__c]"), "only text fields, no id nor reference: " + samples.keySet());
		check(samples.get("Email__c").path("values").size() == 2 && samples.get("Email__c").path("valuesText").asText().contains("info@gamma.example"), "distinct values");

		// --- nothing readable at all
		inputs.put("schemas", "Broken__c");
		inputs.put("tables", "*");
		try
		{
			catalog.execute("catalog", inputs);
			check(false, "an org where nothing can be read fails");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("Broken__c"), "names the object: " + expected.getMessage());
		}
		check("STRING".equals(SalesforceCatalog.dataType("picklist")) && "INTEGER".equals(SalesforceCatalog.dataType("int")) && "TIME".equals(SalesforceCatalog.dataType("time"))
			&& "BINARY".equals(SalesforceCatalog.dataType("base64")), "type mapping");

		System.out.println("SalesforceCatalogTest: PASS");
	}

	static String names(final List<String> pNames)
	{
		final StringBuilder b = new StringBuilder();
		for (final String n : pNames) b.append(b.length() == 0 ? "" : ",").append(n);
		return b.toString();
	}

	static JsonNode parse(final String pJson) throws ConnectorException
	{
		try
		{
			return Json.parse(pJson);
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("bad test JSON: " + ex.getMessage());
		}
	}

	static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
