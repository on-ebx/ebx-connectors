package com.on.ebx.connect.connectors;

import java.util.HashSet;
import java.util.Set;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;

/** Account-free contract checks for the certified Firebird JDBC profile. */
public final class FirebirdConnectorTest
{
	public static void main(final String[] pArgs)
	{
		final FirebirdConnector connector = new FirebirdConnector();
		check("firebird".equals(connector.getType()) && "Firebird".equals(connector.getLabel()), "identity");
		final ConnectorConfig valid = configuration("jdbc:firebirdsql://db.example:3050/data/governed.fdb");
		connector.configure(valid);
		check(FirebirdConnector.DRIVER.equals(valid.getProperty("driver", null)), "Jaybird driver default");
		check(connector.validateConfiguration(valid).isValid(), "valid configuration rejected");

		final ConnectorConfig wrongUrl = configuration("jdbc:postgresql://db.example:5432/mdm");
		connector.configure(wrongUrl);
		check(has(connector.validateConfiguration(wrongUrl), "baseUrl"), "wrong URL accepted");
		final ConnectorConfig wrongDriver = configuration("jdbc:firebirdsql://db.example:3050/data/governed.fdb");
		wrongDriver.setProperty("driver", "com.example.Driver");
		check(has(connector.validateConfiguration(wrongDriver), "driver"), "wrong driver accepted");
		final ConnectorConfig unsafe = configuration("jdbc:firebirdsql://db.example:3050/data/governed.fdb");
		unsafe.setProperty("max.rows", "10001"); unsafe.setProperty("fetch.size", "0"); unsafe.setProperty("batch.size", "1001");
		final ConfigurationValidationResult unsafeResult = connector.validateConfiguration(unsafe);
		check(has(unsafeResult, "max.rows") && has(unsafeResult, "fetch.size") && has(unsafeResult, "batch.size"), "unsafe bounds accepted");
		final ConnectorConfig jndi = configuration(null); jndi.setProperty("jndi", "java:comp/env/jdbc/Firebird");
		check(connector.validateConfiguration(jndi).isValid(), "JNDI configuration rejected");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND)
			&& descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "directions");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)
			&& descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "capabilities");
		check(descriptor.getDescription().contains("update-then-insert"), "upsert semantics undisclosed");
		boolean dependency = false; boolean boundary = false;
		for (final ConnectorDependency value : descriptor.getDependencies())
			if (("org.firebirdsql.jdbc:jaybird:" + FirebirdConnector.JAYBIRD_VERSION).equals(value.getMinimumVersion())) dependency = true;
		for (final String version : descriptor.getApiVersions()) if (version.contains("live target acceptance required")) boundary = true;
		check(dependency && boundary, "dependency or live-acceptance boundary missing");
		final Set<String> operations = new HashSet<String>();
		for (final ConnectorOperationDescriptor operation : descriptor.getOperations()) operations.add(operation.getName());
		check(operations.contains("query") && operations.contains("get") && operations.contains("insert")
			&& operations.contains("update") && operations.contains("upsert") && operations.contains("execute")
			&& operations.contains("catalog") && operations.contains("profile") && operations.contains("sample"), "operations incomplete");
		System.out.println("PASS: Firebird Jaybird defaults, validation, bounds, operations, descriptor, and acceptance boundary");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("firebird-test"); config.setType("firebird");
		config.setBaseUrl(pUrl); config.setAuthType(ConnectorConfig.AuthType.BASIC);
		config.setUsername("ebx"); config.setPassword("secret"); return config;
	}
	private static boolean has(final ConfigurationValidationResult pResult, final String pField)
	{
		for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true;
		return false;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
