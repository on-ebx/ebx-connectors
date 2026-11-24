package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Creditsafe Connect ({@code https://connect.creditsafe.com/v1}). Authentication BASIC = the
 * Creditsafe user and password, exchanged for a bearer token on {@code /authenticate}.
 * {@code search} (name, country, regNo, vatNo, city), {@code report} (connectId: the company
 * report, {@code report} object), {@code find} (search then report of the first hit).
 */
public final class CreditsafeConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("search", "Companies (inputs name or regNo or vatNo, country, city)", new String[0]),
		Operation.read("report", "Company credit report by connectId", "connectId"),
		Operation.read("find", "Search then report of the first result", new String[0]));

	private String token;
	private long tokenTime;

	@Override
	public String getType()
	{
		return "creditsafe";
	}

	@Override
	public String getLabel()
	{
		return "Creditsafe Connect";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "RISK_COMPLIANCE").description("Company search and credit reports through Creditsafe Connect.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC);
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("country",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("country").description("Default country code").build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("language",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("language").description("Response language").build());
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return CreditsafeConnector.OPERATIONS;
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Accept", "application/json");
		if (this.token != null)
		{
			headers.put("Authorization", this.token);
		}
		return headers;
	}

	private synchronized void authenticate() throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		if (cfg.getUsername() == null || cfg.isPlaceholder(cfg.getPassword()))
		{
			throw new ConnectorException("Creditsafe: set the user name and password (authentication BASIC)");
		}
		if (this.token != null && System.currentTimeMillis() - this.tokenTime < 50L * 60L * 1000L)
		{
			return;
		}
		final ObjectNode body = Json.object();
		body.put("username", cfg.getUsername());
		body.put("password", cfg.getPassword());
		this.token = null;
		final HttpJsonClient.Response response = this.send("POST", "/authenticate", null, body);
		final JsonNode token = Json.at(response.getBody(), "token");
		if (!response.isSuccess() || token == null)
		{
			throw new ConnectorException("Creditsafe authentication failed (HTTP " + response.getStatus() + "): "
					+ String.valueOf(Json.at(response.getBody(), "message")), response.getStatus(), null);
		}
		this.token = token.asText();
		this.tokenTime = System.currentTimeMillis();
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.authenticate();
		if ("search".equals(pOperation) || "find".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			final String country = pInputs == null ? null : pInputs.get("country");
			query.put("countries", country == null || country.trim().isEmpty() ? this.getConfig().getProperty("country", "FR") : country.trim().toUpperCase());
			for (final String key : new String[] { "name", "regNo", "vatNo", "city", "postCode", "street", "id" })
			{
				final String value = pInputs == null ? null : pInputs.get(key);
				if (value != null && !value.trim().isEmpty())
				{
					query.put(key, value.trim());
				}
			}
			if (query.size() == 1)
			{
				throw new ConnectorException("Creditsafe: give a name, regNo or vatNo");
			}
			query.put("pageSize", pInputs.get("max") == null ? "20" : pInputs.get("max"));
			final JsonNode reply = this.bodyOrNull(this.get("/companies", query), "search");
			final JsonNode companies = Json.at(reply, "companies");
			if (companies == null || !companies.isArray() || companies.size() == 0)
			{
				return null;
			}
			if ("find".equals(pOperation))
			{
				return this.report(Json.at(companies.get(0), "id").asText());
			}
			final ObjectNode out = Json.object();
			out.set("results", companies);
			out.put("count", companies.size());
			return out;
		}
		if ("report".equals(pOperation))
		{
			return this.report(AbstractRestConnector.require(pInputs, "connectId"));
		}
		throw new ConnectorException("Creditsafe: unknown operation '" + pOperation + "'");
	}

	private JsonNode report(final String pConnectId) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("language", this.getConfig().getProperty("language", "en"));
		final JsonNode reply = this.bodyOrNull(this.get("/companies/" + AbstractRestConnector.segment(pConnectId), query), "report " + pConnectId);
		return Json.at(reply, "report") == null ? reply : Json.at(reply, "report");
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("search".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("results[0].id", "Connect id", "string"), FieldInfo.of("results[0].name", "Name", "string"),
				FieldInfo.of("results[0].regNo", "Registration number", "string"), FieldInfo.of("results[0].vatNo", "VAT number", "string"),
				FieldInfo.of("results[0].status", "Status", "string"), FieldInfo.of("results[0].address.simpleValue", "Address", "string"),
				FieldInfo.of("results[0].address.city", "City", "string"), FieldInfo.of("results[0].address.postCode", "Postal code", "string"));
		}
		return Arrays.asList(FieldInfo.of("companyId", "Connect id", "string"), FieldInfo.of("companySummary.businessName", "Business name", "string"),
			FieldInfo.of("companySummary.companyRegistrationNumber", "Registration number", "string"),
			FieldInfo.of("companySummary.companyStatus.status", "Status", "string"), FieldInfo.of("companySummary.creditRating.commonValue", "Credit rating", "string"),
			FieldInfo.of("companySummary.creditRating.creditLimit.value", "Credit limit", "number"),
			FieldInfo.of("companyIdentification.basicInformation.registeredCompanyName", "Registered name", "string"),
			FieldInfo.of("companyIdentification.basicInformation.legalForm.description", "Legal form", "string"),
			FieldInfo.of("companyIdentification.basicInformation.companyRegistrationDate", "Registration date", "date"),
			FieldInfo.of("contactInformation.mainAddress.street", "Street", "string"), FieldInfo.of("contactInformation.mainAddress.city", "City", "string"),
			FieldInfo.of("contactInformation.mainAddress.postalCode", "Postal code", "string"), FieldInfo.of("contactInformation.mainAddress.country", "Country", "string"),
			FieldInfo.of("contactInformation.websites[0]", "Website", "string"), FieldInfo.of("otherInformation.employeesInformation[0].numberOfEmployees", "Employees", "string"),
			FieldInfo.of("groupStructure.ultimateParent.name", "Ultimate parent", "string"));
	}
}
