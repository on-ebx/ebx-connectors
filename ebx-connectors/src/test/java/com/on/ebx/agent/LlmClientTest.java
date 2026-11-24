package com.on.ebx.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link LlmClient} against a fake of each wire format: a two-step tool conversation checked JSON-field by
 * JSON-field on both requests, retry-then-success on 429, and an HTTP error that must not leak the test's
 * own authentication header value into the exception message.
 */
public final class LlmClientTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		LlmClientTest.testAnthropicTwoStepConversation();
		LlmClientTest.testOpenAiTwoStepConversation();
		LlmClientTest.testRetryOn429ThenSuccess();
		LlmClientTest.testErrorDoesNotLeakHeaders();
		LlmClientTest.testOpenAiMaxTokensParamName();
		LlmClientTest.testReasoningEffortNotSentByDefault();
		LlmClientTest.testReasoningEffortConfigured();
		LlmClientTest.testReasoningEffortNoneFallback();
		LlmClientTest.testOtherBadRequestNotRetried();
		System.out.println("LlmClientTest OK");
	}

	private static void testAnthropicTwoStepConversation() throws Exception
	{
		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = LlmClientTest.fakeServer("/v1/messages", received, new Responder()
		{
			@Override
			public String answer(final int pCallIndex, final JsonNode pBody)
			{
				if (pCallIndex == 0)
				{
					return "{\"content\":[{\"type\":\"tool_use\",\"id\":\"call1\",\"name\":\"tools__do_it\",\"input\":{\"x\":1}}],"
						+ "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
				}
				return "{\"content\":[{\"type\":\"text\",\"text\":\"answer: 42\"}],\"stop_reason\":\"end_turn\","
					+ "\"usage\":{\"input_tokens\":20,\"output_tokens\":8}}";
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
			final LlmClient client = new LlmClient(LlmClient.Format.ANTHROPIC, url, LlmClientTest.headers("x-api-key", "test-key"), "claude-sonnet-5", 512, 2000, 5000);
			final List<LlmClient.ToolSpec> tools = Collections.singletonList(
				new LlmClient.ToolSpec("tools__do_it", "Does it", LlmClientTest.MAPPER.createObjectNode().put("type", "object")));
			final List<LlmClient.Message> conversation = new ArrayList<LlmClient.Message>();
			conversation.add(LlmClient.Message.user("please do it"));

			final LlmClient.Turn turn1 = client.send("be nice", tools, conversation);
			LlmClientTest.check("first call carries model/max_tokens/system", "claude-sonnet-5".equals(received.get(0).path("model").asText())
				&& received.get(0).path("max_tokens").asInt() == 512 && "be nice".equals(received.get(0).path("system").asText()));
			LlmClientTest.check("first call's tools array", "tools__do_it".equals(received.get(0).path("tools").path(0).path("name").asText()));
			LlmClientTest.check("stop reason parsed as TOOL_USE", turn1.stopReason == LlmClient.StopReason.TOOL_USE);
			LlmClientTest.check("one tool call parsed", turn1.toolCalls.size() == 1 && "call1".equals(turn1.toolCalls.get(0).id)
				&& "tools__do_it".equals(turn1.toolCalls.get(0).name) && turn1.toolCalls.get(0).arguments.path("x").asInt() == 1);
			LlmClientTest.check("usage parsed", turn1.inputTokens == 10 && turn1.outputTokens == 5);

			conversation.add(LlmClient.Message.assistant(turn1.text, turn1.toolCalls));
			conversation.add(LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult("call1", "42", false))));
			final LlmClient.Turn turn2 = client.send("be nice", tools, conversation);

			final JsonNode secondRequest = received.get(1);
			final JsonNode messages = secondRequest.path("messages");
			LlmClientTest.check("second request has 3 messages", messages.size() == 3);
			LlmClientTest.check("message 0 is the original user turn", "user".equals(messages.path(0).path("role").asText())
				&& "please do it".equals(messages.path(0).path("content").asText()));
			final JsonNode assistantMsg = messages.path(1);
			LlmClientTest.check("message 1 is the assistant's tool_use, echoed back", "assistant".equals(assistantMsg.path("role").asText())
				&& "tool_use".equals(assistantMsg.path("content").path(0).path("type").asText()) && "call1".equals(assistantMsg.path("content").path(0).path("id").asText())
				&& "tools__do_it".equals(assistantMsg.path("content").path(0).path("name").asText()));
			final JsonNode toolResultMsg = messages.path(2);
			LlmClientTest.check("message 2 is the tool result, linked to the right id, in the right shape", "user".equals(toolResultMsg.path("role").asText())
				&& "tool_result".equals(toolResultMsg.path("content").path(0).path("type").asText())
				&& "call1".equals(toolResultMsg.path("content").path(0).path("tool_use_id").asText())
				&& "42".equals(toolResultMsg.path("content").path(0).path("content").asText())
				&& !toolResultMsg.path("content").path(0).has("is_error"));
			LlmClientTest.check("final turn text and END", "answer: 42".equals(turn2.text) && turn2.stopReason == LlmClient.StopReason.END);

			// an error tool result must carry is_error
			conversation.add(LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult("call1", "boom", true))));
			client.send("be nice", tools, conversation);
			final JsonNode errorBlock = received.get(2).path("messages").path(3).path("content").path(0);
			LlmClientTest.check("is_error carried through", errorBlock.path("is_error").asBoolean(false));
		}
		finally
		{
			server.stop(0);
		}
	}

	private static void testOpenAiTwoStepConversation() throws Exception
	{
		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = LlmClientTest.fakeServer("/v1/chat/completions", received, new Responder()
		{
			@Override
			public String answer(final int pCallIndex, final JsonNode pBody)
			{
				if (pCallIndex == 0)
				{
					return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\","
						+ "\"function\":{\"name\":\"tools__do_it\",\"arguments\":\"{\\\"x\\\":1}\"}}]},\"finish_reason\":\"tool_calls\"}],"
						+ "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6}}";
				}
				return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer: 42\"},\"finish_reason\":\"stop\"}],"
					+ "\"usage\":{\"prompt_tokens\":22,\"completion_tokens\":9}}";
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
			final LlmClient client = new LlmClient(LlmClient.Format.OPENAI_CHAT, url, LlmClientTest.headers("Authorization", "Bearer test-key"), "gpt-4o-mini", 512, 2000, 5000);
			final List<LlmClient.ToolSpec> tools = Collections.singletonList(
				new LlmClient.ToolSpec("tools__do_it", "Does it", LlmClientTest.MAPPER.createObjectNode().put("type", "object")));
			final List<LlmClient.Message> conversation = new ArrayList<LlmClient.Message>();
			conversation.add(LlmClient.Message.user("please do it"));

			final LlmClient.Turn turn1 = client.send("be nice", tools, conversation);
			LlmClientTest.check("tools array in OpenAI shape", "function".equals(received.get(0).path("tools").path(0).path("type").asText())
				&& "tools__do_it".equals(received.get(0).path("tools").path(0).path("function").path("name").asText()));
			LlmClientTest.check("system message present", "system".equals(received.get(0).path("messages").path(0).path("role").asText())
				&& "be nice".equals(received.get(0).path("messages").path(0).path("content").asText()));
			LlmClientTest.check("tool_calls arguments parsed from the JSON string", turn1.toolCalls.size() == 1
				&& turn1.toolCalls.get(0).arguments.path("x").asInt() == 1);
			LlmClientTest.check("finish_reason tool_calls -> TOOL_USE", turn1.stopReason == LlmClient.StopReason.TOOL_USE);
			LlmClientTest.check("usage parsed from prompt/completion tokens", turn1.inputTokens == 12 && turn1.outputTokens == 6);

			conversation.add(LlmClient.Message.assistant(turn1.text, turn1.toolCalls));
			conversation.add(LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult("call1", "42", false))));
			final LlmClient.Turn turn2 = client.send("be nice", tools, conversation);

			final JsonNode secondRequest = received.get(1);
			final JsonNode messages = secondRequest.path("messages");
			LlmClientTest.check("second request has system+user+assistant+tool = 4 messages", messages.size() == 4);
			final JsonNode assistantMsg = messages.path(2);
			LlmClientTest.check("assistant message has null content and the tool_calls array", assistantMsg.path("content").isNull()
				&& "call1".equals(assistantMsg.path("tool_calls").path(0).path("id").asText())
				&& "tools__do_it".equals(assistantMsg.path("tool_calls").path(0).path("function").path("name").asText()));
			final JsonNode toolMsg = messages.path(3);
			LlmClientTest.check("tool result message linked to the right id, in the right shape", "tool".equals(toolMsg.path("role").asText())
				&& "call1".equals(toolMsg.path("tool_call_id").asText()) && "42".equals(toolMsg.path("content").asText()));
			LlmClientTest.check("final turn text and END", "answer: 42".equals(turn2.text) && turn2.stopReason == LlmClient.StopReason.END);

			// an error tool result has no is_error field in this format: it is prefixed in content instead
			conversation.add(LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult("call1", "boom", true))));
			client.send("be nice", tools, conversation);
			LlmClientTest.check("error prefixed into content", received.get(2).path("messages").path(4).path("content").asText().startsWith("ERROR: "));
		}
		finally
		{
			server.stop(0);
		}
	}

	private static void testRetryOn429ThenSuccess() throws Exception
	{
		final AtomicInteger calls = new AtomicInteger();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				LlmClientTest.drain(pExchange);
				if (calls.getAndIncrement() == 0)
				{
					pExchange.getResponseHeaders().add("Retry-After", "0");
					LlmClientTest.answer(pExchange, 429, "{\"error\":\"rate limited\"}");
					return;
				}
				LlmClientTest.answer(pExchange, 200,
					"{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
			final LlmClient client = new LlmClient(LlmClient.Format.ANTHROPIC, url, null, "claude-sonnet-5", 512, 2000, 5000);
			final LlmClient.Turn turn = client.send("s", Collections.<LlmClient.ToolSpec>emptyList(),
				Collections.singletonList(LlmClient.Message.user("hi")));
			LlmClientTest.check("429 then success: two attempts made", calls.get() == 2);
			LlmClientTest.check("the retried call's answer is returned", "ok".equals(turn.text));
		}
		finally
		{
			server.stop(0);
		}
	}

	private static void testErrorDoesNotLeakHeaders() throws Exception
	{
		final String secretHeaderValue = "sk-super-secret-test-value";
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				LlmClientTest.drain(pExchange);
				LlmClientTest.answer(pExchange, 400, "{\"error\":{\"message\":\"bad request: missing field\"}}");
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
			final LlmClient client = new LlmClient(LlmClient.Format.ANTHROPIC, url, LlmClientTest.headers("x-api-key", secretHeaderValue), "claude-sonnet-5", 512, 2000, 5000);
			try
			{
				client.send("s", Collections.<LlmClient.ToolSpec>emptyList(), Collections.singletonList(LlmClient.Message.user("hi")));
				LlmClientTest.check("a 400 must throw", false);
			}
			catch (final LlmException ex)
			{
				LlmClientTest.check("status kept", ex.getStatus() == 400);
				LlmClientTest.check("body detail surfaced", ex.getMessage().contains("missing field"));
				LlmClientTest.check("the API key header value is never in the message", !ex.getMessage().contains(secretHeaderValue));
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * The OpenAI-shaped token-limit field name is an explicit constructor option, not guessed from the model
	 * name: the 7-arg constructor keeps sending {@code max_tokens} (the {@code LlmConnector} default), and
	 * the 8-arg constructor sends whatever field name a caller (the factory, passing the connector's
	 * {@code openai.maxTokensParam} property through) supplies instead — {@code max_completion_tokens} for a
	 * reasoning model, here.
	 */
	private static void testOpenAiMaxTokensParamName() throws Exception
	{
		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = LlmClientTest.fakeServer("/v1/chat/completions", received, new Responder()
		{
			@Override
			public String answer(final int pCallIndex, final JsonNode pBody)
			{
				return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
					+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
			final List<LlmClient.Message> conversation = Collections.singletonList(LlmClient.Message.user("hi"));

			// default (7-arg constructor): the LlmConnector default, "max_tokens"
			final LlmClient defaultClient = new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-4o-mini", 256, 2000, 5000);
			defaultClient.send("s", Collections.<LlmClient.ToolSpec>emptyList(), conversation);
			LlmClientTest.check("default field name is max_tokens", received.get(0).has("max_tokens") && received.get(0).path("max_tokens").asInt() == 256);
			LlmClientTest.check("max_completion_tokens absent by default", !received.get(0).has("max_completion_tokens"));

			// explicit (8-arg constructor): a reasoning-model deployment configured for max_completion_tokens
			final LlmClient reasoningClient = new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "o3-mini", 256, 2000, 5000, "max_completion_tokens");
			reasoningClient.send("s", Collections.<LlmClient.ToolSpec>emptyList(), conversation);
			LlmClientTest.check("explicit field name is honoured", received.get(1).has("max_completion_tokens") && received.get(1).path("max_completion_tokens").asInt() == 256);
			LlmClientTest.check("max_tokens absent when max_completion_tokens is configured", !received.get(1).has("max_tokens"));

			// null/blank falls back to the default rather than sending a malformed field name
			final LlmClient blankClient = new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-4o-mini", 256, 2000, 5000, "  ");
			blankClient.send("s", Collections.<LlmClient.ToolSpec>emptyList(), conversation);
			LlmClientTest.check("blank falls back to the default field name", received.get(2).has("max_tokens"));
		}
		finally
		{
			server.stop(0);
		}
	}

	/** No {@code reasoning_effort} unless configured: gpt-4o and every endpoint that does not know the field see the old request, tools or not. */
	private static void testReasoningEffortNotSentByDefault() throws Exception
	{
		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = LlmClientTest.fakeServer("/v1/chat/completions", received, new Responder()
		{
			@Override
			public String answer(final int pCallIndex, final JsonNode pBody)
			{
				return LlmClientTest.OPENAI_OK;
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
			final List<LlmClient.Message> conversation = Collections.singletonList(LlmClient.Message.user("hi"));
			new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-4o", 256, 2000, 5000).send("s", LlmClientTest.oneTool(), conversation);
			new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-4o", 256, 2000, 5000, "max_tokens").send("s", LlmClientTest.oneTool(), conversation);
			new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-4o", 256, 2000, 5000, "max_tokens", "  ").send("s", LlmClientTest.oneTool(), conversation);
			new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-4o", 256, 2000, 5000, "max_tokens", null).send("s", LlmClientTest.oneTool(), conversation);
			LlmClientTest.check("four requests", received.size() == 4);
			for (final JsonNode request : received)
			{
				LlmClientTest.check("reasoning_effort absent by default", !request.has("reasoning_effort"));
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	/** A configured value is sent as-is on every OpenAI-shaped request, never on an Anthropic one. */
	private static void testReasoningEffortConfigured() throws Exception
	{
		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = LlmClientTest.fakeServer("/v1/chat/completions", received, new Responder()
		{
			@Override
			public String answer(final int pCallIndex, final JsonNode pBody)
			{
				return LlmClientTest.OPENAI_OK;
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
			final LlmClient client = new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-6-luna", 256, 2000, 5000, "max_completion_tokens", " low ");
			client.send("s", LlmClientTest.oneTool(), Collections.singletonList(LlmClient.Message.user("hi")));
			client.send("s", Collections.<LlmClient.ToolSpec>emptyList(), Collections.singletonList(LlmClient.Message.user("hi")));
			LlmClientTest.check("configured reasoning_effort sent (trimmed)", "low".equals(received.get(0).path("reasoning_effort").asText()));
			LlmClientTest.check("configured reasoning_effort sent without tools too", "low".equals(received.get(1).path("reasoning_effort").asText()));
			LlmClientTest.check("getter reports the configured value", "low".equals(client.getOpenAiReasoningEffort()));
		}
		finally
		{
			server.stop(0);
		}

		final List<JsonNode> anthropic = new ArrayList<JsonNode>();
		final HttpServer anthropicServer = LlmClientTest.fakeServer("/v1/messages", anthropic, new Responder()
		{
			@Override
			public String answer(final int pCallIndex, final JsonNode pBody)
			{
				return "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
			}
		});
		anthropicServer.start();
		try
		{
			final String url = "http://127.0.0.1:" + anthropicServer.getAddress().getPort() + "/v1/messages";
			new LlmClient(LlmClient.Format.ANTHROPIC, url, null, "claude-sonnet-5", 256, 2000, 5000, null, "low").send("s", LlmClientTest.oneTool(),
				Collections.singletonList(LlmClient.Message.user("hi")));
			LlmClientTest.check("never sent in the Anthropic format", !anthropic.get(0).has("reasoning_effort"));
		}
		finally
		{
			anthropicServer.stop(0);
		}
	}

	/**
	 * The live refusal of 2026-09-26 (gpt-6-luna, function tools, no reasoning_effort): one resend with
	 * {@code reasoning_effort: none}, and the value is kept for the next turns of the same client - no second
	 * refusal to pay per turn.
	 */
	private static void testReasoningEffortNoneFallback() throws Exception
	{
		final List<JsonNode> received = new ArrayList<JsonNode>();
		final AtomicInteger refusals = new AtomicInteger();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				final JsonNode body = LlmClientTest.MAPPER.readTree(LlmClientTest.read(pExchange.getRequestBody()));
				received.add(body);
				if (body.has("tools") && !"none".equals(body.path("reasoning_effort").asText(null)))
				{
					refusals.incrementAndGet();
					LlmClientTest.answer(pExchange, 400, LlmClientTest.LUNA_REFUSAL);
					return;
				}
				LlmClientTest.answer(pExchange, 200, LlmClientTest.OPENAI_OK);
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
			final LlmClient client = new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-6-luna", 256, 2000, 5000, "max_completion_tokens");
			final List<LlmClient.Message> conversation = new ArrayList<LlmClient.Message>();
			conversation.add(LlmClient.Message.user("hi"));
			final LlmClient.Turn turn = client.send("s", LlmClientTest.oneTool(), conversation);
			LlmClientTest.check("the fallback answer is returned", "ok".equals(turn.text));
			LlmClientTest.check("two requests: the refused one and one resend", received.size() == 2 && refusals.get() == 1);
			LlmClientTest.check("first request carried no reasoning_effort", !received.get(0).has("reasoning_effort"));
			LlmClientTest.check("resend carries reasoning_effort none", "none".equals(received.get(1).path("reasoning_effort").asText()));
			LlmClientTest.check("resend is otherwise the same request", received.get(1).path("tools").equals(received.get(0).path("tools"))
				&& received.get(1).path("messages").equals(received.get(0).path("messages"))
				&& received.get(1).path("max_completion_tokens").asInt() == 256);
			LlmClientTest.check("getter reports none after the fallback", "none".equals(client.getOpenAiReasoningEffort()));

			conversation.add(LlmClient.Message.assistant(turn.text, turn.toolCalls));
			conversation.add(LlmClient.Message.user("again"));
			client.send("s", LlmClientTest.oneTool(), conversation);
			LlmClientTest.check("next turn sends none straight away, no second refusal", received.size() == 3 && refusals.get() == 1
				&& "none".equals(received.get(2).path("reasoning_effort").asText()));
		}
		finally
		{
			server.stop(0);
		}

		// the refusal repeated even with none: exactly one resend, then the error surfaces
		final AtomicInteger calls = new AtomicInteger();
		final HttpServer stubborn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		stubborn.createContext("/v1/chat/completions", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				LlmClientTest.drain(pExchange);
				calls.incrementAndGet();
				LlmClientTest.answer(pExchange, 400, LlmClientTest.LUNA_REFUSAL);
			}
		});
		stubborn.start();
		try
		{
			final String url = "http://127.0.0.1:" + stubborn.getAddress().getPort() + "/v1/chat/completions";
			final LlmClient client = new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-6-luna", 256, 2000, 5000);
			try
			{
				client.send("s", LlmClientTest.oneTool(), Collections.singletonList(LlmClient.Message.user("hi")));
				LlmClientTest.check("a repeated refusal must throw", false);
			}
			catch (final LlmException ex)
			{
				LlmClientTest.check("one resend only, then the 400 surfaces", calls.get() == 2 && ex.getStatus() == 400);
			}
		}
		finally
		{
			stubborn.stop(0);
		}
	}

	/** Any other HTTP 400, and the same refusal on a call without tools, is never retried. */
	private static void testOtherBadRequestNotRetried() throws Exception
	{
		final AtomicInteger calls = new AtomicInteger();
		final String[] body = new String[] { "{\"error\":{\"message\":\"Unsupported parameter: 'temperature'\"}}" };
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				LlmClientTest.drain(pExchange);
				calls.incrementAndGet();
				LlmClientTest.answer(pExchange, 400, body[0]);
			}
		});
		server.start();
		try
		{
			final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
			try
			{
				new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-6-luna", 256, 2000, 5000).send("s", LlmClientTest.oneTool(),
					Collections.singletonList(LlmClient.Message.user("hi")));
				LlmClientTest.check("an unrelated 400 must throw", false);
			}
			catch (final LlmException ex)
			{
				LlmClientTest.check("an unrelated 400 is not retried", calls.get() == 1);
			}
			body[0] = LlmClientTest.LUNA_REFUSAL;
			try
			{
				new LlmClient(LlmClient.Format.OPENAI_CHAT, url, null, "gpt-6-luna", 256, 2000, 5000).send("s", Collections.<LlmClient.ToolSpec>emptyList(),
					Collections.singletonList(LlmClient.Message.user("hi")));
				LlmClientTest.check("the refusal without tools must throw", false);
			}
			catch (final LlmException ex)
			{
				LlmClientTest.check("the refusal without tools is not retried", calls.get() == 2);
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	// ---- fixtures ----

	/** The body OpenAI answered on 2026-09-26 (agent run 141 of the MDM Factory), shape of its usual error envelope. */
	private static final String LUNA_REFUSAL = "{\"error\":{\"message\":\"Function tools with reasoning_effort are not supported for gpt-6-luna in "
		+ "/v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort to 'none'.\",\"type\":\"invalid_request_error\","
		+ "\"param\":\"reasoning_effort\",\"code\":null}}";

	private static final String OPENAI_OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
		+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";

	private static List<LlmClient.ToolSpec> oneTool()
	{
		return Collections.singletonList(new LlmClient.ToolSpec("tools__do_it", "Does it", LlmClientTest.MAPPER.createObjectNode().put("type", "object")));
	}

	private interface Responder
	{
		String answer(int pCallIndex, JsonNode pBody);
	}

	private static HttpServer fakeServer(final String pPath, final List<JsonNode> pReceived, final Responder pResponder) throws IOException
	{
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(pPath, new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				final JsonNode body = LlmClientTest.MAPPER.readTree(LlmClientTest.read(pExchange.getRequestBody()));
				final int index = pReceived.size();
				pReceived.add(body);
				LlmClientTest.answer(pExchange, 200, pResponder.answer(index, body));
			}
		});
		return server;
	}

	private static Map<String, String> headers(final String pName, final String pValue)
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put(pName, pValue);
		return headers;
	}

	private static void drain(final HttpExchange pExchange) throws IOException
	{
		LlmClientTest.read(pExchange.getRequestBody());
	}

	private static void answer(final HttpExchange pExchange, final int pStatus, final String pBody) throws IOException
	{
		final byte[] bytes = pBody.getBytes(StandardCharsets.UTF_8);
		pExchange.getResponseHeaders().add("Content-Type", "application/json");
		pExchange.sendResponseHeaders(pStatus, bytes.length);
		pExchange.getResponseBody().write(bytes);
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
