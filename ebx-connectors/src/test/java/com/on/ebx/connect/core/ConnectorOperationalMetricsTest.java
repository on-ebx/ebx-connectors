package com.on.ebx.connect.core;

import java.util.Date;

/** Deterministic operational health, counter, latency and durability contract. */
public final class ConnectorOperationalMetricsTest
{
	public static void main(final String[] pArgs)
	{
		recordsHealthCountersAndDurableState();
		calculatesPercentilesCheckpointAgeAndSchemaDrift();
		boundsSamplesAndProtectsCounters();
		System.out.println("PASS: connector operational health and metrics");
	}

	private static void recordsHealthCountersAndDurableState()
	{
		final ConnectorOperationalMetrics.MemoryPersistence persistence = new ConnectorOperationalMetrics.MemoryPersistence();
		ConnectorOperationalMetrics metrics = new ConnectorOperationalMetrics(persistence);
		check(metrics.snapshot("postgresql").getHealth() == ConnectorOperationalMetrics.Health.UNKNOWN, "new connector health");
		metrics.success("postgresql", new ConnectorOperationalMetrics.Observation()
			.add(ConnectorOperationalMetrics.Counter.REQUEST, 2).add(ConnectorOperationalMetrics.Counter.READ, 10)
			.add(ConnectorOperationalMetrics.Counter.WRITE, 8).add(ConnectorOperationalMetrics.Counter.SKIP, 1)
			.add(ConnectorOperationalMetrics.Counter.CONFLICT, 1).durationMillis(20), new Date(1000L));
		metrics.failure("postgresql", new ConnectorOperationalMetrics.Observation()
			.add(ConnectorOperationalMetrics.Counter.REQUEST, 1).add(ConnectorOperationalMetrics.Counter.RETRY, 2)
			.add(ConnectorOperationalMetrics.Counter.THROTTLE, 1).durationMillis(40), new Date(2000L), "HTTP 429");
		ConnectorOperationalMetrics.Snapshot value = metrics.snapshot("postgresql");
		check(value.getHealth() == ConnectorOperationalMetrics.Health.DEGRADED, "single failure health");
		check(value.get(ConnectorOperationalMetrics.Counter.REQUEST) == 3L, "request count");
		check(value.get(ConnectorOperationalMetrics.Counter.READ) == 10L, "read count");
		check(value.get(ConnectorOperationalMetrics.Counter.RETRY) == 2L, "retry count");
		check("HTTP 429".equals(value.getLastFailure()), "last failure detail");
		metrics.failure("postgresql", null, new Date(3000L), "timeout");
		metrics.failure("postgresql", null, new Date(4000L), "timeout");
		check(metrics.snapshot("postgresql").getHealth() == ConnectorOperationalMetrics.Health.FAILED, "repeated failure health");
		metrics = new ConnectorOperationalMetrics(persistence);
		value = metrics.snapshot("postgresql");
		check(value.getHealth() == ConnectorOperationalMetrics.Health.FAILED && value.getLastFailureAt().getTime() == 4000L,
			"state did not survive service recreation");
		metrics.success("postgresql", null, new Date(5000L));
		check(metrics.snapshot("postgresql").getHealth() == ConnectorOperationalMetrics.Health.HEALTHY, "success did not restore health");
	}

	private static void calculatesPercentilesCheckpointAgeAndSchemaDrift()
	{
		final ConnectorOperationalMetrics metrics = new ConnectorOperationalMetrics(new ConnectorOperationalMetrics.MemoryPersistence());
		for (int i = 1; i <= 100; i++) metrics.success("salesforce",
			new ConnectorOperationalMetrics.Observation().durationMillis(i), new Date(i));
		ConnectorOperationalMetrics.Snapshot value = metrics.checkpoint("salesforce", new Date(1000L));
		check(value.percentile(50) == 50L, "p50");
		check(value.percentile(95) == 95L, "p95");
		check(value.percentile(99) == 99L, "p99");
		check(value.checkpointAgeMillis(new Date(4750L)) == 3750L, "checkpoint age");
		value = metrics.schemaDrift("salesforce", new Date(5000L));
		value = metrics.schemaDrift("salesforce", new Date(6000L));
		check(value.getSchemaDriftCount() == 2 && value.getSchemaDriftAt().getTime() == 6000L, "schema drift state");
	}

	private static void boundsSamplesAndProtectsCounters()
	{
		final ConnectorOperationalMetrics metrics = new ConnectorOperationalMetrics(new ConnectorOperationalMetrics.MemoryPersistence());
		for (int i = 0; i < ConnectorOperationalMetrics.MAX_DURATION_SAMPLES + 10; i++)
			metrics.success("kafka", new ConnectorOperationalMetrics.Observation().durationMillis(i), new Date(i));
		check(metrics.snapshot("kafka").getDurations().size() == ConnectorOperationalMetrics.MAX_DURATION_SAMPLES, "unbounded latency samples");
		boolean rejected = false;
		try { new ConnectorOperationalMetrics.Observation().add(ConnectorOperationalMetrics.Counter.WRITE, -1); }
		catch (final IllegalArgumentException expected) { rejected = true; }
		check(rejected, "negative counter accepted");
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
