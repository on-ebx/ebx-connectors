package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.List;

/**
 * The parts of the conflict listing that need no repository (audit C-39): the page size, the XPath of a listing, the same rule
 * applied to a conflict fetched by key (the rows a user ticked in the Conflict table), and the order of those keys. That the
 * request really stops after a page and that EBX counts the rest without reading it need a repository and are not run here.
 */
public final class ConflictPageTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// the page size: a default, a floor and a ceiling, and an unreadable value is the default
		check(ConflictResolver.pageSize(null) == 200 && ConflictResolver.pageSize("") == 200 && ConflictResolver.pageSize("  ") == 200, "default 200");
		check(ConflictResolver.pageSize("50") == 50 && ConflictResolver.pageSize(" 75 ") == 75, "a value is kept");
		check(ConflictResolver.pageSize("0") == 1 && ConflictResolver.pageSize("-4") == 1, "at least one conflict");
		check(ConflictResolver.pageSize("999999") == ConflictResolver.MAX_PAGE_SIZE, "at most the maximum");
		check(ConflictResolver.pageSize("many") == 200, "unreadable: the default");

		// the XPath: the status, then each non-empty narrowing, every value quoted
		check(ConflictResolver.filter(null, null, null, null, null).equals("(./status='OPEN' or ./status='APPLYING')"), "open by default");
		check(ConflictResolver.filter("OPEN", "BCompanies", "Companies", "/root/Company", "12").equals(
			"(./status='OPEN' or ./status='APPLYING') and ./dataspace='BCompanies' and ./dataset='Companies' and ./tablePath='/root/Company' and ./recordKey='12'"),
			"every narrowing: " + ConflictResolver.filter("OPEN", "BCompanies", "Companies", "/root/Company", "12"));
		check(ConflictResolver.filter("ACCEPTED", " ", "", null, "o'k").equals("./status='ACCEPTED' and ./recordKey='o''k'"), "blank narrowings are left out, quotes doubled");

		// a conflict fetched by key is kept on the same rule
		check(ConflictResolver.matches(null, null, null, null, null, "OPEN", "B", "D", "/root/T", "1"), "an open one is kept");
		check(ConflictResolver.matches("OPEN", null, null, null, null, "APPLYING", "B", "D", "/root/T", "1"), "an unfinished acceptance counts as open");
		check(!ConflictResolver.matches("OPEN", null, null, null, null, "ACCEPTED", "B", "D", "/root/T", "1"), "a decided one is not");
		check(!ConflictResolver.matches("OPEN", null, null, null, null, null, "B", "D", "/root/T", "1"), "no status is not open");
		check(ConflictResolver.matches("ACCEPTED", null, null, null, null, "ACCEPTED", "B", "D", "/root/T", "1") && !ConflictResolver.matches("ACCEPTED", null, null, null, null, "OPEN", "B", "D", "/root/T", "1"),
			"another status is exact");
		check(ConflictResolver.matches("OPEN", "B", "D", "/root/T", "1", "OPEN", "B", "D", "/root/T", "1"), "all narrowings agree");
		check(!ConflictResolver.matches("OPEN", "B", null, null, null, "OPEN", "X", "D", "/root/T", "1")
			&& !ConflictResolver.matches("OPEN", null, "E", null, null, "OPEN", "B", "D", "/root/T", "1")
			&& !ConflictResolver.matches("OPEN", null, null, "/root/U", null, "OPEN", "B", "D", "/root/T", "1")
			&& !ConflictResolver.matches("OPEN", null, null, null, "2", "OPEN", "B", "D", "/root/T", "1"), "one narrowing that disagrees drops it");

		// the selection, newest (highest id) first, numbers as numbers
		final List<String> ordered = ConflictResolver.newestFirst(Arrays.asList("9", "100", "27", "x", "3"));
		check(ordered.equals(Arrays.asList("100", "27", "9", "3", "x")), "numeric, descending, non-numbers last: " + ordered);
		check(ConflictResolver.newestFirst(Arrays.<String>asList()).isEmpty(), "empty selection");

		System.out.println("PASS: conflict listing is one capped page: page size bounds, XPath, rule for rows fetched by key, selection order");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
