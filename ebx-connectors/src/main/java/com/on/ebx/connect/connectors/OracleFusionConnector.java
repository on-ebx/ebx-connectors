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
 * Oracle Fusion Cloud Applications REST ({@code https://host.oraclecloud.com}), authentication
 * BASIC (integration user) or BEARER (OAuth / JWT). Property {@code apiVersion}
 * (11.13.18.05) and {@code family} (fscmRestApi for ERP/SCM, crmRestApi for CX, hcmRestApi for HCM).
 * {@code object} = resource (suppliers, receivablesCustomerAccounts, accounts...), {@code id} =
 * resource key, {@code filter} = {@code q} expression ({@code OrganizationName like 'ACME%'}),
 * {@code upsert} sends {@code Upsert-Mode: true} on the collection.
 */
public final class OracleFusionConnector extends AbstractCrudRestConnector
{
	@Override
	public String getType()
	{
		return "oracle-fusion";
	}

	@Override
	public String getLabel()
	{
		return "Oracle Fusion Cloud (REST)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder b = com.on.ebx.connect.spi.ConnectorDescriptor.builder(getType(), getLabel(), "BUSINESS_APPS")
			.description("Oracle Fusion Cloud Applications REST connector").authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC).authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN)
			.configurationProperty(prop("family", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, "fscmRestApi"))
			.configurationProperty(prop("apiVersion", com.on.ebx.connect.spi.ConfigurationPropertyType.STRING, "11.13.18.05"))
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
		return this.getConfig().getBaseUrl().replaceAll("/+$", "") + "/" + this.getConfig().getProperty("family", "fscmRestApi")
				+ "/resources/" + this.getConfig().getProperty("apiVersion", "11.13.18.05");
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		headers.put("Accept", "application/json");
		headers.put("REST-Framework-Version", "4");
		return headers;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.requireSecret();
		final String resource = AbstractRestConnector.require(pInputs, "object").replaceAll("[^A-Za-z0-9_/]", "");
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if (pInputs.get("select") != null && !pInputs.get("select").trim().isEmpty())
		{
			query.put("fields", pInputs.get("select").trim());
		}
		if (pInputs.get("expand") != null && !pInputs.get("expand").trim().isEmpty())
		{
			query.put("expand", pInputs.get("expand").trim());
		}
		query.put("onlyData", "true");
		if ("get".equals(pOperation))
		{
			return this.bodyOrNull(this.get("/" + resource + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "id")), query), "get");
		}
		if ("query".equals(pOperation))
		{
			if (pInputs.get("filter") != null && !pInputs.get("filter").trim().isEmpty())
			{
				query.put("q", pInputs.get("filter").trim());
			}
			query.put("limit", pInputs.get("max") == null ? this.getConfig().getProperty("max.rows", "200") : pInputs.get("max"));
			query.put("totalResults", "true");
			final JsonNode reply = this.bodyOrNull(this.get("/" + resource, query), "query");
			return AbstractCrudRestConnector.list(Json.at(reply, "items"), Json.at(reply, "totalResults"));
		}
		if ("create".equals(pOperation))
		{
			return this.bodyOrNull(this.send("POST", "/" + resource, null, pPayload == null ? Json.object() : pPayload), "create");
		}
		if ("update".equals(pOperation))
		{
			return this.bodyOrNull(this.send("PATCH", "/" + resource + "/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "id")), null,
				pPayload == null ? Json.object() : pPayload), "update");
		}
		if ("upsert".equals(pOperation))
		{
			final Map<String, String> extra = new LinkedHashMap<String, String>();
			extra.put("Upsert-Mode", "true");
			final HttpJsonClient.Response response = this.send("POST", "/" + resource, null, pPayload == null ? Json.object() : pPayload, extra);
			return this.bodyOrNull(response, "upsert");
		}
		throw new ConnectorException("Oracle Fusion: unknown operation '" + pOperation + "'");
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
		throws ConnectorException
	{
		final List<FieldInfo> fields = new java.util.ArrayList<FieldInfo>();
		final String resource = pInputs == null ? null : pInputs.get("object");
		if (resource == null || resource.trim().isEmpty())
		{
			return fields;
		}
		this.requireSecret();
		final JsonNode describe = this.bodyOrNull(this.get("/" + resource.replaceAll("[^A-Za-z0-9_/]", "") + "/describe", null), "describe");
		final JsonNode attributes = Json.at(describe, "Resources." + resource + ".attributes");
		final String prefix = "query".equals(pOperation) ? "results[0]." : "";
		if (attributes != null)
		{
			for (final JsonNode attribute : attributes)
			{
				fields.add(FieldInfo.of(prefix + attribute.get("name").asText(), attribute.get("title") == null ? attribute.get("name").asText() : attribute.get("title").asText(),
					attribute.get("type") == null ? "" : attribute.get("type").asText()));
			}
		}
		return fields;
	}
}
