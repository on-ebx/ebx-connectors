package com.on.ebx.connect.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version this module was built as (audit C-08). The build ({@code build-test.sh}) derives it from git and writes it
 * into the resource {@value #RESOURCE} inside the classes; the war's {@code META-INF/MANIFEST.MF} carries the same
 * value as {@code Implementation-Version}. A class tree that was not produced by that script (compiled by Eclipse,
 * for instance) has no such resource, and every part then reads {@value #UNKNOWN}: a reader is never told a version
 * the build did not stamp.
 * <p>
 * Reads and exposes the version only; nothing in the module behaves differently according to it.
 */
public final class ModuleVersion
{
	/** The resource the build writes: {@code version}, {@code commit} and {@code builtAt} (UTC, ISO 8601). */
	public static final String RESOURCE = "/com/on/ebx/connect/version.properties";

	/** What each part reads when the build stamped nothing, or something unusable. */
	public static final String UNKNOWN = "unknown";

	private static final ModuleVersion CURRENT = ModuleVersion.fromResource(ModuleVersion.class.getResourceAsStream(ModuleVersion.RESOURCE));

	private final String version;
	private final String commit;
	private final String builtAt;

	private ModuleVersion(final String pVersion, final String pCommit, final String pBuiltAt)
	{
		this.version = pVersion;
		this.commit = pCommit;
		this.builtAt = pBuiltAt;
	}

	/** The version of this running module, {@value #UNKNOWN} when the build did not stamp one. */
	public static String version()
	{
		return ModuleVersion.CURRENT.version;
	}

	/** The commit the module was built from, {@value #UNKNOWN} when not stamped. */
	public static String commit()
	{
		return ModuleVersion.CURRENT.commit;
	}

	/** When the module was built (UTC, ISO 8601), {@value #UNKNOWN} when not stamped. */
	public static String builtAt()
	{
		return ModuleVersion.CURRENT.builtAt;
	}

	/**
	 * Reads a version resource. Null (resource absent), an unreadable stream or a blank value gives
	 * {@value #UNKNOWN} for the part concerned; it never throws, because a missing stamp must not stop the module
	 * from starting. The stream is closed.
	 */
	static ModuleVersion fromResource(final InputStream pStream)
	{
		final Properties properties = new Properties();
		if (pStream != null)
		{
			try
			{
				properties.load(pStream);
			}
			catch (final IOException | IllegalArgumentException ex)
			{
				properties.clear();
			}
			finally
			{
				try
				{
					pStream.close();
				}
				catch (final IOException ex)
				{
					// nothing was written; a close failure changes nothing for the reader
				}
			}
		}
		return new ModuleVersion(ModuleVersion.part(properties, "version"), ModuleVersion.part(properties, "commit"),
			ModuleVersion.part(properties, "builtAt"));
	}

	private static String part(final Properties pProperties, final String pKey)
	{
		final String value = pProperties.getProperty(pKey);
		return value == null || value.trim().isEmpty() ? ModuleVersion.UNKNOWN : value.trim();
	}

	String getVersion()
	{
		return this.version;
	}

	String getCommit()
	{
		return this.commit;
	}

	String getBuiltAt()
	{
		return this.builtAt;
	}
}
