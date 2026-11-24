package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.RulePolicies;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Export and import of the configuration (connectors, their properties, mappings with rules,
 * inputs and sub-object mappings) as one JSON document, for transport between environments.
 * <p>
 * Secrets are never exported: the document says whether they are set ({@code secrets.apiKey = true})
 * and properties whose name looks secret are exported as {@code TO_BE_SET}. On import, connectors
 * are matched by id, properties by connector and key, mappings by connector, operation, direction,
 * table and label (ids are auto-incremented and differ between environments), sub-object mappings
 * by parent and label. Existing secrets are never overwritten; the rules and inputs of an imported
 * mapping replace the existing ones.
 */
public final class ConfigTransfer
{
	private static final String[] CONNECTOR_FIELDS = { "label", "type", "baseUrl", "authType", "username", "apiKeyHeader", "connectTimeoutMs", "readTimeoutMs",
			"active", "trust", "pricing", "pricingNote", "logo", "description" };
	private static final String[] MAPPING_FIELDS = { "connector", "operation", "direction", "label", "description", "dataspace", "dataset", "tablePath",
			"responseRoot", "active", "collectionPath", "parentLinkField", "childKeyFields", "childFilter", "deleteMissing", "itemsPath", "keyPredicate",
			"createIfMissing", "loadMode", "checkpointStrategy", "checkpointInput", "checkpointValuePath", "checkpointInitialValue",
			"deleteOperation", "maxAttempts", "retryDelayMinutes",
			"provenance", "unchangedIfEqual", "checkpointBoundary", "batchSize", "logEveryRecord" };

	/**
	 * The fields of the Mapping table that are deliberately not in {@link #MAPPING_FIELDS}, each with
	 * the reason. {@code ConfigTransferFieldsTest} fails when a model field is in neither list, so a
	 * setting added to the model cannot be silently dropped by an export or an upsert.
	 */
	private static final Map<String, String> MAPPING_FIELDS_NOT_TRANSFERRED = ConfigTransfer.notTransferred();
	private static final String[] RULE_FIELDS = { "position", "sourcePath", "targetPath", "transformation", "policy", "active", "comment" };

	private ConfigTransfer()
	{
	}

	private static Map<String, String> notTransferred()
	{
		final Map<String, String> out = new java.util.LinkedHashMap<String, String>();
		out.put("id", "auto-increment key, differs between environments: carried as the document's 'id' and never written");
		out.put("parent", "set from the nesting of the document: a sub-object mapping sits in its parent's 'children'");
		out.put("inputs", "association to MappingInput: carried as the document's 'inputs' object");
		out.put("children", "association to the sub-object mappings: carried as the document's 'children' array");
		out.put("rules", "association to MappingRule: carried as the document's 'rules' array");
		return java.util.Collections.unmodifiableMap(out);
	}

	/** The Mapping fields copied one by one by an export and written by an import or an upsert. */
	public static java.util.List<String> mappingFields()
	{
		return java.util.Collections.unmodifiableList(java.util.Arrays.asList(ConfigTransfer.MAPPING_FIELDS));
	}

	/** The MappingRule fields an export copies and an import or an upsert writes. */
	public static java.util.List<String> ruleFields()
	{
		return java.util.Collections.unmodifiableList(java.util.Arrays.asList(ConfigTransfer.RULE_FIELDS));
	}

	/**
	 * One root mapping as {@link #export} writes it (fields, inputs, rules, sub-object mappings), the document an upsert
	 * accepts back; null when there is no root mapping of that id. Used by the in-JVM contract's {@code flows.get}.
	 */
	public static ObjectNode exportMapping(final Repository pRepository, final String pMappingId)
	{
		final Adaptation dataset = ConnectorService.get().repository(pRepository).dataset();
		if (dataset == null || pMappingId == null || pMappingId.trim().isEmpty())
		{
			return null;
		}
		final Adaptation row = dataset.getTable(ConnectorsPaths.MAPPING).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pMappingId.trim()));
		if (row == null)
		{
			return null;
		}
		return ConfigTransfer.exportMapping(dataset, row);
	}

	/** The Mapping fields that are carried another way or not at all, with the reason. */
	static Map<String, String> mappingFieldsNotTransferred()
	{
		return ConfigTransfer.MAPPING_FIELDS_NOT_TRANSFERRED;
	}

	// ------------------------------------------------------------------ export

	public static ObjectNode export(final Repository pRepository, final String pConnector, final boolean pMappings)
	{
		final Adaptation dataset = ConnectorService.get().repository(pRepository).dataset();
		final ObjectNode out = Json.object();
		out.put("format", "ebx-connectors-config");
		out.put("version", 1);
		if (dataset == null)
		{
			return out;
		}
		final ArrayNode connectors = out.putArray("connectors");
		for (final Adaptation row : ConfigTransfer.rows(dataset.getTable(ConnectorsPaths.CONNECTOR), null))
		{
			final String id = row.getString(ConnectorsPaths.Connector.ID);
			if (pConnector != null && !pConnector.trim().isEmpty() && !pConnector.trim().equals(id))
			{
				continue;
			}
			final ObjectNode c = connectors.addObject();
			c.put("id", id);
			ConfigTransfer.copy(row, c, ConfigTransfer.CONNECTOR_FIELDS);
			// the URL of a chat webhook is its credential (C2-02): exported like the other secrets, as the placeholder an import expects
			if (c.has("baseUrl") && ConfigTransfer.exportedBaseUrl(c.path("type").asText(), c.path("baseUrl").asText()) != null)
			{
				c.put("baseUrl", "TO_BE_SET");
			}
			final ObjectNode secrets = c.putObject("secrets");
			secrets.put("apiKey", !ConfigTransfer.placeholder(row.getString(ConnectorsPaths.Connector.API_KEY)));
			secrets.put("password", !ConfigTransfer.placeholder(row.getString(ConnectorsPaths.Connector.PASSWORD)));
			final ObjectNode properties = c.putObject("properties");
			for (final Adaptation property : ConfigTransfer.rows(dataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY), "./connector=" + ConfigTransfer.quote(id)))
			{
				final String key = property.getString(ConnectorsPaths.ConnectorProperty.KEY);
				final String value = property.getString(ConnectorsPaths.ConnectorProperty.VALUE);
				properties.put(key, com.on.ebx.connect.core.Redaction.isSecretKey(key) && !ConfigTransfer.placeholder(value)
					&& !ConfigTransfer.isReference(value) ? "TO_BE_SET" : value);
			}
		}
		if (pMappings)
		{
			final ArrayNode mappings = out.putArray("mappings");
			for (final Adaptation row : ConfigTransfer.rows(dataset.getTable(ConnectorsPaths.MAPPING), "osd:is-null(./parent)"))
			{
				if (pConnector != null && !pConnector.trim().isEmpty() && !pConnector.trim().equals(row.getString(ConnectorsPaths.Mapping.CONNECTOR)))
				{
					continue;
				}
				mappings.add(ConfigTransfer.exportMapping(dataset, row));
			}
		}
		return out;
	}

	private static ObjectNode exportMapping(final Adaptation pDataset, final Adaptation pRow)
	{
		final ObjectNode m = Json.object();
		final String id = pRow.getOccurrencePrimaryKey().format();
		m.put("id", id);
		ConfigTransfer.copy(pRow, m, ConfigTransfer.MAPPING_FIELDS);
		final ObjectNode inputs = m.putObject("inputs");
		for (final Adaptation input : ConfigTransfer.rows(pDataset.getTable(ConnectorsPaths.MAPPING_INPUT), "./mapping=" + ConfigTransfer.quote(id)))
		{
			inputs.put(input.getString(ConnectorsPaths.MappingInput.NAME), input.getString(ConnectorsPaths.MappingInput.EXPRESSION));
		}
		final ArrayNode rules = m.putArray("rules");
		for (final Adaptation rule : ConfigTransfer.rows(pDataset.getTable(ConnectorsPaths.MAPPING_RULE), "./mapping=" + ConfigTransfer.quote(id), ConnectorsPaths.MappingRule.POSITION))
		{
			ConfigTransfer.copy(rule, rules.addObject(), ConfigTransfer.RULE_FIELDS);
		}
		final ArrayNode children = m.putArray("children");
		for (final Adaptation child : ConfigTransfer.rows(pDataset.getTable(ConnectorsPaths.MAPPING), "./parent=" + ConfigTransfer.quote(id)))
		{
			children.add(ConfigTransfer.exportMapping(pDataset, child));
		}
		return m;
	}

	// ------------------------------------------------------------------ import

	/** Applies a document produced by {@link #export}; the report counts what was created, updated and skipped. */
	public static ObjectNode importConfig(final Session pSession, final Repository pRepository, final JsonNode pDocument, final boolean pDryRun)
	{
		final Adaptation dataset = ConnectorService.get().repository(pRepository).dataset();
		final ObjectNode report = Json.object();
		report.put("dryRun", pDryRun);
		if (dataset == null)
		{
			report.put("error", "the Connectors dataset is not available");
			return report;
		}
		if (pDocument == null || !"ebx-connectors-config".equals(Json.at(pDocument, "format") == null ? "" : Json.at(pDocument, "format").asText()))
		{
			report.put("error", "not an ebx-connectors-config document (missing \"format\": \"ebx-connectors-config\")");
			return report;
		}
		final JsonNode documentMappings = Json.at(pDocument, "mappings");
		if (documentMappings != null && documentMappings.isArray())
		{
			// audit C2-04 / C2-05: nothing is written when one mapping holds a template that could carry a value out of its place
			for (final JsonNode m : documentMappings)
			{
				final String refusal = ConfigTransfer.templateRefusal(pRepository, m);
				if (refusal != null)
				{
					report.put("error", refusal);
					return report;
				}
			}
		}
		final int[] counts = new int[6]; // connectors created/updated, properties written, mappings created/updated, rules written
		final RulePolicies.Tally policies = new RulePolicies.Tally();
		// written without triggers: remember the connectors as they are, to send the declared lineage the triggers would have sent
		final java.util.Map<String, String> before = pDryRun ? null : ConfigTransfer.connectorFingerprints(pRepository, pDocument);
		final ArrayNode notes = report.putArray("notes");
		final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
		final com.orchestranetworks.service.ProcedureResult result = service.execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setTriggerActivation(false);
				final JsonNode connectors = Json.at(pDocument, "connectors");
				if (connectors != null && connectors.isArray())
				{
					for (final JsonNode c : connectors)
					{
						ConfigTransfer.importConnector(pContext, dataset, c, counts, notes);
					}
				}
				final JsonNode mappings = Json.at(pDocument, "mappings");
				if (mappings != null && mappings.isArray())
				{
					for (final JsonNode m : mappings)
					{
						ConfigTransfer.importMapping(pContext, dataset, m, null, counts, notes, policies);
					}
				}
				if (pDryRun)
				{
					throw new RollbackException();
				}
			}
		});
		if (result.hasFailed() && !(result.getException() != null && result.getException().getCause() instanceof RollbackException))
		{
			report.put("error", String.valueOf(result.getExceptionFullMessage(java.util.Locale.ENGLISH)));
		}
		com.on.ebx.connect.core.ConnectorRegistry.get().invalidate();
		if (before != null && !report.has("error"))
		{
			ConfigTransfer.redeclareImported(pRepository, pDocument, before);
		}
		report.put("connectorsCreated", counts[0]);
		report.put("connectorsUpdated", counts[1]);
		report.put("propertiesWritten", counts[2]);
		report.put("mappingsCreated", counts[3]);
		report.put("mappingsUpdated", counts[4]);
		report.put("rulesWritten", counts[5]);
		ConfigTransfer.putPolicies(report, notes, policies);
		return report;
	}

	/** type | base URL | active of each connector a configuration document names, as it is now ("" when absent). */
	static java.util.Map<String, String> connectorFingerprints(final Repository pRepository, final JsonNode pDocument)
	{
		final java.util.Map<String, String> out = new java.util.LinkedHashMap<String, String>();
		final JsonNode connectors = pDocument == null ? null : Json.at(pDocument, "connectors");
		if (connectors == null || !connectors.isArray())
		{
			return out;
		}
		final com.on.ebx.connect.core.ConnectorRepository repo = ConnectorService.get().repository(pRepository);
		for (final JsonNode c : connectors)
		{
			final String id = c.path("id").asText("").trim();
			if (!id.isEmpty())
			{
				out.put(id, ConfigTransfer.fingerprint(repo, id));
			}
		}
		return out;
	}

	static String fingerprint(final com.on.ebx.connect.core.ConnectorRepository pRepo, final String pId)
	{
		try
		{
			final com.on.ebx.connect.spi.ConnectorConfig c = pRepo.connector(pId);
			return c == null ? "" : c.getType() + "|" + c.getBaseUrl() + "|" + c.isActive();
		}
		catch (final RuntimeException ex)
		{
			return "";
		}
	}

	/**
	 * After an import (written without triggers): the declared lineage of every connector whose type, base
	 * URL or activity changed, and of every connector whose mappings the document brought.
	 */
	static void redeclareImported(final Repository pRepository, final JsonNode pDocument, final java.util.Map<String, String> pBefore)
	{
		try
		{
			final com.on.ebx.connect.core.ConnectorRepository repo = ConnectorService.get().repository(pRepository);
			final java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
			for (final java.util.Map.Entry<String, String> e : pBefore.entrySet())
			{
				if (!e.getValue().equals(ConfigTransfer.fingerprint(repo, e.getKey())))
				{
					ids.add(e.getKey());
				}
			}
			final JsonNode mappings = Json.at(pDocument, "mappings");
			if (mappings != null && mappings.isArray())
			{
				for (final JsonNode m : mappings)
				{
					final String connector = m.path("connector").asText("").trim();
					if (!connector.isEmpty())
					{
						ids.add(connector);
					}
				}
			}
			for (final String id : ids)
			{
				com.on.ebx.connect.core.DeclaredLineage.connectorChanged(pRepository, id);
			}
		}
		catch (final RuntimeException ex)
		{
			ConnectorService.log().warn("[connectors] declared lineage not scheduled after the configuration import: " + ex);
		}
	}

	/**
	 * Creates or updates one mapping from a compact document: {@code connector}, {@code operation},
	 * {@code direction}, {@code dataset}, {@code tablePath}, {@code label} (the matching key with the
	 * first four, or {@code id} to update a precise mapping), optional fields, {@code inputs}
	 * ({name: expression}), {@code rules} ([{sourcePath, targetPath, transformation, policy}], replaced)
	 * and {@code children} (sub-object mappings, matched by label under the parent).
	 */
	public static ObjectNode upsertMapping(final Session pSession, final Repository pRepository, final JsonNode pMapping, final boolean pDryRun)
	{
		final Adaptation dataset = ConnectorService.get().repository(pRepository).dataset();
		final ObjectNode report = Json.object();
		report.put("dryRun", pDryRun);
		if (dataset == null)
		{
			report.put("error", "the Connectors dataset is not available");
			return report;
		}
		for (final String required : new String[] { "connector", "operation", "direction", "dataset", "tablePath", "label" })
		{
			if (pMapping == null || ConfigTransfer.text(pMapping, required) == null)
			{
				report.put("error", "mapping." + required + " is required (connector, operation, direction INBOUND|OUTBOUND, dataset, tablePath, label)");
				return report;
			}
		}
		if (ConnectorService.get().repository(pRepository).connector(ConfigTransfer.text(pMapping, "connector")) == null)
		{
			report.put("error", "no connector '" + ConfigTransfer.text(pMapping, "connector") + "'");
			return report;
		}
		final int[] counts = new int[6];
		final ArrayNode notes = report.putArray("notes");
		// a blank dataspace stays blank (a generic mapping); it is refused (dry run included) when several distinct dataspaces serve the
		// dataset, since a table-scoped run could not choose; a dataspace that does not exist is refused too
		final ObjectNode targeted = pMapping instanceof ObjectNode ? ((ObjectNode) pMapping).deepCopy() : null;
		if (targeted != null)
		{
			final String refusal = ConfigTransfer.resolveTargets(pRepository, dataset, targeted, null, notes);
			if (refusal != null)
			{
				report.put("error", refusal);
				return report;
			}
			final String stored = ConfigTransfer.text(targeted, "dataspace");
			if (stored != null && !stored.trim().isEmpty())
			{
				report.put("dataspace", stored.trim());
			}
		}
		final JsonNode document = ConfigTransfer.withFeed(targeted == null ? pMapping : targeted, notes);
		final String templateRefusal = ConfigTransfer.templateRefusal(pRepository, document);
		if (templateRefusal != null)
		{
			report.put("error", templateRefusal);
			return report;
		}
		final String previousLabel = ConfigTransfer.previousLabel(dataset, document);
		final String[] mappingId = new String[1];
		final RulePolicies.Tally policies = new RulePolicies.Tally();
		final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
		final com.orchestranetworks.service.ProcedureResult result = service.execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setTriggerActivation(false);
				mappingId[0] = ConfigTransfer.importMapping(pContext, dataset, document, null, counts, notes, policies);
				if (pDryRun)
				{
					throw new RollbackException();
				}
			}
		});
		if (result.hasFailed() && !(result.getException() != null && result.getException().getCause() instanceof RollbackException))
		{
			report.put("error", String.valueOf(result.getExceptionFullMessage(java.util.Locale.ENGLISH)));
			return report;
		}
		com.on.ebx.connect.core.ConnectorRegistry.get().invalidate();
		if (!pDryRun)
		{
			// written without triggers: schedule the declared lineage the Mapping triggers would have scheduled
			ConfigTransfer.lineage(pRepository, mappingId[0], previousLabel);
		}
		report.put("mapping", pDryRun ? null : mappingId[0]);
		report.put("created", counts[3] > 0);
		report.put("rulesWritten", counts[5]);
		report.put("childrenWritten", counts[3] + counts[4] - 1);
		ConfigTransfer.putPolicies(report, notes, policies);
		if (!pDryRun)
		{
			report.put("nextStep", "connectors_enrich or connectors_sync with mappings=" + mappingId[0] + " and dryRun=true to test it, connectors_mapping_get to review it.");
		}
		report.set("lineage", ConfigTransfer.lineageReport(pRepository, pDryRun ? null : mappingId[0], ConfigTransfer.text(pMapping, "connector"),
			pDryRun ? "not scheduled (dry run)" : "scheduled"));
		return report;
	}

	/** What the upsert decides about the dataspace of one mapping document: the key to store (null: leave the document as given), or a refusal, plus an optional warning. */
	static final class TargetDecision
	{
		final String dataspace;
		final String error;
		final String warning;

		TargetDecision(final String pDataspace, final String pError, final String pWarning)
		{
			this.dataspace = pDataspace;
			this.error = pError;
			this.warning = pWarning;
		}
	}

	/**
	 * Pure: the decision for the dataspace a mapping names ({@code pGiven}, blank when it names none) from what
	 * {@link DatasetLocator#locate} found. A blank dataspace stays BLANK (a generic mapping applies to a record wherever the
	 * dataset exists, workflow child dataspaces included): UNIQUE only adds a note saying which dataspace table-scoped runs will
	 * use; a {@code B}-prefixed dataspace is normalised (stored, said in the warning). AMBIGUOUS (only possible without a
	 * dataspace): refused, the candidates named. A dataspace that does not exist: refused. A dataset not (yet) there: stored as given, with a warning.
	 */
	static TargetDecision decideTarget(final String pGiven, final DatasetLocator.Verdict pVerdict, final boolean pDataspaceMissing, final List<String> pCandidates,
		final String pDataset, final String pTablePath)
	{
		final String given = pGiven == null ? "" : pGiven.trim();
		final String subject = "dataset '" + pDataset + "'" + (pTablePath == null || pTablePath.trim().isEmpty() ? "" : ", table " + pTablePath.trim());
		if (pVerdict == DatasetLocator.Verdict.UNIQUE && pCandidates != null && !pCandidates.isEmpty())
		{
			final String key = pCandidates.get(0);
			if (given.isEmpty())
			{
				return new TargetDecision(null, null, "dataspace not named: table-scoped runs will use " + key + " (the only open dataspace serving " + subject
					+ "); a record is matched wherever the dataset exists");
			}
			if (!given.equals(key))
			{
				return new TargetDecision(key, null, "dataspace: '" + given + "' is not a dataspace; '" + key + "' (" + subject + ") is stored instead");
			}
			return new TargetDecision(key, null, null);
		}
		if (pVerdict == DatasetLocator.Verdict.AMBIGUOUS)
		{
			return new TargetDecision(null, DatasetLocator.failureText(pVerdict, false, given, pDataset, pTablePath, pCandidates), null);
		}
		if (pDataspaceMissing)
		{
			return new TargetDecision(null, "Dataspace '" + given + "' does not exist: name an existing dataspace (a mapping must not point nowhere)", null);
		}
		if (given.isEmpty())
		{
			return new TargetDecision(null, null, "dataspace not named: " + subject + " is served by no open dataspace yet; the mapping is stored as given and a table-scoped run will fail until the dataset exists");
		}
		return new TargetDecision(null, null, "dataspace: " + subject + " is not (yet) in dataspace '" + given + "'; stored as given");
	}

	/**
	 * Fills or checks the dataspace of a mapping document and of its children (inherited the way the import does), in place:
	 * returns the refusal text, or null. On an update that does not mention the dataspace, the stored one is kept as is.
	 */
	private static String resolveTargets(final Repository pRepository, final Adaptation pConnectorsDataset, final ObjectNode pNode, final ObjectNode pParent,
		final ArrayNode pNotes)
	{
		final ObjectNode effective = pParent == null ? pNode : (ObjectNode) ConfigTransfer.inherited(pParent, pNode);
		final String dataset = ConfigTransfer.text(effective, "dataset");
		if (dataset != null && !dataset.trim().isEmpty())
		{
			String stored = null;
			if (pParent == null && ConfigTransfer.text(pNode, "dataspace") == null)
			{
				stored = ConfigTransfer.storedDataspace(pConnectorsDataset, pNode);
			}
			if (stored != null && !stored.trim().isEmpty())
			{
				effective.put("dataspace", stored.trim()); // the update keeps it; the children inherit it
			}
			else
			{
				final String table = ConfigTransfer.text(effective, "tablePath");
				final String given = ConfigTransfer.text(effective, "dataspace");
				DatasetLocator.Location location = DatasetLocator.locate(pRepository, given, dataset, table);
				if (location.isDataspaceMissing() && given != null && given.trim().length() > 1 && given.trim().charAt(0) == 'B')
				{
					final DatasetLocator.Location stripped = DatasetLocator.locate(pRepository, given.trim().substring(1), dataset, table);
					if (!stripped.isDataspaceMissing())
					{
						location = stripped;
					}
				}
				final TargetDecision decision = ConfigTransfer.decideTarget(given, location.getVerdict(), location.isDataspaceMissing(), location.getCandidates(), dataset, table);
				if (decision.error != null)
				{
					return decision.error;
				}
				if (decision.warning != null)
				{
					pNotes.add(decision.warning);
				}
				if (decision.dataspace != null)
				{
					pNode.put("dataspace", decision.dataspace);
					effective.put("dataspace", decision.dataspace);
				}
			}
		}
		final JsonNode children = pNode.get("children");
		if (children != null && children.isArray())
		{
			for (final JsonNode child : children)
			{
				if (child instanceof ObjectNode)
				{
					final String refusal = ConfigTransfer.resolveTargets(pRepository, pConnectorsDataset, (ObjectNode) child, effective, pNotes);
					if (refusal != null)
					{
						return refusal;
					}
				}
			}
		}
		return null;
	}

	/** The dataspace stored on the root mapping this document designates (natural key, or id with updateById); null when there is none or it is blank. */
	private static String storedDataspace(final Adaptation pConnectorsDataset, final JsonNode pRoot)
	{
		try
		{
			final List<Adaptation> found = ConfigTransfer.findRoot(pConnectorsDataset.getTable(ConnectorsPaths.MAPPING), pRoot);
			return found.isEmpty() ? null : found.get(0).getString(ConnectorsPaths.Mapping.DATASPACE);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static List<Adaptation> findRoot(final AdaptationTable pTable, final JsonNode pJson)
	{
		final String label = ConfigTransfer.text(pJson, "label");
		final StringBuilder filter = new StringBuilder();
		filter.append("osd:is-null(./parent) and ./connector=").append(ConfigTransfer.quote(ConfigTransfer.text(pJson, "connector")))
			.append(" and ./operation=").append(ConfigTransfer.quote(ConfigTransfer.text(pJson, "operation")))
			.append(" and ./direction=").append(ConfigTransfer.quote(ConfigTransfer.text(pJson, "direction")))
			.append(" and ./tablePath=").append(ConfigTransfer.quote(ConfigTransfer.text(pJson, "tablePath")))
			.append(" and ./label=").append(ConfigTransfer.quote(label == null ? "" : label));
		List<Adaptation> found = ConfigTransfer.rows(pTable, filter.toString());
		if (found.isEmpty() && ConfigTransfer.text(pJson, "id") != null && pJson.has("updateById"))
		{
			final Adaptation byId = pTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(ConfigTransfer.text(pJson, "id")));
			found = byId == null ? found : java.util.Collections.singletonList(byId);
		}
		return found;
	}

	/**
	 * The write policies an upsert or an import applied (audit C-35): {@code rulePolicies} {IF_EMPTY, ALWAYS, TRUSTED: count},
	 * {@code policyDefaulted} (rules sent without a policy, written IF_EMPTY) and, when that touched an inbound mapping, a note
	 * saying what IF_EMPTY means for a run after the source changed.
	 */
	private static void putPolicies(final ObjectNode pReport, final ArrayNode pNotes, final RulePolicies.Tally pPolicies)
	{
		final ObjectNode byPolicy = pReport.putObject("rulePolicies");
		for (final Map.Entry<String, Integer> entry : pPolicies.byPolicy().entrySet())
		{
			byPolicy.put(entry.getKey(), entry.getValue().intValue());
		}
		pReport.put("policyDefaulted", pPolicies.defaulted());
		final String note = pPolicies.note();
		if (note != null)
		{
			pNotes.add(note);
		}
	}

	/** Deletes a mapping with its rules, inputs and sub-object mappings. */
	public static ObjectNode deleteMapping(final Session pSession, final Repository pRepository, final String pMappingId)
	{
		final Adaptation dataset = ConnectorService.get().repository(pRepository).dataset();
		final ObjectNode report = Json.object();
		if (dataset == null || pMappingId == null)
		{
			report.put("error", "mapping id is required");
			return report;
		}
		final AdaptationTable table = dataset.getTable(ConnectorsPaths.MAPPING);
		final Adaptation mapping = table.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pMappingId.trim()));
		if (mapping == null)
		{
			report.put("error", "no mapping " + pMappingId);
			return report;
		}
		final int[] deleted = new int[1];
		final String label = mapping.getString(ConnectorsPaths.Mapping.LABEL);
		final String connectorId = mapping.getString(ConnectorsPaths.Mapping.CONNECTOR);
		final String parent = mapping.getSchemaNode().getNode(ConnectorsPaths.Mapping.PARENT) == null ? null : mapping.getString(ConnectorsPaths.Mapping.PARENT);
		final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
		final com.orchestranetworks.service.ProcedureResult result = service.execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setTriggerActivation(false);
				ConfigTransfer.deleteMappingRows(pContext, dataset, mapping, deleted);
			}
		});
		if (result.hasFailed())
		{
			report.put("error", String.valueOf(result.getExceptionFullMessage(java.util.Locale.ENGLISH)));
			return report;
		}
		com.on.ebx.connect.core.ConnectorRegistry.get().invalidate();
		// deleted without triggers: retire its job, and describe again the root it belonged to
		ConfigTransfer.lineage(pRepository, pMappingId.trim(), label == null ? "" : label);
		ConfigTransfer.lineage(pRepository, parent, null);
		report.put("mapping", pMappingId.trim());
		report.put("label", label);
		report.put("deletedRows", deleted[0]);
		report.put("note", "rules, inputs, sub-object mappings and outbound states deleted; provenance and conflict rows kept without their mapping reference");
		report.set("lineage", ConfigTransfer.lineageReport(pRepository, null, connectorId, "retirement scheduled"));
		return report;
	}

	private static void deleteMappingRows(final ProcedureContext pContext, final Adaptation pDataset, final Adaptation pMapping, final int[] pDeleted) throws Exception
	{
		pDeleted[0] += ConfigTransfer.cascade(pContext, pDataset, pMapping.getOccurrencePrimaryKey().format());
		pContext.doDelete(pMapping.getAdaptationName(), false);
		pDeleted[0]++;
	}

	/**
	 * Deletes everything attached to a mapping id, the mapping itself excepted: sub-object mappings
	 * (recursively, with their own attachments), rules, inputs, outbound states; the provenance and
	 * conflict rows that referenced the mapping keep their history but lose the reference. Returns
	 * the number of rows deleted. Used by the cascade trigger of the Mapping table and by the services.
	 */
	public static int cascade(final ProcedureContext pContext, final Adaptation pDataset, final String pMappingId) throws Exception
	{
		int deleted = 0;
		final String id = ConfigTransfer.quote(pMappingId);
		for (final Adaptation child : ConfigTransfer.rows(pDataset.getTable(ConnectorsPaths.MAPPING), "./parent=" + id))
		{
			deleted += ConfigTransfer.cascade(pContext, pDataset, child.getOccurrencePrimaryKey().format());
			pContext.doDelete(child.getAdaptationName(), false);
			deleted++;
		}
		for (final Path table : new Path[] { ConnectorsPaths.MAPPING_RULE, ConnectorsPaths.MAPPING_INPUT,
			ConnectorsPaths.SYNCHRONIZATION_CHECKPOINT, ConnectorsPaths.OUTBOUND_STATE })
		{
			if (pDataset.getSchemaNode().getNode(table) == null)
			{
				continue;
			}
			for (final Adaptation row : ConfigTransfer.rows(pDataset.getTable(table), "./mapping=" + id))
			{
				pContext.doDelete(row.getAdaptationName(), false);
				deleted++;
			}
		}
		for (final Path table : new Path[] { ConnectorsPaths.FIELD_PROVENANCE, ConnectorsPaths.CONFLICT })
		{
			if (pDataset.getSchemaNode().getNode(table) == null)
			{
				continue;
			}
			for (final Adaptation row : ConfigTransfer.rows(pDataset.getTable(table), "./mapping=" + id))
			{
				final ValueContextForUpdate update = pContext.getContext(row.getAdaptationName());
				update.setValue(null, Path.parse("./mapping"));
				pContext.doModifyContent(row, update);
			}
		}
		return deleted;
	}

	/** What a deletion would remove or unlink, for a confirmation screen: {rules, inputs, children, outboundStates, provenanceRefs, conflictRefs}. */
	public static int[] attachments(final Adaptation pDataset, final String pMappingId)
	{
		final int[] counts = new int[6];
		final String id = ConfigTransfer.quote(pMappingId);
		counts[0] = ConfigTransfer.rows(pDataset.getTable(ConnectorsPaths.MAPPING_RULE), "./mapping=" + id).size();
		counts[1] = ConfigTransfer.rows(pDataset.getTable(ConnectorsPaths.MAPPING_INPUT), "./mapping=" + id).size();
		for (final Adaptation child : ConfigTransfer.rows(pDataset.getTable(ConnectorsPaths.MAPPING), "./parent=" + id))
		{
			counts[2]++;
			final int[] sub = ConfigTransfer.attachments(pDataset, child.getOccurrencePrimaryKey().format());
			for (int i = 0; i < counts.length; i++)
			{
				counts[i] += sub[i];
			}
		}
		final Path[] tables = { ConnectorsPaths.OUTBOUND_STATE, ConnectorsPaths.FIELD_PROVENANCE, ConnectorsPaths.CONFLICT };
		for (int i = 0; i < tables.length; i++)
		{
			if (pDataset.getSchemaNode().getNode(tables[i]) != null)
			{
				counts[3 + i] += ConfigTransfer.rows(pDataset.getTable(tables[i]), "./mapping=" + id).size();
			}
		}
		return counts;
	}

	/** Thrown to roll the procedure back after a dry run. */
	private static final class RollbackException extends Exception
	{
		private static final long serialVersionUID = 1L;
	}

	private static void importConnector(final ProcedureContext pContext, final Adaptation pDataset, final JsonNode pJson, final int[] pCounts, final ArrayNode pNotes)
		throws Exception
	{
		final String id = ConfigTransfer.text(pJson, "id");
		if (id == null)
		{
			pNotes.add("connector without id skipped");
			return;
		}
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.CONNECTOR);
		Adaptation existing = table.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(id));
		if (existing == null)
		{
			final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
			row.setValue(id, ConnectorsPaths.Connector.ID);
			ConfigTransfer.apply(row, pJson, ConfigTransfer.CONNECTOR_FIELDS);
			row.setValue("TO_BE_SET", ConnectorsPaths.Connector.API_KEY);
			row.setValue("TO_BE_SET", ConnectorsPaths.Connector.PASSWORD);
			existing = pContext.doCreateOccurrence(row, table);
			pCounts[0]++;
		}
		else
		{
			final ValueContextForUpdate update = pContext.getContext(existing.getAdaptationName());
			// an exported webhook URL is the placeholder: the existing URL stays, an import never blanks it
			ConfigTransfer.apply(update, "TO_BE_SET".equals(ConfigTransfer.text(pJson, "baseUrl")) ? ConfigTransfer.withoutBaseUrl(pJson) : pJson, ConfigTransfer.CONNECTOR_FIELDS);
			pContext.doModifyContent(existing, update);
			pCounts[1]++;
		}
		final JsonNode properties = Json.at(pJson, "properties");
		if (properties != null && properties.isObject())
		{
			final AdaptationTable propertyTable = pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY);
			for (final Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> entry = it.next();
				final String value = entry.getValue().isNull() ? null : entry.getValue().asText();
				final List<Adaptation> found = ConfigTransfer.rows(propertyTable, "./connector=" + ConfigTransfer.quote(id) + " and ./key=" + ConfigTransfer.quote(entry.getKey()));
				if (found.isEmpty())
				{
					final ValueContextForUpdate row = pContext.getContextForNewOccurrence(propertyTable);
					row.setValue(id, ConnectorsPaths.ConnectorProperty.CONNECTOR);
					row.setValue(entry.getKey(), ConnectorsPaths.ConnectorProperty.KEY);
					row.setValue(value, ConnectorsPaths.ConnectorProperty.VALUE);
					pContext.doCreateOccurrence(row, propertyTable);
					pCounts[2]++;
				}
				else if (!(ConfigTransfer.placeholder(value) && !ConfigTransfer.placeholder(found.get(0).getString(ConnectorsPaths.ConnectorProperty.VALUE))))
				{
					// a placeholder never overwrites a value already set (secrets stay)
					final ValueContextForUpdate update = pContext.getContext(found.get(0).getAdaptationName());
					update.setValue(value, ConnectorsPaths.ConnectorProperty.VALUE);
					pContext.doModifyContent(found.get(0), update);
					pCounts[2]++;
				}
			}
		}
	}

	private static String importMapping(final ProcedureContext pContext, final Adaptation pDataset, final JsonNode pJson, final String pParentId, final int[] pCounts,
		final ArrayNode pNotes, final RulePolicies.Tally pPolicies) throws Exception
	{
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.MAPPING);
		List<Adaptation> found;
		if (pParentId == null)
		{
			found = ConfigTransfer.findRoot(table, pJson);
		}
		else
		{
			final String label = ConfigTransfer.text(pJson, "label");
			found = ConfigTransfer.rows(table, "./parent=" + ConfigTransfer.quote(pParentId) + " and ./label=" + ConfigTransfer.quote(label == null ? "" : label));
		}
		Adaptation mapping;
		if (found.isEmpty())
		{
			final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
			ConfigTransfer.apply(row, pJson, ConfigTransfer.MAPPING_FIELDS);
			if (pParentId != null)
			{
				row.setValue(pParentId, ConnectorsPaths.Mapping.PARENT);
			}
			mapping = pContext.doCreateOccurrence(row, table);
			pCounts[3]++;
		}
		else
		{
			mapping = found.get(0);
			final ValueContextForUpdate update = pContext.getContext(mapping.getAdaptationName());
			ConfigTransfer.apply(update, pJson, ConfigTransfer.MAPPING_FIELDS);
			pContext.doModifyContent(mapping, update);
			pCounts[4]++;
		}
		final String id = mapping.getOccurrencePrimaryKey().format();
		// rules and inputs: replaced
		final JsonNode rules = Json.at(pJson, "rules");
		if (rules != null && rules.isArray())
		{
			final AdaptationTable ruleTable = pDataset.getTable(ConnectorsPaths.MAPPING_RULE);
			for (final Adaptation old : ConfigTransfer.rows(ruleTable, "./mapping=" + ConfigTransfer.quote(id)))
			{
				pContext.doDelete(old.getAdaptationName(), false);
			}
			int position = 0;
			for (final JsonNode r : rules)
			{
				position += 10;
				final ValueContextForUpdate row = pContext.getContextForNewOccurrence(ruleTable);
				row.setValue(id, ConnectorsPaths.MappingRule.MAPPING);
				row.setValue(Integer.valueOf(position), ConnectorsPaths.MappingRule.POSITION);
				ConfigTransfer.apply(row, r, ConfigTransfer.RULE_FIELDS);
				// a rule sent without a policy is written IF_EMPTY explicitly, and the answer says how many were (audit C-35)
				final String policy = ConfigTransfer.text(r, "policy");
				if (RulePolicies.isMissing(policy))
				{
					row.setValue(RulePolicies.DEFAULT, ConnectorsPaths.MappingRule.POLICY);
				}
				pPolicies.add(policy, ConfigTransfer.text(pJson, "direction"));
				pContext.doCreateOccurrence(row, ruleTable);
				pCounts[5]++;
			}
		}
		final JsonNode inputs = Json.at(pJson, "inputs");
		if (inputs != null && inputs.isObject())
		{
			final AdaptationTable inputTable = pDataset.getTable(ConnectorsPaths.MAPPING_INPUT);
			for (final Adaptation old : ConfigTransfer.rows(inputTable, "./mapping=" + ConfigTransfer.quote(id)))
			{
				pContext.doDelete(old.getAdaptationName(), false);
			}
			for (final Iterator<Map.Entry<String, JsonNode>> it = inputs.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> entry = it.next();
				final ValueContextForUpdate row = pContext.getContextForNewOccurrence(inputTable);
				row.setValue(id, ConnectorsPaths.MappingInput.MAPPING);
				row.setValue(entry.getKey(), ConnectorsPaths.MappingInput.NAME);
				row.setValue(entry.getValue().isNull() ? null : entry.getValue().asText(), ConnectorsPaths.MappingInput.EXPRESSION);
				pContext.doCreateOccurrence(row, inputTable);
			}
		}
		final JsonNode children = Json.at(pJson, "children");
		if (children != null && children.isArray())
		{
			for (final JsonNode child : children)
			{
				ConfigTransfer.importMapping(pContext, pDataset, ConfigTransfer.inherited(pJson, child), id, pCounts, pNotes, pPolicies);
			}
		}
		return id;
	}

	// ------------------------------------------------------------------ helpers

	/** What a sub-object mapping written without them takes from its parent. */
	private static final String[] INHERITED_FIELDS = { "connector", "operation", "direction", "dataspace", "dataset" };

	/**
	 * A sub-object mapping document completed with the connector, operation, direction, dataspace and
	 * dataset of its parent: without them the row was stored with no connector and read back INBOUND.
	 */
	static JsonNode inherited(final JsonNode pParent, final JsonNode pChild)
	{
		if (!(pChild instanceof ObjectNode) || pParent == null)
		{
			return pChild;
		}
		final ObjectNode out = ((ObjectNode) pChild).deepCopy();
		for (final String field : ConfigTransfer.INHERITED_FIELDS)
		{
			final JsonNode parent = pParent.get(field);
			final String own = ConfigTransfer.text(out, field);
			if ((own == null || own.trim().isEmpty()) && parent != null && !parent.isNull())
			{
				out.set(field, parent);
			}
		}
		return out;
	}

	/**
	 * Audit C2-04 / C2-05: why a mapping document (and its sub-object mappings) cannot be stored, or null. A key predicate must hold
	 * every placeholder between apostrophes; an {@code sql} input must not hold a quoted run that is never closed.
	 * {@code pConnector} is the connector of the document (a child takes its parent's).
	 */
	static String templateRefusal(final Repository pRepository, final JsonNode pMapping)
	{
		if (pMapping == null || !pMapping.isObject())
		{
			return null;
		}
		// a preview function that is closed (audit C3-05, decision of 2026-11-12): matching around a CDC batch is not stored
		final JsonNode givenInputs = pMapping.get("inputs");
		if ("cdc".equals(ConfigTransfer.text(pMapping, "operation")) && givenInputs != null && givenInputs.isObject() && givenInputs.has("matching"))
		{
			final java.util.Map<String, String> matching = new java.util.HashMap<String, String>();
			matching.put("matching", ConfigTransfer.text(givenInputs, "matching"));
			try
			{
				com.on.ebx.connect.core.PreviewFeatures.requireMappingAllowed("cdc", matching);
			}
			catch (final com.on.ebx.connect.spi.ConnectorException closed)
			{
				return "mapping '" + ConfigTransfer.text(pMapping, "label") + "': " + closed.getMessage();
			}
		}
		final String predicate = ConfigTransfer.text(pMapping, "keyPredicate");
		final String feed = pMapping.has("feed") && pMapping.get("feed").isObject() ? ConfigTransfer.text(pMapping.get("feed"), "keyPredicate") : null;
		for (final String template : new String[] { predicate, feed })
		{
			final String refusal = com.on.ebx.connect.core.ValueTemplates.xpathRefusal(template);
			if (refusal != null)
			{
				return "mapping '" + ConfigTransfer.text(pMapping, "label") + "': " + refusal;
			}
		}
		final JsonNode inputs = pMapping.get("inputs");
		if (inputs != null && inputs.isObject() && inputs.has("sql"))
		{
			final String refusal = com.on.ebx.connect.core.ValueTemplates.sqlRefusal(ConfigTransfer.text(inputs, "sql"));
			if (refusal != null)
			{
				return "mapping '" + ConfigTransfer.text(pMapping, "label") + "': " + refusal;
			}
		}
		final JsonNode children = pMapping.get("children");
		if (children != null && children.isArray())
		{
			for (final JsonNode child : children)
			{
				final String refusal = ConfigTransfer.templateRefusal(pRepository, child);
				if (refusal != null)
				{
					return refusal;
				}
			}
		}
		return null;
	}

	/** {@code feed: {itemsPath, keyPredicate, createIfMissing}} flattened onto the mapping; a feed without keyPredicate is reported. */
	static JsonNode withFeed(final JsonNode pMapping, final ArrayNode pNotes)
	{
		final JsonNode feed = pMapping == null ? null : pMapping.get("feed");
		if (feed == null || feed.isNull() || !(pMapping instanceof ObjectNode))
		{
			return pMapping;
		}
		final ObjectNode out = ((ObjectNode) pMapping).deepCopy();
		out.remove("feed");
		if (feed.isObject())
		{
			for (final String field : new String[] { "itemsPath", "keyPredicate", "createIfMissing" })
			{
				if (feed.has(field) && !out.has(field))
				{
					out.set(field, feed.get(field));
				}
			}
		}
		if (ConfigTransfer.text(out, "keyPredicate") == null)
		{
			pNotes.add("feed ignored: a feed needs keyPredicate (XPath template locating the record of an item, e.g. ./Code='${code}') and, when one document holds several items, itemsPath (e.g. messages[*].value)");
		}
		return out;
	}

	/** The label an update by id is about to replace, when it changes (its job must then be retired); null otherwise. */
	private static String previousLabel(final Adaptation pDataset, final JsonNode pMapping)
	{
		final String id = ConfigTransfer.text(pMapping, "id");
		if (id == null || !pMapping.has("updateById"))
		{
			return null;
		}
		try
		{
			final Adaptation row = pDataset.getTable(ConnectorsPaths.MAPPING).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(id.trim()));
			final String before = row == null ? null : row.getString(ConnectorsPaths.Mapping.LABEL);
			return before == null || before.equals(ConfigTransfer.text(pMapping, "label")) ? null : before;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Schedules the declared lineage of a mapping (id or foreign key value); {@code pRetiredLabel} non-null retires the job of that label. Never fails. */
	private static void lineage(final Repository pRepository, final String pMapping, final String pRetiredLabel)
	{
		if (pMapping == null || pMapping.trim().isEmpty())
		{
			return;
		}
		try
		{
			final int id = Integer.parseInt(pMapping.replaceAll("[^0-9]", ""));
			com.on.ebx.connect.core.DeclaredLineage.mappingChanged(pRepository, id,
				pRetiredLabel == null ? null : com.on.ebx.connect.core.OpenLineageEmitter.jobName(id, pRetiredLabel));
		}
		catch (final RuntimeException ex)
		{
			ConnectorService.log().warn("[connectors] declared lineage not scheduled for mapping " + pMapping + ": " + ex);
		}
	}

	/**
	 * Where the declared lineage of a mapping change goes and what the data catalog still needs: sinks and
	 * their contracts, the catalog found here (only when its dataset is really the catalog's), the Source
	 * matching the connector's namespace, advice. Never fails the change it reports on.
	 */
	private static ObjectNode lineageReport(final Repository pRepository, final String pMappingId, final String pConnectorId, final String pDeclaration)
	{
		try
		{
			final com.on.ebx.connect.core.ConnectorRepository repo = ConnectorService.get().repository(pRepository);
			com.on.ebx.connect.core.MappingDefinition mapping = null;
			if (pMappingId != null)
			{
				try
				{
					mapping = repo.mapping(Integer.parseInt(pMappingId.replaceAll("[^0-9]", "")));
				}
				catch (final RuntimeException ex)
				{
					mapping = null;
				}
			}
			return com.on.ebx.connect.core.CatalogDetection.report(repo, mapping, pConnectorId, pDeclaration);
		}
		catch (final RuntimeException ex)
		{
			final ObjectNode out = Json.object();
			out.put("declaration", pDeclaration);
			out.put("error", String.valueOf(ex.getMessage()));
			return out;
		}
	}

	private static void copy(final Adaptation pRow, final ObjectNode pTarget, final String[] pFields)
	{
		for (final String field : pFields)
		{
			final Path path = Path.parse("./" + field);
			if (pRow.getSchemaNode().getNode(path) == null)
			{
				continue;
			}
			final Object value = pRow.get(path);
			if (value == null)
			{
				continue;
			}
			if (value instanceof Boolean)
			{
				pTarget.put(field, ((Boolean) value).booleanValue());
			}
			else if (value instanceof Integer)
			{
				pTarget.put(field, ((Integer) value).intValue());
			}
			else
			{
				pTarget.put(field, pRow.getSchemaNode().getNode(path).formatToXsString(value));
			}
		}
	}

	/** Non-null when the connector type's base URL is a credential that an export must not carry (see {@code ConnectorConfig#isSecretBaseUrl}). */
	static String exportedBaseUrl(final String pType, final String pBaseUrl)
	{
		return "chat-webhook".equals(pType) && pBaseUrl != null && !ConfigTransfer.placeholder(pBaseUrl) ? "TO_BE_SET" : null;
	}

	private static JsonNode withoutBaseUrl(final JsonNode pJson)
	{
		final ObjectNode copy = ((ObjectNode) pJson).deepCopy();
		copy.remove("baseUrl");
		return copy;
	}

	private static void apply(final ValueContextForUpdate pRow, final JsonNode pJson, final String[] pFields)
	{
		for (final String field : pFields)
		{
			final JsonNode value = pJson.get(field);
			final Path path = Path.parse("./" + field);
			if (value == null || value.isNull() || pRow.getNode(path) == null)
			{
				continue;
			}
			if (value.isBoolean())
			{
				pRow.setValue(Boolean.valueOf(value.asBoolean()), path);
			}
			else if (value.isInt())
			{
				pRow.setValue(Integer.valueOf(value.asInt()), path);
			}
			else
			{
				pRow.setValueFromXsString(value.asText(), path);
			}
		}
	}

	static List<Adaptation> rows(final AdaptationTable pTable, final String pFilter)
	{
		return ConfigTransfer.rows(pTable, pFilter, null);
	}

	static List<Adaptation> rows(final AdaptationTable pTable, final String pFilter, final Path pSort)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final Request request = pTable.createRequest();
		if (pFilter != null)
		{
			request.setXPathFilter(pFilter);
		}
		if (pSort != null)
		{
			request.setSortCriteria(new com.onwbp.adaptation.RequestSortCriteria().add(pSort, true));
		}
		final RequestResult result = request.execute();
		try
		{
			for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			result.close();
		}
		return out;
	}

	static String quote(final String pValue)
	{
		return "'" + (pValue == null ? "" : pValue.replace("'", "''")) + "'";
	}

	private static String text(final JsonNode pJson, final String pField)
	{
		final JsonNode v = pJson.get(pField);
		return v == null || v.isNull() ? null : v.asText();
	}

	private static boolean placeholder(final String pValue)
	{
		final String v = pValue == null ? "" : pValue.trim();
		return v.isEmpty() || "TO_BE_SET".equals(v) || "A_RENSEIGNER".equals(v);
	}

	private static boolean isReference(final String pValue)
	{
		return pValue != null && pValue.matches("^(env|file|vault|azurekv):.+");
	}

}
