package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaLocation;

/**
 * Finds the ON EBX data catalog (module {@code ebx-data-catalog}) without ever taking a customer's
 * dataset of the same name for it. Two independent checks, and nothing is proposed or written on doubt:
 * <ul>
 * <li><b>local</b> — the dataset {@code -Debx.catalog.dataspace}/{@code -Debx.catalog.dataset} (default
 * {@code DataCatalog}/{@code DataCatalog}, the JVM properties the catalog itself reads) counts only when
 * its data model is {@code /WEB-INF/ebx/schemas/DataCatalog.xsd} packaged by module
 * {@code ebx-data-catalog}. A DMA publication or another module's model of the same name is
 * {@link State#FOREIGN}: never read, never written;</li>
 * <li><b>remote</b> — an {@code openlineage} connector's endpoint is the catalog only when its
 * {@code GET} answers {@code module = ebx-data-catalog}; its {@code features} say what it takes
 * (a catalog announcing no {@code jobEvents} receives no declared lineage). Any other answer, or none,
 * leaves the sink as it always was: a plain OpenLineage endpoint.</li>
 * </ul>
 * No Java call crosses the module boundary: the catalog is read as data and over HTTP.
 */
public final class CatalogDetection
{
	private CatalogDetection()
	{
	}

	public static final String MODULE = "ebx-data-catalog";
	public static final String MODEL_PATH = "/WEB-INF/ebx/schemas/DataCatalog.xsd";
	public static final String JOB_EVENTS = "jobEvents";
	static final Path SOURCE_TABLE = Path.parse("/root/Source");
	static final Path SOURCE_ID = Path.parse("./id");
	static final Path SOURCE_CONNECTOR = Path.parse("./connector");
	static final Path SOURCE_NAMESPACE = Path.parse("./lineageNamespace");
	static final long CONTRACT_TTL_MS = 300000L;

	public enum State
	{
		/** The dataset exists and its model is the catalog's. */
		VERIFIED,
		/** No dataset of that name. */
		ABSENT,
		/** A dataset of that name on another data model: not the catalog. */
		FOREIGN
	}

	// ------------------------------------------------------------------ local

	public static String dataspaceKey()
	{
		return CatalogDetection.property("ebx.catalog.dataspace");
	}

	public static String datasetName()
	{
		return CatalogDetection.property("ebx.catalog.dataset");
	}

	private static String property(final String pName)
	{
		final String value = System.getProperty(pName);
		return value == null || value.trim().isEmpty() ? "DataCatalog" : value.trim();
	}

	/** What the local check found. {@link #adaptation} is set only when {@link State#VERIFIED}. */
	public static final class Local
	{
		public final State state;
		public final String dataspace;
		public final String dataset;
		public final String model;
		public final String reason;
		final Adaptation adaptation;

		Local(final State pState, final String pModel, final String pReason, final Adaptation pAdaptation)
		{
			this.state = pState;
			this.dataspace = CatalogDetection.dataspaceKey();
			this.dataset = CatalogDetection.datasetName();
			this.model = pModel;
			this.reason = pReason;
			this.adaptation = pState == State.VERIFIED ? pAdaptation : null;
		}
	}

	/**
	 * Pure: null when a model location is the catalog's, else why it is not. {@code pModule} and
	 * {@code pPath} are only meaningful when {@code pPackaged}.
	 */
	public static String mismatch(final boolean pPackaged, final String pModule, final String pPath)
	{
		if (!pPackaged)
		{
			return "its data model is not packaged in a module (a DMA publication or an external schema)";
		}
		if (!CatalogDetection.MODULE.equals(pModule))
		{
			return "its data model belongs to module '" + pModule + "', not " + CatalogDetection.MODULE;
		}
		final String path = pPath == null ? "" : pPath.trim().startsWith("/") ? pPath.trim() : "/" + pPath.trim();
		if (!CatalogDetection.MODEL_PATH.equals(path))
		{
			return "its data model is " + path + ", not " + CatalogDetection.MODEL_PATH;
		}
		return null;
	}

	/** The local check. Never throws: an unreadable model counts as {@link State#FOREIGN}. */
	public static Local local(final Repository pRepository)
	{
		final String where = CatalogDetection.dataspaceKey() + "/" + CatalogDetection.datasetName();
		try
		{
			final AdaptationHome home = pRepository == null ? null : pRepository.lookupHome(HomeKey.forBranchName(CatalogDetection.dataspaceKey()));
			final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(CatalogDetection.datasetName()));
			if (dataset == null)
			{
				return new Local(State.ABSENT, null, "no dataset " + where, null);
			}
			final SchemaLocation location = dataset.getSchemaLocation();
			final boolean packaged = location.isPackagedInModule();
			final String mismatch = CatalogDetection.mismatch(packaged, packaged ? location.getModuleName() : null, packaged ? location.getPathInModule() : null);
			final String model = String.valueOf(location.format());
			return mismatch == null ? new Local(State.VERIFIED, model, null, dataset)
				: new Local(State.FOREIGN, model, "dataset " + where + " is not the data catalog: " + mismatch, null);
		}
		catch (final RuntimeException ex)
		{
			return new Local(State.FOREIGN, null, "dataset " + where + " could not be checked (" + Redaction.redact(String.valueOf(ex.getMessage())) + "): not treated as the data catalog", null);
		}
	}

	/** Pure: whether a mapping's EBX side is the dataset named like the catalog ({@code -Debx.catalog.dataspace} / {@code .dataset}). */
	public static boolean onCatalogKeys(final MappingDefinition pMapping, final String pDataspace, final String pDataset)
	{
		return pMapping != null && pDataspace.equals(pMapping.getDataspace()) && pDataset.equals(pMapping.getDataset());
	}

	private static volatile long localCheckedAt;
	private static volatile String localCheckedKey;
	private static volatile State localCheckedState;

	/**
	 * Whether a mapping is one of the catalog's own flows (scan, profile, sample, usage, lineage, jobs,
	 * access grant and revoke, alerts, Collibra publication): its EBX side is the catalog dataset. Those
	 * feed or serve the catalog; described as lineage they would appear in it as tables named after
	 * their operation. A same-named dataset that is not the catalog's ({@link State#FOREIGN}) is not
	 * concerned: its flows are described as any other. The local check is kept one minute.
	 */
	public static boolean isCatalogFlow(final ConnectorRepository pRepo, final MappingDefinition pMapping)
	{
		final String dataspace = CatalogDetection.dataspaceKey();
		final String dataset = CatalogDetection.datasetName();
		if (!CatalogDetection.onCatalogKeys(pMapping, dataspace, dataset) || pRepo == null)
		{
			return false;
		}
		final String key = dataspace + "/" + dataset;
		final long now = System.currentTimeMillis();
		State state = CatalogDetection.localCheckedState;
		if (state == null || !key.equals(CatalogDetection.localCheckedKey) || now - CatalogDetection.localCheckedAt > 60000L)
		{
			state = CatalogDetection.local(pRepo.getRepository()).state;
			CatalogDetection.localCheckedState = state;
			CatalogDetection.localCheckedKey = key;
			CatalogDetection.localCheckedAt = now;
		}
		return state == State.VERIFIED;
	}

	/**
	 * {id, connector} of the catalog Source whose lineageNamespace is {@code pNamespace}; null when none, or
	 * when the catalog is not verified. A Source without connector is usually one the catalog created by
	 * itself from lineage events for a namespace it did not know.
	 */
	public static String[] sourceByNamespace(final Local pLocal, final String pNamespace)
	{
		final List<Adaptation> rows = CatalogDetection.sources(pLocal, SOURCE_NAMESPACE, pNamespace);
		return rows.isEmpty() ? null : new String[] { rows.get(0).getString(SOURCE_ID), rows.get(0).getString(SOURCE_CONNECTOR) };
	}

	/** The catalog Sources that name {@code pConnectorId} as their connector: {id, lineageNamespace} each. */
	public static List<String[]> sourcesByConnector(final Local pLocal, final String pConnectorId)
	{
		final List<String[]> out = new ArrayList<String[]>();
		for (final Adaptation row : CatalogDetection.sources(pLocal, SOURCE_CONNECTOR, pConnectorId))
		{
			out.add(new String[] { row.getString(SOURCE_ID), row.getString(SOURCE_NAMESPACE) });
		}
		return out;
	}

	static List<Adaptation> sources(final Local pLocal, final Path pField, final String pValue)
	{
		if (pLocal == null || pLocal.adaptation == null || pValue == null || pValue.trim().isEmpty())
		{
			return Collections.emptyList();
		}
		try
		{
			if (pLocal.adaptation.getSchemaNode().getNode(SOURCE_TABLE) == null)
			{
				return Collections.emptyList();
			}
			final AdaptationTable table = pLocal.adaptation.getTable(SOURCE_TABLE);
			if (table.getTableOccurrenceRootNode().getNode(pField) == null || table.getTableOccurrenceRootNode().getNode(SOURCE_ID) == null)
			{
				return Collections.emptyList();
			}
			return table.selectOccurrences(pField.format() + "=" + CatalogDetection.literal(pValue.trim()));
		}
		catch (final RuntimeException ex)
		{
			return Collections.emptyList();
		}
	}

	static String literal(final String pValue)
	{
		return pValue.indexOf('\'') < 0 ? "'" + pValue + "'" : "\"" + pValue.replace("\"", "") + "\"";
	}

	// ------------------------------------------------------------------ the catalog Source of a connector

	public static final String LINKED = "LINKED";
	public static final String CREATE = "CREATE";
	public static final String SET_CONNECTOR = "SET_CONNECTOR";
	public static final String SET_NAMESPACE = "SET_NAMESPACE";
	public static final String MANUAL = "MANUAL";
	static final Path SOURCE_LABEL = Path.parse("./label");
	static final Path SOURCE_PLATFORM = Path.parse("./platform");
	static final Path SOURCE_DESCRIPTION = Path.parse("./description");

	/** What {@link #catalogSource} does, or would do, for a connector. */
	public static final class SourcePlan
	{
		public final String action;
		public final String sourceId;
		public final String message;

		SourcePlan(final String pAction, final String pSourceId, final String pMessage)
		{
			this.action = pAction;
			this.sourceId = pSourceId;
			this.message = pMessage;
		}

		public boolean writes()
		{
			return CatalogDetection.CREATE.equals(this.action) || CatalogDetection.SET_CONNECTOR.equals(this.action) || CatalogDetection.SET_NAMESPACE.equals(this.action);
		}
	}

	/**
	 * Pure: how the catalog should know the system a connector reaches, from the Source holding its
	 * namespace ({id, connector}, or null) and the Sources naming the connector ({id, namespace} each).
	 * Anything ambiguous is {@link #MANUAL}: never guess which Source to change, never delete one.
	 */
	public static SourcePlan planSource(final String pConnectorId, final String pNamespace, final String[] pByNamespace, final List<String[]> pByConnector,
		final String pNewId, final boolean pNewIdTaken)
	{
		if (pByNamespace != null)
		{
			final String holder = pByNamespace[1] == null ? "" : pByNamespace[1].trim();
			if (holder.equals(pConnectorId))
			{
				return new SourcePlan(LINKED, pByNamespace[0], "Source " + pByNamespace[0] + " already names connector " + pConnectorId + " with namespace '" + pNamespace + "'.");
			}
			if (!holder.isEmpty())
			{
				return new SourcePlan(MANUAL, pByNamespace[0], "Namespace '" + pNamespace + "' is held by Source " + pByNamespace[0] + " of connector " + holder
					+ ": two connectors reach the same system, or one of them is misconfigured. Nothing changed.");
			}
			final List<String> others = new ArrayList<String>();
			for (final String[] s : pByConnector)
			{
				if (!s[0].equals(pByNamespace[0]))
				{
					others.add(s[0] + " ('" + (s[1] == null ? "" : s[1]) + "')");
				}
			}
			if (others.isEmpty())
			{
				return new SourcePlan(SET_CONNECTOR, pByNamespace[0], "Source " + pByNamespace[0] + " holds namespace '" + pNamespace
					+ "' and has no connector (the catalog created it from lineage events): set its connector to " + pConnectorId + ".");
			}
			return new SourcePlan(MANUAL, pByNamespace[0], "The catalog lists this system twice: Source " + pByNamespace[0] + " (no connector, created from lineage events) holds '"
				+ pNamespace + "' while " + others + " name connector " + pConnectorId + ". Set lineageNamespace '" + pNamespace
				+ "' on the Source of the connector, then move the assets of " + pByNamespace[0] + " and delete it. Nothing changed.");
		}
		if (pByConnector.size() == 1)
		{
			final String[] s = pByConnector.get(0);
			return new SourcePlan(SET_NAMESPACE, s[0], "Source " + s[0] + " names connector " + pConnectorId + " with namespace '" + (s[1] == null ? "" : s[1])
				+ "': set it to '" + pNamespace + "', the namespace the connector's lineage carries.");
		}
		if (pByConnector.size() > 1)
		{
			final StringBuilder ids = new StringBuilder();
			for (final String[] s : pByConnector)
			{
				ids.append(ids.length() == 0 ? "" : ", ").append(s[0]);
			}
			return new SourcePlan(MANUAL, null, "Sources " + ids + " all name connector " + pConnectorId + " and none has namespace '" + pNamespace
				+ "': choose the one to align. Nothing changed.");
		}
		if (pNewIdTaken)
		{
			return new SourcePlan(MANUAL, pNewId, "No Source names connector " + pConnectorId + ", and the id " + pNewId + " is already used by another Source: pass source=<id>. Nothing changed.");
		}
		return new SourcePlan(CREATE, pNewId, "No Source for connector " + pConnectorId + ": create Source " + pNewId + " with namespace '" + pNamespace
			+ "'; the catalog then creates its flows.");
	}

	/** Pure: a Source id from a connector id ({@code sap-odata prod} → {@code SAP_ODATA_PROD}), at most 40 characters. */
	public static String sourceIdFor(final String pConnectorId)
	{
		String s = (pConnectorId == null ? "" : pConnectorId).toUpperCase(java.util.Locale.ENGLISH).replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
		if (s.isEmpty())
		{
			s = "SOURCE";
		}
		return s.length() > 40 ? s.substring(0, 40) : s;
	}

	/** Pure: the catalog's platform for a namespace, the way the catalog guesses it from lineage events. */
	public static String platformFor(final String pNamespace)
	{
		final String s = pNamespace == null ? "" : pNamespace.toLowerCase(java.util.Locale.ENGLISH);
		final String[][] prefixes = { { "snowflake", "SNOWFLAKE" }, { "databricks", "DATABRICKS" }, { "dbfs", "DATABRICKS" }, { "bigquery", "BIGQUERY" },
			{ "postgres", "POSTGRESQL" }, { "oracle", "ORACLE" }, { "sqlserver", "SQLSERVER" }, { "mssql", "SQLSERVER" }, { "mysql", "MYSQL" }, { "h2", "H2" },
			{ "sap", "SAP" }, { "salesforce", "SALESFORCE" }, { "ebx", "EBX" }, { "kafka", "KAFKA" }, { "s3", "FILES" }, { "gs", "FILES" }, { "abfs", "FILES" },
			{ "wasb", "FILES" }, { "sftp", "FILES" }, { "file", "FILES" }, { "hdfs", "FILES" }, { "powerbi", "BI" }, { "tableau", "BI" }, { "looker", "BI" }, { "qlik", "BI" } };
		for (final String[] p : prefixes)
		{
			if (s.startsWith(p[0]))
			{
				return p[1];
			}
		}
		return "OTHER";
	}

	static String cut(final String pValue, final int pMax)
	{
		return pValue == null || pValue.length() <= pMax ? pValue : pValue.substring(0, pMax);
	}

	/**
	 * MCP {@code connectors_catalog_source} / REST {@code POST /lineage/catalog-source}: how the system a
	 * connector reaches is known to the ON EBX data catalog, and with {@code apply} the change that makes
	 * its lineage land on the right Source. Reads and writes only a {@link State#VERIFIED} catalog, with
	 * the caller's permissions; never deletes. The catalog's own source trigger then creates or realigns
	 * the flows of the Source.
	 */
	public static ObjectNode catalogSource(final ConnectorRepository pRepo, final com.orchestranetworks.service.Session pSession, final String pConnectorId,
		final String pSourceId, final boolean pApply)
	{
		final ObjectNode out = Json.object();
		out.put("apply", pApply);
		if (pConnectorId == null || pConnectorId.trim().isEmpty())
		{
			out.put("error", "connector is required");
			return out;
		}
		final String id = pConnectorId.trim();
		final ConnectorConfig connector = pRepo.connector(id);
		if (connector == null)
		{
			out.put("error", "No connector '" + id + "'");
			return out;
		}
		final Local local = CatalogDetection.local(pRepo.getRepository());
		final ObjectNode catalog = out.putObject("catalog");
		catalog.put("state", local.state.name());
		catalog.put("dataspace", local.dataspace);
		catalog.put("dataset", local.dataset);
		out.put("connector", id);
		if (local.state != State.VERIFIED)
		{
			out.put("action", "NONE");
			out.put("applied", false);
			out.put("message", local.state == State.ABSENT ? "The ON EBX data catalog is not deployed on this instance (" + local.reason + "): nothing to link."
				: local.reason + ". Nothing is read or written there.");
			return out;
		}
		final String namespace = OpenLineageEmitter.namespaceOf(connector);
		out.put("namespace", namespace);
		final String newId = pSourceId == null || pSourceId.trim().isEmpty() ? CatalogDetection.sourceIdFor(id) : pSourceId.trim();
		final SourcePlan plan = CatalogDetection.planSource(id, namespace, CatalogDetection.sourceByNamespace(local, namespace), CatalogDetection.sourcesByConnector(local, id),
			newId, !CatalogDetection.sources(local, SOURCE_ID, newId).isEmpty());
		out.put("action", plan.action);
		if (plan.sourceId != null)
		{
			out.put("source", plan.sourceId);
		}
		out.put("message", plan.message);
		if (!plan.writes() || !pApply)
		{
			out.put("applied", false);
			if (plan.writes())
			{
				out.put("nextStep", "connectors_catalog_source(connector=" + id + (pSourceId == null || pSourceId.trim().isEmpty() ? "" : ", source=" + newId) + ", apply=true) writes it.");
			}
			return out;
		}
		final String error = CatalogDetection.writeSource(local, pSession, connector, namespace, plan);
		out.put("applied", error == null);
		if (error != null)
		{
			out.put("error", error);
			return out;
		}
		out.put("nextStep", "The catalog creates or realigns the flows of Source " + plan.sourceId
			+ " by itself. Run connectors_lineage_declare so that the declared lineage of this connector lands on it; assets filed earlier under another Source stay there.");
		return out;
	}

	static String writeSource(final Local pLocal, final com.orchestranetworks.service.Session pSession, final ConnectorConfig pConnector, final String pNamespace,
		final SourcePlan pPlan)
	{
		try
		{
			final AdaptationTable table = pLocal.adaptation.getTable(SOURCE_TABLE);
			final com.orchestranetworks.service.ProcedureResult result = com.orchestranetworks.service.ProgrammaticService.createForSession(pSession, pLocal.adaptation.getHome())
				.execute(new SourceWrite(table, pConnector, pNamespace, pPlan));
			return result.hasFailed() ? Redaction.redact(String.valueOf(result.getExceptionFullMessage(java.util.Locale.ENGLISH))) : null;
		}
		catch (final RuntimeException ex)
		{
			return Redaction.redact(String.valueOf(ex.getMessage()));
		}
	}

	/** The one write of {@link #catalogSource}: create the Source, or set its connector or its namespace. */
	static final class SourceWrite implements com.orchestranetworks.service.Procedure
	{
		final AdaptationTable table;
		final ConnectorConfig connector;
		final String namespace;
		final SourcePlan plan;

		SourceWrite(final AdaptationTable pTable, final ConnectorConfig pConnector, final String pNamespace, final SourcePlan pPlan)
		{
			this.table = pTable;
			this.connector = pConnector;
			this.namespace = pNamespace;
			this.plan = pPlan;
		}

		@Override
		public void execute(final com.orchestranetworks.service.ProcedureContext pContext) throws Exception
		{
			if (CatalogDetection.CREATE.equals(this.plan.action))
			{
				final com.orchestranetworks.service.ValueContextForUpdate row = pContext.getContextForNewOccurrence(this.table);
				row.setValue(this.plan.sourceId, CatalogDetection.SOURCE_ID);
				final String label = this.connector.getLabel() == null || this.connector.getLabel().trim().isEmpty() ? this.connector.getId() : this.connector.getLabel().trim();
				row.setValue(CatalogDetection.cut(label, 120), CatalogDetection.SOURCE_LABEL);
				row.setValue(CatalogDetection.platformFor(this.namespace), CatalogDetection.SOURCE_PLATFORM);
				row.setValue(this.connector.getId(), CatalogDetection.SOURCE_CONNECTOR);
				row.setValue(CatalogDetection.cut(this.namespace, 500), CatalogDetection.SOURCE_NAMESPACE);
				row.setValue("Declared from ebx-connectors for connector " + this.connector.getId() + ".", CatalogDetection.SOURCE_DESCRIPTION);
				pContext.doCreateOccurrence(row, this.table);
				return;
			}
			final Adaptation existing = this.table.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(this.plan.sourceId));
			if (existing == null)
			{
				throw com.orchestranetworks.service.OperationException.createError("Source " + this.plan.sourceId + " no longer exists");
			}
			final com.orchestranetworks.service.ValueContextForUpdate row = pContext.getContext(existing.getAdaptationName());
			if (CatalogDetection.SET_CONNECTOR.equals(this.plan.action))
			{
				row.setValue(this.connector.getId(), CatalogDetection.SOURCE_CONNECTOR);
			}
			else
			{
				row.setValue(CatalogDetection.cut(this.namespace, 500), CatalogDetection.SOURCE_NAMESPACE);
			}
			pContext.doModifyContent(existing, row);
		}
	}

	// ------------------------------------------------------------------ remote

	/** What an OpenLineage endpoint announced on {@code GET}. */
	public static final class Contract
	{
		/** True only when the endpoint says it is module ebx-data-catalog. */
		public final boolean catalog;
		public final int version;
		public final Set<String> features;
		public final String dataspace;
		public final String dataset;
		public final String datasetState;

		Contract(final boolean pCatalog, final int pVersion, final Set<String> pFeatures, final String pDataspace, final String pDataset, final String pDatasetState)
		{
			this.catalog = pCatalog;
			this.version = pVersion;
			this.features = pFeatures;
			this.dataspace = pDataspace;
			this.dataset = pDataset;
			this.datasetState = pDatasetState;
		}

		/** Pure. Anything that is not the catalog's contract parses as a non-catalog endpoint. */
		public static Contract parse(final JsonNode pBody)
		{
			if (pBody == null || !pBody.isObject() || !CatalogDetection.MODULE.equals(pBody.path("module").asText("")))
			{
				return new Contract(false, 0, Collections.<String>emptySet(), null, null, null);
			}
			final Set<String> features = new LinkedHashSet<String>();
			for (final JsonNode f : pBody.path("features"))
			{
				features.add(f.asText());
			}
			return new Contract(true, pBody.path("contractVersion").asInt(0), Collections.unmodifiableSet(features), CatalogDetection.textOrNull(pBody, "dataspace"),
				CatalogDetection.textOrNull(pBody, "dataset"), CatalogDetection.textOrNull(pBody, "datasetState"));
		}

		/** A catalog that does not take declared lineage (an older one), or whose dataset is not its own. */
		public boolean refusesJobEvents()
		{
			return this.catalog && (!this.features.contains(CatalogDetection.JOB_EVENTS) || "FOREIGN".equals(this.datasetState));
		}
	}

	static String textOrNull(final JsonNode pNode, final String pField)
	{
		final JsonNode v = pNode.get(pField);
		return v == null || v.isNull() ? null : v.asText();
	}

	/** sink id + base URL → {read at, contract (null = no answer)}. */
	static final Map<String, Object[]> CONTRACTS = new ConcurrentHashMap<String, Object[]>();

	/**
	 * The contract of a sink, read at most every five minutes; null when its endpoint gave none (not
	 * reachable, not the catalog's GET): the sink is then treated as it always was.
	 */
	public static Contract contractOf(final ConnectorRepository pRepo, final ConnectorConfig pSink)
	{
		if (pSink == null)
		{
			return null;
		}
		final String key = pSink.getId() + "|" + pSink.getBaseUrl();
		final Object[] cached = CatalogDetection.CONTRACTS.get(key);
		final long now = System.currentTimeMillis();
		if (cached != null && now - ((Long) cached[0]).longValue() < CatalogDetection.CONTRACT_TTL_MS)
		{
			return (Contract) cached[1];
		}
		Contract contract = null;
		try
		{
			final Connector connector = ConnectorRegistry.get().connector(pSink.getId(), pRepo);
			final JsonNode reply = connector.execute(new ConnectorContext(null, pRepo.getRepository(), null, null), "contract", new LinkedHashMap<String, String>(), null);
			contract = Contract.parse(reply);
		}
		catch (final Exception ex)
		{
			contract = null;
		}
		CatalogDetection.CONTRACTS.put(key, new Object[] { Long.valueOf(now), contract });
		return contract;
	}

	// ------------------------------------------------------------------ reports

	/**
	 * Pure: what to tell the person (or agent) configuring a flow. Empty when there is nothing to do,
	 * and silent about the catalog when it is absent and no sink is one.
	 */
	public static List<String> advice(final Local pLocal, final int pSinks, final List<String> pCatalogSinks, final List<String> pRefusingSinks, final String pConnectorId,
		final String pNamespace, final String[] pSourceByNamespace, final List<String[]> pSourcesByConnector)
	{
		final List<String> out = new ArrayList<String>();
		if (pLocal.state == State.FOREIGN)
		{
			out.add(pLocal.reason + ". Nothing is proposed or written there; if the catalog is installed under another key, set -Debx.catalog.dataspace (and -Debx.catalog.dataset) on this JVM.");
		}
		if (pLocal.state == State.VERIFIED && pSinks == 0)
		{
			out.add("The ON EBX data catalog is deployed (" + pLocal.dataspace + "/" + pLocal.dataset + ") but no active connector of type openlineage sends it anything: "
				+ "create one with base URL <EBX URL>/ebx-data-catalog/rest/v1/openlineage and EBX Basic authentication, then run connectors_lineage_declare.");
		}
		if (pLocal.state == State.VERIFIED && pSinks > 0 && pCatalogSinks.isEmpty())
		{
			out.add("None of the openlineage connectors answered as the data catalog (GET on its base URL): check that one points at <EBX URL>/ebx-data-catalog/rest/v1/openlineage.");
		}
		for (final String sink : pRefusingSinks)
		{
			out.add("Sink " + sink + " is a data catalog that does not take declared lineage (no jobEvents, or its dataset is not its own): mappings reach it only when they run.");
		}
		if (pConnectorId != null && pNamespace != null && pLocal.state == State.VERIFIED && !pCatalogSinks.isEmpty() && pSourceByNamespace == null)
		{
			final StringBuilder b = new StringBuilder("No Source of the catalog has lineageNamespace '").append(pNamespace)
				.append("': the lineage of connector ").append(pConnectorId).append(" will land on a separate source created for that namespace. ");
			if (pSourcesByConnector.isEmpty())
			{
				b.append("Create the Source of this system in the catalog (connector ").append(pConnectorId).append(", lineageNamespace ").append(pNamespace).append(").")
					.append(" connectors_catalog_source(connector=").append(pConnectorId).append(", apply=true) does it.");
			}
			else
			{
				b.append("Set lineageNamespace to that value on ");
				for (int i = 0; i < pSourcesByConnector.size(); i++)
				{
					final String[] s = pSourcesByConnector.get(i);
					b.append(i == 0 ? "" : ", ").append("Source ").append(s[0]).append(" (now '").append(s[1] == null ? "" : s[1]).append("')");
				}
				b.append(". connectors_catalog_source(connector=").append(pConnectorId).append(") shows the change.");
			}
			out.add(b.toString());
		}
		if (pConnectorId != null && pNamespace != null && pLocal.state == State.VERIFIED && !pCatalogSinks.isEmpty() && pSourceByNamespace != null
			&& (pSourceByNamespace[1] == null || pSourceByNamespace[1].trim().isEmpty()))
		{
			// the namespace is held by a Source without connector (created from lineage events) while another Source names this connector: the system is listed twice
			final StringBuilder others = new StringBuilder();
			for (final String[] s : pSourcesByConnector)
			{
				if (!s[0].equals(pSourceByNamespace[0]))
				{
					others.append(others.length() == 0 ? "" : ", ").append("Source ").append(s[0]).append(" (now '").append(s[1] == null ? "" : s[1]).append("')");
				}
			}
			if (others.length() > 0)
			{
				out.add("The lineage of connector " + pConnectorId + " lands on Source " + pSourceByNamespace[0] + ", which has no connector (the catalog created it from lineage events for '"
					+ pNamespace + "'), while " + others + " names this connector: the catalog lists this system twice. Set lineageNamespace to '" + pNamespace + "' on the Source of the connector, "
					+ "then move the assets of " + pSourceByNamespace[0] + " and delete it (two Sources with the same namespace are ambiguous).");
			}
		}
		if (pLocal.state != State.VERIFIED && !pCatalogSinks.isEmpty() && pConnectorId != null && pNamespace != null)
		{
			out.add("The data catalog is on another EBX instance: make sure one of its Sources has lineageNamespace '" + pNamespace + "'.");
		}
		return out;
	}

	/**
	 * The {@code lineage} block of a mapping change (MCP {@code connectors_mapping_upsert} / {@code _delete}):
	 * what happens to its declared lineage, where it goes, and what is missing for the catalog to show it
	 * on the right assets. {@code pMapping} may be null (dry run, deleted mapping).
	 */
	public static ObjectNode report(final ConnectorRepository pRepo, final MappingDefinition pMapping, final String pConnectorId, final String pDeclaration)
	{
		final ObjectNode out = Json.object();
		out.put("declaration", DeclaredLineage.enabled() ? pDeclaration : "disabled (-Debx.connectors.lineage.declared=false)");
		try
		{
			final ConnectorConfig connector = pConnectorId == null ? null : pRepo.connector(pConnectorId);
			final String namespace = connector == null ? null : OpenLineageEmitter.namespaceOf(connector);
			if (namespace != null)
			{
				out.put("namespace", namespace);
			}
			CatalogDetection.describe(pRepo, out, pMapping, connector, namespace);
		}
		catch (final RuntimeException ex)
		{
			out.put("error", Redaction.redact(String.valueOf(ex.getMessage())));
		}
		return out;
	}

	/** The catalog part of {@code connectors_lineage_declare}: local state, sinks and their contracts, advice. */
	public static ObjectNode summary(final ConnectorRepository pRepo)
	{
		final ObjectNode out = Json.object();
		try
		{
			CatalogDetection.describe(pRepo, out, null, null, null);
		}
		catch (final RuntimeException ex)
		{
			out.put("error", Redaction.redact(String.valueOf(ex.getMessage())));
		}
		return out;
	}

	private static void describe(final ConnectorRepository pRepo, final ObjectNode pOut, final MappingDefinition pMapping, final ConnectorConfig pConnector, final String pNamespace)
	{
		final Local local = CatalogDetection.local(pRepo.getRepository());
		final ObjectNode catalog = pOut.putObject("catalog");
		catalog.put("state", local.state.name());
		catalog.put("dataspace", local.dataspace);
		catalog.put("dataset", local.dataset);
		if (local.reason != null && local.state != State.ABSENT)
		{
			catalog.put("reason", local.reason);
		}
		OpenLineageEmitter.invalidate();
		final List<ConnectorConfig> sinks = OpenLineageEmitter.targets(pRepo);
		final List<String> catalogSinks = new ArrayList<String>();
		final List<String> refusing = new ArrayList<String>();
		final ArrayNode items = pOut.putArray("sinks");
		for (final ConnectorConfig sink : sinks)
		{
			final ObjectNode item = items.addObject();
			item.put("id", sink.getId());
			final Contract contract = CatalogDetection.contractOf(pRepo, sink);
			item.put("endpoint", contract == null ? "no contract (not reachable, or not the data catalog)" : contract.catalog ? "ON EBX data catalog" : "OpenLineage endpoint");
			if (contract != null && contract.catalog)
			{
				catalogSinks.add(sink.getId());
				item.put("contractVersion", contract.version);
				final ArrayNode features = item.putArray("features");
				for (final String f : contract.features)
				{
					features.add(f);
				}
				if (contract.datasetState != null)
				{
					item.put("datasetState", contract.datasetState);
				}
				if (contract.refusesJobEvents())
				{
					refusing.add(sink.getId());
				}
			}
			if (pMapping != null && pConnector != null)
			{
				item.put("takesThisMapping", OpenLineageEmitter.Settings.of(sink).acceptsDeclaration(pMapping, pConnector, sink.getId())
					&& !(contract != null && contract.refusesJobEvents()));
			}
		}
		final String[] byNamespace = CatalogDetection.sourceByNamespace(local, pNamespace);
		final List<String[]> byConnector = pConnector == null ? Collections.<String[]>emptyList() : CatalogDetection.sourcesByConnector(local, pConnector.getId());
		if (byNamespace != null)
		{
			catalog.put("source", byNamespace[0]);
			catalog.put("sourceConnector", byNamespace[1]);
		}
		final ArrayNode advice = pOut.putArray("advice");
		for (final String a : CatalogDetection.advice(local, sinks.size(), catalogSinks, refusing, pConnector == null ? null : pConnector.getId(), pNamespace, byNamespace, byConnector))
		{
			advice.add(a);
		}
	}
}
