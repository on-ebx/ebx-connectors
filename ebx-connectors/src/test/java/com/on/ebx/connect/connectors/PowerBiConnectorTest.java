package com.on.ebx.connect.connectors;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.PowerBiScanTest;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * The HTTP side of the Power BI connector against a fake tenant: Entra token, admin Scanner API
 * (getInfo with the four detail flags, scanStatus polling, scanResult, dataflow export), activity
 * events, refresh history, DAX query, and the fallback on the standard APIs when the admin API
 * answers 403. Same recorded scan result as {@link PowerBiScanTest}.
 */
public final class PowerBiConnectorTest
{
	private PowerBiConnectorTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String previousAllowList = System.getProperty(Egress.PROPERTY);
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final Tenant tenant = new Tenant(new String(Files.readAllBytes(PowerBiScanTest.fixture("powerbi/scanResult.json").toPath()), StandardCharsets.UTF_8));
		server.createContext("/", tenant);
		server.start();
		try
		{
			final int port = server.getAddress().getPort();
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + port);
			final ConnectorConfig config = new ConnectorConfig();
			config.setId("PowerBI");
			config.setType("powerbi");
			config.setBaseUrl("http://127.0.0.1:" + port + "/v1.0/myorg");
			config.setAuthType(ConnectorConfig.AuthType.OAUTH2);
			config.setApiKey("s3cret");
			config.setConnectTimeoutMs(5000);
			config.setReadTimeoutMs(10000);
			config.setProperty("tenantId", "tenant-1");
			config.setProperty("clientId", "app-1");
			config.setProperty("oauth.tokenUrl", "http://127.0.0.1:" + port + "/tenant-1/oauth2/v2.0/token");
			config.setProperty("datasource.map", "xy12345.snowflakecomputing.com=DWH:upper");
			final PowerBiConnector connector = new PowerBiConnector();
			connector.configure(config);
			check(PowerBiConnector.SCOPE.equals(config.getProperty("oauth.scope", null)) && "app-1".equals(config.getProperty("oauth.clientId", null)), "OAuth scope and client id derived from the properties");
			check(connector.validateConfiguration(config).isValid(), "configuration valid: " + connector.validateConfiguration(config).getIssues());

			final ConnectionTestResult probe = connector.testConnection(null);
			check(probe.isSuccessful() && probe.getMessage().contains("admin Scanner API available"), "connection test reports the admin API: " + probe.getMessage());
			check("client_credentials".equals(tenant.tokenGrant) && tenant.tokenScope.contains("analysis.windows.net"), "token requested with client credentials on the Power BI scope");
			check("Bearer tok-1".equals(tenant.lastAuthorization), "API calls carry the bearer token");

			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			inputs.put("source", "POWERBI");
			inputs.put("schemas", "Finance");
			inputs.put("types", "TABLE,VIEW");
			final JsonNode catalog = connector.execute(null, "catalog", inputs, null);
			check(catalog != null && catalog.get("count").asInt() == 9, "admin scan of the Finance workspace: 9 assets (got " + (catalog == null ? null : catalog.get("count")) + ")");
			check(tenant.getInfoQuery.contains("lineage=true") && tenant.getInfoQuery.contains("datasetSchema=true") && tenant.getInfoQuery.contains("datasetExpressions=true")
				&& tenant.getInfoQuery.contains("datasourceDetails=true"), "getInfo asked for lineage, schema, expressions and datasource details: " + tenant.getInfoQuery);
			check(tenant.getInfoWorkspaces == 1, "only the selected workspace is scanned");
			check(tenant.scanStatusCalls == 2, "scanStatus polled until Succeeded");
			check(tenant.exportCalls == 1, "dataflow definition exported once");
			check(tenant.adminGroupsQuery.contains("PersonalGroup"), "personal workspaces excluded server-side");

			final JsonNode lineage = connector.execute(null, "lineage", inputs, null);
			check(lineage != null && lineage.get("count").asInt() == 23, "lineage of the Finance workspace: 23 edges (got " + (lineage == null ? null : lineage.get("count")) + ")");

			inputs.put("days", "2");
			final JsonNode usage = connector.execute(null, "usage", inputs, null);
			check(usage != null && usage.get("count").asInt() == 1 && usage.get("items").get(0).get("queryCount30d").asInt() == 4, "usage over 2 days with a continuation page");
			check(tenant.activityCalls == 3, "2 days = 2 calls + 1 continuation (got " + tenant.activityCalls + ")");

			final JsonNode refreshes = connector.execute(null, "refreshes", inputs, null);
			check(refreshes != null && refreshes.get("count").asInt() == 1 && "FAIL".equals(refreshes.get("jobs").get(0).get("lastRunStatus").asText()), "refresh history of the model; dataflow transactions refused and skipped");

			final Map<String, String> dax = new LinkedHashMap<String, String>();
			dax.put("dataset", "Finance/Finance model");
			dax.put("dax", "EVALUATE SUMMARIZECOLUMNS(Orders[Order date], \"Revenue\", [Total revenue])");
			final JsonNode rows = connector.execute(null, "query", dax, null);
			check(rows.get("count").asInt() == 2 && "Order date".equals(rows.get("columns").get(0).asText()) && rows.get("rows").get(0).get("Revenue").asDouble() == 1200.5, "DAX query rows with bracket-free column names");
			check(tenant.lastQueryBody.contains("SUMMARIZECOLUMNS") && tenant.lastQueryPath.contains("/groups/11111111-1111-1111-1111-111111111111/datasets/dddddddd-0001"), "query resolved workspace/name to ids");

			final JsonNode workspaces = connector.execute(null, "workspaces", new LinkedHashMap<String, String>(), null);
			check(workspaces.get("count").asInt() == 1 && "Finance".equals(workspaces.get("items").get(0).get("name").asText()), "workspaces listed");

			// admin API refused: auto mode falls back on the standard APIs (no schema, no expressions)
			tenant.adminForbidden = true;
			final ConnectionTestResult limited = connector.testConnection(null);
			check(limited.isSuccessful() && limited.getMessage().contains("admin API refused (HTTP 403)"), "connection test explains the limited mode: " + limited.getMessage());
			final JsonNode standard = connector.execute(null, "catalog", inputs, null);
			check(standard != null && standard.get("count").asInt() == 6, "standard scan: model, report, dashboard, dataflow, its entity and the mashup data source (got " + (standard == null ? null : standard.get("count")) + ")");
			final JsonNode standardLineage = connector.execute(null, "lineage", inputs, null);
			check(standardLineage != null && standardLineage.get("count").asInt() == 7, "standard lineage: report, tiles and mashup links only (got " + (standardLineage == null ? null : standardLineage.get("count")) + ")");
			config.setProperty("scanner", "admin");
			connector.configure(config);
			try
			{
				connector.execute(null, "catalog", inputs, null);
				check(false, "scanner=admin must not fall back");
			}
			catch (final ConnectorException expected)
			{
				check(expected.getStatus() == 403, "admin-only mode surfaces the 403: " + expected.getMessage());
			}
		}
		finally
		{
			server.stop(0);
			if (previousAllowList == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, previousAllowList);
		}
		System.out.println("PASS: Power BI connector against a fake tenant (token, Scanner API polling, activity log, refresh history, DAX query, standard fallback)");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	/** A fake tenant: token endpoint plus the handful of Power BI routes the connector uses. */
	private static final class Tenant implements HttpHandler
	{
		final String scanResult;
		volatile boolean adminForbidden;
		String tokenGrant;
		String tokenScope = "";
		String lastAuthorization;
		String adminGroupsQuery = "";
		String getInfoQuery = "";
		int getInfoWorkspaces;
		int scanStatusCalls;
		int exportCalls;
		int activityCalls;
		String lastQueryBody = "";
		String lastQueryPath = "";

		Tenant(final String pScanResult)
		{
			this.scanResult = pScanResult;
		}

		@Override
		public void handle(final HttpExchange pExchange) throws IOException
		{
			final String path = pExchange.getRequestURI().getPath();
			final String query = pExchange.getRequestURI().getQuery() == null ? "" : pExchange.getRequestURI().getQuery();
			final String rawQuery = pExchange.getRequestURI().getRawQuery() == null ? "" : pExchange.getRequestURI().getRawQuery();
			final String body = new String(PowerBiConnectorTest.readAll(pExchange), StandardCharsets.UTF_8);
			try
			{
				if (path.endsWith("/oauth2/v2.0/token"))
				{
					final Map<String, String> form = new LinkedHashMap<String, String>();
					for (final String pair : body.split("&"))
					{
						final int eq = pair.indexOf('=');
						if (eq > 0) form.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
					}
					this.tokenGrant = form.get("grant_type");
					this.tokenScope = form.get("scope") == null ? "" : form.get("scope");
					this.reply(pExchange, 200, "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"tok-1\"}");
					return;
				}
				this.lastAuthorization = pExchange.getRequestHeaders().getFirst("Authorization");
				final String route = path.substring(path.indexOf("/v1.0/myorg") + "/v1.0/myorg".length());
				if (route.startsWith("/admin/"))
				{
					if (this.adminForbidden) { this.reply(pExchange, 403, "{\"error\":{\"code\":\"PowerBINotAuthorizedException\"}}"); return; }
					if (route.equals("/admin/groups")) { this.adminGroupsQuery = query; this.reply(pExchange, 200, "{\"value\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Finance\",\"type\":\"Workspace\",\"state\":\"Active\"},{\"id\":\"22222222-2222-2222-2222-222222222222\",\"name\":\"Marketing\",\"type\":\"Workspace\",\"state\":\"Active\"}]}"); return; }
					if (route.equals("/admin/workspaces/getInfo")) { this.getInfoQuery = rawQuery; this.getInfoWorkspaces = Json.parse(body).path("workspaces").size(); this.reply(pExchange, 202, "{\"id\":\"scan-1\",\"createdDateTime\":\"2026-09-13T10:00:00Z\",\"status\":\"NotStarted\"}"); return; }
					if (route.equals("/admin/workspaces/scanStatus/scan-1")) { this.scanStatusCalls++; this.reply(pExchange, 200, this.scanStatusCalls < 2 ? "{\"id\":\"scan-1\",\"status\":\"Running\"}" : "{\"id\":\"scan-1\",\"status\":\"Succeeded\"}"); return; }
					if (route.equals("/admin/workspaces/scanResult/scan-1")) { this.reply(pExchange, 200, PowerBiConnectorTest.without(this.scanResult, "definition")); return; }
					if (route.startsWith("/admin/dataflows/") && route.endsWith("/export")) { this.exportCalls++; this.reply(pExchange, 200, Json.write(Json.parse(this.scanResult).path("workspaces").get(0).path("dataflows").get(0).path("definition"))); return; }
					if (route.equals("/admin/activityevents"))
					{
						this.activityCalls++;
						final String event = "{\"Activity\":\"ViewReport\",\"WorkSpaceName\":\"Finance\",\"ReportName\":\"Revenue dashboard\",\"ReportId\":\"aaaaaaaa-0001-0000-0000-000000000001\"}";
						if (query.contains("startDateTime") && this.activityCalls == 1)
						{
							this.reply(pExchange, 200, "{\"activityEventEntities\":[" + event + "," + event + "],\"continuationUri\":\"http://127.0.0.1:" + pExchange.getLocalAddress().getPort()
									+ "/v1.0/myorg/admin/activityevents?continuationToken=abc\",\"continuationToken\":\"abc\",\"lastResultSet\":false}");
							return;
						}
						this.reply(pExchange, 200, "{\"activityEventEntities\":[" + event + "],\"lastResultSet\":true}");
						return;
					}
				}
				if (route.equals("/groups")) { this.reply(pExchange, 200, "{\"value\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Finance\",\"type\":\"Workspace\",\"isOnDedicatedCapacity\":true}]}"); return; }
				final String ws = "/groups/11111111-1111-1111-1111-111111111111";
				if (route.equals(ws + "/reports")) { this.reply(pExchange, 200, "{\"value\":[{\"id\":\"aaaaaaaa-0001-0000-0000-000000000001\",\"name\":\"Revenue dashboard\",\"datasetId\":\"dddddddd-0001-0000-0000-000000000001\"}]}"); return; }
				if (route.equals(ws + "/datasets")) { this.reply(pExchange, 200, "{\"value\":[{\"id\":\"dddddddd-0001-0000-0000-000000000001\",\"name\":\"Finance model\",\"configuredBy\":\"finance-bi@corp.example\"}]}"); return; }
				if (route.equals(ws + "/dashboards")) { this.reply(pExchange, 200, "{\"value\":[{\"id\":\"bbbbbbbb-0001-0000-0000-000000000001\",\"displayName\":\"Exec overview\"}]}"); return; }
				if (route.equals(ws + "/dashboards/bbbbbbbb-0001-0000-0000-000000000001/tiles")) { this.reply(pExchange, 200, "{\"value\":[{\"id\":\"t1\",\"title\":\"Net revenue YTD\",\"reportId\":\"aaaaaaaa-0001-0000-0000-000000000001\"},{\"id\":\"t2\",\"title\":\"Customers\",\"datasetId\":\"dddddddd-0001-0000-0000-000000000001\"}]}"); return; }
				if (route.equals(ws + "/dataflows")) { this.reply(pExchange, 200, "{\"value\":[{\"objectId\":\"ffffffff-0001-0000-0000-000000000001\",\"name\":\"Sales staging\"}]}"); return; }
				if (route.equals(ws + "/dataflows/ffffffff-0001-0000-0000-000000000001")) { this.reply(pExchange, 200, Json.write(Json.parse(this.scanResult).path("workspaces").get(0).path("dataflows").get(0).path("definition"))); return; }
				if (route.equals(ws + "/dataflows/ffffffff-0001-0000-0000-000000000001/transactions")) { this.reply(pExchange, 403, "{\"error\":{\"code\":\"Forbidden\"}}"); return; }
				if (route.equals(ws + "/datasets/dddddddd-0001-0000-0000-000000000001/refreshes"))
				{
					this.reply(pExchange, 200, "{\"value\":[{\"requestId\":\"r2\",\"refreshType\":\"Scheduled\",\"startTime\":\"2026-09-13T06:00:00Z\",\"endTime\":\"2026-09-13T06:01:00Z\",\"status\":\"Failed\",\"serviceExceptionJson\":\"{\\\"errorCode\\\":\\\"x\\\"}\"},"
							+ "{\"requestId\":\"r1\",\"refreshType\":\"Scheduled\",\"startTime\":\"2026-09-12T06:00:00Z\",\"endTime\":\"2026-09-12T06:03:00Z\",\"status\":\"Completed\"}]}");
					return;
				}
				if (route.equals(ws + "/datasets/dddddddd-0001-0000-0000-000000000001/executeQueries"))
				{
					this.lastQueryBody = body;
					this.lastQueryPath = route;
					this.reply(pExchange, 200, "{\"results\":[{\"tables\":[{\"rows\":[{\"Orders[Order date]\":\"2026-09-01T00:00:00\",\"[Revenue]\":1200.5},{\"Orders[Order date]\":\"2026-09-02T00:00:00\",\"[Revenue]\":80}]}]}]}");
					return;
				}
				this.reply(pExchange, 404, "{\"error\":\"no route " + route + "\"}");
			}
			finally
			{
				pExchange.close();
			}
		}

		private void reply(final HttpExchange pExchange, final int pStatus, final String pBody) throws IOException
		{
			final byte[] bytes = pBody.getBytes(StandardCharsets.UTF_8);
			pExchange.getResponseHeaders().add("Content-Type", "application/json");
			pExchange.sendResponseHeaders(pStatus, bytes.length);
			final OutputStream out = pExchange.getResponseBody();
			out.write(bytes);
			out.close();
		}
	}

	/** The scan result as the Scanner API returns it: the dataflow definition comes from a separate export call. */
	static String without(final String pJson, final String pField) throws IOException
	{
		final JsonNode root = Json.parse(pJson);
		for (final JsonNode ws : root.path("workspaces"))
		{
			for (final JsonNode dataflow : ws.path("dataflows")) ((com.fasterxml.jackson.databind.node.ObjectNode) dataflow).remove(pField);
		}
		return Json.write(root);
	}

	static byte[] readAll(final HttpExchange pExchange) throws IOException
	{
		final java.io.InputStream in = pExchange.getRequestBody();
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		final byte[] buffer = new byte[4096];
		for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) out.write(buffer, 0, n);
		return out.toByteArray();
	}

}
