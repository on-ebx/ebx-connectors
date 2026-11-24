package com.on.ebx.connect.connectors;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Loopback Azure Table REST contract: bounds, tokens, keys, writes, batches, redirects and redaction. */
public final class AzureTableStorageConnectorTest
{
	public static void main(final String[] pArgs)throws Exception
	{
		final AtomicReference<String> request=new AtomicReference<String>();final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
		server.createContext("/",new HttpHandler(){public void handle(final HttpExchange x)throws java.io.IOException{request.set(x.getRequestMethod()+" "+x.getRequestURI());final String path=x.getRequestURI().getPath();if(path.contains("Redirect")){x.getResponseHeaders().add("Location","/Tables");x.sendResponseHeaders(302,-1);x.close();return;}if(path.contains("Fail")){reply(x,500,"{\"error\":\"TOP-SECRET\"}");return;}if(path.endsWith("Tables")){x.getResponseHeaders().add("x-ms-continuation-NextTableName","NextOne");reply(x,200,"{\"value\":[{\"TableName\":\"Company\"}]}");return;}if("GET".equals(x.getRequestMethod())){reply(x,200,"{\"PartitionKey\":\"EU\",\"RowKey\":\"1\"}");return;}reply(x,204,"");}});server.start();
		try
		{
			final AzureTableStorageConnector c=new AzureTableStorageConnector(true);final ConnectorConfig cfg=new ConnectorConfig();cfg.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());cfg.setApiKey("sv=test&sig=TOP-SECRET");cfg.setAuthType(ConnectorConfig.AuthType.API_KEY_HEADER);c.configure(cfg);check(c.validateConfiguration(cfg).isValid(),"loopback fixture rejected");
			final JsonNode tables=c.execute(null,"tables",map("max","1"),null);check(tables.path("count").asInt()==1&&tables.has("continuationToken"),"bounded table discovery/token");
			final Map<String,String> get=map("table","Company");get.put("partitionKey","EU");get.put("rowKey","1");check("EU".equals(c.execute(null,"get",get,null).path("PartitionKey").asText()),"keyed get");check(request.get().contains("PartitionKey='EU'")||request.get().contains("PartitionKey=%27EU%27"),"PartitionKey/RowKey address");
			final JsonNode entity=Json.object().put("PartitionKey","EU").put("RowKey","1").put("Name","Ada");check(c.execute(null,"upsert",map("table","Company"),entity).path("written").asBoolean(),"upsert");
			final com.fasterxml.jackson.databind.node.ArrayNode same=Json.mapper().createArrayNode();same.add(entity);same.add(Json.object().put("PartitionKey","EU").put("RowKey","2"));final Map<String,String> okBatch=map("table","Company");okBatch.put("batchOperation","upsert");check(c.execute(null,"batch",okBatch,same).path("processed").asInt()==2,"same-partition transactional batch");
			final com.fasterxml.jackson.databind.node.ArrayNode mixed=Json.mapper().createArrayNode();mixed.add(entity);mixed.add(Json.object().put("PartitionKey","US").put("RowKey","2"));
			final Map<String,String> batch=map("table","Company");batch.put("batchOperation","upsert");rejected(c,"batch",batch,mixed,"one PartitionKey");
			final Map<String,String> redirect=map("table","Redirect");redirect.put("partitionKey","EU");redirect.put("rowKey","1");rejected(c,"get",redirect,null,"refused redirect");
			final Map<String,String> fail=map("table","Fail");fail.put("partitionKey","EU");fail.put("rowKey","1");try{c.execute(null,"get",fail,null);throw new AssertionError("failure accepted");}catch(final ConnectorException ex){check(!ex.getMessage().contains("TOP-SECRET"),"response secret leaked");}
			final ConnectorConfig bad=new ConnectorConfig();bad.setBaseUrl("https://evil.example");bad.setApiKey("x");check(has(c.validateConfiguration(bad),"baseUrl"),"foreign endpoint accepted");
			System.out.println("PASS: Azure Table bounded discovery, continuation, keys, writes, batch partition, redirect and redaction");
		}
		finally{server.stop(0);}
	}
	private static void reply(final HttpExchange x,final int s,final String b)throws java.io.IOException{final byte[] bytes=b.getBytes(StandardCharsets.UTF_8);if(bytes.length==0)x.sendResponseHeaders(s,-1);else{x.sendResponseHeaders(s,bytes.length);x.getResponseBody().write(bytes);}x.close();}
	private static Map<String,String> map(final String k,final String v){final Map<String,String> m=new LinkedHashMap<String,String>();m.put(k,v);return m;}
	private static void rejected(final AzureTableStorageConnector c,final String o,final Map<String,String> i,final JsonNode p,final String text)throws Exception{try{c.execute(null,o,i,p);throw new AssertionError("expected rejection");}catch(final ConnectorException ex){check(ex.getMessage().contains(text),"unexpected rejection: "+ex.getMessage());}}
	private static boolean has(final com.on.ebx.connect.spi.ConfigurationValidationResult r,final String f){for(final ConfigurationValidationIssue i:r.getIssues())if(f.equals(i.getPropertyName()))return true;return false;}
	private static void check(final boolean v,final String m){if(!v)throw new AssertionError(m);}
}
