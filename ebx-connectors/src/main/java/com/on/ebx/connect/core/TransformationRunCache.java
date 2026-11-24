package com.on.ebx.connect.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.onwbp.adaptation.AdaptationTable;

/**
 * Volume lot 5, task 4: per-run caches behind the transformation pipeline.
 * <ul>
 * <li>a resolved {@link AdaptationTable} per {@code (dataspace, dataset, path)} - {@link Transformations#table}
 * otherwise re-resolves the table on every call, and with a dataset but no dataspace this recurses
 * over the whole dataspace tree ({@code DatasetLocator.search});</li>
 * <li>a parsed {@link TransformationPipeline} per pipeline string;</li>
 * <li>a compiled {@link Pattern} per regex string ({@code regex(...)});</li>
 * <li>a bounded memo of {@code lookup(...)} results per {@code (table, field, result, value)}.</li>
 * </ul>
 * One instance lives for exactly one {@code ConnectorService.importDocument} run: created once
 * before the per-item loop, handed to every {@link TransformationContext} of that run through
 * {@link TransformationContext#setCache}, and simply dropped when the run returns - there is no
 * static field anywhere in this class, so nothing leaks across runs or threads.
 */
final class TransformationRunCache
{
	/** Distinguishes "resolved to null / not found" from "never looked up" in a plain {@code Map}. */
	private static final String NULL_MARKER = "\u0000";

	/** Default bound on the lookup(...) memo: a run against a huge reference table must not grow it without limit. */
	private static final int DEFAULT_LOOKUP_CAPACITY = 50000;

	private final Map<String, AdaptationTable> tables = new HashMap<String, AdaptationTable>();
	private final Map<String, TransformationPipeline> pipelines = new HashMap<String, TransformationPipeline>();
	private final Map<String, Pattern> patterns = new HashMap<String, Pattern>();
	private final Map<String, String> lookups;
	private final Map<String, ConnectorConfig> connectors = new HashMap<String, ConnectorConfig>();

	TransformationRunCache()
	{
		this(TransformationRunCache.DEFAULT_LOOKUP_CAPACITY);
	}

	TransformationRunCache(final int pLookupCapacity)
	{
		this.lookups = new BoundedLruMap(pLookupCapacity);
	}

	// ------------------------------------------------------------------ tables

	boolean hasTable(final String pKey)
	{
		return this.tables.containsKey(pKey);
	}

	AdaptationTable table(final String pKey)
	{
		return this.tables.get(pKey);
	}

	void putTable(final String pKey, final AdaptationTable pTable)
	{
		this.tables.put(pKey, pTable);
	}

	// ------------------------------------------------------------------ connector configuration

	/**
	 * The configuration of a connector as the run first read it (audit C-34): reading it is a record, its properties (one
	 * XPath scan of the property table) and the resolution of every secret reference, which is too much to repeat for
	 * every row of a feed. A configuration changed in EBX while the run is going on is taken into account by the next run.
	 * A connector that cannot be read (unknown, or the read fails) is not remembered: every call asks again, as it always did.
	 * {@link ConnectorService#runConnectorConfig} is the one reader of this memo.
	 */
	ConnectorConfig connectorConfig(final String pConnectorId)
	{
		return this.connectors.get(pConnectorId);
	}

	/** Keeps the configuration the run has read; a connector that was not found (null) is not kept. */
	void rememberConnectorConfig(final String pConnectorId, final ConnectorConfig pConfig)
	{
		if (pConfig != null)
		{
			this.connectors.put(pConnectorId, pConfig);
		}
	}

	// ------------------------------------------------------------------ pipelines

	TransformationPipeline pipeline(final String pText)
	{
		TransformationPipeline parsed = this.pipelines.get(pText);
		if (parsed == null)
		{
			parsed = TransformationPipeline.parse(pText);
			this.pipelines.put(pText, parsed);
		}
		return parsed;
	}

	// ------------------------------------------------------------------ regex patterns

	Pattern pattern(final String pRegex)
	{
		Pattern compiled = this.patterns.get(pRegex);
		if (compiled == null)
		{
			compiled = Pattern.compile(pRegex);
			this.patterns.put(pRegex, compiled);
		}
		return compiled;
	}

	// ------------------------------------------------------------------ lookup(...) memo

	/** @return the cached result, or {@code null} when nothing is cached for this key yet (never confused with a cached "not found": see {@link #putLookupResult}/{@link #wasLookupMiss}). */
	String lookup(final String pKey)
	{
		final String stored = this.lookups.get(pKey);
		return TransformationRunCache.NULL_MARKER.equals(stored) ? null : stored;
	}

	boolean hasLookup(final String pKey)
	{
		return this.lookups.containsKey(pKey);
	}

	void putLookup(final String pKey, final String pValue)
	{
		this.lookups.put(pKey, pValue == null ? TransformationRunCache.NULL_MARKER : pValue);
	}

	/** Bounded LRU (access order): evicts the least-recently-used entry once {@code capacity} is exceeded. */
	private static final class BoundedLruMap extends LinkedHashMap<String, String>
	{
		private final int capacity;

		BoundedLruMap(final int pCapacity)
		{
			super(16, 0.75f, true);
			this.capacity = pCapacity;
		}

		@Override
		protected boolean removeEldestEntry(final Map.Entry<String, String> pEldest)
		{
			return this.size() > this.capacity;
		}
	}
}
