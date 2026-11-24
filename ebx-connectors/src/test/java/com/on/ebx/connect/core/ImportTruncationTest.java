package com.on.ebx.connect.core;

import java.util.Arrays;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Before this fix (audited 2026-09-28), a feed connector that already truncated its own array to its
 * {@code max} (JdbcConnector's {@code query}, a file feed's {@code read}) always had
 * {@code available == items.size()} at the {@code ConnectorService.importDocument} level, so the old
 * "was more available than we imported?" check never fired and a truncated load looked like a clean,
 * complete one. This exercises the pure decision, {@link ConnectorService#truncationOf}, and its
 * aggregation into {@link SyncResult.Summary}, without any EBX session or repository.
 */
public final class ImportTruncationTest
{
	private ImportTruncationTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// the old case: the connector returned everything, but this class only imported up to pMax of it
		final ConnectorService.Truncation counted = ConnectorService.truncationOf(null, 214, 100);
		check(counted.truncated, "counted overflow must be reported truncated");
		check(counted.available == 214, "counted overflow must report the real counted total");

		// no truncation at all: nothing counted past the max, and the document does not claim truncation
		final ObjectNode clean = Json.object();
		clean.put("truncated", false);
		final ConnectorService.Truncation none = ConnectorService.truncationOf(clean, 2, 2);
		check(!none.truncated, "an untruncated document must not be reported truncated");
		check(none.available == -1, "an untruncated document has no available total to report");

		// the new case: the connector pre-truncated its own array (JDBC's query, a file's read) - the counted
		// available never exceeds items.size(), so only the document's own "truncated" flag can tell.
		final ObjectNode jdbcStyle = Json.object();
		jdbcStyle.put("truncated", true); // JdbcConnector.boundedQuery: knows it was cut, not by how much overall
		final ConnectorService.Truncation unknownTotal = ConnectorService.truncationOf(jdbcStyle, 1000, 1000);
		check(unknownTotal.truncated, "a connector-declared truncation must be reported even when nothing was counted past max");
		check(unknownTotal.available == -1, "no available field on the document: the total is genuinely unknown");

		final ObjectNode fileStyle = Json.object();
		fileStyle.put("truncated", true);
		fileStyle.put("available", 3300000); // FileFormats.parse / Csv.parse: the real total is known for free
		final ConnectorService.Truncation knownTotal = ConnectorService.truncationOf(fileStyle, 1000, 1000);
		check(knownTotal.truncated, "a connector-declared truncation with a known available must be reported");
		check(knownTotal.available == 3300000, "a declared available field must be surfaced exactly: " + knownTotal.available);

		// a document that is null (an empty read) or that never mentions "truncated" must never be flagged
		check(!ConnectorService.truncationOf(null, 1, 1).truncated, "a null document must not be flagged truncated");
		check(!ConnectorService.truncationOf(Json.object(), 1, 1).truncated, "a document with no truncated field must not be flagged");

		// SyncResult.Summary aggregates a limit notice's truncation into top-level fields a caller can check
		// without walking every result (ConnectorsTools.putOutcome, ConnectorsResource.putOutcome).
		final SyncResult ok = new SyncResult("Src", "query", "IMPORT", "DS", "/root/T", "1", "1");
		ok.setStatus(SyncResult.Status.OK);
		final SyncResult limit = new SyncResult("Src", "query", "IMPORT", "DS", "/root/T", "", "");
		limit.setStatus(SyncResult.Status.SKIPPED);
		limit.setAvailable(3300000);
		final SyncResult.Summary summary = SyncResult.summarize(Arrays.asList(ok, limit));
		check(summary.truncated, "a limit notice in the run must set the summary's truncated flag");
		check(summary.available == 3300000, "the summary must surface the limit notice's available count: " + summary.available);
		check("PARTIAL".equals(summary.status()), "a truncated run must never summarize as a clean OK");

		final SyncResult.Summary cleanSummary = SyncResult.summarize(Arrays.asList(ok));
		check(!cleanSummary.truncated, "a run with no limit notice must not claim truncation");
		check(cleanSummary.available == -1, "a run with no limit notice has no available total");

		System.out.println("PASS: truncation detection covers a connector's own pre-truncated array, and a truncated run never summarizes as OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
