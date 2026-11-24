package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-time mastering (docs/REALTIME-MASTERING.md): the matching of a CDC batch on a fake add-on. The trigger is paused
 * only around the writes and always resumed, never resumed when someone else paused it; the written keys are matched in
 * chunks; only DONE lets the checkpoint move; a timeout cancels the process; the origin marker skips the echo; a MERGED
 * record publishes its golden. No EBX repository.
 */
public final class CdcMatchingTest
{
	private CdcMatchingTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		inputs();
		pauseAroundTheWritesOnly();
		chunksAndDone();
		failuresKeepTheCheckpoint();
		timeoutCancels();
		originAndPublish();
		source(pArgs.length > 0 ? pArgs[0] : ".");
		System.out.println("PASS: CDC matching pauses around the writes, matches the written keys in chunks, lets the checkpoint move only on DONE, cancels on timeout; skipOrigin and publish=golden decide as documented");
	}

	/** A fake add-on that records what it was asked. */
	static final class FakeEngine implements CdcMatching.Engine
	{
		boolean paused;
		final List<String> calls = new ArrayList<String>();
		final List<String> filters = new ArrayList<String>();
		String policy = "Default";
		String[] statuses = { "RUNNING", "DONE" };
		boolean failMatch;
		boolean cancelled;

		@Override
		public boolean isPaused()
		{
			return this.paused;
		}

		@Override
		public void pause()
		{
			this.calls.add("pause");
			this.paused = true;
		}

		@Override
		public void resume()
		{
			this.calls.add("resume");
			this.paused = false;
		}

		@Override
		public String defaultPolicy()
		{
			return this.policy;
		}

		@Override
		public CdcMatching.Process match(final String pXPathFilter, final String pPolicy) throws Exception
		{
			this.calls.add("match:" + pPolicy + (this.paused ? ":while-paused" : ""));
			if (this.failMatch)
				throw new IllegalStateException("dataspace is locked");
			this.filters.add(pXPathFilter);
			final String[] sequence = this.statuses;
			return new CdcMatching.Process()
			{
				int read;

				@Override
				public String status()
				{
					return sequence[Math.min(this.read++, sequence.length - 1)];
				}

				@Override
				public void cancel()
				{
					FakeEngine.this.cancelled = true;
				}
			};
		}

		@Override
		public CdcMatching.Tally tally(final Collection<String> pPredicates)
		{
			final CdcMatching.Tally tally = new CdcMatching.Tally();
			tally.golden = pPredicates.size() - 1;
			tally.merged = 1;
			tally.withSuspects = 2;
			return tally;
		}
	}

	/** A clock that only moves when slept on. */
	static final class FakeClock implements CdcMatching.Clock
	{
		long now = 1000L;

		@Override
		public long now()
		{
			return this.now;
		}

		@Override
		public void sleep(final long pMillis)
		{
			this.now += pMillis;
		}
	}

	private static void inputs()
	{
		check(CdcMatching.from(null).getMode() == CdcMatching.Mode.POLICY, "matching defaults to policy");
		check(!CdcMatching.from(new HashMap<String, String>()).isActive(), "policy does not touch the add-on");
		check(CdcMatching.from(map("matching", "afterApply")).getMode() == CdcMatching.Mode.AFTER_APPLY, "afterApply");
		check(CdcMatching.from(map("matching", " OFF ")).getMode() == CdcMatching.Mode.OFF, "off, any case");
		expectIllegal(map("matching", "always"), "matching must be policy, afterApply or off");
		expectIllegal(map("matching", "afterApply", "matchingChunk", "0"), "matchingChunk must be between 1 and 10000");
		expectIllegal(map("matching", "afterApply", "matchingTimeoutSeconds", "ten"), "matchingTimeoutSeconds must be a whole number");
		final CdcMatching inactive = CdcMatching.from(null);
		check(inactive.matchWritten(new FakeEngine(), Arrays.asList("./code = 'A'")).isDone(), "policy mode: done, nothing run");
	}

	private static void pauseAroundTheWritesOnly() throws Exception
	{
		final CdcMatching matching = CdcMatching.from(map("matching", "afterApply"));
		final FakeEngine engine = new FakeEngine();
		final boolean[] pausedDuringWrites = new boolean[1];
		check(matching.whilePaused(engine, () -> pausedDuringWrites[0] = engine.paused), "this run paused the trigger");
		check(pausedDuringWrites[0], "the writes run with the trigger paused");
		check(!engine.paused && engine.calls.equals(Arrays.asList("pause", "resume")), "paused then resumed: " + engine.calls);

		final FakeEngine failing = new FakeEngine();
		try
		{
			matching.whilePaused(failing, () ->
			{
				throw new IllegalStateException("row error");
			});
			check(false, "the writes' exception goes through");
		}
		catch (final IllegalStateException expected)
		{
			check("row error".equals(expected.getMessage()), "the writes' own exception");
		}
		check(!failing.paused && failing.calls.equals(Arrays.asList("pause", "resume")), "resumed after a failure of the writes: " + failing.calls);

		final FakeEngine alreadyPaused = new FakeEngine();
		alreadyPaused.paused = true;
		check(!matching.whilePaused(alreadyPaused, () -> { }), "a table someone else paused is not taken over");
		check(alreadyPaused.paused && alreadyPaused.calls.isEmpty(), "and stays paused, untouched: " + alreadyPaused.calls);

		final FakeEngine untouched = new FakeEngine();
		CdcMatching.from(null).whilePaused(untouched, () -> { });
		check(untouched.calls.isEmpty(), "policy mode never pauses");
	}

	private static void chunksAndDone()
	{
		final CdcMatching matching = CdcMatching.from(map("matching", "afterApply", "matchingChunk", "2"));
		final FakeEngine engine = new FakeEngine();
		final CdcMatching.Outcome outcome = matching.matchWritten(engine,
			Arrays.asList("./code = 'A'", "./code = 'B'", "./code = 'A'", "", "./code = 'C'"), new FakeClock());
		check(outcome.isDone(), "every process DONE: the checkpoint may move: " + outcome.getMessage());
		check(engine.filters.equals(Arrays.asList("(./code = 'A') or (./code = 'B')", "(./code = 'C')")),
			"one process per chunk of 2 distinct keys, blank keys left out: " + engine.filters);
		check(engine.calls.equals(Arrays.asList("match:Default", "match:Default")), "the table's default policy, with the trigger running: " + engine.calls);
		check(outcome.getMessage().startsWith("matching: 3 record(s), policy Default, 2 golden, 1 merged, 2 with suspect(s), 2 process(es)"),
			"the run message says what the matching did: " + outcome.getMessage());

		final FakeEngine named = new FakeEngine();
		check(CdcMatching.from(map("matching", "afterApply", "matchingPolicy", "Company_exact")).matchWritten(named, Arrays.asList("./id = 1"), new FakeClock())
			.isDone() && named.calls.equals(Arrays.asList("match:Company_exact")), "matchingPolicy names the policy: " + named.calls);

		final FakeEngine none = new FakeEngine();
		final CdcMatching.Outcome empty = matching.matchWritten(none, new ArrayList<String>(), new FakeClock());
		check(empty.isDone() && none.calls.isEmpty() && "matching: nothing to match".equals(empty.getMessage()), "a batch of deletes only: nothing to match");

		final FakeEngine off = new FakeEngine();
		final CdcMatching.Outcome offOutcome = CdcMatching.from(map("matching", "off")).matchWritten(off, Arrays.asList("./id = 1"), new FakeClock());
		check(offOutcome.isDone() && off.calls.isEmpty() && offOutcome.getMessage().contains("not matched"), "off: paused, nothing matched, checkpoint moves");
	}

	private static void failuresKeepTheCheckpoint()
	{
		final CdcMatching matching = CdcMatching.from(map("matching", "afterApply"));
		final FakeEngine failed = new FakeEngine();
		failed.statuses = new String[] { "RUNNING", "FAILED" };
		final CdcMatching.Outcome outcome = matching.matchWritten(failed, Arrays.asList("./id = 1"), new FakeClock());
		check(!outcome.isDone() && outcome.getMessage().contains("ended FAILED") && outcome.getMessage().contains("checkpoint kept"),
			"a FAILED process keeps the checkpoint: " + outcome.getMessage());

		final FakeEngine locked = new FakeEngine();
		locked.failMatch = true;
		final CdcMatching.Outcome lockedOutcome = matching.matchWritten(locked, Arrays.asList("./id = 1"), new FakeClock());
		check(!lockedOutcome.isDone() && lockedOutcome.getMessage().contains("dataspace is locked"), "an add-on exception keeps the checkpoint: " + lockedOutcome.getMessage());

		final FakeEngine noPolicy = new FakeEngine();
		noPolicy.policy = null;
		final CdcMatching.Outcome noPolicyOutcome = matching.matchWritten(noPolicy, Arrays.asList("./id = 1"), new FakeClock());
		check(!noPolicyOutcome.isDone() && noPolicyOutcome.getMessage().contains("set input matchingPolicy"), "no default policy: refused, nothing matched");
	}

	private static void timeoutCancels()
	{
		final FakeEngine slow = new FakeEngine();
		slow.statuses = new String[] { "RUNNING" };
		final FakeClock clock = new FakeClock();
		final CdcMatching.Outcome outcome = CdcMatching.from(map("matching", "afterApply", "matchingTimeoutSeconds", "5")).matchWritten(slow,
			Arrays.asList("./id = 1"), clock);
		check(!outcome.isDone() && outcome.getMessage().contains("TIMEOUT (cancelled)"), "the timeout gives up: " + outcome.getMessage());
		check(slow.cancelled, "and cancels the process (a caller that only stops polling leaves the add-on's threads busy)");
		check(clock.now - 1000L >= 5000L && clock.now - 1000L < 7100L, "after about the timeout: " + (clock.now - 1000L) + " ms");
	}

	private static void originAndPublish() throws Exception
	{
		check(CdcOrigin.current() == null, "no origin outside a CDC run");
		final String[] seen = new String[1];
		CdcOrigin.run("CRM", () -> seen[0] = CdcOrigin.current());
		check("CRM".equals(seen[0]) && CdcOrigin.current() == null, "the origin is set during the writes and cleared after");
		check(CdcOrigin.skips("CRM", "CRM") && CdcOrigin.skips("ERP, CRM", "CRM") && CdcOrigin.skips("*", "Any"), "skipOrigin lists or *");
		check(!CdcOrigin.skips("ERP", "CRM") && !CdcOrigin.skips("", "CRM") && !CdcOrigin.skips("CRM", null), "a write of another origin, or none, is sent");

		check(GoldenRecords.publishAs("MERGED") == GoldenRecords.PublishAs.GOLDEN, "MERGED publishes its golden");
		check(GoldenRecords.publishAs("GOLDEN") == GoldenRecords.PublishAs.SELF && GoldenRecords.publishAs("UNSET") == GoldenRecords.PublishAs.SELF
			&& GoldenRecords.publishAs(GoldenRecords.UNMANAGED) == GoldenRecords.PublishAs.SELF, "GOLDEN, UNSET and unmanaged publish themselves");
		check(GoldenRecords.publishAs("DELETED") == GoldenRecords.PublishAs.NOTHING && GoldenRecords.publishAs("PENDING_APPROVAL") == GoldenRecords.PublishAs.NOTHING,
			"DELETED and PENDING_APPROVAL publish nothing");
	}

	/** The order inside runCdc, which needs EBX to run: the matching is inside the applier, so the checkpoint waits for it. */
	private static void source(final String pRoot) throws Exception
	{
		final String src = new String(Files.readAllBytes(Paths.get(pRoot, "src/main/java/com/on/ebx/connect/core/ConnectorService.java")), StandardCharsets.UTF_8);
		final int start = src.indexOf("private ImportOutcome runCdc(");
		final int end = src.indexOf("new CdcCheckpointCoordinator(", start);
		check(start > 0 && end > start, "runCdc and its checkpoint coordinator are found");
		final String applier = src.substring(start, end);
		check(applier.indexOf("matching.whilePaused(") > 0 && applier.indexOf("matching.whilePaused(") < applier.indexOf("matching.matchWritten("),
			"the writes (paused) come before the matching");
		check(applier.indexOf("throw new CdcNotApplied(failures") < applier.indexOf("matching.matchWritten("), "a batch with failed rows is not matched");
		check(applier.indexOf("if (!result.isDone())") > applier.indexOf("matching.matchWritten("), "a matching not DONE throws CdcNotApplied: checkpoint kept");
		check(applier.contains("CdcOrigin.run(pFeed.getConnectorId()"), "the writes carry the connector as their origin");
		final String trigger = new String(Files.readAllBytes(Paths.get(pRoot, "src/main/java/com/on/ebx/connect/ebx/trigger/ConnectorTableTrigger.java")), StandardCharsets.UTF_8);
		final String sync = trigger.substring(trigger.indexOf("private void sync(final ProcedureContext"), trigger.indexOf("private void syncInTransaction("));
		check(sync.indexOf("CdcOrigin.skips(") > 0 && sync.indexOf("CdcOrigin.skips(") < sync.indexOf("this.syncInTransaction("),
			"skipOrigin is decided before either send path");
	}

	private static Map<String, String> map(final String... pPairs)
	{
		final Map<String, String> out = new HashMap<String, String>();
		for (int i = 0; i + 1 < pPairs.length; i += 2)
			out.put(pPairs[i], pPairs[i + 1]);
		return out;
	}

	private static void expectIllegal(final Map<String, String> pInputs, final String pMessage)
	{
		try
		{
			CdcMatching.from(pInputs);
			check(false, "refused: " + pMessage);
		}
		catch (final IllegalArgumentException expected)
		{
			check(expected.getMessage().startsWith(pMessage), "message '" + expected.getMessage() + "' starts with '" + pMessage + "'");
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
