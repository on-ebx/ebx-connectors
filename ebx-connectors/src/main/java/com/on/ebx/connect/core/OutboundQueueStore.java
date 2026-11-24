package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The {@code OutboundQueue} table of the Connectors dataset as an {@link OutboundQueue.Store}. Written by the module's own
 * thread with a system-user session and all privileges (the table is read only for everyone else, see
 * {@code ConnectorsTableAccessRule}); each call is one transaction on the Connectors dataspace.
 */
public final class OutboundQueueStore implements OutboundQueue.Store
{
	private final ConnectorRepository repo;
	private final Session session;

	public OutboundQueueStore(final Repository pRepository, final Session pSession)
	{
		this.repo = ConnectorService.get().repository(pRepository);
		this.session = pSession;
	}

	private AdaptationTable table()
	{
		final Adaptation dataset = this.repo.dataset();
		if (dataset == null || dataset.getSchemaNode().getNode(ConnectorsPaths.OUTBOUND_QUEUE) == null)
		{
			throw new IllegalStateException("The OutboundQueue table is not in the Connectors model: restart the module so that the model is reloaded");
		}
		return dataset.getTable(ConnectorsPaths.OUTBOUND_QUEUE);
	}

	@Override
	public void add(final List<OutboundQueue.Entry> pEntries) throws Exception
	{
		final AdaptationTable table = this.table();
		this.run(table, new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final OutboundQueue.Entry entry : pEntries)
				{
					final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
					row.setValue(entry.connector, ConnectorsPaths.OutboundQueue.CONNECTOR);
					row.setValue(entry.operation, ConnectorsPaths.OutboundQueue.OPERATION);
					row.setValue(entry.direction, ConnectorsPaths.OutboundQueue.DIRECTION);
					row.setValue(entry.dataspace, ConnectorsPaths.OutboundQueue.DATASPACE);
					row.setValue(entry.dataset, ConnectorsPaths.OutboundQueue.DATASET);
					row.setValue(entry.tablePath, ConnectorsPaths.OutboundQueue.TABLE_PATH);
					row.setValue(ConnectorRepository.cut(entry.recordKey, 255), ConnectorsPaths.OutboundQueue.RECORD_KEY);
					if (entry.publish != null)
					{
						row.setValue(entry.publish, ConnectorsPaths.OutboundQueue.PUBLISH);
					}
					row.setValue(new Date(entry.enqueuedAt), ConnectorsPaths.OutboundQueue.ENQUEUED_AT);
					row.setValue(new Date(entry.notBefore), ConnectorsPaths.OutboundQueue.NOT_BEFORE);
					row.setValue(Integer.valueOf(0), ConnectorsPaths.OutboundQueue.ATTEMPTS);
					row.setValue(OutboundQueue.PENDING, ConnectorsPaths.OutboundQueue.STATUS);
					pContext.doCreateOccurrence(row, table);
				}
			}
		});
	}

	@Override
	public List<OutboundQueue.Entry> due(final long pNow, final int pMax) throws Exception
	{
		final AdaptationTable table = this.table();
		final List<OutboundQueue.Entry> out = new ArrayList<OutboundQueue.Entry>();
		final Request request = table.createRequest();
		request.setXPathFilter("./status='" + OutboundQueue.PENDING + "'");
		final RequestResult rows = request.execute();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Date notBefore = (Date) row.get(ConnectorsPaths.OutboundQueue.NOT_BEFORE);
				if (notBefore != null && notBefore.getTime() > pNow)
				{
					continue;
				}
				final OutboundQueue.Entry entry = new OutboundQueue.Entry();
				entry.id = (Integer) row.get(ConnectorsPaths.OutboundQueue.ID);
				entry.connector = row.getString(ConnectorsPaths.OutboundQueue.CONNECTOR);
				entry.operation = row.getString(ConnectorsPaths.OutboundQueue.OPERATION);
				entry.direction = row.getString(ConnectorsPaths.OutboundQueue.DIRECTION);
				entry.dataspace = row.getString(ConnectorsPaths.OutboundQueue.DATASPACE);
				entry.dataset = row.getString(ConnectorsPaths.OutboundQueue.DATASET);
				entry.tablePath = row.getString(ConnectorsPaths.OutboundQueue.TABLE_PATH);
				entry.recordKey = row.getString(ConnectorsPaths.OutboundQueue.RECORD_KEY);
				entry.publish = OutboundQueueStore.publishOf(row);
				final Integer attempts = (Integer) row.get(ConnectorsPaths.OutboundQueue.ATTEMPTS);
				entry.attempts = attempts == null ? 0 : attempts.intValue();
				entry.notBefore = notBefore == null ? 0L : notBefore.getTime();
				out.add(entry);
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(out, new Comparator<OutboundQueue.Entry>()
		{
			@Override
			public int compare(final OutboundQueue.Entry pA, final OutboundQueue.Entry pB)
			{
				return pA.id.compareTo(pB.id);
			}
		});
		return out.size() <= pMax ? out : new ArrayList<OutboundQueue.Entry>(out.subList(0, pMax));
	}

	@Override
	public void delete(final List<OutboundQueue.Entry> pEntries) throws Exception
	{
		final AdaptationTable table = this.table();
		this.run(table, new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final OutboundQueue.Entry entry : pEntries)
				{
					final Adaptation row = OutboundQueueStore.row(table, entry);
					if (row != null)
					{
						pContext.doDelete(row.getAdaptationName(), false);
					}
				}
			}
		});
	}

	@Override
	public void failed(final List<OutboundQueue.Entry> pEntries, final int pAttempts, final long pNotBefore, final String pError, final boolean pDead,
		final long pNow) throws Exception
	{
		final AdaptationTable table = this.table();
		this.run(table, new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final OutboundQueue.Entry entry : pEntries)
				{
					final Adaptation existing = OutboundQueueStore.row(table, entry);
					if (existing == null)
					{
						continue;
					}
					final ValueContextForUpdate row = pContext.getContext(existing.getAdaptationName());
					row.setValue(Integer.valueOf(pAttempts), ConnectorsPaths.OutboundQueue.ATTEMPTS);
					row.setValue(pDead ? OutboundQueue.DEAD : OutboundQueue.PENDING, ConnectorsPaths.OutboundQueue.STATUS);
					row.setValue(new Date(pNotBefore), ConnectorsPaths.OutboundQueue.NOT_BEFORE);
					row.setValue(new Date(pNow), ConnectorsPaths.OutboundQueue.LAST_ATTEMPT_AT);
					row.setValue(ConnectorRepository.cut(pError, 2000), ConnectorsPaths.OutboundQueue.LAST_ERROR);
					pContext.doModifyContent(existing, row);
				}
			}
		});
	}

	private static Adaptation row(final AdaptationTable pTable, final OutboundQueue.Entry pEntry)
	{
		return pTable.lookupAdaptationByPrimaryKey(pTable.computePrimaryKey(new Object[] { pEntry.id }));
	}

	private void run(final AdaptationTable pTable, final Procedure pProcedure) throws Exception
	{
		final ProcedureResult result = ProgrammaticService.createForSession(this.session, pTable.getContainerAdaptation().getHome()).execute(pProcedure);
		if (result.hasFailed())
		{
			throw result.getException() == null ? new IllegalStateException("The OutboundQueue write failed") : result.getException();
		}
	}

	/** The optional {@code publish} field; a model loaded before real-time mastering has no such node: null. */
	private static String publishOf(final Adaptation pRow)
	{
		if (pRow.getSchemaNode().getNode(ConnectorsPaths.OutboundQueue.PUBLISH) == null)
		{
			return null;
		}
		final String value = pRow.getString(ConnectorsPaths.OutboundQueue.PUBLISH);
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}
}
