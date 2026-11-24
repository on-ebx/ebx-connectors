package com.on.ebx.connect.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admission of background runs: how many may be running or waiting at once.
 * <p>
 * A run that is accepted writes its {@code RUNNING} row before it is handed to a thread, so the
 * decision to accept it has to be taken first and cannot be left to the executor's queue: a run
 * refused by a full queue would already have a row, and a caller that keeps starting runs against an
 * unbounded pool gets one thread per run until the JVM has none left. A slot is therefore reserved
 * up front, kept for the whole life of the run - waiting or running - and released when it ends.
 */
final class RunSlots
{
	private final int maxConcurrent;
	private final int maxQueued;
	private final AtomicInteger inUse = new AtomicInteger();

	RunSlots(final int pMaxConcurrent, final int pMaxQueued)
	{
		this.maxConcurrent = Math.max(1, pMaxConcurrent);
		this.maxQueued = Math.max(0, pMaxQueued);
	}

	int getMaxConcurrent()
	{
		return this.maxConcurrent;
	}

	int getMaxQueued()
	{
		return this.maxQueued;
	}

	int capacity()
	{
		return this.maxConcurrent + this.maxQueued;
	}

	int inUse()
	{
		return this.inUse.get();
	}

	/** Takes a slot for a run about to be started; false when as many runs as allowed are already running or waiting. */
	boolean tryReserve()
	{
		for (;;)
		{
			final int current = this.inUse.get();
			if (current >= this.capacity())
			{
				return false;
			}
			if (this.inUse.compareAndSet(current, current + 1))
			{
				return true;
			}
		}
	}

	/** Gives back the slot of a run that ended, or that was never started after all. */
	void release()
	{
		for (;;)
		{
			final int current = this.inUse.get();
			if (current <= 0 || this.inUse.compareAndSet(current, current - 1))
			{
				return;
			}
		}
	}

	/** Frees every slot: the runs that held them were dropped with their executor. */
	void clear()
	{
		this.inUse.set(0);
	}

	/** The refusal a caller gets: what the limit is, and that waiting or raising it are the two ways out. */
	String refusal(final String pConcurrentProperty, final String pQueuedProperty)
	{
		return "Too many asynchronous runs: " + this.maxConcurrent + " may run and " + this.maxQueued + " may wait at the same time, and all "
			+ this.capacity() + " places are taken. Start this run again once one has finished (connectors_run_get shows their status), or raise -D"
			+ pConcurrentProperty + " / -D" + pQueuedProperty + ".";
	}
}
