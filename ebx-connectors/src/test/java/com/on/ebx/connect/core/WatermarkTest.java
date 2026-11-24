package com.on.ebx.connect.core;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.SynchronizationStrategy;

/**
 * Volume lot V2-D: the pure rules of the feed watermark - which strategy is a row watermark, how values
 * are read from a row, compared per kind (timestamp, number, string), normalised, bound to JDBC, and when
 * a checkpoint advances. No EBX type is involved.
 */
public final class WatermarkTest
{
	private WatermarkTest() { }

	public static void main(final String[] args) throws Exception
	{
		kinds();
		timestamps();
		numbers();
		strings();
		floors();
		reading();
		advancing();
		sqlValues();
		System.out.println("PASS: feed watermark rules (kinds, comparison per type, normalisation, reading, advance rule, JDBC values)");
	}

	private static void kinds()
	{
		check(Watermark.Kind.of(SynchronizationStrategy.TIMESTAMP) == Watermark.Kind.TIMESTAMP, "TIMESTAMP strategy is a timestamp watermark");
		check(Watermark.Kind.of(SynchronizationStrategy.MONOTONIC_ID) == Watermark.Kind.NUMBER, "MONOTONIC_ID strategy is a number watermark");
		check(Watermark.Kind.of(SynchronizationStrategy.ORDERED_STRING) == Watermark.Kind.STRING, "ORDERED_STRING strategy is a string watermark");
		check(Watermark.Kind.of(SynchronizationStrategy.VENDOR_CURSOR) == null, "a vendor cursor is not a row watermark");
		check(Watermark.Kind.of(SynchronizationStrategy.ODATA_DELTA) == null, "an OData delta link is not a row watermark");
		check(Watermark.Kind.of(SynchronizationStrategy.SNAPSHOT) == null, "a snapshot is not a row watermark");
		check(Watermark.Kind.of(SynchronizationStrategy.FULL) == null, "FULL is not a row watermark");
		check(Watermark.Kind.of(null) == null, "no strategy is not a row watermark");
		for (final Watermark.Kind kind : Watermark.Kind.values())
		{
			check(Watermark.Kind.of(kind.strategy()) == kind, "kind and strategy round-trip: " + kind);
		}
		check(Watermark.kindNamed("timestamp") == Watermark.Kind.TIMESTAMP, "kind names are case-insensitive");
		check(Watermark.kindNamed("bogus") == null && Watermark.kindNamed(null) == null, "an unknown kind name is null");
	}

	private static void timestamps()
	{
		final Watermark.Kind t = Watermark.Kind.TIMESTAMP;
		// the exact shapes JdbcConnector.rowOf writes (LocalDateTime.toString(): seconds dropped when zero)
		check(Watermark.normalise(t, "2026-01-31T10:15") != null, "a local date-time without seconds is valid (LocalDateTime.toString shape)");
		check(Watermark.normalise(t, "2026-01-31T10:15:00.123456") != null, "microseconds are valid");
		check(Watermark.normalise(t, "2026-01-31 10:15:00") != null, "a space separator is valid");
		check(Watermark.normalise(t, "2026-01-31") != null, "a bare date is valid");
		check(Watermark.normalise(t, "2026-01-31T10:15:00Z") != null, "a UTC suffix is valid");
		check(Watermark.normalise(t, "2026-01-31T10:15:00+01:00") != null, "an offset is valid");
		check(Watermark.normalise(t, "  2026-01-31T10:15  ").equals("2026-01-31T10:15"), "a timestamp is kept as written, trimmed");
		check(Watermark.normalise(t, "yesterday") == null, "text is not a timestamp");
		check(Watermark.normalise(t, "2026-13-45") == null, "an impossible date is not a timestamp");
		check(Watermark.normalise(t, "12345") == null, "a bare number is not a timestamp (use MONOTONIC_ID)");
		check(Watermark.normalise(t, "") == null && Watermark.normalise(t, null) == null, "empty is not a value");

		check(Watermark.compare(t, "2026-01-31T10:15", "2026-01-31T10:15:00") == 0, "omitted zero seconds compare equal");
		check(Watermark.compare(t, "2026-01-31T10:15:01", "2026-01-31T10:15") > 0, "one second later is greater");
		check(Watermark.compare(t, "2026-01-31T10:15:00.000001", "2026-01-31T10:15") > 0, "a microsecond later is greater");
		check(Watermark.compare(t, "2026-02-01", "2026-01-31T23:59:59") > 0, "a later date beats an earlier day's last second");
		check(Watermark.compare(t, "2026-01-31", "2026-01-31T00:00:00") == 0, "a bare date is midnight");
		check(Watermark.compare(t, "2026-01-31 10:15:00", "2026-01-31T10:15:00") == 0, "space and T separators compare equal");
		check(Watermark.compare(t, "2026-01-31T11:00:00+01:00", "2026-01-31T10:00:00Z") == 0, "offsets are honoured: 11:00+01:00 is 10:00Z");
		check(Watermark.compare(t, "2026-01-31T10:30:00+01:00", "2026-01-31T10:00:00Z") < 0, "09:30Z is before 10:00Z");
		check(Watermark.compare(t, "2025-12-31T23:59:59", "2026-01-01T00:00") < 0, "year boundary");
		expectRefused(t, "nonsense", "2026-01-01");
	}

	private static void numbers()
	{
		final Watermark.Kind n = Watermark.Kind.NUMBER;
		check(Watermark.compare(n, "9", "10") < 0, "numbers compare numerically, not as text: 9 < 10");
		check(Watermark.compare(n, "10.0", "10") == 0, "10.0 equals 10");
		check(Watermark.compare(n, "1E3", "1000") == 0, "1E3 equals 1000 (a JSON double)");
		check(Watermark.compare(n, "-5", "-10") > 0, "negative numbers");
		check(Watermark.normalise(n, "1E3").equals("1000"), "a number is stored as a plain decimal: " + Watermark.normalise(n, "1E3"));
		check(Watermark.normalise(n, " 42 ").equals("42"), "a number is trimmed");
		check(Watermark.normalise(n, "12345678901234567890").equals("12345678901234567890"), "beyond long range is kept exactly");
		check(Watermark.normalise(n, "abc") == null && Watermark.normalise(n, "1,5") == null, "text is not a number");
		check(Watermark.compare(n, "12345678901234567891", "12345678901234567890") > 0, "big numbers keep their precision");
		expectRefused(n, "x", "1");
	}

	private static void strings()
	{
		final Watermark.Kind s = Watermark.Kind.STRING;
		check(Watermark.compare(s, "b", "a") > 0, "plain string order");
		check(Watermark.compare(s, "10", "9") < 0, "string order is NOT numeric: '10' < '9'");
		check(Watermark.compare(s, "01HZ", "01HY") > 0, "ULID-like ids");
		check(Watermark.compare(s, "A", "a") < 0, "code-unit order: upper case before lower case");
		check("0012".equals(Watermark.normalise(s, "0012")), "a string is kept exactly, leading zeros included");
		check(Watermark.normalise(s, "") == null && Watermark.normalise(s, null) == null, "empty is not a value");
		final StringBuilder tooLong = new StringBuilder();
		for (int i = 0; i <= Watermark.MAX_LENGTH; i++) tooLong.append('x');
		check(Watermark.normalise(s, tooLong.toString()) == null, "a value longer than " + Watermark.MAX_LENGTH + " is refused, never truncated");
	}

	private static void floors()
	{
		// the floor is what ${watermark} is on a first run: it must be a valid value below any real one
		for (final Watermark.Kind kind : Watermark.Kind.values())
		{
			check(Watermark.normalise(kind, kind.floor()) != null, "the floor of " + kind + " is a valid value: '" + kind.floor() + "'");
		}
		check(Watermark.compare(Watermark.Kind.TIMESTAMP, Watermark.Kind.TIMESTAMP.floor(), "1970-01-01T00:00:00") < 0, "timestamp floor is before the epoch");
		check(Watermark.compare(Watermark.Kind.TIMESTAMP, Watermark.Kind.TIMESTAMP.floor(), "1900-01-01T00:00:01") < 0, "timestamp floor is before its first second");
		check(Watermark.compare(Watermark.Kind.NUMBER, Watermark.Kind.NUMBER.floor(), "-9223372036854775807") < 0, "number floor is below every long");
		check(Watermark.compare(Watermark.Kind.STRING, Watermark.Kind.STRING.floor(), "0") < 0, "string floor sorts before digits");
		check(Watermark.compare(Watermark.Kind.STRING, Watermark.Kind.STRING.floor(), "A") < 0, "string floor sorts before letters");
		check(!Watermark.Kind.STRING.floor().isEmpty(), "the string floor is not empty (Oracle binds '' as NULL and a comparison with NULL reads nothing)");
	}

	private static void reading() throws Exception
	{
		final JsonNode row = Json.parse("{\"LAST_MODIFIED\":\"2026-01-31T10:15\",\"id\":7,\"nested\":{\"at\":\"2026-02-01\"},"
			+ "\"nullish\":null,\"empty\":\"\",\"obj\":{\"a\":1},\"list\":[1,2],\"price\":12.50}");
		check("2026-01-31T10:15".equals(Watermark.read(row, "LAST_MODIFIED")), "reads a top-level field");
		check("7".equals(Watermark.read(row, "id")), "reads a number as text");
		check("2026-02-01".equals(Watermark.read(row, "nested.at")), "reads a dotted path");
		check("2026-01-31T10:15".equals(Watermark.read(row, "last_modified")), "a single name is found ignoring case (JDBC upper-cases labels)");
		check(Watermark.read(row, "nested.AT") == null, "case-insensitive fallback is for a single name only");
		check(Watermark.read(row, "nullish") == null, "a null is not a value");
		check(Watermark.read(row, "empty") == null, "an empty string is not a value");
		check(Watermark.read(row, "obj") == null && Watermark.read(row, "list") == null, "an object or array is not a value");
		check(Watermark.read(row, "missing") == null, "an absent field is not a value");
		check(Watermark.read(row, "") == null && Watermark.read(row, null) == null && Watermark.read(null, "id") == null, "no path or no row is not a value");
		check("12.5".equals(Watermark.normalise(Watermark.Kind.NUMBER, Watermark.read(row, "price"))) || "12.50".equals(Watermark.normalise(Watermark.Kind.NUMBER, Watermark.read(row, "price"))),
			"a decimal number reads back as a number");
	}

	private static void advancing()
	{
		final Watermark.Kind t = Watermark.Kind.TIMESTAMP;
		check("2026-02-01T00:00:00".equals(Watermark.next(t, null, "2026-02-01T00:00:00", false)), "nothing stored: the highest value seen becomes the checkpoint");
		check("2026-02-01T00:00:00".equals(Watermark.next(t, "2026-01-01T00:00:00", "2026-02-01T00:00:00", false)), "a higher value advances the checkpoint");
		check(Watermark.next(t, "2026-03-01T00:00:00", "2026-02-01T00:00:00", false) == null, "a lower value never moves the checkpoint back");
		check(Watermark.next(t, "2026-02-01T00:00", "2026-02-01T00:00:00", false) == null, "an equal value (seconds omitted) leaves it unchanged");
		check(Watermark.next(t, "2026-01-01T00:00:00", null, false) == null, "an empty read leaves the checkpoint alone");
		check(Watermark.next(t, "2026-01-01T00:00:00", "garbage", false) == null, "an invalid highest value leaves the checkpoint alone");
		check("2026-02-01T00:00:00".equals(Watermark.next(t, "not a timestamp", "2026-02-01T00:00:00", false)), "an unusable stored value is replaced");

		// full refresh RESETS: the highest value seen wins even when it is below the stored one
		check("2026-01-15T00:00:00".equals(Watermark.next(t, "2026-03-01T00:00:00", "2026-01-15T00:00:00", true)), "a full refresh resets the checkpoint to what the source holds now");
		check(Watermark.next(t, "2026-03-01T00:00:00", null, true) == null, "a full refresh over an empty source leaves the checkpoint alone");

		final Watermark.Kind n = Watermark.Kind.NUMBER;
		check("100".equals(Watermark.next(n, "99", "100", false)), "numeric advance: 99 -> 100 (not text order)");
		check(Watermark.next(n, "100", "99", false) == null, "numeric: 99 does not move 100 back");
		final Watermark.Kind s = Watermark.Kind.STRING;
		check("b".equals(Watermark.next(s, "a", "b", false)), "string advance");
		check(Watermark.next(s, "9", "10", false) == null, "string order: '10' is below '9', it does not advance");
	}

	private static void sqlValues()
	{
		final Object ts = Watermark.sqlValue(Watermark.Kind.TIMESTAMP, "2026-01-31T10:15:30.5");
		check(ts instanceof Timestamp, "a timestamp binds as java.sql.Timestamp: " + ts.getClass());
		check(((Timestamp) ts).toLocalDateTime().equals(LocalDateTime.of(2026, 1, 31, 10, 15, 30, 500000000)), "the local value round-trips");
		final Object noSeconds = Watermark.sqlValue(Watermark.Kind.TIMESTAMP, "2026-01-31T10:15");
		check(((Timestamp) noSeconds).toLocalDateTime().equals(LocalDateTime.of(2026, 1, 31, 10, 15)), "a value without seconds binds");
		final Object date = Watermark.sqlValue(Watermark.Kind.TIMESTAMP, "2026-01-31");
		check(((Timestamp) date).toLocalDateTime().equals(LocalDateTime.of(2026, 1, 31, 0, 0)), "a bare date binds as midnight");
		final Object zoned = Watermark.sqlValue(Watermark.Kind.TIMESTAMP, "2026-01-31T11:00:00+01:00");
		check(((Timestamp) zoned).toInstant().equals(java.time.Instant.parse("2026-01-31T10:00:00Z")), "an offset value binds as the absolute instant");

		check(Long.valueOf(42).equals(Watermark.sqlValue(Watermark.Kind.NUMBER, "42")), "an integral number binds as Long");
		check(new BigDecimal("12.5").equals(Watermark.sqlValue(Watermark.Kind.NUMBER, "12.5")), "a decimal number binds as BigDecimal");
		check(new BigDecimal("12345678901234567890").equals(Watermark.sqlValue(Watermark.Kind.NUMBER, "12345678901234567890")), "beyond long range binds as BigDecimal");
		check(Long.valueOf(Long.MIN_VALUE).equals(Watermark.sqlValue(Watermark.Kind.NUMBER, Watermark.Kind.NUMBER.floor())), "the number floor binds as Long.MIN_VALUE");

		check("0012".equals(Watermark.sqlValue(Watermark.Kind.STRING, "0012")), "a string stays a string: '0012' is not the number 12");
		expectSqlRefused(Watermark.Kind.NUMBER, "abc");
		expectSqlRefused(Watermark.Kind.TIMESTAMP, "yesterday");
	}

	private static void expectRefused(final Watermark.Kind pKind, final String pBad, final String pGood)
	{
		try
		{
			Watermark.compare(pKind, pBad, pGood);
			throw new AssertionError("comparing an invalid " + pKind + " value was accepted: " + pBad);
		}
		catch (final IllegalArgumentException expected)
		{
			check(expected.getMessage().contains(pBad), "the refusal names the value");
		}
	}

	private static void expectSqlRefused(final Watermark.Kind pKind, final String pBad)
	{
		try
		{
			Watermark.sqlValue(pKind, pBad);
			throw new AssertionError("binding an invalid " + pKind + " value was accepted: " + pBad);
		}
		catch (final IllegalArgumentException expected)
		{
			// refused: never bound as text
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
