package com.on.ebx.connect.core;

/**
 * Immutable, server-bounded request for a connector data preview.
 *
 * Callers may ask for smaller limits, but cannot raise the safety ceilings. This makes the
 * object safe to pass across UI, REST and MCP boundaries without trusting client parameters.
 */
public final class PreviewRequest
{
	public static final int DEFAULT_RECORD_LIMIT = 25;
	public static final int MAX_RECORD_LIMIT = 100;
	public static final long DEFAULT_TIMEOUT_MILLIS = 5000L;
	public static final long MAX_TIMEOUT_MILLIS = 15000L;
	public static final int DEFAULT_PAYLOAD_BYTES = 256 * 1024;
	public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

	private final int recordLimit;
	private final long timeoutMillis;
	private final int payloadByteLimit;

	private PreviewRequest(final int pRecordLimit, final long pTimeoutMillis, final int pPayloadByteLimit)
	{
		this.recordLimit = pRecordLimit;
		this.timeoutMillis = pTimeoutMillis;
		this.payloadByteLimit = pPayloadByteLimit;
	}

	/** Creates a request using conservative defaults. */
	public static PreviewRequest defaults()
	{
		return new PreviewRequest(DEFAULT_RECORD_LIMIT, DEFAULT_TIMEOUT_MILLIS, DEFAULT_PAYLOAD_BYTES);
	}

	/**
	 * Creates a request bounded by the module's hard ceilings. Non-positive values select the
	 * corresponding default, which also makes omitted or malformed optional parameters harmless.
	 */
	public static PreviewRequest bounded(final int pRecordLimit, final long pTimeoutMillis,
		final int pPayloadByteLimit)
	{
		return new PreviewRequest(
			bound(pRecordLimit, DEFAULT_RECORD_LIMIT, MAX_RECORD_LIMIT),
			bound(pTimeoutMillis, DEFAULT_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS),
			bound(pPayloadByteLimit, DEFAULT_PAYLOAD_BYTES, MAX_PAYLOAD_BYTES));
	}

	public int getRecordLimit() { return this.recordLimit; }
	public long getTimeoutMillis() { return this.timeoutMillis; }
	public int getPayloadByteLimit() { return this.payloadByteLimit; }

	private static int bound(final int pRequested, final int pDefault, final int pMaximum)
	{
		return pRequested <= 0 ? pDefault : Math.min(pRequested, pMaximum);
	}

	private static long bound(final long pRequested, final long pDefault, final long pMaximum)
	{
		return pRequested <= 0L ? pDefault : Math.min(pRequested, pMaximum);
	}
}
