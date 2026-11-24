package com.on.ebx.connect.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.connectors.GenericRestConnector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.test.MockSaasHttpServer;

/** Account-free acceptance checks for the HTTP behavior shared by SaaS connectors. */
public final class SaasHttpAcceptanceTest
{
	public static void main(final String[] args)throws Exception
	{
		final MockSaasHttpServer server=new MockSaasHttpServer();final String previous=System.getProperty(Egress.PROPERTY);
		try
		{
			System.setProperty(Egress.PROPERTY,"127.0.0.1:"+server.baseUrl().substring(server.baseUrl().lastIndexOf(':')+1));
			final Map<String,String> retry=new LinkedHashMap<String,String>();retry.put("Retry-After","0");
			server.enqueue("/customers",new MockSaasHttpServer.Response(429,"{\"error\":\"throttled\"}",retry))
				.enqueue("/customers",new MockSaasHttpServer.Response(200,"{\"data\":[{\"id\":1}],\"next\":\"/customers/page2\"}"))
				.enqueue("/customers/page2",new MockSaasHttpServer.Response(200,"{\"data\":[{\"id\":2}]}"))
				.enqueue("/customers/7",new MockSaasHttpServer.Response(200,"{\"id\":7,\"updated\":true}"))
				.enqueue("/failure",new MockSaasHttpServer.Response(400,"{\"error\":\"token=vendor-secret\"}"));
			final GenericRestConnector connector=new GenericRestConnector();connector.configure(configuration(server.baseUrl()));
			final JsonNode list=connector.execute(null,"list",Collections.<String,String>emptyMap(),null);
			check(list.path("results").size()==2&&list.path("pages").asInt()==2,"bounded pagination");
			check(server.calls("/customers")==2,"idempotent throttled GET was not retried exactly once");
			final MockSaasHttpServer.Request first=server.requests().get(0);check("Bearer vendor-secret".equals(first.header("Authorization")),"bearer auth");check("tenant-a".equals(first.header("X-Tenant")),"representative SaaS tenant header");
			final Map<String,String> input=new LinkedHashMap<String,String>();input.put("id","7");connector.execute(null,"update",input,Json.parse("{\"name\":\"Acme\"}"));
			final MockSaasHttpServer.Request write=server.requests().get(server.requests().size()-1);check("PUT".equals(write.method)&&write.body.contains("Acme"),"write request assertion");
			try{connector.execute(null,"failure",Collections.<String,String>emptyMap(),null);throw new AssertionError("HTTP error accepted");}
			catch(final ConnectorException expected){final String safe=SensitiveDataMasker.fromConfiguration(secretMap()).mask(expected.getMessage());check(safe.contains("HTTP 400")&&!safe.contains("vendor-secret"),"normalized error/redaction");}
			System.out.println("PASS: reusable SaaS mock auth, pagination, retry, normalized error, redaction and read/write assertions");
		}
		finally{server.close();if(previous==null)System.clearProperty(Egress.PROPERTY);else System.setProperty(Egress.PROPERTY,previous);}
	}
	private static ConnectorConfig configuration(final String base){final ConnectorConfig c=new ConnectorConfig();c.setId("saas-acceptance");c.setBaseUrl(base);c.setAuthType(ConnectorConfig.AuthType.BEARER);c.setApiKey("vendor-secret");c.setProperty("op.list.method","GET");c.setProperty("op.list.path","/customers");c.setProperty("op.list.responseRoot","data");c.setProperty("op.list.pagination.next","next");c.setProperty("op.list.pagination.maxPages","3");c.setProperty("op.list.pagination.maxRecords","5");c.setProperty("op.list.header.X-Tenant","tenant-a");c.setProperty("op.update.method","PUT");c.setProperty("op.update.path","/customers/{id}");c.setProperty("op.failure.method","GET");c.setProperty("op.failure.path","/failure");return c;}
	private static Map<String,String> secretMap(){final Map<String,String> m=new LinkedHashMap<String,String>();m.put("apiKey","vendor-secret");return m;}
	private static void check(final boolean c,final String m){if(!c)throw new AssertionError(m);}
}
