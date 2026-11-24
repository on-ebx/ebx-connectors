package com.on.ebx.connect.spi;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Descriptor-driven, side-effect-free validation of connector configuration. */
public final class ConnectorConfigurationValidator
{
	private ConnectorConfigurationValidator() { }

	public static ConfigurationValidationResult validate(final ConnectorDescriptor pDescriptor,
		final ConnectorConfig pConfig)
	{
		if (pDescriptor == null) throw new IllegalArgumentException("descriptor is required");
		if (pConfig == null) throw new IllegalArgumentException("configuration is required");
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>();
		final List<ConfigurationValidationIssue> warnings = new ArrayList<ConfigurationValidationIssue>();
		if (blank(pConfig.getBaseUrl()))
		{
			issues.add(new ConfigurationValidationIssue("baseUrl", "A base URL is required"));
		}
		else if (!validUrl(pConfig.getBaseUrl()))
		{
			issues.add(new ConfigurationValidationIssue("baseUrl", "Must be an absolute URL"));
		}
		if (pConfig.getProblem() != null)
		{
			issues.add(new ConfigurationValidationIssue(null, "A configured secret or resource cannot be resolved"));
		}
		validateAuthentication(pConfig, issues);

		final Map<String, Object> values = new LinkedHashMap<String, Object>();
		values.putAll(pConfig.getProperties());
		values.put("baseUrl", pConfig.getBaseUrl());
		values.put("authType", pConfig.getAuthType().name());
		for (final ConfigurationPropertyDefinition definition : pDescriptor.getConfigurationProperties())
		{
			if (!definition.isVisible(values)) continue;
			for (final String key : keysOf(pDescriptor, definition, pConfig))
			{
				final String value = pConfig.getProperties().get(key);
				if (blank(value))
				{
					if (definition.isRequired() && definition.getDefaultValue() == null)
					{
						issues.add(new ConfigurationValidationIssue(key, "A value is required"));
					}
					continue;
				}
				if (definition.isSecret() && pConfig.isPlaceholder(value))
				{
					issues.add(new ConfigurationValidationIssue(key, "A secret value is required"));
					continue;
				}
				final List<ConfigurationValidationIssue> typeIssues = new ArrayList<ConfigurationValidationIssue>();
				validateType(definition, value.trim(), typeIssues);
				for (final ConfigurationValidationIssue issue : typeIssues)
				{
					issues.add(new ConfigurationValidationIssue(key, issue.getMessage()));
				}
				final String deprecation = definition.getDeprecationMessage(value);
				if (deprecation != null)
				{
					warnings.add(new ConfigurationValidationIssue(key, deprecation));
				}
			}
		}
		return new ConfigurationValidationResult(issues, warnings);
	}

	/**
	 * The configuration keys a definition applies to. A per-operation pattern such as
	 * {@code op.<name>.path} is not a key of its own: it applies to every operation the configuration
	 * declares (an operation being any {@code op.X.<suffix>} key whose suffix is one of the descriptor's
	 * patterns), and to none when the configuration declares no operation.
	 */
	private static List<String> keysOf(final ConnectorDescriptor pDescriptor, final ConfigurationPropertyDefinition pDefinition,
		final ConnectorConfig pConfig)
	{
		final String pattern = pDefinition.getName();
		final int at = pattern.indexOf("<name>");
		if (at < 0)
		{
			return java.util.Collections.singletonList(pattern);
		}
		final String prefix = pattern.substring(0, at);
		final java.util.Set<String> suffixes = new java.util.LinkedHashSet<String>();
		for (final ConfigurationPropertyDefinition other : pDescriptor.getConfigurationProperties())
		{
			final int otherAt = other.getName().indexOf("<name>");
			if (otherAt >= 0 && other.getName().substring(0, otherAt).equals(prefix))
			{
				suffixes.add(other.getName().substring(otherAt + "<name>".length()));
			}
		}
		final java.util.Set<String> operations = new java.util.TreeSet<String>();
		for (final String key : pConfig.getProperties().keySet())
		{
			if (!key.startsWith(prefix))
			{
				continue;
			}
			final String rest = key.substring(prefix.length());
			for (final String suffix : suffixes)
			{
				if (rest.length() > suffix.length() && rest.endsWith(suffix))
				{
					operations.add(rest.substring(0, rest.length() - suffix.length()));
				}
			}
		}
		final List<String> keys = new ArrayList<String>();
		for (final String operation : operations)
		{
			keys.add(prefix + operation + pattern.substring(at + "<name>".length()));
		}
		return keys;
	}

	private static void validateAuthentication(final ConnectorConfig pConfig,
		final List<ConfigurationValidationIssue> pIssues)
	{
		switch (pConfig.getAuthType())
		{
		case BASIC:
			// Companies House represents its API key as the Basic user during configure.
			if (pConfig.isPlaceholder(pConfig.getApiKey())
				&& (blank(pConfig.getUsername()) || pConfig.isPlaceholder(pConfig.getPassword())))
			{
				pIssues.add(new ConfigurationValidationIssue("authentication", "Basic credentials are incomplete"));
			}
			break;
		case API_KEY_HEADER:
		case BEARER:
		case OAUTH2:
			if (pConfig.isPlaceholder(pConfig.getApiKey()))
			{
				pIssues.add(new ConfigurationValidationIssue("authentication", "An authentication secret is required"));
			}
			break;
		case NONE:
		default:
			break;
		}
	}

	private static void validateType(final ConfigurationPropertyDefinition pDefinition, final String pValue,
		final List<ConfigurationValidationIssue> pIssues)
	{
		try
		{
			switch (pDefinition.getType())
			{
			case BOOLEAN:
				if (!"true".equalsIgnoreCase(pValue) && !"false".equalsIgnoreCase(pValue))
					throw new IllegalArgumentException("Must be true or false");
				break;
			case INTEGER:
				Long.parseLong(pValue);
				break;
			case URL:
				if (!validUrl(pValue)) throw new IllegalArgumentException("Must be an absolute URL");
				break;
			case ENUMERATION:
				if (!pDefinition.getEnumerationValues().contains(pValue))
					throw new IllegalArgumentException("Must be one of the declared values");
				break;
			case JSON:
				new ObjectMapper().readTree(pValue);
				break;
			default:
				break;
			}
		}
		catch (final Exception ex)
		{
			pIssues.add(new ConfigurationValidationIssue(pDefinition.getName(), ex.getMessage()));
		}
	}

	private static boolean validUrl(final String pValue)
	{
		if (pValue != null && pValue.trim().matches(
			"[A-Za-z0-9._-]+:[0-9]+(?:\\s*,\\s*[A-Za-z0-9._-]+:[0-9]+)*"))
		{
			// Messaging and database transports commonly use one or more host:port endpoints.
			return true;
		}
		try
		{
			final URI uri = new URI(pValue);
			return uri.isAbsolute() && uri.getScheme() != null;
		}
		catch (final Exception ex) { return false; }
	}

	private static boolean blank(final String pValue) { return pValue == null || pValue.trim().isEmpty(); }
}
