package com.on.ebx.connect.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Offline checks of {@link GoldenRecords#followMerge}: the bounded chain-following algorithm behind
 * {@link MameGoldenRecords#golden}, exercised with plain {@link
 * String} "records" so the hop-bounding and stop conditions are verified without EBX or the add-on.
 */
public final class GoldenRecordsChainTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		testNonMergedStopsImmediately();
		testUnmanagedStopsImmediately();
		testFollowsAShortChain();
		testNullTargetStopsAtTheLastNodeReached();
		testCycleIsBoundedAtTenHops();
		testExactlyTenHopsChainResolvesFully();
		System.out.println("PASS: GoldenRecords.followMerge (hop bounding, cycles, failure handling)");
	}

	private static void testNonMergedStopsImmediately()
	{
		final Map<String, String> states = new HashMap<String, String>();
		states.put("A", "GOLDEN");
		final Function<String, String> stateOf = fake(states);
		final Function<String, String> targetOf = fake(new HashMap<String, String>());
		check("GOLDEN".equals(states.get("A")), "sanity");
		check("A".equals(GoldenRecords.followMerge("A", stateOf, targetOf)), "a non-MERGED record is returned as is, no target lookup");
	}

	private static void testUnmanagedStopsImmediately()
	{
		final Map<String, String> states = new HashMap<String, String>();
		states.put("A", GoldenRecords.UNMANAGED);
		check("A".equals(GoldenRecords.followMerge("A", fake(states), fake(new HashMap<String, String>()))),
			"UNMANAGED is not MERGED: returned as is");
	}

	/** A -> B -> C, C is GOLDEN: resolves in two hops. */
	private static void testFollowsAShortChain()
	{
		final Map<String, String> states = new HashMap<String, String>();
		states.put("A", "MERGED");
		states.put("B", "MERGED");
		states.put("C", "GOLDEN");
		final Map<String, String> targets = new HashMap<String, String>();
		targets.put("A", "B");
		targets.put("B", "C");
		check("C".equals(GoldenRecords.followMerge("A", fake(states), fake(targets))), "follows MERGED -> MERGED -> GOLDEN to C");
	}

	/** A MERGED record whose target lookup failed (MameGoldenRecords turns any failure into a null
	 * target): stop at the last node actually reached, per the interface contract ("on any failure
	 * returns the last record reached"). */
	private static void testNullTargetStopsAtTheLastNodeReached()
	{
		final Map<String, String> states = new HashMap<String, String>();
		states.put("A", "MERGED");
		final Map<String, String> targets = new HashMap<String, String>(); // "A" -> no target (failure or none)
		check("A".equals(GoldenRecords.followMerge("A", fake(states), fake(targets))), "a null target stops at A, never null");
	}

	/** A cycle (A -> B -> A -> ...) never loops forever: bounded at MAX_HOPS (10) hops. */
	private static void testCycleIsBoundedAtTenHops()
	{
		final Map<String, String> states = new HashMap<String, String>();
		states.put("A", "MERGED");
		states.put("B", "MERGED");
		final Map<String, String> targets = new HashMap<String, String>();
		targets.put("A", "B");
		targets.put("B", "A");
		// 10 hops from A, alternating A/B/A/...: an even number of hops lands back on A.
		check("A".equals(GoldenRecords.followMerge("A", fake(states), fake(targets))), "a cycle is bounded, not infinite");
	}

	/** A chain of exactly 10 hops resolves fully to the terminal GOLDEN node. */
	private static void testExactlyTenHopsChainResolvesFully()
	{
		final Map<String, String> states = new HashMap<String, String>();
		final Map<String, String> targets = new HashMap<String, String>();
		for (int i = 0; i < 10; i++)
		{
			states.put("N" + i, "MERGED");
			targets.put("N" + i, "N" + (i + 1));
		}
		states.put("N10", "GOLDEN");
		check("N10".equals(GoldenRecords.followMerge("N0", fake(states), fake(targets))), "exactly 10 hops still resolves to the terminal node");
	}

	private static Function<String, String> fake(final Map<String, String> pMap)
	{
		return new Function<String, String>()
		{
			@Override
			public String apply(final String pKey)
			{
				return pMap.get(pKey);
			}
		};
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
