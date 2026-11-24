package com.on.ebx.connect.connectors;

import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConnectorDescriptor;

public final class NetSuiteConnectorTest
{
	private NetSuiteConnectorTest() { }
	public static void main(final String[] args)
	{
		final NetSuiteConnector connector = new NetSuiteConnector();
		final ConnectorDescriptor descriptor = connector.getDescriptor();
		check("netsuite".equals(descriptor.getType()), "type");
		check("BUSINESS_APPS".equals(descriptor.getCategory()), "category");
		check(descriptor.getAuthenticationMethods().contains(AuthenticationMethod.BEARER_TOKEN), "bearer authentication");
		check(connector.getOperations().size() == 5, "operation count");
		boolean account = false;
		for (final com.on.ebx.connect.spi.ConfigurationPropertyDefinition property : descriptor.getConfigurationProperties())
		{
			account |= "account".equals(property.getName());
		}
		check(account, "account property");
		System.out.println("PASS: NetSuite SuiteTalk REST and bounded SuiteQL profile");
	}
	private static void check(final boolean value, final String label)
	{
		if (!value) { throw new AssertionError("NetSuite contract failed: " + label); }
	}
}
