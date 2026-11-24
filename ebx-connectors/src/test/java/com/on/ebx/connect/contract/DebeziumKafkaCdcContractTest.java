package com.on.ebx.connect.contract;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.CdcBatch;
import com.on.ebx.connect.core.CdcCheckpointCoordinator;
import com.on.ebx.connect.core.CdcEvent;
import com.on.ebx.connect.core.DebeziumKafkaCdcAdapter;
import com.on.ebx.connect.core.InMemorySynchronizationCheckpointStore;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.SynchronizationCheckpoint;
import com.on.ebx.connect.connectors.KafkaCdcExecution;

/** Offline contract for Debezium normalization, replay safety and checkpoint-after-apply. */
public final class DebeziumKafkaCdcContractTest
{
	private DebeziumKafkaCdcContractTest() { }
	public static void main(final String[] pArgs) throws Exception
	{
		final JsonNode key = Json.parse("{\"id\":7}");
		final DebeziumKafkaCdcAdapter.Record snapshot = record(0, "{\"schema\":{\"name\":\"v1\"},\"payload\":{\"op\":\"r\",\"before\":null,\"after\":{\"id\":7},\"source\":{\"snapshot\":\"last\"}}}", key);
		final DebeziumKafkaCdcAdapter.Record update = record(1, "{\"schema\":{\"name\":\"v2\"},\"payload\":{\"op\":\"u\",\"before\":{\"id\":7,\"n\":\"a\"},\"after\":{\"id\":7,\"n\":\"b\"},\"source\":{\"snapshot\":false}}}", key);
		final DebeziumKafkaCdcAdapter.Record delete = record(2, "{\"payload\":{\"op\":\"d\",\"before\":{\"id\":7},\"after\":null,\"source\":{}}}", key);
		final DebeziumKafkaCdcAdapter.Record tombstone = new DebeziumKafkaCdcAdapter.Record("db.public.customer", 0, 3L, key, null);
		final CdcBatch batch = DebeziumKafkaCdcAdapter.normalize(Arrays.asList(snapshot, update, delete, tombstone), "", 10, 100000);
		check(batch.getEvents().size() == 4, "events lost");
		check(batch.getEvents().get(0).getOperation() == CdcEvent.Operation.SNAPSHOT && batch.getEvents().get(0).getSnapshotBoundary() == CdcEvent.SnapshotBoundary.LAST, "snapshot boundary lost");
		check(batch.getEvents().get(2).getOperation() == CdcEvent.Operation.DELETE && batch.getEvents().get(2).getAfter() == null, "delete semantics lost");
		check(batch.getEvents().get(3).getOperation() == CdcEvent.Operation.TOMBSTONE, "tombstone lost");
		check(!batch.getEvents().get(0).getSchemaFingerprint().equals(batch.getEvents().get(1).getSchemaFingerprint()), "schema evolution not observable");
		check("db.public.customer:0:1".equals(batch.getEvents().get(1).getId()), "idempotency id unstable");
		final DebeziumKafkaCdcAdapter.Record sameSchema = record(4, "{\"schema\":{\"fields\":[{\"type\":\"int32\",\"field\":\"id\"}],\"name\":\"v1\"},\"payload\":{\"op\":\"c\",\"after\":{\"id\":8}}}", key);
		final DebeziumKafkaCdcAdapter.Record reorderedSchema = record(5, "{\"schema\":{\"name\":\"v1\",\"fields\":[{\"field\":\"id\",\"type\":\"int32\"}]},\"payload\":{\"op\":\"c\",\"after\":{\"id\":9}}}", key);
		final CdcBatch canonical = DebeziumKafkaCdcAdapter.normalize(Arrays.asList(sameSchema, reorderedSchema), "", 10, 100000);
		check(canonical.getEvents().get(0).getSchemaFingerprint().equals(canonical.getEvents().get(1).getSchemaFingerprint()), "schema fingerprint depends on object field order");

		final com.fasterxml.jackson.databind.node.ObjectNode mutableKey = Json.object().put("id", 99);
		final com.fasterxml.jackson.databind.node.ObjectNode mutableAfter = Json.object().put("id", 99);
		final com.fasterxml.jackson.databind.node.ObjectNode mutablePayload = Json.object().put("op", "c");
		mutablePayload.set("after", mutableAfter);
		final DebeziumKafkaCdcAdapter.Record immutableInput = new DebeziumKafkaCdcAdapter.Record("db.public.customer", 0, 9L, mutableKey, mutablePayload);
		mutableKey.put("id", 100); mutableAfter.put("id", 100);
		final CdcEvent immutableEvent = DebeziumKafkaCdcAdapter.normalize(Arrays.asList(immutableInput), "", 10, 100000).getEvents().get(0);
		check(immutableEvent.getKey().get("id").asInt() == 99 && immutableEvent.getAfter().get("id").asInt() == 99, "mutable source payload leaked into event");
		((com.fasterxml.jackson.databind.node.ObjectNode) immutableEvent.getAfter()).put("id", 101);
		check(immutableEvent.getAfter().get("id").asInt() == 99, "event getter exposed mutable payload");

		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
		final CdcCheckpointCoordinator coordinator = new CdcCheckpointCoordinator(store);
		check(store.load(42) == null, "normalization committed prematurely");
		check(coordinator.confirmApplied(42, batch, false, false, new Date(1L)) == null && store.load(42) == null, "failed apply advanced checkpoint");
		final SynchronizationCheckpoint saved = coordinator.confirmApplied(42, batch, true, false, new Date(2L));
		check(saved != null && saved.getVersion() == 1L, "successful apply did not commit");
		final CdcBatch replay = DebeziumKafkaCdcAdapter.normalize(Arrays.asList(snapshot, update, delete, tombstone), saved.getValue(), 10, 100000);
		check(replay.getEvents().isEmpty(), "durable positions did not suppress replay");

		final CdcBatch bounded = DebeziumKafkaCdcAdapter.normalize(Arrays.asList(snapshot, update), "", 1, 100000);
		check(bounded.getEvents().size() == 1 && bounded.getCandidateCheckpoint().contains(":0\":0"), "event bound advanced beyond delivered event");
		final CdcBatch byteBounded = DebeziumKafkaCdcAdapter.normalize(Arrays.asList(snapshot), "{}", 10, 1);
		check(byteBounded.getEvents().isEmpty() && "{}".equals(byteBounded.getCandidateCheckpoint()), "byte bound advanced checkpoint");
		expectFailure(new Runnable() { @Override public void run() { DebeziumKafkaCdcAdapter.normalize(Arrays.asList(snapshot, snapshot), "", 10, 100000); } }, "duplicate accepted");
		expectFailure(new Runnable() { @Override public void run() { DebeziumKafkaCdcAdapter.normalize(Arrays.asList(recordUnchecked(5, "{\"payload\":{\"op\":\"x\"}}", key)), "", 10, 100000); } }, "unknown op accepted");
		expectFailure(new Runnable() { @Override public void run() { DebeziumKafkaCdcAdapter.normalize(Arrays.asList(snapshot), "{\"bad\":1}", 10, 100000); } }, "malformed checkpoint partition accepted");
		expectFailure(new Runnable() { @Override public void run() { DebeziumKafkaCdcAdapter.normalize(Arrays.asList(snapshot), "{\"db.public.customer:0\":1.5}", 10, 100000); } }, "fractional checkpoint accepted");
		expectFailure(new Runnable() { @Override public void run() { DebeziumKafkaCdcAdapter.normalize(Arrays.asList(new DebeziumKafkaCdcAdapter.Record(" db.public.customer", 0, 8L, key, null)), "", 10, 100000); } }, "unstable topic identity accepted");

		pipelineCheckpointBoundary(snapshot);
		System.out.println("PASS: Debezium/Kafka CDC normalization and durable checkpoint boundary");
	}

	private static void pipelineCheckpointBoundary(final DebeziumKafkaCdcAdapter.Record pRecord) throws Exception
	{
		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
		final CdcCheckpointCoordinator coordinator = new CdcCheckpointCoordinator(store);
		final KafkaCdcExecution.Source source = new KafkaCdcExecution.Source()
		{
			@Override public List<DebeziumKafkaCdcAdapter.Record> poll(final String pCheckpoint) { return Arrays.asList(pRecord); }
		};
		boolean failed = false;
		try
		{
			KafkaCdcExecution.pollApplyConfirm(73, "", 10, 100000, false, source, new KafkaCdcExecution.Applier()
			{
				@Override public void apply(final CdcBatch pBatch, final boolean pDryRun) throws Exception { throw new Exception("EBX apply rejected"); }
			}, coordinator, new Date(10L));
		}
		catch (final Exception expected) { failed = true; }
		check(failed && store.load(73) == null, "failed EBX apply advanced CDC checkpoint");
		KafkaCdcExecution.pollApplyConfirm(73, "", 10, 100000, true, source, new KafkaCdcExecution.Applier()
		{
			@Override public void apply(final CdcBatch pBatch, final boolean pDryRun) { check(pDryRun, "dry-run flag not propagated"); }
		}, coordinator, new Date(11L));
		check(store.load(73) == null, "dry-run advanced CDC checkpoint");
		KafkaCdcExecution.pollApplyConfirm(73, "", 10, 100000, false, source, new KafkaCdcExecution.Applier()
		{
			@Override public void apply(final CdcBatch pBatch, final boolean pDryRun) { check(!pDryRun, "live apply marked dry-run"); }
		}, coordinator, new Date(12L));
		check(store.load(73) != null && store.load(73).getValue().contains(":0\":0"), "successful EBX apply did not advance CDC checkpoint");
	}

	private static DebeziumKafkaCdcAdapter.Record record(final long pOffset, final String pValue, final JsonNode pKey) throws Exception
	{ return new DebeziumKafkaCdcAdapter.Record("db.public.customer", 0, pOffset, pKey, Json.parse(pValue)); }
	private static DebeziumKafkaCdcAdapter.Record recordUnchecked(final long pOffset, final String pValue, final JsonNode pKey)
	{ try { return record(pOffset, pValue, pKey); } catch (final Exception ex) { throw new IllegalStateException(ex); } }
	private static void expectFailure(final Runnable pRun, final String pMessage) { try { pRun.run(); throw new AssertionError(pMessage); } catch (final IllegalArgumentException expected) { } }
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
