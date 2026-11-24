package com.on.ebx.connect.core;

/** Pure preflight contract. Missing and unbound catalog Sources fail closed. */
public final class CatalogExecutionIdentityTest
{
	public static void main(final String[] pArgs)
	{
		final MappingDefinition scan = CatalogExecutionIdentityTest.mapping("catalog", "/root/Asset", "SF");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(scan, true, true, "Salesforce") == null, "matching connector allowed");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(scan, true, true, "Other") != null, "stale flow blocked");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(scan, true, true, null) != null, "unbound source blocked");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(scan, true, false, null) != null, "missing source blocked");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(scan, false, false, null) == null, "foreign dataset unchanged");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(CatalogExecutionIdentityTest.mapping("query", "/root/Asset", "SF"), true, false, null) == null, "unrelated operation");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(CatalogExecutionIdentityTest.mapping("catalog", "/root/Other", "SF"), true, false, null) == null, "unrelated table");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(CatalogExecutionIdentityTest.mapping("catalog", "/root/Asset", "${param.source}"), true, false, null) == null, "runtime source outside literal recognition");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(CatalogExecutionIdentityTest.mapping("catalog", "/root/Asset", ""), true, false, null) == null, "generic mapping unchanged");
		CatalogExecutionIdentityTest.check(CatalogScanStatus.executionIdentityError(null, true, false, null) == null, "null mapping");
		System.out.println("PASS: catalog scan identity preflight rejects stale/missing/unbound Source; unrelated mappings preserved");
	}

	private static MappingDefinition mapping(final String pOperation, final String pTable, final String pSource)
	{
		final MappingDefinition mapping = new MappingDefinition(190, "Salesforce", pOperation, MappingDefinition.Direction.INBOUND,
			"Catalog scan: SF", "DataCatalog", "DataCatalog", pTable, "", true);
		mapping.addInput("source", pSource);
		return mapping;
	}

	private static void check(final boolean pPass, final String pMessage)
	{
		if (!pPass)
		{
			throw new AssertionError(pMessage);
		}
	}
}
