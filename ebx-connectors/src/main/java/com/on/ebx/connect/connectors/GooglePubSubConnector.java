package com.on.ebx.connect.connectors;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.GoogleAuth;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;

/** Google Cloud Pub/Sub v1 with bounded pulls and explicit acknowledgement. */
public final class GooglePubSubConnector extends AbstractRestConnector
{
	private static final String SCOPE = "https://www.googleapis.com/auth/pubsub";
	private static final int MAX_RESPONSE = 1048576;
	private static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
		new Operation("publish", Operation.Kind.WRITE, "Publish one message to a topic", new String[] { "topic" }, new String[] { "data" }),
		new Operation("pull", Operation.Kind.READ, "Pull messages without acknowledging them", new String[] { "subscription" }, new String[] { "maxMessages" }),
		new Operation("ack", Operation.Kind.WRITE, "Explicitly acknowledge supplied ack IDs", new String[] { "subscription", "ackIds" }, new String[0]),
		new Operation("topics", Operation.Kind.SEARCH, "Discover topics in the configured project", new String[0], new String[] { "pageSize", "pageToken" }),
		new Operation("subscriptions", Operation.Kind.SEARCH, "Discover subscriptions in the configured project", new String[0], new String[] { "pageSize", "pageToken" })));
	private final GoogleAuth auth = new GoogleAuth();

	@Override public String getType() { return "google-pubsub"; }
	@Override public String getLabel() { return "Google Cloud Pub/Sub"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(getType(), getLabel(), "MESSAGING")
			.description("Google Cloud Pub/Sub v1: bounded publish, pull without auto-ack, explicit acknowledgement, and topic/subscription discovery.")
			.authentication(AuthenticationMethod.SERVICE_ACCOUNT).authentication(AuthenticationMethod.BEARER_TOKEN)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.DISCOVER_OBJECTS)
			.capability(ConnectorCapability.PREVIEW_RECORDS).apiVersion("Google Cloud Pub/Sub API v1")
			.configurationProperty(property("project", ConfigurationPropertyType.STRING, true))
			.configurationProperty(property("serviceAccountJson", ConfigurationPropertyType.SECRET, false))
			.configurationProperty(property("serviceAccountFile", ConfigurationPropertyType.FILE, false));
		for (final Operation op : OPERATIONS)
		{
			final ConnectorDirection d = op.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(op.getName(), op.getKind(), op.getDescription(),
				Collections.singleton(d), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		final Map<String, String> inputs = pInputs == null ? Collections.<String, String>emptyMap() : pInputs;
		if ("publish".equals(pOperation))
		{
			final String topic = resource("topics", required(inputs, "topic"));
			final ObjectNode message = Json.object();
			String data = inputs.get("data");
			if (data == null && pPayload != null) data = pPayload.isTextual() ? pPayload.asText() : pPayload.toString();
			if (data == null) throw new ConnectorException("Pub/Sub publish requires input data or a payload");
			if (data.getBytes(StandardCharsets.UTF_8).length > 10 * 1024 * 1024) throw new ConnectorException("Pub/Sub message exceeds 10 MiB");
			message.put("data", Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8)));
			final ObjectNode body = Json.object();
			body.putArray("messages").add(message);
			return call("POST", "/" + topic + ":publish", body);
		}
		if ("pull".equals(pOperation))
		{
			final int max = bounded(inputs.get("maxMessages"), 1, 1000, 100);
			final ObjectNode body = Json.object();
			body.put("maxMessages", max);
			body.put("returnImmediately", true);
			final JsonNode answer = call("POST", "/" + resource("subscriptions", required(inputs, "subscription")) + ":pull", body);
			final ObjectNode out = Json.object();
			out.put("autoAcknowledged", false);
			out.set("receivedMessages", answer != null && answer.path("receivedMessages").isArray()
				? answer.path("receivedMessages") : Json.mapper().createArrayNode());
			return out;
		}
		if ("ack".equals(pOperation))
		{
			final ArrayNode ids = Json.mapper().createArrayNode();
			for (final String id : required(inputs, "ackIds").split(","))
				if (!id.trim().isEmpty()) ids.add(id.trim());
			if (ids.size() == 0 || ids.size() > 1000) throw new ConnectorException("Pub/Sub ack requires 1 to 1000 explicit ack IDs");
			final ObjectNode body = Json.object();
			body.set("ackIds", ids);
			call("POST", "/" + resource("subscriptions", required(inputs, "subscription")) + ":acknowledge", body);
			final ObjectNode out = Json.object();
			out.put("acknowledged", ids.size());
			return out;
		}
		if ("topics".equals(pOperation) || "subscriptions".equals(pOperation))
		{
			final int size = bounded(inputs.get("pageSize"), 1, 1000, 100);
			String path = "/projects/" + project() + "/" + pOperation + "?pageSize=" + size;
			if (inputs.get("pageToken") != null) path += "&pageToken=" + HttpJsonClient.encode(inputs.get("pageToken"));
			return call("GET", path, null);
		}
		throw new ConnectorException("Pub/Sub: unknown operation '" + pOperation + "'");
	}

	private JsonNode call(final String pMethod, final String pPath, final JsonNode pBody) throws ConnectorException
	{
		final ConnectorConfig cfg = getConfig();
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Authorization", "Bearer " + auth.token(cfg, SCOPE));
		headers.put("Content-Type", "application/json");
		try
		{
			final HttpJsonClient.Response response = new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(),
				"EBX-Connectors/1.0 (google-pubsub)").sendBytesWithoutRedirects(pMethod, endpoint() + pPath, headers,
				pBody == null ? null : pBody.toString().getBytes(StandardCharsets.UTF_8), MAX_RESPONSE);
			if (!response.isSuccess()) throw new ConnectorException("Pub/Sub request failed (HTTP " + response.getStatus() + ")", response.getStatus(), null);
			return response.getBody() == null ? Json.object() : response.getBody();
		}
		catch (final java.io.IOException ex) { throw new ConnectorException("Pub/Sub request failed: " + ex.getMessage(), ex); }
	}

	private String endpoint() throws ConnectorException
	{
		final String configured = getConfig().getBaseUrl();
		final String endpoint = configured == null || configured.trim().isEmpty()
			? "https://pubsub.googleapis.com/v1" : configured.trim().replaceAll("/+$", "");
		final URI uri;
		try
		{
			uri = URI.create(endpoint);
		}
		catch (final RuntimeException ex)
		{
			throw new ConnectorException("Pub/Sub endpoint is invalid");
		}
		final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
		final boolean official = "https".equalsIgnoreCase(uri.getScheme()) && "pubsub.googleapis.com".equals(host) && "/v1".equals(uri.getPath());
		final boolean local = configured != null && "http".equalsIgnoreCase(uri.getScheme())
			&& ("127.0.0.1".equals(host) || "localhost".equals(host));
		if (!official && !local) throw new ConnectorException("Pub/Sub endpoint must be the official v1 API or an explicit localhost fixture");
		try
		{
			Egress.check(endpoint);
		}
		catch (final IllegalStateException ex)
		{
			throw new ConnectorException(ex.getMessage());
		}
		return endpoint;
	}

	private String project() throws ConnectorException
	{
		final String p = getConfig().getProperty("project", null);
		if (p == null || !p.matches("[A-Za-z0-9._~-]+")) throw new ConnectorException("Pub/Sub project is missing or invalid");
		return p;
	}
	private String resource(final String pKind, final String pValue) throws ConnectorException
	{
		if (pValue.matches("projects/[A-Za-z0-9._~-]+/" + pKind + "/[A-Za-z0-9._~-]+")) return pValue;
		if (!pValue.matches("[A-Za-z0-9._~-]+")) throw new ConnectorException("Pub/Sub " + pKind + " name is invalid");
		return "projects/" + project() + "/" + pKind + "/" + pValue;
	}
	private static String required(final Map<String, String> pInputs, final String pName) throws ConnectorException
	{
		final String value = pInputs.get(pName);
		if (value == null || value.trim().isEmpty()) throw new ConnectorException("Missing input '" + pName + "'");
		return value.trim();
	}
	private static int bounded(final String pValue, final int pMin, final int pMax, final int pDefault) throws ConnectorException
	{
		try
		{
			final int n = pValue == null ? pDefault : Integer.parseInt(pValue);
			if (n < pMin || n > pMax) throw new Exception();
			return n;
		}
		catch (final Exception ex) { throw new ConnectorException("Value must be between " + pMin + " and " + pMax); }
	}
	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType, final boolean pRequired)
	{ return ConfigurationPropertyDefinition.builder(pName, pType).required(pRequired).build(); }
}
