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

/** Dedicated Vertica profile backed by the shared bounded JDBC engine. */
public final class VerticaConnector extends JdbcConnector
{
	public static final String DRIVER = "com.vertica.jdbc.Driver";

	@Override public String getType() { return "vertica"; }
	@Override public String getLabel() { return "Vertica Database"; }

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
		final String jndi = trim(pConfig.getProperty("jndi", null));
		if (jndi != null)
		{
			for (final java.util.Iterator<ConfigurationValidationIssue> iterator = issues.iterator(); iterator.hasNext();)
				if ("baseUrl".equals(iterator.next().getPropertyName())) iterator.remove();
		}
		else
		{
			final String url = pConfig.getBaseUrl();
			if (url == null || !url.startsWith("jdbc:vertica://"))
				issues.add(new ConfigurationValidationIssue("baseUrl",
					"Must start with jdbc:vertica://, or property jndi must name a DataSource"));
			if (!DRIVER.equals(pConfig.getProperty("driver", DRIVER)))
				issues.add(new ConfigurationValidationIssue("driver", "Dedicated Vertica connections require " + DRIVER));
		}
		validateBound(pConfig, "max.rows", 1, 10000, issues);
		validateBound(pConfig, "fetch.size", 1, 10000, issues);
		validateBound(pConfig, "batch.size", 1, 1000, issues);
		return new ConfigurationValidationResult(issues);
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("Vertica database connectivity with schema discovery, bounded preview, parameterized SQL, JDBC batching, and transactions. Upsert uses update-then-insert; native COPY is not implied.")
			.authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("Vertica JDBC 4.x")
			.dependency(new ConnectorDependency("Vertica JDBC driver", "com.vertica.jdbc:vertica-jdbc:24.x", true,
				"Install a Vertica JDBC JAR supported by the target server under WEB-INF/lib; EBX-Connectors does not redistribute it"))
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

	private static void validateBound(final ConnectorConfig pConfig, final String pName, final int pMinimum,
		final int pMaximum, final List<ConfigurationValidationIssue> pIssues)
	{
		final String value = trim(pConfig.getProperty(pName, null));
		if (value == null) return;
		try
		{
			final long parsed = Long.parseLong(value);
			if (parsed < pMinimum || parsed > pMaximum)
				pIssues.add(new ConfigurationValidationIssue(pName, "Must be between " + pMinimum + " and " + pMaximum));
		}
		catch (final NumberFormatException ex)
		{
			pIssues.add(new ConfigurationValidationIssue(pName, "Must be an integer between " + pMinimum + " and " + pMaximum));
		}
	}

	private static String trim(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? null : pValue.trim();
	}

	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).build();
	}
}
