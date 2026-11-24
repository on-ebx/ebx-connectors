package com.on.ebx.connect.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minimal JSON-over-HTTP client on {@link HttpURLConnection} (Java 8 compatible, no extra
 * dependency, honours the JVM proxy settings). Retries once on HTTP 429 after a short pause.
 * <p>
 * Redirects are never left to the JDK: it would follow them to any host without asking
 * {@link Egress}, and replay the caller's headers there. They are followed here, at most
 * {@link #MAX_REDIRECTS} hops, each hop checked like a first call; as soon as a hop leaves the
 * origin (host or port) of the request, only the headers of {@link #SAFE_ACROSS_ORIGINS} travel
 * on, so that no credential reaches a host the caller did not name. A redirect that changes the
 * scheme (https to http, or the reverse) is returned to the caller, not followed.
 */
public final class HttpJsonClient
{
	public static final class Response
	{
		private final int status;
		private final String rawBody;
		private final JsonNode body;
		private final Map<String, List<String>> headers;
		private final String finalUrl;

		Response(final int pStatus, final String pRawBody, final Map<String, List<String>> pHeaders)
		{
			this(pStatus, pRawBody, pHeaders, null);
		}

		Response(final int pStatus, final String pRawBody, final Map<String, List<String>> pHeaders, final String pFinalUrl)
		{
			this.status = pStatus;
			this.rawBody = pRawBody;
			this.headers = pHeaders;
			this.finalUrl = pFinalUrl;
			JsonNode parsed = null;
			try
			{
				parsed = Json.parse(pRawBody);
			}
			catch (final IOException ex)
			{
				parsed = null;
			}
			this.body = parsed;
		}

		public int getStatus()
		{
			return this.status;
		}

		public String getRawBody()
		{
			return this.rawBody;
		}

		/** Effective URL after redirects, when the transport exposes it. */
		public String getFinalUrl() { return this.finalUrl; }

		/** Parsed body, null when empty or not JSON. */
		public JsonNode getBody()
		{
			return this.body;
		}

		public String getHeader(final String pName)
		{
			if (this.headers == null)
			{
				return null;
			}
			for (final Map.Entry<String, List<String>> entry : this.headers.entrySet())
			{
				if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(pName)
					&& entry.getValue() != null && !entry.getValue().isEmpty())
				{
					return entry.getValue().get(0);
				}
			}
			return null;
		}

		/** Every value of a header (Set-Cookie comes several times). */
		public List<String> getHeaders(final String pName)
		{
			final List<String> values = new java.util.ArrayList<String>();
			if (this.headers == null)
			{
				return values;
			}
			for (final Map.Entry<String, List<String>> entry : this.headers.entrySet())
			{
				if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(pName) && entry.getValue() != null)
				{
					values.addAll(entry.getValue());
				}
			}
			return values;
		}

		public boolean isSuccess()
		{
			return this.status >= 200 && this.status < 300;
		}
	}

	/** {@code -Debx.connectors.http.maxResponseBytes}: ceiling on one HTTP answer read without an explicit limit; 0 = no ceiling (audit C-16). */
	public static final String MAX_RESPONSE_BYTES_PROPERTY = "ebx.connectors.http.maxResponseBytes";
	/** 128 MiB: far above any API page, far below what would exhaust the heap on an unbounded read. */
	public static final int DEFAULT_MAX_RESPONSE_BYTES = 128 * 1024 * 1024;

	/**
	 * The response ceiling applied when a caller gave none: the system property, else {@link #DEFAULT_MAX_RESPONSE_BYTES}.
	 * {@code 0} removes it; an unreadable or negative value keeps the default.
	 */
	public static int defaultMaxResponseBytes()
	{
		return HttpJsonClient.parseMaxResponseBytes(System.getProperty(HttpJsonClient.MAX_RESPONSE_BYTES_PROPERTY));
	}

	static int parseMaxResponseBytes(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return HttpJsonClient.DEFAULT_MAX_RESPONSE_BYTES;
		}
		try
		{
			final long value = Long.parseLong(pValue.trim());
			return value < 0 ? HttpJsonClient.DEFAULT_MAX_RESPONSE_BYTES : (int) Math.min(value, Integer.MAX_VALUE);
		}
		catch (final NumberFormatException ex)
		{
			return HttpJsonClient.DEFAULT_MAX_RESPONSE_BYTES;
		}
	}

	/** The message of a read stopped by the ceiling; names the property when the ceiling is the default one. */
	static String limitMessage(final int pLimit)
	{
		return "HTTP response exceeds byte limit " + pLimit + (pLimit == HttpJsonClient.defaultMaxResponseBytes()
			? " (default ceiling; -D" + HttpJsonClient.MAX_RESPONSE_BYTES_PROPERTY + "=<bytes> raises it, 0 removes it)" : "");
	}

	/** Redirect hops followed for one request; one more is an error, not an endless chain. */
	public static final int MAX_REDIRECTS = 5;
	/**
	 * The request headers that follow a redirect to another origin. Everything else is dropped
	 * there: credentials travel under names this class cannot enumerate ({@code Authorization},
	 * {@code Cookie}, {@code X-Api-Key}, the configurable API-key header of a connector, signatures).
	 */
	private static final java.util.Set<String> SAFE_ACROSS_ORIGINS = new java.util.HashSet<String>(java.util.Arrays.asList(
		"accept", "accept-charset", "accept-language", "content-type", "content-language", "content-encoding"));

	private final int connectTimeoutMs;
	private final int readTimeoutMs;
	private final String userAgent;
	private final HttpRetryPolicy retryPolicy;
	private javax.net.ssl.SSLContext sslContext;
	private boolean platformMetadata;

	/**
	 * For the module's own workload-identity lookups only (EC2, ECS, Azure and Google metadata
	 * services, at an address fixed by the platform): the link-local refusal of {@link Egress}
	 * does not apply to this client. Never for a URL that comes from a connector.
	 */
	public HttpJsonClient forPlatformMetadata()
	{
		this.platformMetadata = true;
		return this;
	}

	/** The read timeout of this client, in milliseconds. */
	public int getReadTimeoutMs()
	{
		return this.readTimeoutMs;
	}

	/**
	 * The same client (user agent, retry policy, TLS context) with another read timeout, for one call that must wait
	 * longer or shorter than the connector's setting (the in-JVM contract's language-model call); this client is left as
	 * it is, so a cached connector shared by other threads keeps its own timeout. This client itself when the value is
	 * the same or not positive.
	 */
	public HttpJsonClient withReadTimeout(final int pReadTimeoutMs)
	{
		if (pReadTimeoutMs <= 0 || pReadTimeoutMs == this.readTimeoutMs)
		{
			return this;
		}
		final HttpJsonClient copy = new HttpJsonClient(this.connectTimeoutMs, pReadTimeoutMs, this.userAgent, this.retryPolicy);
		copy.sslContext = this.sslContext;
		copy.platformMetadata = this.platformMetadata;
		return copy;
	}

	/** Client certificate and/or private CA for this client (mutual TLS); null keeps the JVM defaults. */
	public void setSslContext(final javax.net.ssl.SSLContext pContext)
	{
		this.sslContext = pContext;
	}

	public HttpJsonClient(final int pConnectTimeoutMs, final int pReadTimeoutMs, final String pUserAgent)
	{
		this(pConnectTimeoutMs, pReadTimeoutMs, pUserAgent, HttpRetryPolicy.defaults());
	}

	public HttpJsonClient(final int pConnectTimeoutMs, final int pReadTimeoutMs, final String pUserAgent,
		final HttpRetryPolicy pRetryPolicy)
	{
		this.connectTimeoutMs = pConnectTimeoutMs;
		this.readTimeoutMs = pReadTimeoutMs;
		this.userAgent = pUserAgent == null ? "EBX-Connectors/1.0" : pUserAgent;
		this.retryPolicy = pRetryPolicy == null ? HttpRetryPolicy.defaults() : pRetryPolicy;
	}

	public Response send(final String pMethod, final String pUrl, final Map<String, String> pHeaders,
		final String pBody) throws IOException
	{
		if ("PATCH".equalsIgnoreCase(pMethod))
		{
			Response patched = null;
			for (int attempt = 1; attempt <= this.retryPolicy.getMaxAttempts(); attempt++)
			{
				patched = this.sendWithJavaNetHttp(pMethod, pUrl, pHeaders, pBody);
				if (patched == null) break;
				if (!this.retryPolicy.canRetry(pMethod, patched.getStatus(), attempt)) return patched;
				HttpExchangeStats.retried(patched.getStatus(), patched.getHeader("Retry-After"));
				try { this.retryPolicy.pause(attempt, patched.getHeader("Retry-After")); }
				catch (final InterruptedException ex)
				{
					Thread.currentThread().interrupt();
					return patched;
				}
			}
			if (patched != null) return patched;
		}
		return this.sendBytes(pMethod, pUrl, pHeaders, pBody == null ? null : pBody.getBytes("UTF-8"));
	}

	/** Sends an arbitrary body (multipart and binary uploads) with the same bounded retry policy. */
	public Response sendBytes(final String pMethod, final String pUrl, final Map<String, String> pHeaders,
		final byte[] pBody) throws IOException
	{
		return this.sendBytes(pMethod, pUrl, pHeaders, pBody, 0);
	}

	/** Same exchange with a streaming response ceiling; zero keeps the legacy unbounded behavior. */
	public Response sendBytes(final String pMethod, final String pUrl, final Map<String, String> pHeaders,
		final byte[] pBody, final int pMaximumResponseBytes) throws IOException
	{
		return this.sendBytes(pMethod, pUrl, pHeaders, pBody, pMaximumResponseBytes, true);
	}

	/** Bounded exchange that returns redirects to the caller instead of forwarding sensitive headers. */
	public Response sendBytesWithoutRedirects(final String pMethod, final String pUrl,
		final Map<String, String> pHeaders, final byte[] pBody, final int pMaximumResponseBytes) throws IOException
	{
		return this.sendBytes(pMethod, pUrl, pHeaders, pBody, pMaximumResponseBytes, false);
	}

	private Response sendBytes(final String pMethod, final String pUrl, final Map<String, String> pHeaders,
		final byte[] pBody, final int pMaximumResponseBytes, final boolean pFollowRedirects) throws IOException
	{
		if (pMaximumResponseBytes < 0) throw new IllegalArgumentException("Response byte limit cannot be negative");
		// audit C-16: a caller that gave no limit (the main path, send()) gets the default ceiling instead of an unbounded read
		final int limit = pMaximumResponseBytes == 0 ? HttpJsonClient.defaultMaxResponseBytes() : pMaximumResponseBytes;
		Response response = null;
		for (int attempt = 1; attempt <= this.retryPolicy.getMaxAttempts(); attempt++)
		{
			response = this.sendOnce(pMethod, pUrl, pHeaders, pBody, limit, pFollowRedirects);
			if (!this.retryPolicy.canRetry(pMethod, response.getStatus(), attempt)) return response;
			HttpExchangeStats.retried(response.getStatus(), response.getHeader("Retry-After"));
			try
			{
				this.retryPolicy.pause(attempt, response.getHeader("Retry-After"));
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				return response;
			}
		}
		return response;
	}

	private Response sendOnce(final String pMethod, final String pUrl, final Map<String, String> pHeaders,
		final byte[] pBody) throws IOException
	{
		return this.sendOnce(pMethod, pUrl, pHeaders, pBody, 0);
	}

	private Response sendOnce(final String pMethod, final String pUrl, final Map<String, String> pHeaders,
		final byte[] pBody, final int pMaximumResponseBytes) throws IOException
	{
		return this.sendOnce(pMethod, pUrl, pHeaders, pBody, pMaximumResponseBytes, true);
	}

	private Response sendOnce(final String pMethod, final String pUrl, final Map<String, String> pHeaders,
		final byte[] pBody, final int pMaximumResponseBytes, final boolean pFollowRedirects) throws IOException
	{
		String method = pMethod;
		URL url = new URL(pUrl);
		Map<String, String> headers = pHeaders;
		byte[] body = pBody;
		for (int hop = 0;; hop++)
		{
			final Response response = this.exchange(method, url, headers, body, pMaximumResponseBytes);
			final int status = response.getStatus();
			final String location = response.getHeader("Location");
			if (!pFollowRedirects || location == null || location.trim().isEmpty()
				|| !(status == 301 || status == 302 || status == 303 || status == 307 || status == 308))
			{
				return response;
			}
			final URL target;
			try
			{
				target = new URL(url, location.trim());
			}
			catch (final java.net.MalformedURLException ex)
			{
				return response;
			}
			if (!target.getProtocol().equalsIgnoreCase(url.getProtocol()))
			{
				// a change of scheme (a downgrade to clear text, or anything that is not HTTP) is handed
				// back to the caller as the redirect it is: following it is the caller's decision
				return response;
			}
			if (hop >= HttpJsonClient.MAX_REDIRECTS)
			{
				throw new IOException("more than " + HttpJsonClient.MAX_REDIRECTS + " redirects starting at "
					+ HttpJsonClient.origin(new URL(pUrl)) + ": not followed further");
			}
			if (!HttpJsonClient.origin(url).equals(HttpJsonClient.origin(target)))
			{
				headers = HttpJsonClient.safeAcrossOrigins(headers);
			}
			// 303 always, and 301/302 for a POST as every browser and the JDK do: the answer is fetched with GET
			if ((status == 303 && !"HEAD".equalsIgnoreCase(method))
				|| ((status == 301 || status == 302) && "POST".equalsIgnoreCase(method)))
			{
				method = "GET";
				body = null;
				headers = HttpJsonClient.withoutContentHeaders(headers);
			}
			url = target;
		}
	}

	/** {@code scheme://host:port} with the default port made explicit: what "the same server" means for a redirect. */
	private static String origin(final URL pUrl)
	{
		final int port = pUrl.getPort() < 0 ? pUrl.getDefaultPort() : pUrl.getPort();
		return pUrl.getProtocol().toLowerCase() + "://" + String.valueOf(pUrl.getHost()).toLowerCase() + ":" + port;
	}

	private static Map<String, String> safeAcrossOrigins(final Map<String, String> pHeaders)
	{
		final Map<String, String> kept = new LinkedHashMap<String, String>();
		if (pHeaders != null)
		{
			for (final Map.Entry<String, String> header : pHeaders.entrySet())
			{
				if (header.getKey() != null && HttpJsonClient.SAFE_ACROSS_ORIGINS.contains(header.getKey().toLowerCase()))
				{
					kept.put(header.getKey(), header.getValue());
				}
			}
		}
		return kept;
	}

	private static Map<String, String> withoutContentHeaders(final Map<String, String> pHeaders)
	{
		final Map<String, String> kept = new LinkedHashMap<String, String>();
		if (pHeaders != null)
		{
			for (final Map.Entry<String, String> header : pHeaders.entrySet())
			{
				if (header.getKey() != null && !header.getKey().toLowerCase().startsWith("content-"))
				{
					kept.put(header.getKey(), header.getValue());
				}
			}
		}
		return kept;
	}

	private void checkEgress(final URL pUrl) throws IOException
	{
		try
		{
			if (this.platformMetadata)
			{
				Egress.checkPlatformMetadata(pUrl.toExternalForm());
			}
			else
			{
				Egress.check(pUrl);
			}
		}
		catch (final IllegalStateException refused)
		{
			throw new IOException(refused.getMessage());
		}
	}

	/** One request, one answer: the connection never follows a redirect by itself. */
	private Response exchange(final String pMethod, final URL pUrl, final Map<String, String> pHeaders,
		final byte[] pBody, final int pMaximumResponseBytes) throws IOException
	{
		this.checkEgress(pUrl);
		final HttpURLConnection connection = (HttpURLConnection) pUrl.openConnection();
		try
		{
		if (this.sslContext != null && connection instanceof javax.net.ssl.HttpsURLConnection)
		{
			((javax.net.ssl.HttpsURLConnection) connection).setSSLSocketFactory(this.sslContext.getSocketFactory());
		}
		connection.setRequestMethod(pMethod);
		connection.setConnectTimeout(this.connectTimeoutMs);
		connection.setReadTimeout(this.readTimeoutMs);
		connection.setInstanceFollowRedirects(false);
		connection.setRequestProperty("User-Agent", this.userAgent);
		connection.setRequestProperty("Accept", "application/json");
		if (pHeaders != null)
		{
			for (final Map.Entry<String, String> header : pHeaders.entrySet())
			{
				if (header.getKey() != null && header.getValue() != null)
				{
					connection.setRequestProperty(header.getKey(), header.getValue());
				}
			}
		}
		if (pBody != null)
		{
			connection.setDoOutput(true);
			if (connection.getRequestProperty("Content-Type") == null)
			{
				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			}
			final OutputStream out = connection.getOutputStream();
			try
			{
				out.write(pBody);
			}
			finally
			{
				out.close();
			}
		}
		final int status = connection.getResponseCode();
		InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		final String text = stream == null ? "" : HttpJsonClient.readAll(stream, pMaximumResponseBytes);
		final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>(
			connection.getHeaderFields());
		return new Response(status, text, headers, pUrl.toExternalForm());
		}
		finally
		{
			connection.disconnect();
		}
	}

	/**
	 * A real PATCH through {@code java.net.http} (JDK 11+), reached by reflection so the module
	 * still compiles for Java 8; null when the runtime does not have it.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Response sendWithJavaNetHttp(final String pMethod, final String pUrl, final Map<String, String> pHeaders,
		final String pBody) throws IOException
	{
		// java.net.http never follows a redirect unless asked to, and this client does not ask
		this.checkEgress(new URL(pUrl));
		try
		{
			final Class<?> clientClass = Class.forName("java.net.http.HttpClient");
			/*
			 * Do not reflect on the concrete builder implementation returned by the
			 * JDK. On newer JDKs it is a non-public class, so invoking its methods
			 * through reflection fails with IllegalAccessException and silently
			 * falls back to HttpURLConnection (which refuses PATCH). Reflect on the
			 * public API interfaces instead.
			 */
			final Class<?> clientBuilderClass = Class.forName("java.net.http.HttpClient$Builder");
			final Class<?> requestClass = Class.forName("java.net.http.HttpRequest");
			final Class<?> requestBuilderClass = Class.forName("java.net.http.HttpRequest$Builder");
			final Class<?> publishersClass = Class.forName("java.net.http.HttpRequest$BodyPublishers");
			final Class<?> responseClass = Class.forName("java.net.http.HttpResponse");
			final Class<?> handlersClass = Class.forName("java.net.http.HttpResponse$BodyHandlers");
			final Class<?> headersClass = Class.forName("java.net.http.HttpHeaders");
			final Object clientBuilder = clientClass.getMethod("newBuilder").invoke(null);
			clientBuilderClass.getMethod("connectTimeout", java.time.Duration.class)
					.invoke(clientBuilder, java.time.Duration.ofMillis(this.connectTimeoutMs));
			if (this.sslContext != null)
			{
				clientBuilderClass.getMethod("sslContext", javax.net.ssl.SSLContext.class).invoke(clientBuilder, this.sslContext);
			}
			final Object client = clientBuilderClass.getMethod("build").invoke(clientBuilder);
			final Object requestBuilder = requestClass.getMethod("newBuilder", java.net.URI.class).invoke(null, java.net.URI.create(pUrl));
			requestBuilderClass.getMethod("timeout", java.time.Duration.class).invoke(requestBuilder, java.time.Duration.ofMillis(this.readTimeoutMs));
			requestBuilderClass.getMethod("header", String.class, String.class).invoke(requestBuilder, "User-Agent", this.userAgent);
			boolean hasContentType = false;
			if (pHeaders != null)
			{
				for (final Map.Entry<String, String> header : pHeaders.entrySet())
				{
					if (header.getKey() != null && header.getValue() != null)
					{
						requestBuilderClass.getMethod("header", String.class, String.class).invoke(requestBuilder, header.getKey(), header.getValue());
						hasContentType |= "content-type".equalsIgnoreCase(header.getKey());
					}
				}
			}
			if (!hasContentType)
			{
				requestBuilderClass.getMethod("header", String.class, String.class).invoke(requestBuilder, "Content-Type", "application/json; charset=UTF-8");
			}
			final Object publisher = publishersClass.getMethod("ofString", String.class).invoke(null, pBody == null ? "" : pBody);
			final Class<?> publisherClass = Class.forName("java.net.http.HttpRequest$BodyPublisher");
			requestBuilderClass.getMethod("method", String.class, publisherClass).invoke(requestBuilder, pMethod.toUpperCase(), publisher);
			final Object request = requestBuilderClass.getMethod("build").invoke(requestBuilder);
			final Object handler = handlersClass.getMethod("ofString").invoke(null);
			final Class<?> handlerClass = Class.forName("java.net.http.HttpResponse$BodyHandler");
			final Object response = clientClass.getMethod("send", requestClass, handlerClass).invoke(client, request, handler);
			// As for builders above, response and header instances are private JDK
			// implementations. Invoke through their public API interfaces so a valid
			// PATCH response is not discarded and retried through HttpURLConnection.
			final int status = ((Integer) responseClass.getMethod("statusCode").invoke(response)).intValue();
			final String text = (String) responseClass.getMethod("body").invoke(response);
			final Object headersObject = responseClass.getMethod("headers").invoke(response);
			final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>(
				(Map<String, List<String>>) headersClass.getMethod("map").invoke(headersObject));
			return new Response(status, text, headers);
		}
		catch (final ClassNotFoundException ex)
		{
			return null;
		}
		catch (final java.lang.reflect.InvocationTargetException ex)
		{
			final Throwable cause = ex.getCause();
			throw cause instanceof IOException ? (IOException) cause : new IOException(String.valueOf(cause), cause);
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	private static String readAll(final InputStream pStream) throws IOException
	{
		return readAll(pStream, 0);
	}

	private static String readAll(final InputStream pStream, final int pMaximumBytes) throws IOException
	{
		try
		{
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			final byte[] chunk = new byte[8192];
			int read = pStream.read(chunk);
			while (read >= 0)
			{
				if (pMaximumBytes > 0 && buffer.size() + read > pMaximumBytes)
					throw new IOException(HttpJsonClient.limitMessage(pMaximumBytes));
				buffer.write(chunk, 0, read);
				read = pStream.read(chunk);
			}
			return new String(buffer.toByteArray(), "UTF-8");
		}
		finally
		{
			pStream.close();
		}
	}

	public static String encode(final String pValue)
	{
		try
		{
			return URLEncoder.encode(pValue == null ? "" : pValue, "UTF-8").replace("+", "%20");
		}
		catch (final UnsupportedEncodingException ex)
		{
			return pValue;
		}
	}

	/** {@code base + path + ?k=v&...}; path segments are expected already encoded by the caller. */
	public static String buildUrl(final String pBase, final String pPath, final Map<String, String> pQuery)
	{
		final StringBuilder url = new StringBuilder();
		final String base = pBase == null ? "" : pBase.trim();
		url.append(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
		if (pPath != null && !pPath.isEmpty())
		{
			url.append(pPath.startsWith("/") ? pPath : "/" + pPath);
		}
		if (pQuery != null && !pQuery.isEmpty())
		{
			char separator = url.indexOf("?") >= 0 ? '&' : '?';
			for (final Map.Entry<String, String> parameter : pQuery.entrySet())
			{
				url.append(separator).append(HttpJsonClient.encode(parameter.getKey())).append('=')
						.append(HttpJsonClient.encode(parameter.getValue()));
				separator = '&';
			}
		}
		return url.toString();
	}
}
