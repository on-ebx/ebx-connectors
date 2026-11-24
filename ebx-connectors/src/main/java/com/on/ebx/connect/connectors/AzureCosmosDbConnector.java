package com.on.ebx.connect.connectors;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.core.*;
import com.on.ebx.connect.spi.*;
/** Azure Cosmos DB NoSQL REST profile. Entra bearer and scoped resource tokens only; Shared Key and change feed are out of scope. */
public final class AzureCosmosDbConnector implements Connector{
private static final int MAX_REQUEST = 1024*1024, MAX_RESPONSE = 2*1024*1024;
private static final String VERSION = "2018-12-31";
private static final List<Operation> OPS = Arrays.asList(Operation.search("databases", "Discover databases", "max"), Operation.search("containers", "Discover containers", "database", "max"), Operation.read("get", "Point-read one item", "database", "container", "id", "partitionKey"), Operation.search("query", "Bounded parameterized query", "database", "container", "query", "partitionKey", "max", "continuation"), Operation.write("create", "Create an item with an explicit partition key", "database", "container", "partitionKey"), Operation.write("upsert", "Upsert an item with an explicit partition key", "database", "container", "partitionKey"), Operation.write("delete", "Delete one item with an explicit partition key", "database", "container", "id", "partitionKey"));
private ConnectorConfig config;
public String getType()
{
    return "azure-cosmos-db";
}
public String getLabel()
{
    return "Azure Cosmos DB (NoSQL)";
}
public List<Operation>getOperations()
{
    return OPS;
}
public void configure(ConnectorConfig c)
{
    config = c;
    Redaction.remember(c);
}
public ConnectorDescriptor getDescriptor()
{
    ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "DATA_PLATFORMS").description("Azure Cosmos DB NoSQL REST: bounded discovery, point reads, parameterized queries with continuation, and partition-key-scoped item mutations. No Shared Key or change feed.").authentication(AuthenticationMethod.BEARER_TOKEN).authentication(AuthenticationMethod.CUSTOM).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.PREVIEW_RECORDS).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).apiVersion("Cosmos DB NoSQL REST "+VERSION).pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.CURSOR, 100, 1000, "continuation")).configurationProperty(prop("resourceToken", ConfigurationPropertyType.SECRET));
    for (Operation o:OPS)
    {
        ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
        b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
    }
    return b.build();
}
public ConfigurationValidationResult validateConfiguration(ConnectorConfig c)
{
    List<ConfigurationValidationIssue>i = new ArrayList<ConfigurationValidationIssue>(Connector.super.validateConfiguration(c).getIssues());
    if (c != null && !safe(c.getBaseUrl())) i.add(new ConfigurationValidationIssue("baseUrl", "Use an HTTPS *.documents.azure.com endpoint; localhost HTTP is fixture-only"));
    if (c != null && c.getProperty("resourceToken", null) == null && (c.getAuthType() != ConnectorConfig.AuthType.BEARER || c.isPlaceholder(c.getApiKey()))) i.add(new ConfigurationValidationIssue("apiKey", "Set an Entra bearer token or scoped resourceToken; Shared Key is not accepted"));
    return new ConfigurationValidationResult(i);
}
public JsonNode execute(ConnectorContext c, String op, Map<String, String>in, JsonNode payload)throws ConnectorException
{
    if ("databases".equals(op)) return boundedFeed(request("GET", "/dbs", null, null, null), "Databases", bound(in.get("max"), 100));
    String db = seg(req(in, "database"));
    if ("containers".equals(op)) return boundedFeed(request("GET", "/dbs/"+db+"/colls", null, null, null), "DocumentCollections", bound(in.get("max"), 100));
    String col = seg(req(in, "container")), root = "/dbs/"+db+"/colls/"+col+"/docs";
    if ("get".equals(op)) return request("GET", root+"/"+seg(req(in, "id")), null, pk(in), null);
    if ("query".equals(op))
    {
        int max = bound(in.get("max"), 100);
        ObjectNode body = Json.object();
        body.put("query", req(in, "query"));
        JsonNode params = in.get("parameters") == null ? Json.mapper().createArrayNode() : parse(in.get("parameters"), "parameters must be a JSON array");
        if (!params.isArray()) throw new ConnectorException("Cosmos parameters must be a JSON array");
        body.set("parameters", params);
        Map<String, String>h = new LinkedHashMap<String, String>();
        h.put("x-ms-documentdb-isquery", "true");
        h.put("x-ms-max-item-count", String.valueOf(max));
        h.put("Content-Type", "application/query+json");
        if (in.get("continuation") != null)
        {
            if (in.get("continuation").length() > 8192) throw new ConnectorException("Cosmos continuation exceeds 8192 characters");
            h.put("x-ms-continuation", in.get("continuation"));
        }
        if (in.get("partitionKey") != null) h.put("x-ms-documentdb-partitionkey", partition(in.get("partitionKey")));
        else h.put("x-ms-documentdb-query-enablecrosspartition", "true");
        return request("POST", root, body, h, null);
    }
    if ("create".equals(op) || "upsert".equals(op))
    {
        if (payload == null || !payload.isObject()) throw new ConnectorException("Cosmos item write requires an object payload");
        Map<String, String>h = pk(in);
        if ("upsert".equals(op)) h.put("x-ms-documentdb-is-upsert", "true");
        return request("POST", root, payload, h, null);
    }
    if ("delete".equals(op))
    {
        request("DELETE", root+"/"+seg(req(in, "id")), null, pk(in), null);
        return Json.object().put("deleted", true);
    }
    throw new ConnectorException("Cosmos: unknown operation '"+op+"'");
}
private JsonNode request(String method, String path, JsonNode body, Map<String, String>extra, String unused)throws ConnectorException
{
    try
    {
        if (config == null || !safe(config.getBaseUrl())) throw new ConnectorException("Cosmos endpoint is invalid");
        byte[]bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes != null && bytes.length > MAX_REQUEST) throw new ConnectorException("Cosmos request exceeds 1 MiB");
        Map<String, String>h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("x-ms-version", VERSION);
        h.put("x-ms-date", date());
        String token = config.getProperty("resourceToken", null);
        if (token != null && !config.isPlaceholder(token)) h.put("Authorization", token);
        else if (config.getAuthType() == ConnectorConfig.AuthType.BEARER && !config.isPlaceholder(config.getApiKey())) h.put("Authorization", "Bearer "+config.getApiKey());
        else throw new ConnectorException("Cosmos requires an Entra bearer or scoped resource token");
        if (extra != null) h.putAll(extra);
        HttpJsonClient.Response r = new HttpJsonClient(config.getConnectTimeoutMs(), config.getReadTimeoutMs(), "EBX-Connectors/1.0 (cosmos)").sendBytesWithoutRedirects(method, config.getBaseUrl().replaceAll("/+$", "")+path, h, bytes, MAX_RESPONSE);
        if (!r.isSuccess()) throw new ConnectorException("Cosmos request failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
        ObjectNode out = r.getBody() != null && r.getBody().isObject() ? ((ObjectNode)r.getBody()).deepCopy() : Json.object();
        String continuation = r.getHeader("x-ms-continuation");
        if (continuation != null) out.put("continuation", continuation);
        return out;
    }
    catch (ConnectorException e)
    {
        throw e;
    }
    catch (Exception e)
    {
        throw new ConnectorException("Cosmos request failed: "+e.getClass().getSimpleName(), e);
    }
}
private static Map<String, String>pk(Map<String, String>in)throws ConnectorException
{
    Map<String, String>h = new LinkedHashMap<String, String>();
    h.put("x-ms-documentdb-partitionkey", partition(req(in, "partitionKey")));
    return h;
}
private static String partition(String value)throws ConnectorException
{
    JsonNode v = parse(value, "partitionKey must be a JSON scalar or array");
    ArrayNode a = Json.mapper().createArrayNode();
    if (v.isArray()) return v.toString();
    if (v.isContainerNode()) throw new ConnectorException("partitionKey must be a JSON scalar or array");
    a.add(v);
    return a.toString();
}
private static JsonNode parse(String v, String m)throws ConnectorException
{
    try
    {
        return Json.parse(v);
    }
    catch (Exception e)
    {
        throw new ConnectorException(m);
    }
}
private static JsonNode boundedFeed(JsonNode r, String field, int max)
{
    JsonNode a = r.path(field);
    if (!a.isArray() || a.size() <= max) return r;
    ObjectNode o = ((ObjectNode)r).deepCopy();
    ArrayNode n = Json.mapper().createArrayNode();
    for (int i = 0; i < max; i++) n.add(a.get(i));
    o.set(field, n);
    return o;
}
private static boolean safe(String v)
{
    try
    {
        URI u = URI.create(v);
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
        return u.getUserInfo() == null && u.getFragment() == null && ("https".equalsIgnoreCase(u.getScheme()) && (h.endsWith(".documents.azure.com") || h.equals("documents.azure.com")) || "http".equalsIgnoreCase(u.getScheme()) && (h.equals("127.0.0.1") || h.equals("localhost")));
    }
    catch (Exception e)
    {
        return false;
    }
}
private static String date()
{
    SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("GMT"));
    return f.format(new Date());
}
private static int bound(String v, int d)throws ConnectorException
{
    try
    {
        int x = v == null ? d : Integer.parseInt(v);
        if (x < 1 || x > 1000) throw new Exception();
        return x;
    }
    catch (Exception e)
    {
        throw new ConnectorException("Cosmos max must be between 1 and 1000");
    }
}
private static String req(Map<String, String>m, String n)throws ConnectorException
{
    String v = m == null ? null : m.get(n);
    if (v == null || v.trim().isEmpty()) throw new ConnectorException("Cosmos: set "+n);
    return v.trim();
}
private static String seg(String v)throws ConnectorException
{
    if (!v.matches("[A-Za-z0-9._~-]+")) throw new ConnectorException("Cosmos resource identifier is invalid");
    return v;
}
private static ConfigurationPropertyDefinition prop(String n, ConfigurationPropertyType t)
{
    return ConfigurationPropertyDefinition.builder(n, t).required(false).build();
}
}
