package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Schema-like description of one external object. */
public final class ObjectDescription
{
	private final boolean supported;
	private final String objectId;
	private final String label;
	private final List<FieldInfo> fields;
	private final String message;

	private ObjectDescription(final boolean pSupported, final String pObjectId, final String pLabel,
		final List<FieldInfo> pFields, final String pMessage)
	{
		this.supported = pSupported;
		this.objectId = pObjectId == null ? "" : pObjectId;
		this.label = pLabel == null ? this.objectId : pLabel;
		this.fields = Collections.unmodifiableList(new ArrayList<FieldInfo>(
			pFields == null ? Collections.<FieldInfo>emptyList() : pFields));
		this.message = pMessage == null ? "" : pMessage;
	}

	public static ObjectDescription of(final String pObjectId, final String pLabel,
		final List<FieldInfo> pFields)
	{
		return new ObjectDescription(true, pObjectId, pLabel, pFields, "");
	}

	public static ObjectDescription unsupported(final String pObjectId, final String pMessage)
	{
		return new ObjectDescription(false, pObjectId, pObjectId, null, pMessage);
	}

	public boolean isSupported() { return this.supported; }
	public String getObjectId() { return this.objectId; }
	public String getLabel() { return this.label; }
	public List<FieldInfo> getFields() { return this.fields; }
	public String getMessage() { return this.message; }
}
