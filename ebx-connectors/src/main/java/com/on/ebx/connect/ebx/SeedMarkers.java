package com.on.ebx.connect.ebx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the installer has already created in this repository, and the rule that follows from it (audit C-26):
 * an item created once is never created again, so a connector or a mapping the customer deleted stays deleted.
 * <p>
 * The state lives in the {@code SeedMarker} table, one row per item: {@code connector:<id>} for a seeded connector,
 * {@code mappings:<group>} for a group of demonstration mappings seeded apart from their connector, {@code migration:<name>}
 * for a data migration that must run once, and {@code baseline} for the first start of this version. At that first
 * start every connector already in the repository counts as seeded - whoever created it - because an item that exists
 * then and is deleted later must not come back; the installer used to put it back at every start, so that is
 * what the repository holds. An item absent at that moment, and never seeded, is still created once.
 * <p>
 * A model without the table (a repository whose model was not reloaded) has no markers; every rule then reads like
 * the old one - an absent item is created - and nothing is recorded.
 * <p>
 * Pure: no EBX type ({@code SeedMarkersTest}); the installer reads and writes the table.
 */
final class SeedMarkers
{
	/** The marker of the first start of the version that records markers. */
	static final String BASELINE = "baseline";

	/** What {@code -Debx.connectors.seed} asks for. */
	enum Mode
	{
		/** Create the demonstration connectors, mappings and catalogue cards that were never created (the default). */
		ALL,
		/** Create no connector and no mapping: a production repository holds only what its owners made. */
		NONE
	}

	private final boolean supported;
	private final Set<String> items = new HashSet<String>();

	/** @param pSupported whether the model has the table; false = nothing is remembered */
	SeedMarkers(final boolean pSupported)
	{
		this.supported = pSupported;
	}

	static String connector(final String pId)
	{
		return "connector:" + pId;
	}

	static String mappings(final String pGroup)
	{
		return "mappings:" + pGroup;
	}

	static String migration(final String pName)
	{
		return "migration:" + pName;
	}

	/** {@code all} (default, also for any unknown value) or {@code none}, case and spaces ignored. */
	static Mode mode(final String pProperty)
	{
		return pProperty != null && "none".equals(pProperty.trim().toLowerCase(Locale.ENGLISH)) ? Mode.NONE : Mode.ALL;
	}

	boolean isSupported()
	{
		return this.supported;
	}

	void add(final String pItem)
	{
		if (this.supported)
		{
			this.items.add(pItem);
		}
	}

	boolean has(final String pItem)
	{
		return this.items.contains(pItem);
	}

	int size()
	{
		return this.items.size();
	}

	/** True once the first start of this version has recorded what existed. */
	boolean baselineDone()
	{
		return this.has(SeedMarkers.BASELINE);
	}

	/** The installer may create connector {@code pId}: it has no row and was never seeded. */
	boolean mayCreateConnector(final String pId, final boolean pRowExists)
	{
		return !pRowExists && !this.has(SeedMarkers.connector(pId));
	}

	/** The installer may add the mappings of {@code pGroup}: some are missing and the group was never seeded. */
	boolean mayAddMappings(final String pGroup, final boolean pSomeMissing)
	{
		return pSomeMissing && !this.has(SeedMarkers.mappings(pGroup));
	}

	/**
	 * The markers the first start records: every connector in the repository, the mapping groups whose mappings are
	 * all there, and {@link #BASELINE} itself. Never an item already recorded.
	 */
	List<String> baseline(final Collection<String> pConnectorIds, final Collection<String> pCompleteMappingGroups)
	{
		final List<String> out = new ArrayList<String>();
		for (final String id : pConnectorIds)
		{
			final String item = SeedMarkers.connector(id);
			if (!this.has(item) && !out.contains(item))
			{
				out.add(item);
			}
		}
		for (final String group : pCompleteMappingGroups)
		{
			final String item = SeedMarkers.mappings(group);
			if (!this.has(item) && !out.contains(item))
			{
				out.add(item);
			}
		}
		out.add(SeedMarkers.BASELINE);
		return out;
	}
}
