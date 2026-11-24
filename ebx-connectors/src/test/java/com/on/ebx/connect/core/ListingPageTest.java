package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;

/** The bounded page of outbound states and of the synchronisation log (audit C2-07): clamping, offsets, cut/nextFrom, total, the answer fields. */
public final class ListingPageTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// parameters: unset = 200, never above 2000 (clamped, not refused), never a negative offset
		check(ListingPage.max(0) == 200 && ListingPage.max(-1) == 200, "unset: the default page");
		check(ListingPage.max(50) == 50 && ListingPage.max(2000) == 2000, "inside the ceiling: kept");
		check(ListingPage.max(10000000) == 2000, "10^7 is lowered to 2000");
		check(ListingPage.clamped(10000000) && !ListingPage.clamped(2000), "clamped() says when the ceiling was applied");
		check(ListingPage.from(-3) == 0 && ListingPage.from(7) == 7, "the offset is never negative");

		// three pages of 2 over 5 rows: total stays 5, cut and nextFrom coherent
		ListingPage<Integer> p1 = walk(5, 0, 2);
		check(p1.getRows().equals(list(0, 1)) && p1.getTotal() == 5 && p1.isCut() && p1.getNextFrom() == 2, "page 1: rows 0,1 of 5, cut, next 2");
		ListingPage<Integer> p2 = walk(5, p1.getNextFrom(), 2);
		check(p2.getRows().equals(list(2, 3)) && p2.getTotal() == 5 && p2.isCut() && p2.getNextFrom() == 4, "page 2: rows 2,3, cut, next 4");
		ListingPage<Integer> p3 = walk(5, p2.getNextFrom(), 2);
		check(p3.getRows().equals(list(4)) && p3.getTotal() == 5 && !p3.isCut() && p3.getNextFrom() == 5, "page 3: row 4, not cut");
		check(p3.note("states", 2) == null, "no note when nothing is cut or clamped");
		check(p1.note("states", 2).contains("from=2") && p1.note("states", 2).contains("2 of 5"), "the note says where the next page starts: " + p1.note("states", 2));

		// from beyond the end: empty, total unchanged, not cut
		ListingPage<Integer> beyond = walk(5, 50, 2);
		check(beyond.getRows().isEmpty() && beyond.getTotal() == 5 && !beyond.isCut() && beyond.getNextFrom() == 50, "from beyond the end: empty page, total 5, not cut");

		// exactly a page: not cut
		check(!walk(10, 0, 10).isCut(), "10 rows, page of 10: not cut");

		// the ceiling applies to the page and the note says so
		ListingPage<Integer> big = walk(5000, 0, 10000000);
		check(big.getRows().size() == 2000 && big.getTotal() == 5000 && big.isCut() && big.getNextFrom() == 2000, "5000 rows, max 10^7: a page of 2000, total 5000");
		check(big.note("states", 10000000).contains("lowered to the ceiling of 2000"), "the note says max was lowered: " + big.note("states", 10000000));
		ListingPage<Integer> clampedOnly = walk(30, 0, 10000000);
		check(!clampedOnly.isCut() && clampedOnly.note("states", 10000000) != null, "a clamped max is announced even when the page is not cut");

		// take() over a stream whose total is known elsewhere: reads at most from + max rows
		final Counting stream = new Counting(1000000);
		ListingPage<Integer> taken = ListingPage.take(stream, 100, 50, 1000000);
		check(taken.getRows().size() == 50 && taken.getRows().get(0).intValue() == 100 && taken.getTotal() == 1000000 && taken.isCut() && taken.getNextFrom() == 150, "take: rows 100..149 of a million");
		check(stream.read == 150, "take reads only from+max rows: " + stream.read);
		ListingPage<Integer> shortStream = ListingPage.take(new Counting(3), 0, 50, 3);
		check(shortStream.getRows().size() == 3 && !shortStream.isCut(), "take: a short stream is whole and not cut");

		// the answer fields: count is the total, cut/nextFrom/note only when they apply
		final ObjectNode cut = Json.object();
		ConnectorsTools.pageFields(cut, p1, 2, "outbound states");
		check(cut.path("count").asInt() == 5 && cut.path("max").asInt() == 2 && cut.path("from").asInt() == 0 && cut.path("cut").asBoolean() && cut.path("nextFrom").asInt() == 2 && cut.has("note"), "cut answer: " + cut);
		final ObjectNode last = Json.object();
		ConnectorsTools.pageFields(last, p3, 2, "outbound states");
		check(last.path("count").asInt() == 5 && !last.has("cut") && !last.has("nextFrom") && !last.has("note"), "last page: count 5, no cut, no nextFrom, no note: " + last);
		final ObjectNode lowered = Json.object();
		ConnectorsTools.pageFields(lowered, clampedOnly, 10000000, "log rows");
		check(lowered.path("max").asInt() == 2000 && lowered.path("note").asText().contains("2000"), "max reported as applied: " + lowered);
		System.out.println("ListingPageTest OK");
	}

	private static ListingPage<Integer> walk(final int pTotal, final int pFrom, final int pMax)
	{
		final ListingPage<Integer> page = new ListingPage<Integer>(pFrom, pMax);
		for (int i = 0; i < pTotal; i++)
		{
			page.offer(Integer.valueOf(i));
		}
		return page;
	}

	private static List<Integer> list(final int... pValues)
	{
		final List<Integer> out = new ArrayList<Integer>();
		for (final int v : pValues)
		{
			out.add(Integer.valueOf(v));
		}
		return out;
	}

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
