package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.ConnectorException;

/** Offline checks of {@link PublicationBatcher} (audit C-17): batching, bounded memory, the row cap and the status decision. */
public final class PublicationBatcherTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		testBatchesAndBoundedMemory();
		testFlushOfAnEmptyBatchLoadsNothing();
		testTheTargetsCountIsWhatIsReported();
		testLoadFailurePropagates();
		testEffectiveMax();
		testOperatorCapParsing();
		testStatusDecision();
		System.out.println("PASS: PublicationBatcher (batching, bounded memory, row cap, PARTIAL decision)");
	}

	private static void testBatchesAndBoundedMemory() throws Exception
	{
		final List<Integer> sizes = new ArrayList<Integer>();
		final PublicationBatcher batcher = new PublicationBatcher(500, new PublicationBatcher.Loader()
		{
			@Override
			public int load(final List<ObjectNode> pDocuments)
			{
				sizes.add(Integer.valueOf(pDocuments.size()));
				return pDocuments.size();
			}
		});
		int peak = 0;
		for (int i = 0; i < 1200; i++)
		{
			batcher.add(Json.object());
			peak = Math.max(peak, batcher.pendingRows());
		}
		batcher.flush();
		check(sizes.toString().equals("[500, 500, 200]"), "1200 rows in batches of 500: " + sizes);
		check(peak <= 500, "never more than one batch waiting in memory, peak " + peak);
		check(batcher.loaded() == 1200 && batcher.batches() == 3, "loaded 1200 in 3 batches: " + batcher.loaded() + "/" + batcher.batches());
		check(batcher.pendingRows() == 0, "nothing left pending");
		final PublicationBatcher tiny = new PublicationBatcher(0, new PublicationBatcher.Loader()
		{
			@Override
			public int load(final List<ObjectNode> pDocuments)
			{
				return pDocuments.size();
			}
		});
		tiny.add(Json.object());
		check(tiny.batches() == 1, "a batch size below 1 is raised to 1");
	}

	private static void testFlushOfAnEmptyBatchLoadsNothing() throws Exception
	{
		final int[] calls = new int[1];
		final PublicationBatcher batcher = new PublicationBatcher(10, new PublicationBatcher.Loader()
		{
			@Override
			public int load(final List<ObjectNode> pDocuments)
			{
				calls[0]++;
				return pDocuments.size();
			}
		});
		batcher.flush();
		batcher.add(Json.object());
		batcher.flush();
		batcher.flush();
		check(calls[0] == 1 && batcher.batches() == 1, "one load for one row, none for an empty flush: " + calls[0]);
	}

	private static void testTheTargetsCountIsWhatIsReported() throws Exception
	{
		final PublicationBatcher batcher = new PublicationBatcher(2, new PublicationBatcher.Loader()
		{
			@Override
			public int load(final List<ObjectNode> pDocuments)
			{
				return pDocuments.size() - 1; // the target accepted one row less per batch
			}
		});
		for (int i = 0; i < 4; i++)
		{
			batcher.add(Json.object());
		}
		check(batcher.loaded() == 2, "the count is what the target accepted, not what was sent: " + batcher.loaded());
	}

	private static void testLoadFailurePropagates() throws Exception
	{
		final PublicationBatcher batcher = new PublicationBatcher(1, new PublicationBatcher.Loader()
		{
			@Override
			public int load(final List<ObjectNode> pDocuments) throws ConnectorException
			{
				throw new ConnectorException("boom");
			}
		});
		boolean thrown = false;
		try
		{
			batcher.add(Json.object());
		}
		catch (final ConnectorException ex)
		{
			thrown = "boom".equals(ex.getMessage());
		}
		check(thrown, "a failing load surfaces to the caller");
	}

	private static void testEffectiveMax() throws Exception
	{
		check(PublicationBatcher.effectiveMax(0, 0) == Integer.MAX_VALUE, "no max, no ceiling: nothing is cut");
		check(PublicationBatcher.effectiveMax(500, 0) == 500, "request only");
		check(PublicationBatcher.effectiveMax(0, 900) == 900, "ceiling only");
		check(PublicationBatcher.effectiveMax(500, 900) == 500, "the smaller of the two: request");
		check(PublicationBatcher.effectiveMax(900, 500) == 500, "the smaller of the two: ceiling");
		check(PublicationBatcher.effectiveMax(-1, -1) == Integer.MAX_VALUE, "negative values mean none");
		check(PublicationBatcher.effectiveMax(5000000, 0) == 5000000, "no hard cap at 200000");
	}

	private static void testOperatorCapParsing() throws Exception
	{
		check(PublicationBatcher.parseCap(null) == 0, "unset");
		check(PublicationBatcher.parseCap("  ") == 0, "blank");
		check(PublicationBatcher.parseCap("250000") == 250000, "a number");
		check(PublicationBatcher.parseCap(" 42 ") == 42, "trimmed");
		check(PublicationBatcher.parseCap("-5") == 0, "negative");
		check(PublicationBatcher.parseCap("lots") == 0, "unreadable keeps no ceiling");
	}

	private static void testStatusDecision() throws Exception
	{
		check("OK".equals(PublicationBatcher.statusOf(false)), "nothing cut: OK");
		check("PARTIAL".equals(PublicationBatcher.statusOf(true)), "cut: PARTIAL, never OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
