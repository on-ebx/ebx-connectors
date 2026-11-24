package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.ArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * Offline check of {@link SnowflakeConnector#statementBody}, the pure builder of the
 * {@code /api/v2/statements} request body (no HTTP call): {@code parameters.MULTI_STATEMENT_COUNT}
 * must be serialized as a string, per the Snowflake SQL API reference (POST /api/v2/statements,
 * {@code parameters} object — every entry is a session-parameter value, sent as text). Also covers the
 * two partial-read decisions fixed 2026-09-28: {@link SnowflakeConnector#stillRunning} (a statement still
 * at HTTP 202 after the read-timeout poll loop must never be mistaken for success) and
 * {@link SnowflakeConnector#appendRows} (whether a page of data held more rows than the caller's {@code max}
 * let through) — both pure, no HTTP call.
 */
public final class SnowflakeConnectorTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setProperty("warehouse", "WH");
		config.setProperty("database", "DB");
		config.setProperty("schema", "SCHEMA");

		final ObjectNode body = SnowflakeConnector.statementBody(config, "BEGIN;\nDELETE FROM T;\nCOMMIT", new LinkedHashMap<String, String>(), 3);
		final JsonNode multiStatementCount = body.get("parameters").get("MULTI_STATEMENT_COUNT");
		check(multiStatementCount != null, "MULTI_STATEMENT_COUNT present when pMultiStatementCount > 0");
		check(multiStatementCount.isTextual(), "MULTI_STATEMENT_COUNT must be a JSON string, not a number: " + multiStatementCount);
		check("3".equals(multiStatementCount.asText()), "MULTI_STATEMENT_COUNT carries the count as text: " + multiStatementCount.asText());

		final ObjectNode singleStatement = SnowflakeConnector.statementBody(config, "SELECT 1", null, 0);
		check(!singleStatement.get("parameters").has("MULTI_STATEMENT_COUNT"), "MULTI_STATEMENT_COUNT omitted for a single-statement call");

		final Map<String, String> bindings = new LinkedHashMap<String, String>();
		bindings.put("1", "acme");
		final ObjectNode withBindings = SnowflakeConnector.statementBody(config, "SELECT ?", bindings, 0);
		check("acme".equals(withBindings.get("bindings").get("1").get("value").asText()), "bindings still built correctly");

		// A statement still executing keeps answering HTTP 202, which sits inside HttpJsonClient.Response's
		// 2xx "success" range: before 2026-09-28 that let a read-timed-out poll fall through to collect() and
		// come back looking like success. stillRunning is the one-line predicate the poll loop and the
		// post-loop guard both use.
		check(SnowflakeConnector.stillRunning(202), "202 must be reported as still running");
		check(!SnowflakeConnector.stillRunning(200), "200 must not be reported as still running");
		check(!SnowflakeConnector.stillRunning(500), "500 must not be reported as still running");

		// appendRows: whether a page of data held more rows than fit under max, without a second query.
		final List<String> names = java.util.Collections.singletonList("ID");
		final List<String> types = java.util.Collections.singletonList("fixed");
		final List<Integer> scales = java.util.Collections.singletonList(0);

		final ArrayNode underRows = Json.mapper().createArrayNode();
		final boolean underTruncated = SnowflakeConnector.appendRows(underRows, twoRowPage(), names, types, scales, 5);
		check(!underTruncated, "a page under max must not report truncation");
		check(underRows.size() == 2, "every row of a page under max must be appended");

		final ArrayNode overRows = Json.mapper().createArrayNode();
		final boolean overTruncated = SnowflakeConnector.appendRows(overRows, twoRowPage(), names, types, scales, 1);
		check(overTruncated, "a page holding more rows than max must report truncation");
		check(overRows.size() == 1, "only rows up to max may be appended");

		final ArrayNode alreadyFullRows = Json.mapper().createArrayNode();
		alreadyFullRows.addObject().put("ID", 0);
		final boolean fullTruncated = SnowflakeConnector.appendRows(alreadyFullRows, twoRowPage(), names, types, scales, 1);
		check(fullTruncated, "a page arriving after rows already reached max must report truncation");
		check(alreadyFullRows.size() == 1, "no more rows may be appended once max is already reached");

		// Volume lot 7b: streamRows - one partition's rows fed to a sink one at a time, never held all
		// together, across several simulated "partition fetch" calls sharing one StreamState (exactly
		// how streamCollect drives it, minus the HTTP fetch itself - not verified live, same caveat as
		// JdbcConnector's own streaming; see docs/REFERENCE.md).
		final SnowflakeConnector.StreamState state = new SnowflakeConnector.StreamState();
		state.names.add("ID");
		state.types.add("fixed");
		state.scales.add(0);

		final Collector collector = new Collector();
		final boolean firstPartitionOverflowed = SnowflakeConnector.streamRows(twoRowPage(), 10, collector, state);
		check(!firstPartitionOverflowed, "first partition (2 rows) fits comfortably under max=10");
		check(state.delivered == 2, "both rows of the first partition delivered: " + state.delivered);
		final boolean secondPartitionOverflowed = SnowflakeConnector.streamRows(twoRowPage(), 10, collector, state);
		check(!secondPartitionOverflowed, "second partition also fits under max=10");
		check(state.delivered == 4, "state.delivered accumulates ACROSS partitions, not reset per call: " + state.delivered);
		check(collector.values.equals(java.util.Arrays.asList("1", "2", "1", "2")), "rows delivered in order, across both partitions: " + collector.values);

		// max cuts delivery mid-partition and is reported as this partition holding more than fit
		final SnowflakeConnector.StreamState capped = new SnowflakeConnector.StreamState();
		capped.names.add("ID");
		capped.types.add("fixed");
		capped.scales.add(0);
		final Collector cappedCollector = new Collector();
		final boolean overflowed = SnowflakeConnector.streamRows(twoRowPage(), 1, cappedCollector, capped);
		check(overflowed, "a partition holding more rows than max lets through must report it");
		check(capped.delivered == 1, "only rows up to max are delivered: " + capped.delivered);
		check(cappedCollector.values.equals(java.util.Collections.singletonList("1")), "only the first row reached the sink: " + cappedCollector.values);

		// a sink that stops mid-partition (a batch/circuit-breaker/cancellation decision) is never
		// reported as this partition "overflowing" - the caller already knows why it stopped.
		final SnowflakeConnector.StreamState stopped = new SnowflakeConnector.StreamState();
		stopped.names.add("ID");
		stopped.types.add("fixed");
		stopped.scales.add(0);
		final StoppingSink stopping = new StoppingSink(1);
		final boolean stoppedOverflow = SnowflakeConnector.streamRows(twoRowPage(), 10, stopping, stopped);
		check(!stoppedOverflow, "a sink-initiated stop is not reported as this partition overflowing max");
		check(stopped.sinkStopped, "StreamState remembers the sink asked to stop");
		check(stopped.delivered == 1, "the sink stopped after 1 row, and never saw the 2nd: " + stopped.delivered);

		// an already-stopped state is a no-op on the next call (streamCollect relies on this to skip
		// fetching further partitions once the sink stopped on an earlier one)
		final boolean noopOverflow = SnowflakeConnector.streamRows(twoRowPage(), 10, stopping, stopped);
		check(!noopOverflow, "no further rows are processed once the state is already sinkStopped");
		check(stopped.delivered == 1, "delivered count untouched by the no-op call: " + stopped.delivered);

		System.out.println("PASS: SnowflakeConnector statement body, still-running detection, page truncation and streamed partition delivery");
	}

	/** Collects the "ID" value of every row a sink accepts, in delivery order. */
	private static final class Collector implements Connector.RowSink
	{
		final List<String> values = new ArrayList<String>();

		@Override
		public boolean accept(final JsonNode pRow)
		{
			this.values.add(pRow.path("ID").asText());
			return true;
		}
	}

	/** Stops after accepting {@code max} rows - the same contract ConnectorService.FeedSink relies on for a breaker/cancellation stop. */
	private static final class StoppingSink implements Connector.RowSink
	{
		private final int max;
		private int accepted;

		StoppingSink(final int pMax)
		{
			this.max = pMax;
		}

		@Override
		public boolean accept(final JsonNode pRow)
		{
			this.accepted++;
			return this.accepted < this.max;
		}
	}

	private static ArrayNode twoRowPage()
	{
		final ArrayNode data = Json.mapper().createArrayNode();
		data.addArray().add("1");
		data.addArray().add("2");
		return data;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
