package com.on.ebx.connect.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.Operation;

/** Offline check for the registry's pre-configuration validation boundary. */
public final class ConnectorRegistryValidationTest
{
	private ConnectorRegistryValidationTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("validation-probe");
		config.setType("probe");
		config.setBaseUrl("https://example.invalid");
		config.setProperty("account", "");
		config.setProperty("credential", "sensitive-value-must-not-appear");

		try
		{
			ConnectorRegistry.validateConfiguration(new ProbeConnector(), config, config.getId());
			throw new AssertionError("Invalid connector configuration was accepted");
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().contains("account: A value is required"),
				"Validation error does not identify the invalid field: " + expected.getMessage());
			check(!expected.getMessage().contains("sensitive-value-must-not-appear"),
				"Validation error exposed a configuration value");
		}

		config.setProperty("account", "configured");
		ConnectorRegistry.validateConfiguration(new ProbeConnector(), config, config.getId());
		config.setBaseUrl("broker-1.internal:9092,broker-2.internal:9092");
		ConnectorRegistry.validateConfiguration(new ProbeConnector(), config, config.getId());
		System.out.println("PASS: registry rejects invalid configuration before connector setup");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	private static final class ProbeConnector implements Connector
	{
		@Override public String getType() { return "probe"; }
		@Override public String getLabel() { return "Probe"; }
		@Override public List<Operation> getOperations() { return Collections.emptyList(); }
		@Override public void configure(final ConnectorConfig pConfig) { throw new AssertionError("configure must not be called"); }
		@Override public JsonNode execute(final ConnectorContext pContext, final String pOperation,
			final Map<String, String> pInputs, final JsonNode pPayload) { return null; }
		@Override public ConnectorDescriptor getDescriptor()
		{
			return ConnectorDescriptor.builder(getType(), getLabel(), "GENERIC_INTEGRATION")
				.configurationProperty(ConfigurationPropertyDefinition.builder("account", ConfigurationPropertyType.STRING)
					.required(true).build())
				.configurationProperty(ConfigurationPropertyDefinition.builder("credential", ConfigurationPropertyType.SECRET)
					.build())
				.build();
		}
	}
}
