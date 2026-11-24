package com.on.ebx.connect.contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.connectors.Dynamics365Connector;
import com.on.ebx.connect.connectors.SapODataConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.ODataBatchAdapter;
import com.on.ebx.connect.spi.BatchRequest;
import com.on.ebx.connect.spi.BatchResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Operation;

/** Deterministic contract tests for bounded fallback execution and OData response normalization. */
public final class ConnectorBatchContractTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		fallbackIsOrderedAndIsolatesErrors();
		limitsAreEnforcedBeforeExecution();
		odataResultsAreOrderedAndNormalized();
		responseStreamingBoundStopsEarly();
		nativeODataAdoptionIsExplicit();
		System.out.println("PASS: bounded connector batch contract");
	}

	private static void fallbackIsOrderedAndIsolatesErrors() throws Exception
	{
		final Stub connector = new Stub();
		final BatchResult result = connector.executeBatch(null, new BatchRequest(Arrays.asList(
			item("a", "read", "1", null), item("b", "read", "bad", null), item("c", "write", "3", Json.parse("{\"x\":1}"))), 4096));
		check(!result.isNativeBatch() && result.getItems().size() == 3, "fallback shape");
		check("a".equals(result.getItems().get(0).getId()) && result.getItems().get(0).isSuccess(), "first result order");
		check(!result.getItems().get(1).isSuccess() && result.getItems().get(1).getStatus() == 422, "per-item error");
		check("c".equals(result.getItems().get(2).getId()) && result.getItems().get(2).isSuccess(), "execution did not continue");
	}

	private static void limitsAreEnforcedBeforeExecution() throws Exception
	{
		final Stub connector = new Stub();
		try { connector.executeBatch(null, new BatchRequest(Collections.singletonList(item("a", "read", "a very long value", null)), 3)); throw new AssertionError("byte bound accepted"); }
		catch (final ConnectorException expected) { check(expected.getMessage().contains("byte limit"), "byte limit message"); }
		check(connector.calls == 0, "validation executed an item");
		final List<BatchRequest.Item> writes = new ArrayList<BatchRequest.Item>();
		for (int i = 0; i <= BatchRequest.HARD_MAX_WRITE_ITEMS; i++) writes.add(item("w" + i, "write", String.valueOf(i), Json.object()));
		try { connector.executeBatch(null, new BatchRequest(writes, 100000)); throw new AssertionError("write item bound accepted"); }
		catch (final ConnectorException expected) { check(expected.getMessage().contains("write items"), "write bound message"); }
		check(connector.calls == 0, "write validation executed an item");
	}

	private static void odataResultsAreOrderedAndNormalized() throws Exception
	{
		final BatchRequest request = new BatchRequest(Arrays.asList(item("one", "read", "1", null), item("two", "read", "2", null), item("three", "read", "3", null)), 4096);
		final JsonNode response = Json.parse("{\"responses\":["
			+ "{\"id\":\"two\",\"status\":404,\"body\":{\"error\":{\"message\":\"missing\"}}},"
			+ "{\"id\":\"one\",\"status\":200,\"body\":{\"Name\":\"Ada\",\"@odata.etag\":\"x\"}}]}");
		final BatchResult result = ODataBatchAdapter.results(request, response);
		check(result.isNativeBatch() && "one".equals(result.getItems().get(0).getId()), "request order not retained");
		check(result.getItems().get(0).getDocument().get("@odata.etag") == null, "OData annotation retained");
		check(result.getItems().get(1).getStatus() == 404 && !result.getItems().get(1).isSuccess(), "HTTP error not normalized");
		check(result.getItems().get(2).getStatus() == 0 && result.getItems().get(2).getError().contains("omitted"), "missing response not represented");
		check("People?%24top=2".equals(ODataBatchAdapter.relativeUrl("People", Collections.singletonMap("$top", "2"))), "relative query encoding");
	}

	private static void nativeODataAdoptionIsExplicit() throws Exception
	{
		check(SapODataConnector.class.getDeclaredMethod("executeBatch", ConnectorContext.class, BatchRequest.class) != null, "SAP native batch missing");
		check(Dynamics365Connector.class.getDeclaredMethod("executeBatch", ConnectorContext.class, BatchRequest.class) != null, "Dynamics native batch missing");
	}

	private static void responseStreamingBoundStopsEarly() throws Exception
	{
		final java.lang.reflect.Method read = com.on.ebx.connect.core.HttpJsonClient.class.getDeclaredMethod(
			"readAll", java.io.InputStream.class, Integer.TYPE);
		read.setAccessible(true);
		try
		{
			read.invoke(null, new java.io.ByteArrayInputStream(new byte[9]), Integer.valueOf(8));
			throw new AssertionError("streaming response bound accepted");
		}
		catch (final java.lang.reflect.InvocationTargetException expected)
		{
			check(expected.getCause() instanceof java.io.IOException && expected.getCause().getMessage().contains("byte limit"), "streaming bound error");
		}
	}

	private static BatchRequest.Item item(final String pId, final String pOperation, final String pValue, final JsonNode pPayload)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>(); inputs.put("id", pValue);
		return new BatchRequest.Item(pId, pOperation, inputs, pPayload);
	}
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }

	private static final class Stub implements Connector
	{
		private int calls;
		public String getType() { return "stub"; }
		public String getLabel() { return "Stub"; }
		public List<Operation> getOperations() { return Arrays.asList(Operation.read("read", "read", "id"), Operation.write("write", "write", "id")); }
		public void configure(final ConnectorConfig pConfig) { }
		public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
		{
			calls++;
			if ("bad".equals(pInputs.get("id"))) throw new ConnectorException("invalid", 422, null);
			return pPayload == null ? Json.fromJava(pInputs.get("id")) : pPayload;
		}
	}
}
