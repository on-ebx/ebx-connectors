package com.on.ebx.connect.core;

import java.util.Date;

import com.on.ebx.connect.spi.SynchronizationStrategy;

/** Explicit checkpoint-after-apply boundary for CDC consumers. */
public final class CdcCheckpointCoordinator
{
	private final SynchronizationCheckpointStore store;
	public CdcCheckpointCoordinator(final SynchronizationCheckpointStore pStore)
	{
		if (pStore == null) throw new IllegalArgumentException("Checkpoint store is required");
		this.store = pStore;
	}

	public SynchronizationCheckpoint confirmApplied(final int pMappingId, final CdcBatch pBatch,
		final boolean pSuccessful, final boolean pDryRun, final Date pCompletedAt)
	{
		if (pMappingId < 1) throw new IllegalArgumentException("A persisted mapping id is required");
		if (!pSuccessful || pDryRun || pBatch == null || pBatch.getEvents().isEmpty()) return null;
		if (pBatch.getCandidateCheckpoint() == null || pBatch.getCandidateCheckpoint().trim().isEmpty())
			throw new IllegalArgumentException("A non-empty CDC candidate checkpoint is required");
		final SynchronizationCheckpoint current = this.store.load(pMappingId);
		if (current != null && current.getStrategy() != SynchronizationStrategy.VENDOR_CURSOR)
			throw new IllegalStateException("CDC requires VENDOR_CURSOR checkpoint strategy");
		final long expected = current == null ? 0L : current.getVersion();
		return this.store.save(new SynchronizationCheckpoint(pMappingId, SynchronizationStrategy.VENDOR_CURSOR,
			pBatch.getCandidateCheckpoint(), pCompletedAt == null ? new Date() : pCompletedAt, expected), expected);
	}
}
