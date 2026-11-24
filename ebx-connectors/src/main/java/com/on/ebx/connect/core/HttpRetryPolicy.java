package com.on.ebx.connect.core;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Bounded retry policy for idempotent HTTP requests. */
public final class HttpRetryPolicy
{
	public interface Sleeper { void sleep(long pMilliseconds) throws InterruptedException; }
	static final Sleeper THREAD_SLEEPER = new Sleeper()
	{
		public void sleep(final long pMilliseconds) throws InterruptedException
		{
			Thread.sleep(pMilliseconds);
		}
	};

	private final int maxAttempts;
	private final long baseDelayMs;
	private final long maxDelayMs;
	private final Sleeper sleeper;

	public HttpRetryPolicy(final int pMaxAttempts, final long pBaseDelayMs, final long pMaxDelayMs)
	{
		this(pMaxAttempts, pBaseDelayMs, pMaxDelayMs, THREAD_SLEEPER);
	}

	HttpRetryPolicy(final int pMaxAttempts, final long pBaseDelayMs, final long pMaxDelayMs, final Sleeper pSleeper)
	{
		if (pMaxAttempts < 1 || pBaseDelayMs < 0L || pMaxDelayMs < pBaseDelayMs) throw new IllegalArgumentException("Invalid retry policy");
		this.maxAttempts = pMaxAttempts;
		this.baseDelayMs = pBaseDelayMs;
		this.maxDelayMs = pMaxDelayMs;
		this.sleeper = pSleeper;
	}

	public static HttpRetryPolicy defaults() { return new HttpRetryPolicy(2, 2000L, 30000L); }
	public int getMaxAttempts() { return this.maxAttempts; }

	public boolean canRetry(final String pMethod, final int pStatus, final int pAttempt)
	{
		return pAttempt < this.maxAttempts && isIdempotent(pMethod)
			&& (pStatus == 408 || pStatus == 425 || pStatus == 429 || pStatus >= 500 && pStatus <= 504);
	}

	public void pause(final int pAttempt, final String pRetryAfter) throws InterruptedException
	{
		this.sleeper.sleep(delayMillis(pAttempt, pRetryAfter, System.currentTimeMillis()));
	}

	long delayMillis(final int pAttempt, final String pRetryAfter, final long pNow)
	{
		long delay = parseRetryAfter(pRetryAfter, pNow);
		if (delay < 0L)
		{
			delay = this.baseDelayMs;
			for (int i = 1; i < pAttempt && delay < this.maxDelayMs; i++) delay = Math.min(this.maxDelayMs, delay * 2L);
		}
		return Math.min(this.maxDelayMs, Math.max(0L, delay));
	}

	static boolean isIdempotent(final String pMethod)
	{
		return "GET".equalsIgnoreCase(pMethod) || "HEAD".equalsIgnoreCase(pMethod)
			|| "PUT".equalsIgnoreCase(pMethod) || "DELETE".equalsIgnoreCase(pMethod)
			|| "OPTIONS".equalsIgnoreCase(pMethod) || "TRACE".equalsIgnoreCase(pMethod);
	}

	static long parseRetryAfter(final String pValue, final long pNow)
	{
		if (pValue == null || pValue.trim().isEmpty()) return -1L;
		try { return Math.max(0L, Long.parseLong(pValue.trim()) * 1000L); }
		catch (final NumberFormatException ignored) { }
		final SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
		format.setTimeZone(TimeZone.getTimeZone("GMT"));
		try
		{
			final Date date = format.parse(pValue.trim());
			return Math.max(0L, date.getTime() - pNow);
		}
		catch (final ParseException ignored) { return -1L; }
	}
}
