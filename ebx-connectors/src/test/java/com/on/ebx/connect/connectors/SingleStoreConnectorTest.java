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
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;

/** Account-free certification of the SingleStore Connector/J profile and catalog metadata dialect. */
public final class SingleStoreConnectorTest
{
	private SingleStoreConnectorTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final SingleStoreConnector connector = new SingleStoreConnector();
		check("singlestore".equals(connector.getType()) && "SingleStore".equals(connector.getLabel()), "identity");
		final ConnectorConfig valid = configuration("jdbc:mysql://db.example:3306/mdm");
		connector.configure(valid);
		check(SingleStoreConnector.DRIVER.equals(valid.getProperty("driver", null)), "driver default");
		check("VERIFY_IDENTITY".equals(valid.getProperty("jdbc.sslMode", null)), "verified TLS default");
		check("false".equals(valid.getProperty("jdbc.allowPublicKeyRetrieval", null)), "public-key retrieval default");
		check(connector.validateConfiguration(valid).isValid(), "valid configuration rejected");

		final ConnectorConfig wrongUrl = configuration("jdbc:postgresql://db.example:5432/mdm");
		connector.configure(wrongUrl);
		check(has(connector.validateConfiguration(wrongUrl), "baseUrl"), "foreign URL accepted");
		final ConnectorConfig insecure = configuration("jdbc:mysql://db.example:3306/mdm");
		insecure.setProperty("jdbc.sslMode", "DISABLED");
		insecure.setProperty("jdbc.allowPublicKeyRetrieval", "true");
		connector.configure(insecure);
		check(has(connector.validateConfiguration(insecure), "jdbc.sslMode")
			&& has(connector.validateConfiguration(insecure), "jdbc.allowPublicKeyRetrieval"), "insecure settings accepted");

		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("DATABASE".equals(descriptor.getCategory()), "database category");
		check(descriptor.getDirections().containsAll(Arrays.asList(ConnectorDirection.INBOUND, ConnectorDirection.OUTBOUND)), "directions");
		check(descriptor.getCapabilities().contains(ConnectorCapability.SCHEMA_DISCOVERY)
			&& descriptor.getCapabilities().contains(ConnectorCapability.TRANSACTIONS), "capabilities");
		boolean dependency = false;
		for (final ConnectorDependency value : descriptor.getDependencies())
			if (("com.mysql:mysql-connector-j:" + SingleStoreConnector.CONNECTOR_J_VERSION).equals(value.getMinimumVersion())) dependency = true;
		check(dependency, "Connector/J dependency");

		final AtomicReference<Object[]> tablesArguments = new AtomicReference<Object[]>();
		final AtomicReference<Object[]> columnsArguments = new AtomicReference<Object[]>();
		final AtomicReference<String> writeSql = new AtomicReference<String>();
		final AtomicBoolean committed = new AtomicBoolean();
		final Driver fake = new FakeDriver(tablesArguments, columnsArguments, writeSql, committed);
		DriverManager.registerDriver(fake);
		try
		{
			final ConnectorConfig offline = configuration("jdbc:ebx-singlestore:test");
			offline.setProperty("schema", "customer360");
			connector.configure(offline);
			final ObjectDiscoveryResult objects = connector.discoverObjects(null, ObjectDiscoveryRequest.firstPage("account", 10));
			check(objects.isSupported() && objects.getObjects().size() == 1
				&& "customer360.account".equals(objects.getObjects().get(0).getId()), "catalog table discovery");
			check("customer360".equals(tablesArguments.get()[0]) && tablesArguments.get()[1] == null,
				"catalog was not passed as TABLE_CAT");
			check(connector.describeObject(null, "customer360.account").isSupported(), "catalog object description");
			check("customer360".equals(columnsArguments.get()[0]) && columnsArguments.get()[1] == null,
				"catalog was not passed as COLUMN TABLE_CAT");

			final JsonNode payload = Json.object().put("id", 7).put("name", "Ada");
			final JsonNode result = connector.execute(null, "upsert",
				new java.util.LinkedHashMap<String, String>() {{ put("table", "customer360.account"); put("key", "id"); }}, payload);
			check(result.path("updated").asInt() == 0 && result.path("inserted").asInt() == 1 && committed.get(),
				"portable update-then-insert upsert");
			check(writeSql.get() != null && writeSql.get().contains("`customer360`.`account`"), "backtick-qualified write SQL");
		}
		finally { DriverManager.deregisterDriver(fake); }
		System.out.println("PASS: SingleStore secure Connector/J profile, catalog metadata dialect, and portable upsert");
	}

	private static ConnectorConfig configuration(final String pUrl)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("singlestore-test"); config.setType("singlestore"); config.setBaseUrl(pUrl);
		config.setAuthType(ConnectorConfig.AuthType.BASIC); config.setUsername("ebx"); config.setPassword("secret");
		return config;
	}

	private static final class FakeDriver implements Driver
	{
		private final AtomicReference<Object[]> tablesArgs;
		private final AtomicReference<Object[]> columnsArgs;
		private final AtomicReference<String> sql;
		private final AtomicBoolean committed;
		FakeDriver(final AtomicReference<Object[]> pTablesArgs, final AtomicReference<Object[]> pColumnsArgs,
			final AtomicReference<String> pSql, final AtomicBoolean pCommitted)
		{ this.tablesArgs = pTablesArgs; this.columnsArgs = pColumnsArgs; this.sql = pSql; this.committed = pCommitted; }
		public boolean acceptsURL(final String pUrl) { return pUrl != null && pUrl.startsWith("jdbc:ebx-singlestore:"); }
		public Connection connect(final String pUrl, final Properties pInfo)
		{
			if (!acceptsURL(pUrl)) return null;
			final DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (p, m, a) -> {
				if ("getIdentifierQuoteString".equals(m.getName())) return "`";
				if ("getTables".equals(m.getName())) { this.tablesArgs.set(a); return rows(new String[][] {{ "TABLE_CAT", "customer360", "TABLE_NAME", "account", "TABLE_TYPE", "TABLE" }}); }
				if ("getColumns".equals(m.getName())) { this.columnsArgs.set(a); return columns(); }
				if ("getCatalogs".equals(m.getName())) return rows(new String[][] {{ "TABLE_CAT", "customer360" }});
				return defaultValue(m.getReturnType());
			});
			return proxy(Connection.class, (p, m, a) -> {
				if ("getMetaData".equals(m.getName())) return metadata;
				if ("isReadOnly".equals(m.getName())) return true;
				if ("prepareStatement".equals(m.getName())) {
					this.sql.set(String.valueOf(a[0]));
					return proxy(PreparedStatement.class, (ps, pm, pa) -> "executeUpdate".equals(pm.getName())
						? (String.valueOf(a[0]).startsWith("UPDATE") ? 0 : 1) : defaultValue(pm.getReturnType()));
				}
				if ("commit".equals(m.getName())) { this.committed.set(true); return null; }
				return defaultValue(m.getReturnType());
			});
		}
		public DriverPropertyInfo[] getPropertyInfo(final String u, final Properties p) { return new DriverPropertyInfo[0]; }
		public int getMajorVersion() { return 1; } public int getMinorVersion() { return 0; }
		public boolean jdbcCompliant() { return false; } public Logger getParentLogger() { return Logger.getGlobal(); }
	}

	private static ResultSet columns()
	{
		return proxy(ResultSet.class, new InvocationHandler() {
			private int row = -1;
			public Object invoke(final Object p, final Method m, final Object[] a) {
				if ("next".equals(m.getName())) return ++this.row == 0;
				if ("getString".equals(m.getName())) {
					if ("COLUMN_NAME".equals(a[0])) return "id";
					if ("TYPE_NAME".equals(a[0])) return "BIGINT";
					if ("REMARKS".equals(a[0])) return "Identifier";
				}
				if ("getInt".equals(m.getName()) && "DATA_TYPE".equals(a[0])) return Types.BIGINT;
				return defaultValue(m.getReturnType());
			}
		});
	}

	private static ResultSet rows(final String[][] pRows)
	{
		return proxy(ResultSet.class, new InvocationHandler() {
			private int row = -1;
			public Object invoke(final Object p, final Method m, final Object[] a) {
				if ("next".equals(m.getName())) return ++this.row < pRows.length;
				if ("getString".equals(m.getName())) for (int i = 0; i < pRows[this.row].length; i += 2)
					if (pRows[this.row][i].equals(a[0])) return pRows[this.row][i + 1];
				return defaultValue(m.getReturnType());
			}
		});
	}

	@SuppressWarnings("unchecked") private static <T> T proxy(final Class<T> pType, final InvocationHandler pHandler)
	{ return (T) Proxy.newProxyInstance(SingleStoreConnectorTest.class.getClassLoader(), new Class<?>[] { pType }, pHandler); }
	private static Object defaultValue(final Class<?> pType)
	{
		if (!pType.isPrimitive()) return null; if (pType == Boolean.TYPE) return false; if (pType == Integer.TYPE) return 0;
		if (pType == Long.TYPE) return 0L; if (pType == Double.TYPE) return 0D; if (pType == Float.TYPE) return 0F;
		if (pType == Short.TYPE) return (short) 0; if (pType == Byte.TYPE) return (byte) 0; if (pType == Character.TYPE) return '\0'; return null;
	}
	private static boolean has(final ConfigurationValidationResult pResult, final String pField)
	{ for (final ConfigurationValidationIssue issue : pResult.getIssues()) if (pField.equals(issue.getPropertyName())) return true; return false; }
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
