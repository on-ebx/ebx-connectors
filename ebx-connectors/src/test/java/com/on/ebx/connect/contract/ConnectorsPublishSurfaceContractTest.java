package com.on.ebx.connect.contract;

import java.lang.reflect.Method;

import com.on.ebx.connect.ebx.mcp.ConnectorsMcpServer;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.on.ebx.connect.ebx.rest.ConnectorsResource;
import com.orchestranetworks.mcp.annotations.McpTool;
import com.orchestranetworks.mcp.annotations.ToolBehavior;

/**
 * Structural contract for the {@code connectors_publish} surface (product publication, §2.4 of
 * {@code SPEC-product-publication.md}): the tool catalogue entry, its native MCP wrapper, and the REST
 * route are declared consistently, so a caller discovering one surface finds the same tool on the
 * others. No EBX repository or connector call is exercised here — {@code ProductPublisherTest} and
 * {@code SnowflakePublicationSqlTest} cover the behaviour.
 */
public final class ConnectorsPublishSurfaceContractTest
{
	private ConnectorsPublishSurfaceContractTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final ConnectorsTools.Tool tool = ConnectorsTools.tool(ConnectorsTools.T_PUBLISH);
		check(tool != null, "connectors_publish is missing from ConnectorsTools.CATALOG");
		check("connectors_publish".equals(ConnectorsTools.T_PUBLISH), "T_PUBLISH constant name drifted");
		check("additive".equals(tool.behavior), "connectors_publish must be additive (it writes a Publication row and, for a real run, target tables)");
		check(hasRequiredParam(tool, "mappings"), "connectors_publish must require 'mappings'");
		check(hasRequiredParam(tool, "schema"), "connectors_publish must require 'schema'");
		check(hasRequiredParam(tool, "product"), "connectors_publish must require 'product'");
		check(hasParam(tool, "contractVersion") && !hasRequiredParam(tool, "contractVersion"), "connectors_publish's contractVersion must be optional (default 1)");
		check(hasParam(tool, "dryRun"), "connectors_publish must expose dryRun");
		check(hasParam(tool, "recreate"), "connectors_publish must expose recreate");
		check(hasParam(tool, "max"), "connectors_publish must expose max");
		check(hasParam(tool, "dataspace"), "connectors_publish must expose dataspace");

		final Method mcpMethod = findMcpToolMethod(ConnectorsMcpServer.class, ConnectorsTools.T_PUBLISH);
		check(mcpMethod != null, "ConnectorsMcpServer has no @McpTool wrapper named " + ConnectorsTools.T_PUBLISH);
		final McpTool annotation = mcpMethod.getAnnotation(McpTool.class);
		check(annotation.behavior() == ToolBehavior.ADDITIVE, "the native MCP wrapper must declare ToolBehavior.ADDITIVE, like the tool catalogue");
		check(annotation.openWorldHint(), "connectors_publish reaches an external system (the connector): openWorldHint should be true, like connectors_sync/connectors_call");

		check(hasPostPublish(ConnectorsResource.class), "ConnectorsResource has no POST method mapped to /publish");

		System.out.println("PASS: connectors_publish is consistent across the tool catalogue, the native MCP wrapper and the REST route");
	}

	private static boolean hasParam(final ConnectorsTools.Tool pTool, final String pName)
	{
		for (final ConnectorsTools.Param param : pTool.params)
		{
			if (param.name.equals(pName))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasRequiredParam(final ConnectorsTools.Tool pTool, final String pName)
	{
		for (final ConnectorsTools.Param param : pTool.params)
		{
			if (param.name.equals(pName))
			{
				return param.required;
			}
		}
		return false;
	}

	private static Method findMcpToolMethod(final Class<?> pServerClass, final String pToolName)
	{
		for (final Method method : pServerClass.getDeclaredMethods())
		{
			final McpTool annotation = method.getAnnotation(McpTool.class);
			if (annotation != null && pToolName.equals(annotation.name()))
			{
				return method;
			}
		}
		return null;
	}

	private static boolean hasPostPublish(final Class<?> pResourceClass)
	{
		for (final Method method : pResourceClass.getDeclaredMethods())
		{
			final jakarta.ws.rs.Path path = method.getAnnotation(jakarta.ws.rs.Path.class);
			final jakarta.ws.rs.POST post = method.getAnnotation(jakarta.ws.rs.POST.class);
			if (post != null && path != null && "/publish".equals(path.value()))
			{
				return true;
			}
		}
		return false;
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
