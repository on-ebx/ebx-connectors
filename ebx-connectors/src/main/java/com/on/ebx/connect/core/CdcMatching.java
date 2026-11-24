package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real-time mastering (docs/REALTIME-MASTERING.md): Match and Merge around one CDC batch. With {@code matching=afterApply}
 * the table's matching trigger is paused while the batch is written (the add-on would otherwise match inside every
 * sub-batch commit, the bulk-load defect of 6.3.0-rc2..rc4), resumed, then ONE matching process per chunk of the keys
 * the batch created or updated is run and waited for. The checkpoint may move only when {@link Outcome#isDone()}.
 * <p>
 * No EBX type here: the add-on is reached through {@link Engine} ({@link MameGoldenRecords} in the module, a fake in
 * {@code CdcMatchingTest}).
 */
public final class CdcMatching
{
	/** Input {@code matching} of a {@code cdc} feed. */
	public enum Mode
	{
		/** The add-on's own execution mode decides; the module does nothing (the default). */
		POLICY,
		/** Pause during the writes, then match the written keys; the checkpoint waits for it. */
		AFTER_APPLY,
		/** Pause during the writes, match nothing (a snapshot load, matched later by hand). */
		OFF
	}

	/** Default keys per matching process, the default {@code maxEvents} of a cdc run. */
	public static final int DEFAULT_CHUNK = 500;
	/** Default wait for one batch's matching, in seconds. */
	public static final int DEFAULT_TIMEOUT_SECONDS = 600;

	/** The add-on, for one table. Every method may throw; {@link CdcMatching} turns a failure into a not-done outcome. */
	public interface Engine
	{
		/** Whether the table's matching trigger is paused now (by anyone). */
		boolean isPaused() throws Exception;

		void pause() throws Exception;

		void resume() throws Exception;

		/** The policy code used when the mapping names none: the table's default matching policy. */
		String defaultPolicy() throws Exception;

		/** Starts one matching process on the records the XPath filter selects. */
		Process match(String pXPathFilter, String pPolicy) throws Exception;

		/** What became of the records the predicates select, read after the matching: state name per record and suspects. */
		Tally tally(Collection<String> pPredicates) throws Exception;
	}

	/** A running matching process. */
	public interface Process
	{
		/** INITIALIZING, RUNNING, DONE, FAILED, CANCELLING or CANCELLED (the add-on's {@code ProcessStatus}). */
		String status();

		void cancel();
	}

	/** States after the matching, for the run message. */
	public static final class Tally
	{
		public int golden;
		public int merged;
		public int other;
		public int withSuspects;
	}

	/** What the matching of one batch came to. */
	public static final class Outcome
	{
		private final boolean done;
		private final String message;

		Outcome(final boolean pDone, final String pMessage)
		{
			this.done = pDone;
			this.message = pMessage;
		}

		/** True when nothing was to match, or every process ended DONE: the checkpoint may move. */
		public boolean isDone()
		{
			return this.done;
		}

		public String getMessage()
		{
			return this.message;
		}
	}

	/** Waits between two status reads; a test replaces it. */
	public interface Clock
	{
		long now();

		void sleep(long pMillis) throws InterruptedException;
	}

	static final Clock SYSTEM_CLOCK = new Clock()
	{
		@Override
		public long now()
		{
			return System.currentTimeMillis();
		}

		@Override
		public void sleep(final long pMillis) throws InterruptedException
		{
			Thread.sleep(pMillis);
		}
	};

	private final Mode mode;
	private final String policy;
	private final int chunk;
	private final long timeoutMs;

	public CdcMatching(final Mode pMode, final String pPolicy, final int pChunk, final int pTimeoutSeconds)
	{
		this.mode = pMode == null ? Mode.POLICY : pMode;
		this.policy = pPolicy == null || pPolicy.trim().isEmpty() ? null : pPolicy.trim();
		this.chunk = Math.max(1, Math.min(10000, pChunk));
		this.timeoutMs = Math.max(1, pTimeoutSeconds) * 1000L;
	}

	/** {@code matching} (policy | afterApply | off), {@code matchingPolicy}, {@code matchingChunk} (500), {@code matchingTimeoutSeconds} (600). */
	public static CdcMatching from(final Map<String, String> pInputs)
	{
		final String raw = pInputs == null ? null : pInputs.get("matching");
		Mode mode = Mode.POLICY;
		if (raw != null && !raw.trim().isEmpty())
		{
			final String value = raw.trim().toLowerCase(Locale.ENGLISH);
			if ("policy".equals(value))
				mode = Mode.POLICY;
			else if ("afterapply".equals(value) || "after_apply".equals(value))
				mode = Mode.AFTER_APPLY;
			else if ("off".equals(value))
				mode = Mode.OFF;
			else
				throw new IllegalArgumentException("matching must be policy, afterApply or off, not '" + raw.trim() + "'");
		}
		return new CdcMatching(mode, pInputs == null ? null : pInputs.get("matchingPolicy"),
			CdcMatching.intInput(pInputs, "matchingChunk", CdcMatching.DEFAULT_CHUNK, 1, 10000),
			CdcMatching.intInput(pInputs, "matchingTimeoutSeconds", CdcMatching.DEFAULT_TIMEOUT_SECONDS, 1, 86400));
	}

	private static int intInput(final Map<String, String> pInputs, final String pName, final int pDefault, final int pMin, final int pMax)
	{
		final String raw = pInputs == null ? null : pInputs.get(pName);
		if (raw == null || raw.trim().isEmpty())
			return pDefault;
		try
		{
			final int value = Integer.parseInt(raw.trim());
			if (value < pMin || value > pMax)
				throw new IllegalArgumentException(pName + " must be between " + pMin + " and " + pMax + ", not " + value);
			return value;
		}
		catch (final NumberFormatException ex)
		{
			throw new IllegalArgumentException(pName + " must be a whole number, not '" + raw.trim() + "'");
		}
	}

	public Mode getMode()
	{
		return this.mode;
	}

	/** True when the module touches the add-on at all for this run. */
	public boolean isActive()
	{
		return this.mode != Mode.POLICY;
	}

	/**
	 * The writes of one batch, with the trigger paused around them when the mode asks for it. The trigger is resumed in a
	 * {@code finally}, and only when this run paused it: a table someone else paused stays paused. Returns whether this run
	 * paused it (for the log).
	 */
	public boolean whilePaused(final Engine pEngine, final Writes pWrites) throws Exception
	{
		if (!this.isActive() || pEngine == null)
		{
			pWrites.run();
			return false;
		}
		final boolean alreadyPaused = pEngine.isPaused();
		if (!alreadyPaused)
		{
			pEngine.pause();
		}
		try
		{
			pWrites.run();
		}
		finally
		{
			if (!alreadyPaused)
			{
				pEngine.resume();
			}
		}
		return !alreadyPaused;
	}

	/** The writes of a batch. */
	public interface Writes
	{
		void run() throws Exception;
	}

	/** {@link #matchWritten(Engine, Collection, Clock)} on the system clock. */
	public Outcome matchWritten(final Engine pEngine, final Collection<String> pPredicates)
	{
		return this.matchWritten(pEngine, pPredicates, CdcMatching.SYSTEM_CLOCK);
	}

	/**
	 * Matches the records the batch created or updated, one process per chunk of their key predicates
	 * ({@code ./code = 'A1'}, as the feed resolved them), each waited for until DONE. Any other end, an exception or the
	 * timeout (the process is then cancelled: a caller that stops polling leaves the add-on's threads busy) gives a
	 * not-done outcome, and the checkpoint stays where it was.
	 */
	public Outcome matchWritten(final Engine pEngine, final Collection<String> pPredicates, final Clock pClock)
	{
		if (this.mode == Mode.POLICY)
		{
			return new Outcome(true, null);
		}
		final List<String> keys = new ArrayList<String>(new LinkedHashSet<String>(pPredicates == null ? new ArrayList<String>() : pPredicates));
		keys.removeIf(k -> k == null || k.trim().isEmpty());
		if (this.mode == Mode.OFF)
		{
			return new Outcome(true, "matching: off, " + keys.size() + " record(s) written with matching paused, not matched");
		}
		if (keys.isEmpty())
		{
			return new Outcome(true, "matching: nothing to match");
		}
		final long start = pClock.now();
		final long deadline = start + this.timeoutMs;
		String policyCode = this.policy;
		try
		{
			if (policyCode == null)
			{
				policyCode = pEngine.defaultPolicy();
			}
			if (policyCode == null || policyCode.trim().isEmpty())
			{
				return new Outcome(false, "matching: the table has no default matching policy; set input matchingPolicy");
			}
			int processes = 0;
			for (int from = 0; from < keys.size(); from += this.chunk)
			{
				final List<String> part = keys.subList(from, Math.min(keys.size(), from + this.chunk));
				final Process process = pEngine.match(CdcMatching.filter(part), policyCode);
				processes++;
				final String status = CdcMatching.await(process, deadline, pClock);
				if (!"DONE".equals(status))
				{
					return new Outcome(false, "matching: process " + processes + " ended " + status + " on " + part.size() + " record(s), policy "
						+ policyCode + "; checkpoint kept, the next run matches them again");
				}
			}
			final StringBuilder message = new StringBuilder("matching: ").append(keys.size()).append(" record(s), policy ").append(policyCode);
			try
			{
				final Tally tally = pEngine.tally(keys);
				message.append(", ").append(tally.golden).append(" golden, ").append(tally.merged).append(" merged, ")
					.append(tally.withSuspects).append(" with suspect(s)");
				if (tally.other > 0)
					message.append(", ").append(tally.other).append(" other");
			}
			catch (final Exception ex)
			{
				message.append(" (states not read: ").append(Redaction.redact(String.valueOf(ex.getMessage()))).append(')');
			}
			message.append(", ").append(processes).append(" process(es), ").append(pClock.now() - start).append(" ms");
			return new Outcome(true, message.toString());
		}
		catch (final InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			return new Outcome(false, "matching: interrupted; checkpoint kept");
		}
		catch (final Exception ex)
		{
			return new Outcome(false, "matching failed: " + Redaction.redact(String.valueOf(ex.getMessage())) + "; checkpoint kept, the next run matches them again");
		}
	}

	/** Waits for a terminal status; cancels the process at the deadline and answers TIMEOUT. */
	static String await(final Process pProcess, final long pDeadline, final Clock pClock) throws InterruptedException
	{
		long pause = 100L;
		while (true)
		{
			final String status = String.valueOf(pProcess.status());
			if ("DONE".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status))
			{
				return status;
			}
			if (pClock.now() >= pDeadline)
			{
				pProcess.cancel();
				return "TIMEOUT (cancelled)";
			}
			pClock.sleep(Math.min(pause, Math.max(1L, pDeadline - pClock.now())));
			pause = Math.min(2000L, pause * 2);
		}
	}

	/** The XPath filter of one chunk: the key predicates joined by {@code or}, each in parentheses. */
	static String filter(final List<String> pPredicates)
	{
		final StringBuilder out = new StringBuilder();
		for (final String predicate : pPredicates)
		{
			if (out.length() > 0)
				out.append(" or ");
			out.append('(').append(predicate.trim()).append(')');
		}
		return out.toString();
	}
}
