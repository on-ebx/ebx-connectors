package com.on.ebx.connect.connectors;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import com.sun.net.httpserver.HttpServer;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.spi.*;

public final class EbxPreviewTest {
 public static void main(String[] args)throws Exception {
  String[] identity=EbxConnector.previewIdentity("Geographies/Geographies/root/Country");
  check("/root/Country".equals(identity[2]),"table path");
  for(String bad:new String[]{"Country","A/B/root/../Country","A/B/root/Country?x=1","A/B/root/Country#x","A/B/root//Country","A/B/root/"}) {
   try{EbxConnector.previewIdentity(bad);throw new AssertionError("accepted invalid identity");}catch(ConnectorException expected){}
  }
  String before=System.getProperty(Egress.PROPERTY);
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  final int[] calls={0};
  server.createContext("/",exchange->{
   calls[0]++;
   check("GET".equals(exchange.getRequestMethod()),"read-only GET");
   check("/BGeographies/Geographies/root/Country".equals(exchange.getRequestURI().getPath()),"configured table routing");
   check("pageSize=3".equals(exchange.getRequestURI().getQuery()),"bounded request");
   check("Bearer preview-test".equals(exchange.getRequestHeaders().getFirst("Authorization")),"saved connector authentication");
   byte[] body="{\"rows\":[{\"content\":{\"Name\":{\"content\":\"France\"}}},{\"content\":{\"Name\":{\"content\":\"Japan\"}}},{\"content\":{\"Name\":{\"content\":\"Canada\"}}}]}".getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
  });server.start();
  try {
   System.setProperty(Egress.PROPERTY,"127.0.0.1:"+server.getAddress().getPort());
   ConnectorConfig config=new ConnectorConfig();config.setId("preview");config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());config.setAuthType(ConnectorConfig.AuthType.BEARER);config.setApiKey("preview-test");
   EbxConnector connector=new EbxConnector();connector.configure(config);
   PreviewResult result=connector.preview(null,new PreviewRequest("Geographies/Geographies/root/Country","",Collections.<String,String>emptyMap(),2));
   check(result.isSupported()&&result.isTruncated()&&result.getRecords().size()==2,"bounded result");
   check("France".equals(result.getRecords().get(0).path("Name").asText()),"EBX content unwrapped");
   check(!connector.preview(null,new PreviewRequest("Geographies/Geographies/root/Country","write",null,2)).isSupported(),"write not supported");
   check(calls[0]==1,"no write or extra request");
  }finally{server.stop(0);if(before==null)System.clearProperty(Egress.PROPERTY);else System.setProperty(Egress.PROPERTY,before);}
  System.out.println("PASS: EBX preview identity, saved authentication, read-only request, bounds and envelopes");
 }
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
