package com.on.ebx.connect.core;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * A connector-stored sample (the {@code sample-file} connector type's {@code sampleData} field), as
 * an RFC 2397 data URL that may carry the uploaded file's original name as a parameter:
 * <pre>data:&lt;mime&gt;;name=&lt;percent-encoded original file name&gt;;base64,&lt;payload&gt;</pre>
 * {@code name} is optional — every value stored before the sample-file-name lot (2026-09-23), and
 * still today's default when a caller does not supply a file name, has none, and that remains a
 * valid, fully-supported data URL, not an error. Shared by {@code SampleFileConnector} (the server
 * side) and {@code UploadedSampleFileWidgetFactory} (the record-form widget) so both parse the same
 * tolerant grammar once. sample-file-name lot (2026-09-23).
 */
public final class SampleDataUrl
{
	private final String mime;
	private final String name;
	private final String payload;

	private SampleDataUrl(final String pMime, final String pName, final String pPayload)
	{
		this.mime = pMime;
		this.name = pName;
		this.payload = pPayload;
	}

	/** The media type, e.g. {@code "text/csv"} (the segment before the first {@code ;} or {@code ,}). */
	public String mime()
	{
		return this.mime;
	}

	/**
	 * The original file's base name with any directory prefix stripped, trimmed, and refused
	 * (answers null) if it contains a control character — or null when the data URL carries no
	 * {@code name} parameter at all.
	 */
	public String name()
	{
		return this.name;
	}

	/** The base64 payload, i.e. everything after the first {@code ,}. */
	public String payload()
	{
		return this.payload;
	}

	/**
	 * Parses {@code pDataUrl}. Answers null for anything that is not a well-formed
	 * {@code data:<mime>[;params...];base64,<payload>} URL — including a missing or empty value, no
	 * {@code data:} prefix, no comma, and a prefix with no {@code base64} parameter — never throws.
	 * An unrecognised parameter other than {@code name}/{@code base64} is ignored, not rejected: this
	 * parser only needs to be tolerant, not exhaustive.
	 */
	public static SampleDataUrl parse(final String pDataUrl)
	{
		if (pDataUrl == null || pDataUrl.trim().isEmpty() || !pDataUrl.startsWith("data:"))
		{
			return null;
		}
		final int comma = pDataUrl.indexOf(',');
		if (comma < 0)
		{
			return null;
		}
		final String prefix = pDataUrl.substring("data:".length(), comma);
		final String[] parts = prefix.split(";", -1);
		final String mime = parts.length == 0 ? "" : parts[0].trim();
		if (mime.isEmpty())
		{
			return null;
		}
		boolean base64 = false;
		String name = null;
		for (int i = 1; i < parts.length; i++)
		{
			final String part = parts[i].trim();
			if ("base64".equals(part))
			{
				base64 = true;
				continue;
			}
			final int eq = part.indexOf('=');
			if (eq > 0 && "name".equals(part.substring(0, eq).trim()))
			{
				name = SampleDataUrl.sanitizeName(SampleDataUrl.decode(part.substring(eq + 1).trim()));
			}
		}
		if (!base64)
		{
			return null;
		}
		return new SampleDataUrl(mime, name, pDataUrl.substring(comma + 1));
	}

	/**
	 * Mirrors the browser's {@code decodeURIComponent}: only {@code %XX} escapes are decoded (as
	 * UTF-8), a literal {@code +} is left alone. {@link URLDecoder} alone would wrongly turn a literal
	 * {@code +} into a space (it decodes {@code application/x-www-form-urlencoded}, a different,
	 * stricter grammar than {@code encodeURIComponent}'s), so {@code +} is escaped first.
	 */
	private static String decode(final String pValue)
	{
		try
		{
			return URLDecoder.decode(pValue.replace("+", "%2B"), "UTF-8");
		}
		catch (final UnsupportedEncodingException | IllegalArgumentException ex)
		{
			return null;
		}
	}

	/** Keeps only the base name (strips anything up to the last {@code /} or {@code \}), trims it, and refuses control characters. */
	private static String sanitizeName(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final int slash = Math.max(pValue.lastIndexOf('/'), pValue.lastIndexOf('\\'));
		final String base = (slash < 0 ? pValue : pValue.substring(slash + 1)).trim();
		if (base.isEmpty())
		{
			return null;
		}
		for (int i = 0; i < base.length(); i++)
		{
			if (Character.isISOControl(base.charAt(i)))
			{
				return null;
			}
		}
		return base;
	}
}
