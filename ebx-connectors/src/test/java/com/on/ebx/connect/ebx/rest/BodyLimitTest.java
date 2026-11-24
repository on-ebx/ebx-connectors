package com.on.ebx.connect.ebx.rest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.ebx.ConnectorsAccess;
import com.on.ebx.connect.ebx.contract.InJvmContract;
import com.orchestranetworks.rest.inject.SessionContext;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;

/**
 * Audit C2-06: a request body, a JSON-RPC batch and the nesting of a JSON body are bounded; the caller's right is checked
 * before the body is read; the in-JVM bridges apply the same bounds; each refusal is coded and names the property to raise.
 */
public final class BodyLimitTest
{
	private static final String[] PROPERTIES = { BodyLimit.PROP_MCP_BODY, BodyLimit.PROP_MCP_BATCH, BodyLimit.PROP_MCP_DEPTH, BodyLimit.PROP_REST_BODY,
		BodyLimit.PROP_REST_DEPTH };

	private BodyLimitTest() { }

	/** A stream that counts what is read from it. */
	private static final class CountingStream extends InputStream
	{
		private final long size;
		long read;

		CountingStream(final long pSize)
		{
			this.size = pSize;
		}

		@Override
		public int read() throws IOException
		{
			if (this.read >= this.size)
			{
				return -1;
			}
			this.read++;
			return ' ';
		}

		@Override
		public int read(final byte[] pBuffer, final int pOffset, final int pLength)
		{
			if (this.read >= this.size)
			{
				return -1;
			}
			final int n = (int) Math.min(pLength, this.size - this.read);
			java.util.Arrays.fill(pBuffer, pOffset, pOffset + n, (byte) ' ');
			this.read += n;
			return n;
		}
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
			defaults();
			sizeIsBounded();
			batchIsBounded();
			depthIsBounded();
			rightBeforeBody();
			inJvmBridges();
			restRoutes();
			propertiesRaiseTheBounds();
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
		System.out.println("PASS: C2-06 bodies, batches and JSON depth are bounded, the right is checked first, the in-JVM bridges share the bounds");
	}

	private static void defaults()
	{
		check(BodyLimit.maxMcpBodyBytes() == 4L * 1024L * 1024L && BodyLimit.maxMcpBatch() == 20 && BodyLimit.maxMcpDepth() == 64, "MCP defaults");
		check(BodyLimit.maxRestBodyBytes() == 32L * 1024L * 1024L && BodyLimit.maxRestDepth() == 64, "REST defaults");
		System.setProperty(BodyLimit.PROP_MCP_BATCH, "abc");
		check(BodyLimit.maxMcpBatch() == 20, "a value that is not a number reads as the default");
		System.setProperty(BodyLimit.PROP_MCP_BATCH, "-3");
		check(BodyLimit.maxMcpBatch() == 20, "a negative value reads as the default");
		System.clearProperty(BodyLimit.PROP_MCP_BATCH);
	}

	private static void sizeIsBounded() throws Exception
	{
		// a declared Content-Length over the bound: refused before one byte is read
		final CountingStream declared = new CountingStream(10);
		final McpEndpoint.Parsed byHeader = McpEndpoint.parse(String.valueOf(5L * 1024L * 1024L), declared, Locale.ENGLISH);
		check(byHeader.refusal != null && byHeader.refusal.getStatus() == 413, "2 declared MiB over the bound answer 413");
		check(declared.read == 0, "nothing was read for a declared length over the bound: " + declared.read);
		final JsonNode body = Json.parse(String.valueOf(byHeader.refusal.getEntity()));
		check("CN_BODY_TOO_LARGE".equals(body.get("error").get("data").get("code").asText()), "coded: " + body);
		check(BodyLimit.PROP_MCP_BODY.equals(body.get("error").get("data").get("property").asText()), "names the property to raise");
		check(body.get("error").get("message").asText().contains(BodyLimit.PROP_MCP_BODY), "the message tells what to set");
		// no Content-Length (chunked): at most one byte past the bound is consumed
		final long bound = BodyLimit.maxMcpBodyBytes();
		final CountingStream chunked = new CountingStream(bound * 4L);
		final McpEndpoint.Parsed byStream = McpEndpoint.parse(null, chunked, Locale.ENGLISH);
		check(byStream.refusal != null && byStream.refusal.getStatus() == 413, "a stream over the bound answers 413");
		check(chunked.read <= bound + 1L, "at most one byte past the bound was read: " + chunked.read);
		// French
		final McpEndpoint.Parsed fr = McpEndpoint.parse(String.valueOf(bound + 1L), new CountingStream(1), Locale.FRENCH);
		check(String.valueOf(fr.refusal.getEntity()).contains("dépasse") || String.valueOf(fr.refusal.getEntity()).contains("d\\u00e9passe"), "French text: " + fr.refusal.getEntity());
		// a message within the bound goes through
		final McpEndpoint.Parsed ok = McpEndpoint.parse(null, stream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"), Locale.ENGLISH);
		check(ok.refusal == null && ok.request != null && ok.request.isObject(), "a small message is parsed");
	}

	private static void batchIsBounded() throws Exception
	{
		final McpEndpoint.Parsed twenty = McpEndpoint.parse(null, stream(batch(20)), Locale.ENGLISH);
		check(twenty.refusal == null && twenty.request.size() == 20, "a batch of 20 is accepted");
		final McpEndpoint.Parsed tooLong = McpEndpoint.parse(null, stream(batch(21)), Locale.ENGLISH);
		check(tooLong.refusal != null && tooLong.refusal.getStatus() == 400, "a batch of 21 is refused");
		final JsonNode error = Json.parse(String.valueOf(tooLong.refusal.getEntity())).get("error");
		check(error.get("code").asInt() == -32600 && "CN_BATCH_TOO_LONG".equals(error.get("data").get("code").asText()), "coded -32600: " + error);
		check(error.get("data").get("limit").asInt() == 20 && BodyLimit.PROP_MCP_BATCH.equals(error.get("data").get("property").asText()), "bound and property");
	}

	private static void depthIsBounded() throws Exception
	{
		final McpEndpoint.Parsed deep = McpEndpoint.parse(null, stream(nested(65)), Locale.ENGLISH);
		check(deep.refusal != null && deep.refusal.getStatus() == 400, "65 levels are refused");
		check(String.valueOf(deep.refusal.getEntity()).contains("CN_JSON_TOO_DEEP"), "coded: " + deep.refusal.getEntity());
		check(McpEndpoint.parse(null, stream(nested(64)), Locale.ENGLISH).refusal == null, "64 levels are accepted");
		// a document far deeper than anything a parser should be fed is refused without being parsed into a tree
		check(McpEndpoint.parse(null, stream(nested(20000)), Locale.ENGLISH).refusal != null, "20000 levels are refused");
		// not JSON at all: the parse error, not a depth error
		final McpEndpoint.Parsed junk = McpEndpoint.parse(null, stream("{{{{ not json"), Locale.ENGLISH);
		check(junk.refusal != null && junk.refusal.getStatus() == 400 && String.valueOf(junk.refusal.getEntity()).contains("-32700"), "a parse error stays a parse error");
	}

	private static void rightBeforeBody() throws Exception
	{
		// no session: 401 and the body is untouched
		final CountingStream untouched = new CountingStream(1000);
		final Response anonymous = new McpEndpoint(context(null)).handle("1000", untouched);
		check(anonymous.getStatus() == 401 && untouched.read == 0, "anonymous: 401 and no byte read");
		// a session without any Connectors right: 403 and the body is untouched, even when it would be refused for its size
		final CountingStream huge = new CountingStream(100L * 1024L * 1024L);
		final Response noRole = new McpEndpoint(context(session())).handle(String.valueOf(100L * 1024L * 1024L), huge);
		check(noRole.getStatus() == 403, "no Connectors right: 403, not 413: " + noRole.getStatus());
		check(huge.read == 0, "no Connectors right: no byte read: " + huge.read);
		// a viewer past the bound gets the 413
		final Response viewer = new McpEndpoint(context(session(Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER)))).handle(String.valueOf(100L * 1024L * 1024L), new CountingStream(1));
		check(viewer.getStatus() == 413, "a role holder over the bound: 413: " + viewer.getStatus());
		// and a viewer within it is served
		final Response served = new McpEndpoint(context(session(Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER)))).handle(null,
			stream("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}"));
		check(served.getStatus() == 200 && String.valueOf(served.getEntity()).contains("\"id\":7"), "a role holder is served: " + served.getStatus());
	}

	private static void inJvmBridges() throws Exception
	{
		final Session session = session(Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER));
		final StringBuilder big = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"pad\":\"");
		for (long i = 0; i <= BodyLimit.maxMcpBodyBytes(); i += 1024L)
		{
			big.append(PAD);
		}
		big.append("\"}");
		final String mcp = new InJvmMcp().handle(session, big.toString());
		check(mcp.contains("CN_BODY_TOO_LARGE") && mcp.contains("-32600"), "InJvmMcp refuses a message over the bound: " + shorten(mcp));
		final String mcpDeep = new InJvmMcp().handle(session, nested(65));
		check(mcpDeep.contains("CN_JSON_TOO_DEEP"), "InJvmMcp refuses a deep message: " + shorten(mcpDeep));
		// no repository offline: the small message gets past the bounds and fails later, not with a bound's code
		check(!new InJvmMcp().handle(session, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").contains("CN_"), "a small message is not refused by a bound");
		final String contract = new InJvmContract().handle(session, big.toString());
		check(contract.contains("CN_BODY_TOO_LARGE") && contract.contains("INVALID_REQUEST"), "the contract refuses a message over the bound: " + shorten(contract));
		final String contractDeep = new InJvmContract().handle(session, nested(65));
		check(contractDeep.contains("CN_JSON_TOO_DEEP"), "the contract refuses a deep message: " + shorten(contractDeep));
	}

	private static void restRoutes() throws Exception
	{
		final String[] out = new String[1];
		final Response tooBig = ConnectorsResource.readBounded(String.valueOf(33L * 1024L * 1024L), new CountingStream(1), out, Locale.ENGLISH);
		check(tooBig != null && tooBig.getStatus() == 413 && String.valueOf(tooBig.getEntity()).contains("CN_BODY_TOO_LARGE"), "a REST document over 32 MiB: 413");
		check(String.valueOf(tooBig.getEntity()).contains(BodyLimit.PROP_REST_BODY), "names the REST property");
		final Response deep = ConnectorsResource.readBounded(null, stream(nested(65)), out, Locale.ENGLISH);
		check(deep != null && deep.getStatus() == 400 && String.valueOf(deep.getEntity()).contains("CN_JSON_TOO_DEEP"), "a deep REST document: 400");
		check(ConnectorsResource.readBounded(null, stream("{\"mappings\":[1]}"), out, Locale.ENGLISH) == null && out[0].contains("mappings"), "a small document is read");
		// csv and ndjson are not JSON documents: they pass the depth check
		check(ConnectorsResource.readBounded(null, stream("a;b\n1;2\n"), out, Locale.ENGLISH) == null, "csv passes");
		check(ConnectorsResource.readBounded(null, stream("{\"a\":1}\n{\"a\":2}\n"), out, Locale.ENGLISH) == null, "ndjson passes");
	}

	private static void propertiesRaiseTheBounds() throws Exception
	{
		System.setProperty(BodyLimit.PROP_MCP_BATCH, "30");
		check(McpEndpoint.parse(null, stream(batch(25)), Locale.ENGLISH).refusal == null, "-Debx.connectors.mcp.maxBatch=30 accepts 25");
		System.setProperty(BodyLimit.PROP_MCP_BODY, "64");
		check(McpEndpoint.parse(null, stream(batch(25)), Locale.ENGLISH).refusal.getStatus() == 413, "-Debx.connectors.mcp.maxBodyBytes=64 refuses a 25-message batch");
		System.setProperty(BodyLimit.PROP_MCP_BODY, String.valueOf(80L * 1024L * 1024L));
		check(BodyLimit.maxMcpBodyBytes() == 80L * 1024L * 1024L, "the body bound can be raised for 50 MB documents in base64");
	}

	private static final String PAD;
	static
	{
		final StringBuilder b = new StringBuilder();
		for (int i = 0; i < 1024; i++)
		{
			b.append('x');
		}
		PAD = b.toString();
	}

	private static String shorten(final String pText)
	{
		return pText.length() > 300 ? pText.substring(0, 300) : pText;
	}

	private static String batch(final int pSize)
	{
		final StringBuilder b = new StringBuilder("[");
		for (int i = 0; i < pSize; i++)
		{
			b.append(i == 0 ? "" : ",").append("{\"jsonrpc\":\"2.0\",\"id\":").append(i).append(",\"method\":\"ping\"}");
		}
		return b.append(']').toString();
	}

	private static String nested(final int pDepth)
	{
		final StringBuilder b = new StringBuilder();
		for (int i = 0; i < pDepth; i++)
		{
			b.append('[');
		}
		for (int i = 0; i < pDepth; i++)
		{
			b.append(']');
		}
		return b.toString();
	}

	private static InputStream stream(final String pText)
	{
		return new ByteArrayInputStream(pText.getBytes(StandardCharsets.UTF_8));
	}

	private static SessionContext context(final Session pSession)
	{
		return (SessionContext) Proxy.newProxyInstance(BodyLimitTest.class.getClassLoader(), new Class<?>[] { SessionContext.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				return "getSessionOrNull".equals(pMethod.getName()) || "getSession".equals(pMethod.getName()) ? pSession : null;
			}
		});
	}

	private static Session session(final Profile... pRoles)
	{
		final java.util.Set<Profile> roles = new java.util.HashSet<Profile>(java.util.Arrays.asList(pRoles));
		return (Session) Proxy.newProxyInstance(BodyLimitTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				if ("isSystemUser".equals(pMethod.getName()))
				{
					return Boolean.FALSE;
				}
				if ("isUserInRole".equals(pMethod.getName()))
				{
					return Boolean.valueOf(roles.contains(pArguments[0]));
				}
				if ("getLocale".equals(pMethod.getName()))
				{
					return Locale.ENGLISH;
				}
				return null;
			}
		});
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
