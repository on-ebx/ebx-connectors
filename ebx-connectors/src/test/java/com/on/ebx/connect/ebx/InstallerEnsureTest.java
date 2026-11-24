package com.on.ebx.connect.ebx;

/**
 * C2-12: an incomplete installation is retried by one run at a time, never behind a lock, and after a failure at most
 * once a minute; without the module's own installation session nothing runs and no caller's session is used.
 */
public final class InstallerEnsureTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final InstallGate gate = new InstallGate();
		check(gate.tryBegin(1000L, false), "the first attempt runs");
		check(gate.isRunning() && !gate.tryBegin(1001L, false) && !gate.tryBegin(1002L, true), "while one runs, the others return at once, the start of the module included");
		gate.end(false, 2000L);
		check(!gate.tryBegin(2000L + InstallGate.RETRY_AFTER_MS - 1L, false), "after a failure no new attempt inside the minute");
		check(gate.isWaiting(2500L), "and the gate says it is waiting");
		check(gate.tryBegin(2000L + InstallGate.RETRY_AFTER_MS, false), "a minute later it tries again");
		gate.end(true, 70000L);
		check(gate.tryBegin(70001L, false) && !gate.isWaiting(70001L), "a success clears the waiting period");
		gate.end(false, 70002L);
		check(gate.tryBegin(70003L, true), "the start of the module tries once whatever happened before");
		gate.end(true, 70004L);

		// concurrent callers: exactly one wins, nobody waits
		final InstallGate shared = new InstallGate();
		final int[] winners = new int[1];
		final Thread[] threads = new Thread[16];
		for (int i = 0; i < threads.length; i++)
		{
			threads[i] = new Thread(new Runnable()
			{
				@Override
				public void run()
				{
					if (shared.tryBegin(5L, false))
					{
						synchronized (winners)
						{
							winners[0]++;
						}
					}
				}
			});
		}
		for (final Thread t : threads)
		{
			t.start();
		}
		for (final Thread t : threads)
		{
			t.join(5000L);
		}
		check(winners[0] == 1, "one winner among concurrent callers: " + winners[0]);

		// without the module's installation session, ensure runs nothing (a null session of a caller is not used instead)
		ConnectorsInstaller.forgetInstallSession();
		final long before = System.nanoTime();
		ConnectorsInstaller.ensure(null, null);
		check(!ConnectorsInstaller.isInstallComplete(), "nothing installed without the module's own session");
		check(System.nanoTime() - before < 2000000000L, "and the call returned at once");
		System.out.println("PASS: one installation run at a time, a minute between failed attempts, no run without the module's own session");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
