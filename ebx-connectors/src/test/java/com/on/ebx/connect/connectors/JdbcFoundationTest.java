package com.on.ebx.connect.connectors;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;
import com.on.ebx.connect.spi.PreviewRequest;
import com.on.ebx.connect.spi.PreviewResult;

/** Offline fake-driver checks for JDBC discovery, read-only gating and transaction bounds. */
public final class JdbcFoundationTest
{
	private JdbcFoundationTest() { }

	/** When set, the fake driver behaves like H2 over TCP: setReadOnly(true) is accepted but isReadOnly() stays false. */
	private static final AtomicBoolean REFUSES_READ_ONLY = new AtomicBoolean();

	public static void main(final String[] pArgs) throws Exception
	{
		final AtomicBoolean readOnly = new AtomicBoolean();
		final AtomicBoolean prepared = new AtomicBoolean();
		final AtomicBoolean rolledBack = new AtomicBoolean();
		final AtomicReference<String> preparedSql = new AtomicReference<String>();
		final Driver driver = new FakeDriver(readOnly, prepared, rolledBack, preparedSql);
		DriverManager.registerDriver(driver);
		try
		{
			final JdbcConnector connector = configured();
			final ObjectDiscoveryResult result = connector.discoverObjects(null,
				ObjectDiscoveryRequest.firstPage("cust", 10));
			check(result.isSupported() && result.getObjects().size() == 1,
				"metadata table discovery did not filter or return the fake table");
			check("PUBLIC.CUSTOMER".equals(result.getObjects().get(0).getId()),
				"discovered table did not retain its schema");
			check(readOnly.get() && rolledBack.get(), "metadata discovery was not read-only and rolled back");

			final PreviewResult preview = connector.preview(null,
				new PreviewRequest("PUBLIC.CUSTOMER", "PUBLIC.CUSTOMER", null, 1));
			check(preview.isSupported() && preview.getRecords().size() == 1,
				"discovered table could not be previewed");
			check("SELECT * FROM \"PUBLIC\".\"CUSTOMER\"".equals(preparedSql.get()),
				"table preview was not generated with quoted identifiers");

			prepared.set(false);
			final Map<String, String> unsafe = new LinkedHashMap<String, String>();
			unsafe.put("sql", "DELETE FROM CUSTOMER");
			try { connector.execute(null, "query", unsafe, null); throw new AssertionError("unsafe query was accepted"); }
			catch (final ConnectorException expected) { /* expected */ }
			check(!prepared.get(), "unsafe query reached prepareStatement");

			final ArrayNode documents = Json.mapper().createArrayNode();
			documents.addObject().put("id", 1);
			documents.addObject().put("id", 2);
			try { connector.execute(null, "insert", Collections.singletonMap("table", "CUSTOMER"), documents); throw new AssertionError("oversized batch was accepted"); }
			catch (final ConnectorException expected) { /* expected */ }

			final java.util.List<Object> values = new java.util.ArrayList<Object>();
			final Map<String, Object> parameters = new LinkedHashMap<String, Object>();
			parameters.put("id", Long.valueOf(7));
			parameters.put("name", "Ada");
			check("SELECT * FROM t WHERE id=? OR owner=?".equals(
				JdbcConnector.bind("SELECT * FROM t WHERE id=:id OR owner=:name", parameters, values)),
				"named parameters were not converted to JDBC placeholders");
			check(values.size() == 2 && Long.valueOf(7).equals(values.get(0)) && "Ada".equals(values.get(1)),
				"named parameters were not retained in statement order");

			// H2 over TCP never reports read-only mode: query must still run (inside a rolled-back transaction),
			// and a non-SELECT must still be refused before reaching the driver (MDM Factory landing, 2026-09-24).
			REFUSES_READ_ONLY.set(true);
			rolledBack.set(false);
			final JsonNode rows = connector.execute(null, "query", Collections.singletonMap("sql", "SELECT ID FROM CUSTOMER"), null);
			check(rows != null && rows.path("count").asInt() == 1, "query failed on a driver that ignores read-only mode");
			check(rolledBack.get(), "query on a driver that ignores read-only mode was not rolled back");
			prepared.set(false);
			try { connector.execute(null, "query", unsafe, null); throw new AssertionError("unsafe query accepted without read-only mode"); }
			catch (final ConnectorException expected) { /* expected */ }
			check(!prepared.get(), "unsafe query reached prepareStatement without read-only mode");
			REFUSES_READ_ONLY.set(false);
		}
		finally { DriverManager.deregisterDriver(driver); }
		System.out.println("PASS: JDBC metadata discovery, parameter binding and safety bounds");
	}

	private static JdbcConnector configured()
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setBaseUrl("jdbc:ebx-foundation:test");
		config.setProperty("batch.size", "1");
		final JdbcConnector connector = new JdbcConnector();
		connector.configure(config);
		return connector;
	}

	private static final class FakeDriver implements Driver
	{
		private final AtomicBoolean readOnly;
		private final AtomicBoolean prepared;
		private final AtomicBoolean rolledBack;
		private final AtomicReference<String> preparedSql;
		FakeDriver(final AtomicBoolean pReadOnly, final AtomicBoolean pPrepared, final AtomicBoolean pRolledBack,
			final AtomicReference<String> pPreparedSql)
		{
			this.readOnly = pReadOnly; this.prepared = pPrepared; this.rolledBack = pRolledBack; this.preparedSql = pPreparedSql;
		}
		public boolean acceptsURL(final String pUrl) { return pUrl != null && pUrl.startsWith("jdbc:ebx-foundation:"); }
		public Connection connect(final String pUrl, final Properties pInfo)
		{
			if (!acceptsURL(pUrl)) return null;
			final ResultSet tables = proxy(ResultSet.class, new InvocationHandler()
			{
				private int row = -1;
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
				{
					if ("next".equals(pMethod.getName())) return ++this.row == 0;
					if ("getString".equals(pMethod.getName()))
					{
						final String column = String.valueOf(pArgs[0]);
						if ("TABLE_SCHEM".equals(column)) return "PUBLIC";
						if ("TABLE_NAME".equals(column)) return "CUSTOMER";
						if ("TABLE_TYPE".equals(column)) return "TABLE";
					}
					return defaultValue(pMethod.getReturnType());
				}
			});
			final DatabaseMetaData metadata = proxy(DatabaseMetaData.class, new InvocationHandler()
			{
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
				{
					if ("getTables".equals(pMethod.getName())) return tables;
					if ("getSchemas".equals(pMethod.getName())) return proxy(ResultSet.class, new InvocationHandler() {
						private int row = -1;
						public Object invoke(final Object p, final Method m, final Object[] a) { if ("next".equals(m.getName())) return ++row == 0; if ("getString".equals(m.getName())) return "PUBLIC"; return defaultValue(m.getReturnType()); }
					});
					if ("getIdentifierQuoteString".equals(pMethod.getName())) return "\"";
					return defaultValue(pMethod.getReturnType());
				}
			});
			return proxy(Connection.class, new InvocationHandler()
			{
				public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
				{
					if ("getMetaData".equals(pMethod.getName())) return metadata;
					if ("setReadOnly".equals(pMethod.getName())) { readOnly.set(Boolean.TRUE.equals(pArgs[0])); return null; }
					if ("isReadOnly".equals(pMethod.getName())) return readOnly.get() && !REFUSES_READ_ONLY.get();
					if ("prepareStatement".equals(pMethod.getName())) {
						prepared.set(true); preparedSql.set(String.valueOf(pArgs[0]));
						final ResultSetMetaData rowMeta = proxy(ResultSetMetaData.class, new InvocationHandler() {
							public Object invoke(final Object p, final Method m, final Object[] a) { if ("getColumnCount".equals(m.getName())) return 1; if ("getColumnLabel".equals(m.getName()) || "getColumnName".equals(m.getName())) return "ID"; return defaultValue(m.getReturnType()); }
						});
						final ResultSet rows = proxy(ResultSet.class, new InvocationHandler() {
							private int row = -1;
							public Object invoke(final Object p, final Method m, final Object[] a) { if ("next".equals(m.getName())) return ++row == 0; if ("getMetaData".equals(m.getName())) return rowMeta; if ("getObject".equals(m.getName())) return Long.valueOf(7); return defaultValue(m.getReturnType()); }
						});
						return proxy(java.sql.PreparedStatement.class, new InvocationHandler() { public Object invoke(final Object p, final Method m, final Object[] a) { if ("executeQuery".equals(m.getName())) return rows; return defaultValue(m.getReturnType()); } });
					}
					if ("rollback".equals(pMethod.getName())) { rolledBack.set(true); return null; }
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
		return (T) Proxy.newProxyInstance(JdbcFoundationTest.class.getClassLoader(), new Class<?>[] { pType }, pHandler);
	}

	private static Object defaultValue(final Class<?> pType)
	{
		if (!pType.isPrimitive()) return null;
		if (pType == Boolean.TYPE) return false;
		if (pType == Integer.TYPE) return 0;
		if (pType == Long.TYPE) return 0L;
		if (pType == Double.TYPE) return 0D;
		if (pType == Float.TYPE) return 0F;
		if (pType == Short.TYPE) return (short) 0;
		if (pType == Byte.TYPE) return (byte) 0;
		if (pType == Character.TYPE) return '\0';
		return null;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
