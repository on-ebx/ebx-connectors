package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * The functions that arrived after the October release and are CLOSED by default (decision of 2026-11-12, audit
 * C3-03..C3-06): the server administrator opens each by name in the JVM property {@value #PROPERTY}, a comma-separated list
 * of {@code documents}, {@code cdcMatching} and {@code redshift}. This is the only class that reads that property.
 * <p>
 * Absent, empty or misspelt means closed: a word that is not one of the three is reported once with a WARN and never
 * taken for an opening. A closed function stays known to the registry (a connection of that type still reads and shows),
 * but refuses to run, with a coded message in English and French that names the property to set (see
 * {@code docs/OPERATIONS.md}, "Preview functions"). The property is read at each call, so a test or an operator
 * tool sees its current value; nothing is cached.
 */
public final class PreviewFeatures
{
	/** The JVM property that opens the functions. */
	public static final String PROPERTY = "ebx.connectors.preview";

	/** Code at the head of every refusal, for a log search or a monitoring rule. */
	public static final String CODE = "CN-PREVIEW-CLOSED";

	private static final String BUNDLE = "com.on.ebx.connect.ebx.ui.Messages";

	/** A function that is closed until named in the property. */
	public enum Feature
	{
		/** Connection type {@code documents}: reading, extraction, DAMA annotation, vision. */
		DOCUMENTS("documents", "preview.documents"),
		/** Inputs {@code matching=afterApply|off} of a CDC feed and {@code publish=golden} of an outbound trigger. */
		CDC_MATCHING("cdcMatching", "preview.cdcMatching"),
		/** Connection type {@code redshift}. */
		REDSHIFT("redshift", "preview.redshift");

		private final String word;
		private final String key;

		Feature(final String pWord, final String pKey)
		{
			this.word = pWord;
			this.key = pKey;
		}

		/** The word that opens this function in the property. */
		public String word()
		{
			return this.word;
		}

		String key()
		{
			return this.key;
		}
	}

	private static String warnedFor;

	private PreviewFeatures()
	{
	}

	/** Whether the function is open now. */
	public static boolean isOpen(final Feature pFeature)
	{
		return PreviewFeatures.opened().contains(pFeature);
	}

	/** The functions the property opens now. */
	public static Set<Feature> opened()
	{
		final Set<Feature> open = EnumSet.noneOf(Feature.class);
		for (final String word : PreviewFeatures.words())
		{
			final Feature feature = PreviewFeatures.of(word);
			if (feature != null)
			{
				open.add(feature);
			}
		}
		return open;
	}

	/** The words of the property that name no function (misspelt, other case, retired), in order. */
	public static List<String> unknownWords()
	{
		final List<String> unknown = new ArrayList<String>();
		for (final String word : PreviewFeatures.words())
		{
			if (PreviewFeatures.of(word) == null)
			{
				unknown.add(word);
			}
		}
		return unknown;
	}

	/**
	 * The function a connector type belongs to, null when the type is not a preview one. The match is exact and in lower case,
	 * as the registry knows the type.
	 */
	public static Feature featureOfType(final String pType)
	{
		if ("documents".equals(pType))
		{
			return Feature.DOCUMENTS;
		}
		if ("redshift".equals(pType))
		{
			return Feature.REDSHIFT;
		}
		return null;
	}

	/** Refuses with the coded message when the function is closed. */
	public static void require(final Feature pFeature) throws ConnectorException
	{
		if (!PreviewFeatures.isOpen(pFeature))
		{
			throw new ConnectorException(PreviewFeatures.closedMessage(pFeature));
		}
	}

	/** {@link #require} for a connector type: nothing happens for a type that is not a preview one. */
	public static void requireForType(final String pType) throws ConnectorException
	{
		final Feature feature = PreviewFeatures.featureOfType(pType);
		if (feature != null)
		{
			PreviewFeatures.require(feature);
		}
	}

	/**
	 * Whether the inputs of a {@code cdc} feed ask for Match and Merge around the batch ({@code matching=afterApply} or
	 * {@code off}). {@code matching=policy} (or none) leaves the add-on alone and is not a preview function. A value that is
	 * neither is left to the CDC reader, which refuses it by its own message.
	 */
	public static boolean cdcMatchingRequested(final Map<String, String> pInputs)
	{
		final String raw = pInputs == null ? null : pInputs.get("matching");
		if (raw == null)
		{
			return false;
		}
		final String value = raw.trim().toLowerCase(Locale.ENGLISH);
		return "afterapply".equals(value) || "after_apply".equals(value) || "off".equals(value);
	}

	/** Whether a trigger's {@code publish} parameter asks for the golden record. */
	public static boolean publishGoldenRequested(final String pPublish)
	{
		return pPublish != null && "golden".equalsIgnoreCase(pPublish.trim());
	}

	/**
	 * Refuses a mapping that carries a closed function: a {@code cdc} operation whose inputs ask for matching around the batch.
	 * Used when the mapping is saved AND when it runs.
	 */
	public static void requireMappingAllowed(final String pOperation, final Map<String, String> pInputs) throws ConnectorException
	{
		if ("cdc".equals(pOperation) && PreviewFeatures.cdcMatchingRequested(pInputs))
		{
			PreviewFeatures.require(Feature.CDC_MATCHING);
		}
	}

	/** The refusal text: code, then English, then French, both naming the property and the word to set. */
	public static String closedMessage(final Feature pFeature)
	{
		return "[" + PreviewFeatures.CODE + "] " + PreviewFeatures.text(pFeature, Locale.ENGLISH) + " / "
			+ PreviewFeatures.text(pFeature, Locale.FRENCH);
	}

	/** The refusal text in one language, from the module's message bundle. */
	public static String text(final Feature pFeature, final Locale pLocale)
	{
		final String pattern = PreviewFeatures.bundleText(pFeature.key(), pLocale);
		return pattern.replace("{0}", PreviewFeatures.PROPERTY).replace("{1}", pFeature.word());
	}

	private static String bundleText(final String pKey, final Locale pLocale)
	{
		try
		{
			return ResourceBundle.getBundle(PreviewFeatures.BUNDLE, pLocale, PreviewFeatures.class.getClassLoader()).getString(pKey);
		}
		catch (final MissingResourceException missing)
		{
			// a build without the bundle must still say what to do: the key and the property are enough
			return pKey + " (set -D{0}={1})";
		}
	}

	/** The {@code preview} block of {@code /v1/status}: the property, what is open, what is closed, the words not understood. */
	public static ObjectNode status()
	{
		final ObjectNode out = com.on.ebx.connect.core.Json.object();
		out.put("property", PreviewFeatures.PROPERTY);
		final String raw = System.getProperty(PreviewFeatures.PROPERTY);
		out.put("value", raw == null ? "" : raw);
		final ArrayNode open = out.putArray("open");
		final ArrayNode closed = out.putArray("closed");
		final Set<Feature> opened = PreviewFeatures.opened();
		for (final Feature feature : Feature.values())
		{
			(opened.contains(feature) ? open : closed).add(feature.word());
		}
		final ArrayNode unknown = out.putArray("unknownWords");
		for (final String word : PreviewFeatures.unknownWords())
		{
			unknown.add(word);
		}
		out.put("note", "Functions closed by default; an administrator opens one by naming it in -D" + PreviewFeatures.PROPERTY
			+ " (documents, cdcMatching, redshift), then restarts the server. See docs/OPERATIONS.md.");
		return out;
	}

	private static Feature of(final String pWord)
	{
		for (final Feature feature : Feature.values())
		{
			if (feature.word.equals(pWord))
			{
				return feature;
			}
		}
		return null;
	}

	/** The trimmed, non-empty words of the property, without repetition; unknown ones are logged once per distinct value. */
	private static Set<String> words()
	{
		final Set<String> words = new LinkedHashSet<String>();
		final String raw = System.getProperty(PreviewFeatures.PROPERTY);
		if (raw == null)
		{
			return words;
		}
		for (final String part : raw.split(","))
		{
			final String word = part.trim();
			if (!word.isEmpty())
			{
				words.add(word);
			}
		}
		PreviewFeatures.warnOnce(raw, words);
		return words;
	}

	private static synchronized void warnOnce(final String pRaw, final Set<String> pWords)
	{
		if (pRaw.equals(PreviewFeatures.warnedFor))
		{
			return;
		}
		PreviewFeatures.warnedFor = pRaw;
		final List<String> unknown = new ArrayList<String>();
		for (final String word : pWords)
		{
			if (PreviewFeatures.of(word) == null)
			{
				unknown.add(word);
			}
		}
		if (!unknown.isEmpty())
		{
			ConnectorService.log().warn("[connectors] " + PreviewFeatures.PROPERTY + " names " + unknown
				+ ", which is not a preview function: it opens nothing. Known words: documents, cdcMatching, redshift (exact spelling).");
		}
	}
}
