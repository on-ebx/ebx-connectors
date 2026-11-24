package com.on.ebx.connect.connectors;

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;

/** Provider fixtures only: no tenant calls or content download. */
public final class GraphMetadataStateTest {
 public static void main(String[] args) throws Exception {
  JsonNode noSite=run("sharepoint",false,"normal").path("tables").get(0);
  check("UNAVAILABLE".equals(noSite.path("sourceMetadataStatus").asText()),"missing SP site not available");
  check("NOT_REQUESTED".equals(run("onedrive",false,"normal").path("tables").get(0).path("sourceMetadataStatus").asText()),"OneDrive without site not requested");
  JsonNode partial=run("sharepoint",true,"denied").path("tables").get(0);
  check("PARTIAL".equals(partial.path("sourceMetadataStatus").asText()),"later denied collection is partial");
  check(partial.path("sourceMetadataStructure").asText().contains("SiteField"),"successful definitions retained");
  check("RETENTION-LITERAL".equals(partial.path("sourceRetention").asText()),"observed retention preserved");
  check("CT".equals(partial.path("originContentTypeId").asText()),"observed content type preserved");
  JsonNode truncated=run("sharepoint",true,"truncated").path("tables").get(0);
  check(!"AVAILABLE".equals(truncated.path("sourceMetadataStatus").asText()),"truncated definitions not complete");
  JsonNode malformed=run("sharepoint",true,"malformed").path("tables").get(0);
  check("ERROR".equals(malformed.path("sourceSensitivityStatus").asText()),"malformed labels not unassigned");
  JsonNode complete=run("sharepoint",true,"normal").path("tables").get(0);
  check("AVAILABLE".equals(complete.path("sourceMetadataStatus").asText()),"complete definitions available");
  check(complete.path("columns").get(0).path("sourceDefinitionId").asText().equals("F1"),"stable column definition identity");
  check(complete.path("columns").get(0).path("sourceTermAssignments").asText().contains("TERM"),"taxonomy assignment preserved");
  check(!complete.path("sourceMetadataStructure").asText().contains("PRIVATE_CONTENT"),"arbitrary field values not retained");
  System.out.println("PASS: Graph missing-site, partial/denied, truncated definitions, malformed sensitivity and taxonomy provenance");
 }
 static JsonNode run(String provider,boolean site,String mode) throws Exception {
  Map<String,String> in=new HashMap<String,String>();in.put("source","S");in.put("driveId","D");in.put("max","1");
  if(site)in.put("siteId","SITE");
  if("malformed".equals(mode))in.put("extractSensitivityLabels","true");
  SharedDriveCatalog.Transport transport=(method,path)->{
   if(path.startsWith("/drives/D?"))return json("{\"id\":\"D\",\"name\":\"Documents\"}");
   if(path.startsWith("/drives/D/root?"))return json("{\"id\":\"ROOT\"}");
   if(path.startsWith("/sites/SITE?"))return json("{\"id\":\"SITE\",\"displayName\":\"Fixture\"}");
   if(path.contains("/children"))return json("{\"value\":[{\"id\":\"FILE\",\"name\":\"Document\",\"file\":{},\"sharepointIds\":{\"listId\":\"L\"}}]}");
   if(path.endsWith("/retentionLabel"))return json("{\"name\":\"RETENTION-LITERAL\"}");
   if(path.endsWith("/extractSensitivityLabels"))return json("{\"labels\":[{\"tenantId\":\"T\"}]}");
   if(path.contains("/listItem?"))return json("{\"contentType\":{\"id\":\"CT\",\"name\":\"Document\"},\"fields\":{\"Taxonomy\":[{\"termGuid\":\"TERM\"}],\"Text\":\"PRIVATE_CONTENT\"}}");
   if(path.equals("/sites/SITE/columns"))return json("truncated".equals(mode)
     ?"{\"value\":[{\"id\":\"F1\"},{\"id\":\"F2\"}]}":"{\"value\":[{\"id\":\"F1\",\"name\":\"SiteField\"}]}");
   if(path.equals("/sites/SITE/contentTypes")&&"denied".equals(mode))throw new ConnectorException("secret vendor details",403,null);
   if(path.equals("/sites/SITE/lists/L/columns"))return json("{\"value\":[{\"id\":\"F1\",\"name\":\"Taxonomy\",\"term\":{}}]}");
   if(path.endsWith("/contentTypes"))return json("{\"value\":[]}");
   throw new AssertionError("Unexpected request "+path);
  };
  return new SharedDriveCatalog(transport,"https://graph.microsoft.com/v1.0",provider,"T",in).run("catalog");
 }
 static JsonNode json(String value) throws ConnectorException {try{return Json.parse(value);}catch(Exception e){throw new ConnectorException("Fixture JSON",e);}}
 static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
