package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Volume lot 7a: {@link Connector#replay} is the pure engine behind {@code Connector.readRows}'s
 * default implementation AND {@code ConnectorService.importDocument}'s "document already in hand"
 * path - both narrow a document with an {@code itemsPath}, stream elements to a {@link Connector.RowSink}
 * up to a max, and report truncation/availability exactly like {@code ConnectorService.truncationOf}
 * used to from a fully materialised array. No EBX type, no connector, no network: a fake sink is
 * enough to exercise the streaming contract a real connector's cursor (JdbcConnector) or a real batch
 * feed (ConnectorService.FeedSink) relies on.
 */
public final class ConnectorRowStreamTest
{
	public static void main(final String[] args) throws Exception
	{
		// itemsPath narrows to a nested array; every element is delivered, in order
		final ObjectNode doc = obj();
		final ArrayNode rows = doc.putArray("rows");
		rows.add(row("a")).add(row("b")).add(row("c"));
		final Collector all = new Collector();
		Connector.RowStreamResult result = Connector.replay(doc, "rows", 10, all);
		check(all.keys().equals(java.util.Arrays.asList("a", "b", "c")), "every element of the narrowed array is delivered, in order: " + all.keys());
		check(result.count == 3, "count is the number delivered: " + result.count);
		check(!result.truncated, "nothing was cut: not truncated");
		check(result.available == -1, "no overflow, no declared total: available is unknown (-1)");
		check(result.document == doc, "replay reports the document it was given back, for catalog-scan bookkeeping");

		// blank itemsPath: the document itself is the array (or a single item, tested below)
		final Collector blankCollector = new Collector();
		final ArrayNode bareArrayDoc = JsonNodeFactory.instance.arrayNode();
		bareArrayDoc.add(row("x")).add(row("y"));
		result = Connector.replay(bareArrayDoc, "", 10, blankCollector);
		check(blankCollector.keys().equals(java.util.Arrays.asList("x", "y")), "blank itemsPath narrows to the document itself when it is directly an array");
		check(!result.truncated && result.available == -1, "a small bare array is never truncated");

		// a non-array, non-null document at the path is delivered once
		final ObjectNode single = row("solo");
		final Collector soloCollector = new Collector();
		result = Connector.replay(single, "", 10, soloCollector);
		check(soloCollector.keys().equals(java.util.Collections.singletonList("solo")), "a non-array node at the path is delivered as one item");
		check(result.count == 1 && result.available == -1, "a single delivered item has no separate 'available' concept");

		// null document: nothing delivered, not truncated, nothing thrown
		result = Connector.replay(null, "rows", 10, new Collector());
		check(result.count == 0 && !result.truncated && result.available == -1, "a null document delivers nothing and is never truncated");

		// max cuts delivery, but counting for 'available' continues over the already-materialised array
		// (the old 'counted overflow' case truncationOf used to detect from importDocument's own loop)
		final ObjectNode bigDoc = obj();
		final ArrayNode bigRows = bigDoc.putArray("rows");
		for (int i = 0; i < 5; i++) bigRows.add(row("r" + i));
		final Collector capped = new Collector();
		result = Connector.replay(bigDoc, "rows", 3, capped);
		check(capped.keys().equals(java.util.Arrays.asList("r0", "r1", "r2")), "only the first max items are delivered to the sink: " + capped.keys());
		check(result.count == 3, "count reflects what was actually delivered, not the array size");
		check(result.truncated, "delivering fewer than the array held is truncation");
		check(result.available == 5, "the real total is known for free: the array was already fully in hand");

		// a connector that pre-truncated its own array (JdbcConnector's boundedQuery, a file feed's read)
		// declares truncated=true on the document; when it also knows the real total (a file), available
		// is exact; when it does not (JDBC, a probe-row trick with no full scan), available stays unknown
		final ObjectNode declaredNoTotal = obj();
		final ArrayNode declaredRows = declaredNoTotal.putArray("rows");
		declaredRows.add(row("only")); // already cut to max=1 by the connector itself
		declaredNoTotal.put("truncated", true);
		final Collector declaredCollector = new Collector();
		result = Connector.replay(declaredNoTotal, "rows", 1, declaredCollector);
		check(result.truncated, "the document's own declared truncation is honoured even when the counted array never overflowed max");
		check(result.available == -1, "no 'available' field on the document: the total stays genuinely unknown");

		final ObjectNode declaredWithTotal = obj();
		final ArrayNode declaredRows2 = declaredWithTotal.putArray("rows");
		declaredRows2.add(row("only"));
		declaredWithTotal.put("truncated", true);
		declaredWithTotal.put("available", 3300000);
		result = Connector.replay(declaredWithTotal, "rows", 1, new Collector());
		check(result.truncated && result.available == 3300000, "a declared 'available' is surfaced exactly: " + result.available);

		// a sink that stops early (a batch/circuit-breaker/cancellation decision, never a max cutoff -
		// the max cutoff is enforced by replay itself, before the sink ever sees more than max items)
		// is reported as an ordinary read of however many items were delivered - never "truncated" for
		// that reason, since the caller already knows why it stopped and reports that separately.
		final ObjectNode stopDoc = obj();
		final ArrayNode stopRows = stopDoc.putArray("rows");
		for (int i = 0; i < 10; i++) stopRows.add(row("s" + i));
		final StoppingSink stopping = new StoppingSink(2); // stop after accepting 2 items
		result = Connector.replay(stopDoc, "rows", 10, stopping);
		check(stopping.delivered.size() == 2, "the sink stopped after 2, and never saw a 3rd: " + stopping.delivered);
		check(result.count == 2, "count reflects the early stop: " + result.count);
		check(!result.truncated, "an external stop is not the same signal as 'the source held more than max'");

		System.out.println("ConnectorRowStreamTest: PASS");
	}

	/** Collects every accepted row's "id" field, in delivery order. */
	private static final class Collector implements Connector.RowSink
	{
		private final List<String> ids = new ArrayList<String>();

		@Override
		public boolean accept(final JsonNode row)
		{
			this.ids.add(row.path("id").asText());
			return true;
		}

		List<String> keys()
		{
			return this.ids;
		}
	}

	/** Stops after accepting {@code max} rows - the same contract FeedSink relies on for a breaker/cancellation stop. */
	private static final class StoppingSink implements Connector.RowSink
	{
		private final int max;
		final List<String> delivered = new ArrayList<String>();

		StoppingSink(final int pMax)
		{
			this.max = pMax;
		}

		@Override
		public boolean accept(final JsonNode row)
		{
			this.delivered.add(row.path("id").asText());
			return this.delivered.size() < this.max;
		}
	}

	private static ObjectNode obj()
	{
		return JsonNodeFactory.instance.objectNode();
	}

	private static ObjectNode row(final String id)
	{
		final ObjectNode row = obj();
		row.put("id", id);
		return row;
	}

	private static void check(final boolean condition, final String what)
	{
		if (!condition)
		{
			throw new AssertionError(what);
		}
	}
}
