package com.on.ebx.connect.core;

/**
 * Volume lot 7b: {@code ConnectorService.notProcessedRemainder} - the exact remainder a stopped
 * (circuit-breaker-tripped or cancelled) run reports when the source gave an availability hint,
 * the nominal non-zero 1 otherwise. Pure, no EBX type, no connector.
 */
public final class NotProcessedRemainderTest
{
	public static void main(final String[] args)
	{
		check(ConnectorService.notProcessedRemainder(-1, 42) == 1, "no availability hint: nominal 1");
		check(ConnectorService.notProcessedRemainder(100, 60) == 40, "exact remainder when available is known: " + ConnectorService.notProcessedRemainder(100, 60));
		check(ConnectorService.notProcessedRemainder(100, 100) == 1, "processed caught up to available: floored at 1, never 0");
		check(ConnectorService.notProcessedRemainder(100, 130) == 1, "processed somehow past available (resumeFrom overlap): floored at 1, never negative");
		check(ConnectorService.notProcessedRemainder(0, 0) == 1, "available reported as 0: still a nominal 1 (the run WAS stopped)");

		System.out.println("PASS: not-processed remainder (exact when available is known, nominal 1 otherwise, never <= 0)");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
