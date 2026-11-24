package com.on.ebx.connect.connectors;

import java.util.Date;
import java.util.List;

import com.on.ebx.connect.core.CdcBatch;
import com.on.ebx.connect.core.CdcCheckpointCoordinator;
import com.on.ebx.connect.core.DebeziumKafkaCdcAdapter;
import com.on.ebx.connect.core.SynchronizationCheckpoint;

/**
 * Atomic application boundary for Kafka/Debezium CDC. Polling never commits broker offsets;
 * the EBX vendor-cursor checkpoint is confirmed only after the complete batch was applied.
 */
public final class KafkaCdcExecution
{
	public interface Source
	{
		List<DebeziumKafkaCdcAdapter.Record> poll(String pCheckpoint) throws Exception;
	}
	public interface Applier
	{
		void apply(CdcBatch pBatch, boolean pDryRun) throws Exception;
	}
	private KafkaCdcExecution() { }

	public static SynchronizationCheckpoint pollApplyConfirm(final int pMappingId, final String pCheckpoint,
		final int pMaxEvents, final int pMaxBytes, final boolean pDryRun, final Source pSource,
		final Applier pApplier, final CdcCheckpointCoordinator pCoordinator, final Date pCompletedAt) throws Exception
	{
		if (pSource == null || pApplier == null || pCoordinator == null)
			throw new IllegalArgumentException("CDC source, applier and checkpoint coordinator are required");
		final CdcBatch batch = DebeziumKafkaCdcAdapter.normalize(pSource.poll(pCheckpoint), pCheckpoint, pMaxEvents, pMaxBytes);
		if (batch.getEvents().isEmpty()) return null;
		pApplier.apply(batch, pDryRun);
		return pCoordinator.confirmApplied(pMappingId, batch, true, pDryRun, pCompletedAt);
	}
}
