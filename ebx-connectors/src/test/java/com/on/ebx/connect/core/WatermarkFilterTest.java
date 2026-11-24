package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;

/**
 * Volume lot V2-D: the client-side filter of an incremental feed - rows at or below the checkpoint are
 * dropped before they reach the batching sink, the highest watermark is tracked, a row that cannot be
 * judged is delivered and never taken for the highest value, and the boundary option decides what happens
 * to a row that EQUALS the checkpoint.
 */
public final class WatermarkFilterTest
{
	private WatermarkFilterTest() { }

	public static void main(final String[] args) throws Exception
	{
		final Watermark.Kind t = Watermark.Kind.TIMESTAMP;

		// no checkpoint (first run, full refresh): everything is delivered, the highest value is tracked
		Collector all = new Collector();
		WatermarkFilter filter = new WatermarkFilter(all, t, "LAST_MODIFIED", null, true, null);
		feed(filter, "2026-01-01T10:00", "2026-01-03T10:00", "2026-01-02T10:00");
		check(all.rows.size() == 3, "without a checkpoint every row is delivered: " + all.rows.size());
		check(filter.getSkipped() == 0, "nothing skipped without a checkpoint");
		check("2026-01-03T10:00".equals(filter.getMax()), "the highest value is tracked whatever the order: " + filter.getMax());
		check(filter.getSeen() == 3, "seen counts every row");

		// exclusive: rows at or below the checkpoint are dropped, including the equal one
		Collector out = new Collector();
		final ImportOutcome outcome = new ImportOutcome();
		filter = new WatermarkFilter(out, t, "LAST_MODIFIED", "2026-01-02T10:00", false, outcome);
		feed(filter, "2026-01-01T10:00", "2026-01-02T10:00", "2026-01-02T10:00:00.5", "2026-01-03T10:00");
		check(out.rows.size() == 2, "exclusive: only the rows past the checkpoint are delivered: " + out.rows.size());
		check(filter.getSkipped() == 2 && outcome.getSkippedByWatermark() == 2, "two rows skipped, counted on the filter and on the outcome");
		check(filter.getSeen() == 4, "skipped rows are still seen");
		check("2026-01-03T10:00".equals(filter.getMax()), "max: " + filter.getMax());
		check("2026-01-02T10:00".equals(filter.getCheckpoint()), "the checkpoint is kept");

		// inclusive: the row that equals the checkpoint is delivered again (it may have been committed after the previous read)
		out = new Collector();
		filter = new WatermarkFilter(out, t, "LAST_MODIFIED", "2026-01-02T10:00", true, null);
		feed(filter, "2026-01-01T10:00", "2026-01-02T10:00:00", "2026-01-03T10:00");
		check(out.rows.size() == 2, "inclusive: the equal row is delivered again: " + out.rows.size());
		check(filter.getSkipped() == 1, "only the strictly older row is skipped");

		// a row that cannot be judged is delivered, counted, and never becomes the maximum
		out = new Collector();
		filter = new WatermarkFilter(out, t, "LAST_MODIFIED", "2026-01-02T10:00", false, null);
		filter.accept(Json.parse("{\"LAST_MODIFIED\":null}"));
		filter.accept(Json.parse("{\"other\":1}"));
		filter.accept(Json.parse("{\"LAST_MODIFIED\":\"garbage\"}"));
		filter.accept(Json.parse("{\"LAST_MODIFIED\":\"2026-01-05T00:00\"}"));
		check(out.rows.size() == 4, "unreadable rows are delivered, never silently dropped: " + out.rows.size());
		check(filter.getUnreadable() == 3, "unreadable rows are counted: " + filter.getUnreadable());
		check("2026-01-05T00:00".equals(filter.getMax()), "only a valid value can be the maximum: " + filter.getMax());

		// a source with no valid value at all leaves no maximum
		filter = new WatermarkFilter(new Collector(), t, "LAST_MODIFIED", null, true, null);
		filter.accept(Json.parse("{\"x\":1}"));
		check(filter.getMax() == null, "no valid value, no maximum");

		// a downstream sink that says stop stops the read
		final Connector.RowSink stopper = new Connector.RowSink()
		{
			@Override
			public boolean accept(final JsonNode pRow)
			{
				return false;
			}
		};
		filter = new WatermarkFilter(stopper, t, "LAST_MODIFIED", "2026-01-02T10:00", false, null);
		check(!filter.accept(Json.parse("{\"LAST_MODIFIED\":\"2026-01-03T00:00\"}")), "a stop from the batching sink reaches the source");
		check(filter.accept(Json.parse("{\"LAST_MODIFIED\":\"2026-01-01T00:00\"}")), "a skipped row asks the source to keep going");

		// numbers: numeric, not textual, comparison
		out = new Collector();
		filter = new WatermarkFilter(out, Watermark.Kind.NUMBER, "id", "9", false, null);
		filter.accept(Json.parse("{\"id\":10}"));
		filter.accept(Json.parse("{\"id\":9}"));
		filter.accept(Json.parse("{\"id\":\"8\"}"));
		check(out.rows.size() == 1 && filter.getSkipped() == 2, "number filter: 10 passes, 9 and 8 are skipped");
		check("10".equals(filter.getMax()), "number max: " + filter.getMax());

		// strings
		out = new Collector();
		filter = new WatermarkFilter(out, Watermark.Kind.STRING, "ulid", "01B", false, null);
		filter.accept(Json.parse("{\"ulid\":\"01A\"}"));
		filter.accept(Json.parse("{\"ulid\":\"01C\"}"));
		check(out.rows.size() == 1 && "01C".equals(filter.getMax()), "string filter and max");

		// an invalid checkpoint value cannot filter: it reads everything rather than dropping rows on a wrong comparison
		out = new Collector();
		filter = new WatermarkFilter(out, t, "LAST_MODIFIED", "not a timestamp", false, null);
		feed(filter, "2026-01-01T10:00");
		check(out.rows.size() == 1 && filter.getCheckpoint() == null, "an unusable checkpoint filters nothing");

		System.out.println("PASS: watermark filter (client-side skip, boundary option, highest value, unreadable rows, stop)");
	}

	private static void feed(final WatermarkFilter pFilter, final String... pValues) throws Exception
	{
		for (final String value : pValues)
		{
			pFilter.accept(Json.parse("{\"LAST_MODIFIED\":\"" + value + "\"}"));
		}
	}

	private static final class Collector implements Connector.RowSink
	{
		final List<JsonNode> rows = new ArrayList<JsonNode>();

		@Override
		public boolean accept(final JsonNode pRow)
		{
			this.rows.add(pRow);
			return true;
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
