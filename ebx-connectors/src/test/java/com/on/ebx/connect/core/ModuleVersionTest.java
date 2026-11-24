package com.on.ebx.connect.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The version the build stamps is read as written, and a missing or unusable stamp reads "unknown" instead of
 * stopping the module or inventing a number (audit C-08).
 */
public final class ModuleVersionTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		stamped();
		absent();
		unusable();
		running();
		System.out.println("PASS: module version is read from the build stamp, and is \"unknown\" without one");
	}

	private static InputStream of(final String pText)
	{
		return new ByteArrayInputStream(pText.getBytes(StandardCharsets.ISO_8859_1));
	}

	private static void stamped()
	{
		final ModuleVersion v = ModuleVersion.fromResource(of("version=1.4.0-3-g1a2b3c4-dirty\ncommit=1a2b3c4d5e\nbuiltAt=2026-10-06T08:15:00Z\n"));
		check("1.4.0-3-g1a2b3c4-dirty".equals(v.getVersion()), "version as stamped: " + v.getVersion());
		check("1a2b3c4d5e".equals(v.getCommit()), "commit as stamped: " + v.getCommit());
		check("2026-10-06T08:15:00Z".equals(v.getBuiltAt()), "build time as stamped: " + v.getBuiltAt());
		// the file may use spaces around the value, and a part may be stamped alone
		final ModuleVersion partial = ModuleVersion.fromResource(of("version =  0.0.0-abc1234  \n"));
		check("0.0.0-abc1234".equals(partial.getVersion()), "value trimmed: " + partial.getVersion());
		check(ModuleVersion.UNKNOWN.equals(partial.getCommit()) && ModuleVersion.UNKNOWN.equals(partial.getBuiltAt()), "parts not stamped read unknown");
	}

	private static void absent()
	{
		final ModuleVersion v = ModuleVersion.fromResource(null);
		check(ModuleVersion.UNKNOWN.equals(v.getVersion()) && ModuleVersion.UNKNOWN.equals(v.getCommit())
			&& ModuleVersion.UNKNOWN.equals(v.getBuiltAt()), "no resource: every part is unknown");
	}

	private static void unusable()
	{
		check(ModuleVersion.UNKNOWN.equals(ModuleVersion.fromResource(of("version=\ncommit=   \n")).getVersion()), "a blank value is unknown");
		check(ModuleVersion.UNKNOWN.equals(ModuleVersion.fromResource(of("version=\\u12G4\n")).getVersion()), "a malformed escape is unknown, not an exception");
		final InputStream broken = new InputStream()
		{
			@Override
			public int read() throws IOException
			{
				throw new IOException("disk gone");
			}
		};
		check(ModuleVersion.UNKNOWN.equals(ModuleVersion.fromResource(broken).getVersion()), "an unreadable stream is unknown, not an exception");
	}

	/** The resource of the classes under test: when the build stamped one, the public accessors say what it says. */
	private static void running() throws IOException
	{
		final InputStream resource = ModuleVersion.class.getResourceAsStream(ModuleVersion.RESOURCE);
		if (resource == null)
		{
			// a class tree compiled outside build-test.sh
			check(ModuleVersion.UNKNOWN.equals(ModuleVersion.version()), "no stamp in the classes: version() is unknown");
			return;
		}
		final ModuleVersion stamp = ModuleVersion.fromResource(resource);
		check(stamp.getVersion().equals(ModuleVersion.version()) && stamp.getCommit().equals(ModuleVersion.commit())
			&& stamp.getBuiltAt().equals(ModuleVersion.builtAt()), "the public accessors answer what the stamp holds");
		check(!ModuleVersion.UNKNOWN.equals(ModuleVersion.version()), "a build stamps a version");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
