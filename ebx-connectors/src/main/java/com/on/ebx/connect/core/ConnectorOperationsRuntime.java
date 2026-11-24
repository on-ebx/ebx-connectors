package com.on.ebx.connect.core;

import java.util.Date;
import java.util.Map;

/**
 * Process-wide, schema-independent observation seam for connector executions. The default observer
 * is a no-op; an EBX startup hook or embedding application may install durable metrics and alert
 * adapters without changing synchronization transaction semantics.
 */
public final class ConnectorOperationsRuntime
{
	/** One result to observe, with the HTTP retries and throttles the thread had counted up to it (see {@link HttpExchangeStats}). */
	public static final class Observed
	{
		final String actor;
		final SyncResult result;
		final Date occurredAt;
		final long[] http;

		Observed(final String pActor, final SyncResult pResult, final Date pOccurredAt, final long[] pHttp)
		{
			actor = pActor;
			result = pResult;
			occurredAt = pOccurredAt == null ? new Date() : new Date(pOccurredAt.getTime());
			http = pHttp;
		}
	}

	public interface Observer
	{
		void onResult(String pActor, SyncResult pResult, Date pOccurredAt);
		/**
		 * Results already counted by the caller (audit C-13): a record-mode run hands them over in groups. The default observes
		 * them one by one, which is what an observer that knows nothing of groups has always been given.
		 */
		default void onResults(java.util.List<Observed> pResults)
		{
			for (final Observed observed : pResults) onResult(observed.actor, observed.result, observed.occurredAt);
		}
		default void onCheckpoint(String pConnectorId, Date pOccurredAt) { }
		default void onSchemaDrift(String pConnectorId, Date pOccurredAt) { }
	}
	private static final Observer NOOP = new Observer() { @Override public void onResult(final String pActor, final SyncResult pResult, final Date pAt) { } };
	private static volatile Observer observer = NOOP;

	private ConnectorOperationsRuntime() { }

	/** Installs an observer until the returned registration is closed. Intended for module startup and tests. */
	public static synchronized Registration install(final Observer pObserver)
	{
		if (pObserver == null) throw new IllegalArgumentException("Observer is required");
		final Observer previous = observer;
		observer = pObserver;
		return new Registration(previous, pObserver);
	}

	/** The results of sync log lines, with the retries counted on this thread since the last observation going to the first one. */
	static java.util.List<Observed> capture(final String pActor, final java.util.List<ConnectorRepository.LogEntry> pEntries)
	{
		final java.util.List<Observed> out = new java.util.ArrayList<Observed>(pEntries.size());
		for (final ConnectorRepository.LogEntry entry : pEntries)
		{
			if (entry.result != null) out.add(new Observed(pActor, entry.result, entry.at, entry.http != null ? entry.http : HttpExchangeStats.drain()));
		}
		return out;
	}

	/** Observation failures never fail or roll back connector work. */
	static void observeAll(final java.util.List<Observed> pResults)
	{
		if (pResults == null || pResults.isEmpty()) return;
		try { observer.onResults(pResults); }
		catch (final RuntimeException ex) { ConnectorService.log().warn(Redaction.redact("[connectors] operational observation failed: " + ex.getMessage())); }
	}

	/** Observation failures never fail or roll back connector work. */
	public static void observe(final String pActor, final SyncResult pResult, final Date pOccurredAt)
	{
		if (pResult == null) return;
		try { observer.onResult(pActor, pResult, pOccurredAt == null ? new Date() : new Date(pOccurredAt.getTime())); }
		catch (final RuntimeException ex) { ConnectorService.log().warn(Redaction.redact("[connectors] operational observation failed: " + ex.getMessage())); }
	}
	public static void checkpoint(final String pConnectorId, final Date pOccurredAt) { signal(pConnectorId, pOccurredAt, true); }
	public static void schemaDrift(final String pConnectorId, final Date pOccurredAt) { signal(pConnectorId, pOccurredAt, false); }
	private static void signal(final String pConnectorId, final Date pOccurredAt, final boolean pCheckpoint)
	{
		if (pConnectorId == null || pConnectorId.trim().isEmpty()) return;
		try
		{
			final Date at = pOccurredAt == null ? new Date() : new Date(pOccurredAt.getTime());
			if (pCheckpoint) observer.onCheckpoint(pConnectorId, at);
			else observer.onSchemaDrift(pConnectorId, at);
		}
		catch (final RuntimeException ex) { ConnectorService.log().warn(Redaction.redact("[connectors] operational signal failed: " + ex.getMessage())); }
	}

	public static final class Registration implements AutoCloseable
	{
		private final Observer previous, installed;
		private boolean closed;
		private Registration(final Observer pPrevious, final Observer pInstalled)
		{
			previous = pPrevious;
			installed = pInstalled;
		}
		@Override public synchronized void close()
		{
			if (!closed)
			{
				synchronized (ConnectorOperationsRuntime.class)
				{
					if (observer == installed) observer = previous;
				}
				closed = true;
			}
		}
	}

	/** Standard observer connecting runtime outcomes to metrics and repeated-failure alerting. */
	public static final class MetricsAndAlertsObserver implements Observer
	{
		private final ConnectorOperationalMetrics metrics;
		private final RepeatedFailureAlertHook alerts;
		public MetricsAndAlertsObserver(final ConnectorOperationalMetrics pMetrics, final RepeatedFailureAlertHook pAlerts)
		{
			if (pMetrics == null) throw new IllegalArgumentException("Metrics are required");
			metrics = pMetrics;
			alerts = pAlerts;
		}
		@Override public void onResult(final String pActor, final SyncResult pResult, final Date pOccurredAt)
		{
			// a record the batch line of its run already counts: before the HTTP counters are drained, so that the batch line takes them
			if (pResult.isCountedByBatch()) return;
			final long[] http = HttpExchangeStats.drain();
			final ConnectorOperationalMetrics.Observation observation = observation(pResult, http);
			if (pResult.countsAsFailure()) metrics.failure(pResult.getConnectorId(), observation, pOccurredAt, failureContext(pResult));
			else metrics.success(pResult.getConnectorId(), observation, pOccurredAt);
			if (alerts != null) alerts.record(pActor, pResult, pOccurredAt);
		}
		/**
		 * A group of results: the metrics of each connector are read and written once for all its results, in order, instead of
		 * once per result under the global lock (audit C-13). The alert hook still sees each result.
		 */
		@Override public void onResults(final java.util.List<Observed> pResults)
		{
			final Map<String, java.util.List<ConnectorOperationalMetrics.Entry>> byConnector = new java.util.LinkedHashMap<String, java.util.List<ConnectorOperationalMetrics.Entry>>();
			final java.util.List<Observed> counted = new java.util.ArrayList<Observed>(pResults.size());
			for (final Observed observed : pResults)
			{
				if (!observed.result.isCountedByBatch()) counted.add(observed);
			}
			for (final Observed observed : counted)
			{
				final SyncResult result = observed.result;
				final java.util.List<ConnectorOperationalMetrics.Entry> entries = byConnector.get(result.getConnectorId());
				final ConnectorOperationalMetrics.Entry entry = new ConnectorOperationalMetrics.Entry(observation(result, observed.http), observed.occurredAt,
					result.countsAsFailure() ? failureContext(result) : null, !result.countsAsFailure());
				if (entries == null)
				{
					final java.util.List<ConnectorOperationalMetrics.Entry> created = new java.util.ArrayList<ConnectorOperationalMetrics.Entry>();
					created.add(entry);
					byConnector.put(result.getConnectorId(), created);
				}
				else
				{
					entries.add(entry);
				}
			}
			for (final Map.Entry<String, java.util.List<ConnectorOperationalMetrics.Entry>> group : byConnector.entrySet()) metrics.recordAll(group.getKey(), group.getValue());
			if (alerts != null) for (final Observed observed : counted) alerts.record(observed.actor, observed.result, observed.occurredAt);
		}
		private static ConnectorOperationalMetrics.Observation observation(final SyncResult pResult, final long[] pHttp)
		{
			// The counters are records (audit C-41): a batch line stands for every record of its batch, which the run counted; any other
			// line is one call or one record. The latency sample of a batch is the time per record.
			final ImportBatchSummary batch = pResult.getBatch();
			final long records = batch == null ? 1L : Math.max(1, batch.total());
			final ConnectorOperationalMetrics.Observation observation = new ConnectorOperationalMetrics.Observation()
				.add(ConnectorOperationalMetrics.Counter.REQUEST, records).durationMillis(Math.max(0L, pResult.getDurationMs()) / records);
			if ("INBOUND".equalsIgnoreCase(pResult.getDirection()) || "IMPORT".equalsIgnoreCase(pResult.getDirection()))
				observation.add(ConnectorOperationalMetrics.Counter.READ, records);
			if ("OUTBOUND".equalsIgnoreCase(pResult.getDirection())) observation.add(ConnectorOperationalMetrics.Counter.WRITE, records);
			if (batch != null)
			{
				observation.add(ConnectorOperationalMetrics.Counter.SKIP, batch.getNoChange() + batch.getRejected());
				if (batch.getConflicts() > 0) observation.add(ConnectorOperationalMetrics.Counter.CONFLICT, batch.getConflicts());
			}
			else
			{
				if (pResult.getStatus() == SyncResult.Status.SKIPPED || pResult.getStatus() == SyncResult.Status.NO_CHANGE || pResult.getStatus() == SyncResult.Status.NO_DATA)
					observation.add(ConnectorOperationalMetrics.Counter.SKIP, 1L);
				if (!pResult.getConflicts().isEmpty()) observation.add(ConnectorOperationalMetrics.Counter.CONFLICT, pResult.getConflicts().size());
			}
			observation.add(ConnectorOperationalMetrics.Counter.RETRY, pHttp[0]).add(ConnectorOperationalMetrics.Counter.THROTTLE, pHttp[1]);
			return observation;
		}
		/** The metrics row keeps one message for the whole connector: say which operation and record it came from. */
		static String failureContext(final SyncResult pResult)
		{
			final StringBuilder out = new StringBuilder();
			if (pResult.getOperation() != null && !pResult.getOperation().isEmpty()) out.append(pResult.getOperation());
			if (pResult.getTablePath() != null && !pResult.getTablePath().isEmpty()) out.append(out.length() == 0 ? "" : " ").append("on ").append(pResult.getTablePath());
			if (pResult.getRecordKey() != null && !pResult.getRecordKey().isEmpty()) out.append(" [").append(pResult.getRecordKey()).append(']');
			final String message = pResult.getMessage() == null ? "" : pResult.getMessage();
			return out.length() == 0 ? message : out.append(": ").append(message).toString();
		}
		@Override public void onCheckpoint(final String pConnectorId, final Date pOccurredAt) { metrics.checkpoint(pConnectorId, pOccurredAt); }
		@Override public void onSchemaDrift(final String pConnectorId, final Date pOccurredAt) { metrics.schemaDrift(pConnectorId, pOccurredAt); }
	}
}
