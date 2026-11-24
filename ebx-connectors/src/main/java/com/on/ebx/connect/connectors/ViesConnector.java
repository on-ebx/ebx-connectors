package com.on.ebx.connect.connectors;

import java.util.Arrays;
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
 * VIES, the European Commission VAT number validation ({@code https://ec.europa.eu/taxation_customs/vies/rest-api}),
 * no key. {@code check} takes {@code vat} (FR12345678901, or {@code countryCode} + {@code vatNumber})
 * and returns {@code {valid, countryCode, vatNumber, name, address, requestDate}}; null when the
 * number is unknown, so an IF_EMPTY rule never overwrites with nothing.
 */
public final class ViesConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("check", "Validate an EU VAT number (input vat, or countryCode + vatNumber)", new String[0]));

	@Override
	public String getType()
	{
		return "vies";
	}

	@Override
	public String getLabel()
	{
		return "VIES (EU VAT validation)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "RISK_COMPLIANCE").description("European Union VAT-number validation through VIES.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE);
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return ViesConnector.OPERATIONS;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if (!"check".equals(pOperation))
		{
			throw new ConnectorException("VIES: unknown operation '" + pOperation + "'");
		}
		String country = pInputs == null ? null : pInputs.get("countryCode");
		String number = pInputs == null ? null : pInputs.get("vatNumber");
		final String vat = pInputs == null ? null : pInputs.get("vat");
		if ((country == null || number == null) && vat != null && vat.trim().length() > 2)
		{
			final String compact = vat.replaceAll("[\\s.-]", "").toUpperCase();
			country = compact.substring(0, 2);
			number = compact.substring(2);
		}
		if (country == null || number == null || country.trim().isEmpty() || number.trim().isEmpty())
		{
			throw new ConnectorException("VIES: give a VAT number (input vat) or countryCode + vatNumber");
		}
		final ObjectNode body = Json.object();
		body.put("countryCode", country.trim().toUpperCase());
		body.put("vatNumber", number.trim().toUpperCase());
		final JsonNode reply = this.bodyOrNull(this.send("POST", "/check-vat-number", null, body), "check-vat-number");
		final JsonNode wrapped = Json.at(reply, "errorWrappers[0].error");
		if (wrapped != null)
		{
			// MS_MAX_CONCURRENT_REQ, MS_UNAVAILABLE, SERVICE_UNAVAILABLE, INVALID_INPUT...: a transient or input error, not "no data"
			throw new ConnectorException("VIES: " + wrapped.asText() + (Json.at(reply, "errorWrappers[0].message") == null ? "" : " - " + Json.at(reply, "errorWrappers[0].message").asText()));
		}
		if (reply == null || Json.at(reply, "valid") == null)
		{
			return null;
		}
		final ObjectNode out = Json.object();
		out.put("valid", Json.at(reply, "valid").asBoolean());
		out.put("countryCode", country.trim().toUpperCase());
		out.put("vatNumber", number.trim().toUpperCase());
		out.put("vat", country.trim().toUpperCase() + number.trim().toUpperCase());
		out.put("name", ViesConnector.clean(Json.at(reply, "name")));
		out.put("address", ViesConnector.clean(Json.at(reply, "address")));
		out.put("requestDate", Json.at(reply, "requestDate") == null ? "" : Json.at(reply, "requestDate").asText());
		out.put("status", Json.at(reply, "valid").asBoolean() ? "VALID" : "INVALID");
		final JsonNode error = Json.at(reply, "userError");
		if (error != null && !"VALID".equals(error.asText()) && !"INVALID".equals(error.asText()))
		{
			out.put("status", error.asText());
		}
		return out;
	}

	private static String clean(final JsonNode pValue)
	{
		if (pValue == null || pValue.isNull())
		{
			return "";
		}
		final String text = pValue.asText().trim();
		return "---".equals(text) ? "" : text.replaceAll("\\s+", " ");
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		return Arrays.asList(FieldInfo.of("valid", "Valid?", "boolean"), FieldInfo.of("status", "Status (VALID / INVALID / service error)", "string"),
			FieldInfo.of("vat", "VAT number (country + number)", "string"), FieldInfo.of("name", "Registered name", "string"),
			FieldInfo.of("address", "Registered address", "string"), FieldInfo.of("requestDate", "Request date", "string"));
	}
}
