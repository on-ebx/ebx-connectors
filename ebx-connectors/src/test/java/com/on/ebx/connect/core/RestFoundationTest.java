package com.on.ebx.connect.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Deterministic offline contract checks for the shared REST/OpenAPI foundation. */
public final class RestFoundationTest
{
	private RestFoundationTest() { }
	public static void main(final String[] pArgs) throws Exception
	{
		final long[] slept = { -1L };
		final HttpRetryPolicy policy = new HttpRetryPolicy(3, 10L, 5000L,
			new HttpRetryPolicy.Sleeper() { public void sleep(final long pMilliseconds) { slept[0] = pMilliseconds; } });
		final String previous = System.getProperty(Egress.PROPERTY);
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final RetryHandler retry = new RetryHandler();
		final SchemaHandler schema = new SchemaHandler();
		final BinaryHandler binary = new BinaryHandler();
		final GenericHandler generic = new GenericHandler();
		server.createContext("/retry", retry);
		server.createContext("/schema", schema);
		server.createContext("/.well-known/openid-configuration", new OpenIdHandler());
		server.createContext("/binary", binary);
		server.createContext("/generic", generic);
		server.createContext("/generic2", generic);
		server.createContext("/upload", generic);
		server.start();
		try
		{
			final String base = "http://127.0.0.1:" + server.getAddress().getPort();
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.getAddress().getPort());
			final HttpJsonClient client = new HttpJsonClient(1000, 1000, "test", policy);
			check(client.send("GET", base + "/retry", null, null).getStatus() == 200, "GET was not retried");
			check(retry.calls == 2 && slept[0] == 3000L, "Retry-After was not honored");
			retry.calls = 0;
			check(client.send("POST", base + "/retry", null, "{}").getStatus() == 429 && retry.calls == 1,
				"Non-idempotent POST was retried");

			final RestSchemaRefresh refresh = new RestSchemaRefresh();
			final RestSchemaRefresh.Result first = refresh.refresh(client, base + "/schema", null);
			final RestSchemaRefresh.Result second = refresh.refresh(client, base + "/schema", null);
			check(first.isChanged() && !second.isChanged() && second.getStatus() == 304, "Conditional schema refresh failed");
			check("\"v1\"".equals(schema.ifNoneMatch), "ETag was not sent back");
			final java.util.Map<String, String> persisted = new java.util.LinkedHashMap<String, String>();
			final RestSchemaRefresh.StateStore stateStore = new RestSchemaRefresh.StateStore() {
				public String load(final String pKey) { return persisted.get(pKey); }
				public void save(final String pKey, final String pValue) { persisted.put(pKey, pValue); }
			};
			final long[] now = { 1000L };
			final RestSchemaRefresh.Clock clock = new RestSchemaRefresh.Clock() { public long now() { return now[0]; } };
			final RestSchemaRefresh durable = new RestSchemaRefresh(stateStore, "schema", clock);
			check(durable.refresh(client, base + "/schema", null, 60000L).wasChecked(), "Scheduled refresh did not run initially");
			final RestSchemaRefresh restarted = new RestSchemaRefresh(stateStore, "schema", clock);
			final RestSchemaRefresh.Result skipped = restarted.refresh(client, base + "/schema", null, 60000L);
			check(!skipped.wasChecked() && skipped.getSchema() != null && skipped.getNextRefreshAt() == 61000L,
				"Persisted refresh state did not survive reconstruction");
			check("next-token".equals(RestPagination.jsonNext(Json.parse("{\"paging\":{\"next\":\"next-token\"}}"), "paging.next")), "JSON token failed");
			check("https://example.test/p2".equals(RestPagination.linkNext("<../p2>; rel=\"next\"", "https://example.test/v1/p1")), "Link next failed");
			check(RestPayload.root(Json.parse("{\"data\":{\"id\":7}}"), "data").get("id").asInt() == 7, "Response root failed");
			final MultipartBody multipart = new MultipartBody("fixed").field("name", "value").file("file", "a.txt", "text/plain", "abc".getBytes("UTF-8"));
			final String encoded = new String(multipart.finish(), "UTF-8");
			check(encoded.contains("name=\"name\"") && encoded.contains("filename=\"a.txt\"") && encoded.endsWith("--fixed--\r\n"), "Multipart body failed");
			check("etag".equals(ConditionalRequest.ifMatch("etag").get("If-Match")), "If-Match failed");
			final byte[] payload = new byte[] { 0, 1, (byte) 255, 2 };
			check(client.sendBytes("POST", base + "/binary", Collections.singletonMap("Content-Type", "application/octet-stream"), payload).isSuccess(), "Binary send failed");
			check(java.util.Arrays.equals(payload, binary.body), "Binary body was altered");
			final OpenApiImporter.Draft imported = OpenApiImporter.build(Json.parse("{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\"},"
				+ "\"servers\":[{\"url\":\"https://api.example.test\"}],\"components\":{\"securitySchemes\":{\"oauth\":{\"type\":\"oauth2\","
				+ "\"flows\":{\"clientCredentials\":{\"tokenUrl\":\"https://auth.example.test/token\",\"scopes\":{}}}}}},"
				+ "\"security\":[{\"oauth\":[]}],\"paths\":{\"/things/{id}\":{\"patch\":{\"operationId\":\"patchThing\",\"x-ebx-response-root\":\"data\","
				+ "\"parameters\":[{\"name\":\"X-Tenant\",\"in\":\"header\",\"required\":true},{\"name\":\"region\",\"in\":\"cookie\"}],"
				+ "\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}"), "test", null);
			check(imported.authType == com.on.ebx.connect.spi.ConnectorConfig.AuthType.OAUTH2, "OpenAPI OAuth was not configured");
			check("client_credentials".equals(imported.properties.get("oauth.flow")), "OpenAPI OAuth flow missing");
			check("PATCH".equals(imported.properties.get("op.patchThing.method")), "OpenAPI PATCH was not imported");
			check("{X-Tenant}".equals(imported.properties.get("op.patchThing.header.X-Tenant")), "OpenAPI header parameter missing");
			check("{region}".equals(imported.properties.get("op.patchThing.cookie.region")), "OpenAPI cookie parameter missing");
			check("data".equals(imported.properties.get("op.patchThing.responseRoot")), "OpenAPI response root extension missing");
			final OpenApiImporter.Draft inferred = OpenApiImporter.build(Json.parse("{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Paged\"},"
				+ "\"paths\":{\"/items\":{\"get\":{\"responses\":{\"200\":{\"description\":\"ok\",\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\","
				+ "\"properties\":{\"data\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"links\":{\"type\":\"object\",\"properties\":{\"next\":{\"type\":\"string\",\"format\":\"uri\"}}}}}}}}}}}}}"), "paged", null);
			check("links.next".equals(inferred.properties.get("op.items.pagination.next")), "Conventional next-link pagination was not inferred");
			final OpenApiImporter.Draft oidc = OpenApiImporter.build(Json.parse("{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"OIDC\"},"
				+ "\"components\":{\"securitySchemes\":{\"oidc\":{\"type\":\"openIdConnect\",\"openIdConnectUrl\":\"https://id.example.test/.well-known/openid-configuration\"}}},"
				+ "\"security\":[{\"oidc\":[]}],\"paths\":{\"/me\":{\"get\":{\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}"), "oidc", null);
			check("https://id.example.test/.well-known/openid-configuration".equals(oidc.properties.get("oauth.discoveryUrl")), "OIDC discovery URL was not imported");
			check(oidc.authType == com.on.ebx.connect.spi.ConnectorConfig.AuthType.BEARER, "OIDC import falsely enabled backend authorization-code execution");
			final OAuth2.AuthorizationMetadata metadata = OAuth2.discover(client, base + "/.well-known/openid-configuration");
			check((base + "/authorize").equals(metadata.authorizationEndpoint) && (base + "/token").equals(metadata.tokenEndpoint), "OIDC metadata discovery failed");
			final com.on.ebx.connect.spi.ConnectorConfig authCode = new com.on.ebx.connect.spi.ConnectorConfig();
			authCode.setProperty("oauth.flow", "authorization_code");
			check(OAuth2.validate(authCode, true).contains("interactive EBX user-service"), "Authorization-code flow was not explicitly bounded");

			final HttpJsonClient.Response page1 = response(200, "{\"data\":[{\"id\":1}],\"next\":\"/p2\"}");
			final HttpJsonClient.Response page2 = response(200, "{\"data\":[{\"id\":2}]}");
			final int[] fetched = { 0 };
			final com.fasterxml.jackson.databind.node.ObjectNode pages = RestPageTraversal.collect("https://example.test/p1", "data", "next", 3, 10,
				new RestPageTraversal.Fetcher() { public HttpJsonClient.Response get(final String pUrl) { return fetched[0]++ == 0 ? page1 : page2; } });
			check(pages.get("results").size() == 2 && pages.get("pages").asInt() == 2, "Multi-page traversal failed");

			// Volume lot 7b: RestPageTraversal.stream - same two pages, delivered to an ItemSink one at a
			// time instead of accumulated into one "results" array (pure, a fake Fetcher, no HTTP call).
			final java.util.List<JsonNode> streamedPure = new java.util.ArrayList<JsonNode>();
			final int[] fetchedPure = { 0 };
			final com.fasterxml.jackson.databind.node.ObjectNode streamSummary = RestPageTraversal.stream("https://example.test/p1", "data", "next", 3, 10,
				new RestPageTraversal.ItemSink() { public boolean accept(final JsonNode pItem) { streamedPure.add(pItem); return true; } },
				new RestPageTraversal.Fetcher() { public HttpJsonClient.Response get(final String pUrl) { return fetchedPure[0]++ == 0 ? page1 : page2; } });
			check(streamedPure.size() == 2 && streamSummary.get("pages").asInt() == 2 && streamSummary.get("count").asInt() == 2,
				"stream() delivers the same items as collect(), just not accumulated: " + streamedPure);
			check(!streamSummary.has("results"), "stream()'s summary carries no results array - the caller already got every item through the sink");

			// maxRecords still cuts delivery and is reported as truncated, exactly like collect()'s own contract
			final int[] fetchedCapped = { 0 };
			final java.util.List<JsonNode> cappedPure = new java.util.ArrayList<JsonNode>();
			final com.fasterxml.jackson.databind.node.ObjectNode cappedSummary = RestPageTraversal.stream("https://example.test/p1", "data", "next", 3, 1,
				new RestPageTraversal.ItemSink() { public boolean accept(final JsonNode pItem) { cappedPure.add(pItem); return true; } },
				new RestPageTraversal.Fetcher() { public HttpJsonClient.Response get(final String pUrl) { return fetchedCapped[0]++ == 0 ? page1 : page2; } });
			check(cappedPure.size() == 1 && cappedSummary.path("truncated").asBoolean(false), "maxRecords=1 cuts delivery and is reported truncated");

			// a sink-initiated stop after the first page never triggers a fetch of the second page, and
			// is never reported as truncation - the caller already knows why it stopped.
			final int[] fetchedStopped = { 0 };
			final com.fasterxml.jackson.databind.node.ObjectNode stoppedSummary = RestPageTraversal.stream("https://example.test/p1", "data", "next", 3, 10,
				new RestPageTraversal.ItemSink() { public boolean accept(final JsonNode pItem) { return false; } },
				new RestPageTraversal.Fetcher() { public HttpJsonClient.Response get(final String pUrl) { fetchedStopped[0]++; return fetchedStopped[0] == 1 ? page1 : page2; } });
			check(fetchedStopped[0] == 1, "only the first page was ever fetched: " + fetchedStopped[0]);
			check(!stoppedSummary.path("truncated").asBoolean(false), "a sink-initiated stop is not the same signal as maxRecords/maxPages truncation");

			final com.on.ebx.connect.spi.ConnectorConfig config = new com.on.ebx.connect.spi.ConnectorConfig();
			config.setId("generic-test"); config.setBaseUrl(base); config.setAuthType(com.on.ebx.connect.spi.ConnectorConfig.AuthType.NONE);
			config.setProperty("op.search.method", "GET"); config.setProperty("op.search.path", "/generic");
			config.setProperty("op.search.header.X-Tenant", "{tenant}"); config.setProperty("op.search.cookie.region", "{region}");
			config.setProperty("op.search.responseRoot", "data"); config.setProperty("op.search.pagination.next", "next");
			config.setProperty("op.upload.method", "POST"); config.setProperty("op.upload.path", "/upload"); config.setProperty("op.upload.contentType", "multipart/form-data");
			final com.on.ebx.connect.connectors.GenericRestConnector connector = new com.on.ebx.connect.connectors.GenericRestConnector(); connector.configure(config);
			final java.util.Map<String, String> inputs = new java.util.LinkedHashMap<String, String>(); inputs.put("tenant", "one"); inputs.put("region", "us");
			final JsonNode collected = connector.execute(null, "search", inputs, null);
			check(collected.get("results").size() == 2 && "one".equals(generic.tenant) && "region=us".equals(generic.cookie), "Generic REST paging/header/cookie integration failed");

			// Volume lot 7b: readRows streams the same two pages to a sink one item at a time (real HTTP
			// calls, not a fake Fetcher) instead of collect()'s single accumulated "results" array -
			// cross-checked against the exact same paginated answer collect()/execute() just produced.
			final java.util.List<JsonNode> streamedItems = new java.util.ArrayList<JsonNode>();
			final com.on.ebx.connect.spi.Connector.RowStreamResult streamed = connector.readRows(null, "search", inputs, "",
				100, new com.on.ebx.connect.spi.Connector.RowSink()
				{
					@Override public boolean accept(final JsonNode pItem) { streamedItems.add(pItem); return true; }
				});
			check(streamedItems.size() == 2 && streamedItems.get(0).get("id").asInt() == 1 && streamedItems.get(1).get("id").asInt() == 2,
				"readRows streamed both pages' items, in order: " + streamedItems);
			check(streamed.count == 2 && !streamed.truncated && streamed.available == -1,
				"readRows summary: count=" + streamed.count + " truncated=" + streamed.truncated + " available=" + streamed.available);

			// a sink that stops after the first page's item never causes the second page to be fetched
			final int[] pageOneFetches = { generic.pageOneHits };
			final java.util.List<JsonNode> stoppedItems = new java.util.ArrayList<JsonNode>();
			final com.on.ebx.connect.spi.Connector.RowStreamResult stopped = connector.readRows(null, "search", inputs, "",
				100, new com.on.ebx.connect.spi.Connector.RowSink()
				{
					@Override public boolean accept(final JsonNode pItem) { stoppedItems.add(pItem); return false; }
				});
			check(stoppedItems.size() == 1, "the sink stopped after the first page's only item: " + stoppedItems);
			check(!stopped.truncated, "a sink-initiated stop is not reported as pagination truncation");
			check(generic.pageOneHits == pageOneFetches[0] + 1, "exactly one more /generic hit for the stopped read, and no /generic2 hit at all");

			connector.execute(null, "upload", Collections.<String, String>emptyMap(), Json.parse("{\"note\":\"hello\",\"file\":{\"filename\":\"a.bin\",\"base64\":\"AAH/Ag==\"}}"));
			check(generic.upload != null && generic.upload.contains("filename=\"a.bin\"") && generic.upload.contains("hello"), "Generic multipart integration failed");
		}
		finally
		{
			server.stop(0);
			if (previous == null) System.clearProperty(Egress.PROPERTY); else System.setProperty(Egress.PROPERTY, previous);
		}
		System.out.println("PASS: REST retries, pagination, conditions, multipart, roots, and schema refresh");
	}

	private static final class RetryHandler implements HttpHandler
	{
		volatile int calls;
		public void handle(final HttpExchange pExchange) throws IOException
		{
			this.calls++;
			if (this.calls == 1) { pExchange.getResponseHeaders().set("Retry-After", "3"); pExchange.sendResponseHeaders(429, -1L); }
			else pExchange.sendResponseHeaders(200, -1L);
			pExchange.close();
		}
	}
	private static final class SchemaHandler implements HttpHandler
	{
		volatile String ifNoneMatch;
		public void handle(final HttpExchange pExchange) throws IOException
		{
			this.ifNoneMatch = pExchange.getRequestHeaders().getFirst("If-None-Match");
			if ("\"v1\"".equals(this.ifNoneMatch)) pExchange.sendResponseHeaders(304, -1L);
			else
			{
				final byte[] body = "{\"type\":\"object\"}".getBytes(StandardCharsets.UTF_8);
				pExchange.getResponseHeaders().set("ETag", "\"v1\"");
				pExchange.sendResponseHeaders(200, body.length);
				pExchange.getResponseBody().write(body);
			}
			pExchange.close();
		}
	}
	private static final class BinaryHandler implements HttpHandler
	{
		volatile byte[] body;
		public void handle(final HttpExchange pExchange) throws IOException
		{
			final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			final byte[] buffer = new byte[16]; int read;
			while ((read = pExchange.getRequestBody().read(buffer)) >= 0) out.write(buffer, 0, read);
			this.body = out.toByteArray(); pExchange.sendResponseHeaders(204, -1L); pExchange.close();
		}
	}
	private static final class OpenIdHandler implements HttpHandler
	{
		public void handle(final HttpExchange pExchange) throws IOException
		{
			final String base = "http://127.0.0.1:" + pExchange.getLocalAddress().getPort();
			final byte[] body = ("{\"issuer\":\"" + base + "\",\"authorization_endpoint\":\"" + base
				+ "/authorize\",\"token_endpoint\":\"" + base + "/token\",\"jwks_uri\":\"" + base + "/jwks\"}").getBytes("UTF-8");
			pExchange.getResponseHeaders().set("Content-Type", "application/json");
			pExchange.sendResponseHeaders(200, body.length); pExchange.getResponseBody().write(body); pExchange.close();
		}
	}
	private static final class GenericHandler implements HttpHandler
	{
		volatile String tenant, cookie, upload;
		volatile int pageOneHits, pageTwoHits;
		public void handle(final HttpExchange pExchange) throws IOException
		{
			this.tenant = pExchange.getRequestHeaders().getFirst("X-Tenant"); this.cookie = pExchange.getRequestHeaders().getFirst("Cookie");
			final byte[] body;
			if ("/generic".equals(pExchange.getRequestURI().getPath())) { this.pageOneHits++; body = "{\"data\":[{\"id\":1}],\"next\":\"/generic2\"}".getBytes("UTF-8"); }
			else if ("/generic2".equals(pExchange.getRequestURI().getPath())) { this.pageTwoHits++; body = "{\"data\":[{\"id\":2}]}".getBytes("UTF-8"); }
			else
			{
				final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); final byte[] buffer = new byte[64]; int read;
				while ((read = pExchange.getRequestBody().read(buffer)) >= 0) out.write(buffer, 0, read);
				this.upload = new String(out.toByteArray(), "ISO-8859-1"); body = "{}".getBytes("UTF-8");
			}
			pExchange.getResponseHeaders().set("Content-Type", "application/json"); pExchange.sendResponseHeaders(200, body.length); pExchange.getResponseBody().write(body); pExchange.close();
		}
	}
	private static HttpJsonClient.Response response(final int pStatus, final String pBody) throws Exception
	{
		return new HttpJsonClient.Response(pStatus, pBody, Collections.<String, java.util.List<String>>emptyMap());
	}
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
