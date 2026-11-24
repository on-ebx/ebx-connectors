package com.on.ebx.connect.core;

/**
 * Volume lot 6, task 3: pure aggregation of one import batch into the shape of its single SyncLog
 * row - {@code recordKey} = {@code "<firstKey>..<lastKey>"} (or the one key when they are equal, or
 * null when no non-blank key was ever seen), {@code message} = the counts, {@code status} derived
 * from them. Built from plain counters and key strings collected while a batch commits; no EBX type
 * involved.
 */
public final class ImportBatchSummary
{
	private int created, updated, noChange, errors, rejected, conflicts;
	private String firstKey, lastKey;

	/** Folds in one item's record key; a null or blank key leaves first/last unchanged. */
	public void addKey(final String pKey)
	{
		if (pKey == null || pKey.isEmpty())
		{
			return;
		}
		if (this.firstKey == null)
		{
			this.firstKey = pKey;
		}
		this.lastKey = pKey;
	}

	public void created()
	{
		this.created++;
	}

	public void updated()
	{
		this.updated++;
	}

	public void noChange()
	{
		this.noChange++;
	}

	public void error()
	{
		this.errors++;
	}

	public void rejected()
	{
		this.rejected++;
	}

	/** Adds the conflicts one item raised (audit C-41): the health metrics count them from the batch line. */
	public void conflicts(final int pCount)
	{
		this.conflicts += Math.max(0, pCount);
	}

	public int getConflicts()
	{
		return this.conflicts;
	}

	public int getCreated()
	{
		return this.created;
	}

	public int getUpdated()
	{
		return this.updated;
	}

	public int getNoChange()
	{
		return this.noChange;
	}

	public int getErrors()
	{
		return this.errors;
	}

	public int getRejected()
	{
		return this.rejected;
	}

	/** Items that needed a write attempt (created + updated + the ones that ended up in error). */
	public int attempted()
	{
		return this.created + this.updated + this.errors;
	}

	public int total()
	{
		return this.created + this.updated + this.noChange + this.errors + this.rejected;
	}

	/** {@code "<firstKey>..<lastKey>"}, the single key when equal, or null when no key was ever seen. */
	public String recordKey()
	{
		if (this.firstKey == null)
		{
			return null;
		}
		return this.firstKey.equals(this.lastKey) ? this.firstKey : this.firstKey + ".." + this.lastKey;
	}

	public String message()
	{
		return "batch of " + this.total() + ": created=" + this.created + " updated=" + this.updated
			+ " noChange=" + this.noChange + " errors=" + this.errors + " rejected=" + this.rejected;
	}

	/**
	 * {@code ERROR} when every write this batch attempted failed, {@code PARTIAL} when some items
	 * errored or were rejected (skipped) but at least one write succeeded, else {@code OK}.
	 */
	public SyncResult.Status status()
	{
		if (this.errors > 0 && this.created == 0 && this.updated == 0)
		{
			return SyncResult.Status.ERROR;
		}
		if (this.errors > 0 || this.rejected > 0)
		{
			return SyncResult.Status.PARTIAL;
		}
		return SyncResult.Status.OK;
	}

	/** True when this batch attempted at least one write and every one of them failed (the circuit breaker's signal). */
	public boolean fullyFailed()
	{
		return this.errors > 0 && this.created == 0 && this.updated == 0;
	}
}
