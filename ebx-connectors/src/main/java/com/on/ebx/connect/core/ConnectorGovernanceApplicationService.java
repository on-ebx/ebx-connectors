package com.on.ebx.connect.core;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Permission-checked, secret-safe application API for governance lifecycle and impact inspection.
 * Transport adapters must derive the actor from their authenticated EBX session, never request data.
 */
public final class ConnectorGovernanceApplicationService
{
	private final ConnectorGovernanceStore store;
	private final ConnectorGovernanceService governance;
	private final ConnectorGovernanceAccess access;

	public ConnectorGovernanceApplicationService(final ConnectorGovernanceStore pStore, final ConnectorGovernanceAccess pAccess)
	{
		if (pStore == null || pAccess == null) throw new IllegalArgumentException("Governance store and access policy are required");
		store = pStore;
		governance = new ConnectorGovernanceService(pStore);
		access = pAccess;
	}

	public ObjectNode describe(final String pActor, final String pResourceId, final Date pWhen)
	{
		require(pActor, ConnectorGovernanceAccess.Permission.READ, pResourceId);
		final ConnectorGovernanceRecord record = find(pResourceId);
		final ObjectNode out = Json.object();
		out.put("resourceId", record.getResourceId());
		out.put("owner", record.getOwner());
		out.put("sourceSystem", record.getSourceSystem());
		out.put("sourcePrecedence", record.getSourcePrecedence());
		out.put("approval", record.getApproval().name());
		out.put("effective", record.isEffectiveAt(pWhen));
		out.put("publicationVersion", record.getPublicationVersion());
		final ArrayNode dependencies = out.putArray("dependencies");
		for (final String dependency : record.getDependencies()) dependencies.add(dependency);
		// Expose provider and count for operability; never expose a vault path, key, file, or environment name.
		out.put("secretReferenceCount", record.getSecretReferences().size());
		final java.util.Set<String> providers = new java.util.TreeSet<String>();
		for (final String reference : record.getSecretReferences()) providers.add(reference.substring(0, reference.indexOf(':')).toLowerCase(java.util.Locale.ENGLISH));
		final ArrayNode providerJson = out.putArray("secretProviders");
		for (final String provider : providers) providerJson.add(provider);
		return out;
	}

	public ObjectNode impact(final String pActor, final String pResourceId)
	{
		require(pActor, ConnectorGovernanceAccess.Permission.READ, pResourceId);
		final ObjectNode out = Json.object();
		out.put("resourceId", pResourceId);
		final ArrayNode impacted = out.putArray("impactedResources");
		for (final String id : governance.impact(pResourceId)) if (access.isAllowed(pActor, ConnectorGovernanceAccess.Permission.READ, id)) impacted.add(id);
		out.put("visibleImpactCount", impacted.size());
		return out;
	}

	public void submit(final String pActor, final String pResourceId)
	{
		require(pActor, ConnectorGovernanceAccess.Permission.MANAGE, pResourceId);
		governance.submit(pResourceId, pActor);
	}
	public void approve(final String pActor, final String pResourceId)
	{
		require(pActor, ConnectorGovernanceAccess.Permission.APPROVE, pResourceId);
		governance.approve(pResourceId, pActor);
	}
	public ObjectNode publish(final String pActor, final String pResourceId, final Date pWhen)
	{
		require(pActor, ConnectorGovernanceAccess.Permission.APPROVE, pResourceId);
		governance.publish(pResourceId, pActor, pWhen);
		return describe(pActor, pResourceId, pWhen);
	}
	public ArrayNode audit(final String pActor, final String pResourceId)
	{
		require(pActor, ConnectorGovernanceAccess.Permission.AUDIT, pResourceId);
		final ArrayNode out = Json.mapper().createArrayNode();
		final List<ConnectorGovernanceAuditEvent> events = store.audit(pResourceId);
		for (final ConnectorGovernanceAuditEvent event : events)
		{
			final ObjectNode item = out.addObject();
			item.put("action", event.getAction());
			item.put("actor", event.getActor());
			item.put("occurredAt", event.getOccurredAt().getTime());
			item.put("detail", event.getDetail());
		}
		return out;
	}
	private ConnectorGovernanceRecord find(final String pId)
	{
		final ConnectorGovernanceRecord record = store.find(pId);
		if (record == null) throw new IllegalArgumentException("Unknown governed resource: " + pId);
		return record;
	}
	private void require(final String pActor, final ConnectorGovernanceAccess.Permission pPermission, final String pResourceId)
	{
		if (pActor == null || pActor.trim().isEmpty() || !access.isAllowed(pActor, pPermission, pResourceId))
			throw new SecurityException("Governance access denied");
	}
}
