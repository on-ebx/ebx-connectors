package com.on.ebx.connect.ebx.rest;

/**
 * The management interface of {@link InJvmMcp}: JDK types only, so another webapp of the same JVM can invoke it
 * through the platform MBean server by reflection, without sharing any class of this module.
 */
public interface InJvmMcpMBean
{
	/**
	 * One JSON-RPC message of the MCP protocol, answered exactly as {@code POST /ebx-connectors/rest/mcp} would
	 * answer it for the same session.
	 *
	 * @param pSession an EBX {@code com.orchestranetworks.service.Session} (typed Object: the EBX classes are
	 *        loaded once, by Tomcat's common class loader, so the caller's instance IS a Session here)
	 * @param pMessage the JSON-RPC request (or notification) as text
	 * @return the JSON-RPC answer as text; empty for a notification
	 */
	String handle(Object pSession, String pMessage);
}
