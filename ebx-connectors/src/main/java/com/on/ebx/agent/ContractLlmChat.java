package com.on.ebx.agent;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import javax.management.InstanceNotFoundException;
import javax.management.ObjectName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A model turn asked through ebx-connectors: the operation {@code llm.chat} of its in-JVM contract
 * ({@code ebx-connectors.sibling} 1.1.0, platform MBean {@value #MBEAN}). ebx-connectors sends the turn with the
 * {@code llm} connector's own client, key, outbound rules and provider settings, caps the answer size at the connector's
 * {@code maxTokens}, checks the caller's Connectors right (RUN) and leaves an audit line; the key never reaches the
 * calling module. One call = one HTTP round trip to the model: the tools are run by the caller ({@link AgentLoop}), never
 * by ebx-connectors.
 * <p>
 * Pure JDK and Jackson like the rest of this library: the caller's EBX {@code Session} is passed as an {@code Object}
 * (the EBX classes are shared by Tomcat's common class loader), the answer comes back as JSON text. Not thread-safe:
 * one instance per conversation, like {@link LlmClient}.
 */
public final class ContractLlmChat implements LlmChat
{
	/** The platform MBean of the contract. */
	public static final String MBEAN = "com.on.ebx.connectors:type=Contract";
	/** The contract operation this class calls. */
	public static final String OPERATION = "llm.chat";
	/** The contract version that introduced {@link #OPERATION}: a caller needs this one or a later minor of the same major. */
	public static final String NEEDED_VERSION = "1.1.0";

	/** How a JSON-RPC message reaches the contract: the platform MBean in production, the contract itself in a test. */
	public interface Transport
	{
		/** One JSON-RPC request, its JSON-RPC answer. */
		String handle(Object pSession, String pMessage) throws Exception;
	}

	/** The platform MBean {@value #MBEAN}, operation {@code handle(Object, String)}. */
	public static final Transport MBEAN_TRANSPORT = new Transport()
	{
		@Override
		public String handle(final Object pSession, final String pMessage) throws Exception
		{
			return String.valueOf(ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName(ContractLlmChat.MBEAN), "handle",
				new Object[] { pSession, pMessage }, new String[] { "java.lang.Object", "java.lang.String" }));
		}
	};

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Object session;
	private final String connector;
	private final Integer maxTokens;
	private final Integer timeoutMs;
	private final boolean json;
	private final Transport transport;
	private JsonNode lastResult;
	private String lastRefusal;

	/**
	 * @param pSession the caller's EBX {@code com.orchestranetworks.service.Session} (the person, or the system session of
	 * a background task): the call runs with its Connectors right
	 * @param pConnector id of an {@code llm} connector; null = the default one of ebx-connectors (see its {@code llm.connectors})
	 * @param pMaxTokens answer size asked for each turn (null = the connector's {@code maxTokens}); ebx-connectors never
	 * applies more than the connector's own setting ({@link #lastMaxTokensApplied}, {@link #lastCapped})
	 * @param pTimeoutMs read timeout of each turn in milliseconds (1000..600000; null = the connector's read timeout)
	 */
	public ContractLlmChat(final Object pSession, final String pConnector, final Integer pMaxTokens, final Integer pTimeoutMs)
	{
		this(pSession, pConnector, pMaxTokens, pTimeoutMs, false, ContractLlmChat.MBEAN_TRANSPORT);
	}

	/** Same, plus whether a JSON answer is expected ({@code json} of the contract) and the transport (a test's). */
	public ContractLlmChat(final Object pSession, final String pConnector, final Integer pMaxTokens, final Integer pTimeoutMs, final boolean pJson,
		final Transport pTransport)
	{
		this.session = pSession;
		this.connector = pConnector == null || pConnector.trim().isEmpty() ? null : pConnector.trim();
		this.maxTokens = pMaxTokens;
		this.timeoutMs = pTimeoutMs;
		this.json = pJson;
		this.transport = pTransport == null ? ContractLlmChat.MBEAN_TRANSPORT : pTransport;
	}

	/**
	 * Null when ebx-connectors offers a contract that has {@code llm.chat} (same major as {@value #NEEDED_VERSION}, at
	 * least that minor); else why not. Call it before choosing this class over a direct {@link LlmClient}.
	 */
	public static String incompatibility()
	{
		try
		{
			return ContractLlmChat.incompatibility(String.valueOf(ManagementFactory.getPlatformMBeanServer().getAttribute(new ObjectName(ContractLlmChat.MBEAN), "ContractVersion")));
		}
		catch (final InstanceNotFoundException ex)
		{
			return "ebx-connectors is not started (no " + ContractLlmChat.MBEAN + ")";
		}
		catch (final Exception ex)
		{
			return "ebx-connectors contract unreadable: " + ex;
		}
	}

	/** Pure: null when a contract of version {@code pOffered} has {@code llm.chat}; else why not. */
	public static String incompatibility(final String pOffered)
	{
		final int[] offered = ContractLlmChat.parse(pOffered);
		final int[] needed = ContractLlmChat.parse(ContractLlmChat.NEEDED_VERSION);
		final boolean ok = offered != null && offered[0] == needed[0] && (offered[1] > needed[1] || offered[1] == needed[1] && offered[2] >= needed[2]);
		return ok ? null : "ebx-connectors offers contract " + pOffered + ", " + ContractLlmChat.OPERATION + " needs " + ContractLlmChat.NEEDED_VERSION;
	}

	private static int[] parse(final String pVersion)
	{
		if (pVersion == null || !pVersion.trim().matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))
		{
			return null;
		}
		final String[] parts = pVersion.trim().split("\\.");
		return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
	}

	@Override
	public LlmClient.Turn send(final String pSystem, final List<LlmClient.ToolSpec> pTools, final List<LlmClient.Message> pConversation) throws LlmException
	{
		this.lastResult = null;
		this.lastRefusal = null;
		final ObjectNode request = ContractLlmChat.MAPPER.createObjectNode();
		request.put("jsonrpc", "2.0");
		request.put("id", 1);
		request.put("method", ContractLlmChat.OPERATION);
		request.set("params", ContractLlmChat.params(this.connector, pSystem, pTools, pConversation, this.maxTokens, this.timeoutMs, this.json));
		final JsonNode answer;
		try
		{
			answer = ContractLlmChat.MAPPER.readTree(this.transport.handle(this.session, ContractLlmChat.MAPPER.writeValueAsString(request)));
		}
		catch (final InstanceNotFoundException ex)
		{
			this.lastRefusal = "ABSENT";
			throw new LlmException("ebx-connectors is not started (no " + ContractLlmChat.MBEAN + ")", ex);
		}
		catch (final Exception ex)
		{
			this.lastRefusal = "INTERNAL";
			throw new LlmException("ebx-connectors contract call " + ContractLlmChat.OPERATION + " failed: " + ex, ex);
		}
		if (answer == null || answer.has("error") || !answer.has("result"))
		{
			final JsonNode error = answer == null ? ContractLlmChat.MAPPER.createObjectNode() : answer.path("error");
			this.lastRefusal = error.path("data").path("type").asText("OPERATION_FAILED");
			throw new LlmException(0, "ebx-connectors refused " + ContractLlmChat.OPERATION + " (" + this.lastRefusal + "): " + error.path("message").asText(""));
		}
		this.lastResult = answer.get("result");
		return ContractLlmChat.turnOf(this.lastResult);
	}

	/**
	 * Pure: the {@code llm.chat} parameters for one turn. The neutral conversation becomes the contract's messages: a user
	 * entry {@code {role: user, content}}, an assistant entry {@code {role: assistant, content?, toolCalls?[{id, name,
	 * arguments}]}}, and each tool result its own {@code {role: tool, toolCallId, content, isError?}}.
	 */
	public static ObjectNode params(final String pConnector, final String pSystem, final List<LlmClient.ToolSpec> pTools,
		final List<LlmClient.Message> pConversation, final Integer pMaxTokens, final Integer pTimeoutMs, final boolean pJson)
	{
		final ObjectNode params = ContractLlmChat.MAPPER.createObjectNode();
		if (pConnector != null)
		{
			params.put("connector", pConnector);
		}
		if (pSystem != null && !pSystem.trim().isEmpty())
		{
			params.put("system", pSystem);
		}
		final ArrayNode messages = params.putArray("messages");
		if (pConversation != null)
		{
			for (final LlmClient.Message message : pConversation)
			{
				if ("tool".equals(message.role()))
				{
					for (final LlmClient.ToolResult result : message.getResults())
					{
						final ObjectNode m = messages.addObject();
						m.put("role", "tool");
						m.put("toolCallId", result.toolCallId);
						m.put("content", result.text);
						if (result.isError)
						{
							m.put("isError", true);
						}
					}
					continue;
				}
				final ObjectNode m = messages.addObject();
				m.put("role", message.role());
				if (message.getText() != null && !message.getText().isEmpty())
				{
					m.put("content", message.getText());
				}
				if (!message.getToolCalls().isEmpty())
				{
					final ArrayNode calls = m.putArray("toolCalls");
					for (final LlmClient.ToolCall call : message.getToolCalls())
					{
						final ObjectNode c = calls.addObject();
						c.put("id", call.id);
						c.put("name", call.name);
						c.set("arguments", call.arguments);
					}
				}
			}
		}
		if (pTools != null && !pTools.isEmpty())
		{
			final ArrayNode tools = params.putArray("tools");
			for (final LlmClient.ToolSpec tool : pTools)
			{
				final ObjectNode t = tools.addObject();
				t.put("name", tool.name);
				t.put("description", tool.description);
				t.set("inputSchema", tool.inputSchema);
			}
		}
		if (pMaxTokens != null)
		{
			params.put("maxTokens", pMaxTokens.intValue());
		}
		if (pTimeoutMs != null)
		{
			params.put("timeoutMs", pTimeoutMs.intValue());
		}
		if (pJson)
		{
			params.put("json", true);
		}
		return params;
	}

	/** Pure: the turn of an {@code llm.chat} result ({@code message}, {@code stopReason}, {@code usage}). */
	public static LlmClient.Turn turnOf(final JsonNode pResult)
	{
		final JsonNode message = pResult.path("message");
		final List<LlmClient.ToolCall> calls = new ArrayList<LlmClient.ToolCall>();
		for (final JsonNode call : message.path("toolCalls"))
		{
			calls.add(new LlmClient.ToolCall(call.path("id").asText(), call.path("name").asText(), call.get("arguments")));
		}
		LlmClient.StopReason reason;
		try
		{
			reason = LlmClient.StopReason.valueOf(pResult.path("stopReason").asText("OTHER"));
		}
		catch (final IllegalArgumentException ex)
		{
			reason = LlmClient.StopReason.OTHER; // a reason a later contract version adds
		}
		final String text = message.path("content").isTextual() ? message.path("content").asText() : "";
		return new LlmClient.Turn(text, calls, reason, pResult.path("usage").path("inputTokens").asInt(0), pResult.path("usage").path("outputTokens").asInt(0));
	}

	/** The whole result of the last successful turn (model, provider, connector, maxTokensApplied, capped, durationMs...); null before or after a failure. */
	public JsonNode lastResult()
	{
		return this.lastResult;
	}

	/** {@code error.data.type} of the last refused turn ({@code TIMEOUT}, {@code ACCESS_DENIED}...; {@code ABSENT} or {@code INTERNAL} when the contract could not be reached); null otherwise. */
	public String lastRefusal()
	{
		return this.lastRefusal;
	}

	/** The answer size ebx-connectors applied to the last turn; 0 before any. */
	public int lastMaxTokensApplied()
	{
		return this.lastResult == null ? 0 : this.lastResult.path("maxTokensApplied").asInt(0);
	}

	/** True when the last turn asked more than the connector's {@code maxTokens} and got the connector's value. */
	public boolean lastCapped()
	{
		return this.lastResult != null && this.lastResult.path("capped").asBoolean(false);
	}
}
