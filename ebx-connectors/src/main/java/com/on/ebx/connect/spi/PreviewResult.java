package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Immutable bounded sample returned by a connector inspection. */
public final class PreviewResult
{
	private final boolean supported;
	private final List<JsonNode> records;
	private final boolean truncated;
	private final String message;

	private PreviewResult(final boolean pSupported, final List<JsonNode> pRecords,
		final boolean pTruncated, final String pMessage)
	{
		this.supported = pSupported;
		this.records = Collections.unmodifiableList(new ArrayList<JsonNode>(
			pRecords == null ? Collections.<JsonNode>emptyList() : pRecords));
		this.truncated = pTruncated;
		this.message = pMessage == null ? "" : pMessage;
	}

	public static PreviewResult of(final List<JsonNode> pRecords, final boolean pTruncated)
	{
		if (pRecords != null && pRecords.size() > PreviewRequest.MAX_LIMIT)
			throw new IllegalArgumentException("Preview exceeds the maximum of " + PreviewRequest.MAX_LIMIT);
		return new PreviewResult(true, pRecords, pTruncated, "");
	}

	public static PreviewResult unsupported(final String pMessage)
	{
		return new PreviewResult(false, null, false, pMessage);
	}

	public boolean isSupported() { return this.supported; }
	public List<JsonNode> getRecords() { return this.records; }
	public boolean isTruncated() { return this.truncated; }
	public String getMessage() { return this.message; }
}
