package com.on.ebx.connect.core;

/**
 * Per-thread tally of the HTTP retries {@link HttpJsonClient} made, drained into the connector's
 * operational metrics each time an execution result is observed. A connector call and the result it
 * produces run on the same thread, so the retries land on the right connector without threading a
 * counter through every connector's API. A retry on 429, or on 503 carrying {@code Retry-After}, is
 * also a throttle.
 */
public final class HttpExchangeStats
{
	private static final ThreadLocal<long[]> COUNTS = new ThreadLocal<long[]>()
	{
		@Override protected long[] initialValue() { return new long[2]; }
	};

	private HttpExchangeStats() { }

	static void retried(final int pStatus, final String pRetryAfter)
	{
		final long[] counts = COUNTS.get();
		counts[0]++;
		if (pStatus == 429 || pStatus == 503 && pRetryAfter != null && !pRetryAfter.trim().isEmpty()) { counts[1]++; }
	}

	/** Returns {retries, throttles} since the last drain on this thread and resets them. */
	public static long[] drain()
	{
		final long[] counts = COUNTS.get();
		final long[] out = { counts[0], counts[1] };
		counts[0] = 0L;
		counts[1] = 0L;
		return out;
	}
}
