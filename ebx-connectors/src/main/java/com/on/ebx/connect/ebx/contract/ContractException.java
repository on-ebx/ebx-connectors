package com.on.ebx.connect.ebx.contract;

/** A refusal or a failure of a contract operation, answered as a typed JSON-RPC error. */
public final class ContractException extends Exception
{
	private static final long serialVersionUID = 1L;

	private final ContractError type;

	public ContractException(final ContractError pType, final String pMessage)
	{
		super(pMessage);
		this.type = pType;
	}

	public ContractError getType()
	{
		return this.type;
	}
}
