package com.on.ebx.connect.connectors;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;

/** Account-free contract coverage for the dedicated MySQL connector. */
public final class MySqlConnectorTest
{
	public static void main(final String[] pArgs)
	{
		final MySqlConnector connector = new MySqlConnector();
		final ConnectorConfig valid = configuration("jdbc:mysql://db.example:3306/mdm");
		connector.configure(valid);
		check(MySqlConnector.DRIVER.equals(valid.getProperty("driver", null)), "driver default");
		check("VERIFY_IDENTITY".equals(valid.getProperty("jdbc.sslMode", null)), "secure TLS default");
		check("false".equals(valid.getProperty("jdbc.allowPublicKeyRetrieval", null)), "public-key retrieval default");
		check(connector.validateConfiguration(valid).isValid(), "valid MySQL configuration rejected");

		final ConnectorConfig wrongUrl = configuration("jdbc:postgresql://db.example:5432/mdm");
		connector.configure(wrongUrl);
		check(has(connector.validateConfiguration(wrongUrl), "baseUrl"), "wrong URL accepted");
		final ConnectorConfig insecure = configuration("jdbc:mysql://db.example:3306/mdm");
		insecure.setProperty("jdbc.sslMode", "DISABLED"); insecure.setProperty("jdbc.allowPublicKeyRetrieval", "true");
		connector.configure(insecure); final ConfigurationValidationResult insecureResult = connector.validateConfiguration(insecure);
		check(has(insecureResult, "jdbc.sslMode") && has(insecureResult, "jdbc.allowPublicKeyRetrieval"), "insecure settings accepted");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND) && descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "directions");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)
			&& descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "capabilities");
		boolean dependency = false;
		for (final ConnectorDependency value : descriptor.getDependencies())
			if (("com.mysql:mysql-connector-j:" + MySqlConnector.CONNECTOR_J_VERSION).equals(value.getMinimumVersion())) dependency = true;
		check(dependency, "exact Connector/J dependency missing");
		System.out.println("PASS: dedicated MySQL connector secure defaults, validation, descriptor, and dependency contract");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("mysql-test"); config.setType("mysql"); config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("ebx"); config.setPassword("secret"); return config;
	}
	private static boolean has(final ConfigurationValidationResult pResult, final String pField)
	{
		for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true; return false;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
