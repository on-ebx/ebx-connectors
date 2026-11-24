package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * One bounded page of a listing that can be very long: the outbound states of a mapping and the synchronisation log
 * (audit C2-07). {@code max} defaults to {@link #DEFAULT_MAX} and is clamped to {@link #HARD_MAX} (never refused), {@code from}
 * is a 0-based offset. The page keeps only the rows it returns but counts every row offered, so {@code total} stays what
 * the caller had before paging. Same vocabulary as {@link RunLogPage}: {@code cut}, {@code nextFrom}, a note.
 * <p>
 * Pure: {@code ListingPageTest}.
 */
public final class ListingPage<T>
{
	/** Rows of a page when the caller does not say. */
	public static final int DEFAULT_MAX = 200;
	/** The most rows one page may hold. */
	public static final int HARD_MAX = 2000;

	private final int from;
	private final int max;
	private final List<T> rows = new ArrayList<T>();
	private int total;

	public ListingPage(final int pFrom, final int pMax)
	{
		this.from = ListingPage.from(pFrom);
		this.max = ListingPage.max(pMax);
	}

	/** The rows requested, clamped: unset or below 1 = {@link #DEFAULT_MAX}, never above {@link #HARD_MAX}. */
	public static int max(final int pRequested)
	{
		if (pRequested < 1)
		{
			return ListingPage.DEFAULT_MAX;
		}
		return Math.min(pRequested, ListingPage.HARD_MAX);
	}

	/** True when the caller asked for more than the ceiling. */
	public static boolean clamped(final int pRequested)
	{
		return pRequested > ListingPage.HARD_MAX;
	}

	/** Rows to skip, never negative. */
	public static int from(final int pRequested)
	{
		return Math.max(0, pRequested);
	}

	/** Offers the next row in the order to report: counted always, kept only when it falls in the page. */
	public void offer(final T pRow)
	{
		if (this.total >= this.from && this.rows.size() < this.max)
		{
			this.rows.add(pRow);
		}
		this.total++;
	}

	/** Takes a page from a stream when the total is known elsewhere: reads only {@code from + max + 1} rows at most. */
	public static <T> ListingPage<T> take(final Iterator<T> pRows, final int pFrom, final int pMax, final int pTotal)
	{
		final ListingPage<T> page = new ListingPage<T>(pFrom, pMax);
		int index = 0;
		while (pRows.hasNext() && index < page.from + page.max)
		{
			final T row = pRows.next();
			if (index >= page.from)
			{
				page.rows.add(row);
			}
			index++;
		}
		page.total = Math.max(pTotal, page.from + page.rows.size());
		return page;
	}

	public List<T> getRows()
	{
		return this.rows;
	}

	/** Every row that matched, whether or not it is in the page. */
	public int getTotal()
	{
		return this.total;
	}

	public int getFrom()
	{
		return this.from;
	}

	public int getMax()
	{
		return this.max;
	}

	/** True when matching rows lie beyond this page. */
	public boolean isCut()
	{
		return this.from + this.rows.size() < this.total;
	}

	/** Where the next page starts. */
	public int getNextFrom()
	{
		return this.from + this.rows.size();
	}

	/**
	 * The sentence that goes with a page: says when it is cut (and where the next one starts) and when the requested {@code max}
	 * was lowered to the ceiling; null when there is nothing to say.
	 */
	public String note(final String pWhat, final int pRequestedMax)
	{
		final StringBuilder note = new StringBuilder();
		if (ListingPage.clamped(pRequestedMax))
		{
			note.append("max=").append(pRequestedMax).append(" was lowered to the ceiling of ").append(ListingPage.HARD_MAX).append(". ");
		}
		if (this.isCut())
		{
			note.append("Only ").append(this.rows.size()).append(" of ").append(this.total).append(' ').append(pWhat).append(" are listed (from ").append(this.from)
				.append("): pass from=").append(this.getNextFrom()).append(" for the next page (max default ").append(ListingPage.DEFAULT_MAX).append(", at most ")
				.append(ListingPage.HARD_MAX).append("), or narrow the filters.");
		}
		return note.length() == 0 ? null : note.toString().trim();
	}
}
