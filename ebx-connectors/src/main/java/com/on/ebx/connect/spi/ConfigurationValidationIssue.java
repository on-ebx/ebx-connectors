package com.on.ebx.connect.spi;

/** One safe configuration problem. Values are deliberately never retained or rendered. */
public final class ConfigurationValidationIssue
{
	private final String propertyName;
	private final String message;

	public ConfigurationValidationIssue(final String pPropertyName, final String pMessage)
	{
		this.propertyName = pPropertyName;
		this.message = pMessage;
	}

	public String getPropertyName() { return this.propertyName; }
	public String getMessage() { return this.message; }

	@Override
	public String toString()
	{
		return (this.propertyName == null ? "configuration" : this.propertyName) + ": " + this.message;
	}
}
