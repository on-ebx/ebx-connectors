package com.on.ebx.connect.connectors;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;

/** Deterministic SQL Server metadata binding, status, and warning checks. */
public final class SqlServerMetadataTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final List<String> sql = new ArrayList<String>();
		final List<List<String>> bindings = new ArrayList<List<String>>();
		final Connection denied = connection(sql, bindings, new SQLException("hidden detail", "42000", 229),
			new SQLException("hidden detail", "28000"));
		final ObjectNode target = target();
		SqlServerMetadata.read(denied, "odd schema", "table]name", target, true);
		check(sql.size() == 2 && !sql.get(0).contains("odd schema") && !sql.get(0).contains("table]name"), "identifiers were interpolated");
		check(bindings.get(0).equals(Arrays.asList("odd schema", "odd schema", "table]name")), "extended-property scope was not bound");
		check(bindings.get(1).equals(Arrays.asList("odd schema", "table]name")), "usage scope was not bound");
		check("ACCESS_DENIED".equals(target.path("sourceExtendedPropertiesStatus").asText()), "permission status missing");
		check("ACCESS_DENIED".equals(target.path("sourceUsageStatus").asText()), "usage permission status missing");
		check(target.path("sourceMetadataWarnings").size() == 2 && !target.toString().contains("hidden detail"), "safe warnings missing");

		final ObjectNode empty = target();
		SqlServerMetadata.read(connection(new ArrayList<String>(), new ArrayList<List<String>>(), new SQLException[] { null }), "dbo", "T", empty, false);
		check("VISIBLE_METADATA_ONLY".equals(empty.path("sourceExtendedPropertiesStatus").asText()), "empty visible metadata failed");
		check("NOT_REQUESTED".equals(empty.path("sourceUsageStatus").asText()) && !empty.has("sourceMetadataWarnings"), "not-requested semantics failed");
		check("UNAVAILABLE".equals(SqlServerMetadata.status(new SQLException("syntax", "42000", 102))), "syntax is not permission denial");
		check("UNAVAILABLE".equals(SqlServerMetadata.status(new SQLException("unknown object", "42S02", 208))), "missing DMV is not permission denial");
		check("LIMIT_EXCEEDED".equals(SqlServerMetadata.status(new SQLException("limit", "54000"))), "truncation is explicit");
		final ObjectNode noCounters = target();
		SqlServerMetadata.read(connection(new ArrayList<String>(), new ArrayList<List<String>>(), new SQLException[] { null, null }), "dbo", "T", noCounters, true);
		check("NO_VISIBLE_COUNTERS".equals(noCounters.path("sourceUsageStatus").asText()), "missing counters are not zero");
		check(!noCounters.path("sourceUsage").asText().contains("\"seeks\":0"), "absent usage not fabricated");
		check(noCounters.path("sourceUsage").asText().contains("\"harvestedSchema\":\"dbo\""), "usage provenance");
		final ObjectNode literal = target();
		SqlServerMetadata.read(propertyConnection(), "dbo", "T", literal, false);
		final com.fasterxml.jackson.databind.JsonNode property = Json.parse(literal.path("sourceExtendedProperties").asText()).get(0);
		check(property.path("majorId").asInt()==42 && property.path("minorId").asInt()==3, "source IDs preserved");
		check("Default; 18 Months".equals(property.path("value").asText()), "literal property not policy interpreted");
		check("nvarchar".equals(property.path("type").asText()), "source type preserved");
		check(Json.parse(literal.path("columns").get(0).path("sourceExtendedProperties").asText()).size()==1, "column association retained");
		System.out.println("PASS: SQL Server metadata uses bound scope, bounded safe warnings, and explicit permission/not-requested states");
	}

	private static ObjectNode target()
	{
		final ObjectNode target = Json.object();
		target.putArray("columns").addObject().put("name", "C");
		return target;
	}

	private static Connection connection(final List<String> pSql, final List<List<String>> pBindings,
		final SQLException... pFailures)
	{
		final int[] prepared = { 0 };
		return proxy(Connection.class, (p, method, args) -> {
			if ("prepareStatement".equals(method.getName()))
			{
				check(args.length == 3 && ((Integer)args[2]).intValue() == ResultSet.CONCUR_READ_ONLY, "read-only result set required");
				pSql.add((String) args[0]);
				final int index = prepared[0]++;
				final List<String> values = new ArrayList<String>(); pBindings.add(values);
				return proxy(PreparedStatement.class, (statement, called, parameters) -> {
					if ("setString".equals(called.getName())) { while (values.size() < ((Integer) parameters[0])) values.add(null); values.set(((Integer) parameters[0]) - 1, (String) parameters[1]); return null; }
					if ("executeQuery".equals(called.getName())) { if (index < pFailures.length && pFailures[index] != null) throw pFailures[index]; return emptyRows(); }
					return defaultValue(called.getReturnType());
				});
			}
			return defaultValue(method.getReturnType());
		});
	}

	private static Connection propertyConnection() {
		final Object[] values={Integer.valueOf(1),Integer.valueOf(42),Integer.valueOf(3),"Policy note","Default; 18 Months","nvarchar","C"};
		return proxy(Connection.class,(p,m,a)->{
			if(!"prepareStatement".equals(m.getName()))return defaultValue(m.getReturnType());
			return proxy(PreparedStatement.class,(s,sm,sa)->{
				if(!"executeQuery".equals(sm.getName()))return defaultValue(sm.getReturnType());
				final int[] row={0};
				return proxy(ResultSet.class,(r,rm,ra)->{
					if("next".equals(rm.getName()))return row[0]++==0;
					if("getInt".equals(rm.getName())||"getString".equals(rm.getName()))return values[((Integer)ra[0])-1];
					return defaultValue(rm.getReturnType());
				});
			});
		});
	}
	private static ResultSet emptyRows() { return proxy(ResultSet.class, (p, m, a) -> "next".equals(m.getName()) ? false : defaultValue(m.getReturnType())); }
	@SuppressWarnings("unchecked") private static <T> T proxy(final Class<T> pType, final InvocationHandler pHandler) { return (T) Proxy.newProxyInstance(pType.getClassLoader(), new Class<?>[] { pType }, pHandler); }
	private static Object defaultValue(final Class<?> pType) { if (!pType.isPrimitive()) return null; if (pType == boolean.class) return false; if (pType == byte.class) return (byte) 0; if (pType == short.class) return (short) 0; if (pType == int.class) return 0; if (pType == long.class) return 0L; if (pType == float.class) return 0F; if (pType == double.class) return 0D; if (pType == char.class) return '\0'; return null; }
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
