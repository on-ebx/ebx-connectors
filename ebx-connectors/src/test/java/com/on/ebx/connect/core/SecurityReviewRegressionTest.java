package com.on.ebx.connect.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Map;

import com.on.ebx.connect.connectors.GenericRestConnector;
import com.on.ebx.connect.spi.ConnectorConfig;

/** Isolated transport-failure and output-boundary regression fixtures. */
public final class SecurityReviewRegressionTest
{
	private static FakeConnection last;
	private static boolean responseFailure;
	private static final java.util.concurrent.CountDownLatch slowEntered = new java.util.concurrent.CountDownLatch(1);
	private static final java.util.concurrent.CountDownLatch releaseSlow = new java.util.concurrent.CountDownLatch(1);
	public static void main(final String[] args) throws Exception
	{
		check("'O''Brien'".equals(Xpath.quote("O'Brien")), "XPath quote escaped incorrectly");
		check("''".equals(Xpath.quote(null)), "XPath null literal changed");
		headerInjectionRejected();
		mcpErrorIsRedacted();
		URL.setURLStreamHandlerFactory(protocol -> "http".equals(protocol) ? new URLStreamHandler()
		{
			@Override protected URLConnection openConnection(final URL url) { return last = new FakeConnection(url); }
		} : null);
		final Method send = HttpJsonClient.class.getDeclaredMethod("sendOnce", String.class, String.class,
			Map.class, byte[].class, int.class);
		send.setAccessible(true);
		final HttpJsonClient client = new HttpJsonClient(100, 100, "test");
		for (final boolean fail : new boolean[] {true, false})
		{
			responseFailure = fail;
			try
			{
				send.invoke(client, "GET", "http://example.test/", Collections.emptyMap(), null, 2);
				throw new AssertionError("Expected I/O or response-limit failure");
			}
			catch (final InvocationTargetException expected)
			{
				check(expected.getCause() instanceof IOException, "Wrong transport failure");
			}
			check(last.disconnected, "HTTP connection leaked on failure");
		}
		oauthRefreshDoesNotHoldGlobalLock(client);
		System.out.println("PASS: HTTP failure cleanup, independent OAuth refresh, header injection rejection, MCP error redaction and XPath literals");
	}

	private static void oauthRefreshDoesNotHoldGlobalLock(final HttpJsonClient client) throws Exception
	{
		final ConnectorConfig slow = tokenConfig("slow", "http://example.test/slow-token");
		ConnectorConfig fast = tokenConfig("fast", "http://example.test/fast-token");
		for (int i = 0; stripe(slow) == stripe(fast); i++) fast = tokenConfig("fast" + i, "http://example.test/fast-token");
		final ConnectorConfig fastConfig = fast;
		final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try
		{
			final java.util.concurrent.Future<OAuth2.Token> slowResult = pool.submit(() -> OAuth2.token(slow, client, null, null));
			check(slowEntered.await(2, java.util.concurrent.TimeUnit.SECONDS), "Slow request never started");
			final OAuth2.Token token = pool.submit(() -> OAuth2.token(fastConfig, client, null, null)).get(2, java.util.concurrent.TimeUnit.SECONDS);
			check("fixture-token".equals(token.accessToken), "Independent refresh failed");
			releaseSlow.countDown();
			slowResult.get(2, java.util.concurrent.TimeUnit.SECONDS);
		}
		finally { releaseSlow.countDown(); pool.shutdownNow(); }
	}

	private static ConnectorConfig tokenConfig(final String id, final String url)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId(id);
		config.setProperty("oauth.tokenUrl", url);
		config.setProperty("oauth.clientId", "fixture-client");
		config.setApiKey("fixture-secret");
		return config;
	}
	private static int stripe(final ConnectorConfig config)
	{
		return ((config.getId() + "|" + Hashing.sha256(config.fingerprint() + "|null")).hashCode() & Integer.MAX_VALUE) % 256;
	}

	private static void headerInjectionRejected() throws Exception
	{
		final GenericRestConnector connector = new GenericRestConnector();
		final ConnectorConfig config = new ConnectorConfig();
		config.setProperty("op.test.header.X-Test", "safe\r\nInjected: value");
		connector.configure(config);
		final Method method = GenericRestConnector.class.getDeclaredMethod("operationHeaders", String.class, Map.class);
		method.setAccessible(true);
		try
		{
			method.invoke(connector, "test", Collections.emptyMap());
			throw new AssertionError("Header injection accepted");
		}
		catch (final InvocationTargetException expected)
		{
			check(expected.getCause() instanceof IllegalArgumentException, "Wrong header rejection");
		}
	}

	private static void mcpErrorIsRedacted() throws Exception
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setApiKey("private-review-token");
		Redaction.remember(config);
		final Method error = com.on.ebx.connect.ebx.mcp.ConnectorsTools.class.getDeclaredMethod("error", String.class);
		error.setAccessible(true);
		final String result = error.invoke(null, "Failed private-review-token https://example.test/?api_key=other-secret").toString();
		check(!result.contains("private-review-token") && !result.contains("other-secret"), "MCP error leaks credentials");
	}

	private static final class FakeConnection extends HttpURLConnection
	{
		boolean disconnected;
		FakeConnection(final URL url) { super(url); }
		@Override public void disconnect() { disconnected = true; }
		@Override public boolean usingProxy() { return false; }
		@Override public void connect() { }
		@Override public int getResponseCode() throws IOException
		{
			if (url.getPath().equals("/slow-token"))
			{
				slowEntered.countDown();
				try { if (!releaseSlow.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Slow fixture timed out"); }
				catch (final InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
			}
			if (responseFailure) throw new IOException("simulated timeout");
			return 200;
		}
		@Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
		@Override public InputStream getInputStream()
		{
			return new ByteArrayInputStream(url.getPath().endsWith("token")
				? "{\"access_token\":\"fixture-token\",\"expires_in\":3600}".getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[100]);
		}
	}
	private static void check(final boolean condition, final String message)
	{
		if (!condition) throw new AssertionError(message);
	}
}
