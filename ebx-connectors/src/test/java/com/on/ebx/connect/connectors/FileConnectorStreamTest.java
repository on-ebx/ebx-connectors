package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Volume lot 7b: {@code AbstractFileConnector.readRows} - a CSV/NDJSON {@code read} streams rows to
 * a {@link Connector.RowSink} one at a time (via {@link FileFormats#streamCsv}/{@link
 * FileFormats#streamNdjson}) instead of going through the default (execute + replay) implementation
 * that materialises {@code {rows:[...]}} first; a JSON/XML {@code read} still falls back to that
 * default, per the task's own "JSON arrays and XML may stay materialised". Every streamed scenario is
 * cross-checked against the non-streamed {@code execute("read")} answer on the same file, so the two
 * paths can never silently disagree on what rows a caller sees.
 */
public final class FileConnectorStreamTest
{
	private FileConnectorStreamTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		csvStreamsAndMatchesNonStreamedRead();
		ndjsonStreamsAndMatchesNonStreamedRead();
		jsonFallsBackToDefaultMaterialisedPath();
		maxTruncatesAndSinkStopIsNotTruncation();
		absoluteByteLimitStillEnforcedBeforeStreaming();
		System.out.println("PASS: AbstractFileConnector.readRows (CSV/NDJSON streamed, JSON/XML default, max/truncation/limits honoured)");
	}

	private static void csvStreamsAndMatchesNonStreamedRead() throws Exception
	{
		final FakeFileConnector connector = new FakeFileConnector();
		connector.configure(new ConnectorConfig());
		connector.put("data.csv", "id;name\r\n1;Alpha\r\n2;Beta\r\n3;Gamma\r\n");

		final Collector sink = new Collector();
		final Connector.RowStreamResult result = connector.readRows(null, "read", inputs("data.csv"), "rows", 100, sink);
		check(sink.rows.size() == 3, "3 rows delivered: " + sink.rows.size());
		check(sink.rows.get(0).path("id").asText().equals("1") && sink.rows.get(0).path("name").asText().equals("Alpha"), "row shape matches header pairing: " + sink.rows.get(0));
		check(result.count == 3 && !result.truncated && result.available == 3, "result summary: count=" + result.count + " truncated=" + result.truncated + " available=" + result.available);
		check(result.document == null, "a genuinely streamed CSV read never materialises a document");

		// cross-check against the non-streamed execute("read") answer on the exact same file
		final JsonNode nonStreamed = connector.execute(null, "read", inputs("data.csv"), null);
		check(nonStreamed.path("count").asInt() == 3, "non-streamed count agrees");
		check(nonStreamed.path("rows").get(1).path("name").asText().equals(sink.rows.get(1).path("name").asText()), "row content agrees between the two paths");
	}

	private static void ndjsonStreamsAndMatchesNonStreamedRead() throws Exception
	{
		final FakeFileConnector connector = new FakeFileConnector();
		connector.configure(new ConnectorConfig());
		connector.put("data.ndjson", "{\"id\":1,\"name\":\"Alpha\"}\n{\"id\":2,\"name\":\"Beta\"}\n\n{\"id\":3,\"name\":\"Gamma\"}\n");

		final Collector sink = new Collector();
		final Connector.RowStreamResult result = connector.readRows(null, "read", inputs("data.ndjson"), "rows", 100, sink);
		check(sink.rows.size() == 3, "blank lines skipped, 3 real rows delivered: " + sink.rows.size());
		check(sink.rows.get(2).path("name").asText().equals("Gamma"), "row order preserved: " + sink.rows.get(2));
		check(result.count == 3 && !result.truncated && result.available == 3, "result summary: " + result.count + "/" + result.truncated + "/" + result.available);

		final JsonNode nonStreamed = connector.execute(null, "read", inputs("data.ndjson"), null);
		check(nonStreamed.path("count").asInt() == 3, "non-streamed count agrees for ndjson too");
	}

	private static void jsonFallsBackToDefaultMaterialisedPath() throws Exception
	{
		final FakeFileConnector connector = new FakeFileConnector();
		connector.configure(new ConnectorConfig());
		connector.put("data.json", "[{\"id\":1},{\"id\":2}]");

		final Collector sink = new Collector();
		final Connector.RowStreamResult result = connector.readRows(null, "read", inputs("data.json"), "rows", 100, sink);
		check(sink.rows.size() == 2, "JSON still delivers every row through the default path: " + sink.rows.size());
		check(result.document != null, "the default (materialised) path carries the raw document, unlike the streamed CSV/NDJSON one");
		check(result.count == 2 && !result.truncated, "same counting contract as the streamed formats");
	}

	private static void maxTruncatesAndSinkStopIsNotTruncation() throws Exception
	{
		final FakeFileConnector connector = new FakeFileConnector();
		connector.configure(new ConnectorConfig());
		final StringBuilder csv = new StringBuilder("id;name\r\n");
		for (int i = 1; i <= 10; i++) csv.append(i).append(";row").append(i).append("\r\n");
		connector.put("many.csv", csv.toString());

		// max cuts delivery and is reported as truncated, with the exact available (CSV always knows it -
		// every record is already walked to find boundaries, same trade Csv.parse has always made).
		final Collector capped = new Collector();
		final Connector.RowStreamResult cappedResult = connector.readRows(null, "read", inputs("many.csv"), "rows", 3, capped);
		check(capped.rows.size() == 3, "only max rows delivered: " + capped.rows.size());
		check(cappedResult.truncated && cappedResult.available == 10, "max cutoff is truncation with an exact available: " + cappedResult.available);

		// a sink-initiated stop (batch/circuit-breaker/cancellation) is never reported as truncation -
		// the caller already knows why it stopped and reports that separately.
		final StoppingCollector stopping = new StoppingCollector(4);
		final Connector.RowStreamResult stoppedResult = connector.readRows(null, "read", inputs("many.csv"), "rows", 100, stopping);
		check(stopping.rows.size() == 4, "the sink stopped after 4, never saw a 5th: " + stopping.rows.size());
		check(!stoppedResult.truncated, "an external stop is not the same signal as 'the source held more than max'");
		check(stoppedResult.available == -1, "available is genuinely unknown once the walk stopped early");
	}

	private static void absoluteByteLimitStillEnforcedBeforeStreaming() throws Exception
	{
		final FakeFileConnector connector = new FakeFileConnector();
		connector.configure(new ConnectorConfig());
		connector.put("small.csv", "id;name\r\n1;Alpha\r\n");
		final Map<String, String> inputs = inputs("small.csv");
		inputs.put("max.bytes", "5"); // smaller than the file itself
		try
		{
			connector.readRows(null, "read", inputs, "rows", 100, new Collector());
			throw new AssertionError("a file over max.bytes must be rejected before a single row is streamed");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("max.bytes"), "the 100 MB/settings ceiling (FileFormats.checkSize) still gates the streamed path: " + expected.getMessage());
		}
	}

	private static Map<String, String> inputs(final String pPath)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("path", pPath);
		return inputs;
	}

	/** Collects every row a sink accepts, in delivery order. */
	private static class Collector implements Connector.RowSink
	{
		final List<JsonNode> rows = new ArrayList<JsonNode>();

		@Override
		public boolean accept(final JsonNode pRow)
		{
			this.rows.add(pRow);
			return true;
		}
	}

	/** Stops after accepting {@code max} rows - the same contract ConnectorService.FeedSink relies on for a breaker/cancellation stop. */
	private static final class StoppingCollector extends Collector
	{
		private final int max;

		StoppingCollector(final int pMax)
		{
			this.max = pMax;
		}

		@Override
		public boolean accept(final JsonNode pRow)
		{
			super.accept(pRow);
			return this.rows.size() < this.max;
		}
	}

	private static final class FakeFileConnector extends AbstractFileConnector
	{
		private final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();

		void put(final String pPath, final String pContent)
		{
			this.files.put(pPath, pContent.getBytes(StandardCharsets.UTF_8));
		}

		@Override public String getType() { return "fake-file-stream-test"; }
		@Override public String getLabel() { return "Fake file (streaming test only)"; }

		@Override
		protected byte[] readBytes(final String pPath) throws ConnectorException
		{
			return this.files.get(pPath);
		}

		@Override
		protected void writeBytes(final String pPath, final byte[] pContent, final String pContentType) throws ConnectorException
		{
			this.files.put(pPath, pContent);
		}

		@Override
		protected List<Entry> listEntries(final String pPrefix, final int pMax) throws ConnectorException
		{
			final List<Entry> entries = new ArrayList<Entry>();
			for (final String path : this.files.keySet())
			{
				if (entries.size() >= pMax) break;
				entries.add(new Entry(path, this.files.get(path).length, null));
			}
			return entries;
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
