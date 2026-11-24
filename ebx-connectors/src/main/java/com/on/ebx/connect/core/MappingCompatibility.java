package com.on.ebx.connect.core;

import java.util.List;

import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;

/** Side-effect-free validation of a mapping direction against a connector operation contract. */
public final class MappingCompatibility
{
	private MappingCompatibility()
	{
	}

	/** Returns null when compatible, otherwise a stable user-facing explanation. */
	public static String problem(final Connector pConnector, final Operation pOperation,
		final MappingDefinition.Direction pMappingDirection)
	{
		if (pConnector == null) { return "Connector is required"; }
		if (pOperation == null) { return "Connector operation is required"; }
		if (pMappingDirection == null) { return "Mapping direction is required"; }

		final ConnectorDirection requested = pMappingDirection == MappingDefinition.Direction.OUTBOUND
			? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
		final ConnectorOperationDescriptor described = operation(pConnector.getDescriptor(), pOperation.getName());
		if (described != null && !described.getDirections().isEmpty())
		{
			return described.getDirections().contains(requested) ? null : incompatible(
				pConnector, pOperation, pMappingDirection, "descriptor permits " + described.getDirections());
		}

		final ConnectorDirection conventional = pOperation.getKind() == Operation.Kind.WRITE
			? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
		return conventional == requested ? null : incompatible(pConnector, pOperation, pMappingDirection,
			pOperation.getKind() + " operations are " + conventional);
	}

	public static boolean isCompatible(final Connector pConnector, final Operation pOperation,
		final MappingDefinition.Direction pMappingDirection)
	{
		return problem(pConnector, pOperation, pMappingDirection) == null;
	}

	public static void requireCompatible(final Connector pConnector, final Operation pOperation,
		final MappingDefinition.Direction pMappingDirection) throws ConnectorException
	{
		final String problem = problem(pConnector, pOperation, pMappingDirection);
		if (problem != null) { throw new ConnectorException(problem); }
	}

	private static ConnectorOperationDescriptor operation(final ConnectorDescriptor pDescriptor, final String pName)
	{
		if (pDescriptor == null) { return null; }
		final List<ConnectorOperationDescriptor> operations = pDescriptor.getOperations();
		if (operations == null) { return null; }
		for (final ConnectorOperationDescriptor operation : operations)
		{
			if (operation != null && pName.equals(operation.getName())) { return operation; }
		}
		return null;
	}

	private static String incompatible(final Connector pConnector, final Operation pOperation,
		final MappingDefinition.Direction pDirection, final String pReason)
	{
		return "Mapping direction " + pDirection + " is not compatible with " + pConnector.getType() + "/"
			+ pOperation.getName() + " (" + pReason + ")";
	}
}
