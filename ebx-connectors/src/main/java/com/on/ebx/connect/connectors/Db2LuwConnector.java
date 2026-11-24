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

/** IBM Db2 for Linux, UNIX and Windows profile backed by the shared bounded JDBC engine. */
public final class Db2LuwConnector extends JdbcConnector
{
	public static final String DRIVER = "com.ibm.db2.jcc.DB2Driver";
	public static final String DRIVER_COORDINATE = "com.ibm.db2:jcc:11.5.x";

	@Override public String getType() { return "db2-luw"; }
	@Override public String getLabel() { return "IBM Db2 LUW"; }

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getProperty("driver", null) == null) pConfig.setProperty("driver", DRIVER);
		super.configure(pConfig);
	}

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null) return common;
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		final String jndi = pConfig.getProperty("jndi", null);
		if (jndi != null)
		{
			for (final java.util.Iterator<ConfigurationValidationIssue> it = issues.iterator(); it.hasNext();)
				if ("baseUrl".equals(it.next().getPropertyName())) it.remove();
			return new ConfigurationValidationResult(issues);
		}
		if (pConfig.getBaseUrl() == null || !pConfig.getBaseUrl().startsWith("jdbc:db2://"))
			issues.add(new ConfigurationValidationIssue("baseUrl", "Db2 LUW requires jdbc:db2://host:port/database, or a JNDI DataSource"));
		if (!DRIVER.equals(pConfig.getProperty("driver", DRIVER)))
			issues.add(new ConfigurationValidationIssue("driver", "Db2 LUW requires " + DRIVER));
		bounded(pConfig, "max.rows", 1, 10000, issues);
		bounded(pConfig, "fetch.size", 1, 10000, issues);
		bounded(pConfig, "batch.size", 1, 1000, issues);
		return new ConfigurationValidationResult(issues);
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("IBM Db2 LUW connectivity with schema discovery, bounded preview, parameterized SQL, batching, and transactions. Db2 for z/OS and IBM i are outside this profile's verified scope.")
			.authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("IBM Db2 LUW via JDBC 4.2; live target acceptance required")
			.dependency(new ConnectorDependency("IBM Data Server Driver for JDBC and SQLJ", DRIVER_COORDINATE, true,
				"Supply a vendor-licensed JCC driver under WEB-INF/lib or use a governed JNDI DataSource"))
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
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(),
				operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return descriptor.build();
	}

	private static void bounded(final ConnectorConfig pConfig, final String pName, final int pMin, final int pMax,
		final List<ConfigurationValidationIssue> pIssues)
	{
		final String value = pConfig.getProperty(pName, null);
		if (value == null) return;
		try
		{
			final long parsed = Long.parseLong(value);
			if (parsed < pMin || parsed > pMax) pIssues.add(new ConfigurationValidationIssue(pName,
				"Must be between " + pMin + " and " + pMax));
		}
		catch (final NumberFormatException ignored) { }
	}

	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).build();
	}
}
