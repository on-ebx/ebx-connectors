package com.on.ebx.connect.spi;

/**
 * Checkpoint strategy an operation can use when synchronizing records.
 *
 * <p>This is capability metadata only. Advertising a strategy does not itself implement
 * checkpoint persistence or change how an operation executes. (The engine's own row watermark for an
 * import feed - {@link #TIMESTAMP}, {@link #MONOTONIC_ID}, {@link #ORDERED_STRING} - needs no
 * advertisement: it reads the change column from the returned rows.)</p>
 */
public enum SynchronizationStrategy
{
	/** Re-read the complete source selection on each run. */
	FULL,
	/** Continue from a source-maintained last-modified timestamp. */
	TIMESTAMP,
	/** Continue from a strictly increasing identifier or sequence. */
	MONOTONIC_ID,
	/**
	 * Continue from the highest value, in plain string order, of a source column (fixed-width identifiers,
	 * ULIDs, ISO-8601 text). Volume lot V2-D: like {@link #TIMESTAMP} and {@link #MONOTONIC_ID}, a row
	 * watermark of an import feed.
	 */
	ORDERED_STRING,
	/** Continue from an opaque cursor supplied by the external provider. */
	VENDOR_CURSOR,
	/** Continue from an OData delta link or delta token. */
	ODATA_DELTA,
	/** Continue from EBX-native delta or history semantics. */
	EBX_DELTA,
	/** Compare or replace a versioned snapshot as one synchronization unit. */
	SNAPSHOT
}
