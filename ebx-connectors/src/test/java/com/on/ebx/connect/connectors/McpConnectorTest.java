package com.on.ebx.connect.connectors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** The mcp connector against a fake MCP server: Basic account, tool list, tool call, the empty list of a disabled EBX server, egress. */
public final class McpConnectorTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final String previousAllowList = System.getProperty(Egress.PROPERTY);
		final boolean[] empty = { false };
		final JsonNode[] lastArguments = { null };
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp/v1/data-model", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				// admin:admin
				if (!"Basic YWRtaW46YWRtaW4=".equals(pExchange.getRequestHeaders().getFirst("Authorization")))
				{
					pExchange.getResponseHeaders().add("WWW-Authenticate", "EBX");
					McpConnectorTest.answer(pExchange, 401, "{\"code\":401}");
					return;
				}
				final JsonNode message = Json.parse(McpConnectorTest.read(pExchange.getRequestBody()));
				final String method = message.path("method").asText();
				final int id = message.path("id").asInt();
				if ("initialize".equals(method))
				{
					McpConnectorTest.answer(pExchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"serverInfo\":{\"name\":\"data-model\"}}}");
				}
				else if ("tools/list".equals(method))
				{
					McpConnectorTest.answer(pExchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[" + (empty[0] ? ""
						: "{\"name\":\"build_data_model\",\"description\":\"b\",\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"publish_data_model\",\"description\":\"p\",\"inputSchema\":{\"type\":\"object\"}}")
						+ "]}}");
				}
				else if ("tools/call".equals(method))
				{
					lastArguments[0] = message.path("params").path("arguments");
					McpConnectorTest.answer(pExchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id
						+ ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"built\\\":true}\"}]}}");
				}
				else
				{
					McpConnectorTest.answer(pExchange, 202, "");
				}
			}
		});
		server.start();
		try
		{
			final int port = server.getAddress().getPort();
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + port);
			final McpConnector connector = new McpConnector();
			connector.configure(McpConnectorTest.config(port, "admin"));

			final ConnectionTestResult ok = connector.testConnection(null);
			McpConnectorTest.check("test names the server and counts its tools: " + ok.getMessage(), ok.isSuccessful() && ok.getMessage().contains("data-model: 2 tool"));

			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			inputs.put("name", "publish");
			final JsonNode tools = connector.execute(null, "listTools", inputs, null);
			McpConnectorTest.check("listTools filters on the name", tools.path("count").asInt() == 1 && "publish_data_model".equals(tools.path("tools").get(0).path("name").asText()));

			inputs.clear();
			inputs.put("tool", "build_data_model");
			inputs.put("arguments", "{\"data_model_name\":\"X\",\"dry_run\":true}");
			final JsonNode called = connector.execute(null, "callTool", inputs, Json.parse("{\"ignored\":1}"));
			McpConnectorTest.check("the arguments input wins over the payload", lastArguments[0].path("dry_run").asBoolean() && !lastArguments[0].has("ignored"));
			McpConnectorTest.check("the JSON answer is parsed", !called.path("isError").asBoolean() && called.path("json").path("built").asBoolean());

			inputs.remove("arguments");
			connector.execute(null, "callTool", inputs, Json.parse("{\"data_model_name\":\"Y\"}"));
			McpConnectorTest.check("without the input, the payload is the arguments", "Y".equals(lastArguments[0].path("data_model_name").asText()));

			inputs.put("arguments", "[1]");
			McpConnectorTest.check("arguments must be an object", McpConnectorTest.fails(connector, "callTool", inputs).getMessage().contains("JSON object"));

			empty[0] = true;
			final ConnectionTestResult none = connector.testConnection(null);
			McpConnectorTest.check("an empty tool list is not a success: " + none.getMessage(), !none.isSuccessful() && none.getMessage().contains("MCP servers"));
			empty[0] = false;

			final McpConnector wrong = new McpConnector();
			wrong.configure(McpConnectorTest.config(port, "nope"));
			final ConnectionTestResult refused = wrong.testConnection(null);
			McpConnectorTest.check("a refused account is explained: " + refused.getMessage(), !refused.isSuccessful() && refused.getMessage().contains("refused the account"));
			McpConnectorTest.check("the password never shows", !refused.getMessage().contains("nope"));

			System.setProperty(Egress.PROPERTY, "example.org");
			final ConnectionTestResult blocked = connector.testConnection(null);
			McpConnectorTest.check("the outbound allow-list applies: " + blocked.getMessage(), !blocked.isSuccessful() && blocked.getMessage().contains(Egress.PROPERTY));
		}
		finally
		{
			server.stop(0);
			if (previousAllowList == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, previousAllowList);
		}
		System.out.println("McpConnectorTest OK");
	}

	private static ConnectorConfig config(final int pPort, final String pPassword)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("EbxDataModel");
		config.setType("mcp");
		config.setBaseUrl("http://127.0.0.1:" + pPort + "/mcp/v1/data-model");
		config.setAuthType(ConnectorConfig.AuthType.BASIC);
		config.setUsername("admin");
		config.setPassword(pPassword);
		config.setConnectTimeoutMs(5000);
		config.setReadTimeoutMs(10000);
		return config;
	}

	private static ConnectorException fails(final McpConnector pConnector, final String pOperation, final Map<String, String> pInputs)
	{
		try
		{
			pConnector.execute(null, pOperation, pInputs, null);
		}
		catch (final ConnectorException ex)
		{
			return ex;
		}
		throw new AssertionError(pOperation + " should have failed");
	}

	private static void answer(final HttpExchange pExchange, final int pStatus, final String pBody) throws IOException
	{
		final byte[] bytes = pBody.getBytes(StandardCharsets.UTF_8);
		pExchange.getResponseHeaders().add("Content-Type", "application/json");
		pExchange.sendResponseHeaders(pStatus, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0)
		{
			pExchange.getResponseBody().write(bytes);
		}
		pExchange.close();
	}

	private static String read(final InputStream pStream) throws IOException
	{
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buffer = new byte[4096];
		for (int n = pStream.read(buffer); n >= 0; n = pStream.read(buffer))
		{
			out.write(buffer, 0, n);
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
