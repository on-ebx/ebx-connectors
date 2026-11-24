package com.on.ebx.connect.core;

import java.util.List;

/** Persistence boundary; production adapters may store these records and audit events in EBX tables. */
public interface ConnectorGovernanceStore
{
	ConnectorGovernanceRecord find(String pResourceId);
	List<ConnectorGovernanceRecord> list();
	void save(ConnectorGovernanceRecord pRecord);
	void appendAudit(ConnectorGovernanceAuditEvent pEvent);
	List<ConnectorGovernanceAuditEvent> audit(String pResourceId);
}
