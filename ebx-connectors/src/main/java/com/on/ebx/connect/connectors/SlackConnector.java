package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Slack Web API ({@code https://slack.com/api}) with a bot token ({@code xoxb-...}) in {@code apiKey}, sent as
 * Bearer. Unlike the {@code chat-webhook} type, the secret is an encrypted credential, one connector reaches
 * every channel the app may post to, and a posted message can be edited afterwards.
 * <p>
 * Operations: {@code post} (channel, text, title, thread, Block Kit blocks), {@code notify} (same as post, so a
 * flow written for {@code chat-webhook} moves over unchanged), {@code update} (edit a message by channel + ts),
 * {@code dm} (direct message to a user found by email) and {@code channels} (the channels the token can see).
 * <p>
 * Slack answers HTTP 200 with {@code {"ok":false,"error":"..."}} when it refuses a call: every answer is checked
 * on {@code ok}, never on the status alone, and the error code is turned into a hint (missing scope, app not in
 * the channel, unknown user...). A channel may be given as an id ({@code C0123ABCD}) or a name ({@code #alerts});
 * names are resolved once through {@code conversations.list} and cached for the life of the connector.
 * Scopes: {@code chat:write} (+ {@code chat:write.public} to post in a public channel without inviting the app),
 * {@code channels:read} (names, {@code channels}), {@code groups:read} (private channels), {@code users:read.email}
 * ({@code dm}).
 */
public final class SlackConnector extends AbstractRestConnector
{
	private static final String[] MESSAGE_INPUTS = { "channel", "title", "threadTs", "blocks", "replyBroadcast" };

	private static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
		new Operation("post", Operation.Kind.WRITE, "Post a message to a channel (text, optional title, thread and Block Kit blocks)",
			new String[] { "text" }, SlackConnector.MESSAGE_INPUTS),
		new Operation("notify", Operation.Kind.WRITE, "Same as post: a flow written for the chat-webhook type works unchanged",
			new String[] { "text" }, SlackConnector.MESSAGE_INPUTS),
		new Operation("update", Operation.Kind.WRITE, "Edit a message this app posted (channel and ts returned by post)",
			new String[] { "ts", "text" }, new String[] { "channel", "title", "blocks" }),
		new Operation("dm", Operation.Kind.WRITE, "Direct message to a workspace member found by email (or user id)",
			new String[] { "text" }, new String[] { "email", "user", "title", "blocks" }),
		new Operation("channels", Operation.Kind.SEARCH, "Channels the token can see (optional name filter)",
			new String[0], new String[] { "name", "types", "max" })));

	/** Channel name (lower case, without #) to id, filled by conversations.list. */
	private final Map<String, String> channelIds = new ConcurrentHashMap<String, String>();

	@Override
	public String getType()
	{
		return "slack";
	}

	@Override
	public String getLabel()
	{
		return "Slack (Web API, bot token)";
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(this.getType(), this.getLabel(), "MESSAGING")
			.description("Slack Web API: post, edit and direct messages with a bot token, channel discovery")
			.authentication(AuthenticationMethod.BEARER_TOKEN).apiVersion("Web API")
			.configurationProperty(ConfigurationPropertyDefinition.builder("channel", ConfigurationPropertyType.STRING)
				.description("Default channel (id or #name) when a flow gives none").build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("channelTypes", ConfigurationPropertyType.STRING)
				.description("Conversation types searched to resolve a channel name: public_channel, private_channel (needs groups:read)").advanced(true).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("escapeText", ConfigurationPropertyType.BOOLEAN).defaultValue(Boolean.TRUE)
				.description("Escape &, < and > in text and title; false keeps Slack markup such as <@U123> mentions or <url|label> links").advanced(true).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("unfurlLinks", ConfigurationPropertyType.BOOLEAN).defaultValue(Boolean.FALSE)
				.description("Let Slack preview the links of a message").advanced(true).build());
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
		return SlackConnector.OPERATIONS;
	}

	/** The token is sent as Bearer whatever the authentication type saved on the row. */
	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		final String token = this.getConfig().getApiKey();
		if (!headers.containsKey("Authorization") && token != null && !this.getConfig().isPlaceholder(token))
		{
			headers.put("Authorization", "Bearer " + token.trim());
		}
		return headers;
	}

	private void requireToken() throws ConnectorException
	{
		final String token = this.getConfig().getApiKey();
		if (token == null || token.trim().isEmpty() || this.getConfig().isPlaceholder(token))
		{
			throw new ConnectorException("Connector " + this.getConfig().getId()
				+ ": bot token not configured (Connectors > Connector > " + this.getConfig().getId() + " > apiKey, xoxb-...)");
		}
	}

	/** auth.test: reads who the token is, changes nothing. */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.nanoTime();
		this.requireToken();
		try
		{
			final JsonNode who = this.call("GET", "auth.test", null, null);
			return ConnectionTestResult.success("Slack workspace '" + who.path("team").asText("?") + "' as " + who.path("user").asText("?"),
				(System.nanoTime() - started) / 1000000L);
		}
		catch (final ConnectorException ex)
		{
			return ConnectionTestResult.failure(ex.getMessage(), (System.nanoTime() - started) / 1000000L);
		}
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		final Map<String, String> inputs = SlackConnector.merge(pInputs, pPayload);
		if ("post".equals(pOperation) || "notify".equals(pOperation))
		{
			this.requireToken();
			final String channel = this.channelId(this.channelOf(inputs));
			final ObjectNode body = this.message(inputs, pPayload);
			body.put("channel", channel);
			final String thread = SlackConnector.value(inputs, "threadTs");
			if (thread != null)
			{
				body.put("thread_ts", thread);
				if ("true".equalsIgnoreCase(SlackConnector.value(inputs, "replyBroadcast")))
				{
					body.put("reply_broadcast", true);
				}
			}
			final JsonNode answer = this.call("POST", "chat.postMessage", null, body);
			final ObjectNode out = SlackConnector.sent("sent", answer);
			out.put("threadTs", thread == null ? answer.path("ts").asText() : thread);
			return out;
		}
		if ("update".equals(pOperation))
		{
			this.requireToken();
			final ObjectNode body = this.message(inputs, pPayload);
			body.put("channel", this.channelId(this.channelOf(inputs)));
			body.put("ts", AbstractRestConnector.require(inputs, "ts"));
			return SlackConnector.sent("updated", this.call("POST", "chat.update", null, body));
		}
		if ("dm".equals(pOperation))
		{
			this.requireToken();
			String user = SlackConnector.value(inputs, "user");
			if (user == null)
			{
				final String email = SlackConnector.value(inputs, "email");
				if (email == null)
				{
					throw new ConnectorException("Missing input 'email' (or 'user')");
				}
				final Map<String, String> query = new LinkedHashMap<String, String>();
				query.put("email", email);
				user = this.call("GET", "users.lookupByEmail", query, null).path("user").path("id").asText(null);
			}
			final ObjectNode body = this.message(inputs, pPayload);
			body.put("channel", user); // a user id opens the conversation between the app and that member
			final ObjectNode out = SlackConnector.sent("sent", this.call("POST", "chat.postMessage", null, body));
			out.put("user", user);
			return out;
		}
		if ("channels".equals(pOperation))
		{
			this.requireToken();
			final String filter = SlackConnector.value(inputs, "name");
			final String max = SlackConnector.value(inputs, "max");
			final int limit = max == null ? 1000 : Math.max(1, Integer.parseInt(max.trim()));
			final ArrayNode items = Json.mapper().createArrayNode();
			for (final JsonNode channel : this.listChannels(SlackConnector.value(inputs, "types")))
			{
				final String name = channel.path("name").asText("");
				if (filter != null && !name.toLowerCase(Locale.ROOT).contains(SlackConnector.bare(filter)))
				{
					continue;
				}
				if (items.size() >= limit)
				{
					break;
				}
				final ObjectNode item = items.addObject();
				item.put("id", channel.path("id").asText());
				item.put("name", name);
				item.put("isPrivate", channel.path("is_private").asBoolean(false));
				item.put("isMember", channel.path("is_member").asBoolean(false));
				item.put("isArchived", channel.path("is_archived").asBoolean(false));
				item.put("members", channel.path("num_members").asInt(0));
				item.put("topic", channel.path("topic").path("value").asText(""));
				item.put("purpose", channel.path("purpose").path("value").asText(""));
			}
			final ObjectNode out = Json.object();
			out.put("count", items.size());
			out.set("items", items);
			return out;
		}
		throw new ConnectorException(this.getConfig().getId() + ": unknown operation '" + pOperation + "'");
	}

	/** chat.postMessage / chat.update body: text (with the title in bold), blocks, link previews. */
	private ObjectNode message(final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final boolean escape = !"false".equalsIgnoreCase(this.getConfig().getProperty("escapeText", "true").trim());
		String text = AbstractRestConnector.require(pInputs, "text");
		final String title = SlackConnector.value(pInputs, "title");
		if (escape)
		{
			text = SlackConnector.escape(text);
		}
		if (title != null)
		{
			text = "*" + (escape ? SlackConnector.escape(title) : title) + "*\n" + text;
		}
		if (text.length() > 40000)
		{
			throw new ConnectorException(this.getConfig().getId() + ": message longer than the 40,000 characters Slack accepts (" + text.length() + ")");
		}
		final ObjectNode body = Json.object();
		body.put("text", text); // also the notification and accessibility text when blocks are given
		final JsonNode blocks = this.blocks(pInputs, pPayload);
		if (blocks != null)
		{
			body.set("blocks", blocks);
		}
		final boolean unfurl = "true".equalsIgnoreCase(this.getConfig().getProperty("unfurlLinks", "false").trim());
		body.put("unfurl_links", unfurl);
		body.put("unfurl_media", unfurl);
		return body;
	}

	private JsonNode blocks(final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final String text = SlackConnector.value(pInputs, "blocks");
		if (text == null)
		{
			final JsonNode fromPayload = pPayload == null ? null : pPayload.get("blocks");
			return fromPayload != null && fromPayload.isArray() ? fromPayload : null;
		}
		try
		{
			final JsonNode parsed = Json.parse(text);
			final JsonNode array = parsed != null && parsed.isObject() ? parsed.get("blocks") : parsed;
			if (array == null || !array.isArray())
			{
				throw new ConnectorException(this.getConfig().getId() + ": input 'blocks' must be a JSON array of Block Kit blocks (or {\"blocks\":[...]})");
			}
			return array;
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException(this.getConfig().getId() + ": input 'blocks' is not valid JSON: " + ex.getMessage(), ex);
		}
	}

	private String channelOf(final Map<String, String> pInputs) throws ConnectorException
	{
		final String channel = SlackConnector.value(pInputs, "channel");
		if (channel != null)
		{
			return channel;
		}
		final String fallback = this.getConfig().getProperty("channel", null);
		if (fallback == null || fallback.trim().isEmpty() || this.getConfig().isPlaceholder(fallback))
		{
			throw new ConnectorException("Missing input 'channel' (or the connector property channel)");
		}
		return fallback.trim();
	}

	/** An id is used as is; a name is looked up once in conversations.list. */
	String channelId(final String pChannel) throws ConnectorException
	{
		final String channel = pChannel.trim();
		if (SlackConnector.isId(channel))
		{
			return channel;
		}
		final String name = SlackConnector.bare(channel);
		String id = this.channelIds.get(name);
		if (id == null)
		{
			this.listChannels(null); // fills the cache
			id = this.channelIds.get(name);
		}
		if (id == null)
		{
			throw new ConnectorException(this.getConfig().getId() + ": no channel named '" + name + "' visible to this token. A private channel needs the "
				+ "groups:read scope, the property channelTypes = public_channel,private_channel and the app invited to it; or give the channel id "
				+ "(channel details > About, C...).", 404, null);
		}
		return id;
	}

	private List<JsonNode> listChannels(final String pTypes) throws ConnectorException
	{
		final String types = pTypes != null ? pTypes : this.getConfig().getProperty("channelTypes", "public_channel").trim();
		final List<JsonNode> channels = new ArrayList<JsonNode>();
		String cursor = null;
		for (int page = 0; page < 50; page++) // 50 x 200 channels, a bound against a cursor that never ends
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("types", types.isEmpty() ? "public_channel" : types);
			query.put("exclude_archived", "true");
			query.put("limit", "200");
			if (cursor != null)
			{
				query.put("cursor", cursor);
			}
			final JsonNode answer = this.call("GET", "conversations.list", query, null);
			for (final JsonNode channel : answer.path("channels"))
			{
				channels.add(channel);
				if (channel.hasNonNull("name") && channel.hasNonNull("id"))
				{
					this.channelIds.put(channel.get("name").asText().toLowerCase(Locale.ROOT), channel.get("id").asText());
				}
			}
			cursor = answer.path("response_metadata").path("next_cursor").asText("");
			if (cursor.isEmpty())
			{
				break;
			}
		}
		return channels;
	}

	/** One Web API call; a refusal ({@code ok:false}) becomes an exception with a status and a hint. */
	private JsonNode call(final String pHttpMethod, final String pMethod, final Map<String, String> pQuery, final JsonNode pBody)
		throws ConnectorException
	{
		final HttpJsonClient.Response response = this.send(pHttpMethod, "/" + pMethod, pQuery, pBody);
		final String id = this.getConfig().getId();
		if (response.getStatus() == 429)
		{
			final String after = response.getHeader("Retry-After");
			throw new ConnectorException(id + ": " + pMethod + " rate limited by Slack" + (after == null ? "" : ", retry after " + after + " s")
				+ " (about one message per second per channel)", 429, null);
		}
		final JsonNode body = response.getBody();
		if (!response.isSuccess() || body == null || !body.isObject())
		{
			String detail = response.getRawBody();
			if (detail != null && detail.length() > 300)
			{
				detail = detail.substring(0, 300) + "...";
			}
			throw new ConnectorException(id + ": " + pMethod + " -> HTTP " + response.getStatus() + (detail == null || detail.isEmpty() ? "" : " " + detail),
				response.getStatus() >= 400 ? response.getStatus() : 502, null);
		}
		if (!body.path("ok").asBoolean(false))
		{
			final String error = body.path("error").asText("unknown_error");
			throw new ConnectorException(id + ": " + pMethod + " refused by Slack: " + error + SlackConnector.hint(error, body), SlackConnector.status(error), null);
		}
		return body;
	}

	static String hint(final String pError, final JsonNode pAnswer)
	{
		if ("missing_scope".equals(pError))
		{
			return " - add the scope " + pAnswer.path("needed").asText("?") + " under OAuth & Permissions (Bot Token Scopes), then reinstall the app"
				+ (pAnswer.hasNonNull("provided") ? " (token has: " + pAnswer.get("provided").asText() + ")" : "");
		}
		if ("not_in_channel".equals(pError))
		{
			return " - invite the app to the channel (/invite @app), or add the chat:write.public scope for a public channel";
		}
		if ("channel_not_found".equals(pError))
		{
			return " - unknown channel, or a private channel the app was not invited to";
		}
		if ("invalid_auth".equals(pError) || "not_authed".equals(pError) || "token_revoked".equals(pError) || "account_inactive".equals(pError)
			|| "token_expired".equals(pError))
		{
			return " - check the bot token (xoxb-...) in apiKey; reinstalling the app issues a new one";
		}
		if ("users_not_found".equals(pError))
		{
			return " - no member with this email in the workspace (the lookup needs the users:read.email scope)";
		}
		if ("message_not_found".equals(pError) || "cant_update_message".equals(pError) || "edit_window_closed".equals(pError))
		{
			return " - only a message posted by this app can be edited, with the channel and ts returned by post";
		}
		if ("is_archived".equals(pError))
		{
			return " - the channel is archived";
		}
		if ("invalid_blocks".equals(pError) || "invalid_blocks_format".equals(pError))
		{
			return " - check the blocks with Slack's Block Kit Builder";
		}
		if ("ratelimited".equals(pError))
		{
			return " - about one message per second per channel";
		}
		return "";
	}

	static int status(final String pError)
	{
		if ("invalid_auth".equals(pError) || "not_authed".equals(pError) || "token_revoked".equals(pError) || "account_inactive".equals(pError)
			|| "token_expired".equals(pError))
		{
			return 401;
		}
		if ("missing_scope".equals(pError) || "not_in_channel".equals(pError) || "restricted_action".equals(pError) || "is_archived".equals(pError)
			|| "cant_update_message".equals(pError) || "edit_window_closed".equals(pError))
		{
			return 403;
		}
		if ("channel_not_found".equals(pError) || "users_not_found".equals(pError) || "user_not_found".equals(pError) || "message_not_found".equals(pError))
		{
			return 404;
		}
		if ("ratelimited".equals(pError))
		{
			return 429;
		}
		return 400;
	}

	private static ObjectNode sent(final String pStatus, final JsonNode pAnswer)
	{
		final ObjectNode out = Json.object();
		out.put("status", pStatus);
		out.put("channel", pAnswer.path("channel").asText());
		out.put("ts", pAnswer.path("ts").asText());
		return out;
	}

	/** Inputs win; a document built by an outbound mapping fills what they leave empty. */
	private static Map<String, String> merge(final Map<String, String> pInputs, final JsonNode pPayload)
	{
		final Map<String, String> merged = new LinkedHashMap<String, String>();
		if (pPayload != null && pPayload.isObject())
		{
			for (final String name : new String[] { "text", "title", "channel", "threadTs", "ts", "email", "user", "replyBroadcast" })
			{
				final JsonNode value = pPayload.get(name);
				if (value != null && value.isValueNode() && !value.asText().trim().isEmpty())
				{
					merged.put(name, value.asText());
				}
			}
		}
		if (pInputs != null)
		{
			for (final Map.Entry<String, String> input : pInputs.entrySet())
			{
				if (input.getValue() != null && !input.getValue().trim().isEmpty())
				{
					merged.put(input.getKey(), input.getValue());
				}
			}
		}
		return merged;
	}

	private static String value(final Map<String, String> pInputs, final String pName)
	{
		final String value = pInputs.get(pName);
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	/** C (public), G (private, legacy), D (direct), U/W (user): upper-case letters and digits. */
	static boolean isId(final String pChannel)
	{
		return pChannel.matches("[CGDUW][A-Z0-9]{8,}");
	}

	private static String bare(final String pName)
	{
		final String name = pName.trim();
		return (name.startsWith("#") ? name.substring(1) : name).toLowerCase(Locale.ROOT);
	}

	/** The three characters Slack reads as markup. */
	static String escape(final String pText)
	{
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("channels".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("count", "Channels returned", "number"), FieldInfo.of("items[0].id", "Channel id", "string"),
				FieldInfo.of("items[0].name", "Channel name", "string"), FieldInfo.of("items[0].isPrivate", "Private", "boolean"),
				FieldInfo.of("items[0].isMember", "App is a member", "boolean"), FieldInfo.of("items[0].members", "Members", "number"),
				FieldInfo.of("items[0].topic", "Topic", "string"), FieldInfo.of("items[0].purpose", "Purpose", "string"));
		}
		final List<FieldInfo> fields = new ArrayList<FieldInfo>(Arrays.asList(FieldInfo.of("status", "sent or updated", "string"),
			FieldInfo.of("channel", "Channel id", "string"), FieldInfo.of("ts", "Message timestamp (its id, for update)", "string")));
		if ("post".equals(pOperation) || "notify".equals(pOperation))
		{
			fields.add(FieldInfo.of("threadTs", "Thread timestamp (to reply in the thread)", "string"));
		}
		if ("dm".equals(pOperation))
		{
			fields.add(FieldInfo.of("user", "Member id", "string"));
		}
		return fields;
	}
}
