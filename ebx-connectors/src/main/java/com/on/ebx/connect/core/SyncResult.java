package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

/** Outcome of synchronising one record through one mapping. */
public final class SyncResult
{
	public enum Status
	{
		OK, NO_CHANGE, NO_DATA, SKIPPED, ERROR, DRY_RUN, PARTIAL
	}

	private final String connectorId;
	private final String operation;
	private final String direction;
	private final String dataspace;
	private final String tablePath;
	private final String recordKey;
	private final String recordLabel;
	private Status status = Status.OK;
	private String message = "";
	private final List<MappingResult.Change> changes = new ArrayList<MappingResult.Change>();
	private final List<String> errors = new ArrayList<String>();
	private long durationMs;
	private String runId;
	private String mappingId;
	private String outboundDocument;

	public SyncResult(final String pConnectorId, final String pOperation, final String pDirection,
		final String pDataspace, final String pTablePath, final String pRecordKey, final String pRecordLabel)
	{
		this.connectorId = pConnectorId;
		this.operation = pOperation;
		this.direction = pDirection;
		this.dataspace = pDataspace;
		this.tablePath = pTablePath;
		this.recordKey = pRecordKey;
		this.recordLabel = pRecordLabel;
	}

	public String getConnectorId()
	{
		return this.connectorId;
	}

	public String getOperation()
	{
		return this.operation;
	}

	public String getDirection()
	{
		return this.direction;
	}

	public String getDataspace()
	{
		return this.dataspace;
	}

	public String getTablePath()
	{
		return this.tablePath;
	}

	public String getRecordKey()
	{
		return this.recordKey;
	}

	public String getRecordLabel()
	{
		return this.recordLabel;
	}

	public Status getStatus()
	{
		// Mapping errors do not roll back valid fields. Never present that partial
		// commit (or incomplete simulation) as a clean success.
		if (!this.errors.isEmpty() && (this.status == Status.OK
			|| this.status == Status.NO_CHANGE))
		{
			return Status.PARTIAL;
		}
		return this.status;
	}

	public void setStatus(final Status pStatus)
	{
		this.status = pStatus;
	}

	public String getMessage()
	{
		return this.message;
	}

	public void setMessage(final String pMessage)
	{
		this.message = pMessage == null ? "" : pMessage;
	}

	public List<MappingResult.Change> getChanges()
	{
		return this.changes;
	}

	public List<String> getErrors()
	{
		return this.errors;
	}

	public long getDurationMs()
	{
		return this.durationMs;
	}

	public void setDurationMs(final long pDurationMs)
	{
		this.durationMs = pDurationMs;
	}

	private final List<MappingResult.Conflict> conflicts = new java.util.ArrayList<MappingResult.Conflict>();

	public List<MappingResult.Conflict> getConflicts()
	{
		return this.conflicts;
	}

	public void absorb(final MappingResult pResult)
	{
		this.changes.addAll(pResult.getChanges());
		this.errors.addAll(pResult.getErrors());
		this.conflicts.addAll(pResult.getConflicts());
	}

	public String changesAsText()
	{
		final StringBuilder sb = new StringBuilder();
		for (final MappingResult.Change change : this.changes)
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append(change.toString());
		}
		for (final String error : this.errors)
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append("ERROR ").append(error);
		}
		return sb.toString();
	}

	public boolean isSuccess()
	{
		final Status effective = this.getStatus();
		return !this.hasFailure() && (effective == Status.OK || effective == Status.NO_CHANGE || effective == Status.DRY_RUN);
	}

	/** The records a batch line stands for (audit C-41); null for a line that is one call or one record. */
	private ImportBatchSummary batch;
	/** True for the own line of a record that the batch line of its run already counts. */
	private boolean countedByBatch;

	/** The tally of the batch this line summarises, null for any other line. */
	public ImportBatchSummary getBatch()
	{
		return this.batch;
	}

	public void setBatch(final ImportBatchSummary pBatch)
	{
		this.batch = pBatch;
	}

	/**
	 * A feed run in batches writes one line per batch, plus an own line for each failing or conflicting record. The health
	 * metrics count the records of the batch line, so those own lines are not counted a second time.
	 */
	public boolean isCountedByBatch()
	{
		return this.countedByBatch;
	}

	public void setCountedByBatch(final boolean pCounted)
	{
		this.countedByBatch = pCounted;
	}

	/**
	 * Whether the health metrics and the repeated-failure alert count this line as a failure: a call or a record that failed, and for a
	 * batch line only a batch in which every attempted write failed (the circuit breaker's own signal). A batch with some failed
	 * records is not a failed batch: its failures are in its counts, and each has its own line.
	 */
	public boolean countsAsFailure()
	{
		return this.batch != null ? this.batch.fullyFailed() : this.hasFailure();
	}

	/** Partial outcomes retain successful changes; they must not be retried as an atomic failure. */
	public boolean hasFailure()
	{
		return this.getStatus() == Status.ERROR || this.getStatus() == Status.PARTIAL || !this.errors.isEmpty();
	}

	/** Single result aggregation contract for REST, MCP and execution observers. */
	public static final class Summary
	{
		public int ok, errors, partial, skipped, noData;
		/** True when any result is a "limit reached" notice ({@link SyncResult#isLimitNotice()}): a feed, a table or a scope cut at max. */
		public boolean truncated;
		/** The message of the first "limit reached" notice; null when nothing was cut. */
		public String limitMessage;
		/** The largest known total the source reported across every limit notice; -1 when never reported. */
		public long available = -1;
		public String status()
		{
			if (errors > 0 && ok == 0 && partial == 0 && skipped == 0 && noData == 0) return "ERROR";
			if (errors > 0 || partial > 0 || skipped > 0 || noData > 0) return "PARTIAL";
			return ok > 0 ? "OK" : "NO_DATA";
		}
	}

	public static Summary summarize(final List<SyncResult> results)
	{
		final Summary summary = new Summary();
		for (final SyncResult result : results)
		{
			if (result.isLimitNotice())
			{
				summary.truncated = true;
				if (summary.limitMessage == null) summary.limitMessage = result.getMessage();
				if (result.getAvailable() > summary.available) summary.available = result.getAvailable();
			}
			if (result.getStatus() != Status.ERROR && result.hasFailure())
			{
				summary.partial++;
				continue;
			}
			switch (result.getStatus())
			{
				case ERROR: summary.errors++;
				break;
				case PARTIAL: summary.partial++;
				break;
				case SKIPPED: summary.skipped++;
				break;
				case NO_DATA: summary.noData++;
				break;
				default: summary.ok++;
			}
		}
		return summary;
	}

	@Override
	public String toString()
	{
		return this.recordLabel + ": " + this.getStatus() + " (" + this.changes.size() + " change(s)) " + this.message;
	}

	public String getRunId()
	{
		return this.runId;
	}

	public void setRunId(final String pRunId)
	{
		this.runId = pRunId;
	}

	public String getMappingId()
	{
		return this.mappingId;
	}

	public void setMappingId(final String pMappingId)
	{
		this.mappingId = pMappingId;
	}

	/** JSON of the document built for an outbound call (its fingerprint feeds the outbound state). */
	public String getOutboundDocument()
	{
		return this.outboundDocument;
	}

	public void setOutboundDocument(final String pDocument)
	{
		this.outboundDocument = pDocument;
	}

	/** Items the connector returned when this result reports a feed cut at its maximum; -1 otherwise. */
	private int available = -1;

	public int getAvailable()
	{
		return this.available;
	}

	/** Set on a "limit reached" notice whose total is not known (audit C-35): see {@link #markLimitReached()}. */
	private boolean limitReached;

	/**
	 * True for the notice a run adds when the source, the table or the scope held more than its maximum let it take:
	 * a feed whose connector said how many items it returned ({@link #setAvailable}), or any run marked by
	 * {@link #markLimitReached()} (a feed without a known total, records of a table, an outbound scope).
	 */
	public boolean isLimitNotice()
	{
		return this.available >= 0 || this.limitReached;
	}

	public void setAvailable(final int pAvailable)
	{
		this.available = pAvailable;
	}

	/** Marks this result as the "limit reached" notice of its run, when the total beyond the maximum is not known. */
	public void markLimitReached()
	{
		this.limitReached = true;
	}

	/**
	 * The "limit reached" notice of a run on records or an outbound scope that stopped at {@code pMax} with more left:
	 * status SKIPPED (the run is never a plain success), the run and mapping it belongs to, the sentence of
	 * {@link RunLimits#recordsLimitMessage}.
	 */
	public static SyncResult limitNotice(final MappingDefinition pMapping, final String pDirection, final String pRunId, final int pMax, final String pWhat,
		final boolean pDrains)
	{
		final SyncResult notice = new SyncResult(pMapping.getConnectorId(), pMapping.getOperation(), pDirection, pMapping.getDataspace(),
			pMapping.getTablePath(), "", pWhat);
		notice.setStatus(Status.SKIPPED);
		notice.setRunId(pRunId);
		notice.setMappingId(String.valueOf(pMapping.getId()));
		notice.setMessage(RunLimits.recordsLimitMessage(pMax, pWhat, pDrains));
		notice.markLimitReached();
		return notice;
	}

	/** The message of the first "limit reached" notice of {@code pResults}; null when none was cut. */
	public static String limitMessage(final List<SyncResult> pResults)
	{
		if (pResults != null)
		{
			for (final SyncResult result : pResults)
			{
				if (result.isLimitNotice())
				{
					return result.getMessage();
				}
			}
		}
		return null;
	}

	/** The results apart from their "limit reached" notices: what a count of records processed counts. */
	public static int countWithoutLimitNotices(final List<SyncResult> pResults)
	{
		int count = 0;
		for (final SyncResult result : pResults)
		{
			if (!result.isLimitNotice())
			{
				count++;
			}
		}
		return count;
	}

	/** Kinds of bookkeeping rows about this result that could not be saved; null until one is lost. */
	private List<String> bookkeepingLost;

	/**
	 * Notes that a row the module keeps about this result (see {@link Bookkeeping}) could not be
	 * written. The status is left alone: what the result describes did happen.
	 */
	public void bookkeepingLost(final String pKind)
	{
		if (this.bookkeepingLost == null)
		{
			this.bookkeepingLost = new ArrayList<String>(2);
		}
		if (!this.bookkeepingLost.contains(pKind))
		{
			this.bookkeepingLost.add(pKind);
		}
	}

	/** Empty for nearly every result; otherwise what a reader of this result cannot rely on (outbound state, sync log line...). */
	public List<String> getBookkeepingLost()
	{
		return this.bookkeepingLost == null ? java.util.Collections.<String> emptyList() : this.bookkeepingLost;
	}

	/**
	 * Volume lot 7a: only meaningful for a feed import's per-record result (status {@code OK} or
	 * {@code NO_CHANGE}) - whether the record was created rather than updated. A plain status cannot
	 * tell the two apart (both report {@code OK}), and {@link ImportOutcome} needs it to keep its
	 * {@code created}/{@code updated} counters distinct. Unused (default {@code false}) outside feed
	 * imports - {@code syncRecord}, {@code sendScope} and {@code replay} never set it.
	 */
	private boolean created;

	public boolean isCreated()
	{
		return this.created;
	}

	public void setCreated(final boolean pCreated)
	{
		this.created = pCreated;
	}
}
