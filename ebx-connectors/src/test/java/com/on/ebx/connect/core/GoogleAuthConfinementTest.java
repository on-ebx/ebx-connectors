package com.on.ebx.connect.core;

import java.nio.file.Files;
import java.nio.file.Path;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Audit C2-08 (remainder): {@code serviceAccountFile} is read under the same confinement as a {@code file:} secret reference. A path
 * outside the directory of {@code -Debx.connectors.secrets.dir}, or any path while no directory is declared, is refused with a message
 * that says what to set; no read reaches the Google token endpoint.
 */
public final class GoogleAuthConfinementTest
{
	private GoogleAuthConfinementTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final String previous = System.getProperty(SecretResolvers.SECRETS_DIR_PROPERTY);
		final Path work = Files.createTempDirectory("ebx-google-auth").toRealPath();
		try
		{
			final Path secrets = Files.createDirectories(work.resolve("secrets"));
			Files.write(secrets.resolve("sa.json"), "not a service account key".getBytes("UTF-8"));
			final Path outside = work.resolve("key.json");
			Files.write(outside, "not a service account key".getBytes("UTF-8"));

			// no directory declared: refused, whatever the path
			System.clearProperty(SecretResolvers.SECRETS_DIR_PROPERTY);
			refused(outside.toString(), SecretResolvers.SECRETS_DIR_PROPERTY);
			refused(secrets.resolve("sa.json").toString(), SecretResolvers.SECRETS_DIR_PROPERTY);

			// directory declared: a file outside is refused, a traversal is refused
			System.setProperty(SecretResolvers.SECRETS_DIR_PROPERTY, secrets.toString());
			refused(outside.toString(), "secrets directory");
			refused("../key.json", "secrets directory");
			// a file inside is READ (it is not a key: the failure is the key's, not the confinement's)
			final String inside = failureOf(secrets.resolve("sa.json").toString());
			check(!inside.contains("serviceAccountFile is refused"), "a file inside the directory passes the confinement: " + inside);
			final String relative = failureOf("sa.json");
			check(!relative.contains("serviceAccountFile is refused"), "a relative name passes the confinement: " + relative);
		}
		finally
		{
			if (previous == null)
			{
				System.clearProperty(SecretResolvers.SECRETS_DIR_PROPERTY);
			}
			else
			{
				System.setProperty(SecretResolvers.SECRETS_DIR_PROPERTY, previous);
			}
			SecretResolvers.get().invalidate();
		}
		System.out.println("PASS: C2-08 serviceAccountFile stays inside the secrets directory");
	}

	private static String failureOf(final String pFile)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setProperty("serviceAccountFile", pFile);
		try
		{
			new GoogleAuth().token(config, "https://www.googleapis.com/auth/cloud-platform");
			throw new AssertionError("no token can be issued from a fake key");
		}
		catch (final ConnectorException ex)
		{
			return String.valueOf(ex.getMessage());
		}
	}

	private static void refused(final String pFile, final String pMustName)
	{
		final String message = failureOf(pFile);
		check(message.contains("serviceAccountFile is refused") && message.contains(pMustName), "refused and says what to set (" + pFile + "): " + message);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
