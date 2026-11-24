package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

/** Account-free checks for approval, publication, precedence, impact and secret-safe audit state. */
public final class ConnectorGovernanceServiceTest
{
	public static void main(final String[] pArgs)
	{
		final InMemoryConnectorGovernanceStore store = new InMemoryConnectorGovernanceStore();
		final ConnectorGovernanceService service = new ConnectorGovernanceService(store);
		final Date now = new Date(100000L);
		service.register(record("source.low", 10, Collections.<String>emptyList(), "env:LOW_TOKEN"), "alice");
		service.register(record("source.high", 90, Collections.<String>emptyList(), "vault:connectors/high#token"), "alice");
		service.register(record("mapping.customer", 0, Arrays.asList("source.high"), "azurekv:ebx/mapping"), "bob");
		service.register(record("publication.customer", 0, Arrays.asList("mapping.customer"), "file:/run/secrets/pub"), "bob");
		approve(service, "source.low"); approve(service, "source.high");
		check("source.high".equals(service.authoritative(Arrays.asList("source.low", "source.high"), now).getResourceId()), "source precedence");
		check(service.impact("source.high").equals(Arrays.asList("mapping.customer", "publication.customer")), "transitive impact");
		final ConnectorGovernanceRecord published = service.publish("source.high", "release-manager", now);
		check(published.getPublicationVersion() == 1, "publication version");
		check(store.audit("source.high").size() == 4, "configuration/approval/publication audit");
		check(store.audit("source.high").get(0).getDetail().contains("secretProviders=[vault]"), "secret provider is auditable");
		for (final ConnectorGovernanceAuditEvent event : store.audit("source.high"))
			check(!event.getDetail().contains("connectors/high") && !event.getDetail().contains("token"), "audit leaked secret reference");
		expect(new Runnable() { public void run() { new ConnectorGovernanceRecord("bad", "owner", "source", 1,
			ConnectorGovernanceRecord.Approval.DRAFT, null, null, 0, null, Arrays.asList("clear-secret")); } }, "clear secret rejected");
		System.out.println("PASS: connector governance approval, publication, precedence, impact and secret-reference audit");
	}
	private static ConnectorGovernanceRecord record(final String pId, final int pPrecedence, final java.util.List<String> pDependencies, final String pSecretReference)
	{
		return new ConnectorGovernanceRecord(pId, "data-platform", "EBX", pPrecedence, ConnectorGovernanceRecord.Approval.DRAFT,
			new Date(0), new Date(200000L), 0, pDependencies, Arrays.asList(pSecretReference));
	}
	private static void approve(final ConnectorGovernanceService pService, final String pId) { pService.submit(pId, "owner"); pService.approve(pId, "approver"); }
	private static void expect(final Runnable pRun, final String pMessage) { try { pRun.run(); throw new AssertionError(pMessage); } catch (final IllegalArgumentException expected) { } }
	private static void check(final boolean pCondition, final String pMessage) { if (!pCondition) throw new AssertionError(pMessage); }
}
