package com.on.ebx.connect.connectors;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.*;
import com.on.ebx.connect.spi.*;
/** Google Sheets v4 with bounded reads and explicit, RAW-by-default writes. */
public final class GoogleSheetsConnector extends AbstractRestConnector{
private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";
private static final int MAX = 1048576;
 private static final List<Operation> OPS=Collections.unmodifiableList(Arrays.asList(
  new Operation("metadata",Operation.Kind.READ,"Workbook and sheet metadata",new String[]{"spreadsheetId"},new String[0]),
  new Operation("read",Operation.Kind.READ,"Bounded values from an explicit A1 range",new String[]{"spreadsheetId","range"},new String[0]),
  new Operation("append",Operation.Kind.WRITE,"Append rows to an explicit range",new String[]{"spreadsheetId","range"},new String[0]),
  new Operation("update",Operation.Kind.WRITE,"Update an explicit range",new String[]{"spreadsheetId","range"},new String[0])));
private final GoogleAuth auth = new GoogleAuth();
public String getType()
{
    return "google-sheets";
}
public String getLabel()
{
    return "Google Sheets";
}
public List<Operation> getOperations()
{
    return OPS;
}
public ConnectorDescriptor getDescriptor()
{
    ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS").description("Google Sheets v4 metadata, bounded range reads, and explicit RAW-by-default append/update.").authentication(AuthenticationMethod.SERVICE_ACCOUNT).authentication(AuthenticationMethod.BEARER_TOKEN).capability(ConnectorCapability.DISCOVER_OBJECTS).capability(ConnectorCapability.PREVIEW_RECORDS).apiVersion("Google Sheets API v4").configurationProperty(prop("serviceAccountJson", ConfigurationPropertyType.SECRET)).configurationProperty(prop("serviceAccountFile", ConfigurationPropertyType.FILE)).configurationProperty(prop("allowFormulas", ConfigurationPropertyType.BOOLEAN));
    for (Operation o:OPS)
    {
        ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
        b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
    }
    return b.build();
}
public JsonNode execute(ConnectorContext c, String op, Map<String, String> in, JsonNode payload)throws ConnectorException
{
    String id = req(in, "spreadsheetId");
    if (!id.matches("[A-Za-z0-9_-]+")) throw new ConnectorException("Sheets spreadsheetId is invalid");
    String root = "/spreadsheets/"+id;
    if ("metadata".equals(op)) return call("GET", root+"?fields=spreadsheetId%2Cproperties(title%2Clocale%2CtimeZone)%2Csheets(properties(sheetId%2Ctitle%2Cindex%2CrowCount%2CcolumnCount))", null);
    String range = req(in, "range");
    if (range.length() > 500 || range.indexOf('\n') >= 0 || range.indexOf('\r') >= 0) throw new ConnectorException("Sheets range is invalid");
    String encoded = HttpJsonClient.encode(range);
    if ("read".equals(op)) return call("GET", root+"/values/"+encoded+"?majorDimension=ROWS&valueRenderOption=UNFORMATTED_VALUE", null);
    if ("append".equals(op) || "update".equals(op))
    {
        if (payload == null) throw new ConnectorException("Sheets write requires a payload with values");
        ObjectNode body = Json.object();
        body.put("range", range);
        body.put("majorDimension", "ROWS");
        body.set("values", payload.has("values") ? payload.get("values") : payload);
        boolean formulas = "true".equalsIgnoreCase(getConfig().getProperty("allowFormulas", "false"));
        String suffix = "?valueInputOption="+(formulas ? "USER_ENTERED" : "RAW");
        return call("append".equals(op) ? "POST" : "PUT", root+"/values/"+encoded+("append".equals(op) ? ":append" : "")+suffix, body);
    }
    throw new ConnectorException("Sheets: unknown operation '"+op+"'");
}
private JsonNode call(String method, String path, JsonNode body)throws ConnectorException
{
    ConnectorConfig c = getConfig();
    Map<String, String> h = new LinkedHashMap<String, String>();
    h.put("Authorization", "Bearer "+auth.token(c, SCOPE));
    h.put("Content-Type", "application/json");
    try
    {
        HttpJsonClient.Response r = new HttpJsonClient(c.getConnectTimeoutMs(), c.getReadTimeoutMs(), "EBX-Connectors/1.0 (google-sheets)").sendBytesWithoutRedirects(method, endpoint()+path, h, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), MAX);
        if (!r.isSuccess()) throw new ConnectorException("Sheets request failed (HTTP "+r.getStatus()+")", r.getStatus(), null);
        return r.getBody() == null ? Json.object() : r.getBody();
    }
    catch (java.io.IOException e)
    {
        throw new ConnectorException("Sheets request failed: "+e.getMessage(), e);
    }
}
private String endpoint()throws ConnectorException
{
    String x = getConfig().getBaseUrl(), e = x == null || x.trim().isEmpty() ? "https://sheets.googleapis.com/v4" : x.trim().replaceAll("/+$", "");
    URI u;
    try
    {
        u = URI.create(e);
    }
    catch (Exception z)
    {
        throw new ConnectorException("Sheets endpoint is invalid");
    }
    String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
    boolean official = "https".equalsIgnoreCase(u.getScheme()) && "sheets.googleapis.com".equals(h) && "/v4".equals(u.getPath()), local = x != null && "http".equalsIgnoreCase(u.getScheme()) && ("127.0.0.1".equals(h) || "localhost".equals(h));
    if (!official && !local) throw new ConnectorException("Sheets endpoint must be the official v4 API or an explicit localhost fixture");
    try
    {
        Egress.check(e);
    }
    catch (IllegalStateException z)
    {
        throw new ConnectorException(z.getMessage());
    }
    return e;
}
private static String req(Map<String, String> m, String n)throws ConnectorException
{
    String v = m == null ? null : m.get(n);
    if (v == null || v.trim().isEmpty()) throw new ConnectorException("Missing input '"+n+"'");
    return v.trim();
}
private static ConfigurationPropertyDefinition prop(String n, ConfigurationPropertyType t)
{
    return ConfigurationPropertyDefinition.builder(n, t).required(false).build();
}
}
