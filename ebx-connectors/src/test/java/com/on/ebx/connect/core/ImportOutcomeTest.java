package com.on.ebx.connect.core;

/**
 * Volume lot 7a: {@link ImportOutcome} replaces a run-long {@code List<SyncResult>} with running
 * counters plus two small bounded samples ({@code results}, {@code errors}) - this exercises the
 * counting, the bounding (never grows past {@code resultsMax}/{@code errorsMax}, and sets
 * {@code resultsOmitted} exactly once it would have), {@link ImportOutcome#status()} and
 * {@link ImportOutcome#finalStatus(boolean)}'s run-state transitions, with no EBX type involved.
 */
public final class ImportOutcomeTest
{
	public static void main(final String[] args) throws Exception
	{
		// counters: created/updated distinguished by SyncResult.isCreated(), NO_CHANGE/SKIPPED/ERROR by status
		final ImportOutcome outcome = new ImportOutcome(2, 1); // tiny caps to exercise bounding cheaply
		fold(outcome, ok("k1", true));   // created
		fold(outcome, ok("k2", false));  // updated
		fold(outcome, noChange("k3"));
		fold(outcome, rejected("k4"));
		fold(outcome, error("k5", "boom"));
		fold(outcome, error("k6", "bang"));

		check(outcome.getCreated() == 1, "one created: " + outcome.getCreated());
		check(outcome.getUpdated() == 1, "one updated: " + outcome.getUpdated());
		check(outcome.getNoChange() == 1, "one unchanged: " + outcome.getNoChange());
		check(outcome.getRejected() == 1, "one rejected: " + outcome.getRejected());
		check(outcome.getErrorCount() == 2, "two errors: " + outcome.getErrorCount());
		check(outcome.getCount() == 6, "count is every item reaching a final outcome (excludes skipped-not-attempted): " + outcome.getCount());

		// resultsMax=2: only the first 2 additions are kept, everything after sets resultsOmitted
		check(outcome.getResults().size() == 2, "results is capped at resultsMax: " + outcome.getResults().size());
		check(outcome.isResultsOmitted(), "a 6th result past resultsMax=2 must set resultsOmitted");
		check(outcome.getResults().get(0).getRecordKey().equals("k1"), "results keeps the FIRST ones, not the last: " + outcome.getResults().get(0).getRecordKey());

		// errorsMax=1: only the first failing result is kept, even though two failed
		check(outcome.getErrors().size() == 1, "errors is capped at errorsMax: " + outcome.getErrors().size());
		check(outcome.getErrors().get(0).getRecordKey().equals("k5"), "errors keeps the FIRST failure: " + outcome.getErrors().get(0).getRecordKey());

		// status(): a mix of success and failure is PARTIAL, never a clean OK
		check("PARTIAL".equals(outcome.status()), "successes and failures together: PARTIAL, not OK: " + outcome.status());

		// a run with only successes is OK; finalStatus() calms that down to DONE once the run is over
		final ImportOutcome clean = new ImportOutcome();
		fold(clean, ok("a", true));
		fold(clean, ok("b", false));
		check("OK".equals(clean.status()), "only successes: OK in flight: " + clean.status());
		check("DONE".equals(clean.finalStatus(false)), "OK becomes DONE once the run has actually ended: " + clean.finalStatus(false));

		// a run that touched nothing at all is NO_DATA in flight, DONE once it is over (not "no data forever")
		final ImportOutcome empty = new ImportOutcome();
		check("NO_DATA".equals(empty.status()), "nothing attempted: NO_DATA: " + empty.status());
		check("DONE".equals(empty.finalStatus(false)), "NO_DATA also becomes DONE once the run has ended: " + empty.finalStatus(false));

		// a run whose every attempt failed is ERROR, and stays ERROR at the end (never calmed to DONE)
		final ImportOutcome allFailed = new ImportOutcome();
		fold(allFailed, error("x", "no"));
		check("ERROR".equals(allFailed.status()), "everything failed: ERROR: " + allFailed.status());
		check("ERROR".equals(allFailed.finalStatus(false)), "ERROR is a real terminal state, not smoothed into DONE: " + allFailed.finalStatus(false));

		// cancellation always wins, even over an all-success run - a stopped run is never reported "DONE"
		check("STOPPED".equals(clean.finalStatus(true)), "cancellation overrides an otherwise-clean outcome: " + clean.finalStatus(true));

		// truncation and conflicts alone (no errors/rejections) still make status() PARTIAL, never OK
		final ImportOutcome truncatedRun = new ImportOutcome();
		fold(truncatedRun, ok("only", true));
		final SyncResult limitNotice = new SyncResult("Src", "query", "IMPORT", "DS", "/root/T", "", "");
		limitNotice.setStatus(SyncResult.Status.SKIPPED);
		limitNotice.setAvailable(999);
		truncatedRun.addResult(limitNotice);
		check(truncatedRun.isTruncated(), "a limit-notice result marks the outcome truncated");
		check(truncatedRun.getAvailable() == 999, "the notice's available is surfaced: " + truncatedRun.getAvailable());
		check("PARTIAL".equals(truncatedRun.status()), "a truncated run is never a clean OK, even with zero errors: " + truncatedRun.status());

		// volume lot 7b: markTruncated - a "limit reached" notice with NO known total (JDBC's native
		// cursor, most connectors on a sink-initiated max cutoff: available stays -1) must still flag
		// the run truncated, which SyncResult.isLimitNotice()'s own available>=0 requirement cannot do.
		final ImportOutcome unknownTotal = new ImportOutcome();
		fold(unknownTotal, ok("only", true));
		final SyncResult noTotalNotice = new SyncResult("Src", "query", "IMPORT", "DS", "/root/T", "", "");
		noTotalNotice.setStatus(SyncResult.Status.SKIPPED);
		noTotalNotice.setMessage("Limit reached: the source returned 1 row(s) and more were available");
		unknownTotal.addResult(noTotalNotice); // available never set: isLimitNotice() alone would miss this
		check(!unknownTotal.isTruncated(), "addResult alone does not flag it when available is unknown");
		unknownTotal.markTruncated(-1, noTotalNotice.getMessage());
		check(unknownTotal.isTruncated(), "markTruncated flags the run truncated even with no known total");
		check(unknownTotal.getAvailable() == -1, "available genuinely stays unknown: " + unknownTotal.getAvailable());

		// getTotalResultsAdded(): unbounded, unlike getResults().size() once resultsMax is exceeded
		final ImportOutcome capped = new ImportOutcome(1, 1);
		fold(capped, ok("a", true));
		fold(capped, ok("b", false));
		fold(capped, ok("c", true));
		check(capped.getResults().size() == 1, "results itself stays capped at resultsMax: " + capped.getResults().size());
		check(capped.getTotalResultsAdded() == 3, "but the total ever added is exact: " + capped.getTotalResultsAdded());

		// audit decision 4: a run that failed as a whole (source not read, dataset not found, scan refused) is an ERROR with errors >= 1,
		// not NO_DATA with none; it is not an item, so count stays 0
		final ImportOutcome down = new ImportOutcome();
		down.failRun(error("", "Connection refused"));
		check("ERROR".equals(down.status()) && "ERROR".equals(down.finalStatus(false)), "a read that failed: ERROR, not NO_DATA/DONE: " + down.status());
		check(down.getErrorCount() == 1 && down.getCount() == 0, "one error, no item: " + down.getErrorCount() + "/" + down.getCount());
		check("Connection refused".equals(down.getRunFailure()) && down.getErrors().size() == 1, "the reason is kept: " + down.getRunFailure());
		check(RunVerdict.of(down).describe().contains("the run failed: Connection refused"), "the scheduled task fails with it: " + RunVerdict.of(down).describe());
		// after items were written: PARTIAL, the items still counted
		final ImportOutcome cutShort = new ImportOutcome();
		fold(cutShort, ok("a", true));
		fold(cutShort, ok("b", false));
		cutShort.failRun(error("", "connection reset"));
		check("PARTIAL".equals(cutShort.status()) && cutShort.getCount() == 2 && cutShort.getErrorCount() == 1, "written then failed: PARTIAL: " + cutShort.status() + "/" + cutShort.getCount());
		check(RunVerdict.of(cutShort).describe().contains("the run failed: connection reset"), "and still a failure: " + RunVerdict.of(cutShort).describe());
		// a failure with no message still says something
		final ImportOutcome mute = new ImportOutcome();
		mute.failRun(new SyncResult("Src", "op", "IMPORT", "DS", "/root/T", "", ""));
		check("the run failed".equals(mute.getRunFailure()), "no message: " + mute.getRunFailure());
		System.out.println("ImportOutcomeTest: PASS");
	}

	/** Mirrors ConnectorService.tallyItem's classification, without needing that EBX-dependent class. */
	private static void fold(final ImportOutcome pOutcome, final SyncResult pResult)
	{
		pOutcome.addResult(pResult);
		final SyncResult.Status status = pResult.getStatus();
		if (status == SyncResult.Status.SKIPPED) pOutcome.rejected();
		else if (status == SyncResult.Status.NO_CHANGE) pOutcome.noChange();
		else if (status == SyncResult.Status.ERROR || status == SyncResult.Status.PARTIAL) pOutcome.error();
		else if (pResult.isCreated()) pOutcome.created();
		else pOutcome.updated();
	}

	private static SyncResult ok(final String key, final boolean created)
	{
		final SyncResult r = new SyncResult("Src", "op", "IMPORT", "DS", "/root/T", key, key);
		r.setStatus(SyncResult.Status.OK);
		r.setCreated(created);
		r.getChanges().add(new MappingResult.Change("f", null, "v"));
		return r;
	}

	private static SyncResult noChange(final String key)
	{
		final SyncResult r = new SyncResult("Src", "op", "IMPORT", "DS", "/root/T", key, key);
		r.setStatus(SyncResult.Status.NO_CHANGE);
		return r;
	}

	private static SyncResult rejected(final String key)
	{
		final SyncResult r = new SyncResult("Src", "op", "IMPORT", "DS", "/root/T", key, key);
		r.setStatus(SyncResult.Status.SKIPPED);
		r.setMessage("No record matches and creation is off");
		return r;
	}

	private static SyncResult error(final String key, final String message)
	{
		final SyncResult r = new SyncResult("Src", "op", "IMPORT", "DS", "/root/T", key, key);
		r.setStatus(SyncResult.Status.ERROR);
		r.setMessage(message);
		return r;
	}

	private static void check(final boolean condition, final String what)
	{
		if (!condition)
		{
			throw new AssertionError(what);
		}
	}
}
