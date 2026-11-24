package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Sanitized result produced by {@link BoundedInspectionRuntime}. */
public final class InspectionPreview
{
	private final boolean supported;
	private final List<JsonNode> records;
	private final boolean truncated;
	private final int payloadBytes;
	private final String message;

	private InspectionPreview(final boolean pSupported, final List<JsonNode> pRecords,
		final boolean pTruncated, final int pPayloadBytes, final String pMessage)
	{
		this.supported = pSupported;
		this.records = Collections.unmodifiableList(new ArrayList<JsonNode>(pRecords));
		this.truncated = pTruncated;
		this.payloadBytes = pPayloadBytes;
		this.message = pMessage == null ? "" : pMessage;
	}

	static InspectionPreview of(final List<JsonNode> pRecords, final boolean pTruncated,
		final int pPayloadBytes)
	{
		return new InspectionPreview(true, pRecords, pTruncated, pPayloadBytes, "");
	}

	static InspectionPreview unsupported(final String pMessage)
	{
		return new InspectionPreview(false, Collections.<JsonNode>emptyList(), false, 0, pMessage);
	}

	public boolean isSupported() { return this.supported; }
	public List<JsonNode> getRecords() { return this.records; }
	public boolean isTruncated() { return this.truncated; }
	public int getPayloadBytes() { return this.payloadBytes; }
	public String getMessage() { return this.message; }
}
