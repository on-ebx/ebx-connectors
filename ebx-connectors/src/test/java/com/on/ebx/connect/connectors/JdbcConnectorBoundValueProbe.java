package com.on.ebx.connect.connectors;

/** Test access to the package-private binding of generated SQL parameters (audit C2-05). */
public final class JdbcConnectorBoundValueProbe
{
	private JdbcConnectorBoundValueProbe() { }

	public static Object of(final String pValue)
	{
		return JdbcConnector.boundValue(pValue);
	}
}
