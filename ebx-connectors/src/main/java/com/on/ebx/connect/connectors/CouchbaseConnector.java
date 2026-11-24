package com.on.ebx.connect.connectors;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.*;

/** Couchbase KV connector using the optional Java SDK 3.6.2 (Java 8 compatible). */
public final class CouchbaseConnector implements Connector
{
	public static final String SDK_VERSION = "3.6.2";
	private static final List<Operation> OPS = Arrays.asList(Operation.search("buckets", "Discover bounded buckets", "max"), Operation.search("collections", "Discover bounded scopes and collections", "bucket", "max"), Operation.read("get", "Get one KV document", "bucket", "scope", "collection", "id"), Operation.write("upsert", "Upsert one bounded JSON document", "bucket", "scope", "collection", "id"), Operation.write("remove", "Remove one KV document", "bucket", "scope", "collection", "id"));
	interface Adapter
	{
		List<String> buckets(int max)throws Exception;
		List<String> collections(String bucket, int max)throws Exception;
		String get(String b, String s, String c, String id)throws Exception;
		void upsert(String b, String s, String c, String id, String json)throws Exception;
		void remove(String b, String s, String c, String id)throws Exception;
		void close();
	}
	interface AdapterFactory{Adapter open(ConnectorConfig config)throws Exception;}
	private ConnectorConfig config;
	private AdapterFactory factory = new ReflectionFactory();
	@Override
	public String getType()
	{
		return "couchbase";
	}
	@Override
	public String getLabel()
	{
		return "Couchbase";
	}
	@Override
	public List<Operation> getOperations()
	{
		return OPS;
	}
	@Override
	public void configure(ConnectorConfig c)
	{
		config = c;
	}
	void setAdapterFactory(AdapterFactory f)
	{
		factory = f;
	}
	@Override
	public ConfigurationValidationResult validateConfiguration(ConnectorConfig c)
	{
		List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(Connector.super.validateConfiguration(c).getIssues());
		if (c != null && (c.getBaseUrl() == null || !c.getBaseUrl().startsWith("couchbases://"))) issues.add(new ConfigurationValidationIssue("baseUrl", "Couchbase production connections must use couchbases:// with TLS"));
		if (c != null && (c.getUsername() == null || c.isPlaceholder(c.getPassword()))) issues.add(new ConfigurationValidationIssue("password", "Couchbase username and password are required"));
		return new ConfigurationValidationResult(issues);
	}
	@Override
	public ConnectorDescriptor getDescriptor()
	{
		ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "DATA_PLATFORMS").description("Couchbase bounded bucket/scope/collection discovery and KV get/upsert/remove only. SQL++, deep paging, DCP, and CDC are excluded.").authentication(AuthenticationMethod.BASIC).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).apiVersion("Couchbase Java SDK "+SDK_VERSION+" / KV; live cluster acceptance required").dependency(new ConnectorDependency("Couchbase Java SDK", "com.couchbase.client:java-client:"+SDK_VERSION, true, "Optional pinned Java 8-compatible SDK and its core dependency must be packaged under WEB-INF/lib")).defaults(ConnectorDefaults.standard());
		for (Operation o:OPS)
		{
			ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	@Override
	public JsonNode execute(ConnectorContext x, String op, Map<String, String> in, JsonNode payload)throws ConnectorException
	{
		Adapter a = null;
		try
		{
			a = factory.open(config);
			if ("buckets".equals(op)) return array(a.buckets(bound(in.get("max"), 100)));
			if ("collections".equals(op)) return array(a.collections(req(in, "bucket"), bound(in.get("max"), 500)));
			String b = req(in, "bucket"), s = value(in, "scope", "_default"), c = value(in, "collection", "_default"), id = req(in, "id");
			if ("get".equals(op))
			{
				String json = a.get(b, s, c, id);
				return json == null ? Json.object() : Json.parse(json);
			}
			if ("upsert".equals(op))
			{
				if (payload == null) throw new ConnectorException("Couchbase upsert requires a JSON payload");
				byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
				if (bytes.length > 1024*1024) throw new ConnectorException("Couchbase document exceeds 1 MiB connector limit");
				a.upsert(b, s, c, id, payload.toString());
				ObjectNode out = Json.object();
				out.put("id", id);
				out.put("upserted", true);
				return out;
			}
			if ("remove".equals(op))
			{
				a.remove(b, s, c, id);
				ObjectNode out = Json.object();
				out.put("id", id);
				out.put("removed", true);
				return out;
			}
			throw new ConnectorException("Couchbase: unknown operation '"+op+"'");
		}
		catch (ConnectorException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new ConnectorException("Couchbase "+op+" failed: "+e.getClass().getSimpleName(), e);
		}
		finally
		{
			if (a != null) a.close();
		}
	}
	private static ArrayNode array(List<String> values)
	{
		ArrayNode a = Json.mapper().createArrayNode();
		for (String v:values) a.add(v);
		return a;
	}
	private static int bound(String v, int d)throws ConnectorException
	{
		try
		{
			int n = v == null ? d : Integer.parseInt(v);
			if (n < 1 || n > 1000) throw new ConnectorException("max must be between 1 and 1000");
			return n;
		}
		catch (NumberFormatException e)
		{
			throw new ConnectorException("max must be an integer");
		}
	}
	private static String req(Map<String, String>m, String n)throws ConnectorException
	{
		String v = m.get(n);
		if (v == null || v.isEmpty()) throw new ConnectorException("Couchbase: set "+n);
		return v;
	}
	private static String value(Map<String, String>m, String n, String d)
	{
		String v = m.get(n);
		return v == null ? d : v;
	}
	static final class ReflectionFactory implements AdapterFactory{public Adapter open(ConnectorConfig c)throws Exception{return new ReflectionAdapter(c);}}
	static final class ReflectionAdapter implements Adapter
	{
		final Object cluster;
		ReflectionAdapter(ConnectorConfig c)throws Exception
		{
			Class<?> k = Class.forName("com.couchbase.client.java.Cluster");
			cluster = k.getMethod("connect", String.class, String.class, String.class).invoke(null, c.getBaseUrl(), c.getUsername(), c.getPassword());
		}
		Object bucket(String b)throws Exception
		{
			return cluster.getClass().getMethod("bucket", String.class).invoke(cluster, b);
		}
		Object collection(String b, String s, String c)throws Exception
		{
			Object scope = bucket(b).getClass().getMethod("scope", String.class).invoke(bucket(b), s);
			return scope.getClass().getMethod("collection", String.class).invoke(scope, c);
		}
		public List<String>buckets(int max)throws Exception
		{
			Object manager = cluster.getClass().getMethod("buckets").invoke(cluster), map = manager.getClass().getMethod("getAllBuckets").invoke(manager);
			List<String>out = new ArrayList<String>();
			for (Object k:((Map<?, ?>)map).keySet())
			{
				if (out.size() >= max) break;
				out.add(String.valueOf(k));
			}
			Collections.sort(out);
			return out;
		}
		public List<String>collections(String b, int max)throws Exception
		{
			Object manager = bucket(b).getClass().getMethod("collections").invoke(bucket(b));
			Iterable<?> scopes = (Iterable<?>)manager.getClass().getMethod("getAllScopes").invoke(manager);
			List<String>out = new ArrayList<String>();
			for (Object s:scopes)
			{
				String sn = String.valueOf(s.getClass().getMethod("name").invoke(s));
				Iterable<?> cs = (Iterable<?>)s.getClass().getMethod("collections").invoke(s);
				for (Object c:cs)
				{
					if (out.size() >= max) return out;
					out.add(sn+"."+c.getClass().getMethod("name").invoke(c));
				}
			}
			return out;
		}
		public String get(String b, String s, String c, String id)throws Exception
		{
			Object r = collection(b, s, c).getClass().getMethod("get", String.class).invoke(collection(b, s, c), id);
			return String.valueOf(r.getClass().getMethod("contentAsObject").invoke(r));
		}
		public void upsert(String b, String s, String c, String id, String json)throws Exception
		{
			Class<?> o = Class.forName("com.couchbase.client.java.json.JsonObject");
			Object doc = o.getMethod("fromJson", String.class).invoke(null, json);
			collection(b, s, c).getClass().getMethod("upsert", String.class, Object.class).invoke(collection(b, s, c), id, doc);
		}
		public void remove(String b, String s, String c, String id)throws Exception
		{
			collection(b, s, c).getClass().getMethod("remove", String.class).invoke(collection(b, s, c), id);
		}
		public void close()
		{
			try
			{
				cluster.getClass().getMethod("disconnect").invoke(cluster);
			}
			catch (Exception ignored) { }
		}
	}
}
