package com.on.ebx.connect.connectors;
import java.sql.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.core.Json;
/** Bounded metadata SELECTs; scope identifiers are bound parameters, never interpolated SQL. */
final class SqlServerMetadata {
 private static final int MAX_PROPERTIES=500,MAX_PROPERTY_BYTES=80000;
 private SqlServerMetadata(){}
 static void read(Connection db,String schema,String table,ObjectNode target,boolean usage)throws SQLException{
target.put("sourceExtendedProperties", "[]");
target.put("sourceExtendedPropertiesStatus", "UNAVAILABLE");
for (JsonNode c:target.path("columns"))
{
    if (c.isObject())
    {
        ((ObjectNode)c).put("sourceExtendedProperties", "[]");
        ((ObjectNode)c).put("sourceExtendedPropertiesStatus", "UNAVAILABLE");
    }
}
  String sql="SELECT ep.class, ep.major_id, ep.minor_id, ep.name, CONVERT(nvarchar(max),ep.value) AS property_value, "
   +"CONVERT(nvarchar(128),SQL_VARIANT_PROPERTY(ep.value,'BaseType')) AS value_type, COL_NAME(ep.major_id,ep.minor_id) AS column_name "
   +"FROM sys.extended_properties ep WHERE ep.class=0 OR (ep.class=3 AND ep.major_id=SCHEMA_ID(?)) "
   +"OR (ep.class=1 AND ep.major_id=OBJECT_ID(QUOTENAME(?) + '.' + QUOTENAME(?))) ORDER BY ep.class,ep.minor_id,ep.name";
  try(PreparedStatement st=db.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
st.setString(1, schema);
st.setString(2, schema);
st.setString(3, table);
st.setQueryTimeout(30);
st.setMaxRows(MAX_PROPERTIES+1);
ArrayNode all = Json.mapper().createArrayNode();
int bytes = 2;
   try(ResultSet rows=st.executeQuery()){
    while(rows.next()){
     if(all.size()>=MAX_PROPERTIES)throw new SQLException("Extended property limit exceeded","54000");
    ObjectNode p = Json.object();
    p.put("class", rows.getInt(1));
    p.put("majorId", rows.getInt(2));
    p.put("minorId", rows.getInt(3));
    p.put("harvestedSchema", schema);
    p.put("harvestedObject", table);
    put(p, "name", rows.getString(4));
    put(p, "value", rows.getString(5));
    put(p, "type", rows.getString(6));
    put(p, "column", rows.getString(7));
     bytes+=Json.write(p).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1;
     if(bytes>MAX_PROPERTY_BYTES)throw new SQLException("Extended property byte budget exceeded","54000");
     all.add(p);
    }
   }
target.put("sourceExtendedProperties", all.toString());
target.put("sourceExtendedPropertiesStatus", "VISIBLE_METADATA_ONLY");
   for(JsonNode c:target.path("columns")){
    ArrayNode own=Json.mapper().createArrayNode();
    for(JsonNode p:all)if(p.path("class").asInt()==1&&p.path("minorId").asInt()>0&&c.path("name").asText().equals(p.path("column").asText()))own.add(p);
    ((ObjectNode)c).put("sourceExtendedProperties", own.toString());
    ((ObjectNode)c).put("sourceExtendedPropertiesStatus", "VISIBLE_METADATA_ONLY");
   }
}
catch (SQLException ex)
{
String status = status(ex);
target.put("sourceExtendedPropertiesStatus", status);
for (JsonNode c:target.path("columns")) if (c.isObject()) ((ObjectNode)c).put("sourceExtendedPropertiesStatus", status);
warning(target, "EXTENDED_PROPERTIES", status);
}
target.put("sourceUsage", "{}");
target.put("sourceUsageStatus", usage ? "UNAVAILABLE" : "NOT_REQUESTED");
  if(!usage)return;
  String q="SELECT SUM(CONVERT(bigint,user_seeks)),SUM(CONVERT(bigint,user_scans)),SUM(CONVERT(bigint,user_lookups)),SUM(CONVERT(bigint,user_updates)),"
   +"MAX(last_user_seek),MAX(last_user_scan),MAX(last_user_lookup),MAX(last_user_update) "
   +"FROM sys.dm_db_index_usage_stats WHERE database_id=DB_ID() AND object_id=OBJECT_ID(QUOTENAME(?) + '.' + QUOTENAME(?))";
  try(PreparedStatement st=db.prepareStatement(q,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
st.setString(1, schema);
st.setString(2, table);
st.setQueryTimeout(30);
st.setMaxRows(1);
   try(ResultSet rows=st.executeQuery()){
    ObjectNode result = Json.object();
    result.put("observedAt", java.time.Instant.now().toString());
    result.put("harvestedSchema", schema);
    result.put("harvestedObject", table);
    result.put("meaning","Per-index cumulative operations since DMV reset; not unique queries, users, rows, or a 30-day metric.");
    boolean reported=false;
    if(rows.next()){
     String[] names={"seeks","scans","lookups","updates","lastSeek","lastScan","lastLookup","lastUpdate"};
    for (int i = 1; i <= 8; i++)
    {
        Object v = rows.getObject(i);
        if (v != null)
        {
            reported = true;
            if (i <= 4) result.put(names[i-1], ((Number)v).longValue());
            else result.put(names[i-1], v.toString());
        }
    }
    }
    target.put("sourceUsage", result.toString());
    target.put("sourceUsageStatus", reported ? "AVAILABLE" : "NO_VISIBLE_COUNTERS");
   }
}
catch (SQLException ex)
{
String status = status(ex);
target.put("sourceUsageStatus", status);
warning(target, "USAGE_STATISTICS", status);
}
 }
private static void put(ObjectNode node, String name, String value)
{
    if (value == null) node.putNull(name);
    else node.put(name, value);
}
 static String status(SQLException error){
  String state=error.getSQLState();
  if(state!=null&&state.startsWith("54"))return "LIMIT_EXCEEDED";
  // Class 42 includes syntax/object availability errors as well as access violations.
  // Only explicit permission denial (SQL Server 229) or authentication states justify DENIED.
  if(error.getErrorCode()==229 || (state!=null&&state.startsWith("28")))return "ACCESS_DENIED";
  return "UNAVAILABLE";
 }
 private static void warning(ObjectNode target,String component,String code){target.withArray("sourceMetadataWarnings").addObject().put("component",component).put("code",code);}
}
