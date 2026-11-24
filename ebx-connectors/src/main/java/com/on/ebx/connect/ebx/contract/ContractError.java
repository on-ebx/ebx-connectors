package com.on.ebx.connect.ebx.contract;

/**
 * The typed errors of the in-JVM contract: {@code error.data.type} of a JSON-RPC error answer carries the name, which a
 * caller tests; {@code error.code} carries the number, stable too; {@code error.message} is English text for a log and is
 * not part of the contract. A new type is a minor version of the contract (a caller must treat an unknown type as
 * {@link #OPERATION_FAILED}).
 */
public enum ContractError
{
	/** The message is not a JSON-RPC request object (not JSON, an array, no method). */
	INVALID_REQUEST(-32600),
	/** No operation of that name in this version of the contract. */
	UNKNOWN_OPERATION(-32601),
	/** A parameter is missing, unknown, of the wrong type or out of its bounds; the message names it. */
	INVALID_ARGUMENT(-32602),
	/** An operation other than {@code contract.describe} was called without an EBX Session. */
	SESSION_REQUIRED(-32001),
	/** The contract is switched off on this instance ({@code -Debx.connectors.contract=false}). */
	DISABLED(-32002),
	/** The session does not hold the Connectors right the operation needs; the message names it. */
	ACCESS_DENIED(-32003),
	/** The connector or the flow designated does not exist. */
	NOT_FOUND(-32004),
	/** More than one root mapping carries the flow key as label: the caller must rename one. */
	AMBIGUOUS_KEY(-32005),
	/** The connector exists but cannot be used: inactive, misconfigured, its secret not set or held in clear, its host refused. */
	CONNECTOR_UNUSABLE(-32010),
	/** No connector was named and no default language-model connector can be chosen. */
	NO_DEFAULT_LLM(-32011),
	/** The connector named is not a language-model connector (type {@code llm}). */
	NOT_LLM(-32012),
	/** The model's API answered an error or something that is not an answer. */
	LLM_FAILED(-32013),
	/** The model did not answer within the timeout. */
	TIMEOUT(-32014),
	/** The operation is not offered for that connector (since 1.1.0: llm.chat on a Google Gemini or Vertex AI connector). */
	NOT_SUPPORTED(-32015),
	/** The flow document was refused by the validation of the mapping upsert, or a run answered an error. */
	OPERATION_FAILED(-32020),
	/** A failure of the module itself; the kernel log has the detail. */
	INTERNAL(-32603);

	private final int code;

	ContractError(final int pCode)
	{
		this.code = pCode;
	}

	/** The JSON-RPC error code. */
	public int code()
	{
		return this.code;
	}
}
