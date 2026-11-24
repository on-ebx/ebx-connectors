package com.on.ebx.connect.core;

import java.util.Arrays;

/**
 * Enrichment without a dataspace: a mapping bound to a dataspace is accepted and the run takes that dataspace,
 * instead of "Mapping 89 is not a mapping of /root/Companies" (seen 2026-09-15). appliesTo itself is unchanged.
 */
public final class MappingRunDataspaceTest
{
	public static void main(final String[] pArgs)
	{
		final MappingDefinition bound = mapping(89, "Companies");
		final MappingDefinition generic = mapping(1, null);
		final MappingDefinition other = mapping(2, "Other");

		check(!bound.appliesTo(null, "Companies", "/root/Companies"), "appliesTo unchanged");
		check(bound.appliesToAnyDataspace(null, "Companies", "/root/Companies"), "null dataspace accepts a bound mapping");
		check(bound.appliesToAnyDataspace(" ", "Companies", "/root/Companies"), "blank dataspace accepts a bound mapping");
		check(!bound.appliesToAnyDataspace("Other", "Companies", "/root/Companies"), "a given dataspace still restricts");
		check(!bound.appliesToAnyDataspace(null, "Companies", "/root/Other"), "the table still restricts");
		check(!bound.appliesToAnyDataspace(null, "OtherDataset", "/root/Companies"), "the dataset still restricts");

		check("Companies".equals(MappingDefinition.runDataspace(null, Arrays.asList(bound))), "dataspace of the mapping");
		check(MappingDefinition.runDataspace(null, Arrays.asList(generic)) == null, "generic mapping: dataset searched");
		check("Companies".equals(MappingDefinition.runDataspace("", Arrays.asList(generic, bound, bound))), "generic plus bound");
		check("Given".equals(MappingDefinition.runDataspace(" Given ", Arrays.asList(bound))), "given dataspace wins");
		try
		{
			MappingDefinition.runDataspace(null, Arrays.asList(bound, other));
			throw new AssertionError("two dataspaces accepted");
		}
		catch (final IllegalArgumentException expected)
		{
			check(expected.getMessage().contains("Companies") && expected.getMessage().contains("Other"), "ambiguity message");
		}
		System.out.println("PASS: enrichment without dataspace takes the dataspace of the selected mappings");
	}

	private static MappingDefinition mapping(final int pId, final String pDataspace)
	{
		return new MappingDefinition(pId, "Salesforce", "getByExternalId", MappingDefinition.Direction.INBOUND, "Mapping " + pId, pDataspace,
			"Companies", "/root/Companies", "", true);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
