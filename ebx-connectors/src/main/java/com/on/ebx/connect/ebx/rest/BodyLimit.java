package com.on.ebx.connect.ebx.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.on.ebx.connect.ebx.ui.ConnectorSyncServiceDeclaration;
import com.on.ebx.connect.ebx.ui.Messages;

/**
 * Bounds of what the module reads from a caller (audit C2-06): the size of a request body, the number of messages of a
 * JSON-RPC batch and the nesting depth of a JSON body. Every route that reads a body (the MCP endpoint, the REST routes
 * that take a document, the two in-JVM bridges) checks the caller's right FIRST, then reads at most one byte past the bound
 * ({@link #readBounded}) and refuses with a coded answer that names the JVM property to raise. The server's Jackson (2.14)
 * has no read constraints of its own, so the depth is counted here by streaming the tokens, before anything is parsed into a
 * tree.
 * <p>
 * Each bound is a JVM property, read at each request (a change needs no restart of the module, only of the JVM that holds
 * the property); a missing, non-numeric or non-positive value reads as the default.
 */
public final class BodyLimit
{
	/** MCP endpoint and the in-JVM bridges: bytes of one message or batch. Default 4 MiB. */
	public static final String PROP_MCP_BODY = "ebx.connectors.mcp.maxBodyBytes";
	/** MCP endpoint and the in-JVM bridges: messages of one JSON-RPC batch. Default 20. */
	public static final String PROP_MCP_BATCH = "ebx.connectors.mcp.maxBatch";
	/** MCP endpoint and the in-JVM bridges: nesting depth of a message. Default 64. */
	public static final String PROP_MCP_DEPTH = "ebx.connectors.mcp.maxJsonDepth";
	/** REST routes that take a document (webhook, configuration import, publish, OpenAPI, templates): bytes. Default 32 MiB. */
	public static final String PROP_REST_BODY = "ebx.connectors.rest.maxBodyBytes";
	/** REST routes that take a JSON document: nesting depth. Default 64. */
	public static final String PROP_REST_DEPTH = "ebx.connectors.rest.maxJsonDepth";

	public static final long DEFAULT_MCP_BODY = 4L * 1024L * 1024L;
	public static final int DEFAULT_MCP_BATCH = 20;
	public static final int DEFAULT_DEPTH = 64;
	public static final long DEFAULT_REST_BODY = 32L * 1024L * 1024L;

	public static final String CODE_BODY = "CN_BODY_TOO_LARGE";
	public static final String CODE_BATCH = "CN_BATCH_TOO_LONG";
	public static final String CODE_DEPTH = "CN_JSON_TOO_DEEP";

	private static final JsonFactory FACTORY = new JsonFactory();

	private BodyLimit()
	{
	}

	/** A bound that was exceeded: the HTTP status, the code, the bundle key, the property to raise and the bound. */
	public static final class Violation
	{
		public final int status;
		public final String code;
		public final String messageKey;
		public final String property;
		public final long limit;

		Violation(final int pStatus, final String pCode, final String pKey, final String pProperty, final long pLimit)
		{
			this.status = pStatus;
			this.code = pCode;
			this.messageKey = pKey;
			this.property = pProperty;
			this.limit = pLimit;
		}

		/** {@code [CODE] text}, in the language of the reader (English or French). */
		public String message(final Locale pLocale)
		{
			return "[" + this.code + "] " + BodyLimit.text(pLocale, this.messageKey, String.valueOf(this.limit), this.property);
		}
	}

	/** Thrown by {@link #readBounded} when the stream holds more than the bound. */
	public static final class TooLarge extends Exception
	{
		private static final long serialVersionUID = 1L;
		public final Violation violation;

		TooLarge(final Violation pViolation)
		{
			super(pViolation.code);
			this.violation = pViolation;
		}
	}

	private static String text(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		final Locale locale = pLocale != null && "fr".equals(pLocale.getLanguage()) ? Locale.FRENCH : Locale.ENGLISH;
		return Messages.get(ConnectorSyncServiceDeclaration.class, locale, pKey, pArgs);
	}

	// ------------------------------------------------------------------ the bounds, as they are right now

	public static long maxMcpBodyBytes()
	{
		return BodyLimit.longProperty(BodyLimit.PROP_MCP_BODY, BodyLimit.DEFAULT_MCP_BODY);
	}

	public static int maxMcpBatch()
	{
		return (int) Math.min(Integer.MAX_VALUE, BodyLimit.longProperty(BodyLimit.PROP_MCP_BATCH, BodyLimit.DEFAULT_MCP_BATCH));
	}

	public static int maxMcpDepth()
	{
		return (int) Math.min(Integer.MAX_VALUE, BodyLimit.longProperty(BodyLimit.PROP_MCP_DEPTH, BodyLimit.DEFAULT_DEPTH));
	}

	public static long maxRestBodyBytes()
	{
		return BodyLimit.longProperty(BodyLimit.PROP_REST_BODY, BodyLimit.DEFAULT_REST_BODY);
	}

	public static int maxRestDepth()
	{
		return (int) Math.min(Integer.MAX_VALUE, BodyLimit.longProperty(BodyLimit.PROP_REST_DEPTH, BodyLimit.DEFAULT_DEPTH));
	}

	static long longProperty(final String pProperty, final long pDefault)
	{
		final String value = System.getProperty(pProperty);
		if (value == null || value.trim().isEmpty())
		{
			return pDefault;
		}
		try
		{
			final long parsed = Long.parseLong(value.trim());
			return parsed > 0L ? parsed : pDefault;
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	// ------------------------------------------------------------------ the checks

	/** The violation of a declared {@code Content-Length} above the bound, null when absent, unreadable or within it. */
	public static Violation declaredLength(final String pContentLength, final long pMax, final String pProperty)
	{
		if (pContentLength == null || pContentLength.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return Long.parseLong(pContentLength.trim()) > pMax ? BodyLimit.tooLarge(pMax, pProperty) : null;
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}

	/** The bytes of the stream; at most {@code pMax + 1} are consumed, and {@link TooLarge} says the stream held more than {@code pMax}. */
	public static byte[] readBounded(final InputStream pIn, final long pMax, final String pProperty) throws IOException, TooLarge
	{
		if (pIn == null)
		{
			return new byte[0];
		}
		final ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(pMax, 8192L));
		final byte[] buffer = new byte[8192];
		long total = 0L;
		while (true)
		{
			final int n = pIn.read(buffer, 0, (int) Math.min(buffer.length, pMax + 1L - total));
			if (n < 0)
			{
				return out.toByteArray();
			}
			total += n;
			if (total > pMax)
			{
				throw new TooLarge(BodyLimit.tooLarge(pMax, pProperty));
			}
			out.write(buffer, 0, n);
		}
	}

	/** The text form of a message already in memory (an in-JVM call): its length in characters, a lower bound of its bytes. */
	public static Violation checkLength(final String pMessage, final long pMax, final String pProperty)
	{
		return pMessage != null && pMessage.length() > pMax ? BodyLimit.tooLarge(pMax, pProperty) : null;
	}

	private static Violation tooLarge(final long pMax, final String pProperty)
	{
		return new Violation(413, BodyLimit.CODE_BODY, "rest.limit.body", pProperty, pMax);
	}

	/** The violation of a batch of more than {@code pMax} messages, null when within it. */
	public static Violation checkBatch(final int pSize, final int pMax, final String pProperty)
	{
		return pSize > pMax ? new Violation(400, BodyLimit.CODE_BATCH, "rest.limit.batch", pProperty, pMax) : null;
	}

	/** Violation for a text nested deeper than the bound, found by streaming its tokens; null when within it or not JSON (the parse reports that). */
	public static Violation checkDepth(final String pText, final int pMaxDepth, final String pProperty)
	{
		try
		{
			return BodyLimit.depth(BodyLimit.FACTORY.createParser(pText == null ? "" : pText), pMaxDepth, pProperty);
		}
		catch (final IOException ex)
		{
			return null;
		}
	}

	/** {@link #checkDepth(String, int, String)} for bytes. */
	public static Violation checkDepth(final byte[] pBody, final int pMaxDepth, final String pProperty)
	{
		try
		{
			return BodyLimit.depth(BodyLimit.FACTORY.createParser(pBody), pMaxDepth, pProperty);
		}
		catch (final IOException ex)
		{
			return null;
		}
	}

	private static Violation depth(final JsonParser pParser, final int pMaxDepth, final String pProperty)
	{
		try
		{
			int depth = 0;
			for (JsonToken t = pParser.nextToken(); t != null; t = pParser.nextToken())
			{
				if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY)
				{
					depth++;
					if (depth > pMaxDepth)
					{
						return new Violation(400, BodyLimit.CODE_DEPTH, "rest.limit.depth", pProperty, pMaxDepth);
					}
				}
				else if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY)
				{
					depth--;
				}
			}
			return null;
		}
		catch (final JsonParseException notJson)
		{
			// not JSON (or truncated): the parse that follows reports it, with its own message
			return null;
		}
		catch (final IOException ex)
		{
			return null;
		}
		finally
		{
			try
			{
				pParser.close();
			}
			catch (final IOException ex)
			{
				// nothing left to release
			}
		}
	}

	/** A JSON body as UTF-8 text, read bounded (the caller has checked its right first); throws {@link TooLarge} past the bound. */
	public static String readText(final InputStream pIn, final String pContentLength, final long pMax, final String pProperty) throws IOException, TooLarge
	{
		final Violation declared = BodyLimit.declaredLength(pContentLength, pMax, pProperty);
		if (declared != null)
		{
			throw new TooLarge(declared);
		}
		return new String(BodyLimit.readBounded(pIn, pMax, pProperty), "UTF-8");
	}
}
