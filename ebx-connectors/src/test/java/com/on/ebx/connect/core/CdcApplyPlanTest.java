package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.connectors.KafkaCdcExecution;

/**
 * Gartner lot B, offline: Debezium events to EBX actions (upsert, delete, flag, ignore), per-key collapse
 * inside a batch, and an end-to-end replay on an in-memory table proving the apply is idempotent and the
 * run resumable - a crash after a partial apply keeps the checkpoint and the replay converges.
 */
public final class CdcApplyPlanTest
{
	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	private static final String TOPIC = "erp.public.supplier";

	private static DebeziumKafkaCdcAdapter.Record event(final long pOffset, final String pOp, final int pId, final String pName)
	{
		final ObjectNode key = Json.object().put("id", pId);
		final ObjectNode payload = Json.object().put("op", pOp);
		final ObjectNode row = Json.object().put("id", pId).put("name", pName);
		if ("d".equals(pOp)) payload.set("before", Json.object().put("id", pId));
		else payload.set("after", row);
		payload.putObject("source").put("snapshot", "r".equals(pOp) ? "true" : "false");
		final ObjectNode value = Json.object();
		value.set("payload", payload);
		return new DebeziumKafkaCdcAdapter.Record(TOPIC, 0, pOffset, key, value);
	}

	private static DebeziumKafkaCdcAdapter.Record tombstone(final long pOffset, final int pId)
	{
		return new DebeziumKafkaCdcAdapter.Record(TOPIC, 0, pOffset, Json.object().put("id", pId), null);
	}

	private static Map<String, String> inputs(final String... pPairs)
	{
		final Map<String, String> out = new HashMap<String, String>();
		for (int i = 0; i + 1 < pPairs.length; i += 2) out.put(pPairs[i], pPairs[i + 1]);
		return out;
	}

	/** The EBX table of the replay test: id to row, a delete removes, a flag sets deleted=true. */
	static final class Table
	{
		final Map<Integer, ObjectNode> rows = new LinkedHashMap<Integer, ObjectNode>();

		void apply(final CdcApplyPlan.Plan pPlan, final CdcApplyPlan.Options pOptions, final int pCrashAfter)
		{
			int done = 0;
			for (final ObjectNode upsert : pPlan.getUpserts())
			{
				if (done++ == pCrashAfter) throw new IllegalStateException("crash");
				final ObjectNode row = this.rows.containsKey(Integer.valueOf(upsert.path("id").asInt())) ? this.rows.get(Integer.valueOf(upsert.path("id").asInt())) : Json.object();
				row.put("id", upsert.path("id").asInt()).put("name", upsert.path("name").asText()).put("deleted", false);
				this.rows.put(Integer.valueOf(upsert.path("id").asInt()), row);
			}
			for (final ObjectNode delete : pPlan.getDeletes())
			{
				if (done++ == pCrashAfter) throw new IllegalStateException("crash");
				final Integer id = Integer.valueOf(delete.path("id").asInt());
				if (pOptions.getDeleteMode() == CdcApplyPlan.DeleteMode.DELETE) this.rows.remove(id);
				else if (this.rows.containsKey(id)) this.rows.get(id).put("deleted", true);
			}
		}
	}

	public static void main(final String[] args) throws Exception
	{
		final List<DebeziumKafkaCdcAdapter.Record> records = Arrays.asList(
			event(0, "r", 1, "Acme"),       // snapshot
			event(1, "c", 2, "Globex"),
			event(2, "u", 1, "Acme SA"),    // supersedes the snapshot of 1
			event(3, "d", 2, null),         // 2 created then deleted in the same batch
			tombstone(4, 2),
			event(5, "c", 3, "Initech"),
			event(6, "d", 3, null),
			event(7, "c", 3, "Initech 2"),  // deleted then re-created: an upsert
			event(8, "d", 4, null));        // a record EBX may not have
		final CdcBatch batch = DebeziumKafkaCdcAdapter.normalize(records, "", 100, 1000000);

		final CdcApplyPlan.Plan plan = CdcApplyPlan.plan(batch, CdcApplyPlan.Options.from(inputs()));
		check(plan.getEvents() == 9 && plan.getTombstones() == 1, "events and tombstone counted");
		check(plan.getUpserts().size() == 2, "1 (last image) and 3 (re-created): " + plan.getUpserts());
		check("Acme SA".equals(plan.getUpserts().get(0).path("name").asText()) && "u".equals(plan.getUpserts().get(0).path("__op").asText()), "last image of a key wins");
		check("Initech 2".equals(plan.getUpserts().get(1).path("name").asText()) && !plan.getUpserts().get(1).path("__deleted").asBoolean(), "re-creation clears __deleted");
		check(plan.getDeletes().size() == 2 && plan.getDeletes().get(0).path("id").asInt() == 2 && plan.getDeletes().get(0).path("__deleted").asBoolean(), "deletes of 2 and 4");
		check((TOPIC + ":0:3").equals(plan.getDeletes().get(0).path("__position").asText()), "position kept on the row");
		check(plan.getCollapsed() == 4, "4 events superseded inside the batch: " + plan.getCollapsed());
		check(plan.summary(CdcApplyPlan.DeleteMode.DELETE).startsWith("CDC: 9 event(s) -> 2 upsert(s), 2 delete(s) (4 collapsed, 1 tombstone(s))"), plan.summary(CdcApplyPlan.DeleteMode.DELETE));

		final CdcApplyPlan.Plan ignored = CdcApplyPlan.plan(batch, CdcApplyPlan.Options.from(inputs("deleteMode", "ignore")));
		check(ignored.getDeletes().isEmpty() && ignored.getIgnoredDeletes() == 2, "ignore mode drops deletes");
		try
		{
			CdcApplyPlan.Options.from(inputs("deleteMode", "flag"));
			throw new AssertionError("flag mode without a field accepted");
		}
		catch (final IllegalArgumentException expected)
		{
			check(expected.getMessage().contains("deleteFlagField"), expected.getMessage());
		}
		try
		{
			CdcApplyPlan.Options.from(inputs("deleteMode", "purge"));
			throw new AssertionError("unknown delete mode accepted");
		}
		catch (final IllegalArgumentException expected)
		{
			// refused
		}
		final CdcApplyPlan.Options flag = CdcApplyPlan.Options.from(inputs("deleteMode", "FLAG", "deleteFlagField", "./deleted", "deleteFlagValue", "Y"));
		check(flag.getDeleteMode() == CdcApplyPlan.DeleteMode.FLAG && "Y".equals(flag.getFlagValue()), "flag options");

		// key identity ignores field order and a schema wrapper
		final ObjectNode wrapped = Json.object();
		wrapped.putObject("schema").put("type", "struct");
		wrapped.putObject("payload").put("b", 2).put("a", 1);
		final ObjectNode afterA = Json.object().put("op", "c");
		afterA.putObject("after").put("a", 1).put("b", 2).put("v", "first");
		final ObjectNode afterB = Json.object().put("op", "u");
		afterB.putObject("after").put("a", 1).put("b", 2).put("v", "second");
		final CdcBatch composite = DebeziumKafkaCdcAdapter.normalize(Arrays.asList(
			new DebeziumKafkaCdcAdapter.Record(TOPIC, 1, 0, Json.object().put("a", 1).put("b", 2), afterA),
			new DebeziumKafkaCdcAdapter.Record(TOPIC, 1, 1, wrapped, afterB)), "", 10, 100000);
		final CdcApplyPlan.Plan compositePlan = CdcApplyPlan.plan(composite, null);
		check(compositePlan.getUpserts().size() == 1 && "second".equals(compositePlan.getUpserts().get(0).path("v").asText()), "composite key collapsed");

		// ---- end to end: crash mid-apply, replay, then a second batch, in both delete modes
		for (final CdcApplyPlan.Options options : Arrays.asList(CdcApplyPlan.Options.from(inputs()), flag))
		{
			final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
			final CdcCheckpointCoordinator coordinator = new CdcCheckpointCoordinator(store);
			final Table table = new Table();
			table.rows.put(Integer.valueOf(4), Json.object().put("id", 4).put("name", "Umbrella").put("deleted", false));
			final List<DebeziumKafkaCdcAdapter.Record> topic = new ArrayList<DebeziumKafkaCdcAdapter.Record>(records);
			final KafkaCdcExecution.Source source = new KafkaCdcExecution.Source()
			{
				@Override
				public List<DebeziumKafkaCdcAdapter.Record> poll(final String pCheckpoint)
				{
					return Collections.unmodifiableList(new ArrayList<DebeziumKafkaCdcAdapter.Record>(topic));
				}
			};
			final int[] crashAfter = { 1 };
			final KafkaCdcExecution.Applier applier = new KafkaCdcExecution.Applier()
			{
				@Override
				public void apply(final CdcBatch pBatch, final boolean pDryRun)
				{
					table.apply(CdcApplyPlan.plan(pBatch, options), options, crashAfter[0]);
				}
			};
			try
			{
				KafkaCdcExecution.pollApplyConfirm(11, "", 100, 1000000, false, source, applier, coordinator, new Date());
				throw new AssertionError("crash not raised");
			}
			catch (final IllegalStateException expected)
			{
				check(store.load(11) == null, "a crashed batch keeps the old checkpoint");
			}
			crashAfter[0] = -1;
			final SynchronizationCheckpoint first = KafkaCdcExecution.pollApplyConfirm(11, "", 100, 1000000, false, source, applier, coordinator, new Date());
			check(first != null && first.getValue().contains(":0\":8"), "checkpoint after the replayed batch: " + first.getValue());
			final String afterFirst = Json.write(Json.mapper().valueToTree(table.rows));
			// the same batch delivered again (at-least-once): suppressed by the checkpoint; applied again by force: no change
			check(KafkaCdcExecution.pollApplyConfirm(11, first.getValue(), 100, 1000000, false, source, applier, coordinator, new Date()) == null, "replayed positions suppressed");
			table.apply(CdcApplyPlan.plan(batch, options), options, -1);
			check(afterFirst.equals(Json.write(Json.mapper().valueToTree(table.rows))), "applying the same batch twice changes nothing");
			if (options.getDeleteMode() == CdcApplyPlan.DeleteMode.DELETE)
			{
				check(table.rows.keySet().equals(new java.util.HashSet<Integer>(Arrays.asList(1, 3))), "delete mode: 1 and 3 left: " + table.rows.keySet());
			}
			else
			{
				check(table.rows.get(Integer.valueOf(4)).path("deleted").asBoolean() && !table.rows.get(Integer.valueOf(3)).path("deleted").asBoolean(), "flag mode keeps 4 flagged, 3 live");
			}
			topic.add(event(9, "u", 3, "Initech 3"));
			final SynchronizationCheckpoint second = KafkaCdcExecution.pollApplyConfirm(11, first.getValue(), 100, 1000000, false, source, applier, coordinator, new Date());
			check(second.getValue().contains(":0\":9") && "Initech 3".equals(table.rows.get(Integer.valueOf(3)).path("name").asText()), "resumes after the checkpoint");
			check(second.getVersion() == first.getVersion() + 1, "optimistic version advanced once per batch");
		}
		JsonNode none = Json.object();
		check(CdcApplyPlan.plan(null, null).getEvents() == 0 && none.size() == 0, "no batch, no action");
		System.out.println("CdcApplyPlanTest PASS: upsert/delete/flag/ignore, per-key collapse, tombstones, composite keys, crash-and-replay idempotency, resume after checkpoint");
	}
}
