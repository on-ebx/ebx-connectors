package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Choosing a mapping among mappings already read (audit C-13): a record-mode run reads the mappings once and chooses from the
 * list for every record, so the choice must be the one {@code ConnectorRepository.mappingFor} has always made.
 */
public final class MappingSelectionTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final List<MappingDefinition> all = new ArrayList<MappingDefinition>();
		all.add(mapping(1, "GLEIF", "lei", MappingDefinition.Direction.INBOUND, null, "/root/Company", true));
		all.add(mapping(2, "GLEIF", "lei", MappingDefinition.Direction.INBOUND, "BCompanies", "/root/Company", true));
		all.add(mapping(3, "Slack", "post", MappingDefinition.Direction.OUTBOUND, null, "/root/Company", true));
		all.add(mapping(4, "Slack", "post", MappingDefinition.Direction.INBOUND, null, "/root/Company", true));
		all.add(mapping(5, "GLEIF", "lei", MappingDefinition.Direction.INBOUND, null, "/root/Other", false));
		all.add(mapping(6, "Dup", "op", MappingDefinition.Direction.INBOUND, null, "/root/Company", true));
		all.add(mapping(7, "Dup", "op", MappingDefinition.Direction.INBOUND, null, "/root/Company", true));
		final MappingDefinition child = mapping(8, "GLEIF", "lei", MappingDefinition.Direction.INBOUND, null, "/root/Company", true);
		child.setParentId(Integer.valueOf(1));
		all.add(child);

		// a mapping bound to the dataspace wins over a generic one
		check(id(ConnectorRepository.mappingFor(all, "BCompanies", "Companies", "/root/Company", "GLEIF", "lei", MappingDefinition.Direction.INBOUND)) == 2, "the dataspace's own mapping wins");
		check(id(ConnectorRepository.mappingFor(all, "BOther", "Companies", "/root/Company", "GLEIF", "lei", MappingDefinition.Direction.INBOUND)) == 1, "else the generic one; a child mapping is never chosen");
		// direction, connector and operation must all match: the response mapping of an outbound call is the INBOUND twin
		check(id(ConnectorRepository.mappingFor(all, "BCompanies", "Companies", "/root/Company", "Slack", "post", MappingDefinition.Direction.INBOUND)) == 4, "the inbound twin of an outbound mapping");
		check(id(ConnectorRepository.mappingFor(all, "BCompanies", "Companies", "/root/Company", "Slack", "post", MappingDefinition.Direction.OUTBOUND)) == 3, "the outbound one");
		check(ConnectorRepository.mappingFor(all, "BCompanies", "Companies", "/root/Company", "Slack", "other", MappingDefinition.Direction.INBOUND) == null, "no mapping: null");
		check(ConnectorRepository.mappingFor(all, "BCompanies", "Companies", "/root/Other", "GLEIF", "lei", MappingDefinition.Direction.INBOUND) == null, "an inactive mapping is not chosen");
		// two candidates at the same level are refused, naming them
		try
		{
			ConnectorRepository.mappingFor(all, "BCompanies", "Companies", "/root/Company", "Dup", "op", MappingDefinition.Direction.INBOUND);
			throw new AssertionError("an ambiguous choice must be refused");
		}
		catch (final IllegalStateException ex)
		{
			check(ex.getMessage().contains("6") && ex.getMessage().contains("7") && ex.getMessage().contains("Name the mapping by its id"), "the refusal names both: " + ex.getMessage());
		}
		// by id, from the list
		check(id(ConnectorRepository.mapping(all, 3)) == 3 && ConnectorRepository.mapping(all, 99) == null, "a mapping by id, or null");
		check(ConnectorRepository.mappingsFor(all, "BCompanies", "Companies", "/root/Company").size() == 6, "active, non-child mappings of the table: " + ConnectorRepository.mappingsFor(all, "BCompanies", "Companies", "/root/Company").size());

		// the batch size of a run
		check(RecordRun.batchSize(null) == 100 && RecordRun.batchSize(" ") == 100 && RecordRun.batchSize("many") == 100, "default 100");
		check(RecordRun.batchSize("1") == 1 && RecordRun.batchSize("0") == 1 && RecordRun.batchSize("-5") == 1, "1 writes after each record, never less");
		check(RecordRun.batchSize("250") == 250 && RecordRun.batchSize("999999") == RecordRun.MAX_BATCH, "a value, bounded");

		System.out.println("PASS: mappings chosen from a list already read are chosen as before; batch size bounds");
	}

	private static MappingDefinition mapping(final int pId, final String pConnector, final String pOperation, final MappingDefinition.Direction pDirection, final String pDataspace,
		final String pTable, final boolean pActive)
	{
		return new MappingDefinition(pId, pConnector, pOperation, pDirection, "m" + pId, pDataspace, null, pTable, "", pActive);
	}

	private static int id(final MappingDefinition pMapping)
	{
		return pMapping == null ? -1 : pMapping.getId();
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
