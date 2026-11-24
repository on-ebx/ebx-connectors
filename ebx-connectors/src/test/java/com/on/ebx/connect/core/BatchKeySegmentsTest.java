package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Where a feed batch is cut so that a repeated key finds the record its first occurrence made (audit C-20). */
public final class BatchKeySegmentsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		check(sizes("a", "b", "c").equals(Arrays.asList(3)), "different keys: one segment, no extra write");
		check(sizes("a", "b", "a").equals(Arrays.asList(2, 1)), "a key that comes back opens a new segment: " + sizes("a", "b", "a"));
		check(sizes("a", "a", "a").equals(Arrays.asList(1, 1, 1)), "three times the same key: three segments, each located after the previous was written");
		check(sizes("a", "b", "a", "b", "c").equals(Arrays.asList(2, 3)), "the segment after a cut starts clean: " + sizes("a", "b", "a", "b", "c"));
		check(sizes("a", "b", "a", "a").equals(Arrays.asList(2, 1, 1)), "" + sizes("a", "b", "a", "a"));
		check(sizes("", "", "x").equals(Arrays.asList(3)), "an empty key (a feed with no predicate) never repeats");
		check(sizes(null, null).equals(Arrays.asList(2)), "nor does a null key");
		check(BatchKeySegments.sizes(Collections.<String>emptyList()).isEmpty(), "an empty batch has no segment");
		final BatchKeySegments segments = new BatchKeySegments();
		check(!segments.startsNewSegment("k") && segments.startsNewSegment("k") && !segments.startsNewSegment("j"), "incremental use matches");
		System.out.println("BatchKeySegmentsTest OK");
	}

	private static List<Integer> sizes(final String... pKeys)
	{
		return BatchKeySegments.sizes(Arrays.asList(pKeys));
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
