package com.on.ebx.connect.core;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.connect.spi.Connector;

/**
 * Volume lot V2-D: everything one run of an incremental feed needs to know about its watermark, decided
 * once before the source is read - what the stored checkpoint is, what value the mapping's inputs see,
 * how the rows are filtered, and what the checkpoint becomes at the end. Pure (the stored checkpoint is
 * handed in, never loaded here): {@code WatermarkRunTest}.
 * <p>
 * <b>Placeholders</b> in a mapping input expression:
 * <ul>
 * <li>{@code ${watermark}}: the checkpoint - the stored one, else {@code checkpointInitialValue}, else the
 * floor of the kind (so {@code where s.LAST_MODIFIED >= ${watermark}} reads everything the first time). In
 * an input the connector {@linkplain Connector#bindsWatermark binds} (the SQL of a JDBC {@code query}) it
 * becomes the named parameter {@code :watermark}; anywhere else it is written as text.</li>
 * <li>{@code ${watermark.isSet}}: {@code 1} when a stored checkpoint or an initial value is in force,
 * {@code 0} when {@code ${watermark}} is only the floor (first run, full refresh). A literal in the text,
 * so {@code where (${watermark.isSet} = 0 or s.LAST_MODIFIED >= ${watermark})} is valid SQL.</li>
 * </ul>
 * A full refresh behaves as a first run: the checkpoint is ignored for reading (the initial value too).
 */
public final class WatermarkRun
{
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{\\s*watermark(\\.isSet)?\\s*\\}");
	private static final String SENTINEL_VALUE = "WATERMARK";
	private static final String SENTINEL_IS_SET = "WATERMARKISSET";

	/** Resolves everything in a template other than the watermark placeholders (record fields, {@code ${param.x}}). */
	public interface Resolver
	{
		String resolve(String pTemplate);
	}

	private final Watermark.Kind kind;
	private final String field;
	private final boolean inclusive;
	private final boolean fullRefresh;
	private final String stored;
	private final long storedVersion;
	private final String effective;
	private final String note;
	private boolean boundInput;

	private WatermarkRun(final Watermark.Kind pKind, final String pField, final boolean pInclusive, final boolean pFullRefresh,
		final String pStored, final long pStoredVersion, final String pEffective, final String pNote)
	{
		this.kind = pKind;
		this.field = pField;
		this.inclusive = pInclusive;
		this.fullRefresh = pFullRefresh;
		this.stored = pStored;
		this.storedVersion = pStoredVersion;
		this.effective = pEffective;
		this.note = pNote;
	}

	/**
	 * The plan of a run of {@code pFeed}, or null when the mapping is not an incremental feed
	 * ({@link MappingDefinition#isWatermarkFeed}).
	 *
	 * @param pStored the checkpoint row of the mapping, null when none
	 * @throws IllegalArgumentException when {@code checkpointInitialValue} is not a valid value of the kind
	 *         (a run that would read from a wrong starting point must not start)
	 */
	public static WatermarkRun plan(final MappingDefinition pFeed, final SynchronizationCheckpoint pStored, final boolean pFullRefresh)
	{
		if (pFeed == null || !pFeed.isWatermarkFeed())
		{
			return null;
		}
		final Watermark.Kind kind = Watermark.Kind.of(pFeed.getCheckpointStrategy());
		String stored = null;
		long version = 0L;
		String note = null;
		if (pStored != null)
		{
			version = pStored.getVersion();
			if (pStored.getStrategy() != pFeed.getCheckpointStrategy())
			{
				note = "Stored checkpoint ignored: it was saved under strategy " + pStored.getStrategy() + ", the mapping now uses "
					+ pFeed.getCheckpointStrategy() + " (it is replaced at the end of a successful run)";
			}
			else
			{
				stored = Watermark.normalise(kind, pStored.getValue());
				if (stored == null)
				{
					note = "Stored checkpoint ignored: '" + abbreviate(pStored.getValue()) + "' is not a valid " + kind + " value";
				}
			}
		}
		String initial = null;
		if (!pFeed.getCheckpointInitialValue().isEmpty())
		{
			initial = Watermark.normalise(kind, pFeed.getCheckpointInitialValue());
			if (initial == null)
			{
				throw new IllegalArgumentException("checkpointInitialValue '" + abbreviate(pFeed.getCheckpointInitialValue()) + "' is not a valid " + kind + " value");
			}
		}
		final String effective = pFullRefresh ? null : stored != null ? stored : initial;
		return new WatermarkRun(kind, pFeed.getCheckpointValuePath(), pFeed.isWatermarkInclusive(), pFullRefresh, stored, version, effective, note);
	}

	// ------------------------------------------------------------------ inputs

	/** True when the template contains a watermark placeholder. */
	public static boolean references(final String pTemplate)
	{
		return pTemplate != null && PLACEHOLDER.matcher(pTemplate).find();
	}

	/**
	 * Resolves one mapping input. The watermark placeholders are protected from {@code pResolver} (which
	 * would turn an unknown placeholder into ""), then written: {@code :watermark} when {@code pBind}, the
	 * value as text otherwise; {@code ${watermark.isSet}} as {@code 1}/{@code 0}. The result is trimmed.
	 */
	public String resolveInput(final String pTemplate, final boolean pBind, final Resolver pResolver)
	{
		final Matcher matcher = PLACEHOLDER.matcher(pTemplate);
		final StringBuffer protectedText = new StringBuffer();
		while (matcher.find())
		{
			matcher.appendReplacement(protectedText, matcher.group(1) == null ? SENTINEL_VALUE : SENTINEL_IS_SET);
		}
		matcher.appendTail(protectedText);
		String resolved = pResolver.resolve(protectedText.toString());
		if (resolved == null)
		{
			resolved = "";
		}
		if (resolved.contains(SENTINEL_VALUE))
		{
			if (pBind)
			{
				this.boundInput = true;
			}
			resolved = resolved.replace(SENTINEL_VALUE, pBind ? ":watermark" : this.startValue());
		}
		resolved = resolved.replace(SENTINEL_IS_SET, this.isSet() ? "1" : "0");
		return resolved.trim();
	}

	/**
	 * Completes the resolved inputs: when an input bound {@code :watermark}, the value and its type
	 * ({@code watermark}, {@code watermarkType}); when the mapping names a {@code checkpointInput} and a
	 * checkpoint is in force, that input receives it as text (the pre-existing meaning of the field).
	 */
	public void completeInputs(final Map<String, String> pInputs, final String pCheckpointInput)
	{
		if (this.boundInput)
		{
			pInputs.put("watermark", this.startValue());
			pInputs.put("watermarkType", this.kind.name());
		}
		if (pCheckpointInput != null && !pCheckpointInput.isEmpty() && this.effective != null)
		{
			pInputs.put(pCheckpointInput, this.effective);
		}
	}

	// ------------------------------------------------------------------ reading and ending

	/** The sink to read through: skips rows at or below the checkpoint and tracks the highest watermark. */
	public WatermarkFilter filter(final Connector.RowSink pDelegate, final ImportOutcome pOutcome)
	{
		return new WatermarkFilter(pDelegate, this.kind, this.field, this.effective, this.inclusive, pOutcome);
	}

	/**
	 * Whether the run may move the checkpoint: it must be a real run that ended DONE with nothing
	 * wrong - no failed item, no exception, not truncated by the maximum, not stopped by the circuit
	 * breaker or a cancellation, no conflict left to arbitrate (any of those makes the final status
	 * PARTIAL, ERROR or STOPPED, which is exactly what this checks, plus the explicit signals).
	 */
	public static boolean mayAdvance(final boolean pDryRun, final String pFinalStatus, final boolean pTruncated, final int pErrorCount,
		final boolean pFailed, final boolean pStopped)
	{
		return !pDryRun && !pFailed && !pStopped && !pTruncated && pErrorCount == 0 && "DONE".equals(pFinalStatus);
	}

	/** The value to store after a run that {@linkplain #mayAdvance may advance}, null to leave the stored one. See {@link Watermark#next}. */
	public String next(final WatermarkFilter pFilter)
	{
		return pFilter == null ? null : Watermark.next(this.kind, this.stored, pFilter.getMax(), this.fullRefresh);
	}

	// ------------------------------------------------------------------ accessors

	/** The value {@code ${watermark}} takes: the checkpoint in force, else the floor of the kind. */
	public String startValue()
	{
		return this.effective != null ? this.effective : this.kind.floor();
	}

	/** True when a checkpoint (stored or initial) is in force for reading. */
	public boolean isSet()
	{
		return this.effective != null;
	}

	public Watermark.Kind getKind()
	{
		return this.kind;
	}

	public String getField()
	{
		return this.field;
	}

	public boolean isFullRefresh()
	{
		return this.fullRefresh;
	}

	public boolean isInclusive()
	{
		return this.inclusive;
	}

	/** The checkpoint stored in EBX before the run (canonical), null when none or unusable; reported as {@code watermarkBefore}. */
	public String getStored()
	{
		return this.stored;
	}

	/** Version of the checkpoint row read (0 when none): the optimistic-lock token of the save that ends the run. */
	public long getStoredVersion()
	{
		return this.storedVersion;
	}

	/** Why the stored checkpoint was not used, null when it was fine or absent. */
	public String getNote()
	{
		return this.note;
	}

	/** Message of a run that read rows but found none past the checkpoint. */
	public String describeEmpty(final WatermarkFilter pFilter)
	{
		return "No row past the watermark " + this.startValue() + " (" + pFilter.getSeen() + " row(s) read, "
			+ pFilter.getSkipped() + " at or below it)";
	}

	private static String abbreviate(final String pValue)
	{
		return pValue == null ? "" : pValue.length() > 60 ? pValue.substring(0, 60) + "..." : pValue;
	}
}
