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

/** Account-free contract checks for the Teradata JDBC profile. */
public final class TeradataConnectorTest
{
	private TeradataConnectorTest() { }

	public static void main(final String[] pArgs)
	{
		final TeradataConnector connector = new TeradataConnector();
		check("teradata".equals(connector.getType()) && "Teradata".equals(connector.getLabel()), "identity");
		final ConnectorConfig valid = configuration("jdbc:teradata://vantage.example/DATABASE=GOVERNED,TMODE=ANSI");
		connector.configure(valid);
		check(TeradataConnector.DRIVER.equals(valid.getProperty("driver", null)), "driver default");
		check(connector.validateConfiguration(valid).isValid(), "valid Teradata URL rejected");

		final ConnectorConfig wrongUrl = configuration("jdbc:postgresql://db.example/mdm"); connector.configure(wrongUrl);
		check(has(connector.validateConfiguration(wrongUrl), "baseUrl"), "foreign URL accepted");
		final ConnectorConfig ambiguousUrl = configuration("jdbc:teradata:vantage.example"); connector.configure(ambiguousUrl);
		check(has(connector.validateConfiguration(ambiguousUrl), "baseUrl"), "ambiguous Teradata URL accepted");
		final ConnectorConfig wrongDriver = configuration("jdbc:teradata://vantage.example/DATABASE=GOVERNED");
		wrongDriver.setProperty("driver", "example.Driver");
		check(has(connector.validateConfiguration(wrongDriver), "driver"), "foreign driver accepted");

		final ConnectorConfig unsafe = configuration("jdbc:teradata://vantage.example/DATABASE=GOVERNED");
		unsafe.setProperty("max.rows", "10001"); unsafe.setProperty("fetch.size", "0"); unsafe.setProperty("batch.size", "1001");
		final ConfigurationValidationResult bounds = connector.validateConfiguration(unsafe);
		check(has(bounds, "max.rows") && has(bounds, "fetch.size") && has(bounds, "batch.size"), "unsafe bounds accepted");
		final ConnectorConfig jndi = configuration(null); jndi.setProperty("jndi", "java:comp/env/jdbc/Teradata");
		jndi.setProperty("driver", "container.Managed");
		check(connector.validateConfiguration(jndi).isValid(), "JNDI DataSource rejected");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("DATABASE".equals(descriptor.getCategory()), "category");
		check(descriptor.getDirections().containsAll(Arrays.asList(ConnectorDirection.INBOUND, ConnectorDirection.OUTBOUND)), "directions");
		check(descriptor.getCapabilities().containsAll(Arrays.asList(ConnectorCapability.TEST_CONNECTION,
			ConnectorCapability.DISCOVER_OBJECTS, ConnectorCapability.DESCRIBE_OBJECT, ConnectorCapability.SCHEMA_DISCOVERY,
			ConnectorCapability.PREVIEW_RECORDS, ConnectorCapability.BULK_READ, ConnectorCapability.BULK_WRITE,
			ConnectorCapability.TRANSACTIONS)), "capabilities");
		check(descriptor.getPagination().getDefaultPageSize() == 1000
			&& descriptor.getPagination().getMaximumPageSize() == 10000, "query limits");
		final Set<String> operations = new HashSet<String>();
		for (final Operation operation : connector.getOperations()) operations.add(operation.getName());
		check(operations.equals(new HashSet<String>(Arrays.asList("query", "get", "insert", "update", "upsert", "procedure", "execute",
			"catalog", "profile", "sample"))), "operation contract");
		boolean dependency = false;
		for (final ConnectorDependency value : descriptor.getDependencies())
			if (TeradataConnector.DRIVER_COORDINATE.equals(value.getMinimumVersion())) dependency = true;
		check(dependency, "vendor driver dependency");
		check(descriptor.getDescription().contains("update-then-insert") && descriptor.getDescription().contains("MERGE"),
			"portable upsert caveat");
		boolean acceptance = false;
		for (final String api : descriptor.getApiVersions()) if (api.contains("live target acceptance required")) acceptance = true;
		check(acceptance, "live acceptance boundary");
		System.out.println("PASS: Teradata URL, driver, JNDI, bounds, query limits, descriptor, operations, and caveats");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("teradata-test"); config.setType("teradata");
		config.setBaseUrl(pUrl); config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("ebx"); config.setPassword("secret");
		return config;
	}
	private static boolean has(final ConfigurationValidationResult pResult, final String pField)
	{ for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true; return false; }
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
