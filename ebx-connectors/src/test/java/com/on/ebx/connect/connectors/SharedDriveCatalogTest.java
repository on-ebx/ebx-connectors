package com.on.ebx.connect.connectors;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;
public final class SharedDriveCatalogTest {
 static JsonNode json(String text)throws ConnectorException{try{return Json.mapper().readTree(text);}catch(Exception ex){throw new ConnectorException("fixture",ex);}}
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 public static void main(String[] args)throws Exception{
  com.on.ebx.connect.spi.ConnectorDescriptor oneDrive=new OneDriveConnector().getDescriptor();
  check(!oneDrive.getCapabilities().contains(com.on.ebx.connect.spi.ConnectorCapability.BULK_WRITE),"OneDrive must not advertise content writes");
  check(oneDrive.getCapabilities().contains(com.on.ebx.connect.spi.ConnectorCapability.BULK_READ),"OneDrive metadata read capability");
  check(oneDrive.getDirections().equals(Collections.singleton(com.on.ebx.connect.spi.ConnectorDirection.INBOUND)),"OneDrive inbound-only descriptor");
  check(oneDrive.getOperations().size()==1&&"catalog".equals(oneDrive.getOperations().get(0).getName()),"OneDrive catalog-only descriptor");
  check(oneDrive.getConfigurationProperties().stream().anyMatch(p->"driveId".equals(p.getName())&&p.isRequired()),"OneDrive drive scope required");
  check(oneDrive.getConfigurationProperties().stream().noneMatch(p->"max.file.bytes".equals(p.getName())),"OneDrive hides content settings");
  try{new OneDriveConnector().execute(null,"uploadFile",Collections.<String,String>emptyMap(),null);throw new AssertionError("inherited write executed");}catch(ConnectorException expected){check(expected.getMessage().contains("metadata catalog only"),"OneDrive execution allowlist");}
  final List<String> calls=new ArrayList<String>();
  SharedDriveCatalog.Transport google=(method,path)->{
   calls.add(method+" "+path);check("GET".equals(method),"Google metadata read-only");
   if(path.startsWith("/files/root?"))return json("{\"id\":\"root\",\"name\":\"My Drive\",\"mimeType\":\"application/vnd.google-apps.folder\"}");
   if(path.startsWith("/labels-api/"))return json("{\"labels\":[{\"id\":\"S\",\"properties\":{\"title\":\"File Sensitivity\"},\"fields\":[{\"id\":\"sf\",\"properties\":{\"displayName\":\"Level\"},\"selectionOptions\":{\"choices\":[{\"id\":\"default\",\"properties\":{\"displayName\":\"Default\"}}]}}]},{\"id\":\"R\",\"properties\":{\"title\":\"File Retention\"},\"fields\":[{\"id\":\"rf\",\"properties\":{\"displayName\":\"File Retention Period\"},\"selectionOptions\":{\"choices\":[{\"id\":\"18\",\"properties\":{\"displayName\":\"18 Months\"}}]}}]}]}");
   if(path.startsWith("/files?"))return json("{\"files\":[{\"id\":\"file1\",\"name\":\"Document\",\"parents\":[\"root\"]}]}");
   if(path.contains("/listLabels"))return json("{\"labels\":[{\"id\":\"S\",\"fields\":{\"sf\":{\"selection\":[\"default\"]}}},{\"id\":\"R\",\"fields\":{\"rf\":{\"selection\":[\"18\"]}}}]}");
   throw new AssertionError("Unexpected path "+path);
  };
  Map<String,String> inputs=new HashMap<String,String>();inputs.put("source","drive");inputs.put("folderId","root");
  JsonNode out=new SharedDriveCatalog(google,"https://www.googleapis.com/drive/v3","google-drive","",inputs).run("catalog");
  JsonNode file=out.path("tables").get(0);
  check("Default".equals(file.path("sourceSensitivity").asText()),"literal sensitivity");
  check("18 Months".equals(file.path("sourceRetention").asText()),"selection display name");
  check(file.path("sourceRetentionDetails").asText().contains("rf"),"stable field IDs");
  check(!file.has("retentionExpiresAt"),"no invented expiry");
  check(calls.stream().noneMatch(x->x.contains("alt=media")||x.contains("/content")),"no content download");
  Map<String,String> missing=new HashMap<String,String>();missing.put("source","drive");
  try{new SharedDriveCatalog(google,"https://www.googleapis.com/drive/v3","google-drive","",missing).run("catalog");throw new AssertionError("unscoped scan");}catch(ConnectorException expected){}
  SharedDriveCatalog.Transport graph=(method,path)->{
   check(!"POST".equals(method),"no sensitivity extraction by default");
   if(path.startsWith("/drives/D?"))return json("{\"id\":\"D\",\"name\":\"Documents\"}");
   if(path.startsWith("/drives/D/root?"))return json("{\"id\":\"ROOT\"}");
   if(path.contains("/root/children"))return json("{\"value\":[{\"id\":\"F\",\"name\":\"File\",\"file\":{}}]}");
   if(path.endsWith("/retentionLabel"))throw new ConnectorException("Denied",403,null);
   throw new AssertionError(path);
  };
  inputs.clear();inputs.put("source","sp");inputs.put("driveId","D");
  out=new SharedDriveCatalog(graph,"https://graph.microsoft.com/v1.0","sharepoint","T",inputs).run("catalog");
  check("ACCESS_DENIED".equals(out.path("tables").get(0).path("sourceRetentionStatus").asText()),"denied is not absent");
  check("NOT_REQUESTED".equals(out.path("tables").get(0).path("sourceSensitivityStatus").asText()),"safe default");
  final SharedDriveCatalog.Transport cross=(method,path)->{
   if(path.contains("/children"))return json("{\"value\":[],\"@odata.nextLink\":\"https://evil.example/token\"}");
   return json("{\"id\":\"D\"}");
  };
  try{new SharedDriveCatalog(cross,"https://graph.microsoft.com/v1.0","sharepoint","",inputs).run("catalog");throw new AssertionError("cross-origin accepted");}catch(ConnectorException expected){check(expected.getMessage().contains("Cross-origin"),"pagination rejected");}
  final SharedDriveCatalog.Transport traversal=(method,path)->{
   if(path.contains("/children"))return json("{\"value\":[],\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/%2e%2e/beta/me\"}");
   return json("{\"id\":\"D\"}");
  };
  try{new SharedDriveCatalog(traversal,"https://graph.microsoft.com/v1.0","sharepoint","",inputs).run("catalog");throw new AssertionError("encoded traversal accepted");}catch(ConnectorException expected){check(expected.getMessage().contains("Cross-origin"),"encoded traversal rejected");}
  Map<String,String> budget=new HashMap<String,String>(inputs);budget.put("maxRequests","2");
  try{new SharedDriveCatalog(graph,"https://graph.microsoft.com/v1.0","sharepoint","T",budget).run("catalog");throw new AssertionError("request budget ignored");}catch(ConnectorException expected){check(expected.getMessage().contains("budget"),"request budget enforced");}
  check(SharedDriveCatalog.termCode("a","b","c","d").equals(SharedDriveCatalog.termCode("a","b","c","d")),"stable term ID");
  System.out.println("PASS: shared-drive labels, stable IDs, explicit scope, no content, permission states, safe extraction and pagination");
 }
}
