package com.on.ebx.connect.core;

/**
 * Volume lot 6, task 2: stops a batched import after too many consecutive batches that failed
 * entirely (every item that needed a write in that batch ended up an error). A batch with nothing
 * to write, or with at least one successful write, resets the streak - only a run of batches with
 * ZERO successful writes trips it. Pure counter, no EBX dependency.
 */
public final class CircuitBreaker
{
	private final int threshold;
	private int consecutiveFailures;
	private boolean tripped;

	public CircuitBreaker(final int pThreshold)
	{
		if (pThreshold < 1)
		{
			throw new IllegalArgumentException("Threshold must be at least 1");
		}
		this.threshold = pThreshold;
	}

	/** Records the outcome of one batch: true when every item that needed a write in it failed. */
	public void record(final boolean pBatchFullyFailed)
	{
		if (this.tripped)
		{
			return;
		}
		if (pBatchFullyFailed)
		{
			this.consecutiveFailures++;
			if (this.consecutiveFailures >= this.threshold)
			{
				this.tripped = true;
			}
		}
		else
		{
			this.consecutiveFailures = 0;
		}
	}

	public boolean isTripped()
	{
		return this.tripped;
	}

	public int getConsecutiveFailures()
	{
		return this.consecutiveFailures;
	}
}
