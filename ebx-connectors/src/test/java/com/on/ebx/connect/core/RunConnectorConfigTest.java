package com.on.ebx.connect.core;

import com.on.ebx.connect.spi.ConnectorConfig;

/** Audit C-34: what the run's cache keeps of a connector's configuration. */
public final class RunConnectorConfigTest
{
	private RunConnectorConfigTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final TransformationRunCache cache = new TransformationRunCache();
		check(cache.connectorConfig("GLEIF") == null, "nothing is known before the first read");
		cache.rememberConnectorConfig("GLEIF", null);
		check(cache.connectorConfig("GLEIF") == null, "a connector that was not found is not kept: the next call asks again");
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("GLEIF");
		cache.rememberConnectorConfig("GLEIF", config);
		check(cache.connectorConfig("GLEIF") == config, "the configuration read is the one every later row gets");
		check(cache.connectorConfig("Sirene") == null, "another connector is another entry");
		check(new TransformationRunCache().connectorConfig("GLEIF") == null, "another run starts with nothing");
		System.out.println("PASS: the run keeps the connector configuration it read, never a missing one");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
