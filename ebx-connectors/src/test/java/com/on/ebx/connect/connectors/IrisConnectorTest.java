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

/** Account-free contract coverage for the InterSystems IRIS JDBC profile. */
public final class IrisConnectorTest
{
	private IrisConnectorTest() { }

	public static void main(final String[] pArgs)
	{
		final IrisConnector connector = new IrisConnector();
		final ConnectorConfig iris = configuration("jdbc:IRIS://iris.example:1972/USER");
		iris.setProperty("max.rows", "1000"); iris.setProperty("fetch.size", "100"); iris.setProperty("batch.size", "50");
		connector.configure(iris);
		check("iris".equals(connector.getType()) && "InterSystems IRIS".equals(connector.getLabel()), "canonical identity");
		check(IrisConnector.DRIVER.equals(iris.getProperty("driver", null)), "IRIS driver default");
		check(connector.validateConfiguration(iris).isValid(), "valid IRIS URL rejected");

		final ConnectorConfig cache = configuration("jdbc:Cache://cache.example:1972/USER");
		connector.configure(cache);
		check(connector.validateConfiguration(cache).isValid(), "legacy Caché-compatible URL rejected");
		final ConnectorConfig wrong = configuration("jdbc:postgresql://db.example:5432/mdm"); connector.configure(wrong);
		check(has(connector.validateConfiguration(wrong), "baseUrl"), "unrelated JDBC URL accepted");
		final ConnectorConfig driver = configuration("jdbc:IRIS://iris.example:1972/USER");
		driver.setProperty("driver", "example.WrongDriver"); connector.configure(driver);
		check(has(connector.validateConfiguration(driver), "driver"), "unrelated driver accepted");
		final ConnectorConfig bounds = configuration("jdbc:IRIS://iris.example:1972/USER");
		bounds.setProperty("max.rows", "10001"); bounds.setProperty("fetch.size", "0"); bounds.setProperty("batch.size", "many");
		connector.configure(bounds); final ConfigurationValidationResult invalidBounds = connector.validateConfiguration(bounds);
		check(has(invalidBounds, "max.rows") && has(invalidBounds, "fetch.size") && has(invalidBounds, "batch.size"), "unsafe bounds accepted");
		final ConnectorConfig jndi = configuration(null); jndi.setProperty("jndi", "java:comp/env/jdbc/iris"); connector.configure(jndi);
		check(connector.validateConfiguration(jndi).isValid(), "JNDI DataSource configuration rejected");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("iris".equals(descriptor.getType()) && "InterSystems IRIS".equals(descriptor.getLabel()), "descriptor identity");
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND)
			&& descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "bidirectional descriptor");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)
			&& descriptor.getCapabilities().contains(ConnectorCapability.PREVIEW_RECORDS)
			&& descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "capabilities");
		boolean dependency = false;
		for (final ConnectorDependency value : descriptor.getDependencies())
			if (value.isRequired() && value.getName().contains("IRIS JDBC")) dependency = true;
		check(dependency, "IRIS JDBC dependency missing");
		final Set<String> operations = new LinkedHashSet<String>();
		for (final ConnectorOperationDescriptor operation : descriptor.getOperations()) operations.add(operation.getName());
		check(operations.contains("query") && operations.contains("get") && operations.contains("insert")
			&& operations.contains("update") && operations.contains("upsert") && operations.contains("execute")
			&& operations.contains("catalog") && operations.contains("profile") && operations.contains("sample"), "JDBC operations incomplete");
		check(descriptor.getDescription().contains("legacy Caché-compatible"), "compatibility boundary absent");
		System.out.println("PASS: InterSystems IRIS canonical profile, Caché URL compatibility, bounds, JNDI, descriptor, dependency, and operations");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("iris-test"); config.setType("iris");
		config.setBaseUrl(pUrl); config.setAuthType(ConnectorConfig.AuthType.BASIC);
		config.setUsername("ebx"); config.setPassword("secret"); return config;
	}
	private static boolean has(final ConfigurationValidationResult pResult, final String pField)
	{
		for (final ConfigurationValidationIssue issue : pResult.getIssues())
			if (pField.equals(issue.getPropertyName())) return true;
		return false;
	}
	private static void check(final boolean pValue, final String pMessage)
	{
		if (!pValue) throw new AssertionError(pMessage);
	}
}
