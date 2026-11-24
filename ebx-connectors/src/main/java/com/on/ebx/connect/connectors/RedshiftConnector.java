package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDefaults;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/**
 * Amazon Redshift (provisioned clusters and Serverless workgroups) through the Amazon Redshift JDBC driver,
 * on the shared bounded JDBC engine: schema discovery, catalog, profile, sample, bounded preview,
 * parameterised SQL, watermark feeds ({@code ${watermark}} bound as a typed parameter), JDBC batches.
 * <p>
 * {@code baseUrl} = the <b>endpoint</b> form, {@code jdbc:redshift://<endpoint>:5439/<database>} or
 * {@code jdbc:redshift:iam://<endpoint>:5439/<database>}: the egress allow-list and the private-address rule
 * see the real host. The cluster-identifier IAM form ({@code jdbc:redshift:iam://my-cluster:us-east-1/dev})
 * is refused, since the driver would resolve the endpoint through AWS on its own, outside those checks.
 * URL options that make the driver load a class ({@code plugin_name}, {@code CredentialsProvider}) are
 * refused as well; a path option (log, truststore) is refused by the shared JDBC guard.
 */
public final class RedshiftConnector extends JdbcConnector
{
	public static final String DRIVER = "com.amazon.redshift.jdbc42.Driver";

	@Override public String getType() { return "redshift"; }
	@Override public String getLabel() { return "Amazon Redshift"; }

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getProperty("driver", null) == null) pConfig.setProperty("driver", DRIVER);
		super.configure(pConfig);
	}

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null) return common;
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		final String jndi = trim(pConfig.getProperty("jndi", null));
		if (jndi != null)
		{
			for (final java.util.Iterator<ConfigurationValidationIssue> iterator = issues.iterator(); iterator.hasNext();)
				if ("baseUrl".equals(iterator.next().getPropertyName())) iterator.remove();
		}
		else
		{
			final String url = pConfig.getBaseUrl();
			final String problem = RedshiftConnector.urlProblem(url);
			if (problem != null) issues.add(new ConfigurationValidationIssue("baseUrl", problem));
			final String driver = pConfig.getProperty("driver", DRIVER);
			if (!DRIVER.equals(driver) && !LEGACY_DRIVER.equals(driver))
				issues.add(new ConfigurationValidationIssue("driver", "Dedicated Redshift connections require " + DRIVER));
		}
		validateBound(pConfig, "max.rows", 1, 10000, issues);
		validateBound(pConfig, "fetch.size", 1, 10000, issues);
		validateBound(pConfig, "batch.size", 1, 1000, issues);
		return new ConfigurationValidationResult(issues);
	}

	/** The older class name of the same driver (JDBC 4.1 builds). */
	public static final String LEGACY_DRIVER = "com.amazon.redshift.jdbc.Driver";

	/** Why a Redshift JDBC URL is refused, null when it is accepted. Called by {@code JdbcGuard.checkUrl} on every connection (audit C3-06). */
	public static String urlProblem(final String pUrl)
	{
		if (pUrl == null || !(pUrl.startsWith("jdbc:redshift://") || pUrl.startsWith("jdbc:redshift:iam://")))
			return "Must start with jdbc:redshift:// or jdbc:redshift:iam:// (endpoint form), or property jndi must name a DataSource";
		final String rest = pUrl.substring(pUrl.indexOf("://") + 3);
		final String authority = rest.split("[/;?]", 2)[0];
		final int colon = authority.lastIndexOf(':');
		final String host = colon < 0 ? authority : authority.substring(0, colon);
		final String port = colon < 0 ? "" : authority.substring(colon + 1);
		if (host.isEmpty() || !host.contains(".") || !port.matches("\\d{1,5}"))
			return "Use the endpoint form <endpoint>:<port>/<database> (e.g. my-cluster.abc123.eu-west-1.redshift.amazonaws.com:5439/dev): "
				+ "the cluster-identifier form lets the driver resolve the host outside the egress checks";
		final String lower = pUrl.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("plugin_name") || lower.contains("credentialsprovider"))
			return "Options plugin_name and CredentialsProvider are refused: the driver would load the class they name";
		return null;
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("Amazon Redshift connectivity with schema discovery, catalog, profile and sample, bounded preview, parameterized SQL, watermark feeds, JDBC batching, and transactions. Upsert uses update-then-insert; COPY / UNLOAD through S3 and the Redshift Data API are not implied.")
			.authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ)
			.capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.TRANSACTIONS)
			.apiVersion("Amazon Redshift JDBC 2.x")
			.dependency(new ConnectorDependency("Amazon Redshift JDBC driver", "com.amazon.redshift:redshift-jdbc42:2.1.x", true,
				"Install the Amazon Redshift JDBC 4.2 driver JAR under WEB-INF/lib (or the server lib); EBX-Connectors does not redistribute it"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, 10000, null))
			.defaults(ConnectorDefaults.standard())
			.configurationProperty(property("jndi", ConfigurationPropertyType.STRING))
			.configurationProperty(property("driver", ConfigurationPropertyType.STRING))
			.configurationProperty(property("schema", ConfigurationPropertyType.STRING))
			.configurationProperty(property("max.rows", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("fetch.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("batch.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(property("quote", ConfigurationPropertyType.STRING));
		for (final Operation operation : getOperations())
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(),
				operation.getDescription(), Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return descriptor.build();
	}

	private static void validateBound(final ConnectorConfig pConfig, final String pName, final int pMinimum,
		final int pMaximum, final List<ConfigurationValidationIssue> pIssues)
	{
		final String value = trim(pConfig.getProperty(pName, null));
		if (value == null) return;
		try
		{
			final long parsed = Long.parseLong(value);
			if (parsed < pMinimum || parsed > pMaximum)
				pIssues.add(new ConfigurationValidationIssue(pName, "Must be between " + pMinimum + " and " + pMaximum));
		}
		catch (final NumberFormatException ex)
		{
			pIssues.add(new ConfigurationValidationIssue(pName, "Must be an integer between " + pMinimum + " and " + pMaximum));
		}
	}

	private static String trim(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? null : pValue.trim();
	}

	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).build();
	}
}
