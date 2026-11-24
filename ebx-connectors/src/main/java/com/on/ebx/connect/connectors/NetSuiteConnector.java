package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;

/** NetSuite SuiteTalk REST Record and SuiteQL connector. */
public final class NetSuiteConnector extends AbstractCrudRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("get", "Read a NetSuite record by type and id", "object", "id"),
		Operation.search("query", "Execute bounded SuiteQL", "query"),
		Operation.write("create", "Create a NetSuite record", "object"),
		Operation.write("update", "Update a NetSuite record", "object", "id"),
		Operation.write("upsert", "Upsert by NetSuite external id", "object", "externalId"));

	@Override public String getType() { return "netsuite"; }
	@Override public String getLabel() { return "Oracle NetSuite"; }
	@Override public List<Operation> getOperations() { return OPERATIONS; }

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder builder = ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("NetSuite SuiteTalk REST Record and SuiteQL connector")
			.authentication(AuthenticationMethod.BEARER_TOKEN)
			.authentication(AuthenticationMethod.CUSTOM)
			.configurationProperty(ConfigurationPropertyDefinition.builder("account", ConfigurationPropertyType.STRING).required(true).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("max.rows", ConfigurationPropertyType.INTEGER).defaultValue(Integer.valueOf(200)).build());
		for (final Operation operation : OPERATIONS)
		{
			final ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			builder.direction(direction).operation(new ConnectorOperationDescriptor(operation.getName(), operation.getKind(), operation.getDescription(),
				java.util.Collections.singleton(direction), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return builder.build();
	}

	@Override
	protected String baseUrl() throws ConnectorException
	{
		final String configured = getConfig().getBaseUrl();
		if (configured != null && !configured.trim().isEmpty()) { return configured.replaceAll("/+$", ""); }
		final String account = getConfig().getProperty("account", "").trim().toLowerCase().replace('_', '-');
		if (account.isEmpty()) { throw new ConnectorException("NetSuite: set property account or baseUrl"); }
		return "https://" + account + ".suitetalk.api.netsuite.com/services/rest";
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		headers.put("Accept", "application/json");
		headers.put("Content-Type", "application/json");
		return headers;
	}

	@Override
	public JsonNode execute(final ConnectorContext context, final String operation, final Map<String, String> inputs, final JsonNode payload)
		throws ConnectorException
	{
		requireSecret();
		if ("query".equals(operation))
		{
			final int maximum = bounded(inputs.get("max"), 1, bounded(getConfig().getProperty("max.rows", "200"), 1, 1000));
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("limit", String.valueOf(maximum));
			query.put("offset", inputs.get("offset") == null ? "0" : inputs.get("offset"));
			final Map<String, String> extra = new LinkedHashMap<String, String>();
			extra.put("Prefer", "transient");
			final JsonNode body = Json.object().put("q", AbstractRestConnector.require(inputs, "query"));
			return bodyOrNull(send("POST", "/query/v1/suiteql", query, body, extra), "query");
		}
		final String type = safeName(AbstractRestConnector.require(inputs, "object"));
		final String collection = "/record/v1/" + type;
		if ("get".equals(operation)) { return bodyOrNull(get(collection + "/" + segment(AbstractRestConnector.require(inputs, "id")), null), "get"); }
		if ("create".equals(operation)) { return bodyOrNull(send("POST", collection, null, payload == null ? Json.object() : payload), "create"); }
		if ("update".equals(operation))
		{
			final HttpJsonClient.Response response = send("PATCH", collection + "/" + segment(AbstractRestConnector.require(inputs, "id")), null,
				payload == null ? Json.object() : payload);
			return response.getStatus() == 204 ? Json.object() : bodyOrNull(response, "update");
		}
		if ("upsert".equals(operation))
		{
			final HttpJsonClient.Response response = send("PUT", collection + "/eid:" + segment(AbstractRestConnector.require(inputs, "externalId")), null,
				payload == null ? Json.object() : payload);
			return response.getStatus() == 204 ? Json.object() : bodyOrNull(response, "upsert");
		}
		throw new ConnectorException("NetSuite: unknown operation '" + operation + "'");
	}

	private static int bounded(final String value, final int minimum, final int maximum)
	{
		try { return Math.max(minimum, Math.min(maximum, Integer.parseInt(value))); }
		catch (final RuntimeException ex) { return maximum; }
	}
}
