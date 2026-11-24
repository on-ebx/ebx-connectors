package com.on.ebx.connect.connectors;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.OAuth2;
import com.on.ebx.connect.core.Redaction;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;

/**
 * A Model Context Protocol server reached over Streamable HTTP: the base URL is the MCP endpoint itself
 * ({@code http://host:8080/ebx-assistant/mcp/v1/data-model} for a server of EBX,
 * {@code .../ebx-connectors/rest/mcp} for a module endpoint, or any remote server). Two operations:
 * {@code listTools} and {@code callTool}.
 * <p>
 * The account of the connector is the identity every tool call runs as: on an EBX server, what that
 * user may do is what a caller of this connector may do. An agent that is given this connector (the
 * MDM Factory) is bounded the same way, so a dedicated, least-privileged account belongs here.
 * <p>
 * EBX answers an empty tool list, without any error, for a server that is not enabled in
 * Administration &gt; MCP servers: the connection test says so instead of reporting a plain success.
 */
public final class McpConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
		new Operation("listTools", Operation.Kind.SEARCH, "The tools the server offers to this account: name, description and input schema", new String[0],
			new String[] { "name" }),
		new Operation("callTool", Operation.Kind.WRITE, "Call one tool; the arguments are the payload, or the 'arguments' input as a JSON object",
			new String[] { "tool" }, new String[] { "arguments" })));

	@Override
	public String getType()
	{
		return "mcp";
	}

	@Override
	public String getLabel()
	{
		return "MCP server (Model Context Protocol)";
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(this.getType(), this.getLabel(), "AI")
			.description("Model Context Protocol server over Streamable HTTP: list its tools and call them, as the account of the connector")
			.authentication(AuthenticationMethod.BASIC).authentication(AuthenticationMethod.BEARER_TOKEN).authentication(AuthenticationMethod.NONE)
			.apiVersion("MCP " + McpClient.PROTOCOL_VERSION);
		for (final Operation op : this.getOperations())
		{
			final ConnectorDirection direction = op.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(direction).operation(new ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(),
				Collections.singleton(direction), Collections.<ConnectorCapability> emptySet()));
		}
		return b.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return McpConnector.OPERATIONS;
	}

	/** A client per call: the handshake is cheap and nothing is shared between the threads of two flows. */
	private McpClient client() throws ConnectorException
	{
		this.requireSecret();
		try
		{
			// McpClient is pure JDK (it is shared with modules that have no HttpJsonClient): the outbound allow-list is checked here
			Egress.check(this.baseUrl());
			return new McpClient(this.baseUrl(), this.headers(), this.getConfig().getConnectTimeoutMs(), this.getConfig().getReadTimeoutMs());
		}
		catch (final OAuth2.TokenException ex)
		{
			throw new ConnectorException(ex.getMessage(), ex);
		}
		catch (final IllegalStateException ex)
		{
			throw new ConnectorException(this.getConfig().getId() + ": " + ex.getMessage(), ex);
		}
	}

	/** The handshake and the tool list: reads, changes nothing. */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.nanoTime();
		try
		{
			final McpClient client;
			try
			{
				client = this.client();
			}
			catch (final ConnectorException ex)
			{
				return ConnectionTestResult.failure(ex.getMessage(), (System.nanoTime() - started) / 1000000L);
			}
			final JsonNode server = client.initialize().path("serverInfo");
			final int tools = client.listTools().size();
			final long elapsed = (System.nanoTime() - started) / 1000000L;
			final String name = server.path("name").asText("MCP server");
			if (tools == 0)
			{
				return ConnectionTestResult.failure(name + " answers but offers no tool to this account. On EBX, a server that is not enabled in "
					+ "Administration > MCP servers answers exactly this.", elapsed);
			}
			return ConnectionTestResult.success(name + ": " + tools + " tool(s)", elapsed);
		}
		catch (final McpException ex)
		{
			return ConnectionTestResult.failure(this.explain(ex), (System.nanoTime() - started) / 1000000L);
		}
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		try
		{
			if ("listTools".equals(pOperation))
			{
				final String filter = pInputs == null || pInputs.get("name") == null ? "" : pInputs.get("name").trim().toLowerCase(java.util.Locale.ROOT);
				final ObjectNode out = Json.mapper().createObjectNode();
				final ArrayNode tools = out.putArray("tools");
				for (final McpClient.Tool tool : this.client().listTools())
				{
					if (filter.isEmpty() || tool.name.toLowerCase(java.util.Locale.ROOT).contains(filter))
					{
						final ObjectNode item = tools.addObject();
						item.put("name", tool.name);
						item.put("description", tool.description);
						item.set("inputSchema", tool.inputSchema);
					}
				}
				out.put("count", tools.size());
				return out;
			}
			if ("callTool".equals(pOperation))
			{
				final String tool = AbstractRestConnector.require(pInputs, "tool");
				final McpClient.ToolResult result = this.client().callTool(tool, McpConnector.arguments(pInputs, pPayload));
				final ObjectNode out = Json.mapper().createObjectNode();
				out.put("tool", tool);
				out.put("isError", result.error);
				out.put("text", result.text);
				if (result.json != null)
				{
					out.set("json", result.json);
				}
				return out;
			}
		}
		catch (final McpException ex)
		{
			throw new ConnectorException(this.explain(ex), ex);
		}
		throw new ConnectorException("Unknown operation '" + pOperation + "' for the mcp connector (listTools, callTool)");
	}

	/** The 'arguments' input wins over the payload: a flow maps a record to the payload, a caller that knows the tool passes the input. */
	static JsonNode arguments(final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final String text = pInputs == null ? null : pInputs.get("arguments");
		if (text != null && !text.trim().isEmpty())
		{
			try
			{
				final JsonNode parsed = Json.parse(text);
				if (parsed == null || !parsed.isObject())
				{
					throw new ConnectorException("Input 'arguments' must be a JSON object");
				}
				return parsed;
			}
			catch (final IOException ex)
			{
				throw new ConnectorException("Input 'arguments' is not valid JSON: " + ex.getMessage(), ex);
			}
		}
		return pPayload != null && pPayload.isObject() ? pPayload : Json.mapper().createObjectNode();
	}

	private String explain(final McpException pException)
	{
		final String id = this.getConfig().getId();
		if (pException.getCode() == 401 || pException.getCode() == 403)
		{
			return Redaction.redact(id + ": the MCP server refused the account of the connector (HTTP " + pException.getCode()
				+ "). Check the authentication type, the user and the password.");
		}
		if (pException.getCode() == 400 || pException.getCode() == 404)
		{
			return Redaction.redact(id + ": " + pException.getMessage() + ". The base URL must be the MCP endpoint itself, with the server name EBX registered "
				+ "(data-model, match-merge, data-access, workflow-modeling...).");
		}
		return Redaction.redact(id + ": " + pException.getMessage());
	}
}
