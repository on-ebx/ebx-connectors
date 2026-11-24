package com.on.ebx.connect.ebx.contract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.ContractLlmChat;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.agent.LlmException;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.connect.connectors.LlmConnector;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.orchestranetworks.service.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Contract 1.1.0 offline, against a local fake of the providers' chat APIs: the answer size capped at the connector's own
 * maxTokens (llm.ask and llm.chat), the validation of llm.chat's conversation item by item, a two-turn tool exchange in
 * both wire formats (Anthropic Messages, OpenAI chat completions) where the caller runs the tool and calls again, the
 * one-time reasoning_effort fallback, Google refused, and the agent library's {@link AgentLoop} driving the contract
 * through {@link ContractLlmChat} and the JSON-RPC transport, exactly as the MDM Factory will. No repository, no account.
 */
public final class ContractChatTest
{
	private static int checks;

	private ContractChatTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		ContractChatTest.capping();
		ContractChatTest.validation();
		ContractChatTest.libraryConversion();
		final String previousAllowList = System.getProperty(Egress.PROPERTY);
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final Fake fake = new Fake();
		server.createContext("/", fake);
		server.start();
		try
		{
			final String base = "http://127.0.0.1:" + server.getAddress().getPort();
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.getAddress().getPort());
			ContractChatTest.askCapped(fake, base);
			ContractChatTest.anthropicTwoTurns(fake, base);
			ContractChatTest.openAiTwoTurns(fake, base);
			ContractChatTest.reasoningEffortFallback(fake, base);
			ContractChatTest.otherProviders(fake, base);
			ContractChatTest.agentLoopThroughTheContract(fake, base);
		}
		finally
		{
			server.stop(0);
			if (previousAllowList == null)
			{
				System.clearProperty(Egress.PROPERTY);
			}
			else
			{
				System.setProperty(Egress.PROPERTY, previousAllowList);
			}
		}
		System.out.println("PASS: contract " + ContractCatalog.VERSION + " llm.chat and maxTokens capping (" + ContractChatTest.checks + " checks)");
	}

	// ------------------------------------------------------------------ pure

	private static void capping()
	{
		ContractService.MaxTokens size = ContractService.maxTokens(Integer.valueOf(65536), 2048);
		yes(size.applied == 2048 && size.capped, "asking more than the connector allows gets the connector's maxTokens, capped");
		size = ContractService.maxTokens(Integer.valueOf(2048), 2048);
		yes(size.applied == 2048 && !size.capped, "asking exactly the connector's is not capped");
		size = ContractService.maxTokens(Integer.valueOf(500), 2048);
		yes(size.applied == 500 && !size.capped, "asking less gets what is asked");
		size = ContractService.maxTokens(null, 2048);
		yes(size.applied == 2048 && !size.capped, "asking nothing gets the connector's, not capped");
		yes(LlmConnector.configuredMaxTokens(ContractChatTest.config("anthropic", "http://x", "k")) == 1024, "no maxTokens property: 1024");
		yes(LlmConnector.configuredMaxTokens(ContractChatTest.config("anthropic", "http://x", "k", "maxTokens", " 8192 ")) == 8192, "maxTokens property read");
		yes(LlmConnector.configuredMaxTokens(ContractChatTest.config("anthropic", "http://x", "k", "maxTokens", "4096.0")) == 4096, "a decimal form is read");
		yes(LlmConnector.configuredMaxTokens(ContractChatTest.config("anthropic", "http://x", "k", "maxTokens", "lots")) == 1024
			&& LlmConnector.configuredMaxTokens(ContractChatTest.config("anthropic", "http://x", "k", "maxTokens", "0")) == 1024, "not a positive number: the documented 1024");
		yes(LlmConnector.chatFormat("anthropic") == LlmClient.Format.ANTHROPIC && LlmConnector.chatFormat("ollama") == LlmClient.Format.OPENAI_CHAT
			&& LlmConnector.chatFormat("azure-ai") == LlmClient.Format.OPENAI_CHAT && LlmConnector.chatFormat("google") == null, "tool-use wire format per provider");
	}

	private static final String TOOLS = "\"tools\":[{\"name\":\"factory__lookup\",\"description\":\"Country of a code\",\"inputSchema\":{\"type\":\"object\","
		+ "\"properties\":{\"code\":{\"type\":\"string\"}},\"required\":[\"code\"]}}]";

	private static void validation() throws Exception
	{
		final ContractCatalog.Operation chat = ContractCatalog.operation(ContractCatalog.LLM_CHAT);
		yes(chat != null && "1.1.0".equals(chat.since) && chat.right == com.on.ebx.connect.ebx.ConnectorsAccess.Right.RUN, "llm.chat: since 1.1.0, right RUN");
		final ChatRequest ok = ContractChatTest.parse("{\"messages\":[{\"role\":\"user\",\"content\":\"Find FR\"},{\"role\":\"assistant\",\"content\":\"Looking\","
			+ "\"toolCalls\":[{\"id\":\"c1\",\"name\":\"factory__lookup\",\"arguments\":{\"code\":\"FR\"}},{\"id\":\"c2\",\"name\":\"factory__lookup\"}]},"
			+ "{\"role\":\"tool\",\"toolCallId\":\"c2\",\"content\":\"\",\"isError\":true},{\"role\":\"tool\",\"toolCallId\":\"c1\",\"content\":\"France\"}]," + ContractChatTest.TOOLS + "}");
		yes(ok.conversation.size() == 3 && "user".equals(ok.conversation.get(0).role()) && "assistant".equals(ok.conversation.get(1).role())
			&& "tool".equals(ok.conversation.get(2).role()), "consecutive tool messages become one tool-results entry: " + ok.conversation.size());
		yes(ok.conversation.get(1).getToolCalls().size() == 2 && ok.conversation.get(1).getToolCalls().get(1).arguments.isObject()
			&& ok.conversation.get(1).getToolCalls().get(1).arguments.size() == 0, "tool calls kept, missing arguments = {}");
		yes(ok.conversation.get(2).getResults().get(0).isError && "c2".equals(ok.conversation.get(2).getResults().get(0).toolCallId)
			&& "France".equals(ok.conversation.get(2).getResults().get(1).text), "results kept in order, isError carried, an empty result accepted");
		yes(ok.tools.size() == 1 && "factory__lookup".equals(ok.tools.get(0).name) && ok.messageCount == 4, "tools and the count of messages as sent");
		yes(ContractChatTest.parse("{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}").tools.isEmpty(), "no tools is allowed");

		final String user = "{\"role\":\"user\",\"content\":\"Find FR\"}";
		final String asks = "{\"role\":\"assistant\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"factory__lookup\",\"arguments\":{}}]}";
		ContractChatTest.chatRefused("{}", "'messages' is required");
		ContractChatTest.chatRefused("{\"messages\":[]}", "between 1 and");
		ContractChatTest.chatRefused("{\"messages\":{}}", "must be a JSON array");
		ContractChatTest.chatRefused("{\"messages\":[\"hello\"]}", "messages[0] must be a JSON object");
		ContractChatTest.chatRefused("{\"messages\":[{\"role\":\"system\",\"content\":\"x\"}]}", "messages[0].role must be user, assistant or tool");
		ContractChatTest.chatRefused("{\"messages\":[{\"role\":\"user\",\"content\":\"x\",\"name\":\"bob\"}]}", "messages[0] unknown field 'name'");
		ContractChatTest.chatRefused("{\"messages\":[{\"role\":\"user\"}]}", "messages[0].content is required");
		ContractChatTest.chatRefused("{\"messages\":[{\"role\":\"user\",\"content\":\"  \"}]}", "must not be blank");
		ContractChatTest.chatRefused("{\"messages\":[{\"role\":\"user\",\"content\":42}]}", "must be a string");
		ContractChatTest.chatRefused("{\"messages\":[{\"role\":\"assistant\",\"content\":\"x\"},{\"role\":\"user\",\"content\":\"y\"}]}", "the first message must be a user message");
		ContractChatTest.chatRefused("{\"messages\":[" + user + ",{\"role\":\"assistant\",\"content\":\"done\"}]}", "the last message must be a user or tool message");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "," + asks + "]}", "of the last assistant message are not answered");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "," + asks + "," + user + "]}", "are not all answered");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "," + asks + ",{\"role\":\"tool\",\"toolCallId\":\"c9\",\"content\":\"x\"}]}", "'c9' answers no tool call");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "," + asks + ",{\"role\":\"tool\",\"toolCallId\":\"c1\",\"content\":\"x\"},"
			+ "{\"role\":\"tool\",\"toolCallId\":\"c1\",\"content\":\"x\"}]}", "(or one already answered)");
		ContractChatTest.chatRefused("{\"messages\":[" + user + ",{\"role\":\"tool\",\"toolCallId\":\"c1\",\"content\":\"x\"}]}", "answers no tool call");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "," + asks + ",{\"role\":\"tool\",\"toolCallId\":\"c1\"}]}", "messages[2].content is required");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "," + asks + ",{\"role\":\"tool\",\"toolCallId\":\"c1\",\"content\":\"x\",\"isError\":\"yes\"}]}",
			"isError must be true or false");
		ContractChatTest.chatRefused("{\"messages\":[" + user + ",{\"role\":\"assistant\"}," + user + "]}", "needs a content or tool calls");
		ContractChatTest.chatRefused("{\"messages\":[" + user + ",{\"role\":\"assistant\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"a b\"}]}]}", "toolCalls[0].name must be letters");
		ContractChatTest.chatRefused("{\"messages\":[" + user + ",{\"role\":\"assistant\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"t\",\"arguments\":\"{}\"}]}]}",
			"arguments must be a JSON object");
		ContractChatTest.chatRefused("{\"messages\":[" + user + ",{\"role\":\"assistant\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"t\"},{\"id\":\"c1\",\"name\":\"t\"}]}]}",
			"is used twice");
		ContractChatTest.chatRefused("{\"messages\":[" + user + ",{\"role\":\"assistant\",\"toolCalls\":[{\"name\":\"t\"}]}]}", "toolCalls[0].id is required");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "],\"tools\":[{\"name\":\"t\",\"inputSchema\":{\"type\":\"string\"}}]}", "inputSchema must be a JSON Schema object of type object");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "],\"tools\":[{\"name\":\"t\"},{\"name\":\"t\"}]}", "'t' is offered twice");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "],\"tools\":[{\"name\":\"t.x\"}]}", "tools[0].name must be letters");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "],\"tools\":[{\"name\":\"t\",\"code\":\"x\"}]}", "tools[0] unknown field 'code'");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "],\"temperature\":0}", "unknown parameter 'temperature'");
		ContractChatTest.chatRefused("{\"messages\":[" + user + "],\"maxTokens\":65537}", "between 1 and");
		final StringBuilder many = new StringBuilder("{\"messages\":[");
		for (int i = 0; i <= ContractCatalog.CHAT_MESSAGES_MAX; i++)
		{
			many.append(i == 0 ? "" : ",").append(user);
		}
		ContractChatTest.chatRefused(many.append("]}").toString(), "between 1 and " + ContractCatalog.CHAT_MESSAGES_MAX + " items");
	}

	private static ChatRequest parse(final String pArgs) throws Exception
	{
		return ChatRequest.parse(ContractCatalog.validate(ContractCatalog.operation(ContractCatalog.LLM_CHAT), Json.parse(pArgs)));
	}

	private static void chatRefused(final String pArgs, final String pExpected) throws Exception
	{
		try
		{
			ContractChatTest.parse(pArgs);
			yes(false, pArgs + " must be refused");
		}
		catch (final ContractException ex)
		{
			yes(ex.getType() == ContractError.INVALID_ARGUMENT && ex.getMessage().contains(pExpected), "llm.chat " + pArgs + ": " + ex.getMessage());
		}
	}

	/** The agent library's side: the neutral conversation to the contract's messages and back. */
	private static void libraryConversion() throws Exception
	{
		final List<LlmClient.Message> conversation = new ArrayList<LlmClient.Message>();
		conversation.add(LlmClient.Message.user("Find FR"));
		conversation.add(LlmClient.Message.assistant("", Arrays.asList(new LlmClient.ToolCall("c1", "factory__lookup", Json.parse("{\"code\":\"FR\"}")),
			new LlmClient.ToolCall("c2", "factory__lookup", null))));
		conversation.add(LlmClient.Message.toolResults(Arrays.asList(new LlmClient.ToolResult("c1", "France", false), new LlmClient.ToolResult("c2", "bad", true))));
		final ObjectNode params = ContractLlmChat.params("Claude", "Be precise", Collections.singletonList(new LlmClient.ToolSpec("factory__lookup", "d",
			Json.parse("{\"type\":\"object\"}"))), conversation, Integer.valueOf(4096), Integer.valueOf(60000), true);
		yes("Claude".equals(params.path("connector").asText()) && params.path("maxTokens").asInt() == 4096 && params.path("timeoutMs").asInt() == 60000
			&& params.path("json").asBoolean() && "Be precise".equals(params.path("system").asText()), "scalar parameters: " + params);
		yes(params.path("messages").size() == 4, "one contract message per tool result: " + params.path("messages"));
		yes(!params.path("messages").path(1).has("content") && params.path("messages").path(1).path("toolCalls").size() == 2,
			"an assistant turn with only tool calls has no content");
		yes("tool".equals(params.path("messages").path(3).path("role").asText()) && "c2".equals(params.path("messages").path(3).path("toolCallId").asText())
			&& params.path("messages").path(3).path("isError").asBoolean() && !params.path("messages").path(2).has("isError"), "isError only when true");
		yes("factory__lookup".equals(params.path("tools").path(0).path("name").asText()) && params.path("tools").path(0).path("inputSchema").isObject(), "tools");
		// what the library sends, the contract accepts as is
		final ChatRequest back = ChatRequest.parse(ContractCatalog.validate(ContractCatalog.operation(ContractCatalog.LLM_CHAT), params));
		yes(back.conversation.size() == 3 && back.conversation.get(2).getResults().size() == 2, "the library's parameters pass the contract's validation");
		final LlmClient.Turn turn = ContractLlmChat.turnOf(Json.parse("{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"toolCalls\":[{\"id\":\"x\",\"name\":\"n\","
			+ "\"arguments\":{\"a\":1}}]},\"stopReason\":\"TOOL_USE\",\"usage\":{\"inputTokens\":10,\"outputTokens\":null}}"));
		yes(turn.stopReason == LlmClient.StopReason.TOOL_USE && turn.toolCalls.get(0).arguments.path("a").asInt() == 1 && turn.inputTokens == 10 && turn.outputTokens == 0,
			"a result becomes a Turn, a null count reads 0");
		yes(ContractLlmChat.turnOf(Json.parse("{\"message\":{},\"stopReason\":\"REFUSED_LATER\"}")).stopReason == LlmClient.StopReason.OTHER, "an unknown stop reason is OTHER");
		yes(ContractLlmChat.incompatibility("1.1.0") == null && ContractLlmChat.incompatibility("1.4.2") == null, "1.1.0 and later minors have llm.chat");
		yes(ContractLlmChat.incompatibility("1.0.0") != null && ContractLlmChat.incompatibility("2.0.0") != null && ContractLlmChat.incompatibility(null) != null,
			"1.0.0, another major or nothing: refused with the reason");
		yes(ContractLlmChat.incompatibility(ContractCatalog.VERSION) == null, "this build serves the library's llm.chat");
	}

	// ------------------------------------------------------------------ against the fake providers

	private static void askCapped(final Fake pFake, final String pBase) throws Exception
	{
		final ConnectorConfig config = ContractChatTest.config("anthropic", pBase, "sk-ant", "maxTokens", "2048");
		final LlmConnector llm = ContractChatTest.connector(config);
		final ContractCatalog.Operation ask = ContractCatalog.operation(ContractCatalog.LLM_ASK);
		ObjectNode out = ContractService.askWith(llm, config, "Claude", ContractCatalog.validate(ask, Json.parse("{\"prompt\":\"Hi\",\"maxTokens\":65536}")), System.nanoTime());
		yes(pFake.body.path("max_tokens").asInt() == 2048, "llm.ask: the connector's maxTokens is sent, not the 65536 asked: " + pFake.body.path("max_tokens"));
		yes(out.path("maxTokensApplied").asInt() == 2048 && out.path("capped").asBoolean(), "llm.ask: the answer says what was applied, capped: " + out);
		yes("Hello".equals(out.path("text").asText()) && "Claude".equals(out.path("connector").asText()) && out.has("truncated") && out.has("usage")
			&& out.has("durationMs"), "llm.ask: every 1.0 answer field still there");
		out = ContractService.askWith(llm, config, "Claude", ContractCatalog.validate(ask, Json.parse("{\"prompt\":\"Hi\",\"maxTokens\":300}")), System.nanoTime());
		yes(pFake.body.path("max_tokens").asInt() == 300 && out.path("maxTokensApplied").asInt() == 300 && !out.path("capped").asBoolean(), "llm.ask: less is kept");
		out = ContractService.askWith(llm, config, "Claude", ContractCatalog.validate(ask, Json.parse("{\"prompt\":\"Hi\"}")), System.nanoTime());
		yes(pFake.body.path("max_tokens").asInt() == 2048 && out.path("maxTokensApplied").asInt() == 2048 && !out.path("capped").asBoolean(),
			"llm.ask: nothing asked gets the connector's, not capped");
	}

	private static ObjectNode chat(final LlmConnector pLlm, final ConnectorConfig pConfig, final String pArgs) throws Exception
	{
		final ObjectNode args = ContractCatalog.validate(ContractCatalog.operation(ContractCatalog.LLM_CHAT), Json.parse(pArgs));
		return ContractService.chatWith(pLlm, pConfig, pConfig.getId(), args, ChatRequest.parse(args), System.nanoTime());
	}

	private static void anthropicTwoTurns(final Fake pFake, final String pBase) throws Exception
	{
		final ConnectorConfig config = ContractChatTest.config("anthropic", pBase, "sk-ant-chat", "maxTokens", "2048");
		final LlmConnector llm = ContractChatTest.connector(config);
		final String first = "{\"role\":\"user\",\"content\":\"Which country is FR?\"}";
		ObjectNode out = ContractChatTest.chat(llm, config, "{\"system\":\"Use the tools\",\"messages\":[" + first + "]," + ContractChatTest.TOOLS + ",\"maxTokens\":9000}");
		yes("/v1/messages".equals(pFake.path) && "sk-ant-chat".equals(pFake.headers.get("x-api-key")), "anthropic chat: path and the connector's own key header");
		yes(pFake.body.path("max_tokens").asInt() == 2048 && "Use the tools".equals(pFake.body.path("system").asText())
			&& "factory__lookup".equals(pFake.body.path("tools").path(0).path("name").asText())
			&& "object".equals(pFake.body.path("tools").path(0).path("input_schema").path("type").asText()) && !pFake.body.has("temperature"),
			"anthropic chat body: capped max_tokens, system, tools with input_schema, no temperature: " + pFake.body);
		yes("TOOL_USE".equals(out.path("stopReason").asText()) && "assistant".equals(out.path("message").path("role").asText()), "turn 1 asks for a tool: " + out);
		final JsonNode call = out.path("message").path("toolCalls").path(0);
		yes("toolu_1".equals(call.path("id").asText()) && "factory__lookup".equals(call.path("name").asText()) && "FR".equals(call.path("arguments").path("code").asText()),
			"the tool call: id, name, arguments as an object");
		yes(out.path("maxTokensApplied").asInt() == 2048 && out.path("capped").asBoolean() && "anthropic".equals(out.path("provider").asText())
			&& "claude-test-model".equals(out.path("model").asText()) && out.path("usage").path("inputTokens").asInt() == 21 && out.path("usage").path("totalTokens").asInt() == 26,
			"capped, provider, model and usage: " + out);
		final int calls = pFake.calls;
		// the caller runs the tool, then sends the whole conversation with the result
		final String second = "{\"messages\":[" + first + ",{\"role\":\"assistant\",\"content\":" + Json.write(out.path("message").path("content")) + ",\"toolCalls\":"
			+ Json.write(out.path("message").path("toolCalls")) + "},{\"role\":\"tool\",\"toolCallId\":\"toolu_1\",\"content\":\"France\"}]," + ContractChatTest.TOOLS + "}";
		out = ContractChatTest.chat(llm, config, second);
		yes(pFake.calls == calls + 1, "one HTTP round trip per call");
		final JsonNode sent = pFake.body.path("messages");
		yes(sent.size() == 3 && "tool_use".equals(sent.path(1).path("content").path(1).path("type").asText()) && "toolu_1".equals(sent.path(1).path("content").path(1).path("id").asText())
			&& "user".equals(sent.path(2).path("role").asText()) && "tool_result".equals(sent.path(2).path("content").path(0).path("type").asText())
			&& "toolu_1".equals(sent.path(2).path("content").path(0).path("tool_use_id").asText()) && "France".equals(sent.path(2).path("content").path(0).path("content").asText()),
			"turn 2 sends the assistant's tool_use and the tool_result: " + sent);
		yes("END".equals(out.path("stopReason").asText()) && out.path("message").path("content").asText().contains("France")
			&& out.path("message").path("toolCalls").size() == 0 && pFake.body.path("max_tokens").asInt() == 2048 && !out.path("capped").asBoolean(),
			"turn 2 ends with the answer, nothing asked = the connector's maxTokens, not capped: " + out);
	}

	private static void openAiTwoTurns(final Fake pFake, final String pBase) throws Exception
	{
		final ConnectorConfig config = ContractChatTest.config("openai", pBase + "/v1", "sk-oa", "maxTokens", "512", "openai.maxTokensParam", "max_completion_tokens");
		final LlmConnector llm = ContractChatTest.connector(config);
		final String first = "{\"role\":\"user\",\"content\":\"Which country is FR?\"}";
		ObjectNode out = ContractChatTest.chat(llm, config, "{\"messages\":[" + first + "]," + ContractChatTest.TOOLS + ",\"maxTokens\":256,\"json\":true}");
		yes("/v1/chat/completions".equals(pFake.path) && "Bearer sk-oa".equals(pFake.headers.get("authorization")), "openai chat: path not doubled, Bearer key");
		yes(pFake.body.path("max_completion_tokens").asInt() == 256 && !pFake.body.has("max_tokens") && "function".equals(pFake.body.path("tools").path(0).path("type").asText())
			&& "system".equals(pFake.body.path("messages").path(0).path("role").asText()) && !pFake.body.has("response_format") && !pFake.body.has("temperature"),
			"openai body: the connector's token field, function tools, its system prompt, no JSON mode by default (jsonMode auto), no temperature: " + pFake.body);
		yes(out.path("maxTokensApplied").asInt() == 256 && !out.path("capped").asBoolean() && "TOOL_USE".equals(out.path("stopReason").asText())
			&& "call_1".equals(out.path("message").path("toolCalls").path(0).path("id").asText())
			&& "FR".equals(out.path("message").path("toolCalls").path(0).path("arguments").path("code").asText()), "openai turn 1: tool call, arguments parsed: " + out);
		final String second = "{\"messages\":[" + first + ",{\"role\":\"assistant\",\"toolCalls\":" + Json.write(out.path("message").path("toolCalls"))
			+ "},{\"role\":\"tool\",\"toolCallId\":\"call_1\",\"content\":\"no such code\",\"isError\":true}]," + ContractChatTest.TOOLS + "}";
		out = ContractChatTest.chat(llm, config, second);
		final JsonNode sent = pFake.body.path("messages");
		yes(sent.size() == 4 && sent.path(2).path("content").isNull() && "call_1".equals(sent.path(2).path("tool_calls").path(0).path("id").asText())
			&& "tool".equals(sent.path(3).path("role").asText()) && "call_1".equals(sent.path(3).path("tool_call_id").asText())
			&& "ERROR: no such code".equals(sent.path(3).path("content").asText()), "openai turn 2: tool_calls replayed, role tool with the error marked: " + sent);
		yes("END".equals(out.path("stopReason").asText()) && out.path("message").path("content").asText().contains("no such code") && "gpt-test".equals(out.path("model").asText())
			&& out.path("usage").path("totalTokens").asInt() == 13, "openai turn 2 answer: " + out);
		// json: true with jsonMode true asks the API for JSON
		final ConnectorConfig jsonConfig = ContractChatTest.config("mistral", pBase, "ms", "jsonMode", "true");
		ContractChatTest.chat(ContractChatTest.connector(jsonConfig), jsonConfig, "{\"messages\":[" + first + "],\"json\":true}");
		yes("json_object".equals(pFake.body.path("response_format").path("type").asText()) && !pFake.body.has("tools"), "json + jsonMode true: response_format, no tools sent when none");
	}

	private static void reasoningEffortFallback(final Fake pFake, final String pBase) throws Exception
	{
		final ConnectorConfig config = ContractChatTest.config("openai", pBase, "sk-r", "model", "reasoner");
		final LlmConnector llm = ContractChatTest.connector(config);
		final int before = pFake.calls;
		ObjectNode out = ContractChatTest.chat(llm, config, "{\"messages\":[{\"role\":\"user\",\"content\":\"Go\"}]," + ContractChatTest.TOOLS + "}");
		yes(pFake.calls == before + 2 && "none".equals(pFake.body.path("reasoning_effort").asText()) && "TOOL_USE".equals(out.path("stopReason").asText()),
			"a 400 asking for reasoning_effort none is answered by one resend with none");
		out = ContractChatTest.chat(llm, config, "{\"messages\":[{\"role\":\"user\",\"content\":\"Again\"}]," + ContractChatTest.TOOLS + "}");
		yes(pFake.calls == before + 3 && "none".equals(pFake.body.path("reasoning_effort").asText()), "the connector instance remembers it: no second refusal");
		final ConnectorConfig plain = ContractChatTest.config("openai", pBase, "sk-p", "model", "reasoner");
		try
		{
			ContractChatTest.chat(ContractChatTest.connector(plain), plain, "{\"messages\":[{\"role\":\"user\",\"content\":\"No tools\"}]}");
			yes(false, "without tools the 400 is not retried");
		}
		catch (final ContractException ex)
		{
			yes(ex.getType() == ContractError.LLM_FAILED && ex.getMessage().contains("HTTP 400"), "a 400 without tools is LLM_FAILED: " + ex.getMessage());
		}
	}

	private static void otherProviders(final Fake pFake, final String pBase) throws Exception
	{
		final ConnectorConfig ollama = ContractChatTest.config("ollama", pBase, null);
		ollama.setAuthType(ConnectorConfig.AuthType.NONE);
		ContractChatTest.chat(ContractChatTest.connector(ollama), ollama, "{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]," + ContractChatTest.TOOLS + "}");
		yes("/v1/chat/completions".equals(pFake.path) && pFake.headers.get("authorization") == null, "ollama: its OpenAI-compatible endpoint, no credentials");
		final ConnectorConfig azure = ContractChatTest.config("azure", pBase, "az", "model", "gpt4o-prod");
		azure.setAuthType(ConnectorConfig.AuthType.API_KEY_HEADER);
		azure.setApiKeyHeader("api-key");
		ContractChatTest.chat(ContractChatTest.connector(azure), azure, "{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
		yes("/openai/deployments/gpt4o-prod/chat/completions?api-version=2024-10-21".equals(pFake.path) && "az".equals(pFake.headers.get("api-key")),
			"azure: deployment path and api-key header, as llm.ask: " + pFake.path);
		final ConnectorConfig google = ContractChatTest.config("google", pBase, "AIza");
		final int before = pFake.calls;
		try
		{
			ContractChatTest.chat(ContractChatTest.connector(google), google, "{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
			yes(false, "google must be refused");
		}
		catch (final ContractException ex)
		{
			yes(ex.getType() == ContractError.NOT_SUPPORTED && ex.getMessage().contains("google") && pFake.calls == before, "google: NOT_SUPPORTED before any call: " + ex.getMessage());
		}
		pFake.delayMs = 1500;
		try
		{
			final ConnectorConfig slow = ContractChatTest.config("anthropic", pBase, "sk");
			ContractChatTest.chat(ContractChatTest.connector(slow), slow, "{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"timeoutMs\":1000}");
			yes(false, "a slower model than timeoutMs must fail");
		}
		catch (final ContractException ex)
		{
			yes(ex.getType() == ContractError.TIMEOUT, "timeoutMs of the call: TIMEOUT: " + ex.getMessage());
		}
		finally
		{
			pFake.delayMs = 0;
		}
	}

	/** What the MDM Factory will do: its agent loop, its local tool, the contract as the only way to the model. */
	private static void agentLoopThroughTheContract(final Fake pFake, final String pBase) throws Exception
	{
		final ConnectorConfig config = ContractChatTest.config("anthropic", pBase, "sk-loop", "maxTokens", "1500");
		final LlmConnector llm = ContractChatTest.connector(config);
		final List<String> errors = new ArrayList<String>();
		final InJvmContract.Dispatcher dispatcher = new InJvmContract.Dispatcher()
		{
			@Override
			public ObjectNode call(final Session pSession, final String pOperation, final JsonNode pParams) throws ContractException
			{
				if (!errors.isEmpty())
				{
					throw new ContractException(ContractError.valueOf(errors.get(0)), "simulated refusal");
				}
				final ContractCatalog.Operation operation = ContractCatalog.operation(pOperation);
				final ObjectNode args = ContractCatalog.validate(operation, pParams);
				return ContractService.chatWith(llm, config, config.getId(), args, ChatRequest.parse(args), System.nanoTime());
			}
		};
		final ContractLlmChat.Transport transport = new ContractLlmChat.Transport()
		{
			@Override
			public String handle(final Object pSession, final String pMessage)
			{
				return Json.write(InJvmContract.respond(pSession, pMessage, dispatcher));
			}
		};
		final List<String> asked = new ArrayList<String>();
		final LocalTool lookup = new LocalTool("lookup", "Country of a code", Json.parse("{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}},\"required\":[\"code\"]}"),
			new LocalTool.Handler()
			{
				@Override
				public LocalTool.Result handle(final JsonNode pArguments)
				{
					asked.add(pArguments.path("code").asText());
					return LocalTool.Result.ok("France");
				}
			});
		final Map<String, ToolSet.LocalGroup> groups = new LinkedHashMap<String, ToolSet.LocalGroup>();
		groups.put("factory", new ToolSet.LocalGroup(Collections.singletonList(lookup)));
		final ToolSet tools = ToolSet.build(null, groups);
		final ContractLlmChat chat = new ContractLlmChat(null, null, Integer.valueOf(4000), Integer.valueOf(30000), false, transport);
		final int before = pFake.calls;
		final AgentLoop.Result result = new AgentLoop(chat, tools, new AgentLoop.Budget(5, 100000, 60000L), null).run("Answer with the country", "Which country is FR?");
		yes(result.status == AgentLoop.Status.DONE && result.steps == 2 && result.text.contains("France"), "the loop ran two turns through the contract: " + result.status + " "
			+ result.detail + " " + result.text);
		yes(asked.equals(Collections.singletonList("FR")) && pFake.calls == before + 2, "the tool ran in the caller, once; two model calls in all");
		yes(pFake.body.path("system").asText().startsWith(AgentLoop.UNTRUSTED_DATA_PREAMBLE) && pFake.body.path("max_tokens").asInt() == 1500,
			"the loop's system prompt reaches the model; 4000 asked, the connector's 1500 applied");
		yes(chat.lastMaxTokensApplied() == 1500 && chat.lastCapped() && "anthropic".equals(chat.lastResult().path("provider").asText()), "the library reads what was applied");
		yes(result.inputTokens == 21 + 30 && result.outputTokens == 5 + 7, "the loop counts the usage the contract answers: " + result.inputTokens + "/" + result.outputTokens);

		errors.add("TIMEOUT");
		final AgentLoop.Result failed = new AgentLoop(chat, tools, new AgentLoop.Budget(5, 100000, 60000L), null).run(null, "Which country is FR?");
		yes(failed.status == AgentLoop.Status.FAILED && failed.detail.contains("TIMEOUT") && "TIMEOUT".equals(chat.lastRefusal()) && chat.lastResult() == null,
			"a refusal of the contract ends the loop FAILED with its type: " + failed.detail);
		try
		{
			new ContractLlmChat(null, null, null, null, false, new ContractLlmChat.Transport()
			{
				@Override
				public String handle(final Object pSession, final String pMessage) throws Exception
				{
					throw new javax.management.InstanceNotFoundException(ContractLlmChat.MBEAN);
				}
			}).send(null, Collections.<LlmClient.ToolSpec>emptyList(), Collections.singletonList(LlmClient.Message.user("x")));
			yes(false, "no contract must fail");
		}
		catch (final LlmException ex)
		{
			yes(ex.getMessage().contains("not started"), "ebx-connectors absent: said: " + ex.getMessage());
		}
	}

	// ------------------------------------------------------------------ helpers

	private static ConnectorConfig config(final String pProvider, final String pBase, final String pKey, final String... pProperties)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("Llm-" + pProvider);
		config.setType("llm");
		config.setBaseUrl(pBase);
		config.setAuthType(ConnectorConfig.AuthType.BEARER);
		config.setApiKey(pKey);
		config.setConnectTimeoutMs(5000);
		config.setReadTimeoutMs(10000);
		config.setProperty("provider", pProvider);
		for (int i = 0; i + 1 < pProperties.length; i += 2)
		{
			config.setProperty(pProperties[i], pProperties[i + 1]);
		}
		return config;
	}

	private static LlmConnector connector(final ConnectorConfig pConfig) throws Exception
	{
		final LlmConnector connector = new LlmConnector();
		connector.configure(pConfig);
		return connector;
	}

	private static void yes(final boolean pCondition, final String pWhat)
	{
		ContractChatTest.checks++;
		if (!pCondition)
		{
			throw new AssertionError("FAIL: " + pWhat);
		}
	}

	/** Answers like the provider the path designates: a tool call while no tool result was sent, the final text after. */
	private static final class Fake implements HttpHandler
	{
		String path;
		Map<String, String> headers;
		JsonNode body;
		volatile int calls;
		volatile long delayMs;

		@Override
		public void handle(final HttpExchange pExchange) throws IOException
		{
			this.calls++;
			this.path = pExchange.getRequestURI().getRawPath() + (pExchange.getRequestURI().getRawQuery() == null ? "" : "?" + pExchange.getRequestURI().getRawQuery());
			this.headers = new LinkedHashMap<String, String>();
			for (final Map.Entry<String, List<String>> header : pExchange.getRequestHeaders().entrySet())
			{
				this.headers.put(header.getKey().toLowerCase(java.util.Locale.ENGLISH), header.getValue().get(0));
			}
			this.body = Json.parse(Fake.read(pExchange.getRequestBody()));
			if (this.delayMs > 0)
			{
				try
				{
					Thread.sleep(this.delayMs);
				}
				catch (final InterruptedException ex)
				{
					Thread.currentThread().interrupt();
				}
			}
			int status = 200;
			final String reply;
			final boolean tools = this.body.has("tools");
			final JsonNode messages = this.body.path("messages");
			final JsonNode last = messages.path(messages.size() - 1);
			if (this.path.endsWith("/v1/messages"))
			{
				final JsonNode result = last.path("content").path(0);
				if ("tool_result".equals(result.path("type").asText()))
				{
					reply = "{\"model\":\"claude-test-model\",\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":30,\"output_tokens\":7},"
						+ "\"content\":[{\"type\":\"text\",\"text\":\"The answer is " + result.path("content").asText() + "\"}]}";
				}
				else if (tools)
				{
					reply = "{\"model\":\"claude-test-model\",\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":21,\"output_tokens\":5},"
						+ "\"content\":[{\"type\":\"text\",\"text\":\"Let me look.\"},{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"factory__lookup\",\"input\":{\"code\":\"FR\"}}]}";
				}
				else
				{
					reply = "{\"model\":\"claude-test-model\",\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1},"
						+ "\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}";
				}
			}
			else if ("reasoner".equals(this.body.path("model").asText()) && !"none".equals(this.body.path("reasoning_effort").asText()))
			{
				status = 400;
				reply = "{\"error\":{\"message\":\"Function tools with reasoning_effort are not supported for reasoner. To use function tools, set reasoning_effort to 'none'.\"}}";
			}
			else if ("tool".equals(last.path("role").asText()))
			{
				reply = "{\"model\":\"gpt-test\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Seen: "
					+ last.path("content").asText() + "\"}}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4,\"total_tokens\":13}}";
			}
			else if (tools)
			{
				reply = "{\"model\":\"gpt-test\",\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
					+ "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"factory__lookup\",\"arguments\":\"{\\\"code\\\":\\\"FR\\\"}\"}}]}}],"
					+ "\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":2,\"total_tokens\":10}}";
			}
			else
			{
				reply = "{\"model\":\"gpt-test\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}";
			}
			final byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
			pExchange.getResponseHeaders().add("Content-Type", "application/json");
			pExchange.sendResponseHeaders(status, bytes.length);
			final OutputStream out = pExchange.getResponseBody();
			out.write(bytes);
			out.close();
		}

		private static String read(final InputStream pIn) throws IOException
		{
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			final byte[] chunk = new byte[4096];
			for (int n = pIn.read(chunk); n >= 0; n = pIn.read(chunk))
			{
				buffer.write(chunk, 0, n);
			}
			return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
