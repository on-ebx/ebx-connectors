package com.on.ebx.connect.core;

import java.util.Arrays;

/** Completion-only errors must remain visible without becoming replayable record failures. */
public final class CatalogCompletionTest
{
    public static void main(String[] args) throws Exception
    {
        MappingDefinition mapping = new MappingDefinition(190, "Salesforce", "catalog",
            MappingDefinition.Direction.INBOUND, "Scan", "DataCatalog", "DataCatalog", "/root/Asset", "", true);
        mapping.addInput("source", "SF");
        String run = ConnectorService.beginRun();
        try {
            SyncResult discovery = ConnectorService.discoveryFailure(mapping,
                Json.parse("{\"tables\":[{\"name\":\"Account\"}],\"errors\":[\"SECRET vendor detail\"]}"), false);
            check(discovery != null && discovery.hasFailure(), "discovery failure surfaced");
            check(run.equals(discovery.getRunId()) && "190".equals(discovery.getMappingId()), "correlation");
            check(discovery.getRecordKey().isEmpty(), "completion not replayable as record");
            check(!discovery.getMessage().contains("SECRET"), "provider error content not echoed");
            SyncResult written = new SyncResult("Salesforce", "catalog", "IMPORT", "DataCatalog", "/root/Asset", "1", "Account");
            check("PARTIAL".equals(SyncResult.summarize(Arrays.asList(written, discovery)).status()), "mixed run");
            check("PARTIAL".equals(CatalogScanStatus.outcome(outcomeOf(written, discovery)).status), "mixed Source");
            SyncResult bookkeeping = CatalogScanStatus.bookkeepingFailure(mapping, "Persistence failed");
            check(bookkeeping.getStatus() == SyncResult.Status.PARTIAL, "bookkeeping distinct partial");
            check(run.equals(bookkeeping.getRunId()) && bookkeeping.getRecordKey().isEmpty(), "bookkeeping correlation/replay");
            check(bookkeeping.getMessage().contains("not rolled back"), "commits disclosed");
            check("PARTIAL".equals(SyncResult.summarize(Arrays.asList(written, bookkeeping)).status()), "bookkeeping reflected in run");
            check(CatalogScanStatus.connectorMatches("Salesforce", "Salesforce"), "matching connector");
            check(!CatalogScanStatus.connectorMatches("Salesforce", "other"), "stale connector rejected");
            check(!CatalogScanStatus.connectorMatches("Salesforce", null), "unbound source rejected");
            check(!CatalogScanStatus.connectorMatches("", ""), "empty identities rejected");
            SyncResult dry = ConnectorService.discoveryFailure(mapping, Json.parse("{\"errors\":[\"x\"]}"), true);
            check(dry.getStatus() == SyncResult.Status.DRY_RUN && dry.hasFailure(), "dry-run identity preserved");
            check(ConnectorService.discoveryFailure(mapping, Json.parse("{\"errors\":[]}"), false) == null, "success unchanged");
            check(CatalogScanStatus.record(null, null, mapping, outcomeOf(written)) == null, "no session no writes");
        } finally { ConnectorService.endRun(); }
        System.out.println("PASS: catalog discovery/completion correlation, identity guard, dry-run and replay exclusion");
    }

    /** Builds an ImportOutcome the way ConnectorService does (volume lot 7b) - see CatalogScanStatusTest.outcomeOf. */
    private static ImportOutcome outcomeOf(SyncResult... results)
    {
        ImportOutcome outcome = new ImportOutcome();
        outcome.enableCatalogTally();
        for (SyncResult r : results) outcome.addResult(r);
        return outcome;
    }

    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
