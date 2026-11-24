package com.on.ebx.connect.connectors;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;

/** Account-free contract checks for the certified Greenplum JDBC profile. */
public final class GreenplumConnectorTest
{
	private GreenplumConnectorTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		final GreenplumConnector connector = new GreenplumConnector();
		check("greenplum".equals(connector.getType()), "Unexpected Greenplum type");
		check("Greenplum".equals(connector.getLabel()), "Unexpected Greenplum label");

		final ConnectorConfig valid = configuration("jdbc:postgresql://greenplum.example:5432/mdm");
		connector.configure(valid);
		check("org.postgresql.Driver".equals(valid.getProperty("driver", null)),
			"PostgreSQL-family driver default was not applied");
		check(connector.validateConfiguration(valid).isValid(), "A valid Greenplum configuration was rejected");

		final ConnectorConfig wrongUrl = configuration("jdbc:mysql://db.example:3306/mdm");
		connector.configure(wrongUrl);
		check(hasIssue(connector.validateConfiguration(wrongUrl), "baseUrl"),
			"A non-PostgreSQL-family JDBC URL was accepted");

		final ConnectorConfig wrongDriver = configuration("jdbc:postgresql://greenplum.example:5432/mdm");
		wrongDriver.setProperty("driver", "com.example.WrongDriver");
		check(hasIssue(connector.validateConfiguration(wrongDriver), "driver"),
			"A non-PostgreSQL driver was accepted");

		final ConnectorConfig unsafeBounds = configuration("jdbc:postgresql://greenplum.example:5432/mdm");
		unsafeBounds.setProperty("max.rows", "10001");
		unsafeBounds.setProperty("batch.size", "0");
		final ConfigurationValidationResult bounds = connector.validateConfiguration(unsafeBounds);
		check(hasIssue(bounds, "max.rows") && hasIssue(bounds, "batch.size"),
			"Unsafe execution bounds were accepted");

		final ConnectorConfig jndi = configuration(null);
		jndi.setProperty("jndi", "java:comp/env/jdbc/Greenplum");
		check(connector.validateConfiguration(jndi).isValid(), "A Greenplum JNDI DataSource was rejected");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND)
			&& descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "Connector directions are incomplete");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)
			&& descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "Connector capabilities are incomplete");
		check(descriptor.getDescription().contains("update-then-insert"),
			"Portable upsert semantics are not disclosed");
		boolean acceptanceBoundary = false;
		for (final String apiVersion : descriptor.getApiVersions())
		{
			if (apiVersion.contains("live target acceptance required")) acceptanceBoundary = true;
		}
		check(acceptanceBoundary, "Live-acceptance boundary is not disclosed");
		boolean postgresDriver = false;
		for (final ConnectorDependency dependency : descriptor.getDependencies())
		{
			if ("org.postgresql:postgresql:42.7.x".equals(dependency.getMinimumVersion())) postgresDriver = true;
		}
		check(postgresDriver, "PostgreSQL JDBC dependency is not declared");

		System.out.println("PASS: Greenplum PostgreSQL-family defaults, validation, descriptor, and acceptance boundary");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("greenplum-test");
		config.setType("greenplum");
		config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC);
		config.setUsername("ebx");
		config.setPassword("secret");
		return config;
	}

	private static boolean hasIssue(final ConfigurationValidationResult pResult, final String pField)
	{
		for (final ConfigurationValidationIssue issue : pResult.getIssues())
		{
			if (pField.equals(issue.getPropertyName())) return true;
		}
		return false;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
