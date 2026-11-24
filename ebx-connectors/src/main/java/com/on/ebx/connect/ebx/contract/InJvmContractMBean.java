package com.on.ebx.connect.ebx.contract;

/**
 * The management interface of {@link InJvmContract}: JDK types only, so another web application of the same JVM reads
 * it and calls it through the platform MBean server by reflection, without sharing any class of this module.
 */
public interface InJvmContractMBean
{
	/** Attribute {@code ContractName}: the name of the contract ({@value ContractCatalog#NAME}). */
	String getContractName();

	/** Attribute {@code ContractVersion}: its semantic version, {@code MAJOR.MINOR.PATCH}. */
	String getContractVersion();

	/** The answer of {@code contract.describe} as JSON text: operations, parameters, rights, error types. No session needed. */
	String describe();

	/**
	 * One JSON-RPC 2.0 request: {@code method} = an operation of the contract, {@code params} = its parameters (an object).
	 *
	 * @param pSession the caller's {@code com.orchestranetworks.service.Session} (typed Object: the EBX classes are loaded
	 *        once, by Tomcat's common class loader, so the caller's instance IS a Session here); may be null for
	 *        {@code contract.describe} only
	 * @param pMessage the request as JSON text
	 * @return the JSON-RPC answer as text: {@code result}, or {@code error {code, message, data {type, operation}}}
	 */
	String handle(Object pSession, String pMessage);
}
