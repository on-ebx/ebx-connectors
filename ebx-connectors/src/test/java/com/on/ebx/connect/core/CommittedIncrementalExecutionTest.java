package com.on.ebx.connect.core;

import java.util.Date;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Proves that committed work, rather than merely fetched work, advances policy progress. */
public final class CommittedIncrementalExecutionTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		commitFailurePreservesCheckpoint();
		successfulCommitAdvancesCheckpoint();
		dryRunDoesNotCommitOrAdvance();
		System.out.println("PASS: committed incremental checkpoint ordering");
	}
	private static void commitFailurePreservesCheckpoint() throws Exception
	{
		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
		boolean failed = false;
		try { CommittedIncrementalExecution.execute(mapping(), new SynchronizationCheckpointManager(store), false, work("two"), new CommittedIncrementalExecution.Commit() {
			public void commit() throws Exception { throw new Exception("repository rejected write"); }
		}, new Date(2L)); } catch (final Exception expected) { failed = true; }
		check(failed && store.load(71) == null, "failed commit advanced checkpoint");
	}
	private static void successfulCommitAdvancesCheckpoint() throws Exception
	{
		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
		CommittedIncrementalExecution.execute(mapping(), new SynchronizationCheckpointManager(store), false, work("three"), new CommittedIncrementalExecution.Commit() {
			public void commit() { }
		}, new Date(3L));
		check("three".equals(store.load(71).getValue()), "committed checkpoint missing");
	}
	private static void dryRunDoesNotCommitOrAdvance() throws Exception
	{
		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore(); final boolean[] committed = { false };
		CommittedIncrementalExecution.execute(mapping(), new SynchronizationCheckpointManager(store), true, work("dry"), new CommittedIncrementalExecution.Commit() {
			public void commit() { committed[0] = true; }
		}, new Date());
		check(!committed[0] && store.load(71) == null, "dry run changed durable state");
	}
	private static MappingDefinition mapping()
	{
		final MappingDefinition mapping = new MappingDefinition(71, "stub", "read", MappingDefinition.Direction.INBOUND, "incremental", null, "Dataset", "/root/Table", "", true);
		mapping.setSynchronizationPolicy("INCREMENTAL", "VENDOR_CURSOR", "cursor", "next", ""); return mapping;
	}
	private static CommittedIncrementalExecution.Work work(final String pValue) { return new CommittedIncrementalExecution.Work() {
		public JsonNode execute() { return JsonNodeFactory.instance.objectNode().put("next", pValue); }
	}; }
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
