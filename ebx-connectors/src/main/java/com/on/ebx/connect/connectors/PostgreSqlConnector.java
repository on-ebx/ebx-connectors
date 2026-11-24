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

/**
 * Dedicated PostgreSQL connector backed by the shared, parameterized JDBC execution engine.
 * It has its own catalog identity and PostgreSQL-specific validation/default metadata while
 * retaining JDBC discovery, preview, transactions, batching, and read/write operations.
 */
public class PostgreSqlConnector extends JdbcConnector
{
	@Override
	public String getType()
	{
		return "postgresql";
	}

	@Override
	public String getLabel()
	{
		return "PostgreSQL";
	}

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getProperty("driver", null) == null)
		{
			pConfig.setProperty("driver", "org.postgresql.Driver");
		}
		super.configure(pConfig);
	}

	@Override
	public com.on.ebx.connect.spi.ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final com.on.ebx.connect.spi.ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null)
		{
			return common;
		}
		final java.util.List<com.on.ebx.connect.spi.ConfigurationValidationIssue> issues =
			new java.util.ArrayList<com.on.ebx.connect.spi.ConfigurationValidationIssue>(common.getIssues());
		final String jndi = pConfig.getProperty("jndi", null);
		if (jndi != null)
		{
			for (final java.util.Iterator<com.on.ebx.connect.spi.ConfigurationValidationIssue> iterator = issues.iterator(); iterator.hasNext();)
			{
				if ("baseUrl".equals(iterator.next().getPropertyName())) iterator.remove();
			}
		}
		final String url = pConfig.getBaseUrl();
		if (jndi == null && (url == null || !url.startsWith("jdbc:postgresql:")))
		{
			issues.add(new com.on.ebx.connect.spi.ConfigurationValidationIssue("baseUrl",
				"Must start with jdbc:postgresql:, or property jndi must name a DataSource"));
		}
		final String driver = pConfig.getProperty("driver", null);
		if (jndi == null && driver != null && !"org.postgresql.Driver".equals(driver))
		{
			issues.add(new com.on.ebx.connect.spi.ConfigurationValidationIssue("driver",
				"The dedicated PostgreSQL connector requires org.postgresql.Driver"));
		}
		validateBoundedInteger(pConfig, "max.rows", 1, 10000, issues);
		// The per-call "max" input honours this ceiling instead of a fixed 10,000 (JdbcConnector.maxRows);
		// default Integer.MAX_VALUE (effectively unbounded) when the property is absent.
		validateBoundedInteger(pConfig, "max.rows.hard", 1, Integer.MAX_VALUE, issues);
		validateBoundedInteger(pConfig, "fetch.size", 1, 10000, issues);
		validateBoundedInteger(pConfig, "batch.size", 1, 1000, issues);
		return new com.on.ebx.connect.spi.ConfigurationValidationResult(issues);
	}

	private static void validateBoundedInteger(final ConnectorConfig pConfig, final String pName, final int pMin,
		final int pMax, final java.util.List<com.on.ebx.connect.spi.ConfigurationValidationIssue> pIssues)
	{
		final String value = pConfig.getProperty(pName, null);
		if (value == null) return;
		try
		{
			final long parsed = Long.parseLong(value);
			if (parsed < pMin || parsed > pMax) pIssues.add(new com.on.ebx.connect.spi.ConfigurationValidationIssue(
				pName, "Must be between " + pMin + " and " + pMax));
		}
		catch (final NumberFormatException ignored)
		{
			// Descriptor validation already reports the invalid integer.
		}
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("PostgreSQL database connectivity with schema discovery, safe preview, parameterized SQL, batching, and transactions.")
			.authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION)
			.capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT)
			.capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS)
			.capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE)
			.capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("PostgreSQL JDBC 4.2")
			.dependency(new ConnectorDependency("PostgreSQL JDBC driver", "org.postgresql:postgresql:42.7.x", true,
				"Packaged by EBX-Connectors under WEB-INF/lib"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, 0, null))
			.defaults(ConnectorDefaults.standard())
			.configurationProperty(property("jndi", ConfigurationPropertyType.STRING, false))
			.configurationProperty(property("driver", ConfigurationPropertyType.STRING, false))
			.configurationProperty(property("schema", ConfigurationPropertyType.STRING, false))
			.configurationProperty(property("max.rows", ConfigurationPropertyType.INTEGER, false))
			.configurationProperty(property("max.rows.hard", ConfigurationPropertyType.INTEGER, false))
			.configurationProperty(property("fetch.size", ConfigurationPropertyType.INTEGER, false))
			.configurationProperty(property("batch.size", ConfigurationPropertyType.INTEGER, false))
			.configurationProperty(property("quote", ConfigurationPropertyType.STRING, false));
		for (final Operation operation : getOperations())
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(),
				operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return descriptor.build();
	}

	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType,
		final boolean pRequired)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(pRequired).build();
	}
}
