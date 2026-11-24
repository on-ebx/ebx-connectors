package com.on.ebx.connect.core;

/**
 * The end of a catalog scan is written on its Source (seen 2026-09-16: Salesforce assets refreshed while
 * the Source still said NEVER). Only scan flows are concerned, and failures are recorded too.
 */
public final class CatalogScanStatusTest
{
	public static void main(final String[] pArgs)
	{
		// which mappings are scan flows
		check("SF".equals(CatalogScanStatus.sourceOf(mapping("catalog", "/root/Asset", " SF "))), "scan flow of source SF");
		check(CatalogScanStatus.sourceOf(mapping("profile", "/root/ProfileSnapshot", "SF")) == null, "profile flow is not a scan");
		check(CatalogScanStatus.sourceOf(mapping("usage", "/root/Asset", "SF")) == null, "usage flow on Asset is not a scan");
		check(CatalogScanStatus.sourceOf(mapping("catalog", "/root/Column", "SF")) == null, "columns table is not the scan");
		check(CatalogScanStatus.sourceOf(mapping("catalog", "/root/Asset", "")) == null, "no source input");
		check(CatalogScanStatus.sourceOf(mapping("catalog", "/root/Asset", "${param.source}")) == null, "templated source is not guessed");
		check(CatalogScanStatus.sourceOf(null) == null, "no mapping");

		// what the results say (volume lot 7b: outcome() now reads an ImportOutcome's exact, unbounded
		// tally instead of walking a bounded List<SyncResult> - outcomeOf below builds one the same way
		// ConnectorService does: every result, header/notices included, through addResult alone, which
		// itself feeds the tally when enabled)
		CatalogScanStatus.Outcome o = CatalogScanStatus.outcome(outcomeOf(item("A", SyncResult.Status.OK), item("B", SyncResult.Status.NO_CHANGE)));
		check("OK".equals(o.status), "all written: " + o.status);
		check(o.message.startsWith("2 object(s) scanned: 1 written, 1 unchanged."), o.message);

		o = CatalogScanStatus.outcome(outcomeOf(item("A", SyncResult.Status.OK), failed("B", "too long")));
		check("PARTIAL".equals(o.status), "one failure: " + o.status);
		check(o.message.contains("1 failed") && o.message.contains("First error: B: too long"), o.message);

		o = CatalogScanStatus.outcome(outcomeOf(failed("A", "x"), failed("B", "y")));
		check("ERROR".equals(o.status), "all failed: " + o.status);

		o = CatalogScanStatus.outcome(outcomeOf(header(SyncResult.Status.ERROR, "401 INVALID_SESSION_ID")));
		check("ERROR".equals(o.status) && o.message.equals("Scan failed: 401 INVALID_SESSION_ID"), "connection failure: " + o.message);

		o = CatalogScanStatus.outcome(outcomeOf(header(SyncResult.Status.NO_DATA, "No item returned")));
		check("PARTIAL".equals(o.status) && o.message.contains("no object"), "empty answer: " + o.message);

		o = CatalogScanStatus.outcome(outcomeOf(item("A", SyncResult.Status.OK), header(SyncResult.Status.SKIPPED, "Limit reached: 1 of the 3 items")));
		check("PARTIAL".equals(o.status) && o.message.contains("Limit reached"), "limit reached: " + o.message);

		final SyncResult warned = item("A", SyncResult.Status.OK);
		warned.getErrors().add("./rowCount: not a number");
		o = CatalogScanStatus.outcome(outcomeOf(warned));
		check("PARTIAL".equals(o.status) && o.message.contains("1 with field errors"), "field errors: " + o.message);

		o = CatalogScanStatus.outcome(outcomeOf());
		check("ERROR".equals(o.status), "no result at all");

		final StringBuilder longMessage = new StringBuilder();
		for (int i = 0; i < 2000; i++) longMessage.append('x');
		o = CatalogScanStatus.outcome(outcomeOf(header(SyncResult.Status.ERROR, longMessage.toString())));
		check(o.message.length() == CatalogScanStatus.MESSAGE_MAX && o.message.endsWith("..."), "message fits the field");

		// volume lot 7b: the tally is exact past resultsMax, where the bounded results sample is not -
		// a scan larger than resultsMax must still report every object, every failure counted.
		final ImportOutcome big = new ImportOutcome(2, 50); // resultsMax=2: only the first 2 results are kept
		big.enableCatalogTally();
		for (int i = 0; i < 5; i++)
		{
			big.addResult(item("A" + i, SyncResult.Status.OK));
		}
		big.addResult(failed("A5", "boom")); // past resultsMax=2: dropped from getResults(), still tallied
		check(big.isResultsOmitted(), "results sample is capped in this scenario");
		final CatalogScanStatus.Outcome bigOutcome = CatalogScanStatus.outcome(big);
		check(bigOutcome.message.startsWith("6 object(s) scanned: 5 written, 0 unchanged, 1 failed"),
			"exact total past resultsMax, not the capped sample size: " + bigOutcome.message);
		check(bigOutcome.message.contains("First error: A5: boom"), "a failure past resultsMax still surfaces: " + bigOutcome.message);

		System.out.println("PASS: catalog scan status (scan flows only, OK / PARTIAL / ERROR, failures recorded, exact past resultsMax)");
	}

	/** Builds an ImportOutcome the way ConnectorService does: every result (item, header, notice) folded in through addResult alone, which itself feeds the catalog-scan tally when enabled. */
	private static ImportOutcome outcomeOf(final SyncResult... pResults)
	{
		final ImportOutcome outcome = new ImportOutcome();
		outcome.enableCatalogTally();
		for (final SyncResult r : pResults)
		{
			outcome.addResult(r);
		}
		return outcome;
	}

	private static MappingDefinition mapping(final String pOperation, final String pTable, final String pSource)
	{
		final MappingDefinition m = new MappingDefinition(190, "Salesforce", pOperation, MappingDefinition.Direction.INBOUND, "Catalog scan: SF", "DataCatalog",
			"DataCatalog", pTable, "", true);
		m.addInput("source", pSource);
		return m;
	}

	private static SyncResult item(final String pKey, final SyncResult.Status pStatus)
	{
		final SyncResult r = new SyncResult("Salesforce", "catalog", "IMPORT", "DataCatalog", "/root/Asset", pKey, pKey);
		r.setStatus(pStatus);
		return r;
	}

	private static SyncResult failed(final String pKey, final String pMessage)
	{
		final SyncResult r = CatalogScanStatusTest.item(pKey, SyncResult.Status.ERROR);
		r.setMessage(pMessage);
		return r;
	}

	private static SyncResult header(final SyncResult.Status pStatus, final String pMessage)
	{
		final SyncResult r = CatalogScanStatusTest.item("", pStatus);
		r.setMessage(pMessage);
		return r;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
