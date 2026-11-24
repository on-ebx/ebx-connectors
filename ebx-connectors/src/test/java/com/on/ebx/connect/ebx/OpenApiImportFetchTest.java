package com.on.ebx.connect.ebx;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.spi.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * The download of an OpenAPI specification by URL is an outbound call typed by a user: http(s)
 * only, allow-list and address rules on the URL and on redirects, bounded answer, no key echoed.
 */
public final class OpenApiImportFetchTest
{
	private OpenApiImportFetchTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String previousList = System.getProperty(Egress.PROPERTY);
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/openapi.json", new Fixed(200, null, "{\"openapi\":\"3.0.0\",\"paths\":{}}"));
		server.createContext("/moved", new Fixed(302, "/openapi.json", ""));
		server.createContext("/to-metadata", new Fixed(302, "http://169.254.169.254/latest/user-data", ""));
		server.createContext("/missing", new Fixed(404, null, "{}"));
		final StringBuilder large = new StringBuilder();
		for (int i = 0; i < 4096; i++)
		{
			large.append("0123456789abcdef");
		}
		server.createContext("/large", new Fixed(200, null, large.toString()));
		server.start();
		try
		{
			System.clearProperty(Egress.PROPERTY);
			final String base = "http://127.0.0.1:" + server.getAddress().getPort();
			OpenApiImportFetchTest.check(OpenApiImport.fetch(base + "/openapi.json").contains("\"openapi\""), "specification not downloaded");
			OpenApiImportFetchTest.check(OpenApiImport.fetch(base + "/moved").contains("\"openapi\""), "same-origin redirect not followed");

			OpenApiImportFetchTest.refused("file:///etc/passwd", "must start with http:// or https://");
			OpenApiImportFetchTest.refused("jar:file:/tmp/x.jar!/spec.json", "must start with http:// or https://");
			OpenApiImportFetchTest.refused("ftp://127.0.0.1/spec.json", "must start with http:// or https://");
			OpenApiImportFetchTest.refused("http://169.254.169.254/latest/user-data", "link-local or cloud metadata address");
			OpenApiImportFetchTest.refused("http://metadata.google.internal/computeMetadata/v1/", "cloud metadata service");
			OpenApiImportFetchTest.refused(base + "/to-metadata", "link-local or cloud metadata address");

			final String keyed = OpenApiImportFetchTest.refused(base + "/missing?api_key=do-not-echo-me", "answered HTTP 404");
			OpenApiImportFetchTest.check(!keyed.contains("do-not-echo-me"), "the key of the URL is echoed: " + keyed);

			OpenApiImportFetchTest.check(OpenApiImport.fetch(base + "/large", 131072).length() == 65536, "answer under the ceiling refused");
			try
			{
				OpenApiImport.fetch(base + "/large", 32768);
				throw new AssertionError("an answer above the ceiling was accepted");
			}
			catch (final ConnectorException expected)
			{
				OpenApiImportFetchTest.check(expected.getMessage().contains("exceeds byte limit"), "unexpected failure: " + expected.getMessage());
			}

			System.setProperty(Egress.PROPERTY, "api.example.org");
			OpenApiImportFetchTest.refused(base + "/openapi.json", "not in " + Egress.PROPERTY);
		}
		finally
		{
			server.stop(0);
			if (previousList == null)
			{
				System.clearProperty(Egress.PROPERTY);
			}
			else
			{
				System.setProperty(Egress.PROPERTY, previousList);
			}
		}
		System.out.println("PASS: OpenAPI download by URL is http(s) only, egress-checked on every hop, bounded and redacted");
	}

	private static String refused(final String pUrl, final String pExpected)
	{
		try
		{
			OpenApiImport.fetch(pUrl);
		}
		catch (final ConnectorException expected)
		{
			OpenApiImportFetchTest.check(expected.getMessage().contains(pExpected), pUrl + ": expected '" + pExpected + "' in: " + expected.getMessage());
			return expected.getMessage();
		}
		throw new AssertionError(pUrl + " was downloaded");
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static final class Fixed implements HttpHandler
	{
		private final int status;
		private final String location;
		private final String body;

		Fixed(final int pStatus, final String pLocation, final String pBody)
		{
			this.status = pStatus;
			this.location = pLocation;
			this.body = pBody;
		}

		@Override
		public void handle(final HttpExchange pExchange) throws IOException
		{
			if (this.location != null)
			{
				pExchange.getResponseHeaders().add("Location", this.location);
			}
			final byte[] answer = this.body.getBytes("UTF-8");
			pExchange.sendResponseHeaders(this.status, answer.length == 0 ? -1 : answer.length);
			if (answer.length > 0)
			{
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
			pExchange.close();
		}
	}
}
