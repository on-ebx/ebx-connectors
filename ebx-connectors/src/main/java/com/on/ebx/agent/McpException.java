package com.on.ebx.agent;

/**
 * A failure of the MCP exchange itself (transport, HTTP status, JSON-RPC error); a tool that answers an error is not one.
 * {@link #getKind()} tells which, so that a caller (and the person who reads its message) can tell a server that did not answer
 * from one that answered something this client refuses to read (audit F3-100, 1.4.2).
 */
public final class McpException extends Exception
{
	private static final long serialVersionUID = 1L;

	/** What kind of failure it is. */
	public enum Kind
	{
		/** The server could not be reached: connection refused, timeout, a broken stream. */
		UNREACHABLE,
		/** The server answered with an HTTP status of 400 or more, or with a JSON-RPC error. */
		SERVER_ERROR,
		/** The server answered, and the answer goes beyond a read bound of the JSON reader (nesting, string, number or name length): nothing is wrong with the connection. */
		RESPONSE_OVER_LIMIT,
		/** The server answered something that is not readable as JSON (or as the stream of events it announced). */
		UNREADABLE_ANSWER
	}

	private final int code;
	private final Kind kind;

	public McpException(final int pCode, final String pMessage)
	{
		super(pMessage);
		this.code = pCode;
		this.kind = Kind.SERVER_ERROR;
	}

	/** A transport failure (kind {@link Kind#UNREACHABLE}). */
	public McpException(final String pMessage, final Throwable pCause)
	{
		this(Kind.UNREACHABLE, pMessage, pCause);
	}

	public McpException(final Kind pKind, final String pMessage, final Throwable pCause)
	{
		super(pMessage, pCause);
		this.code = 0;
		this.kind = pKind;
	}

	/** The HTTP status or the JSON-RPC error code; 0 for a transport failure. */
	public int getCode()
	{
		return this.code;
	}

	/** Which failure it is: server unreachable, server error, answer over a read bound, answer unreadable. */
	public Kind getKind()
	{
		return this.kind;
	}
}
