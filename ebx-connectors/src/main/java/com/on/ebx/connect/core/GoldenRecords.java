package com.on.ebx.connect.core;

import java.util.function.Function;

import com.onwbp.adaptation.Adaptation;

/**
 * Consolidation state of a record under Match and Merge (ebx-addon-mame), and its golden record when
 * merged. The add-on's own classes
 * ({@code com.orchestranetworks.addon.mame.*}) are referenced only from {@link MameGoldenRecords} —
 * nowhere else in this module imports them — so {@link #get()} can fall back to a no-op
 * implementation (every record {@link #UNMANAGED}) when the add-on jar is absent from the classpath,
 * without the rest of the module ever seeing the difference. Behaviour on a table that is not under
 * Match and Merge is unknown ahead of time; {@link MameGoldenRecords} treats any failure the same way
 * and remembers it per table so the add-on's exception is paid once, not once per row.
 */
interface GoldenRecords
{
	/** {@link #state} for a record on a table the add-on does not manage, or when the add-on itself
	 * is absent from the classpath. */
	String UNMANAGED = "UNMANAGED";

	/** The Match and Merge state name of the add-on's own {@code State} enum: {@code GOLDEN},
	 * {@code MERGED}, {@code UNSET}, {@code PENDING_APPROVAL}, {@code DELETED} — or
	 * {@link #UNMANAGED}. Never null for a non-null record. */
	String state(Adaptation pRecord);

	/** Reads the Match and Merge state of the records of one table. */
	interface StateReader
	{
		String state(Adaptation pRecord);
	}

	/**
	 * A reader for the records of {@code pTable}, resolved ONCE per table by a caller that is about to read
	 * every record of it (the product publication, audit C-17): whatever is per-table (is the table under
	 * Match and Merge, which field holds the state) is decided here, so the per-record cost is one field read.
	 * The default is {@link #state} itself.
	 */
	default StateReader stateReaderFor(final com.onwbp.adaptation.AdaptationTable pTable)
	{
		return this::state;
	}

	/**
	 * {@code pRecord} itself, unless its state is {@code MERGED}, in which case its target record —
	 * following the chain (a target that is itself {@code MERGED}) with a bound of 10 hops. On any
	 * failure, or once the bound is reached, the last record reached. Never null for a non-null input.
	 */
	Adaptation golden(Adaptation pRecord);

	/**
	 * Real-time mastering (docs/REALTIME-MASTERING.md): the add-on for one table, to pause its matching trigger around a CDC
	 * batch and match the written records. Null when the table is not under Match and Merge, or the add-on is absent or too
	 * old for this API (the default).
	 */
	default CdcMatching.Engine matchingEngine(final com.orchestranetworks.service.Session pSession, final com.onwbp.adaptation.AdaptationTable pTable)
	{
		return null;
	}

	/** What an outbound trigger with {@code publish=golden} sends for a record in this state. */
	enum PublishAs
	{
		/** The record itself: GOLDEN, UNSET (not matched yet) or UNMANAGED. */
		SELF,
		/** Its golden record: MERGED. */
		GOLDEN,
		/** Nothing: DELETED, PENDING_APPROVAL. */
		NOTHING
	}

	/** {@link PublishAs} of a {@link #state} value. */
	static PublishAs publishAs(final String pState)
	{
		if ("MERGED".equals(pState))
			return PublishAs.GOLDEN;
		if ("DELETED".equals(pState) || "PENDING_APPROVAL".equals(pState))
			return PublishAs.NOTHING;
		return PublishAs.SELF;
	}

	/** The add-on backed implementation, resolved once, or a no-op one after a {@link LinkageError}. */
	static GoldenRecords get()
	{
		return Holder.INSTANCE;
	}

	/** Hop bound for {@link #followMerge}. */
	int MAX_HOPS = 10;

	/**
	 * Bounded chain-following, decoupled from {@link Adaptation} so the hop-bounding and
	 * failure-handling logic is unit-tested without EBX or the add-on: follows {@code pTargetOf} while
	 * {@code pStateOf} reports {@code "MERGED"}, up to {@link #MAX_HOPS} hops; stops (returning the
	 * last node reached) on a non-{@code MERGED} state, a null target, or once the bound is reached.
	 * Neither function is expected to throw: {@link MameGoldenRecords} already turns a failure of the
	 * real add-on call into a null target, which this method treats as "stop here".
	 */
	static <T> T followMerge(final T pStart, final Function<T, String> pStateOf, final Function<T, T> pTargetOf)
	{
		T current = pStart;
		for (int hop = 0; hop < GoldenRecords.MAX_HOPS; hop++)
		{
			if (!"MERGED".equals(pStateOf.apply(current)))
			{
				return current;
			}
			final T target = pTargetOf.apply(current);
			if (target == null)
			{
				return current;
			}
			current = target;
		}
		return current;
	}

	/** Resolved on first use (initialization-on-demand holder): {@link MameGoldenRecords}, or a no-op
	 * implementation when constructing it fails with a {@link LinkageError} (the add-on jar is absent). */
	final class Holder
	{
		static final GoldenRecords INSTANCE = Holder.resolve();

		private Holder()
		{
		}

		private static GoldenRecords resolve()
		{
			try
			{
				return new MameGoldenRecords();
			}
			catch (final LinkageError ex)
			{
				return new GoldenRecords()
				{
					@Override
					public String state(final Adaptation pRecord)
					{
						return GoldenRecords.UNMANAGED;
					}

					@Override
					public Adaptation golden(final Adaptation pRecord)
					{
						return pRecord;
					}
				};
			}
		}
	}
}
