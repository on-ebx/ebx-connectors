package com.on.ebx.connect.core;

import java.util.Date;

/** Immutable, secret-safe audit entry for a configuration governance transition. */
public final class ConnectorGovernanceAuditEvent
{
	private final String resourceId, action, actor, detail;
	private final Date occurredAt;
	public ConnectorGovernanceAuditEvent(final String pResourceId, final String pAction, final String pActor,
		final String pDetail, final Date pOccurredAt)
	{
		resourceId = required(pResourceId);
		action = required(pAction);
		actor = required(pActor);
		detail = SensitiveDataMasker.empty().mask(pDetail == null ? "" : pDetail);
		occurredAt = new Date((pOccurredAt == null ? new Date() : pOccurredAt).getTime());
	}
	public String getResourceId() { return resourceId; }
	public String getAction() { return action; }
	public String getActor() { return actor; }
	public String getDetail() { return detail; }
	public Date getOccurredAt() { return new Date(occurredAt.getTime()); }
	private static String required(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty()) throw new IllegalArgumentException("Audit identity is required");
		return pValue.trim();
	}
}
