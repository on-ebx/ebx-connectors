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
 * Loqate ({@code https://api.addressy.com}), international address verification. {@code apiKey}
 * = Loqate key (sent as the {@code Key} parameter). {@code verify} cleanses one address
 * (inputs address, locality, postalCode, country) through the international batch cleanse
 * and returns the first match with its AVC code; {@code find} / {@code retrieve} wrap the
 * interactive capture endpoints.
 */
public final class LoqateConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("verify", "Cleanse and verify one address (address, locality, postalCode, country)", "address"),
		Operation.search("find", "Interactive capture: suggestions for a text (text, country)", "text"),
		Operation.read("retrieve", "Interactive capture: full address of a suggestion id", "id"));

	@Override
	public String getType()
	{
		return "loqate";
	}

	@Override
	public String getLabel()
	{
		return "Loqate address verification";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "REFERENCE_DATA").description("International address capture, verification and retrieval through Loqate.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.CUSTOM);
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("geocode",
			com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN).label("geocode").description("Request geocoding details").build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("verification.min",
			com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER).label("verification.min").description("Minimum verification level").advanced(true).build());
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return LoqateConnector.OPERATIONS;
	}

	private void requireKey() throws ConnectorException
	{
		if (this.getConfig().isPlaceholder(this.getConfig().getApiKey()))
		{
			throw new ConnectorException("Loqate: set the API key in apiKey");
		}
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.requireKey();
		if ("verify".equals(pOperation))
		{
			final ObjectNode body = Json.object();
			body.put("Key", this.getConfig().getApiKey());
			body.put("Geocode", "true".equalsIgnoreCase(this.getConfig().getProperty("geocode", "true")));
			final ObjectNode address = body.putArray("Addresses").addObject();
			address.put("Address", AbstractRestConnector.require(pInputs, "address"));
			for (final String[] map : new String[][] { { "locality", "Locality" }, { "postalCode", "PostalCode" }, { "country", "Country" },
				{ "administrativeArea", "AdministrativeArea" }, { "organization", "Organization" } })
			{
				final String value = pInputs.get(map[0]);
				if (value != null && !value.trim().isEmpty())
				{
					address.put(map[1], value.trim());
				}
			}
			final JsonNode reply = this.bodyOrNull(this.send("POST", "/Cleansing/International/Batch/v1.00/json4.ws", null, body), "cleanse");
			final JsonNode matches = Json.at(reply, "[0].Matches");
			final JsonNode first = matches == null || !matches.isArray() || matches.size() == 0 ? null : matches.get(0);
			if (first == null)
			{
				return null;
			}
			final String avc = Json.at(first, "AVC") == null ? "" : Json.at(first, "AVC").asText();
			final ObjectNode out = first.deepCopy();
			// AVC starts with V (verified), P (partial), A (ambiguous), U (unverified), R (reverted), C (conflict)
			out.put("verificationStatus", avc.isEmpty() ? "" : avc.substring(0, 1));
			final String minimum = this.getConfig().getProperty("verification.min", "V");
			if (!avc.isEmpty() && "V".equals(minimum) && !avc.startsWith("V"))
			{
				return null;
			}
			return out;
		}
		if ("find".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("Key", this.getConfig().getApiKey());
			query.put("Text", AbstractRestConnector.require(pInputs, "text"));
			final String country = pInputs.get("country");
			if (country != null && !country.trim().isEmpty())
			{
				query.put("Countries", country.trim().toUpperCase());
			}
			query.put("Limit", pInputs.get("max") == null ? "10" : pInputs.get("max"));
			final JsonNode reply = this.bodyOrNull(this.get("/Capture/Interactive/Find/v1.10/json3.ws", query), "find");
			return LoqateConnector.items(reply);
		}
		if ("retrieve".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("Key", this.getConfig().getApiKey());
			query.put("Id", AbstractRestConnector.require(pInputs, "id"));
			final JsonNode reply = this.bodyOrNull(this.get("/Capture/Interactive/Retrieve/v1.20/json3.ws", query), "retrieve");
			return Json.at(reply, "Items[0]");
		}
		throw new ConnectorException("Loqate: unknown operation '" + pOperation + "'");
	}

	private static JsonNode items(final JsonNode pReply)
	{
		final JsonNode items = Json.at(pReply, "Items");
		if (items == null || !items.isArray() || items.size() == 0 || items.get(0).get("Error") != null)
		{
			return null;
		}
		final ObjectNode out = Json.object();
		final ArrayNode results = out.putArray("results");
		for (final JsonNode item : items)
		{
			results.add(item);
		}
		out.put("count", results.size());
		return out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("find".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("results[0].Id", "Suggestion id", "string"), FieldInfo.of("results[0].Text", "Text", "string"),
				FieldInfo.of("results[0].Description", "Description", "string"), FieldInfo.of("results[0].Type", "Type", "string"));
		}
		return Arrays.asList(FieldInfo.of("AVC", "Address verification code", "string"), FieldInfo.of("verificationStatus", "Verification status (V/P/A/U/R/C)", "string"),
			FieldInfo.of("Address", "Formatted address", "string"), FieldInfo.of("Address1", "Address line 1", "string"),
			FieldInfo.of("Organization", "Organization", "string"), FieldInfo.of("PremiseNumber", "Premise number", "string"),
			FieldInfo.of("Thoroughfare", "Thoroughfare", "string"), FieldInfo.of("Locality", "Locality", "string"),
			FieldInfo.of("AdministrativeArea", "Administrative area", "string"), FieldInfo.of("PostalCode", "Postal code", "string"),
			FieldInfo.of("ISO3166-2", "Country (ISO 3166-2)", "string"), FieldInfo.of("Latitude", "Latitude", "number"),
			FieldInfo.of("Longitude", "Longitude", "number"), FieldInfo.of("GeoAccuracy", "Geocode accuracy", "string"));
	}
}
