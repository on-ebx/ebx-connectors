package com.on.ebx.connect.core;

import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorAvailability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorConfigurationValidator;
import com.on.ebx.connect.spi.ConnectorDescriptor;

/** Resolves installation/availability before applying descriptor-driven configuration validation. */
public final class ConnectorStatusEvaluator
{
	private ConnectorStatusEvaluator() { }

	public static ConnectorStatusEvaluation evaluate(final ConnectorConfig pConfig,
		final ConnectorRegistry pRegistry)
	{
		if (pConfig == null) throw new IllegalArgumentException("configuration is required");
		final ConnectorRegistry registry = pRegistry == null ? ConnectorRegistry.get() : pRegistry;
		if (!pConfig.isActive())
		{
			return new ConnectorStatusEvaluation(ConnectorStatus.INACTIVE, null, null);
		}
		final Class<? extends Connector> implementation = registry.types().get(pConfig.getType());
		if (implementation == null)
		{
			return new ConnectorStatusEvaluation(ConnectorStatus.UNAVAILABLE, null, null);
		}
		try
		{
			final ConnectorDescriptor descriptor = implementation.newInstance().getDescriptor();
			if (descriptor.getAvailability() == ConnectorAvailability.UNAVAILABLE)
			{
				return new ConnectorStatusEvaluation(ConnectorStatus.UNAVAILABLE, descriptor, null);
			}
			final ConfigurationValidationResult validation = ConnectorConfigurationValidator.validate(descriptor, pConfig);
			return new ConnectorStatusEvaluation(validation.isValid() ? ConnectorStatus.AVAILABLE : ConnectorStatus.MISCONFIGURED,
				descriptor, validation);
		}
		catch (final RuntimeException ex)
		{
			return new ConnectorStatusEvaluation(ConnectorStatus.UNAVAILABLE, null, null);
		}
		catch (final Exception ex)
		{
			return new ConnectorStatusEvaluation(ConnectorStatus.UNAVAILABLE, null, null);
		}
	}
}
