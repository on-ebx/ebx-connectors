package com.on.ebx.connect.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, read-only request for sample records. */
public final class PreviewRequest
{
	public static final int DEFAULT_LIMIT = 20;
	public static final int MAX_LIMIT = 100;
	private final String objectId;
	private final String operation;
	private final Map<String, String> inputs;
	private final int limit;

	public PreviewRequest(final String pObjectId, final String pOperation,
		final Map<String, String> pInputs, final int pLimit)
	{
		this.objectId = pObjectId == null ? "" : pObjectId.trim();
		this.operation = pOperation == null ? "" : pOperation.trim();
		this.inputs = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
			pInputs == null ? Collections.<String, String>emptyMap() : pInputs));
		this.limit = pLimit <= 0 ? DEFAULT_LIMIT : Math.min(pLimit, MAX_LIMIT);
	}

	public String getObjectId() { return this.objectId; }
	public String getOperation() { return this.operation; }
	public Map<String, String> getInputs() { return this.inputs; }
	public int getLimit() { return this.limit; }
}
