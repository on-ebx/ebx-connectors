package com.on.ebx.connect.ebx;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.OriginVisibility;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.Session;

/**
 * Audit C2-03: the fields of the run-time tables that hold VALUES of a record (changes of a log line, the value written, the
 * values of a conflict, the document of an outbound state, the record's label) are hidden from a session that may not read the
 * record they came from under the EBX permissions of its own table ({@link OriginVisibility}). Declared on the node, so the
 * screens, Data Services and the {@code data-access} MCP follow it; the engine reads rows with Java and is not affected.
 */
public final class OriginValueAccessRule implements AccessRule
{
	static final UserMessage HIDDEN_REASON = UserMessage.createInfo("This value comes from a record you may not read.");

	/** Where the origin of a row is written. */
	private final Path dataspace;
	private final Path dataset;
	private final Path tablePath;
	private final Path recordKey;

	OriginValueAccessRule(final Path pDataspace, final Path pDataset, final Path pTablePath, final Path pRecordKey)
	{
		this.dataspace = pDataspace;
		this.dataset = pDataset;
		this.tablePath = pTablePath;
		this.recordKey = pRecordKey;
	}

	/** Declares the rule on the value fields of the four tables that carry them (once, from the schema extensions). */
	static void register(final com.orchestranetworks.schema.SchemaExtensionsContext pContext)
	{
		final OriginValueAccessRule log = new OriginValueAccessRule(ConnectorsPaths.SyncLog.DATASPACE, null, ConnectorsPaths.SyncLog.TABLE_PATH, ConnectorsPaths.SyncLog.RECORD_KEY);
		OriginValueAccessRule.on(pContext, ConnectorsPaths.SYNC_LOG, ConnectorsPaths.SyncLog.CHANGES, log);
		OriginValueAccessRule.on(pContext, ConnectorsPaths.SYNC_LOG, ConnectorsPaths.SyncLog.RECORD_LABEL, log);
		final OriginValueAccessRule provenance = new OriginValueAccessRule(ConnectorsPaths.FieldProvenance.DATASPACE, ConnectorsPaths.FieldProvenance.DATASET,
			ConnectorsPaths.FieldProvenance.TABLE_PATH, ConnectorsPaths.FieldProvenance.RECORD_KEY);
		OriginValueAccessRule.on(pContext, ConnectorsPaths.FIELD_PROVENANCE, ConnectorsPaths.FieldProvenance.VALUE, provenance);
		// audit C3-02, C2-03 (1.4.2): where the value was read (document, page, quoted words, up to 2,000 characters) is a value of the origin record too
		OriginValueAccessRule.on(pContext, ConnectorsPaths.FIELD_PROVENANCE, ConnectorsPaths.FieldProvenance.SOURCE, provenance);
		final OriginValueAccessRule conflict = new OriginValueAccessRule(ConnectorsPaths.Conflict.DATASPACE, ConnectorsPaths.Conflict.DATASET,
			ConnectorsPaths.Conflict.TABLE_PATH, ConnectorsPaths.Conflict.RECORD_KEY);
		OriginValueAccessRule.on(pContext, ConnectorsPaths.CONFLICT, ConnectorsPaths.Conflict.CURRENT_VALUE, conflict);
		OriginValueAccessRule.on(pContext, ConnectorsPaths.CONFLICT, ConnectorsPaths.Conflict.PROPOSED_VALUE, conflict);
		OriginValueAccessRule.on(pContext, ConnectorsPaths.CONFLICT, ConnectorsPaths.Conflict.RECORD_LABEL, conflict);
		// audit C3-02, C2-03 (1.4.2): the comment of a proposal quotes the source (up to 500 characters); the steward's decision comment shares the column
		OriginValueAccessRule.on(pContext, ConnectorsPaths.CONFLICT, ConnectorsPaths.Conflict.COMMENT, conflict);
		final OriginValueAccessRule outbound = new OriginValueAccessRule(ConnectorsPaths.OutboundState.DATASPACE, ConnectorsPaths.OutboundState.DATASET,
			ConnectorsPaths.OutboundState.TABLE_PATH, ConnectorsPaths.OutboundState.RECORD_KEY);
		OriginValueAccessRule.on(pContext, ConnectorsPaths.OUTBOUND_STATE, ConnectorsPaths.OutboundState.LAST_DOCUMENT, outbound);
		OriginValueAccessRule.on(pContext, ConnectorsPaths.OUTBOUND_STATE, ConnectorsPaths.OutboundState.RECORD_LABEL, outbound);
	}

	private static void on(final com.orchestranetworks.schema.SchemaExtensionsContext pContext, final Path pTable, final Path pField,
		final OriginValueAccessRule pRule)
	{
		pContext.setAccessRuleOnNode(Path.parse(pTable.format() + "/" + pField.format().substring(2)), pRule);
	}

	/** Pure: the permission of a session on the values of a row whose origin is given. */
	AccessPermission permission(final Session pSession, final String pDataspace, final String pDataset, final String pTable, final String pKey)
	{
		return OriginVisibility.canRead(pSession, pDataspace, pDataset, pTable, pKey) ? AccessPermission.getReadWrite()
			: AccessPermission.getHidden(OriginValueAccessRule.HIDDEN_REASON);
	}

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		if (pRecord == null || !pRecord.isTableOccurrence())
		{
			return AccessPermission.getReadWrite(); // a creation form: nothing stored yet
		}
		return this.permission(pSession, pRecord.getString(this.dataspace), this.dataset == null ? null : pRecord.getString(this.dataset),
			pRecord.getString(this.tablePath), pRecord.getString(this.recordKey));
	}
}
