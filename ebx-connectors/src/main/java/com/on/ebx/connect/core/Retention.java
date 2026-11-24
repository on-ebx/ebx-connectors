package com.on.ebx.connect.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;

/**
 * Deletes, by age, the rows the module writes about its own activity and never reads again.
 * <p>
 * <b>On by default, at 90 days</b> (owner decision of 2026-10-06): {@code ebx.connectors.log.retentionDays} unset
 * means 90, {@code 0} turns the whole purge off, and each table has its own age
 * ({@code ebx.connectors.retention.<table>Days}, {@code 0} keeps that table for ever). What is still needed is
 * never deleted whatever its age: an open conflict, a running run, the outbound state of a record that exists.
 * <p>
 * The purge is one background task of the module (started at repository startup,
 * stopped with it), never work done on the thread of whoever happens to write a log line. A pass
 * goes through the tables below in turn and deletes what is older than each table's limit, a
 * bounded batch per transaction, pausing between batches, up to a bounded number of rows per table
 * and per pass - a backlog of millions of rows is absorbed over several passes instead of in one
 * transaction.
 * <p>
 * Tables, and why each is safe to purge by age:
 * <ul>
 * <li>{@code SyncLog}: an append-only trace. A run whose lines are gone can no longer be replayed
 * nor listed by {@code connectors_run_get}.</li>
 * <li>{@code Conflict}, decided ones only: an {@code OPEN} conflict is work waiting for a steward
 * and is never deleted.</li>
 * <li>{@code ExecutionRun}, finished ones only ({@code DONE}, {@code PARTIAL}, {@code ERROR},
 * {@code STOPPED}, {@code INTERRUPTED}): a {@code RUNNING} row and a row without a status are never
 * deleted. A purged run can no longer be resumed from its saved position.</li>
 * <li>{@code Publication}: the report of a past publication, read back only by run id.</li>
 * <li>{@code OutboundState}, the {@code DELETED} ones only: the trace of a remote deletion that was done. A
 * {@code SENT} state is what the {@code changed} and {@code unsent} scopes compare with and a {@code FAILED}
 * one is waiting for its retry: neither is ever deleted.</li>
 * <li>{@code OutboundQueue}, the {@code DEAD} ones only (set aside after the mapping's maximum attempts; sent rows are deleted by the sender
 * itself, so none remain). A {@code PENDING} row is waiting to be sent and is never deleted.</li>
 * <li>{@code FieldProvenance}, the rows of records that no longer exist only. A provenance row describes the
 * <i>current</i> state of a field - who last wrote it - and stays true however old it is, so age alone never
 * deletes one: deleting it would change the next TRUSTED decision on that field. What is safe to delete is
 * what belongs to a record that is gone, and it is deleted only when the row is older than its limit, the
 * record's dataspace no longer exists or the record is no longer in an open dataspace, and the table could
 * be read to say so. A row whose record cannot be looked up (dataspace closed, dataset or table not
 * served, key unreadable) is kept. See {@link Orphans}.</li>
 * </ul>
 * A pass reads each table with the age predicate, outside any procedure, and deletes what it found in
 * transactions of {@code batchSize} rows: EBX indexes only the primary key, so the predicate is a scan of
 * the table, once per pass and table, on the background thread. For the provenance table a pass looks at
 * {@code maxRowsPerPass} old rows at most and resumes where it stopped at the next pass.
 */
public final class Retention
{
	/** The switch, and the default age for every table: unset is {@link #DEFAULT_DAYS}, 0 deletes nothing at all. */
	public static final String DAYS_PROPERTY = "ebx.connectors.log.retentionDays";
	/** The age when {@link #DAYS_PROPERTY} is not set. */
	public static final int DEFAULT_DAYS = 90;
	/** Per-table ages ({@code <prefix>syncLogDays}...): default the general one; 0 keeps that table for ever. */
	static final String PREFIX = "ebx.connectors.retention.";

	static final int DEFAULT_INTERVAL_MINUTES = 60;
	static final int DEFAULT_BATCH_SIZE = 500;
	static final int MAX_BATCH_SIZE = 5000;
	static final int DEFAULT_MAX_ROWS_PER_PASS = 50000;
	/** The first pass waits for the repository to be done starting. */
	static final int FIRST_PASS_DELAY_MINUTES = 5;
	static final long PAUSE_BETWEEN_BATCHES_MS = 100L;

	/** One table to purge: which rows are candidates, and from which age. */
	static final class Target
	{
		final String name;
		final Path table;
		final Path dateField;
		/** Field that must exist for the table to be purged at all (an older model without it is left alone); null when none. */
		final Path requiredField;
		/** XPath restriction on top of the age; null when every old row is a candidate. */
		final String restriction;
		/** Field re-checked in Java on each row before it is deleted; null when none. */
		final Path statusField;
		/** The statuses a row must have ({@code statusMustBeIn}) or must not have to be deleted; a row without a status is never deleted. */
		final List<String> statuses;
		final boolean statusMustBeIn;
		final int days;

		Target(final String pName, final Path pTable, final Path pDateField, final Path pRequiredField, final String pRestriction, final Path pStatusField,
			final List<String> pStatuses, final boolean pStatusMustBeIn, final int pDays)
		{
			this.name = pName;
			this.table = pTable;
			this.dateField = pDateField;
			this.requiredField = pRequiredField;
			this.restriction = pRestriction;
			this.statusField = pStatusField;
			this.statuses = pStatuses;
			this.statusMustBeIn = pStatusMustBeIn;
			this.days = pDays;
		}

		Date limit(final Date pNow)
		{
			return new Date(pNow.getTime() - this.days * 86400000L);
		}

		/** The rows of this table a pass may delete, as an XPath predicate. */
		String predicate(final Date pNow)
		{
			final String age = "date-less-than(" + this.dateField.format() + ",'" + Retention.dateTime(this.limit(pNow)) + "')";
			return this.restriction == null ? age : age + " and (" + this.restriction + ")";
		}

		/**
		 * The same rule, decided on the values of one row. The predicate selects; this decides: a row is
		 * deleted only if both agree, so that no reading of the predicate by EBX can ever delete a row
		 * that is too young, still open or still running.
		 */
		boolean mayDelete(final Date pRowDate, final String pRowStatus, final Date pNow)
		{
			if (pRowDate == null || !pRowDate.before(this.limit(pNow)))
			{
				return false;
			}
			if (this.statusField == null)
			{
				return true;
			}
			return pRowStatus != null && this.statusMustBeIn == this.statuses.contains(pRowStatus);
		}
	}

	/** What a pass does, read from the JVM properties; no EBX type, so that it can be decided and tested on its own. */
	static final class Policy
	{
		final int days;
		final int intervalMinutes;
		final int batchSize;
		final int maxRowsPerPass;
		/** Age, in days, of the provenance rows whose record is gone that a pass deletes; 0 keeps them all. */
		int provenanceDays;
		final List<Target> targets = new ArrayList<Target>();
		/** Per-table ages that were set to something unreadable; their tables are kept, and the startup log says so. */
		final List<String> unreadable = new ArrayList<String>();

		Policy(final Properties pProperties)
		{
			// unset: the default age. 0 (or negative): off. Unreadable: off, said at start - a typing mistake must not
			// choose an age the operator did not write.
			this.days = Policy.intOf(pProperties, Retention.DAYS_PROPERTY, Retention.DEFAULT_DAYS, 0);
			if (this.days == 0 && pProperties.getProperty(Retention.DAYS_PROPERTY) != null && !pProperties.getProperty(Retention.DAYS_PROPERTY).trim().isEmpty()
				&& !pProperties.getProperty(Retention.DAYS_PROPERTY).trim().matches("-?\\d+"))
			{
				this.unreadable.add(Retention.DAYS_PROPERTY + "=" + pProperties.getProperty(Retention.DAYS_PROPERTY).trim());
			}
			this.intervalMinutes = Math.max(1, Policy.intOf(pProperties, Retention.PREFIX + "intervalMinutes", Retention.DEFAULT_INTERVAL_MINUTES));
			this.batchSize = Math.min(Retention.MAX_BATCH_SIZE, Math.max(1, Policy.intOf(pProperties, Retention.PREFIX + "batchSize", Retention.DEFAULT_BATCH_SIZE)));
			this.maxRowsPerPass = Math.max(this.batchSize, Policy.intOf(pProperties, Retention.PREFIX + "maxRowsPerPass", Retention.DEFAULT_MAX_ROWS_PER_PASS));
			if (this.days <= 0)
			{
				// the switch is off: a per-table age alone does not turn the purge on
				return;
			}
			this.add(pProperties, "syncLogDays", new Target("SyncLog", ConnectorsPaths.SYNC_LOG, ConnectorsPaths.SyncLog.TIMESTAMP, null, null, null, null, false, 0));
			this.add(pProperties, "conflictDays", new Target("Conflict", ConnectorsPaths.CONFLICT, ConnectorsPaths.Conflict.DETECTED_AT, null,
				"./status!='OPEN' and ./status!='APPLYING'", ConnectorsPaths.Conflict.STATUS, java.util.Arrays.asList("OPEN", "APPLYING"), false, 0));
			this.add(pProperties, "executionRunDays", new Target("ExecutionRun", ConnectorsPaths.EXECUTION_RUN, ConnectorsPaths.ExecutionRun.UPDATED_AT,
				ConnectorsPaths.ExecutionRun.STATUS, Policy.anyOf("./status", Retention.FINISHED_RUN_STATUSES), ConnectorsPaths.ExecutionRun.STATUS,
				Retention.FINISHED_RUN_STATUSES, true, 0));
			this.add(pProperties, "publicationDays", new Target("Publication", ConnectorsPaths.PUBLICATION, ConnectorsPaths.Publication.ENDED_AT, null, null, null, null, false, 0));
			this.add(pProperties, "outboundStateDays", new Target("OutboundState", ConnectorsPaths.OUTBOUND_STATE, ConnectorsPaths.OutboundState.LAST_ATTEMPT_AT, null,
				"./status='DELETED'", ConnectorsPaths.OutboundState.STATUS, java.util.Arrays.asList("DELETED"), true, 0), false);
			this.add(pProperties, "outboundQueueDays", new Target("OutboundQueue", ConnectorsPaths.OUTBOUND_QUEUE, ConnectorsPaths.OutboundQueue.LAST_ATTEMPT_AT,
				ConnectorsPaths.OutboundQueue.STATUS, "./status='DEAD'", ConnectorsPaths.OutboundQueue.STATUS, java.util.Arrays.asList("DEAD"), true, 0));
			this.provenanceDays = this.tableDays(pProperties, "provenanceDays", false);
		}

		/**
		 * The age of one table: its own property, else the general one. An age that cannot be read keeps the table: a typing
		 * mistake must not delete at the general age what its author meant to keep longer.
		 */
		private int tableDays(final Properties pProperties, final String pKey, final boolean pFollowsGeneral)
		{
			final int tableDays = Policy.intOf(pProperties, Retention.PREFIX + pKey, pFollowsGeneral ? this.days : 0, 0);
			if (tableDays == 0 && pProperties.getProperty(Retention.PREFIX + pKey) != null && !"0".equals(pProperties.getProperty(Retention.PREFIX + pKey).trim()))
			{
				this.unreadable.add(Retention.PREFIX + pKey + "=" + pProperties.getProperty(Retention.PREFIX + pKey).trim());
			}
			return tableDays;
		}

		private void add(final Properties pProperties, final String pKey, final Target pTemplate)
		{
			this.add(pProperties, pKey, pTemplate, true);
		}

		/** {@code pFollowsGeneral} false: the table is purged only when its own property gives an age (the general age does not reach it). */
		private void add(final Properties pProperties, final String pKey, final Target pTemplate, final boolean pFollowsGeneral)
		{
			final int tableDays = this.tableDays(pProperties, pKey, pFollowsGeneral);
			if (tableDays > 0)
			{
				this.targets.add(new Target(pTemplate.name, pTemplate.table, pTemplate.dateField, pTemplate.requiredField, pTemplate.restriction, pTemplate.statusField,
					pTemplate.statuses, pTemplate.statusMustBeIn, tableDays));
			}
		}

		boolean isEnabled()
		{
			return !this.targets.isEmpty() || this.provenanceDays > 0;
		}

		/**
		 * The size of the next batch of a table within one pass, 0 when the pass is done with that table:
		 * either the previous batch came back short (nothing old is left) or the pass has deleted as much
		 * as it may.
		 *
		 * @param pDeletedSoFar rows of that table already deleted by this pass
		 * @param pLastBatch size of the batch just deleted, -1 before the first one
		 */
		int nextBatch(final int pDeletedSoFar, final int pLastBatch)
		{
			if (pLastBatch >= 0 && pLastBatch < this.batchSize)
			{
				return 0;
			}
			return Math.max(0, Math.min(this.batchSize, this.maxRowsPerPass - pDeletedSoFar));
		}

		String describe()
		{
			final StringBuilder sb = new StringBuilder();
			for (final Target target : this.targets)
			{
				sb.append(sb.length() > 0 ? ", " : "").append(target.name).append(" after ").append(target.days).append(" day(s)");
			}
			if (this.provenanceDays > 0)
			{
				sb.append(sb.length() > 0 ? ", " : "").append("FieldProvenance of records that no longer exist after ").append(this.provenanceDays).append(" day(s)");
			}
			return sb + "; every " + this.intervalMinutes + " min, " + this.batchSize + " rows per transaction, at most " + this.maxRowsPerPass + " rows per table and per pass"
				+ (this.unreadable.isEmpty() ? "" : "; NOT purged because their age cannot be read: " + this.unreadable);
		}

		private static String anyOf(final String pField, final List<String> pValues)
		{
			final StringBuilder sb = new StringBuilder();
			for (final String value : pValues)
			{
				sb.append(sb.length() > 0 ? " or " : "").append(pField).append("='").append(value).append('\'');
			}
			return sb.toString();
		}

		private static int intOf(final Properties pProperties, final String pName, final int pDefault)
		{
			return Policy.intOf(pProperties, pName, pDefault, pDefault);
		}

		/** {@code pDefault} when the property is not set, {@code pUnreadable} when it is set to something that is not a number. */
		private static int intOf(final Properties pProperties, final String pName, final int pDefault, final int pUnreadable)
		{
			final String raw = pProperties.getProperty(pName);
			if (raw == null || raw.trim().isEmpty())
			{
				return pDefault;
			}
			try
			{
				return Math.max(0, Integer.parseInt(raw.trim()));
			}
			catch (final NumberFormatException ex)
			{
				return pUnreadable;
			}
		}
	}

	static final List<String> FINISHED_RUN_STATUSES = java.util.Collections.unmodifiableList(java.util.Arrays.asList("DONE", "PARTIAL", "ERROR", "STOPPED", "INTERRUPTED"));

	private static final BackgroundWork.Managed<ScheduledExecutorService> EXECUTOR = BackgroundWork.managed("retention",
		new BackgroundWork.Factory<ScheduledExecutorService>()
		{
			@Override
			public ScheduledExecutorService create()
			{
				return new ScheduledThreadPoolExecutor(1, BackgroundWork.daemonThreads("ebx-connectors-retention", false));
			}
		});

	/** Set once EBX has refused the age predicate: the passes then read the table sorted by date instead. */
	private static final AtomicBoolean PREDICATE_REFUSED = new AtomicBoolean();

	private Retention()
	{
	}

	/** The dateTime literal of an XPath predicate, in the JVM's time zone like the values EBX stores. */
	static String dateTime(final Date pDate)
	{
		return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").format(pDate);
	}

	/**
	 * Repository startup: starts the purge task when retention is asked for, and says in the log what
	 * it will do; starts nothing otherwise.
	 */
	public static void start(final Repository pRepository, final Session pSession)
	{
		final Policy policy = new Policy(System.getProperties());
		if (!policy.isEnabled())
		{
			ConnectorService.log().info("[connectors] retention is off (" + Retention.DAYS_PROPERTY + "=0 or every table kept): the synchronisation log, decided conflicts,"
				+ " finished runs and publication reports are kept for ever"
				+ (policy.unreadable.isEmpty() ? "" : "; ages that cannot be read: " + policy.unreadable));
			return;
		}
		ConnectorService.log().info("[connectors] retention is ON (default " + Retention.DEFAULT_DAYS + " days; " + Retention.DAYS_PROPERTY + "=0 turns it off): " + policy.describe());
		try
		{
			Retention.EXECUTOR.get().scheduleWithFixedDelay(new Runnable()
			{
				@Override
				public void run()
				{
					Retention.pass(pRepository, pSession);
				}
			}, Math.min(Retention.FIRST_PASS_DELAY_MINUTES, policy.intervalMinutes), policy.intervalMinutes, TimeUnit.MINUTES);
		}
		catch (final java.util.concurrent.RejectedExecutionException stopping)
		{
			// the module is already stopping: nothing to start
		}
	}

	/** True when the purge task runs: it does unless {@link #DAYS_PROPERTY} is 0 (or every table is kept). */
	public static boolean enabled()
	{
		return new Policy(System.getProperties()).isEnabled();
	}

	/** True when the age is the default one, because {@link #DAYS_PROPERTY} is not set. */
	public static boolean onByDefault()
	{
		final String raw = System.getProperty(Retention.DAYS_PROPERTY);
		return (raw == null || raw.trim().isEmpty()) && Retention.enabled();
	}

	/** The general age in days, 0 when the purge is off. */
	public static int days()
	{
		final Policy policy = new Policy(System.getProperties());
		return policy.isEnabled() ? policy.days : 0;
	}

	/** One pass over every table; never throws, since an exception would end the periodic task for good. */
	static void pass(final Repository pRepository, final Session pSession)
	{
		try
		{
			final Policy policy = new Policy(System.getProperties());
			final Adaptation dataset = new ConnectorRepository(pRepository).dataset();
			if (dataset == null)
			{
				return;
			}
			final Date now = new Date();
			final StringBuilder done = new StringBuilder();
			for (final Target target : policy.targets)
			{
				if (BackgroundWork.isStopped())
				{
					return;
				}
				final int deleted = Retention.purge(pSession, dataset, policy, target, now);
				if (deleted > 0)
				{
					done.append(done.length() > 0 ? ", " : "").append(deleted).append(' ').append(target.name);
				}
			}
			if (policy.provenanceDays > 0 && !BackgroundWork.isStopped())
			{
				final int orphans = Retention.purgeProvenance(pRepository, pSession, dataset, policy, now);
				if (orphans > 0)
				{
					done.append(done.length() > 0 ? ", " : "").append(orphans).append(" FieldProvenance (record gone)");
				}
			}
			if (done.length() > 0)
			{
				ConnectorService.log().info("[connectors] retention: deleted " + done);
			}
		}
		catch (final Throwable ex)
		{
			Bookkeeping.lost(Bookkeeping.RETENTION, null, ex);
			if (ex instanceof VirtualMachineError)
			{
				throw (VirtualMachineError) ex;
			}
		}
	}

	/** Deletes what one table holds beyond its age, batch after batch; returns how many rows went. */
	private static int purge(final Session pSession, final Adaptation pDataset, final Policy pPolicy, final Target pTarget, final Date pNow)
	{
		if (pDataset.getSchemaNode().getNode(pTarget.table) == null
			|| (pTarget.requiredField != null && pDataset.getSchemaNode().getNode(pTarget.table.add(pTarget.requiredField)) == null))
		{
			return 0;
		}
		final AdaptationTable table = pDataset.getTable(pTarget.table);
		int deleted = 0;
		int last = -1;
		for (int size = pPolicy.nextBatch(deleted, last); size > 0 && !BackgroundWork.isStopped(); size = pPolicy.nextBatch(deleted, last))
		{
			// Read first, outside any procedure, then delete in a procedure that reads nothing: a request
			// on a table inside the transaction that modifies it forces an index refresh at every batch.
			final List<Adaptation> doomed = Retention.select(table, pTarget, pNow, size);
			if (doomed.isEmpty())
			{
				break;
			}
			final List<AdaptationName> names = new ArrayList<AdaptationName>(doomed.size());
			for (final Adaptation row : doomed)
			{
				names.add(row.getAdaptationName());
			}
			final ProcedureResult result = Retention.delete(pSession, pDataset, pTarget.name, names);
			if (!Bookkeeping.committed(Bookkeeping.RETENTION, pTarget.name, result))
			{
				// the same rows would be selected and refused again: leave this table until the next pass
				break;
			}
			deleted += doomed.size();
			last = doomed.size();
			try
			{
				Thread.sleep(Retention.PAUSE_BETWEEN_BATCHES_MS);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				break;
			}
		}
		return deleted;
	}

	/** One transaction that deletes the rows of the names given and reads nothing. */
	private static ProcedureResult delete(final Session pSession, final Adaptation pDataset, final String pLabel, final List<AdaptationName> pNames)
	{
		final ProgrammaticService service = ProgrammaticService.createForSession(pSession, pDataset.getHome());
		service.setSessionTrackingInfo("connectors:retention:" + pLabel);
		return service.execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final AdaptationName name : pNames)
				{
					pContext.doDelete(name, false);
				}
			}
		});
	}

	/** What is known of the record a provenance row is about. */
	enum Existence
	{
		/** The record is in its table. */
		PRESENT,
		/** The record's table was read and the record is not in it. */
		ABSENT,
		/** The record's dataspace no longer exists. */
		HOME_GONE,
		/** Could not be told: dataspace closed, dataset or table not served, key unreadable. */
		UNKNOWN
	}

	/**
	 * The rule for a provenance row, on its own: old enough, and its record certainly gone. An unknown is never a reason to
	 * delete, and nothing about a row whose record exists, however old it is.
	 */
	static boolean mayDeleteProvenance(final Date pWrittenAt, final Date pLimit, final Existence pExistence)
	{
		return pWrittenAt != null && pWrittenAt.before(pLimit) && (pExistence == Existence.ABSENT || pExistence == Existence.HOME_GONE);
	}

	/**
	 * Where the next pass resumes in the provenance table: the old rows it did not delete are the ones it passed over, and
	 * the table is read in the same order each time, so the next pass skips that many. At the end of the table it starts again.
	 */
	static int nextSkip(final int pSkipped, final int pExamined, final int pDeleted, final boolean pReachedEnd)
	{
		return pReachedEnd ? 0 : Math.max(0, pSkipped + pExamined - pDeleted);
	}

	/** Rows of provenance that a pass passed over, kept in memory: a restart begins again at the start of the table, which only costs time. */
	private static final java.util.concurrent.atomic.AtomicInteger PROVENANCE_SKIP = new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * Deletes the provenance rows of records that no longer exist (see the class comment). The pass reads, outside any
	 * procedure, at most {@code maxRowsPerPass} old rows after the ones an earlier pass left, looks each record up by primary
	 * key (the tables are resolved once per pass), then deletes the orphans in transactions of {@code batchSize}.
	 */
	private static int purgeProvenance(final Repository pRepository, final Session pSession, final Adaptation pDataset, final Policy pPolicy, final Date pNow)
	{
		if (pDataset.getSchemaNode().getNode(ConnectorsPaths.FIELD_PROVENANCE) == null)
		{
			return 0;
		}
		final AdaptationTable table = pDataset.getTable(ConnectorsPaths.FIELD_PROVENANCE);
		final Date limit = new Date(pNow.getTime() - pPolicy.provenanceDays * 86400000L);
		final int skipped = Retention.PROVENANCE_SKIP.get();
		final List<AdaptationName> orphans = new ArrayList<AdaptationName>();
		final java.util.Map<String, Object> tables = new java.util.HashMap<String, Object>();
		int examined = 0;
		boolean reachedEnd = false;
		final String predicate = "date-less-than(" + ConnectorsPaths.FieldProvenance.WRITTEN_AT.format() + ",'" + Retention.dateTime(limit) + "')";
		for (int attempt = Retention.PREDICATE_REFUSED.get() ? 1 : 0; attempt < 2; attempt++)
		{
			orphans.clear();
			examined = 0;
			reachedEnd = false;
			try
			{
				final Request request = table.createRequest();
				if (attempt == 0)
				{
					request.setXPathFilter(predicate);
				}
				final RequestResult rows = request.execute();
				try
				{
					int toSkip = skipped;
					Adaptation row = rows.nextAdaptation();
					for (; row != null && toSkip > 0; row = rows.nextAdaptation())
					{
						if (attempt == 1 && !Retention.isOld(row, limit))
						{
							continue;
						}
						toSkip--;
					}
					for (; row != null && examined < pPolicy.maxRowsPerPass && !BackgroundWork.isStopped(); row = rows.nextAdaptation())
					{
						final Date writtenAt = row.getDate(ConnectorsPaths.FieldProvenance.WRITTEN_AT);
						if (writtenAt == null || !writtenAt.before(limit))
						{
							continue;
						}
						examined++;
						final Existence existence = Retention.existence(pRepository, tables, row.getString(ConnectorsPaths.FieldProvenance.DATASPACE),
							row.getString(ConnectorsPaths.FieldProvenance.DATASET), row.getString(ConnectorsPaths.FieldProvenance.TABLE_PATH),
							row.getString(ConnectorsPaths.FieldProvenance.RECORD_KEY));
						if (Retention.mayDeleteProvenance(writtenAt, limit, existence))
						{
							orphans.add(row.getAdaptationName());
						}
					}
					reachedEnd = row == null;
				}
				finally
				{
					rows.close();
				}
				break;
			}
			catch (final RuntimeException ex)
			{
				if (attempt == 0 && Retention.PREDICATE_REFUSED.compareAndSet(false, true))
				{
					ConnectorService.log().warn(Redaction.redact("[connectors] retention: EBX refused the age predicate (" + ex.getMessage()
						+ "); the tables are read sorted by date instead, which costs a sort of each table at every pass"));
					continue;
				}
				throw ex;
			}
		}
		int deleted = 0;
		for (int from = 0; from < orphans.size() && !BackgroundWork.isStopped(); from += pPolicy.batchSize)
		{
			final List<AdaptationName> batch = orphans.subList(from, Math.min(orphans.size(), from + pPolicy.batchSize));
			if (!Bookkeeping.committed(Bookkeeping.RETENTION, "FieldProvenance", Retention.delete(pSession, pDataset, "FieldProvenance", new ArrayList<AdaptationName>(batch))))
			{
				break;
			}
			deleted += batch.size();
			try
			{
				Thread.sleep(Retention.PAUSE_BETWEEN_BATCHES_MS);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				break;
			}
		}
		Retention.PROVENANCE_SKIP.set(Retention.nextSkip(skipped, examined, deleted, reachedEnd));
		return deleted;
	}

	private static boolean isOld(final Adaptation pRow, final Date pLimit)
	{
		final Date date = pRow.getDate(ConnectorsPaths.FieldProvenance.WRITTEN_AT);
		return date != null && date.before(pLimit);
	}

	/** Looks one record up, resolving its table once per pass; never throws: what cannot be told is {@link Existence#UNKNOWN}. */
	private static Existence existence(final Repository pRepository, final java.util.Map<String, Object> pTables, final String pDataspace, final String pDataset,
		final String pTablePath, final String pRecordKey)
	{
		if (pDataspace == null || pDataset == null || pTablePath == null || pRecordKey == null)
		{
			return Existence.UNKNOWN;
		}
		try
		{
			final String id = pDataspace + '\u0000' + pDataset + '\u0000' + pTablePath;
			Object resolved = pTables.get(id);
			if (resolved == null)
			{
				resolved = Retention.resolve(pRepository, pDataspace, pDataset, pTablePath);
				pTables.put(id, resolved);
			}
			if (resolved instanceof Existence)
			{
				return (Existence) resolved;
			}
			return ((AdaptationTable) resolved).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pRecordKey)) == null
				? Existence.ABSENT : Existence.PRESENT;
		}
		catch (final RuntimeException ex)
		{
			return Existence.UNKNOWN;
		}
	}

	/** The table of a (dataspace, dataset, path), or the {@link Existence} that says why a record cannot be looked up in it. */
	private static Object resolve(final Repository pRepository, final String pDataspace, final String pDataset, final String pTablePath)
	{
		com.onwbp.adaptation.AdaptationHome home;
		try
		{
			home = pRepository.lookupHome(com.orchestranetworks.instance.HomeKey.forBranchName(pDataspace));
			if (home == null)
			{
				home = pRepository.lookupHome(com.orchestranetworks.instance.HomeKey.forVersionName(pDataspace));
			}
		}
		catch (final RuntimeException ex)
		{
			return Existence.UNKNOWN;
		}
		if (home == null)
		{
			return Existence.HOME_GONE;
		}
		if (!home.isOpen())
		{
			return Existence.UNKNOWN;
		}
		final Adaptation dataset = home.findAdaptationOrNull(com.onwbp.adaptation.AdaptationName.forName(pDataset));
		if (dataset == null)
		{
			return Existence.UNKNOWN;
		}
		try
		{
			final AdaptationTable table = dataset.getTable(Path.parse(pTablePath));
			return table == null ? Existence.UNKNOWN : table;
		}
		catch (final RuntimeException ex)
		{
			return Existence.UNKNOWN;
		}
	}

	/** At most {@code pMax} rows of the table that the target allows to delete. */
	private static List<Adaptation> select(final AdaptationTable pTable, final Target pTarget, final Date pNow, final int pMax)
	{
		if (!Retention.PREDICATE_REFUSED.get())
		{
			try
			{
				final Request request = pTable.createRequest();
				request.setXPathFilter(pTarget.predicate(pNow));
				return Retention.collect(request, pTarget, pNow, pMax, false);
			}
			catch (final RuntimeException ex)
			{
				if (Retention.PREDICATE_REFUSED.compareAndSet(false, true))
				{
					ConnectorService.log().warn(Redaction.redact("[connectors] retention: EBX refused the age predicate (" + ex.getMessage()
						+ "); the tables are read sorted by date instead, which costs a sort of each table at every pass"));
				}
			}
		}
		// Oldest first, stopping at the first row that is not old enough.
		final Request request = pTable.createRequest();
		if (pTarget.restriction != null)
		{
			request.setXPathFilter(pTarget.restriction);
		}
		request.setSortCriteria(new com.onwbp.adaptation.RequestSortCriteria().add(pTarget.dateField, true));
		return Retention.collect(request, pTarget, pNow, pMax, true);
	}

	private static List<Adaptation> collect(final Request pRequest, final Target pTarget, final Date pNow, final int pMax, final boolean pSortedByDate)
	{
		final List<Adaptation> doomed = new ArrayList<Adaptation>();
		final RequestResult rows = pRequest.execute();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null && doomed.size() < pMax; row = rows.nextAdaptation())
			{
				final Date date = row.getDate(pTarget.dateField);
				final String status = pTarget.statusField == null ? null : row.getString(pTarget.statusField);
				if (pTarget.mayDelete(date, status, pNow))
				{
					doomed.add(row);
				}
				else if (pSortedByDate && (date == null || !date.before(pTarget.limit(pNow))))
				{
					break;
				}
			}
		}
		finally
		{
			rows.close();
		}
		return doomed;
	}
}
