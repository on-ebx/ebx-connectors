package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Splits the items of one feed batch where a key comes back (audit C-20).
 * <p>
 * The engine locates every item of a batch before it writes any of them, so two items with the same key both
 * find no record and each creates one. Cutting the batch at the second occurrence - the first part is written,
 * then the repeated item is located - lets it find the record the first one made and update it instead.
 * A batch whose keys are all different is never cut and costs one set insertion per item.
 * <p>
 * The key is the resolved key predicate of the item; an empty key (a feed with no predicate) never repeats.
 * Pure: no EBX type ({@code BatchKeySegmentsTest}).
 */
final class BatchKeySegments
{
	private final Set<String> seen = new HashSet<String>();

	/**
	 * True when {@code pKey} was already met in the current segment: the caller writes what it holds first, and
	 * the item then opens the next segment.
	 */
	boolean startsNewSegment(final String pKey)
	{
		if (pKey == null || pKey.isEmpty())
		{
			return false;
		}
		if (this.seen.add(pKey))
		{
			return false;
		}
		this.seen.clear();
		this.seen.add(pKey);
		return true;
	}

	/** The sizes of the segments the keys fall into, in order; for tests and for reasoning about a batch. */
	static List<Integer> sizes(final List<String> pKeys)
	{
		final BatchKeySegments segments = new BatchKeySegments();
		final List<Integer> sizes = new ArrayList<Integer>();
		int current = 0;
		for (final String key : pKeys)
		{
			if (segments.startsNewSegment(key))
			{
				sizes.add(Integer.valueOf(current));
				current = 0;
			}
			current++;
		}
		if (current > 0)
		{
			sizes.add(Integer.valueOf(current));
		}
		return sizes;
	}
}
