package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The health metrics count records, not log lines (audit C-41): a batched feed run writes one line per batch plus an own line for each
 * failing record, and the metrics read the batch line's tally once instead of counting lines.
 */
public final class BatchMetricsTest
{
	public static void main(final String[] pArgs)
	{
		final Date at = new Date(1000L);

		// a batch of 1000 records with 3 failures: 1000 records, the 3 failing records' own lines are not counted again
		ConnectorOperationalMetrics metrics = new ConnectorOperationalMetrics(new ConnectorOperationalMetrics.MemoryPersistence());
		ConnectorOperationsRuntime.MetricsAndAlertsObserver observer = new ConnectorOperationsRuntime.MetricsAndAlertsObserver(metrics, null);
		final ImportBatchSummary partial = new ImportBatchSummary();
		for (int i = 0; i < 900; i++) partial.created();
		for (int i = 0; i < 50; i++) partial.updated();
		for (int i = 0; i < 40; i++) partial.noChange();
		for (int i = 0; i < 7; i++) partial.rejected();
		for (int i = 0; i < 3; i++) partial.error();
		partial.conflicts(4);
		for (int i = 0; i < 3; i++) observer.onResult("ops", itemLine("K" + i, SyncResult.Status.ERROR, true), at);
		observer.onResult("ops", batchLine(partial, 2000L), at);
		ConnectorOperationalMetrics.Snapshot snapshot = metrics.snapshot("Kafka");
		check(snapshot.get(ConnectorOperationalMetrics.Counter.REQUEST) == 1000L, "requests are the records of the batch: " + snapshot.get(ConnectorOperationalMetrics.Counter.REQUEST));
		check(snapshot.get(ConnectorOperationalMetrics.Counter.READ) == 1000L, "inbound records: " + snapshot.get(ConnectorOperationalMetrics.Counter.READ));
		check(snapshot.get(ConnectorOperationalMetrics.Counter.SKIP) == 47L, "records without change: " + snapshot.get(ConnectorOperationalMetrics.Counter.SKIP));
		check(snapshot.get(ConnectorOperationalMetrics.Counter.CONFLICT) == 4L, "conflicts come from the batch tally: " + snapshot.get(ConnectorOperationalMetrics.Counter.CONFLICT));
		check(snapshot.getConsecutiveFailures() == 0 && snapshot.getHealth() == ConnectorOperationalMetrics.Health.HEALTHY,
			"a batch with some failures is not a failed batch: " + snapshot.getConsecutiveFailures());
		check(snapshot.percentile(50) == 2L, "the latency sample is the time per record: " + snapshot.percentile(50));

		// a batch in which every write failed is one failure, whatever the number of its records
		metrics = new ConnectorOperationalMetrics(new ConnectorOperationalMetrics.MemoryPersistence());
		observer = new ConnectorOperationsRuntime.MetricsAndAlertsObserver(metrics, null);
		final ImportBatchSummary failed = new ImportBatchSummary();
		for (int i = 0; i < 500; i++) failed.error();
		for (int i = 0; i < 500; i++) observer.onResult("ops", itemLine("F" + i, SyncResult.Status.ERROR, true), at);
		observer.onResult("ops", batchLine(failed, 500L), at);
		snapshot = metrics.snapshot("Kafka");
		check(snapshot.get(ConnectorOperationalMetrics.Counter.REQUEST) == 500L, "the failed records are counted once: " + snapshot.get(ConnectorOperationalMetrics.Counter.REQUEST));
		check(snapshot.getConsecutiveFailures() == 1 && snapshot.getHealth() == ConnectorOperationalMetrics.Health.DEGRADED,
			"one failed batch, not 501 failures: " + snapshot.getConsecutiveFailures());
		check(snapshot.getLastFailure() != null && snapshot.getLastFailure().contains("batch of 500"), "the failure says which batch: " + snapshot.getLastFailure());

		// the same through the grouped path
		metrics = new ConnectorOperationalMetrics(new ConnectorOperationalMetrics.MemoryPersistence());
		observer = new ConnectorOperationsRuntime.MetricsAndAlertsObserver(metrics, null);
		final List<ConnectorOperationsRuntime.Observed> group = new ArrayList<ConnectorOperationsRuntime.Observed>();
		group.add(observed(itemLine("G1", SyncResult.Status.ERROR, true), at));
		group.add(observed(batchLine(partial, 10L), at));
		observer.onResults(group);
		check(metrics.snapshot("Kafka").get(ConnectorOperationalMetrics.Counter.REQUEST) == 1000L, "the grouped path counts records too");

		// without a batch line (logEveryRecord) every line is one record and nothing is skipped
		metrics = new ConnectorOperationalMetrics(new ConnectorOperationalMetrics.MemoryPersistence());
		observer = new ConnectorOperationsRuntime.MetricsAndAlertsObserver(metrics, null);
		observer.onResult("ops", itemLine("L1", SyncResult.Status.OK, false), at);
		observer.onResult("ops", itemLine("L2", SyncResult.Status.ERROR, false), at);
		snapshot = metrics.snapshot("Kafka");
		check(snapshot.get(ConnectorOperationalMetrics.Counter.REQUEST) == 2L && snapshot.getConsecutiveFailures() == 1, "own lines of a per-record log count as before");

		// the repeated-failure alert follows the same rule: one partial batch is no streak, two failed batches are
		final List<RepeatedFailureAlertHook.Alert> alerts = new ArrayList<RepeatedFailureAlertHook.Alert>();
		final RepeatedFailureAlertHook hook = new RepeatedFailureAlertHook(2, new RepeatedFailureAlertHook.MemoryFailureHistory(), alerts::add, (actor, connector) -> true);
		observer = new ConnectorOperationsRuntime.MetricsAndAlertsObserver(new ConnectorOperationalMetrics(new ConnectorOperationalMetrics.MemoryPersistence()), hook);
		observer.onResult("ops", itemLine("A1", SyncResult.Status.ERROR, true), at);
		observer.onResult("ops", batchLine(partial, 10L), at);
		check(alerts.isEmpty(), "a batch with a failed record raises no alert");
		observer.onResult("ops", batchLine(failed, 10L), at);
		check(alerts.isEmpty(), "one failed batch is a streak of one");
		observer.onResult("ops", batchLine(failed, 10L), at);
		check(alerts.size() == 1, "two failed batches in a row raise the alert");
		System.out.println("PASS: health metrics and failure alerts count the records of a batch, once, and a batch fails only when every write failed");
	}

	private static SyncResult itemLine(final String pKey, final SyncResult.Status pStatus, final boolean pCountedByBatch)
	{
		final SyncResult result = new SyncResult("Kafka", "consume", "IMPORT", "Main", "/root/Company", pKey, pKey);
		result.setStatus(pStatus);
		result.setMessage(pStatus.name().toLowerCase());
		result.setMappingId("7");
		result.setCountedByBatch(pCountedByBatch);
		return result;
	}

	private static SyncResult batchLine(final ImportBatchSummary pSummary, final long pDurationMs)
	{
		final SyncResult result = new SyncResult("Kafka", "consume", "IMPORT", "Main", "/root/Company", "K0..K9", "batch 1");
		result.setStatus(pSummary.status());
		result.setMessage(pSummary.message());
		result.setDurationMs(pDurationMs);
		result.setMappingId("7");
		result.setBatch(pSummary);
		return result;
	}

	private static ConnectorOperationsRuntime.Observed observed(final SyncResult pResult, final Date pAt)
	{
		return ConnectorOperationsRuntime.capture("ops", java.util.Collections.singletonList(new ConnectorRepository.LogEntry(pResult, "test", pAt, new long[] { 0L, 0L }))).get(0);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
