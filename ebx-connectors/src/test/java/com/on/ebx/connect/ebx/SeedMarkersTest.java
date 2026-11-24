package com.on.ebx.connect.ebx;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The rule that keeps a deleted seeded item deleted (audit C-26), without a repository. */
public final class SeedMarkersTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// a repository that never saw the installer: everything absent may be created, once
		final SeedMarkers fresh = new SeedMarkers(true);
		check(fresh.mayCreateConnector("Kafka", false), "an absent connector never seeded is created");
		check(!fresh.mayCreateConnector("Kafka", true), "one that exists is left alone");
		fresh.add(SeedMarkers.connector("Kafka"));
		check(!fresh.mayCreateConnector("Kafka", false), "deleted after it was seeded, it does not come back");
		check(fresh.mayCreateConnector("Slack", false), "another item is not affected");

		// mapping groups seeded apart from their connector
		check(fresh.mayAddMappings("DnB", true), "missing mappings of a group never seeded are added");
		check(!fresh.mayAddMappings("DnB", false), "nothing to add when none is missing");
		fresh.add(SeedMarkers.mappings("DnB"));
		check(!fresh.mayAddMappings("DnB", true), "a mapping deleted after the group was seeded stays deleted");

		// the first start of this version on a repository that already holds connectors
		final SeedMarkers upgraded = new SeedMarkers(true);
		check(!upgraded.baselineDone(), "no baseline before the first start");
		final List<String> baseline = upgraded.baseline(Arrays.asList("GLEIF", "Kafka", "Mine"), Arrays.asList("DnB"));
		check(baseline.equals(Arrays.asList("connector:GLEIF", "connector:Kafka", "connector:Mine", "mappings:DnB", "baseline")),
			"every connector present, the complete mapping groups, then the baseline: " + baseline);
		for (final String item : baseline)
		{
			upgraded.add(item);
		}
		check(upgraded.baselineDone(), "the baseline is remembered");
		check(!upgraded.mayCreateConnector("Kafka", false), "a connector present at the first start and deleted later does not come back");
		check(upgraded.mayCreateConnector("Snowflake", false), "one that was absent at the first start is still created once");
		check(upgraded.baseline(Arrays.asList("GLEIF", "New"), Collections.<String>emptyList()).equals(Arrays.asList("connector:New", "baseline")),
			"nothing recorded twice");

		// an empty repository (a new install): only the baseline, the seeds record their own items
		check(new SeedMarkers(true).baseline(Collections.<String>emptyList(), Collections.<String>emptyList()).equals(Arrays.asList("baseline")),
			"a new repository records only the baseline");

		// a model without the table: old behaviour, nothing remembered
		final SeedMarkers legacy = new SeedMarkers(false);
		legacy.add(SeedMarkers.connector("Kafka"));
		check(!legacy.isSupported() && legacy.size() == 0 && legacy.mayCreateConnector("Kafka", false), "without the table an absent connector is created, as before");

		// the property
		check(SeedMarkers.mode(null) == SeedMarkers.Mode.ALL, "unset seeds");
		check(SeedMarkers.mode("none") == SeedMarkers.Mode.NONE && SeedMarkers.mode("  NONE ") == SeedMarkers.Mode.NONE, "none seeds nothing, case and spaces ignored");
		check(SeedMarkers.mode("all") == SeedMarkers.Mode.ALL && SeedMarkers.mode("whatever") == SeedMarkers.Mode.ALL, "any other value seeds");
		System.out.println("SeedMarkersTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
