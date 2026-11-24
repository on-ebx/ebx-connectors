package com.on.ebx.connect.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.orchestranetworks.service.Session;

/**
 * The trace an operator reads to know who ran or changed what: one INFO line in the kernel log for every MCP
 * tool call (whatever the transport) and every REST route that asks the right {@link ConnectorsAccess.Right#RUN}
 * or {@link ConnectorsAccess.Right#MANAGE}. A read is not traced.
 * <p>
 * The line names the user, the tool or the route, the connection, mapping, run or conflict the call designates,
 * the mode flags that change what the call does ({@code dryRun}, {@code apply}, {@code allowWrite}), the outcome
 * and the duration. It never carries a document, an input, a record key, a predicate, a URL or a comment: a line
 * of log must not become a copy of the data or of a secret. The outcome is the call's: a synchronisation that
 * answered with rejected records is {@value #OK}; its detail is in the run and the sync log.
 */
public final class ConnectorsAudit
{
	public static final String OK = "ok";
	public static final String ERROR = "error";
	public static final String REFUSED = "refused";

	/** Longest identifier written; a longer one is cut, so that a caller cannot fill the log with one call. */
	private static final int MAX_TOKEN = 80;

	/** The arguments of a tool that designate something, in the order they are written. */
	private static final String[] IDENTIFIERS = { "connector", "operation", "mapping", "mappings", "connectors", "runId", "id", "decision" };
	/** The arguments that change what a call does, written only when true. */
	private static final String[] FLAGS = { "dryRun", "apply", "allowWrite" };

	/** Where the lines go. */
	public interface Sink
	{
		void write(String pLine);
	}

	private static final Sink KERNEL_LOG = new Sink()
	{
		@Override
		public void write(final String pLine)
		{
			ConnectorService.log().info(pLine);
		}
	};

	private static volatile Sink sink = ConnectorsAudit.KERNEL_LOG;

	private ConnectorsAudit()
	{
	}

	/** Replaces where the lines go (the kernel log by default; null restores it). */
	public static void use(final Sink pSink)
	{
		ConnectorsAudit.sink = pSink == null ? ConnectorsAudit.KERNEL_LOG : pSink;
	}

	/** True when a call that asks {@code pNeeded} leaves a line: it runs or changes something. */
	public static boolean traced(final ConnectorsAccess.Right pNeeded)
	{
		return pNeeded != null && pNeeded.covers(ConnectorsAccess.Right.RUN);
	}

	/** One MCP tool call. Never throws: a trace that cannot be written must not fail the call. */
	public static void tool(final Session pSession, final String pTool, final JsonNode pArguments, final String pOutcome, final long pMillis)
	{
		try
		{
			ConnectorsAudit.sink.write(ConnectorsAudit.line(ConnectorsAccess.userOf(pSession), "tool", pTool, ConnectorsAudit.subject(pTool, pArguments), pOutcome, -1, pMillis));
		}
		catch (final RuntimeException ex)
		{
			// the call already has its answer
		}
	}

	/**
	 * One call of the in-JVM contract for sibling modules ({@code com.on.ebx.connect.ebx.contract}) that is not already
	 * traced as a tool; {@code pSubject} comes from {@link #append} and never carries a prompt or a document. Never throws.
	 */
	public static void contract(final Session pSession, final String pOperation, final String pSubject, final String pOutcome, final long pMillis)
	{
		try
		{
			ConnectorsAudit.sink.write(ConnectorsAudit.line(ConnectorsAccess.userOf(pSession), "contract", pOperation, pSubject, pOutcome, -1, pMillis));
		}
		catch (final RuntimeException ex)
		{
			// the call already has its answer
		}
	}

	/** One REST route; {@code pSubject} comes from {@link #append}. Never throws. */
	public static void rest(final String pUser, final String pRoute, final String pSubject, final String pOutcome, final int pStatus, final long pMillis)
	{
		try
		{
			ConnectorsAudit.sink.write(ConnectorsAudit.line(pUser, "rest", pRoute, pSubject, pOutcome, pStatus, pMillis));
		}
		catch (final RuntimeException ex)
		{
			// the answer already left
		}
	}

	/** Pure: the line. {@code pStatus} is the HTTP status of a REST answer, negative when there is none. */
	static String line(final String pUser, final String pKind, final String pName, final String pSubject, final String pOutcome, final int pStatus, final long pMillis)
	{
		final StringBuilder out = new StringBuilder("[connectors] audit: user=").append(ConnectorsAudit.token(pUser));
		out.append(' ').append(pKind).append('=').append(ConnectorsAudit.token(pName));
		if (pSubject != null && !pSubject.isEmpty())
		{
			out.append(' ').append(pSubject);
		}
		out.append(" outcome=").append(pOutcome);
		if (pStatus >= 0)
		{
			out.append(" status=").append(pStatus);
		}
		return out.append(" ms=").append(Math.max(0L, pMillis)).toString();
	}

	/**
	 * Pure: what a tool call designates, as {@code name=value} pairs. Only the arguments listed in this class are
	 * read, and only when they are short scalars (or a list of them): {@code mapping} is an identifier for most
	 * tools and a whole document for {@code connectors_mapping_upsert}, of which only the identifiers are kept.
	 */
	static String subject(final String pTool, final JsonNode pArguments)
	{
		final StringBuilder out = new StringBuilder();
		if (pArguments == null || !pArguments.isObject())
		{
			return "";
		}
		for (final String name : ConnectorsAudit.IDENTIFIERS)
		{
			JsonNode value = pArguments.get(name);
			if ("mapping".equals(name) && ConnectorsTools.T_UPSERT.equals(pTool))
			{
				value = ConnectorsAudit.document(value);
				if (value != null && value.isObject())
				{
					ConnectorsAudit.append(out, "mapping", ConnectorsAudit.scalar(value.get("id"), "(new)"));
					ConnectorsAudit.append(out, "connector", ConnectorsAudit.scalar(value.get("connector"), null));
					ConnectorsAudit.append(out, "operation", ConnectorsAudit.scalar(value.get("operation"), null));
				}
				continue;
			}
			ConnectorsAudit.append(out, name, ConnectorsAudit.scalar(value, null));
		}
		for (final String name : ConnectorsAudit.FLAGS)
		{
			final JsonNode value = pArguments.get(name);
			if (value != null && (value.isBoolean() ? value.asBoolean() : value.isTextual() && "true".equalsIgnoreCase(value.asText().trim())))
			{
				ConnectorsAudit.append(out, name, "true");
			}
		}
		return out.toString();
	}

	/** Adds {@code name=value} to a subject; nothing when the value is null or blank. */
	public static void append(final StringBuilder pSubject, final String pName, final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return;
		}
		if (pSubject.length() > 0)
		{
			pSubject.append(' ');
		}
		pSubject.append(pName).append('=').append(ConnectorsAudit.token(pValue));
	}

	/** A mapping document sent as an object or as its JSON text; null for anything else. */
	private static JsonNode document(final JsonNode pValue)
	{
		if (pValue == null || pValue.isObject())
		{
			return pValue;
		}
		if (pValue.isTextual() && pValue.asText().trim().startsWith("{"))
		{
			try
			{
				return com.on.ebx.connect.core.Json.parse(pValue.asText());
			}
			catch (final Exception ex)
			{
				return null;
			}
		}
		return null;
	}

	/** A text, a number or a list of them as one value; {@code pIfAbsent} for nothing; null for an object or a document sent as text. */
	private static String scalar(final JsonNode pValue, final String pIfAbsent)
	{
		if (pValue == null || pValue.isNull())
		{
			return pIfAbsent;
		}
		if (pValue.isArray())
		{
			final StringBuilder list = new StringBuilder();
			for (final JsonNode one : pValue)
			{
				final String item = ConnectorsAudit.scalar(one, null);
				if (item != null && list.length() <= ConnectorsAudit.MAX_TOKEN)
				{
					list.append(list.length() == 0 ? "" : ",").append(item);
				}
			}
			return list.length() == 0 ? pIfAbsent : list.toString();
		}
		if (!pValue.isTextual() && !pValue.isNumber())
		{
			return null;
		}
		final String text = pValue.asText().trim();
		if (text.isEmpty())
		{
			return pIfAbsent;
		}
		// an identifier is never a JSON document
		return text.charAt(0) == '{' || text.charAt(0) == '[' ? null : text;
	}

	/** Pure: a value as one token of the line - no space, no control character, no line break, bounded. */
	static String token(final String pValue)
	{
		if (pValue == null)
		{
			return "-";
		}
		final String value = pValue.trim();
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < value.length() && out.length() < ConnectorsAudit.MAX_TOKEN; i++)
		{
			final char c = value.charAt(i);
			out.append(c <= 0x20 || c == 0x7f || c == '\u0085' || c == ' ' || c == ' ' ? '_' : c);
		}
		if (out.length() == 0)
		{
			return "-";
		}
		return value.length() > ConnectorsAudit.MAX_TOKEN ? out.append("...").toString() : out.toString();
	}
}
