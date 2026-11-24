package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Audit C-10: the in-memory business-key index answers what the XPath predicate answers. The reference model is the
 * predicate itself: the first record, in the table's order, whose every key field equals the text the placeholders
 * give - where an empty text, or a record whose field is empty (EBX stores it as null), never matches.
 */
public final class BusinessKeyIndexTest
{
	private BusinessKeyIndexTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		basics();
		oneClausePredicateText();
		equivalenceWithTheXPathModel();
		boundAndForget();
		System.out.println("PASS: BusinessKeyIndex answers as the XPath predicate does, or says it cannot");
	}

	private static List<String> v(final String... pValues)
	{
		return Arrays.asList(pValues);
	}

	private static void basics()
	{
		final BusinessKeyIndex<String> index = new BusinessKeyIndex<String>(100);
		index.add(v("C1"), "pk1");
		index.add(v("C2"), "pk2");
		check(index.find(v("C1")).getKind() == BusinessKeyIndex.Kind.ONE && "pk1".equals(index.find(v("C1")).getRecord()), "a key finds its record");
		check(index.find(v("C3")).getKind() == BusinessKeyIndex.Kind.NONE, "an unknown key finds nothing");
		check(index.find(v("c1")).getKind() == BusinessKeyIndex.Kind.NONE, "the comparison is exact, case included");
		check(index.find(v("C1 ")).getKind() == BusinessKeyIndex.Kind.NONE, "and blank included");

		index.add(v("C1"), "pk1");
		check(index.find(v("C1")).getKind() == BusinessKeyIndex.Kind.ONE, "adding a record again (after a write) changes nothing");
		index.add(v("C1"), "pk9");
		check(index.find(v("C1")).getKind() == BusinessKeyIndex.Kind.AMBIGUOUS, "two records with one key: ask EBX, which knows its own order");
		index.add(v("C1"), "pk1");
		check(index.find(v("C1")).getKind() == BusinessKeyIndex.Kind.AMBIGUOUS, "and it stays so");

		// empty and null: EBX stores an empty string as null, so ./f='' never matches
		index.add(v(""), "pkE");
		index.add(v((String) null), "pkN");
		check(index.size() == 2, "records with an empty key are not indexed (C1 and C2 only): " + index.size());
		check(index.find(v("")).getKind() == BusinessKeyIndex.Kind.NONE, "an empty text never matches");
		check(index.find(v((String) null)).getKind() == BusinessKeyIndex.Kind.NONE, "nor a null one");
		index.add(v("A", ""), "pkP");
		check(index.find(v("A", "")).getKind() == BusinessKeyIndex.Kind.NONE, "one empty part of two: no match either");
		check(index.find(v("A")).getKind() == BusinessKeyIndex.Kind.NONE, "a key of another width is another key");

		// a value that holds a quote, a separator, or what looks like the encoding of another key
		index.add(v("O'Brien"), "pkQ");
		check("pkQ".equals(index.find(v("O'Brien")).getRecord()), "a quote in the value is the value");
		index.add(v("1:a", "b"), "pkX");
		index.add(v("1", "a:b"), "pkY");
		check("pkX".equals(index.find(v("1:a", "b")).getRecord()) && "pkY".equals(index.find(v("1", "a:b")).getRecord()), "no two lists of values collide");
		index.add(v("ab", "c"), "pk1");
		index.add(v("a", "bc"), "pk2b");
		check("pk1".equals(index.find(v("ab", "c")).getRecord()) && "pk2b".equals(index.find(v("a", "bc")).getRecord()), "('ab','c') and ('a','bc') are different keys");
	}

	/** The text the index is asked about is the text resolvePredicate puts between the quotes. */
	private static void oneClausePredicateText() throws Exception
	{
		final JsonNode item = Json.parse("{\"id\":42,\"name\":\"O'Brien\",\"nul\":null,\"deep\":{\"k\":\"v\"},\"arr\":[1,2],\"flag\":true,\"empty\":\"\"}");
		final String[] paths = { "id", "name", "nul", "missing", "deep.k", "deep", "arr", "flag", "empty", " name " };
		for (final String path : paths)
		{
			final String resolved = ConnectorService.resolvePredicate("./f='${" + path + "}'", item);
			final String rebuilt = "./f=" + ValueTemplates.literal(ConnectorService.placeholderText(item, path));
			check(resolved.equals(rebuilt), "placeholderText is what resolvePredicate embeds: " + path + " -> " + resolved + " vs " + rebuilt);
		}
		check("".equals(ConnectorService.placeholderText(item, "missing")), "a missing path is the empty text");
		check("O'Brien".equals(ConnectorService.placeholderText(item, "name")), "the text is not escaped (the XPath literal unescapes it)");
		check("42".equals(ConnectorService.placeholderText(item, "id")), "a number is its text");
	}

	private static final class Row
	{
		final String pk;
		final List<String> values;

		Row(final String pPk, final List<String> pValues)
		{
			this.pk = pPk;
			this.values = pValues;
		}
	}

	/** What EBX answers to ./f1='t1' and ./f2='t2': the first record of the table that equals every text; never for an empty text. */
	private static String xpathFirst(final List<Row> pTable, final List<String> pTexts)
	{
		for (final String text : pTexts)
		{
			if (text == null || text.isEmpty())
			{
				return null;
			}
		}
		for (final Row row : pTable)
		{
			boolean all = true;
			for (int i = 0; i < pTexts.size(); i++)
			{
				if (!pTexts.get(i).equals(row.values.get(i)))
				{
					all = false;
					break;
				}
			}
			if (all)
			{
				return row.pk;
			}
		}
		return null;
	}

	/** The new decision: the index, asking EBX (the model) when it says ambiguous. */
	private static String indexedFirst(final BusinessKeyIndex<String> pIndex, final List<Row> pTable, final List<String> pTexts)
	{
		final BusinessKeyIndex.Match<String> match = pIndex.find(pTexts);
		switch (match.getKind())
		{
			case NONE:
				return null;
			case ONE:
				return match.getRecord();
			default:
				return xpathFirst(pTable, pTexts);
		}
	}

	private static void equivalenceWithTheXPathModel()
	{
		final String[] alphabet = { "a", "b", "A", "O'Brien", "1:a", "x y", " a", "", null, "é", "", "a|b", "0", "00" };
		final Random random = new Random(20261006L);
		int compared = 0;
		int ambiguous = 0;
		for (int round = 0; round < 400; round++)
		{
			final int width = 1 + random.nextInt(3);
			final int rows = random.nextInt(40);
			final List<Row> table = new ArrayList<Row>();
			final BusinessKeyIndex<String> index = new BusinessKeyIndex<String>(1000);
			for (int r = 0; r < rows; r++)
			{
				final List<String> values = new ArrayList<String>();
				for (int i = 0; i < width; i++)
				{
					values.add(alphabet[random.nextInt(alphabet.length)]);
				}
				final Row row = new Row("pk" + r, values);
				table.add(row);
				index.add(values, row.pk);
			}
			// the run then creates records of its own and tells the index
			final int created = random.nextInt(5);
			for (int r = 0; r < created; r++)
			{
				final List<String> values = new ArrayList<String>();
				for (int i = 0; i < width; i++)
				{
					values.add(alphabet[random.nextInt(alphabet.length)]);
				}
				final String text = xpathFirst(table, values);
				if (text != null)
				{
					continue; // a row that finds its record updates it: nothing created
				}
				final Row row = new Row("new" + r, values);
				table.add(row);
				index.add(values, row.pk);
			}
			for (int q = 0; q < 60; q++)
			{
				final List<String> texts = new ArrayList<String>();
				for (int i = 0; i < width; i++)
				{
					texts.add(alphabet[random.nextInt(alphabet.length)]);
				}
				if (q % 3 == 0 && !table.isEmpty())
				{
					texts.clear();
					for (final String value : table.get(random.nextInt(table.size())).values)
					{
						texts.add(value);
					}
				}
				final String expected = xpathFirst(table, texts);
				final String actual = indexedFirst(index, table, texts);
				check(equal(expected, actual), "index and XPath disagree on " + texts + ": " + expected + " vs " + actual);
				compared++;
				if (index.find(texts).getKind() == BusinessKeyIndex.Kind.AMBIGUOUS)
				{
					ambiguous++;
				}
			}
		}
		check(compared == 24000 && ambiguous > 0, "the random run did exercise ambiguous keys: " + ambiguous);
	}

	private static void boundAndForget()
	{
		final BusinessKeyIndex<String> small = new BusinessKeyIndex<String>(3);
		small.add(v("a"), "1");
		small.add(v("b"), "2");
		small.add(v("c"), "3");
		check(!small.isOverflowed() && small.size() == 3, "three records fit a bound of three");
		small.add(v("a"), "1");
		check(!small.isOverflowed(), "a record already held does not count twice");
		small.add(v("d"), "4");
		check(small.isOverflowed() && small.size() == 0, "the fourth overflows: the index is emptied and must not be used");
		small.add(v("e"), "5");
		check(small.size() == 0, "an overflowed index stays empty");

		final BusinessKeyIndex<String> forgotten = new BusinessKeyIndex<String>(10);
		forgotten.add(v("a"), "1");
		forgotten.forget(v("a"));
		check(forgotten.find(v("a")).getKind() == BusinessKeyIndex.Kind.NONE, "a forgotten key is no longer answered");
		forgotten.add(v("a"), "7");
		check("7".equals(forgotten.find(v("a")).getRecord()), "and takes the record EBX found");
		forgotten.forget(v(""));
		check(forgotten.size() == 1, "forgetting an empty key does nothing");
	}

	private static boolean equal(final String pA, final String pB)
	{
		return pA == null ? pB == null : pA.equals(pB);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
