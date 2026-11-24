package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.onwbp.adaptation.XPathExpressionHelper;

/**
 * Audit C2-04 and C2-05: a value that is substituted into a template is DATA, never code. Two templates are concerned,
 * both written as text by a mapping author and filled with values of the source or of an EBX record:
 * <ul>
 * <li>a feed's {@code keyPredicate} (an XPath predicate, {@code ./id='${id}'}): every placeholder must sit inside a
 * string literal; the literal is rebuilt as a whole and quoted by EBX's own
 * {@link XPathExpressionHelper#encodeLiteralStringWithDelimiters(String)}, so no value can leave it (an apostrophe,
 * a quotation mark, both, a value that looks like an expression);</li>
 * <li>the {@code sql} input of a JDBC operation: every {@code ${...}} becomes a bound parameter ({@code ?} through the
 * named parameter {@code :__bN}), a quoted {@code '${x}'} included (the quotes go, the parameter is the literal). The
 * one position where a parameter is impossible is an identifier ({@code GRANT ... ON ${asset} TO ${user}}): the value
 * is then written only when it is a plain, optionally dotted, identifier, otherwise the statement is refused.</li>
 * </ul>
 * Pure, no EBX session: {@code TemplatesTest}. Refusals are bilingual (EN / FR) and name the expected form.
 */
public final class ValueTemplates
{
	private ValueTemplates() { }

	/** What a placeholder stands for: its expression (the text between {@code ${} and {@code }}), answered as text. */
	public interface Values
	{
		String of(String pExpression);
	}

	/** Prefix of the named parameters this class generates; the JDBC connector binds them as typed values, never as guessed text. */
	public static final String BOUND_PREFIX = "__b";

	private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
	private static final Pattern BOOLEAN = Pattern.compile("true|false|0|1");
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)*");
	private static final Pattern IDENTIFIER_KEYWORD = Pattern.compile("(?is).*\\b(?:from|join|into|update|table|on|to|view|schema|database|user|role)\\s+$");

	// ------------------------------------------------------------------ scanner

	private enum Kind { TEXT, PLACEHOLDER, LITERAL, COMMENT }

	/** One piece of a template: raw text, a placeholder outside any quote, a quoted run (parts + whether it was closed) or a SQL comment. */
	private static final class Token
	{
		final Kind kind;
		final String raw;
		final char quote;
		/** LITERAL: unescaped text and placeholders in order; a part is {@code [0, text]} or {@code [1, expression]}. */
		final List<String[]> parts = new ArrayList<String[]>();
		boolean closed = true;
		boolean hasPlaceholder;

		Token(final Kind pKind, final String pRaw, final char pQuote)
		{
			this.kind = pKind;
			this.raw = pRaw;
			this.quote = pQuote;
		}
	}

	/** Index of the brace closing the placeholder whose expression starts at {@code pFrom}, or -1 (braces nest: {@code ${default(${a})}}). */
	private static int closingBrace(final String pText, final int pFrom)
	{
		int depth = 0;
		for (int i = pFrom; i < pText.length(); i++)
		{
			final char c = pText.charAt(i);
			if (c == '$' && i + 1 < pText.length() && pText.charAt(i + 1) == '{')
			{
				depth++;
				i++;
			}
			else if (c == '}')
			{
				if (depth == 0)
				{
					return i;
				}
				depth--;
			}
		}
		return -1;
	}

	private static List<Token> scan(final String pTemplate, final boolean pSql)
	{
		final List<Token> tokens = new ArrayList<Token>();
		final StringBuilder text = new StringBuilder();
		int i = 0;
		final int n = pTemplate.length();
		while (i < n)
		{
			final char c = pTemplate.charAt(i);
			if (c == '$' && i + 1 < n && pTemplate.charAt(i + 1) == '{' && closingBrace(pTemplate, i + 2) >= 0)
			{
				ValueTemplates.flush(tokens, text);
				final int close = closingBrace(pTemplate, i + 2);
				final Token t = new Token(Kind.PLACEHOLDER, pTemplate.substring(i + 2, close), '\0');
				tokens.add(t);
				i = close + 1;
			}
			else if (c == '\'' || c == '"' || pSql && c == '`')
			{
				ValueTemplates.flush(tokens, text);
				final Token literal = new Token(Kind.LITERAL, null, c);
				final StringBuilder part = new StringBuilder();
				int j = i + 1;
				boolean closed = false;
				while (j < n)
				{
					final char d = pTemplate.charAt(j);
					if (d == '$' && j + 1 < n && pTemplate.charAt(j + 1) == '{' && closingBrace(pTemplate, j + 2) >= 0)
					{
						if (part.length() > 0)
						{
							literal.parts.add(new String[] { "0", part.toString() });
							part.setLength(0);
						}
						final int close = closingBrace(pTemplate, j + 2);
						literal.parts.add(new String[] { "1", pTemplate.substring(j + 2, close) });
						literal.hasPlaceholder = true;
						j = close + 1;
					}
					else if (d == c)
					{
						if (j + 1 < n && pTemplate.charAt(j + 1) == c)
						{
							part.append(c);
							j += 2;
						}
						else
						{
							closed = true;
							j++;
							break;
						}
					}
					else
					{
						part.append(d);
						j++;
					}
				}
				if (part.length() > 0)
				{
					literal.parts.add(new String[] { "0", part.toString() });
				}
				literal.closed = closed;
				final Token t = new Token(Kind.LITERAL, pTemplate.substring(i, j), c);
				t.parts.addAll(literal.parts);
				t.closed = closed;
				t.hasPlaceholder = literal.hasPlaceholder;
				tokens.add(t);
				i = j;
			}
			else if (pSql && c == '-' && i + 1 < n && pTemplate.charAt(i + 1) == '-')
			{
				ValueTemplates.flush(tokens, text);
				int j = pTemplate.indexOf('\n', i);
				j = j < 0 ? n : j;
				tokens.add(new Token(Kind.COMMENT, pTemplate.substring(i, j), '\0'));
				i = j;
			}
			else if (pSql && c == '/' && i + 1 < n && pTemplate.charAt(i + 1) == '*')
			{
				ValueTemplates.flush(tokens, text);
				int j = pTemplate.indexOf("*/", i + 2);
				j = j < 0 ? n : j + 2;
				tokens.add(new Token(Kind.COMMENT, pTemplate.substring(i, j), '\0'));
				i = j;
			}
			else
			{
				text.append(c);
				i++;
			}
		}
		ValueTemplates.flush(tokens, text);
		return tokens;
	}

	private static void flush(final List<Token> pTokens, final StringBuilder pText)
	{
		if (pText.length() > 0)
		{
			pTokens.add(new Token(Kind.TEXT, pText.toString(), '\0'));
			pText.setLength(0);
		}
	}

	private static String assemble(final Token pLiteral, final Values pValues)
	{
		final StringBuilder value = new StringBuilder();
		for (final String[] part : pLiteral.parts)
		{
			value.append("0".equals(part[0]) ? part[1] : String.valueOf(pValues.of(part[1])));
		}
		return value.toString();
	}

	private static String text(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	// ------------------------------------------------------------------ XPath

	/**
	 * The predicate with every placeholder replaced by a correctly quoted literal. A placeholder inside a literal
	 * ({@code './id=\'${x}\''}, with or without text around it) gives one literal holding the whole text; a placeholder outside
	 * any literal (a template written before this rule) keeps a number or a boolean as it is and is quoted as a literal
	 * otherwise, so what it can no longer do is leave the value position.
	 */
	public static String xpath(final String pTemplate, final Values pValues)
	{
		if (pTemplate == null || pTemplate.trim().isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		for (final Token t : ValueTemplates.scan(pTemplate, false))
		{
			if (t.kind == Kind.TEXT)
			{
				out.append(t.raw);
			}
			else if (t.kind == Kind.PLACEHOLDER)
			{
				final String value = ValueTemplates.text(pValues.of(t.raw));
				out.append(NUMBER.matcher(value).matches() || BOOLEAN.matcher(value).matches() ? value : ValueTemplates.literal(value));
			}
			else if (!t.hasPlaceholder)
			{
				out.append(t.raw);
			}
			else
			{
				out.append(ValueTemplates.literal(ValueTemplates.assemble(t, pValues)));
			}
		}
		return out.toString();
	}

	/** A quoted XPath string literal, delimiters included, as EBX itself writes it. */
	public static String literal(final String pValue)
	{
		return XPathExpressionHelper.encodeLiteralStringWithDelimiters(ValueTemplates.text(pValue));
	}

	/**
	 * Why a key predicate cannot be saved, or null. Every placeholder must be inside a string literal; the message shows the
	 * expected form. Empty and placeholder-free predicates are fine.
	 */
	public static String xpathRefusal(final String pTemplate)
	{
		if (pTemplate == null)
		{
			return null;
		}
		for (final Token t : ValueTemplates.scan(pTemplate, false))
		{
			if (t.kind == Kind.PLACEHOLDER)
			{
				return "KEY_PREDICATE_PLACEHOLDER_UNQUOTED: ${" + t.raw.trim() + "} must be written between apostrophes in the key predicate, e.g. ./id='${" + t.raw.trim()
					+ "}' (a value outside a string literal could become part of the XPath expression)."
					+ " / FR: ${" + t.raw.trim() + "} doit etre ecrit entre apostrophes dans le predicat de cle, par exemple ./id='${" + t.raw.trim()
					+ "}' (une valeur hors d'une chaine pourrait devenir une partie de l'expression XPath).";
			}
			if (t.kind == Kind.LITERAL && t.hasPlaceholder && !t.closed)
			{
				return "KEY_PREDICATE_LITERAL_UNCLOSED: a string literal of the key predicate is not closed (" + t.quote + "), e.g. ./id='${id}'."
					+ " / FR: une chaine du predicat de cle n'est pas fermee (" + t.quote + "), par exemple ./id='${id}'.";
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ SQL

	/**
	 * The statement with every placeholder bound: {@code ${x}} and {@code '${x}'} become {@code :__bN} and the value goes in
	 * {@code pBound} under that name. A placeholder in an identifier position is written as text when the value is a plain
	 * identifier; otherwise {@link IllegalArgumentException} (a coded, bilingual message) - the statement never runs. A
	 * placeholder inside a comment is dropped.
	 */
	public static String sql(final String pTemplate, final Values pValues, final Map<String, String> pBound)
	{
		if (pTemplate == null)
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		final List<Token> tokens = ValueTemplates.scan(pTemplate, true);
		for (int k = 0; k < tokens.size(); k++)
		{
			final Token t = tokens.get(k);
			if (t.kind == Kind.TEXT)
			{
				out.append(t.raw);
			}
			else if (t.kind == Kind.COMMENT)
			{
				out.append(t.raw.replaceAll("\\$\\{[^}]*\\}", ""));
			}
			else if (t.kind == Kind.PLACEHOLDER)
			{
				final String value = ValueTemplates.text(pValues.of(t.raw));
				final boolean after = IDENTIFIER_KEYWORD.matcher(out).matches() || out.length() > 0 && ValueTemplates.identifierChar(out.charAt(out.length() - 1));
				final Token next = k + 1 < tokens.size() ? tokens.get(k + 1) : null;
				final boolean before = next != null && next.kind == Kind.TEXT && !next.raw.isEmpty() && ValueTemplates.identifierChar(next.raw.charAt(0));
				if (after || before)
				{
					out.append(ValueTemplates.identifier(t.raw, value));
				}
				else
				{
					out.append(ValueTemplates.bind(pBound, value));
				}
			}
			else if (!t.hasPlaceholder)
			{
				out.append(t.raw);
			}
			else if (t.quote == '\'')
			{
				out.append(ValueTemplates.bind(pBound, ValueTemplates.assemble(t, pValues)));
			}
			else
			{
				final String name = ValueTemplates.assemble(t, pValues);
				out.append(t.quote).append(ValueTemplates.identifier(name, name)).append(t.quote);
			}
		}
		return out.toString();
	}

	private static boolean identifierChar(final char pChar)
	{
		return Character.isLetterOrDigit(pChar) || pChar == '_' || pChar == '$' || pChar == '.';
	}

	private static String bind(final Map<String, String> pBound, final String pValue)
	{
		final String name = ValueTemplates.BOUND_PREFIX + pBound.size();
		pBound.put(name, pValue);
		return ":" + name;
	}

	private static String identifier(final String pExpression, final String pValue)
	{
		if (!IDENTIFIER.matcher(pValue).matches())
		{
			throw new IllegalArgumentException("SQL_PLACEHOLDER_NOT_BINDABLE: ${" + pExpression.trim() + "} stands where SQL accepts no parameter (a table, schema or user name) and its value is not a plain identifier"
				+ " (letters, digits, _ and $, parts joined by dots); the statement was not run. Put the placeholder where a value goes (= ${x}, IN (${x}), '${x}') or give a plain identifier."
				+ " / FR: ${" + pExpression.trim() + "} est a une place ou SQL n'accepte aucun parametre (nom de table, de schema ou d'utilisateur) et sa valeur n'est pas un identifiant simple"
				+ " (lettres, chiffres, _ et $, parties separees par des points); l'instruction n'a pas ete executee. Mettez le placeholder a une place de valeur (= ${x}, IN (${x}), '${x}') ou fournissez un identifiant simple.");
		}
		return pValue;
	}

	/** Why an {@code sql} input cannot be saved, or null: a quoted run holding a placeholder that is never closed. */
	public static String sqlRefusal(final String pTemplate)
	{
		if (pTemplate == null)
		{
			return null;
		}
		for (final Token t : ValueTemplates.scan(pTemplate, true))
		{
			if (t.kind == Kind.LITERAL && t.hasPlaceholder && !t.closed)
			{
				return "SQL_LITERAL_UNCLOSED: a quoted run of the sql input holding ${...} is not closed (" + t.quote + "); write '${x}' with both quotes, or ${x} alone."
					+ " / FR: une chaine de l'entree sql contenant ${...} n'est pas fermee (" + t.quote + "); ecrivez '${x}' avec les deux apostrophes, ou ${x} seul.";
			}
		}
		return null;
	}
}
