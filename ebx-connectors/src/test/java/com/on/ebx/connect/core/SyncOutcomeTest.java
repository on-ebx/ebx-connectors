package com.on.ebx.connect.core;

import java.util.Arrays;

/** Contract tests: valid field commits survive, incomplete work is never green. */
public final class SyncOutcomeTest
{
    public static void main(String[] args)
    {
        SyncResult mixed = item(SyncResult.Status.OK);
        mixed.getChanges().add(new MappingResult.Change("./label", "old", "new"));
        mixed.getErrors().add("./count: conversion failed");
        check(mixed.getStatus() == SyncResult.Status.PARTIAL, "field error status");
        check(!mixed.isSuccess() && mixed.hasFailure(), "field error not clean success");
        check(mixed.getChanges().size() == 1, "valid committed change retained");
        check("PARTIAL".equals(SyncResult.summarize(Arrays.asList(mixed)).status()), "run partial");
        check("PARTIAL".equals(CatalogScanStatus.outcome(outcomeOf(mixed)).status), "Source partial");
        SyncResult dry = item(SyncResult.Status.DRY_RUN);
        dry.getErrors().add("conversion failed");
        check(dry.getStatus() == SyncResult.Status.DRY_RUN && !dry.isSuccess(), "dry-run identity preserved");
        check("PARTIAL".equals(SyncResult.summarize(Arrays.asList(dry)).status()), "dry-run summary partial");
        SyncResult skipped = item(SyncResult.Status.SKIPPED);
        check("PARTIAL".equals(CatalogScanStatus.outcome(outcomeOf(skipped)).status), "all skipped Source");
        check("PARTIAL".equals(SyncResult.summarize(Arrays.asList(skipped)).status()), "all skipped run");
        SyncResult good = item(SyncResult.Status.OK);
        SyncResult unchanged = item(SyncResult.Status.NO_CHANGE);
        check("OK".equals(SyncResult.summarize(Arrays.asList(good, unchanged)).status()), "unchanged is successful");
        check("OK".equals(CatalogScanStatus.outcome(outcomeOf(good, unchanged)).status), "unchanged Source");
        SyncResult discoveryError = item(SyncResult.Status.ERROR);
        discoveryError.setMessage("Metadata discovery incomplete: 1 warning(s)");
        SyncResult.Summary summary = SyncResult.summarize(Arrays.asList(good, discoveryError));
        check(summary.ok == 1 && summary.errors == 1 && "PARTIAL".equals(summary.status()), "mixed discovery run");
        check("PARTIAL".equals(CatalogScanStatus.outcome(outcomeOf(good, discoveryError)).status), "mixed discovery Source");
        check("ERROR".equals(SyncResult.summarize(Arrays.asList(discoveryError)).status()), "all failed");
        System.out.println("PASS: field-error partial commits, discovery errors, skips and unchanged outcomes");
    }
    private static SyncResult item(SyncResult.Status status)
    {
        SyncResult r = new SyncResult("fixture", "catalog", "IMPORT", "DataCatalog", "/root/Asset", "id", "fixture");
        r.setStatus(status);
        return r;
    }
    /** Builds an ImportOutcome the way ConnectorService does (volume lot 7b) - see CatalogScanStatusTest.outcomeOf. */
    private static ImportOutcome outcomeOf(SyncResult... results)
    {
        ImportOutcome outcome = new ImportOutcome();
        outcome.enableCatalogTally();
        for (SyncResult r : results) outcome.addResult(r);
        return outcome;
    }
    private static void check(boolean test, String message) { if (!test) throw new AssertionError(message); }
}
