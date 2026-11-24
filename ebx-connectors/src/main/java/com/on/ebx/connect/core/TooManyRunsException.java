package com.on.ebx.connect.core;

/**
 * An asynchronous run was not started because as many runs as the instance allows are already
 * running or waiting. Nothing was written for it: the caller may simply try again later.
 */
public final class TooManyRunsException extends IllegalStateException
{
	private static final long serialVersionUID = 1L;

	public TooManyRunsException(final String pMessage)
	{
		super(pMessage);
	}
}
