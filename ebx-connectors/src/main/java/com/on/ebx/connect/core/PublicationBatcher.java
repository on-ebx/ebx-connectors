package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Bounded-memory loading of one published table (audit C-17): rows are handed in one at a time and
 * flushed to the target in batches of {@code batchSize}, so a publication never holds more than one
 * batch in memory whatever the table's size. Also the pure decisions about the row cap and the
 * resulting status, kept here so they are unit-tested without EBX or a warehouse.
 */
final class PublicationBatcher
{
	/** {@code -Debx.connectors.publish.maxRowsPerTable}: operator ceiling on the rows read per table; 0 or unset = no ceiling. */
	static final String MAX_ROWS_PROPERTY = "ebx.connectors.publish.maxRowsPerTable";

	/** What one batch is loaded with; returns the rows the target accepted. */
	interface Loader
	{
		int load(List<ObjectNode> pDocuments) throws ConnectorException;
	}

	private final int batchSize;
	private final Loader loader;
	private List<ObjectNode> pending = new ArrayList<ObjectNode>();
	private int loaded;
	private int batches;

	PublicationBatcher(final int pBatchSize, final Loader pLoader)
	{
		this.batchSize = Math.max(1, pBatchSize);
		this.loader = pLoader;
	}

	void add(final ObjectNode pDocument) throws ConnectorException
	{
		this.pending.add(pDocument);
		if (this.pending.size() >= this.batchSize)
		{
			this.flush();
		}
	}

	/** Loads what is pending, if anything. */
	void flush() throws ConnectorException
	{
		if (this.pending.isEmpty())
		{
			return;
		}
		final List<ObjectNode> batch = this.pending;
		this.pending = new ArrayList<ObjectNode>();
		this.loaded += this.loader.load(batch);
		this.batches++;
	}

	/** Rows the target accepted so far. */
	int loaded()
	{
		return this.loaded;
	}

	int batches()
	{
		return this.batches;
	}

	/** Rows waiting in memory right now (never more than the batch size). */
	int pendingRows()
	{
		return this.pending.size();
	}

	/**
	 * The number of rows to read per table: the request's {@code max} and the operator ceiling, the smaller
	 * positive one; {@code Integer.MAX_VALUE} (no cut) when neither is positive.
	 */
	static int effectiveMax(final int pRequested, final int pOperatorCap)
	{
		if (pRequested > 0 && pOperatorCap > 0)
		{
			return Math.min(pRequested, pOperatorCap);
		}
		if (pRequested > 0)
		{
			return pRequested;
		}
		return pOperatorCap > 0 ? pOperatorCap : Integer.MAX_VALUE;
	}

	/** The operator ceiling from the system property; 0 (no ceiling) when unset or unreadable. */
	static int operatorCap()
	{
		return PublicationBatcher.parseCap(System.getProperty(PublicationBatcher.MAX_ROWS_PROPERTY));
	}

	static int parseCap(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return 0;
		}
		try
		{
			return Math.max(0, Integer.parseInt(pValue.trim()));
		}
		catch (final NumberFormatException ex)
		{
			return 0;
		}
	}

	/**
	 * The status of a publication that committed: {@code OK} when every row of every table was read,
	 * {@code PARTIAL} when at least one table was cut at the row cap. A cut publication is never OK
	 * (the target's tables were replaced by the cut set).
	 */
	static String statusOf(final boolean pCut)
	{
		return pCut ? "PARTIAL" : "OK";
	}
}
