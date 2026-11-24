package com.on.ebx.connect.spi;

import java.util.Random;

/**
 * Audit C-34: a cached connector asks "is my configuration still the one in EBX?" at every access. The answer is
 * {@link ConnectorConfig#sameConfigurationAs} (no string built, a sample of megabytes compared in place) and must be the
 * answer the fingerprint comparison gave: equal exactly when no configured value differs.
 */
public final class ConnectorConfigSameTest
{
	private ConnectorConfigSameTest() { }

	/** A configuration whose every value is derived from the seed, so two with the same seed are equal. */
	private static ConnectorConfig config(final int pSeed, final String pSample)
	{
		final ConnectorConfig c = new ConnectorConfig();
		c.setId("id" + pSeed);
		c.setType("type");
		c.setLabel("label é");
		c.setBaseUrl("https://example.org/" + pSeed);
		c.setAuthType(ConnectorConfig.AuthType.BASIC);
		c.setUsername("user");
		c.setPassword("secret");
		c.setApiKeyHeader("X-Key");
		c.setApiKey("key");
		c.setConnectTimeoutMs(1000);
		c.setReadTimeoutMs(2000);
		c.setTrust(60);
		c.setActive(true);
		c.setPricing(ConnectorConfig.Pricing.FREE);
		c.setPricingNote("note");
		c.setLogo("logo");
		c.setSampleData(pSample);
		c.setProperty("a", "1");
		c.setProperty("b", "2");
		return c;
	}

	private static String big(final int pLength, final char pLast)
	{
		final StringBuilder b = new StringBuilder(pLength);
		for (int i = 0; i < pLength - 1; i++)
		{
			b.append((char) ('a' + i % 26));
		}
		return b.append(pLast).toString();
	}

	private static void agree(final ConnectorConfig pA, final ConnectorConfig pB, final boolean pExpected, final String pWhat)
	{
		check(pA.sameConfigurationAs(pB) == pExpected, "sameConfigurationAs on " + pWhat);
		check(pB.sameConfigurationAs(pA) == pExpected, "sameConfigurationAs is symmetric on " + pWhat);
		check(pA.fingerprint().equals(pB.fingerprint()) == pExpected, "fingerprint on " + pWhat + " must say the same");
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String sample = big(3 * 1024 * 1024, 'z');
		agree(config(1, sample), config(1, new String(sample)), true, "equal configurations, the sample being another string of the same text");
		agree(config(1, null), config(1, null), true, "no sample");
		agree(config(1, sample), config(1, null), false, "a sample against none");
		agree(config(1, sample), config(1, big(3 * 1024 * 1024, 'y')), false, "a sample that differs in its last character");
		agree(config(1, "short"), config(1, "shorT"), false, "a short sample that differs");
		agree(config(1, sample), config(2, sample), false, "another id");
		check(!config(1, null).sameConfigurationAs(null), "null is not the same configuration");

		// every configured value, one at a time
		final Random random = new Random(7);
		for (int field = 0; field < 17; field++)
		{
			final ConnectorConfig a = config(3, "s");
			final ConnectorConfig b = config(3, "s");
			agree(a, b, true, "baseline " + field);
			switch (field)
			{
				case 0: b.setId("other"); break;
				case 1: b.setType("other"); break;
				case 2: b.setLabel("other"); break;
				case 3: b.setBaseUrl("https://other"); break;
				case 4: b.setAuthType(ConnectorConfig.AuthType.BEARER); break;
				case 5: b.setUsername("other"); break;
				case 6: b.setPassword("other"); break;
				case 7: b.setApiKeyHeader("other"); break;
				case 8: b.setApiKey("other"); break;
				case 9: b.setConnectTimeoutMs(1); break;
				case 10: b.setReadTimeoutMs(1); break;
				case 11: b.setTrust(1); break;
				case 12: b.setActive(false); break;
				case 13: b.setPricing(ConnectorConfig.Pricing.PAID); break;
				case 14: b.setPricingNote("other"); break;
				case 15: b.setLogo(big(1000, (char) ('a' + random.nextInt(26)) == 'x' ? 'y' : 'x')); break;
				default: b.setProperty("a", "changed"); break;
			}
			agree(a, b, false, "field " + field + " changed");
		}
		final ConnectorConfig extra = config(3, "s");
		extra.setProperty("c", "3");
		agree(config(3, "s"), extra, false, "one more property");

		// a value set again after the fingerprint was read is a new value
		final ConnectorConfig moving = config(4, sample);
		final String before = moving.fingerprint();
		check(before.equals(moving.fingerprint()), "the fingerprint is stable");
		moving.setSampleData(big(3 * 1024 * 1024, 'q'));
		check(!before.equals(moving.fingerprint()), "a new sample is in the fingerprint");
		moving.setSampleData(sample);
		check(before.equals(moving.fingerprint()), "and the old one is back");
		check(before.length() < 1000, "a sample of megabytes is not copied into the fingerprint: " + before.length());
		moving.setLogo(big(5000, 'a'));
		check(moving.fingerprint().length() < 1000, "nor is a big logo");

		System.out.println("PASS: ConnectorConfig.sameConfigurationAs agrees with the fingerprint and builds nothing");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
