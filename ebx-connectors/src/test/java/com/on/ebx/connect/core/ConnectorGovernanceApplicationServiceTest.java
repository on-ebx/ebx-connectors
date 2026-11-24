package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Permission and disclosure contract for governance transport adapters. */
public final class ConnectorGovernanceApplicationServiceTest
{
	public static void main(final String[] pArgs)
	{
		final InMemoryConnectorGovernanceStore store = new InMemoryConnectorGovernanceStore();
		final ConnectorGovernanceService lifecycle = new ConnectorGovernanceService(store);
		lifecycle.register(record("connector.crm", Collections.<String>emptyList()), "owner");
		lifecycle.register(record("mapping.customer", Arrays.asList("connector.crm")), "owner");
		final ConnectorGovernanceApplicationService api = new ConnectorGovernanceApplicationService(store, new ConnectorGovernanceAccess() {
			public boolean isAllowed(final String actor, final Permission permission, final String resource) {
				return "admin".equals(actor) || ("reader".equals(actor) && permission == Permission.READ && !"mapping.customer".equals(resource));
			}});

		final ObjectNode description = api.describe("reader", "connector.crm", new Date(1000));
		check(description.path("secretReferenceCount").asInt() == 1, "secret reference count");
		check("vault".equals(description.path("secretProviders").get(0).asText()), "safe provider exposure");
		check(!description.toString().contains("customer-crm") && !description.toString().contains("apiKey"), "secret locator disclosed");
		check(api.impact("reader", "connector.crm").path("visibleImpactCount").asInt() == 0, "impact permissions were not filtered");
		expectDenied(new Runnable() { public void run() { api.submit("reader", "connector.crm"); } });
		api.submit("admin", "connector.crm"); api.approve("admin", "connector.crm");
		check(api.publish("admin", "connector.crm", new Date(1000)).path("publicationVersion").asInt() == 1, "approved publication");
		final ArrayNode audit = api.audit("admin", "connector.crm"); check(audit.size() == 4, "audit lifecycle");
		check(!audit.toString().contains("customer-crm") && !audit.toString().contains("apiKey"), "audit disclosed secret locator");
		expectDenied(new Runnable() { public void run() { api.audit("reader", "connector.crm"); } });
		System.out.println("PASS: permission-checked governance lifecycle, filtered impact and secret-safe audit API");
	}
	private static ConnectorGovernanceRecord record(final String pId, final java.util.List<String> pDependencies)
	{
		return new ConnectorGovernanceRecord(pId, "integration-team", "EBX", 50, ConnectorGovernanceRecord.Approval.DRAFT,
			new Date(0), null, 0, pDependencies, Arrays.asList("vault:connectors/customer-crm#apiKey"));
	}
	private static void expectDenied(final Runnable pRun) { try { pRun.run(); throw new AssertionError("access allowed"); } catch (final SecurityException expected) { } }
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
