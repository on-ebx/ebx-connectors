package com.on.ebx.connect.ebx.rest;

import java.io.InputStream;
import java.util.Locale;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.ebx.ConnectorsAccess;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.rest.inject.SessionContext;
import com.orchestranetworks.service.Session;

/**
 * The module's own MCP endpoint, {@code POST /ebx-connectors/rest/mcp}: MCP over streamable HTTP
 * (JSON-RPC 2.0, one JSON answer per request, no server-sent events), authenticated like any
 * EBX REST call (Basic, token, session). It serves the tools of {@link ConnectorsTools} on every
 * EBX version; on EBX 6.3 the same tools are also registered with the native MCP runtime.
 * Disabled with {@code -Debx.connectors.mcp=native} or {@code none}.
 */
@Path("/mcp")
@Produces(MediaType.APPLICATION_JSON)
public final class McpEndpoint
{
	public static final String PROTOCOL_VERSION = "2025-06-18";
	private final SessionContext sessionContext;

	public McpEndpoint(@Context final SessionContext pSessionContext)
	{
		this.sessionContext = pSessionContext;
	}

	public static boolean enabled()
	{
		final String mode = System.getProperty("ebx.connectors.mcp", "both").trim().toLowerCase();
		return "both".equals(mode) || "endpoint".equals(mode);
	}

	/**
	 * MCP streamable HTTP without server-sent events: a client probing {@code GET} (to open a stream)
	 * or {@code DELETE} (to end a session) must get {@code 405} with {@code Allow: POST, OPTIONS}, like
	 * the standard EBX endpoints, so that it falls back to plain request/response. Any other answer
	 * (a JSON page, 406 because of {@code Accept: text/event-stream}, 204) makes the handshake fail
	 * silently. Produces anything, so that the Accept header of the probe never turns into a 406.
	 */
	@GET
	@Produces(MediaType.WILDCARD)
	public Response get()
	{
		return McpEndpoint.methodNotAllowed();
	}

	@DELETE
	@Produces(MediaType.WILDCARD)
	public Response delete()
	{
		return McpEndpoint.methodNotAllowed();
	}

	@jakarta.ws.rs.OPTIONS
	@Produces(MediaType.WILDCARD)
	public Response options()
	{
		return Response.ok().header("Allow", "POST,OPTIONS").build();
	}

	private static Response methodNotAllowed()
	{
		if (!McpEndpoint.enabled())
		{
			return Response.status(404).build();
		}
		return Response.status(405).header("Allow", "POST,OPTIONS").build();
	}

	@POST
	@Consumes({ MediaType.APPLICATION_JSON, MediaType.WILDCARD })
	public Response handle(@HeaderParam("Content-Length") final String pContentLength, final InputStream pBody)
	{
		if (!McpEndpoint.enabled())
		{
			return Response.status(404).build();
		}
		// audit C2-06: authentication, then the Connectors right, and only then the body, read at most one byte past its bound
		final Session session = this.sessionContext.getSessionOrNull();
		if (session == null)
		{
			return Response.status(401).header("WWW-Authenticate", "Basic realm=\"EBX\"").entity("{\"error\":\"Authentication required\"}").build();
		}
		final Locale locale = this.sessionContext.getLocale() == null ? Locale.ENGLISH : this.sessionContext.getLocale();
		if (ConnectorsAccess.granted(session) == null)
		{
			return Response.status(403).entity(Json.write(McpEndpoint.error(null, -32600,
				ConnectorsAccess.refusal("the connectors MCP endpoint", ConnectorsAccess.Right.READ, locale)))).build();
		}
		final Parsed parsed = McpEndpoint.parse(pContentLength, pBody, locale);
		if (parsed.refusal != null)
		{
			return parsed.refusal;
		}
		final JsonNode request = parsed.request;
		if (request.isArray())
		{
			final ArrayNode answers = Json.object().arrayNode();
			for (final JsonNode one : request)
			{
				final JsonNode answer = McpEndpoint.dispatch(one, session, this.sessionContext.getRepository(), locale);
				if (answer != null)
				{
					answers.add(answer);
				}
			}
			return answers.size() == 0 ? Response.status(202).build() : Response.ok(Json.write(answers)).build();
		}
		final JsonNode answer = McpEndpoint.dispatch(request, session, this.sessionContext.getRepository(), locale);
		return answer == null ? Response.status(202).build() : Response.ok(Json.write(answer)).build();
	}

	/** What {@link #parse} makes of a body: the parsed message or batch, or the answer that refuses it. */
	static final class Parsed
	{
		final JsonNode request;
		final Response refusal;

		Parsed(final JsonNode pRequest, final Response pRefusal)
		{
			this.request = pRequest;
			this.refusal = pRefusal;
		}
	}

	/**
	 * Audit C2-06: the body of a POST, bounded (size, then nesting depth, then batch length) and parsed. The caller's right has
	 * been checked before this reads a byte. Every refusal is a JSON-RPC error that carries its code, the property to raise and the bound.
	 */
	static Parsed parse(final String pContentLength, final InputStream pBody, final Locale pLocale)
	{
		final long maxBody = BodyLimit.maxMcpBodyBytes();
		final BodyLimit.Violation declared = BodyLimit.declaredLength(pContentLength, maxBody, BodyLimit.PROP_MCP_BODY);
		if (declared != null)
		{
			return new Parsed(null, McpEndpoint.refused(declared, pLocale));
		}
		final byte[] raw;
		try
		{
			raw = BodyLimit.readBounded(pBody, maxBody, BodyLimit.PROP_MCP_BODY);
		}
		catch (final BodyLimit.TooLarge ex)
		{
			return new Parsed(null, McpEndpoint.refused(ex.violation, pLocale));
		}
		catch (final java.io.IOException ex)
		{
			return new Parsed(null, Response.status(400).entity(Json.write(McpEndpoint.error(null, -32700, "Parse error: " + ex.getMessage()))).build());
		}
		final BodyLimit.Violation deep = BodyLimit.checkDepth(raw, BodyLimit.maxMcpDepth(), BodyLimit.PROP_MCP_DEPTH);
		if (deep != null)
		{
			return new Parsed(null, McpEndpoint.refused(deep, pLocale));
		}
		final JsonNode request;
		try
		{
			request = Json.parse(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
		}
		catch (final Exception ex)
		{
			return new Parsed(null, Response.status(400).entity(Json.write(McpEndpoint.error(null, -32700, "Parse error: " + ex.getMessage()))).build());
		}
		if (request == null)
		{
			return new Parsed(null, Response.status(400).entity(Json.write(McpEndpoint.error(null, -32600, "Invalid request"))).build());
		}
		final BodyLimit.Violation batch = request.isArray() ? BodyLimit.checkBatch(request.size(), BodyLimit.maxMcpBatch(), BodyLimit.PROP_MCP_BATCH) : null;
		if (batch != null)
		{
			return new Parsed(null, McpEndpoint.refused(batch, pLocale));
		}
		return new Parsed(request, null);
	}

	/** The JSON-RPC error (code -32600) of a call over a bound, with its HTTP status, code, property and bound. */
	static Response refused(final BodyLimit.Violation pViolation, final Locale pLocale)
	{
		final ObjectNode out = McpEndpoint.error(null, -32600, pViolation.message(pLocale));
		final ObjectNode data = ((ObjectNode) out.get("error")).putObject("data");
		data.put("code", pViolation.code);
		data.put("property", pViolation.property);
		data.put("limit", pViolation.limit);
		return Response.status(pViolation.status).entity(Json.write(out)).build();
	}

	/**
	 * One JSON-RPC message; null for a notification (no answer). Static and package-visible: the HTTP endpoint
	 * above and the in-JVM bridge ({@link InJvmMcp}) answer through this one method, so both transports serve
	 * exactly the same tools with the same session semantics.
	 */
	static JsonNode dispatch(final JsonNode pMessage, final Session pSession, final Repository pRepository, final Locale pLocale)
	{
		final JsonNode id = pMessage.get("id");
		final String method = pMessage.get("method") == null ? "" : pMessage.get("method").asText();
		final JsonNode params = pMessage.get("params") == null ? Json.object() : pMessage.get("params");
		if (method.startsWith("notifications/"))
		{
			return null;
		}
		if ("initialize".equals(method))
		{
			final ObjectNode result = Json.object();
			final JsonNode wanted = params.get("protocolVersion");
			result.put("protocolVersion", wanted == null || wanted.isNull() ? McpEndpoint.PROTOCOL_VERSION : wanted.asText());
			result.putObject("capabilities").putObject("tools").put("listChanged", false);
			final ObjectNode info = result.putObject("serverInfo");
			info.put("name", ConnectorsTools.SERVER_NAME);
			info.put("title", "ON EBX connectors");
			info.put("version", com.on.ebx.connect.core.ModuleVersion.version());
			result.put("instructions", ConnectorsTools.SERVER_DESCRIPTION + " Call " + ConnectorsTools.T_HELP + " first.");
			return McpEndpoint.result(id, result);
		}
		if ("ping".equals(method))
		{
			return McpEndpoint.result(id, Json.object());
		}
		if ("tools/list".equals(method))
		{
			final ObjectNode result = Json.object();
			final ArrayNode tools = result.putArray("tools");
			// a caller is only shown the tools its Connectors role lets it call (none without a role)
			final ConnectorsAccess.Right granted = ConnectorsAccess.granted(pSession);
			for (final ConnectorsTools.Tool tool : ConnectorsTools.CATALOG)
			{
				if (granted != null && granted.covers(ConnectorsAccess.required(tool.name)))
				{
					tools.add(McpEndpoint.toolJson(tool));
				}
			}
			return McpEndpoint.result(id, result);
		}
		if ("tools/call".equals(method))
		{
			final String name = params.get("name") == null ? "" : params.get("name").asText();
			if (ConnectorsTools.tool(name) == null)
			{
				return McpEndpoint.error(id, -32602, "Unknown tool: " + name);
			}
			final ObjectNode data = new ConnectorsTools(pSession, pRepository, pLocale).invoke(name, params.get("arguments"));
			final ObjectNode result = Json.object();
			final ObjectNode text = result.putArray("content").addObject();
			text.put("type", "text");
			text.put("text", Json.write(data));
			result.set("structuredContent", data);
			result.put("isError", data.has("error"));
			return McpEndpoint.result(id, result);
		}
		if ("resources/list".equals(method) || "prompts/list".equals(method))
		{
			final ObjectNode result = Json.object();
			result.putArray("resources/list".equals(method) ? "resources" : "prompts");
			return McpEndpoint.result(id, result);
		}
		return McpEndpoint.error(id, -32601, "Method not found: " + method);
	}

	static ObjectNode toolJson(final ConnectorsTools.Tool pTool)
	{
		final ObjectNode t = Json.object();
		t.put("name", pTool.name);
		t.put("description", pTool.description);
		final ObjectNode schema = t.putObject("inputSchema");
		schema.put("type", "object");
		final ObjectNode properties = schema.putObject("properties");
		final ArrayNode required = schema.putArray("required");
		for (final ConnectorsTools.Param param : pTool.params)
		{
			final ObjectNode p = properties.putObject(param.name);
			p.put("type", param.type);
			p.put("description", param.description);
			if (param.required)
			{
				required.add(param.name);
			}
		}
		if (required.size() == 0)
		{
			schema.remove("required");
		}
		final ObjectNode annotations = t.putObject("annotations");
		annotations.put("readOnlyHint", "read".equals(pTool.behavior));
		annotations.put("destructiveHint", "destructive".equals(pTool.behavior));
		annotations.put("idempotentHint", !"additive".equals(pTool.behavior));
		return t;
	}

	private static ObjectNode result(final JsonNode pId, final JsonNode pResult)
	{
		final ObjectNode out = Json.object();
		out.put("jsonrpc", "2.0");
		out.set("id", pId == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : pId);
		out.set("result", pResult);
		return out;
	}

	private static ObjectNode error(final JsonNode pId, final int pCode, final String pMessage)
	{
		final ObjectNode out = Json.object();
		out.put("jsonrpc", "2.0");
		out.set("id", pId == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : pId);
		final ObjectNode error = out.putObject("error");
		error.put("code", pCode);
		error.put("message", pMessage);
		return out;
	}
}
