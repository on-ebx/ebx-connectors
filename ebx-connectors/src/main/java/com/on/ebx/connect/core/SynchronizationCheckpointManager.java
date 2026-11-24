package com.on.ebx.connect.core;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** Applies persisted mapping policy without mixing mutable progress into mapping configuration. */
public final class SynchronizationCheckpointManager
{
	private final SynchronizationCheckpointStore store;

	public SynchronizationCheckpointManager(final SynchronizationCheckpointStore pStore)
	{
		if (pStore == null) throw new IllegalArgumentException("Checkpoint store is required");
		this.store = pStore;
	}

	/** Returns a copy of operation inputs with the current incremental checkpoint injected. */
	public Map<String, String> inputs(final MappingDefinition pMapping, final Map<String, String> pInputs)
	{
		final Map<String, String> result = new LinkedHashMap<String, String>();
		if (pInputs != null) result.putAll(pInputs);
		if (pMapping == null || pMapping.getLoadMode() == MappingDefinition.LoadMode.FULL) return result;
		if (pMapping.getCheckpointStrategy() == null) throw new IllegalStateException("Incremental mapping has no checkpoint strategy");
		if (pMapping.getCheckpointInput().isEmpty()) throw new IllegalStateException("Incremental mapping has no checkpoint input");
		final SynchronizationCheckpoint current = this.store.load(pMapping.getId());
		if (current != null && current.getStrategy() != pMapping.getCheckpointStrategy())
			throw new IllegalStateException("Stored checkpoint strategy differs from mapping policy");
		final String value = current == null ? pMapping.getCheckpointInitialValue() : current.getValue();
		if (!value.isEmpty()) result.put(pMapping.getCheckpointInput(), value);
		return result;
	}

	/** Advances progress after a successful incremental run. FULL and dry runs deliberately preserve it. */
	public SynchronizationCheckpoint complete(final MappingDefinition pMapping, final JsonNode pResponse,
		final boolean pSuccessful, final boolean pDryRun, final Date pCompletedAt)
	{
		if (pMapping == null || !pSuccessful || pDryRun || pMapping.getLoadMode() == MappingDefinition.LoadMode.FULL) return null;
		if (pMapping.getCheckpointStrategy() == null) throw new IllegalStateException("Incremental mapping has no checkpoint strategy");
		final String value = checkpointValue(pResponse, pMapping.getCheckpointValuePath());
		if (value.isEmpty()) throw new IllegalStateException("Successful incremental response has no checkpoint value");
		final SynchronizationCheckpoint current = this.store.load(pMapping.getId());
		final long expected = current == null ? 0L : current.getVersion();
		final Date completedAt = pCompletedAt == null ? new Date() : pCompletedAt;
		final SynchronizationCheckpoint saved = this.store.save(new SynchronizationCheckpoint(pMapping.getId(), pMapping.getCheckpointStrategy(),
			value, completedAt, expected), expected);
		ConnectorOperationsRuntime.checkpoint(pMapping.getConnectorId(), completedAt);
		return saved;
	}

	static String checkpointValue(final JsonNode pResponse, final String pPath)
	{
		if (pResponse == null || pResponse.isNull()) return "";
		JsonNode value = pResponse;
		if (pPath != null && !pPath.trim().isEmpty())
		{
			String pointer = pPath.trim();
			if (!pointer.startsWith("/")) pointer = "/" + pointer.replace('.', '/');
			value = pResponse.at(pointer);
		}
		return value == null || value.isMissingNode() || value.isNull() ? "" : value.isValueNode() ? value.asText() : value.toString();
	}
}
