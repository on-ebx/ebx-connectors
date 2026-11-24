package com.on.ebx.connect.core;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.SynchronizationStrategy;

/**
 * Pure rules of the feed watermark (volume lot V2-D): how a source-side change column is read from a
 * row, compared, normalised and advanced. No EBX type is involved, so everything here is tested offline
 * ({@code WatermarkTest}).
 * <p>
 * A watermark is a text value compared according to a {@link Kind}:
 * <ul>
 * <li>{@link Kind#TIMESTAMP}: ISO-8601 date, local date-time, or date-time with an offset or zone
 * ({@code 2026-01-31}, {@code 2026-01-31T10:15}, {@code 2026-01-31 10:15:00.123456},
 * {@code 2026-01-31T10:15:00+01:00}); seconds are optional, exactly what {@code LocalDateTime.toString()}
 * produces. A value without offset is read as UTC for COMPARISON only (a source column is homogeneous);
 * bound to a JDBC parameter it is a local {@link Timestamp}, an offset value an absolute one.</li>
 * <li>{@link Kind#NUMBER}: a decimal number ({@link BigDecimal}, so {@code 9} &lt; {@code 10} and {@code 1E3}
 * equals {@code 1000}).</li>
 * <li>{@link Kind#STRING}: plain {@code String.compareTo}, i.e. UTF-16 code-unit order - right for
 * fixed-width identifiers, ULIDs and ISO-8601 text, wrong for anything that must sort numerically.</li>
 * </ul>
 * The strategy of the mapping picks the kind: {@code TIMESTAMP}, {@code MONOTONIC_ID} (a number) and
 * {@code ORDERED_STRING}; any other {@link SynchronizationStrategy} is not a row watermark.
 */
public final class Watermark
{
	/** How the values of one watermark are compared. */
	public enum Kind
	{
		TIMESTAMP(SynchronizationStrategy.TIMESTAMP, "1900-01-01T00:00:00"),
		NUMBER(SynchronizationStrategy.MONOTONIC_ID, "-9223372036854775808"),
		STRING(SynchronizationStrategy.ORDERED_STRING, " ");

		private final SynchronizationStrategy strategy;
		private final String floor;

		Kind(final SynchronizationStrategy pStrategy, final String pFloor)
		{
			this.strategy = pStrategy;
			this.floor = pFloor;
		}

		/** The checkpoint strategy stored with a value of this kind. */
		public SynchronizationStrategy strategy()
		{
			return this.strategy;
		}

		/**
		 * The value every real value of this kind is at or above, used as the placeholder value when no
		 * checkpoint exists so that {@code where col >= ${watermark}} reads everything on a first run.
		 */
		public String floor()
		{
			return this.floor;
		}

		/** The kind of a row-watermark strategy; null for a strategy that is not one (a vendor cursor, a delta link...). */
		public static Kind of(final SynchronizationStrategy pStrategy)
		{
			if (pStrategy == null)
			{
				return null;
			}
			for (final Kind kind : Kind.values())
			{
				if (kind.strategy == pStrategy)
				{
					return kind;
				}
			}
			return null;
		}
	}

	/** Longest watermark value kept: a value past it is treated as unreadable rather than truncated (a truncated value would compare wrong). */
	public static final int MAX_LENGTH = 2000;

	private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
	private static final Pattern DATE_SPACE_TIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) ");

	private Watermark()
	{
	}

	// ------------------------------------------------------------------ reading a row

	/**
	 * The watermark text of a row at {@code pPath} (dotted, like every other path of the module), or
	 * null when the field is absent, null, empty, or not a scalar. A single-step name that is not found
	 * as written is looked up ignoring case ({@code last_modified} finds {@code LAST_MODIFIED}), since
	 * JDBC drivers upper- or lower-case column labels.
	 */
	public static String read(final JsonNode pRow, final String pPath)
	{
		if (pRow == null || pPath == null || pPath.trim().isEmpty())
		{
			return null;
		}
		final String path = pPath.trim();
		JsonNode value = Json.at(pRow, path);
		if ((value == null || value.isMissingNode()) && pRow.isObject() && path.indexOf('.') < 0 && path.indexOf('[') < 0)
		{
			final Iterator<Map.Entry<String, JsonNode>> fields = pRow.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				if (field.getKey().equalsIgnoreCase(path))
				{
					value = field.getValue();
					break;
				}
			}
		}
		if (value == null || value.isNull() || value.isMissingNode() || !value.isValueNode())
		{
			return null;
		}
		final String text = value.asText();
		return text == null || text.isEmpty() ? null : text;
	}

	// ------------------------------------------------------------------ comparison

	/** Comparison key of a value: an {@link Instant}, a {@link BigDecimal} or a {@link String}; null when the text is not a valid value of the kind. */
	static Comparable<?> key(final Kind pKind, final String pText)
	{
		if (pText == null || pText.isEmpty() || pText.length() > Watermark.MAX_LENGTH)
		{
			return null;
		}
		switch (pKind)
		{
		case TIMESTAMP:
			return Watermark.instant(pText);
		case NUMBER:
			try
			{
				return new BigDecimal(pText.trim());
			}
			catch (final NumberFormatException ex)
			{
				return null;
			}
		default:
			return pText;
		}
	}

	/**
	 * The canonical text stored for a value, or null when it is not valid for the kind: a timestamp is
	 * kept as the source wrote it (trimmed - it must round-trip to the same instant when it is bound
	 * back), a number is written as a plain decimal, a string is kept exactly.
	 */
	public static String normalise(final Kind pKind, final String pText)
	{
		if (key(pKind, pText) == null)
		{
			return null;
		}
		switch (pKind)
		{
		case TIMESTAMP:
			return pText.trim();
		case NUMBER:
			return new BigDecimal(pText.trim()).toPlainString();
		default:
			return pText;
		}
	}

	/** Compares two values of the kind; both must be valid (see {@link #normalise}). */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static int compare(final Kind pKind, final String pA, final String pB)
	{
		final Comparable a = key(pKind, pA);
		final Comparable b = key(pKind, pB);
		if (a == null || b == null)
		{
			throw new IllegalArgumentException("Not a " + pKind + " watermark: " + (a == null ? pA : pB));
		}
		return a.compareTo(b);
	}

	// ------------------------------------------------------------------ advancing

	/**
	 * The value to store as the new checkpoint after a run that ended DONE, or null to leave the stored
	 * one untouched.
	 * <ul>
	 * <li>Nothing valid was seen: null (an empty read never moves a checkpoint, in either direction).</li>
	 * <li>{@code pFullRefresh}: the highest value seen, even when it is below the stored one - a full
	 * refresh RESETS the checkpoint to what the source really holds now.</li>
	 * <li>Otherwise: the highest value seen when it is above the stored one; never a lower value (the
	 * checkpoint only moves forward), and null when nothing is stored yet is replaced by the value seen.</li>
	 * </ul>
	 */
	public static String next(final Kind pKind, final String pStored, final String pMaxSeen, final boolean pFullRefresh)
	{
		final String seen = normalise(pKind, pMaxSeen);
		if (seen == null)
		{
			return null;
		}
		final String stored = normalise(pKind, pStored);
		if (pFullRefresh || stored == null)
		{
			return seen;
		}
		return compare(pKind, seen, stored) > 0 ? seen : null;
	}

	// ------------------------------------------------------------------ JDBC binding

	/**
	 * The JDBC parameter value of a watermark: a {@link Timestamp} for a timestamp, a {@link Long} (or a
	 * {@link BigDecimal} when it does not fit or has decimals) for a number, the {@link String} itself
	 * for a string - never the untyped guess that turns {@code "0012"} into 12.
	 */
	public static Object sqlValue(final Kind pKind, final String pText)
	{
		if (key(pKind, pText) == null)
		{
			throw new IllegalArgumentException("Not a " + pKind + " watermark: " + pText);
		}
		switch (pKind)
		{
		case TIMESTAMP:
			return Watermark.timestamp(pText);
		case NUMBER:
			final BigDecimal number = new BigDecimal(pText.trim());
			try
			{
				return Long.valueOf(number.longValueExact());
			}
			catch (final ArithmeticException ex)
			{
				return number;
			}
		default:
			return pText;
		}
	}

	/** Kind named {@code pName} (case-insensitive), null when it is not one. */
	public static Kind kindNamed(final String pName)
	{
		if (pName == null)
		{
			return null;
		}
		try
		{
			return Kind.valueOf(pName.trim().toUpperCase());
		}
		catch (final IllegalArgumentException ex)
		{
			return null;
		}
	}

	// ------------------------------------------------------------------ timestamps

	private static TemporalAccessor parseTemporal(final String pText)
	{
		final String text = pText.trim();
		if (DATE_ONLY.matcher(text).matches())
		{
			try
			{
				return LocalDate.parse(text);
			}
			catch (final RuntimeException ex)
			{
				return null;
			}
		}
		final String iso = DATE_SPACE_TIME.matcher(text).replaceFirst("$1T");
		try
		{
			return DateTimeFormatter.ISO_DATE_TIME.parseBest(iso, ZonedDateTime::from, LocalDateTime::from);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static Instant instant(final String pText)
	{
		final TemporalAccessor parsed = parseTemporal(pText);
		if (parsed instanceof ZonedDateTime)
		{
			return ((ZonedDateTime) parsed).toInstant();
		}
		if (parsed instanceof LocalDateTime)
		{
			return ((LocalDateTime) parsed).toInstant(ZoneOffset.UTC);
		}
		if (parsed instanceof LocalDate)
		{
			return ((LocalDate) parsed).atStartOfDay().toInstant(ZoneOffset.UTC);
		}
		return null;
	}

	private static Timestamp timestamp(final String pText)
	{
		final TemporalAccessor parsed = parseTemporal(pText);
		if (parsed instanceof ZonedDateTime)
		{
			return Timestamp.from(((ZonedDateTime) parsed).toInstant());
		}
		if (parsed instanceof LocalDateTime)
		{
			return Timestamp.valueOf((LocalDateTime) parsed);
		}
		return Timestamp.valueOf(((LocalDate) parsed).atStartOfDay());
	}
}
