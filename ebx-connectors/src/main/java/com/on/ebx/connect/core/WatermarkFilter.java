package com.on.ebx.connect.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;

/**
 * Volume lot V2-D: the row sink an incremental feed reads through. It sits in front of the batching sink
 * and does two cheap things per row, before anything is looked up, transformed or written:
 * <ul>
 * <li>skips the rows at or below the checkpoint (the client-side filter of a source that cannot filter
 * itself - a file, a REST list without a "since" parameter - and a harmless second guard for a source that
 * did: rows whose watermark equals the checkpoint are skipped only when the boundary is exclusive);</li>
 * <li>tracks the highest watermark it has seen, which becomes the new checkpoint if the run ends DONE
 * ({@link Watermark#next}).</li>
 * </ul>
 * A row whose watermark field is missing, null or not a valid value of the kind cannot be judged: it is
 * delivered (never silently dropped), counted as unreadable, and never taken for the highest value.
 * Pure: no EBX type is involved ({@code WatermarkFilterTest}).
 */
public final class WatermarkFilter implements Connector.RowSink
{
	private final Connector.RowSink delegate;
	private final Watermark.Kind kind;
	private final String field;
	private final String checkpoint;
	private final Comparable<?> checkpointKey;
	private final boolean inclusive;
	private final ImportOutcome outcome;

	/** For each row passed on, the number the source gave it: lets the source's own report be told in its numbers. */
	private int[] passedOn = new int[16];
	private int passedCount;
	/** What the delegate has settled so far, in its numbers and in the source's. */
	private int delegateSettled;
	private int sourceSettled;
	private Connector.HandledListener listener;

	private int seen;
	private int skipped;
	private int unreadable;
	private Comparable<?> maxKey;
	private String maxText;

	/**
	 * @param pCheckpoint the value at or below which rows are skipped; null reads everything (a first run, a full refresh)
	 * @param pInclusive true: a row equal to the checkpoint is read again; false: it is skipped
	 * @param pOutcome receives the running skipped count (for the run state saved after each batch); may be null
	 */
	public WatermarkFilter(final Connector.RowSink pDelegate, final Watermark.Kind pKind, final String pField, final String pCheckpoint,
		final boolean pInclusive, final ImportOutcome pOutcome)
	{
		this.delegate = pDelegate;
		this.kind = pKind;
		this.field = pField;
		this.checkpoint = pCheckpoint == null ? null : Watermark.normalise(pKind, pCheckpoint);
		this.checkpointKey = this.checkpoint == null ? null : Watermark.key(pKind, this.checkpoint);
		this.inclusive = pInclusive;
		this.outcome = pOutcome;
	}

	private boolean passOn(final JsonNode pRow)
	{
		if (this.passedCount == this.passedOn.length)
		{
			this.passedOn = java.util.Arrays.copyOf(this.passedOn, this.passedCount * 2);
		}
		this.passedOn[this.passedCount++] = this.seen - 1;
		return this.delegate.accept(pRow);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public boolean accept(final JsonNode pRow)
	{
		this.seen++;
		final String raw = Watermark.read(pRow, this.field);
		final Comparable key = raw == null ? null : Watermark.key(this.kind, raw);
		if (key == null)
		{
			this.unreadable++;
			return this.passOn(pRow);
		}
		if (this.maxKey == null || key.compareTo(this.maxKey) > 0)
		{
			this.maxKey = key;
			this.maxText = Watermark.normalise(this.kind, raw);
		}
		if (this.checkpointKey != null)
		{
			final int c = key.compareTo(this.checkpointKey);
			if (c < 0 || (c == 0 && !this.inclusive))
			{
				this.skipped++;
				if (this.outcome != null)
				{
					this.outcome.skippedByWatermark();
				}
				return true;
			}
		}
		return this.passOn(pRow);
	}

	/**
	 * Passes the report on in the source's numbering: a row this filter skipped is settled with the rows around it
	 * (it was meant to be left out), and a row it passed on is settled as the delegate says.
	 */
	@Override
	public boolean onHandled(final Connector.HandledListener pListener)
	{
		final boolean live = this.delegate.onHandled(new Connector.HandledListener()
		{
			@Override
			public void handled(final int pFrom, final int pTo, final java.util.Set<Integer> pNotWritten)
			{
				WatermarkFilter.this.settle(pFrom, pTo, pNotWritten);
			}
		});
		this.listener = live ? pListener : null;
		return live;
	}

	private void settle(final int pFrom, final int pTo, final java.util.Set<Integer> pNotWritten)
	{
		if (this.listener == null || pTo <= pFrom || pTo > this.passedCount)
		{
			return;
		}
		// Contiguous with what was reported: the rows skipped in between are settled too; after a gap (rows the
		// delegate chose not to take) they are not.
		final int from = pFrom == this.delegateSettled ? this.sourceSettled : this.passedOn[pFrom];
		final int to = this.passedOn[pTo - 1] + 1;
		final java.util.Set<Integer> failed = new java.util.TreeSet<Integer>();
		if (pNotWritten != null)
		{
			for (final Integer row : pNotWritten)
			{
				if (row.intValue() >= pFrom && row.intValue() < pTo)
				{
					failed.add(Integer.valueOf(this.passedOn[row.intValue()]));
				}
			}
		}
		this.delegateSettled = pTo;
		this.sourceSettled = to;
		this.listener.handled(from, to, failed);
	}

	/** The delegate settles what it holds; rows skipped after the last one passed on are then settled too. */
	@Override
	public void endOfRows()
	{
		this.delegate.endOfRows();
		if (this.listener != null && this.delegateSettled == this.passedCount && this.sourceSettled < this.seen)
		{
			final int from = this.sourceSettled;
			this.sourceSettled = this.seen;
			this.listener.handled(from, this.seen, java.util.Collections.<Integer>emptySet());
		}
	}

	@Override
	public void notice(final String pMessage)
	{
		this.delegate.notice(pMessage);
	}

	/** Rows the source delivered, skipped ones included. */
	public int getSeen()
	{
		return this.seen;
	}

	/** Rows skipped because they were at (exclusive boundary) or below the checkpoint. */
	public int getSkipped()
	{
		return this.skipped;
	}

	/** Rows delivered without being judged: no watermark field, null, or not a valid value. */
	public int getUnreadable()
	{
		return this.unreadable;
	}

	/** Highest valid watermark seen, canonical text; null when no row carried one. */
	public String getMax()
	{
		return this.maxText;
	}

	/** The checkpoint this filter compared against (canonical), null when it read everything. */
	public String getCheckpoint()
	{
		return this.checkpoint;
	}
}
