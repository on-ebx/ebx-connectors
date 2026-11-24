package com.on.ebx.connect.contract;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.MappingCompatibility;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;

/** Dependency-free direction compatibility contracts. */
public final class MappingCompatibilityTest
{
	private static final Operation READ = Operation.read("read", "Read", new String[0]);
	private static final Operation SEARCH = Operation.search("search", "Search", new String[0]);
	private static final Operation WRITE = Operation.write("write", "Write", new String[0]);

	private MappingCompatibilityTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final Connector conventional = new StubConnector(false);
		check(MappingCompatibility.isCompatible(conventional, READ, MappingDefinition.Direction.INBOUND), "READ must permit inbound");
		check(MappingCompatibility.isCompatible(conventional, SEARCH, MappingDefinition.Direction.INBOUND), "SEARCH must permit inbound");
		check(MappingCompatibility.isCompatible(conventional, WRITE, MappingDefinition.Direction.OUTBOUND), "WRITE must permit outbound");
		check(!MappingCompatibility.isCompatible(conventional, READ, MappingDefinition.Direction.OUTBOUND), "READ must reject outbound");
		check(!MappingCompatibility.isCompatible(conventional, SEARCH, MappingDefinition.Direction.OUTBOUND), "SEARCH must reject outbound");
		check(!MappingCompatibility.isCompatible(conventional, WRITE, MappingDefinition.Direction.INBOUND), "WRITE must reject inbound");

		final Connector explicit = new StubConnector(true);
		check(MappingCompatibility.isCompatible(explicit, READ, MappingDefinition.Direction.OUTBOUND),
			"Explicit descriptor direction must override the conventional kind direction");
		check(!MappingCompatibility.isCompatible(explicit, READ, MappingDefinition.Direction.INBOUND),
			"An explicit descriptor direction must be enforced");

		try
		{
			MappingCompatibility.requireCompatible(conventional, WRITE, MappingDefinition.Direction.INBOUND);
			throw new AssertionError("Incompatible mapping was accepted");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("stub/write"), "Compatibility error must identify the operation");
		}
		System.out.println("PASS: mapping direction compatibility");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) { throw new AssertionError(pMessage); }
	}

	private static final class StubConnector implements Connector
	{
		private final boolean explicit;
		private StubConnector(final boolean pExplicit) { this.explicit = pExplicit; }
		@Override public String getType() { return "stub"; }
		@Override public String getLabel() { return "Stub"; }
		@Override public List<Operation> getOperations() { return Arrays.asList(READ, SEARCH, WRITE); }
		@Override public void configure(final ConnectorConfig pConfig) { }
		@Override public JsonNode execute(final ConnectorContext pContext, final String pOperation,
			final Map<String, String> pInputs, final JsonNode pPayload) { return null; }

		@Override
		public ConnectorDescriptor getDescriptor()
		{
			if (!this.explicit) { return Connector.super.getDescriptor(); }
			return ConnectorDescriptor.builder(getType(), getLabel(), "TEST")
				.operation(new ConnectorOperationDescriptor("read", Operation.Kind.READ, "Read outbound by contract",
					Collections.singleton(ConnectorDirection.OUTBOUND), Collections.emptySet()))
				.direction(ConnectorDirection.OUTBOUND).build();
		}
	}
}
