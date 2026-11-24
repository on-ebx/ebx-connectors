package com.on.ebx.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A client of one MCP server over Streamable HTTP (or an injected {@link Transport}), request/response only: every JSON-RPC message is a
 * POST, answered either by a JSON body or by an event stream that carries the answer (both are read).
 * No server-to-client stream is opened, which is what the EBX servers ({@code /ebx-assistant/mcp/v1/<name>})
 * and the module endpoints ({@code /<module>/rest/mcp}) expect.
 * <p>
 * Not thread-safe: one client per conversation. Pure JDK and Jackson, no EBX class and nothing of
 * {@code com.on.ebx.connect}: this package is also built on its own as {@code on-ebx-agent.jar}
 * ({@code build-agent.sh}) for the sibling modules that talk to MCP servers.
 */
public final class McpClient
{
	public static final String PROTOCOL_VERSION = "2025-06-18";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** What a tool answered. {@code error} is the tool's own verdict ({@code isError}), not a protocol failure. */
	public static final class ToolResult
	{
		public final boolean error;
		/** Every text block of the answer, one per line. */
		public final String text;
		/** {@code structuredContent} when the server sends it, otherwise the text parsed as JSON, otherwise null. */
		public final JsonNode json;

		ToolResult(final boolean pError, final String pText, final JsonNode pJson)
		{
			this.error = pError;
			this.text = pText;
			this.json = pJson;
		}
	}

	/** A tool as {@code tools/list} describes it. */
	public static final class Tool
	{
		public final String name;
		public final String description;
		public final JsonNode inputSchema;

		Tool(final String pName, final String pDescription, final JsonNode pInputSchema)
		{
			this.name = pName;
			this.description = pDescription;
			this.inputSchema = pInputSchema;
		}
	}

	/**
	 * Carries one JSON-RPC message to a server and brings its answer back, in place of HTTP: for a server of the
	 * same JVM reached without the network (the ebx-connectors in-JVM bridge). Returns the answer text, empty or
	 * null for a notification; throws {@link McpException} when the message could not be delivered.
	 */
	public interface Transport
	{
		String exchange(String pMessage) throws McpException;

		/** Where the messages go, for error texts ("in-JVM ebx-connectors"). */
		String describe();
	}

	private final Transport transport;
	private final String url;
	private final Map<String, String> headers = new LinkedHashMap<String, String>();
	private final int connectTimeoutMs;
	private final int readTimeoutMs;
	private final AtomicInteger ids = new AtomicInteger();
	private String sessionId;
	private boolean initialized;

	/**
	 * @param pHeaders sent with every request: authentication ({@code Authorization}, an API key header...), may be null
	 * @param pReadTimeoutMs generous: building or publishing a data model is one call
	 */
	public McpClient(final String pUrl, final Map<String, String> pHeaders, final int pConnectTimeoutMs, final int pReadTimeoutMs)
	{
		this.transport = null;
		this.url = pUrl;
		if (pHeaders != null)
		{
			this.headers.putAll(pHeaders);
		}
		this.connectTimeoutMs = pConnectTimeoutMs;
		this.readTimeoutMs = pReadTimeoutMs;
	}

	/** A client whose messages go through {@code pTransport} instead of HTTP; same protocol, same answers. */
	public McpClient(final Transport pTransport)
	{
		this.transport = pTransport;
		this.url = pTransport.describe();
		this.connectTimeoutMs = 0;
		this.readTimeoutMs = 0;
	}

	/** The headers of HTTP Basic authentication. */
	public static Map<String, String> basicHeaders(final String pLogin, final String pPassword)
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Authorization", McpClient.basic(pLogin, pPassword));
		return headers;
	}

	public static String basic(final String pLogin, final String pPassword)
	{
		final byte[] pair = (pLogin + ":" + (pPassword == null ? "" : pPassword)).getBytes(StandardCharsets.UTF_8);
		return "Basic " + java.util.Base64.getEncoder().encodeToString(pair);
	}

	/** The handshake; done on the first call when not invoked explicitly. Returns the server's {@code initialize} result. */
	public JsonNode initialize() throws McpException
	{
		final ObjectNode params = McpClient.MAPPER.createObjectNode();
		params.put("protocolVersion", McpClient.PROTOCOL_VERSION);
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "on-ebx-agent");
		info.put("version", "1");
		final JsonNode result = this.request("initialize", params);
		this.initialized = true;
		this.notify("notifications/initialized");
		return result;
	}

	public List<Tool> listTools() throws McpException
	{
		this.ensureInitialized();
		final List<Tool> tools = new ArrayList<Tool>();
		String cursor = null;
		do
		{
			final ObjectNode params = McpClient.MAPPER.createObjectNode();
			if (cursor != null)
			{
				params.put("cursor", cursor);
			}
			final JsonNode result = this.request("tools/list", params);
			for (final JsonNode tool : result.path("tools"))
			{
				tools.add(new Tool(tool.path("name").asText(), tool.path("description").asText(""), tool.get("inputSchema")));
			}
			cursor = result.path("nextCursor").asText("");
		}
		while (!cursor.isEmpty());
		return tools;
	}

	public ToolResult callTool(final String pName, final JsonNode pArguments) throws McpException
	{
		this.ensureInitialized();
		final ObjectNode params = McpClient.MAPPER.createObjectNode();
		params.put("name", pName);
		params.set("arguments", pArguments == null ? McpClient.MAPPER.createObjectNode() : pArguments);
		final JsonNode result = this.request("tools/call", params);
		final StringBuilder text = new StringBuilder();
		for (final JsonNode block : result.path("content"))
		{
			if ("text".equals(block.path("type").asText()))
			{
				if (text.length() > 0)
				{
					text.append('\n');
				}
				text.append(block.path("text").asText());
			}
		}
		JsonNode json = result.get("structuredContent");
		if (json == null || json.isNull())
		{
			json = McpClient.parseOrNull(text.toString());
		}
		return new ToolResult(result.path("isError").asBoolean(false), text.toString(), json);
	}

	private void ensureInitialized() throws McpException
	{
		if (!this.initialized)
		{
			this.initialize();
		}
	}

	private JsonNode request(final String pMethod, final JsonNode pParams) throws McpException
	{
		final int id = this.ids.incrementAndGet();
		final ObjectNode message = McpClient.MAPPER.createObjectNode();
		message.put("jsonrpc", "2.0");
		message.put("id", id);
		message.put("method", pMethod);
		message.set("params", pParams);
		final JsonNode answer = this.post(message, id);
		if (answer == null)
		{
			throw new McpException(0, pMethod + ": the server sent no answer to request " + id);
		}
		if (answer.hasNonNull("error"))
		{
			final JsonNode error = answer.get("error");
			throw new McpException(error.path("code").asInt(), pMethod + ": " + error.path("message").asText("JSON-RPC error"));
		}
		return answer.path("result");
	}

	private void notify(final String pMethod) throws McpException
	{
		final ObjectNode message = McpClient.MAPPER.createObjectNode();
		message.put("jsonrpc", "2.0");
		message.put("method", pMethod);
		this.post(message, -1);
	}

	/** @return the JSON-RPC answer carrying {@code pId}, or null for a notification */
	private JsonNode post(final JsonNode pMessage, final int pId) throws McpException
	{
		if (this.transport != null)
		{
			try
			{
				final String body = this.transport.exchange(McpClient.MAPPER.writeValueAsString(pMessage));
				return pId < 0 || body == null || body.trim().isEmpty() ? null : McpClient.pick(McpClient.MAPPER.readTree(body), pId);
			}
			catch (final IOException ex)
			{
				throw McpClient.unreadable(this.url, ex);
			}
		}
		HttpURLConnection connection = null;
		try
		{
			connection = (HttpURLConnection) new URL(this.url).openConnection();
			// never replay the Authorization / API key / Cookie headers to a redirect target (security review, 2026-09-24)
			connection.setInstanceFollowRedirects(false);
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(this.connectTimeoutMs);
			connection.setReadTimeout(this.readTimeoutMs);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("Accept", "application/json, text/event-stream");
			if (this.initialized)
			{
				connection.setRequestProperty("MCP-Protocol-Version", McpClient.PROTOCOL_VERSION);
			}
			for (final Map.Entry<String, String> header : this.headers.entrySet())
			{
				connection.setRequestProperty(header.getKey(), header.getValue());
			}
			if (this.sessionId != null)
			{
				connection.setRequestProperty("Mcp-Session-Id", this.sessionId);
			}
			final OutputStream out = connection.getOutputStream();
			try
			{
				out.write(McpClient.MAPPER.writeValueAsBytes(pMessage));
			}
			finally
			{
				out.close();
			}
			final int status = connection.getResponseCode();
			final String session = connection.getHeaderField("Mcp-Session-Id");
			if (session != null && !session.isEmpty())
			{
				this.sessionId = session;
			}
			final InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
			final String body = stream == null ? "" : McpClient.read(stream);
			if (status >= 400)
			{
				throw new McpException(status, "HTTP " + status + " from " + this.url + (body.isEmpty() ? "" : ": " + McpClient.shorten(body)));
			}
			if (pId < 0 || body.trim().isEmpty())
			{
				return null;
			}
			final String type = String.valueOf(connection.getContentType());
			return type.startsWith("text/event-stream") ? McpClient.fromEvents(body, pId) : McpClient.pick(McpClient.MAPPER.readTree(body), pId);
		}
		catch (final IOException ex)
		{
			// the server answered but its answer could not be READ: that is not "unreachable" (audit F3-100)
			if (ex instanceof com.fasterxml.jackson.core.JsonProcessingException)
			{
				throw McpClient.unreadable(this.url, ex);
			}
			throw new McpException("MCP server unreachable at " + this.url + ": " + ex, ex);
		}
		finally
		{
			if (connection != null)
			{
				connection.disconnect();
			}
		}
	}

	/**
	 * The failure of reading an answer that arrived. A Jackson read bound (StreamConstraintsException, Jackson 2.15 and later: nesting 1000,
	 * number 1000 characters, name 50,000, string 20,000,000 by default) is {@link McpException.Kind#RESPONSE_OVER_LIMIT}; anything else
	 * that is not JSON is {@link McpException.Kind#UNREADABLE_ANSWER}. Found by the class name, so that a Jackson older than 2.15 still loads this class.
	 */
	static McpException unreadable(final String pUrl, final IOException pCause)
	{
		for (Class<?> type = pCause.getClass(); type != null; type = type.getSuperclass())
		{
			if ("com.fasterxml.jackson.core.exc.StreamConstraintsException".equals(type.getName()))
			{
				return new McpException(McpException.Kind.RESPONSE_OVER_LIMIT, "MCP answer from " + pUrl
					+ " goes beyond a read bound of the JSON reader (the server answered, it is not unreachable): " + pCause.getMessage()
					+ " - raise the bound in the reader's configuration or ask the tool for a smaller answer", pCause);
			}
		}
		return new McpException(McpException.Kind.UNREADABLE_ANSWER, "Unreadable answer from " + pUrl + " (the server answered, it is not unreachable): " + pCause, pCause);
	}

	/** The answer to our request among the events of a stream: a server may send notifications and requests of its own first. */
	static JsonNode fromEvents(final String pBody, final int pId) throws IOException
	{
		final StringBuilder data = new StringBuilder();
		for (final String line : (pBody + "\n\n").split("\r?\n", -1))
		{
			if (line.startsWith("data:"))
			{
				if (data.length() > 0)
				{
					data.append('\n');
				}
				data.append(line.substring(5).trim());
			}
			else if (line.isEmpty() && data.length() > 0)
			{
				final JsonNode answer = McpClient.pick(McpClient.MAPPER.readTree(data.toString()), pId);
				if (answer != null)
				{
					return answer;
				}
				data.setLength(0);
			}
		}
		return null;
	}

	/** A body is one message or a batch; only an answer (result or error) to {@code pId} is ours. */
	private static JsonNode pick(final JsonNode pBody, final int pId)
	{
		if (pBody.isArray())
		{
			for (final JsonNode message : pBody)
			{
				final JsonNode answer = McpClient.pick(message, pId);
				if (answer != null)
				{
					return answer;
				}
			}
			return null;
		}
		final boolean answer = pBody.has("result") || pBody.has("error");
		return answer && pBody.path("id").asInt(Integer.MIN_VALUE) == pId ? pBody : null;
	}

	private static JsonNode parseOrNull(final String pText)
	{
		final String text = pText.trim();
		if (!text.startsWith("{") && !text.startsWith("["))
		{
			return null;
		}
		try
		{
			return McpClient.MAPPER.readTree(text);
		}
		catch (final IOException ex)
		{
			return null;
		}
	}

	private static String read(final InputStream pStream) throws IOException
	{
		try
		{
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buffer = new byte[8192];
			for (int n = pStream.read(buffer); n >= 0; n = pStream.read(buffer))
			{
				out.write(buffer, 0, n);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
		finally
		{
			pStream.close();
		}
	}

	private static String shorten(final String pBody)
	{
		final String body = pBody.replaceAll("\\s+", " ").trim();
		return body.length() > 300 ? body.substring(0, 300) + "..." : body;
	}
}
