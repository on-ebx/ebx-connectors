package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationReference;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.instance.HomeCreationSpec;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import java.util.Map;
import java.util.HashMap;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.schema.SchemaLocation;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Creates the {@code Connectors} dataspace and dataset under the reference dataspace at module
 * startup when they are missing, and seeds them once with the GLEIF, Sirene and Google Geocoding
 * connectors plus the mappings for {@code /root/Companies}. Idempotent: existing records are
 * preserved, missing seed records are added, and additive migrations only initialise blank fields.
 */
public final class ConnectorsInstaller
{
	private ConnectorsInstaller()
	{
	}

	/**
	 * System property {@code ebx.connectors.seedMappings}: {@code auto} (default: the demonstration
	 * mappings are seeded only when their table exists, and only with the rules and inputs whose fields
	 * exist), {@code always} (seed everything, as written), {@code never} (connectors only).
	 */
	public static final String SEED_PROPERTY = "ebx.connectors.seedMappings";

	/**
	 * System property {@code ebx.connectors.seed}: {@code all} (default) lets the installer create the demonstration
	 * connectors, mappings and catalogue cards that it never created in this repository; {@code none} makes it create
	 * no connector and no mapping at all - what a production repository wants. Read at each install. Whatever the
	 * value, an item the installer created once is never created again ({@link SeedMarkers}).
	 */
	public static final String SEED_MODE_PROPERTY = "ebx.connectors.seed";

	static SeedMarkers.Mode seedMode()
	{
		return SeedMarkers.mode(System.getProperty(ConnectorsInstaller.SEED_MODE_PROPERTY));
	}

	/** What the installer created so far in the repository, read at the start of each install; see {@link SeedMarkers}. */
	private static SeedMarkers markers = new SeedMarkers(false);

	static String seedPolicy()
	{
		final String value = System.getProperty(ConnectorsInstaller.SEED_PROPERTY, "auto").trim().toLowerCase(java.util.Locale.ENGLISH);
		return "always".equals(value) || "never".equals(value) ? value : "auto";
	}

	/** Tables of the mappings created in the current seed run (id -> table node), and their direction. */
	private static final Map<Integer, SchemaNode> SEEDED_TABLES = new HashMap<Integer, SchemaNode>();
	private static final Map<Integer, String> SEEDED_DIRECTIONS = new HashMap<Integer, String>();
	private static final int SKIPPED = -1;

	/**
	 * True once {@link #install} ran to its end without an error in this class loader's life (module startup
	 * included). Reset by a module reload, since the class is reloaded with it.
	 */
	private static volatile boolean installComplete;

	/**
	 * Lazy installation from any entry point that has a session; safe to invoke repeatedly, and cheap after the
	 * first complete run.
	 *
	 * <p>perf/installer-once (2026-09-27): this used to run the whole {@link #install} on EVERY call - four
	 * procedures on the Connectors dataspace, about 85 ms, under the class lock - and every MCP tool call goes
	 * through it ({@code ConnectorsTools.invoke}). Opening one MDM Factory project wizard made 120 such calls
	 * (one {@code connectors_describe} per connector), about 10 s of the page's 11 s. {@link #install} is
	 * idempotent and runs at module startup, so once it has completed, a later call only needs to check that the
	 * dataset is still there (a dataspace deleted or replaced at runtime triggers a full run again). A model
	 * upgrade picked up without a restart (development mode) is seeded at the next module reload.
	 */
	public static void ensure(final Repository pRepository, final Session pSession)
	{
		if (ConnectorsInstaller.installComplete && ConnectorsInstaller.datasetPresent(pRepository == null ? Repository.getDefault() : pRepository))
		{
			return;
		}
		// C2-12: never under the caller's session (a reader's, with whatever rights it has) and never waiting for a lock:
		// the module's own privileged session, one run at a time, and after a failure one new attempt a minute
		final Session privileged = ConnectorsInstaller.installSession;
		if (privileged == null)
		{
			ConnectorsInstaller.warnNotInstalled("the module has not started: its installation session does not exist yet");
			return;
		}
		if (!ConnectorsInstaller.GATE.tryBegin(System.currentTimeMillis(), false))
		{
			if (ConnectorsInstaller.GATE.isWaiting(System.currentTimeMillis()))
			{
				ConnectorsInstaller.warnNotInstalled("the last installation failed, see the kernel log; the next attempt is within a minute");
			}
			return;
		}
		ConnectorsInstaller.run(pRepository == null ? Repository.getDefault() : pRepository, privileged, ConnectorsPaths.MODULE_NAME,
			LoggingCategory.getKernel());
	}

	/** One installation run at a time, started from {@link #ensure} and {@link #install}; the gate is already held. */
	private static void run(final Repository pRepository, final Session pSession, final String pModuleName, final LoggingCategory pLog)
	{
		boolean succeeded = false;
		try
		{
			ConnectorsInstaller.runInstall(pRepository, pSession, pModuleName, pLog);
			succeeded = ConnectorsInstaller.installComplete;
		}
		finally
		{
			ConnectorsInstaller.GATE.end(succeeded, System.currentTimeMillis());
		}
	}

	/** Which run may start, and when: see {@link InstallGate}. */
	private static final InstallGate GATE = new InstallGate();

	/**
	 * The session the installer runs under: a system-user session of this module, created at repository startup
	 * ({@code ModuleContextOnRepositoryStartup.createSystemUserSession}). The installer writes with every privilege
	 * on purpose (it creates the dataspace, the dataset and the seed rows), so it is never given the session of a
	 * request. Null before the module started and after it stopped.
	 */
	private static volatile Session installSession;
	private static volatile long lastWarningAt;

	/** Called by the module at repository startup, before the first {@link #install}. */
	public static void useInstallSession(final Session pSession)
	{
		ConnectorsInstaller.installSession = pSession;
	}

	/** Called when the module stops: no session of a stopped module is kept. */
	public static void forgetInstallSession()
	{
		ConnectorsInstaller.installSession = null;
	}

	private static void warnNotInstalled(final String pReason)
	{
		final long now = System.currentTimeMillis();
		if (now - ConnectorsInstaller.lastWarningAt >= InstallGate.RETRY_AFTER_MS)
		{
			ConnectorsInstaller.lastWarningAt = now;
			LoggingCategory.getKernel().warn("[connectors] module not installed: " + pReason + ".");
		}
	}

	/** True once the installer ran to its end in this module's life; false until then, or after a failed run. */
	public static boolean isInstallComplete()
	{
		return ConnectorsInstaller.installComplete;
	}

	private static boolean datasetPresent(final Repository pRepository)
	{
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(ConnectorsPaths.DATASPACE));
		return home != null && home.isOpen() && home.findAdaptationOrNull(AdaptationName.forName(ConnectorsPaths.DATASET)) != null;
	}

	/** True when a seeded connector is missing (the composite ones only once the model has the rev 3 fields). */
	static boolean needsSeed(final Adaptation pDataset)
	{
		final AdaptationTable connectors = pDataset.getTable(ConnectorsPaths.CONNECTOR);
		final boolean rev3 = pDataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING).getNode(Path.parse("./parent")) != null;
		for (final String id : new String[] { "Salesforce", "Kafka", "Snowflake", "Databricks", "SAP", "JDEdwards", "DnB", "VIES", "OpenFDA" })
		{
			if (!rev3 && !"Salesforce".equals(id))
			{
				continue;
			}
			if (ConnectorsInstaller.absent(pDataset, id))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The start of the module: always tries once, whatever happened before, and gives way to a run already in progress
	 * (it does not wait for it behind a lock).
	 */
	public static void install(final Repository pRepository, final Session pSession, final String pModuleName,
		final LoggingCategory pLog)
	{
		if (ConnectorsInstaller.GATE.tryBegin(System.currentTimeMillis(), true))
		{
			ConnectorsInstaller.run(pRepository, pSession, pModuleName, pLog);
		}
	}

	private static void runInstall(final Repository pRepository, final Session pSession, final String pModuleName,
		final LoggingCategory pLog)
	{
		ConnectorsInstaller.installComplete = false;
		ConnectorsInstaller.SEEDED_TABLES.clear();
		ConnectorsInstaller.SEEDED_DIRECTIONS.clear();
		try
		{
			AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(ConnectorsPaths.DATASPACE));
			if (home == null)
			{
				final HomeCreationSpec spec = new HomeCreationSpec();
				spec.setKey(HomeKey.forBranchName(ConnectorsPaths.DATASPACE));
				spec.setParent(pRepository.getReferenceBranch());
				spec.setOwner(Profile.ADMINISTRATOR);
				spec.setLabel(UserMessage.createInfo("Connectors"));
				spec.setDescription(UserMessage.createInfo("External application connectors: endpoints, mappings, sync log."));
				home = pRepository.createHome(spec, pSession);
				pLog.info("[connectors] dataspace Connectors created");
			}
			Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(ConnectorsPaths.DATASET));
			if (dataset != null && !ConnectorsInstaller.belongsTo(dataset, pModuleName))
			{
				// Never destroy configuration during module startup. An older installation may package
				// the same model in another module; migration then requires an explicit export/import.
				pLog.error("[connectors] installation stopped: dataset Connectors uses the data model of another module ("
					+ ConnectorsInstaller.schemaOf(dataset) + "), not " + pModuleName
					+ ". Existing data was preserved. Export it, migrate the model explicitly, then restart.");
				return;
			}
			if (home == null)
			{
				final HomeCreationSpec spec = new HomeCreationSpec();
				spec.setKey(HomeKey.forBranchName(ConnectorsPaths.DATASPACE));
				spec.setParent(pRepository.getReferenceBranch());
				spec.setOwner(Profile.ADMINISTRATOR);
				spec.setLabel(UserMessage.createInfo("Connectors"));
				spec.setDescription(UserMessage.createInfo("External application connectors: endpoints, mappings, sync log."));
				home = pRepository.createHome(spec, pSession);
				pLog.info("[connectors] dataspace Connectors created");
			}
			if (dataset == null)
			{
				final AdaptationHome target = home;
				final ProcedureResult result = ProgrammaticService.createForSession(pSession, home).execute(new Procedure()
				{
					@Override
					public void execute(final ProcedureContext pContext) throws Exception
					{
						pContext.setAllPrivileges(true);
						final Adaptation created = pContext.doCreateRoot(
							SchemaLocation.forPathInModule(ConnectorsPaths.MODEL_PATH, pModuleName),
							AdaptationReference.forPersistentName(ConnectorsPaths.DATASET), Profile.ADMINISTRATOR);
						pContext.setInstanceLabel(created, UserMessage.createInfo("Connectors"));
					}
				});
				if (result.hasFailed())
				{
					pLog.error("[connectors] dataset creation failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
					return;
				}
				dataset = target.findAdaptationOrNull(AdaptationName.forName(ConnectorsPaths.DATASET));
				pLog.info("[connectors] dataset Connectors created");
			}
			final boolean seedAll = ConnectorsInstaller.seedMode() == SeedMarkers.Mode.ALL;
			if (dataset != null)
			{
				ConnectorsInstaller.markers = ConnectorsInstaller.loadMarkers(dataset);
				ConnectorsInstaller.recordBaseline(pSession, dataset, pLog);
				if (!seedAll)
				{
					pLog.info("[connectors] -D" + ConnectorsInstaller.SEED_MODE_PROPERTY + "=none: no connector, mapping or catalogue card is seeded");
				}
			}
			if (seedAll && dataset != null && ConnectorsInstaller.isEmpty(dataset.getTable(ConnectorsPaths.CONNECTOR))
				&& ConnectorsInstaller.markers.mayCreateConnector("GLEIF", false))
			{
				ConnectorsInstaller.seed(pSession, dataset, pLog);
			}

			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "Salesforce"))
			{
				ConnectorsInstaller.seedSalesforce(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "Kafka")
				&& dataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING).getNode(Path.parse("./parent")) != null)
			{
				ConnectorsInstaller.seedKafka(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "Snowflake")
				&& dataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING).getNode(Path.parse("./parent")) != null)
			{
				ConnectorsInstaller.seedSnowflake(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "Databricks")
				&& dataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING).getNode(Path.parse("./parent")) != null)
			{
				ConnectorsInstaller.seedDatabricks(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "SAP")
				&& dataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING).getNode(Path.parse("./parent")) != null)
			{
				ConnectorsInstaller.seedSap(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "JDEdwards")
				&& dataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING).getNode(Path.parse("./parent")) != null)
			{
				ConnectorsInstaller.seedJde(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "DnB")
				&& dataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING).getNode(Path.parse("./parent")) != null)
			{
				ConnectorsInstaller.seedDnb(pSession, dataset, pLog);
			}
			else if (seedAll && dataset != null && ConnectorsInstaller.rowExists(dataset, "DnB")
				&& ConnectorsInstaller.markers.mayAddMappings("DnB", !ConnectorsInstaller.hasMappingFor(dataset, "DnB", "match")
				|| !ConnectorsInstaller.hasMappingFor(dataset, "DnB", "bestMatch")
				|| !ConnectorsInstaller.hasMappingFor(dataset, "DnB", "enrich"))
				&& dataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING).getNode(Path.parse("./parent")) != null)
			{
				// Older repositories can already contain the connector and its governed credentials but
				// predate the modern Company/DUNS mappings. Backfill only the missing mappings: never
				// recreate the connector or overwrite customer-owned secrets and runtime properties.
				ConnectorsInstaller.seedDnbMappings(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && (ConnectorsInstaller.absent(dataset, "VIES")
				|| ConnectorsInstaller.absent(dataset, "SQLServer")
				|| ConnectorsInstaller.absent(dataset, "AzureSQL")
				|| ConnectorsInstaller.absent(dataset, "AzureServiceBus")
				|| ConnectorsInstaller.absent(dataset, "NetSuite")
				|| ConnectorsInstaller.absent(dataset, "SharePoint")
				|| ConnectorsInstaller.absent(dataset, "MySQL"))
				&& dataset.getSchemaNode().getNode(ConnectorsPaths.MAPPING).getNode(Path.parse("./parent")) != null)
			{
				ConnectorsInstaller.seedCatalog(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "OpenFDA"))
			{
				ConnectorsInstaller.seedLot2(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && (ConnectorsInstaller.absent(dataset, "Gemini")
				|| ConnectorsInstaller.absent(dataset, "Mistral")))
			{
				ConnectorsInstaller.seedLlmProviders(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "PowerBI"))
			{
				ConnectorsInstaller.seedPowerBi(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "Collibra"))
			{
				ConnectorsInstaller.seedCollibra(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "Slack"))
			{
				ConnectorsInstaller.seedSlack(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.absent(dataset, "EbxMcpDataModel"))
			{
				ConnectorsInstaller.seedEbxMcpServers(pSession, dataset, pLog);
			}
			if (seedAll && dataset != null && ConnectorsInstaller.markers.mayAddMappings("Melissa", !ConnectorsInstaller.hasMappingFor(dataset, "Melissa"))
				&& ConnectorsInstaller.rowExists(dataset, "Melissa"))
			{
				ConnectorsInstaller.seedAddressNormalisation(pSession, dataset, pLog);
			}
			if (dataset != null)
			{
				// Connector implementations are type metadata; the catalog itself is data-backed.
				// Add every wave record never created, so an upgraded repository receives new cards once,
				// without replacing customer-owned rows, credentials or properties - and a card the customer
				// deleted stays deleted (SeedMarkers).
				if (seedAll)
				{
					ConnectorsInstaller.seedWaveCatalog(pSession, dataset, pLog);
				}
				ConnectorsInstaller.migrate(pSession, dataset, pLog); // after every seed: placeholders, trust levels, logos
				ConnectorsInstaller.dropIncompleteFieldProvenanceRows(pSession, dataset, pLog); // volume lot 5: FieldProvenance primary-key change
				ConnectorsInstaller.seedReleaseNotes(pSession, dataset, pLog);
				ConnectorsInstaller.installComplete = true;
			}
		}
		catch (final Throwable ex)
		{
			pLog.error("[connectors] installation failed: " + ex, ex instanceof Exception ? (Exception) ex : null);
		}
	}

	// ------------------------------------------------------------------ seed markers (audit C-26)

	/** True when the model has the SeedMarker table (a model not reloaded since the upgrade does not). */
	private static boolean markerTablePresent(final Adaptation pDataset)
	{
		return pDataset.getSchemaNode().getNode(ConnectorsPaths.SEED_MARKER) != null;
	}

	/** Reads the markers once per install: a handful of rows. */
	static SeedMarkers loadMarkers(final Adaptation pDataset)
	{
		final SeedMarkers markers = new SeedMarkers(ConnectorsInstaller.markerTablePresent(pDataset));
		if (markers.isSupported())
		{
			for (final Adaptation row : ConnectorsInstaller.rows(pDataset.getTable(ConnectorsPaths.SEED_MARKER).createRequest()))
			{
				markers.add(row.getString(ConnectorsPaths.SeedMarker.ITEM));
			}
		}
		return markers;
	}

	private static boolean rowExists(final Adaptation pDataset, final String pConnectorId)
	{
		return pDataset.getTable(ConnectorsPaths.CONNECTOR).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pConnectorId)) != null;
	}

	/** The installer may still create this connector: no row, and never created in this repository. */
	private static boolean absent(final Adaptation pDataset, final String pConnectorId)
	{
		return ConnectorsInstaller.markers.mayCreateConnector(pConnectorId, ConnectorsInstaller.rowExists(pDataset, pConnectorId));
	}

	/**
	 * First start of the version that records markers: every connector already in the repository, and the mapping groups
	 * whose mappings are all there, count as created, so that one the customer deletes later is not created again. A
	 * connector absent now is not marked: it was never there, and is created once. Written in one procedure, then
	 * remembered; a failure leaves the baseline for the next start.
	 */
	private static void recordBaseline(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		if (!ConnectorsInstaller.markers.isSupported() || ConnectorsInstaller.markers.baselineDone())
		{
			return;
		}
		final List<String> ids = new ArrayList<String>();
		for (final Adaptation row : ConnectorsInstaller.rows(pDataset.getTable(ConnectorsPaths.CONNECTOR).createRequest()))
		{
			ids.add(row.getString(ConnectorsPaths.Connector.ID));
		}
		final List<String> groups = new ArrayList<String>();
		if (ConnectorsInstaller.hasMappingFor(pDataset, "DnB", "match") && ConnectorsInstaller.hasMappingFor(pDataset, "DnB", "bestMatch")
			&& ConnectorsInstaller.hasMappingFor(pDataset, "DnB", "enrich"))
		{
			groups.add("DnB");
		}
		if (ConnectorsInstaller.hasMappingFor(pDataset, "Melissa"))
		{
			groups.add("Melissa");
		}
		final List<String> items = ConnectorsInstaller.markers.baseline(ids, groups);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final String item : items)
				{
					ConnectorsInstaller.mark(pContext, pDataset, item);
				}
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] seed markers could not be recorded: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
			ConnectorsInstaller.markers = ConnectorsInstaller.loadMarkers(pDataset);
		}
		else
		{
			pLog.info("[connectors] seed markers recorded for " + (items.size() - 1) + " existing item(s): a connector or mapping group deleted from now on is not seeded again");
		}
	}

	/** Records, inside the caller's procedure, that the installer created {@code pItem}. Does nothing without the table. */
	private static void mark(final ProcedureContext pContext, final Adaptation pDataset, final String pItem) throws Exception
	{
		if (!ConnectorsInstaller.markers.isSupported() || ConnectorsInstaller.markers.has(pItem))
		{
			return;
		}
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.SEED_MARKER);
		if (table.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pItem)) == null)
		{
			final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
			row.setValue(pItem, ConnectorsPaths.SeedMarker.ITEM);
			row.setValue(new java.util.Date(), ConnectorsPaths.SeedMarker.SEEDED_AT);
			pContext.doCreateOccurrence(row, table);
		}
		ConnectorsInstaller.markers.add(pItem);
	}

	/** Adds immutable packaged release records. Existing packaged or customer rows are never modified. */
	static void seedReleaseNotes(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		if (pDataset.getSchemaNode().getNode(ConnectorsPaths.RELEASE) == null) return;
		final int[] created = new int[5];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final PackagedReleaseNotes.Release release : PackagedReleaseNotes.all())
				{
					final AdaptationTable releases = pDataset.getTable(ConnectorsPaths.RELEASE);
					final Adaptation existing = releases.lookupAdaptationByPrimaryKey(
						com.onwbp.adaptation.PrimaryKey.parseString(release.version));
					final String ownership = existing == null ? null : existing.getString(ConnectorsPaths.Release.OWNERSHIP);
					if (!PackagedReleaseNotes.seedPackagedChildren(ownership)) continue;
					if (PackagedReleaseNotes.installPackagedRelease(ownership))
					{
						final ValueContextForUpdate header = pContext.getContextForNewOccurrence(releases);
						header.setValue(release.version, ConnectorsPaths.Release.VERSION);
						header.setValue("PACKAGED", ConnectorsPaths.Release.OWNERSHIP);
						if (release.releaseDate != null && !release.releaseDate.isEmpty()) header.setValueFromXsString(release.releaseDate, ConnectorsPaths.Release.RELEASE_DATE);
						header.setValue(release.status, ConnectorsPaths.Release.STATUS); header.setValue(release.title, ConnectorsPaths.Release.TITLE); header.setValue(release.summary, ConnectorsPaths.Release.SUMMARY);
						pContext.doCreateOccurrence(header, releases); created[0]++;
					}

					for (final PackagedReleaseNotes.Item item : release.items)
					{
						final AdaptationTable table = pDataset.getTable(ConnectorsPaths.RELEASE_ITEM);
						if (!PackagedReleaseNotes.createMissingChild(ConnectorsInstaller.releaseChildExists(table, new Object[] { release.version, item.id }, new Path[] { ConnectorsPaths.ReleaseItem.RELEASE, ConnectorsPaths.ReleaseItem.ID }))) continue;
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
						value.setValue(release.version, ConnectorsPaths.ReleaseItem.RELEASE); value.setValue(item.id, ConnectorsPaths.ReleaseItem.ID);
						value.setValue("PACKAGED", ConnectorsPaths.ReleaseItem.OWNERSHIP); value.setValue(item.type, ConnectorsPaths.ReleaseItem.TYPE);
						value.setValue(Integer.valueOf(item.order), ConnectorsPaths.ReleaseItem.DISPLAY_ORDER); value.setValue(item.title, ConnectorsPaths.ReleaseItem.TITLE);
						value.setValue(item.detail, ConnectorsPaths.ReleaseItem.DETAIL); pContext.doCreateOccurrence(value, table); created[1]++;
					}
					for (final PackagedReleaseNotes.Metric metric : release.metrics)
					{
						final AdaptationTable table = pDataset.getTable(ConnectorsPaths.RELEASE_METRIC);
						if (!PackagedReleaseNotes.createMissingChild(ConnectorsInstaller.releaseChildExists(table, new Object[] { release.version, metric.code }, new Path[] { ConnectorsPaths.ReleaseMetric.RELEASE, ConnectorsPaths.ReleaseMetric.CODE }))) continue;
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
						value.setValue(release.version, ConnectorsPaths.ReleaseMetric.RELEASE); value.setValue(metric.code, ConnectorsPaths.ReleaseMetric.CODE);
						value.setValue("PACKAGED", ConnectorsPaths.ReleaseMetric.OWNERSHIP); value.setValue(metric.label, ConnectorsPaths.ReleaseMetric.LABEL);
						value.setValue(metric.value, ConnectorsPaths.ReleaseMetric.VALUE); value.setValue(Integer.valueOf(metric.order), ConnectorsPaths.ReleaseMetric.DISPLAY_ORDER);
						pContext.doCreateOccurrence(value, table); created[2]++;
					}
					for (final PackagedReleaseNotes.PassStatus pass : release.passes)
					{
						final AdaptationTable table = pDataset.getTable(ConnectorsPaths.RELEASE_PASS_STATUS);
						if (!PackagedReleaseNotes.createMissingChild(ConnectorsInstaller.releaseChildExists(table, new Object[] { release.version, pass.code }, new Path[] { ConnectorsPaths.ReleasePassStatus.RELEASE, ConnectorsPaths.ReleasePassStatus.CODE }))) continue;
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
						value.setValue(release.version, ConnectorsPaths.ReleasePassStatus.RELEASE); value.setValue(pass.code, ConnectorsPaths.ReleasePassStatus.CODE);
						value.setValue("PACKAGED", ConnectorsPaths.ReleasePassStatus.OWNERSHIP); value.setValue(pass.label, ConnectorsPaths.ReleasePassStatus.LABEL);
						value.setValue(pass.status, ConnectorsPaths.ReleasePassStatus.STATUS); value.setValue(Integer.valueOf(pass.progress), ConnectorsPaths.ReleasePassStatus.PROGRESS);
						value.setValue(pass.remainingGates, ConnectorsPaths.ReleasePassStatus.REMAINING_GATES); value.setValue(Integer.valueOf(pass.order), ConnectorsPaths.ReleasePassStatus.DISPLAY_ORDER);
						pContext.doCreateOccurrence(value, table); created[3]++;
					}
					for (final PackagedReleaseNotes.PassStep step : release.steps)
					{
						final AdaptationTable table = pDataset.getTable(ConnectorsPaths.RELEASE_PASS_STEP);
						if (!PackagedReleaseNotes.createMissingChild(ConnectorsInstaller.releaseChildExists(table, new Object[] { release.version, step.passCode, step.code }, new Path[] { ConnectorsPaths.ReleasePassStep.RELEASE, ConnectorsPaths.ReleasePassStep.PASS_CODE, ConnectorsPaths.ReleasePassStep.CODE }))) continue;
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
						value.setValue(release.version, ConnectorsPaths.ReleasePassStep.RELEASE); value.setValue(step.passCode, ConnectorsPaths.ReleasePassStep.PASS_CODE); value.setValue(step.code, ConnectorsPaths.ReleasePassStep.CODE);
						value.setValue("PACKAGED", ConnectorsPaths.ReleasePassStep.OWNERSHIP); value.setValue(step.label, ConnectorsPaths.ReleasePassStep.LABEL); value.setValue(step.status, ConnectorsPaths.ReleasePassStep.STATUS);
						if (step.occurredOn != null && !step.occurredOn.isEmpty()) value.setValueFromXsString(step.occurredOn, ConnectorsPaths.ReleasePassStep.OCCURRED_ON);
						value.setValue(step.detail, ConnectorsPaths.ReleasePassStep.DETAIL); value.setValue(Integer.valueOf(step.order), ConnectorsPaths.ReleasePassStep.DISPLAY_ORDER);
						pContext.doCreateOccurrence(value, table); created[4]++;
					}
				}
			}
		});
		if (result.hasFailed()) pLog.error("[connectors] release-note seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		else if (created[0] + created[1] + created[2] + created[3] + created[4] > 0) pLog.info("[connectors] release notes seeded: " + created[0] + " release(s), " + created[1] + " item(s), " + created[2] + " metric(s), " + created[3] + " pass status row(s), " + created[4] + " pass step(s)");
	}

	private static boolean releaseChildExists(final AdaptationTable pTable, final Object[] pValues, final Path[] pPaths)
	{
		final com.orchestranetworks.schema.SchemaNode[] nodes = new com.orchestranetworks.schema.SchemaNode[pPaths.length];
		for (int i = 0; i < pPaths.length; i++) nodes[i] = pTable.getTableOccurrenceRootNode().getNode(pPaths[i]);
		return pTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(
			com.onwbp.adaptation.PrimaryKey.parseObjects(pValues, nodes))) != null;
	}

	/** Default trust of a connector type, when the row has none (new field): official registers first. */
	static int defaultTrust(final String pType)
	{
		final Integer legacy = ConnectorsInstaller.legacyDefaultTrust(pType);
		if (legacy != null) return legacy.intValue();
		try
		{
			final Integer descriptorTrust = com.on.ebx.connect.core.DescriptorConfiguration.trustLevel(
				com.on.ebx.connect.core.ConnectorRegistry.get().describeType(pType).getDescriptor());
			if (descriptorTrust != null) return descriptorTrust.intValue();
		}
		catch (final Exception ex)
		{
			// Preserve historical values when an extension type or optional implementation is unavailable.
		}
		return 50;
	}

	/** Compatibility values used by existing seeded connector records. */
	private static Integer legacyDefaultTrust(final String pType)
	{
		final String t = pType == null ? "" : pType;
		if (t.equals("gleif") || t.equals("sirene") || t.equals("companies-house")) { return 90; }
		if (t.equals("dnb") || t.equals("orbis") || t.equals("handelsregister")) { return 85; }
		if (t.equals("vies") || t.equals("creditsafe") || t.equals("opensanctions")) { return 80; }
		if (t.equals("melissa") || t.equals("loqate")) { return 75; }
		if (t.equals("ban") || t.equals("opencorporates") || t.equals("sap-odata") || t.equals("jde-ais")) { return 70; }
		if (t.equals("google-geocoding") || t.equals("salesforce") || t.equals("dynamics365") || t.equals("oracle-fusion") || t.equals("hubspot") || t.equals("servicenow") || t.equals("workday")) { return 60; }
		if (t.equals("inpi")) { return 90; }
		if (t.equals("openfda")) { return 85; }
		if (t.equals("ebx")) { return 70; }
		if (t.equals("wikidata")) { return 40; }
		if (t.equals("llm")) { return 30; }
		return null;
	}

	/** Catalog category inferred once for seeded and pre-existing connectors; users may override it. */
	/**
	 * Category of a seeded connector: an explicit per-id value for the few seeds whose {@code type} is a
	 * shared generic transport ({@code soap}, {@code graphql}, {@code rest}) and therefore says nothing
	 * about what the connector is for, else {@link #defaultCategory}. Kept in code so a re-seed of the
	 * dataset cannot drop these back to GENERIC_INTEGRATION again (that is exactly what happened on
	 * 2026-09-12: the values had only been corrected in data).
	 */
	static String seedCategory(final String pId, final String pType)
	{
		if ("Countries".equals(pId)) { return "REFERENCE_DATA"; }
		if ("EORI".equals(pId)) { return "RISK_COMPLIANCE"; }
		return ConnectorsInstaller.defaultCategory(pType);
	}

	/**
	 * How the external service behind a connector type is billed, set only where public knowledge of
	 * the service is confident enough to state as fact (never guessed): an official open-data
	 * registry or public utility is {@code FREE}; a service with a small free tier that requires
	 * payment beyond it, or whose commercial use requires a paid licence, is {@code FREEMIUM}; a
	 * commercial data provider is {@code PAID}. Everything else, including every generic transport
	 * (rest/soap/graphql/jdbc/kafka/sftp/s3/...) and every product a customer already runs and pays
	 * for on their own terms (Salesforce, SAP, a database, another EBX...), stays {@code UNKNOWN}: the
	 * notion of "pricing of the connector" does not apply to the module's own choice for those.
	 */
	static String defaultPricing(final String pType)
	{
		final String t = pType == null ? "" : pType;
		if (t.equals("gleif") || t.equals("sirene") || t.equals("vies") || t.equals("ban") || t.equals("inpi")
			|| t.equals("openfda") || t.equals("companies-house") || t.equals("handelsregister")
			|| t.equals("wikidata") || t.equals("nominatim"))
		{
			return "FREE";
		}
		if (t.equals("opencorporates") || t.equals("opensanctions"))
		{
			return "FREEMIUM";
		}
		if (t.equals("google-geocoding") || t.equals("melissa") || t.equals("loqate") || t.equals("dnb")
			|| t.equals("orbis") || t.equals("creditsafe"))
		{
			return "PAID";
		}
		return "UNKNOWN";
	}

	/**
	 * Pricing of a seeded connector: an explicit per-id value for the few seeds whose {@code type} is
	 * a shared generic transport ({@code soap}, {@code graphql}) and therefore says nothing about the
	 * external service, else {@link #defaultPricing}.
	 */
	static String seedPricing(final String pId, final String pType)
	{
		if ("EORI".equals(pId)) { return "FREE"; } // EU customs public reference data (SOAP), no key
		if ("Countries".equals(pId)) { return "FREE"; } // public demo GraphQL reference API, no key
		return ConnectorsInstaller.defaultPricing(pType);
	}

	/** Free-text nuance to go with {@link #seedPricing}, or null when the value needs none. */
	static String seedPricingNote(final String pId, final String pType)
	{
		final String t = pType == null ? "" : pType;
		if (t.equals("opencorporates"))
		{
			return "Free tier capped at a small number of monthly requests; paid plans beyond that.";
		}
		if (t.equals("opensanctions"))
		{
			return "Free for non-commercial use under rate limits; commercial use requires a paid licence.";
		}
		if (t.equals("nominatim"))
		{
			return "Public OpenStreetMap instance: usage policy caps it at about 1 request/second and requires attribution.";
		}
		if (t.equals("handelsregister"))
		{
			return "OffeneRegister community mirror, not the official Handelsregister portal.";
		}
		if ("Countries".equals(pId))
		{
			return "Public demo endpoint (countries.trevorblades.com), no key.";
		}
		return null;
	}

	static String defaultCategory(final String pType)
	{
		try
		{
			final com.on.ebx.connect.spi.ConnectorDescriptor descriptor = com.on.ebx.connect.core.ConnectorRegistry.get()
				.describeType(pType).getDescriptor();
			final String category = com.on.ebx.connect.core.DescriptorConfiguration.category(descriptor);
			if (category != null)
			{
				return category;
			}
		}
		catch (final Exception ex)
		{
			// Optional connector libraries (for example Kafka or SFTP) can be absent at install time.
			// Retain the historical fallback so an additive migration never changes their category.
		}
		return ConnectorsInstaller.legacyDefaultCategory(pType);
	}

	/** Compatibility fallback for unknown types and connector types whose optional library is absent. */
	private static String legacyDefaultCategory(final String pType)
	{
		final String t = pType == null ? "" : pType;
		if (t.equals("llm") || t.equals("mcp")) { return "AI"; }
		if (t.equals("s3") || t.equals("azure-blob") || t.equals("gcs") || t.equals("sftp")) { return "CLOUD_STORAGE"; }
		if (t.equals("jdbc") || t.equals("postgresql") || t.equals("mysql") || t.equals("h2") || t.equals("oracle-database") || t.equals("sql-server") || t.equals("azure-sql")) { return "DATABASE"; }
		if (t.equals("bigquery") || t.equals("snowflake") || t.equals("databricks") || t.equals("powerbi") || t.equals("collibra")) { return "DATA_PLATFORMS"; }
		if (t.equals("kafka") || t.equals("rabbitmq") || t.equals("jms") || t.equals("activemq") || t.equals("azure-service-bus") || t.equals("webhook") || t.equals("chat-webhook") || t.equals("slack")) { return "MESSAGING"; }
		if (t.equals("salesforce") || t.equals("dynamics365") || t.equals("dynamics-business-central") || t.equals("oracle-fusion") || t.equals("netsuite") || t.equals("sharepoint") || t.equals("hubspot") || t.equals("servicenow")
			|| t.equals("workday") || t.equals("sap-odata") || t.equals("sap-successfactors") || t.equals("sap-ariba") || t.equals("jde-ais")) { return "BUSINESS_APPS"; }
		if (t.equals("ban") || t.equals("google-geocoding") || t.equals("loqate") || t.equals("melissa") || t.equals("wikidata") || t.equals("nominatim")) { return "REFERENCE_DATA"; }
		if (t.equals("opensanctions") || t.equals("openfda") || t.equals("vies") || t.equals("creditsafe")) { return "RISK_COMPLIANCE"; }
		if (t.equals("gleif") || t.equals("sirene") || t.equals("companies-house") || t.equals("dnb") || t.equals("orbis") || t.equals("handelsregister")
			|| t.equals("inpi") || t.equals("opencorporates")) { return "CORPORATE_REGISTRIES"; }
		return "GENERIC_INTEGRATION";
	}

	/** Additive data fixes. Existing non-null customer values are never overwritten. */
	static void migrate(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final boolean hasTrust = pDataset.getSchemaNode().getNode(ConnectorsPaths.CONNECTOR).getNode(ConnectorsPaths.Connector.TRUST) != null;
		final boolean hasLogo = pDataset.getSchemaNode().getNode(ConnectorsPaths.CONNECTOR).getNode(ConnectorsPaths.Connector.LOGO) != null;
		final boolean hasCategory = pDataset.getSchemaNode().getNode(ConnectorsPaths.CONNECTOR_CATEGORY) != null
			&& pDataset.getSchemaNode().getNode(ConnectorsPaths.CONNECTOR).getNode(ConnectorsPaths.Connector.CATEGORY) != null;
		final boolean hasPricing = pDataset.getSchemaNode().getNode(ConnectorsPaths.CONNECTOR).getNode(ConnectorsPaths.Connector.PRICING) != null;
		final int[] counts = new int[6];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				if (hasCategory)
				{
					final String[][] categories = {
						{ "AI", "AI & machine learning", "Language models and intelligent enrichment services", "10" },
						{ "BUSINESS_APPS", "Business applications", "CRM, ERP, HCM and enterprise SaaS platforms", "20" },
						{ "CLOUD_STORAGE", "Cloud & file storage", "Object storage, managed files and secure file transfer", "30" },
						{ "DATA_PLATFORMS", "Data platforms", "Databases, warehouses and lakehouse platforms", "40" },
						{ "DATABASE", "Database", "Relational databases and managed SQL services", "45" },
						{ "MESSAGING", "Messaging & events", "Event streams, webhooks and notifications", "50" },
						{ "REFERENCE_DATA", "Location & reference data", "Address, geocoding and public reference sources", "60" },
						{ "CORPORATE_REGISTRIES", "Corporate registries", "Company identity and official registry services", "70" },
						{ "RISK_COMPLIANCE", "Risk & compliance", "Verification, sanctions, tax and regulatory sources", "80" },
						{ "GENERIC_INTEGRATION", "Generic integration", "General-purpose APIs and EBX-to-EBX connectivity", "90" }
					};
					final AdaptationTable categoryTable = pDataset.getTable(ConnectorsPaths.CONNECTOR_CATEGORY);
					for (final String[] category : categories)
					{
						if (categoryTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(category[0])) != null) { continue; }
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(categoryTable);
						value.setValue(category[0], ConnectorsPaths.ConnectorCategory.CODE);
						value.setValue(category[1], ConnectorsPaths.ConnectorCategory.LABEL);
						value.setValue(category[2], ConnectorsPaths.ConnectorCategory.DESCRIPTION);
						value.setValue(Integer.valueOf(category[3]), ConnectorsPaths.ConnectorCategory.DISPLAY_ORDER);
						pContext.doCreateOccurrence(value, categoryTable);
						counts[3]++;
					}
				}
				final List<Adaptation> all = ConnectorsInstaller.rows(pDataset.getTable(ConnectorsPaths.CONNECTOR).createRequest());
				for (final Adaptation row : all)
				{
					boolean touched = false;
					final ValueContextForUpdate update = pContext.getContext(row.getAdaptationName());
					// AuthenticationMethod describes a connector capability; authType is the
					// persisted EBX runtime setting and has the smaller OAUTH2 vocabulary.
					if ("OAUTH2_CLIENT_CREDENTIALS".equals(row.getString(ConnectorsPaths.Connector.AUTH_TYPE)))
					{
						update.setValue("OAUTH2", ConnectorsPaths.Connector.AUTH_TYPE);
						touched = true;
						counts[0]++;
					}
					for (final Path secret : new Path[] { ConnectorsPaths.Connector.USERNAME, ConnectorsPaths.Connector.PASSWORD, ConnectorsPaths.Connector.API_KEY })
					{
						if ("A_RENSEIGNER".equals(row.getString(secret)))
						{
							update.setValue("TO_BE_SET", secret);
							touched = true;
							counts[0]++;
						}
					}
					final String connectorId = row.getString(ConnectorsPaths.Connector.ID);
					final String preferredLogo = com.on.ebx.connect.ebx.ui.Logos.preferredFor(connectorId);
					String currentLogo = null;
					if (hasLogo && row.get(ConnectorsPaths.Connector.LOGO) != null)
					{
						currentLogo = row.getSchemaNode().getNode(ConnectorsPaths.Connector.LOGO)
							.formatToXsString(row.get(ConnectorsPaths.Connector.LOGO));
					}
					final String generatedLogo = com.on.ebx.connect.ebx.ui.Logos.generatedFor(connectorId);
					final boolean replaceGeneratedEbxLogo = "EBX".equals(connectorId)
						&& generatedLogo != null && generatedLogo.equals(currentLogo);
					if (hasLogo && preferredLogo != null && (currentLogo == null || replaceGeneratedEbxLogo))
					{
						update.setValueFromXsString(preferredLogo, ConnectorsPaths.Connector.LOGO);
						touched = true;
						counts[2]++;
					}
					if (hasTrust && row.get(ConnectorsPaths.Connector.TRUST) == null)
					{
						update.setValue(Integer.valueOf(ConnectorsInstaller.defaultTrust(row.getString(ConnectorsPaths.Connector.TYPE))), ConnectorsPaths.Connector.TRUST);
						touched = true;
						counts[1]++;
					}
					if (hasCategory && row.get(ConnectorsPaths.Connector.CATEGORY) == null)
					{
						update.setValue(ConnectorsInstaller.seedCategory(row.getString(ConnectorsPaths.Connector.ID), row.getString(ConnectorsPaths.Connector.TYPE)), ConnectorsPaths.Connector.CATEGORY);
						touched = true;
						counts[3]++;
					}
					if (hasPricing)
					{
						// null (field new to this row) and UNKNOWN (the schema default) are indistinguishable
						// from "nobody has set this yet", so only those two are filled in; a row already set
						// to FREE/FREEMIUM/PAID by a person is never overwritten by this migration.
						final String currentPricing = row.getString(ConnectorsPaths.Connector.PRICING);
						if (currentPricing == null || "UNKNOWN".equals(currentPricing))
						{
							final String seeded = ConnectorsInstaller.seedPricing(connectorId, row.getString(ConnectorsPaths.Connector.TYPE));
							if (!"UNKNOWN".equals(seeded))
							{
								update.setValue(seeded, ConnectorsPaths.Connector.PRICING);
								final String currentNote = row.getString(ConnectorsPaths.Connector.PRICING_NOTE);
								final String note = ConnectorsInstaller.seedPricingNote(connectorId, row.getString(ConnectorsPaths.Connector.TYPE));
								if (note != null && (currentNote == null || currentNote.trim().isEmpty()))
								{
									update.setValue(note, ConnectorsPaths.Connector.PRICING_NOTE);
								}
								touched = true;
								counts[5]++;
							}
						}
					}
					if (touched)
					{
						pContext.doModifyContent(row, update);
					}
				}
				for (final Adaptation mapping : ConnectorsInstaller.rows(pDataset.getTable(ConnectorsPaths.MAPPING).createRequest()))
				{
					final String storedDataspace = mapping.getString(ConnectorsPaths.Mapping.DATASPACE);
					final String datasetName = mapping.getString(ConnectorsPaths.Mapping.DATASET);
					final String tablePath = mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH);
					final MappingTarget target = ConnectorsInstaller.resolveMappingDataspace(
						pDataset.getHome().getRepository(), storedDataspace, datasetName, tablePath);
					final String resolvedDataspace = target.dataspace;
					if (target.warning != null)
					{
						pLog.warn("[connectors] mapping " + mapping.getOccurrencePrimaryKey().format() + ": " + target.warning);
					}
					if (resolvedDataspace != null && !resolvedDataspace.equals(storedDataspace))
					{
						final ValueContextForUpdate update = pContext.getContext(mapping.getAdaptationName());
						update.setValue(resolvedDataspace, ConnectorsPaths.Mapping.DATASPACE);
						pContext.doModifyContent(mapping, update);
						counts[4]++;
					}
				}
				final Request request = pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY).createRequest();
				request.setXPathFilter("./value='A_RENSEIGNER'");
				for (final Adaptation property : ConnectorsInstaller.rows(request))
				{
					final ValueContextForUpdate update = pContext.getContext(property.getAdaptationName());
					update.setValue("TO_BE_SET", ConnectorsPaths.ConnectorProperty.VALUE);
					pContext.doModifyContent(property, update);
					counts[0]++;
				}
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] migration failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else if (counts[0] + counts[1] + counts[2] + counts[3] + counts[4] + counts[5] > 0)
		{
			pLog.info("[connectors] migration: " + counts[0] + " placeholder(s) set to TO_BE_SET, " + counts[1] + " trust level(s) initialised, " + counts[2]
				+ " logo(s) assigned, " + counts[3] + " category value(s) created or assigned, " + counts[4]
				+ " mapping target dataspace value(s) normalised, " + counts[5] + " pricing value(s) initialised");
		}
	}

	/**
	 * Volume lot 5, task 2: {@code FieldProvenance}'s primary key changed from the auto-increment
	 * {@code id} to the natural key {@code (dataspace, dataset, tablePath, recordKey, fieldPath)} -
	 * see {@code tools/build_xsd.py} and {@code ConnectorRepository.fieldProvenanceKey}. All five
	 * fields already existed and were always populated by {@code recordProvenance} before this lot, so
	 * an ordinary repository upgrade is expected to need no repair at all: the new key is simply the
	 * same five values that already made every row unique in practice. This is a narrow safety net for
	 * the one shape that WOULD be broken by the new key - a row with one of the five fields blank
	 * (unaddressable: {@code lookupAdaptationByPrimaryKey} can never find it again, and it would sit as
	 * permanent dead weight) - not a full-table wipe: {@code FieldProvenance} is a rebuildable
	 * audit/cache (the engine rewrites a field's row on its next write; the TRUSTED write policy only
	 * degrades to "no known provenance" until then), so deleting the handful of rows that cannot be
	 * addressed is safe, while the rest of the audit trail is preserved. A plain forward scan is used
	 * (not a primary-key lookup), so this works whether or not the new key has fully "taken" yet.
	 * <p>
	 * NOT verified against a live repository upgrade carrying pre-lot-5 rows: re-check on the first
	 * real upgrade before relying on this in production (see docs/REFERENCE.md).
	 */
	static void dropIncompleteFieldProvenanceRows(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		if (pDataset.getSchemaNode().getNode(ConnectorsPaths.FIELD_PROVENANCE) == null)
		{
			return;
		}
		// Once is enough (audit C-26): the table used to be read whole at every start, and a row cannot gain a blank key
		// field afterwards. Recorded in the same transaction as the check, so a check that failed runs again at the next start.
		final String migration = SeedMarkers.migration("fieldProvenanceKey");
		if (ConnectorsInstaller.markers.has(migration))
		{
			return;
		}
		final int[] removed = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final AdaptationTable provenance = pDataset.getTable(ConnectorsPaths.FIELD_PROVENANCE);
				final List<Adaptation> stale = ConnectorsInstaller.incompleteProvenanceRows(provenance);
				for (final Adaptation row : stale)
				{
					pContext.doDelete(row.getAdaptationName(), false);
					removed[0]++;
				}
				ConnectorsInstaller.mark(pContext, pDataset, migration);
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] FieldProvenance primary-key migration check failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else if (removed[0] > 0)
		{
			pLog.info("[connectors] FieldProvenance: removed " + removed[0]
				+ " row(s) that predate the (dataspace, dataset, tablePath, recordKey, fieldPath) primary key and could not be addressed by it");
		}
	}

	/**
	 * The rows with a blank part of the natural key, read as a stream: the ones EBX reports null for the five fields, then
	 * checked in code too (a blank is a null in EBX, a few spaces are not). Without the predicate (should the
	 * engine refuse it) the whole table is streamed, never held in a list.
	 */
	private static List<Adaptation> incompleteProvenanceRows(final AdaptationTable pProvenance)
	{
		final Path[] key = { ConnectorsPaths.FieldProvenance.DATASPACE, ConnectorsPaths.FieldProvenance.DATASET, ConnectorsPaths.FieldProvenance.TABLE_PATH,
			ConnectorsPaths.FieldProvenance.RECORD_KEY, ConnectorsPaths.FieldProvenance.FIELD_PATH };
		final StringBuilder predicate = new StringBuilder();
		for (final Path field : key)
		{
			predicate.append(predicate.length() == 0 ? "" : " or ").append("osd:is-null(").append(field.format()).append(')');
		}
		RequestResult rows;
		try
		{
			final Request request = pProvenance.createRequest();
			request.setXPathFilter(predicate.toString());
			rows = request.execute();
		}
		catch (final RuntimeException refused)
		{
			rows = pProvenance.createRequest().execute();
		}
		final List<Adaptation> stale = new ArrayList<Adaptation>();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				for (final Path field : key)
				{
					if (ConnectorsInstaller.isBlank(row.getString(field)))
					{
						stale.add(row);
						break;
					}
				}
			}
		}
		finally
		{
			rows.close();
		}
		return stale;
	}

	private static boolean isBlank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	/** What the boot-time repair decides for one mapping: the dataspace to write (null: leave the row as it is) and a warning to log (null: nothing to say). */
	static final class MappingTarget
	{
		final String dataspace;
		final String warning;

		MappingTarget(final String pDataspace, final String pWarning)
		{
			this.dataspace = pDataspace;
			this.warning = pWarning;
		}
	}

	/** Looks a dataset up in a dataspace (null: in any open dataspace); the decision below reads only the verdicts. */
	interface DatasetLookup
	{
		DatasetLocator.Location at(String pDataspace);
	}

	/**
	 * Converts historical intrinsic dataspace keys (for example {@code BAI_MDM_WORKSHOP}) and
	 * formerly optional values to the branch-name representation required by the packaged EBX
	 * dependent dataset/table enumerations. Never guesses and never fills a blank value: see {@link #decideMappingTarget}.
	 */
	static MappingTarget resolveMappingDataspace(final Repository pRepository, final String pStoredDataspace,
		final String pDataset, final String pTablePath)
	{
		if (pRepository == null || pDataset == null || pDataset.trim().isEmpty()) return new MappingTarget(null, null);
		return ConnectorsInstaller.decideMappingTarget(pStoredDataspace, pDataset, pTablePath, new DatasetLookup()
		{
			@Override
			public DatasetLocator.Location at(final String pDataspace)
			{
				return DatasetLocator.locate(pRepository, pDataspace, pDataset, pTablePath);
			}
		});
	}

	/**
	 * Pure. A BLANK dataspace is left blank and nothing is looked up: an empty dataspace is a documented, generic mapping (it
	 * applies to a record wherever the dataset exists, workflow child dataspaces included), and filling it at boot would turn
	 * every such mapping into a specific one. A stored dataspace that resolves is written back in its branch-name form; a
	 * {@code B}-prefixed one only when the stripped key resolves; one that no longer resolves is NEVER replaced (the dataspace
	 * may have been deleted, and another project's dataset of the same name must not take its place): it is left as stored and
	 * a warning says so.
	 */
	static MappingTarget decideMappingTarget(final String pStoredDataspace, final String pDataset, final String pTablePath, final DatasetLookup pLookup)
	{
		final String stored = pStoredDataspace == null ? "" : pStoredDataspace.trim();
		if (stored.isEmpty())
		{
			return new MappingTarget(null, null);
		}
		final DatasetLocator.Location named = pLookup.at(stored);
		if (named.getVerdict() == DatasetLocator.Verdict.UNIQUE)
		{
			return new MappingTarget(named.getCandidates().get(0), null);
		}
		if (stored.length() > 1 && stored.charAt(0) == 'B')
		{
			final DatasetLocator.Location unprefixed = pLookup.at(stored.substring(1));
			if (unprefixed.getVerdict() == DatasetLocator.Verdict.UNIQUE)
			{
				return new MappingTarget(unprefixed.getCandidates().get(0), null);
			}
		}
		return new MappingTarget(null, "dataspace '" + stored + "' no longer resolves dataset '" + pDataset + "'"
			+ (pTablePath == null || pTablePath.trim().isEmpty() ? "" : " (table " + pTablePath.trim() + ")") + ": left as stored, the mapping will not run until the dataspace is restored or corrected");
	}

	private static List<Adaptation> rows(final Request pRequest)
	{
		final List<Adaptation> all = new ArrayList<Adaptation>();
		final RequestResult rows = pRequest.execute();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				all.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return all;
	}

	private static boolean hasMappingFor(final Adaptation pDataset, final String pConnector)
	{
		final com.onwbp.adaptation.Request request = pDataset.getTable(ConnectorsPaths.MAPPING).createRequest();
		request.setXPathFilter("./connector='" + pConnector + "'");
		final com.onwbp.adaptation.RequestResult rows = request.execute();
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	private static boolean hasMappingFor(final Adaptation pDataset, final String pConnector, final String pOperation)
	{
		final com.onwbp.adaptation.Request request = pDataset.getTable(ConnectorsPaths.MAPPING).createRequest();
		request.setXPathFilter("./connector='" + pConnector + "' and ./operation='" + pOperation + "'");
		final com.onwbp.adaptation.RequestResult rows = request.execute();
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Round trip on an address: the record's address is sent to Melissa (or Loqate), the
	 * normalised answer is written back. Only verified addresses come back (connector property
	 * verified.only / verification.min), so ALWAYS is safe on the address lines.
	 */
	static void seedAddressNormalisation(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final String[][] inputs = { { "address", "${./Address/Street}" }, { "locality", "${./Address/City}" }, { "postalCode", "${./Address/Zipcode}" },
					{ "country", "${./Address/Country}" } };
				final int melissa = ConnectorsInstaller.mapping(pContext, pDataset, "Melissa", "address", "INBOUND",
					"Melissa address normalisation (record address -> verified address + coordinates)", null, "Companies", "/root/Companies", "", inputs);
				if (melissa >= 0)
				{
					ConnectorsInstaller.mark(pContext, pDataset, SeedMarkers.mappings("Melissa"));
				}
				ConnectorsInstaller.rules(pContext, pDataset, melissa, new String[][] {
					{ "AddressLine1", "./Address/Street", "trim|left(80)", "ALWAYS" }, { "Locality", "./Address/City", "capitalize|left(80)", "ALWAYS" },
					{ "PostalCode", "./Address/Zipcode", "trim|left(80)", "ALWAYS" }, { "CountryISO3166_1_Alpha2", "./Address/Country", "upper", "IF_EMPTY" },
					{ "Latitude", "./Address/latitude", "round(7)", "IF_EMPTY" }, { "Longitude", "./Address/longitude", "round(7)", "IF_EMPTY" },
					{ "", "./Governance/enrichmentSource", "constant(MELISSA)", "IF_EMPTY" } });
				if (pDataset.getTable(ConnectorsPaths.CONNECTOR).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString("Loqate")) != null)
				{
					final int loqate = ConnectorsInstaller.mapping(pContext, pDataset, "Loqate", "verify", "INBOUND",
						"Loqate address verification (record address -> verified address + coordinates)", null, "Companies", "/root/Companies", "", inputs);
					ConnectorsInstaller.rules(pContext, pDataset, loqate, new String[][] {
						{ "Address1", "./Address/Street", "trim|left(80)", "ALWAYS" }, { "Locality", "./Address/City", "capitalize|left(80)", "ALWAYS" },
						{ "PostalCode", "./Address/Zipcode", "trim|left(80)", "ALWAYS" }, { "ISO3166-2", "./Address/Country", "upper", "IF_EMPTY" },
						{ "Latitude", "./Address/latitude", "round(7)", "IF_EMPTY" }, { "Longitude", "./Address/longitude", "round(7)", "IF_EMPTY" },
						{ "", "./Governance/enrichmentSource", "constant(LOQATE)", "IF_EMPTY" } });
				}
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] address normalisation seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the Melissa / Loqate address normalisation mappings");
		}
	}

	private static String schemaOf(final Adaptation pDataset)
	{
		try
		{
			return String.valueOf(pDataset.getSchemaLocation().format());
		}
		catch (final RuntimeException ex)
		{
			return "?";
		}
	}

	/** True when the dataset's model is packaged by this module (urn:ebx:module:&lt;module&gt;:/WEB-INF/...). */
	static boolean belongsTo(final Adaptation pDataset, final String pModuleName)
	{
		try
		{
			final com.orchestranetworks.schema.SchemaLocation location = pDataset.getSchemaLocation();
			return pModuleName == null || !location.isPackagedInModule() || pModuleName.equals(location.getModuleName());
		}
		catch (final RuntimeException ex)
		{
			// Fail closed: if ownership cannot be established, installation must not risk
			// changing a dataset packaged by another module.
			return false;
		}
	}

	private static boolean isEmpty(final AdaptationTable pTable)
	{
		final com.onwbp.adaptation.RequestResult rows = pTable.createRequest().execute();
		try
		{
			return rows.nextAdaptation() == null;
		}
		finally
		{
			rows.close();
		}
	}

	// ------------------------------------------------------------------ seed

	private static final class Row
	{
		final Path table;
		final Object[] values;

		Row(final Path pTable, final Object... pValues)
		{
			this.table = pTable;
			this.values = pValues;
		}
	}

	/** Seeds connectors and the Companies mappings; secrets are left as placeholders. */
	static void seed(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connector(pContext, pDataset, "GLEIF", "GLEIF - Legal Entity Identifier", "gleif",
					"https://api.gleif.org/api/v1", "NONE", null, null, "Public LEI registry, no key, 60 calls/min.");
				ConnectorsInstaller.connector(pContext, pDataset, "Sirene", "INSEE Sirene 3.11", "sirene",
					"https://api.insee.fr/api-sirene/3.11", "API_KEY_HEADER", "X-INSEE-Api-Key-Integration", "TO_BE_SET",
					"French business register. Set the API key (Connector > apiKey).");
				ConnectorsInstaller.connector(pContext, pDataset, "GoogleGeocoding", "Google Geocoding", "google-geocoding",
					"https://maps.googleapis.com/maps/api", "NONE", null, "TO_BE_SET",
					"Set the API key (Connector > apiKey); sent as the key query parameter.");

				// GLEIF -> Companies
				final int gleif = ConnectorsInstaller.mapping(pContext, pDataset, "GLEIF", "lei", "INBOUND",
					"GLEIF record -> company legal information", null, "Companies", "/root/Companies", "data.attributes",
					new String[][] { { "lei", "${./Identification/LegalEntityIdentifier}" } });
				final String[][] gleifRules = {
					{ "entity.legalName.name", "./LegalInformation/legalName", "trim|left(255)", "IF_EMPTY" },
					{ "entity.legalForm.id", "./LegalInformation/legalForm", "fk(/root/ReferenceData/LegalForm)", "IF_EMPTY" },
					{ "entity.category", "./LegalInformation/entityCategory", "map(GENERAL=GENERAL;BRANCH=BRANCH;FUND=FUND;SOLE_PROPRIETOR=SOLE_PROPRIETOR;RESIDENT_GOVERNMENT_ENTITY=RESIDENT_GOVERNMENT_ENTITY;INTERNATIONAL_ORGANIZATION=INTERNATIONAL_ORGANIZATION)", "IF_EMPTY" },
					{ "entity.jurisdiction", "./LegalInformation/jurisdiction", "left(2)|upper", "IF_EMPTY" },
					{ "entity.registeredAt.id", "./LegalInformation/registrationAuthority", "fk(/root/ReferenceData/RegistrationAuthority)", "IF_EMPTY" },
					{ "entity.registeredAs", "./LegalInformation/registrationNumber", "trim|left(80)", "IF_EMPTY" },
					{ "entity.creationDate", "./LegalInformation/incorporationDate", "isodate", "IF_EMPTY" },
					{ "registration.status", "./Lifecycle/leiStatus", "trim", "ALWAYS" },
					{ "registration.nextRenewalDate", "./Lifecycle/leiNextRenewalDate", "isodate", "ALWAYS" },
					{ "entity.status", "./Lifecycle/leiEntityStatus", "map(ACTIVE=ACTIVE;INACTIVE=INACTIVE)", "ALWAYS" },
					{ "entity.status", "./Lifecycle/status", "map(INACTIVE=CLOSED)", "IF_EMPTY" },
					{ "registration.lastUpdateDate", "./Governance/gleifLastUpdate", "isodatetime", "ALWAYS" },
					{ "entity.headquartersAddress.addressLines", "./Address/Street", "join(', ')|left(80)", "IF_EMPTY" },
					{ "entity.headquartersAddress.postalCode", "./Address/Zipcode", "left(80)", "IF_EMPTY" },
					{ "entity.headquartersAddress.city", "./Address/City", "left(80)", "IF_EMPTY" },
					{ "entity.headquartersAddress.country", "./Address/Country", "left(2)|upper", "IF_EMPTY" },
					{ "", "./Governance/enrichmentSource", "constant(GLEIF)", "IF_EMPTY" } };
				ConnectorsInstaller.rules(pContext, pDataset, gleif, gleifRules);

				// GLEIF search by SIREN -> fills the LEI of French companies that only have a registration number
				final int gleifSearch = ConnectorsInstaller.mapping(pContext, pDataset, "GLEIF", "searchByRegistration", "INBOUND",
					"GLEIF search by SIREN -> LEI", null, "Companies", "/root/Companies", "data",
					new String[][] { { "registrationId", "${./LegalInformation/registrationNumber}" },
						{ "registrationAuthority", "RA000189" } });
				ConnectorsInstaller.rules(pContext, pDataset, gleifSearch, new String[][] {
					{ "id", "./Identification/LegalEntityIdentifier", "trim|upper", "IF_EMPTY" },
					{ "attributes.entity.legalName.name", "./LegalInformation/legalName", "trim|left(255)", "IF_EMPTY" } });

				// Sirene -> Companies
				final int sirene = ConnectorsInstaller.mapping(pContext, pDataset, "Sirene", "siren", "INBOUND",
					"Sirene legal unit -> company", null, "Companies", "/root/Companies", "uniteLegale",
					new String[][] { { "siren", "${./LegalInformation/registrationNumber}" } });
				ConnectorsInstaller.rules(pContext, pDataset, sirene, new String[][] {
					{ "periodesUniteLegale[0].denominationUniteLegale", "./LegalInformation/legalName", "trim|left(255)", "IF_EMPTY" },
					{ "dateCreationUniteLegale", "./LegalInformation/incorporationDate", "isodate", "IF_EMPTY" },
					{ "periodesUniteLegale[0].activitePrincipaleUniteLegale", "./AdditionalInformation/naceCode", "left(5)|fk(/root/ReferenceData/NaceCode)", "IF_EMPTY" },
					{ "periodesUniteLegale[0].activitePrincipaleUniteLegale", "./AdditionalInformation/naceCode", "left(2)|fk(/root/ReferenceData/NaceCode)", "IF_EMPTY" },
					{ "categorieEntreprise", "./LegalInformation/sizeCategory", "map(PME=MEDIUM;ETI=LARGE;GE=VERY_LARGE)", "IF_EMPTY" },
					{ "periodesUniteLegale[0].etatAdministratifUniteLegale", "./Lifecycle/status", "map(C=CLOSED)", "IF_EMPTY" },
					{ "", "./LegalInformation/jurisdiction", "constant(FR)", "IF_EMPTY" },
					{ "", "./LegalInformation/registrationAuthority", "constant(RA000189)", "IF_EMPTY" } });
				final int siege = ConnectorsInstaller.mapping(pContext, pDataset, "Sirene", "headOffice", "INBOUND",
					"Sirene head office -> company address", null, "Companies", "/root/Companies", "etablissement.adresseEtablissement",
					new String[][] { { "siren", "${./LegalInformation/registrationNumber}" } });
				ConnectorsInstaller.rules(pContext, pDataset, siege, new String[][] {
					{ "numeroVoieEtablissement", "./Address/Number", "left(80)", "IF_EMPTY" },
					{ "libelleVoieEtablissement", "./Address/Street", "left(80)", "IF_EMPTY" },
					{ "codePostalEtablissement", "./Address/Zipcode", "left(80)", "IF_EMPTY" },
					{ "libelleCommuneEtablissement", "./Address/City", "capitalize|left(80)", "IF_EMPTY" },
					{ "", "./Address/Country", "constant(FR)", "IF_EMPTY" } });

				// Google Geocoding -> Companies coordinates
				final int geo = ConnectorsInstaller.mapping(pContext, pDataset, "GoogleGeocoding", "geocode", "INBOUND",
					"Google Geocoding -> company coordinates", null, "Companies", "/root/Companies", "results[0]",
					new String[][] { { "address", "${./Address/Number} ${./Address/Street}, ${./Address/Zipcode} ${./Address/City}, ${./Address/Country}" } });
				ConnectorsInstaller.rules(pContext, pDataset, geo, new String[][] {
					{ "geometry.location.lat", "./Address/latitude", "round(7)", "IF_EMPTY" },
					{ "geometry.location.lng", "./Address/longitude", "round(7)", "IF_EMPTY" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded GLEIF, Sirene, GoogleGeocoding and the Companies mappings");
		}
	}

	/** Salesforce: connector with placeholders plus an Account <-> Companies pair of mappings. */
	static void seedSalesforce(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connector(pContext, pDataset, "Salesforce", "Salesforce CRM", "salesforce",
					"https://login.salesforce.com", "NONE", null, "TO_BE_SET",
					"OAuth 2.0 client credentials by default. Set the consumer secret (apiKey) and the property clientId (consumer key); "
							+ "properties oauth.flow (client_credentials | password), securityToken, apiVersion, instanceUrl.");
				ConnectorsInstaller.property(pContext, pDataset, "Salesforce", "clientId", "TO_BE_SET");
				ConnectorsInstaller.property(pContext, pDataset, "Salesforce", "oauth.flow", "client_credentials");
				ConnectorsInstaller.property(pContext, pDataset, "Salesforce", "apiVersion", "v60.0");
				// Salesforce Account -> Companies (by the LEI stored in an external id field LEI__c)
				final int in = ConnectorsInstaller.mapping(pContext, pDataset, "Salesforce", "getByExternalId", "INBOUND",
					"Salesforce Account -> company", null, "Companies", "/root/Companies", "",
					new String[][] { { "sobject", "Account" }, { "externalIdField", "LEI__c" }, { "value", "${./Identification/LegalEntityIdentifier}" } });
				ConnectorsInstaller.rules(pContext, pDataset, in, new String[][] {
					{ "Name", "./Identification/Name", "trim|left(80)", "IF_EMPTY" },
					{ "Website", "./Contact/website", "trim", "IF_EMPTY" },
					{ "Phone", "./Contact/phone", "trim|left(40)", "IF_EMPTY" },
					{ "BillingStreet", "./Address/Street", "left(80)", "IF_EMPTY" },
					{ "BillingPostalCode", "./Address/Zipcode", "left(80)", "IF_EMPTY" },
					{ "BillingCity", "./Address/City", "left(80)", "IF_EMPTY" },
					{ "BillingCountryCode", "./Address/Country", "upper", "IF_EMPTY" },
					{ "NumberOfEmployees", "./LegalInformation/employeesCount", "number", "IF_EMPTY" },
					{ "Id", "./Governance/enrichmentSource", "constant(SALESFORCE)", "IF_EMPTY" } });
				// Companies -> Salesforce Account (upsert by LEI__c)
				final int out = ConnectorsInstaller.mapping(pContext, pDataset, "Salesforce", "upsert", "OUTBOUND",
					"Company -> Salesforce Account", null, "Companies", "/root/Companies", "",
					new String[][] { { "sobject", "Account" }, { "externalIdField", "LEI__c" }, { "value", "${./Identification/LegalEntityIdentifier}" } });
				ConnectorsInstaller.rules(pContext, pDataset, out, new String[][] {
					{ "./Identification/Name", "Name", "trim|left(255)", "ALWAYS" },
					{ "./Contact/website", "Website", "trim", "ALWAYS" },
					{ "./Contact/phone", "Phone", "trim", "ALWAYS" },
					{ "./Address/Street", "BillingStreet", "trim", "ALWAYS" },
					{ "./Address/Zipcode", "BillingPostalCode", "trim", "ALWAYS" },
					{ "./Address/City", "BillingCity", "trim", "ALWAYS" },
					{ "./Address/Country", "BillingCountryCode", "upper", "ALWAYS" },
					{ "./LegalInformation/employeesCount", "NumberOfEmployees", "number", "ALWAYS" },
					{ "./AdditionalInformation/naceCode", "Industry", "lookup(/root/ReferenceData/NaceCode,./Code,./Label)", "ALWAYS" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] Salesforce seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the Salesforce connector and the Account <-> Companies mappings");
		}
	}

	/** Kafka: connector plus a composite outbound mapping (company + addresses + contacts) and an inbound feed. */
	static void seedKafka(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connector(pContext, pDataset, "Kafka", "Apache Kafka", "kafka", "localhost:9092", "NONE", null, null,
					"baseUrl = bootstrap servers. Properties: topic, group.id, envelope, key.field, security.protocol, sasl.mechanism, kafka.* passed through. "
							+ "Authentication BASIC = SASL/PLAIN with username / password.");
				ConnectorsInstaller.property(pContext, pDataset, "Kafka", "topic", "ebx.companies");
				ConnectorsInstaller.property(pContext, pDataset, "Kafka", "group.id", "ebx-connectors");
				ConnectorsInstaller.property(pContext, pDataset, "Kafka", "envelope", "false");
				ConnectorsInstaller.property(pContext, pDataset, "Kafka", "key.field", "lei");

				// OUTBOUND: company with its addresses and contacts -> one JSON message
				final int out = ConnectorsInstaller.mapping(pContext, pDataset, "Kafka", "publish", "OUTBOUND",
					"Company (with addresses and contacts) -> Kafka", null, "Companies", "/root/Companies", "",
					new String[][] { { "table", "Companies" }, { "key", "${./Identification/LegalEntityIdentifier}" } });
				ConnectorsInstaller.rules(pContext, pDataset, out, new String[][] {
					{ "./Identification/Id", "id", "", "ALWAYS" }, { "./Identification/Name", "name", "trim", "ALWAYS" },
					{ "./Identification/LegalEntityIdentifier", "lei", "trim", "ALWAYS" }, { "./LegalInformation/legalName", "legalName", "", "ALWAYS" },
					{ "./LegalInformation/legalForm", "legalForm", "", "ALWAYS" }, { "./LegalInformation/incorporationDate", "incorporationDate", "", "ALWAYS" },
					{ "./AdditionalInformation/ActivitySector", "sector", "", "ALWAYS" }, { "./AdditionalInformation/naceCode", "nace", "", "ALWAYS" },
					{ "./Lifecycle/status", "status", "", "ALWAYS" }, { "./Contact/website", "website", "", "ALWAYS" },
					{ "./Address/Country", "country", "", "ALWAYS" } });
				final int outAddresses = ConnectorsInstaller.childMapping(pContext, pDataset, out, "Kafka", "publish", "OUTBOUND",
					"Company -> Kafka / addresses", "Companies", "/root/CompanyAddress", "addresses", "./company", "", "");
				ConnectorsInstaller.rules(pContext, pDataset, outAddresses, new String[][] {
					{ "./type", "type", "", "ALWAYS" }, { "./Number", "number", "", "ALWAYS" }, { "./Street", "street", "", "ALWAYS" },
					{ "./Zipcode", "zipcode", "", "ALWAYS" }, { "./City", "city", "", "ALWAYS" }, { "./Country", "country", "", "ALWAYS" },
					{ "./latitude", "lat", "", "ALWAYS" }, { "./longitude", "lng", "", "ALWAYS" } });
				final int outContacts = ConnectorsInstaller.childMapping(pContext, pDataset, out, "Kafka", "publish", "OUTBOUND",
					"Company -> Kafka / contacts", "Companies", "/root/Contact", "contacts", "./company", "", "./active=true");
				ConnectorsInstaller.rules(pContext, pDataset, outContacts, new String[][] {
					{ "./firstName", "firstName", "", "ALWAYS" }, { "./lastName", "lastName", "", "ALWAYS" }, { "./jobTitle", "jobTitle", "", "ALWAYS" },
					{ "./role", "role", "", "ALWAYS" }, { "./email", "email", "", "ALWAYS" }, { "./phone", "phone", "", "ALWAYS" } });

				// INBOUND feed: messages of the topic -> companies, addresses and contacts
				final int in = ConnectorsInstaller.feedMapping(pContext, pDataset, "Kafka", "consume", "Kafka -> companies (with addresses and contacts)",
					"Companies", "/root/Companies", "", "messages[*].value", "./Identification/LegalEntityIdentifier='${lei}'", true,
					new String[][] { { "topic", "ebx.companies.in" } });
				ConnectorsInstaller.rules(pContext, pDataset, in, new String[][] {
					{ "lei", "./Identification/LegalEntityIdentifier", "trim|upper", "ALWAYS" }, { "name", "./Identification/Name", "trim|left(80)", "ALWAYS" },
					{ "legalName", "./LegalInformation/legalName", "trim|left(255)", "ALWAYS" }, { "legalForm", "./LegalInformation/legalForm", "fk(/root/ReferenceData/LegalForm)", "ALWAYS" },
					{ "incorporationDate", "./LegalInformation/incorporationDate", "isodate", "ALWAYS" }, { "nace", "./AdditionalInformation/naceCode", "fk(/root/ReferenceData/NaceCode)", "ALWAYS" },
					{ "status", "./Lifecycle/status", "map(ACTIVE=ACTIVE;CLOSED=CLOSED;SUSPENDED=SUSPENDED;PROSPECT=PROSPECT;MERGED=MERGED)", "ALWAYS" },
					{ "website", "./Contact/website", "trim", "ALWAYS" }, { "country", "./Address/Country", "left(2)|upper", "ALWAYS" },
					{ "", "./Governance/enrichmentSource", "constant(KAFKA)", "IF_EMPTY" } });
				final int inAddresses = ConnectorsInstaller.childMapping(pContext, pDataset, in, "Kafka", "consume", "INBOUND",
					"Kafka -> companies / addresses", "Companies", "/root/CompanyAddress", "addresses", "./company", "./type,./City", "./source='KAFKA'");
				ConnectorsInstaller.rules(pContext, pDataset, inAddresses, new String[][] {
					{ "type", "./type", "upper|default(OTHER)", "ALWAYS" }, { "number", "./Number", "left(20)", "ALWAYS" }, { "street", "./Street", "left(255)", "ALWAYS" },
					{ "zipcode", "./Zipcode", "left(20)", "ALWAYS" }, { "city", "./City", "left(120)", "ALWAYS" }, { "country", "./Country", "left(2)|upper", "ALWAYS" },
					{ "lat", "./latitude", "number", "ALWAYS" }, { "lng", "./longitude", "number", "ALWAYS" }, { "", "./source", "constant(KAFKA)", "ALWAYS" } });
				final int inContacts = ConnectorsInstaller.childMapping(pContext, pDataset, in, "Kafka", "consume", "INBOUND",
					"Kafka -> companies / contacts", "Companies", "/root/Contact", "contacts", "./company", "./email", "");
				ConnectorsInstaller.rules(pContext, pDataset, inContacts, new String[][] {
					{ "firstName", "./firstName", "left(80)", "ALWAYS" }, { "lastName", "./lastName", "left(80)", "ALWAYS" }, { "jobTitle", "./jobTitle", "left(120)", "ALWAYS" },
					{ "role", "./role", "upper|fk(/root/ReferenceData/ContactRole)", "ALWAYS" }, { "email", "./email", "lower|trim", "ALWAYS" }, { "phone", "./phone", "left(40)", "ALWAYS" },
					{ "", "./active", "constant(true)", "IF_EMPTY" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] Kafka seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the Kafka connector with the composite Companies mappings");
		}
	}

	/** Power BI: the connector row with placeholders (Entra service principal), no mapping — the data catalog creates its flows per source. */
	static void seedPowerBi(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "PowerBI", "Microsoft Power BI", "powerbi", "https://api.powerbi.com/v1.0/myorg",
					"OAUTH2", null, "TO_BE_SET", null, null,
					"Reports, dashboards, semantic models (tables, columns, measures, Power Query and DAX), dataflows and their lineage for the data catalog; DAX queries. "
							+ "Entra service principal: properties tenantId and clientId, client secret in apiKey. Admin Scanner API needs the tenant settings "
							+ "'Allow service principals to use read-only admin APIs' and 'Enhance admin APIs responses with detailed metadata / DAX and mashup expressions'; "
							+ "otherwise only the workspaces the principal is a member of are read (scanner=standard). "
							+ "datasource.map joins a Power Query data source to the catalog source that scans it (server[/database]=SOURCE[:upper]).",
					new String[][] { { "tenantId", "TO_BE_SET" }, { "clientId", "TO_BE_SET" }, { "scanner", "auto" }, { "datasource.map", "" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] Power BI seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] Power BI connector seeded (placeholders)");
		}
	}

	/** Collibra: the connector row with placeholders (Basic user), no mapping — the data catalog creates its glossary and publication flows. */
	static void seedCollibra(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Collibra", "Collibra Data Intelligence Platform", "collibra", "https://INSTANCE.collibra.com",
					"BASIC", null, null, "TO_BE_SET", "TO_BE_SET",
					"REST API 2.0, both directions: business terms and assets read into EBX, catalog assets, columns and lineage published as Collibra assets, attributes and relations. "
							+ "Basic authentication (a Collibra user with API access) or a bearer JWT. Names, never UUIDs: properties community, domain.type, term.type, "
							+ "relation.contains / relation.lineage / relation.term as 'Source type>role>Target type', attribute.* aliases.",
					new String[][] { { "community", "Data Governance Council" }, { "domain.type", "Physical Data Dictionary" }, { "term.type", "Business Term" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] Collibra seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] Collibra connector seeded (placeholders)");
		}
	}

	/** Snowflake: connector with placeholders, an import feed from a table and a composite MERGE. */
	static void seedSnowflake(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connector(pContext, pDataset, "Snowflake", "Snowflake", "snowflake",
					"https://ACCOUNT.snowflakecomputing.com", "BEARER", null, "TO_BE_SET",
					"SQL REST API. apiKey = programmatic access token (property tokenType=PAT) or OAuth token (tokenType=OAUTH); "
							+ "or property auth=keypair with username + private key in password + property account. "
							+ "Context: properties warehouse, database, schema, role, timeout, max.rows.");
				ConnectorsInstaller.property(pContext, pDataset, "Snowflake", "tokenType", "PAT");
				ConnectorsInstaller.property(pContext, pDataset, "Snowflake", "warehouse", "COMPUTE_WH");
				ConnectorsInstaller.property(pContext, pDataset, "Snowflake", "database", "MDM");
				ConnectorsInstaller.property(pContext, pDataset, "Snowflake", "schema", "PUBLIC");
				ConnectorsInstaller.property(pContext, pDataset, "Snowflake", "max.rows", "1000");

				// import feed: a customers table -> companies (rows[] of the query document)
				final int in = ConnectorsInstaller.feedMapping(pContext, pDataset, "Snowflake", "query", "Snowflake CUSTOMERS -> companies",
					"Companies", "/root/Companies", "", "rows", "./Identification/LegalEntityIdentifier='${LEI}'", true,
					new String[][] { { "sql", "SELECT LEI, NAME, LEGAL_NAME, COUNTRY, CITY, WEBSITE, NACE, STATUS FROM CUSTOMERS WHERE LEI IS NOT NULL" } });
				ConnectorsInstaller.rules(pContext, pDataset, in, new String[][] {
					{ "LEI", "./Identification/LegalEntityIdentifier", "trim|upper", "ALWAYS" }, { "NAME", "./Identification/Name", "trim|left(80)", "ALWAYS" },
					{ "LEGAL_NAME", "./LegalInformation/legalName", "trim|left(255)", "IF_EMPTY" }, { "COUNTRY", "./Address/Country", "left(2)|upper", "IF_EMPTY" },
					{ "CITY", "./Address/City", "left(80)", "IF_EMPTY" }, { "WEBSITE", "./Contact/website", "trim", "IF_EMPTY" },
					{ "NACE", "./AdditionalInformation/naceCode", "fk(/root/ReferenceData/NaceCode)", "IF_EMPTY" },
					{ "STATUS", "./Lifecycle/status", "upper|map(ACTIVE=ACTIVE;CLOSED=CLOSED;SUSPENDED=SUSPENDED)", "IF_EMPTY" },
					{ "", "./Governance/enrichmentSource", "constant(SNOWFLAKE)", "IF_EMPTY" } });

				// outbound: company + addresses (VARIANT column) merged into a table on LEI
				final int out = ConnectorsInstaller.mapping(pContext, pDataset, "Snowflake", "merge", "OUTBOUND",
					"Company (with addresses) -> Snowflake COMPANIES", null, "Companies", "/root/Companies", "",
					new String[][] { { "table", "COMPANIES" }, { "keyColumn", "LEI" } });
				ConnectorsInstaller.rules(pContext, pDataset, out, new String[][] {
					{ "./Identification/LegalEntityIdentifier", "LEI", "trim|upper", "ALWAYS" }, { "./Identification/Id", "EBX_ID", "", "ALWAYS" },
					{ "./Identification/Name", "NAME", "trim", "ALWAYS" }, { "./LegalInformation/legalName", "LEGAL_NAME", "", "ALWAYS" },
					{ "./LegalInformation/legalForm", "LEGAL_FORM", "", "ALWAYS" }, { "./LegalInformation/incorporationDate", "INCORPORATION_DATE", "", "ALWAYS" },
					{ "./AdditionalInformation/naceCode", "NACE", "", "ALWAYS" }, { "./Lifecycle/status", "STATUS", "", "ALWAYS" },
					{ "./Address/Country", "COUNTRY", "", "ALWAYS" }, { "./Address/City", "CITY", "", "ALWAYS" },
					{ "./Governance/completenessScore", "COMPLETENESS", "", "ALWAYS" } });
				final int outAddresses = ConnectorsInstaller.childMapping(pContext, pDataset, out, "Snowflake", "merge", "OUTBOUND",
					"Company -> Snowflake / ADDRESSES (VARIANT)", "Companies", "/root/CompanyAddress", "ADDRESSES", "./company", "", "");
				ConnectorsInstaller.rules(pContext, pDataset, outAddresses, new String[][] {
					{ "./type", "type", "", "ALWAYS" }, { "./Street", "street", "", "ALWAYS" }, { "./Zipcode", "zipcode", "", "ALWAYS" },
					{ "./City", "city", "", "ALWAYS" }, { "./Country", "country", "", "ALWAYS" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] Snowflake seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the Snowflake connector with a feed and a composite MERGE mapping");
		}
	}

	/** Databricks: connector with placeholders, a feed from a Delta table and a composite MERGE. */
	static void seedDatabricks(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connector(pContext, pDataset, "Databricks", "Databricks SQL", "databricks",
					"https://adb-0000000000000000.0.azuredatabricks.net", "BEARER", null, "TO_BE_SET",
					"SQL Statement Execution API. apiKey = personal access token, or property auth=oauth with clientId + secret in apiKey. "
							+ "Required property warehouse_id; context catalog, schema, wait, max.rows, nested.function (parse_json).");
				ConnectorsInstaller.property(pContext, pDataset, "Databricks", "warehouse_id", "TO_BE_SET");
				ConnectorsInstaller.property(pContext, pDataset, "Databricks", "catalog", "main");
				ConnectorsInstaller.property(pContext, pDataset, "Databricks", "schema", "mdm");
				ConnectorsInstaller.property(pContext, pDataset, "Databricks", "max.rows", "1000");
				final int in = ConnectorsInstaller.feedMapping(pContext, pDataset, "Databricks", "query", "Databricks customers -> companies",
					"Companies", "/root/Companies", "", "rows", "./Identification/LegalEntityIdentifier='${lei}'", true,
					new String[][] { { "sql", "SELECT lei, name, legal_name, country, city, website, nace, status FROM customers WHERE lei IS NOT NULL" } });
				ConnectorsInstaller.rules(pContext, pDataset, in, new String[][] {
					{ "lei", "./Identification/LegalEntityIdentifier", "trim|upper", "ALWAYS" }, { "name", "./Identification/Name", "trim|left(80)", "ALWAYS" },
					{ "legal_name", "./LegalInformation/legalName", "trim|left(255)", "IF_EMPTY" }, { "country", "./Address/Country", "left(2)|upper", "IF_EMPTY" },
					{ "city", "./Address/City", "left(80)", "IF_EMPTY" }, { "website", "./Contact/website", "trim", "IF_EMPTY" },
					{ "nace", "./AdditionalInformation/naceCode", "fk(/root/ReferenceData/NaceCode)", "IF_EMPTY" },
					{ "status", "./Lifecycle/status", "upper|map(ACTIVE=ACTIVE;CLOSED=CLOSED;SUSPENDED=SUSPENDED)", "IF_EMPTY" },
					{ "", "./Governance/enrichmentSource", "constant(DATABRICKS)", "IF_EMPTY" } });
				final int out = ConnectorsInstaller.mapping(pContext, pDataset, "Databricks", "merge", "OUTBOUND",
					"Company (with addresses) -> Databricks companies", null, "Companies", "/root/Companies", "",
					new String[][] { { "table", "companies" }, { "keyColumn", "lei" } });
				ConnectorsInstaller.rules(pContext, pDataset, out, new String[][] {
					{ "./Identification/LegalEntityIdentifier", "lei", "trim|upper", "ALWAYS" }, { "./Identification/Id", "ebx_id", "", "ALWAYS" },
					{ "./Identification/Name", "name", "trim", "ALWAYS" }, { "./LegalInformation/legalName", "legal_name", "", "ALWAYS" },
					{ "./LegalInformation/legalForm", "legal_form", "", "ALWAYS" }, { "./LegalInformation/incorporationDate", "incorporation_date", "", "ALWAYS" },
					{ "./AdditionalInformation/naceCode", "nace", "", "ALWAYS" }, { "./Lifecycle/status", "status", "", "ALWAYS" },
					{ "./Address/Country", "country", "", "ALWAYS" }, { "./Address/City", "city", "", "ALWAYS" },
					{ "./Governance/completenessScore", "completeness", "", "ALWAYS" } });
				final int outAddresses = ConnectorsInstaller.childMapping(pContext, pDataset, out, "Databricks", "merge", "OUTBOUND",
					"Company -> Databricks / addresses (VARIANT)", "Companies", "/root/CompanyAddress", "addresses", "./company", "", "");
				ConnectorsInstaller.rules(pContext, pDataset, outAddresses, new String[][] {
					{ "./type", "type", "", "ALWAYS" }, { "./Street", "street", "", "ALWAYS" }, { "./Zipcode", "zipcode", "", "ALWAYS" },
					{ "./City", "city", "", "ALWAYS" }, { "./Country", "country", "", "ALWAYS" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] Databricks seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the Databricks connector with a feed and a composite MERGE mapping");
		}
	}

	/** SAP: Business Partner API (OData v2) with a feed, a deep-insert outbound mapping and address sub-objects. */
	static void seedSap(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connector(pContext, pDataset, "SAP", "SAP S/4HANA Business Partner (OData)", "sap-odata",
					"https://sandbox.api.sap.com/s4hanacloud/sap/opu/odata/sap/API_BUSINESS_PARTNER", "API_KEY_HEADER", "APIKey", "TO_BE_SET",
					"OData v2 service root. Sandbox of the SAP Business Accelerator Hub: header APIKey. Real system: authentication BASIC "
							+ "(communication user) or BEARER, property sap-client. Property odata.version=4 for OData v4 services.");
				ConnectorsInstaller.property(pContext, pDataset, "SAP", "odata.version", "2");
				ConnectorsInstaller.property(pContext, pDataset, "SAP", "entitySet", "A_BusinessPartner");
				ConnectorsInstaller.property(pContext, pDataset, "SAP", "max.rows", "200");

				// feed: organisations of the business partner API -> companies, addresses as sub-objects
				final int in = ConnectorsInstaller.feedMapping(pContext, pDataset, "SAP", "query", "SAP business partners (organisations) -> companies",
					"Companies", "/root/Companies", "", "results", "./LegalInformation/registrationNumber='${BusinessPartner}'", true,
					new String[][] { { "entitySet", "A_BusinessPartner" }, { "$filter", "BusinessPartnerCategory eq '2'" },
						{ "$expand", "to_BusinessPartnerAddress" }, { "$top", "50" } });
				ConnectorsInstaller.rules(pContext, pDataset, in, new String[][] {
					{ "BusinessPartner", "./LegalInformation/registrationNumber", "trim", "ALWAYS" },
					{ "BusinessPartnerFullName", "./Identification/Name", "trim|left(80)", "ALWAYS" },
					{ "OrganizationBPName1", "./LegalInformation/legalName", "trim|left(255)", "IF_EMPTY" },
					{ "Industry", "./AdditionalInformation/naceCode", "fk(/root/ReferenceData/NaceCode)", "IF_EMPTY" },
					{ "BusinessPartnerIsBlocked", "./Lifecycle/status", "map(true=SUSPENDED;false=ACTIVE)", "IF_EMPTY" },
					{ "", "./Governance/enrichmentSource", "constant(SAP)", "IF_EMPTY" } });
				final int inAddresses = ConnectorsInstaller.childMapping(pContext, pDataset, in, "SAP", "query", "INBOUND",
					"SAP business partners / addresses", "Companies", "/root/CompanyAddress", "to_BusinessPartnerAddress", "./company", "./type,./City", "./source='SAP'");
				ConnectorsInstaller.rules(pContext, pDataset, inAddresses, new String[][] {
					{ "", "./type", "constant(HEADQUARTERS)", "ALWAYS" }, { "HouseNumber", "./Number", "left(20)", "ALWAYS" },
					{ "StreetName", "./Street", "left(255)", "ALWAYS" }, { "PostalCode", "./Zipcode", "left(20)", "ALWAYS" },
					{ "CityName", "./City", "left(120)", "ALWAYS" }, { "Country", "./Country", "left(2)|upper", "ALWAYS" },
					{ "Region", "./Region", "left(20)", "ALWAYS" }, { "", "./source", "constant(SAP)", "ALWAYS" } });

				// outbound: company + addresses -> deep insert of a business partner
				final int out = ConnectorsInstaller.mapping(pContext, pDataset, "SAP", "create", "OUTBOUND",
					"Company (with addresses) -> SAP business partner (deep insert)", null, "Companies", "/root/Companies", "",
					new String[][] { { "entitySet", "A_BusinessPartner" } });
				ConnectorsInstaller.rules(pContext, pDataset, out, new String[][] {
					{ "", "BusinessPartnerCategory", "constant(2)", "ALWAYS" }, { "./Identification/Name", "OrganizationBPName1", "trim|left(40)", "ALWAYS" },
					{ "./LegalInformation/legalName", "OrganizationBPName2", "trim|left(40)", "ALWAYS" },
					{ "./Identification/LegalEntityIdentifier", "SearchTerm1", "trim|left(20)", "ALWAYS" },
					{ "./AdditionalInformation/naceCode", "Industry", "left(10)", "ALWAYS" } });
				final int outAddresses = ConnectorsInstaller.childMapping(pContext, pDataset, out, "SAP", "create", "OUTBOUND",
					"Company -> SAP business partner / to_BusinessPartnerAddress", "Companies", "/root/CompanyAddress", "to_BusinessPartnerAddress", "./company", "", "");
				ConnectorsInstaller.rules(pContext, pDataset, outAddresses, new String[][] {
					{ "./Number", "HouseNumber", "left(10)", "ALWAYS" }, { "./Street", "StreetName", "left(60)", "ALWAYS" },
					{ "./Zipcode", "PostalCode", "left(10)", "ALWAYS" }, { "./City", "CityName", "left(40)", "ALWAYS" },
					{ "./Country", "Country", "upper", "ALWAYS" }, { "./Region", "Region", "left(3)", "ALWAYS" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] SAP seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the SAP OData connector with the business partner mappings");
		}
	}

	/** JD Edwards: AIS connector, address book feed and an orchestration-based outbound mapping. */
	static void seedJde(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connector(pContext, pDataset, "JDEdwards", "JD Edwards EnterpriseOne (AIS)", "jde-ais",
					"https://jde-ais.example.com:9080/jderest", "BASIC", null, null,
					"AIS REST server. Authentication BASIC = EnterpriseOne user / password (session token requested and renewed). "
							+ "Properties ais.version (v2), environment, role, device, max.rows, strip.prefix.");
				ConnectorsInstaller.property(pContext, pDataset, "JDEdwards", "ais.version", "v2");
				ConnectorsInstaller.property(pContext, pDataset, "JDEdwards", "environment", "JDV920");
				ConnectorsInstaller.property(pContext, pDataset, "JDEdwards", "max.rows", "500");
				final AdaptationTable connectors = pDataset.getTable(ConnectorsPaths.CONNECTOR);
				final Adaptation row = connectors.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString("JDEdwards"));
				final ValueContextForUpdate credentials = pContext.getContext(row.getAdaptationName());
				credentials.setValue("JDE_USER", ConnectorsPaths.Connector.USERNAME);
				credentials.setValue("TO_BE_SET", ConnectorsPaths.Connector.PASSWORD);
				pContext.doModifyContent(row, credentials);

				// feed: address book customers (F0101, search type C) -> companies
				final int in = ConnectorsInstaller.feedMapping(pContext, pDataset, "JDEdwards", "query", "JDE address book (customers) -> companies",
					"Companies", "/root/Companies", "", "results", "./LegalInformation/registrationNumber='${AN8}'", true,
					new String[][] { { "table", "F0101" }, { "columns", "AN8|ALPH|AT1|TAX|TXCT|MCU" }, { "filter", "AT1 EQUAL C" } });
				ConnectorsInstaller.rules(pContext, pDataset, in, new String[][] {
					{ "AN8", "./LegalInformation/registrationNumber", "trim", "ALWAYS" }, { "ALPH", "./Identification/Name", "trim|left(80)", "ALWAYS" },
					{ "TAX", "./LegalInformation/registrationNumber", "trim", "IF_EMPTY" }, { "", "./Governance/enrichmentSource", "constant(JDE)", "IF_EMPTY" } });

				// outbound: company + addresses -> orchestration (to be created in Orchestrator Studio)
				final int out = ConnectorsInstaller.mapping(pContext, pDataset, "JDEdwards", "orchestration", "OUTBOUND",
					"Company (with addresses) -> JDE orchestration EBX_UpsertAddressBook", null, "Companies", "/root/Companies", "",
					new String[][] { { "name", "EBX_UpsertAddressBook" } });
				ConnectorsInstaller.rules(pContext, pDataset, out, new String[][] {
					{ "./Identification/LegalEntityIdentifier", "LEI", "trim", "ALWAYS" }, { "./Identification/Name", "AlphaName", "trim|left(40)", "ALWAYS" },
					{ "./LegalInformation/registrationNumber", "TaxId", "trim|left(20)", "ALWAYS" }, { "", "SearchType", "constant(C)", "ALWAYS" } });
				final int outAddresses = ConnectorsInstaller.childMapping(pContext, pDataset, out, "JDEdwards", "orchestration", "OUTBOUND",
					"Company -> JDE orchestration / Addresses", "Companies", "/root/CompanyAddress", "Addresses", "./company", "", "");
				ConnectorsInstaller.rules(pContext, pDataset, outAddresses, new String[][] {
					{ "./Street", "AddressLine1", "left(40)", "ALWAYS" }, { "./Zipcode", "PostalCode", "left(12)", "ALWAYS" },
					{ "./City", "City", "left(25)", "ALWAYS" }, { "./Country", "Country", "upper", "ALWAYS" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] JD Edwards seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the JD Edwards AIS connector");
		}
	}

	/** D&B: enrichment of a company from its registration number, DUNS stored as an identifier. */
	static void seedDnb(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connector(pContext, pDataset, "DnB", "Dun & Bradstreet Direct+", "dnb", "https://plus.dnb.com", "BASIC", null, null,
					"Direct+ API. Authentication BASIC: username = consumer key, password = consumer secret (bearer token requested on /v2/token). "
							+ "Properties blockIDs, confidence.min, candidates.max, tradeUp, country.");
				ConnectorsInstaller.property(pContext, pDataset, "DnB", "blockIDs", "companyinfo_L2_v1,hierarchyconnections_L1_v1");
				ConnectorsInstaller.property(pContext, pDataset, "DnB", "confidence.min", "7");
				ConnectorsInstaller.property(pContext, pDataset, "DnB", "country", "FR");
				ConnectorsInstaller.property(pContext, pDataset, "DnB", "registration.reference", "TO_BE_SET");
				ConnectorsInstaller.property(pContext, pDataset, "DnB", "registration.path", "TO_BE_SET");
				ConnectorsInstaller.property(pContext, pDataset, "DnB", "notifications.path", "TO_BE_SET");
				final Adaptation row = pDataset.getTable(ConnectorsPaths.CONNECTOR).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString("DnB"));
				final ValueContextForUpdate credentials = pContext.getContext(row.getAdaptationName());
				credentials.setValue("TO_BE_SET", ConnectorsPaths.Connector.USERNAME);
				credentials.setValue("TO_BE_SET", ConnectorsPaths.Connector.PASSWORD);
				pContext.doModifyContent(row, credentials);

				final int in = ConnectorsInstaller.mapping(pContext, pDataset, "DnB", "enrich", "INBOUND",
					"D&B enrichment (registration number or name -> DUNS + company data)", null, "Companies", "/root/Companies", "organization",
					new String[][] { { "registrationNumber", "${./LegalInformation/registrationNumber}" }, { "name", "${./Identification/Name}" },
						{ "country", "${./Address/Country}" }, { "city", "${./Address/City}" } });
				ConnectorsInstaller.rules(pContext, pDataset, in, new String[][] {
					{ "primaryName", "./LegalInformation/legalName", "trim|left(255)", "IF_EMPTY" },
					{ "startDate", "./LegalInformation/incorporationDate", "isodate", "IF_EMPTY" },
					{ "numberOfEmployees[0].value", "./LegalInformation/employeesCount", "number|round(0)", "IF_EMPTY" },
					{ "financials[0].yearlyRevenue[0].value", "./LegalInformation/annualRevenue", "number", "IF_EMPTY" },
					{ "financials[0].yearlyRevenue[0].currency", "./LegalInformation/revenueCurrency", "upper", "IF_EMPTY" },
					{ "websiteAddress[0].url", "./Contact/website", "trim", "IF_EMPTY" }, { "telephone[0].telephoneNumber", "./Contact/phone", "trim|left(40)", "IF_EMPTY" },
					{ "primaryAddress.streetAddress.line1", "./Address/Street", "left(80)", "IF_EMPTY" }, { "primaryAddress.postalCode", "./Address/Zipcode", "left(80)", "IF_EMPTY" },
					{ "primaryAddress.addressLocality.name", "./Address/City", "capitalize|left(80)", "IF_EMPTY" },
					{ "primaryAddress.addressCountry.isoAlpha2Code", "./Address/Country", "upper", "IF_EMPTY" },
					{ "primaryAddress.latitude", "./Address/latitude", "round(7)", "IF_EMPTY" }, { "primaryAddress.longitude", "./Address/longitude", "round(7)", "IF_EMPTY" },
					{ "dunsControlStatus.operatingStatus.description", "./Lifecycle/status", "map(Active=ACTIVE;Out of Business=CLOSED)", "IF_EMPTY" },
					{ "", "./Governance/enrichmentSource", "constant(DNB)", "IF_EMPTY" } });
				// the DUNS itself and the global ultimate go to the identifiers table (single embedded object = a collection of one)
				final int duns = ConnectorsInstaller.childMapping(pContext, pDataset, in, "DnB", "enrich", "INBOUND",
					"D&B enrichment / DUNS identifier", "Companies", "/root/CompanyIdentifier", "", "./company", "./type", "./type='DUNS'");
				ConnectorsInstaller.rules(pContext, pDataset, duns, new String[][] {
					{ "", "./type", "constant(DUNS)", "ALWAYS" }, { "duns", "./value", "digits", "ALWAYS" },
					{ "", "./issuingAuthority", "constant(Dun & Bradstreet)", "ALWAYS" }, { "match.confidenceCode", "./source", "prefix(DNB confidence )", "ALWAYS" } });
				final int ultimate = ConnectorsInstaller.childMapping(pContext, pDataset, in, "DnB", "enrich", "INBOUND",
					"D&B enrichment / global ultimate relationship", "Companies", "/root/CompanyRelationship", "corporateLinkage.globalUltimate", "./fromCompany",
					"./type", "./source='DNB'");
				ConnectorsInstaller.rules(pContext, pDataset, ultimate, new String[][] {
					{ "", "./type", "constant(ULTIMATE_PARENT)", "ALWAYS" }, { "duns", "./relatedLei", "prefix(DUNS:)|left(20)", "ALWAYS" },
					{ "primaryName", "./relatedName", "left(255)", "ALWAYS" }, { "", "./status", "constant(ACTIVE)", "ALWAYS" }, { "", "./source", "constant(DNB)", "ALWAYS" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] D&B seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the D&B connector");
		}
	}

	/**
	 * Backfills safe D&B preview flows for repositories whose legacy Companies model predates the
	 * richer identifier and relationship tables. The flow is deliberately read-only: candidate
	 * review is evidenced before any enrichment or promotion model is introduced.
	 */
	static void seedDnbMappings(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final boolean[] created = new boolean[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final String[][] inputs = new String[][] { { "registrationNumber", "${./Identification/RegistrationID}" },
					{ "name", "${./Identification/Name}" }, { "country", "${./Address/Country}" }, { "city", "${./Address/City}" } };
				if (!ConnectorsInstaller.hasMappingFor(pDataset, "DnB", "match"))
				{
					created[0] |= ConnectorsInstaller.mapping(pContext, pDataset, "DnB", "match", "INBOUND",
						"D&B candidate match (read-only stewardship preview)", null, "Companies", "/root/Companies", "", inputs) >= 0;
				}
				if (!ConnectorsInstaller.hasMappingFor(pDataset, "DnB", "bestMatch"))
				{
					created[0] |= ConnectorsInstaller.mapping(pContext, pDataset, "DnB", "bestMatch", "INBOUND",
						"D&B best match (confidence-gated read-only preview)", null, "Companies", "/root/Companies", "", inputs) >= 0;
				}
				if (!ConnectorsInstaller.hasMappingFor(pDataset, "DnB", "enrich"))
				{
					created[0] |= ConnectorsInstaller.mapping(pContext, pDataset, "DnB", "enrich", "INBOUND",
						"D&B data-block enrichment (read-only preview)", null, "Companies", "/root/Companies", "organization", inputs) >= 0;
				}
				if (created[0])
				{
					ConnectorsInstaller.mark(pContext, pDataset, SeedMarkers.mappings("DnB"));
				}
			}
		});
		if (result.hasFailed()) pLog.error("[connectors] D&B mapping backfill failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		else pLog.info("[connectors] backfilled the D&B Company preview mappings without changing connector credentials");
	}

	/** A connector row unless it already exists (idempotent re-seed). */
	/**
	 * The lot added on 2026-09-06: an LLM as enrichment source, generic SOAP and GraphQL, EBX to EBX,
	 * Slack / Teams notifications, INPI and openFDA. The three free services (EORI validation in SOAP,
	 * the Countries GraphQL API, openFDA) work at once; the others need their secrets.
	 */
	static void seedLot2(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final String todo = "TO_BE_SET";
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Claude", "Claude (Anthropic) - AI enrichment", "llm", "https://api.anthropic.com",
					"BEARER", null, todo, null, null,
					"Large language model as a low-trust enrichment source (trust 30): classify (NACE code from a description), extract (structured address from free text), "
							+ "match (same entity?), complete. Set the API key in apiKey. Properties: model (claude-sonnet-5), maxTokens, temperature, system, language. "
							+ "For OpenAI set baseUrl https://api.openai.com and BEARER; for Azure OpenAI the resource URL, API_KEY_HEADER api-key and model = deployment.",
					new String[][] { { "model", "claude-sonnet-5" }, { "maxTokens", "1024" }, { "temperature", "0" }, { "language", "the language of the text" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "EORI", "EORI validation (EU customs, SOAP)", "soap",
					"https://ec.europa.eu/taxation_customs/dds2/eos/validation/services/validation", "NONE", null, null, null, null,
					"European Commission EORI number validation, a SOAP service configured by properties (the generic soap type): operation validate (eori) "
							+ "returns status, statusDescr, name, street, postalCode, city, country.",
					new String[][] { { "soap.version", "1.1" }, { "op.validate.action", "" },
							{ "op.validate.body", "<ev:validateEORI xmlns:ev=\"http://eori.ws.eos.dds.s/\"><ev:eori>{eori}</ev:eori></ev:validateEORI>" },
							{ "op.validate.result", "result" }, { "op.validate.description", "Validate an EORI number" },
							{ "op.validate.fields", "eori=string;status=number;statusDescr=string;name=string;street=string;postalCode=string;city=string;country=string" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Countries", "Countries (GraphQL reference data)", "graphql",
					"https://countries.trevorblades.com/graphql", "NONE", null, null, null, null,
					"Free GraphQL API of countries (the generic graphql type): operation country (code ISO-2) returns name, capital, currency, languages, continent.",
					new String[][] { { "op.country.query", "query($code: ID!) { country(code: $code) { code name native capital currency emoji phone continent { name } languages { name } } }" },
							{ "op.country.result", "country" }, { "op.country.description", "Country by ISO-2 code" },
							{ "op.country.fields", "code=string;name=string;native=string;capital=string;currency=string;emoji=string;phone=string;continent.name=string;languages[0].name=string" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "EBX", "EBX (another instance)", "ebx", "http://localhost:8080/ebx-dataservices/rest/data/v1",
					"BASIC", null, null, todo, todo,
					"Another EBX repository through Data Services: read (dataspace, dataset, table, key), search (feed: rows), write (create or update from an outbound mapping). "
							+ "Set the account in username / password; envelopes are unwrapped so that mappings see plain records.",
					new String[][] { { "dataspacePrefix", "B" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "ChatWebhook", "Slack / Teams notifications", "chat-webhook", "https://hooks.slack.com/services/TO_BE_SET",
					"NONE", null, null, null, null,
					"Incoming webhook of a Slack channel or a Teams channel: the base URL is the webhook URL (it is a secret: restrict who can read this connector). "
							+ "Operation notify (text, title). Property format: auto, slack, teams (Office connector card) or adaptive (Power Automate workflow).",
					new String[][] { { "format", "auto" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "JMS", "JMS messaging", "jms", "tcp://BROKER:61616",
					"NONE", null, null, null, null,
					"Provider-neutral JMS queues and topics through JNDI. Operations publish, receive and non-consuming peek are bounded; acknowledgement and redelivery are explicit.",
					new String[][] { { "initial.context.factory", todo }, { "connection.factory", "ConnectionFactory" }, { "destination", todo },
						{ "destination.type", "QUEUE" }, { "acknowledgement", "CLIENT" }, { "max.messages", "100" }, { "receive.timeout.ms", "3000" },
						{ "redelivery.max", "6" }, { "redelivery.delay.ms", "1000" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "ActiveMQ", "Apache ActiveMQ", "activemq", "tcp://localhost:61616",
					"NONE", null, null, null, null,
					"Apache ActiveMQ Classic JMS queues and topics. Operations publish, receive and non-consuming peek; supports selectors, client or transactional acknowledgement, and redelivery policy.",
					new String[][] { { "initial.context.factory", "org.apache.activemq.jndi.ActiveMQInitialContextFactory" },
						{ "connection.factory", "ConnectionFactory" }, { "destination", "dynamicQueues/ebx.connectors" }, { "destination.type", "QUEUE" },
						{ "acknowledgement", "CLIENT" }, { "max.messages", "100" }, { "receive.timeout.ms", "3000" },
						{ "redelivery.max", "6" }, { "redelivery.delay.ms", "1000" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "INPI", "INPI - Registre National des Entreprises", "inpi",
					"https://registre-national-entreprises.inpi.fr/api", "BASIC", null, null, todo, todo,
					"Official French register (legal identity, head office, beneficial owners). Free account: set username / password; a bearer token is obtained and cached. "
							+ "Operations company (siren), search (name, postalCode).",
					new String[][] { { "token.ttl", "3000" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "OpenFDA", "openFDA (US FDA devices and drugs)", "openfda", "https://api.fda.gov", "NONE", null, null, null,
					null, "Public US FDA API, no key (an optional key in apiKey raises the quota). Operations udi (device identifier -> GUDID record), deviceSearch (brand, company), drug (NDC).",
					new String[0][]);
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] lot 2 seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded Claude, EORI, Countries, EBX, ChatWebhook, INPI and OpenFDA");
		}
	}

	/**
	 * The Slack Web API connector (2026-09-15), next to the ChatWebhook one: a bot token in apiKey instead of a
	 * secret URL, any channel, edits and direct messages. Token and default channel are placeholders.
	 */
	static void seedSlack(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final String todo = "TO_BE_SET";
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Slack", "Slack", "slack", "https://slack.com/api",
					"BEARER", null, todo, null, null,
					"Slack Web API with the bot token of a Slack app in apiKey (xoxb-...): post (channel, text, title, threadTs, blocks), notify (same as post, "
							+ "so a flow written for chat-webhook works unchanged), update (edit a posted message by channel + ts), dm (member found by email), "
							+ "channels (search). Bot scopes: chat:write, chat:write.public, channels:read, users:read.email (groups:read for private channels). "
							+ "Property channel = default channel (#name or id).",
					new String[][] { { "channel", todo } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] Slack seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the Slack connector");
		}
	}

	/**
	 * The MCP servers of EBX itself as connectors of type {@code mcp} (2026-09-20): data-model, match-merge and
	 * data-access, what the MDM Factory drives. Basic authentication with a placeholder account: the user of the
	 * connector is the identity every tool call runs as, so a dedicated, least-privileged account belongs here.
	 * The host is a seed value; a server must also be enabled in Administration &gt; MCP servers.
	 */
	static void seedEbxMcpServers(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final String todo = "TO_BE_SET";
		final String[][] servers = { { "EbxMcpDataModel", "EBX MCP: data-model", "data-model", "build, patch, validate, compile and publish data models, create a dataset from a publication" },
			{ "EbxMcpMatchMerge", "EBX MCP: match-merge", "match-merge", "profile a table, generate and apply matching and merge policies, activate matching" },
			{ "EbxMcpDataAccess", "EBX MCP: data-access", "data-access", "read-only discovery of dataspaces, datasets, tables and records, and read-only SQL" } };
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final String[] server : servers)
				{
					ConnectorsInstaller.connectorIfMissing(pContext, pDataset, server[0], server[1], "mcp", "http://localhost:8080/ebx-assistant/mcp/v1/" + server[2],
						"BASIC", null, null, todo, todo,
						"MCP server '" + server[2] + "' of this EBX instance: " + server[3] + ". Operations listTools and callTool (tool + arguments). "
								+ "Every call runs as the user of this connector: give it a dedicated account that may do no more than the callers should. "
								+ "An empty tool list means the server is not enabled in Administration > MCP servers.",
						new String[0][]);
				}
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] EBX MCP servers seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the EBX MCP server connectors");
		}
	}

	/**
	 * The other language-model providers next to Claude (2026-09-14): OpenAI, Google Gemini, Microsoft
	 * Azure OpenAI, Mistral AI and a local Ollama. All of type {@code llm}, trust 30; keys are
	 * placeholders, Ollama needs none.
	 */
	static void seedLlmProviders(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final String todo = "TO_BE_SET";
		final String usage = "Large language model as a low-trust enrichment source (trust 30): classify, extract, match, complete. ";
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "OpenAI", "OpenAI - AI enrichment", "llm", "https://api.openai.com",
					"BEARER", null, todo, null, null,
					usage + "Set the platform.openai.com key in apiKey (Bearer). For reasoning models set temperature = none and openai.maxTokensParam = max_completion_tokens.",
					new String[][] { { "provider", "openai" }, { "model", "gpt-4o-mini" }, { "maxTokens", "1024" }, { "temperature", "0" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Gemini", "Gemini (Google) - AI enrichment", "llm", "https://generativelanguage.googleapis.com",
					"API_KEY_HEADER", "x-goog-api-key", todo, null, null,
					usage + "Gemini API: a Google AI Studio key in apiKey (header x-goog-api-key). For Vertex AI set baseUrl https://<location>-aiplatform.googleapis.com, "
							+ "OAUTH2 (service account) and the properties google.project / google.location.",
					new String[][] { { "provider", "google" }, { "model", "gemini-2.5-flash" }, { "maxTokens", "1024" }, { "temperature", "0" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "AzureOpenAI", "Azure OpenAI (Microsoft) - AI enrichment", "llm", "https://TO_BE_SET.openai.azure.com",
					"API_KEY_HEADER", "api-key", todo, null, null,
					usage + "Base URL = the Azure OpenAI resource, apiKey = one of its keys (header api-key), model = the DEPLOYMENT name. azure.apiVersion = v1 for the v1 API. "
							+ "For models deployed in Azure AI Foundry (Phi, Llama, Mistral...) set provider = azure-ai and baseUrl https://<resource>.services.ai.azure.com.",
					new String[][] { { "provider", "azure" }, { "model", "gpt-4o-mini" }, { "azure.apiVersion", "2024-10-21" }, { "maxTokens", "1024" }, { "temperature", "0" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Mistral", "Mistral AI - AI enrichment", "llm", "https://api.mistral.ai",
					"BEARER", null, todo, null, null,
					usage + "Set the console.mistral.ai key in apiKey (Bearer). JSON answers are requested natively (jsonMode auto).",
					new String[][] { { "provider", "mistral" }, { "model", "mistral-small-latest" }, { "maxTokens", "1024" }, { "temperature", "0" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Ollama", "Ollama (local models) - AI enrichment", "llm", "http://localhost:11434",
					"NONE", null, null, null, null,
					usage + "Models served by Ollama on your own infrastructure: no key, nothing leaves the network. Pull the model first (ollama pull llama3.2); "
							+ "behind an authenticating proxy use BEARER.",
					new String[][] { { "provider", "ollama" }, { "model", "llama3.2" }, { "maxTokens", "1024" }, { "temperature", "0" } });
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] LLM providers seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the OpenAI, Gemini, AzureOpenAI, Mistral and Ollama connectors (those missing)");
		}
	}

	/** Additive catalog records introduced by connector-enrichment waves. Existing rows are untouched. */
	static void seedWaveCatalog(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final String todo = "TO_BE_SET";
		final int[] created = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				created[0] += add(pContext, pDataset, "Db2-LUW", "IBM Db2 LUW", "db2-luw", "jdbc:db2://HOST:50000/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated Db2 LUW JDBC connector with bounded discovery, prepared SQL, batching and transactions.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "IRIS", "InterSystems IRIS", "iris", "jdbc:IRIS://HOST:1972/NAMESPACE", "BASIC", null, null, todo, todo,
					"Dedicated InterSystems IRIS JDBC connector with bounded metadata and parameterized operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "SapAse", "SAP ASE", "sap-ase", "jdbc:sybase:Tds:HOST:5000/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated SAP ASE JDBC connector with bounded metadata and parameterized operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "SapHana", "SAP HANA", "sap-hana", "jdbc:sap://HOST:30015", "BASIC", null, null, todo, todo,
					"Dedicated SAP HANA JDBC connector with bounded metadata and parameterized operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "Teradata", "Teradata", "teradata", "jdbc:teradata://HOST/DATABASE=DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated Teradata JDBC connector with bounded discovery and parameterized operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "Vertica", "Vertica", "vertica", "jdbc:vertica://HOST:5433/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated Vertica JDBC connector with bounded discovery and parameterized operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "Redshift", "Amazon Redshift", "redshift", "jdbc:redshift://CLUSTER.ACCOUNT.REGION.redshift.amazonaws.com:5439/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated Amazon Redshift JDBC profile (endpoint URL, vendor driver 2.x) with bounded discovery and parameterized operations.", new String[][] { { "schema", "public" }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "ActianVector", "Actian Avalanche / Vector", "actian-vector", "jdbc:ingres://HOST:II7/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated Actian Avalanche and Vector JDBC profile with bounded discovery and operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "ActianIngres", "Actian Ingres", "actian-ingres", "jdbc:ingres://HOST:II7/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated Actian Ingres JDBC profile with bounded discovery and operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "MonetDB", "MonetDB", "monetdb", "jdbc:monetdb://HOST:50000/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated MonetDB JDBC profile with bounded discovery and parameterized operations.", new String[][] { { "schema", "sys" }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "AmazonSQS", "Amazon SQS", "amazon-sqs", "", "NONE", null, null, null, null,
					"Amazon SQS with signed bounded send, receive and explicit acknowledgement.", new String[][] { { "queueUrl", todo }, { "region", todo } });
				created[0] += add(pContext, pDataset, "AmazonSNS", "Amazon SNS", "amazon-sns", "", "NONE", null, null, null, null,
					"Amazon SNS with signed bounded topic discovery and publish.", new String[][] { { "topicArn", todo }, { "region", todo } });
				created[0] += add(pContext, pDataset, "GooglePubSub", "Google Pub/Sub", "google-pubsub", "https://pubsub.googleapis.com/v1", "BEARER", null, todo, null, null,
					"Google Pub/Sub bounded publish, pull and explicit acknowledgement.", new String[][] { { "project", todo } });
				created[0] += add(pContext, pDataset, "FTP-FTPS", "FTP / FTPS", "ftp", "ftps://HOST:990", "BASIC", null, null, todo, todo,
					"Bounded FTP/FTPS file transport; TLS is required unless cleartext is explicitly enabled.", new String[][] { { "root", "/" } });
				created[0] += add(pContext, pDataset, "AzureQueueStorage", "Azure Queue Storage", "azure-queue-storage", "https://ACCOUNT.queue.core.windows.net", "API_KEY_HEADER", null, todo, null, null,
					"Azure Queue Storage bounded send, peek, receive and explicit acknowledgement.", new String[][] { { "queue", todo } });
				created[0] += add(pContext, pDataset, "Azure-Table", "Azure Table Storage", "azure-table", "https://ACCOUNT.table.core.windows.net", "API_KEY_HEADER", null, todo, null, null,
					"Azure Table Storage bounded discovery, keyed entities and same-partition batches.", new String[][] { { "table", todo } });
				created[0] += add(pContext, pDataset, "AzureCosmosDB", "Azure Cosmos DB (NoSQL)", "azure-cosmos-db", "https://ACCOUNT.documents.azure.com", "BEARER", null, todo, null, null,
					"Azure Cosmos DB bounded discovery, partition-scoped items and parameterized queries; Shared Key and change feed excluded.", new String[0][]);
				created[0] += add(pContext, pDataset, "GoogleSheets", "Google Sheets", "google-sheets", "https://sheets.googleapis.com/v4", "BEARER", null, todo, null, null,
					"Google Sheets metadata and bounded explicit-range reads and writes.", new String[0][]);
				created[0] += add(pContext, pDataset, "Elasticsearch", "Elasticsearch", "elasticsearch", "https://HOST:9200", "API_KEY_HEADER", null, todo, null, null,
					"Elasticsearch bounded index discovery, mappings, search_after reads and bulk indexing.", new String[0][]);
				created[0] += add(pContext, pDataset, "Cassandra", "Apache Cassandra", "cassandra", "HOST:9042", "BASIC", null, null, todo, todo,
					"Apache Cassandra bounded discovery and prepared-CQL reads and writes; CDC is excluded.", new String[][] { { "tls", "true" }, { "page.size", "100" } });
				created[0] += add(pContext, pDataset, "MongoDB", "MongoDB", "mongodb", "mongodb+srv://HOST/DATABASE", "BASIC", null, null, todo, todo,
					"MongoDB bounded discovery, document CRUD and keyset pagination; change streams are excluded.", new String[][] { { "authDatabase", "admin" } });
				created[0] += add(pContext, pDataset, "Couchbase", "Couchbase", "couchbase", "couchbases://HOST", "BASIC", null, null, todo, todo,
					"Couchbase bounded bucket and collection discovery with single-document KV operations.", new String[0][]);
				created[0] += add(pContext, pDataset, "CMIS", "CMIS Repository", "cmis", "https://HOST/cmis/browser", "BASIC", null, null, todo, todo,
					"CMIS 1.1 Browser Binding repository discovery, bounded content and controlled document writes.", new String[0][]);
				created[0] += add(pContext, pDataset, "VeevaVault", "Veeva Vault CRM", "veeva-vault", "https://CUSTOMER.veevavault.com", "BEARER", null, todo, null, null,
					"Read-only Vault object discovery and bounded VQL; writes and bulk jobs require a later acceptance tranche.", new String[][] { { "apiVersion", "v25.3" } });
				created[0] += add(pContext, pDataset, "Alation", "Alation Data Catalog", "alation", "https://ALATION.INSTANCE", "API_KEY_HEADER", "TOKEN", todo, null, null,
					"Read-only bounded Alation search and relational catalog discovery.", new String[0][]);
				created[0] += add(pContext, pDataset, "MicrosoftPurview", "Microsoft Purview Data Map", "microsoft-purview", "https://ACCOUNT.purview.azure.com", "OAUTH2", null, todo, null, null,
					"Read-only bounded Purview Data Map discovery and Atlas entity/type reads.", new String[][] { { "tenantId", todo }, { "clientId", todo } });
				created[0] += add(pContext, pDataset, "HadoopWebHDFS", "Hadoop WebHDFS / HttpFS", "hadoop-webhdfs", "https://HOST/webhdfs/v1", "BEARER", null, todo, null, null,
					"Bounded Hadoop-compatible file access through a non-redirecting HTTPS WebHDFS/HttpFS gateway.", new String[0][]);
				created[0] += add(pContext, pDataset, "SparkSQL", "Apache Spark SQL", "spark-sql", "jdbc:hive2://HOST:10000/default;ssl=true", "BASIC", null, null, todo, todo,
					"Read-only bounded Spark SQL through an administrator-managed HiveServer2/Thrift JDBC endpoint.", new String[][] { { "driver", "org.apache.hive.jdbc.HiveDriver" }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "DominoREST", "HCL Domino REST API", "domino-rest", "https://HOST/api/v1", "BEARER", null, todo, null, null,
					"Read-only Domino REST API Basis discovery and bounded DQL without proprietary runtime libraries.", new String[][] { { "scope", todo } });
				created[0] += add(pContext, pDataset, "Firebird", "Firebird", "firebird", "jdbc:firebirdsql://HOST:3050/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated Firebird JDBC profile with bounded discovery and parameterized operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "Greenplum", "Greenplum", "greenplum", "jdbc:postgresql://HOST:5432/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated Greenplum JDBC profile with bounded discovery and parameterized operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "SingleStore", "SingleStore", "singlestore", "jdbc:singlestore://HOST:3306/DATABASE", "BASIC", null, null, todo, todo,
					"Dedicated SingleStore JDBC profile with bounded discovery and parameterized operations.", new String[][] { { "schema", todo }, { "max.rows", "1000" } });
				created[0] += add(pContext, pDataset, "GenericREST", "Generic REST", "rest", "https://HOST/api", "NONE", null, null, null, null,
					"Governed generic REST connector for explicitly configured endpoints and operations.", new String[0][]);
				created[0] += add(pContext, pDataset, "OpenLineage", "OpenLineage", "openlineage", "https://HOST/api/v1/lineage", "BEARER", null, todo, null, null,
					"OpenLineage run-event sink for an explicitly configured catalog endpoint.", new String[0][]);
				created[0] += add(pContext, pDataset, "GoogleDrive", "Google Drive", "google-drive", "https://www.googleapis.com/drive/v3", "BEARER", null, todo, null, null,
					"Read-only, explicitly scoped Google Drive metadata inventory with governance labels.", new String[][] { { "folderId", todo } });
				created[0] += add(pContext, pDataset, "OneDrive", "Microsoft OneDrive", "onedrive", "https://graph.microsoft.com/v1.0", "BEARER", null, todo, null, null,
					"Read-only, explicitly scoped Microsoft OneDrive metadata inventory with governance labels.", new String[][] { { "driveId", todo } });
				created[0] += add(pContext, pDataset, "SampleFile", "Sample file (CSV / JSON)", "sample-file", "local://sample-file", "NONE", null, null, null, null,
					"Manually uploaded CSV/JSON sample serving a source's shape before a real connection exists; read-only, replace once one does.", new String[0][]);
				// a preview function (decision of 2026-11-12): seeded only when the administrator opened it, and (audit C3-03) with NO language
				// model: a person chooses which model may receive documents, the seed never does
				if (ConnectorsInstaller.seedsDocuments())
				{
					created[0] += add(pContext, pDataset, "Documents", "Documents (PDF, DOCX, text) with AI extraction", "documents", "local://documents", "NONE", null, null, null, null,
						"Reads PDF, DOCX and text documents (uploaded, or from a folder under ebx.connectors.documents.roots) and extracts the target attributes through the LLM connector chosen in the property llm.connector, with a confidence and a snippet each; below the threshold a value is proposed, not written.",
						ConnectorsInstaller.documentsSeedProperties());
				}
			}
		});
		if (result.hasFailed()) pLog.error("[connectors] wave catalog seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		else if (created[0] > 0) pLog.info("[connectors] seeded " + created[0] + " missing wave connector catalog record(s)");
	}

	/** Whether the "Documents" connection is seeded: only while the administrator has opened the {@code documents} preview function. */
	static boolean seedsDocuments()
	{
		return com.on.ebx.connect.core.PreviewFeatures.isOpen(com.on.ebx.connect.core.PreviewFeatures.Feature.DOCUMENTS);
	}

	/** The properties of the seeded "Documents" connection: the confidence threshold, and NO language model (audit C3-03). */
	static String[][] documentsSeedProperties()
	{
		return new String[][] { { "confidence.threshold", "0.8" } };
	}

	private static int add(final ProcedureContext pContext, final Adaptation pDataset, final String pId, final String pLabel,
		final String pType, final String pUrl, final String pAuth, final String pKeyHeader, final String pKey, final String pUser,
		final String pPassword, final String pDescription, final String[][] pProperties) throws Exception
	{
		return ConnectorsInstaller.connectorIfMissing(pContext, pDataset, pId, pLabel, pType, pUrl, pAuth, pKeyHeader, pKey, pUser, pPassword,
			pDescription, pProperties) ? 1 : 0;
	}

	private static boolean connectorIfMissing(final ProcedureContext pContext, final Adaptation pDataset, final String pId, final String pLabel,
		final String pType, final String pUrl, final String pAuth, final String pKeyHeader, final String pKey, final String pUser, final String pPassword,
		final String pDescription, final String[][] pProperties) throws Exception
	{
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.CONNECTOR);
		if (!ConnectorsInstaller.markers.mayCreateConnector(pId, table.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pId)) != null))
		{
			return false; // there already, or created once and deleted since
		}
		ConnectorsInstaller.connector(pContext, pDataset, pId, pLabel, pType, pUrl, pAuth, pKeyHeader, pKey, pDescription);
		if (pUser != null || pPassword != null)
		{
			final Adaptation row = table.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pId));
			final ValueContextForUpdate credentials = pContext.getContext(row.getAdaptationName());
			credentials.setValue(pUser, ConnectorsPaths.Connector.USERNAME);
			credentials.setValue(pPassword, ConnectorsPaths.Connector.PASSWORD);
			pContext.doModifyContent(row, credentials);
		}
		for (final String[] property : pProperties)
		{
			ConnectorsInstaller.property(pContext, pDataset, pId, property[0], property[1]);
		}
		return true;
	}

	/**
	 * The catalogue added on 2026-09-04 evening: registries (VIES, OpenCorporates, Companies House,
	 * Handelsregister, Orbis, Creditsafe), addresses (BAN, Loqate, Melissa), sanctions
	 * (OpenSanctions), business applications (Dynamics 365, Oracle Fusion, HubSpot, ServiceNow,
	 * Workday), platforms (BigQuery, JDBC, SFTP, S3, Azure Blob, GCS) and the inbound webhook.
	 * Secrets are placeholders; a few Companies mappings show the free services at work.
	 */
	static void seedCatalog(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final String todo = "TO_BE_SET";
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				// ---- registries
				if (ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "VIES", "VIES (EU VAT check)", "vies", "https://ec.europa.eu/taxation_customs/vies/rest-api",
					"NONE", null, null, null, null, "European Commission VAT number validation, no key. Operation check (countryCode, vatNumber).", new String[0][]))
				{
					final int vies = ConnectorsInstaller.mapping(pContext, pDataset, "VIES", "check", "INBOUND", "VIES VAT check (EU VAT number -> validity, name, address)",
						null, "Companies", "/root/Companies", "",
						new String[][] { { "vat", "${./LegalInformation/registrationNumber|vatfr}" } });
					ConnectorsInstaller.rules(pContext, pDataset, vies, new String[][] {
						{ "name", "./LegalInformation/legalName", "trim|left(255)", "IF_EMPTY" },
						{ "", "./Governance/enrichmentSource", "constant(VIES)", "IF_EMPTY" },
						{ "requestDate", "./Governance/lastEnrichmentDate", "isodatetime", "ALWAYS" } });
					final int vat = ConnectorsInstaller.childMapping(pContext, pDataset, vies, "VIES", "check", "INBOUND", "VIES VAT check / VAT identifier",
						"Companies", "/root/CompanyIdentifier", "", "./company", "./type", "./type='VAT'");
					ConnectorsInstaller.rules(pContext, pDataset, vat, new String[][] {
						{ "", "./type", "constant(VAT)", "ALWAYS" }, { "vat", "./value", "trim", "ALWAYS" },
						{ "", "./issuingAuthority", "constant(VIES)", "ALWAYS" }, { "valid", "./source", "map(true=VIES valid;false=VIES invalid)", "ALWAYS" } });
				}
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "OpenCorporates", "OpenCorporates", "opencorporates", "https://api.opencorporates.com/v0.4",
					"API_KEY_HEADER", "api_token", todo, null, null, "Worldwide company registry aggregate. apiKey = api_token. Operations get (jurisdiction, number), search (q, jurisdiction).",
					new String[0][]);
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "CompaniesHouse", "Companies House (UK)", "companies-house", "https://api.company-information.service.gov.uk",
					"API_KEY_HEADER", null, todo, null, null, "UK register. apiKey = Companies House API key (sent as Basic user). Operations get, search, officers, pscs, filings.",
					new String[0][]);
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Handelsregister", "Handelsregister (OffeneRegister, DE)", "handelsregister", "https://db.offeneregister.de",
					"NONE", null, null, null, null, "German register through the OffeneRegister open data mirror, no key. Operations search (name), get (id).", new String[0][]);
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Orbis", "Moody's Orbis", "orbis", "https://api.bvdinfo.com/v1/orbis",
					"API_KEY_HEADER", "ApiToken", todo, null, null, "Bureau van Dijk Orbis. apiKey = ApiToken. Operations match (name, country, registration number), data (bvdId), property select.",
					new String[][] { { "select", "NAME,ADDRESS_LINE1,POSTCODE,CITY,COUNTRY_ISO_CODE,NATIONAL_ID,VAT_NUMBER,LEI,EMPL,OPRE,STATUS" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Creditsafe", "Creditsafe Connect", "creditsafe", "https://connect.creditsafe.com/v1",
					"BASIC", null, null, todo, todo, "Creditsafe Connect API. Username / password of the API account (token from /authenticate). Operations search, report, find.",
					new String[][] { { "countries", "FR" } });
				// ---- addresses
				if (ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "BAN", "Base Adresse Nationale (FR)", "ban", "https://api-adresse.data.gouv.fr",
					"NONE", null, null, null, null, "French national address base, no key. Operations geocode (address, postcode), reverse (lat, lon). Property score.min.",
					new String[][] { { "score.min", "0.5" } }))
				{
					final int ban = ConnectorsInstaller.mapping(pContext, pDataset, "BAN", "geocode", "INBOUND", "BAN geocoding (French address -> normalised address + coordinates)",
						null, "Companies", "/root/Companies", "",
						new String[][] { { "address", "${./Address/Street} ${./Address/Zipcode} ${./Address/City}" }, { "postcode", "${./Address/Zipcode}" } });
					ConnectorsInstaller.rules(pContext, pDataset, ban, new String[][] {
						{ "lat", "./Address/latitude", "round(7)", "IF_EMPTY" }, { "lon", "./Address/longitude", "round(7)", "IF_EMPTY" },
						{ "postcode", "./Address/Zipcode", "trim", "IF_EMPTY" }, { "city", "./Address/City", "capitalize|left(80)", "IF_EMPTY" },
						{ "", "./Governance/enrichmentSource", "constant(BAN)", "IF_EMPTY" } });
				}
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Loqate", "Loqate", "loqate", "https://api.addressy.com", "API_KEY_HEADER", null, todo, null, null,
					"Loqate international address verification. apiKey = Loqate key. Operations verify (address, locality, postalCode, country), find, retrieve.",
					new String[][] { { "geocode", "true" }, { "verification.min", "V" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Melissa", "Melissa Data", "melissa", "https://address.melissadata.net", "API_KEY_HEADER", null, todo, null, null,
					"Melissa global address, email and phone verification. apiKey = license key. Operations address, email, phone.",
					new String[][] { { "country", "FR" }, { "verified.only", "true" } });
				// ---- sanctions
				if (ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "OpenSanctions", "OpenSanctions (OFAC, UN, EU, PEP)", "opensanctions", "https://api.opensanctions.org",
					"API_KEY_HEADER", "Authorization", todo, null, null,
					"Sanctions and PEP screening over OFAC SDN, UN, EU, UK and 200+ lists. apiKey = OpenSanctions API key. Operations screen (name, registrationNumber, country), search, entity. "
							+ "Properties dataset (default, sanctions, us_ofac_sdn, eu_fsf, un_sc_sanctions), threshold.",
					new String[][] { { "dataset", "default" }, { "threshold", "0.7" } }))
				{
					final int screen = ConnectorsInstaller.mapping(pContext, pDataset, "OpenSanctions", "screen", "INBOUND",
						"Sanctions screening (hit -> blacklist reason and date)", null, "Companies", "/root/Companies", "",
						new String[][] { { "name", "${./Identification/Name}" }, { "registrationNumber", "${./LegalInformation/registrationNumber}" },
							{ "country", "${./Address/Country}" }, { "schema", "Company" } });
					ConnectorsInstaller.rules(pContext, pDataset, screen, new String[][] {
						{ "bestSummary.datasets", "./Lifecycle/blacklistReason", "prefix(Sanctions hit: )|left(255)", "ALWAYS" },
						{ "screenedAt", "./Lifecycle/blacklistDate", "isodate", "ALWAYS" },
						{ "hit", "./Lifecycle/status", "map(true=SUSPENDED)", "ALWAYS" },
						{ "screenedAt", "./Governance/lastEnrichmentDate", "isodatetime", "ALWAYS" } });
				}
				// ---- business applications
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Dynamics365", "Microsoft Dynamics 365", "dynamics365", "https://ORG.crm4.dynamics.com", "BEARER", null, todo,
					null, null, "Dataverse Web API (OData v4). Entra ID client credentials: properties tenantId, clientId, apiKey = client secret. Objects accounts, contacts...",
					new String[][] { { "tenantId", todo }, { "clientId", todo }, { "apiVersion", "v9.2" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "OracleFusion", "Oracle Fusion Cloud", "oracle-fusion", "https://HOST.oraclecloud.com", "BASIC", null, null, todo, todo,
					"Oracle Fusion Cloud Applications REST (ERP / SCM / HCM / CX). Integration user in BASIC. Properties family (fscmRestApi, crmRestApi, hcmRestApi), apiVersion.",
					new String[][] { { "family", "fscmRestApi" }, { "apiVersion", "11.13.18.05" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "HubSpot", "HubSpot CRM", "hubspot", "https://api.hubapi.com", "BEARER", null, todo, null, null,
					"HubSpot CRM v3. apiKey = private app token. Objects companies, contacts, deals; upsert on a lookup field such as domain.", new String[0][]);
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "ServiceNow", "ServiceNow", "servicenow", "https://INSTANCE.service-now.com", "BASIC", null, null, todo, todo,
					"ServiceNow Table API. Tables core_company, customer_account, cmdb_ci... Filter = encoded query (name=ACME^country=FR).",
					new String[][] { { "display.values", "false" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Workday", "Workday", "workday", "https://wd3-impl-services1.workday.com", "BASIC", null, todo, todo, todo,
					"Workday REST (OAuth refresh token: properties clientId, refreshToken, apiKey = client secret) and RaaS reports (integration user in BASIC). Property tenant.",
					new String[][] { { "tenant", todo }, { "clientId", todo }, { "refreshToken", todo } });
				// ---- platforms
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "BigQuery", "Google BigQuery", "bigquery", "https://bigquery.googleapis.com", "NONE", null, null, null, null,
					"BigQuery jobs.query with @named parameters. Service account key: property serviceAccountFile (a file of the folder named by -Debx.connectors.secrets.dir, written relative to it; any other path is refused) or serviceAccountJson. Properties project, dataset, location.",
					new String[][] { { "project", todo }, { "dataset", todo }, { "serviceAccountFile", todo } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "JDBC", "JDBC database", "jdbc", "jdbc:postgresql://localhost:5432/mdm", "BASIC", null, null, todo, todo,
					"Any database with a JDBC driver in ebx-ps-fasttrack-server/lib (PostgreSQL, H2, Oracle, SQL Server, MySQL, Azure Synapse, Redshift...). "
							+ "baseUrl = JDBC URL. Operations query (:named parameters), get, insert, update, upsert, execute. Property schema, driver, jdbc.* passed to the driver.",
					new String[][] { { "max.rows", "1000" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "PostgreSQL", "PostgreSQL", "postgresql", "jdbc:postgresql://localhost:5432/mdm", "BASIC", null, null, todo, todo,
					"Dedicated PostgreSQL connector. Supports query, get, insert, update, upsert, execute, schema discovery, preview, batching, and transactions. "
						+ "The PostgreSQL JDBC driver is packaged with EBX-Connectors; use property jndi for a container-managed DataSource.",
					new String[][] { { "driver", "org.postgresql.Driver" }, { "schema", "public" }, { "max.rows", "1000" },
						{ "fetch.size", "100" }, { "batch.size", "100" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "H2", "H2 Database", "h2", "jdbc:h2:tcp://localhost:9092/mdm", "BASIC", null, null, "sa", todo,
					"Dedicated H2 connector for embedded or TCP databases. Supports query, get, insert, update, upsert, execute, schema discovery, preview, batching, and transactions. "
						+ "The H2 JDBC driver is packaged with EBX-Connectors; use property jndi for a container-managed DataSource.",
					new String[][] { { "driver", "org.h2.Driver" }, { "schema", "PUBLIC" }, { "max.rows", "1000" },
						{ "fetch.size", "100" }, { "batch.size", "100" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "MySQL", "MySQL Database", "mysql", "jdbc:mysql://HOST:3306/MDM", "BASIC", null, null, todo, todo,
					"Dedicated MySQL connector with verified TLS, parameterized SQL, bounded preview, discovery, batching and transactions.",
					new String[][] { { "driver", "com.mysql.cj.jdbc.Driver" }, { "jdbc.sslMode", "VERIFY_IDENTITY" },
						{ "jdbc.allowPublicKeyRetrieval", "false" }, { "jdbc.useServerPrepStmts", "true" }, { "jdbc.serverTimezone", "UTC" },
						{ "max.rows", "1000" }, { "fetch.size", "100" }, { "batch.size", "100" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "SQLServer", "Microsoft SQL Server", "sql-server", "jdbc:sqlserver://HOST:1433;databaseName=MDM", "BASIC", null, null, todo, todo,
					"Dedicated on-premises Microsoft SQL Server connector. Encrypted transport and certificate validation are required; use property jndi for a managed DataSource.",
					new String[][] { { "driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver" }, { "schema", "dbo" },
						{ "jdbc.encrypt", "true" }, { "jdbc.trustServerCertificate", "false" }, { "max.rows", "1000" },
						{ "fetch.size", "100" }, { "batch.size", "100" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "AzureSQL", "Azure SQL Database", "azure-sql", "jdbc:sqlserver://SERVER.database.windows.net:1433;databaseName=MDM", "BASIC", null, null, todo, todo,
					"Dedicated Azure SQL Database connector. Requires an Azure SQL endpoint, TLS encryption, certificate validation, and the Microsoft JDBC driver packaged with EBX-Connectors.",
					new String[][] { { "driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver" }, { "schema", "dbo" },
						{ "jdbc.encrypt", "true" }, { "jdbc.trustServerCertificate", "false" },
						{ "jdbc.hostNameInCertificate", "*.database.windows.net" }, { "max.rows", "1000" },
						{ "fetch.size", "100" }, { "batch.size", "100" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "AzureServiceBus", "Microsoft Azure Service Bus", "azure-service-bus", "sb://NAMESPACE.servicebus.windows.net", "NONE", null, todo, null, null,
					"Azure Service Bus queues and topics. Store the connection string in the encrypted API key field, or select AZURE_IDENTITY; bounded receive defaults to peek-lock and requires explicit complete/abandon settlement.",
					new String[][] { { "auth.mode", "CONNECTION_STRING" }, { "entity", todo }, { "receive.mode", "PEEK_LOCK" },
						{ "max.messages", "100" }, { "wait.ms", "5000" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "BusinessCentral", "Microsoft Dynamics 365 Business Central", "dynamics-business-central", "https://api.businesscentral.dynamics.com", "OAUTH2", null, null, todo, todo,
					"Business Central standard API over bounded OData v4, scoped by tenant, environment and company.",
					new String[][] { { "tenantId", todo }, { "environment", "Production" }, { "companyId", todo }, { "apiVersion", "v2.0" }, { "max.rows", "200" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "SuccessFactors", "SAP SuccessFactors", "sap-successfactors", "https://HOST.successfactors.com", "OAUTH2", null, null, todo, todo,
					"SAP SuccessFactors OData v2 profile for person, employment and user master data.",
					new String[][] { { "companyId", todo }, { "max.rows", "200" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "SAPAriba", "SAP Ariba", "sap-ariba", "https://openapi.ariba.com", "API_KEY_HEADER", "apiKey", todo, null, null,
					"SAP Ariba supplier and procurement APIs with bounded reads and realm-scoped writes.",
					new String[][] { { "realm", todo }, { "max.rows", "200" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "NetSuite", "Oracle NetSuite", "netsuite", "https://ACCOUNT.suitetalk.api.netsuite.com/services/rest", "BEARER", null, todo, null, null,
					"NetSuite SuiteTalk REST Record and bounded SuiteQL connector.",
					new String[][] { { "account", todo }, { "max.rows", "200" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "SharePoint", "Microsoft SharePoint Online", "sharepoint", "https://graph.microsoft.com/v1.0", "OAUTH2", null, null, todo, todo,
					"Microsoft Graph connector for bounded SharePoint site, list, item, drive and master-data file operations.",
					new String[][] { { "tenantId", todo }, { "max.rows", "200" }, { "max.pages", "20" }, { "max.content.bytes", "10485760" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "OracleDatabase", "Oracle Database", "oracle-database", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1", "BASIC", null, null, todo, todo,
					"Dedicated Oracle Database connector. Supports query, get, insert, update, upsert, execute, schema discovery, preview, batching, and transactions. "
						+ "The Oracle JDBC driver is packaged with EBX-Connectors; use property jndi for a container-managed DataSource.",
					new String[][] { { "driver", "oracle.jdbc.OracleDriver" }, { "schema", todo }, { "max.rows", "1000" },
						{ "fetch.size", "100" }, { "batch.size", "100" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "SFTP", "SFTP server", "sftp", "sftp://HOST:22", "BASIC", null, null, todo, todo,
					"Files on an SFTP server (jsch.jar). Password, or property privateKey = path of an OpenSSH key. The server's key is verified: set property knownHosts"
						+ " (path of a known_hosts file) or hostKey (output of ssh-keyscan). Operations read (csv/json/jsonl), write, list. Property root.",
					new String[][] { { "root", "/" }, { "separator", ";" }, { "knownHosts", todo } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "RabbitMQ", "RabbitMQ", "rabbitmq", "amqps://localhost:5671", "BASIC", null, null, todo, todo,
					"RabbitMQ AMQP 0-9-1. Bounded publish and manual-ack receive; peek requeues deliveries. Use TLS (amqps) outside local development.",
					new String[][] { { "queue", "ebx.master-data" }, { "exchange", "" }, { "routing.key", "ebx.master-data" },
						{ "prefetch", "100" }, { "max.messages", "100" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "S3", "Amazon S3", "s3", "", "BASIC", null, null, todo, todo,
					"S3 bucket (SigV4). Username = access key id, password = secret access key; properties bucket, region, endpoint (S3-compatible), root.",
					new String[][] { { "bucket", todo }, { "region", "eu-west-3" } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "AzureBlob", "Azure Blob Storage", "azure-blob", "https://ACCOUNT.blob.core.windows.net", "API_KEY_HEADER", null, todo,
					null, null, "Azure Blob container with a SAS token (apiKey) or a bearer token. Property container, root.", new String[][] { { "container", todo } });
				ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "GCS", "Google Cloud Storage", "gcs", "https://storage.googleapis.com", "NONE", null, null, null, null,
					"GCS bucket. Service account key: property serviceAccountFile (a file of the folder named by -Debx.connectors.secrets.dir, written relative to it; any other path is refused) or serviceAccountJson. Property bucket, root.",
					new String[][] { { "bucket", todo }, { "serviceAccountFile", todo } });
				// ---- inbound webhook
				if (ConnectorsInstaller.connectorIfMissing(pContext, pDataset, "Webhook", "Inbound webhook", "webhook", "", "NONE", null, null, null, null,
					"POST /ebx-connectors/rest/v1/webhook/{mapping id or label}. The sender authenticates as an EBX user (Basic or token) holding the Connectors RUN right; "
							+ "the import runs under that user. No secret is stored on this connection. Property sample = sample JSON for the designer.",
					new String[][] { { "sample", "{\"items\":[{\"siren\":\"440676559\",\"name\":\"ACME SAS\",\"city\":\"Paris\",\"postcode\":\"75008\",\"country\":\"FR\",\"website\":\"https://acme.example\"}]}" } }))
				{
					final int feed = ConnectorsInstaller.feedMapping(pContext, pDataset, "Webhook", "receive", "Webhook: companies pushed by an external application",
						"Companies", "/root/Companies", "", "items", "./LegalInformation/registrationNumber='${siren}'", true, new String[0][]);
					ConnectorsInstaller.rules(pContext, pDataset, feed, new String[][] {
						{ "siren", "./LegalInformation/registrationNumber", "digits", "ALWAYS" }, { "name", "./Identification/Name", "trim|left(80)", "ALWAYS" },
						{ "city", "./Address/City", "capitalize|left(80)", "IF_EMPTY" }, { "postcode", "./Address/Zipcode", "trim|left(80)", "IF_EMPTY" },
						{ "country", "./Address/Country", "upper", "IF_EMPTY" }, { "website", "./Contact/website", "trim", "IF_EMPTY" },
						{ "", "./Governance/enrichmentSource", "constant(WEBHOOK)", "IF_EMPTY" } });
				}
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[connectors] catalogue seed failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		else
		{
			pLog.info("[connectors] seeded the connector catalogue (registries, addresses, sanctions, business applications, platforms, webhook)");
		}
	}

	private static int childMapping(final ProcedureContext pContext, final Adaptation pDataset, final int pParent, final String pConnector,
		final String pOperation, final String pDirection, final String pLabel, final String pDatasetName, final String pTablePath,
		final String pCollection, final String pLink, final String pKeys, final String pFilter) throws Exception
	{
		if (pParent < 0)
		{
			return ConnectorsInstaller.SKIPPED;
		}
		SchemaNode target = null;
		if (!"always".equals(ConnectorsInstaller.seedPolicy()))
		{
			target = ModelCheck.table(pDataset.getHome().getRepository(), null, pDatasetName, pTablePath);
			final List<String> missing = target == null ? null : ModelCheck.missingFieldList(target, pLink + (pKeys.isEmpty() ? "" : "," + pKeys));
			if (target == null || !missing.isEmpty())
			{
				LoggingCategory.getKernel().info("[connectors] seed: sub-object mapping '" + pLabel + "' skipped, "
						+ (target == null ? "table " + pTablePath + " not found" : "field(s) " + missing + " not in " + pTablePath));
				return ConnectorsInstaller.SKIPPED;
			}
		}
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.MAPPING);
		final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
		row.setValue(pConnector, ConnectorsPaths.Mapping.CONNECTOR);
		row.setValue(pOperation, ConnectorsPaths.Mapping.OPERATION);
		row.setValue(pDirection, ConnectorsPaths.Mapping.DIRECTION);
		row.setValue(pLabel, ConnectorsPaths.Mapping.LABEL);
		row.setValue(pDatasetName, ConnectorsPaths.Mapping.DATASET);
		row.setValue(pTablePath, ConnectorsPaths.Mapping.TABLE_PATH);
		row.setValue(Boolean.TRUE, ConnectorsPaths.Mapping.ACTIVE);
		row.setValue(String.valueOf(pParent), ConnectorsPaths.Mapping.PARENT);
		row.setValue(pCollection, ConnectorsPaths.Mapping.COLLECTION_PATH);
		row.setValue(pLink, ConnectorsPaths.Mapping.PARENT_LINK_FIELD);
		row.setValue(pKeys.isEmpty() ? null : pKeys, ConnectorsPaths.Mapping.CHILD_KEY_FIELDS);
		row.setValue(pFilter.isEmpty() ? null : pFilter, ConnectorsPaths.Mapping.CHILD_FILTER);
		row.setValue(Boolean.FALSE, ConnectorsPaths.Mapping.DELETE_MISSING);
		final Adaptation created = pContext.doCreateOccurrence(row, table);
		final Integer id = (Integer) created.get(ConnectorsPaths.Mapping.ID);
		ConnectorsInstaller.SEEDED_TABLES.put(id, target);
		ConnectorsInstaller.SEEDED_DIRECTIONS.put(id, pDirection);
		return id.intValue();
	}

	private static int feedMapping(final ProcedureContext pContext, final Adaptation pDataset, final String pConnector, final String pOperation,
		final String pLabel, final String pDatasetName, final String pTablePath, final String pResponseRoot, final String pItemsPath,
		final String pKeyPredicate, final boolean pCreate, final String[][] pInputs) throws Exception
	{
		final int id = ConnectorsInstaller.mapping(pContext, pDataset, pConnector, pOperation, "INBOUND", pLabel, null, pDatasetName, pTablePath,
			pResponseRoot, pInputs);
		if (id < 0)
		{
			return ConnectorsInstaller.SKIPPED;
		}
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.MAPPING);
		final Adaptation row = table.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(String.valueOf(id)));
		final ValueContextForUpdate update = pContext.getContext(row.getAdaptationName());
		update.setValue(pItemsPath, ConnectorsPaths.Mapping.ITEMS_PATH);
		update.setValue(pKeyPredicate, ConnectorsPaths.Mapping.KEY_PREDICATE);
		update.setValue(Boolean.valueOf(pCreate), ConnectorsPaths.Mapping.CREATE_IF_MISSING);
		pContext.doModifyContent(row, update);
		return id;
	}

	private static void property(final ProcedureContext pContext, final Adaptation pDataset, final String pConnector,
		final String pKey, final String pValue) throws Exception
	{
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY);
		final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
		row.setValue(pConnector, ConnectorsPaths.ConnectorProperty.CONNECTOR);
		row.setValue(pKey, ConnectorsPaths.ConnectorProperty.KEY);
		row.setValue(pValue, ConnectorsPaths.ConnectorProperty.VALUE);
		pContext.doCreateOccurrence(row, table);
	}

	private static void connector(final ProcedureContext pContext, final Adaptation pDataset, final String pId,
		final String pLabel, final String pType, final String pUrl, final String pAuth, final String pKeyHeader,
		final String pKey, final String pDescription) throws Exception
	{
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.CONNECTOR);
		final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
		row.setValue(pId, ConnectorsPaths.Connector.ID);
		row.setValue(pLabel, ConnectorsPaths.Connector.LABEL);
		row.setValue(pType, ConnectorsPaths.Connector.TYPE);
		row.setValue(pUrl, ConnectorsPaths.Connector.BASE_URL);
		row.setValue(pAuth, ConnectorsPaths.Connector.AUTH_TYPE);
		row.setValue(pKeyHeader, ConnectorsPaths.Connector.API_KEY_HEADER);
		row.setValue(pKey, ConnectorsPaths.Connector.API_KEY);
		row.setValue(pDescription, ConnectorsPaths.Connector.DESCRIPTION);
		if (table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.Connector.CATEGORY) != null)
		{
			row.setValue(ConnectorsInstaller.seedCategory(pId, pType), ConnectorsPaths.Connector.CATEGORY);
		}
		row.setValue(Boolean.TRUE, ConnectorsPaths.Connector.ACTIVE);
		if (table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.Connector.TRUST) != null)
		{
			// the model default is 50: the trust by type must be set explicitly at seed time
			row.setValue(Integer.valueOf(ConnectorsInstaller.defaultTrust(pType)), ConnectorsPaths.Connector.TRUST);
		}
		if (table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.Connector.PRICING) != null)
		{
			final String pricing = ConnectorsInstaller.seedPricing(pId, pType);
			row.setValue(pricing, ConnectorsPaths.Connector.PRICING);
			if (table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.Connector.PRICING_NOTE) != null)
			{
				row.setValue(ConnectorsInstaller.seedPricingNote(pId, pType), ConnectorsPaths.Connector.PRICING_NOTE);
			}
		}
		if (table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.Connector.LOGO) != null && com.on.ebx.connect.ebx.ui.Logos.preferredFor(pId) != null)
		{
			row.setValueFromXsString(com.on.ebx.connect.ebx.ui.Logos.preferredFor(pId), ConnectorsPaths.Connector.LOGO);
		}
		pContext.doCreateOccurrence(row, table);
		ConnectorsInstaller.mark(pContext, pDataset, SeedMarkers.connector(pId));
	}

	private static int mapping(final ProcedureContext pContext, final Adaptation pDataset, final String pConnector,
		final String pOperation, final String pDirection, final String pLabel, final String pDataspace,
		final String pDatasetName, final String pTablePath, final String pResponseRoot, final String[][] pInputs)
		throws Exception
	{
		final String policy = ConnectorsInstaller.seedPolicy();
		SchemaNode target = null;
		if (!"always".equals(policy))
		{
			if ("never".equals(policy))
			{
				return ConnectorsInstaller.SKIPPED;
			}
			target = ModelCheck.table(pDataset.getHome().getRepository(), pDataspace, pDatasetName, pTablePath);
			if (target == null)
			{
				LoggingCategory.getKernel().info("[connectors] seed: mapping '" + pLabel + "' skipped, table " + pTablePath + " not found in dataset "
						+ pDatasetName + " (set -D" + ConnectorsInstaller.SEED_PROPERTY + "=always to seed it anyway)");
				return ConnectorsInstaller.SKIPPED;
			}
			for (final String[] input : pInputs)
			{
				final List<String> missing = ModelCheck.missingFields(target, input[1]);
				if (!missing.isEmpty())
				{
					LoggingCategory.getKernel().info("[connectors] seed: mapping '" + pLabel + "' skipped, input " + input[0] + " uses field(s) " + missing
							+ " that " + pTablePath + " does not have");
					return ConnectorsInstaller.SKIPPED;
				}
			}
		}
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.MAPPING);
		final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
		row.setValue(pConnector, ConnectorsPaths.Mapping.CONNECTOR);
		row.setValue(pOperation, ConnectorsPaths.Mapping.OPERATION);
		row.setValue(pDirection, ConnectorsPaths.Mapping.DIRECTION);
		row.setValue(pLabel, ConnectorsPaths.Mapping.LABEL);
		row.setValue(pDataspace, ConnectorsPaths.Mapping.DATASPACE);
		row.setValue(pDatasetName, ConnectorsPaths.Mapping.DATASET);
		row.setValue(pTablePath, ConnectorsPaths.Mapping.TABLE_PATH);
		row.setValue(pResponseRoot, ConnectorsPaths.Mapping.RESPONSE_ROOT);
		row.setValue(Boolean.TRUE, ConnectorsPaths.Mapping.ACTIVE);
		final Adaptation created = pContext.doCreateOccurrence(row, table);
		final Integer id = (Integer) created.get(ConnectorsPaths.Mapping.ID);
		ConnectorsInstaller.SEEDED_TABLES.put(id, target);
		ConnectorsInstaller.SEEDED_DIRECTIONS.put(id, pDirection);
		final AdaptationTable inputs = pDataset.getTable(ConnectorsPaths.MAPPING_INPUT);
		for (final String[] input : pInputs)
		{
			final ValueContextForUpdate in = pContext.getContextForNewOccurrence(inputs);
			in.setValue(String.valueOf(id), ConnectorsPaths.MappingInput.MAPPING);
			in.setValue(input[0], ConnectorsPaths.MappingInput.NAME);
			in.setValue(input[1], ConnectorsPaths.MappingInput.EXPRESSION);
			pContext.doCreateOccurrence(in, inputs);
		}
		return id.intValue();
	}

	private static void rules(final ProcedureContext pContext, final Adaptation pDataset, final int pMapping,
		final String[][] pRules) throws Exception
	{
		if (pMapping < 0)
		{
			return; // the mapping was skipped
		}
		final SchemaNode target = ConnectorsInstaller.SEEDED_TABLES.get(Integer.valueOf(pMapping));
		final boolean outbound = "OUTBOUND".equals(ConnectorsInstaller.SEEDED_DIRECTIONS.get(Integer.valueOf(pMapping)));
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.MAPPING_RULE);
		int position = 10;
		for (final String[] rule : pRules)
		{
			final String ebxPath = outbound ? rule[0] : rule[1];
			if (target != null && !ModelCheck.hasField(target, ebxPath))
			{
				LoggingCategory.getKernel().info("[connectors] seed: rule " + ebxPath + " dropped from mapping " + pMapping + ", not a field of "
						+ target.getPathInSchema().format());
				continue;
			}
			final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
			row.setValue(String.valueOf(pMapping), ConnectorsPaths.MappingRule.MAPPING);
			row.setValue(Integer.valueOf(position), ConnectorsPaths.MappingRule.POSITION);
			row.setValue(rule[0], ConnectorsPaths.MappingRule.SOURCE_PATH);
			row.setValue(rule[1], ConnectorsPaths.MappingRule.TARGET_PATH);
			row.setValue(rule[2], ConnectorsPaths.MappingRule.TRANSFORMATION);
			row.setValue(rule[3], ConnectorsPaths.MappingRule.POLICY);
			row.setValue(Boolean.TRUE, ConnectorsPaths.MappingRule.ACTIVE);
			pContext.doCreateOccurrence(row, table);
			position += 10;
		}
	}

	/** Names of the tables of the model, for diagnostics. */
	public static List<String> tables()
	{
		final List<String> names = new ArrayList<String>();
		names.add(ConnectorsPaths.CONNECTOR.format());
		names.add(ConnectorsPaths.CONNECTOR_PROPERTY.format());
		names.add(ConnectorsPaths.MAPPING.format());
		names.add(ConnectorsPaths.MAPPING_INPUT.format());
		names.add(ConnectorsPaths.MAPPING_RULE.format());
		names.add(ConnectorsPaths.SYNC_LOG.format());
		return names;
	}
}
