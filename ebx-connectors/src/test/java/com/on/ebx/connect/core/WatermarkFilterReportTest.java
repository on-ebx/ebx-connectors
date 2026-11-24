package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;

/**
 * The incremental-feed filter passes the sink's "these rows are written" report back to the source in the
 * source's own numbering (audit C-06): a row the filter left out is settled with its neighbours, a row it
 * passed on is settled as the sink says, and a failure stays attached to the right row.
 */
public final class WatermarkFilterReportTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// source rows 0..5 with watermarks 1,2,5,3,6,2 - checkpoint 3 exclusive: rows 2 (5) and 4 (6) pass, the others are skipped
		final Recorder sink = new Recorder();
		final WatermarkFilter filter = new WatermarkFilter(sink, Watermark.Kind.NUMBER, "n", "3", false, null);
		final List<String> reports = new ArrayList<String>();
		final boolean live = filter.onHandled(new Connector.HandledListener()
		{
			@Override
			public void handled(final int pFrom, final int pTo, final Set<Integer> pNotWritten)
			{
				reports.add(pFrom + "-" + pTo + new TreeSet<Integer>(pNotWritten));
			}
		});
		check(live, "a sink that reports lets the filter report");
		for (final String n : Arrays.asList("1", "2", "5", "3", "6", "2"))
		{
			filter.accept(Json.parse("{\"n\":" + n + "}"));
		}
		check(sink.rows == 2, "two rows reached the sink: " + sink.rows);
		// the sink settles its row 0 (source row 2), then its row 1 (source row 4) as failed
		sink.listener.handled(0, 1, new HashSet<Integer>());
		sink.listener.handled(1, 2, new HashSet<Integer>(Arrays.asList(Integer.valueOf(1))));
		check(reports.equals(Arrays.asList("0-3[]", "3-5[4]")), "rows skipped before the first are settled with it, the failed row keeps its source number: " + reports);
		filter.endOfRows();
		check(reports.get(reports.size() - 1).equals("5-6[]"), "the skipped row after the last one is settled at the end: " + reports);
		check(sink.ended, "and the sink was asked to finish first");

		// a sink that cannot report: the filter cannot either
		final WatermarkFilter silent = new WatermarkFilter(new Connector.RowSink()
		{
			@Override
			public boolean accept(final JsonNode pRow)
			{
				return true;
			}
		}, Watermark.Kind.NUMBER, "n", null, false, null);
		check(!silent.onHandled(new Connector.HandledListener()
		{
			@Override
			public void handled(final int pFrom, final int pTo, final Set<Integer> pNotWritten)
			{
			}
		}), "no report to give when the sink gives none");

		// a gap in the sink's numbering (rows it chose not to take) is not settled on its behalf
		final Recorder gapSink = new Recorder();
		final WatermarkFilter gap = new WatermarkFilter(gapSink, Watermark.Kind.NUMBER, "n", null, false, null);
		final List<String> gapReports = new ArrayList<String>();
		gap.onHandled(new Connector.HandledListener()
		{
			@Override
			public void handled(final int pFrom, final int pTo, final Set<Integer> pNotWritten)
			{
				gapReports.add(pFrom + "-" + pTo);
			}
		});
		for (int i = 0; i < 4; i++)
		{
			gap.accept(Json.parse("{\"n\":" + i + "}"));
		}
		gapSink.listener.handled(2, 4, new HashSet<Integer>());
		check(gapReports.equals(Arrays.asList("2-4")), "rows 0 and 1 were never settled by the sink: " + gapReports);
		System.out.println("WatermarkFilterReportTest OK");
	}

	private static final class Recorder implements Connector.RowSink
	{
		int rows;
		boolean ended;
		Connector.HandledListener listener;

		@Override
		public boolean accept(final JsonNode pRow)
		{
			this.rows++;
			return true;
		}

		@Override
		public boolean onHandled(final Connector.HandledListener pListener)
		{
			this.listener = pListener;
			return true;
		}

		@Override
		public void endOfRows()
		{
			this.ended = true;
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
