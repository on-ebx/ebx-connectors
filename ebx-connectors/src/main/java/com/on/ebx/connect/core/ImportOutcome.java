package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of a feed import run ({@code importFrom}/{@code importDocument}), volume lot 7a.
 * <p>
 * Before this lot every imported row's {@link SyncResult} was kept in one {@code List<SyncResult>}
 * for the whole run's lifetime, and {@link SyncResult#summarize} walked it at the very end to build
 * the counters a caller actually wants - two of the five full per-row copies the 2026-09-28 audit
 * found (the other three are the OpenLineage buffer, the JSON-RPC answer's own {@code results}
 * array, and the caller re-parsing that JSON). This class replaces the run-long list with running
 * counters, updated as each item finishes, plus two small bounded samples:
 * <ul>
 * <li>{@code errors}: the first {@link #getErrorsMax()} results whose effective status is a failure
 * (see {@link SyncResult#hasFailure()}), for a caller that wants to see what actually broke without
 * scanning a huge log.</li>
 * <li>{@code results}: the first {@link #getResultsMax()} results of any status, for a caller that
 * still wants full per-row detail on a modest-sized run; {@link #isResultsOmitted()} is set as soon
 * as the run has more rows than that, so a caller never mistakes a partial sample for the whole run.</li>
 * </ul>
 * Everything past those two caps is only ever reflected in the counters - never held in memory.
 * <p>
 * "skipped" here counts items a run never attempted at all (the item count of a truncation, circuit
 * breaker or cancellation notice - see {@code ConnectorService.addNotProcessedNotice}), distinct
 * from "rejected" (an item the run DID look up and declined to create because the mapping's
 * {@code createIfMissing} is false and nothing matched its key predicate).
 */
public final class ImportOutcome
{
	/** {@code -Debx.connectors.import.resultsMax}: how many individual results {@link #getResults()} keeps. Default 1000. */
	public static int defaultResultsMax()
	{
		return ImportOutcome.intProperty("ebx.connectors.import.resultsMax", 1000);
	}

	/** {@code -Debx.connectors.import.errorsMax}: how many failing results {@link #getErrors()} keeps. Default 50. */
	public static int defaultErrorsMax()
	{
		return ImportOutcome.intProperty("ebx.connectors.import.errorsMax", 50);
	}

	private static int intProperty(final String pName, final int pDefault)
	{
		try
		{
			return Math.max(0, Integer.parseInt(System.getProperty(pName, String.valueOf(pDefault)).trim()));
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}

	private final int resultsMax;
	private final int errorsMax;
	private final List<SyncResult> results = new ArrayList<SyncResult>();
	private final List<SyncResult> errors = new ArrayList<SyncResult>();
	private boolean resultsOmitted;
	/** Volume lot 7b: every {@link #addResult} call, unbounded - unlike {@code results.size()}, never capped at {@link #resultsMax}. */
	private int totalAdded;

	private int created, updated, noChange, errorCount, skipped, rejected, conflicts;
	private boolean truncated;
	private long available = -1;
	private String limitMessage;
	private String runId;
	private int nextIndex;
	private long durationMs;
	private String stopReason;
	/** Volume lot 7b: exact, unbounded per-item classification for a catalog scan's status message - see {@link CatalogScanTally}. Null for a run that is not a catalog scan. */
	private CatalogScanTally catalogTally;

	// ------------------------------------------------------------------ volume lot V2-D: feed watermark

	private boolean watermarkActive;
	private String watermarkField;
	private String watermarkBefore;
	private String watermarkAfter;
	private boolean watermarkAdvanced;
	private boolean watermarkFullRefresh;
	private String watermarkNote;
	private int skippedByWatermark;

	/**
	 * Marks this run as an incremental feed read: the checkpoint it started from ({@code before}, null when
	 * none), whether it ignored it ({@code fullRefresh}), and a note when the stored checkpoint could not
	 * be used. {@code watermarkAfter} starts as the same value: it only changes if the run advances it.
	 */
	public void setWatermark(final String pField, final String pBefore, final boolean pFullRefresh, final String pNote)
	{
		this.watermarkActive = true;
		this.watermarkField = pField;
		this.watermarkBefore = pBefore;
		this.watermarkAfter = pBefore;
		this.watermarkFullRefresh = pFullRefresh;
		this.watermarkNote = pNote;
	}

	/** The checkpoint after the run and whether the run moved it. */
	public void setWatermarkAfter(final String pAfter, final boolean pAdvanced)
	{
		this.watermarkAfter = pAfter;
		this.watermarkAdvanced = pAdvanced;
	}

	/** A row skipped by the watermark filter: at or below the checkpoint, never looked up or transformed. */
	public void skippedByWatermark()
	{
		this.skippedByWatermark++;
	}

	/** Adds to the note of the watermark (a checkpoint that could not be saved, for instance). */
	public void appendWatermarkNote(final String pNote)
	{
		if (pNote != null && !pNote.isEmpty())
		{
			this.watermarkNote = this.watermarkNote == null || this.watermarkNote.isEmpty() ? pNote : this.watermarkNote + "; " + pNote;
		}
	}

	public boolean isWatermarkActive()
	{
		return this.watermarkActive;
	}

	public String getWatermarkField()
	{
		return this.watermarkField;
	}

	public String getWatermarkBefore()
	{
		return this.watermarkBefore;
	}

	public String getWatermarkAfter()
	{
		return this.watermarkAfter;
	}

	public boolean isWatermarkAdvanced()
	{
		return this.watermarkAdvanced;
	}

	public boolean isWatermarkFullRefresh()
	{
		return this.watermarkFullRefresh;
	}

	public String getWatermarkNote()
	{
		return this.watermarkNote;
	}

	/** Rows the watermark filter skipped; distinct from {@link #getSkipped()} (items never attempted) and not part of {@link #getCount()}. */
	public int getSkippedByWatermark()
	{
		return this.skippedByWatermark;
	}

	/** Errors counted by {@link #failRun}: failures of the run, not of an item. */
	private int runFailures;
	private String runFailure;

	public ImportOutcome()
	{
		this(ImportOutcome.defaultResultsMax(), ImportOutcome.defaultErrorsMax());
	}

	public ImportOutcome(final int pResultsMax, final int pErrorsMax)
	{
		this.resultsMax = Math.max(0, pResultsMax);
		this.errorsMax = Math.max(0, pErrorsMax);
	}

	public int getResultsMax()
	{
		return this.resultsMax;
	}

	public int getErrorsMax()
	{
		return this.errorsMax;
	}

	/**
	 * Folds one item's (or one batch header's / notice's) {@link SyncResult} into the bounded samples.
	 * Does not touch the counters - callers increment {@link #created()}/{@link #updated()}/etc.
	 * themselves at the point they already know which bucket the item belongs to (created vs updated
	 * cannot be told apart from a {@link SyncResult} alone: both report status {@code OK}).
	 */
	public void addResult(final SyncResult pResult)
	{
		if (pResult == null)
		{
			return;
		}
		this.totalAdded++;
		if (this.results.size() < this.resultsMax)
		{
			this.results.add(pResult);
		}
		else
		{
			this.resultsOmitted = true;
		}
		if (pResult.hasFailure() && this.errors.size() < this.errorsMax)
		{
			this.errors.add(pResult);
		}
		if (pResult.isLimitNotice())
		{
			this.truncated = true;
			this.limitMessage = pResult.getMessage();
			if (pResult.getAvailable() > this.available)
			{
				this.available = pResult.getAvailable();
			}
		}
		if (!pResult.getConflicts().isEmpty())
		{
			this.conflicts++;
		}
		// Volume lot 7b: every result of a catalog-scan run, unbounded - items, header, "limit
		// reached"/"not processed" notice, discovery-failure notice - exactly the set getResults() used
		// to hold, fed to CatalogScanStatus's own exact classification instead. A no-op for any other run.
		if (this.catalogTally != null)
		{
			this.catalogTally.add(pResult);
		}
	}

	public void created()
	{
		this.created++;
	}

	public void updated()
	{
		this.updated++;
	}

	public void noChange()
	{
		this.noChange++;
	}

	public void error()
	{
		this.errorCount++;
	}

	/**
	 * The run itself failed - the source could not be read, the target dataset was not found, the run was refused before it
	 * read anything - and {@code pHeader} (status {@code ERROR}) says why. The failure is counted as an error, so that the
	 * run reads {@code ERROR} (or {@code PARTIAL} when items were written before it) with {@code errors >= 1} instead of
	 * {@code NO_DATA} with none, but it is not an item: {@link #getCount()} does not include it. Before this, such a run
	 * ended {@code DONE}/{@code NO_DATA} with 0 errors and a REST answer of 200 (audit decision 4).
	 */
	public void failRun(final SyncResult pHeader)
	{
		this.addResult(pHeader);
		this.errorCount++;
		this.runFailures++;
		if (this.runFailure == null)
		{
			this.runFailure = pHeader == null || pHeader.getMessage() == null || pHeader.getMessage().isEmpty() ? "the run failed" : pHeader.getMessage();
		}
	}

	/** Why the run itself failed (see {@link #failRun}), null when it did not. */
	public String getRunFailure()
	{
		return this.runFailure;
	}

	/** An item never attempted at all (part of a truncation/breaker/cancellation notice's count). */
	public void skipped(final int pCount)
	{
		this.skipped += Math.max(0, pCount);
	}

	/** An item the run looked up and declined to create ({@code createIfMissing=false}, no match). */
	public void rejected()
	{
		this.rejected++;
	}

	public int getCreated()
	{
		return this.created;
	}

	public int getUpdated()
	{
		return this.updated;
	}

	public int getNoChange()
	{
		return this.noChange;
	}

	public int getErrorCount()
	{
		return this.errorCount;
	}

	public int getSkipped()
	{
		return this.skipped;
	}

	public int getRejected()
	{
		return this.rejected;
	}

	public int getConflicts()
	{
		return this.conflicts;
	}

	/** Total items the run reached a final outcome for (excludes never-attempted, i.e. {@link #getSkipped()}). */
	public int getCount()
	{
		return this.created + this.updated + this.noChange + (this.errorCount - this.runFailures) + this.rejected;
	}

	public boolean isTruncated()
	{
		return this.truncated;
	}

	public long getAvailable()
	{
		return this.available;
	}

	/** The exact text of the run's own "limit reached" notice (volume lot 7b), for a caller (CatalogScanStatus) that wants to reuse it verbatim instead of re-deriving one from the counters; null when {@link #isTruncated()} is false. */
	public String getLimitMessage()
	{
		return this.limitMessage;
	}

	/**
	 * Volume lot 7b: marks this run truncated explicitly, from the one place ({@code
	 * ConnectorService.runImport}) that knows the read was actually cut short - needed because
	 * {@link #addResult}'s own truncation detection goes through {@link SyncResult#isLimitNotice()},
	 * which requires the notice's {@code available} to be set; a source with NO known total (JDBC's
	 * native cursor and its probe-row trick, most connectors on a sink-initiated max cutoff) reports its
	 * "limit reached" notice with {@code available} left at -1, so {@code isLimitNotice()} would
	 * otherwise never flag it and {@link #isTruncated()} would silently stay {@code false} for exactly
	 * the runs where the caller most needs to know a max was hit. Called once, right next to the same
	 * notice's {@link #addResult}, regardless of whether the notice itself carries a known
	 * {@code available} (idempotent either way - the {@code isLimitNotice()} path already set the same
	 * fields when it applies).
	 */
	void markTruncated(final long pAvailable, final String pMessage)
	{
		this.truncated = true;
		this.limitMessage = pMessage;
		if (pAvailable > this.available)
		{
			this.available = pAvailable;
		}
	}

	/**
	 * Volume lot 7b: turns on the catalog-scan tally for this run (see {@link CatalogScanTally}) - only
	 * {@code ConnectorService.runImport} calls this, and only for a mapping {@code CatalogScanStatus}
	 * itself would recognise as a scan flow. Once enabled, every {@link #addResult} call also feeds the
	 * tally; a run that never enables it pays nothing beyond one extra null check per {@link #addResult}.
	 */
	void enableCatalogTally()
	{
		this.catalogTally = new CatalogScanTally();
	}

	/** The exact, unbounded classification for a catalog scan's status message; null when this run never enabled it. */
	CatalogScanTally getCatalogTally()
	{
		return this.catalogTally;
	}

	/** Every {@link #addResult} call ever made for this run, unbounded - unlike {@code getResults().size()}, never capped at {@link #getResultsMax()}. */
	int getTotalResultsAdded()
	{
		return this.totalAdded;
	}

	public String getRunId()
	{
		return this.runId;
	}

	public void setRunId(final String pRunId)
	{
		this.runId = pRunId;
	}

	/** Index of the next item to process - what a resumed {@code connectors_sync(resumeFrom=...)} would pass. */
	public int getNextIndex()
	{
		return this.nextIndex;
	}

	public void setNextIndex(final int pNextIndex)
	{
		this.nextIndex = pNextIndex;
	}

	public long getDurationMs()
	{
		return this.durationMs;
	}

	public void setDurationMs(final long pDurationMs)
	{
		this.durationMs = pDurationMs;
	}

	/** Non-null when the run stopped before exhausting its items (circuit breaker, cancellation). */
	public String getStopReason()
	{
		return this.stopReason;
	}

	public void setStopReason(final String pReason)
	{
		this.stopReason = pReason;
	}

	/**
	 * The connector's raw document, when the read produced one - internal plumbing for
	 * {@code ConnectorService}'s catalog-scan bookkeeping (see {@code Connector.RowStreamResult}); not
	 * part of the public answer shape, never surfaced by the MCP/REST tools.
	 */
	private JsonNode sourceDocument;

	JsonNode getSourceDocument()
	{
		return this.sourceDocument;
	}

	void setSourceDocument(final JsonNode pDocument)
	{
		this.sourceDocument = pDocument;
	}

	/** Bookkeeping rows of this run that could not be saved, by kind; null until one is lost. */
	private java.util.Map<String, Integer> bookkeepingLost;

	/**
	 * Notes that a row the module keeps about this run (see {@link Bookkeeping}) could not be written.
	 * Neither a counter nor the status moves: the items are written. Called per lost write, which on
	 * the batched path means per batch at most, never per row of a healthy run.
	 */
	public void bookkeepingLost(final String pKind)
	{
		if (pKind == null)
		{
			return;
		}
		if (this.bookkeepingLost == null)
		{
			this.bookkeepingLost = new java.util.LinkedHashMap<String, Integer>();
		}
		final Integer count = this.bookkeepingLost.get(pKind);
		this.bookkeepingLost.put(pKind, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
	}

	/** Lost bookkeeping writes of this run, by kind; empty for a run whose every row about it was saved. */
	public java.util.Map<String, Integer> getBookkeepingLost()
	{
		return this.bookkeepingLost == null ? java.util.Collections.<String, Integer> emptyMap() : this.bookkeepingLost;
	}

	/** A sentence for the caller when something was lost, null otherwise - see {@link Bookkeeping#describe}. */
	public String getBookkeepingNotice()
	{
		return Bookkeeping.describe(this.bookkeepingLost);
	}

	/** The first {@link #getResultsMax()} results, any status; see {@link #isResultsOmitted()}. */
	public List<SyncResult> getResults()
	{
		return this.results;
	}

	/** True once the run produced more results than {@link #getResultsMax()} - {@link #getResults()} is then a sample, not the whole run. */
	public boolean isResultsOmitted()
	{
		return this.resultsOmitted;
	}

	/** The first {@link #getErrorsMax()} failing results (see {@link SyncResult#hasFailure()}). */
	public List<SyncResult> getErrors()
	{
		return this.errors;
	}

	/**
	 * {@code ERROR} when nothing at all succeeded and something was attempted, {@code PARTIAL} when
	 * any error, rejection, skip or conflict happened alongside at least one success, {@code OK} when
	 * every attempted item succeeded, {@code NO_DATA} when nothing was ever attempted.
	 */
	public String status()
	{
		final int successes = this.created + this.updated + this.noChange;
		if (successes == 0 && this.errorCount == 0 && this.rejected == 0 && this.skipped == 0)
		{
			return "NO_DATA";
		}
		if (successes == 0 && (this.errorCount > 0 || this.rejected > 0))
		{
			return "ERROR";
		}
		if (this.errorCount > 0 || this.rejected > 0 || this.skipped > 0 || this.truncated || this.conflicts > 0)
		{
			return "PARTIAL";
		}
		return "OK";
	}

	/**
	 * Volume lot 7a: the terminal {@code ExecutionRun.status} a real (non-dry) run ends on -
	 * {@code RUNNING}/{@code DONE}/{@code PARTIAL}/{@code ERROR}/{@code STOPPED}/{@code INTERRUPTED},
	 * used by {@code ConnectorService.runImport} regardless of whether the run was synchronous or
	 * asynchronous, batched or not. {@code STOPPED} always wins over whatever {@link #status()} would
	 * otherwise say (a cancelled run is never reported as a clean {@code DONE} just because every item
	 * it did attempt happened to succeed); a merely in-progress {@code OK}/{@code NO_DATA} in-flight
	 * reading becomes the calmer {@code DONE} once the run is actually over (an import that touched
	 * nothing, or hit no problems, is "done", not perpetually "ok" the way a live counter would read).
	 */
	public String finalStatus(final boolean pCancelled)
	{
		if (pCancelled)
		{
			return "STOPPED";
		}
		final String s = this.status();
		return "OK".equals(s) || "NO_DATA".equals(s) ? "DONE" : s;
	}
}
