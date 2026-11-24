package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDefaults;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** Shared Microsoft JDBC behavior; concrete products retain distinct catalog and policy contracts. */
abstract class MicrosoftSqlConnectorBase extends JdbcConnector
{
	static final String DRIVER = "com.microsoft.sqlserver.jdbc.SQLServerDriver";

	@Override public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null)
		{
			if (pConfig.getProperty("driver", null) == null) pConfig.setProperty("driver", DRIVER);
			if (pConfig.getProperty("jdbc.encrypt", null) == null) pConfig.setProperty("jdbc.encrypt", "true");
			if (pConfig.getProperty("jdbc.trustServerCertificate", null) == null) pConfig.setProperty("jdbc.trustServerCertificate", "false");
		}
		super.configure(pConfig);
	}

	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null || pConfig.getProperty("jndi", null) != null) return common;
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		final String url = pConfig.getBaseUrl();
		if (url == null || !url.toLowerCase().startsWith("jdbc:sqlserver://"))
			issues.add(new ConfigurationValidationIssue("baseUrl", "Must start with jdbc:sqlserver://, or property jndi must name a DataSource"));
		validateProduct(pConfig, issues);
		return new ConfigurationValidationResult(issues);
	}

	abstract void validateProduct(ConnectorConfig pConfig, List<ConfigurationValidationIssue> pIssues);
	abstract String descriptorDescription();
	abstract String apiVersion();
	abstract String dependencyCoordinates();

	@Override public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description(descriptorDescription()).authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion(apiVersion())
			.dependency(new ConnectorDependency("Microsoft JDBC Driver for SQL Server", dependencyCoordinates(), true,
				"Packaged by EBX-Connectors under WEB-INF/lib"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, 10000, null))
			.defaults(ConnectorDefaults.standard())
			.configurationProperty(property("jndi", ConfigurationPropertyType.STRING))
			.configurationProperty(property("driver", ConfigurationPropertyType.STRING))
			.configurationProperty(property("schema", ConfigurationPropertyType.STRING))
			.configurationProperty(property("jdbc.encrypt", ConfigurationPropertyType.BOOLEAN))
			.configurationProperty(property("jdbc.trustServerCertificate", ConfigurationPropertyType.BOOLEAN))
			.configurationProperty(property("jdbc.hostNameInCertificate", ConfigurationPropertyType.STRING))
			.configurationProperty(property("max.rows", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("fetch.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("batch.size", ConfigurationPropertyType.INTEGER));
		for (final Operation operation : getOperations())
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(), operation.getDescription(),
				Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return descriptor.build();
	}
	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType)
	{ return ConfigurationPropertyDefinition.builder(pName, pType).required(false).build(); }
	static boolean falseValue(final ConnectorConfig pConfig, final String pKey)
	{ return "false".equalsIgnoreCase(pConfig.getProperty(pKey, "")); }
	static void issue(final List<ConfigurationValidationIssue> pIssues, final String pField, final String pMessage)
	{ pIssues.add(new ConfigurationValidationIssue(pField, pMessage)); }
}
