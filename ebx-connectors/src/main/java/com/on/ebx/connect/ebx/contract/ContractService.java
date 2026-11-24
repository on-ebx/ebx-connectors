package com.on.ebx.connect.ebx.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.connect.connectors.LlmConnector;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.Redaction;
import com.on.ebx.connect.ebx.ConfigTransfer;
import com.on.ebx.connect.ebx.ConnectorsAccess;
import com.on.ebx.connect.ebx.ConnectorsAudit;
import com.on.ebx.connect.ebx.ConnectorsInstaller;
import com.on.ebx.connect.ebx.mcp.ConnectorsTools;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Session;

/**
 * Runs the operations of the in-JVM contract ({@link ContractCatalog}) with the caller's EBX session: the right of
 * each operation is checked first ({@link ConnectorsAccess}, the same rights table as every other entry point), then
 * the work is done by the code the module already has, never by a second copy of it:
 * <ul>
 * <li>{@code llm.ask}: the cached {@link LlmConnector} of {@link ConnectorRegistry} (the instance {@code connectors_call
 * 'complete'} uses: inactive connector, clear secret, refused host and invalid configuration refused the same way), its
 * key decrypted in this module only; the answer size capped at the connector's own {@code maxTokens};</li>
 * <li>{@code llm.chat} (1.1.0): the same connector, one tool-use turn ({@link LlmConnector#chatTurn}, the agent library's
 * provider adapters sent by the connector's own client); the tools are run by the caller, never here;</li>
 * <li>{@code flows.*}: the MCP tools {@code connectors_mapping_upsert}, {@code connectors_mapping_delete} and
 * {@code connectors_sync} through {@link ConnectorsTools#invoke} (same validation, same audit line, same answers), and
 * the configuration export for {@code flows.get}.</li>
 * </ul>
 */
public final class ContractService
{
	/**
	 * JVM property naming the language-model connector {@code llm.ask} and {@code llm.chat} use when the caller names none
	 * and the setting "Default language-model connector" of the Connectors dataset is empty.
	 */
	public static final String DEFAULT_LLM_PROPERTY = "ebx.connectors.llm.default";

	private final Session session;
	private final Repository repository;
	private final Locale locale;

	public ContractService(final Session pSession, final Repository pRepository, final Locale pLocale)
	{
		this.session = pSession;
		this.repository = pRepository;
		this.locale = pLocale == null ? Locale.ENGLISH : pLocale;
	}

	/** One operation; its answer, or a typed refusal. */
	public ObjectNode call(final String pOperation, final JsonNode pParams) throws ContractException
	{
		final ContractCatalog.Operation operation = ContractCatalog.operation(pOperation);
		if (operation == null)
		{
			throw new ContractException(ContractError.UNKNOWN_OPERATION, "Unknown operation '" + pOperation + "' in contract " + ContractCatalog.NAME + " "
				+ ContractCatalog.VERSION + " (contract.describe lists them)");
		}
		final ObjectNode args = ContractCatalog.validate(operation, pParams);
		if (operation.right == null)
		{
			return ContractCatalog.describe();
		}
		if (this.session == null)
		{
			throw new ContractException(ContractError.SESSION_REQUIRED, operation.name + " runs as its caller: pass an EBX Session");
		}
		if (!ContractCatalog.allowed(operation, ConnectorsAccess.granted(this.session)))
		{
			ConnectorService.log().warn("[connectors] access denied: user " + ConnectorsAccess.userOf(this.session) + ", contract operation " + operation.name
				+ " (needs " + operation.right + ")");
			if (ConnectorsAudit.traced(operation.right))
			{
				ConnectorsAudit.contract(this.session, operation.name, "", ConnectorsAudit.REFUSED, 0L);
			}
			throw new ContractException(ContractError.ACCESS_DENIED, ConnectorsAccess.refusal("the contract operation '" + operation.name + "'", operation.right));
		}
		ConnectorsInstaller.ensure(this.repository, this.session);
		if (ContractCatalog.LLM_CONNECTORS.equals(operation.name))
		{
			return this.llmConnectors();
		}
		if (ContractCatalog.LLM_ASK.equals(operation.name))
		{
			return this.ask(args);
		}
		if (ContractCatalog.LLM_CHAT.equals(operation.name))
		{
			return this.chat(args);
		}
		if (ContractCatalog.FLOWS_GET.equals(operation.name))
		{
			return this.get(args);
		}
		if (ContractCatalog.FLOWS_PUT.equals(operation.name))
		{
			return this.put(args);
		}
		if (ContractCatalog.FLOWS_DELETE.equals(operation.name))
		{
			return this.delete(args);
		}
		if (ContractCatalog.FLOWS_RUN.equals(operation.name))
		{
			return this.run(args);
		}
		if (ContractCatalog.RUN_GET.equals(operation.name))
		{
			return this.tool(ConnectorsTools.T_RUN, ContractService.runArguments(args));
		}
		if (ContractCatalog.RUN_STOP.equals(operation.name))
		{
			return this.tool(ConnectorsTools.T_STOP, ContractService.runArguments(args));
		}
		throw new ContractException(ContractError.INTERNAL, "operation '" + operation.name + "' is described but not served");
	}

	private ConnectorRepository repo()
	{
		return ConnectorService.get().repository(this.repository);
	}

	// ------------------------------------------------------------------ language model

	/** The default language-model connector, how it was chosen, and why there is none. */
	public static final class DefaultLlm
	{
		public final String id;
		/** setting, property, only-usable or none */
		public final String source;
		public final String problem;

		DefaultLlm(final String pId, final String pSource, final String pProblem)
		{
			this.id = pId;
			this.source = pSource;
			this.problem = pProblem;
		}
	}

	/** Pure: true for a configuration of type {@code llm}. */
	public static boolean isLlm(final ConnectorConfig pConfig)
	{
		return pConfig != null && "llm".equalsIgnoreCase(pConfig.getType() == null ? "" : pConfig.getType().trim());
	}

	/** Pure: whether the secret the connector's authentication needs is set (OAuth 2.0: not judged here, the call says). */
	public static boolean secretSet(final ConnectorConfig pConfig)
	{
		final ConnectorConfig.AuthType auth = pConfig.getAuthType();
		if (auth == null || auth == ConnectorConfig.AuthType.NONE || auth == ConnectorConfig.AuthType.OAUTH2)
		{
			return true;
		}
		return auth == ConnectorConfig.AuthType.BASIC ? !pConfig.isPlaceholder(pConfig.getPassword()) : !pConfig.isPlaceholder(pConfig.getApiKey());
	}

	/** Pure: active, without a configuration problem, its secret set. */
	public static boolean usable(final ConnectorConfig pConfig)
	{
		return pConfig.isActive() && pConfig.getProblem() == null && ContractService.secretSet(pConfig);
	}

	/** Same as {@link #defaultLlm(List, String, String)} without a dataset setting. */
	public static DefaultLlm defaultLlm(final List<ConnectorConfig> pConnectors, final String pProperty)
	{
		return ContractService.defaultLlm(pConnectors, null, pProperty);
	}

	/**
	 * Pure: the connector {@code llm.ask} and {@code llm.chat} use when given none, in this order: the setting "Default
	 * language-model connector" of the Connectors dataset ({@code pSetting}) when set; else the JVM property
	 * {@value #DEFAULT_LLM_PROPERTY} when set; else the only usable llm connector, when exactly one is; else none, with
	 * the reason. A setting or property that names no llm connector is refused, never followed nor skipped. Never a
	 * guess among several.
	 */
	public static DefaultLlm defaultLlm(final List<ConnectorConfig> pConnectors, final String pSetting, final String pProperty)
	{
		final String set = pSetting == null ? "" : pSetting.trim();
		if (!set.isEmpty())
		{
			return ContractService.named(pConnectors, set, "setting", "the setting 'Default language-model connector' of the Connectors dataset ("
				+ set + ")");
		}
		final String named = pProperty == null ? "" : pProperty.trim();
		if (!named.isEmpty())
		{
			return ContractService.named(pConnectors, named, "property", "-D" + ContractService.DEFAULT_LLM_PROPERTY + "=" + named);
		}
		final List<String> usable = new ArrayList<String>();
		for (final ConnectorConfig config : pConnectors)
		{
			if (ContractService.isLlm(config) && ContractService.usable(config))
			{
				usable.add(config.getId());
			}
		}
		if (usable.size() == 1)
		{
			return new DefaultLlm(usable.get(0), "only-usable", null);
		}
		return new DefaultLlm(null, "none", (usable.isEmpty() ? "no llm connector is usable (active, configured, its key set)"
			: usable.size() + " llm connectors are usable " + usable) + ": name one, or choose the default in the setting 'Default language-model "
			+ "connector' of the Connectors dataset (or -D" + ContractService.DEFAULT_LLM_PROPERTY + "=<connector id>)");
	}

	private static DefaultLlm named(final List<ConnectorConfig> pConnectors, final String pId, final String pSource, final String pWhat)
	{
		for (final ConnectorConfig config : pConnectors)
		{
			if (ContractService.isLlm(config) && pId.equals(config.getId()))
			{
				return new DefaultLlm(pId, pSource, null);
			}
		}
		return new DefaultLlm(null, "none", pWhat + " names no llm connector of this instance");
	}

	/**
	 * The setting "Default language-model connector" of the Connectors dataset, read at each call (null when empty, when
	 * the dataset does not exist, or when the loaded model predates the setting).
	 */
	private String defaultSetting()
	{
		try
		{
			final com.onwbp.adaptation.Adaptation dataset = this.repo().dataset();
			if (dataset == null || dataset.getSchemaNode().getNode(com.on.ebx.connect.core.ConnectorsPaths.SETTINGS_DEFAULT_LLM) == null)
			{
				return null;
			}
			final Object value = dataset.get(com.on.ebx.connect.core.ConnectorsPaths.SETTINGS_DEFAULT_LLM);
			return value == null || String.valueOf(value).trim().isEmpty() ? null : String.valueOf(value).trim();
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private DefaultLlm defaultLlm(final List<ConnectorConfig> pLlms)
	{
		return ContractService.defaultLlm(pLlms, this.defaultSetting(), System.getProperty(ContractService.DEFAULT_LLM_PROPERTY));
	}

	private List<ConnectorConfig> llmConfigs()
	{
		final List<ConnectorConfig> out = new ArrayList<ConnectorConfig>();
		for (final ConnectorConfig config : this.repo().connectors())
		{
			if (ContractService.isLlm(config))
			{
				out.add(config);
			}
		}
		return out;
	}

	private ObjectNode llmConnectors()
	{
		final List<ConnectorConfig> llms = this.llmConfigs();
		final ObjectNode out = Json.object();
		final ArrayNode list = out.putArray("connectors");
		for (final ConnectorConfig config : llms)
		{
			final ObjectNode c = list.addObject();
			final String provider = LlmConnector.providerOf(config.getProperty("provider", "auto"), config.getBaseUrl());
			c.put("id", config.getId());
			c.put("label", config.getLabel());
			c.put("provider", provider);
			c.put("model", config.getProperty("model", LlmConnector.defaultModel(provider)).trim());
			c.put("maxTokens", LlmConnector.configuredMaxTokens(config));
			c.put("chat", LlmConnector.chatFormat(provider) != null);
			c.put("active", config.isActive());
			c.put("secretSet", ContractService.secretSet(config));
			c.put("usable", ContractService.usable(config));
			if (config.getProblem() != null)
			{
				c.put("problem", Redaction.redact(config.getProblem()));
			}
		}
		final DefaultLlm choice = this.defaultLlm(llms);
		if (choice.id == null)
		{
			out.putNull("default");
		}
		else
		{
			out.put("default", choice.id);
		}
		out.put("defaultSource", choice.source);
		if (choice.problem != null)
		{
			out.put("defaultProblem", choice.problem);
		}
		return out;
	}

	/** What {@code maxTokens} a call gets: what it asks, at most the connector's own {@code maxTokens}, the connector's when it asks nothing. */
	public static final class MaxTokens
	{
		public final int applied;
		/** True when the call asked more than the connector allows. */
		public final boolean capped;

		MaxTokens(final int pApplied, final boolean pCapped)
		{
			this.applied = pApplied;
			this.capped = pCapped;
		}
	}

	/** Pure: the answer size a call gets ({@code pAsked} null: none asked) from a connector allowing {@code pOwn}. */
	public static MaxTokens maxTokens(final Integer pAsked, final int pOwn)
	{
		if (pAsked == null)
		{
			return new MaxTokens(pOwn, false);
		}
		return pAsked.intValue() > pOwn ? new MaxTokens(pOwn, true) : new MaxTokens(pAsked.intValue(), false);
	}

	private static Integer asked(final ObjectNode pArgs)
	{
		return pArgs.has("maxTokens") ? Integer.valueOf(pArgs.get("maxTokens").asInt()) : null;
	}

	/** A language-model connector resolved for a call: its configuration and its cached instance. */
	private static final class ResolvedLlm
	{
		final ConnectorConfig config;
		final LlmConnector connector;

		ResolvedLlm(final ConnectorConfig pConfig, final LlmConnector pConnector)
		{
			this.config = pConfig;
			this.connector = pConnector;
		}
	}

	/** The connector named, refused when it is not a usable llm connector. */
	private ResolvedLlm llmConnector(final String pConnectorId) throws ContractException
	{
		final ConnectorConfig config = this.repo().connector(pConnectorId);
		if (config == null)
		{
			throw new ContractException(ContractError.NOT_FOUND, "no connector '" + pConnectorId + "'");
		}
		if (!ContractService.isLlm(config))
		{
			throw new ContractException(ContractError.NOT_LLM, "connector '" + pConnectorId + "' is of type " + config.getType() + ", not llm");
		}
		final Connector connector;
		try
		{
			connector = ConnectorRegistry.get().connector(pConnectorId, this.repo());
		}
		catch (final ConnectorException ex)
		{
			throw new ContractException(ContractError.CONNECTOR_UNUSABLE, Redaction.redact(String.valueOf(ex.getMessage())));
		}
		if (!(connector instanceof LlmConnector))
		{
			throw new ContractException(ContractError.NOT_LLM, "connector '" + pConnectorId + "' is not served by the llm connector type");
		}
		final String secret = ((LlmConnector) connector).secretProblem();
		if (secret != null)
		{
			throw new ContractException(ContractError.CONNECTOR_UNUSABLE, Redaction.redact(secret));
		}
		return new ResolvedLlm(config, (LlmConnector) connector);
	}

	/** The connector the call names, else the default one, else NO_DEFAULT_LLM. */
	private String connectorOrDefault(final ObjectNode pArgs) throws ContractException
	{
		final String connectorId = ContractService.text(pArgs, "connector");
		if (connectorId != null)
		{
			return connectorId;
		}
		final DefaultLlm choice = this.defaultLlm(this.llmConfigs());
		if (choice.id == null)
		{
			throw new ContractException(ContractError.NO_DEFAULT_LLM, "no connector given and no default language-model connector: " + choice.problem);
		}
		return choice.id;
	}

	private ObjectNode ask(final ObjectNode pArgs) throws ContractException
	{
		final long started = System.nanoTime();
		final StringBuilder subject = new StringBuilder();
		String outcome = ConnectorsAudit.ERROR;
		try
		{
			final String connectorId = this.connectorOrDefault(pArgs);
			ConnectorsAudit.append(subject, "connector", connectorId);
			if (pArgs.path("json").asBoolean(false))
			{
				ConnectorsAudit.append(subject, "json", "true");
			}
			final ResolvedLlm llm = this.llmConnector(connectorId);
			final ObjectNode out = ContractService.askWith(llm.connector, llm.config, connectorId, pArgs, started);
			if (out.path("capped").asBoolean(false))
			{
				ConnectorsAudit.append(subject, "capped", String.valueOf(out.path("maxTokensApplied").asInt()));
			}
			outcome = ConnectorsAudit.OK;
			return out;
		}
		finally
		{
			ConnectorsAudit.contract(this.session, ContractCatalog.LLM_ASK, subject.toString(), outcome, (System.nanoTime() - started) / 1000000L);
		}
	}

	/**
	 * {@code llm.ask} on a resolved connector: one call, the answer size capped at the connector's own {@code maxTokens}
	 * (since 1.1.0). No repository: tested offline against a fake provider.
	 */
	public static ObjectNode askWith(final LlmConnector pLlm, final ConnectorConfig pConfig, final String pConnectorId, final ObjectNode pArgs, final long pStarted)
		throws ContractException
	{
		final MaxTokens size = ContractService.maxTokens(ContractService.asked(pArgs), LlmConnector.configuredMaxTokens(pConfig));
		final LlmConnector.Answer answer;
		try
		{
			answer = pLlm.ask(ContractService.text(pArgs, "system"), ContractService.text(pArgs, "prompt"), Integer.valueOf(size.applied),
				pArgs.path("json").asBoolean(false), pArgs.has("timeoutMs") ? pArgs.get("timeoutMs").asInt() : 0);
		}
		catch (final ConnectorException ex)
		{
			throw new ContractException(ContractService.timedOut(ex) ? ContractError.TIMEOUT : ContractError.LLM_FAILED, Redaction.redact(String.valueOf(ex.getMessage())));
		}
		final ObjectNode out = Json.object();
		out.put("text", answer.text);
		out.put("model", answer.model);
		out.put("provider", answer.provider);
		out.put("connector", pConnectorId);
		ContractService.usage(out, answer.inputTokens, answer.outputTokens, answer.totalTokens);
		out.put("truncated", answer.truncated);
		out.put("maxTokensApplied", size.applied);
		out.put("capped", size.capped);
		out.put("durationMs", (System.nanoTime() - pStarted) / 1000000L);
		return out;
	}

	private ObjectNode chat(final ObjectNode pArgs) throws ContractException
	{
		final long started = System.nanoTime();
		// the items are checked first: a malformed conversation is refused like a malformed parameter, before any lookup
		final ChatRequest request = ChatRequest.parse(pArgs);
		final StringBuilder subject = new StringBuilder();
		String outcome = ConnectorsAudit.ERROR;
		try
		{
			final String connectorId = this.connectorOrDefault(pArgs);
			// never the content of a message or of a tool: only how much there was
			ConnectorsAudit.append(subject, "connector", connectorId);
			ConnectorsAudit.append(subject, "messages", String.valueOf(request.messageCount));
			ConnectorsAudit.append(subject, "tools", String.valueOf(request.tools.size()));
			if (pArgs.path("json").asBoolean(false))
			{
				ConnectorsAudit.append(subject, "json", "true");
			}
			final ResolvedLlm llm = this.llmConnector(connectorId);
			final ObjectNode out = ContractService.chatWith(llm.connector, llm.config, connectorId, pArgs, request, started);
			ConnectorsAudit.append(subject, "stop", out.path("stopReason").asText());
			if (out.path("capped").asBoolean(false))
			{
				ConnectorsAudit.append(subject, "capped", String.valueOf(out.path("maxTokensApplied").asInt()));
			}
			outcome = ConnectorsAudit.OK;
			return out;
		}
		finally
		{
			ConnectorsAudit.contract(this.session, ContractCatalog.LLM_CHAT, subject.toString(), outcome, (System.nanoTime() - started) / 1000000L);
		}
	}

	/**
	 * {@code llm.chat} on a resolved connector: one tool-use turn, the answer size capped at the connector's own
	 * {@code maxTokens}. No repository: tested offline against a fake provider.
	 */
	public static ObjectNode chatWith(final LlmConnector pLlm, final ConnectorConfig pConfig, final String pConnectorId, final ObjectNode pArgs,
		final ChatRequest pRequest, final long pStarted) throws ContractException
	{
		final String provider = LlmConnector.providerOf(pConfig.getProperty("provider", "auto"), pConfig.getBaseUrl());
		if (LlmConnector.chatFormat(provider) == null)
		{
			throw new ContractException(ContractError.NOT_SUPPORTED, "llm.chat is not offered for the provider " + provider + " of connector '" + pConnectorId
				+ "': use an Anthropic or OpenAI-shaped connector (OpenAI, Azure OpenAI, Azure AI Foundry, Mistral, Ollama), or llm.ask");
		}
		final MaxTokens size = ContractService.maxTokens(ContractService.asked(pArgs), LlmConnector.configuredMaxTokens(pConfig));
		final LlmConnector.ChatAnswer answer;
		try
		{
			answer = pLlm.chatTurn(ContractService.text(pArgs, "system"), pRequest.tools, pRequest.conversation, size.applied, pArgs.path("json").asBoolean(false),
				pArgs.has("timeoutMs") ? pArgs.get("timeoutMs").asInt() : 0);
		}
		catch (final ConnectorException ex)
		{
			throw new ContractException(ContractService.timedOut(ex) ? ContractError.TIMEOUT : ContractError.LLM_FAILED, Redaction.redact(String.valueOf(ex.getMessage())));
		}
		final ObjectNode out = Json.object();
		final ObjectNode message = out.putObject("message");
		message.put("role", "assistant");
		message.put("content", answer.turn.text == null ? "" : answer.turn.text);
		final ArrayNode calls = message.putArray("toolCalls");
		for (final LlmClient.ToolCall call : answer.turn.toolCalls)
		{
			final ObjectNode c = calls.addObject();
			c.put("id", call.id);
			c.put("name", call.name);
			c.set("arguments", call.arguments);
		}
		out.put("stopReason", answer.turn.stopReason.name());
		ContractService.usage(out, answer.inputTokens, answer.outputTokens, answer.totalTokens);
		out.put("model", answer.model);
		out.put("provider", answer.provider);
		out.put("connector", pConnectorId);
		out.put("maxTokensApplied", size.applied);
		out.put("capped", size.capped);
		out.put("durationMs", (System.nanoTime() - pStarted) / 1000000L);
		return out;
	}

	private static void usage(final ObjectNode pOut, final Integer pInput, final Integer pOutput, final Integer pTotal)
	{
		final ObjectNode usage = pOut.putObject("usage");
		ContractService.putCount(usage, "inputTokens", pInput);
		ContractService.putCount(usage, "outputTokens", pOutput);
		ContractService.putCount(usage, "totalTokens", pTotal);
	}

	/** Pure: true when the failure is the model not answering in time (a read or connect timeout somewhere in the causes). */
	public static boolean timedOut(final Throwable pFailure)
	{
		for (Throwable cause = pFailure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause())
		{
			if (cause instanceof java.net.SocketTimeoutException || cause.getClass().getName().endsWith("HttpTimeoutException")
				|| cause.getMessage() != null && cause.getMessage().toLowerCase(Locale.ENGLISH).contains("timed out"))
			{
				return true;
			}
		}
		return false;
	}

	private static void putCount(final ObjectNode pTarget, final String pName, final Integer pValue)
	{
		if (pValue == null)
		{
			pTarget.putNull(pName);
		}
		else
		{
			pTarget.put(pName, pValue.intValue());
		}
	}

	// ------------------------------------------------------------------ flows

	private Integer resolve(final String pKey) throws ContractException
	{
		return FlowDocument.resolve(this.repo().mappings(), pKey);
	}

	private ObjectNode get(final ObjectNode pArgs) throws ContractException
	{
		final String key = FlowDocument.key(ContractService.text(pArgs, "key"));
		final Integer id = this.resolve(key);
		final ObjectNode exported = id == null ? null : ConfigTransfer.exportMapping(this.repository, String.valueOf(id));
		final ObjectNode out = Json.object();
		out.put("key", key);
		out.put("found", exported != null);
		if (exported != null)
		{
			out.put("mappingId", id.intValue());
			out.set("flow", FlowDocument.fromExport(exported));
		}
		return out;
	}

	private ObjectNode put(final ObjectNode pArgs) throws ContractException
	{
		final String key = FlowDocument.key(ContractService.text(pArgs, "key"));
		final Integer existing = this.resolve(key);
		final ObjectNode document = FlowDocument.toUpsert(key, pArgs.get("flow"), existing);
		final boolean dryRun = pArgs.path("dryRun").asBoolean(false);
		final ObjectNode toolArgs = Json.object();
		toolArgs.set("mapping", document);
		toolArgs.put("dryRun", dryRun);
		final ObjectNode report = this.tool(ConnectorsTools.T_UPSERT, toolArgs);
		final ObjectNode out = Json.object();
		out.put("key", key);
		final JsonNode written = report.get("mapping");
		if (written != null && !written.isNull() && written.asText().matches("[0-9]+"))
		{
			out.put("mappingId", Integer.parseInt(written.asText()));
		}
		else if (existing != null)
		{
			out.put("mappingId", existing.intValue());
		}
		else
		{
			out.putNull("mappingId");
		}
		out.put("created", existing == null);
		out.put("dryRun", dryRun);
		for (final String field : new String[] { "dataspace", "rulesWritten", "childrenWritten", "rulePolicies", "policyDefaulted", "notes", "lineage" })
		{
			if (report.has(field))
			{
				out.set(field, report.get(field));
			}
		}
		return out;
	}

	private ObjectNode delete(final ObjectNode pArgs) throws ContractException
	{
		final String key = FlowDocument.key(ContractService.text(pArgs, "key"));
		final Integer id = this.resolve(key);
		final ObjectNode out = Json.object();
		out.put("key", key);
		if (id == null)
		{
			out.put("deleted", false);
			return out;
		}
		final ObjectNode toolArgs = Json.object();
		toolArgs.put("mapping", String.valueOf(id));
		final ObjectNode report = this.tool(ConnectorsTools.T_DELETE, toolArgs);
		out.put("deleted", true);
		out.put("mappingId", id.intValue());
		if (report.has("deletedRows"))
		{
			out.set("deletedRows", report.get("deletedRows"));
		}
		return out;
	}

	private ObjectNode run(final ObjectNode pArgs) throws ContractException
	{
		final String keyText = ContractService.text(pArgs, "key");
		final boolean byId = pArgs.has("mappingId");
		if ((keyText == null) == !byId)
		{
			throw new ContractException(ContractError.INVALID_ARGUMENT, "flows.run: give key or mappingId, exactly one");
		}
		final ObjectNode toolArgs = ContractService.syncArguments(pArgs);
		String key = null;
		final int id;
		if (byId)
		{
			id = pArgs.get("mappingId").asInt();
			if (this.repo().mapping(id) == null)
			{
				throw new ContractException(ContractError.NOT_FOUND, "no mapping " + id);
			}
		}
		else
		{
			key = FlowDocument.key(keyText);
			final Integer found = this.resolve(key);
			if (found == null)
			{
				throw new ContractException(ContractError.NOT_FOUND, "no flow of key '" + key + "'");
			}
			id = found.intValue();
		}
		toolArgs.put("mapping", String.valueOf(id));
		final ObjectNode answer = this.tool(ConnectorsTools.T_SYNC, toolArgs);
		if (key != null)
		{
			answer.put("key", key);
		}
		answer.put("mappingId", id);
		return answer;
	}

	/** Pure: the arguments of connectors_run_get / connectors_run_stop for a {@code run.get} / {@code run.stop} call: the run id (trimmed) and the log page, as given. */
	public static ObjectNode runArguments(final ObjectNode pArgs)
	{
		final ObjectNode out = Json.object();
		out.put("runId", pArgs.path("runId").asText("").trim());
		for (final String name : new String[] { "callsMax", "callsFrom" })
		{
			if (pArgs.has(name))
			{
				out.set(name, pArgs.get(name));
			}
		}
		return out;
	}

	/** Pure: the arguments of connectors_sync for a {@code flows.run} call (without the mapping). */
	public static ObjectNode syncArguments(final ObjectNode pArgs) throws ContractException
	{
		final ObjectNode out = Json.object();
		if (pArgs.has("recordKey"))
		{
			out.set("key", pArgs.get("recordKey"));
		}
		if (pArgs.has("predicate"))
		{
			out.set("predicate", pArgs.get("predicate"));
		}
		if (pArgs.has("scope"))
		{
			final String scope = pArgs.get("scope").asText().trim();
			boolean known = false;
			for (final String one : ContractCatalog.SCOPES)
			{
				known |= one.equalsIgnoreCase(scope);
			}
			if (!known)
			{
				throw new ContractException(ContractError.INVALID_ARGUMENT, "flows.run: scope must be one of " + ContractCatalog.SCOPES);
			}
			out.put("scope", scope);
		}
		final String inputs = FlowDocument.runtimeInputs(pArgs.get("inputs"));
		if (inputs != null)
		{
			out.put("inputs", inputs);
		}
		for (final String name : new String[] { "max", "resumeFrom", "dryRun", "async", "fullRefresh" })
		{
			if (pArgs.has(name))
			{
				out.set(name, pArgs.get(name));
			}
		}
		return out;
	}

	/** One MCP tool of the module with the caller's session; an answer carrying {@code error} becomes a typed refusal. */
	private ObjectNode tool(final String pTool, final ObjectNode pArgs) throws ContractException
	{
		final ObjectNode answer = new ConnectorsTools(this.session, this.repository, this.locale).invoke(pTool, pArgs);
		if (answer == null)
		{
			throw new ContractException(ContractError.INTERNAL, pTool + " gave no answer");
		}
		if (answer.has("error"))
		{
			final String message = answer.get("error").asText();
			throw new ContractException(message.startsWith("Access denied") ? ContractError.ACCESS_DENIED : ContractError.OPERATION_FAILED, message);
		}
		return answer;
	}

	private static String text(final JsonNode pArgs, final String pName)
	{
		final JsonNode value = pArgs.get(pName);
		return value == null || value.isNull() ? null : value.asText();
	}
}
