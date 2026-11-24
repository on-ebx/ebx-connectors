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
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * GLEIF Legal Entity Identifier API (https://api.gleif.org/api/v1, no key, 60 calls/min).
 * Every operation returns a document shaped like {@code GET /lei-records/{lei}}: the record under
 * {@code data}, so one inbound mapping with {@code responseRoot = data.attributes} serves them all.
 */
public final class GleifConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("lei", "LEI record by LEI", "lei"),
		Operation.search("searchByRegistration", "LEI record by national registration id (e.g. SIREN + RA000189)",
			"registrationId", "registrationAuthority"),
		Operation.search("searchByName", "First LEI record whose legal name fuzzy-matches", "name"),
		Operation.read("directParent", "LEI record of the direct parent (consolidating entity)", "lei"),
		Operation.read("ultimateParent", "LEI record of the ultimate parent", "lei"));

	@Override
	public String getType()
	{
		return "gleif";
	}

	@Override
	public String getLabel()
	{
		return "GLEIF (LEI)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "CORPORATE_REGISTRIES").description("Legal Entity Identifier records and parent relationships from GLEIF.");
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
		return GleifConnector.OPERATIONS;
	}

	/**
	 * GLEIF deliberately does not expose a resource at its API base URL.  Probe the
	 * collection endpoint instead; HEAD is supported and does not read or change
	 * a legal-entity record.
	 */
	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.nanoTime();
		this.requireSecret();
		final HttpJsonClient.Response response = this.send("HEAD", "/lei-records",
			java.util.Collections.<String, String>emptyMap(), null);
		final long elapsed = (System.nanoTime() - started) / 1000000L;
		final int status = response.getStatus();
		if (status >= 200 && status < 400)
		{
			return ConnectionTestResult.success("GLEIF lei-records endpoint responded with status " + status, elapsed);
		}
		return ConnectionTestResult.failure("GLEIF lei-records endpoint responded with status " + status, elapsed);
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		headers.put("Accept", "application/vnd.api+json");
		return headers;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation,
		final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		if ("lei".equals(pOperation))
		{
			final String lei = AbstractRestConnector.require(pInputs, "lei").toUpperCase();
			return this.bodyOrNull(this.get("/lei-records/" + AbstractRestConnector.segment(lei), null), "lei-records/" + lei);
		}
		if ("directParent".equals(pOperation) || "ultimateParent".equals(pOperation))
		{
			final String lei = AbstractRestConnector.require(pInputs, "lei").toUpperCase();
			final String suffix = "directParent".equals(pOperation) ? "direct-parent" : "ultimate-parent";
			return this.bodyOrNull(this.get("/lei-records/" + AbstractRestConnector.segment(lei) + "/" + suffix, null),
				suffix + " of " + lei);
		}
		if ("searchByRegistration".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("filter[entity.registeredAs]", AbstractRestConnector.require(pInputs, "registrationId"));
			query.put("filter[entity.registeredAt]", AbstractRestConnector.require(pInputs, "registrationAuthority"));
			query.put("page[size]", "1");
			return GleifConnector.firstAsRecord(this.bodyOrNull(this.get("/lei-records", query), "search by registration"));
		}
		if ("searchByName".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("field", "entity.legalName");
			query.put("q", AbstractRestConnector.require(pInputs, "name"));
			final JsonNode completions = this.bodyOrNull(this.get("/fuzzycompletions", query), "fuzzy name search");
			final JsonNode first = Json.at(completions, "data[0].relationships.lei-records.data.id");
			if (first == null)
			{
				return null;
			}
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			inputs.put("lei", first.asText());
			return this.execute(pContext, "lei", inputs, null);
		}
		throw new ConnectorException("GLEIF: unknown operation '" + pOperation + "'");
	}

	private static final String[][] FIELDS = {
		{ "data.id", "LEI", "string" }, { "data.attributes.entity.legalName.name", "Legal name", "string" },
		{ "data.attributes.entity.legalForm.id", "Legal form (ELF code)", "string" }, { "data.attributes.entity.category", "Entity category", "string" },
		{ "data.attributes.entity.jurisdiction", "Jurisdiction", "string" }, { "data.attributes.entity.status", "Entity status", "string" },
		{ "data.attributes.entity.creationDate", "Creation date", "datetime" }, { "data.attributes.entity.registeredAt.id", "Registration authority (RA code)", "string" },
		{ "data.attributes.entity.registeredAs", "Registration number", "string" },
		{ "data.attributes.entity.legalAddress.addressLines", "Legal address lines", "array" }, { "data.attributes.entity.legalAddress.city", "Legal address city", "string" },
		{ "data.attributes.entity.legalAddress.region", "Legal address region", "string" }, { "data.attributes.entity.legalAddress.country", "Legal address country", "string" },
		{ "data.attributes.entity.legalAddress.postalCode", "Legal address postal code", "string" },
		{ "data.attributes.entity.headquartersAddress.addressLines", "HQ address lines", "array" }, { "data.attributes.entity.headquartersAddress.city", "HQ city", "string" },
		{ "data.attributes.entity.headquartersAddress.region", "HQ region", "string" }, { "data.attributes.entity.headquartersAddress.country", "HQ country", "string" },
		{ "data.attributes.entity.headquartersAddress.postalCode", "HQ postal code", "string" },
		{ "data.attributes.registration.status", "LEI registration status", "string" }, { "data.attributes.registration.initialRegistrationDate", "Initial registration", "datetime" },
		{ "data.attributes.registration.lastUpdateDate", "Last update", "datetime" }, { "data.attributes.registration.nextRenewalDate", "Next renewal", "datetime" },
		{ "data.attributes.registration.managingLou", "Managing LOU", "string" }, { "data.attributes.bic", "BIC codes", "array" },
		{ "data.attributes.mic", "MIC codes", "array" }, { "data.attributes.ocid", "OpenCorporates id", "string" }, { "data.attributes.spglobal", "S&P Global ids", "array" } };

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		for (final String[] f : GleifConnector.FIELDS)
		{
			fields.add(FieldInfo.of(f[0], f[1], f[2]));
		}
		return fields;
	}

	/** {@code {data: [first]}} to {@code {data: first}} so that the same mapping applies. */
	private static JsonNode firstAsRecord(final JsonNode pList)
	{
		final JsonNode first = Json.at(pList, "data[0]");
		if (first == null)
		{
			return null;
		}
		final ObjectNode wrapped = Json.object();
		wrapped.set("data", first);
		final List<String> keep = new ArrayList<String>();
		keep.add("meta");
		if (pList.has("meta"))
		{
			wrapped.set("meta", pList.get("meta"));
		}
		return wrapped;
	}
}
