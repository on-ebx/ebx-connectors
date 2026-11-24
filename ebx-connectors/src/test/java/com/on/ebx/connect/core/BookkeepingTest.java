package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import com.orchestranetworks.schema.ConstraintViolationException;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.ProcedureResult;

/**
 * A bookkeeping write EBX refuses is neither an exception nor a success: the result of its procedure
 * is read, the loss is counted and said, and the outcome of the business write is left alone. The
 * decisions are exercised here with a hand-made {@link ProcedureResult}; that a real refused
 * procedure answers {@code hasFailed()} without throwing is EBX's documented contract, not something
 * this suite can run.
 */
public final class BookkeepingTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// reading the result: null and success are committed, a failure is a counted loss, never a throw
		check(Bookkeeping.committed(Bookkeeping.SYNC_LOG, "run R1", null), "no result to read: nothing to report");
		check(Bookkeeping.committed(Bookkeeping.SYNC_LOG, "run R1", result(false, null)), "a successful procedure is committed");
		final long before = Bookkeeping.lostCount(Bookkeeping.OUTBOUND_STATE);
		check(!Bookkeeping.committed(Bookkeeping.OUTBOUND_STATE, "run R1, mapping 7, status SENT", result(true, "Value must not exceed 255 character(s)")),
			"a failed procedure is not committed");
		check(Bookkeeping.lostCount(Bookkeeping.OUTBOUND_STATE) == before + 1, "and is counted");
		check(!Bookkeeping.lost(Bookkeeping.RUN_STATE, "run R1", new IllegalStateException("dataset closed")), "a thrown failure answers false too");

		// the log line: says what was lost and that the data is safe, never a secret, bounded
		final String line = Bookkeeping.logLine(Bookkeeping.OUTBOUND_STATE, "run R1, mapping 7, status SENT",
			"POST https://api.example.test/push?token=SECRETVALUE123 refused", 1);
		check(line.contains("outbound state not written") && line.contains("run R1") && line.contains("not affected"), "the line says what and where: " + line);
		check(!line.contains("SECRETVALUE123"), "the line is redacted: " + line);
		final StringBuilder huge = new StringBuilder();
		for (int i = 0; i < 5000; i++) huge.append('x');
		check(Bookkeeping.logLine(Bookkeeping.SYNC_LOG, null, huge.toString(), 1).length() <= 2000, "the line is bounded");

		// throttle: a table that refuses every write costs ten lines, then one per thousand
		int logged = 0;
		for (long n = 1; n <= 5000; n++) if (Bookkeeping.shouldLog(n)) logged++;
		check(logged == 15, "5000 losses of one kind log 10 + 5 lines, not 5000: " + logged);
		check(Bookkeeping.shouldLog(1) && Bookkeeping.shouldLog(10) && !Bookkeeping.shouldLog(11) && Bookkeeping.shouldLog(1000), "first ten, then every thousandth");

		// an import run: a lost row changes neither the counters nor the status, and is said
		final ImportOutcome outcome = new ImportOutcome();
		final SyncResult written = item(SyncResult.Status.OK);
		written.getChanges().add(new MappingResult.Change("./label", "old", "new"));
		outcome.addResult(written);
		outcome.updated();
		check(outcome.getBookkeepingLost().isEmpty() && outcome.getBookkeepingNotice() == null, "a healthy run carries no notice");
		outcome.bookkeepingLost(Bookkeeping.RUN_STATE);
		outcome.bookkeepingLost(Bookkeeping.SYNC_LOG);
		outcome.bookkeepingLost(Bookkeeping.SYNC_LOG);
		check("OK".equals(outcome.status()) && "DONE".equals(outcome.finalStatus(false)), "the status of the data is untouched: " + outcome.status());
		check(outcome.getUpdated() == 1 && outcome.getErrorCount() == 0, "the counters are untouched");
		final Map<String, Integer> lost = outcome.getBookkeepingLost();
		check(lost.get(Bookkeeping.RUN_STATE).intValue() == 1 && lost.get(Bookkeeping.SYNC_LOG).intValue() == 2, "losses are counted by kind: " + lost);
		check(outcome.getBookkeepingNotice().contains("run state (1)") && outcome.getBookkeepingNotice().contains("sync log (2)"), "and described: " + outcome.getBookkeepingNotice());

		// one record: the result stays a success, and carries what its reader cannot rely on
		final SyncResult sent = item(SyncResult.Status.OK);
		sent.bookkeepingLost(Bookkeeping.OUTBOUND_STATE);
		sent.bookkeepingLost(Bookkeeping.OUTBOUND_STATE);
		check(sent.getStatus() == SyncResult.Status.OK && sent.isSuccess() && !sent.hasFailure(), "a delivered record is not turned into a failure");
		check(sent.getBookkeepingLost().equals(Arrays.asList(Bookkeeping.OUTBOUND_STATE)), "the loss is carried once: " + sent.getBookkeepingLost());
		check(item(SyncResult.Status.OK).getBookkeepingLost().isEmpty(), "an ordinary result carries none");
		final Map<String, Integer> inList = Bookkeeping.lostIn(Arrays.asList(sent, item(SyncResult.Status.OK), sent));
		check(inList.get(Bookkeeping.OUTBOUND_STATE).intValue() == 2 && inList.size() == 1, "a list of results is summed by kind: " + inList);
		check(Bookkeeping.lostIn(Arrays.asList(item(SyncResult.Status.OK))).isEmpty(), "nothing lost, nothing said");
		check("OK".equals(SyncResult.summarize(Arrays.asList(sent)).status()), "the summary status of the run is untouched");

		// what a lost outbound state means depends on the state
		check(ConnectorService.outboundStateLostMessage("SENT").contains("send it again"), "a lost SENT is a future re-send");
		check(ConnectorService.outboundStateLostMessage("FAILED").contains("'failed' scope"), "a lost FAILED hides the record from the failed scope");
		check(ConnectorService.outboundStateLostMessage("DELETED").contains("attempt it again"), "a lost DELETED is a repeated deletion");

		// the final state of a run: remembered when the row cannot say it, forgotten when it can again
		check(Bookkeeping.lostRunState("R-none") == null, "a run whose row was saved is not remembered");
		Bookkeeping.rememberLostRunState("R-lost", "PARTIAL", "Limit reached");
		check("PARTIAL".equals(Bookkeeping.lostRunState("R-lost").getStatus()) && "Limit reached".equals(Bookkeeping.lostRunState("R-lost").getMessage()),
			"the state the row should have had is kept");
		Bookkeeping.forgetLostRunState("R-lost");
		check(Bookkeeping.lostRunState("R-lost") == null, "a later successful write clears it");
		for (int i = 0; i < Bookkeeping.LOST_RUN_STATES_MAX + 50; i++) Bookkeeping.rememberLostRunState("R-" + i, "ERROR", null);
		check(Bookkeeping.lostRunState("R-0") == null && Bookkeeping.lostRunState("R-" + (Bookkeeping.LOST_RUN_STATES_MAX + 49)) != null,
			"the memory is bounded and keeps the most recent runs");
		check(ConnectorService.reducedRunStateMessage("Limit reached").startsWith("Final state saved without the counters")
			&& ConnectorService.reducedRunStateMessage("Limit reached").endsWith("Limit reached"), "a reduced final write says so before the run's own message");

		System.out.println("PASS: a refused bookkeeping write is counted, logged once in a bounded way and carried by the result, without changing the outcome of the data");
	}

	private static SyncResult item(final SyncResult.Status pStatus)
	{
		final SyncResult r = new SyncResult("fixture", "push", "OUTBOUND", "Reference", "/root/T", "42", "fixture");
		r.setStatus(pStatus);
		return r;
	}

	private static ProcedureResult result(final boolean pFailed, final String pMessage)
	{
		return new ProcedureResult()
		{
			@Override
			public boolean hasFailed()
			{
				return pFailed;
			}

			@Override
			public OperationException getException()
			{
				return null;
			}

			@Override
			public ConstraintViolationException getConstraintViolationException()
			{
				return null;
			}

			@Override
			public String getExceptionFullMessage(final Locale pLocale)
			{
				return pMessage;
			}

			@Override
			public Date getExecutionTimestamp()
			{
				return new Date();
			}

			@Override
			public String getExecutionUUIDString()
			{
				return "test";
			}
		};
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
