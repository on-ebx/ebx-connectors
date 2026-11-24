package com.on.ebx.connect.core;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free regression checks for bounded preview, cache and masking foundations. */
public final class SafetyFoundationsTest
{
	private SafetyFoundationsTest() { }

	public static void main(final String[] pArgs)
	{
		previewIsBoundedAndMasked();
		metadataCacheExpiresAndUsesOpaqueKeys();
		configurationAndTransportSecretsAreMasked();
		redactionRetentionIsBounded();
		System.out.println("PASS: preview, metadata cache and secret-masking safety foundations");
	}

	private static void redactionRetentionIsBounded()
	{
		final com.on.ebx.connect.spi.ConnectorConfig config = new com.on.ebx.connect.spi.ConnectorConfig();
		for (int i = 0; i < 4200; i++)
		{
			config.setApiKey("rotated-secret-" + i + "-end");
			Redaction.remember(config);
		}
		check("rotated-secret-0-end".equals(Redaction.redact("rotated-secret-0-end")), "Old secret never evicted");
		check("***".equals(Redaction.redact("rotated-secret-4199-end")), "Latest secret not masked");
		check(!Redaction.redact("https://example.test?api_key=sensitive-value").contains("sensitive-value"), "Query credential leaked");
		check("***".equals(Redaction.propertyValue("clientSecret", "abc")), "Short secret property leaked");
		check("***".equals(Redaction.propertyValue("oauth.refresh_token", "x")), "Underscored token property leaked");
		check("visible".equals(Redaction.propertyValue("tenant", "visible")), "Non-secret property was masked");
	}

	private static void previewIsBoundedAndMasked()
	{
		final Map<String, String> config = new LinkedHashMap<String, String>();
		config.put("apiKey", "super-secret-token");
		final SensitiveDataMasker masker = SensitiveDataMasker.fromConfiguration(config);
		final PreviewRequest request = PreviewRequest.bounded(2, Long.MAX_VALUE, 40);
		check(request.getTimeoutMillis() == PreviewRequest.MAX_TIMEOUT_MILLIS, "timeout was not capped");
		final PreviewResult result = PreviewResult.collect(Arrays.asList(
			"one=super-secret-token", "two=public", "three=public"), request, masker);
		check(result.getRecords().size() == 2, "record ceiling was not enforced");
		check(result.isTruncated(), "truncation was not reported");
		check(!result.getRecords().toString().contains("super-secret-token"), "preview retained a secret");
		try
		{
			result.getRecords().add("unsafe");
			throw new AssertionError("preview records are mutable");
		}
		catch (final UnsupportedOperationException expected) { }
	}

	private static void metadataCacheExpiresAndUsesOpaqueKeys()
	{
		final AtomicLong now = new AtomicLong(1000L);
		final MetadataCache<String> cache = new MetadataCache<String>(100L, now::get);
		final String sensitiveScope = "https://user:secret@example.test/path?token=private";
		final MetadataCacheKey key = MetadataCacheKey.of("REST", "catalog", sensitiveScope);
		check(!key.toString().contains("secret") && !key.toString().contains("example"), "cache key exposed its scope");
		cache.put(key, "metadata");
		check("metadata".equals(cache.get(key)), "fresh cache entry was not returned");
		now.addAndGet(100L);
		check(cache.get(key) == null, "expired cache entry was returned");
		check(cache.size() == 0, "expired cache entry was not removed");
		final MetadataCache<String> capped = new MetadataCache<String>(Long.MAX_VALUE, now::get);
		check(capped.getTtlMillis() == MetadataCache.MAX_TTL_MILLIS, "cache TTL was not capped");
	}

	private static void configurationAndTransportSecretsAreMasked()
	{
		final Map<String, String> config = new LinkedHashMap<String, String>();
		config.put("oauth.clientSecret", "client-secret-value");
		config.put("password", "password-value");
		config.put("endpoint", "https://example.test?token=query-token");
		final SensitiveDataMasker masker = SensitiveDataMasker.fromConfiguration(config);
		final String safe = masker.mask("Authorization: Bearer header-token\nvalue=client-secret-value&api_key=query-token");
		check(!safe.contains("header-token") && !safe.contains("client-secret-value") && !safe.contains("query-token"),
			"transport or configured secret was not masked");
		final Map<String, String> masked = masker.maskConfiguration(config);
		check(SensitiveDataMasker.MASK.equals(masked.get("oauth.clientSecret")), "client secret key was not masked");
		check(SensitiveDataMasker.MASK.equals(masked.get("password")), "password key was not masked");
		check(!masked.get("endpoint").contains("query-token"), "secret URL parameter was not masked");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
