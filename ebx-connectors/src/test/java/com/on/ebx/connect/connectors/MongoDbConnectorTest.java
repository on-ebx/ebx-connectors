package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/** Deterministic MongoDB contract checks with no server or network dependency. */
public final class MongoDbConnectorTest
{
	public static void main(final String[] args)throws Exception
	{
		final Fake fake=new Fake();final MongoDbConnector c=new MongoDbConnector(config->fake,true);final ConnectorConfig cfg=config("mongodb://127.0.0.1:27017/?directConnection=true");c.configure(cfg);
		check(c.validateConfiguration(cfg).isValid(),"loopback fixture should be valid");
		check(c.execute(null,"databases",map("max","1"),null).size()==1&&fake.discoveryMax==1,"bounded database discovery");
		check(c.execute(null,"collections",map("database","governance","max","2"),null).size()==2,"bounded collection discovery");
		final Map<String,String> find=map("database","governance","collection","company","max","2");final JsonNode page=c.execute(null,"find",find,Json.parse("{\"active\":true}"));check(page.path("count").asInt()==2&&page.has("continuationToken"),"first page/token");check(fake.max==2&&fake.maxTime==30000&&fake.after==null,"find bounds propagated");
		find.put("continuationToken",page.path("continuationToken").asText());c.execute(null,"find",find,Json.object());check(fake.after.asInt()==2,"keyset id decoded");
		final Map<String,String> wrong=map("database","governance","collection","other","continuationToken",page.path("continuationToken").asText());rejected(c,"find",wrong,Json.object(),"invalid for this collection");
		final Map<String,String> one=map("database","governance","collection","company","id","1");check(c.execute(null,"get",one,null).path("name").asText().equals("Ada"),"get");check(c.execute(null,"insert",map("database","governance","collection","company"),Json.object().put("name","Grace")).path("_id").asInt()==9,"insert");
		check(c.execute(null,"upsert",one,Json.object().put("name","Lin")).path("affected").asLong()==1&&fake.upsert,"upsert");check(c.execute(null,"update",one,Json.parse("{\"$set\":{\"active\":false}}" )).path("affected").asLong()==1,"operator update");check(c.execute(null,"delete",one,null).path("deleted").asLong()==1,"delete");
		rejected(c,"find",map("database","governance","collection","company","max","501"),Json.object(),"between 1 and 500");rejected(c,"update",one,Json.parse("{\"name\":\"unsafe replacement\"}"),"operator documents only");
		final com.fasterxml.jackson.databind.node.ArrayNode many=Json.mapper().createArrayNode();for(int i=0;i<101;i++)many.add(Json.object().put("n",i));rejected(c,"insertMany",map("database","governance","collection","company"),many,"1 to 100");
		final ConnectorConfig credentials=config("mongodb://user:secret@example.com/?tls=true");check(has(c.validateConfiguration(credentials),"baseUrl"),"URI credentials accepted");final ConnectorConfig plain=config("mongodb://db.example.com:27017");check(has(c.validateConfiguration(plain),"baseUrl"),"remote clear transport accepted");final ConnectorConfig insecure=config("mongodb://db.example.com/?tls=true&tlsInsecure=true");check(has(c.validateConfiguration(insecure),"baseUrl"),"insecure TLS accepted");
		try{fake.fail=true;c.execute(null,"databases",map("max","1"),null);throw new AssertionError("expected failure");}catch(final ConnectorException ex){check(!ex.getMessage().contains("secret-password")&&!ex.getMessage().contains("fixture detail"),"secret/detail leaked");}
		check(c.getDescriptor().getApiVersions().get(0).contains("5.11")&&!operation(c,"watch"),"driver/change-stream contract");
		System.out.println("PASS: MongoDB Java 8 driver contract, TLS/auth validation, bounded discovery/CRUD, keyset tokens, limits, redaction, no change streams");
	}

	private static ConnectorConfig config(final String uri){final ConnectorConfig c=new ConnectorConfig();c.setBaseUrl(uri);c.setAuthType(ConnectorConfig.AuthType.BASIC);c.setUsername("fixture-user");c.setPassword("secret-password");return c;}
	private static boolean operation(final MongoDbConnector c,final String name){for(final com.on.ebx.connect.spi.Operation o:c.getOperations())if(name.equals(o.getName()))return true;return false;}
	private static boolean has(final com.on.ebx.connect.spi.ConfigurationValidationResult r,final String name){for(final ConfigurationValidationIssue i:r.getIssues())if(name.equals(i.getPropertyName()))return true;return false;}
	private static void rejected(final MongoDbConnector c,final String op,final Map<String,String> in,final JsonNode body,final String text)throws Exception{try{c.execute(null,op,in,body);throw new AssertionError("expected rejection");}catch(final ConnectorException ex){check(ex.getMessage().contains(text),"unexpected rejection: "+ex.getMessage());}}
	private static Map<String,String> map(final String... values){final Map<String,String> m=new LinkedHashMap<String,String>();for(int i=0;i<values.length;i+=2)m.put(values[i],values[i+1]);return m;}
	private static void check(final boolean ok,final String message){if(!ok)throw new AssertionError(message);}

	private static final class Fake implements MongoDbConnector.Transport
	{
		int discoveryMax,max,maxTime;JsonNode after;boolean upsert,fail;
		public List<String> databases(final int m)throws Exception{if(fail)throw new IllegalStateException("fixture detail secret-password");discoveryMax=m;return Collections.singletonList("governance");}
		public List<String> collections(final String db,final int m){return Arrays.asList("company","person").subList(0,Math.min(m,2));}
		public List<JsonNode> find(final String db,final String collection,final JsonNode filter,final JsonNode a,final int m,final int time){after=a;max=m;maxTime=time;final List<JsonNode> out=new ArrayList<JsonNode>();out.add(Json.object().put("_id",1).put("name","Ada"));out.add(Json.object().put("_id",2).put("name","Grace"));return out;}
		public JsonNode get(final String db,final String collection,final JsonNode id,final int time){final com.fasterxml.jackson.databind.node.ObjectNode out=Json.object();out.set("_id",id);out.put("name","Ada");return out;}
		public JsonNode insert(final String db,final String collection,final JsonNode document){return Json.object().put("_id",9).set("document",document);}
		public int insertMany(final String db,final String collection,final List<JsonNode> documents){return documents.size();}
		public long replace(final String db,final String collection,final JsonNode id,final JsonNode document,final boolean u){upsert=u;return 1;}
		public long update(final String db,final String collection,final JsonNode id,final JsonNode update){return 1;}
		public long delete(final String db,final String collection,final JsonNode id){return 1;}
		public void close(){}
	}
}
