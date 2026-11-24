package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure bisection behind the volume lot 6 batched import writes: {@link BatchCommit} must isolate a
 * failing item with O(log N) extra commit attempts, never retry a good item once it has already
 * committed, and never call the executor at all on an empty input. No EBX type involved.
 */
public final class BatchCommitTest
{
	private BatchCommitTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// everything succeeds on the first attempt: exactly one procedure call for the whole batch
		final List<Integer> ten = ints(10);
		final List<List<Integer>> calls1 = new ArrayList<List<Integer>>();
		final BatchCommit.Result<Integer> allOk = BatchCommit.commit(ten, recording(calls1, java.util.Collections.<Integer>emptySet()));
		check(allOk.getCommitted().equals(ten), "every item must be reported committed when the whole batch succeeds");
		check(allOk.getFailures().isEmpty(), "no failures expected");
		check(allOk.getProcedureCount() == 1, "a fully successful batch must take exactly one commit call, got " + allOk.getProcedureCount());
		check(calls1.size() == 1 && calls1.get(0).equals(ten), "the single call must have received the whole batch");

		// one bad item among many: isolated with O(log N) extra calls, everyone else still committed together.
		// With N=64 (a power of two, so every recursive halving stays a power of two too, regardless of which
		// single item is bad), the call count is deterministic: 1 (root) + 2 per halving down to size 1 (a
		// successful sibling call plus one more failing call to recurse into) - log2(64)=6 halvings, so
		// 1 + 2*6 = 13 calls, versus 64 for one procedure per item.
		final List<Integer> sixtyFour = ints(64);
		final List<List<Integer>> calls2 = new ArrayList<List<Integer>>();
		final BatchCommit.Result<Integer> oneBad = BatchCommit.commit(sixtyFour, recording(calls2, java.util.Collections.singleton(37)));
		check(!oneBad.getCommitted().contains(37), "the bad item must never be reported committed");
		check(oneBad.getCommitted().size() == 63, "every other item must end up committed, got " + oneBad.getCommitted().size());
		check(oneBad.getFailures().size() == 1 && oneBad.getFailures().get(0).getItem().intValue() == 37, "exactly the bad item must be reported failed");
		check(oneBad.getProcedureCount() == 13, "isolating one bad item out of 64 must take exactly 13 commit calls (2*log2(64)+1), got " + oneBad.getProcedureCount());
		check(oneBad.getProcedureCount() < sixtyFour.size() / 2, "bisection must cost far fewer calls than one per item, got " + oneBad.getProcedureCount() + " for " + sixtyFour.size() + " items");

		// two bad items far apart: both isolated, nothing in between ever retried once committed together
		final List<Integer> sixteen = ints(16);
		final List<List<Integer>> calls3 = new ArrayList<List<Integer>>();
		final BatchCommit.Result<Integer> twoBad = BatchCommit.commit(sixteen, recording(calls3, new java.util.HashSet<Integer>(Arrays.asList(2, 13))));
		check(twoBad.getFailures().size() == 2, "exactly two failures expected, got " + twoBad.getFailures().size());
		check(twoBad.getCommitted().size() == 14, "the other 14 items must all be committed, got " + twoBad.getCommitted().size());
		check(twoBad.getProcedureCount() < sixteen.size(), "two bad items must still cost far fewer than N calls, got " + twoBad.getProcedureCount());

		// a single item that fails on its own, isolated commit: recorded as a failure immediately (no infinite recursion)
		final List<Integer> one = java.util.Collections.singletonList(42);
		final BatchCommit.Result<Integer> soleFailure = BatchCommit.commit(one, recording(new ArrayList<List<Integer>>(), java.util.Collections.singleton(42)));
		check(soleFailure.getFailures().size() == 1, "a lone failing item must be reported failed");
		check(soleFailure.getCommitted().isEmpty(), "a lone failing item must never be reported committed");
		check(soleFailure.getProcedureCount() == 1, "a single item costs exactly one attempt, got " + soleFailure.getProcedureCount());

		// empty input: the executor must never be called
		final List<List<Integer>> calls4 = new ArrayList<List<Integer>>();
		final BatchCommit.Result<Integer> empty = BatchCommit.commit(new ArrayList<Integer>(), recording(calls4, java.util.Collections.<Integer>emptySet()));
		check(empty.getCommitted().isEmpty() && empty.getFailures().isEmpty(), "empty input must produce an empty result");
		check(calls4.isEmpty(), "the executor must never be invoked on empty input");
		check(empty.getProcedureCount() == 0, "empty input must cost zero commit calls");

		// the failure message is preserved for the caller to report as the item's own error
		final BatchCommit.Result<Integer> messaged = BatchCommit.commit(java.util.Collections.singletonList(7), new BatchCommit.Executor<Integer>()
		{
			@Override public void commit(final List<Integer> pItems) throws Exception { throw new IllegalStateException("boom"); }
		});
		check("boom".equals(messaged.getFailures().get(0).getMessage()), "the executor's exception message must be preserved");

		System.out.println("PASS: BatchCommit isolates failing items with far fewer than N commit calls and never retries a committed item");
	}

	private static List<Integer> ints(final int pCount)
	{
		final List<Integer> out = new ArrayList<Integer>();
		for (int i = 1; i <= pCount; i++) out.add(Integer.valueOf(i));
		return out;
	}

	/** An executor that fails a whole sublist whenever it contains one of pBadItems, and records every call it receives. */
	private static BatchCommit.Executor<Integer> recording(final List<List<Integer>> pCalls, final java.util.Set<Integer> pBadItems)
	{
		return new BatchCommit.Executor<Integer>()
		{
			@Override
			public void commit(final List<Integer> pItems) throws Exception
			{
				pCalls.add(new ArrayList<Integer>(pItems));
				for (final Integer item : pItems)
				{
					if (pBadItems.contains(item))
					{
						throw new Exception("item " + item + " is bad");
					}
				}
			}
		};
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
