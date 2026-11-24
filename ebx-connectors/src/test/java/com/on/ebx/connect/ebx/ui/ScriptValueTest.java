package com.on.ebx.connect.ebx.ui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audit C2-09: a connection identifier reaches the script of a page only as a literal that nothing in the value can break out of,
 * and the model refuses an identifier with such characters in the first place.
 */
public final class ScriptValueTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final String hostile = "x';</script><script>alert(1)</script>\\\"&\r\n  ";
		final String literal = FacetedPage.jsLiteral(hostile);
		check(literal.startsWith("'") && literal.endsWith("'"), "a quoted literal");
		final String inside = literal.substring(1, literal.length() - 1);
		for (final char forbidden : new char[] { '\'', '"', '<', '>', '&', '\\', '\r', '\n', ' ', ' ' })
		{
			if (forbidden == '\\')
			{
				continue;
			}
			check(inside.indexOf(forbidden) < 0, "no raw " + (int) forbidden + " in " + literal);
		}
		check(decode(inside).equals(hostile), "the literal denotes the original value: " + literal);
		check(FacetedPage.jsLiteral(null).equals("''") && FacetedPage.jsLiteral("plain-id.1").equals("'plain-id.1'"), "null and plain values");
		check(FacetedPage.unquote("a'b").equals("a\\u0027b"), "unquote for a literal already open");

		// the model: Connector/id carries a restrictive pattern
		final File xsd = new File(System.getProperty("ebx.connectors.moduleDir", "."), "src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd");
		final String text = new String(Files.readAllBytes(xsd.toPath()), StandardCharsets.UTF_8);
		final int idStart = text.indexOf("<xs:element name=\"id\"");
		final Matcher found = Pattern.compile("<xs:pattern value=\"([^\"]+)\"/>").matcher(text.substring(idStart, text.indexOf("</xs:element>", idStart)));
		check(found.find(), "Connector/id has a pattern");
		final Pattern id = Pattern.compile(found.group(1));
		check(id.matcher("GLEIF").matches() && id.matcher("my_conn-1.2").matches(), "ordinary identifiers pass");
		for (final String bad : new String[] { "a'b", "a</b", "a b", "a\nb", "a b", "", "a\"b", "a;b" })
		{
			check(!id.matcher(bad).matches(), "refused: " + bad.replace("\n", "\\n"));
		}
		check(id.matcher("12345678901234567890123456789012345678901").matches() == false, "41 characters refused");
		System.out.println("PASS: identifiers reach scripts as safe literals; Connector/id pattern " + found.group(1));
	}

	/** Reads back the {@code \}{@code uXXXX} escapes of a literal. */
	private static String decode(final String pText)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pText.length(); i++)
		{
			final char c = pText.charAt(i);
			if (c == '\\' && pText.charAt(i + 1) == 'u')
			{
				out.append((char) Integer.parseInt(pText.substring(i + 2, i + 6), 16));
				i += 5;
			}
			else
			{
				out.append(c);
			}
		}
		return out.toString();
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
