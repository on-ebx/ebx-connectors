package com.on.ebx.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link AgentLoop} guardrails, end to end against a fake Anthropic-shaped provider and a fake MCP server
 * (reusing the real {@link McpClient} and {@link ToolSet}, only the HTTP servers are fakes): a refused tool
 * call is never executed, each of the three independent budgets stops the run with the right detail, an
 * MCP tool error is fed back as data instead of failing the run, an oversized tool result is truncated, a
 * trace {@link AgentLoop.Listener} sees every turn and every call — including refused ones — without ever
 * being handed a request header, and (the local-tool guardrails from the same family) a {@link LocalTool}
 * called twice across separate turns, a handler that throws, and a missing required argument.
 */
public final class AgentLoopTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		AgentLoopTest.testRefusedToolIsNotExecuted();
		AgentLoopTest.testMaxStepsBudget();
		AgentLoopTest.testMaxTokensBudget();
		AgentLoopTest.testMaxWallClockBudget();
		AgentLoopTest.testMcpErrorFedBackAsData();
		AgentLoopTest.testTruncation();
		AgentLoopTest.testDoneOnFinalAnswer();
		AgentLoopTest.testParallelToolCallsAnthropic();
		AgentLoopTest.testParallelToolCallsOpenAi();
		AgentLoopTest.testLocalToolCalledTwiceAnthropic();
		AgentLoopTest.testLocalToolCalledTwiceOpenAi();
		AgentLoopTest.testLocalToolHandlerThrows();
		AgentLoopTest.testLocalToolMissingRequiredProperty();
		System.out.println("AgentLoopTest OK");
	}

	/** The model asks for a tool that was never allow-listed: it must not be executed, and the loop counts it as refused. */
	private static void testRefusedToolIsNotExecuted() throws Exception
	{
		final McpFixture mcp = McpFixture.start("do_it");
		try
		{
			final Map<String, ToolSet.Server> servers = new LinkedHashMap<String, ToolSet.Server>();
			servers.put("srv", new ToolSet.Server(mcp.client, AgentLoopTest.set("do_it")));
			final ToolSet tools = ToolSet.build(servers);

			// the model calls a tool name that isn't in the ToolSet at all (invented, or allow-listed on no server)
			final LlmFixture llm = LlmFixture.start(new String[] { LlmFixture.toolUse("call1", "srv__not_allowed", "{}"), LlmFixture.text("done") });
			try
			{
				final AgentLoop loop = new AgentLoop(llm.client(), tools, AgentLoopTest.generousBudget(), null);
				final AgentLoop.Result result = loop.run("system", "task");

				AgentLoopTest.check("run finishes DONE (the model recovers after the refusal)", result.status == AgentLoop.Status.DONE);
				AgentLoopTest.check("one refused call counted", result.refusedToolCalls == 1);
				AgentLoopTest.check("the MCP server was never called", mcp.toolCallCount.get() == 0);
				final JsonNode secondRequestToolResult = llm.received.get(1).path("messages").path(2).path("content").path(0);
				AgentLoopTest.check("the model was told the call was refused", secondRequestToolResult.path("is_error").asBoolean(false)
					&& secondRequestToolResult.path("content").asText().toLowerCase(java.util.Locale.ROOT).contains("refus"));
			}
			finally
			{
				llm.stop();
			}
		}
		finally
		{
			mcp.stop();
		}
	}

	private static void testMaxStepsBudget() throws Exception
	{
		final McpFixture mcp = McpFixture.start("do_it");
		try
		{
			final ToolSet tools = AgentLoopTest.oneServerToolSet(mcp, "do_it");
			// the model always asks for the tool again: with maxSteps=1 the loop must stop after the first turn
			final LlmFixture llm = LlmFixture.start(new String[] { LlmFixture.toolUse("call1", "srv__do_it", "{}"), LlmFixture.toolUse("call2", "srv__do_it", "{}") });
			try
			{
				final AgentLoop.Budget budget = new AgentLoop.Budget(1, 1000000, 60000L);
				final AgentLoop loop = new AgentLoop(llm.client(), tools, budget, null);
				final AgentLoop.Result result = loop.run("system", "task");
				AgentLoopTest.check("BUDGET on step overrun", result.status == AgentLoop.Status.BUDGET);
				AgentLoopTest.check("detail names the step limit", result.detail != null && result.detail.contains("step budget"));
				AgentLoopTest.check("exactly one step ran", result.steps == 1);
			}
			finally
			{
				llm.stop();
			}
		}
		finally
		{
			mcp.stop();
		}
	}

	private static void testMaxTokensBudget() throws Exception
	{
		final McpFixture mcp = McpFixture.start("do_it");
		try
		{
			final ToolSet tools = AgentLoopTest.oneServerToolSet(mcp, "do_it");
			// each fake turn reports 100 input + 50 output tokens; a cap of 120 must trip after the first turn
			final LlmFixture llm = LlmFixture.startWithUsage(new String[] { LlmFixture.toolUse("call1", "srv__do_it", "{}") }, 100, 50);
			try
			{
				final AgentLoop.Budget budget = new AgentLoop.Budget(10, 120, 60000L);
				final AgentLoop loop = new AgentLoop(llm.client(), tools, budget, null);
				final AgentLoop.Result result = loop.run("system", "task");
				AgentLoopTest.check("BUDGET on token overrun", result.status == AgentLoop.Status.BUDGET);
				AgentLoopTest.check("detail names the token limit", result.detail != null && result.detail.contains("token budget"));
				AgentLoopTest.check("tokens reported even on overrun", result.inputTokens == 100 && result.outputTokens == 50);
			}
			finally
			{
				llm.stop();
			}
		}
		finally
		{
			mcp.stop();
		}
	}

	private static void testMaxWallClockBudget() throws Exception
	{
		final McpFixture mcp = McpFixture.start("do_it");
		try
		{
			final ToolSet tools = AgentLoopTest.oneServerToolSet(mcp, "do_it");
			// the fake provider sleeps 150ms per call; a 50ms wall-clock budget must trip before a second step
			final LlmFixture llm = LlmFixture.startSlow(150L, new String[] { LlmFixture.toolUse("call1", "srv__do_it", "{}"), LlmFixture.text("done") });
			try
			{
				final AgentLoop.Budget budget = new AgentLoop.Budget(10, 1000000, 50L);
				final AgentLoop loop = new AgentLoop(llm.client(), tools, budget, null);
				final AgentLoop.Result result = loop.run("system", "task");
				AgentLoopTest.check("BUDGET on wall-clock overrun", result.status == AgentLoop.Status.BUDGET);
				AgentLoopTest.check("detail names the wall-clock limit", result.detail != null && result.detail.contains("wall-clock"));
			}
			finally
			{
				llm.stop();
			}
		}
		finally
		{
			mcp.stop();
		}
	}

	/** An MCP tool that throws McpException must not fail the run: it becomes an error tool-result the model can recover from. */
	private static void testMcpErrorFedBackAsData() throws Exception
	{
		final McpFixture mcp = McpFixture.startFailing("do_it");
		try
		{
			final ToolSet tools = AgentLoopTest.oneServerToolSet(mcp, "do_it");
			final LlmFixture llm = LlmFixture.start(new String[] { LlmFixture.toolUse("call1", "srv__do_it", "{}"), LlmFixture.text("recovered") });
			try
			{
				final List<Object[]> traced = new ArrayList<Object[]>();
				final AgentLoop.Listener listener = new AgentLoop.Listener()
				{
					@Override
					public void onTurn(final int pStep, final LlmClient.Turn pTurn)
					{
					}

					@Override
					public void onToolCall(final String pServer, final String pTool, final JsonNode pArguments, final String pResultText, final boolean pIsError,
						final long pDurationMs)
					{
						traced.add(new Object[] { pServer, pTool, pResultText, pIsError, pDurationMs });
					}
				};
				final AgentLoop loop = new AgentLoop(llm.client(), tools, AgentLoopTest.generousBudget(), listener);
				final AgentLoop.Result result = loop.run("system", "task");

				AgentLoopTest.check("the run still completes DONE", result.status == AgentLoop.Status.DONE && "recovered".equals(result.text));
				AgentLoopTest.check("no refusal: the tool was allowed, it just failed", result.refusedToolCalls == 0);
				AgentLoopTest.check("the failure was traced as an error with server/tool identified", traced.size() == 1 && "srv".equals(traced.get(0)[0])
					&& "do_it".equals(traced.get(0)[1]) && Boolean.TRUE.equals(traced.get(0)[3]));
				final JsonNode fedBack = llm.received.get(1).path("messages").path(2).path("content").path(0);
				AgentLoopTest.check("the model was fed the MCP error as data, marked is_error", fedBack.path("is_error").asBoolean(false)
					&& fedBack.path("content").asText().contains("boom"));
			}
			finally
			{
				llm.stop();
			}
		}
		finally
		{
			mcp.stop();
		}
	}

	private static void testTruncation() throws Exception
	{
		final StringBuilder huge = new StringBuilder();
		for (int i = 0; i < 500; i++)
		{
			huge.append("0123456789");
		}
		final McpFixture mcp = McpFixture.startWithResult("do_it", huge.toString());
		try
		{
			final ToolSet tools = AgentLoopTest.oneServerToolSet(mcp, "do_it");
			final LlmFixture llm = LlmFixture.start(new String[] { LlmFixture.toolUse("call1", "srv__do_it", "{}"), LlmFixture.text("done") });
			try
			{
				final AgentLoop loop = new AgentLoop(llm.client(), tools, AgentLoopTest.generousBudget(), null, 100);
				loop.run("system", "task");
				final String fedBack = llm.received.get(1).path("messages").path(2).path("content").path(0).path("content").asText();
				AgentLoopTest.check("truncated to at most a bit over the configured limit", fedBack.length() < huge.length() && fedBack.length() < 200);
				AgentLoopTest.check("truncation is noted inside the result", fedBack.contains("truncated"));
			}
			finally
			{
				llm.stop();
			}
		}
		finally
		{
			mcp.stop();
		}
	}

	private static void testDoneOnFinalAnswer() throws Exception
	{
		final McpFixture mcp = McpFixture.start("do_it");
		try
		{
			final ToolSet tools = AgentLoopTest.oneServerToolSet(mcp, "do_it");
			final LlmFixture llm = LlmFixture.start(new String[] { LlmFixture.text("the answer") });
			try
			{
				final AgentLoop loop = new AgentLoop(llm.client(), tools, AgentLoopTest.generousBudget(), null);
				final AgentLoop.Result result = loop.run("system", "task");
				AgentLoopTest.check("DONE with the model's text, one step, no tokens lost", result.status == AgentLoop.Status.DONE
					&& "the answer".equals(result.text) && result.steps == 1 && result.detail == null);
			}
			finally
			{
				llm.stop();
			}
		}
		finally
		{
			mcp.stop();
		}
	}

	/**
	 * The model asks for TWO tools in the SAME turn (Anthropic: two {@code tool_use} blocks in one assistant
	 * message) — one allow-listed and actually executed, one not and refused. Both must get a result, and per
	 * the Anthropic API both results must travel back in a single {@code user} message right after the
	 * assistant's, in the same order as the calls.
	 */
	private static void testParallelToolCallsAnthropic() throws Exception
	{
		final McpFixture mcp = McpFixture.start("do_it");
		try
		{
			final ToolSet tools = AgentLoopTest.oneServerToolSet(mcp, "do_it");
			final String bothCalls = LlmFixture.toolUse("call1", "srv__do_it", "{}") + "," + LlmFixture.toolUse("call2", "srv__not_allowed", "{}");
			final LlmFixture llm = LlmFixture.start(new String[] { bothCalls, LlmFixture.text("done") });
			try
			{
				final AgentLoop loop = new AgentLoop(llm.client(), tools, AgentLoopTest.generousBudget(), null);
				final AgentLoop.Result result = loop.run("system", "task");

				AgentLoopTest.check("run completes DONE", result.status == AgentLoop.Status.DONE);
				AgentLoopTest.check("exactly one of the pair refused", result.refusedToolCalls == 1);
				AgentLoopTest.check("the allowed tool was actually executed once", mcp.toolCallCount.get() == 1);

				final JsonNode secondRequest = llm.received.get(1);
				final JsonNode assistantMsg = secondRequest.path("messages").path(1);
				AgentLoopTest.check("both tool_use blocks echoed back in ONE assistant message, in order", "assistant".equals(assistantMsg.path("role").asText())
					&& assistantMsg.path("content").size() == 2 && "call1".equals(assistantMsg.path("content").path(0).path("id").asText())
					&& "call2".equals(assistantMsg.path("content").path(1).path("id").asText()));

				final JsonNode resultMsg = secondRequest.path("messages").path(2);
				AgentLoopTest.check("both results travel back in a SINGLE user message", "user".equals(resultMsg.path("role").asText())
					&& resultMsg.path("content").size() == 2);
				final JsonNode r1 = resultMsg.path("content").path(0);
				final JsonNode r2 = resultMsg.path("content").path(1);
				AgentLoopTest.check("result 1 answers call1, executed, not an error", "call1".equals(r1.path("tool_use_id").asText()) && !r1.path("is_error").asBoolean(false));
				AgentLoopTest.check("result 2 answers call2, refused, is_error", "call2".equals(r2.path("tool_use_id").asText()) && r2.path("is_error").asBoolean(false)
					&& r2.path("content").asText().toLowerCase(java.util.Locale.ROOT).contains("refus"));
			}
			finally
			{
				llm.stop();
			}
		}
		finally
		{
			mcp.stop();
		}
	}

	/**
	 * The OpenAI-shaped equivalent: two entries in one assistant message's {@code tool_calls} array, one
	 * allow-listed, one refused. Per that API each must get its OWN {@code role:"tool"} message afterwards
	 * (not grouped into one message), in the same order, or the real API answers 400.
	 */
	private static void testParallelToolCallsOpenAi() throws Exception
	{
		final McpFixture mcp = McpFixture.start("do_it");
		try
		{
			final ToolSet tools = AgentLoopTest.oneServerToolSet(mcp, "do_it");
			final List<JsonNode> received = new ArrayList<JsonNode>();
			final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/v1/chat/completions", new HttpHandler()
			{
				@Override
				public void handle(final HttpExchange pExchange) throws IOException
				{
					final JsonNode body = AgentLoopTest.MAPPER.readTree(AgentLoopTest.read(pExchange.getRequestBody()));
					final int index = received.size();
					received.add(body);
					final String answer;
					if (index == 0)
					{
						answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
							+ "{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"srv__do_it\",\"arguments\":\"{}\"}},"
							+ "{\"id\":\"call2\",\"type\":\"function\",\"function\":{\"name\":\"srv__not_allowed\",\"arguments\":\"{}\"}}"
							+ "]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
					}
					else
					{
						answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}],"
							+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";
					}
					AgentLoopTest.answerJson(pExchange, answer);
				}
			});
			server.start();
			try
			{
				final LlmClient client = new LlmClient(LlmClient.Format.OPENAI_CHAT, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", null,
					"gpt-4o-mini", 512, 2000, 5000);
				final AgentLoop loop = new AgentLoop(client, tools, AgentLoopTest.generousBudget(), null);
				final AgentLoop.Result result = loop.run("system", "task");

				AgentLoopTest.check("run completes DONE", result.status == AgentLoop.Status.DONE);
				AgentLoopTest.check("exactly one of the pair refused", result.refusedToolCalls == 1);
				AgentLoopTest.check("the allowed tool was actually executed once", mcp.toolCallCount.get() == 1);

				final JsonNode secondRequest = received.get(1);
				final JsonNode messages = secondRequest.path("messages");
				AgentLoopTest.check("system + user + assistant + 2 separate tool messages = 5", messages.size() == 5);
				final JsonNode assistantMsg = messages.path(2);
				AgentLoopTest.check("both tool_calls echoed back in ONE assistant message, in order", assistantMsg.path("tool_calls").size() == 2
					&& "call1".equals(assistantMsg.path("tool_calls").path(0).path("id").asText()) && "call2".equals(assistantMsg.path("tool_calls").path(1).path("id").asText()));
				final JsonNode toolMsg1 = messages.path(3);
				final JsonNode toolMsg2 = messages.path(4);
				AgentLoopTest.check("call1's result is its OWN tool message, executed, no ERROR prefix", "tool".equals(toolMsg1.path("role").asText())
					&& "call1".equals(toolMsg1.path("tool_call_id").asText()) && !toolMsg1.path("content").asText().startsWith("ERROR: "));
				AgentLoopTest.check("call2's result is its OWN tool message, refused, ERROR-prefixed", "tool".equals(toolMsg2.path("role").asText())
					&& "call2".equals(toolMsg2.path("tool_call_id").asText()) && toolMsg2.path("content").asText().startsWith("ERROR: ")
					&& toolMsg2.path("content").asText().toLowerCase(java.util.Locale.ROOT).contains("refus"));
			}
			finally
			{
				server.stop(0);
			}
		}
		finally
		{
			mcp.stop();
		}
	}

	/**
	 * A {@link LocalTool} (no MCP server involved at all: this is the factory's real use case) called on two
	 * separate turns, then the model ends the conversation. Each call's result must be fed back before the
	 * next request, and the handler must run exactly as many times as the model actually called it.
	 */
	private static void testLocalToolCalledTwiceAnthropic() throws Exception
	{
		final AtomicInteger calls = new AtomicInteger();
		final ToolSet tools = AgentLoopTest.oneLocalToolSet("factory", AgentLoopTest.countingProposeTool(calls));
		final LlmFixture llm = LlmFixture.start(new String[] { LlmFixture.toolUse("call1", "factory__propose_entity", "{}"),
			LlmFixture.toolUse("call2", "factory__propose_entity", "{}"), LlmFixture.text("done") });
		try
		{
			final AgentLoop loop = new AgentLoop(llm.client(), tools, AgentLoopTest.generousBudget(), null);
			final AgentLoop.Result result = loop.run("system", "task");

			AgentLoopTest.check("DONE after the model stops asking for the tool", result.status == AgentLoop.Status.DONE && "done".equals(result.text));
			AgentLoopTest.check("the handler ran exactly twice", calls.get() == 2);
			AgentLoopTest.check("no refusal: this is an allowed local tool", result.refusedToolCalls == 0);

			final JsonNode secondRequest = llm.received.get(1);
			final JsonNode firstResultMsg = secondRequest.path("messages").path(2).path("content").path(0);
			AgentLoopTest.check("first local result fed back before the second request, not an error", "call1".equals(firstResultMsg.path("tool_use_id").asText())
				&& firstResultMsg.path("content").asText().contains("recorded #1") && !firstResultMsg.path("is_error").asBoolean(false));

			final JsonNode thirdRequest = llm.received.get(2);
			final JsonNode secondResultMsg = thirdRequest.path("messages").path(4).path("content").path(0);
			AgentLoopTest.check("second local result fed back before the third request", "call2".equals(secondResultMsg.path("tool_use_id").asText())
				&& secondResultMsg.path("content").asText().contains("recorded #2"));
		}
		finally
		{
			llm.stop();
		}
	}

	/** The OpenAI-shaped equivalent of {@link #testLocalToolCalledTwiceAnthropic()}. */
	private static void testLocalToolCalledTwiceOpenAi() throws Exception
	{
		final AtomicInteger calls = new AtomicInteger();
		final ToolSet tools = AgentLoopTest.oneLocalToolSet("factory", AgentLoopTest.countingProposeTool(calls));
		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				final JsonNode body = AgentLoopTest.MAPPER.readTree(AgentLoopTest.read(pExchange.getRequestBody()));
				final int index = received.size();
				received.add(body);
				final String callId = index == 0 ? "call1" : "call2";
				final String answer;
				if (index < 2)
				{
					answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"" + callId
						+ "\",\"type\":\"function\",\"function\":{\"name\":\"factory__propose_entity\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],"
						+ "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
				}
				else
				{
					answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}],"
						+ "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";
				}
				AgentLoopTest.answerJson(pExchange, answer);
			}
		});
		server.start();
		try
		{
			final LlmClient client = new LlmClient(LlmClient.Format.OPENAI_CHAT, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", null,
				"gpt-4o-mini", 512, 2000, 5000);
			final AgentLoop loop = new AgentLoop(client, tools, AgentLoopTest.generousBudget(), null);
			final AgentLoop.Result result = loop.run("system", "task");

			AgentLoopTest.check("DONE after the model stops asking for the tool", result.status == AgentLoop.Status.DONE && "done".equals(result.text));
			AgentLoopTest.check("the handler ran exactly twice", calls.get() == 2);

			final JsonNode secondRequest = received.get(1);
			final JsonNode firstToolMsg = secondRequest.path("messages").path(3);
			AgentLoopTest.check("first local result is its own tool message", "tool".equals(firstToolMsg.path("role").asText())
				&& "call1".equals(firstToolMsg.path("tool_call_id").asText()) && firstToolMsg.path("content").asText().contains("recorded #1"));

			final JsonNode thirdRequest = received.get(2);
			final JsonNode secondToolMsg = thirdRequest.path("messages").path(5);
			AgentLoopTest.check("second local result is its own tool message too", "tool".equals(secondToolMsg.path("role").asText())
				&& "call2".equals(secondToolMsg.path("tool_call_id").asText()) && secondToolMsg.path("content").asText().contains("recorded #2"));
		}
		finally
		{
			server.stop(0);
		}
	}

	/** A local handler that throws must not fail the run: it becomes an error tool-result (message only, no stack trace), same as an McpException. */
	private static void testLocalToolHandlerThrows() throws Exception
	{
		final LocalTool boom = new LocalTool("boom", "always throws", AgentLoopTest.MAPPER.createObjectNode().put("type", "object"), new LocalTool.Handler()
		{
			@Override
			public LocalTool.Result handle(final JsonNode pArguments) throws Exception
			{
				throw new IllegalStateException("kaboom");
			}
		});
		final ToolSet tools = AgentLoopTest.oneLocalToolSet("factory", boom);
		final LlmFixture llm = LlmFixture.start(new String[] { LlmFixture.toolUse("call1", "factory__boom", "{}"), LlmFixture.text("recovered") });
		try
		{
			final AgentLoop loop = new AgentLoop(llm.client(), tools, AgentLoopTest.generousBudget(), null);
			final AgentLoop.Result result = loop.run("system", "task");

			AgentLoopTest.check("the run still completes DONE: a handler exception is recoverable data, not a run failure", result.status == AgentLoop.Status.DONE
				&& "recovered".equals(result.text));
			AgentLoopTest.check("no refusal: the tool was allowed, its handler just failed", result.refusedToolCalls == 0);

			final JsonNode fedBack = llm.received.get(1).path("messages").path(2).path("content").path(0);
			AgentLoopTest.check("the exception's message reached the model, marked as an error", fedBack.path("is_error").asBoolean(false)
				&& fedBack.path("content").asText().contains("kaboom"));
			AgentLoopTest.check("no stack trace and no exception class name leaked into the tool result", !fedBack.path("content").asText().contains("\tat ")
				&& !fedBack.path("content").asText().contains("IllegalStateException"));
		}
		finally
		{
			llm.stop();
		}
	}

	/**
	 * A model call missing a property the local tool's schema lists as {@code required}: the handler must
	 * NOT run, and the model is told which property is missing so it can correct itself.
	 */
	private static void testLocalToolMissingRequiredProperty() throws Exception
	{
		final AtomicInteger calls = new AtomicInteger();
		final JsonNode schema = AgentLoopTest.MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"entityId\":{\"type\":\"string\"}},\"required\":[\"entityId\"]}");
		final LocalTool propose = new LocalTool("propose_entity", "records a proposal", schema, new LocalTool.Handler()
		{
			@Override
			public LocalTool.Result handle(final JsonNode pArguments)
			{
				calls.incrementAndGet();
				return LocalTool.Result.ok("recorded");
			}
		});
		final ToolSet tools = AgentLoopTest.oneLocalToolSet("factory", propose);
		// the model calls the tool with an empty argument object: the required "entityId" is missing
		final LlmFixture llm = LlmFixture.start(new String[] { LlmFixture.toolUse("call1", "factory__propose_entity", "{}"), LlmFixture.text("done") });
		try
		{
			final AgentLoop loop = new AgentLoop(llm.client(), tools, AgentLoopTest.generousBudget(), null);
			final AgentLoop.Result result = loop.run("system", "task");

			AgentLoopTest.check("the handler was never called", calls.get() == 0);
			AgentLoopTest.check("the run still completes DONE: the model gets to correct itself", result.status == AgentLoop.Status.DONE);
			AgentLoopTest.check("no refusal: this is an allowed tool with a bad argument, not an unlisted one", result.refusedToolCalls == 0);

			final JsonNode fedBack = llm.received.get(1).path("messages").path(2).path("content").path(0);
			AgentLoopTest.check("the violation names the missing property, marked as an error", fedBack.path("is_error").asBoolean(false)
				&& fedBack.path("content").asText().contains("entityId"));
		}
		finally
		{
			llm.stop();
		}
	}

	// ---- fixtures ----

	private static ToolSet oneServerToolSet(final McpFixture pMcp, final String... pToolNames) throws McpException
	{
		final Map<String, ToolSet.Server> servers = new LinkedHashMap<String, ToolSet.Server>();
		servers.put("srv", new ToolSet.Server(pMcp.client, AgentLoopTest.set(pToolNames)));
		return ToolSet.build(servers);
	}

	/** A {@link ToolSet} with no MCP server at all: one local group under {@code pGroup}, carrying the given tools. */
	private static ToolSet oneLocalToolSet(final String pGroup, final LocalTool... pTools) throws McpException
	{
		final Map<String, ToolSet.LocalGroup> localGroups = new LinkedHashMap<String, ToolSet.LocalGroup>();
		localGroups.put(pGroup, new ToolSet.LocalGroup(java.util.Arrays.asList(pTools)));
		return ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), localGroups);
	}

	/** A {@code propose_entity} local tool whose handler counts its own invocations and reports which call it was. */
	private static LocalTool countingProposeTool(final AtomicInteger pCalls)
	{
		return new LocalTool("propose_entity", "Records a proposed entity merge for a person to accept", AgentLoopTest.MAPPER.createObjectNode().put("type", "object"),
			new LocalTool.Handler()
			{
				@Override
				public LocalTool.Result handle(final JsonNode pArguments)
				{
					return LocalTool.Result.ok("recorded #" + pCalls.incrementAndGet());
				}
			});
	}

	private static AgentLoop.Budget generousBudget()
	{
		return new AgentLoop.Budget(10, 1000000, 60000L);
	}

	private static Set<String> set(final String... pNames)
	{
		return new LinkedHashSet<String>(java.util.Arrays.asList(pNames));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}

	/** A fake Anthropic-shaped provider answering one fixed turn per call, from a scripted list of {@code content} block bodies. */
	private static final class LlmFixture
	{
		final List<JsonNode> received = new ArrayList<JsonNode>();
		private final HttpServer server;

		private LlmFixture(final HttpServer pServer)
		{
			this.server = pServer;
		}

		LlmClient client()
		{
			return new LlmClient(LlmClient.Format.ANTHROPIC, "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1/messages", null, "claude-sonnet-5", 512, 2000,
				5000);
		}

		void stop()
		{
			this.server.stop(0);
		}

		static LlmFixture start(final String[] pContentBlocks) throws IOException
		{
			return LlmFixture.startWithUsage(pContentBlocks, 10, 5);
		}

		static LlmFixture startWithUsage(final String[] pContentBlocks, final int pInputTokens, final int pOutputTokens) throws IOException
		{
			return LlmFixture.startDelayed(pContentBlocks, pInputTokens, pOutputTokens, 0L);
		}

		static LlmFixture startSlow(final long pDelayMs, final String[] pContentBlocks) throws IOException
		{
			return LlmFixture.startDelayed(pContentBlocks, 10, 5, pDelayMs);
		}

		static LlmFixture startDelayed(final String[] pContentBlocks, final int pInputTokens, final int pOutputTokens, final long pDelayMs) throws IOException
		{
			final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			final LlmFixture fixture = new LlmFixture(server);
			server.createContext("/v1/messages", new HttpHandler()
			{
				@Override
				public void handle(final HttpExchange pExchange) throws IOException
				{
					final JsonNode body = AgentLoopTest.MAPPER.readTree(AgentLoopTest.read(pExchange.getRequestBody()));
					final int index = fixture.received.size();
					fixture.received.add(body);
					if (pDelayMs > 0)
					{
						try
						{
							Thread.sleep(pDelayMs);
						}
						catch (final InterruptedException ex)
						{
							Thread.currentThread().interrupt();
						}
					}
					final String block = pContentBlocks[Math.min(index, pContentBlocks.length - 1)];
					final boolean toolUse = block.contains("\"tool_use\"");
					final String answer = "{\"content\":[" + block + "],\"stop_reason\":\"" + (toolUse ? "tool_use" : "end_turn") + "\",\"usage\":{\"input_tokens\":"
						+ pInputTokens + ",\"output_tokens\":" + pOutputTokens + "}}";
					AgentLoopTest.answerJson(pExchange, answer);
				}
			});
			server.start();
			return fixture;
		}

		static String toolUse(final String pId, final String pName, final String pInputJson)
		{
			return "{\"type\":\"tool_use\",\"id\":\"" + pId + "\",\"name\":\"" + pName + "\",\"input\":" + pInputJson + "}";
		}

		static String text(final String pText)
		{
			return "{\"type\":\"text\",\"text\":\"" + pText + "\"}";
		}
	}

	/** A fake MCP server (real {@link McpClient} against it) with either a fixed successful result, or a JSON-RPC error, per tool. */
	private static final class McpFixture
	{
		final McpClient client;
		final AtomicInteger toolCallCount;
		private final HttpServer server;

		private McpFixture(final McpClient pClient, final HttpServer pServer, final AtomicInteger pToolCallCount)
		{
			this.client = pClient;
			this.server = pServer;
			this.toolCallCount = pToolCallCount;
		}

		void stop()
		{
			this.server.stop(0);
		}

		static McpFixture start(final String... pToolNames) throws IOException
		{
			return McpFixture.startWithResult(pToolNames[0], "ok");
		}

		static McpFixture startWithResult(final String pToolName, final String pResultText) throws IOException
		{
			return McpFixture.build(pToolName, false, pResultText);
		}

		static McpFixture startFailing(final String pToolName) throws IOException
		{
			return McpFixture.build(pToolName, true, "boom");
		}

		private static McpFixture build(final String pToolName, final boolean pFail, final String pResultText) throws IOException
		{
			final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			final AtomicInteger toolCallCount = new AtomicInteger();
			server.createContext("/mcp", new HttpHandler()
			{
				@Override
				public void handle(final HttpExchange pExchange) throws IOException
				{
					final JsonNode message = AgentLoopTest.MAPPER.readTree(AgentLoopTest.read(pExchange.getRequestBody()));
					final String method = message.path("method").asText();
					final int id = message.path("id").asInt();
					if ("initialize".equals(method))
					{
						AgentLoopTest.answerJson(pExchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-06-18\"}}");
					}
					else if ("notifications/initialized".equals(method))
					{
						pExchange.sendResponseHeaders(202, -1);
						pExchange.close();
					}
					else if ("tools/list".equals(method))
					{
						AgentLoopTest.answerJson(pExchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{\"name\":\"" + pToolName
							+ "\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}");
					}
					else if ("tools/call".equals(method))
					{
						toolCallCount.incrementAndGet();
						if (pFail)
						{
							AgentLoopTest.answerJson(pExchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32000,\"message\":\"" + pResultText + "\"}}");
						}
						else
						{
							AgentLoopTest.answerJson(pExchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\""
								+ AgentLoopTest.jsonEscape(pResultText) + "\"}]}}");
						}
					}
					else
					{
						pExchange.sendResponseHeaders(404, -1);
						pExchange.close();
					}
				}
			});
			server.start();
			return new McpFixture(new McpClient("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", null, 2000, 5000), server, toolCallCount);
		}
	}

	private static String jsonEscape(final String pText)
	{
		return pText.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void answerJson(final HttpExchange pExchange, final String pBody) throws IOException
	{
		final byte[] bytes = pBody.getBytes(StandardCharsets.UTF_8);
		pExchange.getResponseHeaders().add("Content-Type", "application/json");
		pExchange.sendResponseHeaders(200, bytes.length);
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
}
