package com.on.ebx.connect.connectors;

import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;

/** Dependency-free contract coverage for the dedicated H2 connector. */
public final class H2ConnectorTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final H2Connector connector = new H2Connector();
		check("h2".equals(connector.getType()) && "H2 Database".equals(connector.getLabel()), "H2 identity");
		final ConnectorConfig valid = configuration("jdbc:h2:tcp://localhost:9092/mdm"); connector.configure(valid);
		check(H2Connector.DRIVER.equals(valid.getProperty("driver", null)), "H2 driver default");
		check(connector.validateConfiguration(valid).isValid(), "valid H2 configuration rejected");
		final ConnectorConfig wrongUrl = configuration("jdbc:postgresql://localhost/mdm"); connector.configure(wrongUrl);
		check(hasIssue(connector.validateConfiguration(wrongUrl), "baseUrl"), "foreign JDBC URL accepted");
		final ConnectorConfig wrongDriver = configuration("jdbc:h2:mem:test"); wrongDriver.setProperty("driver", "org.postgresql.Driver"); connector.configure(wrongDriver);
		check(hasIssue(connector.validateConfiguration(wrongDriver), "driver"), "foreign driver accepted");
		final ConnectorConfig jndi = configuration("java:comp/env/jdbc/H2"); jndi.setProperty("jndi", "java:comp/env/jdbc/H2"); jndi.setProperty("driver", "container.Managed"); connector.configure(jndi);
		check(connector.validateConfiguration(jndi).isValid(), "JNDI H2 DataSource rejected");
		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND) && descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "directions");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY) && descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "capabilities");
		check("DATABASE".equals(descriptor.getCategory()), "database category");
		check(ConnectorRegistry.get().describeType("h2") != null, "H2 registry entry");
		System.out.println("PASS: dedicated H2 connector defaults, validation, registry, and descriptor");
	}
	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("h2-test"); config.setType("h2"); config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("sa"); config.setPassword("secret"); return config;
	}
	private static boolean hasIssue(final ConfigurationValidationResult pResult, final String pField)
	{ for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true; return false; }
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
