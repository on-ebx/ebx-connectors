package com.on.ebx.connect.connectors;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.on.ebx.connect.core.*;
import com.on.ebx.connect.spi.*;
/** Bounded Hadoop-compatible access through a non-redirecting HTTPS WebHDFS/HttpFS gateway. */
public final class HadoopWebHdfsConnector implements Connector{
private static final int MAX_LIST = 1000, MAX_BYTES = 10*1024*1024;
private static final List<Operation> OPS = Arrays.asList(Operation.search("list", "Bounded LISTSTATUS", "path", "max"), Operation.read("status", "Read one file status", "path"), Operation.read("open", "Read a bounded byte range", "path", "offset", "length"), Operation.write("create", "Create a bounded file from contentBase64", "path"), Operation.write("mkdirs", "Create a directory", "path"), Operation.write("delete", "Delete a file or empty directory", "path"));
private ConnectorConfig config;
private final boolean loopback;
public HadoopWebHdfsConnector()
{
    this(false);
}
HadoopWebHdfsConnector(boolean l)
{
    loopback = l;
}
public String getType()
{
    return "hadoop-webhdfs";
}
public String getLabel()
{
    return "Hadoop WebHDFS / HttpFS";
}
public List<Operation>getOperations()
{
    return OPS;
}
public void configure(ConnectorConfig c)
{
    config = c;
}
public ConfigurationValidationResult validateConfiguration(ConnectorConfig c)
{
    List<ConfigurationValidationIssue>i = new ArrayList<ConfigurationValidationIssue>();
    if (c == null) return new ConfigurationValidationResult(i);
    try
    {
        URI u = URI.create(c.getBaseUrl());
        boolean local = loopback && ("127.0.0.1".equals(u.getHost()) || "localhost".equalsIgnoreCase(u.getHost()));
        if (u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null || (!local && !"https".equalsIgnoreCase(u.getScheme())) || !u.getPath().matches(".*/webhdfs/v1/?")) i.add(new ConfigurationValidationIssue("baseUrl", "Use an HTTPS gateway URL ending in /webhdfs/v1"));
    }
    catch (Exception e)
    {
        i.add(new ConfigurationValidationIssue("baseUrl", "Use a valid WebHDFS/HttpFS gateway URL"));
    }
    if (c.getAuthType() != ConnectorConfig.AuthType.BEARER) i.add(new ConfigurationValidationIssue("authType", "Use a delegated bearer token"));
    if (c.isPlaceholder(c.getApiKey())) i.add(new ConfigurationValidationIssue("apiKey", "Set the gateway bearer token"));
    return new ConfigurationValidationResult(i);
}
public JsonNode execute(ConnectorContext x, String op, Map<String, String> inputs, JsonNode payload)throws ConnectorException
{
    if (config == null || !validateConfiguration(config).isValid()) throw new ConnectorException("Hadoop WebHDFS configuration is invalid");
    Map<String, String>in = inputs == null ? Collections.<String, String>emptyMap() : inputs;
    String path = safePath(req(in, "path"));
    if ("list".equals(op))
    {
        int max = bound(in.get("max"), 100, 1, MAX_LIST, "max");
        JsonNode body = json(call("GET", path, q("op", "LISTSTATUS"), null));
        ArrayNode rows = Json.mapper().createArrayNode();
        JsonNode statuses = body.path("FileStatuses").path("FileStatus");
        if (statuses.isArray()) for (JsonNode s:statuses) if (rows.size() < max) rows.add(s);
        ObjectNode out = Json.object();
        out.set("results", rows);
        out.put("count", rows.size());
        return out;
    }
    if ("status".equals(op)) return json(call("GET", path, q("op", "GETFILESTATUS"), null));
    if ("open".equals(op))
    {
        int length = bound(in.get("length"), 1024, 1, MAX_BYTES, "length"), offset = bound(in.get("offset"), 0, 0, Integer.MAX_VALUE, "offset");
        Map<String, String>query = q("op", "OPEN");
        query.put("offset", String.valueOf(offset));
        query.put("length", String.valueOf(length));
        HttpJsonClient.Response r = call("GET", path, query, null);
        ok(r, "open");
        byte[]b = r.getRawBody() == null ? new byte[0] : r.getRawBody().getBytes(StandardCharsets.ISO_8859_1);
        if (b.length > length) throw new ConnectorException("WebHDFS gateway exceeded requested length");
        return Json.object().put("bytes", b.length).put("contentBase64", Base64.getEncoder().encodeToString(b));
    }
    if ("create".equals(op))
    {
        if (payload == null || !payload.isObject()) throw new ConnectorException("WebHDFS create requires contentBase64");
        byte[]b;
        try
        {
            b = Base64.getDecoder().decode(payload.path("contentBase64").asText());
        }
        catch (Exception e)
        {
            throw new ConnectorException("WebHDFS contentBase64 is invalid");
        }
        if (b.length > MAX_BYTES) throw new ConnectorException("WebHDFS create exceeds 10 MiB");
        Map<String, String>query = q("op", "CREATE");
        query.put("overwrite", String.valueOf(Boolean.parseBoolean(in.get("overwrite"))));
        ok(call("PUT", path, query, b), "create");
        return Json.object().put("written", b.length);
    }
    if ("mkdirs".equals(op))
    {
        ok(call("PUT", path, q("op", "MKDIRS"), new byte[0]), "mkdirs");
        return Json.object().put("created", true);
    }
    if ("delete".equals(op))
    {
        Map<String, String>query = q("op", "DELETE");
        query.put("recursive", "false");
        return json(call("DELETE", path, query, null));
    }
    throw new ConnectorException("WebHDFS: unknown operation '"+op+"'");
}
private HttpJsonClient.Response call(String method, String path, Map<String, String>query, byte[]body)throws ConnectorException
{
    try
    {
        StringBuilder u = new StringBuilder(config.getBaseUrl().replaceAll("/+$", "")).append(path);
        char sep = '?';
        for (Map.Entry<String, String>e:query.entrySet())
        {
            u.append(sep).append(AwsSigV4.encode(e.getKey())).append('=').append(AwsSigV4.encode(e.getValue()));
            sep = '&';
        }
        Map<String, String>h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Bearer "+config.getApiKey());
        h.put("Accept", "application/json");
        if (body != null) h.put("Content-Type", "application/octet-stream");
        HttpJsonClient.Response r = new HttpJsonClient(config.getConnectTimeoutMs(), config.getReadTimeoutMs(), "EBX-Connectors/1.0 (webhdfs)").sendBytesWithoutRedirects(method, u.toString(), h, body, MAX_BYTES+65536);
        if (r.getStatus() >= 300 && r.getStatus() < 400) throw new ConnectorException("WebHDFS redirects are refused; use a non-redirecting gateway");
        return r;
    }
    catch (ConnectorException e)
    {
        throw e;
    }
    catch (Exception e)
    {
        throw new ConnectorException("WebHDFS request failed: "+e.getClass().getSimpleName(), e);
    }
}
private static JsonNode json(HttpJsonClient.Response r)throws ConnectorException
{
    ok(r, "request");
    try
    {
        return r.getRawBody() == null ? Json.object() : Json.parse(r.getRawBody());
    }
    catch (Exception e)
    {
        throw new ConnectorException("WebHDFS returned invalid JSON");
    }
}
private static void ok(HttpJsonClient.Response r, String a)throws ConnectorException
{
    if (!r.isSuccess()) throw new ConnectorException("WebHDFS "+a+" failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
}
private static Map<String, String>q(String k, String v)
{
    Map<String, String>m = new LinkedHashMap<String, String>();
    m.put(k, v);
    return m;
}
private static String req(Map<String, String>m, String k)throws ConnectorException
{
    String v = m.get(k);
    if (v == null || v.trim().isEmpty()) throw new ConnectorException("WebHDFS "+k+" is required");
    return v;
}
private static String safePath(String v)throws ConnectorException
{
    if (!v.startsWith("/") || v.contains("\\") || v.indexOf('\0') >= 0) throw new ConnectorException("WebHDFS path is invalid");
    for (String s:v.split("/")) if ("..".equals(s)) throw new ConnectorException("WebHDFS parent traversal is refused");
    return v;
}
private static int bound(String v, int d, int min, int max, String n)throws ConnectorException
{
    try
    {
        int z = v == null ? d : Integer.parseInt(v);
        if (z < min || z > max) throw new ConnectorException("WebHDFS "+n+" must be between "+min+" and "+max);
        return z;
    }
    catch (NumberFormatException e)
    {
        throw new ConnectorException("WebHDFS "+n+" must be an integer");
    }
}
public ConnectorDescriptor getDescriptor()
{
    ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "DATA_PLATFORMS").description("Bounded Hadoop-compatible file access through an HTTPS WebHDFS/HttpFS gateway. Native redirects, Kerberos, HDFS clients, Hive and recursive deletion are excluded.").authentication(AuthenticationMethod.BEARER_TOKEN).capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).apiVersion("WebHDFS REST via non-redirecting gateway; live acceptance required").pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.OFFSET, 100, MAX_LIST, null)).defaults(ConnectorDefaults.standard());
    for (Operation o:OPS)
    {
        ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
        b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
    }
    return b.build();
}
}
