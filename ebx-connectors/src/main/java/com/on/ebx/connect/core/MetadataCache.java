package com.on.ebx.connect.core;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Small in-memory metadata cache with a deliberately short, bounded time to live. */
public final class MetadataCache<T>
{
	public static final long DEFAULT_TTL_MILLIS = 30000L;
	public static final long MAX_TTL_MILLIS = 300000L;

	private final long ttlMillis;
	private final LongSupplier clock;
	private final ConcurrentHashMap<MetadataCacheKey, Entry<T>> entries =
		new ConcurrentHashMap<MetadataCacheKey, Entry<T>>();

	public MetadataCache()
	{
		this(DEFAULT_TTL_MILLIS, System::currentTimeMillis);
	}

	public MetadataCache(final long pTtlMillis)
	{
		this(pTtlMillis, System::currentTimeMillis);
	}

	/** Public for deterministic testing and embedders with an existing monotonic time source. */
	public MetadataCache(final long pTtlMillis, final LongSupplier pClock)
	{
		if (pTtlMillis <= 0L) throw new IllegalArgumentException("TTL must be positive");
		if (pClock == null) throw new IllegalArgumentException("clock must not be null");
		this.ttlMillis = Math.min(pTtlMillis, MAX_TTL_MILLIS);
		this.clock = pClock;
	}

	public T get(final MetadataCacheKey pKey)
	{
		requireKey(pKey);
		final Entry<T> entry = this.entries.get(pKey);
		if (entry == null) return null;
		if (expired(entry, this.clock.getAsLong()))
		{
			this.entries.remove(pKey, entry);
			return null;
		}
		return entry.value;
	}

	public void put(final MetadataCacheKey pKey, final T pValue)
	{
		requireKey(pKey);
		if (pValue == null) throw new IllegalArgumentException("value must not be null");
		this.entries.put(pKey, new Entry<T>(pValue, this.clock.getAsLong()));
	}

	public void invalidate(final MetadataCacheKey pKey)
	{
		requireKey(pKey);
		this.entries.remove(pKey);
	}

	public int purgeExpired()
	{
		final long now = this.clock.getAsLong();
		int removed = 0;
		for (final Iterator<Map.Entry<MetadataCacheKey, Entry<T>>> iterator = this.entries.entrySet().iterator(); iterator.hasNext();)
		{
			final Map.Entry<MetadataCacheKey, Entry<T>> item = iterator.next();
			if (expired(item.getValue(), now) && this.entries.remove(item.getKey(), item.getValue())) removed++;
		}
		return removed;
	}

	public void clear() { this.entries.clear(); }
	public int size() { return this.entries.size(); }
	public long getTtlMillis() { return this.ttlMillis; }

	private boolean expired(final Entry<T> pEntry, final long pNow)
	{
		return pNow - pEntry.createdAtMillis >= this.ttlMillis;
	}

	private static void requireKey(final MetadataCacheKey pKey)
	{
		if (pKey == null) throw new IllegalArgumentException("key must not be null");
	}

	private static final class Entry<T>
	{
		private final T value;
		private final long createdAtMillis;
		private Entry(final T pValue, final long pCreatedAtMillis)
		{
			this.value = pValue;
			this.createdAtMillis = pCreatedAtMillis;
		}
	}
}
