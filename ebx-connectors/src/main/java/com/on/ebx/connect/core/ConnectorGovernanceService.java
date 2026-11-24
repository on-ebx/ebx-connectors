package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Approval, publication, precedence and dependency-impact rules for governed configurations. */
public final class ConnectorGovernanceService
{
	private final ConnectorGovernanceStore store;
	public ConnectorGovernanceService(final ConnectorGovernanceStore pStore)
	{
		if (pStore == null) throw new IllegalArgumentException("Governance store is required");
		store = pStore;
	}
	public void register(final ConnectorGovernanceRecord pRecord, final String pActor)
	{
		store.save(pRecord); audit(pRecord.getResourceId(), "CONFIGURED", pActor, "owner=" + pRecord.getOwner()
			+ ", secretReferences=" + pRecord.getSecretReferences().size() + ", secretProviders=" + secretProviders(pRecord));
	}
	public void submit(final String pId, final String pActor) { transition(pId, ConnectorGovernanceRecord.Approval.PENDING, "SUBMITTED", pActor); }
	public void approve(final String pId, final String pActor) { transition(pId, ConnectorGovernanceRecord.Approval.APPROVED, "APPROVED", pActor); }
	public void reject(final String pId, final String pActor) { transition(pId, ConnectorGovernanceRecord.Approval.REJECTED, "REJECTED", pActor); }
	public ConnectorGovernanceRecord publish(final String pId, final String pActor, final Date pWhen)
	{
		final ConnectorGovernanceRecord current = require(pId);
		if (!current.isEffectiveAt(pWhen)) throw new IllegalStateException("Only an approved, effective configuration can be published");
		final ConnectorGovernanceRecord published = current.published(current.getPublicationVersion() + 1);
		store.save(published);
		audit(pId, "PUBLISHED", pActor, "version=" + published.getPublicationVersion());
		return published;
	}
	/** Highest precedence wins; resource id provides a stable tie-break. */
	public ConnectorGovernanceRecord authoritative(final List<String> pCandidates, final Date pWhen)
	{
		final List<ConnectorGovernanceRecord> eligible = new ArrayList<ConnectorGovernanceRecord>();
		if (pCandidates != null) for (final String id : pCandidates)
		{
			final ConnectorGovernanceRecord r = store.find(id);
			if (r != null && r.isEffectiveAt(pWhen)) eligible.add(r);
		}
		Collections.sort(eligible, new Comparator<ConnectorGovernanceRecord>() { public int compare(final ConnectorGovernanceRecord a, final ConnectorGovernanceRecord b) {
			final int precedence = b.getSourcePrecedence() - a.getSourcePrecedence();
			return precedence != 0 ? precedence : a.getResourceId().compareTo(b.getResourceId());
		}
	});
		return eligible.isEmpty() ? null : eligible.get(0);
	}
	/** Returns direct and transitive dependants that may be affected by changing the resource. */
	public List<String> impact(final String pResourceId)
	{
		final Set<String> impacted = new HashSet<String>();
		collectImpact(pResourceId, impacted);
		final List<String> out = new ArrayList<String>(impacted);
		Collections.sort(out);
		return out;
	}
	private void collectImpact(final String pId, final Set<String> pOut)
	{
		for (final ConnectorGovernanceRecord record : store.list()) if (!pOut.contains(record.getResourceId()) && record.getDependencies().contains(pId))
		{
			pOut.add(record.getResourceId());
			collectImpact(record.getResourceId(), pOut);
		}
	}
	private void transition(final String pId, final ConnectorGovernanceRecord.Approval pApproval, final String pAction, final String pActor)
	{
		final ConnectorGovernanceRecord current = require(pId);
		if (pApproval == ConnectorGovernanceRecord.Approval.APPROVED && current.getApproval() != ConnectorGovernanceRecord.Approval.PENDING)
			throw new IllegalStateException("Only a pending configuration can be approved");
		store.save(current.withApproval(pApproval));
		audit(pId, pAction, pActor, "approval=" + pApproval);
	}
	private ConnectorGovernanceRecord require(final String pId)
	{
		final ConnectorGovernanceRecord found = store.find(pId);
		if (found == null) throw new IllegalArgumentException("Unknown governed resource: " + pId);
		return found;
	}
	private void audit(final String pId, final String pAction, final String pActor, final String pDetail)
	{
		store.appendAudit(new ConnectorGovernanceAuditEvent(pId, pAction, pActor, pDetail, new Date()));
	}
	private static String secretProviders(final ConnectorGovernanceRecord pRecord)
	{
		final Set<String> providers = new java.util.TreeSet<String>();
		for (final String reference : pRecord.getSecretReferences()) providers.add(reference.substring(0, reference.indexOf(':')).toLowerCase(java.util.Locale.ENGLISH));
		return providers.toString();
	}
}
