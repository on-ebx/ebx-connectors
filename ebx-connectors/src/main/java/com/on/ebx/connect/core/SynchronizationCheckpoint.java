package com.on.ebx.connect.core;

import java.util.Date;

import com.on.ebx.connect.spi.SynchronizationStrategy;

/** Immutable restart-safe progress for one mapping. Policy remains on {@link MappingDefinition}. */
public final class SynchronizationCheckpoint
{
	private final int mappingId;
	private final SynchronizationStrategy strategy;
	private final String value;
	private final Date updatedAt;
	private final long version;

	public SynchronizationCheckpoint(final int pMappingId, final SynchronizationStrategy pStrategy,
		final String pValue, final Date pUpdatedAt, final long pVersion)
	{
		if (pStrategy == null || pStrategy == SynchronizationStrategy.FULL)
			throw new IllegalArgumentException("A checkpoint requires an incremental strategy");
		this.mappingId = pMappingId;
		this.strategy = pStrategy;
		this.value = pValue == null ? "" : pValue;
		this.updatedAt = pUpdatedAt == null ? null : new Date(pUpdatedAt.getTime());
		this.version = pVersion;
	}

	public int getMappingId() { return this.mappingId; }
	public SynchronizationStrategy getStrategy() { return this.strategy; }
	public String getValue() { return this.value; }
	public Date getUpdatedAt() { return this.updatedAt == null ? null : new Date(this.updatedAt.getTime()); }
	public long getVersion() { return this.version; }
}
