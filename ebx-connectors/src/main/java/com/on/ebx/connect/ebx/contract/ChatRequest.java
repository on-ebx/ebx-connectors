package com.on.ebx.connect.ebx.contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.connect.core.Json;

/**
 * The parameters of {@code llm.chat} (contract 1.1.0) checked item by item and turned into the agent library's
 * provider-neutral conversation ({@link LlmClient.Message}, {@link LlmClient.ToolSpec}), which the language-model
 * connector then sends in its provider's wire format. Pure: every refusal is tested offline. Each refusal is
 * {@link ContractError#INVALID_ARGUMENT} and names the item ({@code messages[3].toolCallId}).
 * <p>
 * What is checked, beyond {@link ContractCatalog#validate} (types and number of items): only the known fields of each
 * item, each of its type; the role; a user message has a content; an assistant message has a content or tool calls;
 * the first message is a user's and the last a user's or tool results (the model answers next); the tool messages
 * right after an assistant message answer each of its tool calls exactly once, by id, before any other message (both
 * provider formats refuse anything else); the sizes; the tool names (the pattern both providers accept), unique; each
 * input schema an object of type object.
 */
public final class ChatRequest
{
	private static final Set<String> USER_FIELDS = new HashSet<String>(Arrays.asList("role", "content"));
	private static final Set<String> ASSISTANT_FIELDS = new HashSet<String>(Arrays.asList("role", "content", "toolCalls"));
	private static final Set<String> TOOL_FIELDS = new HashSet<String>(Arrays.asList("role", "content", "toolCallId", "isError"));
	private static final Set<String> CALL_FIELDS = new HashSet<String>(Arrays.asList("id", "name", "arguments"));
	private static final Set<String> SPEC_FIELDS = new HashSet<String>(Arrays.asList("name", "description", "inputSchema"));
	private static final String TOOL_NAME = "[A-Za-z0-9_-]{1,64}";
	private static final int ID_MAX_LENGTH = 200;

	/** The conversation, as the agent library's neutral messages (consecutive tool messages grouped as one tool-results entry). */
	public final List<LlmClient.Message> conversation;
	public final List<LlmClient.ToolSpec> tools;
	/** Number of messages as the caller sent them (for the audit line; never their content). */
	public final int messageCount;

	private ChatRequest(final List<LlmClient.Message> pConversation, final List<LlmClient.ToolSpec> pTools, final int pMessageCount)
	{
		this.conversation = Collections.unmodifiableList(pConversation);
		this.tools = Collections.unmodifiableList(pTools);
		this.messageCount = pMessageCount;
	}

	/** Pure: the {@code messages} and {@code tools} of validated {@code llm.chat} parameters, checked item by item. */
	public static ChatRequest parse(final JsonNode pArgs) throws ContractException
	{
		final List<LlmClient.ToolSpec> tools = ChatRequest.tools(pArgs.get("tools"));
		final JsonNode messages = pArgs.get("messages");
		if (messages == null || !messages.isArray() || messages.size() == 0)
		{
			throw ChatRequest.refused("messages", "must be a non-empty array");
		}
		final List<LlmClient.Message> conversation = new ArrayList<LlmClient.Message>();
		long total = 0L;
		// the ids of the last assistant message's tool calls still waiting for their result
		Set<String> pending = Collections.<String>emptySet();
		List<LlmClient.ToolResult> results = null;
		for (int i = 0; i < messages.size(); i++)
		{
			final String where = "messages[" + i + "]";
			final JsonNode message = messages.get(i);
			if (message == null || !message.isObject())
			{
				throw ChatRequest.refused(where, "must be a JSON object");
			}
			final String role = message.path("role").isTextual() ? message.path("role").asText() : null;
			if (!"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role))
			{
				throw ChatRequest.refused(where + ".role", "must be user, assistant or tool");
			}
			ChatRequest.onlyKnown(message, "user".equals(role) ? ChatRequest.USER_FIELDS : "assistant".equals(role) ? ChatRequest.ASSISTANT_FIELDS
				: ChatRequest.TOOL_FIELDS, where);
			if (i == 0 && !"user".equals(role))
			{
				throw ChatRequest.refused(where, "the first message must be a user message");
			}
			if (!"tool".equals(role))
			{
				if (!pending.isEmpty())
				{
					throw ChatRequest.refused(where, "the tool calls " + pending + " of the previous assistant message are not all answered: "
						+ "send one tool message per call (toolCallId) right after it");
				}
				if (results != null)
				{
					conversation.add(LlmClient.Message.toolResults(results));
					results = null;
				}
			}
			final String content = ChatRequest.content(message, where, "user".equals(role) || "tool".equals(role), !"tool".equals(role));
			total += content == null ? 0 : content.length();
			if ("user".equals(role))
			{
				conversation.add(LlmClient.Message.user(content));
			}
			else if ("assistant".equals(role))
			{
				final List<LlmClient.ToolCall> calls = new ArrayList<LlmClient.ToolCall>();
				final Set<String> ids = new LinkedHashSet<String>();
				final JsonNode toolCalls = message.get("toolCalls");
				if (toolCalls != null && !toolCalls.isNull())
				{
					if (!toolCalls.isArray())
					{
						throw ChatRequest.refused(where + ".toolCalls", "must be a JSON array");
					}
					for (int c = 0; c < toolCalls.size(); c++)
					{
						final String at = where + ".toolCalls[" + c + "]";
						final JsonNode call = toolCalls.get(c);
						if (call == null || !call.isObject())
						{
							throw ChatRequest.refused(at, "must be a JSON object");
						}
						ChatRequest.onlyKnown(call, ChatRequest.CALL_FIELDS, at);
						final String id = ChatRequest.text(call, "id", at, ChatRequest.ID_MAX_LENGTH);
						final String name = ChatRequest.text(call, "name", at, 64);
						if (!name.matches(ChatRequest.TOOL_NAME))
						{
							throw ChatRequest.refused(at + ".name", "must be letters, digits, _ or -, 64 at most");
						}
						final JsonNode arguments = call.get("arguments");
						if (arguments != null && !arguments.isNull() && !arguments.isObject())
						{
							throw ChatRequest.refused(at + ".arguments", "must be a JSON object");
						}
						if (!ids.add(id))
						{
							throw ChatRequest.refused(at + ".id", "'" + id + "' is used twice in the same message");
						}
						total += Json.write(arguments == null || arguments.isNull() ? Json.object() : arguments).length();
						calls.add(new LlmClient.ToolCall(id, name, arguments == null || arguments.isNull() ? Json.object() : arguments));
					}
				}
				if ((content == null || content.isEmpty()) && calls.isEmpty())
				{
					throw ChatRequest.refused(where, "an assistant message needs a content or tool calls");
				}
				conversation.add(LlmClient.Message.assistant(content, calls));
				pending = ids;
			}
			else
			{
				final String id = ChatRequest.text(message, "toolCallId", where, ChatRequest.ID_MAX_LENGTH);
				if (!pending.contains(id))
				{
					throw ChatRequest.refused(where + ".toolCallId", "'" + id + "' answers no tool call of the assistant message just before "
						+ "(or one already answered)");
				}
				final JsonNode isError = message.get("isError");
				if (isError != null && !isError.isNull() && !isError.isBoolean())
				{
					throw ChatRequest.refused(where + ".isError", "must be true or false");
				}
				pending = new LinkedHashSet<String>(pending);
				pending.remove(id);
				if (results == null)
				{
					results = new ArrayList<LlmClient.ToolResult>();
				}
				results.add(new LlmClient.ToolResult(id, content, isError != null && isError.asBoolean(false)));
			}
			if (total > ContractCatalog.CHAT_TOTAL_MAX_LENGTH)
			{
				throw ChatRequest.refused("messages", "carry more than " + ContractCatalog.CHAT_TOTAL_MAX_LENGTH + " characters in all");
			}
		}
		if (!pending.isEmpty())
		{
			throw ChatRequest.refused("messages[" + (messages.size() - 1) + "]", "the tool calls " + pending
				+ " of the last assistant message are not answered: the model answers after a user message or the results of every tool call");
		}
		if (results != null)
		{
			conversation.add(LlmClient.Message.toolResults(results));
		}
		final String last = messages.get(messages.size() - 1).path("role").asText();
		if ("assistant".equals(last))
		{
			throw ChatRequest.refused("messages[" + (messages.size() - 1) + "]", "the last message must be a user or tool message: the model answers next");
		}
		return new ChatRequest(conversation, tools, messages.size());
	}

	private static List<LlmClient.ToolSpec> tools(final JsonNode pTools) throws ContractException
	{
		final List<LlmClient.ToolSpec> out = new ArrayList<LlmClient.ToolSpec>();
		if (pTools == null || pTools.isNull())
		{
			return out;
		}
		if (!pTools.isArray())
		{
			throw ChatRequest.refused("tools", "must be a JSON array");
		}
		final Set<String> names = new HashSet<String>();
		for (int i = 0; i < pTools.size(); i++)
		{
			final String where = "tools[" + i + "]";
			final JsonNode tool = pTools.get(i);
			if (tool == null || !tool.isObject())
			{
				throw ChatRequest.refused(where, "must be a JSON object");
			}
			ChatRequest.onlyKnown(tool, ChatRequest.SPEC_FIELDS, where);
			final String name = ChatRequest.text(tool, "name", where, 64);
			if (!name.matches(ChatRequest.TOOL_NAME))
			{
				throw ChatRequest.refused(where + ".name", "must be letters, digits, _ or -, 64 at most");
			}
			if (!names.add(name))
			{
				throw ChatRequest.refused(where + ".name", "'" + name + "' is offered twice");
			}
			final JsonNode description = tool.get("description");
			if (description != null && !description.isNull() && (!description.isTextual() || description.asText().length() > ContractCatalog.TOOL_DESCRIPTION_MAX_LENGTH))
			{
				throw ChatRequest.refused(where + ".description", "must be a string of at most " + ContractCatalog.TOOL_DESCRIPTION_MAX_LENGTH + " characters");
			}
			final JsonNode schema = tool.get("inputSchema");
			if (schema != null && !schema.isNull() && (!schema.isObject() || !"object".equals(schema.path("type").asText())))
			{
				throw ChatRequest.refused(where + ".inputSchema", "must be a JSON Schema object of type object ({\"type\": \"object\", \"properties\": {...}})");
			}
			out.add(new LlmClient.ToolSpec(name, description == null || description.isNull() ? "" : description.asText(),
				schema == null || schema.isNull() ? null : schema));
		}
		return out;
	}

	/** The content of a message: required (user, tool) or optional (assistant); blank refused for a user message. */
	private static String content(final JsonNode pMessage, final String pWhere, final boolean pRequired, final boolean pNotBlank) throws ContractException
	{
		final JsonNode content = pMessage.get("content");
		if (content == null || content.isNull())
		{
			if (pRequired)
			{
				throw ChatRequest.refused(pWhere + ".content", "is required");
			}
			return null;
		}
		if (!content.isTextual())
		{
			throw ChatRequest.refused(pWhere + ".content", "must be a string");
		}
		final String text = content.asText();
		if (pRequired && pNotBlank && text.trim().isEmpty())
		{
			throw ChatRequest.refused(pWhere + ".content", "must not be blank");
		}
		if (text.length() > ContractCatalog.CHAT_CONTENT_MAX_LENGTH)
		{
			throw ChatRequest.refused(pWhere + ".content", "is longer than " + ContractCatalog.CHAT_CONTENT_MAX_LENGTH + " characters");
		}
		return text;
	}

	private static String text(final JsonNode pNode, final String pField, final String pWhere, final int pMaxLength) throws ContractException
	{
		final JsonNode value = pNode.get(pField);
		if (value == null || !value.isTextual() || value.asText().trim().isEmpty())
		{
			throw ChatRequest.refused(pWhere + "." + pField, "is required, a non-blank string");
		}
		if (value.asText().length() > pMaxLength)
		{
			throw ChatRequest.refused(pWhere + "." + pField, "is longer than " + pMaxLength + " characters");
		}
		return value.asText();
	}

	private static void onlyKnown(final JsonNode pNode, final Set<String> pKnown, final String pWhere) throws ContractException
	{
		for (final Iterator<Map.Entry<String, JsonNode>> it = pNode.fields(); it.hasNext();)
		{
			final String name = it.next().getKey();
			if (!pKnown.contains(name))
			{
				throw ChatRequest.refused(pWhere, "unknown field '" + name + "'");
			}
		}
	}

	private static ContractException refused(final String pWhere, final String pWhy)
	{
		return new ContractException(ContractError.INVALID_ARGUMENT, ContractCatalog.LLM_CHAT + ": " + pWhere + " " + pWhy);
	}
}
