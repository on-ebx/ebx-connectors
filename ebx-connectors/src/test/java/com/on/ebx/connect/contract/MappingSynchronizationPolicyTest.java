package com.on.ebx.connect.contract;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingSynchronizationPolicy;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.SynchronizationStrategy;

/** Offline synchronization-policy compatibility contracts; no checkpoint storage is exercised. */
public final class MappingSynchronizationPolicyTest
{
	private static final Operation READ = Operation.search("read", "Read records", new String[0]);
	private MappingSynchronizationPolicyTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final Connector fullOnly = new StubConnector(EnumSet.of(SynchronizationStrategy.FULL));
		final MappingDefinition mapping = mapping(MappingDefinition.Direction.INBOUND);
		check(mapping.getLoadMode() == MappingDefinition.LoadMode.FULL, "Legacy mapping default must remain FULL");
		check(mapping.getCheckpointStrategy() == null, "FULL default must not invent a checkpoint strategy");
		check(MappingSynchronizationPolicy.isValid(fullOnly, mapping), "FULL default must remain compatible");

		mapping.setSynchronizationPolicy("INCREMENTAL", "TIMESTAMP", "since", "updatedAt", "");
		check(!MappingSynchronizationPolicy.isValid(fullOnly, mapping), "Unsupported incremental strategy was accepted");
		check(MappingSynchronizationPolicy.problem(fullOnly, mapping).contains("supported: [FULL]"),
			"Unsupported-strategy error must list descriptor support");

		final Connector timestamp = new StubConnector(EnumSet.of(SynchronizationStrategy.FULL, SynchronizationStrategy.TIMESTAMP));
		check(MappingSynchronizationPolicy.isValid(timestamp, mapping), "Advertised TIMESTAMP strategy was rejected");

		mapping.setSynchronizationPolicy("INCREMENTAL", null, "", "", "");
		check(MappingSynchronizationPolicy.problem(timestamp, mapping).contains("requires a checkpoint strategy"),
			"Incremental mode without a strategy was accepted");

		final MappingDefinition outbound = mapping(MappingDefinition.Direction.OUTBOUND);
		outbound.setSynchronizationPolicy("INCREMENTAL", "TIMESTAMP", "since", "updatedAt", "");
		check(MappingSynchronizationPolicy.problem(timestamp, outbound).contains("only valid for inbound"),
			"Outbound incremental mapping was accepted");

		final MappingDefinition unknown = new MappingDefinition(2, "stub", "missing", MappingDefinition.Direction.INBOUND,
			"Unknown", null, "Dataset", "/root/Table", "", true);
		check(MappingSynchronizationPolicy.problem(timestamp, unknown).contains("has no descriptor"),
			"Unknown operation was accepted");

		try
		{
			MappingSynchronizationPolicy.requireValid(fullOnly, mapping);
			throw new AssertionError("Invalid policy was not rejected");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("checkpoint strategy"), "Exception does not explain the invalid policy");
		}
		System.out.println("PASS: mapping synchronization-policy compatibility");
	}

	private static MappingDefinition mapping(final MappingDefinition.Direction pDirection)
	{
		return new MappingDefinition(1, "stub", "read", pDirection, "Read", null, "Dataset", "/root/Table", "", true);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	private static final class StubConnector implements Connector
	{
		private final EnumSet<SynchronizationStrategy> strategies;
		StubConnector(final EnumSet<SynchronizationStrategy> pStrategies) { this.strategies = pStrategies; }
		@Override public String getType() { return "stub"; }
		@Override public String getLabel() { return "Stub"; }
		@Override public List<Operation> getOperations() { return Arrays.asList(READ); }
		@Override public void configure(final ConnectorConfig pConfig) { }
		@Override public JsonNode execute(final ConnectorContext pContext, final String pOperation,
			final Map<String, String> pInputs, final JsonNode pPayload) { return null; }
		@Override public ConnectorDescriptor getDescriptor()
		{
			return ConnectorDescriptor.builder("stub", "Stub", "GENERIC_INTEGRATION")
				.direction(ConnectorDirection.INBOUND)
				.operation(new ConnectorOperationDescriptor("read", Operation.Kind.SEARCH, "Read records",
					Collections.singleton(ConnectorDirection.INBOUND), Collections.emptySet(), this.strategies)).build();
		}
	}
}
