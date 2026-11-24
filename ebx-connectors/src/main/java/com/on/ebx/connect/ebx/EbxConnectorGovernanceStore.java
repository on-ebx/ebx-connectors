package com.on.ebx.connect.ebx;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import com.on.ebx.connect.core.ConnectorGovernanceAuditEvent;
import com.on.ebx.connect.core.ConnectorGovernanceRecord;
import com.on.ebx.connect.core.ConnectorGovernanceStore;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/** EBX-table implementation of governed connector/mapping configuration state and audit history. */
public final class EbxConnectorGovernanceStore implements ConnectorGovernanceStore
{
	interface Backend
	{
		GovernanceRow find(String pId);
		List<GovernanceRow> list();
		void save(GovernanceRow pRow);
		void append(AuditRow pRow);
		List<AuditRow> audit(String pResourceId);
	}
	static final class GovernanceRow
	{
		String resourceId, owner, sourceSystem, approval, dependencies, secretReferences;
		int sourcePrecedence, publicationVersion;
		Date effectiveFrom, effectiveUntil;
	}
	static final class AuditRow
	{
		String resourceId, action, actor, detail;
		Date occurredAt;
	}

	private final Backend backend;
	public EbxConnectorGovernanceStore(final Repository pRepository, final Session pSession) { this(new EbxBackend(pRepository, pSession)); }
	EbxConnectorGovernanceStore(final Backend pBackend)
	{
		if (pBackend == null) throw new IllegalArgumentException("Governance backend is required");
		backend = pBackend;
	}
	@Override public ConnectorGovernanceRecord find(final String pResourceId) { return from(backend.find(pResourceId)); }
	@Override public List<ConnectorGovernanceRecord> list()
	{
		final List<ConnectorGovernanceRecord> out = new ArrayList<ConnectorGovernanceRecord>();
		for (final GovernanceRow row : backend.list()) out.add(from(row));
		Collections.sort(out, new Comparator<ConnectorGovernanceRecord>()
		{
			@Override
			public int compare(final ConnectorGovernanceRecord a, final ConnectorGovernanceRecord b)
			{
				return a.getResourceId().compareTo(b.getResourceId());
			}
		});
		return Collections.unmodifiableList(out);
	}
	@Override public void save(final ConnectorGovernanceRecord pRecord)
	{
		if (pRecord == null) throw new IllegalArgumentException("Governance record is required");
		final GovernanceRow row = new GovernanceRow();
		row.resourceId = pRecord.getResourceId();
		row.owner = pRecord.getOwner();
		row.sourceSystem = pRecord.getSourceSystem();
		row.sourcePrecedence = pRecord.getSourcePrecedence();
		row.approval = pRecord.getApproval().name();
		row.effectiveFrom = pRecord.getEffectiveFrom();
		row.effectiveUntil = pRecord.getEffectiveUntil();
		row.publicationVersion = pRecord.getPublicationVersion();
		row.dependencies = encode(pRecord.getDependencies());
		row.secretReferences = encode(pRecord.getSecretReferences());
		backend.save(row);
	}
	@Override public void appendAudit(final ConnectorGovernanceAuditEvent pEvent)
	{
		if (pEvent == null) throw new IllegalArgumentException("Audit event is required");
		final AuditRow row = new AuditRow();
		row.resourceId = pEvent.getResourceId();
		row.action = pEvent.getAction();
		row.actor = pEvent.getActor();
		row.detail = pEvent.getDetail();
		row.occurredAt = pEvent.getOccurredAt();
		backend.append(row);
	}
	@Override public List<ConnectorGovernanceAuditEvent> audit(final String pResourceId)
	{
		final List<ConnectorGovernanceAuditEvent> out = new ArrayList<ConnectorGovernanceAuditEvent>();
		for (final AuditRow row : backend.audit(pResourceId)) out.add(new ConnectorGovernanceAuditEvent(row.resourceId, row.action, row.actor, row.detail, row.occurredAt));
		Collections.sort(out, new Comparator<ConnectorGovernanceAuditEvent>()
		{
			@Override
			public int compare(final ConnectorGovernanceAuditEvent a, final ConnectorGovernanceAuditEvent b)
			{
				return a.getOccurredAt().compareTo(b.getOccurredAt());
			}
		});
		return Collections.unmodifiableList(out);
	}
	private static ConnectorGovernanceRecord from(final GovernanceRow pRow)
	{
		if (pRow == null) return null;
		return new ConnectorGovernanceRecord(pRow.resourceId, pRow.owner, pRow.sourceSystem, pRow.sourcePrecedence,
			ConnectorGovernanceRecord.Approval.valueOf(pRow.approval), pRow.effectiveFrom, pRow.effectiveUntil,
			pRow.publicationVersion, decode(pRow.dependencies), decode(pRow.secretReferences));
	}
	static String encode(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder(); if (pValues != null) for (final String value : pValues)
		{
			if (value == null) throw new IllegalArgumentException("Governance list values cannot be null");
			if (out.length() > 0) out.append(',');
			out.append(Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
		}
		return out.toString();
	}
	static List<String> decode(final String pValue)
	{
		final List<String> out = new ArrayList<String>(); if (pValue != null && !pValue.isEmpty()) for (final String item : pValue.split(","))
			out.add(new String(Base64.getUrlDecoder().decode(item), StandardCharsets.UTF_8));
			return out;
	}

	private static final class EbxBackend implements Backend
	{
		private final Repository repository;
		private final Session session;
		EbxBackend(final Repository pRepository, final Session pSession)
		{
			if (pRepository == null || pSession == null) throw new IllegalArgumentException("Repository and session are required");
			repository = pRepository;
			session = pSession;
		}
		private Adaptation dataset() { return new com.on.ebx.connect.core.ConnectorRepository(repository).dataset(); }
		private AdaptationTable table(final com.orchestranetworks.schema.Path pPath)
		{
			final Adaptation dataset = dataset(); if (dataset == null || dataset.getSchemaNode().getNode(pPath) == null)
				throw new IllegalStateException(pPath.format() + " table is unavailable; reload the Connectors data model");
				return dataset.getTable(pPath);
		}
		@Override public GovernanceRow find(final String pId)
		{
			final Adaptation row = table(ConnectorsPaths.CONNECTOR_GOVERNANCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pId));
			return row == null ? null : governance(row);
		}
		@Override public List<GovernanceRow> list()
		{
			final List<GovernanceRow> out = new ArrayList<GovernanceRow>();
			final RequestResult rows = table(ConnectorsPaths.CONNECTOR_GOVERNANCE).createRequest().execute();
			try
			{
				for (Adaptation row; (row = rows.nextAdaptation()) != null;) out.add(governance(row));
			}
			finally
			{
				rows.close();
			}
			return out;
		}
		@Override public void save(final GovernanceRow pRow)
		{
			final AdaptationTable table = table(ConnectorsPaths.CONNECTOR_GOVERNANCE);
			final Adaptation existing = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRow.resourceId));
			write(table, existing, new Writer() { public void set(final ValueContextForUpdate v) {
				v.setValue(pRow.resourceId, ConnectorsPaths.ConnectorGovernance.RESOURCE_ID);
				v.setValue(pRow.owner, ConnectorsPaths.ConnectorGovernance.OWNER);
				v.setValue(pRow.sourceSystem, ConnectorsPaths.ConnectorGovernance.SOURCE_SYSTEM);
				v.setValue(Integer.valueOf(pRow.sourcePrecedence), ConnectorsPaths.ConnectorGovernance.SOURCE_PRECEDENCE);
				v.setValue(pRow.approval, ConnectorsPaths.ConnectorGovernance.APPROVAL);
				v.setValue(pRow.effectiveFrom, ConnectorsPaths.ConnectorGovernance.EFFECTIVE_FROM);
				v.setValue(pRow.effectiveUntil, ConnectorsPaths.ConnectorGovernance.EFFECTIVE_UNTIL);
				v.setValue(Integer.valueOf(pRow.publicationVersion), ConnectorsPaths.ConnectorGovernance.PUBLICATION_VERSION);
				v.setValue(pRow.dependencies, ConnectorsPaths.ConnectorGovernance.DEPENDENCIES);
				v.setValue(pRow.secretReferences, ConnectorsPaths.ConnectorGovernance.SECRET_REFERENCES);
			}});
		}
		@Override public void append(final AuditRow pRow)
		{
			final AdaptationTable table = table(ConnectorsPaths.CONNECTOR_GOVERNANCE_AUDIT); write(table, null, new Writer() { public void set(final ValueContextForUpdate v) {
				v.setValue(pRow.resourceId, ConnectorsPaths.ConnectorGovernanceAudit.RESOURCE_ID);
				v.setValue(pRow.action, ConnectorsPaths.ConnectorGovernanceAudit.ACTION);
				v.setValue(pRow.actor, ConnectorsPaths.ConnectorGovernanceAudit.ACTOR);
				v.setValue(pRow.detail, ConnectorsPaths.ConnectorGovernanceAudit.DETAIL);
				v.setValue(pRow.occurredAt, ConnectorsPaths.ConnectorGovernanceAudit.OCCURRED_AT);
			}});
		}
		@Override public List<AuditRow> audit(final String pResourceId)
		{
			final List<AuditRow> out = new ArrayList<AuditRow>();
			final RequestResult rows = table(ConnectorsPaths.CONNECTOR_GOVERNANCE_AUDIT).createRequest().execute();
			try { for (Adaptation row; (row = rows.nextAdaptation()) != null;) if (pResourceId.equals(row.getString(ConnectorsPaths.ConnectorGovernanceAudit.RESOURCE_ID))) {
				final AuditRow value = new AuditRow();
				value.resourceId = pResourceId;
				value.action = row.getString(ConnectorsPaths.ConnectorGovernanceAudit.ACTION);
				value.actor = row.getString(ConnectorsPaths.ConnectorGovernanceAudit.ACTOR);
				value.detail = row.getString(ConnectorsPaths.ConnectorGovernanceAudit.DETAIL);
				value.occurredAt = (Date) row.get(ConnectorsPaths.ConnectorGovernanceAudit.OCCURRED_AT);
				out.add(value);
		}
	}
	finally
	{
		rows.close();
	}
	return out;
		}
		private void write(final AdaptationTable pTable, final Adaptation pExisting, final Writer pWriter)
		{
			final ProcedureResult result = ProgrammaticService.createForSession(session, pTable.getContainerAdaptation().getHome()).execute(new Procedure() { @Override public void execute(final ProcedureContext c) throws Exception {
				final ValueContextForUpdate values = pExisting == null ? c.getContextForNewOccurrence(pTable) : c.getContext(pExisting.getAdaptationName());
				pWriter.set(values);
				if (pExisting == null) c.doCreateOccurrence(values, pTable);
				else c.doModifyContent(pExisting, values);
		}
	});
	if (result.hasFailed()) throw new IllegalStateException("EBX rejected governance state", result.getException());
		}
		private static GovernanceRow governance(final Adaptation pRow)
		{
			final GovernanceRow row = new GovernanceRow();
			row.resourceId = pRow.getString(ConnectorsPaths.ConnectorGovernance.RESOURCE_ID);
			row.owner = pRow.getString(ConnectorsPaths.ConnectorGovernance.OWNER);
			row.sourceSystem = pRow.getString(ConnectorsPaths.ConnectorGovernance.SOURCE_SYSTEM);
			row.sourcePrecedence = integer(pRow, ConnectorsPaths.ConnectorGovernance.SOURCE_PRECEDENCE);
			row.approval = pRow.getString(ConnectorsPaths.ConnectorGovernance.APPROVAL);
			row.effectiveFrom = (Date) pRow.get(ConnectorsPaths.ConnectorGovernance.EFFECTIVE_FROM);
			row.effectiveUntil = (Date) pRow.get(ConnectorsPaths.ConnectorGovernance.EFFECTIVE_UNTIL);
			row.publicationVersion = integer(pRow, ConnectorsPaths.ConnectorGovernance.PUBLICATION_VERSION);
			row.dependencies = pRow.getString(ConnectorsPaths.ConnectorGovernance.DEPENDENCIES);
			row.secretReferences = pRow.getString(ConnectorsPaths.ConnectorGovernance.SECRET_REFERENCES);
			return row;
		}
		private static int integer(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
		{
			final Integer value = (Integer) pRow.get(pPath);
			return value == null ? 0 : value.intValue();
		}
		private interface Writer { void set(ValueContextForUpdate pValues); }
	}
}
