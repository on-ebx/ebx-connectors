package com.on.ebx.connect.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationPropertyVisibility;
import com.on.ebx.connect.spi.ConnectorDefaults;
import com.on.ebx.connect.spi.ConnectorDescriptor;

/** Deterministic regression coverage for descriptor-driven EBX configuration metadata. */
public final class DescriptorConfigurationTest
{
	public static void main(final String[] pArgs)
	{
		final ConfigurationPropertyDefinition endpoint = ConfigurationPropertyDefinition.builder("region", ConfigurationPropertyType.STRING)
			.label("Region").defaultValue("eu-west-1").build();
		final ConfigurationPropertyDefinition secret = ConfigurationPropertyDefinition.builder("token", ConfigurationPropertyType.SECRET)
			.defaultValue(null).build();
		final ConfigurationPropertyDefinition oauth = ConfigurationPropertyDefinition.builder("clientId", ConfigurationPropertyType.STRING)
			.visibleWhen(ConfigurationPropertyVisibility.equalTo("authType", "OAUTH2")).build();
		final ConnectorDescriptor descriptor = ConnectorDescriptor.builder("probe", "Probe", "BUSINESS_APPS")
			.defaults(new ConnectorDefaults(1000, 2000, 1, 73)).configurationProperty(endpoint)
			.configurationProperty(secret).configurationProperty(oauth).build();

		final List<ConfigurationPropertyDefinition> basic = DescriptorConfiguration.visibleProperties(descriptor,
			Collections.<String, Object>singletonMap("authType", "BASIC"));
		check(basic.size() == 2 && !contains(basic, "clientId"), "conditional property rendered for BASIC");
		final List<ConfigurationPropertyDefinition> oauthProperties = DescriptorConfiguration.visibleProperties(descriptor,
			Collections.<String, Object>singletonMap("authType", "OAUTH2"));
		check(contains(oauthProperties, "clientId"), "conditional property omitted for OAuth");

		final Map<String, String> defaults = DescriptorConfiguration.persistableDefaults(descriptor);
		check(defaults.size() == 1 && "eu-west-1".equals(defaults.get("region")), "safe defaults not derived");
		check(!defaults.containsKey("token"), "secret default exposed");
		check("BUSINESS_APPS".equals(DescriptorConfiguration.category(descriptor)), "category not derived");
		check(Integer.valueOf(73).equals(DescriptorConfiguration.trustLevel(descriptor)), "trust not derived");
		System.out.println("PASS: descriptor-driven visibility, safe defaults, category, and trust metadata");
	}

	private static boolean contains(final List<ConfigurationPropertyDefinition> pValues, final String pName)
	{
		for (final ConfigurationPropertyDefinition value : pValues) if (pName.equals(value.getName())) return true; return false;
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
