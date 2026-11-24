package com.on.ebx.connect.connectors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * The llm connector against a local fake of each provider's chat API (Anthropic, OpenAI, Google
 * Gemini API and Vertex AI, Azure OpenAI deployments and v1, Azure AI Foundry, Mistral, Ollama): path,
 * authentication header, request body (native JSON mode, temperature "none") and answer parsing.
 * Plus provider detection from the base URL. No account, no network.
 */
public final class LlmConnectorTest
{
	private static int checks;

	private LlmConnectorTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		// provider detection
		eq("anthropic", LlmConnector.providerOf("auto", "https://api.anthropic.com"), "anthropic from URL");
		eq("google", LlmConnector.providerOf(null, "https://generativelanguage.googleapis.com"), "Gemini API from URL");
		eq("google", LlmConnector.providerOf("", "https://europe-west1-aiplatform.googleapis.com"), "Vertex AI from URL");
		eq("azure", LlmConnector.providerOf("auto", "https://acme.openai.azure.com"), "Azure OpenAI from URL");
		eq("azure-ai", LlmConnector.providerOf("auto", "https://acme.services.ai.azure.com"), "Azure AI Foundry from URL");
		eq("mistral", LlmConnector.providerOf("auto", "https://api.mistral.ai/v1"), "Mistral from URL");
		eq("ollama", LlmConnector.providerOf("auto", "http://gpu-box:11434"), "Ollama from port");
		eq("openai", LlmConnector.providerOf("auto", "https://api.openai.com"), "OpenAI by default");
		eq("mistral", LlmConnector.providerOf("Mistral", "http://127.0.0.1:1"), "declared provider wins");
		yes(LlmConnector.PROVIDERS.contains("google") && LlmConnector.PROVIDERS.contains("ollama") && LlmConnector.PROVIDERS.contains("auto"), "provider enumeration");

		final String previousAllowList = System.getProperty(Egress.PROPERTY);
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final Fake fake = new Fake();
		server.createContext("/", fake);
		server.start();
		try
		{
			final int port = server.getAddress().getPort();
			final String base = "http://127.0.0.1:" + port;
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + port);
			for (String suffix : new String[] { "", "/v1" }) {
				LlmConnector probe = new LlmConnector();
				probe.configure(config("openai", base + suffix, ConnectorConfig.AuthType.BEARER, "sk-test", null));
				yes(probe.testConnection(null).isSuccessful(), "authenticated GET models probe");
				eq("/v1/models", fake.path, "models version not doubled");
				eq("Bearer sk-test", fake.headers.get("authorization"), "probe auth");
				fake.probeStatus = 401;
				yes(!probe.testConnection(null).isSuccessful(), "invalid key rejected");
				fake.probeStatus = 403;
				yes(probe.testConnection(null).getMessage().contains("permissions"), "restricted key distinguished");
				fake.probeStatus = 200;
			}

			// Anthropic: BEARER translated to x-api-key, temperature none omitted
			JsonNode out = classify(config("anthropic", base, ConnectorConfig.AuthType.BEARER, "sk-ant-1", null, "temperature", "none"));
			eq("/v1/messages", fake.path, "anthropic path");
			eq("sk-ant-1", fake.headers.get("x-api-key"), "anthropic key header");
			yes(fake.headers.get("authorization") == null, "anthropic: no Bearer header");
			yes(!fake.body.has("temperature") && "claude-sonnet-5".equals(fake.body.path("model").asText()), "anthropic body: no temperature, default model");
			category(out, "anthropic");

			// OpenAI: Bearer, base URL ending in /v1 not doubled, no native JSON mode by default
			out = classify(config("openai", base + "/v1", ConnectorConfig.AuthType.BEARER, "sk-2", null));
			eq("/v1/chat/completions", fake.path, "openai path not doubled");
			eq("Bearer sk-2", fake.headers.get("authorization"), "openai Bearer");
			yes(!fake.body.has("response_format") && fake.body.path("temperature").asDouble(-1) == 0.0, "openai body: no response_format, temperature 0");
			category(out, "openai");

			// Google Gemini API: key header, systemInstruction, JSON mime type, thought parts skipped
			out = classify(config("google", base, ConnectorConfig.AuthType.API_KEY_HEADER, "AIza-3", "x-goog-api-key", "model", "models/gemini-2.5-pro"));
			eq("/v1beta/models/gemini-2.5-pro:generateContent", fake.path, "gemini path");
			eq("AIza-3", fake.headers.get("x-goog-api-key"), "gemini key header");
			yes(fake.body.path("systemInstruction").path("parts").path(0).path("text").asText().startsWith("You classify"), "gemini system instruction");
			eq("application/json", fake.body.path("generationConfig").path("responseMimeType").asText(), "gemini JSON mode");
			eq("1024", fake.body.path("generationConfig").path("maxOutputTokens").asText(), "gemini max tokens");
			category(out, "gemini");

			// Gemini with BEARER: translated to x-goog-api-key
			classify(config("google", base, ConnectorConfig.AuthType.BEARER, "AIza-4", null));
			yes("AIza-4".equals(fake.headers.get("x-goog-api-key")) && fake.headers.get("authorization") == null, "gemini BEARER translated");
			eq("/v1beta/models/gemini-2.5-flash:generateContent", fake.path, "gemini default model");

			// Vertex AI: project/location path, the Bearer token kept
			final ConnectorConfig vertex = config("google", base, ConnectorConfig.AuthType.BEARER, "ya29.token", null, "google.project", "acme-mdm", "google.location", "europe-west1");
			vertex.setBaseUrl(base + "/aiplatform.googleapis.com-proxy");
			classify(vertex);
			eq("/aiplatform.googleapis.com-proxy/v1/projects/acme-mdm/locations/europe-west1/publishers/google/models/gemini-2.5-flash:generateContent", fake.path, "vertex path");
			eq("Bearer ya29.token", fake.headers.get("authorization"), "vertex Bearer kept");

			// Azure OpenAI: deployment path + api-version, api-key header
			out = classify(config("azure", base, ConnectorConfig.AuthType.API_KEY_HEADER, "az-5", "api-key", "model", "gpt4o-prod"));
			eq("/openai/deployments/gpt4o-prod/chat/completions?api-version=2024-10-21", fake.path, "azure deployment path");
			eq("az-5", fake.headers.get("api-key"), "azure key header");
			category(out, "azure");

			// Azure OpenAI v1 API
			classify(config("azure", base, ConnectorConfig.AuthType.API_KEY_HEADER, "az-6", "api-key", "azure.apiVersion", "v1"));
			yes("/openai/v1/chat/completions".equals(fake.path) && "gpt-4o-mini".equals(fake.body.path("model").asText()), "azure v1 path, model in body: " + fake.path);

			// Azure AI Foundry models
			out = classify(config("azure-ai", base, ConnectorConfig.AuthType.API_KEY_HEADER, "az-7", "api-key", "model", "Phi-4"));
			eq("/models/chat/completions?api-version=2024-05-01-preview", fake.path, "foundry path");
			eq("Phi-4", fake.body.path("model").asText(), "foundry model in body");
			category(out, "foundry");

			// Mistral: Bearer, native JSON mode, chunked content
			fake.mistralChunks = true;
			out = classify(config("mistral", base, ConnectorConfig.AuthType.BEARER, "ms-8", null));
			fake.mistralChunks = false;
			eq("/v1/chat/completions", fake.path, "mistral path");
			eq("Bearer ms-8", fake.headers.get("authorization"), "mistral Bearer");
			eq("json_object", fake.body.path("response_format").path("type").asText(), "mistral JSON mode");
			eq("mistral-small-latest", fake.body.path("model").asText(), "mistral default model");
			category(out, "mistral chunks");

			// Ollama: no authentication, /api/chat, stream false, format json, options
			out = classify(config("ollama", base, ConnectorConfig.AuthType.NONE, null, null));
			eq("/api/chat", fake.path, "ollama path");
			yes(fake.headers.get("authorization") == null && fake.headers.get("x-api-key") == null, "ollama: no credentials");
			yes(!fake.body.path("stream").asBoolean(true) && "json".equals(fake.body.path("format").asText()) && fake.body.path("options").path("num_predict").asInt() == 1024,
				"ollama body: stream false, format json, num_predict");
			category(out, "ollama");

			// complete never asks for JSON
			final LlmConnector ollama = new LlmConnector();
			ollama.configure(config("ollama", base, ConnectorConfig.AuthType.NONE, null, null, "jsonMode", "true"));
			final Map<String, String> prompt = new LinkedHashMap<String, String>();
			prompt.put("prompt", "Say hello");
			final JsonNode text = ollama.execute(null, "complete", prompt, null);
			yes(!fake.body.has("format") && text.path("text").asText().contains("category"), "complete: no JSON mode, text returned");

			// unknown model: the provider's 404 is reported, not read as an empty answer
			try
			{
				classify(config("ollama", base, ConnectorConfig.AuthType.NONE, null, null, "model", "missing"));
				yes(false, "a 404 must fail");
			}
			catch (final com.on.ebx.connect.spi.ConnectorException ex)
			{
				yes(ex.getMessage().contains("HTTP 404") && ex.getMessage().contains("model 'missing' not found"), "404 detail reported: " + ex.getMessage());
			}

			// jsonMode=false turns the native mode off
			classify(config("google", base, ConnectorConfig.AuthType.API_KEY_HEADER, "AIza-9", "x-goog-api-key", "jsonMode", "false"));
			yes(!fake.body.path("generationConfig").has("responseMimeType"), "jsonMode false honoured");

			// the in-JVM contract's call (llm.ask): answer size, JSON expectation and read timeout for this call only
			contractCall(fake, base);
			visionCall(fake, base);
		}
		finally
		{
			server.stop(0);
			if (previousAllowList == null)
			{
				System.clearProperty(Egress.PROPERTY);
			}
			else
			{
				System.setProperty(Egress.PROPERTY, previousAllowList);
			}
		}
		System.out.println("PASS: llm connector providers (" + LlmConnectorTest.checks + " checks)");
	}

	/** {@link LlmConnector#ask(String, String, Integer, boolean, int)}, the call of the contract operation llm.ask. */
	private static void contractCall(final Fake pFake, final String pBase) throws Exception
	{
		final LlmConnector claude = new LlmConnector();
		claude.configure(config("anthropic", pBase, ConnectorConfig.AuthType.BEARER, "sk-ant-c", null, "system", "Default instructions"));
		LlmConnector.Answer answer = claude.ask(null, "Describe the column", Integer.valueOf(4096), false, 0);
		eq("/v1/messages", pFake.path, "contract call: anthropic path");
		eq(4096, pFake.body.path("max_tokens").asInt(), "contract call: maxTokens of the call sent instead of the connector's 1024");
		eq("Default instructions", pFake.body.path("system").asText(), "contract call: the connector's system prompt when none is given");
		eq("sk-ant-c", pFake.headers.get("x-api-key"), "contract call: the connector's own key header");
		yes(answer.text.contains("category"), "contract call: text answered");
		eq("claude-test-model", answer.model, "contract call: the model the provider says answered");
		eq(Integer.valueOf(12), answer.inputTokens, "contract call: input tokens");
		eq(Integer.valueOf(34), answer.outputTokens, "contract call: output tokens");
		eq(Integer.valueOf(46), answer.totalTokens, "contract call: total computed when the provider gives none");
		yes(!answer.truncated, "contract call: end_turn is not a cut answer");
		answer = claude.ask("Own system", "Describe", null, false, 0);
		eq(1024, pFake.body.path("max_tokens").asInt(), "contract call: the connector's maxTokens when the call gives none");
		eq("Own system", pFake.body.path("system").asText(), "contract call: the caller's system prompt");
		try
		{
			claude.ask(null, " ", null, false, 0);
			yes(false, "an empty prompt must be refused");
		}
		catch (final com.on.ebx.connect.spi.ConnectorException ex)
		{
			yes(ex.getMessage().contains("prompt is empty"), "empty prompt refused before any call: " + ex.getMessage());
		}

		// json=true: the connector's jsonMode rule decides (auto = native for Ollama), complete itself never asks
		final LlmConnector ollama = new LlmConnector();
		ollama.configure(config("ollama", pBase, ConnectorConfig.AuthType.NONE, null, null));
		ollama.ask(null, "Answer in JSON", Integer.valueOf(2048), true, 0);
		yes("json".equals(pFake.body.path("format").asText()) && pFake.body.path("options").path("num_predict").asInt() == 2048, "json=true and maxTokens on ollama: " + pFake.body);
		ollama.ask(null, "Answer in text", null, false, 0);
		yes(!pFake.body.has("format"), "json=false: no native JSON mode");
		final LlmConnector anthropicNoKey = new LlmConnector();
		anthropicNoKey.configure(config("anthropic", pBase, ConnectorConfig.AuthType.BEARER, "TO_BE_SET", null));
		yes(anthropicNoKey.secretProblem() != null && anthropicNoKey.secretProblem().contains("API key not configured") && claude.secretProblem() == null,
			"secretProblem says when the key is missing, and only then");

		// read timeout of the call: shorter than the model's delay -> timeout; the cached client keeps its own (10 s)
		pFake.delayMs = 1500;
		try
		{
			claude.ask(null, "Slow", null, false, 1000);
			yes(false, "a call longer than its timeout must fail");
		}
		catch (final com.on.ebx.connect.spi.ConnectorException ex)
		{
			yes(com.on.ebx.connect.ebx.contract.ContractService.timedOut(ex), "the failure is recognised as a timeout: " + ex.getMessage());
		}
		answer = claude.ask(null, "Slow again", null, false, 0);
		yes(answer.text.contains("category"), "the connector's own read timeout (10 s) is untouched by the shorter call");
		answer = claude.ask(null, "Slow, longer timeout", null, false, 5000);
		yes(answer.text.contains("category"), "a longer timeout of the call lets the answer arrive");
		pFake.delayMs = 0;

		// usage, model and cut answers in each provider's format (pure)
		LlmConnector.Answer parsed = LlmConnector.answerOf("openai", "gpt-configured", Json.parse(
			"{\"model\":\"gpt-4o-2024\",\"choices\":[{\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":7,\"total_tokens\":13}}"), "t");
		yes("gpt-4o-2024".equals(parsed.model) && parsed.inputTokens.intValue() == 5 && parsed.outputTokens.intValue() == 7 && parsed.totalTokens.intValue() == 13 && parsed.truncated,
			"openai usage, model, finish_reason length = cut");
		parsed = LlmConnector.answerOf("google", "gemini-configured", Json.parse(
			"{\"modelVersion\":\"gemini-2.5-flash-001\",\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}],\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":4,\"totalTokenCount\":9}}"), "t");
		yes("gemini-2.5-flash-001".equals(parsed.model) && parsed.inputTokens.intValue() == 3 && parsed.totalTokens.intValue() == 9 && parsed.truncated, "gemini usage and MAX_TOKENS");
		parsed = LlmConnector.answerOf("ollama", "llama3.2", Json.parse("{\"prompt_eval_count\":8,\"eval_count\":2,\"done_reason\":\"stop\"}"), "t");
		yes("llama3.2".equals(parsed.model) && parsed.totalTokens.intValue() == 10 && !parsed.truncated, "ollama counts, configured model when the reply names none");
		parsed = LlmConnector.answerOf("anthropic", "claude-configured", Json.parse("{\"stop_reason\":\"max_tokens\"}"), "t");
		yes(parsed.truncated && parsed.inputTokens == null && parsed.totalTokens == null && "claude-configured".equals(parsed.model), "anthropic cut, no usage given = nulls");
		parsed = LlmConnector.answerOf("mistral", "m", null, "t");
		yes("m".equals(parsed.model) && parsed.outputTokens == null && !parsed.truncated, "no reply body: nothing invented");
	}

	/** Images and PDFs sent with the prompt: each provider's format (pure), the opt-in and the PDF refusals, one call on the wire. */
	private static void visionCall(final Fake pFake, final String pBase) throws Exception
	{
		final java.util.List<LlmConnector.Attachment> png = java.util.Collections.singletonList(new LlmConnector.Attachment("image/png", new byte[] { 1, 2, 3 }, "a.png"));
		final java.util.List<LlmConnector.Attachment> pdf = java.util.Collections.singletonList(new LlmConnector.Attachment("application/pdf", new byte[] { 4, 5 }, "s.pdf"));
		final String b64 = "AQID";
		final String pdf64 = "BAU=";

		com.fasterxml.jackson.databind.node.ObjectNode body = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse("{\"messages\":[{\"role\":\"user\",\"content\":\"Read it\"}]}");
		LlmConnector.attach("anthropic", body, png);
		JsonNode content = body.path("messages").path(0).path("content");
		yes("image".equals(content.path(0).path("type").asText()) && "base64".equals(content.path(0).path("source").path("type").asText())
			&& "image/png".equals(content.path(0).path("source").path("media_type").asText()) && b64.equals(content.path(0).path("source").path("data").asText())
			&& "Read it".equals(content.path(1).path("text").asText()), "anthropic image block before the text: " + body);
		body = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse("{\"messages\":[{\"role\":\"user\",\"content\":\"Read it\"}]}");
		LlmConnector.attach("anthropic", body, pdf);
		yes("document".equals(body.path("messages").path(0).path("content").path(0).path("type").asText())
			&& "application/pdf".equals(body.path("messages").path(0).path("content").path(0).path("source").path("media_type").asText()), "anthropic document block");

		for (final String provider : new String[] { "openai", "azure", "azure-ai" })
		{
			body = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse("{\"messages\":[{\"role\":\"system\",\"content\":\"S\"},{\"role\":\"user\",\"content\":\"Read it\"}]}");
			LlmConnector.attach(provider, body, png);
			content = body.path("messages").path(1).path("content");
			yes("image_url".equals(content.path(0).path("type").asText()) && ("data:image/png;base64," + b64).equals(content.path(0).path("image_url").path("url").asText())
				&& "text".equals(content.path(1).path("type").asText()) && "S".equals(body.path("messages").path(0).path("content").asText()), provider + " image_url data URL: " + body);
		}
		body = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse("{\"messages\":[{\"role\":\"system\",\"content\":\"S\"},{\"role\":\"user\",\"content\":\"Read it\"}]}");
		LlmConnector.attach("openai", body, pdf);
		content = body.path("messages").path(1).path("content").path(0);
		yes("file".equals(content.path("type").asText()) && "s.pdf".equals(content.path("file").path("filename").asText())
			&& ("data:application/pdf;base64," + pdf64).equals(content.path("file").path("file_data").asText()), "openai file part: " + content);

		body = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse("{\"messages\":[{\"role\":\"system\",\"content\":\"S\"},{\"role\":\"user\",\"content\":\"Read it\"}]}");
		LlmConnector.attach("mistral", body, png);
		content = body.path("messages").path(1).path("content").path(0);
		yes("image_url".equals(content.path("type").asText()) && ("data:image/png;base64," + b64).equals(content.path("image_url").asText()), "mistral image_url is a string: " + content);
		body = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse("{\"messages\":[{\"role\":\"system\",\"content\":\"S\"},{\"role\":\"user\",\"content\":\"Read it\"}]}");
		LlmConnector.attach("mistral", body, pdf);
		content = body.path("messages").path(1).path("content").path(0);
		yes("document_url".equals(content.path("type").asText()) && ("data:application/pdf;base64," + pdf64).equals(content.path("document_url").asText()), "mistral document_url: " + content);

		body = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Read it\"}]}]}");
		LlmConnector.attach("google", body, png);
		final JsonNode parts = body.path("contents").path(0).path("parts");
		yes(parts.size() == 2 && "image/png".equals(parts.path(0).path("inlineData").path("mimeType").asText()) && b64.equals(parts.path(0).path("inlineData").path("data").asText())
			&& "Read it".equals(parts.path(1).path("text").asText()), "gemini inlineData part before the text: " + body);

		body = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse("{\"messages\":[{\"role\":\"system\",\"content\":\"S\"},{\"role\":\"user\",\"content\":\"Read it\"}]}");
		LlmConnector.attach("ollama", body, png);
		yes("Read it".equals(body.path("messages").path(1).path("content").asText()) && b64.equals(body.path("messages").path(1).path("images").path(0).asText()), "ollama raw base64 images: " + body);

		// opt-in and PDF support, refused before any call
		final LlmConnector blind = new LlmConnector();
		blind.configure(config("anthropic", pBase, ConnectorConfig.AuthType.BEARER, "sk-ant-v", null));
		try
		{
			blind.ask(null, "Read it", png, null, true, 0);
			yes(false, "an image without vision=true must be refused");
		}
		catch (final com.on.ebx.connect.spi.ConnectorException ex)
		{
			yes(ex.getMessage().contains("vision=true"), "vision off refused: " + ex.getMessage());
		}
		for (final String provider : new String[] { "ollama", "azure", "azure-ai" })
		{
			final LlmConnector noPdf = new LlmConnector();
			noPdf.configure(config(provider, pBase, ConnectorConfig.AuthType.NONE, null, null, "vision", "true"));
			yes(!noPdf.readsPdf(), provider + " reads no PDF");
			try
			{
				noPdf.ask(null, "Read it", pdf, null, true, 0);
				yes(false, provider + ": a PDF must be refused");
			}
			catch (final com.on.ebx.connect.spi.ConnectorException ex)
			{
				yes(ex.getMessage().contains("does not read a PDF"), provider + " PDF refused: " + ex.getMessage());
			}
		}

		// one call on the wire: the image reaches the provider, no attachment keeps the text-only body
		final LlmConnector claude = new LlmConnector();
		claude.configure(config("anthropic", pBase, ConnectorConfig.AuthType.BEARER, "sk-ant-v", null, "vision", "true"));
		yes(claude.vision() && claude.readsPdf(), "vision declared");
		final LlmConnector.Answer answer = claude.ask("Sys", "Read it", png, Integer.valueOf(2048), true, 0);
		content = pFake.body.path("messages").path(0).path("content");
		yes("/v1/messages".equals(pFake.path) && "image".equals(content.path(0).path("type").asText()) && "Read it".equals(content.path(1).path("text").asText())
			&& answer.text.contains("category"), "the image is sent: " + pFake.body);
		claude.ask("Sys", "Text only", java.util.Collections.<LlmConnector.Attachment>emptyList(), null, false, 0);
		yes("Text only".equals(pFake.body.path("messages").path(0).path("content").asText()), "no attachment: the prompt stays a string");
	}

	private static ConnectorConfig config(final String pProvider, final String pBase, final ConnectorConfig.AuthType pAuth, final String pKey, final String pHeader,
		final String... pProperties)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("Llm-" + pProvider);
		config.setType("llm");
		config.setBaseUrl(pBase);
		config.setAuthType(pAuth);
		config.setApiKey(pKey);
		config.setApiKeyHeader(pHeader);
		config.setConnectTimeoutMs(5000);
		config.setReadTimeoutMs(10000);
		config.setProperty("provider", pProvider);
		for (int i = 0; i + 1 < pProperties.length; i += 2)
		{
			config.setProperty(pProperties[i], pProperties[i + 1]);
		}
		return config;
	}

	private static JsonNode classify(final ConnectorConfig pConfig) throws Exception
	{
		final LlmConnector connector = new LlmConnector();
		connector.configure(pConfig);
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("text", "Manufacturer of industrial gases");
		inputs.put("categories", "A=Agriculture;C=Manufacturing");
		return connector.execute(null, "classify", inputs, null);
	}

	private static void category(final JsonNode pOut, final String pWhat)
	{
		yes(pOut != null && "C".equals(pOut.path("category").asText()) && pOut.path("confidence").asDouble() == 0.9, pWhat + ": answer parsed (" + pOut + ")");
	}

	private static void eq(final Object pExpected, final Object pActual, final String pWhat)
	{
		yes(pExpected == null ? pActual == null : pExpected.equals(pActual), pWhat + ": expected " + pExpected + ", got " + pActual);
	}

	private static void yes(final boolean pCondition, final String pWhat)
	{
		LlmConnectorTest.checks++;
		if (!pCondition)
		{
			throw new AssertionError("FAIL: " + pWhat);
		}
	}

	/** Answers like the provider the path designates; remembers the last request. */
	private static final class Fake implements HttpHandler
	{
		private static final String ANSWER = "{\"category\":\"C\",\"confidence\":0.9,\"reason\":\"gases are manufactured\"}";
		String path;
		Map<String, String> headers;
		JsonNode body;
		boolean mistralChunks;
		int probeStatus = 200;
		volatile long delayMs;

		@Override
		public void handle(final HttpExchange pExchange) throws IOException
		{
			this.path = pExchange.getRequestURI().getRawPath() + (pExchange.getRequestURI().getRawQuery() == null ? "" : "?" + pExchange.getRequestURI().getRawQuery());
			this.headers = new LinkedHashMap<String, String>();
			for (final Map.Entry<String, java.util.List<String>> header : pExchange.getRequestHeaders().entrySet())
			{
				this.headers.put(header.getKey().toLowerCase(java.util.Locale.ENGLISH), header.getValue().get(0));
			}
			if ("/v1/models".equals(this.path)) {
				if (!"GET".equals(pExchange.getRequestMethod())) throw new AssertionError("Models probe must use GET");
				byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
				pExchange.sendResponseHeaders(this.probeStatus, bytes.length);
				pExchange.getResponseBody().write(bytes);
				pExchange.close();
				return;
			}
			this.body = Json.parse(read(pExchange.getRequestBody()));
			if ("missing".equals(this.body.path("model").asText()))
			{
				final byte[] bytes = "{\"error\":\"model 'missing' not found\"}".getBytes(StandardCharsets.UTF_8);
				pExchange.sendResponseHeaders(404, bytes.length);
				pExchange.getResponseBody().write(bytes);
				pExchange.getResponseBody().close();
				return;
			}
			final String reply;
			final String quoted = Json.write(new com.fasterxml.jackson.databind.node.TextNode(Fake.ANSWER));
			if (this.path.endsWith("/v1/messages"))
			{
				reply = "{\"model\":\"claude-test-model\",\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":12,\"output_tokens\":34},"
					+ "\"content\":[{\"type\":\"text\",\"text\":" + quoted + "}]}";
			}
			else if (this.path.contains(":generateContent"))
			{
				reply = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"let me think\",\"thought\":true},{\"text\":" + quoted + "}]}}]}";
			}
			else if (this.path.equals("/api/chat"))
			{
				reply = "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":" + quoted + "},\"done\":true}";
			}
			else if (this.mistralChunks)
			{
				reply = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":[]},{\"type\":\"text\",\"text\":" + quoted + "}]}}]}";
			}
			else
			{
				reply = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + quoted + "}}]}";
			}
			if (this.delayMs > 0)
			{
				try
				{
					Thread.sleep(this.delayMs);
				}
				catch (final InterruptedException ex)
				{
					Thread.currentThread().interrupt();
				}
			}
			final byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
			pExchange.getResponseHeaders().add("Content-Type", "application/json");
			pExchange.sendResponseHeaders(200, bytes.length);
			final OutputStream out = pExchange.getResponseBody();
			out.write(bytes);
			out.close();
		}

		private static String read(final InputStream pIn) throws IOException
		{
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			final byte[] chunk = new byte[4096];
			for (int n = pIn.read(chunk); n >= 0; n = pIn.read(chunk))
			{
				buffer.write(chunk, 0, n);
			}
			return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
