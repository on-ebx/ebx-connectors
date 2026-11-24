package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDefaults;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;
import com.on.ebx.connect.spi.PreviewRequest;
import com.on.ebx.connect.spi.PreviewResult;

/**
 * Read-only profile of the shared JDBC engine for an engine reached through the HiveServer2 protocol (Apache Hive,
 * Impala) on a Cloudera platform, with a user name and a password over TLS.
 * <p>
 * Such an engine has no transaction and no read-only connection mode ({@link #withoutTransactions()}), so nothing on
 * the connection backs the read-only promise: the profile exposes read operations only, and refuses a {@code SELECT}
 * that runs a program on the cluster ({@code TRANSFORM ... USING 'script'}).
 * <p>
 * HiveServer2 names the columns of a {@code SELECT *} {@code table.column}: a dotted name cannot be addressed by a
 * mapping, so the table prefix is dropped wherever a result reaches the caller, unless two columns would then share a
 * name (a join: give them aliases).
 * <p>
 * Kerberos is not handled: a URL that asks for it is reported at validation, with the two ways round (a Knox or
 * Data Warehouse endpoint that takes a password, or a JNDI DataSource of the container).
 */
public abstract class AbstractHiveServer2Connector extends JdbcConnector
{
	/** The Apache Hive JDBC driver; Impala accepts it on its HiveServer2 port. */
	public static final String APACHE_HIVE_DRIVER = "org.apache.hive.jdbc.HiveDriver";

	/** Hive's script operators: the statement starts as a SELECT and runs a command on the cluster's nodes. */
	private static final Pattern RUNS_A_SCRIPT = Pattern.compile("(?is)\\busing\\s*['\"]|\\btransform\\s*\\(");

	private static final Pattern ASKS_FOR_KERBEROS = Pattern
		.compile("(?i)[;?&](principal|krbhostfqdn|krbrealm|krbservicename|kerberosauthtype|krbauthtype)\\s*=|[;?&]authmech\\s*=\\s*1\\b|[;?&]auth\\s*=\\s*kerberos\\b");

	/** The JDBC URL prefixes this profile accepts. */
	protected abstract List<String> urlPrefixes();

	/** The driver classes this profile accepts, the default one for {@code pUrl} first. */
	protected abstract List<String> drivers(String pUrl);

	/** The dependency line of the descriptor: which driver to install, and that it is not packaged. */
	protected abstract ConnectorDependency driverDependency();

	@Override
	protected final boolean withoutTransactions()
	{
		return true;
	}

	/** Why this profile refuses {@code pSql}, or null. The single-SELECT rule itself stays with the JDBC engine. */
	static String refusal(final String pSql)
	{
		return pSql != null && RUNS_A_SCRIPT.matcher(pSql).find()
			? "the statement runs a script on the cluster (TRANSFORM / MAP / REDUCE ... USING), which a read-only connection does not allow" : null;
	}

	/**
	 * The names without their {@code table.} prefix, or null when nothing is to change: no name has one, or two columns
	 * would then share a name.
	 */
	static List<String> unqualified(final List<String> pNames)
	{
		boolean qualified = false;
		final List<String> plain = new ArrayList<String>(pNames.size());
		for (final String name : pNames)
		{
			final int dot = name == null ? -1 : name.lastIndexOf('.');
			qualified |= dot >= 0 && dot < name.length() - 1;
			plain.add(dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name);
		}
		return qualified && new HashSet<String>(plain).size() == plain.size() ? plain : null;
	}

	/** One row with its columns named without their table prefix (the same row when nothing is to change). */
	static JsonNode unqualified(final JsonNode pRow)
	{
		if (pRow == null || !pRow.isObject()) return pRow;
		final List<String> names = new ArrayList<String>();
		for (final Iterator<String> iterator = pRow.fieldNames(); iterator.hasNext();)
		{
			names.add(iterator.next());
		}
		final List<String> plain = AbstractHiveServer2Connector.unqualified(names);
		if (plain == null) return pRow;
		final ObjectNode row = Json.object();
		for (int i = 0; i < names.size(); i++)
		{
			row.set(plain.get(i), pRow.get(names.get(i)));
		}
		return row;
	}

	private void checkStatement(final Map<String, String> pInputs) throws ConnectorException
	{
		final String reason = AbstractHiveServer2Connector.refusal(pInputs == null ? null : pInputs.get("sql"));
		if (reason != null) throw new ConnectorException(this.getLabel() + ": " + reason);
	}

	private boolean offers(final String pOperation)
	{
		for (final Operation operation : this.getOperations())
		{
			if (operation.getName().equals(pOperation)) return true;
		}
		return false;
	}

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null)
		{
			if (pConfig.getProperty("driver", null) == null && pConfig.getProperty("jndi", null) == null)
			{
				pConfig.setProperty("driver", this.drivers(pConfig.getBaseUrl()).get(0));
			}
			if (pConfig.getProperty("max.rows", null) == null) pConfig.setProperty("max.rows", "1000");
			if (pConfig.getProperty("fetch.size", null) == null) pConfig.setProperty("fetch.size", "500");
		}
		super.configure(pConfig);
	}

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult common = super.validateConfiguration(pConfig);
		if (pConfig == null) return common;
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(common.getIssues());
		final String jndi = pConfig.getProperty("jndi", null);
		if (jndi != null && !jndi.trim().isEmpty())
		{
			// the container's DataSource carries the URL, the driver and the authentication
			for (final Iterator<ConfigurationValidationIssue> iterator = issues.iterator(); iterator.hasNext();)
			{
				if ("baseUrl".equals(iterator.next().getPropertyName())) iterator.remove();
			}
			return new ConfigurationValidationResult(issues);
		}
		final String url = pConfig.getBaseUrl() == null ? "" : pConfig.getBaseUrl().trim();
		boolean prefixed = false;
		for (final String prefix : this.urlPrefixes())
		{
			prefixed |= url.startsWith(prefix);
		}
		if (!prefixed)
		{
			issues.add(new ConfigurationValidationIssue("baseUrl", "Must start with " + AbstractHiveServer2Connector.either(this.urlPrefixes())
				+ ", or property jndi must name a DataSource"));
			return new ConfigurationValidationResult(issues);
		}
		final String lower = url.toLowerCase(Locale.ROOT);
		if (!lower.matches(".*[;?&]ssl\\s*=\\s*(true|1)\\b.*"))
		{
			issues.add(new ConfigurationValidationIssue("baseUrl",
				"The connection must be encrypted: add ssl=true (Apache Hive driver) or SSL=1 (Cloudera driver) to the URL"));
		}
		if (ASKS_FOR_KERBEROS.matcher(url).find())
		{
			issues.add(new ConfigurationValidationIssue("baseUrl", "Kerberos is not handled by this connection. Use an endpoint that takes"
				+ " a user name and a password (a Knox gateway, a Cloudera Data Warehouse virtual warehouse, LDAP), or name a JNDI DataSource"
				+ " of the container in property jndi"));
		}
		final List<String> drivers = this.drivers(url);
		if (!drivers.contains(pConfig.getProperty("driver", drivers.get(0))))
		{
			issues.add(new ConfigurationValidationIssue("driver", "For this URL the driver must be " + AbstractHiveServer2Connector.either(drivers)));
		}
		return new ConfigurationValidationResult(issues);
	}

	private static String either(final List<String> pValues)
	{
		final StringBuilder text = new StringBuilder();
		for (int i = 0; i < pValues.size(); i++)
		{
			text.append(i == 0 ? "" : " or ").append(pValues.get(i));
		}
		return text.toString();
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if (!this.offers(pOperation))
		{
			throw new ConnectorException(this.getLabel() + " is read-only; operation '" + pOperation + "' is not permitted");
		}
		if (!"query".equals(pOperation)) return super.execute(pContext, pOperation, pInputs, pPayload);
		this.checkStatement(pInputs);
		final JsonNode result = super.execute(pContext, pOperation, pInputs, pPayload);
		if (result != null && result.path("rows").isArray())
		{
			final ArrayNode rows = (ArrayNode) result.get("rows");
			for (int i = 0; i < rows.size(); i++)
			{
				rows.set(i, AbstractHiveServer2Connector.unqualified(rows.get(i)));
			}
		}
		return result;
	}

	@Override
	public Connector.RowStreamResult readRows(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final String pItemsPath, final int pMax, final Connector.RowSink pSink) throws ConnectorException
	{
		if (!"query".equals(pOperation)) return super.readRows(pContext, pOperation, pInputs, pItemsPath, pMax, pSink);
		this.checkStatement(pInputs);
		return super.readRows(pContext, pOperation, pInputs, pItemsPath, pMax, new Connector.RowSink()
		{
			@Override public boolean accept(final JsonNode pRow) { return pSink.accept(AbstractHiveServer2Connector.unqualified(pRow)); }
			@Override public boolean onHandled(final Connector.HandledListener pListener) { return pSink.onHandled(pListener); }
			@Override public void endOfRows() { pSink.endOfRows(); }
			@Override public void notice(final String pMessage) { pSink.notice(pMessage); }
		});
	}

	@Override
	public PreviewResult preview(final ConnectorContext pContext, final PreviewRequest pRequest) throws ConnectorException
	{
		if (pRequest != null && "query".equals(pRequest.getOperation()))
		{
			final String reason = AbstractHiveServer2Connector.refusal(pRequest.getInputs() == null ? null : pRequest.getInputs().get("sql"));
			if (reason != null) return PreviewResult.unsupported(this.getLabel() + ": " + reason);
		}
		final PreviewResult result = super.preview(pContext, pRequest);
		if (result == null || !result.isSupported() || result.getRecords() == null) return result;
		final List<JsonNode> records = new ArrayList<JsonNode>(result.getRecords().size());
		for (final JsonNode record : result.getRecords())
		{
			records.add(AbstractHiveServer2Connector.unqualified(record));
		}
		return PreviewResult.of(records, result.isTruncated());
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		this.checkStatement(pInputs);
		final List<FieldInfo> fields = super.describeFields(pContext, pOperation, pInputs);
		// the fields of a query are paths under rows[0]: the same renaming as the rows themselves
		final String prefix = "rows[0].";
		final List<String> names = new ArrayList<String>(fields.size());
		for (final FieldInfo field : fields)
		{
			if (field.getPath() == null || !field.getPath().startsWith(prefix)) return fields;
			names.add(field.getPath().substring(prefix.length()));
		}
		final List<String> plain = AbstractHiveServer2Connector.unqualified(names);
		if (plain == null) return fields;
		final List<FieldInfo> renamed = new ArrayList<FieldInfo>(fields.size());
		for (int i = 0; i < fields.size(); i++)
		{
			final FieldInfo field = fields.get(i);
			renamed.add(new FieldInfo(prefix + plain.get(i), plain.get(i), field.getType(), field.getSample()));
		}
		return renamed;
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder descriptor = ConnectorDescriptor.builder(this.getType(), this.getLabel(), "DATA_PLATFORMS")
			.description(this.description()).authentication(AuthenticationMethod.BASIC)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.DESCRIBE_OBJECT).capability(ConnectorCapability.SCHEMA_DISCOVERY)
			.capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ)
			.apiVersion("HiveServer2 protocol over JDBC; live endpoint acceptance required")
			.dependency(this.driverDependency())
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 1000, 10000, null))
			.defaults(ConnectorDefaults.standard())
			.configurationProperty(AbstractHiveServer2Connector.property("jndi"))
			.configurationProperty(AbstractHiveServer2Connector.property("driver"))
			.configurationProperty(AbstractHiveServer2Connector.property("schema"))
			.configurationProperty(ConfigurationPropertyDefinition.builder("max.rows", ConfigurationPropertyType.INTEGER).required(false).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("fetch.size", ConfigurationPropertyType.INTEGER).required(false).build());
		for (final Operation operation : this.getOperations())
		{
			descriptor.direction(ConnectorDirection.INBOUND).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(),
				operation.getDescription(), Collections.singleton(ConnectorDirection.INBOUND), Collections.<ConnectorCapability>emptySet()));
		}
		return descriptor.build();
	}

	/** The sentence of the catalogue card. */
	protected abstract String description();

	private static ConfigurationPropertyDefinition property(final String pName)
	{
		return ConfigurationPropertyDefinition.builder(pName, ConfigurationPropertyType.STRING).required(false).build();
	}
}
