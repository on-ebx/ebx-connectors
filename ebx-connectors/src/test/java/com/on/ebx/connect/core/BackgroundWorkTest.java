package com.on.ebx.connect.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Operation;

/**
 * What stops with the module: executors (two-step stop, bounded wait, nothing started afterwards),
 * the admission of asynchronous runs, and the clients held by connector instances (closed when the
 * registry lets go of an instance). All of it runs here on plain threads and a stand-in connector;
 * that EBX calls the two shutdown callbacks, and what an interrupt does to a transaction in flight,
 * can only be seen on an instance.
 */
public final class BackgroundWorkTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		executorsStopWithTheModule();
		runsAreAdmittedUpToALimit();
		connectorsAreClosedWhenLetGo();
		threadsCarryTheLoaderOfTheModule();
		System.out.println("PASS: executors stop in a bounded time and start nothing afterwards; asynchronous runs are refused beyond the limit; a connector instance is closed when the registry lets go of it; a thread created from another module's thread carries this module's loader");
	}

	private static void executorsStopWithTheModule() throws Exception
	{
		BackgroundWork.start();
		final AtomicInteger created = new AtomicInteger();
		final BackgroundWork.Managed<ThreadPoolExecutor> pool = BackgroundWork.managed("test pool", new BackgroundWork.Factory<ThreadPoolExecutor>()
		{
			@Override
			public ThreadPoolExecutor create()
			{
				created.incrementAndGet();
				return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), BackgroundWork.daemonThreads("test-pool", true));
			}
		});
		check(created.get() == 0 && pool.peek() == null, "declaring an executor starts no thread");

		// a task that looks at the stop flag ends by itself, uninterrupted; what was queued behind it never runs
		final CountDownLatch started = new CountDownLatch(1);
		final AtomicBoolean cooperativeInterrupted = new AtomicBoolean();
		final AtomicBoolean queuedRan = new AtomicBoolean();
		pool.get().execute(new Runnable()
		{
			@Override
			public void run()
			{
				started.countDown();
				while (!BackgroundWork.isStopped())
				{
					Thread.yield();
				}
				// what a real task does at its safe point: finish what is in hand, then end
				try
				{
					Thread.sleep(200L);
				}
				catch (final InterruptedException ex)
				{
					cooperativeInterrupted.set(true);
				}
			}
		});
		pool.get().execute(new Runnable()
		{
			@Override
			public void run()
			{
				queuedRan.set(true);
			}
		});
		// a task that only an interrupt can end, and a delayed one that must never fire
		final BackgroundWork.Managed<ScheduledThreadPoolExecutor> scheduler = BackgroundWork.managed("test scheduler", new BackgroundWork.Factory<ScheduledThreadPoolExecutor>()
		{
			@Override
			public ScheduledThreadPoolExecutor create()
			{
				return new ScheduledThreadPoolExecutor(1, BackgroundWork.daemonThreads("test-scheduler", false));
			}
		});
		final CountDownLatch sleeping = new CountDownLatch(1);
		final AtomicBoolean sleeperInterrupted = new AtomicBoolean();
		final AtomicBoolean delayedRan = new AtomicBoolean();
		scheduler.get().execute(new Runnable()
		{
			@Override
			public void run()
			{
				sleeping.countDown();
				try
				{
					Thread.sleep(600000L);
				}
				catch (final InterruptedException ex)
				{
					sleeperInterrupted.set(true);
				}
			}
		});
		scheduler.get().schedule(new Runnable()
		{
			@Override
			public void run()
			{
				delayedRan.set(true);
			}
		}, 300L, TimeUnit.MILLISECONDS);
		final ExecutorService tracked = BackgroundWork.track("test transient", new ScheduledThreadPoolExecutor(1, BackgroundWork.daemonThreads("test-transient", false)));
		check(started.await(5, TimeUnit.SECONDS) && sleeping.await(5, TimeUnit.SECONDS), "tasks started");

		final long begin = System.nanoTime();
		final List<String> stillRunning = BackgroundWork.stop(2000L);
		final long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
		check(stillRunning.isEmpty(), "everything stopped: " + stillRunning);
		check(tookMs < 2500L, "within the wait: " + tookMs + " ms");
		check(!cooperativeInterrupted.get(), "a task that ends by itself is not interrupted");
		check(sleeperInterrupted.get() && tookMs >= 1400L, "a task that does not is interrupted, after the first three quarters of the wait: " + tookMs + " ms");
		check(!queuedRan.get(), "a task that had not started is dropped");
		Thread.sleep(400L);
		check(!delayedRan.get(), "a delayed task does not fire after the stop");
		check(tracked.isTerminated(), "a transient executor is stopped too");

		// nothing starts for a stopped module
		boolean refused = false;
		try
		{
			pool.get();
		}
		catch (final RejectedExecutionException expected)
		{
			refused = true;
		}
		check(refused && created.get() == 1, "a stopped module creates no executor");
		final ScheduledThreadPoolExecutor late = new ScheduledThreadPoolExecutor(1);
		refused = false;
		try
		{
			BackgroundWork.track("late", late);
		}
		catch (final RejectedExecutionException expected)
		{
			refused = true;
		}
		check(refused && late.isShutdown(), "an executor handed over too late is shut down, not kept");
		check(BackgroundWork.stop(1000L).isEmpty(), "a second stop is harmless");

		// a task that ignores both the flag and the interrupt is reported, not waited for forever
		BackgroundWork.start();
		final AtomicBoolean release = new AtomicBoolean();
		final CountDownLatch stubbornStarted = new CountDownLatch(1);
		pool.get().execute(new Runnable()
		{
			@Override
			public void run()
			{
				stubbornStarted.countDown();
				while (!release.get())
				{
					Thread.yield();
				}
			}
		});
		check(created.get() == 2, "after a start, a fresh executor is created");
		check(stubbornStarted.await(5, TimeUnit.SECONDS), "stubborn task started");
		final long stubbornBegin = System.nanoTime();
		final List<String> left = BackgroundWork.stop(400L);
		check(left.contains("test pool"), "the executor that did not stop is named: " + left);
		check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stubbornBegin) < 1500L, "and the stop is still bounded");
		release.set(true);
		BackgroundWork.start();
	}

	private static void runsAreAdmittedUpToALimit()
	{
		final RunSlots slots = new RunSlots(2, 1);
		check(slots.capacity() == 3 && slots.getMaxConcurrent() == 2, "two running and one waiting");
		check(slots.tryReserve() && slots.tryReserve() && slots.tryReserve(), "three runs are admitted");
		check(!slots.tryReserve() && slots.inUse() == 3, "the fourth is refused and takes nothing");
		final String refusal = slots.refusal("ebx.connectors.import.async.maxConcurrent", "ebx.connectors.import.async.maxQueued");
		check(refusal.startsWith("Too many asynchronous runs") && refusal.contains("-Debx.connectors.import.async.maxConcurrent") && refusal.contains("3 places"),
			"the refusal says the limit and the properties: " + refusal);
		slots.release();
		check(slots.tryReserve(), "a run that ended frees its place");
		slots.clear();
		check(slots.inUse() == 0, "a stop frees every place");
		slots.release();
		check(slots.inUse() == 0, "a late release does not go below zero");
		check(new RunSlots(0, -5).capacity() == 1, "at least one run is always possible");
		check(BackgroundWork.intProperty("ebx.connectors.test.unset", 4, 1) == 4, "an unset property takes its default");
		System.setProperty("ebx.connectors.test.bad", "zero");
		System.setProperty("ebx.connectors.test.low", "0");
		System.setProperty("ebx.connectors.test.ok", " 9 ");
		check(BackgroundWork.intProperty("ebx.connectors.test.bad", 4, 1) == 4 && BackgroundWork.intProperty("ebx.connectors.test.low", 4, 1) == 4
			&& BackgroundWork.intProperty("ebx.connectors.test.ok", 4, 1) == 9, "a property that is not a number or below the minimum takes its default");
	}

	private static void connectorsAreClosedWhenLetGo() throws Exception
	{
		final ConnectorRegistry registry = ConnectorRegistry.get();
		registry.registerType(HoldingConnector.class);
		final ConnectorConfig[] stored = new ConnectorConfig[] { config("https://one.example.invalid", true) };
		final ConnectorRegistry.ConfigSource source = new ConnectorRegistry.ConfigSource()
		{
			@Override
			public ConnectorConfig connector(final String pId)
			{
				return stored[0];
			}
		};
		final HoldingConnector first = (HoldingConnector) registry.connector("holding", source);
		check(registry.connector("holding", source) == first && first.closed.get() == 0, "an unchanged configuration keeps its instance, open");

		stored[0] = config("https://two.example.invalid", true);
		final HoldingConnector second = (HoldingConnector) registry.connector("holding", source);
		check(second != first && first.closed.get() == 1 && second.closed.get() == 0, "a changed configuration replaces the instance and closes the old one, once");

		stored[0] = config("https://two.example.invalid", false);
		check(refuses(registry, source, "inactive") && second.closed.get() == 1, "a connector switched off lets go of its instance");
		check(refuses(registry, source, "inactive") && second.closed.get() == 1, "and does not close it twice");

		stored[0] = config("https://three.example.invalid", true);
		final HoldingConnector third = (HoldingConnector) registry.connector("holding", source);
		stored[0] = null;
		check(refuses(registry, source, "not declared") && third.closed.get() == 1, "a connector removed from the dataset lets go of its instance");

		stored[0] = config("https://four.example.invalid", true);
		final HoldingConnector fourth = (HoldingConnector) registry.connector("holding", source);
		fourth.failOnClose = true;
		registry.invalidate();
		check(fourth.closed.get() == 1, "invalidating the cache closes what it held, even when a connector fails to close");
		final HoldingConnector fifth = (HoldingConnector) registry.connector("holding", source);
		check(fifth != fourth && fifth.closed.get() == 0, "the next lookup builds a fresh instance");
		registry.invalidate();
		check(fifth.closed.get() == 1, "the stop of the module closes it");
	}

	private static boolean refuses(final ConnectorRegistry pRegistry, final ConnectorRegistry.ConfigSource pSource, final String pExpected)
	{
		try
		{
			pRegistry.connector("holding", pSource);
			return false;
		}
		catch (final ConnectorException expected)
		{
			return expected.getMessage().contains(pExpected);
		}
	}

	private static ConnectorConfig config(final String pBaseUrl, final boolean pActive)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("holding");
		config.setType("test-holding");
		config.setBaseUrl(pBaseUrl);
		config.setActive(pActive);
		return config;
	}

	/** A connector that holds something between calls, as the Kafka connector holds its producer. */
	public static final class HoldingConnector implements Connector
	{
		final AtomicInteger closed = new AtomicInteger();
		volatile boolean failOnClose;

		@Override
		public String getType()
		{
			return "test-holding";
		}

		@Override
		public String getLabel()
		{
			return "Holding";
		}

		@Override
		public List<Operation> getOperations()
		{
			return Collections.emptyList();
		}

		@Override
		public void configure(final ConnectorConfig pConfig)
		{
		}

		@Override
		public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		{
			return null;
		}

		@Override
		public void close()
		{
			this.closed.incrementAndGet();
			if (this.failOnClose)
			{
				throw new IllegalStateException("client refused to close");
			}
		}
	}

	/** C2-13: the factory is called from a thread whose context loader is another module's; the new thread must not inherit it. */
	private static void threadsCarryTheLoaderOfTheModule() throws Exception
	{
		final ClassLoader foreign = new ClassLoader(null)
		{
		};
		final java.util.concurrent.atomic.AtomicReference<ClassLoader> seen = new java.util.concurrent.atomic.AtomicReference<ClassLoader>();
		final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
		final Thread creator = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					final Thread made = BackgroundWork.daemonThreads("test-loader", true).newThread(new Runnable()
					{
						@Override
						public void run()
						{
						}
					});
					seen.set(made.getContextClassLoader());
				}
				catch (final Throwable ex)
				{
					failure.set(ex);
				}
			}
		});
		creator.setContextClassLoader(foreign);
		creator.start();
		creator.join(5000L);
		check(failure.get() == null, "creating the thread: " + failure.get());
		check(seen.get() == BackgroundWork.class.getClassLoader() && seen.get() != foreign, "the thread runs on the module's loader, not its creator's: " + seen.get());
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
