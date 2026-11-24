package com.on.ebx.connect.connectors;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.JdbcGuard;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.Operation;

/**
 * Offline checks of the Cloudera Hive and Impala connections, against a test driver that behaves as the Apache Hive JDBC
 * driver does on the calls that matter: {@code setReadOnly(true)} throws "Enabling read-only mode not supported",
 * {@code commit()} and {@code rollback()} throw "Method not supported", and the columns of a result come back named
 * {@code table.column}. No server is involved here: the same operations against a real Apache Hive are
 * {@code tools/live-hive/run.sh} ({@code HiveServer2LiveCheck}).
 */
public final class ClouderaConnectorsTest
{
	private static final String HIVE_URL = "jdbc:hive2://vw.example.test/default;transportMode=http;httpPath=cliservice;ssl=true";
	private static final String IMPALA_URL = "jdbc:impala://vw.example.test:443/default;AuthMech=3;transportMode=http;httpPath=cliservice;SSL=1";

	private ClouderaConnectorsTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		validation();
		readOnlySurface();
		columnNames();
		guard();
		System.setProperty(JdbcGuard.PROPERTY_DRIVERS, HiveLikeDriver.class.getName());
		final HiveLikeDriver driver = new HiveLikeDriver();
		DriverManager.registerDriver(driver);
		try
		{
			genericEngineFailsOnSuchADriver();
			reads(new ClouderaHiveConnector(), driver);
			reads(new ClouderaImpalaConnector(), driver);
			reads(new SparkSqlConnector(), driver);
		}
		finally
		{
			DriverManager.deregisterDriver(driver);
			System.clearProperty(JdbcGuard.PROPERTY_DRIVERS);
		}
		System.out.println("PASS: Cloudera Hive and Impala connections: URL, TLS, driver and Kerberos validation, read-only surface, script"
			+ " refusal, and reads on a driver without read-only mode or rollback (Spark SQL included)");
	}

	private static void validation() throws Exception
	{
		final ClouderaHiveConnector hive = new ClouderaHiveConnector();
		final ConnectorConfig ok = config(HIVE_URL);
		hive.configure(ok);
		check(hive.validateConfiguration(ok).isValid(), "a Data Warehouse Hive URL is valid");
		check(AbstractHiveServer2Connector.APACHE_HIVE_DRIVER.equals(ok.getProperty("driver", null)), "Hive defaults the Apache driver");
		check("1000".equals(ok.getProperty("max.rows", null)) && "500".equals(ok.getProperty("fetch.size", null)), "bounds default");
		final ConnectorConfig clouderaDriver = config(HIVE_URL);
		clouderaDriver.setProperty("driver", ClouderaHiveConnector.CLOUDERA_HIVE_DRIVER);
		hive.configure(clouderaDriver);
		check(hive.validateConfiguration(clouderaDriver).isValid(), "Cloudera's Hive driver is accepted on jdbc:hive2://");
		check(invalid(hive, "jdbc:hive2://h:10000/default", "encrypted"), "a clear Hive URL is refused");
		check(invalid(hive, "jdbc:impala://h:443/default;SSL=1", "Must start with"), "an Impala URL is refused by the Hive connection");
		check(invalid(hive, "jdbc:hive2://h:10000/default;ssl=true;principal=hive/_HOST@REALM", "Kerberos"), "a Kerberos principal is reported");
		final ConnectorConfig wrongDriver = config(HIVE_URL);
		wrongDriver.setProperty("driver", "org.postgresql.Driver");
		hive.configure(wrongDriver);
		check(!hive.validateConfiguration(wrongDriver).isValid(), "another driver is refused");
		final ConnectorConfig jndi = new ConnectorConfig();
		jndi.setProperty("jndi", "java:comp/env/jdbc/cloudera");
		hive.configure(jndi);
		check(hive.validateConfiguration(jndi).isValid(), "a JNDI DataSource needs no URL");
		check(jndi.getProperty("driver", null) == null, "a JNDI DataSource gets no driver");

		final ClouderaImpalaConnector impala = new ClouderaImpalaConnector();
		final ConnectorConfig native_ = config(IMPALA_URL);
		impala.configure(native_);
		check(impala.validateConfiguration(native_).isValid(), "a Data Warehouse Impala URL is valid");
		check(ClouderaImpalaConnector.CLOUDERA_IMPALA_DRIVER.equals(native_.getProperty("driver", null)), "jdbc:impala:// defaults Cloudera's driver");
		final ConnectorConfig overHive = config("jdbc:hive2://impalad.example.test:21050/default;ssl=true");
		impala.configure(overHive);
		check(impala.validateConfiguration(overHive).isValid(), "Impala's HiveServer2 port is valid");
		check(AbstractHiveServer2Connector.APACHE_HIVE_DRIVER.equals(overHive.getProperty("driver", null)), "jdbc:hive2:// defaults the Apache driver");
		check(invalid(impala, "jdbc:impala://h:21050/default;AuthMech=3", "encrypted"), "a clear Impala URL is refused");
		check(invalid(impala, "jdbc:impala://h:21050/default;SSL=1;AuthMech=1;KrbHostFQDN=h", "Kerberos"), "AuthMech=1 is reported as Kerberos");
		check(!invalid(impala, "jdbc:impala://h:21050/default;SSL=1;AuthMech=12", "Kerberos"), "AuthMech=12 is not Kerberos");
		final ConnectorConfig crossed = config(IMPALA_URL);
		crossed.setProperty("driver", AbstractHiveServer2Connector.APACHE_HIVE_DRIVER);
		impala.configure(crossed);
		check(!impala.validateConfiguration(crossed).isValid(), "the Hive driver on jdbc:impala:// is refused");
	}

	private static void readOnlySurface() throws Exception
	{
		final ClouderaHiveConnector hive = new ClouderaHiveConnector();
		final ClouderaImpalaConnector impala = new ClouderaImpalaConnector();
		check(names(hive).equals("query,catalog,profile,sample"), "Hive operations: " + names(hive));
		check(names(impala).equals("query,catalog,sample"), "Impala operations: " + names(impala));
		for (final JdbcConnector connector : new JdbcConnector[] { hive, impala })
		{
			check(!connector.getDescriptor().getCapabilities().contains(ConnectorCapability.BULK_WRITE)
				&& !connector.getDescriptor().getCapabilities().contains(ConnectorCapability.TRANSACTIONS), connector.getType() + " descriptor is read-only");
			check(!connector.getDescriptor().getDependencies().get(0).isRequired(), connector.getType() + " driver is not packaged");
			for (final String write : new String[] { "execute", "insert", "update", "upsert", "procedure", "get" })
			{
				try
				{
					connector.execute(null, write, Collections.singletonMap("sql", "DROP TABLE t"), null);
					throw new AssertionError(connector.getType() + " exposes " + write);
				}
				catch (final ConnectorException expected)
				{
					check(expected.getMessage().contains("read-only"), connector.getType() + " refuses " + write + ": " + expected.getMessage());
				}
			}
		}
		try
		{
			impala.execute(null, "profile", Collections.<String, String>emptyMap(), null);
			throw new AssertionError("Impala exposes profile");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("read-only"), "Impala refuses profile");
		}
		check(AbstractHiveServer2Connector.refusal("SELECT TRANSFORM(a) USING '/bin/cat' FROM t") != null, "TRANSFORM ... USING is refused");
		check(AbstractHiveServer2Connector.refusal("select a from (from t map a using \"x.py\" as a) s") != null, "MAP ... USING is refused");
		check(AbstractHiveServer2Connector.refusal("SELECT a, b FROM t JOIN u USING (id) WHERE a = :a") == null, "JOIN ... USING (col) passes");
		check(AbstractHiveServer2Connector.refusal("SELECT map('k', v) FROM t") == null, "the map() constructor passes");
		try
		{
			hive.execute(null, "query", Collections.singletonMap("sql", "SELECT TRANSFORM(a) USING '/bin/cat' FROM t"), null);
			throw new AssertionError("a script statement reached the engine");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("script"), "script refusal message: " + expected.getMessage());
		}
	}

	/** HiveServer2 names the columns of SELECT * table.column; the prefix goes unless two columns would share a name. */
	private static void columnNames() throws Exception
	{
		check(Arrays.asList("id", "name").equals(AbstractHiveServer2Connector.unqualified(Arrays.asList("customer.id", "customer.name"))), "table prefixes are dropped");
		check(Arrays.asList("a", "b").equals(AbstractHiveServer2Connector.unqualified(Arrays.asList("t.a", "b"))), "a mixed result is renamed too");
		check(AbstractHiveServer2Connector.unqualified(Arrays.asList("id", "name")) == null, "plain names are left alone");
		check(AbstractHiveServer2Connector.unqualified(Arrays.asList("c.id", "d.id")) == null, "a join with twice the same column keeps its qualified names");
		check(AbstractHiveServer2Connector.unqualified(Arrays.asList("t.", "x")) == null, "a name ending with a dot is not a qualified name");
		final com.fasterxml.jackson.databind.node.ObjectNode row = com.on.ebx.connect.core.Json.object();
		row.put("customer.id", 1);
		row.put("customer.name", "Ada");
		check("{\"id\":1,\"name\":\"Ada\"}".equals(AbstractHiveServer2Connector.unqualified((JsonNode) row).toString()), "a row keeps its values and their order");
		final com.fasterxml.jackson.databind.node.ObjectNode join = com.on.ebx.connect.core.Json.object();
		join.put("c.id", 1);
		join.put("d.id", 1);
		check(AbstractHiveServer2Connector.unqualified((JsonNode) join) == join, "a join row is returned as it is");
	}

	private static void guard() throws Exception
	{
		JdbcGuard.checkDriver(HIVE_URL, ClouderaHiveConnector.CLOUDERA_HIVE_DRIVER);
		JdbcGuard.checkDriver(IMPALA_URL, ClouderaImpalaConnector.CLOUDERA_IMPALA_DRIVER);
		JdbcGuard.checkUrl(HIVE_URL);
		JdbcGuard.checkUrl(IMPALA_URL);
		try
		{
			JdbcGuard.checkDriver(IMPALA_URL, AbstractHiveServer2Connector.APACHE_HIVE_DRIVER);
			throw new AssertionError("the guard lets the Hive driver answer jdbc:impala://");
		}
		catch (final ConnectorException expected) { /* one driver per URL scheme */ }
		try
		{
			JdbcGuard.checkUrl(HIVE_URL + ";sslTrustStore=/etc/x.jks");
			throw new AssertionError("a trust store path in the URL is accepted");
		}
		catch (final ConnectorException expected) { /* the JVM's trust store is the one to use */ }
	}

	/** The reason for {@code withoutTransactions()}: the shared engine, unchanged, loses its result on such a driver. */
	private static void genericEngineFailsOnSuchADriver() throws Exception
	{
		final JdbcConnector generic = new JdbcConnector();
		final ConnectorConfig config = config(HIVE_URL);
		config.setProperty("driver", HiveLikeDriver.class.getName());
		generic.configure(config);
		try
		{
			generic.execute(null, "query", Collections.singletonMap("sql", "SELECT id, name FROM customer"), null);
			throw new AssertionError("the generic JDBC engine did not fail on a driver without rollback: the test driver proves nothing");
		}
		catch (final ConnectorException expected)
		{
			check(String.valueOf(expected.getMessage()).contains("Method not supported") || expected.getCause() instanceof SQLFeatureNotSupportedException,
				"expected the rollback refusal, got: " + expected.getMessage());
		}
	}

	private static void reads(final JdbcConnector pConnector, final HiveLikeDriver pDriver) throws Exception
	{
		final ConnectorConfig config = config(HIVE_URL);
		config.setProperty("driver", HiveLikeDriver.class.getName());
		pConnector.configure(config);
		final String type = pConnector.getType();
		pDriver.reset();
		check(pConnector.testConnection(null).getMessage().contains("Apache Hive"), type + " connection test");
		pDriver.reset();
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("sql", "SELECT id, name FROM customer");
		final JsonNode result = pConnector.execute(null, "query", inputs, null);
		check(result != null && result.path("count").asInt() == 2 && "Ada".equals(result.path("rows").path(0).path("name").asText()),
			type + " query returns the rows: " + result);
		check(pDriver.readOnlyAsked && pDriver.rollbackAsked, type + " asks for read-only mode and a rollback, and survives both refusals");
		check(!pDriver.committed, type + " never commits");
		check(pDriver.closed, type + " closes the connection");
		pDriver.reset();
		final List<JsonNode> streamed = new ArrayList<JsonNode>();
		final Connector.RowStreamResult stream = pConnector.readRows(null, "query", inputs, "rows", 10, new Connector.RowSink()
		{
			public boolean accept(final JsonNode pRow)
			{
				streamed.add(pRow);
				return true;
			}
		});
		check(stream.count == 2 && streamed.size() == 2 && !stream.truncated, type + " streamed query returns the rows");
		check("Grace".equals(streamed.get(1).path("name").asText()) && !streamed.get(1).has("customer.name"), type + " streamed rows carry plain column names");
		check(pDriver.closed && !pDriver.committed, type + " streamed query closes and never commits");
	}

	private static boolean invalid(final JdbcConnector pConnector, final String pUrl, final String pExpected)
	{
		final ConnectorConfig config = config(pUrl);
		pConnector.configure(config);
		final StringBuilder messages = new StringBuilder();
		for (final com.on.ebx.connect.spi.ConfigurationValidationIssue issue : pConnector.validateConfiguration(config).getIssues())
		{
			messages.append(issue.getMessage()).append('\n');
		}
		return messages.indexOf(pExpected) >= 0;
	}

	private static String names(final JdbcConnector pConnector)
	{
		final StringBuilder names = new StringBuilder();
		for (final Operation operation : pConnector.getOperations())
		{
			names.append(names.length() == 0 ? "" : ",").append(operation.getName());
		}
		return names.toString();
	}

	private static ConnectorConfig config(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC);
		config.setUsername("workload-user");
		config.setPassword("not-a-real-secret");
		return config;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	/** Answers {@code jdbc:hive2://} as the Apache Hive driver does on read-only mode, commit and rollback; two fixed rows for any query. */
	public static final class HiveLikeDriver implements Driver
	{
		boolean readOnlyAsked;
		boolean rollbackAsked;
		boolean committed;
		boolean closed;

		void reset()
		{
			this.readOnlyAsked = false;
			this.rollbackAsked = false;
			this.committed = false;
			this.closed = false;
		}

		public boolean acceptsURL(final String pUrl) { return pUrl != null && pUrl.startsWith("jdbc:hive2://"); }

		public Connection connect(final String pUrl, final Properties pInfo)
		{
			if (!this.acceptsURL(pUrl)) return null;
			final HiveLikeDriver self = this;
			final DatabaseMetaData metadata = proxy(DatabaseMetaData.class, new InvocationHandler()
			{
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
				{
					final String name = pMethod.getName();
					if ("getDatabaseProductName".equals(name)) return "Apache Hive";
					if ("getDatabaseProductVersion".equals(name)) return "4.0.1";
					if ("getIdentifierQuoteString".equals(name)) return "`";
					return defaultValue(pMethod.getReturnType());
				}
			});
			return proxy(Connection.class, new InvocationHandler()
			{
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs) throws Throwable
				{
					final String name = pMethod.getName();
					if ("setReadOnly".equals(name))
					{
						self.readOnlyAsked = true;
						if (Boolean.TRUE.equals(pArgs[0])) throw new SQLException("Enabling read-only mode not supported");
						return null;
					}
					if ("isReadOnly".equals(name)) return Boolean.FALSE;
					if ("getAutoCommit".equals(name)) return Boolean.TRUE;
					if ("rollback".equals(name))
					{
						self.rollbackAsked = true;
						throw new SQLFeatureNotSupportedException("Method not supported");
					}
					if ("commit".equals(name))
					{
						self.committed = true;
						throw new SQLFeatureNotSupportedException("Method not supported");
					}
					if ("getMetaData".equals(name)) return metadata;
					if ("prepareStatement".equals(name)) return statement();
					if ("close".equals(name)) self.closed = true;
					return defaultValue(pMethod.getReturnType());
				}
			});
		}

		private static PreparedStatement statement()
		{
			return proxy(PreparedStatement.class, new InvocationHandler()
			{
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
				{
					if ("executeQuery".equals(pMethod.getName())) return rows();
					return defaultValue(pMethod.getReturnType());
				}
			});
		}

		private static ResultSet rows()
		{
			final String[] labels = { "customer.id", "customer.name" };
			final Object[][] values = { { Integer.valueOf(1), "Ada" }, { Integer.valueOf(2), "Grace" } };
			final ResultSetMetaData meta = proxy(ResultSetMetaData.class, new InvocationHandler()
			{
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
				{
					final String name = pMethod.getName();
					if ("getColumnCount".equals(name)) return Integer.valueOf(labels.length);
					if ("getColumnLabel".equals(name) || "getColumnName".equals(name)) return labels[((Integer) pArgs[0]).intValue() - 1];
					if ("getColumnType".equals(name)) return Integer.valueOf(((Integer) pArgs[0]).intValue() == 1 ? java.sql.Types.INTEGER : java.sql.Types.VARCHAR);
					return defaultValue(pMethod.getReturnType());
				}
			});
			final int[] cursor = { -1 };
			return proxy(ResultSet.class, new InvocationHandler()
			{
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
				{
					final String name = pMethod.getName();
					if ("getMetaData".equals(name)) return meta;
					if ("next".equals(name)) return Boolean.valueOf(++cursor[0] < values.length);
					if ("getObject".equals(name) && pArgs != null && pArgs[0] instanceof Integer) return values[cursor[0]][((Integer) pArgs[0]).intValue() - 1];
					return defaultValue(pMethod.getReturnType());
				}
			});
		}

		public DriverPropertyInfo[] getPropertyInfo(final String pUrl, final Properties pInfo) { return new DriverPropertyInfo[0]; }
		public int getMajorVersion() { return 1; }
		public int getMinorVersion() { return 0; }
		public boolean jdbcCompliant() { return false; }
		public Logger getParentLogger() { return Logger.getGlobal(); }
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(final Class<T> pType, final InvocationHandler pHandler)
	{
		return (T) Proxy.newProxyInstance(ClouderaConnectorsTest.class.getClassLoader(), new Class<?>[] { pType }, pHandler);
	}

	private static Object defaultValue(final Class<?> pType)
	{
		if (!pType.isPrimitive()) return null;
		if (pType == Boolean.TYPE) return Boolean.FALSE;
		if (pType == Character.TYPE) return Character.valueOf('\0');
		if (pType == Byte.TYPE) return Byte.valueOf((byte) 0);
		if (pType == Short.TYPE) return Short.valueOf((short) 0);
		if (pType == Integer.TYPE) return Integer.valueOf(0);
		if (pType == Long.TYPE) return Long.valueOf(0L);
		if (pType == Float.TYPE) return Float.valueOf(0F);
		if (pType == Double.TYPE) return Double.valueOf(0D);
		return null;
	}
}
