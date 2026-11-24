package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Offline contract of the data catalog detection: a same-named dataset on another model is never the
 * catalog, an endpoint is the catalog only when it says so, and the advice given to whoever configures
 * a flow. No EBX repository, no network.
 */
public final class CatalogDetectionTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// --- local: the model location decides, never the dataset name
		check(CatalogDetection.mismatch(true, "ebx-data-catalog", "/WEB-INF/ebx/schemas/DataCatalog.xsd") == null, "the catalog's own model");
		check(CatalogDetection.mismatch(true, "ebx-data-catalog", "WEB-INF/ebx/schemas/DataCatalog.xsd") == null, "path without leading slash");
		check(CatalogDetection.mismatch(false, null, null).contains("not packaged"), "a DMA publication named DataCatalog is not the catalog");
		check(CatalogDetection.mismatch(true, "customer-governance", "/WEB-INF/ebx/schemas/DataCatalog.xsd").contains("customer-governance"),
			"another module packaging a DataCatalog.xsd is not the catalog");
		check(CatalogDetection.mismatch(true, "ebx-data-catalog", "/WEB-INF/ebx/schemas/Other.xsd").contains("Other.xsd"), "another model of our module is not the catalog");
		check("DataCatalog".equals(CatalogDetection.dataspaceKey()) && "DataCatalog".equals(CatalogDetection.datasetName()), "default keys");
		System.setProperty("ebx.catalog.dataspace", "OnDataCatalog");
		check("OnDataCatalog".equals(CatalogDetection.dataspaceKey()), "the dataspace key follows the JVM property the catalog reads");
		System.clearProperty("ebx.catalog.dataspace");
		check("'a'".equals(CatalogDetection.literal("a")) && "\"it's\"".equals(CatalogDetection.literal("it's")), "XPath literals");

		// --- the catalog's own flows: recognised by the mapping's EBX dataset, left out by default
		final MappingDefinition scan = new MappingDefinition(71, "Snowflake", "catalog", MappingDefinition.Direction.INBOUND, "Catalog scan: SNOW_DEMO", "DataCatalog", "DataCatalog",
			"/root/Asset", "", true);
		final MappingDefinition business = new MappingDefinition(98, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Company -> Snowflake COMPANIES", "Companies", "Companies",
			"/root/Companies", "", true);
		check(CatalogDetection.onCatalogKeys(scan, "DataCatalog", "DataCatalog") && !CatalogDetection.onCatalogKeys(business, "DataCatalog", "DataCatalog"),
			"a catalog scan is on the catalog dataset, a business flow is not");
		check(!CatalogDetection.onCatalogKeys(scan, "OnDataCatalog", "DataCatalog"), "the catalog keys follow the JVM properties");
		check(!CatalogDetection.isCatalogFlow(null, scan), "without a repository to verify the model, nothing is left out");
		final com.on.ebx.connect.spi.ConnectorConfig sinkConfig = new com.on.ebx.connect.spi.ConnectorConfig();
		sinkConfig.setId("CatalogOpenLineage");
		sinkConfig.setType("openlineage");
		check(OpenLineageEmitter.Settings.of(sinkConfig).excludeCatalogFlows, "catalog flows left out by default");
		sinkConfig.setProperty("exclude.catalogFlows", "false");
		check(!OpenLineageEmitter.Settings.of(sinkConfig).excludeCatalogFlows, "exclude.catalogFlows=false describes them again");

		// --- remote: only the catalog's GET makes a catalog
		final ObjectMapper json = new ObjectMapper();
		final CatalogDetection.Contract current = CatalogDetection.Contract.parse(json.readTree(
			"{\"ready\":true,\"module\":\"ebx-data-catalog\",\"contractVersion\":1,\"features\":[\"runEvents\",\"jobEvents\",\"sources\"],"
				+ "\"dataspace\":\"DataCatalog\",\"dataset\":\"DataCatalog\",\"datasetState\":\"VERIFIED\"}"));
		check(current.catalog && current.version == 1 && current.features.contains("jobEvents") && !current.refusesJobEvents(), "current catalog takes job events");
		final CatalogDetection.Contract older = CatalogDetection.Contract.parse(json.readTree("{\"ready\":true,\"accepts\":\"OpenLineage RunEvent\",\"user\":\"admin\"}"));
		check(!older.catalog && !older.refusesJobEvents(), "a catalog older than the contract is an unknown endpoint: it keeps receiving everything");
		final CatalogDetection.Contract noJobs = CatalogDetection.Contract.parse(json.readTree("{\"module\":\"ebx-data-catalog\",\"contractVersion\":1,\"features\":[\"runEvents\"]}"));
		check(noJobs.catalog && noJobs.refusesJobEvents(), "a catalog without jobEvents receives no declaration");
		final CatalogDetection.Contract foreign = CatalogDetection.Contract.parse(json.readTree(
			"{\"module\":\"ebx-data-catalog\",\"features\":[\"runEvents\",\"jobEvents\"],\"datasetState\":\"FOREIGN\"}"));
		check(foreign.refusesJobEvents(), "a catalog whose dataset is not its own receives no declaration");
		check(!CatalogDetection.Contract.parse(json.readTree("{\"module\":\"marquez\"}")).catalog && !CatalogDetection.Contract.parse(null).catalog, "Marquez, or no answer, is not the catalog");

		// --- advice
		final CatalogDetection.Local verified = new CatalogDetection.Local(CatalogDetection.State.VERIFIED, "urn:ebx:module:ebx-data-catalog:/WEB-INF/ebx/schemas/DataCatalog.xsd", null, null);
		final CatalogDetection.Local absent = new CatalogDetection.Local(CatalogDetection.State.ABSENT, null, "no dataset DataCatalog/DataCatalog", null);
		final CatalogDetection.Local customer = new CatalogDetection.Local(CatalogDetection.State.FOREIGN, "urn:ebx:publication:DataCatalog",
			"dataset DataCatalog/DataCatalog is not the data catalog: its data model is not packaged in a module", null);
		final List<String> none = Collections.emptyList();
		final List<String[]> noSources = Collections.emptyList();

		check(CatalogDetection.advice(absent, 0, none, none, "SNOW", "snowflake://acme", null, noSources).isEmpty(), "no catalog, no sink: nothing said about the catalog");
		final List<String> onCustomer = CatalogDetection.advice(customer, 0, none, none, "SNOW", "snowflake://acme", null, noSources);
		check(onCustomer.size() == 1 && onCustomer.get(0).contains("not the data catalog") && onCustomer.get(0).contains("Nothing is proposed or written"),
			"a customer's DataCatalog: explained, nothing proposed: " + onCustomer);
		check(!String.valueOf(onCustomer).contains("create one"), "no sink is proposed on a customer's dataset");
		final List<String> noSink = CatalogDetection.advice(verified, 0, none, none, "SNOW", "snowflake://acme", null, noSources);
		check(noSink.size() == 1 && noSink.get(0).contains("no active connector of type openlineage"), "catalog deployed, no sink: propose one: " + noSink);
		final List<String> wrongSink = CatalogDetection.advice(verified, 1, none, none, "SNOW", "snowflake://acme", null, noSources);
		check(wrongSink.size() == 1 && wrongSink.get(0).contains("None of the openlineage connectors"), "a sink that is not the catalog: " + wrongSink);
		final List<String> noSource = CatalogDetection.advice(verified, 1, Arrays.asList("CatalogOpenLineage"), none, "SNOW", "snowflake://acme", null, noSources);
		check(noSource.size() == 1 && noSource.get(0).contains("Create the Source") && noSource.get(0).contains("snowflake://acme"), "no Source for the namespace: " + noSource);
		final List<String[]> mismatched = Collections.singletonList(new String[] { "SNOW_DEMO", "snowflake://old" });
		final List<String> fix = CatalogDetection.advice(verified, 1, Arrays.asList("CatalogOpenLineage"), none, "SNOW", "snowflake://acme", null, mismatched);
		check(fix.size() == 1 && fix.get(0).contains("Source SNOW_DEMO (now 'snowflake://old')"), "a Source of the connector with another namespace: " + fix);
		check(CatalogDetection.advice(verified, 1, Arrays.asList("CatalogOpenLineage"), none, "SNOW", "snowflake://acme", new String[] { "SNOW_DEMO", "SNOW" },
			Collections.singletonList(new String[] { "SNOW_DEMO", "snowflake://acme" })).isEmpty(), "everything in place: no advice");
		// seen live on 2026-09-15: the namespace is held by OL_SNOWFLAKE_..., created by the catalog, while SNOW_DEMO names the connector
		final List<String> twice = CatalogDetection.advice(verified, 1, Arrays.asList("CatalogOpenLineage"), none, "Snowflake", "snowflake://ACCOUNT.snowflakecomputing.com",
			new String[] { "OL_SNOWFLAKE_ACCOUNT", null }, Collections.singletonList(new String[] { "SNOW_DEMO", "snowflake://acme.eu-west-1/ANALYTICS" }));
		check(twice.size() == 1 && twice.get(0).contains("lists this system twice") && twice.get(0).contains("Source SNOW_DEMO (now 'snowflake://acme.eu-west-1/ANALYTICS')")
			&& twice.get(0).contains("OL_SNOWFLAKE_ACCOUNT"), "a lineage-created Source shadows the connector's Source: " + twice);
		check(CatalogDetection.advice(verified, 1, Arrays.asList("CatalogOpenLineage"), none, "Snowflake", "snowflake://x", new String[] { "OL_SNOWFLAKE_X", "" }, noSources).isEmpty(),
			"a lineage-created Source and no Source of the connector: nothing to reconcile");
		final List<String> refusing = CatalogDetection.advice(verified, 1, Arrays.asList("OldCatalog"), Arrays.asList("OldCatalog"), null, null, null, noSources);
		check(refusing.size() == 1 && refusing.get(0).contains("does not take declared lineage"), "older catalog: " + refusing);
		final List<String> remote = CatalogDetection.advice(absent, 1, Arrays.asList("RemoteCatalog"), none, "SNOW", "snowflake://acme", null, noSources);
		check(remote.size() == 1 && remote.get(0).contains("another EBX instance"), "catalog on another instance: " + remote);

		// --- the catalog Source of a connector: never guess, never delete
		final List<String[]> noneByConnector = Collections.emptyList();
		CatalogDetection.SourcePlan plan = CatalogDetection.planSource("CatalogDemoH2", "h2://localhost:9092/catalogdemo", new String[] { "DEMO_H2", "CatalogDemoH2" },
			Collections.singletonList(new String[] { "DEMO_H2", "h2://localhost:9092/catalogdemo" }), "CATALOGDEMOH2", false);
		check(CatalogDetection.LINKED.equals(plan.action) && "DEMO_H2".equals(plan.sourceId) && !plan.writes(), "already linked: " + plan.message);
		plan = CatalogDetection.planSource("Databricks", "databricks://adb-0.azuredatabricks.net", new String[] { "OL_DATABRICKS_ADB", null }, noneByConnector, "DATABRICKS", false);
		check(CatalogDetection.SET_CONNECTOR.equals(plan.action) && "OL_DATABRICKS_ADB".equals(plan.sourceId) && plan.writes(), "lineage-created Source gets the connector: " + plan.message);
		plan = CatalogDetection.planSource("Snowflake", "snowflake://x", new String[] { "OL_SNOWFLAKE_X", "" },
			Collections.singletonList(new String[] { "SNOW_DEMO", "snowflake://acme" }), "SNOWFLAKE", false);
		check(CatalogDetection.MANUAL.equals(plan.action) && plan.message.contains("twice") && !plan.writes(), "duplicate: manual, nothing written: " + plan.message);
		plan = CatalogDetection.planSource("Snowflake2", "snowflake://x", new String[] { "SNOW_DEMO", "Snowflake" }, noneByConnector, "SNOWFLAKE2", false);
		check(CatalogDetection.MANUAL.equals(plan.action) && plan.message.contains("connector Snowflake"), "namespace held by another connector: " + plan.message);
		plan = CatalogDetection.planSource("Snowflake", "snowflake://acme.eu-west-1.snowflakecomputing.com", null,
			Collections.singletonList(new String[] { "SNOW_DEMO", "snowflake://acme.eu-west-1/ANALYTICS" }), "SNOWFLAKE", false);
		check(CatalogDetection.SET_NAMESPACE.equals(plan.action) && "SNOW_DEMO".equals(plan.sourceId), "the connector's Source gets the namespace: " + plan.message);
		plan = CatalogDetection.planSource("Snowflake", "snowflake://x", null, Arrays.asList(new String[] { "A", "snowflake://a" }, new String[] { "B", "snowflake://b" }), "SNOWFLAKE",
			false);
		check(CatalogDetection.MANUAL.equals(plan.action) && plan.message.contains("A, B"), "several Sources name the connector: " + plan.message);
		plan = CatalogDetection.planSource("Salesforce", "salesforce://login.salesforce.com", null, noneByConnector, "SALESFORCE", false);
		check(CatalogDetection.CREATE.equals(plan.action) && "SALESFORCE".equals(plan.sourceId) && plan.writes(), "nothing yet: create: " + plan.message);
		plan = CatalogDetection.planSource("Salesforce", "salesforce://login.salesforce.com", null, noneByConnector, "SALESFORCE", true);
		check(CatalogDetection.MANUAL.equals(plan.action) && plan.message.contains("source=<id>"), "id already used: " + plan.message);
		check("CATALOGDEMOH2".equals(CatalogDetection.sourceIdFor("CatalogDemoH2")) && "SAP_ODATA_PROD".equals(CatalogDetection.sourceIdFor("sap-odata prod"))
			&& CatalogDetection.sourceIdFor("x_______________________________________________________________long").length() == 40 && "SOURCE".equals(CatalogDetection.sourceIdFor("--")),
			"Source ids from connector ids");
		check("SNOWFLAKE".equals(CatalogDetection.platformFor("snowflake://acme")) && "H2".equals(CatalogDetection.platformFor("h2://localhost:9092/x"))
			&& "SAP".equals(CatalogDetection.platformFor("sap-odata://sandbox")) && "OTHER".equals(CatalogDetection.platformFor("rest://www.wikidata.org")),
			"platforms from namespaces");
		check(String.valueOf(CatalogDetection.advice(verified, 1, Arrays.asList("CatalogOpenLineage"), none, "SNOW", "snowflake://acme", null, noSources)).contains("connectors_catalog_source"),
			"the advice names the tool");

		System.out.println("CatalogDetectionTest: PASS");
	}

	static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
