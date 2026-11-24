package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * One bounded page of the log rows of a run (audit C-33). A run can leave a log row per batch and one per failed
 * record; answering "what is the state of this run" used to return every one of them, read from the whole
 * table. The state is answered from the run's own row; the log rows come as a page, newest first, and the answer
 * says when the page is cut.
 * <p>
 * Pure: {@code RunLogPageTest}.
 */
public final class RunLogPage<T>
{
	/** Rows of a page when the caller does not say. */
	public static final int DEFAULT_MAX = 50;
	/** The most rows one page may hold. */
	public static final int HARD_MAX = 500;

	private final List<T> rows;
	private final int from;
	private final boolean more;

	private RunLogPage(final List<T> pRows, final int pFrom, final boolean pMore)
	{
		this.rows = pRows;
		this.from = pFrom;
		this.more = pMore;
	}

	/** The rows requested, clamped: unset (negative) = {@link #DEFAULT_MAX}, 0 = none, never above {@link #HARD_MAX}. */
	public static int max(final int pRequested)
	{
		if (pRequested < 0)
		{
			return RunLogPage.DEFAULT_MAX;
		}
		return Math.min(pRequested, RunLogPage.HARD_MAX);
	}

	/** Rows to skip, never negative. */
	public static int from(final int pRequested)
	{
		return Math.max(0, pRequested);
	}

	/**
	 * Takes a page from a stream already in the order to report: skips {@code pFrom} rows, keeps up to {@code pMax}, and
	 * reads one more only to know whether the page is cut. Reads nothing when {@code pMax} is 0.
	 */
	public static <T> RunLogPage<T> take(final Iterator<T> pRows, final int pFrom, final int pMax)
	{
		final List<T> page = new ArrayList<T>();
		if (pMax <= 0)
		{
			return new RunLogPage<T>(page, pFrom, false);
		}
		int skipped = 0;
		while (skipped < pFrom && pRows.hasNext())
		{
			pRows.next();
			skipped++;
		}
		while (page.size() < pMax && pRows.hasNext())
		{
			page.add(pRows.next());
		}
		return new RunLogPage<T>(page, pFrom, page.size() == pMax && pRows.hasNext());
	}

	public List<T> getRows()
	{
		return this.rows;
	}

	public int getFrom()
	{
		return this.from;
	}

	/** True when rows lie beyond this page. */
	public boolean isCut()
	{
		return this.more;
	}

	/** Where the next page starts. */
	public int getNextFrom()
	{
		return this.from + this.rows.size();
	}

	/** What the answer says about a cut page. */
	public String cutNote(final int pMax)
	{
		return "Only " + this.rows.size() + " log row(s), newest first, are listed (from " + this.from + "): more exist. Pass callsFrom=" + this.getNextFrom()
			+ " for the next page (callsMax up to " + RunLogPage.HARD_MAX + ", " + pMax + " here), or connectors_log_search(runId=...) to filter them. "
			+ "The state of the run is in execution{}, which does not depend on the page.";
	}
}
