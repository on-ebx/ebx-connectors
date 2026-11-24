package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Non-persistent mapping proposal produced from inspected metadata. */
public final class DraftMapping
{
	private final boolean supported;
	private final List<DraftMappingField> fields;
	private final String message;

	private DraftMapping(final boolean pSupported, final List<DraftMappingField> pFields,
		final String pMessage)
	{
		this.supported = pSupported;
		this.fields = Collections.unmodifiableList(new ArrayList<DraftMappingField>(
			pFields == null ? Collections.<DraftMappingField>emptyList() : pFields));
		this.message = pMessage == null ? "" : pMessage;
	}

	public static DraftMapping of(final List<DraftMappingField> pFields)
	{
		return new DraftMapping(true, pFields, "");
	}

	public static DraftMapping unsupported(final String pMessage)
	{
		return new DraftMapping(false, null, pMessage);
	}

	public boolean isSupported() { return this.supported; }
	public List<DraftMappingField> getFields() { return this.fields; }
	public String getMessage() { return this.message; }
}
