package com.on.ebx.connect.connectors;

import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;

/** Dependency-free contract coverage for the dedicated PostgreSQL connector. */
public final class PostgreSqlConnectorTest
{
	private PostgreSqlConnectorTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		final PostgreSqlConnector connector = new PostgreSqlConnector();
		check("postgresql".equals(connector.getType()), "Unexpected PostgreSQL type");
		check("PostgreSQL".equals(connector.getLabel()), "Unexpected PostgreSQL label");
		check(com.on.ebx.connect.ebx.ui.Logos.preferredFor("PostgreSQL") != null,
			"PostgreSQL logo is not packaged");
		check(com.on.ebx.connect.ebx.ui.UploadedLogoWidgetFactory.isAcceptedDataUrl("data:image/png;base64,AA=="),
			"A valid uploaded PNG logo was rejected");
		check(!com.on.ebx.connect.ebx.ui.UploadedLogoWidgetFactory.isAcceptedDataUrl("data:image/svg+xml;base64,AA=="),
			"An active-content SVG upload was accepted");
		check("/ebx-ui/".equals(com.on.ebx.connect.ebx.ui.MappingForm.EBX_UI_BASE_URI),
			"Catalog record launch must remain in the modern EBX UI context");

		final ConnectorConfig valid = configuration("jdbc:postgresql://db.example:5432/mdm");
		connector.configure(valid);
		check("org.postgresql.Driver".equals(valid.getProperty("driver", null)),
			"PostgreSQL driver default was not applied");
		check(connector.validateConfiguration(valid).isValid(), "A valid PostgreSQL configuration was rejected");

		final ConnectorConfig invalid = configuration("jdbc:mysql://db.example:3306/mdm");
		final ConfigurationValidationResult invalidResult = connector.validateConfiguration(invalid);
		check(!invalidResult.isValid(), "A non-PostgreSQL JDBC URL was accepted");
		check(hasIssue(invalidResult, "baseUrl"), "The invalid URL was not attributed to baseUrl");

		final ConnectorConfig wrongDriver = configuration("jdbc:postgresql://db.example:5432/mdm");
		wrongDriver.setProperty("driver", "com.example.WrongDriver");
		check(hasIssue(connector.validateConfiguration(wrongDriver), "driver"),
			"A non-PostgreSQL driver was accepted");

		final ConnectorConfig invalidBounds = configuration("jdbc:postgresql://db.example:5432/mdm");
		invalidBounds.setProperty("max.rows", "10001");
		invalidBounds.setProperty("batch.size", "0");
		final ConfigurationValidationResult bounds = connector.validateConfiguration(invalidBounds);
		check(hasIssue(bounds, "max.rows") && hasIssue(bounds, "batch.size"),
			"Unsafe PostgreSQL execution bounds were accepted");

		final ConnectorConfig jndi = configuration(null);
		jndi.setProperty("jndi", "java:comp/env/jdbc/PostgreSQL");
		check(connector.validateConfiguration(jndi).isValid(), "A JNDI PostgreSQL DataSource was rejected");
		try
		{
			Class.forName("org.postgresql.Driver", false, PostgreSqlConnectorTest.class.getClassLoader());
		}
		catch (final ClassNotFoundException ex)
		{
			throw new AssertionError("The packaged PostgreSQL JDBC driver is not loadable", ex);
		}

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check(descriptor.getDirections().contains(ConnectorDirection.INBOUND), "Inbound direction is missing");
		check(descriptor.getDirections().contains(ConnectorDirection.OUTBOUND), "Outbound direction is missing");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY),
			"Schema discovery capability is missing");
		check(descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS),
			"Transaction capability is missing");

		System.out.println("PASS: dedicated PostgreSQL connector defaults, validation, and descriptor");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("postgresql-test");
		config.setType("postgresql");
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
