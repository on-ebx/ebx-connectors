package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.connectors.DatabricksConnector;
import com.on.ebx.connect.connectors.SnowflakeConnector;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Offline contract of the data-catalog operations for statement-API platforms: a fake runner answers
 * canned rows for the SQL each dialect generates, and the documents must have the exact shape the
 * catalog flows consume ({@code JdbcConnector} produces the same). No account, no network.
 */
public final class CatalogOperationsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		for (final String platform : new String[] { "snowflake", "databricks" })
		{
			final FakeRunner runner = new FakeRunner(platform);
			final CatalogOperations.Dialect dialect = "snowflake".equals(platform)
				? new SnowflakeConnector.SnowflakeDialect("ANALYTICS") : new DatabricksConnector.DatabricksDialect("analytics");
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			inputs.put("source", "DWH");

			final JsonNode catalog = CatalogOperations.catalog(runner, dialect, inputs, null);
			check(catalog != null && catalog.get("count").asInt() == 2, platform + " catalog: 2 tables");
			final JsonNode orders = catalog.get("tables").get(0);
			check("DWH:SALES.ORDERS".equals(orders.get("assetKey").asText()), platform + " assetKey");
			check("TABLE".equals(orders.get("kind").asText()) && "VIEW".equals(catalog.get("tables").get(1).get("kind").asText()), platform + " kinds");
			check(orders.get("columns").size() == 3, platform + " 3 columns on ORDERS");
			final JsonNode price = orders.get("columns").get(1);
			check("DECIMAL".equals(price.get("dataType").asText()) && "DWH:SALES.ORDERS.GROSS_PRICE".equals(price.get("columnKey").asText()), platform + " column normalised type and key");
			check(!orders.get("columns").get(0).get("nullable").asBoolean(), platform + " NOT NULL read from IS_NULLABLE");
			check(runner.sql.get(0).toUpperCase().contains("SCHEMA_NAME") && runner.sql.get(1).contains("IN ('SALES'") && runner.sql.get(2).toUpperCase().contains("COLUMNS"), platform + " catalog SQL sequence");

			// --- read recipe + deduced scan (2026-09-24): only written when a connector id is given (profile/sample
			// call catalog() for the table list only, with no id, and must get no recipe)
			final FakeRunner recipeRunner = new FakeRunner(platform);
			final JsonNode recipeCatalog = CatalogOperations.catalog(recipeRunner, dialect, inputs, null, "DWH_CONNECTOR");
			final JsonNode recipeOrders = recipeCatalog.get("tables").get(0);
			check("OFFICIAL".equals(recipeOrders.get("scanOrigin").asText()) && "CONFIRMED".equals(recipeOrders.get("scanState").asText()), platform + " scan origin/state");
			final JsonNode recipe = Json.parse(recipeOrders.get("readRecipe").asText());
			check("DWH_CONNECTOR".equals(recipe.path("connector").asText()) && "query".equals(recipe.path("operation").asText())
				&& "rows".equals(recipe.path("itemsPath").asText()), platform + " recipe shape: " + recipe);
			final String recipeSql = recipe.path("inputs").path("sql").asText();
			check(recipeSql.startsWith("SELECT ") && recipeSql.contains(" FROM ") && recipeSql.contains(dialect.quote("SALES.ORDERS")), platform + " recipe SQL: " + recipeSql);
			check(!catalog.get("tables").get(0).has("readRecipe"), platform + " no recipe without a connector id");

			runner.sql.clear();
			final JsonNode profile = CatalogOperations.profile(runner, dialect, inputs, null);
			check(profile != null && profile.get("count").asInt() == 3 + 1, platform + " profile: one item per column of both tables");
			final JsonNode p = profile.get("columns").get(1);
			check(p.get("rowCount").asLong() == 1000 && p.get("nullCount").asLong() == 40 && Math.abs(p.get("nullRate").asDouble() - 0.04) < 1e-9, platform + " profile counts");
			check("19.90".equals(p.get("minValue").asText()) && p.get("meanValue").asDouble() > 500, platform + " profile min / mean");
			final String profileSql = runner.sql.get(3);
			check(profileSql.startsWith("SELECT COUNT(*) AS N0") && profileSql.contains("COUNT(DISTINCT") && profileSql.contains("LENGTH("), platform + " profile SQL");

			runner.sql.clear();
			inputs.put("limit", "5");
			final JsonNode sample = CatalogOperations.sample(runner, dialect, inputs, null);
			check(sample != null && sample.get("count").asInt() == 1, platform + " sample: only the STRING column");
			check(sample.get("columns").get(0).get("values").size() == 2 && sample.get("columns").get(0).get("valuesText").asText().contains("\n"), platform + " sample distinct values, joined text");
			check(runner.sql.get(3).endsWith("LIMIT 5"), platform + " sample LIMIT");

			final JsonNode usage = CatalogOperations.usage(runner, dialect, inputs);
			check(usage != null && usage.get("count").asInt() == 1, platform + " usage: foreign database/catalog row dropped");
			check("DWH:SALES.ORDERS".equals(usage.get("items").get(0).get("assetKey").asText()) && usage.get("items").get(0).get("queryCount30d").asLong() == 4210, platform + " usage item");

			final JsonNode lineage = CatalogOperations.lineage(runner, dialect, inputs);
			check(lineage != null && lineage.get("count").asInt() == 2, platform + " lineage: duplicate edge collapsed");
			final JsonNode e = lineage.get("edges").get(0);
			check("DWH:SALES.ORDERS.GROSS_PRICE>DWH:FINANCE.REVENUE.NET_REVENUE".equals(e.get("edgeKey").asText()), platform + " edgeKey");
			check("PLATFORM".equals(e.get("origin").asText()) && e.has("seenAt"), platform + " lineage origin and seenAt");
			check(!lineage.get("edges").get(1).has("fromColumnKey"), platform + " table-level edge has no column keys");
			final FakeRunner cross = new FakeRunner(platform);
			final Map<String, String> crossInputs = new LinkedHashMap<String, String>();
			crossInputs.put("source", "DWH");
			crossInputs.put("schemas", "SALES,OTHERDB.MARKETING");
			final JsonNode crossCatalog = CatalogOperations.catalog(cross, dialect, crossInputs, null);
			check(crossCatalog != null && crossCatalog.get("count").asInt() == 3, platform + " cross-database catalog: 2 own + 1 foreign");
			check("DWH:SALES.ORDERS".equals(crossCatalog.get("tables").get(0).get("assetKey").asText()), platform + " own database keeps unprefixed names");
			final JsonNode lead = crossCatalog.get("tables").get(2);
			check("DWH:OTHERDB.MARKETING.LEAD".equals(lead.get("assetKey").asText()) && "OTHERDB.MARKETING".equals(lead.get("schema").asText()), platform + " foreign database keeps its prefix");
			check(lead.get("columns").size() == 1 && "DWH:OTHERDB.MARKETING.LEAD.EMAIL".equals(lead.get("columns").get(0).get("columnKey").asText()), platform + " foreign column key");
			check(cross.sql.size() == 4 && !cross.sql.get(0).contains("OTHERDB") && cross.sql.get(2).contains("OTHERDB."), platform + " one statement pair per database, the foreign one qualified");
			System.out.println("PASS: " + platform + " catalog operations (" + runner.calls + " statements)");
		}
		check("STRING".equals(CatalogOperations.normalisedType("VARCHAR(80)")) && "DECIMAL".equals(CatalogOperations.normalisedType("NUMBER(12,2)"))
			&& "TIMESTAMP".equals(CatalogOperations.normalisedType("TIMESTAMP_NTZ(9)")) && "JSON".equals(CatalogOperations.normalisedType("VARIANT"))
			&& "INTEGER".equals(CatalogOperations.normalisedType("bigint")) && "DATE".equals(CatalogOperations.normalisedType("date")), "type normalisation");
		System.out.println("PASS: type normalisation");
		check("2026-09-17T22:13:00".equals(java.time.LocalDateTime.of(2026, 9, 17, 22, 13, 0).format(CatalogOperations.TIMESTAMP))
			&& "2026-09-17T22:13:07".equals(java.time.LocalDateTime.of(2026, 9, 17, 22, 13, 7).format(CatalogOperations.TIMESTAMP))
			&& CatalogOperations.timestamp().length() == 19, "a timestamp keeps its seconds when they are zero");
		System.out.println("PASS: timestamp format");
	}

	/** Answers the dialect's statements with canned rows; records every SQL it saw. */
	static final class FakeRunner implements CatalogOperations.SqlRunner
	{
		final List<String> sql = new ArrayList<String>();
		final String platform;
		int calls;

		FakeRunner(final String pPlatform)
		{
			this.platform = pPlatform;
		}

		private static ObjectNode result(final ArrayNode pRows)
		{
			final ObjectNode out = Json.object();
			out.set("rows", pRows);
			out.put("count", pRows.size());
			return out;
		}

		private ObjectNode row(final ArrayNode pRows, final Object... pPairs)
		{
			final ObjectNode r = pRows.addObject();
			for (int i = 0; i < pPairs.length; i += 2)
			{
				final String key = "snowflake".equals(this.platform) ? String.valueOf(pPairs[i]).toUpperCase() : String.valueOf(pPairs[i]).toLowerCase();
				final Object v = pPairs[i + 1];
				if (v == null) r.putNull(key);
				else if (v instanceof Integer) r.put(key, (Integer) v);
				else if (v instanceof Long) r.put(key, (Long) v);
				else if (v instanceof Double) r.put(key, (Double) v);
				else r.put(key, String.valueOf(v));
			}
			return r;
		}

		@Override
		public JsonNode run(final String pSql) throws ConnectorException
		{
			this.sql.add(pSql);
			this.calls++;
			final ArrayNode rows = Json.object().putArray("rows");
			final String prefix = "snowflake".equals(this.platform) ? "ANALYTICS." : "analytics.";
			if (pSql.contains("SCHEMATA") || pSql.contains("schemata"))
			{
				this.row(rows, "SCHEMA_NAME", "SALES");
				this.row(rows, "SCHEMA_NAME", "FINANCE");
			}
			else if (pSql.contains("INFORMATION_SCHEMA.TABLES") || pSql.contains("information_schema.tables"))
			{
				if (pSql.contains("OTHERDB."))
				{
					this.row(rows, "TABLE_SCHEMA", "MARKETING", "TABLE_NAME", "LEAD", "TABLE_TYPE", "BASE TABLE", "TABLE_COMMENT", null, "ROW_COUNT", 12L);
					return FakeRunner.result(rows);
				}
				this.row(rows, "TABLE_SCHEMA", "SALES", "TABLE_NAME", "ORDERS", "TABLE_TYPE", "BASE TABLE", "TABLE_COMMENT", "Orders", "ROW_COUNT", 1000L);
				this.row(rows, "TABLE_SCHEMA", "FINANCE", "TABLE_NAME", "REVENUE", "TABLE_TYPE", "VIEW", "TABLE_COMMENT", null, "ROW_COUNT", null);
			}
			else if (pSql.contains("INFORMATION_SCHEMA.COLUMNS") || pSql.contains("information_schema.columns"))
			{
				if (pSql.contains("OTHERDB."))
				{
					this.row(rows, "TABLE_SCHEMA", "MARKETING", "TABLE_NAME", "LEAD", "COLUMN_NAME", "EMAIL", "ORDINAL_POSITION", 1, "DATA_TYPE", "VARCHAR(100)", "IS_NULLABLE", "YES", "COLUMN_COMMENT", null);
					return FakeRunner.result(rows);
				}
				this.row(rows, "TABLE_SCHEMA", "SALES", "TABLE_NAME", "ORDERS", "COLUMN_NAME", "ORDER_ID", "ORDINAL_POSITION", 1, "DATA_TYPE", "NUMBER", "IS_NULLABLE", "NO", "COLUMN_COMMENT", null);
				this.row(rows, "TABLE_SCHEMA", "SALES", "TABLE_NAME", "ORDERS", "COLUMN_NAME", "GROSS_PRICE", "ORDINAL_POSITION", 2, "DATA_TYPE", "NUMBER(12,2)", "IS_NULLABLE", "YES", "COLUMN_COMMENT", "before discount");
				this.row(rows, "TABLE_SCHEMA", "SALES", "TABLE_NAME", "ORDERS", "COLUMN_NAME", "STATUS", "ORDINAL_POSITION", 3, "DATA_TYPE", "VARCHAR(20)", "IS_NULLABLE", "YES", "COLUMN_COMMENT", null);
				this.row(rows, "TABLE_SCHEMA", "FINANCE", "TABLE_NAME", "REVENUE", "COLUMN_NAME", "NET_REVENUE", "ORDINAL_POSITION", 1, "DATA_TYPE", "NUMBER(12,2)", "IS_NULLABLE", "YES", "COLUMN_COMMENT", null);
			}
			else if (pSql.startsWith("SELECT COUNT(*) AS N0"))
			{
				if (pSql.contains("STATUS"))
				{
					this.row(rows, "N0", 1000L, "C0", 1000L, "D0", 1000L, "MI0", "100001", "MA0", "103000", "AV0", 101500.5, "LMI0", null, "LMA0", null, "LAV0", null,
						"C1", 960L, "D1", 900L, "MI1", "19.90", "MA1", "999.63", "AV1", 503.4754, "LMI1", null, "LMA1", null, "LAV1", null,
						"C2", 1000L, "D2", 3L, "MI2", "CANCELLED", "MA2", "SHIPPED", "AV2", null, "LMI2", 7, "LMA2", 9, "LAV2", 7.8);
				}
				else
				{
					this.row(rows, "N0", 2667L, "C0", 2667L, "D0", 2667L, "MI0", "-18.40", "MA0", "999.63", "AV0", 503.47, "LMI0", null, "LMA0", null, "LAV0", null);
				}
			}
			else if (pSql.contains("base_objects_accessed") || pSql.contains("table_lineage"))
			{
				this.row(rows, "OBJECT_NAME", prefix + "SALES.ORDERS", "QUERY_COUNT", 4210L);
				this.row(rows, "OBJECT_NAME", "OTHERDB.SALES.ORDERS", "QUERY_COUNT", 99L);
			}
			else if (pSql.contains("objects_modified") || pSql.contains("column_lineage"))
			{
				this.row(rows, "SOURCE_TABLE", prefix + "SALES.ORDERS", "SOURCE_COLUMN", "GROSS_PRICE", "TARGET_TABLE", prefix + "FINANCE.REVENUE", "TARGET_COLUMN", "NET_REVENUE", "SEEN_AT", "2026-09-12T10:00:00");
				this.row(rows, "SOURCE_TABLE", prefix + "SALES.ORDERS", "SOURCE_COLUMN", "GROSS_PRICE", "TARGET_TABLE", prefix + "FINANCE.REVENUE", "TARGET_COLUMN", "NET_REVENUE", "SEEN_AT", "2026-09-11T10:00:00");
				this.row(rows, "SOURCE_TABLE", prefix + "SALES.ORDERS", "SOURCE_COLUMN", null, "TARGET_TABLE", prefix + "FINANCE.REVENUE", "TARGET_COLUMN", null, "SEEN_AT", "2026-09-12T10:00:00");
			}
			else if (pSql.startsWith("SELECT ") && pSql.contains("LIMIT"))
			{
				this.row(rows, "STATUS", "SHIPPED");
				this.row(rows, "STATUS", "PENDING");
				this.row(rows, "STATUS", "SHIPPED");
			}
			return FakeRunner.result(rows);
		}
	}

	private static void check(final boolean pValue, final String pMessage)
	{
		if (!pValue) throw new AssertionError(pMessage);
	}
}
