package com.on.ebx.connect.connectors;

import java.net.URL;
import java.util.ServiceLoader;

public final class AzureServiceBusSdkTransportFactoryTest
{
	public static void main(final String[] pArgs)
	{
		AzureServiceBusTransport.Factory found = null;
		for (final AzureServiceBusTransport.Factory provider : ServiceLoader.load(AzureServiceBusTransport.Factory.class))
			if (provider instanceof AzureServiceBusSdkTransportFactory) { found = provider; break; }
		check(found != null, "Azure Service Bus SDK transport provider is not registered");
		final ClassLoader empty = new ClassLoader(null) { };
		check(!AzureServiceBusSdkTransportFactory.sdkAvailable(empty), "SDK probe must honor the supplied class loader");
		final URL registration = AzureServiceBusSdkTransportFactoryTest.class.getClassLoader().getResource(
			"META-INF/services/com.on.ebx.connect.connectors.AzureServiceBusTransport$Factory");
		check(registration != null, "ServiceLoader registration resource missing");
		System.out.println("PASS: Azure Service Bus concrete SDK provider registration and reflective availability probe");
	}
	private static void check(final boolean pValue, final String pMessage) { if (!pValue) throw new AssertionError(pMessage); }
}
