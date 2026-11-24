package com.on.ebx.connect.core;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.on.ebx.connect.spi.ConnectorConfig;

/**
 * TLS options of a connector, from its properties: {@code tls.keystore} (client certificate for
 * mutual TLS: a PKCS#12 or JKS file), {@code tls.keystorePassword}, {@code tls.keyPassword},
 * {@code tls.keystoreType}; {@code tls.truststore} (a private CA), {@code tls.truststorePassword},
 * {@code tls.truststoreType}. Passwords may be vault references. Without these properties the JVM
 * defaults apply (its truststore, its proxy settings); there is deliberately no "trust everything" option.
 */
public final class Tls
{
	private static final ConcurrentHashMap<String, SSLContext> CONTEXTS = new ConcurrentHashMap<String, SSLContext>();

	private Tls()
	{
	}

	/** The SSL context of a connector, or null when it uses the JVM defaults. */
	public static SSLContext contextFor(final ConnectorConfig pConfig)
	{
		final String keystore = pConfig.getProperty("tls.keystore", null);
		final String truststore = pConfig.getProperty("tls.truststore", null);
		if (Tls.blank(keystore) && Tls.blank(truststore))
		{
			return null;
		}
		final String key = Hashing.sha256(keystore + "|" + truststore + "|" + pConfig.getProperty("tls.keystorePassword", "") + "|"
				+ pConfig.getProperty("tls.truststorePassword", "") + "|" + pConfig.getProperty("tls.keyPassword", ""));
		final SSLContext cached = Tls.CONTEXTS.get(key);
		if (cached != null)
		{
			return cached;
		}
		try
		{
			KeyManagerFactory keyManagers = null;
			if (!Tls.blank(keystore))
			{
				final char[] storePassword = Tls.password(pConfig.getProperty("tls.keystorePassword", null));
				final String keyPassword = pConfig.getProperty("tls.keyPassword", null);
				final KeyStore store = Tls.load(keystore, pConfig.getProperty("tls.keystoreType", null), storePassword);
				keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
				keyManagers.init(store, keyPassword == null ? storePassword : Tls.password(keyPassword));
			}
			TrustManagerFactory trustManagers = null;
			if (!Tls.blank(truststore))
			{
				final KeyStore store = Tls.load(truststore, pConfig.getProperty("tls.truststoreType", null), Tls.password(pConfig.getProperty("tls.truststorePassword", null)));
				trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				trustManagers.init(store);
			}
			final SSLContext context = SSLContext.getInstance("TLS");
			context.init(keyManagers == null ? null : keyManagers.getKeyManagers(), trustManagers == null ? null : trustManagers.getTrustManagers(), null);
			Tls.CONTEXTS.put(key, context);
			return context;
		}
		catch (final Exception ex)
		{
			throw new IllegalStateException("TLS configuration of connector " + pConfig.getId() + " is invalid: " + ex.getMessage(), ex);
		}
	}

	private static KeyStore load(final String pPath, final String pType, final char[] pPassword) throws Exception
	{
		final String lower = pPath.toLowerCase();
		final String type = pType != null ? pType : lower.endsWith(".jks") ? "JKS" : "PKCS12";
		final KeyStore store = KeyStore.getInstance(type);
		final InputStream in = new FileInputStream(pPath.trim());
		try
		{
			store.load(in, pPassword);
		}
		finally
		{
			in.close();
		}
		return store;
	}

	private static char[] password(final String pValue)
	{
		final String resolved = Secrets.resolveIfReference(pValue);
		return resolved == null ? new char[0] : resolved.toCharArray();
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
