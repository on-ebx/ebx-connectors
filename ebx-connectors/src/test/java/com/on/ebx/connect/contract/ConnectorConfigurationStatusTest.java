package com.on.ebx.connect.contract;

import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorStatus;
import com.on.ebx.connect.core.ConnectorStatusEvaluator;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorConfigurationValidator;
import com.on.ebx.connect.spi.ConnectorDescriptor;

/** Offline contracts for descriptor validation and instance status classification. */
public final class ConnectorConfigurationStatusTest
{
	private ConnectorConfigurationStatusTest() { }

	public static void main(final String[] pArgs)
	{
		final ConnectorDescriptor descriptor = ConnectorDescriptor.builder("probe", "Probe", "GENERIC_INTEGRATION")
			.configurationProperty(ConfigurationPropertyDefinition.builder("requiredInteger", ConfigurationPropertyType.INTEGER)
				.required(true).build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("mode", ConfigurationPropertyType.ENUMERATION)
				.enumerationValues("one", "two").build())
			.configurationProperty(ConfigurationPropertyDefinition.builder("credential", ConfigurationPropertyType.SECRET)
				.required(true).build())
			.build();
		final ConnectorConfig invalid = config("probe", true, "https://example.invalid");
		invalid.setProperty("requiredInteger", "not-an-integer");
		invalid.setProperty("mode", "three");
		invalid.setProperty("credential", "TO_BE_SET");
		final ConfigurationValidationResult validation = ConnectorConfigurationValidator.validate(descriptor, invalid);
		check(!validation.isValid(), "Invalid typed values were accepted");
		check(validation.getIssues().size() == 3, "Expected three property problems");
		for (final ConfigurationValidationIssue issue : validation.getIssues())
		{
			check(!issue.toString().contains("TO_BE_SET"), "Validation exposed a secret value");
		}

		final ConnectorConfig inactive = config("missing-type", false, "https://example.invalid");
		check(ConnectorStatusEvaluator.evaluate(inactive, ConnectorRegistry.get()).getStatus() == ConnectorStatus.INACTIVE,
			"Inactive must take precedence");
		final ConnectorConfig missing = config("missing-type", true, "https://example.invalid");
		check(ConnectorStatusEvaluator.evaluate(missing, ConnectorRegistry.get()).getStatus() == ConnectorStatus.UNAVAILABLE,
			"An uninstalled type must be unavailable");
		final ConnectorConfig malformed = config("ban", true, "not a URL");
		check(ConnectorStatusEvaluator.evaluate(malformed, ConnectorRegistry.get()).getStatus() == ConnectorStatus.MISCONFIGURED,
			"An invalid installed configuration must be misconfigured");
		final ConnectorConfig available = config("ban", true, "https://api-adresse.data.gouv.fr");
		check(ConnectorStatusEvaluator.evaluate(available, ConnectorRegistry.get()).getStatus() == ConnectorStatus.AVAILABLE,
			"A valid installed configuration must be available");

		// A deprecated value still works: valid, available, and one warning that names the replacement.
		final ConnectorConfig salesforce = config("salesforce", true, "https://login.salesforce.com");
		salesforce.setProperty("clientId", "consumer-key");
		salesforce.setProperty("oauth.flow", "password");
		final ConfigurationValidationResult deprecated = ConnectorStatusEvaluator.evaluate(salesforce, ConnectorRegistry.get()).getValidation();
		check(deprecated != null && deprecated.isValid(), "A deprecated flow must not block the configuration");
		check(ConnectorStatusEvaluator.evaluate(salesforce, ConnectorRegistry.get()).getStatus() == ConnectorStatus.AVAILABLE,
			"A deprecated flow must leave the connector available");
		check(deprecated.getWarnings().size() == 1 && deprecated.getWarnings().get(0).toString().startsWith("oauth.flow: ")
			&& deprecated.getWarnings().get(0).getMessage().contains("20 February 2027")
			&& deprecated.getWarnings().get(0).getMessage().contains("client_credentials"),
			"The password flow must be reported as deprecated, with its date and replacement: " + deprecated.getWarnings());
		salesforce.setProperty("oauth.flow", "client_credentials");
		check(ConnectorStatusEvaluator.evaluate(salesforce, ConnectorRegistry.get()).getValidation().getWarnings().isEmpty(),
			"client_credentials must raise no warning");
		try
		{
			ConfigurationPropertyDefinition.builder("mode", ConfigurationPropertyType.ENUMERATION).enumerationValues("one")
				.deprecatedValue("two", "gone").build();
			throw new AssertionError("A deprecated value outside the enumeration was accepted");
		}
		catch (final IllegalArgumentException expected) { /* refused, as it should */ }

		System.out.println("PASS: descriptor validation and connector status classification");
	}

	private static ConnectorConfig config(final String pType, final boolean pActive, final String pBaseUrl)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("status-probe");
		config.setType(pType);
		config.setBaseUrl(pBaseUrl);
		config.setActive(pActive);
		return config;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
