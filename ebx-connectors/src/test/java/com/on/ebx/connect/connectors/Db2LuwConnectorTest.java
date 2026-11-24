package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.Operation;

/** Account-free contract checks for the IBM Db2 LUW JDBC profile. */
public final class Db2LuwConnectorTest
{
	private Db2LuwConnectorTest() { }

	public static void main(final String[] pArgs)
	{
		final Db2LuwConnector connector = new Db2LuwConnector();
		check("db2-luw".equals(connector.getType()) && "IBM Db2 LUW".equals(connector.getLabel()), "identity");
		final ConnectorConfig valid = configuration("jdbc:db2://db2.example:50000/MDM");
		connector.configure(valid);
		check(Db2LuwConnector.DRIVER.equals(valid.getProperty("driver", null)), "JCC driver default");
		check(connector.validateConfiguration(valid).isValid(), "valid Db2 LUW URL rejected");

		final ConnectorConfig wrongUrl = configuration("jdbc:db2:MDM");
		connector.configure(wrongUrl);
		check(has(connector.validateConfiguration(wrongUrl), "baseUrl"), "non-network or ambiguous Db2 URL accepted");
		final ConnectorConfig foreignUrl = configuration("jdbc:postgresql://db.example/mdm");
		connector.configure(foreignUrl);
		check(has(connector.validateConfiguration(foreignUrl), "baseUrl"), "foreign URL accepted");
		final ConnectorConfig wrongDriver = configuration("jdbc:db2://db2.example:50000/MDM");
		wrongDriver.setProperty("driver", "example.Driver");
		check(has(connector.validateConfiguration(wrongDriver), "driver"), "foreign driver accepted");

		final ConnectorConfig unsafe = configuration("jdbc:db2://db2.example:50000/MDM");
		unsafe.setProperty("max.rows", "10001"); unsafe.setProperty("fetch.size", "0"); unsafe.setProperty("batch.size", "1001");
		final ConfigurationValidationResult bounds = connector.validateConfiguration(unsafe);
		check(has(bounds, "max.rows") && has(bounds, "fetch.size") && has(bounds, "batch.size"), "unsafe bounds accepted");
		final ConnectorConfig jndi = configuration(null);
		jndi.setProperty("jndi", "java:comp/env/jdbc/Db2Luw"); jndi.setProperty("driver", "container.Managed");
		check(connector.validateConfiguration(jndi).isValid(), "JNDI DataSource rejected");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("DATABASE".equals(descriptor.getCategory()), "category");
		check(descriptor.getDirections().containsAll(Arrays.asList(ConnectorDirection.INBOUND, ConnectorDirection.OUTBOUND)), "directions");
		check(descriptor.getCapabilities().containsAll(Arrays.asList(ConnectorCapability.TEST_CONNECTION,
			ConnectorCapability.DISCOVER_OBJECTS, ConnectorCapability.DESCRIBE_OBJECT, ConnectorCapability.SCHEMA_DISCOVERY,
			ConnectorCapability.PREVIEW_RECORDS, ConnectorCapability.BULK_READ, ConnectorCapability.BULK_WRITE,
			ConnectorCapability.TRANSACTIONS)), "capabilities");
		final Set<String> operations = new HashSet<String>();
		for (final Operation operation : connector.getOperations()) operations.add(operation.getName());
		check(operations.equals(new HashSet<String>(Arrays.asList("query", "get", "insert", "update", "upsert", "procedure", "execute",
			"catalog", "profile", "sample"))), "operation contract");
		boolean dependency = false;
		for (final ConnectorDependency value : descriptor.getDependencies())
			if (Db2LuwConnector.DRIVER_COORDINATE.equals(value.getMinimumVersion())) dependency = true;
		check(dependency, "JCC dependency");
		check(descriptor.getDescription().contains("z/OS") && descriptor.getDescription().contains("IBM i"), "platform boundary");
		boolean acceptance = false;
		for (final String api : descriptor.getApiVersions()) if (api.contains("live target acceptance required")) acceptance = true;
		check(acceptance, "acceptance boundary");
		System.out.println("PASS: IBM Db2 LUW URL, driver, bounds, JNDI, descriptor, operations, and scope boundary");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("db2-luw-test"); config.setType("db2-luw");
		config.setBaseUrl(pUrl); config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("ebx"); config.setPassword("secret");
		return config;
	}
	private static boolean has(final ConfigurationValidationResult pResult, final String pField)
	{ for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true; return false; }
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
