package com.on.ebx.connect.ebx;

/**
 * Who may start an installation attempt, and when (audit C-2-12). Until an installation has run to its end, every
 * entry point of the module (each tool, route and trigger) asks the installer to make sure; without a gate each of
 * those calls would start a full run, one after the other, behind a global lock. The gate lets ONE run go at a time,
 * without blocking the others (they return at once), and after a failed run it refuses a new attempt for a minute:
 * the failure is in the kernel log, retrying it on every request only slows the module down.
 */
final class InstallGate
{
	/** How long after a failed run the next attempt of {@link #tryBegin} is refused. */
	static final long RETRY_AFTER_MS = 60000L;

	private boolean running;
	private long failedAt = Long.MIN_VALUE;

	/**
	 * True when the caller may run the installation now, and must then call {@link #end}. False while another run is
	 * in progress; false after a failure until {@link #RETRY_AFTER_MS} has passed, unless {@code pForce} (the start of
	 * the module, which always tries once).
	 */
	synchronized boolean tryBegin(final long pNowMs, final boolean pForce)
	{
		if (this.running)
		{
			return false;
		}
		if (!pForce && this.failedAt != Long.MIN_VALUE && pNowMs - this.failedAt < InstallGate.RETRY_AFTER_MS)
		{
			return false;
		}
		this.running = true;
		return true;
	}

	/** Ends the run started by {@link #tryBegin}; a failure starts the waiting period, a success clears it. */
	synchronized void end(final boolean pSucceeded, final long pNowMs)
	{
		this.running = false;
		this.failedAt = pSucceeded ? Long.MIN_VALUE : pNowMs;
	}

	synchronized boolean isRunning()
	{
		return this.running;
	}

	/** True when the last run failed less than {@link #RETRY_AFTER_MS} ago: the next {@link #tryBegin} says no. */
	synchronized boolean isWaiting(final long pNowMs)
	{
		return this.failedAt != Long.MIN_VALUE && pNowMs - this.failedAt < InstallGate.RETRY_AFTER_MS;
	}
}
