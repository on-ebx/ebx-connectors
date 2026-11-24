package com.on.ebx.connect.core;

import java.util.Date;

import com.fasterxml.jackson.databind.JsonNode;

/** Orders an incremental unit of work so its policy checkpoint advances only after commit. */
public final class CommittedIncrementalExecution
{
	public interface Work { JsonNode execute() throws Exception; }
	public interface Commit { void commit() throws Exception; }
	private CommittedIncrementalExecution() { }

	public static SynchronizationCheckpoint execute(final MappingDefinition pMapping,
		final SynchronizationCheckpointManager pCheckpoints, final boolean pDryRun,
		final Work pWork, final Commit pCommit, final Date pCompletedAt) throws Exception
	{
		if (pMapping == null || pCheckpoints == null || pWork == null || pCommit == null)
			throw new IllegalArgumentException("Mapping, checkpoint manager, work and commit are required");
		final JsonNode response = pWork.execute();
		if (pDryRun) return null;
		pCommit.commit();
		return pCheckpoints.complete(pMapping, response, true, false, pCompletedAt);
	}
}
