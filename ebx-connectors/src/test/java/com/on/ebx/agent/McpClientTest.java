package com.on.ebx.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** The client against a fake MCP server that answers in JSON, in event streams, with a session id and with errors. */
public final class McpClientTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		final List<JsonNode> received = new ArrayList<JsonNode>();
		final List<String> sessions = new ArrayList<String>();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				if (!"Basic YWdlbnQ6c2VjcmV0".equals(pExchange.getRequestHeaders().getFirst("Authorization")))
				{
					McpClientTest.answer(pExchange, 401, "application/json", "{\"code\":401}");
					return;
				}
				final JsonNode message = McpClientTest.MAPPER.readTree(McpClientTest.read(pExchange.getRequestBody()));
				received.add(message);
				sessions.add(String.valueOf(pExchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
				final String method = message.path("method").asText();
				final int id = message.path("id").asInt();
				if ("initialize".equals(method))
				{
					pExchange.getResponseHeaders().add("Mcp-Session-Id", "s-42");
					McpClientTest.answer(pExchange, 200, "application/json",
						"{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"fake\"}}}");
				}
				else if ("notifications/initialized".equals(method))
				{
					McpClientTest.answer(pExchange, 202, "application/json", "");
				}
				else if ("tools/list".equals(method))
				{
					final boolean second = message.path("params").has("cursor");
					McpClientTest.answer(pExchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{\"name\":\""
						+ (second ? "publish" : "build") + "\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]" + (second ? "" : ",\"nextCursor\":\"p2\"") + "}}");
				}
				else if ("boom".equals(message.path("params").path("name").asText()))
				{
					McpClientTest.answer(pExchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32602,\"message\":\"Unknown tool\"}}");
				}
				else
				{
					// an event stream: a notification of the server first, then the answer on two data lines
					McpClientTest.answer(pExchange, 200, "text/event-stream",
						"event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":"
							+ id + ",\ndata: \"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"{\\\"built\\\":false}\"}]}}\n\n");
				}
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
			final McpClient client = new McpClient(url, McpClient.basicHeaders("agent", "secret"), 2000, 5000);

			final List<McpClient.Tool> tools = client.listTools();
			McpClientTest.check("handshake before the first call", "initialize".equals(received.get(0).path("method").asText())
				&& "notifications/initialized".equals(received.get(1).path("method").asText()));
			McpClientTest.check("both pages of tools: " + tools.size(), tools.size() == 2 && "publish".equals(tools.get(1).name));
			McpClientTest.check("the session id is sent back", "s-42".equals(sessions.get(2)));

			final ObjectNode arguments = McpClientTest.MAPPER.createObjectNode();
			arguments.put("dry_run", true);
			final McpClient.ToolResult result = client.callTool("build", arguments);
			McpClientTest.check("arguments are passed", received.get(received.size() - 1).path("params").path("arguments").path("dry_run").asBoolean());
			McpClientTest.check("the tool's own error flag", result.error);
			McpClientTest.check("text parsed as JSON", result.json != null && !result.json.path("built").asBoolean(true));

			try
			{
				client.callTool("boom", null);
				McpClientTest.check("a JSON-RPC error must throw", false);
			}
			catch (final McpException ex)
			{
				McpClientTest.check("JSON-RPC error code: " + ex.getCode(), ex.getCode() == -32602 && ex.getMessage().contains("Unknown tool"));
			}
			try
			{
				new McpClient(url, null, 2000, 5000).listTools();
				McpClientTest.check("401 must throw", false);
			}
			catch (final McpException ex)
			{
				McpClientTest.check("HTTP status kept: " + ex.getCode(), ex.getCode() == 401);
			}
			try
			{
				new McpClient("http://127.0.0.1:1/mcp", null, 500, 500).listTools();
				McpClientTest.check("an unreachable server must throw", false);
			}
			catch (final McpException ex)
			{
				McpClientTest.check("transport failure has no code", ex.getCode() == 0);
			}
		}
		finally
		{
			server.stop(0);
		}
		McpClientTest.injectedTransport();
		System.out.println("McpClientTest OK");
	}

	/** in-jvm-mcp lot (2026-09-24): the same protocol through a Transport, no HTTP (the ebx-connectors in-JVM bridge). */
	private static void injectedTransport() throws Exception
	{
		final List<String> seen = new ArrayList<String>();
		final McpClient client = new McpClient(new McpClient.Transport()
		{
			@Override
			public String exchange(final String pMessage) throws McpException
			{
				seen.add(pMessage);
				final JsonNode message;
				try
				{
					message = McpClientTest.MAPPER.readTree(pMessage);
				}
				catch (final IOException ex)
				{
					throw new McpException("bad", ex);
				}
				final String method = message.path("method").asText();
				final int id = message.path("id").asInt();
				if ("initialize".equals(method))
				{
					return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"serverInfo\":{\"name\":\"in-jvm\"}}}";
				}
				if (method.startsWith("notifications/"))
				{
					return "";
				}
				if ("tools/call".equals(method))
				{
					return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"status\\\":\\\"OK\\\"}\"}],"
						+ "\"structuredContent\":{\"status\":\"OK\",\"count\":3},\"isError\":false}}";
				}
				return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32001,\"message\":\"An EBX Session is required\"}}";
			}

			@Override
			public String describe()
			{
				return "in-JVM test";
			}
		});
		final McpClient.ToolResult result = client.callTool("connectors_sync", McpClientTest.MAPPER.createObjectNode().put("mapping", "231"));
		McpClientTest.check("the transport carried initialize, the notification and the call: " + seen.size(), seen.size() == 3);
		McpClientTest.check("the structured answer is read", !result.error && result.json.path("count").asInt() == 3);
		try
		{
			client.listTools();
			McpClientTest.check("a JSON-RPC error through the transport must throw", false);
		}
		catch (final McpException ex)
		{
			McpClientTest.check("the error keeps its code and text: " + ex.getMessage(), ex.getCode() == -32001 && ex.getMessage().indexOf("Session is required") >= 0);
		}
	}

	private static void answer(final HttpExchange pExchange, final int pStatus, final String pType, final String pBody) throws IOException
	{
		final byte[] bytes = pBody.getBytes(StandardCharsets.UTF_8);
		pExchange.getResponseHeaders().add("Content-Type", pType);
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
