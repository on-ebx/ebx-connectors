package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * A feed's {@code keyPredicate} that names a business key: one or several {@code ./field='${expr}'} clauses joined by
 * {@code and}, nothing else (audit C-10). It is what lets a feed whose key is not the table's primary key - the
 * pattern the module recommends, {@code ./sourceId='${customerId}'} on an auto-increment table - find its records
 * in an in-memory index built by one read of the table ({@link BusinessKeyIndex}), instead of one XPath scan per row
 * (CLAUDE.md section 5: the only index of an EBX 6.3 table is its primary key, every other predicate scans).
 * <p>
 * Pure string decision, parsed conservatively: a clause of any other shape (a function, a literal, another operator,
 * a path below the record, text around the placeholder) makes the whole template unrecognized, and the feed keeps
 * asking EBX with the XPath predicate. Whether each field is a plain string of the table is checked by the caller,
 * which has the schema.
 */
final class BusinessKeyPredicate
{
	private final List<String> fields;
	private final List<String> placeholders;

	private BusinessKeyPredicate(final List<String> pFields, final List<String> pPlaceholders)
	{
		this.fields = Collections.unmodifiableList(pFields);
		this.placeholders = Collections.unmodifiableList(pPlaceholders);
	}

	/** The key fields, without the leading {@code ./}, in the order of the template. */
	List<String> getFields()
	{
		return this.fields;
	}

	/** The item path read for each field, same order as {@link #getFields()}. */
	List<String> getPlaceholders()
	{
		return this.placeholders;
	}

	/** @return the parsed key, or null when the template is not made only of {@code ./field='${expr}'} clauses on distinct fields */
	static BusinessKeyPredicate parse(final String pTemplate)
	{
		if (pTemplate == null)
		{
			return null;
		}
		final String trimmed = pTemplate.trim();
		if (trimmed.isEmpty())
		{
			return null;
		}
		final List<String> fields = new ArrayList<String>();
		final List<String> placeholders = new ArrayList<String>();
		final Set<String> seen = new HashSet<String>();
		for (final String clause : KeyPredicatePrimaryKey.AND.split(trimmed))
		{
			final Matcher m = KeyPredicatePrimaryKey.CLAUSE.matcher(clause.trim());
			if (!m.matches())
			{
				return null;
			}
			if (!seen.add(m.group(1)))
			{
				return null; // the same field twice: not a key shape worth reasoning about
			}
			fields.add(m.group(1));
			placeholders.add(m.group(2).trim());
		}
		return new BusinessKeyPredicate(fields, placeholders);
	}
}
