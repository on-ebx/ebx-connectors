package com.on.ebx.connect.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Business key to record key, for the records of one table (audit C-10). Built once per run by one streaming read of
 * the table, then asked instead of EBX: an XPath predicate on a field that is not the primary key scans the whole
 * table, once per feed row (CLAUDE.md section 5), where a hash lookup costs nothing (section 15: the master reload
 * that took 263 s took 36 s once its per-row lookups were replaced by one read per table joined in memory).
 * <p>
 * It answers exactly what the XPath predicate {@code ./f1='v1' and ./f2='v2'} would, or says it cannot:
 * <ul>
 * <li>a value that is null or empty never matches and a record whose value is null or empty is never indexed - EBX
 * stores an empty string as null, so {@code ./f=''} finds nothing;</li>
 * <li>two records with the same key make the key {@link Kind#AMBIGUOUS}: the XPath returns the first of them in
 * its own order, which the index does not know, so the caller asks EBX for that key;</li>
 * <li>the index holds at most {@code pMaxRows} records; past that it is {@link #isOverflowed() overflowed}, empty,
 * and the caller must not use it.</li>
 * </ul>
 * The record key is an opaque value of the caller ({@code V}); equal values for one business key are the same
 * record, so adding a record again (after a write) is idempotent.
 */
final class BusinessKeyIndex<V>
{
	enum Kind
	{
		/** No record holds this key. */
		NONE,
		/** One record holds it: {@link Match#getRecord()}. */
		ONE,
		/** Several records hold it: ask EBX. */
		AMBIGUOUS
	}

	static final class Match<V>
	{
		private static final Match<Object> NONE = new Match<Object>(Kind.NONE, null);
		private static final Match<Object> AMBIGUOUS = new Match<Object>(Kind.AMBIGUOUS, null);

		private final Kind kind;
		private final V record;

		private Match(final Kind pKind, final V pRecord)
		{
			this.kind = pKind;
			this.record = pRecord;
		}

		static <V> Match<V> one(final V pRecord)
		{
			return new Match<V>(Kind.ONE, pRecord);
		}

		@SuppressWarnings("unchecked")
		static <V> Match<V> none()
		{
			return (Match<V>) Match.NONE;
		}

		@SuppressWarnings("unchecked")
		static <V> Match<V> ambiguous()
		{
			return (Match<V>) Match.AMBIGUOUS;
		}

		Kind getKind()
		{
			return this.kind;
		}

		V getRecord()
		{
			return this.record;
		}
	}

	private final int maxRows;
	private final Map<String, Object> byKey = new HashMap<String, Object>();
	private boolean overflowed;

	/** Marks a key held by several records. */
	private static final Object MANY = new Object();

	BusinessKeyIndex(final int pMaxRows)
	{
		this.maxRows = pMaxRows;
	}

	/**
	 * The map key of a list of values: each value is prefixed with its length, so that no two different lists give the
	 * same text whatever characters the values hold. Null when a value is null or empty (it can never match).
	 */
	static String compose(final List<String> pValues)
	{
		final StringBuilder b = new StringBuilder();
		for (final String value : pValues)
		{
			if (value == null || value.isEmpty())
			{
				return null;
			}
			b.append(value.length()).append(':').append(value);
		}
		return b.toString();
	}

	/** Records that {@code pRecord} holds the key {@code pValues}; a key that is null or empty is not indexable and is ignored. */
	void add(final List<String> pValues, final V pRecord)
	{
		if (this.overflowed)
		{
			return;
		}
		final String key = BusinessKeyIndex.compose(pValues);
		if (key == null)
		{
			return;
		}
		final Object held = this.byKey.get(key);
		if (held == null)
		{
			if (this.byKey.size() >= this.maxRows)
			{
				this.overflowed = true;
				this.byKey.clear();
				return;
			}
			this.byKey.put(key, pRecord);
		}
		else if (held != BusinessKeyIndex.MANY && !held.equals(pRecord))
		{
			this.byKey.put(key, BusinessKeyIndex.MANY);
		}
	}

	/** What the XPath predicate on {@code pValues} would find. */
	@SuppressWarnings("unchecked")
	Match<V> find(final List<String> pValues)
	{
		final String key = BusinessKeyIndex.compose(pValues);
		if (key == null)
		{
			return (Match<V>) Match.NONE;
		}
		final Object held = this.byKey.get(key);
		if (held == null)
		{
			return (Match<V>) Match.NONE;
		}
		if (held == BusinessKeyIndex.MANY)
		{
			return (Match<V>) Match.AMBIGUOUS;
		}
		return new Match<V>(Kind.ONE, (V) held);
	}

	/**
	 * Forgets a key whose record no longer holds it (the caller read the record and found another value). The caller
	 * then asks EBX for that key and {@link #add adds} what it finds.
	 */
	void forget(final List<String> pValues)
	{
		final String key = BusinessKeyIndex.compose(pValues);
		if (key != null)
		{
			this.byKey.remove(key);
		}
	}

	boolean isOverflowed()
	{
		return this.overflowed;
	}

	int size()
	{
		return this.byKey.size();
	}
}
