package com.on.ebx.connect.spi;

import java.util.Objects;

/** An optional runtime library or module required by a connector type. */
public final class ConnectorDependency
{
	private final String name;
	private final String minimumVersion;
	private final boolean required;
	private final String description;

	public ConnectorDependency(final String pName, final String pMinimumVersion, final boolean pRequired,
		final String pDescription)
	{
		this.name = requireText(pName, "name");
		this.minimumVersion = trimToNull(pMinimumVersion);
		this.required = pRequired;
		this.description = trimToNull(pDescription);
	}

	public String getName() { return this.name; }
	public String getMinimumVersion() { return this.minimumVersion; }
	public boolean isRequired() { return this.required; }
	public String getDescription() { return this.description; }

	private static String requireText(final String pValue, final String pName)
	{
		final String value = trimToNull(pValue);
		return Objects.requireNonNull(value, pName + " must not be blank");
	}

	private static String trimToNull(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? null : pValue.trim();
	}
}
