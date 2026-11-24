package com.on.ebx.connect.core;

/** Which records an outbound run sends. */
public enum OutboundScope
{
	/** Every record of the request (the historical behaviour). */
	ALL,
	/** Records never sent successfully by this mapping. */
	UNSENT,
	/** Records whose document differs from the last one sent, plus retriable failures and deletions. */
	CHANGED,
	/** Records whose last send failed and that the retry policy allows again. */
	FAILED,
	/** Records created, modified or deleted in the dataspace since its initial snapshot. */
	DATASPACE_DELTA;

	public static OutboundScope parse(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return ALL;
		}
		final String v = pValue.trim().toUpperCase(java.util.Locale.ENGLISH).replace('-', '_');
		if ("DELTA".equals(v) || "DATASPACEDELTA".equals(v))
		{
			return DATASPACE_DELTA;
		}
		return OutboundScope.valueOf(v);
	}
}
