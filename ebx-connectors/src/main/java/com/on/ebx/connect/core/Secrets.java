package com.on.ebx.connect.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orchestranetworks.service.LoggingCategory;

/**
 * Secrets of the Connector table (API key, password) and secret references in properties.
 * <p>
 * The {@code osd:password} fields are declared with EBX's reversible encryption
 * ({@code com.orchestranetworks.security.PasswordTypeEncryption}, repository key), so a value
 * entered in the UI is decrypted here. The clear value may itself be a reference
 * ({@code vault:secret/data/ebx/sirene#apiKey}, {@code env:SIRENE_KEY}, {@code file:/run/secrets/sirene}):
 * it is then resolved through the {@link com.on.ebx.connect.spi.SecretResolver} of its scheme.
 * A value that neither decrypts nor is a reference (a key written in clear through Data Services or an
 * import) is REFUSED by default (audit decision 1, 2026-10-06): the system property
 * {@code ebx.connectors.secrets.allowClear=true} accepts it again, with one kernel warning. The seeded
 * placeholders ({@code TO_BE_SET}) are not secrets and are returned as they are.
 */
public final class Secrets
{
	private static final Pattern REFERENCE = Pattern.compile("^([a-z][a-z0-9+.-]*):(.+)$", Pattern.DOTALL);
	/** The JVM property that accepts secrets stored in clear again. Default: not accepted. */
	public static final String ALLOW_CLEAR_PROPERTY = "ebx.connectors.secrets.allowClear";
	/** A property whose name has one of these words holds a URL, a header name, a type or the like, not a credential. */
	private static final java.util.Set<String> NOT_SECRET_WORDS = new java.util.HashSet<String>(java.util.Arrays.asList("url", "uri", "endpoint",
		"header", "name", "type", "scope", "expires", "expiry", "ttl", "path", "file", "enabled", "protocol", "location", "mechanism", "provider", "field",
		"audience", "algorithm", "flow", "cache", "query", "max", "param", "params", "routing", "prefix", "style", "seconds"));
	private static final java.util.concurrent.atomic.AtomicBoolean WARNED_CLEAR = new java.util.concurrent.atomic.AtomicBoolean(false);

	private Secrets()
	{
	}

	/** The usable secret behind a stored value; throws when a reference cannot be resolved (or clear values are refused). */
	public static String reveal(final String pStored)
	{
		if (pStored == null || pStored.isEmpty())
		{
			return pStored;
		}
		String value = pStored;
		boolean decrypted = false;
		try
		{
			final String clear = com.orchestranetworks.security.PasswordTypeEncryption.decrypt(pStored);
			if (clear != null && !clear.isEmpty())
			{
				value = clear;
				decrypted = true;
			}
		}
		catch (final Exception ex)
		{
			// not encrypted: placeholder, or a value written in clear
		}
		catch (final LinkageError ex)
		{
			// EBX encryption not available (unit test): the value is used as is
		}
		if (Secrets.isReference(value))
		{
			return Secrets.resolve(value);
		}
		if (!decrypted && !Secrets.isPlaceholder(value))
		{
			if (!Secrets.allowClear())
			{
				// the secret itself is never part of the message
				throw new IllegalStateException(Secrets.CLEAR_REFUSED);
			}
			// Accepted only by an explicit -Debx.connectors.secrets.allowClear=true: logged once so it stays visible.
			if (Secrets.WARNED_CLEAR.compareAndSet(false, true))
			{
				LoggingCategory.getKernel().warn("[connectors] a connector secret is stored in clear text (not EBX's reversible"
						+ " encryption, not a vault:/env:/file: reference) and is accepted because -D" + Secrets.ALLOW_CLEAR_PROPERTY
						+ "=true is set. Re-enter it in the EBX user interface or use a reference, then remove that property.");
			}
		}
		return value;
	}

	/** The message of a refused clear secret: what is wrong and how the administrator fixes it. Contains no secret. */
	public static final String CLEAR_REFUSED = "a secret must be entered in the EBX user interface (so that EBX stores it encrypted) or referenced from a"
			+ " vault:, env: or file: reference: a value stored in clear is refused because " + Secrets.ALLOW_CLEAR_PROPERTY
			+ " is not true. Re-enter the password or API key in the Connector form, or replace it with a reference";

	/**
	 * Whether a stored value is a secret held in clear: neither EBX-encrypted, nor a reference, nor a placeholder.
	 * Same test as {@link #reveal}, without resolving anything and without ever returning the value.
	 */
	public static boolean isStoredInClear(final String pStored)
	{
		if (pStored == null || pStored.isEmpty() || Secrets.isPlaceholder(pStored))
		{
			return false;
		}
		try
		{
			final String clear = com.orchestranetworks.security.PasswordTypeEncryption.decrypt(pStored);
			if (clear != null && !clear.isEmpty())
			{
				return false;
			}
		}
		catch (final Exception ex)
		{
			// not encrypted
		}
		catch (final LinkageError ex)
		{
			// EBX encryption not available (unit test)
		}
		return !Secrets.isReference(pStored);
	}

	/**
	 * Whether a {@code ConnectorProperty} key names credential material that must not be stored in clear: the keys
	 * {@link Redaction#isSecretKey} masks, minus those that hold a URL, header, name, type, scope, path or the like.
	 */
	public static boolean isSecretProperty(final String pKey)
	{
		if (!Redaction.isSecretKey(pKey))
		{
			return false;
		}
		// the words of the key (dots, dashes, underscores and camelCase): oauth.tokenUrl = oauth, token, url
		for (final String word : pKey.replaceAll("([a-z0-9])([A-Z])", "$1.$2").toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+"))
		{
			if (Secrets.NOT_SECRET_WORDS.contains(word))
			{
				return false;
			}
		}
		return true;
	}

	/** A property value: resolved when it is a reference of a registered scheme, returned as is otherwise. */
	public static String resolveIfReference(final String pValue)
	{
		return Secrets.isReference(pValue) ? Secrets.resolve(pValue) : pValue;
	}

	/** True when the value is {@code <scheme>:<reference>} with a scheme handled by a registered resolver. */
	public static boolean isReference(final String pValue)
	{
		if (pValue == null)
		{
			return false;
		}
		final Matcher m = Secrets.REFERENCE.matcher(pValue.trim());
		return m.matches() && SecretResolvers.get().handles(m.group(1));
	}

	private static String resolve(final String pReference)
	{
		final Matcher m = Secrets.REFERENCE.matcher(pReference.trim());
		m.matches();
		return SecretResolvers.get().resolve(m.group(1), m.group(2));
	}

	static boolean isPlaceholder(final String pValue)
	{
		return Redaction.isPlaceholder(pValue);
	}

	/** True when a secret may be stored in clear in the configuration ({@code ebx.connectors.secrets.allowClear=true}; off by default). */
	public static boolean clearSecretsAllowed()
	{
		return Secrets.allowClear();
	}

	private static boolean allowClear()
	{
		return "true".equalsIgnoreCase(String.valueOf(System.getProperty(Secrets.ALLOW_CLEAR_PROPERTY)).trim());
	}
}
