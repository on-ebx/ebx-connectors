package com.on.ebx.connect.connectors;

import java.util.LinkedHashSet;
import java.util.Set;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;

/** Account-free contract coverage for the dedicated Vertica JDBC profile. */
public final class VerticaConnectorTest
{
	private VerticaConnectorTest() { }

	public static void main(final String[] pArgs)
	{
		final VerticaConnector connector = new VerticaConnector();
		final ConnectorConfig valid = configuration("jdbc:vertica://vertica.example:5433/mdm");
		valid.setProperty("max.rows", "1000"); valid.setProperty("fetch.size", "250"); valid.setProperty("batch.size", "100");
		connector.configure(valid);
		check(VerticaConnector.DRIVER.equals(valid.getProperty("driver", null)), "driver default");
		check(connector.validateConfiguration(valid).isValid(), "valid Vertica URL rejected");

		final ConnectorConfig wrongUrl = configuration("jdbc:postgresql://db.example:5432/mdm"); connector.configure(wrongUrl);
		check(has(connector.validateConfiguration(wrongUrl), "baseUrl"), "unrelated JDBC URL accepted");
		final ConnectorConfig wrongDriver = configuration("jdbc:vertica://vertica.example:5433/mdm");
		wrongDriver.setProperty("driver", "example.WrongDriver"); connector.configure(wrongDriver);
		check(has(connector.validateConfiguration(wrongDriver), "driver"), "unrelated driver accepted");
		final ConnectorConfig bounds = configuration("jdbc:vertica://vertica.example:5433/mdm");
		bounds.setProperty("max.rows", "10001"); bounds.setProperty("fetch.size", "0"); bounds.setProperty("batch.size", "many"); connector.configure(bounds);
		final ConfigurationValidationResult bounded = connector.validateConfiguration(bounds);
		check(has(bounded, "max.rows") && has(bounded, "fetch.size") && has(bounded, "batch.size"), "unsafe bounds accepted");
		final ConnectorConfig jndi = configuration(null); jndi.setProperty("jndi", "java:comp/env/jdbc/vertica"); connector.configure(jndi);
		check(connector.validateConfiguration(jndi).isValid(), "JNDI-only configuration rejected");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("vertica".equals(descriptor.getType()) && "Vertica Database".equals(descriptor.getLabel()), "descriptor identity");
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND)
			&& descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "directions");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)
			&& descriptor.getCapabilities().contains(ConnectorCapability.BULK_READ)
			&& descriptor.getCapabilities().contains(ConnectorCapability.BULK_WRITE)
			&& descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "capabilities");
		boolean dependency = false;
		for (final ConnectorDependency value : descriptor.getDependencies())
			if (value.isRequired() && value.getName().contains("Vertica JDBC")) dependency = true;
		check(dependency, "vendor JDBC dependency missing");
		final Set<String> operations = new LinkedHashSet<String>();
		for (final ConnectorOperationDescriptor operation : descriptor.getOperations()) operations.add(operation.getName());
		check(operations.contains("query") && operations.contains("get") && operations.contains("insert")
			&& operations.contains("update") && operations.contains("upsert") && operations.contains("execute")
			&& operations.contains("catalog") && operations.contains("profile") && operations.contains("sample"), "operations incomplete");
		check(descriptor.getDescription().contains("update-then-insert") && descriptor.getDescription().contains("native COPY"),
			"bulk/upsert acceptance caveats missing");
		System.out.println("PASS: Vertica URL, driver, JNDI, bounds, descriptor, dependency, operations, and write caveats");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("vertica-test"); config.setType("vertica"); config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("ebx"); config.setPassword("secret"); return config;
	}
	private static boolean has(final ConfigurationValidationResult pResult, final String pField)
	{
		for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true;
		return false;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
