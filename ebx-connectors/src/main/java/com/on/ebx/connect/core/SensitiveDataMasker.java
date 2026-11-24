package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-request masking helper: immutable, bounded, and free of global secret retention. */
public final class SensitiveDataMasker
{
	public static final String MASK = "***";
	private static final int MIN_SECRET_LENGTH = 4;
	private static final int MAX_SECRETS = 128;
	private static final Pattern SECRET_NAME = Pattern.compile(
		"(?i)(?:^|[._-])(?:api[_-]?key|password|passwd|pwd|secret|token|authorization|credential|private[_-]?key|signature|sas)(?:$|[._-])");
	private static final Pattern QUERY_VALUE = Pattern.compile(
		"(?i)([?&](?:api[_-]?key|key|password|passwd|pwd|secret|token|access[_-]?token|auth[_-]?token|client[_-]?secret|signature|sig|sas|code)=)[^&\\s\"']*");
	private static final Pattern HEADER_VALUE = Pattern.compile(
		"(?im)^((?:authorization|proxy-authorization|x-api-key|x-auth-token|x-vault-token|cookie|set-cookie)\\s*[:=]\\s*)(?:basic\\s+|bearer\\s+)?[^\\r\\n]*");

	private final List<String> secrets;

	private SensitiveDataMasker(final List<String> pSecrets)
	{
		this.secrets = pSecrets;
	}

	public static SensitiveDataMasker empty()
	{
		return new SensitiveDataMasker(Collections.<String>emptyList());
	}

	/** Creates a masker from values whose configuration keys identify them as sensitive. */
	public static SensitiveDataMasker fromConfiguration(final Map<String, String> pConfiguration)
	{
		if (pConfiguration == null || pConfiguration.isEmpty()) return empty();
		final List<String> values = new ArrayList<String>();
		for (final Map.Entry<String, String> item : pConfiguration.entrySet())
		{
			if (isSensitiveKey(item.getKey())) addSecret(values, item.getValue());
			if (values.size() >= MAX_SECRETS) break;
		}
		// Longest first avoids a shorter secret partially rewriting a longer one.
		Collections.sort(values, new Comparator<String>()
		{
			@Override public int compare(final String pLeft, final String pRight)
			{
				return Integer.compare(pRight.length(), pLeft.length());
			}
		});
		return new SensitiveDataMasker(Collections.unmodifiableList(values));
	}

	public String mask(final String pText)
	{
		if (pText == null || pText.isEmpty()) return pText;
		String safe = QUERY_VALUE.matcher(pText).replaceAll("$1" + MASK);
		safe = HEADER_VALUE.matcher(safe).replaceAll("$1" + MASK);
		for (final String secret : this.secrets) safe = safe.replace(secret, MASK);
		return safe;
	}

	/** Returns a new map; sensitive-key values are replaced rather than copied. */
	public Map<String, String> maskConfiguration(final Map<String, String> pConfiguration)
	{
		if (pConfiguration == null) return Collections.emptyMap();
		final Map<String, String> safe = new LinkedHashMap<String, String>();
		for (final Map.Entry<String, String> item : pConfiguration.entrySet())
			safe.put(item.getKey(), isSensitiveKey(item.getKey()) ? MASK : mask(item.getValue()));
		return Collections.unmodifiableMap(safe);
	}

	public static boolean isSensitiveKey(final String pKey)
	{
		if (pKey == null) return false;
		final String normalized = pKey.trim().replaceAll("([a-z0-9])([A-Z])", "$1_$2")
			.toLowerCase(Locale.ROOT);
		return SECRET_NAME.matcher(normalized).find() || normalized.endsWith("key")
			&& (normalized.contains("private") || normalized.contains("api"));
	}

	private static void addSecret(final List<String> pSecrets, final String pValue)
	{
		if (pValue == null) return;
		final String value = pValue.trim();
		if (value.length() < MIN_SECRET_LENGTH || "to_be_set".equalsIgnoreCase(value)) return;
		if (!pSecrets.contains(value)) pSecrets.add(value);
	}
}
