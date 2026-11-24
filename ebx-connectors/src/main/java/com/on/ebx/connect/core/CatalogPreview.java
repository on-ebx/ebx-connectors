package com.on.ebx.connect.core;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.connectors.JdbcConnector;
import com.on.ebx.connect.spi.*;
import com.onwbp.adaptation.*;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.*;
import com.orchestranetworks.instance.Repository;
/** Masked on-demand catalog preview. No unmask option, persistence, sample logging or external model calls. */
public final class CatalogPreview {
 private CatalogPreview(){}
 public static ArrayNode mask(List<JsonNode> records,Set<String> allowed) {
  return mask(records,null,allowed);
 }
 static ArrayNode mask(List<JsonNode> records,Set<String> known,Set<String> allowed) {
  ArrayNode result=Json.mapper().createArrayNode();
  if(records==null)return result;
  Set<String> visible=allowed==null?Collections.<String>emptySet():new HashSet<String>(allowed);
  int bytes=2;
  for(JsonNode record:records){
   if(result.size()>=20)break;
   int rowOverhead=result.size()==0?2:3; // object braces and inter-row comma
   if(bytes+rowOverhead>65536)break;
ObjectNode row = result.addObject();
int count = 0;
   bytes+=rowOverhead;
   if(record==null||!record.isObject()){
    row.put("value","[MASKED]");
    int size=row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length-2;
    if (bytes+size > 65536)
    {
        result.remove(result.size()-1);
        return result;
    }
    bytes += size;
    continue;
   }
   Iterator<Map.Entry<String,JsonNode>> fields=record.fields();
   while(fields.hasNext()&&count<200){
    Map.Entry<String,JsonNode> f=fields.next();
    if("attributes".equals(f.getKey()))continue;
    if(f.getKey().length()>128 || (known!=null&&!known.contains(f.getKey())))continue;
    count++;
    JsonNode value=f.getValue();
    if(!visible.contains(f.getKey())||value==null||value.isContainerNode()||value.isBinary()||value.isPojo())row.put(f.getKey(),"[MASKED]");
    else if(value.isTextual())row.put(f.getKey(),value.asText().length()>256?value.asText().substring(0,256)+"…":value.asText());
    else if(value.isNumber()&&value.asText().length()>256)row.put(f.getKey(),"[MASKED]");
    else row.set(f.getKey(),value);
    int size=Json.mapper().createObjectNode().set(f.getKey(),row.get(f.getKey())).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length-2+(row.size()>1?1:0);
    if (bytes+size > 65536)
    {
        row.remove(f.getKey());
        return result;
    }
    bytes+=size;
   }
  }
  return result;
 }
 static boolean visibleClassification(String sensitivity) {
  return "PUBLIC".equals(sensitivity)||"INTERNAL".equals(sensitivity);
 }
 static boolean protectedTag(String sensitivity,String family) {
  return !visibleClassification(sensitivity)||family==null||"PERSONAL_DATA".equals(family);
 }
 static boolean blocked(Session session,Adaptation row) {
  String sensitivity=row.getString(Path.parse("./sensitivity"));
  if(!visibleClassification(sensitivity))return true;
  Object tags=row.get(Path.parse("./tags"));
  if(tags instanceof List)for(Object code:(List<?>)tags){
   if(!(code instanceof String))return true;
   AdaptationTable tagTable=row.getContainer().getTable(Path.parse("/root/Tag"));
   if(tagTable==null)return true;
   Adaptation tag=tagTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString((String)code));
   try{authorize(session,row.getContainer(),tag,"sensitivity","family");}
   catch(SecurityException restricted){return true;}
   if(protectedTag(tag.getString(Path.parse("./sensitivity")),tag.getString(Path.parse("./family"))))return true;
  }
  // Only known source labels are eligible; unknown labels remain protected.
  if(row.getSchemaNode().getNode(Path.parse("./sourceSensitivity"))!=null){
   String source=row.getString(Path.parse("./sourceSensitivity"));
   if(source!=null&&!source.trim().isEmpty()&&!visibleClassification(source.toUpperCase(Locale.ENGLISH)))return true;
  }
  if(row.getSchemaNode().getNode(Path.parse("./sourceSensitivityStatus"))!=null){
   String status=row.getString(Path.parse("./sourceSensitivityStatus"));
   if(status!=null&&!"AVAILABLE".equals(status)&&!"NOT_ASSIGNED".equals(status))return true;
  }
  return false;
 }
 static void authorize(Session session,Adaptation data,Adaptation row,String...fields){
  if(row==null)throw new SecurityException("Preview metadata unavailable.");
  SessionPermissions p=session.getPermissions();
  try{
   if(p==null||p.getHomeAccessPermission(data.getHome()).isHidden()||p.getAdaptationAccessPermission(data).isHidden()
    ||p.getNodeAccessPermission(row.getContainerTable().getTableNode(),data).isHidden()
    ||p.getNodeAccessPermission(row.getSchemaNode(),row).isHidden())throw new SecurityException();
   for(String field:fields){com.orchestranetworks.schema.SchemaNode node=row.getSchemaNode().getNode(Path.parse("./"+field));
    if(node==null||p.getNodeAccessPermission(node,row).isHidden())throw new SecurityException();}
   for(String field:Arrays.asList("sourceSensitivity","sourceSensitivityStatus")){
    com.orchestranetworks.schema.SchemaNode node=row.getSchemaNode().getNode(Path.parse("./"+field));
    if(node!=null&&p.getNodeAccessPermission(node,row).isHidden())throw new SecurityException();}
  }catch(RuntimeException denied){throw new SecurityException("Preview metadata unavailable or restricted.");}
 }
 static List<Adaptation> columns(Session session,Adaptation data,String id){
  List<Adaptation> out=new ArrayList<Adaptation>();
  AdaptationTable table=data.getTable(Path.parse("/root/Column"));
  if(table==null||session.getPermissions().getNodeAccessPermission(table.getTableNode(),data).isHidden())throw new SecurityException("Preview metadata unavailable or restricted.");
Request q = table.createRequest();
q.setXPathFilter("./asset="+Xpath.quote(id));
q.setSession(session);
q.setFetchSize(201);
  RequestResult r=q.execute();
try
{
    for (Adaptation c = r.nextAdaptation(); c != null; c = r.nextAdaptation())
    {
        if (out.size() >= 200) throw new IllegalArgumentException("Preview supports at most 200 catalog columns.");
        authorize(session, data, c, "name", "sensitivity", "tags", "asset");
        out.add(c);
    }
}
finally
{
    r.close();
}
return out;
 }
 public static JsonNode preview(Session session,Repository repository,String id)throws Exception{
  if(session==null||!session.isUserInRole(Profile.ADMINISTRATOR))throw new SecurityException("Catalog preview currently requires an EBX administrator.");
  // the catalog keys an asset by its natural key (source:qualifiedName) since its audit D-07; it used to be a number
  if(id==null||id.trim().isEmpty()||id.length()>2000)throw new IllegalArgumentException("An asset key is required.");
  Adaptation data=CatalogDetection.local(repository).adaptation;
  if(data==null)throw new IllegalArgumentException("Verified local catalog unavailable.");
  AdaptationTable assets=data.getTable(Path.parse("/root/Asset"));
  Adaptation asset=assets.lookupAdaptationByPrimaryKey(assets.computePrimaryKey(new Object[]{id}));
  if(asset==null)throw new IllegalArgumentException("Asset not found.");
  authorize(session,data,asset,"source","physicalName","qualifiedName","sensitivity","tags");
  // Classification controls disclosure, not whether an administrator may request a masked sample.
  String sourceId=asset.getString(Path.parse("./source"));
  Adaptation source=data.getTable(Path.parse("/root/Source")).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(asset.getString(Path.parse("./source"))));
  authorize(session,data,source,"active","connector");
  if(!Boolean.TRUE.equals(source.get(Path.parse("./active"))))throw new IllegalArgumentException("Active source required.");
  String connectorId=source.getString(Path.parse("./connector"));
  List<String> identity=Arrays.asList(sourceId,connectorId,asset.getString(Path.parse("./physicalName")),asset.getString(Path.parse("./qualifiedName")));
  List<Adaptation> initialColumns=columns(session,data,id);
  Set<String> known=new HashSet<String>(),initialAllowed=new HashSet<String>();
for (Adaptation c:initialColumns)
{
    String name = c.getString(Path.parse("./name"));
    if (name != null)
    {
        known.add(name);
        if (!blocked(session, asset) && !blocked(session, c)) initialAllowed.add(name);
    }
}
  if(known.isEmpty())throw new IllegalArgumentException("Scanned, readable column metadata is required.");
  if(connectorId==null||connectorId.trim().isEmpty())throw new IllegalArgumentException("A configured source connector is required.");
  ConnectorRepository connectorRepository=new ConnectorRepository(repository);
  PreviewConfiguration configuration=new PreviewConfiguration(connectorRepository.connector(connectorId));
  Connector connector=ConnectorRegistry.get().connector(connectorId,connectorRepository);
  configuration.requireUnchanged(connectorRepository.connector(connectorId));
List<JsonNode> records = new ArrayList<JsonNode>();
boolean truncated = false;
  if(connector instanceof JdbcConnector || connector instanceof com.on.ebx.connect.connectors.EbxConnector){
   com.on.ebx.connect.spi.PreviewResult preview=connector.preview(new ConnectorContext(session,repository,null,data),new com.on.ebx.connect.spi.PreviewRequest(asset.getString(Path.parse("./qualifiedName")),"",Collections.<String,String>emptyMap(),20));
   if(!preview.isSupported())throw new IllegalArgumentException("This connector does not support catalog previews.");
for (JsonNode record:preview.getRecords())
{
    if (records.size() >= 20) break;
    records.add(record);
}
   truncated=preview.isTruncated()||preview.getRecords().size()>20;
  }else if("salesforce".equals(connector.getType())){
   String object=asset.getString(Path.parse("./physicalName"));
   if(object==null||!object.matches("[A-Za-z][A-Za-z0-9_]*"))throw new IllegalArgumentException("Invalid Salesforce object identity.");
   List<String> names=new ArrayList<String>();
for (Adaptation c:initialColumns)
{
    String name = c.getString(Path.parse("./name"));
    if (name != null && name.matches("[A-Za-z][A-Za-z0-9_]*")) names.add(name);
}
   if(names.isEmpty())throw new IllegalArgumentException("Scan the asset's columns before previewing.");
Map<String, String> inputs = new HashMap<String, String>();
inputs.put("soql", "SELECT "+String.join(",", names)+" FROM "+object+" LIMIT 21");
   JsonNode document=connector.execute(new ConnectorContext(session,repository,null,data),"query",inputs,null);
   if(document==null||!document.path("records").isArray())throw new IllegalArgumentException("No supported preview response.");
for (JsonNode row:document.path("records"))
{
    if (records.size() == 20)
    {
        truncated = true;
        break;
    }
    records.add(row);
}
  }else throw new IllegalArgumentException("Catalog record preview supports EBX, JDBC and Salesforce; file bodies and consuming streams are not previewed.");
  // Re-read authorization and masking decisions after the source call; stale state never authorizes disclosure.
  if(!session.isUserInRole(Profile.ADMINISTRATOR))throw new SecurityException("Catalog preview authorization changed.");
  asset=assets.lookupAdaptationByPrimaryKey(assets.computePrimaryKey(new Object[]{id}));
  if(asset==null)throw new SecurityException("Catalog preview asset is no longer authorized.");
  authorize(session,data,asset,"source","physicalName","qualifiedName","sensitivity","tags");
  Adaptation currentSource=data.getTable(Path.parse("/root/Source")).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(asset.getString(Path.parse("./source"))));
  authorize(session,data,currentSource,"active","connector");
  if(currentSource==null||!Boolean.TRUE.equals(currentSource.get(Path.parse("./active")))
   ||!connectorId.equals(currentSource.getString(Path.parse("./connector"))))throw new SecurityException("Catalog preview source authorization changed.");
  requireSameIdentity(identity,Arrays.asList(asset.getString(Path.parse("./source")),currentSource.getString(Path.parse("./connector")),asset.getString(Path.parse("./physicalName")),asset.getString(Path.parse("./qualifiedName"))));
  Set<String> allowed=new HashSet<String>(),currentKnown=new HashSet<String>();
  List<Adaptation> currentColumns=columns(session,data,id);
  for(Adaptation c:currentColumns)currentKnown.add(c.getString(Path.parse("./name")));
  known.retainAll(currentKnown);
  if(!blocked(session,asset))for(Adaptation c:currentColumns)if(!blocked(session,c)){
String name = c.getString(Path.parse("./name"));
if (name != null && !name.isEmpty()) allowed.add(name);
  }
  allowed.retainAll(initialAllowed);
  configuration.requireUnchanged(connectorRepository.connector(connectorId));
ObjectNode output = Json.object();
output.put("asset", id);
output.put("limit", 20);
output.put("truncated", truncated);
  output.put("masking","Authorized PUBLIC and INTERNAL assets and fields may display scalar values. Non-sensitive business tags do not force masking. Confidential, restricted, personal-data, unknown and nested values remain masked. Classification and tag permissions are checked before and after reading. Rows are bounded to 64 KiB, 20 rows and 200 fields; values may be truncated. Source data is unchanged.");
  output.put("connectorType",connector.getType());
output.set("rows", mask(records, known, allowed));
return output;
 }
 static void requireSameIdentity(List<String> before,List<String> after){if(before==null||!before.equals(after))throw new SecurityException("Catalog preview identity changed; retry.");}
}
