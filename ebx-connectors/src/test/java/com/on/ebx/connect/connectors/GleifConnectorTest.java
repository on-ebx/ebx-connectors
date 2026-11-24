package com.on.ebx.connect.connectors;

import java.net.InetSocketAddress;

import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Contract check for GLEIF's non-probeable API root. */
public final class GleifConnectorTest
{
	private GleifConnectorTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String previousAllowList = System.getProperty(Egress.PROPERTY);
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final Probe probe = new Probe();
		server.createContext("/api/v1/lei-records", probe);
		server.start();
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.getAddress().getPort());
			final ConnectorConfig config = new ConnectorConfig();
			config.setId("GLEIF");
			config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
			config.setAuthType(ConnectorConfig.AuthType.NONE);
			final GleifConnector connector = new GleifConnector();
			connector.configure(config);
			final ConnectionTestResult result = connector.testConnection(null);
			check(result.isSuccessful(), "GLEIF health probe did not succeed");
			check("HEAD".equals(probe.method), "GLEIF health probe was not a read-only HEAD request");
			check("/api/v1/lei-records".equals(probe.path), "GLEIF health probe did not target lei-records");
		}
		finally
		{
			server.stop(0);
			if (previousAllowList == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, previousAllowList);
		}
		System.out.println("PASS: GLEIF connection probe uses the read-only lei-records endpoint");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	private static final class Probe implements HttpHandler
	{
		String method;
		String path;

		@Override
		public void handle(final HttpExchange pExchange)
		{
			this.method = pExchange.getRequestMethod();
			this.path = pExchange.getRequestURI().getPath();
			try
			{
				pExchange.sendResponseHeaders(204, -1);
			}
			catch (final java.io.IOException ex)
			{
				throw new IllegalStateException(ex);
			}
			finally
			{
				pExchange.close();
			}
		}
	}
}
