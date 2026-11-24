package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import com.on.ebx.connect.connectors.DatabricksConnector;
import com.on.ebx.connect.connectors.Db2LuwConnector;
import com.on.ebx.connect.connectors.DnbConnector;
import com.on.ebx.connect.connectors.GenericRestConnector;
import com.on.ebx.connect.connectors.GleifConnector;
import com.on.ebx.connect.connectors.FirebirdConnector;
import com.on.ebx.connect.connectors.GoogleGeocodingConnector;
import com.on.ebx.connect.connectors.GoogleSheetsConnector;
import com.on.ebx.connect.connectors.JdeAisConnector;
import com.on.ebx.connect.connectors.KafkaConnector;
import com.on.ebx.connect.connectors.SalesforceConnector;
import com.on.ebx.connect.connectors.SapODataConnector;
import com.on.ebx.connect.connectors.SireneConnector;
import com.on.ebx.connect.connectors.SnowflakeConnector;
import com.on.ebx.connect.connectors.PowerBiConnector;
import com.on.ebx.connect.connectors.CollibraConnector;
import com.on.ebx.connect.connectors.AzureBlobConnector;
import com.on.ebx.connect.connectors.AzureCosmosDbConnector;
import com.on.ebx.connect.connectors.BanConnector;
import com.on.ebx.connect.connectors.BigQueryConnector;
import com.on.ebx.connect.connectors.CompaniesHouseConnector;
import com.on.ebx.connect.connectors.CreditsafeConnector;
import com.on.ebx.connect.connectors.Dynamics365Connector;
import com.on.ebx.connect.connectors.DynamicsBusinessCentralConnector;
import com.on.ebx.connect.connectors.GcsConnector;
import com.on.ebx.connect.connectors.GreenplumConnector;
import com.on.ebx.connect.connectors.HubSpotConnector;
import com.on.ebx.connect.connectors.JdbcConnector;
import com.on.ebx.connect.connectors.LoqateConnector;
import com.on.ebx.connect.connectors.MelissaConnector;
import com.on.ebx.connect.connectors.MySqlConnector;
import com.on.ebx.connect.connectors.MonetDbConnector;
import com.on.ebx.connect.connectors.OffeneRegisterConnector;
import com.on.ebx.connect.connectors.OpenCorporatesConnector;
import com.on.ebx.connect.connectors.OpenSanctionsConnector;
import com.on.ebx.connect.connectors.OracleFusionConnector;
import com.on.ebx.connect.connectors.NetSuiteConnector;
import com.on.ebx.connect.connectors.OracleDatabaseConnector;
import com.on.ebx.connect.connectors.OrbisConnector;
import com.on.ebx.connect.connectors.S3Connector;
import com.on.ebx.connect.connectors.ServiceNowConnector;
import com.on.ebx.connect.connectors.SharePointConnector;
import com.on.ebx.connect.connectors.SingleStoreConnector;
import com.on.ebx.connect.connectors.ViesConnector;
import com.on.ebx.connect.connectors.WebhookConnector;
import com.on.ebx.connect.connectors.WorkdayConnector;
import com.on.ebx.connect.connectors.LlmConnector;
import com.on.ebx.connect.connectors.SoapConnector;
import com.on.ebx.connect.connectors.GraphqlConnector;
import com.on.ebx.connect.connectors.EbxConnector;
import com.on.ebx.connect.connectors.ElasticsearchConnector;
import com.on.ebx.connect.connectors.CassandraConnector;
import com.on.ebx.connect.connectors.MongoDbConnector;
import com.on.ebx.connect.connectors.CouchbaseConnector;
import com.on.ebx.connect.connectors.CmisConnector;
import com.on.ebx.connect.connectors.ChatWebhookConnector;
import com.on.ebx.connect.connectors.McpConnector;
import com.on.ebx.connect.connectors.SlackConnector;
import com.on.ebx.connect.connectors.JmsConnector;
import com.on.ebx.connect.connectors.ActiveMqConnector;
import com.on.ebx.connect.connectors.FtpConnector;
import com.on.ebx.connect.connectors.GooglePubSubConnector;
import com.on.ebx.connect.connectors.SnsConnector;
import com.on.ebx.connect.connectors.SqsConnector;
import com.on.ebx.connect.connectors.ActianVectorConnector;
import com.on.ebx.connect.connectors.ActianIngresConnector;
import com.on.ebx.connect.connectors.InpiConnector;
import com.on.ebx.connect.connectors.IrisConnector;
import com.on.ebx.connect.connectors.OpenFdaConnector;
import com.on.ebx.connect.connectors.OpenLineageConnector;
import com.on.ebx.connect.connectors.PostgreSqlConnector;
import com.on.ebx.connect.connectors.RabbitMqConnector;
import com.on.ebx.connect.connectors.H2Connector;
import com.on.ebx.connect.connectors.SqlServerConnector;
import com.on.ebx.connect.connectors.AzureSqlDatabaseConnector;
import com.on.ebx.connect.connectors.AzureServiceBusConnector;
import com.on.ebx.connect.connectors.AzureQueueStorageConnector;
import com.on.ebx.connect.connectors.AzureTableStorageConnector;
import com.on.ebx.connect.connectors.SuccessFactorsConnector;
import com.on.ebx.connect.connectors.SapAribaConnector;
import com.on.ebx.connect.connectors.SapAseConnector;
import com.on.ebx.connect.connectors.SapHanaConnector;
import com.on.ebx.connect.connectors.TeradataConnector;
import com.on.ebx.connect.connectors.VerticaConnector;
import com.on.ebx.connect.connectors.RedshiftConnector;
import com.on.ebx.connect.connectors.DocumentConnector;
import com.on.ebx.connect.connectors.VeevaVaultConnector;
import com.on.ebx.connect.connectors.AlationConnector;
import com.on.ebx.connect.connectors.MicrosoftPurviewConnector;
import com.on.ebx.connect.connectors.HadoopWebHdfsConnector;
import com.on.ebx.connect.connectors.SparkSqlConnector;
import com.on.ebx.connect.connectors.ClouderaHiveConnector;
import com.on.ebx.connect.connectors.ClouderaImpalaConnector;
import com.on.ebx.connect.connectors.DominoRestConnector;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorConfigurationValidator;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Connector types (by {@link Connector#getType()}) and configured instances (by connector id).
 * <p>
 * Types come from the built-ins, from {@link #registerType(Class)} and from
 * {@code META-INF/services/com.on.ebx.connect.spi.Connector}. Instances are created lazily from
 * the configuration read in EBX and cached; {@link #invalidate()} drops the cache after a
 * configuration change.
 */
public final class ConnectorRegistry
{
	private static final ConnectorRegistry INSTANCE = new ConnectorRegistry();

	private final Map<String, Class<? extends Connector>> types = new LinkedHashMap<String, Class<? extends Connector>>();
	private final Map<String, Connector> instances = new LinkedHashMap<String, Connector>();
	private final Map<String, ConnectorConfig> configs = new LinkedHashMap<String, ConnectorConfig>();

	private ConnectorRegistry()
	{
		this.registerType(GleifConnector.class);
		this.registerType(ActianVectorConnector.class);
		this.registerType(ActianIngresConnector.class);
		this.registerType(MonetDbConnector.class);
		this.registerType(SireneConnector.class);
		this.registerType(GoogleGeocodingConnector.class);
		this.registerType(GoogleSheetsConnector.class);
        this.registerType(com.on.ebx.connect.connectors.GoogleDriveConnector.class);
        this.registerType(com.on.ebx.connect.connectors.OneDriveConnector.class);
		this.registerType(GenericRestConnector.class);
		this.registerType(SalesforceConnector.class);
		this.registerType(SnowflakeConnector.class);
		this.registerType(DatabricksConnector.class);
		this.registerType(PowerBiConnector.class);
		this.registerType(CollibraConnector.class);
		this.registerType(SapODataConnector.class);
		this.registerType(JdeAisConnector.class);
		this.registerType(DnbConnector.class);
		this.registerType(AzureBlobConnector.class);
		this.registerType(AzureCosmosDbConnector.class);
		this.registerType(BanConnector.class);
		this.registerType(BigQueryConnector.class);
		this.registerType(CompaniesHouseConnector.class);
		this.registerType(CreditsafeConnector.class);
		this.registerType(Dynamics365Connector.class);
		this.registerType(DynamicsBusinessCentralConnector.class);
		this.registerType(GcsConnector.class);
		this.registerType(HubSpotConnector.class);
		this.registerType(JdbcConnector.class);
		this.registerType(FirebirdConnector.class);
		this.registerType(Db2LuwConnector.class);
		this.registerType(IrisConnector.class);
		this.registerType(PostgreSqlConnector.class);
		this.registerType(GreenplumConnector.class);
		this.registerType(RabbitMqConnector.class);
		this.registerType(H2Connector.class);
		this.registerType(SqlServerConnector.class);
		this.registerType(AzureSqlDatabaseConnector.class);
		this.registerType(AzureServiceBusConnector.class);
		this.registerType(AzureQueueStorageConnector.class);
		this.registerType(AzureTableStorageConnector.class);
		this.registerType(OracleDatabaseConnector.class);
		this.registerType(LoqateConnector.class);
		this.registerType(MelissaConnector.class);
		this.registerType(MySqlConnector.class);
		this.registerType(SingleStoreConnector.class);
		this.registerType(OffeneRegisterConnector.class);
		this.registerType(OpenCorporatesConnector.class);
		this.registerType(OpenSanctionsConnector.class);
		this.registerType(OracleFusionConnector.class);
		this.registerType(NetSuiteConnector.class);
		this.registerType(SuccessFactorsConnector.class);
		this.registerType(SapAribaConnector.class);
		this.registerType(SapAseConnector.class);
		this.registerType(SapHanaConnector.class);
		this.registerType(TeradataConnector.class);
		this.registerType(VerticaConnector.class);
		this.registerType(RedshiftConnector.class);
		this.registerType(VeevaVaultConnector.class);
		this.registerType(AlationConnector.class);
		this.registerType(MicrosoftPurviewConnector.class);
		this.registerType(HadoopWebHdfsConnector.class);
		this.registerType(SparkSqlConnector.class);
		this.registerType(ClouderaHiveConnector.class);
		this.registerType(ClouderaImpalaConnector.class);
		this.registerType(DominoRestConnector.class);
		this.registerType(OrbisConnector.class);
		this.registerType(S3Connector.class);
		this.registerType(ServiceNowConnector.class);
		this.registerType(SharePointConnector.class);
		this.registerType(ViesConnector.class);
		this.registerType(WebhookConnector.class);
		this.registerType(WorkdayConnector.class);
		this.registerType(LlmConnector.class);
		this.registerType(DocumentConnector.class);
		this.registerType(SoapConnector.class);
		this.registerType(GraphqlConnector.class);
		this.registerType(EbxConnector.class);
		this.registerType(ElasticsearchConnector.class);
		this.registerType(CassandraConnector.class);
		this.registerType(MongoDbConnector.class);
		this.registerType(CouchbaseConnector.class);
		this.registerType(CmisConnector.class);
		this.registerType(OpenLineageConnector.class);
		this.registerType(ChatWebhookConnector.class);
		this.registerType(SlackConnector.class);
		this.registerType(McpConnector.class);
		this.registerType(JmsConnector.class);
		this.registerType(ActiveMqConnector.class);
		this.registerType(FtpConnector.class);
		this.registerType(com.on.ebx.connect.connectors.SampleFileConnector.class);
		this.registerType(GooglePubSubConnector.class);
		this.registerType(SnsConnector.class);
		this.registerType(SqsConnector.class);
		this.registerType(InpiConnector.class);
		this.registerType(OpenFdaConnector.class);
		this.registerTypeQuietly(KafkaConnector.class.getName());
		this.registerTypeQuietly("com.on.ebx.connect.connectors.SftpConnector");
		try
		{
			for (final Connector connector : ServiceLoader.load(Connector.class, ConnectorRegistry.class.getClassLoader()))
			{
				this.registerType(connector.getClass());
			}
		}
		catch (final Throwable ex)
		{
			// a broken service file must not prevent the built-ins from working
		}
	}

	public static ConnectorRegistry get()
	{
		return ConnectorRegistry.INSTANCE;
	}

	/** A type whose library may be absent (kafka-clients.jar): skipped rather than fatal. */
	@SuppressWarnings("unchecked")
	private void registerTypeQuietly(final String pClassName)
	{
		try
		{
			this.registerType((Class<? extends Connector>) Class.forName(pClassName, true, ConnectorRegistry.class.getClassLoader()));
		}
		catch (final Throwable ex)
		{
			// library missing: the type is simply not offered
		}
	}

	public synchronized void registerType(final Class<? extends Connector> pClass)
	{
		try
		{
			final Connector probe = pClass.newInstance();
			this.types.put(probe.getType(), pClass);
		}
		catch (final Exception ex)
		{
			throw new IllegalArgumentException("Connector class " + pClass.getName()
					+ " needs a public no-arg constructor: " + ex.getMessage(), ex);
		}
	}

	public synchronized List<String> getTypes()
	{
		return new ArrayList<String>(this.types.keySet());
	}

	/** A throw-away instance, to read the label and the operations of a type. */
	public synchronized Connector describeType(final String pType) throws ConnectorException
	{
		final Class<? extends Connector> type = this.types.get(pType);
		if (type == null)
		{
			throw new ConnectorException("Unknown connector type '" + pType + "'; known: " + this.types.keySet());
		}
		try
		{
			return type.newInstance();
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Cannot instantiate connector type '" + pType + "'", ex);
		}
	}

	/** Where the registry reads the configuration of a connector id; EBX in the module, a stand-in in a test. */
	interface ConfigSource
	{
		ConnectorConfig connector(String pId);

		/** Why a secret property of this connector, held in clear, forbids building it; null when none does (the default). */
		default String clearSecretProblem(final String pId)
		{
			return null;
		}
	}

	/** The configured connector for an id; the instance is cached while its configuration is unchanged in EBX. */
	public Connector connector(final String pId, final ConnectorRepository pRepository)
		throws ConnectorException
	{
		return this.connector(pId, new ConfigSource()
		{
			@Override
			public ConnectorConfig connector(final String pConnectorId)
			{
				return pRepository.connector(pConnectorId);
			}

			@Override
			public String clearSecretProblem(final String pConnectorId)
			{
				// read from the stored rows (a vault reference is already a value in the configuration), once per (re)build
				return ClearSecrets.propertyProblem(pRepository.dataset(), pConnectorId);
			}
		});
	}

	Connector connector(final String pId, final ConfigSource pSource) throws ConnectorException
	{
		// The instance this lookup makes obsolete, closed once the lock is released: closing a client may
		// take seconds (a Kafka producer flushes) and every synchronisation goes through this lock.
		final Connector[] dropped = new Connector[1];
		try
		{
			synchronized (this)
			{
				return this.connectorLocked(pId, pSource, dropped);
			}
		}
		finally
		{
			ConnectorRegistry.close(pId, dropped[0]);
		}
	}

	private Connector connectorLocked(final String pId, final ConfigSource pSource, final Connector[] pDropped)
		throws ConnectorException
	{
		// the configuration is re-read on every access (one record and its properties, negligible next
		// to the remote call) and the cached instance is kept only while it is unchanged: a key entered
		// or an endpoint edited in the EBX UI is taken into account immediately, without restart
		final ConnectorConfig config = pSource.connector(pId);
		if (config == null)
		{
			pDropped[0] = this.instances.remove(pId);
			this.configs.remove(pId);
			throw new ConnectorException("Connector '" + pId + "' is not declared in the Connectors dataset");
		}
		// the one door every execution passes (call, sync, scheduled task, trigger, workflow): a preview function that is closed
		// refuses here, before anything of the connection is built; the type stays known and the row stays readable
		PreviewFeatures.requireForType(config.getType());
		Connector connector = this.instances.get(pId);
		final ConnectorConfig cached = this.configs.get(pId);
		if (connector != null && cached != null && cached.sameConfigurationAs(config))
		{
			return connector;
		}
		// Whatever follows - a new instance, or a refusal because the connector is now inactive or
		// misconfigured - the cached instance was built for a configuration that no longer exists: it
		// will never be returned again and is let go of here, with what it holds open.
		pDropped[0] = this.instances.remove(pId);
		this.configs.remove(pId);
		if (!config.isActive())
		{
			throw new ConnectorException("Connector '" + pId + "' is inactive");
		}
		if (config.getProblem() != null)
		{
			throw new ConnectorException("Connector '" + pId + "': " + config.getProblem());
		}
		final String clearSecret = pSource.clearSecretProblem(pId);
		if (clearSecret != null)
		{
			throw new ConnectorException("Connector '" + pId + "': " + clearSecret);
		}
		try
		{
			Egress.checkBaseUrl(config);
		}
		catch (final IllegalStateException refused)
		{
			throw new ConnectorException("Connector '" + pId + "': " + refused.getMessage());
		}
		Redaction.remember(config);
		connector = this.describeType(config.getType());
		ConnectorRegistry.validateConfiguration(connector, config, pId);
		try
		{
			connector.configure(config);
		}
		catch (final RuntimeException ex)
		{
			// configured halfway: it may already hold something
			ConnectorRegistry.close(pId, connector);
			throw new ConnectorException("Connector '" + pId + "': " + ex.getMessage(), ex);
		}
		this.instances.put(pId, connector);
		this.configs.put(pId, config);
		return connector;
	}

	/** Closes an instance the registry no longer holds; a connector that fails to close is logged and forgotten all the same. */
	private static void close(final String pId, final Connector pConnector)
	{
		if (pConnector == null)
		{
			return;
		}
		try
		{
			pConnector.close();
		}
		catch (final RuntimeException ex)
		{
			ConnectorRegistry.closeFailed(pId, ex);
		}
		catch (final LinkageError ex)
		{
			// an extension compiled against another version of the SPI
			ConnectorRegistry.closeFailed(pId, ex);
		}
	}

	private static void closeFailed(final String pId, final Throwable pFailure)
	{
		ConnectorService.log().warn(Redaction.redact("[connectors] connector '" + pId + "' did not close cleanly: "
			+ (pFailure.getMessage() == null ? pFailure.toString() : pFailure.getMessage())));
	}

	/** Enforces the descriptor contract immediately before an instance is configured and cached. */
	static void validateConfiguration(final Connector pConnector, final ConnectorConfig pConfig,
		final String pId) throws ConnectorException
	{
		final ConfigurationValidationResult validation = ConnectorConfigurationValidator.validate(
			pConnector.getDescriptor(), pConfig);
		if (validation.isValid())
		{
			return;
		}
		final StringBuilder message = new StringBuilder("Connector '").append(pId)
			.append("' configuration is invalid: ");
		boolean separator = false;
		for (final ConfigurationValidationIssue issue : validation.getIssues())
		{
			if (separator)
			{
				message.append("; ");
			}
			message.append(issue.getPropertyName() == null ? "configuration" : issue.getPropertyName())
				.append(": ").append(issue.getMessage());
			separator = true;
		}
		if (!separator)
		{
			return;
		}
		throw new ConnectorException(Redaction.redact(message.toString()));
	}

	public synchronized ConnectorConfig config(final String pId)
	{
		return this.configs.get(pId);
	}

	/**
	 * Drops every cached instance, closing what each holds open: after a configuration import, and
	 * when the module stops. The next lookup of an id builds a fresh instance.
	 */
	public void invalidate()
	{
		final Map<String, Connector> dropped;
		synchronized (this)
		{
			dropped = new LinkedHashMap<String, Connector>(this.instances);
			this.instances.clear();
			this.configs.clear();
		}
		for (final Map.Entry<String, Connector> entry : dropped.entrySet())
		{
			ConnectorRegistry.close(entry.getKey(), entry.getValue());
		}
	}

	public synchronized Map<String, Class<? extends Connector>> types()
	{
		return Collections.unmodifiableMap(new LinkedHashMap<String, Class<? extends Connector>>(this.types));
	}
}
