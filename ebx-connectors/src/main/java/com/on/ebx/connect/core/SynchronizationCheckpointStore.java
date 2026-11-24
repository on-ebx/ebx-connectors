package com.on.ebx.connect.core;

/** Durable checkpoint storage. Implementations must reject stale expected versions. */
public interface SynchronizationCheckpointStore
{
	SynchronizationCheckpoint load(int pMappingId);
	SynchronizationCheckpoint save(SynchronizationCheckpoint pCheckpoint, long pExpectedVersion);
}
