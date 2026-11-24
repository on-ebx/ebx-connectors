package com.on.ebx.connect.core;

import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectorDescriptor;

/** Immutable status, descriptor and safe validation details for a configured connector. */
public final class ConnectorStatusEvaluation
{
	private final ConnectorStatus status;
	private final ConnectorDescriptor descriptor;
	private final ConfigurationValidationResult validation;

	ConnectorStatusEvaluation(final ConnectorStatus pStatus, final ConnectorDescriptor pDescriptor,
		final ConfigurationValidationResult pValidation)
	{
		this.status = pStatus;
		this.descriptor = pDescriptor;
		this.validation = pValidation;
	}

	public ConnectorStatus getStatus() { return this.status; }
	public ConnectorDescriptor getDescriptor() { return this.descriptor; }
	public ConfigurationValidationResult getValidation() { return this.validation; }
}
