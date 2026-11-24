package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Google Geocoding API (https://maps.googleapis.com/maps/api). The key goes in the {@code apiKey}
 * column with authType NONE: Google wants it as a query parameter, which this connector adds.
 * A result is returned only when Google found an actual street, locality or postal code, never
 * the country centroid it answers with on garbage input.
 */
public final class GoogleGeocodingConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("geocode", "Coordinates and normalised components of a postal address", "address"),
		Operation.read("reverse", "Address of a lat,lng pair", "latlng"));

	@Override
	public String getType()
	{
		return "google-geocoding";
	}

	@Override
	public String getLabel()
	{
		return "Google Geocoding";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "REFERENCE_DATA").description("Google address geocoding and reverse geocoding.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.CUSTOM);
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return GoogleGeocodingConnector.OPERATIONS;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation,
		final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		if (this.getConfig().isPlaceholder(this.getConfig().getApiKey()))
		{
			throw new ConnectorException("Google Geocoding: API key not configured");
		}
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if ("geocode".equals(pOperation))
		{
			query.put("address", AbstractRestConnector.require(pInputs, "address"));
		}
		else if ("reverse".equals(pOperation))
		{
			query.put("latlng", AbstractRestConnector.require(pInputs, "latlng"));
		}
		else
		{
			throw new ConnectorException("Google Geocoding: unknown operation '" + pOperation + "'");
		}
		final String region = pInputs.get("region");
		if (region != null && !region.trim().isEmpty())
		{
			query.put("region", region.trim().toLowerCase());
		}
		query.put("key", this.getConfig().getApiKey());
		final JsonNode body = this.bodyOrNull(this.get("/geocode/json", query), pOperation);
		final JsonNode status = Json.at(body, "status");
		if (status == null || !"OK".equals(status.asText()))
		{
			if (status != null && ("ZERO_RESULTS".equals(status.asText())))
			{
				return null;
			}
			throw new ConnectorException("Google Geocoding: " + (status == null ? "no status" : status.asText())
					+ " " + String.valueOf(Json.at(body, "error_message")));
		}
		if ("geocode".equals(pOperation) && !GoogleGeocodingConnector.isPrecise(Json.at(body, "results[0]")))
		{
			return null;
		}
		return body;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		fields.add(FieldInfo.of("results[0].formatted_address", "Formatted address", "string"));
		fields.add(FieldInfo.of("results[0].geometry.location.lat", "Latitude", "number"));
		fields.add(FieldInfo.of("results[0].geometry.location.lng", "Longitude", "number"));
		fields.add(FieldInfo.of("results[0].geometry.location_type", "Precision (ROOFTOP...)", "string"));
		fields.add(FieldInfo.of("results[0].place_id", "Google place id", "string"));
		fields.add(FieldInfo.of("results[0].address_components", "Address components (array)", "array"));
		return fields;
	}

	private static boolean isPrecise(final JsonNode pResult)
	{
		final JsonNode components = Json.at(pResult, "address_components");
		if (components == null || !components.isArray())
		{
			return false;
		}
		for (final JsonNode component : components)
		{
			final JsonNode types = component.get("types");
			if (types == null)
			{
				continue;
			}
			for (final JsonNode type : types)
			{
				final String t = type.asText();
				if ("route".equals(t) || "locality".equals(t) || "postal_code".equals(t) || "street_address".equals(t))
				{
					return true;
				}
			}
		}
		return false;
	}
}
