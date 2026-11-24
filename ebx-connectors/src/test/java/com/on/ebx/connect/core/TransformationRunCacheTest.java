package com.on.ebx.connect.core;

import java.util.regex.Pattern;

import com.onwbp.adaptation.AdaptationTable;

/**
 * Volume lot 5, task 4: the per-run caches behind the transformation pipeline - no EBX repository
 * needed, since {@link TransformationRunCache} only maps plain string keys to values (an
 * {@link AdaptationTable} reference is never dereferenced here, only stored and returned).
 */
public final class TransformationRunCacheTest
{
	private TransformationRunCacheTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// pipelines: the same pipeline string is parsed once and the identical parsed instance is reused
		final TransformationRunCache cache = new TransformationRunCache();
		final TransformationPipeline first = cache.pipeline("trim|left(255)");
		final TransformationPipeline second = cache.pipeline("trim|left(255)");
		check(first == second, "the same pipeline string must return the identical cached parse, not a fresh one");
		check(first.getSteps().size() == 2, "the cached parse must still be correct: two steps");
		check("trim".equals(first.getSteps().get(0).getName()), "first step name");
		check("left".equals(first.getSteps().get(1).getName()), "second step name");
		check("255".equals(first.getSteps().get(1).getArgs().get(0)), "second step argument");
		final TransformationPipeline different = cache.pipeline("upper");
		check(different != first, "a different pipeline string must not reuse another string's cached parse");

		// regex patterns: the same regex string returns the identical compiled Pattern
		final Pattern p1 = cache.pattern("[0-9]+");
		final Pattern p2 = cache.pattern("[0-9]+");
		check(p1 == p2, "the same regex string must return the identical compiled Pattern, not recompile it");
		check(p1.matcher("abc123").find(), "sanity: the cached Pattern must still work");

		// tables: distinguishes "never looked up" from "looked up and resolved to null (not found)"
		check(!cache.hasTable("k"), "a key never put must not be reported as cached");
		check(cache.table("k") == null, "an uncached key reads as null (same as a cached miss - callers must check hasTable first)");
		final AdaptationTable notFound = null;
		cache.putTable("k", notFound);
		check(cache.hasTable("k"), "after putTable(null), the key must be reported cached (a negative result)");
		check(cache.table("k") == null, "the cached negative result must read back as null");

		// lookup(...) memo: same null-vs-not-cached distinction, by value this time
		check(!cache.hasLookup("FR"), "a lookup key never put must not be reported as cached");
		cache.putLookup("FR", "France");
		check(cache.hasLookup("FR"), "a put lookup key must be reported cached");
		check("France".equals(cache.lookup("FR")), "a cached lookup value must read back exactly");
		cache.putLookup("ZZ", null); // "looked up, no matching record"
		check(cache.hasLookup("ZZ"), "a cached null lookup result must still be reported cached");
		check(cache.lookup("ZZ") == null, "a cached null lookup result must read back as null, not the internal sentinel");

		// the lookup memo is bounded: pushing well past capacity must not grow it without limit
		final TransformationRunCache bounded = new TransformationRunCache(10);
		for (int i = 0; i < 1000; i++)
		{
			bounded.putLookup("key-" + i, "value-" + i);
		}
		int cached = 0;
		for (int i = 0; i < 1000; i++)
		{
			if (bounded.hasLookup("key-" + i))
			{
				cached++;
			}
		}
		check(cached <= 10, "a bounded cache must never hold more than its capacity: held " + cached);
		check(bounded.hasLookup("key-999"), "the most recently inserted entry must still be present after eviction");
		check(!bounded.hasLookup("key-0"), "the least-recently-used entry must have been evicted");

		System.out.println("PASS: TransformationRunCache caches pipelines/patterns/tables/lookups per run and bounds the lookup memo");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
