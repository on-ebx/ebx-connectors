package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.orchestranetworks.service.Session;

/**
 * Describes what a mapping run did as an <a href="https://openlineage.io">OpenLineage</a>
 * {@code RunEvent} and posts it to every active connector of type {@code openlineage} (the data
 * catalog's {@code /ebx-data-catalog/rest/v1/openlineage}, Marquez, DataHub, ...). One event per
 * mapping and per run: the EBX table on one side, the connector's object on the other, one
 * {@code columnLineage} entry per rule. The emission never fails a synchronisation: a sink that
 * refuses the event is logged and forgotten.
 * <p>
 * The event builder ({@link #event}) is pure and tested offline; the dispatch ({@link #emit}) reads
 * the sinks from the Connectors dataset (cached for thirty seconds) and goes through the
 * connector's {@code emit} operation, so that its authentication and egress rules apply.
 */
public final class OpenLineageEmitter
{
	private OpenLineageEmitter()
	{
	}

	/** Type name of the sink connector. */
	public static final String TYPE = "openlineage";
	public static final String PRODUCER = "https://github.com/on-ebx/ebx-connectors";
	public static final String SCHEMA_URL = "https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/RunEvent";
	static final String FACETS = "https://openlineage.io/spec/facets/1-0-0/";
	static final long TARGETS_TTL_MS = 30000L;

	/** What one sink wants to receive: read from the properties of its {@code openlineage} connector. */
	public static final class Settings
	{
		public final String ebxNamespace;
		public final String jobNamespace;
		public final String producer;
		public final boolean outbound;
		public final boolean inbound;
		public final Set<String> excludedTypes;
		public final Set<String> excludedConnectors;
		/** Whether the mappings are described as soon as they are declared, before any run ({@code emit.declared}, default true). */
		public final boolean declared;
		/** Whether the data catalog's own flows (mappings on its dataset) are left out ({@code exclude.catalogFlows}, default true). */
		public boolean excludeCatalogFlows = true;

		Settings(final String pEbxNamespace, final String pJobNamespace, final String pProducer, final boolean pOutbound, final boolean pInbound,
			final boolean pDeclared, final Set<String> pExcludedTypes, final Set<String> pExcludedConnectors)
		{
			this.ebxNamespace = pEbxNamespace;
			this.jobNamespace = pJobNamespace;
			this.producer = pProducer;
			this.outbound = pOutbound;
			this.inbound = pInbound;
			this.declared = pDeclared;
			this.excludedTypes = pExcludedTypes;
			this.excludedConnectors = pExcludedConnectors;
		}

		public static Settings of(final ConnectorConfig pSink)
		{
			final Settings settings = Settings.read(pSink);
			settings.excludeCatalogFlows = !"false".equalsIgnoreCase(OpenLineageEmitter.text(pSink.getProperty("exclude.catalogFlows", null), "true"));
			return settings;
		}

		private static Settings read(final ConnectorConfig pSink)
		{
			return new Settings(
				OpenLineageEmitter.text(pSink.getProperty("ebx.namespace", null), "ebx://localhost:8080"),
				OpenLineageEmitter.text(pSink.getProperty("job.namespace", null), "ebx-connectors"),
				OpenLineageEmitter.text(pSink.getProperty("producer", null), OpenLineageEmitter.PRODUCER),
				!"false".equalsIgnoreCase(OpenLineageEmitter.text(pSink.getProperty("emit.outbound", null), "true")),
				"true".equalsIgnoreCase(OpenLineageEmitter.text(pSink.getProperty("emit.inbound", null), "false")),
				!"false".equalsIgnoreCase(OpenLineageEmitter.text(pSink.getProperty("emit.declared", null), "true")),
				OpenLineageEmitter.set(pSink.getProperty("exclude.types", null), "chat-webhook,slack,openlineage,llm"),
				OpenLineageEmitter.set(pSink.getProperty("exclude.connectors", null), ""));
		}

		static Settings defaults()
		{
			return new Settings("ebx://localhost:8080", "ebx-connectors", OpenLineageEmitter.PRODUCER, true, false, true, Collections.<String>emptySet(),
				Collections.<String>emptySet());
		}

		/** Whether this sink wants the declared lineage of a mapping: {@code emit.declared}, then the same direction and exclusion rules as the runs. */
		public boolean acceptsDeclaration(final MappingDefinition pMapping, final ConnectorConfig pTarget, final String pSinkId)
		{
			return this.declared && this.accepts(pMapping, pTarget, pSinkId);
		}

		/** Whether this sink wants the run of a mapping whose remote side is {@code pTarget}. */
		public boolean accepts(final MappingDefinition pMapping, final ConnectorConfig pTarget, final String pSinkId)
		{
			if (pMapping == null || pTarget == null)
			{
				return false;
			}
			final boolean out = pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND;
			if (out ? !this.outbound : !this.inbound)
			{
				return false;
			}
			if (pSinkId != null && pSinkId.equals(pTarget.getId()))
			{
				return false; // never describe the sink to itself
			}
			final String type = pTarget.getType() == null ? "" : pTarget.getType().trim().toLowerCase(java.util.Locale.ENGLISH);
			return !this.excludedTypes.contains(type) && !this.excludedConnectors.contains(pTarget.getId());
		}
	}

	static String text(final String pValue, final String pDefault)
	{
		return pValue == null || pValue.trim().isEmpty() ? pDefault : pValue.trim();
	}

	static Set<String> set(final String pValue, final String pDefault)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final String s : OpenLineageEmitter.text(pValue, pDefault).split(","))
		{
			if (!s.trim().isEmpty()) out.add(s.trim().toLowerCase(java.util.Locale.ENGLISH));
		}
		return out;
	}

	// ------------------------------------------------------------------ the event (pure)

	/** True when the results describe something that actually reached (or failed to reach) the other side. */
	public static boolean worthAnEvent(final List<SyncResult> pResults)
	{
		if (pResults == null || pResults.isEmpty())
		{
			return false;
		}
		boolean touched = false;
		for (final SyncResult r : pResults)
		{
			if (r == null || r.getStatus() == null) continue;
			if (r.getStatus() == SyncResult.Status.DRY_RUN) return false;
			if (r.getStatus() == SyncResult.Status.OK || r.hasFailure()) touched = true;
		}
		return touched;
	}

	/**
	 * The RunEvent of one mapping run. {@code pTarget} is the connector the mapping talks to; the
	 * event's run id is derived from the Connectors run id and the mapping, so replaying a run
	 * updates the same OpenLineage run.
	 */
	public static ObjectNode event(final MappingDefinition pMapping, final ConnectorConfig pTarget, final List<SyncResult> pResults,
		final String pRunId, final Date pWhen, final Settings pSettings)
	{
		return OpenLineageEmitter.event(pMapping, pTarget, ResultTally.of(pMapping, pResults), pRunId, pWhen, pSettings);
	}

	/**
	 * Volume lot 7a: pure aggregation of a mapping run's results, incrementally updatable (see
	 * {@link ConnectorService#lineage}) instead of requiring the whole {@code List<SyncResult>} of a
	 * run to be held in memory until it ends - the OpenLineage half of item 2 of the 2026-09-28 audit
	 * (the other half is {@link ImportOutcome}, for the {@code connectors_sync} answer itself). The
	 * event never needed more than these counts plus a small sample of failing keys.
	 */
	static final class ResultTally
	{
		static final int MAX_SAMPLE_KEYS = 50;
		int sent, failed, partial, skipped, deleted;
		String dataspace;
		String firstError;
		private boolean touched;
		private boolean dryRun;
		final List<String> firstFailedKeys = new ArrayList<String>();

		ResultTally(final String pMappingDataspace)
		{
			this.dataspace = pMappingDataspace;
		}

		void add(final MappingDefinition pMapping, final SyncResult r)
		{
			if (r == null) return;
			if (this.dataspace == null && r.getDataspace() != null && !r.getDataspace().isEmpty()) this.dataspace = r.getDataspace();
			final SyncResult.Status st = r.getStatus();
			if (st == SyncResult.Status.DRY_RUN) this.dryRun = true;
			if (st == SyncResult.Status.OK || r.hasFailure()) this.touched = true;
			if (r.hasFailure())
			{
				this.failed++;
				if (st == SyncResult.Status.PARTIAL) this.partial++;
				if (this.firstError == null && r.getMessage() != null) this.firstError = r.getMessage();
				if (this.firstFailedKeys.size() < MAX_SAMPLE_KEYS && r.getRecordKey() != null && !r.getRecordKey().isEmpty())
				{
					this.firstFailedKeys.add(r.getRecordKey());
				}
			}
			else if (st == SyncResult.Status.OK)
			{
				if (pMapping.getDeleteOperation() != null && pMapping.getDeleteOperation().equals(r.getOperation())
					&& !pMapping.getDeleteOperation().equals(pMapping.getOperation())) this.deleted++;
				else this.sent++;
			}
			else if (st != null)
			{
				this.skipped++;
			}
		}

		/** Same rule as the old {@code OpenLineageEmitter.worthAnEvent(List)}: any dry-run result vetoes the whole event. */
		boolean worthAnEvent()
		{
			return !this.dryRun && this.touched;
		}

		static ResultTally of(final MappingDefinition pMapping, final List<SyncResult> pResults)
		{
			final ResultTally tally = new ResultTally(pMapping.getDataspace());
			if (pResults != null)
			{
				for (final SyncResult r : pResults) tally.add(pMapping, r);
			}
			return tally;
		}
	}

	static ObjectNode event(final MappingDefinition pMapping, final ConnectorConfig pTarget, final ResultTally pTally,
		final String pRunId, final Date pWhen, final Settings pSettings)
	{
		final Settings s = pSettings == null ? Settings.defaults() : pSettings;
		final String dataspace = pTally.dataspace;
		final boolean outbound = pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND;
		// Any failed item makes the run unsuccessful; counts retain evidence of successful writes.
		final String eventType = pTally.failed > 0 ? "FAIL" : "COMPLETE";
		final String when = java.time.format.DateTimeFormatter.ISO_INSTANT.format((pWhen == null ? new Date() : pWhen).toInstant());

		final String ebxName = OpenLineageEmitter.ebxName(pMapping, dataspace);

		final ObjectNode event = Json.object();
		event.put("eventType", eventType);
		event.put("eventTime", when);
		event.put("producer", s.producer);
		event.put("schemaURL", OpenLineageEmitter.SCHEMA_URL);

		final ObjectNode run = event.putObject("run");
		run.put("runId", OpenLineageEmitter.runUuid(pRunId, pMapping.getId()));
		final ObjectNode runFacets = run.putObject("facets");
		final ObjectNode nominal = OpenLineageEmitter.facet(runFacets, "nominalTime", s.producer, "NominalTimeRunFacet");
		nominal.put("nominalStartTime", when);
		if ("FAIL".equals(eventType))
		{
			final ObjectNode error = OpenLineageEmitter.facet(runFacets, "errorMessage", s.producer, "ErrorMessageRunFacet");
			error.put("message", pTally.firstError == null ? pTally.failed + " record(s) failed" : pTally.firstError);
			error.put("programmingLanguage", "JAVA");
		}
		final ObjectNode summary = OpenLineageEmitter.facet(runFacets, "ebx_connectors", OpenLineageEmitter.PRODUCER, "EbxConnectorsRunFacet");
		summary.put("runId", pRunId == null ? "" : pRunId);
		summary.put("mapping", pMapping.getId());
		summary.put("label", pMapping.getLabel() == null ? "" : pMapping.getLabel());
		summary.put("connector", pMapping.getConnectorId());
		summary.put("operation", pMapping.getOperation());
		summary.put("direction", outbound ? "OUTBOUND" : "INBOUND");
		summary.put("sent", pTally.sent);
		summary.put("deleted", pTally.deleted);
		summary.put("failed", pTally.failed);
		summary.put("partial", pTally.partial);
		summary.put("skipped", pTally.skipped);
		if (!pTally.firstFailedKeys.isEmpty())
		{
			final ArrayNode sampleKeys = summary.putArray("sampleFailedKeys");
			for (final String key : pTally.firstFailedKeys) sampleKeys.add(key);
		}

		final ObjectNode job = event.putObject("job");
		job.put("namespace", s.jobNamespace);
		job.put("name", OpenLineageEmitter.jobName(pMapping));
		final ObjectNode jobFacets = job.putObject("facets");
		final ObjectNode doc = OpenLineageEmitter.facet(jobFacets, "documentation", s.producer, "DocumentationJobFacet");
		doc.put("description", "ON EBX Connectors mapping " + pMapping.getId() + " (" + (outbound ? "outbound" : "inbound") + " " + pMapping.getConnectorId() + "/"
			+ pMapping.getOperation() + ") on " + ebxName + (pMapping.getLabel() == null ? "" : ": " + pMapping.getLabel()));
		final ObjectNode jobType = OpenLineageEmitter.facet(jobFacets, "jobType", s.producer, "JobTypeJobFacet");
		jobType.put("processingType", "BATCH");
		jobType.put("integration", "EBX_CONNECTORS");
		jobType.put("jobType", outbound ? "PUBLICATION" : "IMPORT");

		final ObjectNode output = OpenLineageEmitter.datasets(event, pMapping, pTarget, dataspace, s);
		final ObjectNode stats = OpenLineageEmitter.facet(output.putObject("outputFacets"), "outputStatistics", s.producer, "OutputStatisticsOutputDatasetFacet");
		stats.put("rowCount", pTally.sent);
		return event;
	}

	/**
	 * Adds {@code inputs} and {@code outputs} to an event — the EBX table on one side, the connector's
	 * object on the other, one {@code columnLineage} entry per rule on the output — and returns the
	 * output dataset. Each sub-object mapping brings its own EBX table: an input of an outbound event
	 * (the {@code collection[].field} of the document come from it), an output of an inbound one (its
	 * fields come from the {@code collection[].field} of the document).
	 */
	static ObjectNode datasets(final ObjectNode pEvent, final MappingDefinition pMapping, final ConnectorConfig pTarget, final String pDataspace, final Settings pSettings)
	{
		final boolean outbound = pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND;
		final String[] remote = OpenLineageEmitter.datasetOf(pTarget, pMapping);
		final ArrayNode inputs = pEvent.putArray("inputs");
		final ArrayNode outputs = pEvent.putArray("outputs");
		final ObjectNode ebx = Json.object();
		ebx.put("namespace", pSettings.ebxNamespace);
		ebx.put("name", OpenLineageEmitter.ebxName(pMapping, pDataspace));
		final ObjectNode other = Json.object();
		other.put("namespace", remote[0]);
		other.put("name", remote[1]);
		final ObjectNode input = outbound ? ebx : other;
		final ObjectNode output = outbound ? other : ebx;
		inputs.add(input);
		outputs.add(output);

		OpenLineageEmitter.columnLineage(OpenLineageEmitter.lineageFields(output, pSettings), pMapping, "", "", input.get("namespace").asText(), input.get("name").asText(),
			outbound, pSettings.producer);
		OpenLineageEmitter.children(inputs, outputs, output, pMapping, "", pMapping.getDataset(), pDataspace, other, outbound, pSettings);
		for (final com.fasterxml.jackson.databind.JsonNode dataset : outputs)
		{
			final ObjectNode facets = (ObjectNode) dataset.get("facets");
			if (facets != null && facets.path("columnLineage").path("fields").size() == 0)
			{
				facets.remove("columnLineage");
			}
		}
		return output;
	}

	/** The sub-object mappings of {@code pParent}, at any depth; {@code pPrefix} is the parent's collection path in the document ({@code a[].b[].}). */
	private static void children(final ArrayNode pInputs, final ArrayNode pOutputs, final ObjectNode pRemoteOutput, final MappingDefinition pParent, final String pPrefix,
		final String pDataset, final String pDataspace, final ObjectNode pRemote, final boolean pOutbound, final Settings pSettings)
	{
		for (final MappingDefinition child : pParent.getChildren())
		{
			if (child.getCollectionPath() == null || child.getCollectionPath().isEmpty() || child.getTablePath().isEmpty())
			{
				continue;
			}
			final String prefix = pPrefix + child.getCollectionPath() + "[].";
			// a sub-object mapping stored without them uses the dataset and dataspace of its root
			final String dataset = child.getDataset() == null ? pDataset : child.getDataset();
			final String dataspace = child.getDataspace() == null ? pDataspace : child.getDataspace();
			final String table = OpenLineageEmitter.ebxName(dataset, child.getTablePath(), dataspace);
			if (pOutbound)
			{
				OpenLineageEmitter.datasetIn(pInputs, pSettings.ebxNamespace, table);
				OpenLineageEmitter.columnLineage(OpenLineageEmitter.lineageFields(pRemoteOutput, pSettings), child, prefix, "", pSettings.ebxNamespace, table, true,
					pSettings.producer);
			}
			else
			{
				final ObjectNode written = OpenLineageEmitter.datasetIn(pOutputs, pSettings.ebxNamespace, table);
				OpenLineageEmitter.columnLineage(OpenLineageEmitter.lineageFields(written, pSettings), child, "", prefix, pRemote.get("namespace").asText(),
					pRemote.get("name").asText(), false, pSettings.producer);
			}
			OpenLineageEmitter.children(pInputs, pOutputs, pRemoteOutput, child, prefix, dataset, dataspace, pRemote, pOutbound, pSettings);
		}
	}

	/** The dataset of that name in the list, added when absent. */
	private static ObjectNode datasetIn(final ArrayNode pList, final String pNamespace, final String pName)
	{
		for (final com.fasterxml.jackson.databind.JsonNode dataset : pList)
		{
			if (pNamespace.equals(dataset.path("namespace").asText()) && pName.equals(dataset.path("name").asText()))
			{
				return (ObjectNode) dataset;
			}
		}
		final ObjectNode dataset = pList.addObject();
		dataset.put("namespace", pNamespace);
		dataset.put("name", pName);
		return dataset;
	}

	/** The {@code facets.columnLineage.fields} object of a dataset, created when absent. */
	private static ObjectNode lineageFields(final ObjectNode pDataset, final Settings pSettings)
	{
		final ObjectNode facets = pDataset.has("facets") ? (ObjectNode) pDataset.get("facets") : pDataset.putObject("facets");
		if (facets.has("columnLineage"))
		{
			return (ObjectNode) facets.get("columnLineage").get("fields");
		}
		return OpenLineageEmitter.facet(facets, "columnLineage", pSettings.producer, "ColumnLineageDatasetFacet").putObject("fields");
	}

	// ------------------------------------------------------------------ declared lineage (pure)

	public static final String JOB_EVENT_SCHEMA_URL = "https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/JobEvent";
	/** Job facet of a declaration: the mapping it describes and whether it is declared or retired. */
	public static final String DECLARATION_FACET = "ebx_connectors_declaration";

	/**
	 * The lineage a mapping declares, before any run: an OpenLineage {@code JobEvent} (no {@code run},
	 * no {@code eventType}) with the datasets and the column lineage a run of the mapping would carry.
	 * A receiver treats it as the job's whole current lineage — what it no longer lists is retired —
	 * so that the catalog shows where the data goes as soon as the flow is designed.
	 */
	public static ObjectNode declaration(final MappingDefinition pMapping, final ConnectorConfig pTarget, final Date pWhen, final Settings pSettings)
	{
		final Settings s = pSettings == null ? Settings.defaults() : pSettings;
		final boolean outbound = pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND;
		final ObjectNode event = OpenLineageEmitter.jobEvent(OpenLineageEmitter.jobName(pMapping), pWhen, s);
		final ObjectNode jobFacets = (ObjectNode) event.get("job").get("facets");
		final ObjectNode doc = OpenLineageEmitter.facet(jobFacets, "documentation", s.producer, "DocumentationJobFacet");
		doc.put("description", "ON EBX Connectors mapping " + pMapping.getId() + " (" + (outbound ? "outbound" : "inbound") + " " + pMapping.getConnectorId() + "/"
			+ pMapping.getOperation() + ") on " + OpenLineageEmitter.ebxName(pMapping, pMapping.getDataspace())
			+ (pMapping.getLabel() == null ? "" : ": " + pMapping.getLabel()));
		final ObjectNode jobType = OpenLineageEmitter.facet(jobFacets, "jobType", s.producer, "JobTypeJobFacet");
		jobType.put("processingType", "BATCH");
		jobType.put("integration", "EBX_CONNECTORS");
		jobType.put("jobType", outbound ? "PUBLICATION" : "IMPORT");
		final ObjectNode declaration = OpenLineageEmitter.facet(jobFacets, OpenLineageEmitter.DECLARATION_FACET, OpenLineageEmitter.PRODUCER, "EbxConnectorsDeclarationJobFacet");
		declaration.put("state", "DECLARED");
		declaration.put("mapping", pMapping.getId());
		declaration.put("connector", pMapping.getConnectorId());
		declaration.put("operation", pMapping.getOperation());
		declaration.put("direction", outbound ? "OUTBOUND" : "INBOUND");
		OpenLineageEmitter.datasets(event, pMapping, pTarget, pMapping.getDataspace(), s);
		return event;
	}

	/** The retirement of a mapping's job (deleted, deactivated, renamed): a {@code JobEvent} with no dataset. */
	public static ObjectNode retirement(final String pJobName, final int pMappingId, final Date pWhen, final Settings pSettings)
	{
		final Settings s = pSettings == null ? Settings.defaults() : pSettings;
		final ObjectNode event = OpenLineageEmitter.jobEvent(pJobName, pWhen, s);
		final ObjectNode declaration = OpenLineageEmitter.facet((ObjectNode) event.get("job").get("facets"), OpenLineageEmitter.DECLARATION_FACET,
			OpenLineageEmitter.PRODUCER, "EbxConnectorsDeclarationJobFacet");
		declaration.put("state", "RETIRED");
		declaration.put("mapping", pMappingId);
		event.putArray("inputs");
		event.putArray("outputs");
		return event;
	}

	static ObjectNode jobEvent(final String pJobName, final Date pWhen, final Settings pSettings)
	{
		final ObjectNode event = Json.object();
		event.put("eventTime", java.time.format.DateTimeFormatter.ISO_INSTANT.format((pWhen == null ? new Date() : pWhen).toInstant()));
		event.put("producer", pSettings.producer);
		event.put("schemaURL", OpenLineageEmitter.JOB_EVENT_SCHEMA_URL);
		final ObjectNode job = event.putObject("job");
		job.put("namespace", pSettings.jobNamespace);
		job.put("name", pJobName);
		job.putObject("facets");
		return event;
	}

	/** The job name of a mapping known only by its id and label (a deleted one): the label, else {@code mapping-<id>}. */
	public static String jobName(final int pMappingId, final String pLabel)
	{
		return pLabel == null || pLabel.trim().isEmpty() ? "mapping-" + pMappingId : pLabel.trim();
	}

	/** {@code pPrefix} prefixes the output fields, {@code pInputPrefix} the input fields (the collection path of a sub-object mapping on the document side). */
	static void columnLineage(final ObjectNode pFields, final MappingDefinition pMapping, final String pPrefix, final String pInputPrefix, final String pInputNamespace,
		final String pInputName, final boolean pOutbound, final String pProducer)
	{
		for (final MappingRule rule : pMapping.getRules())
		{
			final String source = rule.getSourcePath() == null ? "" : rule.getSourcePath().trim();
			final String target = rule.getTargetPath() == null ? "" : rule.getTargetPath().trim();
			if (source.isEmpty() || target.isEmpty())
			{
				continue; // a constant, or a rule without a destination: no input field to point at
			}
			final String transformation = rule.getTransformation() == null ? "" : rule.getTransformation().trim();
			if (Transformations.hasConstantStep(transformation))
			{
				continue;
			}
			// outbound: source = EBX field, target = document path; inbound: the reverse
			final String outField = pPrefix + OpenLineageEmitter.field(target);
			final String inField = pInputPrefix + OpenLineageEmitter.field(source);
			ObjectNode spec = (ObjectNode) pFields.get(outField);
			if (spec == null)
			{
				spec = pFields.putObject(outField);
				spec.putArray("inputFields");
			}
			final ObjectNode in = ((ArrayNode) spec.get("inputFields")).addObject();
			in.put("namespace", pInputNamespace);
			in.put("name", pInputName);
			in.put("field", inField);
			final ObjectNode t = in.putArray("transformations").addObject();
			t.put("type", "DIRECT");
			t.put("subtype", transformation.isEmpty() ? "IDENTITY" : "TRANSFORMATION");
			if (!transformation.isEmpty()) t.put("description", transformation);
			t.put("masking", false);
		}
	}

	/** {@code ./Identification/Name} → {@code Identification/Name}; a JSON path is kept as is. */
	static String field(final String pPath)
	{
		String p = pPath.trim();
		if (p.startsWith("./")) p = p.substring(2);
		else if (p.startsWith("/")) p = p.substring(1);
		return p;
	}

	static ObjectNode facet(final ObjectNode pHolder, final String pName, final String pProducer, final String pSchemaName)
	{
		final ObjectNode facet = pHolder.putObject(pName);
		facet.put("_producer", pProducer);
		facet.put("_schemaURL", OpenLineageEmitter.FACETS + pSchemaName + ".json");
		return facet;
	}

	/** The mapping's label, else {@code mapping-<id>}. */
	static String jobName(final MappingDefinition pMapping)
	{
		return OpenLineageEmitter.jobName(pMapping.getId(), pMapping.getLabel());
	}

	/** {@code <dataspace>/<dataset><tablePath>} — the qualified name the data catalog gives an EBX table. */
	static String ebxName(final MappingDefinition pMapping, final String pDataspace)
	{
		return OpenLineageEmitter.ebxName(pMapping.getDataset(), pMapping.getTablePath(), pDataspace);
	}

	static String ebxName(final String pDataset, final String pTablePath, final String pDataspace)
	{
		final String table = pTablePath == null ? "" : pTablePath.trim();
		final String dataset = pDataset == null ? "" : pDataset.trim();
		final String dataspace = pDataspace == null ? "" : pDataspace.trim();
		return (dataspace.isEmpty() ? dataset : dataspace + "/" + dataset) + (table.startsWith("/") ? table : "/" + table);
	}

	/** A stable UUID for (Connectors run, mapping): the OpenLineage {@code runId}. */
	static String runUuid(final String pRunId, final int pMappingId)
	{
		return OpenLineageEmitter.runUuid(pRunId, String.valueOf(pMappingId));
	}

	/** A stable UUID for (Connectors run, suffix) — {@code "publication"} for a product publication run (§2.3). */
	static String runUuid(final String pRunId, final String pSuffix)
	{
		try
		{
			return UUID.nameUUIDFromBytes(((pRunId == null ? "" : pRunId) + "|" + pSuffix).getBytes("UTF-8")).toString();
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			return UUID.randomUUID().toString();
		}
	}

	static final String[] OBJECT_INPUTS = { "table", "object", "sobject", "entity", "entitySet", "topic", "queue", "collection", "list", "index", "resource",
		"dataset", "sheet", "file", "path", "container", "bucket" };

	/** Brokers address a topic or a queue: there, a {@code table} input only labels the document and never names the object. */
	static final Set<String> MESSAGING_TYPES = new LinkedHashSet<String>(java.util.Arrays.asList("kafka", "rabbitmq", "jms", "activemq", "azure-service-bus"));
	static final String[] MESSAGE_INPUTS = { "topic", "queue", "destination", "entity", "subject", "channel" };

	static final String[] SQL_INPUTS = { "sql", "query", "statement" };
	/** The first {@code FROM [schema.]table} of a statement, identifiers optionally quoted; a sub-query ({@code FROM (}) is skipped to the table it reads. */
	static final java.util.regex.Pattern SQL_FROM = java.util.regex.Pattern.compile(
		"(?is)\\bfrom\\s+([`\"\\[]?[A-Za-z_][\\w$]*[`\"\\]]?(?:\\s*\\.\\s*[`\"\\[]?[A-Za-z_][\\w$]*[`\"\\]]?){0,2})");

	/** The table a query input reads ({@code sql}, {@code query}, {@code statement}); null when there is none or it is built from a template. */
	static String tableOfSql(final java.util.Map<String, String> pInputs)
	{
		if (pInputs == null)
		{
			return null;
		}
		for (final String candidate : OpenLineageEmitter.SQL_INPUTS)
		{
			final String sql = pInputs.get(candidate);
			if (sql == null || sql.trim().isEmpty())
			{
				continue;
			}
			final java.util.regex.Matcher m = OpenLineageEmitter.SQL_FROM.matcher(sql);
			if (!m.find())
			{
				return null;
			}
			final String table = m.group(1).replaceAll("[`\"\\[\\]\\s]", "");
			return table.isEmpty() || table.contains("${") ? null : table;
		}
		return null;
	}

	/** {namespace, name} of the connector's side of a mapping, from its base URL and the object-naming input of the mapping. */
	static String[] datasetOf(final ConnectorConfig pTarget, final MappingDefinition pMapping)
	{
		final String namespace = OpenLineageEmitter.namespaceOf(pTarget);
		final String type = pTarget.getType() == null ? "" : pTarget.getType().trim().toLowerCase(java.util.Locale.ENGLISH);
		if (OpenLineageEmitter.MESSAGING_TYPES.contains(type))
		{
			// the mapping's input, else the connector's default destination (Kafka: property "topic"), else the operation
			for (final String candidate : OpenLineageEmitter.MESSAGE_INPUTS)
			{
				final String v = pMapping.getInputs().get(candidate);
				if (v != null && !v.trim().isEmpty() && !v.contains("${"))
				{
					return new String[] { namespace, v.trim() };
				}
			}
			for (final String candidate : OpenLineageEmitter.MESSAGE_INPUTS)
			{
				final String v = pTarget.getProperty(candidate, null);
				if (v != null && !v.trim().isEmpty())
				{
					return new String[] { namespace, v.trim() };
				}
			}
			return new String[] { namespace, pMapping.getOperation() == null ? "?" : pMapping.getOperation() };
		}
		String name = null;
		String key = null;
		for (final String candidate : OpenLineageEmitter.OBJECT_INPUTS)
		{
			final String v = pMapping.getInputs().get(candidate);
			if (v != null && !v.trim().isEmpty() && !v.contains("${"))
			{
				name = v.trim();
				key = candidate;
				break;
			}
		}
		if (name == null)
		{
			// a query names no object: the table its SQL reads, when it says so plainly (else the operation, which names an API endpoint)
			name = OpenLineageEmitter.tableOfSql(pMapping.getInputs());
			key = name == null ? null : "table";
		}
		if (name == null)
		{
			name = pMapping.getOperation() == null ? "?" : pMapping.getOperation();
		}
		else if ("table".equals(key) && !name.contains("."))
		{
			final String schema = pTarget.getProperty("schema", null);
			if (schema != null && !schema.trim().isEmpty()) name = schema.trim() + "." + name;
		}
		return new String[] { namespace, name };
	}

	/** {@code h2://localhost:9092/catalogdemo}, {@code snowflake://acme.snowflakecomputing.com}, {@code salesforce://login.salesforce.com}... */
	static String namespaceOf(final ConnectorConfig pTarget)
	{
		final String type = pTarget.getType() == null || pTarget.getType().trim().isEmpty() ? "connector" : pTarget.getType().trim().toLowerCase(java.util.Locale.ENGLISH);
		final String url = pTarget.getBaseUrl() == null ? "" : pTarget.getBaseUrl().trim();
		if (url.toLowerCase(java.util.Locale.ENGLISH).startsWith("jdbc:"))
		{
			return OpenLineageEmitter.jdbcNamespace(url);
		}
		String host = OpenLineageEmitter.hostOf(url);
		if ((host == null || host.isEmpty()) && !url.isEmpty() && !url.contains("://"))
		{
			// no scheme: bootstrap servers or host:port ("broker1:9092,broker2:9092") — the first server names the cluster
			host = url.split(",")[0].trim().replaceAll("/.*$", "");
		}
		return type + "://" + (host == null || host.isEmpty() ? (pTarget.getId() == null ? "default" : pTarget.getId()) : host);
	}

	static String hostOf(final String pUrl)
	{
		if (pUrl == null || pUrl.isEmpty()) return null;
		try
		{
			final java.net.URI uri = new java.net.URI(pUrl);
			if (uri.getHost() == null) return null;
			return uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
		}
		catch (final java.net.URISyntaxException ex)
		{
			return null;
		}
	}

	/** JDBC URL → OpenLineage namespace, following the naming the catalog scans use ({@code h2://host:port/db}). */
	static String jdbcNamespace(final String pUrl)
	{
		final String rest = pUrl.substring(5); // after "jdbc:"
		final int colon = rest.indexOf(':');
		if (colon < 0)
		{
			return rest;
		}
		final String sub = rest.substring(0, colon).toLowerCase(java.util.Locale.ENGLISH);
		String tail = rest.substring(colon + 1);
		if ("oracle".equals(sub))
		{
			// thin:@host:port/service, thin:@//host:port/service, thin:@host:port:sid
			final int at = tail.indexOf('@');
			tail = at >= 0 ? tail.substring(at + 1) : tail;
		}
		if ("h2".equals(sub))
		{
			for (final String p : new String[] { "tcp://", "ssl://" })
			{
				if (tail.startsWith(p)) tail = tail.substring(p.length());
			}
			if (tail.startsWith("mem:") || tail.startsWith("file:")) tail = tail.replace(':', '/');
		}
		while (tail.startsWith("/")) tail = tail.substring(1);
		for (final char stop : new char[] { ';', '?' })
		{
			final int i = tail.indexOf(stop);
			if (i >= 0)
			{
				if ("sqlserver".equals(sub) && stop == ';')
				{
					final java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)databaseName=([^;]+)").matcher(tail);
					final String db = m.find() ? "/" + m.group(1) : "";
					tail = tail.substring(0, i) + db;
				}
				else
				{
					tail = tail.substring(0, i);
				}
			}
		}
		if (tail.endsWith("/")) tail = tail.substring(0, tail.length() - 1);
		return sub + "://" + tail;
	}

	// ------------------------------------------------------------------ dispatch

	private static volatile long targetsReadAt;
	private static volatile List<ConnectorConfig> targetsCache = Collections.emptyList();

	/** Forgets the cached list of sinks (a connector was created or edited). */
	public static void invalidate()
	{
		OpenLineageEmitter.targetsReadAt = 0L;
	}

	/** The active {@code openlineage} connectors, re-read at most every thirty seconds. */
	static List<ConnectorConfig> targets(final ConnectorRepository pRepo)
	{
		final long now = System.currentTimeMillis();
		if (now - OpenLineageEmitter.targetsReadAt < OpenLineageEmitter.TARGETS_TTL_MS)
		{
			return OpenLineageEmitter.targetsCache;
		}
		final List<ConnectorConfig> sinks = new ArrayList<ConnectorConfig>();
		try
		{
			for (final ConnectorConfig config : pRepo.connectors())
			{
				if (config.isActive() && config.getType() != null && OpenLineageEmitter.TYPE.equalsIgnoreCase(config.getType().trim()))
				{
					sinks.add(config);
				}
			}
		}
		catch (final RuntimeException ex)
		{
			// no Connectors dataset yet: nothing to send to
		}
		OpenLineageEmitter.targetsCache = Collections.unmodifiableList(sinks);
		OpenLineageEmitter.targetsReadAt = now;
		return OpenLineageEmitter.targetsCache;
	}

	/** Sends the event of one mapping run to every sink that wants it. Never throws. */
	public static void emit(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pMapping, final List<SyncResult> pResults,
		final String pRunId)
	{
		try
		{
			if (pMapping == null || pMapping.isChild() || !OpenLineageEmitter.worthAnEvent(pResults))
			{
				return;
			}
			final List<ConnectorConfig> sinks = OpenLineageEmitter.targets(pRepo);
			if (sinks.isEmpty())
			{
				return;
			}
			final ConnectorConfig target = pRepo.connector(pMapping.getConnectorId());
			if (target == null)
			{
				return;
			}
			for (final ConnectorConfig sink : sinks)
			{
				final Settings settings = Settings.of(sink);
				if (!settings.accepts(pMapping, target, sink.getId()) || settings.excludeCatalogFlows && CatalogDetection.isCatalogFlow(pRepo, pMapping))
				{
					continue;
				}
				try
				{
					final ObjectNode event = OpenLineageEmitter.event(pMapping, target, pResults, pRunId, new Date(), settings);
					final Connector connector = ConnectorRegistry.get().connector(sink.getId(), pRepo);
					connector.execute(new ConnectorContext(pSession, pRepo.getRepository(), null, null), "emit", new LinkedHashMap<String, String>(), event);
				}
				catch (final Exception ex)
				{
					ConnectorService.log().warn("[connectors] OpenLineage sink " + sink.getId() + " refused the event of mapping " + pMapping.getId() + ": "
						+ Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
				}
			}
		}
		catch (final Throwable ex)
		{
			ConnectorService.log().warn("[connectors] OpenLineage emission skipped: " + Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
		}
	}

	// ------------------------------------------------------------------ product publication (§2.3 bookkeeping)

	/** One target table of a publication, as needed to describe it: rows loaded and its columns (name, native type). */
	public static final class PublicationTable
	{
		public final int mapping;
		public final String table;
		public final int rows;
		public final List<String[]> columns; // {name, nativeType}

		public PublicationTable(final int pMapping, final String pTable, final int pRows, final List<String[]> pColumns)
		{
			this.mapping = pMapping;
			this.table = pTable;
			this.rows = pRows;
			this.columns = pColumns == null ? Collections.<String[]>emptyList() : pColumns;
		}
	}

	/**
	 * The {@code RunEvent} of a whole product publication (pure, offline-testable): one output dataset
	 * per target table, one input per EBX table read (every root mapping and its children), one
	 * {@code ebx_connectors} run facet carrying the publication summary. {@code pDataspace} is the
	 * dataspace actually resolved for the run ({@link MappingDefinition#runDataspace}), used for every
	 * input name instead of a mapping's own (often null) bound dataspace.
	 */
	public static ObjectNode publicationEvent(final List<MappingDefinition> pMappings, final String pDataspace, final ConnectorConfig pTarget, final String pRunId,
		final String pProduct, final String pContractVersion, final String pSchema, final String pCutoff, final List<PublicationTable> pTables,
		final boolean pFailed, final String pErrorMessage, final Date pWhen, final Settings pSettings)
	{
		final Settings s = pSettings == null ? Settings.defaults() : pSettings;
		final String eventType = pFailed ? "FAIL" : "COMPLETE";
		final String when = java.time.format.DateTimeFormatter.ISO_INSTANT.format((pWhen == null ? new Date() : pWhen).toInstant());

		final ObjectNode event = Json.object();
		event.put("eventType", eventType);
		event.put("eventTime", when);
		event.put("producer", s.producer);
		event.put("schemaURL", OpenLineageEmitter.SCHEMA_URL);

		final ObjectNode run = event.putObject("run");
		run.put("runId", OpenLineageEmitter.runUuid(pRunId, "publication"));
		final ObjectNode runFacets = run.putObject("facets");
		final ObjectNode nominal = OpenLineageEmitter.facet(runFacets, "nominalTime", s.producer, "NominalTimeRunFacet");
		nominal.put("nominalStartTime", when);
		if (pFailed)
		{
			final ObjectNode error = OpenLineageEmitter.facet(runFacets, "errorMessage", s.producer, "ErrorMessageRunFacet");
			error.put("message", pErrorMessage == null ? "publication failed" : pErrorMessage);
			error.put("programmingLanguage", "JAVA");
		}
		final ObjectNode summary = OpenLineageEmitter.facet(runFacets, "ebx_connectors", OpenLineageEmitter.PRODUCER, "EbxConnectorsRunFacet");
		summary.put("runId", pRunId == null ? "" : pRunId);
		summary.put("publication", true);
		summary.put("product", pProduct == null ? "" : pProduct);
		summary.put("contractVersion", pContractVersion == null ? "" : pContractVersion);
		summary.put("connector", pTarget.getId());
		summary.put("schema", pSchema == null ? "" : pSchema);
		summary.put("cutoff", pCutoff == null ? "" : pCutoff);
		final ArrayNode mappingIds = summary.putArray("mappings");
		for (final MappingDefinition m : pMappings)
		{
			if (m != null && !m.isChild())
			{
				mappingIds.add(m.getId());
			}
		}
		int totalRows = 0;
		final ArrayNode tablesArray = summary.putArray("tables");
		for (final PublicationTable t : pTables)
		{
			totalRows += t.rows;
			final ObjectNode tn = tablesArray.addObject();
			tn.put("table", t.table);
			tn.put("rows", t.rows);
		}
		summary.put("rows", totalRows);

		final ObjectNode job = event.putObject("job");
		job.put("namespace", s.jobNamespace);
		job.put("name", "publication:" + (pProduct == null ? "" : pProduct) + ":" + pTarget.getId());
		final ObjectNode jobFacets = job.putObject("facets");
		final ObjectNode doc = OpenLineageEmitter.facet(jobFacets, "documentation", s.producer, "DocumentationJobFacet");
		doc.put("description", "Publication of product " + pProduct + ", contract v" + pContractVersion + ", by mappings "
			+ OpenLineageEmitter.idsOf(pMappings) + " to " + pTarget.getId());
		final ObjectNode jobType = OpenLineageEmitter.facet(jobFacets, "jobType", s.producer, "JobTypeJobFacet");
		jobType.put("processingType", "BATCH");
		jobType.put("integration", "EBX_CONNECTORS");
		jobType.put("jobType", "PUBLICATION");

		final ArrayNode inputs = event.putArray("inputs");
		final ArrayNode outputs = event.putArray("outputs");
		final String namespace = OpenLineageEmitter.namespaceOf(pTarget);
		for (final MappingDefinition m : pMappings)
		{
			if (m == null || m.isChild())
			{
				continue;
			}
			final String table = OpenLineageEmitter.ebxName(m, pDataspace);
			OpenLineageEmitter.datasetIn(inputs, s.ebxNamespace, table);
			OpenLineageEmitter.publicationChildInputs(inputs, m, m.getDataset(), pDataspace, s);
			for (final PublicationTable t : pTables)
			{
				if (t.mapping != m.getId())
				{
					continue;
				}
				final ObjectNode ds = OpenLineageEmitter.datasetIn(outputs, namespace, pSchema + "." + t.table);
				final ObjectNode facets = ds.has("facets") ? (ObjectNode) ds.get("facets") : ds.putObject("facets");
				final ObjectNode schemaFacet = OpenLineageEmitter.facet(facets, "schema", s.producer, "SchemaDatasetFacet");
				final ArrayNode fields = schemaFacet.putArray("fields");
				for (final String[] column : t.columns)
				{
					final ObjectNode f = fields.addObject();
					f.put("name", column[0]);
					f.put("type", column.length > 1 ? column[1] : "");
				}
				OpenLineageEmitter.columnLineage(OpenLineageEmitter.lineageFields(ds, s), m, "", "", s.ebxNamespace, table, true, s.producer);
				// outputStatistics is an OUTPUT-specific facet: per the OpenLineage spec it belongs under the
				// dataset's own outputFacets, a sibling of facets (schema, columnLineage, ...) - not inside
				// facets itself. Same nesting event() already uses; a consumer reading outputs[].outputFacets
				// (OpenLineageImporter included) would otherwise never see the row count of a publication.
				final ObjectNode stats = OpenLineageEmitter.facet(ds.putObject("outputFacets"), "outputStatistics", s.producer, "OutputStatisticsOutputDatasetFacet");
				stats.put("rowCount", t.rows);
			}
		}
		return event;
	}

	/** {@code mapping.getId()} of every root mapping, comma-separated. */
	private static String idsOf(final List<MappingDefinition> pMappings)
	{
		final StringBuilder sb = new StringBuilder();
		for (final MappingDefinition m : pMappings)
		{
			if (m != null && !m.isChild())
			{
				sb.append(sb.length() > 0 ? ", " : "").append(m.getId());
			}
		}
		return "[" + sb + "]";
	}

	/** Sub-object mappings of a publication's root, as inputs only (a publication never writes back to EBX). */
	private static void publicationChildInputs(final ArrayNode pInputs, final MappingDefinition pParent, final String pDataset,
		final String pDataspace, final Settings pSettings)
	{
		for (final MappingDefinition child : pParent.getChildren())
		{
			if (child.getTablePath() == null || child.getTablePath().isEmpty())
			{
				continue;
			}
			final String dataset = child.getDataset() == null ? pDataset : child.getDataset();
			final String dataspace = child.getDataspace() == null ? pDataspace : child.getDataspace();
			OpenLineageEmitter.datasetIn(pInputs, pSettings.ebxNamespace, OpenLineageEmitter.ebxName(dataset, child.getTablePath(), dataspace));
			OpenLineageEmitter.publicationChildInputs(pInputs, child, dataset, dataspace, pSettings);
		}
	}

	/**
	 * Sends the {@link #publicationEvent} of a product publication to every sink that wants it. Never
	 * throws, and does nothing for a dry run (the caller simply does not call this for one).
	 */
	public static void emitPublication(final Session pSession, final ConnectorRepository pRepo, final List<MappingDefinition> pMappings,
		final String pDataspace, final String pRunId, final String pProduct, final String pContractVersion, final String pSchema, final String pCutoff,
		final List<PublicationTable> pTables, final boolean pFailed, final String pErrorMessage)
	{
		try
		{
			if (pMappings == null || pMappings.isEmpty())
			{
				return;
			}
			final MappingDefinition first = pMappings.get(0);
			final List<ConnectorConfig> sinks = OpenLineageEmitter.targets(pRepo);
			if (sinks.isEmpty())
			{
				return;
			}
			final ConnectorConfig target = pRepo.connector(first.getConnectorId());
			if (target == null)
			{
				return;
			}
			for (final ConnectorConfig sink : sinks)
			{
				final Settings settings = Settings.of(sink);
				if (!settings.outbound || sink.getId().equals(target.getId()))
				{
					continue;
				}
				final String type = target.getType() == null ? "" : target.getType().trim().toLowerCase(java.util.Locale.ENGLISH);
				if (settings.excludedTypes.contains(type) || settings.excludedConnectors.contains(target.getId()))
				{
					continue;
				}
				try
				{
					final ObjectNode event = OpenLineageEmitter.publicationEvent(pMappings, pDataspace, target, pRunId, pProduct, pContractVersion, pSchema,
						pCutoff, pTables, pFailed, pErrorMessage, new Date(), settings);
					final Connector connector = ConnectorRegistry.get().connector(sink.getId(), pRepo);
					connector.execute(new ConnectorContext(pSession, pRepo.getRepository(), null, null), "emit", new LinkedHashMap<String, String>(), event);
				}
				catch (final Exception ex)
				{
					ConnectorService.log().warn("[connectors] OpenLineage sink " + sink.getId() + " refused the publication event of '" + pProduct + "': "
						+ Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
				}
			}
		}
		catch (final Throwable ex)
		{
			ConnectorService.log().warn("[connectors] OpenLineage publication emission skipped: " + Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
		}
	}

	/** What a declaration or a retirement did: events posted, refused by a sink, or not wanted by it. */
	public static final class Dispatch
	{
		public int sent;
		public int failed;
		public int skipped;
		public final List<String> messages = new ArrayList<String>();

		void add(final Dispatch pOther)
		{
			this.sent += pOther.sent;
			this.failed += pOther.failed;
			this.skipped += pOther.skipped;
			this.messages.addAll(pOther.messages);
		}
	}

	/** Sends the declared lineage of an active root mapping to every sink that wants it. Never throws. */
	public static Dispatch declare(final ConnectorRepository pRepo, final MappingDefinition pMapping)
	{
		final Dispatch out = new Dispatch();
		try
		{
			if (pMapping == null || pMapping.isChild())
			{
				return out;
			}
			final List<ConnectorConfig> sinks = OpenLineageEmitter.targets(pRepo);
			if (sinks.isEmpty())
			{
				return out;
			}
			final ConnectorConfig target = pRepo.connector(pMapping.getConnectorId());
			if (target == null)
			{
				out.skipped++;
				out.messages.add("mapping " + pMapping.getId() + ": no connector '" + pMapping.getConnectorId() + "'");
				return out;
			}
			for (final ConnectorConfig sink : sinks)
			{
				final Settings settings = Settings.of(sink);
				if (!settings.acceptsDeclaration(pMapping, target, sink.getId()) || OpenLineageEmitter.refusesJobEvents(pRepo, sink))
				{
					out.skipped++;
					continue;
				}
				if (settings.excludeCatalogFlows && CatalogDetection.isCatalogFlow(pRepo, pMapping))
				{
					// one of the catalog's own flows: never declared, and whatever an earlier version declared is retired
					OpenLineageEmitter.post(pRepo, sink, OpenLineageEmitter.retirement(OpenLineageEmitter.jobName(pMapping), pMapping.getId(), new Date(), settings),
						"catalog flow " + pMapping.getId() + " (retired)", out);
					continue;
				}
				OpenLineageEmitter.post(pRepo, sink, OpenLineageEmitter.declaration(pMapping, target, new Date(), settings), "mapping " + pMapping.getId(), out);
			}
		}
		catch (final Throwable ex)
		{
			out.failed++;
			out.messages.add("mapping " + (pMapping == null ? "?" : String.valueOf(pMapping.getId())) + ": " + Redaction.redact(String.valueOf(ex.getMessage())));
			ConnectorService.log().warn("[connectors] declared lineage skipped: " + Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
		}
		return out;
	}

	/**
	 * Retires the job of a mapping on every sink that takes declarations. {@code pKnown} is the
	 * mapping when it still exists (deactivated, renamed): its connector's exclusions then apply.
	 * Never throws; a sink that never heard of the job ignores the event.
	 */
	public static Dispatch retire(final ConnectorRepository pRepo, final String pJobName, final int pMappingId, final MappingDefinition pKnown)
	{
		final Dispatch out = new Dispatch();
		try
		{
			final List<ConnectorConfig> sinks = OpenLineageEmitter.targets(pRepo);
			final ConnectorConfig target = pKnown == null ? null : pRepo.connector(pKnown.getConnectorId());
			for (final ConnectorConfig sink : sinks)
			{
				final Settings settings = Settings.of(sink);
				if (!settings.declared || target != null && !settings.accepts(pKnown, target, sink.getId()) || OpenLineageEmitter.refusesJobEvents(pRepo, sink))
				{
					out.skipped++;
					continue;
				}
				OpenLineageEmitter.post(pRepo, sink, OpenLineageEmitter.retirement(pJobName, pMappingId, new Date(), settings), "job '" + pJobName + "'", out);
			}
		}
		catch (final Throwable ex)
		{
			out.failed++;
			out.messages.add("job '" + pJobName + "': " + Redaction.redact(String.valueOf(ex.getMessage())));
			ConnectorService.log().warn("[connectors] lineage retirement skipped: " + Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
		}
		return out;
	}

	/** Whether a mapping is a catalog flow that at least one sink leaves out (its declaration is then a retirement). */
	static boolean excludedCatalogFlow(final ConnectorRepository pRepo, final MappingDefinition pMapping)
	{
		if (!CatalogDetection.isCatalogFlow(pRepo, pMapping))
		{
			return false;
		}
		for (final ConnectorConfig sink : OpenLineageEmitter.targets(pRepo))
		{
			if (Settings.of(sink).excludeCatalogFlows)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A sink that announced itself as the data catalog but takes no declared lineage (an older catalog,
	 * or one whose dataset is not its own). A sink with no contract keeps receiving everything, as before.
	 */
	static boolean refusesJobEvents(final ConnectorRepository pRepo, final ConnectorConfig pSink)
	{
		final CatalogDetection.Contract contract = CatalogDetection.contractOf(pRepo, pSink);
		return contract != null && contract.refusesJobEvents();
	}

	static void post(final ConnectorRepository pRepo, final ConnectorConfig pSink, final ObjectNode pEvent, final String pWhat, final Dispatch pOut)
	{
		try
		{
			final Connector connector = ConnectorRegistry.get().connector(pSink.getId(), pRepo);
			connector.execute(new ConnectorContext(null, pRepo.getRepository(), null, null), "emit", new LinkedHashMap<String, String>(), pEvent);
			pOut.sent++;
		}
		catch (final Exception ex)
		{
			pOut.failed++;
			final String message = Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage());
			pOut.messages.add(pWhat + " -> " + pSink.getId() + ": " + message);
			ConnectorService.log().warn("[connectors] OpenLineage sink " + pSink.getId() + " refused the declared lineage of " + pWhat + ": " + message);
		}
	}

	/**
	 * Entries of the per-run buffer: one per mapping, flushed by {@link ConnectorService#endRun()}.
	 * Volume lot 7a: a running {@link ResultTally} instead of a {@code List<SyncResult>} - see
	 * {@link ConnectorService#lineage}.
	 */
	static final class Pending
	{
		final Session session;
		final ConnectorRepository repository;
		final MappingDefinition mapping;
		final ResultTally tally;
		final String runId;

		Pending(final Session pSession, final ConnectorRepository pRepository, final MappingDefinition pMapping, final String pRunId)
		{
			this.session = pSession;
			this.repository = pRepository;
			this.mapping = pMapping;
			this.tally = new ResultTally(pMapping.getDataspace());
			this.runId = pRunId;
		}
	}

	/** Flushes a run buffer: one event per mapping. */
	static void flush(final Map<Integer, Pending> pBuffer)
	{
		if (pBuffer == null)
		{
			return;
		}
		for (final Pending p : pBuffer.values())
		{
			OpenLineageEmitter.emit(p.session, p.repository, p.mapping, p.tally, p.runId);
		}
		pBuffer.clear();
	}

	/** Tally-based counterpart of {@link #emit(Session, ConnectorRepository, MappingDefinition, List, String)}. */
	private static void emit(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pMapping, final ResultTally pTally,
		final String pRunId)
	{
		try
		{
			if (pMapping == null || pMapping.isChild() || !pTally.worthAnEvent())
			{
				return;
			}
			final List<ConnectorConfig> sinks = OpenLineageEmitter.targets(pRepo);
			if (sinks.isEmpty())
			{
				return;
			}
			final ConnectorConfig target = pRepo.connector(pMapping.getConnectorId());
			if (target == null)
			{
				return;
			}
			for (final ConnectorConfig sink : sinks)
			{
				final Settings settings = Settings.of(sink);
				if (!settings.accepts(pMapping, target, sink.getId()) || settings.excludeCatalogFlows && CatalogDetection.isCatalogFlow(pRepo, pMapping))
				{
					continue;
				}
				try
				{
					final ObjectNode event = OpenLineageEmitter.event(pMapping, target, pTally, pRunId, new Date(), settings);
					final Connector connector = ConnectorRegistry.get().connector(sink.getId(), pRepo);
					connector.execute(new ConnectorContext(pSession, pRepo.getRepository(), null, null), "emit", new LinkedHashMap<String, String>(), event);
				}
				catch (final Exception ex)
				{
					ConnectorService.log().warn("[connectors] OpenLineage sink " + sink.getId() + " refused the event of mapping " + pMapping.getId() + ": "
						+ Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
				}
			}
		}
		catch (final Throwable ex)
		{
			ConnectorService.log().warn("[connectors] OpenLineage emission skipped: " + Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
		}
	}
}
