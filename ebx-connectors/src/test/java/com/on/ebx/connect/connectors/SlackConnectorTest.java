package com.on.ebx.connect.connectors;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The Slack connector against an in-memory fake of the Web API: Bearer token, auth.test, channel names resolved
 * through a paginated conversations.list and cached, text escaped with the title in bold, {@code ok:false}
 * refusals turned into exceptions with a status and a hint (HTTP 200 is not success), update, dm by email,
 * channel search, default channel property, Block Kit blocks. No workspace, no network.
 */
public final class SlackConnectorTest
{
	private SlackConnectorTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String previousAllowList = System.getProperty(Egress.PROPERTY);
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final Fake fake = new Fake();
		server.createContext("/", fake);
		server.start();
		try
		{
			final int port = server.getAddress().getPort();
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + port);
			final ConnectorConfig config = config(port, "xoxb-test");
			final SlackConnector connector = connector(config);

			final ConnectionTestResult probe = connector.testConnection(null);
			check(probe.isSuccessful() && probe.getMessage().contains("'Test workspace' as ebx-bot"), "connection test: " + probe.getMessage());
			check("Bearer xoxb-test".equals(fake.lastAuthorization), "bot token sent as Bearer: " + fake.lastAuthorization);

			// post by channel name: two pages of conversations.list, then the id
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			inputs.put("channel", "#Data-Alerts");
			inputs.put("title", "Quality <alert>");
			inputs.put("text", "3 records & 1 table");
			final JsonNode sent = connector.execute(null, "post", inputs, null);
			check("sent".equals(sent.path("status").asText()) && "C0000DATA".equals(sent.path("channel").asText()) && "1700000000.000100".equals(sent.path("ts").asText()),
				"message sent to the resolved channel: " + sent);
			check("1700000000.000100".equals(sent.path("threadTs").asText()), "threadTs = ts of a new message");
			check(fake.listCalls == 2, "conversations.list followed its cursor (2 pages, got " + fake.listCalls + ")");
			final JsonNode body = fake.posts.get(0);
			check("C0000DATA".equals(body.path("channel").asText()), "channel id posted");
			check("*Quality &lt;alert&gt;*\n3 records &amp; 1 table".equals(body.path("text").asText()), "title in bold, markup escaped: " + body.path("text").asText());
			check(!body.path("unfurl_links").asBoolean(true), "link previews off by default");

			// second post: the name is cached
			inputs.put("threadTs", "1700000000.000100");
			connector.execute(null, "notify", inputs, null);
			check(fake.listCalls == 2, "channel id cached, no new conversations.list");
			check("1700000000.000100".equals(fake.posts.get(1).path("thread_ts").asText()), "reply posted in the thread");

			// refusal with HTTP 200: an exception, never a success
			final Map<String, String> closed = new LinkedHashMap<String, String>();
			closed.put("channel", "C0000PRIV");
			closed.put("text", "hello");
			final ConnectorException notInChannel = fails(connector, "post", closed);
			check(notInChannel.getMessage().contains("not_in_channel") && notInChannel.getMessage().contains("/invite") && notInChannel.getStatus() == 403,
				"ok:false not_in_channel -> 403 with a hint: " + notInChannel.getMessage() + " / " + notInChannel.getStatus());

			// unknown name: 404 before any post
			final Map<String, String> unknown = new LinkedHashMap<String, String>();
			unknown.put("channel", "#nowhere");
			unknown.put("text", "hello");
			final int postsBefore = fake.posts.size();
			final ConnectorException missing = fails(connector, "post", unknown);
			check(missing.getStatus() == 404 && missing.getMessage().contains("groups:read") && fake.posts.size() == postsBefore, "unknown channel name: " + missing.getMessage());

			// update
			final Map<String, String> edit = new LinkedHashMap<String, String>();
			edit.put("channel", "#data-alerts");
			edit.put("ts", "1700000000.000100");
			edit.put("text", "Resolved");
			final JsonNode updated = connector.execute(null, "update", edit, null);
			check("updated".equals(updated.path("status").asText()) && "C0000DATA".equals(fake.lastUpdate.path("channel").asText())
				&& "1700000000.000100".equals(fake.lastUpdate.path("ts").asText()) && "Resolved".equals(fake.lastUpdate.path("text").asText()), "message edited: " + fake.lastUpdate);

			// dm by email
			final Map<String, String> dm = new LinkedHashMap<String, String>();
			dm.put("email", "ana@example.com");
			dm.put("text", "Your table has 3 invalid records");
			final JsonNode direct = connector.execute(null, "dm", dm, null);
			check("U0000ANA1".equals(direct.path("user").asText()) && "D0000ANA1".equals(direct.path("channel").asText()), "dm to the member found by email: " + direct);
			check("U0000ANA1".equals(fake.posts.get(fake.posts.size() - 1).path("channel").asText()), "dm posted to the user id");
			dm.put("email", "nobody@example.com");
			final ConnectorException nobody = fails(connector, "dm", dm);
			check(nobody.getStatus() == 404 && nobody.getMessage().contains("users_not_found"), "unknown email: " + nobody.getMessage());

			// channels
			final Map<String, String> search = new LinkedHashMap<String, String>();
			search.put("name", "alert");
			final JsonNode found = connector.execute(null, "channels", search, null);
			check(found.path("count").asInt() == 1 && "data-alerts".equals(found.path("items").get(0).path("name").asText())
				&& found.path("items").get(0).path("isMember").asBoolean(), "channel search by name: " + found);

			// outbound mapping document, default channel property, blocks
			config.setProperty("channel", "#general"); // the connector keeps the config object it was given
			final ObjectNode document = Json.object();
			document.put("text", "From the document");
			document.putArray("blocks").addObject().put("type", "divider");
			final Map<String, String> none = new LinkedHashMap<String, String>();
			connector.execute(null, "post", none, document);
			final JsonNode last = fake.posts.get(fake.posts.size() - 1);
			check("C0000GENE".equals(last.path("channel").asText()) && "From the document".equals(last.path("text").asText())
				&& "divider".equals(last.path("blocks").get(0).path("type").asText()), "document text + blocks to the default channel: " + last);
			final Map<String, String> badBlocks = new LinkedHashMap<String, String>();
			badBlocks.put("text", "x");
			badBlocks.put("blocks", "{not json");
			check(fails(connector, "post", badBlocks).getMessage().contains("not valid JSON"), "invalid blocks refused before any call");

			// missing scope, placeholder token
			final SlackConnector noScope = connector(config(port, "xoxb-noscope"));
			final Map<String, String> plain = new LinkedHashMap<String, String>();
			plain.put("channel", "C0000DATA");
			plain.put("text", "x");
			final ConnectorException scope = fails(noScope, "post", plain);
			check(scope.getMessage().contains("chat:write") && scope.getMessage().contains("reinstall") && scope.getStatus() == 403, "missing scope hint: " + scope.getMessage());
			final ConnectionTestResult refused = noScope.testConnection(null);
			check(refused.isSuccessful(), "auth.test needs no scope");
			final SlackConnector placeholder = connector(config(port, "TO_BE_SET"));
			final int calls = fake.calls;
			check(fails(placeholder, "post", plain).getMessage().contains("bot token not configured") && fake.calls == calls, "placeholder token: no call");

			// chat-webhook: a Slack webhook is verified by an empty payload that Slack refuses without posting
			final int postsBeforeProbe = fake.webhookMessages;
			final ChatWebhookConnector hook = new ChatWebhookConnector();
			final ConnectorConfig hookConfig = config(port, null);
			hookConfig.setType("chat-webhook");
			hookConfig.setAuthType(ConnectorConfig.AuthType.NONE);
			hookConfig.setBaseUrl("http://127.0.0.1:" + port + "/hook");
			hook.configure(hookConfig);
			final ConnectionTestResult hookProbe = hook.testConnection(null);
			check(hookProbe.isSupported() && hookProbe.isSuccessful() && hookProbe.getMessage().contains("invalid_payload"), "valid Slack webhook verified: " + hookProbe.getMessage());
			check(fake.webhookMessages == postsBeforeProbe, "the probe posted nothing");
			hookConfig.setBaseUrl("http://127.0.0.1:" + port + "/badhook");
			final ConnectionTestResult badHook = hook.testConnection(null);
			check(badHook.isSupported() && !badHook.isSuccessful() && badHook.getMessage().contains("403 invalid_token"), "revoked webhook reported: " + badHook.getMessage());
			hookConfig.setProperty("format", "teams");
			final int callsBeforeTeams = fake.calls;
			final ConnectionTestResult teams = hook.testConnection(null);
			check(!teams.isSupported() && fake.calls == callsBeforeTeams, "a Teams webhook is not probed: " + teams.getMessage());

			check(SlackConnector.isId("C0123ABCD") && !SlackConnector.isId("general") && !SlackConnector.isId("#C0123ABCD"), "channel id detection");
			check(connector.getDescriptor().getOperations().size() == 5, "5 operations described");
		}
		finally
		{
			server.stop(0);
			if (previousAllowList == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, previousAllowList);
		}
		System.out.println("PASS: Slack connector against a fake Web API (ok:false refusals, channel names, post/notify/update/dm/channels, hints)");
	}

	private static SlackConnector connector(final ConnectorConfig pConfig)
	{
		final SlackConnector connector = new SlackConnector();
		connector.configure(pConfig);
		return connector;
	}

	private static ConnectorConfig config(final int pPort, final String pToken)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("Slack");
		config.setType("slack");
		config.setBaseUrl("http://127.0.0.1:" + pPort + "/api");
		config.setAuthType(ConnectorConfig.AuthType.BEARER);
		config.setApiKey(pToken);
		config.setConnectTimeoutMs(5000);
		config.setReadTimeoutMs(10000);
		return config;
	}

	private static ConnectorException fails(final SlackConnector pConnector, final String pOperation, final Map<String, String> pInputs)
	{
		try
		{
			pConnector.execute(null, pOperation, pInputs, null);
		}
		catch (final ConnectorException expected)
		{
			return expected;
		}
		throw new AssertionError(pOperation + " should have failed with " + pInputs);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	/** In-memory Slack Web API. */
	private static final class Fake implements com.sun.net.httpserver.HttpHandler
	{
		final List<JsonNode> posts = new ArrayList<JsonNode>();
		JsonNode lastUpdate;
		String lastAuthorization;
		int listCalls;
		int calls;
		int webhookMessages;

		@Override
		public void handle(final HttpExchange pExchange) throws IOException
		{
			this.calls++;
			this.lastAuthorization = pExchange.getRequestHeaders().getFirst("Authorization");
			final String path = pExchange.getRequestURI().getPath();
			final String query = pExchange.getRequestURI().getRawQuery() == null ? "" : pExchange.getRequestURI().getRawQuery();
			final JsonNode body = read(pExchange.getRequestBody());
			if (path.endsWith("/hook") || path.endsWith("/badhook"))
			{
				// incoming webhook: plain-text answers, as hooks.slack.com gives them
				final boolean valid = path.endsWith("/hook");
				final boolean hasText = body.hasNonNull("text");
				if (valid && hasText)
				{
					this.webhookMessages++;
				}
				final byte[] bytes = (!valid ? "invalid_token" : hasText ? "ok" : "invalid_payload").getBytes(StandardCharsets.UTF_8);
				pExchange.getResponseHeaders().set("Content-Type", "text/plain");
				pExchange.sendResponseHeaders(!valid ? 403 : hasText ? 200 : 400, bytes.length);
				final OutputStream stream = pExchange.getResponseBody();
				stream.write(bytes);
				stream.close();
				return;
			}
			final ObjectNode out = Json.object();
			final boolean noScope = "Bearer xoxb-noscope".equals(this.lastAuthorization);
			if (path.endsWith("/auth.test"))
			{
				out.put("ok", true).put("team", "Test workspace").put("user", "ebx-bot").put("team_id", "T0000TEST").put("bot_id", "B0000BOT1");
			}
			else if (noScope)
			{
				out.put("ok", false).put("error", "missing_scope").put("needed", "chat:write").put("provided", "channels:read");
			}
			else if (path.endsWith("/conversations.list"))
			{
				this.listCalls++;
				out.put("ok", true);
				if (!query.contains("cursor=page2"))
				{
					out.putArray("channels").addObject().put("id", "C0000GENE").put("name", "general").put("is_member", true).put("num_members", 12);
					out.putObject("response_metadata").put("next_cursor", "page2");
				}
				else
				{
					final ObjectNode alerts = out.putArray("channels").addObject();
					alerts.put("id", "C0000DATA").put("name", "data-alerts").put("is_member", true).put("num_members", 4);
					alerts.putObject("topic").put("value", "Catalog alerts");
					out.putObject("response_metadata").put("next_cursor", "");
				}
			}
			else if (path.endsWith("/chat.postMessage"))
			{
				this.posts.add(body);
				final String channel = body.path("channel").asText();
				if ("C0000PRIV".equals(channel))
				{
					out.put("ok", false).put("error", "not_in_channel");
				}
				else
				{
					out.put("ok", true).put("channel", channel.startsWith("U") ? "D" + channel.substring(1) : channel).put("ts", "1700000000.000100");
				}
			}
			else if (path.endsWith("/chat.update"))
			{
				this.lastUpdate = body;
				out.put("ok", true).put("channel", body.path("channel").asText()).put("ts", body.path("ts").asText());
			}
			else if (path.endsWith("/users.lookupByEmail"))
			{
				if (query.contains("ana%40example.com") || query.contains("ana@example.com"))
				{
					out.put("ok", true).putObject("user").put("id", "U0000ANA1");
				}
				else
				{
					out.put("ok", false).put("error", "users_not_found");
				}
			}
			else
			{
				out.put("ok", false).put("error", "unknown_method");
			}
			final byte[] bytes = Json.write(out).getBytes(StandardCharsets.UTF_8);
			pExchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
			pExchange.sendResponseHeaders(200, bytes.length); // Slack answers 200 even when it refuses
			final OutputStream stream = pExchange.getResponseBody();
			stream.write(bytes);
			stream.close();
		}

		private static JsonNode read(final InputStream pStream) throws IOException
		{
			final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
			final byte[] chunk = new byte[4096];
			for (int n = pStream.read(chunk); n > 0; n = pStream.read(chunk))
			{
				buffer.write(chunk, 0, n);
			}
			final String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
			return text.trim().isEmpty() ? Json.object() : Json.parse(text);
		}
	}
}
