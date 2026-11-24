package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable, connector-level operational telemetry. Persistence adapters own the
 * transaction boundary; this class owns deterministic health and metric rules.
 */
public final class ConnectorOperationalMetrics
{
	public static final int MAX_DURATION_SAMPLES = 256;
	public static final int DEGRADED_FAILURE_THRESHOLD = 1;
	public static final int FAILED_FAILURE_THRESHOLD = 3;

	public enum Health { UNKNOWN, HEALTHY, DEGRADED, FAILED }
	public enum Counter { READ, WRITE, SKIP, CONFLICT, REQUEST, RETRY, THROTTLE }

	public interface Persistence
	{
		Snapshot load(String pConnectorId);
		void save(Snapshot pSnapshot);
	}

	/** Immutable value suitable for serialization in an EBX table. */
	public static final class Snapshot
	{
		private final String connectorId;
		private final Map<Counter, Long> counters;
		private final List<Long> durations;
		private final Date lastSuccessAt, lastFailureAt, checkpointAt, schemaDriftAt, updatedAt;
		private final String lastFailure;
		private final int consecutiveFailures, schemaDriftCount;

		public Snapshot(final String pConnectorId, final Map<Counter, Long> pCounters,
			final List<Long> pDurations, final Date pLastSuccessAt, final Date pLastFailureAt,
			final String pLastFailure, final int pConsecutiveFailures, final Date pCheckpointAt,
			final Date pSchemaDriftAt, final int pSchemaDriftCount, final Date pUpdatedAt)
		{
			if (pConnectorId == null || pConnectorId.trim().isEmpty())
				throw new IllegalArgumentException("Connector identifier is required");
			if (pConsecutiveFailures < 0 || pSchemaDriftCount < 0)
				throw new IllegalArgumentException("Operational counts cannot be negative");
			connectorId = pConnectorId;
			final Map<Counter, Long> copy = emptyCounters();
			if (pCounters != null) for (final Map.Entry<Counter, Long> entry : pCounters.entrySet()) {
				if (entry.getKey() == null || entry.getValue() == null || entry.getValue().longValue() < 0L)
					throw new IllegalArgumentException("Counters cannot be null or negative");
				copy.put(entry.getKey(), entry.getValue());
			}
			counters = Collections.unmodifiableMap(copy);
			final List<Long> samples = new ArrayList<Long>();
			if (pDurations != null) for (final Long duration : pDurations) {
				if (duration == null || duration.longValue() < 0L) throw new IllegalArgumentException("Duration cannot be null or negative");
				samples.add(duration);
			}
			if (samples.size() > MAX_DURATION_SAMPLES)
				samples.subList(0, samples.size() - MAX_DURATION_SAMPLES).clear();
			durations = Collections.unmodifiableList(samples);
			lastSuccessAt = copy(pLastSuccessAt);
			lastFailureAt = copy(pLastFailureAt);
			checkpointAt = copy(pCheckpointAt);
			schemaDriftAt = copy(pSchemaDriftAt);
			updatedAt = copy(pUpdatedAt);
			lastFailure = pLastFailure;
			consecutiveFailures = pConsecutiveFailures;
			schemaDriftCount = pSchemaDriftCount;
		}

		public static Snapshot empty(final String pConnectorId)
		{
			return new Snapshot(pConnectorId, null, null, null, null, null, 0, null, null, 0, null);
		}
		public String getConnectorId() { return connectorId; }
		public long get(final Counter pCounter) { return counters.get(pCounter).longValue(); }
		public Map<Counter, Long> getCounters() { return counters; }
		public List<Long> getDurations() { return durations; }
		public Date getLastSuccessAt() { return copy(lastSuccessAt); }
		public Date getLastFailureAt() { return copy(lastFailureAt); }
		public String getLastFailure() { return lastFailure; }
		public int getConsecutiveFailures() { return consecutiveFailures; }
		public Date getCheckpointAt() { return copy(checkpointAt); }
		public Date getSchemaDriftAt() { return copy(schemaDriftAt); }
		public int getSchemaDriftCount() { return schemaDriftCount; }
		public Date getUpdatedAt() { return copy(updatedAt); }

		public Health getHealth()
		{
			if (lastSuccessAt == null && lastFailureAt == null) return Health.UNKNOWN;
			if (consecutiveFailures >= FAILED_FAILURE_THRESHOLD) return Health.FAILED;
			if (consecutiveFailures >= DEGRADED_FAILURE_THRESHOLD) return Health.DEGRADED;
			return Health.HEALTHY;
		}
		public long percentile(final int pPercentile)
		{
			if (pPercentile < 1 || pPercentile > 100) throw new IllegalArgumentException("Percentile must be between 1 and 100");
			if (durations.isEmpty()) return 0L;
			final List<Long> sorted = new ArrayList<Long>(durations);
			Collections.sort(sorted);
			final int rank = (int) Math.ceil((pPercentile / 100.0d) * sorted.size());
			return sorted.get(Math.max(0, rank - 1)).longValue();
		}
		public long checkpointAgeMillis(final Date pNow)
		{
			if (checkpointAt == null) return -1L;
			final long age = (pNow == null ? new Date() : pNow).getTime() - checkpointAt.getTime();
			return Math.max(0L, age);
		}
	}

	/** One execution observation; all counters are deltas. */
	public static final class Observation
	{
		private final Map<Counter, Long> counters = emptyCounters();
		private long durationMillis = -1L;
		public Observation add(final Counter pCounter, final long pDelta)
		{
			if (pCounter == null || pDelta < 0L) throw new IllegalArgumentException("Counter and non-negative delta are required");
			counters.put(pCounter, Long.valueOf(safeAdd(counters.get(pCounter).longValue(), pDelta)));
			return this;
		}
		public Observation durationMillis(final long pDurationMillis)
		{
			if (pDurationMillis < 0L) throw new IllegalArgumentException("Duration cannot be negative");
			durationMillis = pDurationMillis;
			return this;
		}
	}

	private final Persistence persistence;
	public ConnectorOperationalMetrics(final Persistence pPersistence)
	{
		if (pPersistence == null) throw new IllegalArgumentException("Persistence is required");
		persistence = pPersistence;
	}

	public synchronized Snapshot snapshot(final String pConnectorId)
	{
		final Snapshot value = persistence.load(pConnectorId);
		return value == null ? Snapshot.empty(pConnectorId) : value;
	}
	public synchronized Snapshot success(final String pConnectorId, final Observation pObservation, final Date pWhen)
	{
		return record(pConnectorId, pObservation, pWhen, null, true);
	}
	public synchronized Snapshot failure(final String pConnectorId, final Observation pObservation, final Date pWhen, final String pMessage)
	{
		return record(pConnectorId, pObservation, pWhen, pMessage, false);
	}
	public synchronized Snapshot checkpoint(final String pConnectorId, final Date pWhen)
	{
		final Snapshot current = snapshot(pConnectorId);
		final Date when = requiredDate(pWhen);
		return save(current, current.counters, current.durations, current.lastSuccessAt, current.lastFailureAt,
			current.lastFailure, current.consecutiveFailures, when, current.schemaDriftAt, current.schemaDriftCount, when);
	}
	public synchronized Snapshot schemaDrift(final String pConnectorId, final Date pWhen)
	{
		final Snapshot current = snapshot(pConnectorId);
		final Date when = requiredDate(pWhen);
		return save(current, current.counters, current.durations, current.lastSuccessAt, current.lastFailureAt,
			current.lastFailure, current.consecutiveFailures, current.checkpointAt, when,
			increment(current.schemaDriftCount), when);
	}

	/** One observation waiting to be folded into a connector's metrics: what {@link #success}/{@link #failure} take. */
	public static final class Entry
	{
		private final Observation observation;
		private final Date when;
		private final String failure;
		private final boolean success;

		public Entry(final Observation pObservation, final Date pWhen, final String pFailure, final boolean pSuccess)
		{
			observation = pObservation;
			when = pWhen;
			failure = pFailure;
			success = pSuccess;
		}
	}

	/**
	 * Several observations of one connector in order, as if {@link #success}/{@link #failure} had been called for each, with ONE read
	 * and ONE write of the connector's row instead of one of each per observation (audit C-13: a record-mode run observes every
	 * record). The final row is the same as the one the calls one by one leave.
	 */
	public synchronized Snapshot recordAll(final String pConnectorId, final List<Entry> pEntries)
	{
		if (pEntries == null || pEntries.isEmpty()) return snapshot(pConnectorId);
		Snapshot current = snapshot(pConnectorId);
		for (final Entry entry : pEntries) current = fold(current, entry.observation, entry.when, entry.failure, entry.success);
		persistence.save(current);
		return current;
	}

	private Snapshot record(final String pConnectorId, final Observation pObservation, final Date pWhen,
		final String pFailure, final boolean pSuccess)
	{
		final Snapshot next = fold(snapshot(pConnectorId), pObservation, pWhen, pFailure, pSuccess);
		persistence.save(next);
		return next;
	}

	private Snapshot fold(final Snapshot current, final Observation pObservation, final Date pWhen,
		final String pFailure, final boolean pSuccess)
	{
		final Observation observation = pObservation == null ? new Observation() : pObservation;
		final Map<Counter, Long> counters = new LinkedHashMap<Counter, Long>(current.counters);
		for (final Counter counter : Counter.values()) counters.put(counter,
			Long.valueOf(safeAdd(counters.get(counter).longValue(), observation.counters.get(counter).longValue())));
		final List<Long> durations = new ArrayList<Long>(current.durations);
		if (observation.durationMillis >= 0L)
		{
			durations.add(Long.valueOf(observation.durationMillis));
			if (durations.size() > MAX_DURATION_SAMPLES) durations.remove(0);
		}
		final Date when = requiredDate(pWhen);
		return new Snapshot(current.connectorId, counters, durations, pSuccess ? when : current.lastSuccessAt,
			pSuccess ? current.lastFailureAt : when, pSuccess ? current.lastFailure : pFailure,
			pSuccess ? 0 : increment(current.consecutiveFailures), current.checkpointAt,
			current.schemaDriftAt, current.schemaDriftCount, when);
	}

	private Snapshot save(final Snapshot pCurrent, final Map<Counter, Long> pCounters, final List<Long> pDurations,
		final Date pLastSuccess, final Date pLastFailure, final String pFailure, final int pConsecutiveFailures,
		final Date pCheckpoint, final Date pSchemaDrift, final int pSchemaDriftCount, final Date pUpdatedAt)
	{
		final Snapshot next = new Snapshot(pCurrent.connectorId, pCounters, pDurations, pLastSuccess, pLastFailure,
			pFailure, pConsecutiveFailures, pCheckpoint, pSchemaDrift, pSchemaDriftCount, pUpdatedAt);
		persistence.save(next);
		return next;
	}
	private static Map<Counter, Long> emptyCounters()
	{
		final Map<Counter, Long> values = new LinkedHashMap<Counter, Long>();
		for (final Counter counter : Counter.values()) values.put(counter, Long.valueOf(0L));
		return values;
	}
	private static long safeAdd(final long pLeft, final long pRight)
	{
		if (Long.MAX_VALUE - pLeft < pRight) return Long.MAX_VALUE;
		return pLeft + pRight;
	}
	private static int increment(final int pValue) { return pValue == Integer.MAX_VALUE ? pValue : pValue + 1; }
	private static Date requiredDate(final Date pDate) { return pDate == null ? new Date() : new Date(pDate.getTime()); }
	private static Date copy(final Date pDate) { return pDate == null ? null : new Date(pDate.getTime()); }

	/** Deterministic adapter used by tests and embedded runtimes. */
	public static final class MemoryPersistence implements Persistence
	{
		private final Map<String, Snapshot> values = new LinkedHashMap<String, Snapshot>();
		@Override public synchronized Snapshot load(final String pConnectorId) { return values.get(pConnectorId); }
		@Override public synchronized void save(final Snapshot pSnapshot) { values.put(pSnapshot.getConnectorId(), pSnapshot); }
	}
}
