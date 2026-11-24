package com.on.ebx.connect.connectors;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Deterministic loopback contracts for the evidence-backed Wave 5 connectors. */
public final class BusinessIntegrationConnectorTest
{
	private BusinessIntegrationConnectorTest(){}
	public static void main(String[] args)throws Exception{
		final String old=System.getProperty(Egress.PROPERTY);final HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final Fixture f=new Fixture();s.createContext("/",f);s.start();
		try{int port=s.getAddress().getPort();System.setProperty(Egress.PROPERTY,"127.0.0.1:"+port);String base="http://127.0.0.1:"+port;
			VeevaVaultConnector v=new VeevaVaultConnector();v.configure(cfg("veeva-vault",base,ConnectorConfig.AuthType.BEARER));Map<String,String> q=new LinkedHashMap<String,String>();q.put("q","SELECT id FROM account__v");q.put("pageSize","7");JsonNode vr=v.execute(null,"query",q,null);check(vr.get("responseStatus").asText().equals("SUCCESS")&&f.path.contains("pagesize=7"),"Veeva bounded VQL");check("Bearer secret".equals(f.auth),"Veeva bearer header");
			AlationConnector a=new AlationConnector();ConnectorConfig ac=cfg("alation",base,ConnectorConfig.AuthType.API_KEY_HEADER);ac.setApiKeyHeader("ignored");a.configure(ac);Map<String,String> ai=new LinkedHashMap<String,String>();ai.put("kind","table");ai.put("limit","8");a.execute(null,"catalog",ai,null);check(f.path.startsWith("/catalog/table/")&&f.path.contains("limit=8")&&"secret".equals(f.token),"Alation TOKEN and bounds");
			MicrosoftPurviewConnector p=new MicrosoftPurviewConnector();p.configure(cfg("microsoft-purview",base,ConnectorConfig.AuthType.BEARER));Map<String,String> pi=new LinkedHashMap<String,String>();pi.put("keywords","customer");pi.put("limit","9");JsonNode pr=p.execute(null,"search",pi,null);check(pr.get("continuationToken").asText().equals("next")&&f.body.contains("\"limit\":9")&&f.path.contains("api-version=2023-09-01"),"Purview continuation and limit");
			try{pi.put("limit","1001");p.execute(null,"search",pi,null);throw new AssertionError("Purview limit accepted");}catch(com.on.ebx.connect.spi.ConnectorException expected){check(expected.getMessage().contains("between 1 and 1000"),"Purview bound message");}
		}finally{s.stop(0);if(old==null)System.clearProperty(Egress.PROPERTY);else System.setProperty(Egress.PROPERTY,old);}System.out.println("PASS: Wave 5 business integrations (Veeva, Alation, Purview) bounded loopback contracts");
	}
	private static ConnectorConfig cfg(String type,String base,ConnectorConfig.AuthType auth){ConnectorConfig c=new ConnectorConfig();c.setId(type);c.setType(type);c.setBaseUrl(base);c.setAuthType(auth);c.setApiKey("secret");c.setConnectTimeoutMs(2000);c.setReadTimeoutMs(2000);return c;}
	private static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
	private static final class Fixture implements com.sun.net.httpserver.HttpHandler{volatile String path,auth,token,body;public void handle(HttpExchange x)throws java.io.IOException{path=x.getRequestURI().toString();auth=x.getRequestHeaders().getFirst("Authorization");token=x.getRequestHeaders().getFirst("TOKEN");byte[] in=read(x);body=new String(in,StandardCharsets.UTF_8);String out=path.contains("/query")&&path.contains("api-version")?"{\"value\":[],\"continuationToken\":\"next\"}":path.startsWith("/api/")?"{\"responseStatus\":\"SUCCESS\",\"data\":[]}":"[]";byte[] b=out.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,b.length);OutputStream os=x.getResponseBody();os.write(b);os.close();}private byte[] read(HttpExchange x)throws java.io.IOException{java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();byte[] z=new byte[1024];int n;while((n=x.getRequestBody().read(z))>=0)b.write(z,0,n);return b.toByteArray();}}
}
