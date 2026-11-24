package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Bureau van Dijk Orbis ({@code https://api.bvdinfo.com/v1/orbis}), Moody's. {@code apiKey} = API
 * token sent as the {@code ApiToken} header (authentication API_KEY_HEADER is set by the connector).
 * {@code get} by BvD id, {@code search} by name and country, {@code match} (name, city, country,
 * national id) through the match endpoint. Property {@code select} lists the Orbis fields returned
 * (defaults to the identity, address, status, size and ownership fields).
 */
public final class OrbisConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("get", "Company data by BvD id", "bvdId"),
		Operation.search("search", "Companies by name (input name, optional country)", "name"),
		Operation.read("match", "Best match by name / national id / city / country", new String[0]));

	private static final String DEFAULT_SELECT = "NAME,BVD_ID_NUMBER,NATIONAL_ID,NATIONAL_ID_LABEL,LEI,STATUS,INCORPORATION_DATE,"
			+ "NACE2_MAIN_CODE,NACE2_MAIN_DESCRIPTION,ADDRESS_LINE1,POSTCODE,CITY,COUNTRY_ISO_CODE,WEBSITE,PHONE_NUMBER,"
			+ "EMPL,OPRE,OPRE_CURRENCY,GUO_NAME,GUO_BVD_ID_NUMBER,ISH_NAME,ISH_BVD_ID_NUMBER,LATITUDE,LONGITUDE";

	@Override
	public String getType()
	{
		return "orbis";
	}

	@Override
	public String getLabel()
	{
		return "Bureau van Dijk Orbis";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "CORPORATE_REGISTRIES").description("Company lookup and matching through Moody's Bureau van Dijk Orbis.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER);
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("select",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("select").description("Requested response fields").advanced(true).build());
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return OrbisConnector.OPERATIONS;
	}

	@Override
	public void configure(final com.on.ebx.connect.spi.ConnectorConfig pConfig)
	{
		pConfig.setAuthType(com.on.ebx.connect.spi.ConnectorConfig.AuthType.API_KEY_HEADER);
		pConfig.setApiKeyHeader("ApiToken");
		super.configure(pConfig);
	}

	private ArrayNode select()
	{
		final ArrayNode select = Json.mapper().createArrayNode();
		for (final String field : this.getConfig().getProperty("select", OrbisConnector.DEFAULT_SELECT).split(","))
		{
			if (!field.trim().isEmpty())
			{
				select.add(field.trim());
			}
		}
		return select;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.requireSecret();
		if ("get".equals(pOperation))
		{
			final ObjectNode body = Json.object();
			body.putArray("WHERE").addObject().put("BvDID", AbstractRestConnector.require(pInputs, "bvdId"));
			body.set("SELECT", this.select());
			final JsonNode reply = this.bodyOrNull(this.send("POST", "/Companies/data", null, body), "get");
			return Json.at(reply, "Data[0]");
		}
		if ("search".equals(pOperation))
		{
			final ObjectNode body = Json.object();
			final ObjectNode where = body.putArray("WHERE").addObject();
			where.put("Name", AbstractRestConnector.require(pInputs, "name"));
			final String country = pInputs.get("country");
			if (country != null && !country.trim().isEmpty())
			{
				where.put("Country", country.trim().toUpperCase());
			}
			body.set("SELECT", this.select());
			body.putObject("PAGE").put("Size", pInputs.get("max") == null ? 20 : Integer.parseInt(pInputs.get("max")));
			final JsonNode reply = this.bodyOrNull(this.send("POST", "/Companies/data", null, body), "search");
			final JsonNode data = Json.at(reply, "Data");
			if (data == null || !data.isArray() || data.size() == 0)
			{
				return null;
			}
			final ObjectNode out = Json.object();
			out.set("results", data);
			out.put("count", data.size());
			return out;
		}
		if ("match".equals(pOperation))
		{
			final ObjectNode body = Json.object();
			final ObjectNode criteria = body.putObject("MATCH").putObject("Criteria");
			for (final String[] map : new String[][] { { "name", "Name" }, { "city", "City" }, { "country", "Country" },
				{ "nationalId", "NationalId" }, { "postalCode", "PostCode" }, { "address", "Address" } })
			{
				final String value = pInputs == null ? null : pInputs.get(map[0]);
				if (value != null && !value.trim().isEmpty())
				{
					criteria.put(map[1], "country".equals(map[0]) ? value.trim().toUpperCase() : value.trim());
				}
			}
			if (criteria.size() == 0)
			{
				throw new ConnectorException("Orbis match: give at least a name or a national id");
			}
			body.set("SELECT", this.select());
			final JsonNode reply = this.bodyOrNull(this.send("POST", "/Companies/match", null, body), "match");
			final JsonNode data = Json.at(reply, "Data") == null ? Json.at(reply, "MatchResults") : Json.at(reply, "Data");
			if (data == null || !data.isArray() || data.size() == 0)
			{
				return null;
			}
			return data.get(0);
		}
		throw new ConnectorException("Orbis: unknown operation '" + pOperation + "'");
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		final String p = "search".equals(pOperation) ? "results[0]." : "";
		for (final String field : this.getConfig().getProperty("select", OrbisConnector.DEFAULT_SELECT).split(","))
		{
			if (!field.trim().isEmpty())
			{
				fields.add(FieldInfo.of(p + field.trim(), field.trim(), "string"));
			}
		}
		return fields;
	}
}
