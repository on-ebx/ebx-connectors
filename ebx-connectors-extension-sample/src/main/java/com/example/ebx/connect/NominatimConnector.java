package com.example.ebx.connect;

import java.util.Arrays;
import java.util.Iterator;
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
 * Sample third-party connector: OpenStreetMap Nominatim geocoder
 * ({@code https://nominatim.openstreetmap.org}), no key, one request per second, a User-Agent that
 * identifies the caller is mandatory (property {@code user.agent}).
 *
 * <p>This class lives outside the ebx-connectors module: it is compiled against
 * {@code ebx-connectors-spi.jar}, packaged in its own jar with a
 * {@code META-INF/services/com.on.ebx.connect.spi.Connector} file, and dropped into
 * {@code WEB-INF/lib} of the module. The type {@code nominatim} then shows up in the
 * {@code Connector/type} list.
 *
 * <p>Operations: {@code geocode} (q, optional countrycodes) returns the best match flattened
 * (lat, lon, display_name, type, importance, house_number, road, postcode, city, country,
 * country_code); {@code reverse} (lat, lon) the address of a point.
 */
public final class NominatimConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("geocode", "Best match of a free-text address (q, optional countrycodes)", "q"),
		Operation.read("reverse", "Address of a lat / lon point", "lat", "lon"));

	@Override
	public String getType()
	{
		return "nominatim";
	}

	@Override
	public String getLabel()
	{
		return "OpenStreetMap Nominatim (sample extension)";
	}

	@Override
	public List<Operation> getOperations()
	{
		return NominatimConnector.OPERATIONS;
	}

	/** Nominatim refuses anonymous clients: the User-Agent comes from the connector properties. */
	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		headers.put("User-Agent", this.getConfig().getProperty("user.agent", "ebx-connectors-sample/1.0"));
		headers.put("Accept-Language", this.getConfig().getProperty("accept.language", "en"));
		return headers;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("format", "jsonv2");
		query.put("addressdetails", "1");
		final JsonNode reply;
		if ("geocode".equals(pOperation))
		{
			final String q = AbstractRestConnector.require(pInputs, "q").replaceAll("\\s+", " ").trim();
			if (q.length() < 3)
			{
				return null;
			}
			query.put("q", q);
			query.put("limit", "1");
			final String countries = pInputs.get("countrycodes") != null ? pInputs.get("countrycodes")
					: this.getConfig().getProperty("countrycodes", null);
			if (countries != null && !countries.trim().isEmpty())
			{
				query.put("countrycodes", countries.trim().toLowerCase());
			}
			reply = this.bodyOrNull(this.get("/search", query), pOperation);
			return NominatimConnector.flatten(reply == null || !reply.isArray() ? null : reply.get(0));
		}
		if ("reverse".equals(pOperation))
		{
			query.put("lat", AbstractRestConnector.require(pInputs, "lat"));
			query.put("lon", AbstractRestConnector.require(pInputs, "lon"));
			reply = this.bodyOrNull(this.get("/reverse", query), pOperation);
			return NominatimConnector.flatten(reply != null && reply.has("error") ? null : reply);
		}
		throw new ConnectorException("Nominatim: unknown operation '" + pOperation + "'");
	}

	/** One flat object: the mapping designer and the rules prefer simple paths. */
	private static JsonNode flatten(final JsonNode pPlace)
	{
		if (pPlace == null || pPlace.isNull())
		{
			return null;
		}
		final ObjectNode out = Json.object();
		for (final String key : new String[] { "place_id", "osm_type", "osm_id", "lat", "lon", "display_name", "type", "category", "importance", "name" })
		{
			final JsonNode v = pPlace.get(key);
			if (v != null && !v.isNull())
			{
				if ("lat".equals(key) || "lon".equals(key) || "importance".equals(key))
				{
					out.put(key, v.asDouble());
				}
				else
				{
					out.put(key, v.asText());
				}
			}
		}
		final JsonNode address = pPlace.get("address");
		if (address != null && address.isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> fields = address.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> e = fields.next();
				out.put(e.getKey(), e.getValue().asText());
			}
			// a single "city" whatever the settlement type
			for (final String key : new String[] { "city", "town", "village", "municipality" })
			{
				if (address.has(key))
				{
					out.put("city", address.get(key).asText());
					break;
				}
			}
		}
		return out;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		return Arrays.asList(FieldInfo.of("display_name", "Full label", "string"), FieldInfo.of("lat", "Latitude", "number"),
			FieldInfo.of("lon", "Longitude", "number"), FieldInfo.of("type", "Place type", "string"), FieldInfo.of("importance", "Importance 0-1", "number"),
			FieldInfo.of("house_number", "House number", "string"), FieldInfo.of("road", "Street", "string"), FieldInfo.of("postcode", "Postal code", "string"),
			FieldInfo.of("city", "City", "string"), FieldInfo.of("state", "State / region", "string"), FieldInfo.of("country", "Country", "string"),
			FieldInfo.of("country_code", "ISO country code", "string"));
	}
}
