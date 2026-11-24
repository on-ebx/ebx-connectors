package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Reads connectors and mappings from the {@code Connectors} dataset and writes the sync log.
 */
public final class ConnectorRepository implements TrustOracle
{
	private final Repository repository;

	public ConnectorRepository(final Repository pRepository)
	{
		this.repository = pRepository == null ? Repository.getDefault() : pRepository;
	}

	/** The configuration dataset, or null when the module has not been installed. */
	public Adaptation dataset()
	{
		final AdaptationHome home = this.repository.lookupHome(HomeKey.forBranchName(ConnectorsPaths.DATASPACE));
		if (home == null)
		{
			return null;
		}
		return home.findAdaptationOrNull(AdaptationName.forName(ConnectorsPaths.DATASET));
	}

	private Adaptation requireDataset()
	{
		final Adaptation dataset = this.dataset();
		if (dataset == null)
		{
			throw new IllegalStateException("The 'Connectors' dataset does not exist: restart the module or create it");
		}
		return dataset;
	}

	// ------------------------------------------------------------------ connectors

	public List<ConnectorConfig> connectors()
	{
		final Adaptation dataset = this.requireDataset();
		final List<ConnectorConfig> out = new ArrayList<ConnectorConfig>();
		final RequestResult rows = dataset.getTable(ConnectorsPaths.CONNECTOR).createRequest().execute();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(this.toConfig(dataset, row));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	public ConnectorConfig connector(final String pId)
	{
		final Adaptation dataset = this.requireDataset();
		final Adaptation row = dataset.getTable(ConnectorsPaths.CONNECTOR)
				.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pId));
		return row == null ? null : this.toConfig(dataset, row);
	}

	private ConnectorConfig toConfig(final Adaptation pDataset, final Adaptation pRow)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId(pRow.getString(ConnectorsPaths.Connector.ID));
		config.setLabel(pRow.getString(ConnectorsPaths.Connector.LABEL));
		config.setType(pRow.getString(ConnectorsPaths.Connector.TYPE));
		config.setBaseUrl(pRow.getString(ConnectorsPaths.Connector.BASE_URL));
		final String auth = pRow.getString(ConnectorsPaths.Connector.AUTH_TYPE);
		try
		{
			config.setAuthType(auth == null ? ConnectorConfig.AuthType.NONE : ConnectorConfig.AuthType.valueOf(auth));
		}
		catch (final IllegalArgumentException ex)
		{
			config.setAuthType(ConnectorConfig.AuthType.NONE);
		}
		config.setUsername(pRow.getString(ConnectorsPaths.Connector.USERNAME));
		config.setApiKeyHeader(pRow.getString(ConnectorsPaths.Connector.API_KEY_HEADER));
		try
		{
			config.setPassword(Secrets.reveal(pRow.getString(ConnectorsPaths.Connector.PASSWORD)));
		}
		catch (final IllegalStateException ex)
		{
			config.setProblem("password: " + ex.getMessage());
		}
		try
		{
			config.setApiKey(Secrets.reveal(pRow.getString(ConnectorsPaths.Connector.API_KEY)));
		}
		catch (final IllegalStateException ex)
		{
			config.setProblem("apiKey: " + ex.getMessage());
		}
		final Integer connect = (Integer) pRow.get(ConnectorsPaths.Connector.CONNECT_TIMEOUT);
		final Integer read = (Integer) pRow.get(ConnectorsPaths.Connector.READ_TIMEOUT);
		config.setConnectTimeoutMs(connect == null ? 0 : connect.intValue());
		config.setReadTimeoutMs(read == null ? 0 : read.intValue());
		final Boolean active = (Boolean) pRow.get(ConnectorsPaths.Connector.ACTIVE);
		config.setActive(active == null || active.booleanValue());
		if (pRow.getSchemaNode().getNode(ConnectorsPaths.Connector.LOGO) != null)
		{
			final Object logo = pRow.get(ConnectorsPaths.Connector.LOGO);
			config.setLogo(logo == null ? null : pRow.getSchemaNode().getNode(ConnectorsPaths.Connector.LOGO).formatToXsString(logo));
		}
		if (pRow.getSchemaNode().getNode(ConnectorsPaths.Connector.SAMPLE_DATA) != null)
		{
			config.setSampleData(pRow.getString(ConnectorsPaths.Connector.SAMPLE_DATA));
		}
		if (pRow.getSchemaNode().getNode(ConnectorsPaths.Connector.TRUST) != null)
		{
			final Integer trust = (Integer) pRow.get(ConnectorsPaths.Connector.TRUST);
			config.setTrust(trust == null ? 50 : trust.intValue());
		}
		if (pRow.getSchemaNode().getNode(ConnectorsPaths.Connector.PRICING) != null)
		{
			final String pricing = pRow.getString(ConnectorsPaths.Connector.PRICING);
			try
			{
				config.setPricing(pricing == null ? ConnectorConfig.Pricing.UNKNOWN
						: ConnectorConfig.Pricing.valueOf(pricing));
			}
			catch (final IllegalArgumentException ex)
			{
				config.setPricing(ConnectorConfig.Pricing.UNKNOWN);
			}
		}
		if (pRow.getSchemaNode().getNode(ConnectorsPaths.Connector.PRICING_NOTE) != null)
		{
			config.setPricingNote(pRow.getString(ConnectorsPaths.Connector.PRICING_NOTE));
		}
		final Request request = pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY).createRequest();
		request.setXPathFilter("./connector=" + Xpath.quote(config.getId()));
		final RequestResult rows = request.execute();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String key = row.getString(ConnectorsPaths.ConnectorProperty.KEY);
				try
				{
					config.setProperty(key, Secrets.resolveIfReference(row.getString(ConnectorsPaths.ConnectorProperty.VALUE)));
				}
				catch (final IllegalStateException ex)
				{
					config.setProblem("property " + key + ": " + ex.getMessage());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return config;
	}

	// ------------------------------------------------------------------ mappings

	/** Active mappings declared for a table (any connector, any direction). */
	public List<MappingDefinition> mappingsFor(final String pDataspace, final String pDataset, final String pTablePath)
	{
		return ConnectorRepository.mappingsFor(this.mappings(), pDataspace, pDataset, pTablePath);
	}

	/** {@link #mappingsFor(String, String, String)} on mappings already read (a run reads them once, audit C-13). */
	static List<MappingDefinition> mappingsFor(final List<MappingDefinition> pAll, final String pDataspace, final String pDataset, final String pTablePath)
	{
		final List<MappingDefinition> out = new ArrayList<MappingDefinition>();
		for (final MappingDefinition mapping : pAll)
		{
			if (mapping.isActive() && !mapping.isChild() && mapping.appliesTo(pDataspace, pDataset, pTablePath))
			{
				out.add(mapping);
			}
		}
		return out;
	}

	/** Like {@link #mappingsFor}, a null or blank dataspace also returning the mappings bound to a dataspace. */
	public List<MappingDefinition> mappingsForAnyDataspace(final String pDataspace, final String pDataset, final String pTablePath)
	{
		final List<MappingDefinition> out = new ArrayList<MappingDefinition>();
		for (final MappingDefinition mapping : this.mappings())
		{
			if (mapping.isActive() && !mapping.isChild() && mapping.appliesToAnyDataspace(pDataspace, pDataset, pTablePath))
			{
				out.add(mapping);
			}
		}
		return out;
	}

	/**
	 * The mapping of a connector operation for a table in one direction, or null. A mapping bound to
	 * the dataspace wins over a generic one; several candidates at the same level are ambiguous and
	 * refused ({@link IllegalStateException} naming them): the caller must then name the mapping by id.
	 */
	public MappingDefinition mappingFor(final String pDataspace, final String pDataset, final String pTablePath,
		final String pConnectorId, final String pOperation, final MappingDefinition.Direction pDirection)
	{
		return ConnectorRepository.mappingFor(this.mappings(), pDataspace, pDataset, pTablePath, pConnectorId, pOperation, pDirection);
	}

	/** {@link #mappingFor(String, String, String, String, String, MappingDefinition.Direction)} on mappings already read: the same choice, no read. */
	static MappingDefinition mappingFor(final List<MappingDefinition> pAll, final String pDataspace, final String pDataset, final String pTablePath,
		final String pConnectorId, final String pOperation, final MappingDefinition.Direction pDirection)
	{
		final List<MappingDefinition> specific = new ArrayList<MappingDefinition>();
		final List<MappingDefinition> generic = new ArrayList<MappingDefinition>();
		for (final MappingDefinition mapping : ConnectorRepository.mappingsFor(pAll, pDataspace, pDataset, pTablePath))
		{
			if (!mapping.getConnectorId().equals(pConnectorId) || !mapping.getOperation().equals(pOperation)
				|| mapping.getDirection() != pDirection)
			{
				continue;
			}
			(mapping.getDataspace() != null ? specific : generic).add(mapping);
		}
		final List<MappingDefinition> candidates = specific.isEmpty() ? generic : specific;
		if (candidates.size() > 1)
		{
			final StringBuilder ids = new StringBuilder();
			for (final MappingDefinition candidate : candidates)
			{
				ids.append(ids.length() == 0 ? "" : ", ").append(candidate.getId()).append(" (").append(candidate.getLabel()).append(')');
			}
			throw new IllegalStateException("Several " + pDirection + " mappings for " + pConnectorId + "/" + pOperation + " on " + pTablePath
					+ ": " + ids + ". Name the mapping by its id (mappings=<id>) instead of the connector and operation.");
		}
		return candidates.isEmpty() ? null : candidates.get(0);
	}

	public MappingDefinition mapping(final int pId)
	{
		return ConnectorRepository.mapping(this.mappings(), pId);
	}

	/** {@link #mapping(int)} on mappings already read. */
	static MappingDefinition mapping(final List<MappingDefinition> pAll, final int pId)
	{
		for (final MappingDefinition mapping : pAll)
		{
			if (mapping.getId() == pId)
			{
				return mapping;
			}
		}
		return null;
	}

	public List<MappingDefinition> mappings()
	{
		final Adaptation dataset = this.requireDataset();
		final Map<Integer, MappingDefinition> byId = new LinkedHashMap<Integer, MappingDefinition>();
		final RequestResult rows = dataset.getTable(ConnectorsPaths.MAPPING).createRequest().execute();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Integer id = (Integer) row.get(ConnectorsPaths.Mapping.ID);
				MappingDefinition.Direction direction = MappingDefinition.Direction.INBOUND;
				try
				{
					direction = MappingDefinition.Direction.valueOf(row.getString(ConnectorsPaths.Mapping.DIRECTION));
				}
				catch (final RuntimeException ex)
				{
					direction = MappingDefinition.Direction.INBOUND;
				}
				final Boolean active = (Boolean) row.get(ConnectorsPaths.Mapping.ACTIVE);
				final MappingDefinition mapping = new MappingDefinition(id.intValue(), row.getString(ConnectorsPaths.Mapping.CONNECTOR),
					row.getString(ConnectorsPaths.Mapping.OPERATION), direction,
					row.getString(ConnectorsPaths.Mapping.LABEL), row.getString(ConnectorsPaths.Mapping.DATASPACE),
					row.getString(ConnectorsPaths.Mapping.DATASET), row.getString(ConnectorsPaths.Mapping.TABLE_PATH),
					row.getString(ConnectorsPaths.Mapping.RESPONSE_ROOT), active == null || active.booleanValue());
				if (row.getSchemaNode().getNode(ConnectorsPaths.Mapping.DELETE_OPERATION) != null)
				{
					mapping.setOutboundPolicy(row.getString(ConnectorsPaths.Mapping.DELETE_OPERATION), (Integer) row.get(ConnectorsPaths.Mapping.MAX_ATTEMPTS),
						(Integer) row.get(ConnectorsPaths.Mapping.RETRY_DELAY_MINUTES));
				}
				// Additive synchronization-policy fields: old repository schemas safely remain FULL.
				if (row.getSchemaNode().getNode(ConnectorsPaths.Mapping.LOAD_MODE) != null)
				{
					mapping.setSynchronizationPolicy(row.getString(ConnectorsPaths.Mapping.LOAD_MODE),
						row.getString(ConnectorsPaths.Mapping.CHECKPOINT_STRATEGY), row.getString(ConnectorsPaths.Mapping.CHECKPOINT_INPUT),
						row.getString(ConnectorsPaths.Mapping.CHECKPOINT_VALUE_PATH), row.getString(ConnectorsPaths.Mapping.CHECKPOINT_INITIAL_VALUE));
				}
				// volume lot V2-D field: absent until the model is reloaded, hence the guarded read
				if (row.getSchemaNode().getNode(ConnectorsPaths.Mapping.CHECKPOINT_BOUNDARY) != null)
				{
					mapping.setCheckpointBoundary(row.getString(ConnectorsPaths.Mapping.CHECKPOINT_BOUNDARY));
				}
				// rev 3 fields: absent until the model is reloaded, hence the guarded reads
				if (row.getSchemaNode().getNode(ConnectorsPaths.Mapping.PARENT) != null)
				{
					final String parent = row.getString(ConnectorsPaths.Mapping.PARENT);
					mapping.setParentId(parent == null || parent.trim().isEmpty() ? null : ConnectorRepository.fkId(parent));
					final Boolean del = (Boolean) row.get(ConnectorsPaths.Mapping.DELETE_MISSING);
					mapping.setSubObject(row.getString(ConnectorsPaths.Mapping.COLLECTION_PATH), row.getString(ConnectorsPaths.Mapping.PARENT_LINK_FIELD),
						row.getString(ConnectorsPaths.Mapping.CHILD_KEY_FIELDS), row.getString(ConnectorsPaths.Mapping.CHILD_FILTER),
						del != null && del.booleanValue());
					final Boolean create = (Boolean) row.get(ConnectorsPaths.Mapping.CREATE_IF_MISSING);
					mapping.setFeed(row.getString(ConnectorsPaths.Mapping.ITEMS_PATH), row.getString(ConnectorsPaths.Mapping.KEY_PREDICATE),
						create == null || create.booleanValue());
				}
				// volume lot 5 fields: absent until the model is reloaded, hence the guarded reads
				if (row.getSchemaNode().getNode(ConnectorsPaths.Mapping.PROVENANCE) != null)
				{
					final Boolean provenance = (Boolean) row.get(ConnectorsPaths.Mapping.PROVENANCE);
					mapping.setProvenance(provenance == null || provenance.booleanValue());
				}
				if (row.getSchemaNode().getNode(ConnectorsPaths.Mapping.UNCHANGED_IF_EQUAL) != null)
				{
					mapping.setUnchangedIfEqual(row.getString(ConnectorsPaths.Mapping.UNCHANGED_IF_EQUAL));
				}
				// volume lot 6 fields: absent until the model is reloaded, hence the guarded reads
				if (row.getSchemaNode().getNode(ConnectorsPaths.Mapping.BATCH_SIZE) != null)
				{
					mapping.setBatchSize((Integer) row.get(ConnectorsPaths.Mapping.BATCH_SIZE));
				}
				if (row.getSchemaNode().getNode(ConnectorsPaths.Mapping.LOG_EVERY_RECORD) != null)
				{
					final Boolean logEveryRecord = (Boolean) row.get(ConnectorsPaths.Mapping.LOG_EVERY_RECORD);
					mapping.setLogEveryRecord(logEveryRecord != null && logEveryRecord.booleanValue());
				}
				byId.put(id, mapping);
			}
		}
		finally
		{
			rows.close();
		}
		for (final MappingDefinition mapping : byId.values())
		{
			if (mapping.getParentId() != null)
			{
				final MappingDefinition parent = byId.get(mapping.getParentId());
				if (parent != null && mapping.isActive())
				{
					parent.addChild(mapping);
				}
			}
		}
		final RequestResult inputs = dataset.getTable(ConnectorsPaths.MAPPING_INPUT).createRequest().execute();
		try
		{
			for (Adaptation row = inputs.nextAdaptation(); row != null; row = inputs.nextAdaptation())
			{
				final MappingDefinition mapping = byId.get(ConnectorRepository.fkId(row.getString(ConnectorsPaths.MappingInput.MAPPING)));
				if (mapping != null)
				{
					mapping.addInput(row.getString(ConnectorsPaths.MappingInput.NAME),
						row.getString(ConnectorsPaths.MappingInput.EXPRESSION));
				}
			}
		}
		finally
		{
			inputs.close();
		}
		final Request ruleRequest = dataset.getTable(ConnectorsPaths.MAPPING_RULE).createRequest();
		ruleRequest.setSortCriteria(new com.onwbp.adaptation.RequestSortCriteria().add(ConnectorsPaths.MappingRule.POSITION));
		final RequestResult rules = ruleRequest.execute();
		try
		{
			for (Adaptation row = rules.nextAdaptation(); row != null; row = rules.nextAdaptation())
			{
				final Boolean active = (Boolean) row.get(ConnectorsPaths.MappingRule.ACTIVE);
				if (active != null && !active.booleanValue())
				{
					continue;
				}
				final MappingDefinition mapping = byId.get(ConnectorRepository.fkId(row.getString(ConnectorsPaths.MappingRule.MAPPING)));
				if (mapping == null)
				{
					continue;
				}
				final Integer position = (Integer) row.get(ConnectorsPaths.MappingRule.POSITION);
				MappingRule.Policy policy = MappingRule.Policy.IF_EMPTY;
				try
				{
					policy = MappingRule.Policy.valueOf(row.getString(ConnectorsPaths.MappingRule.POLICY));
				}
				catch (final RuntimeException ex)
				{
					policy = MappingRule.Policy.IF_EMPTY;
				}
				mapping.addRule(new MappingRule(position == null ? 0 : position.intValue(),
					row.getString(ConnectorsPaths.MappingRule.SOURCE_PATH), row.getString(ConnectorsPaths.MappingRule.TARGET_PATH),
					row.getString(ConnectorsPaths.MappingRule.TRANSFORMATION), policy,
					row.getString(ConnectorsPaths.MappingRule.COMMENT)));
			}
		}
		finally
		{
			rules.close();
		}
		return new ArrayList<MappingDefinition>(byId.values());
	}

	private static Integer fkId(final String pFk)
	{
		try
		{
			return Integer.valueOf(pFk.trim());
		}
		catch (final RuntimeException ex)
		{
			return Integer.valueOf(-1);
		}
	}

	// ------------------------------------------------------------------ log

	/**
	 * Appends one line to the sync log; never throws (logging must not break a sync).
	 *
	 * @return false when the line could not be written (reported through {@link Bookkeeping}); true
	 *         when it was, or when there is no log to write to.
	 */
	public boolean log(final Session pSession, final SyncResult pResult, final String pOrigin)
	{
		return this.logBatch(pSession, java.util.Collections.singletonList(new LogEntry(pResult, pOrigin, new Date())));
	}

	/** One sync log line waiting to be written: the call, who ran it, and when it ended. */
	public static final class LogEntry
	{
		final SyncResult result;
		final String origin;
		final Date at;
		/** The HTTP retries and throttles counted when the call ended, for a line written later; null: counted when it is written. */
		final long[] http;

		public LogEntry(final SyncResult pResult, final String pOrigin, final Date pAt)
		{
			this(pResult, pOrigin, pAt, null);
		}

		public LogEntry(final SyncResult pResult, final String pOrigin, final Date pAt, final long[] pHttp)
		{
			this.result = pResult;
			this.origin = pOrigin;
			this.at = pAt;
			this.http = pHttp;
		}
	}

	/**
	 * Appends several lines to the sync log in ONE transaction (record-mode runs write a bounded batch of lines instead of one
	 * transaction per record, audit C-13); never throws. All the lines or none are written: the caller falls back to one line at
	 * a time when this answers false.
	 *
	 * @return false when the lines could not be written (reported through {@link Bookkeeping}); true when they were, or when
	 *         there is no log to write to.
	 */
	public boolean logBatch(final Session pSession, final java.util.List<LogEntry> pEntries)
	{
		if (pEntries.isEmpty())
		{
			return true;
		}
		final String context = pEntries.size() == 1 ? ConnectorRepository.context(pEntries.get(0).result)
			: ConnectorRepository.context(pEntries.get(0).result) + " and " + (pEntries.size() - 1) + " more line(s)";
		try
		{
			final Adaptation dataset = this.dataset();
			if (dataset == null)
			{
				return true;
			}
			final AdaptationTable table = dataset.getTable(ConnectorsPaths.SYNC_LOG);
			final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
			final ProcedureResult written = service.execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					for (final LogEntry entry : pEntries)
					{
						ConnectorRepository.writeLogRow(pContext, table, pSession, entry);
					}
				}
			});
			// observed whether or not the line was written, as it always was: the call happened
			final String user = pSession == null || pSession.getUserReference() == null ? null : pSession.getUserReference().getUserId();
			ConnectorOperationsRuntime.observeAll(ConnectorOperationsRuntime.capture(user, pEntries));
			return Bookkeeping.committed(Bookkeeping.SYNC_LOG, context, written);
		}
		catch (final RuntimeException ex)
		{
			// never rethrown: the synchronisation this line describes has already happened
			return Bookkeeping.lost(Bookkeeping.SYNC_LOG, context, ex);
		}
	}

	/** {@code ebx.connectors.log.storeChanges}: only {@code true} copies values and outbound documents into {@code SyncLog.changes} (audit C2-03: default off). */
	public static boolean storeChanges()
	{
		return "true".equalsIgnoreCase(System.getProperty("ebx.connectors.log.storeChanges", "false").trim());
	}

	private static void writeLogRow(final ProcedureContext pContext, final AdaptationTable pTable, final Session pSession, final LogEntry pEntry) throws Exception
	{
		final SyncResult pResult = pEntry.result;
		final ValueContextForUpdate row = pContext.getContextForNewOccurrence(pTable);
		row.setValue(pEntry.at, ConnectorsPaths.SyncLog.TIMESTAMP);
		row.setValue(pResult.getConnectorId(), ConnectorsPaths.SyncLog.CONNECTOR);
		row.setValue(pResult.getOperation(), ConnectorsPaths.SyncLog.OPERATION);
		row.setValue(pResult.getDirection(), ConnectorsPaths.SyncLog.DIRECTION);
		row.setValue(pResult.getDataspace(), ConnectorsPaths.SyncLog.DATASPACE);
		row.setValue(pResult.getTablePath(), ConnectorsPaths.SyncLog.TABLE_PATH);
		row.setValue(ConnectorRepository.cut(pResult.getRecordKey(), 255), ConnectorsPaths.SyncLog.RECORD_KEY);
		row.setValue(ConnectorRepository.cut(pResult.getRecordLabel(), 255), ConnectorsPaths.SyncLog.RECORD_LABEL);
		row.setValue(pResult.getStatus().name(), ConnectorsPaths.SyncLog.STATUS);
		row.setValue(ConnectorRepository.cut(Redaction.redact(pResult.getMessage()), 2000), ConnectorsPaths.SyncLog.MESSAGE);
		// Audit C2-03: values and documents stay out of the log unless ebx.connectors.log.storeChanges=true (personal data)
		row.setValue(!ConnectorRepository.storeChanges() ? null
				: ConnectorRepository.cut(Redaction.redact(pResult.changesAsText()), 4000), ConnectorsPaths.SyncLog.CHANGES);
		row.setValue(Integer.valueOf((int) pResult.getDurationMs()), ConnectorsPaths.SyncLog.DURATION_MS);
		row.setValue(pSession == null || pSession.getUserReference() == null ? null
				: pSession.getUserReference().getUserId(), ConnectorsPaths.SyncLog.USER);
		row.setValue(pEntry.origin, ConnectorsPaths.SyncLog.ORIGIN);
		if (row.getNode(ConnectorsPaths.SyncLog.RUN_ID) != null)
		{
			row.setValue(pResult.getRunId(), ConnectorsPaths.SyncLog.RUN_ID);
			row.setValue(pResult.getMappingId(), ConnectorsPaths.SyncLog.MAPPING);
		}
		pContext.doCreateOccurrence(row, pTable);
	}

	/** Which call a lost bookkeeping row was about: enough to find it again, no value of the record. */
	private static String context(final SyncResult pResult)
	{
		return pResult == null ? null : "run " + pResult.getRunId() + ", mapping " + pResult.getMappingId() + ", " + pResult.getConnectorId() + "/" + pResult.getOperation();
	}

	/**
	 * Writes one {@code /root/Publication} row for a {@code ProductPublisher.publish(...)} call, dry
	 * runs included (§2.3 bookkeeping). Best effort: a write failure here never masks the outcome of
	 * the publication itself.
	 *
	 * @return false when the row could not be written (reported through {@link Bookkeeping}).
	 */
	public boolean logPublication(final Session pSession, final String pRunId, final String pProduct, final String pContractVersion,
		final String pConnector, final String pSchema, final String pStatus, final String pMessage, final Date pCutoff,
		final Date pStartedAt, final Date pEndedAt, final int pRowCount, final String pMappings, final String pTables, final String pReportJson)
	{
		try
		{
			final Adaptation dataset = this.dataset();
			if (dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.PUBLICATION) == null)
			{
				return true;
			}
			final AdaptationTable table = dataset.getTable(ConnectorsPaths.PUBLICATION);
			final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
			final ProcedureResult written = service.execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
					row.setValue(pRunId, ConnectorsPaths.Publication.RUN_ID);
					row.setValue(pProduct, ConnectorsPaths.Publication.PRODUCT);
					row.setValue(pContractVersion, ConnectorsPaths.Publication.CONTRACT_VERSION);
					row.setValue(pConnector, ConnectorsPaths.Publication.CONNECTOR);
					row.setValue(pSchema, ConnectorsPaths.Publication.SCHEMA);
					row.setValue(pStatus, ConnectorsPaths.Publication.STATUS);
					row.setValue(ConnectorRepository.cut(Redaction.redact(pMessage), 2000), ConnectorsPaths.Publication.MESSAGE);
					row.setValue(pCutoff, ConnectorsPaths.Publication.CUTOFF);
					row.setValue(pStartedAt, ConnectorsPaths.Publication.STARTED_AT);
					row.setValue(pEndedAt, ConnectorsPaths.Publication.ENDED_AT);
					row.setValue(java.math.BigDecimal.valueOf(pRowCount), ConnectorsPaths.Publication.ROW_COUNT);
					row.setValue(ConnectorRepository.cut(pMappings, 255), ConnectorsPaths.Publication.MAPPINGS);
					row.setValue(ConnectorRepository.cut(pTables, 2000), ConnectorsPaths.Publication.TABLES);
					row.setValue(ConnectorRepository.cut(pReportJson, 100000), ConnectorsPaths.Publication.REPORT);
					pContext.doCreateOccurrence(row, table);
				}
			});
			return Bookkeeping.committed(Bookkeeping.PUBLICATION, "run " + pRunId + ", product " + pProduct, written);
		}
		catch (final RuntimeException ex)
		{
			// never rethrown, same as log(): a bookkeeping failure must never mask the publication outcome
			return Bookkeeping.lost(Bookkeeping.PUBLICATION, "run " + pRunId + ", product " + pProduct, ex);
		}
	}

	static String cut(final String pValue, final int pMax)
	{
		if (pValue == null)
		{
			return null;
		}
		return pValue.length() <= pMax ? pValue : pValue.substring(0, pMax - 3) + "...";
	}

	// ------------------------------------------------------------------ trust: provenance and conflicts

	private static String recordKey(final Adaptation pRecord)
	{
		return pRecord.getOccurrencePrimaryKey().format();
	}

	private static String quote(final String pValue)
	{
		return Xpath.quote(pValue);
	}

	private boolean hasTable(final Adaptation pDataset, final com.orchestranetworks.schema.Path pTable)
	{
		return pDataset != null && pDataset.getSchemaNode().getNode(pTable) != null;
	}

	/**
	 * Volume lot 5, task 2: {@code FieldProvenance}'s primary key is now the natural key
	 * (dataspace, dataset, tablePath, recordKey, fieldPath) - see {@code tools/build_xsd.py} and
	 * docs/REFERENCE.md - so this is one {@code lookupAdaptationByPrimaryKey} instead of an XPath
	 * {@code Request} scanning a table that used to grow to rows &times; changed fields (measured
	 * 2026-09-23: two thirds of a landing load's per-row time). Built with
	 * {@code AdaptationTable.computePrimaryKey(Object[])}, never a hand-joined string (a raw value may
	 * contain the primary-key separator; see CLAUDE.md's PrimaryKey note).
	 */
	private static com.onwbp.adaptation.PrimaryKey fieldProvenanceKey(final AdaptationTable pTable, final String pDataspace,
		final String pDataset, final String pTablePath, final String pRecordKey, final String pFieldPath)
	{
		return pTable.computePrimaryKey(new Object[] { pDataspace, pDataset, pTablePath, pRecordKey, pFieldPath });
	}

	@Override
	public Provenance provenanceOf(final Adaptation pRecord, final String pFieldPath)
	{
		final Adaptation dataset = this.dataset();
		if (pRecord == null || !this.hasTable(dataset, ConnectorsPaths.FIELD_PROVENANCE))
		{
			return null;
		}
		final AdaptationTable provenance = dataset.getTable(ConnectorsPaths.FIELD_PROVENANCE);
		final Adaptation row = provenance.lookupAdaptationByPrimaryKey(ConnectorRepository.fieldProvenanceKey(provenance,
			pRecord.getHome().getKey().getName(), pRecord.getContainer().getAdaptationName().getStringName(),
			pRecord.getContainerTable().getTablePath().format(), ConnectorRepository.recordKey(pRecord), pFieldPath));
		if (row == null)
		{
			return null;
		}
		final Integer trust = (Integer) row.get(ConnectorsPaths.FieldProvenance.TRUST);
		return new Provenance(row.getString(ConnectorsPaths.FieldProvenance.CONNECTOR), trust == null ? 50 : trust.intValue());
	}

	/**
	 * Remembers who wrote which field (main record changes only: paths starting with ./). Volume lot
	 * 5, task 2: a mapping whose {@code provenance} property is {@code false} (default {@code true})
	 * writes no {@code FieldProvenance} row at all - the single choke point for every caller
	 * ({@code ConnectorService.syncRecord}'s inbound/outbound path, its feed-import path, and
	 * {@code ConflictResolver}'s accept path), so a landing-style feed whose target table already
	 * carries its own lineage fields can turn this off once, on the mapping, rather than at each
	 * call site. {@code pMapping} may be {@code null} (a conflict whose mapping id no longer resolves):
	 * provenance is recorded in that case, matching the property's own default.
	 */
	public boolean recordProvenance(final Session pSession, final Adaptation pRecord, final MappingDefinition pMapping, final ConnectorConfig pConnector,
		final java.util.List<MappingResult.Change> pChanges)
	{
		if (pRecord == null || pChanges.isEmpty())
		{
			return true;
		}
		return this.recordProvenanceBatch(pSession, pMapping, pConnector,
			java.util.Collections.singletonList(new RecordChange(pRecord, pChanges)), null);
	}

	/** One record's changes to record provenance for, in a {@link #recordProvenanceBatch} call. */
	public static final class RecordChange
	{
		private final Adaptation record;
		private final java.util.List<MappingResult.Change> changes;

		public RecordChange(final Adaptation pRecord, final java.util.List<MappingResult.Change> pChanges)
		{
			this.record = pRecord;
			this.changes = pChanges;
		}
	}

	/**
	 * Volume lot 6, task 1: {@link #recordProvenance} for many records in ONE procedure instead of one
	 * procedure per record - the batched import feed's provenance step, using the {@code Adaptation}
	 * each write already returned (no re-lookup by primary key). Every {@code lookupAdaptationByPrimaryKey}
	 * below targets {@code FieldProvenance}, never the table the caller just wrote to, and each (dataspace,
	 * dataset, tablePath, recordKey, fieldPath) key is only ever touched once per call, so this never hits
	 * the "lookup of a key touched earlier in the same procedure" Lucene-refresh cost described in
	 * docs/REFERENCE.md and the EBX performance guide. {@code pTrackingInfo} may be null (single-record
	 * callers keep the session's existing tracking info).
	 *
	 * @return false when the provenance of these records could not be written (reported through
	 *         {@link Bookkeeping}): a later TRUSTED rule then compares against the previous provenance.
	 */
	public boolean recordProvenanceBatch(final Session pSession, final MappingDefinition pMapping, final ConnectorConfig pConnector,
		final java.util.List<RecordChange> pEntries, final String pTrackingInfo)
	{
		final Adaptation dataset = this.dataset();
		if (pEntries.isEmpty() || pConnector == null || (pMapping != null && !pMapping.isProvenance())
			|| !this.hasTable(dataset, ConnectorsPaths.FIELD_PROVENANCE))
		{
			return true;
		}
		final String context = "mapping " + (pMapping == null ? "?" : String.valueOf(pMapping.getId())) + ", " + pEntries.size() + " record(s)";
		try
		{
			final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
			if (pTrackingInfo != null)
			{
				service.setSessionTrackingInfo(pTrackingInfo);
			}
			final ProcedureResult written = service.execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					final AdaptationTable provenance = dataset.getTable(ConnectorsPaths.FIELD_PROVENANCE);
					// Gartner lot A: a repository installed before the source column keeps working without it
					final boolean hasSource = provenance.getTableOccurrenceRootNode().getNode(ConnectorsPaths.FieldProvenance.SOURCE) != null;
					for (final RecordChange entry : pEntries)
					{
						if (entry.record == null || entry.changes.isEmpty())
						{
							continue;
						}
						final String dataspace = entry.record.getHome().getKey().getName();
						final String datasetName = entry.record.getContainer().getAdaptationName().getStringName();
						final String table = entry.record.getContainerTable().getTablePath().format();
						final String key = ConnectorRepository.recordKey(entry.record);
						for (final MappingResult.Change change : entry.changes)
						{
							if (change.getPath() == null || !change.getPath().startsWith("./"))
							{
								continue;
							}
							final Adaptation existing = provenance.lookupAdaptationByPrimaryKey(
								ConnectorRepository.fieldProvenanceKey(provenance, dataspace, datasetName, table, key, change.getPath()));
							final ValueContextForUpdate row = existing == null ? pContext.getContextForNewOccurrence(provenance) : pContext.getContext(existing.getAdaptationName());
							row.setValue(dataspace, ConnectorsPaths.FieldProvenance.DATASPACE);
							row.setValue(datasetName, ConnectorsPaths.FieldProvenance.DATASET);
							row.setValue(table, ConnectorsPaths.FieldProvenance.TABLE_PATH);
							row.setValue(key, ConnectorsPaths.FieldProvenance.RECORD_KEY);
							row.setValue(change.getPath(), ConnectorsPaths.FieldProvenance.FIELD_PATH);
							row.setValue(pConnector.getId(), ConnectorsPaths.FieldProvenance.CONNECTOR);
							row.setValue(pMapping == null ? null : String.valueOf(pMapping.getId()), ConnectorsPaths.FieldProvenance.MAPPING);
							row.setValue(Integer.valueOf(pConnector.getTrust()), ConnectorsPaths.FieldProvenance.TRUST);
							// Defense in depth for callers supplying a Change not produced by MappingEngine.
							final String value = SampleValueSafety.reportValue(table, change.getPath(), change.getNewValue());
							row.setValue(value == null ? null : value.length() > 2000 ? value.substring(0, 2000) : value, ConnectorsPaths.FieldProvenance.VALUE);
							row.setValue(new java.util.Date(), ConnectorsPaths.FieldProvenance.WRITTEN_AT);
							if (hasSource)
							{
								final String source = change.getSource();
								row.setValue(source == null ? null : source.length() > 2000 ? source.substring(0, 2000) : source, ConnectorsPaths.FieldProvenance.SOURCE);
							}
							if (existing == null)
							{
								pContext.doCreateOccurrence(row, provenance);
							}
							else
							{
								pContext.doModifyContent(existing, row);
							}
						}
					}
				}
			});
			return Bookkeeping.committed(Bookkeeping.PROVENANCE, context, written);
		}
		catch (final Exception ex)
		{
			return Bookkeeping.lost(Bookkeeping.PROVENANCE, context, ex);
		}
	}

	/**
	 * Volume lot 6, task 5: persists the index of the next feed item to process for a bounded import
	 * run, in the {@code ExecutionRun} table (same table {@code RecoverableExecution}'s per-item
	 * checkpointing uses through {@code EbxRecoverableExecutionStore} - disjoint usage today, since
	 * nothing wires that engine to imports: this writes only {@code runId}/{@code nextIndex}/
	 * {@code updatedAt} and leaves {@code attempts}/{@code completed}/{@code deadLetters}/
	 * {@code leaseOwner}/{@code leaseExpiresAt} untouched). One small procedure per batch; failures are
	 * logged and otherwise swallowed - losing a resume checkpoint must never fail an import whose batch
	 * already committed.
	 */
	public boolean saveNextItemIndex(final Session pSession, final String pRunId, final int pNextIndex, final String pTrackingInfo)
	{
		return this.saveRunState(pSession, null, pRunId, "RUNNING", null, pNextIndex, null, pTrackingInfo);
	}

	/**
	 * Volume lot 7a: full state of an import run - status, running counters, truncation, a bounded
	 * sample of the first failing keys/messages, and (unchanged from lot 6) the resume position - in
	 * the SAME {@code ExecutionRun} row lot 6's {@code saveNextItemIndex} already maintained (kept
	 * above as a thin overload). Called once before the first batch or the first non-batched item
	 * (status {@code RUNNING}, an empty/zero {@code pOutcome}), after every batch of a batched run
	 * (status {@code RUNNING}, running counters), and once more at the very end of ANY import run,
	 * batched or not (final status {@code DONE}/{@code PARTIAL}/{@code ERROR}/{@code STOPPED}) - see
	 * {@code ConnectorService.runImport}. {@code pFeed} may be null on the very first (pre-lot-7a)
	 * call shape; {@code pOutcome} may be null when only the resume position is known yet. A failure
	 * never throws - losing run-state bookkeeping must never fail an import whose batch already
	 * committed - but it is reported and answered.
	 *
	 * @return false when the row could not be written (reported through {@link Bookkeeping}): the
	 *         caller decides what a stale row means - for a final state, see
	 *         {@code ConnectorService.runImport}.
	 */
	public boolean saveRunState(final Session pSession, final MappingDefinition pFeed, final String pRunId, final String pStatus,
		final ImportOutcome pOutcome, final int pNextIndex, final String pMessage, final String pTrackingInfo)
	{
		if (pRunId == null || pRunId.trim().isEmpty())
		{
			return true;
		}
		final Adaptation dataset = this.dataset();
		if (dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.EXECUTION_RUN) == null)
		{
			return true;
		}
		final boolean hasStateFields = dataset.getSchemaNode().getNode(
			ConnectorsPaths.EXECUTION_RUN.add(ConnectorsPaths.ExecutionRun.STATUS)) != null;
		// volume lot V2-D: three more fields, absent until the model is reloaded
		final boolean hasWatermarkFields = dataset.getSchemaNode().getNode(
			ConnectorsPaths.EXECUTION_RUN.add(ConnectorsPaths.ExecutionRun.WATERMARK_BEFORE)) != null;
		final boolean hasLeaseFields = dataset.getSchemaNode().getNode(
			ConnectorsPaths.EXECUTION_RUN.add(ConnectorsPaths.ExecutionRun.LEASE_OWNER)) != null
			&& dataset.getSchemaNode().getNode(ConnectorsPaths.EXECUTION_RUN.add(ConnectorsPaths.ExecutionRun.LEASE_EXPIRES_AT)) != null;
		try
		{
			final AdaptationTable table = dataset.getTable(ConnectorsPaths.EXECUTION_RUN);
			final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
			if (pTrackingInfo != null)
			{
				service.setSessionTrackingInfo(pTrackingInfo);
			}
			final ProcedureResult written = service.execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					final Adaptation existing = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRunId));
					final ValueContextForUpdate values = existing == null ? pContext.getContextForNewOccurrence(table) : pContext.getContext(existing.getAdaptationName());
					values.setValue(pRunId, ConnectorsPaths.ExecutionRun.RUN_ID);
					// A negative index means the writer does not know the position (a worker that failed
					// outside the import loop): the resume position the run already saved is kept.
					if (pNextIndex >= 0 || existing == null)
					{
						values.setValue(Integer.valueOf(Math.max(0, pNextIndex)), ConnectorsPaths.ExecutionRun.NEXT_INDEX);
					}
					final java.util.Date now = new java.util.Date();
					values.setValue(now, ConnectorsPaths.ExecutionRun.UPDATED_AT);
					if (hasLeaseFields)
					{
						// the run is alive while it is RUNNING: this node holds it, for a lease each batch renews (this method is
						// called after every batch); an end state releases it. Another node starting up leaves a live lease alone.
						final boolean running = "RUNNING".equals(pStatus);
						values.setValue(running ? NodeIdentity.owner() : null, ConnectorsPaths.ExecutionRun.LEASE_OWNER);
						values.setValue(running ? NodeIdentity.expiry(now) : null, ConnectorsPaths.ExecutionRun.LEASE_EXPIRES_AT);
					}
					if (hasStateFields)
					{
						if (pFeed != null) values.setValue(String.valueOf(pFeed.getId()), ConnectorsPaths.ExecutionRun.MAPPING);
						values.setValue(pStatus, ConnectorsPaths.ExecutionRun.STATUS);
						if (existing == null) values.setValue(new java.util.Date(), ConnectorsPaths.ExecutionRun.STARTED_AT);
						if (pOutcome != null)
						{
							values.setValue(Integer.valueOf(pOutcome.getCreated()), ConnectorsPaths.ExecutionRun.CREATED_COUNT);
							values.setValue(Integer.valueOf(pOutcome.getUpdated()), ConnectorsPaths.ExecutionRun.UPDATED_COUNT);
							values.setValue(Integer.valueOf(pOutcome.getNoChange()), ConnectorsPaths.ExecutionRun.NO_CHANGE_COUNT);
							values.setValue(Integer.valueOf(pOutcome.getErrorCount()), ConnectorsPaths.ExecutionRun.ERROR_COUNT);
							values.setValue(Integer.valueOf(pOutcome.getSkipped()), ConnectorsPaths.ExecutionRun.SKIPPED_COUNT);
							values.setValue(Integer.valueOf(pOutcome.getRejected()), ConnectorsPaths.ExecutionRun.REJECTED_COUNT);
							values.setValue(Integer.valueOf(pOutcome.getConflicts()), ConnectorsPaths.ExecutionRun.CONFLICT_COUNT);
							values.setValue(Boolean.valueOf(pOutcome.isTruncated()), ConnectorsPaths.ExecutionRun.TRUNCATED);
							if (pOutcome.getAvailable() >= 0)
							{
								values.setValue(Integer.valueOf((int) Math.min(Integer.MAX_VALUE, pOutcome.getAvailable())), ConnectorsPaths.ExecutionRun.AVAILABLE);
							}
							values.setValue(Boolean.valueOf(pOutcome.isResultsOmitted()), ConnectorsPaths.ExecutionRun.RESULTS_OMITTED);
							final String firstErrors = ConnectorRepository.firstErrorsText(pOutcome);
							if (firstErrors != null) values.setValue(firstErrors, ConnectorsPaths.ExecutionRun.FIRST_ERRORS);
							if (hasWatermarkFields && pOutcome.isWatermarkActive())
							{
								values.setValue(pOutcome.getWatermarkBefore(), ConnectorsPaths.ExecutionRun.WATERMARK_BEFORE);
								values.setValue(pOutcome.getWatermarkAfter(), ConnectorsPaths.ExecutionRun.WATERMARK_AFTER);
								values.setValue(Integer.valueOf(pOutcome.getSkippedByWatermark()), ConnectorsPaths.ExecutionRun.SKIPPED_BY_WATERMARK);
							}
						}
						if (pMessage != null) values.setValue(ConnectorRepository.cut(pMessage, 2000), ConnectorsPaths.ExecutionRun.MESSAGE);
					}
					if (existing == null)
					{
						pContext.doCreateOccurrence(values, table);
					}
					else
					{
						pContext.doModifyContent(existing, values);
					}
				}
			});
			return Bookkeeping.committed(Bookkeeping.RUN_STATE, "run " + pRunId + ", status " + pStatus, written);
		}
		catch (final RuntimeException ex)
		{
			return Bookkeeping.lost(Bookkeeping.RUN_STATE, "run " + pRunId + ", status " + pStatus, ex);
		}
	}

	/** {@code "<key>: <message>"} lines, one per {@link ImportOutcome#getErrors()} entry, cut to fit the field. */
	private static String firstErrorsText(final ImportOutcome pOutcome)
	{
		if (pOutcome.getErrors().isEmpty())
		{
			return null;
		}
		final StringBuilder sb = new StringBuilder();
		for (final SyncResult r : pOutcome.getErrors())
		{
			final String key = r.getRecordKey() == null || r.getRecordKey().isEmpty() ? r.getRecordLabel() : r.getRecordKey();
			final String line = (key == null ? "" : key) + ": " + (r.getMessage() == null ? "" : r.getMessage());
			if (sb.length() > 0) sb.append('\n');
			sb.append(line);
			if (sb.length() > 16000) break;
		}
		return ConnectorRepository.cut(sb.toString(), 16000);
	}

	/**
	 * Reads back one run's persisted state (volume lot 7a, {@code connectors_run_get}); null when the
	 * table, the run, or the state fields do not exist.
	 */
	public Adaptation getRun(final String pRunId)
	{
		if (pRunId == null || pRunId.trim().isEmpty())
		{
			return null;
		}
		final Adaptation dataset = this.dataset();
		if (dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.EXECUTION_RUN) == null)
		{
			return null;
		}
		final AdaptationTable table = dataset.getTable(ConnectorsPaths.EXECUTION_RUN);
		return table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRunId));
	}

	/**
	 * Volume lot 7a: marks the {@code ExecutionRun} rows still {@code RUNNING} as {@code INTERRUPTED} -
	 * called once at module startup ({@code ConnectorsModuleRegistration}), since a run truly stuck at
	 * {@code RUNNING} after a restart can only mean the JVM (and every in-JVM-only cancellation flag,
	 * executor and thread) that was running it is gone. Audit C-44: on a repository shared by several
	 * nodes, a run whose lease is alive and held by ANOTHER node is that node's own and is left alone
	 * ({@link NodeIdentity#isAbandoned}); a run without lease, with an expired lease or held by an earlier
	 * incarnation of this node is interrupted as before. Never throws.
	 */
	public int markInterruptedRunningRuns(final Session pSession)
	{
		final Adaptation dataset = this.dataset();
		if (dataset == null || dataset.getSchemaNode().getNode(
			ConnectorsPaths.EXECUTION_RUN.add(ConnectorsPaths.ExecutionRun.STATUS)) == null)
		{
			return 0;
		}
		try
		{
			final AdaptationTable table = dataset.getTable(ConnectorsPaths.EXECUTION_RUN);
			final Request request = table.createRequest();
			request.setXPathFilter("./status='RUNNING'");
			final List<Adaptation> running = new ArrayList<Adaptation>();
			final RequestResult rows = request.execute();
			try
			{
				final String thisNode = NodeIdentity.node();
				final java.util.Date now = new java.util.Date();
				final boolean hasLease = dataset.getSchemaNode().getNode(ConnectorsPaths.EXECUTION_RUN.add(ConnectorsPaths.ExecutionRun.LEASE_OWNER)) != null;
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					if (!hasLease || NodeIdentity.isAbandoned(row.getString(ConnectorsPaths.ExecutionRun.LEASE_OWNER),
						(java.util.Date) row.get(ConnectorsPaths.ExecutionRun.LEASE_EXPIRES_AT), thisNode, now))
					{
						running.add(row);
					}
					else
					{
						ConnectorService.log().info("[connectors] run " + row.getString(ConnectorsPaths.ExecutionRun.RUN_ID)
							+ " left RUNNING: its lease is held by another node (" + row.getString(ConnectorsPaths.ExecutionRun.LEASE_OWNER) + ")");
					}
				}
			}
			finally
			{
				rows.close();
			}
			if (running.isEmpty())
			{
				return 0;
			}
			final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
			service.setSessionTrackingInfo("connectors:startup:interrupt-running-runs");
			final ProcedureResult written = service.execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					for (final Adaptation row : running)
					{
						final ValueContextForUpdate values = pContext.getContext(row.getAdaptationName());
						values.setValue("INTERRUPTED", ConnectorsPaths.ExecutionRun.STATUS);
						values.setValue(new java.util.Date(), ConnectorsPaths.ExecutionRun.UPDATED_AT);
						pContext.doModifyContent(row, values);
					}
				}
			});
			// One procedure for all of them: refused, none was marked and every one still reads RUNNING.
			return Bookkeeping.committed(Bookkeeping.RUN_STATE, running.size() + " run(s) left RUNNING by the previous JVM or node", written) ? running.size() : 0;
		}
		catch (final RuntimeException ex)
		{
			Bookkeeping.lost(Bookkeeping.RUN_STATE, "runs left RUNNING by the previous JVM", ex);
			return 0;
		}
	}

	/**
	 * Keeps the proposals a TRUSTED rule refused, for a steward (one open conflict per field and proposal).
	 *
	 * @return false when they could not be written (reported through {@link Bookkeeping}): the steward
	 *         will not see these proposals.
	 */
	public boolean logConflicts(final Session pSession, final Adaptation pRecord, final MappingDefinition pMapping, final ConnectorConfig pConnector,
		final java.util.List<MappingResult.Conflict> pConflicts)
	{
		final Adaptation dataset = this.dataset();
		if (pRecord == null || pConnector == null || pConflicts.isEmpty() || !this.hasTable(dataset, ConnectorsPaths.CONFLICT))
		{
			return true;
		}
		final String dataspace = pRecord.getHome().getKey().getName();
		final String datasetName = pRecord.getContainer().getAdaptationName().getStringName();
		final String table = pRecord.getContainerTable().getTablePath().format();
		final String key = ConnectorRepository.recordKey(pRecord);
		final String label = pRecord.getLabel(java.util.Locale.getDefault());
		final String context = "mapping " + (pMapping == null ? "?" : String.valueOf(pMapping.getId())) + ", " + table + ", " + pConflicts.size() + " proposal(s)";
		try
		{
			final ProcedureResult written = ProgrammaticService.createForSession(pSession, dataset.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					final AdaptationTable conflicts = dataset.getTable(ConnectorsPaths.CONFLICT);
					for (final MappingResult.Conflict conflict : pConflicts)
					{
						// Ephemeral samples must not become durable, steward-approvable proposals.
						if (SampleValueSafety.isSampleField(table, conflict.path)) continue;
						final Request request = conflicts.createRequest();
						request.setXPathFilter("(./status='OPEN' or ./status='APPLYING') and ./dataset=" + ConnectorRepository.quote(datasetName) + " and ./tablePath=" + ConnectorRepository.quote(table)
								+ " and ./recordKey=" + ConnectorRepository.quote(key) + " and ./fieldPath=" + ConnectorRepository.quote(conflict.path) + " and ./proposedValue="
								+ ConnectorRepository.quote(conflict.proposedValue));
						final RequestResult rows = request.execute();
						try
						{
							if (rows.nextAdaptation() != null)
							{
								continue; // already reported
							}
						}
						finally
						{
							rows.close();
						}
						final ValueContextForUpdate row = pContext.getContextForNewOccurrence(conflicts);
						row.setValue("OPEN", ConnectorsPaths.Conflict.STATUS);
						row.setValue(dataspace, ConnectorsPaths.Conflict.DATASPACE);
						row.setValue(datasetName, ConnectorsPaths.Conflict.DATASET);
						row.setValue(table, ConnectorsPaths.Conflict.TABLE_PATH);
						row.setValue(key, ConnectorsPaths.Conflict.RECORD_KEY);
						row.setValue(label == null ? null : label.length() > 255 ? label.substring(0, 255) : label, ConnectorsPaths.Conflict.RECORD_LABEL);
						row.setValue(conflict.path, ConnectorsPaths.Conflict.FIELD_PATH);
						row.setValue(conflict.currentValue, ConnectorsPaths.Conflict.CURRENT_VALUE);
						row.setValue(conflict.currentConnector, ConnectorsPaths.Conflict.CURRENT_CONNECTOR);
						row.setValue(Integer.valueOf(conflict.currentTrust), ConnectorsPaths.Conflict.CURRENT_TRUST);
						row.setValue(conflict.proposedValue, ConnectorsPaths.Conflict.PROPOSED_VALUE);
						row.setValue(pConnector.getId(), ConnectorsPaths.Conflict.PROPOSED_CONNECTOR);
						row.setValue(Integer.valueOf(pConnector.getTrust()), ConnectorsPaths.Conflict.PROPOSED_TRUST);
						row.setValue(pMapping == null ? null : String.valueOf(pMapping.getId()), ConnectorsPaths.Conflict.MAPPING);
						row.setValue(new java.util.Date(), ConnectorsPaths.Conflict.DETECTED_AT);
						if (conflict.comment != null)
						{
							row.setValue(conflict.comment.length() > 500 ? conflict.comment.substring(0, 500) : conflict.comment, ConnectorsPaths.Conflict.COMMENT);
						}
						pContext.doCreateOccurrence(row, conflicts);
					}
				}
			});
			return Bookkeeping.committed(Bookkeeping.CONFLICTS, context, written);
		}
		catch (final Exception ex)
		{
			return Bookkeeping.lost(Bookkeeping.CONFLICTS, context, ex);
		}
	}

	// ------------------------------------------------------------------ outbound state (deltas, replay)

	private AdaptationTable outboundTable()
	{
		final Adaptation dataset = this.dataset();
		return dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.OUTBOUND_STATE) == null ? null : dataset.getTable(ConnectorsPaths.OUTBOUND_STATE);
	}

	public Adaptation outboundState(final int pMappingId, final String pRecordKey)
	{
		final AdaptationTable table = this.outboundTable();
		if (table == null || pRecordKey == null)
		{
			return null;
		}
		return table.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(com.onwbp.adaptation.PrimaryKey.parseObjects(new Object[] { String.valueOf(pMappingId), pRecordKey },
			new com.orchestranetworks.schema.SchemaNode[] { table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.OutboundState.MAPPING),
					table.getTableOccurrenceRootNode().getNode(ConnectorsPaths.OutboundState.RECORD_KEY) })));
	}

	/** One state row of a mapping, handed to {@link #forEachOutboundState}. */
	public interface OutboundStateVisitor
	{
		void visit(Adaptation pState);
	}

	/**
	 * Hands each state of a mapping, optionally in one status (SENT, FAILED, DELETED), to the visitor, one at a time, and keeps
	 * none of them: a mapping that has sent a million records holds a million rows, each with the last document it sent,
	 * which a list of them would keep in memory for as long as the caller keeps it (audit C-14).
	 */
	public void forEachOutboundState(final int pMappingId, final String pStatus, final OutboundStateVisitor pVisitor)
	{
		final AdaptationTable table = this.outboundTable();
		if (table == null)
		{
			return;
		}
		final Request request = table.createRequest();
		request.setXPathFilter("./mapping=" + ConnectorRepository.quote(String.valueOf(pMappingId)) + (pStatus == null ? "" : " and ./status=" + ConnectorRepository.quote(pStatus)));
		final RequestResult rows = request.execute();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				pVisitor.visit(row);
			}
		}
		finally
		{
			rows.close();
		}
	}

	/** States of a mapping, optionally in one status (SENT, FAILED, DELETED), all held in a list: for a screen or a tool that shows them. */
	public java.util.List<Adaptation> outboundStates(final int pMappingId, final String pStatus)
	{
		final java.util.List<Adaptation> out = new ArrayList<Adaptation>();
		this.forEachOutboundState(pMappingId, pStatus, new OutboundStateVisitor()
		{
			@Override
			public void visit(final Adaptation pState)
			{
				out.add(pState);
			}
		});
		return out;
	}

	/**
	 * Like {@link #outboundStates(int, String)} for a reader: a state whose record the session may not read (EBX permissions of the
	 * origin table, {@link OriginVisibility}) is left out, since it carries the record's label and the document sent (audit C2-03).
	 */
	public java.util.List<Adaptation> outboundStates(final Session pSession, final int pMappingId, final String pStatus)
	{
		final java.util.List<Adaptation> out = new ArrayList<Adaptation>();
		for (final Adaptation state : this.outboundStates(pMappingId, pStatus))
		{
			if (OriginVisibility.canRead(pSession, state.getString(ConnectorsPaths.OutboundState.DATASPACE), state.getString(ConnectorsPaths.OutboundState.DATASET),
				state.getString(ConnectorsPaths.OutboundState.TABLE_PATH), state.getString(ConnectorsPaths.OutboundState.RECORD_KEY)))
			{
				out.add(state);
			}
		}
		return out;
	}

	/**
	 * One page of {@link #outboundStates(Session, int, String)}: walks the states one at a time (none is kept but the page's), leaves out
	 * those the session may not read, and counts the others, so {@link ListingPage#getTotal()} is what the full list held. The walk cannot
	 * stop at the page's end: the total has to be the number of states the reader may see, which only a read of each state's origin tells
	 * (audit C2-07).
	 */
	public ListingPage<Adaptation> pageOutboundStates(final Session pSession, final int pMappingId, final String pStatus, final int pFrom, final int pMax)
	{
		final ListingPage<Adaptation> page = new ListingPage<Adaptation>(pFrom, pMax);
		this.forEachOutboundState(pMappingId, pStatus, new OutboundStateVisitor()
		{
			@Override
			public void visit(final Adaptation pState)
			{
				if (OriginVisibility.canRead(pSession, pState.getString(ConnectorsPaths.OutboundState.DATASPACE), pState.getString(ConnectorsPaths.OutboundState.DATASET),
					pState.getString(ConnectorsPaths.OutboundState.TABLE_PATH), pState.getString(ConnectorsPaths.OutboundState.RECORD_KEY)))
				{
					page.offer(pState);
				}
			}
		});
		return page;
	}

	/**
	 * Upserts the state of one record for one mapping. {@code pStatus} SENT (with hash and document), FAILED (with error) or DELETED.
	 *
	 * @return false when the state could not be written (reported through {@link Bookkeeping}). The
	 *         remote call it describes has happened all the same: a lost SENT means the record is sent
	 *         again by the next {@code unsent}/{@code changed} scope, a lost FAILED that the record is
	 *         missing from the {@code failed} scope (the run's log still replays it), a lost DELETED
	 *         that the deletion is attempted again.
	 */
	public boolean markOutbound(final Session pSession, final MappingDefinition pMapping, final String pRecordKey, final String pRecordLabel,
		final String pStatus, final String pHash, final String pDocument, final String pError, final String pRunId)
	{
		return this.markOutboundBatch(pSession, java.util.Collections.singletonList(
			new OutboundMark(pMapping, pRecordKey, pRecordLabel, pStatus, pHash, pDocument, pError, pRunId)));
	}

	/** One outbound state to write: what {@link #markOutbound} takes. */
	public static final class OutboundMark
	{
		final MappingDefinition mapping;
		final String recordKey;
		final String recordLabel;
		final String status;
		final String hash;
		final String document;
		final String error;
		final String runId;
		/** Where the call came from, for the notice written when this state is lost. */
		String origin;

		public OutboundMark(final MappingDefinition pMapping, final String pRecordKey, final String pRecordLabel, final String pStatus, final String pHash,
			final String pDocument, final String pError, final String pRunId)
		{
			this.mapping = pMapping;
			this.recordKey = pRecordKey;
			this.recordLabel = pRecordLabel;
			this.status = pStatus;
			this.hash = pHash;
			this.document = pDocument;
			this.error = pError;
			this.runId = pRunId;
		}

		String status()
		{
			return this.status;
		}

		/** The state this write replaces is identified by mapping and record: two marks with the same identity cannot share a transaction. */
		String identity()
		{
			return this.mapping.getId() + "\u0000" + this.recordKey;
		}
	}

	/**
	 * {@link #markOutbound} for several records in ONE transaction (record-mode runs write a bounded batch, audit C-13). The
	 * states already stored are read before it, outside the procedure; a state may appear once in a batch.
	 *
	 * @return false when none could be written (reported through {@link Bookkeeping}); the caller falls back to one at a time.
	 */
	public boolean markOutboundBatch(final Session pSession, final java.util.List<OutboundMark> pMarks)
	{
		final AdaptationTable table = this.outboundTable();
		if (table == null || pMarks.isEmpty())
		{
			return true;
		}
		boolean any = false;
		for (final OutboundMark mark : pMarks)
		{
			any = any || mark.recordKey != null;
		}
		if (!any)
		{
			return true;
		}
		final OutboundMark first = pMarks.get(0);
		final String context = "run " + first.runId + ", mapping " + first.mapping.getId() + ", status " + first.status
			+ (pMarks.size() > 1 ? " and " + (pMarks.size() - 1) + " more state(s)" : "");
		try
		{
			final java.util.List<Adaptation> existing = new ArrayList<Adaptation>(pMarks.size());
			for (final OutboundMark mark : pMarks)
			{
				existing.add(mark.recordKey == null ? null : this.outboundState(mark.mapping.getId(), mark.recordKey));
			}
			final ProcedureResult written = ProgrammaticService.createForSession(pSession, table.getContainerAdaptation().getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					for (int i = 0; i < pMarks.size(); i++)
					{
						final OutboundMark mark = pMarks.get(i);
						if (mark.recordKey == null)
						{
							continue;
						}
						ConnectorRepository.writeOutboundRow(pContext, table, mark, existing.get(i));
					}
				}
			});
			return Bookkeeping.committed(Bookkeeping.OUTBOUND_STATE, context, written);
		}
		catch (final Exception ex)
		{
			return Bookkeeping.lost(Bookkeeping.OUTBOUND_STATE, context, ex);
		}
	}

	private static void writeOutboundRow(final ProcedureContext pContext, final AdaptationTable pTable, final OutboundMark pMark, final Adaptation pExisting) throws Exception
	{
		final ValueContextForUpdate row = pExisting == null ? pContext.getContextForNewOccurrence(pTable) : pContext.getContext(pExisting.getAdaptationName());
		final Date now = new Date();
		row.setValue(String.valueOf(pMark.mapping.getId()), ConnectorsPaths.OutboundState.MAPPING);
		row.setValue(ConnectorRepository.cut(pMark.recordKey, 255), ConnectorsPaths.OutboundState.RECORD_KEY);
		if (pMark.recordLabel != null)
		{
			row.setValue(ConnectorRepository.cut(pMark.recordLabel, 255), ConnectorsPaths.OutboundState.RECORD_LABEL);
		}
		row.setValue(pMark.mapping.getDataspace(), ConnectorsPaths.OutboundState.DATASPACE);
		row.setValue(pMark.mapping.getDataset(), ConnectorsPaths.OutboundState.DATASET);
		row.setValue(pMark.mapping.getTablePath(), ConnectorsPaths.OutboundState.TABLE_PATH);
		row.setValue(pMark.status, ConnectorsPaths.OutboundState.STATUS);
		row.setValue(now, ConnectorsPaths.OutboundState.LAST_ATTEMPT_AT);
		row.setValue(pMark.runId, ConnectorsPaths.OutboundState.LAST_RUN_ID);
		if ("FAILED".equals(pMark.status))
		{
			final Integer attempts = pExisting == null ? null : (Integer) pExisting.get(ConnectorsPaths.OutboundState.ATTEMPTS);
			row.setValue(Integer.valueOf(attempts == null ? 1 : attempts.intValue() + 1), ConnectorsPaths.OutboundState.ATTEMPTS);
			row.setValue(ConnectorRepository.cut(pMark.error, 2000), ConnectorsPaths.OutboundState.LAST_ERROR);
		}
		else
		{
			row.setValue(Integer.valueOf(0), ConnectorsPaths.OutboundState.ATTEMPTS);
			row.setValue(null, ConnectorsPaths.OutboundState.LAST_ERROR);
			row.setValue(now, ConnectorsPaths.OutboundState.LAST_SENT_AT);
			if (pMark.hash != null)
			{
				row.setValue(pMark.hash, ConnectorsPaths.OutboundState.HASH);
			}
			if (pMark.document != null)
			{
				row.setValue(ConnectorRepository.cut(pMark.document, 8000), ConnectorsPaths.OutboundState.LAST_DOCUMENT);
			}
		}
		if (pExisting == null)
		{
			pContext.doCreateOccurrence(row, pTable);
		}
		else
		{
			pContext.doModifyContent(pExisting, row);
		}
	}

	// ------------------------------------------------ synchronization checkpoints

	private AdaptationTable checkpointTable()
	{
		final Adaptation dataset = this.requireDataset();
		return dataset.getSchemaNode().getNode(ConnectorsPaths.SYNCHRONIZATION_CHECKPOINT) == null ? null
			: dataset.getTable(ConnectorsPaths.SYNCHRONIZATION_CHECKPOINT);
	}

	/** Loads durable incremental progress for one mapping; mapping policy itself is stored elsewhere. */
	public SynchronizationCheckpoint checkpoint(final int pMappingId)
	{
		final AdaptationTable table = this.checkpointTable();
		if (table == null) return null;
		final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(String.valueOf(pMappingId)));
		if (row == null) return null;
		final String strategy = row.getString(ConnectorsPaths.SynchronizationCheckpoint.STRATEGY);
		final Number version = (Number) row.get(ConnectorsPaths.SynchronizationCheckpoint.VERSION);
		return new SynchronizationCheckpoint(pMappingId,
			com.on.ebx.connect.spi.SynchronizationStrategy.valueOf(strategy),
			row.getString(ConnectorsPaths.SynchronizationCheckpoint.VALUE),
			(Date) row.get(ConnectorsPaths.SynchronizationCheckpoint.UPDATED_AT), version == null ? 0L : version.longValue());
	}

	/** Atomically advances a checkpoint when its expected version still matches. */
	public SynchronizationCheckpoint saveCheckpoint(final Session pSession, final SynchronizationCheckpoint pCheckpoint,
		final long pExpectedVersion, final Date pNow)
	{
		final AdaptationTable table = this.checkpointTable();
		if (table == null) throw new IllegalStateException("SynchronizationCheckpoint table is unavailable; reload the Connectors data model");
		final SynchronizationCheckpoint[] saved = new SynchronizationCheckpoint[1];
		final Date effectiveNow = pNow == null ? new Date() : pNow;
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, table.getContainerAdaptation().getHome()).execute(new Procedure()
		{
			@Override public void execute(final ProcedureContext pContext) throws Exception
			{
				// engine bookkeeping, like the log, the runs and the provenance: the caller's right (RUN) was checked where the
				// run started, and the table is read only to RUN holders by hand (audit C-01, ConnectorsTableAccessRule)
				pContext.setAllPrivileges(true);
				final Adaptation existing = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(String.valueOf(pCheckpoint.getMappingId())));
				final Number currentVersion = existing == null ? null : (Number) existing.get(ConnectorsPaths.SynchronizationCheckpoint.VERSION);
				final long actual = currentVersion == null ? 0L : currentVersion.longValue();
				if (actual != pExpectedVersion) throw new IllegalStateException("Checkpoint was advanced concurrently");
				final long next = actual + 1L;
				final ValueContextForUpdate values = existing == null ? pContext.getContextForNewOccurrence(table)
					: pContext.getContext(existing.getAdaptationName());
				values.setValue(String.valueOf(pCheckpoint.getMappingId()), ConnectorsPaths.SynchronizationCheckpoint.MAPPING);
				values.setValue(pCheckpoint.getStrategy().name(), ConnectorsPaths.SynchronizationCheckpoint.STRATEGY);
				values.setValue(pCheckpoint.getValue(), ConnectorsPaths.SynchronizationCheckpoint.VALUE);
				values.setValue(effectiveNow, ConnectorsPaths.SynchronizationCheckpoint.UPDATED_AT);
				if (next > Integer.MAX_VALUE) throw new IllegalStateException("Checkpoint version limit reached");
				values.setValue(Integer.valueOf((int) next), ConnectorsPaths.SynchronizationCheckpoint.VERSION);
				if (existing == null) pContext.doCreateOccurrence(values, table);
				else pContext.doModifyContent(existing, values);
				saved[0] = new SynchronizationCheckpoint(pCheckpoint.getMappingId(), pCheckpoint.getStrategy(),
					pCheckpoint.getValue(), effectiveNow, next);
			}
		});
		if (result.hasFailed()) throw new IllegalStateException("EBX rejected synchronization checkpoint", result.getException());
		return saved[0];
	}

	/**
	 * One page of the log rows of a run, newest first (audit C-33): the sort is on the primary key, so the rows of a run
	 * that just ran are met at once instead of after every older row, and the read stops once the page and the one
	 * row that says whether it is cut are in hand. The caller asks for {@code pMax} 0 to read nothing at all.
	 */
	public RunLogPage<Adaptation> logOfRunPage(final String pRunId, final int pFrom, final int pMax)
	{
		final Adaptation dataset = this.dataset();
		if (pMax <= 0 || dataset == null || pRunId == null
			|| dataset.getTable(ConnectorsPaths.SYNC_LOG).getTableOccurrenceRootNode().getNode(ConnectorsPaths.SyncLog.RUN_ID) == null)
		{
			return RunLogPage.take(new ArrayList<Adaptation>().iterator(), pFrom, 0);
		}
		final Request request = dataset.getTable(ConnectorsPaths.SYNC_LOG).createRequest();
		request.setXPathFilter("./runId=" + ConnectorRepository.quote(pRunId));
		request.setSortCriteria(new com.onwbp.adaptation.RequestSortCriteria().add(ConnectorsPaths.SyncLog.ID, false));
		final RequestResult rows = request.execute();
		try
		{
			return RunLogPage.take(new java.util.Iterator<Adaptation>()
			{
				private Adaptation next = rows.nextAdaptation();

				@Override
				public boolean hasNext()
				{
					return this.next != null;
				}

				@Override
				public Adaptation next()
				{
					final Adaptation current = this.next;
					this.next = rows.nextAdaptation();
					return current;
				}

				@Override
				public void remove()
				{
					throw new UnsupportedOperationException();
				}
			}, pFrom, pMax);
		}
		finally
		{
			rows.close();
		}
	}

	/** Every log row of one run, in key order (for a replay); a status answer reads {@link #logOfRunPage} instead. */
	public java.util.List<Adaptation> logOfRun(final String pRunId)
	{
		final java.util.List<Adaptation> out = new ArrayList<Adaptation>();
		final Adaptation dataset = this.dataset();
		if (dataset == null || pRunId == null || dataset.getTable(ConnectorsPaths.SYNC_LOG).getTableOccurrenceRootNode().getNode(ConnectorsPaths.SyncLog.RUN_ID) == null)
		{
			return out;
		}
		final Request request = dataset.getTable(ConnectorsPaths.SYNC_LOG).createRequest();
		request.setXPathFilter("./runId=" + ConnectorRepository.quote(pRunId));
		final RequestResult rows = request.execute();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	public Repository getRepository()
	{
		return this.repository;
	}

	/** Path helper for tables of the configuration dataset. */
	static Path table(final Path pPath)
	{
		return pPath;
	}
}
