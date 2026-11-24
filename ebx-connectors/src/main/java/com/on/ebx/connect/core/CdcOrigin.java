package com.on.ebx.connect.core;

/**
 * Real-time mastering (docs/REALTIME-MASTERING.md): which connector's CDC batch the current thread is writing, so an
 * outbound trigger with {@code skipOrigin} does not send a change back to the system it came from. The import and delete
 * procedures of a cdc run execute on the run's own thread, and EBX runs table triggers inside them, on that thread.
 */
public final class CdcOrigin
{
	private static final ThreadLocal<String> CONNECTOR = new ThreadLocal<String>();

	private CdcOrigin()
	{
	}

	/** The connector id whose CDC batch this thread is applying, or null. */
	public static String current()
	{
		return CdcOrigin.CONNECTOR.get();
	}

	/** Runs the writes with {@code pConnector} as the origin; restores the previous value after. */
	public static void run(final String pConnector, final CdcMatching.Writes pWrites) throws Exception
	{
		final String previous = CdcOrigin.CONNECTOR.get();
		CdcOrigin.CONNECTOR.set(pConnector);
		try
		{
			pWrites.run();
		}
		finally
		{
			if (previous == null)
				CdcOrigin.CONNECTOR.remove();
			else
				CdcOrigin.CONNECTOR.set(previous);
		}
	}

	/**
	 * Whether a trigger configured with {@code pSkipOrigin} (comma-separated connector ids, or {@code *} for any CDC
	 * origin) must not queue a write made with origin {@code pOrigin}. A blank list never skips.
	 */
	public static boolean skips(final String pSkipOrigin, final String pOrigin)
	{
		if (pOrigin == null || pSkipOrigin == null || pSkipOrigin.trim().isEmpty())
			return false;
		for (final String item : pSkipOrigin.split(","))
		{
			final String id = item.trim();
			if ("*".equals(id) || id.equals(pOrigin))
				return true;
		}
		return false;
	}
}
