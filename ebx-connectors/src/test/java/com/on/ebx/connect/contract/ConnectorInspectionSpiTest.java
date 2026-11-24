package com.on.ebx.connect.contract;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.DiscoveredObject;
import com.on.ebx.connect.spi.DraftMapping;
import com.on.ebx.connect.spi.DraftMappingRequest;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.ObjectDescription;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PreviewRequest;
import com.on.ebx.connect.spi.PreviewResult;

/** Dependency-free contracts for the optional connector inspection SPI. */
public final class ConnectorInspectionSpiTest
{
	private ConnectorInspectionSpiTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final Connector connector = new LegacyConnector();
		final ConnectorConfig config = new ConnectorConfig();
		config.setBaseUrl("https://example.invalid");
		check(connector.validateConfiguration(config).isValid(), "Default validation rejected valid configuration");

		final ConnectionTestResult test = connector.testConnection(null);
		check(!test.isSupported() && !test.isSuccessful(), "Legacy connection test must be explicitly unsupported");

		final ObjectDiscoveryResult discovered = connector.discoverObjects(null,
			ObjectDiscoveryRequest.firstPage("customer", 10));
		check(discovered.isSupported(), "Operation metadata discovery is not supported");
		check(discovered.getObjects().size() == 1, "Read/search operation filtering failed");
		final DiscoveredObject object = discovered.getObjects().get(0);
		check("customers".equals(object.getId()), "Wrong discovered operation");

		final ObjectDescription description = connector.describeObject(null, "customers");
		check(description.isSupported() && description.getFields().size() == 2,
			"Legacy describeFields adapter failed");

		final DraftMapping draft = connector.generateDraftMapping(null, new DraftMappingRequest(
			"customers", "customers", Arrays.asList(
				FieldInfo.of("./Customer/Id", "Id", "string"),
				FieldInfo.of("./Customer/Name", "Name", "string"))));
		check(draft.isSupported() && draft.getFields().size() == 2, "Draft mapping adapter failed");
		check(draft.getFields().get(0).getConfidence() == 0.85d, "Unexpected mapping confidence");

		check(new PreviewRequest("customers", "customers", null, 1000).getLimit()
			== PreviewRequest.MAX_LIMIT, "Preview limit is not bounded");
		final PreviewResult preview = connector.preview(null,
			new PreviewRequest("customers", "customers", null, 10));
		check(!preview.isSupported(), "Legacy preview must be explicitly unsupported");

		try
		{
			discovered.getObjects().add(object);
			throw new AssertionError("Discovery result is mutable");
		}
		catch (final UnsupportedOperationException expected) { }
		System.out.println("PASS: connector inspection SPI defaults, bounds, and metadata adapters");
	}

	private static final class LegacyConnector implements Connector
	{
		public String getType() { return "legacy"; }
		public String getLabel() { return "Legacy"; }
		public List<Operation> getOperations()
		{
			return Arrays.asList(Operation.search("customers", "Search customers"),
				Operation.write("upsert", "Write customer"));
		}
		public void configure(final ConnectorConfig pConfig) { }
		public JsonNode execute(final ConnectorContext pContext, final String pOperation,
			final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
		{
			return null;
		}
		public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation,
			final Map<String, String> pInputs)
		{
			return "customers".equals(pOperation) ? Arrays.asList(
				FieldInfo.of("id", "Id", "string"), FieldInfo.of("name", "Name", "string"))
				: Collections.<FieldInfo>emptyList();
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
