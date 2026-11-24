package com.on.ebx.connect.connectors;

import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;

/** Deterministic contracts for the two dedicated Microsoft database offerings. */
public final class MicrosoftSqlConnectorsTest
{
	private MicrosoftSqlConnectorsTest() { }
	public static void main(final String[] pArgs)
	{
		final SqlServerConnector server = new SqlServerConnector();
		final ConnectorConfig serverConfig = config("sql-server", "jdbc:sqlserver://sql.internal:1433;databaseName=MDM");
		server.configure(serverConfig);
		secureDefaults(serverConfig);
		check(server.validateConfiguration(serverConfig).isValid(), "valid SQL Server configuration rejected");
		final ConnectorConfig insecure = config("sql-server", "jdbc:sqlserver://sql.internal:1433;databaseName=MDM");
		insecure.setProperty("jdbc.encrypt", "false"); server.configure(insecure);
		check(hasIssue(server.validateConfiguration(insecure), "jdbc.encrypt"), "SQL Server transport downgrade accepted");

		final AzureSqlDatabaseConnector azure = new AzureSqlDatabaseConnector();
		final ConnectorConfig azureConfig = config("azure-sql", "jdbc:sqlserver://tenant.database.windows.net:1433;databaseName=MDM");
		azure.configure(azureConfig); secureDefaults(azureConfig);
		check("*.database.windows.net".equals(azureConfig.getProperty("jdbc.hostNameInCertificate", null)), "Azure hostname validation default missing");
		check(azure.validateConfiguration(azureConfig).isValid(), "valid Azure SQL configuration rejected");
		final ConnectorConfig wrongCloud = config("azure-sql", "jdbc:sqlserver://sql.internal:1433;databaseName=MDM");
		azure.configure(wrongCloud);
		check(hasIssue(azure.validateConfiguration(wrongCloud), "baseUrl"), "non-Azure endpoint accepted by Azure SQL connector");

		descriptor(server.getDescriptor(), "sql-server"); descriptor(azure.getDescriptor(), "azure-sql");
		check(ConnectorRegistry.get().types().get("sql-server") == SqlServerConnector.class, "SQL Server registry entry missing");
		check(ConnectorRegistry.get().types().get("azure-sql") == AzureSqlDatabaseConnector.class, "Azure SQL registry entry missing");
		check(com.on.ebx.connect.ebx.ui.Logos.preferredFor("SQLServer") != null, "SQL Server logo is not packaged");
		check(com.on.ebx.connect.ebx.ui.Logos.preferredFor("AzureSQL") != null, "Azure SQL logo is not packaged");
		System.out.println("PASS: dedicated SQL Server and Azure SQL defaults, validation, descriptors, and registry");
	}
	private static ConnectorConfig config(final String pType, final String pUrl)
	{
		final ConnectorConfig value = new ConnectorConfig(); value.setId(pType + "-test"); value.setType(pType); value.setBaseUrl(pUrl);
		value.setAuthType(ConnectorConfig.AuthType.BASIC); value.setUsername("ebx"); value.setPassword("secret"); return value;
	}
	private static void secureDefaults(final ConnectorConfig pConfig)
	{
		check(MicrosoftSqlConnectorBase.DRIVER.equals(pConfig.getProperty("driver", null)), "Microsoft driver default missing");
		check("true".equals(pConfig.getProperty("jdbc.encrypt", null)), "encryption default missing");
		check("false".equals(pConfig.getProperty("jdbc.trustServerCertificate", null)), "certificate validation default missing");
	}
	private static void descriptor(final ConnectorDescriptor pDescriptor, final String pType)
	{
		check(pType.equals(pDescriptor.getType()), "descriptor identity mismatch");
		check(pDescriptor.getDirections().contains(ConnectorDirection.INBOUND) && pDescriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "directions missing");
		check(pDescriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY), "schema discovery missing");
		check(pDescriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "transaction capability missing");
		check(!pDescriptor.getDependencies().isEmpty(), "Microsoft JDBC dependency metadata missing");
	}
	private static boolean hasIssue(final ConfigurationValidationResult pResult, final String pField)
	{
		for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true; return false;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
