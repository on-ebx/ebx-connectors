package com.on.ebx.connect.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Volume lot 7b: {@link Csv#parseStream} - the streaming counterpart of {@link Csv#parse}, records
 * delivered to a {@link Csv.RecordSink} one at a time via a real {@link java.io.Reader} (a
 * {@link java.io.PushbackReader} of 1 character) instead of being collected into one
 * {@code List<List<String>>} holding the whole file. Every assertion here is cross-checked against
 * {@link Csv#parse}'s own output on the same text, so the two must never silently diverge.
 */
public final class CsvStreamTest
{
	public static void main(final String[] args) throws Exception
	{
		// basic delivery: header seen first, then every data record in order, matching parse()'s rows
		final String text = "id;name\r\n1;Alpha\r\n2;Beta\r\n3;Gamma\r\n";
		final Collector all = new Collector();
		Csv.StreamResult result = Csv.parseStream(new StringReader(text), ';', 10, all);
		check(all.header.equals(Arrays.asList("id", "name")), "header delivered before any record: " + all.header);
		check(all.records.equals(Arrays.asList(Arrays.asList("1", "Alpha"), Arrays.asList("2", "Beta"), Arrays.asList("3", "Gamma"))),
			"every data record delivered, in order: " + all.records);
		check(result.delivered == 3, "delivered count: " + result.delivered);
		check(result.available == 3, "available matches delivered when nothing was cut: " + result.available);
		check(!result.truncated, "nothing was cut: not truncated");
		final com.fasterxml.jackson.databind.node.ObjectNode parsed = Csv.parse(text, ';', 10);
		check(parsed.get("count").asInt() == result.delivered && parsed.get("available").asLong() == result.available
			&& parsed.get("truncated").asBoolean() == result.truncated, "cross-checked against Csv.parse's own top-level fields");

		// max cuts delivery, but available keeps counting - exactly like parse()'s own trade (already walking every record)
		final Collector capped = new Collector();
		result = Csv.parseStream(new StringReader(text), ';', 2, capped);
		check(capped.records.size() == 2, "only the first max records delivered: " + capped.records.size());
		check(result.delivered == 2 && result.available == 3 && result.truncated, "max cuts delivery, available stays exact: " + result.available);

		// quoted fields: embedded separator, embedded newline, doubled-quote escaping - same as parse()
		final String quoted = "id;note\r\n1;\"a;b\"\r\n2;\"line1\nline2\"\r\n3;\"say \"\"hi\"\"\"\r\n";
		final Collector quotedCollector = new Collector();
		Csv.parseStream(new StringReader(quoted), ';', 10, quotedCollector);
		check(quotedCollector.records.get(0).get(1).equals("a;b"), "embedded separator inside quotes: " + quotedCollector.records.get(0));
		check(quotedCollector.records.get(1).get(1).equals("line1\nline2"), "embedded newline inside quotes: " + quotedCollector.records.get(1));
		check(quotedCollector.records.get(2).get(1).equals("say \"hi\""), "doubled quote unescaped: " + quotedCollector.records.get(2));
		final com.fasterxml.jackson.databind.node.ObjectNode quotedParsed = Csv.parse(quoted, ';', 10);
		check(quotedParsed.get("rows").get(1).get("note").asText().equals("line1\nline2"), "parse() agrees on the embedded newline");

		// a blank line among data records is skipped, matching parse()
		final String withBlank = "id;name\r\n1;Alpha\r\n\r\n2;Beta\r\n";
		final Collector blankCollector = new Collector();
		result = Csv.parseStream(new StringReader(withBlank), ';', 10, blankCollector);
		check(blankCollector.records.size() == 2, "a blank line is skipped, not delivered as a record: " + blankCollector.records);
		check(result.available == 2, "a blank line does not count towards available either: " + result.available);

		// unterminated quote: the same error parse() throws, not silently truncated
		try
		{
			Csv.parseStream(new StringReader("id;name\r\n1;\"unterminated"), ';', 10, new Collector());
			throw new AssertionError("an unterminated quoted field must throw");
		}
		catch (final IllegalArgumentException expected)
		{
			check(expected.getMessage().contains("unterminated"), "error message names the problem: " + expected.getMessage());
		}

		// no trailing newline on the last record: still delivered (mirrors records()'s own trailing-field handling)
		final String noTrailingNewline = "id;name\r\n1;Alpha\r\n2;Beta";
		final Collector noTrailing = new Collector();
		result = Csv.parseStream(new StringReader(noTrailingNewline), ';', 10, noTrailing);
		check(noTrailing.records.size() == 2 && noTrailing.records.get(1).equals(Arrays.asList("2", "Beta")),
			"the last record survives with no trailing newline: " + noTrailing.records);
		check(result.delivered == 2 && result.available == 2, "counted correctly with no trailing newline");

		// a sink that stops mid-stream (a batch/circuit-breaker/cancellation decision) is reported
		// exactly like Connector.replay/JdbcConnector's own convention: not truncated, available unknown -
		// the caller already knows why it stopped and reports that separately.
		final String tenRows = header() + dataRows(10);
		final StoppingCollector stopping = new StoppingCollector(3);
		result = Csv.parseStream(new StringReader(tenRows), ';', 100, stopping);
		check(stopping.records.size() == 3, "the sink stopped after 3, and never saw a 4th: " + stopping.records.size());
		check(result.delivered == 3, "delivered reflects the early stop: " + result.delivered);
		check(!result.truncated, "an external stop is not the same signal as 'the source held more than max'");
		check(result.available == -1, "available is genuinely unknown once the walk stopped early: " + result.available);

		System.out.println("PASS: Csv.parseStream (header-first delivery, quoting, blanks, errors, max, sink-initiated stop)");
	}

	private static String header()
	{
		return "id;name\r\n";
	}

	private static String dataRows(final int n)
	{
		final StringBuilder sb = new StringBuilder();
		for (int i = 1; i <= n; i++) sb.append(i).append(";row").append(i).append("\r\n");
		return sb.toString();
	}

	/** Collects the header and every accepted record, in order. */
	private static class Collector implements Csv.RecordSink
	{
		List<String> header = new ArrayList<String>();
		final List<List<String>> records = new ArrayList<List<String>>();

		@Override
		public void header(final List<String> pHeader)
		{
			this.header = new ArrayList<String>(pHeader);
		}

		@Override
		public boolean accept(final List<String> pRecord)
		{
			this.records.add(new ArrayList<String>(pRecord));
			return true;
		}
	}

	/** Stops after accepting {@code max} records - the same contract FileFormats.streamCsv/ConnectorService.FeedSink relies on. */
	private static final class StoppingCollector extends Collector
	{
		private final int max;

		StoppingCollector(final int pMax)
		{
			this.max = pMax;
		}

		@Override
		public boolean accept(final List<String> pRecord)
		{
			super.accept(pRecord);
			return this.records.size() < this.max;
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
