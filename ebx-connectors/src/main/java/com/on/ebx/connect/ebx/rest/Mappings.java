package com.on.ebx.connect.ebx.rest;

import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.MappingDefinition;

/** Lookups shared by the REST resource. */
public final class Mappings
{
	private Mappings()
	{
	}

	/** The feed mapping named by the path ({@code 12} or {@code Customers from CRM}). */
	public static MappingDefinition feed(final ConnectorRepository pRepo, final String pPathInfo)
	{
		final String name = pPathInfo == null ? "" : pPathInfo.replaceFirst("^/+", "").replaceAll("/+$", "");
		if (name.isEmpty())
		{
			return null;
		}
		String decoded = name;
		try
		{
			decoded = java.net.URLDecoder.decode(name, "UTF-8");
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			// keep the raw name
		}
		if (decoded.matches("[0-9]+"))
		{
			final MappingDefinition byId = pRepo.mapping(Integer.parseInt(decoded));
			return byId != null && byId.isFeed() ? byId : null;
		}
		for (final MappingDefinition mapping : pRepo.mappings())
		{
			if (mapping.isFeed() && decoded.equalsIgnoreCase(mapping.getLabel()))
			{
				return mapping;
			}
		}
		return null;
	}

	public static MappingDefinition mappingNamed(final ConnectorRepository pRepo, final String pName)
	{
		// no name (a caller that passed the mapping under another argument) is "no mapping", not a NullPointerException
		if (pName == null || pName.trim().isEmpty())
		{
			return null;
		}
		String name = pName;
		try
		{
			name = java.net.URLDecoder.decode(pName, "UTF-8");
		}
		catch (final Exception ex)
		{
			// keep as is
		}
		if (name.matches("[0-9]+"))
		{
			return pRepo.mapping(Integer.parseInt(name));
		}
		MappingDefinition found = null;
		int hits = 0;
		for (final MappingDefinition m : pRepo.mappings())
		{
			if (m.getLabel() != null && m.getLabel().equalsIgnoreCase(name))
			{
				return m;
			}
			if (name.indexOf('/') > 0 && (m.getConnectorId() + "/" + m.getOperation()).equalsIgnoreCase(name) && !m.isChild())
			{
				found = m;
				hits++;
			}
		}
		return hits == 1 ? found : null;
	}
}
