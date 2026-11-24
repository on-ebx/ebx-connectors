package com.on.ebx.connect.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.orchestranetworks.service.ProcedureResult;

/**
 * The rows the module keeps <i>about</i> a synchronisation - sync log, field provenance, conflict
 * proposals, run state, outbound state, publication report - are written in their own procedures,
 * after the business write, so that losing one never rolls back or fails the business change.
 * <p>
 * That rule has a blind spot: {@code ProgrammaticService.execute} does not throw when its procedure
 * fails, it answers a {@link ProcedureResult} that says so. A write whose result is not read is
 * lost in silence, and what it meant is lost with it: an outbound state {@code SENT} that is not
 * saved means the record is sent again, a {@code FAILED} that is not saved hides the record from
 * the {@code failed} scope, a run state that is not saved leaves the run {@code RUNNING}.
 * <p>
 * This class is where every such write is checked. A loss is never an exception - the business
 * write is committed and stays a success - but it is always said: an ERROR line in the kernel log
 * (redacted, and throttled so that a broken table cannot flood the log on a long run), a count the
 * caller folds into its result, and, for the final state of a run, a memory of what the row should
 * have said.
 */
public final class Bookkeeping
{
	public static final String SYNC_LOG = "sync log";
	public static final String PROVENANCE = "field provenance";
	public static final String CONFLICTS = "conflict proposals";
	public static final String RUN_STATE = "run state";
	public static final String OUTBOUND_STATE = "outbound state";
	public static final String PUBLICATION = "publication report";
	public static final String RETENTION = "retention purge";

	/** Every loss of a kind is logged up to this many, then one in {@link #LOG_EVERY}. */
	static final int LOG_FIRST = 10;
	static final int LOG_EVERY = 1000;
	/** Runs whose final state could not be saved, remembered until the JVM stops. */
	static final int LOST_RUN_STATES_MAX = 500;

	private static final ConcurrentHashMap<String, AtomicLong> LOST = new ConcurrentHashMap<String, AtomicLong>();
	private static final Map<String, LostRunState> LOST_RUN_STATES = new LinkedHashMap<String, LostRunState>()
	{
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(final Map.Entry<String, LostRunState> pEldest)
		{
			return this.size() > Bookkeeping.LOST_RUN_STATES_MAX;
		}
	};

	/** The final state a run reached when its {@code ExecutionRun} row could not say so. */
	public static final class LostRunState
	{
		private final String status;
		private final String message;

		LostRunState(final String pStatus, final String pMessage)
		{
			this.status = pStatus;
			this.message = pMessage;
		}

		public String getStatus()
		{
			return this.status;
		}

		public String getMessage()
		{
			return this.message;
		}
	}

	private Bookkeeping()
	{
	}

	/**
	 * Whether a bookkeeping procedure was committed. A failed result is reported (see {@link #lost})
	 * and answers false; it never throws.
	 */
	static boolean committed(final String pKind, final String pContext, final ProcedureResult pResult)
	{
		if (pResult == null || !pResult.hasFailed())
		{
			return true;
		}
		String reason;
		try
		{
			reason = pResult.getExceptionFullMessage(java.util.Locale.ENGLISH);
		}
		catch (final RuntimeException ex)
		{
			reason = null;
		}
		Bookkeeping.lost(pKind, pContext, reason == null ? "the procedure failed without a message" : reason);
		return false;
	}

	/** Reports a bookkeeping write that threw; always answers false, for {@code return Bookkeeping.lost(...)}. */
	static boolean lost(final String pKind, final String pContext, final Throwable pFailure)
	{
		return Bookkeeping.lost(pKind, pContext, pFailure == null ? null : pFailure.getMessage() == null ? pFailure.toString() : pFailure.getMessage());
	}

	static boolean lost(final String pKind, final String pContext, final String pReason)
	{
		AtomicLong counter = Bookkeeping.LOST.get(pKind);
		if (counter == null)
		{
			final AtomicLong fresh = new AtomicLong();
			counter = Bookkeeping.LOST.putIfAbsent(pKind, fresh);
			if (counter == null)
			{
				counter = fresh;
			}
		}
		final long occurrence = counter.incrementAndGet();
		if (Bookkeeping.shouldLog(occurrence))
		{
			ConnectorService.log().error(Bookkeeping.logLine(pKind, pContext, pReason, occurrence));
		}
		return false;
	}

	/** The first losses of a kind are all logged; past that, one in a thousand, so a broken table costs one line per thousand rows. */
	static boolean shouldLog(final long pOccurrence)
	{
		return pOccurrence <= Bookkeeping.LOG_FIRST || pOccurrence % Bookkeeping.LOG_EVERY == 0;
	}

	static String logLine(final String pKind, final String pContext, final String pReason, final long pOccurrence)
	{
		return ConnectorRepository.cut(Redaction.redact("[connectors] " + pKind + " not written" + (pContext == null || pContext.isEmpty() ? "" : " (" + pContext + ")")
			+ ": " + (pReason == null ? "no reason given" : pReason) + " - the business write it describes is not affected"
			+ (pOccurrence > Bookkeeping.LOG_FIRST ? " [occurrence " + pOccurrence + " since start; logged once in " + Bookkeeping.LOG_EVERY + "]"
				: pOccurrence == Bookkeeping.LOG_FIRST ? " [occurrence " + pOccurrence + "; further ones are logged once in " + Bookkeeping.LOG_EVERY + "]" : "")), 2000);
	}

	/** Losses of one kind since the JVM started. */
	public static long lostCount(final String pKind)
	{
		final AtomicLong counter = Bookkeeping.LOST.get(pKind);
		return counter == null ? 0L : counter.get();
	}

	/** Remembers the final state of a run whose row could not be written, for whoever reads the run before the next restart. */
	static void rememberLostRunState(final String pRunId, final String pStatus, final String pMessage)
	{
		if (pRunId == null)
		{
			return;
		}
		synchronized (Bookkeeping.LOST_RUN_STATES)
		{
			Bookkeeping.LOST_RUN_STATES.put(pRunId, new LostRunState(pStatus, pMessage));
		}
	}

	/** A later write of the same run succeeded: the row speaks for itself again. */
	static void forgetLostRunState(final String pRunId)
	{
		if (pRunId == null)
		{
			return;
		}
		synchronized (Bookkeeping.LOST_RUN_STATES)
		{
			Bookkeeping.LOST_RUN_STATES.remove(pRunId);
		}
	}

	/** The final state of a run whose row could not say it; null when the row is to be believed. */
	public static LostRunState lostRunState(final String pRunId)
	{
		if (pRunId == null)
		{
			return null;
		}
		synchronized (Bookkeeping.LOST_RUN_STATES)
		{
			return Bookkeeping.LOST_RUN_STATES.get(pRunId);
		}
	}

	/** The losses carried by the results of a run that is a list of results (record syncs, an outbound scope, a replay), by kind. */
	public static Map<String, Integer> lostIn(final java.util.List<SyncResult> pResults)
	{
		Map<String, Integer> lost = null;
		for (final SyncResult result : pResults)
		{
			for (final String kind : result.getBookkeepingLost())
			{
				if (lost == null)
				{
					lost = new LinkedHashMap<String, Integer>();
				}
				final Integer count = lost.get(kind);
				lost.put(kind, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
			}
		}
		return lost == null ? java.util.Collections.<String, Integer> emptyMap() : lost;
	}

	/** {@code "sync log (2), run state (1)"} - the losses of one run or one result, for a message. */
	public static String describe(final Map<String, Integer> pLost)
	{
		if (pLost == null || pLost.isEmpty())
		{
			return null;
		}
		final StringBuilder sb = new StringBuilder();
		for (final Map.Entry<String, Integer> entry : pLost.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(entry.getKey()).append(" (").append(entry.getValue()).append(')');
		}
		return "Bookkeeping incomplete - the data itself is written, but these rows about it could not be saved: " + sb
			+ ". See the kernel log; a lost outbound state means the record is sent again or missing from the 'failed' scope, a lost run state means the run row is stale.";
	}
}
