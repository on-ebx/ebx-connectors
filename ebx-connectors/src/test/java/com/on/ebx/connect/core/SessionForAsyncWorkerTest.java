package com.on.ebx.connect.core;

/**
 * Volume lot 7b: {@code ConnectorService.sessionForAsyncWorker}'s pure guard clauses - a null caller
 * session or a null repository is returned as-is, never NPEs. The actual minting path
 * ({@code com.onwbp.adaptation.SessionImpl.createSession}) needs a real EBX {@code Session}/
 * {@code Repository}/{@code UserReference}, none of which this offline test suite constructs
 * elsewhere (see docs/REFERENCE.md - this route is explicitly "not verified live"); this test covers
 * only what is safely exercisable without an EBX repository.
 */
public final class SessionForAsyncWorkerTest
{
	public static void main(final String[] args) throws Exception
	{
		check(ConnectorService.sessionForAsyncWorker(null, null) == null, "a null caller session returns null, never NPEs");
		System.out.println("PASS: sessionForAsyncWorker's null guards (live minting path not verified - see docs/REFERENCE.md)");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
