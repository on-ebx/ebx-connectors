package com.on.ebx.connect.core;

/**
 * Wave 13, asynchronous outbound scope send: the pure decisions of {@link OutboundRun} - how the result of a record counts in the
 * run's {@code ExecutionRun} row, where the batch boundary falls, when a requested stop takes effect, and the terminal status. No EBX
 * type is involved; the writing of the row itself needs a running repository and is exercised on a live instance.
 */
public final class OutboundRunTest
{
	private static int checks;

	public static void main(final String[] pArgs)
	{
		counters();
		boundaries();
		stop();
		terminalStatus();
		System.out.println("PASS: asynchronous outbound send counts sent / unchanged / failed, writes at a boundary, stops only there, ends DONE / PARTIAL / ERROR / STOPPED (" + checks + " checks)");
	}

	private static void counters()
	{
		final ImportOutcome outcome = new ImportOutcome(10, 5);
		OutboundRun.count(outcome, result("k1", SyncResult.Status.OK, "sent"));
		OutboundRun.count(outcome, result("k2", SyncResult.Status.OK, "sent"));
		OutboundRun.count(outcome, result("k3", SyncResult.Status.NO_CHANGE, "same document"));
		OutboundRun.count(outcome, result("k4", SyncResult.Status.ERROR, "HTTP 500"));
		OutboundRun.count(outcome, result("k5", SyncResult.Status.PARTIAL, "one of two calls failed"));
		OutboundRun.count(outcome, result("k6", SyncResult.Status.SKIPPED, "no rule"));
		eq(0, outcome.getCreated(), "a send cannot tell a record the target never saw: created stays 0");
		eq(2, outcome.getUpdated(), "sent records are counted as updated");
		eq(1, outcome.getNoChange(), "an unchanged record is noChange");
		eq(2, outcome.getErrorCount(), "ERROR and PARTIAL are failures");
		eq(1, outcome.getSkipped(), "a skipped record is skipped");
		eq(0, outcome.getRejected(), "rejected is a feed notion: 0");
		eq(2, outcome.getErrors().size(), "the failing results are kept, bounded, for firstErrors");
		eq("k4", outcome.getErrors().get(0).getRecordKey(), "the FIRST failures are kept");
		eq(5, outcome.getCount(), "rows reached = sent + unchanged + failed (the Factory sums created + updated + noChange)");

		// what the scope left out
		OutboundRun.countScopeRest(outcome, 3, 2);
		eq(4, outcome.getNoChange(), "records unchanged since their last send add to noChange");
		eq(3, outcome.getSkipped(), "records waiting for their retry delay add to skipped");
		final ImportOutcome none = new ImportOutcome(10, 5);
		OutboundRun.countScopeRest(none, 0, 0);
		eq(0, none.getNoChange() + none.getSkipped(), "nothing left out, nothing added");
	}

	private static void boundaries()
	{
		eq(250, OutboundRun.boundary(1000), "the mapping's default batchSize (1000) is cut to 250 records between two writes");
		eq(250, OutboundRun.boundary(10000), "a huge batchSize is cut to 250");
		eq(100, OutboundRun.boundary(100), "a smaller batchSize is followed");
		eq(1, OutboundRun.boundary(1), "batchSize 1 writes at each record");
		eq(250, OutboundRun.boundary(0), "an unset batchSize gives the cap");
		yes(!OutboundRun.atBoundary(0, 250) && !OutboundRun.atBoundary(249, 250) && OutboundRun.atBoundary(250, 250) && !OutboundRun.atBoundary(251, 250)
			&& OutboundRun.atBoundary(500, 250), "the boundary falls after each full group, never before the first record");
		yes(!OutboundRun.atBoundary(5, 0), "no boundary without a size");
	}

	private static void stop()
	{
		yes(!OutboundRun.mustStop(100, 250, true), "a stop asked mid-group waits for the boundary");
		yes(OutboundRun.mustStop(250, 250, true), "a stop asked is honoured at the boundary");
		yes(!OutboundRun.mustStop(250, 250, false), "no stop asked: the send goes on at the boundary");
		yes(!OutboundRun.mustStop(0, 250, true), "never before a record was sent");

		// through the registry the MCP tool writes to: connectors_run_stop(runId) -> requestCancel
		ConnectorService.requestCancel("R-OUT-1");
		yes(ConnectorService.isCancelled("R-OUT-1") && !ConnectorService.isCancelled("R-OUT-2"), "connectors_run_stop flags that run only");
		yes(OutboundRun.mustStop(500, 250, ConnectorService.isCancelled("R-OUT-1")) && !OutboundRun.mustStop(500, 250, ConnectorService.isCancelled("R-OUT-2")),
			"and only that run stops at its boundary");
		yes(OutboundRun.stopReason(500).contains("500 record(s)") && OutboundRun.stopReason(500).contains("keep their outbound state"),
			"the stopped run says how far it went and that the sent records keep their state: " + OutboundRun.stopReason(500));
	}

	private static void terminalStatus()
	{
		final ImportOutcome clean = new ImportOutcome(10, 5);
		OutboundRun.count(clean, result("a", SyncResult.Status.OK, "sent"));
		OutboundRun.count(clean, result("b", SyncResult.Status.NO_CHANGE, "same"));
		eq("DONE", OutboundRun.finalStatus(clean, false), "every record sent or unchanged: DONE");
		eq("STOPPED", OutboundRun.finalStatus(clean, true), "a stopped send is STOPPED even though everything it did succeeded");

		final ImportOutcome mixed = new ImportOutcome(10, 5);
		OutboundRun.count(mixed, result("a", SyncResult.Status.OK, "sent"));
		OutboundRun.count(mixed, result("b", SyncResult.Status.ERROR, "HTTP 500"));
		eq("PARTIAL", OutboundRun.finalStatus(mixed, false), "sent and failed together: PARTIAL");

		final ImportOutcome failed = new ImportOutcome(10, 5);
		OutboundRun.count(failed, result("a", SyncResult.Status.ERROR, "HTTP 500"));
		eq("ERROR", OutboundRun.finalStatus(failed, false), "nothing sent, failures: ERROR");

		final ImportOutcome empty = new ImportOutcome(10, 5);
		eq("DONE", OutboundRun.finalStatus(empty, false), "nothing to send is DONE, not an error");

		final ImportOutcome cut = new ImportOutcome(10, 5);
		OutboundRun.count(cut, result("a", SyncResult.Status.OK, "sent"));
		final MappingDefinition mapping = new MappingDefinition(417, "Kafka", "produce", MappingDefinition.Direction.OUTBOUND, "Distribution", "Master", "Master", "/root/T", "", false);
		final SyncResult notice = SyncResult.limitNotice(mapping, "OUTBOUND", "R1", 1, "scope ALL", false);
		cut.addResult(notice);
		cut.markTruncated(notice.getAvailable(), notice.getMessage());
		yes(cut.isTruncated() && cut.getLimitMessage().startsWith("Limit reached"), "the cut at max is truncated=true with its sentence");
		eq("PARTIAL", OutboundRun.finalStatus(cut, false), "a send cut at max is PARTIAL with truncated, no error");
		eq(0, cut.getErrorCount(), "and has no error");

		// a failure of the run itself (the table is not found, the selection fails) is an ERROR run with its reason
		final ImportOutcome broken = new ImportOutcome(10, 5);
		final SyncResult header = result("", SyncResult.Status.ERROR, "dataset not found");
		broken.failRun(header);
		eq("ERROR", OutboundRun.finalStatus(broken, false), "a run that failed before sending is ERROR");
		eq("dataset not found", broken.getRunFailure(), "with its reason");
	}

	private static SyncResult result(final String pKey, final SyncResult.Status pStatus, final String pMessage)
	{
		final SyncResult result = new SyncResult("Kafka", "produce", "OUTBOUND", "Master", "/root/T", pKey, pKey);
		result.setStatus(pStatus);
		result.setMessage(pMessage);
		return result;
	}

	private static void eq(final Object pExpected, final Object pActual, final String pMessage)
	{
		yes(pExpected == null ? pActual == null : pExpected.equals(pActual), pMessage + " (expected " + pExpected + ", got " + pActual + ")");
	}

	private static void yes(final boolean pCondition, final String pMessage)
	{
		checks++;
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
