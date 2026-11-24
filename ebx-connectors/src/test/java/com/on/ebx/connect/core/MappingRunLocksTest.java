package com.on.ebx.connect.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** One run per mapping at a time (audit C-20): who gets the lock, who is told who holds it, and who may give it back. */
public final class MappingRunLocksTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final MappingRunLocks locks = new MappingRunLocks();
		check(locks.tryAcquire(7, "R1") == null, "a free mapping is taken");
		check("R1".equals(locks.tryAcquire(7, "R2")), "a second start is told which run holds the mapping");
		check("R1".equals(locks.tryAcquire(7, "R1")), "even the same run id does not take it twice: the caller must not nest");
		check(locks.tryAcquire(8, "R2") == null, "another mapping is independent");
		locks.release(7, "R2");
		check("R1".equals(locks.holder(7)), "a release by a run that does not hold the mapping changes nothing");
		locks.release(7, "R1");
		check(locks.holder(7) == null && locks.tryAcquire(7, "R3") == null, "released in a finally, the mapping is free again");
		locks.clear();
		check(locks.holder(7) == null && locks.holder(8) == null, "a module stop forgets every lock");

		// many starts at once: exactly one wins
		final MappingRunLocks race = new MappingRunLocks();
		final int starts = 32;
		final CountDownLatch go = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(starts);
		final AtomicInteger won = new AtomicInteger();
		for (int i = 0; i < starts; i++)
		{
			final String run = "R" + i;
			new Thread(new Runnable()
			{
				@Override
				public void run()
				{
					try
					{
						go.await();
						if (race.tryAcquire(1, run) == null)
						{
							won.incrementAndGet();
						}
					}
					catch (final InterruptedException ex)
					{
						Thread.currentThread().interrupt();
					}
					finally
					{
						done.countDown();
					}
				}
			}).start();
		}
		go.countDown();
		done.await();
		check(won.get() == 1, "exactly one of " + starts + " simultaneous starts holds the mapping: " + won.get());

		final String message = MappingRunLocks.refusal(12, "Kafka feed", "R20261006-0101-0001");
		check(message.contains("Mapping 12 (Kafka feed) is already running (run R20261006-0101-0001)") && message.contains("nothing was read or written"),
			"the refusal names the mapping, the run and what happened: " + message);
		check(MappingRunLocks.refusal(3, "", "R9").startsWith("Mapping 3 is already running (run R9)"), "no empty parentheses without a label");
		System.out.println("MappingRunLocksTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
