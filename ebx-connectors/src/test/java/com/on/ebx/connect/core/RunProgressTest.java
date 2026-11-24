package com.on.ebx.connect.core;

import java.util.Date;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** What the run panel of the sync screen reads of a run: state, counters, whether it can be continued (audit C-19). */
public final class RunProgressTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectNode running = Json.object();
		running.put("status", "RUNNING");
		running.put("created", 10);
		running.put("updated", 5);
		running.put("noChange", 100);
		running.put("errorCount", 2);
		running.put("skipped", 3);
		running.put("rejected", 1);
		running.put("nextIndex", 121);
		running.put("startedAt", "2026-10-06T10:00:00");
		running.put("updatedAt", "2026-10-06T10:00:30");
		final RunProgress progress = RunProgress.of(running);
		check(progress.isRunning() && !progress.isTerminal() && !progress.isResumable(), "a running run is neither terminal nor resumable");
		check(progress.getProcessed() == 121, "processed counts every outcome: " + progress.getProcessed());
		final Date at = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse("2026-10-06T10:02:05");
		check(progress.elapsedSeconds(at) == 125L, "a running run counts until now: " + progress.elapsedSeconds(at));

		// an ended run counts until its last save
		running.put("status", "STOPPED");
		final RunProgress stopped = RunProgress.of(running);
		check(stopped.isTerminal() && stopped.isResumable() && stopped.getNextIndex() == 121, "a stopped run with a saved position can be continued");
		check(stopped.elapsedSeconds(at) == 30L, "an ended run keeps the time it took: " + stopped.elapsedSeconds(at));
		running.put("status", "INTERRUPTED");
		check(RunProgress.of(running).isResumable(), "an interrupted run can be continued");
		running.put("status", "ERROR");
		check(RunProgress.of(running).isResumable(), "a failed run that saved a position can be continued");
		running.put("nextIndex", 0);
		check(!RunProgress.of(running).isResumable(), "no saved position, nothing to continue from");
		running.put("nextIndex", 50);
		for (final String status : new String[] { "DONE", "PARTIAL" })
		{
			running.put("status", status);
			check(RunProgress.of(running).isTerminal() && !RunProgress.of(running).isResumable(), status + " is the end of the source: nothing to continue");
		}

		// a run whose row cannot be read
		final RunProgress unknown = RunProgress.of(null);
		check(RunProgress.UNKNOWN.equals(unknown.getStatus()) && !unknown.isTerminal() && !unknown.isRunning() && unknown.getProcessed() == 0, "no row: unknown, not terminal");
		check(unknown.elapsedSeconds(at) == 0L, "no start, no elapsed time");

		// truncation and messages
		final ObjectNode cut = Json.object();
		cut.put("status", "PARTIAL");
		cut.put("truncated", true);
		cut.put("available", 9000);
		cut.put("message", "stopped at the maximum");
		cut.put("firstErrors", "k1: boom");
		final RunProgress truncated = RunProgress.of(cut);
		check(truncated.isTruncated() && truncated.getAvailable() == 9000 && "stopped at the maximum".equals(truncated.getMessage()) && "k1: boom".equals(truncated.getFirstErrors()), "truncation and messages are read");
		System.out.println("PASS: the run panel reads state, counters, elapsed time and the possibility to continue from the run status");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
