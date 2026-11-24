package com.on.ebx.connect.core;

/**
 * Grammar coverage for {@link SampleDataUrl#parse}, the parser shared by {@code SampleFileConnector}
 * and {@code UploadedSampleFileWidgetFactory}: {@code data:<mime>[;params...];base64,<payload>} with
 * an optional {@code name} parameter (percent-encoded original file name). sample-file-name lot
 * (2026-09-23).
 */
public final class SampleDataUrlTest
{
	private SampleDataUrlTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// Absent "name" parameter: today's behaviour, unchanged — every value stored before this lot.
		final SampleDataUrl noName = SampleDataUrl.parse("data:text/csv;base64,aWQ7bmFtZQ==");
		check(noName != null, "a plain data:<mime>;base64,<payload> URL must parse");
		check("text/csv".equals(noName.mime()), "mime must round-trip");
		check(noName.name() == null, "no name parameter must parse as name() == null");
		check("aWQ7bmFtZQ==".equals(noName.payload()), "payload must be everything after the first comma");

		// name before base64.
		final SampleDataUrl namedFirst = SampleDataUrl.parse("data:application/json;name=Sample.json;base64,W10=");
		check(namedFirst != null && "Sample.json".equals(namedFirst.name()) && "application/json".equals(namedFirst.mime())
			&& "W10=".equals(namedFirst.payload()), "name-then-base64 order");

		// base64 before name: parameter order must not matter.
		final SampleDataUrl namedLast = SampleDataUrl.parse("data:application/json;base64;name=Sample.json,W10=");
		check(namedLast != null && "Sample.json".equals(namedLast.name()), "base64-then-name order must parse the same");

		// Percent-encoded name: accents, spaces, and a literal '%' (mirrors the widget's encodeURIComponent).
		final SampleDataUrl accented = SampleDataUrl.parse("data:text/csv;name=Donn%C3%A9es%20client%20%25.csv;base64,aWQ=");
		check(accented != null && "Données client %.csv".equals(accented.name()), "percent-encoded accents/space/percent must decode");

		// A literal '+' in the encoded name must survive as '+', not become a space (encodeURIComponent
		// always escapes '+' as %2B, so a raw '+' here is deliberately out of contract, but decode() must
		// not silently corrupt an otherwise well-formed value either way).
		final SampleDataUrl plusEncoded = SampleDataUrl.parse("data:text/csv;name=a%2Bb.csv;base64,aWQ=");
		check(plusEncoded != null && "a+b.csv".equals(plusEncoded.name()), "%2B must decode to a literal '+'");

		// Only the base name survives a path-prefixed name (forward or back slash).
		final SampleDataUrl pathPrefixed = SampleDataUrl.parse("data:text/csv;name=..%2F..%2Fetc%2Fpasswd;base64,aWQ=");
		check(pathPrefixed != null && "passwd".equals(pathPrefixed.name()), "only the base name must survive a path-prefixed name");
		final SampleDataUrl backslashPrefixed = SampleDataUrl.parse("data:text/csv;name=C%3A%5CUsers%5Cp%5Cdata.csv;base64,aWQ=");
		check(backslashPrefixed != null && "data.csv".equals(backslashPrefixed.name()), "a Windows-style backslash path must also be stripped to its base name");

		// A control character anywhere in the decoded name is refused: the name is treated as absent,
		// not the whole sample rejected.
		final SampleDataUrl controlChar = SampleDataUrl.parse("data:text/csv;name=bad%0Aname.csv;base64,aWQ=");
		check(controlChar != null && controlChar.name() == null, "a control character in the name must be refused (name absent), not fail the whole parse");

		// A malformed percent escape in the name must not fail the whole parse either: decode() answers
		// null, sanitizeName(null) answers null, mime/payload are unaffected.
		final SampleDataUrl badEscape = SampleDataUrl.parse("data:text/csv;name=%zz;base64,QQ==");
		check(badEscape != null && badEscape.name() == null && "text/csv".equals(badEscape.mime()) && "QQ==".equals(badEscape.payload()),
			"a malformed percent escape must drop only the name, not the rest of the parse");

		// Whitespace-only / empty name after stripping a path prefix is treated as absent.
		final SampleDataUrl blankName = SampleDataUrl.parse("data:text/csv;name=%2F;base64,aWQ=");
		check(blankName != null && blankName.name() == null, "a name that is only a path separator must leave nothing to keep");

		// Negative/refusal cases: never throw, answer null.
		check(SampleDataUrl.parse(null) == null, "null must parse to null");
		check(SampleDataUrl.parse("") == null, "an empty string must parse to null");
		check(SampleDataUrl.parse("   ") == null, "a blank string must parse to null");
		check(SampleDataUrl.parse("not-a-data-url") == null, "a non data: value must parse to null");
		check(SampleDataUrl.parse("data:text/csv;base64QQ==") == null, "a value with no comma must parse to null");
		check(SampleDataUrl.parse("data:text/csv;name=x.csv,QQ==") == null, "a data URL with no base64 parameter must be refused");
		check(SampleDataUrl.parse("data:;base64,QQ==") == null, "an empty media type must parse to null");

		// An unrecognised parameter is ignored, not rejected (tolerant, not exhaustive).
		final SampleDataUrl extraParam = SampleDataUrl.parse("data:text/csv;charset=UTF-8;base64,QQ==");
		check(extraParam != null && "text/csv".equals(extraParam.mime()) && extraParam.name() == null, "an unknown parameter must be ignored, not fail the parse");

		System.out.println("PASS: SampleDataUrl parses the optional name parameter (order, encoding, sanitization, and refusal cases)");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) { throw new AssertionError(pMessage); }
	}
}
