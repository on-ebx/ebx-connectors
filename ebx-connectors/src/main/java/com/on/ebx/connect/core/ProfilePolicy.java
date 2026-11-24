package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a profile run may spend (audit C-15). A profile is one aggregate statement per table over up to 200
 * columns; on a warehouse that is billed compute, and {@code max} bounds only what is imported afterwards,
 * not the statements. This class holds the pure decisions, shared by the statement-API platforms
 * ({@link CatalogOperations#profile}) and {@code JdbcConnector}, so they are unit-tested without a platform:
 * <ul>
 * <li>a per-run budget: at most {@code maxTables} tables and {@code maxSeconds} of elapsed time (defaults 200 and
 * 900; {@code 0} lifts a limit); what the budget left out is named in the answer, never dropped silently;</li>
 * <li>what to do with a table whose known row count reaches {@code largeTableRows} (default 10,000,000; owner
 * decision 2026-10-06: exact below the threshold, sampled above it, by default): {@code largeTables} =
 * {@code sample} (the default: the statement runs on a block sample of about {@code sampleRows} rows, default
 * 1,000,000, and every figure says it is sampled), {@code exact} (every table is scanned in full, whatever its
 * size) or {@code skip};</li>
 * <li>{@code approxDistinct}: distinct counts by the platform's approximate function (off by default);</li>
 * <li>{@code dryRun}: the statements and the estimated work are returned, none is run.</li>
 * </ul>
 * Each setting is read from the operation's input first, then from the connector property {@code profile.<name>},
 * then the default.
 */
public final class ProfilePolicy
{
	public static final int DEFAULT_MAX_TABLES = 200;
	public static final long DEFAULT_MAX_SECONDS = 900L;
	public static final long DEFAULT_LARGE_TABLE_ROWS = 10000000L;
	public static final long DEFAULT_SAMPLE_ROWS = 1000000L;
	/** Smallest block-sampling percentage written (a sample of nothing is not a statistic). */
	static final double MIN_PERCENT = 0.0001d;

	/** What happens to a table above the row threshold. */
	public enum Large
	{
		EXACT, SAMPLE, SKIP
	}

	/** How one table is profiled. */
	public enum Mode
	{
		FULL_SCAN, SAMPLE, SKIP
	}

	/** The decision for one table. */
	public static final class Decision
	{
		private final Mode mode;
		private final double percent;
		private final String reason;

		Decision(final Mode pMode, final double pPercent, final String pReason)
		{
			this.mode = pMode;
			this.percent = pPercent;
			this.reason = pReason;
		}

		public Mode mode()
		{
			return this.mode;
		}

		/** Sampling percentage (0 to 100) when the mode is {@link Mode#SAMPLE}. */
		public double percent()
		{
			return this.percent;
		}

		/** Why the table is sampled or skipped; empty for a full scan. */
		public String reason()
		{
			return this.reason;
		}
	}

	private final int maxTables;
	private final long maxSeconds;
	private final Large large;
	private final long largeTableRows;
	private final long sampleRows;
	private final boolean approxDistinct;
	private final boolean dryRun;

	ProfilePolicy(final int pMaxTables, final long pMaxSeconds, final Large pLarge, final long pLargeTableRows, final long pSampleRows,
		final boolean pApproxDistinct, final boolean pDryRun)
	{
		this.maxTables = pMaxTables;
		this.maxSeconds = pMaxSeconds;
		this.large = pLarge;
		this.largeTableRows = pLargeTableRows;
		this.sampleRows = pSampleRows;
		this.approxDistinct = pApproxDistinct;
		this.dryRun = pDryRun;
	}

	/** {@code pProperties} answers a connector property by its full name ({@code profile.maxTables}), or null. */
	public static ProfilePolicy of(final Map<String, String> pInputs, final Function<String, String> pProperties)
	{
		return new ProfilePolicy(
			(int) ProfilePolicy.number(pInputs, pProperties, "maxTables", ProfilePolicy.DEFAULT_MAX_TABLES, Integer.MAX_VALUE),
			ProfilePolicy.number(pInputs, pProperties, "maxSeconds", ProfilePolicy.DEFAULT_MAX_SECONDS, 86400L),
			ProfilePolicy.large(ProfilePolicy.raw(pInputs, pProperties, "largeTables")),
			ProfilePolicy.number(pInputs, pProperties, "largeTableRows", ProfilePolicy.DEFAULT_LARGE_TABLE_ROWS, Long.MAX_VALUE),
			Math.max(1L, ProfilePolicy.number(pInputs, pProperties, "sampleRows", ProfilePolicy.DEFAULT_SAMPLE_ROWS, Long.MAX_VALUE)),
			ProfilePolicy.bool(ProfilePolicy.raw(pInputs, pProperties, "approxDistinct")),
			ProfilePolicy.bool(pInputs == null ? null : pInputs.get("dryRun")));
	}

	private static String raw(final Map<String, String> pInputs, final Function<String, String> pProperties, final String pName)
	{
		final String input = pInputs == null ? null : pInputs.get(pName);
		if (input != null && !input.trim().isEmpty())
		{
			return input.trim();
		}
		final String property = pProperties == null ? null : pProperties.apply("profile." + pName);
		return property == null || property.trim().isEmpty() ? null : property.trim();
	}

	/** A non-negative whole number; an unreadable or negative value keeps the default. */
	private static long number(final Map<String, String> pInputs, final Function<String, String> pProperties, final String pName, final long pDefault,
		final long pCeiling)
	{
		final String raw = ProfilePolicy.raw(pInputs, pProperties, pName);
		if (raw == null)
		{
			return pDefault;
		}
		try
		{
			final long value = Long.parseLong(raw);
			return value < 0 ? pDefault : Math.min(value, pCeiling);
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	private static Large large(final String pRaw)
	{
		if (pRaw == null)
		{
			return Large.SAMPLE;
		}
		switch (pRaw.toLowerCase(Locale.ROOT))
		{
			case "exact":
				return Large.EXACT;
			case "skip":
				return Large.SKIP;
			default:
				return Large.SAMPLE;
		}
	}

	private static boolean bool(final String pRaw)
	{
		return pRaw != null && "true".equalsIgnoreCase(pRaw.trim());
	}

	public int maxTables()
	{
		return this.maxTables;
	}

	public long maxSeconds()
	{
		return this.maxSeconds;
	}

	public Large large()
	{
		return this.large;
	}

	public long largeTableRows()
	{
		return this.largeTableRows;
	}

	public long sampleRows()
	{
		return this.sampleRows;
	}

	public boolean approxDistinct()
	{
		return this.approxDistinct;
	}

	public boolean dryRun()
	{
		return this.dryRun;
	}

	/**
	 * How to profile a table whose catalog row count is {@code pRowCount} (negative: unknown, as for a view). Unknown
	 * counts are scanned in full: a threshold cannot be applied to a size nobody knows. A platform that cannot sample
	 * ({@code pSampleSupported} false) skips instead of scanning, because the caller asked not to scan large tables.
	 */
	public Decision decide(final long pRowCount, final boolean pSampleSupported)
	{
		if (this.large == Large.EXACT || pRowCount < 0 || pRowCount < this.largeTableRows)
		{
			return new Decision(Mode.FULL_SCAN, 100d, "");
		}
		final String size = pRowCount + " row(s), at or above largeTableRows " + this.largeTableRows;
		if (this.large == Large.SKIP)
		{
			return new Decision(Mode.SKIP, 0d, "skipped: " + size);
		}
		if (!pSampleSupported)
		{
			return new Decision(Mode.SKIP, 0d, "skipped: " + size + " and this platform has no sampling clause");
		}
		return new Decision(Mode.SAMPLE, ProfilePolicy.samplePercent(pRowCount, this.sampleRows), "sampled: " + size);
	}

	/** The block-sampling percentage giving about {@code pSampleRows} rows out of {@code pRowCount}. */
	public static double samplePercent(final long pRowCount, final long pSampleRows)
	{
		if (pRowCount <= 0 || pSampleRows >= pRowCount)
		{
			return 100d;
		}
		return Math.max(ProfilePolicy.MIN_PERCENT, Math.min(100d, pSampleRows * 100d / pRowCount));
	}

	/** A percentage as SQL text: plain decimal, no exponent, no trailing zeros. */
	public static String percentText(final double pPercent)
	{
		String text = String.format(Locale.ROOT, "%.6f", pPercent);
		if (text.indexOf('.') >= 0)
		{
			text = text.replaceAll("0+$", "");
			if (text.endsWith("."))
			{
				text = text.substring(0, text.length() - 1);
			}
		}
		return text;
	}

	/** The running budget of one profile run: counts the tables and the time, and remembers what it left out. */
	public static final class Run
	{
		private final ProfilePolicy policy;
		private final LongSupplier clockMillis;
		private final long startedMillis;
		private int tablesProfiled;
		private int tablesSampled;
		private int tablesSeen;
		private String stoppedBy = "";
		private final List<String[]> skipped = new ArrayList<String[]>();

		public Run(final ProfilePolicy pPolicy)
		{
			this(pPolicy, System::currentTimeMillis);
		}

		/** A run on a fake clock, for tests. */
		Run(final ProfilePolicy pPolicy, final LongSupplier pClockMillis)
		{
			this.policy = pPolicy;
			this.clockMillis = pClockMillis;
			this.startedMillis = pClockMillis.getAsLong();
		}

		/**
		 * Whether the next table may be started. False once the table or time budget is spent (the first limit
		 * reached is remembered in {@code stoppedBy}); then every further table is recorded with {@link #skip}.
		 */
		public boolean mayStartTable()
		{
			if (!this.stoppedBy.isEmpty())
			{
				return false;
			}
			if (this.policy.maxTables > 0 && this.tablesProfiled >= this.policy.maxTables)
			{
				this.stoppedBy = "maxTables";
				return false;
			}
			if (this.policy.maxSeconds > 0 && this.clockMillis.getAsLong() - this.startedMillis >= this.policy.maxSeconds * 1000L)
			{
				this.stoppedBy = "maxSeconds";
				return false;
			}
			return true;
		}

		/** Counts one table as profiled (or planned). */
		public void profiled(final boolean pSampled)
		{
			this.tablesProfiled++;
			if (pSampled)
			{
				this.tablesSampled++;
			}
		}

		/** Records a table left out, with the reason. */
		public void skip(final String pTable, final String pReason)
		{
			this.skipped.add(new String[] { pTable, pReason });
		}

		/** Records a table the budget stopped before it started. */
		public void skipForBudget(final String pTable)
		{
			this.skip(pTable, "budget: " + this.stoppedBy + " reached");
		}

		public void seen()
		{
			this.tablesSeen++;
		}

		public String stoppedBy()
		{
			return this.stoppedBy;
		}

		public int tablesProfiled()
		{
			return this.tablesProfiled;
		}

		public int tablesSampled()
		{
			return this.tablesSampled;
		}

		public int tablesSkipped()
		{
			return this.skipped.size();
		}

		/** True when something the run was asked to profile was not profiled in full: skipped, sampled or cut by the budget. */
		public boolean partial()
		{
			return !this.skipped.isEmpty() || !this.stoppedBy.isEmpty();
		}

		/** The budget block written into the answer: what was asked, what was spent, what was left out. */
		public ObjectNode toJson()
		{
			final ObjectNode out = Json.object();
			out.put("maxTables", this.policy.maxTables);
			out.put("maxSeconds", this.policy.maxSeconds);
			out.put("largeTables", this.policy.large.name().toLowerCase(Locale.ROOT));
			out.put("largeTableRows", this.policy.largeTableRows);
			out.put("approxDistinct", this.policy.approxDistinct);
			out.put("tablesProfiled", this.tablesProfiled);
			out.put("tablesSampled", this.tablesSampled);
			out.put("tablesSkipped", this.skipped.size());
			out.put("elapsedMs", this.clockMillis.getAsLong() - this.startedMillis);
			if (!this.stoppedBy.isEmpty())
			{
				out.put("stoppedBy", this.stoppedBy);
			}
			if (!this.skipped.isEmpty())
			{
				final ArrayNode list = out.putArray("skipped");
				for (final String[] entry : this.skipped)
				{
					final ObjectNode item = list.addObject();
					item.put("table", entry[0]);
					item.put("reason", entry[1]);
				}
			}
			return out;
		}
	}
}
