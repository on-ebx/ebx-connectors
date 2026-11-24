package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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

/** Firebird profile backed by Jaybird and the shared bounded JDBC engine. */
public final class FirebirdConnector extends JdbcConnector
{
	public static final String DRIVER = "org.firebirdsql.jdbc.FBDriver";
	public static final String JAYBIRD_VERSION = "5.0.x.java8";

	@Override public String getType() { return "firebird"; }
	@Override public String getLabel() { return "Firebird"; }

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
			for (final Iterator<ConfigurationValidationIssue> iterator = issues.iterator(); iterator.hasNext();)
			{
				if ("baseUrl".equals(iterator.next().getPropertyName())) iterator.remove();
			}
			return new ConfigurationValidationResult(issues);
		}
		final String url = pConfig.getBaseUrl();
		if (url == null || !url.startsWith("jdbc:firebirdsql://"))
			issues.add(new ConfigurationValidationIssue("baseUrl",
				"Remote Firebird connections must start with jdbc:firebirdsql://, or property jndi must name a DataSource"));
		if (!DRIVER.equals(pConfig.getProperty("driver", DRIVER)))
			issues.add(new ConfigurationValidationIssue("driver", "The Firebird profile requires the Jaybird driver " + DRIVER));
		validateBound(pConfig, "max.rows", 1, 10000, issues);
		validateBound(pConfig, "fetch.size", 1, 10000, issues);
		validateBound(pConfig, "batch.size", 1, 1000, issues);
		return new ConfigurationValidationResult(issues);
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("Firebird connectivity through the optional Jaybird JDBC driver with bounded discovery, preview, parameterized SQL, batching, and transactions. The shared upsert operation uses portable update-then-insert semantics; live acceptance is required for the target Firebird and Jaybird versions.")
			.authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("Firebird via Jaybird 5.x; live target acceptance required")
			.dependency(new ConnectorDependency("Jaybird JDBC driver", "org.firebirdsql.jdbc:jaybird:" + JAYBIRD_VERSION,
				true, "Optional runtime dependency; package the Java 8-compatible Jaybird artifact under WEB-INF/lib"))
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

	private static void validateBound(final ConnectorConfig pConfig, final String pName, final int pMin, final int pMax,
		final List<ConfigurationValidationIssue> pIssues)
	{
		final String value = pConfig.getProperty(pName, null);
		if (value == null) return;
		try
		{
			final long parsed = Long.parseLong(value);
			if (parsed < pMin || parsed > pMax)
				pIssues.add(new ConfigurationValidationIssue(pName, "Must be between " + pMin + " and " + pMax));
		}
		catch (final NumberFormatException ignored)
		{
			// Descriptor validation reports invalid integers.
		}
	}

	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).build();
	}
}
