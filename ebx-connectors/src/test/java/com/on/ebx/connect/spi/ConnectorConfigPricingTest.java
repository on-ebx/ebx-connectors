package com.on.ebx.connect.spi;

/**
 * Pricing is data, like trust: this only checks the plain-Java contract of {@link ConnectorConfig}
 * (default, null-safety, participation in {@link ConnectorConfig#fingerprint()}) -- nothing here
 * touches EBX.
 */
public final class ConnectorConfigPricingTest
{
	public static void main(final String[] args)
	{
		final ConnectorConfig fresh = new ConnectorConfig();
		if (fresh.getPricing() != ConnectorConfig.Pricing.UNKNOWN)
		{
			throw new AssertionError("default pricing must be UNKNOWN, was " + fresh.getPricing());
		}
		if (fresh.getPricingNote() != null)
		{
			throw new AssertionError("default pricingNote must be null");
		}

		fresh.setPricing(ConnectorConfig.Pricing.PAID);
		if (fresh.getPricing() != ConnectorConfig.Pricing.PAID)
		{
			throw new AssertionError("setPricing did not stick");
		}

		// null is tolerated and normalized back to UNKNOWN, the same way setAuthType(null) -> NONE.
		fresh.setPricing(null);
		if (fresh.getPricing() != ConnectorConfig.Pricing.UNKNOWN)
		{
			throw new AssertionError("setPricing(null) must fall back to UNKNOWN, was " + fresh.getPricing());
		}

		fresh.setPricingNote("free up to 1 request/second");
		if (!"free up to 1 request/second".equals(fresh.getPricingNote()))
		{
			throw new AssertionError("setPricingNote did not stick");
		}

		// fingerprint() drives cached-connector reconfiguration: pricing and pricingNote must be part
		// of it, exactly like trust and logo already are, so a UI edit to either is picked up.
		final ConnectorConfig a = new ConnectorConfig();
		a.setId("x");
		a.setType("gleif");
		final ConnectorConfig b = new ConnectorConfig();
		b.setId("x");
		b.setType("gleif");
		if (!a.fingerprint().equals(b.fingerprint()))
		{
			throw new AssertionError("two identical configurations must fingerprint the same");
		}
		b.setPricing(ConnectorConfig.Pricing.FREE);
		if (a.fingerprint().equals(b.fingerprint()))
		{
			throw new AssertionError("fingerprint must change when pricing changes");
		}
		final ConnectorConfig c = new ConnectorConfig();
		c.setId("x");
		c.setType("gleif");
		c.setPricing(ConnectorConfig.Pricing.FREE);
		c.setPricingNote("some nuance");
		if (b.fingerprint().equals(c.fingerprint()))
		{
			throw new AssertionError("fingerprint must change when pricingNote changes");
		}

		System.out.println("PASS: ConnectorConfig.Pricing default, null-safety and fingerprint sensitivity");
	}
}
