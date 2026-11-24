package com.on.ebx.connect.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Prevents registered wave connector types from being omitted from the data-backed UI catalog. */
public final class WaveConnectorCatalogSeedTest
{
	private WaveConnectorCatalogSeedTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final Path module = Paths.get(System.getProperty("ebx.connectors.moduleDir", "."));
		final String installer = new String(Files.readAllBytes(module.resolve(
			"src/main/java/com/on/ebx/connect/ebx/ConnectorsInstaller.java")), StandardCharsets.UTF_8);
		final String[][] expected = {
			{ "Db2-LUW", "db2-luw" }, { "IRIS", "iris" }, { "SapAse", "sap-ase" }, { "SapHana", "sap-hana" },
			{ "Teradata", "teradata" }, { "Vertica", "vertica" }, { "ActianVector", "actian-vector" },
			{ "ActianIngres", "actian-ingres" }, { "MonetDB", "monetdb" }, { "AmazonSQS", "amazon-sqs" },
			{ "AmazonSNS", "amazon-sns" }, { "GooglePubSub", "google-pubsub" }, { "FTP-FTPS", "ftp" },
			{ "AzureQueueStorage", "azure-queue-storage" }, { "Azure-Table", "azure-table" },
			{ "GoogleSheets", "google-sheets" }, { "Elasticsearch", "elasticsearch" }, { "Cassandra", "cassandra" },
			{ "MongoDB", "mongodb" }, { "Couchbase", "couchbase" }, { "CMIS", "cmis" },
			{ "VeevaVault", "veeva-vault" }, { "Alation", "alation" }, { "MicrosoftPurview", "microsoft-purview" },
			{ "HadoopWebHDFS", "hadoop-webhdfs" }, { "SparkSQL", "spark-sql" }, { "DominoREST", "domino-rest" },
			{ "ClouderaHive", "cloudera-hive" }, { "ClouderaImpala", "cloudera-impala" },
			{ "Firebird", "firebird" }, { "Greenplum", "greenplum" }, { "SingleStore", "singlestore" },
			{ "GenericREST", "rest" }, { "OpenLineage", "openlineage" }, { "GoogleDrive", "google-drive" },
			{ "OneDrive", "onedrive" }, { "Redshift", "redshift" }, { "Documents", "documents" }
		};
		check(installer.contains("seedWaveCatalog(pSession, dataset, pLog);"), "startup does not invoke additive wave seeding");
		check(installer.indexOf("seedWaveCatalog(pSession, dataset, pLog);") < installer.indexOf("migrate(pSession, dataset, pLog)"),
			"wave records must be installed before catalog migration");
		for (final String[] value : expected)
		{
			check(installer.contains("\"" + value[0] + "\", \"") && installer.contains("\"" + value[1] + "\""),
				"missing additive catalog seed for " + value[0] + " / " + value[1]);
		}
		check(installer.contains("lookupAdaptationByPrimaryKey") && installer.contains("return false;"),
			"catalog seed must preserve existing customer connector rows");
		System.out.println("PASS: 38 wave connectors are additively seeded into the data-backed catalog");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
