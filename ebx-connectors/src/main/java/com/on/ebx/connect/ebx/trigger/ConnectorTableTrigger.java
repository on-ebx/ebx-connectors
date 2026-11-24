package com.on.ebx.connect.ebx.trigger;

import java.util.Iterator;

import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.OutboundQueue;
import com.on.ebx.connect.core.SyncResult;
import com.on.ebx.connect.ebx.SyncTargets;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.trigger.AfterCreateOccurrenceContext;
import com.orchestranetworks.schema.trigger.AfterModifyOccurrenceContext;
import com.orchestranetworks.schema.trigger.TableTrigger;
import com.orchestranetworks.schema.trigger.TriggerSetupContext;
import com.orchestranetworks.schema.trigger.ValueChange;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.ProcedureContext;

/**
 * Table trigger: synchronises a record after it is created and/or modified.
 * <pre>
 * &lt;osd:trigger class="com.on.ebx.connect.ebx.trigger.ConnectorTableTrigger"&gt;
 *   &lt;connector&gt;GLEIF&lt;/connector&gt;
 *   &lt;operation&gt;lei&lt;/operation&gt;
 *   &lt;onCreate&gt;true&lt;/onCreate&gt;
 *   &lt;onModify&gt;true&lt;/onModify&gt;
 *   &lt;watchedFields&gt;./Identification/LegalEntityIdentifier&lt;/watchedFields&gt;
 * &lt;/osd:trigger&gt;
 * </pre>
 * By default (audit C-07, lot L8) the trigger makes no call to the external system: it hands the record's location to
 * {@link OutboundQueue}, whose background sender writes it to the {@code OutboundQueue} table, waits for the user's
 * transaction to be committed, re-reads the record, sends it with the mapping (sync log, provenance, conflicts and outbound
 * state included), retries a failure under the mapping's {@code maxAttempts} and {@code retryDelayMinutes}, and sets the row
 * aside when they are used up. Delivery is AT LEAST ONCE: the target must tolerate a repeated message. {@code blocking=true}
 * keeps the former behaviour, the exception: the call is made in the user's transaction, before the commit, with the dataspace
 * locked for its duration, and a failure refuses the user's save.
 */
public final class ConnectorTableTrigger extends TableTrigger
{
	private static final ThreadLocal<Boolean> REENTRANT = new ThreadLocal<Boolean>();

	private String connector;
	private String operation;
	private String direction = "INBOUND";
	private String onCreate = "true";
	private String onModify = "false";
	private String watchedFields;
	private String blocking = "false";
	/** Real-time mastering: {@code record} (default) sends the record; {@code golden} sends a MERGED record's golden instead, at send time. */
	private String publish = "record";
	/** Real-time mastering: connector ids (comma-separated, {@code *} for any) whose CDC writes are not sent back. */
	private String skipOrigin;

	@Override
	public void setup(final TriggerSetupContext pContext)
	{
		if (SyncTargets.isBlank(this.connector) || SyncTargets.isBlank(this.operation))
		{
			pContext.addError("ConnectorTableTrigger: connector and operation parameters are required");
		}
		final String mode = this.publish == null ? "record" : this.publish.trim().toLowerCase(java.util.Locale.ENGLISH);
		if (!mode.isEmpty() && !"record".equals(mode) && !"golden".equals(mode))
		{
			pContext.addError("ConnectorTableTrigger: publish must be record or golden, not '" + this.publish.trim() + "'");
		}
		else if ("golden".equals(mode) && "true".equalsIgnoreCase(this.blocking))
		{
			pContext.addError("ConnectorTableTrigger: publish=golden needs the queued send (blocking=false): the golden is known only after the commit");
		}
	}

	@Override
	public void handleAfterCreate(final AfterCreateOccurrenceContext pContext) throws OperationException
	{
		if ("true".equalsIgnoreCase(this.onCreate))
		{
			this.sync(pContext.getProcedureContext(), pContext.getAdaptationOccurrence());
		}
	}

	@Override
	public void handleAfterModify(final AfterModifyOccurrenceContext pContext) throws OperationException
	{
		if (!"true".equalsIgnoreCase(this.onModify))
		{
			return;
		}
		if (!SyncTargets.isBlank(this.watchedFields))
		{
			boolean touched = false;
			final Iterator<ValueChange> changes = pContext.getChanges().getChangesIterator();
			while (changes.hasNext() && !touched)
			{
				final String changed = changes.next().getModifiedNode().getPathInAdaptation().format();
				for (final String watched : this.watchedFields.split(","))
				{
					final String w = watched.trim();
					if (!w.isEmpty() && (changed.equals(w) || changed.endsWith(w.startsWith("./") ? w.substring(1) : w)))
					{
						touched = true;
						break;
					}
				}
			}
			if (!touched)
			{
				return;
			}
		}
		this.sync(pContext.getProcedureContext(), pContext.getAdaptationOccurrence());
	}

	private void sync(final ProcedureContext pProcedureContext, final Adaptation pRecord) throws OperationException
	{
		if (OutboundQueue.isSending())
		{
			return; // a send of the queue writes the record in its own procedure: that write must not queue the record again
		}
		if (com.on.ebx.connect.core.CdcOrigin.skips(this.skipOrigin, com.on.ebx.connect.core.CdcOrigin.current()))
		{
			return; // a change that came in by CDC from this system is not sent back to it (docs/REALTIME-MASTERING.md)
		}
		if ("true".equalsIgnoreCase(this.blocking))
		{
			this.syncInTransaction(pProcedureContext, pRecord);
			return;
		}
		ConnectorTableTrigger.queueSend(this.connector, this.operation, SyncTargets.direction(this.direction).name(), pRecord.getHome().getKey().getName(),
			pRecord.getContainer().getAdaptationName().getStringName(), pRecord.getContainerTable().getTablePath().format(), pRecord.getOccurrencePrimaryKey().format(),
			"golden".equalsIgnoreCase(this.publish == null ? "" : this.publish.trim()) ? OutboundQueue.PUBLISH_GOLDEN : null);
	}

	/** Hands one record's send to the background sender: no EBX call, no wait; says in the log when it could not be queued. */
	public static boolean queueSend(final String pConnector, final String pOperation, final String pDirection, final String pDataspace, final String pDataset,
		final String pTablePath, final String pRecordKey)
	{
		return ConnectorTableTrigger.queueSend(pConnector, pOperation, pDirection, pDataspace, pDataset, pTablePath, pRecordKey, null);
	}

	/** {@code pPublish} = {@link OutboundQueue#PUBLISH_GOLDEN}: the sender sends the golden of a MERGED record (null: the record). */
	public static boolean queueSend(final String pConnector, final String pOperation, final String pDirection, final String pDataspace, final String pDataset,
		final String pTablePath, final String pRecordKey, final String pPublish)
	{
		final OutboundQueue.Entry entry = new OutboundQueue.Entry();
		entry.publish = pPublish;
		entry.connector = pConnector;
		entry.operation = pOperation;
		entry.direction = pDirection;
		entry.dataspace = pDataspace;
		entry.dataset = pDataset;
		entry.tablePath = pTablePath;
		entry.recordKey = pRecordKey;
		if (OutboundQueue.submit(entry))
		{
			return true;
		}
		ConnectorService.log().warn("[connectors] trigger " + pConnector + "/" + pOperation + " on " + pTablePath + "[" + pRecordKey
			+ "] NOT queued: the outbound queue is not running (module stopping) or full; synchronise the record again once the module is up");
		return false;
	}

	/** The former behaviour, kept for {@code blocking=true}: the remote call is made here, inside the user's transaction. */
	private void syncInTransaction(final ProcedureContext pProcedureContext, final Adaptation pRecord) throws OperationException
	{
		if (Boolean.TRUE.equals(ConnectorTableTrigger.REENTRANT.get()))
		{
			return; // our own doModifyContent fires handleAfterModify again
		}
		ConnectorTableTrigger.REENTRANT.set(Boolean.TRUE);
		try
		{
			com.on.ebx.connect.ebx.ConnectorsInstaller.ensure(pRecord.getHome().getRepository(), pProcedureContext.getSession());
			final MappingDefinition mapping;
			try
			{
				mapping = ConnectorService.get().repository(pRecord.getHome().getRepository())
						.mappingFor(pRecord.getHome().getKey().getName(),
							pRecord.getContainer().getAdaptationName().getStringName(),
							pRecord.getContainerTable().getTablePath().format(), this.connector, this.operation,
							SyncTargets.direction(this.direction));
			}
			catch (final IllegalStateException ambiguous)
			{
				ConnectorService.log().warn("[connectors] trigger " + this.connector + "/" + this.operation + ": " + ambiguous.getMessage());
				return;
			}
			if (mapping == null)
			{
				return;
			}
			final SyncResult result = ConnectorService.get().syncRecordInProcedure(pProcedureContext, pRecord, mapping);
			if (result.hasFailure())
			{
				ConnectorService.log().warn("[connectors] trigger " + this.connector + "/" + this.operation + " on "
						+ result.getRecordLabel() + ": " + result.getMessage());
				if ("true".equalsIgnoreCase(this.blocking))
				{
					throw OperationException.createError(result.getMessage());
				}
			}
		}
		finally
		{
			ConnectorTableTrigger.REENTRANT.remove();
		}
	}

	public String getConnector()
	{
		return this.connector;
	}

	public void setConnector(final String pConnector)
	{
		this.connector = pConnector;
	}

	public String getOperation()
	{
		return this.operation;
	}

	public void setOperation(final String pOperation)
	{
		this.operation = pOperation;
	}

	public String getDirection()
	{
		return this.direction;
	}

	public void setDirection(final String pDirection)
	{
		this.direction = pDirection;
	}

	public String getOnCreate()
	{
		return this.onCreate;
	}

	public void setOnCreate(final String pOnCreate)
	{
		this.onCreate = pOnCreate;
	}

	public String getOnModify()
	{
		return this.onModify;
	}

	public void setOnModify(final String pOnModify)
	{
		this.onModify = pOnModify;
	}

	public String getWatchedFields()
	{
		return this.watchedFields;
	}

	public void setWatchedFields(final String pWatchedFields)
	{
		this.watchedFields = pWatchedFields;
	}

	public String getBlocking()
	{
		return this.blocking;
	}

	public void setBlocking(final String pBlocking)
	{
		this.blocking = pBlocking;
	}

	public String getPublish()
	{
		return this.publish;
	}

	public void setPublish(final String pPublish)
	{
		this.publish = pPublish;
	}

	public String getSkipOrigin()
	{
		return this.skipOrigin;
	}

	public void setSkipOrigin(final String pSkipOrigin)
	{
		this.skipOrigin = pSkipOrigin;
	}
}
