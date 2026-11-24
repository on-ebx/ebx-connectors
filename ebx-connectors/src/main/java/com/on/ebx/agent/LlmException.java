package com.on.ebx.agent;

/**
 * A failure of one turn against an LLM provider: transport, or an HTTP status that survived every retry
 * ({@link LlmClient} retries 429 and 5xx on its own). The message carries the HTTP status and a
 * shortened response body, never the request headers — a caller must not log this message as-is if the
 * provider ever echoes something sensitive in an error body, but it is safe with respect to what this
 * library itself sends (the caller's authentication headers are never included).
 */
public final class LlmException extends Exception
{
	private static final long serialVersionUID = 1L;

	private final int status;

	public LlmException(final int pStatus, final String pMessage)
	{
		super(pMessage);
		this.status = pStatus;
	}

	public LlmException(final String pMessage, final Throwable pCause)
	{
		super(pMessage, pCause);
		this.status = 0;
	}

	/** The HTTP status; 0 for a transport failure (no response was ever received). */
	public int getStatus()
	{
		return this.status;
	}
}
