package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * The bookkeeping of a record-mode run (audit C-13): that it is written in bounded groups, in the order of the records, with the
 * same final rows as one transaction per record, and that a group that cannot be written is retried row by row so that only the
 * row at fault is lost and its result says so. The transactions themselves need EBX and are not run here.
 */
public final class RecordBookkeepingTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		bounded();
		sameRowsInTheSameOrder();
		fallbackRowByRow();
		outboundIdentity();
		metrics();
		System.out.println("PASS: record-mode bookkeeping: groups of at most the limit, same rows and order, row-by-row fallback, a state once per group, metrics read and written once");
	}

	private static void bounded()
	{
		final FakeSink sink = new FakeSink();
		final RecordBookkeeping book = new RecordBookkeeping(sink, 5);
		for (int i = 1; i <= 12; i++)
		{
			final SyncResult result = result("k" + i);
			book.log(new ConnectorRepository.LogEntry(result, "t", new Date(i)));
			book.provenance(mapping(1), connector("C"), new ConnectorRepository.RecordChange(null, Collections.<MappingResult.Change> emptyList()), result);
			book.outbound(mark(1, "k" + i), result);
		}
		// 3 rows per record, a group at 5 waiting rows: 12 records = 36 rows = 7 groups of 5 and the last one on flush
		check(book.waiting() < 5, "never more than the limit waits: " + book.waiting());
		book.flush();
		check(book.waiting() == 0, "flushed");
		check(sink.logRows == 12 && sink.provenanceRows == 12 && sink.outboundRows == 12, "every row written once: " + sink.logRows + "/" + sink.provenanceRows + "/" + sink.outboundRows);
		check(sink.logTransactions < 12 && sink.outboundTransactions < 12, "in fewer transactions than rows: " + sink.logTransactions + "/" + sink.outboundTransactions);
		check(sink.maxGroup <= 5, "no group above the limit: " + sink.maxGroup);
		// a limit of 1 is the previous behaviour: a transaction per row of each kind
		final FakeSink one = new FakeSink();
		final RecordBookkeeping single = new RecordBookkeeping(one, 1);
		for (int i = 1; i <= 4; i++)
		{
			single.log(new ConnectorRepository.LogEntry(result("k" + i), "t", new Date(i)));
		}
		check(one.logTransactions == 4 && one.logRows == 4, "limit 1: one transaction per row: " + one.logTransactions);
	}

	private static void sameRowsInTheSameOrder()
	{
		final FakeSink sink = new FakeSink();
		final RecordBookkeeping book = new RecordBookkeeping(sink, 100);
		for (int i = 1; i <= 7; i++)
		{
			book.log(new ConnectorRepository.LogEntry(result("k" + i), "t", new Date(i * 1000L)));
		}
		book.flush();
		check(sink.logKeys.toString().equals("[k1, k2, k3, k4, k5, k6, k7]"), "the lines come in the order of the records: " + sink.logKeys);
		check(sink.logTimes.get(0).getTime() == 1000L && sink.logTimes.get(6).getTime() == 7000L, "each line keeps the time its call ended: " + sink.logTimes);
		book.flush();
		check(sink.logTransactions == 1, "flushing twice writes nothing twice");
	}

	private static void fallbackRowByRow()
	{
		// a group that fails is retried row by row; only the row that cannot be written is lost, and its result says so
		final FakeSink sink = new FakeSink();
		sink.failKey = "k3";
		final RecordBookkeeping book = new RecordBookkeeping(sink, 100);
		final List<SyncResult> results = new ArrayList<SyncResult>();
		for (int i = 1; i <= 5; i++)
		{
			final SyncResult result = result("k" + i);
			results.add(result);
			book.log(new ConnectorRepository.LogEntry(result, "t", new Date(i)));
			book.provenance(mapping(1), connector("C"), new ConnectorRepository.RecordChange(null, Collections.<MappingResult.Change> emptyList()), result);
		}
		book.flush();
		for (int i = 0; i < 5; i++)
		{
			final boolean lostLog = results.get(i).getBookkeepingLost().contains(Bookkeeping.SYNC_LOG);
			check(lostLog == (i == 2), "only k3's line is lost: k" + (i + 1) + " " + results.get(i).getBookkeepingLost());
		}
		check(sink.logKeys.size() == 4 && !sink.logKeys.contains("k3"), "the other four are written: " + sink.logKeys);
		// a lone row that fails is lost without being tried twice
		final FakeSink failing = new FakeSink();
		failing.failAll = true;
		final RecordBookkeeping lone = new RecordBookkeeping(failing, 100);
		final SyncResult only = result("only");
		lone.log(new ConnectorRepository.LogEntry(only, "t", new Date()));
		lone.flush();
		check(only.getBookkeepingLost().contains(Bookkeeping.SYNC_LOG) && failing.logAttempts == 1, "a single row: one attempt: " + failing.logAttempts);
		// outbound states that cannot be written: the result says so and the log is told once per state
		final FakeSink outboundDown = new FakeSink();
		outboundDown.failAll = true;
		final RecordBookkeeping states = new RecordBookkeeping(outboundDown, 100);
		final SyncResult a = result("a");
		final SyncResult b = result("b");
		states.outbound(mark(1, "a"), a);
		states.outbound(mark(1, "b"), b);
		states.flush();
		check(outboundDown.lostStates.size() == 2 && outboundDown.lostStates.contains("a:SENT") && outboundDown.lostStates.contains("b:SENT"), "each lost state is told: " + outboundDown.lostStates);
	}

	private static void outboundIdentity()
	{
		// a state is written once per group: a second mark of the same record and mapping first writes the first
		final FakeSink sink = new FakeSink();
		final RecordBookkeeping book = new RecordBookkeeping(sink, 100);
		book.outbound(mark(1, "a"), result("a"));
		book.outbound(mark(1, "b"), result("b"));
		check(sink.outboundTransactions == 0, "different records share a group");
		book.outbound(mark(1, "a"), result("a"));
		check(sink.outboundTransactions == 1 && sink.outboundRows == 2, "the same record again writes what waits first: " + sink.outboundTransactions);
		book.outbound(mark(2, "a"), result("a"));
		check(sink.outboundTransactions == 1, "the same record under another mapping is another state");
		book.flush();
		check(sink.outboundRows == 4 && sink.outboundTransactions == 2, "everything written in the end: " + sink.outboundRows);
	}

	private static void metrics()
	{
		// the metrics of a connector are read and written once for a group, and end up as the observations one by one leave them
		final CountingPersistence counting = new CountingPersistence();
		final ConnectorOperationalMetrics grouped = new ConnectorOperationalMetrics(counting);
		final ConnectorOperationalMetrics.MemoryPersistence reference = new ConnectorOperationalMetrics.MemoryPersistence();
		final ConnectorOperationalMetrics oneByOne = new ConnectorOperationalMetrics(reference);
		final List<ConnectorOperationalMetrics.Entry> entries = new ArrayList<ConnectorOperationalMetrics.Entry>();
		for (int i = 1; i <= 6; i++)
		{
			final boolean ok = i != 3 && i != 4;
			final ConnectorOperationalMetrics.Observation observation = new ConnectorOperationalMetrics.Observation()
				.add(ConnectorOperationalMetrics.Counter.REQUEST, 1).add(ConnectorOperationalMetrics.Counter.WRITE, 1).durationMillis(i * 10);
			final ConnectorOperationalMetrics.Observation again = new ConnectorOperationalMetrics.Observation()
				.add(ConnectorOperationalMetrics.Counter.REQUEST, 1).add(ConnectorOperationalMetrics.Counter.WRITE, 1).durationMillis(i * 10);
			entries.add(new ConnectorOperationalMetrics.Entry(observation, new Date(i * 1000L), ok ? null : "boom " + i, ok));
			if (ok)
			{
				oneByOne.success("Slack", again, new Date(i * 1000L));
			}
			else
			{
				oneByOne.failure("Slack", again, new Date(i * 1000L), "boom " + i);
			}
		}
		grouped.recordAll("Slack", entries);
		check(counting.saves == 1 && counting.loads == 1, "one read and one write for six observations: " + counting.loads + "/" + counting.saves);
		final ConnectorOperationalMetrics.Snapshot g = counting.last;
		final ConnectorOperationalMetrics.Snapshot r = reference.load("Slack");
		check(g.get(ConnectorOperationalMetrics.Counter.REQUEST) == r.get(ConnectorOperationalMetrics.Counter.REQUEST) && g.get(ConnectorOperationalMetrics.Counter.WRITE) == 6L, "same counters");
		check(g.getConsecutiveFailures() == r.getConsecutiveFailures() && g.getConsecutiveFailures() == 0, "a success after the failures: same streak " + g.getConsecutiveFailures());
		check(g.getDurations().equals(r.getDurations()), "same durations: " + g.getDurations());
		check(g.getLastFailureAt().getTime() == r.getLastFailureAt().getTime() && "boom 4".equals(g.getLastFailure()) && "boom 4".equals(r.getLastFailure()), "same last failure");
		check(g.getLastSuccessAt().getTime() == r.getLastSuccessAt().getTime(), "same last success");
	}

	private static SyncResult result(final String pKey)
	{
		final SyncResult result = new SyncResult("Slack", "post", "OUTBOUND", "BCompanies", "/root/Company", pKey, pKey);
		result.setStatus(SyncResult.Status.OK);
		return result;
	}

	private static MappingDefinition mapping(final int pId)
	{
		return new MappingDefinition(pId, "Slack", "post", MappingDefinition.Direction.OUTBOUND, "m", null, null, "/root/Company", "", true);
	}

	private static ConnectorConfig connector(final String pId)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId(pId);
		return config;
	}

	private static ConnectorRepository.OutboundMark mark(final int pMapping, final String pKey)
	{
		return new ConnectorRepository.OutboundMark(mapping(pMapping), pKey, pKey, "SENT", "h", "{}", null, "R1");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	/** Counts what a database would be asked and can be told to refuse a row or everything. */
	private static final class FakeSink implements RecordBookkeeping.Sink
	{
		int logTransactions, outboundTransactions, logRows, provenanceRows, outboundRows, maxGroup, logAttempts;
		String failKey;
		boolean failAll;
		final List<String> logKeys = new ArrayList<String>();
		final List<Date> logTimes = new ArrayList<Date>();
		final List<String> lostStates = new ArrayList<String>();

		@Override
		public boolean logs(final List<ConnectorRepository.LogEntry> pEntries)
		{
			this.logAttempts++;
			this.maxGroup = Math.max(this.maxGroup, pEntries.size());
			if (this.failAll)
			{
				return false;
			}
			for (final ConnectorRepository.LogEntry entry : pEntries)
			{
				if (entry.result.getRecordKey().equals(this.failKey))
				{
					return false;
				}
			}
			this.logTransactions++;
			for (final ConnectorRepository.LogEntry entry : pEntries)
			{
				this.logRows++;
				this.logKeys.add(entry.result.getRecordKey());
				this.logTimes.add(entry.at);
			}
			return true;
		}

		@Override
		public boolean provenance(final MappingDefinition pMapping, final ConnectorConfig pConnector, final List<ConnectorRepository.RecordChange> pChanges)
		{
			this.maxGroup = Math.max(this.maxGroup, pChanges.size());
			if (this.failAll)
			{
				return false;
			}
			this.provenanceRows += pChanges.size();
			return true;
		}

		@Override
		public boolean outbound(final List<ConnectorRepository.OutboundMark> pMarks)
		{
			this.maxGroup = Math.max(this.maxGroup, pMarks.size());
			if (this.failAll)
			{
				return false;
			}
			this.outboundTransactions++;
			this.outboundRows += pMarks.size();
			return true;
		}

		@Override
		public void outboundStateLost(final SyncResult pResult, final String pState, final String pOrigin)
		{
			this.lostStates.add(pResult.getRecordKey() + ":" + pState);
		}
	}

	private static final class CountingPersistence implements ConnectorOperationalMetrics.Persistence
	{
		int loads, saves;
		ConnectorOperationalMetrics.Snapshot last;

		@Override
		public ConnectorOperationalMetrics.Snapshot load(final String pConnectorId)
		{
			this.loads++;
			return this.last;
		}

		@Override
		public void save(final ConnectorOperationalMetrics.Snapshot pSnapshot)
		{
			this.saves++;
			this.last = pSnapshot;
		}
	}
}
