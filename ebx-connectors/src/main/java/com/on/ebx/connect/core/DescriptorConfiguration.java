package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConnectorDescriptor;

/** Shared, EBX-independent interpretation of connector configuration descriptors. */
public final class DescriptorConfiguration
{
	private DescriptorConfiguration() { }

	/** Properties applicable to the current configuration, in descriptor display order. */
	public static List<ConfigurationPropertyDefinition> visibleProperties(final ConnectorDescriptor pDescriptor,
		final Map<String, ?> pValues)
	{
		if (pDescriptor == null) return Collections.emptyList();
		final Map<String, ?> values = pValues == null ? Collections.<String, Object>emptyMap() : pValues;
		final List<ConfigurationPropertyDefinition> result = new ArrayList<ConfigurationPropertyDefinition>();
		for (final ConfigurationPropertyDefinition property : pDescriptor.getConfigurationProperties())
			if (property.isVisible(values)) result.add(property);
		return Collections.unmodifiableList(result);
	}

	/** Non-secret descriptor defaults suitable for additive persistence into connector properties. */
	public static Map<String, String> persistableDefaults(final ConnectorDescriptor pDescriptor)
	{
		if (pDescriptor == null) return Collections.emptyMap();
		final Map<String, String> defaults = new LinkedHashMap<String, String>();
		for (final ConfigurationPropertyDefinition property : pDescriptor.getConfigurationProperties())
		{
			if (!property.isSecret() && property.getDefaultValue() != null)
				defaults.put(property.getName(), String.valueOf(property.getDefaultValue()));
		}
		return Collections.unmodifiableMap(defaults);
	}

	public static String category(final ConnectorDescriptor pDescriptor)
	{
		return pDescriptor == null ? null : trim(pDescriptor.getCategory());
	}

	public static Integer trustLevel(final ConnectorDescriptor pDescriptor)
	{
		return pDescriptor == null || pDescriptor.getDefaults() == null ? null
			: Integer.valueOf(pDescriptor.getDefaults().getTrustLevel());
	}

	private static String trim(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? null : pValue.trim();
	}
}
