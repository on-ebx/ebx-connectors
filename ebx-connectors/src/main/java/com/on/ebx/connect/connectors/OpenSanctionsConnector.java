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
 * Sanctions and PEP screening through OpenSanctions ({@code https://api.opensanctions.org}), which
 * aggregates OFAC SDN, UN Security Council, EU financial sanctions, UK HMT and 200+ other lists.
 * {@code apiKey} = API key (header {@code Authorization: ApiKey ...}); property {@code dataset}
 * ({@code default}, or {@code sanctions}, {@code us_ofac_sdn}, {@code eu_fsf}, {@code un_sc_sanctions}...),
 * {@code threshold} (default 0.7).
 * <ul>
 * <li>{@code screen} (READ): match a company or person (name, registrationNumber, country, schema)
 * and return {@code {hit, score, matches:[...], best:{...}}}; a document is always returned so that
 * a mapping can record "screened, no hit".</li>
 * <li>{@code search} (SEARCH): full text search, results as {@code results[]}.</li>
 * <li>{@code entity} (READ): one entity by id.</li>
 * </ul>
 */
public final class OpenSanctionsConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("screen", "Screen a name against sanctions and PEP lists (name, registrationNumber, country, schema)", "name"),
		Operation.search("search", "Full text search (q, optional schema, countries)", "q"),
		Operation.read("entity", "Entity by OpenSanctions id", "id"));

	@Override
	public String getType()
	{
		return "opensanctions";
	}

	@Override
	public String getLabel()
	{
		return "OpenSanctions (OFAC, UN, EU sanctions and PEPs)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "RISK_COMPLIANCE").description("Sanctions, politically exposed person and entity screening through OpenSanctions.");
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
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("dataset",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("dataset").description("Dataset or collection to search").build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("threshold",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("threshold").description("Minimum screening score").advanced(true).build());
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return OpenSanctionsConnector.OPERATIONS;
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Accept", "application/json");
		if (!this.getConfig().isPlaceholder(this.getConfig().getApiKey()))
		{
			headers.put("Authorization", "ApiKey " + this.getConfig().getApiKey());
		}
		return headers;
	}

	private String dataset()
	{
		return this.getConfig().getProperty("dataset", "default");
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		if ("screen".equals(pOperation))
		{
			final ObjectNode body = Json.object();
			final ObjectNode query = body.putObject("queries").putObject("q1");
			final String schema = pInputs.get("schema");
			query.put("schema", schema == null || schema.trim().isEmpty() ? "Company" : schema.trim());
			final ObjectNode properties = query.putObject("properties");
			properties.putArray("name").add(AbstractRestConnector.require(pInputs, "name"));
			OpenSanctionsConnector.add(properties, "registrationNumber", pInputs.get("registrationNumber"));
			OpenSanctionsConnector.add(properties, "country", pInputs.get("country") == null ? null : pInputs.get("country").toLowerCase());
			OpenSanctionsConnector.add(properties, "leiCode", pInputs.get("lei"));
			OpenSanctionsConnector.add(properties, "birthDate", pInputs.get("birthDate"));
			final Map<String, String> params = new LinkedHashMap<String, String>();
			params.put("threshold", this.getConfig().getProperty("threshold", "0.7"));
			params.put("limit", pInputs.get("max") == null ? "5" : pInputs.get("max"));
			final JsonNode reply = this.bodyOrNull(this.send("POST", "/match/" + AbstractRestConnector.segment(this.dataset()), params, body), "match");
			final JsonNode results = Json.at(reply, "responses.q1.results");
			final ObjectNode out = Json.object();
			final ArrayNode matches = out.putArray("matches");
			double best = 0;
			JsonNode bestEntity = null;
			if (results != null && results.isArray())
			{
				for (final JsonNode result : results)
				{
					matches.add(result);
					final double score = Json.at(result, "score") == null ? 0 : Json.at(result, "score").asDouble();
					if (score > best)
					{
						best = score;
						bestEntity = result;
					}
				}
			}
			out.put("hit", bestEntity != null && Json.at(bestEntity, "match") != null && Json.at(bestEntity, "match").asBoolean());
			out.put("score", best);
			out.put("matchCount", matches.size());
			out.put("screenedAt", com.on.ebx.connect.core.CatalogOperations.timestamp());
			out.put("dataset", this.dataset());
			if (bestEntity != null)
			{
				out.set("best", bestEntity);
				final ObjectNode summary = out.putObject("bestSummary");
				summary.put("id", Json.at(bestEntity, "id") == null ? "" : Json.at(bestEntity, "id").asText());
				summary.put("caption", Json.at(bestEntity, "caption") == null ? "" : Json.at(bestEntity, "caption").asText());
				summary.put("schema", Json.at(bestEntity, "schema") == null ? "" : Json.at(bestEntity, "schema").asText());
				summary.put("datasets", OpenSanctionsConnector.join(Json.at(bestEntity, "datasets")));
				summary.put("topics", OpenSanctionsConnector.join(Json.at(bestEntity, "properties.topics")));
			}
			return out;
		}
		if ("search".equals(pOperation))
		{
			final Map<String, String> params = new LinkedHashMap<String, String>();
			params.put("q", AbstractRestConnector.require(pInputs, "q"));
			if (pInputs.get("schema") != null && !pInputs.get("schema").trim().isEmpty())
			{
				params.put("schema", pInputs.get("schema").trim());
			}
			if (pInputs.get("countries") != null && !pInputs.get("countries").trim().isEmpty())
			{
				params.put("countries", pInputs.get("countries").trim().toLowerCase());
			}
			params.put("limit", pInputs.get("max") == null ? "10" : pInputs.get("max"));
			final JsonNode reply = this.bodyOrNull(this.get("/search/" + AbstractRestConnector.segment(this.dataset()), params), "search");
			final JsonNode results = Json.at(reply, "results");
			if (results == null || !results.isArray() || results.size() == 0)
			{
				return null;
			}
			final ObjectNode out = Json.object();
			out.set("results", results);
			out.put("count", results.size());
			return out;
		}
		if ("entity".equals(pOperation))
		{
			return this.bodyOrNull(this.get("/entities/" + AbstractRestConnector.segment(AbstractRestConnector.require(pInputs, "id")), null), "entity");
		}
		throw new ConnectorException("OpenSanctions: unknown operation '" + pOperation + "'");
	}

	private static void add(final ObjectNode pProperties, final String pKey, final String pValue)
	{
		if (pValue != null && !pValue.trim().isEmpty())
		{
			pProperties.putArray(pKey).add(pValue.trim());
		}
	}

	private static String join(final JsonNode pArray)
	{
		if (pArray == null || !pArray.isArray())
		{
			return "";
		}
		final StringBuilder sb = new StringBuilder();
		for (final JsonNode item : pArray)
		{
			sb.append(sb.length() > 0 ? ", " : "").append(item.asText());
		}
		return sb.toString();
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("screen".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("hit", "Hit above the threshold?", "boolean"), FieldInfo.of("score", "Best score 0-1", "number"),
				FieldInfo.of("matchCount", "Number of candidates", "number"), FieldInfo.of("screenedAt", "Screened at", "datetime"),
				FieldInfo.of("dataset", "Dataset screened", "string"), FieldInfo.of("bestSummary.id", "Best candidate id", "string"),
				FieldInfo.of("bestSummary.caption", "Best candidate name", "string"), FieldInfo.of("bestSummary.schema", "Best candidate type", "string"),
				FieldInfo.of("bestSummary.datasets", "Lists (us_ofac_sdn, eu_fsf, un_sc_sanctions...)", "string"),
				FieldInfo.of("bestSummary.topics", "Topics (sanction, role.pep, crime...)", "string"), FieldInfo.of("matches", "All candidates", "array"));
		}
		return Arrays.asList(FieldInfo.of("results[0].id", "Id", "string"), FieldInfo.of("results[0].caption", "Name", "string"),
			FieldInfo.of("results[0].schema", "Type", "string"), FieldInfo.of("results[0].datasets", "Lists", "array"),
			FieldInfo.of("results[0].properties.topics", "Topics", "array"), FieldInfo.of("results[0].properties.country", "Countries", "array"));
	}
}
