package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Audit C-07 (lot L8): what a table trigger queues is sent by a background thread after the commit, at least once, with
 * retries and a set-aside after the mapping's maximum, and rows left pending are taken again at the next start. Runs on an
 * in-memory store and a counting sender: no EBX repository.
 */
public final class OutboundQueueTest
{
	private OutboundQueueTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		System.setProperty(OutboundQueue.SETTLE_PROPERTY, "300");
		System.setProperty(OutboundQueue.INTERVAL_PROPERTY, "100");
		check(OutboundQueue.triggerRetryDelayMs(30) == 120000L, "default trigger retry delay is 2 minutes");
		check(OutboundQueue.triggerRetryDelayMs(1) == 60000L, "capped by a smaller mapping delay");
		check(OutboundQueue.triggerRetryDelayMs(0) == 0L, "a mapping delay of 0 stays 0");
		System.setProperty(OutboundQueue.TRIGGER_RETRY_PROPERTY, "5");
		check(OutboundQueue.triggerRetryDelayMs(30) == 300000L, "the JVM property sets the delay");
		System.clearProperty(OutboundQueue.TRIGGER_RETRY_PROPERTY);
		drainSendsOnceAndDeletes();
		failureRetriesThenSetsAside();
		dropDeletesWithoutRetry();
		goldenEntriesSendTheGolden();
		submitNeverSendsOnTheCallingThread();
		pendingRowsAreTakenAtStart();
		System.out.println("PASS: queued sends leave the caller's thread, are sent after the settle delay, once per record, retried then set aside, and recovered at start");
	}

	/** In-memory table. */
	public static final class MemoryStore implements OutboundQueue.Store
	{
		final List<OutboundQueue.Entry> rows = Collections.synchronizedList(new ArrayList<OutboundQueue.Entry>());
		final AtomicInteger ids = new AtomicInteger();

		@Override
		public void add(final List<OutboundQueue.Entry> pEntries)
		{
			for (final OutboundQueue.Entry entry : pEntries)
			{
				entry.id = Integer.valueOf(this.ids.incrementAndGet());
				this.rows.add(entry);
			}
		}

		@Override
		public List<OutboundQueue.Entry> due(final long pNow, final int pMax)
		{
			final List<OutboundQueue.Entry> out = new ArrayList<OutboundQueue.Entry>();
			synchronized (this.rows)
			{
				for (final OutboundQueue.Entry row : this.rows)
				{
					if (OutboundQueue.PENDING.equals(row.status) && row.notBefore <= pNow && out.size() < pMax)
					{
						out.add(row);
					}
				}
			}
			return out;
		}

		@Override
		public void delete(final List<OutboundQueue.Entry> pEntries)
		{
			this.rows.removeAll(pEntries);
		}

		@Override
		public void failed(final List<OutboundQueue.Entry> pEntries, final int pAttempts, final long pNotBefore, final String pError, final boolean pDead, final long pNow)
		{
			for (final OutboundQueue.Entry entry : pEntries)
			{
				entry.attempts = pAttempts;
				entry.notBefore = pNotBefore;
				entry.lastError = pError;
				entry.status = pDead ? OutboundQueue.DEAD : OutboundQueue.PENDING;
			}
		}
	}

	static OutboundQueue.Entry entry(final String pKey)
	{
		final OutboundQueue.Entry entry = new OutboundQueue.Entry();
		entry.connector = "CRM";
		entry.operation = "push";
		entry.direction = "OUTBOUND";
		entry.dataspace = "Master";
		entry.dataset = "Customers";
		entry.tablePath = "/root/Customer";
		entry.recordKey = pKey;
		return entry;
	}

	private static void drainSendsOnceAndDeletes()
	{
		final MemoryStore store = new MemoryStore();
		// the same record written three times is three rows, one send; another record is its own send
		store.add(new ArrayList<OutboundQueue.Entry>(java.util.Arrays.asList(entry("1"), entry("1"), entry("1"), entry("2"))));
		final AtomicInteger calls = new AtomicInteger();
		final OutboundQueue.PassResult result = OutboundQueue.drain(store, new OutboundQueue.Sender()
		{
			@Override
			public OutboundQueue.Outcome send(final OutboundQueue.Entry pEntry)
			{
				calls.incrementAndGet();
				return OutboundQueue.Outcome.sent();
			}
		}, System.currentTimeMillis(), 100);
		check(calls.get() == 2 && result.sent == 2, "one send per record, got " + calls.get());
		check(store.rows.isEmpty() && result.rowsDeleted == 4, "every row of a sent record is deleted");
	}

	/** Real-time mastering: publish=golden entries are resolved at send time; members of one golden are sent once. */
	private static void goldenEntriesSendTheGolden()
	{
		final MemoryStore store = new MemoryStore();
		final OutboundQueue.Entry m1 = golden(entry("m1"));
		final OutboundQueue.Entry m2 = golden(entry("m2"));
		final OutboundQueue.Entry g = golden(entry("g"));
		final OutboundQueue.Entry deleted = golden(entry("d"));
		final OutboundQueue.Entry broken = golden(entry("x"));
		final OutboundQueue.Entry plain = entry("m1");
		store.add(new ArrayList<OutboundQueue.Entry>(java.util.Arrays.asList(m1, m2, g, deleted, broken, plain)));
		final List<String> sent = new ArrayList<String>();
		final OutboundQueue.PassResult result = OutboundQueue.drain(store, new OutboundQueue.Sender()
		{
			@Override
			public OutboundQueue.Outcome send(final OutboundQueue.Entry pEntry)
			{
				sent.add(pEntry.recordKey + (pEntry.publish == null ? "" : "*"));
				return OutboundQueue.Outcome.sent();
			}
		}, new OutboundQueue.Resolver()
		{
			@Override
			public OutboundQueue.Resolution resolve(final OutboundQueue.Entry pEntry)
			{
				if ("x".equals(pEntry.recordKey))
					throw new IllegalStateException("golden not readable yet");
				if ("d".equals(pEntry.recordKey))
					return OutboundQueue.Resolution.drop("DELETED");
				return OutboundQueue.Resolution.send("g"); // m1 and m2 were merged into g; g is its own golden
			}
		}, 1000000L, 100);
		check(sent.equals(java.util.Arrays.asList("g*", "m1")), "the golden of m1, m2 and g once; the record-mode entry for m1 as itself: " + sent);
		check(result.sent == 2 && result.dropped == 1 && result.retried == 1, "2 sent, the DELETED one dropped, the unresolved one retried: " + result);
		check(store.rows.size() == 1 && store.rows.get(0) == broken && broken.attempts == 1 && OutboundQueue.PENDING.equals(broken.status),
			"only the unresolved row stays, pending, one attempt: " + store.rows);
		check(!entry("1").key().equals(golden(entry("1")).key()), "a golden entry and a record entry are not the same send");
	}

	private static OutboundQueue.Entry golden(final OutboundQueue.Entry pEntry)
	{
		pEntry.publish = OutboundQueue.PUBLISH_GOLDEN;
		return pEntry;
	}

	private static void failureRetriesThenSetsAside()
	{
		final MemoryStore store = new MemoryStore();
		store.add(new ArrayList<OutboundQueue.Entry>(java.util.Arrays.asList(entry("7"))));
		final OutboundQueue.Sender failing = new OutboundQueue.Sender()
		{
			@Override
			public OutboundQueue.Outcome send(final OutboundQueue.Entry pEntry)
			{
				return OutboundQueue.Outcome.retry("remote refused", 3, 1000L);
			}
		};
		final long now = 1000000L;
		OutboundQueue.drain(store, failing, now, 10);
		final OutboundQueue.Entry row = store.rows.get(0);
		check(row.attempts == 1 && OutboundQueue.PENDING.equals(row.status) && row.notBefore == now + 1000L && "remote refused".equals(row.lastError),
			"the first failure keeps the row pending, one attempt, delayed by the retry delay");
		OutboundQueue.drain(store, failing, now + 500L, 10);
		check(row.attempts == 1, "a row is not retried before its time");
		OutboundQueue.drain(store, failing, now + 1000L, 10);
		check(row.attempts == 2 && OutboundQueue.PENDING.equals(row.status), "second attempt");
		final OutboundQueue.PassResult last = OutboundQueue.drain(store, failing, now + 5000L, 10);
		check(row.attempts == 3 && OutboundQueue.DEAD.equals(row.status) && last.dead == 1, "set aside at the mapping's maximum number of attempts");
		check(OutboundQueue.drain(store, failing, now + 99999L, 10).retried == 0, "a DEAD row is never sent again");
	}

	private static void dropDeletesWithoutRetry()
	{
		final MemoryStore store = new MemoryStore();
		store.add(new ArrayList<OutboundQueue.Entry>(java.util.Arrays.asList(entry("9"))));
		final OutboundQueue.PassResult result = OutboundQueue.drain(store, new OutboundQueue.Sender()
		{
			@Override
			public OutboundQueue.Outcome send(final OutboundQueue.Entry pEntry)
			{
				return OutboundQueue.Outcome.drop("the record no longer exists");
			}
		}, System.currentTimeMillis(), 10);
		check(result.dropped == 1 && store.rows.isEmpty(), "a record that is gone is dropped, not retried");
	}

	private static void submitNeverSendsOnTheCallingThread() throws Exception
	{
		BackgroundWork.start();
		final MemoryStore store = new MemoryStore();
		final AtomicReference<Thread> sentOn = new AtomicReference<Thread>();
		final AtomicInteger sent = new AtomicInteger();
		final AtomicInteger sendingFlag = new AtomicInteger();
		OutboundQueue.start(store, new OutboundQueue.Sender()
		{
			@Override
			public OutboundQueue.Outcome send(final OutboundQueue.Entry pEntry)
			{
				sentOn.set(Thread.currentThread());
				if (OutboundQueue.isSending())
				{
					sendingFlag.incrementAndGet();
				}
				sent.incrementAndGet();
				return OutboundQueue.Outcome.sent();
			}
		});
		check(!OutboundQueue.isSending(), "the calling thread is not a sender thread");
		final long before = System.currentTimeMillis();
		check(OutboundQueue.submit(entry("1")) && OutboundQueue.submit(entry("1")), "submit accepts while the queue runs");
		check(sent.get() == 0, "nothing is sent while the caller is still in its transaction");
		Thread.sleep(150);
		check(sent.get() == 0, "nothing is sent before the settle delay (the commit)");
		check(waitFor(sent, 1, 5000), "the row is sent after the settle delay");
		check(System.currentTimeMillis() - before >= 300, "not sent before the settle delay");
		check(sentOn.get() != Thread.currentThread() && sentOn.get().getName().startsWith("ebx-connectors-outbound-queue"), "sent on the module's own thread");
		check(sendingFlag.get() == 1, "the sender's thread is flagged, so that the trigger does not queue the sender's own writes");
		Thread.sleep(300);
		check(sent.get() == 1 && store.rows.isEmpty(), "two submissions of the same record in a row are one send, and the row is gone");
		BackgroundWork.stop(2000L);
		check(!OutboundQueue.submit(entry("3")), "a stopped module refuses the hand-over");
		OutboundQueue.forget();
	}

	private static void pendingRowsAreTakenAtStart() throws Exception
	{
		BackgroundWork.start();
		final MemoryStore store = new MemoryStore();
		final OutboundQueue.Entry left = entry("42");
		left.notBefore = 0L;
		store.add(new ArrayList<OutboundQueue.Entry>(java.util.Arrays.asList(left)));
		final AtomicInteger sent = new AtomicInteger();
		OutboundQueue.start(store, new OutboundQueue.Sender()
		{
			@Override
			public OutboundQueue.Outcome send(final OutboundQueue.Entry pEntry)
			{
				sent.incrementAndGet();
				return OutboundQueue.Outcome.sent();
			}
		});
		check(waitFor(sent, 1, 5000), "a row left PENDING before the start is sent by the first passes");
		BackgroundWork.stop(2000L);
		OutboundQueue.forget();
		for (final Iterator<OutboundQueue.Entry> it = store.rows.iterator(); it.hasNext();)
		{
			check(false, "the recovered row is deleted");
		}
	}

	private static boolean waitFor(final AtomicInteger pCounter, final int pValue, final long pMaxMs) throws InterruptedException
	{
		final long end = System.currentTimeMillis() + pMaxMs;
		while (pCounter.get() < pValue && System.currentTimeMillis() < end)
		{
			Thread.sleep(25);
		}
		return pCounter.get() >= pValue;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
