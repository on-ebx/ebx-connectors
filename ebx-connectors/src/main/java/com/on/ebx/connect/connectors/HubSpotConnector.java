package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;

/**
 * HubSpot CRM v3 ({@code https://api.hubapi.com}), {@code apiKey} = private app token (BEARER).
 * {@code object} = companies, contacts, deals or a custom object; documents are flattened:
 * the HubSpot {@code properties} become top-level fields next to {@code id}. {@code query}
 * takes {@code filter} as {@code property OPERATOR value} (EQ, NEQ, CONTAINS_TOKEN, GT, LT, HAS_PROPERTY)
 * separated by {@code ;}; {@code upsert} with {@code lookupField} + {@code value} (e.g. domain) searches first.
 */
public final class HubSpotConnector extends AbstractCrudRestConnector
{
	@Override
	public String getType()
	{
		return "hubspot";
	}

	@Override
	public String getLabel()
	{
		return "HubSpot CRM";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("HubSpot CRM v3 connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN).apiVersion("v3");
		for (final com.on.ebx.connect.spi.Operation o : getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == com.on.ebx.connect.spi.Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.requireSecret();
		final String object = AbstractCrudRestConnector.safeName(AbstractRestConnector.require(pInputs, "object"));
		final String base = "/crm/v3/objects/" + object;
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if (pInputs.get("select") != null && !pInputs.get("select").trim().isEmpty())
		{
			query.put("properties", pInputs.get("select").trim());
		}
		if ("get".equals(pOperation))
		{
			return HubSpotConnector.flatten(this.bodyOrNull(this.get(base + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "id")), query), "get"));
		}
		if ("query".equals(pOperation))
		{
			return this.search(base, pInputs.get("filter"), pInputs.get("select"), pInputs.get("max"));
		}
		if ("create".equals(pOperation))
		{
			return HubSpotConnector.flatten(this.bodyOrNull(this.send("POST", base, null, HubSpotConnector.wrap(pPayload)), "create"));
		}
		if ("update".equals(pOperation))
		{
			return HubSpotConnector.flatten(this.bodyOrNull(this.send("PATCH", base + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "id")), null,
				HubSpotConnector.wrap(pPayload)), "update"));
		}
		if ("upsert".equals(pOperation))
		{
			String id = pInputs.get("id");
			if (id == null || id.trim().isEmpty())
			{
				final String field = AbstractRestConnector.require(pInputs, "lookupField");
				final JsonNode found = this.search(base, field + " EQ " + AbstractRestConnector.require(pInputs, "value"), null, "1");
				final JsonNode hit = Json.at(found, "results[0].id");
				id = hit == null ? null : hit.asText();
			}
			if (id == null)
			{
				return HubSpotConnector.flatten(this.bodyOrNull(this.send("POST", base, null, HubSpotConnector.wrap(pPayload)), "create"));
			}
			return HubSpotConnector.flatten(this.bodyOrNull(this.send("PATCH", base + "/" + AbstractRestConnector.segment(id), null, HubSpotConnector.wrap(pPayload)), "update"));
		}
		throw new ConnectorException("HubSpot: unknown operation '" + pOperation + "'");
	}

	private JsonNode search(final String pBase, final String pFilter, final String pSelect, final String pMax) throws ConnectorException
	{
		final ObjectNode body = Json.object();
		if (pFilter != null && !pFilter.trim().isEmpty())
		{
			final ArrayNode filters = body.putArray("filterGroups").addObject().putArray("filters");
			for (final String clause : pFilter.split(";"))
			{
				final String[] parts = clause.trim().split("\\s+", 3);
				if (parts.length < 2)
				{
					continue;
				}
				final ObjectNode filter = filters.addObject();
				filter.put("propertyName", parts[0]);
				filter.put("operator", parts[1].toUpperCase());
				if (parts.length == 3)
				{
					filter.put("value", parts[2]);
				}
			}
		}
		if (pSelect != null && !pSelect.trim().isEmpty())
		{
			final ArrayNode properties = body.putArray("properties");
			for (final String p : pSelect.split(","))
			{
				properties.add(p.trim());
			}
		}
		body.put("limit", Math.min(100, pMax == null ? 100 : Integer.parseInt(pMax.trim())));
		final JsonNode reply = this.bodyOrNull(this.send("POST", pBase + "/search", null, body), "search");
		final JsonNode results = Json.at(reply, "results");
		if (results == null || !results.isArray() || results.size() == 0)
		{
			return null;
		}
		final ArrayNode flat = Json.mapper().createArrayNode();
		for (final JsonNode item : results)
		{
			flat.add(HubSpotConnector.flatten(item));
		}
		return AbstractCrudRestConnector.list(flat, Json.at(reply, "total"));
	}

	/** {@code {id, properties:{...}}} to {@code {id, ...properties}}. */
	static JsonNode flatten(final JsonNode pObject)
	{
		if (pObject == null || !pObject.isObject())
		{
			return pObject;
		}
		final ObjectNode out = Json.object();
		out.set("id", pObject.get("id"));
		final JsonNode properties = pObject.get("properties");
		if (properties != null && properties.isObject())
		{
			out.setAll((ObjectNode) properties);
		}
		for (final String extra : new String[] { "createdAt", "updatedAt", "archived" })
		{
			if (pObject.get(extra) != null)
			{
				out.set(extra, pObject.get(extra));
			}
		}
		return out;
	}

	/** {@code {...fields}} to {@code {properties:{...}}}; nested values as JSON text. */
	static ObjectNode wrap(final JsonNode pDocument)
	{
		final ObjectNode body = Json.object();
		final ObjectNode properties = body.putObject("properties");
		if (pDocument != null && pDocument.isObject())
		{
			final java.util.Iterator<Map.Entry<String, JsonNode>> fields = pDocument.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				if ("id".equals(field.getKey()))
				{
					continue;
				}
				properties.put(field.getKey(), field.getValue().isValueNode() ? field.getValue().asText() : Json.write(field.getValue()));
			}
		}
		return body;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new java.util.ArrayList<FieldInfo>();
		final String object = pInputs == null ? null : pInputs.get("object");
		if (object == null || object.trim().isEmpty())
		{
			return fields;
		}
		this.requireSecret();
		final JsonNode reply = this.bodyOrNull(this.get("/crm/v3/properties/" + AbstractCrudRestConnector.safeName(object), null), "properties");
		final String prefix = "query".equals(pOperation) ? "results[0]." : "";
		if (Json.at(reply, "results") != null)
		{
			for (final JsonNode property : Json.at(reply, "results"))
			{
				fields.add(FieldInfo.of(prefix + property.get("name").asText(), property.get("label") == null ? property.get("name").asText() : property.get("label").asText(),
					property.get("type") == null ? "" : property.get("type").asText()));
			}
		}
		return fields;
	}

	static HttpJsonClient.Response unused()
	{
		return null;
	}
}
