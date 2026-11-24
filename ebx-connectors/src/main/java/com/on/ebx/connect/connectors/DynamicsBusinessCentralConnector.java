package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.ODataSupport;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PaginationDescriptor;

/** Microsoft Dynamics 365 Business Central standard API (OData v4), scoped to a company. */
public final class DynamicsBusinessCentralConnector extends AbstractCrudRestConnector
{
	private static final java.util.List<Operation> OPERATIONS = java.util.Arrays.asList(
		Operation.read("get", "One Business Central entity by id", "object", "id"),
		Operation.search("query", "Business Central entities using OData filter/select/expand/orderby/top", "object"),
		Operation.write("create", "Create a Business Central entity", "object"),
		Operation.write("update", "Update a Business Central entity by id and optional etag", "object", "id"));
	@Override public String getType() { return "dynamics-business-central"; }
	@Override public String getLabel() { return "Microsoft Dynamics 365 Business Central"; }
	@Override public java.util.List<Operation> getOperations() { return new java.util.ArrayList<Operation>(OPERATIONS); }

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null)
		{
			final String tenant = pConfig.getProperty("tenantId", null);
			if (tenant != null && pConfig.getProperty("oauth.tokenUrl", null) == null)
				pConfig.setProperty("oauth.tokenUrl", "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token");
			if (pConfig.getProperty("oauth.scope", null) == null)
				pConfig.setProperty("oauth.scope", "https://api.businesscentral.dynamics.com/.default");
			if (pConfig.getProperty("oauth.clientId", null) == null && pConfig.getProperty("clientId", null) != null)
				pConfig.setProperty("oauth.clientId", pConfig.getProperty("clientId", null));
		}
		super.configure(pConfig);
	}

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final java.util.List<ConfigurationValidationIssue> issues = new java.util.ArrayList<ConfigurationValidationIssue>();
		if (pConfig == null || pConfig.getBaseUrl() == null || !pConfig.getBaseUrl().matches("https://[^/]+.*"))
			issues.add(new ConfigurationValidationIssue("baseUrl", "Business Central requires an HTTPS API endpoint"));
		if (pConfig != null) for (final String required : new String[] { "tenantId", "environment", "companyId", "clientId" })
			if (blank(pConfig.getProperty(required, null))) issues.add(new ConfigurationValidationIssue(required, required + " is required"));
		if (pConfig != null && pConfig.isPlaceholder(pConfig.getApiKey())) issues.add(new ConfigurationValidationIssue("apiKey", "Microsoft Entra client secret is required"));
		return new ConfigurationValidationResult(issues);
	}

	@Override protected String baseUrl() throws ConnectorException { return apiRoot(getConfig()); }
	static String apiRoot(final ConnectorConfig pConfig)
	{
		return pConfig.getBaseUrl().replaceAll("/+$", "") + "/v2.0/" + safeSegment(pConfig.getProperty("tenantId", "")) + "/"
			+ safeSegment(pConfig.getProperty("environment", "Production")) + "/api/" + safeSegment(pConfig.getProperty("apiVersion", "v2.0"))
			+ "/companies(" + safeSegment(pConfig.getProperty("companyId", "")) + ")";
	}
	@Override protected Map<String,String> headers()
	{
		final Map<String, String> headers = super.headers();
		headers.put("Accept", "application/json");
		headers.put("OData-Version", "4.0");
		headers.put("Prefer", "return=representation");
		return headers;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String,String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		this.requireSecret();
		final String entity = AbstractCrudRestConnector.safeName(AbstractRestConnector.require(pInputs, "object"));
		if(entity.isEmpty()) throw new ConnectorException("Business Central entity is invalid");
		if("query".equals(pOperation))
		{
			final Map<String,String> query=ODataSupport.query(ODataSupport.Version.V4,pInputs,true,getConfig().getProperty("max.rows","200"),null);
			final JsonNode first = this.bodyOrNull(this.get("/"+entity, query), "query "+entity);
			final int maximum = bounded(pInputs == null ? null : pInputs.get("max"), 1, bounded(getConfig().getProperty("max.rows", "200"), 1, 10000));
			return ODataSupport.collectPages(this.baseUrl(), first, bounded(getConfig().getProperty("max.pages", "20"), 1, 100), maximum, new ODataSupport.PageFetcher()
			{
				public JsonNode get(final String url)throws ConnectorException
				{
					return DynamicsBusinessCentralConnector.this.bodyOrNull(DynamicsBusinessCentralConnector.this.getAbsolute(url, null), "OData continuation");
				}
			});
		}
		if("get".equals(pOperation)) return ODataSupport.normalise(this.bodyOrNull(this.get("/"+entity+"("+key(AbstractRestConnector.require(pInputs,"id"))+")",null),"get "+entity));
		if("create".equals(pOperation)) return ODataSupport.normalise(this.bodyOrNull(this.send("POST","/"+entity,null,payload(pPayload)),"create "+entity));
		if("update".equals(pOperation))
		{
			final String id = AbstractRestConnector.require(pInputs, "id");
			final Map<String, String> extra = new LinkedHashMap<String, String>();
			extra.put("If-Match", pInputs.get("etag") == null ? "*" : pInputs.get("etag"));
			final HttpJsonClient.Response response=this.send("PATCH","/"+entity+"("+key(id)+")",null,payload(pPayload),extra);
			return response.getStatus()==204?Json.object():ODataSupport.normalise(this.bodyOrNull(response,pOperation+" "+entity));
		}
		throw new ConnectorException("Business Central: unknown operation '"+pOperation+"'");
	}

	@Override public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b=ConnectorDescriptor.builder(getType(),getLabel(),"BUSINESS_APPS").description("Dynamics 365 Business Central company-scoped standard API over OData v4")
			.authentication(AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS).capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE)
			.apiVersion("Business Central API v2.0").pagination(new PaginationDescriptor(PaginationDescriptor.Strategy.LINK,200,10000,"@odata.nextLink"))
			.configurationProperty(prop("tenantId",ConfigurationPropertyType.STRING,true,""))
			.configurationProperty(prop("environment",ConfigurationPropertyType.STRING,true,"Production"))
			.configurationProperty(prop("companyId",ConfigurationPropertyType.STRING,true,""))
			.configurationProperty(prop("clientId",ConfigurationPropertyType.STRING,true,""))
			.configurationProperty(prop("apiVersion",ConfigurationPropertyType.STRING,false,"v2.0"))
			.configurationProperty(prop("max.rows",ConfigurationPropertyType.INTEGER,false,Integer.valueOf(200)));
		for (final Operation op:getOperations())
		{
			final ConnectorDirection d = op.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	private static ConfigurationPropertyDefinition prop(final String n,final ConfigurationPropertyType t,final boolean r,final Object d){return ConfigurationPropertyDefinition.builder(n,t).required(r).defaultValue(d).build();}
	private static boolean blank(final String v){return v==null||v.trim().isEmpty();}
	private static String safeSegment(final String v){return v==null?"":v.trim().replaceAll("[^A-Za-z0-9._-]","");}
	private static String key(final String v)
	{
		final String s = v.trim();
		return s.matches("[0-9a-fA-F-]{36}") ? s : "'"+s.replace("'", "''")+"'";
	}
	private static JsonNode payload(final JsonNode n){return n==null?Json.object():n;}
	private static int bounded(final String v, final int min, final int max)
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
