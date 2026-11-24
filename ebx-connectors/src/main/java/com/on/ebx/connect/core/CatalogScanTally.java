package com.on.ebx.connect.core;

/**
 * Exact, unbounded per-item classification behind a catalog scan's status message (volume lot 7b).
 * <p>
 * Before this, {@code CatalogScanStatus.outcome} walked {@code ImportOutcome.getResults()} - a
 * sample capped at {@code resultsMax} (default 1000, {@code ImportOutcome}) - so a schema inventory
 * larger than that (a real catalog scan across many schemas/tables) was undercounted: the message
 * would read e.g. "1000 object(s) scanned" for a source that actually returned 4000, and any failure
 * past position 1000 never surfaced. A catalog scan is metadata-sized (tables/columns of a schema,
 * never a multi-million-row feed - see docs/REFERENCE.md), so keeping a handful of extra counters and
 * one error string for the whole run's lifetime is cheap regardless of scan size - unlike the general
 * {@code results}/{@code errors} samples, which stay bounded on purpose for a genuinely huge feed.
 * <p>
 * Mirrors, result for result, the classification {@code CatalogScanStatus.outcome} used to compute by
 * walking the (bounded) list: {@code ImportOutcome.addResult} feeds EVERY result of a catalog-scan run
 * here (items, the header, the "limit reached"/"not processed" notice, the discovery-failure notice -
 * exactly the same set {@code getResults()} used to hold), not just the ones {@code tallyItem} sees.
 */
final class CatalogScanTally
{
	int written;
	int unchanged;
	int skipped;
	int failed;
	int warned;
	private String firstError;
	private String limitMessage;

	/**
	 * Folds one result in - the exact per-result classification {@code CatalogScanStatus.outcome} used
	 * to run inline over the (bounded) results list, minus the "this is the run's ONLY result" special
	 * cases, which the caller still handles itself (it needs {@code ImportOutcome.getTotalResultsAdded}/
	 * {@code getResults}, not anything this class tracks). A header (empty record key) carrying
	 * {@code SKIPPED} is the run's own "limit reached"/"not processed" notice, not a scanned object: its
	 * message is kept ({@link #getLimitMessage()}, last one wins if a run somehow adds more than one -
	 * matching the original loop's unconditional reassignment) and it is excluded from every count.
	 */
	void add(final SyncResult pResult)
	{
		final boolean header = pResult.getRecordKey() == null || pResult.getRecordKey().isEmpty();
		if (header && pResult.getStatus() == SyncResult.Status.SKIPPED)
		{
			this.limitMessage = pResult.getMessage();
			return;
		}
		switch (pResult.getStatus())
		{
			case ERROR:
				this.failed++;
				if (this.firstError == null)
				{
					this.firstError = pResult.getRecordLabel() + ": " + pResult.getMessage();
				}
				break;
			case NO_CHANGE:
				this.unchanged++;
				break;
			case NO_DATA:
			case SKIPPED:
				this.skipped++;
				break;
			case PARTIAL:
				// A partial field mapping may have committed valid changes. With none, it is not
				// evidence of a successful write.
				if (pResult.getChanges().isEmpty())
				{
					this.unchanged++;
				}
				else
				{
					this.written++;
				}
				break;
			default:
				this.written++;
		}
		if (pResult.getStatus() != SyncResult.Status.ERROR && pResult.hasFailure())
		{
			this.warned++;
			if (this.firstError == null)
			{
				this.firstError = pResult.getRecordLabel() + ": "
					+ (pResult.getErrors().isEmpty() ? pResult.getMessage() : pResult.getErrors().get(0));
			}
		}
	}

	String getFirstError()
	{
		return this.firstError;
	}

	/** The message of the last header+{@code SKIPPED} notice folded in ("limit reached"/"not processed"); null when none was. */
	String getLimitMessage()
	{
		return this.limitMessage;
	}

	/** Total items this tally actually classified (excludes anything never attempted). */
	int total()
	{
		return this.written + this.unchanged + this.skipped + this.failed;
	}
}
