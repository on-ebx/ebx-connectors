package com.on.ebx.connect.core;

/**
 * Audit decision 1 (2026-10-06): a secret stored in clear is refused by default, with a message that tells the
 * administrator how to fix it and never contains the secret; {@code ebx.connectors.secrets.allowClear=true} accepts it
 * again; placeholders and references are not clear secrets; the credential property names are told from the others.
 */
public final class SecretsDefaultsTest
{
	private SecretsDefaultsTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String saved = System.getProperty(Secrets.ALLOW_CLEAR_PROPERTY);
		try
		{
			System.clearProperty(Secrets.ALLOW_CLEAR_PROPERTY);
			refusedByDefault();
			optIn();
			classification();
			propertyNames();
			propertyMessage();
		}
		finally
		{
			if (saved == null)
			{
				System.clearProperty(Secrets.ALLOW_CLEAR_PROPERTY);
			}
			else
			{
				System.setProperty(Secrets.ALLOW_CLEAR_PROPERTY, saved);
			}
		}
		System.out.println("PASS: clear secrets refused by default with a fix-it message and no secret, opt-in accepts, placeholders and references are not clear");
	}

	private static void refusedByDefault()
	{
		check(!Secrets.clearSecretsAllowed(), "clear secrets are not allowed when nothing is set");
		final String secret = "s3cr3t-Value-4711";
		try
		{
			Secrets.reveal(secret);
			throw new AssertionError("a clear secret was accepted by default");
		}
		catch (final IllegalStateException expected)
		{
			final String message = expected.getMessage();
			check(!message.contains(secret), "the refusal contains the secret");
			check(message.contains("EBX user interface") && message.contains("vault:") && message.contains("env:") && message.contains("file:")
				&& message.contains(Secrets.ALLOW_CLEAR_PROPERTY), "the refusal tells the administrator how to fix it: " + message);
		}
		check("TO_BE_SET".equals(Secrets.reveal("TO_BE_SET")) && "A_RENSEIGNER".equals(Secrets.reveal("A_RENSEIGNER")), "placeholders are not secrets");
		check(Secrets.reveal(null) == null && "".equals(Secrets.reveal("")), "no value, nothing to refuse");
		System.setProperty(Secrets.ALLOW_CLEAR_PROPERTY, "false");
		check(!Secrets.clearSecretsAllowed(), "an explicit false keeps refusing");
		System.setProperty(Secrets.ALLOW_CLEAR_PROPERTY, "");
		check(!Secrets.clearSecretsAllowed(), "an empty value is not an opt-in");
	}

	private static void optIn()
	{
		System.setProperty(Secrets.ALLOW_CLEAR_PROPERTY, " TRUE ");
		check(Secrets.clearSecretsAllowed(), "true accepts clear secrets");
		check("plain-secret".equals(Secrets.reveal("plain-secret")), "the clear value is returned when accepted");
		System.clearProperty(Secrets.ALLOW_CLEAR_PROPERTY);
	}

	private static void classification()
	{
		check(Secrets.isStoredInClear("plain-secret"), "a plain value is a clear secret");
		check(!Secrets.isStoredInClear(null) && !Secrets.isStoredInClear("") && !Secrets.isStoredInClear("TO_BE_SET") && !Secrets.isStoredInClear("A_RENSEIGNER"),
			"empty and placeholders are not");
		check(!Secrets.isStoredInClear("env:SOME_VARIABLE") && !Secrets.isStoredInClear("file:/run/secrets/x") && !Secrets.isStoredInClear("vault:secret/data/x#k"),
			"references are not");
	}

	private static void propertyNames()
	{
		for (final String key : new String[] { "oauth.clientSecret", "oauth.refreshToken", "oauth.privateKey", "privateKey", "refreshToken", "securityToken",
			"sessionToken", "serviceAccountJson", "tls.keyPassword", "tls.keystorePassword", "tls.truststorePassword", "wsse.password", "sasl.jaas.config",
			"azure.accountKey", "connectionString" })
		{
			check(Secrets.isSecretProperty(key), key + " names a secret");
		}
		for (final String key : new String[] { "oauth.tokenUrl", "oauth.tokenField", "oauth.tokenHeader", "oauth.tokenPrefix", "token.ttl", "tokenType", "maxTokens",
			"openai.maxTokensParam", "auth.apiKeyQuery", "routing.key", "wsse.password.type", "ebx.connectors.secrets.cacheSeconds", "oauth.scope", "baseUrl",
			"strictHostKey", "queueUrl", "endpoint" })
		{
			check(!Secrets.isSecretProperty(key), key + " does not name a secret");
		}
		check(!Secrets.isSecretProperty(null) && !Secrets.isSecretProperty(""), "no key, no secret");
	}

	private static void propertyMessage()
	{
		final String message = ClearSecrets.propertyMessage("oauth.clientSecret");
		check(message.contains("oauth.clientSecret") && message.contains("vault:") && message.contains("API key") && message.contains(Secrets.ALLOW_CLEAR_PROPERTY),
			"the property refusal names the property and the fix: " + message);
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass)
		{
			throw new AssertionError(pMessage);
		}
	}
}
