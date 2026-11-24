# Real-time mastering: CDC in, matching on the changed records, golden records out

> **Closed by default (decision of 2026-11-12).** `matching=afterApply|off` on a `cdc` feed and `publish=golden` on an outbound trigger run only when the server administrator adds `cdc` AND `cdcMatching` to the JVM property `-Debx.connectors.preview` (docs/OPERATIONS.md, "Preview functions"); a mapping that carries them is refused when saved and when run while it is closed.

Status, 2026-11-06: **implemented and tested offline (`CdcMatchingTest`, `OutboundQueueTest`), not yet run on an
instance.** The questions of §8 were answered with the proposed defaults. A restart is needed after the install (new
classes, new optional field `OutboundQueue.publish`). Follows the Gartner Market Overview for
MDM Solutions (5 November 2026), trends "Real-time mastering" and "Lakehouse operationalization and bidirectional
sync": match and reconcile golden records as source changes arrive (event-driven CDC), then write the validated golden
records back to the operational applications (CRM, ERP).

## 1. What already exists

| Piece | Where | What it does today |
|---|---|---|
| CDC in | Kafka operation `cdc`, `ConnectorService.runCdc`, [CDC.md](CDC.md) | One Debezium batch per run: upserts through the feed mapping (`runImport`, committed in sub-batches by `BatchCommit`), deletes or flags in one procedure, checkpoint after apply. Validated 17/17 on the RC (lot B) |
| Matching | Match and Merge add-on, its own table trigger | With `matchingExecOnCreation/Update = 1` (AFTER_SUBMISSION) the add-on matches inside **every commit** that touches the table. The module does not call it |
| Golden state | `GoldenRecords` / `MameGoldenRecords` (only class importing the add-on) | Reads a record's state (GOLDEN, MERGED…) and its golden. Used by the product publication (`mergedRecords=exclude`) and the `golden` / `mameState` transformations |
| Out | `ConnectorTableTrigger` → `OutboundQueue` → send, after the commit | Sends a created or modified record to a connector. **No golden filter, no echo rule** |

So the chain CDC → match → publish already runs by itself on a table with an AFTER_SUBMISSION policy and an outbound
trigger. It is not safe as it is, for three reasons (§2).

## 2. Why it is not enough as it is

1. **Matching inside every CDC commit is the bulk-load defect.** On 6.3.0-rc2 to rc4, a bulk write into a table whose
   policy is AFTER_SUBMISSION runs a clustering at each batch commit; under volume one clustering thread reopened the
   table's Lucene index at an older transaction and the table stayed unreadable until a restart (seen with a bulk update
   and with a Staging import). A CDC backlog (a snapshot `op=r` of a whole source table, or a drain after a pause) is
   exactly that load. Small steady batches were not seen failing, but nothing proves they cannot.
2. **A MERGED record is sent out like any other.** When a CDC row is matched and merged, the trigger still sends the
   source record (now MERGED) to the target, instead of its golden. The target receives duplicates that EBX has resolved.
3. **No echo rule.** A CRM change comes in by CDC, the write fires the outbound trigger, and the same values go back to
   the CRM. It converges (an unchanged upsert writes nothing and fires nothing), but costs one round trip per change and
   can ping-pong when the target stamps the record (an updated-at the CDC brings back).

## 3. Proposal

Three additions, each usable alone. Names are provisional.

### 3.1 Matching after the CDC apply, on the changed keys only (`matching` input of `cdc`)

| Value | Behaviour |
|---|---|
| `policy` (default) | Today: the module does nothing, the add-on's own execution mode decides |
| `afterApply` | The module **pauses matching on the table** (`pauseMatching`) for the apply, then **runs one matching process scoped to the keys the batch upserted** (XPath predicate on the primary keys, in chunks), waits for `DONE`, and only then saves the checkpoint |
| `off` | Pauses matching for the apply and runs nothing (a snapshot load; matching run later by hand) |

- With `afterApply` the checkpoint moves only after the matching ended `DONE`. A matching that fails or times out keeps
  the checkpoint: the next run replays the batch (unchanged upserts) and matches the same keys again. At least once
  stays true.
- Deletes are not matched: the add-on removes a deleted record's pairs itself (seen: a pair disappeared when its record
  was deleted).
- The run message gains `matching: 42 record(s), 3 merged, 5 suspect(s), 1.8 s`, read from the process and the
  metadataset (`RecordMetadata` of the touched keys), never from `getDetail()` (it throws once the process ended).
- The add-on stays optional: everything goes through `GoldenRecords.matchingEngine(session, table)`, implemented in
  `MameGoldenRecords`; without the add-on, or on a table without a Match and Merge configuration, `afterApply` and `off`
  are refused before anything is read from Kafka.
- The add-on API used (javap on the 6.3.0 RC `ebx-addons.jar`, public package
  `com.orchestranetworks.addon.mame.service`): `OperationsFactory.getConfigurationOperations()`
  `.getTableConfiguration(table)` (`isPauseMatchingTrigger`, `getDefaultMatchingPolicy().getPolicyCode()`),
  `.pauseMatchingTrigger(table)`, `.resumeMatchingTrigger(table)`; `OperationsFactory.getMatchingOperations(session)`
  `.matchRecords(table, request, policy)`, asynchronous, returning a `MatchingProcess` (`getMatchingStatus()`:
  INITIALIZING, RUNNING, DONE, FAILED, CANCELLING, CANCELLED; `cancel()`). The request carries the XPath filter of the
  chunk: the key predicates the feed resolved (`./code = 'A1'`), joined by `or`.
- Order inside one batch: pause → upserts and deletes → **resume** → matching → verdict. The matching runs with the
  trigger resumed, so nothing depends on whether a paused trigger also blocks an explicit run (not measured).
- A table someone else had paused is left paused, and the module does not resume it.
- A dry run neither pauses nor matches; its message says how many records would be matched.

### 3.2 Golden records only on the way out (`publish` input of the outbound trigger)

| Value | Behaviour |
|---|---|
| `record` (default) | Today |
| `golden` | A GOLDEN record is sent; a MERGED record sends **its golden** instead (once per golden per pass, the queue already collapses rows due together); DELETED and PENDING_APPROVAL send nothing; UNMANAGED is sent as itself |

- The queue re-reads the record at send time (already the case), so the state is the one after matching, not the one
  of the CDC write. The golden is resolved per row in the background pass (`OutboundQueue.Resolver`), then rows that
  resolve to the same golden are sent once. A golden that cannot be read yet leaves the row for a retry (one minute,
  set aside after 5 attempts); the merged record itself is never sent.
- `publish=golden` needs the queued path: with `blocking=true` the trigger's setup reports an error.
- Optional crosswalk: a rule may read `golden()` / `mameState()` (existing transformations) to carry the source key and
  the golden key in the payload, so the target knows which of its records were merged.

### 3.3 No echo to the source (`skipOrigin` on the outbound trigger)

- A CDC run marks its writes (upserts and deletes) with the connector id as their origin, on the run's thread
  (`CdcOrigin`): the import procedures and the triggers they fire run on that thread. Tracking-info strings are left
  as they were.
- `skipOrigin` lists connector ids (or `*`); the trigger does not queue a record written by a CDC run of one of them,
  in either send path. A golden that changed because a matching merged that record is still sent: the merge runs
  after the writes, outside the marker.

## 4. End to end

```
CRM (Debezium) ─► Kafka ─► cdc run ─► upserts / deletes (matching paused)
                                        │
                                        ▼
                         matching process on the upserted keys ─► DONE ─► checkpoint saved
                                        │ merges modify goldens
                                        ▼
                     outbound trigger (publish=golden, skipOrigin=CRM) ─► OutboundQueue ─► ERP, CRM
```

Latency is the CDC schedule plus the matching time plus the queue's settle delay (2 s by default). A minute-level
schedule gives minute-level mastering; the module has no streaming consumer and this note does not add one.

## 5. What is not proposed

- A long-running Kafka consumer (sub-second latency). The run stays a scheduled batch, which keeps the checkpoint and
  lock rules of CDC.md.
- Business objects (a main table and its members) matched as one: one table per `cdc` mapping, as today.
- Automatic merge decisions: the add-on's policy decides MATCH / SUSPECT and auto-merge, the module only triggers it.

## 6. Risks

- **Scoped runs are not measured.** A matching process restricted by an XPath predicate on keys is documented to work
  (`osd:is-not-null(./pk)` for a whole table); its cost on a few keys against a large table is not known. The
  candidate search still reads the whole table's index.
- **The pause is table-wide.** While a CDC run holds `pauseMatching`, a user's creation in the UI is not matched at
  once. The run must resume matching in a `finally`, and at module start for a run killed in between.
- **A run holds the dataspace.** A matching process can answer "dataspace is locked" to another writer; the CDC run
  retries, a user may see the lock.
- **A merge fires the trigger inside the add-on's procedure.** Not measured: whether `handleAfterModify` sees the
  golden's survivorship write. The queue path makes this harmless either way, but `publish=golden` relies on it.

## 7. Tests

- Offline: `CdcApplyPlanTest` gains the touched-keys list; a fake `GoldenRecords` checks that the checkpoint waits for
  the matching, that a failed matching keeps it, and that pause/resume pairs up after an exception; the outbound queue
  test gains the golden substitution, the collapse of two MERGED members into one send, and `skipOrigin`.
- RC acceptance: a scratch table with a policy (generated by the `match-merge` MCP
  server, auto-merge on an exact key), two Debezium events that are duplicates → one golden, both MERGED/GOLDEN read
  back, matching resumed after the run, the outbound mock receives the golden once and nothing for the CDC-origin echo.
  A 2 000-event snapshot with `afterApply` must leave the table readable (the bulk defect).

## 8. Decisions (2026-11-06, the proposed defaults)

1. `matching` defaults to `policy`: existing mappings do not change.
2. 500 keys per matching process (`matchingChunk`), 10 minutes per batch (`matchingTimeoutSeconds`); at the timeout the
   process is cancelled and the checkpoint kept.
3. Suspects are counted in the run message only (`Linked_Field_HasSuspects`); the add-on's screens show them.
4. `publish=golden` sends nothing for a PENDING_APPROVAL (or DELETED) record.
5. Write-back target: not decided. The RC scenario covers both cases: the same connector as the CDC source (the echo is
   skipped) and another one (the golden is sent).
6. Run it on the RC: yes, with the pause as the workaround for the bulk-load defect, and a line in the vendor report.

[Return to the connector user guide](USER-GUIDE.html)
