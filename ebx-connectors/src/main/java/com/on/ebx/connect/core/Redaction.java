package com.on.ebx.connect.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * Masks secrets in the texts the module keeps or shows (sync log messages, REST answers, workflow
 * outputs, kernel log): the values of the loaded connectors' secrets (API keys, passwords, client
 * secrets, refresh tokens, private keys) and the usual secret query parameters of URLs
 * ({@code key}, {@code api_key}, {@code token}, {@code access_token}, {@code signature}...), plus
 * echoed {@code Authorization} headers.
 */
public final class Redaction
{
	private static final Pattern QUERY_SECRETS = Pattern.compile(
		"(?i)([?&](?:key|api[_-]?key|apikey|token|access[_-]?token|auth[_-]?token|secret|client[_-]?secret|password|pwd|passwd|signature|sig|sas|code)=)[^&\\s\"']+");
	private static final Pattern AUTH_HEADERS = Pattern.compile("(?i)((?:Authorization|X-Api-Key|X-Vault-Token|X-INSEE-Api-Key-Integration|Ocp-Apim-Subscription-Key)[=: ]+(?:Basic|Bearer)?\\s*)[A-Za-z0-9._~+/=-]{8,}");
	/** Userinfo of a URL ({@code scheme://user:password@host}) and the {@code ;password=} / {@code ;pwd=} attributes of a JDBC URL. */
	private static final Pattern URL_USERINFO = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)[^/\\s@\"']+@");
	private static final Pattern JDBC_SECRETS = Pattern.compile("(?i)(;\\s*(?:password|pwd|passwd|accountkey|sharedaccesskey)=)[^;\\s\"']+");
	/** Insertion-ordered secrets (for eviction), touched only under its own monitor by the rare writers. */
	private static final Set<String> SECRETS = new LinkedHashSet<String>();
	/** Immutable snapshot read without any lock: secrets bucketed by first character, longest first. Replaced atomically on each change. */
	private static volatile Map<Character, String[]> index = Collections.emptyMap();
	private static final String MASK = "***";
	private static final int MAX_SECRETS = 4096;

	private Redaction()
	{
	}

	/** Remembers the secret values of a configuration so that they are masked wherever they appear. */
	public static void remember(final ConnectorConfig pConfig)
	{
		Redaction.add(pConfig.getApiKey());
		Redaction.add(pConfig.getPassword());
		if (pConfig.isSecretBaseUrl())
		{
			// the URL of a chat webhook IS the credential: the whole URL and its path are masked wherever they appear
			final String url = pConfig.getBaseUrl();
			Redaction.add(url);
			final int scheme = url == null ? -1 : url.indexOf("://");
			final int path = scheme < 0 ? -1 : url.indexOf('/', scheme + 3);
			if (path > 0)
			{
				Redaction.add(url.substring(path));
			}
		}
		for (final Map.Entry<String, String> property : pConfig.getProperties().entrySet())
		{
			if (Redaction.isSecretKey(property.getKey()))
			{
				Redaction.add(property.getValue());
			}
		}
	}

	/**
	 * The base URL of a connection as it may be shown to a reader: user information and secret query parameters masked,
	 * and for a type whose URL is the credential (chat webhooks) everything after the host.
	 */
	public static String displayUrl(final ConnectorConfig pConfig)
	{
		return pConfig.getDisplayBaseUrl();
	}

	/** Whether a configuration-property name denotes credential material. */
	public static boolean isSecretKey(final String pKey)
	{
		final String key = pKey == null ? "" : pKey.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
		return key.contains("secret") || key.contains("password") || key.contains("token") || key.contains("privatekey")
			|| key.contains("apikey") || key.endsWith(".key")
			// credentials that are not called a password: a key-file passphrase, a service-account JSON (it holds a private key),
			// a storage account key, a JAAS login configuration (it carries the password), a connection string
			|| key.contains("passphrase") || key.contains("serviceaccountjson") || key.contains("accountkey") || key.contains("jaas")
			|| key.contains("connectionstring") || key.contains("credential") || key.endsWith("pwd");
	}

	/** Masks secret properties by name, independent of value length or registration order. */
	public static String propertyValue(final String pKey, final String pValue)
	{
		return Redaction.isSecretKey(pKey) && pValue != null && !pValue.isEmpty() ? Redaction.MASK : Redaction.redact(pValue);
	}

	/** Registers a value obtained from a secret provider before it can reach an error or audit message. */
	static void rememberSecret(final String pValue)
	{
		Redaction.add(pValue);
	}

	private static void add(final String pValue)
	{
		if (pValue != null && pValue.trim().length() >= 6 && !Redaction.isPlaceholder(pValue))
		{
			synchronized (Redaction.SECRETS)
			{
				Redaction.SECRETS.remove(pValue.trim());
				Redaction.SECRETS.add(pValue.trim());
				while (Redaction.SECRETS.size() > MAX_SECRETS)
				{
					final java.util.Iterator<String> oldest = Redaction.SECRETS.iterator();
					oldest.next();
					oldest.remove();
				}
				// copy-on-write: readers never see a half-built index and never wait for this block
				final Map<Character, java.util.List<String>> buckets = new java.util.HashMap<Character, java.util.List<String>>();
				for (final String secret : Redaction.SECRETS)
				{
					java.util.List<String> bucket = buckets.get(Character.valueOf(secret.charAt(0)));
					if (bucket == null)
					{
						bucket = new java.util.ArrayList<String>();
						buckets.put(Character.valueOf(secret.charAt(0)), bucket);
					}
					bucket.add(secret);
				}
				final Map<Character, String[]> next = new java.util.HashMap<Character, String[]>();
				for (final Map.Entry<Character, java.util.List<String>> entry : buckets.entrySet())
				{
					final String[] values = entry.getValue().toArray(new String[0]);
					java.util.Arrays.sort(values, new java.util.Comparator<String>()
					{
						@Override
						public int compare(final String pA, final String pB)
						{
							return pB.length() - pA.length();
						}
					});
					next.put(entry.getKey(), values);
				}
				Redaction.index = Collections.unmodifiableMap(next);
			}
		}
	}

	/** Longest failure text shown to a person; a longer one is cut. */
	private static final int MAX_FAILURE = 1000;
	/** Longest stack trace written to the log. */
	private static final int MAX_STACK = 8000;

	/**
	 * What a person reads of a failure: the redacted message of the outermost throwable that has one, or the
	 * simple name of the exception when none has. A wrapper without a message, or whose message only repeats its
	 * cause's {@code toString()}, says nothing: its cause does. Never {@code toString()}, which prefixes a package
	 * name nobody asked for, and never a stack trace: that goes to the log through {@link #stack}.
	 */
	public static String failure(final Throwable pFailure)
	{
		if (pFailure == null)
		{
			return "";
		}
		Throwable current = pFailure;
		for (int depth = 0; current != null && depth < 10; depth++)
		{
			final String message = current.getMessage();
			// new RuntimeException(cause) writes cause.toString() as its own message: that says nothing the cause does not say better
			final boolean echoOfCause = message != null && current.getCause() != null && message.equals(current.getCause().toString());
			if (message != null && !message.trim().isEmpty() && !echoOfCause)
			{
				final String text = Redaction.redact(message.trim());
				return text.length() > Redaction.MAX_FAILURE ? text.substring(0, Redaction.MAX_FAILURE) + "..." : text;
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return pFailure.getClass().getSimpleName();
	}

	/**
	 * The stack trace of a failure for the kernel log, with the secrets and secret URL parameters its messages may
	 * quote masked: a stack printed as is would carry the unredacted message of every exception in the chain.
	 */
	public static String stack(final Throwable pFailure)
	{
		if (pFailure == null)
		{
			return "";
		}
		final java.io.StringWriter text = new java.io.StringWriter();
		pFailure.printStackTrace(new java.io.PrintWriter(text));
		final String redacted = Redaction.redact(text.toString());
		return redacted.length() > Redaction.MAX_STACK ? redacted.substring(0, Redaction.MAX_STACK) + "..." : redacted;
	}

	/** The text with secret values and secret URL parameters masked; null stays null. */
	public static String redact(final String pText)
	{
		if (pText == null || pText.isEmpty())
		{
			return pText;
		}
		String text = Redaction.QUERY_SECRETS.matcher(pText).replaceAll("$1" + Redaction.MASK);
		text = Redaction.AUTH_HEADERS.matcher(text).replaceAll("$1" + Redaction.MASK);
		text = Redaction.URL_USERINFO.matcher(text).replaceAll("$1" + Redaction.MASK + "@");
		text = Redaction.JDBC_SECRETS.matcher(text).replaceAll("$1" + Redaction.MASK);
		return Redaction.maskSecrets(text);
	}

	/** Masks every remembered secret in one pass over the text, longest match first, reading the immutable snapshot with no lock. */
	private static String maskSecrets(final String pText)
	{
		final Map<Character, String[]> snapshot = Redaction.index;
		if (snapshot.isEmpty())
		{
			return pText;
		}
		StringBuilder out = null;
		int copied = 0;
		final int length = pText.length();
		int i = 0;
		while (i < length)
		{
			final String[] candidates = snapshot.get(Character.valueOf(pText.charAt(i)));
			int matched = 0;
			if (candidates != null)
			{
				for (final String secret : candidates)
				{
					if (pText.startsWith(secret, i))
					{
						matched = secret.length();
						break;
					}
				}
			}
			if (matched > 0)
			{
				if (out == null)
				{
					out = new StringBuilder(length);
				}
				out.append(pText, copied, i).append(Redaction.MASK);
				i += matched;
				copied = i;
			}
			else
			{
				i++;
			}
		}
		if (out == null)
		{
			return pText;
		}
		return out.append(pText, copied, length).toString();
	}

	/**
	 * An empty value or a "to be set" marker, never a secret. Lives here rather than in {@link Secrets} (which calls it) so that
	 * the mapping engine, which redacts its log messages, does not pull the secret resolvers into on-ebx-mapping.jar.
	 */
	static boolean isPlaceholder(final String pValue)
	{
		final String v = pValue == null ? "" : pValue.trim();
		return v.isEmpty() || "TO_BE_SET".equals(v) || "A_RENSEIGNER".equals(v);
	}
}
