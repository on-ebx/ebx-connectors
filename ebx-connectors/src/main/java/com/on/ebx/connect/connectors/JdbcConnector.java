package com.on.ebx.connect.connectors;

import java.sql.Connection;
import java.sql.CallableStatement;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.DiscoveredObject;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.ObjectDescription;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PreviewRequest;
import com.on.ebx.connect.spi.PreviewResult;

/**
 * Any database with a JDBC driver on the Tomcat classpath (PostgreSQL, H2, Oracle, SQL Server,
 * MySQL, Azure Synapse / SQL Database, Redshift...). {@code baseUrl} = JDBC URL
 * ({@code jdbc:postgresql://host/db}), BASIC user / password, optional property {@code driver}
 * (class name, needed for old drivers), {@code schema}, {@code quote} (identifier quote, default
 * from the driver), {@code max.rows}.
 * <ul>
 * <li>{@code query} (SEARCH): {@code sql} with {@code :name} parameters bound from the inputs.</li>
 * <li>{@code get} (READ): {@code table}, {@code key}, {@code value}.</li>
 * <li>{@code insert} / {@code update} / {@code upsert} (WRITE): the outbound document into
 * {@code table}; {@code key} = key columns (comma-separated); upsert = UPDATE then INSERT when
 * no row was updated. Sub-objects are stored as JSON text.</li>
 * <li>{@code execute} (WRITE): any statement ({@code sql}) with {@code :name} parameters from the
 * inputs and the outbound document → {@code {affectedRows}}.</li>
 * </ul>
 */
public class JdbcConnector implements Connector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("query", "SQL query with :named parameters bound from the inputs (input sql)", "sql"),
		Operation.read("get", "One row by key (inputs table, key, value)", "table", "key", "value"),
		Operation.write("insert", "INSERT the outbound document (input table)", "table"),
		Operation.write("update", "UPDATE the outbound document on key columns (inputs table, key)", "table", "key"),
		Operation.write("upsert", "UPDATE then INSERT when missing (inputs table, key)", "table", "key"),
		Operation.write("procedure", "Call one stored procedure with bounded typed IN/OUT parameters (inputs procedure or call, parameters)", "parameters"),
		Operation.write("execute", "Any statement with :named parameters (input sql)", "sql"),
		Operation.search("catalog", "Schema inventory as a document: tables and views with their columns "
			+ "(inputs source = catalog source id echoed on every item, schemas = comma-separated list (default: property schema, else all), "
			+ "types = TABLE,VIEW, rowCounts = true to COUNT(*) every table)", "source"),
		Operation.search("profile", "Column statistics of one or every table: rows, nulls, distinct, min, max, mean, lengths "
			+ "(inputs source = catalog source id, tables = qualified names comma-separated or * for the whole inventory, "
			+ "schemas as for catalog, where = optional SQL filter; budget: maxTables, maxSeconds, dryRun = statements only)", "source"),
		Operation.search("sample", "Distinct sampled values of the text columns of one or every table, for classification "
			+ "(inputs source, tables or * , schemas, limit = rows read per table, default 50)", "source"));

	private static final Pattern PARAMETER = Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)");
	private static final int MAX_FETCH_SIZE = 10000;
	private static final int MAX_BATCH_SIZE = 1000;
	private static final int DEFAULT_MAX_ROWS = 1000;
	private static final int MAX_PROCEDURE_PARAMETERS = 64;
	private static final int MAX_PROCEDURE_DEFINITION_BYTES = 16384;
	private static final int MAX_PROCEDURE_OUTPUT_BYTES = 65536;
	private static final Pattern PROCEDURE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*(?:\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}");
	private static final Pattern CALL_SQL = Pattern.compile("(?i)^\\{\\s*(?:\\?\\s*=\\s*)?call\\s+"
		+ "[A-Za-z_][A-Za-z0-9_$#]*(?:\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}\\s*"
		+ "\\(\\s*(?:\\?\\s*(?:,\\s*\\?\\s*)*)?\\)\\s*\\}$");

	private ConnectorConfig config;

	/**
	 * MySQL-family drivers expose databases as JDBC catalogs rather than schemas.
	 * Dedicated profiles may opt into that metadata dialect without changing the
	 * generic/schema-oriented JDBC contract.
	 */
	protected boolean usesCatalogMetadata()
	{
		return false;
	}

	/**
	 * HiveServer2-family engines (Apache Hive, Spark Thrift Server, Impala) have no transaction to roll back and no
	 * read-only connection mode: the Apache Hive JDBC driver throws on {@code setReadOnly(true)}, {@code commit()} and
	 * {@code rollback()}. A profile that answers {@code true} here gets its reads without those three calls being
	 * required; it must then expose read operations only and check the statement text itself, since no connection
	 * mode backs the read-only promise any more.
	 */
	protected boolean withoutTransactions()
	{
		return false;
	}

	@Override
	public String getType()
	{
		return "jdbc";
	}

	@Override
	public String getLabel()
	{
		return "JDBC database (generic)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("DATA_PLATFORMS");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC).capability(com.on.ebx.connect.spi.ConnectorCapability.SCHEMA_DISCOVERY).capability(com.on.ebx.connect.spi.ConnectorCapability.PREVIEW_RECORDS).capability(com.on.ebx.connect.spi.ConnectorCapability.TRANSACTIONS).apiVersion("JDBC driver-defined").dependency(new com.on.ebx.connect.spi.ConnectorDependency("Runtime JDBC driver or JNDI DataSource", null, true, "Driver for the selected database, or a container-managed DataSource")).pagination(new com.on.ebx.connect.spi.PaginationDescriptor(com.on.ebx.connect.spi.PaginationDescriptor.Strategy.OFFSET, 1000, 0, null)).configurationProperty(property("driver", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("jndi", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("max.rows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("max.rows.hard", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("fetch.size", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("batch.size", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, false)).configurationProperty(property("quote", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false)).configurationProperty(property("schema", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, false));
		return b.build();
	}
	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor(final String c)
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), c);
		for (final com.on.ebx.connect.spi.Operation op:getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = op.getKind() == com.on.ebx.connect.spi.Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b;
	}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition property(final String n,final com.on.ebx.connect.spi.ConfigurationPropertyType t,final boolean r){return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n,t).required(r).build();}

	@Override
	public List<Operation> getOperations()
	{
		return JdbcConnector.OPERATIONS;
	}

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		this.config = pConfig;
	}

	Connection connect() throws ConnectorException
	{
		final String url = this.config.getBaseUrl();
		final String jndi = this.config.getProperty("jndi", url != null && url.startsWith("java:") ? url : null);
		if (jndi != null)
		{
			com.on.ebx.connect.core.JdbcGuard.checkJndiName(jndi);
			try
			{
				final Object resource = new InitialContext().lookup(jndi);
				if (!(resource instanceof DataSource)) throw new ConnectorException("JDBC: JNDI resource is not a DataSource: " + jndi);
				final DataSource dataSource = (DataSource) resource;
				return this.config.getUsername() == null || this.config.getUsername().isEmpty()
					? dataSource.getConnection() : dataSource.getConnection(this.config.getUsername(), this.config.getPassword());
			}
			catch (final NamingException ex)
			{
				throw new ConnectorException("JDBC: cannot resolve JNDI DataSource " + jndi + ": " + ex.getMessage(), ex);
			}
		catch (final SQLException ex)
		{
			throw JdbcConnector.sqlFailure("DataSource connection", ex);
			}
		}
		if (url == null || !url.startsWith("jdbc:"))
		{
			throw new ConnectorException("JDBC: baseUrl must be a JDBC URL, or property jndi must name a DataSource");
		}
		final String driver = this.config.getProperty("driver", null);
		// a connector row is data a designer edits: what it makes the server load or run is checked before anything is loaded
		com.on.ebx.connect.core.JdbcGuard.checkUrl(url);
		com.on.ebx.connect.core.JdbcGuard.checkDriver(url, driver);
		final Map<String, String> driverProperties = new java.util.LinkedHashMap<String, String>();
		for (final Map.Entry<String, String> property : this.config.getProperties().entrySet())
		{
			if (property.getKey().startsWith("jdbc."))
			{
				driverProperties.put(property.getKey().substring(5), property.getValue());
			}
		}
		com.on.ebx.connect.core.JdbcGuard.checkProperties(driverProperties);
		try
		{
			if (driver != null)
			{
				Class.forName(driver, true, JdbcConnector.class.getClassLoader());
			}
			final Properties properties = new Properties();
			if (this.config.getUsername() != null && !this.config.getUsername().isEmpty())
			{
				properties.put("user", this.config.getUsername());
			}
			if (this.config.getPassword() != null && !this.config.isPlaceholder(this.config.getPassword()))
			{
				properties.put("password", this.config.getPassword());
			}
			properties.putAll(driverProperties);
			DriverManager.setLoginTimeout(Math.max(1, this.config.getConnectTimeoutMs() / 1000));
			return DriverManager.getConnection(url, properties);
		}
		catch (final ClassNotFoundException ex)
		{
			throw new ConnectorException("JDBC driver " + driver
				+ " is not available. Install it in the EBX-Connectors runtime-libs bundle, rebuild the WAR, and restart EBX", ex);
		}
		catch (final SQLException ex)
		{
			throw JdbcConnector.sqlFailure("connection", ex);
		}
	}

	private int fetchSize(final Map<String, String> pInputs)
	{
		final String requested = pInputs == null ? null : pInputs.get("fetchSize");
		return boundedInt(requested == null ? this.config.getProperty("fetch.size", "100") : requested, 1, MAX_FETCH_SIZE, 100);
	}

	private int batchSize()
	{
		return boundedInt(this.config.getProperty("batch.size", "100"), 1, MAX_BATCH_SIZE, 100);
	}

	/**
	 * Rows a {@code query}/{@code procedure} call may return. A caller-supplied {@code max} input is honoured
	 * up to the connector's configurable hard ceiling ({@code max.rows.hard}, default unbounded) instead of a
	 * fixed 10,000 - a 3-million-row source used to be silently cut to 10,000 rows with no warning anywhere
	 * (audited 2026-09-28). With no {@code max} input, the connector's own default ({@code max.rows}, default
	 * 1000) applies, bounded the same way. Pure: no connection, no I/O, safe to unit test.
	 */
	static int maxRows(final String pRequestedMax, final String pConfiguredDefault, final String pConfiguredHardCeiling)
	{
		final int hardCeiling = boundedInt(pConfiguredHardCeiling, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
		final String requested = pRequestedMax == null || pRequestedMax.trim().isEmpty() ? pConfiguredDefault : pRequestedMax.trim();
		return boundedInt(requested, 1, hardCeiling, DEFAULT_MAX_ROWS);
	}

	private static int boundedInt(final String pValue, final int pMin, final int pMax, final int pDefault)
	{
		try { return Math.max(pMin, Math.min(pMax, Integer.parseInt(pValue))); }
		catch (final RuntimeException ex) { return pDefault; }
	}

	private void makeReadOnly(final Connection pConnection) throws SQLException
	{
		if (this.withoutTransactions())
		{
			// asked for, never required: the Apache Hive driver answers "Enabling read-only mode not supported"
			try { pConnection.setReadOnly(true); } catch (final SQLException ignored) { }
			return;
		}
		pConnection.setReadOnly(true);
		pConnection.setAutoCommit(false);
		if (!pConnection.isReadOnly()) throw new SQLException("JDBC driver refused read-only mode");
	}

	/** Ends a read: a rollback, so that nothing is ever committed; an engine without transactions has nothing to roll back. */
	private void endRead(final Connection pConnection) throws SQLException
	{
		if (!this.withoutTransactions())
		{
			pConnection.rollback();
			return;
		}
		try { pConnection.rollback(); } catch (final SQLException ignored) { }
	}

	/**
	 * Read-only mode for the {@code query}/{@code get} operations when the driver grants it; when it does not
	 * (H2 over TCP reports {@code isReadOnly()=false}, the same case the catalog path already tolerates), the
	 * read still runs, inside a transaction that is never committed: {@code query} only ever accepts one
	 * {@code SELECT} ({@link #isSafePreviewSelect}), {@code get} builds its own {@code SELECT}, and both roll back
	 * before returning. Before this, every {@code query} on such a driver answered a bare "JDBC query failed"
	 * (MDM Factory landing flows on an H2 source, 2026-09-24).
	 */
	private void readOnlyOrGuarded(final Connection pConnection) throws SQLException
	{
		try
		{
			this.makeReadOnly(pConnection);
		}
		catch (final SQLException refused)
		{
			pConnection.setAutoCommit(false);
		}
	}

	/** Opens and closes a connection and reads JDBC metadata; no SQL is prepared or executed. */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.currentTimeMillis();
		Connection connection = null;
		try
		{
			connection = this.connect();
			final DatabaseMetaData metadata = connection.getMetaData();
			final String product = metadata == null ? null : metadata.getDatabaseProductName();
			final String version = metadata == null ? null : metadata.getDatabaseProductVersion();
			final StringBuilder message = new StringBuilder("Connected");
			if (product != null && !product.trim().isEmpty())
			{
				message.append(" to ").append(product.trim());
				if (version != null && !version.trim().isEmpty())
				{
					message.append(' ').append(version.trim());
				}
			}
			return ConnectionTestResult.success(message.toString(),
				System.currentTimeMillis() - started);
		}
		catch (final SQLException ex)
		{
			throw JdbcConnector.sqlFailure("metadata check", ex);
		}
		finally
		{
			if (connection != null)
			{
				try
				{
					connection.close();
				}
				catch (final SQLException ignored)
				{
					// The connectivity result is based on the completed metadata check.
				}
			}
		}
	}

	/** Executes a bounded table sample or an explicitly supplied, single SELECT under JDBC read-only mode. */
	@Override
	public PreviewResult preview(final ConnectorContext pContext, final PreviewRequest pRequest)
		throws ConnectorException
	{
		if (pRequest == null)
		{
			return PreviewResult.unsupported("JDBC preview requires a discovered table or the read-only query operation");
		}
		final boolean queryPreview = "query".equals(pRequest.getOperation());
		final String requestedSql = pRequest.getInputs().get("sql");
		if (queryPreview && !JdbcConnector.isSafePreviewSelect(requestedSql))
		{
			return PreviewResult.unsupported("JDBC preview requires one explicit SELECT statement without locking or INTO clauses");
		}
		if (!queryPreview && (pRequest.getObjectId() == null || pRequest.getObjectId().trim().isEmpty()))
		{
			return PreviewResult.unsupported("JDBC table preview requires a discovered table name");
		}
		Connection connection = null;
		PreparedStatement statement = null;
		ResultSet resultSet = null;
		try
		{
			connection = this.connect();
			this.makeReadOnly(connection);
			final List<Object> bound = new ArrayList<Object>();
			final String bindable = queryPreview
				? JdbcConnector.bind(requestedSql, JdbcConnector.values(pRequest.getInputs(), null), bound)
				: "SELECT * FROM " + this.table(connection, pRequest.getObjectId().trim());
			statement = connection.prepareStatement(bindable);
			statement.setQueryTimeout(Math.max(1, this.config.getReadTimeoutMs() / 1000));
			final int fetchLimit = pRequest.getLimit() + 1;
			statement.setMaxRows(fetchLimit);
			statement.setFetchSize(Math.min(fetchLimit, this.fetchSize(pRequest.getInputs())));
			JdbcConnector.setParameters(statement, bound);
			resultSet = statement.executeQuery();
			final ObjectNode result = JdbcConnector.rows(resultSet, fetchLimit);
			final ArrayNode rows = (ArrayNode) result.get("rows");
			final boolean truncated = rows.size() > pRequest.getLimit();
			final List<JsonNode> records = new ArrayList<JsonNode>();
			for (int i = 0; i < rows.size() && i < pRequest.getLimit(); i++) records.add(rows.get(i));
			return PreviewResult.of(records, truncated);
		}
		catch (final SQLException ex)
		{
			throw JdbcConnector.sqlFailure("preview", ex);
		}
		finally
		{
			if (resultSet != null) try { resultSet.close(); } catch (final SQLException ignored) { }
			if (statement != null) try { statement.close(); } catch (final SQLException ignored) { }
			if (connection != null)
			{
				try { connection.rollback(); } catch (final SQLException ignored) { }
				try { connection.close(); } catch (final SQLException ignored) { }
			}
		}
	}

	static boolean isSafePreviewSelect(final String pSql)
	{
		if (pSql == null) return false;
		final String sql = pSql.trim();
		if (!sql.matches("(?is)^select\\b[\\s\\S]*")) return false;
		if (sql.indexOf(';') >= 0) return false;
		return !sql.matches("(?is)[\\s\\S]*\\bfor\\s+(update|share)\\b[\\s\\S]*")
			&& !sql.matches("(?is)[\\s\\S]*\\binto\\b[\\s\\S]*");
	}

	private static ConnectorException sqlFailure(final String pAction, final SQLException pError)
	{
		final String state = pError.getSQLState();
		final StringBuilder message = new StringBuilder("JDBC ").append(pAction).append(" failed");
		if (state != null && !state.trim().isEmpty()) message.append(" (SQLState ").append(state.trim()).append(')');
		if (pError.getErrorCode() != 0) message.append(" [vendor code ").append(pError.getErrorCode()).append(']');
		return new ConnectorException(message.toString(), pError);
	}

	private String quote(final Connection pConnection, final String pIdentifier) throws SQLException
	{
		if (pIdentifier == null || pIdentifier.length() > 768)
		{
			throw new SQLException("Invalid JDBC identifier");
		}
		final String[] parts = pIdentifier.split("\\.", -1);
		if (parts.length == 0 || parts.length > 3)
		{
			throw new SQLException("Invalid qualified JDBC identifier");
		}
		for (final String part : parts)
		{
			if (part.isEmpty() || part.length() > 256 || containsControl(part))
			{
				throw new SQLException("Invalid JDBC identifier component");
			}
		}
		String quote = this.config.getProperty("quote", null);
		if (quote == null)
		{
			quote = pConnection.getMetaData().getIdentifierQuoteString();
		}
		if (quote == null || quote.trim().isEmpty() || "none".equalsIgnoreCase(quote))
		{
			if (!pIdentifier.matches("[A-Za-z_][A-Za-z0-9_$#]*(?:\\.[A-Za-z_][A-Za-z0-9_$#]*){0,2}"))
			{
				throw new SQLException("JDBC driver has no identifier quoting and the identifier is not portable");
			}
			return pIdentifier;
		}
		if (quote.length() > 2 || containsControl(quote) || quote.matches(".*[A-Za-z0-9.;].*"))
		{
			throw new SQLException("Unsafe JDBC identifier quote configured by driver");
		}
		final String closeQuote = "[".equals(quote) ? "]" : quote;
		final StringBuilder sb = new StringBuilder();
		for (final String part : parts)
		{
			sb.append(sb.length() > 0 ? "." : "").append(quote)
				.append(part.replace(closeQuote, closeQuote + closeQuote)).append(closeQuote);
		}
		return sb.toString();
	}

	private static boolean containsControl(final String pValue)
	{
		for (int i = 0; i < pValue.length(); i++) if (Character.isISOControl(pValue.charAt(i))) return true;
		return false;
	}

	private String table(final Connection pConnection, final String pTable) throws SQLException
	{
		final String schema = this.config.getProperty("schema", null);
		return this.quote(pConnection, schema != null && !pTable.contains(".") ? schema + "." + pTable : pTable);
	}

	/** {@code :name} placeholders replaced by {@code ?}, values collected in order. */
	static String bind(final String pSql, final Map<String, Object> pValues, final List<Object> pOut) throws ConnectorException
	{
		final Matcher m = JdbcConnector.PARAMETER.matcher(pSql);
		final StringBuffer sb = new StringBuffer();
		while (m.find())
		{
			if (!pValues.containsKey(m.group(1)))
			{
				throw new ConnectorException("JDBC: no value for the parameter :" + m.group(1));
			}
			pOut.add(pValues.get(m.group(1)));
			m.appendReplacement(sb, "?");
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static void setParameters(final PreparedStatement pStatement, final List<Object> pValues) throws SQLException
	{
		for (int i = 0; i < pValues.size(); i++)
		{
			final Object value = pValues.get(i);
			if (value == null)
			{
				pStatement.setNull(i + 1, java.sql.Types.NULL);
			}
			else if (value instanceof java.math.BigDecimal)
			{
				pStatement.setBigDecimal(i + 1, (java.math.BigDecimal) value);
			}
			else if (value instanceof Long)
			{
				pStatement.setLong(i + 1, (Long) value);
			}
			else if (value instanceof Integer)
			{
				pStatement.setInt(i + 1, (Integer) value);
			}
			else if (value instanceof Boolean)
			{
				pStatement.setBoolean(i + 1, (Boolean) value);
			}
			else if (value instanceof Double)
			{
				pStatement.setDouble(i + 1, (Double) value);
			}
			else if (value instanceof java.sql.Timestamp)
			{
				pStatement.setTimestamp(i + 1, (java.sql.Timestamp) value);
			}
			else
			{
				pStatement.setString(i + 1, String.valueOf(value));
			}
		}
	}

	static Object javaValue(final JsonNode pNode)
	{
		if (pNode == null || pNode.isNull())
		{
			return null;
		}
		if (pNode.isBoolean())
		{
			return pNode.asBoolean();
		}
		if (pNode.isIntegralNumber())
		{
			return pNode.asLong();
		}
		if (pNode.isNumber())
		{
			return pNode.decimalValue();
		}
		if (pNode.isValueNode())
		{
			return pNode.asText();
		}
		return Json.write(pNode);
	}

	static Object inputValue(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final String v = pValue.trim();
		if (v.matches("-?[0-9]{1,18}"))
		{
			return Long.valueOf(v);
		}
		if (v.matches("-?[0-9]+\\.[0-9]+"))
		{
			return new java.math.BigDecimal(v);
		}
		if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v))
		{
			return Boolean.valueOf(v);
		}
		return pValue;
	}

	/**
	 * Audit C2-05: the value of a parameter the engine generated from a {@code ${...}} of the sql input. A number is bound as a
	 * number only when its text is the canonical form of that number ("12", "-3", "1.50" no), so a code such as "0012" or a
	 * name such as "007" stays text and a numeric column still compares with a numeric value.
	 */
	static Object boundValue(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		if (pValue.matches("-?[1-9][0-9]{0,17}|0"))
		{
			return Long.valueOf(pValue);
		}
		if (pValue.matches("-?(?:0|[1-9][0-9]{0,17})\\.[0-9]*[1-9]"))
		{
			return new java.math.BigDecimal(pValue);
		}
		return pValue;
	}

	private static Map<String, Object> values(final Map<String, String> pInputs, final JsonNode pPayload)
	{
		final Map<String, Object> values = new LinkedHashMap<String, Object>();
		if (pPayload != null && pPayload.isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> fields = pPayload.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				values.put(field.getKey(), JdbcConnector.javaValue(field.getValue()));
			}
		}
		if (pInputs != null)
		{
			for (final Map.Entry<String, String> input : pInputs.entrySet())
			{
				values.put(input.getKey(), input.getKey().startsWith(com.on.ebx.connect.core.ValueTemplates.BOUND_PREFIX)
					? JdbcConnector.boundValue(input.getValue()) : JdbcConnector.inputValue(input.getValue()));
			}
			// Volume lot V2-D: the feed watermark keeps its declared type instead of the guess inputValue makes
			// from the text ("0012" is a string watermark, not the number 12; a timestamp is a Timestamp).
			final Object watermark = JdbcConnector.watermarkValue(pInputs);
			if (watermark != null)
			{
				values.put(JdbcConnector.WATERMARK_PARAMETER, watermark);
			}
		}
		return values;
	}

	/** Name of the named parameter (and of the input carrying its value) a feed watermark is bound through - see {@link #bindsWatermark}. */
	static final String WATERMARK_PARAMETER = "watermark";
	/** Input carrying the type of the {@code watermark} input: TIMESTAMP, NUMBER or STRING. */
	static final String WATERMARK_TYPE_INPUT = "watermarkType";

	/**
	 * Volume lot V2-D: the typed JDBC value of the {@code watermark} input when the engine also passed its
	 * {@code watermarkType}; null when either is absent (a plain {@code connectors_call} that happens to
	 * name an input {@code watermark} keeps the ordinary text-guessing binding). The value was normalised
	 * by the engine; a value that is not valid for its declared type is refused, never bound as text.
	 */
	static Object watermarkValue(final Map<String, String> pInputs)
	{
		if (pInputs == null)
		{
			return null;
		}
		final String value = pInputs.get(JdbcConnector.WATERMARK_PARAMETER);
		final com.on.ebx.connect.core.Watermark.Kind kind = com.on.ebx.connect.core.Watermark.kindNamed(pInputs.get(JdbcConnector.WATERMARK_TYPE_INPUT));
		if (value == null || kind == null)
		{
			return null;
		}
		return com.on.ebx.connect.core.Watermark.sqlValue(kind, value);
	}

	/**
	 * Volume lot V2-D: the {@code sql} input of the {@code query} operation takes the feed watermark as a
	 * bound parameter - the engine writes {@code :watermark} where the mapping said {@code ${watermark}} and
	 * passes the value through {@link #watermarkValue}; it is never concatenated into the statement.
	 */
	@Override
	public boolean bindsWatermark(final String pOperation, final String pInputName)
	{
		return "query".equals(pOperation) && "sql".equals(pInputName);
	}

	static ObjectNode rows(final ResultSet pResultSet, final int pMax) throws SQLException
	{
		final ObjectNode out = Json.object();
		final ArrayNode rows = out.putArray("rows");
		final ResultSetMetaData meta = pResultSet.getMetaData();
		while (pResultSet.next() && rows.size() < pMax)
		{
			rows.add(JdbcConnector.rowOf(pResultSet, meta));
		}
		out.put("count", rows.size());
		return out;
	}

	/**
	 * One current row of {@code pResultSet} as a document row - the exact per-column conversion
	 * {@link #rows} always used, factored out (volume lot 7a) so the streaming {@link #readRows}
	 * override below builds identical row shapes without ever materialising the whole result set into
	 * one {@link ArrayNode} first.
	 */
	private static ObjectNode rowOf(final ResultSet pResultSet, final ResultSetMetaData pMeta) throws SQLException
	{
		final ObjectNode row = Json.object();
		for (int c = 1; c <= pMeta.getColumnCount(); c++)
		{
			final Object value = pResultSet.getObject(c);
			final String name = pMeta.getColumnLabel(c) == null ? pMeta.getColumnName(c) : pMeta.getColumnLabel(c);
			if (value == null)
			{
				row.putNull(name);
			}
			else if (value instanceof java.sql.Timestamp)
			{
				row.put(name, com.on.ebx.connect.core.CatalogOperations.dateTime(((java.sql.Timestamp) value).toLocalDateTime()));
			}
			else if (value instanceof java.time.LocalDateTime)
			{
				row.put(name, com.on.ebx.connect.core.CatalogOperations.dateTime((java.time.LocalDateTime) value));
			}
			else if (value instanceof java.time.OffsetDateTime)
			{
				// what a driver returns for TIMESTAMP WITH TIME ZONE (H2 2.x)
				row.put(name, com.on.ebx.connect.core.CatalogOperations.dateTime((java.time.OffsetDateTime) value));
			}
			else if (value instanceof java.sql.Date)
			{
				row.put(name, ((java.sql.Date) value).toLocalDate().toString());
			}
			else if (value instanceof java.sql.Time)
			{
				row.put(name, value.toString());
			}
			else if (value instanceof java.sql.Clob)
			{
				final java.sql.Clob clob = (java.sql.Clob) value;
				row.put(name, clob.getSubString(1, (int) Math.min(Integer.MAX_VALUE, clob.length())));
			}
			else if (value instanceof byte[])
			{
				row.put(name, java.util.Base64.getEncoder().encodeToString((byte[]) value));
			}
			else if (value instanceof Number || value instanceof Boolean || value instanceof String)
			{
				row.set(name, Json.fromJava(value));
			}
			else
			{
				row.put(name, value.toString());
			}
		}
		return row;
	}

	/**
	 * Volume lot 7a: real streaming for the {@code query} operation - a JDBC cursor with
	 * {@code fetch.size} honoured, rows pushed to {@code pSink} one at a time, never a
	 * {@code List}/{@code ArrayNode} of every row. Every other operation falls back to the default
	 * (execute + replay) implementation, unchanged.
	 * <p>
	 * Truncation uses the same probe-row trick as {@link #boundedQuery} ({@code setMaxRows(pMax + 1)}):
	 * when the extra row comes back, the read stops there and {@code truncated} is reported with the
	 * total left <em>unknown</em> ({@code available = -1}) - counting the true total would mean reading
	 * the rest of a potentially huge result set just to report a number, defeating the point of
	 * streaming. A sink that returns {@code false} (a batch/circuit-breaker/cancellation stop, never a
	 * max-related one - the max cutoff is enforced here, before the sink ever sees more than
	 * {@code pMax} rows) is reported as an ordinary, non-truncated read of however many rows were
	 * delivered: the caller already knows why it stopped early and reports that separately.
	 * <p>
	 * Streaming conditions verified against the driver, not just requested: read-only mode is
	 * requested via {@link #readOnlyOrGuarded} exactly like the non-streaming {@code query} path, and
	 * {@code fetchSize} is set on the statement before execution. H2 streams its result sets by
	 * default regardless of these settings. PostgreSQL only streams (fetches {@code fetchSize} rows at
	 * a time from the server instead of pulling the whole result set into the driver's memory on
	 * {@code executeQuery}) when autoCommit is {@code false} <em>and</em> a non-zero fetch size is set
	 * on the statement - both already true here ({@code readOnlyOrGuarded}/{@code makeReadOnly} turns
	 * autoCommit off, and {@link #fetchSize} defaults to 100) - a forward-only, read-only
	 * {@code ResultSet} (the default holdability here) is required too, which this method never
	 * changes. Not verified against a live PostgreSQL instance in this lot; H2 was not verified either
	 * (both need a real large table and driver-level instrumentation, e.g. heap sampling during the
	 * read, to confirm live - see docs/REFERENCE.md).
	 */
	@Override
	public Connector.RowStreamResult readRows(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final String pItemsPath, final int pMax, final Connector.RowSink pSink) throws ConnectorException
	{
		if (!"query".equals(pOperation))
		{
			return Connector.super.readRows(pContext, pOperation, pInputs, pItemsPath, pMax, pSink);
		}
		final Map<String, String> inputs = pInputs == null ? java.util.Collections.<String, String>emptyMap() : pInputs;
		if (!JdbcConnector.isSafePreviewSelect(inputs.get("sql")))
		{
			throw new ConnectorException("JDBC query accepts only one read-only SELECT statement");
		}
		Connection connection = null;
		PreparedStatement statement = null;
		try
		{
			connection = this.connect();
			this.readOnlyOrGuarded(connection);
			final List<Object> bound = new ArrayList<Object>();
			final String sql = JdbcConnector.bind(AbstractFileConnector.required(inputs, "sql"), JdbcConnector.values(inputs, null), bound);
			statement = connection.prepareStatement(sql);
			statement.setQueryTimeout(Math.max(1, this.config.getReadTimeoutMs() / 1000));
			final boolean unbounded = pMax == Integer.MAX_VALUE;
			statement.setFetchSize(Math.min(unbounded ? Integer.MAX_VALUE : pMax, this.fetchSize(inputs)));
			statement.setMaxRows(unbounded ? 0 : pMax + 1); // JDBC convention: setMaxRows(0) means "no limit"
			JdbcConnector.setParameters(statement, bound);
			final ResultSet resultSet = statement.executeQuery();
			final ResultSetMetaData meta = resultSet.getMetaData();
			int delivered = 0;
			boolean truncated = false;
			while (resultSet.next())
			{
				if (!unbounded && delivered >= pMax)
				{
					// the probe row: the source holds at least one more than pMax, real total unknown
					truncated = true;
					break;
				}
				delivered++;
				if (!pSink.accept(JdbcConnector.rowOf(resultSet, meta)))
				{
					break;
				}
			}
			this.endRead(connection); // a read never commits, read-only mode granted or not (readOnlyOrGuarded)
			return new Connector.RowStreamResult(delivered, truncated, -1);
		}
		catch (final SQLException ex)
		{
			throw JdbcConnector.sqlFailure("streamed query", ex);
		}
		finally
		{
			if (statement != null)
			{
				try { statement.close(); } catch (final SQLException ignored) { /* best effort */ }
			}
			if (connection != null)
			{
				try { connection.close(); } catch (final SQLException ignored) { /* best effort */ }
			}
		}
	}

	/**
	 * Runs the already-prepared {@code query} statement and reports whether the source held more than
	 * {@code pMax} rows, without a second query: the driver is asked for one extra row ({@code setMaxRows(pMax
	 * + 1)}), and if it comes back the extra row is dropped and {@code truncated} is set - the same top-level
	 * field {@code FileFormats.parse} already sets for a file feed, read the same way by
	 * {@code ConnectorService.importDocument}. {@code pMax == Integer.MAX_VALUE} means "no configured ceiling"
	 * ({@link #maxRows}): the driver is asked for every row ({@code setMaxRows(0)}, the JDBC convention for "no
	 * limit") and truncation never applies. Pure JDBC plumbing; the truncation decision itself
	 * ({@code returned > pMax}) is exercised directly in tests via {@link #rows}.
	 */
	static ObjectNode boundedQuery(final PreparedStatement pStatement, final int pMax) throws SQLException
	{
		final boolean unbounded = pMax == Integer.MAX_VALUE;
		final int probe = unbounded ? 0 : pMax + 1; // JDBC convention: setMaxRows(0) means "no limit"
		pStatement.setMaxRows(probe);
		final ObjectNode out = JdbcConnector.rows(pStatement.executeQuery(), unbounded ? Integer.MAX_VALUE : probe);
		final ArrayNode rows = (ArrayNode) out.get("rows");
		final boolean truncated = !unbounded && rows.size() > pMax;
		if (truncated)
		{
			while (rows.size() > pMax) rows.remove(rows.size() - 1);
			out.put("count", rows.size());
		}
		out.put("truncated", truncated);
		return out;
	}

	private static final class ProcedureParameter
	{
		final String name;
		final String mode;
		final int sqlType;

		ProcedureParameter(final String pName, final String pMode, final int pSqlType)
		{
			this.name = pName;
			this.mode = pMode;
			this.sqlType = pSqlType;
		}

		boolean input() { return "IN".equals(this.mode) || "INOUT".equals(this.mode); }
		boolean output() { return "OUT".equals(this.mode) || "INOUT".equals(this.mode) || "RETURN".equals(this.mode); }
	}

	private static List<ProcedureParameter> procedureParameters(final String pJson) throws ConnectorException
	{
		if (pJson == null || pJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PROCEDURE_DEFINITION_BYTES)
			throw new ConnectorException("JDBC procedure: parameters must be a bounded JSON array");
		final JsonNode root;
		try { root = Json.parse(pJson); }
		catch (final Exception ex) { throw new ConnectorException("JDBC procedure: parameters must be a JSON array"); }
		if (root == null || !root.isArray()) throw new ConnectorException("JDBC procedure: parameters must be a JSON array");
		if (root.size() > MAX_PROCEDURE_PARAMETERS)
			throw new ConnectorException("JDBC procedure: parameter count exceeds " + MAX_PROCEDURE_PARAMETERS);
		final List<ProcedureParameter> out = new ArrayList<ProcedureParameter>();
		final java.util.Set<String> names = new java.util.HashSet<String>();
		for (int i = 0; i < root.size(); i++)
		{
			final JsonNode item = root.get(i);
			final String name = item == null ? null : item.path("name").asText(null);
			final String mode = item == null ? "" : item.path("mode").asText("IN").trim().toUpperCase(java.util.Locale.ENGLISH);
			final String type = item == null ? "" : item.path("type").asText("").trim().toUpperCase(java.util.Locale.ENGLISH);
			if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !names.add(name))
				throw new ConnectorException("JDBC procedure: every parameter needs a unique safe name");
			if (!("IN".equals(mode) || "OUT".equals(mode) || "INOUT".equals(mode) || "RETURN".equals(mode)))
				throw new ConnectorException("JDBC procedure: unsupported parameter mode for " + name);
			if ("RETURN".equals(mode) && i != 0)
				throw new ConnectorException("JDBC procedure: RETURN must be the first parameter");
			out.add(new ProcedureParameter(name, mode, procedureSqlType(type)));
		}
		return out;
	}

	private static int procedureSqlType(final String pType) throws ConnectorException
	{
		if ("VARCHAR".equals(pType) || "STRING".equals(pType)) return java.sql.Types.VARCHAR;
		if ("INTEGER".equals(pType) || "INT".equals(pType)) return java.sql.Types.INTEGER;
		if ("BIGINT".equals(pType)) return java.sql.Types.BIGINT;
		if ("DECIMAL".equals(pType) || "NUMERIC".equals(pType)) return java.sql.Types.DECIMAL;
		if ("BOOLEAN".equals(pType)) return java.sql.Types.BOOLEAN;
		if ("DATE".equals(pType)) return java.sql.Types.DATE;
		if ("TIME".equals(pType)) return java.sql.Types.TIME;
		if ("TIMESTAMP".equals(pType)) return java.sql.Types.TIMESTAMP;
		if ("BINARY".equals(pType) || "VARBINARY".equals(pType)) return java.sql.Types.VARBINARY;
		throw new ConnectorException("JDBC procedure: unsupported JDBC type; use VARCHAR, INTEGER, BIGINT, DECIMAL, BOOLEAN, DATE, TIME, TIMESTAMP, or VARBINARY");
	}

	private String procedureCall(final Connection pConnection, final Map<String, String> pInputs,
		final List<ProcedureParameter> pParameters) throws SQLException, ConnectorException
	{
		final String explicit = pInputs.get("call");
		if (explicit != null && !explicit.trim().isEmpty())
		{
			final String call = explicit.trim();
			if (call.length() > 1024 || !CALL_SQL.matcher(call).matches())
				throw new ConnectorException("JDBC procedure: call must be one JDBC call escape containing only a qualified identifier and '?' placeholders");
			int placeholders = 0;
			for (int i = 0; i < call.length(); i++) if (call.charAt(i) == '?') placeholders++;
			if (placeholders != pParameters.size()) throw new ConnectorException("JDBC procedure: call placeholder count does not match parameters");
			return call;
		}
		final String procedure = pInputs.get("procedure");
		if (procedure == null || !PROCEDURE_NAME.matcher(procedure.trim()).matches())
			throw new ConnectorException("JDBC procedure: provide a safe qualified procedure identifier or an explicit call escape");
		final boolean returns = !pParameters.isEmpty() && "RETURN".equals(pParameters.get(0).mode);
		final int arguments = pParameters.size() - (returns ? 1 : 0);
		final StringBuilder call = new StringBuilder(returns ? "{? = call " : "{call ");
		call.append(this.quote(pConnection, procedure.trim())).append('(');
		for (int i = 0; i < arguments; i++) call.append(i == 0 ? "?" : ",?");
		return call.append(")}").toString();
	}

	private JsonNode callProcedure(final Connection pConnection, final Map<String, String> pInputs,
		final JsonNode pPayload, final int pMax) throws SQLException, ConnectorException
	{
		final List<ProcedureParameter> parameters = procedureParameters(pInputs.get("parameters"));
		final String call = this.procedureCall(pConnection, pInputs, parameters);
		final Map<String, Object> values = JdbcConnector.values(pInputs, pPayload);
		final CallableStatement statement = pConnection.prepareCall(call);
		try
		{
			statement.setQueryTimeout(Math.max(1, this.config.getReadTimeoutMs() / 1000));
			statement.setMaxRows(pMax);
			statement.setFetchSize(Math.min(pMax, this.fetchSize(pInputs)));
			for (int i = 0; i < parameters.size(); i++)
			{
				final ProcedureParameter parameter = parameters.get(i);
				if (parameter.output()) statement.registerOutParameter(i + 1, parameter.sqlType);
				if (parameter.input())
				{
					if (!values.containsKey(parameter.name))
						throw new ConnectorException("JDBC procedure: no value for IN parameter " + parameter.name);
					final Object value = values.get(parameter.name);
					if (value == null) statement.setNull(i + 1, parameter.sqlType);
					else statement.setObject(i + 1, value, parameter.sqlType);
				}
			}
			final boolean resultSet = statement.execute();
			final ObjectNode out = Json.object();
			if (resultSet) out.set("result", JdbcConnector.rows(statement.getResultSet(), pMax));
			else out.put("affectedRows", statement.getUpdateCount());
			final ObjectNode outputs = out.putObject("out");
			for (int i = 0; i < parameters.size(); i++) if (parameters.get(i).output())
			{
				final Object value = statement.getObject(i + 1);
				if (value == null) outputs.putNull(parameters.get(i).name);
				else if (value instanceof byte[])
				{
					if (((byte[]) value).length > MAX_PROCEDURE_OUTPUT_BYTES)
						throw new ConnectorException("JDBC procedure: OUT value exceeds the bounded result limit");
					outputs.put(parameters.get(i).name, java.util.Base64.getEncoder().encodeToString((byte[]) value));
				}
				else
				{
					final String rendered = String.valueOf(value);
					if (rendered.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PROCEDURE_OUTPUT_BYTES)
						throw new ConnectorException("JDBC procedure: OUT value exceeds the bounded result limit");
					if (value instanceof Number || value instanceof Boolean || value instanceof String)
						outputs.set(parameters.get(i).name, Json.fromJava(value));
					else outputs.put(parameters.get(i).name, rendered);
				}
			}
			pConnection.commit();
			return out;
		}
		finally { statement.close(); }
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		final Map<String, String> inputs = pInputs == null ? java.util.Collections.<String, String>emptyMap() : pInputs;
		final int max = maxRows(inputs.get("max"), this.config.getProperty("max.rows", String.valueOf(DEFAULT_MAX_ROWS)),
			this.config.getProperty("max.rows.hard", String.valueOf(Integer.MAX_VALUE)));
		Connection connection = null;
		try
		{
			connection = this.connect();
			if ("catalog".equals(pOperation) || "profile".equals(pOperation) || "sample".equals(pOperation))
			{
				// Metadata and aggregate SELECTs built by this class only: read-only mode is requested but a driver
				// that ignores it (H2 over TCP reports isReadOnly()=false) must not block the inventory.
				try
				{
					this.makeReadOnly(connection);
				}
				catch (final SQLException ignored)
				{
					connection.setAutoCommit(false);
				}
				return "catalog".equals(pOperation) ? this.catalog(connection, inputs)
					: "profile".equals(pOperation) ? this.profile(connection, inputs) : this.sample(connection, inputs);
			}
			final boolean readOperation = "query".equals(pOperation) || "get".equals(pOperation);
			if (readOperation) this.readOnlyOrGuarded(connection);
			else connection.setAutoCommit(false);
			if ("procedure".equals(pOperation))
			{
				connection.setAutoCommit(false);
				return this.callProcedure(connection, inputs, pPayload, max);
			}
			if ("query".equals(pOperation) || "execute".equals(pOperation))
			{
				if ("query".equals(pOperation) && !JdbcConnector.isSafePreviewSelect(inputs.get("sql")))
				{
					throw new ConnectorException("JDBC query accepts only one read-only SELECT statement");
				}
				final List<Object> bound = new ArrayList<Object>();
				final String sql = JdbcConnector.bind(AbstractFileConnector.required(inputs, "sql"), JdbcConnector.values(inputs, pPayload), bound);
				com.on.ebx.connect.core.JdbcGuard.checkStatement(this.config.getBaseUrl(), sql);
				final PreparedStatement statement = connection.prepareStatement(sql);
				try
				{
					statement.setQueryTimeout(Math.max(1, this.config.getReadTimeoutMs() / 1000));
					statement.setFetchSize(Math.min(max, this.fetchSize(inputs)));
					JdbcConnector.setParameters(statement, bound);
					if ("query".equals(pOperation))
					{
						final ObjectNode out = JdbcConnector.boundedQuery(statement, max);
						this.endRead(connection); // a read never commits, read-only mode granted or not (readOnlyOrGuarded)
						return out.get("count").asInt() == 0 ? null : out;
					}
					final boolean hasResult = statement.execute();
					final ObjectNode out = Json.object();
					if (hasResult)
					{
						out.setAll(JdbcConnector.rows(statement.getResultSet(), max));
					}
					else
					{
						out.put("affectedRows", statement.getUpdateCount());
					}
						connection.commit();
						return out;
				}
				finally
				{
					statement.close();
				}
			}
			if ("get".equals(pOperation))
			{
				final String sql = "SELECT * FROM " + this.table(connection, AbstractFileConnector.required(inputs, "table")) + " WHERE "
						+ this.quote(connection, AbstractFileConnector.required(inputs, "key")) + " = ?";
				final PreparedStatement statement = connection.prepareStatement(sql);
				try
				{
					statement.setMaxRows(1);
					statement.setFetchSize(1);
					JdbcConnector.setParameters(statement, Arrays.asList(JdbcConnector.inputValue(AbstractFileConnector.required(inputs, "value"))));
					final JsonNode row = Json.at(JdbcConnector.rows(statement.executeQuery(), 1), "rows[0]");
					this.endRead(connection);
					return row;
				}
				finally
				{
					statement.close();
				}
			}
			if ("insert".equals(pOperation) || "update".equals(pOperation) || "upsert".equals(pOperation))
			{
				if (pPayload == null || !(pPayload.isObject() || pPayload.isArray()) || pPayload.size() == 0)
				{
					throw new ConnectorException("JDBC " + pOperation + ": an outbound document or document array is required");
				}
				final List<JsonNode> documents = new ArrayList<JsonNode>();
				if (pPayload.isArray()) for (final JsonNode document : pPayload) documents.add(document);
				else documents.add(pPayload);
				if (documents.size() > this.batchSize()) throw new ConnectorException("JDBC " + pOperation + ": batch exceeds configured limit " + this.batchSize());
				for (final JsonNode document : documents) if (!document.isObject() || document.size() == 0) throw new ConnectorException("JDBC " + pOperation + ": every batch item must be a non-empty object");
				final String table = this.table(connection, AbstractFileConnector.required(inputs, "table"));
				final List<String> keys = new ArrayList<String>();
				if (!"insert".equals(pOperation))
				{
					for (final String key : AbstractFileConnector.required(inputs, "key").split(","))
					{
						keys.add(key.trim());
					}
				}
				final ObjectNode out = Json.object();
				int updatedTotal = 0;
				int insertedTotal = 0;
				for (final JsonNode document : documents)
				{
					int updated = 0;
					if (!"insert".equals(pOperation)) updated = this.update(connection, table, keys, document);
					updatedTotal += updated;
					if ("insert".equals(pOperation) || "upsert".equals(pOperation) && updated == 0) insertedTotal += this.insert(connection, table, document);
				}
				if (!"insert".equals(pOperation)) out.put("updated", updatedTotal);
				if ("insert".equals(pOperation) || "upsert".equals(pOperation)) out.put("inserted", insertedTotal);
				out.put("processed", documents.size());
				connection.commit();
				return out;
			}
			throw new ConnectorException("JDBC: unknown operation '" + pOperation + "'");
		}
		catch (final SQLException ex)
		{
			if (connection != null) try { connection.rollback(); } catch (final SQLException ignored) { }
			throw JdbcConnector.sqlFailure(pOperation, ex);
		}
		catch (final ConnectorException ex)
		{
			if (connection != null) try { connection.rollback(); } catch (final SQLException ignored) { }
			throw ex;
		}
		finally
		{
			if (connection != null)
			{
				try
				{
					connection.close();
				}
				catch (final SQLException ex)
				{
					// ignore
				}
			}
		}
	}

	// ------------------------------------------------------------------ catalog / profile (data catalog feeds)

	/** One inventoried table: what {@code catalog} emits and {@code profile} iterates. */
	private static final class TableRef
	{
		final String schema;
		final String name;
		final String kind;
		final String remarks;

		TableRef(final String pSchema, final String pName, final String pKind, final String pRemarks)
		{
			this.schema = pSchema;
			this.name = pName;
			this.kind = pKind;
			this.remarks = pRemarks;
		}

		String qualifiedName()
		{
			return this.schema == null || this.schema.isEmpty() ? this.name : this.schema + "." + this.name;
		}
	}

	/** Schemas to inventory: input {@code schemas}, else property {@code schema}, else every schema the driver lists (system schemas excluded). */
	private List<String> schemasToScan(final Connection pConnection, final Map<String, String> pInputs) throws SQLException
	{
		final List<String> schemas = new ArrayList<String>();
		final String requested = pInputs.get("schemas") == null || pInputs.get("schemas").trim().isEmpty()
			? this.config.getProperty("schema", null) : pInputs.get("schemas");
		if (requested != null && !requested.trim().isEmpty() && !"*".equals(requested.trim()))
		{
			for (final String schema : requested.split(","))
			{
				if (!schema.trim().isEmpty()) schemas.add(schema.trim());
			}
			return schemas;
		}
		final ResultSet rs = this.usesCatalogMetadata()
			? pConnection.getMetaData().getCatalogs() : pConnection.getMetaData().getSchemas();
		try
		{
			while (rs.next())
			{
				final String schema = rs.getString(this.usesCatalogMetadata() ? "TABLE_CAT" : "TABLE_SCHEM");
				if (schema == null || schema.trim().isEmpty() || JdbcConnector.isSystemSchema(schema)) continue;
				schemas.add(schema);
			}
		}
		finally
		{
			rs.close();
		}
		return schemas;
	}

	private static boolean isSystemSchema(final String pSchema)
	{
		final String s = pSchema.toUpperCase(java.util.Locale.ENGLISH);
		return s.startsWith("INFORMATION_SCHEMA") || s.startsWith("PG_") || "SYS".equals(s) || "SYSTEM".equals(s)
			|| "SYSIBM".equals(s) || "MYSQL".equals(s) || "PERFORMANCE_SCHEMA".equals(s) || s.startsWith("DB_")
			|| "GUEST".equals(s) || "SNOWFLAKE".equals(s) || s.startsWith("SYS_");
	}

	private List<TableRef> tablesOf(final Connection pConnection, final List<String> pSchemas, final Map<String, String> pInputs) throws SQLException
	{
		final String types = pInputs.get("types") == null || pInputs.get("types").trim().isEmpty() ? "TABLE,VIEW" : pInputs.get("types");
		final List<String> wanted = new ArrayList<String>();
		for (final String type : types.split(",")) if (!type.trim().isEmpty()) wanted.add(type.trim().toUpperCase(java.util.Locale.ENGLISH));
		final List<TableRef> out = new ArrayList<TableRef>();
		final DatabaseMetaData meta = pConnection.getMetaData();
		for (final String schema : pSchemas)
		{
			final ResultSet rs = meta.getTables(this.usesCatalogMetadata() ? schema : null,
				this.usesCatalogMetadata() ? null : schema, "%", null);
			try
			{
				while (rs.next())
				{
					final String type = rs.getString("TABLE_TYPE") == null ? "TABLE" : rs.getString("TABLE_TYPE").toUpperCase(java.util.Locale.ENGLISH);
					final String kind = type.contains("MATERIALIZED") ? "MATERIALIZED_VIEW" : type.contains("VIEW") ? "VIEW" : type.contains("TABLE") ? "TABLE" : null;
					if (kind == null) continue;
					if (!wanted.contains(kind) && !("MATERIALIZED_VIEW".equals(kind) && wanted.contains("VIEW"))) continue;
					out.add(new TableRef(this.usesCatalogMetadata() ? rs.getString("TABLE_CAT") : rs.getString("TABLE_SCHEM"),
						rs.getString("TABLE_NAME"), kind, rs.getString("REMARKS")));
				}
			}
			finally
			{
				rs.close();
			}
		}
		return out;
	}

	/** java.sql.Types → the catalog's normalised type vocabulary. */
	static String normalisedType(final int pSqlType, final String pTypeName)
	{
		final String name = pTypeName == null ? "" : pTypeName.toUpperCase(java.util.Locale.ENGLISH);
		if (name.contains("JSON")) return "JSON";
		switch (pSqlType)
		{
			case java.sql.Types.CHAR: case java.sql.Types.VARCHAR: case java.sql.Types.LONGVARCHAR: case java.sql.Types.NCHAR:
			case java.sql.Types.NVARCHAR: case java.sql.Types.LONGNVARCHAR: case java.sql.Types.CLOB: case java.sql.Types.NCLOB:
				return "STRING";
			case java.sql.Types.TINYINT: case java.sql.Types.SMALLINT: case java.sql.Types.INTEGER: case java.sql.Types.BIGINT:
				return "INTEGER";
			case java.sql.Types.DECIMAL: case java.sql.Types.NUMERIC: case java.sql.Types.FLOAT: case java.sql.Types.REAL: case java.sql.Types.DOUBLE:
				return "DECIMAL";
			case java.sql.Types.BOOLEAN: case java.sql.Types.BIT:
				return "BOOLEAN";
			case java.sql.Types.DATE:
				return "DATE";
			case java.sql.Types.TIME: case java.sql.Types.TIME_WITH_TIMEZONE:
				return "TIME";
			case java.sql.Types.TIMESTAMP: case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
				return "TIMESTAMP";
			case java.sql.Types.BINARY: case java.sql.Types.VARBINARY: case java.sql.Types.LONGVARBINARY: case java.sql.Types.BLOB:
				return "BINARY";
			default:
				return name.contains("CHAR") || name.contains("TEXT") ? "STRING" : name.contains("INT") ? "INTEGER"
					: name.contains("NUM") || name.contains("DEC") ? "DECIMAL" : name.contains("TIMESTAMP") ? "TIMESTAMP"
					: name.contains("DATE") ? "DATE" : name.contains("BOOL") ? "BOOLEAN" : "OTHER";
		}
	}

	/**
	 * {@code catalog}: {@code {source, scannedAt, count, tables:[{source, assetKey, qualifiedName, schema, name, kind,
	 * remarks, rowCount?, scannedAt, columns:[{columnKey, name, position, dataType, nativeType, nullable, primaryKey,
	 * remarks, scannedAt}]}]}}. Keys: {@code assetKey = source:qualifiedName}, {@code columnKey = assetKey.column}.
	 */
    /** Optional vendor metadata, separate from the catalog's locally curated values. */
    protected void enrichCatalogTable(Connection connection,String schema,String table,ObjectNode item,Map<String,String> inputs) throws SQLException {}

	private JsonNode catalog(final Connection pConnection, final Map<String, String> pInputs) throws SQLException, ConnectorException
	{
		final String source = AbstractFileConnector.required(pInputs, "source").trim();
		final boolean rowCounts = "true".equalsIgnoreCase(pInputs.get("rowCounts"));
		final String scannedAt = com.on.ebx.connect.core.CatalogOperations.timestamp();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("scannedAt", scannedAt);
		final ArrayNode tables = out.putArray("tables");
		final DatabaseMetaData meta = pConnection.getMetaData();
		for (final TableRef table : this.tablesOf(pConnection, this.schemasToScan(pConnection, pInputs), pInputs))
		{
			final ObjectNode t = tables.addObject();
			final String assetKey = source + ":" + table.qualifiedName();
			t.put("source", source);
			t.put("assetKey", assetKey);
			t.put("qualifiedName", table.qualifiedName());
			t.put("schema", table.schema == null ? "" : table.schema);
			t.put("name", table.name);
			t.put("kind", table.kind);
			if (table.remarks != null && !table.remarks.trim().isEmpty()) t.put("remarks", table.remarks.trim());
			t.put("scannedAt", scannedAt);
			final java.util.Set<String> keys = new java.util.HashSet<String>();
			if ("TABLE".equals(table.kind))
			{
				final ResultSet pk = meta.getPrimaryKeys(this.usesCatalogMetadata() ? table.schema : null,
					this.usesCatalogMetadata() ? null : table.schema, table.name);
				try
				{
					while (pk.next()) keys.add(pk.getString("COLUMN_NAME"));
				}
				finally
				{
					pk.close();
				}
			}
			final ArrayNode columns = t.putArray("columns");
			final ResultSet rs = meta.getColumns(this.usesCatalogMetadata() ? table.schema : null,
				this.usesCatalogMetadata() ? null : table.schema, table.name, "%");
			try
			{
				while (rs.next())
				{
					final ObjectNode c = columns.addObject();
					final String name = rs.getString("COLUMN_NAME");
					c.put("columnKey", assetKey + "." + name);
					c.put("name", name);
					c.put("position", rs.getInt("ORDINAL_POSITION"));
					c.put("dataType", JdbcConnector.normalisedType(rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME")));
					final int size = rs.getInt("COLUMN_SIZE");
					final int digits = rs.getInt("DECIMAL_DIGITS");
					final String typeName = rs.getString("TYPE_NAME") == null ? "" : rs.getString("TYPE_NAME");
					final String upper = typeName.toUpperCase(java.util.Locale.ENGLISH);
					final boolean sized = size > 0 && !typeName.contains("(") && (upper.contains("CHAR") || upper.contains("DEC") || upper.contains("NUM"));
					c.put("nativeType", typeName + (sized ? "(" + size + (digits > 0 ? "," + digits : "") + ")" : ""));
					c.put("nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
					c.put("primaryKey", keys.contains(name));
					if (rs.getString("REMARKS") != null && !rs.getString("REMARKS").trim().isEmpty()) c.put("remarks", rs.getString("REMARKS").trim());
					c.put("scannedAt", scannedAt);
				}
			}
			finally
			{
				rs.close();
			}
			// Read recipe + deduced scan (2026-09-24): SELECT <quoted columns> FROM <quoted qualified name> through
			// this connector's own read-only "query" operation, so the MDM Factory's landing can read every row of
			// this table without knowing it is JDBC. itemsPath "rows" is the shape the query operation returns.
			if (columns.size() > 0)
			{
				final StringBuilder columnList = new StringBuilder();
				for (final JsonNode column : columns)
				{
					if (columnList.length() > 0) columnList.append(", ");
					columnList.append(this.quote(pConnection, column.get("name").asText()));
				}
				final Map<String, String> recipeInputs = new LinkedHashMap<String, String>();
				recipeInputs.put("sql", "SELECT " + columnList + " FROM " + this.quote(pConnection, table.qualifiedName()));
				t.put("readRecipe", com.on.ebx.connect.core.CatalogOperations.readRecipe(this.config.getId(), "query", recipeInputs, "rows"));
				t.put("scanOrigin", "OFFICIAL");
				t.put("scanState", "CONFIRMED");
			}
			if (rowCounts)
			{
				final PreparedStatement count = pConnection.prepareStatement("SELECT COUNT(*) FROM " + this.quote(pConnection, table.qualifiedName()));
				try
				{
					count.setQueryTimeout(Math.max(1, this.config.getReadTimeoutMs() / 1000));
					final ResultSet r = count.executeQuery();
					if (r.next()) t.put("rowCount", r.getLong(1));
				}
				catch (final SQLException ex)
				{
					t.put("rowCountError", ex.getMessage());
				}
				finally
				{
					count.close();
				}
			}
		}
		for (final JsonNode item : tables)
		{
			enrichCatalogTable(pConnection, item.path("schema").asText(), item.path("name").asText(),
				(ObjectNode) item, pInputs);
			final JsonNode warnings = item.get("sourceMetadataWarnings");
			if (warnings != null && warnings.isArray()) for (final JsonNode warning : warnings)
			{
				final ObjectNode copy = out.withArray("warnings").addObject();
				copy.put("assetKey", item.path("assetKey").asText());
				copy.put("component", warning.path("component").asText());
				copy.put("code", warning.path("code").asText());
			}
		}
		out.put("count", tables.size());
		return tables.size() == 0 ? null : out;
	}

	/**
	 * {@code profile}: one aggregate query per table, {@code {source, profiledAt, profileDate, count, columns:[{source, assetKey,
	 * qualifiedName, columnKey, name, dataType, profiledAt, profileDate, rowCount, nullCount, nullRate, distinctCount, uniqueRate,
	 * minValue, maxValue, meanValue, minLength, maxLength, avgLength}]}}. Flat on purpose: a feed mapping writes one
	 * ProfileSnapshot per item. Booleans and binaries get counts only; property {@code profile.maxColumns} (default 200).
	 * Audit C-15: a per-run budget ({@code maxTables} default 200, {@code maxSeconds} default 900; inputs or properties
	 * {@code profile.*}, 0 lifts a limit) and {@code dryRun=true}, which returns the statements and runs none; every column
	 * says {@code method = FULL_SCAN} and {@code exact = true}, and the answer carries a {@code budget} block naming the
	 * tables left out. No sampling here: a JDBC source has no row count to apply a threshold to without counting.
	 */
	private JsonNode profile(final Connection pConnection, final Map<String, String> pInputs) throws SQLException, ConnectorException
	{
		final String source = AbstractFileConnector.required(pInputs, "source").trim();
		final String requested = pInputs.get("tables") == null || pInputs.get("tables").trim().isEmpty() ? "*" : pInputs.get("tables").trim();
		final String where = pInputs.get("where") == null || pInputs.get("where").trim().isEmpty() ? "" : " WHERE " + pInputs.get("where").trim();
		final int maxColumns = boundedInt(this.config.getProperty("profile.maxColumns", "200"), 1, 1000, 200);
		final List<TableRef> tables = new ArrayList<TableRef>();
		if ("*".equals(requested))
		{
			tables.addAll(this.tablesOf(pConnection, this.schemasToScan(pConnection, pInputs), pInputs));
		}
		else
		{
			for (final String name : requested.split(","))
			{
				final String q = name.trim();
				if (q.isEmpty()) continue;
				final int dot = q.indexOf('.');
				tables.add(new TableRef(dot < 0 ? this.config.getProperty("schema", null) : q.substring(0, dot), dot < 0 ? q : q.substring(dot + 1), "TABLE", null));
			}
		}
		final java.time.LocalDateTime nowTs = java.time.LocalDateTime.now().withNano(0);
		final String profiledAt = nowTs.format(com.on.ebx.connect.core.CatalogOperations.TIMESTAMP);
		final String profileDate = nowTs.toLocalDate().toString();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("profiledAt", profiledAt);
		out.put("profileDate", profileDate);
		final ArrayNode columns = out.putArray("columns");
		final DatabaseMetaData meta = pConnection.getMetaData();
		final com.on.ebx.connect.core.ProfilePolicy policy = com.on.ebx.connect.core.ProfilePolicy.of(pInputs, new java.util.function.Function<String, String>()
		{
			@Override
			public String apply(final String pName)
			{
				return JdbcConnector.this.config.getProperty(pName, null);
			}
		});
		final com.on.ebx.connect.core.ProfilePolicy.Run budget = new com.on.ebx.connect.core.ProfilePolicy.Run(policy);
		final ArrayNode plan = policy.dryRun() ? out.putObject("plan").putArray("tables") : null;
		for (final TableRef table : tables)
		{
			final String assetKey = source + ":" + table.qualifiedName();
			final List<String[]> cols = new ArrayList<String[]>(); // name, normalised type
			final ResultSet rs = meta.getColumns(this.usesCatalogMetadata() ? table.schema : null,
				this.usesCatalogMetadata() ? null : table.schema, table.name, "%");
			try
			{
				while (rs.next() && cols.size() < maxColumns)
				{
					cols.add(new String[] { rs.getString("COLUMN_NAME"), JdbcConnector.normalisedType(rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME")) });
				}
			}
			finally
			{
				rs.close();
			}
			if (cols.isEmpty()) continue;
			if (!budget.mayStartTable())
			{
				budget.skipForBudget(table.qualifiedName());
				continue;
			}
			// COUNT(*), then per column: COUNT(c), COUNT(DISTINCT c), MIN/MAX for comparable types, AVG for numbers, length stats for strings
			final StringBuilder sql = new StringBuilder("SELECT COUNT(*)");
			for (final String[] col : cols)
			{
				final String c = this.quote(pConnection, col[0]);
				final String type = col[1];
				sql.append(", COUNT(").append(c).append("), COUNT(DISTINCT ").append(c).append(')');
				if ("STRING".equals(type) || "INTEGER".equals(type) || "DECIMAL".equals(type) || "DATE".equals(type) || "TIMESTAMP".equals(type) || "TIME".equals(type))
				{
					sql.append(", MIN(").append(c).append("), MAX(").append(c).append(')');
				}
				else
				{
					sql.append(", NULL, NULL");
				}
				if ("INTEGER".equals(type) || "DECIMAL".equals(type))
				{
					sql.append(", AVG(CAST(").append(c).append(" AS DOUBLE PRECISION))");
				}
				else
				{
					sql.append(", NULL");
				}
				if ("STRING".equals(type))
				{
					sql.append(", MIN(LENGTH(").append(c).append(")), MAX(LENGTH(").append(c).append(")), AVG(CAST(LENGTH(").append(c).append(") AS DOUBLE PRECISION))");
				}
				else
				{
					sql.append(", NULL, NULL, NULL");
				}
			}
			sql.append(" FROM ").append(this.quote(pConnection, table.qualifiedName())).append(where);
			budget.profiled(false);
			if (plan != null)
			{
				final ObjectNode item = plan.addObject();
				item.put("table", table.qualifiedName());
				item.put("columns", cols.size());
				item.put("method", com.on.ebx.connect.core.RecordProfiler.METHOD_FULL_SCAN);
				item.put("sql", sql.toString());
				continue;
			}
			final PreparedStatement statement = pConnection.prepareStatement(sql.toString());
			try
			{
				statement.setQueryTimeout(Math.max(1, this.config.getReadTimeoutMs() / 1000));
				final ResultSet r = statement.executeQuery();
				if (!r.next()) continue;
				final long rowCount = r.getLong(1);
				int i = 2;
				for (final String[] col : cols)
				{
					final ObjectNode c = columns.addObject();
					c.put("source", source);
					c.put("assetKey", assetKey);
					c.put("qualifiedName", table.qualifiedName());
					c.put("columnKey", assetKey + "." + col[0]);
					c.put("name", col[0]);
					c.put("dataType", col[1]);
					c.put("profiledAt", profiledAt);
					c.put("profileDate", profileDate);
					c.put("method", com.on.ebx.connect.core.RecordProfiler.METHOD_FULL_SCAN);
					c.put("distinctMethod", "EXACT");
					c.put("exact", true);
					c.put("rowCount", rowCount);
					final long nonNull = r.getLong(i++);
					final long distinct = r.getLong(i++);
					c.put("nullCount", rowCount - nonNull);
					c.put("nullRate", rowCount == 0 ? 0d : JdbcConnector.round4((double) (rowCount - nonNull) / rowCount));
					c.put("distinctCount", distinct);
					c.put("uniqueRate", nonNull == 0 ? 0d : JdbcConnector.round4((double) distinct / nonNull));
					final Object min = r.getObject(i++);
					final Object max = r.getObject(i++);
					if (min != null) c.put("minValue", JdbcConnector.text(min));
					if (max != null) c.put("maxValue", JdbcConnector.text(max));
					final Object mean = r.getObject(i++);
					if (mean instanceof Number) c.put("meanValue", JdbcConnector.round4(((Number) mean).doubleValue()));
					final Object minLength = r.getObject(i++);
					final Object maxLength = r.getObject(i++);
					final Object avgLength = r.getObject(i++);
					if (minLength instanceof Number) c.put("minLength", ((Number) minLength).intValue());
					if (maxLength instanceof Number) c.put("maxLength", ((Number) maxLength).intValue());
					if (avgLength instanceof Number) c.put("avgLength", JdbcConnector.round4(((Number) avgLength).doubleValue()));
				}
			}
			finally
			{
				statement.close();
			}
		}
		out.put("exact", !budget.partial());
		out.put("partial", budget.partial());
		out.set("budget", budget.toJson());
		out.put("count", columns.size());
		if (plan != null)
		{
			out.put("dryRun", true);
			final ObjectNode summary = (ObjectNode) out.get("plan");
			summary.put("statements", budget.tablesProfiled());
			summary.put("note", "dry run: no profile statement was executed; each statement scans its table in full (a JDBC source does not know its row counts without counting)");
			return out;
		}
		return columns.size() == 0 && !budget.partial() ? null : out;
	}

	/**
	 * {@code sample}: {@code {source, sampledAt, count, columns:[{source, assetKey, qualifiedName, columnKey, name, dataType,
	 * sampledAt, sampleSize, values:[...], valuesText}]}} — up to {@code limit} rows read per table (default 50, max 500),
	 * distinct non-empty values of the STRING columns only, each value cut at 200 characters. Meant for pattern-based
	 * classification; the consumer is expected to discard the values once classified.
	 */
	private JsonNode sample(final Connection pConnection, final Map<String, String> pInputs) throws SQLException, ConnectorException
	{
		final String source = AbstractFileConnector.required(pInputs, "source").trim();
		final String requested = pInputs.get("tables") == null || pInputs.get("tables").trim().isEmpty() ? "*" : pInputs.get("tables").trim();
		final int limit = boundedInt(pInputs.get("limit") == null ? "50" : pInputs.get("limit").trim(), 1, 500, 50);
		final List<TableRef> tables = new ArrayList<TableRef>();
		if ("*".equals(requested))
		{
			tables.addAll(this.tablesOf(pConnection, this.schemasToScan(pConnection, pInputs), pInputs));
		}
		else
		{
			for (final String name : requested.split(","))
			{
				final String q = name.trim();
				if (q.isEmpty()) continue;
				final int dot = q.indexOf('.');
				tables.add(new TableRef(dot < 0 ? this.config.getProperty("schema", null) : q.substring(0, dot), dot < 0 ? q : q.substring(dot + 1), "TABLE", null));
			}
		}
		final String sampledAt = com.on.ebx.connect.core.CatalogOperations.timestamp();
		final ObjectNode out = Json.object();
		out.put("source", source);
		out.put("sampledAt", sampledAt);
		final ArrayNode columns = out.putArray("columns");
		final DatabaseMetaData meta = pConnection.getMetaData();
		for (final TableRef table : tables)
		{
			final String assetKey = source + ":" + table.qualifiedName();
			final List<String> textColumns = new ArrayList<String>();
			final ResultSet rs = meta.getColumns(this.usesCatalogMetadata() ? table.schema : null,
				this.usesCatalogMetadata() ? null : table.schema, table.name, "%");
			try
			{
				while (rs.next() && textColumns.size() < 200)
				{
					if ("STRING".equals(JdbcConnector.normalisedType(rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME"))))
					{
						textColumns.add(rs.getString("COLUMN_NAME"));
					}
				}
			}
			finally
			{
				rs.close();
			}
			if (textColumns.isEmpty()) continue;
			final StringBuilder sql = new StringBuilder("SELECT ");
			for (int i = 0; i < textColumns.size(); i++)
			{
				sql.append(i > 0 ? ", " : "").append(this.quote(pConnection, textColumns.get(i)));
			}
			sql.append(" FROM ").append(this.quote(pConnection, table.qualifiedName()));
			final PreparedStatement statement = pConnection.prepareStatement(sql.toString());
			try
			{
				statement.setQueryTimeout(Math.max(1, this.config.getReadTimeoutMs() / 1000));
				statement.setMaxRows(limit);
				statement.setFetchSize(Math.min(limit, this.fetchSize(pInputs)));
				final ResultSet r = statement.executeQuery();
				final List<java.util.LinkedHashSet<String>> values = new ArrayList<java.util.LinkedHashSet<String>>();
				for (int i = 0; i < textColumns.size(); i++) values.add(new java.util.LinkedHashSet<String>());
				int rows = 0;
				while (r.next() && rows < limit)
				{
					rows++;
					for (int i = 0; i < textColumns.size(); i++)
					{
						final String v = r.getString(i + 1);
						if (v != null && !v.trim().isEmpty())
						{
							values.get(i).add(v.length() > 200 ? v.substring(0, 200) : v);
						}
					}
				}
				for (int i = 0; i < textColumns.size(); i++)
				{
					final ObjectNode c = columns.addObject();
					c.put("source", source);
					c.put("assetKey", assetKey);
					c.put("qualifiedName", table.qualifiedName());
					c.put("columnKey", assetKey + "." + textColumns.get(i));
					c.put("name", textColumns.get(i));
					c.put("dataType", "STRING");
					c.put("sampledAt", sampledAt);
					c.put("sampleSize", rows);
					final ArrayNode array = c.putArray("values");
					final StringBuilder text = new StringBuilder();
					for (final String v : values.get(i))
					{
						array.add(v);
						text.append(text.length() > 0 ? "\n" : "").append(v.replace('\n', ' ').replace('\r', ' '));
					}
					c.put("valuesText", text.toString());
				}
			}
			finally
			{
				statement.close();
			}
		}
		out.put("count", columns.size());
		return columns.size() == 0 ? null : out;
	}

	private static double round4(final double pValue)
	{
		return Math.round(pValue * 10000d) / 10000d;
	}

	private static String text(final Object pValue)
	{
		final String s = pValue instanceof java.sql.Timestamp ? com.on.ebx.connect.core.CatalogOperations.dateTime(((java.sql.Timestamp) pValue).toLocalDateTime())
			: pValue instanceof java.time.LocalDateTime ? com.on.ebx.connect.core.CatalogOperations.dateTime((java.time.LocalDateTime) pValue)
			: pValue instanceof java.time.OffsetDateTime ? com.on.ebx.connect.core.CatalogOperations.dateTime((java.time.OffsetDateTime) pValue)
			: pValue instanceof java.sql.Date ? ((java.sql.Date) pValue).toLocalDate().toString() : String.valueOf(pValue);
		return s.length() > 500 ? s.substring(0, 500) : s;
	}

	private int insert(final Connection pConnection, final String pTable, final JsonNode pDocument) throws SQLException
	{
		final StringBuilder columns = new StringBuilder();
		final StringBuilder marks = new StringBuilder();
		final List<Object> values = new ArrayList<Object>();
		final Iterator<Map.Entry<String, JsonNode>> fields = pDocument.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			columns.append(columns.length() > 0 ? ", " : "").append(this.quote(pConnection, field.getKey()));
			marks.append(marks.length() > 0 ? ", " : "").append('?');
			values.add(JdbcConnector.javaValue(field.getValue()));
		}
		final PreparedStatement statement = pConnection.prepareStatement("INSERT INTO " + pTable + " (" + columns + ") VALUES (" + marks + ")");
		try
		{
			JdbcConnector.setParameters(statement, values);
			return statement.executeUpdate();
		}
		finally
		{
			statement.close();
		}
	}

	private int update(final Connection pConnection, final String pTable, final List<String> pKeys, final JsonNode pDocument) throws SQLException, ConnectorException
	{
		final StringBuilder set = new StringBuilder();
		final StringBuilder where = new StringBuilder();
		final List<Object> values = new ArrayList<Object>();
		final List<Object> keyValues = new ArrayList<Object>();
		final Iterator<Map.Entry<String, JsonNode>> fields = pDocument.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			if (pKeys.contains(field.getKey()))
			{
				continue;
			}
			set.append(set.length() > 0 ? ", " : "").append(this.quote(pConnection, field.getKey())).append(" = ?");
			values.add(JdbcConnector.javaValue(field.getValue()));
		}
		for (final String key : pKeys)
		{
			if (pDocument.get(key) == null)
			{
				throw new ConnectorException("JDBC: the key column " + key + " is missing from the outbound document");
			}
			where.append(where.length() > 0 ? " AND " : "").append(this.quote(pConnection, key)).append(" = ?");
			keyValues.add(JdbcConnector.javaValue(pDocument.get(key)));
		}
		if (set.length() == 0)
		{
			// nothing but keys: report whether the row exists
			final PreparedStatement exists = pConnection.prepareStatement("SELECT 1 FROM " + pTable + " WHERE " + where);
			try
			{
				JdbcConnector.setParameters(exists, keyValues);
				return exists.executeQuery().next() ? 1 : 0;
			}
			finally
			{
				exists.close();
			}
		}
		values.addAll(keyValues);
		final PreparedStatement statement = pConnection.prepareStatement("UPDATE " + pTable + " SET " + set + " WHERE " + where);
		try
		{
			JdbcConnector.setParameters(statement, values);
			return statement.executeUpdate();
		}
		finally
		{
			statement.close();
		}
	}

	/** Browses schemas, tables and views through DatabaseMetaData only; no business query is executed. */
	@Override
	public ObjectDiscoveryResult discoverObjects(final ConnectorContext pContext, final ObjectDiscoveryRequest pRequest)
		throws ConnectorException
	{
		final ObjectDiscoveryRequest request = pRequest == null ? ObjectDiscoveryRequest.firstPage("", 50) : pRequest;
		final int offset = boundedInt(request.getContinuationToken(), 0, Integer.MAX_VALUE, 0);
		final List<DiscoveredObject> objects = new ArrayList<DiscoveredObject>();
		Connection connection = null;
		ResultSet schemas = null;
		ResultSet tables = null;
		try
		{
			connection = this.connect();
			this.makeReadOnly(connection);
			final String configuredSchema = this.config.getProperty("schema", null);
			int matched = 0;
			boolean more = false;
			if (configuredSchema == null)
			{
				schemas = this.usesCatalogMetadata() ? connection.getMetaData().getCatalogs()
					: connection.getMetaData().getSchemas();
				while (schemas != null && schemas.next())
				{
					final String schema = schemas.getString(this.usesCatalogMetadata() ? "TABLE_CAT" : "TABLE_SCHEM");
					if (schema == null || schema.trim().isEmpty()) continue;
					if (!request.getQuery().isEmpty() && !schema.toLowerCase().contains(request.getQuery().toLowerCase())) continue;
					if (matched++ < offset) continue;
					if (objects.size() == request.getLimit())
					{
						more = true;
						break;
					}
					objects.add(new DiscoveredObject((this.usesCatalogMetadata() ? "catalog:" : "schema:") + schema,
						schema, this.usesCatalogMetadata() ? "CATALOG" : "SCHEMA", false));
				}
			}
			tables = connection.getMetaData().getTables(this.usesCatalogMetadata() ? configuredSchema : null,
				this.usesCatalogMetadata() ? null : configuredSchema, "%", new String[] { "TABLE", "VIEW" });
			while (!more && tables.next())
			{
				final String schema = tables.getString(this.usesCatalogMetadata() ? "TABLE_CAT" : "TABLE_SCHEM");
				final String table = tables.getString("TABLE_NAME");
				final String id = schema == null || schema.isEmpty() ? table : schema + "." + table;
				if (!request.getQuery().isEmpty() && !id.toLowerCase().contains(request.getQuery().toLowerCase())) continue;
				if (matched++ < offset) continue;
				if (objects.size() == request.getLimit())
				{
					more = true;
					break;
				}
				objects.add(new DiscoveredObject(id, table, tables.getString("TABLE_TYPE"), true));
			}
			return ObjectDiscoveryResult.page(objects, more ? String.valueOf(offset + objects.size()) : null);
		}
		catch (final SQLException ex)
		{
			throw JdbcConnector.sqlFailure("discovery", ex);
		}
		finally
		{
			if (schemas != null) try { schemas.close(); } catch (final SQLException ignored) { }
			if (tables != null) try { tables.close(); } catch (final SQLException ignored) { }
			if (connection != null)
			{
				try
				{
					connection.rollback();
				}
				catch (final SQLException ignored) { }
				try
				{
					connection.close();
				}
				catch (final SQLException ignored) { }
			}
		}
	}

	@Override
	public ObjectDescription describeObject(final ConnectorContext pContext, final String pObjectId)
		throws ConnectorException
	{
		if (pObjectId == null || pObjectId.trim().isEmpty()) return ObjectDescription.unsupported(pObjectId, "Table name is required");
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("table", pObjectId);
		final List<FieldInfo> fields = this.describeFields(pContext, "get", inputs);
		return ObjectDescription.of(pObjectId, pObjectId, fields);
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		final String table = pInputs == null ? null : pInputs.get("table");
		final String sql = pInputs == null ? null : pInputs.get("sql");
		if ((table == null || table.trim().isEmpty()) && (sql == null || sql.trim().isEmpty()))
		{
			return fields;
		}
		final String prefix = "get".equals(pOperation) || "insert".equals(pOperation) || "update".equals(pOperation) || "upsert".equals(pOperation) ? "" : "rows[0].";
		Connection connection = null;
		try
		{
			connection = this.connect();
			this.makeReadOnly(connection);
			if (table != null && !table.trim().isEmpty())
			{
				final DatabaseMetaData meta = connection.getMetaData();
				final String schema = this.config.getProperty("schema", null);
				String name = table.trim();
				String schemaName = schema;
				if (name.contains("."))
				{
					schemaName = name.substring(0, name.indexOf('.'));
					name = name.substring(name.indexOf('.') + 1);
				}
				for (final String candidate : new String[] { name, name.toUpperCase(), name.toLowerCase() })
				{
					final String namespace = schemaName == null ? null : JdbcConnector.matchCase(schemaName, candidate, name);
					final ResultSet columns = meta.getColumns(this.usesCatalogMetadata() ? namespace : null,
						this.usesCatalogMetadata() ? null : namespace, candidate, "%");
					try
					{
						while (columns.next())
						{
							fields.add(FieldInfo.of(prefix + columns.getString("COLUMN_NAME"), columns.getString("REMARKS") == null ? columns.getString("COLUMN_NAME")
									: columns.getString("REMARKS"), columns.getString("TYPE_NAME") == null ? "" : columns.getString("TYPE_NAME").toLowerCase()));
						}
					}
					finally
					{
						columns.close();
					}
					if (!fields.isEmpty())
					{
						break;
					}
				}
				return fields;
			}
			if (!JdbcConnector.isSafePreviewSelect(sql))
			{
				throw new ConnectorException("JDBC describe accepts only one read-only SELECT statement");
			}
			final List<Object> bound = new ArrayList<Object>();
			final String bindable = JdbcConnector.bind(sql, JdbcConnector.values(pInputs, null), bound);
			final PreparedStatement statement = connection.prepareStatement(bindable);
			try
			{
				statement.setMaxRows(1);
				statement.setFetchSize(1);
				JdbcConnector.setParameters(statement, bound);
				final ResultSetMetaData meta = statement.executeQuery().getMetaData();
				for (int c = 1; c <= meta.getColumnCount(); c++)
				{
					fields.add(FieldInfo.of(prefix + (meta.getColumnLabel(c) == null ? meta.getColumnName(c) : meta.getColumnLabel(c)),
						meta.getColumnName(c), meta.getColumnTypeName(c) == null ? "" : meta.getColumnTypeName(c).toLowerCase()));
				}
			}
			finally
			{
				statement.close();
			}
		}
		catch (final SQLException ex)
		{
			throw JdbcConnector.sqlFailure("describe", ex);
		}
		finally
		{
			if (connection != null)
			{
				try { connection.rollback(); } catch (final SQLException ignored) { }
				try
				{
					connection.close();
				}
				catch (final SQLException ex)
				{
					// ignore
				}
			}
		}
		return fields;
	}

	private static String matchCase(final String pSchema, final String pCandidate, final String pOriginal)
	{
		return pCandidate.equals(pOriginal.toUpperCase()) ? pSchema.toUpperCase() : pCandidate.equals(pOriginal.toLowerCase()) ? pSchema.toLowerCase() : pSchema;
	}
}
