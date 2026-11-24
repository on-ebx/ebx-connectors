package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What makes an unattended run a failure: the decision the scheduled task fails on and the workflow
 * task exposes, exercised on outcomes built the way the engine builds them. That EBX records a
 * thrown {@code OperationException} as a failed execution and keeps firing the schedule is read from
 * its bytecode, not run here.
 */
public final class RunVerdictTest
{
	private static final Set<RunVerdict.Cause> NONE = EnumSet.noneOf(RunVerdict.Cause.class);

	public static void main(final String[] pArgs) throws Exception
	{
		// a clean feed run succeeds
		final ImportOutcome clean = new ImportOutcome();
		fold(clean, item(SyncResult.Status.OK, "a"));
		fold(clean, item(SyncResult.Status.NO_CHANGE, "b"));
		check(RunVerdict.of(clean).isClean(), "written and unchanged items are a success");
		check(RunVerdict.of(clean).failureMessage("R1", NONE, "maxRecords", "tolerate") == null, "a clean run has no failure message");
		check(RunVerdict.of(new ImportOutcome()).isClean(), "a source with nothing to give is a success");

		// items in error: a failure, even next to successes (the engine's PARTIAL)
		final ImportOutcome partial = new ImportOutcome();
		fold(partial, item(SyncResult.Status.OK, "a"));
		fold(partial, error("b", "Value must not exceed 10 character(s)"));
		final RunVerdict partialVerdict = RunVerdict.of(partial);
		check(partialVerdict.has(RunVerdict.Cause.ERRORS) && "PARTIAL".equals(partial.status()), "one item in error is a cause");
		final String message = partialVerdict.failureMessage("R2", NONE, "maxRecords", "tolerate");
		check(message != null && message.contains("R2") && message.contains("1 item(s) in error") && message.contains("must not exceed"), "the message says what failed: " + message);
		check(message.contains("'tolerate' to errors"), "and how to accept it: " + message);
		check(partialVerdict.failureMessage("R2", RunVerdict.tolerated("errors"), "maxRecords", "tolerate") == null, "tolerate=errors lets it pass");

		// the run failed as a whole: no item counted, the engine's own status reads NO_DATA - still a failure
		final ImportOutcome down = new ImportOutcome();
		down.addResult(error("", "Connection refused"));
		check("NO_DATA".equals(down.status()) && down.getErrorCount() == 0, "the counters of a failed read say nothing");
		final RunVerdict downVerdict = RunVerdict.of(down);
		check(downVerdict.has(RunVerdict.Cause.ERRORS) && downVerdict.describe().contains("Connection refused"), "a failed read is a cause: " + downVerdict.describe());

		// truncation: a failure that names the parameter to raise
		final ImportOutcome cut = new ImportOutcome();
		fold(cut, item(SyncResult.Status.OK, "a"));
		cut.markTruncated(214, "Limit reached");
		final RunVerdict cutVerdict = RunVerdict.of(cut);
		final String cutMessage = cutVerdict.failureMessage("R3", NONE, "maxRecords", "tolerate");
		check(cutVerdict.has(RunVerdict.Cause.TRUNCATED) && cutMessage.contains("214") && cutMessage.contains("Raise the task parameter 'maxRecords'"),
			"a cut read says how much the source held and what to raise: " + cutMessage);
		check(cutVerdict.failureMessage("R3", RunVerdict.tolerated("errors"), "maxRecords", "tolerate") != null, "tolerating errors does not tolerate a cut read");
		check(cutVerdict.failureMessage("R3", RunVerdict.tolerated("truncated"), "maxRecords", "tolerate") == null, "tolerate=truncated does");
		final ImportOutcome cutUnknown = new ImportOutcome();
		fold(cutUnknown, item(SyncResult.Status.OK, "a"));
		cutUnknown.markTruncated(-1, "Limit reached");
		check(RunVerdict.of(cutUnknown).has(RunVerdict.Cause.TRUNCATED), "a cut read with no known total is a cause too");

		// rejected items, conflicts and a stopped run are each their own cause
		final ImportOutcome soft = new ImportOutcome();
		fold(soft, item(SyncResult.Status.OK, "a"));
		fold(soft, item(SyncResult.Status.SKIPPED, "b"));
		final SyncResult conflicting = item(SyncResult.Status.OK, "c");
		conflicting.getConflicts().add(null);
		fold(soft, conflicting);
		soft.skipped(40);
		final RunVerdict softVerdict = RunVerdict.of(soft);
		check(softVerdict.getCauses().equals(EnumSet.of(RunVerdict.Cause.REJECTED, RunVerdict.Cause.CONFLICTS, RunVerdict.Cause.STOPPED)), "causes: " + softVerdict.getCauses());
		check("rejected,conflicts,stopped".equals(softVerdict.keys()), "as a workflow reads them: " + softVerdict.keys());
		check(softVerdict.failing(RunVerdict.tolerated("conflicts, rejected")).equals(EnumSet.of(RunVerdict.Cause.STOPPED)), "what is left once two are tolerated");
		check(softVerdict.failureMessage("R4", RunVerdict.tolerated("all"), "maxRecords", "tolerate") == null, "tolerate=all accepts everything");

		// a list of record results: errors and partial writes fail, no data and not eligible do not
		final SyncResult fieldError = item(SyncResult.Status.OK, "p");
		fieldError.getErrors().add("./count: conversion failed");
		final List<SyncResult> records = Arrays.asList(item(SyncResult.Status.OK, "a"), item(SyncResult.Status.NO_DATA, "b"), item(SyncResult.Status.SKIPPED, "c"));
		check(RunVerdict.of(records, false).isClean(), "no data at the source and a record not eligible are not failures");
		check(RunVerdict.of(Arrays.asList(item(SyncResult.Status.OK, "a"), fieldError), false).has(RunVerdict.Cause.ERRORS), "a record written with field errors is");
		check(RunVerdict.of(Arrays.asList(error("x", "HTTP 500")), false).describe().contains("HTTP 500"), "a failed call is, with its message");
		check(RunVerdict.of(records, true).has(RunVerdict.Cause.TRUNCATED), "a table holding more than the maximum is a cut read");

		// several mappings in one step
		final RunVerdict merged = RunVerdict.clean().merge(cutVerdict).merge(partialVerdict);
		check(merged.has(RunVerdict.Cause.TRUNCATED) && merged.has(RunVerdict.Cause.ERRORS) && "errors,truncated".equals(merged.keys()), "merged: " + merged.keys());

		// the tolerate property
		check(RunVerdict.tolerated(null).isEmpty() && RunVerdict.tolerated(" ").isEmpty() && RunVerdict.tolerated("none").isEmpty() && RunVerdict.tolerated("false").isEmpty(),
			"nothing, none and false tolerate nothing");
		check(RunVerdict.tolerated("ALL").size() == RunVerdict.Cause.values().length && RunVerdict.tolerated("true").size() == RunVerdict.Cause.values().length, "all and true tolerate everything");
		check(RunVerdict.tolerated("Errors; truncated").equals(EnumSet.of(RunVerdict.Cause.ERRORS, RunVerdict.Cause.TRUNCATED)), "a list, any case, comma or semicolon");
		boolean refused = false;
		try
		{
			RunVerdict.tolerated("erors");
		}
		catch (final IllegalArgumentException expected)
		{
			refused = expected.getMessage().contains("erors");
		}
		check(refused, "a misspelt cause is refused, not ignored");

		// a secret in a failure message never reaches the execution report
		final ImportOutcome leaking = new ImportOutcome();
		leaking.addResult(error("", "GET https://api.example.test/v1?api_key=SECRETVALUE123 failed"));
		check(!RunVerdict.of(leaking).failureMessage("R5", NONE, "maxRecords", "tolerate").contains("SECRETVALUE123"), "the message is redacted");

		System.out.println("PASS: an unattended run fails on errors, a failed read, rejected items, conflicts, a cut read or a stop, unless the cause is tolerated by name");
	}

	/** Mirrors ConnectorService.tallyItem. */
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

	private static SyncResult item(final SyncResult.Status pStatus, final String pKey)
	{
		final SyncResult r = new SyncResult("Src", "op", "IMPORT", "DS", "/root/T", pKey, pKey);
		r.setStatus(pStatus);
		return r;
	}

	private static SyncResult error(final String pKey, final String pMessage)
	{
		final SyncResult r = RunVerdictTest.item(SyncResult.Status.ERROR, pKey);
		r.setMessage(pMessage);
		return r;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
