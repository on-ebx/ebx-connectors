package com.on.ebx.connect.ebx.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Operation;

/**
 * connectors_call must forward a parsed {@code document} as the payload of a WRITE operation
 * (previously always {@code null}, so e.g. SalesforceConnector.call() posted {@code {}} and the
 * remote API answered "Required fields are missing"), and must refuse one on a read/search
 * operation. Exercises {@link ConnectorsTools#executeCall}, the part of {@code connectors_call}
 * that is independent of an EBX session/repository, against a fake {@link Connector}.
 */
public final class ConnectorsToolsCallDocumentTest
{
	private ConnectorsToolsCallDocumentTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		writeCallForwardsDocument();
		writeCallWithoutAllowWriteIsRefused();
		writeCallWithoutDocumentPostsNull();
		nonWriteCallWithDocumentIsRefused();
		nonWriteCallWithoutDocumentIsUnaffected();
		catalogListsDocumentParameter();
		System.out.println("PASS: connectors_call forwards the document payload to a WRITE operation");
	}

	private static void writeCallForwardsDocument() throws Exception
	{
		final CapturingConnector connector = new CapturingConnector();
		final ObjectNode document = Json.object();
		document.put("Name", "Acme");
		ConnectorsTools.executeCall(null, connector, "sfdc", "create", null, true, document);
		check(connector.lastPayload != null && connector.lastPayload.isObject(), "Payload not forwarded");
		check("Acme".equals(connector.lastPayload.get("Name").asText()), "Wrong payload forwarded");
	}

	private static void writeCallWithoutAllowWriteIsRefused() throws Exception
	{
		final CapturingConnector connector = new CapturingConnector();
		try
		{
			ConnectorsTools.executeCall(null, connector, "sfdc", "create", null, false, Json.object());
			throw new AssertionError("A write without allowWrite must be refused");
		}
		catch (final IllegalArgumentException expected)
		{
			check(expected.getMessage().contains("allowWrite"), "Wrong refusal message: " + expected.getMessage());
		}
		check(connector.lastPayload == null && !connector.executed, "Connector must not run when the write is refused");
	}

	private static void writeCallWithoutDocumentPostsNull() throws Exception
	{
		final CapturingConnector connector = new CapturingConnector();
		ConnectorsTools.executeCall(null, connector, "sfdc", "create", null, true, null);
		check(connector.executed, "Connector was not called");
		check(connector.lastPayload == null, "No document was passed: payload should stay null");
	}

	private static void nonWriteCallWithDocumentIsRefused() throws Exception
	{
		final CapturingConnector connector = new CapturingConnector();
		try
		{
			ConnectorsTools.executeCall(null, connector, "sfdc", "search", null, false, Json.object());
			throw new AssertionError("A document on a read/search operation must be refused");
		}
		catch (final IllegalArgumentException expected)
		{
			check(expected.getMessage().contains("not a write operation"), "Wrong refusal message: " + expected.getMessage());
		}
		check(!connector.executed, "Connector must not run when the document is refused");
	}

	private static void nonWriteCallWithoutDocumentIsUnaffected() throws Exception
	{
		final CapturingConnector connector = new CapturingConnector();
		final ObjectNode out = ConnectorsTools.executeCall(null, connector, "sfdc", "search", null, false, null);
		check(connector.executed, "Connector was not called");
		check(out.has("document"), "Result must carry the connector's own document field");
	}

	private static void catalogListsDocumentParameter()
	{
		final ConnectorsTools.Tool call = ConnectorsTools.tool(ConnectorsTools.T_CALL);
		check(call != null, "connectors_call missing from the catalogue");
		ConnectorsTools.Param document = null;
		for (final ConnectorsTools.Param param : call.params)
		{
			if ("document".equals(param.name))
			{
				document = param;
			}
		}
		check(document != null, "connectors_call schema does not list 'document'");
		check(!document.required, "'document' must be optional (only meaningful for a WRITE operation)");
		check("object".equals(document.type), "'document' must be a JSON object parameter");
	}

	/** A fake connector that only captures what it was called with; no EBX/network dependency. */
	private static final class CapturingConnector implements Connector
	{
		private boolean executed;
		private JsonNode lastPayload;

		public String getType() { return "fake"; }
		public String getLabel() { return "Fake"; }

		public List<Operation> getOperations()
		{
			return Arrays.asList(Operation.search("search", "Search"), Operation.write("create", "Create"));
		}

		public void configure(final com.on.ebx.connect.spi.ConnectorConfig pConfig) { }

		public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
			throws ConnectorException
		{
			this.executed = true;
			this.lastPayload = pPayload;
			return Json.object();
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
