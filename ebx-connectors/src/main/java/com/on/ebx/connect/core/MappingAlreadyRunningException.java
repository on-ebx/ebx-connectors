package com.on.ebx.connect.core;

/**
 * An asynchronous run was not started because another run of the same mapping is in progress (audit C-20).
 * Nothing was written for it. A synchronous start gets the same message as the error of its outcome.
 */
public final class MappingAlreadyRunningException extends IllegalStateException
{
	private static final long serialVersionUID = 1L;

	public MappingAlreadyRunningException(final String pMessage)
	{
		super(pMessage);
	}
}
