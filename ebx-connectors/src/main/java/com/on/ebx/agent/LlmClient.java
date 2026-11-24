package com.on.ebx.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One conversation turn WITH tools against an LLM provider, in one of two wire formats:
 * <ul>
 * <li>{@link Format#ANTHROPIC} — the Anthropic Messages API ({@code POST /v1/messages}). Verified against
 * "Tool use with Claude" and "Handle tool calls" (platform.claude.com/docs/en/agents-and-tools/tool-use/...,
 * the current location of what used to be docs.claude.com/en/docs/build-with-claude/tool-use), read
 * 2026-09-20. Request: {@code model, max_tokens, system, messages, tools:[{name, description, input_schema}]}.
 * The answer's {@code content} is a list of blocks ({@code text}, {@code tool_use} with {@code id, name,
 * input}); {@code stop_reason} is {@code tool_use}, {@code end_turn}, {@code stop_sequence}, {@code max_tokens}
 * or a few rarer values; {@code usage.input_tokens} / {@code output_tokens}. A tool result goes back as a
 * {@code user} message whose content is a list of {@code tool_result} blocks ({@code tool_use_id, content,
 * is_error}); a plain string is a valid {@code content} for a block with no image/document. Doc confirms
 * {@code content} may be a plain string on ordinary user/assistant text turns too — used here for that case.
 * <li>{@link Format#OPENAI_CHAT} — the OpenAI Chat Completions API ({@code POST /v1/chat/completions}) and
 * every OpenAI-compatible endpoint (Azure OpenAI, Mistral, Ollama). Verified against the OpenAI Cookbook's
 * "How_to_call_functions_with_chat_models" notebook (github.com/openai/openai-cookbook) and the
 * {@code completion_create_params.py} field docstrings of the official {@code openai-python} SDK
 * (github.com/openai/openai-python, {@code src/openai/types/chat/completion_create_params.py}), both read
 * 2026-09-20, after the interactive API reference pages (platform.openai.com/docs/...,
 * developers.openai.com/api/...) answered 403/404 to an unauthenticated fetch both times they were tried.
 * Request: {@code model, <token-limit field>, messages, tools:[{type: "function", function:{name,
 * description, parameters}}]}. The answer's {@code choices[0].message.tool_calls[]} carries {@code id,
 * type:"function", function:{name, arguments}} where {@code arguments} is a JSON string, not an object —
 * this library parses it back into a {@link JsonNode}, and falls back to {@code {"_raw": "<the unparsed
 * string>"}} if it does not parse. {@code finish_reason} is {@code stop}, {@code tool_calls}, {@code length},
 * {@code content_filter} or the deprecated {@code function_call}. {@code usage.prompt_tokens} /
 * {@code completion_tokens}. A tool result goes back as its own message: {@code role:"tool", tool_call_id,
 * content}. This format has no per-result error flag ({@code is_error} does not exist here): an error result
 * is prefixed with {@code "ERROR: "} in {@code content} instead (see {@link ToolResult#isError}).
 * </ul>
 * <b>The token-limit field name for {@link Format#OPENAI_CHAT}</b> is an explicit constructor option, never
 * guessed from the model name inside this library. Per {@code completion_create_params.py}: {@code max_tokens}
 * "is now deprecated in favor of {@code max_completion_tokens}, and is not compatible with o-series models";
 * {@code max_completion_tokens} is "an upper bound for the number of tokens that can be generated for a
 * completion, including visible output tokens and reasoning tokens" — the o-series (o1, o3, o3-mini) and
 * newer reasoning-style models (also gpt-5 family, per community reports) reject {@code max_tokens} outright
 * with an HTTP 400 "Unsupported parameter". {@link #DEFAULT_OPENAI_MAX_TOKENS_PARAM} is {@code max_tokens},
 * matching {@code com.on.ebx.connect.connectors.LlmConnector}'s own {@code openai.maxTokensParam} property
 * default exactly (that connector-layer property is a plain configurable string with no auto-detection from
 * the model name either — see its {@code ask(...)} method); the factory is expected to pass that same
 * connector property through as the constructor argument here, not invent a second convention. This request
 * builder sends nothing else beyond {@code model}, {@code messages}, {@code tools} and that one token-limit
 * field for {@link Format#OPENAI_CHAT} — in particular no {@code temperature}, which the same reasoning
 * models also reject (HTTP 400 "Unsupported parameter: 'temperature'") unless left at its default.
 * <p>
 * <b>{@code reasoning_effort} for {@link Format#OPENAI_CHAT}</b> (2026-09-26) is the one exception, and it is
 * never guessed from the model name either. It is sent only in two cases: (1) the caller passed an explicit
 * value to the 9-argument constructor (the factory passes the connector's {@code openai.reasoningEffort}
 * property; null or blank = not sent, the default, so gpt-4o and every endpoint that does not know the field
 * are unaffected); (2) an adaptive, one-time fallback: when a call WITH tools answers HTTP 400 and the error
 * body names both {@code reasoning_effort} and {@code none}, the same request is resent once with
 * {@code "reasoning_effort": "none"}, and that value is kept for every later turn of this client (one client
 * = one conversation). Seen live 2026-09-26 on gpt-6-luna: "Function tools with reasoning_effort are not
 * supported for gpt-6-luna in /v1/chat/completions. To use function tools, use /v1/responses or set
 * reasoning_effort to 'none'." - the model's own default effort is not {@code none}, so a request that says
 * nothing is refused as soon as it offers tools. No other HTTP 400 is ever retried.
 * <p>
 * Pure JDK and Jackson, no EBX class and nothing of {@code com.on.ebx.connect}: see {@link McpClient} for why.
 * This client never knows what an API key is — every header, including authentication, is supplied by the
 * caller and sent back only to the configured {@code url}. Not thread-safe: one client per conversation.
 */
public final class LlmClient implements LlmChat
{
	/** The wire format understood by the configured {@code url}. */
	public enum Format
	{
		ANTHROPIC, OPENAI_CHAT
	}

	/** Why the model stopped, normalised across both formats. */
	public enum StopReason
	{
		/** A plain end of turn ({@code end_turn}, {@code stop_sequence}, {@code stop}): nothing more for the caller to do. */
		END,
		/** The model asked to call one or more tools ({@code tool_use}, {@code tool_calls}, {@code function_call}). */
		TOOL_USE,
		/** The answer was cut off at {@code max_tokens} ({@code max_tokens}, {@code length}). */
		MAX_TOKENS,
		/** Any other reason ({@code content_filter}, an unrecognised value, or none reported). */
		OTHER
	}

	/** A tool as offered to the model: the neutral shape both formats serialise from. */
	public static final class ToolSpec
	{
		public final String name;
		public final String description;
		public final JsonNode inputSchema;

		public ToolSpec(final String pName, final String pDescription, final JsonNode pInputSchema)
		{
			this.name = pName;
			this.description = pDescription == null ? "" : pDescription;
			this.inputSchema = pInputSchema == null ? LlmClient.MAPPER.createObjectNode().put("type", "object") : pInputSchema;
		}
	}

	/** One tool call the model asked for. */
	public static final class ToolCall
	{
		public final String id;
		public final String name;
		public final JsonNode arguments;

		public ToolCall(final String pId, final String pName, final JsonNode pArguments)
		{
			this.id = pId;
			this.name = pName;
			this.arguments = pArguments == null ? LlmClient.MAPPER.createObjectNode() : pArguments;
		}
	}

	/** One tool result being sent back to the model. {@code isError} has no direct OpenAI equivalent; see the class Javadoc. */
	public static final class ToolResult
	{
		public final String toolCallId;
		public final String text;
		public final boolean isError;

		public ToolResult(final String pToolCallId, final String pText, final boolean pIsError)
		{
			this.toolCallId = pToolCallId;
			this.text = pText == null ? "" : pText;
			this.isError = pIsError;
		}
	}

	/** What the model answered on one turn: text, any tool calls, why it stopped, and the reported token usage. */
	public static final class Turn
	{
		public final String text;
		public final List<ToolCall> toolCalls;
		public final StopReason stopReason;
		public final int inputTokens;
		public final int outputTokens;

		/** Public since the turn interface {@link LlmChat}: an implementation other than this client builds its turns itself. */
		public Turn(final String pText, final List<ToolCall> pToolCalls, final StopReason pStopReason, final int pInputTokens, final int pOutputTokens)
		{
			this.text = pText;
			this.toolCalls = pToolCalls == null ? Collections.<ToolCall>emptyList() : pToolCalls;
			this.stopReason = pStopReason;
			this.inputTokens = pInputTokens;
			this.outputTokens = pOutputTokens;
		}
	}

	/**
	 * One entry of the provider-neutral conversation, replayed into the wire format on every call (both
	 * formats are stateless: the full transcript is resent each turn). Built with the static factories below;
	 * a {@link Turn} the model answered becomes the next {@code assistant} entry via {@link #assistant}.
	 */
	public static final class Message
	{
		enum Kind
		{
			USER, ASSISTANT, TOOL_RESULTS
		}

		final Kind kind;
		final String text;
		final List<ToolCall> toolCalls;
		final List<ToolResult> results;

		private Message(final Kind pKind, final String pText, final List<ToolCall> pToolCalls, final List<ToolResult> pResults)
		{
			this.kind = pKind;
			this.text = pText;
			this.toolCalls = pToolCalls;
			this.results = pResults;
		}

		/**
		 * The role of this entry as the provider-neutral contract {@code llm.chat} of ebx-connectors names it: {@code user},
		 * {@code assistant}, or {@code tool} (the results of the preceding assistant turn's tool calls).
		 */
		public String role()
		{
			return this.kind == Kind.USER ? "user" : this.kind == Kind.ASSISTANT ? "assistant" : "tool";
		}

		/** The text of a user or assistant entry (may be null or empty for an assistant entry that only called tools); null for tool results. */
		public String getText()
		{
			return this.text;
		}

		/** The tool calls of an assistant entry; empty otherwise. */
		public List<ToolCall> getToolCalls()
		{
			return this.toolCalls;
		}

		/** The tool results of a {@code tool} entry; empty otherwise. */
		public List<ToolResult> getResults()
		{
			return this.results;
		}

		/** A plain user turn. */
		public static Message user(final String pText)
		{
			return new Message(Kind.USER, pText == null ? "" : pText, Collections.<ToolCall>emptyList(), Collections.<ToolResult>emptyList());
		}

		/** What the model answered on a turn (its text and/or its tool calls), replayed back as conversation history. */
		public static Message assistant(final String pText, final List<ToolCall> pToolCalls)
		{
			return new Message(Kind.ASSISTANT, pText, pToolCalls == null ? Collections.<ToolCall>emptyList() : pToolCalls, Collections.<ToolResult>emptyList());
		}

		/**
		 * The results of every tool call from the preceding assistant turn. One {@link Message} of this kind
		 * serialises as one {@code user} message with several {@code tool_result} blocks for
		 * {@link Format#ANTHROPIC}, or as that many separate {@code role:"tool"} messages for
		 * {@link Format#OPENAI_CHAT} — the wire shapes disagree on this, so the neutral model groups them.
		 */
		public static Message toolResults(final List<ToolResult> pResults)
		{
			return new Message(Kind.TOOL_RESULTS, null, Collections.<ToolCall>emptyList(), pResults == null ? Collections.<ToolResult>emptyList() : pResults);
		}
	}

	/** {@link Format#OPENAI_CHAT}'s token-limit field when the constructor is not told otherwise; identical to {@code LlmConnector}'s own default. */
	public static final String DEFAULT_OPENAI_MAX_TOKENS_PARAM = "max_tokens";
	/** The {@code reasoning_effort} value the one-time fallback of {@link #send} switches to. */
	public static final String REASONING_EFFORT_NONE = "none";

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int MAX_ATTEMPTS = 3;
	private static final long BASE_BACKOFF_MS = 200L;

	private final Format format;
	private final String url;
	private final Map<String, String> headers = new LinkedHashMap<String, String>();
	private final String model;
	private final int maxTokens;
	private final String openAiMaxTokensParam;
	private final int connectTimeoutMs;
	private final int readTimeoutMs;
	/** {@code reasoning_effort} for {@link Format#OPENAI_CHAT}, null = not sent; switched to {@code none} by the one-time fallback of {@link #send}. */
	private String openAiReasoningEffort;
	/** The full body of the last HTTP error answer, read by {@link #send} to decide the {@code reasoning_effort} fallback. */
	private String lastErrorBody;

	/** Same as the other constructor, with {@link #DEFAULT_OPENAI_MAX_TOKENS_PARAM} as the OpenAI-shaped token-limit field name. */
	public LlmClient(final Format pFormat, final String pUrl, final Map<String, String> pHeaders, final String pModel, final int pMaxTokens,
		final int pConnectTimeoutMs, final int pReadTimeoutMs)
	{
		this(pFormat, pUrl, pHeaders, pModel, pMaxTokens, pConnectTimeoutMs, pReadTimeoutMs, LlmClient.DEFAULT_OPENAI_MAX_TOKENS_PARAM);
	}

	/**
	 * @param pHeaders sent with every request: authentication and anything else the endpoint needs. This
	 * class never inspects or logs them; may be null.
	 * @param pOpenAiMaxTokensParam the request field name that carries {@code pMaxTokens} for
	 * {@link Format#OPENAI_CHAT} ({@code max_tokens} or {@code max_completion_tokens} — see the class
	 * Javadoc); ignored for {@link Format#ANTHROPIC}, which always uses {@code max_tokens}. Null or blank
	 * falls back to {@link #DEFAULT_OPENAI_MAX_TOKENS_PARAM}.
	 */
	public LlmClient(final Format pFormat, final String pUrl, final Map<String, String> pHeaders, final String pModel, final int pMaxTokens,
		final int pConnectTimeoutMs, final int pReadTimeoutMs, final String pOpenAiMaxTokensParam)
	{
		this(pFormat, pUrl, pHeaders, pModel, pMaxTokens, pConnectTimeoutMs, pReadTimeoutMs, pOpenAiMaxTokensParam, null);
	}

	/**
	 * Same as the 8-argument constructor, plus an explicit {@code reasoning_effort}.
	 *
	 * @param pOpenAiReasoningEffort the {@code reasoning_effort} value sent with every {@link Format#OPENAI_CHAT}
	 * request ({@code none}, {@code low}, {@code medium}, {@code high}... as the provider documents it; passed
	 * through as-is); null or blank = the field is not sent (the default). Ignored for {@link Format#ANTHROPIC}.
	 */
	public LlmClient(final Format pFormat, final String pUrl, final Map<String, String> pHeaders, final String pModel, final int pMaxTokens,
		final int pConnectTimeoutMs, final int pReadTimeoutMs, final String pOpenAiMaxTokensParam, final String pOpenAiReasoningEffort)
	{
		this.format = pFormat;
		this.url = pUrl;
		if (pHeaders != null)
		{
			this.headers.putAll(pHeaders);
		}
		this.model = pModel;
		this.maxTokens = pMaxTokens;
		this.openAiMaxTokensParam = pOpenAiMaxTokensParam == null || pOpenAiMaxTokensParam.trim().isEmpty() ? LlmClient.DEFAULT_OPENAI_MAX_TOKENS_PARAM
			: pOpenAiMaxTokensParam.trim();
		this.connectTimeoutMs = pConnectTimeoutMs;
		this.readTimeoutMs = pReadTimeoutMs;
		this.openAiReasoningEffort = pOpenAiReasoningEffort == null || pOpenAiReasoningEffort.trim().isEmpty() ? null : pOpenAiReasoningEffort.trim();
	}

	/**
	 * Sends the whole conversation (the provider APIs are stateless) plus the allowed tools and gets back one
	 * turn. Retries HTTP 429 and 5xx with backoff, up to 3 attempts total, honouring a {@code Retry-After}
	 * response header given in seconds; any other error, or the last of 3 failed attempts, becomes an
	 * {@link LlmException} carrying the status and a shortened body — never the request headers. For
	 * {@link Format#OPENAI_CHAT} with tools, one HTTP 400 asking for {@code reasoning_effort} {@code none} is
	 * answered by resending once with that value (see the class Javadoc), kept for the rest of the conversation.
	 */
	@Override
	public Turn send(final String pSystem, final List<ToolSpec> pTools, final List<Message> pConversation) throws LlmException
	{
		if (this.format == Format.ANTHROPIC)
		{
			return LlmClient.anthropicTurn(this.postWithRetry(this.anthropicRequest(pSystem, pTools, pConversation)));
		}
		JsonNode answer;
		try
		{
			answer = this.postWithRetry(this.openAiRequest(pSystem, pTools, pConversation));
		}
		catch (final LlmException ex)
		{
			if (ex.getStatus() != 400 || pTools == null || pTools.isEmpty() || LlmClient.REASONING_EFFORT_NONE.equals(this.openAiReasoningEffort)
				|| !LlmClient.asksForNoReasoningEffort(this.lastErrorBody))
			{
				throw ex;
			}
			this.openAiReasoningEffort = LlmClient.REASONING_EFFORT_NONE;
			answer = this.postWithRetry(this.openAiRequest(pSystem, pTools, pConversation));
		}
		return LlmClient.openAiTurn(answer);
	}

	/** The {@code reasoning_effort} currently sent for {@link Format#OPENAI_CHAT} (null = not sent); {@code none} once the fallback of {@link #send} fired. */
	public String getOpenAiReasoningEffort()
	{
		return this.openAiReasoningEffort;
	}

	/** True when an HTTP 400 body is the provider's "function tools need reasoning_effort none" refusal (it names both). */
	public static boolean asksForNoReasoningEffort(final String pErrorBody)
	{
		if (pErrorBody == null)
		{
			return false;
		}
		final String body = pErrorBody.toLowerCase(java.util.Locale.ENGLISH);
		return body.contains("reasoning_effort") && body.contains("none");
	}

	// ---- Anthropic Messages API ----

	/**
	 * The request body of one turn in the wire format {@code pFormat}, without any transport: the adapters of this client,
	 * shared with a caller that sends the request through its own HTTP stack (ebx-connectors' {@code llm.chat} sends it
	 * with the connector's client, key and outbound rules). {@code pOpenAiMaxTokensParam} (null or blank:
	 * {@link #DEFAULT_OPENAI_MAX_TOKENS_PARAM}) and {@code pOpenAiReasoningEffort} (null or blank: not sent) are ignored for
	 * {@link Format#ANTHROPIC}. Pure.
	 */
	public static ObjectNode request(final Format pFormat, final String pModel, final int pMaxTokens, final String pOpenAiMaxTokensParam,
		final String pOpenAiReasoningEffort, final String pSystem, final List<ToolSpec> pTools, final List<Message> pConversation)
	{
		if (pFormat == Format.ANTHROPIC)
		{
			return LlmClient.anthropicRequest(pModel, pMaxTokens, pSystem, pTools, pConversation);
		}
		final String param = pOpenAiMaxTokensParam == null || pOpenAiMaxTokensParam.trim().isEmpty() ? LlmClient.DEFAULT_OPENAI_MAX_TOKENS_PARAM : pOpenAiMaxTokensParam.trim();
		final String effort = pOpenAiReasoningEffort == null || pOpenAiReasoningEffort.trim().isEmpty() ? null : pOpenAiReasoningEffort.trim();
		return LlmClient.openAiRequest(pModel, pMaxTokens, param, effort, pSystem, pTools, pConversation);
	}

	/** The turn a provider answered in the wire format {@code pFormat} (the other half of {@link #request}). Pure. */
	public static Turn turnOf(final Format pFormat, final JsonNode pAnswer)
	{
		final JsonNode answer = pAnswer == null ? LlmClient.MAPPER.createObjectNode() : pAnswer;
		return pFormat == Format.ANTHROPIC ? LlmClient.anthropicTurn(answer) : LlmClient.openAiTurn(answer);
	}

	private ObjectNode anthropicRequest(final String pSystem, final List<ToolSpec> pTools, final List<Message> pConversation)
	{
		return LlmClient.anthropicRequest(this.model, this.maxTokens, pSystem, pTools, pConversation);
	}

	private static ObjectNode anthropicRequest(final String pModel, final int pMaxTokens, final String pSystem, final List<ToolSpec> pTools,
		final List<Message> pConversation)
	{
		final ObjectNode body = LlmClient.MAPPER.createObjectNode();
		body.put("model", pModel);
		body.put("max_tokens", pMaxTokens);
		body.put("system", pSystem == null ? "" : pSystem);
		if (pTools != null && !pTools.isEmpty())
		{
			final ArrayNode tools = body.putArray("tools");
			for (final ToolSpec tool : pTools)
			{
				final ObjectNode t = tools.addObject();
				t.put("name", tool.name);
				t.put("description", tool.description);
				t.set("input_schema", tool.inputSchema);
			}
		}
		final ArrayNode messages = body.putArray("messages");
		for (final Message message : pConversation)
		{
			if (message.kind == Message.Kind.USER)
			{
				final ObjectNode m = messages.addObject();
				m.put("role", "user");
				m.put("content", message.text);
			}
			else if (message.kind == Message.Kind.ASSISTANT)
			{
				final ObjectNode m = messages.addObject();
				m.put("role", "assistant");
				final ArrayNode content = m.putArray("content");
				if (message.text != null && !message.text.isEmpty())
				{
					content.addObject().put("type", "text").put("text", message.text);
				}
				for (final ToolCall call : message.toolCalls)
				{
					final ObjectNode block = content.addObject();
					block.put("type", "tool_use");
					block.put("id", call.id);
					block.put("name", call.name);
					block.set("input", call.arguments);
				}
			}
			else
			{
				final ObjectNode m = messages.addObject();
				m.put("role", "user");
				final ArrayNode content = m.putArray("content");
				for (final ToolResult result : message.results)
				{
					final ObjectNode block = content.addObject();
					block.put("type", "tool_result");
					block.put("tool_use_id", result.toolCallId);
					block.put("content", result.text);
					if (result.isError)
					{
						block.put("is_error", true);
					}
				}
			}
		}
		return body;
	}

	private static Turn anthropicTurn(final JsonNode pAnswer)
	{
		final StringBuilder text = new StringBuilder();
		final List<ToolCall> calls = new ArrayList<ToolCall>();
		for (final JsonNode block : pAnswer.path("content"))
		{
			final String type = block.path("type").asText();
			if ("text".equals(type))
			{
				text.append(block.path("text").asText());
			}
			else if ("tool_use".equals(type))
			{
				calls.add(new ToolCall(block.path("id").asText(), block.path("name").asText(), block.get("input")));
			}
		}
		final String stop = pAnswer.path("stop_reason").asText("");
		final StopReason reason;
		if ("tool_use".equals(stop))
		{
			reason = StopReason.TOOL_USE;
		}
		else if ("end_turn".equals(stop) || "stop_sequence".equals(stop))
		{
			reason = StopReason.END;
		}
		else if ("max_tokens".equals(stop))
		{
			reason = StopReason.MAX_TOKENS;
		}
		else
		{
			reason = StopReason.OTHER;
		}
		return new Turn(text.toString(), calls, reason, pAnswer.path("usage").path("input_tokens").asInt(0), pAnswer.path("usage").path("output_tokens").asInt(0));
	}

	// ---- OpenAI-shaped chat completions ----

	private ObjectNode openAiRequest(final String pSystem, final List<ToolSpec> pTools, final List<Message> pConversation)
	{
		return LlmClient.openAiRequest(this.model, this.maxTokens, this.openAiMaxTokensParam, this.openAiReasoningEffort, pSystem, pTools, pConversation);
	}

	private static ObjectNode openAiRequest(final String pModel, final int pMaxTokens, final String pMaxTokensParam, final String pReasoningEffort,
		final String pSystem, final List<ToolSpec> pTools, final List<Message> pConversation)
	{
		final ObjectNode body = LlmClient.MAPPER.createObjectNode();
		body.put("model", pModel);
		body.put(pMaxTokensParam, pMaxTokens);
		if (pReasoningEffort != null)
		{
			body.put("reasoning_effort", pReasoningEffort);
		}
		if (pTools != null && !pTools.isEmpty())
		{
			final ArrayNode tools = body.putArray("tools");
			for (final ToolSpec tool : pTools)
			{
				final ObjectNode t = tools.addObject();
				t.put("type", "function");
				final ObjectNode function = t.putObject("function");
				function.put("name", tool.name);
				function.put("description", tool.description);
				function.set("parameters", tool.inputSchema);
			}
		}
		final ArrayNode messages = body.putArray("messages");
		final ObjectNode system = messages.addObject();
		system.put("role", "system");
		system.put("content", pSystem == null ? "" : pSystem);
		for (final Message message : pConversation)
		{
			if (message.kind == Message.Kind.USER)
			{
				final ObjectNode m = messages.addObject();
				m.put("role", "user");
				m.put("content", message.text);
			}
			else if (message.kind == Message.Kind.ASSISTANT)
			{
				final ObjectNode m = messages.addObject();
				m.put("role", "assistant");
				if (message.text == null || message.text.isEmpty())
				{
					m.putNull("content");
				}
				else
				{
					m.put("content", message.text);
				}
				if (!message.toolCalls.isEmpty())
				{
					final ArrayNode toolCalls = m.putArray("tool_calls");
					for (final ToolCall call : message.toolCalls)
					{
						final ObjectNode t = toolCalls.addObject();
						t.put("id", call.id);
						t.put("type", "function");
						final ObjectNode function = t.putObject("function");
						function.put("name", call.name);
						function.put("arguments", LlmClient.write(call.arguments));
					}
				}
			}
			else
			{
				for (final ToolResult result : message.results)
				{
					final ObjectNode m = messages.addObject();
					m.put("role", "tool");
					m.put("tool_call_id", result.toolCallId);
					m.put("content", result.isError ? "ERROR: " + result.text : result.text);
				}
			}
		}
		return body;
	}

	private static Turn openAiTurn(final JsonNode pAnswer)
	{
		final JsonNode message = pAnswer.path("choices").path(0).path("message");
		final String text = message.path("content").isTextual() ? message.path("content").asText() : "";
		final List<ToolCall> calls = new ArrayList<ToolCall>();
		for (final JsonNode call : message.path("tool_calls"))
		{
			final String argsText = call.path("function").path("arguments").asText("");
			JsonNode arguments;
			try
			{
				arguments = LlmClient.MAPPER.readTree(argsText);
			}
			catch (final IOException ex)
			{
				arguments = LlmClient.MAPPER.createObjectNode().put("_raw", argsText);
			}
			calls.add(new ToolCall(call.path("id").asText(), call.path("function").path("name").asText(), arguments));
		}
		final String finish = pAnswer.path("choices").path(0).path("finish_reason").asText("");
		final StopReason reason;
		if ("tool_calls".equals(finish) || "function_call".equals(finish))
		{
			reason = StopReason.TOOL_USE;
		}
		else if ("stop".equals(finish))
		{
			reason = StopReason.END;
		}
		else if ("length".equals(finish))
		{
			reason = StopReason.MAX_TOKENS;
		}
		else
		{
			reason = StopReason.OTHER;
		}
		return new Turn(text, calls, reason, pAnswer.path("usage").path("prompt_tokens").asInt(0), pAnswer.path("usage").path("completion_tokens").asInt(0));
	}

	// ---- transport ----

	private JsonNode postWithRetry(final ObjectNode pBody) throws LlmException
	{
		final byte[] bytes;
		try
		{
			bytes = LlmClient.MAPPER.writeValueAsBytes(pBody);
		}
		catch (final IOException ex)
		{
			throw new LlmException(this.url + ": could not encode the request", ex);
		}
		IOException lastTransportFailure = null;
		this.lastErrorBody = null;
		for (int attempt = 1; attempt <= LlmClient.MAX_ATTEMPTS; attempt++)
		{
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
				for (final Map.Entry<String, String> header : this.headers.entrySet())
				{
					connection.setRequestProperty(header.getKey(), header.getValue());
				}
				final OutputStream out = connection.getOutputStream();
				try
				{
					out.write(bytes);
				}
				finally
				{
					out.close();
				}
				final int status = connection.getResponseCode();
				final InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
				final String responseBody = stream == null ? "" : LlmClient.read(stream);
				if ((status == 429 || status >= 500) && attempt < LlmClient.MAX_ATTEMPTS)
				{
					LlmClient.sleep(LlmClient.backoffMs(attempt, connection.getHeaderField("Retry-After")));
					continue;
				}
				if (status >= 400)
				{
					this.lastErrorBody = responseBody;
					throw new LlmException(status, "HTTP " + status + " from " + this.url + (responseBody.isEmpty() ? "" : ": " + LlmClient.shorten(responseBody)));
				}
				return LlmClient.MAPPER.readTree(responseBody);
			}
			catch (final IOException ex)
			{
				lastTransportFailure = ex;
				if (attempt == LlmClient.MAX_ATTEMPTS)
				{
					throw new LlmException("LLM provider unreachable at " + this.url + ": " + ex, ex);
				}
				LlmClient.sleep(LlmClient.backoffMs(attempt, null));
			}
			finally
			{
				if (connection != null)
				{
					connection.disconnect();
				}
			}
		}
		// unreachable (the loop always returns or throws), kept for the compiler
		throw new LlmException("LLM provider unreachable at " + this.url, lastTransportFailure);
	}

	/** Backoff before the next attempt: {@code Retry-After} (seconds) when the server sent one and it parses, else exponential from {@link #BASE_BACKOFF_MS}. */
	private static long backoffMs(final int pAttempt, final String pRetryAfterSeconds)
	{
		if (pRetryAfterSeconds != null)
		{
			try
			{
				return Long.parseLong(pRetryAfterSeconds.trim()) * 1000L;
			}
			catch (final NumberFormatException ex)
			{
				// an HTTP-date form, or garbage: fall back to the exponential default below
			}
		}
		return LlmClient.BASE_BACKOFF_MS << (pAttempt - 1);
	}

	private static void sleep(final long pMs)
	{
		try
		{
			Thread.sleep(pMs);
		}
		catch (final InterruptedException ex)
		{
			Thread.currentThread().interrupt();
		}
	}

	private static String write(final JsonNode pNode)
	{
		try
		{
			return LlmClient.MAPPER.writeValueAsString(pNode);
		}
		catch (final IOException ex)
		{
			return "{}";
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
