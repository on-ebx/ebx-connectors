package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.PowerBiScan;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Microsoft Power BI through the REST API ({@code https://api.powerbi.com/v1.0/myorg}) and the
 * admin Scanner API, for the data catalog: reports, dashboards, semantic models (tables, columns,
 * measures with their DAX, Power Query M of each table), dataflows, and the lineage between them
 * and their data sources.
 * <p>
 * Authentication: a Microsoft Entra service principal, OAuth 2.0 client credentials —
 * {@code authType = OAUTH2}, properties {@code tenantId} and {@code clientId}, the client secret in
 * {@code apiKey}; scope {@code https://analysis.windows.net/powerbi/api/.default}. A user token
 * pasted in {@code apiKey} with {@code authType = BEARER} works too (for a quick try).
 * <p>
 * Two scan modes ({@code scanner} property, default {@code auto}): {@code admin} uses the Scanner
 * API ({@code admin/workspaces/getInfo} with lineage, datasource details, dataset schema and
 * expressions — the service principal must be allowed by the tenant settings "Allow service
 * principals to use read-only admin APIs", "Enhance admin APIs responses with detailed metadata"
 * and "... with DAX and mashup expressions"); {@code standard} reads what the service principal is a
 * member of ({@code groups}, {@code reports}, {@code datasets}, {@code dashboards}, {@code dataflows})
 * — no table schema, no expressions, so no column lineage. {@code auto} tries admin and falls back
 * on a 401/403.
 * <p>
 * Inputs shared with the other catalog connectors: {@code source} (catalog source id, prefix of
 * every asset key), {@code schemas} (comma-separated workspace names or ids; empty = all
 * non-personal workspaces), {@code types} (asset kinds; a database-style value such as TABLE,VIEW
 * selects everything).
 */
public final class PowerBiConnector extends AbstractRestConnector
{
	public static final String DEFAULT_BASE_URL = "https://api.powerbi.com/v1.0/myorg";
	public static final String SCOPE = "https://analysis.windows.net/powerbi/api/.default";
	static final Set<String> KINDS = new LinkedHashSet<String>(Arrays.asList(PowerBiScan.KIND_REPORT, PowerBiScan.KIND_DASHBOARD, PowerBiScan.KIND_MODEL,
		PowerBiScan.KIND_DATAFLOW, PowerBiScan.KIND_TABLE));

	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("catalog", "Data catalog inventory: reports, dashboards, semantic models with their tables, columns and measures, dataflows and their entities (inputs source, schemas = workspaces, types)", "source"),
		Operation.search("lineage", "Data catalog lineage: data sources -> model tables (Power Query), columns -> measures (DAX), models -> reports and dashboards, dataflows -> models (inputs source, schemas)", "source"),
		Operation.search("usage", "Data catalog popularity: report and dashboard views from the admin activity log (inputs source, optional days <= 30)", "source"),
		Operation.search("refreshes", "Data catalog jobs: refresh history of the semantic models and dataflows the service principal can read (inputs source, schemas, optional top)", "source"),
		Operation.search("workspaces", "Workspaces visible to the service principal (optional input filter = name fragment)"),
		Operation.search("query", "Run a DAX query on a semantic model and return its rows (inputs dataset = id or workspace/name, dax)", "dataset", "dax"),
		Operation.read("scan", "Raw Scanner API result of the selected workspaces, for diagnosis (optional input schemas)"));

	@Override
	public String getType()
	{
		return "powerbi";
	}

	@Override
	public String getLabel()
	{
		return "Microsoft Power BI (REST + Scanner API)";
	}

	@Override
	public List<Operation> getOperations()
	{
		return PowerBiConnector.OPERATIONS;
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(this.getType(), this.getLabel(), "DATA_PLATFORMS")
			.description("Power BI reports, dashboards, semantic models, dataflows and their lineage for the data catalog; DAX queries on a semantic model")
			.authentication(AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS).authentication(AuthenticationMethod.BEARER_TOKEN)
			.capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.DESCRIBE_OBJECT).apiVersion("Power BI REST API v1.0")
			.configurationProperty(PowerBiConnector.prop("tenantId", ConfigurationPropertyType.STRING, null, false, "Microsoft Entra tenant id (required with OAUTH2 unless oauth.tokenUrl is set)"))
			.configurationProperty(PowerBiConnector.prop("clientId", ConfigurationPropertyType.STRING, null, false, "Application (client) id of the service principal; the client secret goes in apiKey"))
			.configurationProperty(ConfigurationPropertyDefinition.builder("scanner", ConfigurationPropertyType.ENUMERATION).enumerationValues("auto", "admin", "standard").defaultValue("auto")
				.description("admin = Scanner API (tenant-wide, schemas and expressions); standard = workspaces the principal is a member of; auto = admin, standard on 401/403").build())
			.configurationProperty(PowerBiConnector.prop("datasource.map", ConfigurationPropertyType.STRING, null, false,
				"server[/database]=CATALOG_SOURCE[:upper|:lower] per line or ';'-separated: maps a Power Query data source to the catalog source that scans it, so lineage joins the two"))
			.configurationProperty(PowerBiConnector.prop("scan.timeout", ConfigurationPropertyType.INTEGER, Integer.valueOf(180), false, "Seconds to wait for a Scanner API scan"))
			.configurationProperty(PowerBiConnector.prop("max.workspaces", ConfigurationPropertyType.INTEGER, Integer.valueOf(500), false, "Workspaces scanned at most per call"))
			.configurationProperty(PowerBiConnector.prop("include.personal", ConfigurationPropertyType.BOOLEAN, Boolean.FALSE, false, "Scan personal workspaces (My workspace) too"))
			.configurationProperty(PowerBiConnector.prop("include.hidden", ConfigurationPropertyType.BOOLEAN, Boolean.FALSE, false, "Inventory hidden tables, columns and measures"))
			.configurationProperty(PowerBiConnector.prop("dataflow.definitions", ConfigurationPropertyType.BOOLEAN, Boolean.TRUE, false, "Fetch each dataflow definition (entities and Power Query) during a scan"))
			.configurationProperty(PowerBiConnector.prop("external.assets", ConfigurationPropertyType.BOOLEAN, Boolean.TRUE, false, "Create a catalog asset for each unmapped data source table read by Power Query"))
			.configurationProperty(PowerBiConnector.prop("portal.url", ConfigurationPropertyType.URL, "https://app.powerbi.com", false, "Portal used for the asset URLs"))
			.configurationProperty(PowerBiConnector.prop("max.rows", ConfigurationPropertyType.INTEGER, Integer.valueOf(1000), false, "Rows returned at most by a DAX query"));
		for (final Operation o : PowerBiConnector.OPERATIONS)
		{
			b.direction(ConnectorDirection.INBOUND).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(),
				java.util.Collections.singleton(ConnectorDirection.INBOUND), java.util.Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	private static ConfigurationPropertyDefinition prop(final String pName, final ConfigurationPropertyType pType, final Object pDefault, final boolean pRequired, final String pDescription)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(pRequired).defaultValue(pDefault).description(pDescription).build();
	}

	// ------------------------------------------------------------------ configuration

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		if (pConfig != null && pConfig.getAuthType() == ConnectorConfig.AuthType.OAUTH2)
		{
			final String tenant = pConfig.getProperty("tenantId", null);
			if (tenant != null && !tenant.trim().isEmpty() && pConfig.getProperty("oauth.tokenUrl", null) == null)
			{
				pConfig.setProperty("oauth.tokenUrl", "https://login.microsoftonline.com/" + tenant.trim() + "/oauth2/v2.0/token");
			}
			if (pConfig.getProperty("oauth.scope", null) == null) pConfig.setProperty("oauth.scope", PowerBiConnector.SCOPE);
			if (pConfig.getProperty("oauth.clientId", null) == null && pConfig.getProperty("clientId", null) != null)
			{
				pConfig.setProperty("oauth.clientId", pConfig.getProperty("clientId", null));
			}
		}
		super.configure(pConfig);
	}

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final ConfigurationValidationResult base = super.validateConfiguration(pConfig);
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>(base.getIssues());
		if (pConfig != null && pConfig.getAuthType() == ConnectorConfig.AuthType.OAUTH2 && pConfig.getProperty("oauth.tokenUrl", null) == null
			&& PowerBiConnector.blank(pConfig.getProperty("tenantId", null)))
		{
			issues.add(new ConfigurationValidationIssue("tenantId", "tenantId is required (or set oauth.tokenUrl)"));
		}
		if (pConfig != null && pConfig.getAuthType() == ConnectorConfig.AuthType.OAUTH2 && PowerBiConnector.blank(pConfig.getProperty("clientId", pConfig.getProperty("oauth.clientId", null))))
		{
			issues.add(new ConfigurationValidationIssue("clientId", "clientId is required"));
		}
		return issues.size() == base.getIssues().size() ? base : new ConfigurationValidationResult(issues);
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	@Override
	protected String baseUrl() throws ConnectorException
	{
		final String url = this.getConfig().getBaseUrl();
		return PowerBiConnector.blank(url) ? PowerBiConnector.DEFAULT_BASE_URL : url.replaceAll("/+$", "");
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		headers.put("Accept", "application/json");
		return headers;
	}

	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.nanoTime();
		this.requireSecret();
		final Map<String, String> top = new LinkedHashMap<String, String>();
		top.put("$top", "1");
		final HttpJsonClient.Response groups = this.get("/groups", top);
		final long elapsed = (System.nanoTime() - started) / 1000000L;
		if (!groups.isSuccess())
		{
			return ConnectionTestResult.failure("GET groups -> HTTP " + groups.getStatus() + PowerBiConnector.detail(groups), elapsed);
		}
		final HttpJsonClient.Response admin = this.get("/admin/groups", top);
		if (admin.isSuccess())
		{
			return ConnectionTestResult.success("Power BI REST API reachable; admin Scanner API available (tenant-wide scans with schemas and expressions)", (System.nanoTime() - started) / 1000000L);
		}
		return ConnectionTestResult.success("Power BI REST API reachable; admin API refused (HTTP " + admin.getStatus()
				+ "): scans limited to the workspaces the service principal is a member of, without table schemas or expressions", (System.nanoTime() - started) / 1000000L);
	}

	private static String detail(final HttpJsonClient.Response pResponse)
	{
		final String raw = pResponse.getRawBody();
		return raw == null || raw.isEmpty() ? "" : " " + (raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
	}

	// ------------------------------------------------------------------ operations

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		this.requireSecret();
		final Map<String, String> inputs = pInputs == null ? new LinkedHashMap<String, String>() : pInputs;
		if ("workspaces".equals(pOperation)) return this.workspaces(inputs.get("filter"));
		if ("query".equals(pOperation)) return this.query(pContext, inputs);
		if ("scan".equals(pOperation)) return this.scan(pContext, PowerBiConnector.csv(inputs.get("schemas")));
		if ("usage".equals(pOperation)) return this.usage(pContext, inputs);
		if ("refreshes".equals(pOperation)) return this.refreshes(pContext, inputs);
		if ("catalog".equals(pOperation) || "lineage".equals(pOperation))
		{
			final PowerBiScan.Options options = this.options(inputs);
			final JsonNode scan = this.scan(pContext, options.workspaces);
			final PowerBiScan.Result result = PowerBiScan.scan(scan, options);
			for (final String note : result.notes) if (pContext != null) pContext.getLog().info("[connectors] powerbi " + pOperation + ": " + note);
			final ObjectNode out = "catalog".equals(pOperation) ? result.catalog : result.lineage;
			return out.get("count").asInt() == 0 ? null : out;
		}
		throw new ConnectorException("Power BI: unknown operation '" + pOperation + "'");
	}

	PowerBiScan.Options options(final Map<String, String> pInputs) throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		final PowerBiScan.Options options = new PowerBiScan.Options();
		options.source = AbstractRestConnector.require(pInputs, "source");
		options.workspaces = PowerBiConnector.csv(pInputs.get("schemas"));
		options.kinds = PowerBiConnector.kinds(pInputs.get("types"));
		options.datasourceMap = PowerBiScan.Options.parseDatasourceMap(cfg.getProperty("datasource.map", null));
		options.externalAssets = Boolean.parseBoolean(cfg.getProperty("external.assets", "true"));
		options.includeHidden = Boolean.parseBoolean(cfg.getProperty("include.hidden", "false"));
		options.includePersonal = Boolean.parseBoolean(cfg.getProperty("include.personal", "false"));
		options.portalUrl = cfg.getProperty("portal.url", "https://app.powerbi.com");
		return options;
	}

	/** Comma-separated names → lower-case set (empty for null, blank or '*'). */
	static Set<String> csv(final String pValue)
	{
		final Set<String> set = new LinkedHashSet<String>();
		if (pValue == null) return set;
		for (final String part : pValue.split(","))
		{
			final String v = part.trim().toLowerCase(Locale.ENGLISH);
			if (!v.isEmpty() && !"*".equals(v)) set.add(v);
		}
		return set;
	}

	/** The Power BI kinds named in a types filter; null (= every kind) when the filter names none of them (a database-style TABLE,VIEW). */
	static Set<String> kinds(final String pTypes)
	{
		if (pTypes == null || pTypes.trim().isEmpty()) return null;
		final Set<String> kinds = new LinkedHashSet<String>();
		boolean specific = false;
		for (final String part : pTypes.toUpperCase(Locale.ENGLISH).split(","))
		{
			final String k = part.trim().replace(' ', '_');
			if ("MODEL".equals(k) || "DATASET".equals(k) || "SEMANTIC_MODEL".equals(k))
			{
				kinds.add(PowerBiScan.KIND_MODEL);
				specific = true;
			}
			else if (PowerBiConnector.KINDS.contains(k))
			{
				kinds.add(k);
				specific = !specific ? !PowerBiScan.KIND_TABLE.equals(k) : true;
			}
		}
		if (!specific) return null;
		return kinds;
	}

	// ------------------------------------------------------------------ scanning

	/** The Scanner API document of the selected workspaces (admin), or its equivalent built from the standard APIs. */
	JsonNode scan(final ConnectorContext pContext, final Set<String> pWorkspaces) throws ConnectorException
	{
		final String mode = this.getConfig().getProperty("scanner", "auto").trim().toLowerCase(Locale.ENGLISH);
		if (!"standard".equals(mode))
		{
			try
			{
				return this.adminScan(pContext, pWorkspaces);
			}
			catch (final ConnectorException ex)
			{
				if (!"auto".equals(mode) || ex.getStatus() != 401 && ex.getStatus() != 403) throw ex;
				if (pContext != null) pContext.getLog().info("[connectors] powerbi: admin API refused (HTTP " + ex.getStatus() + "), standard scan of the member workspaces");
			}
		}
		return this.standardScan(pContext, pWorkspaces);
	}

	JsonNode adminScan(final ConnectorContext pContext, final Set<String> pWorkspaces) throws ConnectorException
	{
		final int maximum = PowerBiConnector.bounded(this.getConfig().getProperty("max.workspaces", "500"), 1, 100000);
		final boolean personal = Boolean.parseBoolean(this.getConfig().getProperty("include.personal", "false"));
		final List<String> ids = new ArrayList<String>();
		int skip = 0;
		while (ids.size() < maximum)
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("$top", "5000");
			query.put("$skip", String.valueOf(skip));
			query.put("$filter", "state eq 'Active'" + (personal ? "" : " and type ne 'PersonalGroup'"));
			final JsonNode page = this.body(this.get("/admin/groups", query), "admin/groups");
			final JsonNode value = page.path("value");
			for (final JsonNode ws : value)
			{
				if (PowerBiConnector.selected(ws, pWorkspaces) && ids.size() < maximum) ids.add(ws.path("id").asText());
			}
			if (value.size() < 5000) break;
			skip += 5000;
		}
		final ObjectNode merged = Json.object();
		final ArrayNode workspaces = merged.putArray("workspaces");
		final ArrayNode instances = merged.putArray("datasourceInstances");
		final Map<String, String> flags = new LinkedHashMap<String, String>();
		flags.put("lineage", "true");
		flags.put("datasourceDetails", "true");
		flags.put("datasetSchema", "true");
		flags.put("datasetExpressions", "true");
		flags.put("getArtifactUsers", "false");
		final long deadline = System.currentTimeMillis() + 1000L * PowerBiConnector.bounded(this.getConfig().getProperty("scan.timeout", "180"), 5, 3600);
		for (int from = 0; from < ids.size(); from += 100)
		{
			final ObjectNode request = Json.object();
			final ArrayNode list = request.putArray("workspaces");
			for (final String id : ids.subList(from, Math.min(ids.size(), from + 100))) list.add(id);
			final JsonNode started = this.body(this.send("POST", "/admin/workspaces/getInfo", flags, request), "admin/workspaces/getInfo");
			final String scanId = started.path("id").asText("");
			if (scanId.isEmpty()) throw new ConnectorException("Power BI: getInfo returned no scan id: " + Json.write(started));
			int attempt = 0;
			while (true)
			{
				final JsonNode status = this.body(this.get("/admin/workspaces/scanStatus/" + AbstractRestConnector.segment(scanId), null), "admin/workspaces/scanStatus");
				final String state = status.path("status").asText("");
				if ("Succeeded".equalsIgnoreCase(state)) break;
				if ("Failed".equalsIgnoreCase(state) || "Cancelled".equalsIgnoreCase(state)) throw new ConnectorException("Power BI: scan " + scanId + " " + state + ": " + status.path("error").toString());
				if (System.currentTimeMillis() > deadline) throw new ConnectorException("Power BI: scan " + scanId + " still " + state + " after scan.timeout seconds");
				PowerBiConnector.pause(attempt++ < 10 ? 1000L : 3000L);
			}
			final JsonNode result = this.body(this.get("/admin/workspaces/scanResult/" + AbstractRestConnector.segment(scanId), null), "admin/workspaces/scanResult");
			for (final JsonNode ws : result.path("workspaces")) workspaces.add(ws);
			for (final JsonNode instance : result.path("datasourceInstances")) instances.add(instance);
		}
		if (Boolean.parseBoolean(this.getConfig().getProperty("dataflow.definitions", "true")))
		{
			for (final JsonNode ws : workspaces)
			{
				for (final JsonNode dataflow : ws.path("dataflows"))
				{
					final String id = dataflow.path("objectId").asText("");
					if (id.isEmpty()) continue;
					final HttpJsonClient.Response export = this.get("/admin/dataflows/" + AbstractRestConnector.segment(id) + "/export", null);
					if (export.isSuccess() && export.getBody() != null) ((ObjectNode) dataflow).set("definition", export.getBody());
					else if (pContext != null) pContext.getLog().info("[connectors] powerbi: dataflow " + dataflow.path("name").asText("") + " definition not read (HTTP " + export.getStatus() + ")");
				}
			}
		}
		return merged;
	}

	/** What the standard (non-admin) API shows: the objects of the workspaces the principal belongs to, no schema. */
	JsonNode standardScan(final ConnectorContext pContext, final Set<String> pWorkspaces) throws ConnectorException
	{
		final ObjectNode merged = Json.object();
		final ArrayNode workspaces = merged.putArray("workspaces");
		merged.putArray("datasourceInstances");
		final Map<String, String> top = new LinkedHashMap<String, String>();
		top.put("$top", "5000");
		final int maximum = PowerBiConnector.bounded(this.getConfig().getProperty("max.workspaces", "500"), 1, 100000);
		for (final JsonNode group : this.body(this.get("/groups", top), "groups").path("value"))
		{
			if (!PowerBiConnector.selected(group, pWorkspaces) || workspaces.size() >= maximum) continue;
			final String id = group.path("id").asText("");
			final ObjectNode ws = workspaces.addObject();
			ws.put("id", id);
			ws.put("name", group.path("name").asText(""));
			ws.put("type", "Workspace");
			ws.put("state", "Active");
			final String prefix = "/groups/" + AbstractRestConnector.segment(id);
			ws.set("reports", this.valueOrEmpty(pContext, prefix + "/reports"));
			final ArrayNode datasets = ws.putArray("datasets");
			for (final JsonNode dataset : this.valueOrEmpty(pContext, prefix + "/datasets"))
			{
				final ObjectNode model = datasets.addObject();
				model.setAll((ObjectNode) dataset);
				model.putArray("tables");
			}
			final ArrayNode dashboards = ws.putArray("dashboards");
			for (final JsonNode dashboard : this.valueOrEmpty(pContext, prefix + "/dashboards"))
			{
				final ObjectNode d = dashboards.addObject();
				d.setAll((ObjectNode) dashboard);
				d.set("tiles", this.valueOrEmpty(pContext, prefix + "/dashboards/" + AbstractRestConnector.segment(dashboard.path("id").asText("")) + "/tiles"));
			}
			final ArrayNode dataflows = ws.putArray("dataflows");
			for (final JsonNode dataflow : this.valueOrEmpty(pContext, prefix + "/dataflows"))
			{
				final ObjectNode d = dataflows.addObject();
				d.setAll((ObjectNode) dataflow);
				if (Boolean.parseBoolean(this.getConfig().getProperty("dataflow.definitions", "true")))
				{
					final HttpJsonClient.Response definition = this.get(prefix + "/dataflows/" + AbstractRestConnector.segment(dataflow.path("objectId").asText("")), null);
					if (definition.isSuccess() && definition.getBody() != null) d.set("definition", definition.getBody());
				}
			}
		}
		return merged;
	}

	private ArrayNode valueOrEmpty(final ConnectorContext pContext, final String pPath) throws ConnectorException
	{
		final HttpJsonClient.Response response = this.get(pPath, null);
		if (response.isSuccess() && response.getBody() != null && response.getBody().path("value").isArray()) return (ArrayNode) response.getBody().path("value");
		if (pContext != null) pContext.getLog().info("[connectors] powerbi: GET " + pPath + " -> HTTP " + response.getStatus() + ", skipped");
		return Json.mapper().createArrayNode();
	}

	static boolean selected(final JsonNode pWorkspace, final Set<String> pFilter)
	{
		if (pFilter == null || pFilter.isEmpty()) return true;
		return pFilter.contains(pWorkspace.path("name").asText("").toLowerCase(Locale.ENGLISH)) || pFilter.contains(pWorkspace.path("id").asText("").toLowerCase(Locale.ENGLISH));
	}

	// ------------------------------------------------------------------ usage, refreshes, workspaces, query

	JsonNode usage(final ConnectorContext pContext, final Map<String, String> pInputs) throws ConnectorException
	{
		final PowerBiScan.Options options = this.options(pInputs);
		final int days = PowerBiConnector.bounded(pInputs.get("days") == null ? "30" : pInputs.get("days"), 1, 30);
		final List<JsonNode> events = new ArrayList<JsonNode>();
		final java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
		for (int d = days - 1; d >= 0; d--)
		{
			final java.time.LocalDate day = today.minusDays(d);
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("startDateTime", "'" + day + "T00:00:00.000Z'");
			query.put("endDateTime", "'" + day + "T23:59:59.999Z'");
			HttpJsonClient.Response response = this.get("/admin/activityevents", query);
			while (true)
			{
				final JsonNode page = this.body(response, "admin/activityevents");
				for (final JsonNode event : page.path("activityEventEntities")) events.add(event);
				final String next = page.path("continuationUri").asText("");
				if (page.path("lastResultSet").asBoolean(true) || next.isEmpty()) break;
				response = this.getAbsolute(next, null);
			}
		}
		if (pContext != null) pContext.getLog().info("[connectors] powerbi usage: " + events.size() + " activity events over " + days + " day(s)");
		return PowerBiScan.usage(events, options);
	}

	JsonNode refreshes(final ConnectorContext pContext, final Map<String, String> pInputs) throws ConnectorException
	{
		final PowerBiScan.Options options = this.options(pInputs);
		final JsonNode scan = this.scan(pContext, options.workspaces);
		final Map<String, String> top = new LinkedHashMap<String, String>();
		top.put("$top", String.valueOf(PowerBiConnector.bounded(pInputs.get("top") == null ? "10" : pInputs.get("top"), 1, 500)));
		final String scannedAt = com.on.ebx.connect.core.CatalogOperations.timestamp();
		final ObjectNode out = Json.object();
		out.put("source", options.source);
		out.put("scannedAt", scannedAt);
		final ArrayNode jobs = out.putArray("jobs");
		for (final JsonNode ws : scan.path("workspaces"))
		{
			if (!PowerBiScan.selected(ws, options)) continue;
			final String wsName = ws.path("name").asText("");
			final String prefix = "/groups/" + AbstractRestConnector.segment(ws.path("id").asText(""));
			for (final JsonNode model : ws.path("datasets"))
			{
				final HttpJsonClient.Response history = this.get(prefix + "/datasets/" + AbstractRestConnector.segment(model.path("id").asText("")) + "/refreshes", top);
				if (!history.isSuccess() || history.getBody() == null)
				{
					if (pContext != null) pContext.getLog().info("[connectors] powerbi refreshes: " + wsName + "/" + model.path("name").asText("") + " -> HTTP " + history.getStatus() + ", skipped");
					continue;
				}
				final ObjectNode job = PowerBiScan.job(options, wsName, "models", model.path("name").asText(""), history.getBody().path("value"), scannedAt);
				if (job.get("runCount").asInt() > 0) jobs.add(job);
			}
			for (final JsonNode dataflow : ws.path("dataflows"))
			{
				final HttpJsonClient.Response history = this.get(prefix + "/dataflows/" + AbstractRestConnector.segment(dataflow.path("objectId").asText("")) + "/transactions", null);
				if (!history.isSuccess() || history.getBody() == null) continue;
				final ObjectNode job = PowerBiScan.job(options, wsName, "dataflows", dataflow.path("name").asText(""), history.getBody().path("value"), scannedAt);
				if (job.get("runCount").asInt() > 0) jobs.add(job);
			}
		}
		out.put("count", jobs.size());
		return jobs.size() == 0 ? null : out;
	}

	JsonNode workspaces(final String pFilter) throws ConnectorException
	{
		final Map<String, String> top = new LinkedHashMap<String, String>();
		top.put("$top", "5000");
		final ObjectNode out = Json.object();
		final ArrayNode items = out.putArray("items");
		final String needle = pFilter == null ? "" : pFilter.trim().toLowerCase(Locale.ENGLISH);
		for (final JsonNode group : this.body(this.get("/groups", top), "groups").path("value"))
		{
			if (!needle.isEmpty() && !group.path("name").asText("").toLowerCase(Locale.ENGLISH).contains(needle)) continue;
			final ObjectNode item = items.addObject();
			item.put("id", group.path("id").asText(""));
			item.put("name", group.path("name").asText(""));
			item.put("type", group.path("type").asText(""));
			item.put("isOnDedicatedCapacity", group.path("isOnDedicatedCapacity").asBoolean(false));
		}
		out.put("count", items.size());
		return out;
	}

	/** {@code POST datasets/{id}/executeQueries}: rows with the {@code Table[Column]} prefixes stripped. */
	JsonNode query(final ConnectorContext pContext, final Map<String, String> pInputs) throws ConnectorException
	{
		final String dataset = AbstractRestConnector.require(pInputs, "dataset");
		final String dax = AbstractRestConnector.require(pInputs, "dax");
		String path;
		if (dataset.matches("[0-9a-fA-F-]{36}"))
		{
			path = "/datasets/" + dataset;
		}
		else
		{
			final int slash = dataset.indexOf('/');
			if (slash <= 0) throw new ConnectorException("Power BI query: input dataset must be a dataset id or workspace/name");
			final String wsName = dataset.substring(0, slash).trim().toLowerCase(Locale.ENGLISH);
			final String name = dataset.substring(slash + 1).trim();
			final Map<String, String> top = new LinkedHashMap<String, String>();
			top.put("$top", "5000");
			String wsId = null;
			for (final JsonNode group : this.body(this.get("/groups", top), "groups").path("value"))
			{
				if (wsName.equals(group.path("name").asText("").toLowerCase(Locale.ENGLISH)) || wsName.equals(group.path("id").asText("").toLowerCase(Locale.ENGLISH))) wsId = group.path("id").asText();
			}
			if (wsId == null) throw new ConnectorException("Power BI query: workspace '" + dataset.substring(0, slash) + "' not found (or the service principal is not a member)");
			String dsId = null;
			for (final JsonNode ds : this.body(this.get("/groups/" + AbstractRestConnector.segment(wsId) + "/datasets", null), "datasets").path("value"))
			{
				if (name.equalsIgnoreCase(ds.path("name").asText(""))) dsId = ds.path("id").asText();
			}
			if (dsId == null) throw new ConnectorException("Power BI query: semantic model '" + name + "' not found in workspace " + dataset.substring(0, slash));
			path = "/groups/" + AbstractRestConnector.segment(wsId) + "/datasets/" + AbstractRestConnector.segment(dsId);
		}
		final ObjectNode request = Json.object();
		request.putArray("queries").addObject().put("query", dax);
		request.putObject("serializerSettings").put("includeNulls", true);
		final JsonNode reply = this.body(this.send("POST", path + "/executeQueries", null, request), "executeQueries");
		final ObjectNode out = Json.object();
		final ArrayNode columns = out.putArray("columns");
		final ArrayNode rows = out.putArray("rows");
		final int maximum = PowerBiConnector.bounded(pInputs.get("max") == null ? this.getConfig().getProperty("max.rows", "1000") : pInputs.get("max"), 1, 100000);
		final JsonNode table = reply.path("results").path(0).path("tables").path(0);
		final JsonNode error = reply.path("error");
		if (!error.isMissingNode() && table.isMissingNode()) throw new ConnectorException("Power BI query failed: " + error.toString());
		final Set<String> names = new LinkedHashSet<String>();
		for (final JsonNode row : table.path("rows"))
		{
			if (rows.size() >= maximum) break;
			final ObjectNode flat = rows.addObject();
			final java.util.Iterator<Map.Entry<String, JsonNode>> fields = row.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				final String name = PowerBiConnector.columnName(field.getKey());
				names.add(name);
				flat.set(name, field.getValue());
			}
		}
		for (final String name : names) columns.add(name);
		out.put("count", rows.size());
		return out;
	}

	/** {@code Orders[Order id]} → {@code Order id}; {@code [Total revenue]} → {@code Total revenue}. */
	static String columnName(final String pKey)
	{
		final int open = pKey.indexOf('[');
		final int close = pKey.lastIndexOf(']');
		return open >= 0 && close > open ? pKey.substring(open + 1, close) : pKey;
	}

	// ------------------------------------------------------------------ helpers

	private JsonNode body(final HttpJsonClient.Response pResponse, final String pWhat) throws ConnectorException
	{
		if (pResponse.isSuccess())
		{
			return pResponse.getBody() == null ? Json.object() : pResponse.getBody();
		}
		String detail = pResponse.getRawBody();
		if (detail != null && detail.length() > 300) detail = detail.substring(0, 300) + "...";
		throw new ConnectorException(this.getConfig().getId() + ": " + pWhat + " -> HTTP " + pResponse.getStatus() + (detail == null || detail.isEmpty() ? "" : " " + detail),
			pResponse.getStatus(), null);
	}

	private static int bounded(final String pValue, final int pMinimum, final int pMaximum)
	{
		try { return Math.max(pMinimum, Math.min(pMaximum, Integer.parseInt(pValue.trim()))); }
		catch (final RuntimeException ex) { return pMaximum; }
	}

	private static void pause(final long pMillis)
	{
		try { Thread.sleep(pMillis); }
		catch (final InterruptedException ex) { Thread.currentThread().interrupt(); }
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		if ("catalog".equals(pOperation))
		{
			for (final String[] f : new String[][] { { "tables[0].assetKey", "Asset key (source:qualifiedName)", "string" }, { "tables[0].qualifiedName", "Qualified name", "string" },
				{ "tables[0].schema", "Workspace (or workspace/models/model)", "string" }, { "tables[0].name", "Name", "string" }, { "tables[0].kind", "Kind (REPORT, DASHBOARD, SEMANTIC_MODEL, DATAFLOW, TABLE)", "string" },
				{ "tables[0].url", "Portal URL", "string" }, { "tables[0].remarks", "Description", "string" }, { "tables[0].scannedAt", "Scanned at", "datetime" },
				{ "tables[0].columns[0].columnKey", "Column key", "string" }, { "tables[0].columns[0].name", "Column / measure name", "string" }, { "tables[0].columns[0].dataType", "Normalised type", "string" },
				{ "tables[0].columns[0].nativeType", "Native type (MEASURE, CALCULATED ...)", "string" }, { "tables[0].columns[0].expression", "DAX expression", "string" } })
				fields.add(FieldInfo.of(f[0], f[1], f[2]));
		}
		else if ("lineage".equals(pOperation))
		{
			for (final String[] f : new String[][] { { "edges[0].edgeKey", "Edge key", "string" }, { "edges[0].fromAssetKey", "From asset", "string" }, { "edges[0].fromColumnKey", "From column", "string" },
				{ "edges[0].toAssetKey", "To asset", "string" }, { "edges[0].toColumnKey", "To column", "string" }, { "edges[0].transformationType", "Transformation type", "string" },
				{ "edges[0].transformation", "Power Query M or DAX", "string" }, { "edges[0].confidence", "Confidence (0-100)", "integer" }, { "edges[0].seenAt", "Seen at", "datetime" } })
				fields.add(FieldInfo.of(f[0], f[1], f[2]));
		}
		else if ("usage".equals(pOperation))
		{
			fields.add(FieldInfo.of("items[0].assetKey", "Asset key", "string"));
			fields.add(FieldInfo.of("items[0].queryCount30d", "Views over the period", "integer"));
		}
		else if ("refreshes".equals(pOperation))
		{
			for (final String[] f : new String[][] { { "jobs[0].jobKey", "Job key", "string" }, { "jobs[0].namespace", "Namespace (powerbi://workspace)", "string" }, { "jobs[0].name", "Job name", "string" },
				{ "jobs[0].orchestrator", "Orchestrator", "string" }, { "jobs[0].assetKey", "Refreshed asset", "string" }, { "jobs[0].lastRunAt", "Last run", "datetime" }, { "jobs[0].lastRunStatus", "Last run status", "string" },
				{ "jobs[0].runs[0].externalRunId", "Run id", "string" }, { "jobs[0].runs[0].startedAt", "Started", "datetime" }, { "jobs[0].runs[0].endedAt", "Ended", "datetime" },
				{ "jobs[0].runs[0].status", "Status", "string" }, { "jobs[0].runs[0].message", "Message", "string" } })
				fields.add(FieldInfo.of(f[0], f[1], f[2]));
		}
		else if ("workspaces".equals(pOperation))
		{
			fields.add(FieldInfo.of("items[0].id", "Workspace id", "string"));
			fields.add(FieldInfo.of("items[0].name", "Workspace name", "string"));
		}
		return fields;
	}
}
