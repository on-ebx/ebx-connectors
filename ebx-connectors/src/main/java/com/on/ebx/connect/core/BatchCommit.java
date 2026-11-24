package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Volume lot 6, task 2: commits a list of items as few EBX procedures as possible, isolating a
 * failing item without ever retrying good items one at a time.
 * <p>
 * {@link #commit} hands the whole list to {@link Executor#commit}, which is expected to write every
 * item of the sublist it is given in ONE EBX procedure (one transaction). When that call throws -
 * an EBX transaction rolled back, so none of the sublist's items are actually written - the sublist
 * is bisected into two halves and each half is retried the same way, recursively, down to a single
 * item. A single item whose own, isolated commit still throws is recorded as a failure and never
 * retried further: it is the row responsible. This isolates one (or a few) bad rows out of a batch
 * of N with O(log N) extra procedure calls, never O(N).
 * <p>
 * Pure orchestration: {@code Executor} is the only thing that touches EBX, so this class has no EBX
 * dependency and is unit-tested with plain in-memory fakes.
 */
public final class BatchCommit
{
	private BatchCommit()
	{
	}

	/** Commits a sublist of items as one EBX transaction; throws when the whole sublist failed to write. */
	public interface Executor<T>
	{
		void commit(List<T> pItems) throws Exception;
	}

	/** One item whose own, isolated commit attempt still failed. */
	public static final class ItemFailure<T>
	{
		private final T item;
		private final String message;

		ItemFailure(final T pItem, final String pMessage)
		{
			this.item = pItem;
			this.message = pMessage;
		}

		public T getItem()
		{
			return this.item;
		}

		public String getMessage()
		{
			return this.message;
		}
	}

	public static final class Result<T>
	{
		private final List<T> committed = new ArrayList<T>();
		private final List<ItemFailure<T>> failures = new ArrayList<ItemFailure<T>>();
		private int procedureCount;

		/** Items that were part of a sublist commit that succeeded (in some attempt, not necessarily the first). */
		public List<T> getCommitted()
		{
			return this.committed;
		}

		/** Items whose own, isolated single-item commit attempt threw. */
		public List<ItemFailure<T>> getFailures()
		{
			return this.failures;
		}

		/** Number of {@link Executor#commit} calls this run made (1 when the whole batch succeeded first try). */
		public int getProcedureCount()
		{
			return this.procedureCount;
		}
	}

	/**
	 * Commits {@code pItems} with as few calls to {@code pExecutor} as possible, bisecting on failure
	 * until every failing item is isolated. Empty input commits nothing and calls {@code pExecutor}
	 * zero times.
	 */
	public static <T> Result<T> commit(final List<T> pItems, final Executor<T> pExecutor)
	{
		if (pItems == null || pExecutor == null)
		{
			throw new IllegalArgumentException("Items and executor are required");
		}
		final Result<T> result = new Result<T>();
		BatchCommit.commitInto(pItems, pExecutor, result);
		return result;
	}

	private static <T> void commitInto(final List<T> pItems, final Executor<T> pExecutor, final Result<T> pResult)
	{
		if (pItems.isEmpty())
		{
			return;
		}
		pResult.procedureCount++;
		try
		{
			pExecutor.commit(pItems);
			pResult.committed.addAll(pItems);
		}
		catch (final Exception ex)
		{
			if (pItems.size() == 1)
			{
				pResult.failures.add(new ItemFailure<T>(pItems.get(0), ex.getMessage() == null ? ex.toString() : ex.getMessage()));
				return;
			}
			final int mid = pItems.size() / 2;
			BatchCommit.commitInto(pItems.subList(0, mid), pExecutor, pResult);
			BatchCommit.commitInto(pItems.subList(mid, pItems.size()), pExecutor, pResult);
		}
	}
}
