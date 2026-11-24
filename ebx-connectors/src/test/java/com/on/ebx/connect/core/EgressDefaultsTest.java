package com.on.ebx.connect.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Audit decision 1 (2026-10-06): private, loopback and unresolvable hosts are refused by default, the exception list
 * ({@code ebx.connectors.egress.privateExceptions}: names, host:port, addresses, CIDR) lifts the rule for the hosts it names,
 * never for link-local or metadata addresses, and a redirect to a private address is refused hop by hop. Offline: the
 * names used resolve locally ({@code localhost}) or are literals; the one server is a loopback fixture.
 */
public final class EgressDefaultsTest
{
	private static final String PRIVATE = "loopback or private address";
	private static final String[] PROPERTIES = { Egress.PROPERTY, Egress.REQUIRE_PROPERTY, Egress.BLOCK_PRIVATE_PROPERTY, Egress.PRIVATE_EXCEPTIONS_PROPERTY };

	private EgressDefaultsTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String[] saved = new String[PROPERTIES.length];
		for (int i = 0; i < PROPERTIES.length; i++)
		{
			saved[i] = System.getProperty(PROPERTIES[i]);
			System.clearProperty(PROPERTIES[i]);
		}
		try
		{
			defaultIsOn();
			addressClassification();
			literalForms();
			exceptions();
			fileList();
			neverLiftsMetadata();
			switchOff();
			allowListExactEntry();
			cidr();
			placeholderBaseUrls();
			redirectToPrivate();
		}
		finally
		{
			for (int i = 0; i < PROPERTIES.length; i++)
			{
				if (saved[i] == null)
				{
					System.clearProperty(PROPERTIES[i]);
				}
				else
				{
					System.setProperty(PROPERTIES[i], saved[i]);
				}
			}
		}
		System.out.println("PASS: private hosts refused by default (IPv4, IPv6, mapped, embedded and numeric forms, resolved names), exceptions by name, host:port, address and CIDR, metadata never lifted, redirects re-checked");
	}

	private static void reset()
	{
		for (final String property : PROPERTIES)
		{
			System.clearProperty(property);
		}
	}

	private static void defaultIsOn()
	{
		reset();
		check(Egress.privateHostsBlocked(), "the private-host rule is on when nothing is set");
		System.setProperty(Egress.BLOCK_PRIVATE_PROPERTY, "");
		check(Egress.privateHostsBlocked(), "an empty value is not an opt-out");
		System.setProperty(Egress.BLOCK_PRIVATE_PROPERTY, "true");
		check(Egress.privateHostsBlocked(), "true keeps it on");
		System.setProperty(Egress.BLOCK_PRIVATE_PROPERTY, " False ");
		check(!Egress.privateHostsBlocked(), "only false (any case) switches it off");
		reset();
		// no allow-list is needed for the rule, which stays optional
		check(!Egress.enabled() && !Egress.allowListRequired(), "the allow-list itself stays optional");
		allowed("http://93.184.216.34/");
		allowed("https://[2606:4700:4700::1111]/");
		allowed("jdbc:h2:mem:test");
		allowed("jdbc:postgresql://8.8.8.8:5432/db");
	}

	private static void addressClassification()
	{
		reset();
		for (final String target : new String[] { "http://127.0.0.1:8080/", "http://127.9.9.9/", "http://10.0.0.1/", "http://172.16.5.4/", "http://172.31.255.255/",
			"http://192.168.1.1/", "http://100.64.0.1/", "http://100.127.255.254/", "http://0.0.0.0/", "http://0.1.2.3/", "http://[::1]/", "http://[::]/",
			"http://[fc00::1]/", "http://[fd12:3456::1]/", "http://[fec0::1]/", "http://198.18.0.1/", "http://198.19.255.255/", "http://240.0.0.1/",
			"http://255.255.255.255/", "http://224.0.0.1/", "http://[ff02::1]/", "http://192.0.0.8/",
			// IPv4 inside IPv6: mapped, compatible, NAT64, 6to4
			"http://[::ffff:127.0.0.1]/", "http://[::ffff:10.1.2.3]/", "http://[::ffff:7f00:1]/", "http://[::10.1.2.3]/", "http://[64:ff9b::a00:1]/",
			"http://[2002:7f00:1::]/", "http://[2002:a00:1::1]/",
			// non-HTTP forms: host lists, JDBC, Kafka, bare host:port
			"jdbc:postgresql://10.0.0.5:5432/db", "jdbc:h2:tcp://localhost:9092/repository", "kafka://localhost:9082", "10.0.0.1:9092,10.0.0.2:9092",
			"localhost:9082", "jdbc:oracle:thin:@192.168.0.9:1521/svc", "sftp://user:pw@172.20.0.1:22" })
		{
			refused(target, PRIVATE);
		}
		// not private: neighbours of the ranges above
		for (final String target : new String[] { "http://172.15.255.255/", "http://172.32.0.1/", "http://100.63.255.255/", "http://100.128.0.1/", "http://198.17.0.1/",
			"http://198.20.0.1/", "http://223.255.255.255/", "http://11.0.0.1/", "http://192.169.0.1/", "http://[2606:4700::1]/", "http://[2002:5db8:d822::1]/" })
		{
			allowed(target);
		}
		// a name is judged on what it resolves to, redirects and connections alike
		refused("http://localhost:8080/", "resolves to a loopback or private address");
		refused("http://LOCALHOST./", "resolves to a loopback or private address");
		// an unresolvable name cannot be proven public
		refused("http://no-such-host.invalid/", "does not resolve");
		// the message tells the administrator what to do
		try
		{
			Egress.check("http://10.0.0.1/");
			throw new AssertionError("10.0.0.1 allowed");
		}
		catch (final IllegalStateException expected)
		{
			check(expected.getMessage().contains(Egress.PRIVATE_EXCEPTIONS_PROPERTY) && expected.getMessage().contains(Egress.BLOCK_PRIVATE_PROPERTY),
				"the refusal names the exception property and the opt-out: " + expected.getMessage());
		}
	}

	/**
	 * Decimal, hexadecimal and octal spellings are refused as such: this JVM reads {@code 0177.0.0.1} as 177.0.0.1 while a
	 * proxy or curl reads it as 127.0.0.1, so only the four-part decimal form is a literal address.
	 */
	private static void literalForms()
	{
		reset();
		for (final String target : new String[] { "http://2130706433/", "http://0x7f000001/", "http://0x7f.0.0.1/", "http://127.1/", "http://017700000001/",
			"http://0177.0.0.1/", "http://3232235777/", "http://167772161/" })
		{
			try
			{
				Egress.check(target);
				throw new AssertionError(target + " (a private address in an unusual spelling) was allowed");
			}
			catch (final IllegalStateException expected)
			{
				check(expected.getMessage().contains("non-standard spelling"), target + ": " + expected.getMessage());
			}
		}
		// what the JVM itself reads: the checked address is the connected one
		check(InetAddress.getLoopbackAddress() != null, "sanity");
	}

	private static void exceptions()
	{
		reset();
		System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "localhost:9092, 127.0.0.1:9095, [::1]:11434, 192.168.1.5, kafka.internal, 10.1.0.0/16, fd00::/8, nonsense/xx, *");
		// host:port, by name, by address, bracketed IPv6
		allowed("kafka://localhost:9092");
		allowed("jdbc:h2:tcp://localhost:9092/repository");
		refused("localhost:9093", PRIVATE);
		refused("http://localhost/", PRIVATE); // no port in the URL: the entry names one
		allowed("jdbc:h2:tcp://127.0.0.1:9095/x");
		refused("jdbc:h2:tcp://127.0.0.1:9096/x", PRIVATE);
		allowed("http://[::1]:11434/api");
		refused("http://[::1]:11435/api", PRIVATE);
		// an address entry without a port is for any port, and only for that address
		allowed("http://192.168.1.5/");
		allowed("http://192.168.1.5:8443/");
		refused("http://192.168.1.6/", PRIVATE);
		// a CIDR block
		allowed("http://10.1.2.3/");
		allowed("http://10.1.255.255:1234/");
		refused("http://10.2.0.1/", PRIVATE);
		allowed("http://[fd00::5]/");
		allowed("http://[fd12:3456::1]/");
		refused("http://[fc00::1]/", PRIVATE);
		// a name entry lifts the rule even for a name that does not resolve here (it cannot connect either way)
		allowed("kafka.internal:9092");
		// an entry that does not parse and a bare wildcard allow nothing
		refused("http://10.9.9.9/", PRIVATE);
		refused("http://127.0.0.1:1/", PRIVATE);
		// the port of an address entry is checked against the call, an entry without one covers every port
		System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "127.0.0.1");
		allowed("http://127.0.0.1:1/");
		// every address a name resolves to must be excepted: localhost is 127.0.0.1 AND ::1, so an address entry alone does not cover it
		refused("http://localhost:2/", PRIVATE);
		System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "127.0.0.1, ::1");
		allowed("http://localhost:2/");
		check(Egress.privateExceptionCount() == 2, "two entries counted");
		// a name entry (what an operator writes for localhost) covers whatever the name resolves to, on its port only when it names one
		System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "localhost");
		allowed("http://localhost:2/");
		refused("http://127.0.0.1:2/", PRIVATE); // the name is excepted, not the address
	}

	/** {@code file:<path>}: one entry per line, comments, and an unreadable file allows nothing (and restricts no public host). */
	private static void fileList() throws Exception
	{
		reset();
		final java.io.File list = java.io.File.createTempFile("egress-private", ".txt");
		try
		{
			java.nio.file.Files.write(list.toPath(), "# demo server\nlocalhost:9092\n127.0.0.1:9095  # H2\n\n10.1.0.0/16\n".getBytes("UTF-8"));
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "file:" + list.getPath());
			allowed("kafka://localhost:9092");
			allowed("jdbc:h2:tcp://127.0.0.1:9095/x");
			allowed("http://10.1.5.5/");
			refused("http://10.2.0.1/", PRIVATE);
			check(Egress.privateExceptionCount() == 3, "three entries read from the file");
			// the exceptions do not turn the allow-list on: a public host is still free
			check(!Egress.enabled(), "a private-exception list is not an allow-list");
			allowed("http://93.184.216.34/");
		}
		finally
		{
			list.delete();
		}
		System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "file:/no/such/file/egress-private.txt");
		refused("kafka://localhost:9092", PRIVATE);
		allowed("http://93.184.216.34/");
		reset();
	}

	private static void neverLiftsMetadata()
	{
		reset();
		System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "169.254.169.254, 169.254.0.0/16, metadata.google.internal, fe80::/10, [fd00:ec2::254], 0.0.0.0/0, ::/0");
		refused("http://169.254.169.254/latest", "link-local or cloud metadata address");
		refused("http://169.254.170.2/", "link-local or cloud metadata address");
		refused("http://[fe80::1]/", "link-local or cloud metadata address");
		refused("http://[fd00:ec2::254]/", "link-local or cloud metadata address");
		refused("http://metadata.google.internal/", "cloud metadata service");
		refused("http://100.100.100.200/", "link-local or cloud metadata address");
		// but a catch-all CIDR does lift the private ranges: the operator asked for it
		allowed("http://10.0.0.1/");
		allowed("http://[::1]/");
	}

	private static void switchOff()
	{
		reset();
		System.setProperty(Egress.BLOCK_PRIVATE_PROPERTY, "false");
		allowed("http://127.0.0.1:8080/");
		allowed("http://localhost:11434/");
		allowed("http://10.0.0.1/");
		allowed("http://no-such-host.invalid/");
		refused("http://169.254.169.254/", "link-local or cloud metadata address");
	}

	private static void allowListExactEntry()
	{
		reset();
		System.setProperty(Egress.PROPERTY, "10.0.0.1, localhost:9092");
		allowed("http://10.0.0.1/");
		allowed("kafka://localhost:9092");
		refused("http://10.0.0.2/", "not in " + Egress.PROPERTY);
		System.setProperty(Egress.PROPERTY, "*");
		refused("http://10.0.0.1/", PRIVATE); // a wildcard names nobody
		System.setProperty(Egress.PROPERTY, "*.example.org");
		refused("http://10.0.0.1/", "not in " + Egress.PROPERTY);
	}

	/** A type that never calls out is not refused for its placeholder base URL; every other type still is. */
	private static void placeholderBaseUrls()
	{
		reset();
		for (final String url : new String[] { "https://persons-sap.sample-file.local/data", "local://sample-file", "local:", "https://x.sample-file.local/data" })
		{
			final com.on.ebx.connect.spi.ConnectorConfig sample = config("sample-file", url);
			Egress.checkBaseUrl(sample); // default rules: an unresolvable host would be refused for any other type
			check(!Egress.callsOut(sample) && Egress.example(sample) == null, "sample-file makes no outbound call");
			check(Egress.posture(sample) == Egress.Posture.NOT_ENFORCED, "its posture is not NOT_COVERED");
			final com.on.ebx.connect.spi.ConnectorConfig other = config("rest", url);
			try
			{
				Egress.checkBaseUrl(other);
				throw new AssertionError("a rest connector on " + url + " was allowed");
			}
			catch (final IllegalStateException expected)
			{
				check(expected.getMessage().contains("does not resolve"), "unexpected refusal of " + url + ": " + expected.getMessage());
			}
		}
		// documents reads uploads and folders; its model call goes through the llm connector, checked on its own
		for (final String url : new String[] { "local://documents", "https://documents.local/" })
		{
			final com.on.ebx.connect.spi.ConnectorConfig documents = config("documents", url);
			Egress.checkBaseUrl(documents);
			check(!Egress.callsOut(documents), "documents makes no outbound call");
		}
		// the allow-list does not apply to it either, and a type name in other case or with spaces is still the same type
		System.setProperty(Egress.PROPERTY, "none");
		Egress.checkBaseUrl(config(" Sample-File ", "https://persons-sap.sample-file.local/data"));
		for (final String type : new String[] { "rest", "jdbc", "kafka", "sftp", "ftp", "s3", "h2", "webhook", "mcp", "llm", "ebx", "", null })
		{
			check(Egress.callsOut(config(type, "https://a.example/")), type + " is checked");
		}
		try
		{
			Egress.checkBaseUrl(config("sftp", "sftp://sample-file.local"));
			throw new AssertionError("an sftp connector escaped the allow-list");
		}
		catch (final IllegalStateException expected)
		{
			check(expected.getMessage().contains("not in " + Egress.PROPERTY), expected.getMessage());
		}
		reset();
	}

	private static com.on.ebx.connect.spi.ConnectorConfig config(final String pType, final String pUrl)
	{
		final com.on.ebx.connect.spi.ConnectorConfig config = new com.on.ebx.connect.spi.ConnectorConfig();
		config.setId("c");
		config.setType(pType);
		config.setBaseUrl(pUrl);
		return config;
	}

	private static void cidr()
	{
		final byte[] net10 = { 10, 0, 0, 0 };
		check(Egress.inCidr(new byte[] { 10, 5, 6, 7 }, net10, 8), "/8");
		check(!Egress.inCidr(new byte[] { 11, 0, 0, 0 }, net10, 8), "/8 miss");
		check(Egress.inCidr(new byte[] { 10, 5, 6, 7 }, net10, 0), "/0 covers all");
		check(Egress.inCidr(new byte[] { 10, 0, 0, 0 }, net10, 32) && !Egress.inCidr(new byte[] { 10, 0, 0, 1 }, net10, 32), "/32 is one address");
		final byte[] net172 = { (byte) 172, 16, 0, 0 };
		check(Egress.inCidr(new byte[] { (byte) 172, 31, 9, 9 }, net172, 12) && !Egress.inCidr(new byte[] { (byte) 172, 32, 0, 1 }, net172, 12), "/12 on a non-byte boundary");
		final byte[] fd = new byte[16];
		fd[0] = (byte) 0xfd;
		final byte[] inFd = fd.clone();
		inFd[15] = 7;
		check(Egress.inCidr(inFd, fd, 8) && Egress.inCidr(inFd, fd, 120) && !Egress.inCidr(inFd, fd, 128), "IPv6 prefixes");
		check(!Egress.inCidr(new byte[] { 10, 0, 0, 1 }, fd, 0), "an IPv4 address is never in an IPv6 block");
	}

	/** A redirect from an allowed local server to a private address is refused at the hop, without the credential. */
	private static void redirectToPrivate() throws Exception
	{
		reset();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ok", new Reply(200, null));
		server.createContext("/to-private", new Reply(302, "http://10.255.255.1:81/secret"));
		server.createContext("/to-metadata", new Reply(302, "http://169.254.169.254/latest/meta-data/"));
		server.createContext("/to-localname", new Reply(302, "http://localhost:" + server.getAddress().getPort() + "/ok"));
		server.start();
		try
		{
			final String base = "http://127.0.0.1:" + server.getAddress().getPort();
			final HttpJsonClient client = new HttpJsonClient(2000, 2000, "EgressDefaultsTest");
			final java.util.Map<String, String> headers = Collections.singletonMap("Authorization", "Bearer api-token");
			// by default even the fixture itself is refused
			expectRefused(client, base + "/ok", headers, PRIVATE);
			System.setProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "127.0.0.1, ::1");
			check(client.send("GET", base + "/ok", headers, null).getStatus() == 200, "the excepted fixture answers");
			expectRefused(client, base + "/to-private", headers, PRIVATE);
			expectRefused(client, base + "/to-metadata", headers, "link-local or cloud metadata address");
			// localhost resolves to the excepted address: followed
			check(client.send("GET", base + "/to-localname", headers, null).getStatus() == 200, "a name that resolves to an excepted address is followed");
		}
		finally
		{
			server.stop(0);
		}
	}

	private static void expectRefused(final HttpJsonClient pClient, final String pUrl, final java.util.Map<String, String> pHeaders, final String pExpected)
		throws Exception
	{
		try
		{
			final HttpJsonClient.Response response = pClient.send("GET", pUrl, pHeaders, null);
			throw new AssertionError(pUrl + " answered HTTP " + response.getStatus() + " instead of being refused");
		}
		catch (final IOException expected)
		{
			check(String.valueOf(expected.getMessage()).contains(pExpected), pUrl + ": expected '" + pExpected + "' in: " + expected.getMessage());
			check(!String.valueOf(expected.getMessage()).contains("api-token"), "a refusal echoed a credential");
		}
	}

	private static final class Reply implements HttpHandler
	{
		private final int status;
		private final String location;

		Reply(final int pStatus, final String pLocation)
		{
			this.status = pStatus;
			this.location = pLocation;
		}

		@Override
		public void handle(final HttpExchange pExchange) throws IOException
		{
			if (this.location != null)
			{
				pExchange.getResponseHeaders().add("Location", this.location);
			}
			final byte[] body = "{}".getBytes("UTF-8");
			pExchange.sendResponseHeaders(this.status, body.length);
			try (OutputStream out = pExchange.getResponseBody())
			{
				out.write(body);
			}
		}
	}

	private static void allowed(final String pTarget)
	{
		try
		{
			Egress.check(pTarget);
		}
		catch (final IllegalStateException ex)
		{
			throw new AssertionError(pTarget + " refused: " + ex.getMessage());
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
			check(expected.getMessage().contains(pExpected), pTarget + ": expected '" + pExpected + "' in: " + expected.getMessage());
			return;
		}
		throw new AssertionError(pTarget + " allowed (expected a refusal: " + pExpected + ")");
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass)
		{
			throw new AssertionError(pMessage);
		}
	}
}
