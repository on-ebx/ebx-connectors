# ON EBX Connectors — Functional and Data Model Reference

*Module `ebx-connectors` · source revision `03260f1 2026-09-14` · generated 2026-09-14 from the source code and the `Connectors.xsd` data model.*


## 1. Overview

### 1.1 Purpose and scope

`ebx-connectors` is a self-contained ON EBX module that connects EBX data to external applications
and services. It provides:

- **connector types** — Java code that talks to one family of systems (public registries, CRMs and
  ERPs, databases, message brokers, files and object stores, cloud data platforms, catalogs, language
  models, generic REST / SOAP / GraphQL), extensible by third-party jars;
- **configured connectors** — one row per concrete endpoint, with URL, authentication, secrets and
  properties;
- **mappings** — declarative, bidirectional translations between an external document and an EBX
  table: inputs, rules, transformations, write policies, sub-objects (child tables), import feeds,
  response mappings;
- **execution entry points** everywhere in EBX — a user service, a workflow script task, a scheduled
  task, a table trigger, a REST API, a webhook, and MCP tools for AI agents;
- **operational governance** — synchronisation log, execution runs and replay, outbound delivery
  state and deltas, field provenance and trust-based conflict arbitration, connector health metrics,
  approval of connector configuration, releases, lineage emission (OpenLineage).

This document is the exhaustive reference: every feature, business rule, default behaviour and
default data of the module (chapters 2 to 4, established from the source code), then every table and
attribute of the configuration model (chapter 5), then the known gaps (chapter 6). The handover guide
(`GUIDE.md`) and the illustrated user guide (`docs/USER-GUIDE.md`) remain the task-oriented companions.

### 1.2 Vocabulary

| Term | Definition |
|---|---|
| **Connector type** | The Java implementation for a family of services (`gleif`, `salesforce`, `jdbc`, `rest`…). A type declares its **operations** and the configuration it needs. Several connectors can share one type. |
| **Connector** | A configured external application: one row of the `Connector` table (id, type, base URL, authentication, secrets, properties). |
| **Operation** | Something a connector can do, with named inputs. Kinds: **read** (one document for one record), **search** (a list of documents), **write** (send a document). |
| **Document** | The JSON exchanged with the service — what an operation returns (inbound) or what is sent (outbound). |
| **Mapping** | The translation between a document and an EBX table for one connector operation and one direction. One row of `Mapping`. |
| **Direction** | `INBOUND`: the returned document is written into EBX. `OUTBOUND`: a document is built from the EBX record and sent. |
| **Input** | A parameter of the operation, computed from the record by an expression (`MappingInput`). |
| **Rule** | A link between a document path and an EBX field, with a transformation pipeline and a write policy (`MappingRule`). |
| **Transformation** | A function applied to a value during a rule (`trim`, `upper`, `map(A=B)`, `fk(/root/T)`…), chained with `|`. |
| **Sub-object** | A child mapping translating each element of a collection of the document into a row of a child table. |
| **Import feed** | An inbound mapping whose operation returns a list: each item creates or updates a record. |
| **Response mapping** | The inbound twin of an outbound mapping, storing what the service answered to a send. |
| **Run** | One execution of one or several mappings, identified by a `runId`; its calls are logged and its failures can be replayed. |

### 1.3 Architecture

| Layer | Package | Role |
|---|---|---|
| SPI | `com.on.ebx.connect.spi` | The contract of a connector type, its operations, configuration and secret resolution; published as `ebx-connectors-spi.jar` for third parties. |
| Engine | `com.on.ebx.connect.core` | JSON, HTTP, expressions, transformations, mapping execution, registry, secrets and vaults, OAuth 2.0, TLS, provenance, conflicts, outbound state, lineage emission. |
| Connector types | `com.on.ebx.connect.connectors` | The built-in connector types. |
| ON EBX integration | `com.on.ebx.connect.ebx` | Installer and seeding, schema extensions, constraints and enumerations, forms and designer, user services, workflow bean, scheduled task, triggers, REST API, MCP server. |

The module is a Tomcat web application deployed under the `/ebx-connectors` context, declared as the
ON EBX module `ebx-connectors` (service keys `ebx-connectors@<Service>`). All configuration and all
operational records are ordinary EBX data in the **`Connectors` dataspace, `Connectors` dataset**,
created and seeded at startup (chapter 4). The module registers 96 connector types. Optional
third-party libraries condition them differently: the `kafka` and `sftp` types are not registered
when their library is missing, whereas the other types that need a driver or SDK (JDBC databases,
messaging services…) stay listed and fail only when an operation is run (chapter 3).

### 1.4 Conventions used in this document

- Class names in parentheses, e.g. *(MappingRunner)*, point at the implementation of a rule.
- System properties are JVM `-D` options of the Tomcat running ON EBX.
- "Default" always states who applies it: the data model (schema default) or the code (run-time fallback).
- Dataspace keys in REST paths take the `B` prefix required by ON EBX Data Services.

## 2. Connectors engine

### 2.1 Architecture overview

| Layer | Classes | Role |
|---|---|---|
| Entry point | `ConnectorService` (singleton) | Record synchronisation, scope sends, deletions, replay, feeds, run ids, lineage buffering |
| Configuration access | `ConnectorRepository`, `ConnectorsPaths` | Reads `Connector`, `ConnectorProperty`, `Mapping`, `MappingInput`, `MappingRule`; writes `SyncLog`, `FieldProvenance`, `Conflict`, `OutboundState`, `SynchronizationCheckpoint` |
| Mapping model | `MappingDefinition`, `MappingRule`, `MappingResult`, `SyncResult` | In-memory mapping, rule, per-record outcome |
| Mapping execution | `MappingEngine`, `Transformations`, `Transformation`, `TransformationContext`, `Expressions`, `Json`, `Xpath` | Compute values, apply write policies, sub-objects, outbound documents |
| Connector instances | `ConnectorRegistry`, `MappingCompatibility`, `MappingSynchronizationPolicy`, `ConnectorStatusEvaluator` | Type registry, cached configured instances, pre-call validation |
| Outbound deltas | `OutboundSelector`, `OutboundScope`, `Hashing` | Scope selection, fingerprints, retry policy |
| Trust | `TrustOracle`, `ConflictResolver` | Provenance lookup, steward decisions |
| Security | `Secrets`, `SecretResolvers`, `Redaction`, `SensitiveDataMasker`, `Egress`, `OAuth2`, `Tls`, `Webhooks`, `Jwt`, `GoogleAuth`, `AwsSigV4`, `CredentialGuide` | Secrets, vaults, masking, allow-list, auth |
| HTTP / protocols | `HttpJsonClient`, `HttpRetryPolicy`, `AbstractRestConnector`, `RestPagination`, `RestPageTraversal`, `RestPayload`, `RestSchemaRefresh`, `ConditionalRequest`, `MultipartBody`, `Templates`, `OpenApiImporter`, `SoapSupport`, `Xml`, `Csv`, `ODataSupport`, `ODataBatchAdapter`, `MessagingTransport` | Shared protocol plumbing for connector types |
| Lineage / catalog | `OpenLineageEmitter`, `DeclaredLineage`, `CatalogOperations`, `RecordProfiler`, `PowerBiScan` | OpenLineage run/job events, catalog documents |
| Operations | `ConnectorOperationsRuntime`, `ConnectorOperationalMetrics`, `RepeatedFailureAlertHook`, `ConnectorConnectionStateService`, `BoundedInspectionRuntime`, `PreviewRequest`, `PreviewResult`, `InspectionPreview`, `MetadataCache`, `MetadataCacheKey` | Metrics, health, connection verification, bounded inspection |
| Framework components (partly unwired) | `SynchronizationCheckpoint*`, `CommittedIncrementalExecution`, `RecoverableExecution`, `DurableExecutionStateStore`, `OperationalExecutionService`, `ConnectorGovernance*`, `ConnectorTemplate*`, `ProductConnectorTemplates`, `DescriptorConfiguration` | Incremental checkpoints, durable executions, governance, templates (see §20 for wiring status) |

Configuration location (`ConnectorsPaths`): dataspace `Connectors`, dataset `Connectors`, model `/WEB-INF/ebx/schemas/Connectors.xsd`, module name constant `ebx-connectors`. `ConnectorRepository.dataset()` returns null when the dataspace or dataset is missing; methods needing it throw `IllegalStateException("The 'Connectors' dataset does not exist: restart the module or create it")`.

---


### 2.2 Mapping model

#### 2.2.1 MappingDefinition (loaded by `ConnectorRepository.mappings()`)

| Property | Source field | Normalisation / default (code) |
|---|---|---|
| id | `Mapping/id` | int |
| connectorId | `Mapping/connector` | as stored |
| operation | `Mapping/operation` | as stored |
| direction | `Mapping/direction` | `INBOUND`/`OUTBOUND`; null or unparsable → `INBOUND` |
| label | `Mapping/label` | `getLabel()` returns `"<connector> / <operation>"` when blank |
| dataspace | `Mapping/dataspace` | trimmed; blank → null. A mapping may leave the dataspace empty: it then applies to a record wherever the dataset exists, workflow child dataspaces included. A TABLE-scoped run (feed import, scope send, catalog scan, enrich of a whole table) needs exactly one dataspace serving the dataset; copies in child dataspaces of that dataspace do not count; several distinct dataspaces → the run fails with `Dataset 'X' … is served by N dataspaces (…): name the dataspace on the mapping`, and the upsert refuses it. The upsert (`flows.put`, `connectors_mapping_upsert`) never fills a blank dataspace (§4.2); the boot repair does not either |
| dataset | `Mapping/dataset` | trimmed; blank → null ("any dataset") |
| tablePath | `Mapping/tablePath` | trimmed; null → "" |
| responseRoot | `Mapping/responseRoot` | trimmed; null → "" |
| active | `Mapping/active` | null → true |
| deleteOperation | `Mapping/deleteOperation` | trimmed; null → "" (read only if the field exists in the model) |
| maxAttempts | `Mapping/maxAttempts` | null → 5; otherwise `max(1, value)` |
| retryDelayMinutes | `Mapping/retryDelayMinutes` | null → 30; otherwise `max(0, value)` |
| loadMode | `Mapping/loadMode` | `FULL`/`INCREMENTAL`; null/invalid → `FULL` (read only if field exists) |
| checkpointStrategy | `Mapping/checkpointStrategy` | `SynchronizationStrategy` enum; blank/invalid → null; forced to null when loadMode is FULL |
| checkpointInput, checkpointValuePath, checkpointInitialValue | same-named fields | trimmed; null → "" |
| checkpointBoundary | `Mapping/checkpointBoundary` | `INCLUSIVE`/`EXCLUSIVE` (any case); anything else → "" = automatic (inclusive when `unchangedIfEqual` is set) (read only if the field exists - volume lot V2-D, §2.14.7) |
| parentId | `Mapping/parent` | FK string parsed as int; blank → null; unparsable → -1 (read only if `parent` field exists: "rev 3 fields") |
| collectionPath, parentLinkField, childKeyFields, childFilter | same-named fields | trimmed; null → "" |
| deleteMissing | `Mapping/deleteMissing` | null → false |
| itemsPath, keyPredicate | same-named fields | trimmed; null → "" |
| createIfMissing | `Mapping/createIfMissing` | null → true |
| provenance | `Mapping/provenance` | null → true (read only if the field exists in the model - volume lot 5) |
| unchangedIfEqual | `Mapping/unchangedIfEqual` | trimmed; null → "" (read only if the field exists in the model - volume lot 5) |
| batchSize | `Mapping/batchSize` | null/≤0 → 1000; clamped to [1, 10000] (read only if the field exists in the model - volume lot 6) |
| logEveryRecord | `Mapping/logEveryRecord` | null → false (read only if the field exists in the model - volume lot 6) |

Derived rules:
- `isChild()` = parentId != null.
- `isFeed()` = keyPredicate non-empty and not a child.
- `getChildKeyFields()` = `childKeyFields` split on `,`, trimmed, empty entries dropped.
- `appliesTo(ds, dataset, table)` = tablePath equals exactly, and (dataspace null or equal), and (dataset null or equal).
- Children are attached to their parent only when the child is active and the parent id exists in the same load (`ConnectorRepository.mappings()`). An inactive child is silently not attached.
- Inputs: every `MappingInput` row whose `mapping` FK parses to a loaded id; `addInput` ignores blank names, stores `name.trim()` → expression (null → ""). Later rows with the same name replace earlier ones (LinkedHashMap).
- Rules: `MappingRule` rows sorted by `position` ascending; rows with `active = false` skipped (null counts as active); position null → 0; policy null/invalid → `IF_EMPTY`.
- The whole configuration (all mappings, inputs, rules) is re-read from EBX on every call to `mappings()`, `mapping(id)`, `mappingsFor(...)`, `mappingFor(...)`; there is no mapping cache.

#### 2.2.2 MappingRule

- `sourcePath`, `targetPath`, `transformation` trimmed, null → "". `policy` null → `IF_EMPTY`.
- Inbound: source = JSON path in the (response-rooted) document, target = EBX path relative to the record.
- Outbound: source = EBX path, target = JSON path of the document built.
- Empty source path: the rule value is produced by the pipeline alone (constant); the raw value is null.

#### 2.2.3 Mapping selection (`ConnectorRepository.mappingFor`)

1. Candidates = `mappingsFor(dataspace, dataset, table)` (active, root, `appliesTo`) with same connector id, operation and direction.
2. Candidates bound to a dataspace (`dataspace != null`) win over generic ones.
3. More than one candidate at the winning level → `IllegalStateException`: `Several <DIRECTION> mappings for <connector>/<operation> on <table>: <id> (<label>), ... Name the mapping by its id (mappings=<id>) instead of the connector and operation.`
4. None → null. `ConnectorService.syncRecord(connector, operation, direction)` turns null into status `ERROR` with message `No <DIRECTION> mapping for <connector>/<operation> on <table>`, and the ambiguity exception into status `ERROR` with that message (neither is written to `SyncLog` by this overload).

---


### 2.3 Synchronising one record (`ConnectorService.syncRecord(session, record, mapping, dryRun, origin)`)

#### 2.3.1 Sequence

1. A `SyncResult` is created: connector, operation, direction name, dataspace = record's home key name, tablePath, recordKey = `PrimaryKey.format()`, recordLabel = `getLabel(Locale.getDefault())`. `runId` = `currentRunId()`, `mappingId` = mapping id.
2. **prepare**:
   - Connector instance from `ConnectorRegistry.connector(id, repo)` (§6).
   - Operation looked up by exact name in `connector.getOperations()`; missing → `ConnectorException("Connector <id> has no operation '<op>'")`.
   - `MappingCompatibility.requireCompatible` (§6.3) and `MappingSynchronizationPolicy.requireValid` (§6.4).
   - Inputs: each mapping input resolved with `Expressions.resolve(expression, record, params = null, context)` then trimmed (§5). Because params are null here, `${param.x}` always resolves to "" in record synchronisations.
   - Required inputs (`operation.getRequiredInputs()`) that are blank → status `SKIPPED`, message `Input '<name>' is empty on this record`; the connector is not called.
   - OUTBOUND: the document is built (`MappingEngine.buildOutbound`, §4.4); build errors are copied to the result; `outboundDocument` = JSON text.
     - Dry run: status `DRY_RUN`, message `would send to <connector>/<op> with inputs {…} (not sent)`, one change `document` whose new value is the JSON truncated to 500 characters plus `...`; the connector is **not** called.
     - Otherwise `connector.execute(ctx, op, inputs, payload)`; the response mapping is looked up with `mappingFor(... INBOUND)` on the same dataspace/dataset/table/connector/operation. Ambiguity → no response mapping applied, kernel warning `[connectors] <message>`.
   - INBOUND: `connector.execute(ctx, op, inputs, null)`. Exception: dry run on connector id `Kafka` with operation `consume` executes `peek` instead (no offset commit). A null document → status `NO_DATA`, message `No data returned for {inputs}`. A feed reads through `Connector.readRows`; a connector that must not forget a row before EBX holds it (Kafka) asks the sink for the engine's report (`RowSink.onHandled`): after each batch the engine reports which delivered items are written (`HandledListener.handled(from, to, notWritten)`, items numbered from 0 as delivered), and tells the connector when the rows end (`RowSink.endOfRows`, which flushes the last partial batch) and what to say to the run (`RowSink.notice`). A dry run reports nothing (`onHandled` answers false), so a simulation commits nothing whatever operation it ran. A watermark filter passes the report on in the source's own numbering.
3. **apply** (inside one `ProgrammaticService` procedure on the record's dataspace, session tracking info `Connector <connector>/<op>`):
   - No response mapping or no document: OUTBOUND → status `OK`, message `Sent <n> error(s)` (n = build errors); INBOUND → `NO_DATA`, `No document`.
   - `setAllPrivileges(true)`; trust context set (§8).
   - `MappingEngine.applyInbound` stages values on the record (§4.2).
   - Dry run: children simulated on values only (no lookup, no write); status `DRY_RUN` if any change, else `NO_CHANGE`; message = `MappingResult.summary()` + ` (not written)` when changes exist. No modification is committed (the procedure performs no `doModifyContent`).
   - Real run: `doModifyContent` only if the main record has changes; then `MappingEngine.applyChildren` (§4.3) in the same procedure; status `OK` when the main record or sub-objects changed, else `NO_CHANGE`; message = summary + `, <n> sub-object change(s)` when sub-objects changed.
   - A failed procedure rethrows the captured exception (or `ConnectorException` with the EBX full message).
4. After apply (not dry run, and changes or conflicts exist): the record is re-read, `recordProvenance` and `logConflicts` are called (§8).
5. Any exception → status `ERROR`, message = `Redaction.redact(exception message or toString)`.
6. `durationMs` = elapsed wall time.
7. Not dry run:
   - `SyncLog` row written (§9) with the given origin.
   - OUTBOUND root mapping: status `ERROR` → `OutboundState` FAILED with the message; status other than `SKIPPED` with a non-null outbound document → `OutboundState` SENT with `sha256(document)` and the document (§10).
   - Lineage recorded (§12).
8. Dry runs write nothing: no log, no outbound state, no provenance, no conflict, no lineage.

Important rule-level behaviour: rule errors (bad transformation, unknown field, type conversion) are collected in `SyncResult.errors` but **do not change the status**; a record with rule errors still ends `OK` or `NO_CHANGE`. The errors appear as `ERROR <text>` lines in the `changes` log column.

#### 2.3.2 Inside an existing procedure (`syncRecordInProcedure`)

Used by the table trigger. Same prepare and apply (dry run false) but in the caller's transaction. It does **not** set runId, write `SyncLog`, update `OutboundState`, record provenance, log conflicts or emit lineage. TRUSTED conflicts are computed (visible on the result) but not persisted by the engine.

#### 2.3.3 Many records (`syncRecords`)

Reads up to `pMax` records of an EBX `Request` into memory, then one `syncRecord` (one procedure) per record, in request order.

#### 2.3.4 Statuses (`SyncResult.Status`)

| Status | Set when (code) |
|---|---|
| `OK` | inbound/import: at least one change written (main record or sub-object); outbound: call succeeded (no response mapping, or response mapping produced changes); remote deletion succeeded |
| `NO_CHANGE` | call succeeded, nothing written; also the synthetic scope summary row of `sendScope` |
| `NO_DATA` | connector returned null (inbound); no response mapping/document for inbound; feed returned no item |
| `SKIPPED` | required input blank; feed item not found and `createIfMissing=false`; replay row whose mapping or record no longer exists |
| `ERROR` | any exception (connector, validation, procedure, predicate); no mapping or ambiguous mapping (connector/operation overload) |
| `DRY_RUN` | simulation with something to show |

`isSuccess()` = OK, NO_CHANGE or DRY_RUN.

`MappingResult.summary()`: `<n> change(s), <m> skipped[, <e> error(s)][, <c> conflict(s)]`.

Aggregate status used by the workflow bean (`ebx/SyncTargets.summarise` + `ConnectorScriptTaskBean`, not engine code): OK/DRY_RUN/NO_CHANGE count as `ok` (OK also as `changed`), NO_DATA as `noData`, SKIPPED as `skipped`, everything else as `errors`. Status = errors 0 → (ok 0 → `NO_DATA`, else `OK`); errors > 0 → (ok 0 → `ERROR`, else `PARTIAL`). Consequences: a run whose only results are SKIPPED gives `NO_DATA`; the `sendScope` summary row (NO_CHANGE) counts as ok.

#### 2.3.5 Run ids (`ConnectorService`)

- Format `R<yyyyMMdd-HHmmss>-<NNNN>`, NNNN = JVM-wide atomic sequence modulo 10000, zero-padded to 4 digits (local server time).
- `beginRun()`: flushes any unfinished lineage buffer of the thread, creates a new id, installs a per-thread lineage buffer.
- `endRun()`: removes the thread's run id and buffer, flushes lineage (one event per mapping).
- `currentRunId()`: returns the thread's id; when none, creates one **and stores it in the thread-local** (see Discrepancies §23).

---


### 2.4 Mapping engine (`MappingEngine`)

Values travel as XML-Schema strings so that EBX performs typing (`setValueFromXsString`).

#### 2.4.1 Computing values (`compute`)

1. Root = document when `responseRoot` is empty, else `Json.at(document, responseRoot)`.
2. For each rule in position order:
   - raw = null when source path empty, else `Json.toJava(Json.at(root, sourcePath))` (§5.4).
   - value = `Transformations.apply(pipeline, raw, context)`.
   - xs = `asXsString(value)`: Boolean → `true`/`false`; BigDecimal → `stripTrailingZeros().toPlainString()` (`1.0` → `1`, `1E+3` → `1000`); List → non-null items joined with `", "`; other → `String.valueOf`.
   - null or empty xs → rule ignored (no write, no skip count). **A mapping cannot clear a field.**
   - target normalised: `./x` kept; `/x` → `./x`; `x` → `./x`.
   - Fallback rule: when several rules target the same field, the **first non-empty value in position order wins**, together with that rule's policy.
   - Exception → error `<targetPath>: <message>`.

#### 2.4.2 Staging values on a record (`applyValues`) — write policies

For each computed target:
- Target node missing or not a terminal value → error `<target>: not a field of <table path>`.
- Record being created (`existing == null`): the value is always written, whatever the policy.
- Existing record: current value formatted as XS string; "empty" = null or whitespace-only.

| Policy | Behaviour on an existing record |
|---|---|
| `IF_EMPTY` (default) | current non-empty → skipped (counted) |
| `ALWAYS` | written unless equal to the current XS value |
| `TRUSTED` | current empty → written. Current non-empty → provenance looked up (`TrustOracle.provenanceOf(record, fieldPath)`); current trust = provenance trust, or `MANUAL_TRUST` = 100 when unknown; source trust = context trust (negative → 0). If no oracle is set **or** source trust < current trust → conflict recorded (path, current value, proposed value, current connector or null, current trust) and skipped; otherwise written |

- For every policy, a proposed value equal to the current XS value is skipped.
- Written values produce a change `(path, oldXs, newXs)`; staging exceptions → `<target>: <message>`.
- Equal trust is allowed to overwrite ("a source refreshes itself").

#### 2.4.3 Inbound sub-objects (`applyChildren` / `applyChild`)

Called with the parent document **before** the parent's response root; the parent's root is applied first, then for each active child mapping (errors per child caught as `<collectionPath>: <message>`):
1. Child table = `parentRecord.getContainer().getTable(child.tablePath)` (same dataset as the parent).
2. `parentLinkField` normalised; empty → error `<collectionPath>: parentLinkField is not set`, child ignored.
3. Collection = `Json.at(parentRoot, collectionPath)`: array → each element; object → a single element (an empty `collectionPath` returns the root itself, i.e. one element); scalar or absent → no element.
4. For each element (label prefix `<collectionPath>[<index>]`):
   - Values computed with the child's rules (child `responseRoot` applies to the element).
   - Matching existing row only when `childKeyFields` is non-empty: XPath predicate `link = parentKey` and, for each key field, `field = computed value`, plus ` and (<childFilter>)`. If any key field has no computed value, no lookup is made → a new row is created. First matching row wins.
   - Predicate literal typing (`MappingEngine.predicate`): for `xs:int`, `xs:integer`, `xs:decimal`, `xs:boolean` fields that are not foreign keys the value is unquoted and validated (`true|false|0|1`, decimal regex, integer regex), otherwise `IllegalArgumentException("Invalid typed predicate value")`; every other type is quoted with `'` and single quotes doubled.
   - Not found → new occurrence: link field set to the parent primary key (XS string), all values written (no policy applied), created. Changes are prefixed `<collection>[i]+ <path>`.
   - Found → policies applied as in §4.2; `doModifyContent` only when changes exist. Changes prefixed `<collection>[i] <path>`; errors prefixed `<collection>[i] `.
   - Grandchildren applied recursively on the element and the created/updated row.
5. `deleteMissing = true`: every row of the child table with `link = parentKey` (and child filter) not matched in this run is deleted (`doDelete`, not cascading); change `(<collectionPath> -, <row label>, "deleted")`. When the collection is absent from the document, **all** linked rows are deleted.
6. Sub-object changes never produce provenance (their paths do not start with `./`).
7. **In a batched import feed (batch size above 1) the child rows are read once per segment, not once per element (`ChildRowPreload`, `ChildRowGroups`, audit C-11).** Before the write procedure of a segment of parents, one read per child mapping and 200 existing parents (`link='a' or link='b' …`) loads the rows already linked to them; step 3's lookup and step 5's selection are then answered in memory, with the XPath's own rules (the first row, never for a null or empty value, none when the key is incomplete), and every row the procedure creates or changes is entered in the groups as it is written (a rolled-back attempt is forgotten). Used only when the XPath it replaces is a plain equality: a direct child mapping of the feed that owns its table (no other mapping of the feed's tree uses it), a stored top-level `xs:string` link field (or a foreign key) and key fields, no `childFilter`. A parent that was not read, a key held by two rows, a parent that received a row moved from another parent, a nested sub-object, a new parent whose table is not keyed by one auto-increment field (a row left behind by a deleted record could carry its key), the single-item path and the dry run keep one XPath per element. `-Debx.connectors.feed.childPreload=false` turns it off.

Dry run of sub-objects (`ConnectorService.simulateChildren`): for each element, computed values are listed as changes `<collectionPath>[i] <path>` with null old value; no lookup, no key matching, no deletion simulated; recursive.

#### 2.4.4 Outbound document (`buildOutbound`)

- Empty JSON object; for each rule:
  - Source path empty → raw null. Otherwise normalised; node absent → error `<source>: not a field of the record`.
  - Raw record value: null → null; List → list whose Date/Number items are converted to XS strings; Boolean, BigDecimal, Integer kept typed; terminal value → XS string; non-terminal → `String.valueOf`.
  - Pipeline applied; null → skipped (counted).
  - Written with `Json.set(document, targetPath, Json.fromJava(value))`: String → JSON string, BigDecimal/Integer/Number → JSON number, Boolean → JSON boolean, List → array, JsonNode kept.
  - Change `(targetPath, null, xs)` recorded (these changes are not persisted by `syncRecord`; only errors are copied).
- Children: rows of the child table where `link = parent primary key` (and child filter); each row built with the child's rules (with a fresh `TransformationContext` on the child row) and appended to `<collectionPath>[]`; change `(<collectionPath>, null, "<n> element(s)")`. No row → no array key is created.
- `Json.set` path rules: dotted steps create intermediate objects (a non-object intermediate is replaced); a last step ending in `[]` appends to an array (created if absent); indexes like `a[0]` are not interpreted by `set` (the key `a[0]` is created literally).

#### 2.4.5 Response mapping

An outbound call applies, to the connector's answer, the INBOUND mapping of the same connector, operation, dataspace, dataset and table (§3.1). Its changes, conflicts and provenance are those of a normal inbound application on the record. Without it the answer is discarded.

---


### 2.5 Expressions and paths

#### 2.5.1 Input expressions (`Expressions.resolve`)

| Syntax | Behaviour |
|---|---|
| literal text | copied as is |
| `${./Group/field}` or `${Group/field}` or `${/Group/field}` | record field value as XS string (`node.formatToXsString`); non-terminal node → `String.valueOf`; unknown path, null value or read error → "" |
| `${param.name}` | value of `name` in the params map; missing or no map → "" |
| `${key|pipeline}` | pipeline applied to that placeholder's value (first `|` outside parentheses and single/double quotes separates key and pipeline) |
| `text |> pipeline` | the whole assembled text is **trimmed**, then the pipeline is applied. Detection uses the **last** occurrence of `|>` in the template |
| nested `${... ${inner} ...}` | the inner placeholder is resolved first (placeholder end found by brace matching that honours nested `${` and quotes) |
| unterminated `${` | the rest of the template is copied literally |

- Pipeline result converted with `Transformations.text`; null → "".
- A failing pipeline throws `IllegalArgumentException("Expression pipeline '<pipeline>' failed: <message>")` → the synchronisation ends in `ERROR`.
- Record synchronisations call it with params null (so `${param.*}` = ""); feeds (`importFrom`) call it with record null and params = run-time inputs (so `${./field}` = "").
- Transformation context for record inputs: dataset = record container, record, session.

#### 2.5.2 Run-time inputs of a feed (`ConnectorService.runtimeInputs`)

- Text split on `;`, `\r` or `\n`; each part `name=value` (name and value trimmed).
- A name is kept only if an input expression of the mapping contains `${param.<name>}` or `${param.<name>|` (`readsParam`); parts without `=` or not read are added to the "ignored" list.
- Returns null when the text is blank or nothing remains.

#### 2.5.3 Feed key predicate (`resolvePredicate`)

- Blank template → "".
- Every `${path}` replaced by `Json.at(item, path)`: value node → `asText()`; object/array → JSON text; missing → "". Single quotes in the value are doubled (`Xpath.escape`); the template must provide the surrounding quotes, e.g. `./Code='${code}'`.

#### 2.5.4 JSON paths (`Json`)

- `at(root, path)`: dotted steps; `name[i]` integer index; `name[*]` or `[*]` projection: with no remaining steps returns the array itself, otherwise returns an array of the remaining path applied to each element (missing → JSON null element). A missing step, JSON null or non-array under `[i]` → null. Blank path → root. Keys containing dots are not addressable.
- `toJava`: text → String; number → BigDecimal; boolean → Boolean; array → List (recursive); object → its JSON text (String).
- `flatten(root, max)` (designer): objects recurse; empty array → `(path, "[]", "array")`; array of scalars → `(path, first 3 values joined + ", ...", "array(<size>)")` and `(path[0], value, type)`; array of objects → recurse into `[0]`; depth limit 12; types `null|number|boolean|string`.
- `write(null)` → `"null"`.

#### 2.5.5 XPath literals (`Xpath`)

`escape` doubles single quotes (null → ""); `quote` wraps in single quotes.

---


### 2.6 Connector instances and pre-call validation

#### 2.6.1 Registry (`ConnectorRegistry`)

- Types: 56 built-in classes registered in the constructor (GLEIF, Sirene, Google Geocoding, generic REST, Salesforce, Snowflake, Databricks, Power BI, Collibra, SAP OData, JDE AIS, D&B, Azure Blob, BAN, BigQuery, Companies House, Creditsafe, Dynamics 365, Dynamics Business Central, GCS, HubSpot, JDBC, PostgreSQL, RabbitMQ, H2, SQL Server, Azure SQL, Azure Service Bus, Oracle Database, Loqate, Melissa, MySQL, OffeneRegister, OpenCorporates, OpenSanctions, Oracle Fusion, NetSuite, SuccessFactors, SAP Ariba, Orbis, S3, ServiceNow, SharePoint, VIES, Webhook, Workday, LLM, SOAP, GraphQL, EBX, OpenLineage, Chat webhook, JMS, ActiveMQ, INPI, openFDA), plus `KafkaConnector` and `com.on.ebx.connect.connectors.SftpConnector` registered "quietly" (skipped if their library is absent), plus every `META-INF/services/com.on.ebx.connect.spi.Connector` entry (a broken service file is ignored).
- `registerType(class)`: instantiates with the public no-arg constructor and keys the class by `getType()`; a later registration with the same type replaces the earlier one. Failure → `IllegalArgumentException("Connector class <name> needs a public no-arg constructor: ...")`.
- `describeType(type)`: fresh instance; unknown → `ConnectorException("Unknown connector type '<type>'; known: [...]")`.
- `connector(id, repo)` (synchronized: all instance lookups in the JVM are serialised):
  1. Configuration re-read from EBX on every call (`repo.connector(id)`).
  2. Missing → cache entry removed, `ConnectorException("Connector '<id>' is not declared in the Connectors dataset")`.
  3. Cached instance returned when the cached `ConnectorConfig.fingerprint()` equals the fresh one (fingerprint covers id, type, label, baseUrl, authType, username, password, apiKeyHeader, apiKey, timeouts, trust, active, logo and all properties). Any change in EBX is taken into account at the next call, without restart.
  4. Inactive → `Connector '<id>' is inactive`.
  5. Configuration problem (secret resolution) → `Connector '<id>': <problem>`.
  6. `Egress.checkBaseUrl` refusal → `Connector '<id>': outbound call to <host> refused: not in ebx.connectors.allowedHosts (<list>)`.
  7. `Redaction.remember(config)`; new instance; descriptor validation (`ConnectorConfigurationValidator`) → `Connector '<id>' configuration is invalid: <property|configuration>: <message>; ...` (redacted); `configure(config)` RuntimeException → `Connector '<id>': <message>`.
  8. Instance and config cached.
- `invalidate()` clears all cached instances (called by configuration import).

#### 2.6.2 ConnectorConfig as built by `ConnectorRepository.toConfig`

| Config value | Source | Rule |
|---|---|---|
| id, label, type, baseUrl, username, apiKeyHeader | Connector fields | as stored |
| authType | `authType` | null or unknown → `NONE` |
| password, apiKey | `password`, `apiKey` | `Secrets.reveal` (§11.1); failure sets problem `password: <msg>` / `apiKey: <msg>` |
| connectTimeoutMs / readTimeoutMs | fields | null → 0 → setter default 10000 / 30000 (any value ≤ 0 maps to the default) |
| active | `active` | null → true |
| logo | `logo` | XS string, only when the field exists |
| trust | `trust` | only when the field exists; null → 50; setter clamps to 0–100 |
| properties | `ConnectorProperty` rows with `connector = id` | value passed through `Secrets.resolveIfReference`; failure → problem `property <key>: <msg>` |

`getProperty(key, default)` returns the trimmed value, or the default when absent or blank. `isPlaceholder`: null, blank, `TO_BE_SET` or `A_RENSEIGNER`.

#### 2.6.3 Direction compatibility (`MappingCompatibility`)

- If the connector descriptor declares directions for the operation, the mapping direction must be among them; otherwise the convention applies: `WRITE` operations are OUTBOUND, all others INBOUND.
- Failure message: `Mapping direction <DIR> is not compatible with <type>/<operation> (descriptor permits [..] | <KIND> operations are <DIR>)`.
- Enforced before every record sync, deletion and feed import.

#### 2.6.4 Load-policy validation (`MappingSynchronizationPolicy`)

- Operation absent from the connector descriptor → `Operation <type>/<op> has no descriptor` (the SPI default descriptor lists every operation with strategy FULL, so this only affects connectors overriding `getDescriptor()`).
- FULL: the operation must support `FULL`, else `Synchronization strategy FULL is not supported by <type>/<op>; supported: [...]`.
- INCREMENTAL: mapping must be INBOUND (`Incremental load is only valid for inbound mappings`), must have a strategy (`Incremental load requires a checkpoint strategy`), and the strategy must be supported by the operation.
- Enforced before every record sync, deletion and feed import. Validation only: checkpoint injection is not performed by `ConnectorService` (§20).

#### 2.6.5 Configured-connector status (`ConnectorStatusEvaluator`)

`INACTIVE` (not active) → `UNAVAILABLE` (type not registered, descriptor availability UNAVAILABLE, or instantiation failure) → `MISCONFIGURED` (descriptor validation fails) → `AVAILABLE`.

---


### 2.7 Transformations (`Transformations`)

#### 2.7.1 Pipeline syntax

- Steps separated by `|` at parenthesis depth 0. Quotes are **not** honoured by the step splitter: a `|` inside quotes but outside parentheses splits the step.
- Step name = text before the first `(`, trimmed, lower-cased (names are case-insensitive).
- Arguments = text between the first `(` and the last `)` (missing `)` → to end of step), split on `,` outside single quotes; each argument trimmed, then one pair of surrounding single quotes removed. Unquoted leading/trailing spaces are therefore lost.
- Empty pipeline → value unchanged. Empty steps ignored.
- Unknown name → `IllegalArgumentException("Unknown transformation '<name>'")`.
- Values flowing through: String, BigDecimal, Boolean, Integer, List or null. Most string steps first convert with `text()`: BigDecimal → plain string without trailing zeros; List → non-null items joined with `", "`.
- Extension: `Transformations.register(name, Transformation)` (name trimmed, lower-cased; replaces a built-in with the same name). `names()` lists registered names.

#### 2.7.2 Built-in transformations

| Name | Arguments (default) | Behaviour and edge cases |
|---|---|---|
| `trim` | – | `text().trim()`; null → null |
| `upper` / `lower` | – | `Locale.ROOT` case change |
| `capitalize` | – | lower-cases everything, upper-cases the first character and any character following a space, `-` or `'` |
| `left` | n (0) | first n characters; value length ≤ n returned as is. Without argument a non-empty value becomes "" (then ignored as empty) |
| `right` | n (0) | last n characters; same edge case |
| `substring` | start (0), end (length) | both clamped to [0, length], end ≥ start |
| `replace` | a (""), b ("") | literal `String.replace`, not a regex |
| `regex` | pattern (`.*`), group (1 if the pattern has groups, else 0) | `Matcher.find()` (not a full match); no match → null; an invalid group index throws |
| `vatfr` | – | keeps digits; 14 digits (SIRET) → uses the first 9; 9 digits → `FR` + 2-digit key + SIREN, key = `(12 + 3 × (SIREN mod 97)) mod 97`; any other length → original value returned unchanged |
| `digits` | – | removes every non-digit |
| `prefix` / `suffix` | p ("") | concatenation; null → null |
| `default` | v (null) | value null, whitespace-only string or empty list → v |
| `constant` | v (null) | always v, ignores the input |
| `nullifempty` | – | null, whitespace-only string or empty list → null |
| `nullif` | v ("") | `text(value)` equals v exactly (no trim) → null |
| `isodate` | – | first 10 characters; shorter than 10 → null |
| `isodatetime` | – | first 19 characters with the space at position 10 replaced by `T`; shorter than 19 → null |
| `date` | inFormat (`dd/MM/yyyy`), outFormat (`yyyy-MM-dd`) | `SimpleDateFormat`, `Locale.ROOT`, non-lenient; blank → null; unparsable → exception (rule error) |
| `map` | pairs `k=v` separated by `;` (arguments split on `,` are also scanned) | key compared case-insensitively with the trimmed value; value = text after the first `=` (trimmed); `*=v` = fallback; pairs without `=` ignored; no match and no fallback → null |
| `join` | separator (`", "`) | only for lists (non-list value returned unchanged); null or empty items skipped; result empty → null. Extra unquoted arguments are glued back with `,` (an unquoted `join(, )` yields `,`) |
| `first` | – | first element of a list (empty list → null); non-list unchanged |
| `count` | – | list → Integer size; null → 0; any other value → 1 |
| `boolean` | – | Boolean kept; null or blank → null; `true`, `1`, `yes`, `oui`, `y`, `o`, `active` (case-insensitive) → true; **any other text → false** |
| `number` | – | BigDecimal kept; `,` replaced by `.`, every character other than digits, `.` and `-` removed; empty, `-` or `.` → null; invalid (e.g. `1.234,56` → `1.234.56`) → exception |
| `round` | scale (0) | BigDecimal (or parsed text) `setScale(scale, ROUND_HALF_UP)`; non-numeric → exception |
| `fk` | path, dataspace?, dataset? | blank value, no context or no dataset in context → null. Table resolved by `table()` (§7.3); not found → `IllegalArgumentException("fk: table <path> not found (add the dataspace and dataset: fk(path,dataspace,dataset))")`. Record looked up by `PrimaryKey.parseString(value.trim())`; exists → trimmed value, else **null** (silently not written) |
| `lookup` | path, field, result, dataspace?, dataset? | blank value / no dataset → null; table not found → `lookup: table <path> not found (add the dataspace and dataset: lookup(path,field,result,dataspace,dataset))`. XPath `<field>='<value>'` (always quoted, quotes doubled); first row returned by EBX; no row → null; empty `result` → formatted primary key of the row, else the XS value of `result` |
| `golden` | path, dataspace?, dataset? | blank value, no context or no dataset in context → null. Table resolved by `table()` (§7.3); not found → `IllegalArgumentException("golden: table <path> not found (add the dataspace and dataset: golden(path,dataspace,dataset))")`. Value is a record's formatted primary key, looked up by `PrimaryKey.parseString(value.trim())`; not found → value unchanged; found → `TransformationContext#getGoldenRecords().golden(record)` (§2.7.4): the same record's own formatted key unless it is `MERGED` under Match and Merge, in which case the golden record's formatted key (chain of up to 10 hops; unmanaged table or add-on absent → unchanged) |
| `mdmstate` | path, dataspace?, dataset? | blank value / no dataset → null; table not found → `IllegalArgumentException("mdmState: table <path> not found (add the dataspace and dataset: mdmState(path,dataspace,dataset))")`. Record looked up by primary key; not found → null; found → `TransformationContext#getGoldenRecords().state(record)`: `GOLDEN`, `MERGED`, `UNSET`, `PENDING_APPROVAL`, `DELETED`, or `UNMANAGED` (table not under Match and Merge, or the add-on is absent from the classpath) |
| `hash` | – | `Hashing.sha256(text(value))`; ignores `a`/`c`. Same digest for the same text every time; not reversible |
| `now` | pattern (`yyyy-MM-dd'T'HH:mm:ss`) | ignores the incoming value; `new SimpleDateFormat(pattern, Locale.ROOT).format(new Date())` — the current server time, not injectable/mockable. Default pattern is the same 19-character shape `isodatetime` produces, so a landing `_receivedAt` field populated by `now()` needs no follow-up `isodatetime` step |
| `runid` | – | `ConnectorService.currentRunId()`; ignores `v`/`a`/`c`. Reflects `ConnectorService.beginRun()` when a run is in progress on this thread, else a fresh id is minted on the spot |
| `concat` | path1, path2, ... (at least one required) | zero arguments → `IllegalArgumentException("concat: at least one path argument is required, e.g. concat(id,name)")`, even with no context. `c == null` or `c.getDocumentRoot() == null` → null (the context has no document root when `MappingEngine.compute` never ran, mirroring `fk`'s no-dataset guard). Otherwise, for each path argument: `Json.at(context.getDocumentRoot(), path)` → `Json.toJava(node)` → `text(...)`, null/missing treated as `""`; every piece joined with a **fixed** `\|` separator (not configurable, not an argument slot) — the same shape `LandingFlow.concatCoalesce` builds in generated SQL (`COALESCE(expr,'') \|\| '\|' \|\| COALESCE(expr,'')`), so `concat(...)\|hash` on a feed-based landing mapping can build the same `_sourceId`/`_rowHash` a SQL-based one does |

#### 2.7.3 Table resolution for `fk` / `lookup` (`Transformations.table`)

1. Explicit dataset argument → `DatasetLocator.find(repository, dataspace or null, dataset, path)` (ebx package); without a dataspace the dataset must be served by exactly one dataspace (copies in child dataspaces of that dataspace do not count), otherwise `null` (not found, as for an unknown dataset).
2. Else the table path exists in the dataset being synchronised → that table.
3. Else the first foreign key of the current model (depth-first, max depth 12) whose target table path equals the requested path; its container home (dataspace) and container reference (dataset) are followed; with a different home but no container reference, the dataset of the same name in that home is used. Not found → null.
4. **Cached per run (volume lot 5, task 4):** when `pContext.getCache()` is non-null (`TransformationRunCache`, set by `ConnectorService.importDocument` for the whole run), the resolved table - including a "not found" result - is cached per `(path, dataspace, dataset)` and steps 1-3 above run at most once per distinct triple per run, never once per row. With no cache (a single-record `syncRecord`, a test), resolution runs every call as before.

#### 2.7.4 TransformationContext

Dataset, record (null for a creation or a feed without match), session, plus trust data (oracle, source trust, source connector) set by the service before inbound application. Outbound child rows get a context without trust. `getGoldenRecords()` (package-private) answers `GoldenRecords.get()` — the Match and Merge add-on backed implementation, or a no-op one when `ebx-addons.jar` is absent — unless `setGoldenRecords(...)` injected a fake; that seam exists so `golden`/`mdmstate` are testable without the add-on or a repository. `getDocumentRoot()`/`setDocumentRoot(JsonNode)` carry the document (or, for a child row, the child row itself) that `MappingEngine.compute` is currently evaluating rules against — set by `compute()` itself right after resolving `responseRoot`, before its rules loop, so it covers root-level rows and child rows alike with no extra wiring; behind `concat(...)`. `getCache()`/`setCache(TransformationRunCache)` (package-private, volume lot 5, task 4): the per-run cache described in §2.14.3's last bullet; `null` outside a cached run path.

#### 2.7.5 Per-run caches (volume lot 5, task 4, `TransformationRunCache`)

One instance per `ConnectorService.importDocument` run (created before the per-item loop, shared by every `TransformationContext` of that run through `setCache`, dropped with the run - no static state). It caches, all keyed by plain strings so no EBX object is ever used as a map key:
- a resolved `AdaptationTable` per `(path, dataspace, dataset)` (§2.7.3), including a negative "not found" result (`hasTable`/`table`/`putTable` distinguish "never looked up" from "looked up, not found" - a plain `null`-returning `Map.get` cannot);
- a parsed `TransformationPipeline` per pipeline string (`pipeline(text)`) - the steps and arguments `Transformations.apply` used to re-split/re-unquote on every call (`splitPipeline`/`splitArgs`/`unquote`, now package-private and shared with `TransformationPipeline.parse` so a cached parse behaves identically to the uncached one it replaces);
- a compiled `Pattern` per regex string (`pattern(regex)`), used by the `regex(...)` transformation;
- a bounded (default 50,000 entries, access-order `LinkedHashMap`/LRU) memo of `lookup(...)` results per `(table identity, field, result, value)` (`lookup(key)`/`putLookup(key, value)`; a `null` result - "no matching record" - is cached too, distinguished from "not cached" the same way as the table cache).

---


### 2.8 Trust, provenance and conflicts

#### 2.8.1 Trust assignment (`ConnectorService.trustify`)

Source trust = `ConnectorConfig.trust` of the mapping's connector (config default 50; unreadable connector → 50). The oracle is the `ConnectorRepository`. Applied to inbound record synchronisations (response mapping for outbound) and to feed items; `TrustOracle.MANUAL_TRUST` = 100 for any value without provenance.

#### 2.8.2 Provenance (`ConnectorRepository.recordProvenance` / `provenanceOf`)

- Requires the `FieldProvenance` table to exist in the model; otherwise nothing is recorded and every value counts as manual (100).
- Written after a non-dry-run record sync or feed item that produced changes or conflicts, in a separate procedure (all privileges), for each change whose path starts with `./` (main record fields only; sub-object changes excluded).
- **Opt-out (volume lot 5, task 2):** when the mapping's `provenance` property is `false` (default `true`), `recordProvenance` returns immediately and writes nothing — the single choke point for `syncRecord`'s inbound/outbound path, the feed-import path (`ConnectorService.importItem`) and `ConflictResolver`'s accept path alike. `pMapping == null` (e.g. a conflict whose mapping id no longer resolves) always records, matching the property's own default. Intended for a mapping whose target table already carries its own lineage (a landing table's `_loadId`/`_sourceSystem`).
- One row per (dataspace, dataset, tablePath, recordKey, fieldPath): upserted, looked up by primary key. **`FieldProvenance`'s primary key is now this same 5-field natural key (volume lot 5, task 2)**, not the former auto-increment `id` — `ConnectorRepository.fieldProvenanceKey` builds it with `AdaptationTable.computePrimaryKey(Object[])` (never a hand-joined string) and both `provenanceOf` and `recordProvenance` use `lookupAdaptationByPrimaryKey` instead of an XPath `Request` that used to scan a table growing to rows × changed fields (measured 2026-09-23: two thirds of a landing load's per-row time). See §5's `FieldProvenance` table and §6 for what the key change implies on an upgraded repository.
- Values: connector id, mapping id (string, may be null), trust = connector trust at write time, value = new value truncated to 2000 characters, writtenAt = now, source = where the value was read (document feed only, when the column exists).
- **Confidence envelope (Gartner lot A, docs/DOCUMENTS.md):** a document whose response root carries a numeric `_minConfidence` makes every rule whose source path has a `_confidence` below it (or none) a proposal: an OPEN conflict whose `comment` is `confidence 0.62 < 0.80 - <source>`, never a write, whatever the rule policy and also on a record being created.
- Provenance is recorded for every policy (IF_EMPTY, ALWAYS, TRUSTED).
- Failure → kernel error `[connectors] provenance not recorded: <message>` (redacted); the synchronisation is not affected.
- `provenanceOf(record, fieldPath)` reads the row by primary key; trust null → 50. The dataspace is part of the key: provenance written in a child dataspace is not found once the record is read from another dataspace (it then counts as manual).

#### 2.8.3 Conflict logging (`logConflicts`)

- Requires the `Conflict` table; written after non-dry-run record syncs and feed items (not by `syncRecordInProcedure`).
- Deduplication: skipped when an `OPEN` or `APPLYING` conflict exists with the same dataset, tablePath, recordKey, fieldPath and proposedValue (dataspace not part of the check).
- Row: status `OPEN`, dataspace, dataset, tablePath, recordKey, recordLabel (≤ 255), fieldPath, currentValue, currentConnector (null = manual), currentTrust, proposedValue, proposedConnector (connector id), proposedTrust (connector trust), mapping id, detectedAt = now. Current and proposed values are not truncated by code (model limit 2000).
- Failure → kernel error `[connectors] conflicts not logged: <message>`.

#### 2.8.4 Steward decisions (`ConflictResolver`)

- Status constants: `OPEN`, `APPLYING` (an acceptance that did not finish), `ACCEPTED`, `REJECTED`. `APPLYING` is an additive value of the `Conflict/status` enumeration; no key changed.
- `list(status, dataspace, dataset, table, recordKey)`: status defaults to `OPEN`, and `OPEN` also lists what is `APPLYING` (so that an unfinished acceptance is seen and decided); any other status lists that status only; non-blank filters are combined; sorted by `detectedAt` descending. Empty list when the dataset or the Conflict table is missing. `countOpen` = size of the OPEN list.
- `recordOf(conflict)`: dataspace → dataset → table → primary key; null when any step is missing.
- Concurrency: decisions on the same conflict id are serialised by a 256-stripe JVM lock and the live status is re-read under the lock (single-node protection only, per the code comment).
- `accept(session, conflict, comment)`:
  1. Conflict not found, or neither `OPEN` nor `APPLYING` → `Outcome(false, "conflict <id> is not open")`.
  2. Record missing → `Outcome(false, "record <key> no longer exists in <table>")`.
  3. The conflict is claimed first: one transaction that checks the stored status (`OPEN`, or `APPLYING` found by a retry) and sets it to `APPLYING`. Then the proposed value is written into the record in a procedure (null proposed → field set to null; else `setValueFromXsString`); tracking info `Connector conflict accepted: <proposedConnector>`. Write failure → the claim is given back (status as found) and `Outcome(false, <EBX message>)`; if giving it back also fails the message adds that the conflict stays `APPLYING`.
     A conflict found `APPLYING` (the process died, or the closing failed, after the claim) is settled by the next accept: when the record already holds the proposed value (`holds`: both as XML Schema text, no value = empty value) the write is skipped and only provenance and closing remain, otherwise it is claimed again and applied. A reject of an `APPLYING` conflict is refused when the record already holds the proposed value, and closes it as `REJECTED` otherwise.
  4. Provenance recorded with the proposing connector and the trust it had when proposing (`proposedTrust`, else current connector trust), when that connector still exists.
  5. Conflict closed as `ACCEPTED` (status checked in the transaction against the one claimed). Closing failure → `Outcome(false, <message> (the record was changed; the conflict stays APPLYING: accept it again to close it))`.
  The three steps are not one transaction; what makes the sequence safe is that a failure between them leaves a conflict that says so and that the retry is idempotent. Not covered: two EBX nodes deciding the same conflict at the same instant (the claim transaction is the only guard; the JVM lock is single-node).
- `reject(session, conflict, comment)`: status check (`OPEN`, or `APPLYING` as above), then closed as `REJECTED` (record untouched).
- Close and claim: re-check the expected status inside the procedure (`IllegalStateException("conflict <id> was already <status> by another decision")`); sets status, comment truncated to 500 when non-blank, `resolvedBy` = session user id and `resolvedAt` = now (when those fields exist). Messages: `accepted: <field> = <proposed>` / `rejected: <field> keeps <current>`.
- The record write (step 3) and the conflict close are separate transactions.

---


### 2.9 Sync log (`ConnectorRepository.log`)

- Called for every non-dry-run record sync, remote deletion, feed item, feed header on error/no data, never for dry runs.
- The retention purge does not run here: it is a background task (§2.9.1), so writing a log line costs the writer nothing else.
- New `SyncLog` row in its own procedure (all privileges):

| Field | Value |
|---|---|
| timestamp | now |
| connector, operation | from the result (not truncated by code) |
| direction | `INBOUND`, `OUTBOUND` or `IMPORT` |
| dataspace, tablePath | from the result |
| recordKey, recordLabel | truncated to 255 (`cut`: first max−3 characters + `...`) |
| status | status name |
| message | redacted, truncated to 2000 |
| changes | `changesAsText()` (one `path: old -> new` line per change, then `ERROR <text>` per error), redacted, truncated to 4000; **null** when `-Debx.connectors.log.storeChanges=false` |
| durationMs | int |
| user | session user id, or null |
| origin | caller-supplied origin string |
| runId, mapping | only when the `runId` field exists in the model |

- Logging never breaks a synchronisation: nothing is thrown. A line that could not be written - an exception, or an insert refused by EBX (e.g. a value longer than the model's maxLength), which does not throw but answers a failed `ProcedureResult` - is reported and `log` answers `false` (§2.22.2).
- After the insert attempt, `ConnectorOperationsRuntime.observe(user, result, now)` feeds operational metrics (§17).
- **Since volume lot 6, a BATCHED import feed's items (batchSize > 1, non-dry-run - §14.4) DO carry runId and mappingId** (`ConnectorService.prepareFeedItem` sets both on every item's `SyncResult` before any write is attempted), so they now show up in `logOfRun`/`connectors_run_get` alongside the mapping's other calls. The pre-lot-6 `importItem` path (a dry run, or `batchSize <= 1`) is untouched by this lot and still carries neither, per the original §14.3 note below - this is the one behavioural difference between the two paths worth knowing before relying on `logOfRun` to see everything a feed run touched.
- **Batched import logging (volume lot 6, task 3, `ConnectorService.commitBatch`)**: for a non-dry-run import feed with `batchSize > 1` (the default: `Mapping.batchSize` unset or > 1), one `SyncLog` row is written **per batch** instead of one per record: `recordKey` = `"<firstKey>..<lastKey>"` (the batch's first and last predicate/key strings, cut to 255 like any other row; the single key with no `..` when they are equal; **null** when no item in the batch ever had a non-blank key - e.g. an always-create feed with no `keyPredicate`), `recordLabel` = `"batch <n> (<size> item(s))"`, `message` = `"batch of <total>: created=<c> updated=<u> noChange=<n> errors=<e> rejected=<r>"` (`ImportBatchSummary`; "rejected" = `SKIPPED`, i.e. `createIfMissing=false` with no match), `status` = `ERROR` when every attempted write in the batch failed, `PARTIAL` when some errored or were rejected but at least one write succeeded, else `OK`; `changes` is empty (the per-field detail lives in the individual ERROR/CONFLICT rows below, not in the batch row). **An individual record still gets its own `SyncLog` row** when its own status is `ERROR`/`PARTIAL` or it produced a conflict (`SyncResult.hasFailure()` or a non-empty conflicts list) - exactly the rows `connectors_replay` needs; an `OK`, `NO_CHANGE` or `SKIPPED` record's own line disappears into the batch row's counts instead. Setting the mapping's `logEveryRecord = true` restores one `SyncLog` row per record exactly as before this lot (no batch row is written in that mode). `batchSize <= 1` or a dry run always uses the pre-lot-6, one-row-per-record path regardless of `logEveryRecord`. `connectors_log_search`/`SyncLogForm` read `recordKey` as a plain string field either way, so a batch row (including one whose `recordKey` is null) displays and filters exactly like any other row - a batch row is just not what you get back from a search for one specific record's key (search the individual ERROR/CONFLICT row, or the batch's `runId`, for that).

#### 2.9.1 Retention (`core/Retention`)

**On by default, 90 days** (owner decision of 2026-10-06). With `-Debx.connectors.log.retentionDays` unset the age is
90; `0`, negative or unreadable turns the purge off: nothing is deleted and no thread is started (startup log:
`retention is off…`, an unreadable value is named).

While it is on, one daemon task (`ebx-connectors-retention`, declared through
`BackgroundWork`, §2.22.3) is started at repository startup with a system-user session and stopped
with the module. First pass 5 minutes after startup, then one pass every
`ebx.connectors.retention.intervalMinutes` (default 60) after the end of the previous one. The purge
never runs on the thread of whoever writes a log line, and does not sort the log.

| Table | Rows that may go | Age read from | Own property (default N; `0` keeps the table for ever) | What a purged row means |
|---|---|---|---|---|
| `SyncLog` | all | `timestamp` | `ebx.connectors.retention.syncLogDays` | the run can no longer be replayed nor its calls listed |
| `Conflict` | status other than `OPEN` and `APPLYING` (a row without status is kept) | `detectedAt` | `ebx.connectors.retention.conflictDays` | the decision is no longer listed; an open conflict, or one whose acceptance did not finish, is never deleted |
| `ExecutionRun` | status `DONE`, `PARTIAL`, `ERROR`, `STOPPED` or `INTERRUPTED`; never `RUNNING`, never a row without status (the rows of `RecoverableExecution`); only when the model has the `status` field | `updatedAt` | `ebx.connectors.retention.executionRunDays` | `connectors_run_get` no longer has an `execution` block for it; a partial run can no longer be resumed from its saved position |
| `Publication` | all | `endedAt` (a row without it is kept) | `ebx.connectors.retention.publicationDays` | the report can no longer be read back by run id |
| `OutboundState` | status `DELETED` only (never `SENT` or `FAILED`) | `lastAttemptAt` | `ebx.connectors.retention.outboundStateDays` (**no default: kept unless set**) | the trace of a remote deletion is gone |
| `FieldProvenance` | rows whose record no longer exists (`Retention.Existence` `ABSENT` or `HOME_GONE`), never `UNKNOWN` | `writtenAt` | `ebx.connectors.retention.provenanceDays` (**no default: kept unless set**) | nothing readable: the record is gone |

A per-table age alone does not turn the purge on; a per-table age that is set to something that is
not a number **keeps** that table (and the startup log names it).

**Provenance and outbound states.** Both hold the *current* state of a record (who last wrote a field; what was
last sent and its fingerprint) and stay true however old: deleting an old provenance row of an existing record changes
the next TRUSTED decision on that field, deleting a `SENT` outbound state sends the record again. So age alone never
deletes them. What goes, when its own property is set: `DELETED` outbound states, and provenance rows whose record is
certainly gone - the dataspace key no longer resolves (branch or version), or the dataspace is open, the dataset and
table are served and the primary key is not in the table. A dataspace that is closed, a dataset that is not found, a
table that does not load or a key that does not parse leave the row alone. The provenance pass reads at most
`maxRowsPerPass` old rows (skipping the ones an earlier pass kept, kept in memory, so a restart starts again at the
beginning), looks each record up by primary key (one table resolution per pass and per (dataspace, dataset, table)),
then deletes the orphans in batches. `ConnectorOperationalMetrics`, `SynchronizationCheckpoint` and the
governance tables are one row per connector or mapping and do not grow with activity.

**One pass**, table after table (`Retention.pass`, never throws):

1. Read at most `ebx.connectors.retention.batchSize` rows (default 500, 1..5000) with the predicate
   `date-less-than(<date field>,'<limit yyyy-MM-ddTHH:mm:ss.SSS>')` (+ ` and (<status restriction>)`),
   **outside any procedure**, no sort. Each row is then re-checked in Java (`Target.mayDelete`: date
   strictly before the limit, status rule): a row is deleted only when the predicate and that check
   agree, so no reading of the predicate by EBX can delete a row that is too young, open or running.
2. Delete them in one procedure that reads nothing (all privileges, tracking info
   `connectors:retention:<Table>`); its `ProcedureResult` is read (`Bookkeeping`, kind
   `retention purge`): refused, the table is left until the next pass.
3. Pause 100 ms, look at `BackgroundWork.isStopped()`, and continue while the last batch was full and
   the pass has deleted fewer than `ebx.connectors.retention.maxRowsPerPass` rows of that table
   (default 50 000, at least one batch). A backlog larger than that is absorbed by the following
   passes: 1.2 million rows a day per table with the defaults.
4. If EBX refuses the age predicate (a `RuntimeException` on the request), a WARN says so once and
   every later read uses the previous method - the table sorted by date ascending, stopping at the
   first row that is not old enough - which costs a sort per pass.
5. A pass that deleted something logs `retention: deleted <n> SyncLog, <n> Conflict…` at INFO.

Tests: `RetentionTest` (the default and the switch; per-table ages, `0` and unreadable values; the opt-in tables; the provenance rule and its resume position; clamps; the batches of
a pass; which rows may go; the predicates). **Not run offline and to be checked on an instance
before relying on it:** the `date-less-than` predicate on a dateTime field as EBX 6.3 evaluates it
(taken from the instance's XPath reference; the Java re-check and the sorted fallback bound what a
surprise there could do), and the duration of a pass over a multi-million-row `SyncLog`.

#### 2.9.2 Record-mode runs (`core/RecordRun`, `core/RecordBookkeeping`, audit C-13)

A run that synchronises records one at a time (`syncRecords`, `sendScope`, `replay`, a workflow step through
`ConnectorService.beginRecordRun`) opens a `RecordRun` on its thread. It changes how often the module reads and writes, never what
is sent or written to the records: each record still has its own remote call and its own procedure on its dataspace.

- **Read once per run**: the mappings (`ConnectorRepository.mappings()` reads Mapping, MappingInput and MappingRule; an outbound
  record used to read them again to find the mapping of the answer, a replay once per row) and the configuration of each
  connector (record, properties, secret references), through the `ConfigSource` the registry already had and the
  `TransformationRunCache` a feed uses (C-34). A mapping or a connection edited while the run goes on is taken into account by
  the next run.
- **Written in groups**: sync log lines (`ConnectorRepository.logBatch`), provenance (`recordProvenanceBatch`), outbound states
  (`markOutboundBatch`, one state per record and mapping per group) and metrics (`ConnectorOperationalMetrics.recordAll`: one
  read and one write of the connector's row for the whole group, as if observed one by one) wait up to
  `ebx.connectors.record.bookkeepingBatch` records (default 100, `1` = after each record) and are written by one transaction per
  kind, at the end of the run in a `finally`. Each line keeps the time its call ended and the HTTP retries counted for it.
  A group that is refused is retried row by row, so only the row at fault is lost; its `SyncResult` carries
  `bookkeepingLost` exactly as before. Conflicts are not grouped.
- **What can differ**: the lines of a group appear together; a JVM crash in the middle of a run loses the bookkeeping of the
  records of the group in progress (an outbound record whose state was lost is sent again by the next `unsent`/`changed` scope,
  the same at-least-once the module already has for a lost state).
- Not changed: one remote call per record, no parallelism, the OpenLineage emission, the transaction on the record.

Tests: `RecordBookkeepingTest` (group sizes, order and times, row-by-row fallback, a state once per group, metrics read and
written once and equal to the one-by-one result), `MappingSelectionTest` (the mapping chosen from a list read once is the one
`mappingFor` chose). **Not run offline and to be checked on an instance**: that a run of N records performs a handful of
transactions on the Connectors dataset instead of about 4 per record.

#### 2.9.2 Runs (`logOfRun`)

Rows with `./runId = <runId>`; empty list when the dataset, the runId or the `runId` field is missing. No sort is applied.

---


### 2.10 Outbound state, scopes, deletions and retry

#### 2.10.1 OutboundState (`ConnectorRepository.markOutbound`)

- Only when the `OutboundState` table exists and the record key is non-null. Primary key (mapping id, recordKey).
- Upsert in its own procedure:
  - Always: mapping id, recordKey (cut 255), recordLabel (cut 255, only when non-null), dataspace/dataset/tablePath **of the mapping** (null for a generic mapping), status, lastAttemptAt = now, lastRunId.
  - `FAILED`: attempts = previous + 1 (first failure 1), lastError = error cut to 2000. `lastSentAt`, hash and lastDocument keep their previous values.
  - `SENT` / `DELETED`: attempts = 0, lastError = null, lastSentAt = now, hash set when provided, lastDocument = document cut to 8000 when provided (DELETED keeps the previous hash and document).
- Failure → kernel error `[connectors] outbound state not saved: <message>`.
- Written by: `syncRecord` (outbound root mapping, not dry run: ERROR → FAILED; any status other than SKIPPED with a built document → SENT, hash = SHA-256 of the full JSON document), `deleteRemote` (DELETED or FAILED).
- `outboundStates(mappingId, status)`: rows of a mapping, optionally filtered by status.

#### 2.10.2 Scopes (`OutboundScope`)

`parse`: null/blank → `ALL`; upper-cased, `-` → `_`; `DELTA` and `DATASPACEDELTA` (so `dataspaceDelta`) → `DATASPACE_DELTA`; any other unknown value → `IllegalArgumentException` from `valueOf`.

`ConnectorService.sendScope(session, repo, mapping, table, request, scope, max, dryRun, origin)`:
- Scope other than `ALL` on a non-outbound mapping → `ConnectorException("Scope <SCOPE> only applies to outbound mappings (mapping <id> is inbound)")`.
- Records selected by `OutboundSelector.select`, then one `syncRecord` each, then one `deleteRemote` per deletion (lineage recorded for deletions when not dry run).
- When some records were unchanged or waiting: one extra result, not logged, status `NO_CHANGE`, record key "", label `scope <SCOPE>`, message `<u> unchanged since the last send, <w> failed and waiting for the retry delay`.

| Scope | Selection (`OutboundSelector`) |
|---|---|
| `ALL` | every record of the request (or the whole table when the request is null), up to max |
| `UNSENT` | records whose state is absent or has no `lastSentAt`; a FAILED never-sent record whose retry is not allowed counts as waiting; others count as unchanged |
| `CHANGED` | FAILED state → sent if retry allowed, else waiting; no state or no `lastSentAt` → sent; otherwise sent when `fingerprint(record)` ≠ stored hash, else unchanged. Then, when the mapping has a `deleteOperation`, every `SENT` state whose record no longer exists in the table is a deletion (with the last document) |
| `FAILED` | iterates `FAILED` states (not the request): retry not allowed → waiting; record exists → sent; record gone and `deleteOperation` set → deletion. Stops when records + deletions reach max |
| `DATASPACE_DELTA` | see §10.3 (the request/predicate is ignored) |

- Max applies to records for ALL/UNSENT/CHANGED/DATASPACE_DELTA; deletions of CHANGED and DATASPACE_DELTA are not bounded by max.
- The `SENT` states of `CHANGED` are streamed (`ConnectorRepository.forEachOutboundState`): each is read for its record key and dropped, and the label and last document are read only for a state whose record is gone, so the sweep no longer holds every state ever sent in memory (audit C-14). `outboundStates` (a list) remains for the screens, REST and MCP tools that show them.
- Fingerprint (`OutboundSelector.fingerprint`) = SHA-256 of the JSON of the document the mapping would build now (sub-objects included). Hex lower-case; if SHA-256 is unavailable, `Integer.toHexString(text.hashCode())`.
- Retry allowed (`retryAllowed`): attempts < `maxAttempts` **and** (no lastAttemptAt or now − lastAttemptAt ≥ `retryDelayMinutes` × 60000 ms). Exhausted attempts are reported as "waiting" forever until a replay or a successful send resets attempts.

#### 2.10.3 Dataspace delta

- Parent home of the table's dataspace must be a version (the initial snapshot of a child dataspace); otherwise `ConnectorException("Dataspace <name> has no initial snapshot (a reference dataspace cannot be compared)")`.
- Dataset absent in the snapshot → every record of the table (up to max), no deletion.
- Otherwise `DifferenceHelper.compareInstances(current, snapshot, false)` on the mapping table: created occurrences, then modified occurrences (up to max); when `deleteOperation` is set, each occurrence present only in the snapshot is a deletion carrying the snapshot record and the last document from `OutboundState`.

#### 2.10.4 Remote deletion (`deleteRemote`)

- Result: operation = `deleteOperation`, direction `OUTBOUND`, dataspace/table of the mapping, key and label of the deletion (label defaults to the key).
- Delete operation must exist on the connector (`Connector <id> has no delete operation '<op>'`), be OUTBOUND-compatible, and pass the load-policy check.
- Inputs: `key` = record primary key; when a snapshot record exists, every mapping input resolved on it (trimmed); then every scalar field of the last document sent, when not already present (`asText`). Payload = last document (may be null).
- Dry run: `DRY_RUN`, message `would call <op> with {inputs} (not sent)`.
- Success: `OK`, message `deleted remotely (<op>)`, change `(remote, <key>, deleted)`, state `DELETED`.
- Exception: `ERROR` with redacted message; state `FAILED` (not in dry run).
- Logged unless dry run.
- Because `lastDocument` is truncated to 8000 characters, a longer document cannot be parsed back; the deletion then runs without the document fields.

#### 2.10.5 Replay (`ConnectorService.replay(session, repo, runId, dryRun, origin)`)

- Iterates the `SyncLog` rows of the run, keeps `status = ERROR`, requires non-null mapping id and record key, and processes each (mapping, key) pair once.
- Mapping no longer exists → `SKIPPED`, `mapping <id> no longer exists`.
- Dataset located with `DatasetLocator.find(repo, <log dataspace>, mapping.dataset, tablePath)`; record found → `syncRecord` on the current record with the current mapping.
- Record gone, mapping OUTBOUND with a `deleteOperation` → `deleteRemote` with the last document from `OutboundState` (no snapshot record).
- Otherwise → `SKIPPED`, `record no longer exists`.
- RuntimeException on a row → `ERROR`, `Replay of this record failed: <message of the cause>` (`Redaction.failure`: the message of the outermost throwable that has one, redacted and cut at 1000 characters; for a mapping on ephemeral samples the fixed sample text instead). The redacted stack trace goes to the kernel log (`ConnectorService.logFailure`). The synchronisation screen, the conflict manager, the OpenAPI import screen, the mapping designer, the REST routes and the MCP tools show failures the same way.
- Replayed calls receive the caller's current run id, not the replayed run's id. Deletions performed by replay are not reported to lineage.

---


### 2.11 Secrets and security

#### 2.11.1 Secret values (`Secrets`)

`reveal(stored)` for `Connector/password` and `Connector/apiKey`:
1. Null or empty → returned as is.
2. `PasswordTypeEncryption.decrypt(stored)`; a non-empty result is the clear value (decryption errors and `LinkageError` ignored).
3. If the (decrypted or raw) value is a reference → resolved (§11.2).
4. Not decrypted and not a placeholder:
   - unless `-Debx.connectors.secrets.allowClear=true` (default: not true; changed 2026-10-06) → `IllegalStateException(Secrets.CLEAR_REFUSED)`: "a secret must be entered in the EBX user interface (so that EBX stores it encrypted) or referenced from a vault:, env: or file: reference: a value stored in clear is refused because ebx.connectors.secrets.allowClear is not true. Re-enter the password or API key in the Connector form, or replace it with a reference". The message never contains the value.
   - With `allowClear=true` used as is; kernel warning logged once per JVM: `[connectors] a connector secret is stored in clear text ... accepted because -Debx.connectors.secrets.allowClear=true is set`.
- `isStoredInClear(stored)`: the same test without resolving or returning anything. `isSecretProperty(key)`: `Redaction.isSecretKey(key)` and none of the words url, uri, endpoint, header, name, type, scope, path, file, query, max, param, prefix, style... in the key (so `oauth.clientSecret`, `tls.keystorePassword`, `sasl.jaas.config` are secrets, `oauth.tokenUrl`, `maxTokens`, `routing.key` are not).
- `ClearSecrets` (core): `scan(dataset)` lists the `Connector.password`/`apiKey` and secret `ConnectorProperty` values held in clear (exact count, 50 listed, `{connector, where}`); `logAtStartup` writes one kernel WARN; `ConnectorsStatus` reports it under `secrets`; `propertyProblem(dataset, connectorId)` is called by `ConnectorRegistry` when a connector instance is (re)built and refuses it (`Connector '<id>': property <key> holds a secret stored in clear text ...`) because the value read by `ConnectorRepository` has already had its vault references resolved.
- Reference = trimmed value matching `^([a-z][a-z0-9+.-]*):(.+)$` whose scheme is registered. Placeholder = blank, `TO_BE_SET`, `A_RENSEIGNER`.
- `resolveIfReference(value)` (properties and TLS passwords): resolved if reference, else unchanged (no decryption). Every `ConnectorProperty` value goes through it, whatever its key: property names are free-form and the secret fields themselves accept references, so the reach of a reference is bounded in the resolvers (§2.11.2: `ebx.connectors.secrets.dir`, `ebx.connectors.secrets.envAllowed`), not by the name of the property.

#### 2.11.2 Secret resolvers (`SecretResolvers`)

- Built-in schemes: `env`, `file`, `vault`, `azurekv`, `awssecrets`, `gcpsecret`, plus `META-INF/services/com.on.ebx.connect.spi.SecretResolver` (schemes lower-cased; later registration replaces).
- Cache key `<scheme>:<reference>`; TTL `-Debx.connectors.secrets.cacheSeconds` (default `300`). Empty resolved value → `secret reference '<key>' resolved to an empty value`. Unknown scheme → `no secret resolver for scheme '<scheme>' (known: [...])`. Other errors → `secret reference '<scheme>:<ref>' cannot be resolved: <message>`. `invalidate()` clears the cache.

| Scheme | Reference | Behaviour |
|---|---|---|
| `env` | `NAME` | `-Debx.connectors.secrets.envAllowed` unset: environment variable `NAME` only (never a system property); missing → `environment variable '<NAME>' is not set (a system property is read only when ebx.connectors.secrets.envAllowed names it)`; kernel warning logged once per JVM. Set (comma-separated names, or `PREFIX*` with at least one character before the star): a name outside the list → `'<NAME>' is not in ebx.connectors.secrets.envAllowed`; a listed name → system property `NAME`, else environment variable `NAME`; missing → `environment variable or system property '<NAME>' is not set` |
| `file` | path | trimmed UTF-8 file content, 1 MiB at most (`file <abs path> exceeds 1 MiB: not a secret`). `-Debx.connectors.secrets.dir` unset: any path; missing → `file <abs path> does not exist`; kernel warning logged once per JVM. Set: the real path of the file (`toRealPath`: `..` and symbolic links resolved) must be inside the real path of the directory; a relative reference is resolved against the directory; outside, absent or unreadable → `file reference refused: no such file in the secrets directory (ebx.connectors.secrets.dir)`; a directory that does not exist → `the secrets directory set by ebx.connectors.secrets.dir does not exist or cannot be read` |
| `vault` | `<path>#<key>` | address `ebx.connectors.vault.address` / `VAULT_ADDR`; token `ebx.connectors.vault.token` / `VAULT_TOKEN` / `~/.vault-token`; namespace `ebx.connectors.vault.namespace` / `VAULT_NAMESPACE` (header `X-Vault-Namespace`). `GET <address>/v1/<path>` with `X-Vault-Token`; value at `data.data.<key>` (KV v2) else `data.<key>` (KV v1). Timeouts 5000/10000 ms. Errors: address not set, token not set, missing `#`, `Vault answered HTTP <n> for <path>`, `key '<k>' not found in Vault secret <path>` |
| `azurekv` | secret URL, or `<vault>/<name>` → `https://<vault>.vault.azure.net/secrets/<name>` | `GET <url>?api-version=7.4` with bearer token; value `value`. Token: service principal (`ebx.connectors.azure.tenantId/clientId/clientSecret` or `AZURE_TENANT_ID/AZURE_CLIENT_ID/AZURE_CLIENT_SECRET`, client credentials against `login.microsoftonline.com`, scope `https://vault.azure.net/.default`), else `IDENTITY_ENDPOINT` + `IDENTITY_HEADER` (api-version 2019-08-01), else IMDS `169.254.169.254` (api-version 2018-02-01). Token cached until `expires_in − 60 s` (minimum 60 s; 3000 s when absent) |

Resolver HTTP calls go through `HttpJsonClient`, hence through the egress allow-list. The metadata lookups of the host (EC2 IMDSv2, ECS, Azure IMDS / `IDENTITY_ENDPOINT`, Google metadata) use `forPlatformMetadata()`: exempt from the link-local refusal of §2.11.4, never following a redirect.

#### 2.11.3 Redaction (`Redaction`)

- Applied to SyncLog messages and changes, error messages of synchronisations, REST connector exceptions, kernel log lines.
- Masks with `***`:
  - URL query parameters `key`, `api_key`/`api-key`/`apikey`, `token`, `access_token`, `auth_token`, `secret`, `client_secret`, `password`, `pwd`, `passwd`, `signature`, `sig`, `sas`, `code` (case-insensitive).
  - Headers `Authorization`, `X-Api-Key`, `X-Vault-Token`, `X-INSEE-Api-Key-Integration`, `Ocp-Apim-Subscription-Key` followed by `=`, `:` or space, optional `Basic`/`Bearer`, then a token of ≥ 8 characters.
  - Every remembered secret value appearing literally.
- `remember(config)` (on connector load and REST connector `configure`): apiKey, password, and property values whose key (lower-cased) contains `secret`, `password`, `token`, `privatekey`, `apikey`, or ends with `.key`. Only values ≥ 6 characters that are not placeholders; at most 4096 values kept (oldest evicted); JVM-global.

`SensitiveDataMasker` (per request, used by inspection and governance audit): sensitive keys = normalised (camelCase → snake) key matching `api_key|password|passwd|pwd|secret|token|authorization|credential|private_key|signature|sas` as a segment, or ending with `key` and containing `private` or `api`. Masks query values (same list, empty values too), header lines `authorization|proxy-authorization|x-api-key|x-auth-token|x-vault-token|cookie|set-cookie` (whole line value), and up to 128 secret values ≥ 4 characters (not `to_be_set`), longest first.

#### 2.11.4 Outbound allow-list (`Egress`)

- `-Debx.connectors.allowedHosts`: comma-separated patterns `host`, `host:port`, `*.domain` (matches subdomains and the domain itself), `*` (all), `none` (matches nothing), `[ipv6]`, `[ipv6]:port` (a bare IPv6 address is read whole, without port). Lower-cased; IPv6 literals compared in canonical form. Absent/empty → no restriction, warning logged once: `[connectors] ebx.connectors.allowedHosts is not set: outbound connector calls are NOT restricted ...`.
- `file:<path>`: patterns read from the file (one per line, `#` comments, blank lines ignored); re-read when mtime changes, checked at most every 30 s; missing, unreadable or empty file → `none` (refuse all).
- `-Debx.connectors.egress.privateExceptions` (default unset; separate from the allow-list, never turns it on): comma-separated or `file:<path>` (same reader and 30 s check as the allow-list; an unreadable or empty file = no exception). Entries: `host`, `host:port` (name kind: lifts the private rule for that exact name, any address it resolves to), IPv4/IPv6 literal with optional port (`[::1]:11434`), CIDR (`10.1.0.0/16`, `fd00::/8`; address kind: lifts it for a resolved address inside the block, every address of a name must be covered). An entry that does not parse allows nothing; `*` is a name that matches nothing. Never lifts link-local, metadata (names or addresses) or the non-canonical numeric refusal. `Egress.privateExceptionCount()` feeds the status route.
- `-Debx.connectors.egress.requireAllowList=true` (default unset): while the allow-list is absent/empty, every target that names a host is refused: `outbound call to <host>[:<port>] refused: ebx.connectors.egress.requireAllowList=true and ebx.connectors.allowedHosts is not set (...)`; the "not set" warning is not logged. A JDBC URL without host (`jdbc:h2:mem:...`) is not an outbound call and passes.
- Address rules, applied after the allow-list to every host that no **exact** entry (no `*`) of the list covers:
  - always: refused when the host is `metadata.google.internal` / `metadata.goog` (trailing dot ignored), or is / resolves to (`InetAddress.getAllByName`, every address) a link-local address (169.254.0.0/16, fe80::/10), `fd00:ec2::254`, `100.100.100.200`, `192.0.0.192`; an IPv4 address embedded in IPv6 (`::ffff:a.b.c.d`, `::a.b.c.d`, `64:ff9b::/96`) is judged as that IPv4 address. Messages: `... refused: it is a cloud metadata service (...)`, `... refused: it is|resolves to a link-local or cloud metadata address (<ip>) (to allow it, name this host as an exact entry of ebx.connectors.allowedHosts, not through a wildcard)`.
  - unless `-Debx.connectors.egress.blockPrivate=false` (default: on since 2026-10-06; also when the value is empty or anything but `false`), and unless a private exception or an exact allow-list entry covers it: also refused when it is / resolves to a loopback, any-local, site-local (10/8, 172.16/12, 192.168/16), 0.0.0.0/8, 100.64.0.0/10, multicast, broadcast or `fc00::/7` address (also 192.0.0.0/24, 198.18.0.0/15, 240.0.0.0/4 and `fec0::/10`; 6to4 `2002::/16` is judged as its embedded IPv4), when the name does not resolve, or when a numeric host is not four canonical decimal parts (`2130706433`, `0x7f.1`, `0177.0.0.1`, `127.1`: this JVM and a proxy or driver read them differently). Messages end with the way out: "(to allow it, add the host, host:port or CIDR block to -Debx.connectors.egress.privateExceptions, or name it as an exact entry of -Debx.connectors.allowedHosts; -Debx.connectors.egress.blockPrivate=false lifts the rule for the whole instance)".
  - (with the rule on, as it is by default, a name that does not resolve is refused: it cannot be proven public; with `blockPrivate=false` it passes and the connection fails by itself.)
  - The check and the connection are two lookups served by the JVM address cache; a DNS answer that changes between them is not covered (no address pinning).
  - An http(s) URL that the JVM's `ProxySelector` sends to a proxy is not resolved here (the proxy resolves it, on its network): only its literal host is judged — metadata names, literal addresses — and with `blockPrivate` an unresolvable name is not refused.
- `checkPlatformMetadata(target)`: the allow-list and `requireAllowList` only, no address rule. Used by the module's own workload-identity lookups (EC2 IMDSv2, ECS container credentials, Azure IMDS / `IDENTITY_ENDPOINT`, Google metadata), whose address is fixed by the platform or set at JVM level; `HttpJsonClient.forPlatformMetadata()` selects it.
- Checked: every `HttpJsonClient` request and every redirect hop (host and port taken from the parsed `java.net.URL`), and on connector load `baseUrl`, property `oauth.tokenUrl`, property `instanceUrl`. Refusal as `IOException` in HTTP, `ConnectorException` on load. Allow-list refusal: `outbound call to <host>[:<port>] refused: not in ebx.connectors.allowedHosts (<list>)`.
- Host extraction (string targets; a single `http://` / `https://` URL that `java.net.URL` can parse is checked as that URL): comma-separated targets; `scheme://[user@]host[:port]/...` (JDBC `jdbc:x://` too); `...@host:port/...` (Oracle thin); other `jdbc:` URLs without host → no check; bare `host[:port][/...]`; `[ipv6]` and `[ipv6]:port` read as a host. A target part in which no host can be read is kept whole as the host, so an allow-list refuses it.
- `posture(config)` (UI, no DNS lookup: literal addresses and names only): `NOT_COVERED` when the connector would be refused (not in the list, list required and absent, metadata or — with `blockPrivate` — private literal), else `COVERED` when a list is set, else `NOT_ENFORCED`. `source()` and `example(config)` are descriptive helpers.

#### 2.11.5 OAuth 2.0 (`OAuth2`)

- Used when `authType = OAUTH2` by `AbstractRestConnector` (header `oauth.tokenHeader`, default `Authorization`; value `oauth.tokenPrefix` default `Bearer ` + token).
- Properties: `oauth.tokenUrl`, `oauth.flow` (`client_credentials` default, `password`, `refresh_token`, `jwt_bearer`; `authorization_code` refused: `authorization_code requires an interactive EBX user-service redirect/callback; use a stored refresh_token after authorization`), `oauth.clientId` or `clientId`, client secret = `oauth.clientSecret` else `apiKey`, `oauth.clientAuth` (`body` default, `basic`), `oauth.scope`, `oauth.audience`, `oauth.resource`, `oauth.extra.<name>`, `oauth.refreshToken`, `oauth.tokenField` (`access_token`), `oauth.expiresInSeconds` (3600, when the endpoint gives none).
- Validation messages (`validate`): token URL not set; `oauth.privateKey` not set for jwt_bearer; `oauth.clientId (or clientId)` not set; client secret not set (except password flow); username and password required by the password flow; `oauth.refreshToken` not set.
- jwt_bearer assertion (RS256, `oauth.privateKey` = unencrypted PKCS#8 RSA PEM): `iss` = `oauth.issuer` (client id), `sub` = `oauth.subject` (username, omitted when null), `aud` = `oauth.jwtAudience` (connector-supplied audience, else token URL), `iat`, `exp` = iat + `oauth.jwtLifetimeSeconds` (300), `jti` UUID, `oauth.claim.<name>` extra claims; form `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`, `assertion`, `client_id` when known.
- Token cache: key = connector id + SHA-256(config fingerprint + token URL); 256-stripe lock; expiry = now + (expires_in − skew) where skew = min(60, max(1, expires_in / 10)) seconds. Invalid or non-positive `expires_in` → error.
- Errors: `OAuth 2.0 (<id>): token endpoint <url> answered HTTP <n> [<error>][: <error_description>]`, `... unreachable: <message>`.
- A 401 response on a REST call invalidates the connector's tokens and retries once (`AbstractRestConnector.exchange`).
- `discover(client, url)` parses OpenID metadata (`issuer`, `authorization_endpoint`, `token_endpoint` required; `jwks_uri` optional); not used by the module outside core.

#### 2.11.6 Mutual TLS (`Tls`)

Properties `tls.keystore` (PKCS12, or JKS when the file ends with `.jks`, overridable by `tls.keystoreType`), `tls.keystorePassword`, `tls.keyPassword` (defaults to the store password), `tls.truststore`, `tls.truststorePassword`, `tls.truststoreType`. Passwords may be references. No property → JVM defaults. Contexts cached by SHA-256 of paths and passwords. Invalid stores → `IllegalStateException("TLS configuration of connector <id> is invalid: ...")` at `configure`. No "trust all" option.

#### 2.11.7 Webhook access (`Webhooks`)

- Audit C2-10 (2026-10-24): the signed-webhook and shared-secret modes were **removed**. The EBX REST Toolkit refuses a request without EBX credentials before the module's code runs, so a third party without an account never reached them, and they fell back to the login and password stored on the connection.
- `Webhooks.access(authenticated, mayRun)` is the decision: no session 401 (`UNAUTHENTICATED`), a session without the RUN right 403 (`FORBIDDEN`), otherwise 200 (`OK`); `Webhooks.refusal(status)` is the text that says what to do. The header constant `SIGNATURE_HEADER` stays (deprecated) only because `CsrfGuard` names it.
- The connector properties `webhook.signature`, `webhook.toleranceSeconds` and `webhook.replayProtection` and the `apiKey` / `username` / `password` of a `webhook` connection are no longer read.

#### 2.11.8 Other authentication helpers

- `Jwt`: RS256 signing with PKCS#8 keys (literal `\n` sequences removed); `googleAccessToken(serviceAccountJson, scope, timeout)` exchanges a 3600 s assertion at `https://oauth2.googleapis.com/token`.
- `GoogleAuth`: token cached 50 minutes; BEARER with a non-placeholder apiKey → apiKey used directly; else property `serviceAccountJson` or file `serviceAccountFile`; missing → `Google: set the property serviceAccountJson or serviceAccountFile (service account key), or a bearer token in apiKey`.
- `AwsSigV4.sign`: AWS Signature V4 headers (`x-amz-date`, `x-amz-content-sha256`, optional `x-amz-security-token`, `Authorization`), RFC 3986 canonical path/query.

#### 2.11.9 Credential guide (`CredentialGuide`, UI rules)

- Field states per auth mode (`REQUIRED`, `OPTIONAL`, `DERIVED`, `UNUSED`) for `username`, `password`, `apiKeyHeader`, `apiKey`: BASIC → username+password required; API_KEY_HEADER → header+key; BEARER → key; OAUTH2 → key required (password flow: username/password required, key optional; jwt_bearer: key unused, username optional); NONE → nothing (key optional for self-managed types: CUSTOM, SERVICE_ACCOUNT, CERTIFICATE, SSH_KEY).
- Type refinements: `google-geocoding`, `loqate`, `melissa`, `azure-service-bus` require apiKey on NONE; `kafka` never uses apiKey (NONE/BASIC), optional `security.protocol`, `sasl.mechanism` on BASIC; `powerbi`, `sharepoint`, `dynamics-business-central` (Entra): `tenantId`, `clientId` required, token URL `https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token` and scope derived; `dynamics365`: tenantId, clientId, oauth.tokenUrl, oauth.scope required; `salesforce`: token URL derived `{baseUrl}/services/oauth2/token`; databases (`postgresql`, `mysql`, `h2`, `jdbc`, `sql-server`, `azure-sql`, `oracle-database`) optional `jndi`.
- Default OAuth properties: `oauth.flow` optional, `oauth.tokenUrl` required, `clientId` required, `oauth.scope` optional; plus `oauth.refreshToken` or `oauth.privateKey` by flow. Unknown flows normalise to `client_credentials`.
- `SECRET_MAX_LENGTH = 255` (see Discrepancies).

---


### 2.12 OpenLineage

#### 2.12.1 Run events (`OpenLineageEmitter`)

- Sinks: active connectors of type `openlineage` (case-insensitive), list cached 30 s (`TARGETS_TTL_MS`); `invalidate()` forces a re-read. No Connectors dataset → no sink.
- When recorded (`ConnectorService.lineage`): non-dry-run results of root mappings from `syncRecord`, `sendScope` deletions and feed items. Inside an explicit run (`beginRun`…`endRun`) results are buffered per mapping and one event per mapping is sent at `endRun`; outside a run each result is sent immediately.
- `worthAnEvent`: false if the list is empty or any result is `DRY_RUN`; true only if at least one result is `OK` or `ERROR`.
- Sink settings (connector properties, defaults): `ebx.namespace` (`ebx://localhost:8080`), `job.namespace` (`ebx-connectors`), `producer` (`https://github.com/on-ebx/ebx-connectors`), `emit.outbound` (true), `emit.inbound` (false), `emit.declared` (true), `exclude.types` (`chat-webhook,slack,openlineage,llm`), `exclude.connectors` (""). Values of the two exclusion lists are lower-cased.
- A sink accepts a mapping when the direction flag is on, the remote connector is not the sink itself, its type is not excluded and its id is not in `exclude.connectors`.
- Event (`RunEvent`, schema `https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/RunEvent`):
  - Counts: `ERROR` → failed; `OK` whose operation equals the mapping's `deleteOperation` (and differs from the main operation) → deleted; other `OK` → sent; any other status → skipped.
  - `eventType` = `FAIL` when sent + deleted = 0 and failed > 0, else `COMPLETE`; `eventTime` ISO instant.
  - `run.runId` = name-based UUID of `<runId>|<mappingId>` (replaying a run updates the same OpenLineage run). Facets: `nominalTime`; `errorMessage` on FAIL (first error message or `<n> record(s) failed`, language `JAVA`); `ebx_connectors` (runId, mapping, label, connector, operation, direction, sent, deleted, failed, skipped).
  - `job`: namespace, name = mapping label (else `mapping-<id>`), facets `documentation` (`ON EBX Connectors mapping <id> (<outbound|inbound> <connector>/<op>) on <ebxName>[: <label>]`) and `jobType` (`BATCH`, `EBX_CONNECTORS`, `PUBLICATION` for outbound / `IMPORT` for inbound).
  - Datasets: EBX side `{ebx.namespace, <dataspace>/<dataset><tablePath>}` (dataspace from the mapping, else from the first result); remote side from `datasetOf`. Outbound: input = EBX, output = remote; inbound: the reverse. `outputStatistics.rowCount` = sent.
  - `columnLineage` on the output: one field entry per rule with non-empty source and target and a pipeline not starting with `constant(`; output field = target, input field = source (`./` or `/` prefix removed); transformation `DIRECT` / `IDENTITY` (empty pipeline) or `TRANSFORMATION` with the pipeline as description, `masking=false`. Sub-object mappings at any depth add their EBX table as an input (outbound) or an output (inbound) and prefix document fields with `<collectionPath>[].`. Empty `columnLineage` facets are removed.
- Remote dataset naming (`datasetOf`, `namespaceOf`):
  - Namespace = `<type lower-case>://<host[:port]>` from `baseUrl`; JDBC URLs via `jdbcNamespace` (`<sub>://<host...>/<db>`: Oracle `@` part, H2 `tcp://`/`ssl://` stripped and `mem:`/`file:` colons turned to `/`, SQL Server `databaseName=` appended, `;`/`?` parameters dropped); a base URL without scheme → first comma-separated server; no host → connector id or `default`.
  - Messaging types (`kafka`, `rabbitmq`, `jms`, `activemq`, `azure-service-bus`): name = first literal (no `${`) mapping input among `topic`, `queue`, `destination`, `entity`, `subject`, `channel`, else the connector property of the same name, else the operation.
  - Other types: first literal mapping input among `table`, `object`, `sobject`, `entity`, `entitySet`, `topic`, `queue`, `collection`, `list`, `index`, `resource`, `dataset`, `sheet`, `file`, `path`, `container`, `bucket`; a `table` name without `.` is prefixed with the connector property `schema`; else the operation name.
- Dispatch: sink connector operation `emit` with the event as payload (so the sink's authentication and egress rules apply). Failures logged as warnings, never propagated.

#### 2.12.2 Declared lineage (`OpenLineageEmitter.declaration/retirement`, `DeclaredLineage`)

- Declaration = OpenLineage `JobEvent` (schema `.../$defs/JobEvent`, no run, no eventType) with the same job facets, datasets and column lineage as a run, plus facet `ebx_connectors_declaration` (`state=DECLARED`, mapping, connector, operation, direction).
- Retirement = `JobEvent` with empty inputs/outputs and facet `state=RETIRED`, mapping id.
- `DeclaredLineage` (single daemon thread `ebx-connectors-declared-lineage`):
  - Disabled entirely by `-Debx.connectors.lineage.declared=false`.
  - `install(repository)` (repository startup): unless `ebx.connectors.lineage.declareOnStartup=false`, schedules a sweep after `ebx.connectors.lineage.startupDelaySeconds` (default 90); a sweep with refusals is retried up to 3 attempts in total (`STARTUP_ATTEMPTS`), 120 s apart.
  - `mappingChanged(repository, id, retiredJobName)` (called by triggers and configuration upserts/deletes): debounced per mapping by `ebx.connectors.lineage.debounceMs` (default 2000 ms); old job names to retire are accumulated.
  - Processing: a child mapping resolves to its root (max depth 10); root missing (deleted) → retire the accumulated names, or `mapping-<id>`; names different from the current job name are retired; active root → declared, inactive → current job retired.
  - Sinks decide with `emit.declared` plus the same direction/exclusion rules; a sink with `emit.declared=false` skips retirements too.
  - `declareNow(repository, mappingId|null)` returns JSON `{sinks, note?, error?, mappings:[{id,label,state,sent,failed,skipped}], declared, retired, sent, failed, skipped, messages}`; errors: `The Connectors dataset does not exist`, `No mapping <id>`; note when no sink: `No active connector of type openlineage: nothing to send to.`

---


### 2.13 HTTP and REST plumbing

#### 2.13.1 HttpJsonClient

- `HttpURLConnection` (JVM proxy settings honoured), `User-Agent` (default `EBX-Connectors/1.0`; REST connectors use `EBX-Connectors/1.0 (<connectorId>)`), `Accept: application/json`, caller headers; with a body and no Content-Type → `application/json; charset=UTF-8`.
- Every attempt, and every redirect hop, checks `Egress` first (allow-list and address rules, §2.11.4).
- Redirects: the connection never follows one itself (`setInstanceFollowRedirects(false)`). `send` / `sendBytes` follow 301, 302, 303, 307, 308 with a `Location`, at most `MAX_REDIRECTS` = 5 hops (one more → `IOException("more than 5 redirects starting at <scheme://host:port>: not followed further")`). A `Location` with another scheme than the current URL (https → http, http → https, anything else) or unparseable is not followed: the 3xx response is returned. When a hop changes host or port, the caller's headers are reduced to `Accept`, `Accept-Charset`, `Accept-Language`, `Content-Type`, `Content-Language`, `Content-Encoding` for that hop and all later ones (no `Authorization`, API-key header, `Cookie`, signature). 303 (except HEAD), and 301/302 after a POST, continue as `GET` without body nor `Content-*` headers; 307/308, and 301/302 for other methods, replay the method and the body. `sendBytesWithoutRedirects` returns the 3xx response. A `201` with `Location` is not a redirect.
- Error status (≥ 400) reads the error stream; body parsed as JSON when possible (`getBody()` null otherwise); `getHeader` case-insensitive; `getFinalUrl` after redirects.
- PATCH: sent through `java.net.http.HttpClient` by reflection (JDK 11+), after the same `Egress` check; that transport does not follow redirects (the 3xx response is returned); if unavailable, falls back to `HttpURLConnection` (which refuses PATCH). `AbstractRestConnector` converts PATCH to `POST ...?_HttpMethod=PATCH` when the JDK has no `java.net.http`.
- Optional response byte ceiling (`sendBytes(..., max)`): exceeding → `IOException("HTTP response exceeds byte limit <n>")`.
- `withReadTimeout(ms)`: a copy of the client (same connect timeout, user agent, retry policy, TLS context) with another read timeout, for one call; the client itself when the value is the same or not positive. Used by `AbstractRestConnector.send(method, path, query, body, headers, readTimeoutMs)` (PATCH and a non-positive value fall back to the plain `send`), hence by the contract's `llm.ask`.
- `encode` = URL encoding with `%20` for spaces; `buildUrl(base, path, query)` strips one trailing `/` of the base, adds `/` before the path, appends encoded query parameters.

#### 2.13.2 Retry policy (`HttpRetryPolicy`)

- Defaults: max attempts 2 (i.e. one retry), base delay 2000 ms, max delay 30000 ms.
- Retry only for idempotent methods (`GET`, `HEAD`, `PUT`, `DELETE`, `OPTIONS`, `TRACE`) and statuses 408, 425, 429, 500–504.
- Delay = `Retry-After` (seconds or HTTP date) when present, else base × 2^(attempt−1), capped at max delay.

#### 2.13.3 AbstractRestConnector (base of the JSON REST connector types)

- `configure`: remembers secrets, builds the client with the connector timeouts, applies TLS.
- Auth headers: BASIC (`Authorization: Basic base64(user:password)`, empty strings for nulls); API_KEY_HEADER (`<apiKeyHeader>: <apiKey>` when both set); BEARER (`Authorization: Bearer <apiKey>`); OAUTH2 (§11.5); NONE. Property `accept` overrides `Accept`.
- `requireSecret`: placeholder apiKey on API_KEY_HEADER/BEARER → `Connector <id>: API key not configured (Connectors > Connector > <id>)`; BASIC placeholder password → `Connector <id>: password not configured`; OAUTH2 validation problem.
- `testConnection`: `HEAD <baseUrl>/<inspection.path>`; 200–399 success.
- `preview`: needs an explicit operation (or object id); refuses unknown operations, `WRITE` operations (`... writes data and cannot be used for preview`) and missing required inputs; returns up to `limit` records (array elements or the single object).
- `bodyOrNull`: 2xx → body; 404 → null (hence `NO_DATA` for inbound); other → `ConnectorException("<id>: <what> -> HTTP <status> <first 300 chars of body>")` carrying the status.
- Transport failures → `ConnectorException("<id>: <METHOD> <url> failed: <message>")` (redacted).
- Schema refresh (`RestSchemaRefresh`): conditional GET with `If-None-Match`/`If-Modified-Since`, 304 or non-2xx keeps the last schema, optional persisted state (`etag`, `lastModified`, `schema`, `checkedAt`, `nextRefreshAt`) and minimum interval; a changed schema signals `schemaDrift` to the operations runtime.

#### 2.13.4 Pagination and payload helpers

- `RestPagination`: offset/limit and token query builders (limit ≥ 1, offset ≥ 0), JSON next link at a path, RFC 5988 `Link` header `rel="next"`.
- `RestPageTraversal.collect`: follows JSON next links then `Link` headers; limits on pages and records (both must be ≥ 1); a continuation changing scheme/host/port → `IOException("Pagination continuation changed HTTP origin")`; result `{results, count, pages, truncated?, nextLink?}`.
- `RestPayload.root`: `/`-prefixed root = JSON Pointer, else dotted path; `schemaFingerprint` = SHA-256 of the JSON.
- `ConditionalRequest`: `If-None-Match`, `If-Match` headers; `notModified` = 304.
- `MultipartBody`: `multipart/form-data` with boundary `ebx-<uuid>`; names and file names escaped.
- `Templates`: `{name}` placeholders (`[A-Za-z0-9_.-]+`) filled with NONE, XML or JSON escaping; missing value → ""; `required(declared, templates)`: explicit comma list, `-` = none, absent = every placeholder.

---


### 2.14 Import feeds

#### 2.14.1 `importFrom(session, repo, feed, extraInputs, max, dryRun, origin[, resumeFrom])` — call then import

1. Dataset = `DatasetLocator.find(repo, feed.dataspace, feed.dataset, feed.tablePath)`; header result: direction `IMPORT`, record key "", label = feed label.
2. Dataset missing → `ERROR` `Dataset '<dataset>' (table <path>) not found[ in <dataspace>]`, or, for a mapping with no dataspace served by several distinct dataspaces, `Dataset '<dataset>' … is served by N dataspaces (…): name the dataspace on the mapping` (logged unless dry run). `DatasetLocator.find` answers null on ambiguity; `locate` carries the reason. A mapping may leave the dataspace empty: it then applies to a record wherever the dataset exists, workflow child dataspaces included. A TABLE-scoped run (feed import, scope send, catalog scan, enrich of a whole table) needs exactly one dataspace serving the dataset; copies in child dataspaces of that dataspace do not count; several distinct dataspaces → the run fails with `Dataset 'X' … is served by N dataspaces (…): name the dataspace on the mapping`, and the upsert refuses it.
3. Connector, operation existence, direction compatibility and load policy checked.
4. Inputs: each expression resolved with record null and params = `extraInputs`, trimmed; then each extra input added when the mapping input is absent or empty; `max` added (= max) when absent. (Name filtering of run-time inputs is done beforehand by callers via `runtimeInputs`.)
5. Execution; dry run on `Kafka`/`consume` uses `peek` (and a feed's dry run commits no offset whatever the connector id).
6. Items imported (§14.3); empty → header `NO_DATA` `No item returned by <connector>/<op> {inputs}`.
7. Exception → header `ERROR` inserted first in the results and logged.

#### 2.14.2 `importDocument(session, repo, feed, document, max, dryRun, origin[, resumeFrom])` — document already in hand (webhook, file, message)

Same as §14.1 from step 6; dataset missing → header `ERROR` returned **without** being logged; empty → `NO_DATA` `Empty document for <label>`.

**`resumeFrom` (volume lot 6, task 5, both entry points)**: an optional 8th parameter, 0-based index into the items list built in §14.3 (a value ≤ 0 behaves exactly like the original 7-argument overloads, which still exist and delegate with `resumeFrom = 0`). Items before it are neither looked up nor written; the batched path (§14.4) is what actually honours it - the pre-lot-6 `importItem` path (dry run, or `batchSize <= 1`) also receives the already-clamped value but currently ignores it (it has no batch boundary to resume from; a caller wanting to resume a `batchSize <= 1` mapping's feed would need to slice its own document/inputs instead). **Caveat repeated where it matters**: only meaningful when the connector call or supplied document returns items in the same order every time - true for a fixed document (a file, most REST list calls) but not for a paged or streamed source with no stable ordering guarantee, which is why real mid-stream resume (lot 7) is out of scope here.

#### 2.14.3 Items and per-item processing (`importItem`)

- Items: node at `itemsPath` (document itself when empty); array → non-null elements up to max; any other non-null node → one item.
- Truncation (`ConnectorService.truncationOf`, fixed 2026-09-28): before this, a feed whose document already held every item (a catalog scan, a REST page walk) was flagged truncated only when this class counted more items in the array than it imported (`available > items.size()`) — but a connector that already cuts its own array to `max` (JdbcConnector's `query`, a file feed's `read`, both now via `boundedQuery`/`FileFormats.parse`/`Csv.parse`) always has `available == items.size()` here, so the old check never fired and the run looked like a clean, complete load. Now the connector's own top-level `truncated`/`available` fields on the document are read too: either signal adds a `SKIPPED` "Limit reached" result (`available` set on it when known — the old counted case, or a document's own declared `available`; left unset, with a "more were available, exact total unknown" message, when the document says `truncated: true` but does not know its own total). A `SKIPPED` result turns the whole run `PARTIAL` (never `OK`) via `SyncResult.summarize`, which also surfaces `truncated`/`available` as top-level fields of the `connectors_sync`/`importFrom`/`importDocument` answer (`ConnectorsTools.putOutcome`, `ConnectorsResource.putOutcome`) so a caller checking only `status`/`count` — the MDM Factory's `LandingFlow.loadOutcome` — can still catch a load that was cut short.
- Each item: predicate = `resolvePredicate(keyPredicate, item)`; result key and label = the predicate text; direction `IMPORT`. (Audit C2-04) Every placeholder must sit inside a string literal (`./id='${id}'`, text around it allowed, composite keys clause by clause): the literal is rebuilt with the value and quoted as a whole by EBX (`XPathExpressionHelper.encodeLiteralStringWithDelimiters`, so `O'Brien` gives `"O'Brien"` and a value with both quotes doubles the apostrophes), `ValueTemplates.xpath`. A placeholder outside a literal is refused at save (`ValueTemplates.xpathRefusal`); in a mapping stored earlier it yields a literal unless the value is a number or boolean.
- Predicate empty → no lookup (item treated as new). Otherwise first matching record; EBX refusal → `Key predicate rejected by EBX on <table>: <predicate> (<reason>)`, with the hint `A date, time or dateTime field is compared with date-equal(./field,'2026-01-31'), not '='.` when the reason contains `unknown predicate operator`.
- **How a feed row finds its record (`FeedKeyLookup`, built once per `importDocument` run; volume lot 5 task 1, audit C-10)**, cheapest first. (1) **The whole primary key**: `keyPredicate` is exactly one `./field='${expr}'` clause per primary-key field (any order, e.g. `./_sourceId='${SOURCE_ID}'`, or `./a='${A}' and ./b='${B}'`), every key field a non-foreign-key `xs:string`, `xs:int`, `xs:integer` or `xs:decimal` (a number may also be written unquoted, `./id=${x}`): the placeholder values are read off the item, a number is parsed with the field's own `SchemaNode.parseXsString` (only plain `-?digits[.digits]` texts qualify) and `AdaptationTable.lookupAdaptationByPrimaryKey(table.computePrimaryKey(values))` answers, no XPath `Request`. A decimal key that finds nothing is still checked with the XPath predicate (it may be stored with another scale than the text carries); a number that is not a number of the key's type, or an item missing a placeholder (an incomplete key is never guessed), falls back to XPath. (2) **A business key** (`BusinessKeyPredicate`): `keyPredicate` made only of `./field='${expr}'` clauses joined by `and` on top-level, stored, single-valued, non-foreign-key `xs:string` fields of the table, the table's primary key being something else (the module's own recommended pattern, `./sourceId='${customerId}'` on an auto-increment table). Every XPath predicate on a field that is not the primary key scans the whole table, once per row; instead, once the run has asked ten times, one streaming read of the table (as the session sees it) builds an in-memory map business key to primary key (`BusinessKeyIndex`), and each row is a hash lookup followed by a lookup by primary key that re-reads the key fields (a record changed or deleted since the read is then found, or not, by the XPath). A key held by two records is left to the XPath (EBX returns the first in its own order); an empty text or an empty field never matches, as `./f=''` never does. The records the run creates or changes are added to the index as each segment commits. The index is a snapshot taken during the run: a record created by someone else after that is not seen before the next run. `-Debx.connectors.feed.keyIndex.maxRows` (default `2000000`; `0` turns the index off) bounds it: a table with more records is not indexed and each row is looked up with the XPath, with one WARN. A build that fails does the same. (3) **The XPath predicate** (`MappingEngine.first`) for any other shape. The resolved predicate text is still computed in every case, for the result's key/label.
- **The connector's configuration is read once per run, not per row (audit C-34).** The trust of the source (`trustify`) and the configuration handed to provenance and conflict logging come from `TransformationRunCache.connectorConfig`, filled by the first read of the run (a record, its `ConnectorProperty` rows and the resolution of every secret reference); a connector that cannot be read is asked again at the next call. A configuration edited in EBX while a run is going on is taken into account by the next run. `ConnectorRegistry` compares the cached connector's configuration with `ConnectorConfig.sameConfigurationAs` (value by value, nothing concatenated); `ConnectorConfig.fingerprint()` summarises the logo and an uploaded sample by their SHA-256 digest, computed once per value.
- No match and `createIfMissing = false` → `SKIPPED` `No record matches <predicate> and creation is off`.
- Trust context set (dataset, matched record or null).
- **`unchangedIfEqual` (volume lot 5, task 3, `MappingEngine.unchanged`)**: mapping values are computed once (`MappingEngine.compute`, reused by both the dry-run report and the real-run write — it used to run a second time inside the write procedure). When the mapping's `unchangedIfEqual` names a target field, the record already exists, a rule produced a value for that field, and that value (as an XS string) equals the record's current value at the same field (`SchemaNode.formatToXsString`; null and `""` both mean "no value" — `UnchangedRowDetector`), the whole item is reported `NO_CHANGE` and nothing else runs: no write policy, no procedure, no provenance, no sub-objects — including rules whose policy is `ALWAYS` on a technical field (`_loadId`, `_receivedAt`...) that would otherwise always look "changed" on a reload of the same unchanged source row. It still goes through the normal end-of-item `SyncLog` line, same as any other `NO_CHANGE`.
- Dry run: values computed; changes listed (`+ ` prefix when creating); sub-objects simulated; `DRY_RUN` `would create (not written)` / `would update <label> (not written)`.
- Real run, one procedure per item (all privileges, tracking info `Connector import <connector>/<op>`):
  - Create: all values written (no policy), `created <label>, <summary>`.
  - Update: policies applied, modified only when changed, `updated <label>, <summary>`.
  - Sub-objects applied on the written record.
  - Status `OK` if any change (main or sub-object) else `NO_CHANGE`.
  - Provenance and conflicts recorded when changes or conflicts exist. **Provenance (volume lot 5, task 2)**: only when the mapping's `provenance` property is `true` (default) — `ConnectorRepository.recordProvenance` is the single choke point for this guard, shared with `syncRecord`'s inbound/outbound path and `ConflictResolver`'s accept path, so a mapping whose target table already carries its own lineage (a landing table's `_loadId`/`_sourceSystem`) can turn it off once, on the mapping. Conflict logging is unaffected either way.
- Exception → `ERROR`. Logged unless dry run. Items do not carry runId or mappingId **on this path** (`importItem`, i.e. a dry run or a mapping with `batchSize <= 1`); the batched real-run path (§14.4) does set both, per the note in §9.
- `ConnectorService.locate` and `predicateFailure` are the reusable lookup/error helpers.
- **Per-run transformation caches (volume lot 5, task 4, `TransformationRunCache`)**: one instance is created per `importDocument` run (before the per-item loop) and set on every `TransformationContext` of that run (`TransformationContext.setCache`) — the main record's, the sub-object rows', and, through them, every `Transformations.apply` call. It caches a resolved `AdaptationTable` per `(path, dataspace, dataset)` (`Transformations.table`, including a negative "not found" result — never re-walks a foreign key or re-searches the dataspace tree for the same key twice in one run), a parsed `TransformationPipeline` per pipeline string (`Transformations.apply`, replacing the old per-row `splitPipeline`/`splitArgs`/`unquote`), a compiled `Pattern` per regex string (`regex(...)`), and a bounded (default 50,000 entries, LRU) memo of `lookup(...)` results per `(table, field, result, value)` — including a cached "not found". The cache lives for exactly one run and is dropped with it: outside a cached run path (a single-record `syncRecord`, a test), `TransformationContext.getCache()` is `null` and every cache-aware call falls back to computing directly, exactly as before this cache existed. Shared by both the per-item path above and the batched path below (one instance per `importDocument` run either way).

#### 2.14.4 Batched real-run import (volume lot 6, `ConnectorService.importItemsBatched`/`commitBatch`/`FeedItem`)

Chosen automatically by `importDocument` when the run is **not** a dry run **and** the feed mapping's `batchSize > 1` (the default is 1000, so this is the normal path for any feed that hasn't been explicitly configured down to `batchSize = 1`); a dry run or an explicit `batchSize <= 1` keeps using `importItem` (§14.3) exactly as before this lot, one procedure per item, no batching, no bisection, no breaker, no batch `SyncLog` row.

- **Task 1, batched writes.** Items are chunked into groups of `batchSize` (the last chunk may be smaller). For every item of a chunk, `ConnectorService.prepareFeedItem` does everything §14.3 does OUTSIDE any procedure: predicate resolution, the lookup (`locateForImport`, primary-key fast path included), trust assignment, `MappingEngine.compute`, the `unchangedIfEqual` check - producing a `FeedItem` already finalized (`SKIPPED`/`NO_CHANGE`/a preparation-failure `ERROR`) or still needing a write. Items still needing a write go to `BatchCommit.commit` (task 2) with an `Executor` that opens ONE `ProgrammaticService.execute` procedure per attempt, writing every item of the sublist it is given with `ConnectorService.writeFeedItem` (the same create/update/`applyChildren` logic `importItem`'s own procedure body runs, factored out so several items can share one procedure) - **sub-object mappings run inside this SAME batch procedure as their parent**, never a separate one. Provenance for the whole chunk is then written in ONE further procedure (`ConnectorRepository.recordProvenanceBatch`), using the `Adaptation` each successful write already returned - no per-row re-lookup by primary key (the EBX performance guide's "a lookup of a key touched in the same procedure forces a Lucene refresh" does not apply here: every lookup inside that procedure targets `FieldProvenance`, never the table the batch just wrote, and each (dataspace, dataset, tablePath, recordKey, fieldPath) key is touched at most once). Conflicts stay per record, one procedure each, exactly like `importItem` today.
- **A key repeated inside one chunk (audit C-20, `BatchKeySegments`).** Preparing every item of a chunk before writing any of them made two items with the same resolved key predicate each find no record and each create one. The chunk is now cut where a key comes back: the items prepared so far are written (one `BatchCommit`, as above), and the repeated item - and those after it - are then looked up against what was written, so it updates the record its first occurrence created. A chunk whose keys are all different is not cut (one set insertion per item); a feed with no key predicate never cuts. Each cut costs one more procedure for that chunk, so a feed whose every item repeats the previous key degrades towards row-by-row writes. The `SyncLog` still gets one batch row per chunk.
- **Task 2, bisection on failure (`BatchCommit`).** An EBX procedure is one transaction: if any item in a batch attempt throws (typically a blocking constraint on `doCreateOccurrence`/`doModifyContent` - §5, or a bad child value in `applyChildren`), the WHOLE attempt rolls back, including items that looked fine earlier in the same loop. `BatchCommit.commit` handles this by bisecting the failing sublist into two halves and retrying each independently, recursively, down to a single item; a single item whose own, isolated attempt still throws is the row responsible and is recorded as `ERROR` with the exception's message (`SampleValueSafety.safeFailure`), never retried further. This isolates one (or a few) bad rows out of a batch of N with **O(log N)** extra procedure calls - measured exactly for the pure algorithm in `BatchCommitTest`: isolating one bad item out of 64 costs 13 calls (`2*log2(64)+1`), never 64. **A `FeedItem` whose commit attempt is retried (because a sibling in the same sublist failed) has its own `SyncResult` reset first** (`FeedItem.resetForRetry`, called at the top of `writeFeedItem`): a rolled-back attempt's changes/errors/conflicts/message must never accumulate onto a later, real attempt on the same item - without this, a twice-attempted item would double its reported changes.
- **Task 2, circuit breaker (`CircuitBreaker`).** After every chunk, `ImportBatchSummary.fullyFailed()` (every write the chunk attempted ended up `ERROR`, i.e. `created == 0 && updated == 0 && errors > 0`; a chunk with nothing to write, or with at least one successful write, is never "fully failed") feeds a `CircuitBreaker(threshold = 3)`. Three consecutive fully-failed chunks stop the whole import (`importItemsBatched` returns without touching the rest of `pItems`); the remaining, never-attempted items are reported as one `SKIPPED` result/`SyncLog` line (`"Import stopped after 3 consecutive fully-failed batches; N item(s) were not processed"`, `ConnectorService.addNotProcessedNotice`) - counted as skipped in the run's summary, **never** as `OK` (`SyncResult.summarize` turns any `SKIPPED` into an overall `PARTIAL`, surfaced by `ConnectorsTools.putOutcome`/`ConnectorsResource.putOutcome` exactly like the existing truncation notice, §14.3).
- **Task 3, batched `SyncLog`.** See §9's addition: one batch row (counts + first/last key) plus one row per `ERROR`/`PARTIAL`/conflict-bearing record, unless the mapping's `logEveryRecord = true` restores the pre-lot-6 one-row-per-record logging.
- **Task 4, EBX tracking info.** Every procedure this lot opens (the batch write attempt, the batch's provenance write, the resume-position write) calls `ProgrammaticService.setSessionTrackingInfo("connectors:<runId>:<mappingId>:<batchIndex>")` before `execute(...)` (the resume-position write uses `:resume` instead of a batch index). **`ProcedureContext` has no `setTrackingInfo` method on this build** (checked with `javap` against `ebx-lib.jar`, 6.3.0-RC2, 2026-09-28) - only `Session.getTrackingInfo()` (read-only) and `ProgrammaticService.setSessionTrackingInfo(String)` (the one every write in this module already used, e.g. `importItem`'s own `"Connector import <connector>/<op>"`), so this lot uses that existing method, called once per `ProgrammaticService` before its `execute(...)`, not a per-procedure-context call.
- **Task 5, cancellation.** `ConnectorService.requestCancel(runId)` / `isCancelled(runId)` (a static, in-JVM `ConcurrentHashMap`-backed set, cleared once the import ends - cancelled, errored or completed) is checked **between chunks only, never mid-chunk**: a chunk already committing always finishes. There is no persisted, cluster-wide cancellation flag on `ExecutionRun` or anywhere else - a genuinely durable, cross-node "stop" was judged out of scope for this lot (see the open question below). The MCP tool `connectors_run_stop(runId)` (and the generic `/rest/mcp` JSON-RPC endpoint, which serves the same `ConnectorsTools.CATALOG` automatically) calls `requestCancel`; finding the `runId` of an import that is still running is only possible by reading what it has already written incrementally - the batch `SyncLog` rows (`connectors_log_search`) or `ExecutionRun.nextIndex` (`connectors_run_get` does not read `ExecutionRun` today; that table has no dedicated MCP/REST reader - see the open question below) - since `connectors_sync` itself is a single synchronous call that only returns its `runId` once the whole import has finished or stopped.
- **Task 5, resume position.** After every chunk (success, partial, or the chunk that trips the breaker) `ConnectorRepository.saveNextItemIndex(session, runId, endIndexOfChunk, trackingInfo)` writes/updates one `ExecutionRun` row (`runId`, `nextIndex`, `updatedAt` only - a small, separate procedure) keyed by the feed's own `runId` (`ConnectorService.currentRunId()`, the same id `connectors_sync`/`connectors_run_get` already use, not a new id scheme). **This reuses the `ExecutionRun` table `RecoverableExecution`/`EbxRecoverableExecutionStore` already declares** (§7's `wait` node table family is unrelated; this is the "Durable restart... for bounded connector executions" table, `attempts`/`completed`/`deadLetters`/`leaseOwner`/`leaseExpiresAt` fields included) for a **different, narrower purpose**: nothing currently wires `RecoverableExecution`'s own per-item checkpoint/retry/dead-letter engine to imports (`OperationalExecutionService` has no caller outside its own test), so today the two usages are disjoint - this lot's writes only ever touch `runId`/`nextIndex`/`updatedAt` and leave the other fields alone. If `RecoverableExecution` is ever wired to imports in a later lot, re-check this shared-table assumption first. A caller resumes with `connectors_sync(..., resumeFrom = <nextIndex>)` (§14.1/14.2) - the index most recently saved for that `runId`, read today from the batch `SyncLog` rows or by inspecting `ExecutionRun` directly (Data Services or `data-access`, `Bebx-connectors/Connectors/root/ExecutionRun`), since (again) no MCP/REST tool reads `ExecutionRun` back yet.
- **Task 6, result shape unchanged.** `connectors_sync`'s/`connectors_call`'s answer keeps `status`, `count`, `truncated`, `available` and the status counters (`ok`, `errors`, `partial`, `skipped`, `noData`) exactly as lot 1a left them; `results[]` still carries one entry per item (§14.3's shape), batched or not - lot 7 is where that array is meant to be replaced by a summary + `runId` for a very large run. The OpenLineage per-row buffer (`ConnectorService.lineage`) is unchanged by this lot; it was not found to be cheap to special-case for a large batched run without touching `OpenLineageEmitter`'s own buffering contract, so it still buffers one entry per item exactly as before - a genuinely large (multi-million-row) batched import's OpenLineage cost is therefore **not** addressed by lot 6 and is worth measuring before relying on it at that scale. **Superseded by volume lot 7a, §2.14.5**: `results[]` is now bounded and the OpenLineage buffer is now a running tally, not a list.
- **Not yet verified against a live EBX instance**: this lot's Java compiles and its pure logic (`BatchCommitTest`, `CircuitBreakerTest`, `ImportBatchSummaryTest`) is unit-tested (`build-test.sh` green), but nothing here has been run against a real repository - the actual EBX transaction-rollback-on-exception behaviour a blocking constraint relies on for bisection to trigger, the real cost of `recordProvenanceBatch`'s primary-key lookups at high volume, and the `ExecutionRun` upsert under concurrent batches of the same run are all *expected* to behave as described from reading the EBX performance guide and the existing single-row code, not measured live.
- **Open question for the coordinator**: should `connectors_run_get`/a new tool read `ExecutionRun.nextIndex` directly (so a caller doesn't have to parse it out of a batch `SyncLog` message or go through Data Services), and should stop/resume eventually persist the cancellation flag itself (on `ExecutionRun`, alongside `nextIndex`) instead of the current in-JVM-only set - the latter was left as a documented follow-up rather than implemented, per the task's own "otherwise document that stop is a follow-up" allowance, though a minimal in-JVM version (`requestCancel`/`connectors_run_stop`) was implemented since it was judged trivial to add. **First half answered by volume lot 7a** (`connectors_run_get` now reads `ExecutionRun` - §2.14.5); **the cancellation flag itself is still in-JVM-only** - still open.

---


#### 2.14.5 Streamed read, summary answer, asynchronous run (volume lot 7a)

Addresses the 2026-09-28 audit's remaining items: a feed's source was still fully materialised into
one `JsonNode` document/array even after lot 6's batched writes, every imported row's `SyncResult`
was kept for the whole run before being summarised at the end (both in the answer and in the
OpenLineage buffer), and `connectors_sync` was purely synchronous (a multi-hour load either times
out the caller or holds a thread with no timeout at all, in-JVM).

- **Task 1, streamed read SPI.** `com.on.ebx.connect.spi.Connector` gains `RowSink` (`boolean
  accept(JsonNode)`, `false` = stop early), `RowStreamResult` (`count`, `truncated`, `available`,
  and `document` - the raw document when one was materialised, `null` for a true streaming read) and
  a default `readRows(context, operation, inputs, itemsPath, max, sink)` that calls the existing
  `execute(...)` and replays the result through the new static `Connector.replay(document, itemsPath,
  max, sink)` - the exact narrowing/counting rules `ConnectorService.importDocument` used to apply
  inline (itemsPath empty → the document itself; an array → its non-null elements up to max, counting
  past max for `available` since the array is already fully in hand; a declared `truncated`/`available`
  on the document itself folded in exactly like `ConnectorService.truncationOf` used to). Every
  connector that does not override `readRows` keeps working unchanged through this default - it still
  fully materialises its own document (unavoidable without changing the connector itself - lot 7b),
  but the caller no longer keeps a SECOND full copy of it (the old `List<JsonNode> items`). `spi`
  depends on `core.Json` for this (`Json.at`) - a deliberate, one-line exception to the otherwise
  clean `spi` ⇏ `core` direction, made because itemsPath narrowing (`[*]` projections included) must
  stay byte-for-byte identical to the pre-existing `core.Json.at`-based logic; duplicating a lesser
  navigator in `spi` risked silently regressing an itemsPath form nobody happened to test.
- **Task 1, JdbcConnector native streaming.** Overrides `readRows` for the `query` operation only
  (every other JDBC operation - `get`, `catalog`, `profile`, `sample`, `execute`, `insert`/`update`/
  `upsert`, `procedure` - falls back to the default via `Connector.super.readRows(...)`): opens its
  own `PreparedStatement`, sets `fetchSize` (from the `fetch.size` config, same as the non-streaming
  path) and `setMaxRows(max + 1)` (the same probe-row trick `boundedQuery` already used, `0` = no
  limit for an unbounded `max`), then reads the `ResultSet` one row at a time (`JdbcConnector.rowOf`,
  factored out of `rows()` so both paths build byte-identical row JSON) and calls `pSink.accept(row)`
  per row, stopping as soon as the sink returns `false` or the probe row (row `max+1`) is reached.
  **Never builds the `{rows:[...], count, truncated}` wrapper document at all** - `RowStreamResult
  .document` is `null` for this path. Truncation is reported (`truncated=true`) with `available=-1`
  when the probe row is hit: counting the real total would mean scanning the rest of a potentially
  huge result set just to report a number, defeating the point of streaming (contrast with the
  default/`replay` path, which reports an exact `available` for free because its array is already
  fully in memory). A sink-initiated early stop (a batch's breaker/cancellation decision, never a
  max-related one - the max cutoff is enforced inside `readRows` itself, before the sink ever sees
  more than `max` rows) is reported as an ordinary, non-truncated read of however many rows were
  delivered; the caller already knows why it stopped and reports that separately (the "not
  processed" notice below). **`itemsPath` is ignored by this native path** - each row IS an item,
  equivalent to the `itemsPath="rows"` convention already used for JDBC-backed feeds (the catalog
  scan's own read recipe, §16); a JDBC `query` feed mapped with a different `itemsPath` would behave
  differently under native streaming than it did under the old fully-materialised path (untried - no
  such mapping was found in this codebase, since `rows` is the only shape `query` has ever returned).
  **Streaming conditions, not just requested - verified against what each driver needs, not measured
  live**: read-only mode via the existing `readOnlyOrGuarded` (turns `autoCommit` off either way,
  granted or not); H2 streams its result sets by default regardless of these settings; PostgreSQL
  only streams (fetches `fetchSize` rows at a time from the server instead of pulling the whole
  result set into driver memory on `executeQuery`) when `autoCommit=false` **and** a non-zero
  `fetchSize` is set on a forward-only, read-only statement - all already true here. **Not verified
  against a live PostgreSQL or H2 instance in this lot** - confirming it needs a real large table and
  driver-level instrumentation (heap sampling during the read) that this task's constraints (no live
  EBX instance, no credentials) put out of reach; `ConnectorRowStreamTest` covers the pure
  narrowing/counting contract (`Connector.replay`) a fake sink exercises identically to how
  `JdbcConnector`'s real cursor and `ConnectorService.FeedSink` both rely on it.
- **Task 1, `importFrom`/`importDocument` rebuilt around the stream.** The private, list-based
  `importDocument(session, repo, feed, dataset, document, ...)` engine lot 6 iterated is gone,
  replaced by `ConnectorService.runImport(...)`, fed by a small `RowSource` abstraction (`readRows`
  for `importFrom` - a live connector call; `Connector.replay` for `importDocument` - a document
  already in hand, e.g. a webhook body) and driving a new inner class, `FeedSink` (implements
  `Connector.RowSink`): resumeFrom skipping (an item's 0-based position is now the sink's own call
  count, not an index into a materialised list), the dry-run/`batchSize<=1` immediate-write path
  (delegates to the unchanged `importItem` per item), and the batched real-run path (accumulates a
  chunk of `batchSize` items, commits it via the unchanged `commitBatch`/`BatchCommit`/
  `CircuitBreaker`, persists run state, then checks the breaker and cancellation before accepting more
  items - returning `false` from `accept()` to stop the source, which for `JdbcConnector`'s `query`
  means the `ResultSet`/`Statement` are closed immediately without fetching further rows). At most one
  batch's worth of items is ever held in memory at once for the streamed path (`FeedSink.chunk`); a
  connector on the default (non-native) path still fully materialises its own document once (lot 7b
  is where that changes for Snowflake/files/REST), but the second, redundant `List<JsonNode>` copy
  lot 1-6 built on top of it is gone either way. `importItem` itself is untouched except two additive
  lines: it now sets `result.setRunId`/`setMappingId` (the one behavioural difference from the batched
  path §2.9 used to call out - now uniform) and `result.setCreated(existing == null)` (so
  `ImportOutcome`'s created/updated counters can tell the two apart from a plain `SyncResult` alone,
  since status `OK` does not).
- **Task 2, `ImportOutcome` replaces the run-long results list.** New class `core.ImportOutcome`:
  running counters (`created`, `updated`, `noChange`, `errorCount`, `skipped` - items never attempted
  at all, a truncation/breaker/cancellation notice's count, nudged to a nominal 1 rather than a real
  number since a streamed source's exact remainder is not knowable - `rejected` - an item looked up
  and declined because `createIfMissing=false` matched nothing - `conflicts`), `truncated`/
  `available`, `runId`/`nextIndex`/`durationMs`, and two independently-bounded samples: `results`
  (`-Debx.connectors.import.resultsMax`, default 1000 - every result up to the cap, any status) and
  `errors` (`-Debx.connectors.import.errorsMax`, default 50 - only failing results, so a caller can
  see what broke even when it is far past position 50 in the run). `isResultsOmitted()` is set the
  instant a result would have exceeded `resultsMax`; `status()` mirrors `SyncResult.Summary.status()`'s
  rule (a truncation, conflict, rejection or error always prevents a clean `OK`), and a new
  `finalStatus(cancelled)` maps the in-flight `status()` to the terminal `ExecutionRun` state
  (`OK`/`NO_DATA` → `DONE`, everything else passes through, `cancelled` always wins as `STOPPED`).
  `ConnectorService.tallyItem`/`tallyInto` are the ONE place a `SyncResult` is classified into these
  counters, shared by the per-item and batched paths (`ImportOutcomeTest`).
- **Task 2, `connectors_sync`/`POST /sync/{mapping}` answer shape - most fields unchanged on purpose.**
  `ConnectorsTools.feedResults`/`ConnectorsResource.feedResultsResponse` build the feed answer from
  `ImportOutcome` instead of walking a `List<SyncResult>` (`SyncResult.summarize`/`putOutcome`).
  **Verified against the MDM Factory's actual source** (`LandingFlow.loadOutcome`,
  `~/Dev/ebx-mdm-factory/ebx-mdm-factory/src/main/java/com/on/ebx/factory/landing/LandingFlow.java`,
  read-only, 2026-09-28) before finalising the shape: it reads exactly `status` (string), `count`
  (number), `ok` (number), `errors` (**a NUMBER**, the failing count - `result.path("errors").asInt(0)`)
  and, per item of `results[]`, `.status`/`.message`/`.errors[]` (an array, only inside one result
  item, never at the top level). An EARLIER draft of this lot made top-level `errors` an array (matching
  a literal reading of the task brief), which would have silently broken `LandingFlow` (`asInt(0)` on
  an array node answers `0`, so every load would report "0 errors" even when every row failed) - caught
  before landing, not after. **Final shape, kept 100% source-compatible with `LandingFlow` and any
  other consumer of the pre-lot-7a fields**:
  ```
  {
    runId, dryRun, mapping, feed: true, async: false,
    status, count,                          // unchanged meaning: count = items reaching a final
                                             // per-record outcome (created+updated+noChange+errorCount
                                             // +rejected) - NOT including a truncation/breaker/
                                             // cancellation notice's own count, a 1-item discrepancy
                                             // from the old count in the truncated/stopped case only
    ok, errors,                             // unchanged: NUMBERS (ok = created+updated+noChange,
                                             // errors = errorCount) - never arrays
    created, updated, noChange,             // NEW: split out of "ok"
    skipped, rejected, conflicts,           // NEW
    truncated, available,                   // unchanged meaning and position
    resultsOmitted,                         // NEW: present (true) only once results[] stopped being the whole run
    results: [ {mapping, record, key, status, message, errors:[...]}, ... up to resultsMax ],
                                             // unchanged item shape; OMITTED (key absent) once resultsOmitted
    errorSamples: [ same item shape, ... up to errorsMax ]   // NEW key (never "errors" - that stays the count)
  }
  ```
  `connectors_call`'s answer (unrelated to feed imports) and `syncRecord`/`sendScope`/`replay`'s
  answers (`ConnectorsTools.results`/`putOutcome`, `ConnectorsResource.resultsResponse`) are
  untouched - this lot's scope is `importFrom`/`importDocument` callers only, per the task. The
  webhook endpoint (`POST /sync/inbound/{connector}`, `importDocument`) was moved onto the same
  `feedResultsResponse` shape (it had no external "must keep working" contract on record, unlike
  `LandingFlow`, so unifying it was judged worth the shape change).
- **Task 2, callers updated to read counters instead of iterating `results` blindly** (the task's own
  words) - every caller of `importFrom` audited: `ConnectorsTools.sync` (MCP, above),
  `ConnectorsResource.sync`/webhook (REST, above), `ConnectorScheduledTask.execute` (new
  `SyncTargets.summarise(ImportOutcome)` overload builds the same `Summary`/execution-log text
  directly from the counters, no per-row walk), `ConnectorScriptTaskBean.executeScript` (a step
  mixing a feed with record-level mappings merges each feed's `SyncTargets.summarise(outcome)` into
  running totals alongside the exact, unbounded counts a `syncRecord`/`sendScope` result list still
  gives), `ConnectorSyncUserService` (the Mapping Designer's "Run" button: shows the bounded
  `outcome.getResults()` sample, with a `message.resultsOmitted` label when the run had more).
- **Task 2, OpenLineage buffer is now a running tally, not a list.** `OpenLineageEmitter.ResultTally`
  (package-private, `ResultTallyTest`): the exact same per-result classification `event(mapping,
  target, List<SyncResult>, ...)` always did (`sent`/`failed`/`partial`/`skipped`/`deleted`,
  `firstError`, a dataspace fallback), now built incrementally via `ResultTally.add(mapping, result)`
  instead of a loop over the whole run's results at the end. `ConnectorService.lineage`'s per-run
  buffer (`OpenLineageEmitter.Pending`) holds one `ResultTally` per mapping instead of one
  `List<SyncResult>`; `event(mapping, target, List<SyncResult>, ...)` itself is UNCHANGED (still
  public, still used for a single-record call outside an explicit run) and now internally builds a
  `ResultTally` via `ResultTally.of(mapping, list)` then delegates to the new `event(mapping, target,
  ResultTally, ...)` overload - a pure refactor, `OpenLineageEmitterTest` (unchanged) still passes.
  `ResultTally` also keeps a bounded sample of up to 50 failing record keys (`firstFailedKeys`,
  `MAX_SAMPLE_KEYS`), surfaced as a new `sampleFailedKeys` array in the emitted event's
  `ebx_connectors` run facet - additive, no existing consumer of the event schema read a field by
  this name before. The per-row OpenLineage cost lot 6 flagged as unmeasured for a large batched
  import is addressed by this: the buffer's size is now O(mappings) for the whole run, not O(rows).
- **Task 3, asynchronous run.** `ConnectorService.importFromAsync(session, repository, feed,
  extraInputs, max, origin, resumeFrom)` - always a real run (rejects being combined with `dryRun` at
  the MCP layer): mints a run id (`generateRunId()`, a pure ID string with no thread-local side
  effect, factored out of `newRunId()`), synchronously persists an initial `ExecutionRun` row
  (`status=RUNNING`, zero counters) so a caller polling `connectors_run_get` immediately after this
  call returns never races the background thread for the first row, submits the actual import to
  `ConnectorService.ASYNC_IMPORT_EXECUTOR` (daemon threads named `ebx-connector-import-async-N`; a
  bounded pool with up-front admission and a stop with the module - see §2.22.3, which replaces the
  unbounded cached pool described when this was written), and returns the
  run id immediately. The background thread calls `ConnectorService.beginRun(runId)` - a new overload
  of `beginRun()` that adopts a caller-chosen id instead of minting one, since the worker thread does
  not share the launching thread's `ThreadLocal` `CURRENT_RUN`/lineage-buffer state - then runs the
  ordinary synchronous `importFrom(...)`, which persists its OWN final `ExecutionRun` state exactly as
  a synchronous caller's run would (see the next bullet: `runImport` always persists an initial and a
  final state, synchronous or asynchronous, batched or not). `connectors_sync(async=true)` (feed, real
  run only) returns `{runId, status: "RUNNING", mapping, feed: true, async: true, nextStep: "poll
  connectors_run_get..."}` at once.
  - **Session lifetime - not verified live.** The background thread reuses the calling `Session`
    object exactly as the rest of this module already reuses one `Session` across many sequential
    procedures within a single call (a large synchronous batched import already spans many minutes on
    the same session, across thousands of procedures - the new factor is a DIFFERENT thread, not
    necessarily request-boundary invalidation). `javap` on `ebx-lib.jar` (6.3.0-RC2) shows
    `com.orchestranetworks.service.Session` carries no reference to `HttpServletRequest`/response and
    nothing that reads as thread-affine (`UserReference`, `SessionPermissions`, `getInteraction`,
    `clone(Map)` - no obvious "still bound to this HTTP call" flag); `Repository` also exposes
    `createSessionPermissionsForUser(UserReference)` and `createSessionFromArray(Object[])`, which
    would be the fallback (mint a fresh session for the same user) if reusing the caller's session
    turns out not to survive past the request/response cycle on a live instance - **this was not
    measurable in this task's environment** (no live EBX instance, no credentials, per the task's own
    constraints) and needs a real multi-minute async run watched end to end before being trusted.
  - **A truly unexpected failure inside the background `Runnable`** (anything `importFrom` itself did
    not already attribute to the run via its own header/notice handling) is caught, logged, and best-effort
    written as `ExecutionRun.status=ERROR` - a last resort so a bug in this lot cannot leave a run
    stuck `RUNNING` forever with nothing explaining why.
- **Task 3, `ExecutionRun` carries run state now, for every import run, sync or async, batched or
  not.** `ConnectorService.runImport` always writes a final `ExecutionRun` state at the end of a
  non-dry run (`ConnectorRepository.saveRunState`, replacing lot 6's narrower `saveNextItemIndex`,
  kept as a thin backward-compatible overload): `status` (`RUNNING`/`DONE`/`PARTIAL`/`ERROR`/
  `STOPPED`/`INTERRUPTED`), `mapping`, `startedAt` (set once, on first insert), the seven counters,
  `truncated`/`available`/`resultsOmitted`, `firstErrors` (a `key: message` line per
  `ImportOutcome.getErrors()` entry, cut to 16000 chars - the DB-side counterpart of the answer's
  `errorSamples[]`) and `message` (the stop reason when the run was cut short, else the header's own
  error message when there was one). The BATCHED path additionally writes this same full state after
  EVERY chunk (not just `nextIndex` as lot 6 did), so `connectors_run_get` shows live progress on a
  still-running import, synchronous or asynchronous, without needing `async=true` specifically. A
  **non-batched** run (dry run semantics aside - dry runs never write any `ExecutionRun` row) pays
  exactly one extra procedure for this, at the very end - a deliberate trade-off so `connectors_run_get`
  always has something to say about ANY feed run, not only a batched one. New XSD fields on
  `ExecutionRun` (`Connectors.xsd`, additive, trilingual docs): `mapping` (foreign key to `Mapping`),
  `status`, `startedAt`, `createdCount`/`updatedCount`/`noChangeCount`/`errorCount`/`skippedCount`/
  `rejectedCount`/`conflictCount`, `truncated`, `available`, `resultsOmitted`, `firstErrors`,
  `message`; `ConnectorsPaths.ExecutionRun` gains the matching `Path` constants. `tools/build_xsd.py`
  has no entry for `ExecutionRun` at all (unlike `Mapping`'s `batchSize`/`logEveryRecord` in lot 6) -
  nothing to update there.
- **The log rows of a run are a page (audit C-33, `RunLogPage`, `ConnectorRepository.logOfRunPage`).** `connectors_run_get` and `GET /runs/{runId}` used to read every SyncLog row of the run (an XPath on `./runId`, a field that is not a key, over the whole table) and return them all. The state of the run (`execution{}`) comes from the run's own `ExecutionRun` row - a primary-key lookup - and `calls[]` is one page of `callsMax` rows (default 50, at most 500) starting `callsFrom` rows from the newest. The page is read sorted on the primary key, descending (the newest rows first, which are the run's rows for a run that just ran), and the read stops after the page plus the one row that says whether more exist. `callsMax=0` does not read the log. When rows remain, the answer carries `callsCut: true`, `callsNextFrom` and `callsNote`. **Changes for callers**: `calls[]` is newest first (it was in key order, oldest first) and bounded; `count` and `errors` describe the page, no longer the whole run (the run's counters are in `execution{}`); `replayable` is no longer exactly `errors > 0`. `connectors_replay` still reads every row of the run (`ConnectorRepository.logOfRun`). Not done: a SyncLog key that starts with the run id (the key change would empty the table) - the page still filters a non-key field, which is why its cost depends on where the run's rows lie in the table; not measured on a large table.
- **Task 3, `connectors_run_get` reads `ExecutionRun`.** `ConnectorRepository.getRun(runId)` (a plain
  lookup, no writes) backs a new `execution{}` object in the `connectors_run_get`/`connectors_run_get`
  MCP answer, alongside the unchanged `calls[]` (the `SyncLog` rows of the run, lot 6's own shape):
  `status`, `mapping`, `nextIndex`, `startedAt`/`updatedAt`, the seven counters, `truncated`/
  `available`/`resultsOmitted` (when true), `firstErrors`/`message` (when non-empty). Present only
  when the row exists AND the schema actually has the new `status` field (a defensive check mirroring
  `ConnectorRepository.saveRunState`'s own `hasStateFields` guard) - an instance whose `ExecutionRun`
  table predates this lot, or a run that never went through `runImport` (outbound sync, a plain
  `syncRecord`, a replay), simply gets no `execution{}` block, exactly as before this lot.
- **Audit C-44: leases.** `saveRunState` (so the first write, every batch and the end) writes `ExecutionRun.leaseOwner` = `NodeIdentity.owner()` (`<node>#<JVM start time ms>`, node = `-Debx.connectors.nodeId` or the host name, `#` replaced) and `leaseExpiresAt` = now + `NodeIdentity.leaseMillis()` (`-Debx.connectors.run.leaseSeconds`, default 1800, 60 to 3600) while the status is `RUNNING`, and clears both for any other status; skipped when the model has no lease fields. `markInterruptedRunningRuns` then interrupts a `RUNNING` row only when `NodeIdentity.isAbandoned`: no owner or no expiry (written before leases), expiry before now, or an owner whose node part equals this node's (an earlier incarnation of it, necessarily dead). A live lease of another node is logged (`[connectors] run <id> left RUNNING: its lease is held by another node (...)`) and left alone. `EbxRecoverableExecutionStore` uses the same two fields for its own run ids, never the same rows.
- **Task 3, restart marks every `RUNNING` run `INTERRUPTED`** (before audit C-44; see the bullet above for what it does now). `ConnectorRepository
  .markInterruptedRunningRuns(session)`, called once from `ConnectorsModuleRegistration
  .handleRepositoryStartup` (a system-user session, `pContext.createSystemUserSession
  ("connectors-run-recovery")`, same pattern the other startup installers already use): an XPath
  query for `./status='RUNNING'`, all matches flipped to `INTERRUPTED` in one procedure. Rationale: a
  run truly stuck `RUNNING` across a JVM restart can only mean the JVM that was running it - and every
  in-JVM-only cancellation flag, executor thread and `ExecutionRun` checkpoint writer with it - is
  gone; nothing will ever move it out of `RUNNING` again on its own. Never throws (swallows and logs,
  same convention as every other bookkeeping write in this module).
- **Known, documented gaps/trade-offs of this lot** (none silent; several closed by volume lot 7b - see §2.14.6):
  - `count` in the feed answer excludes a truncation/breaker/cancellation notice's own item (a 1-item
    discrepancy from the pre-lot-7a `count` in exactly those cases - see the answer-shape bullet
    above). **Unchanged by lot 7b.**
  - `skipped` (items never attempted because a breaker/cancellation stopped the run) was a **nominal**
    non-zero count (`ImportOutcome.skipped(1)` per stop event) in every case. **Lot 7b (§2.14.6,
    `ConnectorService.notProcessedRemainder`):** now the exact remainder (`available - processed`)
    when the source gave an availability hint (Snowflake's `resultSetMetaData.numRows`, a file feed
    that already knows its own total); still the nominal 1 when it did not (JDBC's native cursor, most
    connectors on a sink-initiated stop) - `status()`/`finalStatus()` were already correct either way.
  - A catalog scan used to read `ImportOutcome.getResults()` - exact for any scan smaller than
    `resultsMax` (the normal case), a documented undercount beyond it. **Fixed by lot 7b (§2.14.6,
    `CatalogScanTally`):** `CatalogScanStatus.outcome` now reads an exact, unbounded per-result tally
    fed by every `ImportOutcome.addResult` call of a catalog-scan run, never the capped sample.
  - `resumeFrom` and `async` were MCP-only. **Fixed by lot 7b (§2.14.6):** `POST /sync/{mapping}`
    (REST) now exposes both, at parity with `connectors_sync`.
  - Only `JdbcConnector`'s `query` operation streamed natively; every other connector fully
    materialised its own document via the default `readRows`. **Fixed by lot 7b (§2.14.6):** Snowflake
    (`query`/`execute`, partition by partition), file connectors' CSV/TSV/NDJSON `read` (a real
    `Reader`, line by line) and the generic REST connector's paginated GET operations (page by page)
    all stream natively now. JSON/XML file reads, and every push-style connector (Kafka, Slack, SQS,
    SNS, message queues in general), still use the default `readRows` - explicitly out of scope for
    both lots (JSON/XML "may stay materialised" per the task; a push-style connector has no
    server-side cursor/partition/page concept to stream through in the first place).

---

#### 2.14.6 Native streaming for the remaining feed sources, exact catalog-scan counts, REST run parity, async session robustness (volume lot 7b)

Closes the "lot 7b's stated scope" items §2.14.5 left open, and the catalog-scan undercount noted
there.

- **Task 1, Snowflake native `readRows` (`query`/`execute` operations only).** Overrides
  `Connector.readRows`: consumes the SQL API's result **partitions ONE AT A TIME** - fetch partition
  `p`, stream its rows to the sink, drop the partition's `JsonNode` array, fetch `p+1` - instead of
  `collect`'s materialised `{columns, rows:[...], count}` document. `SnowflakeConnector.runStatement`
  is the submit/poll/error-handling half of the old `statement(...)` method, factored out so
  `readRows` reuses it without ever calling `collect` (which is what actually builds the `rows`
  array); `streamCollect`/`streamRows`/`StreamState` (package-private, no HTTP - `SnowflakeConnectorTest`
  exercises them directly with a fake sink) hold the per-partition column names/types/scales and the
  running `delivered` count across partitions. `available` comes from
  `resultSetMetaData.numRows` when the API reports it (known from the FIRST response, before a single
  partition is even fetched - cheap and exact, unlike JDBC's own probe-row trick) - `-1` otherwise.
  `truncated` is set when `max` cuts delivery OR partitions remain unfetched at that point; a
  sink-initiated stop (`accept` returning `false` - a batch/circuit-breaker/cancellation decision)
  stops fetching further partitions at once and is reported as an ordinary, non-truncated read - same
  convention as `Connector.replay`/`JdbcConnector`. A partition fetch that fails, or a statement still
  running past the read timeout, is an error either way (both go through `runStatement`, unchanged
  from the non-streamed path - the "a partial read must never look complete" fix of 2026-09-28
  applies here too). `itemsPath` is ignored, like JDBC's native path - each row IS an item, the
  `itemsPath="rows"` convention this connector's document already used. **Not verified against a live
  Snowflake account** (no live instance/credentials in this task's environment, same caveat as
  `JdbcConnector`'s own streaming in lot 7a) - `SnowflakeConnectorTest` covers the pure partition-
  feeding/truncation/sink-stop contract with a fake sink, exactly at the level lot 7a's own JDBC
  streaming was verified.
- **Task 1, file connectors' CSV/TSV/NDJSON native `readRows`.** `AbstractFileConnector.readRows`
  overrides the `read` operation for `format` `csv`/`ndjson` only (JSON and XML "may stay
  materialised" per the task, and fall back to the default `readRows`, as does any format a
  registered `DocumentCodecProvider` claims - `FileFormats.hasCustomProvider`, since excel/parquet
  always do and a third-party provider could in principle claim csv/ndjson too, and its own decoding
  may not be line-oriented UTF-8 text at all). **`Csv.parseStream(Reader, char, int, RecordSink)`**
  (new, `core` package) is the streaming counterpart of `Csv.parse`: the exact same RFC 4180 state
  machine (quoted fields, doubled-quote escaping, `\r`/`\n`/`\r\n` line endings), but a record is
  delivered to a `RecordSink` (two callbacks: `header(List<String>)` once, before any data record,
  then `accept(List<String>)` per record) and forgotten as soon as its terminator is seen - never
  appended to one `List<List<String>>` holding the whole file - via a `PushbackReader` of 1 character
  for the doubled-quote/`\r\n` lookahead (no `mark`/`reset` support needed on the underlying reader).
  `pMax` bounds delivery; past it, records are still walked (to report the exact `available` - the
  same "count everything, it is already being walked" trade `Csv.parse` has always made) unless the
  sink itself stops, in which case the walk stops immediately and `available` is `-1` (an external
  stop is not the same signal as "the source held more than max" - same convention as everywhere
  else in this lot). `RecordSink`/`StreamResult` stay list-of-strings, not `spi.Connector`-aware - the
  same `core` ⇏ `spi` direction `Csv`/`RestPageTraversal` all keep (see §2.14.5's own `spi` ⇏ `core`
  note on `Connector.replay`). `FileFormats.streamCsv`/`streamNdjson` (the `connectors` package, which
  is allowed to depend on `spi`) bridge `Csv.RecordSink`/a per-line NDJSON reader to
  `Connector.RowSink`, building the exact same `{header[i]: value}` row shape `Csv.parse` already
  produces; `FileFormats.decodingReader` gives both a strict-UTF-8, BOM-stripped `Reader` over the raw
  `byte[]` - never a `byte[] -> String -> one big tree` conversion, matching the task's own phrasing
  exactly. NDJSON streams line by line via `BufferedReader.readLine()`, each line parsed with the same
  `SafeTextDocuments.json` safety guard (one shared node-count budget across the whole file, exactly
  like the non-streamed path's `remainingTokens`). **The underlying `readBytes` storage primitive
  still returns one complete `byte[]` per file** (a true network-level streaming download would need a
  per-backend change - SFTP/S3/Azure Blob/GCS each with their own SDK - explicitly out of this lot's
  scope, see the task's own "a real Reader" phrasing, read as "never re-derive a big String or a big
  tree from the bytes you already have," not "stream the network fetch itself"); what changes is that
  the PARSED rows are never all held as `JsonNode` objects at once, which is the dominant memory cost
  for a file with many columns or many rows. The 100 MB (`FileFormats.ABSOLUTE_MAX_BYTES`) and
  100,000-row (`FileFormats.ABSOLUTE_MAX_ROWS`) absolute ceilings, and the `max.bytes`/`max.rows`
  settings, stay in force exactly as for the non-streamed path - checked via the same
  `FileFormats.checkSize`/`FileFormats.boundedRows` calls, before a single row is streamed.
  `readRows` uses the caller's `pMax` parameter directly (like every other native override), NOT the
  mapping's own `max` input/`max.rows` property the way `execute`'s non-streamed `read` branch does -
  a caller streaming a feed always gets exactly the ceiling it asked for. `CsvStreamTest` (pure,
  `Csv.parseStream` only) and `FileConnectorStreamTest` (an in-memory fake connector, cross-checked
  against the non-streamed `execute("read")` answer on the same file) cover this.
- **Task 1, the generic REST connector's paginated GET operations.** `GenericRestConnector.readRows`
  overrides an operation with `op.<name>.pagination.next` set (the same gate `execute` itself uses to
  choose `RestPageTraversal.collect` over a plain call): **`RestPageTraversal.stream`** (new) fetches
  pages exactly like `collect` (same `next`-link/JSON-cursor resolution, same same-origin check), but
  pushes each page's items to an `ItemSink` (package-private-flavoured, `core`, plain `JsonNode` -
  same `core` ⇏ `spi` reasoning as `Csv.RecordSink`) as soon as that page is fetched, then drops the
  page's own array - never accumulated into one `results` array across pages. `pMax` is the tighter of
  the caller's own ceiling and the connector's configured `pagination.maxRecords` (a caller asking for
  fewer gets fewer, never more); `pagination.maxPages` still bounds the HTTP call count regardless of
  `pMax`. `available` is always `-1` - nothing in a `next`-link/cursor contract ever reveals the
  collection's real total (unlike Snowflake's `numRows`), same honest-unknown convention as JDBC's own
  native streaming. A sink-initiated stop stops fetching further pages at once and is reported
  non-truncated, matching every other native override in this lot. `execute`'s own query-building
  (`GenericRestConnector.query`), path-requirement check (`requiredPath`) and `Fetcher` construction
  (`fetcher`) were factored out of `execute` into shared private methods so `readRows` reuses them
  verbatim rather than duplicating - `execute`'s own behaviour is unchanged (`RestFoundationTest`'s
  existing paging/header/cookie integration assertions still pass unmodified). Verified against a real
  embedded two-page HTTP server (`RestFoundationTest`, not a fake `Fetcher`): `readRows` delivers both
  pages' items via a fake sink in order, and a sink that stops after the first page's item never
  triggers a fetch of the second page at all (`generic.pageOneHits`/`pageTwoHits` counters). Kafka,
  Slack, SQS, SNS and every other push-style connector are unaffected - they have no page/partition/
  cursor concept to stream through, and keep the default `readRows` as before.
- **Task 2, `CatalogScanTally` - exact, unbounded catalog-scan counts past `resultsMax`.** New
  package-private class (`core`), a faithful, unbounded port of `CatalogScanStatus.outcome`'s old
  per-result loop (written/unchanged/skipped/failed/warned counts, the first failing message, and the
  header+`SKIPPED` "limit reached"/"not processed" notice's own message - excluded from every count,
  exactly like the old loop's `continue`). **`ImportOutcome.addResult` feeds EVERY result to it when
  enabled** (`enableCatalogTally()`, called once by `ConnectorService.runImport` for a mapping
  `CatalogScanStatus.sourceOf` recognises as a scan flow) - items via `tallyItem`, but also the
  header, the "limit reached"/"not processed" notice and the discovery-failure notice, exactly the
  same set `ImportOutcome.getResults()` used to hold, just never capped. `CatalogScanStatus.outcome`
  now takes the whole `ImportOutcome` (not a `List<SyncResult>`): the two "nothing was ever attempted"
  special cases (the whole call failed outright, or the source returned nothing) are still read off
  the run's lone result via the new `ImportOutcome.getTotalResultsAdded()` (unbounded, unlike
  `getResults().size()` once a scan exceeds `resultsMax`) equal to 1 - by definition never past the
  bound either way; everything else reads `tally.written`/`unchanged`/`skipped`/`failed`/`warned`/
  `getFirstError()`/`getLimitMessage()` directly. `CatalogScanStatus.record`'s signature changed to
  match (`ImportOutcome`, not `List<SyncResult>`); the three existing pure tests
  (`CatalogScanStatusTest`, `CatalogCompletionTest`, `SyncOutcomeTest`) were updated to build an
  `ImportOutcome` the same way production does (`outcomeOf` helpers: `enableCatalogTally()` then
  `addResult` per fixture) instead of a bare list, and `CatalogScanStatusTest` adds a scenario proving
  the fix: 6 results with `resultsMax=2` (so `getResults()` only ever held 2) still reports "6
  object(s) scanned... 1 failed" with the failure's message, not "2 object(s)".
  **`ImportOutcome.markTruncated(long, String)`** (new) is a related, general fix found while building
  this: `addResult`'s own truncation detection goes through `SyncResult.isLimitNotice()`, which
  requires the notice's `available` to be set - a "limit reached" notice with NO known total (JDBC's
  native cursor, most connectors on a sink-initiated max cutoff all report `available = -1`) was
  silently NEVER flagging `ImportOutcome.isTruncated()` at all, for exactly the runs a caller most
  needs to know a max was hit. `ConnectorService.runImport` now calls `markTruncated` explicitly, next
  to the same notice's `addResult`, regardless of whether `available` is known - idempotent when it
  is (the `isLimitNotice()` path already set the same fields). This is a general fix (affects the
  top-level `truncated`/`available` fields of every feed's answer, not just catalog scans), made
  relevant by this lot since native streaming makes `available = -1` the COMMON case rather than the
  exception (JDBC always, Snowflake/files/REST pagination often).
- **Task 3, `skipped` after a stop is the exact remainder when it can be.**
  `ConnectorService.notProcessedRemainder(long pAvailable, int pProcessed)` (new, pure, `static`,
  `NotProcessedRemainderTest`): `pAvailable - pProcessed` (floored at 1 - the run WAS stopped, so at
  least one item was left) when `pAvailable >= 0`, else the nominal 1 as before. `runImport` passes
  `streamResult.available` and `sink.index` (the sink's own processed count) to
  `addNotProcessedNotice`, which appends `"; N item(s) of M available were left unprocessed"` to the
  stop-reason message when the remainder is exact, and uses it (not a blind 1) for
  `outcome.skipped(...)`. Deliberately never calls `notice.setAvailable(...)` on this notice - that
  field is `SyncResult.isLimitNotice()`'s own signal, which `ImportOutcome.addResult` folds into
  `truncated`/`available`; a cancellation/breaker stop must never be reported as that kind of
  truncation (same "an external stop is not truncation" contract as everywhere else in this lot). In
  practice this is exact whenever the source is Snowflake (`numRows` known from the first response)
  or a file connector that already knows its own record count while parsing; still nominal for
  JDBC's native cursor (its own probe-row trick never learns the real total) and the REST connector's
  page traversal (a `next`-link/cursor reveals no total) - both documented, not silent.
- **Task 4, REST parity with `connectors_sync`/`connectors_run_get`/`connectors_run_stop`.**
  `POST /sync/{mapping}` gains `resumeFrom` (default 0) and `async` (default false) query parameters,
  mirroring the MCP tool's own parameters and validation exactly (`async` rejected combined with
  `dryRun`, `async` rejected outside the "inbound feed, no key/predicate/scope" shape - since wave 13 also accepted for an outbound mapping with a `scope`, §2.14.6a - both `400`s,
  matching the MCP tool's `IllegalArgumentException`s) - see `ConnectorsTools.sync`'s own async branch
  for the template this mirrors. `async=true` returns `{runId, status:"RUNNING", mapping, feed:true,
  async:true, nextStep}` at once, same shape as the MCP answer with REST-flavoured `nextStep` text
  (`GET`/`POST` routes instead of tool names). **`GET /runs/{runId}`** now answers the exact same
  `execution{}` block `connectors_run_get` does (status/counters/`nextIndex`/truncation/`firstErrors`)
  alongside `calls[]` - both built by one new, shared, `public static`
  `ConnectorsTools.runStatusJson(ConnectorRepository, String)`, extracted from the MCP tool's own
  private `run(String)` method (now a one-line delegator) so REST (`com.on.ebx.connect.ebx.rest`, a
  different package - the two packages already cross-import each other, see `McpEndpoint`/
  `ConnectorsTools`'s own imports) builds the identical answer instead of a second, drifting
  reimplementation. Additive only: `calls[]` rows now also carry `id`/`timestamp`/`direction`/
  `runId`/`origin` (via the shared `ConnectorsTools.logJson`), every field the endpoint answered
  before keeps the same name and meaning. **`POST /runs/{runId}/stop`** (new) mirrors
  `connectors_run_stop` - `ConnectorService.requestCancel`, same "takes effect at the next batch
  boundary, only if still running on this node" caveat. `ConnectorsRunRestSurfaceContractTest`
  (reflection over `ConnectorsResource`'s annotations, no EBX repository - same style as
  `ConnectorsPublishSurfaceContractTest`) checks the query parameters/routes/shared-method wiring;
  `RestFoundationTest` exercises `readRows` end to end (see the REST pagination bullet above) but not
  `sync`/`run`/`stopRun` themselves, which need a container-injected `SessionContext`/`Repository`
  this offline suite cannot provide - same limitation the rest of `ConnectorsResource` already has.
- **Task 5, async session robustness - real and automatic, not a documented "if a future measurement
  shows..." caveat.** `ConnectorService.sessionForAsyncWorker(Session, Repository)` (new): mints a
  fresh `Session` for the same user as the caller's, called once the async worker thread actually
  starts (not on the launching thread, where the caller's session is still guaranteed fresh - only the
  synchronous initial `saveRunState` uses it directly). **Route found by tracing bytecode** (`javap -c`
  on `com.onwbp.adaptation.CfRepository`, the `Repository` implementation, 6.3.0-RC2, 2026-09-28):
  `Repository.createSessionFromArray(Object[])` itself does nothing more than
  `DirectoryHandler.authenticateUserFromArray(array)` (directory-handler-specific credentials this
  module never has for the original caller - a login/password directory would need their password)
  followed by `com.onwbp.adaptation.SessionImpl.createSession(UserReference, Repository)` - a
  `public static` factory that builds a working `Session` directly from an ALREADY-TRUSTED
  `UserReference`, no further authentication needed. Since the worker already has exactly that (the
  caller's own session was already authenticated by the framework before this module ever saw it),
  calling this factory directly is simpler and safer than re-deriving credentials
  `createSessionFromArray` would need - and `createSessionPermissionsForUser(UserReference)`, the
  other candidate the lot 7a doc named, turned out to return a `SessionPermissions`, not a `Session`,
  so it cannot build one at all. `com.onwbp.adaptation` is the same package this module already
  imports `Adaptation`/`AdaptationTable`/`AdaptationHome`/`PrimaryKey` from throughout, not some
  deprecated namespace - but `SessionImpl` is found via `javap`, not documented Javadoc, so treat it
  exactly like any other `javap`-discovered API in this codebase (§13): re-verify against the actual
  target instance, expect it to be build-specific. **Not verified live** (no EBX instance in this
  task's environment, per its own constraints) - the intended live check is a real async import
  launched over the HTTP MCP/REST endpoints, confirmed to still attribute writes to the original user
  once minted. Falls back to the caller's own `Session` unchanged on ANY failure (no repository, no
  user reference, or the factory call itself throwing) - never lets a session-minting problem break an
  async import that has already started; `SessionForAsyncWorkerTest` covers the pure null-guard
  branches only (a real `Session`/`Repository`/`UserReference` cannot be constructed offline).
  The call now lives in `WorkerSessions` (by reflection, with the same fallback extended to linkage
  errors) and the worker body is guarded as a whole: see §2.22.1.
  **Runs unconditionally for every async import, regardless of caller type** - a `Session` object
  carries no "how was I created" flag to branch on. For the **in-JVM bridge** (`InJvmMcp` - "the
  caller hands over its own EBX Session... every tool runs with it") this is redundant rather than
  harmful: minting a session for the SAME `UserReference` an already-valid, already long-lived session
  carries produces an equivalent session - the MDM Factory's own job sessions (what it passes to
  `connectors_sync` through `com.on.ebx.connectors:type=InJvmMcp`) are already designed to survive the
  worker's whole lifetime, by the Factory's own construction, so nothing here needs to protect that
  path specifically. For an **HTTP MCP/REST caller**, the session is whatever the web container
  attached to that one request - exactly the case lot 7a's own doc flagged as unverified, and the case
  this mint genuinely protects.

#### 2.14.6a Asynchronous outbound scope send (wave 13)

Measured 2026-10-12: the MDM Factory's distribution send of 88,991 rows to Kafka (mapping 417, scope `all`) took 9 min 36 inside one
synchronous `connectors_sync` call, with no run id before it returned and no way to stop it. `connectors_sync(scope=…, async=true)` now
runs an OUTBOUND scope send the way `async=true` runs a feed import (§2.14.5): same executor, same slots, same `ExecutionRun` row, same
`connectors_run_get` / `connectors_run_stop`. **Nothing changes without `async`**: the synchronous call keeps its answer, its `results[]`
and its behaviour (`ConnectorService.sendScope` is the same loop; the asynchronous run adds a `OutboundRun` to it).

- **Which calls.** `async=true` with a `scope` (`all`, `unsent`, `changed`, `failed`, `dataspaceDelta`; an optional `predicate` narrows
  the table) on an OUTBOUND mapping, real run only, no `key`. Anything else that is not a feed import is refused with
  `async only applies to an inbound feed with no key, predicate or scope, or to an outbound mapping sent on a scope (…)` (REST `400`);
  with `dryRun`, `async cannot be combined with dryRun`. The refusal keeps the words "async only applies" the MDM Factory
  (`SendRuns.asyncRefused`) looks for to fall back to the synchronous call. `ConnectorsTools.scopeAsyncRefusal` (pure) decides it, MCP and
  REST alike.
- **Start.** `ConnectorService.sendScopeAsync(session, repository, mapping, table, predicate, scope, max, origin)`: mints the run id
  (`generateRunId`), takes the mapping (`MappingRunLocks`: a second real async run of the same mapping is refused, REST `409`), takes a
  slot (`RunSlots`, `ebx.connectors.import.async.maxConcurrent` / `maxQueued`, REST `429`), writes the `ExecutionRun` row (`RUNNING`,
  counters 0, `nextIndex` 0) before returning, and submits `sendWorker` to the same executor as the asynchronous imports. Answer, at once:
  `{runId, status: "RUNNING", mapping, direction: "OUTBOUND", scope, async: true, nextStep}` - no `count`, which is how a caller tells it from a
  finished synchronous answer. A start refused (mapping held, no slot, module stopping) leaves no row. The synchronous call is not blocked
  by a running asynchronous send of the same mapping (as before, outbound runs took no lock): start one or the other.
- **Worker.** As the feed's: `beginRun(runId)`, a fresh `Session` for the same user (`sessionForAsyncWorker`, falling back to the
  caller's), `AsyncRunGuard` (an `Error` or an interrupt still ends the row `ERROR` / `INTERRUPTED`), and a `finally` that gives back the
  slot, the mapping and the lineage buffer. The XPath `Request` for `predicate` is built on the worker, with the worker's session.
- **Counters.** `OutboundRun` keeps an `ImportOutcome` (counters and two bounded samples; a 90,000-record send does not hold 90,000
  results) and writes the row at each batch boundary. A record counts as: sent (also a remote deletion) `updated`; unchanged since its last
  send (`NO_CHANGE`, and at the end the scope's own "unchanged" count) `noChange`; any failure (`ERROR`, `PARTIAL`, a result carrying
  errors) `errorCount`, its result in the bounded sample behind `firstErrors`; `SKIPPED`, and at the end the records waiting for their retry
  delay, `skipped`. `created` and `rejected` stay 0: a send cannot tell a record the target never saw from one it had, so the Factory's
  `rows = created + updated + noChange` is the number of records the send reached. `nextIndex` is the number of records handled.
- **Batch boundary.** Every `min(batchSize of the mapping, 250)` records (`OutboundRun.boundary`; the mapping's default 1000 gives 250,
  about 1.6 s at the 6.5 ms per record measured on Vol100k): the pending bookkeeping of the run (`RecordRun`: provenance, outbound states,
  log lines) is flushed first, so the outbound state of every record counted is saved before the counters say so; then the `ExecutionRun` row
  is written (`RUNNING`, counters, `nextIndex`); then `ConnectorService.isCancelled(runId)` is looked at. A stop requested between two boundaries waits
  for the next one (`OutboundRun.mustStop`); a send that has fewer records than one boundary never looks and ends normally.
- **Stop.** `connectors_run_stop(runId)` / `POST /runs/{runId}/stop` / `run.stop` flag the run (`requestCancel`, in the JVM, as for feeds).
  At the next boundary the loop ends (remaining records and remote deletions are not sent), the row ends `STOPPED` with the counters of what
  was sent and the message `Send stopped (connectors_run_stop) after N record(s); the records already sent keep their outbound state`.
  The outbound state of the records already sent is written (flush at the boundary and in the `finally` of the loop), so a later send with
  scope `changed` or `unsent` does not resend them. Not undone: a record sent stays sent.
- **Terminal status** (`ImportOutcome.finalStatus`): `DONE` when every record was sent or unchanged (also an empty scope); `PARTIAL` when some
  failed, or the send was cut at `max` (`truncated: true`, the "Limit reached" sentence in `message`, `errorCount` 0 if nothing failed);
  `ERROR` when nothing was sent and something failed, or the run itself failed before sending (the dataset is not found, the selection
  fails: `message` = the redacted reason, `errorCount` 1); `STOPPED`; `INTERRUPTED` when a module or server restart caught it
  (`ConnectorRepository.markInterruptedRunningRuns` at startup, as for feeds; `AsyncRunGuard` for a worker that dies). A cut at `max` on an
  asynchronous send reads like the synchronous `truncated`: raise `max` (the default is 200, `RunLimits.API`; the Factory passes its own).
- **`connectors_run_get(runId)` / `GET /runs/{runId}` / `run.get`.** The same `execution{}` block as for a feed
  (`status, mapping, nextIndex, startedAt, updatedAt, created, updated, noChange, errorCount, skipped, rejected, conflicts, truncated, available?,
  resultsOmitted?, firstErrors?, message?`), plus, new, `direction` (`INBOUND` for a feed import, `OUTBOUND` for a send) in `execution{}` and at
  the top of the answer with the `mapping` id, read from the run's mapping (`ConnectorsTools.putDirection`; absent when the mapping was deleted).
  `calls[]` are the sync log lines of the records sent, as for a synchronous send.
- **Not done.** Resuming a send from `nextIndex` (a send is resumed by running `changed` / `unsent`, which skip what was sent); a stop that
  survives a restart (in-JVM, as for feeds); the OpenLineage event of an asynchronous send is emitted when the worker ends (`endRun`), as for a
  synchronous one. Tests: `OutboundRunTest` (counting, boundary, stop, terminal status), `ConnectorsToolsAsyncSendTest` (accepted and refused
  calls, start answer, `direction` in `run_get`, the tool descriptions), `InJvmContractTest` (`run.get`, `run.stop`). The write of the row and
  the send itself need a running instance.

#### 2.14.7 Watermark (incremental feed reads) (volume lot V2-D)

A reload of an UNCHANGED source used to cost 0.47 ms per row (99,249 rows: 47 s) even with
`unchangedIfEqual`, because every row is read from the source, transformed and looked up before the hash
compare reports `NO_CHANGE`. A **watermark** makes a feed read only what changed at the source since its
last successful run. It reuses the incremental machinery the module already declared and never wired
(`loadMode`, `checkpointStrategy`, `checkpointInput`, `checkpointValuePath`, `checkpointInitialValue`, the
`SynchronizationCheckpoint` table); `SynchronizationCheckpointManager` (the response-based flavour: a value
read from the call's answer) is left as it was, this is the row-based flavour beside it.

**Mapping properties** (Mapping table; a feed is a root mapping with `keyPredicate`):

| Property | Value / default | Meaning for a feed |
|---|---|---|
| `loadMode` | `FULL` (default) / `INCREMENTAL` | `INCREMENTAL` turns the watermark on |
| `checkpointStrategy` | `TIMESTAMP`, `MONOTONIC_ID`, `ORDERED_STRING` | how the values compare: **timestamp**, **number**, **plain string order** (UTF-16 code units: fixed-width ids, ULIDs, ISO text; NOT numeric). Any other strategy is not a row watermark and keeps its old (unwired) meaning |
| `checkpointValuePath` | none; **required** | the field of each returned row carrying the change value (`LAST_MODIFIED`, dotted for a nested one; a single name is also found ignoring case, JDBC drivers upper- or lower-case labels) |
| `checkpointInitialValue` | empty | where the very FIRST run starts (a valid value of the type). Empty: the floor of the type. A full refresh ignores it |
| `checkpointInput` | empty | pre-existing meaning kept: the connector input that receives the checkpoint as text when one is in force (e.g. `since`); nothing is sent on a first run |
| `checkpointBoundary` | empty = automatic; `INCLUSIVE` / `EXCLUSIVE` | see "Boundary" below. New in this lot |

`MappingSynchronizationPolicy` accepts a row-watermark strategy on a feed whatever the operation
advertises (the engine applies it itself) and refuses one without `checkpointValuePath`. On a mapping that
is not a feed nothing changed: the strategy must still be advertised by the operation descriptor.

**Where the checkpoint is stored.** In the existing `SynchronizationCheckpoint` table: one row per
mapping (primary key = mapping id), `strategy`, `value` (the canonical text of the highest watermark),
`updatedAt`, `version`. It is written by ONE procedure at the end of a run (`ConnectorRepository.saveCheckpoint`,
optimistic version check: a concurrent run that advanced it first makes the second save fail, which is
logged and said in the run's note, never failing an import whose rows were written), never per row, and
deleted with its mapping (the cascade already covered the table). A checkpoint saved under another
strategy than the mapping's now, or whose value is not valid for the type, is ignored with a note and
replaced at the end of the next successful run. Changing `checkpointValuePath` does not invalidate the
stored value: run once with `fullRefresh=true`.

**Placeholders** in any input expression of the mapping (`${watermark}` is resolved before the other
placeholders, and its value is written after them, so it can never be interpreted or erased):

- `${watermark}`: the checkpoint in force - the stored one, else `checkpointInitialValue`, else the
  **floor** of the type (`1900-01-01T00:00:00`, `-9223372036854775808`, a single space), so that
  `where s.LAST_MODIFIED >= ${watermark}` reads everything the first time (a comparison with NULL would read
  nothing). Written as text, except in an input the connector *binds* (next paragraph).
- `${watermark.isSet}`: the literal `1` when a stored checkpoint or an initial value is in force, `0` when
  `${watermark}` is only the floor (first run, full refresh):
  `where (${watermark.isSet} = 0 or s.LAST_MODIFIED >= ${watermark})` is valid SQL, and also reads the rows
  whose change column is NULL on the first run.

**JDBC binding.** `Connector.bindsWatermark(operation, input)` (SPI default `false`) says whether an input
is a text in which the engine may write a named parameter (audit C2-05: every `${...}` of that text, not only `${watermark}`, becomes a parameter `:__bN` bound as a typed value, `'${x}'` as one parameter holding the whole literal, `ValueTemplates.sql`; a number is bound as a number only in its canonical form; a placeholder in an identifier position, e.g. after `ON`, `TO`, `FROM`, is written only when the value is a plain dotted identifier, otherwise the statement is refused with `SQL_PLACEHOLDER_NOT_BINDABLE`; `:name` stays the way to name a value of the payload); `JdbcConnector` answers `true` for the `sql`
input of `query` (every JDBC-family connector inherits it). The engine then writes `:watermark` where the
mapping said `${watermark}` and adds two inputs, `watermark` (the value) and `watermarkType`
(`TIMESTAMP` | `NUMBER` | `STRING`); `JdbcConnector.values` turns them into a typed prepared-statement
parameter (`Watermark.sqlValue`): a `java.sql.Timestamp` (a local value as a local timestamp, an offset one
as the absolute instant; seconds are optional, `2026-01-31T10:15` binds), a `Long` (a `BigDecimal` beyond
long range or with decimals), the `String` itself (so `'0012'` stays `'0012'`). The value is never
concatenated into the SQL text. The names `watermark` and `watermarkType` are reserved for this; a plain
call that names an input `watermark` without `watermarkType` keeps the ordinary text-guessing binding.
Any other connector receives `${watermark}` as text (right for a REST `since` parameter; for a SQL-over-HTTP
connector such as Snowflake or Databricks the author quotes it, and a STRING watermark is source data,
never quote-escaped by the engine) - not verified against those two.

**Complete example** (H2/PostgreSQL/Oracle... `query`), mapping `Contacts` on `/root/Contact`:

```json
{ "connector": "crm-db", "operation": "query", "direction": "INBOUND", "dataset": "Landing", "tablePath": "/root/Contact",
  "label": "Contacts",
  "keyPredicate": "./_sourceId='${ID}'", "itemsPath": "rows",
  "provenance": false, "unchangedIfEqual": "./_rowHash",
  "loadMode": "INCREMENTAL", "checkpointStrategy": "TIMESTAMP", "checkpointValuePath": "LAST_MODIFIED",
  "inputs": { "sql": "select ID, NAME, EMAIL, LAST_MODIFIED from CONTACT s where s.LAST_MODIFIED >= ${watermark} order by s.LAST_MODIFIED" },
  "rules": [ ... ] }
```

- **First run** (no checkpoint row): the inputs the connector receives are
  `sql = "select ... from CONTACT s where s.LAST_MODIFIED >= :watermark order by s.LAST_MODIFIED"`,
  `watermark = "1900-01-01T00:00:00"`, `watermarkType = "TIMESTAMP"`, `max = <max>` - every row with a change
  value is read; the run ends DONE and stores the highest `LAST_MODIFIED` seen, e.g. `2026-09-30T08:41:07.5`.
- **Next run**: same `sql`, `watermark = "2026-09-30T08:41:07.5"` (bound as a `Timestamp`), only rows changed
  since are read; the row equal to the checkpoint comes back once more (inclusive, see below) and the hash
  compare reports it `NO_CHANGE`.
- **Rows whose `LAST_MODIFIED` is NULL** are never returned by `>=`: keep the column NOT NULL, or use the
  `isSet` form for the first run and accept that they are never read again.
- For a first load of a big source in slices, set `checkpointInitialValue` and call `connectors_sync` with a
  `max`; a truncated run does not advance (below).

**Client-side filter (files, REST without a since-parameter, and a second guard for SQL).** Every incremental
run reads through `WatermarkFilter`, a sink in front of the batching sink: a row at or below the checkpoint is
dropped **before it is looked up, transformed or written** (counted `skippedByWatermark`, not part of `count`),
and the highest valid watermark seen is tracked. It applies to a source that already filtered too: the boundary
option is what decides a row that EQUALS the checkpoint, so a `>=` in the SQL with an exclusive boundary still
drops it. A row whose field is missing, null or not a valid value cannot be judged: it is delivered (never
silently dropped), counted unreadable in the filter, and never becomes the maximum. Note: `max` bounds what the
CONNECTOR reads from the source, so for a client-side filter over a big file raise it (rows that were only
skipped count against it); a run cut short by `max` is truncated and therefore does not advance. Resume
(`resumeFrom`) counts the rows that passed the filter, which is stable across a retry because a failed run
keeps its checkpoint. A run that read rows but found none past the checkpoint ends DONE with the header
`No row past the watermark ...`. `importDocument` (a webhook or a document in hand) does not apply a watermark.

**Boundary.** Rows written at the source with the very value the previous run ended on may have been
committed after that run read them (two transactions, one timestamp), so reading the equal rows again is the
safe choice; it is only affordable when the hash compare turns them into `NO_CHANGE` without a write.
`checkpointBoundary` empty therefore means **INCLUSIVE when `unchangedIfEqual` is set, EXCLUSIVE otherwise**;
`INCLUSIVE` / `EXCLUSIVE` overrides. For SQL, write the operator that matches (`>=` inclusive, `>` exclusive):
the engine's filter enforces the option on what comes back, it cannot add a row the SQL left out. A timestamp
column that is not unique per transaction and is set by the application before commit can still lose rows that
commit later than the checkpoint moved past; a monotonic sequence (`MONOTONIC_ID`) or a source-side
commit-ordered value does not have that gap.

**When the checkpoint advances.** Only after a **real** run that ends `DONE` with no failed item, no
exception, no truncation by `max`, no stop by the circuit breaker or a cancellation, and no conflict
(`WatermarkRun.mayAdvance`; PARTIAL, ERROR and STOPPED keep the old value). It becomes the highest valid
watermark seen in the run (`Watermark.next`): never lower than the stored one, and unchanged when the run saw
none. A dry run applies the checkpoint for reading and reports the same fields but never saves.
(`runImport` also checks its own failure flag: an exception thrown mid-run leaves the ImportOutcome counters
at zero, so `finalStatus` alone would read DONE.)

**`fullRefresh=true`** on `connectors_sync` (MCP) and `POST /sync/{mapping}?fullRefresh=true` (REST):
`${watermark}` is the floor, `${watermark.isSet}` is 0, `checkpointInput` is not sent, the client filter reads
everything, and when the run ends DONE the checkpoint is **reset** to the highest value seen even if it is
below the stored one (a source rebuilt from a backup); over an empty source the checkpoint is left as it was.
The scheduled task, the workflow bean and the user service call the pre-existing overloads, so they use the
checkpoint and cannot request a full refresh.

**Answer.** `connectors_sync` / `POST /sync/{mapping}` (feed answer) gain, for an incremental feed only:
`watermarkField`, `watermarkBefore` (the checkpoint the run started from, `null` on a first run),
`watermarkAfter` (the checkpoint now; differs from before only when the run moved it), `skippedByWatermark`,
`watermarkAdvanced`, `fullRefresh` and, when something needs saying, `watermarkNote` (an ignored stored
checkpoint, a checkpoint that could not be saved). `connectors_run_get` / `GET /runs/{runId}` report
`execution.watermarkBefore`, `watermarkAfter`, `skippedByWatermark` from three new `ExecutionRun` fields
(written with the run state, after each batch and at the end). `connectors_mapping_get` shows `loadMode`,
`checkpointStrategy`, `checkpointValuePath`, `checkpointInitialValue`, `checkpointBoundary`,
`watermarkFeed` and `watermarkInclusive` for an incremental mapping.

**Deleted source rows are out of scope.** A row deleted at the source never comes back from a watermark read,
so an incremental feed cannot retire it. The count of target rows NOT seen is not exposed: a full run is
streamed and keeps no set of seen keys (that would be an in-memory set of every key, the thing lot 7a removed),
so it is not cheap. Retire rows with a periodic `fullRefresh` plus a separate reconciliation, or a source-side
tombstone column read as an ordinary change.

**Schema change at startup.** Additive only: one optional field on `Mapping` (`checkpointBoundary`), three
optional fields on `ExecutionRun` (`watermarkBefore`, `watermarkAfter`, `skippedByWatermark`), and one more
value (`ORDERED_STRING`) in the two `strategy` enumerations. No primary key changes, so no table is emptied
when the model reloads; every read of the new fields is guarded (`getNode != null`) until the model has
reloaded, so an old model just behaves as FULL. Additive enumeration value: a `SynchronizationCheckpoint` row
already stored keeps validating.

**Not verified.** Nothing ran against a live EBX instance (no instance in this lot's environment): the
checkpoint save procedure, the two new form fields, the persistence of the run-state fields and the REST
parameter are compiled and reviewed, not executed; the SQL binding is exercised against a real in-memory H2
only (`JdbcWatermarkTest`: TIMESTAMP, NUMBER, STRING, first run, stored checkpoint, hostile value), PostgreSQL,
Oracle and SQL Server were not run (Oracle binds an empty string as NULL, which is why the string floor is a
space). `WatermarkTest`, `WatermarkFilterTest`, `WatermarkRunTest` cover the pure decisions.

---


### 2.15 OpenAPI import (`OpenApiImporter`)

- `parse(text)`: text starting with `{` or `[` → JSON; otherwise YAML via `jackson-dataformat-yaml` loaded by reflection. Errors: `OpenAPI: the specification is empty`, `OpenAPI: invalid JSON: ...`, `OpenAPI: YAML needs jackson-dataformat-yaml.jar and snakeyaml.jar in the Tomcat lib directory; send JSON instead`, `OpenAPI: invalid YAML: ...`.
- `build(spec, id, label)` produces a `Draft` for a connector of type `rest` (the persistence is done by the REST/MCP layer):
  - No `paths` → `OpenAPI: no 'paths' in the specification (OpenAPI 3 expected)`; no operation → `OpenAPI: no GET/POST/PUT/DELETE operation found` (PATCH is imported too).
  - label = given label, else `info.title`, else the id; id = given id, else label with non-alphanumerics removed.
  - description = `Imported from OpenAPI <openapi> - <label>[ v<info.version>]. <info.description>`, truncated to 1900.
  - baseUrl = `servers[0].url` with `{variables}` replaced by their defaults and trailing `/` removed; relative or absent → note to set it by hand.
  - Security: the scheme named by the first global `security` requirement, else the first scheme. `apiKey` in header → `API_KEY_HEADER` + header name (default `X-API-Key`); in query → `API_KEY_HEADER` + property `auth.apiKeyQuery`; `http bearer` → `BEARER`; `http` other → `BASIC`; `oauth2` with `clientCredentials`, else `password`, else `authorizationCode` having a `tokenUrl` → `OAUTH2` (authorization code → `BEARER`) with `oauth.flow`, `oauth.tokenUrl`, `oauth.authorizationUrl`; oauth2 without a usable token URL → `BEARER`; `openIdConnect` → `BEARER`, `oauth.discoveryUrl`, `oauth.flow=authorization_code`. Each choice adds a note.
  - Operations: for each path and method in order get, post, put, patch, delete. Name = `operationId`, else `[<method>_]<path with braces removed, non-alphanumerics → _, trimmed of _>` (no prefix for GET); then characters outside `[A-Za-z0-9_]` → `_`; duplicates suffixed 2, 3, …
  - Properties per operation `op.<name>.`: `method` (upper case), `path`, `query` (`p={p}` joined by `&` for query parameters), `required` (path parameters and required parameters, comma-separated; `-` when query parameters exist but none is required), `header.<p>` = `{p}`, `cookie.<p>` = `{p}`, `description` (summary, else description, ≤ 500), `responseRoot` from `x-ebx-response-root`, `pagination.next` from `x-ebx-pagination-next` else inferred (response properties `next`, `nextLink`, `next_link`, `nextPage`, `next_page` of type string with no format or `uri`/`url`, directly or under `links`, `pagination`, `paging`; a note asks to verify), `contentType=multipart/form-data` when the request body offers it, `fields` (flattened response schema), `bodyFields` (flattened request body schema). Path-level and operation-level parameters both used; local `$ref` resolved (max 20 hops, `~1`/`~0` unescaped).
  - Response schema: first of responses `200`, `201`, `2XX`, `default`, else the first response; media `application/json`, else the first media type containing `json`.
  - Flattening: `path=type` entries; objects recurse; `allOf` merged; `oneOf`/`anyOf` → first alternative when no properties; arrays → `<path>[0]`; types `date` (string/date), `datetime` (string/date-time), `number` (integer or number), else the JSON type; max 300 entries, max depth 6.
  - Chunking: `op.<name>.fields`, `.fields.2`, … each ≤ 1900 characters, entries separated by `;`. `fields(config, key)` reads up to 99 chunks back.

---


### 2.16 Protocol and catalog helpers used by connector types

- `Xml.parse`: DOCTYPE refused (`XML document type declarations are not allowed`), external entities/DTD/XInclude disabled. `toJson`: namespaces dropped, attributes → `@name` (xmlns skipped), repeated children → array, text-only element → string, mixed → `#text`.
- `Csv.parse(text, separator, max)`: RFC 4180 quoting; first line = header; blank lines skipped; missing cells → null; result `{rows, columns, count, available, truncated}` (`available`/`truncated` added 2026-09-28: the real record count, free to compute since every record is already walked to find boundaries, and `truncated = available > count`); separator `"`, CR or LF refused; unterminated quote refused. `line`/`quote`/`header` for writing.
- `SoapSupport`: SOAP 1.1 (`text/xml`, `SOAPAction`) or 1.2 (`application/soap+xml; action=`) envelopes; WS-Security UsernameToken PasswordText or PasswordDigest (SHA-1 of nonce + created + password); fault → `<connector>: SOAP fault on <op> [<code>]: <reason> — <detail>`; WSDL 1.1 parsing (operations, SOAP action, endpoint, version, input element fields typed number/boolean/date/string, request template); WS-Policy and XML Signature documents refused; import graph bounded (defaults depth 4, 24 documents, 4 MiB, 15 s; hard limits 32, 1024, 64 MiB, 120 s), same origin, HTTP(S) only, no embedded credentials.
- `ODataSupport`: query options (`$select`, `$expand`, `$filter`, `$orderby`, `$top`, `$skip`, `$search`, `$skiptoken`, `$deltatoken`, `$apply`, also accepted without `$`); V2 adds `$format=json`; collections get `$top` = input `max`, else the given maximum, else `500`, and `$count=true` (V4) or `$inlinecount=allpages` (V2). Normalisation removes `__metadata`, `@odata*` annotations and `__deferred` links, unwraps V2 `results`, converts `/Date(ms)/` to ISO instants, marks removed entities `_deleted`/`_removedReason`. Collections → `{results, count, totalCount?, nextLink?, deltaLink?}`; page collection bounded, same-origin continuations. V4 JSON batch and V2 multipart batch (mutations in one changeset) builders and parsers (bounded parts/bytes). `$metadata` parsing per entity set (name, SAP label, Edm type, nullable, key, max length, precision, scale, navigation properties).
- `ODataBatchAdapter`: maps batch responses back to request items in order; missing → error `OData batch response omitted this item`; duplicate id → exception; non-2xx → item error (body truncated to 500).
- `MessagingTransport`: provider-neutral publish/receive contract (acknowledgement modes AUTO, CLIENT, DUPS_OK, TRANSACTED; peek; selector; redelivery settings).
- `CatalogOperations` (Snowflake, Databricks, EBX catalog): documents `catalog`, `profile`, `sample`, `usage`, `lineage` built from SQL statements of a `Dialect`.
  - Input `source` required. `schemas` (comma list, `*` or blank → all non-system schemas, or the connector default), `types` (default `TABLE,VIEW`; materialised views included with VIEW), `tables` (qualified names, case-insensitive; default all), `where` (profile), `limit` (sample, default 50, clamped 1–500).
  - Keys: `assetKey = <source>:<SCHEMA>.<TABLE>`, `columnKey = <assetKey>.<COLUMN>`, `edgeKey = <from>><to>`.
  - Type normalisation: JSON, STRING, BOOLEAN, TIMESTAMP, DATE, TIME, BINARY, INTEGER, DECIMAL, OTHER.
  - Profile: one aggregate query per table over up to 200 columns: row count, null count/rate, distinct count, unique rate (distinct / non-null), min/max (≤ 500 chars) for string/numeric/date types, mean (numeric), min/max/avg length (string); rates rounded to 4 decimals; `method = FULL_SCAN`, or `SAMPLE` for a table at or above `largeTableRows` (default 10,000,000), with `sampleSize`, `samplePercent`, `distinctMethod`, `exact`; per-run budget `maxTables` (200) / `maxSeconds` (900), `largeTables`, `sampleRows`, `approxDistinct`, `dryRun` (OPERATIONS §3.3, audit C-15).
  - Sample: string columns only (≤ 200), distinct non-blank values (≤ 200 chars each), `valuesText` newline-joined.
  - Usage/lineage require dialect SQL; absent → `usage: this platform exposes no query history` / `lineage: this platform exposes no lineage views`; lineage edges deduplicated, `transformationType=UNKNOWN`, `origin=PLATFORM`.
  - Each operation returns null when it produced nothing (hence `NO_DATA`).
- `RecordProfiler`: same statistics computed in memory over records read (`method = SAMPLE`, `sampleSize`), `rowCount` = source count when larger than the sample, `nullCount` = sampled null rate × row count, distinct set capped at 100000 values; field paths `Group/field`; empty strings, empty arrays and empty objects count as null.
- `PowerBiScan`: interprets a Power BI Scanner result into catalog and lineage documents (assets REPORT, DASHBOARD, SEMANTIC_MODEL, DATAFLOW, TABLE; names `<workspace>/reports/<name>`, `/dashboards/`, `/models/<model>[.<table>]`, `/dataflows/<df>[.<entity>]`, `datasources/<server>[/<db>]/<schema>.<table>`); options `source` (`POWERBI`), workspaces filter, kinds, `datasourceMap` (`server[/database]=SOURCE[:upper|:lower]`, entries separated by newline or `;`), `externalAssets` (true), `includeHidden` (false), `includePersonal` (false), `portalUrl` (`https://app.powerbi.com`). Deleted/removing workspaces skipped. Power Query M read with a lexer (sources by function prefix, navigation records, native SQL `FROM/JOIN` tables, renames, added/removed/transformed columns; edge type = highest of IDENTITY < FILTER < TRANSFORMATION < AGGREGATION < JOIN); DAX references `Table[Column]`, `'Table'[Column]`, `[Measure]`, measures with aggregate functions typed AGGREGATION. Confidence values 70–100. Usage from `ViewReport`/`ViewDashboard`/`ViewTile` activity events; refresh jobs with statuses COMPLETE, FAIL, ABORT, RUNNING, OTHER. Unrecognised expressions give notes or table-level edges, never exceptions.

---


### 2.17 Operations runtime, metrics, alerts, connection state, inspection

#### 2.17.1 ConnectorOperationsRuntime

- Process-wide observer, default no-op; `install(observer)` returns a closable registration. At repository startup the module installs `MetricsAndAlertsObserver` with the EBX metrics persistence and **no alert hook** (`null`).
- `observe` is called after every `SyncLog` write; `checkpoint(connector, at)` by `SynchronizationCheckpointManager.complete`; `schemaDrift(connector, at)` by REST schema refresh. Observer failures are logged as warnings.
- `MetricsAndAlertsObserver.onResult`: REQUEST +1 and duration sample; READ +1 for `INBOUND`/`IMPORT`; WRITE +1 for `OUTBOUND`; SKIP +1 for SKIPPED, NO_CHANGE, NO_DATA; CONFLICT + number of conflicts; ERROR → failure with context `<operation> on <table> [<key>]: <message>`, otherwise success. RETRY and THROTTLE counters exist but are never incremented by this observer.

#### 2.17.2 ConnectorOperationalMetrics (table `ConnectorOperationalMetrics`)

- Counters READ, WRITE, SKIP, CONFLICT, REQUEST, RETRY, THROTTLE (non-negative, saturating at Long.MAX).
- Duration samples: last 256 (`MAX_DURATION_SAMPLES`); percentile = nearest-rank.
- Success: lastSuccessAt, consecutiveFailures reset to 0. Failure: lastFailureAt, lastFailure message, consecutiveFailures + 1.
- Health: UNKNOWN (never ran), FAILED (≥ 3 consecutive failures), DEGRADED (≥ 1), HEALTHY.
- checkpointAt and checkpoint age; schemaDriftAt and schemaDriftCount.

#### 2.17.3 RepeatedFailureAlertHook (not installed by the module)

Threshold ≥ 2; publishes once when a (connector, mapping) failure streak reaches exactly the threshold and a permission policy allows the actor; a success resets the streak; message redacted.

#### 2.17.4 ConnectorConnectionStateService

- States NOT_CONNECTED, CONNECTED, STALE, FAILED. `verifyAndRecord` runs `testConnection`: unsupported or unsuccessful → FAILED; success → CONNECTED with verifiedAt now; `ConnectorException` → FAILED with `Connection verification failed`, rethrown. Messages redacted and truncated to 500.
- Material fingerprint = SHA-256 of type, baseUrl, authType, username, password, apiKeyHeader, apiKey, timeouts and sorted properties (not label, trust, active or logo).
- `status`: stored CONNECTED whose fingerprint differs → STALE, `Connection settings changed; connect again to verify them`. `disconnect` → NOT_CONNECTED, `Disconnected`.

#### 2.17.5 Bounded inspection (`BoundedInspectionRuntime`, `PreviewRequest`, `PreviewResult`, `InspectionPreview`, `MetadataCache`, `MetadataCacheKey`)

- Limits (`PreviewRequest`): records default 25 / max 100; timeout default 5000 ms / max 15000 ms; payload default 256 KiB / max 1 MiB; non-positive requests fall back to defaults.
- Discovery, description and preview run on a daemon cached thread pool (`ebx-connector-inspection`) with the timeout (`Inspection exceeded the <n> ms time limit`); results masked with a `SensitiveDataMasker` built from the connector properties, password and apiKey; truncated to record and byte limits.
- Discovery and description cached in `MetadataCache` (default TTL 30 s, max 300 s) under a key (connector type, operation, SHA-256 of connector id + base URL + scope); previews are not cached.

---


### 2.18 Configuration export/import and seeding (implemented in `ebx/`, used by the engine)

#### 2.18.1 Export (`ebx/ConfigTransfer.export(repository, connector?, withMappings)`)

- Document `{ "format": "ebx-connectors-config", "version": 1, "connectors": [...], "mappings": [...] }`; only `format` and `version` when the dataset is missing.
- Connector entry: `id`, then the non-null fields `label`, `type`, `baseUrl`, `authType`, `username`, `apiKeyHeader`, `connectTimeoutMs`, `readTimeoutMs`, `active`, `trust`, `logo`, `description` (Booleans and Integers typed, others as XS strings); `secrets: {apiKey: <is set>, password: <is set>}` (never the values); `properties: {key: value}` where a secret-looking key (contains `secret`, `password`, `token`, `privatekey`, `apikey` or ends with `.key`, case-insensitive) with a real value that is not a `env:|file:|vault:|azurekv:` reference is exported as `TO_BE_SET`.
- Optional connector filter applies to connectors and to mappings (by `connector`).
- Mapping entry (root mappings only, `osd:is-null(./parent)`): `id`, non-null fields `connector`, `operation`, `direction`, `label`, `description`, `dataspace`, `dataset`, `tablePath`, `responseRoot`, `active`, `collectionPath`, `parentLinkField`, `childKeyFields`, `childFilter`, `deleteMissing`, `itemsPath`, `keyPredicate`, `createIfMissing`, `loadMode`, `checkpointStrategy`, `checkpointInput`, `checkpointValuePath`, `checkpointInitialValue`, `deleteOperation`, `maxAttempts`, `retryDelayMinutes`, `provenance`, `unchangedIfEqual`, `checkpointBoundary`, `batchSize`, `logEveryRecord` (every Mapping field of the model except the key, the parent link and the three associations, which the document carries by its own structure; `ConfigTransferFieldsTest` fails when a model field is neither transferred nor listed with the reason it is not); `inputs: {name: expression}`; `rules: [{position, sourcePath, targetPath, transformation, policy, active, comment}]` sorted by position; `children: [...]` recursively.

#### 2.18.2 Import (`ConfigTransfer.importConfig(session, repository, document, dryRun)`)

- Refused without `"format": "ebx-connectors-config"` (`not an ebx-connectors-config document ...`) or without the dataset. `version` is not checked.
- One procedure, all privileges, **triggers disabled**; dry run = executed then rolled back.
- Connectors matched by `id`: created (fields applied, `apiKey` and `password` set to `TO_BE_SET`) or updated (listed fields only; secrets never touched). Properties matched by (connector, key): created, or updated unless the incoming value is a placeholder while the existing one is set.
- Mappings: root matched by connector + operation + direction + tablePath + label (label null → ""); child matched by parent id + label. Created or updated with the listed fields (null JSON values ignored). When `rules` is present they are all deleted and recreated with positions 10, 20, … (incoming `position` ignored); when `inputs` is present they are replaced. Children inherit `connector`, `operation`, `direction`, `dataspace`, `dataset` from the parent document when blank.
- Report: `dryRun`, `error?`, `notes`, `connectorsCreated`, `connectorsUpdated`, `propertiesWritten`, `mappingsCreated`, `mappingsUpdated`, `rulesWritten`. The connector cache is invalidated.
- `upsertMapping` (MCP/REST): requires `connector`, `operation`, `direction`, `dataset`, `tablePath`, `label` and an existing connector; checks the dataspace (a blank one stays blank, an ambiguous or non-existent one is refused, §4.2); `feed {itemsPath, keyPredicate, createIfMissing}` flattened (note when no `keyPredicate`); `id` + `updateById` updates that mapping when the natural key finds nothing; schedules declared lineage (retiring the previous label on rename).
- `deleteMapping` / `cascade`: deletes child mappings recursively, rules, inputs, synchronisation checkpoints and outbound states; sets `mapping` to null on `FieldProvenance` and `Conflict` rows; SyncLog kept; schedules lineage retirement.

#### 2.18.3 Seeding (`ebx/ConnectorsInstaller`, called at repository startup)

- Creates dataspace `Connectors` (child of the reference dataspace, owner administrator) and dataset `Connectors` on the module model when missing. If an existing `Connectors` dataset uses another module's model: installation stops with an error, nothing deleted.
- **Seed once (audit C-26, `SeedMarkers`).** The installer records what it creates in table `SeedMarker` (key `item`: `connector:<id>`, `mappings:<group>` for the D&B and Melissa mapping backfills, `migration:<name>`, `baseline`) - `connector(...)` writes the marker in the procedure that creates the row - and a seed group, a wave catalogue card or a backfill is created only when its item has no row **and** no marker. At the first start that sees the table (`baseline` absent) every connector in the repository, and the D&B / Melissa mapping groups whose mappings are all there, are recorded in one procedure; an item absent then is created once. `-Debx.connectors.seed=none` skips every connector, mapping and catalogue-card seed (`all`, the default, and any other value, seed); categories, templates, release notes, migrations and the baseline still run. The base seed (GLEIF, Sirene, GoogleGeocoding) runs only on an empty `Connector` table whose `connector:GLEIF` marker is absent. The D&B mapping backfill needs the `DnB` connector row. A mapping group is marked only when one of its mappings was actually created (a skipped mapping - table missing - is retried at the next start). A model without the `SeedMarker` table has no markers and every rule reads as before. `FieldProvenance`'s primary-key check (below) is a `migration:fieldProvenanceKey` marker: it reads the rows with a null key part as a stream, once, instead of the whole table at every start.
- Seed groups, each run only when its marker connector id has no row and no marker: base seed (empty Connector table), `Salesforce`, `Kafka`, `Snowflake`, `Databricks`, `SAP`, `JDEdwards`, `DnB` (these six require the `Mapping/parent` field), catalogue (`VIES`, `SQLServer`, `AzureSQL`, `AzureServiceBus`, `NetSuite`, `SharePoint`, `MySQL`), `OpenFDA` group, LLM providers (`Gemini`, `Mistral`), `PowerBI`, `Collibra`, Melissa address normalisation mapping when no Melissa mapping exists.
- At least 61 distinct connector ids are created through the seed helpers (e.g. GLEIF, Sirene, GoogleGeocoding, VIES, BAN, Salesforce, SAP, Kafka, Snowflake, Databricks, PowerBI, Collibra, EBX, OpenAI, Claude, Gemini, Mistral, Ollama, AzureOpenAI, Webhook, JDBC, H2, PostgreSQL, S3, SFTP …).
- Seeded connector row: id, label, type, baseUrl, authType, apiKeyHeader, apiKey (placeholder `TO_BE_SET` where a key is needed), description, category (`seedCategory`), `active = true`, trust = `defaultTrust(type)`, logo when a packaged logo exists; plus properties.
- `defaultTrust(type)`: gleif, sirene, companies-house, inpi 90; dnb, orbis, handelsregister, openfda 85; vies, creditsafe, opensanctions 80; melissa, loqate 75; ban, opencorporates, sap-odata, jde-ais, ebx 70; google-geocoding, salesforce, dynamics365, oracle-fusion, hubspot, servicenow, workday 60; wikidata 40; llm 30; otherwise the descriptor's default trust level, else 50.
- Categories (created when missing): AI (10), BUSINESS_APPS (20), CLOUD_STORAGE (30), DATA_PLATFORMS (40), DATABASE (45), MESSAGING (50), REFERENCE_DATA (60), CORPORATE_REGISTRIES (70), RISK_COMPLIANCE (80), GENERIC_INTEGRATION (90). `seedCategory`: `Countries` → REFERENCE_DATA, `EORI` → RISK_COMPLIANCE, else descriptor category, else a legacy type table, else GENERIC_INTEGRATION.
- Demonstration mappings: `-Debx.connectors.seedMappings` = `auto` (default; any other value is treated as auto), `always`, `never`. In `auto` a mapping is skipped when its table is not found in the named dataset or an input references a missing field; a sub-object mapping is skipped when its table, link field or key fields are missing; each rule whose EBX-side path (target inbound, source outbound) is not a field is dropped; every skip logs `[connectors] seed: ...`. `never` skips all mappings. Seeded rules get positions 10, 20, … and `active = true`; seeded mappings `active = true`; children `deleteMissing = false`.
- Migration after seeding (never overwrites non-null values): categories created; `authType = OAUTH2_CLIENT_CREDENTIALS` → `OAUTH2`; `A_RENSEIGNER` in username/password/apiKey and property values → `TO_BE_SET`; logo set when absent (EBX generated logo replaced by the preferred one); trust initialised when null; category initialised when null; mapping `dataspace` normalised to the branch name of the dataspace actually holding the dataset.

#### 2.18.4 Packaged templates (`ProductConnectorTemplates`, `ConnectorTemplateService`, `ConnectorTemplateTransfer`)

- Packaged templates (version 1, ownership PACKAGED, id `product.<x>`, inactive, category BUSINESS_APPS, credential-free): salesforce (`Salesforce`, type `salesforce`, `https://login.salesforce.com`, NONE, clientId TO_BE_SET, oauth.flow client_credentials, apiVersion v60.0), sap (`SAP`, `sap-odata`, SAP sandbox Business Partner OData, API_KEY_HEADER, odata.version 2, entitySet A_BusinessPartner, max.rows 200), oracle-fusion (BASIC, apiVersion 11.13.18.05, family crmRestApi), dynamics365 (BEARER, tenantId/clientId TO_BE_SET, apiVersion v9.2), workday (BASIC, tenant/clientId TO_BE_SET), servicenow (BASIC, max.rows 1000, display.values false), hubspot (BEARER, max.rows 1000).
- Template ids `[A-Za-z0-9][A-Za-z0-9_.-]{0,127}`, version ≥ 1; PACKAGED templates have no origin; CUSTOMER templates must keep `basedOn` + `basedOnVersion`.
- Interchange format `{"format": "ebx-connectors-templates", "formatVersion": 1, "templates": [{id, version, ownership (lower-case), basedOn?, basedOnVersion?, configuration}]}`, sorted by id.
- Export redacts every configuration key whose normalised name contains `password`, `secret`, `apikey`, `token`, `privatekey` or `credential` to `***`. Import refuses any such key with a non-null value other than `***` (`Template imports must not contain credentials or secret values`); CUSTOMER templates are only created (existing id → `Import will not overwrite template <id>`); PACKAGED templates created, upgraded when the version is higher, unchanged when equal, downgrade refused; a packaged import cannot replace a customer template.
- `copy` creates a CUSTOMER template version 1 based on the packaged origin; `upgradeCopy` creates a new copy from a newer packaged version (`No newer packaged version is available` otherwise).

---


### 2.19 Caching and concurrency summary

| Cache / lock | Scope | Lifetime / rule |
|---|---|---|
| Mapping definitions | none | re-read from EBX on every lookup |
| Connector instances (`ConnectorRegistry`) | JVM | kept while the configuration fingerprint is unchanged; config re-read at every call; `invalidate()` on config import and at module stop; lookups synchronized on the registry. An instance that is replaced, switched off, removed or invalidated is closed (`Connector.close()`), outside the registry lock |
| Secret references (`SecretResolvers`) | JVM | `ebx.connectors.secrets.cacheSeconds` (300 s) |
| Remembered secrets for redaction | JVM | up to 4096 values, never expire |
| OAuth tokens | JVM, per connector config + token URL | until expiry − skew; dropped after a 401 |
| Azure Key Vault identity token | resolver instance | expires_in − 60 s |
| Google token (`GoogleAuth`) | instance | 50 minutes |
| TLS contexts | JVM | forever, keyed by store paths and passwords |
| Egress allow-list file | JVM | re-checked every 30 s, re-read on mtime change |
| OpenLineage sinks | JVM | 30 s |
| Metadata cache (inspection) | runtime instance | 30 s default, 300 s max |
| Conflict decisions | JVM | 256 striped locks + live status re-check (single node) |
| OAuth token fetch | JVM | 256 striped locks |
| Checkpoint save (`saveCheckpoint`) | EBX transaction | optimistic version check: `Checkpoint was advanced concurrently` |
| Retention purge | one daemon thread, unless retention is off (`retentionDays=0`) | one pass per `intervalMinutes` (60), batches of 500 rows per transaction, reads outside the deleting procedure (§2.9.1) |
| Declared lineage | single scheduler thread | debounce per mapping |

Each record synchronisation and each feed item runs in its own EBX procedure; SyncLog, provenance, conflicts and outbound state are written in further separate procedures, so a failure writing them does not roll back the business change. Such a failure is never silent: §2.22.2.

---


### 2.20 Framework components and their wiring status

| Component | Capability | Wired into the module? |
|---|---|---|
| `SynchronizationCheckpointManager`, `CommittedIncrementalExecution`, `SynchronizationCheckpoint`, `InMemorySynchronizationCheckpointStore`, `ConnectorRepository.checkpoint/saveCheckpoint`, `ebx/EbxSynchronizationCheckpointStore` | Incremental load: injects the checkpoint (stored value, else `checkpointInitialValue`) into input `checkpointInput`; after a successful non-dry run reads the new value at `checkpointValuePath` (dotted path converted to JSON Pointer, or a `/` pointer; empty path = whole response; non-scalar → JSON text), refuses an empty value (`Successful incremental response has no checkpoint value`) and saves with version + 1; stored strategy must equal the mapping's; checkpoint advances only after commit | **Not the manager itself** (volume lot V2-D wired the same `SynchronizationCheckpoint` table and `ConnectorRepository.checkpoint/saveCheckpoint` for a row-based feed watermark, §2.14.7; `ConnectorService.importFrom` reads and saves the checkpoint through the repository directly). No caller outside `core` instantiates the manager or the EBX store. An INCREMENTAL feed with a row-watermark strategy reads from its checkpoint (§2.14.7); any other INCREMENTAL mapping is only validated (§6.4) and otherwise runs like FULL |
| `RecoverableExecution`, `DurableExecutionStateStore`, `OperationalExecutionService`, `ebx/EbxRecoverableExecutionStore` | Restartable bounded work: hard caps 1000 records and 10 attempts; deletion policy PROPAGATE/TOMBSTONE/IGNORE; statuses SUCCESS, ERROR, DEAD_LETTER, CANCELLED, DRY_RUN, DELETION_IGNORED; idempotency key `ebx-` + SHA-256(runId + newline + itemId); work ids `[A-Za-z0-9._:-]{1,160}`; dry runs never save state; non-retryable or exhausted failures become dead letters; replay of dead letters resets them; leases up to 1 hour (service uses 5 minutes) with `Execution is already leased`; permission check `Execution operation is not permitted` | **No.** The EBX store class exists but nothing uses it or the service |
| `ConnectorGovernanceRecord/Service/ApplicationService/Store/Access/AuditEvent`, `InMemoryConnectorGovernanceStore` | Approval DRAFT → PENDING → APPROVED/REJECTED (approve only from PENDING), publication version +1 only when approved and effective, precedence resolution (highest `sourcePrecedence`, tie by id), transitive dependency impact, secret references restricted to `vault:`, `env:`, `file:`, `azurekv:`, audit events CONFIGURED/SUBMITTED/APPROVED/REJECTED/PUBLISHED, permissions READ/MANAGE/APPROVE/AUDIT | **No** caller outside core; the `ConnectorGovernance` and `ConnectorGovernanceAudit` tables are declared in `ConnectorsPaths` only |
| `ConnectorTemplateLibrary` | In-memory template library with the same ownership rules | Not used (the service + store are) |
| `RepeatedFailureAlertHook` | Failure streak alerts | Not installed (observer created with `null`). It cannot be wired as it stands: (1) it needs an `AlertProvider`, and the module has no delivery channel (mail, webhook, a connector operation) nor a configured recipient - a product decision; (2) it is fed one `SyncResult` at a time, so three failing rows in a row inside one run would raise a "failed three times" alert - it must be fed per run before it means anything; (3) its only `FailureHistory` is in memory. Until then a failing scheduled run is visible because the execution fails (§4.6.1) |
| `RestPagination`, `ConditionalRequest`, `Jwt` (directly), `Tls.contextFor` (outside `AbstractRestConnector`), `OAuth2.discover`, `PreviewResult.collect`, `MetadataCache` (outside inspection) | Helpers | No direct callers outside core (they may be used inside core) |

---


### 2.21 Defaults and constants

#### 2.21.1 JVM system properties read by the engine

| Property | Default | Effect (class) |
|---|---|---|
| `ebx.connectors.allowedHosts` | unset (no restriction) | outbound allow-list, `file:<path>` supported (`Egress`) |
| `ebx.connectors.egress.requireAllowList` | unset (`false`) | `true`: outbound calls refused while `ebx.connectors.allowedHosts` is unset (`Egress`); production setting |
| `ebx.connectors.egress.blockPrivate` | on (`false` switches off) | loopback, private and unresolvable hosts refused unless covered by `privateExceptions` or an exact allow-list entry (`Egress`) |
| `ebx.connectors.egress.privateExceptions` | unset | private targets that stay allowed: host, host:port, address, CIDR, `file:<path>` (`Egress.PRIVATE_EXCEPTIONS_PROPERTY`) |
| `ebx.connectors.secrets.allowClear` | `false` | `true` accepts secrets stored in clear (`Secrets.ALLOW_CLEAR_PROPERTY`); otherwise they are refused at use |
| `ebx.connectors.secrets.cacheSeconds` | `300` | secret reference cache TTL (`SecretResolvers`) |
| `ebx.connectors.secrets.dir` | unset (`file:` references refused) | directory that confines `file:` secret references (`SecretResolvers.SECRETS_DIR_PROPERTY`); production setting |
| `ebx.connectors.secrets.envAllowed` | unset (`env:` references refused) | names / `PREFIX*` that `env:` secret references may read, system properties included (`SecretResolvers.ENV_ALLOWED_PROPERTY`); production setting |
| `ebx.connectors.vault.address` / `.token` / `.namespace` | env `VAULT_ADDR` / `VAULT_TOKEN` (or `~/.vault-token`) / `VAULT_NAMESPACE` | Vault resolver |
| `ebx.connectors.azure.tenantId` / `.clientId` / `.clientSecret` / `.identityEndpoint` / `.identityHeader` | env `AZURE_TENANT_ID` / `AZURE_CLIENT_ID` / `AZURE_CLIENT_SECRET` / `IDENTITY_ENDPOINT` / `IDENTITY_HEADER` | Azure Key Vault resolver |
| `ebx.connectors.log.retentionDays` | `90` (**purge on**); `0` = off | purge of SyncLog, decided conflicts, finished ExecutionRun rows and Publication reports older than N days (`Retention`, §2.9.1) |
| `ebx.connectors.retention.syncLogDays` / `.conflictDays` / `.executionRunDays` / `.publicationDays` | the value of `retentionDays` | age for that table; `0` keeps the table for ever; ignored while `retentionDays` is 0 |
| `ebx.connectors.retention.outboundStateDays` / `.provenanceDays` | unset (kept) | opt-in: `DELETED` outbound states, and provenance rows of records that no longer exist, older than N days |
| `ebx.connectors.retention.intervalMinutes` | `60` (minimum 1) | delay between two passes |
| `ebx.connectors.retention.batchSize` | `500` (1..5000) | rows deleted per transaction |
| `ebx.connectors.retention.maxRowsPerPass` | `50000` (at least one batch) | rows deleted per table and per pass |
| `ebx.connectors.log.storeChanges` | `true` | `false` stores no `changes` in SyncLog |
| `ebx.connectors.import.async.maxConcurrent` | `4` (minimum 1) | asynchronous imports running at once (`ConnectorService`); read at class load |
| `ebx.connectors.feed.keyIndex.maxRows` | `2000000` (`0` = no index) | most records of the target table that an import feed holds in memory to find its records by business key instead of one XPath scan per row (`FeedKeyLookup`, §14.3); a bigger table is looked up row by row, one WARN; read when each run starts |
| `ebx.connectors.feed.childPreload` | `true` | `false`: sub-object mappings of a batched import feed ask EBX for each child element (one XPath per element) instead of matching it in rows read once per segment (`ChildRowPreload`, §2.4.3) |
| `ebx.connectors.import.async.maxQueued` | `16` (minimum 0) | asynchronous imports accepted beyond that, waiting for a thread; a start beyond both is refused (`TooManyRunsException`, REST 429) |
| `ebx.connectors.shutdown.waitSeconds` | `10` | total time a stop of the module waits for its background threads (`ConnectorsModuleRegistration`) |
| `ebx.connectors.http.maxResponseBytes` | `134217728` (`0` = none) | default ceiling on one HTTP answer when the caller passes no limit (`HttpJsonClient.defaultMaxResponseBytes`, audit C-16); error "HTTP response exceeds byte limit N" |
| `ebx.connectors.publish.maxRowsPerTable` | unset (`0` = no ceiling) | operator ceiling on rows read per table by a product publication (`PublicationBatcher.effectiveMax`, with the request's `max`; the smaller positive value applies). A cut ends the run `PARTIAL` (audit C-17) |
| `ebx.connectors.lineage.declared` | `true` | declared lineage on/off (`DeclaredLineage`) |
| `ebx.connectors.lineage.declareOnStartup` | `true` | startup sweep |
| `ebx.connectors.lineage.startupDelaySeconds` | `90` | delay of the sweep |
| `ebx.connectors.lineage.debounceMs` | `2000` | debounce of mapping changes |
| `ebx.connectors.seed` | `all` | `none`: the installer creates no connector, mapping or catalogue card (`ConnectorsInstaller.SEED_MODE_PROPERTY`, `SeedMarkers.Mode`) |
| `ebx.connectors.seedMappings` | `auto` | demonstration mapping seeding (`ebx/ConnectorsInstaller`) |

#### 2.21.2 Code constants

| Constant | Value | Class |
|---|---|---|
| Connector connect / read timeout | 10000 / 30000 ms (values ≤ 0 → default) | `spi/ConnectorConfig` |
| Connector default trust | 50, clamped 0–100 | `ConnectorConfig`, `ConnectorRepository` |
| `TrustOracle.MANUAL_TRUST` | 100 | `TrustOracle` |
| Mapping `maxAttempts` / `retryDelayMinutes` | 5 (min 1) / 30 (min 0) | `MappingDefinition` |
| Outbound dry-run document preview | 500 characters | `ConnectorService` |
| SyncLog recordKey / label / message / changes | 255 / 255 / 2000 / 4000 | `ConnectorRepository.log` |
| OutboundState key / label / lastError / lastDocument | 255 / 255 / 2000 / 8000 | `markOutbound` |
| Provenance value | 2000 | `recordProvenance` |
| Conflict recordLabel / comment | 255 / 500 | `logConflicts`, `ConflictResolver` |
| Retention first pass / pause between batches | 5 min after startup / 100 ms | `Retention` |
| Redaction remembered secrets / min length | 4096 / 6 | `Redaction` |
| Masker secrets / min length | 128 / 4 | `SensitiveDataMasker` |
| HTTP retry | 2 attempts, 2000 ms base, 30000 ms cap | `HttpRetryPolicy.defaults` |
| Vault / Azure resolver timeouts | 5000 connect / 10000 read ms | `SecretResolvers` |
| OAuth `oauth.expiresInSeconds` / `oauth.jwtLifetimeSeconds` | 3600 / 300 | `OAuth2` |
| Egress file recheck | 30 s | `Egress` |
| OpenLineage sink cache | 30 s | `OpenLineageEmitter.TARGETS_TTL_MS` |
| Declared lineage startup attempts / retry | 3 / 120 s | `DeclaredLineage` |
| OpenAPI property chunk / fields / depth | 1900 chars / 300 / 6; description 1900; op description 500 | `OpenApiImporter` |
| Preview records / timeout / payload | 25 (max 100) / 5000 ms (max 15000) / 256 KiB (max 1 MiB) | `PreviewRequest` |
| Metadata cache TTL | 30 s (max 300 s) | `MetadataCache` |
| Metrics duration samples / DEGRADED / FAILED thresholds | 256 / 1 / 3 consecutive failures | `ConnectorOperationalMetrics` |
| Recoverable execution caps / lease | 1000 records, 10 attempts / 1 h max, 5 min in service | `RecoverableExecution`, `DurableExecutionStateStore`, `OperationalExecutionService` |
| Catalog profile columns / sample limit / value lengths | 200 / 50 (1–500) / min-max 500, sample values 200 | `CatalogOperations` |
| RecordProfiler distinct cap | 100000 | `RecordProfiler` |
| OData default `$top` | 500 | `ODataSupport` |
| SOAP import defaults | depth 4, 24 docs, 4 MiB, 15 s | `SoapSupport.ImportLimits` |
| Json flatten depth | 12 | `Json` |
| fk/lookup FK search depth | 12 | `Transformations` |
| `CredentialGuide.SECRET_MAX_LENGTH` | 255 | `CredentialGuide` |
| Run id sequence | modulo 10000 | `ConnectorService` |

---


### 2.22 Background work and bookkeeping: what is guaranteed

This section states the rules the engine follows for work that runs outside the caller's own
request and for the rows it keeps about a run. It overrides any older sentence of §2.9, §2.14 and
§4.6 that says otherwise.

#### 2.22.1 An asynchronous import always reaches a terminal state

- `ConnectorService.importFromAsync` writes the `ExecutionRun` row `RUNNING` on the caller's thread,
  then hands the run to the executor. The whole body of the worker - minting its session included -
  runs inside `AsyncRunGuard.run`, which catches `Throwable`: an exception that escapes `importFrom`,
  but also an `Error` (`NoSuchMethodError`, `NoClassDefFoundError`... what an EBX internal that changed
  with a product build throws). In every such case the row is written one last time: `ERROR`, or
  `INTERRUPTED` when the worker thread was interrupted (module or server stopping), with a redacted
  message naming the kind of failure. The resume position (`nextIndex`) the run already saved is kept.
  A `VirtualMachineError` is rethrown after that write was attempted.
- When the terminal write itself fails, the reason is logged at ERROR and the row is corrected at the
  next start (`markInterruptedRunningRuns`).
- The worker's session comes from `WorkerSessions.forWorker(callerSession, repository)`, the only
  place of the module that calls the internal `com.onwbp.adaptation.SessionImpl.createSession(UserReference,
  Repository)`. EBX offers no public API that builds a session for an already authenticated user
  without credentials, so the call is made by reflection and **every** failure (class or method no
  longer there, linkage error, refusal) falls back to the caller's own session, with a WARN in the
  kernel log. Not verified under an SSO directory.
- Tests: `AsyncRunGuardTest` (an `Error` or an exception from the body ends in `ERROR`; an interrupt in
  `INTERRUPTED`; the message is redacted; a failing terminal write does not throw; the session
  fallback for each kind of failure; the internal factory still exists, public and static, in the EBX
  jar the suite compiles against). The real minting and the write of the row need a running EBX.

#### 2.22.2 A bookkeeping write that EBX refuses is reported, never thrown

`ProgrammaticService.execute` does not throw when its procedure fails: it answers a
`ProcedureResult` whose `hasFailed()` is true. The eight bookkeeping writes of
`ConnectorRepository` read that result through `Bookkeeping.committed(kind, context, result)`:

| Write | Kind | Answers | What a loss means |
|---|---|---|---|
| `log` | `sync log` | `boolean` | the line is missing from the log and from `connectors_run_get.calls` |
| `logPublication` | `publication report` | `boolean` | the report is not readable later by run id; a warning is added to the report returned to the caller |
| `recordProvenance` / `recordProvenanceBatch` | `field provenance` | `boolean` | a later TRUSTED rule compares against the previous provenance |
| `logConflicts` | `conflict proposals` | `boolean` | the steward does not see the proposals |
| `saveRunState` / `saveNextItemIndex` | `run state` | `boolean` | the `ExecutionRun` row is stale |
| `markInterruptedRunningRuns` | `run state` | count (0 when refused) | the rows keep reading `RUNNING` |
| `markOutbound` | `outbound state` | `boolean` | `SENT` lost: the record is sent again by the next `unsent`/`changed` scope; `FAILED` lost: the record is missing from the `failed` scope; `DELETED` lost: the deletion is attempted again |
| retention purge | `retention purge` | - | nothing was deleted by that pass |

Rules:

- **Never an exception, never a changed status.** The business write is committed; the status of
  the record, of the run and the counters stay what they are.
- **Always an ERROR line in the kernel log**, redacted, naming the kind, the run and the mapping, and
  the reason EBX gave. Throttled per kind: the first 10 losses are all logged, then one in 1000
  (with its occurrence number), so that a table refusing every write costs a long non-batched run a
  few lines, not one per row.
- **Carried to the caller.** A record-level result (`SyncResult`) lists the kinds it lost in
  `bookkeepingLost`; an import run (`ImportOutcome`) counts them by kind. REST and MCP answers add
  `bookkeepingLost` (object: kind → count; on an item of `results[]`: array of kinds) and
  `bookkeepingNotice` (one sentence) **only when something was lost**, so the answer of a healthy
  run is unchanged. The scheduler's execution information and the workflow report end with the same
  sentence; the synchronisation screen shows it for a feed.
- **Batched feeds pay nothing per row.** On the batched path the writes concerned are one per batch
  (provenance, batch log line, run state) or one per failing or conflicting item; the check is the
  boolean they return.
- **Outbound state.** When `markOutbound` answers false after a remote call, one more `SyncLog` line
  is written for the same run and record with status `PARTIAL` and a message saying which state was
  lost and what follows. `PARTIAL` and not `ERROR`: a replay of the run re-sends its `ERROR` lines and
  must not send a delivered record again because of this line.
- **Final run state.** `ConnectorService.saveFinalRunState`: if the full write (counters, error
  sample) is refused, a reduced write is attempted (status, resume position, message prefixed with
  `Final state saved without the counters of the last batch`). If that is refused too, the state is
  kept in memory (`Bookkeeping.lostRunState`, the last 500 runs): `connectors_run_get` and
  `GET /runs/{runId}` then answer `execution.status` from memory with `execution.stateNotSaved: true`,
  until the next restart, where the row is marked `INTERRUPTED`. The caller of a synchronous run sees
  `bookkeepingLost: {"run state": n}` either way.
- `ConflictResolver.accept` appends "the provenance of the field could not be updated" to its
  outcome message when that write is lost.
- Tests: `BookkeepingTest` (reading a `ProcedureResult`, counting, the throttle, redaction and bound
  of the log line, an outcome and a result that keep their status, the meaning of each lost outbound
  state, the bounded memory of lost run states). That a refused procedure really answers
  `hasFailed()` without throwing is EBX's contract; no test of this suite runs a repository.

#### 2.22.3 Background threads and clients stop with the module

A thread that outlives its web application keeps the application's class loader reachable, and the
kernel calling into it after a context reload (`Illegal access: this web application instance has
been stopped already`). Daemon threads only let the JVM exit; they do nothing for a reload.

**Executors.** Every executor of the module is declared through `core/BackgroundWork` and created on
first use, never by a class initialiser:

| Executor | Threads | Declared by |
|---|---|---|
| asynchronous imports | `ebx-connector-import-async-N`, at most `maxConcurrent`, idle ones released after 60 s | `ConnectorService` |
| declared lineage | `ebx-connectors-declared-lineage` (one) | `DeclaredLineage` |
| connector inspection | `ebx-connector-inspection` (cached pool, shared by every `BoundedInspectionRuntime`) | `BoundedInspectionRuntime` |
| retention | `ebx-connectors-retention` (one, only when retention is on) | `Retention` (§2.9.1) |
| perspective installation | `ebx-connectors-perspective` (one, only while its retries last) | `PerspectiveInstaller` (`BackgroundWork.track`) |

`EbxCatalog`'s `ebx-connectors-catalog-read` pool is created and shut down inside one call and is not
concerned.

**Stop** (`BackgroundWork.stop(waitMs)`, from both shutdown callbacks, §4.1.3), in two steps within
the wait:

1. Nothing new is accepted, what has not started is dropped (queued tasks, delayed tasks), and the
   running tasks get three quarters of the wait to end by themselves. They are not interrupted yet:
   a task may be inside an EBX transaction, and an interrupt delivered to a thread that is reading or
   writing a file closes the file's channel under every other user of it - an index of the
   repository, in a kernel that keeps running when only the module is unloaded. Batched imports were
   asked to stop first (`requestStopOfAsyncImports` = `requestCancel` on each): they end at their next
   batch boundary with their own final state (`STOPPED`, resume position saved). The retention task
   looks at `BackgroundWork.isStopped()` between two batches.
2. Whatever still runs is interrupted (`shutdownNow`) and given the rest of the wait. An executor
   that has still not terminated is named in a WARN; a run it was executing reads `RUNNING` until the
   next start marks it `INTERRUPTED`. An import that was only waiting for a thread is dropped with
   the queue and ends the same way.

After the stop, `Managed.get()` refuses (`RejectedExecutionException`): a table trigger firing during
the shutdown starts no thread. `DeclaredLineage` drops the declaration (the sweep of the next start
catches up), an inspection answers `The connectors module is stopping`, an asynchronous import is
refused and its row written `INTERRUPTED`. `BackgroundWork.start()` at the next repository startup
allows executors again (a repository restarted inside the same class loader).

**Asynchronous imports are bounded.** `RunSlots` admits `maxConcurrent + maxQueued` runs (4 + 16 by
default, §2.21.1). The slot is taken **before** the `RUNNING` row is written and given back when the
run ends, so a refused start leaves nothing behind: `importFromAsync` throws `TooManyRunsException`
(`Too many asynchronous runs: 4 may run and 16 may wait at the same time…`), `POST /sync?async=true`
answers **429** with that message, `connectors_sync(async=true)` answers `{"error": …}`. A run that is
admitted beyond `maxConcurrent` waits for a thread with its row already `RUNNING`.

**One real run per mapping at a time (audit C-20, `MappingRunLocks`).** A mapping is taken, in the JVM, by the run that
starts it (`importFrom` for a synchronous start, `importFromAsync` when the start is accepted) and given back in a
`finally` when that run ends, however it ends; a module stop forgets every lock. A second real start of the same mapping
is refused before anything is read, looked up or written and before any `ExecutionRun` row exists, and says
`Mapping 12 (label) is already running (run R...): this start was refused and nothing was read or written for it...`.
Where that message goes: `importFromAsync` throws `MappingAlreadyRunningException`, which `POST /sync?async=true` answers
as **409**, `connectors_sync(async=true)` as `{"error": ...}`; a synchronous `importFrom` (REST `POST /sync`, MCP
`connectors_sync`, the scheduled task, the workflow step, the screen) returns an outcome whose status is `ERROR`
(REST 422) with that message as its one result - for the scheduled task and the workflow step that is a failed execution.
A dry run takes no lock (it writes nothing). Not covered: webhook and file documents (`importDocument`, several may
arrive at once by design), synchronous outbound runs (an asynchronous scope send, wave 13 §2.14.6a, does take the mapping), and a second EBX node (the lease columns of `ExecutionRun` are not used for this;
a check there is a scan on a non-key field at every start). A run that hangs holds its mapping until it ends or the module
restarts: `connectors_run_get` shows the run named in the message, `connectors_run_stop` stops it at the next batch boundary.

**Connector clients.** `Connector.close()` (§3.1.1) is called by `ConnectorRegistry` whenever it lets
go of an instance, and for every cached instance at module stop. Only `kafka` holds a client between
calls (its `KafkaProducer` and network thread); `close()` closes it with a 5 s flush, and a send made
later through the same instance opens a new producer.

Tests: `BackgroundWorkTest` (lazy creation; a cooperative task is not interrupted, a sleeping one is,
after three quarters of the wait; queued and delayed tasks never run; the stop is bounded and names
an executor that would not stop; nothing is created after a stop and everything again after a start;
`RunSlots` admission and refusal text; the registry closes an instance on change, switch-off, removal
and invalidation, once, and survives a `close()` that throws). Not verifiable offline: that EBX calls
the two callbacks, and the absence of `stopped already` errors after a real context reload.

---

## 3. Connector types and extension SPI

### 3.1 The connector SPI

#### 3.1.1 `Connector` interface (Connector)

One instance serves **one configured connector id**. Instances are stateless apart from their `ConnectorConfig`
(some keep a cached token, see individual types). The external representation is always Jackson `JsonNode`.

| Method | Required | Contract / default behaviour |
|---|---|---|
| `String getType()` | yes | Registry key, stored in `Connector/type`. Must be unique; a later registration with the same type replaces the earlier one (map put). |
| `String getLabel()` | yes | Human-readable type name. |
| `List<Operation> getOperations()` | yes | Operations supported. Dynamic types (`rest`, `graphql`, `soap`) compute them from the configuration and return an empty list before `configure`. |
| `ConnectorDescriptor getDescriptor()` | default | Default builds a descriptor with category `GENERIC_INTEGRATION`, one `ConnectorOperationDescriptor` per operation, direction `OUTBOUND` for `WRITE` operations and `INBOUND` otherwise; no properties, no auth methods, no capabilities. Most built-ins override it. |
| `void configure(ConnectorConfig)` | yes | Called once before any execution with the configuration read from EBX. A `RuntimeException` thrown here is wrapped by the registry into `ConnectorException("Connector '<id>': <msg>")`. |
| `void close()` | default | Releases what the instance keeps open between two calls (a client, a connection, a thread). Called by the registry when it lets go of the instance: configuration changed or removed, connector switched off, cache invalidated (configuration import, OpenAPI import), module stopping; also after a `configure` that threw. Default: nothing - right for a connector that opens and closes what it needs inside each call, which is every built-in except `kafka` (its producer). A run started earlier may still call the instance during or after `close()`: fail that call cleanly or reopen what is needed; never throw from `close()` (the registry logs a WARN and forgets the instance all the same). |
| `JsonNode execute(ConnectorContext, String operation, Map<String,String> inputs, JsonNode payload)` | yes | Executes one operation. `payload` = document to send for `WRITE`, else null. Returns the document received, or **null when the external system has nothing** (HTTP 404, empty search…). Throws `ConnectorException`. |
| `BatchResult executeBatch(ConnectorContext, BatchRequest)` | default | `ConnectorBatchExecutor.sequential`: validates the batch then runs items in order; a failed item does not stop its peers. Overridden natively by `dynamics365` (OData `$batch`). |
| `List<FieldInfo> describeFields(ctx, operation, inputs)` | default | Fields of the returned (READ/SEARCH) or expected (WRITE) document for the mapping designer; paths relative to the document root (`data.attributes.entity.legalName.name`). Default: empty list (designer then relies on a sample call). |
| `ConfigurationValidationResult validateConfiguration(ConnectorConfig)` | default | `ConnectorConfigurationValidator.validate(getDescriptor(), config)`; must not contact the external system. |
| `ConnectionTestResult testConnection(ConnectorContext)` | default | `ConnectionTestResult.unsupported("Connection testing is not implemented by this connector")`. |
| `ObjectDiscoveryResult discoverObjects(ctx, ObjectDiscoveryRequest)` | default | `ConnectorInspectionAdapters.discoverOperations`: lists non-WRITE operations as objects (id = operation name, label = description, type = kind), filtered by a case-insensitive substring of `name + " " + description`, capped at the request limit; a continuation token returns an empty page. |
| `ObjectDescription describeObject(ctx, objectId)` | default | Calls `describeFields(ctx, objectId, {})`; empty → `ObjectDescription.unsupported(id, "Object description is not implemented by this connector")`, else `of(id, operation description, fields)`. |
| `PreviewResult preview(ctx, PreviewRequest)` | default | `PreviewResult.unsupported("Preview is not implemented by this connector")`. Must never invoke a write operation. |
| `DraftMapping generateDraftMapping(ctx, DraftMappingRequest)` | default | `ConnectorInspectionAdapters.suggestMapping(describeObject(...), request)`: for each source field, the first target field whose **leaf name** (after last `/` or `.`, non-alphanumerics removed) matches case-insensitively; confidence 1.0 when full paths are equal ignoring case, else 0.85. Unsupported description → `DraftMapping.unsupported(message)` ("Object metadata is unavailable" when null). |

#### 3.1.2 Value classes of the SPI

| Class | Content and rules |
|---|---|
| `Operation` (Operation) | `name`, `kind` ∈ `READ`, `SEARCH`, `WRITE`, `description`, `requiredInputs`, `optionalInputs` (immutable lists). Factories `read`, `search`, `write(name, description, required...)` (no optional inputs). `toString` = `name (KIND)`. Kind drives direction: READ/SEARCH inbound, WRITE outbound. |
| `ConnectorConfig` (ConnectorConfig) | Runtime configuration of one instance (row of `Connector` + its `ConnectorProperty` rows). Fields: `id`, `type`, `label`, `baseUrl`, `authType` (enum `NONE`, `BASIC`, `API_KEY_HEADER`, `BEARER`, `OAUTH2`; null → `NONE`), `username`, `password`, `apiKeyHeader`, `apiKey`, `connectTimeoutMs` (default 10000; ≤0 → 10000), `readTimeoutMs` (default 30000; ≤0 → 30000), `trust` (default 50, clamped 0-100), `active` (default true), `logo`, `problem`, `properties` (ordered map, exposed read-only). `getProperty(key, default)` returns the **trimmed** value, or the default when missing/blank. `setProperty` ignores a null key. `setProblem` appends with `"; "`. `isPlaceholder(v)` = null, blank, `A_RENSEIGNER` or `TO_BE_SET`. `fingerprint()` concatenates every field and property (used by the registry cache). Secrets never logged by the class. |
| `ConnectorContext` (ConnectorContext) | `Session`, `Repository`, `LoggingCategory` (never null: falls back to `LoggingCategory.getKernel()`), `Adaptation dataset` (dataset of the record being synchronised, null for a raw call). |
| `ConnectorException` (ConnectorException) | Checked exception with `status` (HTTP status when there is one, else 0). Constructors `(msg)`, `(msg, cause)`, `(msg, status, cause)`. |
| `FieldInfo` (FieldInfo) | `path`, `label` (defaults to path), `type` (defaults to ""), `sample`; `of(path,label,type)`, `withSample(s)`. Path is relative to the mapping's response root (inbound) or payload root (outbound). |
| `ConnectorDescriptor` (ConnectorDescriptor) | Immutable, secret-free type metadata: `type`, `label`, `category` (all required, trimmed), `description`, `operations` (`ConnectorOperationDescriptor`), `configurationProperties`, `directions`, `capabilities`, `authenticationMethods`, `dependencies`, `apiVersions` (distinct, ordered), `pagination` (default `PaginationDescriptor.none()`), `defaults` (default `ConnectorDefaults.standard()`), `availability` (default `AVAILABLE`) + `availabilityMessage`, `deprecated` (also true when availability = `DEPRECATED`), `deprecationMessage`, `replacementType`. Builder ignores null values. |
| `ConnectorOperationDescriptor` | `name` (required), `kind` (required), `description`, `directions`, `capabilities`, `synchronizationStrategies` (default `{FULL}`). |
| `ConfigurationPropertyDefinition` | `name` (required), `label` (defaults to name), `description`, `type` (required), `required`, `secret` (forced true when type = `SECRET`), `advanced`, `defaultValue`, `enumerationValues` (distinct), `visibility`, `deprecatedValues` (value → message, `deprecatedValue(value, message)`; read with `getDeprecationMessage(value)`, null when not deprecated). Builder checks: a deprecated value must be one of the enumeration values; ENUMERATION needs ≥1 value; enumeration values only on ENUMERATION; enum default must be one of the values; BOOLEAN default must be `Boolean`; INTEGER default must be Byte/Short/Integer/Long; any other type's default must be a `String`. `isVisible(config)` true when no condition. |
| `ConfigurationPropertyType` | `STRING`, `SECRET`, `BOOLEAN`, `INTEGER`, `URL`, `ENUMERATION`, `CERTIFICATE`, `RESOURCE`, `FILE`, `JSON`. |
| `ConfigurationPropertyVisibility` | Condition on another property: `EQUALS` / `NOT_EQUALS` (exactly one value), `IN` (≥1 value), `PRESENT`, `ABSENT` (no value). A missing or blank value never matches EQUALS/NOT_EQUALS/IN. Factories `equalTo`, `notEqualTo`, `in`, `present`, `absent`. |
| `ConfigurationValidationIssue` / `ConfigurationValidationResult` | Issue = `propertyName` (null → rendered `configuration`) + `message`; values are never retained. Result `isValid()` = no issue; `getWarnings()` = non-blocking findings (deprecated values), never counted by `isValid()`. |
| `ConnectionTestResult` | `supported`, `successful`, `message` (null → ""), `elapsedMillis` (≥0); factories `success`, `failure`, `unsupported`. |
| `AuthenticationMethod` | Catalogue metadata only: `NONE`, `BASIC`, `API_KEY_HEADER`, `BEARER_TOKEN`, `OAUTH2_CLIENT_CREDENTIALS`, `OAUTH2_AUTHORIZATION_CODE`, `OAUTH2_REFRESH_TOKEN`, `CERTIFICATE`, `SSH_KEY`, `SERVICE_ACCOUNT`, `CUSTOM`. The runtime auth is `ConnectorConfig.AuthType` (5 values). |
| `ConnectorCapability` | `TEST_CONNECTION`, `DISCOVER_OBJECTS`, `DESCRIBE_OBJECT`, `PREVIEW_RECORDS`, `INCREMENTAL_READ`, `BULK_READ`, `BULK_WRITE`, `STREAMING`, `SCHEMA_DISCOVERY`, `TRANSACTIONS` (advertised metadata). |
| `ConnectorDirection` | `INBOUND`, `OUTBOUND`, `BIDIRECTIONAL`. |
| `ConnectorAvailability` | `AVAILABLE`, `EXPERIMENTAL`, `OPTIONAL_DEPENDENCY_REQUIRED`, `UNAVAILABLE`, `DEPRECATED`. |
| `ConnectorDefaults` | `connectTimeoutMs`, `readTimeoutMs` (>0), `maximumRetries` (≥0), `trustLevel` (0-100), else `IllegalArgumentException("Invalid connector defaults")`. `standard()` = 10000 / 30000 / 0 / 50. |
| `ConnectorDependency` | `name` (required), `minimumVersion`, `required`, `description` (trimmed to null). |
| `PaginationDescriptor` | `strategy` ∈ `NONE`, `OFFSET`, `PAGE_NUMBER`, `CURSOR`, `LINK`, `TOKEN` (null → NONE), `defaultPageSize`, `maximumPageSize` (≥0; default ≤ maximum when maximum > 0), `cursorProperty`. `none()` = NONE/0/0. |
| `SynchronizationStrategy` | `FULL`, `TIMESTAMP`, `MONOTONIC_ID`, `VENDOR_CURSOR`, `ODATA_DELTA`, `EBX_DELTA`, `SNAPSHOT`. Metadata only: advertising a strategy implements nothing. |
| `BatchRequest` | Constants `HARD_MAX_ITEMS = 100`, `HARD_MAX_WRITE_ITEMS = 50`, `HARD_MAX_BYTES = 2 MiB`. Needs ≥1 item, ≤100 items, byte limit 1..2 MiB, no null item, unique ids. `Item`: `id` matching `[A-Za-z0-9._-]{1,80}`, non-blank `operation` (trimmed), `inputs`, `payload`. Error messages: "A batch must contain at least one item", "A batch cannot exceed 100 items", "Batch byte limit must be between 1 and 2097152", "Batch items cannot be null", "Duplicate batch item id '<id>'", "Batch item id must contain only letters, numbers, '.', '_' or '-'", "Batch item operation is required". |
| `BatchResult` | Ordered `ItemResult`s (`id`, `success`, `status`, `document`, `error` — null error → "Connector operation failed"), `nativeBatch` flag, `isSuccess()` = all items succeeded. |
| `ConnectorBatchExecutor` | `validate`: unknown operation → "Unknown batch operation '<op>'"; missing required input → "Batch item '<id>' requires input '<name>'"; WRITE item without payload → "Batch write item '<id>' requires a payload"; cumulative UTF-8 bytes of operation + input keys/values + payload JSON above the request limit → "Batch request exceeds byte limit N"; more than 50 writes → "Batch cannot exceed 50 write items". `sequential`: success status 200; a `ConnectorException` gives its status; a `RuntimeException` gives status 0. |
| `ObjectDiscoveryRequest` / `ObjectDiscoveryResult` / `DiscoveredObject` | Request: `query` (trimmed), `limit` (≤0 → `DEFAULT_LIMIT` 50, capped at `MAX_LIMIT` 500), `continuationToken`. Result: `supported`, `objects`, `continuationToken`, `message`, `truncated` ("Page truncated locally; omitted objects require a smaller page request."). DiscoveredObject: `id` (required), `label` (defaults to id), `type`, `readable`. |
| `ObjectDescription` | `supported`, `objectId`, `label`, `fields`, `message`. |
| `PreviewRequest` / `PreviewResult` | Request: `objectId`, `operation`, `inputs`, `limit` (≤0 → `DEFAULT_LIMIT` 20, capped at `MAX_LIMIT` 100). Result: `supported`, `records`, `truncated`, `message`; `of()` refuses more than 100 records. |
| `DraftMapping`, `DraftMappingField`, `DraftMappingRequest` | Non-persistent proposal; field confidence clamped 0..1; request = `objectId`, `operation`, `targetFields`. |
| `SecretResolver` | Extension point for secret references `<scheme>:<reference>`: `getScheme()` (lower case, no colon), `resolve(reference)` (throws with an administrator-readable message). Declared in `META-INF/services/com.on.ebx.connect.spi.SecretResolver`. Javadoc lists built-in schemes `env`, `file`, `vault` (HashiCorp KV), `azurekv`, cache controlled by system property `ebx.connectors.secrets.cacheSeconds` (implementation lives in `core`). |
| `DocumentCodecProvider` | Optional binary file codec found by `ServiceLoader` (`META-INF/services/com.on.ebx.connect.spi.DocumentCodecProvider`): `getFormats()` (lower-case names/aliases), `parse(bytes, format, maxRows, maxBytes)` → `ObjectNode`, `serialise(rows, format, maxBytes)`, `getContentType(format)`. Intended for Excel/Parquet; none is packaged (see §5). |

#### 3.1.3 Configuration validation (ConnectorConfigurationValidator)

Run by `Connector.validateConfiguration` default and **enforced by the registry immediately before `configure`**
(ConnectorRegistry.validateConfiguration): any issue makes the connector unusable with
`ConnectorException("Connector '<id>' configuration is invalid: <prop>: <msg>; ...")` (redacted).

Checks, in order:

1. `baseUrl` blank → `baseUrl: A base URL is required`; not absolute → `baseUrl: Must be an absolute URL`. A value
   matching `host:port[, host:port...]` (messaging and database endpoints) is accepted as a URL; otherwise
   `new URI(v)` must be absolute with a scheme (so `jdbc:postgresql://…` passes).
2. `config.getProblem() != null` (vault reference not resolved, refused clear secret) → `configuration: A configured secret or resource cannot be resolved`.
3. Authentication:
   - `BASIC`: issue `authentication: Basic credentials are incomplete` when `apiKey` is a placeholder **and** (username blank or password placeholder). (The `apiKey` escape exists for Companies House, which uses the key as Basic user.)
   - `API_KEY_HEADER`, `BEARER`, `OAUTH2`: `authentication: An authentication secret is required` when `apiKey` is a placeholder.
   - `NONE`: nothing.
4. For each descriptor property **visible** for the current values (properties + `baseUrl` + `authType` name):
   - A property name containing `<name>` (e.g. `op.<name>.path`) is a **pattern**: it applies to every operation the configuration declares, an operation being any key `prefix + X + suffix` where the suffix is one of the suffixes of the descriptor's patterns sharing that prefix. No declared operation → the pattern checks nothing.
   - Blank value: `<key>: A value is required` only when `required` and no `defaultValue`.
   - Secret property holding a placeholder → `<key>: A secret value is required`.
   - Type checks on the trimmed value: BOOLEAN must be `true`/`false` (case-insensitive) → "Must be true or false"; INTEGER must parse as `long` (message = the `NumberFormatException` text); URL absolute → "Must be an absolute URL"; ENUMERATION → "Must be one of the declared values"; JSON must parse (message = Jackson's). Other types unchecked.
   - An enumeration value the descriptor declares **deprecated** (`deprecatedValue(value, message)`) adds a **warning** `<key>: <message>` to `getWarnings()`: it never makes the configuration invalid, the connector stays `AVAILABLE` and runs. Warnings are shown by the connector diagnostics (Validate: headline "Configuration is valid, with warnings"; Test connection: one "Warning — …" line each) and by `connectors_describe` (`warnings[]`). Only Salesforce `oauth.flow = password` is deprecated today.

Many types **override** `validateConfiguration` completely (without calling the default) or extend it; their extra
rules are listed in each catalogue entry. Where a type replaces the default, the generic `baseUrl`/auth checks above
do **not** run for it.

#### 3.1.4 Registration, discovery and life cycle (ConnectorRegistry, in `core`, summarised for context)

- Singleton `ConnectorRegistry.get()`. The constructor first loads `ServiceLoader.load(Connector.class, registry classloader)` and registers each class found; **any `Throwable` there is swallowed** ("a broken service file must not prevent the built-ins from working").
- Then 56 built-ins are registered with `registerType(Class)` (listed in §2), and two **quietly** by class name with `registerTypeQuietly`: `KafkaConnector` and `com.on.ebx.connect.connectors.SftpConnector`. A `Throwable` (library absent) there means the type is simply not offered.
- Because service-loaded types are registered first, a built-in with the same `getType()` **overrides** an extension type of that name.
- `registerType` instantiates the class once (`newInstance`) to read `getType()`; a class without a public no-arg constructor → `IllegalArgumentException("Connector class X needs a public no-arg constructor: …")`.
- `describeType(type)` returns a throw-away instance; unknown → `ConnectorException("Unknown connector type '<t>'; known: [...]")`.
- `connector(id, repository)`: re-reads the configuration on every access; missing → "Connector '<id>' is not declared in the Connectors dataset" (cache entry dropped). Cached instance returned while `fingerprint()` is unchanged. Otherwise: inactive → "Connector '<id>' is inactive"; `problem` → "Connector '<id>': <problem>"; `Egress.checkBaseUrl` refusal → "Connector '<id>': <msg>"; `Redaction.remember(config)`; new instance; descriptor validation (§1.3); `configure`; cached. `invalidate()` clears all.
- The registry does not itself use `testConnection`, `preview`, `discoverObjects` etc.; those are called by the EBX integration layer.

#### 3.1.5 Base classes available to connector authors

**`AbstractRestConnector`** (`core`, shipped in the SPI jar; used by most HTTP types and by the sample):

- `configure`: stores the config, `Redaction.remember`, creates an `HttpJsonClient(connectTimeoutMs, readTimeoutMs, "EBX-Connectors/1.0 (<id>)")`, applies `Tls.contextFor(config)` (mutual TLS / private CA, `IllegalStateException` when stores are wrong).
- `headers()` from `authType`: `BASIC` → `Authorization: Basic base64(user:password)`; `API_KEY_HEADER` → `<apiKeyHeader>: <apiKey>` (only when both set); `BEARER` → `Authorization: Bearer <apiKey>`; `OAUTH2` → header named by property `oauth.tokenHeader` (default `Authorization`) = `OAuth2.bearer(config, client)`; plus `Accept: <accept>` when property `accept` is set.
- `requireSecret()`: API_KEY_HEADER/BEARER with placeholder `apiKey` → "Connector <id>: API key not configured (Connectors > Connector > <id>)"; BASIC with placeholder password → "Connector <id>: password not configured"; OAUTH2 → `OAuth2.validate(config,false)` problem.
- `send(method, path, query, body[, extraHeaders])`: URL = `HttpJsonClient.buildUrl(baseUrl(), path, query)`. `PATCH` on a JVM without `java.net.http` is sent as `POST` with query `_HttpMethod=PATCH`. With OAUTH2 a **401 drops the cached token and retries once**. `IOException` → redacted `ConnectorException("<id>: <METHOD> <url> failed: <msg>")`.
- `sendRaw` (non-JSON body), `sendBytes`, `sendBytesBounded` (response stopped at a byte ceiling), `getAbsolute(url, headers)` (follows a resolved URL with the same auth/renewal).
- `bodyOrNull(response, what)`: 2xx → body; 404 → null; other → `ConnectorException("<id>: <what> -> HTTP <status> <first 300 chars of body>...", status)`.
- `testConnection`: `requireSecret`, then `HEAD <baseUrl><inspection.path>` (property `inspection.path`, default ""); 200-399 → success "HTTP endpoint responded with status N", else failure.
- `preview`: requires an explicit operation (or objectId) → "Select a READ or SEARCH operation to preview"; unknown → "Unknown operation '<x>'"; WRITE → "Operation '<x>' writes data and cannot be used for preview"; missing required inputs → "Operation '<x>' requires input(s): [..]"; then `requireSecret`, `execute`, array responses truncated to the limit, object → one record.
- `refreshSchema(url, headers[, store, stateKey, minIntervalMs])`: conditional schema/OpenAPI refresh per URL, signals drift to `ConnectorOperationsRuntime.schemaDrift` by reflection when present; `refreshPersistedSchema` is the scheduler entry point.
- Helpers `require(inputs, name)` → "Missing input '<name>'" (trimmed value), `segment(v)` = URL-encode.

**`AbstractCrudRestConnector`** (package-private, `connectors`): default operations `get` (object,id), `query` (object; inputs object, filter, max), `create` (object), `update` (object,id), `upsert` (object; id or lookup field + value). `list(array, total)` → `{results:[…], count, totalCount?}` or **null when empty**. `safeName(n)` strips everything but `[A-Za-z0-9_]`.

**`AbstractFileConnector`** (package-private): see §2 "File connectors".

**`JdbcConnector`** (public, non-final) is the base for the dedicated database types; **`JmsConnector`** (public, non-final) the base for `activemq`; **`MicrosoftSqlConnectorBase`** (package-private) the base for `sql-server` and `azure-sql`.

#### 3.1.6 Writing an extension (ebx-connectors-extension-sample)

- `NominatimConnector` (`com.example.ebx.connect`) extends `AbstractRestConnector`; type `nominatim`, label "OpenStreetMap Nominatim (sample extension)".
- Operations: `geocode` (READ, required `q`, optional `countrycodes` input or property) and `reverse` (READ, required `lat`, `lon`).
- `headers()` override adds `User-Agent` = property `user.agent` (default `ebx-connectors-sample/1.0`) and `Accept-Language` = property `accept.language` (default `en`).
- `geocode`: query `format=jsonv2`, `addressdetails=1`, `q` (whitespace collapsed; fewer than 3 characters → null), `limit=1`, `countrycodes` lower-cased; `GET /search`; returns the first array element flattened, null when empty.
- `reverse`: `GET /reverse` with `lat`, `lon`; a reply carrying `error` → null.
- Flattening: `place_id, osm_type, osm_id, lat, lon, display_name, type, category, importance, name` (lat/lon/importance as doubles), every `address.*` key at top level, and `city` = first of `city`, `town`, `village`, `municipality`.
- `describeFields`: `display_name, lat, lon, type, importance, house_number, road, postcode, city, state, country, country_code`.
- Registration file `src/main/resources/META-INF/services/com.on.ebx.connect.spi.Connector` containing `com.example.ebx.connect.NominatimConnector`.
- `build.sh [spi.jar]`: default SPI jar `lib/ebx-connectors-spi.jar`; EBX libs from `$EBX_LIB_DIR` or `<workspace>/ebx-ps-fasttrack-server/lib` (needs `ebx.jar` or `ebx-lib.jar`,; `javac --release 8`; copies resources; produces `target/ebx-connectors-extension-sample.jar`; exits 2 when the SPI jar, EBX libs or sources are missing.
- Deployment (README): copy the jar into `ebx-connectors/WEB-INF/lib/`, restart Tomcat; the type appears in `Connector/type`. The sample needs a public no-arg constructor (implicit) and Java 8 syntax.
- Recipe for a new type: copy the project, rename class and `getType()`, declare operations with `Operation.read/search/write`, implement `execute` (return `JsonNode` or null), optionally `describeFields`; optionally override `getDescriptor` for typed properties and categories (otherwise category `GENERIC_INTEGRATION`, no validated properties beyond `baseUrl`/auth).

#### 3.1.7 Service files shipped by the module (`src/main/resources/META-INF/services`)

- `com.on.ebx.connect.connectors.AzureServiceBusTransport$Factory` → `com.on.ebx.connect.connectors.AzureServiceBusSdkTransportFactory` (the only service file; the module itself ships **no** `com.on.ebx.connect.spi.Connector` file — built-ins are registered in code).

---


### 3.2 Connector type catalogue

Conventions used below: **R/S/W** = READ / SEARCH / WRITE. "Inputs" lists required inputs first, then the optional
inputs the code reads. "Default validation" = §1.3. "Common auth" = `AbstractRestConnector.headers()` (§1.5).
Unless said otherwise, a `ConnectorException` "…: unknown operation '<op>'" is thrown for an unknown operation, and
404 yields null.

Registered types (ConnectorRegistry order): `gleif`, `sirene`, `google-geocoding`, `rest`, `salesforce`, `snowflake`,
`databricks`, `powerbi`, `collibra`, `sap-odata`, `jde-ais`, `dnb`, `azure-blob`, `ban`, `bigquery`, `companies-house`,
`creditsafe`, `dynamics365`, `dynamics-business-central`, `gcs`, `hubspot`, `jdbc`, `postgresql`, `rabbitmq`, `h2`,
`sql-server`, `azure-sql`, `azure-service-bus`, `oracle-database`, `loqate`, `melissa`, `mysql`, `handelsregister`,
`opencorporates`, `opensanctions`, `oracle-fusion`, `netsuite`, `sap-successfactors`, `sap-ariba`, `orbis`, `s3`,
`servicenow`, `sharepoint`, `vies`, `webhook`, `workday`, `llm`, `soap`, `graphql`, `ebx`, `openlineage`,
`chat-webhook`, `slack`, `jms`, `activemq`, `inpi`, `openfda`, then quietly `kafka`, `sftp`. Total 59 (+ `nominatim` when the
sample jar is deployed). There is **no** `wikidata` type: Wikidata is a seeded `rest` connector.

#### 3.2.1 Corporate registries

#### 3.2.2 `gleif` — GLEIF (LEI) (GleifConnector)

- Purpose: Legal Entity Identifier records and parent relationships. API `https://api.gleif.org/api/v1` (javadoc; no key, 60 calls/min — not enforced in code). Category `CORPORATE_REGISTRIES`. Auth advertised `NONE`; common auth headers apply. Extra header `Accept: application/vnd.api+json`.
- Configuration properties: none declared.
- Operations (all return a document shaped like `GET /lei-records/{lei}`: record under `data`, so one mapping with response root `data.attributes` serves all):
  - `lei` (R) — input `lei` (upper-cased) → `GET /lei-records/{lei}`.
  - `searchByRegistration` (S) — `registrationId`, `registrationAuthority` → `GET /lei-records?filter[entity.registeredAs]=…&filter[entity.registeredAt]=…&page[size]=1`; the first element is re-wrapped as `{data: first, meta}`; none → null.
  - `searchByName` (S) — `name` → `GET /fuzzycompletions?field=entity.legalName&q=…`, takes `data[0].relationships.lei-records.data.id` then runs `lei`; no completion → null.
  - `directParent` / `ultimateParent` (R) — `lei` → `GET /lei-records/{lei}/direct-parent` / `/ultimate-parent`.
- `testConnection` override: `HEAD /lei-records` (the API root is not a resource); message "GLEIF lei-records endpoint responded with status N".
- `describeFields`: 28 fixed paths (`data.id`, `data.attributes.entity.legalName.name`, legalForm.id, category, jurisdiction, status, creationDate, registeredAt.id, registeredAs, legal and headquarters address lines/city/region/country/postalCode, `registration.status/initialRegistrationDate/lastUpdateDate/nextRenewalDate/managingLou`, `bic`, `mic`, `ocid`, `spglobal`).

#### 3.2.3 `sirene` — INSEE Sirene (SireneConnector)

- API Sirene 3.11 (`https://api.insee.fr/api-sirene/3.11`, javadoc); key in header `X-INSEE-Api-Key-Integration` → configure `authType=API_KEY_HEADER`, `apiKeyHeader`, `apiKey`. Category `CORPORATE_REGISTRIES`, auth `API_KEY_HEADER`, api version `3.11`.
- Every call starts with `requireSecret()`.
- Operations:
  - `siren` (R) — `siren`: non-digits removed, must be 9 digits else "Sirene: '<v>' is not a 9-digit siren" → `GET /siren/{siren}`.
  - `siret` (R) — `siret`, 14 digits → `GET /siret/{siret}`.
  - `headOffice` (R) — `siren` → reads `/siren/{siren}`, takes `uniteLegale.periodesUniteLegale[0].nicSiegeUniteLegale` (absent → null), then `GET /siret/{siren+nic}`.
- `describeFields`: `siren` → legal-unit fields (`uniteLegale.siren`, `dateCreationUniteLegale`, `categorieEntreprise`, `trancheEffectifsUniteLegale`, `periodesUniteLegale[0].denominationUniteLegale/categorieJuridiqueUniteLegale/activitePrincipaleUniteLegale/etatAdministratifUniteLegale/nicSiegeUniteLegale`); other operations → establishment fields (`etablissement.siret`, `siren`, `etablissementSiege`, `adresseEtablissement.numeroVoie/typeVoie/libelleVoie/codePostal/libelleCommune/codeCommuneEtablissement`, `periodesEtablissement[0].activitePrincipaleEtablissement/etatAdministratifEtablissement`).

#### 3.2.4 `companies-house` — Companies House (UK) (CompaniesHouseConnector)

- API `https://api.company-information.service.gov.uk` (javadoc). Category `CORPORATE_REGISTRIES`, auth `BASIC`, capability `BULK_READ`.
- `configure` **forces** `authType=BASIC`, `username = apiKey`, `password = ""` (the API key is the Basic user).
- Every call: placeholder `apiKey` → "Companies House: set the API key in apiKey".
- Company number normalisation: spaces removed, upper-cased, left-padded with zeros to 8 characters when shorter.
- Operations: `get` (R, `number`) → `GET /company/{number}`; `search` (S, `q`, optional `max` → `items_per_page`, default 20) → `GET /search/companies`; `officers` (S, `number`) → `/company/{n}/officers`; `pscs` (S) → `/persons-with-significant-control`; `filings` (S) → `/filing-history`. List results → `{results: items, count, totalCount: total_results}`; empty → null.
- `describeFields` (prefix `results[0].` except for `get`): company_name, company_number, company_status, type, date_of_creation, date_of_cessation, jurisdiction, sic_codes, registered_office_address.address_line_1/locality/postal_code/country, accounts.next_due.

#### 3.2.5 `handelsregister` — Handelsregister (OffeneRegister mirror) (OffeneRegisterConnector)

- German register through the OffeneRegister Datasette JSON API (`https://db.offeneregister.de`, javadoc); data may lag the official register. Category `CORPORATE_REGISTRIES`, auth `NONE`.
- All calls `GET /openregister/company.json` with `_shape=objects`.
- `search` (S, `q`) → `name__contains=q`, `_size` = input `max` or 30. `get` (R, `number` e.g. `HRB 12345`, optional `court`) → `native_company_number__contains` (whitespace collapsed), `registrar__contains=court`, `_size=1`, returns `results[0]`.
- Result shape `{results: rows, count}`; empty → null.
- `describeFields`: name, company_number, native_company_number, registrar, federal_state, registered_address, current_status, retrieved_at (prefix `results[0].` for search).

#### 3.2.6 `opencorporates` — OpenCorporates (OpenCorporatesConnector)

- API `https://api.opencorporates.com/v0.4` (javadoc). Category `CORPORATE_REGISTRIES`, auth `NONE` + `CUSTOM`. `apiKey` optional: sent as query `api_token` when not a placeholder.
- `get` (R, `jurisdiction` lower-cased, `number` spaces removed) → `GET /companies/{jurisdiction}/{number}` → `results.company`.
- `search` (S, `q`, optional `jurisdiction` → `jurisdiction_code`, `max` → `per_page` default 30) → `GET /companies/search` → `{results:[company…], count}`; empty → null.
- `describeFields`: name, company_number, jurisdiction_code, incorporation_date, dissolution_date, company_type, current_status, registered_address_in_full, registered_address.street_address/locality/postal_code/country, opencorporates_url, previous_names, officers, industry_codes.

#### 3.2.7 `inpi` — INPI Registre National des Entreprises (InpiConnector)

- API `https://registre-national-entreprises.inpi.fr/api` (javadoc). Category `CORPORATE_REGISTRIES`, auth `BASIC`. Property `token.ttl` (INTEGER, advanced; default 3000 s in code).
- Token: `username`/`password` required (placeholder → "<id>: set the INPI account in username / password (authentication BASIC)"); `POST /sso/login {username,password}` with header `Authorization` emptied; reply `token` (absent → "<id>: login gave no token"); cached `token.ttl` seconds per instance (synchronized).
- `company` (R, `siren`: non-digits removed; not 9 digits → **null**) → `GET /companies/{siren}` with `Authorization: Bearer`.
- `search` (S, `name`, optional `postalCode`) → `GET /companies?companyName=…&codePostal=…&pageSize=1`; returns the first element (array reply or `results[0]`), else null.
- `describeFields`: `formality.siren`, `formality.content.personneMorale.identite.entreprise.denomination/formeJuridique/dateImmat/dateDebutActiv`, `identite.description.montantCapital/deviseCapital/objet`, `adresseEntreprise.adresse.numVoie/typeVoie/voie/codePostal/commune/pays`, beneficial owner `beneficiairesEffectifs[0]…nom/prenoms[0]/modalite.pourcentageParts`, `formality.content.natureCessationEntreprise`, `updatedAt`.

#### 3.2.8 `orbis` — Bureau van Dijk Orbis (OrbisConnector)

- API `https://api.bvdinfo.com/v1/orbis` (javadoc). Category `CORPORATE_REGISTRIES`, auth `API_KEY_HEADER`. `configure` **forces** `authType=API_KEY_HEADER`, `apiKeyHeader=ApiToken`. Every call `requireSecret()`.
- Property `select` (STRING, advanced): comma list of Orbis fields; default `DEFAULT_SELECT` = `NAME,BVD_ID_NUMBER,NATIONAL_ID,NATIONAL_ID_LABEL,LEI,STATUS,INCORPORATION_DATE,NACE2_MAIN_CODE,NACE2_MAIN_DESCRIPTION,ADDRESS_LINE1,POSTCODE,CITY,COUNTRY_ISO_CODE,WEBSITE,PHONE_NUMBER,EMPL,OPRE,OPRE_CURRENCY,GUO_NAME,GUO_BVD_ID_NUMBER,ISH_NAME,ISH_BVD_ID_NUMBER,LATITUDE,LONGITUDE`.
- `get` (R, `bvdId`) → `POST /Companies/data {WHERE:[{BvDID}], SELECT}` → `Data[0]`.
- `search` (S, `name`, optional `country` upper-cased, `max` → `PAGE.Size` default 20) → `{results: Data, count}`; empty → null. (`max` parsed with `Integer.parseInt`: a non-number throws `NumberFormatException`.)
- `match` (R, no required input; criteria from `name`, `city`, `country` (upper), `nationalId`, `postalCode`→`PostCode`, `address`) → none given → "Orbis match: give at least a name or a national id"; `POST /Companies/match {MATCH:{Criteria}, SELECT}` → first of `Data` or `MatchResults`.
- `describeFields`: one string field per `select` entry (prefix `results[0].` for search).

#### 3.2.9 Reference data (address, geocoding)

#### 3.2.10 `ban` — Base Adresse Nationale (France) (BanConnector)

- API `https://api-adresse.data.gouv.fr` (javadoc). Category `REFERENCE_DATA`, auth `NONE`. Property `score.min` (declared STRING, advanced; default 0.5 in code; parsed with `Double.parseDouble`).
- `geocode` (R, `address`, whitespace collapsed; <3 chars → null; optional `postcode` used only when 5 digits) → `GET /search/?limit=1&q=…`.
- `reverse` (R, `lat`, `lon`) → `GET /reverse/?limit=1&lat&lon`.
- Result = `features[0].properties` copied, plus `lon`/`lat` from `geometry.coordinates`; no feature → null; for `geocode` a score below `score.min` → null.
- `describeFields`: label, housenumber, street, postcode, city, citycode, context, type, score, lat, lon.

#### 3.2.11 `google-geocoding` — Google Geocoding (GoogleGeocodingConnector)

- API base `https://maps.googleapis.com/maps/api` (javadoc). Category `REFERENCE_DATA`, auth `CUSTOM`: the key goes in `apiKey` with `authType NONE` and is sent as query `key`.
- Placeholder key → "Google Geocoding: API key not configured".
- `geocode` (R, `address`) / `reverse` (R, `latlng`), optional `region` (lower-cased) → `GET /geocode/json`.
- `status` other than `OK`: `ZERO_RESULTS` → null; anything else → "Google Geocoding: <status> <error_message>". For `geocode`, the result is kept only when `results[0].address_components` contains a `route`, `locality`, `postal_code` or `street_address` type (never the country centroid) — else null. Returns the whole Google body.
- `describeFields`: `results[0].formatted_address`, `geometry.location.lat/lng`, `geometry.location_type`, `place_id`, `address_components`.

#### 3.2.12 `loqate` — Loqate address verification (LoqateConnector)

- API `https://api.addressy.com` (javadoc). Category `REFERENCE_DATA`, auth `CUSTOM` (key sent as `Key`). Placeholder key → "Loqate: set the API key in apiKey".
- Properties: `geocode` (BOOLEAN; default true in code), `verification.min` (INTEGER in the descriptor, advanced; default **`V`** in code — see Discrepancies).
- `verify` (R, `address`; optional `locality`, `postalCode`, `country`, `administrativeArea`, `organization`) → `POST /Cleansing/International/Batch/v1.00/json4.ws {Key, Geocode, Addresses:[…]}` → first of `[0].Matches`; adds `verificationStatus` = first letter of `AVC` (V verified, P partial, A ambiguous, U unverified, R reverted, C conflict); when `verification.min` = `V` and AVC does not start with V → null.
- `find` (S, `text`; optional `country` → `Countries` upper, `max` → `Limit` default 10) → `GET /Capture/Interactive/Find/v1.10/json3.ws` → `{results: Items, count}`; empty or `Items[0].Error` → null.
- `retrieve` (R, `id`) → `GET /Capture/Interactive/Retrieve/v1.20/json3.ws` → `Items[0]`.
- `describeFields`: find → results[0].Id/Text/Description/Type; else AVC, verificationStatus, Address, Address1, Organization, PremiseNumber, Thoroughfare, Locality, AdministrativeArea, PostalCode, ISO3166-2, Latitude, Longitude, GeoAccuracy.

#### 3.2.13 `melissa` — Melissa Data (MelissaConnector)

- Category `REFERENCE_DATA`, auth `CUSTOM`: `apiKey` = licence key sent as query `id`; placeholder → "Melissa: set the license key in apiKey". Calls use their own `HttpJsonClient` ("EBX-Connectors/1.0 (melissa)") to absolute URLs with the common headers; `baseUrl` is **not used**.
- Properties: `address.url` (URL, advanced, default `https://address.melissadata.net/v3/WEB/GlobalAddress/doGlobalAddress`), `email.url` (default `https://globalemail.melissadata.net/v4/WEB/GlobalEmail/doGlobalEmail`), `phone.url` (default `https://globalphone.melissadata.net/v4/WEB/GlobalPhone/doGlobalPhone`), `country` (default country, `FR` in code), `verified.only` (BOOLEAN; default **true** in code).
- All: query `format=json`.
- `address` (R, `address` → `a1`; optional `locality`→`loc`, `postalCode`→`postal`, `administrativeArea`→`admarea`, `country`→`ctry` else property) + `opt=OutputGeo:ON`. `email` (R, `email`). `phone` (R, `phone`, optional `country`→`ctry`).
- Result = `Records[0]` copied + `verified` (address: `Results` contains `AV2`; email: `ES01`; phone: `PS01`); address without AV2 and `verified.only` true → null; no record → null.
- `describeFields`: email → EmailAddress, DomainName, verified, Results; phone → PhoneNumber, PhoneInternationalPrefix, Carrier, verified, Results; address → verified, Results, FormattedAddress, AddressLine1, PremisesNumber, Thoroughfare, Locality, AdministrativeArea, PostalCode, CountryISO3166_1_Alpha2, Latitude, Longitude.

#### 3.2.14 Risk and compliance

#### 3.2.15 `vies` — VIES (EU VAT validation) (ViesConnector)

- API `https://ec.europa.eu/taxation_customs/vies/rest-api` (javadoc), JSON REST (not SOAP). Category `RISK_COMPLIANCE`, auth `NONE`.
- `check` (R, no required input): `countryCode` + `vatNumber`, or `vat` (spaces, dots, hyphens removed, upper-cased, first 2 characters = country). Neither → "VIES: give a VAT number (input vat) or countryCode + vatNumber".
- `POST /check-vat-number {countryCode, vatNumber}` (upper-cased).
- `errorWrappers[0].error` present (MS_MAX_CONCURRENT_REQ, MS_UNAVAILABLE, SERVICE_UNAVAILABLE, INVALID_INPUT…) → `ConnectorException("VIES: <error> - <message>")` (a transient error is not "no data"). No `valid` → null.
- Document `{valid, countryCode, vatNumber, vat, name, address, requestDate, status}`; `name`/`address` trimmed, whitespace collapsed, `---` → ""; `status` = `VALID`/`INVALID`, replaced by `userError` when that is neither.
- `describeFields`: valid, status, vat, name, address, requestDate.

#### 3.2.16 `opensanctions` — OpenSanctions (OpenSanctionsConnector)

- API `https://api.opensanctions.org` (javadoc). Category `RISK_COMPLIANCE`, auth `NONE` + `CUSTOM`: header `Authorization: ApiKey <apiKey>` when the key is not a placeholder (common auth headers are **replaced**, `Accept: application/json`).
- Properties: `dataset` (STRING; default `default` in code), `threshold` (STRING, advanced; default `0.7` in code).
- `screen` (R, `name`; optional `schema` default `Company`, `registrationNumber`, `country` lower-cased, `lei`→`leiCode`, `birthDate`, `max`→`limit` default 5) → `POST /match/{dataset}?threshold&limit {queries:{q1:{schema, properties}}}`. **Always returns a document**: `{matches:[…], hit, score, matchCount, screenedAt (local date-time, no nanos), dataset, best?, bestSummary?{id, caption, schema, datasets, topics}}`; `hit` = best result's `match` flag; `datasets`/`topics` joined with ", ".
- `search` (S, `q`; optional `schema`, `countries` lower, `max`→`limit` default 10) → `GET /search/{dataset}` → `{results, count}`; empty → null.
- `entity` (R, `id`) → `GET /entities/{id}`.
- `describeFields`: screen → hit, score, matchCount, screenedAt, dataset, bestSummary.*, matches; other → results[0].id/caption/schema/datasets/properties.topics/properties.country.

#### 3.2.17 `creditsafe` — Creditsafe Connect (CreditsafeConnector)

- API `https://connect.creditsafe.com/v1` (javadoc). Category `RISK_COMPLIANCE`, auth `BASIC` (exchanged for a token). Properties `country` (default `FR` in code), `language` (default `en`).
- `headers()` replaced: `Accept: application/json` + `Authorization: <token>` (raw token, no "Bearer").
- Authentication: username null or password placeholder → "Creditsafe: set the user name and password (authentication BASIC)"; token reused for **50 minutes**; `POST /authenticate {username,password}` → `token`, failure → "Creditsafe authentication failed (HTTP n): <message>".
- `search` (S) / `find` (R): query `countries` (input `country` upper or property), any of `name`, `regNo`, `vatNo`, `city`, `postCode`, `street`, `id`; none → "Creditsafe: give a name, regNo or vatNo"; `pageSize` = `max` or 20 → `GET /companies` → `companies`; empty → null. `search` → `{results, count}`; `find` → report of the first `id`.
- `report` (R, `connectId`) → `GET /companies/{id}?language=` → `report` (or the whole reply).
- `describeFields`: search → results[0].id/name/regNo/vatNo/status/address.simpleValue/city/postCode; report → companyId, companySummary.businessName/companyRegistrationNumber/companyStatus.status/creditRating.commonValue/creditLimit.value, companyIdentification.basicInformation.*, contactInformation.mainAddress.*, websites[0], employees, groupStructure.ultimateParent.name.

#### 3.2.18 `openfda` — openFDA (OpenFdaConnector)

- API `https://api.fda.gov` (javadoc). Category `RISK_COMPLIANCE`, auth `NONE` + `CUSTOM` (optional key as query `api_key` when not a placeholder).
- `udi` (R, `di`) → `GET /device/udi.json?search=identifiers.id:"<di>"`; `deviceSearch` (S, `brand`, optional `company`) → `search=brand_name:"…"+AND+company_name:"…"`; `drug` (R, `ndc`) → `GET /drug/ndc.json?search=product_ndc:"…"`. Double quotes are stripped from values. `limit=1`; returns `results[0]` or null.
- Unknown operation → "<id>: unknown operation '<op>'".
- `describeFields`: drug → product_ndc, brand_name, generic_name, labeler_name, dosage_form, route[0], product_type, marketing_category, active_ingredients[0].name/strength, packaging[0].package_ndc/description; devices → identifiers[0].id, brand_name, company_name, device_description, version_or_model_number, catalog_number, record_status, publish_date, is_rx, is_otc, is_single_use, sterilization.is_sterile, gmdn_terms[0].name/definition, product_codes[0].code/name/openfda.device_class/medical_specialty_description, public_device_record_key, mri_safety.

#### 3.2.19 Business applications

#### 3.2.20 `servicenow` — ServiceNow (Table API) (ServiceNowConnector)

- Base = `baseUrl` + `/api/now/table`. Category `BUSINESS_APPS`, auth `BASIC` + `BEARER_TOKEN`. Properties `display.values` (BOOLEAN, default false), `max.rows` (INTEGER, default 200). Every call `requireSecret()`. CRUD operations of `AbstractCrudRestConnector`.
- Common query: `sysparm_display_value` = `display.values`, `sysparm_exclude_reference_link=true`, `sysparm_fields` = input `select`.
- `get` (object,id) → `GET /{table}/{sys_id}` → `result`. `query` (object; `filter` → `sysparm_query` encoded query, `max` → `sysparm_limit` default `max.rows`) → `{results, count, totalCount}` (`X-Total-Count` header). `create` POST → `result`; `update` PATCH → `result`; `upsert`: `id`, or lookup `sysparm_query=<lookupField>=<value>&sysparm_limit=1&sysparm_fields=sys_id` → PATCH when found, POST otherwise.
- `describeFields` (needs `object`, `requireSecret`): `GET /sys_dictionary?sysparm_query=name=<table>&sysparm_fields=element,column_label,internal_type&sysparm_limit=500` (note: under the `/api/now/table` base).

#### 3.2.21 `oracle-fusion` — Oracle Fusion Cloud (REST) (OracleFusionConnector)

- Base = `baseUrl` + `/<family>/resources/<apiVersion>`. Category `BUSINESS_APPS`, auth `BASIC` + `BEARER_TOKEN`. Properties `family` (default `fscmRestApi`; `crmRestApi`, `hcmRestApi`), `apiVersion` (default `11.13.18.05`), `max.rows` (default 200). Headers `Accept: application/json`, `REST-Framework-Version: 4`. `requireSecret()`.
- Inputs: `object` = resource (only `[A-Za-z0-9_/]` kept), `select` → `fields`, `expand`, always `onlyData=true`.
- `get` → `GET /{resource}/{id}`; `query` (`filter` → `q`, `max` → `limit`, `totalResults=true`) → `{results: items, count, totalCount: totalResults}`; `create` POST; `update` PATCH `/{resource}/{id}`; `upsert` POST collection with header `Upsert-Mode: true`.
- `describeFields` (needs `object`): `GET /{resource}/describe` → `Resources.<resource>.attributes` (name, title, type).

#### 3.2.22 `netsuite` — Oracle NetSuite (NetSuiteConnector)

- Base = `baseUrl` (trailing slashes removed) or, when blank, `https://<account lower-cased, _→->.suitetalk.api.netsuite.com/services/rest`; neither → "NetSuite: set property account or baseUrl". Category `BUSINESS_APPS`, auth `BEARER_TOKEN` + `CUSTOM`. Properties `account` (STRING, **required**), `max.rows` (INTEGER default 200). Headers `Accept` and `Content-Type: application/json`. `requireSecret()`.
- `get` (R, object,id) → `GET /record/v1/{type}/{id}`. `query` (S, **`query`** required = SuiteQL; `max` bounded 1..min(max.rows,1000), `offset` default 0) → `POST /query/v1/suiteql?limit&offset {q}` with `Prefer: transient`. `create` (W, object) POST `/record/v1/{type}`. `update` (W, object,id) PATCH; 204 → `{}`. `upsert` (W, object, **`externalId`**) → `PUT /record/v1/{type}/eid:{externalId}`; 204 → `{}`.
- Default validation applies: `baseUrl` is required by §1.3 even though the code can derive it from `account` (see Discrepancies).

#### 3.2.23 `dynamics365` — Microsoft Dynamics 365 (Dataverse) (Dynamics365Connector)

- Base = `baseUrl` + `/api/data/<apiVersion>`. Category `BUSINESS_APPS`, auth `OAUTH2_CLIENT_CREDENTIALS`, capabilities `BULK_READ`, `BULK_WRITE`, api `v9.2`. Properties `tenantId` (required), `clientId` (required), `apiVersion` (default `v9.2`), `max.rows` (default 200); code also reads `max.pages` (default 20, 1..100) and `batch.max.items` (default 50).
- Own token (not `OAuth2`): `POST https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token` form `grant_type=client_credentials, client_id, client_secret=apiKey, scope=<baseUrl>/.default`; missing → "Dynamics 365: set properties tenantId and clientId, and the client secret in apiKey"; expiry = `expires_in − 120 s` (min 60 s; 3000 s when absent). `headers()` replaced: `Accept`, `OData-MaxVersion: 4.0`, `OData-Version: 4.0`, `Prefer: return=representation`, `Authorization: Bearer`.
- Keys: a 36-char GUID as is; a value containing `=` used as an alternate-key predicate; otherwise quoted `'…'`.
- `get` → `GET /{set}({key})` with `$select/$expand` (ODataSupport) → normalised. `query` (`filter`, `select`, `expand`, `orderby`, `max`…; optional **`deltaLink`** → follow that link) → pages collected through `@odata.nextLink` up to `max.pages` pages and `max` rows (≤ `max.rows`, ≤10000). `create` POST; `update` PATCH (204 → `{}`); `upsert` PATCH `/{set}({id or lookupField='value'})`.
- **Native batch** (`executeBatch`): validates, item count ≤ `batch.max.items`, builds an OData v4 JSON `$batch` (`get`, `query`, `create`, `update`, `upsert`; others → "Dynamics 365: operation '<op>' cannot be batched"), wire size ≤ request byte limit ("Dynamics 365 batch exceeds byte limit N"), `POST /$batch` bounded; results mapped per item id.
- `describeFields` (needs `object`): `EntityDefinitions?$filter=EntitySetName eq '<set>'` → `LogicalName` → `/EntityDefinitions(LogicalName='…')/Attributes?$select=LogicalName,AttributeType,DisplayName`.

#### 3.2.24 `dynamics-business-central` — Microsoft Dynamics 365 Business Central (DynamicsBusinessCentralConnector)

- API root = `baseUrl` + `/v2.0/<tenantId>/<environment>/api/<apiVersion>/companies(<companyId>)` (segments sanitised to `[A-Za-z0-9._-]`). Category `BUSINESS_APPS`, auth `OAUTH2_CLIENT_CREDENTIALS`, capabilities BULK_READ/BULK_WRITE, api "Business Central API v2.0", pagination LINK 200/10000 `@odata.nextLink`.
- `configure`: when `tenantId` set and no `oauth.tokenUrl` → `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token`; `oauth.scope` default `https://api.businesscentral.dynamics.com/.default`; `oauth.clientId` ← `clientId`. Uses the common `OAUTH2` flow (authType must be `OAUTH2`).
- Properties: `tenantId` (required), `environment` (required, default `Production`), `companyId` (required), `clientId` (required), `apiVersion` (default `v2.0`), `max.rows` (default 200); code reads `max.pages` (default 20).
- Validation (replaces default): `baseUrl` must match `https://[^/]+.*` → "Business Central requires an HTTPS API endpoint"; `tenantId`, `environment`, `companyId`, `clientId` blank → "<p> is required"; placeholder `apiKey` → "apiKey: Microsoft Entra client secret is required".
- Headers `Accept: application/json`, `OData-Version: 4.0`, `Prefer: return=representation`. `requireSecret()`.
- Operations (declared list): `get` (object,id), `query` (object), `create` (object), `update` (object,id; optional `etag` → `If-Match`, default `*`; 204 → `{}`). **No `upsert`** (the §17 table lists one). Entity name via `safeName`, empty → "Business Central entity is invalid". Key: GUID or quoted string.

#### 3.2.25 `sap-successfactors` — SAP SuccessFactors (SuccessFactorsConnector)

- Profile that **delegates to `SapODataConnector`** (see `sap-odata`). Category `BUSINESS_APPS`, auth `BASIC` + `BEARER_TOKEN`, capabilities TEST_CONNECTION, SCHEMA_DISCOVERY, PREVIEW_RECORDS, BULK_READ, BULK_WRITE, api "SuccessFactors OData v2", pagination LINK 200/10000 `__next`.
- `configure` defaults: `odata.version=2`, `entitySet=PerPerson`, `max.rows=200`, `max.pages=20`, `default.expand=personalInfoNav,employmentNav`.
- Properties: `companyId`, `odata.version` (ENUMERATION `2`, default `2`), `entitySet`, `default.expand`, `max.rows`, `max.pages`.
- Validation = default + `baseUrl` must be https ("SuccessFactors API URL must use HTTPS") and end with `/odata/v2` ("SuccessFactors API URL must end with /odata/v2"; unparsable → "Invalid SuccessFactors API URL"); BASIC without `companyId` → "Company identifier is required for Basic authentication"; `odata.version` ≠ 2 → "SuccessFactors uses OData v2".
- Operation dispatch to the delegate: `getPerson` (R, `personIdExternal`) → `get` on `PerPerson` with key `personIdExternal='<id>'` (quotes doubled); `queryPeople` (S) → `query` PerPerson; `queryEmployment` (S) → `query` EmpEmployment; `queryUsers` (S) → `query` User; `createPerson` (W) → `create` PerPerson; `updatePerson` / `upsertPerson` (W, `personIdExternal`) → `update` / `upsert`; `describePeople` (R) → `describe` PerPerson. Missing id → "SuccessFactors: personIdExternal is required". `describeFields` and `testConnection` also delegate.

#### 3.2.26 `sap-ariba` — SAP Ariba (SapAribaConnector)

- Category `BUSINESS_APPS`, auth `API_KEY_HEADER`, `BEARER_TOKEN`, `OAUTH2_CLIENT_CREDENTIALS`; capabilities TEST_CONNECTION, PREVIEW_RECORDS, BULK_READ, BULK_WRITE; api "SAP Ariba Open APIs v1"; pagination OFFSET 100/500. Constant `MAX_ROWS = 500`.
- `configure` defaults: `api.version=v1`, `supplier.path=/supplier-data-api/v1/prod/suppliers`, `purchase-order.path=/procurement-api/v1/prod/purchaseOrders`, `max.rows=100`; with `API_KEY_HEADER` and no header name → `apiKeyHeader=apiKey`.
- Properties: `realm` (required), `api.version` (default `v1`; not used in URLs), `supplier.path`, `purchase-order.path`, `max.rows` (default 100).
- Validation = default + `baseUrl` must start with `https://` ("SAP Ariba endpoints must use HTTPS"); `realm` blank → "The SAP Ariba tenant realm is required"; authType other than API_KEY_HEADER/BEARER/OAUTH2 → "authentication: Use API key, bearer token, or OAuth 2.0 authentication"; each path must start with `/` and contain no `..`, `?`, `#` → "Use an absolute API path without traversal or query text"; `max.rows` 1..500 → "Must be between 1 and 500".
- Headers `Accept`/`Content-Type: application/json`, `X-Ariba-Realm: <realm>`. Every call query: `realm`, `limit` = input `max` or `max.rows` bounded 1..500 (unparsable → 1), and `offset`, `filter`, `updatedSince` when given. `requireSecret()`.
- `getSupplier` (R, `supplierId`) GET `<supplier.path>/{id}`; `searchSuppliers` (S) GET `<supplier.path>`; `upsertSupplier` (W, `supplierId`) PUT; `getPurchaseOrder` (R, `documentId`); `searchPurchaseOrders` (S); `acknowledgePurchaseOrder` (W, `documentId`) POST `<po.path>/{id}/acknowledgements`. Searches normalised: an array → `{results,count}`; first of `records`/`items`/`results`/`content` → `{results, count, totalCount from totalCount/count/total}`; otherwise the raw reply.

#### 3.2.27 `sharepoint` — Microsoft SharePoint (SharePointConnector)

- Microsoft Graph v1.0. Label in code "Microsoft SharePoint" (GUIDE: "Microsoft SharePoint Online (Microsoft Graph)"). Category `BUSINESS_APPS`, auth `OAUTH2_CLIENT_CREDENTIALS`, capabilities BULK_READ/BULK_WRITE, pagination LINK 200/5000 `@odata.nextLink`.
- `configure`: `oauth.tokenUrl` ← `https://login.microsoftonline.com/<tenantId>/oauth2/v2.0/token`, `oauth.scope` default `https://graph.microsoft.com/.default`, `oauth.clientId` ← `clientId`. `Accept: application/json`.
- Properties: `tenantId` (required), `clientId` (required), `siteId`, `driveId`, `max.rows` (default 200), `max.file.bytes` (default 1048576).
- Validation (replaces default): `baseUrl` must match `https://graph.microsoft.(com|us|de|cn)(/.*)?` → "Use an HTTPS Microsoft Graph cloud endpoint"; `tenantId`/`clientId` blank → "<p> is required"; placeholder apiKey → "Microsoft Entra client secret is required".
- Operations (every call `requireSecret()`):
  - `site` (R, `host`, `path`) → `GET /sites/{host}:/{server path}`.
  - Site-scoped (input `siteId` or property; blank → "SharePoint siteId is required"): `listItems` (S, `listId`; `filter`/`select`/`orderby`/`expand` → `$…`, `$top` = `max` bounded 1..999, `$expand=fields`) → pages; `getItem` (R, listId,itemId, `$expand=fields`); `createItem` (W, listId) POST `{fields: payload}`; `updateItem` (W, listId,itemId) PATCH `…/items/{id}/fields`.
  - Drive-scoped (input `driveId` or property; blank → "SharePoint driveId is required"): `listFiles` (S, driveId; optional `folderId` default `root`) → `/drives/{d}/items/{folder}/children` pages; `downloadTextFile` (R, driveId,itemId; `maxBytes` input or `max.file.bytes`, bounded 1..10485760) → bounded GET `/content` → `{contentBase64, bytes, encoding:"UTF-8"}`; `uploadFile` (W, driveId, `path`; payload `contentBase64` required → "SharePoint upload requires contentBase64", invalid → "SharePoint contentBase64 is invalid", larger than `max.file.bytes` → "SharePoint upload exceeds max.file.bytes"; optional `contentType` default `application/octet-stream`) → `PUT /drives/{d}/root:/{path}:/content`.
- Pagination: `RestPageTraversal.collect(url, "value", "@odata.nextLink", 50 pages, max)`, `max` = input or `max.rows` bounded 1..5000.
- Sanitisation: ids keep `[A-Za-z0-9,._-]`; paths strip `..` and anything outside `[A-Za-z0-9/._ -]`, spaces → `%20`. Missing input → "SharePoint input '<k>' is required".
- Operation names differ from the inventory JSON (which has none for SharePoint).

#### 3.2.28 `hubspot` — HubSpot CRM (HubSpotConnector)

- API `https://api.hubapi.com` CRM v3 (javadoc), `apiKey` = private app token (BEARER). Category `BUSINESS_APPS`, auth `BEARER_TOKEN`, api `v3`. CRUD operations; `requireSecret()`.
- Base `/crm/v3/objects/{object}` (`companies`, `contacts`, `deals`, custom). `select` → `properties`.
- Documents flattened: `{id, …properties, createdAt, updatedAt, archived}`. Outbound payload wrapped as `{properties:{…}}` (key `id` skipped; non-scalar values written as JSON text).
- `get` GET `/{id}`; `query` → `POST /search` with `filterGroups[0].filters` from `filter` = `property OPERATOR value` clauses separated by `;` (operators EQ, NEQ, CONTAINS_TOKEN, GT, LT, HAS_PROPERTY…; clauses with fewer than 2 tokens ignored), `properties` from `select`, `limit` = min(100, `max` default 100) → `{results, count, totalCount}`; `create` POST; `update` PATCH; `upsert`: `id` or search `<lookupField> EQ <value>` limit 1, then PATCH or POST.
- `describeFields` (needs `object`): `GET /crm/v3/properties/{object}` (name, label, type).

#### 3.2.29 `workday` — Workday (REST and RaaS) (WorkdayConnector)

- Category `BUSINESS_APPS`, auth `OAUTH2_REFRESH_TOKEN` + `BASIC`. Properties `tenant` (required), `clientId` (required), `refreshToken` (SECRET, required). Note: the descriptor makes `clientId`/`refreshToken` mandatory even for RaaS-only use.
- OAuth (own implementation): `POST <baseUrl>/ccx/oauth2/<tenant>/token`, Basic `clientId:apiKey`, form `grant_type=refresh_token&refresh_token=…`; missing → "Workday REST: set properties clientId, refreshToken and the client secret in apiKey"; token kept **50 minutes**; failure "Workday token failed (HTTP n): …". `headers()` adds `Accept: application/json` and `Authorization: Bearer <token>` when a token exists (overriding the common auth header).
- `tenant` missing → "Workday: set the property tenant".
- `workers` (S; `search`, `max`) → `GET /ccx/api/v1/{tenant}/workers` read page by page with `limit` (≤ 100, what is left of `max`) and `offset` until `total`, `max` or an empty page (at most 100 pages) → `{results: data, count, totalCount: total}`. `max` default 100, ceiling property `max.rows` (INTEGER, default 1000, at most 10000). `worker` (R, `id`). `resource` (S, `path`, never `..`, `?` or a URL ("Workday resource: path must be a resource path such as /organizations"); `query` = `a=b&c=d` pairs) → paged the same way when the answer has `data`, raw otherwise. A 401 on a REST call drops the token, refreshes it once and repeats the call.
- `raas` (S, `report`: absolute path starting with `/`, or `<owner>/<report>` appended to `/ccx/service/customreport2/<tenant>/`; every other non-empty input except `report`/`max` becomes a query prompt; `format=json`) uses **Basic** with the integration user (token temporarily cleared); username null or password placeholder → "Workday RaaS: set the integration user name and password (authentication BASIC)" → `{results: Report_Entry, count}`.
- `describeFields`: workers/worker → id, descriptor, primaryWorkEmail, primaryWorkPhone, businessTitle, primarySupervisoryOrganization.descriptor, isManager, person.email.

#### 3.2.30 Messaging

#### 3.2.31 `webhook` — Inbound webhook (HTTP POST) (WebhookConnector)

- Category `MESSAGING`, auth `API_KEY_HEADER` + `BASIC`, api "Internal EBX webhook endpoint", capability PREVIEW_RECORDS. Property `sample` (JSON): sample document for the designer.
- The connector row no longer holds a secret that is read (C2-10): the sender is an EBX user with the RUN right and the import runs under that user; the HTTP endpoint lives in the EBX layer. (Class javadoc cites `/ebx-ps-fasttrack/connectors/webhook/{mapping}` — see Discrepancies.)
- `receive` (S, no input) returns the payload pushed by the caller as is (null when called with no document, e.g. from a user service).
- `describeFields`: flattens `sample` (max 200 fields) to `path, type, sample`; invalid JSON → "Webhook: the property sample is not valid JSON: …".

#### 3.2.32 `chat-webhook` — Slack / Microsoft Teams incoming webhook (ChatWebhookConnector)

- `baseUrl` = the webhook URL (it carries the secret). Category `MESSAGING`, auth `CUSTOM`, api "Provider-defined incoming webhook". Property `format` (ENUMERATION `auto`, `slack`, `teams`, `adaptive`; default `auto`).
- `auto` resolution: URL contains `hooks.slack.com` → slack; `logic.azure.com` or `powerautomate` → adaptive; `webhook.office.com` → teams; else slack.
- `notify` (W, `text`; optional `title`): when `text` input empty, taken from payload `text` (and `title`), else the whole payload as JSON text; still empty → "Missing input 'text'".
- Bodies: slack `{text: "*title*\ntext"}`; teams MessageCard `{@type:"MessageCard", @context:"https://schema.org/extensions", summary (title or first 80 chars), title?, text}`; adaptive `{type:"message", attachments:[{contentType:"application/vnd.microsoft.card.adaptive", content:{type:"AdaptiveCard", version:"1.4", $schema, body:[TextBlock title (Bolder, Medium)?, TextBlock text wrap]}}]}`. `POST ""`; non-2xx → "<id>: notify -> HTTP n <body>".
- Returns `{status:"sent", http, format}`.

#### 3.2.33 `rabbitmq` — RabbitMQ (RabbitMqConnector)

- AMQP 0-9-1 through `com.rabbitmq:amqp-client` loaded **by reflection** (ReflectiveRabbitMqTransport). Category `MESSAGING`, auth `BASIC` + `NONE`, capabilities STREAMING, INCREMENTAL_READ, api `AMQP 0-9-1`, dependency `com.rabbitmq:amqp-client:5.x` (packaged `amqp-client-5.35.0.jar`).
- Registered with `registerType` (not quietly): the type is listed even without the jar; the failure appears at execution ("RabbitMQ connect failed: …").
- Properties: `queue` (required), `exchange`, `routing.key`, `prefetch` (INTEGER; default 100, bounded 1..500 → `basicQos`), `max.messages` (INTEGER; default 100, bounded 1..500).
- Validation (replaces default): `baseUrl` must start with `amqp://` or `amqps://`; `queue` blank → "Queue is required for receive and peek".
- Transport: `ConnectionFactory.setUri(baseUrl)`, username/password when set, connection timeout = `connectTimeoutMs`, handshake timeout = `readTimeoutMs`. A new connection is opened and closed **per execute**.
- `publish` (W, `routingKey` declared required; value = input `routingKey` or property `routing.key` or `queue`; `exchange` input or property, default ""): body = payload JSON (`{}` when null) → `{exchange, routingKey, bytes}`; none → "RabbitMQ routingKey or queue is required".
- `receive` (S) / `peek` (R): `queue` input or property; up to `max` `basicGet(queue, autoAck=false)`; each message `{deliveryTag, redelivered, remaining, value}` (JSON parsed, else text); `receive` acks each message, `peek` nacks with requeue; a runtime failure nacks with requeue and throws "RabbitMQ delivery processing failed". Empty → null. Result `{queue, count, acknowledged: !peek, messages}`.

#### 3.2.34 `jms` — JMS messaging (JmsConnector)

- Provider-neutral JMS 1.1 through JNDI, reflection-isolated (ReflectiveJmsTransport). Category `MESSAGING`, auth `NONE` + `BASIC`, capabilities STREAMING, INCREMENTAL_READ, api "JMS 1.1 provider-defined", dependency "JMS API and provider client" (required), pagination OFFSET 100/500. Constants `MAX_MESSAGES = 500`, `MAX_TIMEOUT_MILLIS = 60000`.
- `configure` defaults: `connection.factory=ConnectionFactory`, `destination.type=QUEUE`, `acknowledgement=CLIENT`, `max.messages=100`, `receive.timeout.ms=3000`, `redelivery.max=6`, `redelivery.delay.ms=1000`.
- Properties: `initial.context.factory`, `connection.factory`, `destination`, `selector` (STRING); `destination.type` (ENUM QUEUE/TOPIC); `acknowledgement` (ENUM AUTO/CLIENT/DUPS_OK/TRANSACTED); `max.messages`, `receive.timeout.ms`, `redelivery.max`, `redelivery.delay.ms`, `priority` (default 4, 0..9), `ttl.ms` (default 0, 0..86400000) (INTEGER); `persistent` (BOOLEAN, default true).
- Validation = default (so `baseUrl` = JNDI provider URL is required) + `initial.context.factory` and `connection.factory` required ("A value is required"); ranges `max.messages` 1..500, `receive.timeout.ms` 1..60000, `redelivery.max` 0..100, `redelivery.delay.ms` 0..3600000 ("Must be between a and b").
- Operations (all require input `destination`, falling back to property `destination`; none → "JMS: destination is required"):
  - `publish` (W): body = input `body` or payload (text node as is, else JSON); inputs `header.<name>` → string properties; `persistent`, `priority`, `ttl.ms` → `{destination, messageId, timestamp}`.
  - `receive` (S) / `peek` (R): `max` (1..500), `timeoutMs` (1..60000; the first receive waits `timeout`, the next ones 1 ms), `selector`; peek uses a transacted session rolled back; CLIENT acknowledges per message; TRANSACTED commits. Result `{destination, count, peek, messages:[{id, correlationId, timestamp, redelivered, value}]}`; empty → null.
- Transport details: JNDI `InitialContext` with `INITIAL_CONTEXT_FACTORY` and `PROVIDER_URL` = `baseUrl`; connection factory looked up by name; username/password when username set; provider redelivery policy set when the connection has `getRedeliveryPolicy` (ActiveMQ); destination looked up in JNDI, else `createTopic`/`createQueue`. Bounded integers fall back to the **minimum** when unparsable. Any failure → "JMS <op> failed: <msg>".
- `describeFields`: messages[0].id, messages[0].value, messages[0].redelivered.

#### 3.2.35 `activemq` — Apache ActiveMQ (ActiveMqConnector)

- Extends `JmsConnector`; constant `INITIAL_CONTEXT_FACTORY = org.apache.activemq.jndi.ActiveMQInitialContextFactory` set by `configure` when absent.
- Validation = JMS + `baseUrl` must start with `tcp://`, `ssl://` or `failover:` ("ActiveMQ provider URL must use tcp://, ssl://, or failover:"); another `initial.context.factory` → "Apache ActiveMQ requires org.apache.activemq.jndi.ActiveMQInitialContextFactory".
- api "ActiveMQ Classic JMS 1.1"; dependency `org.apache.activemq:activemq-client:5.x` (required). Same operations as `jms`.

#### 3.2.36 `azure-service-bus` — Microsoft Azure Service Bus (AzureServiceBusConnector)

- Category `MESSAGING`, auth `CUSTOM` + `NONE`, capabilities STREAMING, INCREMENTAL_READ, BULK_READ, api "Azure Service Bus AMQP 1.0", dependency `com.azure:azure-messaging-servicebus:7.x`, pagination CURSOR 100/500 `lockToken`. Constant `HARD_MAX = 500`.
- Transport factory found through `ServiceLoader<AzureServiceBusTransport.Factory>` at each execution (none → "Azure Service Bus transport adapter is not installed"); the module's service file names `AzureServiceBusSdkTransportFactory`, which needs `com.azure.messaging.servicebus.ServiceBusClientBuilder` on the classpath ("Azure Service Bus SDK is not packaged (azure-messaging-servicebus and dependencies are required)").
- `configure` defaults: `auth.mode=CONNECTION_STRING`, `receive.mode=PEEK_LOCK`, `max.messages=100`, `wait.ms=5000`.
- Properties: `auth.mode` (ENUM `CONNECTION_STRING`, `AZURE_IDENTITY`), `entity`, `subscription` (STRING), `entity.type` (ENUM QUEUE/TOPIC), `receive.mode` (ENUM PEEK_LOCK/RECEIVE_AND_DELETE), `max.messages`, `wait.ms` (INTEGER).
- Validation (replaces default): `baseUrl` must start with `sb://` or `https://` and contain `.servicebus.windows.net` ("Namespace must be an sb:// or https://*.servicebus.windows.net endpoint"); `CONNECTION_STRING` mode needs `apiKey` containing `Endpoint=sb://`, `SharedAccessKeyName=`, `SharedAccessKey=` ("A complete Service Bus connection string must be stored in the encrypted API key field"); other mode than the two → "Use CONNECTION_STRING or AZURE_IDENTITY".
- Authentication: connection string = `apiKey`; `AZURE_IDENTITY` uses `DefaultAzureCredentialBuilder` with the namespace host taken from `baseUrl` (scheme `sb://`/`https://` and path removed).
- Operations (input `entity` always required — property `entity` is **not** used as fallback; `subscription` input or property):
  - `publish` (W, entity): `entityType` input or `entity.type` = TOPIC → topic sender; body = payload JSON; inputs `property.<name>` → application properties → `{entity, published:1}`.
  - `receive` (S, entity): `mode` input or `receive.mode`; `max` (1..500, default `max.messages`), `waitMs` (≥1, default `wait.ms`) → `{entity, mode, messages:[{messageId, lockToken, enqueuedAt, value}], count}`; empty → null. PEEK_LOCK keeps the receiver and message in a static lock table keyed by lock token.
  - `peek` (R, entity): `peekMessages(max)` (no lock) → mode `PEEK`.
  - `complete` / `abandon` (W, entity, `lockToken`): settles a message received earlier in PEEK_LOCK **by the same JVM**; unknown or expired → "Unknown or expired Service Bus lock token"; → `{entity, settlement, lockToken}`.
  - Entity routing: a subscription (or `topic:` prefix) → topic + subscription, else queue.
- Locks retained more than **5 minutes** are reaped (receiver closed) when a transport is opened.
- Errors → "Azure Service Bus <op> failed: <msg>".

---


#### 3.2.37 `dnb` — Dun & Bradstreet Direct+ (DnbConnector)

- API `https://plus.dnb.com` (javadoc). Category `CORPORATE_REGISTRIES`, auth advertised `OAUTH2_CLIENT_CREDENTIALS`; configured as `authType BASIC` with `username` = consumer key, `password` = consumer secret.
- Token: username null or password placeholder → "D&B: set the consumer key (username) and consumer secret (password), authentication BASIC"; `POST <baseUrl>/v2/token` with Basic header and JSON body `{"grant_type":"client_credentials"}`; expiry = `expiresIn − 120 s` (min 60 s; 3600 s when absent); failure → "D&B token request failed (HTTP n): <error.errorMessage>". `headers()` replaced by `Accept: application/json` + `Authorization: Bearer <token>`.
- Properties: `blockIDs` (default `companyinfo_L2_v1,hierarchyconnections_L1_v1`), `candidates.max` (INTEGER, default 10), `confidence.min` (STRING, default 7), `country` (default `FR`), `tradeUp` (declared BOOLEAN, passed through verbatim as query `tradeUp`; javadoc suggests value `hq`), `registration.reference`, `registration.path` (template with `{reference}` and `{type}`), `notifications.path` (template with `{reference}`).
- Match query: `countryISOAlpha2Code` (input `country` upper-cased or property), inputs mapped `name→name`, `street→streetAddressLine1`, `city→addressLocality`, `postalCode→postalCode`, `registrationNumber→registrationNumber`, `duns→duns`, `region→addressRegion`, `telephone→telephoneNumber`; `candidateMaximumQuantity` = `candidates.max`; `tradeUp` when set. Only country + candidate count → "D&B match: give at least a name, a registration number or a DUNS".
- Operations:
  - `match` (S) → `GET /v1/match/cleanseMatch` → `{matchCandidates, count, inquiryDetail}`; 404 or no candidate → null.
  - `bestMatch` (R) → highest `matchQualityInformation.confidenceCode`; below `confidence.min` → null; → `{duns, confidenceCode, matchGrade?, organization, matchQualityInformation}`.
  - `dataBlocks` (R, `duns`: 9 digits after stripping non-digits, else "D&B: '<v>' is not a 9-digit DUNS"; optional input `blockIDs`) → `GET /v1/data/duns/{duns}?blockIDs&tradeUp`.
  - `familyTree` (R, `duns`) → data blocks `hierarchyconnections_L1_v1`.
  - `enrich` (R) → bestMatch then dataBlocks; adds `organization.match = {duns, confidenceCode, matchGrade}`.
  - `monitoringUpdates` (S) → `GET <notifications.path>` with `{reference}` URL-encoded.
  - `registrationAdd` / `registrationRemove` (W, `duns`: list separated by `,`, `;` or whitespace, each 9 digits, deduplicated; none → "D&B: at least one 9-digit DUNS is required") → `PATCH <registration.path>` (`{type}` = `add`/`remove`) with a multipart body (field `duns`, file `duns.txt`, one DUNS per line) → `{registrationReference, action, count, accepted:true}`; non-2xx → "D&B registration <type> failed (HTTP n)".
  - Monitoring templates: missing reference or template → "D&B: set registration.reference and <prop> before using monitoring"; template not starting with `/`, containing `..`, lacking `{reference}` (or `{type}` for writes) → "D&B: unsafe <prop> template".
- `describeFields`: `match` → matchCandidates[0] duns/primaryName/confidenceCode/matchGrade; `bestMatch` → duns, confidenceCode, organization.primaryName, city; others → 32 `organization.*` paths (duns, names, registration numbers, operating status, dates, legal form, primary address incl. lat/long, telephone, website, employees, revenue, industry codes, US SIC, isStandalone, corporate linkage global/domestic ultimate and parent, `organization.match.confidenceCode`).

#### 3.2.38 Generic integration

#### 3.2.39 `rest` — Generic JSON REST (GenericRestConnector)

- Category `GENERIC_INTEGRATION`, auth `NONE`, `BASIC`, `API_KEY_HEADER`, `BEARER_TOKEN`, `OAUTH2_CLIENT_CREDENTIALS` (runtime: all five `authType`s through `AbstractRestConnector`), capability `SCHEMA_DISCOVERY`. No static operation: every operation is declared by properties.
- Descriptor properties: `auth.apiKeyQuery` (STRING, advanced), `op.<name>.method` (ENUMERATION `GET`,`POST`,`PUT`,`PATCH`,`DELETE`, **required**), `op.<name>.path` (STRING, **required**), `op.<name>.kind` (ENUMERATION `READ`,`SEARCH`,`WRITE`).
- Per-operation properties read by the code:

| Property | Meaning |
|---|---|
| `op.<name>.path` | **Declares** the operation (an operation exists for each key ending in `.path`). Path appended to `baseUrl`; `{placeholder}` replaced by the input of that name, URL-encoded (missing → empty). |
| `op.<name>.method` | HTTP method, upper-cased; code default `GET`. GET/DELETE → READ, POST/PUT/PATCH → WRITE. |
| `op.<name>.query` | `k=v&k2={input}` pairs; a value that is exactly one `{placeholder}` whose input is missing/blank is **not sent**; other placeholders filled without URL encoding (the URL builder encodes). |
| `op.<name>.required` | Comma list of mandatory inputs (`-` = none). Absent → every placeholder of path and query is required; present → the other placeholders become optional inputs. |
| `op.<name>.kind` | Overrides the kind (`READ`, `SEARCH`, `WRITE`; upper-cased by `Operation.Kind.valueOf`). Without it a READ becomes SEARCH when a declared `op.<name>.fields` path starts with `[0]` or matches `name[0].…`. |
| `op.<name>.description` | Shown in operation lists; default `<METHOD> <path>`. |
| `op.<name>.fields` | `path=type;path=type` catalogue (written by the OpenAPI import) returned by `describeFields` for read operations. |
| `op.<name>.bodyFields` | Same for write operations (POST/PUT/PATCH). |
| `op.<name>.responseRoot` | Path extracted from the response (`RestPayload.root`) and item root for pagination. |
| `op.<name>.header.<Name>` | Extra request header, placeholders filled. Header names must match the RFC token grammar (`[!#$%&'*+.^_`|~0-9A-Za-z-]+`), values without control characters, else `IllegalArgumentException`. |
| `op.<name>.cookie.<name>` | Cookie pairs joined into one `Cookie` header. |
| `op.<name>.contentType` | `multipart/form-data` sends the payload as multipart: an object field `{base64, filename?, contentType?}` becomes a file part (invalid base64 → "Invalid base64 multipart field '<f>'"), other fields text parts (non-scalars as JSON). |
| `op.<name>.pagination.next` | For GET only: JSON path (or link convention) of the next page; enables `RestPageTraversal.collect`. |
| `op.<name>.pagination.maxPages` | Default 20, bounded 1..100 (unparsable → 1). |
| `op.<name>.pagination.maxRecords` | Default 1000, bounded 1..10000 (unparsable → 1). |
| `auth.apiKeyQuery` | Query parameter name that receives `apiKey` (OpenAPI `apiKey in: query`), only when the key is not a placeholder. |

- Execution: missing `op.<op>.path` → "<id>: no property op.<op>.path". GET and DELETE send no body; POST/PUT/PATCH send the payload. Result = `bodyOrNull` (404 → null) then `responseRoot`. Pagination failure → "<id>: pagination failed: …".
- `getOperations()` returns an empty list before `configure` (catches `IllegalStateException`).
- Created by hand or by the OpenAPI importer (core `OpenApiImporter`, other section). Wikidata is a seeded `rest` connector (GUIDE §3.2).

#### 3.2.40 `graphql` — Generic GraphQL API (GraphqlConnector)

- `baseUrl` = the GraphQL endpoint. Category `GENERIC_INTEGRATION`, auth `NONE`, `BASIC`, `API_KEY_HEADER`, `BEARER_TOKEN`, `OAUTH2_CLIENT_CREDENTIALS`.
- Descriptor properties: `op.<name>.query` (STRING, required), `op.<name>.variables` (JSON, advanced), `op.<name>.write` (BOOLEAN, default false).
- Per-operation properties: `op.<name>.query` (**declares** the operation), `op.<name>.variables` (JSON template, `{input}` placeholders JSON-escaped), `op.<name>.result` (path under `data` returned; default `data`), `op.<name>.required` (comma list, `-` = none; default = the `$variables` declared in the query, pattern `$name:`), `op.<name>.fields` (`path=type;…` for the designer), `op.<name>.write` (`true` → WRITE, else READ — never SEARCH), `op.<name>.description` (default "GraphQL operation <name>"), `op.<name>.path` (appended to base URL, default "").
- Execution: `POST {query, variables}`. Without a variables template, each `$var` of the query whose input is non-blank is sent as a **string**, then the fields of an object payload are merged in (outbound rules build variables). Template filled but not valid JSON → "<id>: op.<op>.variables is not JSON once filled: …". `errors[0].message` → `ConnectorException("<id>: <op>: <message>")`. `data` (or `data.<result>`) null → null.
- Missing query property → "<id>: no property op.<op>.query".

#### 3.2.41 `soap` — Generic SOAP web service (SoapConnector)

- Category `GENERIC_INTEGRATION`, auth `NONE`, `BASIC`, `API_KEY_HEADER`, `BEARER_TOKEN`. Converts the XML answer to JSON (namespaces dropped, repeated elements as arrays; `Xml.toJson`).
- Descriptor properties: `soap.version` (ENUM `1.1`/`1.2`, default `1.1`), `wsdl.path`, `wsdl.document`, `wsdl.baseUrl` (STRING, advanced), `wsdl.import.maxDepth` (default 4, 0 allowed), `wsdl.import.maxDocuments` (default 24), `wsdl.import.maxBytes` (default 4194304), `wsdl.import.maxMillis` (default 15000), `wsse.username`, `wsse.password` (SECRET), `wsse.password.type` (ENUM `text`/`digest`, default `text`), `op.<name>.body` (required), `op.<name>.action` (advanced).
- Per-operation properties: `op.<name>.body` (**declares** the operation; XML placed inside the SOAP Body, `{input}` XML-escaped), `op.<name>.action` (SOAPAction header in 1.1 / action parameter in 1.2), `op.<name>.path` (appended to base URL; its placeholders count as required), `op.<name>.result` (local name of the element returned; default first element child of `Body`; no Body → whole document), `op.<name>.required` (default every placeholder of body and path; `-` = none), `op.<name>.fields`, `op.<name>.description` (default "SOAP operation <name>"), `op.<name>.write` (`true` → WRITE, else READ).
- WSDL mode: when no `op.*.body` is declared, operations come from `wsdl.document` (inline) parsed by `SoapSupport.parseWsdl` (READ, required inputs = the WSDL request fields); at execution, the operation's request template, version and action are taken from the WSDL fetched from `wsdl.document` or `GET <baseUrl><wsdl.path>` (`Accept: application/wsdl+xml, text/xml, application/xml`), imports followed within the `wsdl.import.*` limits, **cross-origin redirects rejected**; invalid limits → "Invalid WSDL import safety limits: …" / "Invalid <prop>: <value>".
- WS-Security: `wsse.username`/`wsse.password` add a UsernameToken; `digest` builds a PasswordDigest with a 16-byte `SecureRandom` nonce and the current instant.
- Execution: `POST <path>` with the envelope and SOAP headers. Empty body: success → null, else "<id>: <op> -> HTTP n". Not XML → "<id>: <op> -> HTTP n, not XML: <first 200 chars>". A `Fault` element → `SoapSupport.fault` exception (fault string). Non-2xx without fault → "<id>: <op> -> HTTP n".
- `discoverObjects` with a WSDL: operations filtered by name substring, paged with a numeric continuation token (invalid → "Invalid WSDL continuation token"), type `SOAP <version>`. `describeObject` with a WSDL returns the operation's request fields.

#### 3.2.42 `ebx` — EBX (another instance, Data Services) (EbxConnector + EbxCatalog)

- `baseUrl` = `http://host/ebx-dataservices/rest/data/v1` (another ON EBX instance, or this one). Category `GENERIC_INTEGRATION`, auth `BASIC`, api `data/v1`. Property `dataspacePrefix` (ENUM `B` = branch (default), `V` = snapshot/version).
- Record operations (table path = `/<prefix><dataspace>/<dataset>/<table>`; a `table` not starting with `/` gets one):
  - `read` (R, `dataspace`, `dataset`, `table`, `key`) → `GET …/{key}` → `content` **unwrapped** (every `{field:{content:v}}` envelope removed recursively; list items' `content` unwrapped).
  - `search` (S, dataspace, dataset, table; optional `filter` (XPath), `pageSize` default 100) → `{rows:[record + _key], count}` (`_key` = last segment of the row's `details` link). Always returns a document (even with 0 rows).
  - `write` (W, dataspace, dataset, table; optional `key`): payload must be an object ("<id>: write needs the document built by an outbound mapping"); payload wrapped into envelopes; `key` → `PUT …/{key}`, else `POST` table; non-2xx → "<id>: write -> HTTP n <body>"; → `{status: created|updated, http, key}` (key from `Location` header, or the input key).
- Data-catalog operations (EbxCatalog; all require input `source`; common inputs `schemas` = comma list of dataspaces or `dataspace/dataset`, empty or `*` = every non-technical dataspace **including children**; `exclude` = regex matched against `/<qualifiedName>` and against `/<dataspace>/<dataset>/` (default `DEFAULT_EXCLUDE` = `.*(/TechnicalData/|_MDS/|/ebx-|/Bebx-).*`); `rowCounts` (`false` skips counts); `tables` = comma list of qualified names (case-insensitive) or `*`):
  - Discovery: `GET /` lists root dataspaces (keys without prefix, `ebx-*` and closed ones skipped, `isSelectAllowed` honoured), recursion through `/<prefix><ds>:children` when `hasChildren`; datasets from `GET /<prefix><ds>` (`ebx-*` skipped); table paths from the dataset tree (a node carrying `details` and no object `content` is a table).
  - `catalog` (S) → `{source, scannedAt, tables:[{source, assetKey = source:qualifiedName, qualifiedName = <ds>/<dataset><tablePath>, schema = <ds>/<dataset>, name = <tablePath>, kind TABLE, label?, remarks? (≤4000 chars), rowCount? (from `…:count`), url = baseUrl + path, scannedAt, primaryKeys[], columns:[…]}], count}`; null when no table. Metamodel read with `pageSize=2&includeMetamodel=true&includeContent=false`. Columns: terminal fields, groups flattened (`Group/field`), associations/tables/non-terminal skipped, `Linked_Field_*`, `Association_Reverse`, `crossRef` skipped; `columnKey`, `name`, `position`, `dataType` (map: string/text/email/uri/anyURI/name/color/locale/html/password/foreignKey/select/osd:text → STRING; int/integer/long → INTEGER; decimal → DECIMAL; date → DATE; dateTime → TIMESTAMP; time → TIME; boolean → BOOLEAN; other → STRING), `nativeType` (`foreignKey -> ds/dataset/tablePath`, `<type> (enumeration)`, `list<…>` when maxOccurs ≠ 1, `, computed` with a value function, `, auto-increment`), `nullable` (= not mandatory: minOccurs ≥1 or a `mandatory` constraint), `primaryKey`, `label` (when different from the leaf name), `remarks`, `foreignKey` (raw object), `scannedAt`.
  - `profile` (S; `sampleSize` default 1000, bounded 3..10000) → runs `catalog`, reads the first `sampleSize` records of each table (`pageSize=sampleSize`), `RecordProfiler.profile(…)` per column with the exact row count → `{source, profiledAt, profileDate, columns:[{source, assetKey, qualifiedName, columnKey, name, dataType, profiledAt, profileDate, + RecordProfiler statistics, method SAMPLE}], count}`.
  - `sample` (S; `limit` default 50, bounded 3..500) → first 200 STRING columns per table, `RecordProfiler.distinctValues(records, col, 200)` → `{…, columns:[{…, sampledAt, sampleSize, values[], valuesText (newline-joined)}], count}`.
  - `lineage` (S; `fkScope` = `cross` (default: only keys pointing at another dataspace/dataset) or `all`) → one edge per foreign key whose target table is in the scanned catalog **and has exactly one primary key**: `{source, edgeKey = fromColumnKey>toColumnKey, fromAssetKey (target table), fromColumnKey (target PK), toAssetKey, toColumnKey (the FK field), transformationType IDENTITY, transformation "foreign key <field> -> <target> (primary key <pk>)", origin PLATFORM, confidence 100, seenAt}`.
  - Note: `profile`, `sample`, `lineage` return **null** (not an empty document) when nothing is found. Missing `source` → "Missing input 'source'".
- `describeFields` describes each operation's document (catalog/profile/sample/lineage/write/search; `read` → one placeholder field "(record fields)").

#### 3.2.43 `openlineage` — OpenLineage sink (OpenLineageConnector)

- Type constant `OpenLineageEmitter.TYPE = "openlineage"`. Label "OpenLineage (data catalog, Marquez, DataHub...)". Category `DATA_PLATFORMS` (the §17 table and inventory list no category; `legacyDefaultCategory` would give GENERIC_INTEGRATION — irrelevant since the descriptor declares one). Auth `NONE`, `BASIC`, `API_KEY_HEADER`, `BEARER_TOKEN`; api "OpenLineage 2.0".
- `baseUrl` = the endpoint accepting RunEvents, e.g. `http://host/ebx-data-catalog/rest/v1/openlineage`, `http://marquez:5000/api/v1/lineage`.
- Properties (all read by `core/OpenLineageEmitter`, not by this class): `ebx.namespace` (default `ebx://localhost:8080`), `job.namespace` (default `ebx-connectors`), `emit.outbound` (BOOLEAN, default true), `emit.inbound` (BOOLEAN, default false, advanced), `emit.declared` (BOOLEAN, default true, advanced), `exclude.types` (default `chat-webhook,slack,openlineage,llm`), `exclude.connectors`, `producer` (default `OpenLineageEmitter.PRODUCER` = `https://github.com/on-ebx/ebx-connectors`).
- `configure` calls `OpenLineageEmitter.invalidate()` so the list of sinks is re-read at the next run.
- `emit` (W, no input): payload must be an object or array ("<id>: emit needs a RunEvent (or an array of events) as document"); an object without `job` → "<id>: not a RunEvent (no 'job')"; `POST ""` (the base URL itself); non-2xx → "<id>: emit -> HTTP n <body>"; → `{status:"sent", http, events (1 or array size), reply? (object answer)}`.
- Automatic emission of run and declared-lineage events is implemented in `core` (other section).

#### 3.2.44 Business applications (continued)

#### 3.2.45 `salesforce` — Salesforce (REST, OAuth 2.0) (SalesforceConnector)

- `baseUrl` = login URL (`https://login.salesforce.com`, `https://test.salesforce.com`, or My Domain). Calls go to `<instanceUrl>/services/data/<apiVersion>`. Category `BUSINESS_APPS`, auth `OAUTH2_CLIENT_CREDENTIALS`, `CUSTOM`, `CERTIFICATE`; capability `SCHEMA_DISCOVERY`; api `v60.0`.
- Properties: `apiVersion` (default `v60.0`), `clientId` (**required**, consumer key), `oauth.flow` (ENUM `client_credentials` (default), `password` (**deprecated**: Salesforce retires the username-password flow on 20 February 2027; still accepted, reported as a configuration warning and logged as a kernel WARN at each login), `jwt_bearer`; required), `securityToken` (SECRET, visible when flow = password), `oauth.privateKey` (CERTIFICATE, secret, visible when flow = jwt_bearer), `instanceUrl` (URL, advanced: overrides the `instance_url` returned by the token endpoint).
- Token (own implementation, cached **50 minutes**, synchronized):
  - `client_credentials` / `password`: `POST <baseUrl>/services/oauth2/token` form `grant_type=<flow>, client_id, client_secret=apiKey` (+ `username`, `password = password + securityToken` for `password`). Missing clientId or placeholder apiKey → "Salesforce: set the connected app consumer key (property clientId) and consumer secret (apiKey)"; password flow without credentials → "Salesforce: the password flow needs username and password"; failure → "Salesforce login failed (HTTP n): <error_description>".
  - `jwt_bearer`: delegated to `core.OAuth2.token(cfg, client, <baseUrl>/services/oauth2/token, audience = baseUrl)` (uses `oauth.*` properties; expiry from the token).
- `headers()`: common headers + `Authorization: Bearer <token>` + `Accept: application/json`. Because the declared descriptor has no `authType`, the connector is normally configured with `authType NONE`; with `BASIC`/`BEARER`/… the generic validator adds its own checks.
- Execution: a **401 clears the token and retries once**. `query` success with `totalSize` 0 → null. 201/204 → body or `{}`. Otherwise `bodyOrNull`.
- Operations: `get` (R, `sobject`, `id`) → `GET /sobjects/{sobject}/{id}`; `getByExternalId` (R, `sobject`, `externalIdField`, `value`); `query` (S, `soql`) → `GET /query?q=` (follows `nextRecordsUrl` and returns `records[]`; with an input `max` it stops once it holds `max + 1` records, so the engine sees a truncation, and `totalSize` stays the real total; a page that fails raises an error naming the page and the HTTP status instead of returning a short result, audit C-16); `create` (W, sobject) POST; `update` (W, sobject,id) PATCH; `upsert` (W, sobject, externalIdField, value) PATCH `/sobjects/{s}/{field}/{value}`; `describe` (R, sobject) → `/sobjects/{s}/describe`. `sobject` must match `[A-Za-z][A-Za-z0-9_]*` → "Salesforce: invalid sObject name '<n>'".
- `describeFields` (needs `sobject`): from `describe.fields` (name, label, type); prefix `records[0].` for `query`; write operations keep only `updateable` or `createable` fields.

#### 3.2.46 `sap-odata` — SAP (OData v2 / v4) (SapODataConnector)

- `baseUrl` = service root (e.g. `https://host/sap/opu/odata/sap/API_BUSINESS_PARTNER`). Category `BUSINESS_APPS`, auth `BASIC`, `BEARER_TOKEN`, `API_KEY_HEADER` (header `APIKey` for the SAP Business Accelerator Hub sandbox); capabilities SCHEMA_DISCOVERY, BULK_READ, BULK_WRITE; api `OData v2`, `OData v4`.
- Properties: `odata.version` (ENUM `2`/`4`, default `2`), `sap-client` (sent as header `sap-client`), `entitySet` (default entity set), `default.expand`, `max.rows` (INTEGER, default 500); code also reads `max.pages` (default 20, 1..100) and `batch.max.items` (default 50, ≤100).
- Headers: common auth + `Accept: application/json`, `sap-client`, `Cookie` (Gateway session cookies kept after a CSRF fetch). Every execution `requireSecret()`.
- Entity set: input `entitySet` or property; none → "SAP OData: no entity set (input 'entitySet' or connector property 'entitySet')"; must match `[A-Za-z_][A-Za-z0-9_]*`. Key predicate: a value containing `=` or starting with `'` is kept, otherwise quoted (`'1000001'`, quotes doubled).
- Operations:
  - `get` (R, entitySet, `key`) → `GET /{set}({key})` with `$select`/`$expand` (ODataSupport) → normalised entity.
  - `query` (S, entitySet; `$filter`, `$select`, `$expand`, `$orderby`, `$top` inputs through `ODataSupport.query`, `max` bounded by `max.rows` ≤10000; optional `deltaLink`) → pages followed (`__next` / `@odata.nextLink`) up to `max.pages` → `{results:[…], count}`.
  - `create` (W) → CSRF fetch then `POST /{set}` (deep insert when the payload carries collections); `update` (W, key) → `POST /{set}({key})` with `X-HTTP-Method: MERGE` (v2) or `PATCH` (v4); `upsert` (W, key) → GET the key first: success → update, else create. Empty payload → "SAP <op>: the outbound document is empty". 204 → `{}`. A CSRF rejection (403 with `x-csrf-token: Required`, or a body that mentions csrf) drops the token and the session cookies, fetches a new token and sends the write **once more**; a second rejection → "SAP OData: CSRF token rejected again after a fresh token was fetched". A 403 that is not about CSRF is not repeated. `$batch` with writes behaves the same.
  - `describe` (R) → returns null (the designer uses `describeFields`).
- CSRF: `GET /{set}?$top=1` with `X-CSRF-Token: Fetch`; token from `x-csrf-token`, cookies from `Set-Cookie`; cached per instance.
- Normalisation (ODataSupport, core): one entity flat; collections `{results, count}`; v2 `{results:[…]}` wrappers of expanded navigations become arrays; `__metadata`/`__deferred`/`@odata.*` dropped; `/Date(ms)/` → ISO timestamps.
- **Native batch** (`executeBatch`): validated; item count ≤ `batch.max.items`; a batch containing any `upsert` falls back to **sequential** execution; `get`, `query`, `create`, `update` supported ("SAP OData: operation '<op>' cannot be batched" otherwise); CSRF fetched when writes are present; v4 → JSON `$batch`, v2 → multipart `$batch` (boundary `batch_ebx`); byte limit enforced ("SAP OData batch exceeds byte limit N").
- `describeFields` (entity set resolvable, else empty): `GET /$metadata` (`Accept: application/xml`), properties and navigation properties (`navigation (array)`) of the entity type; prefix `results[0].` for `query`; failure → "SAP OData: $metadata -> HTTP n".

#### 3.2.47 `jde-ais` — JD Edwards EnterpriseOne (AIS) (JdeAisConnector)

- `baseUrl` = AIS root (`https://host:9080/jderest`). Category `BUSINESS_APPS`, auth `BASIC` (EnterpriseOne user/password exchanged for a session token).
- Properties: `ais.version` (default `v2`), `environment`, `role`, `device` (default `EBX` in code), `max.rows` (INTEGER, default 500), `strip.prefix` (BOOLEAN, default true).
- `headers()` replaced: `Accept` and `Content-Type: application/json`, `Authorization: Bearer <token>` when present.
- Session: username null or password placeholder → "JD Edwards: set the EnterpriseOne user name and password (authentication BASIC)"; token reused **20 minutes**; `POST /<version>/tokenrequest {username, password, deviceName, environment?, role?}` → `userInfo.token`; failure → "JD Edwards: token request failed (HTTP n) <message>". Every AIS call puts `token` in the body; on 401, 403 or a message containing "not authorized" the token is renewed and the call retried once.
- Operations (`max` input or `max.rows`):
  - `query` (S, `table`; optional `targetType` table|view, `columns` `AN8|ALPH` or comma list, `filter`) → `POST /<v>/dataservice {targetName, targetType, dataServiceType:BROWSE, maxPageSize, findOnEntry:TRUE, returnControlIDs, query?}` → `{results:[…], count, moreRecords?}` from `fs_*.data.gridData.rowset` (or `rowset`); `rowIndex` dropped, `F0101_` prefixes stripped when `strip.prefix`, text values trimmed; empty → null.
  - `get` (R, `table`, `keyColumn`, `value`) → query with `<keyColumn> EQUAL <value>`, max 1 → first row.
  - `orchestration` (W, `name`) → `POST /<v>/orchestrator/{name}` with the payload object (or, when no object payload, the inputs other than name/max); `orchestrationQuery` (S, `name`) → same with the inputs as body.
  - `form` (W): payload must be the form service request ("JD Edwards form: the outbound document must be the form service request") → `POST /<v>/formservice`.
- Filter grammar: clauses `COLUMN OPERATOR value` separated by `;`, operators `EQUAL`, `NOT_EQUAL`, `GREATER`, `GREATER_EQUAL`, `LESS`, `LESS_EQUAL`, `CONTAINS`→`STR_CONTAIN`, `STARTS_WITH`→`STR_START_WITH`, `ENDS_WITH`→`STR_END_WITH`, `BLANK`→`STR_BLANK`, `NOT_BLANK`→`STR_NOT_BLANK`, `BETWEEN`, `LIST` (values separated by `|`); MATCH_ALL, `autoFind`; values as `LITERAL`. Errors "JD Edwards filter: expected 'COLUMN OPERATOR value', got '<c>'" / "unknown operator". Names must match `[A-Za-z0-9_.]+` ("JD Edwards: invalid name '<v>'").
- `describeFields` (needs `table`): browses one row and lists its columns with the value as sample.

#### 3.2.48 Data platforms

#### 3.2.49 `bigquery` — Google BigQuery (BigQueryConnector)

- Base = (`baseUrl` or `https://bigquery.googleapis.com`) + `/bigquery/v2/projects/<project>`. Category `DATA_PLATFORMS`, auth `SERVICE_ACCOUNT` + `BEARER_TOKEN`, capabilities PREVIEW_RECORDS, SCHEMA_DISCOVERY, api "Google BigQuery REST API v2", pagination TOKEN 1000/10000 `pageToken`.
- Properties: `project` (**required**), `dataset` (**required** in the descriptor; default dataset), `location`, `max.rows` (default 1000 in code), `serviceAccountJson` (SECRET), `serviceAccountFile` (FILE). Token via `core.GoogleAuth.token(config, "https://www.googleapis.com/auth/bigquery")` (service account JSON/file or a bearer token in `apiKey`). A token failure while building headers is put in a header `X-Connector-Auth-Error` (the call then fails at the API); `run`/`insert` call the token first so the failure surfaces as an exception.
- Missing project → "BigQuery: set the property project". Table qualification: `project.dataset.table` kept; `dataset.table` → project added; bare name → needs `dataset` ("BigQuery: qualify the table as dataset.table or set the property dataset"); backticks removed then re-added.
- `query` (S, `sql` with `@name` parameters bound from inputs of the same name; values matching `-?[0-9]+` sent as INT64, others STRING, missing as NULL) → `POST /queries {query, useLegacySql:false, maxResults=max, timeoutMs = max(1000, readTimeoutMs − 1000), location?, parameterMode NAMED, queryParameters}` → `{rows, count, totalRows?, affectedRows?, truncated?}`; 0 rows → null. `jobComplete=false` → polled with `GET /queries/{jobId}` (`jobs.getQueryResults`, `location` of the job, `timeoutMs` ≤ 10 s per call) until complete or property `query.maxWaitSeconds` (default 120, 1..3600) → "BigQuery: the query did not complete within query.maxWaitSeconds (job <id>)". Further pages are read with `pageToken` up to `max` rows; rows left behind → `truncated: true`.
- Value conversion: INTEGER/INT64 → long; FLOAT/FLOAT64/NUMERIC/BIGNUMERIC → BigDecimal; BOOLEAN/BOOL → boolean; TIMESTAMP (epoch seconds) → ISO instant; JSON → parsed; RECORD/STRUCT → nested object; REPEATED → array; failures keep text.
- `get` (R, `table`, `key` sanitised to `[A-Za-z0-9_]`, `value`) → `SELECT * FROM <t> WHERE CAST(<key> AS STRING) = @value LIMIT 1` → `rows[0]`.
- `insert` (W, `table`) → `POST /datasets/{d}/tables/{t}/insertAll {rows:[{json: row}]}` (payload = one document, an array or `{rows:[…]}`); `insertErrors` → "BigQuery insertAll: <errors>"; → `{inserted}`.
- `upsert` (W, `table`, `key` comma list) → builds `MERGE <t> T USING (SELECT <literal> AS col, …) S ON T.k = S.k … WHEN MATCHED THEN UPDATE SET … WHEN NOT MATCHED THEN INSERT (…) VALUES (…)` with **inline literals** (strings quoted with `\\` and `\'` escaping, nested values as JSON text); payload object required ("BigQuery upsert: the outbound document is required"); returns the `run` document (`affectedRows`).
- `max` input: default and ceiling `max.rows` (default 1000, at most 100000); non-numeric → the default.
- Data-catalog operations `catalog`, `profile`, `sample` (S, required `source`) through `CatalogOperations` with `BigQueryDialect`: a catalog schema is a **dataset** (`PROJECT.DATASET` for another project; default the `dataset` property). With `location`, the region views (`` `project`.`region-<location>`.INFORMATION_SCHEMA.TABLES / COLUMNS ``) filtered on the datasets; without, one view per dataset joined with `UNION ALL`. Column descriptions come from `COLUMN_FIELD_PATHS`; row counts are not read. `profile` samples with `TABLESAMPLE SYSTEM (p PERCENT)` and `APPROX_COUNT_DISTINCT`; `usage` and `lineage` are not offered. `catalog` writes the read recipe (`query`, `itemsPath = rows`).
- `describeFields`: with `table` → table schema (`GET /datasets/{d}/tables/{t}`), prefix `rows[0].` except for `get`; with `sql` on `query` → executes the query with max 1 and flattens the first row.

#### 3.2.50 `databricks` — Databricks (SQL Statement Execution API) (DatabricksConnector)

- `baseUrl` = workspace URL. Category `DATA_PLATFORMS`, auth `BEARER_TOKEN` + `OAUTH2_CLIENT_CREDENTIALS`, capabilities DESCRIBE_OBJECT, SCHEMA_DISCOVERY, api "Databricks SQL Statement Execution API 2.0", pagination TOKEN 1000/10000 `next_chunk_internal_link`.
- Properties: `warehouse_id` (**required**), `auth` (ENUM `token` (code default) / `oauth`), `clientId` (service principal, required for oauth), `catalog`, `schema`, `wait` (seconds, default 30, clamped 5..50), `max.rows` (default 1000), `nested.function` (default `parse_json`; empty = nested values bound as JSON strings).
- `headers()` replaced: `Accept: application/json`, `Authorization: Bearer <apiKey or OAuth token>` (the `authType` field is ignored).
- Authentication: missing `warehouse_id` → "Databricks: set the property warehouse_id (SQL warehouse)"; placeholder apiKey → "Databricks: set apiKey (personal access token, or the service principal secret with auth=oauth)". OAuth M2M: `POST <baseUrl>/oidc/v1/token` Basic `clientId:apiKey`, form `grant_type=client_credentials&scope=all-apis`; expiry `expires_in − 60 s` (min 60; 3000 s default); missing clientId → "Databricks OAuth: set the property clientId (service principal application id)"; failure → "Databricks OAuth failed (HTTP n): …".
- Statement: `POST /api/2.0/sql/statements {warehouse_id, statement, wait_timeout "<5..50>s", on_wait_timeout CONTINUE, disposition INLINE, format JSON_ARRAY, catalog?, schema?, parameters:[{name, value, type STRING}]}`; while `PENDING`/`RUNNING` poll `GET /api/2.0/sql/statements/{id}` every **2 s** until `max(readTimeoutMs, 60000)` ms; non-2xx → "Databricks: HTTP n <message>"; final state ≠ SUCCEEDED → "Databricks: statement <state> <error>". Result `{columns:[{name,type}], rows:[{col: value}], count, totalRowCount?}`, following `next_chunk_internal_link` chunks until `max` rows. Type conversion: INT/LONG/SHORT/BYTE/DECIMAL/DOUBLE/FLOAT → BigDecimal; BOOLEAN; TIMESTAMP/TIMESTAMP_NTZ → ISO string cut to seconds; STRUCT/ARRAY/MAP/VARIANT → parsed JSON.
- Operations:
  - `query` (S, `sql` with `:b1..:bn`, inputs `b1..b50` — stops at the first missing index) and `execute` (W, same) → statement document.
  - `lookup` (R, `table`, `keyColumn`, `value`) → `SELECT * FROM t WHERE k = :b1 LIMIT 1` → first row or null.
  - `describe` (R, `table`) → `DESCRIBE TABLE`.
  - `merge` (W, `table`, `keyColumn` comma list) / `insert` (W, `table`): payload object non-empty ("Databricks <op>: the outbound document is empty"); top-level fields bound as `:b1..` markers (null → literal NULL; objects/arrays → `nested.function(:bn)`); `MERGE INTO t USING (SELECT :b1 AS c1, …) s ON … WHEN MATCHED THEN UPDATE SET … WHEN NOT MATCHED THEN INSERT … VALUES (s.…)`; `INSERT INTO t (…) SELECT …`.
  - Data-catalog: `catalog`, `profile`, `sample`, `usage`, `lineage` (S, input `source`) delegate to `core.CatalogOperations` with `DatabricksDialect(catalog)` and a runner limited to max(max, 10000) rows. Dialect: `<catalog>.information_schema.schemata/tables/columns` (`full_data_type`, comments), usage = distinct runs per `source_table_full_name` in `system.access.table_lineage` over 30 days (LIMIT 5000), lineage = `system.access.column_lineage` over 30 days (LIMIT 20000), names cut to `schema.table` inside the configured catalog (other catalogs ignored), `LENGTH`, `CAST AS DOUBLE`, `LIMIT n`. System tables need the system schema enabled and SELECT on it.
- Identifier sanitising keeps `[A-Za-z0-9_.\`]`.
- `describeFields` (needs `table`): DESCRIBE rows (`col_name`, `data_type`, partition/detail sections starting with `#` skipped), prefix `rows[0].` for `query`.

#### 3.2.51 AI

#### 3.2.52 `llm` — LLM: Claude, OpenAI, Gemini, Azure OpenAI, Mistral, Ollama (LlmConnector)

- Label "LLM (Claude, OpenAI, Gemini, Azure OpenAI, Mistral, Ollama)". Category `AI`. Auth `API_KEY_HEADER`, `BEARER_TOKEN`, `OAUTH2_CLIENT_CREDENTIALS`, `NONE`. Every call `requireSecret()`. All operations are READ (enrichment); trust recommended 30 (javadoc; seeding in core).
- Properties:

| Property | Type | Default (code) | Meaning |
|---|---|---|---|
| `provider` | ENUM `auto`,`anthropic`,`openai`,`google`,`azure`,`azure-ai`,`mistral`,`ollama` | `auto` | `auto` = from base URL |
| `model` | STRING | per provider (below) | model or Azure deployment name |
| `maxTokens` | INTEGER | 1024 | generated tokens |
| `temperature` | STRING | `0` | `none` or blank = not sent; parsed as double |
| `system` | STRING | `You are a precise data assistant.` | default instruction for `complete` |
| `language` | STRING | none | appends " Answer in <language>." to extract/classify/match prompts |
| `jsonMode` | STRING | `auto` | `true`/`false`; `auto` = on for google, mistral, ollama |
| `anthropic.version` | STRING | `2023-06-01` | `anthropic-version` header |
| `azure.apiVersion` | STRING | `2024-10-21` (azure), `2024-05-01-preview` (azure-ai) | `v1` = Azure OpenAI v1 API |
| `google.apiVersion` | STRING | `v1beta` | Gemini API |
| `google.project` | STRING | — | required for Vertex AI ("<id>: set the google.project property for Vertex AI") |
| `google.location` | STRING | `us-central1` | Vertex AI |
| `openai.maxTokensParam` | STRING | `max_tokens` | e.g. `max_completion_tokens` |
| `openai.reasoningEffort` | STRING | empty (not sent) | `reasoning_effort` for OpenAI-shaped requests (`none`, `low`, `medium`, `high`), sent by `complete`/`extract`/`classify`/`match` and passed by the MDM Factory to `com.on.ebx.agent.LlmClient`; added 2026-09-26 for gpt-6-luna, which refuses function tools unless it is `none` (the agent client also falls back to `none` once on its own when that refusal comes back, see `LlmClient`) |
| `openai.path` | STRING | `/v1/chat/completions` | OpenAI-compatible path |

- Provider auto-detection from `baseUrl` (lower-cased): `anthropic.com` → anthropic; `generativelanguage.googleapis.com` or `aiplatform.googleapis.com` → google; `services.ai.azure.com`, `models.inference.ai.azure.com`, `models.ai.azure.com` → azure-ai; `openai.azure.com`, `cognitiveservices.azure.com` → azure; `mistral.ai` → mistral; `:11434` or `ollama` → ollama; otherwise openai. Vertex AI = base URL containing `aiplatform.googleapis.com`.
- Default models (`defaultModel`): anthropic `claude-sonnet-5`; google `gemini-2.5-flash`; mistral `mistral-small-latest`; ollama `llama3.2`; openai, azure, azure-ai `gpt-4o-mini`.
- Authentication headers: common `AbstractRestConnector` auth, then: anthropic adds `anthropic-version`, and with `authType BEARER` moves the key to `x-api-key`; google (non-Vertex) with BEARER moves the key to `x-goog-api-key`; Vertex keeps `Authorization: Bearer` (OAuth token); Azure uses `api-key` via `API_KEY_HEADER` (header name in `apiKeyHeader`) or an Entra token (BEARER/OAUTH2); OpenAI/Mistral Bearer; Ollama `NONE` — but note `requireSecret` still refuses API_KEY_HEADER/BEARER with a placeholder key.
- Request shapes and endpoints:
  - anthropic: `POST <path("/v1/messages")>` `{model, max_tokens, temperature?, system, messages:[{role:user, content}]}`; answer `content[0].text`.
  - google: `{systemInstruction.parts[0].text, contents:[{role:user, parts:[{text}]}], generationConfig:{maxOutputTokens, temperature?, responseMimeType:"application/json" when JSON mode}}`; path `/<google.apiVersion>/models/<model>:generateContent` (a `models/` prefix is removed), Vertex `/v1/projects/<p>/locations/<l>/publishers/google/models/<model>:generateContent`; answer = concatenated `candidates[0].content.parts[].text` excluding parts flagged `thought`.
  - ollama: `POST /api/chat {model, stream:false, messages:[system,user], options:{num_predict, temperature?}, format:"json"?}`; answer `message.content`.
  - openai/mistral/azure/azure-ai: `{model, temperature?, <maxTokensParam>: n, reasoning_effort?, messages:[system,user], response_format:{type:"json_object"}?}`; azure path `/openai/deployments/<model>/chat/completions?api-version=<v>` or `/openai/v1/chat/completions` when `v1`; azure-ai `/models/chat/completions?api-version=<v>`; others `path(openai.path)`. Answer `choices[0].message.content`; an array of chunks (Mistral reasoning models) → concatenated `type:"text"` chunks.
  - `path()` avoids doubling a version segment already ending the base URL (`…/v1` + `/v1/messages` → `/messages`).
- Errors: HTTP 404 → "<id>: <provider> chat -> HTTP 404 (model '<m>', path <p>) <body ≤300>" (unknown model/deployment, Ollama model not pulled); empty reply → "<id>: empty answer"; no text → "<id>: unexpected answer <json ≤300>".
- `chatTurn(system, tools, conversation, maxTokens, json, readTimeoutMs)` (public, for the contract's `llm.chat`, 1.1.0):
  `requireSecret`; the format from `chatFormat(provider)` (anthropic → Anthropic Messages at `path("/v1/messages")`;
  openai, azure, azure-ai, mistral → OpenAI chat completions at the same path as `complete`; ollama → its OpenAI-compatible
  `path("/v1/chat/completions")`, not `/api/chat`; google → refused); the body built by `com.on.ebx.agent.LlmClient.request`
  (model, `max_tokens` or `openai.maxTokensParam`, `openai.reasoningEffort`, system — the connector's `system` when none —,
  tools, conversation; no `temperature`, as in the agent library), `response_format: json_object` when `json` and the
  `jsonMode` rule says so (OpenAI-shaped only); sent with the connector's own headers, client, outbound rules and the read
  timeout of the call. An HTTP 400 to a request with tools whose body names `reasoning_effort` and `none` is resent once
  with `none`, and the connector instance keeps sending `none` afterwards. Answer `ChatAnswer {turn (LlmClient.turnOf),
  provider, model, inputTokens, outputTokens, totalTokens}` (usage and model by `answerOf` in the wire format). Errors as
  `ask` (404 detail, empty answer, a reply without `content` / `choices[0].message` → "unexpected answer").
  `configuredMaxTokens(config)`: the `maxTokens` property, 1024 (`DEFAULT_MAX_TOKENS`) when unset or not a positive number.
- `ask(system, prompt, maxTokens, json, readTimeoutMs)` (public, for the in-JVM contract §4.5.4): `requireSecret`, an empty prompt refused (`<id>: the prompt is empty`), the connector's `system` when none is given, then the same request as `complete` with `maxTokens` (when not null) instead of the property, `json` as the "JSON expected" flag of `jsonMode`, and the read timeout of this call (0 = the connector's). Answers `Answer {text, provider, model, inputTokens, outputTokens, totalTokens, truncated}` (`answerOf`, per provider: anthropic `usage.input_tokens/output_tokens`, `model`, `stop_reason`; google `usageMetadata.promptTokenCount/candidatesTokenCount/totalTokenCount`, `modelVersion`, `candidates[0].finishReason`; ollama `prompt_eval_count/eval_count`, `model`, `done_reason`; others `usage.prompt_tokens/completion_tokens/total_tokens`, `model`, `choices[0].finish_reason`). `complete`, `extract`, `classify` and `match` are unchanged (same path, `maxTokens` from the property, the connector's read timeout). `secretProblem()` = the `requireSecret` message or null.
- Operations:
  - `complete` (R, `prompt`; optional `system`) → `{text}`.
  - `extract` (R, `text`, `fields` = `name:description, name2:…`) → the JSON object answered (keys as requested, null when absent).
  - `classify` (R, `text`, `categories` = `A=description;B=…`) → `{category, confidence 0..1, reason}`.
  - `match` (R, `a`, `b`) → `{same, confidence, reason}`.
  - JSON answers are extracted tolerantly (code fences removed, first `{` to last `}`); not an object → `{text, error:"the model did not answer with a JSON object"}` (no exception).
- `describeFields`: extract → one string field per declared name (label = description), else `text`; classify → category/confidence/reason; match → same/confidence/reason; complete → text.
- Tag-review/LLM usage by the data catalog module reads this connector as data (outside this module).

#### 3.2.53 File connectors (AbstractFileConnector + FileFormats)

Common to `s3`, `azure-blob`, `gcs`, `sftp`, and `sample-file` (its own single-entry, read-only variant; see the dedicated note at the end of this section).

- Operations:
  - `read` (S, `path`; optional `format`, `separator`, `max`, `max.bytes`) → `{rows:[…], count, path, truncated, available}`; missing file or 0 rows → null. A feed uses `itemsPath = rows`. `available` (every format, added 2026-09-28: csv already via `Csv.parse`, the others already had `truncated`) is the file's real record/element count — free to compute, since the file is already walked end to end to find boundaries — and `truncated = available > count`; read by `ConnectorService.importDocument`, which turns a truncated feed read into a `SKIPPED` "Limit reached" notice instead of a silent, apparently-complete `OK`.
  - `write` (W, `path`; optional `format`, `mode` = `overwrite` (default) | `append`, `separator`, `max.bytes`): payload = one document, a JSON array, or `{rows:[…]}`; nothing → "Nothing to write to <path>"; more than 100000 rows → "Write exceeds the row limit of 100000"; append on `json`/`xml` → "Append is supported only for CSV and NDJSON documents"; append reads the existing file, writes a CSV header only when the file was empty, adds CRLF if the previous content did not end with a newline → `{path, rows, bytes, mode}`.
  - `list` (S; optional `prefix`, `max` default 1000) → `{results:[{path, name, size, modified}], count}`; empty → null.
  - `catalog` (S, `source`; optional `prefix`, `max` = files listed (default 200), `columnsMax` = files whose columns are actually inferred via a real bounded `read` (default 50; files beyond this count still appear in `tables` with `columns: []`, not dropped)) → `{source, scannedAt, count, tables:[{source, assetKey, qualifiedName, name, kind="FILE", scannedAt, columns:[{name, columnKey, position, dataType, nativeType, nullable, primaryKey, scannedAt}]}]}`, the same document shape `JdbcConnector.catalog` produces (`assetKey = source:path`, `columnKey = assetKey.column`); `rowCount`/`schema`/`url`/`remarks` are never sent (nothing meaningful to report for a file). Columns come from `Json.flatten` on `rows[0]` of a one-row `read` of that file: `dataType` is the coarse taxonomy (`string`→`STRING`, `number`→`NUMBER`, `boolean`→`BOOLEAN`, anything else → `STRING`), `nativeType` is `flatten`'s own raw type string, `nullable`/`primaryKey` default to `true`/`false` (unknown from one sampled row). A file whose column-inference read fails (malformed content, etc.) does not abort the scan: that table's `columns` stays empty and the failure is recorded on it as `columnsError`, mirroring `JdbcConnector.catalog`'s own `rowCountError`.
- Connector properties read (not declared in the descriptors): `root` (prefix of every path), `format`, `separator`, `max.bytes`, `max.rows` (default 10000), `write.mode` (default `overwrite`).
- Path safety (`fullPath`): NUL or backslash → "Invalid connector file path"; a `..` segment → "Parent traversal is not allowed in connector file paths"; with `root`: a path equal to or under root is kept, an absolute path outside → "Absolute path is outside the connector root", otherwise `root/` is prefixed. (These are `IllegalArgumentException`s, not `ConnectorException`s.)
- `testConnection`: `listEntries(root, 1)` → "Connected; read-only metadata listing succeeded" (no content read, no write).
- `preview`: only the `list` operation → bounded metadata rows; any other → "Safe file content preview is unavailable; use the bounded list preview".
- `describeFields`: `list` → results[0].path/name/size/modified; otherwise reads the file with `max=1` and flattens `rows[0]` (200 fields).
- `FileFormats` constants: `DEFAULT_MAX_BYTES` = 10 MiB, `ABSOLUTE_MAX_BYTES` = 100 MiB, `ABSOLUTE_MAX_ROWS` = 100000.
  - Format from input/property, else extension: `.csv`, `.txt`, `.tsv` → csv; `.jsonl`, `.ndjson` → ndjson; `.xml` → xml; `.xlsx`, `.xls` → excel; `.parquet` → parquet; else json.
  - Separator: `tab` or `\t` → tab; else first character. A configured `separator` property or input always wins. Otherwise (2026-10-17) it is DETECTED when a file is read (`read`, streamed read, catalog column inference, `append`): the header and up to five following records are scanned (first 64 KB), counting `;` `,` tab and `|` outside double quotes; a candidate qualifies when every sampled record has the same count above zero; `;` wins a tie, then the highest count, then the order `;` `,` tab `|`; with no consistent candidate the one most frequent in the header is taken. Nothing detectable (a single column) → the old default: tab for `.tsv`, `;` otherwise.
  - JSON envelope (2026-10-17): an object whose `rows`/`items`/`data`/`results`/`value`/`records` is an array is unwrapped (two of them → "Ambiguous JSON row collections"). Otherwise an object that holds exactly ONE non-empty array of objects, all its other properties being scalars named like pagination/status metadata (`total`, `count`, `page`, `pageSize`, `offset`, `limit`, `next`, `status`, `message`…; e.g. `{"customers":[…160 objects…]}`), is unwrapped too, in `read` and therefore in catalog column inference and `describeFields`. Any other object (several arrays, an array next to business fields, an array of scalars) stays ONE row, as before.
  - Catalog (2026-10-17): a file `catalog` table always carries `columnsComplete`: `true` when its columns were really re-read in full (a row was read, no `columnsError`, the 200-field cap not reached), `false` for an asset beyond `columnsMax`, in error or empty (sent explicitly so that the catalog's scan flow, which writes it ALWAYS, resets an old `true`). A consumer may treat a column missing from a `true` table as gone (the data catalog marks `Column.goneSince`, see its REFERENCE, "Gone columns"); for any other table it must leave the existing columns alone. Each returned column carries `scannedAt` = the scan time, the only source of the catalog's `Column.lastSeen`; columns the scan does not return are simply absent (Connectors upserts and never deletes or touches them).
  - `describeFields` entries are `(path, label, type, sample)`: `type` is the JSON type (`string`, `number`…) and `sample` the first value (they were swapped before 2026-10-17, also in the webhook and BigQuery connectors).
  - Parsing: csv via `core.Csv` (header line); `jsonl`/`ndjson` one object or array per non-empty line ("Invalid ndjson content at line n: expected a JSON object or array"); xml: children of the root become rows when the root has >1 child elements of the same name or the root is `<rows>`, otherwise the root is one row; json: an array, or the first array under `rows`, `items`, `data`, `results`, `value`, `records`, or the object itself; a UTF-8 BOM is removed. Other errors → "Invalid <format> content: …".
  - Serialising: csv with header from the first row and CRLF lines; ndjson one JSON per line; xml `<?xml …?><rows><row>…</row></rows>` (invalid element names → `field`, arrays → `item`); json = the single document or an array.
  - Content types: `text/csv; charset=UTF-8`, `application/x-ndjson`, `application/xml; charset=UTF-8`, `application/json`.
  - `max.bytes` must be 1..104857600 ("max.bytes must be between 1 and 104857600" / "max.bytes must be an integer"); size exceeded → "<File|Existing file|Generated file|Input document> exceeds the configured max.bytes limit of N"; row limit < 1 → "Row limit must be positive".
  - Excel and Parquet: a `DocumentCodecProvider` (ServiceLoader, thread context classloader) handling the canonical format (`excel`/`xls` → `xlsx`) is used when present; several providers → "Multiple document codec providers claim format '<f>'"; invalid provider result → "…returned an invalid result… rows must be an array" / "exceeded the configured row limit" / "returned no content" / "returned no content type". Without a provider: "Excel format is unavailable: install a DocumentCodecProvider backed by Apache POI; use CSV, JSON, NDJSON or XML" (same for Parquet with Apache Parquet); any other format → "Unsupported document format '<f>'". None is packaged.
- Binary safety note: S3, Azure Blob and GCS read the response as a **string** (`getRawBody().getBytes(UTF-8)`), so binary objects are not preserved byte-for-byte; SFTP reads real bytes.
- `sample-file` (`SampleFileConnector`, category `CLOUD_STORAGE`, auth `NONE`): the one virtual object is the connector's manually uploaded sample (`Connector.sampleData`, an RFC 2397 data URL `data:<mime>;base64,<payload>` edited by `UploadedSampleFileWidgetFactory`'s record-form widget). Since the sample-file-name lot (2026-09-23) the stored data URL may carry the uploaded file's original name as an extra `;name=<percent-encoded name>` parameter, written by the widget's `encodeURIComponent(f.name)`; the connector decodes it, keeps only the base name (strips a `/` or `\` path prefix), trims it, and refuses a name that contains a control character. An absent or refused name falls back to the previous generic `sample.<ext>` default (derived from the MIME) — every value stored before this lot, unchanged. When a name is kept, `list()`'s single entry, `read()`'s `path` input, and the `catalog` operation's `assetKey`/`qualifiedName`/`name` are all that file's own name with its extension stripped (`Personnes_SAP_Sample.json` → `Personnes_SAP_Sample`), so a catalog scan of a sample-file source names its asset after the uploaded file instead of the generic `sample` every sample-file source used to share. The **format** (csv/tsv/json/ndjson) is always decided by the stored MIME, never by that file name's own extension: `configure()` pins an explicit `format` connector property (and, for `text/tab-separated-values`, a default `separator=tab` when none is already configured) from the MIME, so the extension-based fallback documented above for `FileFormats` is never actually reached for this type. `write` is always refused ("The sample file connector is read-only…"); `testConnection` reports the sample's display name and byte size, or that none has been uploaded yet. Since 2026-10-19 the connector also offers `sample` (inbound), with the contract of `JdbcConnector.sample` so the data catalogue declares its `Catalog sample: <SOURCE>` flow and classifies a file source's columns: inputs `source` (required), `tables` (`*`, empty, or the one table's own name, in a comma list; any other name answers nothing), `limit` (rows read, default 50, bounded 1..500); answer `{source, sampledAt, count, columns:[{source, assetKey, qualifiedName, columnKey, name, dataType, sampledAt, sampleSize, values, valuesText}]}` for the columns the `catalog` operation types as text (the flattened first row; a number or a boolean in the first row is not text), distinct non-blank values in order of first appearance, each cut at 200 characters, `assetKey`/`qualifiedName`/`columnKey` identical to the `catalog` operation's. The shared data-URL grammar is parsed by `core.SampleDataUrl` (`mime()`, `name()`, `payload()`), used by both the connector and the upload widget so both sides of the field agree on one tolerant parser.

#### 3.2.54 `s3` — Amazon S3 (files) (S3Connector)

- S3 and S3-compatible stores (MinIO, Ceph) with Signature V4 (`core.AwsSigV4`, JDK only). Category `CLOUD_STORAGE`, auth advertised `API_KEY_HEADER`; runtime credentials `username` = access key id, `password` = secret access key (missing → "S3: set the access key id as user name and the secret access key as password"). Capabilities DISCOVER_OBJECTS, PREVIEW_RECORDS; api "Amazon S3 REST / Signature Version 4"; pagination TOKEN 1000/1000 `NextContinuationToken` (not followed).
- Properties: `bucket` (required; missing → "S3: set the property bucket"), `endpoint` (URL; custom, path-style), `region` (default `us-east-1`), `sessionToken` (SECRET); `root` etc. as above.
- Bucket URL: `endpoint` property, else `baseUrl` when set (path-style: `/<bucket>` appended unless the URL already contains the bucket name), else `https://<bucket>.s3.<region>.amazonaws.com`. Key segments encoded with `AwsSigV4.encode`.
- read → GET object (404 → null); write → PUT object with content type; list → `GET ?list-type=2&max-keys=min(1000,max)&prefix=` parsed from XML `<Contents>` (keys ending with `/` skipped); errors "S3 GET|PUT <path> failed (HTTP n): <Message>" / "S3 list failed …".
- Note: the generic validator requires `baseUrl` even though the code defaults it (see Discrepancies).

#### 3.2.55 `azure-blob` — Azure Blob Storage (files) (AzureBlobConnector)

- `baseUrl` = `https://<account>.blob.core.windows.net`. Category `CLOUD_STORAGE`, auth `API_KEY_HEADER` (SAS) + `BEARER_TOKEN`; api "Azure Blob REST; x-ms-version configurable"; pagination TOKEN 1000/5000 `NextMarker` (not followed).
- Properties: `container` (required; "Azure Blob: set the property container"), `apiVersion` (default `2021-08-06`, header `x-ms-version`).
- `apiKey` = SAS token (query string with or without leading `?`), appended to every URL; with `authType BEARER` the key is sent as `Authorization: Bearer` instead. Placeholder → "Azure Blob: set the SAS token (or a bearer token) in apiKey".
- Headers `Accept: application/xml, */*`; PUT adds `x-ms-blob-type: BlockBlob` and `Content-Type` (default `application/octet-stream`).
- list → `GET <container>?restype=container&comp=list&maxresults=min(5000,max)&prefix=` parsed from `<Blob>` (Name, Content-Length, Last-Modified).
- Note: because `authType` API_KEY_HEADER/BEARER is expected, the generic validator requires a non-placeholder `apiKey`; with `authType NONE` the SAS still works.

#### 3.2.56 `gcs` — Google Cloud Storage (files) (GcsConnector)

- JSON API v1. Base = `baseUrl` or `https://storage.googleapis.com`. Category `CLOUD_STORAGE`, auth `SERVICE_ACCOUNT` + `BEARER_TOKEN`; capabilities DISCOVER_OBJECTS, PREVIEW_RECORDS; pagination TOKEN 1000/1000 `nextPageToken` (not followed).
- Properties: `bucket` (required; "GCS: set the property bucket"), `serviceAccountJson` (SECRET), `serviceAccountFile` (FILE). Token `GoogleAuth.token(config, "https://www.googleapis.com/auth/devstorage.read_write")`.
- read → `GET /storage/v1/b/<bucket>/o/<object>?alt=media` (404 → null); write → `POST /upload/storage/v1/b/<bucket>/o?uploadType=media&name=<object>`; list → `GET /storage/v1/b/<bucket>/o?maxResults=min(1000,max)&fields=items(name,size,updated),nextPageToken&prefix=` (names ending with `/` skipped). Errors "GCS GET <p> failed (HTTP n): …", "GCS upload …", "GCS list failed …".

#### 3.2.57 `sftp` — SFTP (files) (SftpConnector)

- JSch (`com.jcraft.jsch.*`, packaged `jsch.jar`); registered **quietly** (absent library → type not offered). Category `CLOUD_STORAGE`, auth `BASIC` + `SSH_KEY`; dependency "JSch-compatible SSH client"; pagination OFFSET 1000/10000.
- `baseUrl` = `sftp://host:22` (a value without `://` gets `sftp://`; port default 22).
- Properties: `knownHosts` (FILE path of a known_hosts file on the EBX server), `hostKey` (STRING: the public key(s) of the server as `ssh-keyscan <host>` prints them, `[host] <type> <base64>`, one or several; only the base64 blobs are read), `privateKey` (FILE path of an OpenSSH key on the EBX server; `password` used as passphrase when set), `strictHostKey` (BOOLEAN; default true in code).
- Session: username required ("SFTP: set the user name"); without `privateKey` a non-placeholder password is required ("SFTP: set the password or the property privateKey"); host key (`SftpConnector.applyHostKeyPolicy`, before any connection; blank and placeholder values count as not set): `strictHostKey=false` → `StrictHostKeyChecking=no`, kernel warning once per connector and JVM (`[connectors] SFTP connector '<id>': strictHostKey=false, the key of the server is NOT verified...`); otherwise, neither `knownHosts` nor `hostKey` → refusal `SFTP: the key of the server cannot be verified. Set the property knownHosts (...) or hostKey (...). strictHostKey=false connects without verification: for a test server only`; `hostKey` set → `StrictHostKeyChecking=yes` against an in-memory repository that accepts exactly the pinned key blobs (byte comparison, whatever the host name; `knownHosts` is then not consulted and never written; no key in the value → `SFTP: the property hostKey holds no public key...`); else `knownHosts` → the file must exist and be readable (`SFTP: the known_hosts file of the property knownHosts does not exist or cannot be read on the EBX server`), `StrictHostKeyChecking=yes`. A server whose key is not the expected one is refused by JSch (`SFTP: HostKey has been changed: <host>` / `SFTP: reject HostKey: <host>`); `PreferredAuthentications` = `publickey` or `password,keyboard-interactive`; session timeout = readTimeoutMs, connect timeout = connectTimeoutMs. One session and channel per operation. Errors "SFTP: <msg>".
- read → `get` (no such file → null); write → creates missing parent directories recursively, `put` with OVERWRITE; list → `ls` of the prefix directory (or of its parent, filtering by file-name prefix), directories skipped, `modified` = ISO instant of mtime.

#### 3.2.58 Messaging (continued)

#### 3.2.59 `kafka` — Apache Kafka (KafkaConnector + KafkaCatalog)

- Uses `org.apache.kafka:kafka-clients` directly (not by reflection); registered **quietly** — absent jar → type not offered. Category `MESSAGING`, auth `NONE`, `BASIC`, `CUSTOM`; capabilities STREAMING, INCREMENTAL_READ; pagination OFFSET 100/10000 `offset`. The thread context classloader is switched to the module's classloader around every Kafka call.
- `baseUrl` = bootstrap servers `host:9092,host2:9092` (a `kafka://` prefix is removed; the §1.3 validator accepts `host:port` lists). `client.id` = `ebx-connectors-<id>`.
- Properties: `topic` (default topic), `key.field` (JSON path of the message key in the document), `envelope` (BOOLEAN, default false), `acks` (default `all`), `group.id` (default `ebx-connectors-<id>`), `auto.offset.reset` (ENUM `earliest`/`latest`/`none`; default `earliest`), `max.messages` (default 100), `poll.ms` (default 3000), `dead.letter.topic` (unset: a message that cannot be written is held back, not moved), `security.protocol`, `sasl.mechanism` (default `PLAIN`), `kafka.*` (a property starting with `kafka.` is passed to the clients with the prefix removed only when it is on the allow-list of client settings (`acks`, `linger.ms`, `compression.type`, `security.protocol`, the `ssl.*` family...); `bootstrap.servers`, `sasl.jaas.config`, the OAuth endpoint URLs and every key ending `.class` or `.classes` are always refused, any other key is refused with a message naming JVM property `ebx.connectors.kafka.extraAllowedKeys`, and `ssl.truststore.location` / `ssl.keystore.location` must lie under the folder named by `ebx.connectors.kafka.keystoreDir`; audit C2-01).
- SASL: with `authType BASIC`, a username and a non-placeholder password → `security.protocol` (default `SASL_SSL`), `sasl.mechanism`, `sasl.jaas.config` with `ScramLoginModule` for `SCRAM-SHA-256`/`SCRAM-SHA-512`, else `PlainLoginModule` (quotes escaped). Without credentials `security.protocol` is set only when the property exists.
- `publish` (W, no required input): topic = input `topic` or property (none → "Kafka: no topic (input 'topic' or connector property 'topic')"); key = input `key`, else the `key.field` path of the payload, else no key; `envelope=true` wraps `{table: String.valueOf(input table), key, operation (input, default "upsert"), timestamp (ms), data}`. Producer cached per instance (re-created after `configure` or a failure): string serializers, `acks`, `max.block.ms` = connectTimeoutMs, `delivery.timeout.ms` = max(readTimeoutMs, 30000), `request.timeout.ms` = min(readTimeoutMs, 30000); send awaited readTimeoutMs → `{topic, partition, offset, key}`. Failure → "Kafka publish to <topic> failed: …".
- `consume` (S) / `peek` (R), no required input: `max` input or `max.messages` (a feed also caps it at its own maximum); `max.poll.records` = max clamped 1..500; group `group.id` (+ `-peek` suffix for peek); auto-commit off; `auto.offset.reset` = property for consume, **always `earliest`** for peek. Subscribes and polls `poll.ms` until `max` messages (valid or not), a deadline of max(3 × poll.ms, readTimeoutMs), or 2 consecutive empty polls; a poll that returns more than `max` leaves the rest (result `moreAvailable: true`). A value that is not JSON goes to `invalidMessages` `{topic, partition, offset, reason:"Payload is not valid JSON"}` (never exposed as a mapping payload, counted towards `max`); a null value (tombstone) is skipped and counted (`tombstonesSkipped`) and never blocks. Result `{topic, count, messages:[{topic, partition, offset, timestamp, key, value}], invalidCount?, invalidMessages?, tombstonesSkipped?, moreAvailable?, notices?}`; null when nothing was polled. Failure → "Kafka consume from <topic> failed: …".
  **Offsets (audit C-06).** Offsets are committed explicitly (`commitSync(Map)`), never the consumer's position. A **feed** (`readRows`) commits, per partition, up to and excluding the first message that EBX has not written, and only once the engine reports a batch written (`KafkaConsumeLedger`, `KafkaConsumption`); a message that failed, that was not delivered (stop of the run, circuit breaker, cancellation, resume skip), that lies beyond `max`, or that is not valid JSON therefore stays on the topic and, without a dead-letter topic, holds its partition: the next run delivers it and everything after it on that partition again (the feed is an upsert, so replays are idempotent). A partition held at a message is told in the run's results (`Partition p of t stays at offset o`). Property `dead.letter.topic` (default unset): a message that is not JSON or that EBX refused is sent there (original key and value, headers `ebx.source.topic`, `ebx.source.partition`, `ebx.source.offset`, `ebx.reason`) and then counts as handled; when the send fails the message is held back as without the property; it must differ from the topic that is read. A feed's `itemsPath` that is not the message list (`messages[*].value` or `messages`) cannot be matched to messages, so the run is settled as a whole: committed only when every message was delivered and none failed. A dry run, and any sink that cannot confirm writes, commits nothing and sends nothing to the dead-letter topic. A direct `consume` (`connectors_call`, designer) returns the messages and commits exactly the ones it returns (not the position), with the same rule for invalid messages (result `notices`). A refused offset commit is told in the results and never fails the run; the messages are delivered again. Not verified against a real broker: group eviction (a long run polls with every partition paused between batches to stay in the group), rebalances.
- `cdc` (S, Debezium change data capture, see `CDC.md`): a direct call previews the next batch from input `checkpoint` as `{summary, upserts, deletes, candidateCheckpoint}` (no commit, nothing saved). Run as an inbound feed (`ConnectorService.runCdc`), it polls from the mapping's `SynchronizationCheckpoint` (`VENDOR_CURSOR`) with no broker commit (group `<group.id>-cdc-no-commit`), collapses events per key (`CdcApplyPlan`: last event wins, tombstones skipped), upserts `r`/`c`/`u` through the feed rules and `keyPredicate`, applies `d` per input `deleteMode` (`delete` default, `flag` with `deleteFlagField` / `deleteFlagValue` = `true`, `ignore`), and saves the checkpoint only when every row and delete succeeded and the run is not a simulation. `maxEvents` 500 (1..10000), `maxBytes` 8 MB (1024..16 MB).
- `testConnection`: `AdminClient.listTopics()` within connectTimeoutMs → "Connected to Kafka; read-only metadata returned N topic(s)" or a failure result "Kafka metadata check failed: …" (no exception).
- Data-catalog operations `catalog`, `profile`, `sample` (S, required `source`) via `KafkaCatalog` over a read-only `CatalogBroker` (timeout = max(5000, readTimeoutMs)): topics from `AdminClient` (sorted); `retained` = Σ(end − beginning offsets); latest messages = a consumer **without group** assigns every partition, seeks to `end − ceil(max/partitions)`, polls 500 ms until every partition reaches its end snapshot, the deadline, or 4 idle polls; records published meanwhile are ignored; sorted newest first by timestamp and cut to `max`. Nothing committed, no consumer group created.
  - Topic selection: `exclude` regex full-match on the topic (default `DEFAULT_EXCLUDE` = `__.*|_schemas|_confluent.*|connect-(configs|offsets|status)`); `schemas` = comma list of names or `*` globs (empty or `*` = all); `tables` (profile/sample only) = exact topic names, case-insensitive.
  - Records: `@key` (message key), `@timestamp` (ISO instant), the payload's fields when it is a JSON object, otherwise the payload under `@value` (text when not JSON).
  - Inference: nested objects flattened with `/`; arrays and objects `JSON`; booleans `BOOLEAN`; integral `INTEGER`, other numbers `DECIMAL`; texts `DATE` (`yyyy-MM-dd`), `TIMESTAMP` (ISO date-time pattern) or `STRING`; merges INTEGER+DECIMAL → DECIMAL, DATE+TIMESTAMP → TIMESTAMP, other mixes → STRING; only nulls → STRING. At most `MAX_FIELDS` = 500 fields per topic. `nullable` when fewer messages carry a non-empty value than were read; `nativeType` = the JSON kinds seen joined by ` | ` (`message key`, `message timestamp` for the two technical fields).
  - `catalog` (`sampleSize` default 1000, bounded 1..10000; `rowCounts` default true) → `{source, scannedAt, tables:[{source, assetKey = source:topic, qualifiedName = name = topic, kind TOPIC, remarks, rowCount?, url = kafka://<first bootstrap server>/<topic>, scannedAt, columns:[{columnKey, name, position, dataType, nativeType, nullable, primaryKey:false, scannedAt}]}], count, errorCount?, errors?}`.
  - `profile` (`sampleSize` default 1000, bounded **3**..10000) → `RecordProfiler.profile` per field with the retained count (−1 when `rowCounts=false`); topics without messages skipped.
  - `sample` (`limit` default 50, bounded 3..500) → first 200 STRING fields, `RecordProfiler.distinctValues(…, 200)`, `values[]`, `valuesText`.
  - A topic failing is reported in `errors[{topic, message}]`; if nothing could be produced and there are errors → "Kafka: no topic could be read (n); first: <topic>: <message>"; nothing at all → null.
- `describeFields`: consume/peek → messages[0].topic/key/offset/timestamp/value; catalog/profile/sample → document descriptions.

#### 3.2.60 Data platforms (continued)

#### 3.2.61 `snowflake` — Snowflake (SQL REST API) (SnowflakeConnector)

- `baseUrl` = `https://<account>.snowflakecomputing.com`; calls `POST /api/v2/statements`, no JDBC driver. Category `DATA_PLATFORMS`, auth `BEARER_TOKEN`, `OAUTH2_CLIENT_CREDENTIALS`, `CERTIFICATE`; capabilities DESCRIBE_OBJECT, SCHEMA_DISCOVERY; api "Snowflake SQL API v2"; pagination TOKEN 1000/10000 `partitionInfo`.
- Declared properties: `account`, `auth` (ENUM `token` (code default) / `keypair`), `max.rows` (default 1000), `timeout` (seconds, default 60), `tokenType` (ENUM `PAT` (default) / `OAUTH`). Also read but not declared: `warehouse`, `database`, `schema`, `role`.
- `headers()` replaced (the `authType` field is not used): `Accept: application/json`, `User-Agent: EBX-Connectors/1.0`, `Authorization: Bearer <apiKey>` + `X-Snowflake-Authorization-Token-Type: PROGRAMMATIC_ACCESS_TOKEN` (or `OAUTH`); key-pair: `Authorization: Bearer <JWT>` + `KEYPAIR_JWT`.
- Key pair: `username` = Snowflake user, `password` = **unencrypted PKCS#8 RSA private key (PEM)**, property `account`; JWT RS256 with `iss = ACCOUNT.USER.SHA256:<base64 SHA-256 of the public key DER>`, `sub = ACCOUNT.USER`, `exp = iat + 3540 s`; account upper-cased and cut at the first dot; regenerated after **50 minutes**. Missing pieces → "Snowflake key-pair: property account, username and the private key (password) are required"; other failures → "Snowflake key-pair: cannot build the JWT: …". Token mode with a placeholder apiKey → "Snowflake: set the token in apiKey (PAT or OAuth) or use key-pair authentication (property auth=keypair)".
- Statement body: `statement`, `timeout`, `warehouse`/`database`/`schema`/`role` when set, output parameters `DATE_OUTPUT_FORMAT=YYYY-MM-DD`, `TIMESTAMP*_OUTPUT_FORMAT=YYYY-MM-DD"T"HH24:MI:SS`, `bindings {"1":{type:TEXT, value}, …}`. HTTP 202 → poll `GET /api/v2/statements/{handle}` every **1.5 s** until readTimeoutMs; non-2xx → "Snowflake: HTTP n <message>"; still 202 when the poll loop gives up (deadline, or a response with no `statementHandle`) → "Snowflake: statement still running after Nms (read timeout)…" (fixed 2026-09-28 — before, a still-running statement fell through to `collect()` and came back looking like success). Result `{columns:[{name,type}], rows:[{COL: value}], count, truncated, available?, message?}`; further partitions fetched with `?partition=p` until `max` rows — a partition fetch failure now raises `ConnectorException("Snowflake: partition p of N failed with HTTP …")` instead of silently keeping whatever had been collected so far (fixed 2026-09-28); `truncated` is true when a page held more rows than fit under `max` or a partition was left unfetched because `max` was already reached; `available` is `resultSetMetaData.numRows` when the API reports it (the whole result set's total, not just this call's page). Conversion: `fixed`/`real` → BigDecimal; `boolean` (`true`/`1`); `date` epoch days → ISO date; `timestamp*` epoch seconds → ISO local date-time (UTC); `variant`/`object`/`array` → parsed JSON; other text unchanged.
- Operations (`max` input or `max.rows`):
  - `query` (S, `sql`) and `execute` (W, `sql`): positional `?` bindings from inputs `b1..b50` (stop at the first missing index).
  - `lookup` (R, `table`, `keyColumn`, `value`) → `SELECT * FROM t WHERE k = ? LIMIT 1` → first row or null.
  - `describe` (R, `table`) → `DESCRIBE TABLE`.
  - `merge` (W, `table`, `keyColumn` comma list) / `insert` (W, `table`): payload object non-empty ("Snowflake <op>: the outbound document is empty"); scalars bound as `?`, objects/arrays as `PARSE_JSON(?)` (VARIANT), nulls as `NULL`; `MERGE INTO … USING (SELECT ? AS c …) s ON … WHEN MATCHED THEN UPDATE SET … WHEN NOT MATCHED THEN INSERT …`.
  - Data-catalog `catalog`, `profile`, `sample`, `usage`, `lineage` (S, `source`) → `core.CatalogOperations` with `SnowflakeDialect(database)`: `INFORMATION_SCHEMA.SCHEMATA/TABLES (ROW_COUNT, COMMENT)/COLUMNS` of the current database; usage = distinct `query_id` per `base_objects_accessed` table in `SNOWFLAKE.ACCOUNT_USAGE.ACCESS_HISTORY` over 30 days (LIMIT 5000); lineage = `objects_modified` → `columns` → `directSources` over 30 days (LIMIT 20000); full names cut to `SCHEMA.TABLE` in the configured `database` (other databases ignored). ACCOUNT_USAGE needs IMPORTED PRIVILEGES on the SNOWFLAKE database and lags up to 3 hours. Runner limited to max(max, 10000) rows.
- Identifier sanitising keeps `[A-Za-z0-9_."$]`.
- `describeFields` (needs `table`): DESCRIBE rows (`name`, `type`), prefix `rows[0].` for `query`.

#### 3.2.62 `powerbi` — Microsoft Power BI (REST + Scanner API) (PowerBiConnector + core PowerBiScan)

- Constants `DEFAULT_BASE_URL = https://api.powerbi.com/v1.0/myorg` (used when `baseUrl` is blank), `SCOPE = https://analysis.windows.net/powerbi/api/.default`. Category `DATA_PLATFORMS`, auth `OAUTH2_CLIENT_CREDENTIALS` + `BEARER_TOKEN`; capabilities BULK_READ, DESCRIBE_OBJECT; api "Power BI REST API v1.0". All operations INBOUND.
- `configure` (only with `authType OAUTH2`): `oauth.tokenUrl` ← `https://login.microsoftonline.com/<tenantId>/oauth2/v2.0/token` when absent; `oauth.scope` ← SCOPE when absent; `oauth.clientId` ← `clientId`. The client secret is `apiKey`. `BEARER` with a user token works too.
- Properties: `tenantId`, `clientId` (STRING, not required in the descriptor), `scanner` (ENUM `auto` (default), `admin`, `standard`), `datasource.map` (`server[/database]=CATALOG_SOURCE[:upper|:lower]` per line or `;`-separated), `scan.timeout` (default 180 s, bounded 5..3600), `max.workspaces` (default 500, bounded 1..100000), `include.personal` (false), `include.hidden` (false), `dataflow.definitions` (true), `external.assets` (true), `portal.url` (default `https://app.powerbi.com`), `max.rows` (default 1000).
- Validation = default + with OAUTH2: no `oauth.tokenUrl` and blank `tenantId` → "tenantId: tenantId is required (or set oauth.tokenUrl)"; blank `clientId`/`oauth.clientId` → "clientId: clientId is required".
- `testConnection`: `GET /groups?$top=1` (failure result "GET groups -> HTTP n …"), then `GET /admin/groups?$top=1`: success → "Power BI REST API reachable; admin Scanner API available (tenant-wide scans with schemas and expressions)", refused → success message saying scans are limited to member workspaces without schemas or expressions.
- Scan (`scan()`): mode `standard` → standard scan; otherwise admin scan, and in `auto` a 401/403 falls back to standard (logged).
  - Admin: `GET /admin/groups?$top=5000&$skip=n&$filter=state eq 'Active'[ and type ne 'PersonalGroup']` paged, filtered by `schemas` (workspace name or id, case-insensitive), up to `max.workspaces`; `POST /admin/workspaces/getInfo?lineage=true&datasourceDetails=true&datasetSchema=true&datasetExpressions=true&getArtifactUsers=false` per batch of **100** workspaces; `scanStatus/{id}` polled every 1 s (first 10 attempts) then 3 s until `Succeeded` (`Failed`/`Cancelled` → "Power BI: scan <id> <state>: <error>"; timeout → "Power BI: scan <id> still <state> after scan.timeout seconds"); `scanResult/{id}` merged (`workspaces`, `datasourceInstances`); with `dataflow.definitions`, `GET /admin/dataflows/{id}/export` per dataflow (failure logged, not fatal).
  - Standard: `GET /groups?$top=5000`, then per workspace `reports`, `datasets` (tables left empty), `dashboards` + `tiles`, `dataflows` (+ `GET /groups/{ws}/dataflows/{id}` definition); each failing call logged and skipped.
- Operations (every call `requireSecret()`):
  - `catalog` (S, `source`; `schemas`, `types`) and `lineage` (S, `source`; `schemas`) → `PowerBiScan.scan(scanDocument, options)`; notes logged at INFO; `count` 0 → null.
  - `usage` (S, `source`; `days` default 30, bounded 1..30) → `GET /admin/activityevents?startDateTime='…T00:00:00.000Z'&endDateTime='…T23:59:59.999Z'` for each UTC day, following `continuationUri` until `lastResultSet` → `PowerBiScan.usage(events, options)`.
  - `refreshes` (S, `source`; `schemas`, `top` default 10, bounded 1..500) → for each scanned model `GET /groups/{ws}/datasets/{id}/refreshes?$top=` and each dataflow `/dataflows/{id}/transactions` → `PowerBiScan.job(...)` (jobs with at least one run) → `{source, scannedAt, jobs, count}`; none → null.
  - `workspaces` (S; optional `filter` = name fragment) → `{items:[{id, name, type, isOnDedicatedCapacity}], count}` (always a document).
  - `query` (S, `dataset` = 36-char id or `workspace/name`, `dax`; `max` default `max.rows`, bounded 1..100000) → `POST [/groups/{ws}]/datasets/{id}/executeQueries {queries:[{query}], serializerSettings:{includeNulls:true}}` → `{columns, rows, count}` with keys `Table[Column]` reduced to `Column`. Errors: "Power BI query: input dataset must be a dataset id or workspace/name", "…workspace '<w>' not found (or the service principal is not a member)", "…semantic model '<n>' not found in workspace <w>", "Power BI query failed: <error>".
  - `scan` (R; optional `schemas`) → the raw merged scan document.
- `types` → kinds: `MODEL`/`DATASET`/`SEMANTIC_MODEL` → semantic model kind; `REPORT`, `DASHBOARD`, `DATAFLOW`, `TABLE` (PowerBiScan kind constants); a filter naming no specific Power BI kind (e.g. `TABLE,VIEW`, or `TABLE` alone) → every kind.
- Options passed to PowerBiScan: `source`, `workspaces`, `kinds`, `datasourceMap` (`Options.parseDatasourceMap`), `externalAssets`, `includeHidden`, `includePersonal`, `portalUrl`.
- Parsing (core `PowerBiScan`, summarised; details belong to the core section): Power Query M lexer/step parser (`parseM`), source navigation (`Sql`/`AzureSynapse` → platform SQLSERVER…), `Table.SelectRows` → FILTER, `Table.Group` → AGGREGATION, join/combine functions → JOIN, transformation type order `IDENTITY < FILTER < TRANSFORMATION < AGGREGATION < JOIN`; DAX references (`daxReferences`, strings/comments stripped); a measure whose expression calls an aggregate function (`SUM`, `SUMX`, `COUNT*`, `DISTINCTCOUNT*`, `AVERAGE*`, `MIN*`, `MAX*`, `MEDIAN*`, `PRODUCT*`, `GEOMEAN*`, `PERCENTILE.INC/EXC`, `CALCULATE`, `CALCULATETABLE`, `TOTALYTD/QTD/MTD`, `SUMMARIZE`, `SUMMARIZECOLUMNS`, `RANKX`, `TOPN`) → AGGREGATION, otherwise TRANSFORMATION; calculated columns have native type `CALCULATED <type>`, measures `MEASURE`.
- `describeFields`: catalog → tables[0].assetKey/qualifiedName/schema/name/kind/url/remarks/scannedAt + columns[0].columnKey/name/dataType/nativeType/expression; lineage → edges[0].*; usage → items[0].assetKey/queryCount30d; refreshes → jobs[0].* and runs[0].*; workspaces → items[0].id/name.
- Tested offline only per GUIDE §3.5 (not verifiable from this code).

#### 3.2.63 `collibra` — Collibra Data Intelligence Platform (REST API v2) (CollibraConnector)

- `baseUrl` = `https://<instance>.collibra.com`; constant `API = /rest/2.0` appended unless already present; blank → "Collibra: baseUrl (https://<instance>.collibra.com) is not set". Category `DATA_PLATFORMS`, auth `BASIC`, `BEARER_TOKEN`, `OAUTH2_CLIENT_CREDENTIALS`; capabilities BULK_READ, BULK_WRITE, DESCRIBE_OBJECT; api "Collibra REST API 2.0". `nativePatch()` forced true (PATCH is sent as PATCH; requires `java.net.http`, JDK 11+). Every call `requireSecret()`.
- Properties (all optional): `community` (default **`Data Governance Council`**), `domain.type` (`Physical Data Dictionary`), `term.type` (`Business Term`), `relation.contains` (`Table>contains>Column`), `relation.lineage` (`Column>is source for>Column`), `relation.lineageTable` (`Table>is source for>Table`), `relation.term` (`Column>represents>Business Term`), `attribute.description` (`Description`), `attribute.definition` (`Definition`), `attribute.dataType` (`Technical Data Type`), `attribute.note` (`Note`), `attribute.url` (`URL`), `max.rows` (1000). Any `attribute.<key>` property aliases a payload key to an attribute type name.
- Name resolution: communities, domains (scoped by community), asset types, attribute types, statuses, domain types resolved by **exact** name (case-insensitive compare) through their list endpoints, cached per instance (cleared by `configure`); a UUID is used as is; not found → "Collibra: <kind> '<name>' not found [in <scope>]". Relation types `Source type>role>Target type`: loaded once (`/relationTypes`, up to 5000), match on role or co-role, preference exact source+target, then target, then role alone (approximate match logged); none → "Collibra: no relation type matches '<spec>' (see operation types, kind=relationTypes)".
- Paging helper: `offset`/`limit` (≤1000 per page) on `results[]` until the maximum. Non-2xx → "<id>: <what> -> HTTP n <body ≤300>".
- `testConnection`: `GET /communities?limit=1`, then resolves `community` → "Collibra REST API 2.0 reachable; community '<c>' found|NOT found".
- Operations (inputs win over same-named top-level payload fields):
  - `search` (S; `name` + `match` (`EXACT`/`START`/`ANYWHERE`, default ANYWHERE), `type`, `domain` (+`community`), `community`, `status`, `limit` (default `max.rows`, 1..100000), `attributes=true`) → `{items:[{id, name, displayName, type, domain, community, status, createdOn, lastModifiedOn, url, attributes?}], count}`; empty → null.
  - `get` (R; `id`, or `name` + `domain` [+ `community`]) → asset flattened + `attributes` `{typeName: first value}` + `outgoing[]`/`incoming[]` relations `{id, typeId, source, sourceId, target, targetId}`; not found → null.
  - `terms` (S; `type` default `term.type`, `domain`, `community`, `name`, `limit`) → items with `code` = id, `definition`, `description`, `note` read from attributes; empty → null.
  - `types` (R, `kind` = `assetTypes`, `attributeTypes`, `relationTypes`, `statuses`, `communities`, `domains`, `domainTypes`; optional `name`, `community` for domains) → `{items:[{id, name, description?, source/role/coRole/target/spec (relationTypes), community/type (domains), parent?}], count}`.
  - `upsert` (W; `name` required, `domain` required, `type` required to create, optional `displayName`, `status`, `community`, `domainType`, `parent` + `parentRelation`, `qualify` (default true), `term` + `termDomain` + `termRelation`, `attributes.<key>` inputs or payload `attributes{}`, payload `relations[{target, targetDomain?, targetCommunity?, direction: incoming?, type}]`): with `parent` and `qualify`, the asset name is `<parent> > <name>` and the display name the bare name; domain resolved in the community and **created on demand** (no community → "Collibra: domain '<d>' not found and no community to create it in (property community)"); asset found by exact name in the domain → PATCH only changed displayName/typeId/statusId, else POST (`type` missing → "Collibra upsert: 'type' is required to create asset '<n>'"); attributes created or patched only when different (unknown attribute type logged and skipped); relations created only when missing (parent contains, term represents, listed relations) → `{id, name, domainId, created, url, attributesSet, relationsCreated}`.
  - `relate` (W; `sourceId`/`targetId`, or `source`/`sourceParent`/`sourceDomain` and `target`/…; `type`; `community`): an end neither id nor name → "Collibra relate: '<side>Id', or '<side>' / '<side>Parent', is required"; end not found → `{created:false, skipped:"source not found"|"target not found"}`; default type `relation.lineage` when a parent is given on either side, else `relation.lineageTable` → `{sourceId, targetId, created}`.
  - `import` (W; payload = JSON array of Collibra import commands, else "Collibra import: the payload must be an array of import commands"; optional `simulation`, `wait=true`, `timeout` seconds default 120) → multipart `POST /import/json-job` (file `import.json`, `sendNotification=false`) → `{jobId, state}`; with `wait`, polls `GET /jobs/{id}` every 1 s until COMPLETED/ERROR/CANCELED or timeout → adds `message`, `result`; ERROR → "Collibra import job <id> failed: <message>".
  - `delete` (W, `id`) → `DELETE /assets/{id}` → `{deleted: true|false}` (404 → false, other errors thrown).
- `describeFields`: search/terms → items[0].* (search omits code/definition/description/note); upsert → payload fields; relate → source/target fields.

#### 3.2.64 Databases (JdbcConnector and dedicated types)

#### 3.2.65 `jdbc` — JDBC database (generic) (JdbcConnector)

- Public, non-final base class. Category `DATA_PLATFORMS` (dedicated types use `DATABASE`), auth `BASIC`, capabilities SCHEMA_DISCOVERY, PREVIEW_RECORDS, TRANSACTIONS; dependency "Runtime JDBC driver or JNDI DataSource"; pagination OFFSET 1000/0 (no fixed maximum page size advertised: a caller's `max` is bounded only by `max.rows.hard`, see below). Constants `MAX_FETCH_SIZE = 10000`, `MAX_BATCH_SIZE = 1000`; parameter pattern `(?<![:\w]):name` (so `::cast` and `a:b` are not parameters).
- Properties: `driver` (class name, loaded when set), `jndi` (DataSource JNDI name), `schema` (default schema for undotted table names and catalog scans), `max.rows` (default 1000: the row limit used when a call passes no `max` input), `max.rows.hard` (default unbounded — `Integer.MAX_VALUE` — the ceiling an **explicit** `max` input is capped at; before 2026-09-28 an explicit `max` was silently clamped to a fixed 10,000 regardless of what the caller asked for, which cut a multi-million-row source to 10,000 rows with no warning), `fetch.size` (default 100, 1..10000; input `fetchSize` overrides — the JDBC cursor batch size, independent of `max`/`max.rows.hard`), `batch.size` (default 100, 1..1000: maximum documents per write), `quote` (identifier quote; default the driver's; `none` → identifiers sanitised to `[A-Za-z0-9_.]`), plus undeclared `profile.maxColumns` (default 200, 1..1000) and `jdbc.<name>` (passed to the driver as connection property `<name>`).
- Guard (audit C-04, `core/JdbcGuard`, called by `connect()` before anything is loaded; the JVM properties `ebx.connectors.jdbc.jndiPrefixes`, `.allowedDrivers`, `.allowedOptions`, `.allowH2File`, `.allowH2Code` open the exceptions, see OPERATIONS §3.1): a JNDI name must start with `java:comp/env/` (and may not contain `..`); with a URL, the `driver` class must be one of the drivers of the URL's sub-protocol (table in `JdbcGuard`), or, for a sub-protocol the table does not know, any driver of the table; the URL (percent-decoded twice, option names lower-cased with `_`/`-` removed) and the `jdbc.*` properties may not carry the options that load a class, run SQL or touch a file (H2 `INIT`, `DATABASE_EVENT_LISTENER`, `JAVA_OBJECT_SERIALIZER`; `socketFactory`, `socketFactoryClass`, `sslfactory`, `sslSocketFactory`, `sslhostnameverifier`, `sslpasswordcallback`, `authenticationPluginClassName`, `accessTokenCallbackClass`, `trustManagerClass`, the MySQL interceptor, plugin, logger and handler options, `SYBSOCKET_FACTORY`, their `...Arg` companions; `loggerFile`, `traceFile`, `traceDirectory`; `allowLoadLocalInfile`, `allowLocalInfile`, `allowLoadLocalInfileInPath`, `allowUrlInLocalInfile`, `autoDeserialize`); an H2 URL must start with `jdbc:h2:tcp:`, `ssl:` or `mem:`; on `jdbc:h2:mem:` the `query` and `execute` statements may not contain `CREATE ALIAS|TRIGGER|AGGREGATE`, `FILE_READ`, `FILE_WRITE`, `CSVREAD`, `CSVWRITE`, `LINK_SCHEMA`, `SCRIPT` or `RUNSCRIPT` (comments removed first). Every refusal is `JDBC: ...` and names its property. Not covered: SQL run by the `execute` operation on a database server over TCP (the server runs it), and the JNDI environment of the JMS connectors (`ReflectiveJmsTransport`).
- Connection (a new one per operation; no pool unless JNDI): JNDI when property `jndi` is set or `baseUrl` starts with `java:` (resource not a DataSource → "JDBC: JNDI resource is not a DataSource: <n>"; naming error → "JDBC: cannot resolve JNDI DataSource <n>: …"; with a username, `getConnection(user, password)`); otherwise `baseUrl` must start with `jdbc:` ("JDBC: baseUrl must be a JDBC URL, or property jndi must name a DataSource"), `DriverManager.setLoginTimeout(max(1, connectTimeoutMs/1000))` (JVM-wide setting), properties `user`, `password` (unless placeholder), `jdbc.*`. Missing driver class → "JDBC driver <d> is not available. Install it in the EBX-Connectors runtime-libs bundle, rebuild the WAR, and restart EBX". SQL errors → "JDBC <action> failed (SQLState s) [vendor code n]" (the driver message is deliberately not included).
- Read operations use a read-only, non-autocommit connection (`setReadOnly(true)`; a driver still reporting read-write → "JDBC driver refused read-only mode"); writes run in one transaction committed at the end, rolled back on any error. Query timeout = max(1, readTimeoutMs/1000).
- Value binding: inputs typed (`-?[0-9]{1,18}` → Long, `-?[0-9]+\.[0-9]+` → BigDecimal, `true`/`false` → Boolean, else String); payload values typed from JSON (integral → Long, decimal → BigDecimal, boolean, text; objects/arrays → JSON text); inputs override payload fields of the same name. Missing parameter → "JDBC: no value for the parameter :<name>". Row values: Timestamp → ISO local date-time, Date → ISO date, Time → text, Clob → text, byte[] → Base64, numbers/booleans/strings as JSON, others `toString`; column label preferred to name.
- Operations:
  - `query` (S, `sql`): must be **one** SELECT: starts with `select`, contains no `;`, no `for update|share`, no word `into` anywhere ("JDBC query accepts only one read-only SELECT statement") → `{rows, count, truncated}`, 0 rows → null. `truncated` (added 2026-09-28) is set by asking the driver for one extra row beyond `max` (`setMaxRows(max + 1)`, or `setMaxRows(0)` — "no limit" — when `max` is unbounded): the extra row is dropped from `rows`/`count` when it comes back, no second query. Read by `ConnectorService.importDocument`, which turns a truncated feed read into a `SKIPPED` "Limit reached" notice instead of a silent, apparently-complete `OK`.
  - `get` (R, `table`, `key`, `value`) → `SELECT * FROM <table> WHERE <key> = ?` → first row or null.
  - `insert` (W, `table`), `update` (W, `table`, `key` comma list), `upsert` (W, `table`, `key`): payload = object or array of non-empty objects ("JDBC <op>: an outbound document or document array is required", "…every batch item must be a non-empty object"), at most `batch.size` ("JDBC <op>: batch exceeds configured limit N"); update = `UPDATE … SET <non-key fields> WHERE <keys>` (key absent from the document → "JDBC: the key column <k> is missing from the outbound document"; a document with only key fields runs an existence `SELECT 1`); upsert = update, then insert when no row was updated → `{updated?, inserted?, processed}`.
  - `execute` (W, `sql`): any statement with `:name` parameters from inputs and payload → rows when it returns a result set, else `{affectedRows}`; committed.
  - `catalog` (S, `source`; `schemas` (else property `schema`, else every schema except system ones: `INFORMATION_SCHEMA*`, `PG_*`, `SYS`, `SYSTEM`, `SYSIBM`, `MYSQL`, `PERFORMANCE_SCHEMA`, `DB_*`, `GUEST`, `SNOWFLAKE`, `SYS_*`; `*` = all), `types` (default `TABLE,VIEW`; materialized views included with VIEW), `rowCounts` (**default false**; `true` runs `COUNT(*)`, errors put in `rowCountError`)) → `{source, scannedAt, tables:[{source, assetKey, qualifiedName (schema.name), schema, name, kind TABLE|VIEW|MATERIALIZED_VIEW, remarks?, scannedAt, rowCount?, columns:[{columnKey, name, position, dataType, nativeType (with (size[,digits]) for CHAR/DEC/NUM types), nullable, primaryKey (tables only), remarks?, scannedAt}]}], count}`; none → null. Read-only mode is attempted but a refusal is tolerated for the catalog operations (H2 over TCP).
  - Normalised types (`normalisedType`): any type name containing JSON → JSON; CHAR/VARCHAR/…/CLOB/NCLOB → STRING; TINYINT..BIGINT → INTEGER; DECIMAL/NUMERIC/FLOAT/REAL/DOUBLE → DECIMAL; BOOLEAN/BIT → BOOLEAN; DATE; TIME(+TZ) → TIME; TIMESTAMP(+TZ) → TIMESTAMP; BINARY/VARBINARY/LONGVARBINARY/BLOB → BINARY; otherwise by name heuristics, else OTHER.
  - `profile` (S, `source`; `tables` = `*` (default, the catalog selection) or qualified names; `schemas`, `types`; `where` = SQL appended as `WHERE <where>` verbatim) → one aggregate query per table over up to `profile.maxColumns` columns: `COUNT(*)`, `COUNT(c)`, `COUNT(DISTINCT c)`, MIN/MAX for STRING/INTEGER/DECIMAL/DATE/TIMESTAMP/TIME, `AVG(CAST(c AS DOUBLE PRECISION))` for numbers, `MIN/MAX/AVG(LENGTH(c))` for strings; per-run budget and dry run `maxTables`, `maxSeconds`, `dryRun` (OPERATIONS §3.3), items carry `exact: true`, `distinctMethod: EXACT`, plus `exact`/`partial`/`budget` at the top; no sampling on JDBC (audit C-15) → items `{source, assetKey, qualifiedName, columnKey, name, dataType, profiledAt, profileDate, method: FULL_SCAN, rowCount, nullCount, nullRate, distinctCount, uniqueRate, minValue?, maxValue? (≤500 chars), meanValue?, minLength?, maxLength?, avgLength?}` (rates and means rounded to 4 decimals).
  - `sample` (S, `source`; `tables`, `schemas`, `limit` default 50, bounded 1..500) → first 200 STRING columns per table, distinct non-empty values cut at 200 characters → `{…, sampleSize, values[], valuesText}`.
- `testConnection`: opens a connection, reads `DatabaseMetaData` → "Connected to <product> <version>" (SQL errors are thrown, not returned as failure).
- `preview`: operation `query` with a safe SELECT (`sql` input, other inputs as parameters) or `objectId` = table (`SELECT * FROM <table>`); read-only; `limit + 1` rows to detect truncation; always rolled back. Messages "JDBC preview requires one explicit SELECT statement without locking or INTO clauses", "JDBC table preview requires a discovered table name", "JDBC preview requires a discovered table or the read-only query operation".
- `discoverObjects`: without property `schema`, schemas first as objects `schema:<name>` (type SCHEMA, not readable), then tables and views `schema.table` (readable); substring filter; numeric continuation token (offset).
- `describeObject(table)` → fields of `get`. `describeFields`: `table` → metadata columns trying the name as given, upper- and lower-case (label = REMARKS or name, type lower-case); `sql` → safe SELECT executed with max 1 row, result metadata ("JDBC describe accepts only one read-only SELECT statement"); prefix `rows[0].` except for get/insert/update/upsert.

#### 3.2.66 Dedicated database types (subclasses of JdbcConnector)

All: category `DATABASE`, auth `BASIC`, capabilities TEST_CONNECTION, DISCOVER_OBJECTS, DESCRIBE_OBJECT, SCHEMA_DISCOVERY, PREVIEW_RECORDS, BULK_READ, BULK_WRITE, TRANSACTIONS; pagination OFFSET 1000/0 (see `max.rows.hard` above — the fixed 10,000-row descriptor maximum was removed 2026-09-28); `ConnectorDefaults.standard()`; same operations as `jdbc`; a driver default is set in `configure` **after** validation has already run.

| Type | Class | Label | Driver default | Declared properties | Extra validation (only when property `jndi` is absent) | Dependency declared |
|---|---|---|---|---|---|---|
| `postgresql` | PostgreSqlConnector | PostgreSQL | `org.postgresql.Driver` | jndi, driver, schema, max.rows, max.rows.hard, fetch.size, batch.size, quote | `baseUrl` must start with `jdbc:postgresql:`; `driver`, when set, must be `org.postgresql.Driver`; `max.rows` 1..10000, `max.rows.hard` 1..`Integer.MAX_VALUE`, `fetch.size` 1..10000, `batch.size` 1..1000 ("Must be between a and b"). With `jndi`, the generic `baseUrl` issues are **removed**. | `org.postgresql:postgresql:42.7.x`, api "PostgreSQL JDBC 4.2" |
| `h2` | H2Connector | H2 Database | `org.h2.Driver` (`DRIVER`) | jndi, driver, schema, max.rows, max.rows.hard, fetch.size, batch.size, quote | `baseUrl` must start with `jdbc:h2:`; `driver` must be `org.h2.Driver` | `com.h2database:h2:2.x`, api "H2 JDBC 2.x" |
| `mysql` | MySqlConnector | MySQL Database | `com.mysql.cj.jdbc.Driver` (`DRIVER`) | jndi, driver, schema, `jdbc.sslMode` (ENUM `VERIFY_IDENTITY` (default) / `VERIFY_CA`), `jdbc.allowPublicKeyRetrieval`, `jdbc.useServerPrepStmts`, `jdbc.serverTimezone`, max.rows, fetch.size, batch.size, quote | `baseUrl` must start with `jdbc:mysql://`; driver must be the default; `jdbc.sslMode` must be VERIFY_IDENTITY or VERIFY_CA; `jdbc.allowPublicKeyRetrieval=true` refused | `com.mysql:mysql-connector-j:26.7.0` (`CONNECTOR_J_VERSION`), "X DevAPI is not used" |
| `oracle-database` | OracleDatabaseConnector | Oracle Database | `oracle.jdbc.OracleDriver` | jndi, driver, schema, max.rows, fetch.size, batch.size, quote | `baseUrl` must start with `jdbc:oracle:` | `com.oracle.database.jdbc:ojdbc8:23.9.0.25.07`, api "Oracle JDBC 4.3" |
| `sql-server` | SqlServerConnector (MicrosoftSqlConnectorBase) | Microsoft SQL Server | `com.microsoft.sqlserver.jdbc.SQLServerDriver` | jndi, driver, schema, `jdbc.encrypt`, `jdbc.trustServerCertificate`, `jdbc.hostNameInCertificate`, max.rows, fetch.size, batch.size (no `quote`) | `baseUrl` must start with `jdbc:sqlserver://` (case-insensitive); `jdbc.encrypt=false` → "Encrypted transport is required; use a trusted server certificate"; `jdbc.trustServerCertificate=true` → "Certificate trust bypass is not permitted" | `com.microsoft.sqlserver:mssql-jdbc:12.x.jre11`, api "Microsoft SQL Server JDBC 12.x" |
| `azure-sql` | AzureSqlDatabaseConnector (MicrosoftSqlConnectorBase) | Azure SQL Database | same | same | as SQL Server, plus `baseUrl` must contain `.database.windows.net` ("Azure SQL host must end in .database.windows.net"); messages "Azure SQL requires encrypted transport", "Azure SQL certificate validation cannot be bypassed" | same, api "Azure SQL / Microsoft JDBC 12.x" |

- Microsoft types' `configure` defaults: `jdbc.encrypt=true`, `jdbc.trustServerCertificate=false`; Azure SQL adds `jdbc.hostNameInCertificate=*.database.windows.net`. MySQL `configure` defaults: `jdbc.sslMode=VERIFY_IDENTITY`, `jdbc.allowPublicKeyRetrieval=false`, `jdbc.useServerPrepStmts=true`, `jdbc.serverTimezone=UTC`. These reach the driver through the `jdbc.*` pass-through.

---


#### 3.2.67 `slack` — Slack (Web API, bot token) (SlackConnector)

- `baseUrl` = `https://slack.com/api`, `apiKey` = the bot token (`xoxb-…`). Category `MESSAGING`, auth `BEARER_TOKEN`, api "Web API". The token is sent as `Authorization: Bearer` whatever the saved auth type: `headers()` adds it when the inherited ones did not. A missing or `TO_BE_SET` token fails before any call ("bot token not configured").
- Properties: `channel` (default channel, id or `#name`), `channelTypes` (conversation types searched to resolve a name, default `public_channel`; `private_channel` needs `groups:read`), `escapeText` (BOOLEAN, default true, advanced), `unfurlLinks` (BOOLEAN, default false, advanced). The `workflow.*` properties read by §4.6.6 are not declared in the descriptor.
- `post` (W, `text`; optional `channel`, `title`, `threadTs`, `blocks`, `replyBroadcast`) → `chat.postMessage`. `notify` is the same operation under the name used by `chat-webhook` flows. `update` (W, `ts`, `text`; optional `channel`, `title`, `blocks`) → `chat.update`. `dm` (W, `text`; optional `email` or `user`, `title`, `blocks`) → `users.lookupByEmail` then `chat.postMessage` on the user id. `channels` (S; optional `name`, `types`, `max`) → paginated `conversations.list`, filtered on the name.
- Message body: `text` is `*title*\ntext` with `&`, `<`, `>` escaped unless `escapeText=false`; `blocks` (JSON array, or `{blocks:[…]}`) overrides the rendering and `text` stays the notification text; `unfurl_links`/`unfurl_media` follow `unfurlLinks`; over 40 000 characters the call is refused locally. An outbound mapping's document fills `text`, `title`, `channel`, `threadTs`, `ts`, `email`, `user`, `replyBroadcast` when the inputs leave them empty (inputs win).
- Channel resolution: `[CGDUW][A-Z0-9]{8,}` is used as an id; anything else (with or without `#`) is looked up once through `conversations.list` (50 pages of 200 max) and cached per connector instance. Not found → `ConnectorException` 404 naming `groups:read` and `channelTypes`.
- **Refusals are HTTP 200.** Every answer is checked on `ok`; `false` raises a `ConnectorException` carrying the Slack error, a hint and a status: 401 (`invalid_auth`, `not_authed`, `token_revoked`, `account_inactive`, `token_expired`), 403 (`missing_scope` — the hint quotes the `needed` scope —, `not_in_channel`, `restricted_action`, `is_archived`, `cant_update_message`, `edit_window_closed`), 404 (`channel_not_found`, `users_not_found`, `user_not_found`, `message_not_found`), 429 (`ratelimited`), else 400. A real HTTP 429 is reported with `Retry-After`.
- `testConnection`: `auth.test` (read-only) → "Slack workspace '<team>' as <user>".
- Returns: `post`/`notify` `{status:"sent", channel, ts, threadTs}`, `update` `{status:"updated", channel, ts}`, `dm` adds `user`, `channels` `{count, items[]}` (`id`, `name`, `isPrivate`, `isMember`, `isArchived`, `members`, `topic`, `purpose`).
- Bot scopes: `chat:write`, `chat:write.public`, `channels:read`, `users:read.email`, `groups:read` for private channels. Offline contract: `SlackConnectorTest`.

---

#### 3.2.68 `documents` — Documents (PDF, DOCX, text) with AI extraction (DocumentConnector)

- Category `AI`. Reads a document (input `content` base64 + `name`, a file `path` of the configured `folder`, or the `sampleData` uploaded on the connector), extracts its text (`DocumentText` / `PdfText`, no OCR) and asks the LLM connector `llm.connector` (`llm.ask`, JSON mode) for the attributes in `fields`, each with a confidence and a snippet. Operations `list` (S), `text` (R), `extract` (R), `extractAll` (S), `annotate` (W). The item carries the confidence envelope of §2.8.2 (`_confidence`, `_minConfidence`, `_sources`). Folders must sit under `-Debx.connectors.documents.roots`. A DAMA asset is read from a record's media field (`record`, `table`, `field`, defaults `dama.*`) or by UUID (`asset`), under the caller's session; `annotate` writes the extracted values as DAMA metadata and adds tags chosen from DAMA's vocabulary, and what is below the threshold becomes a `dama:` conflict on the record. With `vision=true` (on this connection and on the LLM connector), an image (png, jpeg, gif, webp) or a PDF without text layer is sent to the multimodal model, its values capped at `vision.confidence.max` (0.7). Full reference in `DOCUMENTS.md`, `DAMA-DOCUMENTS.md` and `DOCUMENTS-VISION.md`.

#### 3.2.69 `redshift` — Amazon Redshift (RedshiftConnector)

- Dedicated JDBC profile (subclass of `JdbcConnector`, same operations, category `DATABASE`, capabilities as §3.2.66). Driver default `com.amazon.redshift.jdbc42.Driver` (`com.amazon.redshift.jdbc.Driver` also accepted by `JdbcGuard`), dependency `com.amazon.redshift:redshift-jdbc42:2.1.x` (not shipped: install it in Tomcat), api "Amazon Redshift JDBC 2.x".
- Validation (without `jndi`): `baseUrl` must be `jdbc:redshift://` or `jdbc:redshift:iam://` with an **endpoint host** (containing a dot) and a numeric port, e.g. `jdbc:redshift://cluster.abc123.eu-west-1.redshift.amazonaws.com:5439/dev`; the cluster-identifier form (`jdbc:redshift:iam://my-cluster:us-east-1/dev`, resolved by the driver outside the egress rules), `plugin_name` and `CredentialsProvider` (driver-side class loading) are refused. Upsert is update-then-insert; COPY/UNLOAD and the Redshift Data API are not used.

### 3.3 Defaults and constants

| Scope | Constant / property | Value (class) |
|---|---|---|
| Instance | connect / read timeout | 10000 / 30000 ms; non-positive values reset to these (ConnectorConfig) |
| Instance | trust | 50, clamped 0..100 (ConnectorConfig) |
| Instance | placeholder secrets | blank, `A_RENSEIGNER`, `TO_BE_SET` (ConnectorConfig.isPlaceholder) |
| Type | `ConnectorDefaults.standard()` | 10000 ms, 30000 ms, 0 retries, trust 50 |
| Batch | `HARD_MAX_ITEMS`, `HARD_MAX_WRITE_ITEMS`, `HARD_MAX_BYTES` | 100, 50, 2 097 152 (BatchRequest) |
| Discovery | `DEFAULT_LIMIT`, `MAX_LIMIT` | 50, 500 (ObjectDiscoveryRequest) |
| Preview | `DEFAULT_LIMIT`, `MAX_LIMIT` | 20, 100 (PreviewRequest) |
| HTTP | User-Agent | `EBX-Connectors/1.0 (<id>)`; own clients `EBX-Connectors/1.0 (gcs|s3|azure-blob|melissa|dynamics|dnb|databricks|salesforce|workday)` |
| HTTP | error detail length | 300 characters (AbstractRestConnector.bodyOrNull, PowerBiConnector, CollibraConnector) |
| Files | `DEFAULT_MAX_BYTES`, `ABSOLUTE_MAX_BYTES`, `ABSOLUTE_MAX_ROWS` | 10 MiB, 100 MiB, 100 000 (FileFormats); `max.rows` default 10000 (AbstractFileConnector); list max 1000 |
| JDBC | `MAX_FETCH_SIZE`, `MAX_BATCH_SIZE`; defaults | 10000, 1000; fetch.size 100 (≤10000, cursor batch size, independent of the row limit), batch.size 100 (≤1000), max.rows 1000 (used with no `max` input), max.rows.hard unbounded by default — caps an *explicit* `max` input, replacing the old fixed 10,000 (2026-09-28); profile.maxColumns 200 (≤1000), sample limit 50 (≤500) (JdbcConnector) |
| JMS | `MAX_MESSAGES`, `MAX_TIMEOUT_MILLIS`; defaults | 500, 60000; max.messages 100, receive.timeout.ms 3000, redelivery.max 6, redelivery.delay.ms 1000, priority 4, ttl.ms 0, persistent true, acknowledgement CLIENT, destination.type QUEUE, connection.factory `ConnectionFactory` (JmsConnector) |
| RabbitMQ | prefetch, max.messages | 100 each, bounded 1..500 (RabbitMqConnector, ReflectiveRabbitMqTransport) |
| Azure Service Bus | `HARD_MAX`; defaults; lock retention | 500; max.messages 100, wait.ms 5000, auth.mode CONNECTION_STRING, receive.mode PEEK_LOCK; locks reaped after 5 min (AzureServiceBusConnector, AzureServiceBusSdkTransportFactory) |
| Kafka | defaults | poll.ms 3000, max.messages 100, max.poll.records ≤500, acks all, auto.offset.reset earliest, group `ebx-connectors-<id>`; catalog `MAX_FIELDS` 500, sampleSize 1000 (≤10000), sample limit 50 (3..500), broker timeout max(5000, readTimeoutMs), 500 ms polls, 4 idle polls (KafkaConnector, KafkaCatalog) |
| EBX catalog | defaults | search pageSize 100; sampleSize 1000 (3..10000); limit 50 (3..500); `DEFAULT_EXCLUDE` `.*(/TechnicalData/|_MDS/|/ebx-|/Bebx-).*`; remarks cut at 4000 (EbxConnector, EbxCatalog) |
| Generic REST | pagination | maxPages 20 (1..100), maxRecords 1000 (1..10000) (GenericRestConnector) |
| SOAP | WSDL import limits | maxDepth 4, maxDocuments 24, maxBytes 4 194 304, maxMillis 15000 (SoapConnector) |
| SAP OData | defaults | odata.version 2, max.rows 500 (≤10000), max.pages 20 (≤100), batch.max.items 50 (SapODataConnector) |
| SuccessFactors | defaults | entitySet PerPerson, max.rows 200, max.pages 20, default.expand `personalInfoNav,employmentNav` |
| Dynamics 365 | defaults | apiVersion v9.2, max.rows 200, max.pages 20, batch.max.items 50 |
| Business Central | defaults | environment Production, apiVersion v2.0, max.rows 200, max.pages 20 |
| SharePoint | defaults | max.rows 200 (≤5000), 50 pages, `$top` ≤999, max.file.bytes 1 048 576 (≤10 485 760), scope `https://graph.microsoft.com/.default` |
| Oracle Fusion / ServiceNow / NetSuite | max.rows | 200 / 200 / 200 (NetSuite ≤1000); Fusion family `fscmRestApi`, apiVersion `11.13.18.05` |
| SAP Ariba | `MAX_ROWS`; defaults | 500; max.rows 100, api.version v1, supplier.path `/supplier-data-api/v1/prod/suppliers`, purchase-order.path `/procurement-api/v1/prod/purchaseOrders` |
| HubSpot / Workday | limits | search limit ≤100 (default 100) / limit 100 |
| Salesforce | apiVersion, token cache | v60.0, 50 min |
| JD Edwards | ais.version, token cache, max.rows, device | v2, 20 min, 500, `EBX` |
| Snowflake | timeout, max.rows, polling, JWT | 60 s, 1000, 1.5 s until readTimeoutMs, exp 3540 s / regenerated after 50 min |
| Databricks | wait, max.rows, polling, nested function | 30 s (clamped 5..50), 1000, 2 s until max(readTimeoutMs, 60000), `parse_json` |
| BigQuery | max.rows, job timeout, query wait, base | 1000, readTimeoutMs − 1000 (≥1000), `query.maxWaitSeconds` 120, `https://bigquery.googleapis.com` |
| GCS / S3 / Azure Blob | base, region, API version | `https://storage.googleapis.com` / `us-east-1` / `x-ms-version 2021-08-06` |
| Power BI | defaults | scanner auto, scan.timeout 180 s (5..3600), max.workspaces 500, getInfo batch 100, status polling 1 s ×10 then 3 s, usage days 30, refreshes top 10 (≤500), query max.rows 1000 (≤100000), portal `https://app.powerbi.com` |
| Collibra | defaults | community `Data Governance Council`, max.rows 1000 (≤100000), page size ≤1000, relation types ≤5000, import timeout 120 s |
| LLM | defaults | maxTokens 1024, temperature 0, system "You are a precise data assistant.", anthropic.version 2023-06-01, azure.apiVersion 2024-10-21 (azure) / 2024-05-01-preview (azure-ai), google.apiVersion v1beta, google.location us-central1, openai.path `/v1/chat/completions`, openai.maxTokensParam `max_tokens`; models claude-sonnet-5, gemini-2.5-flash, mistral-small-latest, llama3.2, gpt-4o-mini |
| OpenLineage | defaults | ebx.namespace `ebx://localhost:8080`, job.namespace `ebx-connectors`, exclude.types `chat-webhook,slack,openlineage,llm`, producer `https://github.com/on-ebx/ebx-connectors` |
| Token lifetimes | own implementations | Creditsafe 50 min; Workday 50 min; INPI token.ttl 3000 s; D&B expiresIn − 120 s (default 3600 s); Dynamics 365 expires_in − 120 s (default 3000 s); Databricks expires_in − 60 s (default 3000 s) |
| Reference data | thresholds | BAN score.min 0.5; OpenSanctions threshold 0.7, screen limit 5, search limit 10, dataset `default`; D&B confidence.min 7, candidates.max 10, country FR, blockIDs `companyinfo_L2_v1,hierarchyconnections_L1_v1`; Melissa country FR, verified.only true; Loqate geocode true, verification.min V, find limit 10; Creditsafe country FR, language en, pageSize 20 |
| Registries | page sizes | Companies House 20; OpenCorporates 30; Handelsregister 30; Orbis 20 |

---


### 3.4 Configuration validation by type (summary)

Order at run time (ConnectorRegistry.connector): inactive → problem (secret/vault) → egress allow-list on `baseUrl` → descriptor validation (`validateConfiguration`) → `configure`. Defaults applied in `configure` are therefore **not** visible to validation.

| Validation mode | Types |
|---|---|
| Default validator only (§1.3) | gleif, sirene, google-geocoding, rest, salesforce, snowflake, databricks, collibra, sap-odata, jde-ais, dnb, azure-blob, ban, bigquery, companies-house, creditsafe, dynamics365, gcs, hubspot, jdbc, loqate, melissa, handelsregister, opencorporates, opensanctions, oracle-fusion, netsuite, orbis, s3, servicenow, vies, webhook, workday, llm, soap, graphql, ebx, openlineage, chat-webhook, inpi, openfda, kafka, sftp |
| Default + type rules | postgresql, h2, mysql, oracle-database, sql-server, azure-sql, jms, activemq, sap-successfactors, sap-ariba, powerbi |
| Replaces the default entirely (no generic `baseUrl` or auth checks) | rabbitmq, azure-service-bus, sharepoint, dynamics-business-central |

Required properties declared in descriptors (default validator reports "A value is required" when blank and no default): `dynamics365` tenantId, clientId; `sharepoint` tenantId, clientId (own validator); `dynamics-business-central` tenantId, environment, companyId, clientId (own validator); `netsuite` account; `salesforce` clientId (oauth.flow has a default); `workday` tenant, clientId, refreshToken (secret); `bigquery` project, dataset; `databricks` warehouse_id; `gcs` bucket; `s3` bucket; `azure-blob` container; `rabbitmq` queue; `sap-ariba` realm (+ own rule); `rest` `op.<name>.method`, `op.<name>.path`; `graphql` `op.<name>.query`; `soap` `op.<name>.body`. Pattern properties apply only to the operations actually declared.

---


### 3.5 Runtime libraries and dependencies

- `runtime-libs/` (packaged into `WEB-INF/lib` of the WAR; `SHA256SUMS` present): `postgresql.jar` (42.7.13); `h2.jar` (2.1.212); `ojdbc8.jar` (23.9.0.25.07); `mssql-jdbc.jar` (12.10.0, Java 8 build); `mysql-connector-j-26.7.0.jar`; `jsch.jar` (`com.github.mwiede` 2.28.7); `kafka-clients.jar` (3.9.2) + `lz4-java.jar`, `snappy-java.jar`, `zstd-jni.jar`; `amqp-client-5.35.0.jar`; `activemq-client-5.19.11.jar`, `jakarta.jms-api-2.0.3.jar`, `hawtbuf-1.11.jar`; `azure-messaging-servicebus-7.17.20.jar`, `azure-identity-1.18.4.jar`, `azure-core-1.59.0`, `azure-core-amqp-2.12.1`, `azure-core-http-netty-1.16.6`, `azure-json-1.5.1`, `azure-xml-1.2.1`, `msal4j-1.23.1`, `msal4j-persistence-extension-1.3.0`, `jna-5.13.0`, `jna-platform-5.17.0`, `proton-j-0.34.1`, `qpid-proton-j-extensions-1.2.6`, `reactive-streams-1.0.4`, `reactor-core-3.7.19`, `reactor-netty-core/http-1.2.18`, Netty 4.1/4.2 modules and native transports / tcnative for Linux, macOS, Windows. EBX, Servlet, Jackson and SLF4J are deliberately excluded (provided by EBX). No Excel/Parquet codec is packaged.
- Registration behaviour per dependency:

| Types | Library use | Missing library |
|---|---|---|
| `kafka` | direct imports | type **not registered** (quiet) |
| `sftp` | direct imports (JSch) | type **not registered** (quiet) |
| `rabbitmq` | reflection on `com.rabbitmq.client.*` | type listed; "RabbitMQ connect failed: …" at execution |
| `jms`, `activemq` | reflection + JNDI | type listed; JNDI/`NoSuchMethod` failure at execution ("JMS <op> failed: …") |
| `azure-service-bus` | ServiceLoader adapter + reflection on the Azure SDK | type listed; "Azure Service Bus transport adapter is not installed" / "Azure Service Bus SDK is not packaged …" |
| `jdbc`, `postgresql`, `h2`, `mysql`, `oracle-database`, `sql-server`, `azure-sql` | JDBC `DriverManager` / JNDI | type listed; "JDBC driver <d> is not available…" or a `DriverManager` error |
| all HTTP types (incl. `snowflake`, `databricks`, `bigquery`, `s3`, `azure-blob`, `gcs`, `sharepoint`, `powerbi`, `collibra`, `llm`) | JDK + `HttpJsonClient` (core) | none needed |
| `soap` | JDK XML (`Xml`, `SoapSupport` in core) | none |

- Extension SPI jar: `ebx-connectors/build-spi.sh` builds `dist/ebx-connectors-spi.jar` (Java 8 bytecode; the built jar in the repository has 79 classes, `Implementation-Version 2026.09.11`) from `spi/*.java` plus core `AbstractRestConnector`, `HttpJsonClient`, `HttpRetryPolicy`, `RestSchemaRefresh`, `Json`, `OAuth2`, `Tls`, `Secrets`, `SecretResolvers`, `Hashing`, `Webhooks`, `Egress`, `Redaction`, and `dist/ebx-connectors-spi-sources.jar`. Helpers such as `Templates`, `ODataSupport`, `RestPageTraversal`, `GoogleAuth`, `AwsSigV4`, `Csv`, `RecordProfiler`, `CatalogOperations` are **not** in the SPI jar.

---

## 4. ON EBX integration: services, UI, REST, MCP, automation

### 4.1 Module registration and startup

#### 4.1.1 Module identity

| Item | Value | Source |
|---|---|---|
| Module name (`module.xml` `<name>`) | `ebx-connectors` | `module.xml` |
| Public path / web context | `ebx-connectors` | `module.xml` |
| Data model | `/WEB-INF/ebx/schemas/Connectors.xsd` (`ConnectorsPaths.MODEL_PATH`) | |
| Configuration dataspace | `Connectors` (`ConnectorsPaths.DATASPACE`), child of the reference dataspace | `ConnectorsInstaller` |
| Configuration dataset | `Connectors` (`ConnectorsPaths.DATASET`) | `ConnectorsInstaller` |
| Module services in `module.xml` | none (`<services/>` is empty) | |
| Beans in `module.xml` | two: `com.on.ebx.connect.ebx.workflow.ConnectorScriptTaskBean` (§6.3) and `com.on.ebx.connect.ebx.workflow.ChatWorkItemTrigger` (§4.6.6) | |
| Model extensions | `<osd:extensions class="com.on.ebx.connect.ebx.ConnectorsSchemaExtensions"/>` | `Connectors.xsd` |
| Model-level settings | `validationMessageThreshold` 1000; `dataSetInheritance` none; root `osd:access="--"` | `tools/build_xsd.py` header |

#### 4.1.2 `web.xml`

- One servlet, `JAX-RS-REST`, class `com.orchestranetworks.rest.internal.RESTRegistrationServlet` (EBX REST Toolkit), `load-on-startup` 1.
- Init parameters: `RESTApplicationAbstract` = `com.on.ebx.connect.ebx.rest.ConnectorsRestApplication`; `ebxModuleName` = `ebx-connectors`; `hide-service-list-page` = `true`.
- `ConnectorsRestApplication` is annotated `@ApplicationPath("/rest")` and registers two resources: `ConnectorsResource` (`/v1`) and `McpEndpoint` (`/mcp`). The resulting URLs are `/ebx-connectors/rest/v1/...` and `/ebx-connectors/rest/mcp`.
- Authentication is the EBX REST Toolkit's own (HTTP Basic, token, SSO/session). The caller's session runs every operation.

#### 4.1.3 `ConnectorsModuleRegistration` (a `@WebListener`, extends `ModuleRegistrationListener`)

**`handleRepositoryStartup`**, in this order:

1. `ConnectorsInstaller.install(repository, systemSession("connectors"), moduleName, loggingCategory)`. Details in §1.5.
2. `PackagedProductProfileInstaller.install(repository, systemSession("connectors-product-profiles"), log)`. Details in §1.6.
3. `ConnectorOperationsRuntime.install(new MetricsAndAlertsObserver(new ConnectorOperationalMetrics(new EbxConnectorOperationalMetricsPersistence(repository, systemSession("connectors-metrics"))), null))`. Live synchronisation results now feed the `ConnectorOperationalMetrics` table. Without this call the runtime uses a no-op observer and the health dashboard stays empty.
4. `Retention.start(repository, systemSession("connectors-retention"))`: starts the purge task unless `retentionDays` is 0 (§2.9.1).
5. `DeclaredLineage.install(repository)`. This schedules a startup sweep that describes every mapping to the OpenLineage sinks. The delay comes from `-Debx.connectors.lineage.startupDelaySeconds` (default 90 s, in core).

`BackgroundWork.start()` runs before step 1, so that anything below may create its executor (§2.22.3).

**`handleRepositoryShutdown`** and **`destroyBeforeUnregisterModule`** both call `stopBackgroundWork()` (harmless twice), in this order: `InJvmMcp.unregister()`; `ConnectorService.requestStopOfAsyncImports()`; `BackgroundWork.stop(-Debx.connectors.shutdown.waitSeconds, default 10)`; `ConnectorService.forgetAsyncImports()`; `DeclaredLineage.forgetPending()`; `ConnectorRegistry.invalidate()` (closes every cached connector instance); the operations observer installed at step 3 is taken back. Executors still running after the wait are named in a WARN. A failure in this sequence is logged and never thrown. Details in §2.22.3.

**`handleServiceRegistration`** registers seven module-level user services:
`ConnectorSyncServiceDeclaration`, `OpenApiImportServiceDeclaration`, `ConflictManagerServiceDeclaration`, `ConflictManagerOnTableDeclaration`, `ConflictManagerOnRecordDeclaration`, `DeleteMappingOnRecordDeclaration`, `DeleteMappingOnTableDeclaration`.

It then calls `registerMcpServer`:

- It reads `-Debx.connectors.mcp` (values `native|endpoint|both|none`, default `both`, lower-cased). It continues only for `both` or `native`.
- Using reflection, it loads `com.orchestranetworks.mcp.core.McpServerCategory` and the method `registerMcpServer(String, String, McpServerCategory, Class[])` on the registration context. It then registers `ConnectorsMcpServer` under name `ebx-connectors`, with `SERVER_DESCRIPTION` and category `PRODUCTION`.
- Log outcomes (category `ConnectorService.log()`):
  - Success: info `MCP server 'ebx-connectors' registered with the EBX MCP runtime (N tools)`.
  - `ClassNotFoundException`: info `no native MCP runtime in this EBX version: the MCP tools are served by /ebx-connectors/rest/mcp`.
  - `NoSuchMethodException`: info `this EBX version does not let modules register MCP servers: ...`.
  - `InvocationTargetException`: warn `EBX declined the MCP server registration (<cause>): ...`. On EBX 6.3 dev builds only core/add-on modules are accepted, so this is the expected case.
  - Any other `Throwable`: warn `MCP server registration failed: ...`.

#### 4.1.4 `ConnectorsSchemaExtensions` (model-level registration, reloads with the XSD)

`defineExtensions` registers these **schema-scoped** user services. Their keys have no module part (`ServiceKey.forName`):

| Order | Declaration | Key |
|---|---|---|
| 1 | `MappingDesignerServiceDeclaration` | `MappingDesigner` |
| 2 | `ConnectorCatalogServiceDeclaration` | `ConnectorCatalog` |
| 3 | `ConnectorDiagnosticsServiceDeclaration` | `ConnectorDiagnostics` |
| 4 | `ConnectorOperationsServiceDeclaration` | `ConnectorOperations` |
| 5 | `ReleaseNotesServiceDeclaration` | `ReleaseNotes` |

Before `ReleaseNotes` is registered, `disableReleaseNotesOnTables` walks the schema tree. On every table node it calls `setServicePermissionRuleOnNodeAndAllDescendants(tablePath, ReleaseNotesServiceDeclaration.SERVICE_KEY, ServicePermissionRule.ALWAYS_DISABLED)`. The effect: the dataset-level service is not inherited into any table or record Actions menu. The overrides must be declared first because EBX snapshots inherited permissions at registration.

**Access rights** (`ConnectorsAccess`, package `ebx`): the one place every entry point asks.

- Rights, each including the previous one: `READ`, `RUN`, `MANAGE`. `granted(session)` answers `MANAGE` for the system user, the built-in administrator and the designer role; otherwise the widest of the operator role (`RUN`), the viewer role (`READ`) and every row of the table `AccessRight` whose profile the session holds (`Session.isUserInRole`); null when nothing matches (also when the directory or the table throws).
- Table `/root/AccessRight` ("Access rights", primary key `/profile`): `profile` = a formatted `Profile` (`R<role>` or `Beveryone`) chosen in `ConnectorsRoleEnumeration` (the roles of the directory, listed through `DirectoryHandler.getProfiles` with a context obtained by reflection from `ProfileListContextBridge`; fallback to the default role names; `checkOccurrence` uses `Profile.parse` and `DirectoryHandler.isSpecificRoleDefined` only), `right` = `READ` / `RUN` / `MANAGE`, `comment`. Read by `AccessRightTable` (no session, cached 5 s, any failure = no row). An access rule of `ConnectorsSchemaExtensions` leaves it read-only to everyone but the built-in administrator. Not part of the configuration export/import. Default role names `ConnectorsViewer`, `ConnectorsOperator`, `ConnectorsDesigner`; properties `ebx.connectors.role.viewer|operator|designer`, the designer falling back to `ebx.connectors.adminRole`.
- MCP: `ConnectorsTools.invoke` asks `required(tool, arguments)` before anything else (before `ConnectorsInstaller.ensure`), so the HTTP endpoint, the in-JVM bridge and the native server share the check; a refusal is the tool error `Access denied: '<tool>' requires the role '<role>' ...` and a WARN in the kernel log. `McpEndpoint` lists only the tools `required(tool)` allows. A tool absent from the table asks `MANAGE`. `connectors_describe` with an `operation` and `connectors_catalog_source` with `apply=true` ask `MANAGE`.
- REST: every route of `ConnectorsResource` starts with `refused(route, right)` and answers `403`; `/catalog/preview` keeps its own administrator check; `POST /webhook/{feed}` asks `RUN` only when the caller comes with an EBX session (a call authenticated by the feed's secret or signature is unchanged); `GET /webhook/{feed}` is unchanged.
- User services: each declaration sets `ConnectorsAccess.rule(right)` as its permission rule (`ConnectorSync` and the three conflict managers `RUN`; `DeleteMapping`, `OpenApiImport`, `MappingDesigner`, `ConnectorDiagnostics` `MANAGE`; `ConnectorCatalog`, `ConnectorOperations` `READ`).
- In-JVM contract for sibling modules (§4.5.4): each operation declares its right in `ContractCatalog` (`llm.connectors`, `flows.get` READ; `llm.ask`, `llm.chat`, `flows.run` RUN; `flows.put`, `flows.delete` MANAGE; `contract.describe` none) and `ContractService` checks it with `granted(session)` before anything else.
- Not covered: workflow beans, scheduled tasks and the table trigger (they run configured mappings under the session EBX gives them), and direct reads of the Connectors dataset through EBX.

**Cross-site request guard** (`CsrfGuard`, package `ebx.rest`, a JAX-RS request filter registered by `ConnectorsRestApplication`): EBX accepts its session cookie on `/ebx-connectors/rest/...`, so a page of another site could make a signed-in user's browser send a route that changes something.

- Checked: every `POST`, `PUT`, `PATCH` and `DELETE` of the application, the MCP endpoint `/rest/mcp` included. `GET`, `HEAD` and `OPTIONS` are never checked.
- Let through when the request carries an `Authorization` header (Basic, token), or an `X-Requested-With` header (any value), or an `Origin` whose host and port are those of the request's own `Host` header (or of an entry of `X-Forwarded-Host`, for a reverse proxy that rewrites `Host`). Without an `Origin`, the `Referer` is compared the same way; with an `Origin`, the `Referer` is not read. A `Host` without a port matches an origin on the default port of its scheme; the scheme itself is not compared (a proxy may end TLS).
- `POST /v1/webhook/{feed}` has no exemption from the cross-site guard any more (C2-10, lot L7): like any POST it needs an `Authorization` header (or `X-Requested-With`, or a same-origin `Origin`/`Referer`), and the route then needs an EBX session with the RUN right.
- Anything else: `403` with `{"error": "Cross-site request protection: ..."}`, before the route runs, and a WARN in the kernel log (`[connectors] cross-site request refused: <method> <path> (Origin ..., Referer host ...)`).
- Who passes how: scripts and `curl -u` (Authorization); MCP clients and the other modules' HTTP clients (Authorization); the in-JVM bridge `InJvmMcp` (no HTTP, not concerned); the data catalog's preview pane, the only page that calls this API from the browser (`fetch('/ebx-connectors/rest/v1/catalog/preview', {method: 'POST'})`, same origin: the browser sends `Origin`); a webhook sender (its secret or signature header). The module's own screens do not call the REST API.
- Order: EBX's own filters (`RequestAuthenticationFilter`, pre-matching; `RequestAuthorizationFilter`, priority 2000) run before this one (default priority). Whether a request without any credentials is answered `401` by them or reaches this guard and gets `403` was not observed on an instance.
- Consequences: a client that sends its credentials only after a `401` challenge (non-preemptive Basic) may get this `403` on its first `POST` instead: send `Authorization` with the request. A caller authenticated by something other than `Authorization` (a header added by an SSO proxy, a cookie held by a script) must add `X-Requested-With`. The accepted content types of the routes are unchanged.
- Not covered: a browser that cached Basic credentials for this host after a browser login prompt sends `Authorization` by itself, also across sites; use the EBX login page, not a Basic prompt, in a browser.

**Audit line** (`ConnectorsAudit`, package `ebx`; `RestAudit`, package `ebx.rest`): one INFO line in the kernel log for every call that runs or changes something, i.e. that asks `RUN` or `MANAGE`. A call that asks `READ` is not traced.

- MCP tools, whatever the transport (HTTP endpoint, in-JVM bridge, native server): written by `ConnectorsTools.invoke` when the tool returns. `[connectors] audit: user=<login> tool=<tool> [<name>=<value> ...] outcome=ok|error|refused ms=<duration>`. `error` = the answer carries `error`; `refused` = the caller lacks the right (the WARN `access denied` is still written too).
- REST routes: announced by `ConnectorsResource.refused(route, right)` and written by the JAX-RS response filter `RestAudit` when the answer leaves (same thread; a request filter clears what an earlier request may have left). `[connectors] audit: user=<login> rest=<route> [<name>=<value> ...] outcome=ok|error|refused status=<HTTP status> ms=<duration>`; `ok` = status 2xx or 3xx. `<route>` is the name the route gives to `refused` (`sync`, `enrich`, `replay`, `stopRun`, `decide`, `deleteMapping`, `configImport`, `configExport`, `publish`, `importOpenApi`, `importTemplates`, `copyTemplate`, `upgradeTemplate`, `declareLineage`, `outbound`, `conflicts`, `catalogSource` with `apply=true`, `webhook`). A webhook call authenticated by the feed's secret or signature is traced as `rest=webhook-secret` under the account it runs as; one refused for lack of authentication (401) is not traced.
- What the line names: the user; for a tool the arguments `connector`, `operation`, `mapping`, `mappings`, `connectors`, `runId`, `id`, `decision`; for a route the path parameters `mapping`, `runId`, `template`, `feed`, `id`, `decision` and the query parameters `connector`, `mapping`, `mappings`, `connectors`, `id`; the flags `dryRun`, `apply`, `allowWrite` when true. For `connectors_mapping_upsert`, of the mapping document only `id` (or `(new)`), `connector` and `operation`.
- What it never carries: a document, an input, a configuration, a record key, a predicate, a URL, a label, a comment, a secret. A value is one token (spaces and control characters become `_`), cut after 80 characters; a JSON document sent where an identifier is expected is not written.
- The outcome is the call's, not each record's: a synchronisation whose answer lists rejected records is `ok`; the detail is in the run (`ExecutionRun`) and the sync log.
- Not traced by this line: `POST /catalog/preview` (its own administrator check, read-only), the module's screens (their runs are in the sync log and `ExecutionRun`), workflow beans, scheduled tasks and the table trigger, and changes made directly in the tables of the Connectors dataset (EBX history, when enabled on those tables).
- `ConnectorsAudit.use(Sink)` redirects the lines (tests); a sink or a filter that fails never fails the call.

**Rights on the tables (audit C-01, tables; `ConnectorsTableAccessRule`).** The designer role property (`-Debx.connectors.role.designer=<role name>`, older name `-Debx.connectors.adminRole`) only names the MANAGE role; it no longer switches a table rule on.

- `ConnectorsSchemaExtensions` calls `ConnectorsTableAccessRule.register(context)`, which walks the model: on every table, `setAccessRuleOnOccurrence(table, rule)` (except `AccessRight` and `Favorite`, which keep their own record rule, declared before) and, to close creation, `setAccessRuleForCreateOnNode(field, rule)` on every terminal field of the record (associations and selections skipped). A creation rule on the table node itself would be a compilation error of the whole model.
- `ConnectorsTableAccessRule.TABLES` gives each table a `Policy`: the level that reads it and the level that writes it by hand (`READ` < `RUN` < `MANAGE` < `ADMIN`, ADMIN = EBX administrator or system user). `decide(level, policy)`: from the write level up READ_WRITE, from the read level up READ_ONLY, otherwise HIDDEN. No session (the platform itself) is READ_WRITE. A table without a decision gets `DEFAULT` (read READ, write MANAGE). `ConnectorsTableAccessRuleTest` checks that every table of the XSD is decided.
- Configuration (`Connector`, `ConnectorProperty`, `ConnectorCategory`, `ConnectorTemplate`, `Mapping`, `MappingInput`, `MappingRule`, `SchemaRefreshState`, `ConnectorGovernance`, `ConnectorGovernanceAudit`): read READ, write MANAGE. Cleaned by a mapping deletion (`ConfigTransfer.cascade`, designer's transaction, no privileges) - `SynchronizationCheckpoint`, `FieldProvenance`: read READ, write MANAGE; `Conflict`, `OutboundState` (record values): read RUN, write MANAGE. Engine and packaged tables (`SyncLog`, `ExecutionRun`, `Publication`, `ConnectorOperationalMetrics`, the five release tables, `SeedMarker`): read READ, write ADMIN. `AccessRight` and `Favorite`: creation ADMIN, their own record rule.
- The engine is not stopped by the rule: every bookkeeping write runs with `setAllPrivileges(true)` (log, runs, provenance, outbound states, conflicts logged by a run, publication reports, and since this change the feed checkpoint in `ConnectorRepository.saveCheckpoint`) or with a system-user session (metrics, retention, run recovery, installers). A conflict decision (`ConflictResolver.accept/reject`) checks `ConnectorsAccess.has(session, RUN)` and then writes the conflict row with all privileges; the business record the accepted value goes into is still written with the caller's session and EBX permissions.
- **Credential values (audit C-05).** `ConnectorsSchemaExtensions` ends by declaring `setAccessRuleOnNode(/root/ConnectorProperty/value, new ConnectorPropertyValueAccessRule(true))`, a node rule that EBX combines with the record rule of the table (the most restrictive wins) (`decide`: MANAGE sees and edits; anybody else gets the value HIDDEN when `Redaction.isSecretKey(key)` and `-Debx.connectors.secrets.hideProperties` is not `false`, read-only otherwise). Which keys count as credentials is `Redaction.isSecretKey` (`secret`, `password`, `token`, `privatekey`, `apikey`, `.key` suffix, `passphrase`, `serviceaccountjson`, `accountkey`, `jaas`, `connectionstring`, `credential`, `pwd` suffix; `_`, `-` and case ignored), the function that also masks logs, MCP answers and the configuration export. The engine and the module's own code read the rows through `Adaptation` without a session's access rules, so they are unaffected. Of the `Connector` table, `password` and `apiKey` are `osd:password` with EBX's reversible encryption; `baseUrl` (webhook URLs, JDBC URLs with credentials in the query), `username` and `sampleData` are plain.

#### 4.1.5 `ConnectorsInstaller` — creation, seeding, migration

**Entry points**

- `install(repository, session, moduleName, log)` is `synchronized`. It clears the per-run seed maps and catches every `Throwable` (logged as `[connectors] installation failed: ...`).
- `ensure(repository, session)` is the lazy entry point. It calls `install(..., ConnectorsPaths.MODULE_NAME, LoggingCategory.getKernel())`. It deliberately runs every time, even when the dataset exists, so that model upgrades get their additive data.
- `ensure` is called by: every REST handler (except `GET /webhook/{feed}` and the start of `POST /webhook/{feed}` before authentication), every MCP tool invocation, both scheduled tasks, the workflow bean, `ConnectorTableTrigger`, `ConnectorSyncUserService`, `ConflictManagerUserService`, and `OpenApiImport.run` (non-dry-run).

**Dataspace and dataset creation**

1. If the `Connectors` branch is missing, it is created under the reference branch. Owner `Profile.ADMINISTRATOR`, label `Connectors`, description `External application connectors: endpoints, mappings, sync log.`. Log: `dataspace Connectors created`.
2. If a `Connectors` dataset exists but its model is packaged in another module (`belongsTo` false), installation **stops**. Nothing is modified. Error log: `installation stopped: dataset Connectors uses the data model of another module (<schema>), not <module>. Existing data was preserved. Export it, migrate the model explicitly, then restart.`
   - `belongsTo` returns true when the module name is null, when the model is not packaged in a module, or when the module names match.
   - If the schema location cannot be read, it fails closed (returns false).
3. If the dataset is missing, it is created with `doCreateRoot(SchemaLocation.forPathInModule(MODEL_PATH, moduleName), "Connectors", Profile.ADMINISTRATOR)`, all privileges, instance label `Connectors`. On failure the error is logged and installation returns.

**Seed sequence.** Every step has its own guard, so re-running is idempotent.

- "rev3" means the `Mapping` table has a `./parent` node.
- `seedPolicy()` reads `-Debx.connectors.seedMappings`: `auto` (default; any unknown value), `always`, or `never`.

| Step | Guard | Content |
|---|---|---|
| `seed` | `Connector` table empty | GLEIF, Sirene, GoogleGeocoding + Companies mappings |
| `seedSalesforce` | no `Salesforce` row | Salesforce + 2 mappings |
| `seedKafka` | no `Kafka` row and rev3 | Kafka + composite mappings |
| `seedSnowflake` | no `Snowflake` row and rev3 | |
| `seedDatabricks` | no `Databricks` row and rev3 | |
| `seedSap` | no `SAP` row and rev3 | |
| `seedJde` | no `JDEdwards` row and rev3 | |
| `seedDnb` | no `DnB` row and rev3 | |
| `seedCatalog` | (any of `VIES`, `SQLServer`, `AzureSQL`, `AzureServiceBus`, `NetSuite`, `SharePoint`, `MySQL` missing) and rev3 | 35 connectors, every one created only if missing |
| `seedLot2` | no `OpenFDA` row | 9 connectors, each if missing |
| `seedLlmProviders` | `Gemini` or `Mistral` missing | 5 LLM connectors, each if missing |
| `seedPowerBi` | no `PowerBI` row | |
| `seedCollibra` | no `Collibra` row | |
| `seedAddressNormalisation` | no mapping with `./connector='Melissa'` and a `Melissa` connector exists | Melissa (+ Loqate) mappings |
| `migrate` | always, after every seed | additive fixes |
| `seedReleaseNotes` | always, if the `Release` table exists | packaged release records |

Each step runs in its own `ProgrammaticService` procedure with `setAllPrivileges(true)`. A failure is logged (`<X> seed failed: ...`) and the following steps still run.

**Connector row written by `connector(...)`**

- Fields set: `id`, `label`, `type`, `baseUrl`, `authType`, `apiKeyHeader`, `apiKey`, `description`, `active=true`.
- `category` = `seedCategory(id, type)`, only if the field exists.
- `trust` = `defaultTrust(type)`, only if the field exists. The model default is 50, so trust is always set explicitly at seed time.
- `logo` = `Logos.preferredFor(id)`, if the field exists and a logo is known.
- `connectorIfMissing(...)` additionally writes `username`/`password` when either is non-null, then one `ConnectorProperty` row per `{key, value}`.

**`defaultTrust(type)`**

- Legacy table first:

  | Trust | Types |
  |---|---|
  | 90 | gleif, sirene, companies-house, inpi |
  | 85 | dnb, orbis, handelsregister, openfda |
  | 80 | vies, creditsafe, opensanctions |
  | 75 | melissa, loqate |
  | 70 | ban, opencorporates, sap-odata, jde-ais, ebx |
  | 60 | google-geocoding, salesforce, dynamics365, oracle-fusion, hubspot, servicenow, workday |
  | 40 | wikidata |
  | 30 | llm |

- Otherwise `DescriptorConfiguration.trustLevel(descriptor)`.
- Otherwise 50. An exception while resolving the descriptor is ignored.

**`seedCategory(id, type)`**

- Explicit values: `Countries` → `REFERENCE_DATA`; `EORI` → `RISK_COMPLIANCE`.
- Otherwise `DescriptorConfiguration.category(descriptor)`.
- Otherwise the legacy table:

  | Category | Types |
  |---|---|
  | AI | llm |
  | CLOUD_STORAGE | s3, azure-blob, gcs, sftp |
  | DATABASE | jdbc, postgresql, mysql, h2, oracle-database, sql-server, azure-sql |
  | DATA_PLATFORMS | bigquery, snowflake, databricks, powerbi, collibra |
  | MESSAGING | kafka, rabbitmq, jms, activemq, azure-service-bus, webhook, chat-webhook |
  | BUSINESS_APPS | salesforce, dynamics365, dynamics-business-central, oracle-fusion, netsuite, sharepoint, hubspot, servicenow, workday, sap-odata, sap-successfactors, sap-ariba, jde-ais |
  | REFERENCE_DATA | ban, google-geocoding, loqate, melissa, wikidata, nominatim |
  | RISK_COMPLIANCE | opensanctions, openfda, vies, creditsafe |
  | CORPORATE_REGISTRIES | gleif, sirene, companies-house, dnb, orbis, handelsregister, inpi, opencorporates |
  | GENERIC_INTEGRATION | anything else |

**Mapping seed helpers and the seed policy**

`mapping(...)`:

- `never`: skipped (returns -1).
- `auto`: the target table is resolved with `ModelCheck.table`. If the table is missing, the mapping is skipped. The log line says `seed: mapping '<label>' skipped, table <path> not found in dataset <ds> (set -Debx.connectors.seedMappings=always to seed it anyway)`. If any input expression references a `${./...}` field the table lacks, the mapping is skipped with a log line.
- When created: `connector`, `operation`, `direction`, `label`, `dataspace` (null for every seed), `dataset`, `tablePath`, `responseRoot`, `active=true`, then one `MappingInput` per input.

`childMapping(...)`:

- Skipped if the parent was skipped.
- In non-`always` modes, skipped when the child table is missing, or when the link field or key fields are missing.
- Writes `parent`, `collectionPath`, `parentLinkField`, `childKeyFields` (null if empty), `childFilter` (null if empty), `deleteMissing=false`, `active=true`.
- Note: with `never`, `childMapping` does not check the policy itself. It is only skipped because its parent returned -1.

`feedMapping(...)`: creates an INBOUND mapping, then sets `itemsPath`, `keyPredicate` and `createIfMissing`.

`rules(...)`:

- Positions 10, 20, 30…; `active=true`.
- The EBX-side path is `targetPath` for inbound and `sourcePath` for outbound. When a target table was resolved (non-`always`) and that path is not a field, the rule is dropped with a log line.

**Seeded connectors (61 in total).** Secrets are `TO_BE_SET` unless noted as none.

| Id | Label | Type | Base URL | Auth | Header / key / user / password | Properties |
|---|---|---|---|---|---|---|
| GLEIF | GLEIF - Legal Entity Identifier | gleif | https://api.gleif.org/api/v1 | NONE | — | — |
| Sirene | INSEE Sirene 3.11 | sirene | https://api.insee.fr/api-sirene/3.11 | API_KEY_HEADER | `X-INSEE-Api-Key-Integration` / key TO_BE_SET | — |
| GoogleGeocoding | Google Geocoding | google-geocoding | https://maps.googleapis.com/maps/api | NONE | key TO_BE_SET | — |
| Salesforce | Salesforce CRM | salesforce | https://login.salesforce.com | NONE | key TO_BE_SET | clientId=TO_BE_SET, oauth.flow=client_credentials, apiVersion=v60.0 |
| Kafka | Apache Kafka | kafka | localhost:9092 | NONE | — | topic=ebx.companies, group.id=ebx-connectors, envelope=false, key.field=lei |
| Snowflake | Snowflake | snowflake | https://ACCOUNT.snowflakecomputing.com | BEARER | key TO_BE_SET | tokenType=PAT, warehouse=COMPUTE_WH, database=MDM, schema=PUBLIC, max.rows=1000 |
| Databricks | Databricks SQL | databricks | https://adb-0000000000000000.0.azuredatabricks.net | BEARER | key TO_BE_SET | warehouse_id=TO_BE_SET, catalog=main, schema=mdm, max.rows=1000 |
| SAP | SAP S/4HANA Business Partner (OData) | sap-odata | https://sandbox.api.sap.com/s4hanacloud/sap/opu/odata/sap/API_BUSINESS_PARTNER | API_KEY_HEADER | `APIKey` / TO_BE_SET | odata.version=2, entitySet=A_BusinessPartner, max.rows=200 |
| JDEdwards | JD Edwards EnterpriseOne (AIS) | jde-ais | https://jde-ais.example.com:9080/jderest | BASIC | user JDE_USER / password TO_BE_SET | ais.version=v2, environment=JDV920, max.rows=500 |
| DnB | Dun & Bradstreet Direct+ | dnb | https://plus.dnb.com | BASIC | user TO_BE_SET / password TO_BE_SET | blockIDs=companyinfo_L2_v1,hierarchyconnections_L1_v1; confidence.min=7; country=FR; registration.reference, registration.path, notifications.path=TO_BE_SET |
| VIES | VIES (EU VAT check) | vies | https://ec.europa.eu/taxation_customs/vies/rest-api | NONE | — | — |
| OpenCorporates | OpenCorporates | opencorporates | https://api.opencorporates.com/v0.4 | API_KEY_HEADER | `api_token` / TO_BE_SET | — |
| CompaniesHouse | Companies House (UK) | companies-house | https://api.company-information.service.gov.uk | API_KEY_HEADER | (no header) / TO_BE_SET | — |
| Handelsregister | Handelsregister (OffeneRegister, DE) | handelsregister | https://db.offeneregister.de | NONE | — | — |
| Orbis | Moody's Orbis | orbis | https://api.bvdinfo.com/v1/orbis | API_KEY_HEADER | `ApiToken` / TO_BE_SET | select=NAME,ADDRESS_LINE1,POSTCODE,CITY,COUNTRY_ISO_CODE,NATIONAL_ID,VAT_NUMBER,LEI,EMPL,OPRE,STATUS |
| Creditsafe | Creditsafe Connect | creditsafe | https://connect.creditsafe.com/v1 | BASIC | user/password TO_BE_SET | countries=FR |
| BAN | Base Adresse Nationale (FR) | ban | https://api-adresse.data.gouv.fr | NONE | — | score.min=0.5 |
| Loqate | Loqate | loqate | https://api.addressy.com | API_KEY_HEADER | (no header) / TO_BE_SET | geocode=true, verification.min=V |
| Melissa | Melissa Data | melissa | https://address.melissadata.net | API_KEY_HEADER | (no header) / TO_BE_SET | country=FR, verified.only=true |
| OpenSanctions | OpenSanctions (OFAC, UN, EU, PEP) | opensanctions | https://api.opensanctions.org | API_KEY_HEADER | `Authorization` / TO_BE_SET | dataset=default, threshold=0.7 |
| Dynamics365 | Microsoft Dynamics 365 | dynamics365 | https://ORG.crm4.dynamics.com | BEARER | key TO_BE_SET | tenantId=TO_BE_SET, clientId=TO_BE_SET, apiVersion=v9.2 |
| OracleFusion | Oracle Fusion Cloud | oracle-fusion | https://HOST.oraclecloud.com | BASIC | user/password TO_BE_SET | family=fscmRestApi, apiVersion=11.13.18.05 |
| HubSpot | HubSpot CRM | hubspot | https://api.hubapi.com | BEARER | key TO_BE_SET | — |
| ServiceNow | ServiceNow | servicenow | https://INSTANCE.service-now.com | BASIC | user/password TO_BE_SET | display.values=false |
| Workday | Workday | workday | https://wd3-impl-services1.workday.com | BASIC | key, user, password TO_BE_SET | tenant, clientId, refreshToken=TO_BE_SET |
| BigQuery | Google BigQuery | bigquery | https://bigquery.googleapis.com | NONE | — | project, dataset, serviceAccountFile=TO_BE_SET |
| JDBC | JDBC database | jdbc | jdbc:postgresql://localhost:5432/mdm | BASIC | user/password TO_BE_SET | max.rows=1000 |
| PostgreSQL | PostgreSQL | postgresql | jdbc:postgresql://localhost:5432/mdm | BASIC | user/password TO_BE_SET | driver=org.postgresql.Driver, schema=public, max.rows=1000, fetch.size=100, batch.size=100 |
| H2 | H2 Database | h2 | jdbc:h2:tcp://localhost:9092/mdm | BASIC | user `sa` / password TO_BE_SET | driver=org.h2.Driver, schema=PUBLIC, max.rows=1000, fetch.size=100, batch.size=100 |
| MySQL | MySQL Database | mysql | jdbc:mysql://HOST:3306/MDM | BASIC | user/password TO_BE_SET | driver=com.mysql.cj.jdbc.Driver, jdbc.sslMode=VERIFY_IDENTITY, jdbc.allowPublicKeyRetrieval=false, jdbc.useServerPrepStmts=true, jdbc.serverTimezone=UTC, max.rows=1000, fetch.size=100, batch.size=100 |
| SQLServer | Microsoft SQL Server | sql-server | jdbc:sqlserver://HOST:1433;databaseName=MDM | BASIC | user/password TO_BE_SET | driver=com.microsoft.sqlserver.jdbc.SQLServerDriver, schema=dbo, jdbc.encrypt=true, jdbc.trustServerCertificate=false, max.rows=1000, fetch.size=100, batch.size=100 |
| AzureSQL | Azure SQL Database | azure-sql | jdbc:sqlserver://SERVER.database.windows.net:1433;databaseName=MDM | BASIC | user/password TO_BE_SET | as SQLServer + jdbc.hostNameInCertificate=*.database.windows.net |
| AzureServiceBus | Microsoft Azure Service Bus | azure-service-bus | sb://NAMESPACE.servicebus.windows.net | NONE | key TO_BE_SET | auth.mode=CONNECTION_STRING, entity=TO_BE_SET, receive.mode=PEEK_LOCK, max.messages=100, wait.ms=5000 |
| BusinessCentral | Microsoft Dynamics 365 Business Central | dynamics-business-central | https://api.businesscentral.dynamics.com | OAUTH2 | user/password TO_BE_SET | tenantId=TO_BE_SET, environment=Production, companyId=TO_BE_SET, apiVersion=v2.0, max.rows=200 |
| SuccessFactors | SAP SuccessFactors | sap-successfactors | https://HOST.successfactors.com | OAUTH2 | user/password TO_BE_SET | companyId=TO_BE_SET, max.rows=200 |
| SAPAriba | SAP Ariba | sap-ariba | https://openapi.ariba.com | API_KEY_HEADER | `apiKey` / TO_BE_SET | realm=TO_BE_SET, max.rows=200 |
| NetSuite | Oracle NetSuite | netsuite | https://ACCOUNT.suitetalk.api.netsuite.com/services/rest | BEARER | key TO_BE_SET | account=TO_BE_SET, max.rows=200 |
| SharePoint | Microsoft SharePoint Online | sharepoint | https://graph.microsoft.com/v1.0 | OAUTH2 | user/password TO_BE_SET | tenantId=TO_BE_SET, max.rows=200, max.pages=20, max.content.bytes=10485760 |
| OracleDatabase | Oracle Database | oracle-database | jdbc:oracle:thin:@//localhost:1521/FREEPDB1 | BASIC | user/password TO_BE_SET | driver=oracle.jdbc.OracleDriver, schema=TO_BE_SET, max.rows=1000, fetch.size=100, batch.size=100 |
| SFTP | SFTP server | sftp | sftp://HOST:22 | BASIC | user/password TO_BE_SET | root=/, separator=; |
| RabbitMQ | RabbitMQ | rabbitmq | amqps://localhost:5671 | BASIC | user/password TO_BE_SET | queue=ebx.master-data, exchange=(empty), routing.key=ebx.master-data, prefetch=100, max.messages=100 |
| S3 | Amazon S3 | s3 | (empty) | BASIC | user/password TO_BE_SET | bucket=TO_BE_SET, region=eu-west-3 |
| AzureBlob | Azure Blob Storage | azure-blob | https://ACCOUNT.blob.core.windows.net | API_KEY_HEADER | (no header) / TO_BE_SET | container=TO_BE_SET |
| GCS | Google Cloud Storage | gcs | https://storage.googleapis.com | NONE | — | bucket, serviceAccountFile=TO_BE_SET |
| Webhook | Inbound webhook | webhook | (empty) | API_KEY_HEADER | `X-Webhook-Secret` / key, user, password TO_BE_SET | sample=`{"items":[{"siren":"440676559","name":"ACME SAS","city":"Paris","postcode":"75008","country":"FR","website":"https://acme.example"}]}` |
| Claude | Claude (Anthropic) - AI enrichment | llm | https://api.anthropic.com | BEARER | key TO_BE_SET | model=claude-sonnet-5, maxTokens=1024, temperature=0, language=the language of the text |
| EORI | EORI validation (EU customs, SOAP) | soap | https://ec.europa.eu/taxation_customs/dds2/eos/validation/services/validation | NONE | — | soap.version=1.1; op.validate.action=(empty); op.validate.body=`<ev:validateEORI xmlns:ev="http://eori.ws.eos.dds.s/"><ev:eori>{eori}</ev:eori></ev:validateEORI>`; op.validate.result=result; op.validate.description=Validate an EORI number; op.validate.fields=eori=string;status=number;statusDescr=string;name=string;street=string;postalCode=string;city=string;country=string |
| Countries | Countries (GraphQL reference data) | graphql | https://countries.trevorblades.com/graphql | NONE | — | op.country.query=`query($code: ID!) { country(code: $code) { code name native capital currency emoji phone continent { name } languages { name } } }`; op.country.result=country; op.country.description=Country by ISO-2 code; op.country.fields=code,name,native,capital,currency,emoji,phone (string), continent.name, languages[0].name |
| EBX | EBX (another instance) | ebx | http://localhost:8080/ebx-dataservices/rest/data/v1 | BASIC | user/password TO_BE_SET | dataspacePrefix=B |
| ChatWebhook | Slack / Teams notifications | chat-webhook | https://hooks.slack.com/services/TO_BE_SET | NONE | — | format=auto |
| Slack | Slack | slack | https://slack.com/api | BEARER | key TO_BE_SET (bot token `xoxb-…`) | channel=TO_BE_SET |
| JMS | JMS messaging | jms | tcp://BROKER:61616 | NONE | — | initial.context.factory=TO_BE_SET, connection.factory=ConnectionFactory, destination=TO_BE_SET, destination.type=QUEUE, acknowledgement=CLIENT, max.messages=100, receive.timeout.ms=3000, redelivery.max=6, redelivery.delay.ms=1000 |
| ActiveMQ | Apache ActiveMQ | activemq | tcp://localhost:61616 | NONE | — | initial.context.factory=org.apache.activemq.jndi.ActiveMQInitialContextFactory, connection.factory=ConnectionFactory, destination=dynamicQueues/ebx.connectors, other values as JMS |
| INPI | INPI - Registre National des Entreprises | inpi | https://registre-national-entreprises.inpi.fr/api | BASIC | user/password TO_BE_SET | token.ttl=3000 |
| OpenFDA | openFDA (US FDA devices and drugs) | openfda | https://api.fda.gov | NONE | — | — |
| OpenAI | OpenAI - AI enrichment | llm | https://api.openai.com | BEARER | key TO_BE_SET | provider=openai, model=gpt-4o-mini, maxTokens=1024, temperature=0 |
| Gemini | Gemini (Google) - AI enrichment | llm | https://generativelanguage.googleapis.com | API_KEY_HEADER | `x-goog-api-key` / TO_BE_SET | provider=google, model=gemini-2.5-flash, maxTokens=1024, temperature=0 |
| AzureOpenAI | Azure OpenAI (Microsoft) - AI enrichment | llm | https://TO_BE_SET.openai.azure.com | API_KEY_HEADER | `api-key` / TO_BE_SET | provider=azure, model=gpt-4o-mini, azure.apiVersion=2024-10-21, maxTokens=1024, temperature=0 |
| Mistral | Mistral AI - AI enrichment | llm | https://api.mistral.ai | BEARER | key TO_BE_SET | provider=mistral, model=mistral-small-latest, maxTokens=1024, temperature=0 |
| Ollama | Ollama (local models) - AI enrichment | llm | http://localhost:11434 | NONE | — | provider=ollama, model=llama3.2, maxTokens=1024, temperature=0 |
| PowerBI | Microsoft Power BI | powerbi | https://api.powerbi.com/v1.0/myorg | OAUTH2 | key TO_BE_SET | tenantId=TO_BE_SET, clientId=TO_BE_SET, scanner=auto, datasource.map=(empty) |
| Collibra | Collibra Data Intelligence Platform | collibra | https://INSTANCE.collibra.com | BASIC | user/password TO_BE_SET | community=Data Governance Council, domain.type=Physical Data Dictionary, term.type=Business Term |

Each row also receives the English `description` text from the installer. Those texts document the connector's usage and its properties.

**Seeded demonstration mappings.** All target dataset `Companies`, dataspace empty. Rule tuples are written as `source → target [transformation] POLICY`.

- **GLEIF `lei`**, INBOUND, "GLEIF record -> company legal information".
  - responseRoot `data.attributes`; input `lei=${./Identification/LegalEntityIdentifier}`.
  - 17 rules:
    - `entity.legalName.name → ./LegalInformation/legalName [trim|left(255)]`
    - `entity.legalForm.id → ./LegalInformation/legalForm [fk(/root/ReferenceData/LegalForm)]`
    - `entity.category → ./LegalInformation/entityCategory [map(...6 identical values)]`
    - `entity.jurisdiction → ./LegalInformation/jurisdiction [left(2)|upper]`
    - `entity.registeredAt.id → ./LegalInformation/registrationAuthority [fk(/root/ReferenceData/RegistrationAuthority)]`
    - `entity.registeredAs → registrationNumber [trim|left(80)]`
    - `entity.creationDate → incorporationDate [isodate]`
    - `registration.status → ./Lifecycle/leiStatus [trim] ALWAYS`
    - `registration.nextRenewalDate → leiNextRenewalDate [isodate] ALWAYS`
    - `entity.status → leiEntityStatus [map(ACTIVE=ACTIVE;INACTIVE=INACTIVE)] ALWAYS`
    - `entity.status → ./Lifecycle/status [map(INACTIVE=CLOSED)]`
    - `registration.lastUpdateDate → ./Governance/gleifLastUpdate [isodatetime] ALWAYS`
    - `headquartersAddress.addressLines → ./Address/Street [join(', ')|left(80)]`
    - postalCode / city `[left(80)]`
    - country `[left(2)|upper]`
    - constant `GLEIF → ./Governance/enrichmentSource`
  - Unmarked rules are IF_EMPTY.
- **GLEIF `searchByRegistration`**, "GLEIF search by SIREN -> LEI".
  - root `data`; inputs `registrationId=${./LegalInformation/registrationNumber}`, `registrationAuthority=RA000189`.
  - Rules: `id → LegalEntityIdentifier [trim|upper]`; `attributes.entity.legalName.name → legalName [trim|left(255)]`.
- **Sirene `siren`**, "Sirene legal unit -> company".
  - root `uniteLegale`; input `siren=${./LegalInformation/registrationNumber}`.
  - Rules: denomination → legalName; dateCreation → incorporationDate `isodate`; activitePrincipale → naceCode twice (`left(5)|fk(NaceCode)` then `left(2)|fk(NaceCode)`); categorieEntreprise → sizeCategory `map(PME=MEDIUM;ETI=LARGE;GE=VERY_LARGE)`; etatAdministratif → status `map(C=CLOSED)`; constants FR → jurisdiction and RA000189 → registrationAuthority.
- **Sirene `headOffice`**, "Sirene head office -> company address".
  - root `etablissement.adresseEtablissement`.
  - Rules: numero → Number; libelleVoie → Street; codePostal → Zipcode; libelleCommune → City `capitalize|left(80)`; constant FR → Country.
- **GoogleGeocoding `geocode`**.
  - root `results[0]`; input `address=${./Address/Number} ${./Address/Street}, ${./Address/Zipcode} ${./Address/City}, ${./Address/Country}`.
  - Rules: `geometry.location.lat/lng → latitude/longitude [round(7)]`.
- **Salesforce `getByExternalId`**, INBOUND, "Salesforce Account -> company".
  - Inputs `sobject=Account`, `externalIdField=LEI__c`, `value=${LEI}`.
  - Rules: Name, Website, Phone, Billing* address fields, NumberOfEmployees `number`, and `Id → enrichmentSource [constant(SALESFORCE)]`.
- **Salesforce `upsert`**, OUTBOUND, "Company -> Salesforce Account". Same inputs; 9 ALWAYS rules including `naceCode → Industry [lookup(/root/ReferenceData/NaceCode,./Code,./Label)]`.
- **Kafka `publish`**, OUTBOUND, composite.
  - Inputs `table=Companies`, `key=${LEI}`; 11 root rules.
  - Children: `/root/CompanyAddress` collection `addresses`, link `./company` (8 rules); `/root/Contact` collection `contacts`, filter `./active=true` (6 rules).
- **Kafka `consume`**, INBOUND feed.
  - itemsPath `messages[*].value`; keyPredicate `./Identification/LegalEntityIdentifier='${lei}'`; createIfMissing true; input `topic=ebx.companies.in`; 10 ALWAYS rules.
  - Children: addresses (keys `./type,./City`, filter `./source='KAFKA'`, `type` rule `upper|default(OTHER)`, constant source KAFKA); contacts (keys `./email`, role `upper|fk(/root/ReferenceData/ContactRole)`, `active` constant true IF_EMPTY).
- **Snowflake `query`** feed, "Snowflake CUSTOMERS -> companies".
  - itemsPath `rows`, key `...LEI='${LEI}'`; input `sql=SELECT LEI, NAME, LEGAL_NAME, COUNTRY, CITY, WEBSITE, NACE, STATUS FROM CUSTOMERS WHERE LEI IS NOT NULL`.
- **Snowflake `merge`** OUTBOUND. Inputs `table=COMPANIES`, `keyColumn=LEI`; 11 rules; child ADDRESSES (VARIANT).
- **Databricks `query` / `merge`**: same shape as Snowflake, lower-case columns, table `companies`, key `lei`.
- **SAP `query`** feed.
  - itemsPath `results`, key `registrationNumber='${BusinessPartner}'`.
  - Inputs `entitySet=A_BusinessPartner`, `$filter=BusinessPartnerCategory eq '2'`, `$expand=to_BusinessPartnerAddress`, `$top=50`.
  - Child `to_BusinessPartnerAddress` (keys `./type,./City`, filter `./source='SAP'`).
- **SAP `create`** OUTBOUND deep insert (`entitySet=A_BusinessPartner`), with child `to_BusinessPartnerAddress`.
- **JDEdwards `query`** feed.
  - Inputs `table=F0101`, `columns=AN8|ALPH|AT1|TAX|TXCT|MCU`, `filter=AT1 EQUAL C`; key `registrationNumber='${AN8}'`.
- **JDEdwards `orchestration`** OUTBOUND, `name=EBX_UpsertAddressBook`, child `Addresses`.
- **DnB `enrich`** INBOUND, root `organization`.
  - Inputs registrationNumber, name, country, city; 15 rules.
  - Children: `/root/CompanyIdentifier` (DUNS; keys `./type`, filter `./type='DUNS'`); `/root/CompanyRelationship` collection `corporateLinkage.globalUltimate`, link `./fromCompany`, filter `./source='DNB'`.
- **VIES `check`**: input `vat=${./LegalInformation/registrationNumber|vatfr}`; child VAT identifier.
- **BAN `geocode`**: inputs `address`, `postcode`.
- **OpenSanctions `screen`**: inputs name, registrationNumber, country, `schema=Company`.
  - Rules: `bestSummary.datasets → blacklistReason [prefix(Sanctions hit: )|left(255)]`; `hit → status [map(true=SUSPENDED)]`.
- **Webhook `receive`** feed: itemsPath `items`, key `registrationNumber='${siren}'`, no input.
- **Melissa `address`** and **Loqate `verify`** (seedAddressNormalisation).
  - Inputs address, locality, postalCode, country.
  - Address lines ALWAYS; country and coordinates IF_EMPTY; constant MELISSA / LOQATE.
  - The Loqate mapping is created only if the Loqate connector exists.

**`migrate(...)`** — additive; never overwrites a non-null customer value.

1. If the `ConnectorCategory` table and `Connector/category` exist, create the missing categories:

   | Code | Label | Description | Order |
   |---|---|---|---|
   | AI | AI & machine learning | Language models and intelligent enrichment services | 10 |
   | BUSINESS_APPS | Business applications | CRM, ERP, HCM and enterprise SaaS platforms | 20 |
   | CLOUD_STORAGE | Cloud & file storage | Object storage, managed files and secure file transfer | 30 |
   | DATA_PLATFORMS | Data platforms | Databases, warehouses and lakehouse platforms | 40 |
   | DATABASE | Database | Relational databases and managed SQL services | 45 |
   | MESSAGING | Messaging & events | Event streams, webhooks and notifications | 50 |
   | REFERENCE_DATA | Location & reference data | Address, geocoding and public reference sources | 60 |
   | CORPORATE_REGISTRIES | Corporate registries | Company identity and official registry services | 70 |
   | RISK_COMPLIANCE | Risk & compliance | Verification, sanctions, tax and regulatory sources | 80 |
   | GENERIC_INTEGRATION | Generic integration | General-purpose APIs and EBX-to-EBX connectivity | 90 |

2. For every `Connector` row:
   - `authType` `OAUTH2_CLIENT_CREDENTIALS` → `OAUTH2`.
   - `username`/`password`/`apiKey` equal to `A_RENSEIGNER` → `TO_BE_SET`.
   - `logo` set to `Logos.preferredFor(id)` when empty. The `EBX` connector's logo is also replaced when it equals the generated tile.
   - `trust` set to `defaultTrust(type)` when null.
   - `category` set to `seedCategory(id, type)` when null.
3. For every `Mapping`: `dataspace` is normalised with `resolveMappingDataspace`.
   - Pure decision `decideMappingTarget`. A BLANK value is left blank and nothing is looked up: an empty dataspace is a generic mapping (it applies to a record wherever the dataset exists, workflow child dataspaces included), and filling it would make every such mapping specific.
   - A stored value is tried as is, then with a leading `B` removed (only when the stripped key resolves); when found in a branch, the branch name is stored if it differs.
   - A stored value that no longer resolves is NEVER replaced (the dataspace may have been deleted, and another project's dataset of the same name must not take its place): it is left as stored and a WARN line names the mapping id (`dataspace 'X' no longer resolves dataset …: left as stored`). Before wave 13 it was silently re-pointed to the first dataset of that name found anywhere in the repository.
4. `ConnectorProperty` rows with `value='A_RENSEIGNER'` → `TO_BE_SET`.
5. Log line: `migration: N placeholder(s) set to TO_BE_SET, N trust level(s) initialised, N logo(s) assigned, N category value(s) created or assigned, N mapping target dataspace value(s) normalised`.

**`seedReleaseNotes(...)`** (with `PackagedReleaseNotes`)

- Reads `/com/on/ebx/connect/release/release-notes.xml` with a hardened XML parser: DOCTYPE disallowed, no external entities, no external DTD or schema.
- For each `<release>`:
  - `kind="formal"` → version = `version` attribute, status `CURRENT`.
  - Otherwise version = `id` attribute, status `IN_PROGRESS`. The XML `status` attribute is ignored.
  - releaseDate = `releasedOn`.
- Item mapping:
  - Section `category` maps to type: `fixed`→FIX, `security`→SECURITY, `known-issue`→KNOWN_ISSUE, `upgrade`→UPGRADE, anything else→FEATURE.
  - Item id = explicit `id`, else `<category>-<order>`. Order counts 10, 20… across the release. Title = detail = item text.
- Metrics have order 10, 20…
- Passes: status enum is upper-cased with `-`→`_`, and `PENDING`→`PLANNED`. `remainingGates` comes from the attribute, else the child element, else the direct text.
- Steps come from `<step>` under `<pass>` (label = `label` attribute or text) and from `<substeps passCode>/<substep id>`.
- Ownership rules:
  - A release header is created only if no row with that version exists.
  - Children are seeded only when the release is absent or has ownership `PACKAGED`.
  - Each child is created only if missing. Existing rows are never modified.
  - New rows carry ownership `PACKAGED`.

**Other helpers**

- `needsSeed(dataset)` exists but has no caller.
- `tables()` returns 6 table paths "for diagnostics" and has no caller in scope.

#### 4.1.6 `PackagedProductProfileInstaller`

- Obtains the dataset via `ConnectorRepository.dataset()`. If it is missing: warn `packaged profiles skipped: Connectors dataset is unavailable`.
- For each `ProductConnectorTemplates.packaged()` template, it checks whether the connector named by `configuration.connectorId` exists.
  - Templates whose connector is absent are skipped with warn `packaged profile <id> skipped: connector <cid> is not installed`.
- Available templates are imported one by one: `ConnectorTemplateService(new EbxConnectorTemplateStore(...)).importTemplates(exportTemplates([t]), actor "ebx-connectors-packaged-profile-installer")`.
  - A `RuntimeException` (for example a conflicting customer-owned id) logs `packaged profile <id> was not installed: ...` and does not block the others.
- Summary log: `packaged product profiles ready: N validated, M installed or upgraded`.

#### 4.1.7 EBX persistence adapters (top-level classes)

| Class | Table | Behaviour |
|---|---|---|
| `EbxConnectorTemplateStore` | `ConnectorTemplate` | See details below. |
| `EbxConnectorConnectionStateStore` | `Connector` (connection* fields) | See details below. |
| `EbxConnectorOperationalMetricsPersistence` | `ConnectorOperationalMetrics` | See details below. |
| `EbxRestSchemaRefreshStore` | `SchemaRefreshState` | See details below. |
| `EbxSynchronizationCheckpointStore` | `SynchronizationCheckpoint` (via `ConnectorRepository.checkpoint/saveCheckpoint`) | Optimistic `expectedVersion`. No caller in `main` (tests only). |
| `EbxRecoverableExecutionStore` | `ExecutionRun` | See details below. No caller in `main`. |
| `EbxConnectorGovernanceStore` | `ConnectorGovernance`, `ConnectorGovernanceAudit` | See details below. No caller in `main`. |

**`EbxConnectorTemplateStore`**

- Missing table → `IOException "ConnectorTemplate table is unavailable; reload the Connectors data model"`.
- `list()` is sorted by id.
- `create` refuses an existing id: `Template id already exists: <id>`.
- `replacePackaged` requires both the new template and the existing row to be `PACKAGED`: `Packaged upgrade cannot replace customer template <id>`.
- Writes `id, version, ownership, basedOn, basedOnVersion` (null if < 1), `configuration` (JSON), `createdBy/At` (on creation only), `updatedBy/At`.
- No `setAllPrivileges`: the caller's permissions apply.
- An invalid JSON configuration on read → `Template <id> has invalid configuration`.

**`EbxConnectorConnectionStateStore`**

- `load`: status parsed from `connectionStatus` (unknown values → `NOT_CONNECTED`), plus `connectionVerifiedAt`, `connectionFingerprint`, `connectionMessage`.
- `save` writes the four fields.
- Errors: unknown connector → `Connector '<id>' does not exist`; missing dataset → `Connectors dataset is unavailable`; write rejected → `EBX rejected connector connection state`.
- `STALE` is never stored. `ConnectorConnectionStateService` (core) derives it when the stored fingerprint no longer matches the settings.

**`EbxConnectorOperationalMetricsPersistence`**

- One row per connector. Counters (READ, WRITE, SKIP, CONFLICT, REQUEST, RETRY, THROTTLE) are stored as decimal strings: `readCount`, `writeCount`, `skipCount`, `conflictCount`, `requestCount`, `retryCount`, `throttleCount`.
- Durations are a comma-separated list in `durationSamples`.
- `lastFailure` is masked with `SensitiveDataMasker` and truncated to `LAST_FAILURE_MAX_LENGTH` = 2000 (1999 characters + `…`).
- Negative or non-numeric counters raise `IllegalArgumentException`.
- Missing table → `ConnectorOperationalMetrics table is unavailable; reload the Connectors data model`.

**`EbxRestSchemaRefreshStore`**

- Composite key (connector, key). The key is `"schema-" + SHA-256 hex(url)`.
- The URL is required and at most 1000 characters. The state is required and at most `MAX_STATE_CHARS` = 1,000,000.
- A key that does not match the store's URL → `Schema refresh state key does not match its URL`.
- No `setAllPrivileges`: the scheduler session must be able to write.

**`EbxRecoverableExecutionStore`**

- Encodings: `completed` and `deadLetters` are comma-separated sets; `attempts` is `key=n,...`.
- `acquireLease(runId, owner, now, expiresAt)` fails (returns false) when another owner holds a lease that is still valid. On creation `nextIndex` = 0.
- `releaseLease` only clears the lease for the same owner.
- Missing table → `ExecutionRun table is unavailable; reload the Connectors data model`.

**`EbxConnectorGovernanceStore`**

- `dependencies` and `secretReferences` are comma-separated lists of Base64URL (unpadded) UTF-8 values.
- Audit rows are filtered by resourceId (full table scan) and sorted by `occurredAt`.

#### 4.1.8 Upgrade, snapshot and rollback (docs + tools)

- `docs/UPGRADE-ROLLBACK.md` — runbook:
  - Deploy the WAR and `runtime-libs` (verified against `runtime-libs/SHA256SUMS`) as one unit. Never duplicate container APIs (EBX, Servlet/Tomcat); Jackson 2.14.2 and the SLF4J API are carried by the WAR because a standard ON EBX `lib/` has neither.
  - The schema evolves additively. Installers only create missing packaged records.
  - Before an upgrade: record the version and keep the old WAR; stop schedules; take a verified EBX repository backup; export the configuration as a secondary artefact; build clean and run the offline suite; rehearse on a disposable repository copy.
  - Verification: compare counts and stable identifiers of connectors, categories, properties, mappings, templates, checkpoints, runs, dead letters, metrics and governance records; re-running the installer must add no duplicates.
  - Rollback: never copy the old WAR over a repository opened by a newer schema. Restore the backup, then the exact previous WAR, clearing only disposable Tomcat caches. Without a verified backup, stop and escalate.
- `tools/upgrade_snapshot.py` — read-only snapshot and comparison (§8).
  - Implemented: 01 path boundaries; 02 exception redaction; 03 replay resilience; 04 redaction retention bounded to 4,096 secrets; 05 first mapping; 06 webhook query-string secret removed; 08 HTTP disconnect in finally; 09 striped OAuth locks; 10 discovery truncation flag; 12 common XPath quoting; 13 typed predicates; 14 header validation.
  - Still open: 07 secure defaults (allow-list not default-deny) and 11 atomicity of conflict acceptance across transactions.

---


### 4.2 User services

#### 4.2.1 Summary

| Label (en) | Key | Registered by | Entity | Activation | Web component |
|---|---|---|---|---|---|
| Synchronise with an external application | `ebx-connectors@ConnectorSync` | module | table view | all datasets, all tables | toolbar, perspective; input parameter `mapping` |
| Import an OpenAPI specification | `ebx-connectors@OpenApiImport` | module | table view | all datasets, `/root/Connector` | toolbar |
| Manage connector conflicts | `ebx-connectors@ConflictManager` | module | dataspace | all BRANCH dataspaces; hidden everywhere except `DATASPACE_VIEW_WORKSPACE` toolbar | perspective, toolbar, **workflow user task**; inputs `dataset`, `table`, `recordKey`; outputs `resolvedCount`, `remainingCount` |
| Manage connector conflicts of this table | `ebx-connectors@ConflictManagerTable` | module | table view | all datasets, all tables | toolbar |
| Manage connector conflicts of this record | `ebx-connectors@ConflictManagerRecord` | module | record | all datasets, all tables | toolbar |
| Delete this mapping and everything attached | `ebx-connectors@DeleteMappingRecord` | module | record | all datasets, `/root/Mapping` | toolbar |
| Delete the selected mappings and everything attached | `ebx-connectors@DeleteMappingTable` | module | table view | all datasets, `/root/Mapping` | toolbar |
| Design the mapping | `MappingDesigner` | schema | record | `/root/Mapping` of the Connectors model | toolbar |
| Connection catalog | `ConnectorCatalog` | schema | table view | `/root/Connector` | toolbar, perspective |
| Connector diagnostics | `ConnectorDiagnostics` | schema | record | `/root/Connector`; permission rule: enabled only when a record is selected and the session does not see it hidden | toolbar |
| Connector operations | `ConnectorOperations` | schema | table view | `/root/SyncLog` | toolbar, perspective |
| Release notes | `ReleaseNotes` | schema | dataset | all datasets + activation rule `isConnectorsDataset` (schema path in module = `/WEB-INF/ebx/schemas/Connectors.xsd`); disabled on every table node | toolbar, perspective |

All labels and descriptions come from `Messages_*.properties` (`Messages.getInfo`, resolved in the viewer's locale with English fallback; the key itself when missing).

#### 4.2.2 Synchronise with an external application (`ConnectorSyncUserService`)

- **Set-up** (`setupObjectContext`, runs on every request): calls `ConnectorsInstaller.ensure`, then loads `ConnectorRepository.mappingsFor(dataspace, dataset, tablePath)` for the current table. That list contains active, non-child mappings that apply to the table; a mapping with an empty dataspace applies everywhere the dataset exists. If loading throws, the exception message is shown as an error.
- **Presets**:
  - `mapping`: read from the session input parameters (`getInputParameterValue(false, "mapping")`), else from the request. It becomes the initial selection. It can only select among the mappings of this table, so an invalid value cannot trigger another mapping.
  - `inputs`: same lookup. Stored as run-time inputs and shown as the banner `Run-time inputs of this run: {0}`.
  - `dryRun`: applied once per service instance. The value `false` unticks the simulation box.
- **Form bean** `ftConnectorSyncOptions`:

| Field | Type | Values / facets | Default |
|---|---|---|---|
| `mapping` | string, required | enumeration: id → `<label> [<connector> / <operation>, <direction>]` | preset, else last choice, else first mapping |
| `scope` | string, required | `SELECTED` Selected records · `VIEW` Displayed records (current filter) · `ALL` Whole table · `UNSENT` Not sent yet (outbound) · `CHANGED` Changed since the last send, failures to retry, deletions (outbound) · `FAILED` Failed sends to retry (outbound) · `DATASPACE_DELTA` Created, modified or deleted since the initial snapshot (outbound) | `SELECTED` |
| `maxRecords` | int, required | 1…500 (`ABSOLUTE_MAX` = 500) | 100 |
| `dryRun` | boolean, required | "Simulate only (nothing is written)" | `true` |

- **Buttons**: **Run** (`ftcsRun`) and **Close** (no confirmation on leave).
- **Run** behaviour:
  1. Looks up the chosen mapping by id among the table's mappings. If none is found: `Choose a mapping.` (error).
  2. **Feed** mapping (it has a `keyPredicate`): computes `ConnectorService.runtimeInputs(mapping, requestedInputs, ignored)`, then calls `importFrom(session, repo, mapping, inputs, max, dryRun, "userService")`. The scope is ignored. Ignored input names produce the message `Run-time input(s) ignored because the mapping does not read them as param.name: {0}`.
  3. Scope `UNSENT`, `CHANGED`, `FAILED` or `DATASPACE_DELTA`: `beginRun`, then `sendScope(session, repo, mapping, table, null, OutboundScope.parse(scope), max, dryRun, "userService")`, with message `Run {0} (replay its failures with POST /replay/{0})`, then `endRun`.
  4. Otherwise: the request is `getAllRecords` (ALL), `getFilteredRecords` (VIEW) or `getSelectedRecords` (SELECTED). A null request falls back to all records. Calls `syncRecords(session, request, mapping, max, dryRun, "userService")`. An empty result gives `No record in the chosen scope.`.
  5. Any exception: `The synchronisation failed: {0}` (error).
- **Display**:
  - If the table has no mapping: `No active mapping is declared for {0}: add one in the Connectors dataset (Mapping table).`
  - Before a run: `Choose a mapping and click Run. Simulation shows the changes without writing them.`
  - After a run, the summary line is `{0} record(s): {1} updated, {2} unchanged, {3} without data, {4} skipped, {5} error(s).`, followed by `Simulation: nothing was written.` when dry-run. Below it, a table: Record | Status (CSS colour per status) | Changes (list of changes and errors) | Message (`… (<ms> ms)`).
- **Run-time inputs format**: `name=value` pairs separated by `;` or line breaks. An entry is kept only when it has `=` and the feed mapping reads `${param.<name>}`; every other entry is reported as ignored (`ConnectorService.runtimeInputs`).

#### 4.2.3 Import an OpenAPI specification (`OpenApiImportUserService`, `OpenApiImport`)

- The form is plain HTML, read back from the request:

| Field (request name) | Label |
|---|---|
| `ftoaId` | Connector id (letters and digits; default: from the title) — placeholder `RechercheEntreprises` |
| `ftoaLabel` | Label (default: the specification title) |
| `ftoaUrl` | Specification URL — placeholder `https://.../openapi.json` |
| `ftoaSpec` (textarea) | ...or the specification text |
| `ftoaReplace` (checkbox) | Replace an existing connector of the same id (its operations and fields; secrets are kept) |
| `ftoaDryRun` (checkbox) | Preview only, create nothing |

- Hint: `Give a URL to the specification, or paste it (JSON or YAML). The connector is created with type rest; secrets stay to be entered on the connector.`
- Buttons: **Import** (`ftoaRun`) and **Close**.
- **Import** behaviour:
  1. If a URL is given, `OpenApiImport.fetch(url)` downloads it: `HttpJsonClient` with a 10 s connect timeout, a 30 s read timeout, user agent `EBX-Connectors/1.0 (openapi)` and `Accept: application/json, application/yaml, text/yaml, */*`. The URL must start with `http://` or `https://` (`OpenAPI: the specification URL must start with http:// or https://`); the call and its redirects follow the rules of `HttpJsonClient` and `Egress` (§2.11.4, §2.13.1); the answer is limited to 16 MiB (`HTTP response exceeds byte limit`). A non-2xx response raises `OpenAPI: GET <url> answered HTTP <status>`; an I/O failure (egress refusal included) raises `OpenAPI: cannot download <url>: …`; `<url>` is shown through `Redaction.redact` (secret query parameters masked).
  2. `OpenApiImport.run(session, repo, id, label, text, replace, dryRun)` parses the spec with `OpenApiImporter.parse`/`build` (core). It produces a draft: id, label, baseUrl, authType, apiKeyHeader, description, properties, operations, notes.
  3. With dry-run, the report is returned and nothing is written.
  4. Otherwise `ConnectorsInstaller.ensure` runs first. A missing dataset raises `The Connectors dataset is not available`. An existing connector without replace raises `Connector '<id>' already exists: choose another id or allow replacement`.
  5. The procedure runs with all privileges. It writes `id, label, type=rest, baseUrl, authType, apiKeyHeader, description, active=true`.
     - On creation, `apiKey` is set to `TO_BE_SET`.
     - On replacement, the existing row is modified (secrets untouched) and every property whose key starts with `op.` or `auth.` is deleted; other properties are kept.
     - Then one `ConnectorProperty` is created per draft property.
     - A failure raises `Import failed: …`.
     - `ConnectorRegistry.get().invalidate()` runs afterwards.
- **Report**:
  - Title: `Preview of connector {0} (nothing created).` / `Connector {0} created.` / `Connector {0} updated.`
  - Summary: `Base URL {0}, authentication {1}, {2} operation(s), {3} propert(y/ies).`
  - Then the list of operations and the notes.
  - Errors are shown in red with the raw exception message.

#### 4.2.4 Manage connector conflicts (`ConflictManagerUserService`, three declarations)

- **Scope** is resolved on first display:
  - Dataspace selection → `dataspace` = branch name.
  - Dataset selection → `dataset` = dataset name.
  - Table selection → `table` = table path. If the table is `/root/Conflict` itself, the scope is cleared and the selection designates conflicts instead: selected records of the view, or the opened record. `conflictIds` = those ids, or null when nothing is selected. Scope label: `All open conflicts` / `Selected conflict(s)`.
  - Record selection (not on the Conflict table) → `recordKey` = the record's primary key.
  - Parameters `dataset`, `table` and `recordKey` narrow the scope further. They are read from the request, else from the workflow interaction input parameters.
  - `inWorkflow` = `session.isInWorkflowInteraction(false)`.
- **List**: `ConflictResolver.list("OPEN", dataspace, dataset, table, recordKey)`, restricted to `conflictIds` when set. It is re-read on every request.
- **Form bean** `ftConflictOptions`: `comment` (string, max 500 characters). Label `Comment`; help `Stored on every conflict decided in this round.`; the default is the last comment entered.
- **Screen**:
  - Title `Connector conflicts - {scope}`, then `Scope: {0} - {1} open conflict(s)`. The scope text is `dataspace / dataset / table / key / #id-or-count`, or `*`.
  - With no conflict: `No open conflict in this scope.`
  - Otherwise a hint, links (Select all: take / keep / later, client-side radio selection), the comment row, and a table with columns:
    - **Record**: record label, plus `table / key`.
    - **Field**: the path without `./`.
    - **Current value**: value, the connector that wrote it or `manual entry`, and a trust badge.
    - **Proposed value**: value, proposing connector, trust badge.
    - **Detected**: `yyyy-MM-dd HH:mm`.
    - **Decision**: radios `Take the proposed value` / `Keep the current value` / `Decide later` (default).
  - A trust badge is green when higher than the other side and orange when lower. A missing current trust counts as 100; a missing proposed trust counts as 0.
- **Buttons**:
  - `Apply the decisions` (`ftcfApply`): each conflict follows its radio (`ftcfDecision_<id>`). `take` → `resolver.accept(session, conflict, comment)`; `keep` → `resolver.reject(...)`; `later` or none → skipped.
  - `Take all proposed values` (`ftcfAcceptAll`) forces `take` on every listed conflict. `Keep all current values` (`ftcfRejectAll`) forces `keep`.
  - After any of the three: a log line per decided conflict (`<record> - <outcome message>`, in red when it failed), then `{0} accepted, {1} rejected, {2} failed; {3} still open.` (error style when something failed). The resolved counter accumulates across rounds.
  - Workflow only — `Complete the task` (`ftcfComplete`): sets the interaction outputs `resolvedCount` (the accumulated count) and `remainingCount` (open conflicts now), calls `interaction.complete(outputs)` and closes. A failure shows `The task could not be completed: {0}`.
  - Outside a workflow: **Close**.

#### 4.2.5 Delete a mapping and everything attached (`DeleteMappingUserService`)

- **Selection**: the record, or the distinct selected records of the table view. On later requests every row is looked up again and deleted rows are dropped.
- Title: `Delete {0} mapping(s) and everything attached`.
- With no selection: `No mapping selected: open a mapping, or select rows in the Mapping table.` (only a Close button).
- **Preview table**, one row per mapping, built from `ConfigTransfer.attachments(dataset, id)`. Counts are recursive over child mappings:

  | Column | Meaning |
  |---|---|
  | Mapping | `id - label` |
  | Rules | rules deleted |
  | Inputs | inputs deleted |
  | Sub-object mappings | child mappings deleted |
  | Outbound states | outbound states deleted |
  | Provenance rows (unlinked) | provenance rows that lose their reference |
  | Conflicts (unlinked) | conflict rows that lose their reference |

  - Intro: `The following elements will be deleted with each mapping; provenance and conflict rows are kept and only lose their reference to the mapping.`
  - Hint: `The synchronisation log is kept as is. This cannot be undone: export the configuration first if you may need the mapping again.`
- **Confirmation button** `Delete {0} mapping(s)` (`ftdmConfirm`) calls `ConfigTransfer.deleteMapping(session, repo, id)` for each mapping (§3.9).
  - Success: `{0} mapping(s) deleted, {1} attached row(s) removed.` The screen then shows only Close.
  - Otherwise: `Deletion failed: [<label>: <error>, ...]`.
  - Note: `attachments()` does not count `SynchronizationCheckpoint` rows, although `cascade()` deletes them.

#### 4.2.6 Design the mapping (`MappingDesignerUserService` + `mapping-designer.js`)

**Server state.** The service keeps: direction and responseRoot (initialised from the record), sampleKey, posted rules and inputs, external fields and their origin (`catalog` or `sample`), a message, and a test result.

**Form bean** `ftMappingDesignerOptions`: `sampleKey` (string). Label `Sample record (primary key)`; help `A record of the target table used to load a sample document and to test the rules. Empty: the first record.`

**Hidden fields submitted with every button**: `ftmdRules` (JSON array of `{id, source, target, transformation, policy, comment}`), `ftmdDirection` (`INBOUND`/`OUTBOUND`), `ftmdResponseRoot`, `ftmdInputs` (JSON array of `{name, expression}`), `ftmdChildCollection`, `ftmdChildTable`, `ftmdChildLink`, `ftmdChildKeys`.

**`readPosted`** (runs before each action):
- Clears the message and the test result.
- Keeps the posted rules and inputs when non-empty.
- A direction change clears the cached external fields.
- Takes responseRoot as posted (trimmed) and reads sampleKey from the bean.

**Buttons** (left bar, in order), then **Close**:

| Button (id) | Label | Behaviour |
|---|---|---|
| `ftmdSave` | Save the rules | One procedure, in the user's session (no privilege escalation). Details below. |
| `ftmdTest` | Test on the sample (simulation) | Details below. |
| `ftmdSample` | Load the external fields | Details below. |
| `ftmdInvert` | Create the reverse mapping | Details below. |
| `ftmdChild` | Create a sub-object mapping | Details below. |
| `ftmdResponse` | Create the response mapping | Details below. |

*Save the rules* (`ftmdSave`):
1. Updates the mapping's `direction` and `responseRoot` (empty → null).
2. Rules are upserted in posted order with positions 10, 20…. A posted rule whose `id` matches an existing `MappingRule.id` of this mapping updates that row; any other rule creates a row. Every rule is written with `active=true`, `policy` (invalid → `IF_EMPTY`), an empty source or transformation written as null, and its comment.
3. Existing rules not kept are deleted.
4. If inputs were posted, every `MappingInput` of the mapping is deleted and recreated from the posted list (entries with a blank name or expression are dropped).
5. Result: `{0} rule(s) saved.`, or `Saving failed: {0}` / `The rules could not be read: {0}`. After a save, rules and inputs are re-read from the database.

*Test on the sample* (`ftmdTest`):
- Builds a fresh `MappingDefinition` from the record: current direction, responseRoot, stored inputs, posted rules.
- Picks the sample record: the given key, else the first record of the target table found through `DatasetLocator`.
- Runs `ConnectorService.syncRecord(session, record, definition, dryRun=true, "designer")`.
- Message `Test on {0}: {1} - {2}` (error style when the status is ERROR), then a result block `Simulation on {0}` listing changes and errors, or the message alone.
- No sample record: `No sample record found in the target table.`
- Failure: `The test failed: {0}`.

*Load the external fields* (`ftmdSample`):
- Uses the **root** mapping of the parent chain (its connector, operation, inputs).
- Inputs are resolved against the sample record with `Expressions.resolve`. Posted inputs win over stored ones.
- Calls `connector.describeFields(ctx, operation, inputs)` to get the catalogue.
- OUTBOUND: the catalogue is merged with `MappingEngine.buildOutbound(...)` flattened for the sample record (up to `MAX_SAMPLE_FIELDS` = 400), plus every rule target. Messages: `Sample loaded from {0}: {1} field(s) with their values.`, or `No sample record found…` when there is no record.
- INBOUND without a record: the catalogue alone.
- INBOUND with a record: `connector.execute(...)`. For a sub-object mapping the document is narrowed with `narrowToCollection`: root responseRoot, then for each chain link the first element of `collectionPath`, then that link's responseRoot. An empty document gives `The connector returned nothing for the sample record (inputs {0}); the catalog is shown instead.` (error). Otherwise the flattened document is merged into the catalogue (values become samples).
- Failure: `Loading the sample failed: {0}`.

*Create the reverse mapping* (`ftmdInvert`):
- Creates a new mapping: same connector, operation, dataspace, dataset and table; opposite direction; label `<label> (<direction lower-case>)`; `active=false`.
- Copies the inputs.
- For each current rule with a non-empty source, creates a swapped rule (source↔target), policy `IF_EMPTY`, no transformation. Constants have no inverse and are skipped.
- Message: `Reverse mapping #{0} created ({1}), inactive: open it to adjust the operation and the inputs.`

*Create a sub-object mapping* (`ftmdChild`):
- Requires the collection, child table and link field: `Collection, child table and link field are required.`
- Creates a mapping with the same connector, operation, dataspace and dataset; the current direction; label `<label> / <collection>`; `tablePath` = child table; `parent` = this id; `collectionPath`, `parentLinkField`, `childKeyFields` (null if blank); `active=true`.
- Message: `Sub-object mapping #{0} created for {1} -> {2}: open it to design its rules.`

*Create the response mapping* (`ftmdResponse`):
- OUTBOUND only; otherwise `A response mapping only makes sense for an outbound mapping (EBX -> service); an inbound mapping already stores the answer.`
- If an INBOUND non-child mapping with the same connector, operation, table and dataset exists: `The response mapping already exists: #{0} {1}.`
- Otherwise creates it: INBOUND, label `<label> / response`, same target, responseRoot = current, `active=true`. Message `Response mapping #{0} created: open it to choose which elements of the answer are stored in EBX.`

**Page model `ftmdModel`** (JSON written with `addJS_cr` through `Json.writeForScript`, which writes `<`, `>`, `&`, U+2028 and U+2029 as `\uXXXX` so that a sampled value holding `</script>` cannot end the script; then the script `connect/mapping-designer.js?v=<minute stamp>` is appended):
- `direction`, `responseRoot`, `connector`, `connectorLogo`, `operation`, `operationKind`, `operationDescription`, `table`, `externalOrigin`.
- `isChild`, `collectionPath`, `parentLinkField`, `children[]` (`id`, `label`, `collectionPath`, `tablePath`, `rules`).
- `inputs[]`: required inputs of the operation first, then optional ones, then stored inputs not declared by the operation. Each carries `expression` and `required`.
- `sourceObjects[]` / `sourceDiscoveryError`: only for type `postgresql` with operation `query`. Built with `discoverObjects(firstPage("", 500))`; only readable objects; each has `query` = `SELECT * FROM "<schema>"."<object>" LIMIT 100`.
- `responseMapping` (the twin, or null); `tables[]` (every table path of the target dataset); `labels[]` (about 90 designer bundle keys); `connectorError`.
- `externalFields[]` (`path`, `label`, `type`, `sample`). When nothing is cached, the connector catalogue is read with constant inputs only (inputs without `${`); OUTBOUND adds the rule targets.
- `ebxFields[]`: terminal fields of the target table. Association and computed fields are skipped; groups are recursed. Each carries `path` (`./a/b`), `label`, `type` (`fk <table>` for foreign keys, `list of …` for multi-occurrence fields), `group`, `pk`, `groupPath`.
- `rules[]`, `transformations[]`: 35 built-in hints plus any other `Transformations.names()`.
- If the target table cannot be read: `Table <path> cannot be read in dataset <ds> (<dataspace>): <msg>` (error).

Built-in transformation hints: `trim, upper, lower, capitalize, left(n), right(n), substring(start,end), replace(a,b), regex(pattern,group), digits, prefix(p), suffix(s), default(v), constant(v), nullIfEmpty, nullIf(v), isoDate, isoDateTime, date(inFormat,outFormat), map(A=B;C=D;*=default), join(', '), first, count, boolean, number, round(n), fk(/root/Table), lookup(/root/Table,./field,./result), golden(/root/Table), mdmState(/root/Table), hash, now([pattern]), runid, concat(path1,path2,...)`.

**Page behaviour (`mapping-designer.js`)**

*Tool bar*
- Direction select `<connector> → EBX` / `EBX → <connector>`. Changing it with existing links asks to confirm (`Changing the direction clears the links of this mapping…`) and clears the rules.
- Response root field.
- `+ Constant value`: arms constant mode; the next click on an output field creates a rule with `constant()`.
- Unsaved-changes marker; `Hide unmapped fields`; rule count.
- Diagram / Table view toggle.
- Zoom 60–180 % in 10 % steps (buttons or Ctrl/⌘+wheel).
- Full screen (Esc leaves it).

*Service call section* (collapsible)
- Hint: inbound — what is sent; outbound — call parameters, the document is the body.
- The PostgreSQL source picker replaces the `sql` input with the object's query (keeping its `|>` pipeline) and clicks *Load the external fields* automatically.
- One row per input: expression text, an `Insert an EBX field…` select (inserts `${./path}`), removable unless required, and a transformation-step chain.
- An input expression's pipeline follows the last `|>`.
- `+ Add a parameter` prompts for a name.

*Panes*
- Input (left) and Output (right). Inbound: left = connector document, right = EBX table. Outbound: the reverse.
- Hierarchical trees: JSON paths split on `.`; EBX paths split on `/` with group labels.
- Type badges: LIST, FK, OBJ, NUM, DT, DATE, BOOL, STR.
- Grey sample value, primary-key marker, per-pane search, expand/collapse all, `N mapped / M` counter.

*Links*
- Drag an input port onto an output row, or click an input field then an output field.
- Dropping on an already mapped output re-points that rule, because there is one rule per output field.
- A rule with a transformation, or a constant, is drawn as a node in the middle canvas (auto-layout, collisions pushed down). A node's output port can be dragged to re-target the rule.
- Clicking an output field that has a rule selects that rule.

*Inspector* (selected link)
- Source chip → editable step chips (arguments inline, ✕ to remove, `Add a step…` select) → target chip.
- Constant value (edits the argument of `constant()`), raw pipeline text, policy select (IF_EMPTY / ALWAYS / TRUSTED), comment, **Delete the rule**.

*Table view*: `# | Source | Transformation | Target | Policy | Comment`.

*Outbound only — response section*: the twin card, or `No response mapping yet…`.

*Sub-object section*
- Child cards and a form: collection, child table select, link field, key fields.
- Clicking an array row of the document side fills the collection with `name[*]`.

*Every change* serialises rules, inputs, direction and root into the hidden fields. Nothing is saved until **Save the rules** is clicked.

#### 4.2.7 Connection catalog (`ConnectorCatalogUserService`)

Labelled "Connection catalog" since 2026-09-23: every card is a row of `Connector`, i.e. a configured connection, and the search row carries a **New connection** button (`?service=@creation` on the table's ebx-ui route, rewritten to the current perspective like the card links). The button leaves a `{create:true,url}` origin token: `ConnectorForm` accepts it while the record has no id and narrows it to the record's id once saved, so Close on the new record returns to the catalog too.

**Data**
- Records = the filtered table-view selection, so EBX quick search, criteria, saved views and permissions apply. The total count is `getAllRecords`.
- Sorted by label, case-insensitive (the default of the sort control).
- Mapping counts: one read of `Mapping`, grouped by `connector`; unreadable table = every connector counts 0.
- Favorites of the current user: `Favorites.keys(dataset, login, CONNECTOR)`; a model loaded before the `Favorite` table existed gives none and the page still opens.
- Categories come from `ConnectorCategory`, ordered by `displayOrder` (missing order = 999).
- A connector whose category is empty or unknown goes to a synthetic `uncategorized` group: `Other` / `Connectors without a recognized catalog category.`

**Layout (`FacetedPage`)** — the same faceted page as the data catalog's marketplace, kept as a copy in this module (`FacetedPage`, `FavoriteStars`, `connect/facets.css`); the two modules ship separately.
- Search row: `Filters` button (folds the rail, remembered per user and browser in `localStorage` `ftcxRail:<user>`), search box `Search connectors, descriptions or types` (every word must match, any order, accents/case/plural ignored; searches label, description, type, id, category and both states), sort (`Name`, `Mappings`, `Trust`) with a direction button, `Advanced filters` button.
- Advanced filters (toggle pills, folded, `localStorage` `ftcxMoreOpen:<user>`): `Authentication` (`authType`, empty = `NONE`) and `Usage` (`Used by a mapping` / `Not used yet`).
- Active-filter line: one removable chip per selected value, `Clear all`, then `{0} of {1} connectors` when the native table view already filters the table, the `Advanced EBX search` link to the native table view, and the result count `shown / total`.
- Left rail, check boxes: `Favorites` (`My favorites`), `Category`, `Status` (readiness), `Connection`. Only values present in the data are offered.
- Filters are OR inside a filter, AND between filters; each value shows the number of cards that pass every *other* filter, and is dimmed at 0.
- One group per category, in `displayOrder` (not by size); a group shows two full rows of cards, then `Show N more`; the row length is re-read on window resize.
- One `data-<filter>` attribute per filter and sort on each card (`data-fav`, `data-category`, `data-status`, `data-connection`, `data-auth`, `data-usage`, `data-name`, `data-mappings`, `data-trust`) — guarded by `ConnectorCatalogFacetsContractTest`.

**Card per connector**
- Logo from `logoUrl`, falling back to `default.svg` (also on image load error).
- Readiness badge from `ConnectorStatusEvaluator.evaluate`: INACTIVE → `Inactive` (card greyed out); UNAVAILABLE → `Unavailable`; MISCONFIGURED, unreadable configuration or exception → `Needs setup`; otherwise `Ready`.
- Connection badge from `ConnectorConnectionStateService.status`: `Connected` / `Reconnect` (STALE) / `Connection failed` / `Not connected` (also used on exception).
- Category label, type (`Unknown type` when empty), label (fallback: id, then `Unnamed connector`), description (clamped to 3 lines), id, then `N mapping(s)` when the connector is used.
- Favorite star (bottom right): a `<button>` next to the card link, never inside it (the card is an `<article>`, the link covers its content). A click calls the service's `UserServiceAjaxRequest` with `type=CONNECTOR&key=<primary key>`, which runs `Favorites.toggle` and answers `{"favorite":true|false}` or `{"favorite":…,"error":"<localized text>"}` (shown with `alert`). The click neither opens the record nor stores a return token.
- Each card is evaluated independently, so one failing connector never hides the others.

**Link target**
- `/ebx-ui/ui/advanced/data/<dataspace key>/<dataset>/root/Connector?workspaceDisplay=record:./id='<id>'`, opened in `_top`.
- Clicking a card stores `{id, url}` in `sessionStorage` key `ftccRecordOrigin:<user>` (see *Browser storage* below), with the catalog URL `...?service=ConnectorCatalog&serviceMode=TABLE_VIEW`.
- `ConnectorForm` later uses that entry to send the record's native Close back to the catalog.
- The catalog clears the key when it renders.

**Client scripts**
- If the top window path contains `/ui/custom/<perspective>/`, every `/ui/advanced/data/` link and return URL is rewritten to `/ui/custom/<perspective>/data/`.
- `FacetedPage.script` filters, counts, sorts and folds the groups; `No connectors match your search.` shows when nothing matches.

**Favorites (`Favorites`, `FavoriteAccessRule`)**
- `toggle` never trusts the client: kind must be `CONNECTOR`, key non-blank and ≤ 255 characters, the connector must exist, and a user keeps at most 200 favorites (`LIMIT`). Error codes: `BAD_REQUEST`, `UNKNOWN_TARGET`, `LIMIT`, `FAILED`.
- Rows are written with all privileges through `ProgrammaticService.createForSession`; the table itself is read-only for everybody, each user sees only their own rows, an administrator sees all (`setAccessRuleOnOccurrence` in `ConnectorsSchemaExtensions`).
- A favorite gives no access: the card list is still the session's filtered table view.

#### 4.2.8 Connector diagnostics (`ConnectorDiagnosticsUserService`, `ConnectorDiagnosticsAdapter`)

**Access**
- Readable means the session's permission on the record is not hidden.
- When not readable: `Access denied` / `This connector record is not readable in your EBX session.` and no action buttons.

**Automatic action** on open: request parameter `ftcdAction` = `test` | `connect` | `preview` | `disconnect`, run only with the proof `ftcdToken` (`DiagnosticsLaunch`). This is how the connector form's buttons launch it.
- The token is `<expiry in ms>.<HMAC-SHA256 hex>` over the session's user, the connector id, the action and the expiry, under a key drawn in the JVM when the class loads. It is valid 8 hours, for that user, that connector and that action only, and not after a restart or on another node.
- The connector form mints it when it renders its buttons (`DiagnosticsLaunch.proof`); the service checks it before anything runs (`DiagnosticsLaunch.proven`).
- Without a valid token (a hand-made link, a link somebody made the user follow, a form open for more than 8 hours, a restart since the form was rendered) nothing runs: the page shows `"<action>" was not started` and the user chooses with the page's buttons.
- A proven action runs once per opening: later requests of the same page (its own buttons) do not run it again.

**Buttons**: `Validate configuration` (`ftcdValidate`), `Test connection` (`ftcdTest`), `Connect` (`ftcdConnect`), `Preview capabilities` (`ftcdPreview`), `Disconnect` (`ftcdDisconnect`), Close.
- Each button re-checks readability before acting.
- The close callback is a plain native close. It never redirects to `/ebx-ui`, which would nest a workspace inside the manager bridge.

**Actions** (all headlines and details are passed through `Redaction.redact`):

| Action | Behaviour | Headline |
|---|---|---|
| validate | `ConnectorStatusEvaluator.evaluate(config)`; details = each validation issue, else `Status: <status>` | `Configuration is valid` / `Configuration needs attention`; `Connector not found` |
| test | `ConnectorRegistry.connector(id)` (descriptor validation, secret resolution, egress allow-list), then `connector.testConnection(ctx)`; details: implementation label, message, elapsed ms or `The connector accepted its configuration, but does not provide a live connectivity probe yet.` | `Connection test passed` / `Connection test failed` / `Connection test is not supported` / `Connector test failed` |
| preview | Details: category and authentication methods. `BoundedInspectionRuntime.discover(firstPage("", 25))`: lists `label (id) — type`; for the first readable object, `describe` (`Schema: N field(s)`) and `preview` limited to 5 records (`Read-only sample: N record(s), B byte(s) (truncated)` plus each record as JSON). If discovery is unsupported: the descriptor operations and the discovery message. | `<n> discoverable object(s)`; `Capability preview unavailable` |
| connect | `ConnectorConnectionStateService.verifyAndRecord(connector, ctx, config)`. If the status is not CONNECTED: `Connection verification failed`. Otherwise preview, with details prefixed by `Connection verification was recorded; no database socket is retained.` | `Connected and database objects discovered` / `Connected; discovery needs attention` |
| disconnect | `ConnectorConnectionStateService.disconnect(config)` | `Connector disconnected` — `Stored verification was revoked; no external database session was retained.` |

- Intro text: `Safe connector diagnostics` / `Validate the saved configuration, test connectivity, or preview a bounded read operation when the connector can do so safely. Diagnostics never invoke write operations and do not modify EBX or external data.`
- Note: connect and disconnect do write the `connection*` fields of the Connector row.

#### 4.2.9 Connector operations (`ConnectorOperationsUserService`)

- Read-only dashboard over `getFilteredRecords()` of the `SyncLog` table view, so EBX permissions and filters apply.
- KPI tiles: `visible calls` (row count), `connectors` (distinct connector values), `failures` (status `ERROR`).
- One card per connector (a missing connector is `Unknown`):
  - `{n} successful` (any status other than ERROR or SKIPPED; a missing status is `UNKNOWN`, which counts as successful), `{n} failed`, `{n} skipped`.
  - `Average duration: <sum durationMs / total> ms`, or `—`.
- Only a Close button.

#### 4.2.10 Release notes (`ReleaseNotesUserService`)

**Sort and content**
- Releases sorted by `releaseDate` descending, then `version` descending (string comparison). Rows with status `DRAFT` are skipped.
- Header: kicker `EBX Connectors`, heading `Connect data to EBX with confidence`, intro, and `Export as PDF` button.
  - `ftrnExportPdf()` clones the page, opens every `<details>`, removes the button, writes the clone to a new window with the stylesheet, and calls `print()` after 500 ms.
- *What you can do* section: four cards (Set up a connection; Choose and map data; Test before you run; Run and monitor a flow) and a note.

**Per release**
- Version label `v<version>`. Versions starting with `development-preview` show `Development preview` and the title `Using EBX Connectors`.
- Status badge: `releaseNotes.status.<STATUS>`, else the raw value with `_` replaced by spaces.
- Date and summary.
- `Technical release details` (collapsed) holds the metrics.
- Items are grouped by type, with headings `Available to help you work with data` (FEATURE), `Improvements you may notice` (FIX), `Security and safeguards`, `Before you update` (UPGRADE), `Before you begin` (KNOWN_ISSUE). The detail is shown only when it differs from the title.
- `Delivery history for administrators` (collapsed): one `<details>` per pass, open unless COMPLETE. Each shows label, status badge, a `<progress>` bar, remaining gates and steps (status, label, detail).
- Footer `Build {0} · Commit {1}` when either value is set.
- No published release: `No published release notes are available yet.`

**Packaged content** (`release-notes.xml`, 4 development releases, no formal release):

| id | Content |
|---|---|
| `development-preview-2026-09-09-pass10` | Metrics: catalog offerings 54, connector types 57, declared operations 234, passes 1–8 checklist 70/73, complete passes 6/10. Passes 1–10 (progress 100/80/100/100/100/80/100/95/80/100) with steps. New / improved / known-issue items. |
| `development-preview-2026-09-12-catalog-and-data-fixes` | 8 fixes (catalog view error, perspective kept, flow edit/design sign-out, categories, EBX logo, JDE/Oracle Fusion logos, branding overflow), 1 improvement, 1 known issue (closing a flow may leave the panel loading). |
| `development-preview-2026-09-13-openlineage-and-ebx-catalog` | openlineage sink type, EBX catalogue operations, per-run lineage, credential guide, run ids; known issue: a new sink is seen within 30 s. |
| `development-preview-2026-09-13-declared-lineage` | Declared lineage, `POST /lineage/declare` + MCP tool, broker dataset naming; known issue: changing a connector's base URL does not re-describe its mappings. |

---


### 4.3 UI components (record forms, widgets, functions)

**Perspective colours (audit C-40).** Every pane of the module (the user services of §4.2 and the record forms below) first writes `ThemeTokens`: a `<style data-ebx-theme>:root{…}</style>` block read from the perspective that displays it (`UserServiceRequest.getCurrentPerspective().getTheme()`, `UIFormContext.getCurrentPerspective().getTheme()`). Tokens and their `PerspectiveTheme` getter: `--ebx-main` (`getMainColor`), `--ebx-focus` (`getFocusColor`), `--ebx-header` (`getHeaderColor`), `--ebx-second-header` (`getSecondHeaderColor`), `--ebx-button` (`getButtonsHighlightColor`), `--ebx-table-header` (`getTableHeaderBackgroundColor`), `--ebx-table-odd` / `--ebx-table-even` (`getTableOdd/EvenBackgroundColor`), `--ebx-table-border` (`getTableBodyInnerHorizontalBordersColor`, else `getTableBodyOuterBorderColor`), `--ebx-selection` (`getPerspectiveNavigationPaneSelectionColor`); `--ebx-main-text`, `--ebx-header-text`, `--ebx-button-text` are the theme's own text colour (`getMainTextIconColor`, `getHeaderTextColor`, `getButtonsHighlightTextColor`) when it reaches 4.5:1 on its background, else white, `#202124` or black. Module tokens derived from the main colour: `--ftc-accent-soft` (9 % tint), `--ftc-accent-line` (25 % tint), `--ftc-accent-ink` (main colour darkened until 4.5:1 on white and on the soft tint). Only `#rgb`, `#rrggbb`, `rgb()`/`rgba()` and CSS named colours are written; anything else, an empty colour, no theme or no perspective writes no token. The stylesheets and style constants read `var(--ebx-…, <previous colour>)`, so a screen without tokens looks as before. Semantic colours (success, error, warning, information blue), neutral greys, the connector logos and the perspective menu icons (SVG files) stay fixed. The release notes' print window copies the block. `ThemeColourScanTest` fails when an accent colour of the module appears outside a `var()` fallback or a pane does not write the tokens.

#### 4.3.1 Connector record form (`ConnectorForm`, declared `recordForm` on `/root/Connector`)

- The form uses native EBX rows and widgets throughout, so validation, permissions, encrypted fields and Save/Revert keep working.
- Stylesheet `connect/connector-form.css?v=4`.
- Every pane injects the *contextual return* script: when `sessionStorage` `ftccRecordOrigin:<user>` holds this record's id and a same-origin `/ebx-ui/` URL, the last button of `.ebx_ActionsBack` (native Close) is intercepted and navigates the top window back to that URL. A `MutationObserver` waits for the bar to appear.

**Home tab (overview)**

| Area | Content |
|---|---|
| **Connector identity** — "Name, catalog placement and implementation" | rows `id`, `label`, `description`, `category`, `type`, `active` (only those present in the model) |
| **Integration summary** | Connector ID (or `Assigned on save`); Direction coverage = count of `Mapping` rows with this connector (`No flows configured` / `{0} configured flow(s)`); Configuration = count of `ConnectorProperty` rows (`Standard defaults` / `{0} runtime properties`) |
| **Catalog branding** — "Logo used across connector experiences" | rows `logo`, `uploadedLogo` |

**Tab "Connection & configuration"**
- **Endpoint & credentials** (main column):
  - PostgreSQL setup block, type `postgresql` only: JDBC URL example `jdbc:postgresql://host:5432/database`, schema property hint, readiness chips.
    - Endpoint ready when the `jndi` property is set or the URL starts with `jdbc:postgresql://`.
    - Identity ready when `jndi` is set or a username is present.
    - Neutral chip: `PostgreSQL JDBC driver is packaged with the module`.
  - Credential fields and guide (§3.2).
  - Connection actions (existing record only; otherwise `Save the connector before testing or connecting.`). The buttons open `ConnectorDiagnostics` in the manager bridge (`/ebx/`) with `ftcdAction` and its proof `ftcdToken` (§4.2.8):
    - `Test connection`.
    - `Connect` (label `Connect & discover` for PostgreSQL).
    - `Disconnect`, only when the state is not NOT_CONNECTED.
    - `Discover schemas & tables` (preview), PostgreSQL only.
  - A state chip `Not connected / Connected / Verification stale / Connection failed`, plus `Verified {date}`, and the help `Uses the saved connector settings; save changes before testing.`
- **Aside**:
  - The three credential guide panels.
  - **Runtime policy**: rows `connectTimeoutMs`, `readTimeoutMs`, `trust`.
  - **Validate & test**: `Open connector diagnostics` button, or `Save the connector before running diagnostics.`
- **Credential security** banner: `Password and API token fields use EBX encrypted password widgets and remain governed by record permissions.`
- **Egress posture** (existing record only; `Egress.posture(config)` is recomputed on every render):
  - COVERED: `This connector's host is covered by the current ebx.connectors.allowedHosts list.`
  - NOT_ENFORCED: `Outbound calls are not restricted to an allow-list. An administrator must set ebx.connectors.allowedHosts before this connector goes to production.`
  - NOT_COVERED: `This connector's host is not in the current allow-list. It will be refused the next time it runs — an administrator needs to add it below.`
  - For the last two, it also shows `Current source: <Egress.source()>` (or the example `-Debx.connectors.allowedHosts=file:/etc/ebx/connectors-allowed-hosts.txt (one host per line, # comments, no restart needed to edit it)`) and `Add to it: <Egress.example(config)>`.
- **Connector properties**:
  - When a descriptor is known, a "Configuration expected by this connector" list from `DescriptorConfiguration.visibleProperties(descriptor, values)`. The values include `authentication` and `authType`. Each entry shows label, type, and the flags required / encrypted-secret / advanced / configured, or `default {0}` (never for secrets), plus the description.
  - If no property applies: `No additional properties apply to the current authentication mode.`
  - Then the native `./properties` association widget, or `Save the connector before adding properties.` in creation.

**Tab "Data flows"**
- Help and `Create or manage a data flow` note.
- In creation: `Save the connector before adding related records.`
- Otherwise:
  - A `New data flow` block with a `Create mapping` button: the native Create service on `/root/Mapping`, through the manager bridge preview.
  - One card per `Mapping` row with this connector: direction badge (in/out), Active/Inactive badge, label (or primary key), operation, dataspace/dataset/table (`—` when empty), and buttons.
  - Card buttons navigate the **top window** (never an iframe preview). Under a `/ui/custom/<perspective>/` path the route is rewritten to that perspective:
    - `Edit flow` → `/ebx-ui/ui/advanced/data/<dataspace key>/<dataset>/root/Mapping?workspaceDisplay=record:./id=<id>` (id unquoted, it is an int).
    - `Design & test` → the same URL plus `&service=MappingDesigner&serviceMode=RECORD`.
    - `Run flow` (only when the flow's dataspace, dataset and table are all set) → `/ebx-ui/ui/advanced/data/B<flow dataspace>/<dataset><table>?service=ebx-connectors@ConnectorSync&serviceMode=TABLE_VIEW&serviceParams=<base64 {"mapping":"<id>"}>`.
  - Mapping id resolution: `Mapping/id`, else the occurrence key, else `repo.mappingFor(...)`.
  - Hint: `Activation is controlled by the Active setting in Edit flow.`
  - Empty: `No data flows are configured yet. Choose Create mapping above to add the first one.`

#### 4.3.2 Credential guide (`ConnectorCredentialsPane` + `connector-credentials.js`)

**Inputs**
- Offered modes = `CredentialGuide.modesFor(descriptor)`.
- Current mode = stored `authType`. When empty or unknown: NONE if it is offered or nothing is offered, else the first offered mode.
- OAuth flow = `CredentialGuide.normalizeFlow(property oauth.flow)`. Default `client_credentials`.

**Main column**
- Type strip: descriptor label + `credentials.type.<type>.hint`.
- Mode cards, one per `AuthType` (NONE, BASIC, API_KEY_HEADER, BEARER, OAUTH2): name + short text. Modes not offered are disabled with title `Not offered by this connector type`.
- Description of the current mode.
- Native rows `baseUrl`, `authType`, `username`, `password`, `apiKeyHeader`, `apiKey`, each with row id `ftcc_<field>` inside `#ftcc-rows`.
- Secret length meter (`#ftcc-meter`).
- Per offered mode, an *Expected properties* panel from `CredentialGuide.properties(type, mode, flow)`. Each property shows its state (required/optional/derived/unused) and a value:
  - Derived value when not configured.
  - `configured` for secret names (containing secret, password, privatekey, refreshtoken, or token but not tokenurl).
  - The stored value otherwise.
  - The default flow for `oauth.flow`.
  - Hint: descriptor description, else `credentials.type.<type>.prop.<name>`, else `credentials.prop.<name>`.
  - A property counts as set when it is not blank and not `TO_BE_SET`.
- Readiness strip (`#ftcc-ready`) and legend.

**Aside (three panels, one pane per offered mode)**
- **What to enter**: numbered steps from `credentials.type.<type>.<MODE>.step.N.title/text`, else `credentials.mode.<MODE>.step.N.*` (N < 20). For OAUTH2 with a non-default flow, `credentials.flow.<flow>.step.N.*` are appended. A warning comes from `...type.<type>.<MODE>.warn`, else `...mode.<MODE>.warn`, formatted `level|message` with level `warn` / `crit` / `good`. With no steps: `Nothing to enter: no credential is sent with this mode.`
- **Where to get it**: `credentials.type.<type>.<MODE>.obtain.N`, else `credentials.type.<type>.obtain.N`, plus `.note`. Otherwise `Nothing to obtain.` (NONE) or `See the provider's documentation for this connector type.`
- **How the module authenticates**: send text and code sample from `type.<type>.<MODE>.send/code`, else the flow code, else the mode text. Placeholders resolved from the record:
  - `{apiKeyHeader}` (default `X-API-Key`)
  - `{baseUrl}` (default `https://…`)
  - `{clientId}`
  - `{tenantId}`
  - `{oauth.tokenUrl}` (the property, else the derived value with tenantId/baseUrl substituted, else `https://…/oauth2/token`)
- Text inside `` `backticks` `` is rendered as code.

**Bundle coverage (English)**
- Modes NONE, BASIC, API_KEY_HEADER, BEARER (warning: permanent tokens only; the field takes 255 characters), OAUTH2.
- Flows password, refresh_token, jwt_bearer.
- Field labels: `apiKey` per mode (API key / Token / Client secret / Secret read by the connector); `username` for OAUTH2 = OAuth user.
- Property hints: oauth.flow, oauth.tokenUrl, clientId, oauth.scope, oauth.refreshToken, oauth.privateKey, tenantId, jndi, security.protocol, sasl.mechanism, apiVersion.
- Type-specific guides: `powerbi` (OAUTH2 steps and obtain list; BEARER marked crit because an Entra token exceeds 255 characters), `rest`, `salesforce`, `google-geocoding` / `loqate` / `melissa` (NONE mode, key read by the connector), `postgresql`, `kafka`, `hubspot` (BEARER marked good), `azure-service-bus`, `llm`.

**Page script model `ftccModel`**
- `current`, `limit` = `CredentialGuide.SECRET_MAX_LENGTH` (255), `rows`.
- `stored` (baseUrl present; password/apiKey set and not `TO_BE_SET`), `fieldLabels`.
- `modes.<MODE>` = `{fields: {field: state}, labels: {field: contextual label}, props: [{name, state, configured}]}`, from `CredentialGuide.fieldStates`.
- `text`: required, optional, derived, unused, unusedPlaceholder, encrypted, counter, tooLong, endpoint, credentials, missing, none.
- Script `connect/connector-credentials.js?v=1`.

**Script behaviour**
- Binds once (`documentElement.dataset.ftccBound`).
- Locates rows by id, else by position.
- Follows the native `authType` select, or polls every 500 ms for custom drop-downs.
- Mode cards set the select and dispatch `change`. For a non-select widget they focus and click it.
- For the active mode:
  - Annotates each credential row with a state chip and a contextual label.
  - Dims unused rows and sets the placeholder `Not used with this mode`.
  - Shows the matching panes.
  - Updates the meter: `Encrypted when saved` / `Too long for this field`, `{n} / 255 characters`, based on the typed or pasted length.
  - Updates readiness: `Endpoint` ok when baseUrl is typed or stored; `No credential required` when every field is unused and there are no props; otherwise `Credentials complete` or `Credentials: missing <labels>`, with `Too long for this field` appended.
- A field counts as filled when typed, or stored and not cleared by typing.
- The script never submits or stores anything.

#### 4.3.3 Mapping record form (`MappingForm`, declared `recordForm` on `/root/Mapping`)

- Constants: `EBX_BASE_URI` = `/ebx/`; `EBX_UI_BASE_URI` = `/ebx-ui/`.
- **Home tab**:
  - Call to action: `Design the mapping graphically` button (preview of `MappingDesigner` through `/ebx/`) with the hint `Fields side by side, links by drag and drop, transformations, service inputs and a test on a sample record.`. In creation: `Save the mapping first: the graphical designer opens on an existing mapping.`
  - Section *What the mapping does*: label, connector, operation, direction, active, description.
  - Section *Where it applies*: dataspace, dataset, tablePath, responseRoot. Hint: the dataspace may stay empty to apply everywhere the dataset exists, workflow child dataspaces included.
- **Tabs**:
  - `Inputs (service call)`: the `./inputs` association.
  - `Rules`: the `./rules` association.
  - `Sub-objects`: fields parent, collectionPath, parentLinkField, childKeyFields, childFilter, deleteMissing, plus the `./children` association.
  - `Import feed` (if `itemsPath` exists): itemsPath, keyPredicate, createIfMissing.
  - `Synchronization` (if `loadMode` exists): loadMode, checkpointStrategy, checkpointInput, checkpointValuePath, checkpointInitialValue. Its hint is hard-coded in English and French, not taken from the bundle: `Configure the incremental strategy. Runtime checkpoints are stored separately.`
  - In creation, associations show `Available once the mapping is saved.`
- Only paths present in the model are rendered. `deleteOperation`, `maxAttempts` and `retryDelayMinutes` are not placed on any tab.

#### 4.3.4 Connector metrics record form (`ConnectorMetricsForm`, `recordForm` on `/root/ConnectorOperationalMetrics`)

- In creation: `These rows are written by the connector runtime after each execution; there is normally no need to create one by hand.`
- **Summary tiles**, computed with `ConnectorOperationalMetrics.Snapshot` (skipped if any stored value is unreadable):
  - **Health** badge UNKNOWN / HEALTHY / DEGRADED / FAILED. Detail: `No execution recorded yet` / `Last success {age}` / `{n} consecutive failures, last one {age}`. Section hint: one failure or more in a row is degraded, three or more is failed, a success resets the count (runtime rule in core).
  - **Executions**: requestCount, with `{read} inbound · {write} outbound · {conflict} conflicts`.
  - **Latency p50 / p95** from the duration samples (`<1000 ms` shown as ms, else seconds with 1 decimal below 10 s). Detail: `Over the last {n} executions`, or `No measurement yet`.
  - **Last update**: age (`just now`, `{n} min ago` under 60 min, `{n} h ago` under 48 h, `{n} days ago`, `never`) and the date.
- Last error (at most 600 characters) is shown when the state is DEGRADED or FAILED.
- Sections:
  - Connector: connector, updatedAt.
  - Executions: request, read, write, skip, conflict counts.
  - Reliability: lastSuccessAt, lastFailureAt, consecutiveFailures, retry, throttle.
  - Incremental synchronization: checkpointAt, schemaDriftAt, schemaDriftCount.
  - Diagnostics: lastFailure, durationSamples.

#### 4.3.5 Widgets and computed field

| Class | Bound to | Behaviour |
|---|---|---|
| `LogoWidgetFactory` | `Connector/logo` (`osd:resource`, via `osd:defaultView/widget`) | Image of the resource (`Logos.imagePath` → module image URL; default `connect/logos/default.svg`), 24 px in tables and 48 px in forms. In forms the native resource widget is wrapped in a span with break-all wrapping, so long resource paths do not overflow. |
| `UploadedLogoWidgetFactory` | `Connector/uploadedLogo` (`osd:text` max 360000) | Table: 32 px image when the value is an accepted data URL, else `—`. Form: drop zone (click, Enter/Space, drag and drop), preview, status text. When editable: `Browse files` and `Use packaged logo` (clears the value) buttons. Accepts `image/png`, `image/jpeg`, `image/webp`, file size ≤ `MAX_FILE_BYTES` = 256 KB. Client errors: `Choose a PNG, JPEG or WebP image.`, `Logo exceeds the 256 KB limit.`, `The logo could not be read.`. The value is written into the hidden native widget (input + change events), so Save/Revert apply. `isAcceptedDataUrl`: length ≤ 360000 and a prefix `data:image/png;base64,`, `data:image/jpeg;base64,` or `data:image/webp;base64,` followed by content. |
| `LogoUrlFunction` | `Connector/logoUrl` (`osd:function`) | The uploaded data URL when accepted, else `Logos.url(logo)` = `/ebx-connectors/www/common/images/<path>`, else the default logo URL. |
| `Logos` (helper) | — | `FOLDER` = `connect/logos/`. Resource format `ebx-connectors:ext-images:connect/logos/<file>`. `generatedFor(id)` is non-null when `id` is listed in `logos.txt`. `preferredFor("EBX")` = `EBX.svg` (the real ON EBX mark); otherwise the generated tile. |
| `Messages` (helper) | — | Bundle `<package>.Messages`. Falls back to English, then to the key. `MessageFormat` applies only when arguments are given; a bad pattern returns the raw pattern. `getInfo`/`getError` return locale-lazy `UserMessage`s. |

---


#### Browser storage of the custom screens (wave 13)

Every `sessionStorage` / `localStorage` use of the custom screens is keyed by the EBX user. `sessionStorage` belongs to the browser tab and
survives an EBX logout, `localStorage` survives closing the browser, so a key without the user hands one user's values to the next one who
logs in in the same browser. One helper, `StorageScope` (package `com.on.ebx.connect.ebx.ui`), composes the keys and the scripts; no
screen builds a key on its own and no `.js` file of the module touches browser storage.

| Key prefix (stored as `<prefix>:<user>`) | Store | Written by | Read by | What it holds | Lifetime |
|---|---|---|---|---|---|
| `ftccRecordOrigin` | `sessionStorage` | catalog (`ConnectorCatalogUserService`: card or New connection click) | `ConnectorForm` (native Close of the record) | `{id, url}` or `{create, url}` of the catalog page | removed when the catalog renders, and by the Close click that uses it |
| `ftcnFlowReturn` | `sessionStorage` | `ConnectorForm` flow cards (Edit flow, Design & test); `MappingForm.createPrefill` (pending) | `MappingForm` (Close of the flow form), `MappingDesignerUserService` (Close) | `{id, url, at}` of the connector page | removed when the connector flow tab renders and by the Close click |
| `ftcnCreateConnector` | `sessionStorage` | `ConnectorForm` (Create mapping) | `MappingForm.createPrefill` (creation form) | `{id, url, at}` of the connector | read then removed at once; ignored after 30 min |
| `ftServiceReturn` | `sessionStorage` | `ConnectorForm` flow card (Run) | `ConnectorSyncUserService` (Back and Close) | `{id, url, at}` of the connector page | removed by the Back / Close click |
| `ftcxRail` | `localStorage` | `FacetedPage` (Filters button) | `FacetedPage` | `1` / `0`, facet rail shown (visual only) | until the browser is cleared |
| `ftcxMoreOpen` | `localStorage` | `FacetedPage` (Advanced filters) | `FacetedPage` | `1` / `0`, advanced filters opened (visual only) | until the browser is cleared |

**Cross-module contract (shared with the Data Catalog): key `ftServiceReturn:<user>`.** The Data Catalog module writes the return address of its links (a Re-profile or flow Run link to the run screen) under `sessionStorage` key `ftServiceReturn:<encodeURIComponent(login)>`, value `{url}`, through its `ftdcStore` helper (`user-storage.js`: `<prefix>:<encoded user>[:<extra>]`); Connectors' run screen (`ConnectorSyncUserService` Back / Close) reads and removes that same key, and `ConnectorForm` flow cards write it. `ftcxRail` and `ftcxMoreOpen` are likewise the same entries in both modules. Both modules use the same key form and the same encoding, and each purge keeps the other's keys of the current user; changing either side breaks the Back button across modules (`StorageScopeTest` checks the encoding and, when the catalog worktree is next to this one, the catalog helper's key form).

Rules every screen follows (the same as the ebx-tiers explorer, CLAUDE.md section 9):
- the key is the prefix above + `:` + `encodeURIComponent(login)` of the session that renders the screen (`session.getUserReference().getUserId()`; the encoding means a login can never contain the `:` separator), composed on the server (`StorageScope.key` / `jsKey`, `StorageScope.encode` reproduces `encodeURIComponent` byte for byte); the value is a JSON text;
- when a screen opens it runs `StorageScope.purgeJs`: for each of the six prefixes, the bare key written before this lot and the keys of another user are removed from both stores, the current user's own are kept;
- a value passed from one page to another is read then removed at once (`ftcnCreateConnector`) or, when it must survive the redisplay of the target form (a return address), is bound to the user and consumed by the click that uses it;
- the catalog container carries `data-user="<login>"` (`StorageScope.attribute`).

Nothing sensitive is stored: return addresses are same-origin `/ebx-ui/` routes (checked again before use), the rest is a connector id and two display flags. Manual check after an upgrade: log in as user A, open the catalog, click a card and fold the filters, log out and log in as user B in the same tab, then in the browser console `Object.keys(sessionStorage).concat(Object.keys(localStorage)).filter(k => /^(ftccRecordOrigin|ftcnFlowReturn|ftcnCreateConnector|ftServiceReturn|ftcxRail|ftcxMoreOpen)/.test(k))`: only keys ending in `:<B>` (or none) are listed, and no bare name. The pure parts (key composition, encoding, attribute, purge script, and a scan that no screen uses a bare literal key) are `StorageScopeTest`.

### 4.4 REST API — `/ebx-connectors/rest/v1` (`ConnectorsResource`)

**Common rules**
- JSON responses. Authentication through the EBX REST Toolkit (Basic, token, session); the caller's session runs everything. Most handlers call `ConnectorsInstaller.ensure` first.
- Errors use `fail(status, message)` → `{"error": "<Redaction.redact(message)>"}`.
- **Mapping name resolution** (`Mappings.mappingNamed`):
  1. The value is URL-decoded.
  2. All digits → id.
  3. Otherwise the first mapping whose label matches case-insensitively.
  4. Otherwise, when the value contains `/` at a position > 0, `connector/operation` matched case-insensitively among non-child mappings, but only if exactly one matches.
- **Feed resolution** (`Mappings.feed`): leading and trailing `/` are trimmed and the value URL-decoded. An id is accepted only if that mapping is a feed; otherwise the first feed whose label matches case-insensitively.
- **Record run result** (enrich / sync on a record):

  ```
  {runId, record, dryRun,
   results: [{mapping, label, connector, operation, status, message, durationMs,
              changes[], conflicts[]?, errors[]?}],
   ok, errors, conflicts, hasConflicts}
  ```

  Always HTTP 200. After each mapping the record is re-read, so the next mapping sees what the previous one wrote.
- **Batch result** (`resultsResponse`: feed, scope, replay):

  ```
  {runId, dryRun, …extra, results: [{mapping, connector, operation, record, key, status, message, durationMs, changes[], errors[]?}],
   count, ok, errors, deleted}
  ```

  `deleted` counts messages starting with `deleted`. HTTP **422** when `errors > 0 && ok == 0 && results non-empty`, else 200.

#### 4.4.1 Endpoints

| Method & path | Parameters | Behaviour | Status codes |
|---|---|---|---|
| `GET /connectors` | — | Returns `types[]` (`type`, `label`, `operations[{name, kind, description}]`; `error` when a type cannot be described) and `connectors[]` (`id`, `label`, `type`, `baseUrl`, `active`, `logo` URL defaulting to default.svg, `configured` = apiKey or password not a placeholder, or authType NONE) | 200 |
| `GET /templates` | — | `ConnectorTemplateService.exportTemplates()` document (no credential material) | 200; 400 on error |
| `POST /templates/import` | body JSON | `importTemplates(body, actor=userId)` → `{changed}`. Existing customer-owned templates are never overwritten. | 200; 400 |
| `POST /templates/{template}/copy` | `?id=` new id | Customer-owned copy → `{id, version, basedOn, basedOnVersion}` | 201; 400 |
| `POST /templates/{template}/upgrade` | `?id=` | Upgraded customer copy (the original copy is untouched) → same shape | 201; 400 |
| `POST /connectors/openapi` | `id`, `label`, `url`, `replace` (false), `dryRun` (false); body = spec (JSON/YAML/text/any) when no url | §2.3 logic → `{connector, created, dryRun, baseUrl, authType, properties, operations[], notes[]}` | 201 when created and not dry-run, else 200; 400 `ConnectorException`; 500 other (message not redacted in the body, redacted in the log) |
| `GET /mappings` | `table` (exact tablePath) | All mappings (active or not, children included) → `{mappings: [{id, label, connector, operation, direction, dataset, table, active, feed, child, rules}]}` | 200 |
| `DELETE /mappings/{mapping}` | id, label or connector/operation | `ConfigTransfer.deleteMapping` → `{mapping, label, deletedRows, note}` | 200; 404 `No mapping '<x>'`; 400 when the report has `error` |
| `POST /lineage/declare` | `mapping` (optional id/label) | `DeclaredLineage.declareNow(repo, id or null)`: active mappings declared, inactive retired | 200; 404; 400 |
| `POST /sync/{mapping}` | `key`, `predicate`, `dryRun` (false), `scope`, `max` (200), `inputs` | Details below. | see details |
| `POST /enrich` | `dataspace`, `dataset` (req.), `table` (req.), `key`, `predicate`, `mappings`, `connectors`, `dryRun` (false) | Details below. | 400 `Parameters dataset and table are required`; 404 `No mapping 'x'`; 400 `Mapping <id> (<label>) is not a mapping of <table>` |
| `POST /replay/{runId}` | `dryRun` | New run; `ConnectorService.replay(session, repo, runId, dryRun, "replay <runId> by <user>")`; extra `replayOf` | 200/422; 400 ConnectorException |
| `GET /runs/{runId}` | query `callsMax` (default 50, at most 500, 0 = the log is not read), `callsFrom` (rows to skip, default 0) | `{runId, execution{...} (from the run's own `ExecutionRun` row), calls: [one page of SyncLog rows, newest first], count (rows of the page), errors (error rows of the page), callsMax, callsFrom, callsOmitted?, callsCut?, callsNextFrom?, callsNote?, replayable}`; `replayable` = an error row in the page, or `execution.errorCount` > 0, or the page is cut (rows unread), or the log was not read and there is no `execution` | 200 |
| `GET /status` | — | Health of the module, right READ, no `ensure` (it installs and repairs nothing): `{module, installation: {datasetPresent, modelPresent, installerCompleted}, egress: {allowListSet, requireAllowList, blockPrivateHosts, privateExceptions}, retention: {enabled, days, onByDefault}, secrets: {clearValuesAllowed, clearSecretCount, clearSecrets: [{connector, where}] (50 at most, a value never), clearSecretsKnown}, runs: {running, runningCapped?, known}, asyncPool: {maxConcurrent, maxQueued, running, queued, free}, backgroundWorkStopped, onByDefault: [sentence, ...], offUntilConfigured: [sentence, ...]}`. `runs.running` is counted from one read of `ExecutionRun` (`status='RUNNING'`) stopped at 1000 (`runningCapped`); `known: false` when the table cannot be read. `asyncPool` reads the admission counter of `ConnectorService`: `running` and `queued` are derived from the runs holding a slot, `free` is what a new asynchronous run can still take. `offUntilConfigured` lists in words each protection that is off until its property is set (allow-list, private-host block, retention, clear-secret refusal). No connector, no allow-list content and no secret appears in it (`ConnectorsStatus`, `ConnectorsStatusTest`) | 200; 403 without the READ right |
| `GET /outbound` | `mapping` (required), `status` (default `FAILED`; `ALL` = no filter; upper-cased), `max` (page size, default 200, ceiling 2000: a larger value is lowered, never refused), `from` (0-based offset, default 0) | OutboundState rows the caller may read → `{mapping, status, records: [{key, record, status, attempts, lastError, lastRunId, lastSentAt yyyy-MM-dd'T'HH:mm:ss}] (one page), count (every matching state), max, from, cut?, nextFrom?, note?}` (audit C2-07) | 200; 404 `No mapping '…' (parameter mapping is required)` |
| `GET /conflicts` | `status` (default `OPEN`), `dataspace`, `dataset`, `table`, `key` | `ConflictResolver.page` → `{status, count, hasConflicts, conflicts: [conflictJson]}`; `conflicts` holds the newest conflicts up to `-Debx.connectors.conflicts.pageSize` (default 200, max 2000), `count` is the real total, and `truncated`, `returned`, `nextStep` are added when more exist (audit C-39) | 200 |
| `POST /conflicts/{id}/{decision}` | decision `accept`/`reject` (case-insensitive), `comment` | accept writes the proposed value; reject keeps the current one → `{id, ok, message, conflict}` | 200 when ok; **409** when the outcome is not ok; 404 `No conflict <id>`; 400 `Decision must be accept or reject` |
| `GET /config` | `connector`, `mappings` (default true) | `ConfigTransfer.export` (§4.2) | 200 |
| `POST /config` | `dryRun` (false); body = export document | `ConfigTransfer.importConfig` → report | 200; 400 invalid JSON (`Invalid JSON: …`) or a report `error` |
| `GET /webhook/{feed}` | — | Health check, **no `ensure`**, no explicit authentication check → `{mapping, label, connector, table, itemsPath, ready: true}` | 200; 404 `No feed mapping <x>` |
| `POST /webhook/{feed}` | `dryRun`, `max` (10000), `separator` (csv); body | §4.3 | 200/422; 400; 401; 403; 404; 500 |

**`POST /sync/{mapping}`**
- A `scope` makes it an outbound scope run (`runScope`):
  - `OutboundScope.parse(scope)` accepts all, unsent, changed, failed, `dataspaceDelta`/`delta`; unknown → 400 `Unknown scope '<x>': all, unsent, changed, failed, dataspaceDelta`.
  - The dataset is located with `DatasetLocator.find(mapping dataspace, dataset, table)`; 404 `Dataset <ds> not found`.
  - An optional predicate narrows the request.
  - `sendScope(..., max, dryRun, "REST <user>")`; extra `{mapping, scope}`.
- A feed with no key and no predicate: `importFrom(session, repo, mapping, runtimeInputs(inputs), max, dryRun, "REST <user>")`; extra `{mapping, feed: true, inputs{…}, inputsIgnored[]}`.
- Otherwise the record run on `(mapping dataspace, dataset, table, key|predicate)`.
- Errors: 404 `No mapping '<x>'`; 404 `Dataset <ds> not found`; 404 `No record for key/predicate in <table>`.

**`POST /enrich`** — mapping selection, in order:
1. `mappings`: comma list of names; each must apply to (dataspace, dataset, table).
2. Otherwise `connectors`: a comma list of `id` or `id/operation`; every INBOUND non-feed mapping of the table matching (case-insensitive) is taken, in token order.
3. Otherwise every INBOUND non-feed mapping of the table (active, non-child).

Then the record is resolved by `key` (primary key string) or `predicate` (first match), and a record run follows. An empty selection is not rejected here: it runs zero mappings (unlike MCP).

**`conflictJson` shape**: `{id, status, dataspace, dataset, table, recordKey, record, field, currentValue, currentConnector, currentTrust (default 100), proposedValue, proposedConnector, proposedTrust (default 0), mapping, detectedAt, comment, resolvedBy?}`.

#### 4.4.2 Configuration export and import (`ConfigTransfer`)

**Export**

```
{format: "ebx-connectors-config", version: 1, connectors: [...], mappings: [...]}
```

- The keys `connectors` and `mappings` are absent when the dataset is missing.
- **Connector**: `id` plus the fields present and non-null among `label, type, baseUrl, authType, username, apiKeyHeader, connectTimeoutMs, readTimeoutMs, active, trust, logo, description`.
  - Also `secrets: {apiKey: bool, password: bool}`, where true means not a placeholder (placeholders are empty, `TO_BE_SET` or `A_RENSEIGNER`).
  - Also `properties: {key: value}`. A value is replaced by `TO_BE_SET` when the key looks secret (lower-case contains secret, password, token, privatekey, apikey, or ends with `.key`) and the value is neither a placeholder nor a reference (`env:`, `file:`, `vault:`, `azurekv:`).
  - Filter `connector` = only that id.
- **Mappings**: root mappings only (`osd:is-null(./parent)`), filtered by connector.
  - Fields: `id` plus `connector, operation, direction, label, description, dataspace, dataset, tablePath, responseRoot, active, collectionPath, parentLinkField, childKeyFields, childFilter, deleteMissing, itemsPath, keyPredicate, createIfMissing, loadMode, checkpointStrategy, checkpointInput, checkpointValuePath, checkpointInitialValue, deleteOperation, maxAttempts, retryDelayMinutes, provenance, unchangedIfEqual, checkpointBoundary, batchSize, logEveryRecord`. `connectors_mapping_upsert` and the import write the same list, so none of these settings is lost on the way to another environment; `ConfigTransferFieldsTest` fails when a model field is neither in the list nor explained as carried another way.
  - `inputs: {name: expression}`.
  - `rules: [ {position, sourcePath, targetPath, transformation, policy, active, comment} ]`, sorted by position.
  - `children: [recursive]`.
- Values: booleans and ints as JSON; everything else via `formatToXsString`.
- Not exported: `category`, `uploadedLogo`, `username`'s password/apiKey (only their flags), templates, and every runtime table.

**Import** (`importConfig`)
- Missing dataset → report `error: the Connectors dataset is not available`.
- `format` must be `ebx-connectors-config`, else `not an ebx-connectors-config document (missing "format": "ebx-connectors-config")`.
- One procedure with all privileges and **triggers disabled**. Dry-run throws an internal rollback exception, so nothing is committed.
- **Connectors** are matched by id.
  - Create: fields applied; `apiKey` and `password` set to `TO_BE_SET`.
  - Update: only the listed fields present in the document; secrets untouched.
  - Without an id: note `connector without id skipped`.
- **Properties** are matched by (connector, key).
  - Created when missing.
  - Updated unless the incoming value is a placeholder while the existing value is not (secrets are kept).
- **Root mappings** are matched on `parent null` + connector + operation + direction + tablePath + label.
  - With `updateById` present and no match, the mapping `id` is used.
  - Created or updated.
  - Rules are replaced (all deleted, recreated with positions 10, 20…) when `rules` is an array.
  - Inputs are replaced when `inputs` is an object.
  - Children are matched by (parent, label), recursively. A child inherits `connector, operation, direction, dataspace, dataset` from the parent document when it omits them.
- Afterwards `ConnectorRegistry.invalidate()`.
- Report: `{dryRun, notes[], connectorsCreated, connectorsUpdated, propertiesWritten, mappingsCreated, mappingsUpdated, rulesWritten, error?}`.
- Note: `importConfig` does not schedule declared lineage (triggers are disabled), unlike `upsertMapping` and `deleteMapping`.

**`upsertMapping(session, repo, document, dryRun)`** (MCP)
- Requires `connector, operation, direction, dataset, tablePath, label`: `mapping.<field> is required (connector, operation, direction INBOUND|OUTBOUND, dataset, tablePath, label)`.
- The connector must exist: `no connector '<id>'`.
- **The dataspace is checked, never guessed** (`resolveTargets` / `decideTarget`, before anything is written, dry run included; applied to the document and to each child, a child inheriting its parent's dataspace). A mapping may leave the dataspace empty: it then applies to a record wherever the dataset exists, workflow child dataspaces included. A TABLE-scoped run (feed import, scope send, catalog scan, enrich of a whole table) needs exactly one dataspace serving the dataset; copies in child dataspaces of that dataspace do not count; several distinct dataspaces → the run fails with `Dataset 'X' … is served by N dataspaces (…): name the dataspace on the mapping`, and the upsert refuses it. A blank dataspace STAYS blank (generic): `DatasetLocator.locate` collects the dataspaces serving the dataset and table (no descent under a hit); exactly one adds the note `dataspace not named: table-scoped runs will use <key> …`, several refuse the upsert with `error` = `Dataset 'Master' (table /root/Personne) is served by 2 dataspaces (Personnes_Master, Vol100k_Master): name the dataspace on the mapping`, none stores as given with a note. A dataspace that is named but does not exist (a leading `B` is tried stripped, and stored stripped) is refused, `Dataspace 'X' does not exist`; one that exists without the dataset yet is stored as given with a note. An update that does not mention the dataspace keeps the stored one and is not resolved. The report adds `dataspace` only when one was given.
- `feed {itemsPath, keyPredicate, createIfMissing}` is flattened onto the mapping; top-level values win. A missing `keyPredicate` adds the note `feed ignored: a feed needs keyPredicate (…) and, when one document holds several items, itemsPath (e.g. messages[*].value)`.
- Same import logic, triggers disabled.
- After commit (non-dry-run): declared lineage is scheduled for the id, and the previous label's job is retired when `updateById` renamed the mapping.
- Report: `{dryRun, notes, dataspace?, mapping (id or null when dry-run), created, rulesWritten, childrenWritten, nextStep}`.
- `childrenWritten` = created + updated − 1.

**`deleteMapping(session, repo, id)`**
- Triggers disabled. `cascade`, then delete the mapping.
- Lineage: the mapping's job is retired and its former parent re-described.
- Report: `{mapping, label, deletedRows, note: "rules, inputs, sub-object mappings and outbound states deleted; provenance and conflict rows kept without their mapping reference"}`.
- Errors: `mapping id is required`, `no mapping <id>`.

**`cascade(ctx, dataset, id)`** — also used by `MappingCascadeTrigger`:
- Recursively deletes child mappings (each with its own cascade).
- Deletes `MappingRule`, `MappingInput`, `SynchronizationCheckpoint`, `OutboundState` rows with `./mapping=id`. Tables absent from the model are skipped.
- Sets `./mapping` to null on `FieldProvenance` and `Conflict` rows.
- `SyncLog` is untouched.

#### 4.4.3 Inbound webhook — `POST /webhook/{feed}`

1. Resolve the feed (404 `No feed mapping <x>`). Its connector must be of type `webhook` (400 `Mapping <id> is not bound to a webhook connector`) and active (403 `Connector <id> is inactive`).
2. Without an EBX session: 401 with `WWW-Authenticate: Basic realm="EBX"` and `{"error":"Authentication required: post with the credentials of an EBX user ..."}` (`Webhooks.access`, C2-10; the shared-secret and signature modes are removed).
3. With a session: the same right as `POST /sync` (RUN, through `refused("webhook", RUN)`), else 403.
4. The import runs under that session.
5. `ensure`, then parse the body (`parseBody`):
   - Content type containing `csv`, or starting with `text/plain` → `Csv.parse(body, separator, 100000)`. Separator default `;`; `tab` → `\t`; else the first character.
   - `ndjson`/`jsonl` → `{rows: [...], count}`.
   - Otherwise JSON. A missing content type is treated as JSON.
   - An empty body → 400 `Empty or invalid body (JSON, ndjson or csv expected)`.
6. `importDocument(session, repo, feed, document, max, dryRun, "webhook <connectorId>")`.
7. Response `{mapping, dryRun, results: [{status, record, message, changes (count), conflicts[]?, errors[]?}], count, ok, errors, conflicts, hasConflicts}`. HTTP 422 when `errors > 0 && ok == 0`, else 200. Unexpected exception → 500.

---


### 4.5 MCP

#### 4.5.1 Transport — module endpoint `/ebx-connectors/rest/mcp` (`McpEndpoint`)

- **Enabled** when `-Debx.connectors.mcp` is `both` (default) or `endpoint`. When disabled, POST, GET and DELETE answer **404**; OPTIONS still answers 200.
- **GET and DELETE**: `405` with `Allow: POST,OPTIONS`, produces `*/*`, so the probe's Accept header never turns into a 406. This is what makes MCP clients fall back to plain request/response.
- **OPTIONS**: `200` with `Allow: POST,OPTIONS`.
- **POST** (consumes JSON or any):
  - No session → 401 with `WWW-Authenticate: Basic realm="EBX"` and `{"error":"Authentication required"}`.
  - Unparseable body → 400 with JSON-RPC error `-32700 Parse error: …` (id null).
  - Empty body → 400 `-32600 Invalid request`.
  - A batch array is dispatched element by element. No answers → 202; otherwise 200 with an array.
  - A single message: a notification → 202 with no body; otherwise 200 with the answer.
- Locale = session locale, default English.
- Protocol version constant `2025-06-18`.

**Methods**

| Method | Answer |
|---|---|
| `notifications/*` | none (202) |
| `initialize` | `{protocolVersion: <client's, else 2025-06-18>, capabilities: {tools: {listChanged: false}}, serverInfo: {name: "ebx-connectors", title: "ON EBX connectors", version: "1.0"}, instructions: SERVER_DESCRIPTION + " Call connectors_help first."}` |
| `ping` | `{}` |
| `tools/list` | `{tools: [{name, description, inputSchema: {type: object, properties: {p: {type, description}}, required: [...] (omitted when empty)}, annotations: {readOnlyHint: behavior=="read", destructiveHint: behavior=="destructive", idempotentHint: behavior!="additive"}}]}` |
| `tools/call` | Unknown tool → error `-32602 Unknown tool: <name>`. Otherwise `ConnectorsTools.invoke(name, arguments)` → `{content: [{type: "text", text: <JSON>}], structuredContent: <JSON>, isError: <result has "error">}` |
| `resources/list` / `prompts/list` | `{resources: []}` / `{prompts: []}` |
| other | error `-32601 Method not found: <method>` |

#### 4.5.2 Native runtime (`ConnectorsMcpServer`)

- `@McpServer("ebx-connectors")`, served by EBX at `/ebx-assistant/mcp/v1/ebx-connectors` when EBX accepts the registration.
- Each `@McpTool` method builds an argument object (null arguments are omitted) and calls the same `ConnectorsTools.invoke`. Result: a `Map`.
- Behaviours: `READ_ONLY`, `ADDITIVE`, `DESTRUCTIVE`, `ADDITIVE_RETRYABLE` (replay, lineage). `openWorldHint=true` on describe, call, enrich, sync, replay, lineage and openapi.
- Parameter type differences from the catalogue:
  - `inputs` (describe/call) and `mapping` (upsert) are `Map<String,Object>`.
  - `config` (import) is `Object`.
  - Booleans are `Boolean`; `max` is `Integer`.
- **`connectors_sync` has no `inputs` parameter in the native server** (see Discrepancies).

#### 4.5.3 Tool catalogue (`ConnectorsTools.CATALOG`, 23 tools)

**Common rules**
- `invoke` always calls `ConnectorsInstaller.ensure` first.
- Errors come back as `{"error": "<redacted message>"}`: `ConnectorException` and `IllegalArgumentException` messages as is; other exceptions are logged (redacted) and returned redacted.
- String arguments accept any JSON value; container nodes are serialised to text.
- Booleans accept JSON booleans or the text `true`.
- Integers accept numbers or numeric text; an invalid value falls back to the default.
- Object arguments accept a JSON object or JSON text.

| Tool | Behaviour tag | Parameters (type, required) | Behaviour and output |
|---|---|---|---|
| `connectors_help` | read | — | `{overview, typicalFlows[6], secrets, restEquivalent}`: the discover / enrich / publish / create mapping / move configuration / OpenAPI flows. Secrets are never passed through tools (entered in the UI, or `vault:`, `env:`, `file:`, `azurekv:` references). |
| `connectors_list` | read | `type` (string) | `types[]` (`type`, `label`, `operations` as `"name (kind)"`, `error`), filtered by type (case-insensitive); `connectors[]` (`id, label, type, baseUrl, authType, active, trust, secretsSet, problem?`); `count` |
| `connectors_describe` | read | `connector`* (string), `operation` (string), `inputs` (string, JSON object text) | Instantiates the connector through the registry (fails when inactive or misconfigured). Returns `id, label, type, typeLabel, baseUrl, authType, trust, active`, `properties` (values redacted), `operations[] {name, kind, description, requiredInputs[], optionalInputs[]}`. With an operation: `fields[] {path, label, type, sample?}` from `describeFields(ctx, op, inputs)` (it may call the service), plus `fieldsOf`. |
| `connectors_call` | additive | `connector`*, `operation`*, `inputs` (JSON text), `allowWrite` (boolean, default false), `document` (JSON object text, WRITE only) | Unknown operation → `Connector <id> has no operation '<op>'`. A WRITE operation without allowWrite → `'<op>' is a write operation: pass allowWrite=true to call it`. A non-WRITE operation with `document` → `'<op>' is not a write operation: document is only for a WRITE operation`. `connector.execute(ctx, op, inputs, document)` (payload `null` when `document` is omitted, e.g. every read/search call) → `{connector, operation, durationMs, document}`. No EBX data is touched, no SyncLog. |
| `connectors_mappings_list` | read | `table`, `connector`, `direction` | All mappings filtered (table exact; connector and direction case-insensitive) → `{mappings: [{id, label, connector, operation, direction, dataset, table, active, feed, child, rules, children}], count}` |
| `connectors_mapping_get` | read | `mapping`* | `{id, label, connector, operation, direction, dataspace, dataset, table, responseRoot, active, feed, createIfMissing, [parent, collectionPath, parentLinkField, childFilter, deleteMissing when child], inputs{}, rules[{position, source, target, transformation, policy, comment?}], children[recursive]}`. Unknown → `No mapping '<x>'`. |
| `connectors_mapping_upsert` | additive | `mapping`* (object), `dryRun` (boolean) | `ConfigTransfer.upsertMapping` (§4.2). A rule without `policy` is written `IF_EMPTY` explicitly; the answer adds `rulePolicies` {IF_EMPTY, ALWAYS, TRUSTED: count}, `policyDefaulted` and, on an inbound mapping with defaulted rules, a note in `notes` (audit C-35; same for the configuration import) |
| `connectors_mapping_delete` | destructive | `mapping`* ("Mapping id"; labels are also resolved) | `ConfigTransfer.deleteMapping` |
| `connectors_enrich` | additive | `dataset`*, `table`*, `dataspace`, `key`, `predicate`, `mappings`, `connectors`, `dryRun` | Same selection as REST `/enrich`, but an empty selection → `No inbound mapping selected for <table>`. Dataset not found → `Dataset <ds> (table <t>) not found`; record not found → `No record for key/predicate in <table>`. Origin `MCP <user>`. Output as the REST record run, without `operation`/`durationMs` per item; adds `nextStep` when conflicts exist. |
| `connectors_sync` | additive | `mapping`*, `key`, `predicate`, `scope`, `inputs` (`name=value;…`, parsed with `ConnectorService.runtimeInputs`; the description said JSON text until audit C-35), `max` (integer, default `RunLimits.API` = 200), `dryRun`, `resumeFrom`, `async`, `fullRefresh` | `async=true`: a feed import (§2.14.5) or, since wave 13, an OUTBOUND mapping with a `scope` and no `key` (§2.14.6a) starts in the background and answers `{runId, status: RUNNING, async: true, …}`; without `async` the answer below is unchanged. Feed with no key, predicate or scope → `importFrom` (output `results` + `mapping`, `feed: true`, `ignoredInputs[]`). No scope → delegates to enrich with `mappings=<id>` on the mapping's own dataspace, dataset and table. Scope → `sendScope` (output + `mapping`, `scope`). Batch output `{runId, dryRun, results[{mapping, record, key, status, message, errors?}], count, ok, errors, nextStep when errors and not dry-run}`. Audit C-35: every answer goes through `ConnectorsTools.putNotices`: run-time inputs not used (all of them on a record or scope run) → `ignoredInputs[]`, `warning: true`, `warnings[]`, `message`, and a plain `OK` → `WARNING`; a run cut at `max` (feed, table or scope) → a SKIPPED "Limit reached" result, `truncated: true`, `status` PARTIAL and its sentence in `message`; `count` excludes that notice. |
| `connectors_replay` | additive | `runId`*, `dryRun` | `runId is required`; replay in a new run; output + `replayOf` |
| `connectors_lineage_declare` | additive | `mapping` | `DeclaredLineage.declareNow` |
| `connectors_run_get` | read | `runId`*, `callsMax` (default 50, at most 500, 0 = do not read the log), `callsFrom` | same answer as `GET /runs/{runId}` (below); `execution{}` also for an asynchronous outbound send, with `direction` and `mapping` (wave 13, §2.14.6a) |
| `connectors_run_stop` | additive | `runId`* | flags a running feed import or asynchronous outbound send (`ConnectorService.requestCancel`); it ends `STOPPED` at its next batch boundary, on this node only (§2.14.6a) |
| `connectors_outbound_states` | read | `mapping`*, `status` (SENT, FAILED default, DELETED, ALL), `max` (default 200, ceiling 2000), `from` (default 0) | As REST `/outbound` (same shared JSON) |
| `connectors_conflicts_list` | read | `status` (default OPEN, upper-cased), `dataspace`, `dataset`, `table`, `recordKey` | `{status, count, hasConflicts, conflicts[]}`; newest first, at most `-Debx.connectors.conflicts.pageSize` (default 200) in `conflicts[]`, `count` is the real total, `truncated`/`returned`/`nextStep` when more exist. The conflict JSON has no `resolvedBy`, unlike REST. |
| `connectors_conflict_decide` | additive | `id`*, `decision`* (accept/reject), `comment` | `{id, ok, message, conflict}`. Errors: `No conflict <id>`, `decision must be accept or reject`. A failed outcome is **not** an error result (ok=false). |
| `connectors_log_search` | read | `mapping`, `connector`, `recordKey`, `status` (upper-cased), `runId`, `max` (default 200, ceiling 2000, lowered not refused), `from` (default 0) | SyncLog ordered by id descending, equality filters → `{rows: [{id, timestamp, mapping, connector, operation, direction, record, key, status, message, runId, origin}] (one page), count (every matching row), max, from, cut?, nextFrom?, note?}` (audit C2-07) |
| `connectors_config_export` | read | `connector`, `includeMappings` (boolean, default true) | `ConfigTransfer.export` |
| `connectors_config_import` | destructive | `config`* (catalogue: JSON text; native: object or text), `dryRun` | `ConfigTransfer.importConfig` |
| `connectors_publish` | additive | `mappings`* (comma-separated root outbound ids or labels), `schema`*, `product`*, `contractVersion` (default 1), `dryRun`, `recreate`, `max` (default 0 = every row), `dataspace` | Publishes a data product as a whole to a `TablePublisher` connector (Snowflake): one atomic commit for every table of the root outbound mappings, then the report (`status` OK or PARTIAL, per-table action, columns, row counts); GUIDE § 10.2 and the tool description |
| `connectors_catalog_source` | additive | `connector`*, `source` (id of the Source to create), `apply` (default false: plan only) | How the system a connector reaches is known to the data catalog (LINKED, CREATE, SET_CONNECTOR, SET_NAMESPACE, MANUAL); with `apply=true` the change that makes its lineage land on the right Source. Only when the catalog is deployed and verified; never deletes |
| `connectors_openapi_import` | additive | `id`*, `label`, `url`, `spec`, `replace`, `dryRun` | `id is required`; `url or spec is required`; §2.3 → `{connector, created, dryRun, baseUrl, authType, properties, operations[], notes[]}` |

`*` = required. `SERVER_DESCRIPTION`: "Connectors module: configured connectors and mappings, enrichment of records from external services, outbound synchronisation and deltas, replay of failed runs, conflict arbitration, synchronisation log, configuration export/import, OpenAPI import."

#### 4.5.4 In-JVM contract for sibling modules (`InJvmContract`)

Package `ebx.contract`; the caller's guide with copy-pasteable code is [CONTRACT.md](CONTRACT.md). Audit F-26 (MDM Factory)
and D-18 (data catalog): the two modules read Connectors tables by literal path, decrypted the LLM connectors' keys
themselves and wrote mapping rows as data. This contract gives them the same results through Connectors' own code.

- **Name and version**: `ContractCatalog.NAME` = `ebx-connectors.sibling`, `ContractCatalog.VERSION` = `1.2.0` (1.2.0, wave 13: the operations `run.get` and `run.stop`, and `flows.run` `async` for an outbound scope send; 1.0.0 in
  wave 11; 1.1.0, wave 12: `llm.chat`, `maxTokensApplied`/`capped`, `llm.connectors` `maxTokens`/`chat`, the `setting` default
  source, `since` in the description, the error `NOT_SUPPORTED`; a 1.0 caller is served unchanged except that an answer size
  above the connector's own `maxTokens` is now capped, see `llm.ask`)
  (`MAJOR.MINOR.PATCH`; MINOR for a new operation, optional parameter, answer field or error type; MAJOR for a removal,
  a rename, a parameter becoming required or a wider right). `ContractCatalog.compatible(offered, needed)`: same major,
  then minor/patch at least the needed one; anything else than three numbers is incompatible. Each operation carries
  `since` (the version that introduced it). Parameter types: `string`, `integer`, `boolean`, `object`, `array` (1.1.0;
  `min`/`max` = number of items, the items checked by the operation).
- **Transport**: platform MBean `com.on.ebx.connectors:type=Contract` (`InJvmContract`, interface `InJvmContractMBean`):
  attributes `ContractName`, `ContractVersion`; operations `describe()` (JSON text) and `handle(Object session, String
  message)` = one JSON-RPC 2.0 request (`method` = operation, `params` = object; a batch array is refused), answered as
  text. Registered by `ConnectorsModuleRegistration` after the in-JVM MCP bridge (a failure is a WARN, the module still
  starts), unregistered at stop. The thread's context class loader is set to the module's during the call and given back.
  `-Debx.connectors.contract=false` (read at each call) answers every request `DISABLED`.
- **Why a second MBean, not tools of `type=InJvmMcp`**: the MCP tools are also served over HTTP (`/ebx-connectors/rest/mcp`,
  native MCP) and listed to AI agents; a RUN-level "ask the model" there would open a new HTTP surface, and the tool
  catalogue moves with the agents' needs. The contract is for Java callers of the same JVM only (a remote JMX client cannot
  send a `Session`), and its version moves only with its own operations. Its flow operations still run through the MCP
  tools in-process (`ConnectorsTools.invoke`), so validation, answers and the audit line are those of the tools.
- **Flow of a call** (`ContractService.call`): unknown operation → `UNKNOWN_OPERATION`; parameters checked by
  `ContractCatalog.validate` (unknown parameter, missing required, wrong JSON type — integers and booleans as JSON numbers
  and booleans only —, string blank or too long, integer out of bounds → `INVALID_ARGUMENT` naming it; a null value counts
  as absent); `contract.describe` answers here, without session; no session → `SESSION_REQUIRED`; the session's right
  (`ConnectorsAccess.granted`) below the operation's → WARN `[connectors] access denied: user …, contract operation …`, an
  audit line `contract=<op> outcome=refused` for a RUN/MANAGE operation, and `ACCESS_DENIED` with
  `ConnectorsAccess.refusal`; then `ConnectorsInstaller.ensure`, then the operation. A runtime failure → `INTERNAL`
  (redacted, logged at ERROR).
- **Errors** (`ContractError`, JSON-RPC `error {code, message, data {type, operation, contract, version}}`):
  `INVALID_REQUEST` -32600, `UNKNOWN_OPERATION` -32601, `INVALID_ARGUMENT` -32602, `SESSION_REQUIRED` -32001,
  `DISABLED` -32002, `ACCESS_DENIED` -32003, `NOT_FOUND` -32004, `AMBIGUOUS_KEY` -32005, `CONNECTOR_UNUSABLE` -32010,
  `NO_DEFAULT_LLM` -32011, `NOT_LLM` -32012, `LLM_FAILED` -32013, `TIMEOUT` -32014, `NOT_SUPPORTED` -32015 (1.1.0),
  `OPERATION_FAILED` -32020, `INTERNAL` -32603.
- **Default `llm` connector** (`ContractService.defaultLlm(connectors, setting, property)`, read at each call): the setting
  `/root/Settings/defaultLlmConnector` of the Connectors dataset (source `setting`); else `-Debx.connectors.llm.default`
  (`property`); else the only usable `llm` connector (`only-usable`: active, no configuration problem, its secret set); else
  `none` with the reason → `NO_DEFAULT_LLM`. A setting or property that names no `llm` connector is `none` with that reason,
  never skipped for the next source. A setting naming an inactive `llm` connector is followed (the call then answers
  `CONNECTOR_UNUSABLE`). The setting is read with `Adaptation.get` after checking the node exists (a model loaded before
  wave 12 has no such node: no setting).
- **Answer size** (`ContractService.maxTokens(asked, own)`, 1.1.0): `own` = `LlmConnector.configuredMaxTokens(config)`, the
  connector's `maxTokens` property (1024 when unset or not a positive number). Asked more → `own`, `capped: true`; asked
  less or equal → asked; nothing asked → `own`. The bound 1..65 536 of the parameter is unchanged (a larger value is
  `INVALID_ARGUMENT`, a value up to it is capped, not refused).

| Operation | Right | Parameters (`*` required) | Behaviour and answer |
|---|---|---|---|
| `contract.describe` | — | — | `{name, version, transport, operations[{name, since, right, description, params[{name, type, required, min?, max?, description}], result}], errors[{type, code}]}` |
| `llm.connectors` | READ | — | Every connector of type `llm`: `{id, label, provider (LlmConnector.providerOf), model (property or default model), maxTokens (the most a call gets, 1.1.0), chat (whether `llm.chat` serves its provider, 1.1.0), active, secretSet, usable, problem?}`; `default`, `defaultSource` (`setting`, `property`, `only-usable`, `none`), `defaultProblem`. No secret. |
| `llm.ask` | RUN | `connector`, `system` (≤ 200 000 chars), `prompt`* (≤ 1 000 000), `maxTokens` (1..65 536, capped at the connector's), `json` (boolean), `timeoutMs` (1 000..600 000) | Connector = `connector`, else the default (above). Not in the dataset → `NOT_FOUND`; not type `llm` → `NOT_LLM`; the registry refuses it (inactive, problem, clear secret, host refused by `Egress`, invalid configuration) → `CONNECTOR_UNUSABLE`; `LlmConnector.secretProblem()` → `CONNECTOR_UNUSABLE`. Then `ContractService.askWith` → `LlmConnector.ask(system, prompt, maxTokens applied, json, timeoutMs)` (§3.2.52): the request `complete` sends, with the connector's `system` when none is given, the applied `maxTokens` (above), `json` deciding the native JSON mode through the connector's `jsonMode` rule, and the read timeout of this call (`AbstractRestConnector.send(..., readTimeoutMs)` → `HttpJsonClient.withReadTimeout`, a copy of the client: the cached connector keeps its own). A timeout anywhere in the causes → `TIMEOUT`; any other `ConnectorException` → `LLM_FAILED` (redacted). Answer `{text, model (as the provider reports it, else the configured one), provider, connector, usage{inputTokens, outputTokens, totalTokens} (null when not reported; total = in + out when only those two are), truncated (anthropic `stop_reason=max_tokens`, openai-shaped `finish_reason=length`, google `finishReason=MAX_TOKENS`, ollama `done_reason=length`), maxTokensApplied, capped (1.1.0), durationMs}`. One audit line `contract=llm.ask connector=<id> [json=true] [capped=<n>] outcome=… ms=…`; never the prompt or the answer. |
| `llm.chat` (1.1.0) | RUN | `connector`, `system` (≤ 200 000), `messages`* (array, 1..500), `tools` (array, 0..128), `maxTokens` (1..65 536, capped as `llm.ask`), `json`, `timeoutMs` | One tool-use turn. `ChatRequest.parse` checks the items before anything else (`INVALID_ARGUMENT` naming `messages[i].field`): each message `{role: user, content}` (not blank), `{role: assistant, content?, toolCalls?[{id, name, arguments?: object}]}` (a content or calls; ids unique in the message), `{role: tool, toolCallId, content, isError?}`; unknown fields refused; the first message a user's, the last a user's or a tool's; the tool messages right after an assistant message answer each of its calls exactly once before any other message; tool names `[A-Za-z0-9_-]{1,64}`, unique; `inputSchema` a JSON Schema object of `type: object`; a content ≤ 1 000 000 characters, the conversation (contents and arguments) ≤ 4 000 000. Consecutive tool messages become one `LlmClient.Message.toolResults`. Then the connector as for `llm.ask`; its provider without a tool-use format (`LlmConnector.chatFormat`: google) → `NOT_SUPPORTED`, before any call. `ContractService.chatWith` → `LlmConnector.chatTurn` (§3.2.52): one HTTP call (plus the one-time `reasoning_effort: none` resend). Answer `{message{role: assistant, content (may be empty), toolCalls[{id, name, arguments: object}]}, stopReason (END, TOOL_USE, MAX_TOKENS, OTHER: `LlmClient.StopReason`), usage{inputTokens, outputTokens, totalTokens} (as `llm.ask`), model, provider, connector, maxTokensApplied, capped, durationMs}`. Errors as `llm.ask`. ebx-connectors never runs a tool: the caller runs them and calls again with the whole conversation. Audit line `contract=llm.chat connector=<id> messages=<n> tools=<n> [json=true] stop=<reason> [capped=<n>] outcome=… ms=…`: counts only, never a content, a tool name or an argument. |
| `flows.get` | READ | `key`* (≤ 200) | `FlowDocument.resolve`: the ROOT mapping whose label, trimmed, equals the trimmed key (exact case); several → `AMBIGUOUS_KEY`. None → `{key, found: false}`. Else `{key, found: true, mappingId, flow}`, `flow` = `ConfigTransfer.exportMapping` (the export's document for that mapping) without `id`s. |
| `flows.put` | MANAGE | `key`*, `flow`* (object), `dryRun` | `FlowDocument.toUpsert`: key not blank, no control character; `flow` an object whose fields are `ConfigTransfer.mappingFields()` plus `inputs`, `rules`, `children`, `feed` (`id`, `updateById`, `parent` refused); booleans `active`, `deleteMissing`, `createIfMissing`, `provenance`, `logEveryRecord`; integers `batchSize`, `maxAttempts`, `retryDelayMinutes`; others strings; `connector`, `operation`, `direction` (`INBOUND`/`OUTBOUND`), `dataset`, `tablePath` (absolute) required at the root; `inputs` {name: string}; `rules[]` fields of `ConfigTransfer.ruleFields()` (`policy` IF_EMPTY/ALWAYS/TRUSTED, `position` integer, `active` boolean); `children[]` need `label` and `tablePath`, checked the same way; `label`, when given, must equal the key. The key becomes `label`; an existing mapping of that key adds `id` + `updateById`, so the upsert updates it even if connector, operation, direction or table changed. Then the tool `connectors_mapping_upsert` with the caller's session (§4.5.3): its error → `OPERATION_FAILED`. Answer `{key, mappingId (null on a dry run of a new flow), created, dryRun, rulesWritten, childrenWritten, rulePolicies, policyDefaulted, notes, lineage}`. |
| `flows.delete` | MANAGE | `key`* | No mapping of that key → `{key, deleted: false}`. Else the tool `connectors_mapping_delete` on its id → `{key, deleted: true, mappingId, deletedRows}`. |
| `flows.run` | RUN | `key` or `mappingId` (exactly one), `recordKey`, `predicate`, `scope` (all, unsent, changed, failed, dataspaceDelta; any case), `inputs` (object; no `;`, line break, or `=` in a name), `max`, `dryRun`, `resumeFrom`, `async`, `fullRefresh` | Unknown key or id → `NOT_FOUND`. `ContractService.syncArguments`: `recordKey` → `key`, `inputs` → `name=value;…`; the tool `connectors_sync` on the mapping id. Answer = the tool's answer plus `key` and `mappingId`; its error → `OPERATION_FAILED`. `async` is passed through as it always was; the tool accepts it for a feed and, since wave 13 (contract 1.2.0), for an outbound flow with a `scope` and no `recordKey` (§2.14.6a); the answer is then `{runId, status: RUNNING, …}`. |
| `run.get` (1.2.0) | READ | `runId`* (≤ 200), `callsMax` (0..500), `callsFrom` | `ContractService.runArguments`, then the tool `connectors_run_get` as the caller: `{runId, direction?, mapping?, execution{…}, calls[], count, errors, callsMax, callsFrom, callsCut?, callsNextFrom?, replayable}` (§2.14.5, §2.14.6a). An unknown run answers an empty `calls[]` and no `execution`. |
| `run.stop` (1.2.0) | RUN | `runId`* (≤ 200) | The tool `connectors_run_stop`: `{runId, requested: true, note}`; best effort, in the JVM, at the run's next batch boundary - read `run.get` for `STOPPED`. |

Audit: `flows.put`, `flows.delete` and `flows.run` leave the tool's own line (`tool=connectors_mapping_upsert` …);
`llm.ask` and `llm.chat` leave `contract=llm.ask` / `contract=llm.chat` (`ConnectorsAudit.contract`). Tests: `InJvmContractTest`
(description, validation, rights, transport, MBean, default connector order, compatibility with 1.0 callers),
`ContractChatTest` (capping, `llm.chat` validation, two-turn tool exchanges against fake Anthropic and OpenAI servers, the
`reasoning_effort` fallback, Google refused, `AgentLoop` driving the contract through `ContractLlmChat`),
`ContractFlowDocumentTest`, `LlmConnectorTest` (contract call).

**The agent library side** (`com.on.ebx.agent`, built alone into `dist/on-ebx-agent.jar` by `build-agent.sh`, embedded by
the MDM Factory): `AgentLoop` takes an `LlmChat` (one method, `Turn send(system, tools, conversation)`); `LlmClient`
implements it and keeps its constructors, and the former `AgentLoop(LlmClient, …)` constructors stay (binary compatible).
`ContractLlmChat implements LlmChat` calls `llm.chat` through the MBean (`Transport` replaceable for tests), the caller's
`Session` passed as an `Object`: `new AgentLoop(new ContractLlmChat(session, connectorOrNull, maxTokens, timeoutMs), tools,
budget, listener)`. `ContractLlmChat.incompatibility()` = null when the offered contract has `llm.chat`; a refusal becomes
an `LlmException` (the loop ends `FAILED`) and `lastRefusal()` gives `error.data.type`; `lastMaxTokensApplied()`,
`lastCapped()`, `lastResult()` read the last answer. `LlmClient.request(format, …)` / `turnOf(format, answer)` are the
wire-format adapters as pure functions (used by `LlmConnector.chatTurn`); `Turn` has a public constructor and `Message`
public `role()`, `getText()`, `getToolCalls()`, `getResults()`.

---


### 4.6 Scheduler, triggers, workflow

#### 4.6.1 `ConnectorScheduledTask` (`scheduler`)

Registered in Administration > Task scheduler with this class name. All parameters are strings.

| Parameter | Default | Meaning |
|---|---|---|
| `connector` | — | connector id |
| `operation` | — | operation |
| `direction` | `INBOUND` | `OUTBOUND` (case-insensitive) or anything else = INBOUND |
| `dataspace` | — | required |
| `dataset` | — | required |
| `tablePath` | — | required |
| `predicate` | — | XPath filter on the table |
| `maxRecords` | `100` | non-numeric → 100 |
| `dryRun` | `false` | `true` (case-insensitive) |
| `scope` | `all` | outbound scope |
| `inputs` | — | feed run-time inputs `name=value;…` |
| `tolerate` | — (nothing tolerated) | causes for which the execution may succeed although the run is not clean: a list among `errors`, `rejected`, `conflicts`, `truncated`, `stopped`, or `all` (`true` = `all`; `none`/`false` = nothing). An unknown name makes the task fail before it runs anything (`Parameter 'tolerate': Unknown value …`) |

**Behaviour**
0. `tolerate` is parsed (`RunVerdict.tolerated`).
1. `ensure`.
2. `SyncTargets.resolve(repo, dataspace, dataset, null, tablePath, predicate)`. Errors: `dataspace and dataset are required`, `Dataspace '<x>' not found`, `Dataset '<x>' not found in <ds>`, `Either xpath or tablePath is required`.
3. `repo.mappingFor(dataspace, dataset, table, connector, operation, direction)`:
   - Candidates = active, non-child mappings applying to the table with exactly that connector, operation and direction.
   - Dataspace-bound mappings win over generic ones.
   - Several candidates → `OperationException` with the ambiguity message.
   - None → `No <DIR> mapping for <connector>/<operation> on <table>`.
4. `beginRun`.
   - Feed → `importFrom(..., maxRecords items, dryRun, "scheduler")`. Ignored inputs add execution information `inputs ignored (not read by the mapping as ${param.name}): …`.
   - A scope other than ALL with direction OUTBOUND → `sendScope(table, predicate request or null, scope, max, dryRun, "scheduler")`.
   - Otherwise → `syncRecords(request, mapping, max, dryRun, "scheduler")`.
   - An unknown `scope` value is parsed by `OutboundScope.parse` (core behaviour for unknown values not re-verified here).
5. Execution information: `run <runId>: <SyncTargets.summarise text>`. A `ConnectorException` becomes `OperationException`. `endRun` runs in finally.
6. **Verdict** (`core/RunVerdict`, pure, `RunVerdictTest`). The execution **fails** (`OperationException`, after the information of step 5 was added and after everything the run wrote was committed) when the run has a cause that `tolerate` does not list:

   | Cause | Feed (`ImportOutcome`) | Records (`List<SyncResult>`) |
   |---|---|---|
   | `errors` | `errorCount > 0`; or no item in error but failing results in the run (the call to the source failed, the target dataset is missing, a catalog scan could not complete: the counters alone read `NO_DATA`) | any result with `hasFailure()` (ERROR, or written with field errors) |
   | `rejected` | `rejected > 0` (no matching record and `createIfMissing` off) | — |
   | `conflicts` | `conflicts > 0` | any result with conflicts |
   | `truncated` | `truncated` (the source held more than `maxRecords`) | scope `all` and **no predicate** only: the run took `maxRecords` records and the table holds more (`RequestResult.isSizeGreaterOrEqual(max + 1)`); every execution restarts from the first record, so the rest is never reached. With a predicate the task may be draining a backlog (records leave the predicate once processed): the execution information says `maxRecords (<n>) reached: more records match the predicate…` and the execution does not fail on it |
   | `stopped` | `skipped > 0` (circuit breaker, cancellation) | — |

   Not causes: a record for which the source had no data (`NO_DATA`) and a record not eligible for the call (`SKIPPED`) - the ordinary life of an enrichment over a table. An outbound scope other than `all` that reaches `maxRecords` is not a cut read either (the scope is a backlog the next execution continues); it adds the information `maxRecords (<n>) reached: records of scope '<scope>' may remain for the next execution`.

   Message: `Run <runId> is not a success: <one sentence per cause, with the first error message>. [Raise the task parameter 'maxRecords' so that one run takes everything the source holds.] To let the execution succeed in spite of this, set 'tolerate' to <causes> (or 'all').` Redacted. A tolerated cause is still written: `tolerated by this task (tolerate=…): …`.
7. **`maxRecords` stays 100 by default.** The engine has no unbounded read: the maximum is handed to the connector as the `max` input of its operation and connector types bound it differently (SQS 1–10, Azure Queue 1–32, …), so no single value means "everything". A scheduled feed whose source holds more than the maximum now fails with the message above instead of being cut in silence.
8. What EBX does with the failure: `ExecutableTask.execute` records the stack trace as an execution event and throws a plain Quartz `JobExecutionException` (no unschedule flag), so the execution is reported failed and the schedule keeps firing. Read in the bytecode of 6.3.0-rc2, not observed on an instance.

#### 4.6.2 `RestSchemaRefreshScheduledTask` (`scheduler`)

| Parameter | Default | Rule |
|---|---|---|
| `connector` | — | required: `connector is required` |
| `schemaUrl` | — | else connector property `schema.url`, else `openapi.url`; none → `schemaUrl or connector property schema.url/openapi.url is required`; > 1000 characters refused |
| `minimumIntervalMinutes` | `60` | integer 1…10080 (`MIN_INTERVAL_MINUTES`, `MAX_INTERVAL_MINUTES` = 7 days), else `minimumIntervalMinutes must be an integer` / `…must be between 1 and 10080` |

**Behaviour**
- `ensure`.
- The connector row must exist (`Connector '<x>' does not exist`) and be visible to the task profile (`The scheduled-task profile cannot read connector '<x>'`).
- `Egress.check(url)` is enforced.
- The implementation must extend `AbstractRestConnector`, else `Connector '<x>' is not REST-based`.
- `refreshPersistedSchema(url, EbxRestSchemaRefreshStore, key, minutes·60000)` performs a conditional GET with persisted state.
- Information line: `Schema checked|not due; updated|unchanged; HTTP <status>; next refresh at <date>`.
- Any failure → `Schema refresh failed for '<x>': <msg>`.

#### 4.6.3 Workflow script task bean `ConnectorScriptTaskBean` (`workflow`)

- Declared in `module.xml`. Library key: `ebx-connectors@com.on.ebx.connect.ebx.workflow.ConnectorScriptTaskBean`. Labels in English and French.

**Inputs** (all strings)

| Property | Default | In `module.xml` |
|---|---|---|
| `mappings` | — | yes — "Mappings to run, in order: ids, labels or connector/operation (2,1,30 or GLEIF/lei,VIES/check)" |
| `connectors` | — | yes — "all their inbound mappings of the table (GLEIF,VIES,BAN)" |
| `connector` | — | yes |
| `operation` | — | yes |
| `direction` | `INBOUND` | yes |
| `dataspace` | — | yes (required at run) |
| `dataset` | — | yes (required at run) |
| `xpath` | — | yes — one record |
| `tablePath` | — | yes — whole table |
| `predicate` | — | yes |
| `maxRecords` | `200` | yes |
| `dryRun` | `false` | yes |
| `scope` | `all` | yes |
| `failOnError` | `false` | yes |
| `inputs` | — | **no** (setter exists; not declared) |

**Outputs**: `runId`, `report`, `changedCount`, `okCount`, `errorCount`, `conflictCount`, `hasConflicts` (`true`/`false`), `status`, `truncated` (`true`/`false`), `problems`.

- `truncated`: a feed among the mappings was cut at `maxRecords`, or the table (with the predicate) holds more records than `maxRecords`.
- `problems`: every cause found by `RunVerdict` (§4.6.1 step 6) over all the mappings of the step, comma-separated in the order `errors,rejected,conflicts,truncated,stopped`; empty for a clean run. This is what a workflow routes on to tell a complete run from a partial one; `failOnError` stays `false` by default and the step does not fail on these.
- A feed that failed as a whole (no item in error, a failing result in the run) counts as **one** failed call in `errorCount`, so `status` reads `ERROR` or `PARTIAL` instead of `NO_DATA`/`OK`, and `failOnError=true` fails the step on it. `status` is otherwise computed as before: a truncated or stopped run does not change it - read `truncated` and `problems`.

**Target resolution**: `SyncTargets.resolve`. With an `xpath`, the first matching record is looked up. If none matches, it retries 10 times at 500 ms intervals (5 s in total), because a workflow launched from an after-create trigger may run before the creating transaction commits. Then `No record matches <xpath>`.

**Mapping selection** (first rule that applies)
1. `mappings`: each token resolved with `Mappings.mappingNamed` (`No mapping '<x>'`) and required to apply to the target (`Mapping <id> (<label>) is not a mapping of <table>`).
2. `connectors`: INBOUND non-feed mappings of the table matching `id` or `id/op`.
3. `connector` (+ `operation`, `direction`): `mappingFor` (ambiguity → error; none → `No <DIR> mapping for …`).
4. Otherwise every INBOUND non-feed mapping of the table (active, non-child).
- An empty result → `No mapping selected for <table> (mappings=…, connectors=…, connector=…/…)`.

**Execution**
- `runId = ConnectorService.beginRun()`; origin `workflow <userId>`.
- **Record**: each mapping in order through `syncRecord(session, record, mapping, dryRun, origin)`; the record is re-read between mappings.
- **Table**, per mapping:
  - Feed → `importFrom(runtimeInputs(mapping, inputs), max, dryRun)`.
  - Outbound with a scope other than ALL → `sendScope(…)`.
  - Otherwise the first `maxRecords` records of the request, `syncRecord` on each. The loops are mapping-first: every record for mapping 1, then every record for mapping 2, …, although the code comment says the opposite.
- `endRun` (not in a finally: an exception thrown mid-run leaves the thread's run open until the next `beginRun`).

**Summary** (`SyncTargets.summarise`)

| Result status | Counted as |
|---|---|
| `OK` | ok+1, changed+1 |
| `DRY_RUN`, `NO_CHANGE` | ok+1 |
| `NO_DATA` | noData+1 |
| `SKIPPED` | skipped+1 |
| `ERROR` and anything else | errors+1 |

- conflicts += the number of conflicts of each result.
- Text: first line `<n> record(s): <changed> updated, <ok-changed> unchanged, <noData> without data, <skipped> skipped, <errors> error(s)`, then one line per result `<record>: <status> - <message>` while the text is under 3500 characters.

**Outputs computed**
- `report` = the text truncated to 180 characters (177 + `...`), because a workflow variable holds 180 characters at most. The full detail stays in `SyncLog`.
- `status`:
  - `errors == 0` → `NO_DATA` if `ok == 0`, else `OK`.
  - `errors > 0` → `ERROR` if `ok == 0`, else `PARTIAL`.
- `failOnError=true` with errors → `OperationException` `<n> connector call(s) failed:\n<text>`.

**Conflict user task**: route `hasConflicts=true` to a user task on `ebx-connectors@ConflictManager` (inputs `dataset`, `table`, `recordKey`; outputs `resolvedCount`, `remainingCount`; §2.4). See `tools/export/workflows/DemoEnrichmentConflicts.json`.

#### 4.6.4 `ConnectorTableTrigger` (`trigger`) — declared by customers on their own tables

```xml
<osd:trigger class="com.on.ebx.connect.ebx.trigger.ConnectorTableTrigger">
  <connector>GLEIF</connector><operation>lei</operation>
  <onCreate>true</onCreate><onModify>true</onModify>
  <watchedFields>./Identification/LegalEntityIdentifier</watchedFields>
</osd:trigger>
```

| Property | Default |
|---|---|
| `connector` | required |
| `operation` | required (setup error `ConnectorTableTrigger: connector and operation parameters are required`) |
| `direction` | `INBOUND` |
| `onCreate` | `true` |
| `onModify` | `false` |
| `watchedFields` | — comma list; fires only when a changed node path equals the entry or ends with it (a leading `.` is stripped) |
| `blocking` | `false` |
| `publish` | `record`; `golden` sends, at send time, the golden of a MERGED record instead (a DELETED or PENDING_APPROVAL record sends nothing; GOLDEN, UNSET and tables without Match and Merge send the record). Needs `blocking=false` (setup error otherwise). See docs/REALTIME-MASTERING.md |
| `skipOrigin` | — comma list of connector ids, or `*`; a write made by a `cdc` run of one of them is not queued (no echo to the source) |

**Behaviour**
- `handleAfterCreate` fires when `onCreate` is true.
- `handleAfterModify` fires when `onModify` is true and a watched field changed (or no watched fields are configured).
- Re-entrancy is blocked by a ThreadLocal, because the trigger's own write fires `afterModify` again.
- `ensure`, then `mappingFor(record's branch, dataset, table, connector, operation, direction)`:
  - Ambiguous → warn log and return.
  - None → silent return.
- `syncRecordInProcedure(procedureContext, record, mapping)` writes **in the same transaction**.
- On ERROR: warn log `trigger <c>/<op> on <record>: <message>`. When `blocking=true`, `OperationException` rolls the save back.

#### 4.6.5 Triggers declared in `Connectors.xsd`

| Table | Class | Property | Behaviour |
|---|---|---|---|
| `/root/Mapping` | `MappingCascadeTrigger` | `enabled` (`true`; `false` disables both lineage and cascade) | Details below. |
| `/root/MappingInput`, `/root/MappingRule` | `MappingPartLineageTrigger` | `enabled` | After create, modify or delete, `mappingChanged(mapping id)` is scheduled. When the `mapping` FK moved, the old mapping is also rescheduled. Never fails the transaction. |

**`MappingCascadeTrigger` details**
- After create: `DeclaredLineage.mappingChanged(id)` and `mappingChanged(parent)`.
- After modify:
  - `mappingChanged(id, retiredJob)`, where `retiredJob` = `OpenLineageEmitter.jobName(id, oldLabel)` when the label changed.
  - The old parent when the parent changed.
  - The current parent.
- After delete:
  - Extracts the id from the deleted record XPath (`[./id='N']`).
  - Schedules lineage retirement (old label) and the parent refresh. Lineage errors are only logged.
  - Then `ConfigTransfer.cascade(procedureContext, dataset, id)`. A failure → `OperationException` `Deleting the elements attached to mapping <id> failed: …`, which rolls the deletion back.
- The declaration needs a child property: a void `<osd:trigger/>` is refused by EBX.

---


#### 4.6.6 Workflow trigger bean `ChatWorkItemTrigger` (`workflow`)

- Declared in `module.xml`. Set as the `workflowTrigger` of a workflow model, with the module prefix: `ebx-connectors@com.on.ebx.connect.ebx.workflow.ChatWorkItemTrigger`. A model of **another** module accepts it (verified on `CatalogTagReview`, module `ebx-data-catalog`). Extends `WorkflowTriggerBean`; only two handlers do anything, the four others are empty.
- `handleAfterWorkItemCreation`: posts the work item to a chat channel. `handleBeforeWorkItemTermination`: edits that message into the outcome. Both swallow every failure (kernel log + a sync-log row): a notification never stops a workflow.
- Connector: `Slack` by default, `-Debx.connectors.workItemNotifier.connector` for another id. Missing or inactive → one info line, nothing posted. Operation `post` on a `slack` connector, `notify` on anything else (a `chat-webhook` connector gets the text and the URL, and no edit at the end).
- Connector properties read here (none is declared in the descriptor): `workflow.channel` (else the connector's `channel`), `workflow.channel.<role>` (channel of the work items offered to that role — the first offered profile that has one wins; the role name is `Profile.toString` without its `B`/`R`/`U` prefix, a user profile gives its user id), `workflow.baseUrl` (default `http://localhost:8080`), `workflow.startWorkItem` (default `true`).
- Message (built by `core/WorkItemNotices`): title = the work item's specific label, else its label; description; one link `<url|Open the task in EBX>`; a context line "Offered to <profiles> · Workflow <process instance label>". The title is **bold text, never a link**: Slack does not render a link wrapped in formatting markers (`*<url|label>*` opened a broken address, measured 2026-09-16). Locale = the session's, else the repository default; French and English texts.
- Link: `<baseUrl>/ebx/?service=%40workflow&workflowView=inbox&xpath=[./pnode='P' and ./process='X' and ./itemIndex='I']&startWorkItem=…` (URL-encoded), where `P|X|I` is the work item key split on its first and last `|`. Opened by a user the item is not offered to, EBX answers "The work item to select has not been found in your 'Inbox' folder".
- Outcome message: "✅ Accepted by <user>", "❌ Rejected by <user>" or "☑️ Completed", the user resolved through `DirectoryHandler.displayUser`, no link.
- State: the `post` writes a sync-log row with run id `workitem:<work item key>` (at most 160 characters) and message `channel=<id>;ts=<ts>`; the termination reads that row back (`logOfRun`) to edit the right message. Row not found (never posted, log purged) → the outcome is posted as a new message. Origin of both rows: `workflow trigger`.
- **Gap**: a forced termination (`POST /ebx-dataservices/rest/workflow/v1/workflows/{key}/actions/terminate`) does not call `handleBeforeWorkItemTermination`, so the message stays in its "offered" state with a dead link. Covering it would need `handleBeforeProcessInstanceTermination`.
- Offline contract: `WorkItemNoticesTest`. Verified end to end on 2026-09-16 (post, link opened, decision taken in EBX, message edited).

### 4.7 Schema-level constraints and enumerations (Java classes)

All messages are quoted exactly as emitted.

| XSD node | Class | Kind | Behaviour and messages |
|---|---|---|---|
| `Connector/type` | `ConnectorTypeEnumeration` | `osd:constraintEnumeration` | Values: `ConnectorRegistry.getTypes()` sorted. Display: `<type label> (<type>)`. Unknown value → **error** `Unknown connector type '<v>'; registered types: [...]`. Documentation: "One of the connector types registered in the module." (French variant). |
| `Mapping/operation` | `MappingOperationEnumeration` | `osd:constraintEnumeration`, dependency on `../connector` | Values: operations of the selected connector. Uses the configured instance through the registry; when inactive, `describeType(type).configure(config)`, so `rest` connectors list their `op.*` operations. Display: `<op> — <kind lower-case>: <description>`. No list (connector unknown or unavailable) → **warning** `The connector is unknown or unavailable: the operation '<v>' cannot be checked.`. Value not in the list → **error** `Operation '<v>' is not offered by this connector; available: [...]`. |
| `Mapping/tablePath` | `MappingTableConstraint` | `osd:constraint` (warnings only) | No dataset → `No dataset: the mapping cannot run until the dataset holding <path> is set.`; dataset missing → `Dataset '<ds>' not found[ in dataspace '<dsp>']: the mapping cannot run in this environment.`; table missing → `Table <path> not found in dataset '<ds>': the mapping cannot run in this environment.`; link field missing → `Link field <f> is not a field of <path>.`; keys missing → `Key field(s) [...] are not fields of <path>.` |
| `MappingRule/sourcePath`, `MappingRule/targetPath` | `MappingRulePathConstraint` | `osd:constraint` (warning) | Only values starting with `./` are checked, and only on the EBX side: the target for INBOUND, the source for OUTBOUND. The mapping is resolved via `../mapping`; if its table is unresolvable, nothing is reported (it is reported on the mapping). Message: `<path> is not a field of <tablePath>: this rule will fail.` |
| `MappingInput/expression` | `MappingInputConstraint` | `osd:constraint` (warning) | Every `${./…}` placeholder (regex `\$\{\s*(\./[^|}\s]+)`) must be a field of the mapping's table: `Field(s) [...] are not fields of <tablePath>: the input will be empty.` |

`ModelCheck`:
- `table()` resolves a table node through `DatasetLocator`.
- `hasField()` is true for non-`./` paths and for null tables.
- `missingFieldList()` checks a comma list.

`DatasetLocator.find(repo, dataspace, dataset, tablePath)`:
- With a dataspace: that branch only. A dataset whose model cannot serve the table is rejected.
- Without a dataspace: a depth-first search from the reference branch (max depth 12) over open branches, then branch children, then version children. The first dataset of that name whose model can serve the table wins.

---


### 4.8 `tools/` (developer scripts)

All scripts except `upgrade_snapshot.py` default to `http://localhost:8080` and account `admin:admin` (`EBX_AUTH` env var in the config scripts). They go through Data Services `…/data/v1/BConnectors/Connectors/root`.

| Script | Arguments | What it does |
|---|---|---|
| `build_xsd.py` | none | Regenerates `Connectors.xsd` from a Python description (imports `F`, `T`, `table` from `extend_xsd.py`). Validates the XML and writes the file. `password()` fields: `osd:password`, maxLength 4000, `UIPassword` with `PasswordTypeEncryption` (reversible). The description covers only Connector, ConnectorProperty, Mapping, MappingInput, MappingRule, FieldProvenance, Conflict, OutboundState, SyncLog — see Discrepancies. |
| `extend_xsd.py` | `[--check]` | Not a Connectors tool: patches a **Companies.xsd** of a FastTrack workspace at a hard-coded absolute path, inserting Companies rev 2 groups, tables and reference tables at four anchors. Idempotent through a marker. Also used as the emitter library by `build_xsd.py`. |
| `gen_logos.py` | `[out dir] [logos.txt path]` | Generates one 160×160 (or 160×176) SVG tile per connector id (brand colour, wordmark lines, pictogram) plus `default.svg`. Writes the sorted list to `logos.txt` when the second argument is given. The id table includes Wikidata, Nominatim, RechercheEntreprises (not seeded by the installer). |
| `export_config.py` | `[export dir]` (default `export/`) | GETs `Connector`, `ConnectorProperty`, `Mapping`, `MappingInput`, `MappingRule` (pageSize 5000) and writes the raw rows. `apiKey`/`password` become `TO_BE_SET` when set, else null. |
| `import_config.py` | `<export dir>` (default `export`) | Idempotent re-import. Connectors created only when the id is absent (fields `id, label, type, baseUrl, authType, apiKeyHeader, description, active, connectTimeoutMs, readTimeoutMs` + `trust`, `logo`, `category`), with their properties. Mappings created only when the **label** is absent: parents first, `parent` re-mapped, inputs and rules copied. Then missing inputs (mapping, name) and rules (mapping, sourcePath, targetPath) are added to existing mappings. Never deletes. |
| `config_gleif_wikidata.py` | none | Creates (by label, idempotent) GLEIF children (LEI identifier, legal and headquarters addresses), ultimate/direct parent mappings with relationship children, "GLEIF search by name -> LEI" (name without legal form through a `regex`), and Wikidata identifier children. Asserts that the seeded GLEIF mapping exists. |
| `config_recherche_entreprises.py` | none | Requires connector `RechercheEntreprises` (imported from OpenAPI). Creates "Recherche d'entreprises (data.gouv) -> company" (input `q` = SIREN, default name without legal form; `per_page=1`; `minimal=false`; 13 rules) and a SIREN identifier child. |
| `localize_steps.py` | `<modelName> <steps.json> [base URL] [user:password]` | PUTs `documentation/localizedDocumentations` for each step to `Bebx-workflow-definitions/<model>/root/definition/pnode/<stepId>`. Republish afterwards. |
| `upgrade_snapshot.py` | `capture <output> [--base URL] [--page-size 10000] [--tables …]`; `compare <before> <after>` | Credentials from `EBX_USER`/`EBX_PASSWORD`. Read-only GETs only; it deliberately avoids `/config`, which would run `ensure`. Captures 17 tables normalised: nulls dropped, secrets replaced by `__PRESENT__`, sorted by primary key, per-table SHA-256 in `manifest.json`; 404 = unavailable. `compare` fails when a pre-upgrade key is missing or a non-null field changed; exit code 1. |
| `export/*.json` | — | Live export of 2026-09-12 (README: 58 connectors, 52 mappings); secrets `TO_BE_SET`. |
| `export/examples/kafka-crm-partner.json` | — | Outbound mapping document "Company -> Kafka / CRM partner (transformations demo)" with inputs `key`, `table`, `topic=crm.partner.upsert`, rules, and three children (identifiers, sites, people). |
| `export/workflows/DemoEnrichmentConflicts.json` (+ `.steps.json`) | — | Model with module `ebx-connectors`. Variables `dataspace`=Companies, `dataset`=Companies, `companyId`=739, `mappings`=`140,139`. Step 1: bean in library mode (xpath `/root/Companies[./Identification/Id=${companyId}]`, dryRun false, failOnError false; every output mapped). Step 2: condition `hasConflicts == true`. Step 3: user task `ebx-connectors@ConflictManager` (branch, dataset, table `/root/Companies`, recordKey; outputs resolved/remaining). Step 4: record review. |
| `export/workflows/CompanyCreationEnriched.json` | — | Child dataspace → creation → EBX Script `Fasttrack.companies.enrichCompany` → steward validation → merge, or correction loop / close. It does **not** use the connectors bean. |

---


### 4.9 Defaults and constants

| Constant / property | Value | Where |
|---|---|---|
| `ebx.connectors.seed` | `all` (`none`) | `ConnectorsInstaller.SEED_MODE_PROPERTY` |
| `ebx.connectors.seedMappings` | `auto` (`always`, `never`) | `ConnectorsInstaller.SEED_PROPERTY` |
| `ebx.connectors.mcp` | `both` (`native`, `endpoint`, `none`) | `ConnectorsModuleRegistration`, `McpEndpoint.enabled` |
| table `AccessRight` | empty | `AccessRightTable`: rights given by the administrator in the user interface, added to the three roles below |
| `ebx.connectors.role.designer` (older name `ebx.connectors.adminRole`) | `ConnectorsDesigner` | `ConnectorsAccess` (the table rule `ConnectorsTableAccessRule` applies whether it is set or not) |
| `ebx.connectors.secrets.hideProperties` | `true` | `ConnectorPropertyValueAccessRule`: `false` shows credential `ConnectorProperty` values to every reader of the table |
| `ebx.connectors.role.operator` | `ConnectorsOperator` | `ConnectorsAccess` |
| `ebx.connectors.role.viewer` | `ConnectorsViewer` | `ConnectorsAccess` |
| `ebx.connectors.allowedHosts` | unset → egress not enforced | `Egress.PROPERTY` (shown by `ConnectorForm`) |
| `ebx.connectors.egress.requireAllowList` / `ebx.connectors.egress.blockPrivate` / `ebx.connectors.egress.privateExceptions` | unset / on / unset | `Egress.REQUIRE_PROPERTY`, `Egress.BLOCK_PRIVATE_PROPERTY`, `Egress.PRIVATE_EXCEPTIONS_PROPERTY` (§2.11.4) |
| `ebx.connectors.lineage.startupDelaySeconds` | 90 | `DeclaredLineage` (core) |
| Placeholder values | `TO_BE_SET`; legacy `A_RENSEIGNER` migrated | installer, `ConfigTransfer` |
| Default trust (model) | 50 | XSD |
| MCP protocol version | `2025-06-18`; server `ebx-connectors`, title `ON EBX connectors`, version `1.0` | `McpEndpoint` |
| REST `max` for sync | 200 | `ConnectorsResource.sync` |
| REST `max` for webhook | 10000; CSV row cap 100000; default separator `;` | `ConnectorsResource` |
| Outbound status filter | `FAILED` | `/outbound`, MCP |
| Conflict status filter | `OPEN` | `/conflicts`, MCP |
| Log search max | 50 | MCP |
| Sync user service | max 100 (range 1–500), dryRun true, scope SELECTED | `ConnectorSyncUserService` |
| Scheduler | maxRecords 100, direction INBOUND, dryRun false, scope all, tolerate empty (the execution fails on any cause of §4.6.1 step 6) | `ConnectorScheduledTask` |
| Schema refresh | interval 60 min (1–10080), URL ≤ 1000, state ≤ 1,000,000 | `RestSchemaRefreshScheduledTask`, `EbxRestSchemaRefreshStore` |
| Workflow bean | maxRecords 200, direction INBOUND, dryRun false, failOnError false, scope all; report ≤ 180 characters; xpath retry 10 × 500 ms | `ConnectorScriptTaskBean`, `SyncTargets` |
| Summary text cap | 3500 characters | `SyncTargets.summarise` |
| Trigger | onCreate true, onModify false, blocking false, direction INBOUND | `ConnectorTableTrigger` |
| Webhook | EBX session with the RUN right only (C2-10); no secret or signature header is read | `ConnectorsResource`, `Webhooks` |
| OpenAPI fetch | connect 10000 ms, read 30000 ms, UA `EBX-Connectors/1.0 (openapi)` | `OpenApiImport` |
| Designer | `MAX_SAMPLE_FIELDS` 400; PostgreSQL discovery page 500, generated query `LIMIT 100` | `MappingDesignerUserService` |
| Diagnostics | discovery page 25, preview 5 records | `ConnectorDiagnosticsAdapter` |
| Credential meter limit | 255 (`CredentialGuide.SECRET_MAX_LENGTH`) | pane / script |
| Uploaded logo | 256 KB file, 360000-character data URL, png/jpeg/webp | `UploadedLogoWidgetFactory` |
| Metrics last failure | 2000 characters | `EbxConnectorOperationalMetricsPersistence` |
| Conflict comment | 500 characters | `ConflictManagerUserService` |
| Run id format | `R<yyyyMMdd-HHmmss>-<4-digit sequence>` | `ConnectorService.beginRun` (core) |
| Logos | folder `connect/logos/`, resource `ebx-connectors:ext-images:connect/logos/<file>`, URL `/ebx-connectors/www/common/images/connect/logos/<file>` | `Logos` |
| UI bases | `/ebx/` (manager bridge), `/ebx-ui/` | `MappingForm` |

---

## 5. Data model reference

Every table and attribute of `Connectors.xsd`. Cardinality `0..1` means optional, `1..1` mandatory, `0..n` a repeatable (multi-valued) field. *Constraints* lists schema facets (maximum length, pattern), foreign keys, enumerations, computed values and the Java constraint classes; *Default* is the value declared in the schema — defaults applied by code at run time are given in the definition.

| Table | Label | Primary key | Attributes |
|---|---|---|---|
| [`ConnectorCategory`](#table-connectorcategory) | Connector categories | `/code` | 4 |
| [`Connector`](#table-connector) | Connectors | `/id` | 24 |
| [`ConnectorProperty`](#table-connectorproperty) | Connector properties | `/connector /key` | 3 |
| [`ConnectorTemplate`](#table-connectortemplate) | Connector templates | `/id` | 10 |
| [`Release`](#table-release) | Releases | `/version` | 8 |
| [`ReleaseItem`](#table-releaseitem) | Release items | `/release /id` | 7 |
| [`ReleaseMetric`](#table-releasemetric) | Release metrics | `/release /code` | 6 |
| [`ReleasePassStatus`](#table-releasepassstatus) | Release delivery status | `/release /code` | 8 |
| [`ReleasePassStep`](#table-releasepassstep) | Release pass steps | `/release /passCode /code` | 9 |
| [`SchemaRefreshState`](#table-schemarefreshstate) | Schema refresh state | `/connector /key` | 5 |
| [`SynchronizationCheckpoint`](#table-synchronizationcheckpoint) | Synchronization checkpoints | `/mapping` | 5 |
| [`Mapping`](#table-mapping) | Mappings | `/id` | 31 |
| [`MappingInput`](#table-mappinginput) | Mapping inputs | `/mapping /name` | 3 |
| [`MappingRule`](#table-mappingrule) | Mapping rules | `/id` | 9 |
| [`FieldProvenance`](#table-fieldprovenance) | Field provenance | `/id` | 11 |
| [`Conflict`](#table-conflict) | Conflicts | `/id` | 19 |
| [`ConnectorOperationalMetrics`](#table-connectoroperationalmetrics) | Connector health and metrics | `/connector` | 17 |
| [`ConnectorGovernance`](#table-connectorgovernance) | Connector governance | `/resourceId` | 10 |
| [`ConnectorGovernanceAudit`](#table-connectorgovernanceaudit) | Connector governance audit | `/id` | 6 |
| [`ExecutionRun`](#table-executionrun) | Execution runs | `/runId` | 8 |
| [`OutboundState`](#table-outboundstate) | Outbound deliveries | `/mapping /recordKey` | 14 |
| [`SyncLog`](#table-synclog) | Synchronisation log | `/id` | 17 |
| [`SeedMarker`](#table-seedmarker) | Seed markers | `/item` | 2 |

Besides the tables, one group of dataset-level fields: [`Settings`](#group-settings) (wave 12).

### Table `ConnectorCategory` — Connector categories

*Schema description:* Reference values used to group connectors in the connector catalog.

**Purpose.** Reference list of the categories used to group connectors in the Connector Catalog user service (for example Business applications, Messaging & events, Corporate registries). Each record is one catalog section with its heading, subtitle and position.

**Life cycle.** Created at startup by ConnectorsInstaller.migrate (called from ConnectorsModuleRegistration via ConnectorsInstaller.install), which adds any of the ten standard codes that are missing (AI, BUSINESS_APPS, CLOUD_STORAGE, DATA_PLATFORMS, DATABASE, MESSAGING, REFERENCE_DATA, CORPORATE_REGISTRIES, RISK_COMPLIANCE, GENERIC_INTEGRATION) and never updates existing rows. Administrators may add, edit or delete categories in the UI; when the system property ebx.connectors.adminRole is set, ConnectorsSchemaExtensions makes the table read-only for anyone outside that role and the EBX administrators. Read by ConnectorCatalogUserService.

**Identification.** Primary key is the technical code (e.g. BUSINESS_APPS), referenced by Connector.category. The record label is the display label.

**Technical properties.** path `/root/ConnectorCategory`; primary key `/code`; record label `${./label}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `code` | Code | string | 1..1 |  |  | Stable technical code of the category, used as the value of Connector.category. The installer seeds the ten codes listed for the table and assigns them automatically to connectors whose category is empty, based on the connector type (ConnectorsInstaller.seedCategory/defaultCategory, falling back to GENERIC_INTEGRATION). Mandatory; unique. |
| `label` | Label | string | 1..1 |  |  | Heading shown for this category's section in the Connector Catalog (e.g. 'Business applications'). Mandatory; written by the installer for seeded categories, editable by users. |
| `description` | Description | string | 0..1 |  |  | Short subtitle shown under the category heading in the Connector Catalog (e.g. 'CRM, ERP, HCM and enterprise SaaS platforms'). Optional; displayed empty when not set. |
| `displayOrder` | Display order | int | 0..1 |  |  | Sort position of the category section in the catalog, ascending (seeded values 10 to 90, in steps of 10, with DATABASE at 45). Optional. When empty the catalog code treats it as 0, so the category sorts first. Connectors whose category is empty or unknown appear in an extra 'Other' section that is not stored in this table. |

### Table `Connector` — Connectors

**Purpose.** One configured endpoint of an external application or service (a REST API, database, message broker, SaaS application, registry, LLM provider, etc.) that mappings, workflow beans, scheduled tasks, triggers and the REST/MCP APIs call. It holds the connector type (which Java implementation is used), the base URL, credentials, timeouts, source trust level, catalog presentation (category, logo) and the persisted result of the last explicit connection check.

**Life cycle.** Seeded at module startup by ConnectorsInstaller (seed, seedSalesforce, seedKafka, seedCatalog, seedLot2, seedLlmProviders, etc.), which only adds missing connector ids, with secrets set to TO_BE_SET. ConnectorsInstaller.migrate fills empty trust, logo and category values and renames legacy placeholders and auth types, without overwriting non-null values. Users create and edit connectors in the UI (ConnectorForm record form). Connectors are also created or updated by the OpenAPI import (OpenApiImport, from a UI service, REST or MCP) and by configuration import (ConfigTransfer.importConfig from REST POST or the MCP tool connectors_config_import). An import creates missing connectors with TO_BE_SET secrets and updates existing ones without touching their secrets. The connection-state fields are written by ConnectorConnectionStateService through EbxConnectorConnectionStateStore when a user runs Connect or Disconnect (ConnectorDiagnosticsUserService). Deletion is manual. When ebx.connectors.adminRole is set, only that role and administrators can write.

**Identification.** Primary key is the connector identifier (id, max 40 characters), e.g. 'Salesforce' or 'GLEIF'. The record label is '<label> (<id>)'.

**Technical properties.** path `/root/Connector`; primary key `/id`; record label `${./label} (${./id})`; record form `ConnectorForm`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `id` | Identifier | string | 1..1 |  | maxLength=40 | Unique identifier of the connector, referenced by Mapping.connector, ConnectorProperty.connector, workflow beans (the connectors/connector parameters, matched case-insensitively), scheduled tasks and REST/MCP calls. Max 40 characters; mandatory. Set on creation by the user, seed, OpenAPI import or configuration import. Changing it would break every reference. |
| `label` | Label | string | 0..1 |  | maxLength=120 | Free, human-readable name of the connector, shown in the catalog card (falls back to the id when empty), the record label, the sync log and the REST/MCP connector listings. Max 120 characters; optional. The schema description mentions 'mappings=<label>', but in code that parameter matches mapping labels, not connector labels. |
| `description` | Description | text | 0..1 |  | maxLength=2000 | Free-text description of the connector. The seeds use it to document the type's expected properties. It is shown on the catalog card and exported and imported by ConfigTransfer. Max 2000 characters; optional. |
| `category` | Category | string | 0..1 |  | Foreign key → /root/ConnectorCategory | Catalog category of the connector (foreign key to ConnectorCategory.code). If left empty, ConnectorsInstaller fills it on creation or at the next startup from the connector type (a descriptor category, else a built-in table by type; Countries gives REFERENCE_DATA, EORI gives RISK_COMPLIANCE, otherwise GENERIC_INTEGRATION). Users may override it. An empty or unknown code puts the connector in the catalog's 'Other' section. It is not part of the configuration export/import field list. |
| `logo` | Logo | resource | 0..1 |  | UI widget LogoWidgetFactory | Module image resource shown with the connector, e.g. 'ebx-connectors:ext-images:connect/logos/Salesforce.svg'. Rendered with LogoWidgetFactory. The installer sets a preferred logo for known connector ids when the value is empty (Logos.preferredFor), and replaces the generated EBX logo on the 'EBX' connector. Used for logoUrl only when no valid uploaded logo exists. Optional; exported and imported by ConfigTransfer. |
| `uploadedLogo` | Upload custom logo | text | 0..1 |  | maxLength=360000; UI widget UploadedLogoWidgetFactory | Optional custom logo uploaded from the user's computer through UploadedLogoWidgetFactory, stored inline as an image data URL (PNG, JPEG or WebP; UI limit 256 KB, field max 360,000 characters). When present and accepted (UploadedLogoWidgetFactory.isAcceptedDataUrl), it takes precedence over /logo in the computed logoUrl. Written by the user only. |
| `logoUrl` | Logo URL | string | 0..1 |  | Computed (function LogoUrlFunction) | Computed, not stored (LogoUrlFunction). It returns the uploaded logo data URL when valid; otherwise the web URL of the /logo resource (/ebx-connectors/www/common/images/...); otherwise the module's default.svg logo. Used as the image of tile views and by the Connector Catalog cards. |
| `type` | Connector type | string | 1..1 |  | maxLength=60; Dynamic enumeration: ConnectorTypeEnumeration | Connector type, which selects the Java implementation: one of the types registered in ConnectorRegistry (built-in classes, META-INF/services extensions, or 'rest' for a configuration-only REST API declared through op.<name>.* properties). Values come from the dynamic enumeration ConnectorTypeEnumeration, which rejects unknown types with an error. Mandatory; max 60 characters. It also drives the default trust and category assigned by the installer. A type whose implementation is missing makes the connector UNAVAILABLE in the catalog. |
| `baseUrl` | Base URL | string | 0..1 |  | maxLength=500 | Root URL of the external service (or the JDBC/broker address for the types that use it), to which operation paths are appended. Checked against the module's outbound egress policy before the connector is instantiated (Egress.checkBaseUrl). Part of the verified-connection fingerprint. Max 500 characters; optional, because some types do not need it. |
| `authType` | Authentication | string | 0..1 | `NONE` | maxLength=40; Values: `NONE` (None), `BASIC` (HTTP Basic), `API_KEY_HEADER` (API key in a header), `BEARER` (Bearer token), `OAUTH2` (OAuth 2.0 (token endpoint and flow in the oauth.* properties)) | How the connector authenticates HTTP calls, as applied by AbstractRestConnector.headers(). Values: NONE = no credentials header; BASIC = Authorization: Basic built from username and password; API_KEY_HEADER = the API key sent in the header named by apiKeyHeader; BEARER = Authorization: Bearer <apiKey>; OAUTH2 = a token obtained from the endpoint and flow described by the oauth.* connector properties (oauth.tokenUrl, oauth.flow, oauth.clientId, etc.), sent in the oauth.tokenHeader header (default Authorization). Schema default NONE; code also treats an empty or unrecognised value as NONE. The installer migrates the legacy value OAUTH2_CLIENT_CREDENTIALS to OAUTH2. Types that manage their own authentication (e.g. Google Geocoding, Salesforce, Snowflake) may use the key regardless of this field. |
| `username` | User name | string | 0..1 |  | maxLength=120 | User name for BASIC authentication, for the OAuth2 'password' flow, and for types that need an account (databases, Kafka SASL, etc.). The installer replaces the legacy placeholder A_RENSEIGNER with TO_BE_SET. Part of the connection fingerprint. Max 120 characters; optional. |
| `password` | Password | password | 0..1 |  | maxLength=4000; Password field, reversible encryption | Password of the account, stored with EBX's reversible encryption (osd:password, max 4000). At run time Secrets.reveal decrypts it and resolves vault:/env:/file: references. A value stored in clear is accepted with a one-time warning unless ebx.connectors.secrets.allowClear=false, in which case the connector reports a configuration problem. TO_BE_SET, A_RENSEIGNER or empty count as not configured, and BASIC calls then fail fast with 'password not configured'. Seeds and imports set TO_BE_SET; never exported. |
| `apiKeyHeader` | API key header | string | 0..1 |  | maxLength=120 | Name of the HTTP header that carries the API key when authType is API_KEY_HEADER (e.g. X-INSEE-Api-Key-Integration). No header is sent if it is empty. Filled by seeds and by the OpenAPI import from the API-key security scheme. Max 120 characters; optional. |
| `trust` | Trust level (0-100) | int | 0..1 | `50` |  | Reliability score (0-100) of this source for the TRUSTED rule policy: a value written by a more trusted source is never overwritten by a less trusted one, and a manual entry counts as 100. Recorded in FieldProvenance.trust and Conflict.proposedTrust. Schema default 50, and code also uses 50 when empty. The installer sets a type-based value on seed and fills empty values at startup (e.g. gleif/sirene/companies-house/inpi 90, dnb/openfda 85, vies 80, melissa 75, ebx 70, wikidata 40, llm 30, otherwise the descriptor trust or 50). |
| `apiKey` | API key / token | password | 0..1 |  | maxLength=4000; Password field, reversible encryption | Secret of the connector (API key, bearer token, OAuth2 client secret or type-specific token), stored with reversible encryption (max 4000). It is sent in the header named by apiKeyHeader for API_KEY_HEADER, as Authorization: Bearer for BEARER, as the client secret for OAUTH2, or the way the type expects (e.g. a query parameter for Google Geocoding). Same secret handling as /password (decryption, vault/env/file references, clear-text policy). TO_BE_SET or empty means not configured, and API_KEY_HEADER/BEARER calls then fail with 'API key not configured'. Seeds, the OpenAPI import and configuration import write TO_BE_SET; never exported. |
| `connectTimeoutMs` | Connect timeout (ms) | int | 0..1 | `10000` |  | Maximum time in milliseconds to establish the connection to the external service; passed to the HTTP client, JDBC login timeout (converted to seconds, minimum 1), SFTP, Kafka and RabbitMQ clients. Schema default 10000. If the stored value is empty, ConnectorRepository passes 0 to the implementation. Part of the connection fingerprint. |
| `readTimeoutMs` | Read timeout (ms) | int | 0..1 | `30000` |  | Maximum time in milliseconds to wait for a response once connected; used as the HTTP read timeout, JDBC query timeout (seconds, minimum 1), and polling deadline for asynchronous types (Snowflake, Databricks, BigQuery, Kafka, which apply their own minimums). Schema default 30000. If the stored value is empty, ConnectorRepository passes 0. Part of the connection fingerprint. |
| `active` | Active? | boolean | 0..1 | `true` |  | Whether the connector may be used. When false, ConnectorRegistry refuses to instantiate it ('Connector ... is inactive'), so no mapping, workflow bean, scheduled task or REST call bound to it runs. Webhook feeds answer 403, the catalog shows it as Inactive, and OpenLineage sinks ignore it. Schema default true; code also treats empty as true. Seeds set true, while packaged product templates describe connectors as inactive until configured. The schema description, which talks about mappings, is inherited from the Mapping table. |
| `connectionStatus` | Connection status | string | 0..1 | `NOT_CONNECTED` | Values: `NOT_CONNECTED`, `CONNECTED`, `FAILED` | Persisted result of the explicit Connect action (ConnectorDiagnosticsAdapter.connectAndDiscover, then ConnectorConnectionStateService.verifyAndRecord), written by the engine and not by the user. NOT_CONNECTED = never verified, or revoked by Disconnect; CONNECTED = the connector's testConnection succeeded at connectionVerifiedAt; FAILED = the test was unsupported or unsuccessful, or threw. CONNECTED never means an open socket is kept. When reading, code derives an extra STALE state (not stored) if the status is CONNECTED but the current configuration fingerprint differs from connectionFingerprint. Schema default NOT_CONNECTED; an unknown stored value is read as NOT_CONNECTED. |
| `connectionVerifiedAt` | Connection verified at | dateTime | 0..1 |  |  | Date and time of the last successful connection verification. Set to now on CONNECTED, and cleared on FAILED and on Disconnect. Written by the engine (EbxConnectorConnectionStateStore). |
| `connectionFingerprint` | Verified configuration fingerprint | string | 0..1 |  | maxLength=64 | SHA-256 fingerprint of the connection-relevant configuration at verification time: type, baseUrl, authType, username, password, apiKeyHeader, apiKey, both timeouts and all connector properties (ConnectorConnectionStateService.materialFingerprint). Compared with the current configuration to report a CONNECTED connection as STALE after any of these settings change. Set on CONNECTED and FAILED, cleared on Disconnect. Max 64 characters; engine-written. |
| `connectionMessage` | Connection message | string | 0..1 |  | maxLength=500 | Message returned by the last connection verification or Disconnect ('Disconnected', 'Connection verification failed', or the connector's test message), with secrets redacted and truncated to 500 characters. Engine-written; informational. |
| `properties` | Properties | string | 0..0 |  | Association (inverse of /root/ConnectorProperty/connector) | Association (not stored) listing the ConnectorProperty rows whose connector is this record, i.e. the type-specific options of the connector. |
| `mappings` | Mappings | string | 0..0 |  | Association (inverse of /root/Mapping/connector) | Association (not stored) listing the Mapping rows bound to this connector. |

### Table `ConnectorProperty` — Connector properties

**Purpose.** A key/value option of a connector, holding the type-specific settings that do not have a dedicated Connector field. Examples: OAuth2 settings (oauth.tokenUrl, oauth.flow, oauth.clientId...), operation declarations of the generic rest/graphql/soap types (op.<name>.method/.path/.query), schema.url or openapi.url, accept, clientId, tenantId, country, verified.only.

**Life cycle.** Seeded with their connectors by ConnectorsInstaller (via its property() helper), with secret values set to TO_BE_SET. Legacy A_RENSEIGNER values are renamed to TO_BE_SET at startup by ConnectorsInstaller.migrate. Created or replaced by the OpenAPI import (OpenApiImport) and created or updated by configuration import (ConfigTransfer: a placeholder value never overwrites a value already set). Maintained by users in the UI (ConnectorForm shows them). Read by ConnectorRepository.toConfig for every connector lookup. Admin-only when ebx.connectors.adminRole is set.

**Identification.** Composite primary key (connector, key): one value per property name per connector. The record label is the key.

**Technical properties.** path `/root/ConnectorProperty`; primary key `/connector /key`; record label `${./key}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `connector` | Connector | string | 1..1 |  | Foreign key → /root/Connector | Connector that owns the property (foreign key to Connector.id). Mandatory; part of the primary key. The schema description, which talks about a mapping, is inherited from Mapping.connector. |
| `key` | Key | string | 1..1 |  | maxLength=120 | Name of the option, interpreted by the connector type. Examples: oauth.* for OAUTH2 authentication; op.<name>.method (GET/POST/PUT/DELETE), op.<name>.path and op.<name>.query to declare an operation of the generic rest type; schema.url or openapi.url for the scheduled REST schema refresh; accept for the Accept header; plus per-type keys documented in the seeded connector's description. Validated against the type's descriptor by ConnectorConfigurationValidator; a missing required key makes the connector MISCONFIGURED. Max 120 characters; mandatory. |
| `value` | Value | string | 0..1 |  | maxLength=2000 | Value of the option, as a string. Resolved at read time by Secrets.resolveIfReference, so a vault:, env: or file: reference (or another registered scheme) is replaced by the secret it points to, and an unresolvable reference is reported as a configuration problem. TO_BE_SET marks a value still to be entered. Part of the connection fingerprint. Max 2000 characters; optional. |

### Table `ConnectorTemplate` — Connector templates

*Schema description:* Versioned packaged templates and customer-owned copies. Customer copies are never replaced by packaged upgrades.

**Purpose.** Versioned connector configuration templates (product profiles). PACKAGED templates ship with the module (ProductConnectorTemplates) and describe a ready-made connector setup, e.g. a master-data profile for an installed connector. CUSTOMER templates are customer-owned copies derived from a packaged template. The configuration is stored as a JSON document without secrets.

**Life cycle.** PACKAGED rows are installed or upgraded at startup by PackagedProductProfileInstaller, only for templates whose configuration.connectorId matches an existing connector, through ConnectorTemplateService.importTemplates and EbxConnectorTemplateStore under the actor 'ebx-connectors-packaged-profile-installer'. A packaged template is replaced only by a higher version and never downgraded. Customer copies are created through the REST API of ConnectorsResource: POST /templates/{id}/copy, /templates/{id}/upgrade (a new copy based on the newest packaged version, keeping the old copy), and /templates/import. GET /templates exports all templates with secrets redacted. No code path overwrites or deletes a CUSTOMER row; imports refuse documents that contain secret values. Writes use the caller's EBX permissions.

**Identification.** Primary key is the template id (portable identifier matching [A-Za-z0-9][A-Za-z0-9_.-]{0,127}). The record label is '<id> v<version>'.

**Technical properties.** path `/root/ConnectorTemplate`; primary key `/id`; record label `${./id} v${./version}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `id` |  | string | 1..1 |  |  | Unique, portable identifier of the template (letters, digits, '_', '.', '-'; must start with a letter or digit; up to 128 characters, enforced by the ConnectorTemplate class). It is chosen by the caller for a copy (?id= parameter) or comes from the packaged definition. Mandatory. |
| `version` |  | int | 1..1 |  |  | Revision number of the template, at least 1. Packaged upgrades compare it: an incoming packaged template with a higher version replaces the existing packaged row, the same version is ignored, and a lower one is rejected. Customer copies are always created with version 1. Mandatory; engine-written. |
| `ownership` |  | string | 1..1 |  | Values: `PACKAGED`, `CUSTOMER` | Who owns the template. PACKAGED = shipped with the module and replaceable by a newer packaged version; it cannot have basedOn. CUSTOMER = a customer copy that is never replaced by packaged upgrades or imports; it must keep basedOn and basedOnVersion. Mandatory. |
| `basedOn` |  | string | 0..1 |  |  | For a CUSTOMER template, id of the PACKAGED template it was derived from (for a copy of a customer copy, its original packaged origin). Must be empty for PACKAGED templates. Used by the upgrade operation to find the newer packaged version. |
| `basedOnVersion` |  | int | 0..1 |  |  | For a CUSTOMER template, version of the packaged origin at copy time (at least 1). Upgrade is allowed only when the packaged template's current version is higher. Empty for PACKAGED templates; code reads empty as 0. |
| `configuration` |  | text | 1..1 |  | maxLength=1000000 | The template's connector configuration as a JSON object (for packaged profiles: connectorId, label, type, category, active=false, baseUrl, authentication, description, and more). It must parse as a JSON object, otherwise reading fails. Imports reject any secret-like key (password, secret, apiKey, token, privateKey, credential) that has a real value, and exports mask such keys as '***'. Max 1,000,000 characters; mandatory. |
| `createdBy` |  | string | 0..1 |  |  | Actor who created the row: the authenticated user of the REST call, or 'ebx-connectors-packaged-profile-installer' for packaged installation. Set once on creation by EbxConnectorTemplateStore. |
| `createdAt` |  | dateTime | 0..1 |  |  | Timestamp of creation, set once by EbxConnectorTemplateStore. |
| `updatedBy` |  | string | 0..1 |  |  | Actor of the last write (creation or packaged upgrade), set by EbxConnectorTemplateStore. |
| `updatedAt` |  | dateTime | 0..1 |  |  | Timestamp of the last write (creation or packaged upgrade), set by EbxConnectorTemplateStore. |

### Table `Release` — Releases

*Schema description:* Product and customer release-note headers. Packaged installation is additive and never replaces an existing record.

**Purpose.** Header of a release note of the connectors module: one delivered or in-progress version with its date, status, title and summary. It is the parent of ReleaseItem, ReleaseMetric, ReleasePassStatus and ReleasePassStep, and is rendered by the Release Notes user service (ReleaseNotesUserService).

**Life cycle.** Installed additively at every startup by ConnectorsInstaller.seedReleaseNotes from the packaged resource com/on/ebx/connect/release/release-notes.xml (PackagedReleaseNotes). A missing version is created as PACKAGED; an existing header is never modified. Customers may add their own rows (ownership CUSTOMER) in the UI; a CUSTOMER header prevents the installer from adding packaged content for that version. No code deletes rows. The Release Notes service is disabled on table and record contexts by ConnectorsSchemaExtensions.

**Identification.** Primary key is the version string: the release 'version' attribute for formal releases, or the release id for development previews (e.g. development-preview-2026-09-09-pass10). The record label is '<version> — <title>'.

**Technical properties.** path `/root/Release`; primary key `/version`; record label `${./version} — ${./title}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `version` |  | string | 1..1 |  |  | Version identifier of the release; key referenced by all child release tables. Versions starting with 'development-preview' are displayed with a generic preview title instead of /title. Mandatory. |
| `ownership` |  | contentOwnership (string) | 1..1 |  | Values: `PACKAGED`, `CUSTOMER` | Content ownership. PACKAGED = row installed from the module's packaged release notes (release-notes.xml) by ConnectorsInstaller.seedReleaseNotes; CUSTOMER = row authored by the customer. The installer never modifies an existing row: a Release header owned by CUSTOMER blocks packaged installation of that version and of all its child rows, while missing packaged children are added under a PACKAGED header. |
| `releaseDate` |  | date | 0..1 |  |  | Release date (the releasedOn attribute of the packaged XML; may be empty for an in-progress preview). The Release Notes page lists releases by this date, newest first, then by version in descending order, and shows it next to the title. |
| `status` |  | string | 1..1 |  | Values: `CURRENT`, `SUPPORTED`, `ARCHIVED`, `DRAFT`, `IN_PROGRESS` | Publication status of the release, shown as a localized badge. CURRENT = the current formal release (set by the installer for kind='formal' entries); IN_PROGRESS = development preview or ongoing delivery (set by the installer for non-formal entries); SUPPORTED = an older release still supported; ARCHIVED = a historical release; DRAFT = not published, hidden from the Release Notes page. Only DRAFT changes behaviour in code; SUPPORTED and ARCHIVED are only displayed. Mandatory. |
| `title` |  | string | 1..1 |  |  | Title of the release shown as the heading of its section (replaced by a generic preview title for development-preview versions). Mandatory. |
| `summary` |  | text | 0..1 |  | maxLength=10000 | Short summary paragraph shown under the release heading. Max 10,000 characters; optional. |
| `buildVersion` |  | string | 0..1 |  |  | Build number of the delivered artifact, shown in the release footer together with commitId when either is present. The packaged installer does not set it; entered manually. Optional. |
| `commitId` |  | string | 0..1 |  |  | Source-control commit identifier of the build, shown in the release footer together with buildVersion when either is present. The packaged installer does not set it; entered manually. Optional. |

### Table `ReleaseItem` — Release items

**Purpose.** One line of a release note (a feature, fix, security change, upgrade note or known issue) belonging to a Release. Items are grouped by type and ordered on the Release Notes page.

**Life cycle.** Created by ConnectorsInstaller.seedReleaseNotes from the section/item elements of the packaged release-notes.xml, only when the (release, id) pair does not exist yet and the parent release is not CUSTOMER-owned; existing rows are never modified. Customers may add rows manually. Never deleted by code.

**Identification.** Composite primary key (release, id). The record label is the item title.

**Technical properties.** path `/root/ReleaseItem`; primary key `/release /id`; record label `${./title}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `release` |  | string | 1..1 |  | Foreign key → /root/Release | Release the item belongs to (foreign key to Release.version). Mandatory. |
| `id` |  | string | 1..1 |  |  | Identifier of the item within its release: the item's explicit id attribute in the packaged XML, else '<section category>-<order>' (e.g. fixed-30). Mandatory. |
| `ownership` |  | contentOwnership (string) | 1..1 |  | Values: `PACKAGED`, `CUSTOMER` | Content ownership. PACKAGED = row installed from the module's packaged release notes (release-notes.xml) by ConnectorsInstaller.seedReleaseNotes; CUSTOMER = row authored by the customer. The installer never modifies an existing row: a Release header owned by CUSTOMER blocks packaged installation of that version and of all its child rows, while missing packaged children are added under a PACKAGED header. |
| `type` |  | string | 1..1 |  | Values: `FEATURE`, `FIX`, `SECURITY`, `UPGRADE`, `KNOWN_ISSUE` | Kind of change, used to group items on the Release Notes page. FEATURE = new capability (the default for any unrecognised section category); FIX = defect correction (category 'fixed'); SECURITY = security change; UPGRADE = upgrade or migration note; KNOWN_ISSUE = known limitation (category 'known-issue'). Mandatory. |
| `displayOrder` |  | int | 1..1 |  |  | Sort position of the item within its release, ascending. The installer numbers items 10, 20, 30… in XML order across sections. Mandatory. |
| `title` |  | string | 1..1 |  |  | Text of the item shown in bold. For packaged items it is the item text itself (the same as detail). Mandatory. |
| `detail` |  | text | 0..1 |  | maxLength=10000 | Longer description of the item, shown under its title when different or present. Max 10,000 characters; optional. |

### Table `ReleaseMetric` — Release metrics

**Purpose.** A key figure displayed in the metrics strip of a release note (for example a number of connectors, tests or mappings), as a label/value pair.

**Life cycle.** Created by ConnectorsInstaller.seedReleaseNotes from the metric elements of the packaged release-notes.xml when the (release, code) pair is missing and the parent release is not CUSTOMER-owned; never updated or deleted by code. Customers may add their own.

**Identification.** Composite primary key (release, code). The record label is '<label>: <value>'.

**Technical properties.** path `/root/ReleaseMetric`; primary key `/release /code`; record label `${./label}: ${./value}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `release` |  | string | 1..1 |  | Foreign key → /root/Release | Release the metric belongs to (foreign key to Release.version). Mandatory. |
| `code` |  | string | 1..1 |  |  | Technical code of the metric within its release (the code attribute in the packaged XML). Mandatory. |
| `ownership` |  | contentOwnership (string) | 1..1 |  | Values: `PACKAGED`, `CUSTOMER` | Content ownership. PACKAGED = row installed from the module's packaged release notes (release-notes.xml) by ConnectorsInstaller.seedReleaseNotes; CUSTOMER = row authored by the customer. The installer never modifies an existing row: a Release header owned by CUSTOMER blocks packaged installation of that version and of all its child rows, while missing packaged children are added under a PACKAGED header. |
| `label` |  | string | 1..1 |  |  | Caption shown under the figure. Mandatory. |
| `value` |  | string | 1..1 |  |  | Displayed figure, stored as free text so it can include units or symbols (e.g. '120+'). Shown in bold. Mandatory. |
| `displayOrder` |  | int | 1..1 |  |  | Sort position of the metric within the release, ascending; the installer assigns 10, 20, 30… in XML order. Mandatory. |

### Table `ReleasePassStatus` — Release delivery status

**Purpose.** Delivery status of one 'pass' (a delivery increment or workstream) of a release, with its progress percentage and remaining gates. Shown as a collapsible block in the 'delivery' section of the Release Notes page, containing its ReleasePassStep entries.

**Life cycle.** Created by ConnectorsInstaller.seedReleaseNotes from the pass elements of the packaged release-notes.xml when the (release, code) pair is missing and the parent release is not CUSTOMER-owned. Existing rows are never updated, so a later packaged change to a pass's status or progress does not reach an already-installed row. Customers may add or edit rows manually; never deleted by code.

**Identification.** Composite primary key (release, code). The record label is '<label>: <status>'.

**Technical properties.** path `/root/ReleasePassStatus`; primary key `/release /code`; record label `${./label}: ${./status}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `release` |  | string | 1..1 |  | Foreign key → /root/Release | Release the pass belongs to (foreign key to Release.version). Mandatory. |
| `code` |  | string | 1..1 |  |  | Code of the pass within its release (e.g. pass-1); ReleasePassStep.passCode refers to it. Mandatory. |
| `ownership` |  | contentOwnership (string) | 1..1 |  | Values: `PACKAGED`, `CUSTOMER` | Content ownership. PACKAGED = row installed from the module's packaged release notes (release-notes.xml) by ConnectorsInstaller.seedReleaseNotes; CUSTOMER = row authored by the customer. The installer never modifies an existing row: a Release header owned by CUSTOMER blocks packaged installation of that version and of all its child rows, while missing packaged children are added under a PACKAGED header. |
| `label` |  | string | 1..1 |  |  | Title of the pass shown in the block summary (e.g. 'Pass 1 — Catalog and custom connector form'). Mandatory. |
| `status` |  | string | 1..1 |  | Values: `COMPLETE`, `IN_PROGRESS`, `BLOCKED`, `PLANNED` | Delivery state of the pass, shown as a localized badge. COMPLETE = delivered (its block is collapsed by default); IN_PROGRESS = under way; BLOCKED = stopped by an impediment; PLANNED = not started (the packaged XML value 'pending' is converted to PLANNED). Any status other than COMPLETE opens the block by default. Mandatory. |
| `progress` |  | int | 1..1 |  |  | Completion percentage of the pass, rendered as a progress bar with maximum 100 (e.g. 80). Mandatory; no range check in the schema. |
| `remainingGates` |  | text | 0..1 |  | maxLength=10000 | Free text describing what remains before the pass is complete (the remainingGates attribute or child of the packaged XML, else the pass's own text). Shown at the top of the block. Max 10,000 characters; optional. |
| `displayOrder` |  | int | 1..1 |  |  | Sort position of the pass within the release, ascending; the installer assigns 10, 20, 30… in XML order. Mandatory. |

### Table `ReleasePassStep` — Release pass steps

*Schema description:* Immutable, ordered delivery-history entries belonging to a release pass.

**Purpose.** An ordered delivery-history entry (step or sub-step) of a release pass, e.g. 'Seeded connector categories — complete'. Listed inside its pass block on the Release Notes page.

**Life cycle.** Created by ConnectorsInstaller.seedReleaseNotes from the step elements of each pass, and from substeps/substep groups, in the packaged release-notes.xml, only when the (release, passCode, code) triple is missing and the parent release is not CUSTOMER-owned. Described as immutable: the installer never updates or deletes them. Customers may add rows manually.

**Identification.** Composite primary key (release, passCode, code). The record label is '<label>: <status>'.

**Technical properties.** path `/root/ReleasePassStep`; primary key `/release /passCode /code`; record label `${./label}: ${./status}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `release` |  | string | 1..1 |  | Foreign key → /root/Release | Release the step belongs to (foreign key to Release.version). Mandatory. |
| `passCode` |  | string | 1..1 |  |  | Code of the pass the step belongs to; it matches ReleasePassStatus.code for the same release (not a declared foreign key). Only steps with a matching pass are displayed. Mandatory. |
| `code` |  | string | 1..1 |  |  | Identifier of the step within its pass (the step's code attribute, or the substep's id attribute, in the packaged XML). Mandatory. |
| `ownership` |  | contentOwnership (string) | 1..1 |  | Values: `PACKAGED`, `CUSTOMER` | Content ownership. PACKAGED = row installed from the module's packaged release notes (release-notes.xml) by ConnectorsInstaller.seedReleaseNotes; CUSTOMER = row authored by the customer. The installer never modifies an existing row: a Release header owned by CUSTOMER blocks packaged installation of that version and of all its child rows, while missing packaged children are added under a PACKAGED header. |
| `label` |  | string | 1..1 |  |  | Text of the step shown in bold: the label attribute, else the title child or the step's own text (for substeps, the detail text). Mandatory. |
| `status` |  | string | 1..1 |  | Values: `COMPLETE`, `IN_PROGRESS`, `BLOCKED`, `PLANNED` | State of the step, shown as a localized badge. COMPLETE = done; IN_PROGRESS = under way; BLOCKED = stopped; PLANNED = not started (XML 'pending' is converted to PLANNED). Mandatory. |
| `occurredOn` |  | date | 0..1 |  |  | Date on which the step happened or was completed (the occurredOn attribute), when known. Stored but not displayed by the Release Notes page. Optional. |
| `detail` |  | text | 0..1 |  | maxLength=10000 | Additional explanation shown under the step label when not empty. Max 10,000 characters; optional. |
| `displayOrder` |  | int | 1..1 |  |  | Sort position of the step within its pass, ascending: the explicit order attribute, else 10, 20, 30… in XML order. Mandatory. |

### Table `SchemaRefreshState` — Schema refresh state

*Schema description:* Technical, restart-safe REST schema cache.

**Purpose.** Technical cache that keeps a REST connector's remote schema (OpenAPI or similar document) across server restarts. It also stores the HTTP validators (ETag, Last-Modified) and timestamps needed to refresh it conditionally. Each record holds the refresh state for one schema URL of one connector.

**Life cycle.** Created and updated only by the engine, when the scheduled task RestSchemaRefreshScheduledTask runs for a REST-based connector. The task resolves the URL from its schemaUrl parameter or the connector properties schema.url/openapi.url, checks the egress policy, calls AbstractRestConnector.refreshPersistedSchema, and saves the result through EbxRestSchemaRefreshStore without elevated privileges, so the scheduler's profile needs write access. Never deleted by code. Admin-only when ebx.connectors.adminRole is set.

**Identification.** Composite primary key (connector, key), where key = 'schema-' followed by the SHA-256 hex digest of the trimmed schema URL. The record label is '<connector> <url>'.

**Technical properties.** path `/root/SchemaRefreshState`; primary key `/connector /key`; record label `${./connector} ${./url}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `connector` | Connector | string | 1..1 |  | Foreign key → /root/Connector | Connector whose schema is cached (foreign key to Connector.id). Mandatory. |
| `key` | State key | string | 1..1 |  | maxLength=80 | State key derived from the URL: 'schema-' + SHA-256 hex digest of the trimmed schema URL (EbxRestSchemaRefreshStore.keyFor). The store refuses a key that does not match its URL. Max 80 characters; engine-written. |
| `url` | Schema URL | string | 1..1 |  | maxLength=1000 | Absolute URL of the schema document being refreshed (from the task parameter schemaUrl, else the connector property schema.url, else openapi.url). Max 1000 characters, also enforced in code. Mandatory; engine-written. |
| `state` | Conditional refresh state | text | 1..1 |  | maxLength=1000000 | JSON state produced by RestSchemaRefresh.exportState: etag and lastModified validators sent back as If-None-Match / If-Modified-Since, the last usable schema document, checkedAt, and nextRefreshAt (epoch milliseconds, based on the task's minimum interval, 1 to 10080 minutes, default 60). Contains no credentials. Max 1,000,000 characters, also enforced in code. Mandatory; engine-written. |
| `updatedAt` | Updated at | dateTime | 1..1 |  |  | Timestamp of the last save of this state by EbxRestSchemaRefreshStore. Mandatory; engine-written. |

### Table `SynchronizationCheckpoint` — Synchronization checkpoints

*Schema description:* Durable incremental progress, stored separately from mapping policy. A full run never clears this state.

**Purpose.** Durable incremental-synchronization progress of one mapping: the last checkpoint value (timestamp, id, cursor, delta token…) from which the next incremental run continues. It is stored apart from the mapping policy (Mapping.loadMode/checkpointStrategy), and a FULL run never clears it.

**Life cycle.** Designed to be written by the engine through SynchronizationCheckpointManager.complete, after a successful, non-dry-run incremental execution (loadMode other than FULL), via EbxSynchronizationCheckpointStore and ConnectorRepository.saveCheckpoint with an optimistic version check. It is read by SynchronizationCheckpointManager.inputs to inject the value into the mapping's checkpoint input. In the current source, neither EbxSynchronizationCheckpointStore nor CommittedIncrementalExecution is instantiated by any runtime entry point, so the table is not fed by module code as deployed; it can be read via ConnectorRepository.checkpoint. Never deleted by code.

**Identification.** Primary key is the mapping id (one checkpoint per mapping). The record label is '<mapping> (<strategy>)'.

**Technical properties.** path `/root/SynchronizationCheckpoint`; primary key `/mapping`; record label `${./mapping} (${./strategy})`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `mapping` | Mapping | string | 1..1 |  | Foreign key → /root/Mapping | Mapping whose progress this is (foreign key to Mapping.id, stored as the mapping's numeric id in text form). Mandatory; unique. |
| `strategy` | Strategy | string | 1..1 |  | maxLength=40; Values: `TIMESTAMP`, `MONOTONIC_ID`, `ORDERED_STRING`, `VENDOR_CURSOR`, `ODATA_DELTA`, `EBX_DELTA`, `SNAPSHOT` | Incremental strategy under which the value was produced (SynchronizationStrategy); it must equal the mapping's current checkpointStrategy, otherwise the run fails with 'Stored checkpoint strategy differs from mapping policy'. TIMESTAMP = source last-modified timestamp; MONOTONIC_ID = strictly increasing id or sequence; VENDOR_CURSOR = opaque provider cursor; ODATA_DELTA = OData delta link or token; EBX_DELTA = EBX-native delta or history; SNAPSHOT = versioned snapshot compared or replaced as a unit. FULL is not allowed in a checkpoint. Mandatory. |
| `value` | Checkpoint value | text | 1..1 |  | maxLength=1000000 | The checkpoint itself: extracted from the successful response at the mapping's checkpointValuePath (a JSON pointer, or a dotted path converted to one; a scalar is kept as text, an object or array as JSON). It is injected as the mapping's checkpointInput on the next incremental run; when no checkpoint exists, Mapping.checkpointInitialValue is used. An empty extracted value makes the run fail. Max 1,000,000 characters; mandatory. |
| `updatedAt` | Updated at | dateTime | 1..1 |  |  | Completion time of the run that advanced the checkpoint (defaults to now). Mandatory; engine-written. |
| `version` | Version | int | 1..1 | `1` |  | Optimistic concurrency token: the save succeeds only if the stored version (0 when no row exists) still equals the version read before the run, then increments it by 1. Otherwise it throws 'Checkpoint was advanced concurrently'. Limited to Integer.MAX_VALUE. Schema default 1; engine-written. |

### Table `Mapping` — Mappings

**Purpose.** A mapping binds one operation of one configured connector to one EBX table, in one direction. Inbound, it turns the document returned by the operation into field values of an EBX record (or, for an import feed, into records created or updated per item); outbound, it builds a JSON document from an EBX record and sends it through a write operation. A mapping carries its operation inputs (MappingInput), its field rules (MappingRule) and, optionally, sub-object mappings describing child tables (collections of the parent document).

**Life cycle.** Created by a user in the ON EBX UI (MappingForm record form, then the graphical designer MappingDesignerUserService, which also creates reverse mappings (inactive, direction swapped), response mappings and sub-object mappings), by seeding at module start (ConnectorsInstaller seeds GLEIF, Sirene, Google Geocoding, Salesforce, Kafka, Snowflake, Melissa/Loqate mappings only when their target table and input fields exist, unless the seed policy is 'always' or 'never'), by configuration import (ConfigTransfer, REST POST /config and MCP connectors_config_import: matched by connector, operation, direction, tablePath and label; sub-objects by parent and label), by connectors_mapping_upsert (ConfigTransfer.upsertMapping), or by OpenAPI import. Updated by the same paths; at start-up ConnectorsInstaller also normalises the stored dataspace to the dataspace where the dataset is actually found. Deleted from the UI, Data Services, import or the 'Delete this mapping and everything attached' service / DELETE /rest/v1/mappings/{id} / connectors_mapping_delete; MappingCascadeTrigger (after delete) then removes its sub-object mappings (recursively), rules, inputs, synchronization checkpoints and outbound states, and nulls the mapping reference on FieldProvenance and Conflict rows (SyncLog is untouched). The same trigger schedules the declared OpenLineage description on every create/modify/delete (DeclaredLineage), retiring the old job when the label changes. The engine only reads mappings (ConnectorRepository.mappings()).

**Identification.** Primary key /id, an auto-incremented integer assigned by EBX (differs between environments, which is why import/export match on connector, operation, direction, table and label instead). Default label '${label} [${connector} / ${operation} ${direction}]'. The REST API, workflow bean and MCP tools accept either the id or the label (case-insensitive; Mappings.mappingNamed also accepts 'connector/operation' when it matches exactly one root mapping). In code an empty label falls back to 'connector / operation'.

**Technical properties.** path `/root/Mapping`; primary key `/id`; record label `${./label} [${./connector} / ${./operation} ${./direction}]`; record form `MappingForm`; trigger `MappingCascadeTrigger`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `id` | Id | id (int) | 1..1 |  | autoIncrement | Technical identifier of the mapping, auto-incremented by EBX. It is the value referenced by MappingInput/mapping, MappingRule/mapping, Mapping/parent, OutboundState/mapping, FieldProvenance/mapping, Conflict/mapping and SyncLog/mapping, and the id accepted by POST /sync/{mapping}, mappings=<ids> and the MCP tools. Never set by the user. |
| `connector` | Connector | string | 1..1 |  | Foreign key → /root/Connector | The configured connector (foreign key to Connector) whose operation this mapping calls. Mandatory. At run time the connector is instantiated through ConnectorRegistry; an unknown or inactive connector makes the call fail with ERROR. Mapping selection by connector/operation (workflow bean, scheduled task, table trigger) compares this value exactly. |
| `operation` | Operation | string | 1..1 |  | maxLength=60; Dynamic enumeration: MappingOperationEnumeration | Name of the connector operation the mapping calls (e.g. GLEIF 'lei', Salesforce 'upsert', Kafka 'consume'). Mandatory, max 60 characters. Its value list is dynamic (MappingOperationEnumeration): the operations of the selected connector (for the 'rest' type, those declared by its op.* properties); an operation not offered by the connector is a validation error ('Operation 'x' is not offered by this connector; available: [...]'), while an unknown/unavailable connector only yields a warning. At run time the operation's direction must be compatible with /direction (MappingCompatibility: from the operation descriptor, else READ/SEARCH = inbound, WRITE = outbound), and every required input of the operation must resolve to a non-empty value or the call is SKIPPED. |
| `direction` | Direction | string | 0..1 | `INBOUND` | maxLength=40; Values: `INBOUND` (Inbound (external system to EBX)), `OUTBOUND` (Outbound (EBX to external system)) | Direction of the flow. INBOUND (schema default): the document returned by the operation is written into the EBX record; rule sourcePath = JSON path, targetPath = EBX field. OUTBOUND: a document is built from the record (rule sourcePath = EBX field, targetPath = JSON path) and sent; its answer can be stored by a twin inbound 'response mapping' on the same connector, operation and table. An empty or unrecognised stored value is read as INBOUND by ConnectorRepository. It also decides which side of a rule MappingRulePathConstraint checks against the table, and which outbound machinery applies (OutboundState, scopes, deleteOperation, retries are outbound-only). Incremental load is refused for OUTBOUND. |
| `label` | Label | string | 0..1 |  | maxLength=120 | Free business name of the mapping (max 120). Used as an alternative identifier in the REST API (/sync/{label}, /webhook/{label} for feeds), the workflow bean and MCP tools, as part of the matching key of configuration import/upsert (with connector, operation, direction and tablePath; for sub-objects, parent + label), and as the OpenLineage job name (fallback 'mapping-<id>' when empty). Required by ConfigTransfer.upsertMapping ('mapping.label is required'). Renaming it retires the previously declared lineage job. |
| `description` | Description | text | 0..1 |  | maxLength=2000 | Free-text documentation of the mapping (max 2000). Exported and imported with the configuration (ConfigTransfer) and displayed in the form; not read by the synchronisation engine. |
| `dataspace` | Dataspace | string | 0..1 |  |  | Restricts the mapping to one dataspace (EBX dataspace name, without the Data Services 'B' prefix). A mapping may leave the dataspace empty: it then applies to a record wherever the dataset exists, workflow child dataspaces included. A TABLE-scoped run (feed import, scope send, catalog scan, enrich of a whole table) needs exactly one dataspace serving the dataset; copies in child dataspaces of that dataspace do not count; several distinct dataspaces → the run fails with `Dataset 'X' … is served by N dataspaces (…): name the dataspace on the mapping`, and the upsert refuses it. When several mappings match the same connector/operation/direction/table, one bound to the record's dataspace wins over generic ones; several candidates at the same level are refused as ambiguous ('Several INBOUND mappings for ... Name the mapping by its id'). At module start ConnectorsInstaller never fills a blank value, strips a stray 'B' prefix only when the stripped key resolves, and never replaces a stored dataspace that no longer resolves (a WARN names the mapping). Checked with the dataset by MappingTableConstraint (warning only). |
| `dataset` | Dataset | string | 0..1 |  |  | Name of the EBX dataset holding the target table (e.g. Companies). Optional in the schema, but a mapping without dataset cannot run: MappingTableConstraint warns 'No dataset: the mapping cannot run until the dataset holding ... is set', and warns too when the dataset is not found in the (given) dataspace. For a record-level call a non-empty value must equal the record's dataset (MappingDefinition.appliesTo); for feeds and outbound scopes it locates the dataset. Required by connectors_mapping_upsert; inherited by a sub-object mapping from its parent when omitted there. |
| `tablePath` | Table path | string | 1..1 |  | maxLength=255; Constraint: MappingTableConstraint | Absolute path of the EBX table the mapping reads or writes (e.g. /root/Companies; for a sub-object mapping, the child table). Mandatory, max 255. A mapping is selected for a record only when this path equals the record's table path exactly. MappingTableConstraint warns (never blocks) when the table does not exist in the dataset, and, for sub-objects, when parentLinkField or childKeyFields are not fields of this table. |
| `responseRoot` | Response root (JSON path) | string | 0..1 |  | maxLength=255 | JSON path applied to the connector document before the rules are evaluated (e.g. data.attributes for GLEIF, organization for D&B), so that rule source paths are relative to it. Empty: rules read the document root. Also applied to the parent document before its sub-object collections are resolved. Editable in the designer toolbar. |
| `active` | Active? | boolean | 0..1 | `true` |  | Whether the mapping can be selected (schema default true; an empty value is also treated as active by code). An inactive mapping is never chosen by table/connector lookups (mappingsFor/mappingFor), and an inactive sub-object mapping is not attached to its parent, so its child table is not processed. Reverse mappings created by the designer start inactive. Deactivation retires the declared lineage job. |
| `parent` | Parent mapping | string | 0..1 |  | Foreign key → /root/Mapping | Foreign key to the parent Mapping; set only on a sub-object mapping (addresses, contacts, identifiers...). Such a mapping describes one element of a collection of the parent document and is processed only as part of its parent (never selected on its own, never a feed). Deleting the parent deletes its children through MappingCascadeTrigger. Created by the designer's 'Create a sub-object mapping' button, seeding (childMapping) or import/upsert 'children'. |
| `collectionPath` | Collection path (JSON) | string | 0..1 |  | maxLength=255 | Sub-object mappings only: JSON path, in the parent document (after the parent's responseRoot), of the collection mapped to the child table, e.g. entity.addresses or claims.P946. An array gives one child row per element; a single JSON object is a collection of one; an empty path designates the parent document itself (one element); a path absent from the document gives no element, so nothing is created. Outbound, the same path is where the child documents are built in the sent document. |
| `parentLinkField` | Parent link field | string | 0..1 |  | maxLength=255 | Sub-object mappings only: path of the child table field holding the foreign key to the parent record, e.g. ./company. Set to the parent record's primary key on every created child row and used to find the existing children. If empty, the child mapping is not applied and the error 'parentLinkField is not set' is reported. MappingTableConstraint warns when it is not a field of the child table. |
| `childKeyFields` | Child key fields | string | 0..1 |  | maxLength=500 | Sub-object mappings only: comma-separated child-table field paths identifying an existing child row to update, e.g. ./type or ./type,./City. The engine looks for a row linked to the parent whose key fields equal the values computed by the rules for the element (plus childFilter); found = update, otherwise create. Empty, or a key value not produced for the element: always create. MappingTableConstraint warns on fields missing from the child table. |
| `childFilter` | Child filter (XPath) | string | 0..1 |  | maxLength=500 | Sub-object mappings only: XPath predicate restricting which existing child rows this mapping manages, e.g. ./source='GLEIF' or ./type='LEI'. It is ANDed to the lookup of an existing row by childKeyFields and to the selection of rows deleted by deleteMissing, so rows maintained by other sources are left alone. Empty: all child rows of the parent are in scope. |
| `deleteMissing` | Delete children absent from the document? | boolean | 0..1 | `false` |  | Sub-object mappings only: when true, after processing the collection, child rows linked to the parent (and matching childFilter) that were not matched or created from the current document are deleted, and logged as deletions in the changes. Schema default false (code also treats empty as false). |
| `itemsPath` | Items path (imports) | string | 0..1 |  | maxLength=255 | Import feeds only: JSON path of the array of items in the connector document, e.g. messages[*].value, rows, records. Each item is imported separately. Empty: the whole document is the single item (or, if the document itself is an array, each of its elements). Items beyond the run maximum are ignored; no item gives a NO_DATA log row. |
| `keyPredicate` | Record key predicate (imports) | string | 0..1 |  | maxLength=500 | XPath predicate locating the target record of each imported item, with ${json.path} placeholders resolved from the item, e.g. ./Identification/LegalEntityIdentifier='${lei}'. A non-empty value on a root mapping makes it an import feed (MappingDefinition.isFeed): it is run without a record (POST /sync/{mapping} without key, webhook, scheduler, user service) and each item is processed in its own transaction. EBX rejections are wrapped as 'Key predicate rejected by EBX on <table>: <predicate> (...)', with a hint to use date-equal() for date fields. |
| `createIfMissing` | Create the record when not found (imports)? | boolean | 0..1 | `true` |  | Import feeds only: when the key predicate finds no record, create one (true, schema default; empty is also treated as true) or skip the item with status SKIPPED 'No record matches ... and creation is off' (false). |
| `loadMode` | Load mode | string | 0..1 | `FULL` | Values: `FULL`, `INCREMENTAL` | Synchronisation policy. FULL (schema default; empty or unknown values are read as FULL): the complete source is requested. INCREMENTAL: only changes after a persisted checkpoint are meant to be requested, using checkpointStrategy. Before every call MappingSynchronizationPolicy.requireValid checks the policy against the operation descriptor and fails the call (ERROR) when unsupported: FULL must be among the operation's synchronization strategies; INCREMENTAL requires an INBOUND mapping ('Incremental load is only valid for inbound mappings'), a strategy ('Incremental load requires a checkpoint strategy') and a strategy the operation supports. Checkpoint values themselves live in the SynchronizationCheckpoint table, not here. The injection/advance logic is SynchronizationCheckpointManager; in this build no run path instantiates it (verified by search), so the setting is currently validated but not applied to the inputs. |
| `checkpointStrategy` | Checkpoint strategy | string | 0..1 |  | Values: `TIMESTAMP`, `MONOTONIC_ID`, `ORDERED_STRING`, `VENDOR_CURSOR`, `ODATA_DELTA`, `EBX_DELTA`, `SNAPSHOT` | Meaning of the incremental position when loadMode = INCREMENTAL: TIMESTAMP (source modification timestamp), MONOTONIC_ID (increasing identifier), VENDOR_CURSOR (opaque cursor/page token of the vendor API), ODATA_DELTA (OData delta link), EBX_DELTA (EBX dataspace delta), SNAPSHOT (fingerprint of a full snapshot). No schema default; ignored (forced to none) when loadMode is FULL; an unknown value is read as none. Must be supported by the operation descriptor, and must equal the strategy of an already stored checkpoint ('Stored checkpoint strategy differs from mapping policy'). |
| `checkpointInput` | Checkpoint input | string | 0..1 |  | maxLength=120 | Name of the connector input that receives the last checkpoint value on an incremental run, e.g. since, after, cursor or deltaLink. Required for incremental execution by SynchronizationCheckpointManager ('Incremental mapping has no checkpoint input'); when a checkpoint (or the initial value) is non-empty it overrides that input. |
| `checkpointValuePath` | Checkpoint value path | string | 0..1 |  | maxLength=500 | JSON path (dotted or JSON Pointer) in the successful response from which the next checkpoint value is taken (timestamp, id, cursor, delta link or snapshot value). Empty: the whole response. A successful incremental response without a value is an error ('Successful incremental response has no checkpoint value'); FULL runs and dry runs never advance the checkpoint. |
| `checkpointInitialValue` | Initial checkpoint value | text | 0..1 |  | maxLength=2000 | Optional bootstrap checkpoint used as the checkpoint input value before any SynchronizationCheckpoint row exists for the mapping (e.g. a start date). Configuration only: the runtime progress is stored separately and never written back here. Empty: the first incremental run sends no checkpoint input. |
| `checkpointBoundary` | Watermark boundary | string | 0..1 |  | Values: `INCLUSIVE`, `EXCLUSIVE` | For import feeds with a row watermark only (§2.14.7): whether a row whose watermark EQUALS the saved checkpoint is read again at the next run. INCLUSIVE is the safe choice (a row committed at the source after the previous read, with that very value, is not lost) and needs unchangedIfEqual so the rows read again report NO_CHANGE without a write; EXCLUSIVE skips them. Empty: INCLUSIVE when unchangedIfEqual is set, EXCLUSIVE otherwise. |
| `deleteOperation` | Delete operation (outbound) | string | 0..1 |  | maxLength=60 | Outbound mappings: name of the connector operation called for records that disappeared. With scope 'changed', a record present in OutboundState as SENT but absent from the table; with scope 'dataspaceDelta', a record present only in the dataspace's initial snapshot; with scope 'failed' or a replay, a FAILED/errored record that no longer exists. The call receives the mapping inputs resolved on the snapshot record when there is one, plus 'key' (primary key) and the scalar fields of the last document sent; success marks the OutboundState row DELETED, failure FAILED; SyncLog records it with this operation name. Empty: deletions are not propagated (ignored). Not validated against the connector's operation list at design time. |
| `maxAttempts` | Maximum attempts (outbound) | int | 0..1 | `5` |  | Outbound retry policy: number of failed sends after which a FAILED record is no longer retried by the scopes 'changed', 'unsent' and 'failed' (counted as 'waiting') and stays FAILED until a manual replay (POST /replay/{runId}). Compared with OutboundState/attempts. Schema default 5; empty = 5; values below 1 are raised to 1. |
| `retryDelayMinutes` | Delay between attempts, minutes (outbound) | int | 0..1 | `30` |  | Outbound retry policy: minimum number of minutes since OutboundState/lastAttemptAt before a FAILED record is retried by the scopes 'changed', 'unsent' and 'failed'; until then it is counted as waiting. Schema default 30; empty = 30; negative values are treated as 0. |
| `provenance` | Record field provenance? | boolean | 0..1 | `true` |  | Whether a synchronisation through this mapping records FieldProvenance rows. Schema default true; empty also read as true. Set to false on a mapping whose target table already carries its own lineage (a landing table's _loadId/_sourceSystem) so imports skip FieldProvenance bookkeeping entirely - ConnectorRepository.recordProvenance is the single choke point for this guard, shared by record sync, feed import and the conflict-accept path. Conflict logging (Conflict table) is unaffected. |
| `unchangedIfEqual` | Skip when unchanged (target field) | string | 0..1 |  | maxLength=255 | Path of a field of the TARGET table (e.g. ./_rowHash). When non-empty and the target record already exists, an import feed compares the value computed for this field against the record's current value (MappingEngine.unchanged) before applying anything; an exact match reports the whole row NO_CHANGE and skips the write entirely - no doModifyContent, no provenance, no sub-objects, ALWAYS-policy rules on technical fields (_loadId, _receivedAt...) included. Empty: the check is off. No effect when the field was not produced by any rule, or the record does not yet exist (a creation is never "unchanged"). |
| `batchSize` | Import batch size | int | 0..1 | `1000` |  | Number of rows an import feed writes together when it really runs: rows are looked up and mapped first, then written in batches of this size, one transaction per batch; a failing batch is split until the row at fault is isolated. Set 1 to write row by row. Empty or below 1 = 1000; capped at 10000. Ignored by a simulation and by outbound synchronisation (§14.4). |
| `logEveryRecord` | Log every record? | boolean | 0..1 | `false` |  | Off: one SyncLog line per import batch plus one per record in error or in conflict. On: one line per imported record, created, updated or unchanged. |
| `inputs` | Inputs | string | 0..0 |  | Association (inverse of /root/MappingInput/mapping) | Association (inverse of MappingInput/mapping): the operation inputs of this mapping with their expressions, shown as a table on the Inputs tab of MappingForm and edited in the designer's Service call panel. Not stored on the mapping itself. |
| `children` | Sub-object mappings | string | 0..0 |  | Association (inverse of /root/Mapping/parent) | Association (inverse of Mapping/parent): the sub-object mappings of this mapping (child tables fed from collections of the document), shown on the Sub-objects tab. Only active children are applied at run time. Not stored on the mapping itself. |
| `rules` | Rules | string | 0..0 |  | Association (inverse of /root/MappingRule/mapping) | Association (inverse of MappingRule/mapping): the field rules of this mapping, applied in position order, shown on the Rules tab and drawn in the graphical designer. Not stored on the mapping itself. |

### Table `MappingInput` — Mapping inputs

**Purpose.** One input value passed to the connector operation when a mapping runs: the input name the operation expects and an expression computing its value, typically from fields of the EBX record being synchronised, run-time parameters or constants (e.g. lei = ${./Identification/LegalEntityIdentifier}).

**Life cycle.** Created and edited by users in the mapping form's Inputs tab or the designer (MappingDesignerUserService 'Save the rules' rewrites the inputs of the mapping; 'Create the reverse mapping' copies them), by seeding (ConnectorsInstaller), and by configuration import / connectors_mapping_upsert (ConfigTransfer replaces all inputs of the mapping when 'inputs' is given). Deleted when its mapping is deleted (MappingCascadeTrigger via ConfigTransfer.cascade). MappingPartLineageTrigger re-describes the mapping's declared lineage after every create, modify or delete. Read-only for the engine.

**Identification.** Composite primary key (/mapping, /name): one expression per input name per mapping. Default label is the input name.

**Technical properties.** path `/root/MappingInput`; primary key `/mapping /name`; record label `${./name}`; trigger `MappingPartLineageTrigger`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `mapping` | Mapping | string | 1..1 |  | Foreign key → /root/Mapping | Foreign key to the Mapping this input belongs to; part of the primary key. Rows whose mapping id cannot be resolved are ignored when mappings are loaded. Used by MappingInputConstraint to find the table against which ${./...} placeholders are checked. |
| `name` | Input name | string | 1..1 |  | maxLength=60 | Name of the operation input, as declared by the connector type (e.g. lei, siren, query, tables, since); part of the primary key, max 60. Blank names are ignored. Operation inputs declared as required must resolve to a non-empty value, otherwise the record call is SKIPPED with 'Input '<name>' is empty on this record'. Not validated against the operation's input list in the schema. |
| `expression` | Expression | string | 0..1 |  | maxLength=500; Constraint: MappingInputConstraint | Expression resolved at run time (Expressions.resolve) to produce the input value, then trimmed. Syntax: ${./Group/field} = value of a record field in XML Schema format (ISO dates, foreign key = key); text around placeholders is concatenated; ${param.name} = a run-time parameter (workflow, API, scheduler 'inputs'; for feeds only names read this way are accepted, others are reported as ignored); a literal without placeholders is a constant; ${./field\|trans1\|trans2} applies a transformation pipeline to one placeholder, and 'expr \|> pipeline' to the assembled value; placeholders can be nested inside transformation arguments. Available transformations are those of rules (trim, upper, lower, capitalize, left, right, substring, replace, regex, vatfr, digits, prefix, suffix, default, constant, nullifempty, nullif, isodate, isodatetime, date, map, join, first, count, boolean, number, round, fk, lookup, golden, mdmstate, hash, now, runid, concat); an unknown one fails the call. For feeds (no record), record placeholders resolve empty. MappingInputConstraint warns (never blocks) 'Field(s) [...] are not fields of <table>: the input will be empty.' |

### Table `MappingRule` — Mapping rules

**Purpose.** One field rule of a mapping: where a value is read (sourcePath), the transformation pipeline applied to it, where it is written (targetPath) and, inbound, under which write policy. Inbound the source is a JSON path in the connector document and the target an EBX field; outbound the source is an EBX field and the target a JSON path of the document being built. A rule without source produces a constant from its pipeline.

**Life cycle.** Created and edited by users in the Rules tab or, mostly, in the graphical designer (MappingDesignerUserService 'Save the rules' replaces the rules with positions 10, 20, ... and active = true; 'Create the reverse mapping' copies them with source/target swapped and policy IF_EMPTY), by seeding (ConnectorsInstaller), and by configuration import / connectors_mapping_upsert (ConfigTransfer deletes and recreates all rules of the mapping, positions 10, 20, ...). Deleted with their mapping by MappingCascadeTrigger. MappingPartLineageTrigger re-describes the mapping's declared lineage (column lineage) after every change. Read by ConnectorRepository.mappings() sorted by position; inactive rules are skipped.

**Identification.** Primary key /id, auto-incremented integer. Default label '${position} ${sourcePath} -> ${targetPath}'. Rules are not addressed by id in the APIs; export/import carries them as an ordered list under their mapping.

**Technical properties.** path `/root/MappingRule`; primary key `/id`; record label `${./position} ${./sourcePath} -> ${./targetPath}`; trigger `MappingPartLineageTrigger`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `id` | Id | id (int) | 1..1 |  | autoIncrement | Technical auto-incremented identifier of the rule, assigned by EBX. Not used for matching on import (rules are replaced as a whole). |
| `mapping` | Mapping | string | 1..1 |  | Foreign key → /root/Mapping | Foreign key to the Mapping owning the rule. Rules whose mapping id cannot be resolved are ignored at load. Also used by MappingRulePathConstraint to know the mapping's direction and table. |
| `position` | Position | int | 0..1 | `10` |  | Evaluation order of the rule within its mapping (ascending; schema default 10; empty is read as 0). Order matters inbound because when several rules target the same EBX field, the first rule producing a non-empty value wins (fallback chains). Designer and import renumber rules 10, 20, 30... |
| `sourcePath` | Source path | string | 0..1 |  | maxLength=500; Constraint: MappingRulePathConstraint | Where the value is read. Inbound: dotted JSON path in the document after responseRoot (for a sub-object mapping, relative to the collection element): entity.legalName.name, results[0].name (index), addressLines[*] (all elements as a list, to be reduced with join/first/count). Outbound: EBX path relative to the record, e.g. ./Group/field. Empty: no source, the rule's value comes only from its pipeline (e.g. constant(GLEIF)). MappingRulePathConstraint checks it only when it is the EBX side (outbound) and warns '<path> is not a field of <table>: this rule will fail.' |
| `targetPath` | Target path | string | 1..1 |  | maxLength=500; Constraint: MappingRulePathConstraint | Where the value is written. Inbound: EBX field path relative to the record (./LegalInformation/legalName); a foreign key receives the key of the referenced record (composite key as a\|b); values are set from XML Schema strings (dates 2026-09-05, booleans true/false, decimals with a dot). A target that is not a terminal field of the table gives the error '<path>: not a field of <table>'. Outbound: JSON path of the document being built (primaryAddress.addressLocality.name). Mandatory, max 500. MappingRulePathConstraint warns when the EBX side is not a field of the mapping's table. |
| `transformation` | Transformation | string | 0..1 |  | maxLength=500 | Optional pipeline applied to the source value, steps separated by '\|', arguments in parentheses separated by commas (quote arguments containing a comma, a '\|' or edge spaces). Registered steps (Transformations): trim, upper, lower, capitalize, left(n), right(n), substring(start,end), replace(a,b), regex(pattern,group) (null when no match), digits, vatfr (SIREN/SIRET to French VAT number), prefix(p), suffix(s), default(v), constant(v), nullifempty, nullif(v), isodate, isodatetime, date(inFormat,outFormat), map(A=B;C=D;*=default), join(sep), first, count, boolean, number, round(n), fk(/root/Table[,dataspace,dataset]) (keeps the value only if the referenced record exists), lookup(/root/Table,./field,./result[,dataspace,dataset]), golden(/root/Table[,dataspace,dataset]) (resolves a record's key to its Match and Merge golden record's key when merged, else unchanged), mdmstate(/root/Table[,dataspace,dataset]) (that record's Match and Merge state, or UNMANAGED), hash (SHA-256 hex digest of the value), now([pattern]) (current server time, default the 19-character isodatetime shape), runid (the current connector run's id), concat(path1,path2,...) (joins the document root's paths with a fixed '\|', missing/null as ""; pairs with hash for a feed's _sourceId/_rowHash). Additional steps can be registered in Java. An unknown step or a failing step records an error for that target; an empty result means the rule writes nothing. |
| `policy` | Write policy | string | 0..1 | `IF_EMPTY` | maxLength=40; Values: `IF_EMPTY` (Only when the field is empty), `ALWAYS` (Always (overwrite)), `TRUSTED` (Trusted (write when empty, or when this source is at least as trusted as the one that wrote the value; otherwise log a conflict)) | Inbound write policy for an existing record. IF_EMPTY (schema default; empty/unknown values are read as IF_EMPTY): write only when the EBX field is empty, otherwise skip. ALWAYS: overwrite whatever is there (an identical value is skipped). TRUSTED: write when the field is empty, or when the connector's trust (Connector/trust) is at least the trust of whoever wrote the current value according to FieldProvenance (a value with no provenance, i.e. entered by hand or loaded outside the connectors, counts as 100); otherwise nothing is written and a Conflict row (status OPEN) is logged for a steward. On record creation (feeds, child rows) all policies write. Whatever the policy, every value actually written is recorded in FieldProvenance. Not meaningful for outbound rules. |
| `active` | Active? | boolean | 0..1 | `true` |  | Whether the rule is applied (schema default true; empty is treated as active). Inactive rules are excluded when the mapping is loaded, so they neither write nor appear in the designer's rule list. |
| `comment` | Comment | string | 0..1 |  | maxLength=500 | Free note on the rule (max 500), editable in the designer's inspector, returned by connectors_mapping_get and exported/imported with the configuration. Not used by the synchronisation engine. |

### Table `FieldProvenance` — Field provenance

*Schema description:* Who wrote each field of a record and with which trust; read by the TRUSTED policy, kept as an audit trail.

**Purpose.** Records, for each field of each EBX record written by a connector, which connector (and mapping) last wrote it, with the trust level that source had and the value written. It is the audit trail of connector writes and the source of truth read by the TRUSTED rule policy to decide whether a new proposal may overwrite the current value.

**Life cycle.** Written only by the module engine, never by users in normal operation: ConnectorRepository.recordProvenance upserts one row per changed main-record field (paths starting with ./; child-row changes are not recorded) after every non-dry-run inbound record synchronisation (ConnectorService.syncRecord) and feed item import, whatever the rule policy, and ConflictResolver.accept records the accepted proposal as the new provenance. **Volume lot 5:** skipped entirely when the mapping's `provenance` property is `false` (default `true`) - see the `Mapping` table. Writes run with all privileges and failures are only logged. Rows are not deleted by retention; when their mapping is deleted, MappingCascadeTrigger only clears /mapping. Read by ConnectorRepository.provenanceOf (TrustOracle) during TRUSTED evaluation.

**Identification.** **Volume lot 5 (2026-09-28): primary key changed from the auto-incremented `/id` to the natural key (dataspace, dataset, tablePath, recordKey, fieldPath)** - this combination was already logically unique (the engine always looked it up before deciding create-vs-update), so the change makes the physical key match the logical one and lets `ConnectorRepository.provenanceOf`/`recordProvenance` use `AdaptationTable.lookupAdaptationByPrimaryKey` (via `computePrimaryKey(Object[])`, never a hand-joined string) instead of an XPath `Request` that used to scan the whole table (measured 2026-09-23: two thirds of a landing load's per-row time, since the table grows to rows × changed fields with no secondary index available in EBX 6.3). Default label '${fieldPath} = ${value} (${connector}, ${trust})'.
- **What this means for an existing repository (upgrade impact):** the schema change requires a Tomcat restart (a reloaded data model). `dataspace`, `dataset`, `tablePath`, `recordKey` and `fieldPath` already existed and were always populated by `recordProvenance` before this lot, so an ordinary upgrade needs no repair: the new key is the same five values that already made every row unique in practice. `ConnectorsInstaller.dropIncompleteFieldProvenanceRows` runs once at every startup as a narrow safety net - a plain forward scan (not a primary-key lookup, so it works regardless of whether the new key has "taken") that deletes only the rows where one of the five fields is blank (unaddressable under the new key, and otherwise permanent dead weight); it never wipes the whole table. `FieldProvenance` is a rebuildable audit/cache (the engine rewrites a field's row on its next write; the TRUSTED policy only degrades to "no known provenance" in the meantime), so this is safe. **This has not been verified against a live repository upgrade carrying pre-lot-5 rows** - re-check on the first real upgrade before relying on it in production.

**Technical properties.** path `/root/FieldProvenance`; primary key `/dataspace /dataset /tablePath /recordKey /fieldPath` (volume lot 5; formerly `/id`); record label `${./fieldPath} = ${./value} (${./connector}, ${./trust})`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `dataspace` | Dataspace | string | 1..1 |  | maxLength=120 | Name of the dataspace of the written record (the actual dataspace, e.g. a workflow child dataspace, not the mapping's restriction). Part of the primary key (volume lot 5: mandatory, was optional under the old auto-increment key) used by the TRUSTED lookup, so provenance recorded in a child dataspace is not seen from another dataspace. Written by the engine. |
| `dataset` | Dataset | string | 1..1 |  | maxLength=120 | Name of the dataset of the written record. Part of the primary key. Written by the engine. |
| `tablePath` | Table | string | 1..1 |  | maxLength=255 | Absolute path of the table of the written record (e.g. /root/Companies). Part of the primary key. Written by the engine. |
| `recordKey` | Record key | string | 1..1 |  | maxLength=255 | Primary key of the written record in EBX formatted form (PrimaryKey.format(), composite keys as a\|b). Part of the primary key. Written by the engine. |
| `fieldPath` | Field | string | 1..1 |  | maxLength=255 | Path of the written field relative to the record, as used by the rule target (e.g. ./LegalInformation/legalName). Part of the primary key; only paths starting with ./ are recorded. Written by the engine. |
| `connector` | Written by | string | 1..1 |  | Foreign key → /root/Connector | Connector (foreign key to Connector) that last wrote the value, i.e. the current provenance of the field. Reported as 'current value written by' when a TRUSTED conflict is logged. Written by the engine (or, on conflict acceptance, the proposing connector). |
| `mapping` | Mapping | string | 0..1 |  | Foreign key → /root/Mapping | Mapping (foreign key) through which the value was written, as the mapping id. Emptied by MappingCascadeTrigger when the mapping is deleted, the provenance row itself being kept. Informational for the TRUSTED decision (only connector and trust are read). |
| `trust` | Trust of the source | int | 1..1 |  |  | Trust level (0-100) of the writing connector at the time of the write (Connector/trust, 50 when the connector has none; for an accepted conflict, the trust the proposal had). The TRUSTED policy compares a new source's current trust with this stored value (empty is read as 50); a field without any provenance row counts as trust 100 (manual entry). |
| `value` | Value written | string | 0..1 |  | maxLength=2000 | Value written, as an XML Schema string, truncated to 2000 characters. Audit information; not re-read by the TRUSTED policy (which compares against the live record value). |
| `writtenAt` | Written at | dateTime | 1..1 |  |  | Date and time of the last write of this field by a connector (server time). Updated on every new write. Written by the engine. |
| `source` | Read from | string | 0..1 |  | maxLength=2000 | Where the value was read, when the source document says so (document feed: `invoice.pdf p.2: "snippet"`, from the item's `_sources`; for an accepted document proposal, the conflict's detection comment). Empty for every other connector. Optional column: a model without it keeps working. See docs/DOCUMENTS.md. |

### Table `Conflict` — Conflicts

*Schema description:* A less trusted source proposed a different value for a field written by a more trusted one (TRUSTED policy): the proposal is kept here for a steward to decide.

**Purpose.** A proposal refused by the TRUSTED write policy: a connector less trusted than the author of the current value proposed a different value for a field of an EBX record. The row keeps both values, their authors and trust levels so that a data steward can decide to apply the proposal or keep the current value.

**Life cycle.** Created by the engine (ConnectorRepository.logConflicts) after a non-dry-run inbound record synchronisation or feed item import whose TRUSTED rules produced conflicts; a conflict with the same dataset, table, record key, field and proposed value that is still OPEN is not logged twice. Decided by a steward through the 'Manage connector conflicts' user service (ConflictManagerUserService, on a dataspace, table, record, the Conflict table itself, or as workflow user task ebx-connectors@ConflictManager), REST POST /conflicts/{id}/accept|reject, or MCP connectors_conflict_decide — all via ConflictResolver, which sets status, comment, resolvedBy and resolvedAt under a per-conflict lock and refuses a conflict that is no longer OPEN. Accepting writes the proposed value into the record and records its provenance. Deleted by retention (on by default, ebx.connectors.log.retentionDays = 90: decided conflicts, status neither OPEN nor APPLYING, older than N days by detectedAt, hourly, 500 rows per transaction, 50,000 per pass). When the mapping is deleted, MappingCascadeTrigger clears /mapping only.

**Identification.** Primary key /id, auto-incremented; the id used by POST /conflicts/{id}/accept|reject and connectors_conflict_decide. Default label '${fieldPath}: ${currentValue} vs ${proposedValue} (${proposedConnector})'.

**Technical properties.** path `/root/Conflict`; primary key `/id`; record label `${./fieldPath}: ${./currentValue} vs ${./proposedValue} (${./proposedConnector})`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `id` | Id | id (int) | 1..1 |  | autoIncrement | Technical auto-incremented identifier of the conflict, used by the REST decision endpoints and the MCP decision tool. |
| `status` | Status | string | 0..1 | `OPEN` | maxLength=40; Values: `OPEN` (Open), `ACCEPTED` (Accepted (proposed value applied by hand)), `REJECTED` (Rejected (current value kept)) | Decision state. OPEN (schema default, set on creation): awaiting a steward; the only status listed by default and the only one that can be decided. ACCEPTED: the proposed value was written into the record by ConflictResolver.accept and the proposing connector became the field's provenance. REJECTED: the current value was kept. Listing APIs accept status=ACCEPTED\|REJECTED for history. Only non-OPEN conflicts are purged by retention. |
| `dataspace` | Dataspace | string | 0..1 |  | maxLength=120 | Dataspace of the record concerned (actual dataspace of the synchronised record). Used by ConflictResolver to locate the record when accepting, and as a filter of the conflict lists (service on a dataspace, REST/MCP filter). Written by the engine. |
| `dataset` | Dataset | string | 1..1 |  | maxLength=120 | Dataset of the record concerned. Used to locate the record, to filter lists and in the duplicate check of open conflicts. Written by the engine. |
| `tablePath` | Table | string | 1..1 |  | maxLength=255 | Absolute path of the table of the record concerned. Used to locate the record, filter lists (service on a table, table= parameter) and in the duplicate check. Written by the engine. |
| `recordKey` | Record key | string | 1..1 |  | maxLength=255 | Formatted primary key of the record concerned (composite keys as a\|b). Used to locate the record (an accept on a record that no longer exists fails 'record ... no longer exists'), to filter lists (service on a record, key= parameter) and in the duplicate check. |
| `recordLabel` | Record | string | 0..1 |  | maxLength=255 | Label of the record at detection time (default locale), truncated to 255 characters, for display in the conflict manager and API results. Informational. |
| `fieldPath` | Field | string | 1..1 |  | maxLength=255 | Path of the field in conflict relative to the record (e.g. ./Identification/Name). The accepted value is written to this path. Part of the duplicate check. |
| `currentValue` | Current value | string | 0..1 |  | maxLength=2000 | Value of the field in the record when the conflict was detected, as an XML Schema string. Shown to the steward and returned in the rejection message; not re-read on acceptance (the live value is overwritten). |
| `currentConnector` | Current value written by | string | 0..1 |  | maxLength=120 | Connector id that wrote the current value according to FieldProvenance. Empty: no provenance, i.e. the value was entered by hand or loaded outside the connectors (trust 100). Plain text, not a foreign key. |
| `currentTrust` | Trust of the current value | int | 0..1 |  |  | Trust of the current value used in the decision: the trust stored in FieldProvenance, or 100 when there is no provenance. Informational for the steward. |
| `proposedValue` | Proposed value | string | 0..1 |  | maxLength=2000 | Value the less trusted connector proposed, as an XML Schema string. Written into the record (setValueFromXsString; empty = null) when the conflict is accepted. Part of the duplicate check (a different proposal for the same field opens a new conflict). |
| `proposedConnector` | Proposed by | string | 1..1 |  | Foreign key → /root/Connector | Connector (foreign key) that made the refused proposal. On acceptance it becomes the field's provenance and is quoted in the session tracking info 'Connector conflict accepted: <connector>'. |
| `proposedTrust` | Trust of the proposal | int | 0..1 |  |  | Trust level of the proposing connector at detection time. On acceptance it is the trust recorded in FieldProvenance (falling back to the connector's current trust if empty). |
| `mapping` | Mapping | string | 0..1 |  | Foreign key → /root/Mapping | Mapping (foreign key, mapping id) whose TRUSTED rule produced the proposal. Reused as the mapping of the provenance row on acceptance. Emptied by MappingCascadeTrigger when the mapping is deleted, the conflict being kept. |
| `detectedAt` | Detected at | dateTime | 1..1 |  |  | Date and time the conflict was logged. Lists are sorted most recent first on it, and retention purges decided conflicts older than ebx.connectors.log.retentionDays by this date. |
| `comment` | Steward comment | string | 0..1 |  | maxLength=500 | Steward comment stored with the decision (max 500, truncated by code), entered as the common comment of the conflict manager screen or the comment= parameter of the REST/MCP decision. Only written when non-blank. |
| `resolvedBy` | Resolved by | string | 0..1 |  | maxLength=120 | EBX user id of the session that accepted or rejected the conflict. Written by ConflictResolver at decision time; empty while OPEN. |
| `resolvedAt` | Resolved at | dateTime | 0..1 |  |  | Date and time of the decision. Written by ConflictResolver; empty while OPEN. |

### Table `ConnectorOperationalMetrics` — Connector health and metrics

*Schema description:* Durable health, traffic, latency, checkpoint and schema-drift telemetry. Java 64-bit counters are stored as EBX-compatible decimal strings.

**Purpose.** Durable health and telemetry of one connector: execution counters (inbound, outbound, skipped, conflicts, total), a rolling window of execution durations for latency percentiles, the last success and failure, the consecutive-failure streak that determines health, and checkpoint and schema-drift timestamps. It is displayed by the ConnectorMetricsForm record form. 64-bit counters are stored as decimal strings.

**Life cycle.** Created on the first observed execution of a connector and updated after each one by the engine. ConnectorsModuleRegistration installs ConnectorOperationsRuntime.MetricsAndAlertsObserver at startup, which applies ConnectorOperationalMetrics rules and persists them through EbxConnectorOperationalMetricsPersistence under a system session named 'connectors-metrics'. Each SyncResult reported through ConnectorOperationsRuntime.observe triggers success() or failure(); checkpoint and schema-drift signals update their own fields. Observation failures are logged and never fail the connector work. Not meant to be edited by users; never deleted by code.

**Identification.** Primary key is the connector id (foreign key to Connector), one row per connector. The record label is the connector id.

**Technical properties.** path `/root/ConnectorOperationalMetrics`; primary key `/connector`; record label `${./connector}`; record form `ConnectorMetricsForm`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `connector` | Connector | string | 1..1 |  | Foreign key → /root/Connector | Connector these figures describe (foreign key to Connector.id). Mandatory; unique. |
| `readCount` | Inbound executions | metricCounter (string) | 0..1 | `0` | pattern=[0-9]+; maxLength=19 | Cumulative number of executions whose direction was INBOUND or IMPORT (reads from the external application, including import feeds). Non-negative decimal string (pattern [0-9]+, at most 19 digits), saturating at Long.MAX_VALUE. Schema default 0; an empty value is read as 0. Engine-written. |
| `writeCount` | Outbound executions | metricCounter (string) | 0..1 | `0` | pattern=[0-9]+; maxLength=19 | Cumulative number of executions with direction OUTBOUND (data sent to the external application). Same format and rules as readCount. Engine-written. |
| `skipCount` | Executions without change | metricCounter (string) | 0..1 | `0` | pattern=[0-9]+; maxLength=19 | Cumulative number of executions that ended with status SKIPPED, NO_CHANGE or NO_DATA. Same format as readCount. Engine-written. |
| `conflictCount` | Conflicts raised | metricCounter (string) | 0..1 | `0` | pattern=[0-9]+; maxLength=19 | Cumulative number of field conflicts opened by the trust policy during executions (a value proposed by a less trusted source); each execution adds its number of conflicts. Same format as readCount. Engine-written. |
| `requestCount` | Executions | metricCounter (string) | 0..1 | `0` | pattern=[0-9]+; maxLength=19 | Cumulative number of all executions of the connector, successful or not (+1 per observed SyncResult). Same format as readCount. Engine-written. |
| `retryCount` | Retries | metricCounter (string) | 0..1 | `0` | pattern=[0-9]+; maxLength=19 | Intended for calls retried after a transient error. Reserved: the current runtime never increments it, so it stays 0. Shown in the reliability section of the metrics form. |
| `throttleCount` | Throttled calls | metricCounter (string) | 0..1 | `0` | pattern=[0-9]+; maxLength=19 | Intended for calls refused by the external service for rate limiting. Reserved: the current runtime never increments it, so it stays 0. Shown in the reliability section of the metrics form. |
| `durationSamples` | Durations (ms) | text | 0..1 |  | maxLength=8192 | Durations in milliseconds of the last 256 executions, oldest first, comma-separated (MAX_DURATION_SAMPLES = 256; the oldest is dropped beyond that). Source of the p50/p95 latency shown in the metrics form (nearest-rank percentile). Max 8192 characters; engine-written. |
| `lastSuccessAt` | Last success | dateTime | 0..1 |  |  | Timestamp of the last execution whose status was not ERROR. Together with lastFailureAt it decides whether health is UNKNOWN (both empty). Engine-written. |
| `lastFailureAt` | Last failure | dateTime | 0..1 |  |  | Timestamp of the last execution with status ERROR. Engine-written. |
| `lastFailure` | Last error message | text | 0..1 |  | maxLength=2000 | Message of the last failed execution, prefixed with its operation, table path and record key when known. Secrets are masked (SensitiveDataMasker) and the text is truncated to 2000 characters to satisfy the schema limit. Kept when later executions succeed. Shown on the form while health is DEGRADED or FAILED. Engine-written. |
| `consecutiveFailures` | Consecutive failures | int | 0..1 | `0` |  | Current streak of failed executions: incremented on each ERROR and reset to 0 by any success. Health is derived from it: UNKNOWN if the connector never ran; HEALTHY at 0; DEGRADED at 1 or more; FAILED at 3 or more. Schema default 0; engine-written. |
| `checkpointAt` | Last checkpoint | dateTime | 0..1 |  |  | When an incremental synchronization of one of this connector's mappings last saved its checkpoint (signalled by SynchronizationCheckpointManager.complete through ConnectorOperationsRuntime.checkpoint). Used for checkpoint age; stays empty while checkpoints are not written (see SynchronizationCheckpoint). Engine-written. |
| `schemaDriftAt` | Last schema drift | dateTime | 0..1 |  |  | When the external service last returned a structure different from the expected one. The metrics service can record this (ConnectorOperationalMetrics.schemaDrift), but no runtime code currently signals schema drift, so it stays empty. |
| `schemaDriftCount` | Schema drifts | int | 0..1 | `0` |  | Number of schema drifts recorded; incremented with schemaDriftAt. Not fed by the current runtime, so it stays 0. Schema default 0. |
| `updatedAt` | Updated | dateTime | 0..1 |  |  | Timestamp of the last update of this row (the time of the latest observation, checkpoint or drift signal). Engine-written. |

### Table `ConnectorGovernance` — Connector governance

*Schema description:* Ownership, source precedence, approval, effective dates, publication version, dependencies and secret references.

**Purpose.** Governance metadata attached to a governed connector or mapping configuration (identified by a resource id): owner, source system and precedence, approval state, effective period, publication version, dependencies on other resources, and references (never values) to the secrets it uses. It supports approval, publication, authoritative-source selection and impact analysis.

**Life cycle.** Written by ConnectorGovernanceService (register, submit, approve, reject, publish) through EbxConnectorGovernanceStore, and exposed with permission checks by ConnectorGovernanceApplicationService (describe, impact, submit, approve, publish, audit). Every change appends a ConnectorGovernanceAudit row. In the current source these classes are used only by unit tests: no UI, REST, MCP or startup code instantiates EbxConnectorGovernanceStore, so the table is not populated by the deployed module unless filled manually. Never deleted by code.

**Identification.** Primary key is resourceId, the identifier of the governed connector or mapping configuration (free string, max 255). The record label is the resource id.

**Technical properties.** path `/root/ConnectorGovernance`; primary key `/resourceId`; record label `${./resourceId}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `resourceId` |  | string | 1..1 |  | maxLength=255 | Identifier of the governed resource (a connector or mapping configuration); other records list it in their dependencies. It is not a declared foreign key and its format is not constrained beyond being non-blank (trimmed). Max 255; mandatory. |
| `owner` |  | string | 1..1 |  | maxLength=120 | Accountable owner of the configuration (user or team name). Required and non-blank in ConnectorGovernanceRecord; recorded in the CONFIGURED audit detail. Max 120; mandatory. |
| `sourceSystem` |  | string | 1..1 |  | maxLength=160 | Name of the source system the configuration represents. Required and non-blank. Max 160; mandatory. |
| `sourcePrecedence` |  | int | 0..1 | `0` |  | Priority of this source when several effective configurations compete. ConnectorGovernanceService.authoritative picks the highest value, with the resource id as tie-break. Must be 0 or more. Schema default 0; code reads empty as 0. |
| `approval` |  | string | 1..1 | `DRAFT` | Values: `DRAFT`, `PENDING`, `APPROVED`, `REJECTED` | Approval state. DRAFT = registered, not submitted (also the fallback when empty); PENDING = submitted for approval (submit); APPROVED = approved, which is only allowed from PENDING; REJECTED = rejected (reject). Only an APPROVED record within its effective period counts as effective, can be published, or can be chosen as authoritative. Schema default DRAFT. |
| `effectiveFrom` |  | dateTime | 0..1 |  |  | Start of the period in which an approved configuration is effective; empty means no lower bound. The effective check is inclusive. |
| `effectiveUntil` |  | dateTime | 0..1 |  |  | End of the effective period; empty means no upper bound. It must not be earlier than effectiveFrom (validated in ConnectorGovernanceRecord). The effective check is inclusive. |
| `publicationVersion` |  | int | 0..1 | `0` |  | Number of times the configuration was published: publish() increments it by 1 and requires the record to be approved and effective at the publication time. Must be 0 or more. Schema default 0; code reads empty as 0. |
| `dependencies` |  | text | 0..1 |  | maxLength=16000 | Resource ids this configuration depends on, stored as a comma-separated list of URL-safe Base64 (no padding) encoded values. Used by impact(), which returns every resource that depends on a given one, directly or transitively. Max 16000. |
| `secretReferences` |  | text | 0..1 |  | maxLength=16000 | References to the secrets the configuration uses, never the secret values. Each entry must match vault:..., env:..., file:... or azurekv:... (case-insensitive), otherwise it is rejected. Stored in the same comma-separated Base64 encoding as dependencies. Only the number of references and the provider names are ever exposed (describe, audit detail). Max 16000. |

### Table `ConnectorGovernanceAudit` — Connector governance audit

*Schema description:* Append-only, secret-safe governance transitions.

**Purpose.** Append-only audit trail of governance transitions on governed connector or mapping configurations: who did what, when, with a secret-safe detail. It is the history behind ConnectorGovernance.

**Life cycle.** Rows are appended by ConnectorGovernanceService (via EbxConnectorGovernanceStore.appendAudit) on every register, submit, approve, reject and publish; they are never updated or deleted by code. They are read, filtered by resource and sorted by occurredAt, by ConnectorGovernanceApplicationService.audit, which requires the AUDIT permission. As for ConnectorGovernance, the governance services are not wired to any runtime entry point in the current source, so the table is not populated by the deployed module.

**Identification.** Primary key is an auto-incremented integer id. The record label is '<resourceId> <action>'.

**Technical properties.** path `/root/ConnectorGovernanceAudit`; primary key `/id`; record label `${./resourceId} ${./action}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `id` |  | id (int) | 1..1 |  | autoIncrement | Technical identifier, auto-incremented by EBX when the audit row is created. |
| `resourceId` |  | string | 1..1 |  | maxLength=255 | Identifier of the governed resource the event concerns (the ConnectorGovernance.resourceId value; not a declared foreign key). Max 255; mandatory. |
| `action` |  | string | 1..1 |  | maxLength=80 | Transition performed. Values written by code: CONFIGURED (register), SUBMITTED (moved to PENDING), APPROVED, REJECTED, PUBLISHED (publication version incremented). Free text in the schema (max 80); mandatory. |
| `actor` |  | string | 1..1 |  | maxLength=120 | Identity of the user or process that performed the action. Application adapters must take it from the authenticated EBX session, never from request data. Max 120; mandatory. |
| `detail` |  | text | 0..1 |  | maxLength=4000 | Secret-safe detail of the event, e.g. 'owner=..., secretReferences=<count>, secretProviders=[vault, env]' for CONFIGURED, 'approval=PENDING' for transitions, 'version=<n>' for PUBLISHED. Max 4000; optional. |
| `occurredAt` |  | dateTime | 1..1 |  |  | Timestamp of the event (the time the service recorded it). Used to sort the audit history. Mandatory. |

### Table `ExecutionRun` — Execution runs

*Schema description:* Durable restart, item-attempt, dead-letter and lease state for bounded connector executions.

**Purpose.** Durable state of a bounded, restartable connector execution identified by a run id: the index of the next work item to process, per-item attempt counters, the items already completed, the items sent to dead letter, and an exclusive lease so that only one worker processes the run at a time. It is the EBX persistence of the RecoverableExecution / DurableExecutionStateStore framework.

**Life cycle.** Designed to be written only by code: EbxRecoverableExecutionStore (DurableExecutionStateStore.Persistence) creates the row when a lease is first acquired (nextIndex 0), updates it on every checkpoint save and clears leaseOwner/leaseExpiresAt when the lease is released; OperationalExecutionService acquires a 5-minute lease around RecoverableExecution.run. In this build no production code path instantiates EbxRecoverableExecutionStore or OperationalExecutionService (only unit tests use the in-memory persistence), so the table is normally empty. Nothing in the module deletes rows (no retention, no cascade).

**Identification.** Primary key /runId (string, max 160), also the default label. The framework requires a non-blank run id; work item ids must match [A-Za-z0-9._:-]{1,160}.

**Technical properties.** path `/root/ExecutionRun`; primary key `/runId`; record label `${./runId}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `runId` |  | string | 1..1 |  | maxLength=160 | Identifier of the restartable execution; primary key. A checkpoint loaded for a run must belong to that run ('Checkpoint belongs to another run'). Written by EbxRecoverableExecutionStore. |
| `nextIndex` |  | int | 0..1 |  |  | Zero-based position in the ordered work source from which a resumed run continues: items before it are skipped on restart. Set to 0 when the row is created by a lease, then advanced as items reach a terminal state; empty is read as 0. |
| `attempts` |  | text | 0..1 |  | maxLength=16000 | Per-item attempt counters serialised as comma-separated 'itemId=count' pairs (e.g. rec-1=2,rec-7=1), used to stop retrying an item after the policy's maximum attempts (hard cap 10). Written by the store; max 16000 characters. |
| `completed` |  | text | 0..1 |  | maxLength=16000 | Comma-separated ids of the work items that reached a successful terminal state; a resumed run skips them (idempotent replay). Written by the store; max 16000 characters. |
| `deadLetters` |  | text | 0..1 |  | maxLength=16000 | Comma-separated ids of the work items moved to dead letter after exhausting their attempts or failing with a non-retryable error. Written by the store; max 16000 characters. |
| `leaseOwner` |  | string | 0..1 |  | maxLength=160 | Identifier of the worker (actor) currently holding the execution lease. Another owner cannot acquire the lease while leaseExpiresAt is in the future ('Execution is already leased'); the same owner may renew it. Cleared when the owner releases the lease. |
| `leaseExpiresAt` |  | dateTime | 0..1 |  |  | Expiry date-time of the current lease (acquisition time plus the requested duration, at most 1 hour; OperationalExecutionService uses 5 minutes). After it, another worker may take over the run. Cleared on release. |
| `updatedAt` |  | dateTime | 0..1 |  |  | Date-time of the last write of the row (lease acquisition or state save). Written by the store; informational. |
| `watermarkBefore` | Watermark before | text | 0..1 |  | maxLength=2000 | Volume lot V2-D (§2.14.7): the checkpoint an incremental feed run started from; empty on a first run and on any run that is not an incremental feed. |
| `watermarkAfter` | Watermark after | text | 0..1 |  | maxLength=2000 | Volume lot V2-D: the checkpoint the run ended on; different from watermarkBefore only when the run ended DONE and moved it. |
| `skippedByWatermark` | Skipped by watermark | int | 0..1 |  |  | Volume lot V2-D: rows the source delivered that were at or below the checkpoint and were dropped before any lookup or transformation (not part of the run's count). |

### Table `OutboundState` — Outbound deliveries

*Schema description:* What was sent to the external application by each outbound mapping: fingerprint of the last document (scope "changed" resends only what differs), failures to replay, deletions.

**Purpose.** Delivery state of one EBX record for one outbound mapping: whether its last delivery was sent, failed or was a deletion, the SHA-256 fingerprint and content of the last document sent successfully, the number of failed attempts since the last success, and the run involved. It drives the outbound scopes 'unsent', 'changed' and 'failed', the retry policy and the propagation of deletions.

**Life cycle.** Written only by the engine (ConnectorRepository.markOutbound, all privileges, failures only logged) after each non-dry-run outbound call on a root (non sub-object) mapping: status FAILED on an ERROR (attempts + 1, lastError), SENT on success with a built document (attempts reset to 0, hash and lastDocument updated, lastSentAt set); SKIPPED calls and dry runs leave it untouched. ConnectorService.deleteRemote writes DELETED (success) or FAILED for deleted records. Read by OutboundSelector, replay, REST GET /outbound and MCP connectors_outbound_states. Deleted with its mapping by MappingCascadeTrigger; no retention.

**Identification.** Composite primary key (/mapping, /recordKey): one row per outbound mapping and record. Default label '${recordLabel} (${status})'.

**Technical properties.** path `/root/OutboundState`; primary key `/mapping /recordKey`; record label `${./recordLabel} (${./status})`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `mapping` | Mapping | string | 1..1 |  | Foreign key → /root/Mapping | Foreign key to the outbound Mapping (mapping id); part of the primary key. Used to list the states of a mapping (?mapping= on GET /outbound). |
| `recordKey` | Record key | string | 1..1 |  | maxLength=255 | Formatted primary key of the EBX record (composite keys as a\|b), truncated to 255; part of the primary key. Used to look the record up again (scope 'failed', deletion detection, replay) — a key absent from the table marks the record as deleted. |
| `recordLabel` | Record | string | 0..1 |  | maxLength=255 | Label of the record at the last delivery (truncated to 255), kept so that deletions and failures of records that no longer exist can still be displayed and logged. Only overwritten when a label is available. |
| `dataspace` | Dataspace | string | 0..1 |  | maxLength=120 | Dataspace restriction copied from the mapping at each write (empty when the mapping applies to any dataspace). Informational; not used to select states. |
| `dataset` | Dataset | string | 0..1 |  | maxLength=120 | Dataset copied from the mapping at each write. Informational. |
| `tablePath` | Table | string | 0..1 |  | maxLength=255 | Table path copied from the mapping at each write. Informational. |
| `status` | Status | string | 0..1 |  | maxLength=40; Values: `SENT` (Sent), `FAILED` (Failed (to replay)), `DELETED` (Deletion sent) | Last delivery outcome. SENT: the document was sent successfully (fingerprint and document stored). FAILED: the last send or deletion failed; retried by scopes 'changed', 'unsent' and 'failed' while attempts < Mapping/maxAttempts and Mapping/retryDelayMinutes have elapsed, otherwise waiting until a manual replay. DELETED: the record disappeared and the deleteOperation call succeeded. No schema default (always set by the engine). Scope 'changed' also treats SENT rows whose record is gone as deletions when the mapping has a deleteOperation. |
| `lastSentAt` | Last successful send | dateTime | 0..1 |  |  | Date-time of the last successful delivery (SENT or DELETED). Empty means the record was never sent successfully, which is what scope 'unsent' selects and what makes scope 'changed' resend it regardless of fingerprint. |
| `lastAttemptAt` | Last attempt | dateTime | 0..1 |  |  | Date-time of the last delivery attempt, successful or not. The retry delay (Mapping/retryDelayMinutes) is measured from it. |
| `attempts` | Failed attempts since the last success | int | 0..1 | `0` |  | Number of failed attempts since the last success: incremented on each FAILED write, reset to 0 on SENT or DELETED. Schema default 0. When it reaches Mapping/maxAttempts the record is no longer retried automatically. |
| `hash` | Fingerprint of the sent document | string | 0..1 |  | maxLength=64 | SHA-256 hex fingerprint (64 characters) of the last document sent successfully; updated only on successful sends. Scope 'changed' rebuilds the outbound document for each record and resends it only if its fingerprint differs from this value (so a change made by an inbound enrichment is resent too). |
| `lastDocument` | Last document sent | text | 0..1 |  | maxLength=8000 | JSON text of the last document sent successfully (truncated to 8000 characters). On a deletion its scalar fields are passed to the deleteOperation call as inputs, alongside the key. |
| `lastError` | Last error | text | 0..1 |  | maxLength=2000 | Error message of the last failed attempt (truncated to 2000); cleared on success. |
| `lastRunId` | Last run | string | 0..1 |  | maxLength=40 | Run id (format RyyyyMMdd-HHmmss-nnnn) of the execution that last wrote this state, linking it to the SyncLog rows of that run and to POST /replay/{runId}. |

### Table `Favorite` — Favorites

*Schema description:* Per-user shortcuts pinned with the star of the connector explorer. Everybody sees only their own rows; nobody edits them by hand.

**Purpose.** The connectors a user pinned in the connector explorer (`My favorites` facet). Personal convenience data: no engine behaviour depends on it.

**Life cycle.** Written and deleted only by `Favorites.toggle` (the star of a card), with all privileges; at most 200 rows per user. Never exported by the configuration transfer. A deleted connector leaves a dangling row, which `Favorites.pruneDangling` can remove; it is otherwise harmless (no card carries it).

**Identification.** Primary key is user + kind + target key. The record label is '<userLogin> - <targetType> <targetKey>'.

**Technical properties.** path `/root/Favorite`; primary key `/userLogin /targetType /targetKey`; record label `${./userLogin} - ${./targetType} ${./targetKey}`; access rule `FavoriteAccessRule` (own rows only, read-only).

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `userLogin` | User | string | 1..1 |  | maxLength=255 | EBX login of the owner of the shortcut (`Session.getUserReference().getUserId()`). |
| `targetType` | Kind | string | 1..1 |  | maxLength=40; Values: `CONNECTOR` | What is pinned. One table serves every kind; only connectors today. |
| `targetKey` | Target | string | 1..1 |  | maxLength=255 | Primary key of the pinned record (`Connector.id`). Not a foreign key, so deleting a connector is never blocked by somebody's shortcut. |
| `createdAt` | Pinned at | dateTime | 0..1 |  |  | When the star was clicked. |

### Table `SeedMarker` — Seed markers

*Schema description:* What the installer created once: a seeded connector, a group of demonstration mappings, a data migration. A row here keeps the installer from creating that item again after you deleted it. Technical.

**Purpose.** Remembers what `ConnectorsInstaller` has already created so that a connector or a mapping the customer deleted is not created again at the next start (audit C-26). No engine behaviour reads it.

**Life cycle.** Written by the installer, with all privileges, in the procedure that creates the item (and, at the first start that sees the table, in one procedure for everything already there). Never exported by the configuration transfer. Deleting a row lets the installer create that item once more at the next start.

**Identification.** Primary key `item`: `connector:<id>`, `mappings:<group>`, `migration:<name>` or `baseline`. The record label is the item.

**Technical properties.** path `/root/SeedMarker`; primary key `/item`; record label `${./item}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `item` | Item | string | 1..1 |  | maxLength=255 | What was created: `connector:<id>`, `mappings:<group>` (`DnB`, `Melissa`), `migration:<name>` (`fieldProvenanceKey`) or `baseline` (the first start of the version that records markers). |
| `seededAt` | Seeded at | dateTime | 0..1 |  |  | When the installer recorded it. |

### Group `Settings` — Settings

*Schema description:* Settings of the Connectors module on this instance. Read by everyone who holds a Connectors right, changed by an EBX administrator only; a change applies at the next call, no restart.

**Purpose.** The module's own settings, as dataset-level fields (not a table: one value per instance). Wave 12.

**Access.** `ConnectorsTableAccessRule.SETTINGS` on the group and its fields (`setAccessRuleOnNodeAndAllDescendants`):
hidden without a Connectors right, read only with one, read-write for an EBX administrator and the system user.

**Technical properties.** path `/root/Settings`; reached in the dataset's navigation (Settings); not part of the
configuration export (`ConfigTransfer`).

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `defaultLlmConnector` | Default language-model connector | string | 0..1 |  | foreign key `/root/Connector`, filter `./type = 'llm'` (error "Choose a connection of type llm") | The `llm` connector the in-JVM contract (`llm.ask`, `llm.chat`) uses when the calling module names none; read at each call by `ContractService`, before `-Debx.connectors.llm.default` (§4.5.4). Empty: the property, else the only usable `llm` connector. |

### Table `SyncLog` — Synchronisation log

**Purpose.** The synchronisation log: one row per connector call on a record, feed item, outbound delivery, remote deletion or feed-level outcome, with its status, message, changes, duration, user, origin and run id. It is the operational history of the module and the basis for replaying the failures of a run.

**Life cycle.** Appended only by the engine through ConnectorRepository.log (all privileges; errors swallowed so logging never breaks a sync) for every non-dry-run call from the user service (ConnectorSyncUserService), REST API, workflow bean, scheduled task, webhook, MCP tools and replays. Dry runs (simulation, designer test) and synchronisations run inside a table trigger (ConnectorService.syncRecordInProcedure) write no row. Rows are never updated. Retention: with the JVM property ebx.connectors.log.retentionDays = N > 0, rows older than N days (by timestamp) are deleted, at most once an hour and 2000 rows per pass, triggered by the next log write. Not affected by mapping deletion. Read by replay (ConnectorService.replay), GET /runs/{runId}, connectors_run_get, connectors_log_search and the connector operations dashboard.

**Identification.** Primary key /id, auto-incremented (the MCP log search sorts by it, most recent first). Default label '${timestamp} ${connector}/${operation} ${recordLabel} ${status}'. Rows of one execution are grouped by /runId.

**Technical properties.** path `/root/SyncLog`; primary key `/id`; record label `${./timestamp} ${./connector}/${./operation} ${./recordLabel} ${./status}`.

| Attribute | Label | Type | Card. | Default | Constraints | Definition |
|---|---|---|---|---|---|---|
| `id` | Id | id (int) | 1..1 |  | autoIncrement | Technical auto-incremented identifier of the log row; increasing order approximates chronological order. |
| `timestamp` | Timestamp | dateTime | 0..1 |  |  | Date-time the row was written (end of the call). Used by retention (rows older than ebx.connectors.log.retentionDays are purged). |
| `connector` | Connector | string | 0..1 |  | maxLength=40 | Id of the connector called (plain text, not a foreign key, so the log survives connector deletion). Empty on replay placeholder rows for a mapping that no longer exists. |
| `operation` | Operation | string | 0..1 |  | maxLength=60 | Name of the operation called; for a remote deletion, the mapping's deleteOperation. |
| `direction` | Direction | string | 0..1 |  | maxLength=10 | Kind of call: INBOUND (record enrichment), OUTBOUND (send or remote deletion), IMPORT (feed items and feed-level header rows). Empty on replay placeholder rows. Plain text, not an enumeration. |
| `dataspace` | Dataspace | string | 0..1 |  | maxLength=120 | Dataspace where the call ran: the record's actual dataspace for record calls, the resolved dataset's dataspace for feeds, the mapping's restriction for outbound scope notes and deletions. Replay uses it to find the dataset of a failed record again. |
| `tablePath` | Table | string | 0..1 |  | maxLength=255 | Absolute path of the table concerned. |
| `recordKey` | Record key | string | 0..1 |  | maxLength=255 | Formatted primary key of the record (composite a\|b), truncated to 255; for a feed item, the resolved key predicate. Replay re-runs the mapping on the record with this key (skipping it if the record no longer exists, or deleting it remotely when the outbound mapping has a deleteOperation). |
| `recordLabel` | Record | string | 0..1 |  | maxLength=255 | Label of the record (or, for feeds, the resolved predicate or the feed label on header rows), truncated to 255. Informational. |
| `status` | Status | string | 0..1 |  | maxLength=20 | Outcome of the call (SyncResult.Status): OK = at least one change written (or document sent); NO_CHANGE = call succeeded, nothing to write; NO_DATA = the service returned nothing for these inputs (or a feed had no item); SKIPPED = a required input was empty, no record matched with creation off, no applicable mapping, or the record/mapping of a replayed call disappeared; ERROR = the service or the mapping failed. DRY_RUN exists in code but dry runs are not logged. Only ERROR rows are replayed by POST /replay/{runId}. |
| `message` | Message | text | 0..1 |  | maxLength=2000 | Human-readable result or cause of failure (e.g. 'Input 'lei' is empty on this record', '2 change(s), 0 skipped', 'deleted remotely (delete)'), passed through Redaction to mask secrets and truncated to 2000 characters. |
| `changes` | Changes | text | 0..1 |  | maxLength=4000 | Text list of the changes made or of the values sent (path: old -> new), redacted and truncated to 4000 characters. Left empty when the JVM property ebx.connectors.log.storeChanges=false, to keep values and documents (personal data) out of the log. |
| `durationMs` | Duration (ms) | int | 0..1 |  |  | Duration of the call in milliseconds, summed by the operations dashboard. |
| `user` | User | string | 0..1 |  | maxLength=120 | EBX user id of the session that ran the call; empty when the session has no user. |
| `origin` | Origin | string | 0..1 |  | maxLength=60 | What started the call, as passed by the caller: 'userService' (Synchronise user service), 'REST <user>' (REST API), 'workflow <user>' (ConnectorScriptTaskBean), 'scheduler' (ConnectorScheduledTask), 'MCP <user>' (MCP tools), 'webhook <connectorId>' (POST /webhook), 'replay <runId> by <user>' (REST replay). Free text, not an enumeration. |
| `runId` | Run | string | 0..1 |  | maxLength=40 | Identifier of the execution the call belongs to, format RyyyyMMdd-HHmmss-nnnn (ConnectorService.beginRun: one REST call, workflow step, scheduled run or user service run; a single call outside a run gets its own id). POST /ebx-connectors/rest/v1/replay/{runId} re-runs the ERROR rows of the run, GET /runs/{runId} lists them. Only written when the model has the field. |
| `mapping` | Mapping | string | 0..1 |  | maxLength=20 | Id of the mapping used, as text (not a foreign key, max 20), written with runId. Replay needs it together with recordKey: an ERROR row without mapping or key is not replayable, and a mapping id that no longer exists gives a SKIPPED 'mapping <id> no longer exists' result. |

## 6. Known gaps and documentation discrepancies

Points where the handover guides and the code disagree, or where a feature is incomplete or has not been run end to end.

### 6.1 Connectors engine
#### 6.1.1 Documentation versus code

1. **Record-level `${param.name}`** — GUIDE §5 describes `${param.max}` as "a parameter passed at run time (workflow, API)". In the engine, record synchronisations resolve inputs with params = null, so `${param.*}` always yields ""; only feed imports (`importFrom`) receive parameters. (`ConnectorService.prepare`)
2. **SKIPPED "no applicable mapping"** — GUIDE §13 says SKIPPED covers "no applicable mapping". The engine returns `ERROR` (`No <DIR> mapping for ...`) for a missing mapping; SKIPPED is only for blank required inputs, unmatched feed items with creation off, and replays of missing mappings/records. (`ConnectorService.syncRecord`)
3. **Provenance "every write by a connector ... whatever the rule policy"** — GUIDE §4.4. Code records provenance only for main-record fields (paths starting `./`), not for sub-object rows, not for trigger-driven syncs (`syncRecordInProcedure`), and not when the `FieldProvenance` table is absent. (`ConnectorRepository.recordProvenance`)
4. **Conflicts in table triggers** — GUIDE/README present TRUSTED conflicts as always logged; `syncRecordInProcedure` never calls `logConflicts`.
5. **OAuth token renewal "a minute before they expire"** — GUIDE §3.1.1 and the class comment. Code uses skew = min(60 s, max(1 s, expires_in/10)), i.e. less than a minute for tokens shorter than 10 minutes. (`OAuth2.request`)
6. **HttpJsonClient comment "Retries once on HTTP 429 after a short pause"** — actual policy retries once on 408, 425, 429 and 500–504, only for idempotent methods (never POST/PATCH), honouring `Retry-After`, 2 s base delay. (`HttpRetryPolicy`)
7. **Secret field length** — `CredentialGuide.SECRET_MAX_LENGTH = 255` and GUIDE §3.1 / README ("255 characters of the field") vs `Connectors.xsd`: `password` and `apiKey` have `maxLength = 4000`.
8. **Number of seeded connectors** — GUIDE §2 says 40 seeded connectors; README says "about 60"; the installer helpers create at least 61 distinct ids.
9. **Installer class comment** — says it seeds "GLEIF, Sirene and Google Geocoding connectors plus the mappings for /root/Companies"; the installer now runs a dozen seed groups.
10. **`logOfRun` "most recent first"** — the Javadoc says so, but no sort criteria are set. (`ConnectorRepository.logOfRun`)
11. **GUIDE §7 `regex` "group default 1"** — default is 1 only when the pattern has capturing groups, else 0. **`boolean`** — GUIDE lists true values; code also accepts `y` and `o` and returns `false` (not null) for any other non-blank text.
12. **GUIDE §2 table list** — lists 7 tables; the engine also uses `FieldProvenance`, `Conflict`, `OutboundState`, `SynchronizationCheckpoint`, `ConnectorOperationalMetrics`, `ExecutionRun`, `SchemaRefreshState`, `ConnectorCategory`, `ConnectorTemplate`, release tables.
13. **GUIDE §4.2 "OK (at least one change), NO_CHANGE, or ERROR"** — omits that rule-level errors do not make the status ERROR (§3.1).
14. **Incremental loads** — the model and GUIDE-level fields (`loadMode`, `checkpoint*`) suggest incremental synchronisation; the checkpoint manager is not wired into any execution path (§20).
15. **Governance and recoverable executions** — `ConnectorGovernance`, `ConnectorGovernanceAudit`, `ExecutionRun` tables and their Java services exist without any production caller (§20).

#### 6.1.2 Behaviours worth documenting or fixing (observed in code)

1. **Run id leak on pooled threads** — `currentRunId()` without `beginRun()` creates an id and stores it in the thread-local without ever removing it; later single calls on the same worker thread reuse that run id, merging unrelated calls into one "run" for replay. (`ConnectorService.newRunId`)
2. **Feeds are invisible to runs and replay** — feed item and header results never get `runId` or `mappingId`, so `logOfRun`/`replay` cannot see them. (`ConnectorService.importItem`, `importFrom`)
3. **`importDocument` does not log a missing dataset** (unlike `importFrom`).
4. **Empty key predicate creates rows** — `importDocument` does not require `isFeed()`: with a blank `keyPredicate` every item creates a record (when `createIfMissing` is true).
5. **`deleteMissing` with an absent collection deletes all linked child rows.** (`MappingEngine.applyChild`)
6. **Mappings cannot clear values** — empty or null computed values are never written, whatever the policy.
7. **Unbounded deletions** — CHANGED and DATASPACE_DELTA deletions are not limited by `max`; FAILED and DATASPACE_DELTA ignore the request/predicate passed by the caller.
8. **Exhausted retries reported as "waiting for the retry delay"** — records whose attempts reached `maxAttempts` are counted in `waitingRetry` with the same message. (`OutboundSelector`)
9. **Re-created records after a deletion** — a `DELETED` state keeps `lastSentAt` and the old hash, so under CHANGED a re-created record with identical content is counted as unchanged and never re-sent; under UNSENT it is also "unchanged".
10. **`lastDocument` truncation** — documents longer than 8000 characters are stored truncated; their JSON cannot be parsed back for deletions (fields silently missing). Record keys longer than 255 characters are stored truncated while lookups use the full key, so the state row is never found again (insert conflict, error only in the kernel log).
11. **Silent SyncLog loss** — values exceeding the model's limits for `connector` (40), `operation` (60), `origin` (60), `runId` (40) are not truncated; the insert fails inside a swallowed procedure result.
12. **Provenance keyed by dataspace** — values written in a workflow child dataspace have no provenance once read in the parent after merge, so they count as manual (trust 100) and block TRUSTED updates.
13. **Conflict dedupe ignores dataspace** — an OPEN conflict in one dataspace suppresses the same proposal in another.
14. **`exclude.connectors` is case-sensitive in effect** — configured ids are lower-cased but compared with the connector id as stored, so ids containing upper-case letters are never excluded. (`OpenLineageEmitter.Settings`)
15. **Non-atomic conflict acceptance** — the record write and the conflict closure are separate transactions; the conflict lock is JVM-local (the code comment points to a multi-node gap, the security review of September 2026 finding 11).
16. **Insecure defaults acknowledged in code** — clear-text secrets allowed and egress unrestricted by default, each with a one-time kernel warning (the security review of September 2026 finding 07).
17. **Pipeline splitter ignores quotes** — `Transformations.splitPipeline` splits on `|` inside quotes (outside parentheses), while `Expressions.firstPipe` honours quotes; `|>` detection uses the last occurrence in the whole template.
18. **`lookup` always quotes the compared value** — numeric or boolean fields are compared with a string literal.
19. **Child key predicates on date fields** use `=`, which EBX refuses; the explanatory hint exists only for feed key predicates.
20. **Global serialisation of connector lookups** — `ConnectorRegistry.connector` is `synchronized` and re-reads the configuration (including vault resolution on cache expiry) inside the lock, serialising connector acquisition across all concurrent synchronisations.
21. **Workflow aggregate status** — the `sendScope` summary row (NO_CHANGE) counts as `ok`, and runs made only of SKIPPED results report `NO_DATA`. (`ebx/SyncTargets`, `ConnectorScriptTaskBean`)
22. **`FieldProvenance` primary-key migration not verified live (volume lot 5, 2026-09-28)** — `ConnectorsInstaller.dropIncompleteFieldProvenanceRows` (a narrow safety net: deletes only rows with a blank primary-key field, never the whole table) was reasoned through and unit-adjacent tested, but never exercised against a real repository carrying rows written under the pre-lot-5 `/id`-keyed schema. Re-check the very first real upgrade before relying on it.
23. **The import-feed lookups of §14.3 (`FeedKeyLookup`) engage only for the predicate shapes listed there.** A boolean or foreign-key primary-key field, a `keyPredicate` with any other clause (a function, a literal, `or`, a path below the record), or a business-key field that is not a plain stored `xs:string` always uses the XPath `Request` (`MappingEngine.first`).
24. No `TODO`/`FIXME` markers exist in `core`.

### 6.2 Connector types and extension SPI
#### 6.2.1 GUIDE / README versus code

1. **Optional libraries (GUIDE §17, §18).** GUIDE says types whose library is absent (Kafka, SFTP, AMQP/JMS providers, JDBC drivers, Azure Service Bus SDK) "appear only when that library is present" / are "simply not registered". Only `kafka` and `sftp` are registered quietly; `rabbitmq`, `jms`, `activemq`, `azure-service-bus` and all JDBC types are always listed and fail at execution.
2. **Where jars go (GUIDE §18 vs runtime-libs/README vs root README).** GUIDE §18 tells to put jars in Tomcat's shared `lib` or `WEB-INF/lib`; `runtime-libs/README.md` says the WAR packages them and a second copy in the shared Tomcat library directory must not exist; the root README says `kafka-clients`, `jsch` and JDBC drivers come from the EBX installation and are not part of the repository — yet they are in `runtime-libs/`. GUIDE §18 versions (kafka-clients 3.6.1, jsch 0.2.16) are not stated anywhere in the packaged jars.
3. **"JDBC mode" (GUIDE §18).** `snowflake`, `databricks` and `bigquery` have no JDBC mode: they are pure REST connectors; no driver is used.
4. **§17 catalogue incomplete versus code.** Missing operations: `jdbc`/dedicated DB types `catalog`, `profile`, `sample`; `snowflake`/`databricks` `catalog`, `profile`, `sample`, `usage`, `lineage`; `ebx` `catalog`, `profile`, `sample`, `lineage` (documented only in §3.8); `kafka` `catalog`, `profile`, `sample` (only in §3.9). `dynamics-business-central` is listed with `upsert (W)` but the class declares no `upsert`. Labels differ: code `Microsoft SharePoint` vs GUIDE "Microsoft SharePoint Online (Microsoft Graph)"; code `Oracle NetSuite` vs "Oracle NetSuite (SuiteTalk REST/SuiteQL)"; `llm` label. The `rest` row lists no operations (they are instance-defined).
5. **`rest` operations (GUIDE §3.2).** GUIDE lists `GET | POST | PUT | DELETE` and "GET/DELETE = read, POST/PUT = write"; the code also accepts `PATCH` (write), an explicit `op.<name>.kind`, SEARCH inference from `op.<name>.fields`, headers, cookies, multipart, pagination and `responseRoot` — none documented in §3.2. Conversely the descriptor makes `op.<name>.method` **required** although the code defaults it to `GET`: an operation declared with only `op.x.path` fails validation ("op.x.method: A value is required"), and `op.x.kind` in lower case fails the enumeration check although the code upper-cases it.
6. **Secret field length (GUIDE §3.1).** GUIDE says the credential guide warns above "the 255 characters of the field"; the XSD declares `password` and `apiKey` with `maxLength 4000`.
7. **OAuth 2.0 "for any REST-based type" (GUIDE §3.1.1).** Many REST types replace `headers()` and ignore `authType OAUTH2`: creditsafe, dnb, dynamics365 (own token), jde-ais, opensanctions, databricks, snowflake, bigquery; salesforce and workday overwrite `Authorization` with their own tokens.
8. **Webhook endpoint.** `WebhookConnector` javadoc cites `/ebx-ps-fasttrack/connectors/webhook/{mapping}`; GUIDE §12.2 uses `/ebx-connectors/rest/v1/webhook/{feed}` (javadoc is stale).
9. **Extension sample.** Sample README and `build.sh` expect `ebx-connectors-extension-sample/lib/ebx-connectors-spi.jar`; the `lib/` folder is empty in the repository — the jar is in `ebx-connectors/dist/` (as GUIDE §3.3 says). `build.sh` without an argument therefore exits 2. The SPI jar is dated 2026.09.11 and may lag later changes to the helpers it embeds.
10. **Inventory JSON.** `docs/connector-metadata-inventory.json` (schemaVersion 1, 40 entries, "audit inventory, not a runtime descriptor contract") is read by no Java class or build script; it lacks 18 registered types (`powerbi`, `collibra`, `openlineage`, `postgresql`, `h2`, `mysql`, `oracle-database`, `sql-server`, `azure-sql`, `rabbitmq`, `jms`, `activemq`, `azure-service-bus`, `sharepoint`, `netsuite`, `dynamics-business-central`, `sap-successfactors`, `sap-ariba`) and is stale on others: `llm` lists only anthropic/openai/azure; `ebx`, `kafka`, `jdbc`, `snowflake`, `databricks` lack their catalog operations; `dnb` lacks monitoring operations; `soap` says WSDL discovery is "not evident" (it is implemented); `kafka` auth omits SCRAM; `jdbc` lacks `jndi`/`fetch.size`/`batch.size`; categories follow the old `defaultCategory` (e.g. `jdbc` DATA_PLATFORMS vs installer fallback DATABASE).
11. **Categories.** `jdbc` descriptor says `DATA_PLATFORMS`, the installer's legacy fallback says `DATABASE`; `openlineage` descriptor `DATA_PLATFORMS`, not listed in the legacy fallback (would be GENERIC_INTEGRATION).
12. **XSD documentation.** `Connector/active` description reads "An inactive mapping is never selected…" (copied from the Mapping table).

#### 6.2.2 Code-level inconsistencies and limitations

13. **Validator requires `baseUrl` where the code defaults it**: `bigquery`, `gcs`, `s3` (derived URL), `powerbi` (DEFAULT_BASE_URL), `netsuite` (from `account`). A blank `baseUrl` makes the connector unusable ("baseUrl: A base URL is required").
14. **JNDI without `baseUrl`**: only `postgresql` removes the generic `baseUrl` issues when `jndi` is set; `jdbc`, `h2`, `mysql`, `oracle-database`, `sql-server`, `azure-sql` return the default result early and still fail with "A base URL is required" when `baseUrl` is blank.
15. **Validation before `configure`**: type defaults written in `configure` (drivers, `jdbc.encrypt`, `auth.mode`, Ariba paths, JMS factory…) are not seen by validation; harmless for the current rules, but the declared "required" properties with code defaults (e.g. Business Central `environment`, whose descriptor default `Production` is ignored by its own validator) must be entered explicitly.
16. **Declared types versus parsing**: `loqate` `verification.min` declared INTEGER but the code compares with `V` (a numeric value disables filtering; `V` fails the INTEGER check, so the default filter can only be the implicit one). `ban` `score.min`, `dnb` `confidence.min`, `opensanctions` `threshold`, `llm` `temperature` declared STRING (no numeric validation; invalid numbers throw `NumberFormatException` at run time). `dnb` `tradeUp` declared BOOLEAN but its documented value is `hq`, which fails the BOOLEAN check. `llm` `maxTokens` parsed with `Integer.parseInt` without fallback.
17. **Unchecked number parsing of inputs** (`NumberFormatException` instead of a `ConnectorException`): `orbis` `max`, `hubspot` `max`, `bigquery` `max`, `AbstractFileConnector` `max` and property `max.rows`.
18. **`graphql` and `soap` before configuration**: their `getOperations()` call `getConfig()`, which throws `IllegalStateException` on an unconfigured instance (the `config == null` checks are dead code); `getDescriptor()` catches it, other callers do not. `rest` handles it.
19. **Kafka `peek`** is described as "the latest messages without committing", but it subscribes with group `<group.id>-peek` and `auto.offset.reset=earliest`, never committing: it always returns the **oldest** retained messages. `consume` commits offsets of non-JSON messages it did not return (they are skipped permanently, reported only in `invalidMessages`). The `envelope.table` value is the string `"null"` when the input is absent.
20. ~~**Snowflake asynchronous statements**: when a statement is still running (HTTP 202) at the readTimeoutMs deadline, the 202 body is treated as a success and an empty result (`count` 0, `message`) is returned instead of an error.~~ **Fixed 2026-09-28**: `stillRunning(status)` now raises `ConnectorException("Snowflake: statement still running after Nms (read timeout)…")` when the poll loop gives up with the statement still at HTTP 202 (deadline reached, or a response missing its own `statementHandle`), instead of falling through to `collect()`. A failed partition fetch (`GET .../statements/{handle}?partition=p`) used to `break` the loop and hand back whatever had been collected so far as if the read had finished cleanly; it now raises `ConnectorException("Snowflake: partition p of N failed with HTTP …")` instead. `collect()` also now reports `truncated` (the source held more rows than `max` — from `appendRows`' own return, or from partitions left unfetched when `rows.size()` reached `max` first) and, when the SQL API's `resultSetMetaData.numRows` is present, the exact `available` total, read the same way as the JDBC and file feeds by `ConnectorService.importDocument`.
21. **Object-store reads are text-based**: `s3`, `azure-blob`, `gcs` convert the response body to a string then to UTF-8 bytes; binary objects are corrupted (irrelevant for CSV/JSON/XML, relevant for a future Excel/Parquet codec). Pagination descriptors (continuation tokens, `NextMarker`, `nextPageToken`, BigQuery `pageToken`) are advertised but not followed; `list` returns at most one page (≤1000, ≤5000 for Azure).
22. **SFTP host keys**: the connector refuses to connect without `knownHosts` or `hostKey` unless `strictHostKey=false` (§3.2, `sftp`). The packaged `jsch.jar` is the maintained `com.github.mwiede` fork (2.28.7): it knows Ed25519, ECDSA and RSA-SHA2 host keys; SHA-1 `ssh-rsa` and DSA are off by default (an old server that offers only those needs `server_host_key`/`PubkeyAcceptedAlgorithms` set in the connector's JSch configuration). The handshake itself is not covered by an automated test (no SSH server in the offline suite).
23. **Azure Service Bus settlement** depends on an in-memory static lock table: `complete`/`abandon` work only in the same JVM within 5 minutes of `receive`; the input `entity` is always required even though an `entity` property is declared.
24. **JDBC `query` safety filter** rejects any SELECT containing the word `into` (even in a literal or identifier) or a `;`; `profile.where` is concatenated verbatim into SQL; `DriverManager.setLoginTimeout` is JVM-global.
25. **BigQuery `upsert`** builds a MERGE with inline literals rather than parameters.
26. **Collibra PATCH** assumes `java.net.http` (JDK 11+); on Java 8 PATCH would not be converted to the `_HttpMethod` override.
27. **Unused/stub members**: `HubSpotConnector.unused()` returns null; `GleifConnector.firstAsRecord` builds an unused `keep` list; `DnbConnector.describeFields` has a no-op `prefix`; `SapODataConnector.collection`, `normalise`, `parseMetadata` are unused wrappers kept for compatibility; `SapAribaConnector` declares `api.version` but never uses it; `SynchronizationStrategy` and most `ConnectorCapability` / `PaginationDescriptor` values are metadata only.
28. **Descriptors inconsistent with behaviour**: generic `jdbc` advertises no `TEST_CONNECTION` though it implements it; `webhook` advertises PREVIEW_RECORDS but has no preview; `s3` advertises API_KEY_HEADER although it uses access keys in username/password; `bigquery` advertises `dataset` as required although fully qualified table names work without it; `workday` requires `clientId`/`refreshToken` even for RaaS-only use.
29. **Tested offline only** (GUIDE statements, not verifiable in code): `powerbi` and `collibra` against fakes; LLM providers Gemini/Mistral/Azure never called against a real service (per project memory).

### 6.3 ON EBX integration: services, UI, REST, MCP, automation
**Documentation vs code**
1. **Webhook path in the seeded description.** The `Webhook` connector's description says `POST /ebx-ps-fasttrack/connectors/webhook/{mapping id or label}`. The real endpoint is `POST /ebx-connectors/rest/v1/webhook/{feed}`. The `JDBC` description likewise points drivers to `ebx-ps-fasttrack-server/lib`, whereas drivers are packaged in the module WAR (`runtime-libs`).
2. **Connector count.** GUIDE §2 says "40 seeded"; the installer seeds **61** connectors. The GUIDE §2 table lists 7 tables; the model has 22 (templates, releases, provenance, conflicts, outbound states, checkpoints, metrics, governance, execution runs, schema refresh).
3. **Where the sync service is offered.** GUIDE §2 says it appears on "every table that has at least one mapping". The declaration activates on all tables of all datasets; tables without mappings show a hint. GUIDE §12.1 lists 3 scopes; the service offers 7 (plus UNSENT, CHANGED, FAILED, DATASPACE_DELTA).
4. **Declared input parameters of the sync service.** GUIDE §12.1 says `mapping`, `inputs` and `dryRun` are "declared as input parameters". Only `mapping` is declared (`addInputParameter`); the bundle keys `sync.param.inputs*` / `sync.param.dryRun*` exist but are unused. `inputs` and `dryRun` are still read from the session or request.
5. **Workflow bean `inputs`.** GUIDE §12.3 lists `inputs` as a bean input. The bean has the setter, but `module.xml` does not declare the property, so the library editor does not show it. GUIDE also mentions a ready-made model `ConnectorEnrichment` that is not in `tools/export/workflows/`.
6. **MCP `connectors_sync.inputs`.** Catalogued and handled by `ConnectorsTools`, but absent from the native `ConnectorsMcpServer.sync` signature, so it is unreachable through `/ebx-assistant/mcp/v1/ebx-connectors`. Its catalogue description says "JSON object text, e.g. {"maxMessages":"50"}", but the value is parsed as `name=value;…` by `ConnectorService.runtimeInputs`, so JSON text would not be understood as documented.
7. **Parameter type mismatches.** `connectors_config_import.config` is typed "JSON text" (string) in the catalogue and `Object` in the native server. `connectors_mapping_delete.mapping` is documented as "Mapping id" but labels are resolved too.
8. **Record key parameter name.** REST `/conflicts` uses `key`; MCP and the conflict manager use `recordKey`. The `agentInstruction` of `DemoEnrichmentConflicts.json` tells agents to call `GET /conflicts?recordKey=<id>`, which REST ignores (the list is then not narrowed).
9. **Log origins.** GUIDE §13 lists `user service`, `workflow admin`, `REST admin`. The code writes `userService`, `workflow <user>`, `REST <user>`, `MCP <user>`, `scheduler`, `designer`, `webhook <connectorId>`, `replay <runId> by <user>`.
10. **Webhook authentication.** Settled by C2-10: one mode, an EBX session with the RUN right. The shared-secret and HMAC-signature modes were removed (GUIDE §12.2).
11. **Script paths.** GUIDE §15 refers to `connectors-module/import_config.py` and `connectors-module/config_gleif_wikidata.py`; they live in `tools/`. `build_xsd.py`'s header also says "Generated by connectors-module/build_xsd.py".
12. **XSD generation.** `tools/README.md` says "edit the description, never the XSD", but `build_xsd.py` knows only 9 tables and lacks `ConnectorCategory`, `category`, `uploadedLogo`, `logoUrl` placement, `connection*` fields, `ConnectorTemplate`, release tables, `SchemaRefreshState`, `SynchronizationCheckpoint`, `loadMode`/checkpoint fields, metrics, governance, `ExecutionRun` and the metrics record form. Regenerating would drop them: the deployed XSD has been edited by hand.
13. **`extend_xsd.py` is misfiled.** It patches a Companies model at a hard-coded absolute path of a FastTrack workspace; it is not a Connectors tool.
14. **Stale code comments.** `MappingDesignerServiceDeclaration` says the host module name contains spaces (the former FastTrack module name); the module is now `ebx-connectors`. `ConnectorSyncServiceDeclaration`'s comment says "the service itself decides whether a mapping exists": in practice it is always shown and only displays a hint when none exists.
15. **Credential field length.** The BEARER warning and the page meter use a 255-character limit (`CredentialGuide.SECRET_MAX_LENGTH`), while `password`/`apiKey` are declared with `maxLength` 4000 in the XSD. Pasted secrets between 256 and 4000 characters are flagged "Too long for this field" although EBX accepts them.
16. **Connection status enumeration.** The XSD enumerates NOT_CONNECTED|CONNECTED|FAILED; the UI also shows STALE, computed at read time (consistent, but undocumented in the model).
17. **Release notes status.** `release-notes.xml` `status` attributes (`in-progress`, `complete`) are ignored: every development release is stored `IN_PROGRESS` and formal ones `CURRENT`. The labels `releaseNotes.status.IN_PROGRESS` / `DRAFT` have no bundle entry, so they render as the raw text `IN PROGRESS`.
18. **Log message.** `seedLot2` logs "seeded Claude, EORI, Countries, EBX, ChatWebhook, INPI and OpenFDA" but also creates JMS and ActiveMQ.
19. **Seed guard with the `never` policy.** `seedCatalog` and friends still create connectors (by design), but `childMapping` never checks `never` directly (it relies on the parent being skipped).

**Unfinished or unused in code**
20. **Unused persistence adapters.** `EbxSynchronizationCheckpointStore`, `EbxRecoverableExecutionStore` and `EbxConnectorGovernanceStore` have no caller in `main` (tests only). The governance API (`docs/GOVERNANCE.md`: READ/MANAGE/APPROVE/AUDIT, actor from the session) has no REST, MCP or UI adapter in this layer. The bundle keys `governance.*` are unused.
21. **Dead code.** `ConnectorsInstaller.needsSeed()` and `tables()` are unused. `ConnectorSyncUserService.hasMapping()` is unused. `designer.catalogLoaded` is an unused bundle key.
22. **Mapping form coverage.** `MappingForm` does not render `deleteOperation`, `maxAttempts`, `retryDelayMinutes`. Its Synchronization tab hint is hard-coded (English/French) instead of coming from the bundle.
23. **Delete preview count.** `DeleteMappingUserService`'s preview (`attachments`) omits `SynchronizationCheckpoint` rows, which `cascade` deletes.
24. **Runs without `beginRun`.** In `ConnectorSyncUserService`, the SELECTED/VIEW/ALL and feed paths call `syncRecords`/`importFrom` without `beginRun`/`endRun`; only the outbound-scope path gets a run id and the "replay" message. The workflow bean calls `endRun` outside a `finally`.
25. **Loop order.** The workflow bean on a table iterates mapping-first, not record-first as its comment states.
26. **Import and lineage.** `ConfigTransfer.importConfig` disables triggers and schedules no declared lineage, unlike `upsertMapping` and `deleteMapping`; run `POST /lineage/declare` afterwards.
27. **`GET /webhook/{feed}` exposure.** It does not call `ensure` or check the session explicitly. Whether anonymous callers reach it depends on the REST Toolkit's authentication.
28. **Diagnostics connect action.** Resolved: the diagnostics service has its own **Connect** button (`ftcdConnect`), next to the connector form's (`ftcdAction=connect` with its proof).
29. **Admin role coverage.** `ebx.connectors.adminRole` protects 7 configuration tables only; `Conflict`, `ConnectorTemplate`, release, governance and runtime tables stay under plain EBX permissions.
30. **Open security findings.** the security review of September 2026 marks 07 (egress allow-list is not default-deny and plaintext secrets are not rejected unless the opt-in properties are set; redirects and metadata addresses are now controlled, §2.11.4 and §2.13.1) and 11 (conflict acceptance is not atomic across the record and connector transactions) as **still open**. The release notes list the previous-schema upgrade and fresh-install acceptance (pass 2), the Excel provider (pass 6), and PostgreSQL governed-run acceptance (pass 9) as pending.