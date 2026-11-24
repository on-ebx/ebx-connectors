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

/** Account-free contract coverage for the dedicated MonetDB profile. */
public final class MonetDbConnectorTest
{
	private MonetDbConnectorTest() { }

	public static void main(final String[] pArgs)
	{
		final MonetDbConnector connector = new MonetDbConnector();
		final ConnectorConfig plain = configuration("jdbc:monetdb://monet.example:50000/mdm"); connector.configure(plain);
		check(MonetDbConnector.DRIVER.equals(plain.getProperty("driver", null)), "driver default");
		check(connector.validateConfiguration(plain).isValid(), "official MonetDB URL rejected");
		final ConnectorConfig tls = configuration("jdbc:monetdbs://monet.example:50000/mdm"); connector.configure(tls);
		check(connector.validateConfiguration(tls).isValid(), "official TLS URL rejected");
		final ConnectorConfig legacy = configuration("jdbc:mapi:monetdb://monet.example:50000/mdm"); connector.configure(legacy);
		check(has(connector.validateConfiguration(legacy), "baseUrl"), "legacy mapi URL accepted");
		final ConnectorConfig driver = configuration("jdbc:monetdb://monet.example:50000/mdm");
		driver.setProperty("driver", "nl.cwi.monetdb.jdbc.MonetDriver"); connector.configure(driver);
		check(has(connector.validateConfiguration(driver), "driver"), "removed legacy driver class accepted");
		final ConnectorConfig bounds = configuration("jdbc:monetdb://monet.example:50000/mdm");
		bounds.setProperty("max.rows", "10001"); bounds.setProperty("fetch.size", "0"); bounds.setProperty("batch.size", "many"); connector.configure(bounds);
		final ConfigurationValidationResult bounded = connector.validateConfiguration(bounds);
		check(has(bounded, "max.rows") && has(bounded, "fetch.size") && has(bounded, "batch.size"), "unsafe bounds accepted");
		final ConnectorConfig jndi = configuration(null); jndi.setProperty("jndi", "java:comp/env/jdbc/monetdb"); connector.configure(jndi);
		check(connector.validateConfiguration(jndi).isValid(), "JNDI-only configuration rejected");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("monetdb".equals(descriptor.getType()) && "MonetDB".equals(descriptor.getLabel()), "identity");
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND)
			&& descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "directions");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)
			&& descriptor.getCapabilities().contains(ConnectorCapability.BULK_READ)
			&& descriptor.getCapabilities().contains(ConnectorCapability.BULK_WRITE)
			&& descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "capabilities");
		boolean dependency = false;
		for (final ConnectorDependency value : descriptor.getDependencies())
			if (value.isRequired() && value.getMinimumVersion().contains("12.0")) dependency = true;
		check(dependency, "Java-8/TLS driver baseline missing");
		final Set<String> operations = new LinkedHashSet<String>();
		for (final ConnectorOperationDescriptor operation : descriptor.getOperations()) operations.add(operation.getName());
		check(operations.contains("query") && operations.contains("get") && operations.contains("insert")
			&& operations.contains("update") && operations.contains("upsert") && operations.contains("execute")
			&& operations.contains("catalog") && operations.contains("profile") && operations.contains("sample"), "operations incomplete");
		check(descriptor.getDescription().contains("COPY upload handlers are not exposed"), "COPY ON CLIENT boundary absent");
		System.out.println("PASS: MonetDB URL schemes, current driver, JNDI, bounds, descriptor, dependency, operations, and COPY boundary");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("monetdb-test"); config.setType("monetdb"); config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("ebx"); config.setPassword("secret"); return config;
	}
	private static boolean has(final ConfigurationValidationResult pResult, final String pField)
	{
		for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true;
		return false;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
