package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.RestPageTraversal;
import com.on.ebx.connect.spi.*;

/** Microsoft SharePoint Online through Microsoft Graph with bounded list and drive operations. */
public class SharePointConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS=Arrays.asList(
		Operation.search("catalog","Inventory files, source labels and SharePoint metadata","source"),
        Operation.search("glossary","Read SharePoint Term Store (delegated TermStore.Read.All)","source"),
		Operation.read("site","Resolve a SharePoint site by host and server-relative path","host","path"),
		Operation.search("listItems","Read a bounded page of list items and fields","listId"),
		Operation.read("getItem","Read one SharePoint list item and fields","listId","itemId"),
		Operation.write("createItem","Create a SharePoint list item from a fields object","listId"),
		Operation.write("updateItem","Update a SharePoint list item's fields","listId","itemId"),
		Operation.search("listFiles","Read a bounded page of children in a drive folder","driveId"),
		Operation.read("downloadTextFile","Download bounded UTF-8 master-data file content as Base64","driveId","itemId"),
		Operation.write("uploadFile","Upload bounded file content (payload contentBase64)","driveId","path"));
	@Override public String getType(){return "sharepoint";}
	@Override public String getLabel(){return "Microsoft SharePoint";}
	@Override public List<Operation> getOperations(){return OPERATIONS;}

	@Override public void configure(final ConnectorConfig c)
	{
		if (c != null)
		{
			final String tenant = c.getProperty("tenantId", null);
			if (tenant != null && c.getProperty("oauth.tokenUrl", null) == null) c.setProperty("oauth.tokenUrl", "https://login.microsoftonline.com/"+tenant+"/oauth2/v2.0/token");
			if (c.getProperty("oauth.scope", null) == null) c.setProperty("oauth.scope", "https://graph.microsoft.com/.default");
			if (c.getProperty("oauth.clientId", null) == null && c.getProperty("clientId", null) != null) c.setProperty("oauth.clientId", c.getProperty("clientId", null));
		}
		super.configure(c);
	}
	@Override protected String baseUrl(){return getConfig().getBaseUrl().replaceAll("/+$","");}
	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> h = super.headers();
		h.put("Accept", "application/json");
		return h;
	}
	@Override public ConfigurationValidationResult validateConfiguration(final ConnectorConfig c)
	{
		final java.util.List<ConfigurationValidationIssue> issues=new java.util.ArrayList<ConfigurationValidationIssue>();
		if(c==null||c.getBaseUrl()==null||!c.getBaseUrl().matches("https://graph\\.microsoft\\.(com|us|de|cn)(/.*)?"))issues.add(new ConfigurationValidationIssue("baseUrl","Use an HTTPS Microsoft Graph cloud endpoint"));
		if(c!=null&&c.getAuthType()!=ConnectorConfig.AuthType.BEARER)for(final String p:new String[]{"tenantId","clientId"})if(blank(c.getProperty(p,null)))issues.add(new ConfigurationValidationIssue(p,p+" is required"));
		if (c != null && c.isPlaceholder(c.getApiKey())) issues.add(new ConfigurationValidationIssue("apiKey", "Microsoft Entra client secret is required"));
		return new ConfigurationValidationResult(issues);
	}
	@Override public JsonNode execute(final ConnectorContext context,final String operation,final Map<String,String> in,final JsonNode payload)throws ConnectorException
	{
		requireSecret();
        if("catalog".equals(operation)||"glossary".equals(operation)) {
            final Map<String,String> input=new LinkedHashMap<String,String>();
            for(String k:new String[]{"siteId","driveId","folderId","extractSensitivityLabels","max","maxRequests"}) {
                String v = getConfig().getProperty(k, null);
                if (v != null) input.put(k, v);
            }
            if(in!=null) input.putAll(in);
            return new SharedDriveCatalog(new SharedDriveCatalog.Transport() {
                public JsonNode call(String method,String path)throws ConnectorException {
                    HttpJsonClient.Response r=sendBytesBounded(method,path,null,null,null,2097152);
                    if(!r.isSuccess()) throw new ConnectorException("Microsoft metadata request failed",r.getStatus(),null);
                    return r.getBody();
                }
            },baseUrl(),getType(),getConfig().getProperty("tenantId",""),input).run(operation);
        }
		if("site".equals(operation))return bodyOrNull(get("/sites/"+seg(req(in,"host"))+":"+serverPath(req(in,"path")),null),"site");
		final String site=seg(value(in,"siteId",getConfig().getProperty("siteId","")));
		if(site.isEmpty())throw new ConnectorException("SharePoint siteId is required");
		if ("listItems".equals(operation))
		{
			final Map<String, String> q = query(in);
			q.put("$expand", "fields");
			return pages("/sites/"+site+"/lists/"+seg(req(in, "listId"))+"/items", q, in);
		}
		if ("getItem".equals(operation))
		{
			final Map<String, String> q = new LinkedHashMap<String, String>();
			q.put("$expand", "fields");
			return bodyOrNull(get("/sites/"+site+"/lists/"+seg(req(in, "listId"))+"/items/"+seg(req(in, "itemId")), q), "list item");
		}
		if("createItem".equals(operation))return bodyOrNull(send("POST","/sites/"+site+"/lists/"+seg(req(in,"listId"))+"/items",null,fields(payload)),"create item");
		if("updateItem".equals(operation))return bodyOrNull(send("PATCH","/sites/"+site+"/lists/"+seg(req(in,"listId"))+"/items/"+seg(req(in,"itemId"))+"/fields",null,payload==null?Json.object():payload),"update item");
		final String drive = seg(value(in, "driveId", getConfig().getProperty("driveId", "")));
		if (drive.isEmpty()) throw new ConnectorException("SharePoint driveId is required");
		if ("listFiles".equals(operation))
		{
			final String folder = value(in, "folderId", "root");
			return pages("/drives/"+drive+"/items/"+seg(folder)+"/children", query(in), in);
		}
		if ("downloadTextFile".equals(operation))
		{
			final int max = bound(value(in, "maxBytes", getConfig().getProperty("max.file.bytes", "1048576")), 1, 10485760);
			final HttpJsonClient.Response r = sendBytesBounded("GET", "/drives/"+drive+"/items/"+seg(req(in, "itemId"))+"/content", null, null, null, max);
			if (!r.isSuccess()) return bodyOrNull(r, "download text file");
			final byte[] bytes = (r.getRawBody() == null ? "" : r.getRawBody()).getBytes(StandardCharsets.UTF_8);
			final ObjectNode out = Json.object();
			out.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
			out.put("bytes", bytes.length);
			out.put("encoding", "UTF-8");
			return out;
		}
		if ("uploadFile".equals(operation))
		{
			if (payload == null || blank(payload.path("contentBase64").asText(null))) throw new ConnectorException("SharePoint upload requires contentBase64");
			final byte[] bytes;
			try
			{
				bytes = Base64.getDecoder().decode(payload.path("contentBase64").asText());
			}
			catch (final IllegalArgumentException ex)
			{
				throw new ConnectorException("SharePoint contentBase64 is invalid", ex);
			}
			final int max = bound(getConfig().getProperty("max.file.bytes", "1048576"), 1, 10485760);
			if (bytes.length > max) throw new ConnectorException("SharePoint upload exceeds max.file.bytes");
			final Map<String, String> h = new LinkedHashMap<String, String>();
			h.put("Content-Type", value(in, "contentType", "application/octet-stream"));
			return bodyOrNull(sendBytesBounded("PUT", "/drives/"+drive+"/root:/"+filePath(req(in, "path"))+":/content", null, bytes, h, max), "upload file");
		}
		throw new ConnectorException("SharePoint: unknown operation '"+operation+"'");
	}
	private JsonNode pages(final String path,final Map<String,String> query,final Map<String,String> in)throws ConnectorException
	{
		final int max=bound(value(in,"max",getConfig().getProperty("max.rows","200")),1,5000);
		final String initial=HttpJsonClient.buildUrl(this.baseUrl(),path,query);
		try
		{
			return RestPageTraversal.collect(initial, "value", "@odata.nextLink", 50, max, new RestPageTraversal.Fetcher()
			{
				public HttpJsonClient.Response get(final String url)throws java.io.IOException
				{
					try
					{
						return SharePointConnector.this.getAbsolute(url, null);
					}
					catch (final ConnectorException ex)
					{
						throw new java.io.IOException(ex.getMessage(), ex);
					}
				}
			});
		}
		catch(final java.io.IOException ex){throw new ConnectorException("SharePoint pagination failed: "+ex.getMessage(),ex);}
	}
	private static ObjectNode fields(final JsonNode payload)
	{
		final ObjectNode o = Json.object();
		o.set("fields", payload == null ? Json.object() : payload);
		return o;
	}
	private static Map<String, String> query(final Map<String, String> in)
	{
		final Map<String, String> q = new LinkedHashMap<String, String>();
		for (final String k:new String[] {"filter", "select", "orderby", "expand"})
		{
			final String v = in == null ? null : in.get(k);
			if (v != null) q.put("$"+k, v);
		}
		q.put("$top", String.valueOf(bound(in == null ? null : in.get("max"), 1, 999)));
		return q;
	}
	static String graphRoot(final ConnectorConfig c){return c.getBaseUrl().replaceAll("/+$","");}
	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS").description("SharePoint Online list and drive exchange through Microsoft Graph").authentication(AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS).authentication(AuthenticationMethod.BEARER_TOKEN).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).apiVersion("Microsoft Graph v1.0").pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.LINK, 200, 5000, "@odata.nextLink")).configurationProperty(prop("tenantId", true)).configurationProperty(prop("clientId", true)).configurationProperty(prop("siteId", false)).configurationProperty(prop("driveId", false)).configurationProperty(prop("folderId", false)).configurationProperty(prop("extractSensitivityLabels", false)).configurationProperty(ConfigurationPropertyDefinition.builder("max.rows", ConfigurationPropertyType.INTEGER).defaultValue(Integer.valueOf(200)).build()).configurationProperty(ConfigurationPropertyDefinition.builder("max.file.bytes", ConfigurationPropertyType.INTEGER).defaultValue(Integer.valueOf(1048576)).build());
		for (final Operation o:getOperations())
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	private static ConfigurationPropertyDefinition prop(final String n,final boolean r){return ConfigurationPropertyDefinition.builder(n,ConfigurationPropertyType.STRING).required(r).build();}
	private static String req(final Map<String, String> in, final String k)throws ConnectorException
	{
		final String v = in == null ? null : in.get(k);
		if (blank(v)) throw new ConnectorException("SharePoint input '"+k+"' is required");
		return v;
	}
	private static String value(final Map<String, String> in, final String k, final String d)
	{
		final String v = in == null ? null : in.get(k);
		return blank(v) ? d : v;
	}
	private static String seg(final String v){return v==null?"":v.trim().replaceAll("[^A-Za-z0-9,._-]","");}
	private static String serverPath(final String v){return "/"+v.replaceFirst("^/+","").replaceAll("[^A-Za-z0-9/._ -]","").replace(" ","%20");}
	private static String filePath(final String v){return v.replaceFirst("^/+","").replace("..","").replaceAll("[^A-Za-z0-9/._ -]","").replace(" ","%20");}
	private static boolean blank(final String v){return v==null||v.trim().isEmpty();}
	private static int bound(final String v, final int min, final int max)
	{
		try
		{
			return Math.max(min, Math.min(max, Integer.parseInt(v)));
		}
		catch (final Exception ex)
		{
			return max;
		}
	}
}
