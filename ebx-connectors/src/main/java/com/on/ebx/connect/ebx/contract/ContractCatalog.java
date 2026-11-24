package com.on.ebx.connect.ebx.contract;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.ebx.ConnectorsAccess;

/**
 * The versioned contract ebx-connectors offers the other modules of the same JVM (the MDM Factory, the data catalog):
 * its name, its semantic version, its operations with their parameters, the Connectors right each asks, and the
 * validation of the parameters. Pure: no EBX repository, so the description and every refusal of a malformed call are
 * tested offline ({@link InJvmContract} carries it, {@link ContractService} runs it).
 * <p>
 * Versioning: {@link #VERSION} is {@code MAJOR.MINOR.PATCH}. A new operation, a new optional parameter, a new field in an
 * answer or a new error type raise MINOR; a removed or renamed operation, parameter or answer field, a parameter becoming
 * required, a wider right asked, raise MAJOR. A caller checks at startup that the major is the one it was written for and
 * the minor at least the one it needs ({@link #compatible}).
 */
public final class ContractCatalog
{
	/** The name of the contract, answered by {@code contract.describe} and the MBean attribute {@code ContractName}. */
	public static final String NAME = "ebx-connectors.sibling";
	/** Its semantic version, answered by {@code contract.describe} and the MBean attribute {@code ContractVersion}. */
	public static final String VERSION = "1.2.0";

	public static final String DESCRIBE = "contract.describe";
	public static final String LLM_CONNECTORS = "llm.connectors";
	public static final String LLM_ASK = "llm.ask";
	/** Since 1.1.0. */
	public static final String LLM_CHAT = "llm.chat";
	public static final String FLOWS_GET = "flows.get";
	public static final String FLOWS_PUT = "flows.put";
	public static final String FLOWS_DELETE = "flows.delete";
	public static final String FLOWS_RUN = "flows.run";
	/** Since 1.2.0. */
	public static final String RUN_GET = "run.get";
	/** Since 1.2.0. */
	public static final String RUN_STOP = "run.stop";

	/** Bounds of the parameters (also in the description, so that a caller can read them). */
	public static final int KEY_MAX_LENGTH = 200;
	public static final int PROMPT_MAX_LENGTH = 1000000;
	public static final int SYSTEM_MAX_LENGTH = 200000;
	/** The largest maxTokens a call may name; what is applied is never more than the connector's own maxTokens (since 1.1.0). */
	public static final int MAX_TOKENS_MAX = 65536;
	/** llm.chat (1.1.0): messages and tools per call, characters of one message, of the whole conversation, of a tool description. */
	public static final int CHAT_MESSAGES_MAX = 500;
	public static final int CHAT_TOOLS_MAX = 128;
	public static final int CHAT_CONTENT_MAX_LENGTH = 1000000;
	public static final int CHAT_TOTAL_MAX_LENGTH = 4000000;
	public static final int TOOL_DESCRIPTION_MAX_LENGTH = 10000;
	public static final int TIMEOUT_MIN_MS = 1000;
	public static final int TIMEOUT_MAX_MS = 600000;

	/** The outbound scopes {@code flows.run} accepts, as {@code connectors_sync} does. */
	public static final List<String> SCOPES = Collections.unmodifiableList(Arrays.asList("all", "unsent", "changed", "failed", "dataspaceDelta"));

	/** A parameter of an operation. */
	public static final class Param
	{
		public final String name;
		/** string, integer, boolean, object or array (since 1.1.0; min/max then bound the number of items) */
		public final String type;
		public final boolean required;
		public final String description;
		/** integer bounds, string length bounds; null when none */
		public final Integer min;
		public final Integer max;

		Param(final String pName, final String pType, final boolean pRequired, final String pDescription, final Integer pMin, final Integer pMax)
		{
			this.name = pName;
			this.type = pType;
			this.required = pRequired;
			this.description = pDescription;
			this.min = pMin;
			this.max = pMax;
		}
	}

	/** An operation of the contract. */
	public static final class Operation
	{
		public final String name;
		/** the Connectors right the caller's session must hold; null for {@code contract.describe} (no session needed) */
		public final ConnectorsAccess.Right right;
		public final String description;
		public final String result;
		public final List<Param> params;
		/** The contract version that introduced the operation. */
		public final String since;

		Operation(final String pName, final ConnectorsAccess.Right pRight, final String pDescription, final String pResult, final Param... pParams)
		{
			this(pName, "1.0.0", pRight, pDescription, pResult, pParams);
		}

		Operation(final String pName, final String pSince, final ConnectorsAccess.Right pRight, final String pDescription, final String pResult,
			final Param... pParams)
		{
			this.name = pName;
			this.since = pSince;
			this.right = pRight;
			this.description = pDescription;
			this.result = pResult;
			this.params = Collections.unmodifiableList(Arrays.asList(pParams));
		}

		public Param param(final String pName)
		{
			for (final Param param : this.params)
			{
				if (param.name.equals(pName))
				{
					return param;
				}
			}
			return null;
		}
	}

	private static Param text(final String pName, final boolean pRequired, final int pMaxLength, final String pDescription)
	{
		return new Param(pName, "string", pRequired, pDescription, Integer.valueOf(1), Integer.valueOf(pMaxLength));
	}

	private static Param integer(final String pName, final int pMin, final int pMax, final String pDescription)
	{
		return new Param(pName, "integer", false, pDescription, Integer.valueOf(pMin), Integer.valueOf(pMax));
	}

	private static Param flag(final String pName, final String pDescription)
	{
		return new Param(pName, "boolean", false, pDescription, null, null);
	}

	private static Param object(final String pName, final boolean pRequired, final String pDescription)
	{
		return new Param(pName, "object", pRequired, pDescription, null, null);
	}

	private static Param array(final String pName, final boolean pRequired, final int pMinItems, final int pMaxItems, final String pDescription)
	{
		return new Param(pName, "array", pRequired, pDescription, Integer.valueOf(pMinItems), Integer.valueOf(pMaxItems));
	}

	private static final String MAX_TOKENS = "Answer size in tokens for this call, at most the connector's own 'maxTokens' (1024 unless set; llm.connectors "
		+ "gives it): a larger value gets the connector's, and the answer says so (maxTokensApplied, capped: true). The connector's when omitted";

	private static final String KEY = "The flow key chosen by the calling module, unique among the root mappings: it is the mapping's label "
		+ "(seen as such in the Connectors screens), e.g. 'Catalog scan: SNOW_DEMO'. Prefix it with your module's name.";

	public static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
		new Operation(ContractCatalog.DESCRIBE, null,
			"The name and version of this contract, its operations, their parameters and rights, and the error types. No session needed.",
			"{name, version, transport, operations[{name, right, description, params[{name, type, required, min, max, description}], result}], errors[{type, code}]}"),
		new Operation(ContractCatalog.LLM_CONNECTORS, ConnectorsAccess.Right.READ,
			"The language-model connectors (type llm) configured here, each with the answer size it allows (maxTokens) and whether llm.chat "
				+ "serves it, and the one llm.ask and llm.chat use when they are given none. No secret is answered.",
			"{connectors[{id, label, provider, model, maxTokens, chat, active, secretSet, usable, problem?}], default: id or null, "
				+ "defaultSource: setting|property|only-usable|none, defaultProblem?}"),
		new Operation(ContractCatalog.LLM_ASK, ConnectorsAccess.Right.RUN,
			"One prompt to a language-model connector, sent by the connector's own client with its stored key, outbound rules and provider settings "
				+ "(the call connectors_call 'complete' makes). Single turn, no tool use (see llm.chat). The key never leaves ebx-connectors.",
			"{text, model, provider, connector, usage{inputTokens, outputTokens, totalTokens} (null when the provider gave none), truncated, "
				+ "maxTokensApplied, capped, durationMs}",
			ContractCatalog.text("connector", false, ContractCatalog.KEY_MAX_LENGTH, "Id of an llm connector; the default one (see llm.connectors) when omitted"),
			ContractCatalog.text("system", false, ContractCatalog.SYSTEM_MAX_LENGTH, "System prompt; the connector's 'system' property when omitted"),
			ContractCatalog.text("prompt", true, ContractCatalog.PROMPT_MAX_LENGTH, "User prompt"),
			ContractCatalog.integer("maxTokens", 1, ContractCatalog.MAX_TOKENS_MAX, ContractCatalog.MAX_TOKENS),
			ContractCatalog.flag("json", "true when a JSON answer is expected: the connector's jsonMode rule then decides whether the API itself is asked for JSON "
				+ "(auto = Google, Mistral, Ollama). The text is answered as is: parse it yourself. Default false"),
			ContractCatalog.integer("timeoutMs", ContractCatalog.TIMEOUT_MIN_MS, ContractCatalog.TIMEOUT_MAX_MS,
				"Read timeout of the model call in milliseconds; the connector's readTimeoutMs when omitted")),
		new Operation(ContractCatalog.LLM_CHAT, "1.1.0", ConnectorsAccess.Right.RUN,
			"One turn of a conversation with tools, sent by the language-model connector's own client with its stored key, outbound rules and "
				+ "provider settings (Anthropic Messages, or OpenAI chat completions for OpenAI, Azure OpenAI, Azure AI Foundry, Mistral, Ollama; "
				+ "not Google: NOT_SUPPORTED). Stateless: the caller sends the whole conversation each time, runs the tools the model asks for "
				+ "itself and calls again with their results. ebx-connectors never runs a tool. One HTTP round trip to the model per call.",
			"{message{role: assistant, content, toolCalls[{id, name, arguments}]}, stopReason: END|TOOL_USE|MAX_TOKENS|OTHER, "
				+ "usage{inputTokens, outputTokens, totalTokens} (null when the provider gave none), model, provider, connector, maxTokensApplied, capped, durationMs}",
			ContractCatalog.text("connector", false, ContractCatalog.KEY_MAX_LENGTH, "Id of an llm connector; the default one (see llm.connectors) when omitted"),
			ContractCatalog.text("system", false, ContractCatalog.SYSTEM_MAX_LENGTH, "System prompt; the connector's 'system' property when omitted"),
			ContractCatalog.array("messages", true, 1, ContractCatalog.CHAT_MESSAGES_MAX,
				"The conversation in order, first a user message, last a user or tool message. Each item: {role: user, content} | {role: assistant, "
					+ "content?, toolCalls?[{id, name, arguments: object}]} | {role: tool, toolCallId, content, isError?: boolean}. The tool messages "
					+ "right after an assistant message answer each of its toolCalls once, by id, before any other message. At most "
					+ ContractCatalog.CHAT_CONTENT_MAX_LENGTH + " characters per content, " + ContractCatalog.CHAT_TOTAL_MAX_LENGTH + " in all"),
			ContractCatalog.array("tools", false, 0, ContractCatalog.CHAT_TOOLS_MAX,
				"The tools the model may ask for: [{name (letters, digits, _ and -, 64 at most, unique), description, inputSchema: a JSON Schema "
					+ "object of type object}]. None: the model can only answer text"),
			ContractCatalog.integer("maxTokens", 1, ContractCatalog.MAX_TOKENS_MAX, ContractCatalog.MAX_TOKENS),
			ContractCatalog.flag("json", "true when a JSON answer is expected: the connector's jsonMode rule then decides whether the API itself is asked for JSON "
				+ "(OpenAI-shaped providers; auto = Mistral, Ollama). Default false"),
			ContractCatalog.integer("timeoutMs", ContractCatalog.TIMEOUT_MIN_MS, ContractCatalog.TIMEOUT_MAX_MS,
				"Read timeout of the model call in milliseconds; the connector's readTimeoutMs when omitted")),
		new Operation(ContractCatalog.FLOWS_GET, ConnectorsAccess.Right.READ,
			"The flow (root mapping) of that key, in the document flows.put accepts, so that a caller can read, change and put it back.",
			"{found: false, key} or {found: true, key, mappingId, flow{connector, operation, direction, dataspace, dataset, tablePath, label, ..., inputs{}, rules[], children[]}}",
			ContractCatalog.text("key", true, ContractCatalog.KEY_MAX_LENGTH, ContractCatalog.KEY)),
		new Operation(ContractCatalog.FLOWS_PUT, ConnectorsAccess.Right.MANAGE,
			"Creates the flow of that key, or updates it (whatever its connector, operation or table now), from a typed document validated "
				+ "here and then by the mapping upsert (connectors_mapping_upsert). Fields given are written, fields omitted are kept; rules and "
				+ "inputs, when given, replace the stored ones; children are matched by label and never deleted. Idempotent. "
				+ "A flow may leave the dataspace empty (it then applies to a record wherever the dataset exists, workflow child dataspaces included); "
				+ "a table-scoped run needs exactly one dataspace serving the dataset, so the put is refused (dry run included) when several distinct "
				+ "dataspaces serve it, or when the dataspace named does not exist: name the dataspace.",
			"{key, mappingId (null on a dry run of a new flow), created, dryRun, dataspace? (when the flow named one), rulesWritten, childrenWritten, rulePolicies{}, policyDefaulted, notes[], lineage{}}",
			ContractCatalog.text("key", true, ContractCatalog.KEY_MAX_LENGTH, ContractCatalog.KEY),
			ContractCatalog.object("flow", true, "The flow document: connector, operation, direction (INBOUND|OUTBOUND), dataset, tablePath, optional dataspace (advised for a table-scoped flow), "
				+ "description, active, responseRoot, the feed settings (keyPredicate, itemsPath, createIfMissing, or feed{}), the incremental settings, "
				+ "inputs{name: expression}, rules[{sourcePath, targetPath, transformation, policy IF_EMPTY|ALWAYS|TRUSTED, active, comment, position}], "
				+ "children[{label, tablePath, collectionPath, parentLinkField, childKeyFields, childFilter, deleteMissing, inputs, rules, children}]. "
				+ "label, when given, must equal the key; id is refused"),
			ContractCatalog.flag("dryRun", "true: validated by the upsert and rolled back, nothing written (default false)")),
		new Operation(ContractCatalog.FLOWS_DELETE, ConnectorsAccess.Right.MANAGE,
			"Deletes the flow of that key with its rules, inputs, sub-object mappings and outbound states (connectors_mapping_delete). "
				+ "Idempotent: a key that designates nothing answers deleted=false.",
			"{key, deleted, mappingId?, deletedRows?}",
			ContractCatalog.text("key", true, ContractCatalog.KEY_MAX_LENGTH, ContractCatalog.KEY)),
		new Operation(ContractCatalog.FLOWS_RUN, ConnectorsAccess.Right.RUN,
			"Runs one flow, as connectors_sync does: a feed reads its items; with recordKey or predicate one record; an outbound flow with scope its records.",
			"the answer of connectors_sync (runId, status, count, ok, errors, counters, results[], ...) plus key and mappingId",
			ContractCatalog.text("key", false, ContractCatalog.KEY_MAX_LENGTH, ContractCatalog.KEY + " Give key or mappingId, not both"),
			ContractCatalog.integer("mappingId", 1, Integer.MAX_VALUE, "Id of the mapping to run, instead of key"),
			ContractCatalog.text("recordKey", false, 4000, "Primary key of one record"),
			ContractCatalog.text("predicate", false, 4000, "XPath predicate: one record, or narrows the table for a scope"),
			ContractCatalog.text("scope", false, 40, "Outbound scope: all, unsent, changed, failed, dataspaceDelta"),
			ContractCatalog.object("inputs", false, "Feed only: run-time inputs {name: value}, read by the mapping as ${param.name}; a value may not contain ';' or a line break"),
			ContractCatalog.integer("max", 1, Integer.MAX_VALUE, "Maximum records or items (connectors_sync's default when omitted); a cut run says so"),
			ContractCatalog.flag("dryRun", "true: build without sending, or list a feed's values without writing"),
			ContractCatalog.integer("resumeFrom", 0, Integer.MAX_VALUE, "Feed only: 0-based item index to restart a batched import from"),
			ContractCatalog.flag("async", "Real run only: start in the background and answer {runId, status: RUNNING, async: true} at once. A feed with no recordKey, "
				+ "predicate or scope, and (since 1.2.0) an outbound flow with a scope and no recordKey. Follow it with run.get, stop it with run.stop"),
			ContractCatalog.flag("fullRefresh", "Incremental feed only: ignore the watermark for this run")),
		new Operation(ContractCatalog.RUN_GET, "1.2.0", ConnectorsAccess.Right.READ,
			"The state of a run (connectors_run_get): the persisted execution{} of a feed import or of an outbound scope send started with async, "
				+ "plus one page of its calls from the synchronisation log. Poll it after flows.run with async until execution.status is terminal.",
			"{runId, direction? (INBOUND|OUTBOUND), mapping?, execution{status: RUNNING|DONE|PARTIAL|ERROR|STOPPED|INTERRUPTED, direction, mapping, nextIndex, startedAt, updatedAt, "
				+ "created, updated, noChange, errorCount, skipped, rejected, conflicts, truncated, available?, resultsOmitted?, firstErrors?, message?} (absent for a run "
				+ "without a persisted state), calls[], count, errors, callsMax, callsFrom, callsCut?, callsNextFrom?, replayable}",
			ContractCatalog.text("runId", true, ContractCatalog.KEY_MAX_LENGTH, "The run id answered by flows.run (or by the synchronous answer of a run)"),
			ContractCatalog.integer("callsMax", 0, 500, "Log rows to list, newest first (default 50, 0 = none and the log is not read)"),
			ContractCatalog.integer("callsFrom", 0, Integer.MAX_VALUE, "Log rows to skip, to read the next page (default 0)")),
		new Operation(ContractCatalog.RUN_STOP, "1.2.0", ConnectorsAccess.Right.RUN,
			"Asks a running feed import or asynchronous outbound send (connectors_run_stop) to stop at its next batch boundary: it then ends STOPPED with what it "
				+ "did so far in its counters, and the records an outbound send already sent keep their outbound state. In-JVM and best effort, like the tool: read "
				+ "run.get to see that it stopped.",
			"{runId, requested: true, note}",
			ContractCatalog.text("runId", true, ContractCatalog.KEY_MAX_LENGTH, "The run id to stop"))));

	private ContractCatalog()
	{
	}

	/** The operation of that name, null when this version has none. */
	public static Operation operation(final String pName)
	{
		for (final Operation operation : ContractCatalog.OPERATIONS)
		{
			if (operation.name.equals(pName))
			{
				return operation;
			}
		}
		return null;
	}

	/**
	 * Pure: true when a contract of version {@code pOffered} serves a caller written for {@code pNeeded}: same major, and
	 * a minor (then patch) at least the needed one. False for anything that is not {@code MAJOR.MINOR.PATCH}.
	 */
	public static boolean compatible(final String pOffered, final String pNeeded)
	{
		final int[] offered = ContractCatalog.parse(pOffered);
		final int[] needed = ContractCatalog.parse(pNeeded);
		if (offered == null || needed == null || offered[0] != needed[0])
		{
			return false;
		}
		return offered[1] > needed[1] || offered[1] == needed[1] && offered[2] >= needed[2];
	}

	private static int[] parse(final String pVersion)
	{
		if (pVersion == null || !pVersion.trim().matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))
		{
			return null;
		}
		final String[] parts = pVersion.trim().split("\\.");
		return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
	}

	/** The answer of {@code contract.describe}. */
	public static ObjectNode describe()
	{
		final ObjectNode out = Json.object();
		out.put("name", ContractCatalog.NAME);
		out.put("version", ContractCatalog.VERSION);
		out.put("transport", "JSON-RPC 2.0 through the platform MBean " + InJvmContract.OBJECT_NAME
			+ ", operation handle(Object session, String message); method = operation name, params = object");
		final ArrayNode operations = out.putArray("operations");
		for (final Operation operation : ContractCatalog.OPERATIONS)
		{
			final ObjectNode o = operations.addObject();
			o.put("name", operation.name);
			if (operation.right == null)
			{
				o.putNull("right");
			}
			else
			{
				o.put("right", operation.right.name());
			}
			o.put("description", operation.description);
			o.put("since", operation.since);
			final ArrayNode params = o.putArray("params");
			for (final Param param : operation.params)
			{
				final ObjectNode p = params.addObject();
				p.put("name", param.name);
				p.put("type", param.type);
				p.put("required", param.required);
				if (param.min != null)
				{
					p.put("min", param.min.intValue());
				}
				if (param.max != null)
				{
					p.put("max", param.max.intValue());
				}
				p.put("description", param.description);
			}
			o.put("result", operation.result);
		}
		final ArrayNode errors = out.putArray("errors");
		for (final ContractError error : ContractError.values())
		{
			final ObjectNode e = errors.addObject();
			e.put("type", error.name());
			e.put("code", error.code());
		}
		return out;
	}

	/**
	 * Pure: the parameters of a call checked against the operation: an unknown parameter, a missing required one, a value
	 * of the wrong type or out of its bounds is refused with {@link ContractError#INVALID_ARGUMENT} naming it. A null value
	 * counts as absent. Integers are accepted as JSON numbers only (no text), booleans as JSON booleans only: the contract
	 * is typed. An array is checked for its number of items here, its items by the operation ({@link ChatRequest}).
	 * Answers the parameters as an object (an empty one for none).
	 */
	public static ObjectNode validate(final Operation pOperation, final JsonNode pParams) throws ContractException
	{
		final ObjectNode out = Json.object();
		if (pParams != null && !pParams.isNull() && !pParams.isObject())
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, pOperation.name + ": params must be a JSON object");
		}
		if (pParams != null && pParams.isObject())
		{
			for (final Iterator<Map.Entry<String, JsonNode>> it = pParams.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> entry = it.next();
				final Param param = pOperation.param(entry.getKey());
				if (param == null)
				{
					throw new ContractException(ContractError.INVALID_ARGUMENT, pOperation.name + ": unknown parameter '" + entry.getKey() + "'");
				}
				if (entry.getValue() == null || entry.getValue().isNull())
				{
					continue;
				}
				ContractCatalog.check(pOperation, param, entry.getValue());
				out.set(param.name, entry.getValue());
			}
		}
		for (final Param param : pOperation.params)
		{
			if (param.required && !out.has(param.name))
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, pOperation.name + ": parameter '" + param.name + "' is required");
			}
		}
		return out;
	}

	private static void check(final Operation pOperation, final Param pParam, final JsonNode pValue) throws ContractException
	{
		final String where = pOperation.name + ": parameter '" + pParam.name + "'";
		if ("string".equals(pParam.type))
		{
			if (!pValue.isTextual())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must be a string");
			}
			final String text = pValue.asText();
			if (text.trim().isEmpty())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must not be blank");
			}
			if (pParam.max != null && text.length() > pParam.max.intValue())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " is longer than " + pParam.max + " characters");
			}
		}
		else if ("integer".equals(pParam.type))
		{
			if (!pValue.isIntegralNumber() || !pValue.canConvertToInt())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must be an integer");
			}
			final int value = pValue.asInt();
			if (pParam.min != null && value < pParam.min.intValue() || pParam.max != null && value > pParam.max.intValue())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must be between " + pParam.min + " and " + pParam.max);
			}
		}
		else if ("boolean".equals(pParam.type))
		{
			if (!pValue.isBoolean())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must be true or false");
			}
		}
		else if ("object".equals(pParam.type) && !pValue.isObject())
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must be a JSON object");
		}
		else if ("array".equals(pParam.type))
		{
			if (!pValue.isArray())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must be a JSON array");
			}
			if (pParam.min != null && pValue.size() < pParam.min.intValue() || pParam.max != null && pValue.size() > pParam.max.intValue())
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, where + " must have between " + pParam.min + " and " + pParam.max + " items");
			}
		}
	}

	/** Pure: whether a session holding {@code pGranted} (null: no right) may call the operation. */
	public static boolean allowed(final Operation pOperation, final ConnectorsAccess.Right pGranted)
	{
		return pOperation.right == null || pGranted != null && pGranted.covers(pOperation.right);
	}
}
