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

/** SAP HANA profile backed by the optional SAP HANA ngdbc driver. */
public final class SapHanaConnector extends JdbcConnector
{
	public static final String DRIVER = "com.sap.db.jdbc.Driver";
	public static final String NGDBC_VERSION = "2.x";

	@Override public String getType() { return "sap-hana"; }
	@Override public String getLabel() { return "SAP HANA"; }
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
		if (jndi != null) removeBaseUrlIssues(issues);
		else
		{
			if (pConfig.getBaseUrl() == null || !pConfig.getBaseUrl().startsWith("jdbc:sap://"))
				issues.add(new ConfigurationValidationIssue("baseUrl", "SAP HANA ngdbc URLs must start with jdbc:sap://, or property jndi must name a DataSource"));
			if (!DRIVER.equals(pConfig.getProperty("driver", DRIVER)))
				issues.add(new ConfigurationValidationIssue("driver", "The SAP HANA profile requires the ngdbc driver " + DRIVER));
		}
		bounds(pConfig, issues);
		return new ConfigurationValidationResult(issues);
	}

	@Override public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder builder = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("SAP HANA connectivity through the optional SAP HANA ngdbc JDBC driver with bounded discovery, preview, parameterized SQL, batching, and transactions. Upsert uses portable update-then-insert semantics; certify behavior on the target HANA and ngdbc versions.")
			.authentication(AuthenticationMethod.BASIC).capability(ConnectorCapability.TEST_CONNECTION)
			.capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.DESCRIBE_OBJECT)
			.capability(ConnectorCapability.SCHEMA_DISCOVERY).capability(ConnectorCapability.PREVIEW_RECORDS)
			.capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE)
			.capability(ConnectorCapability.TRANSACTIONS).apiVersion("SAP HANA via ngdbc " + NGDBC_VERSION + "; live target acceptance required")
			.dependency(new ConnectorDependency("SAP HANA JDBC driver", "com.sap.cloud.db.jdbc:ngdbc:" + NGDBC_VERSION, true,
				"Optional runtime dependency; install an approved ngdbc 2.x artifact under WEB-INF/lib and certify it against the target HANA revision"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET,1000,10000,null)).defaults(ConnectorDefaults.standard())
			.configurationProperty(property("jndi",ConfigurationPropertyType.STRING)).configurationProperty(property("driver",ConfigurationPropertyType.STRING))
			.configurationProperty(property("schema",ConfigurationPropertyType.STRING)).configurationProperty(property("max.rows",ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("fetch.size",ConfigurationPropertyType.INTEGER)).configurationProperty(property("batch.size",ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("quote",ConfigurationPropertyType.STRING));
		for (final Operation operation:getOperations())
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			builder.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(), operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return builder.build();
	}
	private static void removeBaseUrlIssues(final List<ConfigurationValidationIssue> pIssues){for(final Iterator<ConfigurationValidationIssue> i=pIssues.iterator();i.hasNext();)if("baseUrl".equals(i.next().getPropertyName()))i.remove();}
	private static void bounds(final ConnectorConfig pConfig, final List<ConfigurationValidationIssue> pIssues)
	{
		bound(pConfig, "max.rows", 10000, pIssues);
		bound(pConfig, "fetch.size", 10000, pIssues);
		bound(pConfig, "batch.size", 1000, pIssues);
	}
	private static void bound(final ConnectorConfig pConfig, final String pName, final int pMax, final List<ConfigurationValidationIssue> pIssues)
	{
		final String value = pConfig.getProperty(pName, null);
		if (value == null) return;
		try
		{
			final long n = Long.parseLong(value);
			if (n < 1 || n > pMax) pIssues.add(new ConfigurationValidationIssue(pName, "Must be between 1 and "+pMax));
		}
		catch (final NumberFormatException ignored) { }
	}
	private static ConfigurationPropertyDefinition property(final String pName,final ConfigurationPropertyType pType){return ConfigurationPropertyDefinition.builder(pName,pType).required(false).build();}
}
