package com.on.ebx.connect.spi;

/** Safe type-level defaults used when an instance does not override them. */
public final class ConnectorDefaults
{
	private final int connectTimeoutMs;
	private final int readTimeoutMs;
	private final int maximumRetries;
	private final int trustLevel;

	public ConnectorDefaults(final int pConnectTimeoutMs, final int pReadTimeoutMs,
		final int pMaximumRetries, final int pTrustLevel)
	{
		if (pConnectTimeoutMs <= 0 || pReadTimeoutMs <= 0 || pMaximumRetries < 0
			|| pTrustLevel < 0 || pTrustLevel > 100)
		{
			throw new IllegalArgumentException("Invalid connector defaults");
		}
		this.connectTimeoutMs = pConnectTimeoutMs;
		this.readTimeoutMs = pReadTimeoutMs;
		this.maximumRetries = pMaximumRetries;
		this.trustLevel = pTrustLevel;
	}

	public static ConnectorDefaults standard() { return new ConnectorDefaults(10000, 30000, 0, 50); }
	public int getConnectTimeoutMs() { return this.connectTimeoutMs; }
	public int getReadTimeoutMs() { return this.readTimeoutMs; }
	public int getMaximumRetries() { return this.maximumRetries; }
	public int getTrustLevel() { return this.trustLevel; }
}
