package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Operation;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.schema.SchemaTypeName;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Entry point for every EBX context (user service, workflow, scheduler, trigger, plain Java):
 * <pre>
 * SyncResult r = ConnectorService.get().syncRecord(session, record, "GLEIF", "lei", false, "myCode");
 * JsonNode doc = ConnectorService.get().call(session, "GLEIF", "lei", inputs, null);
 * </pre>
 * A synchronisation resolves the mapping declared for the record's table, evaluates the mapping
 * inputs on the record, calls the connector, applies the rules in a procedure on the record's
 * dataspace and writes one line in the sync log.
 */
public final class ConnectorService
{
	private static final ConnectorService INSTANCE = new ConnectorService();

	private ConnectorService()
	{
	}

	public static ConnectorService get()
	{
		return ConnectorService.INSTANCE;
	}

	/** Per explicit run: the results of each mapping, described as one OpenLineage event at {@link #endRun()}. */
	private static final ThreadLocal<Map<Integer, OpenLineageEmitter.Pending>> LINEAGE = new ThreadLocal<Map<Integer, OpenLineageEmitter.Pending>>();

	/**
	 * Starts an execution: every call logged until {@link #endRun()} carries this id (replayable
	 * through the API), and the lineage of the run is sent in one event per mapping when it ends.
	 * The current-run id itself is held by {@link RunIds}, not here — see its class comment for why.
	 */
	public static String beginRun()
	{
		return ConnectorService.beginRun(ConnectorService.generateRunId());
	}

	/**
	 * Volume lot 7a: like {@link #beginRun()}, but adopting a caller-chosen run id instead of minting a
	 * new one - the asynchronous import worker thread ({@link #importFromAsync}) does not share the
	 * launching thread's {@link ThreadLocal} state, so it resumes the run id that was already minted
	 * (and already persisted as an {@code ExecutionRun} row) on the calling thread before the work was
	 * handed off.
	 */
	public static String beginRun(final String pRunId)
	{
		OpenLineageEmitter.flush(ConnectorService.LINEAGE.get()); // a previous run on this thread that never ended
		RunIds.set(pRunId);
		HttpExchangeStats.drain(); // retries of diagnostics or previews on this thread are not this run's
		ConnectorService.LINEAGE.set(new LinkedHashMap<Integer, OpenLineageEmitter.Pending>());
		return pRunId;
	}

	/** A fresh run id, with no side effect on any thread-local state (the sequence lives in {@link RunIds}). */
	private static String generateRunId()
	{
		return RunIds.newId();
	}

	public static void endRun()
	{
		final Map<Integer, OpenLineageEmitter.Pending> pending = ConnectorService.LINEAGE.get();
		ConnectorService.LINEAGE.remove();
		RunIds.clear();
		OpenLineageEmitter.flush(pending);
	}

	/** The current run id, a fresh one when no run was begun (a single call is its own run). */
	public static String currentRunId()
	{
		return RunIds.current();
	}

	// ------------------------------------------------------------------ volume lot 6: batched imports

	/** Consecutive fully-failed batches (every write attempted in the batch errored) before a batched import gives up. */
	static final int CIRCUIT_BREAKER_THRESHOLD = 3;

	/**
	 * Run ids currently flagged for cancellation between batches (task 5). In-JVM, single-node state
	 * only - not persisted, lost on restart, invisible to other cluster members. A caller wanting to
	 * cancel a long-running import must first discover its run id from the rows it is already writing
	 * incrementally (the batch SyncLog rows, or ExecutionRun's {@code nextIndex}, both updated after
	 * every batch by this same lot) while it is still running, then call {@link #requestCancel}; the
	 * running batch loop checks it between batches, never mid-batch.
	 */
	private static final java.util.Set<String> CANCELLED_RUNS =
		java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

	/** Flags a run id for cancellation; the next batch boundary of a matching, still-running import stops it. */
	public static void requestCancel(final String pRunId)
	{
		if (pRunId != null && !pRunId.trim().isEmpty())
		{
			ConnectorService.CANCELLED_RUNS.add(pRunId);
		}
	}

	static boolean isCancelled(final String pRunId)
	{
		return pRunId != null && ConnectorService.CANCELLED_RUNS.contains(pRunId);
	}

	/** Drops a run id from the cancellation set once its import ends, cancelled or not, so the set never grows unbounded. */
	private static void clearCancellation(final String pRunId)
	{
		if (pRunId != null)
		{
			ConnectorService.CANCELLED_RUNS.remove(pRunId);
		}
	}

	/**
	 * Records the result of one record for the lineage of the run: buffered until {@link #endRun()}
	 * inside an explicit run, sent at once otherwise (a table trigger, a single call).
	 */
	static void lineage(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pMapping, final SyncResult pResult)
	{
		if (pMapping == null || pMapping.isChild() || pResult == null)
		{
			return;
		}
		final Map<Integer, OpenLineageEmitter.Pending> buffer = ConnectorService.LINEAGE.get();
		if (buffer == null)
		{
			OpenLineageEmitter.emit(pSession, pRepo, pMapping, java.util.Collections.singletonList(pResult), pResult.getRunId());
			return;
		}
		OpenLineageEmitter.Pending pending = buffer.get(Integer.valueOf(pMapping.getId()));
		if (pending == null)
		{
			pending = new OpenLineageEmitter.Pending(pSession, pRepo, pMapping, pResult.getRunId() == null ? ConnectorService.currentRunId() : pResult.getRunId());
			buffer.put(Integer.valueOf(pMapping.getId()), pending);
		}
		// Volume lot 7a: a run-long buffer of every SyncResult (item 2 of the 2026-09-28 audit) is now a
		// running tally instead - see OpenLineageEmitter.ResultTally. The event this run eventually sends
		// only ever needed counts (sent/failed/partial/skipped/deleted) plus a small sample of failing
		// keys, never the individual results.
		pending.tally.add(pMapping, pResult);
	}

	/** The end of a record-mode run opened by {@link #beginRecordRun}: writes what is still waiting. */
	public interface RecordRunScope extends AutoCloseable
	{
		@Override
		void close();
	}

	/**
	 * Opens a record-mode run on this thread around several {@code syncRecord} calls of one caller (a workflow step that goes
	 * through the records of a table): the mappings and connector configurations are read once, and the sync log lines,
	 * provenance, outbound states and metrics of the records are written in groups. {@code syncRecords}, {@code sendScope}
	 * and {@code replay} do this themselves. The scope must be closed, in a {@code finally}, before the results are read (audit C-13).
	 */
	public static RecordRunScope beginRecordRun(final Session pSession, final Repository pRepository)
	{
		final RecordRun.Scope scope = RecordRun.open(pSession, pRepository);
		return new RecordRunScope()
		{
			@Override
			public void close()
			{
				scope.close();
			}
		};
	}

	public ConnectorRepository repository(final Repository pRepository)
	{
		return new ConnectorRepository(pRepository);
	}

	// ------------------------------------------------------------------ raw call

	/** Calls a connector operation without any mapping. */
	public JsonNode call(final Session pSession, final Repository pRepository, final String pConnectorId,
		final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		final ConnectorRepository repo = this.repository(pRepository);
		final Connector connector = ConnectorRegistry.get().connector(pConnectorId, repo);
		return connector.execute(new ConnectorContext(pSession, repo.getRepository(), null, null), pOperation,
			pInputs, pPayload);
	}

	// ------------------------------------------------------------------ one record

	/** Synchronises one record with the mapping declared for its table, connector and operation. */
	public SyncResult syncRecord(final Session pSession, final Adaptation pRecord, final String pConnectorId,
		final String pOperation, final MappingDefinition.Direction pDirection, final boolean pDryRun,
		final String pOrigin)
	{
		final ConnectorRepository repo = this.repository(pRecord.getHome().getRepository());
		final MappingDefinition mapping;
		try
		{
			mapping = repo.mappingFor(pRecord.getHome().getKey().getName(),
				pRecord.getContainer().getAdaptationName().getStringName(), pRecord.getContainerTable().getTablePath().format(),
				pConnectorId, pOperation, pDirection);
		}
		catch (final IllegalStateException ambiguous)
		{
			final SyncResult result = this.newResult(pRecord, pConnectorId, pOperation, pDirection);
			result.setStatus(SyncResult.Status.ERROR);
			result.setMessage(ambiguous.getMessage());
			return result;
		}
		if (mapping == null)
		{
			final SyncResult result = this.newResult(pRecord, pConnectorId, pOperation, pDirection);
			result.setStatus(SyncResult.Status.ERROR);
			result.setMessage("No " + pDirection + " mapping for " + pConnectorId + "/" + pOperation + " on "
					+ pRecord.getContainerTable().getTablePath().format());
			return result;
		}
		return this.syncRecord(pSession, pRecord, mapping, pDryRun, pOrigin);
	}

	/** Synchronises one record with an explicit mapping, in its own procedure. */
	public SyncResult syncRecord(final Session pSession, final Adaptation pRecord, final MappingDefinition pMapping,
		final boolean pDryRun, final String pOrigin)
	{
		final long start = System.currentTimeMillis();
		final SyncResult result = this.newResult(pRecord, pMapping.getConnectorId(), pMapping.getOperation(),
			pMapping.getDirection());
		result.setRunId(ConnectorService.currentRunId());
		result.setMappingId(String.valueOf(pMapping.getId()));
		try
		{
			if (pSession == null || pSession.getPermissions().getAdaptationAccessPermission(pRecord).isHidden())
			{
				throw new SecurityException("Record is unavailable or restricted");
			}
			final Prepared prepared = this.prepare(pSession, pRecord, pMapping, result, pDryRun);
			if (prepared != null)
			{
				this.apply(pSession, pRecord, pMapping, prepared, result, pDryRun);
				if (!pDryRun && (!result.getChanges().isEmpty() || !result.getConflicts().isEmpty()))
				{
					final ConnectorRepository repo = this.repository(pRecord.getHome().getRepository());
					final RecordRun run = RecordRun.forSession(pSession);
					final ConnectorConfig config = ConnectorService.runConnectorConfig(run == null ? null : run.cache, repo, pMapping.getConnectorId());
					final Adaptation written = pRecord.getContainerTable().lookupAdaptationByPrimaryKey(pRecord.getOccurrencePrimaryKey());
					if (run != null)
					{
						// a run writes the provenance of its records in groups (audit C-13), told to the result if one is lost
						if (!result.getChanges().isEmpty() && config != null)
						{
							run.bookkeeping().provenance(pMapping, config, new ConnectorRepository.RecordChange(written == null ? pRecord : written, result.getChanges()), result);
						}
					}
					else if (!repo.recordProvenance(pSession, written == null ? pRecord : written, pMapping, config, result.getChanges()))
					{
						result.bookkeepingLost(Bookkeeping.PROVENANCE);
					}
					if (!repo.logConflicts(pSession, written == null ? pRecord : written, pMapping, config, result.getConflicts()))
					{
						result.bookkeepingLost(Bookkeeping.CONFLICTS);
					}
				}
			}
		}
		catch (final Exception ex)
		{
			result.setStatus(SyncResult.Status.ERROR);
			result.setMessage(SampleValueSafety.safeFailure(pMapping, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
		}
		result.setDurationMs(System.currentTimeMillis() - start);
		if (!pDryRun)
		{
			final ConnectorRepository repo = this.repository(pRecord.getHome().getRepository());
			final RecordRun run = RecordRun.forSession(pSession);
			ConnectorService.bookLog(run, pSession, repo, result, pOrigin);
			if (pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND && !pMapping.isChild())
			{
				final String key = pRecord.getOccurrencePrimaryKey().format();
				final String label = pRecord.getLabel(java.util.Locale.getDefault());
				if (result.getStatus() == SyncResult.Status.ERROR)
				{
					ConnectorService.bookOutbound(run, pSession, repo, pOrigin, result,
						new ConnectorRepository.OutboundMark(pMapping, key, label, "FAILED", null, null, result.getMessage(), result.getRunId()));
				}
				else if (result.getStatus() != SyncResult.Status.SKIPPED && result.getOutboundDocument() != null)
				{
					ConnectorService.bookOutbound(run, pSession, repo, pOrigin, result,
						new ConnectorRepository.OutboundMark(pMapping, key, label, "SENT", Hashing.sha256(result.getOutboundDocument()), result.getOutboundDocument(), null,
							result.getRunId()));
				}
			}
			ConnectorService.lineage(pSession, repo, pMapping, result);
		}
		return result;
	}

	/** One sync log line: written now, or with the others of the run when there is one (audit C-13). */
	private static void bookLog(final RecordRun pRun, final Session pSession, final ConnectorRepository pRepo, final SyncResult pResult, final String pOrigin)
	{
		if (pRun != null)
		{
			pRun.bookkeeping().log(new ConnectorRepository.LogEntry(pResult, pOrigin, new java.util.Date(), HttpExchangeStats.drain()));
		}
		else if (!pRepo.log(pSession, pResult, pOrigin))
		{
			pResult.bookkeepingLost(Bookkeeping.SYNC_LOG);
		}
	}

	/** One outbound state: written now, or with the others of the run; a state that is lost is said in the result and the log. */
	private static void bookOutbound(final RecordRun pRun, final Session pSession, final ConnectorRepository pRepo, final String pOrigin, final SyncResult pResult,
		final ConnectorRepository.OutboundMark pMark)
	{
		if (pRun != null)
		{
			pMark.origin = pOrigin;
			pRun.bookkeeping().outbound(pMark, pResult);
		}
		else if (!pRepo.markOutboundBatch(pSession, java.util.Collections.singletonList(pMark)))
		{
			ConnectorService.outboundStateLost(pSession, pRepo, pResult, pMark.status(), pOrigin);
		}
	}

	/**
	 * An outbound state that could not be saved after the remote call happened. The result keeps its
	 * status - the call is what it is - but whoever reads the run must be able to tell: the result
	 * carries the loss for the direct caller, and one more sync log line (same run, same record, status
	 * {@code PARTIAL}) says it to whoever reads the run later. {@code PARTIAL} rather than {@code ERROR}
	 * so that a replay of the run, which re-sends the {@code ERROR} lines, does not send a delivered
	 * record a second time on account of this line.
	 */
	static void outboundStateLost(final Session pSession, final ConnectorRepository pRepo, final SyncResult pResult, final String pState, final String pOrigin)
	{
		pResult.bookkeepingLost(Bookkeeping.OUTBOUND_STATE);
		final SyncResult notice = new SyncResult(pResult.getConnectorId(), pResult.getOperation(), pResult.getDirection(), pResult.getDataspace(),
			pResult.getTablePath(), pResult.getRecordKey(), pResult.getRecordLabel());
		notice.setStatus(SyncResult.Status.PARTIAL);
		notice.setRunId(pResult.getRunId());
		notice.setMappingId(pResult.getMappingId());
		notice.setMessage(ConnectorService.outboundStateLostMessage(pState));
		pRepo.log(pSession, notice, pOrigin);
	}

	/** What a lost outbound state means for the next runs, by the state that was not saved. */
	static String outboundStateLostMessage(final String pState)
	{
		if ("SENT".equals(pState))
		{
			return "Outbound state SENT not saved: the record was delivered, but the next 'unsent' or 'changed' scope will send it again";
		}
		if ("DELETED".equals(pState))
		{
			return "Outbound state DELETED not saved: the remote deletion was done, but the next scope will attempt it again";
		}
		return "Outbound state FAILED not saved: the record is missing from the 'failed' scope; replay this run to retry it";
	}

	/**
	 * Same, but inside an existing procedure (table trigger, custom procedure): the caller's
	 * transaction carries the update, nothing is logged here.
	 */
	public SyncResult syncRecordInProcedure(final ProcedureContext pContext, final Adaptation pRecord,
		final MappingDefinition pMapping)
	{
		final long start = System.currentTimeMillis();
		final SyncResult result = this.newResult(pRecord, pMapping.getConnectorId(), pMapping.getOperation(),
			pMapping.getDirection());
		try
		{
			final Prepared prepared = this.prepare(pContext.getSession(), pRecord, pMapping, result);
			if (prepared != null)
			{
				this.applyIn(pContext, pRecord, pMapping, prepared, result, false);
			}
		}
		catch (final Exception ex)
		{
			result.setStatus(SyncResult.Status.ERROR);
			result.setMessage(SampleValueSafety.safeFailure(pMapping, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
		}
		result.setDurationMs(System.currentTimeMillis() - start);
		return result;
	}

	/**
	 * The send of one queued trigger entry (audit C-07, lot L8), run by the background sender after the user's transaction:
	 * the record is read as it is now, the mapping is chosen as the trigger would have, and the sync runs in its own procedure
	 * with the sync log, provenance, conflicts and outbound state a run writes (origin {@code trigger}). A record that no longer
	 * exists (a creation that was rolled back, a deletion) or a table without mapping is dropped, not retried.
	 */
	/**
	 * Real-time mastering: what a {@code publish=golden} queue entry sends, read now (after the matching): a GOLDEN, UNSET or
	 * unmanaged record sends itself, a MERGED one its golden record, a DELETED or PENDING_APPROVAL one nothing. A record or
	 * dataspace that no longer exists sends its own key, which {@link #sendQueued} then drops with its usual reason.
	 */
	public OutboundQueue.Resolution resolveQueued(final Repository pRepository, final OutboundQueue.Entry pEntry)
	{
		if (!PreviewFeatures.isOpen(PreviewFeatures.Feature.CDC_MATCHING))
		{
			// preview function, closed by default: the row stays queued and is set aside, with this reason, after its attempts
			throw new IllegalStateException(PreviewFeatures.closedMessage(PreviewFeatures.Feature.CDC_MATCHING));
		}
		final com.onwbp.adaptation.AdaptationHome home = pRepository.lookupHome(com.orchestranetworks.instance.HomeKey.forBranchName(pEntry.dataspace));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(com.onwbp.adaptation.AdaptationName.forName(pEntry.dataset));
		if (dataset == null || dataset.getSchemaNode().getNode(com.orchestranetworks.schema.Path.parse(pEntry.tablePath)) == null)
		{
			return OutboundQueue.Resolution.send(pEntry.recordKey);
		}
		final Adaptation record = dataset.getTable(com.orchestranetworks.schema.Path.parse(pEntry.tablePath)).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pEntry.recordKey));
		if (record == null)
		{
			return OutboundQueue.Resolution.send(pEntry.recordKey);
		}
		final GoldenRecords golden = GoldenRecords.get();
		final String state = golden.state(record);
		switch (GoldenRecords.publishAs(state))
		{
			case NOTHING:
				return OutboundQueue.Resolution.drop("publish=golden: the record is " + state + ", nothing to send");
			case GOLDEN:
				final Adaptation target = golden.golden(record);
				if (target == null || target == record || "MERGED".equals(golden.state(target)))
				{
					throw new IllegalStateException("publish=golden: the golden of MERGED record " + pEntry.recordKey + " is not readable yet");
				}
				return OutboundQueue.Resolution.send(target.getOccurrencePrimaryKey().format());
			default:
				return OutboundQueue.Resolution.send(pEntry.recordKey);
		}
	}

	public OutboundQueue.Outcome sendQueued(final Session pSession, final Repository pRepository, final OutboundQueue.Entry pEntry)
	{
		final com.onwbp.adaptation.AdaptationHome home = pRepository.lookupHome(com.orchestranetworks.instance.HomeKey.forBranchName(pEntry.dataspace));
		if (home == null)
		{
			return OutboundQueue.Outcome.drop("dataspace " + pEntry.dataspace + " no longer exists");
		}
		final Adaptation dataset = home.findAdaptationOrNull(com.onwbp.adaptation.AdaptationName.forName(pEntry.dataset));
		if (dataset == null || dataset.getSchemaNode().getNode(com.orchestranetworks.schema.Path.parse(pEntry.tablePath)) == null)
		{
			return OutboundQueue.Outcome.drop("dataset " + pEntry.dataset + " or table " + pEntry.tablePath + " no longer exists in " + pEntry.dataspace);
		}
		final Adaptation record = dataset.getTable(com.orchestranetworks.schema.Path.parse(pEntry.tablePath)).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pEntry.recordKey));
		if (record == null)
		{
			return OutboundQueue.Outcome.drop("the record no longer exists (its creation was rolled back, or it was deleted)");
		}
		final MappingDefinition.Direction direction = MappingDefinition.Direction.valueOf(pEntry.direction);
		final MappingDefinition mapping;
		try
		{
			mapping = this.repository(pRepository).mappingFor(pEntry.dataspace, pEntry.dataset, pEntry.tablePath, pEntry.connector, pEntry.operation, direction);
		}
		catch (final IllegalStateException ambiguous)
		{
			return OutboundQueue.Outcome.drop(ambiguous.getMessage());
		}
		if (mapping == null)
		{
			return OutboundQueue.Outcome.drop("no " + direction + " mapping for " + pEntry.connector + "/" + pEntry.operation + " on " + pEntry.tablePath);
		}
		final SyncResult result = this.syncRecord(pSession, record, mapping, false, "trigger");
		if (result.hasFailure())
		{
			return OutboundQueue.Outcome.retry(result.getMessage() == null ? "the synchronisation failed" : result.getMessage(), mapping.getMaxAttempts(),
				OutboundQueue.triggerRetryDelayMs(mapping.getRetryDelayMinutes()));
		}
		return OutboundQueue.Outcome.sent();
	}

	// ------------------------------------------------------------------ many records

	/**
	 * Synchronises every record of a request, up to {@code pMax}; one procedure per record. When the request
	 * holds more, the results end with a "limit reached" notice ({@link SyncResult#limitNotice}): never a silent cut.
	 */
	public List<SyncResult> syncRecords(final Session pSession, final Request pRequest, final MappingDefinition pMapping,
		final int pMax, final boolean pDryRun, final String pOrigin)
	{
		final List<Adaptation> records = new ArrayList<Adaptation>();
		boolean more = false;
		final RequestResult rows = pRequest.execute();
		try
		{
			Adaptation row = rows.nextAdaptation();
			for (; row != null && records.size() < pMax; row = rows.nextAdaptation())
			{
				records.add(row);
			}
			// a record read and not taken: the request holds more than this run may process
			more = row != null;
		}
		finally
		{
			rows.close();
		}
		final List<SyncResult> results = new ArrayList<SyncResult>();
		// the bookkeeping of the records is written in groups, and ends with the run (audit C-13)
		final RecordRun.Scope scope = RecordRun.open(pSession, records.isEmpty() ? null : records.get(0).getHome().getRepository());
		try
		{
			for (final Adaptation record : records)
			{
				results.add(this.syncRecord(pSession, record, pMapping, pDryRun, pOrigin));
			}
		}
		finally
		{
			scope.close();
		}
		if (more)
		{
			results.add(SyncResult.limitNotice(pMapping, pMapping.getDirection().name(), ConnectorService.currentRunId(), pMax, "table " + pMapping.getTablePath(), false));
		}
		return results;
	}

	// ------------------------------------------------------------------ outbound scopes, deletions, replay

	/** Sends the records of a scope (see {@link OutboundScope}) through an outbound mapping; deletions use the mapping's delete operation. */
	public List<SyncResult> sendScope(final Session pSession, final Repository pRepository, final MappingDefinition pMapping, final AdaptationTable pTable,
		final Request pRequest, final OutboundScope pScope, final int pMax, final boolean pDryRun, final String pOrigin) throws ConnectorException
	{
		return this.sendScope(pSession, pRepository, pMapping, pTable, pRequest, pScope, pMax, pDryRun, pOrigin, null);
	}

	/**
	 * {@link #sendScope(Session, Repository, MappingDefinition, AdaptationTable, Request, OutboundScope, int, boolean, String)}
	 * with {@code pRun} (wave 13, asynchronous send): the results are then counted in the run's {@link ImportOutcome} instead of
	 * being listed - a send of 90,000 records keeps counters and two bounded samples, not 90,000 results - the row of the run is
	 * written at each batch boundary, and a stop requested for the run ends the loop there. The list returned is then empty.
	 */
	private List<SyncResult> sendScope(final Session pSession, final Repository pRepository, final MappingDefinition pMapping, final AdaptationTable pTable,
		final Request pRequest, final OutboundScope pScope, final int pMax, final boolean pDryRun, final String pOrigin, final OutboundRun pRun)
		throws ConnectorException
	{
		if (pMapping.getDirection() != MappingDefinition.Direction.OUTBOUND && pScope != OutboundScope.ALL)
		{
			throw new ConnectorException("Scope " + pScope + " only applies to outbound mappings (mapping " + pMapping.getId() + " is inbound)");
		}
		final ConnectorRepository repo = this.repository(pRepository);
		final OutboundSelector.Selection selection = new OutboundSelector(repo).select(pSession, pMapping, pTable, pRequest, pScope, pMax);
		final List<SyncResult> results = new ArrayList<SyncResult>();
		boolean stopped = false;
		// the mappings are read once and the bookkeeping is written in groups, ending with the run (audit C-13)
		final RecordRun.Scope scope = RecordRun.open(pSession, pRepository);
		try
		{
			for (final Adaptation record : selection.records)
			{
				final SyncResult sent = this.syncRecord(pSession, record, pMapping, pDryRun, pOrigin);
				if (pRun == null)
				{
					results.add(sent);
				}
				else if (pRun.recorded(sent))
				{
					stopped = true;
					break;
				}
			}
			for (final OutboundSelector.Deletion deletion : stopped ? java.util.Collections.<OutboundSelector.Deletion> emptyList() : selection.deletions)
			{
				final SyncResult deleted = this.deleteRemote(pSession, repo, pMapping, deletion, pDryRun, pOrigin);
				if (pRun == null)
				{
					results.add(deleted);
				}
				if (!pDryRun)
				{
					ConnectorService.lineage(pSession, repo, pMapping, deleted);
				}
				if (pRun != null && pRun.recorded(deleted))
				{
					break;
				}
			}
		}
		finally
		{
			scope.close();
		}
		if (pRun != null)
		{
			this.finishSendScope(pRun, selection, pMapping, pScope, pMax);
			return results;
		}
		if (selection.truncated)
		{
			// unsent, changed and failed drain as records are sent: the next run takes the rest; all and dataspaceDelta start again
			final boolean drains = pScope == OutboundScope.UNSENT || pScope == OutboundScope.CHANGED || pScope == OutboundScope.FAILED;
			results.add(SyncResult.limitNotice(pMapping, pMapping.getDirection().name(), ConnectorService.currentRunId(), pMax, "scope " + pScope, drains));
		}
		if (selection.unchanged > 0 || selection.waitingRetry > 0)
		{
			final SyncResult note = new SyncResult(pMapping.getConnectorId(), pMapping.getOperation(), "OUTBOUND", pMapping.getDataspace(), pMapping.getTablePath(), "",
				"scope " + pScope);
			note.setStatus(SyncResult.Status.NO_CHANGE);
			note.setMessage(selection.unchanged + " unchanged since the last send, " + selection.waitingRetry + " failed and waiting for the retry delay");
			note.setRunId(ConnectorService.currentRunId());
			note.setMappingId(String.valueOf(pMapping.getId()));
			results.add(note);
		}
		return results;
	}

	/** What an asynchronous send says once its loop is over: the cut at max (truncated), and the records the scope left out. */
	private void finishSendScope(final OutboundRun pRun, final OutboundSelector.Selection pSelection, final MappingDefinition pMapping, final OutboundScope pScope,
		final int pMax)
	{
		final ImportOutcome outcome = pRun.outcome();
		// a stopped send did not reach the end of what it selected: the cut and the rest are those of a run that went through
		if (pRun.isStopped())
		{
			return;
		}
		if (pSelection.truncated)
		{
			final boolean drains = pScope == OutboundScope.UNSENT || pScope == OutboundScope.CHANGED || pScope == OutboundScope.FAILED;
			final SyncResult notice = SyncResult.limitNotice(pMapping, pMapping.getDirection().name(), ConnectorService.currentRunId(), pMax, "scope " + pScope, drains);
			outcome.addResult(notice);
			outcome.markTruncated(notice.getAvailable(), notice.getMessage());
		}
		OutboundRun.countScopeRest(outcome, pSelection.unchanged, pSelection.waitingRetry);
	}

	/**
	 * Wave 13: {@code connectors_sync(scope=..., async=true)} on an outbound mapping. Like {@link #importFromAsync}: the run id is
	 * allocated here, the mapping is held for it, a slot is taken, the {@code ExecutionRun} row is written (RUNNING, counters at 0) before
	 * this method returns, and the send runs on the same background executor. A refused start leaves no row. The row is rewritten at each
	 * batch boundary of the send and ends DONE / PARTIAL / ERROR / STOPPED ({@code connectors_run_stop}), or INTERRUPTED when a module
	 * restart caught it. Always a real run.
	 *
	 * @param pPredicate XPath predicate narrowing the table (blank: none); the request is built on the worker, with the worker's session
	 */
	public String sendScopeAsync(final Session pSession, final Repository pRepository, final MappingDefinition pMapping, final AdaptationTable pTable,
		final String pPredicate, final OutboundScope pScope, final int pMax, final String pOrigin)
	{
		final String runId = ConnectorService.generateRunId();
		final String holder = ConnectorService.MAPPING_RUNS.tryAcquire(pMapping.getId(), runId);
		if (holder != null)
		{
			throw new MappingAlreadyRunningException(MappingRunLocks.refusal(pMapping.getId(), pMapping.getLabel(), holder));
		}
		if (!ConnectorService.ASYNC_SLOTS.tryReserve())
		{
			ConnectorService.MAPPING_RUNS.release(pMapping.getId(), runId);
			throw new TooManyRunsException(ConnectorService.ASYNC_SLOTS.refusal(ConnectorService.ASYNC_MAX_CONCURRENT_PROPERTY, ConnectorService.ASYNC_MAX_QUEUED_PROPERTY));
		}
		final ConnectorRepository repo = this.repository(pRepository);
		boolean handedOver = false;
		try
		{
			repo.saveRunState(pSession, pMapping, runId, "RUNNING", new ImportOutcome(), 0, null, "connectors:" + runId + ":async-start");
			ConnectorService.ASYNC_RUNS.add(runId);
			ConnectorService.ASYNC_IMPORT_EXECUTOR.get().execute(this.sendWorker(runId, repo, pSession, pRepository, pMapping, pTable, pPredicate, pScope, pMax, pOrigin));
			handedOver = true;
		}
		catch (final java.util.concurrent.RejectedExecutionException stopping)
		{
			repo.saveRunState(pSession, pMapping, runId, AsyncRunGuard.INTERRUPTED, null, -1, "Not started: the connectors module is stopping", "connectors:" + runId + ":async-refused");
			throw new IllegalStateException("The connectors module is stopping: asynchronous send not started", stopping);
		}
		finally
		{
			if (!handedOver)
			{
				ConnectorService.ASYNC_RUNS.remove(runId);
				ConnectorService.ASYNC_SLOTS.release();
				ConnectorService.MAPPING_RUNS.release(pMapping.getId(), runId);
			}
		}
		return runId;
	}

	/** The work of one asynchronous send; gives its slot and the mapping back when it ends, however it ends. */
	private Runnable sendWorker(final String pRunId, final ConnectorRepository pRepo, final Session pSession, final Repository pRepository, final MappingDefinition pMapping,
		final AdaptationTable pTable, final String pPredicate, final OutboundScope pScope, final int pMax, final String pOrigin)
	{
		return new Runnable()
		{
			@Override
			public void run()
			{
				ConnectorService.beginRun(pRunId);
				final Session[] workerSession = new Session[] { pSession };
				try
				{
					final String failure = AsyncRunGuard.run(new AsyncRunGuard.Body()
					{
						@Override
						public void run() throws Exception
						{
							workerSession[0] = ConnectorService.sessionForAsyncWorker(pSession, pRepository);
							ConnectorService.this.runSend(workerSession[0], pRepository, pRepo, pMapping, pTable, pPredicate, pScope, pMax, pOrigin, pRunId);
						}
					}, new AsyncRunGuard.TerminalState()
					{
						@Override
						public void write(final String pStatus, final String pMessage)
						{
							final String message = SampleValueSafety.safeFailure(pMapping, pMessage);
							if (!pRepo.saveRunState(workerSession[0], pMapping, pRunId, pStatus, null, -1, message, "connectors:" + pRunId + ":async-fail"))
							{
								Bookkeeping.rememberLostRunState(pRunId, pStatus, message);
								throw new IllegalStateException("EBX refused the run state (see the kernel log)");
							}
						}
					});
					if (failure != null)
					{
						ConnectorService.log().error("[connectors] async send " + pRunId + " did not complete: " + SampleValueSafety.safeFailure(pMapping, failure));
					}
				}
				finally
				{
					ConnectorService.ASYNC_RUNS.remove(pRunId);
					ConnectorService.ASYNC_SLOTS.release();
					ConnectorService.MAPPING_RUNS.release(pMapping.getId(), pRunId);
					ConnectorService.endRun();
				}
			}
		};
	}

	/** The body of an asynchronous send: the loop of {@link #sendScope} with a run, then the final state of the row. */
	private void runSend(final Session pSession, final Repository pRepository, final ConnectorRepository pRepo, final MappingDefinition pMapping,
		final AdaptationTable pTable, final String pPredicate, final OutboundScope pScope, final int pMax, final String pOrigin, final String pRunId)
	{
		final long start = System.currentTimeMillis();
		final ImportOutcome outcome = new ImportOutcome();
		outcome.setRunId(pRunId);
		final OutboundRun run = new OutboundRun(pSession, pRepo, pMapping, pRunId, outcome);
		String failure = null;
		try
		{
			Request request = null;
			if (pPredicate != null && !pPredicate.trim().isEmpty())
			{
				request = pTable.createRequest();
				request.setSession(pSession);
				request.setXPathFilter(pPredicate.trim());
			}
			this.sendScope(pSession, pRepository, pMapping, pTable, request, pScope, pMax, false, pOrigin, run);
		}
		catch (final Exception ex)
		{
			failure = SampleValueSafety.safeFailure(pMapping, ex.getMessage() == null ? ex.toString() : ex.getMessage());
			final SyncResult header = new SyncResult(pMapping.getConnectorId(), pMapping.getOperation(), "OUTBOUND", pMapping.getDataspace(), pMapping.getTablePath(), "",
				pMapping.getLabel());
			header.setRunId(pRunId);
			header.setMappingId(String.valueOf(pMapping.getId()));
			header.setStatus(SyncResult.Status.ERROR);
			header.setMessage(failure);
			outcome.failRun(header);
			ConnectorService.logFailure("send of run " + pRunId + " (mapping " + pMapping.getId() + ") failed", pMapping, ex);
		}
		finally
		{
			ConnectorService.clearCancellation(pRunId);
		}
		outcome.setDurationMs(System.currentTimeMillis() - start);
		String message = failure != null ? failure : run.isStopped() ? run.stopReason() : outcome.isTruncated() ? outcome.getLimitMessage() : null;
		final String lost = outcome.getBookkeepingNotice();
		if (lost != null && !lost.isEmpty())
		{
			message = message == null || message.isEmpty() ? lost : message + " - " + lost;
		}
		ConnectorService.saveFinalRunState(pSession, pRepo, pMapping, pRunId, OutboundRun.finalStatus(outcome, run.isStopped()), outcome, message);
	}

	/** Calls the mapping's delete operation for a record that disappeared. */
	private SyncResult deleteRemote(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pMapping,
		final OutboundSelector.Deletion pDeletion, final boolean pDryRun, final String pOrigin)
	{
		final long start = System.currentTimeMillis();
		final SyncResult result = new SyncResult(pMapping.getConnectorId(), pMapping.getDeleteOperation(), "OUTBOUND", pMapping.getDataspace(), pMapping.getTablePath(),
			pDeletion.recordKey, pDeletion.recordLabel == null ? pDeletion.recordKey : pDeletion.recordLabel);
		result.setRunId(ConnectorService.currentRunId());
		result.setMappingId(String.valueOf(pMapping.getId()));
		// The outbound state to save once the remote call happened; if it cannot be saved the result and the log say so.
		ConnectorRepository.OutboundMark mark = null;
		try
		{
			final Connector connector = ConnectorRegistry.get().connector(pMapping.getConnectorId(), pRepo);
			final Operation deleteOperation = ConnectorService.operation(connector, pMapping.getDeleteOperation());
			if (deleteOperation == null)
			{
				throw new ConnectorException("Connector " + pMapping.getConnectorId() + " has no delete operation '"
					+ pMapping.getDeleteOperation() + "'");
			}
			MappingCompatibility.requireCompatible(connector, deleteOperation, MappingDefinition.Direction.OUTBOUND);
			MappingSynchronizationPolicy.requireValid(connector, pMapping);
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			inputs.put("key", pDeletion.recordKey);
			if (pDeletion.snapshotRecord != null)
			{
				final TransformationContext tctx = new TransformationContext(pDeletion.snapshotRecord.getContainer(), pDeletion.snapshotRecord, pSession);
				final Map<String, String> bound = new LinkedHashMap<String, String>();
				final Adaptation snapshot = pDeletion.snapshotRecord;
				for (final Map.Entry<String, String> input : pMapping.getInputs().entrySet())
				{
					inputs.put(input.getKey(), ConnectorService.resolveInput(connector, pMapping.getDeleteOperation(), input.getKey(), input.getValue(), bound,
						ConnectorService.placeholderValues(snapshot, tctx), ConnectorService.wholeValues(snapshot, tctx)));
				}
				inputs.putAll(bound);
			}
			if (pDeletion.lastDocument != null && pDeletion.lastDocument.isObject())
			{
				final java.util.Iterator<Map.Entry<String, JsonNode>> fields = pDeletion.lastDocument.fields();
				while (fields.hasNext())
				{
					final Map.Entry<String, JsonNode> e = fields.next();
					if (e.getValue().isValueNode() && !inputs.containsKey(e.getKey()))
					{
						inputs.put(e.getKey(), e.getValue().asText());
					}
				}
			}
			if (pDryRun)
			{
				result.setStatus(SyncResult.Status.DRY_RUN);
				result.setMessage("would call " + pMapping.getDeleteOperation() + " with " + inputs + " (not sent)");
			}
			else
			{
				final ConnectorContext cctx = new ConnectorContext(pSession, pRepo.getRepository(), null, pRepo.dataset());
				connector.execute(cctx, pMapping.getDeleteOperation(), inputs, pDeletion.lastDocument);
				result.setStatus(SyncResult.Status.OK);
				result.setMessage("deleted remotely (" + pMapping.getDeleteOperation() + ")");
				result.getChanges().add(new MappingResult.Change("remote", pDeletion.recordKey, "deleted"));
				mark = new ConnectorRepository.OutboundMark(pMapping, pDeletion.recordKey, pDeletion.recordLabel, "DELETED", null, null, null, result.getRunId());
			}
		}
		catch (final Exception ex)
		{
			result.setStatus(SyncResult.Status.ERROR);
			result.setMessage(SampleValueSafety.safeFailure(pMapping, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			if (!pDryRun)
			{
				mark = new ConnectorRepository.OutboundMark(pMapping, pDeletion.recordKey, pDeletion.recordLabel, "FAILED", null, null, result.getMessage(), result.getRunId());
			}
		}
		result.setDurationMs(System.currentTimeMillis() - start);
		if (!pDryRun)
		{
			final RecordRun run = RecordRun.forSession(pSession);
			ConnectorService.bookLog(run, pSession, pRepo, result, pOrigin);
			if (mark != null)
			{
				ConnectorService.bookOutbound(run, pSession, pRepo, pOrigin, result, mark);
			}
		}
		return result;
	}

	/** Replays the failed calls of a previous run (inbound or outbound): the records are looked up again and their mapping re-run. */
	public List<SyncResult> replay(final Session pSession, final Repository pRepository, final String pRunId, final boolean pDryRun, final String pOrigin)
		throws ConnectorException
	{
		final ConnectorRepository repo = this.repository(pRepository);
		final List<SyncResult> results = new ArrayList<SyncResult>();
		final java.util.Set<String> done = new java.util.HashSet<String>();
		// the mappings are read once and the bookkeeping written in groups, ending with the replay (audit C-13)
		final RecordRun.Scope scope = RecordRun.open(pSession, pRepository);
		try
		{
		for (final Adaptation row : repo.logOfRun(pRunId))
		{
			if (!"ERROR".equals(row.getString(ConnectorsPaths.SyncLog.STATUS)))
			{
				continue;
			}
			final String mappingId = row.getString(ConnectorsPaths.SyncLog.MAPPING);
			final String key = row.getString(ConnectorsPaths.SyncLog.RECORD_KEY);
			if (mappingId == null || key == null || key.isEmpty() || !done.add(mappingId + "|" + key))
			{
				continue;
			}
			// the mapping of the row once it is known, so that the failure text of a sample mapping can be withheld
			MappingDefinition replayed = null;
			try
			{
			final RecordRun replayRun = RecordRun.forSession(pSession);
			final MappingDefinition mapping = replayRun != null ? replayRun.mapping(Integer.parseInt(mappingId)) : repo.mapping(Integer.parseInt(mappingId));
			replayed = mapping;
			if (mapping == null)
			{
				final SyncResult missing = new SyncResult("", "", "", row.getString(ConnectorsPaths.SyncLog.DATASPACE),
					"", key, row.getString(ConnectorsPaths.SyncLog.RECORD_LABEL));
				missing.setStatus(SyncResult.Status.SKIPPED);
				missing.setMessage("mapping " + mappingId + " no longer exists");
				missing.setMappingId(mappingId);
				missing.setRunId(pRunId);
				results.add(missing);
				continue;
			}
			final Adaptation dataset = com.on.ebx.connect.ebx.DatasetLocator.find(pRepository, row.getString(ConnectorsPaths.SyncLog.DATASPACE), mapping.getDataset(),
				mapping.getTablePath());
			final AdaptationTable table = dataset == null ? null : dataset.getTable(Path.parse(mapping.getTablePath()));
			final Adaptation record = table == null ? null : table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(key));
			if (record != null)
			{
				results.add(this.syncRecord(pSession, record, mapping, pDryRun, pOrigin));
			}
			else if (mapping.getDirection() == MappingDefinition.Direction.OUTBOUND && !mapping.getDeleteOperation().isEmpty())
			{
				final Adaptation state = repo.outboundState(mapping.getId(), key);
				JsonNode last = null;
				try
				{
					last = state == null || state.getString(ConnectorsPaths.OutboundState.LAST_DOCUMENT) == null ? null
							: Json.parse(state.getString(ConnectorsPaths.OutboundState.LAST_DOCUMENT));
				}
				catch (final java.io.IOException ex)
				{
					last = null;
				}
				results.add(this.deleteRemote(pSession, repo, mapping, new OutboundSelector.Deletion(key, row.getString(ConnectorsPaths.SyncLog.RECORD_LABEL), last, null),
					pDryRun, pOrigin));
			}
			else
			{
				final SyncResult gone = new SyncResult(mapping.getConnectorId(), mapping.getOperation(), mapping.getDirection().name(), mapping.getDataspace(),
					mapping.getTablePath(), key, row.getString(ConnectorsPaths.SyncLog.RECORD_LABEL));
				gone.setStatus(SyncResult.Status.SKIPPED);
				gone.setMessage("record no longer exists");
				results.add(gone);
			}
			}
			catch (final RuntimeException ex)
			{
				final SyncResult failed = new SyncResult("", "", "", row.getString(ConnectorsPaths.SyncLog.DATASPACE),
					"", key, row.getString(ConnectorsPaths.SyncLog.RECORD_LABEL));
				failed.setStatus(SyncResult.Status.ERROR);
				failed.setMappingId(mappingId);
				failed.setRunId(pRunId);
				// the redacted message of the cause says what to fix; the stack stays in the log
				ConnectorService.logFailure("replay of run " + pRunId + " failed on a record of mapping " + mappingId, replayed, ex);
				failed.setMessage(SampleValueSafety.safeFailure(replayed, "Replay of this record failed: " + Redaction.failure(ex)));
				results.add(failed);
			}
		}
		}
		finally
		{
			scope.close();
		}
		return results;
	}

	/**
	 * Writes a failure to the kernel log: the redacted message and, below it, the redacted stack trace, which is
	 * where the cause is read while the user is shown the message alone. A mapping on ephemeral samples gets the
	 * message of {@link SampleValueSafety#safeFailure} and no stack, since an exception may quote the sample.
	 */
	public static void logFailure(final String pWhat, final MappingDefinition pMapping, final Throwable pFailure)
	{
		final String message = SampleValueSafety.safeFailure(pMapping, Redaction.failure(pFailure));
		ConnectorService.log().error("[connectors] " + pWhat + ": " + message
			+ (SampleValueSafety.containsSampleMapping(pMapping) ? "" : "\n" + Redaction.stack(pFailure)));
	}

	// ------------------------------------------------------------------ internals

	private static final class Prepared
	{
		Connector connector;
		Map<String, String> inputs;
		JsonNode document;
		JsonNode payload;
		MappingDefinition responseMapping;
	}

	private SyncResult newResult(final Adaptation pRecord, final String pConnectorId, final String pOperation,
		final MappingDefinition.Direction pDirection)
	{
		return new SyncResult(pConnectorId, pOperation, pDirection.name(), pRecord.getHome().getKey().getName(),
			pRecord.getContainerTable().getTablePath().format(), pRecord.getOccurrencePrimaryKey().format(),
			pRecord.getLabel(java.util.Locale.getDefault()));
	}

	/** Resolves inputs, calls the connector; null when the record cannot be synchronised. */
	private Prepared prepare(final Session pSession, final Adaptation pRecord, final MappingDefinition pMapping,
		final SyncResult pResult) throws ConnectorException
	{
		return this.prepare(pSession, pRecord, pMapping, pResult, false);
	}

	private Prepared prepare(final Session pSession, final Adaptation pRecord, final MappingDefinition pMapping,
		final SyncResult pResult, final boolean pDryRun) throws ConnectorException
	{
		final ConnectorRepository repo = this.repository(pRecord.getHome().getRepository());
		final RecordRun run = RecordRun.forSession(pSession);
		final Connector connector = run == null ? ConnectorRegistry.get().connector(pMapping.getConnectorId(), repo)
			: ConnectorRegistry.get().connector(pMapping.getConnectorId(), new ConnectorRegistry.ConfigSource()
			{
				@Override
				public ConnectorConfig connector(final String pConnectorId)
				{
					// the configuration is read once for the run, as a feed does (audit C-13, C-34)
					return run.connectorConfig(pConnectorId);
				}
			});
		final Operation operation = ConnectorService.operation(connector, pMapping.getOperation());
		if (operation == null)
		{
			throw new ConnectorException("Connector " + pMapping.getConnectorId() + " has no operation '"
					+ pMapping.getOperation() + "'");
		}
		MappingCompatibility.requireCompatible(connector, operation, pMapping.getDirection());
		MappingSynchronizationPolicy.requireValid(connector, pMapping);
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		final TransformationContext inputContext = new TransformationContext(pRecord.getContainer(), pRecord, pSession);
		final Map<String, String> bound = new LinkedHashMap<String, String>();
		for (final Map.Entry<String, String> input : pMapping.getInputs().entrySet())
		{
			inputs.put(input.getKey(), ConnectorService.resolveInput(connector, pMapping.getOperation(), input.getKey(), input.getValue(), bound,
				ConnectorService.placeholderValues(pRecord, inputContext), ConnectorService.wholeValues(pRecord, inputContext)));
		}
		inputs.putAll(bound);
		for (final String required : operation.getRequiredInputs())
		{
			if (Expressions.isBlank(inputs.get(required)))
			{
				pResult.setStatus(SyncResult.Status.SKIPPED);
				pResult.setMessage("Input '" + required + "' is empty on this record");
				return null;
			}
		}
		final Prepared prepared = new Prepared();
		prepared.connector = connector;
		prepared.inputs = inputs;
		final Adaptation dataset = pRecord.getContainer();
		final TransformationContext tctx = new TransformationContext(dataset, pRecord, pSession);
		final ConnectorContext cctx = new ConnectorContext(pSession, repo.getRepository(), null, dataset);
		if (pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND)
		{
			final MappingResult built = new MappingResult();
			prepared.payload = MappingEngine.buildOutbound(pMapping, pRecord, tctx, built);
			pResult.getErrors().addAll(built.getErrors());
			pResult.setOutboundDocument(Json.write(prepared.payload));
			if (pDryRun)
			{
				// an outbound simulation never calls the service: it shows what would be sent
				pResult.setStatus(SyncResult.Status.DRY_RUN);
				final String doc = Json.write(prepared.payload);
				pResult.setMessage("would send to " + pMapping.getConnectorId() + "/" + pMapping.getOperation() + " with inputs " + inputs + " (not sent)");
				pResult.getChanges().add(new MappingResult.Change("document", null, doc.length() > 500 ? doc.substring(0, 500) + "..." : doc));
				return null;
			}
			prepared.document = connector.execute(cctx, pMapping.getOperation(), inputs, prepared.payload);
			try
			{
				// the mappings are read once for a run: this was a read of three tables for every record sent (audit C-13)
				prepared.responseMapping = run != null
					? run.mappingFor(pRecord.getHome().getKey().getName(), dataset.getAdaptationName().getStringName(), pMapping.getTablePath(), pMapping.getConnectorId(),
						pMapping.getOperation(), MappingDefinition.Direction.INBOUND)
					: repo.mappingFor(pRecord.getHome().getKey().getName(),
						dataset.getAdaptationName().getStringName(), pMapping.getTablePath(), pMapping.getConnectorId(),
						pMapping.getOperation(), MappingDefinition.Direction.INBOUND);
			}
			catch (final IllegalStateException ambiguous)
			{
				prepared.responseMapping = null; // several inbound twins: none applied, the answer is only logged
				ConnectorService.log().warn("[connectors] " + ambiguous.getMessage());
			}
		}
		else
		{
			// Kafka consume commits offsets by design. A record-level simulation (including
			// the Mapping Designer's "Test on the sample" action) must be side-effect free,
			// so it uses the connector's dedicated read-only peek operation instead.
			final String executionOperation = pDryRun && "Kafka".equals(pMapping.getConnectorId())
					&& "consume".equals(pMapping.getOperation()) ? "peek" : pMapping.getOperation();
            if(pDryRun && "catalog".equals(executionOperation)) inputs.put("extractSensitivityLabels","false");
			prepared.document = connector.execute(cctx, executionOperation, inputs, null);
			if (prepared.document == null)
			{
				pResult.setStatus(SyncResult.Status.NO_DATA);
				pResult.setMessage("No data returned for " + inputs);
				return null;
			}
			prepared.responseMapping = pMapping;
		}
		return prepared;
	}

	/**
	 * The connector's configuration for a run: read once and kept by the run's cache (audit C-34), or read now when there is
	 * no run cache (one record synchronised on its own).
	 */
	static ConnectorConfig runConnectorConfig(final TransformationRunCache pRunCache, final ConnectorRepository pRepo, final String pConnectorId)
	{
		if (pRunCache == null)
		{
			return pRepo.connector(pConnectorId);
		}
		ConnectorConfig config = pRunCache.connectorConfig(pConnectorId);
		if (config == null)
		{
			config = pRepo.connector(pConnectorId); // a read that fails is not remembered: the next call asks again
			pRunCache.rememberConnectorConfig(pConnectorId, config);
		}
		return config;
	}

	/** Gives a transformation context the source's trust and the provenance oracle. */
	private static void trustify(final TransformationContext pContext, final ConnectorRepository pRepo, final MappingDefinition pMapping)
	{
		int trust = 50;
		try
		{
			final RecordRun run = RecordRun.current();
			final ConnectorConfig config = ConnectorService.runConnectorConfig(pContext.getCache() != null || run == null ? pContext.getCache() : run.cache, pRepo,
				pMapping.getConnectorId());
			trust = config == null ? 50 : config.getTrust();
		}
		catch (final RuntimeException ex)
		{
			// unknown connector: neutral trust
		}
		pContext.setTrust(pRepo, trust, pMapping.getConnectorId());
	}

	private void apply(final Session pSession, final Adaptation pRecord, final MappingDefinition pMapping,
		final Prepared pPrepared, final SyncResult pResult, final boolean pDryRun) throws Exception
	{
		final ProgrammaticService service = ProgrammaticService.createForSession(pSession, pRecord.getHome());
		service.setSessionTrackingInfo("Connector " + pMapping.getConnectorId() + "/" + pMapping.getOperation());
		final Exception[] failure = new Exception[1];
		final ProcedureResult procedureResult = service.execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				try
				{
					ConnectorService.this.applyIn(pContext, pRecord, pMapping, pPrepared, pResult, pDryRun);
				}
				catch (final Exception ex)
				{
					failure[0] = ex;
					throw ex;
				}
			}
		});
		if (procedureResult.hasFailed())
		{
			throw failure[0] != null ? failure[0]
					: new ConnectorException(String.valueOf(procedureResult.getExceptionFullMessage(java.util.Locale.getDefault())));
		}
	}

	private void applyIn(final ProcedureContext pContext, final Adaptation pRecord, final MappingDefinition pMapping,
		final Prepared pPrepared, final SyncResult pResult, final boolean pDryRun) throws Exception
	{
		if (pPrepared.responseMapping == null || pPrepared.document == null)
		{
			pResult.setStatus(pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND ? SyncResult.Status.OK
					: SyncResult.Status.NO_DATA);
			pResult.setMessage(pMapping.getDirection() == MappingDefinition.Direction.OUTBOUND
					? "Sent " + pResult.getErrors().size() + " error(s)" : "No document");
			return;
		}
		final TransformationContext tctx = new TransformationContext(pRecord.getContainer(), pRecord, pContext.getSession());
		ConnectorService.trustify(tctx, this.repository(pRecord.getHome().getRepository()), pPrepared.responseMapping);
		final ValueContextForUpdate update = pContext.getContext(pRecord.getAdaptationName());
		final MappingResult applied = MappingEngine.applyInbound(pPrepared.responseMapping, pPrepared.document, pRecord,
			update, tctx);
		pResult.absorb(applied);
		if (pDryRun)
		{
			// sub-objects are simulated on the values only: no child row is touched
			final int before = pResult.getChanges().size();
			ConnectorService.simulateChildren(pPrepared.responseMapping, pPrepared.document, tctx, pResult);
			final boolean any = applied.hasChanges() || pResult.getChanges().size() > before;
			pResult.setStatus(any ? SyncResult.Status.DRY_RUN : SyncResult.Status.NO_CHANGE);
			pResult.setMessage(applied.summary() + (any ? " (not written)" : ""));
			return;
		}
		if (applied.hasChanges())
		{
			pContext.doModifyContent(pRecord, update);
		}
		final int before = pResult.getChanges().size();
		MappingEngine.applyChildren(pContext, pPrepared.responseMapping, pPrepared.document, pRecord, tctx, pResult);
		final boolean any = applied.hasChanges() || pResult.getChanges().size() > before;
		pResult.setStatus(any ? SyncResult.Status.OK : SyncResult.Status.NO_CHANGE);
		pResult.setMessage(applied.summary() + (pResult.getChanges().size() > before ? ", " + (pResult.getChanges().size() - before) + " sub-object change(s)" : ""));
	}

	/** Dry run of the sub-objects: lists what each collection element would write, without any lookup. */
	private static void simulateChildren(final MappingDefinition pMapping, final JsonNode pDocument, final TransformationContext pContext,
		final SyncResult pResult)
	{
		final JsonNode root = pMapping.getResponseRoot().isEmpty() ? pDocument : Json.at(pDocument, pMapping.getResponseRoot());
		for (final MappingDefinition child : pMapping.getChildren())
		{
			final JsonNode array = Json.at(root, child.getCollectionPath());
			if (array == null)
			{
				continue;
			}
			int index = 0;
			for (final JsonNode element : array.isArray() ? array : java.util.Collections.singletonList(array))
			{
				final MappingEngine.Values values = MappingEngine.compute(child, element, pContext);
				for (final Map.Entry<String, String> e : values.getByPath().entrySet())
				{
					pResult.getChanges().add(new MappingResult.Change(child.getCollectionPath() + "[" + index + "] " + e.getKey(), null, e.getValue()));
				}
				pResult.getErrors().addAll(values.getErrors());
				ConnectorService.simulateChildren(child, element, pContext, pResult);
				index++;
			}
		}
	}

	// ------------------------------------------------------------------ feeds (import)

	/**
	 * Run-time inputs of a feed given as text — {@code name=value} pairs separated by {@code ;} or new
	 * lines ({@code tables=SALES.ORDERS,SALES.CUSTOMER}) — kept only when an input expression of the
	 * mapping reads them as {@code ${param.name}}: a run-time input refines what the mapping allows, it
	 * never adds an input the mapping does not use. The names dropped are added to {@code pIgnored}
	 * (when not null). Null when nothing remains.
	 */
	public static Map<String, String> runtimeInputs(final MappingDefinition pFeed, final String pText, final List<String> pIgnored)
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return null;
		}
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final String part : pText.split("[;\\r\\n]+"))
		{
			final int eq = part.indexOf('=');
			final String name = (eq < 0 ? part : part.substring(0, eq)).trim();
			if (name.isEmpty())
			{
				continue;
			}
			if (eq < 0 || !ConnectorService.readsParam(pFeed, name))
			{
				if (pIgnored != null) pIgnored.add(name);
				continue;
			}
			out.put(name, part.substring(eq + 1).trim());
		}
		return out.isEmpty() ? null : out;
	}

	/**
	 * The names of run-time inputs given as text ({@code name=value;...}), for a run that reads none of them (a
	 * run on records or on an outbound scope): they are all ignored, and the run must say so (audit C-35).
	 */
	public static List<String> inputNames(final String pText)
	{
		final List<String> out = new ArrayList<String>();
		if (pText == null || pText.trim().isEmpty())
		{
			return out;
		}
		for (final String part : pText.split("[;\\r\\n]+"))
		{
			final int eq = part.indexOf('=');
			final String name = (eq < 0 ? part : part.substring(0, eq)).trim();
			if (!name.isEmpty())
			{
				out.add(name);
			}
		}
		return out;
	}

	static boolean readsParam(final MappingDefinition pFeed, final String pName)
	{
		for (final String expression : pFeed.getInputs().values())
		{
			if (expression != null && (expression.contains("${param." + pName + "}") || expression.contains("${param." + pName + "|")))
			{
				return true;
			}
		}
		return false;
	}

	/** {@code -Debx.connectors.import.async.maxConcurrent}: asynchronous imports running at once. Default 4. */
	static final String ASYNC_MAX_CONCURRENT_PROPERTY = "ebx.connectors.import.async.maxConcurrent";
	/** {@code -Debx.connectors.import.async.maxQueued}: asynchronous imports accepted beyond that, waiting for a thread. Default 16. */
	static final String ASYNC_MAX_QUEUED_PROPERTY = "ebx.connectors.import.async.maxQueued";

	/**
	 * How many asynchronous imports may run and wait. Every import holds a thread, a source connection
	 * and EBX transactions for as long as the source keeps producing rows: letting each caller add one
	 * without limit ends with as many threads as callers. Beyond the limit a start is refused at once
	 * ({@link TooManyRunsException}), before anything is written for it.
	 */
	private static final RunSlots ASYNC_SLOTS = new RunSlots(
		BackgroundWork.intProperty(ConnectorService.ASYNC_MAX_CONCURRENT_PROPERTY, 4, 1),
		BackgroundWork.intProperty(ConnectorService.ASYNC_MAX_QUEUED_PROPERTY, 16, 0));

	/**
	 * The threads of {@link #importFromAsync}: as many as {@link #ASYNC_SLOTS} lets run, idle ones
	 * released after a minute. Its queue needs no bound of its own, since nothing enters it without a
	 * slot. Stopped with the module ({@link BackgroundWork}).
	 */
	private static final BackgroundWork.Managed<java.util.concurrent.ThreadPoolExecutor> ASYNC_IMPORT_EXECUTOR = BackgroundWork.managed("asynchronous imports",
		new BackgroundWork.Factory<java.util.concurrent.ThreadPoolExecutor>()
		{
			@Override
			public java.util.concurrent.ThreadPoolExecutor create()
			{
				final int threads = ConnectorService.ASYNC_SLOTS.getMaxConcurrent();
				final java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(threads, threads, 60L, java.util.concurrent.TimeUnit.SECONDS,
					new java.util.concurrent.LinkedBlockingQueue<Runnable>(), BackgroundWork.daemonThreads("ebx-connector-import-async", true));
				pool.allowCoreThreadTimeOut(true);
				return pool;
			}
		});

	/**
	 * The admission of asynchronous imports as {@code {inUse, maxConcurrent, maxQueued}}: {@code inUse} counts the
	 * runs that are running or waiting for a thread, so what is waiting is {@code inUse - maxConcurrent} when positive.
	 */
	public static int[] asyncSlots()
	{
		return new int[] { ConnectorService.ASYNC_SLOTS.inUse(), ConnectorService.ASYNC_SLOTS.getMaxConcurrent(), ConnectorService.ASYNC_SLOTS.getMaxQueued() };
	}

	/** The run that holds each mapping: a second real run of one mapping is refused while it is held (audit C-20). */
	private static final MappingRunLocks MAPPING_RUNS = new MappingRunLocks();

	/** The run that holds a mapping now (a screen attaches its panel to it), null when none is running on this node (audit C-19). */
	public static String runningRun(final int pMappingId)
	{
		return ConnectorService.MAPPING_RUNS.holder(pMappingId);
	}

	/** Run ids of the asynchronous imports accepted and not yet ended, so that a stop of the module can ask each of them to stop. */
	private static final java.util.Set<String> ASYNC_RUNS =
		java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

	/**
	 * Module stop: asks every asynchronous import still running to stop at its next batch boundary,
	 * where it writes its own final state and resume position. {@link BackgroundWork#stop} then
	 * interrupts the threads and waits for them; an import that could not end in that time is marked
	 * {@code INTERRUPTED} at the next start.
	 */
	public static void requestStopOfAsyncImports()
	{
		for (final String runId : ConnectorService.ASYNC_RUNS)
		{
			ConnectorService.requestCancel(runId);
		}
	}

	/**
	 * Module stop, once the executors are stopped: imports that were still waiting for a thread were
	 * dropped with the queue and will never give their slot back themselves.
	 */
	public static void forgetAsyncImports()
	{
		for (final String runId : ConnectorService.ASYNC_RUNS)
		{
			ConnectorService.clearCancellation(runId);
		}
		ConnectorService.ASYNC_RUNS.clear();
		ConnectorService.ASYNC_SLOTS.clear();
		ConnectorService.MAPPING_RUNS.clear();
	}

	/**
	 * Imports the items a connector operation returns (Kafka messages, a SOQL query, a REST list)
	 * into the table of a feed mapping: each item locates its record through the key predicate,
	 * updates it or creates it, then its sub-objects. Volume lot 7a: read through
	 * {@link Connector#readRows}, which streams items to the batching sink instead of building one
	 * {@code JsonNode} array of the whole source first - see {@link #runImport}.
	 */
	public ImportOutcome importFrom(final Session pSession, final Repository pRepository, final MappingDefinition pFeed,
		final Map<String, String> pExtraInputs, final int pMax, final boolean pDryRun, final String pOrigin)
	{
		return this.importFrom(pSession, pRepository, pFeed, pExtraInputs, pMax, pDryRun, pOrigin, 0);
	}

	/**
	 * Volume lot 6, task 5: like {@link #importFrom(Session, Repository, MappingDefinition, Map, int,
	 * boolean, String)}, restarting at document item index {@code pResumeFrom} (0-based, items before
	 * it are neither looked up nor written) instead of from the start. Only meaningful when the
	 * underlying feed call returns items in a stable order across calls; for a source that returns a
	 * fixed document (a file, most REST list calls) this is already true, and JdbcConnector's own
	 * {@code query} operation orders by whatever the SQL itself specifies. A negative or zero value
	 * behaves exactly like the original overload.
	 */
	public ImportOutcome importFrom(final Session pSession, final Repository pRepository, final MappingDefinition pFeed,
		final Map<String, String> pExtraInputs, final int pMax, final boolean pDryRun, final String pOrigin, final int pResumeFrom)
	{
		return this.importFrom(pSession, pRepository, pFeed, pExtraInputs, pMax, pDryRun, pOrigin, pResumeFrom, false);
	}

	/**
	 * Volume lot V2-D: like {@link #importFrom(Session, Repository, MappingDefinition, Map, int, boolean,
	 * String, int)}, with {@code pFullRefresh}: for an incremental feed (a mapping with {@code loadMode}
	 * INCREMENTAL and a row watermark - {@link MappingDefinition#isWatermarkFeed}) ignore the stored
	 * checkpoint for this run and reset it to what the source holds at the end. Meaningless, and ignored,
	 * for any other mapping.
	 */
	public ImportOutcome importFrom(final Session pSession, final Repository pRepository, final MappingDefinition pFeed,
		final Map<String, String> pExtraInputs, final int pMax, final boolean pDryRun, final String pOrigin, final int pResumeFrom,
		final boolean pFullRefresh)
	{
		if (pDryRun)
		{
			// writes nothing, so it may run beside a real run
			return this.importFromLocked(pSession, pRepository, pFeed, pExtraInputs, pMax, true, pOrigin, pResumeFrom, pFullRefresh);
		}
		final String runId = ConnectorService.currentRunId();
		final String holder = ConnectorService.MAPPING_RUNS.tryAcquire(pFeed.getId(), runId);
		if (holder != null)
		{
			// Refused before anything is read, looked up or written, and before any run row exists: the run that
			// holds the mapping keeps its own state. The caller sees an error outcome, whatever its entry point.
			final ImportOutcome refused = new ImportOutcome();
			refused.setRunId(runId);
			final SyncResult header = new SyncResult(pFeed.getConnectorId(), pFeed.getOperation(), "IMPORT", pFeed.getDataspace(), pFeed.getTablePath(), "", pFeed.getLabel());
			header.setRunId(runId);
			header.setMappingId(String.valueOf(pFeed.getId()));
			header.setStatus(SyncResult.Status.ERROR);
			header.setMessage(MappingRunLocks.refusal(pFeed.getId(), pFeed.getLabel(), holder));
			// counted as the run's failure, so that the answer reads ERROR (REST 422) and not "no data"
			refused.failRun(header);
			return refused;
		}
		try
		{
			return this.importFromLocked(pSession, pRepository, pFeed, pExtraInputs, pMax, false, pOrigin, pResumeFrom, pFullRefresh);
		}
		finally
		{
			ConnectorService.MAPPING_RUNS.release(pFeed.getId(), runId);
		}
	}

	/** {@link #importFrom(Session, Repository, MappingDefinition, Map, int, boolean, String, int, boolean)} for a caller that already holds the mapping (or runs a simulation). */
	private ImportOutcome importFromLocked(final Session pSession, final Repository pRepository, final MappingDefinition pFeed,
		final Map<String, String> pExtraInputs, final int pMax, final boolean pDryRun, final String pOrigin, final int pResumeFrom,
		final boolean pFullRefresh)
	{
		final ConnectorRepository repo = this.repository(pRepository);
		final long start = System.currentTimeMillis();
		final com.on.ebx.connect.ebx.DatasetLocator.Location location = com.on.ebx.connect.ebx.DatasetLocator.locate(repo.getRepository(), pFeed.getDataspace(),
			pFeed.getDataset(), pFeed.getTablePath());
		final Adaptation dataset = location.getDataset();
		final SyncResult header = new SyncResult(pFeed.getConnectorId(), pFeed.getOperation(), "IMPORT",
			dataset == null ? pFeed.getDataspace() : dataset.getHome().getKey().getName(), pFeed.getTablePath(), "", pFeed.getLabel());
		final String runId = ConnectorService.currentRunId();
		header.setRunId(runId);
		header.setMappingId(String.valueOf(pFeed.getId()));
		if (blockedCatalogScan(pSession, repo, pFeed, pDryRun, pOrigin, header))
		{
			final ImportOutcome outcome = new ImportOutcome();
			outcome.setRunId(runId);
			outcome.failRun(header);
			ConnectorService.saveFailedRun(pSession, repo, pFeed, runId, outcome, pDryRun);
			return outcome;
		}
		try
		{
			if (dataset == null)
			{
				throw new ConnectorException(location.failure(pFeed.getDataspace(), pFeed.getDataset(), pFeed.getTablePath()));
			}
			final Connector connector = ConnectorRegistry.get().connector(pFeed.getConnectorId(), repo);
			final Operation operation = ConnectorService.operation(connector, pFeed.getOperation());
			if (operation == null)
			{
				throw new ConnectorException("Connector " + pFeed.getConnectorId() + " has no operation '"
					+ pFeed.getOperation() + "'");
			}
			MappingCompatibility.requireCompatible(connector, operation, pFeed.getDirection());
			MappingSynchronizationPolicy.requireValid(connector, pFeed);
			if (connector instanceof com.on.ebx.connect.connectors.KafkaConnector && "cdc".equals(pFeed.getOperation()))
			{
				// preview function, closed by default: change data capture does nothing until the word cdc is in -Debx.connectors.preview
				// (the Kafka consume feed of the same connector is older and is not asked for it)
				PreviewFeatures.requireOperation("kafka", "cdc");
				// Gartner lot B: Debezium change events, applied then checkpointed per mapping (never a broker commit)
				return this.runCdc(pSession, repo, pFeed, dataset, (com.on.ebx.connect.connectors.KafkaConnector) connector, pExtraInputs,
					pDryRun, pOrigin, header, start, runId);
			}
			// Volume lot V2-D: an incremental feed reads from its checkpoint - see WatermarkRun.
			final WatermarkRun watermark = WatermarkRun.plan(pFeed, pFeed.isWatermarkFeed() ? ConnectorService.storedCheckpoint(repo, pFeed) : null, pFullRefresh);
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			final Map<String, String> bound = new LinkedHashMap<String, String>();
			final ValueTemplates.Values feedPlaceholder = new ValueTemplates.Values()
			{
				@Override
				public String of(final String pExpression)
				{
					return Expressions.resolve("${" + pExpression + "}", null, pExtraInputs);
				}
			};
			final ValueTemplates.Values feedWhole = new ValueTemplates.Values()
			{
				@Override
				public String of(final String pTemplate)
				{
					return Expressions.resolve(pTemplate, null, pExtraInputs);
				}
			};
			for (final Map.Entry<String, String> input : pFeed.getInputs().entrySet())
			{
				final String template = input.getValue();
				final boolean sqlInput = connector.bindsWatermark(pFeed.getOperation(), input.getKey());
				if (watermark != null && WatermarkRun.references(template))
				{
					inputs.put(input.getKey(), watermark.resolveInput(template, sqlInput,
						new WatermarkRun.Resolver()
						{
							@Override
							public String resolve(final String pTemplate)
							{
								// the watermark placeholders are already protected: what is left is bound like any other
								return sqlInput ? ValueTemplates.sql(pTemplate, feedPlaceholder, bound) : feedWhole.of(pTemplate);
							}
						}));
				}
				else
				{
					inputs.put(input.getKey(), ConnectorService.resolveInput(connector, pFeed.getOperation(), input.getKey(), template, bound, feedPlaceholder, feedWhole));
				}
			}
			inputs.putAll(bound);
			if (pExtraInputs != null)
			{
				for (final Map.Entry<String, String> extra : pExtraInputs.entrySet())
				{
					if (!inputs.containsKey(extra.getKey()) || inputs.get(extra.getKey()).isEmpty())
					{
						inputs.put(extra.getKey(), extra.getValue());
					}
				}
			}
			// audit C-15: a simulated profile feed must not run the profile statements against the warehouse;
			// the profile operation then answers its plan (statements, estimated work) instead of the figures
			if (pDryRun && "profile".equals(pFeed.getOperation()))
			{
				inputs.put("dryRun", "true");
			}
			if (watermark != null)
			{
				watermark.completeInputs(inputs, pFeed.getCheckpointInput());
			}
			if (!inputs.containsKey("max"))
			{
				inputs.put("max", String.valueOf(pMax));
			}
			final ConnectorContext cctx = new ConnectorContext(pSession, repo.getRepository(), null, dataset);
			// A Kafka consume commits offsets by design. A feed simulation must remain side-effect free,
			// so it uses Kafka's dedicated read-only peek operation instead.
			final String executionOperation = pDryRun && "Kafka".equals(pFeed.getConnectorId()) && "consume".equals(pFeed.getOperation())
				? "peek" : pFeed.getOperation();
			if(pDryRun && "catalog".equals(executionOperation)) inputs.put("extractSensitivityLabels","false");
			final RowSource source = new RowSource()
			{
				@Override
				public Connector.RowStreamResult stream(final int pStreamMax, final Connector.RowSink pSink) throws ConnectorException
				{
					return connector.readRows(cctx, executionOperation, inputs, pFeed.getItemsPath(), pStreamMax, pSink);
				}
			};
			final ImportOutcome outcome = this.runImport(pSession, repo, pFeed, dataset, source, pMax, pDryRun, pOrigin, header, start,
				"No item returned by " + pFeed.getConnectorId() + "/" + pFeed.getOperation() + " " + inputs, Math.max(0, pResumeFrom), runId, watermark);
			finishCatalogImport(pSession, repo, pFeed, outcome.getSourceDocument(), outcome, pDryRun, pOrigin);
			return outcome;
		}
		catch (final Exception ex)
		{
			header.setStatus(SyncResult.Status.ERROR);
			header.setMessage(SampleValueSafety.safeFailure(pFeed, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			header.setDurationMs(System.currentTimeMillis() - start);
			final ImportOutcome outcome = new ImportOutcome();
			outcome.setRunId(runId);
			outcome.failRun(header);
			if (!pDryRun)
			{
				ConnectorService.logInto(outcome, repo, pSession, header, pOrigin);
				finishCatalogImport(pSession, repo, pFeed, null, outcome, false, pOrigin);
			}
			ConnectorService.saveFailedRun(pSession, repo, pFeed, runId, outcome, pDryRun);
			return outcome;
		}
	}

	/**
	 * Volume lot 7a, task 3: like {@link #importFrom(Session, Repository, MappingDefinition, Map, int,
	 * boolean, String, int)}, started on a background thread and returning as soon as the run is
	 * registered (never waits for it to finish). Always a real, non-dry run.
	 * <p>
	 * <b>Session lifetime (volume lot 7b, real and automatic now - see {@link #sessionForAsyncWorker}):
	 * </b> the background thread no longer blindly reuses {@code pSession} for the run itself - it
	 * mints a fresh {@link Session} for the same user once the worker actually starts (cheap, and done
	 * on the worker thread, not here), falling back to {@code pSession} unchanged when minting is not
	 * possible for any reason. The initial, SYNCHRONOUS {@code saveRunState} below still uses
	 * {@code pSession} as-is - it runs on the caller's own thread, inside the same request/call that
	 * handed it over, so there is nothing to protect there.
	 *
	 * @return the run id immediately - {@code connectors_run_get(runId)} reports {@code RUNNING} at
	 *         once (an {@code ExecutionRun} row is written synchronously, before this method returns,
	 *         so a caller polling right away never sees a 404), then its final status once the
	 *         background thread finishes.
	 */
	public String importFromAsync(final Session pSession, final Repository pRepository, final MappingDefinition pFeed,
		final Map<String, String> pExtraInputs, final int pMax, final String pOrigin, final int pResumeFrom)
	{
		return this.importFromAsync(pSession, pRepository, pFeed, pExtraInputs, pMax, pOrigin, pResumeFrom, false);
	}

	/** Volume lot V2-D: like {@link #importFromAsync(Session, Repository, MappingDefinition, Map, int, String, int)} with {@code pFullRefresh} - see {@link #importFrom(Session, Repository, MappingDefinition, Map, int, boolean, String, int, boolean)}. */
	public String importFromAsync(final Session pSession, final Repository pRepository, final MappingDefinition pFeed,
		final Map<String, String> pExtraInputs, final int pMax, final String pOrigin, final int pResumeFrom, final boolean pFullRefresh)
	{
		// Decided before anything is written: a run that is refused must leave no RUNNING row behind.
		final String runId = ConnectorService.generateRunId();
		final String holder = ConnectorService.MAPPING_RUNS.tryAcquire(pFeed.getId(), runId);
		if (holder != null)
		{
			throw new MappingAlreadyRunningException(MappingRunLocks.refusal(pFeed.getId(), pFeed.getLabel(), holder));
		}
		if (!ConnectorService.ASYNC_SLOTS.tryReserve())
		{
			ConnectorService.MAPPING_RUNS.release(pFeed.getId(), runId);
			throw new TooManyRunsException(ConnectorService.ASYNC_SLOTS.refusal(ConnectorService.ASYNC_MAX_CONCURRENT_PROPERTY, ConnectorService.ASYNC_MAX_QUEUED_PROPERTY));
		}
		final ConnectorRepository repo = this.repository(pRepository);
		boolean handedOver = false;
		try
		{
			// Registered synchronously so a caller that polls connectors_run_get immediately after this
			// method returns never races the background thread for the first row.
			repo.saveRunState(pSession, pFeed, runId, "RUNNING", new ImportOutcome(), 0, null, "connectors:" + runId + ":async-start");
			ConnectorService.ASYNC_RUNS.add(runId);
			ConnectorService.ASYNC_IMPORT_EXECUTOR.get().execute(this.asyncWorker(runId, repo, pSession, pRepository, pFeed, pExtraInputs, pMax, pOrigin, pResumeFrom, pFullRefresh));
			handedOver = true;
		}
		catch (final java.util.concurrent.RejectedExecutionException stopping)
		{
			// The module is stopping: the row written above must not stay RUNNING for a run that never started.
			repo.saveRunState(pSession, pFeed, runId, AsyncRunGuard.INTERRUPTED, null, -1, "Not started: the connectors module is stopping", "connectors:" + runId + ":async-refused");
			throw new IllegalStateException("The connectors module is stopping: asynchronous import not started", stopping);
		}
		finally
		{
			if (!handedOver)
			{
				ConnectorService.ASYNC_RUNS.remove(runId);
				ConnectorService.ASYNC_SLOTS.release();
				ConnectorService.MAPPING_RUNS.release(pFeed.getId(), runId);
			}
		}
		return runId;
	}

	/** The work of one asynchronous import; gives its slot back when it ends, however it ends. */
	private Runnable asyncWorker(final String pRunId, final ConnectorRepository pRepo, final Session pSession, final Repository pRepository, final MappingDefinition pFeed,
		final Map<String, String> pExtraInputs, final int pMax, final String pOrigin, final int pResumeFrom, final boolean pFullRefresh)
	{
		return new Runnable()
		{
			@Override
			public void run()
			{
				ConnectorService.beginRun(pRunId);
				// The caller's session until a fresh one is minted: the terminal write below must have a
				// session even when minting is what failed.
				final Session[] workerSession = new Session[] { pSession };
				try
				{
					final String failure = AsyncRunGuard.run(new AsyncRunGuard.Body()
					{
						@Override
						public void run() throws Exception
						{
							// Minted once the worker actually runs, not on the caller's thread where pSession
							// is still guaranteed fresh - see WorkerSessions for the route and its fallback.
							workerSession[0] = ConnectorService.sessionForAsyncWorker(pSession, pRepository);
							// the mapping was taken for this run when it was accepted
							ConnectorService.this.importFromLocked(workerSession[0], pRepository, pFeed, pExtraInputs, pMax, false, pOrigin, pResumeFrom, pFullRefresh);
						}
					}, new AsyncRunGuard.TerminalState()
					{
						@Override
						public void write(final String pStatus, final String pMessage)
						{
							// importFrom already ends every failure it can attribute to the run; this is the
							// last resort for what escapes it, so that the run never stays RUNNING.
							final String message = SampleValueSafety.safeFailure(pFeed, pMessage);
							if (!pRepo.saveRunState(workerSession[0], pFeed, pRunId, pStatus, null, -1, message, "connectors:" + pRunId + ":async-fail"))
							{
								Bookkeeping.rememberLostRunState(pRunId, pStatus, message);
								throw new IllegalStateException("EBX refused the run state (see the kernel log)");
							}
						}
					});
					if (failure != null)
					{
						ConnectorService.log().error("[connectors] async import " + pRunId + " did not complete: " + SampleValueSafety.safeFailure(pFeed, failure));
					}
				}
				finally
				{
					ConnectorService.ASYNC_RUNS.remove(pRunId);
					ConnectorService.ASYNC_SLOTS.release();
					ConnectorService.MAPPING_RUNS.release(pFeed.getId(), pRunId);
					ConnectorService.endRun();
				}
			}
		};
	}

	/**
	 * A fresh {@link Session} for the same user as {@code pCallerSession}, for the asynchronous import
	 * worker to use instead of the caller's own (which may be tied to the lifecycle of the HTTP or MCP
	 * request that started the run). Runs for every asynchronous import, whatever the caller: for the
	 * in-JVM bridge, whose session is already long-lived, it is redundant rather than harmful.
	 * <p>
	 * The route goes through an EBX internal and is isolated in {@link WorkerSessions}, which also
	 * holds the fallback. <b>Not verified live</b> under an SSO directory.
	 *
	 * @return a freshly minted {@link Session} for the same user, or {@code pCallerSession} unchanged
	 *         when minting is not possible for any reason - a session-minting problem never breaks an
	 *         import that has already started.
	 */
	static Session sessionForAsyncWorker(final Session pCallerSession, final Repository pRepository)
	{
		return WorkerSessions.forWorker(pCallerSession, pRepository);
	}

	/**
	 * Imports a document already in hand (a webhook body, a file content, a message) through a feed
	 * mapping: the items at {@code itemsPath} (or the document itself) are created / updated one by
	 * one, like {@link #importFrom}. Volume lot 7a: read through {@link Connector#replay} - the same
	 * narrowing/streaming logic {@link Connector#readRows}'s default implementation uses - instead of
	 * copying the narrowed array into a second {@code List<JsonNode>} before processing it.
	 */
	public ImportOutcome importDocument(final Session pSession, final Repository pRepository, final MappingDefinition pFeed,
		final JsonNode pDocument, final int pMax, final boolean pDryRun, final String pOrigin)
	{
		return this.importDocument(pSession, pRepository, pFeed, pDocument, pMax, pDryRun, pOrigin, 0);
	}

	/**
	 * Volume lot 6, task 5: like {@link #importDocument(Session, Repository, MappingDefinition,
	 * JsonNode, int, boolean, String)}, restarting at document item index {@code pResumeFrom} (0-based)
	 * instead of from the start - see that overload's resume caveat (stable item order required).
	 */
	public ImportOutcome importDocument(final Session pSession, final Repository pRepository, final MappingDefinition pFeed,
		final JsonNode pDocument, final int pMax, final boolean pDryRun, final String pOrigin, final int pResumeFrom)
	{
		final ConnectorRepository repo = this.repository(pRepository);
		final com.on.ebx.connect.ebx.DatasetLocator.Location location = com.on.ebx.connect.ebx.DatasetLocator.locate(repo.getRepository(), pFeed.getDataspace(),
			pFeed.getDataset(), pFeed.getTablePath());
		final Adaptation dataset = location.getDataset();
		final SyncResult header = new SyncResult(pFeed.getConnectorId(), pFeed.getOperation(), "IMPORT",
			dataset == null ? pFeed.getDataspace() : dataset.getHome().getKey().getName(), pFeed.getTablePath(), "", pFeed.getLabel());
		final long start = System.currentTimeMillis();
		final String runId = ConnectorService.currentRunId();
		header.setRunId(runId);
		header.setMappingId(String.valueOf(pFeed.getId()));
		if (blockedCatalogScan(pSession, repo, pFeed, pDryRun, pOrigin, header))
		{
			final ImportOutcome outcome = new ImportOutcome();
			outcome.setRunId(runId);
			outcome.failRun(header);
			ConnectorService.saveFailedRun(pSession, repo, pFeed, runId, outcome, pDryRun);
			return outcome;
		}
		if (dataset == null)
		{
			header.setStatus(SyncResult.Status.ERROR);
			header.setMessage(location.failure(pFeed.getDataspace(), pFeed.getDataset(), pFeed.getTablePath()));
			header.setDurationMs(System.currentTimeMillis() - start);
			final ImportOutcome outcome = new ImportOutcome();
			outcome.setRunId(runId);
			outcome.failRun(header);
			ConnectorService.saveFailedRun(pSession, repo, pFeed, runId, outcome, pDryRun);
			return outcome;
		}
		final RowSource source = new RowSource()
		{
			@Override
			public Connector.RowStreamResult stream(final int pStreamMax, final Connector.RowSink pSink)
			{
				return Connector.replay(pDocument, pFeed.getItemsPath(), pStreamMax, pSink);
			}
		};
		final ImportOutcome outcome = this.runImport(pSession, repo, pFeed, dataset, source, pMax, pDryRun, pOrigin, header, start,
			"Empty document for " + pFeed.getLabel(), Math.max(0, pResumeFrom), runId, null);
		finishCatalogImport(pSession, repo, pFeed, pDocument, outcome, pDryRun, pOrigin);
		return outcome;
	}

	/** Fail before execution without attributing completion to a changed Source. */
	private static boolean blockedCatalogScan(final Session session, final ConnectorRepository repo,
		final MappingDefinition mapping, final boolean dryRun, final String origin, final SyncResult result)
	{
		final String error = CatalogScanStatus.executionIdentityError(repo, mapping);
		if (error == null)
		{
			return false;
		}
		result.setStatus(SyncResult.Status.ERROR);
		result.setRunId(ConnectorService.currentRunId());
		result.setMappingId(String.valueOf(mapping.getId()));
		result.setMessage(error);
		if (!dryRun) logCompletionNotice(session, repo, result, origin, null);
		return true;
	}

	/** Normalizes provider discovery failures for both live connector and supplied-document feeds. */
	static SyncResult discoveryFailure(final MappingDefinition mapping, final JsonNode document, final boolean dryRun)
	{
		if (mapping == null || !"catalog".equals(mapping.getOperation()) || document == null
			|| !document.path("errors").isArray() || document.path("errors").size() == 0) return null;
		final SyncResult notice = new SyncResult(mapping.getConnectorId(), "catalog-discovery", mapping.getDirection().name(),
			mapping.getDataspace(), mapping.getTablePath(), "", "Metadata discovery");
		notice.setStatus(dryRun ? SyncResult.Status.DRY_RUN : SyncResult.Status.ERROR);
		notice.setRunId(ConnectorService.currentRunId());
		notice.setMappingId(String.valueOf(mapping.getId()));
		notice.setMessage("Metadata discovery incomplete: " + document.path("errors").size()
			+ " failure(s); inspect source retrieval statuses.");
		if (dryRun) notice.getErrors().add("Discovery was incomplete; no records were written.");
		return notice;
	}

	/**
	 * Volume lot 7a: takes the run's {@link ImportOutcome} instead of a raw {@code List<SyncResult>}.
	 * Volume lot 7b: {@code CatalogScanStatus.record} now reads {@link ImportOutcome#getCatalogTally()}
	 * (exact and unbounded) instead of the capped {@link ImportOutcome#getResults()} sample, so a
	 * schema inventory larger than {@code resultsMax} is no longer undercounted - see
	 * docs/REFERENCE.md.
	 */
	private static void finishCatalogImport(final Session session, final ConnectorRepository repo,
		final MappingDefinition mapping, final JsonNode document, final ImportOutcome outcome,
		final boolean dryRun, final String origin)
	{
		final SyncResult discovery = discoveryFailure(mapping, document, dryRun);
		if (discovery != null)
		{
			outcome.addResult(discovery);
			if (!dryRun) logCompletionNotice(session, repo, discovery, origin, outcome);
		}
		if (dryRun) return;
		final SyncResult bookkeeping = CatalogScanStatus.record(session, repo, mapping, outcome);
		if (bookkeeping != null)
		{
			outcome.addResult(bookkeeping);
			// Logging cannot turn a metadata completion problem into a retry of committed imports.
			logCompletionNotice(session, repo, bookkeeping, origin, outcome);
		}
	}

	private static void logCompletionNotice(final Session session, final ConnectorRepository repo,
		final SyncResult notice, final String origin, final ImportOutcome outcome)
	{
		try { ConnectorService.logInto(outcome, repo, session, notice, origin); }
		catch (final RuntimeException unavailable) {
			ConnectorService.log().warn("[connectors] catalog completion notice could not be persisted; returned with run results");
		}
	}

	/**
	 * Whether a feed's read was cut short, and by how much the source really held - pure, no EBX write,
	 * unit-tested. Two independent signals feed it: the items counted off the document's own
	 * {@code itemsPath} array (the old "a catalog scan stopped at 100" case, where the connector
	 * returns everything and this class truncates), and the top-level {@code truncated} /
	 * {@code available} fields a connector sets on the document itself when IT already cut the read at
	 * its own {@code max} (a file feed's {@code read}; JdbcConnector's non-streamed operations - see
	 * JdbcConnector.boundedQuery and FileFormats.parse). Before this, a connector that pre-truncated
	 * its own array never tripped the old {@code available > items.size()} check (both sides were
	 * already equal), so the run came back looking like a clean, complete load (audited 2026-09-28).
	 * <p>
	 * Volume lot 7a: superseded, for the streamed read itself, by {@link Connector.RowStreamResult}
	 * (which folds the same two signals in directly - see {@link Connector#replay}); kept, unused by
	 * {@link #runImport}, only because {@link ImportTruncationTest} still exercises it directly and
	 * because a connector's own non-streamed operations (JDBC's {@code get}/{@code catalog}/etc.,
	 * everything on every connector before lot 7b) still produce a document this same shape of check
	 * applies to.
	 */
	static final class Truncation
	{
		final boolean truncated;
		/** The source's real total when known; -1 otherwise. */
		final long available;

		Truncation(final boolean pTruncated, final long pAvailable)
		{
			this.truncated = pTruncated;
			this.available = pAvailable;
		}
	}

	static Truncation truncationOf(final JsonNode pDocument, final int pCountedAvailable, final int pImportedCount)
	{
		if (pCountedAvailable > pImportedCount)
		{
			return new Truncation(true, pCountedAvailable);
		}
		final boolean declared = pDocument != null && pDocument.path("truncated").asBoolean(false);
		if (!declared)
		{
			return new Truncation(false, -1);
		}
		final long declaredAvailable = pDocument.hasNonNull("available") ? pDocument.path("available").asLong(-1) : -1;
		return new Truncation(true, declaredAvailable);
	}

	/**
	 * Volume lot 7a: where {@link #importFrom} and {@link #importDocument} get their items from - a
	 * live connector call ({@link Connector#readRows}) or a document already in hand
	 * ({@link Connector#replay}). Both shapes reduce to "stream items to a sink, bounded by max,
	 * report what was delivered/truncated/available", so the batching/resume/breaker/cancellation
	 * engine in {@link #runImport}/{@link FeedSink} does not need to know which one it is talking to.
	 */
	private interface RowSource
	{
		Connector.RowStreamResult stream(int pMax, Connector.RowSink pSink) throws ConnectorException;
	}

	/**
	 * Volume lot 7a: the shared engine behind {@link #importFrom} and {@link #importDocument}. Reads
	 * {@code pSource} into a {@link FeedSink} (resumeFrom skipping, the dry-run/batchSize<=1 immediate
	 * path, and the batched real-run path with bisection/breaker/cancellation/resume all live there),
	 * then builds the run's {@link ImportOutcome}: a "no items at all" header, a "limit reached" notice
	 * when the source was cut short, a "not processed" notice when the sink itself stopped early, and
	 * the persisted {@code ExecutionRun} row's final status - for EVERY import run, synchronous or
	 * asynchronous, batched or not, so {@code connectors_run_get(runId)} always has something to say
	 * about a feed sync once it has started.
	 */
	private ImportOutcome runImport(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed, final Adaptation pDataset,
		final RowSource pSource, final int pMax, final boolean pDryRun, final String pOrigin, final SyncResult pHeader, final long pStart,
		final String pEmptyMessage, final int pResumeFrom, final String pRunId, final WatermarkRun pWatermark)
	{
		final ImportOutcome outcome = new ImportOutcome();
		outcome.setRunId(pRunId);
		WatermarkFilter watermarkFilter = null;
		boolean failed = false;
		if (pWatermark != null)
		{
			outcome.setWatermark(pWatermark.getField(), pWatermark.getStored(), pWatermark.isFullRefresh(), pWatermark.getNote());
		}
		// Volume lot 7b: a catalog scan flow gets its own exact, unbounded item tally (CatalogScanTally)
		// alongside the ordinary bounded results/errors samples - see CatalogScanStatus.outcome.
		if (CatalogScanStatus.sourceOf(pFeed) != null)
		{
			outcome.enableCatalogTally();
		}
		FeedSink sink = null;
		try
		{
			final AdaptationTable table = pDataset.getTable(Path.parse(pFeed.getTablePath()));
			// Volume lot 5: computed once for the whole run, not per row - task 1's primary-key fast
			// path (falls back to the per-row XPath Request when the predicate or the key's field types
			// do not qualify) and task 4's transformation caches (table/pipeline/regex/lookup results).
			final FeedKeyLookup primaryKeyLookup = FeedKeyLookup.of(pFeed, table);
			final TransformationRunCache runCache = new TransformationRunCache();
			sink = new FeedSink(pSession, pRepo, pFeed, table, pDataset, pOrigin, primaryKeyLookup, runCache,
				Math.max(0, pResumeFrom), pDryRun, pFeed.getBatchSize(), outcome, pRunId);
			// Volume lot V2-D: an incremental feed reads through the watermark filter (skips what is at or
			// below the checkpoint before any lookup, tracks the highest watermark it sees).
			watermarkFilter = pWatermark == null ? null : pWatermark.filter(sink, outcome);
			final Connector.RowStreamResult streamResult = pSource.stream(pMax, watermarkFilter == null ? sink : watermarkFilter);
			outcome.setSourceDocument(streamResult.document);
			sink.finish();
			outcome.setNextIndex(sink.index);
			if (!sink.sawAnyItem)
			{
				pHeader.setStatus(SyncResult.Status.NO_DATA);
				pHeader.setMessage(watermarkFilter != null && watermarkFilter.getSeen() > 0 ? pWatermark.describeEmpty(watermarkFilter) : pEmptyMessage);
				pHeader.setDurationMs(System.currentTimeMillis() - pStart);
				outcome.addResult(pHeader);
				if (!pDryRun)
				{
					ConnectorService.logInto(outcome, pRepo, pSession, pHeader, pOrigin);
				}
			}
			else
			{
				// The items past the maximum used to vanish without a trace (seen 2026-09-16: a catalog
				// scan of 214 tables stopped at 100, the last dataspaces of its filter never appeared).
				// Say so in the results and the log either way.
				if (streamResult.truncated)
				{
					final SyncResult limit = new SyncResult(pFeed.getConnectorId(), pFeed.getOperation(), "IMPORT", pHeader.getDataspace(),
						pFeed.getTablePath(), "", pFeed.getLabel());
					limit.setStatus(SyncResult.Status.SKIPPED);
					limit.setRunId(pRunId);
					limit.setMappingId(String.valueOf(pFeed.getId()));
					// a notice even when the source gave no total: every reader of the results sees the cut (audit C-35)
					limit.markLimitReached();
					if (streamResult.available >= 0)
					{
						limit.setAvailable((int) Math.min(Integer.MAX_VALUE, streamResult.available));
						limit.setMessage("Limit reached: " + streamResult.count + " of the " + streamResult.available + " items returned by " + pFeed.getConnectorId() + "/"
							+ pFeed.getOperation() + " were imported; raise max to import the others");
					}
					else
					{
						limit.setMessage("Limit reached: the source returned " + streamResult.count + " row(s) and more were available: raise max to import"
							+ " the rest (" + pFeed.getConnectorId() + "/" + pFeed.getOperation() + ")");
					}
					limit.setDurationMs(System.currentTimeMillis() - pStart);
					outcome.addResult(limit);
					// Volume lot 7b: explicit, regardless of whether streamResult.available is known - see
					// ImportOutcome.markTruncated's own javadoc for why addResult alone is not enough here.
					outcome.markTruncated(streamResult.available, limit.getMessage());
					if (!pDryRun)
					{
						ConnectorService.logInto(outcome, pRepo, pSession, limit, pOrigin);
					}
				}
				if (sink.stopReason != null)
				{
					ConnectorService.addNotProcessedNotice(pSession, pRepo, outcome, pFeed, pDataset, pRunId, pOrigin, sink.stopReason,
						streamResult.available, sink.index);
				}
			}
		}
		catch (final Exception ex)
		{
			failed = true;
			pHeader.setStatus(SyncResult.Status.ERROR);
			pHeader.setMessage(SampleValueSafety.safeFailure(pFeed, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			pHeader.setDurationMs(System.currentTimeMillis() - pStart);
			outcome.failRun(pHeader);
			if (!pDryRun)
			{
				ConnectorService.logInto(outcome, pRepo, pSession, pHeader, pOrigin);
			}
		}
		finally
		{
			ConnectorService.clearCancellation(pRunId);
		}
		outcome.setDurationMs(System.currentTimeMillis() - pStart);
		if (!pDryRun)
		{
			final String stopReason = sink == null ? null : sink.stopReason;
			final boolean cancelled = stopReason != null && stopReason.startsWith("Import cancelled");
			final String finalStatus = outcome.finalStatus(cancelled);
			String message = stopReason != null ? stopReason
				: pHeader.getMessage() != null && !pHeader.getMessage().isEmpty() ? pHeader.getMessage() : null;
			if (pWatermark != null)
			{
				// Volume lot V2-D: the checkpoint moves only when the whole run succeeded - never on a stop, an
				// error, a truncation or an exception; a stopped or failed run keeps the old value.
				final boolean advance = WatermarkRun.mayAdvance(false, finalStatus, outcome.isTruncated(), outcome.getErrorCount() + outcome.getErrors().size(),
					failed, stopReason != null);
				ConnectorService.endWatermark(pSession, pRepo, pFeed, pWatermark, pWatermark.next(watermarkFilter), advance, outcome);
				if (outcome.getWatermarkNote() != null && !outcome.getWatermarkNote().isEmpty())
				{
					message = message == null ? outcome.getWatermarkNote() : message + " - " + outcome.getWatermarkNote();
				}
			}
			// What could not be written about this run (sync log lines, provenance, conflicts) is said on the run itself, where a
			// person watching it reads it, and no longer only in the kernel log (audit C-38).
			final String lost = outcome.getBookkeepingNotice();
			if (lost != null && !lost.isEmpty())
			{
				message = message == null || message.isEmpty() ? lost : message + " - " + lost;
			}
			ConnectorService.saveFinalRunState(pSession, pRepo, pFeed, pRunId, finalStatus, outcome, message);
		}
		return outcome;
	}

	/**
	 * Audit C3-04: the inputs of a CDC run. The mapping decides everything it configures: delete mode and flag, matching and its
	 * policy, the topic, the format. A run-time input (a holder of the RUN right is not the mapping's designer) may only LOWER
	 * the size of a batch, {@code maxEvents} and {@code maxBytes}; every other one is dropped and its name added to
	 * {@code pIgnored}, which the run says in its message (the same wording as a run-time input a feed does not read).
	 */
	static Map<String, String> cdcRunInputs(final Map<String, String> pMapping, final Map<String, String> pExtra, final List<String> pIgnored)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>(pMapping);
		if (pExtra == null)
		{
			return inputs;
		}
		for (final Map.Entry<String, String> extra : pExtra.entrySet())
		{
			final String name = extra.getKey();
			if (("maxEvents".equals(name) || "maxBytes".equals(name)) && ConnectorService.lowersCdcLimit(name, inputs, extra.getValue()))
			{
				inputs.put(name, extra.getValue().trim());
			}
			else
			{
				pIgnored.add(name);
			}
		}
		return inputs;
	}

	/** Whether a run-time {@code maxEvents} / {@code maxBytes} is a whole number strictly below what the mapping (or the default) gives. */
	private static boolean lowersCdcLimit(final String pName, final Map<String, String> pMapping, final String pValue)
	{
		final long wanted;
		try
		{
			wanted = Long.parseLong(pValue == null ? "" : pValue.trim());
		}
		catch (final NumberFormatException notANumber)
		{
			return false;
		}
		final long current = "maxEvents".equals(pName) ? com.on.ebx.connect.connectors.KafkaConnector.cdcMaxEvents(pMapping)
			: com.on.ebx.connect.connectors.KafkaConnector.cdcMaxBytes(pMapping);
		return wanted >= 1 && wanted < current;
	}

	/** Thrown by the CDC applier when EBX did not apply the whole batch: the checkpoint must not move. */
	private static final class CdcNotApplied extends Exception
	{
		private static final long serialVersionUID = 1L;

		CdcNotApplied(final String pMessage)
		{
			super(pMessage);
		}
	}

	/**
	 * Gartner lot B: one Debezium batch of a {@code cdc} feed (docs/CDC.md). The batch is polled from the stored
	 * checkpoint without any broker commit; its c/u/r events go through the feed mapping like any imported row
	 * (keyPredicate, rules, trust, provenance), its d events delete, flag or are ignored ({@code deleteMode}); the
	 * checkpoint is saved only when every upsert and delete succeeded and the run is not a simulation. A failed or
	 * partial batch keeps the old checkpoint: the next run reads the same events again, which the idempotent apply
	 * makes harmless.
	 */
	private ImportOutcome runCdc(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed, final Adaptation pDataset,
		final com.on.ebx.connect.connectors.KafkaConnector pKafka, final Map<String, String> pExtraInputs, final boolean pDryRun, final String pOrigin,
		final SyncResult pHeader, final long pStart, final String pRunId) throws Exception
	{
		// preview function, closed by default (audit C3-13, C3-15): the second door, for a caller that reaches this method by another way
		PreviewFeatures.requireOperation("kafka", pFeed.getOperation());
		// audit C3-04: the mapping's own inputs rule (delete mode, matching, topic...); a run-time input only lowers the size of the batch
		final List<String> refusedInputs = new java.util.ArrayList<String>();
		final Map<String, String> inputs = ConnectorService.cdcRunInputs(pFeed.getInputs(), pExtraInputs, refusedInputs);
		if (!refusedInputs.isEmpty())
		{
			pHeader.setMessage(RunLimits.ignoredInputsMessage(refusedInputs));
			ConnectorService.log().warn("[connectors] mapping " + pFeed.getId() + " CDC run " + pRunId + ": " + RunLimits.ignoredInputsMessage(refusedInputs));
		}
		// preview function, closed by default: a feed that asks for matching around the batch does not run
		PreviewFeatures.requireMappingAllowed(pFeed.getOperation(), inputs);
		final CdcApplyPlan.Options options = CdcApplyPlan.Options.from(inputs);
		final CdcMatching matching = CdcMatching.from(inputs);
		final CdcMatching.Engine engine = matching.isActive() && !pDryRun
			? GoldenRecords.get().matchingEngine(pSession, pDataset.getTable(Path.parse(pFeed.getTablePath()))) : null;
		if (matching.isActive() && !pDryRun && engine == null)
			throw new ConnectorException("Kafka CDC: matching=" + inputs.get("matching").trim() + " needs Match and Merge on " + pFeed.getTablePath()
				+ " (no matching configuration for this table, or the add-on is absent); use matching=policy");
		final String[] matched = new String[1];
		final SynchronizationCheckpoint stored = ConnectorService.storedCheckpoint(pRepo, pFeed);
		final String before = stored == null || stored.getValue() == null ? "" : stored.getValue();
		final ImportOutcome[] applied = new ImportOutcome[1];
		final String[] summary = new String[1];
		final String[] candidate = new String[1];
		SynchronizationCheckpoint saved = null;
		String kept = null;
		try
		{
			saved = pKafka.executeCdc(pFeed.getId(), before, com.on.ebx.connect.connectors.KafkaConnector.cdcMaxEvents(inputs),
				com.on.ebx.connect.connectors.KafkaConnector.cdcMaxBytes(inputs), pDryRun, new com.on.ebx.connect.connectors.KafkaCdcExecution.Applier()
				{
					@Override
					public void apply(final CdcBatch pBatch, final boolean pDry) throws Exception
					{
						final CdcApplyPlan.Plan plan = CdcApplyPlan.plan(pBatch, options);
						summary[0] = plan.summary(options.getDeleteMode());
						candidate[0] = pBatch.getCandidateCheckpoint();
						final com.fasterxml.jackson.databind.node.ArrayNode rows = Json.mapper().createArrayNode();
						rows.addAll(new java.util.ArrayList<JsonNode>(plan.getUpserts()));
						pHeader.setMessage(summary[0]);
						final java.util.LinkedHashSet<String> written = new java.util.LinkedHashSet<String>();
						final List<String> deleteErrors = new java.util.ArrayList<String>();
						if (engine != null)
							ConnectorService.log().info("[connectors] mapping " + pFeed.getId() + " CDC run " + pRunId + ": Match and Merge trigger paused on "
								+ pFeed.getTablePath() + " for the writes (if the JVM stops now, resume it: match-merge set_table_settings pauseMatching=false)");
						// the writes: matching paused around them (matching=afterApply|off), the connector marked as their origin (skipOrigin)
						matching.whilePaused(pDry ? null : engine, () -> CdcOrigin.run(pFeed.getConnectorId(), () ->
						{
							ConnectorService.CDC_WRITTEN.set(written);
							try
							{
								applied[0] = ConnectorService.this.runImport(pSession, pRepo, pFeed, pDataset, new RowSource()
								{
									@Override
									public Connector.RowStreamResult stream(final int pStreamMax, final Connector.RowSink pSink)
									{
										return Connector.replay(rows, "", pStreamMax, pSink);
									}
								}, Math.max(1, rows.size()), pDry, pOrigin, pHeader, pStart, summary[0], 0, pRunId, null);
							}
							finally
							{
								ConnectorService.CDC_WRITTEN.remove();
							}
							if (!pDry)
								deleteErrors.addAll(ConnectorService.applyCdcDeletes(pSession, pDataset, pFeed, options, plan.getDeletes()));
						}));
						final ImportOutcome outcome = applied[0];
						for (final String error : deleteErrors)
						{
							final SyncResult failure = new SyncResult(pFeed.getConnectorId(), pFeed.getOperation(), "IMPORT",
								pDataset.getHome().getKey().getName(), pFeed.getTablePath(), "", pFeed.getLabel());
							failure.setRunId(pRunId);
							failure.setStatus(SyncResult.Status.ERROR);
							failure.setMessage(error);
							outcome.addResult(failure);
							outcome.error();
						}
						final int failures = outcome.getErrorCount() + outcome.getErrors().size();
						if (outcome.getRunFailure() != null || failures > 0 || outcome.isTruncated())
							throw new CdcNotApplied(failures + " change(s) not applied");
						if (pDry)
						{
							if (matching.isActive())
								matched[0] = "matching: simulation, " + written.size() + " record(s) would be matched (" + matching.getMode() + ")";
							return;
						}
						if (engine != null)
						{
							final CdcMatching.Outcome result = matching.matchWritten(engine, written);
							matched[0] = result.getMessage();
							if (!result.isDone())
								throw new CdcNotApplied(result.getMessage());
						}
					}
				}, new CdcCheckpointCoordinator(new com.on.ebx.connect.ebx.EbxSynchronizationCheckpointStore(pRepo, pSession)), new java.util.Date());
		}
		catch (final CdcNotApplied ex)
		{
			kept = ex.getMessage() + ": checkpoint kept, the next run reads the batch again";
		}
		catch (final IllegalArgumentException | IllegalStateException ex)
		{
			// a malformed envelope, an out-of-order position, a non-VENDOR_CURSOR checkpoint: nothing was applied
			if (applied[0] == null) throw new ConnectorException("Kafka CDC: " + ex.getMessage());
			kept = "checkpoint not saved: " + ex.getMessage();
		}
		ImportOutcome outcome = applied[0];
		if (outcome == null)
		{
			// no event after the checkpoint: an empty run, recorded like any empty import
			pHeader.setMessage("CDC: no change event after the checkpoint");
			outcome = this.runImport(pSession, pRepo, pFeed, pDataset, new RowSource()
			{
				@Override
				public Connector.RowStreamResult stream(final int pStreamMax, final Connector.RowSink pSink)
				{
					return new Connector.RowStreamResult(0, false, 0);
				}
			}, 1, pDryRun, pOrigin, pHeader, pStart, "CDC: no change event after the checkpoint", 0, pRunId, null);
		}
		outcome.setWatermark("kafka offsets", before.isEmpty() ? null : before, false, summary[0]);
		outcome.setWatermarkAfter(saved != null ? saved.getValue() : before.isEmpty() ? null : before, saved != null);
		if (matched[0] != null)
		{
			outcome.appendWatermarkNote(matched[0]);
			pHeader.setMessage((pHeader.getMessage() == null ? "" : pHeader.getMessage() + " - ") + matched[0]);
		}
		if (kept != null) outcome.appendWatermarkNote(kept);
		else if (pDryRun && candidate[0] != null) outcome.appendWatermarkNote("simulation: checkpoint not saved (would be " + candidate[0] + ")");
		ConnectorService.log().info("[connectors] mapping " + pFeed.getId() + " CDC run " + pRunId + ": " + (summary[0] == null ? "no event" : summary[0])
			+ (matched[0] != null ? " - " + matched[0] : "") + (kept != null ? " - " + kept : saved != null ? " - checkpoint " + saved.getValue() : ""));
		return outcome;
	}

	/**
	 * Gartner lot B: the delete events of a CDC batch, in one procedure - each row is located by the feed's
	 * keyPredicate resolved on the event's key and before image; a missing record is already in the wanted
	 * state. Returns one message per row that could not be applied (the procedure is then rolled back).
	 */
	static List<String> applyCdcDeletes(final Session pSession, final Adaptation pDataset, final MappingDefinition pFeed,
		final CdcApplyPlan.Options pOptions, final List<com.fasterxml.jackson.databind.node.ObjectNode> pDeletes)
	{
		final List<String> errors = new java.util.ArrayList<String>();
		if (pDeletes.isEmpty() || pOptions.getDeleteMode() == CdcApplyPlan.DeleteMode.IGNORE) return errors;
		if (pFeed.getKeyPredicate().isEmpty())
		{
			errors.add("CDC delete: the mapping has no keyPredicate to find the record of a deleted row");
			return errors;
		}
		final AdaptationTable table = pDataset.getTable(Path.parse(pFeed.getTablePath()));
		final List<Adaptation> targets = new java.util.ArrayList<Adaptation>();
		for (final JsonNode row : pDeletes)
		{
			try
			{
				final Adaptation record = ConnectorService.locate(pSession, table, ConnectorService.resolvePredicate(pFeed.getKeyPredicate(), row));
				if (record != null) targets.add(record);
			}
			catch (final ConnectorException ex)
			{
				errors.add("CDC delete " + row.path("__position").asText() + ": " + ex.getMessage());
			}
		}
		if (!errors.isEmpty() || targets.isEmpty()) return errors;
		final Path flag = pOptions.getDeleteMode() == CdcApplyPlan.DeleteMode.FLAG ? Path.parse(pOptions.getFlagField()) : null;
		final ProgrammaticService service = ProgrammaticService.createForSession(pSession, pDataset.getHome());
		service.setSessionTrackingInfo("connectors:cdc:" + pFeed.getId());
		final ProcedureResult result = service.execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Adaptation record : targets)
				{
					if (flag == null)
					{
						pContext.doDelete(record.getAdaptationName(), false);
						continue;
					}
					final SchemaNode node = record.getSchemaNode().getNode(flag);
					if (node == null) throw new IllegalArgumentException(pOptions.getFlagField() + " is not a field of " + pFeed.getTablePath());
					final Object current = record.get(flag);
					if (current != null && pOptions.getFlagValue().equals(node.formatToXsString(current))) continue; // already flagged
					final ValueContextForUpdate update = pContext.getContext(record.getAdaptationName());
					update.setValueFromXsString(pOptions.getFlagValue(), flag);
					pContext.doModifyContent(record, update);
				}
			}
		});
		if (result.hasFailed())
		{
			errors.add("CDC " + (flag == null ? "delete" : "flag") + " of " + targets.size() + " record(s) failed: "
				+ Redaction.redact(result.getExceptionFullMessage(java.util.Locale.ENGLISH)));
		}
		return errors;
	}

	/**
	 * A run that failed before {@link #runImport} took over (connector or operation unknown, dataset not found, scan refused) is
	 * recorded as {@code ERROR} like any other failed run, with the reason as its message: an asynchronous one would otherwise
	 * keep the {@code RUNNING} row written when it was accepted, and a synchronous one left no run at all. A simulation writes nothing.
	 */
	private static void saveFailedRun(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed, final String pRunId,
		final ImportOutcome pOutcome, final boolean pDryRun)
	{
		if (!pDryRun)
		{
			ConnectorService.saveFinalRunState(pSession, pRepo, pFeed, pRunId, pOutcome.finalStatus(false), pOutcome, pOutcome.getRunFailure());
		}
	}

	/**
	 * Writes the final state of a run, and does not let a refused write pass for a finished run. The
	 * full write carries counters and error samples; if EBX refuses it, a second, reduced write
	 * (status, resume position and message only) is attempted, since what makes a row unacceptable is
	 * usually one of the values it carries. If that fails too, the row keeps saying {@code RUNNING}: the
	 * state is then remembered in memory ({@link Bookkeeping#lostRunState}) for whoever reads the run
	 * before the next restart, at which point the row is marked {@code INTERRUPTED}. Either way the
	 * outcome handed back to the caller says that the run state was lost. Never throws.
	 */
	private static void saveFinalRunState(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed, final String pRunId,
		final String pFinalStatus, final ImportOutcome pOutcome, final String pMessage)
	{
		final String tracking = "connectors:" + pRunId + ":" + pFeed.getId() + ":final";
		if (pRepo.saveRunState(pSession, pFeed, pRunId, pFinalStatus, pOutcome, pOutcome.getNextIndex(), pMessage, tracking))
		{
			Bookkeeping.forgetLostRunState(pRunId);
			return;
		}
		pOutcome.bookkeepingLost(Bookkeeping.RUN_STATE);
		final String reduced = ConnectorService.reducedRunStateMessage(pMessage);
		if (pRepo.saveRunState(pSession, pFeed, pRunId, pFinalStatus, null, pOutcome.getNextIndex(), reduced, tracking + "-reduced"))
		{
			Bookkeeping.forgetLostRunState(pRunId);
			return;
		}
		Bookkeeping.rememberLostRunState(pRunId, pFinalStatus, pMessage);
	}

	/** The message of a run row saved without its counters: says so first, then what the run had to say. */
	static String reducedRunStateMessage(final String pMessage)
	{
		return "Final state saved without the counters of the last batch: the full write was refused (see the kernel log)"
			+ (pMessage == null || pMessage.isEmpty() ? "" : " - " + pMessage);
	}

	/** Writes one line of an import run to the sync log; a lost line is folded into the run's outcome instead of vanishing. */
	private static void logInto(final ImportOutcome pOutcome, final ConnectorRepository pRepo, final Session pSession, final SyncResult pResult, final String pOrigin)
	{
		if (!pRepo.log(pSession, pResult, pOrigin) && pOutcome != null)
		{
			pOutcome.bookkeepingLost(Bookkeeping.SYNC_LOG);
		}
	}

	/** The checkpoint row of a mapping, null when there is none or it cannot be read (an old model without the table): the run then reads from the initial value or the floor. */
	private static SynchronizationCheckpoint storedCheckpoint(final ConnectorRepository pRepo, final MappingDefinition pFeed)
	{
		try
		{
			return pRepo.checkpoint(pFeed.getId());
		}
		catch (final RuntimeException ex)
		{
			ConnectorService.log().warn("[connectors] checkpoint of mapping " + pFeed.getId() + " could not be read: "
				+ Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			return null;
		}
	}

	/**
	 * Volume lot V2-D: ends the watermark of a real run - saves the new checkpoint (one procedure, no
	 * per-row write) when the run may advance it and the highest value seen is above the stored one (or
	 * the run was a full refresh), and records before/after on the outcome. A checkpoint that cannot be
	 * saved (an old model without the table, a concurrent run that advanced it first) never fails the
	 * import - its rows are written - but is said in the run's note and message.
	 */
	private static void endWatermark(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed,
		final WatermarkRun pWatermark, final String pNext, final boolean pAdvance, final ImportOutcome pOutcome)
	{
		String after = pWatermark.getStored();
		boolean advanced = false;
		if (pAdvance && pNext != null && !pNext.equals(pWatermark.getStored()))
		{
			try
			{
				final java.util.Date now = new java.util.Date();
				pRepo.saveCheckpoint(pSession, new SynchronizationCheckpoint(pFeed.getId(), pWatermark.getKind().strategy(), pNext, now,
					pWatermark.getStoredVersion()), pWatermark.getStoredVersion(), now);
				ConnectorOperationsRuntime.checkpoint(pFeed.getConnectorId(), now);
				after = pNext;
				advanced = true;
			}
			catch (final RuntimeException ex)
			{
				final String reason = Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage());
				ConnectorService.log().warn("[connectors] checkpoint of mapping " + pFeed.getId() + " not saved: " + reason);
				pOutcome.appendWatermarkNote("Checkpoint not saved (" + reason + "): the next run reads from " + (pWatermark.getStored() == null ? "the start" : pWatermark.getStored()));
			}
		}
		pOutcome.setWatermarkAfter(after, advanced);
	}

	/**
	 * Volume lot 7a: the {@link Connector.RowSink} an import feed streams into. ResumeFrom skipping,
	 * the dry-run/batchSize<=1 per-item immediate write (delegating to the unchanged {@link #importItem}),
	 * and the batched real-run path (chunking, {@link #commitBatch}, the run-state checkpoint, the
	 * {@link CircuitBreaker} and cancellation) all live here now, instead of a since-removed
	 * {@code importItemsBatched} iterating a fully materialised {@code List<JsonNode>}.
	 */
	private final class FeedSink implements Connector.RowSink
	{
		private final Session session;
		private final ConnectorRepository repo;
		private final MappingDefinition feed;
		private final AdaptationTable table;
		private final Adaptation dataset;
		private final String origin;
		private final FeedKeyLookup primaryKeyLookup;
		private final TransformationRunCache runCache;
		private final int resumeFrom;
		private final boolean dryRun;
		private final int batchSize;
		private final ImportOutcome outcome;
		private final String runId;
		private final CircuitBreaker breaker = new CircuitBreaker(ConnectorService.CIRCUIT_BREAKER_THRESHOLD);

		private int index;
		private boolean sawAnyItem;
		private int batchIndex;
		private List<JsonNode> chunk = new ArrayList<JsonNode>();
		private String stopReason;
		private Connector.HandledListener handledListener;

		FeedSink(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed, final AdaptationTable pTable,
			final Adaptation pDataset, final String pOrigin, final FeedKeyLookup pPrimaryKeyLookup, final TransformationRunCache pRunCache,
			final int pResumeFrom, final boolean pDryRun, final int pBatchSize, final ImportOutcome pOutcome, final String pRunId)
		{
			this.session = pSession;
			this.repo = pRepo;
			this.feed = pFeed;
			this.table = pTable;
			this.dataset = pDataset;
			this.origin = pOrigin;
			this.primaryKeyLookup = pPrimaryKeyLookup;
			this.runCache = pRunCache;
			this.resumeFrom = pResumeFrom;
			this.dryRun = pDryRun;
			this.batchSize = pBatchSize;
			this.outcome = pOutcome;
			this.runId = pRunId;
		}

		@Override
		public boolean accept(final JsonNode pItem)
		{
			this.sawAnyItem = true;
			final int i = this.index++;
			if (i < this.resumeFrom)
			{
				return true; // neither looked up nor written - see importFrom/importDocument's resumeFrom javadoc
			}
			if (this.dryRun || this.batchSize <= 1)
			{
				final SyncResult result = ConnectorService.this.importItem(this.session, this.repo, this.feed, this.table, pItem, this.dryRun,
					this.origin, this.primaryKeyLookup, this.runCache);
				ConnectorService.tallyItem(this.outcome, result);
				if (!this.dryRun)
				{
					this.reportHandled(i, i + 1, result.getStatus() == SyncResult.Status.ERROR ? java.util.Collections.singleton(Integer.valueOf(i))
						: java.util.Collections.<Integer>emptySet());
					// This path writes item by item, so a lost row about an item is carried by its result.
					for (final String lost : result.getBookkeepingLost())
					{
						this.outcome.bookkeepingLost(lost);
					}
					ConnectorService.lineage(this.session, this.repo, this.feed, result);
				}
				return true;
			}
			this.chunk.add(pItem);
			if (this.chunk.size() >= this.batchSize)
			{
				return this.flushChunk();
			}
			return true;
		}

		/** Commits the current chunk; called once it reaches {@code batchSize}, and once more by {@link #finish} for a partial tail. */
		private boolean flushChunk()
		{
			if (this.chunk.isEmpty())
			{
				return true;
			}
			this.batchIndex++;
			final List<JsonNode> committing = this.chunk;
			this.chunk = new ArrayList<JsonNode>();
			final java.util.BitSet notWritten = new java.util.BitSet();
			final ImportBatchSummary summary = ConnectorService.this.commitBatch(this.session, this.repo, this.feed, this.table, this.dataset,
				committing, this.origin, this.primaryKeyLookup, this.runCache, this.runId, this.batchIndex, this.outcome, notWritten);
			// The items of this chunk were numbered consecutively as they came; the source may now let go of them.
			final int firstOrdinal = this.index - committing.size();
			final java.util.Set<Integer> failedOrdinals = new java.util.TreeSet<Integer>();
			for (int position = notWritten.nextSetBit(0); position >= 0; position = notWritten.nextSetBit(position + 1))
			{
				failedOrdinals.add(Integer.valueOf(firstOrdinal + position));
			}
			this.reportHandled(firstOrdinal, this.index, failedOrdinals);
			if (!this.repo.saveRunState(this.session, this.feed, this.runId, "RUNNING", this.outcome, this.index, null,
				"connectors:" + this.runId + ":" + this.feed.getId() + ":resume"))
			{
				// the row's counters and resume position are those of an earlier batch until the next write
				this.outcome.bookkeepingLost(Bookkeeping.RUN_STATE);
			}
			this.breaker.record(summary.fullyFailed());
			if (this.breaker.isTripped())
			{
				this.stopReason = "Import stopped after " + this.breaker.getConsecutiveFailures()
					+ " consecutive fully-failed batches, having processed " + this.index + " item(s)";
				return false;
			}
			if (ConnectorService.isCancelled(this.runId))
			{
				this.stopReason = "Import cancelled after " + this.index + " item(s)";
				return false;
			}
			return true;
		}

		/** Commits a leftover partial chunk once the source is exhausted or stopped. No-op when there is none, or the sink already stopped. */
		void finish()
		{
			if (this.stopReason == null && !this.chunk.isEmpty())
			{
				this.flushChunk();
			}
		}

		/**
		 * A real run tells the source which of the items it delivered are written (a Kafka connector commits
		 * offsets on it); a simulation writes nothing, so it cannot, and the source then commits nothing.
		 */
		@Override
		public boolean onHandled(final Connector.HandledListener pListener)
		{
			if (this.dryRun)
			{
				return false;
			}
			this.handledListener = pListener;
			return true;
		}

		@Override
		public void endOfRows()
		{
			this.finish();
		}

		/** A line the source has for the run, kept like the engine's own notices; nothing is written to the sync log by a simulation. */
		@Override
		public void notice(final String pMessage)
		{
			final SyncResult notice = new SyncResult(this.feed.getConnectorId(), this.feed.getOperation(), "IMPORT",
				this.dataset.getHome().getKey().getName(), this.feed.getTablePath(), "", this.feed.getLabel());
			notice.setStatus(SyncResult.Status.SKIPPED);
			notice.setRunId(this.runId);
			notice.setMappingId(String.valueOf(this.feed.getId()));
			notice.setMessage(SampleValueSafety.safeFailure(this.feed, pMessage));
			ConnectorService.tallyItem(this.outcome, notice);
			if (!this.dryRun)
			{
				ConnectorService.logInto(this.outcome, this.repo, this.session, notice, this.origin);
			}
		}

		private void reportHandled(final int pFrom, final int pTo, final java.util.Set<Integer> pNotWritten)
		{
			if (this.handledListener == null)
			{
				return;
			}
			try
			{
				this.handledListener.handled(pFrom, pTo, pNotWritten);
			}
			catch (final RuntimeException ex)
			{
				// The source then holds back what it could not settle; the import itself is not at fault.
				ConnectorService.log().warn("[connectors] the source could not settle items " + pFrom + ".." + pTo + " of " + this.runId + ": "
					+ Redaction.redact(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			}
		}
	}

	/**
	 * First record of a feed's key predicate. EBX's own messages ("An unknown predicate operator has
	 * been detected: =.") name neither the predicate nor the table, so they are wrapped with both.
	 */
	static Adaptation locate(final Session pSession, final AdaptationTable pTable, final String pPredicate) throws ConnectorException
	{
		return FeedLookups.locate(pSession, pTable, pPredicate);
	}

	static String predicateFailure(final String pTablePath, final String pPredicate, final String pReason)
	{
		return FeedLookups.predicateFailure(pTablePath, pPredicate, pReason);
	}

	/**
	 * A feed's per-row lookup: the record the row addresses, found through {@link FeedKeyLookup} (the primary key, an
	 * in-memory business-key index, or the XPath predicate, whichever can answer exactly). {@code pLookup} is null for
	 * a feed whose predicate is neither: the XPath predicate is used. Null when the predicate is empty (the row is new).
	 */
	private static Adaptation locateForImport(final Session pSession, final AdaptationTable pTable, final JsonNode pItem,
		final String pPredicate, final FeedKeyLookup pLookup) throws ConnectorException
	{
		if (pLookup == null)
		{
			return pPredicate.isEmpty() ? null : ConnectorService.locate(pSession, pTable, pPredicate);
		}
		return pLookup.locate(pSession, pTable, pItem, pPredicate);
	}

	private SyncResult importItem(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed,
		final AdaptationTable pTable, final JsonNode pItem, final boolean pDryRun, final String pOrigin,
		final FeedKeyLookup pPrimaryKeyLookup, final TransformationRunCache pRunCache)
	{
		final long start = System.currentTimeMillis();
		final Adaptation dataset = pTable.getContainerAdaptation();
		final String predicate = ConnectorService.resolvePredicate(pFeed.getKeyPredicate(), pItem);
		final SyncResult result = new SyncResult(pFeed.getConnectorId(), pFeed.getOperation(), "IMPORT",
			dataset.getHome().getKey().getName(), pFeed.getTablePath(), predicate, predicate);
		// Volume lot 7a: this path (dry run, or batchSize<=1) used to leave runId/mappingId unset - the
		// one behavioural difference from the batched path §2.9 called out; now set uniformly so a
		// caller reading ImportOutcome.getResults() sees the same shape regardless of batchSize.
		result.setRunId(ConnectorService.currentRunId());
		result.setMappingId(String.valueOf(pFeed.getId()));
		try
		{
			final Adaptation existing = ConnectorService.locateForImport(pSession, pTable, pItem, predicate, pPrimaryKeyLookup);
			if (existing == null && !pFeed.isCreateIfMissing())
			{
				result.setStatus(SyncResult.Status.SKIPPED);
				result.setMessage("No record matches " + predicate + " and creation is off");
				return result;
			}
			final TransformationContext tctx = new TransformationContext(dataset, existing, pSession);
			tctx.setCache(pRunCache);
			ConnectorService.trustify(tctx, pRepo, pFeed);
			// Computed once and reused by both the dry-run report and the real-run write below (it used
			// to run a second time inside the procedure) - also what task 3's unchangedIfEqual check needs.
			final MappingEngine.Values values = MappingEngine.compute(pFeed, pItem, tctx);
			if (MappingEngine.unchanged(pFeed, existing, values))
			{
				// Task 3: skip the write entirely (no doModifyContent, no provenance) - but no early
				// return here, unlike the two branches below: this falls through to the ordinary
				// end-of-method duration/SyncLog handling, the exact same treatment any other
				// NO_CHANGE result gets.
				result.setStatus(SyncResult.Status.NO_CHANGE);
				result.setMessage("unchanged (" + pFeed.getUnchangedIfEqual() + ")");
			}
			else if (pDryRun)
			{
				for (final Map.Entry<String, String> e : values.getByPath().entrySet())
				{
					result.getChanges().add(new MappingResult.Change((existing == null ? "+ " : "") + e.getKey(), null, e.getValue()));
				}
				result.getErrors().addAll(values.getErrors());
				ConnectorService.simulateChildren(pFeed, pItem, tctx, result);
				result.setStatus(SyncResult.Status.DRY_RUN);
				result.setMessage((existing == null ? "would create" : "would update " + existing.getLabel(java.util.Locale.getDefault())) + " (not written)");
				return result;
			}
			else
			{
				final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
				service.setSessionTrackingInfo("Connector import " + pFeed.getConnectorId() + "/" + pFeed.getOperation());
				final Exception[] failure = new Exception[1];
				final Adaptation[] written = new Adaptation[1];
				final ProcedureResult procedureResult = service.execute(new Procedure()
				{
					@Override
					public void execute(final ProcedureContext pContext) throws Exception
					{
						try
						{
							Adaptation record = existing;
							if (existing == null)
							{
								final ValueContextForUpdate created = pContext.getContextForNewOccurrence(pTable);
								final MappingResult applied = MappingEngine.applyValues(values, null, created, pTable.getTableOccurrenceRootNode());
								result.absorb(applied);
								record = pContext.doCreateOccurrence(created, pTable);
								result.setMessage("created " + record.getLabel(java.util.Locale.getDefault()) + ", " + applied.summary());
								result.setCreated(true);
							}
							else
							{
								final ValueContextForUpdate update = pContext.getContext(existing.getAdaptationName());
								final MappingResult applied = MappingEngine.applyValues(values, existing, update, pTable.getTableOccurrenceRootNode());
								result.absorb(applied);
								if (applied.hasChanges())
								{
									record = pContext.doModifyContent(existing, update);
								}
								result.setMessage("updated " + existing.getLabel(java.util.Locale.getDefault()) + ", " + applied.summary());
								result.setCreated(false);
							}
							final TransformationContext childContext = new TransformationContext(dataset, record, pSession);
							childContext.setCache(pRunCache);
							ConnectorService.trustify(childContext, pRepo, pFeed);
							MappingEngine.applyChildren(pContext, pFeed, pItem, record, childContext, result);
							written[0] = record;
						}
						catch (final Exception ex)
						{
							failure[0] = ex;
							throw ex;
						}
					}
				});
				if (procedureResult.hasFailed())
				{
					throw failure[0] != null ? failure[0]
							: new ConnectorException(String.valueOf(procedureResult.getExceptionFullMessage(java.util.Locale.getDefault())));
				}
				result.setStatus(result.getChanges().isEmpty() ? SyncResult.Status.NO_CHANGE : SyncResult.Status.OK);
				if (written[0] != null && pPrimaryKeyLookup != null && (result.isCreated() || !result.getChanges().isEmpty()))
				{
					pPrimaryKeyLookup.wrote(pTable, written[0]);
				}
				if (written[0] != null && (!result.getChanges().isEmpty() || !result.getConflicts().isEmpty()))
				{
					final ConnectorConfig config = ConnectorService.runConnectorConfig(pRunCache, pRepo, pFeed.getConnectorId());
					final Adaptation fresh = pTable.lookupAdaptationByPrimaryKey(written[0].getOccurrencePrimaryKey());
					if (!pRepo.recordProvenance(pSession, fresh == null ? written[0] : fresh, pFeed, config, result.getChanges()))
					{
						result.bookkeepingLost(Bookkeeping.PROVENANCE);
					}
					if (!pRepo.logConflicts(pSession, fresh == null ? written[0] : fresh, pFeed, config, result.getConflicts()))
					{
						result.bookkeepingLost(Bookkeeping.CONFLICTS);
					}
				}
			}
		}
		catch (final Exception ex)
		{
			result.setStatus(SyncResult.Status.ERROR);
			result.setMessage(SampleValueSafety.safeFailure(pFeed, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
		}
		result.setDurationMs(System.currentTimeMillis() - start);
		if (!pDryRun && !pRepo.log(pSession, result, pOrigin))
		{
			result.bookkeepingLost(Bookkeeping.SYNC_LOG);
		}
		return result;
	}

	// ------------------------------------------------------------------ volume lot 6: batched real-run import

	/**
	 * One feed item's lookup, mapping computation and (once committed) write outcome - the same work
	 * {@link #importItem} does for one row, factored out so the batched real-run path
	 * ({@link FeedSink}/{@link #commitBatch}) can prepare a whole batch's worth outside any
	 * procedure (task 1) and commit it with as few procedures as {@link BatchCommit} needs (task 2).
	 * {@link #importItem} itself is untouched by this lot - it stays the dry-run and
	 * {@code batchSize<=1} path exactly as before.
	 */
	private static final class FeedItem
	{
		final JsonNode item;
		final String predicate;
		final SyncResult result;
		final Adaptation existing;
		final TransformationContext context;
		final MappingEngine.Values values;
		/** Nothing more to do: already SKIPPED, already NO_CHANGE, or preparation itself failed (ERROR). */
		final boolean skip;
		long startedAt;
		/** The item's own commit failed (it is the row responsible for a rolled-back batch): nothing of it is in EBX. */
		boolean failed;
		/** Set by {@link #writeFeedItem} once its procedure actually commits; null while unwritten or on failure. */
		Adaptation written;
		boolean created;

		FeedItem(final JsonNode pItem, final String pPredicate, final SyncResult pResult, final Adaptation pExisting,
			final TransformationContext pContext, final MappingEngine.Values pValues, final boolean pSkip)
		{
			this.item = pItem;
			this.predicate = pPredicate;
			this.result = pResult;
			this.existing = pExisting;
			this.context = pContext;
			this.values = pValues;
			this.skip = pSkip;
		}

		/**
		 * Clears whatever a previous, rolled-back commit attempt staged on this item's result. A batch
		 * that fails is bisected and retried (BatchCommit), which can call {@link #writeFeedItem} again
		 * for the very same item after an earlier attempt's procedure rolled back: that earlier attempt's
		 * changes/errors/conflicts were never actually written, so they must never accumulate onto a
		 * later, real attempt.
		 */
		void resetForRetry()
		{
			this.result.getChanges().clear();
			this.result.getErrors().clear();
			this.result.getConflicts().clear();
			this.result.setMessage("");
			this.written = null;
		}
	}

	/** Volume lot 6: the lookup + mapping-computation half of {@link #importItem}, usable outside a procedure. */
	private static FeedItem prepareFeedItem(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed,
		final AdaptationTable pTable, final Adaptation pDataset, final JsonNode pItem, final String pPredicate, final FeedKeyLookup pPrimaryKeyLookup,
		final TransformationRunCache pRunCache)
	{
		final SyncResult result = new SyncResult(pFeed.getConnectorId(), pFeed.getOperation(), "IMPORT",
			pDataset.getHome().getKey().getName(), pFeed.getTablePath(), pPredicate, pPredicate);
		result.setRunId(ConnectorService.currentRunId());
		result.setMappingId(String.valueOf(pFeed.getId()));
		try
		{
			final Adaptation existing = ConnectorService.locateForImport(pSession, pTable, pItem, pPredicate, pPrimaryKeyLookup);
			if (existing == null && !pFeed.isCreateIfMissing())
			{
				result.setStatus(SyncResult.Status.SKIPPED);
				result.setMessage("No record matches " + pPredicate + " and creation is off");
				return new FeedItem(pItem, pPredicate, result, null, null, null, true);
			}
			final TransformationContext tctx = new TransformationContext(pDataset, existing, pSession);
			tctx.setCache(pRunCache);
			ConnectorService.trustify(tctx, pRepo, pFeed);
			final MappingEngine.Values values = MappingEngine.compute(pFeed, pItem, tctx);
			if (MappingEngine.unchanged(pFeed, existing, values))
			{
				result.setStatus(SyncResult.Status.NO_CHANGE);
				result.setMessage("unchanged (" + pFeed.getUnchangedIfEqual() + ")");
				return new FeedItem(pItem, pPredicate, result, existing, tctx, values, true);
			}
			return new FeedItem(pItem, pPredicate, result, existing, tctx, values, false);
		}
		catch (final Exception ex)
		{
			result.setStatus(SyncResult.Status.ERROR);
			result.setMessage(SampleValueSafety.safeFailure(pFeed, ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			return new FeedItem(pItem, pPredicate, result, null, null, null, true);
		}
	}

	/**
	 * Volume lot 6: creates or updates the record for one prepared, non-skip feed item and applies its
	 * sub-objects, inside a caller-supplied procedure - the batched real-run path's equivalent of
	 * {@link #importItem}'s own procedure body, written so several items can share one procedure
	 * ({@link BatchCommit} decides how many are attempted together). Always resets the item's own
	 * result first (see {@link FeedItem#resetForRetry}).
	 */
	private static void writeFeedItem(final ProcedureContext pContext, final AdaptationTable pTable, final Adaptation pDataset,
		final Session pSession, final MappingDefinition pFeed, final ConnectorRepository pRepo, final FeedItem pItem,
		final TransformationRunCache pRunCache, final ChildRowPreload pChildren) throws Exception
	{
		pItem.resetForRetry();
		Adaptation record = pItem.existing;
		if (pItem.existing == null)
		{
			final ValueContextForUpdate created = pContext.getContextForNewOccurrence(pTable);
			final MappingResult applied = MappingEngine.applyValues(pItem.values, null, created, pTable.getTableOccurrenceRootNode());
			pItem.result.absorb(applied);
			record = pContext.doCreateOccurrence(created, pTable);
			pItem.result.setMessage("created " + record.getLabel(java.util.Locale.getDefault()) + ", " + applied.summary());
			pItem.created = true;
			pItem.result.setCreated(true);
		}
		else
		{
			final ValueContextForUpdate update = pContext.getContext(pItem.existing.getAdaptationName());
			final MappingResult applied = MappingEngine.applyValues(pItem.values, pItem.existing, update, pTable.getTableOccurrenceRootNode());
			pItem.result.absorb(applied);
			if (applied.hasChanges())
			{
				record = pContext.doModifyContent(pItem.existing, update);
			}
			pItem.result.setMessage("updated " + pItem.existing.getLabel(java.util.Locale.getDefault()) + ", " + applied.summary());
			pItem.created = false;
			pItem.result.setCreated(false);
		}
		final TransformationContext childContext = new TransformationContext(pDataset, record, pSession);
		childContext.setCache(pRunCache);
		ConnectorService.trustify(childContext, pRepo, pFeed);
		MappingEngine.applyChildren(pContext, pFeed, pItem.item, record, childContext, pItem.result, pChildren, pItem.existing == null);
		pItem.written = record;
	}

	/**
	 * Folds one finalized feed item's outcome into its batch's counts (task 3, lot 6) and, since
	 * volume lot 7a, into the whole run's {@link ImportOutcome} - the same classification, done once,
	 * fed to both. {@code pOutcome} may be null (a caller that only wants the batch summary, e.g. a
	 * test): the run-level fold is then simply skipped.
	 */
	private static void tallyInto(final ImportBatchSummary pSummary, final ImportOutcome pOutcome, final FeedItem pItem)
	{
		if (pOutcome != null)
		{
			ConnectorService.tallyItem(pOutcome, pItem.result);
		}
		final SyncResult.Status status = pItem.result.getStatus();
		if (status == SyncResult.Status.SKIPPED)
		{
			pSummary.rejected();
		}
		else if (status == SyncResult.Status.NO_CHANGE)
		{
			pSummary.noChange();
		}
		else if (status == SyncResult.Status.ERROR || status == SyncResult.Status.PARTIAL)
		{
			pSummary.error();
		}
		else if (pItem.created)
		{
			pSummary.created();
		}
		else
		{
			pSummary.updated();
		}
	}

	/**
	 * Volume lot 7a: classifies one feed item's finished {@link SyncResult} into the run's
	 * {@link ImportOutcome} - shared by the per-item immediate path ({@link FeedSink#accept}, via
	 * {@link #importItem}) and the batched path ({@link #tallyInto}, via {@link #writeFeedItem}/
	 * {@link #prepareFeedItem}). Always folds the result into the outcome's bounded samples first
	 * ({@link ImportOutcome#addResult}), then increments exactly one counter.
	 */
	private static void tallyItem(final ImportOutcome pOutcome, final SyncResult pResult)
	{
		// Volume lot 7b: addResult itself also feeds the catalog-scan tally when enabled for this run
		// (ImportOutcome.enableCatalogTally/getCatalogTally) - a no-op otherwise.
		pOutcome.addResult(pResult);
		final SyncResult.Status status = pResult.getStatus();
		if (status == SyncResult.Status.SKIPPED)
		{
			pOutcome.rejected();
		}
		else if (status == SyncResult.Status.NO_CHANGE)
		{
			pOutcome.noChange();
		}
		else if (status == SyncResult.Status.ERROR || status == SyncResult.Status.PARTIAL)
		{
			pOutcome.error();
		}
		else if (pResult.isCreated())
		{
			pOutcome.created();
			ConnectorService.noteCdcWritten(pResult);
		}
		else
		{
			pOutcome.updated();
			ConnectorService.noteCdcWritten(pResult);
		}
	}

	/** Real-time mastering: the key predicates of the records a cdc batch created or updated, collected on the run's thread
	 * while {@link #runCdc} asks for them (null otherwise: no other import pays for it). */
	private static final ThreadLocal<java.util.LinkedHashSet<String>> CDC_WRITTEN = new ThreadLocal<java.util.LinkedHashSet<String>>();

	private static void noteCdcWritten(final SyncResult pResult)
	{
		final java.util.LinkedHashSet<String> written = ConnectorService.CDC_WRITTEN.get();
		if (written != null && pResult.getRecordKey() != null && !pResult.getRecordKey().trim().isEmpty())
		{
			written.add(pResult.getRecordKey());
		}
	}

	/**
	 * Tells the feed's key lookup about the records a committed segment wrote, before the next item is located: a
	 * later item with the same business key must find the record this one made (as the XPath predicate would).
	 */
	private static void noteWritten(final FeedKeyLookup pLookup, final AdaptationTable pTable, final List<FeedItem> pItems)
	{
		if (pLookup == null)
		{
			return;
		}
		for (final FeedItem item : pItems)
		{
			if (!item.failed && item.written != null && (item.created || !item.result.getChanges().isEmpty()))
			{
				pLookup.wrote(pTable, item.written);
			}
		}
	}

	/**
	 * Writes the prepared items of one segment through {@link BatchCommit} (one procedure, bisected on failure)
	 * and gives each its status: written, or in error with the message of its own failure. Does nothing for an
	 * empty list.
	 */
	private static void commitFeedItems(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed,
		final AdaptationTable pTable, final Adaptation pDataset, final TransformationRunCache pRunCache, final List<FeedItem> pItems,
		final String pTrackingInfo)
	{
		if (pItems.isEmpty())
		{
			return;
		}
		// The existing children of this segment's parents, read once here and not by an XPath per child element inside the
		// procedure (audit C-11); null when the feed has no sub-object mapping that can use it.
		final List<Adaptation> existingParents = new ArrayList<Adaptation>();
		for (final FeedItem item : pItems)
		{
			if (item.existing != null)
			{
				existingParents.add(item.existing);
			}
		}
		final ChildRowPreload children = ChildRowPreload.load(pSession, pFeed, pDataset, pTable, existingParents);
		final BatchCommit.Executor<FeedItem> executor = new BatchCommit.Executor<FeedItem>()
		{
			@Override
			public void commit(final List<FeedItem> pBatch) throws Exception
			{
				final ProgrammaticService service = ProgrammaticService.createForSession(pSession, pDataset.getHome());
				service.setSessionTrackingInfo(pTrackingInfo);
				final Exception[] failure = new Exception[1];
				final ProcedureResult procedureResult = service.execute(new Procedure()
				{
					@Override
					public void execute(final ProcedureContext pContext) throws Exception
					{
						try
						{
							if (children != null)
							{
								children.beginAttempt(); // a rolled-back attempt left nothing in EBX: the rows are as they were read
							}
							for (final FeedItem item : pBatch)
							{
								ConnectorService.writeFeedItem(pContext, pTable, pDataset, pSession, pFeed, pRepo, item, pRunCache, children);
							}
						}
						catch (final Exception ex)
						{
							failure[0] = ex;
							throw ex;
						}
					}
				});
				if (procedureResult.hasFailed())
				{
					throw failure[0] != null ? failure[0]
							: new ConnectorException(String.valueOf(procedureResult.getExceptionFullMessage(java.util.Locale.getDefault())));
				}
			}
		};
		final BatchCommit.Result<FeedItem> outcome = BatchCommit.commit(new ArrayList<FeedItem>(pItems), executor);
		for (final FeedItem item : outcome.getCommitted())
		{
			item.result.setStatus(item.result.getChanges().isEmpty() ? SyncResult.Status.NO_CHANGE : SyncResult.Status.OK);
		}
		for (final BatchCommit.ItemFailure<FeedItem> failure : outcome.getFailures())
		{
			failure.getItem().failed = true;
			failure.getItem().result.setStatus(SyncResult.Status.ERROR);
			failure.getItem().result.setMessage(SampleValueSafety.safeFailure(pFeed, failure.getMessage()));
		}
	}

	/**
	 * Volume lot 6: prepares (lookup + compute, outside any procedure - task 1) and commits (via
	 * {@link BatchCommit}, bisected on failure - task 2) one chunk of feed items, then records
	 * provenance for the whole chunk in one further procedure (task 1, no per-row re-lookup) and
	 * either one batch {@code SyncLog} row or one row per record for a {@code logEveryRecord} mapping
	 * (task 3). Sub-object mappings are applied inside the SAME batch procedure as their parent
	 * (inside {@link #writeFeedItem}), never a separate one. Returns the batch's counts for the
	 * caller's circuit breaker; {@code pNotWritten}, when given, receives the positions in the chunk of
	 * the items that are not in EBX (their preparation or their own commit failed).
	 */
	private ImportBatchSummary commitBatch(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pFeed,
		final AdaptationTable pTable, final Adaptation pDataset, final List<JsonNode> pChunk, final String pOrigin,
		final FeedKeyLookup pPrimaryKeyLookup, final TransformationRunCache pRunCache, final String pRunId,
		final int pBatchIndex, final ImportOutcome pOutcome, final java.util.BitSet pNotWritten)
	{
		final String trackingInfo = "connectors:" + pRunId + ":" + pFeed.getId() + ":" + pBatchIndex;
		final List<FeedItem> prepared = new ArrayList<FeedItem>(pChunk.size());
		final List<FeedItem> toWrite = new ArrayList<FeedItem>();
		// Every item is located before the chunk is written, so two items of one chunk with the same key would
		// both find no record and create it twice (audit C-20). A key that comes back ends the segment: what was
		// prepared so far is written first, and the repeated item is then located against the records it made.
		// A chunk with no repeated key is one segment, written once, as before.
		final BatchKeySegments segments = new BatchKeySegments();
		int written = 0;
		for (final JsonNode item : pChunk)
		{
			final String predicate = ConnectorService.resolvePredicate(pFeed.getKeyPredicate(), item);
			if (segments.startsNewSegment(predicate))
			{
				ConnectorService.commitFeedItems(pSession, pRepo, pFeed, pTable, pDataset, pRunCache, toWrite.subList(written, toWrite.size()), trackingInfo);
				ConnectorService.noteWritten(pPrimaryKeyLookup, pTable, toWrite.subList(written, toWrite.size()));
				written = toWrite.size();
			}
			final FeedItem feedItem = ConnectorService.prepareFeedItem(pSession, pRepo, pFeed, pTable, pDataset, item, predicate, pPrimaryKeyLookup, pRunCache);
			feedItem.startedAt = System.currentTimeMillis();
			prepared.add(feedItem);
			if (!feedItem.skip)
			{
				toWrite.add(feedItem);
			}
		}
		ConnectorService.commitFeedItems(pSession, pRepo, pFeed, pTable, pDataset, pRunCache, toWrite.subList(written, toWrite.size()), trackingInfo);
		ConnectorService.noteWritten(pPrimaryKeyLookup, pTable, toWrite.subList(written, toWrite.size()));
		// Task 1: provenance for the whole chunk in one procedure, using the Adaptation writeFeedItem
		// already returned (no re-lookup) - only records actually written, with real changes or conflicts.
		final ConnectorConfig config = ConnectorService.runConnectorConfig(pRunCache, pRepo, pFeed.getConnectorId());
		final List<ConnectorRepository.RecordChange> provenanceEntries = new ArrayList<ConnectorRepository.RecordChange>();
		for (final FeedItem item : toWrite)
		{
			if (!item.failed && item.written != null && (!item.result.getChanges().isEmpty() || !item.result.getConflicts().isEmpty()))
			{
				provenanceEntries.add(new ConnectorRepository.RecordChange(item.written, item.result.getChanges()));
			}
		}
		if (!provenanceEntries.isEmpty())
		{
			if (!pRepo.recordProvenanceBatch(pSession, pFeed, config, provenanceEntries, trackingInfo) && pOutcome != null)
			{
				pOutcome.bookkeepingLost(Bookkeeping.PROVENANCE);
			}
		}
		// Conflicts stay per record, one procedure each, exactly like importItem does today.
		for (final FeedItem item : toWrite)
		{
			if (!item.failed && item.written != null && !item.result.getConflicts().isEmpty())
			{
				if (!pRepo.logConflicts(pSession, item.written, pFeed, config, item.result.getConflicts()) && pOutcome != null)
				{
					pOutcome.bookkeepingLost(Bookkeeping.CONFLICTS);
				}
			}
		}
		final ImportBatchSummary summary = new ImportBatchSummary();
		for (int position = 0; position < prepared.size(); position++)
		{
			final FeedItem item = prepared.get(position);
			if (pNotWritten != null && (item.failed || item.skip && item.result.getStatus() == SyncResult.Status.ERROR))
			{
				pNotWritten.set(position);
			}
			item.result.setDurationMs(System.currentTimeMillis() - item.startedAt);
			ConnectorService.lineage(pSession, pRepo, pFeed, item.result);
			summary.addKey(item.predicate);
			ConnectorService.tallyInto(summary, pOutcome, item);
			summary.conflicts(item.result.getConflicts().size());
			// Task 3: an OK/NO_CHANGE/SKIPPED record's own line disappears into the batch row below;
			// an ERROR, PARTIAL or conflict-bearing one still gets its own line, same as importItem
			// always did - logEveryRecord restores every line, as before batching.
			final boolean logIndividually = pFeed.isLogEveryRecord() || item.result.hasFailure() || !item.result.getConflicts().isEmpty();
			if (logIndividually)
			{
				// the batch line below counts this record for the health metrics, unless there is no batch line (logEveryRecord)
				item.result.setCountedByBatch(!pFeed.isLogEveryRecord());
				ConnectorService.logInto(pOutcome, pRepo, pSession, item.result, pOrigin);
			}
		}
		if (!pFeed.isLogEveryRecord())
		{
			final SyncResult batchResult = new SyncResult(pFeed.getConnectorId(), pFeed.getOperation(), "IMPORT",
				pDataset.getHome().getKey().getName(), pFeed.getTablePath(), summary.recordKey(),
				"batch " + pBatchIndex + " (" + pChunk.size() + " item(s))");
			batchResult.setRunId(pRunId);
			batchResult.setMappingId(String.valueOf(pFeed.getId()));
			batchResult.setStatus(summary.status());
			batchResult.setBatch(summary);
			batchResult.setMessage(summary.message());
			batchResult.setDurationMs(System.currentTimeMillis() - prepared.get(0).startedAt);
			ConnectorService.logInto(pOutcome, pRepo, pSession, batchResult, pOrigin);
		}
		return summary;
	}

	/**
	 * One {@code SKIPPED} result (and matching SyncLog line) for items a stopped import never
	 * attempted, folded into the run's {@link ImportOutcome}. Volume lot 7a: with a truly streamed
	 * source (no materialised {@code List<JsonNode>} to measure), the exact number of items left
	 * unattempted is often not knowable without exhausting the source, so the outcome's
	 * {@code skipped} counter used to be nudged by a nominal, non-zero 1 unconditionally, just so
	 * {@link ImportOutcome#status()} never reports a stopped run as a clean success.
	 * <p>
	 * Volume lot 7b: when the source DID hand back an availability hint (Snowflake's
	 * {@code resultSetMetaData.numRows}, known before a single partition is even fetched; a file feed
	 * that counted its own record boundaries while parsing - see {@code Csv.parse}) the true remainder
	 * ({@code pAvailable - pProcessed}, never less than 1: the run WAS stopped, so at least one item was
	 * left) is reported instead - see {@link #notProcessedRemainder}. Most connectors still have no such
	 * hint on a sink-initiated stop (JDBC's native cursor never counts a total past its probe row, and
	 * the default {@code Connector.replay} path deliberately does not keep counting past the sink's own
	 * stop - see its "an external stop is not truncation" contract, {@code ConnectorRowStreamTest}), so
	 * the nominal 1 remains the common case - this is a documented approximation, not a claim that every
	 * connector now reports an exact count.
	 */
	private static void addNotProcessedNotice(final Session pSession, final ConnectorRepository pRepo, final ImportOutcome pOutcome,
		final MappingDefinition pFeed, final Adaptation pDataset, final String pRunId, final String pOrigin, final String pMessage,
		final long pAvailable, final int pProcessed)
	{
		final SyncResult notice = new SyncResult(pFeed.getConnectorId(), pFeed.getOperation(), "IMPORT",
			pDataset.getHome().getKey().getName(), pFeed.getTablePath(), "", pFeed.getLabel());
		notice.setStatus(SyncResult.Status.SKIPPED);
		notice.setRunId(pRunId);
		notice.setMappingId(String.valueOf(pFeed.getId()));
		final long remainder = ConnectorService.notProcessedRemainder(pAvailable, pProcessed);
		// Deliberately never notice.setAvailable(...) here: that field is SyncResult.isLimitNotice()'s
		// own signal ("the source held more than max"), which ImportOutcome.addResult folds into
		// truncated/available - a cancellation/breaker stop must never be reported as that kind of
		// truncation (see Connector.RowStreamResult's own "an external stop is not truncation" contract).
		notice.setMessage(pAvailable >= 0
			? pMessage + "; " + remainder + " item(s) of " + pAvailable + " available were left unprocessed"
			: pMessage);
		pOutcome.addResult(notice);
		pOutcome.skipped((int) Math.min(Integer.MAX_VALUE, remainder));
		ConnectorService.logInto(pOutcome, pRepo, pSession, notice, pOrigin);
	}

	/**
	 * Volume lot 7b: the exact remainder to report for a stopped run when the source gave an
	 * availability hint ({@code pAvailable >= 0}, {@code pAvailable - pProcessed}, floored at 1 since
	 * the run WAS stopped before the source was exhausted); the nominal, non-zero 1 otherwise. Pure,
	 * unit-tested ({@code ConnectorRowStreamTest}/{@code ImportOutcomeTest} family).
	 */
	static long notProcessedRemainder(final long pAvailable, final int pProcessed)
	{
		if (pAvailable < 0)
		{
			return 1;
		}
		final long remainder = pAvailable - pProcessed;
		return remainder > 0 ? remainder : 1;
	}

	private static ValueTemplates.Values placeholderValues(final Adaptation pRecord, final TransformationContext pContext)
	{
		return new ValueTemplates.Values()
		{
			@Override
			public String of(final String pExpression)
			{
				return Expressions.resolve("${" + pExpression + "}", pRecord, null, pContext);
			}
		};
	}

	private static ValueTemplates.Values wholeValues(final Adaptation pRecord, final TransformationContext pContext)
	{
		return new ValueTemplates.Values()
		{
			@Override
			public String of(final String pTemplate)
			{
				return Expressions.resolve(pTemplate, pRecord, null, pContext);
			}
		};
	}

	/** Volume lot 5: was recompiled on every call (once per imported row); now compiled once. */
	private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");

	/** What a placeholder stands for in a key predicate: the item's value at that path, empty when there is none. */
	static String placeholderText(final JsonNode pItem, final String pExpression)
	{
		return FeedLookups.placeholderText(pItem, pExpression);
	}

	/**
	 * {@code ./f='${data.id}'} with placeholders read from the item. Audit C2-04: every value is quoted as a whole XPath literal
	 * ({@link ValueTemplates#xpath}), so it can never leave the literal it was written in.
	 */
	public static String resolvePredicate(final String pTemplate, final JsonNode pItem)
	{
		return ValueTemplates.xpath(pTemplate, new ValueTemplates.Values()
		{
			@Override
			public String of(final String pExpression)
			{
				return ConnectorService.placeholderText(pItem, pExpression);
			}
		});
	}

	/**
	 * Audit C2-05: one mapping input, resolved. A connector that binds the input as SQL ({@link Connector#bindsWatermark}) gets
	 * every {@code ${...}} turned into a bound parameter ({@link ValueTemplates#sql}); the values are added to {@code pBound}, to be
	 * passed to the connector as inputs. Any other input is resolved as text, as before.
	 */
	private static String resolveInput(final Connector pConnector, final String pOperation, final String pName, final String pTemplate,
		final Map<String, String> pBound, final ValueTemplates.Values pPlaceholder, final ValueTemplates.Values pWhole)
	{
		if (pTemplate != null && pConnector.bindsWatermark(pOperation, pName))
		{
			return ValueTemplates.sql(pTemplate, pPlaceholder, pBound).trim();
		}
		return pWhole.of(pTemplate).trim();
	}

	static Operation operation(final Connector pConnector, final String pName)
	{
		for (final Operation operation : pConnector.getOperations())
		{
			if (operation.getName().equals(pName))
			{
				return operation;
			}
		}
		return null;
	}

	public static LoggingCategory log()
	{
		return LoggingCategory.getKernel();
	}
}
