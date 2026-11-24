package com.on.ebx.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Audit F3-100 (1.4.2): the MCP client of on-ebx-agent.jar tells "the server answered something over a read bound of the JSON reader" and
 * "the answer is not JSON" from "the server is unreachable". Before, a {@code StreamConstraintsException} (a Jackson IOException) was reported
 * as {@code MCP server unreachable at <url>}, and a person went looking at the network.
 */
public final class McpClientReadLimitTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private McpClientReadLimitTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String[] tools = new String[1];
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				final JsonNode message = MAPPER.readTree(read(pExchange.getRequestBody()));
				final String method = message.path("method").asText();
				final int id = message.path("id").asInt();
				final String body;
				if ("initialize".equals(method))
				{
					body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"serverInfo\":{\"name\":\"fake\"}}}";
				}
				else if (method.startsWith("notifications/"))
				{
					body = "";
				}
				else
				{
					body = tools[0].replace("@ID@", String.valueOf(id));
				}
				final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				pExchange.getResponseHeaders().add("Content-Type", "application/json");
				pExchange.sendResponseHeaders(body.isEmpty() ? 202 : 200, body.isEmpty() ? -1 : bytes.length);
				if (!body.isEmpty())
				{
					final OutputStream out = pExchange.getResponseBody();
					out.write(bytes);
					out.close();
				}
				pExchange.close();
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

			// nested deeper than the reader accepts (1000 levels): the server answered
			final StringBuilder deep = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":@ID@,\"result\":{\"tools\":");
			for (int i = 0; i < 1100; i++) deep.append('[');
			for (int i = 0; i < 1100; i++) deep.append(']');
			tools[0] = deep.append("}}").toString();
			McpException over = null;
			try
			{
				new McpClient(url, null, 2000, 5000).listTools();
				throw new AssertionError("an answer over a read bound must throw");
			}
			catch (final McpException ex)
			{
				over = ex;
			}
			check(over.getKind() == McpException.Kind.RESPONSE_OVER_LIMIT, "kind of a read-bound refusal: " + over.getKind() + " / " + over.getMessage());
			check(!over.getMessage().contains("unreachable at") && over.getMessage().contains("read bound") && over.getMessage().contains(url), "message says the server answered: " + over.getMessage());
			check(over.getCause() != null && over.getCause().getClass().getName().endsWith("StreamConstraintsException"), "the Jackson cause is kept: " + over.getCause());

			// not JSON at all
			tools[0] = "<html>proxy error</html>";
			try
			{
				new McpClient(url, null, 2000, 5000).listTools();
				throw new AssertionError("an unreadable answer must throw");
			}
			catch (final McpException ex)
			{
				check(ex.getKind() == McpException.Kind.UNREADABLE_ANSWER && !ex.getMessage().contains("unreachable at"), "an HTML answer is unreadable, not unreachable: " + ex.getKind() + " / " + ex.getMessage());
			}
		}
		finally
		{
			server.stop(0);
		}

		// a server that is not there stays "unreachable", with the other kinds untouched
		try
		{
			new McpClient("http://127.0.0.1:1/mcp", null, 500, 500).listTools();
			throw new AssertionError("an unreachable server must throw");
		}
		catch (final McpException ex)
		{
			check(ex.getKind() == McpException.Kind.UNREACHABLE && ex.getMessage().contains("MCP server unreachable at") && ex.getCode() == 0, "unreachable stays unreachable: " + ex.getKind() + " / " + ex.getMessage());
		}
		check(new McpException(401, "HTTP 401").getKind() == McpException.Kind.SERVER_ERROR, "an HTTP or JSON-RPC error is a server error");
		System.out.println("McpClientReadLimitTest OK");
	}

	private static String read(final InputStream pStream) throws IOException
	{
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		final byte[] buffer = new byte[4096];
		for (int n = pStream.read(buffer); n >= 0; n = pStream.read(buffer)) out.write(buffer, 0, n);
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
