package com.on.ebx.connect.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic store for tests and non-EBX embedding. */
public final class InMemorySynchronizationCheckpointStore implements SynchronizationCheckpointStore
{
	private final Map<Integer, SynchronizationCheckpoint> states = new LinkedHashMap<Integer, SynchronizationCheckpoint>();

	@Override public synchronized SynchronizationCheckpoint load(final int pMappingId) { return this.states.get(Integer.valueOf(pMappingId)); }

	@Override public synchronized SynchronizationCheckpoint save(final SynchronizationCheckpoint pCheckpoint, final long pExpectedVersion)
	{
		if (pCheckpoint == null) throw new IllegalArgumentException("Checkpoint is required");
		final Integer key = Integer.valueOf(pCheckpoint.getMappingId());
		final SynchronizationCheckpoint current = this.states.get(key);
		final long actual = current == null ? 0L : current.getVersion();
		if (actual != pExpectedVersion) throw new IllegalStateException("Checkpoint was advanced concurrently");
		final SynchronizationCheckpoint saved = new SynchronizationCheckpoint(pCheckpoint.getMappingId(),
			pCheckpoint.getStrategy(), pCheckpoint.getValue(), pCheckpoint.getUpdatedAt(), actual + 1L);
		this.states.put(key, saved);
		return saved;
	}
}
