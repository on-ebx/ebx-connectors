package com.on.ebx.connect.ebx.rest;

import java.lang.management.ManagementFactory;
import java.util.Locale;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Session;

/**
 * The MCP tools of this module for the other modules of the SAME JVM, without HTTP and without an account: the
 * caller hands over its own EBX {@link Session} (the person in a user service, the workflow's session in a script
 * task), and every tool runs with it, exactly as the HTTP endpoint runs with the session it authenticated
 * ({@link McpEndpoint#dispatch} serves both).
 * <p>
 * Registered as the MBean {@link #OBJECT_NAME} of the platform MBean server at repository startup, unregistered
 * when the module goes away (so a reloaded context never leaves a bridge bound to a stopped class loader). The
 * operation takes a live {@code Session} object, which a remote JMX client cannot send (it is not serializable):
 * only code already running in this JVM, and already holding an EBX session, can use it. It honours the same
 * {@code ebx.connectors.mcp} switch as the HTTP endpoint.
 */
public final class InJvmMcp implements InJvmMcpMBean
{
	public static final String OBJECT_NAME = "com.on.ebx.connectors:type=InJvmMcp";

	@Override
	public String handle(final Object pSession, final String pMessage)
	{
		if (!(pSession instanceof Session))
		{
			return InJvmMcp.error(-32001, "An EBX Session is required: the in-JVM bridge runs every tool as its caller.");
		}
		if (!McpEndpoint.enabled())
		{
			return InJvmMcp.error(-32002, "The connectors MCP endpoint is disabled (ebx.connectors.mcp).");
		}
		final Session session = (Session) pSession;
		// audit C2-06: the same bounds as the HTTP endpoint, before anything is parsed (a message is one object, never a batch)
		final BodyLimit.Violation tooLong = BodyLimit.checkLength(pMessage, BodyLimit.maxMcpBodyBytes(), BodyLimit.PROP_MCP_BODY);
		final BodyLimit.Violation tooDeep = tooLong != null ? null : BodyLimit.checkDepth(pMessage, BodyLimit.maxMcpDepth(), BodyLimit.PROP_MCP_DEPTH);
		if (tooLong != null || tooDeep != null)
		{
			return InJvmMcp.error(-32600, (tooLong != null ? tooLong : tooDeep).message(Locale.ENGLISH));
		}
		// the caller's thread carries the caller's context class loader: run this module's code with its own
		final Thread thread = Thread.currentThread();
		final ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(InJvmMcp.class.getClassLoader());
		try
		{
			final JsonNode request = Json.parse(pMessage == null ? "" : pMessage);
			if (request == null || !request.isObject())
			{
				return InJvmMcp.error(-32600, "Invalid request");
			}
			final Locale locale = session.getLocale() == null ? Locale.ENGLISH : session.getLocale();
			final JsonNode answer = McpEndpoint.dispatch(request, session, Repository.getDefault(), locale);
			return answer == null ? "" : Json.write(answer);
		}
		catch (final Exception ex)
		{
			return InJvmMcp.error(-32603, "Internal error: " + ex);
		}
		finally
		{
			thread.setContextClassLoader(previous);
		}
	}

	private static String error(final int pCode, final String pMessage)
	{
		final ObjectNode answer = Json.object();
		answer.put("jsonrpc", "2.0");
		answer.putNull("id");
		final ObjectNode error = answer.putObject("error");
		error.put("code", pCode);
		error.put("message", pMessage);
		return Json.write(answer);
	}

	/** Registers the bridge, replacing a stale registration left by a previous load of this module. */
	public static void register()
	{
		try
		{
			final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			final ObjectName name = new ObjectName(InJvmMcp.OBJECT_NAME);
			if (server.isRegistered(name))
			{
				server.unregisterMBean(name);
			}
			server.registerMBean(new InJvmMcp(), name);
		}
		catch (final Exception ex)
		{
			throw new IllegalStateException("Could not register the in-JVM MCP bridge " + InJvmMcp.OBJECT_NAME, ex);
		}
	}

	/** Unregisters the bridge (a later load of this module registers its own again); never throws. */
	public static void unregister()
	{
		try
		{
			final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			final ObjectName name = new ObjectName(InJvmMcp.OBJECT_NAME);
			if (server.isRegistered(name))
			{
				server.unregisterMBean(name);
			}
		}
		catch (final Exception ex)
		{
			// shutting down: nothing to do
		}
	}
}
