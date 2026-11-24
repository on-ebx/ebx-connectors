package com.on.ebx.connect.connectors;

import java.util.List;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConnectorConfig;

/** Dedicated on-premises Microsoft SQL Server connector with encrypted JDBC defaults. */
public final class SqlServerConnector extends MicrosoftSqlConnectorBase
{
    @Override protected void enrichCatalogTable(java.sql.Connection db,String schema,String table,
        com.fasterxml.jackson.databind.node.ObjectNode item,java.util.Map<String,String> inputs)throws java.sql.SQLException {
        SqlServerMetadata.read(db,schema,table,item,"true".equalsIgnoreCase(inputs.get("usageStatistics")));
    }
	@Override public String getType() { return "sql-server"; }
	@Override public String getLabel() { return "Microsoft SQL Server"; }
	@Override void validateProduct(final ConnectorConfig pConfig, final List<ConfigurationValidationIssue> pIssues)
	{
		if (falseValue(pConfig, "jdbc.encrypt")) issue(pIssues, "jdbc.encrypt", "Encrypted transport is required; use a trusted server certificate");
		if ("true".equalsIgnoreCase(pConfig.getProperty("jdbc.trustServerCertificate", "false")))
			issue(pIssues, "jdbc.trustServerCertificate", "Certificate trust bypass is not permitted");
	}
	@Override String descriptorDescription() { return "Microsoft SQL Server connectivity with encrypted transport, schema discovery, parameterized SQL, batching, and transactions."; }
	@Override String apiVersion() { return "Microsoft SQL Server JDBC 12.x"; }
	@Override String dependencyCoordinates() { return "com.microsoft.sqlserver:mssql-jdbc:12.x.jre11"; }
}
