package com.on.ebx.connect.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reach of the two local secret schemes: {@code file:} stays inside the secrets directory once
 * one is set (traversal and links included), {@code env:} reads environment variables, and system
 * properties only when the JVM-level list names them.
 */
public final class SecretReferenceConfinementTest
{
	private SecretReferenceConfinementTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String previousDir = System.getProperty(SecretResolvers.SECRETS_DIR_PROPERTY);
		final String previousEnv = System.getProperty(SecretResolvers.ENV_ALLOWED_PROPERTY);
		final Path work = Files.createTempDirectory("ebx-connectors-secrets-test").toRealPath();
		try
		{
			SecretReferenceConfinementTest.files(work);
			SecretReferenceConfinementTest.environment();
		}
		finally
		{
			SecretReferenceConfinementTest.restore(SecretResolvers.SECRETS_DIR_PROPERTY, previousDir);
			SecretReferenceConfinementTest.restore(SecretResolvers.ENV_ALLOWED_PROPERTY, previousEnv);
			System.clearProperty("ebx.connectors.test.fakeSecret");
			SecretResolvers.get().invalidate();
			SecretReferenceConfinementTest.delete(work.toFile());
		}
		System.out.println("PASS: file: references stay in the secrets directory, env: reads system properties only when listed");
	}

	private static void files(final Path pWork) throws Exception
	{
		final Path secrets = Files.createDirectories(pWork.resolve("secrets"));
		final Path sibling = Files.createDirectories(pWork.resolve("secrets-other"));
		Files.createDirectories(secrets.resolve("sub"));
		Files.write(secrets.resolve("sirene"), "  inside-secret-value \n".getBytes("UTF-8"));
		Files.write(secrets.resolve("sub").resolve("key"), "nested-secret-value".getBytes("UTF-8"));
		Files.write(pWork.resolve("ebx.properties"), "ebx.persistence.password=outside-secret-value".getBytes("UTF-8"));
		Files.write(sibling.resolve("token"), "sibling-secret-value".getBytes("UTF-8"));
		boolean linked = true;
		try
		{
			Files.createSymbolicLink(secrets.resolve("link"), pWork.resolve("ebx.properties"));
		}
		catch (final Exception ex)
		{
			linked = false; // a file system without symbolic links: that case cannot be shown here
		}
		final String outside = pWork.resolve("ebx.properties").toString();

		// no directory set: refused (C2-08), the refusal names the setting, whether the file exists or not
		System.clearProperty(SecretResolvers.SECRETS_DIR_PROPERTY);
		SecretReferenceConfinementTest.refused("file", outside, SecretResolvers.SECRETS_DIR_PROPERTY);
		SecretReferenceConfinementTest.refused("file", secrets.resolve("sirene").toString(), SecretResolvers.SECRETS_DIR_PROPERTY);
		SecretReferenceConfinementTest.refused("file", pWork.resolve("absent").toString(), SecretResolvers.SECRETS_DIR_PROPERTY);
		SecretReferenceConfinementTest.check(SecretResolvers.referenceRefusal("file:" + outside) != null, "the status route does not flag a refused file: reference");

		System.setProperty(SecretResolvers.SECRETS_DIR_PROPERTY, secrets.toString());
		SecretReferenceConfinementTest.resolves("file", secrets.resolve("sirene").toString(), "inside-secret-value");
		SecretReferenceConfinementTest.resolves("file", "sirene", "inside-secret-value");
		SecretReferenceConfinementTest.resolves("file", "sub/key", "nested-secret-value");
		SecretReferenceConfinementTest.check("inside-secret-value".equals(Secrets.resolveIfReference("file:" + secrets.resolve("sirene"))),
			"a connector property holding a file: reference no longer resolves");
		final String refusal = "no such file in the secrets directory";
		SecretReferenceConfinementTest.refused("file", outside, refusal);
		SecretReferenceConfinementTest.refused("file", secrets + "/../ebx.properties", refusal);
		SecretReferenceConfinementTest.refused("file", "../ebx.properties", refusal);
		SecretReferenceConfinementTest.refused("file", "sub/../../ebx.properties", refusal);
		SecretReferenceConfinementTest.refused("file", sibling.resolve("token").toString(), refusal);
		SecretReferenceConfinementTest.refused("file", "/etc/hosts", refusal);
		// an absent file answers like a file outside: no way to probe what exists elsewhere
		SecretReferenceConfinementTest.refused("file", pWork.resolve("absent").toString(), refusal);
		SecretReferenceConfinementTest.refused("file", "absent", refusal);
		if (linked)
		{
			SecretReferenceConfinementTest.refused("file", secrets.resolve("link").toString(), refusal);
			SecretReferenceConfinementTest.refused("file", "link", refusal);
		}
		final byte[] large = new byte[1024 * 1024 + 1];
		java.util.Arrays.fill(large, (byte) 'x');
		Files.write(secrets.resolve("large"), large);
		SecretReferenceConfinementTest.refused("file", "large", "exceeds 1 MiB");

		// a directory that does not exist closes the scheme, it does not open it
		System.setProperty(SecretResolvers.SECRETS_DIR_PROPERTY, pWork.resolve("missing").toString());
		SecretReferenceConfinementTest.refused("file", secrets.resolve("sirene").toString(), "does not exist or cannot be read");
		System.clearProperty(SecretResolvers.SECRETS_DIR_PROPERTY);
	}

	private static void environment()
	{
		String variable = null;
		for (final java.util.Map.Entry<String, String> entry : System.getenv().entrySet())
		{
			if (entry.getValue() != null && !entry.getValue().trim().isEmpty() && System.getProperty(entry.getKey()) == null
				&& entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*"))
			{
				variable = entry.getKey();
				break;
			}
		}
		SecretReferenceConfinementTest.check(variable != null, "this test needs one environment variable");
		final String expected = System.getenv(variable).trim();
		System.setProperty("ebx.connectors.test.fakeSecret", "system-property-secret");

		// no list: refused (C2-08), whatever the variable
		System.clearProperty(SecretResolvers.ENV_ALLOWED_PROPERTY);
		SecretReferenceConfinementTest.refused("env", variable, SecretResolvers.ENV_ALLOWED_PROPERTY);
		SecretReferenceConfinementTest.check(SecretResolvers.referenceRefusal("env:" + variable) != null, "the status route does not flag a refused env: reference");
		SecretReferenceConfinementTest.check(SecretResolvers.referenceRefusal("sk-plain") == null, "a plain value is not a refused reference");
		SecretReferenceConfinementTest.refused("env", "ebx.connectors.test.fakeSecret", SecretResolvers.ENV_ALLOWED_PROPERTY);
		SecretReferenceConfinementTest.refused("env", "java.home", SecretResolvers.ENV_ALLOWED_PROPERTY);
		SecretReferenceConfinementTest.refused("env", "user.home", SecretResolvers.ENV_ALLOWED_PROPERTY);

		// a list: only what it names, system properties included
		System.setProperty(SecretResolvers.ENV_ALLOWED_PROPERTY, "SOME_OTHER_NAME, ebx.connectors.test.*");
		SecretReferenceConfinementTest.resolves("env", "ebx.connectors.test.fakeSecret", "system-property-secret");
		SecretReferenceConfinementTest.refused("env", variable, "is not in " + SecretResolvers.ENV_ALLOWED_PROPERTY);
		SecretReferenceConfinementTest.refused("env", "java.home", "is not in " + SecretResolvers.ENV_ALLOWED_PROPERTY);
		SecretReferenceConfinementTest.refused("env", "SOME_OTHER_NAME", "is not set");
		System.setProperty(SecretResolvers.ENV_ALLOWED_PROPERTY, variable);
		SecretReferenceConfinementTest.resolves("env", variable, expected);
		SecretReferenceConfinementTest.check(SecretResolvers.referenceRefusal("env:" + variable) == null, "a listed env: reference is flagged");
		SecretReferenceConfinementTest.refused("env", "ebx.connectors.test.fakeSecret", "is not in " + SecretResolvers.ENV_ALLOWED_PROPERTY);
		// a lone star is not a way to open every system property
		System.setProperty(SecretResolvers.ENV_ALLOWED_PROPERTY, "*");
		SecretReferenceConfinementTest.refused("env", "ebx.connectors.test.fakeSecret", "is not in " + SecretResolvers.ENV_ALLOWED_PROPERTY);
		System.clearProperty(SecretResolvers.ENV_ALLOWED_PROPERTY);
	}

	private static void resolves(final String pScheme, final String pReference, final String pExpected)
	{
		SecretResolvers.get().invalidate();
		final String value;
		try
		{
			value = SecretResolvers.get().resolve(pScheme, pReference);
		}
		catch (final IllegalStateException ex)
		{
			throw new AssertionError(pScheme + ":" + pReference + " refused: " + ex.getMessage());
		}
		SecretReferenceConfinementTest.check(pExpected.equals(value), pScheme + ":" + pReference + " resolved to another value");
	}

	private static void refused(final String pScheme, final String pReference, final String pExpected)
	{
		SecretResolvers.get().invalidate();
		try
		{
			SecretResolvers.get().resolve(pScheme, pReference);
		}
		catch (final IllegalStateException expected)
		{
			SecretReferenceConfinementTest.check(expected.getMessage().contains(pExpected),
				pScheme + ":" + pReference + ": expected '" + pExpected + "' in: " + expected.getMessage());
			SecretReferenceConfinementTest.check(!expected.getMessage().contains("secret-value") && !expected.getMessage().contains("system-property-secret"),
				"a refusal carries the value it protects");
			return;
		}
		throw new AssertionError(pScheme + ":" + pReference + " was resolved");
	}

	private static void restore(final String pProperty, final String pValue)
	{
		if (pValue == null)
		{
			System.clearProperty(pProperty);
		}
		else
		{
			System.setProperty(pProperty, pValue);
		}
	}

	private static void delete(final File pFile)
	{
		final File[] children = Files.isSymbolicLink(pFile.toPath()) ? null : pFile.listFiles();
		if (children != null)
		{
			for (final File child : children)
			{
				SecretReferenceConfinementTest.delete(child);
			}
		}
		pFile.delete();
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass)
		{
			throw new AssertionError(pMessage);
		}
	}
}
