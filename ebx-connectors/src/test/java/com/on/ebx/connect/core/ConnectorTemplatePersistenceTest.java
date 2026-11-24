package com.on.ebx.connect.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Deterministic account-free checks for the persistent template lifecycle contract. */
public final class ConnectorTemplatePersistenceTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final MemoryStore store = new MemoryStore();
		final ConnectorTemplateService service = new ConnectorTemplateService(store);
		store.create(packaged("product.salesforce", 1, "old-secret"), "installer");

		final ConnectorTemplate customer = service.copy("product.salesforce", "customer.salesforce", "alice");
		check(customer.getBasedOnVersion() == 1 && store.find("customer.salesforce") != null, "copy provenance");
		final String exported = service.exportTemplates();
		check(!exported.contains("old-secret") && exported.contains("\"password\":\"***\""), "export redaction");

		final String v2 = ConnectorTemplateTransfer.exportTemplates(java.util.Collections.singletonList(packaged("product.salesforce", 2, "***")));
		check(service.importTemplates(v2, "installer") == 1 && store.find("product.salesforce").getVersion() == 2, "packaged upgrade");
		final ConnectorTemplate upgraded = service.upgradeCopy("customer.salesforce", "customer.salesforce.v2", "alice");
		check(upgraded.getBasedOnVersion() == 2, "upgrade provenance");
		check(store.find("customer.salesforce").getBasedOnVersion() == 1, "old customer copy preserved");

		expectFailure(new Action() { @Override public void run() throws Exception { service.copy("product.salesforce", "customer.salesforce", "bob"); } }, "copy overwrite");
		expectFailure(new Action() { @Override public void run() throws Exception { service.importTemplates(
			ConnectorTemplateTransfer.exportTemplates(java.util.Collections.singletonList(customer)), "bob"); } }, "customer import overwrite");
		expectFailure(new Action() { @Override public void run() throws Exception { service.importTemplates(
			ConnectorTemplateTransfer.exportTemplates(java.util.Collections.singletonList(packaged("product.secret", 1, "live-value"))), "bob"); } }, "secret import");
		System.out.println("ConnectorTemplatePersistenceTest OK");
	}

	private static ConnectorTemplate packaged(final String pId, final int pVersion, final String pSecret)
	{
		final ObjectNode config = Json.object();
		config.put("type", "salesforce"); config.put("password", pSecret);
		return new ConnectorTemplate(pId, pVersion, ConnectorTemplate.Ownership.PACKAGED, null, 0, config);
	}

	private interface Action { void run() throws Exception; }
	private static void expectFailure(final Action pAction, final String pLabel) throws Exception
	{
		try { pAction.run(); throw new AssertionError("Expected failure: " + pLabel); }
		catch (final IllegalArgumentException ex) { /* expected */ }
		catch (final IllegalStateException ex) { /* expected */ }
	}
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }

	private static final class MemoryStore implements ConnectorTemplateStore
	{
		private final Map<String, ConnectorTemplate> rows = new LinkedHashMap<String, ConnectorTemplate>();
		@Override public ConnectorTemplate find(final String pId) { return this.rows.get(pId); }
		@Override public List<ConnectorTemplate> list() { return new ArrayList<ConnectorTemplate>(this.rows.values()); }
		@Override public void create(final ConnectorTemplate pTemplate, final String pActor) throws IOException
		{
			if (this.rows.containsKey(pTemplate.getId())) throw new IOException("duplicate");
			this.rows.put(pTemplate.getId(), pTemplate);
		}
		@Override public void replacePackaged(final ConnectorTemplate pTemplate, final String pActor)
		{
			final ConnectorTemplate existing = this.rows.get(pTemplate.getId());
			if (existing == null || existing.getOwnership() != ConnectorTemplate.Ownership.PACKAGED) throw new IllegalStateException("not packaged");
			this.rows.put(pTemplate.getId(), pTemplate);
		}
	}
}
