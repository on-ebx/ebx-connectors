package com.on.ebx.connect.core;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
public final class CatalogPreviewBoundaryTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  check(CatalogPreview.visibleClassification("PUBLIC"),"public visible");
  check(CatalogPreview.visibleClassification("INTERNAL"),"internal visible");
  for(String level:Arrays.asList(null,"","UNKNOWN","CONFIDENTIAL","RESTRICTED"))check(!CatalogPreview.visibleClassification(level),"protected or unknown masked");
  check(!CatalogPreview.protectedTag("INTERNAL","BUSINESS"),"reference business tag does not force masking");
  check(CatalogPreview.protectedTag("PUBLIC","PERSONAL_DATA"),"personal tag stays protected even with inconsistent public label");
  check(CatalogPreview.protectedTag("CONFIDENTIAL","BUSINESS"),"sensitive business tag protected");
  check(CatalogPreview.protectedTag(null,"BUSINESS"),"unknown tag sensitivity protected");
  check(CatalogPreview.protectedTag("INTERNAL",null),"unknown tag family protected");
  List<String> before=Arrays.asList("source1","connector","table1","qualified1");
  CatalogPreview.requireSameIdentity(before,new ArrayList<String>(before));
  for(int i=0;i<before.size();i++){List<String> after=new ArrayList<String>(before);after.set(i,"changed");try{CatalogPreview.requireSameIdentity(before,after);throw new AssertionError("identity change accepted");}catch(SecurityException expected){}}
  ObjectNode row=Json.object();row.put("authorized","visible");row.put("hidden-marker-in-key","secret-marker");row.putObject("nested").put("secret-marker","secret-marker");
  Set<String> known=new HashSet<String>(Arrays.asList("authorized","nested"));
  JsonNode masked=CatalogPreview.mask(Collections.<JsonNode>singletonList(row),known,known);
  check(!masked.toString().contains("marker"),"unauthorized names and nested values absent");
  check("visible".equals(masked.get(0).path("authorized").asText()),"authorized scalar remains");
  JsonNode protectedSample=CatalogPreview.mask(Collections.<JsonNode>singletonList(row),known,Collections.<String>emptySet());
  check(protectedSample.size()==1,"fully masked samples remain previewable");
  check("[MASKED]".equals(protectedSample.get(0).path("authorized").asText()),"unclassified asset discloses no scalar values");
  check(!protectedSample.toString().contains("secret-marker"),"fully masked sample contains no secret values or unauthorized names");
  ObjectNode wide=Json.object();Set<String> names=new HashSet<String>();String longValue=String.join("",Collections.nCopies(256,"界"));
  for(int i=0;i<200;i++){wide.put("field"+i,longValue);names.add("field"+i);}
  JsonNode bounded=CatalogPreview.mask(Collections.<JsonNode>nCopies(20,wide),names,names);
  check(bounded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536,"UTF8 byte budget");
  ObjectNode near=Json.object();Set<String> nearNames=new HashSet<String>();
  String escaped=String.join("",Collections.nCopies(256,"\u0001"));
  for(int i=0;i<42;i++){near.put("p"+i,escaped);nearNames.add("p"+i);}
  // Fill close to the limit with escaped characters; each stays within the scalar character cap.
  int gap=65515-near.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length-11;
  near.put("tail",String.join("",Collections.nCopies(gap/6,"\u0001")));nearNames.add("tail");
  List<JsonNode> mixed=new ArrayList<JsonNode>();mixed.add(near);
  for(int i=0;i<19;i++)mixed.add(i%2==0?null:Json.mapper().createArrayNode().add("synthetic-hidden"));
  JsonNode mixedSafe=CatalogPreview.mask(mixed,nearNames,nearNames);
  check(mixedSafe.get(0).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65500,"fixture reaches budget before nonobject rows");
  check(mixedSafe.size()<mixed.size(),"mixed rows stop at byte limit");
  check(mixedSafe.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536,"near-limit mixed object/nonobject budget");
  check(!mixedSafe.toString().contains("synthetic-hidden"),"mixed input stays masked");
  String hugeKey=String.join("",Collections.nCopies(5000,"x"));wide.removeAll();wide.put(hugeKey,"secret");names.add(hugeKey);
  check(CatalogPreview.mask(Collections.<JsonNode>singletonList(wide),names,names).get(0).size()==0,"oversized key omitted");
  wide.put("binary",new byte[100000]);names.add("binary");
  check("[MASKED]".equals(CatalogPreview.mask(Collections.<JsonNode>singletonList(wide),names,names).get(0).path("binary").asText()),"binary unsupported");
  System.out.println("CatalogPreviewBoundaryTest PASS: identity, hidden keys, nested content, binary and UTF8 output cap; live race/permissions pending");
 }
}
