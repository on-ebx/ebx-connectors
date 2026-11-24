package com.on.ebx.connect.core;

import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.SynchronizationStrategy;

/** Side-effect-free validation of a mapping load policy against operation metadata. */
public final class MappingSynchronizationPolicy
{
	private MappingSynchronizationPolicy() { }

	/** Returns null when the persisted policy is supported, otherwise a stable explanation. */
	public static String problem(final Connector pConnector, final MappingDefinition pMapping)
	{
		if (pConnector == null) return "Connector is required";
		if (pMapping == null) return "Mapping is required";
		final ConnectorOperationDescriptor operation = operation(pConnector.getDescriptor(), pMapping.getOperation());
		if (operation == null)
		{
			return "Operation " + pConnector.getType() + "/" + pMapping.getOperation() + " has no descriptor";
		}

		if (pMapping.getLoadMode() == MappingDefinition.LoadMode.FULL)
		{
			return operation.getSynchronizationStrategies().contains(SynchronizationStrategy.FULL) ? null
				: unsupported(pConnector, pMapping, SynchronizationStrategy.FULL, operation);
		}
		if (pMapping.getDirection() != MappingDefinition.Direction.INBOUND)
		{
			return "Incremental load is only valid for inbound mappings";
		}
		if (pMapping.getCheckpointStrategy() == null)
		{
			return "Incremental load requires a checkpoint strategy";
		}

		// Volume lot V2-D: an import feed's row watermark is applied by the engine itself (a placeholder for
		// a source that can filter, a client-side filter otherwise), so it needs no operation to advertise it.
		if (Watermark.Kind.of(pMapping.getCheckpointStrategy()) != null && pMapping.isFeed())
		{
			return pMapping.isWatermarkFeedWithoutField()
				? "Incremental feed with strategy " + pMapping.getCheckpointStrategy()
					+ " requires checkpointValuePath: the field of the returned row that carries the watermark"
				: null;
		}
		final SynchronizationStrategy strategy = SynchronizationStrategy.valueOf(pMapping.getCheckpointStrategy().name());
		return operation.getSynchronizationStrategies().contains(strategy) ? null
			: unsupported(pConnector, pMapping, strategy, operation);
	}

	public static boolean isValid(final Connector pConnector, final MappingDefinition pMapping)
	{
		return problem(pConnector, pMapping) == null;
	}

	public static void requireValid(final Connector pConnector, final MappingDefinition pMapping)
		throws ConnectorException
	{
		final String problem = problem(pConnector, pMapping);
		if (problem != null) throw new ConnectorException(problem);
	}

	private static ConnectorOperationDescriptor operation(final ConnectorDescriptor pDescriptor, final String pName)
	{
		if (pDescriptor == null || pName == null) return null;
		for (final ConnectorOperationDescriptor operation : pDescriptor.getOperations())
		{
			if (operation != null && pName.equals(operation.getName())) return operation;
		}
		return null;
	}

	private static String unsupported(final Connector pConnector, final MappingDefinition pMapping,
		final SynchronizationStrategy pStrategy, final ConnectorOperationDescriptor pOperation)
	{
		return "Synchronization strategy " + pStrategy + " is not supported by " + pConnector.getType() + "/"
			+ pMapping.getOperation() + "; supported: " + pOperation.getSynchronizationStrategies();
	}
}
