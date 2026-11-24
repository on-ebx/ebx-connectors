package com.on.ebx.connect.spi;

/** A connector call failed. {@link #getStatus()} carries the HTTP status when there is one. */
public class ConnectorException extends Exception
{
	private static final long serialVersionUID = 1L;
	private final int status;

	public ConnectorException(final String pMessage)
	{
		this(pMessage, 0, null);
	}

	public ConnectorException(final String pMessage, final Throwable pCause)
	{
		this(pMessage, 0, pCause);
	}

	public ConnectorException(final String pMessage, final int pStatus, final Throwable pCause)
	{
		super(pMessage, pCause);
		this.status = pStatus;
	}

	public int getStatus()
	{
		return this.status;
	}
}
