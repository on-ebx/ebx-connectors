package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.DiscoveredObject;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;
import com.on.ebx.connect.spi.Operation;
import com.on.ebx.connect.spi.PreviewResult;

/** Focused contracts for post-implementation inspection safety enforcement. */
public final class BoundedInspectionRuntimeTest
{
	private BoundedInspectionRuntimeTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		boundsMasksAndCaches();
		timesOutUncooperativeInspection();
		masksConnectorFailures();
		System.out.println("PASS: bounded inspection runtime limits, masks, caches and times out read-only SPI calls");
	}

	private static void masksConnectorFailures() throws Exception
	{
		final BoundedInspectionRuntime runtime = new BoundedInspectionRuntime(
			PreviewRequest.bounded(5, 1000, 1000), new MetadataCache<Object>(1000));
		final InspectableConnector connector = new InspectableConnector(false)
		{
			@Override public ObjectDiscoveryResult discoverObjects(final ConnectorContext pContext,
				final ObjectDiscoveryRequest pRequest) throws ConnectorException
			{
				throw new ConnectorException("Remote rejected s3cr3t-token", 401, null);
			}
		};
		try
		{
			runtime.discover(connector, null, config(), ObjectDiscoveryRequest.firstPage("", 5));
			throw new AssertionError("Expected connector failure");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getStatus() == 401, "Connector status was not preserved");
			check(!expected.getMessage().contains("s3cr3t-token"), "Connector failure retained a configured secret");
		}
	}

	private static void boundsMasksAndCaches() throws Exception
	{
		final ConnectorConfig config = config();
		final InspectableConnector connector = new InspectableConnector(false);
		final BoundedInspectionRuntime runtime = new BoundedInspectionRuntime(
			PreviewRequest.bounded(2, 1000, 80), new MetadataCache<Object>(1000));
		final ObjectDiscoveryRequest discoveryRequest = ObjectDiscoveryRequest.firstPage("", 500);
		final ObjectDiscoveryResult first = runtime.discover(connector, null, config, discoveryRequest);
		final ObjectDiscoveryResult second = runtime.discover(connector, null, config, discoveryRequest);
		check(first.getObjects().size() == 2, "Discovery row ceiling was not enforced");
		check(first.isTruncated(), "Discovery truncation was not reported");
		check(first.getContinuationToken() != null && !first.getContinuationToken().contains("s3cr3t-token"),
			"Truncation discarded the cursor or exposed its secret");
		check(connector.discoveryCalls.get() == 1, "Discovery metadata was not cached");
		check(second == first, "Cached safe discovery result was not reused");
		check(!first.getObjects().toString().contains("s3cr3t-token"), "Discovery retained a configured secret");

		final InspectionPreview preview = runtime.preview(connector, null, config,
			new com.on.ebx.connect.spi.PreviewRequest("items", "items", null, 100));
		check(preview.getRecords().size() <= 2, "Preview row ceiling was not enforced");
		check(preview.getPayloadBytes() <= 80, "Preview payload ceiling was not enforced");
		check(preview.isTruncated(), "Bounded preview was not marked truncated");
		check(!preview.getRecords().toString().contains("s3cr3t-token"), "Preview retained a configured secret");
	}

	private static void timesOutUncooperativeInspection() throws Exception
	{
		final BoundedInspectionRuntime runtime = new BoundedInspectionRuntime(
			PreviewRequest.bounded(5, 20, 1000), new MetadataCache<Object>(1000));
		try
		{
			runtime.discover(new InspectableConnector(true), null, config(), ObjectDiscoveryRequest.firstPage("", 5));
			throw new AssertionError("Slow discovery did not time out");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("time limit"), "Timeout failure was not explicit");
		}
	}

	private static ConnectorConfig config()
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("test"); config.setType("inspectable"); config.setBaseUrl("https://example.test");
		config.setApiKey("s3cr3t-token"); config.setProperty("clientSecret", "client-secret");
		return config;
	}

	private static class InspectableConnector implements Connector
	{
		private final boolean slow;
		private final AtomicInteger discoveryCalls = new AtomicInteger();
		private InspectableConnector(final boolean pSlow) { this.slow = pSlow; }
		public String getType() { return "inspectable"; }
		public String getLabel() { return "Inspectable"; }
		public List<Operation> getOperations() { return Collections.singletonList(Operation.search("items", "Items")); }
		public void configure(final ConnectorConfig pConfig) { }
		public JsonNode execute(final ConnectorContext pContext, final String pOperation,
			final Map<String, String> pInputs, final JsonNode pPayload) { return null; }
		@Override public ObjectDiscoveryResult discoverObjects(final ConnectorContext pContext,
			final ObjectDiscoveryRequest pRequest) throws ConnectorException
		{
			this.discoveryCalls.incrementAndGet();
			if (this.slow) try { Thread.sleep(1000); } catch (final InterruptedException ex) { Thread.currentThread().interrupt(); }
			final List<DiscoveredObject> objects = new ArrayList<DiscoveredObject>();
			for (int i = 0; i < 5; i++) objects.add(new DiscoveredObject("item" + i, "s3cr3t-token item " + i, "table", true));
			return ObjectDiscoveryResult.page(objects, "next-s3cr3t-token");
		}
		@Override public PreviewResult preview(final ConnectorContext pContext,
			final com.on.ebx.connect.spi.PreviewRequest pRequest)
		{
			final List<JsonNode> records = new ArrayList<JsonNode>();
			for (int i = 0; i < 5; i++)
			{
				final ObjectNode record = Json.object();
				record.put("id", i); record.put("authorization", "Bearer s3cr3t-token"); record.put("padding", "12345678901234567890");
				records.add(record);
			}
			return PreviewResult.of(records, false);
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
