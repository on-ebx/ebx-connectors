package com.on.ebx.connect.core;

/**
 * Pure aggregation behind the volume lot 6 batch SyncLog row: {@link ImportBatchSummary} must build
 * the right {@code recordKey} ("first..last", the single key, or null), the right counts message,
 * and the right status/{@code fullyFailed} signal for the circuit breaker. No EBX type involved.
 */
public final class ImportBatchSummaryTest
{
	private ImportBatchSummaryTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// a clean batch of creates and updates: OK, not fully failed
		final ImportBatchSummary clean = new ImportBatchSummary();
		clean.addKey("A"); clean.created();
		clean.addKey("B"); clean.updated();
		clean.addKey("C"); clean.noChange();
		check(clean.recordKey().equals("A..C"), "first..last must span the batch's keys, got " + clean.recordKey());
		check(clean.status() == SyncResult.Status.OK, "no error or rejection must report OK, got " + clean.status());
		check(!clean.fullyFailed(), "a batch with successful writes must never be fullyFailed");
		check(clean.total() == 3, "total must count every tallied item");
		check(clean.message().contains("created=1") && clean.message().contains("updated=1") && clean.message().contains("noChange=1"),
			"the message must carry every counter: " + clean.message());

		// a single-item batch: first == last, no ".."
		final ImportBatchSummary single = new ImportBatchSummary();
		single.addKey("X"); single.created();
		check("X".equals(single.recordKey()), "a single key must not be turned into a range, got " + single.recordKey());

		// no key ever seen (e.g. every item's predicate was empty, an always-create feed): null, not ".."
		final ImportBatchSummary noKeys = new ImportBatchSummary();
		noKeys.addKey(""); noKeys.created();
		noKeys.addKey(null); noKeys.created();
		check(noKeys.recordKey() == null, "no non-blank key seen must report a null recordKey, got " + noKeys.recordKey());

		// every attempted write failed, nothing created or updated: ERROR and fullyFailed (the circuit breaker's signal)
		final ImportBatchSummary allFailed = new ImportBatchSummary();
		allFailed.addKey("A"); allFailed.error();
		allFailed.addKey("B"); allFailed.error();
		check(allFailed.status() == SyncResult.Status.ERROR, "a batch with zero successful writes and only errors must report ERROR, got " + allFailed.status());
		check(allFailed.fullyFailed(), "a batch with zero successful writes must be fullyFailed");

		// some errors but at least one write succeeded: PARTIAL, not fully failed (must not trip the breaker)
		final ImportBatchSummary partial = new ImportBatchSummary();
		partial.addKey("A"); partial.created();
		partial.addKey("B"); partial.error();
		check(partial.status() == SyncResult.Status.PARTIAL, "a mix of a success and an error must report PARTIAL, got " + partial.status());
		check(!partial.fullyFailed(), "a batch with at least one successful write must never be fullyFailed, even with errors");

		// rejections (createIfMissing=false, no match) alone: PARTIAL, never fullyFailed (nothing was attempted to write)
		final ImportBatchSummary rejectedOnly = new ImportBatchSummary();
		rejectedOnly.addKey("A"); rejectedOnly.rejected();
		rejectedOnly.addKey("B"); rejectedOnly.noChange();
		check(rejectedOnly.status() == SyncResult.Status.PARTIAL, "a rejection must make the batch PARTIAL even with no errors, got " + rejectedOnly.status());
		check(!rejectedOnly.fullyFailed(), "a batch with no error and no write attempt must never be fullyFailed");

		// an empty batch (should not normally be logged, but must not misbehave): OK, not fully failed, no key
		final ImportBatchSummary empty = new ImportBatchSummary();
		check(empty.status() == SyncResult.Status.OK, "an empty batch must default to OK");
		check(!empty.fullyFailed(), "an empty batch must never be fullyFailed");
		check(empty.recordKey() == null, "an empty batch must report a null recordKey");
		check(empty.total() == 0, "an empty batch must total zero");

		System.out.println("PASS: ImportBatchSummary builds the right recordKey, message and status/fullyFailed signal for every batch shape");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
