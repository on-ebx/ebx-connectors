package com.on.ebx.connect.ebx.ui;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.on.ebx.connect.core.ConnectorsPaths;

/**
 * Connector logos: module resources of the form {@code ebx-connectors:ext-images:connect/logos/<id>.svg},
 * files under {@code www/common/images/connect/logos}. The generated tiles are listed in
 * {@code logos.txt}; any file with the same name replaces them (an official brand logo, for instance).
 */
public final class Logos
{
	public static final String FOLDER = "connect/logos/";
	private static final Set<String> GENERATED = Logos.load();

	private Logos()
	{
	}

	private static Set<String> load()
	{
		final Set<String> names = new HashSet<String>();
		try
		{
			final InputStream in = Logos.class.getResourceAsStream("logos.txt");
			if (in != null)
			{
				final BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
				try
				{
					for (String line = reader.readLine(); line != null; line = reader.readLine())
					{
						if (!line.trim().isEmpty())
						{
							names.add(line.trim());
						}
					}
				}
				finally
				{
					reader.close();
				}
			}
		}
		catch (final Exception ex)
		{
			// no list: only explicit values are used
		}
		return Collections.unmodifiableSet(names);
	}

	/** The resource value of the generated tile of a connector id, or null when none was generated. */
	public static String generatedFor(final String pConnectorId)
	{
		return pConnectorId != null && Logos.GENERATED.contains(pConnectorId) ? Logos.resource(pConnectorId + ".svg") : null;
	}

	/**
	 * Packaged logo preferred for a connector. {@code EBX.svg} is the real ON EBX mark, cropped to
	 * its icon (the circular emblem from {@code ebx-ui/images/ebx-logo.svg}, without the wordmark) —
	 * not a generated tile. An earlier {@code EBX.jpg} here was a fabricated placeholder that never
	 * matched the actual brand (found live 2026-09-12, comparing it side by side against the app's
	 * own header logo); removed rather than kept alongside the real one.
	 */
	public static String preferredFor(final String pConnectorId)
	{
		if ("EBX".equals(pConnectorId))
		{
			return Logos.resource("EBX.svg");
		}
		return Logos.generatedFor(pConnectorId);
	}

	public static String resource(final String pFile)
	{
		return ConnectorsPaths.MODULE_NAME + ":ext-images:" + Logos.FOLDER + pFile; // the type key of an osd:resource is "ext-" + folder
	}

	/** Path inside the images folder of the module, from a resource value ({@code module:image:path}) or a bare path. */
	public static String imagePath(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return null;
		}
		final String v = pValue.trim();
		final int last = v.lastIndexOf(':');
		return last >= 0 ? v.substring(last + 1) : v;
	}

	/** Absolute URL of a logo for pages outside a form writer (REST, designer JS). */
	public static String url(final String pValue)
	{
		final String path = Logos.imagePath(pValue);
		return path == null ? null : "/" + ConnectorsPaths.MODULE_NAME + "/www/common/images/" + path;
	}

	public static String defaultUrl()
	{
		return Logos.url(Logos.resource("default.svg"));
	}
}
