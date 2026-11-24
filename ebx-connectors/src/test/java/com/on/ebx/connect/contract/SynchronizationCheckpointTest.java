package com.on.ebx.connect.contract;

import java.util.Collections;
import java.util.Date;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.InMemorySynchronizationCheckpointStore;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.SynchronizationCheckpoint;
import com.on.ebx.connect.core.SynchronizationCheckpointManager;
import com.on.ebx.connect.spi.SynchronizationStrategy;

/** Deterministic contracts for policy/progress separation and all checkpoint strategies. */
public final class SynchronizationCheckpointTest
{
	private SynchronizationCheckpointTest() { }

	public static void main(final String[] pArgs)
	{
		for (final SynchronizationStrategy strategy : SynchronizationStrategy.values())
		{
			if (strategy != SynchronizationStrategy.FULL) strategyRoundTrip(strategy);
		}
		fullRunPreservesIncrementalProgress();
		unsuccessfulAndDryRunsPreserveProgress();
		independentMappingsDoNotShareProgress();
		optimisticConcurrencyRejectsLostUpdate();
		System.out.println("PASS: durable synchronization checkpoints");
	}

	private static void strategyRoundTrip(final SynchronizationStrategy pStrategy)
	{
		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
		final SynchronizationCheckpointManager manager = new SynchronizationCheckpointManager(store);
		final MappingDefinition mapping = mapping(10 + pStrategy.ordinal(), MappingDefinition.Direction.INBOUND);
		mapping.setSynchronizationPolicy("INCREMENTAL", pStrategy.name(), "cursor", "meta.next", "seed");
		check("seed".equals(manager.inputs(mapping, Collections.<String, String>emptyMap()).get("cursor")), "Initial value missing for " + pStrategy);
		final ObjectNode response = JsonNodeFactory.instance.objectNode();
		response.putObject("meta").put("next", "v-" + pStrategy.name());
		final SynchronizationCheckpoint saved = manager.complete(mapping, response, true, false, new Date(1234L));
		check(saved != null && saved.getStrategy() == pStrategy, "Strategy not persisted: " + pStrategy);
		check(("v-" + pStrategy.name()).equals(manager.inputs(mapping, null).get("cursor")), "Stored value not injected: " + pStrategy);
	}

	private static void fullRunPreservesIncrementalProgress()
	{
		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
		final SynchronizationCheckpointManager manager = new SynchronizationCheckpointManager(store);
		final MappingDefinition incremental = mapping(1, MappingDefinition.Direction.INBOUND);
		incremental.setSynchronizationPolicy("INCREMENTAL", "TIMESTAMP", "since", "watermark", "");
		manager.complete(incremental, JsonNodeFactory.instance.objectNode().put("watermark", "2026-09-08T00:00:00Z"), true, false, new Date(1L));
		final SynchronizationCheckpoint before = store.load(1);
		final MappingDefinition full = mapping(1, MappingDefinition.Direction.INBOUND);
		full.setSynchronizationPolicy("FULL", null, null, null, null);
		check(manager.complete(full, JsonNodeFactory.instance.objectNode().put("watermark", "later"), true, false, new Date(2L)) == null,
			"FULL run unexpectedly wrote a checkpoint");
		check(store.load(1).getVersion() == before.getVersion() && store.load(1).getValue().equals(before.getValue()),
			"FULL run destroyed the last incremental checkpoint");
	}

	private static void independentMappingsDoNotShareProgress()
	{
		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
		final SynchronizationCheckpointManager manager = new SynchronizationCheckpointManager(store);
		final MappingDefinition inbound = mapping(20, MappingDefinition.Direction.INBOUND);
		final MappingDefinition outbound = mapping(21, MappingDefinition.Direction.OUTBOUND);
		inbound.setSynchronizationPolicy("INCREMENTAL", "VENDOR_CURSOR", "after", "next", "");
		manager.complete(inbound, JsonNodeFactory.instance.objectNode().put("next", "in-2"), true, false, new Date());
		check(store.load(20) != null && store.load(21) == null, "Inbound/outbound mapping state was not isolated");
		check(outbound.getDirection() == MappingDefinition.Direction.OUTBOUND, "Outbound mapping was altered");
	}

	private static void unsuccessfulAndDryRunsPreserveProgress()
	{
		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
		final SynchronizationCheckpointManager manager = new SynchronizationCheckpointManager(store);
		final MappingDefinition mapping = mapping(15, MappingDefinition.Direction.INBOUND);
		mapping.setSynchronizationPolicy("INCREMENTAL", "EBX_DELTA", "delta", "next", "start");
		manager.complete(mapping, JsonNodeFactory.instance.objectNode().put("next", "one"), true, false, new Date());
		manager.complete(mapping, JsonNodeFactory.instance.objectNode().put("next", "failed"), false, false, new Date());
		manager.complete(mapping, JsonNodeFactory.instance.objectNode().put("next", "dry"), true, true, new Date());
		check("one".equals(store.load(15).getValue()) && store.load(15).getVersion() == 1L,
			"Failed or dry run advanced durable progress");
	}

	private static void optimisticConcurrencyRejectsLostUpdate()
	{
		final InMemorySynchronizationCheckpointStore store = new InMemorySynchronizationCheckpointStore();
		store.save(new SynchronizationCheckpoint(30, SynchronizationStrategy.MONOTONIC_ID, "1", new Date(), 0L), 0L);
		try
		{
			store.save(new SynchronizationCheckpoint(30, SynchronizationStrategy.MONOTONIC_ID, "2", new Date(), 1L), 0L);
			throw new AssertionError("Stale checkpoint update was accepted");
		}
		catch (final IllegalStateException expected) { /* contract */ }
	}

	private static MappingDefinition mapping(final int pId, final MappingDefinition.Direction pDirection)
	{
		return new MappingDefinition(pId, "stub", "read", pDirection, "Mapping " + pId, null, "Dataset", "/root/Table", "", true);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
