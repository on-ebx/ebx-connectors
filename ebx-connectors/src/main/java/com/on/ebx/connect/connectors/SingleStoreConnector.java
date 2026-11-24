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

/** SingleStore profile using the packaged MySQL Connector/J compatibility protocol. */
public final class SingleStoreConnector extends JdbcConnector
{
	public static final String DRIVER = "com.mysql.cj.jdbc.Driver";
	public static final String CONNECTOR_J_VERSION = "26.7.0";

	@Override public String getType() { return "singlestore"; }
	@Override public String getLabel() { return "SingleStore"; }
	@Override protected boolean usesCatalogMetadata() { return true; }

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null)
		{
			defaultProperty(pConfig, "driver", DRIVER);
			defaultProperty(pConfig, "jdbc.sslMode", "VERIFY_IDENTITY");
			defaultProperty(pConfig, "jdbc.allowPublicKeyRetrieval", "false");
			defaultProperty(pConfig, "jdbc.useServerPrepStmts", "true");
			defaultProperty(pConfig, "jdbc.serverTimezone", "UTC");
		}
		super.configure(pConfig);
	}

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null || pConfig.getProperty("jndi", null) != null) return common;
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		if (pConfig.getBaseUrl() == null || !pConfig.getBaseUrl().startsWith("jdbc:mysql://"))
			issues.add(new ConfigurationValidationIssue("baseUrl",
				"SingleStore through Connector/J requires jdbc:mysql://, or property jndi must name a DataSource"));
		if (!DRIVER.equals(pConfig.getProperty("driver", DRIVER)))
			issues.add(new ConfigurationValidationIssue("driver", "SingleStore Connector/J compatibility requires " + DRIVER));
		final String sslMode = pConfig.getProperty("jdbc.sslMode", "VERIFY_IDENTITY");
		if (!("VERIFY_IDENTITY".equalsIgnoreCase(sslMode) || "VERIFY_CA".equalsIgnoreCase(sslMode)))
			issues.add(new ConfigurationValidationIssue("jdbc.sslMode",
				"Use VERIFY_IDENTITY (recommended) or VERIFY_CA for encrypted, verified connections"));
		if (Boolean.parseBoolean(pConfig.getProperty("jdbc.allowPublicKeyRetrieval", "false")))
			issues.add(new ConfigurationValidationIssue("jdbc.allowPublicKeyRetrieval",
				"Public-key retrieval must remain disabled; configure TLS and a trusted server certificate"));
		return new ConfigurationValidationResult(issues);
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("SingleStore connectivity through MySQL Connector/J compatibility with verified TLS, catalog discovery, safe preview, parameterized SQL, batching, and transactions.")
			.authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("SingleStore MySQL protocol / Connector/J " + CONNECTOR_J_VERSION)
			.dependency(new ConnectorDependency("MySQL Connector/J", "com.mysql:mysql-connector-j:" + CONNECTOR_J_VERSION,
				true, "Packaged by EBX-Connectors; SingleStore is accessed through its MySQL wire-protocol compatibility"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, 10000, null))
			.defaults(ConnectorDefaults.standard())
			.configurationProperty(property("jndi", ConfigurationPropertyType.STRING))
			.configurationProperty(property("driver", ConfigurationPropertyType.STRING))
			.configurationProperty(property("schema", ConfigurationPropertyType.STRING))
			.configurationProperty(property("jdbc.sslMode", ConfigurationPropertyType.ENUMERATION,
				"VERIFY_IDENTITY", "VERIFY_IDENTITY", "VERIFY_CA"))
			.configurationProperty(property("jdbc.allowPublicKeyRetrieval", ConfigurationPropertyType.BOOLEAN))
			.configurationProperty(property("jdbc.useServerPrepStmts", ConfigurationPropertyType.BOOLEAN))
			.configurationProperty(property("jdbc.serverTimezone", ConfigurationPropertyType.STRING))
			.configurationProperty(property("max.rows", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("fetch.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("batch.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("quote", ConfigurationPropertyType.STRING));
		for (final Operation operation : getOperations())
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(),
				operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return descriptor.build();
	}

	private static void defaultProperty(final ConnectorConfig pConfig, final String pName, final String pValue)
	{
		if (pConfig.getProperty(pName, null) == null) pConfig.setProperty(pName, pValue);
	}

	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).build();
	}

	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType,
		final String pDefault, final String... pValues)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).defaultValue(pDefault)
			.enumerationValues(pValues).build();
	}
}
