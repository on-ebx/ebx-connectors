package com.on.ebx.connect.spi;

/** Bounded request for external object metadata. */
public final class ObjectDiscoveryRequest
{
	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 500;
	private final String query;
	private final int limit;
	private final String continuationToken;

	public ObjectDiscoveryRequest(final String pQuery, final int pLimit, final String pContinuationToken)
	{
		this.query = pQuery == null ? "" : pQuery.trim();
		this.limit = pLimit <= 0 ? DEFAULT_LIMIT : Math.min(pLimit, MAX_LIMIT);
		this.continuationToken = pContinuationToken;
	}

	public static ObjectDiscoveryRequest firstPage(final String pQuery, final int pLimit)
	{
		return new ObjectDiscoveryRequest(pQuery, pLimit, null);
	}

	public String getQuery() { return this.query; }
	public int getLimit() { return this.limit; }
	public String getContinuationToken() { return this.continuationToken; }
}
