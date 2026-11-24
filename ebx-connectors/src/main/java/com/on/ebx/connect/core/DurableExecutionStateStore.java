package com.on.ebx.connect.core;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Durable adapter for restartable executions, with an atomic bounded lease per run. */
public final class DurableExecutionStateStore implements RecoverableExecution.StateStore
{
	public static final long MAX_LEASE_MILLIS = 60L * 60L * 1000L;

	public interface Persistence
	{
		RunState load(String pRunId);
		void save(RunState pState);
		boolean acquireLease(String pRunId, String pOwner, Date pNow, Date pExpiresAt);
		void releaseLease(String pRunId, String pOwner);
	}

	public static final class RunState
	{
		private final RecoverableExecution.Checkpoint checkpoint;
		private final String leaseOwner;
		private final Date leaseExpiresAt;
		public RunState(final RecoverableExecution.Checkpoint pCheckpoint, final String pLeaseOwner, final Date pLeaseExpiresAt)
		{
			if (pCheckpoint == null) throw new IllegalArgumentException("Checkpoint is required");
			checkpoint = pCheckpoint;
			leaseOwner = pLeaseOwner;
			leaseExpiresAt = pLeaseExpiresAt == null ? null : new Date(pLeaseExpiresAt.getTime());
		}
		public RecoverableExecution.Checkpoint getCheckpoint() { return checkpoint; }
		public String getLeaseOwner() { return leaseOwner; }
		public Date getLeaseExpiresAt() { return leaseExpiresAt == null ? null : new Date(leaseExpiresAt.getTime()); }
	}

	private final Persistence persistence;
	public DurableExecutionStateStore(final Persistence pPersistence)
	{
		if (pPersistence == null) throw new IllegalArgumentException("Persistence is required");
		persistence = pPersistence;
	}

	@Override public RecoverableExecution.Checkpoint load(final String pRunId)
	{
		final RunState state = persistence.load(pRunId);
		return state == null ? null : state.getCheckpoint();
	}

	@Override public void save(final RecoverableExecution.Checkpoint pCheckpoint)
	{
		final RunState current = persistence.load(pCheckpoint.getRunId());
		persistence.save(new RunState(pCheckpoint, current == null ? null : current.getLeaseOwner(),
			current == null ? null : current.getLeaseExpiresAt()));
	}

	public Lease acquire(final String pRunId, final String pOwner, final long pLeaseMillis, final Date pNow)
	{
		if (pRunId == null || pRunId.trim().isEmpty() || pOwner == null || pOwner.trim().isEmpty())
			throw new IllegalArgumentException("Run and lease owner are required");
		if (pLeaseMillis < 1L || pLeaseMillis > MAX_LEASE_MILLIS) throw new IllegalArgumentException("Invalid lease duration");
		final Date now = pNow == null ? new Date() : new Date(pNow.getTime());
		final Date expires = new Date(now.getTime() + pLeaseMillis);
		if (!persistence.acquireLease(pRunId, pOwner, now, expires)) throw new IllegalStateException("Execution is already leased");
		return new Lease(persistence, pRunId, pOwner);
	}

	public static final class Lease implements AutoCloseable
	{
		private final Persistence persistence;
		private final String runId, owner;
		private boolean closed;
		private Lease(final Persistence pPersistence, final String pRunId, final String pOwner)
		{
			persistence = pPersistence;
			runId = pRunId;
			owner = pOwner;
		}
		@Override public synchronized void close()
		{
			if (!closed)
			{
				persistence.releaseLease(runId, owner);
				closed = true;
			}
		}
	}

	/** Atomic deterministic implementation for tests and embedded runtimes. */
	public static final class MemoryPersistence implements Persistence
	{
		private final Map<String, RunState> states = new LinkedHashMap<String, RunState>();
		@Override public synchronized RunState load(final String pRunId) { return states.get(pRunId); }
		@Override public synchronized void save(final RunState pState) { states.put(pState.getCheckpoint().getRunId(), pState); }
		@Override public synchronized boolean acquireLease(final String pRunId, final String pOwner, final Date pNow, final Date pExpiresAt)
		{
			RunState state = states.get(pRunId);
			if (state == null) state = new RunState(RecoverableExecution.Checkpoint.empty(pRunId), null, null);
			if (state.leaseOwner != null && state.leaseExpiresAt != null && state.leaseExpiresAt.after(pNow) && !pOwner.equals(state.leaseOwner)) return false;
			states.put(pRunId, new RunState(state.checkpoint, pOwner, pExpiresAt));
			return true;
		}
		@Override public synchronized void releaseLease(final String pRunId, final String pOwner)
		{
			final RunState state = states.get(pRunId);
			if (state != null && pOwner.equals(state.leaseOwner)) states.put(pRunId, new RunState(state.checkpoint, null, null));
		}
	}
}
