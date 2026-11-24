package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable page of external object metadata. */
public final class ObjectDiscoveryResult
{
	private final boolean supported;
	private final List<DiscoveredObject> objects;
	private final String continuationToken;
	private final String message;
	private final boolean truncated;

	private ObjectDiscoveryResult(final boolean pSupported, final List<DiscoveredObject> pObjects,
		final String pContinuationToken, final String pMessage)
	{
		this(pSupported, pObjects, pContinuationToken, pMessage, false);
	}

	private ObjectDiscoveryResult(final boolean pSupported, final List<DiscoveredObject> pObjects,
		final String pContinuationToken, final String pMessage, final boolean pTruncated)
	{
		this.truncated = pTruncated;
		this.supported = pSupported;
		this.objects = Collections.unmodifiableList(new ArrayList<DiscoveredObject>(
			pObjects == null ? Collections.<DiscoveredObject>emptyList() : pObjects));
		this.continuationToken = pContinuationToken;
		this.message = pMessage == null ? "" : pMessage;
	}

	public static ObjectDiscoveryResult page(final List<DiscoveredObject> pObjects,
		final String pContinuationToken)
	{
		return new ObjectDiscoveryResult(true, pObjects, pContinuationToken, "");
	}

	public static ObjectDiscoveryResult unsupported(final String pMessage)
	{
		return new ObjectDiscoveryResult(false, null, null, pMessage);
	}

	/** A truncated page may omit local objects; the upstream cursor is not a resume point for those omissions. */
	public static ObjectDiscoveryResult page(final List<DiscoveredObject> pObjects,
		final String pContinuationToken, final boolean pTruncated)
	{
		return new ObjectDiscoveryResult(true, pObjects, pContinuationToken,
			pTruncated ? "Page truncated locally; omitted objects require a smaller page request." : "", pTruncated);
	}

	public boolean isTruncated() { return this.truncated; }

	public boolean isSupported() { return this.supported; }
	public List<DiscoveredObject> getObjects() { return this.objects; }
	public String getContinuationToken() { return this.continuationToken; }
	public String getMessage() { return this.message; }
}
