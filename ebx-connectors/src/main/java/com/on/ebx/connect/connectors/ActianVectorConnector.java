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

/** Canonical Actian Avalanche / Vector profile using the vendor iijdbc driver. */
public final class ActianVectorConnector extends JdbcConnector
{
	public static final String DRIVER = "com.ingres.jdbc.IngresDriver";
	public static final String DRIVER_ARCHIVE = "iijdbc.jar";

	@Override public String getType() { return "actian-vector"; }
	@Override public String getLabel() { return "Actian Avalanche / Vector"; }
	@Override public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getProperty("driver", null) == null) pConfig.setProperty("driver", DRIVER);
		super.configure(pConfig);
	}

	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null) return common;
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		final String jndi = pConfig.getProperty("jndi", null);
		if (jndi != null)
		{
			for (final Iterator<ConfigurationValidationIssue> iterator = issues.iterator(); iterator.hasNext();)
				if ("baseUrl".equals(iterator.next().getPropertyName())) iterator.remove();
		}
		else
		{
			if (pConfig.getBaseUrl() == null || !pConfig.getBaseUrl().startsWith("jdbc:ingres://"))
				issues.add(new ConfigurationValidationIssue("baseUrl",
					"Actian Avalanche and Vector JDBC URLs must start with jdbc:ingres://, or property jndi must name a DataSource"));
			if (!DRIVER.equals(pConfig.getProperty("driver", DRIVER)))
				issues.add(new ConfigurationValidationIssue("driver", "The Actian Vector profile requires " + DRIVER));
		}
		bound(pConfig, "max.rows", 10000, issues);
		bound(pConfig, "fetch.size", 10000, issues);
		bound(pConfig, "batch.size", 1000, issues);
		return new ConfigurationValidationResult(issues);
	}

	@Override public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder builder = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("Canonical JDBC profile for Actian Avalanche and Actian Vector, including deployments still described by the legacy VectorWise name. It uses the vendor iijdbc driver and bounded shared JDBC operations. Upsert is portable update-then-insert; live acceptance is required for the target service and driver.")
			.authentication(AuthenticationMethod.BASIC).capability(ConnectorCapability.TEST_CONNECTION)
			.capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.DESCRIBE_OBJECT)
			.capability(ConnectorCapability.SCHEMA_DISCOVERY).capability(ConnectorCapability.PREVIEW_RECORDS)
			.capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE)
			.capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("Actian Avalanche / Vector via iijdbc JDBC 4.2; live target acceptance required")
			.dependency(new ConnectorDependency("Actian Ingres/Vector JDBC driver", null, true,
				"Vendor-supplied optional " + DRIVER_ARCHIVE + "; download the approved Actian JDBC package, retain its license/checksum, and install it under WEB-INF/lib"))
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
			builder.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(),
				operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return builder.build();
	}

	private static void bound(final ConnectorConfig pConfig, final String pName, final int pMax,
		final List<ConfigurationValidationIssue> pIssues)
	{
		final String value = pConfig.getProperty(pName, null);
		if (value == null) return;
		try { final long parsed = Long.parseLong(value); if (parsed < 1 || parsed > pMax)
			pIssues.add(new ConfigurationValidationIssue(pName, "Must be between 1 and " + pMax)); }
		catch (final NumberFormatException ignored) { }
	}
	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).build();
	}
}
