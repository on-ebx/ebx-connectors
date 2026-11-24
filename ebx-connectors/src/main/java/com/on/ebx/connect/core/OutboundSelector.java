package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConnectorException;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.comparison.DifferenceBetweenInstances;
import com.orchestranetworks.service.comparison.DifferenceBetweenOccurrences;
import com.orchestranetworks.service.comparison.DifferenceBetweenTables;
import com.orchestranetworks.service.comparison.DifferenceHelper;
import com.orchestranetworks.service.comparison.ExtraOccurrenceOnLeft;
import com.orchestranetworks.service.comparison.ExtraOccurrenceOnRight;

/** Decides, for an outbound mapping and a scope, which records to send and which deletions to propagate. */
public final class OutboundSelector
{
	/** A record that disappeared: its key, the last document sent (may be null) and, for a dataspace delta, the snapshot record. */
	public static final class Deletion
	{
		public final String recordKey;
		public final String recordLabel;
		public final JsonNode lastDocument;
		public final Adaptation snapshotRecord;

		Deletion(final String pKey, final String pLabel, final JsonNode pLastDocument, final Adaptation pSnapshotRecord)
		{
			this.recordKey = pKey;
			this.recordLabel = pLabel;
			this.lastDocument = pLastDocument;
			this.snapshotRecord = pSnapshotRecord;
		}
	}

	public static final class Selection
	{
		public final List<Adaptation> records = new ArrayList<Adaptation>();
		public final List<Deletion> deletions = new ArrayList<Deletion>();
		public int unchanged;
		public int waitingRetry;
		/** True when the selection stopped at the maximum with candidates left unexamined (audit C-35): the run must say so. */
		public boolean truncated;
	}

	private final ConnectorRepository repository;

	public OutboundSelector(final ConnectorRepository pRepository)
	{
		this.repository = pRepository;
	}

	/** The fingerprint of the document an outbound mapping would send for a record. */
	public static String fingerprint(final Session pSession, final MappingDefinition pMapping, final Adaptation pRecord)
	{
		final TransformationContext tctx = new TransformationContext(pRecord.getContainer(), pRecord, pSession);
		final JsonNode document = MappingEngine.buildOutbound(pMapping, pRecord, tctx, new MappingResult());
		return Hashing.sha256(Json.write(document));
	}

	static boolean retryAllowed(final Adaptation pState, final MappingDefinition pMapping)
	{
		final Integer attempts = (Integer) pState.get(ConnectorsPaths.OutboundState.ATTEMPTS);
		if (attempts != null && attempts.intValue() >= pMapping.getMaxAttempts())
		{
			return false;
		}
		final Date last = (Date) pState.get(ConnectorsPaths.OutboundState.LAST_ATTEMPT_AT);
		return last == null || System.currentTimeMillis() - last.getTime() >= pMapping.getRetryDelayMinutes() * 60000L;
	}

	private static JsonNode document(final Adaptation pState)
	{
		final String text = pState == null ? null : pState.getString(ConnectorsPaths.OutboundState.LAST_DOCUMENT);
		try
		{
			return text == null ? null : Json.parse(text);
		}
		catch (final java.io.IOException ex)
		{
			return null;
		}
	}

	public Selection select(final Session pSession, final MappingDefinition pMapping, final AdaptationTable pTable, final Request pRequest,
		final OutboundScope pScope, final int pMax) throws ConnectorException
	{
		final Selection selection = new Selection();
		if (pScope == OutboundScope.DATASPACE_DELTA)
		{
			this.dataspaceDelta(pMapping, pTable, selection, pMax);
			return selection;
		}
		if (pScope == OutboundScope.FAILED)
		{
			for (final Adaptation state : this.repository.outboundStates(pMapping.getId(), "FAILED"))
			{
				if (selection.records.size() + selection.deletions.size() >= pMax)
				{
					selection.truncated = true;
					break;
				}
				if (!OutboundSelector.retryAllowed(state, pMapping))
				{
					selection.waitingRetry++;
					continue;
				}
				final String key = state.getString(ConnectorsPaths.OutboundState.RECORD_KEY);
				final Adaptation record = pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(key));
				if (record != null && OutboundSelector.readable(pSession, record))
				{
					selection.records.add(record);
				}
				else if (record == null && !pMapping.getDeleteOperation().isEmpty())
				{
					selection.deletions.add(new Deletion(key, state.getString(ConnectorsPaths.OutboundState.RECORD_LABEL), OutboundSelector.document(state), null));
				}
			}
			return selection;
		}
		// ALL, UNSENT, CHANGED: walk the request
		final Request selected = pRequest == null ? pTable.createRequest() : pRequest;
		selected.setSession(pSession);
		final RequestResult rows = selected.execute();
		try
		{
			for (Adaptation record = rows.nextAdaptation(); record != null; record = rows.nextAdaptation())
			{
				if (selection.records.size() >= pMax)
				{
					// a candidate left unexamined: the scope may hold more than this run takes
					selection.truncated = true;
					break;
				}
				if (pScope == OutboundScope.ALL)
				{
					selection.records.add(record);
					continue;
				}
				final Adaptation state = this.repository.outboundState(pMapping.getId(), record.getOccurrencePrimaryKey().format());
				final String status = state == null ? null : state.getString(ConnectorsPaths.OutboundState.STATUS);
				final boolean neverSent = state == null || state.get(ConnectorsPaths.OutboundState.LAST_SENT_AT) == null;
				if (pScope == OutboundScope.UNSENT)
				{
					if (neverSent)
					{
						if (state != null && "FAILED".equals(status) && !OutboundSelector.retryAllowed(state, pMapping))
						{
							selection.waitingRetry++;
						}
						else
						{
							selection.records.add(record);
						}
					}
					else
					{
						selection.unchanged++;
					}
					continue;
				}
				// CHANGED
				if ("FAILED".equals(status))
				{
					if (OutboundSelector.retryAllowed(state, pMapping))
					{
						selection.records.add(record);
					}
					else
					{
						selection.waitingRetry++;
					}
					continue;
				}
				if (neverSent || !OutboundSelector.fingerprint(pSession, pMapping, record).equals(state.getString(ConnectorsPaths.OutboundState.HASH)))
				{
					selection.records.add(record);
				}
				else
				{
					selection.unchanged++;
				}
			}
		}
		finally
		{
			rows.close();
		}
		if (pScope == OutboundScope.CHANGED && !pMapping.getDeleteOperation().isEmpty())
		{
			// Streamed: only the key of each SENT state is read to see whether its record is still there, and the label and last
			// document only of the states whose record is gone. A list of every SENT state, last document included, grew with
			// the number of records ever sent (audit C-14).
			this.repository.forEachOutboundState(pMapping.getId(), "SENT", new ConnectorRepository.OutboundStateVisitor()
			{
				@Override
				public void visit(final Adaptation pState)
				{
					final String key = pState.getString(ConnectorsPaths.OutboundState.RECORD_KEY);
					if (pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(key)) == null)
					{
						selection.deletions.add(new Deletion(key, pState.getString(ConnectorsPaths.OutboundState.RECORD_LABEL), OutboundSelector.document(pState), null));
					}
				}
			});
		}
		return selection;
	}

	private static boolean readable(final Session pSession, final Adaptation pRecord)
	{
		return pSession != null && pRecord != null && !pSession.getPermissions().getAdaptationAccessPermission(pRecord).isHidden();
	}

	/** Created and modified records of the dataspace since its initial snapshot; deleted ones through the snapshot record. */
	private void dataspaceDelta(final MappingDefinition pMapping, final AdaptationTable pTable, final Selection pSelection, final int pMax)
		throws ConnectorException
	{
		final Adaptation current = pTable.getContainerAdaptation();
		final AdaptationHome home = current.getHome();
		final AdaptationHome initial = home.getParent();
		if (initial == null || !initial.isVersion())
		{
			throw new ConnectorException("Dataspace " + home.getKey().getName() + " has no initial snapshot (a reference dataspace cannot be compared)");
		}
		final Adaptation before = initial.findAdaptationOrNull(current.getAdaptationName());
		if (before == null)
		{
			// the dataset was created inside the dataspace: everything in it is new since the snapshot
			final RequestResult rows = pTable.createRequest().execute();
			try
			{
				for (Adaptation record = rows.nextAdaptation(); record != null; record = rows.nextAdaptation())
				{
					if (pSelection.records.size() >= pMax)
					{
						pSelection.truncated = true;
						break;
					}
					pSelection.records.add(record);
				}
			}
			finally
			{
				rows.close();
			}
			return;
		}
		final DifferenceBetweenInstances difference = DifferenceHelper.compareInstances(current, before, false);
		final DifferenceBetweenTables table = difference.getDeltaTable(Path.parse(pMapping.getTablePath()));
		if (table == null)
		{
			return;
		}
		for (final ExtraOccurrenceOnLeft created : table.getExtraOccurrencesOnLeft())
		{
			if (pSelection.records.size() >= pMax)
			{
				pSelection.truncated = true;
				return;
			}
			pSelection.records.add(created.getExtraAdaptationOnLeft());
		}
		for (final DifferenceBetweenOccurrences modified : table.getDeltaOccurrences())
		{
			if (pSelection.records.size() >= pMax)
			{
				pSelection.truncated = true;
				return;
			}
			pSelection.records.add(modified.getOccurrenceOnLeft());
		}
		if (!pMapping.getDeleteOperation().isEmpty())
		{
			for (final ExtraOccurrenceOnRight deleted : table.getExtraOccurrencesOnRight())
			{
				final Adaptation snapshot = deleted.getExtraAdaptationOnRight();
				final Adaptation state = this.repository.outboundState(pMapping.getId(), snapshot.getOccurrencePrimaryKey().format());
				pSelection.deletions.add(new Deletion(snapshot.getOccurrencePrimaryKey().format(), snapshot.getLabel(java.util.Locale.getDefault()),
					OutboundSelector.document(state), snapshot));
			}
		}
	}
}
