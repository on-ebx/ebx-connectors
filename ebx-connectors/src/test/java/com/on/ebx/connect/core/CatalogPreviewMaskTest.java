package com.on.ebx.connect.core;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
public final class CatalogPreviewMaskTest {
 public static void main(String[] args)throws Exception {
  JsonNode row=Json.mapper().readTree("{\"publicName\":\"<script>literal</script>\",\"email\":\"private@example.com\",\"unknown\":null,\"nested\":{\"secret\":\"do not expose\"},\"attributes\":{\"url\":\"internal\"}}");
  JsonNode safe=CatalogPreview.mask(Collections.singletonList(row),new HashSet<String>(Arrays.asList("publicName","nested")));
  check("[MASKED]".equals(safe.get(0).path("email").asText()),"sensitive");
  check("[MASKED]".equals(safe.get(0).path("unknown").asText()),"unclassified/null");
  check("[MASKED]".equals(safe.get(0).path("nested").asText()),"nested cannot bypass");
  check(!safe.toString().contains("private@example.com")&&!safe.toString().contains("do not expose"),"no raw sensitive output");
  check(!safe.get(0).has("attributes"),"provider envelope removed");
  check(row.path("email").asText().equals("private@example.com"),"source unchanged");
  check(CatalogPreview.mask(Collections.nCopies(100,row),Collections.<String>emptySet()).size()==20,"bounded rows");
  check(CatalogPreview.mask(Collections.singletonList(row),null).get(0).path("publicName").asText().equals("[MASKED]"),"null policy must fail closed");
  check(CatalogPreview.mask(Arrays.asList((JsonNode)null,Json.mapper().readTree("[\"secret\"]")),Collections.<String>emptySet()).toString().equals("[{\"value\":\"[MASKED]\"},{\"value\":\"[MASKED]\"}]"),"non-object records must fail closed");
  com.fasterxml.jackson.databind.node.ObjectNode wide=Json.object();Set<String> wideAllowed=new HashSet<String>();for(int i=0;i<250;i++){wide.put("f"+i,"v"+i);wideAllowed.add("f"+i);}
  JsonNode bounded=CatalogPreview.mask(Collections.<JsonNode>singletonList(wide),wideAllowed);
  check(bounded.get(0).size()==200,"bounded fields");
  System.out.println("PASS: server-side preview masking, fail-closed unknown/nested values, no source mutation and row cap");
 }
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
