package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure decision behind the volume lot 5 primary-key fast path: does a feed's {@code keyPredicate}
 * template address the table's whole primary key with one simple equality clause per field? No EBX
 * type involved - see {@link KeyPredicatePrimaryKey}.
 */
public final class KeyPredicatePrimaryKeyTest
{
	private KeyPredicatePrimaryKeyTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// the motivating case: a single-field primary key, e.g. the MDM Factory's landing _sourceId
		final KeyPredicatePrimaryKey single = KeyPredicatePrimaryKey.forTemplate(
			"./_sourceId='${SOURCE_ID}'", Collections.singletonList("_sourceId"));
		check(single != null, "a single-field predicate matching the single primary-key field must be recognized");
		check(single.getPlaceholdersInKeyOrder().equals(Collections.singletonList("SOURCE_ID")), "the placeholder expression must be extracted");

		// extra surrounding spaces are tolerated
		final KeyPredicatePrimaryKey spaced = KeyPredicatePrimaryKey.forTemplate(
			"  ./_sourceId = '${SOURCE_ID}'  ", Collections.singletonList("_sourceId"));
		check(spaced != null, "surrounding and internal spaces around = must be tolerated");

		// a composite key: order of the clauses need not match the table's primary-key order
		final KeyPredicatePrimaryKey composite = KeyPredicatePrimaryKey.forTemplate(
			"./b='${B}' and ./a='${A}'", Arrays.asList("a", "b"));
		check(composite != null, "a composite predicate covering the whole primary key must be recognized regardless of clause order");
		check(composite.getPlaceholdersInKeyOrder().equals(Arrays.asList("A", "B")), "placeholders must come back in the table's primary-key order, not the predicate's");

		// a predicate on a field outside the primary key must never match
		check(KeyPredicatePrimaryKey.forTemplate("./other='${X}'", Collections.singletonList("_sourceId")) == null,
			"a predicate naming a field outside the primary key must fall back to XPath");

		// a predicate missing a primary-key field (composite key, only one clause given) must never match
		check(KeyPredicatePrimaryKey.forTemplate("./a='${A}'", Arrays.asList("a", "b")) == null,
			"a predicate missing part of a composite primary key must fall back to XPath");

		// an extra clause beyond the primary key's fields must never match, even if every key field is present
		check(KeyPredicatePrimaryKey.forTemplate("./a='${A}' and ./b='${B}' and ./c='${C}'", Arrays.asList("a", "b")) == null,
			"an extra clause outside the primary key must fall back to XPath");

		// the same field named twice must never match (ambiguous, and never a legitimate primary-key predicate)
		check(KeyPredicatePrimaryKey.forTemplate("./a='${A}' and ./a='${A2}'", Arrays.asList("a", "b")) == null,
			"a repeated field must fall back to XPath");

		// anything that is not a plain equality-to-a-single-placeholder clause must fall back
		check(KeyPredicatePrimaryKey.forTemplate("./_sourceId=concat('${A}','${B}')", Collections.singletonList("_sourceId")) == null,
			"a non-trivial clause shape must fall back to XPath");
		check(KeyPredicatePrimaryKey.forTemplate("./_sourceId!='${A}'", Collections.singletonList("_sourceId")) == null,
			"a non-equality operator must fall back to XPath");
		check(KeyPredicatePrimaryKey.forTemplate("./_sourceId='ABC'", Collections.singletonList("_sourceId")) == null,
			"a literal value with no placeholder must fall back to XPath (nothing to extract per item)");

		// degenerate inputs
		check(KeyPredicatePrimaryKey.forTemplate(null, Collections.singletonList("_sourceId")) == null, "a null template must fall back");
		check(KeyPredicatePrimaryKey.forTemplate("", Collections.singletonList("_sourceId")) == null, "a blank template must fall back");
		check(KeyPredicatePrimaryKey.forTemplate("./_sourceId='${X}'", Collections.<String>emptyList()) == null, "a table with no known primary-key fields must fall back");

		final List<String> unrelated = single.getPlaceholdersInKeyOrder();
		check(unrelated.size() == 1, "sanity: the single-field case exposes exactly one placeholder");

		numericKeys();

		System.out.println("PASS: KeyPredicatePrimaryKey recognizes exact primary-key-covering predicates and falls back on everything else");
	}

	/** Audit C-10: the fast path now covers int, integer and decimal key fields. */
	private static void numericKeys()
	{
		final KeyPredicatePrimaryKey.NumberParser integer = parser(false);
		final KeyPredicatePrimaryKey.NumberParser decimal = parser(true);
		final List<KeyPredicatePrimaryKey.NumberParser> one = Collections.singletonList(integer);
		final List<String> id = Collections.singletonList("id");

		check(KeyPredicatePrimaryKey.forTemplate("./id='${X}'", id, one) != null, "a quoted placeholder on an int key is recognized");
		check(KeyPredicatePrimaryKey.forTemplate("./id=${X}", id, one) != null, "an unquoted placeholder on an int key is recognized (the form a number is written in)");
		check(KeyPredicatePrimaryKey.forTemplate("./id=${X}", id) == null, "an unquoted placeholder on a string key is not valid XPath: no fast path, EBX answers as it always did");
		check(KeyPredicatePrimaryKey.forTemplate("./id=${X}", id, Collections.<KeyPredicatePrimaryKey.NumberParser>singletonList(null)) == null,
			"a string key (no parser) refuses the unquoted form");
		check(KeyPredicatePrimaryKey.forTemplate("./id=5", id, one) == null, "a literal is not a placeholder");
		check(KeyPredicatePrimaryKey.forTemplate("./id='${X}'", id, Arrays.asList(integer, decimal)) == null, "one parser per key field, no more");

		final KeyPredicatePrimaryKey key = KeyPredicatePrimaryKey.forTemplate("./id=${X}", id, one);
		check(Integer.valueOf(42).equals(key.keyValue(0, "42")), "42 -> 42");
		check(Integer.valueOf(-7).equals(key.keyValue(0, "-7")), "-7 -> -7");
		check(key.keyValue(0, "4.2") == null, "a decimal text is not an int: no fast path for the row");
		check(key.keyValue(0, "") == null, "an empty text is not a number");
		check(key.keyValue(0, "abc") == null, "a word is not a number");
		check(key.keyValue(0, "99999999999") == null, "an int out of range is not an int");
		check(key.keyValue(0, " 5") == null, "no blank is tolerated");
		check(key.missIsFinal(), "an int key that finds nothing has no record");

		final KeyPredicatePrimaryKey price = KeyPredicatePrimaryKey.forTemplate("./price='${P}'", Collections.singletonList("price"), Collections.singletonList(decimal));
		check(new java.math.BigDecimal("12.50").equals(price.keyValue(0, "12.50")), "a decimal text keeps its scale as written");
		check(!price.missIsFinal(), "a decimal key may be stored with another scale: a miss is checked with the XPath predicate");

		// a mixed composite key: the string field keeps its text, the number is parsed, the order is the key's
		final KeyPredicatePrimaryKey mixed = KeyPredicatePrimaryKey.forTemplate("./n=${N} and ./code='${C}'", Arrays.asList("code", "n"),
			Arrays.asList((KeyPredicatePrimaryKey.NumberParser) null, integer));
		check(mixed != null && mixed.getPlaceholdersInKeyOrder().equals(Arrays.asList("C", "N")), "composite key in key order");
		check("AB".equals(mixed.keyValue(0, "AB")) && Integer.valueOf(3).equals(mixed.keyValue(1, "3")), "string kept, number parsed");

		// the old two-argument entry point is the all-strings case
		check(KeyPredicatePrimaryKey.forTemplate("./a='${A}'", Collections.singletonList("a")).keyValue(0, "x").equals("x"), "string key: the text");
		check(KeyPredicatePrimaryKey.forTemplate("./a='${A}'", Collections.singletonList("a")).missIsFinal(), "string key: a miss is final");
	}

	/** Stand-in for the schema-bound parser of FeedKeyLookup: same shapes, plain Java parsing. */
	private static KeyPredicatePrimaryKey.NumberParser parser(final boolean pDecimal)
	{
		return new KeyPredicatePrimaryKey.NumberParser()
		{
			@Override
			public Object parse(final String pText)
			{
				try
				{
					if (pText == null || !pText.matches(pDecimal ? "-?[0-9]+(?:\\.[0-9]+)?" : "-?[0-9]+"))
					{
						return null;
					}
					return pDecimal ? (Object) new java.math.BigDecimal(pText) : (Object) Integer.valueOf(pText);
				}
				catch (final NumberFormatException ex)
				{
					return null;
				}
			}

			@Override
			public boolean missIsFinal()
			{
				return !pDecimal;
			}
		};
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
