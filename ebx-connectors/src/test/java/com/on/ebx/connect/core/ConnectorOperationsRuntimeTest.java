package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class ConnectorOperationsRuntimeTest
{
	public static void main(final String[] pArgs)
	{
		final ConnectorOperationalMetrics metrics = new ConnectorOperationalMetrics(new ConnectorOperationalMetrics.MemoryPersistence());
		final List<RepeatedFailureAlertHook.Alert> alerts = new ArrayList<RepeatedFailureAlertHook.Alert>();
		final RepeatedFailureAlertHook hook = new RepeatedFailureAlertHook(2, new RepeatedFailureAlertHook.MemoryFailureHistory(), alerts::add,
			(pActor, pConnector) -> "ops".equals(pActor));
		final Date at = new Date(1000L);
		try (ConnectorOperationsRuntime.Registration ignored = ConnectorOperationsRuntime.install(
			new ConnectorOperationsRuntime.MetricsAndAlertsObserver(metrics, hook)))
		{
			HttpExchangeStats.retried(503, null);
			HttpExchangeStats.retried(429, "2");
			ConnectorOperationsRuntime.observe("ops", result(SyncResult.Status.ERROR, "OUTBOUND", 20L), at);
			HttpExchangeStats.retried(503, "Wed, 21 Oct 2026 07:28:00 GMT");
			ConnectorOperationsRuntime.observe("ops", result(SyncResult.Status.ERROR, "OUTBOUND", 30L), at);
			ConnectorOperationsRuntime.checkpoint("Kafka", new Date(900L));
			ConnectorOperationsRuntime.schemaDrift("Kafka", at);
		}
		final ConnectorOperationalMetrics.Snapshot snapshot = metrics.snapshot("Kafka");
		check(snapshot.get(ConnectorOperationalMetrics.Counter.REQUEST) == 2L, "request counter not wired");
		check(snapshot.get(ConnectorOperationalMetrics.Counter.WRITE) == 2L, "write counter not wired");
		check(snapshot.get(ConnectorOperationalMetrics.Counter.RETRY) == 3L, "retry counter not wired: " + snapshot.get(ConnectorOperationalMetrics.Counter.RETRY));
		check(snapshot.get(ConnectorOperationalMetrics.Counter.THROTTLE) == 2L, "throttle counter not wired: " + snapshot.get(ConnectorOperationalMetrics.Counter.THROTTLE));
		check(HttpExchangeStats.drain()[0] == 0L, "retries must be drained by the observation");
		check(snapshot.percentile(95) == 30L && snapshot.getConsecutiveFailures() == 2, "duration/failure state not wired");
		check(alerts.size() == 1, "repeated-failure alert not wired");
		check(snapshot.getCheckpointAt().getTime() == 900L && snapshot.getSchemaDriftCount() == 1, "checkpoint/schema drift signals not wired");
		check("publish on /root/Company [1]: failure".equals(snapshot.getLastFailure()), "last failure lacks its context: " + snapshot.getLastFailure());
		final String predicate = ConnectorService.predicateFailure("/root/ProfileSnapshot", "./profileDate='2026-09-12'",
			"An unknown predicate operator has been detected: =.");
		check(predicate.contains("./profileDate='2026-09-12'") && predicate.contains("/root/ProfileSnapshot") && predicate.contains("date-equal("),
			"predicate failure lacks the predicate, the table or the date hint: " + predicate);

		try (ConnectorOperationsRuntime.Registration ignored = ConnectorOperationsRuntime.install((actor, result, when) -> { throw new IllegalStateException("probe"); }))
		{
			ConnectorOperationsRuntime.observe("ops", result(SyncResult.Status.OK, "INBOUND", 1L), at);
		}
		final SyncResult partial = result(SyncResult.Status.OK, "INBOUND", 5L);
		partial.getErrors().add("synthetic conversion error");
		final ConnectorOperationalMetrics partialMetrics = new ConnectorOperationalMetrics(new ConnectorOperationalMetrics.MemoryPersistence());
		final List<RepeatedFailureAlertHook.Alert> partialAlerts = new ArrayList<RepeatedFailureAlertHook.Alert>();
		final RepeatedFailureAlertHook partialHook = new RepeatedFailureAlertHook(2, new RepeatedFailureAlertHook.MemoryFailureHistory(), partialAlerts::add,
			(actor, connector) -> true);
		new ConnectorOperationsRuntime.MetricsAndAlertsObserver(partialMetrics, partialHook).onResult("ops", partial, at);
		check(partialMetrics.snapshot("Kafka").getConsecutiveFailures() == 1, "partial result must not reset failure counters");
		new ConnectorOperationsRuntime.MetricsAndAlertsObserver(partialMetrics, partialHook).onResult("ops", partial, at);
		check(partialAlerts.size() == 1, "partial result must contribute to failure alerts");
		System.out.println("Connector operations runtime wiring verified.");
	}
	private static SyncResult result(final SyncResult.Status pStatus, final String pDirection, final long pDuration)
	{
		final SyncResult result = new SyncResult("Kafka", "publish", pDirection, "Main", "/root/Company", "1", "One");
		result.setStatus(pStatus); result.setMessage("failure"); result.setDurationMs(pDuration); result.setMappingId("company-kafka"); return result;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
