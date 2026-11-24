package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Account-independent execution coordinator for bounded, restartable connector work.
 * Persistence and scheduling are deliberately ports: EBX tables, files, or tests can provide
 * them without changing checkpoint or replay semantics.
 */
public final class RecoverableExecution
{
	public static final int HARD_MAX_RECORDS = 1000;
	public static final int HARD_MAX_ATTEMPTS = 10;

	private RecoverableExecution() { }

	public enum DeletionPolicy { PROPAGATE, TOMBSTONE, IGNORE }
	public enum Status { SUCCESS, ERROR, DEAD_LETTER, CANCELLED, DRY_RUN, DELETION_IGNORED }

	public interface Cancellation
	{
		boolean isCancelled();
	}

	public interface StateStore
	{
		Checkpoint load(String pRunId);
		void save(Checkpoint pCheckpoint);
	}

	public interface Processor<T>
	{
		void execute(T pValue, String pIdempotencyKey) throws Exception;
		void delete(T pValue, String pIdempotencyKey) throws Exception;
		void tombstone(T pValue, String pIdempotencyKey) throws Exception;
	}

	public interface RetryClassifier
	{
		boolean isRetryable(Exception pFailure);
	}

	public static final class Work<T>
	{
		private final String id;
		private final T value;
		private final boolean deletion;
		public Work(final String pId, final T pValue, final boolean pDeletion)
		{
			if (pId == null || !pId.matches("[A-Za-z0-9._:-]{1,160}")) throw new IllegalArgumentException("Unsafe work id");
			this.id = pId;
			this.value = pValue;
			this.deletion = pDeletion;
		}
		public String getId() { return id; }
		public T getValue() { return value; }
		public boolean isDeletion() { return deletion; }
	}

	/** Fixed job policy. It is never accepted from per-run overrides. */
	public static final class Policy
	{
		private final int maximumRecords, maximumAttempts;
		private final DeletionPolicy deletionPolicy;
		public Policy(final int pMaximumRecords, final int pMaximumAttempts, final DeletionPolicy pDeletionPolicy)
		{
			if (pMaximumRecords < 1 || pMaximumRecords > HARD_MAX_RECORDS) throw new IllegalArgumentException("Invalid maximum records");
			if (pMaximumAttempts < 1 || pMaximumAttempts > HARD_MAX_ATTEMPTS) throw new IllegalArgumentException("Invalid maximum attempts");
			if (pDeletionPolicy == null) throw new IllegalArgumentException("Deletion policy is required");
			maximumRecords = pMaximumRecords;
			maximumAttempts = pMaximumAttempts;
			deletionPolicy = pDeletionPolicy;
		}
		public int getMaximumRecords() { return maximumRecords; }
		public int getMaximumAttempts() { return maximumAttempts; }
		public DeletionPolicy getDeletionPolicy() { return deletionPolicy; }
	}

	/** Auditable, safe run-time switches; no checkpoint, retry, deletion, or idempotency controls. */
	public static final class Overrides
	{
		private final int maximumRecords;
		private final boolean dryRun;
		public Overrides(final int pMaximumRecords, final boolean pDryRun)
		{
			if (pMaximumRecords < 1 || pMaximumRecords > HARD_MAX_RECORDS) throw new IllegalArgumentException("Invalid override maximum records");
			maximumRecords = pMaximumRecords;
			dryRun = pDryRun;
		}
		public int getMaximumRecords() { return maximumRecords; }
		public boolean isDryRun() { return dryRun; }
	}

	public static final class ItemResult
	{
		private final String id, idempotencyKey, error;
		private final Status status;
		private final int attempts;
		private ItemResult(final String pId, final String pKey, final Status pStatus, final int pAttempts, final String pError)
		{
			id = pId;
			idempotencyKey = pKey;
			status = pStatus;
			attempts = pAttempts;
			error = pError;
		}
		public String getId() { return id; }
		public String getIdempotencyKey() { return idempotencyKey; }
		public Status getStatus() { return status; }
		public int getAttempts() { return attempts; }
		public String getError() { return error; }
	}

	/** Serializable value object; stores advance only after an item reaches a terminal state. */
	public static final class Checkpoint
	{
		private final String runId;
		private final int nextIndex;
		private final Map<String, Integer> attempts;
		private final Set<String> completed, deadLetters;
		public Checkpoint(final String pRunId, final int pNextIndex, final Map<String, Integer> pAttempts,
			final Set<String> pCompleted, final Set<String> pDeadLetters)
		{
			if (pRunId == null || pRunId.trim().isEmpty()) throw new IllegalArgumentException("Run id is required");
			runId = pRunId;
			nextIndex = Math.max(0, pNextIndex);
			attempts = immutableMap(pAttempts);
			completed = immutableSet(pCompleted);
			deadLetters = immutableSet(pDeadLetters);
		}
		public static Checkpoint empty(final String pRunId) { return new Checkpoint(pRunId, 0, null, null, null); }
		public String getRunId() { return runId; }
		public int getNextIndex() { return nextIndex; }
		public Map<String, Integer> getAttempts() { return attempts; }
		public Set<String> getCompleted() { return completed; }
		public Set<String> getDeadLetters() { return deadLetters; }
	}

	public static <T> List<ItemResult> run(final String pRunId, final Iterable<Work<T>> pSource,
		final Policy pPolicy, final Overrides pOverrides, final StateStore pStore, final Cancellation pCancellation,
		final RetryClassifier pRetry, final Processor<T> pProcessor)
	{
		if (pSource == null || pPolicy == null || pOverrides == null || pStore == null || pProcessor == null)
			throw new IllegalArgumentException("Execution arguments are required");
		final int limit = Math.min(pPolicy.maximumRecords, pOverrides.maximumRecords);
		Checkpoint saved = pStore.load(pRunId);
		if (saved == null) saved = Checkpoint.empty(pRunId);
		if (!pRunId.equals(saved.runId)) throw new IllegalStateException("Checkpoint belongs to another run");
		final Map<String, Integer> attempts = new LinkedHashMap<String, Integer>(saved.attempts);
		final Set<String> completed = new LinkedHashSet<String>(saved.completed);
		final Set<String> dead = new LinkedHashSet<String>(saved.deadLetters);
		final List<ItemResult> results = new ArrayList<ItemResult>();
		int index = 0, processed = 0;
		for (final Work<T> work : pSource)
		{
			if (index++ < saved.nextIndex) continue;
			if (processed >= limit) break;
			if (cancelled(pCancellation))
			{
				results.add(new ItemResult(work.id, key(pRunId, work.id), Status.CANCELLED, count(attempts, work.id), null));
				break;
			}
			if (completed.contains(work.id)) continue;
			processed++;
			final String key = key(pRunId, work.id);
			if (pOverrides.dryRun)
			{
				results.add(new ItemResult(work.id, key, Status.DRY_RUN, count(attempts, work.id), null));
				continue; // simulations never mutate durable recovery state
			}
			Status status = Status.ERROR;
			String error = null;
			while (count(attempts, work.id) < pPolicy.maximumAttempts)
			{
				if (cancelled(pCancellation))
				{
					status = Status.CANCELLED;
					break;
				}
				attempts.put(work.id, Integer.valueOf(count(attempts, work.id) + 1));
				try
				{
					if (!work.deletion) pProcessor.execute(work.value, key);
					else if (pPolicy.deletionPolicy == DeletionPolicy.PROPAGATE) pProcessor.delete(work.value, key);
					else if (pPolicy.deletionPolicy == DeletionPolicy.TOMBSTONE) pProcessor.tombstone(work.value, key);
					status = work.deletion && pPolicy.deletionPolicy == DeletionPolicy.IGNORE ? Status.DELETION_IGNORED : Status.SUCCESS;
					completed.add(work.id);
					dead.remove(work.id);
					break;
				}
				catch (final Exception failure)
				{
					error = Redaction.redact(failure.getMessage() == null ? failure.toString() : failure.getMessage());
					if (pRetry == null || !pRetry.isRetryable(failure)) break;
				}
			}
			// A non-retryable failure is terminal immediately; an exhausted retryable failure is
			// terminal after the configured bound. Both are retained for explicit replay.
			if (status == Status.ERROR)
			{
				status = Status.DEAD_LETTER;
				dead.add(work.id);
				completed.add(work.id);
			}
			results.add(new ItemResult(work.id, key, status, count(attempts, work.id), error));
			if (status != Status.CANCELLED)
				pStore.save(new Checkpoint(pRunId, index, attempts, completed, dead));
			else break;
		}
		return Collections.unmodifiableList(results);
	}

	/** Explicit replay creates a new run while retaining stable keys derived from the original run. */
	public static Checkpoint replayDeadLetters(final Checkpoint pCheckpoint)
	{
		if (pCheckpoint == null) throw new IllegalArgumentException("Checkpoint is required");
		final Set<String> completed = new LinkedHashSet<String>(pCheckpoint.completed);
		final Map<String, Integer> attempts = new LinkedHashMap<String, Integer>(pCheckpoint.attempts);
		for (final String id : pCheckpoint.deadLetters)
		{
			completed.remove(id);
			attempts.remove(id);
		}
		return new Checkpoint(pCheckpoint.runId, 0, attempts, completed, Collections.<String>emptySet());
	}

	private static boolean cancelled(final Cancellation pCancellation) { return pCancellation != null && pCancellation.isCancelled(); }
	private static int count(final Map<String, Integer> pAttempts, final String pId)
	{
		final Integer n = pAttempts.get(pId);
		return n == null ? 0 : n.intValue();
	}
	private static String key(final String pRunId, final String pItemId) { return "ebx-" + Hashing.sha256(pRunId + "\n" + pItemId); }
	private static Map<String, Integer> immutableMap(final Map<String, Integer> pValue) { return Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(pValue == null ? Collections.<String, Integer>emptyMap() : pValue)); }
	private static Set<String> immutableSet(final Set<String> pValue) { return Collections.unmodifiableSet(new LinkedHashSet<String>(pValue == null ? Collections.<String>emptySet() : pValue)); }

	/** Deterministic store useful to embedded runtimes and tests; production EBX code should persist the same value object. */
	public static final class MemoryStateStore implements StateStore
	{
		private final Map<String, Checkpoint> states = new LinkedHashMap<String, Checkpoint>();
		public synchronized Checkpoint load(final String pRunId) { return states.get(pRunId); }
		public synchronized void save(final Checkpoint pCheckpoint) { states.put(pCheckpoint.runId, pCheckpoint); }
	}
}
