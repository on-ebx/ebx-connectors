package com.on.ebx.connect.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime configuration of one connector instance, as stored in the {@code Connector} table of the
 * {@code Connectors} dataset: endpoint, authentication, timeouts and free properties.
 * <p>
 * Secrets never leave this object: connectors read them to build requests and nothing logs them.
 */
public final class ConnectorConfig
{
	public enum AuthType
	{
		NONE, BASIC, API_KEY_HEADER, BEARER, OAUTH2
	}

	/**
	 * How the external service is billed. Data, not behavior: nothing in this module branches on it;
	 * it lets a consumer (e.g. an LLM enrichment-suggestion prompt) prefer a free source over a paid
	 * one when both fit. {@code UNKNOWN} also covers "not applicable" (a customer's own system).
	 */
	public enum Pricing
	{
		UNKNOWN, FREE, FREEMIUM, PAID
	}

	private String id;
	private String type;
	private String label;
	private String baseUrl;
	private AuthType authType = AuthType.NONE;
	private String username;
	private String password;
	private String apiKeyHeader;
	private String apiKey;
	private int connectTimeoutMs = 10000;
	private int readTimeoutMs = 30000;
	private int trust = 50;
	private Pricing pricing = Pricing.UNKNOWN;
	private String pricingNote;
	private boolean active = true;
	private final Map<String, String> properties = new LinkedHashMap<String, String>();

	public String getId()
	{
		return this.id;
	}

	public void setId(final String pId)
	{
		this.id = pId;
	}

	public String getType()
	{
		return this.type;
	}

	public void setType(final String pType)
	{
		this.type = pType;
	}

	public String getLabel()
	{
		return this.label;
	}

	public void setLabel(final String pLabel)
	{
		this.label = pLabel;
	}

	public String getBaseUrl()
	{
		return this.baseUrl;
	}

	public void setBaseUrl(final String pBaseUrl)
	{
		this.baseUrl = pBaseUrl;
	}

	public AuthType getAuthType()
	{
		return this.authType;
	}

	public void setAuthType(final AuthType pAuthType)
	{
		this.authType = pAuthType == null ? AuthType.NONE : pAuthType;
	}

	public String getUsername()
	{
		return this.username;
	}

	public void setUsername(final String pUsername)
	{
		this.username = pUsername;
	}

	public String getPassword()
	{
		return this.password;
	}

	public void setPassword(final String pPassword)
	{
		this.password = pPassword;
	}

	public String getApiKeyHeader()
	{
		return this.apiKeyHeader;
	}

	public void setApiKeyHeader(final String pApiKeyHeader)
	{
		this.apiKeyHeader = pApiKeyHeader;
	}

	public String getApiKey()
	{
		return this.apiKey;
	}

	public void setApiKey(final String pApiKey)
	{
		this.apiKey = pApiKey;
	}

	public int getConnectTimeoutMs()
	{
		return this.connectTimeoutMs;
	}

	public void setConnectTimeoutMs(final int pMs)
	{
		this.connectTimeoutMs = pMs > 0 ? pMs : 10000;
	}

	public int getReadTimeoutMs()
	{
		return this.readTimeoutMs;
	}

	public void setReadTimeoutMs(final int pMs)
	{
		this.readTimeoutMs = pMs > 0 ? pMs : 30000;
	}

	public boolean isActive()
	{
		return this.active;
	}

	public void setActive(final boolean pActive)
	{
		this.active = pActive;
	}

	public Map<String, String> getProperties()
	{
		return Collections.unmodifiableMap(this.properties);
	}

	public void setProperty(final String pKey, final String pValue)
	{
		if (pKey != null)
		{
			this.properties.put(pKey, pValue);
		}
	}

	public String getProperty(final String pKey, final String pDefault)
	{
		final String value = this.properties.get(pKey);
		return value == null || value.trim().isEmpty() ? pDefault : value.trim();
	}

	/** True when a secret is still the placeholder written by the seed, i.e. not configured. */
	/** Reliability of this source, 0-100 (manual entries count as 100); default 50. */
	private String logo;
	/** Why the configuration is unusable (a vault reference that does not resolve, a refused clear secret), or null. */
	private String problem;

	public String getProblem()
	{
		return this.problem;
	}

	public void setProblem(final String pProblem)
	{
		this.problem = this.problem == null ? pProblem : this.problem + "; " + pProblem;
	}

	/** Resource of the image shown with the connector (module:image:path), may be null. */
	public String getLogo()
	{
		return this.logo;
	}

	public void setLogo(final String pLogo)
	{
		this.logo = pLogo;
	}

	/** Uploaded CSV/TSV/JSON/NDJSON sample, as a {@code data:<mime>;base64,<payload>} URL; may be null. */
	private String sampleData;

	public String getSampleData()
	{
		return this.sampleData;
	}

	public void setSampleData(final String pSampleData)
	{
		this.sampleData = pSampleData;
	}

	public int getTrust()
	{
		return this.trust;
	}

	public void setTrust(final int pTrust)
	{
		this.trust = pTrust < 0 ? 0 : pTrust > 100 ? 100 : pTrust;
	}

	public Pricing getPricing()
	{
		return this.pricing;
	}

	public void setPricing(final Pricing pPricing)
	{
		this.pricing = pPricing == null ? Pricing.UNKNOWN : pPricing;
	}

	/** Free-text nuance ("free up to 1 request/second", "licence per seat"), or null. */
	public String getPricingNote()
	{
		return this.pricingNote;
	}

	public void setPricingNote(final String pPricingNote)
	{
		this.pricingNote = pPricingNote;
	}

	public boolean isPlaceholder(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() || "A_RENSEIGNER".equals(pValue.trim())
				|| "TO_BE_SET".equals(pValue.trim());
	}

	@Override
	public String toString()
	{
		return this.id + " [" + this.type + "] " + this.getDisplayBaseUrl();
	}

	/** Types whose base URL is itself the credential (an incoming webhook URL carries its token in the path). */
	public boolean isSecretBaseUrl()
	{
		return "chat-webhook".equals(this.type);
	}

	/**
	 * The base URL as it may be shown to a reader or written to a log: {@code user:password@} and secret query
	 * parameters masked; for a type whose URL is the credential ({@link #isSecretBaseUrl()}) only scheme and host stay.
	 */
	public String getDisplayBaseUrl()
	{
		if (this.baseUrl == null)
		{
			return null;
		}
		if (this.isSecretBaseUrl())
		{
			final int scheme = this.baseUrl.indexOf("://");
			final int path = scheme < 0 ? -1 : this.baseUrl.indexOf('/', scheme + 3);
			final String origin = path < 0 ? this.baseUrl : this.baseUrl.substring(0, path);
			return (scheme < 0 ? "***" : origin.replaceAll("://[^/@]*@", "://***@")) + (path < 0 ? "" : "/***");
		}
		return this.baseUrl.replaceAll("://[^/\\s@]+@", "://***@")
			.replaceAll("(?i)([?&](?:key|api[_-]?key|apikey|token|access[_-]?token|secret|client[_-]?secret|password|pwd|signature|sig|sas|code)=)[^&\\s]+", "$1***")
			.replaceAll("(?i)(;\\s*(?:password|pwd)=)[^;\\s]+", "$1***");
	}

	/** The two values that can be megabytes (the logo, an uploaded sample) are summarised by their digest in {@link #fingerprint()}. */
	private static final int DIGEST_ABOVE = 256;
	/** {source, digest} of the logo and of the sample: valid while the source is the very string now held (compared by identity). */
	private volatile String[] logoMemo;
	private volatile String[] sampleMemo;

	/**
	 * Every configured value in one string, so that a cached connector can detect that its
	 * configuration changed in EBX (key entered, endpoint or property edited) and reconfigure itself.
	 * The logo and the uploaded sample, which can be megabytes, enter it as a SHA-256 digest computed once per value
	 * of this object instead of being copied into a new string at every call (audit C-34).
	 */
	public String fingerprint()
	{
		final StringBuilder b = new StringBuilder();
		b.append(this.id).append('\u0001').append(this.type).append('\u0001').append(this.label).append('\u0001')
			.append(this.baseUrl).append('\u0001').append(this.authType).append('\u0001').append(this.username)
			.append('\u0001').append(this.password).append('\u0001').append(this.apiKeyHeader).append('\u0001')
			.append(this.apiKey).append('\u0001').append(this.connectTimeoutMs).append('\u0001')
			.append(this.readTimeoutMs).append('\u0001').append(this.trust).append('\u0001').append(this.active)
			.append('\u0001').append(this.logoSummary()).append('\u0001').append(this.pricing).append('\u0001')
			.append(this.pricingNote).append('\u0001').append(this.sampleSummary());
		for (final Map.Entry<String, String> e : this.properties.entrySet())
		{
			b.append('\u0002').append(e.getKey()).append('=').append(e.getValue());
		}
		return b.toString();
	}

	private String logoSummary()
	{
		if (this.logo == null || this.logo.length() <= ConnectorConfig.DIGEST_ABOVE)
		{
			return this.logo;
		}
		String[] memo = this.logoMemo;
		if (memo == null || memo[0] != this.logo)
		{
			memo = new String[] { this.logo, ConnectorConfig.digestOf(this.logo) };
			this.logoMemo = memo;
		}
		return memo[1];
	}

	private String sampleSummary()
	{
		if (this.sampleData == null || this.sampleData.length() <= ConnectorConfig.DIGEST_ABOVE)
		{
			return this.sampleData;
		}
		String[] memo = this.sampleMemo;
		if (memo == null || memo[0] != this.sampleData)
		{
			memo = new String[] { this.sampleData, ConnectorConfig.digestOf(this.sampleData) };
			this.sampleMemo = memo;
		}
		return memo[1];
	}

	private static String digestOf(final String pValue)
	{
		try
		{
			final byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(pValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder(hash.length * 2 + 8).append("sha256:");
			for (final byte value : hash)
			{
				hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
			}
			return hex.toString();
		}
		catch (final java.security.NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Whether {@code pOther} holds exactly the configuration {@code this} holds, value by value. What a cached connector
	 * asks at every access to know that its configuration did not change: no string is built, and a large sample is
	 * compared as it is (one pass, no copy) instead of being concatenated into a fingerprint twice (audit C-34).
	 */
	public boolean sameConfigurationAs(final ConnectorConfig pOther)
	{
		return pOther != null && ConnectorConfig.same(this.id, pOther.id) && ConnectorConfig.same(this.type, pOther.type)
			&& ConnectorConfig.same(this.label, pOther.label) && ConnectorConfig.same(this.baseUrl, pOther.baseUrl)
			&& this.authType == pOther.authType && ConnectorConfig.same(this.username, pOther.username)
			&& ConnectorConfig.same(this.password, pOther.password) && ConnectorConfig.same(this.apiKeyHeader, pOther.apiKeyHeader)
			&& ConnectorConfig.same(this.apiKey, pOther.apiKey) && this.connectTimeoutMs == pOther.connectTimeoutMs
			&& this.readTimeoutMs == pOther.readTimeoutMs && this.trust == pOther.trust && this.active == pOther.active
			&& this.pricing == pOther.pricing && ConnectorConfig.same(this.pricingNote, pOther.pricingNote)
			&& ConnectorConfig.same(this.logo, pOther.logo) && ConnectorConfig.same(this.sampleData, pOther.sampleData)
			&& this.properties.equals(pOther.properties);
	}

	private static boolean same(final String pA, final String pB)
	{
		return pA == pB || pA != null && pA.equals(pB);
	}
}
