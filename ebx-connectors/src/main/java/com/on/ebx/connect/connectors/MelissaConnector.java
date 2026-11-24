package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Melissa Data cloud services. {@code apiKey} = license key ({@code id} parameter).
 * {@code address} = Global Address Verification ({@code https://address.melissadata.net/v3/WEB/GlobalAddress/doGlobalAddress}),
 * {@code email} = Global Email, {@code phone} = Global Phone. Results are the first record with
 * its result codes; an address whose codes carry no AV2x (verified) code is dropped.
 */
public final class MelissaConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("address", "Global address verification (address, locality, postalCode, country)", "address"),
		Operation.read("email", "Global email verification", "email"),
		Operation.read("phone", "Global phone verification (phone, country)", "phone"));

	@Override
	public String getType()
	{
		return "melissa";
	}

	@Override
	public String getLabel()
	{
		return "Melissa Data";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "REFERENCE_DATA").description("Melissa address, email and telephone verification services.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.CUSTOM);
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("address.url",
			com.on.ebx.connect.spi.ConfigurationPropertyType.URL).label("address.url").description("Address service URL override").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("country",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("country").description("Default country code").build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("email.url",
			com.on.ebx.connect.spi.ConfigurationPropertyType.URL).label("email.url").description("Email service URL override").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("phone.url",
			com.on.ebx.connect.spi.ConfigurationPropertyType.URL).label("phone.url").description("Phone service URL override").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("verified.only",
			com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN).label("verified.only").description("Return only verified results").build());
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return MelissaConnector.OPERATIONS;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if (this.getConfig().isPlaceholder(this.getConfig().getApiKey()))
		{
			throw new ConnectorException("Melissa: set the license key in apiKey");
		}
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("id", this.getConfig().getApiKey());
		query.put("format", "json");
		final String url;
		if ("address".equals(pOperation))
		{
			url = this.getConfig().getProperty("address.url", "https://address.melissadata.net/v3/WEB/GlobalAddress/doGlobalAddress");
			query.put("a1", AbstractRestConnector.require(pInputs, "address"));
			MelissaConnector.put(query, "loc", pInputs.get("locality"));
			MelissaConnector.put(query, "postal", pInputs.get("postalCode"));
			MelissaConnector.put(query, "admarea", pInputs.get("administrativeArea"));
			query.put("ctry", pInputs.get("country") == null || pInputs.get("country").trim().isEmpty()
					? this.getConfig().getProperty("country", "FR") : pInputs.get("country").trim());
			query.put("opt", "OutputGeo:ON");
		}
		else if ("email".equals(pOperation))
		{
			url = this.getConfig().getProperty("email.url", "https://globalemail.melissadata.net/v4/WEB/GlobalEmail/doGlobalEmail");
			query.put("email", AbstractRestConnector.require(pInputs, "email"));
		}
		else if ("phone".equals(pOperation))
		{
			url = this.getConfig().getProperty("phone.url", "https://globalphone.melissadata.net/v4/WEB/GlobalPhone/doGlobalPhone");
			query.put("phone", AbstractRestConnector.require(pInputs, "phone"));
			MelissaConnector.put(query, "ctry", pInputs.get("country"));
		}
		else
		{
			throw new ConnectorException("Melissa: unknown operation '" + pOperation + "'");
		}
		final com.on.ebx.connect.core.HttpJsonClient client = new com.on.ebx.connect.core.HttpJsonClient(
			this.getConfig().getConnectTimeoutMs(), this.getConfig().getReadTimeoutMs(), "EBX-Connectors/1.0 (melissa)");
		final com.on.ebx.connect.core.HttpJsonClient.Response response;
		try
		{
			response = client.send("GET", com.on.ebx.connect.core.HttpJsonClient.buildUrl(url, "", query), this.headers(), null);
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("Melissa: " + ex.getMessage(), ex);
		}
		final JsonNode reply = this.bodyOrNull(response, pOperation);
		final JsonNode record = Json.at(reply, "Records[0]");
		if (record == null)
		{
			return null;
		}
		final String codes = Json.at(record, "Results") == null ? "" : Json.at(record, "Results").asText();
		final ObjectNode out = record.deepCopy();
		out.put("verified", "address".equals(pOperation) ? codes.contains("AV2") : "email".equals(pOperation) ? codes.contains("ES01") : codes.contains("PS01"));
		if ("address".equals(pOperation) && !codes.contains("AV2") && "true".equalsIgnoreCase(this.getConfig().getProperty("verified.only", "true")))
		{
			return null;
		}
		return out;
	}

	private static void put(final Map<String, String> pQuery, final String pKey, final String pValue)
	{
		if (pValue != null && !pValue.trim().isEmpty())
		{
			pQuery.put(pKey, pValue.trim());
		}
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("email".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("EmailAddress", "Email", "string"), FieldInfo.of("DomainName", "Domain", "string"),
				FieldInfo.of("verified", "Verified?", "boolean"), FieldInfo.of("Results", "Result codes", "string"));
		}
		if ("phone".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("PhoneNumber", "Phone", "string"), FieldInfo.of("PhoneInternationalPrefix", "International prefix", "string"),
				FieldInfo.of("Carrier", "Carrier", "string"), FieldInfo.of("verified", "Verified?", "boolean"), FieldInfo.of("Results", "Result codes", "string"));
		}
		return Arrays.asList(FieldInfo.of("verified", "Verified (AV2x)?", "boolean"), FieldInfo.of("Results", "Result codes", "string"),
			FieldInfo.of("FormattedAddress", "Formatted address", "string"), FieldInfo.of("AddressLine1", "Address line 1", "string"),
			FieldInfo.of("PremisesNumber", "Premises number", "string"), FieldInfo.of("Thoroughfare", "Thoroughfare", "string"),
			FieldInfo.of("Locality", "Locality", "string"), FieldInfo.of("AdministrativeArea", "Administrative area", "string"),
			FieldInfo.of("PostalCode", "Postal code", "string"), FieldInfo.of("CountryISO3166_1_Alpha2", "Country", "string"),
			FieldInfo.of("Latitude", "Latitude", "number"), FieldInfo.of("Longitude", "Longitude", "number"));
	}
}
