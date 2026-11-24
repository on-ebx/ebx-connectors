# The ebx-connectors contract for sibling modules

`ebx-connectors.sibling` **1.2.0** (1.0.0 in wave 11; 1.1.0 in wave 12 adds `llm.chat` and caps every answer size at the
connection's own `maxTokens`; 1.2.0 in wave 13 adds `run.get` and `run.stop`, to follow and stop a run that `flows.run` started with `async`) — what another module of the same ON EBX JVM (the MDM Factory, the data catalog) calls
instead of reading the Connectors tables by path, decrypting a connector's key or writing mapping rows as data
(audit F-26 of the Factory, D-18 of the catalog). The reference (every parameter, every answer field, the internals) is
[REFERENCE.md §4.5.4](REFERENCE.md#454-in-jvm-contract-for-sibling-modules-incontract); the operator's side is in
[OPERATIONS.md](OPERATIONS.md) (properties `ebx.connectors.contract`, `ebx.connectors.llm.default`, the setting "Default
language-model connector").

## What it offers

| Operation | Since | Connectors right | What it does |
|---|---|---|---|
| `contract.describe` | 1.0.0 | none (no session needed) | name, version, operations with their parameters, bounds and `since`, error types |
| `llm.connectors` | 1.0.0 | READ | the `llm` connectors (id, label, provider, model, **maxTokens**, **chat**, active, secretSet, usable) and the default one with its source (`setting`, `property`, `only-usable`, `none`). `maxTokens` is the most a call to that connection gets; `chat` says whether `llm.chat` serves its provider |
| `llm.ask` | 1.0.0 | **RUN** | one prompt to an `llm` connector through its own client and stored key: `{text, model, provider, connector, usage{inputTokens, outputTokens, totalTokens}, truncated, maxTokensApplied, capped, durationMs}`. Parameters: `connector` (default: see below), `system`, `prompt`*, `maxTokens` (1..65536, **at most the connection's own `maxTokens`**: see below), `json` (a JSON answer is expected), `timeoutMs` (1000..600000, default the connector's read timeout) |
| `llm.chat` | **1.1.0** | **RUN** | one turn of a conversation with tools, through the same connector, client and key: `{message{role: assistant, content, toolCalls[{id, name, arguments}]}, stopReason (END, TOOL_USE, MAX_TOKENS, OTHER), usage, model, provider, connector, maxTokensApplied, capped, durationMs}`. Parameters: `connector`, `system`, `messages`* (`{role: user, content}`, `{role: assistant, content?, toolCalls?}`, `{role: tool, toolCallId, content, isError?}`), `tools` (`[{name, description, inputSchema}]`), `maxTokens` (capped as `llm.ask`), `json`, `timeoutMs`. Anthropic and OpenAI-shaped connections (OpenAI, Azure OpenAI, Azure AI Foundry, Mistral, Ollama); Google answers `NOT_SUPPORTED` |
| `flows.get` | 1.0.0 | READ | the flow of a key, in the document `flows.put` accepts (`found: false` when none) |
| `flows.put` | 1.0.0 | MANAGE | creates or updates the flow of a key from a typed document; answers `mappingId`, `created` |
| `flows.delete` | 1.0.0 | MANAGE | deletes the flow of a key with everything attached; `deleted: false` when there was none |
| `flows.run` | 1.0.0 | RUN | runs a flow (`key` or `mappingId`), exactly as `connectors_sync`, and answers its answer. With `async: true` (a feed with no `recordKey`, `predicate` or `scope`; since 1.2.0 also an OUTBOUND flow with a `scope` and no `recordKey`) it answers `{runId, status: "RUNNING", async: true, …}` at once, without `count` |
| `run.get` | **1.2.0** | READ | the state of a run: `{runId, direction (INBOUND / OUTBOUND), mapping, execution{status, nextIndex, startedAt, updatedAt, created, updated, noChange, errorCount, skipped, rejected, conflicts, truncated, available?, firstErrors?, message?}, calls[], …}`. `status` is RUNNING, then DONE, PARTIAL, ERROR, STOPPED or INTERRUPTED (a restart caught it). Parameters: `runId`*, `callsMax` (0..500), `callsFrom` |
| `run.stop` | **1.2.0** | RUN | asks a running feed import or asynchronous send to stop at its next batch boundary: `{runId, requested: true, note}`. Best effort and in the JVM: poll `run.get` for `STOPPED` |

- **Rights** are those of Connectors (`ConnectorsAccess`: the roles `ConnectorsViewer` / `ConnectorsOperator` /
  `ConnectorsDesigner`, the table "Access rights", the administrator; the system user passes). A person who may run
  connectors (RUN) may ask a model: the call is limited to the `llm` connectors and to a single chat turn, unlike
  `connectors_call`, which runs any operation of any connection and stays MANAGE. **Secrets never leave Connectors**:
  no answer carries a key, a password or a URL with credentials.
- **The flow key** is chosen by the caller and is the root mapping's label (as the Connectors screens show it). Prefix it
  with your module (`Catalog scan: SNOW_DEMO`). Two root mappings with the same label → `AMBIGUOUS_KEY`, never a guess.
  A flow put again with the same key updates the same mapping, even after its connector, operation or table changed.
  Fields omitted are kept; `rules` and `inputs`, when given, replace the stored ones; children are matched by label and
  never deleted. The document is checked here (known fields only, each of its type, `direction`, `policy`), then by the
  same code as `connectors_mapping_upsert`, which writes it and declares its lineage.
- **The default `llm` connector** (when `connector` is omitted), read at each call: the setting **"Default language-model
  connector"** of the Connectors dataset (group "Settings", changed by an EBX administrator), else
  `-Debx.connectors.llm.default=<id>` (kept for operators), else the only usable one (active, configured, its key set),
  else `NO_DEFAULT_LLM`. A setting or property naming no `llm` connector is refused, never skipped for the next one.
- **The answer size is the connection's to decide** (1.1.0): a call never gets more than the `maxTokens` property of the
  connection (1024 unless set; `llm.connectors` gives it). Ask more and you get the connection's value, with
  `maxTokensApplied` and `capped: true` in the answer; ask nothing and you get the connection's value. When a feature
  needs longer answers, the administrator raises `maxTokens` on the connection, not the caller.
- **`llm.chat` is one HTTP round trip to the model per call.** The contract is stateless: send the whole conversation
  each time. When the answer's `stopReason` is `TOOL_USE`, run the tools of `message.toolCalls` yourself (with your own
  allow-list and checks), append the assistant message as answered and one `{role: tool, toolCallId, content, isError?}`
  per call, and call again. ebx-connectors never runs a tool. The conversation is checked item by item (first message a
  user's, last a user's or a tool's, every tool call answered once, right after its assistant message; `INVALID_ARGUMENT`
  names the item, e.g. `messages[3].toolCallId`). Up to 500 messages, 128 tools, 1 000 000 characters per content and
  4 000 000 in all.
- **Errors** are JSON-RPC errors: test `error.data.type` (`INVALID_ARGUMENT`, `SESSION_REQUIRED`, `DISABLED`,
  `ACCESS_DENIED`, `NOT_FOUND`, `AMBIGUOUS_KEY`, `CONNECTOR_UNUSABLE`, `NO_DEFAULT_LLM`, `NOT_LLM`, `LLM_FAILED`,
  `TIMEOUT`, `NOT_SUPPORTED` (1.1.0), `OPERATION_FAILED`, `INTERNAL`, `UNKNOWN_OPERATION`, `INVALID_REQUEST`); an unknown
  type counts as `OPERATION_FAILED`. `error.message` is English text for a log.
- **Audit**: each `llm.ask` leaves `contract=llm.ask connector=<id>`, each `llm.chat` `contract=llm.chat connector=<id>
  messages=<n> tools=<n> stop=<reason>`, with `capped=<n>` when the size was capped: never a prompt, a message, a tool
  name or an argument.
- **Following a send** (1.2.0): start with `flows.run {mappingId, scope: "all", max: <n>, async: true}`; the answer has `runId`
  and `status: "RUNNING"` and no `count` (a Connectors that ignores `async`, or an older contract, answers the finished
  synchronous shape instead, and a refused `async` is an `OPERATION_FAILED` whose message begins `async only applies`: ask again
  without `async`). Then poll `run.get {runId, callsMax: 0}` until `execution.status` is not RUNNING. The rows the send reached are
  `execution.created + updated + noChange` (a sent record counts as `updated`, one unchanged since its last send as `noChange`),
  `errorCount` the failures (`firstErrors` the first ones), `truncated` that `max` cut the send. `run.stop {runId}` ends it
  `STOPPED` at the next batch boundary (at most 250 records later); the records already sent keep their outbound state, so a later
  `changed` send does not resend them. Reference: [REFERENCE.md §2.14.6a](REFERENCE.md#2146a-asynchronous-outbound-scope-send-wave-13).
- **Not offered in 1.2**: streaming, a tool run by ebx-connectors, Google tool use, listing the flows of a module.

## Reserved names in a document a mapping reads (1.4.2)

A document that a mapping reads at its response root, from any connector, is read by the mapping engine as a **confidence envelope** when it carries a numeric `_minConfidence`: the names `_minConfidence`,
`_confidence` and `_sources` (and the `_evidence`, `_proposed` the documents connector adds) are then reserved. A rule whose source has no confidence, or a lower one than `_minConfidence`, writes nothing and logs a proposal
(an OPEN conflict) instead; a confident rule writes and keeps the text of `_sources` in `FieldProvenance.source`. The envelope is **a capability of the `documents` preview function, not of the contract**: it is read only while
`-Debx.connectors.preview` names `documents` (closed by default). A document that carries these names while it is closed is treated as before November (every rule writes, no proposal, no source). A third-party API or an extension
connector that wants confidence-gated writes must therefore not rely on it before the function is open; one that merely happens to return a `_minConfidence` field is unaffected by default and, once `documents` is open, must rename it. The envelope is
bounded: the comment of a proposal is cut at 500 characters, a source at 2,000. Detail: [DOCUMENTS.md](DOCUMENTS.md), "The confidence envelope".

## Transport

The platform MBean `com.on.ebx.connectors:type=Contract`, registered when ebx-connectors starts and removed when it
stops. JDK types only, so no class of ebx-connectors is shared:

- attributes `ContractName` and `ContractVersion`;
- operation `describe()` → JSON text;
- operation `handle(Object session, String message)`: `message` is one JSON-RPC 2.0 request
  (`{"jsonrpc":"2.0","id":1,"method":"llm.ask","params":{...}}`), `session` the caller's
  `com.orchestranetworks.service.Session` (the person in a user service, the workflow's or the module's system session in
  a background task); `null` only for `contract.describe`.

It is not reachable over HTTP nor by a remote JMX client (a `Session` cannot be sent). `-Debx.connectors.contract=false`
switches it off (`DISABLED`).

**Versioning.** `MAJOR.MINOR.PATCH`: a new operation, optional parameter, answer field or error type raises MINOR; a
removed or renamed one, a parameter becoming required or a wider right raises MAJOR. Check at startup: same MAJOR, and
MINOR.PATCH at least the one you were written for.

## Caller code (copy into your module)

```java
import java.lang.management.ManagementFactory;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestranetworks.service.Session;

/** The ebx-connectors contract, as seen from this module. */
final class ConnectorsContractClient
{
	static final String MBEAN = "com.on.ebx.connectors:type=Contract";
	static final String WRITTEN_FOR = "1.2.0"; // "1.1.0" if you do not call run.get / run.stop, "1.0.0" if you do not call llm.chat either
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** A refusal of the contract: {@code type} is error.data.type (ACCESS_DENIED, TIMEOUT...). */
	static final class Refusal extends Exception
	{
		final String type;

		Refusal(final String pType, final String pMessage)
		{
			super(pMessage);
			this.type = pType;
		}
	}

	/** Null when ebx-connectors offers a compatible contract; else why not (call it at startup, log it, turn the feature off). */
	static String incompatibility()
	{
		try
		{
			final String version = String.valueOf(ManagementFactory.getPlatformMBeanServer().getAttribute(new ObjectName(MBEAN), "ContractVersion"));
			final String[] offered = version.split("\\.");
			final String[] needed = WRITTEN_FOR.split("\\.");
			final boolean ok = offered.length == 3 && offered[0].equals(needed[0])
				&& (Integer.parseInt(offered[1]) > Integer.parseInt(needed[1])
					|| Integer.parseInt(offered[1]) == Integer.parseInt(needed[1]) && Integer.parseInt(offered[2]) >= Integer.parseInt(needed[2]));
			return ok ? null : "ebx-connectors offers contract " + version + ", this module needs " + WRITTEN_FOR;
		}
		catch (final InstanceNotFoundException ex)
		{
			return "ebx-connectors is not started (no " + MBEAN + ")";
		}
		catch (final Exception ex)
		{
			return "ebx-connectors contract unreadable: " + ex;
		}
	}

	/** One operation; its result, or a Refusal. */
	static JsonNode call(final Session pSession, final String pOperation, final ObjectNode pParams) throws Refusal
	{
		final ObjectNode request = MAPPER.createObjectNode();
		request.put("jsonrpc", "2.0");
		request.put("id", 1);
		request.put("method", pOperation);
		request.set("params", pParams == null ? MAPPER.createObjectNode() : pParams);
		final JsonNode answer;
		try
		{
			final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			final Object text = server.invoke(new ObjectName(MBEAN), "handle", new Object[] { pSession, request.toString() },
				new String[] { "java.lang.Object", "java.lang.String" });
			answer = MAPPER.readTree(String.valueOf(text));
		}
		catch (final InstanceNotFoundException ex)
		{
			throw new Refusal("ABSENT", "ebx-connectors is not started");
		}
		catch (final Exception ex)
		{
			throw new Refusal("INTERNAL", "ebx-connectors contract call failed: " + ex);
		}
		if (answer.has("error"))
		{
			throw new Refusal(answer.path("error").path("data").path("type").asText("OPERATION_FAILED"), answer.path("error").path("message").asText());
		}
		return answer.path("result");
	}

	/** Example: ask the model, JSON expected, 4096 tokens, 3 minutes at most. */
	static String ask(final Session pSession, final String pConnector, final String pSystem, final String pPrompt) throws Refusal
	{
		final ObjectNode params = MAPPER.createObjectNode();
		if (pConnector != null)
		{
			params.put("connector", pConnector);
		}
		params.put("system", pSystem);
		params.put("prompt", pPrompt);
		params.put("maxTokens", 4096);
		params.put("json", true);
		params.put("timeoutMs", 180000);
		final JsonNode result = call(pSession, "llm.ask", params);
		if (result.path("truncated").asBoolean(false))
		{
			throw new Refusal("TRUNCATED", "the model stopped at maxTokens: ask less per call or raise maxTokens");
		}
		return result.path("text").asText();
	}
}
```

Declaring and running a flow:

```java
final ObjectNode flow = MAPPER.createObjectNode();
flow.put("connector", "SNOW");
flow.put("operation", "profile");
flow.put("direction", "INBOUND");
flow.put("dataspace", "DataCatalog");
flow.put("dataset", "DataCatalog");
flow.put("tablePath", "/root/ProfileSnapshot");
flow.put("itemsPath", "columns");
flow.put("keyPredicate", "./columnKey='${columnKey}' and date-equal(./profileDate,'${profileDate}')");
flow.putObject("inputs").put("source", "SNOW_DEMO").put("tables", "${param.tables|default(*)}");
final ObjectNode rule = flow.putArray("rules").addObject();
rule.put("sourcePath", "rowCount");
rule.put("targetPath", "./rowCount");
rule.put("policy", "ALWAYS");
final ObjectNode put = MAPPER.createObjectNode();
put.put("key", "Catalog profile: SNOW_DEMO");
put.set("flow", flow);
final int mappingId = ConnectorsContractClient.call(session, "flows.put", put).path("mappingId").asInt();

final ObjectNode run = MAPPER.createObjectNode();
run.put("key", "Catalog profile: SNOW_DEMO");
run.putObject("inputs").put("tables", "SALES.ORDERS");
final JsonNode outcome = ConnectorsContractClient.call(session, "flows.run", run); // connectors_sync's answer: runId, status, counters...
```

**The dataspace.** A mapping may leave the dataspace empty: it then applies to a record wherever the dataset exists, workflow child dataspaces included. A TABLE-scoped run (feed import, scope send, catalog scan, enrich of a whole table) needs exactly one dataspace serving the dataset; copies in child dataspaces of that dataspace do not count; several distinct dataspaces → the run fails with `Dataset 'X' … is served by N dataspaces (…): name the dataspace on the mapping`, and the upsert refuses it. `flows.put` therefore checks it
(the whole dataspace tree is walked): a flow that names none stays generic, and `notes` says which dataspace table-scoped runs
will use when exactly one serves the dataset; several distinct dataspaces refuse the put with `OPERATION_FAILED` and a message that
names them (`Dataset 'Master' (table /root/Personne) is served by 2 dataspaces (Personnes_Master, Vol100k_Master): name the
dataspace on the mapping`), a dry run included; none serving it stores the flow as given, with a note. A `dataspace` that does not
exist is refused (`Dataspace 'X' does not exist`); one that exists without the dataset yet is stored with a note. A put that omits
`dataspace` on an existing flow keeps the stored one. Name the dataspace whenever the flow is table-scoped, as the example does. The
contract version does not change (the answer only gains `dataspace`, when one was given).

Sending a scope in the background and following it (1.2.0):

```java
final ObjectNode send = MAPPER.createObjectNode();
send.put("mappingId", 417);
send.put("scope", "all");
send.put("max", 200000);
send.put("async", true);
final JsonNode started = ConnectorsContractClient.call(session, "flows.run", send); // {runId, status: "RUNNING", async: true, ...}, no "count"
final String runId = started.path("runId").asText();
while (true)
{
	final ObjectNode poll = MAPPER.createObjectNode();
	poll.put("runId", runId);
	poll.put("callsMax", 0); // the state only, not the log lines
	final JsonNode execution = ConnectorsContractClient.call(session, "run.get", poll).path("execution");
	if (!"RUNNING".equals(execution.path("status").asText()))
	{
		// DONE | PARTIAL (check truncated and errorCount) | ERROR | STOPPED | INTERRUPTED
		final long sent = execution.path("created").asLong() + execution.path("updated").asLong() + execution.path("noChange").asLong();
		break;
	}
	// ... to give up: ConnectorsContractClient.call(session, "run.stop", poll-with-only-runId), then keep polling until STOPPED
	Thread.sleep(2000);
}
```

To change one field of an existing flow: `flows.get`, change `flow`, `flows.put` it back with the same key (the
document `flows.get` answers is the one `flows.put` accepts).

## A conversation with tools (`llm.chat`, 1.1.0)

**With the agent library** (`on-ebx-agent.jar`, built by `ebx-connectors/build-agent.sh`, the jar the MDM Factory embeds):
its `AgentLoop` takes any `LlmChat`, and `ContractLlmChat` is the one that goes through this contract. The loop sends,
runs the allowed tools of its `ToolSet` in your module, feeds the results back and stops at its budgets; the key never
reaches your module.

```java
import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.ContractLlmChat;
import com.on.ebx.agent.ToolSet;

final String why = ContractLlmChat.incompatibility(); // null when ebx-connectors offers llm.chat (1.1.0 or a later 1.x)
if (why != null)
{
	throw new IllegalStateException(why); // or fall back to what you did before
}
// connector null = the default one; 4096 asked (the connection's maxTokens applies if lower); 3 minutes per turn
final ContractLlmChat model = new ContractLlmChat(session, connectorIdOrNull, Integer.valueOf(4096), Integer.valueOf(180000));
final AgentLoop.Result result = new AgentLoop(model, tools, new AgentLoop.Budget(12, 200000, 600000L), listener)
	.run(systemPrompt, userTask);
if (result.status == AgentLoop.Status.FAILED && "TIMEOUT".equals(model.lastRefusal()))
{
	// the model was too slow: error.data.type of the contract's refusal
}
// model.lastMaxTokensApplied(), model.lastCapped(), model.lastResult() (model, provider, connector, usage...)
```

**By hand**, with the `call` method above (one turn; you run the tools and call again):

```java
final ObjectNode params = MAPPER.createObjectNode();
params.put("system", "Answer with the country of the code, use the tool.");
final ArrayNode messages = params.putArray("messages");
messages.addObject().put("role", "user").put("content", "Which country is FR?");
final ObjectNode tool = params.putArray("tools").addObject();
tool.put("name", "lookup_country");
tool.put("description", "The country of an ISO 3166 alpha-2 code");
tool.set("inputSchema", MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}},\"required\":[\"code\"]}"));
params.put("maxTokens", 2048);
params.put("timeoutMs", 120000);

JsonNode turn = ConnectorsContractClient.call(session, "llm.chat", params);
while ("TOOL_USE".equals(turn.path("stopReason").asText()))
{
	final ObjectNode assistant = messages.addObject();
	assistant.put("role", "assistant");
	assistant.put("content", turn.path("message").path("content").asText());
	assistant.set("toolCalls", turn.path("message").path("toolCalls"));
	for (final JsonNode call : turn.path("message").path("toolCalls"))
	{
		final ObjectNode result = messages.addObject();
		result.put("role", "tool");
		result.put("toolCallId", call.path("id").asText());
		if ("lookup_country".equals(call.path("name").asText())) // your allow-list: never run a name you did not offer
		{
			result.put("content", lookupCountry(call.path("arguments").path("code").asText()));
		}
		else
		{
			result.put("content", "unknown tool");
			result.put("isError", true);
		}
	}
	turn = ConnectorsContractClient.call(session, "llm.chat", params); // bound the number of turns in real code
}
final String answer = turn.path("message").path("content").asText(); // stopReason END (or MAX_TOKENS: the answer was cut)
```
