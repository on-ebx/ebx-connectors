package com.on.ebx.connect.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Immutable, versioned connector configuration template. */
public final class ConnectorTemplate
{
	public enum Ownership { PACKAGED, CUSTOMER }

	private final String id;
	private final int version;
	private final Ownership ownership;
	private final String basedOn;
	private final int basedOnVersion;
	private final ObjectNode configuration;

	public ConnectorTemplate(final String pId, final int pVersion, final Ownership pOwnership,
		final String pBasedOn, final int pBasedOnVersion, final ObjectNode pConfiguration)
	{
		if (pId == null || !pId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
		{
			throw new IllegalArgumentException("Template id must be a portable identifier");
		}
		if (pVersion < 1) { throw new IllegalArgumentException("Template version must be positive"); }
		if (pOwnership == null) { throw new IllegalArgumentException("Template ownership is required"); }
		if (pConfiguration == null) { throw new IllegalArgumentException("Template configuration is required"); }
		if (pOwnership == Ownership.PACKAGED && pBasedOn != null)
		{
			throw new IllegalArgumentException("A packaged template cannot be based on another template");
		}
		if (pOwnership == Ownership.CUSTOMER && (pBasedOn == null || pBasedOnVersion < 1))
		{
			throw new IllegalArgumentException("A customer template must retain its packaged origin");
		}
		this.id = pId;
		this.version = pVersion;
		this.ownership = pOwnership;
		this.basedOn = pBasedOn;
		this.basedOnVersion = pBasedOnVersion;
		this.configuration = pConfiguration.deepCopy();
	}

	public String getId() { return this.id; }
	public int getVersion() { return this.version; }
	public Ownership getOwnership() { return this.ownership; }
	public String getBasedOn() { return this.basedOn; }
	public int getBasedOnVersion() { return this.basedOnVersion; }
	public ObjectNode getConfiguration() { return this.configuration.deepCopy(); }
}
