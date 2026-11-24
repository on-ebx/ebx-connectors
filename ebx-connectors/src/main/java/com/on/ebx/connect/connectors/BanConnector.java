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
 * Base Adresse Nationale ({@code https://api-adresse.data.gouv.fr}), the free French address
 * geocoder. {@code geocode} (address, optional postcode) returns the best feature flattened:
 * label, housenumber, street, postcode, city, citycode, score, lat, lon; {@code reverse}
 * (lat, lon) the address of a point. Only results above {@code score.min} (default 0.5) are kept.
 */
public final class BanConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("geocode", "Best match of a French postal address", "address"),
		Operation.read("reverse", "Address of a lat / lon point", "lat", "lon"));

	@Override
	public String getType()
	{
		return "ban";
	}

	@Override
	public String getLabel()
	{
		return "Base Adresse Nationale (France)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "REFERENCE_DATA").description("French national address geocoding and reverse geocoding.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE);
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("score.min",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("score.min").description("Minimum accepted match score").advanced(true).build());
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return BanConnector.OPERATIONS;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("limit", "1");
		final String path;
		if ("geocode".equals(pOperation))
		{
			final String address = AbstractRestConnector.require(pInputs, "address").replaceAll("\\s+", " ");
			if (address.length() < 3)
			{
				return null;
			}
			query.put("q", address);
			final String postcode = pInputs.get("postcode");
			if (postcode != null && postcode.trim().matches("[0-9]{5}"))
			{
				query.put("postcode", postcode.trim());
			}
			path = "/search/";
		}
		else if ("reverse".equals(pOperation))
		{
			query.put("lat", AbstractRestConnector.require(pInputs, "lat"));
			query.put("lon", AbstractRestConnector.require(pInputs, "lon"));
			path = "/reverse/";
		}
		else
		{
			throw new ConnectorException("BAN: unknown operation '" + pOperation + "'");
		}
		final JsonNode reply = this.bodyOrNull(this.get(path, query), pOperation);
		final JsonNode feature = Json.at(reply, "features[0]");
		if (feature == null)
		{
			return null;
		}
		final JsonNode properties = feature.get("properties");
		final double score = Json.at(properties, "score") == null ? 0 : Json.at(properties, "score").asDouble();
		if ("geocode".equals(pOperation) && score < Double.parseDouble(this.getConfig().getProperty("score.min", "0.5")))
		{
			return null;
		}
		final ObjectNode out = properties == null ? Json.object() : properties.deepCopy();
		final JsonNode coordinates = Json.at(feature, "geometry.coordinates");
		if (coordinates != null && coordinates.size() == 2)
		{
			out.put("lon", coordinates.get(0).asDouble());
			out.put("lat", coordinates.get(1).asDouble());
		}
		return out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		return Arrays.asList(FieldInfo.of("label", "Normalised label", "string"), FieldInfo.of("housenumber", "House number", "string"),
			FieldInfo.of("street", "Street", "string"), FieldInfo.of("postcode", "Postal code", "string"), FieldInfo.of("city", "City", "string"),
			FieldInfo.of("citycode", "INSEE city code", "string"), FieldInfo.of("context", "Department, region", "string"),
			FieldInfo.of("type", "Precision (housenumber, street, municipality)", "string"), FieldInfo.of("score", "Score 0-1", "number"),
			FieldInfo.of("lat", "Latitude", "number"), FieldInfo.of("lon", "Longitude", "number"));
	}
}
