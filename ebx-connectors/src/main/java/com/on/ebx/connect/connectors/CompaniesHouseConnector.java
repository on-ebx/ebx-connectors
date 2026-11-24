package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * UK Companies House ({@code https://api.company-information.service.gov.uk}). The API key goes in
 * {@code apiKey}; it is sent as the user of HTTP Basic (authentication set to BASIC by the
 * connector). Operations: {@code get} (company number), {@code search} (q), {@code officers},
 * {@code pscs} (persons with significant control), {@code filings}.
 */
public final class CompaniesHouseConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("get", "Company profile by company number", "number"),
		Operation.search("search", "Companies matching a name (input q)", "q"),
		Operation.search("officers", "Officers of a company", "number"),
		Operation.search("pscs", "Persons with significant control of a company", "number"),
		Operation.search("filings", "Filing history of a company", "number"));

	@Override
	public String getType()
	{
		return "companies-house";
	}

	@Override
	public String getLabel()
	{
		return "Companies House (UK)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "CORPORATE_REGISTRIES").description("UK company profiles, officers, controllers and filing history.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC);
		descriptor.capability(com.on.ebx.connect.spi.ConnectorCapability.BULK_READ);
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return CompaniesHouseConnector.OPERATIONS;
	}

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		// the API key is the Basic user name, with an empty password
		pConfig.setAuthType(ConnectorConfig.AuthType.BASIC);
		pConfig.setUsername(pConfig.getApiKey());
		pConfig.setPassword("");
		super.configure(pConfig);
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if (this.getConfig().isPlaceholder(this.getConfig().getApiKey()))
		{
			throw new ConnectorException("Companies House: set the API key in apiKey");
		}
		if ("get".equals(pOperation))
		{
			return this.bodyOrNull(this.get("/company/" + CompaniesHouseConnector.number(pInputs), null), "company");
		}
		if ("search".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("q", AbstractRestConnector.require(pInputs, "q"));
			query.put("items_per_page", pInputs.get("max") == null ? "20" : pInputs.get("max"));
			return CompaniesHouseConnector.items(this.bodyOrNull(this.get("/search/companies", query), "search"));
		}
		final String suffix = "officers".equals(pOperation) ? "/officers" : "pscs".equals(pOperation)
				? "/persons-with-significant-control" : "filings".equals(pOperation) ? "/filing-history" : null;
		if (suffix == null)
		{
			throw new ConnectorException("Companies House: unknown operation '" + pOperation + "'");
		}
		return CompaniesHouseConnector.items(this.bodyOrNull(this.get("/company/" + CompaniesHouseConnector.number(pInputs) + suffix, null), pOperation));
	}

	private static String number(final Map<String, String> pInputs) throws ConnectorException
	{
		final String number = AbstractRestConnector.require(pInputs, "number").replaceAll("\\s", "").toUpperCase();
		return number.length() < 8 ? String.format("%8s", number).replace(' ', '0') : AbstractRestConnector.segment(number);
	}

	private static JsonNode items(final JsonNode pReply)
	{
		final JsonNode items = Json.at(pReply, "items");
		if (items == null || !items.isArray() || items.size() == 0)
		{
			return null;
		}
		final ObjectNode out = Json.object();
		out.set("results", items);
		out.put("count", items.size());
		final JsonNode total = Json.at(pReply, "total_results");
		if (total != null)
		{
			out.put("totalCount", total.asLong());
		}
		return out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final String p = "get".equals(pOperation) ? "" : "results[0].";
		return Arrays.asList(FieldInfo.of(p + "company_name", "Company name", "string"), FieldInfo.of(p + "company_number", "Company number", "string"),
			FieldInfo.of(p + "company_status", "Status", "string"), FieldInfo.of(p + "type", "Company type", "string"),
			FieldInfo.of(p + "date_of_creation", "Date of creation", "date"), FieldInfo.of(p + "date_of_cessation", "Date of cessation", "date"),
			FieldInfo.of(p + "jurisdiction", "Jurisdiction", "string"), FieldInfo.of(p + "sic_codes", "SIC codes", "array"),
			FieldInfo.of(p + "registered_office_address.address_line_1", "Address line 1", "string"),
			FieldInfo.of(p + "registered_office_address.locality", "Locality", "string"),
			FieldInfo.of(p + "registered_office_address.postal_code", "Postal code", "string"),
			FieldInfo.of(p + "registered_office_address.country", "Country", "string"), FieldInfo.of(p + "accounts.next_due", "Accounts next due", "date"));
	}
}
