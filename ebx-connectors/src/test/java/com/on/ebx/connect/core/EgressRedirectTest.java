package com.on.ebx.connect.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Outbound rules that hold without EBX: link-local and metadata addresses, the two opt-in JVM
 * properties, IPv6 literals, and redirects followed hop by hop with credentials kept on the
 * origin that received them. Two local servers stand for "the API" and "somewhere else".
 */
public final class EgressRedirectTest
{
	private EgressRedirectTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String previousList = System.getProperty(Egress.PROPERTY);
		final String previousRequire = System.getProperty(Egress.REQUIRE_PROPERTY);
		final String previousPrivate = System.getProperty(Egress.BLOCK_PRIVATE_PROPERTY);
		final HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final HttpServer elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final Recorder apiSeen = new Recorder();
		final Recorder elsewhereSeen = new Recorder();
		try
		{
			System.clearProperty(Egress.PROPERTY);
			System.clearProperty(Egress.REQUIRE_PROPERTY);
			System.setProperty(Egress.BLOCK_PRIVATE_PROPERTY, "false"); // the default is ON since 2026-10-06 (EgressDefaultsTest): these cases call local servers
			EgressRedirectTest.addressRules();
			EgressRedirectTest.optInProperties();
			EgressRedirectTest.ipv6Literals();
			EgressRedirectTest.posture();

			api.start();
			elsewhere.start();
			final int apiPort = api.getAddress().getPort();
			final int elsewherePort = elsewhere.getAddress().getPort();
			final String apiUrl = "http://127.0.0.1:" + apiPort;
			final String elsewhereUrl = "http://127.0.0.1:" + elsewherePort;
			api.createContext("/final", new Answer(apiSeen, 200, null));
			api.createContext("/same", new Answer(apiSeen, 302, "/final"));
			api.createContext("/away", new Answer(apiSeen, 302, elsewhereUrl + "/landing"));
			api.createContext("/other-name", new Answer(apiSeen, 302, "http://localhost:" + apiPort + "/final"));
			api.createContext("/metadata", new Answer(apiSeen, 302, "http://169.254.169.254/latest/meta-data/"));
			api.createContext("/loop", new Answer(apiSeen, 302, "/loop"));
			api.createContext("/tls", new Answer(apiSeen, 302, "https://127.0.0.1:" + apiPort + "/final"));
			api.createContext("/keep-method", new Answer(apiSeen, 307, "/final"));
			api.createContext("/keep-method-away", new Answer(apiSeen, 307, elsewhereUrl + "/landing"));
			api.createContext("/see-other", new Answer(apiSeen, 303, "/final"));
			elsewhere.createContext("/landing", new Answer(elsewhereSeen, 200, null));
			EgressRedirectTest.redirects(apiUrl, apiPort, elsewherePort, apiSeen, elsewhereSeen);
		}
		finally
		{
			api.stop(0);
			elsewhere.stop(0);
			EgressRedirectTest.restore(Egress.PROPERTY, previousList);
			EgressRedirectTest.restore(Egress.REQUIRE_PROPERTY, previousRequire);
			EgressRedirectTest.restore(Egress.BLOCK_PRIVATE_PROPERTY, previousPrivate);
		}
		System.out.println("PASS: metadata addresses refused, opt-in egress properties, redirects re-checked per hop without credentials across origins");
	}

	private static void addressRules()
	{
		// what a development instance calls every day stays allowed once the private-host rule is switched off (EgressDefaultsTest covers the default)
		EgressRedirectTest.allowed("http://127.0.0.1:8080/ebx-dataservices/rest");
		EgressRedirectTest.allowed("http://localhost:9082");
		EgressRedirectTest.allowed("jdbc:h2:tcp://localhost:9092/repository");
		EgressRedirectTest.allowed("http://10.20.30.40/api");
		EgressRedirectTest.allowed("jdbc:h2:mem:test");

		EgressRedirectTest.refused("http://169.254.169.254/latest/meta-data/", "link-local or cloud metadata address");
		EgressRedirectTest.refused("http://169.254.170.2/v2/credentials", "link-local or cloud metadata address");
		EgressRedirectTest.refused("http://[fe80::1]/", "link-local or cloud metadata address");
		EgressRedirectTest.refused("http://[fd00:ec2::254]/latest", "link-local or cloud metadata address");
		EgressRedirectTest.refused("http://[::ffff:169.254.169.254]/latest", "link-local or cloud metadata address");
		EgressRedirectTest.refused("http://[::169.254.169.254]/latest", "link-local or cloud metadata address");
		EgressRedirectTest.refused("http://metadata.google.internal/computeMetadata/v1/", "cloud metadata service");
		EgressRedirectTest.refused("http://METADATA.GOOGLE.INTERNAL./computeMetadata/v1/", "cloud metadata service");
		EgressRedirectTest.refused("169.254.169.254:80", "link-local or cloud metadata address");
		EgressRedirectTest.refused("http://user:pw@169.254.169.254/", "link-local or cloud metadata address");

		// a wildcard never opens them, an exact entry does
		System.setProperty(Egress.PROPERTY, "*");
		EgressRedirectTest.refused("http://169.254.169.254/latest", "exact entry");
		EgressRedirectTest.refused("http://metadata.google.internal/", "exact entry");
		System.setProperty(Egress.PROPERTY, "*.google.internal");
		EgressRedirectTest.refused("http://metadata.google.internal/", "cloud metadata service");
		System.setProperty(Egress.PROPERTY, "169.254.169.254, metadata.google.internal");
		EgressRedirectTest.allowed("http://169.254.169.254/latest");
		EgressRedirectTest.allowed("http://metadata.google.internal/");
		EgressRedirectTest.refused("http://169.254.170.2/", "not in " + Egress.PROPERTY);

		// the module's own workload-identity lookups: free of the address rule, not of the allow-list
		System.clearProperty(Egress.PROPERTY);
		Egress.checkPlatformMetadata("http://169.254.169.254");
		Egress.checkPlatformMetadata("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token");
		System.setProperty(Egress.PROPERTY, "api.example.org");
		try
		{
			Egress.checkPlatformMetadata("http://169.254.169.254");
			throw new AssertionError("a metadata lookup passed an allow-list that does not name it");
		}
		catch (final IllegalStateException expected)
		{
			EgressRedirectTest.check(expected.getMessage().contains("not in " + Egress.PROPERTY), "unexpected refusal: " + expected.getMessage());
		}
		System.clearProperty(Egress.PROPERTY);
	}

	private static void optInProperties()
	{
		System.setProperty(Egress.REQUIRE_PROPERTY, "true");
		EgressRedirectTest.refused("https://api.example.org/v1", Egress.REQUIRE_PROPERTY + "=true and " + Egress.PROPERTY + " is not set");
		EgressRedirectTest.refused("localhost:9082", Egress.PROPERTY + " is not set");
		EgressRedirectTest.allowed("jdbc:h2:mem:test"); // no host: nothing leaves the JVM
		try
		{
			Egress.checkPlatformMetadata("http://169.254.169.254");
			throw new AssertionError("a metadata lookup passed although an allow-list is required and absent");
		}
		catch (final IllegalStateException expected)
		{
			// expected
		}
		System.setProperty(Egress.PROPERTY, "127.0.0.1");
		EgressRedirectTest.allowed("http://127.0.0.1:8080/");
		System.clearProperty(Egress.PROPERTY);
		System.clearProperty(Egress.REQUIRE_PROPERTY);

		System.setProperty(Egress.BLOCK_PRIVATE_PROPERTY, "true");
		for (final String target : new String[] { "http://127.0.0.1:8080/", "http://10.0.0.1/", "http://172.16.5.4/", "http://192.168.1.1/",
			"http://100.64.0.1/", "http://0.0.0.0/", "http://[::1]/", "http://[fc00::1]/", "http://[fd12:3456::1]/" })
		{
			EgressRedirectTest.refused(target, "loopback or private address");
		}
		// a name is judged on the addresses it resolves to
		EgressRedirectTest.refused("http://localhost:8080/", "resolves to a loopback or private address");
		EgressRedirectTest.refused("http://no-such-host.invalid/", "does not resolve");
		EgressRedirectTest.allowed("http://93.184.216.34/");
		EgressRedirectTest.throughProxy();
		System.setProperty(Egress.PROPERTY, "*");
		EgressRedirectTest.refused("http://127.0.0.1:8080/", "exact entry");
		System.setProperty(Egress.PROPERTY, "127.0.0.1:8080, localhost, no-such-host.invalid");
		EgressRedirectTest.allowed("http://127.0.0.1:8080/");
		EgressRedirectTest.allowed("http://localhost:9082/");
		EgressRedirectTest.allowed("http://no-such-host.invalid/");
		EgressRedirectTest.refused("http://127.0.0.1:8081/", "not in " + Egress.PROPERTY);
		System.clearProperty(Egress.PROPERTY);
		System.setProperty(Egress.BLOCK_PRIVATE_PROPERTY, "false"); // the default is ON since 2026-10-06 (EgressDefaultsTest): these cases call local servers
	}

	/** Behind an HTTP proxy the name is the proxy's to resolve: only what the URL itself says is judged. */
	private static void throughProxy()
	{
		final String previousHost = System.getProperty("http.proxyHost");
		final String previousPort = System.getProperty("http.proxyPort");
		System.setProperty("http.proxyHost", "proxy.example.org");
		System.setProperty("http.proxyPort", "3128");
		try
		{
			EgressRedirectTest.allowed("http://no-such-host.invalid/");
			EgressRedirectTest.refused("http://169.254.169.254/latest", "link-local or cloud metadata address");
			EgressRedirectTest.refused("http://metadata.google.internal/", "cloud metadata service");
			EgressRedirectTest.refused("http://10.0.0.1/", "loopback or private address");
			// another protocol does not use that proxy: resolved as usual
			EgressRedirectTest.refused("no-such-host.invalid:9092", "does not resolve");
		}
		finally
		{
			EgressRedirectTest.restore("http.proxyHost", previousHost);
			EgressRedirectTest.restore("http.proxyPort", previousPort);
		}
	}

	private static void ipv6Literals()
	{
		// a bracketed address is a host like any other, held to the allow-list
		System.setProperty(Egress.PROPERTY, "localhost");
		EgressRedirectTest.refused("http://[::1]:8080/", "not in " + Egress.PROPERTY);
		EgressRedirectTest.refused("http://[2001:db8::1]/", "not in " + Egress.PROPERTY);
		System.setProperty(Egress.PROPERTY, "[::1]:8080, 2001:db8::2");
		EgressRedirectTest.allowed("http://[::1]:8080/");
		EgressRedirectTest.allowed("http://[0:0:0:0:0:0:0:1]:8080/");
		EgressRedirectTest.refused("http://[::1]:8081/", "not in " + Egress.PROPERTY);
		EgressRedirectTest.allowed("http://[2001:db8::2]:443/");
		EgressRedirectTest.allowed("http://[2001:DB8:0:0:0:0:0:2]/");
		System.clearProperty(Egress.PROPERTY);
	}

	private static void posture()
	{
		final ConnectorConfig local = new ConnectorConfig();
		local.setBaseUrl("http://localhost:8080/ebx-dataservices/rest");
		final ConnectorConfig metadata = new ConnectorConfig();
		metadata.setBaseUrl("http://169.254.169.254/latest");
		EgressRedirectTest.check(Egress.posture(local) == Egress.Posture.NOT_ENFORCED, "no list: a local connector is not enforced");
		EgressRedirectTest.check(Egress.posture(metadata) == Egress.Posture.NOT_COVERED, "no list: a metadata address is announced as refused");
		System.setProperty(Egress.REQUIRE_PROPERTY, "true");
		EgressRedirectTest.check(Egress.posture(local) == Egress.Posture.NOT_COVERED, "list required and absent: announced as refused");
		System.clearProperty(Egress.REQUIRE_PROPERTY);
		System.setProperty(Egress.PROPERTY, "localhost:8080");
		EgressRedirectTest.check(Egress.posture(local) == Egress.Posture.COVERED, "listed host is covered");
		System.clearProperty(Egress.PROPERTY);
	}

	private static void redirects(final String pApiUrl, final int pApiPort, final int pElsewherePort, final Recorder pApi,
		final Recorder pElsewhere) throws Exception
	{
		final HttpJsonClient client = new HttpJsonClient(2000, 2000, "EgressRedirectTest");
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Authorization", "Bearer api-token");
		headers.put("X-Api-Key", "api-key-value");
		headers.put("Cookie", "session=abc");
		headers.put("X-Custom-Signature", "signed");
		headers.put("Accept-Language", "fr");

		// same origin: the call goes through with everything it carried
		HttpJsonClient.Response response = client.send("GET", pApiUrl + "/same", headers, null);
		EgressRedirectTest.check(response.getStatus() == 200, "same-origin redirect not followed: " + response.getStatus());
		EgressRedirectTest.check(response.getFinalUrl().endsWith("/final"), "final URL not reported: " + response.getFinalUrl());
		Seen last = pApi.last("/final");
		EgressRedirectTest.check("Bearer api-token".equals(last.header("Authorization")) && "api-key-value".equals(last.header("X-Api-Key"))
			&& "session=abc".equals(last.header("Cookie")), "same-origin redirect lost the credentials");

		// another port: followed, without anything that authenticates
		pElsewhere.clear();
		response = client.send("GET", pApiUrl + "/away", headers, null);
		EgressRedirectTest.check(response.getStatus() == 200, "cross-origin redirect not followed: " + response.getStatus());
		last = pElsewhere.last("/landing");
		EgressRedirectTest.check(last.header("Authorization") == null && last.header("X-Api-Key") == null && last.header("Cookie") == null
			&& last.header("X-Custom-Signature") == null, "credentials crossed to another origin: " + last.headers);
		EgressRedirectTest.check("fr".equals(last.header("Accept-Language")), "harmless header dropped across origins");

		// same server under another host name: another origin all the same
		pApi.clear();
		response = client.send("GET", pApiUrl + "/other-name", headers, null);
		EgressRedirectTest.check(response.getStatus() == 200, "redirect to another host name not followed: " + response.getStatus());
		last = pApi.last("/final");
		EgressRedirectTest.check(last.header("Authorization") == null && last.header("X-Api-Key") == null && last.header("Cookie") == null,
			"credentials followed a change of host name");

		// a hop is checked like a first call: metadata address, then the allow-list
		EgressRedirectTest.refusedCall(client, "GET", pApiUrl + "/metadata", headers, "link-local or cloud metadata address");
		System.setProperty(Egress.PROPERTY, "127.0.0.1:" + pApiPort);
		pElsewhere.clear();
		EgressRedirectTest.refusedCall(client, "GET", pApiUrl + "/away", headers, "127.0.0.1:" + pElsewherePort + " refused: not in " + Egress.PROPERTY);
		EgressRedirectTest.check(pElsewhere.count() == 0, "a refused hop was called anyway");
		response = client.send("GET", pApiUrl + "/same", headers, null);
		EgressRedirectTest.check(response.getStatus() == 200, "allow-listed same-origin redirect broken");
		System.clearProperty(Egress.PROPERTY);

		// bounded
		pApi.clear();
		EgressRedirectTest.refusedCall(client, "GET", pApiUrl + "/loop", headers, "more than " + HttpJsonClient.MAX_REDIRECTS + " redirects");
		EgressRedirectTest.check(pApi.count() == HttpJsonClient.MAX_REDIRECTS + 1, "redirect loop made " + pApi.count() + " calls");

		// a change of scheme is returned, not followed
		response = client.send("GET", pApiUrl + "/tls", headers, null);
		EgressRedirectTest.check(response.getStatus() == 302, "a redirect to another scheme was followed: " + response.getStatus());

		// callers that asked for no redirect get the redirect itself
		response = client.sendBytesWithoutRedirects("GET", pApiUrl + "/same", headers, null, 65536);
		EgressRedirectTest.check(response.getStatus() == 302 && "/final".equals(response.getHeader("Location")), "redirect followed against the caller's wish");

		// method and body: 302 and 303 turn a POST into a GET, 307 replays it
		headers.put("Content-Type", "application/json");
		pApi.clear();
		response = client.send("POST", pApiUrl + "/same", headers, "{\"a\":1}");
		last = pApi.last("/final");
		EgressRedirectTest.check(response.getStatus() == 200 && "GET".equals(last.method) && last.body.isEmpty() && last.header("Content-Type") == null
			&& "Bearer api-token".equals(last.header("Authorization")), "302 after POST: " + last.method + " " + last.body);
		pApi.clear();
		response = client.send("PUT", pApiUrl + "/see-other", headers, "{\"a\":1}");
		last = pApi.last("/final");
		EgressRedirectTest.check(response.getStatus() == 200 && "GET".equals(last.method) && last.body.isEmpty(), "303 after PUT: " + last.method);
		pApi.clear();
		response = client.send("POST", pApiUrl + "/keep-method", headers, "{\"a\":1}");
		last = pApi.last("/final");
		EgressRedirectTest.check(response.getStatus() == 200 && "POST".equals(last.method) && "{\"a\":1}".equals(last.body)
			&& "Bearer api-token".equals(last.header("Authorization")), "307 same origin: " + last.method + " " + last.body);
		pElsewhere.clear();
		response = client.send("POST", pApiUrl + "/keep-method-away", headers, "{\"a\":1}");
		last = pElsewhere.last("/landing");
		EgressRedirectTest.check(response.getStatus() == 200 && "POST".equals(last.method) && "{\"a\":1}".equals(last.body)
			&& last.header("Authorization") == null && last.header("X-Api-Key") == null && last.header("Cookie") == null
			&& "application/json".equals(last.header("Content-Type")), "307 across origins: " + last.method + " " + last.headers);

		// PATCH takes another transport (java.net.http): same refusal before any connection
		EgressRedirectTest.refusedCall(client, "PATCH", "http://169.254.169.254/latest", headers, "link-local or cloud metadata address");
		System.setProperty(Egress.PROPERTY, "api.example.org");
		EgressRedirectTest.refusedCall(client, "PATCH", pApiUrl + "/final", headers, "not in " + Egress.PROPERTY);
		System.clearProperty(Egress.PROPERTY);
	}

	private static void allowed(final String pTarget)
	{
		try
		{
			Egress.check(pTarget);
		}
		catch (final IllegalStateException ex)
		{
			throw new AssertionError(pTarget + " refused with allow-list " + System.getProperty(Egress.PROPERTY) + ": " + ex.getMessage());
		}
	}

	private static void refused(final String pTarget, final String pExpected)
	{
		try
		{
			Egress.check(pTarget);
		}
		catch (final IllegalStateException expected)
		{
			EgressRedirectTest.check(expected.getMessage().contains(pExpected), pTarget + ": expected '" + pExpected + "' in: " + expected.getMessage());
			EgressRedirectTest.check(!expected.getMessage().contains("pw@"), "a refusal echoed the credentials of the URL");
			return;
		}
		throw new AssertionError(pTarget + " allowed with allow-list " + System.getProperty(Egress.PROPERTY));
	}

	private static void refusedCall(final HttpJsonClient pClient, final String pMethod, final String pUrl, final Map<String, String> pHeaders,
		final String pExpected) throws Exception
	{
		try
		{
			final HttpJsonClient.Response response = pClient.send(pMethod, pUrl, pHeaders, "PATCH".equals(pMethod) ? "{}" : null);
			throw new AssertionError(pMethod + " " + pUrl + " answered HTTP " + response.getStatus() + " instead of being refused");
		}
		catch (final IOException expected)
		{
			EgressRedirectTest.check(String.valueOf(expected.getMessage()).contains(pExpected),
				pMethod + " " + pUrl + ": expected '" + pExpected + "' in: " + expected.getMessage());
			EgressRedirectTest.check(!String.valueOf(expected.getMessage()).contains("api-token"), "a refusal echoed a credential");
		}
	}

	private static void restore(final String pProperty, final String pValue)
	{
		if (pValue == null)
		{
			System.clearProperty(pProperty);
		}
		else
		{
			System.setProperty(pProperty, pValue);
		}
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass)
		{
			throw new AssertionError(pMessage);
		}
	}

	/** One request as a server received it. */
	private static final class Seen
	{
		private final String path;
		private final String method;
		private final String body;
		private final Map<String, String> headers = new LinkedHashMap<String, String>();

		Seen(final String pPath, final String pMethod, final String pBody)
		{
			this.path = pPath;
			this.method = pMethod;
			this.body = pBody;
		}

		String header(final String pName)
		{
			return this.headers.get(pName.toLowerCase());
		}
	}

	private static final class Recorder
	{
		private final List<Seen> requests = Collections.synchronizedList(new ArrayList<Seen>());

		void clear()
		{
			this.requests.clear();
		}

		int count()
		{
			return this.requests.size();
		}

		Seen last(final String pPath)
		{
			synchronized (this.requests)
			{
				for (int i = this.requests.size() - 1; i >= 0; i--)
				{
					if (this.requests.get(i).path.equals(pPath))
					{
						return this.requests.get(i);
					}
				}
			}
			throw new AssertionError("no request reached " + pPath);
		}
	}

	/** Records the request, then answers a status, with a Location when it is a redirect. */
	private static final class Answer implements HttpHandler
	{
		private final Recorder recorder;
		private final int status;
		private final String location;

		Answer(final Recorder pRecorder, final int pStatus, final String pLocation)
		{
			this.recorder = pRecorder;
			this.status = pStatus;
			this.location = pLocation;
		}

		@Override
		public void handle(final HttpExchange pExchange) throws IOException
		{
			final java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
			final InputStream in = pExchange.getRequestBody();
			final byte[] buffer = new byte[4096];
			for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
			{
				received.write(buffer, 0, n);
			}
			final Seen seen = new Seen(pExchange.getRequestURI().getPath(), pExchange.getRequestMethod(), new String(received.toByteArray(), "UTF-8"));
			for (final Map.Entry<String, List<String>> header : pExchange.getRequestHeaders().entrySet())
			{
				seen.headers.put(header.getKey().toLowerCase(), header.getValue().isEmpty() ? "" : header.getValue().get(0));
			}
			this.recorder.requests.add(seen);
			if (this.location != null)
			{
				pExchange.getResponseHeaders().add("Location", this.location);
			}
			final byte[] answer = "{\"ok\":true}".getBytes("UTF-8");
			pExchange.getResponseHeaders().add("Content-Type", "application/json");
			pExchange.sendResponseHeaders(this.status, answer.length);
			final OutputStream out = pExchange.getResponseBody();
			try
			{
				out.write(answer);
			}
			finally
			{
				out.close();
			}
		}
	}
}
