package com.on.ebx.connect.connectors;

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
 * OpenCorporates ({@code https://api.opencorporates.com/v0.4}), company registers of 140
 * jurisdictions. {@code apiKey} = API token (optional, rate-limited without). {@code get} takes
 * {@code jurisdiction} (fr, gb, de, us_de...) and {@code number}; {@code search} takes {@code q}
 * and optional {@code jurisdiction}. Documents are the {@code company} object of the API.
 */
public final class OpenCorporatesConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("get", "Company by jurisdiction code and company number", "jurisdiction", "number"),
		Operation.search("search", "Companies matching a name (input q, optional jurisdiction)", "q"));

	@Override
	public String getType()
	{
		return "opencorporates";
	}

	@Override
	public String getLabel()
	{
		return "OpenCorporates";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "CORPORATE_REGISTRIES").description("Global company search and registered-company lookup through OpenCorporates.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE);
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.CUSTOM);
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return OpenCorporatesConnector.OPERATIONS;
	}

	private Map<String, String> tokenQuery()
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if (!this.getConfig().isPlaceholder(this.getConfig().getApiKey()))
		{
			query.put("api_token", this.getConfig().getApiKey());
		}
		return query;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if ("get".equals(pOperation))
		{
			final String jurisdiction = AbstractRestConnector.require(pInputs, "jurisdiction").toLowerCase();
			final String number = AbstractRestConnector.require(pInputs, "number").replaceAll("\\s", "");
			final JsonNode reply = this.bodyOrNull(this.get("/companies/" + AbstractRestConnector.segment(jurisdiction) + "/"
					+ AbstractRestConnector.segment(number), this.tokenQuery()), "company " + jurisdiction + "/" + number);
			return Json.at(reply, "results.company");
		}
		if ("search".equals(pOperation))
		{
			final Map<String, String> query = this.tokenQuery();
			query.put("q", AbstractRestConnector.require(pInputs, "q"));
			final String jurisdiction = pInputs.get("jurisdiction");
			if (jurisdiction != null && !jurisdiction.trim().isEmpty())
			{
				query.put("jurisdiction_code", jurisdiction.trim().toLowerCase());
			}
			query.put("per_page", pInputs.get("max") == null ? "30" : pInputs.get("max"));
			final JsonNode reply = this.bodyOrNull(this.get("/companies/search", query), "search");
			final JsonNode companies = Json.at(reply, "results.companies");
			if (companies == null || !companies.isArray() || companies.size() == 0)
			{
				return null;
			}
			final ObjectNode out = Json.object();
			final ArrayNode results = out.putArray("results");
			for (final JsonNode item : companies)
			{
				results.add(item.get("company") == null ? item : item.get("company"));
			}
			out.put("count", results.size());
			return out;
		}
		throw new ConnectorException("OpenCorporates: unknown operation '" + pOperation + "'");
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final String p = "search".equals(pOperation) ? "results[0]." : "";
		return Arrays.asList(FieldInfo.of(p + "name", "Name", "string"), FieldInfo.of(p + "company_number", "Company number", "string"),
			FieldInfo.of(p + "jurisdiction_code", "Jurisdiction", "string"), FieldInfo.of(p + "incorporation_date", "Incorporation date", "date"),
			FieldInfo.of(p + "dissolution_date", "Dissolution date", "date"), FieldInfo.of(p + "company_type", "Company type", "string"),
			FieldInfo.of(p + "current_status", "Status", "string"), FieldInfo.of(p + "registered_address_in_full", "Registered address", "string"),
			FieldInfo.of(p + "registered_address.street_address", "Street", "string"), FieldInfo.of(p + "registered_address.locality", "City", "string"),
			FieldInfo.of(p + "registered_address.postal_code", "Postal code", "string"), FieldInfo.of(p + "registered_address.country", "Country", "string"),
			FieldInfo.of(p + "opencorporates_url", "OpenCorporates URL", "string"), FieldInfo.of(p + "previous_names", "Previous names", "array"),
			FieldInfo.of(p + "officers", "Officers (get)", "array"), FieldInfo.of(p + "industry_codes", "Industry codes", "array"));
	}
}
