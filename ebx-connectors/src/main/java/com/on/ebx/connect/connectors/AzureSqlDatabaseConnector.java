package com.on.ebx.connect.connectors;

import java.util.List;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConnectorConfig;

/** Dedicated Azure SQL Database connector with Azure endpoint and strict TLS validation. */
public final class AzureSqlDatabaseConnector extends MicrosoftSqlConnectorBase
{
	@Override public String getType() { return "azure-sql"; }
	@Override public String getLabel() { return "Azure SQL Database"; }
	@Override public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getProperty("jdbc.hostNameInCertificate", null) == null)
			pConfig.setProperty("jdbc.hostNameInCertificate", "*.database.windows.net");
		super.configure(pConfig);
	}
	@Override void validateProduct(final ConnectorConfig pConfig, final List<ConfigurationValidationIssue> pIssues)
	{
		final String url = pConfig.getBaseUrl() == null ? "" : pConfig.getBaseUrl().toLowerCase();
		if (!url.contains(".database.windows.net")) issue(pIssues, "baseUrl", "Azure SQL host must end in .database.windows.net");
		if (falseValue(pConfig, "jdbc.encrypt")) issue(pIssues, "jdbc.encrypt", "Azure SQL requires encrypted transport");
		if ("true".equalsIgnoreCase(pConfig.getProperty("jdbc.trustServerCertificate", "false")))
			issue(pIssues, "jdbc.trustServerCertificate", "Azure SQL certificate validation cannot be bypassed");
	}
	@Override String descriptorDescription() { return "Azure SQL Database connectivity with mandatory TLS, Azure endpoint validation, discovery, batching, and transactions."; }
	@Override String apiVersion() { return "Azure SQL / Microsoft JDBC 12.x"; }
	@Override String dependencyCoordinates() { return "com.microsoft.sqlserver:mssql-jdbc:12.x.jre11"; }
}
