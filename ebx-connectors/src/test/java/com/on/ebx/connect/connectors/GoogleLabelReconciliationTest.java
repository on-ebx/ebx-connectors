package com.on.ebx.connect.connectors;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorException;
public final class GoogleLabelReconciliationTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static JsonNode json(String s)throws ConnectorException {try{return Json.mapper().readTree(s);}catch(Exception e){throw new ConnectorException("fixture",e);}}
 static JsonNode scan(String assignments,boolean denied, boolean truncate)throws Exception {
  Map<String,String> p=new HashMap<>();p.put("source","fixture");p.put("folderId","root");if(truncate)p.put("max","2");
  SharedDriveCatalog.Transport t=(method,path)->{
   if(path.startsWith("/files/root?"))return json("{\"id\":\"root\",\"name\":\"root\"}");
   if(path.startsWith("/labels-api/"))return json("{\"labels\":[{\"id\":\"S\",\"properties\":{\"title\":\"File Sensitivity\"},\"fields\":[{\"id\":\"sf\"}]},{\"id\":\"R\",\"properties\":{\"title\":\"File Retention\"},\"fields\":[{\"id\":\"rf\",\"properties\":{\"displayName\":\"File Retention Period\"}}]}]}");
   if(path.startsWith("/files?"))return json("{\"files\":[{\"id\":\"F\",\"name\":\"Fixture\"}]}");
   if(denied)throw new ConnectorException("denied",403,null);
   return json(assignments);
  };
  return new SharedDriveCatalog(t,"https://www.googleapis.com/drive/v3","google-drive","",p).run("catalog").path("tables").get(0);
 }
 public static void main(String[] args)throws Exception {
  JsonNode assigned=scan("{\"labels\":[{\"id\":\"R\",\"fields\":{\"rf\":{\"text\":[\"18 Months\"]}}},{\"id\":\"S\",\"fields\":{\"sf\":{\"text\":[\"Default\"]}}}]}",false,false);
  JsonNode removed=scan("{\"labels\":[]}",false,false);
  check(removed.path("columns").size()==2,"removed labels retain both metadata columns");
  for(int i=0;i<2;i++) {
   JsonNode old=assigned.path("columns").get(i), fresh=removed.path("columns").get(i);
   check(old.path("columnKey").equals(fresh.path("columnKey")),"stable key");
   check(old.path("name").equals(fresh.path("name")),"existing flow name key unchanged");
   check(fresh.path("sourceDefinition").asText().contains("NOT_ASSIGNED"),"explicit column assignment status");
  }
  check(removed.get("sourceRetention").isNull()&&removed.get("sourceSensitivity").isNull(),"authoritative null clears source labels");
  check(removed.path("columns").get(0).get("sourceRetention").isNull(),"column retention clear");
  check("NOT_ASSIGNED".equals(removed.path("sourceSensitivityStatus").asText()),"absence status");
  JsonNode denied=scan("{}",true,false);
  check(!denied.has("sourceRetention")&&!denied.has("sourceSensitivity"),"denied does not clear prior labels");
  check(denied.path("columns").size()==0,"denied not mistaken for missing assignments");
  check("ACCESS_DENIED".equals(denied.path("sourceRetentionStatus").asText()),"denied status");
  JsonNode partial=scan("{\"labels\":[{\"id\":\"X\"},{\"id\":\"Y\"},{\"id\":\"Z\"}]}",false,true);
  check(!partial.has("sourceRetention")&&partial.path("columns").size()==0,"truncated inventory cannot infer removal");
  check(!removed.has("sensitivity")&&!removed.has("tags"),"never writes curated governance");
  System.out.println("GoogleLabelReconciliationTest PASS");
 }
}
