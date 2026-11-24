# Connector coverage roadmap

This is the live implementation tracker for expanding the ON EBX connector catalog to cover the
named integrations and components published by capability baseline and capability baseline xDI. It is intended for
developers and coding agents working in parallel. Update this document in the same change as the
connector, profile, template, test, or documentation it tracks.

Evidence policy:

- Each target requires an authoritative API, protocol, driver, or product contract.
- Historical source attribution is intentionally excluded from this live engineering tracker.
- Local catalog: `src/main/java/com/on/ebx/connect/core/ConnectorRegistry.java`
- Baseline date: 2026-09-16
- Baseline catalog: 92 registered connector types

capability baseline advertises more than 1,000 Integration Hub connectors but does not enumerate that entire
catalog on the source page. Therefore, **complete** means that every integration explicitly named on
the cited capability baseline page and every applicable component on the cited capability baseline page has a recorded,
verified disposition below. The long tail is covered through versioned REST/OpenAPI templates rather
than an unbounded promise to duplicate every Integration Hub connector.

## Status and ownership rules

Use exactly one status per item:

| Status | Meaning |
|---|---|
| `BACKLOG` | Classified but not assigned |
| `READY` | Scope and dependencies are clear; safe to start |
| `IN_PROGRESS` | Actively owned; the `Owner / branch` cell must be filled |
| `BLOCKED` | Cannot progress; add the blocker and next decision |
| `REVIEW` | Implementation is complete and awaiting review or acceptance |
| `DONE` | Definition of done is satisfied and evidence is linked |
| `N/A` | Deliberately not a connector; the disposition explains where the capability lives |

Parallel-work protocol:

1. Before coding, change the item to `IN_PROGRESS` and record the developer/agent and branch or task.
2. One owner may claim multiple closely related items, but no item may have two active owners.
3. Shared framework changes (`Connector`, `ConnectorRegistry`, codecs, secret resolution, XSD, common
   UI) must be called out in the notes before implementation so parallel owners can coordinate.
4. Prefer one reviewable change per connector family. Do not combine unrelated vendors merely to
   increase the connector count.
5. Update the evidence links and status in the same pull request. `DONE` without evidence is invalid.
6. If an item is covered by a generic implementation, add a certified profile or template and tests;
   do not mark it `DONE` based only on a statement that JDBC or REST could theoretically reach it.
7. Every wave ends with the full `./build-test.sh` baseline, roadmap and evidence updates, a clearly
   named wave-completion commit, and a push to `feature/ebx-connectors-enrichment`. The handoff report
   must include the commit hash, delivered scope, verification performed, known limitations, and the
   next wave. A wave is not complete merely because its changes exist locally.

## Definition of done

A dedicated connector is `DONE` only when all applicable items below are complete:

- Registered type and complete descriptor: category, capabilities, operations, authentication,
  configuration properties, API version, pagination, and optional dependencies.
- Configuration validation, bounded execution, timeouts, TLS/egress checks, secret redaction,
  retries/rate-limit behavior, and safe connection testing.
- Discovery and preview; read/write, checkpoint, catalog, profiling, sampling, usage, or lineage where
  the target supports them.
- Offline unit and contract tests plus recorded vendor fixtures. Live acceptance is required before a
  production-ready claim; credentials and secrets must never enter the repository.
- Acceptance-lab scenario using the Data Connector perspective and retained evidence.
- Catalog logo, accessible label, credential guidance, release notes, and a connector-specific user
  guide linked from `docs/USER-GUIDE.md` and `docs/USER-GUIDE.html`.
- Build passes with `./build-test.sh`; the connector remains optional when its external library is not
  installed.

A JDBC profile or REST/OpenAPI template is `DONE` only when it has vendor-specific validation,
operation defaults, dependency/authentication documentation, contract tests, acceptance evidence,
and catalog/user-guide presentation. A generic connector alone is not evidence of compatibility.

## Delivery summary

| Wave | Scope | Exit criterion | Status |
|---|---|---|---|
| W0 | Coverage manifest and enforcement | Every source item has a stable ID and build-checked disposition | `DONE` |
| W1 | Certified JDBC profiles and secret providers | Generic coverage becomes explicit, tested catalog coverage | `IN_PROGRESS` |
| W2 | Cloud messaging, queues, files, and Sheets | Named messaging, queue, file, and spreadsheet targets have dispositions | `IN_PROGRESS` |
| W3 | NoSQL and search platforms | All named operational datastores have native coverage | `IN_PROGRESS` |
| W4 | File codecs and content repositories | Excel, Avro, Parquet, and CMIS are usable end to end | `IN_PROGRESS` |
| W5 | Business and catalog integrations | Named business and catalog integrations have implemented coverage or an evidence-gated disposition | `IN_PROGRESS` |
| W6 | CDC, Hadoop, and Spark | Incremental and distributed-data component gaps are closed | `IN_PROGRESS` |
| W7 | Specialist dispositions and tracking closeout | Specialist items have approved dispositions; source names, URLs, and assessment language are removed from tracking artifacts | `IN_PROGRESS` |
| W8 | Connector developer scaffold | New connector work starts from safe, deterministic project conventions | `DONE` |
| W9 | Generated capability report | Catalog and capability counts are generated and build-checked from the manifest | `DONE` |
| W10 | Kafka CDC production safety | CDC polling and EBX checkpoints obey apply-before-confirm ordering | `IN_PROGRESS` |
| W11 | Acceptance harness | Repeatable acceptance-lab scenarios exercise connector release evidence | `IN_PROGRESS` |
| W12 | Evidence gates | Release evidence fails closed when required artifacts or results are absent | `DONE` |
| W13 | Release readiness verifier | Source, WAR, catalog, documentation and asset checks produce deterministic release evidence | `DONE` |
| W14 | Catalog metadata and preview reconciliation | Shared-drive metadata, SQL Server metadata, masked preview and all 92 catalog seeds are integrated | `REVIEW` |
| W15 | Discoverable guides and parallel-safe verification | Every connector guide is bidirectionally indexed and concurrent waves can run isolated baselines | `DONE` |
| W16 | Metadata security and release evidence | Drive scope, preview masking, SQL metadata and packaged release evidence fail closed | `REVIEW` |
| W17 | End-to-end operationalization | Every catalog connector has an explicit lifecycle state, evidence source and next action | `IN_PROGRESS` |

## W0 — Coverage manifest and build enforcement

| ID | Deliverable | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| GOV-001 | `connector-coverage.yaml` machine-readable inventory | New build input | `DONE` | Codex / `feature/ebx-connectors-enrichment` | The catalog and all 86 capability-baseline targets are explicit |
| GOV-002 | Coverage validation test | Build/contract test | `DONE` | Codex / `feature/ebx-connectors-enrichment` | `./build-test.sh` passes; build fails for untracked registry types, incomplete source inventories, invalid states/references, unsafe paths, and invalid `DONE` evidence |
| GOV-003 | Connector scaffold/checklist | Developer tooling | `DONE` | Codex / `feature/ebx-connectors-enrichment` | `tools/scaffold_connector.py` safely generates an unavailable class, staged profile, descriptor test, fixture layout, neutral logo and connector guide; dry-run, determinism, path safety and no-overwrite behavior are build-tested |
| GOV-004 | Catalog capability report | Generated documentation | `DONE` | Codex / `feature/ebx-connectors-enrichment` | `tools/generate_connector_parity.py` deterministically renders `docs/CONNECTOR-CAPABILITY-PARITY.md` from the manifest with SHA-256 provenance and build-enforced stale checking |

## W1 — Certified JDBC profiles

These entries reuse `JdbcConnector` and the shared JDBC engine. Each still requires a tested driver,
URL validation, dialect behavior, catalog discovery, write/upsert rules, dependency declaration, logo,
and guide.

| ID | Target | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| JDBC-001 | Actian Avalanche / Vector | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Canonical Actian profile; VectorWise retained as legacy wording; offline contract and guide pass; live Avalanche and Vector acceptance remain |
| JDBC-002 | dBase | Certified JDBC profile | `BLOCKED` | — | No current authoritative Type-4 Java 8 JDBC contract; a DBF file reader would be a separate connector |
| JDBC-003 | Firebird | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Jaybird profile, bounds, offline contract and guide implemented; driver deployment and live acceptance remain |
| JDBC-004 | Greenplum | Certified PostgreSQL-family profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Dedicated type, PostgreSQL-driver reuse, bounded offline contract and guide implemented; live metadata/TLS/write acceptance remains |
| JDBC-005 | HyperFileSQL | Certified JDBC profile | `BLOCKED` | — | No authoritative standalone Type-4 Java 8 JDBC driver contract; JDBC-ODBC is unavailable on Java 8 |
| JDBC-006 | IBM Db2 | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Db2 LUW profile, bounds, offline contract and guide implemented; z/OS and IBM i excluded; live LUW acceptance remains |
| JDBC-007 | IBM Netezza | Certified JDBC profile | `BLOCKED` | — | Requires an explicit vendor-supported Java 8 driver/server matrix; current lines may require Java 11 |
| JDBC-008 | IBM Informix | Certified JDBC profile | `BLOCKED` | — | Pin a vendor-supported Java 8 Informix JDBC release and supply the proprietary driver before implementation |
| JDBC-009 | Ingres | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Distinct Actian Ingres profile, bounds, offline contract and guide implemented; external driver deployment/live acceptance remain |
| JDBC-010 | InterSystems IRIS / Caché | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Canonical IRIS profile with Caché URL compatibility, bounds, offline contract and guide implemented; live acceptance remains |
| JDBC-011 | SingleStore / MemSQL | Certified MySQL-family profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Dedicated type, catalog-aware Connector/J compatibility, secure defaults, offline contract and guide implemented; live distributed-table write/upsert acceptance remains |
| JDBC-012 | Microsoft Access | Certified JDBC profile | `BLOCKED` | — | Maintained UCanAccess requires Java 11+; legacy Java 8 line is unmaintained and local-file security needs separate design |
| JDBC-013 | MonetDB | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Current Java 8 profile, TLS URL, COPY ON CLIENT boundary, offline contract and guide implemented; license review/packaging/live acceptance remain |
| JDBC-014 | Paradox | Certified JDBC profile | `BLOCKED` | — | No maintained authoritative Type-4 Java 8 JDBC driver |
| JDBC-015 | Progress OpenEdge | Certified JDBC profile | `BLOCKED` | — | Supply and bytecode-test the chosen proprietary OpenEdge driver for Java 8 first |
| JDBC-016 | SAP ASE | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | jConnect profile, bounds, offline contract and guide implemented; vendor driver deployment and live acceptance remain |
| JDBC-017 | SAP HANA | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | ngdbc profile, bounds, offline contract and guide implemented; driver deployment and live HANA acceptance remain |
| JDBC-018 | SAP IQ | Certified JDBC profile | `BLOCKED` | — | Choose and supply one exact supported driver family before coding; jConnect and SQL Anywhere contracts differ |
| JDBC-019 | SAP SQL Anywhere | Certified JDBC profile | `BLOCKED` | — | Confirm and supply an exact Java 8 vendor driver/class contract before coding |
| JDBC-020 | SQreamDB | Certified JDBC profile | `BLOCKED` | — | Current driver requires Java 17; older Java 8 line needs a vendor support/security commitment |
| JDBC-021 | Teradata | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Query-bounded JDBC profile, offline contract and guide implemented; driver deployment/live Vantage acceptance remain |
| JDBC-022 | Vertica | Certified JDBC profile | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Bounded JDBC profile, offline contract and guide implemented; driver deployment/live bulk and upsert acceptance remain |
| JDBC-023 | Stored procedures | Shared JDBC operation/profile contract | `REVIEW` | Codex / `feature/ebx-connectors-enrichment` | Strict bounded callable syntax, typed IN/OUT/INOUT/RETURN parameters, transactions, redacted errors and offline fixtures implemented; vendor portability acceptance remains |
| JDBC-024 | Amazon Redshift | Certified JDBC profile | `IN_PROGRESS` | Claude / `claude/gartner-gaps-e3ba8e` | Endpoint-URL profile (vendor driver 2.x), cluster-identifier form and driver-side class loading refused, offline contract and additive seed implemented; driver deployment and live acceptance remain; Data API not implemented |

## W1 — Secret providers

| ID | Target | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| SECRET-001 | HashiCorp Vault | Existing shared capability | `DONE` | Existing implementation | Verify via `SecretResolvers`; retain regression coverage |
| SECRET-002 | Azure Key Vault | Existing shared capability | `DONE` | Existing implementation | Verify via `SecretResolvers`; retain regression coverage |
| SECRET-003 | AWS Secrets Manager | New `SecretResolver` | `REVIEW` | Codex / `feature/ebx-connectors-enrichment` | Signed GetSecretValue; static/temporary credentials, bounded shared profiles, ECS task credentials and IMDSv2-only; selectors, JSON/binary extraction, caching, redaction, strict metadata endpoints, no redirects, response bounds, and offline fixtures pass. Live AWS acceptance remains before `DONE`. |
| SECRET-004 | Google Secret Manager | New `SecretResolver` | `REVIEW` | Codex / `feature/ebx-connectors-enrichment` | Native versioned access; service-account, metadata and bounded local-file workload identity; RFC 8693 STS and optional impersonation; CRC32C, endpoint controls, bounds, redirect refusal and offline fixtures pass. Live Google acceptance remains before `DONE`. |

## W2 — Cloud messaging, queues, files, and collaboration

| ID | Target | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| MSG-001 | Amazon SQS | Dedicated connector/transport | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Signed bounded send/receive/peek, explicit delete ack, visibility and offline fixtures implemented; live AWS acceptance remains |
| MSG-002 | Amazon SNS | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Signed bounded publish and topic discovery implemented; subscription admin excluded; live AWS acceptance remains |
| MSG-003 | Google Pub/Sub | Dedicated connector/transport | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Bounded publish/pull/explicit ack and discovery fixtures implemented; workload-identity parity/live acceptance remain |
| MSG-004 | Azure Queue Storage | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Bounded send/peek/receive, explicit pop-receipt delete and discovery fixtures implemented; distinct from Service Bus; live acceptance remains |
| FILE-001 | FTP / FTPS | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Explicit/implicit FTPS, cleartext opt-in, bounded passive transport and safety fixtures implemented; server compatibility/live acceptance remain |
| SAAS-001 | Google Sheets | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Metadata, bounded ranges, RAW append/update and explicit formula opt-in fixtures implemented; workload-identity parity/live acceptance remain |
| STORE-001 | Azure Table Storage | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Keyed entity operations, opaque continuation and same-partition batch fixtures implemented; Shared Key/live Azure acceptance remain |

## W3 — NoSQL and search platforms

| ID | Target | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| DATA-001 | Apache Cassandra | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Concrete pinned driver backend, bounded prepared CQL/discovery/paging fixtures; native types and live acceptance remain; CDC excluded |
| DATA-002 | Azure Cosmos DB | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Bounded NoSQL REST discovery, point/item operations, parameterized query continuation, bearer/resource-token auth and partition-key fixtures; Shared Key/change feed/live acceptance excluded |
| DATA-003 | MongoDB | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Pinned patched Java 8 sync driver; bounded discovery/CRUD/keyset paging fixtures; live acceptance remains; change streams excluded |
| DATA-004 | Couchbase | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Bounded KV discovery/get/upsert/remove fixtures; SDK packaging and live acceptance remain; SQL++ and DCP excluded |
| DATA-005 | Elasticsearch | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Bounded discovery, mappings, get, search_after and bulk fixtures implemented; live cluster acceptance remains |

## W4 — Formats and repositories

These formats extend `DocumentCodecProvider` and then become available consistently across S3, GCS,
Azure Blob, SFTP/FTP, webhooks, and other file transports.

| ID | Target | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| CODEC-001 | Microsoft Excel | Document codec/provider | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Bounded first-sheet XLSX scalar rows implemented; legacy XLS, formulas and complex workbook features excluded; live workbook acceptance remains |
| CODEC-002 | Apache Avro | Document codec/provider | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Java 8 Avro OCF flat scalar records implemented; logical types, complex unions and schema registry remain explicit boundaries |
| CODEC-003 | Apache Parquet | Document codec/provider | `BLOCKED` | Codex / `feature/ebx-connectors-enrichment` | Runtime rejects explicitly pending approved Java 8 Parquet/Hadoop closure, schema contract and target use case |
| REPO-001 | CMIS | Dedicated repository connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Browser Binding discovery, bounded content and controlled writes implemented; live repository acceptance remains |

## W5 — Business and catalog integrations

Existing coverage: Salesforce, D&B, Collibra, and Moody's through the Orbis connector.

| ID | Target | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| BASE-BIZ-001 | Veeva Vault CRM | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | First-party REST/VQL contract; bounded object discovery and one-page VQL fixtures implemented. Writes, bulk jobs and licensed live acceptance remain |
| BASE-BIZ-002 | MedPro | Governed REST template, promote if necessary | `BLOCKED` | Codex / `feature/ebx-connectors-enrichment` | Requires licensed customer API reference, sandbox, authentication, HCP/HCO schemas, pagination, rate limits, permitted-use terms and redacted fixtures; no endpoint inferred |
| BASE-BIZ-003 | ZoomInfo | Dedicated connector or governed REST template | `BLOCKED` | Codex / `feature/ebx-connectors-enrichment` | Requires entitled product/API version, sandbox, authentication renewal, company/contact schemas, credit behavior, pagination, rate limits, provenance and retention terms |
| BASE-BIZ-004 | Alation | Dedicated catalog connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | First-party Search and Relational Catalog APIs; bounded reads and TOKEN-header fixture implemented. Writes, lineage ingestion and live acceptance remain |
| BASE-BIZ-005 | Microsoft Purview | Dedicated catalog connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Stable 2023-09-01 Data Map search plus Atlas entity/type discovery and Entra auth fixture implemented. Mutations, preview APIs and live acceptance remain |

## W6 — CDC and distributed data

| ID | Target | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| CDC-001 | Change Data Capture contract | Shared connector capability | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Stable source identity/position IDs, immutable payloads, snapshot boundaries, CRUD/tombstones, bounded replay rejection, canonical schema fingerprints and checkpoint-after-apply contract implemented; live source acceptance remains |
| CDC-002 | Debezium/Kafka CDC | CDC adapter over Kafka | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Isolated no-commit Kafka polling seeks from durable checkpoints and confirms only after successful EBX apply; failure/dry-run tests pass. Feed mapping on operation `cdc` applies upserts and delete / flag / ignore, then saves the checkpoint (docs/CDC.md); RC acceptance on a live Kafka broker with Debezium-format events passed 2026-10-30. Schema-registry formats and a real Debezium connector remain |
| DATA-006 | Hadoop-compatible data | Dedicated connector/profile family | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Bounded HTTPS WebHDFS/HttpFS gateway discovery, range reads and controlled writes implemented; native redirects, Kerberos and live gateway acceptance remain excluded |
| DATA-007 | Apache Spark | Dedicated connector | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Read-only bounded Spark SQL JDBC/JNDI profile implemented; approved Java 8 Hive JDBC deployment and live endpoint acceptance remain |

## W7 — Specialist decisions

| ID | Target | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| SAAS-002 | MDM REST integration | Dedicated connector or REST template | `BACKLOG` | — | Require a licensed, versioned API contract, sandbox, authentication, schemas, pagination, concurrency, bulk/error semantics and redacted fixtures |
| SAAS-003 | HCL Domino / Lotus Notes | Optional extension | `IN_PROGRESS` | Codex / `feature/ebx-connectors-enrichment` | Read-only Domino REST API Basis connector, bounded DQL, bearer/TLS validation, catalog seed, guide and loopback contract implemented; licensed live acceptance remains |
| SAAS-004 | Social post search API | Optional REST template | `BLOCKED` | — | Blocked on product priority, API tier, terms, and acceptance credentials |

### Mandatory W7 closeout

| ID | Deliverable | Strategy | Status | Owner / branch | Evidence / notes |
|---|---|---|---|---|---|
| CLOSE-001 | Attribution-neutral roadmap and coverage tracking | Documentation and manifest migration | `DONE` | Codex / `feature/ebx-connectors-enrichment` | Tracking uses neutral baseline sources and IDs; source links and attribution language are removed; statuses, strategies, coverage links and evidence remain build-enforced. |

## Components classified outside the connector catalog

| Source component | Disposition | Status | Evidence / next action |
|---|---|---|---|
| Amazon, Microsoft Azure, Google Cloud Platform | Provider group headings, not individual endpoints | `N/A` | Coverage is measured against their concrete services |
| Privacy Protect | Shared masking/transformation capability | `N/A` | Track security/masking tests, not a connector card |
| Sampling | Shared preview/catalog capability | `N/A` | Enforce through connector contracts |
| Amazon S3 Delivery Repository | Existing S3 connector | `DONE` | Keep S3 fixture and guide evidence current |
| Google Cloud Storage Delivery Repository | Existing GCS connector | `DONE` | Keep GCS fixture and guide evidence current |
| Microsoft Azure Blob Storage Delivery Repository | Existing Azure Blob connector | `DONE` | Keep Azure fixture and guide evidence current |
| AMQP | Existing RabbitMQ/AMQP transport | `DONE` | Preserve protocol-level contract tests |
| JMS | Existing JMS connector | `DONE` | Preserve provider-neutral contract tests |
| HTTP REST | Existing Generic REST and OpenAPI import | `DONE` | Product claims still require governed templates and evidence |

## Acceptance evidence index

Add one row per completed item. Evidence should point to repository-relative fixtures, tests, guide,
and acceptance-lab scenario; never link to local-only files or credential-bearing output.

| ID | Implementation | Contract test | Fixture | Acceptance lab | User guide | Last verified |
|---|---|---|---|---|---|---|
| SECRET-001 | `SecretResolvers` | To be indexed | To be indexed | N/A | `GUIDE.md` | Existing; index pending |
| SECRET-002 | `SecretResolvers` | To be indexed | To be indexed | N/A | `GUIDE.md` | Existing; index pending |

## Progress log

Keep this short; the tables above are authoritative. Add material changes in reverse chronological
order.

| Date | Change | Author / task |
|---|---|---|
| 2026-10-31 | RC acceptance: documents feed 36/36 with a real LLM (commit ad7d232), Debezium CDC 17/17 on a live Kafka broker (commit 317a758, CDC code unchanged since); SAP, Workday, BigQuery and Redshift not run (no tenants) | Claude |
| 2026-10-30 | Debezium CDC applied to EBX: feed mapping on Kafka operation `cdc`, delete / flag / ignore, checkpoint saved after apply (docs/CDC.md) | Claude |
| 2026-10-30 | Document feed with AI extraction: `documents` type (PDF, DOCX, text), confidence envelope, proposals below the threshold, `FieldProvenance.source` (docs/DOCUMENTS.md) | Claude |
| 2026-10-30 | Connector depth: Amazon Redshift JDBC profile; SAP OData fetches a fresh CSRF token and retries a rejected write once; Workday pages REST collections (limit/offset) and refreshes its token after a 401; BigQuery polls running jobs, follows page tokens and gains catalog/profile/sample from INFORMATION_SCHEMA; mocked-HTTP tests for all four; live tenant acceptance remains | Claude |
| 2026-09-17 | W17 VIES deployment gate: preserved the prior deployed module, hot-deployed the checksum-matched verified WAR, confirmed EBX UI/auth readiness, and visually verified the VIES-specific unauthenticated POST/host guidance; no connector record was changed | Codex |
| 2026-09-17 | W17 VIES live inspection: authenticated read-only review confirmed the existing active public connector, endpoint, trust policy, no properties, no flows and missing outbound allow-list; fixed the discovered GLEIF-specific help leak with neutral and connector-specific bilingual examples | Codex |
| 2026-09-17 | W17 VIES fixture gate: added a deterministic bounded VAT-validation receiver and verifier for valid/invalid responses, provider error wrappers, strict request shape, malformed JSON, undeclared paths and payload bounds; live inspection and governed execution remain next | Codex |
| 2026-09-17 | W17 OpenLineage live inspection: authenticated read-only review confirmed the active existing record, correct connector type, placeholder endpoint, Bearer mode, no connector-property overrides and no configured flows; nothing was changed | Codex |
| 2026-09-17 | W17 OpenLineage fixture gate: added a bounded loopback capture receiver and deterministic verifier for OpenLineage 2.0 run/job schemas, stable replay, dataset and column-lineage facets, declaration/retirement, credential-shaped field rejection, payload bounds and cleanup; authenticated read-only inspection of the existing EBX record remains next | Codex |
| 2026-09-17 | W17 GLEIF live gate: verified the pinned public LEI fixture, passed the configured EBX connection diagnostic, simulated the existing mapping on company key 9, and completed a selected-record live run with 1 unchanged, 0 updates and 0 errors; bounded full-load and delta-watermark flows remain pending | Codex |
| 2026-09-17 | W17 H2 live gate: reran the isolated H2 fixture successfully and inspected the existing H2 record in the Data connector perspective; no flows are configured, so mapping through restoration remains live-pending without creating acceptance-only records | Codex |
| 2026-09-16 | W17 start: introduced a build-checked 92-connector operational matrix; Kafka is the sole accepted baseline, the first fixture-ready cohort is explicit, and untested connectors remain NOT_STARTED | Codex |
| 2026-09-16 | W16: hardened shared-drive scope and pagination, fail-closed catalog preview and SQL metadata enrichment; integrated release-readiness checks into the baseline and refreshed packaged development evidence | Codex |
| 2026-09-16 | W15: completed the Markdown and HTML connector-guide indexes, added a build gate preventing orphaned or one-way guides, and made the baseline build directory overridable for parallel wave isolation | Codex |
| 2026-09-16 | W14: reconciled Google Drive/OneDrive metadata discovery, SQL Server source metadata, fail-closed masked catalog preview, and additive installer seeds for every declared catalog type; live provider acceptance remains | Codex |
| 2026-09-16 | W13: added deterministic source/WAR/catalog/documentation release-readiness verification | Codex |
| 2026-09-16 | W12: added fail-closed release evidence gates for missing or incomplete acceptance artifacts | Codex |
| 2026-09-16 | W10: hardened CDC identity, payload/checkpoint bounds and canonical schema fingerprints; added isolated no-commit Kafka polling with apply-before-checkpoint confirmation and negative failure/dry-run tests | Codex |
| 2026-09-16 | W9: added a deterministic manifest-derived catalog/capability report with provenance hashing, unit tests and build-enforced stale checking | Codex |
| 2026-09-16 | W8: added a deterministic, no-overwrite connector scaffold and checklist that generates six safe review artifacts without registering unfinished connector types | Codex |
| 2026-09-16 | W6: added CDC/Debezium normalization and checkpoint contracts, bounded WebHDFS/HttpFS gateway access, and a read-only Spark SQL profile with deterministic fixtures and guides | Codex |
| 2026-09-16 | W5: added bounded read-only Veeva Vault, Alation and Microsoft Purview connectors with deterministic fixtures, guides and additive catalog seeds; MedPro and ZoomInfo remain evidence-gated without invented endpoints | Codex |
| 2026-09-16 | W3 second tranche: added bounded Cassandra, MongoDB and Couchbase cores with pinned Java 8 dependency contracts, guides and deterministic fixtures; live acceptance remains | Codex |
| 2026-09-16 | W3 start: added bounded Elasticsearch search/bulk connector and audited Java 8/core scopes for Cassandra, Cosmos DB, MongoDB and Couchbase | Codex |
| 2026-09-16 | W4: added bounded XLSX and Avro document-codec providers plus CMIS Browser Binding; Parquet remains explicitly gated pending an approved Java 8 dependency closure and use case | Codex |
| 2026-09-16 | W2 second tranche: added Azure Queue Storage, Azure Table Storage and Google Sheets with bounded operations, explicit destructive/write controls, guides and offline fixtures; auth parity/live acceptance remain | Codex |
| 2026-09-16 | W2 first tranche: added Amazon SQS/SNS, Google Pub/Sub and FTP/FTPS with bounded non-destructive semantics, hardened auth/transport fixtures, guides and assets; live acceptance remains | Codex |
| 2026-09-16 | W1 JDBC: added distinct Actian Ingres and MonetDB profiles; documented and expanded negative coverage for bounded stored-procedure calls | Codex |
| 2026-09-16 | W1 JDBC: added Actian Avalanche/Vector profile and bounded stored-procedure operation; audited remaining driver/runtime blockers and promoted Ingres/MonetDB as the next safe batch | Codex |
| 2026-09-16 | W1 JDBC tranche: added SAP ASE, SAP HANA, Teradata and Vertica profiles with bounded validation, offline contracts, readable assets and connector guides; vendor driver deployment/live acceptance remain | Codex |
| 2026-09-16 | W1 JDBC tranche: added Firebird, IBM Db2 LUW and InterSystems IRIS/Caché profiles with bounded validation, offline contracts, readable assets and connector guides; driver deployment/live acceptance remain | Codex |
| 2026-09-16 | W1 JDBC family batch: added Greenplum and SingleStore types, catalog-aware MySQL-family metadata, secure defaults, offline contracts, readable logos and connector guides; live acceptance remains | Codex |
| 2026-09-16 | W1 Google Secret Manager slice: completed bounded local-file workload identity, RFC 8693 STS and optional service-account impersonation; unsafe URL/AWS/executable subject sources are rejected; full baseline passed | Codex |
| 2026-09-16 | W1 Google Secret Manager slice: added native versioned access, service-account JWT and metadata identity, CRC32C verification, bounds, redirect refusal, redaction and offline fixtures; external-account/workload identity remains | Codex |
| 2026-09-16 | W1 AWS slice: completed ECS task and IMDSv2-only credential flows; security review added strict endpoints, no redirects, response/token bounds, and temporary-token requirements; full baseline passed | Codex |
| 2026-09-16 | W1 AWS slice: added bounded named/default shared-credentials-profile loading after property/environment precedence; full baseline passed | Codex |
| 2026-09-16 | W1 AWS slice: added signed Secrets Manager retrieval with static/temporary credentials, selectors, binary/JSON extraction, cache, redaction, and offline acceptance fixture; workload credential chain remains | Codex |
| 2026-09-16 | Began W1 security foundation: values returned by any secret resolver are registered with global redaction before caching or return | Codex |
| 2026-09-16 | Completed W0: machine-readable manifest and build-enforced coverage contract; full module, SPI, and extension baseline passed | Codex |
| 2026-09-16 | Created the live capability roadmap; no implementation items claimed | Codex |
