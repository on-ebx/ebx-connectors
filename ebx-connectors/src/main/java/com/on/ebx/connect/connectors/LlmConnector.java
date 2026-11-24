package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * Large language model as an enrichment source: Anthropic Claude, OpenAI, Google Gemini (Gemini API
 * or Vertex AI), Microsoft (Azure OpenAI, Azure AI Foundry models), Mistral AI, Ollama, or any
 * OpenAI-compatible endpoint. The model proposes, the trust level and the TRUSTED policy decide: give
 * it a low trust (30) so that it never overwrites an official source, and its proposals land in the
 * conflict manager for a human review.
 *
 * <p>Properties: {@code provider} (auto | anthropic | openai | google | azure | azure-ai | mistral |
 * ollama; auto = from the base URL), {@code model}, {@code maxTokens} (1024), {@code temperature} (0,
 * {@code none} = not sent), {@code system} (default instructions), {@code language} (answer language,
 * default the text's), {@code jsonMode} (auto | true | false: ask the API itself for a JSON answer on
 * extract / classify / match; auto = on for Google, Mistral and Ollama), {@code azure.apiVersion}
 * (2024-10-21 for Azure OpenAI, {@code v1} for its v1 API; 2024-05-01-preview for Foundry models),
 * {@code google.apiVersion} (v1beta), {@code google.project} and {@code google.location} (Vertex AI).
 *
 * <p>Authentication: Anthropic takes the key in {@code x-api-key}, Gemini in {@code x-goog-api-key}
 * (both translated from BEARER), Azure in {@code api-key} (API_KEY_HEADER) or an Entra token (BEARER /
 * OAUTH2), Vertex AI an OAuth token, OpenAI and Mistral a Bearer key, a local Ollama nothing (NONE).
 *
 * <p>Operations: {@code complete} (prompt, optional system) returns {@code text}; {@code extract}
 * (text, fields = "name:what it is, name2:...") returns one JSON field per name; {@code classify}
 * (text, categories = "A=description;B=description") returns category, confidence, reason;
 * {@code match} (a, b) returns same, confidence, reason.
 *
 * <p>{@code vision} ({@code false} by default) declares that the model accepts images: only then does
 * {@link #ask(String, String, List, Integer, boolean, int)} send an image or a PDF with the prompt (the documents connector, for an
 * image or a PDF without text layer). The module cannot tell it from the model's name.
 */
public final class LlmConnector extends AbstractRestConnector
{
	/** The answer size of a connector without a {@code maxTokens} property. */
	public static final int DEFAULT_MAX_TOKENS = 1024;

	/** Set once an OpenAI-shaped model refused tools with its default reasoning effort: {@link #chatTurn} then sends {@code none} at once. */
	private volatile boolean reasoningEffortNone;

	/** Read-only authentication probe; never generates tokens or sends catalog samples. */
	@Override
	public com.on.ebx.connect.spi.ConnectionTestResult testConnection(final ConnectorContext context) throws ConnectorException
	{
		if (!"openai".equals(this.provider())) return super.testConnection(context);
		this.requireSecret();
		final long start = System.nanoTime();
		final com.on.ebx.connect.core.HttpJsonClient.Response response = this.send("GET", this.path("/v1/models"),
			java.util.Collections.<String, String>emptyMap(), null);
		final long elapsed = (System.nanoTime() - start) / 1000000L;
		final int status = response.getStatus();
		if (status == 200 && response.getBody() != null && response.getBody().path("data").isArray())
			return com.on.ebx.connect.spi.ConnectionTestResult.success("Authenticated models endpoint reached. Model inference access and quota are not tested.", elapsed);
		final String detail = status == 401 ? "Authentication rejected; check the saved API key."
			: status == 403 ? "Models access denied; check key permissions. This does not prove the key is invalid."
			: status == 429 ? "Rate or quota limit reached."
			: "Unexpected endpoint response; check endpoint routing and connectivity.";
		return com.on.ebx.connect.spi.ConnectionTestResult.failure("Models API HTTP " + status + ". " + detail, elapsed);
	}

	static final List<String> PROVIDERS = Arrays.asList("auto", "anthropic", "openai", "google", "azure", "azure-ai", "mistral", "ollama");

	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("complete", "Free prompt (prompt, optional system) -> text", "prompt"),
		Operation.read("extract", "Structured extraction (text, fields = name:description, ...) -> one JSON field per name", "text", "fields"),
		Operation.read("classify", "Classification (text, categories = A=description;B=...) -> category, confidence, reason", "text", "categories"),
		Operation.read("match", "Entity matching (a, b: two descriptions) -> same, confidence, reason", "a", "b"));

	@Override
	public String getType()
	{
		return "llm";
	}

	@Override
	public String getLabel()
	{
		return "LLM (Claude, OpenAI, Gemini, Azure OpenAI, Mistral, Ollama)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "AI").description("Text completion, extraction, classification and entity matching with Anthropic, OpenAI, Google, Microsoft, Mistral AI or Ollama models.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER);
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BEARER_TOKEN);
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS);
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE);
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("provider",
			com.on.ebx.connect.spi.ConfigurationPropertyType.ENUMERATION).label("provider").description("LLM provider (auto = from the base URL)")
			.enumerationValues(LlmConnector.PROVIDERS.toArray(new String[0])).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("model",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("model").description("Provider model or deployment name").build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("anthropic.version",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("anthropic.version").description("Anthropic API version").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("azure.apiVersion",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("azure.apiVersion").description("Azure API version (v1 = Azure OpenAI v1 API)").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("google.apiVersion",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("google.apiVersion").description("Gemini API version").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("google.project",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("google.project").description("Google Cloud project (Vertex AI only)").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("google.location",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("google.location").description("Vertex AI location").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("jsonMode",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("jsonMode").description("Ask the API for a JSON answer (auto, true, false)").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("language",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("language").description("Requested answer language").build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("maxTokens",
			com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER).label("maxTokens").description("Maximum generated tokens").build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("openai.maxTokensParam",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("openai.maxTokensParam").description("OpenAI token-limit parameter name").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("openai.reasoningEffort",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("openai.reasoningEffort")
			.description("OpenAI reasoning_effort (none, low, medium, high; empty = not sent)").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("openai.path",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("openai.path").description("OpenAI-compatible completion path").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("system",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("system").description("Default system instruction").advanced(true).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("vision",
			com.on.ebx.connect.spi.ConfigurationPropertyType.BOOLEAN).label("vision")
			.description("The model accepts images (and PDFs on Anthropic, OpenAI, Google, Mistral): lets the documents connector send them").defaultValue(Boolean.FALSE).build());
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("temperature",
			com.on.ebx.connect.spi.ConfigurationPropertyType.STRING).label("temperature").description("Sampling temperature (none = not sent)").advanced(true).build());
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return LlmConnector.OPERATIONS;
	}

	private String provider()
	{
		return LlmConnector.providerOf(this.getConfig().getProperty("provider", "auto"), this.getConfig().getBaseUrl());
	}

	/** The declared provider, or the one the base URL designates. */
	public static String providerOf(final String pDeclared, final String pBaseUrl)
	{
		final String declared = pDeclared == null ? "auto" : pDeclared.trim().toLowerCase(Locale.ENGLISH);
		if (!declared.isEmpty() && !"auto".equals(declared))
		{
			return declared;
		}
		final String url = pBaseUrl == null ? "" : pBaseUrl.toLowerCase(Locale.ENGLISH);
		if (url.contains("anthropic.com"))
		{
			return "anthropic";
		}
		if (url.contains("generativelanguage.googleapis.com") || url.contains("aiplatform.googleapis.com"))
		{
			return "google";
		}
		if (url.contains("services.ai.azure.com") || url.contains("models.inference.ai.azure.com") || url.contains("models.ai.azure.com"))
		{
			return "azure-ai";
		}
		if (url.contains("openai.azure.com") || url.contains("cognitiveservices.azure.com"))
		{
			return "azure";
		}
		if (url.contains("mistral.ai"))
		{
			return "mistral";
		}
		if (url.contains(":11434") || url.contains("ollama"))
		{
			return "ollama";
		}
		return "openai";
	}

	/** Model used when the connector has no {@code model} property. */
	public static String defaultModel(final String pProvider)
	{
		if ("anthropic".equals(pProvider))
		{
			return "claude-sonnet-5";
		}
		if ("google".equals(pProvider))
		{
			return "gemini-2.5-flash";
		}
		if ("mistral".equals(pProvider))
		{
			return "mistral-small-latest";
		}
		if ("ollama".equals(pProvider))
		{
			return "llama3.2";
		}
		return "gpt-4o-mini";
	}

	@Override
	protected Map<String, String> headers()
	{
		final Map<String, String> headers = super.headers();
		final String provider = this.provider();
		final boolean bearerKey = this.getConfig().getAuthType() == ConnectorConfig.AuthType.BEARER && this.getConfig().getApiKey() != null;
		if ("anthropic".equals(provider))
		{
			headers.put("anthropic-version", this.getConfig().getProperty("anthropic.version", "2023-06-01"));
			if (bearerKey)
			{
				headers.remove("Authorization");
				headers.put("x-api-key", this.getConfig().getApiKey());
			}
		}
		else if ("google".equals(provider) && bearerKey && !this.vertex())
		{
			// the Gemini API takes an AI Studio key in its own header; Vertex AI keeps the OAuth Bearer token
			headers.remove("Authorization");
			headers.put("x-goog-api-key", this.getConfig().getApiKey());
		}
		return headers;
	}

	private boolean vertex()
	{
		final String url = this.getConfig().getBaseUrl();
		return url != null && url.toLowerCase(Locale.ENGLISH).contains("aiplatform.googleapis.com");
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		this.requireSecret();
		final String system;
		final String prompt;
		final boolean json;
		final String language = this.getConfig().getProperty("language", null);
		final String languageHint = language == null ? "" : " Answer in " + language + ".";
		if ("complete".equals(pOperation))
		{
			prompt = AbstractRestConnector.require(pInputs, "prompt");
			final String s = pInputs.get("system");
			system = s == null || s.trim().isEmpty() ? this.getConfig().getProperty("system", "You are a precise data assistant.") : s;
			json = false;
		}
		else if ("extract".equals(pOperation))
		{
			final String text = AbstractRestConnector.require(pInputs, "text");
			final String fields = AbstractRestConnector.require(pInputs, "fields");
			system = "You extract structured data. Answer with a single JSON object and nothing else.";
			prompt = "Extract the following fields from the text. Use exactly these keys, and null when the text does not say.\nFields: " + fields
					+ languageHint + "\n\nText:\n" + text;
			json = true;
		}
		else if ("classify".equals(pOperation))
		{
			final String text = AbstractRestConnector.require(pInputs, "text");
			final String categories = AbstractRestConnector.require(pInputs, "categories");
			system = "You classify texts. Answer with a single JSON object and nothing else.";
			prompt = "Classify the text into exactly one of these categories (code=description, separated by ;):\n" + categories
					+ "\nAnswer as JSON: {\"category\": <code>, \"confidence\": <0 to 1>, \"reason\": <one sentence>}." + languageHint + "\n\nText:\n" + text;
			json = true;
		}
		else if ("match".equals(pOperation))
		{
			final String a = AbstractRestConnector.require(pInputs, "a");
			final String b = AbstractRestConnector.require(pInputs, "b");
			system = "You decide whether two descriptions designate the same real-world entity. Answer with a single JSON object and nothing else.";
			prompt = "Do A and B describe the same entity (same company, person, product or place)?\nA: " + a + "\nB: " + b
					+ "\nAnswer as JSON: {\"same\": true or false, \"confidence\": <0 to 1>, \"reason\": <one sentence>}." + languageHint;
			json = true;
		}
		else
		{
			throw new ConnectorException(this.getConfig().getId() + ": unknown operation '" + pOperation + "'");
		}
		final String text = this.ask(system, prompt, json);
		if (!json)
		{
			final ObjectNode out = Json.object();
			out.put("text", text);
			return out;
		}
		final JsonNode parsed = LlmConnector.jsonOf(text);
		if (parsed == null || !parsed.isObject())
		{
			final ObjectNode out = Json.object();
			out.put("text", text);
			out.put("error", "the model did not answer with a JSON object");
			return out;
		}
		return parsed;
	}

	/** Whether the request asks the API itself for a JSON answer. */
	static boolean jsonMode(final String pSetting, final String pProvider, final boolean pJsonExpected)
	{
		if (!pJsonExpected)
		{
			return false;
		}
		final String setting = pSetting == null ? "auto" : pSetting.trim().toLowerCase(Locale.ENGLISH);
		if ("true".equals(setting) || "false".equals(setting))
		{
			return "true".equals(setting);
		}
		return "google".equals(pProvider) || "mistral".equals(pProvider) || "ollama".equals(pProvider);
	}

	/** One user turn, the text of the answer. */
	private String ask(final String pSystem, final String pPrompt, final boolean pJson) throws ConnectorException
	{
		return this.chat(pSystem, pPrompt, Collections.<Attachment>emptyList(), pJson, null, 0).text;
	}

	/**
	 * What one model call answered, for the in-JVM contract of the module ({@link #ask(String, String, Integer, boolean, int)}):
	 * the text, the model that answered, the token counts the provider reported (null when it reported none) and whether the
	 * provider says the answer stopped at the token limit.
	 */
	public static final class Answer
	{
		public final String text;
		public final String provider;
		public final String model;
		public final Integer inputTokens;
		public final Integer outputTokens;
		public final Integer totalTokens;
		public final boolean truncated;

		Answer(final String pText, final String pProvider, final String pModel, final Integer pInputTokens, final Integer pOutputTokens,
			final Integer pTotalTokens, final boolean pTruncated)
		{
			this.text = pText;
			this.provider = pProvider;
			this.model = pModel;
			this.inputTokens = pInputTokens;
			this.outputTokens = pOutputTokens;
			this.totalTokens = pTotalTokens != null ? pTotalTokens
				: pInputTokens != null && pOutputTokens != null ? Integer.valueOf(pInputTokens.intValue() + pOutputTokens.intValue()) : null;
			this.truncated = pTruncated;
		}
	}

	/**
	 * One free prompt, as the operation {@code complete} sends it, with what a sibling module of the same JVM may set for
	 * this call only: the answer size ({@code pMaxTokens}, the connector's {@code maxTokens} when null), whether a JSON
	 * answer is expected ({@code pJson}: the connector's {@code jsonMode} rule then decides whether the API itself is asked
	 * for JSON, as for extract / classify / match) and the read timeout ({@code pReadTimeoutMs}, the connector's when not
	 * positive). The connector's configuration and its cached client are not changed. The key never leaves this class.
	 */
	public Answer ask(final String pSystem, final String pPrompt, final Integer pMaxTokens, final boolean pJson, final int pReadTimeoutMs)
		throws ConnectorException
	{
		this.requireSecret();
		if (pPrompt == null || pPrompt.trim().isEmpty())
		{
			throw new ConnectorException(this.getConfig().getId() + ": the prompt is empty");
		}
		final String system = pSystem == null || pSystem.trim().isEmpty() ? this.getConfig().getProperty("system", "You are a precise data assistant.") : pSystem;
		return this.chat(system, pPrompt, Collections.<Attachment>emptyList(), pJson, pMaxTokens, pReadTimeoutMs);
	}

	/** An image or a PDF sent with the prompt, as the provider receives it (base64). */
	public static final class Attachment
	{
		public final String mediaType;
		public final byte[] data;
		public final String name;

		public Attachment(final String pMediaType, final byte[] pData, final String pName)
		{
			this.mediaType = pMediaType;
			this.data = pData;
			this.name = pName == null || pName.trim().isEmpty() ? "document" : pName;
		}

		boolean isPdf()
		{
			return "application/pdf".equals(this.mediaType);
		}
	}

	/** True when the connector declares {@code vision=true}: its model accepts images. */
	public boolean vision()
	{
		return "true".equalsIgnoreCase(this.getConfig().getProperty("vision", "false").trim());
	}

	/**
	 * True when this connector's provider reads a PDF sent in a chat message: Anthropic, OpenAI, Google, Mistral. Not Azure OpenAI
	 * (its vision page names no file part, read 2026-11-02), Azure AI Foundry or Ollama.
	 */
	public boolean readsPdf()
	{
		return LlmConnector.readsPdf(this.provider());
	}

	static boolean readsPdf(final String pProvider)
	{
		return "anthropic".equals(pProvider) || "openai".equals(pProvider) || "google".equals(pProvider) || "mistral".equals(pProvider);
	}

	/**
	 * {@link #ask(String, String, Integer, boolean, int)} with images or PDFs sent along the prompt, in the provider's format. Refused
	 * when the connector does not declare {@code vision=true}, and for a PDF when the provider cannot read one in a chat message.
	 */
	public Answer ask(final String pSystem, final String pPrompt, final List<Attachment> pAttachments, final Integer pMaxTokens, final boolean pJson,
		final int pReadTimeoutMs) throws ConnectorException
	{
		final List<Attachment> attachments = pAttachments == null ? Collections.<Attachment>emptyList() : pAttachments;
		if (attachments.isEmpty()) return this.ask(pSystem, pPrompt, pMaxTokens, pJson, pReadTimeoutMs);
		final String id = this.getConfig().getId();
		if (!this.vision())
			throw new ConnectorException(id + ": this LLM connector does not declare vision=true, so its model is not known to accept images: no image or PDF is sent");
		for (final Attachment attachment : attachments)
		{
			if (attachment.isPdf() && !this.readsPdf())
				throw new ConnectorException(id + ": provider " + this.provider() + " does not read a PDF in a chat message (Anthropic, OpenAI, Google and Mistral do)");
		}
		this.requireSecret();
		if (pPrompt == null || pPrompt.trim().isEmpty())
		{
			throw new ConnectorException(id + ": the prompt is empty");
		}
		final String system = pSystem == null || pSystem.trim().isEmpty() ? this.getConfig().getProperty("system", "You are a precise data assistant.") : pSystem;
		try
		{
			return this.chat(system, pPrompt, attachments, pJson, pMaxTokens, pReadTimeoutMs);
		}
		catch (final ConnectorException ex)
		{
			if (ex.getStatus() != 400) throw ex;
			throw new ConnectorException(ex.getMessage() + " - if the model does not accept images, choose a multimodal model or set vision=false", 400, ex);
		}
	}

	/**
	 * Pure: puts the attachments into the user message of a chat body built for {@code pProvider}, before the prompt text, in that
	 * provider's format. Read 2026-11-02 in each provider's documentation: Anthropic {@code image} / {@code document} blocks with a
	 * base64 source (platform.claude.com vision, pdf-support); OpenAI and Azure {@code image_url} with a data URL, OpenAI
	 * {@code file} with {@code filename, file_data} (developers.openai.com chat create, pdf-files); Gemini and Vertex AI
	 * {@code inlineData {mimeType, data}} parts (generate-content reference, camelCase); Mistral {@code image_url} and
	 * {@code document_url} as data-URL strings (docs.mistral.ai openapi.yaml, vision, document_qna); Ollama {@code images}, raw
	 * base64, on the message (docs.ollama.com api/chat).
	 */
	static void attach(final String pProvider, final ObjectNode pBody, final List<Attachment> pAttachments)
	{
		if ("google".equals(pProvider))
		{
			final ArrayNode parts = (ArrayNode) pBody.path("contents").path(0).path("parts");
			final JsonNode text = parts.remove(0);
			for (final Attachment attachment : pAttachments)
			{
				final ObjectNode inline = parts.addObject().putObject("inlineData");
				inline.put("mimeType", attachment.mediaType);
				inline.put("data", LlmConnector.base64(attachment));
			}
			parts.add(text);
			return;
		}
		final ArrayNode messages = (ArrayNode) pBody.path("messages");
		final ObjectNode user = (ObjectNode) messages.get(messages.size() - 1);
		final String prompt = user.path("content").asText();
		if ("ollama".equals(pProvider))
		{
			final ArrayNode images = user.putArray("images");
			for (final Attachment attachment : pAttachments) images.add(LlmConnector.base64(attachment));
			return;
		}
		final ArrayNode content = user.putArray("content");
		for (final Attachment attachment : pAttachments)
		{
			final ObjectNode part = content.addObject();
			if ("anthropic".equals(pProvider))
			{
				part.put("type", attachment.isPdf() ? "document" : "image");
				final ObjectNode source = part.putObject("source");
				source.put("type", "base64");
				source.put("media_type", attachment.mediaType);
				source.put("data", LlmConnector.base64(attachment));
			}
			else if (attachment.isPdf() && "mistral".equals(pProvider))
			{
				part.put("type", "document_url");
				part.put("document_url", LlmConnector.dataUrl(attachment));
				part.put("document_name", attachment.name);
			}
			else if (attachment.isPdf())
			{
				part.put("type", "file");
				final ObjectNode file = part.putObject("file");
				file.put("filename", attachment.name);
				file.put("file_data", LlmConnector.dataUrl(attachment));
			}
			else if ("mistral".equals(pProvider))
			{
				part.put("type", "image_url");
				part.put("image_url", LlmConnector.dataUrl(attachment));
			}
			else
			{
				part.put("type", "image_url");
				part.putObject("image_url").put("url", LlmConnector.dataUrl(attachment));
			}
		}
		content.addObject().put("type", "text").put("text", prompt);
	}

	private static String base64(final Attachment pAttachment)
	{
		return Base64.getEncoder().encodeToString(pAttachment.data);
	}

	private static String dataUrl(final Attachment pAttachment)
	{
		return "data:" + pAttachment.mediaType + ";base64," + LlmConnector.base64(pAttachment);
	}

	/** Why this connector cannot call its model for want of a secret (key, password, OAuth settings); null when it can. */
	public String secretProblem()
	{
		try
		{
			this.requireSecret();
			return null;
		}
		catch (final ConnectorException ex)
		{
			return ex.getMessage();
		}
	}

	/** Pure: the answer of a chat call in the provider's format, its text already read. */
	static Answer answerOf(final String pProvider, final String pConfiguredModel, final JsonNode pReply, final String pText)
	{
		final JsonNode reply = pReply == null ? Json.object() : pReply;
		String model;
		Integer in;
		Integer out;
		Integer total = null;
		final boolean truncated;
		if ("anthropic".equals(pProvider))
		{
			model = LlmConnector.textOf(reply.path("model"));
			in = LlmConnector.count(reply.path("usage").path("input_tokens"));
			out = LlmConnector.count(reply.path("usage").path("output_tokens"));
			truncated = "max_tokens".equals(reply.path("stop_reason").asText(""));
		}
		else if ("google".equals(pProvider))
		{
			model = LlmConnector.textOf(reply.path("modelVersion"));
			in = LlmConnector.count(reply.path("usageMetadata").path("promptTokenCount"));
			out = LlmConnector.count(reply.path("usageMetadata").path("candidatesTokenCount"));
			total = LlmConnector.count(reply.path("usageMetadata").path("totalTokenCount"));
			truncated = "MAX_TOKENS".equals(reply.path("candidates").path(0).path("finishReason").asText(""));
		}
		else if ("ollama".equals(pProvider))
		{
			model = LlmConnector.textOf(reply.path("model"));
			in = LlmConnector.count(reply.path("prompt_eval_count"));
			out = LlmConnector.count(reply.path("eval_count"));
			truncated = "length".equals(reply.path("done_reason").asText(""));
		}
		else
		{
			model = LlmConnector.textOf(reply.path("model"));
			in = LlmConnector.count(reply.path("usage").path("prompt_tokens"));
			out = LlmConnector.count(reply.path("usage").path("completion_tokens"));
			total = LlmConnector.count(reply.path("usage").path("total_tokens"));
			truncated = "length".equals(reply.path("choices").path(0).path("finish_reason").asText(""));
		}
		if (model == null)
		{
			model = pConfiguredModel;
		}
		return new Answer(pText, pProvider, model, in, out, total, truncated);
	}

	private static String textOf(final JsonNode pNode)
	{
		return pNode != null && pNode.isTextual() && !pNode.asText().trim().isEmpty() ? pNode.asText() : null;
	}

	private static Integer count(final JsonNode pNode)
	{
		return pNode != null && pNode.isNumber() ? Integer.valueOf(pNode.asInt()) : null;
	}

	/** One user turn: {@code pMaxTokens} null and {@code pReadTimeoutMs} 0 keep the connector's settings. */
	private Answer chat(final String pSystem, final String pPrompt, final List<Attachment> pAttachments, final boolean pJson, final Integer pMaxTokens,
		final int pReadTimeoutMs)
		throws ConnectorException
	{
		final ConnectorConfig config = this.getConfig();
		final String provider = this.provider();
		final String model = config.getProperty("model", LlmConnector.defaultModel(provider)).trim();
		final int maxTokens = pMaxTokens != null ? pMaxTokens.intValue() : LlmConnector.configuredMaxTokens(config);
		final String temperature = config.getProperty("temperature", "0").trim();
		final boolean withTemperature = !"none".equalsIgnoreCase(temperature) && !temperature.isEmpty();
		final boolean json = LlmConnector.jsonMode(config.getProperty("jsonMode", "auto"), provider, pJson);
		final ObjectNode body = Json.object();
		final String path;
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if ("anthropic".equals(provider))
		{
			body.put("model", model);
			body.put("max_tokens", maxTokens);
			if (withTemperature)
			{
				body.put("temperature", Double.parseDouble(temperature));
			}
			body.put("system", pSystem);
			final ObjectNode message = body.putArray("messages").addObject();
			message.put("role", "user");
			message.put("content", pPrompt);
			path = this.path("/v1/messages");
		}
		else if ("google".equals(provider))
		{
			body.putObject("systemInstruction").putArray("parts").addObject().put("text", pSystem);
			final ObjectNode content = body.putArray("contents").addObject();
			content.put("role", "user");
			content.putArray("parts").addObject().put("text", pPrompt);
			final ObjectNode generation = body.putObject("generationConfig");
			generation.put("maxOutputTokens", maxTokens);
			if (withTemperature)
			{
				generation.put("temperature", Double.parseDouble(temperature));
			}
			if (json)
			{
				generation.put("responseMimeType", "application/json");
			}
			final String name = AbstractRestConnector.segment(model.startsWith("models/") ? model.substring(7) : model);
			if (this.vertex())
			{
				final String project = config.getProperty("google.project", null);
				if (project == null || project.trim().isEmpty())
				{
					throw new ConnectorException(config.getId() + ": set the google.project property for Vertex AI");
				}
				path = "/v1/projects/" + AbstractRestConnector.segment(project.trim()) + "/locations/"
						+ AbstractRestConnector.segment(config.getProperty("google.location", "us-central1").trim()) + "/publishers/google/models/" + name + ":generateContent";
			}
			else
			{
				path = this.path("/" + config.getProperty("google.apiVersion", "v1beta").trim() + "/models/" + name + ":generateContent");
			}
		}
		else if ("ollama".equals(provider))
		{
			body.put("model", model);
			body.put("stream", false);
			LlmConnector.messages(body, pSystem, pPrompt);
			final ObjectNode options = body.putObject("options");
			options.put("num_predict", maxTokens);
			if (withTemperature)
			{
				options.put("temperature", Double.parseDouble(temperature));
			}
			if (json)
			{
				body.put("format", "json");
			}
			path = "/api/chat";
		}
		else
		{
			body.put("model", model);
			if (withTemperature)
			{
				body.put("temperature", Double.parseDouble(temperature));
			}
			body.put(config.getProperty("openai.maxTokensParam", "max_tokens"), maxTokens);
			final String reasoningEffort = config.getProperty("openai.reasoningEffort", "").trim();
			if (!reasoningEffort.isEmpty())
			{
				body.put("reasoning_effort", reasoningEffort);
			}
			LlmConnector.messages(body, pSystem, pPrompt);
			if (json)
			{
				body.putObject("response_format").put("type", "json_object");
			}
			path = this.openAiShapedPath(provider, model, query);
		}
		if (!pAttachments.isEmpty()) LlmConnector.attach(provider, body, pAttachments);
		final com.on.ebx.connect.core.HttpJsonClient.Response response = this.send("POST", path, query, body, null, pReadTimeoutMs);
		if (response.getStatus() == 404)
		{
			// bodyOrNull reads a 404 as "no data"; here it means an unknown model, deployment or path (Ollama: model not pulled)
			final String detail = response.getRawBody() == null ? "" : response.getRawBody();
			throw new ConnectorException(config.getId() + ": " + provider + " chat -> HTTP 404 (model '" + model + "', path " + path + ") "
					+ (detail.length() > 300 ? detail.substring(0, 300) + "..." : detail), 404, null);
		}
		final JsonNode reply = this.bodyOrNull(response, provider + " chat");
		if (reply == null)
		{
			throw new ConnectorException(config.getId() + ": empty answer");
		}
		final String text = LlmConnector.answerText(provider, reply);
		if (text == null)
		{
			final String raw = Json.write(reply);
			throw new ConnectorException(config.getId() + ": unexpected answer " + raw.substring(0, Math.min(300, raw.length())));
		}
		return LlmConnector.answerOf(provider, model, reply, text);
	}

	/** The chat-completions path of an OpenAI-shaped provider (OpenAI, Azure OpenAI, Azure AI Foundry, Mistral, any compatible endpoint); fills the query. */
	private String openAiShapedPath(final String pProvider, final String pModel, final Map<String, String> pQuery)
	{
		final ConnectorConfig config = this.getConfig();
		if ("azure".equals(pProvider))
		{
			final String version = config.getProperty("azure.apiVersion", "2024-10-21").trim();
			if ("v1".equalsIgnoreCase(version))
			{
				return "/openai/v1/chat/completions";
			}
			pQuery.put("api-version", version);
			return "/openai/deployments/" + AbstractRestConnector.segment(pModel) + "/chat/completions";
		}
		if ("azure-ai".equals(pProvider))
		{
			pQuery.put("api-version", config.getProperty("azure.apiVersion", "2024-05-01-preview").trim());
			return "/models/chat/completions";
		}
		return this.path(config.getProperty("openai.path", "/v1/chat/completions"));
	}

	/** Pure: the connector's own {@code maxTokens} (1024 when unset or not a positive number): the most a contract call may ask. */
	public static int configuredMaxTokens(final ConnectorConfig pConfig)
	{
		final String value = pConfig == null ? null : pConfig.getProperty("maxTokens", null);
		if (value != null && !value.trim().isEmpty())
		{
			try
			{
				final double parsed = Double.parseDouble(value.trim());
				if (parsed >= 1 && parsed <= Integer.MAX_VALUE)
				{
					return (int) parsed;
				}
			}
			catch (final NumberFormatException ex)
			{
				// not a number: the documented default below
			}
		}
		return LlmConnector.DEFAULT_MAX_TOKENS;
	}

	/**
	 * Pure: the tool-use wire format of a provider for {@link #chatTurn}: Anthropic Messages, or OpenAI chat completions for
	 * OpenAI, Azure OpenAI, Azure AI Foundry, Mistral and Ollama (its OpenAI-compatible endpoint). Null for Google (Gemini
	 * API, Vertex AI): its function calling has another shape, not offered by {@code llm.chat}.
	 */
	public static LlmClient.Format chatFormat(final String pProvider)
	{
		if ("anthropic".equals(pProvider))
		{
			return LlmClient.Format.ANTHROPIC;
		}
		return "google".equals(pProvider) ? null : LlmClient.Format.OPENAI_CHAT;
	}

	/** What one tool-use turn answered: the provider-neutral turn, plus the model and the token counts as the provider gave them (null when it gave none). */
	public static final class ChatAnswer
	{
		public final LlmClient.Turn turn;
		public final String provider;
		public final String model;
		public final Integer inputTokens;
		public final Integer outputTokens;
		public final Integer totalTokens;

		ChatAnswer(final LlmClient.Turn pTurn, final String pProvider, final Answer pUsage)
		{
			this.turn = pTurn;
			this.provider = pProvider;
			this.model = pUsage.model;
			this.inputTokens = pUsage.inputTokens;
			this.outputTokens = pUsage.outputTokens;
			this.totalTokens = pUsage.totalTokens;
		}
	}

	/**
	 * One turn of a conversation with tools, for the in-JVM contract's {@code llm.chat}: the request is built by the agent
	 * library's adapters ({@link LlmClient#request}, {@link LlmClient#turnOf}) and sent by this connector's own client, key,
	 * outbound rules and read timeout ({@code pReadTimeoutMs}, the connector's when not positive). One HTTP round trip
	 * (plus, once per connector instance, the resend with {@code reasoning_effort: none} that an OpenAI-shaped model asks
	 * for when it refuses tools with its default effort, as {@link LlmClient} does); no tool is ever run here. No
	 * {@code temperature} is sent, as in the agent library (reasoning models refuse it). {@code pJson} asks the API for
	 * a JSON answer when the connector's {@code jsonMode} rule says so (OpenAI-shaped providers only).
	 *
	 * @throws ConnectorException for Google (see {@link #chatFormat}), a missing secret, an error answer or a timeout
	 */
	public ChatAnswer chatTurn(final String pSystem, final List<LlmClient.ToolSpec> pTools, final List<LlmClient.Message> pConversation,
		final int pMaxTokens, final boolean pJson, final int pReadTimeoutMs) throws ConnectorException
	{
		this.requireSecret();
		final ConnectorConfig config = this.getConfig();
		final String provider = this.provider();
		final LlmClient.Format format = LlmConnector.chatFormat(provider);
		if (format == null)
		{
			throw new ConnectorException(config.getId() + ": tool use is not offered for the provider " + provider);
		}
		final String model = config.getProperty("model", LlmConnector.defaultModel(provider)).trim();
		final String system = pSystem == null || pSystem.trim().isEmpty() ? config.getProperty("system", "You are a precise data assistant.") : pSystem;
		final Map<String, String> query = new LinkedHashMap<String, String>();
		final String path;
		if (format == LlmClient.Format.ANTHROPIC)
		{
			path = this.path("/v1/messages");
		}
		else if ("ollama".equals(provider))
		{
			path = this.path("/v1/chat/completions");
		}
		else
		{
			path = this.openAiShapedPath(provider, model, query);
		}
		final boolean json = format == LlmClient.Format.OPENAI_CHAT && LlmConnector.jsonMode(config.getProperty("jsonMode", "auto"), provider, pJson);
		final String configuredEffort = config.getProperty("openai.reasoningEffort", "").trim();
		String effort = this.reasoningEffortNone ? LlmClient.REASONING_EFFORT_NONE : configuredEffort;
		com.on.ebx.connect.core.HttpJsonClient.Response response = this.send("POST", path, query,
			this.chatBody(format, model, pMaxTokens, effort, json, system, pTools, pConversation), null, pReadTimeoutMs);
		if (format == LlmClient.Format.OPENAI_CHAT && response.getStatus() == 400 && pTools != null && !pTools.isEmpty()
			&& !LlmClient.REASONING_EFFORT_NONE.equals(effort) && LlmClient.asksForNoReasoningEffort(response.getRawBody()))
		{
			this.reasoningEffortNone = true;
			effort = LlmClient.REASONING_EFFORT_NONE;
			response = this.send("POST", path, query, this.chatBody(format, model, pMaxTokens, effort, json, system, pTools, pConversation), null, pReadTimeoutMs);
		}
		if (response.getStatus() == 404)
		{
			final String detail = response.getRawBody() == null ? "" : response.getRawBody();
			throw new ConnectorException(config.getId() + ": " + provider + " chat -> HTTP 404 (model '" + model + "', path " + path + ") "
					+ (detail.length() > 300 ? detail.substring(0, 300) + "..." : detail), 404, null);
		}
		final JsonNode reply = this.bodyOrNull(response, provider + " chat");
		if (reply == null)
		{
			throw new ConnectorException(config.getId() + ": empty answer");
		}
		if (format == LlmClient.Format.ANTHROPIC ? !reply.path("content").isArray() : !reply.path("choices").path(0).path("message").isObject())
		{
			final String raw = Json.write(reply);
			throw new ConnectorException(config.getId() + ": unexpected answer " + raw.substring(0, Math.min(300, raw.length())));
		}
		final LlmClient.Turn turn = LlmClient.turnOf(format, reply);
		return new ChatAnswer(turn, provider, LlmConnector.answerOf(format == LlmClient.Format.ANTHROPIC ? "anthropic" : "openai", model, reply, turn.text));
	}

	private ObjectNode chatBody(final LlmClient.Format pFormat, final String pModel, final int pMaxTokens, final String pEffort, final boolean pJson,
		final String pSystem, final List<LlmClient.ToolSpec> pTools, final List<LlmClient.Message> pConversation)
	{
		final ObjectNode body = LlmClient.request(pFormat, pModel, pMaxTokens, this.getConfig().getProperty("openai.maxTokensParam", "max_tokens"), pEffort, pSystem,
			pTools, pConversation);
		if (pJson)
		{
			body.putObject("response_format").put("type", "json_object");
		}
		return body;
	}

	private static void messages(final ObjectNode pBody, final String pSystem, final String pPrompt)
	{
		final ArrayNode messages = pBody.putArray("messages");
		final ObjectNode system = messages.addObject();
		system.put("role", "system");
		system.put("content", pSystem);
		final ObjectNode user = messages.addObject();
		user.put("role", "user");
		user.put("content", pPrompt);
	}

	/** The path under the base URL, without doubling a version segment ({@code /v1}) the base URL already ends with. */
	private String path(final String pPath)
	{
		String url = this.getConfig().getBaseUrl() == null ? "" : this.getConfig().getBaseUrl().trim();
		while (url.endsWith("/"))
		{
			url = url.substring(0, url.length() - 1);
		}
		final int slash = pPath.indexOf('/', 1);
		if (slash > 0 && url.endsWith(pPath.substring(0, slash)))
		{
			return pPath.substring(slash);
		}
		return pPath;
	}

	/** The text of a chat answer in the provider's format, or null when there is none. */
	static String answerText(final String pProvider, final JsonNode pReply)
	{
		final JsonNode text;
		if ("anthropic".equals(pProvider))
		{
			text = Json.at(pReply, "content[0].text");
		}
		else if ("google".equals(pProvider))
		{
			final StringBuilder out = new StringBuilder();
			for (final JsonNode part : pReply.path("candidates").path(0).path("content").path("parts"))
			{
				if (part.path("text").isTextual() && !part.path("thought").asBoolean(false))
				{
					out.append(part.path("text").asText());
				}
			}
			return out.length() == 0 ? null : out.toString();
		}
		else if ("ollama".equals(pProvider))
		{
			text = pReply.path("message").path("content");
		}
		else
		{
			final JsonNode content = pReply.path("choices").path(0).path("message").path("content");
			if (content.isArray())
			{
				// Mistral's reasoning models answer with chunks: keep the text ones
				final StringBuilder out = new StringBuilder();
				for (final JsonNode chunk : content)
				{
					if ("text".equals(chunk.path("type").asText()) && chunk.path("text").isTextual())
					{
						out.append(chunk.path("text").asText());
					}
				}
				return out.length() == 0 ? null : out.toString();
			}
			text = content;
		}
		return text == null || text.isNull() || text.isMissingNode() ? null : text.asText();
	}

	/** The JSON object in the answer, code fences and surrounding prose tolerated. */
	static JsonNode jsonOf(final String pText)
	{
		if (pText == null)
		{
			return null;
		}
		String t = pText.trim();
		if (t.startsWith("```"))
		{
			t = t.substring(t.indexOf('\n') + 1);
			final int end = t.lastIndexOf("```");
			if (end > 0)
			{
				t = t.substring(0, end);
			}
		}
		final int open = t.indexOf('{');
		final int close = t.lastIndexOf('}');
		if (open < 0 || close <= open)
		{
			return null;
		}
		try
		{
			return Json.parse(t.substring(open, close + 1));
		}
		catch (final java.io.IOException ex)
		{
			return null;
		}
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("extract".equals(pOperation))
		{
			final List<FieldInfo> fields = new ArrayList<FieldInfo>();
			final String declared = pInputs == null ? null : pInputs.get("fields");
			if (declared != null)
			{
				for (final String item : declared.split(","))
				{
					final String name = item.contains(":") ? item.substring(0, item.indexOf(':')).trim() : item.trim();
					if (!name.isEmpty())
					{
						fields.add(FieldInfo.of(name, item.contains(":") ? item.substring(item.indexOf(':') + 1).trim() : name, "string"));
					}
				}
			}
			if (fields.isEmpty())
			{
				fields.add(FieldInfo.of("text", "Answer (set the fields input to get named fields)", "string"));
			}
			return fields;
		}
		if ("classify".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("category", "Category code", "string"), FieldInfo.of("confidence", "Confidence 0-1", "number"),
				FieldInfo.of("reason", "Reason", "string"));
		}
		if ("match".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("same", "Same entity?", "boolean"), FieldInfo.of("confidence", "Confidence 0-1", "number"),
				FieldInfo.of("reason", "Reason", "string"));
		}
		return Arrays.asList(FieldInfo.of("text", "Answer", "string"));
	}
}
