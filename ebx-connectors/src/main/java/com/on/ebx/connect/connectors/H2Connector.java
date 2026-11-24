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

/** Dedicated H2 offering backed by the shared parameterized JDBC engine. */
public final class H2Connector extends JdbcConnector
{
	public static final String DRIVER = "org.h2.Driver";
	@Override public String getType() { return "h2"; }
	@Override public String getLabel() { return "H2 Database"; }
	@Override public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getProperty("driver", null) == null) pConfig.setProperty("driver", DRIVER);
		super.configure(pConfig);
	}
	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null || pConfig.getProperty("jndi", null) != null) return common;
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		if (pConfig.getBaseUrl() == null || !pConfig.getBaseUrl().startsWith("jdbc:h2:"))
			issues.add(new ConfigurationValidationIssue("baseUrl", "Must start with jdbc:h2:, or property jndi must name a DataSource"));
		if (!DRIVER.equals(pConfig.getProperty("driver", DRIVER)))
			issues.add(new ConfigurationValidationIssue("driver", "Dedicated H2 connections require org.h2.Driver"));
		return new ConfigurationValidationResult(issues);
	}
	@Override public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("H2 embedded or TCP database connectivity with schema discovery, safe preview, parameterized SQL, batching, and transactions.")
			.authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("H2 JDBC 2.x")
			.dependency(new ConnectorDependency("H2 JDBC driver", "com.h2database:h2:2.x", true, "Packaged by EBX-Connectors under WEB-INF/lib"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, 0, null)).defaults(ConnectorDefaults.standard())
			.configurationProperty(property("jndi", ConfigurationPropertyType.STRING, false)).configurationProperty(property("driver", ConfigurationPropertyType.STRING, false))
			.configurationProperty(property("schema", ConfigurationPropertyType.STRING, false)).configurationProperty(property("max.rows", ConfigurationPropertyType.INTEGER, false))
			.configurationProperty(property("max.rows.hard", ConfigurationPropertyType.INTEGER, false))
			.configurationProperty(property("fetch.size", ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("batch.size", ConfigurationPropertyType.INTEGER, false))
			.configurationProperty(property("quote", ConfigurationPropertyType.STRING, false));
		for (final Operation operation : getOperations())
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(), operation.getDescription(),
				Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return descriptor.build();
	}
	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType, final boolean pRequired)
	{ return ConfigurationPropertyDefinition.builder(pName, pType).required(pRequired).build(); }
}
