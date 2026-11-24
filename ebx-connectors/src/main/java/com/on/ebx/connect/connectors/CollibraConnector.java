package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MultipartBody;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Collibra Data Intelligence Platform through its REST API v2 ({@code <instance>/rest/2.0}), in
 * both directions: read assets, business terms and types (glossary → EBX), create or update assets
 * with their attributes and relations (EBX → Collibra), bulk import jobs.
 * <p>
 * Connector row: {@code baseUrl} = {@code https://<instance>.collibra.com} (the {@code /rest/2.0}
 * suffix is added), {@code authType} = {@code BASIC} (a Collibra user with API access) or
 * {@code BEARER} / {@code OAUTH2} (a JWT from the identity provider configured on the instance).
 * <p>
 * Everything Collibra identifies by UUID is addressed here by <b>name</b>: communities, domains,
 * asset types, attribute types, statuses, and relation types written {@code Source type>role>Target
 * type} (or a UUID). Names are resolved once and cached for the life of the connector instance
 * (the registry rebuilds it on a configuration change). Asset names are unique within a domain:
 * a column is named {@code <table qualified name> > <column>} with the column as display name,
 * the convention of Collibra's own catalog ingestion.
 * <p>
 * Operations: {@code search} (assets by name / type / domain / community), {@code get} (one asset
 * with attributes and relations), {@code terms} (business terms as a flat feed for a glossary
 * mapping), {@code types} (asset / attribute / relation types, statuses, communities, domains),
 * {@code upsert} (asset + attributes + parent / term relations, domain created on demand),
 * {@code relate} (one relation, idempotent), {@code import} (raw import commands as a job),
 * {@code delete}. Inputs and payload fields carry the same names; an input wins over the payload.
 */
public final class CollibraConnector extends AbstractRestConnector
{
	static final String API = "/rest/2.0";

	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("search", "Assets by name (inputs name, match = EXACT|START|ANYWHERE), type, domain, community, status; attributes=true adds their attributes"),
		Operation.read("get", "One asset with its attributes and relations (input id, or name + domain [+ community])"),
		Operation.search("terms", "Business terms as a flat feed: id, code, name, definition, description, status, domain, community, url (inputs type, domain, community, name, limit)"),
		Operation.read("types", "Reference lists: input kind = assetTypes | attributeTypes | relationTypes | statuses | communities | domains | domainTypes (optional name)", "kind"),
		Operation.write("upsert", "Creates or updates an asset by name in a domain (created on demand): displayName, type, status, attributes.*, parent (+ parentRelation), term (+ termRelation), relations[]"),
		Operation.write("relate", "Creates a relation if missing: source / target as ids, or names with sourceParent / sourceDomain and targetParent / targetDomain; type = Source>role>Target or a UUID"),
		Operation.write("import", "Runs an import job from raw Collibra import commands (payload array); inputs wait=true, timeout (seconds)"),
		Operation.write("delete", "Deletes an asset (input id)", "id"));

	/** Cache of names → ids, one per connector instance. */
	private final Map<String, String> ids = new LinkedHashMap<String, String>();
	private JsonNode relationTypes;

	@Override
	public String getType()
	{
		return "collibra";
	}

	@Override
	public String getLabel()
	{
		return "Collibra Data Intelligence Platform (REST API v2)";
	}

	@Override
	public List<Operation> getOperations()
	{
		return CollibraConnector.OPERATIONS;
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(this.getType(), this.getLabel(), "DATA_PLATFORMS")
			.description("Collibra assets, business terms, attributes and relations in both directions (glossary to EBX, catalog and lineage to Collibra)")
			.authentication(AuthenticationMethod.BASIC).authentication(AuthenticationMethod.BEARER_TOKEN).authentication(AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS)
			.capability(ConnectorCapability.BULK_READ).capability(ConnectorCapability.BULK_WRITE).capability(ConnectorCapability.DESCRIBE_OBJECT).apiVersion("Collibra REST API 2.0")
			.configurationProperty(CollibraConnector.prop("community", ConfigurationPropertyType.STRING, "Data Governance Council", "Community where domains are created on demand and where names are resolved"))
			.configurationProperty(CollibraConnector.prop("domain.type", ConfigurationPropertyType.STRING, "Physical Data Dictionary", "Domain type of the domains created on demand"))
			.configurationProperty(CollibraConnector.prop("term.type", ConfigurationPropertyType.STRING, "Business Term", "Asset type of the glossary terms read by the terms operation"))
			.configurationProperty(CollibraConnector.prop("relation.contains", ConfigurationPropertyType.STRING, "Table>contains>Column", "Relation type parent > child (Source type>role>Target type, or a UUID)"))
			.configurationProperty(CollibraConnector.prop("relation.lineage", ConfigurationPropertyType.STRING, "Column>is source for>Column", "Relation type used for a column-level lineage edge"))
			.configurationProperty(CollibraConnector.prop("relation.lineageTable", ConfigurationPropertyType.STRING, "Table>is source for>Table", "Relation type used for a table-level lineage edge"))
			.configurationProperty(CollibraConnector.prop("relation.term", ConfigurationPropertyType.STRING, "Column>represents>Business Term", "Relation type between a column and its business term"))
			.configurationProperty(CollibraConnector.prop("attribute.description", ConfigurationPropertyType.STRING, "Description", "Attribute type receiving attributes.description"))
			.configurationProperty(CollibraConnector.prop("attribute.definition", ConfigurationPropertyType.STRING, "Definition", "Attribute type read as the definition of a term"))
			.configurationProperty(CollibraConnector.prop("attribute.dataType", ConfigurationPropertyType.STRING, "Technical Data Type", "Attribute type receiving attributes.dataType"))
			.configurationProperty(CollibraConnector.prop("attribute.note", ConfigurationPropertyType.STRING, "Note", "Attribute type receiving attributes.note"))
			.configurationProperty(CollibraConnector.prop("attribute.url", ConfigurationPropertyType.STRING, "URL", "Attribute type receiving attributes.url"))
			.configurationProperty(CollibraConnector.prop("max.rows", ConfigurationPropertyType.INTEGER, Integer.valueOf(1000), "Results returned at most by search and terms"));
		for (final Operation o : CollibraConnector.OPERATIONS)
		{
			final ConnectorDirection d = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(d).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(), java.util.Collections.singleton(d), java.util.Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	private static ConfigurationPropertyDefinition prop(final String pName, final ConfigurationPropertyType pType, final Object pDefault, final String pDescription)
	{
		return ConfigurationPropertyDefinition.builder(pName, pType).required(false).defaultValue(pDefault).description(pDescription).build();
	}

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		this.ids.clear();
		this.relationTypes = null;
		super.configure(pConfig);
	}

	@Override
	protected String baseUrl() throws ConnectorException
	{
		final String url = this.getConfig().getBaseUrl();
		if (url == null || url.trim().isEmpty()) throw new ConnectorException("Collibra: baseUrl (https://<instance>.collibra.com) is not set");
		final String base = url.trim().replaceAll("/+$", "");
		return base.endsWith(CollibraConnector.API) ? base : base + CollibraConnector.API;
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		headers.put("Accept", "application/json");
		return headers;
	}

	@Override
	protected boolean nativePatch()
	{
		return true; // Collibra has no method override: PATCH goes through java.net.http (JDK 11+)
	}

	@Override
	public ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long started = System.nanoTime();
		this.requireSecret();
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("limit", "1");
		final HttpJsonClient.Response response = this.get("/communities", query);
		final long elapsed = (System.nanoTime() - started) / 1000000L;
		if (!response.isSuccess()) return ConnectionTestResult.failure("GET communities -> HTTP " + response.getStatus(), elapsed);
		final String community = this.getConfig().getProperty("community", "");
		final String communityId = community.isEmpty() ? null : this.idOrNull("community", community, null);
		return ConnectionTestResult.success("Collibra REST API 2.0 reachable" + (community.isEmpty() ? "" : communityId == null ? "; community '" + community + "' NOT found" : "; community '" + community + "' found"), elapsed);
	}

	// ------------------------------------------------------------------ operations

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		this.requireSecret();
		final Map<String, String> in = pInputs == null ? new LinkedHashMap<String, String>() : pInputs;
		if ("search".equals(pOperation)) return this.search(in);
		if ("get".equals(pOperation)) return this.getAsset(in);
		if ("terms".equals(pOperation)) return this.terms(in);
		if ("types".equals(pOperation)) return this.types(in);
		if ("upsert".equals(pOperation)) return this.upsert(pContext, in, pPayload);
		if ("relate".equals(pOperation)) return this.relate(in, pPayload);
		if ("import".equals(pOperation)) return this.importJob(in, pPayload);
		if ("delete".equals(pOperation))
		{
			final HttpJsonClient.Response response = this.send("DELETE", "/assets/" + AbstractRestConnector.segment(AbstractRestConnector.require(in, "id")), null, null);
			if (!response.isSuccess() && response.getStatus() != 404) this.body(response, "delete asset");
			return Json.object().put("deleted", response.isSuccess());
		}
		throw new ConnectorException("Collibra: unknown operation '" + pOperation + "'");
	}

	JsonNode search(final Map<String, String> pInputs) throws ConnectorException
	{
		final Map<String, String> query = this.assetQuery(pInputs, pInputs.get("type"));
		final ObjectNode out = Json.object();
		final ArrayNode items = out.putArray("items");
		final boolean attributes = Boolean.parseBoolean(pInputs.get("attributes"));
		for (final JsonNode asset : this.page("/assets", query, CollibraConnector.bounded(pInputs.get("limit"), this.getConfig().getProperty("max.rows", "1000"))))
		{
			final ObjectNode item = this.flat(asset);
			if (attributes) item.set("attributes", this.attributes(asset.path("id").asText()));
			items.add(item);
		}
		out.put("count", items.size());
		return items.size() == 0 ? null : out;
	}

	JsonNode getAsset(final Map<String, String> pInputs) throws ConnectorException
	{
		String id = pInputs.get("id");
		if (id == null || id.trim().isEmpty())
		{
			final JsonNode found = this.findAsset(AbstractRestConnector.require(pInputs, "name"), pInputs.get("domain"), pInputs.get("community"), null);
			if (found == null) return null;
			id = found.path("id").asText();
		}
		final JsonNode asset = this.bodyOrNull(this.get("/assets/" + AbstractRestConnector.segment(id.trim()), null), "get asset");
		if (asset == null) return null;
		final ObjectNode out = this.flat(asset);
		out.set("attributes", this.attributes(id.trim()));
		final ArrayNode outgoing = out.putArray("outgoing");
		final ArrayNode incoming = out.putArray("incoming");
		final Map<String, String> q = new LinkedHashMap<String, String>();
		q.put("sourceId", id.trim());
		for (final JsonNode r : this.page("/relations", q, 1000)) outgoing.add(this.flatRelation(r));
		q.clear();
		q.put("targetId", id.trim());
		for (final JsonNode r : this.page("/relations", q, 1000)) incoming.add(this.flatRelation(r));
		return out;
	}

	/** Business terms flattened for a glossary feed: definition and description read from the attributes. */
	JsonNode terms(final Map<String, String> pInputs) throws ConnectorException
	{
		final String type = pInputs.get("type") == null || pInputs.get("type").trim().isEmpty() ? this.getConfig().getProperty("term.type", "Business Term") : pInputs.get("type");
		final Map<String, String> query = this.assetQuery(pInputs, type);
		final String definitionType = this.attributeType("definition");
		final String descriptionType = this.attributeType("description");
		final String noteType = this.attributeType("note");
		final ObjectNode out = Json.object();
		final ArrayNode items = out.putArray("items");
		for (final JsonNode asset : this.page("/assets", query, CollibraConnector.bounded(pInputs.get("limit"), this.getConfig().getProperty("max.rows", "1000"))))
		{
			final ObjectNode item = this.flat(asset);
			item.put("code", asset.path("id").asText());
			final ObjectNode attributes = this.attributes(asset.path("id").asText());
			item.put("definition", attributes.path(definitionType).asText(""));
			item.put("description", attributes.path(descriptionType).asText(""));
			item.put("note", attributes.path(noteType).asText(""));
			items.add(item);
		}
		out.put("count", items.size());
		return items.size() == 0 ? null : out;
	}

	JsonNode types(final Map<String, String> pInputs) throws ConnectorException
	{
		final String kind = AbstractRestConnector.require(pInputs, "kind");
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if (pInputs.get("name") != null && !pInputs.get("name").trim().isEmpty()) query.put("name", pInputs.get("name").trim());
		if ("domains".equals(kind) && pInputs.get("community") != null) query.put("communityId", this.id("community", pInputs.get("community"), null));
		final ObjectNode out = Json.object();
		final ArrayNode items = out.putArray("items");
		for (final JsonNode t : this.page("/" + kind, query, 1000))
		{
			final ObjectNode item = items.addObject();
			item.put("id", t.path("id").asText());
			item.put("name", t.path("name").asText());
			if (t.has("description")) item.put("description", t.path("description").asText(""));
			if ("relationTypes".equals(kind))
			{
				item.put("source", t.path("sourceType").path("name").asText());
				item.put("role", t.path("role").asText());
				item.put("coRole", t.path("coRole").asText());
				item.put("target", t.path("targetType").path("name").asText());
				item.put("spec", t.path("sourceType").path("name").asText() + ">" + t.path("role").asText() + ">" + t.path("targetType").path("name").asText());
			}
			if ("domains".equals(kind))
			{
				item.put("community", t.path("community").path("name").asText());
				item.put("type", t.path("type").path("name").asText());
			}
			if (t.has("parentType")) item.put("parent", t.path("parentType").path("name").asText());
		}
		out.put("count", items.size());
		return out;
	}

	/** Creates or updates one asset: name + domain identify it; attributes and relations are set idempotently. */
	JsonNode upsert(final ConnectorContext pContext, final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final JsonNode payload = pPayload == null ? Json.object() : pPayload;
		final String name = CollibraConnector.value(pInputs, payload, "name");
		if (name == null) throw new ConnectorException("Collibra upsert: 'name' is required (input or payload)");
		final String parent = CollibraConnector.value(pInputs, payload, "parent");
		final boolean qualify = !"false".equalsIgnoreCase(CollibraConnector.value(pInputs, payload, "qualify"));
		final String fullName = parent != null && qualify && !name.startsWith(parent + " > ") ? parent + " > " + name : name;
		final String community = CollibraConnector.first(CollibraConnector.value(pInputs, payload, "community"), this.getConfig().getProperty("community", null));
		final String domain = CollibraConnector.value(pInputs, payload, "domain");
		if (domain == null) throw new ConnectorException("Collibra upsert: 'domain' is required (input or payload)");
		final String domainId = this.domainId(domain, community, CollibraConnector.first(CollibraConnector.value(pInputs, payload, "domainType"), this.getConfig().getProperty("domain.type", "Physical Data Dictionary")), true);
		final String type = CollibraConnector.value(pInputs, payload, "type");
		final String typeId = type == null ? null : this.id("assetType", type, null);
		final String status = CollibraConnector.value(pInputs, payload, "status");
		final String statusId = status == null ? null : this.id("status", status, null);
		final String displayName = CollibraConnector.first(CollibraConnector.value(pInputs, payload, "displayName"), parent != null && qualify ? name : null);
		final ObjectNode out = Json.object();
		JsonNode existing = this.findAssetInDomain(fullName, domainId);
		boolean created = false;
		if (existing == null)
		{
			if (typeId == null) throw new ConnectorException("Collibra upsert: 'type' is required to create asset '" + fullName + "'");
			final ObjectNode body = Json.object();
			body.put("name", fullName);
			body.put("domainId", domainId);
			body.put("typeId", typeId);
			if (displayName != null) body.put("displayName", displayName);
			if (statusId != null) body.put("statusId", statusId);
			existing = this.body(this.send("POST", "/assets", null, body), "create asset");
			created = true;
		}
		else
		{
			final ObjectNode patch = Json.object();
			if (displayName != null && !displayName.equals(existing.path("displayName").asText(""))) patch.put("displayName", displayName);
			if (typeId != null && !typeId.equals(existing.path("type").path("id").asText(""))) patch.put("typeId", typeId);
			if (statusId != null && !statusId.equals(existing.path("status").path("id").asText(""))) patch.put("statusId", statusId);
			if (patch.size() > 0) existing = this.body(this.send("PATCH", "/assets/" + AbstractRestConnector.segment(existing.path("id").asText()), null, patch), "update asset");
		}
		final String assetId = existing.path("id").asText();
		out.put("id", assetId);
		out.put("name", fullName);
		out.put("domainId", domainId);
		out.put("created", created);
		out.put("url", this.getConfig().getBaseUrl().trim().replaceAll("/+$", "").replace(CollibraConnector.API, "") + "/asset/" + assetId);
		// attributes: payload.attributes.{key} and inputs attributes.{key}; a key is an attribute type name, or one of the configured aliases
		final Map<String, String> attributes = new LinkedHashMap<String, String>();
		final JsonNode payloadAttributes = payload.path("attributes");
		if (payloadAttributes.isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> fields = payloadAttributes.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> f = fields.next();
				if (!f.getValue().isNull() && !f.getValue().asText("").isEmpty()) attributes.put(f.getKey(), f.getValue().asText());
			}
		}
		for (final Map.Entry<String, String> e : pInputs.entrySet())
		{
			if (e.getKey().startsWith("attributes.") && e.getValue() != null && !e.getValue().trim().isEmpty()) attributes.put(e.getKey().substring("attributes.".length()), e.getValue());
		}
		int attributesSet = 0;
		if (!attributes.isEmpty())
		{
			final List<JsonNode> current = this.attributeRows(assetId);
			for (final Map.Entry<String, String> a : attributes.entrySet())
			{
				final String attributeType = this.attributeType(a.getKey());
				final String attributeTypeId = this.idOrNull("attributeType", attributeType, null);
				if (attributeTypeId == null)
				{
					if (pContext != null) pContext.getLog().info("[connectors] collibra: attribute type '" + attributeType + "' not found, skipped");
					continue;
				}
				JsonNode row = null;
				for (final JsonNode r : current) if (attributeTypeId.equals(r.path("type").path("id").asText())) row = r;
				if (row == null)
				{
					final ObjectNode body = Json.object();
					body.put("assetId", assetId);
					body.put("typeId", attributeTypeId);
					body.put("value", a.getValue());
					this.body(this.send("POST", "/attributes", null, body), "create attribute");
					attributesSet++;
				}
				else if (!a.getValue().equals(row.path("value").asText("")))
				{
					this.body(this.send("PATCH", "/attributes/" + AbstractRestConnector.segment(row.path("id").asText()), null, Json.object().put("value", a.getValue())), "update attribute");
					attributesSet++;
				}
			}
		}
		out.put("attributesSet", attributesSet);
		// relations: parent (contains), term (represents), and an explicit list
		int relations = 0;
		if (parent != null)
		{
			final JsonNode parentAsset = this.findAssetInDomain(parent, domainId);
			if (parentAsset != null && this.relateIds(parentAsset.path("id").asText(), assetId, CollibraConnector.first(CollibraConnector.value(pInputs, payload, "parentRelation"), this.getConfig().getProperty("relation.contains", "Table>contains>Column")), pContext)) relations++;
			else if (parentAsset == null && pContext != null) pContext.getLog().info("[connectors] collibra: parent '" + parent + "' not found in domain " + domain + ", no relation");
		}
		final String term = CollibraConnector.value(pInputs, payload, "term");
		if (term != null)
		{
			final JsonNode termAsset = this.findAsset(term, CollibraConnector.value(pInputs, payload, "termDomain"), community, this.getConfig().getProperty("term.type", "Business Term"));
			if (termAsset != null && this.relateIds(assetId, termAsset.path("id").asText(), CollibraConnector.first(CollibraConnector.value(pInputs, payload, "termRelation"), this.getConfig().getProperty("relation.term", "Column>represents>Business Term")), pContext)) relations++;
			else if (termAsset == null && pContext != null) pContext.getLog().info("[connectors] collibra: business term '" + term + "' not found, no relation");
		}
		for (final JsonNode r : payload.path("relations"))
		{
			final String targetName = r.path("target").asText("");
			if (targetName.isEmpty()) continue;
			final JsonNode target = this.findAsset(targetName, r.path("targetDomain").asText(domain), r.path("targetCommunity").asText(community), null);
			if (target == null) continue;
			final boolean incoming = "incoming".equalsIgnoreCase(r.path("direction").asText(""));
			if (this.relateIds(incoming ? target.path("id").asText() : assetId, incoming ? assetId : target.path("id").asText(), r.path("type").asText(""), pContext)) relations++;
		}
		out.put("relationsCreated", relations);
		return out;
	}

	JsonNode relate(final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		final JsonNode payload = pPayload == null ? Json.object() : pPayload;
		final String community = CollibraConnector.first(CollibraConnector.value(pInputs, payload, "community"), this.getConfig().getProperty("community", null));
		final String sourceId = this.endpoint(pInputs, payload, "source", community);
		final String targetId = this.endpoint(pInputs, payload, "target", community);
		final ObjectNode out = Json.object();
		if (sourceId == null || targetId == null)
		{
			out.put("created", false);
			out.put("skipped", sourceId == null ? "source not found" : "target not found");
			return out;
		}
		final boolean columnLevel = CollibraConnector.value(pInputs, payload, "source") != null && CollibraConnector.value(pInputs, payload, "sourceParent") != null
			|| CollibraConnector.value(pInputs, payload, "target") != null && CollibraConnector.value(pInputs, payload, "targetParent") != null;
		final String type = CollibraConnector.first(CollibraConnector.value(pInputs, payload, "type"),
			this.getConfig().getProperty(columnLevel ? "relation.lineage" : "relation.lineageTable", columnLevel ? "Column>is source for>Column" : "Table>is source for>Table"));
		out.put("sourceId", sourceId);
		out.put("targetId", targetId);
		out.put("created", this.relateIds(sourceId, targetId, type, null));
		return out;
	}

	/** One end of a relation: an id, or a name qualified by its parent (column) inside a domain. Null when nothing matches. */
	private String endpoint(final Map<String, String> pInputs, final JsonNode pPayload, final String pSide, final String pCommunity) throws ConnectorException
	{
		final String id = CollibraConnector.value(pInputs, pPayload, pSide + "Id");
		if (id != null) return id;
		final String parent = CollibraConnector.value(pInputs, pPayload, pSide + "Parent");
		String name = CollibraConnector.value(pInputs, pPayload, pSide);
		if (name == null && parent == null) throw new ConnectorException("Collibra relate: '" + pSide + "Id', or '" + pSide + "' / '" + pSide + "Parent', is required");
		if (name == null) name = parent;
		else if (parent != null && !name.startsWith(parent + " > ")) name = parent + " > " + name;
		final JsonNode asset = this.findAsset(name, CollibraConnector.value(pInputs, pPayload, pSide + "Domain"), pCommunity, null);
		return asset == null ? null : asset.path("id").asText();
	}

	JsonNode importJob(final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		if (pPayload == null || !pPayload.isArray()) throw new ConnectorException("Collibra import: the payload must be an array of import commands");
		final MultipartBody multipart = new MultipartBody();
		multipart.file("file", "import.json", "application/json", Json.write(pPayload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		multipart.field("sendNotification", "false");
		if (pInputs.get("simulation") != null) multipart.field("simulation", pInputs.get("simulation"));
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", multipart.contentType());
		final JsonNode job = this.body(this.sendBytes("POST", "/import/json-job", null, multipart.finish(), headers), "import job");
		final ObjectNode out = Json.object();
		out.put("jobId", job.path("id").asText(""));
		out.put("state", job.path("state").asText(""));
		if (Boolean.parseBoolean(pInputs.get("wait")) && !job.path("id").asText("").isEmpty())
		{
			final long deadline = System.currentTimeMillis() + 1000L * CollibraConnector.bounded(pInputs.get("timeout"), "120");
			JsonNode status = job;
			while (System.currentTimeMillis() < deadline)
			{
				final String state = status.path("state").asText("");
				if ("COMPLETED".equals(state) || "ERROR".equals(state) || "CANCELED".equals(state)) break;
				try { Thread.sleep(1000L); }
				catch (final InterruptedException ex)
				{
					Thread.currentThread().interrupt();
					break;
				}
				status = this.body(this.get("/jobs/" + AbstractRestConnector.segment(job.path("id").asText()), null), "job status");
			}
			out.put("state", status.path("state").asText(""));
			out.put("message", status.path("message").asText(""));
			if (status.has("result")) out.set("result", status.get("result"));
			if ("ERROR".equals(status.path("state").asText(""))) throw new ConnectorException("Collibra import job " + job.path("id").asText() + " failed: " + status.path("message").asText(""));
		}
		return out;
	}

	// ------------------------------------------------------------------ lookups

	Map<String, String> assetQuery(final Map<String, String> pInputs, final String pType) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if (pInputs.get("name") != null && !pInputs.get("name").trim().isEmpty())
		{
			query.put("name", pInputs.get("name").trim());
			query.put("nameMatchMode", pInputs.get("match") == null ? "ANYWHERE" : pInputs.get("match").trim().toUpperCase(Locale.ENGLISH));
		}
		if (pType != null && !pType.trim().isEmpty()) query.put("typeIds", this.id("assetType", pType, null));
		final String community = CollibraConnector.first(pInputs.get("community"), null);
		if (pInputs.get("domain") != null && !pInputs.get("domain").trim().isEmpty()) query.put("domainId", this.domainId(pInputs.get("domain"), CollibraConnector.first(community, this.getConfig().getProperty("community", null)), null, false));
		else if (community != null) query.put("communityId", this.id("community", community, null));
		if (pInputs.get("status") != null && !pInputs.get("status").trim().isEmpty()) query.put("statusIds", this.id("status", pInputs.get("status"), null));
		return query;
	}

	/** Pages through a list endpoint ({@code results[]}, {@code offset} / {@code limit}). */
	List<JsonNode> page(final String pPath, final Map<String, String> pQuery, final int pMaximum) throws ConnectorException
	{
		final List<JsonNode> all = new ArrayList<JsonNode>();
		int offset = 0;
		while (all.size() < pMaximum)
		{
			final Map<String, String> query = new LinkedHashMap<String, String>(pQuery);
			final int limit = Math.min(1000, pMaximum - all.size());
			query.put("offset", String.valueOf(offset));
			query.put("limit", String.valueOf(limit));
			final JsonNode body = this.body(this.get(pPath, query), "GET " + pPath);
			final JsonNode results = body.path("results");
			if (!results.isArray() || results.size() == 0) break;
			for (final JsonNode r : results) if (all.size() < pMaximum) all.add(r);
			if (results.size() < limit) break;
			offset += results.size();
		}
		return all;
	}

	JsonNode findAsset(final String pName, final String pDomain, final String pCommunity, final String pType) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("name", pName);
		query.put("nameMatchMode", "EXACT");
		if (pDomain != null && !pDomain.trim().isEmpty())
		{
			final String domainId = this.domainId(pDomain, CollibraConnector.first(pCommunity, this.getConfig().getProperty("community", null)), null, false);
			if (domainId == null) return null;
			query.put("domainId", domainId);
		}
		else if (pCommunity != null && !pCommunity.trim().isEmpty())
		{
			final String communityId = this.idOrNull("community", pCommunity, null);
			if (communityId != null) query.put("communityId", communityId);
		}
		if (pType != null) query.put("typeIds", this.id("assetType", pType, null));
		final List<JsonNode> found = this.page("/assets", query, 2);
		return found.isEmpty() ? null : found.get(0);
	}

	JsonNode findAssetInDomain(final String pName, final String pDomainId) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("name", pName);
		query.put("nameMatchMode", "EXACT");
		query.put("domainId", pDomainId);
		final List<JsonNode> found = this.page("/assets", query, 2);
		return found.isEmpty() ? null : found.get(0);
	}

	/** Domain id by name inside a community; created when {@code pCreate} and missing. */
	String domainId(final String pDomain, final String pCommunity, final String pDomainType, final boolean pCreate) throws ConnectorException
	{
		final String communityId = pCommunity == null || pCommunity.trim().isEmpty() ? null : this.id("community", pCommunity, null);
		final String cached = this.idOrNull("domain", pDomain, communityId);
		if (cached != null) return cached;
		if (!pCreate) return null;
		if (communityId == null) throw new ConnectorException("Collibra: domain '" + pDomain + "' not found and no community to create it in (property community)");
		final ObjectNode body = Json.object();
		body.put("name", pDomain.trim());
		body.put("communityId", communityId);
		body.put("typeId", this.id("domainType", pDomainType == null ? "Physical Data Dictionary" : pDomainType, null));
		final JsonNode created = this.body(this.send("POST", "/domains", null, body), "create domain");
		this.ids.put("domain|" + communityId + "|" + pDomain.trim().toLowerCase(Locale.ENGLISH), created.path("id").asText());
		return created.path("id").asText();
	}

	String id(final String pKind, final String pName, final String pScopeId) throws ConnectorException
	{
		final String id = this.idOrNull(pKind, pName, pScopeId);
		if (id == null) throw new ConnectorException("Collibra: " + pKind + " '" + pName + "' not found" + (pScopeId == null ? "" : " in " + pScopeId));
		return id;
	}

	/** Name → id for communities, domains, assetTypes, attributeTypes, statuses, domainTypes; cached. A UUID is returned as is. */
	String idOrNull(final String pKind, final String pName, final String pScopeId) throws ConnectorException
	{
		if (pName == null || pName.trim().isEmpty()) return null;
		final String name = pName.trim();
		if (name.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return name;
		final String key = pKind + "|" + pScopeId + "|" + name.toLowerCase(Locale.ENGLISH);
		if (this.ids.containsKey(key)) return this.ids.get(key);
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("name", name);
		query.put("nameMatchMode", "EXACT");
		if ("domain".equals(pKind) && pScopeId != null) query.put("communityId", pScopeId);
		final String path = "community".equals(pKind) ? "/communities" : "domain".equals(pKind) ? "/domains" : "assetType".equals(pKind) ? "/assetTypes"
			: "attributeType".equals(pKind) ? "/attributeTypes" : "status".equals(pKind) ? "/statuses" : "domainType".equals(pKind) ? "/domainTypes" : "/" + pKind;
		String id = null;
		for (final JsonNode r : this.page(path, query, 50))
		{
			if (name.equalsIgnoreCase(r.path("name").asText("")))
			{
				id = r.path("id").asText();
				break;
			}
		}
		if (id != null) this.ids.put(key, id);
		return id;
	}

	/** Relation type id from {@code Source type>role>Target type} (exact, then role + target, then role alone) or a UUID. */
	String relationTypeId(final String pSpec, final ConnectorContext pContext) throws ConnectorException
	{
		if (pSpec == null || pSpec.trim().isEmpty()) throw new ConnectorException("Collibra: relation type not set");
		final String cached = this.ids.get("relationType||" + pSpec.trim().toLowerCase(Locale.ENGLISH));
		if (cached != null) return cached;
		if (pSpec.trim().matches("[0-9a-fA-F-]{36}")) return pSpec.trim();
		final String[] parts = pSpec.split(">");
		final String source = parts.length > 0 ? parts[0].trim() : "";
		final String role = parts.length > 1 ? parts[1].trim() : parts[0].trim();
		final String target = parts.length > 2 ? parts[2].trim() : "";
		if (this.relationTypes == null)
		{
			final ArrayNode all = Json.mapper().createArrayNode();
			for (final JsonNode r : this.page("/relationTypes", new LinkedHashMap<String, String>(), 5000)) all.add(r);
			this.relationTypes = all;
		}
		String exact = null;
		String byTarget = null;
		String byRole = null;
		for (final JsonNode r : this.relationTypes)
		{
			final boolean roleMatch = role.equalsIgnoreCase(r.path("role").asText("")) || role.equalsIgnoreCase(r.path("coRole").asText(""));
			if (!roleMatch) continue;
			final boolean sourceMatch = source.equalsIgnoreCase(r.path("sourceType").path("name").asText(""));
			final boolean targetMatch = target.equalsIgnoreCase(r.path("targetType").path("name").asText(""));
			if (sourceMatch && targetMatch && exact == null) exact = r.path("id").asText();
			else if (targetMatch && byTarget == null) byTarget = r.path("id").asText();
			else if (byRole == null) byRole = r.path("id").asText();
		}
		final String id = exact != null ? exact : byTarget != null ? byTarget : byRole;
		if (id == null) throw new ConnectorException("Collibra: no relation type matches '" + pSpec + "' (see operation types, kind=relationTypes)");
		if (exact == null && pContext != null) pContext.getLog().info("[connectors] collibra: relation type '" + pSpec + "' matched approximately (" + id + ")");
		this.ids.put("relationType||" + pSpec.trim().toLowerCase(Locale.ENGLISH), id);
		return id;
	}

	/** Creates the relation source → target of the given type unless it exists; true when created. */
	boolean relateIds(final String pSourceId, final String pTargetId, final String pType, final ConnectorContext pContext) throws ConnectorException
	{
		final String typeId = this.relationTypeId(pType, pContext);
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("sourceId", pSourceId);
		query.put("targetId", pTargetId);
		query.put("relationTypeId", typeId);
		if (!this.page("/relations", query, 1).isEmpty()) return false;
		final ObjectNode body = Json.object();
		body.put("sourceId", pSourceId);
		body.put("targetId", pTargetId);
		body.put("typeId", typeId);
		this.body(this.send("POST", "/relations", null, body), "create relation");
		return true;
	}

	/** Attribute type name for a payload key: property {@code attribute.<key>}, else the built-in alias, else the key itself. */
	String attributeType(final String pKey)
	{
		final String configured = this.getConfig().getProperty("attribute." + pKey, null);
		if (configured != null && !configured.trim().isEmpty()) return configured.trim();
		if ("description".equals(pKey)) return "Description";
		if ("definition".equals(pKey)) return "Definition";
		if ("dataType".equals(pKey)) return "Technical Data Type";
		if ("note".equals(pKey)) return "Note";
		if ("url".equals(pKey)) return "URL";
		return pKey;
	}

	/** Attributes of an asset as {@code {typeName: value}} (the first value of a repeated attribute). */
	ObjectNode attributes(final String pAssetId) throws ConnectorException
	{
		final ObjectNode out = Json.object();
		for (final JsonNode a : this.attributeRows(pAssetId))
		{
			final String type = a.path("type").path("name").asText("");
			if (!type.isEmpty() && !out.has(type)) out.put(type, a.path("value").asText(""));
		}
		return out;
	}

	List<JsonNode> attributeRows(final String pAssetId) throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		query.put("assetId", pAssetId);
		return this.page("/attributes", query, 1000);
	}

	ObjectNode flat(final JsonNode pAsset)
	{
		final ObjectNode item = Json.object();
		item.put("id", pAsset.path("id").asText());
		item.put("name", pAsset.path("name").asText());
		item.put("displayName", pAsset.path("displayName").asText(pAsset.path("name").asText()));
		item.put("type", pAsset.path("type").path("name").asText());
		item.put("domain", pAsset.path("domain").path("name").asText());
		item.put("community", pAsset.path("domain").path("community").path("name").asText(""));
		item.put("status", pAsset.path("status").path("name").asText());
		item.put("createdOn", pAsset.path("createdOn").asText(""));
		item.put("lastModifiedOn", pAsset.path("lastModifiedOn").asText(""));
		final String base = this.getConfig().getBaseUrl() == null ? "" : this.getConfig().getBaseUrl().trim().replaceAll("/+$", "").replace(CollibraConnector.API, "");
		item.put("url", base + "/asset/" + pAsset.path("id").asText());
		return item;
	}

	private ObjectNode flatRelation(final JsonNode pRelation)
	{
		final ObjectNode r = Json.object();
		r.put("id", pRelation.path("id").asText());
		r.put("typeId", pRelation.path("type").path("id").asText());
		r.put("source", pRelation.path("source").path("name").asText());
		r.put("sourceId", pRelation.path("source").path("id").asText());
		r.put("target", pRelation.path("target").path("name").asText());
		r.put("targetId", pRelation.path("target").path("id").asText());
		return r;
	}

	// ------------------------------------------------------------------ helpers

	private JsonNode body(final HttpJsonClient.Response pResponse, final String pWhat) throws ConnectorException
	{
		if (pResponse.isSuccess()) return pResponse.getBody() == null ? Json.object() : pResponse.getBody();
		String detail = pResponse.getRawBody();
		if (detail != null && detail.length() > 300) detail = detail.substring(0, 300) + "...";
		throw new ConnectorException(this.getConfig().getId() + ": " + pWhat + " -> HTTP " + pResponse.getStatus() + (detail == null || detail.isEmpty() ? "" : " " + detail), pResponse.getStatus(), null);
	}

	/** An input, else a top-level payload field; null when both are blank. */
	static String value(final Map<String, String> pInputs, final JsonNode pPayload, final String pName)
	{
		final String input = pInputs.get(pName);
		if (input != null && !input.trim().isEmpty()) return input.trim();
		final JsonNode field = pPayload.path(pName);
		return field.isMissingNode() || field.isNull() || field.asText("").trim().isEmpty() ? null : field.asText().trim();
	}

	static String first(final String pValue, final String pFallback)
	{
		return pValue != null && !pValue.trim().isEmpty() ? pValue.trim() : pFallback == null || pFallback.trim().isEmpty() ? null : pFallback.trim();
	}

	private static int bounded(final String pValue, final String pDefault)
	{
		try { return Math.max(1, Math.min(100000, Integer.parseInt((pValue == null || pValue.trim().isEmpty() ? pDefault : pValue).trim()))); }
		catch (final RuntimeException ex) { return 1000; }
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		if ("terms".equals(pOperation) || "search".equals(pOperation))
		{
			for (final String[] f : new String[][] { { "items[0].id", "Collibra id", "string" }, { "items[0].code", "Code (= id)", "string" }, { "items[0].name", "Name", "string" }, { "items[0].displayName", "Display name", "string" },
				{ "items[0].type", "Asset type", "string" }, { "items[0].domain", "Domain", "string" }, { "items[0].community", "Community", "string" }, { "items[0].status", "Status", "string" },
				{ "items[0].definition", "Definition", "string" }, { "items[0].description", "Description", "string" }, { "items[0].note", "Note", "string" }, { "items[0].url", "URL", "string" }, { "items[0].lastModifiedOn", "Last modified", "string" } })
				if (!"search".equals(pOperation) || !f[0].contains("definition") && !f[0].contains("description") && !f[0].contains("note") && !f[0].contains("code")) fields.add(FieldInfo.of(f[0], f[1], f[2]));
		}
		else if ("upsert".equals(pOperation))
		{
			for (final String[] f : new String[][] { { "name", "Asset name (unique in the domain)", "string" }, { "displayName", "Display name", "string" }, { "type", "Asset type", "string" }, { "domain", "Domain (created on demand)", "string" },
				{ "community", "Community", "string" }, { "status", "Status", "string" }, { "parent", "Parent asset name (contains relation; the name is qualified as parent > name)", "string" }, { "term", "Business term name (represents relation)", "string" },
				{ "attributes.description", "Description", "string" }, { "attributes.dataType", "Technical data type", "string" }, { "attributes.note", "Note", "string" }, { "attributes.url", "URL", "string" } })
				fields.add(FieldInfo.of(f[0], f[1], f[2]));
		}
		else if ("relate".equals(pOperation))
		{
			for (final String[] f : new String[][] { { "source", "Source name (column)", "string" }, { "sourceParent", "Source parent (table)", "string" }, { "sourceDomain", "Source domain", "string" },
				{ "target", "Target name", "string" }, { "targetParent", "Target parent", "string" }, { "targetDomain", "Target domain", "string" }, { "type", "Relation type (Source>role>Target)", "string" } })
				fields.add(FieldInfo.of(f[0], f[1], f[2]));
		}
		return fields;
	}
}
