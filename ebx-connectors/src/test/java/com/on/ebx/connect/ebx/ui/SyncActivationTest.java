package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.connect.core.MappingDefinition;

/** Audit C2-19: the synchronisation action is offered only where an active top-level mapping applies to the table. */
public final class SyncActivationTest
{
	public static void main(final String[] pArgs)
	{
		final List<MappingDefinition> all = new ArrayList<MappingDefinition>();
		check(!SyncActivation.hasMapping(all, "BCompanies", "Companies", "/root/Company"), "no mapping: not offered");
		all.add(mapping(1, null, "/root/Company", true));
		all.add(mapping(2, "BOther", "/root/Contact", true));
		all.add(mapping(3, null, "/root/Off", false));
		final MappingDefinition child = mapping(4, null, "/root/Child", true);
		child.setParentId(Integer.valueOf(1));
		all.add(child);
		check(SyncActivation.hasMapping(all, "BCompanies", "Companies", "/root/Company"), "an active generic mapping: offered");
		check(!SyncActivation.hasMapping(all, "BCompanies", "Companies", "/root/Contact"), "a mapping bound to another dataspace: not offered");
		check(SyncActivation.hasMapping(all, "BOther", "Companies", "/root/Contact"), "bound to this dataspace: offered");
		check(!SyncActivation.hasMapping(all, "BCompanies", "Companies", "/root/Off"), "an inactive mapping: not offered");
		check(!SyncActivation.hasMapping(all, "BCompanies", "Companies", "/root/Child"), "a child mapping runs with its parent: not offered alone");
		check(!SyncActivation.hasMapping(all, "BCompanies", "Companies", "/root/Nothing"), "a table with no mapping: not offered");
		System.out.println("PASS: the synchronisation action follows the mappings");
	}

	private static MappingDefinition mapping(final int pId, final String pDataspace, final String pTable, final boolean pActive)
	{
		return new MappingDefinition(pId, "GLEIF", "lei", MappingDefinition.Direction.INBOUND, "m" + pId, pDataspace, null, pTable, "", pActive);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
