package com.on.ebx.connect.core;

/** Explicit authorization boundary for governance operations exposed to UI, REST or MCP adapters. */
public interface ConnectorGovernanceAccess
{
	enum Permission { READ, MANAGE, APPROVE, AUDIT }
	boolean isAllowed(String pActor, Permission pPermission, String pResourceId);
}
