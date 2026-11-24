package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * The bookkeeping of a record-mode run, written in bounded groups instead of one transaction per row (audit C-13).
 * <p>
 * A run that synchronises records one at a time (enrichment, outbound, a scheduled run that is not a feed, "whole table" from
 * the screen, a replay) used to write, after each record, a sync log line, the provenance of its fields, an outbound state
 * and a metrics row, each in a transaction of its own. Here they wait, up to {@code limit} records, and are written by one
 * transaction per kind. The final state is the same: every line, provenance row and state is written, in the order of the
 * records, the log line carrying the time the call ended. What differs: a crash in the middle of a run loses the
 * bookkeeping of at most {@code limit} records (an outbound record whose state was not written is sent again by the next
 * {@code unsent}/{@code changed} scope), and the lines of a group appear together.
 * <p>
 * A group that cannot be written is not given up: its rows are retried one at a time, so that only a row that cannot be
 * written on its own is lost, and that row's result says so ({@link SyncResult#bookkeepingLost}), exactly as an unbatched
 * write would have. The conflicts are not buffered: they are rare and are decided against what is stored.
 * <p>
 * Pure: the writes go through a {@link Sink}, so that the grouping, the order and the fallback are tested without EBX.
 */
final class RecordBookkeeping
{
	/** Where the groups go: one transaction per call. */
	interface Sink
	{
		boolean logs(List<ConnectorRepository.LogEntry> pEntries);

		boolean provenance(MappingDefinition pMapping, ConnectorConfig pConnector, List<ConnectorRepository.RecordChange> pChanges);

		boolean outbound(List<ConnectorRepository.OutboundMark> pMarks);

		/** The state of a record could not be saved after the remote call happened: say so in the log, once. */
		void outboundStateLost(SyncResult pResult, String pState, String pOrigin);
	}

	private static final class Pending<T>
	{
		final T item;
		final SyncResult result;

		Pending(final T pItem, final SyncResult pResult)
		{
			this.item = pItem;
			this.result = pResult;
		}
	}

	private static final class ProvenanceGroup
	{
		final MappingDefinition mapping;
		final ConnectorConfig connector;
		final List<Pending<ConnectorRepository.RecordChange>> entries = new ArrayList<Pending<ConnectorRepository.RecordChange>>();

		ProvenanceGroup(final MappingDefinition pMapping, final ConnectorConfig pConnector)
		{
			this.mapping = pMapping;
			this.connector = pConnector;
		}
	}

	private final Sink sink;
	private final int limit;
	private final List<Pending<ConnectorRepository.LogEntry>> logs = new ArrayList<Pending<ConnectorRepository.LogEntry>>();
	private final Map<String, ProvenanceGroup> provenance = new LinkedHashMap<String, ProvenanceGroup>();
	private final List<Pending<ConnectorRepository.OutboundMark>> marks = new ArrayList<Pending<ConnectorRepository.OutboundMark>>();
	private final Set<String> markIdentities = new HashSet<String>();
	/** Rows waiting, of any kind: what the limit bounds. */
	private int waiting;

	RecordBookkeeping(final Sink pSink, final int pLimit)
	{
		this.sink = pSink;
		this.limit = Math.max(1, pLimit);
	}

	/** A sync log line; its result is told if the line is lost. */
	void log(final ConnectorRepository.LogEntry pEntry)
	{
		this.logs.add(new Pending<ConnectorRepository.LogEntry>(pEntry, pEntry.result));
		this.added();
	}

	/** The provenance of one record's changed fields; the result is told if it is lost. */
	void provenance(final MappingDefinition pMapping, final ConnectorConfig pConnector, final ConnectorRepository.RecordChange pChange, final SyncResult pResult)
	{
		final String key = (pMapping == null ? "?" : String.valueOf(pMapping.getId())) + "\u0000" + (pConnector == null ? "?" : pConnector.getId());
		ProvenanceGroup group = this.provenance.get(key);
		if (group == null)
		{
			group = new ProvenanceGroup(pMapping, pConnector);
			this.provenance.put(key, group);
		}
		group.entries.add(new Pending<ConnectorRepository.RecordChange>(pChange, pResult));
		this.added();
	}

	/**
	 * An outbound state. A state is written once per group: a second mark of the same record and mapping first writes what is
	 * waiting, so that the second finds the first stored (attempts count, document) as it would have unbatched.
	 */
	void outbound(final ConnectorRepository.OutboundMark pMark, final SyncResult pResult)
	{
		if (pMark.recordKey != null && !this.markIdentities.add(pMark.identity()))
		{
			this.flush();
			this.markIdentities.add(pMark.identity());
		}
		this.marks.add(new Pending<ConnectorRepository.OutboundMark>(pMark, pResult));
		this.added();
	}

	private void added()
	{
		this.waiting++;
		if (this.waiting >= this.limit)
		{
			this.flush();
		}
	}

	/** Rows waiting to be written. */
	int waiting()
	{
		return this.waiting;
	}

	/** Writes everything waiting: provenance, outbound states, then the log lines. Never throws. */
	void flush()
	{
		final List<ProvenanceGroup> groups = new ArrayList<ProvenanceGroup>(this.provenance.values());
		final List<Pending<ConnectorRepository.OutboundMark>> toMark = new ArrayList<Pending<ConnectorRepository.OutboundMark>>(this.marks);
		final List<Pending<ConnectorRepository.LogEntry>> toLog = new ArrayList<Pending<ConnectorRepository.LogEntry>>(this.logs);
		this.provenance.clear();
		this.marks.clear();
		this.markIdentities.clear();
		this.logs.clear();
		this.waiting = 0;
		try
		{
			for (final ProvenanceGroup group : groups)
			{
				this.writeProvenance(group);
			}
			this.writeMarks(toMark);
			this.writeLogs(toLog);
		}
		catch (final RuntimeException ex)
		{
			// a sink never throws; if one does, the run must still end and say what it could not write
			Bookkeeping.lost(Bookkeeping.SYNC_LOG, "record-mode run", ex);
		}
	}

	private void writeProvenance(final ProvenanceGroup pGroup)
	{
		final List<ConnectorRepository.RecordChange> all = new ArrayList<ConnectorRepository.RecordChange>(pGroup.entries.size());
		for (final Pending<ConnectorRepository.RecordChange> entry : pGroup.entries)
		{
			all.add(entry.item);
		}
		if (this.sink.provenance(pGroup.mapping, pGroup.connector, all))
		{
			return;
		}
		if (pGroup.entries.size() == 1)
		{
			pGroup.entries.get(0).result.bookkeepingLost(Bookkeeping.PROVENANCE);
			return;
		}
		for (final Pending<ConnectorRepository.RecordChange> entry : pGroup.entries)
		{
			if (!this.sink.provenance(pGroup.mapping, pGroup.connector, java.util.Collections.singletonList(entry.item)))
			{
				entry.result.bookkeepingLost(Bookkeeping.PROVENANCE);
			}
		}
	}

	private void writeMarks(final List<Pending<ConnectorRepository.OutboundMark>> pMarks)
	{
		if (pMarks.isEmpty())
		{
			return;
		}
		final List<ConnectorRepository.OutboundMark> all = new ArrayList<ConnectorRepository.OutboundMark>(pMarks.size());
		for (final Pending<ConnectorRepository.OutboundMark> mark : pMarks)
		{
			all.add(mark.item);
		}
		if (this.sink.outbound(all))
		{
			return;
		}
		for (final Pending<ConnectorRepository.OutboundMark> mark : pMarks)
		{
			// with one mark the group already was the single write
			if (pMarks.size() == 1 || !this.sink.outbound(java.util.Collections.singletonList(mark.item)))
			{
				this.sink.outboundStateLost(mark.result, mark.item.status, mark.item.origin);
			}
		}
	}

	private void writeLogs(final List<Pending<ConnectorRepository.LogEntry>> pLogs)
	{
		if (pLogs.isEmpty())
		{
			return;
		}
		final List<ConnectorRepository.LogEntry> all = new ArrayList<ConnectorRepository.LogEntry>(pLogs.size());
		for (final Pending<ConnectorRepository.LogEntry> entry : pLogs)
		{
			all.add(entry.item);
		}
		if (this.sink.logs(all))
		{
			return;
		}
		if (pLogs.size() == 1)
		{
			pLogs.get(0).result.bookkeepingLost(Bookkeeping.SYNC_LOG);
			return;
		}
		for (final Pending<ConnectorRepository.LogEntry> entry : pLogs)
		{
			if (!this.sink.logs(java.util.Collections.singletonList(entry.item)))
			{
				entry.result.bookkeepingLost(Bookkeeping.SYNC_LOG);
			}
		}
	}
}
