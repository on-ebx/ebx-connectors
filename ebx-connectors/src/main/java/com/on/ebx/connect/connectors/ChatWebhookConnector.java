package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Slack or Microsoft Teams incoming webhook: the base URL is the webhook URL (it carries the secret,
 * treat the connector accordingly). Operation {@code notify} (text, optional title) posts a message;
 * the format is chosen by the {@code format} property ({@code auto}, {@code slack}, {@code teams} for
 * an Office 365 connector card, {@code adaptive} for a Power Automate workflow). An outbound mapping
 * can also build the document itself: a {@code text} field is used when the input is empty.
 */
public final class ChatWebhookConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(Operation.write("notify", "Post a message (text, optional title)", "text"));

	@Override
	public String getType()
	{
		return "chat-webhook";
	}

	@Override
	public String getLabel()
	{
		return "Slack / Microsoft Teams incoming webhook";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = descriptor("MESSAGING");
		b.authentication(com.on.ebx.connect.spi.AuthenticationMethod.CUSTOM).apiVersion("Provider-defined incoming webhook").configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("format", com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).enumerationValues("auto", "slack", "teams", "adaptive").defaultValue("auto").build());
		return b.build();
	}
	private com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor(final String c)
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), c);
		for (final com.on.ebx.connect.spi.Operation op:getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = op.getKind() == com.on.ebx.connect.spi.Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b;
	}

	@Override
	public List<Operation> getOperations()
	{
		return ChatWebhookConnector.OPERATIONS;
	}

	private String format()
	{
		final String declared = this.getConfig().getProperty("format", "auto").trim().toLowerCase();
		if (!"auto".equals(declared))
		{
			return declared;
		}
		final String url = this.getConfig().getBaseUrl() == null ? "" : this.getConfig().getBaseUrl();
		if (url.contains("hooks.slack.com"))
		{
			return "slack";
		}
		if (url.contains("logic.azure.com") || url.contains("powerautomate"))
		{
			return "adaptive";
		}
		if (url.contains("webhook.office.com"))
		{
			return "teams";
		}
		return "slack";
	}

	/**
	 * The generic HEAD probe answers 400 on every webhook, valid or not. A Slack webhook is probed with an empty JSON
	 * body instead: Slack refuses it without posting anything, with 400 invalid_payload (or no_text) when the URL is
	 * valid and 403 invalid_token / 404 no_service when it is not (measured on hooks.slack.com, 2026-09-15). A Teams
	 * or Power Automate webhook has no such refusal (an empty body can start the flow): not verifiable.
	 */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final String format = this.format();
		if (!"slack".equals(format))
		{
			return ConnectionTestResult.unsupported("A " + ("adaptive".equals(format) ? "Power Automate" : "Teams")
				+ " webhook cannot be verified without posting a message: run the flow once to check it");
		}
		final long started = System.nanoTime();
		final HttpJsonClient.Response response = this.send("POST", "", null, Json.object());
		final long elapsed = (System.nanoTime() - started) / 1000000L;
		final String answer = response.getRawBody() == null ? "" : response.getRawBody().trim();
		if (response.getStatus() == 400 && ("invalid_payload".equals(answer) || "no_text".equals(answer) || "missing_text_or_fallback_or_attachments".equals(answer)))
		{
			return ConnectionTestResult.success("Slack webhook accepted (empty test payload refused as " + answer + ", nothing posted)", elapsed);
		}
		if (response.isSuccess())
		{
			return ConnectionTestResult.success("Webhook endpoint answered HTTP " + response.getStatus(), elapsed);
		}
		return ConnectionTestResult.failure("Slack webhook refused: HTTP " + response.getStatus()
			+ (answer.isEmpty() ? "" : " " + (answer.length() > 120 ? answer.substring(0, 120) + "..." : answer)), elapsed);
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		if (!"notify".equals(pOperation))
		{
			throw new ConnectorException(this.getConfig().getId() + ": unknown operation '" + pOperation + "'");
		}
		String text = pInputs == null ? null : pInputs.get("text");
		String title = pInputs == null ? null : pInputs.get("title");
		if ((text == null || text.trim().isEmpty()) && pPayload != null)
		{
			text = pPayload.path("text").asText(null);
			if (title == null)
			{
				title = pPayload.path("title").asText(null);
			}
			if (text == null && !pPayload.isNull())
			{
				text = Json.write(pPayload);
			}
		}
		if (text == null || text.trim().isEmpty())
		{
			throw new ConnectorException("Missing input 'text'");
		}
		final ObjectNode body = Json.object();
		final String format = this.format();
		if ("teams".equals(format))
		{
			body.put("@type", "MessageCard");
			body.put("@context", "https://schema.org/extensions");
			body.put("summary", title == null ? text.substring(0, Math.min(80, text.length())) : title);
			if (title != null)
			{
				body.put("title", title);
			}
			body.put("text", text);
		}
		else if ("adaptive".equals(format))
		{
			body.put("type", "message");
			final ObjectNode attachment = body.putArray("attachments").addObject();
			attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
			final ObjectNode card = attachment.putObject("content");
			card.put("type", "AdaptiveCard");
			card.put("version", "1.4");
			card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
			final ArrayNode items = card.putArray("body");
			if (title != null)
			{
				final ObjectNode heading = items.addObject();
				heading.put("type", "TextBlock");
				heading.put("text", title);
				heading.put("weight", "Bolder");
				heading.put("size", "Medium");
			}
			final ObjectNode line = items.addObject();
			line.put("type", "TextBlock");
			line.put("text", text);
			line.put("wrap", true);
		}
		else
		{
			body.put("text", title == null ? text : "*" + title + "*\n" + text);
		}
		final HttpJsonClient.Response response = this.send("POST", "", null, body);
		if (!response.isSuccess())
		{
			throw new ConnectorException(this.getConfig().getId() + ": notify -> HTTP " + response.getStatus() + " " + String.valueOf(response.getRawBody()),
				response.getStatus(), null);
		}
		final ObjectNode out = Json.object();
		out.put("status", "sent");
		out.put("http", response.getStatus());
		out.put("format", format);
		return out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		return Arrays.asList(FieldInfo.of("status", "sent", "string"), FieldInfo.of("http", "HTTP status", "number"), FieldInfo.of("format", "Format used", "string"));
	}
}
