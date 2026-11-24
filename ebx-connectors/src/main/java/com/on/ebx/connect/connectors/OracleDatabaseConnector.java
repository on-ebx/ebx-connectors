package com.on.ebx.connect.connectors;

import java.util.Collections;

import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDefaults;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** Dedicated Oracle Database offering backed by the shared parameterized JDBC engine. */
public final class OracleDatabaseConnector extends JdbcConnector
{
	@Override public String getType() { return "oracle-database"; }
	@Override public String getLabel() { return "Oracle Database"; }

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getProperty("driver", null) == null)
			pConfig.setProperty("driver", "oracle.jdbc.OracleDriver");
		super.configure(pConfig);
	}

	@Override
	public com.on.ebx.connect.spi.ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final com.on.ebx.connect.spi.ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null || pConfig.getProperty("jndi", null) != null) return common;
		final String url = pConfig.getBaseUrl();
		if (url == null || !url.startsWith("jdbc:oracle:"))
		{
			final java.util.List<com.on.ebx.connect.spi.ConfigurationValidationIssue> issues =
				new java.util.ArrayList<com.on.ebx.connect.spi.ConfigurationValidationIssue>(common.getIssues());
			issues.add(new com.on.ebx.connect.spi.ConfigurationValidationIssue("baseUrl",
				"Must start with jdbc:oracle:, or property jndi must name a DataSource"));
			return new com.on.ebx.connect.spi.ConfigurationValidationResult(issues);
		}
		return common;
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("Oracle Database connectivity with schema discovery, safe preview, parameterized SQL, batching, and transactions.")
			.authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("Oracle JDBC 4.3")
			.dependency(new ConnectorDependency("Oracle JDBC driver", "com.oracle.database.jdbc:ojdbc8:23.9.0.25.07", true,
				"Packaged by EBX-Connectors under WEB-INF/lib"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, 10000, null))
			.defaults(ConnectorDefaults.standard())
			.configurationProperty(property("jndi", ConfigurationPropertyType.STRING))
			.configurationProperty(property("driver", ConfigurationPropertyType.STRING))
			.configurationProperty(property("schema", ConfigurationPropertyType.STRING))
			.configurationProperty(property("max.rows", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("fetch.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("batch.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("quote", ConfigurationPropertyType.STRING));
		for (final Operation operation : getOperations())
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(),
				operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return descriptor.build();
	}
	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).build();
	}
}
