package com.on.ebx.connect.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Offline checks of the four landing-support transformations ({@code hash}, {@code now},
 * {@code runid}, {@code concat}) added so a feed-based (non-SQL) landing mapping can compute
 * {@code _sourceId}/{@code _rowHash}/load-run-id/received-at through an ordinary transformation
 * pipeline, without needing an EBX repository or session; plus {@code boolean}'s word vocabulary
 * (docs/TARGET-MAPPING.md sec 2.2/8, lot 0: the Factory's {@code PARSE_BOOLEAN} accepted
 * O/N/oui/non, and {@code boolean} must too before {@code PipelineCompat} can retire it).
 */
public final class TransformationsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		testHashIsConsistentAndDiscriminating();
		testNowShapeAndPatternArgument();
		testRunIdReflectsCurrentRun();
		testConcatEndToEndThroughMappingEngine();
		testConcatCoalescesMissingPathsToEmpty();
		testConcatRequiresAtLeastOnePathArgument();
		testBooleanAcceptsFrenchAndSingleLetterWords();
		testEpochDateReadsODataAndBareMilliseconds();
		testJoinWidthRebuildsAValueCutAtAFixedWidth();
		testReplaceWithQuotedSpaceRemovesInnerSpaces();
		testRegexGivesNullOnANonMatch();
		System.out.println("PASS: hash/now/runid/concat/boolean/epochdate/joinwidth/replace/regex");
	}

	/** Same input twice -> same digest; a different input -> a different digest. Exact digest text is not asserted. */
	private static void testHashIsConsistentAndDiscriminating() throws Exception
	{
		final Object h1 = Transformations.apply("hash", "Alpha", null);
		final Object h2 = Transformations.apply("hash", "Alpha", null);
		final Object h3 = Transformations.apply("hash", "Beta", null);
		check(h1 != null && h1.equals(h2), "hash('Alpha') is stable across calls: " + h1 + " vs " + h2);
		check(!h1.equals(h3), "hash('Alpha') must differ from hash('Beta'): " + h1 + " vs " + h3);
		check(h1.equals(Hashing.sha256("Alpha")), "hash(v) must equal Hashing.sha256(v) directly: " + h1);
	}

	/** No-arg output is the 19-character yyyy-MM-dd'T'HH:mm:ss shape; an explicit pattern argument is honoured. */
	private static void testNowShapeAndPatternArgument() throws Exception
	{
		final Object now = Transformations.apply("now", null, null);
		check(now instanceof String, "now() must return a String: " + now);
		final String s = (String) now;
		check(s.length() == 19, "now() must be 19 characters (yyyy-MM-dd'T'HH:mm:ss): '" + s + "'");
		check(s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"), "now() must match the ISO shape: '" + s + "'");
		check(s.charAt(4) == '-' && s.charAt(7) == '-' && s.charAt(10) == 'T' && s.charAt(13) == ':' && s.charAt(16) == ':',
			"now() separators must be at the isodatetime positions: '" + s + "'");

		final Object year = Transformations.apply("now(yyyy)", null, null);
		check(year instanceof String && ((String) year).length() == 4 && ((String) year).matches("\\d{4}"),
			"now(yyyy) must honour the explicit pattern argument: " + year);
	}

	/** runid() reflects ConnectorService's current-run id once a run has been explicitly started. */
	private static void testRunIdReflectsCurrentRun() throws Exception
	{
		final String runId = ConnectorService.beginRun();
		try
		{
			final Object reported = Transformations.apply("runid", null, new TransformationContext(null, null, null));
			check(runId.equals(reported), "runid() must reflect ConnectorService.currentRunId(): expected " + runId + " got " + reported);
		}
		finally
		{
			ConnectorService.endRun();
		}
	}

	/** concat(id,name)|hash through MappingEngine.compute(): the same chained pipeline a mapping rule would run. */
	private static void testConcatEndToEndThroughMappingEngine() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(1, "fixture", "landing",
			MappingDefinition.Direction.INBOUND, "fixture", "B", "D", "/root/Fixture", "", true);
		mapping.addRule(new MappingRule(1, "", "./_rowHash", "concat(id,name)|hash", MappingRule.Policy.ALWAYS, ""));

		final ObjectNode document = Json.object();
		document.put("id", 1);
		document.put("name", "Alpha");

		final TransformationContext context = new TransformationContext(null, null, null);
		final MappingEngine.Values values = MappingEngine.compute(mapping, document, context);

		check(values.getErrors().isEmpty(), "no errors expected: " + values.getErrors());
		final String expected = Hashing.sha256("1|Alpha");
		check(expected.equals(values.get("./_rowHash")), "concat(id,name)|hash must equal hash('1|Alpha'): expected " + expected + " got " + values.get("./_rowHash"));
	}

	/** A path that resolves to null/missing is coalesced to "", same as the SQL-side COALESCE(...,'') convention. */
	private static void testConcatCoalescesMissingPathsToEmpty() throws Exception
	{
		final MappingDefinition mapping = new MappingDefinition(2, "fixture", "landing",
			MappingDefinition.Direction.INBOUND, "fixture", "B", "D", "/root/Fixture", "", true);
		mapping.addRule(new MappingRule(1, "", "./_sourceId", "concat(id,missing)", MappingRule.Policy.ALWAYS, ""));

		final ObjectNode document = Json.object();
		document.put("id", "1");

		final MappingEngine.Values values = MappingEngine.compute(mapping, document, new TransformationContext(null, null, null));

		check(values.getErrors().isEmpty(), "no errors expected: " + values.getErrors());
		check("1|".equals(values.get("./_sourceId")), "concat(id,missing) on {id:'1'} must be '1|': got " + values.get("./_sourceId"));
	}

	/** concat() with zero arguments must throw, not silently return null or "". */
	private static void testConcatRequiresAtLeastOnePathArgument() throws Exception
	{
		final TransformationContext context = new TransformationContext(null, null, null);
		context.setDocumentRoot(Json.object());
		boolean threw = false;
		try
		{
			Transformations.apply("concat", "ignored", context);
		}
		catch (final IllegalArgumentException ex)
		{
			threw = true;
		}
		check(threw, "concat() with no path arguments must throw IllegalArgumentException");
	}

	/**
	 * {@code boolean} is checked, not assumed: the transformation's fallback is already
	 * {@code false} for anything unrecognised, so {@code non}/{@code no} produce the right answer
	 * whether or not they are explicitly registered — only the TRUE-meaning words (o, oui, y, yes,
	 * true, 1) can silently be wrong. Every word docs/TARGET-MAPPING.md sec 2.2/8 names is asserted
	 * here, case-insensitively and with surrounding whitespace, against its EXPECTED boolean, not
	 * merely "does not throw".
	 */
	private static void testBooleanAcceptsFrenchAndSingleLetterWords() throws Exception
	{
		final String[] trueWords = { "true", "1", "o", "O", " o ", "oui", "OUI", "y", "Y", "yes", "YES" };
		for (final String word : trueWords)
		{
			final Object result = Transformations.apply("boolean", word, null);
			check(Boolean.TRUE.equals(result), "boolean('" + word + "') must be true, got " + result);
		}
		final String[] falseWords = { "false", "0", "non", "NON", " non ", "no", "NO" };
		for (final String word : falseWords)
		{
			final Object result = Transformations.apply("boolean", word, null);
			check(Boolean.FALSE.equals(result), "boolean('" + word + "') must be false, got " + result);
		}
		check(Transformations.apply("boolean", null, null) == null, "boolean(null) must stay null");
		check(Transformations.apply("boolean", "", null) == null, "boolean('') must stay null (blank, not false)");
	}

	/** Factory B2B test 2026-10-23: SAP Business Partner CreationDate is an OData v2 JSON date; the offset form and bare milliseconds read the same, in UTC. */
	private static void testEpochDateReadsODataAndBareMilliseconds() throws Exception
	{
		check("2020-08-24".equals(Transformations.apply("epochdate", "/Date(1598227200000)/", null)), "epochdate on /Date(1598227200000)/ must be 2020-08-24");
		check("2020-08-24".equals(Transformations.apply("trim|epochdate", " /Date(1598227200000+0200)/ ", null)), "the +hhmm offset is ignored for the date part");
		check("2020-08-24".equals(Transformations.apply("epochdate", "/Date(1598227200000-0500)/", null)), "a -hhmm offset is ignored too");
		check("2020-08-24".equals(Transformations.apply("epochdate", "1598227200000", null)), "bare epoch milliseconds read the same");
		check("2020-08-24T00:00:00".equals(Transformations.apply("epochdate(yyyy-MM-dd'T'HH:mm:ss)", "/Date(1598227200000)/", null)),
			"an output pattern is honoured, in UTC");
		check("1969-12-31".equals(Transformations.apply("epochdate", "/Date(-1000)/", null)), "a negative value is before 1970");
		check(Transformations.apply("epochdate", "", null) == null && Transformations.apply("epochdate", null, null) == null, "blank -> null");
		boolean threw = false;
		try
		{
			Transformations.apply("epochdate", "24/08/2020", null);
		}
		catch (final IllegalArgumentException ex)
		{
			threw = true;
			check(!ex.getMessage().contains("24/08"), "the refusal never carries the value: " + ex.getMessage());
		}
		check(threw, "epochdate on a text date must throw (the row is refused, like date())");
	}

	/** Factory B2B test 2026-10-23: SAP cuts OrganizationBPName1 at 40 characters, the rest is in OrganizationBPName2. */
	private static void testJoinWidthRebuildsAValueCutAtAFixedWidth() throws Exception
	{
		final TransformationContext context = new TransformationContext(null, null, null);
		final ObjectNode inWord = Json.object();
		inWord.put("n1", "Compagnie G\u00e9n\u00e9rale de Chimie Druarimar E");
		inWord.put("n2", "urope SAS");
		context.setDocumentRoot(inWord);
		check("Compagnie G\u00e9n\u00e9rale de Chimie Druarimar Europe SAS".equals(Transformations.apply("joinwidth(40,n1,n2)", null, context)),
			"a piece of the full width is followed directly by the next: " + Transformations.apply("joinwidth(40,n1,n2)", null, context));
		final ObjectNode atSpace = Json.object();
		atSpace.put("n1", "Compagnie G\u00e9n\u00e9rale de M\u00e9dical Morelidov");
		atSpace.put("n2", "Europe SASU");
		context.setDocumentRoot(atSpace);
		check("Compagnie G\u00e9n\u00e9rale de M\u00e9dical Morelidov Europe SASU".equals(Transformations.apply("joinwidth(40,n1,n2)", null, context)),
			"a shorter piece (its space dropped at the cut) is followed by one space");
		final ObjectNode alone = Json.object();
		alone.put("n1", "Valmor Logistique SAS");
		alone.put("n2", "");
		context.setDocumentRoot(alone);
		check("Valmor Logistique SAS".equals(Transformations.apply("joinwidth(40,n1,n2)", null, context)), "an empty continuation adds nothing");
		context.setDocumentRoot(Json.object());
		check(Transformations.apply("joinwidth(40,n1,n2)", null, context) == null, "no piece -> null");
		boolean threw = false;
		try
		{
			Transformations.apply("joinwidth(n1,n2)", null, context);
		}
		catch (final IllegalArgumentException ex)
		{
			threw = true;
		}
		check(threw, "joinwidth without a numeric width must throw");
	}

	/** replace(' ','') parses (a quoted space, an empty quoted argument): "FR 747 741 994 42" -> "FR74774199442". */
	private static void testReplaceWithQuotedSpaceRemovesInnerSpaces() throws Exception
	{
		check("FR74774199442".equals(Transformations.apply("replace(' ','')|upper", "fr 747 741 994 42", null)), "replace(' ','') removes every space");
		check("FR50676952278".equals(Transformations.apply("replace(' ','')", "FR50676952278", null)), "a compact value is unchanged");
	}

	/** regex returns null on a non-match: regex('^[0-9]{9}$') keeps a SIREN and drops a VAT number held in the same column. */
	private static void testRegexGivesNullOnANonMatch() throws Exception
	{
		check("552100554".equals(Transformations.apply("regex('^[0-9]{9}$')", "552100554", null)), "a 9-digit value is kept whole");
		check(Transformations.apply("regex('^[0-9]{9}$')", "BE0801007390", null) == null, "a VAT number does not match: null");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
