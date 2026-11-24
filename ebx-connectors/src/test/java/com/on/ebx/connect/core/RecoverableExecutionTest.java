package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.List;

/** Deterministic contract for checkpoint, retry, cancellation, replay and deletion semantics. */
public final class RecoverableExecutionTest
{
	public static void main(final String[] pArgs)
	{
		partialFailureIsRetriedAndDeadLettered();
		nonRetryableFailureIsNotLost();
		cancellationResumesWithoutRepeatingCompletedWork();
		dryRunDoesNotAdvanceCheckpoint();
		deletionPoliciesAreExplicit();
		System.out.println("PASS: recoverable execution foundation");
	}

	private static void nonRetryableFailureIsNotLost()
	{
		final RecoverableExecution.MemoryStateStore store = new RecoverableExecution.MemoryStateStore();
		final Probe probe = new Probe("bad");
		final RecoverableExecution.RetryClassifier never = new RecoverableExecution.RetryClassifier() { public boolean isRetryable(final Exception pFailure) { return false; } };
		final List<RecoverableExecution.ItemResult> result = RecoverableExecution.run("terminal", works(), policy(5, RecoverableExecution.DeletionPolicy.PROPAGATE),
			new RecoverableExecution.Overrides(10, false), store, null, never, probe);
		check(result.get(1).getStatus() == RecoverableExecution.Status.DEAD_LETTER && result.get(1).getAttempts() == 1, "terminal failure lost");
		check(store.load("terminal").getDeadLetters().contains("bad"), "terminal failure not persisted");
	}

	private static void partialFailureIsRetriedAndDeadLettered()
	{
		final RecoverableExecution.MemoryStateStore store = new RecoverableExecution.MemoryStateStore();
		final Probe probe = new Probe("bad");
		final List<RecoverableExecution.ItemResult> result = RecoverableExecution.run("run-1", works(), policy(2, RecoverableExecution.DeletionPolicy.PROPAGATE),
			new RecoverableExecution.Overrides(10, false), store, null, retryAll(), probe);
		check(result.size() == 3 && result.get(0).getStatus() == RecoverableExecution.Status.SUCCESS, "success lost");
		check(result.get(1).getStatus() == RecoverableExecution.Status.DEAD_LETTER && result.get(1).getAttempts() == 2, "DLQ/retries missing");
		check(result.get(2).getStatus() == RecoverableExecution.Status.SUCCESS, "partial failure stopped peers");
		check(!result.get(0).getIdempotencyKey().equals(result.get(2).getIdempotencyKey()), "keys not item-specific");
		final RecoverableExecution.Checkpoint saved = store.load("run-1");
		check(saved.getDeadLetters().contains("bad") && saved.getNextIndex() == 3, "checkpoint state missing");
		store.save(RecoverableExecution.replayDeadLetters(saved));
		probe.failing = null;
		final List<RecoverableExecution.ItemResult> replay = RecoverableExecution.run("run-1", works(), policy(2, RecoverableExecution.DeletionPolicy.PROPAGATE),
			new RecoverableExecution.Overrides(10, false), store, null, retryAll(), probe);
		check(replay.size() == 1 && "bad".equals(replay.get(0).getId()) && replay.get(0).getStatus() == RecoverableExecution.Status.SUCCESS, "bounded replay failed");
		check(result.get(1).getIdempotencyKey().equals(replay.get(0).getIdempotencyKey()), "replay key changed");
	}

	private static void cancellationResumesWithoutRepeatingCompletedWork()
	{
		final RecoverableExecution.MemoryStateStore store = new RecoverableExecution.MemoryStateStore();
		final Probe probe = new Probe(null);
		final int[] checks = { 0 };
		final RecoverableExecution.Cancellation stopAfterOne = new RecoverableExecution.Cancellation() { public boolean isCancelled() { return checks[0]++ >= 2; } };
		final List<RecoverableExecution.ItemResult> first = RecoverableExecution.run("resume", works(), policy(1, RecoverableExecution.DeletionPolicy.PROPAGATE),
			new RecoverableExecution.Overrides(3, false), store, stopAfterOne, retryAll(), probe);
		check(first.get(first.size() - 1).getStatus() == RecoverableExecution.Status.CANCELLED, "cancellation missing");
		final int calls = probe.executes;
		RecoverableExecution.run("resume", works(), policy(1, RecoverableExecution.DeletionPolicy.PROPAGATE),
			new RecoverableExecution.Overrides(3, false), store, null, retryAll(), probe);
		check(probe.executes > calls && probe.executes == 3, "resume repeated or skipped work");
	}

	private static void dryRunDoesNotAdvanceCheckpoint()
	{
		final RecoverableExecution.MemoryStateStore store = new RecoverableExecution.MemoryStateStore();
		final Probe probe = new Probe(null);
		final List<RecoverableExecution.ItemResult> results = RecoverableExecution.run("dry", works(), policy(1, RecoverableExecution.DeletionPolicy.PROPAGATE),
			new RecoverableExecution.Overrides(1, true), store, null, retryAll(), probe);
		check(results.size() == 1 && results.get(0).getStatus() == RecoverableExecution.Status.DRY_RUN, "dry run result");
		check(probe.executes == 0 && store.load("dry") == null, "dry run mutated state");
	}

	private static void deletionPoliciesAreExplicit()
	{
		for (final RecoverableExecution.DeletionPolicy deletion : RecoverableExecution.DeletionPolicy.values())
		{
			final Probe probe = new Probe(null);
			final List<RecoverableExecution.Work<String>> work = Arrays.asList(new RecoverableExecution.Work<String>("gone", "x", true));
			final List<RecoverableExecution.ItemResult> result = RecoverableExecution.run("delete-" + deletion, work, policy(1, deletion),
				new RecoverableExecution.Overrides(1, false), new RecoverableExecution.MemoryStateStore(), null, retryAll(), probe);
			if (deletion == RecoverableExecution.DeletionPolicy.PROPAGATE) check(probe.deletes == 1, "delete not propagated");
			if (deletion == RecoverableExecution.DeletionPolicy.TOMBSTONE) check(probe.tombstones == 1, "tombstone missing");
			if (deletion == RecoverableExecution.DeletionPolicy.IGNORE) check(result.get(0).getStatus() == RecoverableExecution.Status.DELETION_IGNORED, "ignore missing");
		}
	}

	private static List<RecoverableExecution.Work<String>> works() { return Arrays.asList(work("one"), work("bad"), work("three")); }
	private static RecoverableExecution.Work<String> work(final String pId) { return new RecoverableExecution.Work<String>(pId, pId, false); }
	private static RecoverableExecution.Policy policy(final int pAttempts, final RecoverableExecution.DeletionPolicy pDeletion) { return new RecoverableExecution.Policy(10, pAttempts, pDeletion); }
	private static RecoverableExecution.RetryClassifier retryAll() { return new RecoverableExecution.RetryClassifier() { public boolean isRetryable(final Exception pFailure) { return true; } }; }
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }

	private static final class Probe implements RecoverableExecution.Processor<String>
	{
		private String failing; private int executes, deletes, tombstones;
		private Probe(final String pFailing) { failing = pFailing; }
		public void execute(final String pValue, final String pKey) throws Exception { executes++; if (pValue.equals(failing)) throw new Exception("temporary token=secret"); }
		public void delete(final String pValue, final String pKey) { deletes++; }
		public void tombstone(final String pValue, final String pKey) { tombstones++; }
	}
}
