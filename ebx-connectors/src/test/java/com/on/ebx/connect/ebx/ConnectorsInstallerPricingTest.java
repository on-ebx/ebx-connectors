package com.on.ebx.connect.ebx;

/**
 * Pure-function checks of the seed table added for the {@code pricing} field: which connector
 * types the installer states as FREE/FREEMIUM/PAID with confidence, that every generic transport
 * and every product a customer already owns (databases, SAP, Salesforce, Kafka...) stays UNKNOWN,
 * and that the two generic-type seeds (EORI = soap, Countries = graphql) are resolved by id like
 * {@code seedCategory} already resolves them. No EBX dependency: {@link ConnectorsInstaller}'s
 * static helpers are plain functions of (id, type).
 */
public final class ConnectorsInstallerPricingTest
{
	public static void main(final String[] args)
	{
		// Confident, publicly documented values (see the CLAUDE.md-style rationale in the PR): open
		// registries and utilities are FREE, a couple of well-known small-free-tier/commercial-use
		// nuances are FREEMIUM, and the named commercial data providers are PAID.
		expect("FREE", ConnectorsInstaller.defaultPricing("gleif"));
		expect("FREE", ConnectorsInstaller.defaultPricing("sirene"));
		expect("FREE", ConnectorsInstaller.defaultPricing("vies"));
		expect("FREE", ConnectorsInstaller.defaultPricing("ban"));
		expect("FREE", ConnectorsInstaller.defaultPricing("inpi"));
		expect("FREE", ConnectorsInstaller.defaultPricing("openfda"));
		expect("FREE", ConnectorsInstaller.defaultPricing("companies-house"));
		expect("FREE", ConnectorsInstaller.defaultPricing("handelsregister"));
		expect("FREE", ConnectorsInstaller.defaultPricing("wikidata"));
		expect("FREE", ConnectorsInstaller.defaultPricing("nominatim"));

		expect("FREEMIUM", ConnectorsInstaller.defaultPricing("opencorporates"));
		expect("FREEMIUM", ConnectorsInstaller.defaultPricing("opensanctions"));

		expect("PAID", ConnectorsInstaller.defaultPricing("google-geocoding"));
		expect("PAID", ConnectorsInstaller.defaultPricing("melissa"));
		expect("PAID", ConnectorsInstaller.defaultPricing("loqate"));
		expect("PAID", ConnectorsInstaller.defaultPricing("dnb"));
		expect("PAID", ConnectorsInstaller.defaultPricing("orbis"));
		expect("PAID", ConnectorsInstaller.defaultPricing("creditsafe"));

		// A customer's own system, or a generic transport that could be pointed at anything: never
		// invented, stays UNKNOWN.
		for (final String type : new String[] { "salesforce", "kafka", "snowflake", "databricks", "sap-odata",
			"jde-ais", "postgresql", "mysql", "sql-server", "rest", "soap", "graphql", "jdbc", "sftp", "s3",
			"ebx", null, "" })
		{
			expect("UNKNOWN", ConnectorsInstaller.defaultPricing(type));
		}

		// seedPricing resolves the two generic-type seeds by id, like seedCategory already does;
		// every other id/type pair falls through to defaultPricing(type).
		expect("FREE", ConnectorsInstaller.seedPricing("EORI", "soap"));
		expect("FREE", ConnectorsInstaller.seedPricing("Countries", "graphql"));
		expect("UNKNOWN", ConnectorsInstaller.seedPricing("SomeOtherSoapConnector", "soap"));
		expect("PAID", ConnectorsInstaller.seedPricing("Melissa", "melissa"));
		expect("UNKNOWN", ConnectorsInstaller.seedPricing("Salesforce", "salesforce"));

		// A note is only ever attached to a value the installer is actually setting (FREEMIUM here,
		// or the two id-based FREE overrides); everything else carries no invented nuance.
		requireNonEmpty(ConnectorsInstaller.seedPricingNote("OpenCorporates", "opencorporates"));
		requireNonEmpty(ConnectorsInstaller.seedPricingNote("OpenSanctions", "opensanctions"));
		requireNonEmpty(ConnectorsInstaller.seedPricingNote("SomeNominatim", "nominatim"));
		requireNonEmpty(ConnectorsInstaller.seedPricingNote("Handelsregister", "handelsregister"));
		requireNonEmpty(ConnectorsInstaller.seedPricingNote("Countries", "graphql"));
		requireNull(ConnectorsInstaller.seedPricingNote("GLEIF", "gleif"));
		requireNull(ConnectorsInstaller.seedPricingNote("Salesforce", "salesforce"));
		requireNull(ConnectorsInstaller.seedPricingNote("EORI", "soap"));

		System.out.println("PASS: ConnectorsInstaller pricing seed table (FREE/FREEMIUM/PAID/UNKNOWN, id overrides, notes)");
	}

	private static void expect(final String pExpected, final String pActual)
	{
		if (!pExpected.equals(pActual))
		{
			throw new AssertionError("expected " + pExpected + " but got " + pActual);
		}
	}

	private static void requireNonEmpty(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			throw new AssertionError("expected a non-empty pricing note");
		}
	}

	private static void requireNull(final String pValue)
	{
		if (pValue != null)
		{
			throw new AssertionError("expected no pricing note, got: " + pValue);
		}
	}
}
