package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows of one child table, grouped by the parent they are linked to, as read once for a whole batch of parents
 * (audit C-11). A sub-object mapping used to ask EBX, inside the batch's write procedure, for every child element
 * "the row linked to this parent whose key fields equal these values": an XPath scan of the child table per element,
 * alternating with the writes of the same table, which is the pattern that forces an index refresh (CLAUDE.md
 * section 5). Here the existing rows of the batch's parents are read before the procedure and the elements are
 * matched in memory, exactly as the XPath would: a value that is null or empty never matches (EBX stores an empty
 * string as null), the first row wins, and two rows that both match leave the answer to EBX ({@link
 * BusinessKeyIndex.Kind#AMBIGUOUS}), which knows its own order.
 * <p>
 * Two layers: the rows read ({@code base}, never changed) and what the current write attempt did to them
 * ({@code work}, per parent, copied on first touch). A row created by the attempt is appended (it is matched, never
 * stale, and a key held by two rows is left to EBX anyway); a row moved from one parent to another makes the
 * receiving parent unknown, since its place in EBX's order is not known. A write attempt that is rolled back and retried by
 * {@link BatchCommit} calls {@link #beginAttempt()}, which forgets the overlay: the rows are as they were read.
 * <p>
 * {@code R} is the row (an {@code Adaptation} in the module); {@code pId} identifies it (its {@code AdaptationName}).
 */
final class ChildRowGroups<R>
{
	private static final class Entry<R>
	{
		final Object id;
		final R row;
		final List<String> keys;

		Entry(final Object pId, final R pRow, final List<String> pKeys)
		{
			this.id = pId;
			this.row = pRow;
			this.keys = pKeys;
		}
	}

	private final Map<String, List<Entry<R>>> base = new HashMap<String, List<Entry<R>>>();
	private final Map<String, List<Entry<R>>> work = new HashMap<String, List<Entry<R>>>();
	/** Parents that received a row from another parent in this attempt: their order is no longer the one EBX has. */
	private final java.util.Set<String> unordered = new java.util.HashSet<String>();

	/** Says that this parent's rows were read: it is known, with none yet. */
	void declareParent(final String pParentKey)
	{
		if (!this.base.containsKey(pParentKey))
		{
			this.base.put(pParentKey, new ArrayList<Entry<R>>());
		}
	}

	/** A row read before the write; its parent must have been declared. */
	void addRead(final String pParentKey, final Object pId, final R pRow, final List<String> pKeys)
	{
		this.declareParent(pParentKey);
		this.base.get(pParentKey).add(new Entry<R>(pId, pRow, pKeys));
	}

	/** Whether the rows of this parent are known: read, or declared new in this attempt. */
	boolean knows(final String pParentKey)
	{
		return !this.unordered.contains(pParentKey) && (this.work.containsKey(pParentKey) || this.base.containsKey(pParentKey));
	}

	/** A parent created by the current attempt has no row yet; it is known to have none. */
	void declareNewParent(final String pParentKey)
	{
		if (!this.knows(pParentKey))
		{
			this.work.put(pParentKey, new ArrayList<Entry<R>>());
		}
	}

	/** A new write attempt: forget what the previous one did. */
	void beginAttempt()
	{
		this.work.clear();
		this.unordered.clear();
	}

	private List<Entry<R>> current(final String pParentKey)
	{
		final List<Entry<R>> own = this.work.get(pParentKey);
		return own != null ? own : this.base.get(pParentKey);
	}

	private List<Entry<R>> mutable(final String pParentKey)
	{
		List<Entry<R>> own = this.work.get(pParentKey);
		if (own == null)
		{
			final List<Entry<R>> read = this.base.get(pParentKey);
			own = read == null ? new ArrayList<Entry<R>>() : new ArrayList<Entry<R>>(read);
			this.work.put(pParentKey, own);
		}
		return own;
	}

	/** What the XPath {@code link=parent and key1=v1 and ...} would find: no row, one, or several (ask EBX). */
	BusinessKeyIndex.Match<R> find(final String pParentKey, final List<String> pValues)
	{
		for (final String value : pValues)
		{
			if (value == null || value.isEmpty())
			{
				return BusinessKeyIndex.Match.none();
			}
		}
		final List<Entry<R>> rows = this.current(pParentKey);
		R found = null;
		for (final Entry<R> entry : rows == null ? new ArrayList<Entry<R>>() : rows)
		{
			if (entry.keys.equals(pValues))
			{
				if (found != null)
				{
					return BusinessKeyIndex.Match.ambiguous();
				}
				found = entry.row;
			}
		}
		return found == null ? BusinessKeyIndex.Match.<R>none() : BusinessKeyIndex.Match.one(found);
	}

	/** Every row now linked to this parent, in the order read (rows made by the attempt last). */
	List<R> rows(final String pParentKey)
	{
		final List<R> out = new ArrayList<R>();
		final List<Entry<R>> rows = this.current(pParentKey);
		if (rows != null)
		{
			for (final Entry<R> entry : rows)
			{
				out.add(entry.row);
			}
		}
		return out;
	}

	/**
	 * What the write did to a row. {@code pOldId} is the row it replaces and {@code pOldParent} the parent it was found
	 * under (both null for a creation), {@code pLinkedTo} the parent the row is linked to now, as read back from the
	 * row. A changed row keeps its place in its group; one that moved to another parent leaves it and enters the
	 * group of {@code pLinkedTo} when that parent is known; a row linked to an unknown parent is in no group, as it
	 * would be in no result of the XPath of a known one.
	 */
	void wrote(final Object pOldId, final String pOldParent, final String pLinkedTo, final Object pId, final R pRow, final List<String> pKeys)
	{
		final Entry<R> entry = new Entry<R>(pId, pRow, pKeys);
		if (pOldId != null)
		{
			final List<Entry<R>> rows = this.current(pOldParent);
			for (int i = 0; rows != null && i < rows.size(); i++)
			{
				if (pOldId.equals(rows.get(i).id))
				{
					if (pOldParent.equals(pLinkedTo))
					{
						this.mutable(pOldParent).set(i, entry);
						return;
					}
					this.mutable(pOldParent).remove(i);
					break;
				}
			}
		}
		if (pLinkedTo != null && this.knows(pLinkedTo))
		{
			if (pOldId != null)
			{
				// a row that was somewhere else has a place among the others that is its key's, which is not known here:
				// the rows of this parent are matched by EBX from now on
				this.unordered.add(pLinkedTo);
			}
			else
			{
				this.mutable(pLinkedTo).add(entry);
			}
		}
	}
}
