package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded and already-redacted connector preview result. */
public final class PreviewResult
{
	private final List<String> records;
	private final int inspectedRecordCount;
	private final int payloadBytes;
	private final boolean truncated;

	private PreviewResult(final List<String> pRecords, final int pInspectedRecordCount,
		final int pPayloadBytes, final boolean pTruncated)
	{
		this.records = Collections.unmodifiableList(new ArrayList<String>(pRecords));
		this.inspectedRecordCount = pInspectedRecordCount;
		this.payloadBytes = pPayloadBytes;
		this.truncated = pTruncated;
	}

	/**
	 * Retains complete records while both request ceilings allow it. Records are masked before
	 * their byte size is measured or retained, so a preview object never contains known secrets.
	 */
	public static PreviewResult collect(final Iterable<String> pRecords, final PreviewRequest pRequest,
		final SensitiveDataMasker pMasker)
	{
		if (pRecords == null) throw new IllegalArgumentException("records must not be null");
		if (pRequest == null) throw new IllegalArgumentException("request must not be null");
		if (pMasker == null) throw new IllegalArgumentException("masker must not be null");

		final List<String> accepted = new ArrayList<String>();
		int inspected = 0;
		int bytes = 0;
		boolean truncated = false;
		for (final String record : pRecords)
		{
			inspected++;
			if (accepted.size() >= pRequest.getRecordLimit())
			{
				truncated = true;
				break;
			}
			final String safe = pMasker.mask(record == null ? "null" : record);
			final int recordBytes = safe.getBytes(StandardCharsets.UTF_8).length;
			if (recordBytes > pRequest.getPayloadByteLimit() - bytes)
			{
				truncated = true;
				break;
			}
			accepted.add(safe);
			bytes += recordBytes;
		}
		return new PreviewResult(accepted, inspected, bytes, truncated);
	}

	public List<String> getRecords() { return this.records; }
	public int getInspectedRecordCount() { return this.inspectedRecordCount; }
	public int getPayloadBytes() { return this.payloadBytes; }
	public boolean isTruncated() { return this.truncated; }
}
