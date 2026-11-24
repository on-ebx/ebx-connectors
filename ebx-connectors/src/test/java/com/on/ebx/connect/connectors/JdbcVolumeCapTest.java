package com.on.ebx.connect.connectors;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Offline checks for the volume-scalability fix (2026-09-28): a caller-supplied {@code max} used to be
 * clamped to a fixed 10,000 rows regardless of what the caller asked for (a 3-million-row source silently
 * cut to 10,000 with no warning), and a connector that pre-truncated its own array never told anyone. This
 * covers {@link JdbcConnector#maxRows} (the caller-vs-ceiling decision, pure, no driver) and
 * {@link JdbcConnector#boundedQuery} (the "did the driver hand back more than max?" decision, against a
 * minimal fake {@link ResultSet}).
 */
public final class JdbcVolumeCapTest
{
	private JdbcVolumeCapTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// no "max" input: the connector's own default (max.rows, else 1000) applies, still bounded by the ceiling
		check(JdbcConnector.maxRows(null, "1000", null) == 1000, "no max input uses the configured default");
		check(JdbcConnector.maxRows("", "1000", null) == 1000, "blank max input uses the configured default");
		check(JdbcConnector.maxRows(null, "500", null) == 500, "no max input honours a lower configured default");

		// explicit "max" input used to be clamped to 10,000 regardless: it must now honour the caller up to the
		// configurable hard ceiling, which defaults to Integer.MAX_VALUE (effectively unbounded).
		check(JdbcConnector.maxRows("3300000", "1000", null) == 3300000,
			"an explicit max above the old fixed 10,000 must be honoured when no hard ceiling is configured");
		check(JdbcConnector.maxRows(String.valueOf(Integer.MAX_VALUE), "1000", null) == Integer.MAX_VALUE,
			"an explicit max at Integer.MAX_VALUE is honoured with no configured ceiling");

		// a configured max.rows.hard still caps an explicit request above it
		check(JdbcConnector.maxRows("50000", "1000", "20000") == 20000,
			"an explicit max above a configured hard ceiling is capped at the ceiling, not silently unbounded");
		check(JdbcConnector.maxRows("5000", "1000", "20000") == 5000,
			"an explicit max under the configured hard ceiling is honoured exactly");

		// bounds and garbage inputs behave the same way boundedInt always has: an out-of-range but parsable
		// value is clamped into range, only an unparsable one falls back to the default.
		check(JdbcConnector.maxRows("0", "1000", null) == 1, "max below 1 is clamped to the minimum of 1");
		check(JdbcConnector.maxRows("not a number", "1000", null) == 1000, "unparsable max falls back to the default");

		// boundedQuery: the driver is asked for one extra row (max + 1) to detect truncation without a second query
		final AtomicInteger requestedMaxRows = new AtomicInteger(-1);
		final PreparedStatement underLimit = fakeStatement(requestedMaxRows, 2);
		final ObjectNode underResult = JdbcConnector.boundedQuery(underLimit, 5);
		check(requestedMaxRows.get() == 6, "boundedQuery must request max + 1 rows from the driver: " + requestedMaxRows.get());
		check(underResult.path("count").asInt() == 2, "fewer rows than max: count must be the real row count");
		check(!underResult.path("truncated").asBoolean(true), "fewer rows than max must not be reported as truncated");

		final PreparedStatement exactLimit = fakeStatement(requestedMaxRows, 5);
		final ObjectNode exactResult = JdbcConnector.boundedQuery(exactLimit, 5);
		check(exactResult.path("count").asInt() == 5, "exactly max rows available: count must equal max");
		check(!exactResult.path("truncated").asBoolean(true), "exactly max rows available must not be reported as truncated");

		final PreparedStatement overLimit = fakeStatement(requestedMaxRows, 10); // the driver still caps its answer at the probe (max + 1 = 6)
		final ObjectNode overResult = JdbcConnector.boundedQuery(overLimit, 5);
		check(overResult.path("count").asInt() == 5, "more rows than max: the extra probe row must be trimmed off, not returned");
		check(overResult.path("truncated").asBoolean(false), "more rows than max must be reported truncated");

		// no configured ceiling at all (Integer.MAX_VALUE): the driver is asked for "no limit" (JDBC setMaxRows(0))
		// and truncation can never be claimed, since nothing capped the read.
		final PreparedStatement unbounded = fakeStatement(requestedMaxRows, 3);
		final ObjectNode unboundedResult = JdbcConnector.boundedQuery(unbounded, Integer.MAX_VALUE);
		check(requestedMaxRows.get() == 0, "Integer.MAX_VALUE max must ask the driver for no limit (setMaxRows(0)): " + requestedMaxRows.get());
		check(!unboundedResult.path("truncated").asBoolean(true), "an unbounded read must never be reported truncated");

		System.out.println("PASS: JDBC max-rows ceiling honours an explicit caller max, and boundedQuery detects truncation without a second query");
	}

	/**
	 * A statement whose {@code executeQuery()} returns exactly {@code min(pRealRowCount, requestedMaxRows)} rows,
	 * the same way a real JDBC driver honours {@code setMaxRows} - {@code 0} means "no limit" per the JDBC contract.
	 */
	private static PreparedStatement fakeStatement(final AtomicInteger pRequestedMaxRows, final int pRealRowCount)
	{
		final ResultSetMetaData meta = proxy(ResultSetMetaData.class, new InvocationHandler()
		{
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
			{
				if ("getColumnCount".equals(pMethod.getName())) return 1;
				if ("getColumnLabel".equals(pMethod.getName()) || "getColumnName".equals(pMethod.getName())) return "ID";
				return defaultValue(pMethod.getReturnType());
			}
		});
		return proxy(PreparedStatement.class, new InvocationHandler()
		{
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
			{
				if ("setMaxRows".equals(pMethod.getName()))
				{
					pRequestedMaxRows.set((Integer) pArgs[0]);
					return null;
				}
				if ("executeQuery".equals(pMethod.getName()))
				{
					final int requested = pRequestedMaxRows.get();
					final int cap = requested == 0 ? pRealRowCount : Math.min(pRealRowCount, requested);
					return proxy(ResultSet.class, new InvocationHandler()
					{
						private int row = -1;
						public Object invoke(final Object p, final Method m, final Object[] a)
						{
							if ("next".equals(m.getName())) return ++this.row < cap;
							if ("getMetaData".equals(m.getName())) return meta;
							if ("getObject".equals(m.getName())) return Long.valueOf(this.row);
							return defaultValue(m.getReturnType());
						}
					});
				}
				return defaultValue(pMethod.getReturnType());
			}
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(final Class<T> pType, final InvocationHandler pHandler)
	{
		return (T) Proxy.newProxyInstance(JdbcVolumeCapTest.class.getClassLoader(), new Class<?>[] { pType }, pHandler);
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
