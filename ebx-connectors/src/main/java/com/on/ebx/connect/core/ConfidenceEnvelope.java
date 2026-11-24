package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The confidence envelope of a document, as {@link MappingEngine} reads it (split out of {@code DocumentExtraction} in 1.4.2 so that the mapping
 * engine jar, {@code on-ebx-mapping.jar}, does not drag the document readers with it). The envelope belongs to the {@code documents} preview
 * function (audit C3-17, bilan 1.4.1 gap 1): while that function is closed a document that carries the reserved names is treated as it was before
 * November, whatever connector it comes from; opened, the names {@code _minConfidence}, {@code _confidence}, {@code _sources} are reserved
 * (docs/CONTRACT.md, "Reserved names").
 */
public final class ConfidenceEnvelope
{
	public static final String CONFIDENCE = "_confidence";
	public static final String MIN_CONFIDENCE = "_minConfidence";
	public static final String SOURCES = "_sources";

	private final double threshold;
	private final JsonNode confidence;
	private final JsonNode sources;

	private ConfidenceEnvelope(final double pThreshold, final JsonNode pConfidence, final JsonNode pSources)
	{
		this.threshold = pThreshold;
		this.confidence = pConfidence;
		this.sources = pSources;
	}

	/** The envelope of a document: null when the function is closed or the document has no numeric {@code _minConfidence} (every rule writes as before). */
	public static ConfidenceEnvelope of(final JsonNode pRoot)
	{
		if (!PreviewFeatures.isOpen(PreviewFeatures.Feature.DOCUMENTS)) return null;
		if (pRoot == null || !pRoot.isObject() || !pRoot.path(MIN_CONFIDENCE).isNumber()) return null;
		return new ConfidenceEnvelope(pRoot.path(MIN_CONFIDENCE).asDouble(), pRoot.path(CONFIDENCE), pRoot.path(SOURCES));
	}

	/** The confidence of a source path; 0 when the envelope has none (a value without confidence never writes). */
	public double confidenceOf(final String pSourcePath)
	{
		final JsonNode c = this.confidence == null ? null : this.confidence.get(pSourcePath);
		return c != null && c.isNumber() ? c.asDouble() : 0;
	}

	public boolean isBelow(final String pSourcePath)
	{
		return this.confidenceOf(pSourcePath) < this.threshold;
	}

	public double getThreshold()
	{
		return this.threshold;
	}

	/** The provenance text of a source path, null when none. */
	public String sourceOf(final String pSourcePath)
	{
		final JsonNode s = this.sources == null ? null : this.sources.get(pSourcePath);
		return s == null || !s.isTextual() ? null : s.asText();
	}

	/** Source paths of the envelope that are attributes, for diagnostics. */
	public List<String> attributes()
	{
		final List<String> out = new ArrayList<String>();
		if (this.confidence != null)
		{
			for (final Iterator<Map.Entry<String, JsonNode>> it = this.confidence.fields(); it.hasNext();) out.add(it.next().getKey());
		}
		return out;
	}
}
