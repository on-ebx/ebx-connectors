package com.on.ebx.connect.connectors;

import java.util.LinkedHashSet;
import java.util.Set;

import com.on.ebx.connect.core.JdbcGuard;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;

/** Account-free contract coverage for the Amazon Redshift JDBC profile (Gartner lot C). */
public final class RedshiftConnectorTest
{
	private RedshiftConnectorTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final RedshiftConnector connector = new RedshiftConnector();
		final String endpoint = "jdbc:redshift://mdm.abc123xyz.eu-west-1.redshift.amazonaws.com:5439/dev";
		final ConnectorConfig valid = configuration(endpoint);
		valid.setProperty("max.rows", "1000");
		connector.configure(valid);
		check(RedshiftConnector.DRIVER.equals(valid.getProperty("driver", null)), "driver default");
		check(connector.validateConfiguration(valid).isValid(), "endpoint URL rejected: " + connector.validateConfiguration(valid).getIssues());
		check(RedshiftConnector.urlProblem("jdbc:redshift:iam://wg.123456789012.eu-west-1.redshift-serverless.amazonaws.com:5439/dev") == null, "serverless IAM endpoint form");
		check(RedshiftConnector.urlProblem("jdbc:redshift:iam://my-cluster:us-east-1/dev") != null, "cluster-identifier form must be refused (host resolved outside egress)");
		check(RedshiftConnector.urlProblem(endpoint + "?plugin_name=com.example.Evil") != null, "class-loading option refused");
		check(RedshiftConnector.urlProblem(endpoint + ";CredentialsProvider=com.example.Evil") != null, "credentials provider class refused");
		check(RedshiftConnector.urlProblem("jdbc:postgresql://db.example:5432/dev") != null, "other sub-protocol refused");

		final ConnectorConfig wrongDriver = configuration(endpoint);
		wrongDriver.setProperty("driver", "org.postgresql.Driver");
		connector.configure(wrongDriver);
		check(has(connector.validateConfiguration(wrongDriver), "driver"), "unrelated driver accepted");
		JdbcGuard.checkDriver(endpoint, RedshiftConnector.DRIVER);
		JdbcGuard.checkDriver(endpoint, RedshiftConnector.LEGACY_DRIVER);
		try
		{
			JdbcGuard.checkDriver(endpoint, "org.h2.Driver");
			throw new AssertionError("JDBC guard accepted an H2 driver for a Redshift URL");
		}
		catch (final com.on.ebx.connect.spi.ConnectorException expected)
		{
			// refused
		}
		final ConnectorConfig jndi = configuration(null);
		jndi.setProperty("jndi", "java:comp/env/jdbc/redshift");
		connector.configure(jndi);
		check(connector.validateConfiguration(jndi).isValid(), "JNDI-only configuration rejected");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("redshift".equals(descriptor.getType()) && "Amazon Redshift".equals(descriptor.getLabel()), "descriptor identity");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)
			&& descriptor.getCapabilities().contains(ConnectorCapability.BULK_READ), "capabilities");
		boolean dependency = false;
		for (final ConnectorDependency value : descriptor.getDependencies())
			if (value.isRequired() && value.getName().contains("Redshift JDBC")) dependency = true;
		check(dependency, "vendor JDBC dependency missing");
		final Set<String> operations = new LinkedHashSet<String>();
		for (final ConnectorOperationDescriptor operation : descriptor.getOperations()) operations.add(operation.getName());
		check(operations.contains("query") && operations.contains("catalog") && operations.contains("sample") && operations.contains("profile"), "catalog, sample and read operations");
		check(descriptor.getDescription().contains("Data API"), "Data API caveat missing");
		check(connector.bindsWatermark("query", "sql"), "watermark bound as a typed parameter");
		realConnectionPathRefuses(endpoint);
		System.out.println("PASS: Redshift endpoint URLs, refused identifier form and class-loading options, driver guard, JNDI, descriptor, operations");
	}

	/**
	 * Audit C3-06, by behaviour: the path every real connection takes ({@code connect()}) refuses what the dedicated validation
	 * describes. Before the fix the cluster-identifier form and the class-loading options went on to the driver.
	 */
	private static void realConnectionPathRefuses(final String pEndpoint) throws Exception
	{
		final String[] urls = { "jdbc:redshift:iam://my-cluster:us-east-1/dev", "jdbc:redshift://mdm.abc123xyz.eu-west-1.redshift.amazonaws.com:5439/dev?plugin_name=com.example.Evil",
			"jdbc:redshift://mdm.abc123xyz.eu-west-1.redshift.amazonaws.com:5439/dev;CredentialsProvider=com.example.Evil",
			"jdbc:redshift://mdm.abc123xyz.eu-west-1.redshift.amazonaws.com:5439/dev?plugin%5Fname=com.example.Evil" };
		for (final String url : urls)
		{
			final RedshiftConnector connector = new RedshiftConnector();
			connector.configure(configuration(url));
			try
			{
				connector.connect();
				throw new AssertionError("connect() went on with " + url);
			}
			catch (final com.on.ebx.connect.spi.ConnectorException expected)
			{
				check(expected.getMessage().startsWith("JDBC: ") && expected.getMessage().contains("refused"), "refusal expected for " + url + ", saw: " + expected.getMessage());
			}
		}
		for (final String option : new String[] { "plugin_name", "CredentialsProvider" })
		{
			final ConnectorConfig withOption = configuration(pEndpoint);
			withOption.setProperty("jdbc." + option, "com.example.Evil");
			final RedshiftConnector connector = new RedshiftConnector();
			connector.configure(withOption);
			try
			{
				connector.connect();
				throw new AssertionError("connect() went on with the driver option " + option);
			}
			catch (final com.on.ebx.connect.spi.ConnectorException expected)
			{
				check(expected.getMessage().contains("option " + option.replace("_", "").toLowerCase() + " is refused"), "option refused: " + expected.getMessage());
			}
		}
		// the endpoint form passes the guard (it then stops at the vendor driver, which this build does not carry)
		final RedshiftConnector good = new RedshiftConnector();
		good.configure(configuration(pEndpoint));
		try
		{
			good.connect();
		}
		catch (final com.on.ebx.connect.spi.ConnectorException expected)
		{
			check(!expected.getMessage().contains("is refused"), "the endpoint form must not be refused: " + expected.getMessage());
		}
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setId("redshift-test"); config.setType("redshift"); config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("ebx"); config.setPassword("secret"); return config;
	}
	private static boolean has(final ConfigurationValidationResult pResult, final String pField)
	{
		for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true;
		return false;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
