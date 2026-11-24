package com.on.ebx.connect.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PreviewRequest;
import com.on.ebx.connect.spi.PreviewResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Offline checks for the shared, read-only HTTP connector probe. */
public final class HttpConnectorInspectionTest
{
	private HttpConnectorInspectionTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String previousAllowList = System.getProperty(Egress.PROPERTY);
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final ProbeHandler handler = new ProbeHandler();
		server.createContext("/health", handler);
		final PreviewHandler previewHandler = new PreviewHandler();
		server.createContext("/preview", previewHandler);
		server.start();
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.getAddress().getPort());
			final ProbeConnector connector = new ProbeConnector();
			final ConnectorConfig config = new ConnectorConfig();
			config.setId("http-probe");
			config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
			config.setAuthType(ConnectorConfig.AuthType.BEARER);
			config.setApiKey("test-token");
			config.setProperty("inspection.path", "/health");
			connector.configure(config);

			handler.status = 204;
			final ConnectionTestResult successful = connector.testConnection(null);
			check(successful.isSupported() && successful.isSuccessful(), "204 probe did not succeed");
			check("HEAD".equals(handler.method), "Probe was not read-only HEAD");
			check("Bearer test-token".equals(handler.authorization), "Configured authentication was not applied");
			check(handler.requestBodyBytes == 0, "Probe sent a request body");

			handler.status = 401;
			final ConnectionTestResult refused = connector.testConnection(null);
			check(refused.isSupported() && !refused.isSuccessful(), "401 probe did not fail");
			check(refused.getMessage().contains("401"), "Failure omitted HTTP status");

			final PreviewConnector previewConnector = new PreviewConnector();
			previewConnector.configure(config);
			final PreviewResult missing = previewConnector.preview(null,
				new PreviewRequest("get", "get", Collections.<String, String>emptyMap(), 2));
			check(!missing.isSupported() && missing.getMessage().contains("id"),
				"Missing required preview input was not reported");
			check(previewHandler.requests == 0, "Missing-input preview contacted the server");

			final PreviewResult write = previewConnector.preview(null,
				new PreviewRequest("create", "create", Collections.<String, String>emptyMap(), 2));
			check(!write.isSupported() && write.getMessage().contains("writes data"),
				"WRITE preview was not explicitly refused");
			check(previewHandler.requests == 0, "WRITE preview contacted the server");

			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			inputs.put("scope", "active");
			final PreviewResult sample = previewConnector.preview(null,
				new PreviewRequest("search", "search", inputs, 2));
			check(sample.isSupported(), "READ/SEARCH preview was not supported");
			check(sample.getRecords().size() == 2 && sample.isTruncated(),
				"Preview did not respect its record limit");
			check(previewHandler.requests == 1 && "GET".equals(previewHandler.method),
				"Preview did not perform exactly one GET");
			check(previewHandler.requestBodyBytes == 0, "Preview sent a request body");
		}
		finally
		{
			server.stop(0);
			if (previousAllowList == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, previousAllowList);
		}
		System.out.println("PASS: shared HTTP probes and bounded read-only previews");
	}

	private static final class PreviewConnector extends AbstractRestConnector
	{
		private static final java.util.List<Operation> OPERATIONS = Arrays.asList(
			Operation.read("get", "Read one", "id"),
			Operation.search("search", "Search"),
			Operation.write("create", "Create"));

		public String getType() { return "http-preview"; }
		public String getLabel() { return "HTTP preview"; }
		public java.util.List<Operation> getOperations() { return OPERATIONS; }
		public JsonNode execute(final ConnectorContext pContext, final String pOperation,
			final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
		{
			return this.bodyOrNull(this.get("/preview", pInputs), "preview");
		}
	}

	private static final class ProbeConnector extends AbstractRestConnector
	{
		public String getType() { return "http-probe"; }
		public String getLabel() { return "HTTP probe"; }
		public java.util.List<Operation> getOperations() { return Collections.emptyList(); }
		public JsonNode execute(final ConnectorContext pContext, final String pOperation,
			final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
		{
			return null;
		}
	}

	private static final class ProbeHandler implements HttpHandler
	{
		private volatile int status;
		private volatile String method;
		private volatile String authorization;
		private volatile int requestBodyBytes;

		public void handle(final HttpExchange pExchange) throws IOException
		{
			this.method = pExchange.getRequestMethod();
			this.authorization = pExchange.getRequestHeaders().getFirst("Authorization");
			int bytes = 0;
			while (pExchange.getRequestBody().read() >= 0) bytes++;
			this.requestBodyBytes = bytes;
			pExchange.sendResponseHeaders(this.status, -1L);
			pExchange.close();
		}
	}

	private static final class PreviewHandler implements HttpHandler
	{
		private volatile int requests;
		private volatile String method;
		private volatile int requestBodyBytes;

		public void handle(final HttpExchange pExchange) throws IOException
		{
			this.requests++;
			this.method = pExchange.getRequestMethod();
			int bytes = 0;
			while (pExchange.getRequestBody().read() >= 0) bytes++;
			this.requestBodyBytes = bytes;
			final byte[] body = "[{\"id\":1},{\"id\":2},{\"id\":3}]".getBytes(StandardCharsets.UTF_8);
			pExchange.getResponseHeaders().set("Content-Type", "application/json");
			pExchange.sendResponseHeaders(200, body.length);
			pExchange.getResponseBody().write(body);
			pExchange.close();
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
