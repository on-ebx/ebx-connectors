package com.on.ebx.connect.ebx.contract;

import java.lang.management.ManagementFactory;
import java.util.Locale;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.Redaction;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Session;

/**
 * The versioned contract of ebx-connectors for the other modules of the same JVM (audit F-26 of the MDM Factory, D-18
 * of the data catalog): "ask the language model" and "declare, read, delete and run a flow" without reading the
 * Connectors tables by path, without decrypting a key and without writing mapping rows as data. Described by
 * {@link ContractCatalog}, run by {@link ContractService}.
 * <p>
 * Transport: the MBean {@value #OBJECT_NAME} of the platform MBean server, JSON-RPC 2.0 as text, the caller's EBX
 * {@code Session} passed as an {@code Object} (JDK types only across the class loaders of two web applications; the
 * EBX classes are shared through Tomcat's common class loader). A second MBean rather than new tools of the in-JVM MCP
 * bridge ({@code type=InJvmMcp}), because the MCP tools are also served over HTTP ({@code /ebx-connectors/rest/mcp}) and
 * listed to AI agents, and their catalogue changes with the agents' needs: this contract is for Java callers only, is
 * reachable from nowhere else (a remote JMX client cannot send a Session, which is not serializable), and its version
 * moves only when its own operations do. Its rights are those of the module ({@code ConnectorsAccess}); its flows go
 * through the same tools, validation and audit as the MCP ones.
 * <p>
 * Registered at repository startup, unregistered when the module stops (a stale registration would pin a stopped
 * class loader). {@code -Debx.connectors.contract=false} answers every call {@link ContractError#DISABLED}.
 */
public final class InJvmContract implements InJvmContractMBean
{
	public static final String OBJECT_NAME = "com.on.ebx.connectors:type=Contract";
	/** JVM property: {@code false} switches the contract off (read at each call). */
	public static final String ENABLED_PROPERTY = "ebx.connectors.contract";

	/** Runs one operation; the EBX service in production, a fake in the offline tests. */
	interface Dispatcher
	{
		ObjectNode call(Session pSession, String pOperation, JsonNode pParams) throws ContractException;
	}

	static final Dispatcher SERVICE = new Dispatcher()
	{
		@Override
		public ObjectNode call(final Session pSession, final String pOperation, final JsonNode pParams) throws ContractException
		{
			final Locale locale = pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();
			// describe needs no repository, and a call without a session is refused before one is used
			final Repository repository = pSession == null ? null : Repository.getDefault();
			return new ContractService(pSession, repository, locale).call(pOperation, pParams);
		}
	};

	@Override
	public String getContractName()
	{
		return ContractCatalog.NAME;
	}

	@Override
	public String getContractVersion()
	{
		return ContractCatalog.VERSION;
	}

	@Override
	public String describe()
	{
		return Json.write(ContractCatalog.describe());
	}

	@Override
	public String handle(final Object pSession, final String pMessage)
	{
		// the caller's thread carries the caller's context class loader: run this module's code with its own
		final Thread thread = Thread.currentThread();
		final ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(InJvmContract.class.getClassLoader());
		try
		{
			return Json.write(InJvmContract.respond(pSession, pMessage, InJvmContract.SERVICE));
		}
		finally
		{
			thread.setContextClassLoader(previous);
		}
	}

	/** True unless {@code -Debx.connectors.contract=false}. */
	public static boolean enabled()
	{
		final String value = System.getProperty(InJvmContract.ENABLED_PROPERTY);
		return value == null || !"false".equalsIgnoreCase(value.trim());
	}

	/** One request, answered as a JSON-RPC object; never throws. */
	static ObjectNode respond(final Object pSession, final String pMessage, final Dispatcher pDispatcher)
	{
		JsonNode id = null;
		String method = null;
		try
		{
			if (!InJvmContract.enabled())
			{
				throw new ContractException(ContractError.DISABLED, "the ebx-connectors contract is switched off on this instance (-D" + InJvmContract.ENABLED_PROPERTY + "=false)");
			}
			// audit C2-06: the same bounds as the MCP endpoint, checked before anything is parsed (a message is one object, never a batch)
			final com.on.ebx.connect.ebx.rest.BodyLimit.Violation tooLong = com.on.ebx.connect.ebx.rest.BodyLimit.checkLength(pMessage,
				com.on.ebx.connect.ebx.rest.BodyLimit.maxMcpBodyBytes(), com.on.ebx.connect.ebx.rest.BodyLimit.PROP_MCP_BODY);
			final com.on.ebx.connect.ebx.rest.BodyLimit.Violation tooDeep = tooLong != null ? null : com.on.ebx.connect.ebx.rest.BodyLimit.checkDepth(pMessage,
				com.on.ebx.connect.ebx.rest.BodyLimit.maxMcpDepth(), com.on.ebx.connect.ebx.rest.BodyLimit.PROP_MCP_DEPTH);
			if (tooLong != null || tooDeep != null)
			{
				throw new ContractException(ContractError.INVALID_REQUEST, (tooLong != null ? tooLong : tooDeep).message(java.util.Locale.ENGLISH));
			}
			final JsonNode request;
			try
			{
				request = Json.parse(pMessage == null ? "" : pMessage);
			}
			catch (final Exception ex)
			{
				throw new ContractException(ContractError.INVALID_REQUEST, "the message is not JSON");
			}
			if (request == null || !request.isObject())
			{
				throw new ContractException(ContractError.INVALID_REQUEST, "the message must be one JSON-RPC request object");
			}
			id = request.get("id");
			final JsonNode methodNode = request.get("method");
			if (methodNode == null || !methodNode.isTextual() || methodNode.asText().trim().isEmpty())
			{
				throw new ContractException(ContractError.INVALID_REQUEST, "the request has no method");
			}
			method = methodNode.asText().trim();
			if (pSession != null && !(pSession instanceof Session))
			{
				throw new ContractException(ContractError.SESSION_REQUIRED, "the first argument must be an EBX Session (or null for contract.describe)");
			}
			return InJvmContract.result(id, pDispatcher.call((Session) pSession, method, request.get("params")));
		}
		catch (final ContractException ex)
		{
			return InJvmContract.error(id, ex.getType(), ex.getMessage(), method);
		}
		catch (final RuntimeException ex)
		{
			InJvmContract.logFailure(method, ex);
			return InJvmContract.error(id, ContractError.INTERNAL, "Internal error: " + Redaction.redact(String.valueOf(ex.getMessage())), method);
		}
	}

	private static void logFailure(final String pMethod, final RuntimeException pFailure)
	{
		try
		{
			ConnectorService.log().error(Redaction.redact("[connectors] contract operation " + pMethod + " failed: " + pFailure));
		}
		catch (final RuntimeException ex)
		{
			// no kernel log (offline): the answer carries the failure
		}
	}

	private static ObjectNode result(final JsonNode pId, final JsonNode pResult)
	{
		final ObjectNode out = Json.object();
		out.put("jsonrpc", "2.0");
		out.set("id", pId == null ? NullNode.getInstance() : pId);
		out.set("result", pResult);
		return out;
	}

	/** A typed error: {@code error.code} the number, {@code error.data.type} the name a caller tests. */
	static ObjectNode error(final JsonNode pId, final ContractError pType, final String pMessage, final String pOperation)
	{
		final ObjectNode out = Json.object();
		out.put("jsonrpc", "2.0");
		out.set("id", pId == null ? NullNode.getInstance() : pId);
		final ObjectNode error = out.putObject("error");
		error.put("code", pType.code());
		error.put("message", pMessage == null ? pType.name() : pMessage);
		final ObjectNode data = error.putObject("data");
		data.put("type", pType.name());
		if (pOperation != null)
		{
			data.put("operation", pOperation);
		}
		data.put("contract", ContractCatalog.NAME);
		data.put("version", ContractCatalog.VERSION);
		return out;
	}

	/** Registers the contract, replacing a stale registration left by a previous load of this module. */
	public static void register()
	{
		try
		{
			final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			final ObjectName name = new ObjectName(InJvmContract.OBJECT_NAME);
			if (server.isRegistered(name))
			{
				server.unregisterMBean(name);
			}
			server.registerMBean(new InJvmContract(), name);
		}
		catch (final Exception ex)
		{
			throw new IllegalStateException("Could not register the in-JVM contract " + InJvmContract.OBJECT_NAME, ex);
		}
	}

	/** Unregisters the contract (a later load of this module registers its own again); never throws. */
	public static void unregister()
	{
		try
		{
			final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			final ObjectName name = new ObjectName(InJvmContract.OBJECT_NAME);
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
