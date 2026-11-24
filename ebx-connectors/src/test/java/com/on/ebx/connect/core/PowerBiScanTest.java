package com.on.ebx.connect.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Offline contract of the Power BI scan interpretation: a recorded Scanner API result
 * ({@code src/test/resources/powerbi/scanResult.json}) must yield the exact catalog and lineage
 * documents the data-catalog flows consume — assets and keys, Power Query data sources mapped to a
 * catalog source or materialised as external assets, pass-through columns, DAX references, report
 * and dashboard links. No tenant, no network.
 */
public final class PowerBiScanTest
{
	private PowerBiScanTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final JsonNode scanResult = Json.parse(new String(Files.readAllBytes(PowerBiScanTest.fixture("powerbi/scanResult.json").toPath()), StandardCharsets.UTF_8));

		final PowerBiScan.Options options = new PowerBiScan.Options();
		options.source = "POWERBI";
		options.scannedAt = "2026-09-13T10:00:00";
		options.datasourceMap = PowerBiScan.Options.parseDatasourceMap("xy12345.snowflakecomputing.com=DWH:upper\n # comment-like junk\n");
		check(options.datasourceMap.size() == 1 && "DWH".equals(options.datasourceMap.get("xy12345.snowflakecomputing.com").source), "datasource map parsed");

		final PowerBiScan.Result all = PowerBiScan.scan(scanResult, options);
		final JsonNode tables = all.catalog.get("tables");
		check(all.catalog.get("count").asInt() == 11, "11 assets over Finance + Marketing, personal workspace skipped (got " + all.catalog.get("count") + ")");
		final Map<String, JsonNode> byKey = index(tables, "assetKey");
		check(byKey.containsKey("POWERBI:Finance/models/Finance model") && "SEMANTIC_MODEL".equals(byKey.get("POWERBI:Finance/models/Finance model").get("kind").asText()), "semantic model asset");
		check(byKey.containsKey("POWERBI:Finance/reports/Revenue dashboard") && byKey.get("POWERBI:Finance/reports/Revenue dashboard").get("url").asText().contains("/reports/aaaaaaaa-0001"), "report asset with portal URL");
		check(byKey.containsKey("POWERBI:Finance/dashboards/Exec overview"), "dashboard asset");
		check(byKey.containsKey("POWERBI:Finance/dataflows/Sales staging") && byKey.containsKey("POWERBI:Finance/dataflows/Sales staging.Customers"), "dataflow and its entity");
		check(!byKey.containsKey("POWERBI:datasources/xy12345.snowflakecomputing.com/ANALYTICS/SALES.ORDERS"), "mapped Snowflake source is not materialised");
		final JsonNode external = byKey.get("POWERBI:datasources/sqlsrv.corp.example/CRM/dbo.Customer");
		check(external != null && "TABLE".equals(external.get("kind").asText()) && external.get("columns").size() == 3, "unmapped SQL Server table materialised once with 3 inferred columns");
		check("datasources/sqlsrv.corp.example/CRM".equals(external.get("schema").asText()) && "dbo.Customer".equals(external.get("name").asText()), "external asset schema / name");

		final JsonNode orders = byKey.get("POWERBI:Finance/models/Finance model.Orders");
		check(orders != null && "Finance/models/Finance model".equals(orders.get("schema").asText()), "model table under the model schema");
		check(orders.get("columns").size() == 8, "Orders: 4 data + 1 calculated + 3 measures, hidden column skipped (got " + orders.get("columns").size() + ")");
		final Map<String, JsonNode> columns = index(orders.get("columns"), "name");
		check("INTEGER".equals(columns.get("Order id").get("dataType").asText()) && "Int64".equals(columns.get("Order id").get("nativeType").asText()), "column type normalised, native kept");
		check("MEASURE".equals(columns.get("Total revenue").get("nativeType").asText()) && columns.get("Total revenue").get("expression").asText().startsWith("SUM("), "measure with its DAX");
		check(columns.get("Net revenue").get("nativeType").asText().startsWith("CALCULATED") && columns.get("Net revenue").has("expression"), "calculated column with its DAX");
		check("POWERBI:Finance/models/Finance model.Orders.Total revenue".equals(columns.get("Total revenue").get("columnKey").asText()), "measure column key");

		final JsonNode edges = all.lineage.get("edges");
		final Map<String, JsonNode> byEdge = index(edges, "edgeKey");
		check(byEdge.size() == edges.size(), "edge keys are unique");
		check(all.lineage.get("count").asInt() == 24, "24 edges (got " + all.lineage.get("count") + ": " + byEdge.keySet() + ")");
		final JsonNode snowflake = byEdge.get("DWH:SALES.ORDERS>POWERBI:Finance/models/Finance model.Orders");
		check(snowflake != null && "IDENTITY".equals(snowflake.get("transformationType").asText()) && snowflake.get("transformation").asText().contains("Snowflake.Databases")
			&& snowflake.get("confidence").asInt() == 90 && "PLATFORM".equals(snowflake.get("origin").asText()), "Snowflake table edge from the parameterised Power Query, IDENTITY (rename + types only)");
		check(byEdge.containsKey("DWH:SALES.ORDERS.GROSS_PRICE>POWERBI:Finance/models/Finance model.Orders.Gross price")
			&& byEdge.containsKey("DWH:SALES.ORDERS.ORDER_DATE>POWERBI:Finance/models/Finance model.Orders.Order date"), "renamed pass-through columns, upper-cased for the mapped source");
		check(!byEdge.containsKey("DWH:SALES.ORDERS.NET REVENUE>POWERBI:Finance/models/Finance model.Orders.Net revenue"), "calculated column has no source column");
		final JsonNode customer = byEdge.get("POWERBI:datasources/sqlsrv.corp.example/CRM/dbo.Customer>POWERBI:Finance/models/Finance model.Customer");
		check(customer != null && "FILTER".equals(customer.get("transformationType").asText()) && customer.get("confidence").asInt() == 80, "SQL Server table edge typed FILTER (Table.SelectRows)");
		check(byEdge.containsKey("POWERBI:datasources/sqlsrv.corp.example/CRM/dbo.Customer.Country>POWERBI:Finance/models/Finance model.Customer.Country"), "inferred external column edge");
		final JsonNode net = byEdge.get("POWERBI:Finance/models/Finance model.Orders.Gross price>POWERBI:Finance/models/Finance model.Orders.Net revenue");
		check(net != null && "TRANSFORMATION".equals(net.get("transformationType").asText()) && net.get("transformation").asText().contains("Orders[Gross price]"), "DAX calculated column edge");
		final JsonNode total = byEdge.get("POWERBI:Finance/models/Finance model.Orders.Net revenue>POWERBI:Finance/models/Finance model.Orders.Total revenue");
		check(total != null && "AGGREGATION".equals(total.get("transformationType").asText()) && total.get("confidence").asInt() == 85, "DAX measure edge typed AGGREGATION");
		check(byEdge.containsKey("POWERBI:Finance/models/Finance model.Orders.Total discount>POWERBI:Finance/models/Finance model.Orders.Discount rate")
			&& byEdge.containsKey("POWERBI:Finance/models/Finance model.Orders.Total revenue>POWERBI:Finance/models/Finance model.Orders.Discount rate"), "measure -> measure references, comment ignored");
		check(byEdge.containsKey("POWERBI:Finance/models/Finance model>POWERBI:Finance/reports/Revenue dashboard"), "model -> report");
		check(byEdge.containsKey("POWERBI:Finance/reports/Revenue dashboard>POWERBI:Finance/dashboards/Exec overview")
			&& byEdge.containsKey("POWERBI:Finance/models/Finance model>POWERBI:Finance/dashboards/Exec overview"), "tiles: report -> dashboard and model -> dashboard");
		check(byEdge.containsKey("POWERBI:Finance/dataflows/Sales staging>POWERBI:Finance/models/Finance model"), "upstream dataflow -> model");
		check(byEdge.containsKey("POWERBI:datasources/sqlsrv.corp.example/CRM/dbo.Customer>POWERBI:Finance/dataflows/Sales staging.Customers")
			&& byEdge.containsKey("POWERBI:datasources/sqlsrv.corp.example/CRM/dbo.Customer.Id>POWERBI:Finance/dataflows/Sales staging.Customers.Id"), "dataflow mashup: source -> entity, columns");
		check(byEdge.containsKey("POWERBI:Marketing/models/Campaigns>POWERBI:Marketing/reports/Campaign results"), "second workspace linked");
		check(all.notes.size() == 1 && all.notes.get(0).startsWith("Date:"), "the List.Dates table is reported as unresolved, not failed");

		// workspace filter and kind filter
		final PowerBiScan.Options finance = new PowerBiScan.Options();
		finance.source = "POWERBI";
		finance.workspaces = new LinkedHashSet<String>(Collections.singleton("finance"));
		finance.datasourceMap = options.datasourceMap;
		check(PowerBiScan.catalog(scanResult, finance).get("count").asInt() == 9, "workspace filter: 9 Finance assets");
		finance.kinds = new LinkedHashSet<String>(Collections.singleton("REPORT"));
		check(PowerBiScan.catalog(scanResult, finance).get("count").asInt() == 1, "kind filter: 1 report");
		finance.kinds = null;
		finance.externalAssets = false;
		final PowerBiScan.Result noExternal = PowerBiScan.scan(scanResult, finance);
		check(noExternal.catalog.get("count").asInt() == 8 && !index(noExternal.lineage.get("edges"), "edgeKey").containsKey("POWERBI:datasources/sqlsrv.corp.example/CRM/dbo.Customer>POWERBI:Finance/models/Finance model.Customer"),
			"external assets off: no materialised table, its edges dropped");

		// Power Query parsing details
		final PowerBiScan.MQuery nativeQuery = PowerBiScan.parseM("let Source = Sql.Database(\"srv\", \"db\", [Query=\"select o.id, c.name from sales.orders o join dbo.customer c on c.id = o.cust\"]) in Source",
			Collections.<String, String>emptyMap());
		final PowerBiScan.Resolved resolved = PowerBiScan.resolve(nativeQuery.sources.get(0), null);
		check(resolved.items.size() == 2 && "sales".equals(resolved.items.get(0)[0]) && "orders".equals(resolved.items.get(0)[1]) && "customer".equals(resolved.items.get(1)[1]), "native SQL query: tables read from FROM/JOIN");
		final PowerBiScan.MQuery join = PowerBiScan.parseM("let\n Source = PostgreSQL.Database(\"pg\", \"crm\"),\n public_orders = Source{[Schema=\"public\",Item=\"orders\"]}[Data],\n Merged = Table.NestedJoin(public_orders, {\"cust\"}, Customers, {\"id\"}, \"Customers\", JoinKind.LeftOuter),\n Grouped = Table.Group(Merged, {\"cust\"}, {{\"n\", each Table.RowCount(_), Int64.Type}})\nin Grouped",
			Collections.<String, String>emptyMap());
		check("JOIN".equals(join.type()) && join.referencedQueries.contains("Customers"), "join beats aggregation in the type precedence, referenced query kept");
		final PowerBiScan.MQuery dataflow = PowerBiScan.parseM("let\n Source = PowerPlatform.Dataflows(null),\n Workspaces = Source{[Id=\"Workspaces\"]}[Data],\n ws = Workspaces{[workspaceId=\"11111111-1111-1111-1111-111111111111\"]}[Data],\n df = ws{[dataflowId=\"ffffffff-0001-0000-0000-000000000001\"]}[Data],\n entity = df{[entity=\"Customers\",version=\"\"]}[Data]\nin entity",
			Collections.<String, String>emptyMap());
		final PowerBiScan.Resolved dfResolved = PowerBiScan.resolve(dataflow.sources.get(0), null);
		check("DATAFLOW".equals(dfResolved.platform) && "ffffffff-0001-0000-0000-000000000001".equals(dfResolved.dataflowId) && "Customers".equals(dfResolved.entity), "dataflow navigation resolved");
		final List<String[]> refs = PowerBiScan.daxReferences("CALCULATE(SUM('Sales Orders'[Amount]), Customer[Country] = \"FR\" /* [not a ref] */) + [Other measure]");
		check(refs.size() == 3 && "Sales Orders".equals(refs.get(0)[0]) && "Amount".equals(refs.get(0)[1]) && "Customer".equals(refs.get(1)[0]) && refs.get(2)[0] == null && "Other measure".equals(refs.get(2)[1]),
			"DAX references: quoted table, plain table, bare measure; strings and comments ignored");
		check("INTEGER".equals(PowerBiScan.normalisedType("Int64")) && "TIMESTAMP".equals(PowerBiScan.normalisedType("DateTime")) && "DECIMAL".equals(PowerBiScan.normalisedType("Double"))
			&& "STRING".equals(PowerBiScan.normalisedType("String")), "Tabular types normalised");

		// usage and refresh feeds
		final JsonNode events = Json.parse("[{\"Activity\":\"ViewReport\",\"WorkSpaceName\":\"Finance\",\"ReportName\":\"Revenue dashboard\"},{\"Activity\":\"ViewReport\",\"WorkSpaceName\":\"Finance\",\"ReportName\":\"Revenue dashboard\"},"
			+ "{\"Activity\":\"ViewDashboard\",\"WorkSpaceName\":\"Finance\",\"DashboardName\":\"Exec overview\"},{\"Activity\":\"GetRefreshHistory\",\"WorkSpaceName\":\"Finance\"}]");
		final JsonNode usage = PowerBiScan.usage(events, options);
		check(usage.get("count").asInt() == 2 && usage.get("items").get(0).get("queryCount30d").asInt() == 2 && "POWERBI:Finance/reports/Revenue dashboard".equals(usage.get("items").get(0).get("assetKey").asText()), "usage counted per report / dashboard");
		final JsonNode job = PowerBiScan.job(options, "Finance", "models", "Finance model", Json.parse("[{\"requestId\":\"r1\",\"refreshType\":\"Scheduled\",\"startTime\":\"2026-09-12T06:00:00Z\",\"endTime\":\"2026-09-12T06:03:00Z\",\"status\":\"Completed\"},"
			+ "{\"requestId\":\"r2\",\"refreshType\":\"OnDemand\",\"startTime\":\"2026-09-13T06:00:00Z\",\"endTime\":\"2026-09-13T06:01:00Z\",\"status\":\"Failed\",\"serviceExceptionJson\":\"{\\\"errorCode\\\":\\\"ModelRefresh_ShortMessage_ProcessingError\\\"}\"}]"), "2026-09-13T10:00:00");
		check(job.get("runCount").asInt() == 2 && "FAIL".equals(job.get("lastRunStatus").asText()) && "2026-09-13T06:01:00Z".equals(job.get("lastRunAt").asText())
			&& "POWERBI:Finance/models/Finance model".equals(job.get("assetKey").asText()) && "POWERBI".equals(job.get("orchestrator").asText()) && job.get("runs").get(1).get("message").asText().contains("ProcessingError"), "refresh history as a job with runs");

		System.out.println("PASS: Power BI scan interpretation (" + all.catalog.get("count") + " assets, " + all.lineage.get("count") + " edges from the recorded scan)");
	}

	public static File fixture(final String pRelative)
	{
		final String module = System.getProperty("ebx.connectors.moduleDir");
		final File[] candidates = { module == null ? null : new File(module, "src/test/resources/" + pRelative), new File("src/test/resources/" + pRelative),
			new File("ebx-connectors/src/test/resources/" + pRelative) };
		for (final File f : candidates) if (f != null && f.isFile()) return f;
		throw new IllegalStateException("fixture not found: " + pRelative + " (set -Debx.connectors.moduleDir)");
	}

	static Map<String, JsonNode> index(final JsonNode pArray, final String pField)
	{
		final Map<String, JsonNode> map = new java.util.LinkedHashMap<String, JsonNode>();
		for (final JsonNode n : pArray) map.put(n.path(pField).asText(), n);
		return map;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
