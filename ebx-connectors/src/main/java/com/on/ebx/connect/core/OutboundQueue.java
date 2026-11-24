package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a table trigger sends to an external application, sent AFTER the user's transaction (audit C-07, lot L8).
 * <p>
 * A trigger used to call the remote system inside the user's transaction: the message left before the commit (a rollback
 * left the remote system ahead), the dataspace lock was held for the remote delays, and a failure was one WARN line. The
 * trigger now only {@link #submit hands over} a small {@link Entry} (the record's location, the connector and operation);
 * a background task of the module persists it in the {@code OutboundQueue} table, waits a few seconds so that the user's
 * transaction is committed, re-reads the record as it is then, sends it, and deletes the row.
 * <p>
 * <b>Limit, in plain words:</b> the queue row is NOT validated with the user's transaction (it lives in another dataspace); a
 * brutal stop of the JVM in the few milliseconds between the trigger and the row's write loses the send. {@code blocking=true}
 * is the only strong guarantee.
 * <p>
 * <b>At least once.</b> A row is deleted only after a send that did not fail; a crash between the send and the delete, or a
 * remote system that processed a message whose answer was lost, makes the next pass send the record again. The target must
 * therefore tolerate a repeated message (idempotent by the record's key). Rows of the same record that are due together are
 * sent once. A failed send is retried after the mapping's retry delay and set aside ({@code DEAD}) after the mapping's
 * maximum number of attempts, the same {@code maxAttempts} the outbound scopes use. Rows left {@code PENDING} by a stop or a
 * crash are taken again at the next start.
 * <p>
 * A procedure cannot write into the Connectors dataspace from the user's dataspace, so the row is written by the module's
 * own thread, in its own transaction, a moment after the trigger. The only window in which a queued send can be lost is
 * therefore a JVM crash between the trigger and that write (milliseconds, flushed at a normal stop). Because the record is
 * re-read at send time, a record whose creation was rolled back is simply not found (the row is dropped) and a rolled-back
 * modification sends the unchanged state.
 * <p>
 * This class holds the whole logic and no EBX type: the table is reached through {@link Store}, the send through {@link Sender}.
 */
public final class OutboundQueue
{
	public static final String PENDING = "PENDING";
	public static final String DEAD = "DEAD";

	/** How long a row waits after the write so that the user's transaction is committed. {@code -Debx.connectors.outboundQueue.settleMs}, default 2000. */
	static final String SETTLE_PROPERTY = "ebx.connectors.outboundQueue.settleMs";
	/** Period of the background pass. {@code -Debx.connectors.outboundQueue.intervalMs}, default 3000. */
	static final String INTERVAL_PROPERTY = "ebx.connectors.outboundQueue.intervalMs";
	/** Rows sent per pass. {@code -Debx.connectors.outboundQueue.batch}, default 200. */
	static final String BATCH_PROPERTY = "ebx.connectors.outboundQueue.batch";
	static final int MAX_INTAKE = 100000;

	/** One queued send: where the record is, and which connector operation to run on it. */
	public static final class Entry
	{
		public Integer id;
		public String connector;
		public String operation;
		public String direction;
		public String dataspace;
		public String dataset;
		public String tablePath;
		public String recordKey;
		/** {@link OutboundQueue#PUBLISH_GOLDEN}: send the golden of a MERGED record instead (real-time mastering); null: the record. */
		public String publish;
		public long enqueuedAt;
		public long notBefore;
		public int attempts;
		public String status = PENDING;
		public String lastError;

		/** What identifies the send: two rows with the same key ask for the same thing. */
		public String key()
		{
			return this.connector + '\u0001' + this.operation + '\u0001' + this.direction + '\u0001' + this.dataspace + '\u0001' + this.dataset + '\u0001'
				+ this.tablePath + '\u0001' + this.recordKey + (this.publish == null ? "" : '\u0001' + this.publish);
		}

		/** A copy that sends {@code pRecordKey} instead (the golden of this entry's record). */
		Entry sending(final String pRecordKey)
		{
			final Entry copy = new Entry();
			copy.id = this.id;
			copy.connector = this.connector;
			copy.operation = this.operation;
			copy.direction = this.direction;
			copy.dataspace = this.dataspace;
			copy.dataset = this.dataset;
			copy.tablePath = this.tablePath;
			copy.recordKey = pRecordKey;
			copy.publish = this.publish;
			copy.enqueuedAt = this.enqueuedAt;
			copy.notBefore = this.notBefore;
			copy.attempts = this.attempts;
			copy.status = this.status;
			copy.lastError = this.lastError;
			return copy;
		}

		@Override
		public String toString()
		{
			return this.connector + "/" + this.operation + " on " + this.tablePath + "[" + this.recordKey + "]";
		}
	}

	/** The queue table. Every method may throw: the pass logs it and tries again at the next one. */
	public interface Store
	{
		void add(List<Entry> pEntries) throws Exception;

		/** PENDING rows whose {@code notBefore} has passed, oldest first, at most {@code pMax}. */
		List<Entry> due(long pNow, int pMax) throws Exception;

		void delete(List<Entry> pEntries) throws Exception;

		/** After a failed send: the new attempts count, the next time (ignored when dead), the error, and whether the rows are set aside. */
		void failed(List<Entry> pEntries, int pAttempts, long pNotBefore, String pError, boolean pDead, long pNow) throws Exception;
	}

	/** What the send of one entry came to. */
	public static final class Outcome
	{
		final boolean ok;
		final boolean drop;
		final String message;
		final int maxAttempts;
		final long retryDelayMs;

		private Outcome(final boolean pOk, final boolean pDrop, final String pMessage, final int pMaxAttempts, final long pRetryDelayMs)
		{
			this.ok = pOk;
			this.drop = pDrop;
			this.message = pMessage;
			this.maxAttempts = pMaxAttempts;
			this.retryDelayMs = pRetryDelayMs;
		}

		public static Outcome sent()
		{
			return new Outcome(true, false, null, 0, 0L);
		}

		/** Nothing to send any more (record gone, no mapping): the row is deleted, the reason is logged. */
		public static Outcome drop(final String pReason)
		{
			return new Outcome(false, true, pReason, 0, 0L);
		}

		/** A failure to try again, under the mapping's policy. */
		public static Outcome retry(final String pMessage, final int pMaxAttempts, final long pRetryDelayMs)
		{
			return new Outcome(false, false, pMessage, Math.max(1, pMaxAttempts), Math.max(0L, pRetryDelayMs));
		}
	}

	/** Sends one entry; never called inside the user's transaction. */
	public interface Sender
	{
		Outcome send(Entry pEntry);
	}

	/** {@link Entry#publish} value of a trigger with {@code publish=golden}. */
	public static final String PUBLISH_GOLDEN = "golden";

	/**
	 * Real-time mastering: what a {@code publish=golden} entry sends, decided at send time (the matching that merged the
	 * record ran after the write): the record's own key, its golden's key, or nothing.
	 */
	public interface Resolver
	{
		Resolution resolve(Entry pEntry);
	}

	/** The key to send, or the reason nothing is sent. */
	public static final class Resolution
	{
		final String recordKey;
		final String dropReason;

		private Resolution(final String pRecordKey, final String pDropReason)
		{
			this.recordKey = pRecordKey;
			this.dropReason = pDropReason;
		}

		public static Resolution send(final String pRecordKey)
		{
			return new Resolution(pRecordKey, null);
		}

		public static Resolution drop(final String pReason)
		{
			return new Resolution(null, pReason);
		}
	}

	/** What a pass did, for the log and the tests. */
	public static final class PassResult
	{
		public int sent;
		public int dropped;
		public int retried;
		public int dead;
		public int rowsDeleted;

		@Override
		public String toString()
		{
			return sent + " sent, " + dropped + " dropped, " + retried + " to retry, " + dead + " set aside";
		}
	}

	private static final ThreadLocal<Boolean> SENDING = new ThreadLocal<Boolean>();
	private static final ConcurrentLinkedDeque<Entry> INTAKE = new ConcurrentLinkedDeque<Entry>();
	private static final AtomicInteger INTAKE_SIZE = new AtomicInteger();
	private static final AtomicBoolean KICKED = new AtomicBoolean();
	private static final AtomicBoolean WARNED_FULL = new AtomicBoolean();
	private static volatile Store store;
	private static volatile Sender sender;
	private static volatile Resolver resolver;

	private static final BackgroundWork.Managed<ScheduledExecutorService> EXECUTOR = BackgroundWork.managed("outbound queue",
		new BackgroundWork.Factory<ScheduledExecutorService>()
		{
			@Override
			public ScheduledExecutorService create()
			{
				return new ScheduledThreadPoolExecutor(1, BackgroundWork.daemonThreads("ebx-connectors-outbound-queue", false));
			}
		});

	private OutboundQueue()
	{
	}

	/** True on the sender's own thread: the writes a send makes fire the same trigger again, which must not queue the record once more. */
	public static boolean isSending()
	{
		return Boolean.TRUE.equals(OutboundQueue.SENDING.get());
	}

	/** True once {@link #start} has run and the module is not stopped: a trigger may hand over. */
	public static boolean isRunning()
	{
		return OutboundQueue.store != null && !BackgroundWork.isStopped();
	}

	/** Repository startup: the background sender begins; rows left PENDING by a stop or a crash are taken by its first pass. */
	public static void start(final Store pStore, final Sender pSender)
	{
		OutboundQueue.start(pStore, pSender, null);
	}

	/** {@link #start(Store, Sender)} with the resolver of {@code publish=golden} entries (null: they send their own record). */
	public static void start(final Store pStore, final Sender pSender, final Resolver pResolver)
	{
		OutboundQueue.store = pStore;
		OutboundQueue.sender = pSender;
		OutboundQueue.resolver = pResolver;
		OutboundQueue.WARNED_FULL.set(false);
		final long interval = OutboundQueue.longProperty(OutboundQueue.INTERVAL_PROPERTY, 3000L, 100L);
		final ScheduledExecutorService executor = OutboundQueue.EXECUTOR.get();
		executor.scheduleWithFixedDelay(new Runnable()
		{
			@Override
			public void run()
			{
				OutboundQueue.pass();
			}
		}, 1000L, interval, TimeUnit.MILLISECONDS);
	}

	/**
	 * Hands one send over: no EBX call, no wait. Returns false (and the caller says so) when the module is not running or the
	 * hand-over buffer is full; the entry is then not queued.
	 */
	public static boolean submit(final Entry pEntry)
	{
		if (!OutboundQueue.isRunning())
		{
			return false;
		}
		if (OutboundQueue.INTAKE_SIZE.get() >= OutboundQueue.MAX_INTAKE)
		{
			if (OutboundQueue.WARNED_FULL.compareAndSet(false, true))
			{
				ConnectorService.log().warn("[connectors] outbound queue: " + OutboundQueue.MAX_INTAKE
					+ " sends are waiting to be written to the OutboundQueue table (is the Connectors dataset writable?); new ones are refused until it drains");
			}
			return false;
		}
		pEntry.enqueuedAt = System.currentTimeMillis();
		pEntry.notBefore = pEntry.enqueuedAt + OutboundQueue.longProperty(OutboundQueue.SETTLE_PROPERTY, 2000L, 0L);
		OutboundQueue.INTAKE.addLast(pEntry);
		OutboundQueue.INTAKE_SIZE.incrementAndGet();
		if (OutboundQueue.KICKED.compareAndSet(false, true))
		{
			try
			{
				OutboundQueue.EXECUTOR.get().schedule(new Runnable()
				{
					@Override
					public void run()
					{
						OutboundQueue.KICKED.set(false);
						OutboundQueue.persistIntake(OutboundQueue.store);
					}
				}, 0L, TimeUnit.MILLISECONDS);
			}
			catch (final RejectedExecutionException ex)
			{
				OutboundQueue.KICKED.set(false);
				return false;
			}
		}
		return true;
	}

	/** Stop of the module: what was handed over and not yet written is written now, on the stopping thread. */
	public static void flushIntake()
	{
		final Store current = OutboundQueue.store;
		if (current != null)
		{
			OutboundQueue.persistIntake(current);
		}
	}

	/** After the executors are stopped. */
	public static void forget()
	{
		OutboundQueue.store = null;
		OutboundQueue.sender = null;
		OutboundQueue.resolver = null;
		OutboundQueue.INTAKE.clear();
		OutboundQueue.INTAKE_SIZE.set(0);
		OutboundQueue.KICKED.set(false);
	}

	/** One pass of the background task: write what was handed over, then send what is due. */
	static void pass()
	{
		final Store current = OutboundQueue.store;
		final Sender currentSender = OutboundQueue.sender;
		if (current == null || currentSender == null || BackgroundWork.isStopped())
		{
			return;
		}
		try
		{
			OutboundQueue.persistIntake(current);
			OutboundQueue.SENDING.set(Boolean.TRUE);
			final PassResult result = OutboundQueue.drain(current, currentSender, OutboundQueue.resolver, System.currentTimeMillis(),
				(int) OutboundQueue.longProperty(OutboundQueue.BATCH_PROPERTY, 200L, 1L));
			if (result.sent + result.dropped + result.retried + result.dead > 0)
			{
				ConnectorService.log().info("[connectors] outbound queue pass: " + result);
			}
		}
		catch (final Exception ex)
		{
			ConnectorService.log().warn("[connectors] outbound queue pass failed, tried again at the next one: " + Redaction.redact(String.valueOf(ex.getMessage())));
		}
		finally
		{
			OutboundQueue.SENDING.remove();
		}
	}

	/** Writes the handed-over entries (the same send asked twice in a row is written once); on failure they are put back in order. */
	static void persistIntake(final Store pStore)
	{
		if (pStore == null)
		{
			return;
		}
		final Map<String, Entry> batch = new LinkedHashMap<String, Entry>();
		final List<Entry> taken = new ArrayList<Entry>();
		for (Entry entry = OutboundQueue.INTAKE.pollFirst(); entry != null && taken.size() < 5000; entry = OutboundQueue.INTAKE.pollFirst())
		{
			taken.add(entry);
			OutboundQueue.INTAKE_SIZE.decrementAndGet();
			batch.put(entry.key(), entry); // the latest wins: the send reads the record as it is
		}
		if (taken.isEmpty())
		{
			return;
		}
		try
		{
			pStore.add(new ArrayList<Entry>(batch.values()));
		}
		catch (final Exception ex)
		{
			for (int i = taken.size() - 1; i >= 0; i--)
			{
				OutboundQueue.INTAKE.addFirst(taken.get(i));
				OutboundQueue.INTAKE_SIZE.incrementAndGet();
			}
			ConnectorService.log().warn("[connectors] outbound queue: " + taken.size() + " send(s) could not be written to the OutboundQueue table, tried again at the next pass: "
				+ Redaction.redact(String.valueOf(ex.getMessage())));
		}
	}

	/**
	 * Sends what is due. Rows asking for the same thing are sent once; the group is deleted when the send did not fail (or
	 * cannot ever succeed), and counted as one more attempt for each row otherwise, set aside at the mapping's maximum.
	 */
	public static PassResult drain(final Store pStore, final Sender pSender, final long pNow, final int pMax)
	{
		return OutboundQueue.drain(pStore, pSender, null, pNow, pMax);
	}

	/**
	 * {@link #drain(Store, Sender, long, int)}; a {@code publish=golden} entry is first resolved by {@code pResolver}: it sends
	 * its golden's key (two members of one golden, due together, are sent once), or nothing (the row is deleted, counted
	 * dropped). A resolver that throws leaves the row for a retry, never sends the record itself.
	 */
	public static PassResult drain(final Store pStore, final Sender pSender, final Resolver pResolver, final long pNow, final int pMax)
	{
		final PassResult result = new PassResult();
		final List<Entry> due;
		try
		{
			due = pStore.due(pNow, pMax);
		}
		catch (final Exception ex)
		{
			ConnectorService.log().warn("[connectors] outbound queue: the due rows cannot be read: " + Redaction.redact(String.valueOf(ex.getMessage())));
			return result;
		}
		final Map<String, List<Entry>> groups = new LinkedHashMap<String, List<Entry>>();
		final Map<String, Entry> toSend = new LinkedHashMap<String, Entry>();
		for (final Entry entry : due)
		{
			Entry sent = entry;
			if (pResolver != null && OutboundQueue.PUBLISH_GOLDEN.equals(entry.publish))
			{
				final Resolution resolution;
				try
				{
					resolution = pResolver.resolve(entry);
				}
				catch (final RuntimeException ex)
				{
					result.retried++;
					OutboundQueue.failQuietly(pStore, Collections.singletonList(entry), entry.attempts + 1, pNow + 60000L,
						"golden not resolved: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()), entry.attempts + 1 >= 5, pNow);
					continue;
				}
				if (resolution == null || resolution.recordKey == null)
				{
					result.dropped++;
					ConnectorService.log().info("[connectors] outbound queue: " + entry + " not sent: "
						+ Redaction.redact(String.valueOf(resolution == null ? "no golden" : resolution.dropReason)));
					OutboundQueue.deleteQuietly(pStore, Collections.singletonList(entry), result);
					continue;
				}
				sent = entry.sending(resolution.recordKey);
			}
			List<Entry> group = groups.get(sent.key());
			if (group == null)
			{
				group = new ArrayList<Entry>();
				groups.put(sent.key(), group);
				toSend.put(sent.key(), sent);
			}
			group.add(entry);
		}
		for (final Map.Entry<String, List<Entry>> keyed : groups.entrySet())
		{
			if (BackgroundWork.isStopped())
			{
				break; // what is left stays PENDING for the next start
			}
			final List<Entry> group = keyed.getValue();
			final Entry first = toSend.get(keyed.getKey());
			Outcome outcome;
			try
			{
				outcome = pSender.send(first);
			}
			catch (final RuntimeException ex)
			{
				outcome = Outcome.retry(ex.getMessage() == null ? ex.toString() : ex.getMessage(), 5, 60000L);
			}
			try
			{
				if (outcome.ok || outcome.drop)
				{
					if (outcome.drop)
					{
						result.dropped++;
						ConnectorService.log().info("[connectors] outbound queue: " + first + " not sent: " + Redaction.redact(String.valueOf(outcome.message)));
					}
					else
					{
						result.sent++;
					}
					pStore.delete(group);
					result.rowsDeleted += group.size();
				}
				else
				{
					int attempts = 0;
					for (final Entry entry : group)
					{
						attempts = Math.max(attempts, entry.attempts);
					}
					attempts++;
					final boolean dead = attempts >= outcome.maxAttempts;
					if (dead)
					{
						result.dead++;
						ConnectorService.log().warn("[connectors] outbound queue: " + first + " set aside after " + attempts + " attempt(s): " + Redaction.redact(String.valueOf(outcome.message)));
					}
					else
					{
						result.retried++;
					}
					pStore.failed(group, attempts, pNow + outcome.retryDelayMs, outcome.message, dead, pNow);
				}
			}
			catch (final Exception ex)
			{
				// the send happened (or failed) and the row could not be updated: it is sent again next time (at least once)
				ConnectorService.log().warn("[connectors] outbound queue: the row of " + first + " could not be updated, it will be sent again: "
					+ Redaction.redact(String.valueOf(ex.getMessage())));
			}
		}
		return result;
	}

	private static void deleteQuietly(final Store pStore, final List<Entry> pRows, final PassResult pResult)
	{
		try
		{
			pStore.delete(pRows);
			pResult.rowsDeleted += pRows.size();
		}
		catch (final Exception ex)
		{
			ConnectorService.log().warn("[connectors] outbound queue: rows " + pRows + " could not be deleted, looked at again next time: "
				+ Redaction.redact(String.valueOf(ex.getMessage())));
		}
	}

	private static void failQuietly(final Store pStore, final List<Entry> pRows, final int pAttempts, final long pNotBefore, final String pError,
		final boolean pDead, final long pNow)
	{
		try
		{
			pStore.failed(pRows, pAttempts, pNotBefore, pError, pDead, pNow);
		}
		catch (final Exception ex)
		{
			ConnectorService.log().warn("[connectors] outbound queue: rows " + pRows + " could not be updated, looked at again next time: "
				+ Redaction.redact(String.valueOf(ex.getMessage())));
		}
	}

	/** {@code -Debx.connectors.trigger.retryDelayMinutes}: the retry delay of a trigger-queued send. Default 2. */
	static final String TRIGGER_RETRY_PROPERTY = "ebx.connectors.trigger.retryDelayMinutes";

	/** The retry delay of a trigger-queued send: the property (default 2 minutes), capped by the mapping's own delay when that is smaller. */
	static long triggerRetryDelayMs(final int pMappingMinutes)
	{
		final long minutes = Math.min(OutboundQueue.longProperty(OutboundQueue.TRIGGER_RETRY_PROPERTY, 2L, 0L), Math.max(0, pMappingMinutes));
		return 60000L * minutes;
	}

	static long longProperty(final String pName, final long pDefault, final long pMin)
	{
		try
		{
			final long value = Long.parseLong(System.getProperty(pName, String.valueOf(pDefault)).trim());
			return value < pMin ? pDefault : value;
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}
}
