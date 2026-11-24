package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Inputs for non-persistent draft mapping generation. */
public final class DraftMappingRequest
{
	private final String objectId;
	private final String operation;
	private final List<FieldInfo> targetFields;

	public DraftMappingRequest(final String pObjectId, final String pOperation,
		final List<FieldInfo> pTargetFields)
	{
		this.objectId = pObjectId == null ? "" : pObjectId.trim();
		this.operation = pOperation == null ? "" : pOperation.trim();
		this.targetFields = Collections.unmodifiableList(new ArrayList<FieldInfo>(
			pTargetFields == null ? Collections.<FieldInfo>emptyList() : pTargetFields));
	}

	public String getObjectId() { return this.objectId; }
	public String getOperation() { return this.operation; }
	public List<FieldInfo> getTargetFields() { return this.targetFields; }
}
