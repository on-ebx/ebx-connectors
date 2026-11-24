package com.on.ebx.agent;

/** A failure of the MCP exchange itself (transport, HTTP status, JSON-RPC error); a tool that answers an error is not one. */
public final class McpException extends Exception
{
	private static final long serialVersionUID = 1L;

	private final int code;

	public McpException(final int pCode, final String pMessage)
	{
		super(pMessage);
		this.code = pCode;
	}

	public McpException(final String pMessage, final Throwable pCause)
	{
		super(pMessage, pCause);
		this.code = 0;
	}

	/** The HTTP status or the JSON-RPC error code; 0 for a transport failure. */
	public int getCode()
	{
		return this.code;
	}
}
