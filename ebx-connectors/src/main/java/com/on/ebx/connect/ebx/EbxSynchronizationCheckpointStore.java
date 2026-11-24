package com.on.ebx.connect.ebx;

import java.util.Date;

import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.SynchronizationCheckpoint;
import com.on.ebx.connect.core.SynchronizationCheckpointStore;
import com.orchestranetworks.service.Session;

/** EBX repository adapter for durable synchronization checkpoints. */
public final class EbxSynchronizationCheckpointStore implements SynchronizationCheckpointStore
{
	private final ConnectorRepository repository;
	private final Session session;

	public EbxSynchronizationCheckpointStore(final ConnectorRepository pRepository, final Session pSession)
	{
		if (pRepository == null || pSession == null) throw new IllegalArgumentException("Repository and session are required");
		this.repository = pRepository;
		this.session = pSession;
	}

	@Override public SynchronizationCheckpoint load(final int pMappingId) { return this.repository.checkpoint(pMappingId); }

	@Override public SynchronizationCheckpoint save(final SynchronizationCheckpoint pCheckpoint, final long pExpectedVersion)
	{
		return this.repository.saveCheckpoint(this.session, pCheckpoint, pExpectedVersion, new Date());
	}
}
