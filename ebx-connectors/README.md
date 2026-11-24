# ebx-connectors — the connectors module for ON EBX

A self-contained, shareable EBX module: connectors to external applications and services,
bidirectional mappings with transformations, a graphical designer, and entry points everywhere in
EBX (user service, workflow, scheduled task, table trigger, REST API, MCP tools for agents).

**Complete guide, mappings first, with examples and a cheat sheet for an AI agent: [GUIDE.md](GUIDE.md) (English), [GUIDE.fr.md](GUIDE.fr.md) (français).**
**User guide with screenshots: [docs/USER-GUIDE.md](docs/USER-GUIDE.md) (also as a self-contained HTML page).**
**Live connector expansion tracker: [docs/CONNECTOR-COVERAGE-ROADMAP.md](docs/CONNECTOR-COVERAGE-ROADMAP.md). Developers and agents must claim an item there before parallel implementation and update its evidence in the same change.**

**Deployment compatibility and rollback runbook: [../docs/UPGRADE-ROLLBACK.md](../docs/UPGRADE-ROLLBACK.md).**

**Other ON EBX modules of the same server (MDM Factory, data catalog) call Connectors through a versioned in-JVM contract: [docs/CONTRACT.md](docs/CONTRACT.md).**

## Contents

| Location | Role |
|---|---|
| `src/main/java/com/on/ebx/connect/spi` | the contract of a connector (`Connector`, `Operation`, `ConnectorConfig`, `FieldInfo`, `SecretResolver`, `TablePublisher` for a whole-product publication) |
| `src/main/java/com/on/ebx/connect/core` | the engine: JSON, HTTP, transformations, expressions, mappings, registry, service, secrets, OAuth 2.0, TLS, product publication (`ProductPublisher`) |
| `src/main/java/com/on/ebx/connect/connectors` | 96 connector types (GLEIF, Sirene, VIES, BAN, Salesforce, SAP, Kafka, PostgreSQL, JDBC, S3, Snowflake, Databricks, Power BI, Collibra, OpenLineage, Slack, LLM, SOAP, GraphQL…) |
| `src/main/java/com/on/ebx/connect/ebx` | EBX integration: installer, designer, user services, workflow, scheduler, trigger, REST, MCP |
| `src/main/webapp/WEB-INF/ebx/module.xml` | declaration of the `ebx-connectors` module and of the workflow bean |
| `src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd` | the configuration model (dataspace / dataset `Connectors`, 27 tables), edited by hand; `tools/build_xsd.py` is history and knows 9 of the 27 tables: do not run it over this file |
| `src/main/webapp/WEB-INF/web.xml` | registration of the REST API (REST Toolkit) |
| `src/main/webapp/www/common/{jscripts,stylesheets}/connect` | front end of the mapping designer |
| `src/main/webapp/www/common/images/connect/logos` | one logo tile per seeded connector |

The module name, `ebx-connectors`, has neither space nor special character: service keys
(`ebx-connectors@ConnectorSync`) follow the pattern EBX requires.

## Dependencies

The war built by `build-test.sh` already carries the libraries of `runtime-libs/` in its `WEB-INF/lib` (Jackson, SLF4J, Kafka, JSch, the PostgreSQL, H2, Oracle,
SQL Server and MySQL drivers, the messaging clients...; list and licences in `runtime-libs/THIRD-PARTY-NOTICES.md`): a second copy in the
shared `lib/` must not exist. What the server provides and what the customer supplies (the Amazon Redshift driver and the other drivers
not in the war): [docs/OPERATIONS.md](docs/OPERATIONS.md) sections 1.1 and 2.6. For a build from the sources, in the shared Tomcat classpath (`lib/`)
or in the module's `WEB-INF/lib`:

- `ebx.jar` (standard ON EBX installation; `ebx-lib.jar` on FastTrack, the same jar) and `servlet-api.jar`: supplied by ON EBX and Tomcat, to compile against,
- `jackson-core`, `jackson-databind`, `jackson-annotations` 2.14.2 (required) and `slf4j-api` 1.7.32: **carried by the war** (`runtime-libs/`), because a standard ON EBX `lib/` has none of them; a FastTrack `lib/` has them too and the war's copy wins,
- `jackson-dataformat-yaml`, `snakeyaml` (OpenAPI specifications in YAML, optional),
- `kafka-clients` (Kafka connector, optional), `jsch` (SFTP connector, optional), JDBC drivers (optional).

A connector type whose library is missing is simply not registered; the others keep working.
No dependency on the FastTrack module nor on any presales toolbox. The dependencies of every
connector type are listed in [GUIDE.md](GUIDE.md), section 18.

## Installation

1. Deploy the module as a Tomcat webapp under the `/ebx-connectors` context (a WAR, or a context
   pointing at `src/main/webapp` with `target/classes` mounted on `/WEB-INF/classes`, see
   `ebx-ps-fasttrack-server/conf/Catalina/localhost/ebx-connectors.xml` in a FastTrack workspace).
   On a FastTrack workspace, `tools/install.sh` at the repository root does the copy and the build for you
   (below).
2. Restart: at startup the module creates the `Connectors` dataspace and dataset and seeds them
   (about 60 sample connectors with secrets left as `TO_BE_SET`, demonstration mappings on `/root/Companies` when
   that table exists, `-Debx.connectors.seedMappings=auto|always|never`).
3. Enter the keys in the Connector table (Data > Connectors), or reference them from a vault
   (`vault:`, `azurekv:`, `awssecrets:`, `gcpsecret:`, `env:`, `file:`; GUIDE.md section 3.1.1).

Startup is additive and idempotent: existing connector configuration is preserved, missing seed and
category rows are added, and newly introduced fields are initialised only when blank. If a `Connectors`
dataset uses the model of another module (an older FastTrack installation), startup stops and reports
the mismatch without deleting anything. Export that configuration (`GET /rest/v1/config`), perform an
explicit model migration, and import it afterwards; module startup never performs this destructive
replacement automatically.

### Installing into a FastTrack workspace (`tools/install.sh`)

```
tools/install.sh [--dry-run]            # EBX_WORKSPACE=<.../Workspace/ebx-ps-fasttrack> to choose the workspace
```

Run it from a clone of this repository (or a worktree of `main`) **while the Tomcat of that workspace is stopped**
(it refuses otherwise; another instance on the machine may keep running). It `rsync`s `ebx-connectors/` into
`<workspace>/ebx-connectors/` (the copy the Tomcat context `ebx-connectors.xml` points at; `target/`, `dist/` and
`src/main/webapp/WEB-INF/lib`, which holds runtime jars that are not in git, are neither copied nor deleted), rebuilds
the copy's `target/classes` with `build-test.sh` (`EBX_LIB_DIR` = the workspace server `lib`, `EBX_BUILD_DIR` = the
copy's `target`), checks `version.properties` and prints one line: version, number of classes, copy path. The version
and the commit come from this repository (the copy has no `.git`) and travel as `EBX_BUILD_VERSION` /
`EBX_BUILD_COMMIT`. `--dry-run` prints the steps and the files that would change and writes nothing. The log of a run
is `${TMPDIR:-/tmp}/ebx-connectors-install-<time>.log` (`EBX_INSTALL_LOG`). It does not start Tomcat.

When restarting from Eclipse, wait for the projects to finish rebuilding: a Tomcat started while
Eclipse rewrites `target/classes` may miss a module listener, and the module then starts without
registering (EBX waits with "not yet registered").

## REST API (REST Toolkit)

Base: `/ebx-connectors/rest/v1`, standard EBX authentication (Basic, token, session).

| Method and path | Role |
|---|---|
| `GET /connectors` | available types, configured connectors (and whether their secrets are set) |
| `POST /connectors/openapi?id=&url=` or JSON/YAML body | creates a `rest` connector from an OpenAPI 3 specification (`&replace=true`, `&dryRun=true`) |
| `GET /mappings[?table=/root/Companies]` | mappings |
| `DELETE /mappings/{id}` | a mapping with everything attached (rules, inputs, sub-objects, outbound states; provenance and conflicts unlinked) |
| `POST /sync/{mapping}?key=<pk>` | one mapping on one record (`mapping` = id or label); `?scope=unsent|changed|failed|dataspaceDelta&max=` for an outbound delta |
| `POST /enrich?dataset=&table=&key=&mappings=2,1,30` | an ordered chain of inbound mappings (`&connectors=GLEIF/lei,VIES` accepted) |
| `POST /replay/{runId}`, `GET /runs/{runId}` | replays the failed calls of a run; the calls of a run |
| `GET /outbound?mapping=&status=` | outbound state of the records of a mapping (SENT, FAILED, DELETED) |
| `GET /conflicts`, `POST /conflicts/{id}/accept|reject` | conflicts logged by the TRUSTED policy, and the steward's decision |
| `GET /config`, `POST /config?dryRun=` | configuration export and import (secrets never exported nor overwritten) |
| `POST /publish` | publishes a data product as a whole to a `TablePublisher` connector (Snowflake): one atomic commit for every table of one or more root outbound mappings, then (best effort) the business meaning: table and column comments, tags (`EBX_PRODUCT`, `EBX_GLOSSARY_TERM`…) and a semantic view `EBX_<PRODUCT>_SEMANTIC` carrying the glossary synonyms — see GUIDE § 10.2; 200 for OK/DRY_RUN, 422 for ERROR |
| `POST /webhook/{feed}` | import of a document (JSON, ndjson, csv) through a feed mapping; `GET` = health check |

Everything accepts `?dryRun=true` (simulation) and `?predicate=` instead of `key`. The webhook takes one
mode only: an EBX session (Basic, token or SSO) of a user who holds the RUN right, the import running
under that user. The shared-secret (`X-Webhook-Secret`) and HMAC-signature modes were removed (audit C2-10);
send the document from a technical EBX account.

```bash
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/enrich?dataset=Companies&table=/root/Companies&key=740&mappings=30,25"
```

## MCP tools for agents

`POST /ebx-connectors/rest/mcp` is an MCP server (streamable HTTP, JSON-RPC 2.0) with 23 tools:
list and describe connectors, call an operation, list, create, update and delete mappings, enrich a
record, synchronise or publish deltas, replay a run, read the log, arbitrate conflicts, export and
import the configuration, import an OpenAPI specification, declare the lineage of the mappings, stop a
running import, see how the data catalog knows a connector's system, and
publish a whole data product (one atomic commit for every table of one or more root outbound
mappings; GUIDE.md section 10.2). On EBX 6.3 the same server is also
registered with the native MCP runtime through its public API (`@McpServer` / `@McpTool`), served at
`/ebx-assistant/mcp/v1/ebx-connectors` once EBX accepts registrations from custom modules.
`-Debx.connectors.mcp=native|endpoint|both|none`. See GUIDE.md section 12.7.

## Computed value lists

Two fields of the configuration are programmatic enumerations (`osd:constraintEnumeration`),
filled at runtime rather than by a code table:

- `Connector/type` (`ConnectorTypeEnumeration`): the types registered in the module, with their
  label. A new type appears as soon as its class is registered (built-in class, `META-INF/services`
  extension, or `rest` for an API declared by properties): nothing to type by hand, and an unknown
  value is a validation error.
- `Mapping/operation` (`MappingOperationEnumeration`): the operations of the connector chosen on the
  same row, with their kind and description; the list is recomputed when `connector` changes. An
  unknown or inactive connector is only a warning, so that a mapping can be prepared in advance.

## Form of the Mapping table

`ebx/ui/MappingForm` (declared by `<recordForm>` in Connectors.xsd) replaces the default form: a
home tab with a **Design the mapping graphically** button (opens the `MappingDesigner` service on
the record, in preview), then two sections "What the mapping does" (label, connector, operation,
direction, active, description) and "Where it applies" (dataspace, dataset, table, response root);
tabs Inputs, Rules, Sub-objects and Import feed, each with its explanation and its association shown
as a table. On creation, the button and the associations are replaced by "save first". The fields are
read from the model: a field added to the XSD appears without recompilation. All the field
documentation (operation, direction, connector, dataset, active…) is carried by the model, so it is
also visible in the default form and in the contextual help.

## Form of the Connector table

The *Connection & configuration* tab shows which credentials to enter for the chosen authentication: the modes
the connector type offers as cards, a required / optional / derived / not used state and a contextual label on
each credential field ("API key / token" reads "Client secret" in OAuth 2.0, "Connection string" for Azure
Service Bus), what to enter, where to get it at the provider and what the module sends, and a warning when a
pasted secret exceeds the 255 characters of the field. Rules in `core/CredentialGuide`, texts in the
`credentials.*` keys of the bundles (GUIDE § 3.1).

## Workflow script task

The bean `com.on.ebx.connect.ebx.workflow.ConnectorScriptTaskBean` (declared in module.xml; declare
the step as a **library** task of module `ebx-connectors` so that the editor shows its parameters)
runs one or several mappings on a record or a table.

| Input | Role |
|---|---|
| `dataspace`, `dataset` | target (e.g. `Companies` / `Companies`) |
| `xpath` | one record: `/root/Companies[./Identification/Id=512]` |
| `tablePath`, `predicate`, `maxRecords` | or a whole table, filtered, capped (200) |
| `mappings` | ordered list of ids, labels or `connector/operation`: `37,38,1` — the unambiguous form |
| `connectors` | or an ordered list of connectors (all their inbound mappings of the table) |
| `connector`, `operation`, `direction` | or one mapping (historical form; refused when several mappings match) |
| `scope` | outbound scope: all, unsent, changed, failed, dataspaceDelta |
| `dryRun`, `failOnError` | simulation; put the step in error when a call fails (default: no) |

Outputs: `report` (one line per call), `changedCount`, `okCount`, `errorCount`, `conflictCount`,
`hasConflicts`, `runId`, `status` (OK, PARTIAL, ERROR, NO_DATA). On a record, the mappings run in
order and each one sees what the previous one wrote (Wikidata finds the LEI, GLEIF uses it).

Example workflow models: `ConnectorEnrichment` (script task, condition on `hasConflicts`, conflict
arbitration user task) and `DemoEnrichmentConflicts` (`tools/export/workflows/`). EBX loads the bean
class and reads its declaration at startup: any change to its properties needs a restart.

## OpenLineage and the data catalog

- Declare one connector of type **`openlineage`** (base URL = the sink's endpoint: the ON EBX data
  catalog's `/ebx-data-catalog/rest/v1/openlineage`, Marquez, DataHub) and **every outbound run** is
  described there as an OpenLineage `RunEvent`: the EBX table as input, the connector's object as
  output, one column-lineage entry per rule, the counts of the run (`core/OpenLineageEmitter`,
  GUIDE § 3.7). Inbound runs on request; simulations and empty runs send nothing; a refused event
  never fails the run.
- Every mapping is also described **as soon as it is declared**, before any run: the triggers of
  `Mapping` / `MappingInput` / `MappingRule` hand it to `core/DeclaredLineage`, which sends an
  OpenLineage `JobEvent` once the changes stop (a retirement when the mapping is deactivated, deleted
  or renamed), sweeps every mapping at startup, and answers `POST /v1/lineage/declare` and the MCP
  tool `connectors_lineage_declare`. The catalog shows the flow as `DECLARED`, then `OPENLINEAGE`
  after its first run (GUIDE § 3.7, sink property `emit.declared`).
- The **`ebx`** connector carries the catalog operations `catalog`, `profile`, `sample` and
  `lineage` (`connectors/EbxCatalog`, GUIDE § 3.8): an EBX instance — this one through its own Data
  Services — is inventoried, profiled and traced by the catalog like a database. Tables named in full
  in `tables` are read directly (3 calls to re-profile one table), and the reads of a whole-instance walk
  run in parallel (`-Debx.connectors.ebx.catalogThreads`, default 4).
- The run service (**Synchronise with an external application**) shows a result banner, a waiting strip
  and a **Back** button, sends one OpenLineage event per run, and switches to a guided layout when a link
  opens it with run-time inputs, as the catalog's **Re-profile this table…** does (GUIDE § 12.1).

## Development

No Maven: `javac --release 8` with the jars above, output in `target/classes`, served directly by
Tomcat. The designer front end (`www/…/connect/mapping-designer.{js,css}`) is served from the
sources: editable without restart. The `Connectors.xsd` model is edited by hand (`tools/build_xsd.py`
knows only 9 of its 27 tables: running it over the file would lose the others; `tools/schema_diff.py` compares two versions). `./build-spi.sh` produces `dist/ebx-connectors-spi.jar` (+ sources), the
contract to hand to a third-party developer.

`./build-agent.sh` produces `dist/on-ebx-agent.jar`: the `com.on.ebx.agent` package (the MCP client,
and the tool-use loop) on its own — pure JDK and Jackson, no `com.on.ebx.connect` class, no EBX
class, Java 8 bytecode — for a sibling module to embed in its own `WEB-INF/lib` (`ebx-mdm-factory`,
`tools/agent_jar.sh`).

`./build-mapping.sh` produces `dist/on-ebx-mapping.jar`: the **mapping engine** on its own
(`com.on.ebx.connect.core.MappingEngine` and its closure — 65 classes, see below), the same way and
for the same reason: a sibling module runs Connectors' own rule-pipeline transformations against its
own documents/records without depending on Connectors at runtime, without a second implementation of
`trim`/`date`/`map`/... to keep in sync. Consumer: **`ebx-mdm-factory`**, which turns a landing row
into a target-model staging row by compiling one `AttributeMapping.pipeline` per field into a
`MappingRule` and calling `MappingEngine.compute(...)` once per row per feed
(`tools/mapping_jar.sh`, twin of `agent_jar.sh`; see `docs/TARGET-MAPPING.md` in that repository for
the full design). Both scripts fail the build if the compiled closure needs one class more than the
list below — that is deliberate: it is the guarantee that the jar stays a pure engine, with nothing
of Connectors' EBX integration (HTTP, secrets, the connector registry, `ConnectorService`...) able to
leak in unnoticed.

### Mapping engine jar (`on-ebx-mapping.jar`)

Classes: `MappingEngine`, `MappingDefinition`, `MappingRule`, `MappingResult`, `Transformations`,
`Transformation`, `TransformationContext`, `RunIds`, `Json`, `Xpath`, `SampleValueSafety`,
`SyncResult`, `TrustOracle`, `Hashing`, `GoldenRecords`, `MameGoldenRecords`, `Expressions` (all
`core`), `ebx.DatasetLocator`, `spi.SynchronizationStrategy`. `Expressions` and `DatasetLocator` are
not obvious from `MappingEngine`'s own imports (`MappingDefinition`'s constructor calls
`Expressions.isBlank`; `Transformations.table()`, behind `fk`/`lookup`/`golden`/`mdmState`, calls
`DatasetLocator.find` to resolve an explicit dataspace/dataset argument) — `build-mapping.sh`'s
comment header explains how they were found (by compiling, not by re-reading the source a second
time). `MameGoldenRecords` needs `ebx-addons.jar` (Match and Merge) on the *compile* classpath
unconditionally — unlike its runtime `LinkageError` fallback (`GoldenRecords`), javac cannot compile
a reference to `com.orchestranetworks.addon.mame.*` without the jar merely because the add-on might
be absent at runtime.

`Transformations.apply(pipeline, value, context)` runs a `|`-separated pipeline of named steps, each
optionally taking `(arg1,arg2,...)`. An argument is normally unquoted text; wrap it in a single
matching pair of `'...'` only when it must itself contain a literal `,` (arguments are comma-split
outside quotes) — a `SimpleDateFormat` pattern with a quoted literal such as `'T'` does **not** need
outer quoting, because it has no comma: `date(dd/MM/yyyy HH:mm:ss,yyyy-MM-dd'T'HH:mm:ss)` is passed
through exactly as written (verified against the parser's own `splitArgs`/`unquote`, 2026-09-23). A
regex quantifier such as `{2,4}` DOES need it: `regex('\d{2,4}')`. All registered names, exact
syntax as read in `core/Transformations.java` (37 since 2026-10-23: `epochdate` and `joinwidth` added for the Factory's B2B test):

| name | arguments | behaviour | example |
|---|---|---|---|
| `trim` | none | Java `String.trim()` | `trim` on `" Foo "` → `"Foo"` |
| `upper` | none | `toUpperCase(Locale.ROOT)` | `upper` on `"foo"` → `"FOO"` |
| `lower` | none | `toLowerCase(Locale.ROOT)` | `lower` on `"FOO"` → `"foo"` |
| `capitalize` | none | lower-cases, then upper-cases the first letter of the string and of every run after a space, `-` or `'` | `capitalize` on `"jean-paul o'brien"` → `"Jean-Paul O'Brien"` |
| `left` | `(n)` | first `n` characters, or the whole string if shorter | `left(3)` on `"Hello"` → `"Hel"` |
| `right` | `(n)` | last `n` characters, or the whole string if shorter | `right(3)` on `"Hello"` → `"llo"` |
| `substring` | `(start[,end])` | 0-based, `end` exclusive, both clamped to `[0,length]`; `end` defaults to the string's length | `substring(2)` on `"Hello"` → `"llo"`; `substring(0,2)` → `"He"` |
| `replace` | `(old,new)` | literal (non-regex) `String.replace` | `replace(-,)` on `"2024-01-15"` → `"20240115"` |
| `regex` | `(pattern[,group])` | first `Matcher.find()` match; `group` defaults to `1` when the pattern has a capturing group, else `0` (the whole match); no match → `null`. Quote the pattern when it contains a literal `,` (e.g. a `{m,n}` quantifier) — see the quoting rule above the table | `regex('[0-9]+')` on `"REF-123-A"` → `"123"`; `regex('([A-Z]+)-(\d+)',2)` on `"ABC-123"` → `"123"`; `regex('\d{2,4}')` on `"AB1234CD"` → `"1234"` |
| `vatfr` | none | 9-digit SIREN (or first 9 digits of a 14-digit SIRET) → French VAT: `"FR" + key + siren`, `key = (12 + 3*(siren mod 97)) mod 97`; anything else, unchanged | `vatfr` on `"552100554"` → `"FR96552100554"` |
| `digits` | none | strips everything but `0`-`9` | `digits` on `"+33 (0)1 23"` → `"330123"` |
| `prefix` | `(text)` | prepends `text` | `prefix(FR-)` on `"123"` → `"FR-123"` |
| `suffix` | `(text)` | appends `text` | `suffix(-END)` on `"123"` → `"123-END"` |
| `default` | `(value)` | `value` when the input is null, blank, or an empty list; the input otherwise | `default(N/A)` on `""` → `"N/A"` |
| `constant` | `(value)` | always `value`, input ignored | `constant(ACTIVE)` → `"ACTIVE"` |
| `nullifempty` | none | `null` when blank/empty-list, else unchanged | `nullifempty` on `""` → `null` |
| `nullif` | `(value)` | `null` when the input's text equals `value`, else unchanged | `nullif(N/A)` on `"N/A"` → `null` |
| `isodate` | none | **first 10 characters** of the text, or `null` if shorter than 10 — a substring, not a real date parse | `isodate` on `"2024-01-15T10:00:00"` → `"2024-01-15"` |
| `isodatetime` | none | **first 19 characters**, space at index 10 turned into `T`, or `null` if shorter than 19 — a substring, not a real date parse | `isodatetime` on `"2024-01-15 10:00:00.000"` → `"2024-01-15T10:00:00"` |
| `date` | `(inPattern='dd/MM/yyyy'[,outPattern='yyyy-MM-dd'])` | strict (`setLenient(false)`) `SimpleDateFormat` parse with `inPattern`, reformat with `outPattern` | `date` on `"15/01/2024"` → `"2024-01-15"`; `date(dd/MM/yyyy HH:mm:ss,yyyy-MM-dd'T'HH:mm:ss)` on `"15/01/2024 10:30:00"` → `"2024-01-15T10:30:00"` |
| `epochdate` | `([outPattern='yyyy-MM-dd'])` | an OData v2 JSON date `/Date(ms)/`, `/Date(ms+hhmm)/`, `/Date(ms-hhmm)/` or bare epoch milliseconds, formatted in **UTC** with `outPattern`; the offset is ignored (the milliseconds are already UTC); blank → `null`; any other text throws (the row is refused, like `date`) | `epochdate` on `"/Date(1598227200000)/"` → `"2020-08-24"`; `epochdate(yyyy-MM-dd'T'HH:mm:ss)` → `"2020-08-24T00:00:00"` |
| `map` | `(K1=V1;K2=V2;...[;*=fallback])` | pairs separated by `;` inside one argument, `key` matched **case-insensitively** against the trimmed input text; `*` is the fallback key; no match and no `*` → `null` | `map(PME=MEDIUM;ETI=LARGE;GE=VERY_LARGE;*=UNKNOWN)` on `"eti"` → `"LARGE"` |
| `join` | `([separator=", "])` | joins a `List` with `separator`, skipping null/empty elements; non-list input passes through unchanged; empty result → `null`. **An unquoted argument is trimmed** (leading/trailing whitespace lost): `join(; )` on `["A","","B"]` → `"A;B"`, not `"A; B"` — quote it (`join('; ')`) to keep the space. A literal comma separator is written unquoted as `join(,)` (the parser re-glues the two empty pieces the split produces) | `join('; ')` on `["A","","B"]` → `"A; B"`; `join(,)` on `["A","B"]` → `"A,B"` |
| `first` | none | first element of a `List`, or the input unchanged if it is not a `List` | `first` on `["A","B"]` → `"A"` |
| `count` | none | size of a `List`; `0` for `null`; `1` for any other single value | `count` on `["A","B"]` → `2` |
| `boolean` | none | `null` when blank; else `true` for (case-insensitive, trimmed) `true`, `1`, `yes`, `oui`, `y`, `o`, `active`; `false` for anything else, `non`/`no`/`false`/`0` included — see `TransformationsTest.testBooleanAcceptsFrenchAndSingleLetterWords` | `boolean` on `"Oui"` → `true`; `boolean` on `"Non"` → `false` |
| `number` | none | trims, `,`→`.`, strips everything but `0`-`9`/`.`/`-`, then `new BigDecimal(...)`; empty/`-`/`.` after stripping → `null` | `number` on `"1 234,56 €"` → `1234.56` |
| `round` | `([scale=0])` | `BigDecimal.setScale(scale, ROUND_HALF_UP)`; the input must already be, or parse as, a `BigDecimal` | `number\|round(2)` on `"3,14159"` → `3.14` |
| `fk` | `(tablePath[,dataspace,dataset])` | keeps the value only if a record with that formatted primary key exists in the resolved table, else `null`; throws if the table cannot be resolved | `fk(/root/LegalForm)` on an unknown key → `null` |
| `lookup` | `(tablePath,fieldXPath,resultXPath[,dataspace,dataset])` | first record where `fieldXPath` equals the (quoted) input; returns `resultXPath`'s formatted value, or the record's own formatted primary key when `resultXPath` is empty; no match → `null` | `lookup(/root/Country,./Alpha3Code,./Alpha2Code)` on `"FRA"` → `"FR"` |
| `golden` | `(tablePath[,dataspace,dataset])` | the record's golden record's formatted primary key under Match and Merge (follows `MERGED` chains, bound 10 hops); unmanaged/not found → the key unchanged | `golden(/root/Company)` on a merged key → the surviving key |
| `mdmstate` | `(tablePath[,dataspace,dataset])` | the record's Match and Merge state name (`GOLDEN`, `MERGED`, `UNSET`, `PENDING_APPROVAL`, `DELETED`, or `UNMANAGED`); `null` if the record is not found | `mdmstate(/root/Company)` → `"GOLDEN"` |
| `hash` | none | SHA-256 hex digest of the text-coerced value; ignores its own arguments | `hash` on `"Alpha"` → a 64-hex-character string |
| `now` | `([pattern], default: yyyy-MM-dd'T'HH:mm:ss)` | current server time, formatted with `SimpleDateFormat(pattern)`; ignores the input value | `now` → `"2026-09-23T14:05:11"`; `now(yyyy)` → `"2026"` |
| `runid` | none | the id of the run in progress on this thread (see `RunIds`), or a fresh one memoized for the rest of it; ignores the input value | `runid` → `"R20260923-140511-00042"` |
| `concat` | `(path1,path2,...)` | joins the value at each dotted path of the pipeline's document root (`Json.at`, same resolution a rule's `sourcePath` uses) with a fixed `\|` separator; a missing/null piece contributes `""` | `concat(id,name)` on `{"id":1,"name":"Alpha"}` → `"1\|Alpha"` |
| `joinwidth` | `(width,path1,path2,...)` | rebuilds one value its source cut into fixed-width pieces (SAP `OrganizationBPName1`/`OrganizationBPName2`, 40 characters): pieces read like `concat`'s; a piece of the full `width` is followed directly by the next (cut inside a word), a shorter one by one space (its space was dropped at the cut); blank pieces skipped; nothing → `null` | `joinwidth(40,n1,n2)` on `{"n1":"Compagnie Générale de Chimie Druarimar E","n2":"urope SAS"}` → `"Compagnie Générale de Chimie Druarimar Europe SAS"` |

`fk`/`lookup`/`golden`/`mdmstate` resolve their table against `TransformationContext.getDataset()`
(the dataset being written) unless `dataspace`/`dataset` are given; give them explicitly for a
reference table that lives elsewhere (`fk(/root/Country,,Geographies)`) — see
`Transformations.table()`. Registering a new name at runtime (`Transformations.register(...)`) is
supported but out of scope for a jar meant to be a closed, reviewed set.

## The customer's model

`-Debx.connectors.seed=none` makes the installer create no connector, mapping or catalogue card (production); by default it creates each demonstration item once and never again after you delete it.
`-Debx.connectors.seedMappings=auto|always|never`: in `auto` (default), the demonstration mappings
are only seeded when `/root/Companies` exists, with the sole rules whose fields exist. Three
validation constraints (warnings) then flag any mapping, rule or input that does not resolve in the
model.

## Extending with a jar (without the sources)

A third-party connector (a class implementing `com.on.ebx.connect.spi.Connector` plus the file
`META-INF/services/com.on.ebx.connect.spi.Connector`) is dropped as a jar in `WEB-INF/lib/`. A secret
resolver for another vault follows the same pattern with `SecretResolver`. Complete example: the
`../ebx-connectors-extension-sample` project (Nominatim), already deployed here.

## Security

**On from the first start**: secrets stored with EBX's reversible encryption or referenced from a vault;
generic OAuth 2.0 (client credentials, password, refresh token, JWT bearer), mutual TLS and private CAs
per connector; webhooks that need an EBX session with the RUN right; secrets masked in every message the module keeps or returns; three rights
(viewer, operator, designer) that open the MCP tools, the REST routes and the user services, given by the
administrator to existing roles in the "Access rights" table and closed to everyone else; a cross-site
request guard on every POST, PUT, PATCH and DELETE of the REST API; one kernel-log line for every call that
runs or changes something; redirects re-checked hop by hop, and cloud metadata addresses refused; the SFTP
host key verified.

**What each protection does without its property** (the module starts and works without them; the first one is logged at
startup). Only the allow-list is off by default:

| Protection | Without the property | Property |
|---|---|---|
| Outbound host allow-list (optional) | every public host is reachable | `-Debx.connectors.allowedHosts=<hosts, or file:<path>>`; `-Debx.connectors.egress.requireAllowList=true` refuses every call until it is set |
| Block of private hosts | **on by default**: loopback, private and unresolvable hosts are refused | exceptions: `-Debx.connectors.egress.privateExceptions=<host, host:port, CIDR, or file:<path>>`; `-Debx.connectors.egress.blockPrivate=false` switches it off |
| Retention | **on by default**: sync log lines, decided conflicts, finished runs and publication reports older than 90 days are deleted by a background task (open conflicts and running runs never) | `-Debx.connectors.log.retentionDays=<days>`, `0` turns it off |
| Refusal of clear secrets | **on by default**: a secret stored in clear is refused at use | `-Debx.connectors.secrets.allowClear=true` accepts them again; `GET /v1/status` lists the connections concerned |

`GET /ebx-connectors/rest/v1/status` (right READ) reports which of these are on, whether the module is
installed, and how many runs are running. GUIDE.md sections 3.1.1 to 3.1.3.

## Outbound deltas and replay

Scopes `unsent`, `changed`, `failed`, `dataspaceDelta` for outbound mappings (table `OutboundState`:
fingerprint of the last document sent, failures, deletions through `deleteOperation`, retry policy);
every run has a `runId`, `POST /replay/{runId}` replays its failures. GUIDE.md section 10.1.

## Publishing a data product

Where the deltas above keep one mapping's target continuously in sync, `connectors_publish` /
`POST /publish` / the workflow bean `ProductPublishTaskBean` send **one or more root outbound
mappings, all on the same connector**, as a whole product against a contract: one atomic commit for
every target table (v1 scope: Snowflake only, full reload only — no incremental publication, no
snapshot isolation of the source read). Each mapping's input `table` names its target table; its
rules and active children become the table's columns (a nested rule or a child mapping becomes a
`VARIANT`/JSON column). Every call, dry runs included, is recorded in the `Publication` table by
`runId`, with the full report as JSON. GUIDE.md section 10.2.

## Conflicts (TRUSTED policy)

Proposals refused because the source is less trusted than the one that wrote the value go to the
`Conflict` table. A steward arbitrates them with the "Manage connector conflicts" user service
(dataspace, table, record, the Conflict table itself, or the workflow user task
`ebx-connectors@ConflictManager`), in REST (`GET /conflicts`, `POST /conflicts/{id}/accept|reject`)
or through the MCP tools. REST answers and the workflow bean report conflicts (`hasConflicts`,
`conflictCount`). GUIDE.md section 4.5.
