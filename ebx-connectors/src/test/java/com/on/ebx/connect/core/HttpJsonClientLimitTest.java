package com.on.ebx.connect.core;

import java.io.IOException;
import java.util.Collections;

import com.on.ebx.connect.test.MockSaasHttpServer;

/** Audit C-16: the main HTTP path has a default response ceiling, configurable, with a clear error; an explicit limit is unchanged. */
public final class HttpJsonClientLimitTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		check(HttpJsonClient.parseMaxResponseBytes(null) == HttpJsonClient.DEFAULT_MAX_RESPONSE_BYTES, "default when unset");
		check(HttpJsonClient.parseMaxResponseBytes("2048") == 2048 && HttpJsonClient.parseMaxResponseBytes(" 10 ") == 10, "a number");
		check(HttpJsonClient.parseMaxResponseBytes("0") == 0, "0 removes the ceiling");
		check(HttpJsonClient.parseMaxResponseBytes("-1") == HttpJsonClient.DEFAULT_MAX_RESPONSE_BYTES && HttpJsonClient.parseMaxResponseBytes("big") == HttpJsonClient.DEFAULT_MAX_RESPONSE_BYTES,
			"unreadable or negative keeps the default");
		check(HttpJsonClient.parseMaxResponseBytes("99999999999") == Integer.MAX_VALUE, "clamped to int");
		check(HttpJsonClient.DEFAULT_MAX_RESPONSE_BYTES == 134217728, "default 128 MiB");

		final StringBuilder big = new StringBuilder("{\"data\":\"");
		for (int i = 0; i < 5000; i++) big.append("0123456789");
		big.append("\"}");
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final String previousEgress = System.getProperty(Egress.PROPERTY);
		final String previousLimit = System.getProperty(HttpJsonClient.MAX_RESPONSE_BYTES_PROPERTY);
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
			final HttpJsonClient client = new HttpJsonClient(5000, 5000, "test");
			final String url = server.baseUrl() + "/big";
			final int size = big.length();

			System.setProperty(HttpJsonClient.MAX_RESPONSE_BYTES_PROPERTY, String.valueOf(size / 2));
			server.enqueue("/big", new MockSaasHttpServer.Response(200, big.toString()));
			try
			{
				client.send("GET", url, Collections.<String, String>emptyMap(), null);
				throw new AssertionError("an answer above the default ceiling was read");
			}
			catch (final IOException expected)
			{
				check(expected.getMessage().contains("exceeds byte limit " + (size / 2)) && expected.getMessage().contains(HttpJsonClient.MAX_RESPONSE_BYTES_PROPERTY),
					"clear error naming the property: " + expected.getMessage());
			}

			System.setProperty(HttpJsonClient.MAX_RESPONSE_BYTES_PROPERTY, String.valueOf(size * 2));
			server.enqueue("/big", new MockSaasHttpServer.Response(200, big.toString()));
			check(client.send("GET", url, Collections.<String, String>emptyMap(), null).isSuccess(), "an answer under the ceiling is read");

			System.setProperty(HttpJsonClient.MAX_RESPONSE_BYTES_PROPERTY, "0");
			server.enqueue("/big", new MockSaasHttpServer.Response(200, big.toString()));
			check(client.send("GET", url, Collections.<String, String>emptyMap(), null).isSuccess(), "0 removes the ceiling");

			System.setProperty(HttpJsonClient.MAX_RESPONSE_BYTES_PROPERTY, "10");
			server.enqueue("/big", new MockSaasHttpServer.Response(200, big.toString()));
			check(client.sendBytes("GET", url, Collections.<String, String>emptyMap(), null, size * 2).isSuccess(), "an explicit limit wins over the default ceiling");
			server.enqueue("/big", new MockSaasHttpServer.Response(200, big.toString()));
			try
			{
				client.sendBytes("GET", url, Collections.<String, String>emptyMap(), null, 100);
				throw new AssertionError("an explicit limit was ignored");
			}
			catch (final IOException expected)
			{
				check(expected.getMessage().contains("byte limit 100") && !expected.getMessage().contains("default ceiling"), "explicit limit message: " + expected.getMessage());
			}
		}
		finally
		{
			server.close();
			restore(Egress.PROPERTY, previousEgress);
			restore(HttpJsonClient.MAX_RESPONSE_BYTES_PROPERTY, previousLimit);
		}
		System.out.println("PASS: HttpJsonClient default response ceiling (property, error, explicit limit)");
	}

	private static void restore(final String pName, final String pValue)
	{
		if (pValue == null) System.clearProperty(pName);
		else System.setProperty(pName, pValue);
	}

	private static void check(final boolean pValue, final String pMessage)
	{
		if (!pValue) throw new AssertionError(pMessage);
	}
}
