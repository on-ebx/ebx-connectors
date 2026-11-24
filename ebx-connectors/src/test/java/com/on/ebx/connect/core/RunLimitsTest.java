package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Audit C-35: one documented default of {@code max} per family of entry points, a run cut at it that always says so,
 * run-time inputs that are never dropped in silence, and rule policies that are a choice. Pure parts only: the EBX
 * reads and writes around them (a request walked, a rule row created) were not run here.
 */
public final class RunLimitsTest
{
	public static void main(final String[] pArgs)
	{
		// the defaults, unchanged in value, now named in one place
		check(RunLimits.SCHEDULER == 100 && RunLimits.API == 200 && RunLimits.SCREEN_RECORDS == 100 && RunLimits.SCREEN_FEED == 499
			&& RunLimits.WEBHOOK == 10000 && RunLimits.PUBLICATION == 0, "the defaults keep their values");

		// a run on records or a scope cut at max: a notice the summary turns into truncated, PARTIAL and a sentence
		final MappingDefinition mapping = new MappingDefinition(7, "Kafka", "publish", MappingDefinition.Direction.OUTBOUND, "Send companies", "Companies",
			"Companies", "/root/Companies", null, true);
		final SyncResult notice = SyncResult.limitNotice(mapping, "OUTBOUND", "R1", 200, "scope UNSENT", true);
		check(notice.isLimitNotice() && notice.getAvailable() == -1, "a notice without a known total is still a limit notice");
		check(notice.getStatus() == SyncResult.Status.SKIPPED && !notice.isSuccess(), "the notice is not a success");
		check(notice.getMessage().contains("200 record(s) of scope UNSENT") && notice.getMessage().contains("a later run takes the rest"),
			"a draining scope says a later run takes the rest: " + notice.getMessage());
		check(RunLimits.recordsLimitMessage(100, "table /root/T", false).contains("raise max"), "a table cut says to raise max");
		check(!RunLimits.recordsLimitMessage(100, "table /root/T", false).contains("later run"), "a table starts again from its first record: no false promise");
		check("R1".equals(notice.getRunId()) && "7".equals(notice.getMappingId()), "the notice belongs to its run and mapping");

		final SyncResult sent = item(SyncResult.Status.OK);
		final SyncResult other = item(SyncResult.Status.OK);
		final List<SyncResult> cut = Arrays.asList(sent, other, notice);
		final SyncResult.Summary summary = SyncResult.summarize(cut);
		check(summary.truncated && notice.getMessage().equals(summary.limitMessage), "the summary is truncated and carries the sentence");
		check("PARTIAL".equals(summary.status()), "a cut run is never OK: " + summary.status());
		check(SyncResult.countWithoutLimitNotices(cut) == 2, "the notice is not counted as a record");
		check(notice.getMessage().equals(SyncResult.limitMessage(cut)), "the notice's sentence is found in the results");
		check(SyncResult.limitMessage(Arrays.asList(sent, other)) == null && !SyncResult.summarize(Arrays.asList(sent, other)).truncated,
			"a run within max has no notice and is not truncated");
		check("OK".equals(SyncResult.summarize(Arrays.asList(sent, other)).status()), "and stays OK");

		// a feed's notice with a known total is still found the old way
		final SyncResult known = item(SyncResult.Status.SKIPPED);
		known.setAvailable(1500);
		check(known.isLimitNotice() && SyncResult.summarize(Collections.singletonList(known)).available == 1500, "a feed notice with a total keeps it");

		// ignored inputs: said, and a plain OK becomes WARNING; every other status is left alone
		final String sentence = RunLimits.ignoredInputsMessage(Arrays.asList("loadId", "region"));
		check(sentence.contains("loadId, region") && sentence.contains("${param.name}"), "the sentence names the inputs: " + sentence);
		check("WARNING".equals(RunLimits.answerStatus("OK", true)), "OK with an ignored input reads WARNING");
		check("OK".equals(RunLimits.answerStatus("OK", false)), "OK without a warning stays OK");
		for (final String status : new String[] { "PARTIAL", "ERROR", "NO_DATA", "RUNNING" })
		{
			check(status.equals(RunLimits.answerStatus(status, true)), status + " is left as it is");
		}

		// the input names of a run that reads none of them
		check(Arrays.asList("loadId", "receivedAt", "flag").equals(ConnectorService.inputNames("loadId=42; receivedAt=2026-10-07T10:00:00\nflag")),
			"names are read from name=value pairs and bare names: " + ConnectorService.inputNames("loadId=42; receivedAt=2026-10-07T10:00:00\nflag"));
		check(ConnectorService.inputNames(null).isEmpty() && ConnectorService.inputNames("  ").isEmpty(), "no input, no name");

		// rule policies: key fields stay IF_EMPTY, the choice applies to the others
		final Set<String> keys = RulePolicies.keyTargets("./Identification/Code='${code}' and ./_sourceSystem='${source}'", Arrays.asList(" ./type", "/line"),
			"./company");
		check(keys.containsAll(Arrays.asList("./Identification/Code", "./_sourceSystem", "./type", "./line", "./company")) && keys.size() == 5,
			"the key targets are the predicate's fields, the child keys and the parent link: " + keys);
		check("IF_EMPTY".equals(RulePolicies.forNewRule("ALWAYS", "/Identification/Code", keys)), "a key field stays IF_EMPTY");
		check("ALWAYS".equals(RulePolicies.forNewRule("always", "./Address/City", keys)), "another field takes the choice");
		check("IF_EMPTY".equals(RulePolicies.forNewRule(null, "./Address/City", keys)), "no choice, the default");
		check(RulePolicies.keyTargets(null, null, null).isEmpty(), "a mapping without key has no key target");

		final RulePolicies.Tally tally = new RulePolicies.Tally();
		tally.add(null, "INBOUND");
		tally.add("  ", "INBOUND");
		tally.add("ALWAYS", "INBOUND");
		check(tally.defaulted() == 2 && tally.byPolicy().get("IF_EMPTY").intValue() == 2 && tally.byPolicy().get("ALWAYS").intValue() == 1
			&& tally.byPolicy().get("TRUSTED").intValue() == 0, "the tally counts per policy and the defaults: " + tally.byPolicy());
		check(tally.note() != null && tally.note().startsWith("2 rule(s) sent without a policy were written IF_EMPTY") && tally.note().contains("ALWAYS"),
			"the answer says which policy was applied and what it means: " + tally.note());
		final RulePolicies.Tally outbound = new RulePolicies.Tally();
		outbound.add(null, "OUTBOUND");
		check(outbound.defaulted() == 1 && outbound.note() == null, "an outbound mapping builds a document: no note about overwriting");
		final RulePolicies.Tally explicit = new RulePolicies.Tally();
		explicit.add("IF_EMPTY", "INBOUND");
		check(explicit.defaulted() == 0 && explicit.note() == null, "an explicit IF_EMPTY is a choice: no note");

		System.out.println("PASS: max defaults, limit notices, ignored inputs and rule policies (audit C-35)");
	}

	private static SyncResult item(final SyncResult.Status pStatus)
	{
		final SyncResult result = new SyncResult("Kafka", "publish", "OUTBOUND", "Companies", "/root/Companies", "1", "one");
		result.setStatus(pStatus);
		return result;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
