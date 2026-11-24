package com.on.ebx.connect.connectors;

import java.util.ArrayList;
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
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Dun &amp; Bradstreet Direct+ ({@code https://plus.dnb.com}).
 * <p>
 * Connector row: authentication BASIC with {@code username} = consumer key and {@code password} =
 * consumer secret, exchanged for a bearer token on {@code /v2/token} (cached, renewed before
 * expiry). Properties: {@code blockIDs} (default
 * {@code companyinfo_L2_v1,hierarchyconnections_L1_v1}), {@code confidence.min} (default 7),
 * {@code candidates.max} (default 10), {@code tradeUp} ({@code hq} to resolve to the headquarters).
 * <ul>
 * <li>{@code match} (SEARCH): cleanse-and-match on {@code name}, {@code country}, {@code street},
 * {@code city}, {@code postalCode}, {@code registrationNumber}, {@code duns}: returns
 * {@code {matchCandidates:[...], count}}.</li>
 * <li>{@code bestMatch} (READ): same, but only the best candidate above the confidence floor,
 * as {@code {duns, confidenceCode, organization:{...}}}; null when none.</li>
 * <li>{@code dataBlocks} (READ): data blocks of a {@code duns} (input {@code blockIDs} overrides
 * the property): {@code {organization:{...}}}.</li>
 * <li>{@code enrich} (READ): best match then data blocks in one call: the match's DUNS and
 * confidence are added to the organization under {@code match}.</li>
 * <li>{@code familyTree} (READ): {@code hierarchyconnections_L1_v1} of a {@code duns}.</li>
 * <li>{@code monitoringUpdates} (SEARCH): reads change notifications for a configured monitoring
 * registration.</li>
 * <li>{@code registrationAdd}/{@code registrationRemove} (WRITE): changes the monitored DUNS set
 * using an explicitly configured registration endpoint template.</li>
 * </ul>
 */
public final class DnbConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("match", "Cleanse and match: candidates for a name, address or registration number", new String[0]),
		Operation.read("bestMatch", "Best candidate above the confidence floor (DUNS + organization)", new String[0]),
		Operation.read("dataBlocks", "Data blocks of a DUNS", "duns"),
		Operation.read("enrich", "Best match then data blocks, in one call", new String[0]),
		Operation.read("familyTree", "Corporate linkage (hierarchy connections) of a DUNS", "duns"),
		Operation.search("monitoringUpdates", "Pull registered DUNS change notifications", new String[0]),
		Operation.write("registrationAdd", "Register one or more DUNS for monitoring", "duns"),
		Operation.write("registrationRemove", "Remove one or more DUNS from monitoring", "duns"));

	private static final String[] MATCH_PARAMS = { "name", "street", "city", "postalCode", "registrationNumber", "duns", "region", "telephone" };
	private static final String[][] MATCH_QUERY = { { "name", "name" }, { "street", "streetAddressLine1" }, { "city", "addressLocality" },
		{ "postalCode", "postalCode" }, { "registrationNumber", "registrationNumber" }, { "duns", "duns" }, { "region", "addressRegion" },
		{ "telephone", "telephoneNumber" } };

	private static final String[][] FIELDS = {
		{ "organization.duns", "DUNS", "string" }, { "organization.primaryName", "Primary name", "string" },
		{ "organization.registeredName", "Registered name", "string" }, { "organization.tradeStyleNames[0].name", "Trade style", "string" },
		{ "organization.registrationNumbers[0].registrationNumber", "Registration number", "string" },
		{ "organization.registrationNumbers[0].typeDescription", "Registration number type", "string" },
		{ "organization.dunsControlStatus.operatingStatus.description", "Operating status", "string" },
		{ "organization.startDate", "Start date", "date" }, { "organization.incorporatedDate", "Incorporation date", "date" },
		{ "organization.legalForm.description", "Legal form", "string" },
		{ "organization.primaryAddress.streetAddress.line1", "Street", "string" }, { "organization.primaryAddress.addressLocality.name", "City", "string" },
		{ "organization.primaryAddress.postalCode", "Postal code", "string" }, { "organization.primaryAddress.addressRegion.name", "Region", "string" },
		{ "organization.primaryAddress.addressCountry.isoAlpha2Code", "Country", "string" },
		{ "organization.primaryAddress.latitude", "Latitude", "number" }, { "organization.primaryAddress.longitude", "Longitude", "number" },
		{ "organization.telephone[0].telephoneNumber", "Telephone", "string" }, { "organization.websiteAddress[0].url", "Website", "string" },
		{ "organization.numberOfEmployees[0].value", "Employees", "number" }, { "organization.financials[0].yearlyRevenue[0].value", "Yearly revenue", "number" },
		{ "organization.financials[0].yearlyRevenue[0].currency", "Revenue currency", "string" },
		{ "organization.industryCodes[0].code", "Primary industry code", "string" }, { "organization.industryCodes[0].description", "Primary industry", "string" },
		{ "organization.primaryIndustryCode.usSicV4", "US SIC", "string" }, { "organization.isStandalone", "Standalone?", "boolean" },
		{ "organization.corporateLinkage.globalUltimate.duns", "Global ultimate DUNS", "string" },
		{ "organization.corporateLinkage.globalUltimate.primaryName", "Global ultimate name", "string" },
		{ "organization.corporateLinkage.domesticUltimate.duns", "Domestic ultimate DUNS", "string" },
		{ "organization.corporateLinkage.parent.duns", "Parent DUNS", "string" }, { "organization.corporateLinkage.parent.primaryName", "Parent name", "string" },
		{ "organization.match.confidenceCode", "Match confidence (enrich)", "number" } };

	private String token;
	private long tokenExpiry;

	@Override
	public String getType()
	{
		return "dnb";
	}

	@Override
	public String getLabel()
	{
		return "Dun & Bradstreet Direct+";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "CORPORATE_REGISTRIES").description("Dun & Bradstreet company matching, enrichment and corporate linkage.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS);
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("blockIDs",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("blockIDs").description("Requested Direct+ data blocks").build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("candidates.max",
			com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER).label("candidates.max").description("Maximum match candidates").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("confidence.min",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("confidence.min").description("Minimum accepted confidence").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("country",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("country").description("Default country code").build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("tradeUp",
			com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN).label("tradeUp").description("Enable trade-up behavior").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("registration.reference",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("Monitoring registration reference")
			.description("D&B reference used by registration and notification endpoints").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("registration.path",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("Registration path template")
			.description("Relative path containing {reference} and {type}; required for registration writes").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("notifications.path",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("Notifications path template")
			.description("Relative path containing {reference}; required for monitoring reads").advanced(true).build());
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return DnbConnector.OPERATIONS;
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Accept", "application/json");
		if (this.token != null)
		{
			headers.put("Authorization", "Bearer " + this.token);
		}
		return headers;
	}

	private synchronized void authenticate() throws ConnectorException
	{
		final ConnectorConfig cfg = this.getConfig();
		if (cfg.getUsername() == null || cfg.isPlaceholder(cfg.getPassword()))
		{
			throw new ConnectorException("D&B: set the consumer key (username) and consumer secret (password), authentication BASIC");
		}
		if (this.token != null && System.currentTimeMillis() < this.tokenExpiry)
		{
			return;
		}
		final HttpJsonClient client = new HttpJsonClient(cfg.getConnectTimeoutMs(), cfg.getReadTimeoutMs(), "EBX-Connectors/1.0 (dnb)");
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/json");
		headers.put("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
			(cfg.getUsername() + ":" + cfg.getPassword()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		try
		{
			final HttpJsonClient.Response response = client.send("POST", HttpJsonClient.buildUrl(cfg.getBaseUrl(), "/v2/token", null), headers,
				"{\"grant_type\":\"client_credentials\"}");
			final JsonNode token = Json.at(response.getBody(), "access_token");
			if (!response.isSuccess() || token == null)
			{
				throw new ConnectorException("D&B token request failed (HTTP " + response.getStatus() + "): "
						+ String.valueOf(Json.at(response.getBody(), "error.errorMessage")), response.getStatus(), null);
			}
			this.token = token.asText();
			final JsonNode expires = Json.at(response.getBody(), "expiresIn");
			this.tokenExpiry = System.currentTimeMillis() + (expires == null ? 3600L : Math.max(60L, expires.asLong() - 120L)) * 1000L;
		}
		catch (final java.io.IOException ex)
		{
			throw new ConnectorException("D&B token request failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Verifies the configured Direct+ credentials without reading or changing business data.
	 *
	 * <p>The generic REST probe sends {@code HEAD} to the API root. Direct+ does not expose a
	 * meaningful unauthenticated root probe (and can answer it with HTTP 500), so the only accurate
	 * side-effect-free readiness check is the OAuth client-credentials token exchange itself.</p>
	 */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.nanoTime();
		this.authenticate();
		final long elapsed = (System.nanoTime() - started) / 1000000L;
		return ConnectionTestResult.success("D&B Direct+ credentials accepted; bearer token obtained", elapsed);
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs,
		final JsonNode pPayload) throws ConnectorException
	{
		this.authenticate();
		if ("match".equals(pOperation))
		{
			return this.match(pInputs);
		}
		if ("bestMatch".equals(pOperation))
		{
			return this.bestMatch(pInputs);
		}
		if ("dataBlocks".equals(pOperation))
		{
			return this.dataBlocks(AbstractRestConnector.require(pInputs, "duns"), pInputs.get("blockIDs"));
		}
		if ("familyTree".equals(pOperation))
		{
			return this.dataBlocks(AbstractRestConnector.require(pInputs, "duns"), "hierarchyconnections_L1_v1");
		}
		if ("enrich".equals(pOperation))
		{
			final JsonNode best = this.bestMatch(pInputs);
			if (best == null)
			{
				return null;
			}
			final JsonNode blocks = this.dataBlocks(best.get("duns").asText(), pInputs.get("blockIDs"));
			if (blocks == null)
			{
				return null;
			}
			final JsonNode organization = blocks.get("organization");
			if (organization != null && organization.isObject())
			{
				final ObjectNode match = ((ObjectNode) organization).putObject("match");
				match.put("duns", best.get("duns").asText());
				match.put("confidenceCode", best.get("confidenceCode").asInt());
				match.put("matchGrade", best.get("matchGrade") == null ? "" : best.get("matchGrade").asText());
			}
			return blocks;
		}
		if ("monitoringUpdates".equals(pOperation))
		{
			return this.monitoringUpdates();
		}
		if ("registrationAdd".equals(pOperation) || "registrationRemove".equals(pOperation))
		{
			return this.updateRegistration("registrationAdd".equals(pOperation) ? "add" : "remove",
				AbstractRestConnector.require(pInputs, "duns"));
		}
		throw new ConnectorException("D&B: unknown operation '" + pOperation + "'");
	}

	private JsonNode monitoringUpdates() throws ConnectorException
	{
		final String path = this.monitoringPath("notifications.path", false, null);
		return this.bodyOrNull(this.get(path, java.util.Collections.<String, String>emptyMap()), "monitoring updates");
	}

	private JsonNode updateRegistration(final String pType, final String pDuns) throws ConnectorException
	{
		final String path = this.monitoringPath("registration.path", true, pType);
		final String boundary = "----EBXConnectorsDnb" + Long.toHexString(System.nanoTime());
		final String body = DnbConnector.multipartDuns(boundary, pDuns);
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
		final HttpJsonClient.Response response = this.sendRaw("PATCH", path,
			java.util.Collections.<String, String>emptyMap(), body, headers);
		if (!response.isSuccess())
		{
			throw new ConnectorException("D&B registration " + pType + " failed (HTTP " + response.getStatus() + ")",
				response.getStatus(), null);
		}
		final ObjectNode out = Json.object();
		out.put("registrationReference", this.getConfig().getProperty("registration.reference", ""));
		out.put("action", pType);
		out.put("count", DnbConnector.dunsValues(pDuns).size());
		out.put("accepted", true);
		return out;
	}

	private String monitoringPath(final String pProperty, final boolean pNeedsType, final String pType)
		throws ConnectorException
	{
		final String reference = this.getConfig().getProperty("registration.reference", null);
		final String template = this.getConfig().getProperty(pProperty, null);
		if (reference == null || template == null)
		{
			throw new ConnectorException("D&B: set registration.reference and " + pProperty + " before using monitoring");
		}
		if (!template.startsWith("/") || template.contains("..") || !template.contains("{reference}")
			|| (pNeedsType && !template.contains("{type}")))
		{
			throw new ConnectorException("D&B: unsafe " + pProperty + " template");
		}
		String path = template.replace("{reference}", HttpJsonClient.encode(reference));
		if (pNeedsType) path = path.replace("{type}", pType);
		return path;
	}

	static String multipartDuns(final String pBoundary, final String pDuns) throws ConnectorException
	{
		final List<String> values = DnbConnector.dunsValues(pDuns);
		final StringBuilder text = new StringBuilder();
		for (final String value : values) text.append(value).append('\n');
		final String body = "--" + pBoundary + "\r\nContent-Disposition: form-data; name=\"duns\"; filename=\"duns.txt\"\r\n"
			+ "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + text + "\r\n--" + pBoundary + "--\r\n";
		return body;
	}

	static List<String> dunsValues(final String pDuns) throws ConnectorException
	{
		final List<String> values = new ArrayList<String>();
		if (pDuns != null)
		{
			for (final String raw : pDuns.split("[,;\\s]+"))
			{
				if (raw.trim().isEmpty()) continue;
				final String value = raw.replaceAll("[^0-9]", "");
				if (value.length() != 9) throw new ConnectorException("D&B: '" + raw + "' is not a 9-digit DUNS");
				if (!values.contains(value)) values.add(value);
			}
		}
		if (values.isEmpty()) throw new ConnectorException("D&B: at least one 9-digit DUNS is required");
		return values;
	}

	private JsonNode match(final Map<String, String> pInputs) throws ConnectorException
	{
		final Map<String, String> query = DnbConnector.matchQuery(pInputs, this.getConfig());
		if (query.size() <= 2)
		{
			throw new ConnectorException("D&B match: give at least a name, a registration number or a DUNS");
		}
		final HttpJsonClient.Response response = this.get("/v1/match/cleanseMatch", query);
		if (response.getStatus() == 404)
		{
			return null; // no candidate
		}
		final JsonNode body = this.bodyOrNull(response, "cleanseMatch");
		final JsonNode candidates = Json.at(body, "matchCandidates");
		if (candidates == null || !candidates.isArray() || candidates.size() == 0)
		{
			return null;
		}
		final ObjectNode out = Json.object();
		out.set("matchCandidates", candidates);
		out.put("count", candidates.size());
		out.set("inquiryDetail", body.get("inquiryDetail"));
		return out;
	}

	static Map<String, String> matchQuery(final Map<String, String> pInputs, final ConnectorConfig pConfig)
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		final String country = pInputs == null ? null : pInputs.get("country");
		query.put("countryISOAlpha2Code", country == null || country.trim().isEmpty() ? pConfig.getProperty("country", "FR") : country.trim().toUpperCase());
		for (final String[] mapping : DnbConnector.MATCH_QUERY)
		{
			final String value = pInputs == null ? null : pInputs.get(mapping[0]);
			if (value != null && !value.trim().isEmpty())
			{
				query.put(mapping[1], value.trim());
			}
		}
		query.put("candidateMaximumQuantity", pConfig.getProperty("candidates.max", "10"));
		if (pConfig.getProperty("tradeUp", null) != null)
		{
			query.put("tradeUp", pConfig.getProperty("tradeUp", null));
		}
		return query;
	}

	private JsonNode bestMatch(final Map<String, String> pInputs) throws ConnectorException
	{
		final JsonNode matched = this.match(pInputs);
		if (matched == null)
		{
			return null;
		}
		final int floor = DnbConnector.toInt(this.getConfig().getProperty("confidence.min", "7"), 7);
		JsonNode best = null;
		int bestCode = -1;
		for (final JsonNode candidate : matched.get("matchCandidates"))
		{
			final JsonNode code = Json.at(candidate, "matchQualityInformation.confidenceCode");
			final int c = code == null ? 0 : code.asInt();
			if (c > bestCode)
			{
				best = candidate;
				bestCode = c;
			}
		}
		if (best == null || bestCode < floor)
		{
			return null;
		}
		final ObjectNode out = Json.object();
		out.put("duns", Json.at(best, "organization.duns") == null ? "" : Json.at(best, "organization.duns").asText());
		out.put("confidenceCode", bestCode);
		final JsonNode grade = Json.at(best, "matchQualityInformation.matchGrade");
		if (grade != null)
		{
			out.put("matchGrade", grade.asText());
		}
		out.set("organization", best.get("organization"));
		out.set("matchQualityInformation", best.get("matchQualityInformation"));
		return out;
	}

	private JsonNode dataBlocks(final String pDuns, final String pBlocks) throws ConnectorException
	{
		final String duns = pDuns.replaceAll("[^0-9]", "");
		if (duns.length() != 9)
		{
			throw new ConnectorException("D&B: '" + pDuns + "' is not a 9-digit DUNS");
		}
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("blockIDs", pBlocks == null || pBlocks.trim().isEmpty()
				? this.getConfig().getProperty("blockIDs", "companyinfo_L2_v1,hierarchyconnections_L1_v1") : pBlocks.trim());
		final String tradeUp = this.getConfig().getProperty("tradeUp", null);
		if (tradeUp != null)
		{
			query.put("tradeUp", tradeUp);
		}
		return this.bodyOrNull(this.get("/v1/data/duns/" + duns, query), "data blocks of " + duns);
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		if ("match".equals(pOperation))
		{
			fields.add(FieldInfo.of("matchCandidates[0].organization.duns", "Best candidate DUNS", "string"));
			fields.add(FieldInfo.of("matchCandidates[0].organization.primaryName", "Best candidate name", "string"));
			fields.add(FieldInfo.of("matchCandidates[0].matchQualityInformation.confidenceCode", "Confidence code (1-10)", "number"));
			fields.add(FieldInfo.of("matchCandidates[0].matchQualityInformation.matchGrade", "Match grade", "string"));
			return fields;
		}
		final String prefix = "bestMatch".equals(pOperation) ? "" : "";
		if ("bestMatch".equals(pOperation))
		{
			fields.add(FieldInfo.of("duns", "DUNS", "string"));
			fields.add(FieldInfo.of("confidenceCode", "Confidence code (1-10)", "number"));
			fields.add(FieldInfo.of("organization.primaryName", "Primary name", "string"));
			fields.add(FieldInfo.of("organization.primaryAddress.addressLocality.name", "City", "string"));
			return fields;
		}
		for (final String[] f : DnbConnector.FIELDS)
		{
			fields.add(FieldInfo.of(prefix + f[0], f[1], f[2]));
		}
		return fields;
	}

	private static int toInt(final String pValue, final int pDefault)
	{
		try
		{
			return Integer.parseInt(pValue.trim());
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}

	static String[] matchParams()
	{
		return DnbConnector.MATCH_PARAMS;
	}
}
