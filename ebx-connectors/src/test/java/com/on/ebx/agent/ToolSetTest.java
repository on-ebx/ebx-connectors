package com.on.ebx.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link ToolSet}: the {@code <group>__<tool>} name mapping both ways (allow-listed MCP tools and every
 * registered local tool are exposed under the combined name and {@link ToolSet#resolve} reverses it back),
 * the allow-list filter (a tool the server has but this run did not allow-list never appears), the
 * build-time refusal of a name that does not fit every provider's {@code ^[a-zA-Z0-9_-]{1,64}$} pattern, a
 * {@link ToolSet} mixing one MCP server and one local group with names routed to the right {@link
 * ToolSet.Kind}, and a local-only {@link ToolSet} with no MCP server at all.
 */
public final class ToolSetTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		// two servers whose tool catalogues share a name ("run"), to prove the exposed names don't collide
		server.createContext("/alpha", ToolSetTest.fakeMcp("run", "build"));
		server.createContext("/beta", ToolSetTest.fakeMcp("run"));
		server.start();
		try
		{
			final String base = "http://127.0.0.1:" + server.getAddress().getPort();
			final McpClient alpha = new McpClient(base + "/alpha", null, 2000, 5000);
			final McpClient beta = new McpClient(base + "/beta", null, 2000, 5000);

			final Map<String, ToolSet.Server> servers = new LinkedHashMap<String, ToolSet.Server>();
			servers.put("alpha", new ToolSet.Server(alpha, ToolSetTest.set("run")));
			servers.put("beta", new ToolSet.Server(beta, ToolSetTest.set("run")));
			final ToolSet set = ToolSet.build(servers);

			ToolSetTest.check("both exposed under <server>__<tool>, no collision", set.specs().size() == 2);
			final Set<String> names = new HashSet<String>();
			for (final LlmClient.ToolSpec spec : set.specs())
			{
				names.add(spec.name);
			}
			ToolSetTest.check("alpha__run present", names.contains("alpha__run"));
			ToolSetTest.check("beta__run present", names.contains("beta__run"));

			ToolSetTest.check("allow-list filter: 'build' on alpha was not allow-listed and must not appear", !names.contains("alpha__build"));

			final ToolSet.Entry resolvedAlpha = set.resolve("alpha__run");
			ToolSetTest.check("resolve() maps back to the right server and real tool name", resolvedAlpha != null && "alpha".equals(resolvedAlpha.server)
				&& "run".equals(resolvedAlpha.toolName) && resolvedAlpha.client == alpha);
			final ToolSet.Entry resolvedBeta = set.resolve("beta__run");
			ToolSetTest.check("the same real tool name on the other server resolves independently", resolvedBeta != null && "beta".equals(resolvedBeta.server)
				&& resolvedBeta.client == beta);
			ToolSetTest.check("an unknown exposed name resolves to null (a call to it is refused, not executed)", set.resolve("alpha__delete_everything") == null);

			// a tool the model was never allow-listed for
			final Map<String, ToolSet.Server> onlyBuild = new LinkedHashMap<String, ToolSet.Server>();
			onlyBuild.put("alpha", new ToolSet.Server(alpha, ToolSetTest.set("build")));
			final ToolSet buildOnly = ToolSet.build(onlyBuild);
			ToolSetTest.check("only the allow-listed tool is exposed", buildOnly.specs().size() == 1 && "alpha__build".equals(buildOnly.specs().get(0).name));

			// a name that does not fit the provider pattern must be refused at build time, before any model call
			final Map<String, ToolSet.Server> badServerName = new LinkedHashMap<String, ToolSet.Server>();
			badServerName.put("a server with spaces", new ToolSet.Server(alpha, ToolSetTest.set("run")));
			try
			{
				ToolSet.build(badServerName);
				ToolSetTest.check("a name with spaces must be refused", false);
			}
			catch (final IllegalArgumentException ex)
			{
				ToolSetTest.check("refusal names the bad exposed name", ex.getMessage().contains("a server with spaces__run"));
			}
		}
		finally
		{
			server.stop(0);
		}
		ToolSetTest.testMixedMcpAndLocalRouting();
		ToolSetTest.testLocalOnlyToolSet();
		System.out.println("ToolSetTest OK");
	}

	/** One MCP server and one local group in the same {@link ToolSet}: each exposed name must resolve to the right {@link ToolSet.Kind}. */
	private static void testMixedMcpAndLocalRouting() throws Exception
	{
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/alpha", ToolSetTest.fakeMcp("run"));
		server.start();
		try
		{
			final McpClient alpha = new McpClient("http://127.0.0.1:" + server.getAddress().getPort() + "/alpha", null, 2000, 5000);
			final Map<String, ToolSet.Server> servers = new LinkedHashMap<String, ToolSet.Server>();
			servers.put("alpha", new ToolSet.Server(alpha, ToolSetTest.set("run")));

			final LocalTool propose = new LocalTool("propose_entity", "Records a proposed merge", ToolSetTest.MAPPER.createObjectNode().put("type", "object"),
				new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return LocalTool.Result.ok("recorded");
					}
				});
			final Map<String, ToolSet.LocalGroup> localGroups = new LinkedHashMap<String, ToolSet.LocalGroup>();
			localGroups.put("factory", new ToolSet.LocalGroup(Collections.singletonList(propose)));

			final ToolSet set = ToolSet.build(servers, localGroups);
			ToolSetTest.check("both entries exposed", set.specs().size() == 2);

			final ToolSet.Entry mcpEntry = set.resolve("alpha__run");
			ToolSetTest.check("the MCP name routes to Kind.MCP with the right client", mcpEntry != null && mcpEntry.kind == ToolSet.Kind.MCP && mcpEntry.client == alpha
				&& mcpEntry.localTool == null);

			final ToolSet.Entry localEntry = set.resolve("factory__propose_entity");
			ToolSetTest.check("the local name routes to Kind.LOCAL with the right LocalTool", localEntry != null && localEntry.kind == ToolSet.Kind.LOCAL
				&& localEntry.client == null && localEntry.localTool == propose && "factory".equals(localEntry.server));
		}
		finally
		{
			server.stop(0);
		}
	}

	/** No MCP server at all — a run that must never write through MCP still gets a working {@link ToolSet}. */
	private static void testLocalOnlyToolSet() throws Exception
	{
		final LocalTool proposeA = new LocalTool("propose_a", "d", ToolSetTest.MAPPER.createObjectNode().put("type", "object"), new LocalTool.Handler()
		{
			@Override
			public LocalTool.Result handle(final JsonNode pArguments)
			{
				return LocalTool.Result.ok("a");
			}
		});
		final LocalTool proposeB = new LocalTool("propose_b", "d", ToolSetTest.MAPPER.createObjectNode().put("type", "object"), new LocalTool.Handler()
		{
			@Override
			public LocalTool.Result handle(final JsonNode pArguments)
			{
				return LocalTool.Result.ok("b");
			}
		});
		final Map<String, ToolSet.LocalGroup> localGroups = new LinkedHashMap<String, ToolSet.LocalGroup>();
		localGroups.put("factory", new ToolSet.LocalGroup(Arrays.asList(proposeA, proposeB)));

		final ToolSet set = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), localGroups);
		ToolSetTest.check("both local tools exposed, no MCP server involved", set.specs().size() == 2);
		ToolSetTest.check("factory__propose_a resolves LOCAL", set.resolve("factory__propose_a").kind == ToolSet.Kind.LOCAL);
		ToolSetTest.check("factory__propose_b resolves LOCAL", set.resolve("factory__propose_b").kind == ToolSet.Kind.LOCAL);

		// the single-arg build() overload (servers only) must still work unchanged
		final ToolSet empty = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap());
		ToolSetTest.check("no servers, no local groups: an empty but valid ToolSet", empty.specs().isEmpty());
	}

	private static Set<String> set(final String... pNames)
	{
		return new LinkedHashSet<String>(java.util.Arrays.asList(pNames));
	}

	/** A minimal MCP server exposing the given tool names, enough for initialize + tools/list. */
	private static HttpHandler fakeMcp(final String... pToolNames)
	{
		return new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
				final com.fasterxml.jackson.databind.JsonNode message = mapper.readTree(ToolSetTest.read(pExchange.getRequestBody()));
				final String method = message.path("method").asText();
				final int id = message.path("id").asInt();
				if ("initialize".equals(method))
				{
					ToolSetTest.answer(pExchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"fake\"}}}");
				}
				else if ("notifications/initialized".equals(method))
				{
					pExchange.sendResponseHeaders(202, -1);
					pExchange.close();
				}
				else if ("tools/list".equals(method))
				{
					final StringBuilder tools = new StringBuilder();
					for (final String name : pToolNames)
					{
						if (tools.length() > 0)
						{
							tools.append(',');
						}
						tools.append("{\"name\":\"").append(name).append("\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}");
					}
					ToolSetTest.answer(pExchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[" + tools + "]}}");
				}
				else
				{
					pExchange.sendResponseHeaders(404, -1);
					pExchange.close();
				}
			}
		};
	}

	private static void answer(final HttpExchange pExchange, final String pBody) throws IOException
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

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
