package com.on.ebx.connect.spi;

/** Immutable outcome of a read-only connector connectivity test. */
public final class ConnectionTestResult
{
	private final boolean supported;
	private final boolean successful;
	private final String message;
	private final long elapsedMillis;

	private ConnectionTestResult(final boolean pSupported, final boolean pSuccessful,
		final String pMessage, final long pElapsedMillis)
	{
		this.supported = pSupported;
		this.successful = pSuccessful;
		this.message = pMessage == null ? "" : pMessage;
		this.elapsedMillis = Math.max(0L, pElapsedMillis);
	}

	public static ConnectionTestResult success(final String pMessage, final long pElapsedMillis)
	{
		return new ConnectionTestResult(true, true, pMessage, pElapsedMillis);
	}

	public static ConnectionTestResult failure(final String pMessage, final long pElapsedMillis)
	{
		return new ConnectionTestResult(true, false, pMessage, pElapsedMillis);
	}

	public static ConnectionTestResult unsupported(final String pMessage)
	{
		return new ConnectionTestResult(false, false, pMessage, 0L);
	}

	public boolean isSupported() { return this.supported; }
	public boolean isSuccessful() { return this.successful; }
	public String getMessage() { return this.message; }
	public long getElapsedMillis() { return this.elapsedMillis; }
}
