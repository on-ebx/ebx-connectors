package com.on.ebx.connect.spi;

/** Lightweight identity returned while browsing objects in an external system. */
public final class DiscoveredObject
{
	private final String id;
	private final String label;
	private final String type;
	private final boolean readable;

	public DiscoveredObject(final String pId, final String pLabel, final String pType,
		final boolean pReadable)
	{
		if (pId == null || pId.trim().isEmpty()) throw new IllegalArgumentException("id is required");
		this.id = pId.trim();
		this.label = pLabel == null || pLabel.trim().isEmpty() ? this.id : pLabel.trim();
		this.type = pType == null ? "" : pType.trim();
		this.readable = pReadable;
	}

	public String getId() { return this.id; }
	public String getLabel() { return this.label; }
	public String getType() { return this.type; }
	public boolean isReadable() { return this.readable; }
}
