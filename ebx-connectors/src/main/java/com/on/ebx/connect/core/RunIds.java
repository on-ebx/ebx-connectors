package com.on.ebx.connect.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the id of the run in progress on the current thread. Extracted out of
 * {@link ConnectorService} so that {@code Transformations} (and anything else in this package) can
 * read the current run id without depending on {@code ConnectorService} — which pulls in the whole
 * EBX-integration surface (secrets, HTTP, the connector registry...) and is exactly what the pure
 * mapping-engine jar (`build-mapping.sh`) must NOT reference; see docs/TARGET-MAPPING.md sec 1/5.
 * <p>
 * Semantics are unchanged from the {@code ConnectorService.CURRENT_RUN} ThreadLocal it replaces:
 * {@link #current()} returns the id set by {@link #set(String)} until {@link #clear()}, or — when
 * none was set — mints a fresh id and MEMOIZES it on this thread (a second {@link #current()} call
 * with no {@link #set}/{@link #clear} in between returns the SAME id, not a new one each time; only
 * an explicit {@link #clear()} — or a later {@link #set}} — resets it). This is what let
 * {@code ConnectorService.currentRunId()} say "a single call is its own run": the first read fixes
 * the run id for the rest of that thread's work.
 */
final class RunIds
{
	private static final ThreadLocal<String> CURRENT = new ThreadLocal<String>();
	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	private RunIds()
	{
	}

	/** Explicitly starts (or re-labels) the run in progress on this thread ({@code ConnectorService.beginRun}). */
	static void set(final String pRunId)
	{
		RunIds.CURRENT.set(pRunId);
	}

	/** Ends the run on this thread ({@code ConnectorService.endRun}): the next {@link #current()} call mints a fresh, unmemoized id. */
	static void clear()
	{
		RunIds.CURRENT.remove();
	}

	/**
	 * The id of the run in progress, or a fresh one when none was begun — memoized on this thread,
	 * see the class comment. This is what {@code runid} (Transformations) and
	 * {@code ConnectorService.currentRunId()} both call.
	 */
	static String current()
	{
		final String id = RunIds.CURRENT.get();
		if (id != null)
		{
			return id;
		}
		final String fresh = RunIds.newId();
		RunIds.CURRENT.set(fresh);
		return fresh;
	}

	/**
	 * A fresh id: {@code R} + {@code yyyyMMdd-HHmmss} + a 4-digit sequence number, unique per JVM.
	 * Same shape {@code ConnectorService.newRunId()} produced before this split — replayable through
	 * the REST API, sortable, and human-readable in a log line.
	 */
	static String newId()
	{
		return "R" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + "-"
				+ Integer.toString(RunIds.SEQUENCE.incrementAndGet() % 10000 + 10000).substring(1);
	}
}
