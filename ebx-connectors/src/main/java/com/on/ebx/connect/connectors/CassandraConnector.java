package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDependency;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** Apache Cassandra prepared-CQL core. CDC and schema mutation are intentionally out of scope. */
public final class CassandraConnector implements com.on.ebx.connect.spi.Connector
{
	public static final String DRIVER_VERSION = "3.11.5";
	private static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
		Operation.search("keyspaces", "Discover bounded non-system keyspaces"),
		Operation.search("tables", "Discover bounded tables in an explicit keyspace", "keyspace"),
		new Operation("read", Operation.Kind.READ, "Execute approved prepared SELECT CQL", new String[] { "cql" }, new String[] { "values", "pageState", "pageSize" }),
		new Operation("write", Operation.Kind.WRITE, "Execute approved prepared INSERT/UPDATE/DELETE CQL", new String[] { "cql" }, new String[] { "values" })));
	private ConnectorConfig config;
	private final Backend backend;

	public CassandraConnector() { this(new ReflectiveBackend()); }
	CassandraConnector(final Backend pBackend) { this.backend = pBackend; }
	@Override public String getType() { return "cassandra"; }
	@Override public String getLabel() { return "Apache Cassandra"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }
	@Override public void configure(final ConnectorConfig pConfig) { this.config = pConfig; }

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final List<ConfigurationValidationIssue> issues = new java.util.ArrayList<ConfigurationValidationIssue>();
		if (pConfig == null || pConfig.getBaseUrl() == null || !pConfig.getBaseUrl().matches("[A-Za-z0-9._-]+(:[0-9]{1,5})?(,[A-Za-z0-9._-]+(:[0-9]{1,5})?)*"))
			issues.add(new ConfigurationValidationIssue("baseUrl", "Use comma-separated Cassandra contact points, optionally host:port; URLs are not accepted"));
		bound(pConfig, "page.size", 1, 1000, issues);
		bound(pConfig, "max.objects", 1, 1000, issues);
		if (pConfig != null && "false".equalsIgnoreCase(pConfig.getProperty("tls", "true")))
			issues.add(new ConfigurationValidationIssue("tls", "TLS must remain enabled"));
		return new ConfigurationValidationResult(issues);
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "DATABASE")
			.description("Apache Cassandra keyspace/table discovery and bounded prepared-CQL reads/writes; no CDC or schema mutation.")
			.authentication(AuthenticationMethod.BASIC).capability(ConnectorCapability.TEST_CONNECTION)
			.capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.PREVIEW_RECORDS)
			.capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE)
			.apiVersion("Cassandra native protocol v4")
			.dependency(new ConnectorDependency("DataStax Java Driver 3", "com.datastax.cassandra:cassandra-driver-core:" + DRIVER_VERSION, true,
				"Pinned Java 8-compatible driver; package it and its dependencies under WEB-INF/lib"))
			.pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.CURSOR, 100, 1000, "pageState"))
			.configurationProperty(prop("page.size", ConfigurationPropertyType.INTEGER))
			.configurationProperty(prop("max.objects", ConfigurationPropertyType.INTEGER))
			.configurationProperty(prop("local.datacenter", ConfigurationPropertyType.STRING))
			.configurationProperty(prop("tls", ConfigurationPropertyType.BOOLEAN));
		for (final Operation o : OPERATIONS)
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String,String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		if (this.config == null) throw new ConnectorException("Cassandra connector is not configured");
		final int maximum = integer(this.config.getProperty("max.objects", "100"), 1, 1000);
		if ("keyspaces".equals(pOperation)) return this.backend.keyspaces(this.config, maximum);
		if ("tables".equals(pOperation)) return this.backend.tables(this.config, name(pInputs,"keyspace"), maximum);
		final String cql = required(pInputs,"cql");
		if (cql.indexOf(';') >= 0) throw new ConnectorException("Cassandra accepts one CQL statement without a semicolon");
		final List<String> values = csv(pInputs == null ? null : pInputs.get("values"));
		if ("read".equals(pOperation)) {
			if (!cql.trim().toUpperCase(java.util.Locale.ENGLISH).startsWith("SELECT ")) throw new ConnectorException("Cassandra read requires SELECT CQL");
			final int size=integer(pInputs.get("pageSize")==null?this.config.getProperty("page.size","100"):pInputs.get("pageSize"),1,1000);
			final String state = pInputs.get("pageState");
			if (state != null && state.length() > 4096) throw new ConnectorException("Cassandra pageState exceeds 4096 characters");
			return this.backend.read(this.config,cql,values,size,state);
		}
		if ("write".equals(pOperation))
		{
			final String u = cql.trim().toUpperCase(java.util.Locale.ENGLISH);
			if (!(u.startsWith("INSERT ") || u.startsWith("UPDATE ") || u.startsWith("DELETE "))) throw new ConnectorException("Cassandra write permits INSERT, UPDATE, or DELETE only");
			return this.backend.write(this.config, cql, values, pPayload);
		}
		throw new ConnectorException("Cassandra: unknown operation '"+pOperation+"'");
	}

	interface Backend
	{
		JsonNode keyspaces(ConnectorConfig c, int max)throws ConnectorException;
		JsonNode tables(ConnectorConfig c, String k, int max)throws ConnectorException;
		JsonNode read(ConnectorConfig c, String q, List<String> v, int size, String state)throws ConnectorException;
		JsonNode write(ConnectorConfig c, String q, List<String> v, JsonNode p)throws ConnectorException;
	}
	private static final class ReflectiveBackend implements Backend {
		public JsonNode keyspaces(ConnectorConfig c, int maximum)throws ConnectorException
		{
			Object cluster = null, session = null;
			try
			{
				cluster = cluster(c);
				session = call(cluster, "connect");
				Object metadata = call(cluster, "getMetadata");
				com.fasterxml.jackson.databind.node.ArrayNode items = com.on.ebx.connect.core.Json.mapper().createArrayNode();
				for (Object k:(Iterable<?>)call(metadata, "getKeyspaces"))
				{
					String n = String.valueOf(call(k, "getName"));
					if (!n.startsWith("system") && items.size() < maximum) items.addObject().put("name", n);
				}
				return com.on.ebx.connect.core.Json.object().put("count", items.size()).set("keyspaces", items);
			}
			catch (Exception e)
			{
				throw failure(e);
			}
			finally
			{
				close(session);
				close(cluster);
			}
		}
		public JsonNode tables(ConnectorConfig c, String keyspace, int maximum)throws ConnectorException
		{
			Object cluster = null, session = null;
			try
			{
				cluster = cluster(c);
				session = call(cluster, "connect");
				Object metadata = call(cluster, "getMetadata"), ks = call(metadata, "getKeyspace", keyspace);
				if (ks == null) throw new ConnectorException("Cassandra keyspace not found");
				com.fasterxml.jackson.databind.node.ArrayNode items = com.on.ebx.connect.core.Json.mapper().createArrayNode();
				for (Object t:(Iterable<?>)call(ks, "getTables"))
				{
					if (items.size() >= maximum) break;
					items.addObject().put("name", String.valueOf(call(t, "getName")));
				}
				return com.on.ebx.connect.core.Json.object().put("keyspace", keyspace).put("count", items.size()).set("tables", items);
			}
			catch (ConnectorException e)
			{
				throw e;
			}
			catch (Exception e)
			{
				throw failure(e);
			}
			finally
			{
				close(session);
				close(cluster);
			}
		}
		public JsonNode read(ConnectorConfig c, String q, List<String> values, int size, String state)throws ConnectorException
		{
			Object cluster = null, session = null;
			try
			{
				cluster = cluster(c);
				session = call(cluster, "connect");
				Object statement = bound(session, q, values);
				call(statement, "setFetchSize", Integer.valueOf(size));
				if (state != null)
				{
					Class<?> ps = Class.forName("com.datastax.driver.core.PagingState");
					Object paging = ps.getMethod("fromString", String.class).invoke(null, state);
					call(statement, "setPagingState", paging);
				}
				Object result = call(session, "execute", statement);
				com.fasterxml.jackson.databind.node.ArrayNode rows = com.on.ebx.connect.core.Json.mapper().createArrayNode();
				int available = ((Number)call(result, "getAvailableWithoutFetching")).intValue();
				for (int i = 0; i < Math.min(size, available); i++)
				{
					Object row = call(result, "one");
					if (row == null) break;
					com.fasterxml.jackson.databind.node.ObjectNode out = rows.addObject();
					Object definitions = call(row, "getColumnDefinitions");
					int count = ((Number)call(definitions, "size")).intValue();
					for (int column = 0; column < count; column++)
					{
						Object definition = call(definitions, "get", Integer.valueOf(column));
						String name = String.valueOf(call(definition, "getName"));
						Object value = call(row, "getObject", Integer.valueOf(column));
						out.set(name, com.on.ebx.connect.core.Json.mapper().valueToTree(value));
					}
				}
				Object info = call(result, "getExecutionInfo"), paging = call(info, "getPagingState");
				com.fasterxml.jackson.databind.node.ObjectNode answer = com.on.ebx.connect.core.Json.object();
				answer.put("count", rows.size());
				answer.set("rows", rows);
				if (paging != null) answer.put("pageState", String.valueOf(paging));
				return answer;
			}
			catch (Exception e)
			{
				throw failure(e);
			}
			finally
			{
				close(session);
				close(cluster);
			}
		}
		public JsonNode write(ConnectorConfig c, String q, List<String> values, JsonNode payload)throws ConnectorException
		{
			Object cluster = null, session = null;
			try
			{
				cluster = cluster(c);
				session = call(cluster, "connect");
				Object result = call(session, "execute", bound(session, q, values));
				return com.on.ebx.connect.core.Json.object().put("applied", Boolean.TRUE.equals(call(result, "wasApplied")));
			}
			catch (Exception e)
			{
				throw failure(e);
			}
			finally
			{
				close(session);
				close(cluster);
			}
		}
		private static Object cluster(ConnectorConfig c)throws Exception
		{
			Class<?> type = Class.forName("com.datastax.driver.core.Cluster");
			Object builder = type.getMethod("builder").invoke(null);
			String[] entries = c.getBaseUrl().split(",");
			int port = 9042;
			for (String entry:entries)
			{
				String[] hp = entry.trim().split(":", 2);
				if (hp.length == 2) port = Integer.parseInt(hp[1]);
				call(builder, "addContactPoint", hp[0]);
			}
			call(builder, "withPort", Integer.valueOf(port));
			if (c.getUsername() != null && !c.getUsername().isEmpty()) call(builder, "withCredentials", c.getUsername(), c.getPassword());
			if (!"false".equalsIgnoreCase(c.getProperty("tls", "true"))) call(builder, "withSSL");
			return call(builder, "build");
		}
		private static Object bound(Object session, String q, List<String> values)throws Exception
		{
			Object prepared = call(session, "prepare", q), bound = call(prepared, "bind");
			for (int i = 0; i < values.size(); i++) call(bound, "setString", Integer.valueOf(i), values.get(i));
			return bound;
		}
		private static Object call(Object target, String name, Object...args)throws Exception
		{
			for (java.lang.reflect.Method method:target.getClass().getMethods())
			{
				if (method.getName().equals(name) && method.getParameterTypes().length == args.length)
				{
					try
					{
						return method.invoke(target, args);
					}
					catch (IllegalArgumentException ignored) { }
				}
			}
			throw new NoSuchMethodException(target.getClass().getName()+"."+name);
		}
		private static void close(Object value)
		{
			if (value == null) return;
			try
			{
				call(value, "close");
			}
			catch (Exception ignored) { }
		}
		private static ConnectorException failure(Exception e)
		{
			Throwable x = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
			return new ConnectorException("Cassandra operation failed: "+(x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage()), x);
		}
	}
	private static void bound(ConnectorConfig c, String n, int lo, int hi, List<ConfigurationValidationIssue> out)
	{
		if (c == null) return;
		String v = c.getProperty(n, null);
		if (v == null) return;
		try
		{
			int x = Integer.parseInt(v);
			if (x < lo || x > hi) throw new Exception();
		}
		catch (Exception e)
		{
			out.add(new ConfigurationValidationIssue(n, "Must be between "+lo+" and "+hi));
		}
	}
	private static int integer(String v, int lo, int hi)throws ConnectorException
	{
		try
		{
			int x = Integer.parseInt(v);
			if (x < lo || x > hi) throw new Exception();
			return x;
		}
		catch (Exception e)
		{
			throw new ConnectorException("Value must be between "+lo+" and "+hi);
		}
	}
	private static String required(Map<String, String>m, String n)throws ConnectorException
	{
		String v = m == null ? null : m.get(n);
		if (v == null || v.trim().isEmpty()) throw new ConnectorException("Missing input '"+n+"'");
		return v.trim();
	}
	private static String name(Map<String, String>m, String n)throws ConnectorException
	{
		String v = required(m, n);
		if (!v.matches("[A-Za-z][A-Za-z0-9_]*")) throw new ConnectorException("Invalid Cassandra "+n);
		return v;
	}
	private static List<String> csv(String v){return v==null||v.isEmpty()?Collections.<String>emptyList():Arrays.asList(v.split(",",-1));}
	private static ConfigurationPropertyDefinition prop(String n,ConfigurationPropertyType t){return ConfigurationPropertyDefinition.builder(n,t).required(false).build();}
}
