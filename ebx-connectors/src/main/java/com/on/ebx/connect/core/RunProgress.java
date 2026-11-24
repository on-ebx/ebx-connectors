package com.on.ebx.connect.core;

import java.util.Date;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a screen shows of a feed run it watches (audit C-19): read from the {@code execution} object of the run status the
 * module already answers ({@code connectors_run_get}, {@code GET /runs/{id}}), so the screen and the agents agree on
 * the same state. Pure: {@code RunProgressTest}.
 */
public final class RunProgress
{
	public static final String RUNNING = "RUNNING";
	public static final String DONE = "DONE";
	public static final String PARTIAL = "PARTIAL";
	public static final String ERROR = "ERROR";
	public static final String STOPPED = "STOPPED";
	public static final String INTERRUPTED = "INTERRUPTED";
	/** The status of a run whose row is not (yet) readable. */
	public static final String UNKNOWN = "UNKNOWN";

	private final String status;
	private final int created;
	private final int updated;
	private final int noChange;
	private final int errors;
	private final int skipped;
	private final int rejected;
	private final int nextIndex;
	private final boolean truncated;
	private final int available;
	private final String message;
	private final String firstErrors;
	private final Date startedAt;
	private final Date updatedAt;

	private RunProgress(final JsonNode pExecution)
	{
		final JsonNode e = pExecution;
		this.status = e == null || !e.hasNonNull("status") ? RunProgress.UNKNOWN : e.get("status").asText();
		this.created = e == null ? 0 : e.path("created").asInt(0);
		this.updated = e == null ? 0 : e.path("updated").asInt(0);
		this.noChange = e == null ? 0 : e.path("noChange").asInt(0);
		this.errors = e == null ? 0 : e.path("errorCount").asInt(0);
		this.skipped = e == null ? 0 : e.path("skipped").asInt(0);
		this.rejected = e == null ? 0 : e.path("rejected").asInt(0);
		this.nextIndex = e == null ? 0 : e.path("nextIndex").asInt(0);
		this.truncated = e != null && e.path("truncated").asBoolean(false);
		this.available = e == null ? 0 : e.path("available").asInt(0);
		this.message = e == null ? "" : e.path("message").asText("");
		this.firstErrors = e == null ? "" : e.path("firstErrors").asText("");
		this.startedAt = RunProgress.parse(e == null ? null : e.path("startedAt").asText(null));
		this.updatedAt = RunProgress.parse(e == null ? null : e.path("updatedAt").asText(null));
	}

	/** The progress of a run from its {@code execution} object; null (no such run row) reads as {@link #UNKNOWN}. */
	public static RunProgress of(final JsonNode pExecution)
	{
		return new RunProgress(pExecution);
	}

	public String getStatus()
	{
		return this.status;
	}

	public boolean isRunning()
	{
		return RunProgress.RUNNING.equals(this.status);
	}

	/** True once the run reached an end the engine wrote: a run whose row is unknown is not terminal, it may not have started to write. */
	public boolean isTerminal()
	{
		return !this.isRunning() && !RunProgress.UNKNOWN.equals(this.status);
	}

	/**
	 * Whether the run can be continued from where it stopped: it ended before the end of the source (stopped, interrupted, or failed
	 * after some items) and saved a position. The engine restarts at that item index; the source must return its items in the
	 * same order for the continuation to be exact.
	 */
	public boolean isResumable()
	{
		return (RunProgress.STOPPED.equals(this.status) || RunProgress.INTERRUPTED.equals(this.status) || RunProgress.ERROR.equals(this.status)) && this.nextIndex > 0;
	}

	/** Items looked at so far, whatever became of them. */
	public int getProcessed()
	{
		return this.created + this.updated + this.noChange + this.errors + this.skipped + this.rejected;
	}

	public int getCreated()
	{
		return this.created;
	}

	public int getUpdated()
	{
		return this.updated;
	}

	public int getNoChange()
	{
		return this.noChange;
	}

	public int getErrors()
	{
		return this.errors;
	}

	public int getSkipped()
	{
		return this.skipped;
	}

	public int getRejected()
	{
		return this.rejected;
	}

	/** The item index a continuation restarts at; 0 when nothing was saved. */
	public int getNextIndex()
	{
		return this.nextIndex;
	}

	/** True when the source had more items than the maximum of the run. */
	public boolean isTruncated()
	{
		return this.truncated;
	}

	public int getAvailable()
	{
		return this.available;
	}

	public String getMessage()
	{
		return this.message;
	}

	public String getFirstErrors()
	{
		return this.firstErrors;
	}

	/** Seconds the run took (until its last save when it ended), 0 when its start is unknown. */
	public long elapsedSeconds(final Date pNow)
	{
		if (this.startedAt == null)
		{
			return 0L;
		}
		final Date end = this.isRunning() || this.updatedAt == null ? pNow : this.updatedAt;
		return Math.max(0L, (end.getTime() - this.startedAt.getTime()) / 1000L);
	}

	private static Date parse(final String pText)
	{
		if (pText == null || pText.isEmpty())
		{
			return null;
		}
		try
		{
			return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(pText);
		}
		catch (final java.text.ParseException ex)
		{
			return null;
		}
	}
}
