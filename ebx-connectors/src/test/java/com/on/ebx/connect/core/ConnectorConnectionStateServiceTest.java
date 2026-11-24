package com.on.ebx.connect.core;

import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.Operation;
import com.fasterxml.jackson.databind.JsonNode;

/** Contract checks for governed logical connection state. */
public final class ConnectorConnectionStateServiceTest
{
	private ConnectorConnectionStateServiceTest() { }
	public static void main(final String[] pArgs) throws Exception
	{
		final MemoryStore store = new MemoryStore(); final ConnectorConnectionStateService service = new ConnectorConnectionStateService(store);
		final ConnectorConfig config = config();
		check(service.status(config).getStatus() == ConnectorConnectionStateService.Status.NOT_CONNECTED, "new connector appeared connected");
		final Connector connector = new Connector() {
			public String getType() { return "postgresql"; }
			public String getLabel() { return "PostgreSQL"; }
			public java.util.List<Operation> getOperations() { return java.util.Collections.emptyList(); }
			public void configure(final ConnectorConfig pConfig) { }
			public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload) { return null; }
			@Override public ConnectionTestResult testConnection(final ConnectorContext pContext) { return ConnectionTestResult.success("PostgreSQL verified", 4); }
		};
		final ConnectorConnectionStateService.State connected = service.verifyAndRecord(connector, null, config);
		check(connected.getStatus() == ConnectorConnectionStateService.Status.CONNECTED && connected.getVerifiedAt() != null, "successful Connect was not persisted");
		check(service.status(config).getStatus() == ConnectorConnectionStateService.Status.CONNECTED, "unchanged settings became stale");
		config.setBaseUrl("jdbc:postgresql://other:5432/ebx");
		check(service.status(config).getStatus() == ConnectorConnectionStateService.Status.STALE, "endpoint change did not invalidate verification");
		config.setBaseUrl("jdbc:postgresql://localhost:5432/ebx"); config.setPassword("changed");
		check(service.status(config).getStatus() == ConnectorConnectionStateService.Status.STALE, "credential change did not invalidate verification");
		service.verifyAndRecord(connector, null, config); config.setProperty("sslmode", "verify-full");
		check(service.status(config).getStatus() == ConnectorConnectionStateService.Status.STALE, "connection property change did not invalidate verification");
		check(service.disconnect(config).getStatus() == ConnectorConnectionStateService.Status.NOT_CONNECTED, "Disconnect did not revoke verification");
		check(service.status(config).getStatus() == ConnectorConnectionStateService.Status.NOT_CONNECTED, "disconnected connector remained connected");
		final Connector unverifiable = new Connector() {
			public String getType() { return "chat-webhook"; }
			public String getLabel() { return "Teams webhook"; }
			public java.util.List<Operation> getOperations() { return java.util.Collections.emptyList(); }
			public void configure(final ConnectorConfig pConfig) { }
			public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload) { return null; }
			@Override public ConnectionTestResult testConnection(final ConnectorContext pContext) { return ConnectionTestResult.unsupported("cannot be verified without posting"); }
		};
		final ConnectorConnectionStateService.State unverified = service.verifyAndRecord(unverifiable, null, config);
		check(unverified.getStatus() == ConnectorConnectionStateService.Status.NOT_CONNECTED && unverified.getMessage().contains("without posting"), "an unsupported check was recorded as a failure");
		check(service.status(config).getStatus() == ConnectorConnectionStateService.Status.NOT_CONNECTED, "unsupported check did not persist as not connected");
		System.out.println("PASS: governed connector connection verification lifecycle");
	}
	private static ConnectorConfig config() { final ConnectorConfig c = new ConnectorConfig(); c.setId("PostgreSQL"); c.setType("postgresql"); c.setBaseUrl("jdbc:postgresql://localhost:5432/ebx"); c.setUsername("ebx"); c.setPassword("secret"); return c; }
	private static final class MemoryStore implements ConnectorConnectionStateService.Store { private final Map<String, ConnectorConnectionStateService.State> states = new LinkedHashMap<String, ConnectorConnectionStateService.State>(); public ConnectorConnectionStateService.State load(final String pId) { return states.get(pId); } public void save(final String pId, final ConnectorConnectionStateService.State pState) { states.put(pId, pState); } }
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
