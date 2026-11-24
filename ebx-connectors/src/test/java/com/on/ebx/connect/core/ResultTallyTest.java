package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Volume lot 7a: {@code OpenLineageEmitter.ResultTally} replaces the per-run {@code List<SyncResult>}
 * buffer {@code ConnectorService.lineage} used to accumulate for the whole run before
 * {@code OpenLineageEmitter.event} walked it once at the end - now the same counts are built
 * incrementally, one result at a time, with a small bounded sample of failing keys instead of the
 * full list. This exercises the counting (sent/failed/partial/skipped/deleted), the dataspace
 * fallback, {@code worthAnEvent()}'s dry-run veto, and the sample-key bound - all pure, no EBX type.
 */
public final class ResultTallyTest
{
	public static void main(final String[] args) throws Exception
	{
		final MappingDefinition inbound = new MappingDefinition(1, "Src", "query", MappingDefinition.Direction.INBOUND,
			"Feed", "DS", "DS", "/root/T", "", true);

		// counting: OK -> sent, PARTIAL/ERROR (hasFailure) -> failed (+ partial when status is exactly PARTIAL),
		// a bare SKIPPED/NO_DATA/NO_CHANGE/DRY_RUN with no failure -> skipped
		final OpenLineageEmitter.ResultTally tally = new OpenLineageEmitter.ResultTally("DS");
		tally.add(inbound, ok("k1"));
		tally.add(inbound, ok("k2"));
		tally.add(inbound, partial("k3", "half done"));
		tally.add(inbound, error("k4", "boom"));
		tally.add(inbound, skipped("k5"));

		check(tally.sent == 2, "two OK results: sent=" + tally.sent);
		check(tally.failed == 2, "PARTIAL and ERROR both count as failed: failed=" + tally.failed);
		check(tally.partial == 1, "only the PARTIAL-status one counts as partial: partial=" + tally.partial);
		check(tally.skipped == 1, "a bare SKIPPED with no failure counts as skipped: skipped=" + tally.skipped);
		check("half done".equals(tally.firstError), "firstError is the FIRST failing message seen (k3, before k4): " + tally.firstError);
		check(tally.firstFailedKeys.size() == 2, "one sample key per failing result: " + tally.firstFailedKeys);
		check(tally.worthAnEvent(), "a run with real successes and failures is worth an event");

		// deleted: OK status, operation equals the mapping's OWN deleteOperation, and that differs from
		// the mapping's main operation (otherwise a delete-flavoured mapping would double-count itself)
		final MappingDefinition outboundWithDelete = new MappingDefinition(2, "Src", "upsert", MappingDefinition.Direction.OUTBOUND,
			"Push", "DS", "DS", "/root/T", "", true);
		outboundWithDelete.setOutboundPolicy("delete", 5, 30);
		final OpenLineageEmitter.ResultTally deletions = new OpenLineageEmitter.ResultTally("DS");
		final SyncResult deletedResult = new SyncResult("Src", "delete", "OUTBOUND", "DS", "/root/T", "k9", "k9");
		deletedResult.setStatus(SyncResult.Status.OK);
		deletions.add(outboundWithDelete, deletedResult);
		check(deletions.deleted == 1 && deletions.sent == 0, "a delete-operation OK result counts as deleted, not sent");

		// dry run vetoes the whole event, even alongside a real failure - matches the old
		// OpenLineageEmitter.worthAnEvent(List) contract exactly (a simulation never reports observed lineage)
		final OpenLineageEmitter.ResultTally dryRun = new OpenLineageEmitter.ResultTally("DS");
		dryRun.add(inbound, error("k1", "would fail"));
		final SyncResult simulated = new SyncResult("Src", "query", "IMPORT", "DS", "/root/T", "k2", "k2");
		simulated.setStatus(SyncResult.Status.DRY_RUN);
		dryRun.add(inbound, simulated);
		check(!dryRun.worthAnEvent(), "any DRY_RUN result in the tally vetoes the whole event, even with real failures alongside");

		// an untouched tally (nothing added, or only skips/no-data) is not worth an event either
		final OpenLineageEmitter.ResultTally untouched = new OpenLineageEmitter.ResultTally("DS");
		check(!untouched.worthAnEvent(), "nothing added: not worth an event");
		untouched.add(inbound, skipped("only"));
		check(!untouched.worthAnEvent(), "only a bare skip, no success and no failure: still not worth an event");

		// dataspace fallback: the mapping's own dataspace wins when set; a null-dataspace mapping picks
		// up the first non-blank dataspace it sees on a result
		final MappingDefinition noDataspace = new MappingDefinition(3, "Src", "query", MappingDefinition.Direction.INBOUND,
			"Feed", null, "DS", "/root/T", "", true);
		final OpenLineageEmitter.ResultTally fallback = new OpenLineageEmitter.ResultTally(noDataspace.getDataspace());
		check(fallback.dataspace == null, "no dataspace yet");
		final SyncResult runtimeResult = new SyncResult("Src", "query", "IMPORT", "Runtime", "/root/T", "k1", "k1");
		runtimeResult.setStatus(SyncResult.Status.OK);
		fallback.add(noDataspace, runtimeResult);
		check("Runtime".equals(fallback.dataspace), "the first result's dataspace fills the gap: " + fallback.dataspace);

		// the sample of failing keys is bounded (MAX_SAMPLE_KEYS), even when many more results fail
		final OpenLineageEmitter.ResultTally many = new OpenLineageEmitter.ResultTally("DS");
		final List<SyncResult> lots = new ArrayList<SyncResult>();
		for (int i = 0; i < OpenLineageEmitter.ResultTally.MAX_SAMPLE_KEYS + 10; i++)
		{
			lots.add(error("k" + i, "fail " + i));
		}
		for (final SyncResult r : lots) many.add(inbound, r);
		check(many.failed == OpenLineageEmitter.ResultTally.MAX_SAMPLE_KEYS + 10, "every failure is still counted: " + many.failed);
		check(many.firstFailedKeys.size() == OpenLineageEmitter.ResultTally.MAX_SAMPLE_KEYS,
			"the sample itself never grows past the bound: " + many.firstFailedKeys.size());

		System.out.println("ResultTallyTest: PASS");
	}

	private static SyncResult ok(final String key)
	{
		final SyncResult r = new SyncResult("Src", "query", "IMPORT", "DS", "/root/T", key, key);
		r.setStatus(SyncResult.Status.OK);
		return r;
	}

	private static SyncResult skipped(final String key)
	{
		final SyncResult r = new SyncResult("Src", "query", "IMPORT", "DS", "/root/T", key, key);
		r.setStatus(SyncResult.Status.SKIPPED);
		return r;
	}

	private static SyncResult partial(final String key, final String message)
	{
		final SyncResult r = new SyncResult("Src", "query", "IMPORT", "DS", "/root/T", key, key);
		r.setStatus(SyncResult.Status.PARTIAL);
		r.setMessage(message);
		return r;
	}

	private static SyncResult error(final String key, final String message)
	{
		final SyncResult r = new SyncResult("Src", "query", "IMPORT", "DS", "/root/T", key, key);
		r.setStatus(SyncResult.Status.ERROR);
		r.setMessage(message);
		return r;
	}

	private static void check(final boolean condition, final String what)
	{
		if (!condition)
		{
			throw new AssertionError(what);
		}
	}
}
