package com.on.ebx.connect.connectors;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
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
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** MongoDB synchronous-driver connector with bounded discovery, CRUD and _id keyset paging. */
public final class MongoDbConnector implements Connector
{
	private static final int MAX_DISCOVERY=500,MAX_PAGE=500,MAX_WRITE=100,MAX_DOCUMENT_BYTES=1024*1024;
	private static final List<Operation> OPERATIONS=Arrays.asList(
		Operation.search("databases","Discover a bounded list of databases","max"),
		Operation.search("collections","Discover a bounded list of collections","database","max"),
		Operation.search("find","Find a bounded page ordered by _id","database","collection","max","continuationToken"),
		Operation.read("get","Read one document by _id","database","collection","id"),
		Operation.write("insert","Insert one document","database","collection"),
		Operation.write("insertMany","Insert at most 100 documents","database","collection"),
		Operation.write("replace","Replace one document by _id","database","collection","id"),
		Operation.write("upsert","Replace or insert one document by _id","database","collection","id"),
		Operation.write("update","Apply a bounded operator update to one document by _id","database","collection","id"),
		Operation.write("delete","Delete one document by _id","database","collection","id"));

	interface Transport extends AutoCloseable
	{
		List<String> databases(int max)throws Exception;
		List<String> collections(String database,int max)throws Exception;
		List<JsonNode> find(String database,String collection,JsonNode filter,JsonNode afterId,int max,int maxTimeMs)throws Exception;
		JsonNode get(String database,String collection,JsonNode id,int maxTimeMs)throws Exception;
		JsonNode insert(String database,String collection,JsonNode document)throws Exception;
		int insertMany(String database,String collection,List<JsonNode> documents)throws Exception;
		long replace(String database,String collection,JsonNode id,JsonNode document,boolean upsert)throws Exception;
		long update(String database,String collection,JsonNode id,JsonNode update)throws Exception;
		long delete(String database,String collection,JsonNode id)throws Exception;
		void close();
	}

	interface TransportFactory{Transport open(ConnectorConfig config)throws Exception;}
	private final TransportFactory factory;
	private final boolean allowLoopback;
	private ConnectorConfig config;
	public MongoDbConnector(){this(new DriverFactory(),false);}
	MongoDbConnector(final TransportFactory pFactory, final boolean pAllowLoopback)
	{
		this.factory = pFactory;
		this.allowLoopback = pAllowLoopback;
	}
	@Override public String getType(){return "mongodb";}
	@Override public String getLabel(){return "MongoDB";}
	@Override public List<Operation> getOperations(){return OPERATIONS;}
	@Override public void configure(final ConnectorConfig pConfig){this.config=pConfig;}

	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final List<ConfigurationValidationIssue> issues=new ArrayList<ConfigurationValidationIssue>();
		if(pConfig==null)return new ConfigurationValidationResult(issues);
		final String uri=pConfig.getBaseUrl();
		try
		{
			final ConnectionString cs=new ConnectionString(uri);
			if(uri.indexOf('@')>=0)issues.add(new ConfigurationValidationIssue("baseUrl","Keep MongoDB credentials in the username and password fields, not in the URI"));
			final String lower=uri.toLowerCase(java.util.Locale.ROOT);
			if(lower.contains("tlsinsecure=true")||lower.contains("tlsallowinvalidhostnames=true")||lower.contains("sslallowinvalidhostnames=true"))issues.add(new ConfigurationValidationIssue("baseUrl","Insecure TLS and hostname verification overrides are refused"));
			final boolean srv=lower.startsWith("mongodb+srv://");
			final boolean tls=srv||Boolean.TRUE.equals(cs.getSslEnabled());
			if(!tls&&!loopback(cs))issues.add(new ConfigurationValidationIssue("baseUrl","MongoDB TLS is required; use mongodb+srv:// or set tls=true"));
		}
		catch(final RuntimeException ex){issues.add(new ConfigurationValidationIssue("baseUrl","Use a valid mongodb:// or mongodb+srv:// connection URI"));}
		if(pConfig.getAuthType()!=ConnectorConfig.AuthType.BASIC)issues.add(new ConfigurationValidationIssue("authType","MongoDB requires BASIC username/password authentication"));
		if(pConfig.isPlaceholder(pConfig.getUsername())||pConfig.isPlaceholder(pConfig.getPassword()))issues.add(new ConfigurationValidationIssue("password","Set the MongoDB username and password"));
		return new ConfigurationValidationResult(issues);
	}

	private boolean loopback(final ConnectionString cs)
	{
		if(!allowLoopback)return false;
		for (final String host:cs.getHosts())
		{
			final String h = host.replaceFirst(":\\d+$", "");
			if (!("localhost".equalsIgnoreCase(h) || "127.0.0.1".equals(h) || "[::1]".equals(h))) return false;
		}
		return !cs.getHosts().isEmpty();
	}

	@Override public JsonNode execute(final ConnectorContext pContext,final String pOperation,final Map<String,String> pInputs,final JsonNode pPayload)throws ConnectorException
	{
		if(config==null)throw new ConnectorException("MongoDB is not configured");
		final ConfigurationValidationResult validation = validateConfiguration(config);
		if (!validation.isValid()) throw new ConnectorException("MongoDB configuration is invalid");
		final Map<String,String> in=pInputs==null?Collections.<String,String>emptyMap():pInputs;
		try(final Transport t=factory.open(config))
		{
			if("databases".equals(pOperation))return strings(t.databases(bound(in.get("max"),100,1,MAX_DISCOVERY,"max")));
			if("collections".equals(pOperation))return strings(t.collections(name(in,"database"),bound(in.get("max"),100,1,MAX_DISCOVERY,"max")));
			if("find".equals(pOperation))return find(t,in,pPayload);
			final String db=name(in,"database"),collection=name(in,"collection");
			if ("get".equals(pOperation))
			{
				final JsonNode result = t.get(db, collection, id(in.get("id")), maxTime());
				return result == null ? Json.mapper().nullNode() : result;
			}
			if ("insert".equals(pOperation))
			{
				document(pPayload);
				return t.insert(db, collection, pPayload);
			}
			if ("insertMany".equals(pOperation))
			{
				final List<JsonNode> docs = documents(pPayload);
				return Json.object().put("inserted", t.insertMany(db, collection, docs));
			}
			if ("replace".equals(pOperation) || "upsert".equals(pOperation))
			{
				document(pPayload);
				return Json.object().put("affected", t.replace(db, collection, id(in.get("id")), pPayload, "upsert".equals(pOperation)));
			}
			if ("update".equals(pOperation))
			{
				update(pPayload);
				return Json.object().put("affected", t.update(db, collection, id(in.get("id")), pPayload));
			}
			if("delete".equals(pOperation))return Json.object().put("deleted",t.delete(db,collection,id(in.get("id"))));
			throw new ConnectorException("MongoDB: unknown operation '"+pOperation+"'");
		}
		catch(final ConnectorException ex){throw ex;}
		catch(final Exception ex){throw new ConnectorException("MongoDB operation failed: "+ex.getClass().getSimpleName(),ex);}
	}

	private JsonNode find(final Transport t,final Map<String,String> in,final JsonNode payload)throws Exception
	{
		final String db = name(in, "database"), collection = name(in, "collection");
		final int max = bound(in.get("max"), 100, 1, MAX_PAGE, "max");
		final JsonNode filter = payload == null ? Json.object() : payload;
		if (!filter.isObject()) throw new ConnectorException("MongoDB find filter must be a JSON object");
		sized(filter);
		JsonNode after = null;
		final String token = in.get("continuationToken");
		if (token != null && !token.trim().isEmpty()) after = token(token, db, collection);
		final List<JsonNode> found = t.find(db, collection, filter, after, max, maxTime());
		final ObjectNode out = Json.object();
		final ArrayNode rows = out.putArray("results");
		for (int i = 0; i < found.size() && i < max; i++) rows.add(found.get(i));
		out.put("count", rows.size());
		if (rows.size() == max && rows.get(rows.size()-1).has("_id")) out.put("continuationToken", token(db, collection, rows.get(rows.size()-1).get("_id")));
		return out;
	}

	private int maxTime(){return Math.max(100,Math.min(config.getReadTimeoutMs(),120000));}
	private static ArrayNode strings(final List<String> values)
	{
		final ArrayNode a = Json.mapper().createArrayNode();
		for (final String v:values) a.add(v);
		return a;
	}
	private static String name(final Map<String, String> in, final String key)throws ConnectorException
	{
		final String v = in.get(key);
		if (v == null || !v.matches("[A-Za-z0-9_.-]{1,120}") || v.startsWith("system.")) throw new ConnectorException("MongoDB "+key+" is invalid");
		return v;
	}
	private static JsonNode id(final String value)throws ConnectorException
	{
		if (value == null || value.length() > 4096) throw new ConnectorException("MongoDB id is required and must be bounded");
		try
		{
			final JsonNode wrapper = Json.parse("{\"_id\":"+value+"}");
			return wrapper.get("_id");
		}
		catch (final Exception ex)
		{
			return Json.mapper().getNodeFactory().textNode(value);
		}
	}
	private static void document(final JsonNode node)throws ConnectorException
	{
		if (node == null || !node.isObject()) throw new ConnectorException("MongoDB document object is required");
		sized(node);
	}
	private static List<JsonNode> documents(final JsonNode node)throws ConnectorException
	{
		if (node == null || !node.isArray() || node.size() < 1 || node.size() > MAX_WRITE) throw new ConnectorException("MongoDB insertMany requires 1 to 100 documents");
		final List<JsonNode> out = new ArrayList<JsonNode>();
		for (final JsonNode item:node)
		{
			document(item);
			out.add(item);
		}
		return out;
	}
	private static void update(final JsonNode node)throws ConnectorException
	{
		document(node);
		final java.util.Iterator<String> names = node.fieldNames();
		if (!names.hasNext()) throw new ConnectorException("MongoDB update must not be empty");
		while (names.hasNext()) if (!names.next().startsWith("$")) throw new ConnectorException("MongoDB update accepts operator documents only");
	}
	private static void sized(final JsonNode node)throws ConnectorException{if(node.toString().getBytes(StandardCharsets.UTF_8).length>MAX_DOCUMENT_BYTES)throw new ConnectorException("MongoDB document or filter exceeds 1 MiB");}
	private static int bound(final String value, final int def, final int min, final int max, final String name)throws ConnectorException
	{
		try
		{
			final int v = value == null ? def : Integer.parseInt(value);
			if (v < min || v > max) throw new ConnectorException("MongoDB "+name+" must be between "+min+" and "+max);
			return v;
		}
		catch (final NumberFormatException ex)
		{
			throw new ConnectorException("MongoDB "+name+" must be an integer");
		}
	}
	private static String token(final String db, final String collection, final JsonNode id)
	{
		final ObjectNode value = Json.object();
		value.put("database", db);
		value.put("collection", collection);
		value.set("id", id);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));
	}
	private static JsonNode token(final String encoded, final String db, final String collection)throws ConnectorException
	{
		if (encoded.length() > 8192) throw new ConnectorException("MongoDB continuation token is invalid");
		try
		{
			final JsonNode value = Json.parse(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
			if (!db.equals(value.path("database").asText()) || !collection.equals(value.path("collection").asText()) || !value.has("id")) throw new Exception();
			return value.get("id");
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("MongoDB continuation token is invalid for this collection");
		}
	}

	@Override public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b=ConnectorDescriptor.builder(getType(),getLabel(),"DATA_PLATFORMS").description("MongoDB synchronous connectivity with bounded metadata discovery, document CRUD, and _id keyset pagination; change streams are intentionally excluded.").authentication(AuthenticationMethod.BASIC).capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).apiVersion("MongoDB Java Sync Driver 5.11; server 4.2+; live acceptance required").pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.TOKEN,100,MAX_PAGE,"continuationToken")).defaults(ConnectorDefaults.standard()).configurationProperty(ConfigurationPropertyDefinition.builder("authDatabase",ConfigurationPropertyType.STRING).defaultValue("admin").build());
		for (final Operation o:OPERATIONS)
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	private static final class DriverFactory implements TransportFactory
	{
		public Transport open(final ConnectorConfig c)
		{
			final ConnectionString connection = new ConnectionString(c.getBaseUrl());
			final MongoCredential credential = MongoCredential.createScramSha256Credential(c.getUsername(), c.getProperty("authDatabase", "admin"), c.getPassword().toCharArray());
			final MongoClientSettings settings=MongoClientSettings.builder().applyConnectionString(connection).credential(credential).applyToSocketSettings(b->b.connectTimeout(c.getConnectTimeoutMs(),TimeUnit.MILLISECONDS).readTimeout(c.getReadTimeoutMs(),TimeUnit.MILLISECONDS)).build();
			return new DriverTransport(MongoClients.create(settings));
		}
	}

	private static final class DriverTransport implements Transport
	{
		private final MongoClient client;
		DriverTransport(final MongoClient c)
		{
			client = c;
		}
		public List<String> databases(final int max)
		{
			final List<String> out = new ArrayList<String>();
			for (final String n:client.listDatabaseNames().batchSize(Math.min(max, 100))) if (out.size() < max) out.add(n);
			return out;
		}
		public List<String> collections(final String db, final int max)
		{
			final List<String> out = new ArrayList<String>();
			for (final String n:client.getDatabase(db).listCollectionNames().batchSize(Math.min(max, 100))) if (out.size() < max) out.add(n);
			return out;
		}
		public List<JsonNode> find(final String db, final String collection, final JsonNode filter, final JsonNode afterId, final int max, final int maxTimeMs)throws Exception
		{
			Bson query = doc(filter);
			if (afterId != null) query = Filters.and(query, Filters.gt("_id", value(afterId)));
			final List<JsonNode> out = new ArrayList<JsonNode>();
			final FindIterable<Document> it = coll(db, collection).find(query).sort(new Document("_id", 1)).limit(max).batchSize(Math.min(max, 100)).maxTime(maxTimeMs, TimeUnit.MILLISECONDS);
			for (final Document d:it) out.add(json(d));
			return out;
		}
		public JsonNode get(final String db, final String collection, final JsonNode id, final int maxTimeMs)throws Exception
		{
			final Document d = coll(db, collection).find(Filters.eq("_id", value(id))).limit(1).maxTime(maxTimeMs, TimeUnit.MILLISECONDS).first();
			return d == null ? null : json(d);
		}
		public JsonNode insert(final String db, final String collection, final JsonNode document)throws Exception
		{
			final Document d = doc(document);
			coll(db, collection).insertOne(d);
			return json(d);
		}
		public int insertMany(final String db, final String collection, final List<JsonNode> documents)
		{
			final List<Document> docs = new ArrayList<Document>();
			for (final JsonNode n:documents) docs.add(doc(n));
			coll(db, collection).insertMany(docs);
			return docs.size();
		}
		public long replace(final String db, final String collection, final JsonNode id, final JsonNode document, final boolean upsert)
		{
			final com.mongodb.client.result.UpdateResult result = coll(db, collection).replaceOne(Filters.eq("_id", value(id)), doc(document), new ReplaceOptions().upsert(upsert));
			return result.getModifiedCount()+(result.getUpsertedId() == null ? 0 : 1);
		}
		public long update(final String db,final String collection,final JsonNode id,final JsonNode update){return coll(db,collection).updateOne(Filters.eq("_id",value(id)),doc(update),new UpdateOptions().upsert(false)).getModifiedCount();}
		public long delete(final String db,final String collection,final JsonNode id){return coll(db,collection).deleteOne(Filters.eq("_id",value(id))).getDeletedCount();}
		private MongoCollection<Document> coll(final String db, final String collection)
		{
			final MongoDatabase d = client.getDatabase(db);
			return d.getCollection(collection);
		}
		private static Document doc(final JsonNode n){return Document.parse(n.toString());}
		private static Object value(final JsonNode n){return Document.parse("{\"v\":"+n.toString()+"}").get("v");}
		private static JsonNode json(final Document d)throws Exception{return Json.parse(d.toJson());}
		public void close(){client.close();}
	}
}
