package com.on.ebx.connect.connectors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.CatalogOperations;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * A timestamp read through JDBC must reach a mapping in a form ON EBX accepts as a dateTime: seconds always written.
 * {@code LocalDateTime.toString()} drops them when they are zero ({@code 2026-11-20T10:15}), and ON EBX then refuses the
 * value ("conversion error while handling input string '2026-11-20T10:15.000'"): every row whose timestamp falls on a
 * whole minute, midnight included, lost that field. Played on a real in-memory H2.
 */
public final class JdbcTimestampTextTest
{
	private static final String URL = "jdbc:h2:mem:timestamptext;DB_CLOSE_DELAY=-1";

	private JdbcTimestampTextTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		formatter();
		try (Connection setup = DriverManager.getConnection(URL); Statement statement = setup.createStatement())
		{
			statement.execute("CREATE TABLE EVENT (ID INT PRIMARY KEY, AT TIMESTAMP, AT_ZONE TIMESTAMP WITH TIME ZONE, ON_DAY DATE, CLOCK TIME)");
			statement.execute("INSERT INTO EVENT VALUES (1, TIMESTAMP '2026-11-20 10:15:00', TIMESTAMP WITH TIME ZONE '2026-11-20 10:15:00+01:00', DATE '2026-11-20', TIME '10:15:00')");
			statement.execute("INSERT INTO EVENT VALUES (2, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00:00', DATE '2026-01-01', TIME '00:00:00')");
			statement.execute("INSERT INTO EVENT VALUES (3, TIMESTAMP '2026-11-22 23:59:59', TIMESTAMP WITH TIME ZONE '2026-11-22 23:59:59-05:00', DATE '2026-11-22', TIME '23:59:59')");
			statement.execute("INSERT INTO EVENT VALUES (4, TIMESTAMP '2026-11-20 10:15:00.125', TIMESTAMP WITH TIME ZONE '2026-11-20 10:15:00.125+01:00', NULL, NULL)");
			statement.execute("INSERT INTO EVENT VALUES (5, NULL, NULL, NULL, NULL)");
		}
		final JdbcConnector connector = new JdbcConnector();
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("TS");
		config.setBaseUrl(URL);
		connector.configure(config);

		final JsonNode rows = connector.execute(null, "query", Collections.singletonMap("sql", "SELECT ID, AT, AT_ZONE, ON_DAY, CLOCK FROM EVENT ORDER BY ID"), null).path("rows");
		expect(rows.path(0).path("AT").asText(), "2026-11-20T10:15:00", "a whole minute keeps its seconds");
		expect(rows.path(1).path("AT").asText(), "2026-01-01T00:00:00", "midnight keeps its seconds");
		expect(rows.path(2).path("AT").asText(), "2026-11-22T23:59:59", "seconds are unchanged");
		expect(rows.path(3).path("AT").asText(), "2026-11-20T10:15:00.125", "a fraction is kept, after the seconds");
		check(rows.path(4).path("AT").isNull(), "an absent timestamp stays null");
		expect(rows.path(0).path("AT_ZONE").asText(), "2026-11-20T10:15:00+01:00", "a whole minute with an offset keeps its seconds");
		expect(rows.path(1).path("AT_ZONE").asText(), "2026-01-01T00:00:00Z", "midnight UTC keeps its seconds");
		expect(rows.path(2).path("AT_ZONE").asText(), "2026-11-22T23:59:59-05:00", "seconds with an offset are unchanged");
		expect(rows.path(3).path("AT_ZONE").asText(), "2026-11-20T10:15:00.125+01:00", "a fraction with an offset is kept");
		expect(rows.path(0).path("ON_DAY").asText(), "2026-11-20", "a date is a date");
		expect(rows.path(0).path("CLOCK").asText(), "10:15:00", "a time keeps its seconds");
		expect(rows.path(1).path("CLOCK").asText(), "00:00:00", "midnight as a time keeps its seconds");

		final List<JsonNode> streamed = new ArrayList<JsonNode>();
		connector.readRows(null, "query", Collections.singletonMap("sql", "SELECT ID, AT FROM EVENT ORDER BY ID"), "rows", 10, new Connector.RowSink()
		{
			public boolean accept(final JsonNode pRow)
			{
				streamed.add(pRow);
				return true;
			}
		});
		expect(streamed.get(0).path("AT").asText(), "2026-11-20T10:15:00", "a streamed whole minute keeps its seconds");
		expect(streamed.get(1).path("AT").asText(), "2026-01-01T00:00:00", "a streamed midnight keeps its seconds");
		System.out.println("PASS: a JDBC timestamp reaches a mapping with its seconds (whole minute, midnight, fraction, offset), on a real H2");
	}

	private static void formatter()
	{
		expect(CatalogOperations.dateTime(java.time.LocalDateTime.of(2026, 11, 20, 10, 15)), "2026-11-20T10:15:00", "formatter: whole minute");
		expect(CatalogOperations.dateTime(java.time.LocalDateTime.of(2026, 11, 20, 10, 15, 7)), "2026-11-20T10:15:07", "formatter: seconds");
		expect(CatalogOperations.dateTime(java.time.LocalDateTime.of(2026, 11, 20, 10, 15, 0, 125000000)), "2026-11-20T10:15:00.125", "formatter: fraction");
		expect(CatalogOperations.dateTime(java.time.OffsetDateTime.of(2026, 11, 20, 10, 15, 0, 0, java.time.ZoneOffset.ofHours(1))), "2026-11-20T10:15:00+01:00",
			"formatter: offset");
	}

	private static void expect(final String pActual, final String pExpected, final String pMessage)
	{
		if (!pExpected.equals(pActual)) throw new AssertionError(pMessage + ": expected " + pExpected + ", got " + pActual);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
