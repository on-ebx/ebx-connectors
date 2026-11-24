package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every thread the module starts on its own lives in an executor declared here, so that stopping
 * the module stops all of them.
 * <p>
 * A thread that outlives its web application keeps the application's class loader, and through it
 * every class and static of the module, reachable for as long as it runs: the kernel can then be
 * left calling into a stopped class loader after a context reload. Marking the threads as daemons
 * only lets the JVM exit; it does nothing for a reload. The module therefore stops its executors
 * itself, from both shutdown callbacks, and waits a bounded time for their threads to end.
 * <p>
 * Executors are created on first use and never by a class initialiser: once the module is stopped
 * a late caller (a table trigger firing during shutdown) is refused instead of quietly starting a
 * new thread, and a repository started again in the same class loader gets fresh executors after
 * {@link #start()}.
 */
public final class BackgroundWork
{
	/** Creates the executor of a {@link Managed}; called under its lock, at most once per start. */
	public interface Factory<E extends ExecutorService>
	{
		E create();
	}

	/** An executor of the module: created on first use, stopped by {@link BackgroundWork#stop}. */
	public static final class Managed<E extends ExecutorService>
	{
		private final String name;
		private final Factory<E> factory;
		private E executor;

		Managed(final String pName, final Factory<E> pFactory)
		{
			this.name = pName;
			this.factory = pFactory;
		}

		/**
		 * The executor, created if need be.
		 *
		 * @throws RejectedExecutionException when the module is stopped: no thread is started for a module that is going away
		 */
		public synchronized E get()
		{
			if (BackgroundWork.stopped)
			{
				throw new RejectedExecutionException("The connectors module is stopping: " + this.name + " accepts no more work");
			}
			if (this.executor == null || this.executor.isShutdown())
			{
				this.executor = this.factory.create();
			}
			return this.executor;
		}

		/** The executor if one was ever created, without creating one; for monitoring and tests. */
		public synchronized E peek()
		{
			return this.executor;
		}

		private synchronized E detach()
		{
			final E current = this.executor;
			this.executor = null;
			return current;
		}
	}

	private static final List<Managed<?>> MANAGED = new CopyOnWriteArrayList<Managed<?>>();
	/** Short-lived executors (a retry loop that ends by itself), kept only until they terminate. */
	private static final List<Named> TRANSIENT = new CopyOnWriteArrayList<Named>();
	private static volatile boolean stopped;

	private static final class Named
	{
		final String name;
		final ExecutorService executor;

		Named(final String pName, final ExecutorService pExecutor)
		{
			this.name = pName;
			this.executor = pExecutor;
		}
	}

	private BackgroundWork()
	{
	}

	/** Declares an executor of the module. Nothing is created until {@link Managed#get()} is called. */
	public static <E extends ExecutorService> Managed<E> managed(final String pName, final Factory<E> pFactory)
	{
		final Managed<E> managed = new Managed<E>(pName, pFactory);
		BackgroundWork.MANAGED.add(managed);
		return managed;
	}

	/**
	 * Hands over an executor that ends by itself (it shuts itself down once its work is done), so that
	 * a stop of the module does not have to wait for that.
	 *
	 * @throws RejectedExecutionException when the module is stopped; the executor is shut down first
	 */
	public static <E extends ExecutorService> E track(final String pName, final E pExecutor)
	{
		if (BackgroundWork.stopped)
		{
			pExecutor.shutdownNow();
			throw new RejectedExecutionException("The connectors module is stopping: " + pName + " is not started");
		}
		for (final Iterator<Named> finished = BackgroundWork.TRANSIENT.iterator(); finished.hasNext();)
		{
			final Named named = finished.next();
			if (named.executor.isTerminated())
			{
				BackgroundWork.TRANSIENT.remove(named);
			}
		}
		BackgroundWork.TRANSIENT.add(new Named(pName, pExecutor));
		return pExecutor;
	}

	/** Repository startup: executors may be created again. */
	public static void start()
	{
		BackgroundWork.stopped = false;
	}

	public static boolean isStopped()
	{
		return BackgroundWork.stopped;
	}

	/**
	 * Stops every executor of the module within {@code pWaitMs}, in two steps.
	 * <p>
	 * First nothing new is accepted, what has not started is dropped, and the tasks that are running
	 * get three quarters of the time to end by themselves. They are not interrupted yet, on purpose: a
	 * task may be in the middle of an EBX transaction, and an interrupt delivered to a thread that is
	 * reading or writing a file closes the file's channel under whoever else uses it - an index of the
	 * repository, in a kernel that keeps running when only this module is being unloaded. Tasks that
	 * can run long are expected to see {@link #isStopped()} (or their own stop request) at their next
	 * safe point.
	 * <p>
	 * Then whatever is still running is interrupted ({@code shutdownNow}) and given the rest of the
	 * time. Safe to call several times and from both shutdown callbacks.
	 *
	 * @return the names of the executors whose threads were still running when the wait ended; empty
	 *         when everything stopped (the normal case)
	 */
	public static List<String> stop(final long pWaitMs)
	{
		BackgroundWork.stopped = true;
		final List<Named> stopping = new ArrayList<Named>();
		for (final Managed<?> managed : BackgroundWork.MANAGED)
		{
			final ExecutorService executor = managed.detach();
			if (executor != null)
			{
				stopping.add(new Named(managed.name, executor));
			}
		}
		stopping.addAll(BackgroundWork.TRANSIENT);
		BackgroundWork.TRANSIENT.clear();
		final long start = System.nanoTime();
		final long total = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, pWaitMs));
		// All of them are told first, so that the wait is shared instead of being spent in turn.
		for (final Named named : stopping)
		{
			BackgroundWork.quiesce(named.executor);
		}
		boolean interrupted = BackgroundWork.await(stopping, start + total / 4 * 3);
		for (final Named named : stopping)
		{
			if (!named.executor.isTerminated())
			{
				named.executor.shutdownNow();
			}
		}
		interrupted |= BackgroundWork.await(stopping, start + total);
		final List<String> stillRunning = new ArrayList<String>();
		for (final Named named : stopping)
		{
			if (!named.executor.isTerminated())
			{
				stillRunning.add(named.name);
			}
		}
		if (interrupted)
		{
			Thread.currentThread().interrupt();
		}
		return stillRunning;
	}

	/** No new work, nothing that has not started yet; running tasks are left to finish. */
	private static void quiesce(final ExecutorService pExecutor)
	{
		if (pExecutor instanceof ScheduledThreadPoolExecutor)
		{
			final ScheduledThreadPoolExecutor scheduled = (ScheduledThreadPoolExecutor) pExecutor;
			scheduled.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
			scheduled.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
			scheduled.shutdown();
		}
		else if (pExecutor instanceof ThreadPoolExecutor)
		{
			pExecutor.shutdown();
			((ThreadPoolExecutor) pExecutor).getQueue().clear();
		}
		else
		{
			// An executor whose queue cannot be reached: the only way to drop what is waiting.
			pExecutor.shutdownNow();
		}
	}

	/** Waits for the executors to terminate until the deadline; true when the waiting thread was itself interrupted. */
	private static boolean await(final List<Named> pStopping, final long pDeadlineNanos)
	{
		boolean interrupted = false;
		for (final Named named : pStopping)
		{
			try
			{
				named.executor.awaitTermination(Math.max(0L, pDeadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
			}
			catch (final InterruptedException ex)
			{
				interrupted = true;
			}
		}
		return interrupted;
	}

	/** Daemon threads named {@code <prefix>} or {@code <prefix>-N}: the name is what a thread dump shows. */
	public static ThreadFactory daemonThreads(final String pPrefix, final boolean pNumbered)
	{
		return new ThreadFactory()
		{
			private final AtomicInteger sequence = new AtomicInteger();

			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, pNumbered ? pPrefix + "-" + this.sequence.incrementAndGet() : pPrefix);
				thread.setDaemon(true);
				// C2-13: a thread keeps the context class loader of whoever creates it, and a pool creates threads from the
				// submitting thread, which may belong to another module (a trigger fired by its write, a workflow step). A
				// module reloaded later would leave this one's threads loading classes through a stopped loader (CLAUDE.md
				// section 10, the workflow engine's pools). The loader of this module is the only one that is right.
				thread.setContextClassLoader(BackgroundWork.class.getClassLoader());
				return thread;
			}
		};
	}

	/** {@code -D<name>} as a positive int, {@code pDefault} when unset, not a number or below {@code pMin}. */
	public static int intProperty(final String pName, final int pDefault, final int pMin)
	{
		try
		{
			final int value = Integer.parseInt(System.getProperty(pName, String.valueOf(pDefault)).trim());
			return value < pMin ? pDefault : value;
		}
		catch (final RuntimeException ex)
		{
			return pDefault;
		}
	}
}
