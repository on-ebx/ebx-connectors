package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Volume lot 5, task 1: decides whether an import feed's {@code keyPredicate} template addresses a
 * table's whole primary key with simple equality clauses - one {@code ./field='${expr}'} per
 * primary-key field, nothing else - so {@code ConnectorService} can look the record up with
 * {@code AdaptationTable.lookupAdaptationByPrimaryKey} instead of a full XPath {@code Request} for
 * every row (measured 2026-09-23: this was the dominant per-row cost of loading an MDM Factory
 * landing table, where {@code keyPredicate} is exactly {@code ./_sourceId='${SOURCE_ID}'} and
 * {@code _sourceId} is the table's only primary-key field).
 * <p>
 * Pure string decision: no EBX type is used here (the caller supplies the primary-key field names
 * as plain strings, already read off {@code AdaptationTable.getPrimaryKeySpec()}), so this is unit
 * tested without a repository. The type-safety gate - a plain {@code xs:string} primary-key field, or
 * (audit C-10) an {@code xs:int}, {@code xs:integer} or {@code xs:decimal} one whose text {@link NumberParser}
 * turns into the Java value EBX keys it by; anything else (a boolean, a foreign key) falls back to the XPath
 * path, which already types and quotes correctly ({@code MappingEngine.predicate}) - lives in
 * {@link FeedKeyLookup}, which has the schema to check field types.
 */
final class KeyPredicatePrimaryKey
{
	/** {@code ./field='${expr}'}: group 1 is the field, group 2 the placeholder expression. */
	static final Pattern CLAUSE = Pattern.compile("^\\./([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*'\\$\\{([^}]+)\\}'$");
	/** {@code ./field=${expr}}, the form a numeric field is written in: group 1 is the field, group 2 the expression. */
	private static final Pattern CLAUSE_UNQUOTED = Pattern.compile("^\\./([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\$\\{([^}]+)\\}$");
	static final Pattern AND = Pattern.compile("\\s+and\\s+");

	/**
	 * Turns the text an item gives for a numeric primary-key field into the Java value EBX keys it by
	 * (what {@code SchemaNode.parseXsString} answers for the field), or null when the text is not a plain number
	 * of that type - the caller then asks EBX with the XPath predicate, as it always did.
	 */
	interface NumberParser
	{
		Object parse(String pText);

		/**
		 * False when a lookup that finds nothing proves nothing, so that the caller must still ask EBX with the
		 * XPath predicate: a decimal key may be stored with another scale than the text carries (5.0 and 5).
		 */
		boolean missIsFinal();
	}

	private final List<String> placeholdersInKeyOrder;
	/** One entry per primary-key field: null for a plain string, the parser of a numeric field otherwise. */
	private final List<NumberParser> parsers;

	private KeyPredicatePrimaryKey(final List<String> pPlaceholdersInKeyOrder, final List<NumberParser> pParsers)
	{
		this.placeholdersInKeyOrder = pPlaceholdersInKeyOrder;
		this.parsers = pParsers;
	}

	/**
	 * The value of one primary-key field for the lookup: the text itself for a string field, the parsed number for a
	 * numeric one, null when a numeric text is not a number of the field's type (then no fast path for this item).
	 */
	Object keyValue(final int pIndex, final String pText)
	{
		final NumberParser parser = this.parsers.get(pIndex);
		return parser == null ? pText : parser.parse(pText);
	}

	/** False when a miss by primary key must be double-checked with the XPath predicate (a decimal key field). */
	boolean missIsFinal()
	{
		for (final NumberParser parser : this.parsers)
		{
			if (parser != null && !parser.missIsFinal())
			{
				return false;
			}
		}
		return true;
	}

	/** The item's JSON path to read for each primary-key field, in the table's primary-key order. */
	List<String> getPlaceholdersInKeyOrder()
	{
		return this.placeholdersInKeyOrder;
	}

	/**
	 * @param pTemplate the mapping's {@code keyPredicate}, e.g. {@code ./_sourceId='${SOURCE_ID}'}, or
	 *        for a composite key {@code ./a='${A}' and ./b='${B}'} (order does not need to match the
	 *        primary key's own order: it is re-ordered onto it)
	 * @param pPrimaryKeyFields the table's primary-key field names, in primary-key order, without the
	 *        leading {@code ./}
	 * @return a matcher exposing the item placeholders in primary-key order, or {@code null} when the
	 *         template is not exactly one {@code ./field='${expr}'} clause per primary-key field (an
	 *         extra clause, a missing field, a repeated field, or any other predicate shape) - the
	 *         caller must fall back to the XPath lookup in that case
	 */
	static KeyPredicatePrimaryKey forTemplate(final String pTemplate, final List<String> pPrimaryKeyFields)
	{
		return KeyPredicatePrimaryKey.forTemplate(pTemplate, pPrimaryKeyFields, null);
	}

	/**
	 * As {@link #forTemplate(String, List)}, for a key that may hold numeric fields. {@code pNumberParsers} has one
	 * entry per primary-key field (same order as {@code pPrimaryKeyFields}), null for a plain string field and the
	 * parser of an int, integer or decimal field otherwise; a numeric field is accepted written with or without
	 * quotes ({@code ./id='${x}'} or {@code ./id=${x}}), a string field only with them.
	 */
	static KeyPredicatePrimaryKey forTemplate(final String pTemplate, final List<String> pPrimaryKeyFields, final List<NumberParser> pNumberParsers)
	{
		if (pTemplate == null || pPrimaryKeyFields == null || pPrimaryKeyFields.isEmpty())
		{
			return null;
		}
		final String trimmed = pTemplate.trim();
		if (trimmed.isEmpty())
		{
			return null;
		}
		final String[] clauses = KeyPredicatePrimaryKey.AND.split(trimmed);
		if (clauses.length != pPrimaryKeyFields.size())
		{
			return null;
		}
		if (pNumberParsers != null && pNumberParsers.size() != pPrimaryKeyFields.size())
		{
			return null;
		}
		final Map<String, String> placeholderByField = new LinkedHashMap<String, String>();
		final Map<String, Boolean> quotedByField = new LinkedHashMap<String, Boolean>();
		for (final String clause : clauses)
		{
			final String text = clause.trim();
			Matcher m = KeyPredicatePrimaryKey.CLAUSE.matcher(text);
			boolean quoted = true;
			if (!m.matches())
			{
				m = KeyPredicatePrimaryKey.CLAUSE_UNQUOTED.matcher(text);
				quoted = false;
				if (!m.matches())
				{
					return null;
				}
			}
			if (placeholderByField.put(m.group(1), m.group(2).trim()) != null)
			{
				return null; // the same field named twice
			}
			quotedByField.put(m.group(1), Boolean.valueOf(quoted));
		}
		final List<String> ordered = new ArrayList<String>(pPrimaryKeyFields.size());
		final List<NumberParser> parsers = new ArrayList<NumberParser>(pPrimaryKeyFields.size());
		for (int i = 0; i < pPrimaryKeyFields.size(); i++)
		{
			final String field = pPrimaryKeyFields.get(i);
			final String placeholder = placeholderByField.remove(field);
			if (placeholder == null)
			{
				return null; // a primary-key field has no clause
			}
			final NumberParser parser = pNumberParsers == null ? null : pNumberParsers.get(i);
			if (parser == null && !quotedByField.get(field).booleanValue())
			{
				return null; // a string compared without quotes is not valid XPath: EBX answers the error, as it always did
			}
			ordered.add(placeholder);
			parsers.add(parser);
		}
		if (!placeholderByField.isEmpty())
		{
			return null; // a clause named a field outside the primary key
		}
		return new KeyPredicatePrimaryKey(ordered, parsers);
	}
}
