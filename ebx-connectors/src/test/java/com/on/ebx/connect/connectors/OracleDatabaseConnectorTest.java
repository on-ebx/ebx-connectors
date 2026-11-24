package com.on.ebx.connect.connectors;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.core.ConnectorRegistry;

/** Dependency-free contract coverage for the dedicated Oracle Database connector. */
public final class OracleDatabaseConnectorTest
{
	public static void main(final String[] pArgs)
	{
		final OracleDatabaseConnector connector = new OracleDatabaseConnector();
		check("oracle-database".equals(connector.getType()), "Oracle type");
		check("Oracle Database".equals(connector.getLabel()), "Oracle label");
		final ConnectorConfig valid = configuration("jdbc:oracle:thin:@//db.example:1521/MDMPDB"); connector.configure(valid);
		check("oracle.jdbc.OracleDriver".equals(valid.getProperty("driver", null)), "Oracle driver default");
		check(connector.validateConfiguration(valid).isValid(), "Valid Oracle configuration rejected");
		final ConfigurationValidationResult invalid = connector.validateConfiguration(configuration("jdbc:postgresql://db/mdm"));
		check(!invalid.isValid() && hasIssue(invalid, "baseUrl"), "Non-Oracle URL accepted");
		final ConnectorConfig jndi = configuration("java:comp/env/jdbc/OracleMDM"); jndi.setProperty("jndi", "java:comp/env/jdbc/OracleMDM");
		check(connector.validateConfiguration(jndi).isValid(), "JNDI Oracle DataSource rejected");
		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("DATABASE".equals(descriptor.getCategory()), "Database category");
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND) && descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "Directions");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY), "Schema discovery");
		check(descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "Transactions");
		check(descriptor.getDependencies().size() == 1 && descriptor.getDependencies().get(0).isRequired(), "Required Oracle driver dependency");
		check(ConnectorRegistry.get().getTypes().contains("oracle-database"), "Oracle connector is not registered");
		System.out.println("PASS: dedicated Oracle Database defaults, validation, descriptor, registry metadata and branding");
	}
	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("oracle-db-test"); config.setType("oracle-database");
		config.setBaseUrl(pUrl); config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("ebx"); config.setPassword("secret"); return config;
	}
	private static boolean hasIssue(final ConfigurationValidationResult pResult, final String pField)
	{
		for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true; return false;
	}
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
