package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic store used by tests and embedders that provide their own durable adapter. */
public final class InMemoryConnectorGovernanceStore implements ConnectorGovernanceStore
{
	private final Map<String, ConnectorGovernanceRecord> records = new LinkedHashMap<String, ConnectorGovernanceRecord>();
	private final List<ConnectorGovernanceAuditEvent> events = new ArrayList<ConnectorGovernanceAuditEvent>();
	public synchronized ConnectorGovernanceRecord find(final String pId) { return records.get(pId); }
	public synchronized List<ConnectorGovernanceRecord> list() { return new ArrayList<ConnectorGovernanceRecord>(records.values()); }
	public synchronized void save(final ConnectorGovernanceRecord pRecord) { records.put(pRecord.getResourceId(), pRecord); }
	public synchronized void appendAudit(final ConnectorGovernanceAuditEvent pEvent) { events.add(pEvent); }
	public synchronized List<ConnectorGovernanceAuditEvent> audit(final String pId)
	{
		final List<ConnectorGovernanceAuditEvent> out = new ArrayList<ConnectorGovernanceAuditEvent>();
		for (final ConnectorGovernanceAuditEvent event : events) if (event.getResourceId().equals(pId)) out.add(event);
		return out;
	}
}
