package com.on.ebx.connect.core;

import java.util.List;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Session;

/**
 * What one record-mode run (many records synchronised one at a time) reads once and writes in groups (audit C-13), for the
 * thread that runs it:
 * <ul>
 * <li>the mappings, with their inputs and rules: {@link ConnectorRepository#mappings()} reads three tables, and an outbound
 * record used to read them again to find the mapping of the answer, and a replay once per row;</li>
 * <li>the configuration of each connector (a record, its properties and the resolution of its secret references), as a feed
 * run has done since C-34;</li>
 * <li>the bookkeeping, see {@link RecordBookkeeping}: sync log lines, provenance, outbound states and metrics, written
 * {@value #DEFAULT_BATCH} records at a time by default instead of in a transaction each.</li>
 * </ul>
 * A configuration or a mapping edited in EBX while the run is going on is taken into account by the next run, as for a feed.
 * One record on its own (a trigger, a single call) has no run and behaves as it always did.
 * <p>
 * {@code -Debx.connectors.record.bookkeepingBatch}: records whose bookkeeping waits; {@code 1} writes it after each record
 * (the previous behaviour, with the mappings and configurations still read once).
 */
final class RecordRun
{
	static final String BATCH_PROPERTY = "ebx.connectors.record.bookkeepingBatch";
	static final int DEFAULT_BATCH = 100;
	static final int MAX_BATCH = 1000;

	private static final ThreadLocal<RecordRun> CURRENT = new ThreadLocal<RecordRun>();

	private final Session session;
	private final ConnectorRepository repo;
	final TransformationRunCache cache = new TransformationRunCache();
	private List<MappingDefinition> mappings;
	private final RecordBookkeeping book;

	private RecordRun(final Session pSession, final ConnectorRepository pRepo, final int pBatch)
	{
		this.session = pSession;
		this.repo = pRepo;
		this.book = new RecordBookkeeping(new RecordBookkeeping.Sink()
		{
			@Override
			public boolean logs(final List<ConnectorRepository.LogEntry> pEntries)
			{
				return pRepo.logBatch(pSession, pEntries);
			}

			@Override
			public boolean provenance(final MappingDefinition pMapping, final ConnectorConfig pConnector, final List<ConnectorRepository.RecordChange> pChanges)
			{
				return pRepo.recordProvenanceBatch(pSession, pMapping, pConnector, pChanges, null);
			}

			@Override
			public boolean outbound(final List<ConnectorRepository.OutboundMark> pMarks)
			{
				return pRepo.markOutboundBatch(pSession, pMarks);
			}

			@Override
			public void outboundStateLost(final SyncResult pResult, final String pState, final String pOrigin)
			{
				ConnectorService.outboundStateLost(pSession, pRepo, pResult, pState, pOrigin);
			}
		}, pBatch);
	}

	/** The batch size the property asks for: unset or unreadable gives the default, at least 1, at most {@link #MAX_BATCH}. */
	static int batchSize(final String pRaw)
	{
		if (pRaw == null || pRaw.trim().isEmpty())
		{
			return RecordRun.DEFAULT_BATCH;
		}
		try
		{
			return Math.min(RecordRun.MAX_BATCH, Math.max(1, Integer.parseInt(pRaw.trim())));
		}
		catch (final NumberFormatException ex)
		{
			return RecordRun.DEFAULT_BATCH;
		}
	}

	/** The end of a run: closes it (writes what waits) when this scope opened it, does nothing when a run already held the thread. */
	static final class Scope implements AutoCloseable
	{
		private final RecordRun opened;

		Scope(final RecordRun pOpened)
		{
			this.opened = pOpened;
		}

		@Override
		public void close()
		{
			if (this.opened != null)
			{
				RecordRun.CURRENT.remove();
				this.opened.book.flush();
			}
		}
	}

	/** Starts a run on this thread, unless one is already going on (a run inside a run is part of the outer one). */
	static Scope open(final Session pSession, final Repository pRepository)
	{
		if (RecordRun.CURRENT.get() != null || pSession == null || pRepository == null)
		{
			return new Scope(null);
		}
		final RecordRun run = new RecordRun(pSession, ConnectorService.get().repository(pRepository), RecordRun.batchSize(System.getProperty(RecordRun.BATCH_PROPERTY)));
		RecordRun.CURRENT.set(run);
		return new Scope(run);
	}

	/**
	 * The run of this thread when it was opened for this session, null otherwise: whoever has none does everything at once.
	 * Two sessions are the same session when they are the same object or belong to the same user (a workflow context hands out
	 * its system session more than once): the writes of the run have the rights of that user either way.
	 */
	static RecordRun forSession(final Session pSession)
	{
		final RecordRun run = RecordRun.CURRENT.get();
		if (run == null || pSession == null)
		{
			return null;
		}
		return run.session == pSession || RecordRun.sameUser(run.session, pSession) ? run : null;
	}

	private static boolean sameUser(final Session pA, final Session pB)
	{
		try
		{
			return pA.getUserReference() != null && pA.getUserReference().equals(pB.getUserReference());
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/** The run of this thread, whoever opened it: for what only caches reads. */
	static RecordRun current()
	{
		return RecordRun.CURRENT.get();
	}

	private List<MappingDefinition> mappings()
	{
		if (this.mappings == null)
		{
			this.mappings = this.repo.mappings();
		}
		return this.mappings;
	}

	MappingDefinition mappingFor(final String pDataspace, final String pDataset, final String pTablePath, final String pConnectorId, final String pOperation,
		final MappingDefinition.Direction pDirection)
	{
		return ConnectorRepository.mappingFor(this.mappings(), pDataspace, pDataset, pTablePath, pConnectorId, pOperation, pDirection);
	}

	MappingDefinition mapping(final int pId)
	{
		return ConnectorRepository.mapping(this.mappings(), pId);
	}

	/** The configuration of a connector, read once for the run. */
	ConnectorConfig connectorConfig(final String pConnectorId)
	{
		return ConnectorService.runConnectorConfig(this.cache, this.repo, pConnectorId);
	}

	RecordBookkeeping bookkeeping()
	{
		return this.book;
	}
}
