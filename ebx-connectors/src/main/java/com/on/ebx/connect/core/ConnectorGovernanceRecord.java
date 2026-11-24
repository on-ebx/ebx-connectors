package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Versioned governance metadata attached to a connector or mapping configuration. */
public final class ConnectorGovernanceRecord
{
	public enum Approval { DRAFT, PENDING, APPROVED, REJECTED }

	private final String resourceId, owner, sourceSystem;
	private final int sourcePrecedence, publicationVersion;
	private final Approval approval;
	private final Date effectiveFrom, effectiveUntil;
	private final List<String> dependencies, secretReferences;

	public ConnectorGovernanceRecord(final String pResourceId, final String pOwner, final String pSourceSystem,
		final int pSourcePrecedence, final Approval pApproval, final Date pEffectiveFrom, final Date pEffectiveUntil,
		final int pPublicationVersion, final List<String> pDependencies, final List<String> pSecretReferences)
	{
		this.resourceId = required(pResourceId, "Resource id");
		this.owner = required(pOwner, "Owner");
		this.sourceSystem = required(pSourceSystem, "Source system");
		if (pSourcePrecedence < 0) throw new IllegalArgumentException("Source precedence cannot be negative");
		if (pPublicationVersion < 0) throw new IllegalArgumentException("Publication version cannot be negative");
		if (pEffectiveFrom != null && pEffectiveUntil != null && pEffectiveUntil.before(pEffectiveFrom))
			throw new IllegalArgumentException("Effective-until cannot precede effective-from");
		this.sourcePrecedence = pSourcePrecedence;
		this.approval = pApproval == null ? Approval.DRAFT : pApproval;
		this.effectiveFrom = copy(pEffectiveFrom);
		this.effectiveUntil = copy(pEffectiveUntil);
		this.publicationVersion = pPublicationVersion;
		this.dependencies = immutable(pDependencies);
		this.secretReferences = validateReferences(pSecretReferences);
	}

	public String getResourceId() { return resourceId; }
	public String getOwner() { return owner; }
	public String getSourceSystem() { return sourceSystem; }
	public int getSourcePrecedence() { return sourcePrecedence; }
	public Approval getApproval() { return approval; }
	public Date getEffectiveFrom() { return copy(effectiveFrom); }
	public Date getEffectiveUntil() { return copy(effectiveUntil); }
	public int getPublicationVersion() { return publicationVersion; }
	public List<String> getDependencies() { return dependencies; }
	public List<String> getSecretReferences() { return secretReferences; }
	public boolean isEffectiveAt(final Date pWhen)
	{
		final Date when = pWhen == null ? new Date() : pWhen;
		return approval == Approval.APPROVED && (effectiveFrom == null || !when.before(effectiveFrom))
			&& (effectiveUntil == null || !when.after(effectiveUntil));
	}

	public ConnectorGovernanceRecord withApproval(final Approval pApproval)
	{
		return new ConnectorGovernanceRecord(resourceId, owner, sourceSystem, sourcePrecedence, pApproval,
			effectiveFrom, effectiveUntil, publicationVersion, dependencies, secretReferences);
	}
	public ConnectorGovernanceRecord published(final int pVersion)
	{
		return new ConnectorGovernanceRecord(resourceId, owner, sourceSystem, sourcePrecedence, approval,
			effectiveFrom, effectiveUntil, pVersion, dependencies, secretReferences);
	}

	private static String required(final String pValue, final String pLabel)
	{
		if (pValue == null || pValue.trim().isEmpty()) throw new IllegalArgumentException(pLabel + " is required");
		return pValue.trim();
	}
	private static Date copy(final Date pDate) { return pDate == null ? null : new Date(pDate.getTime()); }
	private static List<String> immutable(final List<String> pValues)
	{
		return Collections.unmodifiableList(new ArrayList<String>(pValues == null ? Collections.<String>emptyList() : pValues));
	}
	private static List<String> validateReferences(final List<String> pValues)
	{
		final List<String> out = new ArrayList<String>();
		if (pValues != null) for (final String value : pValues)
		{
			if (value == null || !value.matches("(?i)(vault|env|file|azurekv):.+"))
				throw new IllegalArgumentException("Governance stores secret references only");
			out.add(value);
		}
		return Collections.unmodifiableList(out);
	}
}
