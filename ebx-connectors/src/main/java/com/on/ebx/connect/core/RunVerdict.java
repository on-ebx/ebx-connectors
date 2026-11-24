package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether an unattended run may be reported as a success.
 * <p>
 * A run started by a person shows its counters to that person. A run started by the scheduler shows
 * them to nobody: the only thing an operator sees is whether the execution succeeded. This class
 * turns the outcome of a run into the causes for which it must not be called a success, so that the
 * scheduled task can fail on them and the workflow task can expose them. It only reads counters the
 * engine already keeps; it never touches EBX.
 * <p>
 * What is deliberately <b>not</b> a cause: a record for which the source had no data, and a record
 * that was not eligible for the call. Both are the ordinary life of an enrichment over a table and
 * say nothing about the health of the run.
 */
public final class RunVerdict
{
	/** A reason for which a run is not a plain success. The lower-case name is what the task's {@code tolerate} property accepts. */
	public enum Cause
	{
		/** Items or calls in error, a record written with field errors, or the run itself failing before or during the read. */
		ERRORS,
		/** Feed items declined because the mapping does not create missing records and nothing matched their key. */
		REJECTED,
		/** Values refused by a TRUSTED rule and kept as proposals for a steward. */
		CONFLICTS,
		/** The read was cut at the maximum: the source held more than the run took. */
		TRUNCATED,
		/** The run stopped before the end of what it had read (circuit breaker, cancellation). */
		STOPPED;

		String key()
		{
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	private final Set<Cause> causes = EnumSet.noneOf(Cause.class);
	private final List<String> details = new ArrayList<String>();

	private RunVerdict()
	{
	}

	/** A verdict with no cause yet, to {@link #merge} the runs of several mappings into. */
	public static RunVerdict clean()
	{
		return new RunVerdict();
	}

	/** The verdict of a feed import. */
	public static RunVerdict of(final ImportOutcome pOutcome)
	{
		final RunVerdict verdict = new RunVerdict();
		if (pOutcome == null)
		{
			return verdict;
		}
		if (pOutcome.getRunFailure() != null)
		{
			verdict.add(Cause.ERRORS, "the run failed: " + pOutcome.getRunFailure()
				+ (pOutcome.getErrorCount() > 1 ? " (" + (pOutcome.getErrorCount() - 1) + " other error(s))" : ""));
		}
		else if (pOutcome.getErrorCount() > 0)
		{
			verdict.add(Cause.ERRORS, pOutcome.getErrorCount() + " item(s) in error" + RunVerdict.firstMessage(pOutcome.getErrors()));
		}
		else if (!pOutcome.getErrors().isEmpty())
		{
			// No item is counted in error, yet the run holds failing results: the call to the source, the
			// target dataset or the completion of a catalog scan failed, or simulated items have field
			// errors. The counters alone would read "nothing to do" or "all written".
			verdict.add(Cause.ERRORS, pOutcome.getErrors().size() + " failure(s) reported by the run outside its item counters" + RunVerdict.firstMessage(pOutcome.getErrors()));
		}
		if (pOutcome.getRejected() > 0)
		{
			verdict.add(Cause.REJECTED, pOutcome.getRejected() + " item(s) rejected (no matching record and the mapping does not create)");
		}
		if (pOutcome.getConflicts() > 0)
		{
			verdict.add(Cause.CONFLICTS, pOutcome.getConflicts() + " item(s) with a conflict left for a steward");
		}
		if (pOutcome.isTruncated())
		{
			verdict.add(Cause.TRUNCATED, "the read was cut at the maximum" + (pOutcome.getAvailable() >= 0 ? " (the source held " + pOutcome.getAvailable() + " item(s))" : ""));
		}
		if (pOutcome.getSkipped() > 0)
		{
			verdict.add(Cause.STOPPED, "the run stopped before the end, leaving at least " + pOutcome.getSkipped() + " item(s) unprocessed");
		}
		return verdict;
	}

	/**
	 * The verdict of a run that is a list of record results (records of a table, an outbound scope).
	 *
	 * @param pMoreThanMax true when the table or scope held more records than the run was allowed to take
	 */
	public static RunVerdict of(final List<SyncResult> pResults, final boolean pMoreThanMax)
	{
		final RunVerdict verdict = new RunVerdict();
		int failed = 0;
		int conflicts = 0;
		String first = null;
		if (pResults != null)
		{
			for (final SyncResult result : pResults)
			{
				if (result.hasFailure())
				{
					failed++;
					if (first == null)
					{
						first = result.getMessage();
					}
				}
				if (!result.getConflicts().isEmpty())
				{
					conflicts++;
				}
			}
		}
		if (failed > 0)
		{
			verdict.add(Cause.ERRORS, failed + " record(s) in error" + RunVerdict.quoted(first));
		}
		if (conflicts > 0)
		{
			verdict.add(Cause.CONFLICTS, conflicts + " record(s) with a conflict left for a steward");
		}
		if (pMoreThanMax)
		{
			verdict.add(Cause.TRUNCATED, "the table holds more records than the maximum of the run");
		}
		return verdict;
	}

	/** Folds another verdict into this one (a step that runs several mappings). */
	public RunVerdict merge(final RunVerdict pOther)
	{
		if (pOther != null)
		{
			this.causes.addAll(pOther.causes);
			this.details.addAll(pOther.details);
		}
		return this;
	}

	private void add(final Cause pCause, final String pDetail)
	{
		this.causes.add(pCause);
		this.details.add(pDetail);
	}

	/** True when nothing at all stands against calling the run a success. */
	public boolean isClean()
	{
		return this.causes.isEmpty();
	}

	public boolean has(final Cause pCause)
	{
		return this.causes.contains(pCause);
	}

	/** The causes of this run that the caller does not tolerate; empty means the run may be reported as a success. */
	public Set<Cause> failing(final Set<Cause> pTolerated)
	{
		final Set<Cause> failing = EnumSet.copyOf(this.causes.isEmpty() ? EnumSet.noneOf(Cause.class) : this.causes);
		if (pTolerated != null)
		{
			failing.removeAll(pTolerated);
		}
		return failing;
	}

	/** One sentence per cause, in the order they were found; redacted, since it ends in an execution report. */
	public String describe()
	{
		final StringBuilder sb = new StringBuilder();
		for (final String detail : this.details)
		{
			if (sb.length() > 0)
			{
				sb.append("; ");
			}
			sb.append(detail);
		}
		return Redaction.redact(sb.toString());
	}

	/**
	 * What the operator of a scheduled task can do about the causes that failed it: the property to
	 * raise for a truncated read, the property that accepts each cause.
	 *
	 * @param pMaxProperty the name of the task property that bounds the read
	 */
	public String remedy(final Set<Cause> pFailing, final String pMaxProperty, final String pTolerateProperty)
	{
		final StringBuilder sb = new StringBuilder();
		if (pFailing.contains(Cause.TRUNCATED))
		{
			sb.append("Raise the task parameter '").append(pMaxProperty).append("' so that one run takes everything the source holds. ");
		}
		final StringBuilder keys = new StringBuilder();
		for (final Cause cause : pFailing)
		{
			keys.append(keys.length() > 0 ? "," : "").append(cause.key());
		}
		sb.append("To let the execution succeed in spite of this, set '").append(pTolerateProperty).append("' to ").append(keys)
			.append(" (or 'all').");
		return sb.toString();
	}

	/**
	 * Reads a {@code tolerate} property: a comma-separated list of cause names ({@code errors},
	 * {@code rejected}, {@code conflicts}, {@code truncated}, {@code stopped}), {@code all}, or
	 * nothing / {@code none}. {@code true} is read as {@code all} and {@code false} as none, for
	 * whoever thinks of it as a switch.
	 *
	 * @throws IllegalArgumentException for a name that is not a cause: a misspelt tolerance must not
	 *         silently tolerate nothing, nor everything
	 */
	public static Set<Cause> tolerated(final String pValue)
	{
		final Set<Cause> out = EnumSet.noneOf(Cause.class);
		if (pValue == null || pValue.trim().isEmpty())
		{
			return out;
		}
		for (final String raw : pValue.split("[,;\\s]+"))
		{
			final String token = raw.trim().toLowerCase(Locale.ROOT);
			if (token.isEmpty() || "none".equals(token) || "false".equals(token))
			{
				continue;
			}
			if ("all".equals(token) || "true".equals(token))
			{
				return EnumSet.allOf(Cause.class);
			}
			Cause found = null;
			for (final Cause cause : Cause.values())
			{
				if (cause.key().equals(token))
				{
					found = cause;
				}
			}
			if (found == null)
			{
				throw new IllegalArgumentException("Unknown value '" + raw.trim() + "': expected all, none or a list among errors, rejected, conflicts, truncated, stopped");
			}
			out.add(found);
		}
		return out;
	}

	/**
	 * The message a scheduled execution fails with, or null when it may succeed: the causes that are
	 * not tolerated, what they are, and what the operator can change.
	 */
	public String failureMessage(final String pRunId, final Set<Cause> pTolerated, final String pMaxProperty, final String pTolerateProperty)
	{
		final Set<Cause> failing = this.failing(pTolerated);
		if (failing.isEmpty())
		{
			return null;
		}
		return "Run " + pRunId + " is not a success: " + this.describe() + ". " + this.remedy(failing, pMaxProperty, pTolerateProperty);
	}

	/** {@code "errors,truncated"}: the causes as a workflow can route on them; empty for a clean run. */
	public String keys()
	{
		final StringBuilder sb = new StringBuilder();
		for (final Cause cause : this.causes)
		{
			sb.append(sb.length() > 0 ? "," : "").append(cause.key());
		}
		return sb.toString();
	}

	/** The causes, for a caller that exposes them as they are. */
	public Set<Cause> getCauses()
	{
		return Collections.unmodifiableSet(this.causes);
	}

	private static String firstMessage(final List<SyncResult> pErrors)
	{
		return pErrors == null || pErrors.isEmpty() ? "" : RunVerdict.quoted(pErrors.get(0).getMessage());
	}

	private static String quoted(final String pMessage)
	{
		if (pMessage == null || pMessage.trim().isEmpty())
		{
			return "";
		}
		final String text = pMessage.trim();
		return " (first: " + (text.length() > 300 ? text.substring(0, 297) + "..." : text) + ")";
	}
}
