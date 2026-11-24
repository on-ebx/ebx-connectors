package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;

/**
 * ServiceNow Table API ({@code https://instance.service-now.com/api/now/table/{table}}),
 * authentication BASIC (or BEARER with an OAuth token). {@code object} = table name
 * (core_company, customer_account, cmdb_ci...), {@code id} = sys_id, {@code filter} = encoded
 * query ({@code name=ACME^country=FR}); {@code upsert} with {@code lookupField} + {@code value}.
 * Display values are returned as {@code field} plus {@code field_display} when property
 * {@code display.values=true}.
 */
public final class ServiceNowConnector extends AbstractCrudRestConnector
{
	@Override
	public String getType()
	{
		return "servicenow";
	}

	@Override
	public String getLabel()
	{
		return "ServiceNow (Table API)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("ServiceNow Table API connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN)
			.configurationProperty(prop("display.values", com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN, Boolean.FALSE))
			.configurationProperty(prop("max.rows", com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER, Integer.valueOf(200)));
		for (final com.on.ebx.connect.spi.Operation o : getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection d = o.getKind() == com.on.ebx.connect.spi.Operation.Kind.WRITE ? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			b.direction(d).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		return b.build();
	}
	private static com.on.ebx.connect.spi.ConfigurationPropertyDefinition prop(final String n, final com.on.ebx.connect.spi.ConfigurationPropertyType t, final Object v) { return com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder(n, t).defaultValue(v).build(); }

	@Override
	protected String baseUrl() throws ConnectorException
	{
		return this.getConfig().getBaseUrl().replaceAll("/+$", "") + "/api/now/table";
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.requireSecret();
		final String table = AbstractCrudRestConnector.safeName(AbstractRestConnector.require(pInputs, "object"));
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("sysparm_display_value", this.getConfig().getProperty("display.values", "false"));
		query.put("sysparm_exclude_reference_link", "true");
		if (pInputs.get("select") != null && !pInputs.get("select").trim().isEmpty())
		{
			query.put("sysparm_fields", pInputs.get("select").trim());
		}
		if ("get".equals(pOperation))
		{
			return Json.at(this.bodyOrNull(this.get("/" + table + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "id")), query), "get"), "result");
		}
		if ("query".equals(pOperation))
		{
			if (pInputs.get("filter") != null && !pInputs.get("filter").trim().isEmpty())
			{
				query.put("sysparm_query", pInputs.get("filter").trim());
			}
			query.put("sysparm_limit", pInputs.get("max") == null ? this.getConfig().getProperty("max.rows", "200") : pInputs.get("max"));
			final HttpJsonClient.Response response = this.get("/" + table, query);
			final JsonNode reply = this.bodyOrNull(response, "query");
			return AbstractCrudRestConnector.list(Json.at(reply, "result"), response.getHeader("X-Total-Count") == null ? null
					: Json.fromJava(new java.math.BigDecimal(response.getHeader("X-Total-Count"))));
		}
		if ("create".equals(pOperation))
		{
			return Json.at(this.bodyOrNull(this.send("POST", "/" + table, query, pPayload == null ? Json.object() : pPayload), "create"), "result");
		}
		if ("update".equals(pOperation))
		{
			return Json.at(this.bodyOrNull(this.send("PATCH", "/" + table + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "id")), query,
				pPayload == null ? Json.object() : pPayload), "update"), "result");
		}
		if ("upsert".equals(pOperation))
		{
			String id = pInputs.get("id");
			if (id == null || id.trim().isEmpty())
			{
				final Map<String, String> lookup = new LinkedHashMap<String, String>(query);
				lookup.put("sysparm_query", AbstractCrudRestConnector.safeName(AbstractRestConnector.require(pInputs, "lookupField")) + "=" + AbstractRestConnector.require(pInputs, "value"));
				lookup.put("sysparm_limit", "1");
				lookup.put("sysparm_fields", "sys_id");
				final JsonNode hit = Json.at(this.bodyOrNull(this.get("/" + table, lookup), "lookup"), "result[0].sys_id");
				id = hit == null ? null : hit.asText();
			}
			if (id == null)
			{
				return Json.at(this.bodyOrNull(this.send("POST", "/" + table, query, pPayload == null ? Json.object() : pPayload), "create"), "result");
			}
			return Json.at(this.bodyOrNull(this.send("PATCH", "/" + table + "/" + AbstractRestConnector.segment(id), query, pPayload == null ? Json.object() : pPayload), "update"), "result");
		}
		throw new ConnectorException("ServiceNow: unknown operation '" + pOperation + "'");
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new java.util.ArrayList<FieldInfo>();
		final String table = pInputs == null ? null : pInputs.get("object");
		if (table == null || table.trim().isEmpty())
		{
			return fields;
		}
		this.requireSecret();
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("sysparm_query", "name=" + AbstractCrudRestConnector.safeName(table));
		query.put("sysparm_fields", "element,column_label,internal_type");
		query.put("sysparm_limit", "500");
		final JsonNode reply = this.bodyOrNull(this.get("/sys_dictionary", query), "dictionary");
		final String prefix = "query".equals(pOperation) ? "results[0]." : "";
		if (Json.at(reply, "result") != null)
		{
			for (final JsonNode column : Json.at(reply, "result"))
			{
				final JsonNode element = column.get("element");
				if (element != null && !element.asText().isEmpty())
				{
					fields.add(FieldInfo.of(prefix + element.asText(), column.get("column_label") == null ? element.asText() : column.get("column_label").asText(),
						Json.at(column, "internal_type.value") == null ? String.valueOf(column.get("internal_type")) : Json.at(column, "internal_type.value").asText()));
				}
			}
		}
		return fields;
	}
}
