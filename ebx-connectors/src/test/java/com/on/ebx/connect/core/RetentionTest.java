package com.on.ebx.connect.core;

import java.util.Date;
import java.util.Properties;

/**
 * The retention policy on its own: what the properties turn on, which rows a table lets go of, and
 * how a pass is cut into batches. The deletions themselves, the age predicate as EBX reads it and
 * the cost of a pass on a large table need a repository and are not run here.
 */
public final class RetentionTest
{
	private static final long DAY = 86400000L;

	public static void main(final String[] pArgs) throws Exception
	{
		// on by default, at 90 days (owner decision of 2026-10-06); 0 turns the whole purge off
		final Retention.Policy unset = new Retention.Policy(new Properties());
		check(unset.isEnabled() && unset.days == 90 && Retention.DEFAULT_DAYS == 90, "unset: the purge is on at 90 days: " + unset.describe());
		check(unset.targets.size() == 5 && unset.provenanceDays == 0, "unset: the four tables, and not the two opt-in ones: " + unset.describe());
		check(!policy("ebx.connectors.log.retentionDays", "0").isEnabled(), "0: nothing is deleted");
		check(!policy("ebx.connectors.log.retentionDays", "-3").isEnabled(), "negative: nothing is deleted");
		final Retention.Policy typo = policy("ebx.connectors.log.retentionDays", "soon");
		check(!typo.isEnabled() && typo.unreadable.size() == 1 && typo.describe().contains("soon"), "not a number: nothing is deleted, and it is said: " + typo.describe());
		check(policy("ebx.connectors.log.retentionDays", " ").days == 90, "blank is unset");
		check(!policy("ebx.connectors.log.retentionDays", "0", "ebx.connectors.retention.syncLogDays", "30").isEnabled(), "a per-table age alone does not turn a purge that is off back on");

		// a general age: the four tables follow it
		final Retention.Policy general = policy("ebx.connectors.log.retentionDays", "90");
		check(general.isEnabled() && general.targets.size() == 5, "four tables: " + general.describe());
		check("SyncLog".equals(general.targets.get(0).name) && "Conflict".equals(general.targets.get(1).name) && "ExecutionRun".equals(general.targets.get(2).name)
			&& "Publication".equals(general.targets.get(3).name) && "OutboundQueue".equals(general.targets.get(4).name), "in this order: " + general.describe());
		for (final Retention.Target target : general.targets)
		{
			check(target.days == 90, target.name + " at the general age");
		}
		check(general.batchSize == 500 && general.maxRowsPerPass == 50000 && general.intervalMinutes == 60, "defaults: " + general.describe());

		// the two tables that need their own switch: deleted outbound states, and provenance of records that are gone
		boolean found = false;
		for (final Retention.Target t : policy("ebx.connectors.log.retentionDays", "30").targets)
		{
			if ("OutboundQueue".equals(t.name))
			{
				found = true;
				final java.util.Date now = new java.util.Date();
				final java.util.Date old = new java.util.Date(now.getTime() - 31 * 86400000L);
				if (!t.mayDelete(old, "DEAD", now) || t.mayDelete(old, "PENDING", now) || t.mayDelete(now, "DEAD", now) || t.mayDelete(old, null, now)
					|| !t.predicate(now).contains("./status='DEAD'"))
				{
					throw new AssertionError("OutboundQueue purge: only DEAD rows older than the retention");
				}
			}
		}
		if (!found)
		{
			throw new AssertionError("OutboundQueue is a retention target");
		}
		final Retention.Policy optIn = policy("ebx.connectors.log.retentionDays", "90", "ebx.connectors.retention.outboundStateDays", "45", "ebx.connectors.retention.provenanceDays", "120");
		check(optIn.targets.size() == 6 && "OutboundState".equals(optIn.targets.get(4).name) && optIn.targets.get(4).days == 45, "outbound states at their own age: " + optIn.describe());
		check(optIn.provenanceDays == 120 && optIn.describe().contains("FieldProvenance of records that no longer exist after 120"), "provenance at its own age: " + optIn.describe());
		check(policy("ebx.connectors.log.retentionDays", "0", "ebx.connectors.retention.provenanceDays", "120").provenanceDays == 0, "off is off for them too");
		check(policy("ebx.connectors.retention.provenanceDays", "x").unreadable.size() == 1 && policy("ebx.connectors.retention.provenanceDays", "x").provenanceDays == 0, "an unreadable age keeps the provenance");
		final Retention.Policy onlyProvenance = policy("ebx.connectors.retention.syncLogDays", "0", "ebx.connectors.retention.conflictDays", "0", "ebx.connectors.retention.executionRunDays", "0",
			"ebx.connectors.retention.publicationDays", "0", "ebx.connectors.retention.outboundQueueDays", "0", "ebx.connectors.retention.provenanceDays", "30");
		check(onlyProvenance.targets.isEmpty() && onlyProvenance.isEnabled(), "provenance alone is a purge");

		// per table: its own age, or 0 to keep it for ever; an age that cannot be read keeps the table too
		final Retention.Policy perTable = policy("ebx.connectors.log.retentionDays", "90", "ebx.connectors.retention.syncLogDays", "30",
			"ebx.connectors.retention.publicationDays", "0", "ebx.connectors.retention.conflictDays", "3 65");
		check(perTable.targets.size() == 3, "a table at 0 and a table with an unreadable age are kept: " + perTable.describe());
		check("SyncLog".equals(perTable.targets.get(0).name) && perTable.targets.get(0).days == 30, "own age: " + perTable.describe());
		check("ExecutionRun".equals(perTable.targets.get(1).name) && perTable.targets.get(1).days == 90, "else the general one: " + perTable.describe());
		check("OutboundQueue".equals(perTable.targets.get(2).name), "the queue follows the general age: " + perTable.describe());
		check(perTable.unreadable.size() == 1 && perTable.describe().contains("conflictDays=3 65"), "the unreadable age is said: " + perTable.describe());

		// bounds: a batch is never empty nor huge, a pass takes at least one batch
		final Retention.Policy bounded = policy("ebx.connectors.log.retentionDays", "1", "ebx.connectors.retention.batchSize", "1000000",
			"ebx.connectors.retention.maxRowsPerPass", "10", "ebx.connectors.retention.intervalMinutes", "0");
		check(bounded.batchSize == Retention.MAX_BATCH_SIZE && bounded.maxRowsPerPass == Retention.MAX_BATCH_SIZE && bounded.intervalMinutes == 1, "clamped: " + bounded.describe());
		check(policy("ebx.connectors.log.retentionDays", "1", "ebx.connectors.retention.batchSize", "0").batchSize == 1, "a batch holds at least one row");

		// the batches of one pass over one table
		final Retention.Policy small = policy("ebx.connectors.log.retentionDays", "1", "ebx.connectors.retention.batchSize", "100", "ebx.connectors.retention.maxRowsPerPass", "250");
		check(small.nextBatch(0, -1) == 100, "the first batch is a full one");
		check(small.nextBatch(100, 100) == 100, "a full batch calls for another");
		check(small.nextBatch(200, 100) == 50, "the last one is cut to what the pass may still delete");
		check(small.nextBatch(250, 50) == 0, "a short batch ends the table for this pass");
		check(small.nextBatch(250, 100) == 0, "so does the limit of the pass");
		check(small.nextBatch(37, 37) == 0, "fewer rows than a batch: nothing old is left");
		int deleted = 0;
		int batches = 0;
		int last = -1;
		for (int size = small.nextBatch(deleted, last); size > 0; size = small.nextBatch(deleted, last))
		{
			deleted += size;
			last = size;
			batches++;
		}
		check(deleted == 250 && batches == 3, "a backlog larger than the pass is taken in 3 transactions and stops at 250: " + deleted + " in " + batches);

		// which rows go: old enough, and never an open conflict, a running run or a row without a date or a status
		final Date now = new Date(1790000000000L);
		final Date old = new Date(now.getTime() - 91 * DAY);
		final Date young = new Date(now.getTime() - 89 * DAY);
		final Retention.Target log = general.targets.get(0);
		check(log.mayDelete(old, null, now) && !log.mayDelete(young, null, now) && !log.mayDelete(null, null, now), "a log line goes by age only");
		check(!log.mayDelete(log.limit(now), null, now), "a row exactly at the limit is kept");
		final Retention.Target conflict = general.targets.get(1);
		check(conflict.mayDelete(old, "ACCEPTED", now) && conflict.mayDelete(old, "REJECTED", now), "a decided conflict goes");
		check(!conflict.mayDelete(old, "OPEN", now) && !conflict.mayDelete(old, null, now), "an open conflict never does, however old");
		check(!conflict.mayDelete(old, "APPLYING", now), "nor does a conflict whose acceptance did not finish");
		final Retention.Target run = general.targets.get(2);
		for (final String status : new String[] { "DONE", "PARTIAL", "ERROR", "STOPPED", "INTERRUPTED" })
		{
			check(run.mayDelete(old, status, now), "a finished run goes: " + status);
		}
		check(!run.mayDelete(old, "RUNNING", now) && !run.mayDelete(old, null, now), "a running run, or a row without status, never does");
		check(!run.mayDelete(young, "DONE", now), "a recent finished run is kept");

		// the predicate EBX is given says the same thing
		final String logPredicate = log.predicate(now);
		check(logPredicate.equals("date-less-than(./timestamp,'" + Retention.dateTime(log.limit(now)) + "')"), "log predicate: " + logPredicate);
		check(Retention.dateTime(log.limit(now)).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"), "an EBX dateTime literal: " + Retention.dateTime(log.limit(now)));
		check(conflict.predicate(now).endsWith(" and (./status!='OPEN' and ./status!='APPLYING')") && conflict.predicate(now).startsWith("date-less-than(./detectedAt,"), "conflict predicate: " + conflict.predicate(now));
		check(run.predicate(now).contains("date-less-than(./updatedAt,") && run.predicate(now).contains("./status='DONE' or ./status='PARTIAL'")
			&& !run.predicate(now).contains("RUNNING"), "run predicate: " + run.predicate(now));
		check(general.targets.get(3).predicate(now).startsWith("date-less-than(./endedAt,"), "publication predicate: " + general.targets.get(3).predicate(now));

		// outbound states: only a DELETED one goes, a SENT or FAILED state of a live record never does
		final Retention.Target outbound = optIn.targets.get(4);
		final Date oldState = new Date(now.getTime() - 46 * DAY);
		check(outbound.mayDelete(oldState, "DELETED", now), "an old DELETED state goes");
		check(!outbound.mayDelete(oldState, "SENT", now) && !outbound.mayDelete(oldState, "FAILED", now) && !outbound.mayDelete(oldState, null, now), "SENT, FAILED or no status never");
		check(!outbound.mayDelete(new Date(now.getTime() - 44 * DAY), "DELETED", now), "a recent DELETED state is kept");
		check(outbound.predicate(now).contains("date-less-than(./lastAttemptAt,") && outbound.predicate(now).endsWith(" and (./status='DELETED')"), "outbound predicate: " + outbound.predicate(now));

		// provenance: a row goes only when it is old AND its record is certainly gone
		final Date limit = new Date(now.getTime() - 120 * DAY);
		final Date oldRow = new Date(limit.getTime() - DAY);
		final Date youngRow = new Date(limit.getTime() + DAY);
		check(Retention.mayDeleteProvenance(oldRow, limit, Retention.Existence.ABSENT) && Retention.mayDeleteProvenance(oldRow, limit, Retention.Existence.HOME_GONE), "old and gone: deleted");
		check(!Retention.mayDeleteProvenance(oldRow, limit, Retention.Existence.PRESENT), "the record exists: kept, however old");
		check(!Retention.mayDeleteProvenance(oldRow, limit, Retention.Existence.UNKNOWN), "cannot be told: kept");
		check(!Retention.mayDeleteProvenance(youngRow, limit, Retention.Existence.ABSENT) && !Retention.mayDeleteProvenance(limit, limit, Retention.Existence.ABSENT)
			&& !Retention.mayDeleteProvenance(null, limit, Retention.Existence.ABSENT), "gone but not old enough, or no date: kept");
		// where the next pass resumes: it passes over what it kept, and starts again at the end of the table
		check(Retention.nextSkip(0, 1000, 400, false) == 600, "600 kept rows are passed over next time");
		check(Retention.nextSkip(600, 1000, 1000, false) == 600, "every row examined went: still 600 before the unread ones");
		check(Retention.nextSkip(600, 300, 0, true) == 0, "the end of the table: back to the start");
		check(Retention.nextSkip(5, 0, 0, false) == 5 && Retention.nextSkip(0, 3, 9, false) == 0, "never negative");

		System.out.println("PASS: retention is on by default (90 days, 0 = off); per-table ages, opt-in outbound states and provenance of gone records; bounded batches per pass; open conflicts and running runs are never deleted");
	}

	private static Retention.Policy policy(final String... pKeyValues)
	{
		final Properties properties = new Properties();
		for (int i = 0; i < pKeyValues.length; i += 2)
		{
			properties.setProperty(pKeyValues[i], pKeyValues[i + 1]);
		}
		return new Retention.Policy(properties);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
