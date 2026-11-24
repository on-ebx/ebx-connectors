package com.on.ebx.connect.ebx.contract;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.ebx.ConnectorsAccess;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;

/**
 * The in-JVM contract offline: its name and version, the description a caller reads at startup, the validation of
 * every parameter, the right each operation asks, the JSON-RPC transport and its typed errors, the MBean as a caller of
 * another web application reaches it, the default language-model connector and the arguments of a run. No repository.
 */
public final class InJvmContractTest
{
	private static int checks;

	private InJvmContractTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		InJvmContractTest.version();
		InJvmContractTest.description();
		InJvmContractTest.validation();
		InJvmContractTest.rights();
		InJvmContractTest.transport();
		InJvmContractTest.mbean();
		InJvmContractTest.defaultLlm();
		InJvmContractTest.runArguments();
		InJvmContractTest.compatibleWith10();
		System.out.println("PASS: in-JVM contract " + ContractCatalog.NAME + " " + ContractCatalog.VERSION + " (" + InJvmContractTest.checks + " checks)");
	}

	private static void version()
	{
		yes("ebx-connectors.sibling".equals(ContractCatalog.NAME), "contract name");
		yes(ContractCatalog.VERSION.matches("[0-9]+\\.[0-9]+\\.[0-9]+"), "the version is MAJOR.MINOR.PATCH: " + ContractCatalog.VERSION);
		yes(ContractCatalog.compatible("1.0.0", "1.0.0") && ContractCatalog.compatible("1.3.0", "1.2.5") && ContractCatalog.compatible("1.2.6", "1.2.5"),
			"same major and at least the needed minor/patch is compatible");
		yes(!ContractCatalog.compatible("2.0.0", "1.0.0") && !ContractCatalog.compatible("1.0.0", "2.0.0") && !ContractCatalog.compatible("1.1.0", "1.3.0")
			&& !ContractCatalog.compatible("1.2.4", "1.2.5"), "another major, or an older minor/patch, is not");
		yes(!ContractCatalog.compatible("1.0", "1.0.0") && !ContractCatalog.compatible(null, "1.0.0") && !ContractCatalog.compatible("1.0.0", "x")
			&& !ContractCatalog.compatible("1.0.0-beta", "1.0.0"), "anything else than MAJOR.MINOR.PATCH is not compatible");
		yes(ContractCatalog.compatible(ContractCatalog.VERSION, "1.0.0"), "this build serves a caller written for 1.0.0");
		eq("1.2.0", ContractCatalog.VERSION, "wave 13: run.get and run.stop make 1.2.0 (new operations: minor; wave 12's llm.chat made 1.1.0)");
		yes(ContractCatalog.compatible(ContractCatalog.VERSION, "1.1.0") && ContractCatalog.compatible(ContractCatalog.VERSION, "1.2.0"),
			"this build serves a caller written for 1.1.0 or for 1.2.0");
	}

	private static void description() throws Exception
	{
		final ObjectNode described = (ObjectNode) Json.parse(Json.write(ContractCatalog.describe()));
		final JsonNode ask = InJvmContractTest.op(described, "llm.ask");
		eq(ContractCatalog.NAME, described.path("name").asText(), "describe: name");
		eq(ContractCatalog.VERSION, described.path("version").asText(), "describe: version");
		yes(described.path("transport").asText().contains(InJvmContract.OBJECT_NAME), "describe: the transport names the MBean");
		final List<String> names = new ArrayList<String>();
		for (final JsonNode operation : described.path("operations"))
		{
			names.add(operation.path("name").asText());
			yes(operation.path("description").asText().length() > 20 && operation.path("result").asText().length() > 5, "describe: " + operation.path("name") + " is explained");
			for (final JsonNode param : operation.path("params"))
			{
				yes(param.has("name") && param.has("type") && param.has("required") && param.path("description").asText().length() > 3,
					"describe: every parameter has name, type, required and description: " + param);
			}
		}
		eq(Arrays.asList("contract.describe", "llm.connectors", "llm.ask", "llm.chat", "flows.get", "flows.put", "flows.delete", "flows.run", "run.get", "run.stop"), names,
			"describe: the operations of 1.2");
		for (final JsonNode operation : described.path("operations"))
		{
			final String name = operation.path("name").asText();
			eq("llm.chat".equals(name) ? "1.1.0" : "run.get".equals(name) || "run.stop".equals(name) ? "1.2.0" : "1.0.0", operation.path("since").asText(),
				"describe: since of " + name);
		}
		final JsonNode runGet = InJvmContractTest.op(described, "run.get");
		yes("READ".equals(runGet.path("right").asText()) && InJvmContractTest.param(runGet, "runId").path("required").asBoolean()
			&& !InJvmContractTest.param(runGet, "callsMax").path("required").asBoolean() && runGet.path("result").asText().contains("OUTBOUND")
			&& runGet.path("result").asText().contains("INTERRUPTED"), "describe: run.get asks READ, runId required, answers the execution{} of an outbound run");
		final JsonNode runStop = InJvmContractTest.op(described, "run.stop");
		yes("RUN".equals(runStop.path("right").asText()) && InJvmContractTest.param(runStop, "runId").path("required").asBoolean(), "describe: run.stop asks RUN, runId required");
		yes(InJvmContractTest.param(InJvmContractTest.op(described, "flows.run"), "async").path("description").asText().contains("outbound flow with a scope"),
			"describe: flows.run says async now serves an outbound scope send");
		final JsonNode chat = InJvmContractTest.op(described, "llm.chat");
		yes("RUN".equals(chat.path("right").asText()) && "array".equals(InJvmContractTest.param(chat, "messages").path("type").asText())
			&& InJvmContractTest.param(chat, "messages").path("required").asBoolean() && InJvmContractTest.param(chat, "messages").path("max").asInt() == ContractCatalog.CHAT_MESSAGES_MAX
			&& !InJvmContractTest.param(chat, "tools").path("required").asBoolean(), "describe: llm.chat asks RUN, messages required, tools optional, bounds readable");
		yes(chat.path("result").asText().contains("toolCalls") && chat.path("result").asText().contains("maxTokensApplied") && chat.path("result").asText().contains("capped"),
			"describe: llm.chat's answer fields");
		yes(ask.path("result").asText().contains("maxTokensApplied") && ask.path("result").asText().contains("capped")
			&& InJvmContractTest.param(ask, "maxTokens").path("description").asText().contains("at most the connector's own"), "describe: llm.ask says it is capped");
		yes(errorsHas(described, "NOT_SUPPORTED=-32015"), "describe: the 1.1 error type");
		final Set<String> errors = new HashSet<String>();
		for (final JsonNode error : described.path("errors"))
		{
			errors.add(error.path("type").asText() + "=" + error.path("code").asInt());
		}
		eq(ContractError.values().length, errors.size(), "describe: every error type, each once");
		yes(errors.contains("ACCESS_DENIED=-32003") && errors.contains("INVALID_ARGUMENT=-32602") && errors.contains("TIMEOUT=-32014"), "describe: types with their codes");
		final Set<Integer> codes = new HashSet<Integer>();
		for (final ContractError error : ContractError.values())
		{
			yes(codes.add(Integer.valueOf(error.code())), "error codes are distinct: " + error);
		}
		yes("RUN".equals(ask.path("right").asText()), "describe: llm.ask asks RUN");
		final JsonNode maxTokens = InJvmContractTest.param(ask, "maxTokens");
		yes("integer".equals(maxTokens.path("type").asText()) && maxTokens.path("min").asInt() == 1 && maxTokens.path("max").asInt() == ContractCatalog.MAX_TOKENS_MAX,
			"describe: bounds are readable: " + maxTokens);
		yes(InJvmContractTest.param(ask, "prompt").path("required").asBoolean() && !InJvmContractTest.param(ask, "connector").path("required").asBoolean(),
			"describe: prompt required, connector optional");
		yes(InJvmContractTest.op(described, "contract.describe").path("right").isNull(), "describe needs no right");
	}

	private static boolean errorsHas(final JsonNode pDescribed, final String pTypeAndCode)
	{
		for (final JsonNode error : pDescribed.path("errors"))
		{
			if (pTypeAndCode.equals(error.path("type").asText() + "=" + error.path("code").asInt()))
			{
				return true;
			}
		}
		return false;
	}

	private static JsonNode op(final JsonNode pDescribed, final String pName)
	{
		for (final JsonNode operation : pDescribed.path("operations"))
		{
			if (pName.equals(operation.path("name").asText()))
			{
				return operation;
			}
		}
		throw new AssertionError("no operation " + pName);
	}

	private static JsonNode param(final JsonNode pOperation, final String pName)
	{
		for (final JsonNode param : pOperation.path("params"))
		{
			if (pName.equals(param.path("name").asText()))
			{
				return param;
			}
		}
		throw new AssertionError("no parameter " + pName);
	}

	private static void validation() throws Exception
	{
		final ContractCatalog.Operation ask = ContractCatalog.operation(ContractCatalog.LLM_ASK);
		final ObjectNode ok = ContractCatalog.validate(ask, Json.parse("{\"prompt\":\"Hello\",\"maxTokens\":4096,\"json\":true,\"timeoutMs\":120000,\"connector\":null}"));
		yes(ok.path("maxTokens").asInt() == 4096 && ok.path("json").asBoolean() && !ok.has("connector"), "valid llm.ask kept, a null value counts as absent: " + ok);
		InJvmContractTest.refused(ask, "{}", "'prompt' is required");
		InJvmContractTest.refused(ask, "{\"prompt\":\"x\",\"temperature\":0.2}", "unknown parameter 'temperature'");
		InJvmContractTest.refused(ask, "{\"prompt\":\"x\",\"maxTokens\":\"4096\"}", "must be an integer");
		InJvmContractTest.refused(ask, "{\"prompt\":\"x\",\"maxTokens\":40.5}", "must be an integer");
		InJvmContractTest.refused(ask, "{\"prompt\":\"x\",\"maxTokens\":0}", "between 1 and");
		InJvmContractTest.refused(ask, "{\"prompt\":\"x\",\"maxTokens\":65537}", "between 1 and");
		InJvmContractTest.refused(ask, "{\"prompt\":\"x\",\"timeoutMs\":999}", "between 1000 and");
		InJvmContractTest.refused(ask, "{\"prompt\":\"x\",\"timeoutMs\":600001}", "between 1000 and");
		InJvmContractTest.refused(ask, "{\"prompt\":\"x\",\"json\":\"true\"}", "must be true or false");
		InJvmContractTest.refused(ask, "{\"prompt\":\"   \"}", "must not be blank");
		InJvmContractTest.refused(ask, "{\"prompt\":42}", "must be a string");
		InJvmContractTest.refused(ask, "[\"prompt\"]", "params must be a JSON object");
		final StringBuilder longKey = new StringBuilder();
		for (int i = 0; i <= ContractCatalog.KEY_MAX_LENGTH; i++)
		{
			longKey.append('k');
		}
		InJvmContractTest.refused(ContractCatalog.operation(ContractCatalog.FLOWS_GET), "{\"key\":\"" + longKey + "\"}", "longer than 200");
		InJvmContractTest.refused(ContractCatalog.operation(ContractCatalog.FLOWS_PUT), "{\"key\":\"k\",\"flow\":\"{}\"}", "must be a JSON object");
		InJvmContractTest.refused(ContractCatalog.operation(ContractCatalog.FLOWS_RUN), "{\"key\":\"k\",\"mappingId\":0}", "between 1 and");
		yes(ContractCatalog.validate(ContractCatalog.operation(ContractCatalog.DESCRIBE), null).size() == 0, "describe takes no parameter");
		InJvmContractTest.refused(ContractCatalog.operation(ContractCatalog.DESCRIBE), "{\"verbose\":true}", "unknown parameter");
		yes(ContractCatalog.operation("flows.list") == null, "an operation this version does not have is unknown");
	}

	private static void refused(final ContractCatalog.Operation pOperation, final String pParams, final String pExpected) throws Exception
	{
		try
		{
			ContractCatalog.validate(pOperation, Json.parse(pParams));
			yes(false, pOperation.name + " " + pParams + " must be refused");
		}
		catch (final ContractException ex)
		{
			yes(ex.getType() == ContractError.INVALID_ARGUMENT && ex.getMessage().contains(pExpected), pOperation.name + " " + pParams + ": " + ex.getMessage());
		}
	}

	private static void rights() throws Exception
	{
		// the right of each operation, decided on purpose: a person with RUN asks the model, only a designer declares flows
		eq(null, ContractCatalog.operation(ContractCatalog.DESCRIBE).right, "describe: no right");
		eq(ConnectorsAccess.Right.READ, ContractCatalog.operation(ContractCatalog.LLM_CONNECTORS).right, "llm.connectors: READ");
		eq(ConnectorsAccess.Right.RUN, ContractCatalog.operation(ContractCatalog.LLM_ASK).right, "llm.ask: RUN, not MANAGE");
		eq(ConnectorsAccess.Right.RUN, ContractCatalog.operation(ContractCatalog.LLM_CHAT).right, "llm.chat: RUN, like llm.ask (one turn, the tools run in the caller)");
		eq(ConnectorsAccess.Right.READ, ContractCatalog.operation(ContractCatalog.FLOWS_GET).right, "flows.get: READ");
		eq(ConnectorsAccess.Right.MANAGE, ContractCatalog.operation(ContractCatalog.FLOWS_PUT).right, "flows.put: MANAGE, as connectors_mapping_upsert");
		eq(ConnectorsAccess.Right.MANAGE, ContractCatalog.operation(ContractCatalog.FLOWS_DELETE).right, "flows.delete: MANAGE, as connectors_mapping_delete");
		eq(ConnectorsAccess.Right.RUN, ContractCatalog.operation(ContractCatalog.FLOWS_RUN).right, "flows.run: RUN, as connectors_sync");
		eq(ConnectorsAccess.Right.READ, ContractCatalog.operation(ContractCatalog.RUN_GET).right, "run.get: READ");
		eq(ConnectorsAccess.Right.RUN, ContractCatalog.operation(ContractCatalog.RUN_STOP).right, "run.stop: RUN");
		eq(ConnectorsAccess.required(com.on.ebx.connect.ebx.mcp.ConnectorsTools.T_RUN), ContractCatalog.operation(ContractCatalog.RUN_GET).right,
			"run.get asks what connectors_run_get asks");
		eq(ConnectorsAccess.required(com.on.ebx.connect.ebx.mcp.ConnectorsTools.T_STOP), ContractCatalog.operation(ContractCatalog.RUN_STOP).right,
			"run.stop asks what connectors_run_stop asks");
		eq(ConnectorsAccess.required(com.on.ebx.connect.ebx.mcp.ConnectorsTools.T_UPSERT), ContractCatalog.operation(ContractCatalog.FLOWS_PUT).right,
			"flows.put asks what the tool it calls asks");
		eq(ConnectorsAccess.required(com.on.ebx.connect.ebx.mcp.ConnectorsTools.T_SYNC), ContractCatalog.operation(ContractCatalog.FLOWS_RUN).right,
			"flows.run asks what the tool it calls asks");
		final ContractCatalog.Operation ask = ContractCatalog.operation(ContractCatalog.LLM_ASK);
		yes(!ContractCatalog.allowed(ask, null) && !ContractCatalog.allowed(ask, ConnectorsAccess.Right.READ), "llm.ask: refused without a right and to a viewer");
		yes(ContractCatalog.allowed(ask, ConnectorsAccess.Right.RUN) && ContractCatalog.allowed(ask, ConnectorsAccess.Right.MANAGE), "llm.ask: allowed to an operator and a designer");
		yes(!ContractCatalog.allowed(ContractCatalog.operation(ContractCatalog.FLOWS_PUT), ConnectorsAccess.Right.RUN), "flows.put: refused to an operator");
		yes(ContractCatalog.allowed(ContractCatalog.operation(ContractCatalog.DESCRIBE), null), "describe: open");

		// through the service, with sessions holding the default roles (the refusal comes before any repository use)
		final Session viewer = InJvmContractTest.session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_VIEWER));
		final Session operator = InJvmContractTest.session(false, Role.forSpecificRole(ConnectorsAccess.DEFAULT_OPERATOR));
		yes(ConnectorsAccess.granted(operator) == ConnectorsAccess.Right.RUN && ConnectorsAccess.granted(viewer) == ConnectorsAccess.Right.READ, "fake sessions hold their role");
		InJvmContractTest.serviceRefused(viewer, ContractCatalog.LLM_ASK, "{\"prompt\":\"Hello\"}", ContractError.ACCESS_DENIED, "requires the Connectors right RUN");
		InJvmContractTest.serviceRefused(operator, ContractCatalog.FLOWS_PUT, "{\"key\":\"k\",\"flow\":{}}", ContractError.ACCESS_DENIED, "requires the Connectors right MANAGE");
		InJvmContractTest.serviceRefused(InJvmContractTest.session(false), ContractCatalog.FLOWS_GET, "{\"key\":\"k\"}", ContractError.ACCESS_DENIED, "READ");
		InJvmContractTest.serviceRefused(null, ContractCatalog.LLM_ASK, "{\"prompt\":\"Hello\"}", ContractError.SESSION_REQUIRED, "pass an EBX Session");
		InJvmContractTest.serviceRefused(viewer, ContractCatalog.LLM_ASK, "{}", ContractError.INVALID_ARGUMENT, "'prompt' is required");
		InJvmContractTest.serviceRefused(viewer, "flows.list", "{}", ContractError.UNKNOWN_OPERATION, "contract.describe lists them");
		InJvmContractTest.serviceRefused(viewer, ContractCatalog.RUN_STOP, "{\"runId\":\"R1\"}", ContractError.ACCESS_DENIED, "requires the Connectors right RUN");
		InJvmContractTest.serviceRefused(InJvmContractTest.session(false), ContractCatalog.RUN_GET, "{\"runId\":\"R1\"}", ContractError.ACCESS_DENIED, "READ");
		InJvmContractTest.serviceRefused(viewer, ContractCatalog.RUN_GET, "{}", ContractError.INVALID_ARGUMENT, "'runId' is required");
		InJvmContractTest.serviceRefused(viewer, ContractCatalog.RUN_GET, "{\"runId\":\"R1\",\"callsMax\":501}", ContractError.INVALID_ARGUMENT, "callsMax");
		InJvmContractTest.serviceRefused(operator, ContractCatalog.RUN_STOP, "{\"runId\":\"R1\",\"force\":true}", ContractError.INVALID_ARGUMENT, "unknown parameter 'force'");
		final String chat = "{\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
		InJvmContractTest.serviceRefused(viewer, ContractCatalog.LLM_CHAT, chat, ContractError.ACCESS_DENIED, "requires the Connectors right RUN");
		InJvmContractTest.serviceRefused(null, ContractCatalog.LLM_CHAT, chat, ContractError.SESSION_REQUIRED, "pass an EBX Session");
		InJvmContractTest.serviceRefused(viewer, ContractCatalog.LLM_CHAT, "{\"messages\":\"Hello\"}", ContractError.INVALID_ARGUMENT, "must be a JSON array");
		yes(new ContractService(null, null, null).call(ContractCatalog.DESCRIBE, null).path("version").asText().equals(ContractCatalog.VERSION), "describe needs no session");
	}

	private static void serviceRefused(final Session pSession, final String pOperation, final String pParams, final ContractError pType, final String pExpected) throws Exception
	{
		try
		{
			new ContractService(pSession, null, null).call(pOperation, Json.parse(pParams));
			yes(false, pOperation + " must be refused");
		}
		catch (final ContractException ex)
		{
			yes(ex.getType() == pType && ex.getMessage().contains(pExpected), pOperation + ": " + ex.getType() + " " + ex.getMessage());
		}
	}

	private static void transport() throws Exception
	{
		final List<String> called = new ArrayList<String>();
		final InJvmContract.Dispatcher fake = new InJvmContract.Dispatcher()
		{
			@Override
			public ObjectNode call(final Session pSession, final String pOperation, final JsonNode pParams) throws ContractException
			{
				called.add(pOperation + (pParams == null ? "" : " " + Json.write(pParams)));
				if ("boom".equals(pOperation))
				{
					throw new IllegalStateException("broken");
				}
				final ObjectNode out = Json.object();
				out.put("ok", true);
				return out;
			}
		};
		ObjectNode answer = InJvmContract.respond(null, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"flows.get\",\"params\":{\"key\":\"k\"}}", fake);
		yes(answer.path("id").asInt() == 7 && answer.path("result").path("ok").asBoolean() && "2.0".equals(answer.path("jsonrpc").asText()), "result answered with its id: " + answer);
		eq("flows.get {\"key\":\"k\"}", called.get(0), "method and params handed over");
		InJvmContractTest.typed(InJvmContract.respond(null, "not json", fake), ContractError.INVALID_REQUEST, -32600);
		InJvmContractTest.typed(InJvmContract.respond(null, "[{\"method\":\"flows.get\"}]", fake), ContractError.INVALID_REQUEST, -32600);
		InJvmContractTest.typed(InJvmContract.respond(null, "{\"id\":1}", fake), ContractError.INVALID_REQUEST, -32600);
		InJvmContractTest.typed(InJvmContract.respond(null, null, fake), ContractError.INVALID_REQUEST, -32600);
		answer = InJvmContract.respond("not a session", "{\"id\":2,\"method\":\"flows.get\"}", fake);
		InJvmContractTest.typed(answer, ContractError.SESSION_REQUIRED, -32001);
		yes(answer.path("id").asInt() == 2 && "flows.get".equals(answer.path("error").path("data").path("operation").asText())
			&& ContractCatalog.VERSION.equals(answer.path("error").path("data").path("version").asText()), "an error names the operation and the contract version: " + answer);
		answer = InJvmContract.respond(null, "{\"id\":3,\"method\":\"boom\"}", fake);
		InJvmContractTest.typed(answer, ContractError.INTERNAL, -32603);
		yes(answer.path("error").path("message").asText().contains("broken"), "an unexpected failure is answered, never thrown: " + answer);
		yes(called.size() == 2, "nothing dispatched for a malformed request");

		// the real service, without a session: describe answers, every other operation is refused before EBX is touched
		answer = InJvmContract.respond(null, "{\"id\":4,\"method\":\"contract.describe\"}", InJvmContract.SERVICE);
		eq(ContractCatalog.VERSION, answer.path("result").path("version").asText(), "describe through the transport without a session");
		InJvmContractTest.typed(InJvmContract.respond(null, "{\"id\":5,\"method\":\"llm.ask\",\"params\":{\"prompt\":\"Hi\"}}", InJvmContract.SERVICE), ContractError.SESSION_REQUIRED, -32001);
		InJvmContractTest.typed(InJvmContract.respond(null, "{\"id\":6,\"method\":\"llm.ask\",\"params\":{\"prompt\":\"Hi\",\"maxTokens\":-1}}", InJvmContract.SERVICE),
			ContractError.INVALID_ARGUMENT, -32602);
		InJvmContractTest.typed(InJvmContract.respond(null, "{\"id\":7,\"method\":\"flows.list\"}", InJvmContract.SERVICE), ContractError.UNKNOWN_OPERATION, -32601);

		// switched off: everything answers DISABLED, describe included
		System.setProperty(InJvmContract.ENABLED_PROPERTY, "FALSE");
		try
		{
			yes(!InJvmContract.enabled(), "-Debx.connectors.contract=false switches it off, whatever the case");
			InJvmContractTest.typed(InJvmContract.respond(null, "{\"id\":8,\"method\":\"contract.describe\"}", InJvmContract.SERVICE), ContractError.DISABLED, -32002);
		}
		finally
		{
			System.clearProperty(InJvmContract.ENABLED_PROPERTY);
		}
		yes(InJvmContract.enabled(), "on by default");
	}

	private static void typed(final ObjectNode pAnswer, final ContractError pType, final int pCode)
	{
		yes(!pAnswer.has("result") && pAnswer.path("error").path("code").asInt() == pCode && pType.name().equals(pAnswer.path("error").path("data").path("type").asText()),
			"typed error " + pType + ": " + pAnswer);
	}

	/** Exactly what a sibling module does: attributes and operations of the platform MBean, JDK types only. */
	private static void mbean() throws Exception
	{
		final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
		final ObjectName name = new ObjectName(InJvmContract.OBJECT_NAME);
		InJvmContract.register();
		try
		{
			InJvmContract.register();
			yes(server.isRegistered(name), "registered, a second registration replaces the first");
			eq(ContractCatalog.NAME, server.getAttribute(name, "ContractName"), "attribute ContractName");
			eq(ContractCatalog.VERSION, server.getAttribute(name, "ContractVersion"), "attribute ContractVersion");
			final JsonNode described = Json.parse(String.valueOf(server.invoke(name, "describe", new Object[0], new String[0])));
			eq(ContractCatalog.VERSION, described.path("version").asText(), "operation describe()");
			final String message = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"contract.describe\"}";
			final JsonNode answer = Json.parse(String.valueOf(server.invoke(name, "handle", new Object[] { null, message },
				new String[] { "java.lang.Object", "java.lang.String" })));
			eq(ContractCatalog.NAME, answer.path("result").path("name").asText(), "operation handle(Object, String)");
			final ClassLoader before = Thread.currentThread().getContextClassLoader();
			server.invoke(name, "handle", new Object[] { null, "{\"id\":1,\"method\":\"llm.ask\",\"params\":{\"prompt\":\"x\"}}" },
				new String[] { "java.lang.Object", "java.lang.String" });
			yes(Thread.currentThread().getContextClassLoader() == before, "the caller's context class loader is given back");
		}
		finally
		{
			InJvmContract.unregister();
		}
		yes(!server.isRegistered(name), "unregistered at stop");
		InJvmContract.unregister();
	}

	private static void defaultLlm()
	{
		final ConnectorConfig claude = InJvmContractTest.config("Claude", "llm", true, ConnectorConfig.AuthType.BEARER, "sk-1");
		final ConnectorConfig openai = InJvmContractTest.config("OpenAI", "llm", true, ConnectorConfig.AuthType.BEARER, "TO_BE_SET");
		final ConnectorConfig mistral = InJvmContractTest.config("Mistral", "llm", false, ConnectorConfig.AuthType.BEARER, "ms-1");
		final ConnectorConfig gleif = InJvmContractTest.config("GLEIF", "gleif", true, ConnectorConfig.AuthType.NONE, null);
		final ConnectorConfig ollama = InJvmContractTest.config("Ollama", "llm", true, ConnectorConfig.AuthType.NONE, null);
		yes(ContractService.usable(claude) && !ContractService.usable(openai) && !ContractService.usable(mistral) && ContractService.usable(ollama),
			"usable = active, configured, its key set (none needed without authentication)");
		yes(ContractService.isLlm(claude) && !ContractService.isLlm(gleif), "only type llm counts");
		ContractService.DefaultLlm choice = ContractService.defaultLlm(Arrays.asList(gleif, claude, openai, mistral), null);
		yes("Claude".equals(choice.id) && "only-usable".equals(choice.source) && choice.problem == null, "the only usable llm connector is the default");
		choice = ContractService.defaultLlm(Arrays.asList(claude, ollama), " ");
		yes(choice.id == null && "none".equals(choice.source) && choice.problem.contains("2 llm connectors are usable") && choice.problem.contains(ContractService.DEFAULT_LLM_PROPERTY),
			"two usable: no guess, the reason says how to choose: " + choice.problem);
		choice = ContractService.defaultLlm(Arrays.asList(claude, ollama), "Ollama");
		yes("Ollama".equals(choice.id) && "property".equals(choice.source), "the property chooses among several");
		choice = ContractService.defaultLlm(Arrays.asList(claude, gleif), "GLEIF");
		yes(choice.id == null && choice.problem.contains("names no llm connector"), "a property naming a connector of another type is refused, not followed");
		choice = ContractService.defaultLlm(Arrays.asList(openai, mistral, gleif), null);
		yes(choice.id == null && choice.problem.startsWith("no llm connector is usable"), "none usable: said");

		// wave 12: the setting of the Connectors dataset first, then the JVM property, then the only usable one
		choice = ContractService.defaultLlm(Arrays.asList(claude, ollama), "Ollama", "Claude");
		yes("Ollama".equals(choice.id) && "setting".equals(choice.source), "the dataset setting wins over the property");
		choice = ContractService.defaultLlm(Arrays.asList(claude, ollama), " ", "Claude");
		yes("Claude".equals(choice.id) && "property".equals(choice.source), "an empty setting: the property");
		choice = ContractService.defaultLlm(Arrays.asList(claude, gleif), null, null);
		yes("Claude".equals(choice.id) && "only-usable".equals(choice.source), "neither: the only usable one");
		choice = ContractService.defaultLlm(Arrays.asList(claude, gleif, mistral), "Mistral", null);
		yes("Mistral".equals(choice.id) && "setting".equals(choice.source), "the setting may name an inactive llm connector: the call then says CONNECTOR_UNUSABLE, no silent switch");
		choice = ContractService.defaultLlm(Arrays.asList(claude, gleif), "GLEIF", "Claude");
		yes(choice.id == null && "none".equals(choice.source) && choice.problem.contains("setting 'Default language-model connector'") && choice.problem.contains("names no llm connector"),
			"a setting naming no llm connector is refused, never skipped for the property: " + choice.problem);
		choice = ContractService.defaultLlm(Arrays.asList(claude, ollama), null, null);
		yes(choice.id == null && choice.problem.contains("setting 'Default language-model connector'"), "two usable: the reason names the setting to fill");
	}

	private static ConnectorConfig config(final String pId, final String pType, final boolean pActive, final ConnectorConfig.AuthType pAuth, final String pKey)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId(pId);
		config.setType(pType);
		config.setActive(pActive);
		config.setAuthType(pAuth);
		config.setApiKey(pKey);
		return config;
	}

	private static void runArguments() throws Exception
	{
		final ObjectNode args = (ObjectNode) Json.parse("{\"key\":\"Catalog scan: X\",\"recordKey\":\"42\",\"scope\":\"Changed\",\"max\":500,\"dryRun\":true,"
			+ "\"inputs\":{\"tables\":\"SALES.ORDERS\",\"loadId\":42,\"full\":true},\"async\":false}");
		final ObjectNode sync = ContractService.syncArguments(args);
		eq("42", sync.path("key").asText(), "recordKey becomes connectors_sync's key (the record)");
		eq("Changed", sync.path("scope").asText(), "a known scope, whatever its case, is passed as given");
		eq("tables=SALES.ORDERS;loadId=42;full=true", sync.path("inputs").asText(), "inputs become the name=value;... text connectors_sync reads");
		yes(sync.path("max").asInt() == 500 && sync.path("dryRun").asBoolean() && sync.has("async") && !sync.has("mapping") && !sync.has("resumeFrom"),
			"flags passed through, nothing invented: " + sync);
		InJvmContractTest.syncRefused("{\"scope\":\"everything\"}", "scope must be one of");
		InJvmContractTest.syncRefused("{\"inputs\":{\"sql\":\"a;b\"}}", "must not contain ';'");
		InJvmContractTest.syncRefused("{\"inputs\":{\"a=b\":\"c\"}}", "must not be blank nor contain");
		InJvmContractTest.syncRefused("{\"inputs\":{\"x\":{\"y\":1}}}", "must be a string, a number or a boolean");
		yes(!ContractService.syncArguments((ObjectNode) Json.parse("{\"inputs\":{}}")).has("inputs"), "no input, no inputs argument");
		// wave 13: an outbound send on a scope is started with async the same way, and followed with run.get / stopped with run.stop
		final ObjectNode send = ContractService.syncArguments((ObjectNode) Json.parse("{\"mappingId\":417,\"scope\":\"all\",\"max\":100000,\"async\":true}"));
		yes(send.path("async").asBoolean() && "all".equals(send.path("scope").asText()) && send.path("max").asInt() == 100000, "flows.run passes async with a scope: " + send);
		final ObjectNode run = ContractService.runArguments((ObjectNode) Json.parse("{\"runId\":\"  R20261012-101500-0001 \",\"callsMax\":0}"));
		eq("R20261012-101500-0001", run.path("runId").asText(), "run.get / run.stop: the run id is trimmed");
		yes(run.path("callsMax").asInt() == 0 && !run.has("callsFrom"), "run.get: the log page is passed as given, nothing invented: " + run);
	}

	/**
	 * A caller written for 1.0.0 keeps working: every operation of 1.0 is still there with the same right and at least the
	 * same parameters (none of them newly required), the bounds it relied on are unchanged.
	 */
	private static void compatibleWith10() throws Exception
	{
		final String[][] v10 = { { "contract.describe", "" }, { "llm.connectors", "READ" }, { "llm.ask", "RUN", "connector", "system", "prompt*", "maxTokens", "json", "timeoutMs" },
			{ "flows.get", "READ", "key*" }, { "flows.put", "MANAGE", "key*", "flow*", "dryRun" }, { "flows.delete", "MANAGE", "key*" },
			{ "flows.run", "RUN", "key", "mappingId", "recordKey", "predicate", "scope", "inputs", "max", "dryRun", "resumeFrom", "async", "fullRefresh" } };
		for (final String[] operation : v10)
		{
			final ContractCatalog.Operation now = ContractCatalog.operation(operation[0]);
			yes(now != null && "1.0.0".equals(now.since), "1.0 operation still offered: " + operation[0]);
			eq(operation[1].isEmpty() ? null : ConnectorsAccess.Right.valueOf(operation[1]), now.right, operation[0] + ": same right as in 1.0");
			for (int i = 2; i < operation.length; i++)
			{
				final String name = operation[i].replace("*", "");
				final ContractCatalog.Param param = now.param(name);
				yes(param != null && param.required == operation[i].endsWith("*"), operation[0] + "." + name + ": still there, required as in 1.0");
			}
		}
		final ContractCatalog.Param maxTokens = ContractCatalog.operation(ContractCatalog.LLM_ASK).param("maxTokens");
		yes(maxTokens.min.intValue() == 1 && maxTokens.max.intValue() == 65536, "llm.ask maxTokens still accepts 1..65536 (a larger ask is capped, not refused)");
		final ObjectNode v10Call = ContractCatalog.validate(ContractCatalog.operation(ContractCatalog.LLM_ASK),
			Json.parse("{\"prompt\":\"Describe\",\"system\":\"S\",\"maxTokens\":65536,\"json\":true,\"timeoutMs\":180000}"));
		yes(v10Call.size() == 5, "the call of CONTRACT.md 1.0's caller code is still valid");
	}

	private static void syncRefused(final String pArgs, final String pExpected) throws Exception
	{
		try
		{
			ContractService.syncArguments((ObjectNode) Json.parse(pArgs));
			yes(false, pArgs + " must be refused");
		}
		catch (final ContractException ex)
		{
			yes(ex.getType() == ContractError.INVALID_ARGUMENT && ex.getMessage().contains(pExpected), pArgs + ": " + ex.getMessage());
		}
	}

	/** A session that only answers what the access check asks. */
	private static Session session(final boolean pSystem, final Profile... pRoles)
	{
		final Set<Profile> roles = new HashSet<Profile>(Arrays.asList(pRoles));
		return (Session) Proxy.newProxyInstance(InJvmContractTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArguments)
			{
				if ("isSystemUser".equals(pMethod.getName()))
				{
					return Boolean.valueOf(pSystem);
				}
				if ("isUserInRole".equals(pMethod.getName()))
				{
					return Boolean.valueOf(roles.contains(pArguments[0]));
				}
				throw new UnsupportedOperationException(pMethod.getName());
			}
		});
	}

	private static void eq(final Object pExpected, final Object pActual, final String pWhat)
	{
		yes(pExpected == null ? pActual == null : pExpected.equals(pActual), pWhat + ": expected " + pExpected + ", got " + pActual);
	}

	private static void yes(final boolean pCondition, final String pWhat)
	{
		InJvmContractTest.checks++;
		if (!pCondition)
		{
			throw new AssertionError("FAIL: " + pWhat);
		}
	}
}
