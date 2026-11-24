# Change data capture: Debezium on Kafka, and JDBC watermarks

> **Closed by default (1.4.2).** The `kafka` / `cdc` capture described below does nothing - no feed, no direct preview, no read of the topic - until the server administrator adds the word `cdc` to the JVM property `-Debx.connectors.preview`;
> closed, every entry (REST, MCP, screen, scheduled task, workflow step, in-JVM contract) answers `[CN-PREVIEW-CLOSED]`. The ordinary Kafka `consume`, `peek` and `publish` are not part of it. Two limits of this function are **not corrected**
> and decide whether to open it: a retention gap is skipped without a word and an unreadable message blocks the flow (audit C3-13, below and OPERATIONS.md section 10), and the Kafka client of capture is created without the module's class loader (C3-15).
> `matching=afterApply|off` and `publish=golden` (see REALTIME-MASTERING.md) need `cdcMatching` in the same property, and `cdcMatching` opens nothing without `cdc`. A run-time input never replaces what the mapping configures on a `cdc` feed (audit C3-04): only `maxEvents` and `maxBytes`, and only downwards; the others are reported as ignored.

Two ways for EBX to follow a source's changes instead of reloading it. Each is **idempotent**: running it
twice leaves the table as running it once. Each is **resumable**: a stopped or failed run is picked up by
the next one.

| | Debezium on Kafka (`kafka` / `cdc`) | JDBC watermark (any JDBC-family connector / `query`) |
|---|---|---|
| Sees | every insert, update and **delete** | inserts and updates (a delete leaves no row to read) |
| Needs at the source | a Debezium connector writing to Kafka (log-based, no query on the database) | an updated-at column or an increasing key |
| Position kept | Kafka offsets per partition, in the mapping's `SynchronizationCheckpoint` (`VENDOR_CURSOR`) | the highest watermark value, in the mapping's `SynchronizationCheckpoint` (`TIMESTAMP`, `MONOTONIC_ID`, `ORDERED_STRING`) |
| Moves when | EBX applied the whole batch | the whole run succeeded |

## 1. Debezium on Kafka

### Set it up

1. A `kafka` connector on the brokers Debezium writes to (`baseUrl` = bootstrap servers, SASL as for any
   Kafka connector, `topic` = the table's topic, e.g. `erp.public.supplier`). The usual Kafka egress and
   secret rules apply. The values must be **JSON** (`JsonConverter`, with or without `schemas.enable`) in the
   standard Debezium envelope (`op`, `before`, `after`, `source`). Do not configure
   `ExtractNewRecordState`: the envelope is what tells a delete from an update.
2. An **inbound feed mapping** on operation `cdc` of that connector, on the EBX table:
   - rules from the **row's columns** (`after` image): `id → ./code`, `name → ./name`...;
   - a `keyPredicate` such as `./code = '${id}'` (required for deletes) and, as for any feed,
     `createIfMissing`;
   - inputs:

   | Input | Default | Meaning |
   |---|---|---|
   | `deleteMode` | `delete` | `delete` removes the EBX record, `flag` sets a field, `ignore` keeps everything |
   | `deleteFlagField` | | with `flag`: the field to set, e.g. `./deleted` (required) |
   | `deleteFlagValue` | `true` | with `flag`: the value written, as an XML Schema string |
   | `maxEvents` | 500 (1..10000) | events per run |
   | `maxBytes` | 8 MB (ceiling 16 MB) | serialized key + value bytes per run |
   | `matching` | `policy` | Match and Merge on the table: `policy` leaves it to the add-on's execution mode; `afterApply` pauses the matching trigger during the writes, then matches the records the batch created or updated, and saves the checkpoint only when that matching ended `DONE`; `off` pauses it and matches nothing. See [REALTIME-MASTERING.md](REALTIME-MASTERING.md) |
   | `matchingPolicy` | the table's default | with `afterApply`: the matching policy code |
   | `matchingChunk` | 500 (1..10000) | with `afterApply`: records per matching process |
   | `matchingTimeoutSeconds` | 600 | with `afterApply`: the wait for one batch's matching; at the timeout the process is cancelled and the checkpoint kept |

3. Run it like any feed (the mapping screen, `POST /import`, `connectors_sync`, a scheduled task). Each run
   applies **one batch**: schedule it, or run it again while the previous run filled `maxEvents`.

### What a run does

```
checkpoint (per mapping) ──► poll from checkpoint+1 on every partition, NO broker commit
                              │ DebeziumKafkaCdcAdapter: envelope → events (r, c, u, d, tombstone), bounded
                              ▼
                       CdcApplyPlan: last event per source key wins inside the batch
                              │   r/c/u → upsert row (after image + __op, __position, __deleted=false)
                              │   d     → delete row (key + before image, __deleted=true) or ignored
                              ▼
            feed mapping (rules, keyPredicate, trust, provenance)   +   delete / flag (one procedure)
                              │
              everything applied and not a simulation? ── yes ──► save checkpoint (optimistic version)
                              │ no
                              └──► checkpoint kept: the next run reads the same events again
```

- **Snapshot** events (`op = r`, Debezium's initial load) are upserts like `c`.
- **Several events of one key** in a batch collapse to the last one. `c` then `d` deletes (or flags);
  `d` then `c` upserts. The key is the Kafka record key, compared without regard to field order or a
  `schema`/`payload` wrapper.
- **Tombstones** (the null value Debezium sends after a delete, for log compaction) do nothing.
- A **delete** finds its record through `keyPredicate` resolved on the key merged with the `before` image.
  PostgreSQL's default replica identity gives only the key columns, which is enough. A record EBX does not
  have is already deleted: nothing to do, no error.
- **Flag mode** sets `deleteFlagField` and keeps the record. It skips a record that is already flagged. When
  a deleted key comes back, its upsert row carries `__deleted = false`: map `__deleted` to the flag field
  (with `boolean`, or `map` for other values) to clear it. The same is true of `__op` (`c`, `u`, `r`) and
  `__position` (`topic:partition:offset`) if you want them in audit fields.
- The run message and the REST/MCP answer say what happened:
  `CDC: 9 event(s) -> 2 upsert(s), 2 delete(s) (4 collapsed, 1 tombstone(s))`. The checkpoint before and
  after is in `watermarkBefore` / `watermarkAfter`, and the reason when it did not move is in `watermarkNote`.
- A **simulation** (dry run) computes everything, writes nothing, and saves no checkpoint. The answer gives
  the checkpoint it would have saved.
- A **direct call** of `cdc` (`connectors_call`, the "call" button) previews the next batch from input
  `checkpoint` (empty = the beginning) as `{summary, upserts, deletes, candidateCheckpoint}`. It commits
  nothing and saves nothing.

### Delivery guarantees

- **At least once.** The checkpoint is saved after the EBX writes, in its own procedure, so a crash between
  the two replays the batch. Replay is harmless: an upsert by key writes the same values (counted
  `NO_CHANGE`), a delete of a missing record and a flag already set do nothing.
- **Resumable.** Any row error, delete error, truncation or run failure keeps the old checkpoint. The
  message says `checkpoint kept, the next run reads the batch again`.
- **Concurrent runs** of one mapping are refused by the run lock, and the checkpoint is saved with an
  optimistic version, so a second node cannot overwrite a newer position.
- **No broker commit.** The consumer group (`<group.id>-cdc-no-commit`) never commits, so resetting a feed is
  deleting its checkpoint row (`SynchronizationCheckpoint`, key = mapping id): the next run starts from the
  earliest retained offset. A checkpoint below the topic's retention is clamped to the first offset still
  there, and the gap is not reported (limit C3-13, not corrected: the function is closed by default, and the run is green after a gap). Size the topic retention above your longest pause.
- **An unreadable message blocks the flow** (limit C3-13, not corrected): a message whose JSON is invalid fails the batch before any write, the position does not move, and the next run reads the same message again until the
  `SynchronizationCheckpoint` row is edited past it.
- **The Kafka client of capture is created without the module's class loader** (limit C3-15, not corrected): from a scheduled task or a workflow step the client resolves its classes with the loader of the calling thread.

### Not covered

- Avro or Protobuf values (Schema Registry): JSON only.
- Debezium transaction metadata (a source transaction is not applied as one EBX transaction: the batch
  is the unit).
- Schema-change events. The schema fingerprint is computed but not compared: a renamed column simply
  stops matching its rule.
- Sub-object (child table) changes: one topic is one table. Map each table's topic with its own feed.

## 2. JDBC watermark

Already in the module since volume lot V2-D. The full reference is in `REFERENCE.md` §2.14.7. In short, on a
feed of a JDBC-family connector (PostgreSQL, Oracle, SQL Server, Snowflake, Redshift...):

| Mapping field | Value |
|---|---|
| `loadMode` | `INCREMENTAL` |
| `checkpointStrategy` | `TIMESTAMP` (updated-at column), `MONOTONIC_ID` (increasing numeric key), `ORDERED_STRING` (fixed-width or ULID key) |
| `checkpointValuePath` | the column of the returned row that carries the watermark, e.g. `LAST_MODIFIED` |
| `checkpointBoundary` | `INCLUSIVE` (rows equal to the checkpoint are read again: safe for a timestamp that several commits can share) or `EXCLUSIVE`; unset = inclusive exactly when the mapping has `unchangedIfEqual` (the re-read rows then cost no write), exclusive otherwise |
| `checkpointInitialValue` | optional first value |
| input `sql` | `select ... from SUPPLIER s where s.LAST_MODIFIED >= ${watermark} order by s.LAST_MODIFIED` |

- `${watermark}` is bound as a **typed JDBC parameter** (a `Timestamp`, a number), never concatenated.
  The first run reads from the floor of the kind (`1900-01-01`, the smallest long), so the same SQL works
  from the start.
- The highest value seen is saved per mapping **only when the run succeeded**: no error, not stopped, not
  truncated. A failed run keeps the old value, and the next run reads the same rows again.
- **Idempotent.** The rows at the boundary that are read again (inclusive mode) are upserts by
  `keyPredicate`, so they write nothing new (with `unchangedIfEqual`, they are not even written).
- A full refresh (`fullRefresh=true` on `connectors_sync` or `POST /sync/{mapping}`) ignores the checkpoint once and saves the new highest
  value.
- Deletes are invisible to a watermark. Use Debezium, or a soft-delete column the query returns and a rule
  maps.

## Tests

- `CdcApplyPlanTest`: events to actions in every delete mode, per-key collapse (create-then-delete,
  delete-then-create, snapshot then update), tombstones, composite keys with and without a schema wrapper,
  then an end-to-end run on an in-memory table. The run crashes after a partial apply and the checkpoint is
  kept; the replay converges; a re-delivered batch is suppressed; a second batch resumes after the checkpoint.
- `DebeziumKafkaCdcContractTest` (existing): envelope normalisation, bounds, the checkpoint-after-apply
  rule, replay suppression.
- `WatermarkTest`, `WatermarkFilterTest`, `WatermarkRunTest`, `JdbcWatermarkTest` (existing): the watermark
  mode.

Not tested offline, and still to check on a live instance: a real Debezium connector (PostgreSQL or MySQL)
on a real broker, the EBX delete and flag procedure on a real table, and a scheduled run that drains a
backlog over several batches.

[Return to the connector user guide](USER-GUIDE.html)
