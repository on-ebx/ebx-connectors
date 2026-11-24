package com.on.ebx.connect.connectors;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Operation;

/** Offline stored-procedure safety, binding, result-bound and transaction contract. */
public final class JdbcProcedureOperationTest
{
	private JdbcProcedureOperationTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final State state = new State();
		final Driver driver = new FakeDriver(state);
		DriverManager.registerDriver(driver);
		try
		{
			final JdbcConnector connector = configured();
			boolean operation = false;
			for (final Operation value : connector.getOperations()) if ("procedure".equals(value.getName())) operation = true;
			check(operation, "procedure operation missing");

			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			inputs.put("procedure", "governed.enrich_company");
			inputs.put("parameters", "[{\"name\":\"companyId\",\"mode\":\"IN\",\"type\":\"BIGINT\"},"
				+ "{\"name\":\"status\",\"mode\":\"OUT\",\"type\":\"VARCHAR\"}]");
			final JsonNode result = connector.execute(null, "procedure", inputs, Json.object().put("companyId", 42));
			check("{call \"governed\".\"enrich_company\"(?,?)}".equals(state.call.get()), "generated call");
			check(state.registered.get() == 2 && state.boundIndex.get() == 1 && Long.valueOf(42).equals(state.bound.get()),
				"IN/OUT binding");
			check("OK".equals(result.path("out").path("status").asText()) && state.committed.get(), "OUT result and commit");
			check(state.maxRows.get() == 7 && state.fetchSize.get() == 7, "result bounds");

			state.prepared.set(false);
			final Map<String, String> unsafe = new LinkedHashMap<String, String>();
			unsafe.put("call", "{call governed.proc(?)}; DELETE FROM customer");
			unsafe.put("parameters", "[{\"name\":\"id\",\"mode\":\"IN\",\"type\":\"INTEGER\"}]");
			unsafe.put("id", "7");
			try { connector.execute(null, "procedure", unsafe, null); throw new AssertionError("multi-statement call accepted"); }
			catch (final ConnectorException expected) { check(expected.getMessage().contains("call escape"), "safe rejection"); }
			check(!state.prepared.get(), "unsafe call reached prepareCall");

			final Map<String, String> mismatch = new LinkedHashMap<String, String>();
			mismatch.put("call", "{call governed.proc(?,?)}");
			mismatch.put("parameters", "[{\"name\":\"id\",\"mode\":\"IN\",\"type\":\"INTEGER\"}]");
			try { connector.execute(null, "procedure", mismatch, null); throw new AssertionError("placeholder mismatch accepted"); }
			catch (final ConnectorException expected) { check(expected.getMessage().contains("placeholder count"), "placeholder rejection"); }

			final Map<String, String> unsafeIdentifier = new LinkedHashMap<String, String>();
			unsafeIdentifier.put("procedure", "governed.proc;DROP_TABLE");
			unsafeIdentifier.put("parameters", "[]");
			rejected(connector, unsafeIdentifier, null, "safe qualified procedure identifier", "unsafe identifier accepted");

			final Map<String, String> unsupportedType = new LinkedHashMap<String, String>();
			unsupportedType.put("procedure", "governed.proc");
			unsupportedType.put("parameters", "[{\"name\":\"document\",\"mode\":\"IN\",\"type\":\"CLOB\"}]");
			rejected(connector, unsupportedType, Json.object().put("document", "x"), "unsupported JDBC type", "unsupported type accepted");

			final Map<String, String> missingInput = new LinkedHashMap<String, String>();
			missingInput.put("procedure", "governed.proc");
			missingInput.put("parameters", "[{\"name\":\"requiredId\",\"mode\":\"IN\",\"type\":\"BIGINT\"}]");
			rejected(connector, missingInput, Json.object(), "no value for IN parameter", "missing IN value accepted");

			final StringBuilder tooMany = new StringBuilder("[");
			for (int i = 0; i < 65; i++)
			{
				if (i > 0) tooMany.append(',');
				tooMany.append("{\"name\":\"p").append(i).append("\",\"mode\":\"OUT\",\"type\":\"INTEGER\"}");
			}
			tooMany.append(']');
			final Map<String, String> oversized = new LinkedHashMap<String, String>();
			oversized.put("procedure", "governed.proc"); oversized.put("parameters", tooMany.toString());
			rejected(connector, oversized, null, "parameter count exceeds", "oversized parameter list accepted");

			state.fail.set(true); state.rolledBack.set(false);
			try { connector.execute(null, "procedure", inputs, Json.object().put("companyId", 42)); throw new AssertionError("failure not propagated"); }
			catch (final ConnectorException expected) {
				check(!expected.getMessage().contains("TOP-SECRET") && expected.getMessage().contains("SQLState 42000"), "secret-safe SQL error");
			}
			check(state.rolledBack.get(), "failed procedure was not rolled back");
		}
		finally { DriverManager.deregisterDriver(driver); }
		System.out.println("PASS: JDBC stored procedure grammar, typed IN/OUT bounds, commit, rollback, and secret-safe errors");
	}

	private static JdbcConnector configured()
	{
		final ConnectorConfig config = new ConnectorConfig(); config.setBaseUrl("jdbc:ebx-procedure:test");
		config.setProperty("max.rows", "7"); config.setProperty("fetch.size", "20");
		final JdbcConnector connector = new JdbcConnector(); connector.configure(config); return connector;
	}

	private static void rejected(final JdbcConnector pConnector, final Map<String, String> pInputs,
		final JsonNode pPayload, final String pMessageFragment, final String pFailure) throws Exception
	{
		try { pConnector.execute(null, "procedure", pInputs, pPayload); throw new AssertionError(pFailure); }
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains(pMessageFragment), "unexpected rejection: " + expected.getMessage());
		}
	}

	private static final class State
	{
		final AtomicReference<String> call = new AtomicReference<String>(); final AtomicReference<Object> bound = new AtomicReference<Object>();
		final AtomicInteger boundIndex = new AtomicInteger(); final AtomicInteger registered = new AtomicInteger();
		final AtomicInteger maxRows = new AtomicInteger(); final AtomicInteger fetchSize = new AtomicInteger();
		final AtomicBoolean prepared = new AtomicBoolean(); final AtomicBoolean committed = new AtomicBoolean();
		final AtomicBoolean rolledBack = new AtomicBoolean(); final AtomicBoolean fail = new AtomicBoolean();
	}

	private static final class FakeDriver implements Driver
	{
		private final State state; FakeDriver(final State pState) { this.state = pState; }
		public boolean acceptsURL(final String pUrl) { return pUrl != null && pUrl.startsWith("jdbc:ebx-procedure:"); }
		public Connection connect(final String pUrl, final Properties pInfo)
		{
			if (!acceptsURL(pUrl)) return null;
			final DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (p, m, a) ->
				"getIdentifierQuoteString".equals(m.getName()) ? "\"" : defaultValue(m.getReturnType()));
			return proxy(Connection.class, (p, m, a) -> {
				if ("getMetaData".equals(m.getName())) return metadata;
				if ("prepareCall".equals(m.getName())) {
					this.state.prepared.set(true); this.state.call.set(String.valueOf(a[0])); return callable(this.state);
				}
				if ("commit".equals(m.getName())) { this.state.committed.set(true); return null; }
				if ("rollback".equals(m.getName())) { this.state.rolledBack.set(true); return null; }
				return defaultValue(m.getReturnType());
			});
		}
		public DriverPropertyInfo[] getPropertyInfo(final String u, final Properties p) { return new DriverPropertyInfo[0]; }
		public int getMajorVersion() { return 1; } public int getMinorVersion() { return 0; }
		public boolean jdbcCompliant() { return false; } public Logger getParentLogger() { return Logger.getGlobal(); }
	}

	private static CallableStatement callable(final State pState)
	{
		return proxy(CallableStatement.class, (p, m, a) -> {
			if ("registerOutParameter".equals(m.getName())) { pState.registered.set((Integer) a[0]); return null; }
			if ("setObject".equals(m.getName())) { pState.boundIndex.set((Integer) a[0]); pState.bound.set(a[1]); return null; }
			if ("setMaxRows".equals(m.getName())) { pState.maxRows.set((Integer) a[0]); return null; }
			if ("setFetchSize".equals(m.getName())) { pState.fetchSize.set((Integer) a[0]); return null; }
			if ("execute".equals(m.getName())) {
				if (pState.fail.get()) throw new SQLException("TOP-SECRET leaked by driver", "42000", 77); return false;
			}
			if ("getUpdateCount".equals(m.getName())) return 1;
			if ("getObject".equals(m.getName())) return "OK";
			return defaultValue(m.getReturnType());
		});
	}

	@SuppressWarnings("unchecked") private static <T> T proxy(final Class<T> pType, final InvocationHandler pHandler)
	{ return (T) Proxy.newProxyInstance(JdbcProcedureOperationTest.class.getClassLoader(), new Class<?>[] { pType }, pHandler); }
	private static Object defaultValue(final Class<?> pType)
	{
		if (!pType.isPrimitive()) return null; if (pType == Boolean.TYPE) return false; if (pType == Integer.TYPE) return 0;
		if (pType == Long.TYPE) return 0L; if (pType == Double.TYPE) return 0D; if (pType == Float.TYPE) return 0F;
		if (pType == Short.TYPE) return (short) 0; if (pType == Byte.TYPE) return (byte) 0; if (pType == Character.TYPE) return '\0'; return null;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
