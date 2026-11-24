package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** The bounded page of a run's log rows (audit C-33): how far the log is read, when the page is cut, where the next one starts. */
public final class RunLogPageTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// parameters: unset = 50, 0 = none, never above 500, never negative offset
		check(RunLogPage.max(-1) == 50, "unset: the default page");
		check(RunLogPage.max(0) == 0, "0 asks for no log at all");
		check(RunLogPage.max(20) == 20 && RunLogPage.max(500) == 500 && RunLogPage.max(100000) == 500, "bounded above");
		check(RunLogPage.from(-5) == 0 && RunLogPage.from(40) == 40, "the offset is never negative");

		// a log shorter than the page: whole, not cut
		final Counting small = new Counting(7);
		RunLogPage<Integer> page = RunLogPage.take(small, 0, 50);
		check(page.getRows().size() == 7 && !page.isCut() && page.getNextFrom() == 7, "7 rows, 50 asked: all of them, not cut");

		// a log of exactly a page: not cut
		final Counting exact = new Counting(10);
		page = RunLogPage.take(exact, 0, 10);
		check(page.getRows().size() == 10 && !page.isCut(), "10 rows, 10 asked: not cut");

		// a long log: a page, cut, and nothing past the page was taken
		final Counting big = new Counting(1000000);
		page = RunLogPage.take(big, 0, 50);
		check(page.getRows().size() == 50 && page.isCut() && page.getNextFrom() == 50, "a million rows: the first page of 50, cut");
		check(big.read == 50, "the page is taken and the stream only asked whether another row exists, never consumed further: " + big.read);
		check(page.getRows().get(0).intValue() == 0 && page.getRows().get(49).intValue() == 49, "in the order the stream gives (newest first)");
		check(page.cutNote(50).contains("callsFrom=50") && page.cutNote(50).contains("execution{}"), "the note says where the next page starts and where the state is: " + page.cutNote(50));

		// the next page continues where the first stopped
		final Counting again = new Counting(120);
		page = RunLogPage.take(again, 50, 50);
		check(page.getRows().get(0).intValue() == 50 && page.getRows().size() == 50 && page.isCut() && page.getNextFrom() == 100, "second page: rows 50..99, cut");
		page = RunLogPage.take(new Counting(120), 100, 50);
		check(page.getRows().size() == 20 && !page.isCut() && page.getFrom() == 100, "last page: 20 rows, not cut");
		page = RunLogPage.take(new Counting(120), 500, 50);
		check(page.getRows().isEmpty() && !page.isCut(), "beyond the end: empty, not cut");

		// 0 reads nothing
		final Counting none = new Counting(100);
		page = RunLogPage.take(none, 0, 0);
		check(page.getRows().isEmpty() && !page.isCut() && none.read == 0, "callsMax=0 does not touch the log");
		check(RunLogPage.take(new ArrayList<Integer>(Arrays.asList(1, 2)).iterator(), 0, 5).getRows().equals(Arrays.asList(1, 2)), "a plain list works too");
		System.out.println("RunLogPageTest OK");
	}

	/** A stream of rows 0..n-1 that counts how many were read. */
	private static final class Counting implements Iterator<Integer>
	{
		private final int total;
		int read;

		Counting(final int pTotal)
		{
			this.total = pTotal;
		}

		@Override
		public boolean hasNext()
		{
			return this.read < this.total;
		}

		@Override
		public Integer next()
		{
			return Integer.valueOf(this.read++);
		}

		@Override
		public void remove()
		{
			throw new UnsupportedOperationException();
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
