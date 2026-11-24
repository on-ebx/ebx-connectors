package com.on.ebx.connect.core;

import com.orchestranetworks.service.Session;

/**
 * The state of one outbound scope send that runs on a background thread (wave 13, {@code connectors_sync(scope=..., async=true)}):
 * the counters it keeps in its {@code ExecutionRun} row, the batch boundary where it writes them and looks for a stop, and the
 * terminal status it ends on - the same row and the same {@code execution{}} answer as an asynchronous feed import.
 * <p>
 * The decisions are static and pure ({@link #count}, {@link #boundary}, {@link #atBoundary}, {@link #finalStatus}, {@link #stopReason}),
 * tested without a repository by {@code OutboundRunTest}; the instance only carries them to the row.
 * <p>
 * How a record counts (the Factory's {@code rows} = {@code created + updated + noChange}):
 * <ul>
 * <li>sent (status OK, also a remote deletion) is {@code updated} - a send cannot tell a record the target never saw from one it had;</li>
 * <li>not sent because it did not change since the last send ({@code NO_CHANGE}, and the scope's "unchanged" count) is {@code noChange};</li>
 * <li>any failure ({@link SyncResult#hasFailure()}) is {@code errorCount}, its result kept in the bounded sample behind {@code firstErrors};</li>
 * <li>skipped, and the records of the scope waiting for their retry delay, are {@code skipped};</li>
 * <li>{@code created} stays 0, {@code rejected} stays 0.</li>
 * </ul>
 */
final class OutboundRun
{
	/** The most records between two writes of the row and two looks for a stop, whatever the mapping's {@code batchSize}. */
	static final int MAX_BOUNDARY = 250;

	private final Session session;
	private final ConnectorRepository repo;
	private final MappingDefinition mapping;
	private final String runId;
	private final ImportOutcome outcome;
	private final int boundary;
	private int processed;
	private boolean stopped;
	private String stopReason;

	OutboundRun(final Session pSession, final ConnectorRepository pRepo, final MappingDefinition pMapping, final String pRunId, final ImportOutcome pOutcome)
	{
		this.session = pSession;
		this.repo = pRepo;
		this.mapping = pMapping;
		this.runId = pRunId;
		this.outcome = pOutcome;
		this.boundary = OutboundRun.boundary(pMapping == null ? 0 : pMapping.getBatchSize());
	}

	/** Pure: records between two boundaries - the mapping's {@code batchSize}, at most {@link #MAX_BOUNDARY}, at least 1. */
	static int boundary(final int pMappingBatchSize)
	{
		return Math.max(1, Math.min(pMappingBatchSize <= 0 ? OutboundRun.MAX_BOUNDARY : pMappingBatchSize, OutboundRun.MAX_BOUNDARY));
	}

	/** Pure: true when {@code pProcessed} records were handled and that is a multiple of the boundary: the row is written and a stop looked for. */
	static boolean atBoundary(final int pProcessed, final int pBoundary)
	{
		return pProcessed > 0 && pBoundary > 0 && pProcessed % pBoundary == 0;
	}

	/** Pure: a requested stop takes effect at a batch boundary and nowhere else. */
	static boolean mustStop(final int pProcessed, final int pBoundary, final boolean pCancelRequested)
	{
		return pCancelRequested && OutboundRun.atBoundary(pProcessed, pBoundary);
	}

	/** Pure: folds the result of one record (or one remote deletion) into the counters and the bounded samples. */
	static void count(final ImportOutcome pOutcome, final SyncResult pResult)
	{
		pOutcome.addResult(pResult);
		if (pResult.hasFailure())
		{
			pOutcome.error();
			return;
		}
		switch (pResult.getStatus())
		{
			case OK:
				pOutcome.updated();
				break;
			case NO_CHANGE:
				pOutcome.noChange();
				break;
			default:
				pOutcome.skipped(1);
		}
	}

	/** Pure: what the scope left out of the send - records unchanged since their last send, records waiting for their retry delay. */
	static void countScopeRest(final ImportOutcome pOutcome, final int pUnchanged, final int pWaitingRetry)
	{
		for (int i = 0; i < pUnchanged; i++)
		{
			pOutcome.noChange();
		}
		pOutcome.skipped(pWaitingRetry);
	}

	/** Pure: the terminal {@code ExecutionRun.status}: STOPPED when a stop ended it, else the engine's DONE / PARTIAL / ERROR. */
	static String finalStatus(final ImportOutcome pOutcome, final boolean pStopped)
	{
		return pOutcome.finalStatus(pStopped);
	}

	/** Pure: the message a stopped send carries. */
	static String stopReason(final int pProcessed)
	{
		return "Send stopped (connectors_run_stop) after " + pProcessed + " record(s); the records already sent keep their outbound state";
	}

	/**
	 * Called after each record: counts it and, at a batch boundary, writes the pending bookkeeping (so that the outbound states
	 * of the records sent are saved before the counters say they were), writes the running state, and looks for a stop.
	 *
	 * @return true when the send must stop here
	 */
	boolean recorded(final SyncResult pResult)
	{
		OutboundRun.count(this.outcome, pResult);
		this.processed++;
		this.outcome.setNextIndex(this.processed);
		if (!OutboundRun.atBoundary(this.processed, this.boundary))
		{
			return false;
		}
		final RecordRun run = RecordRun.forSession(this.session);
		if (run != null)
		{
			run.bookkeeping().flush();
		}
		if (!this.repo.saveRunState(this.session, this.mapping, this.runId, "RUNNING", this.outcome, this.processed, null,
			"connectors:" + this.runId + ":" + this.mapping.getId() + ":send"))
		{
			this.outcome.bookkeepingLost(Bookkeeping.RUN_STATE);
		}
		if (OutboundRun.mustStop(this.processed, this.boundary, ConnectorService.isCancelled(this.runId)))
		{
			this.stopped = true;
			this.stopReason = OutboundRun.stopReason(this.processed);
			return true;
		}
		return false;
	}

	/** True once a boundary found the stop requested: the loop ended there, and the run ends STOPPED. */
	boolean isStopped()
	{
		return this.stopped;
	}

	String stopReason()
	{
		return this.stopReason;
	}

	int processed()
	{
		return this.processed;
	}

	ImportOutcome outcome()
	{
		return this.outcome;
	}
}
