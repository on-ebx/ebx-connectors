package com.on.ebx.connect.core;

import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * Offline contract of the name given to the connector's side of a mapping: the object an input names,
 * else the table a query reads, else the operation (an API endpoint). Seen on 2026-09-15: the Snowflake
 * and Databricks feeds, which only carry a {@code sql} input, appeared in the catalog as a table named
 * {@code query}. No EBX repository, no network.
 */
public final class OpenLineageDatasetNameTest
{
	public static void main(final String[] pArgs)
	{
		final ConnectorConfig snowflake = new ConnectorConfig();
		snowflake.setId("Snowflake");
		snowflake.setType("snowflake");
		snowflake.setBaseUrl("https://acme.eu-west-1.snowflakecomputing.com");
		snowflake.setProperty("schema", "PUBLIC");

		final MappingDefinition feed = new MappingDefinition(97, "Snowflake", "query", MappingDefinition.Direction.INBOUND, "Snowflake CUSTOMERS -> companies", "Companies",
			"Companies", "/root/Companies", "", true);
		feed.addInput("sql", "SELECT LEI, NAME FROM CUSTOMERS WHERE LEI IS NOT NULL");
		final String[] named = OpenLineageEmitter.datasetOf(snowflake, feed);
		check("snowflake://acme.eu-west-1.snowflakecomputing.com".equals(named[0]) && "PUBLIC.CUSTOMERS".equals(named[1]),
			"a sql feed names the table it reads, with the connector's schema: " + named[0] + " " + named[1]);

		check("MDM.PUBLIC.CUSTOMERS".equals(sql("select * from \"MDM\".\"PUBLIC\".\"CUSTOMERS\" c")), "quoted three-part name: " + sql("select * from \"MDM\".\"PUBLIC\".\"CUSTOMERS\" c"));
		check("sales.orders".equals(sql("SELECT o.id\nFROM `sales`.`orders` o JOIN customers c ON 1=1")), "backticks, first FROM wins");
		check("dbo.Customer".equals(sql("select top 10 * from [dbo].[Customer]")), "brackets");
		check("customers".equals(sql("select * from (select * from customers) t")), "a sub-query is read through");
		check(sql("select * from ${param.table}") == null, "a templated table is not guessed");
		check(sql("select 1") == null, "no FROM, no table");
		final Map<String, String> none = new LinkedHashMap<String, String>();
		none.put("name", "ACME");
		check(OpenLineageEmitter.tableOfSql(none) == null, "no query input");

		final ConnectorConfig wikidata = new ConnectorConfig();
		wikidata.setId("Wikidata");
		wikidata.setType("rest");
		wikidata.setBaseUrl("https://www.wikidata.org/w/api.php");
		final MappingDefinition search = new MappingDefinition(138, "Wikidata", "search", MappingDefinition.Direction.INBOUND, "Wikidata search by name -> wikidataId", "Companies",
			"Companies", "/root/Companies", "", true);
		search.addInput("name", "${./Identification/Name}");
		check("search".equals(OpenLineageEmitter.datasetOf(wikidata, search)[1]), "an API call without object keeps the operation (the endpoint)");

		final MappingDefinition table = new MappingDefinition(98, "Snowflake", "merge", MappingDefinition.Direction.OUTBOUND, "Company -> Snowflake COMPANIES", "Companies",
			"Companies", "/root/Companies", "", true);
		table.addInput("table", "COMPANIES");
		table.addInput("sql", "SELECT * FROM OTHER");
		check("PUBLIC.COMPANIES".equals(OpenLineageEmitter.datasetOf(snowflake, table)[1]), "an input naming the object wins over the query");

		System.out.println("OpenLineageDatasetNameTest: PASS");
	}

	static String sql(final String pSql)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("sql", pSql);
		return OpenLineageEmitter.tableOfSql(inputs);
	}

	static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
