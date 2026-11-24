# Read recipe + deduced scan (lot 27, 2026-09-24)

## What and why

The MDM Factory's landing must be able to build a Connectors inbound feed for ANY catalogued table,
without any per-connector-type code in the Factory itself. The contract (shared, fixed by the pilot
across three agents working in parallel — ebx-data-catalog, ebx-connectors, ebx-mdm-factory): every
official catalog operation writes, for each table/object it finds, a **read recipe** — a small JSON
object describing exactly how to read every row of that table through this connector, using an
operation the Factory refuses to call unless it is provably `readOnly`.


## What this repo does

### 1. `readOnly` on every operation (`connectors_describe`)

`com.on.ebx.connect.spi.Operation` gained an optional `httpMethod` (6-arg constructor; the existing
5-arg one still works, `httpMethod = null`) and one derivation method, `isReadOnly()` — the single
place `readOnly` is computed:

- `Kind.WRITE` → never read-only, whatever the method.
- an HTTP method is known (only `GenericRestConnector`, the one connector that is itself
  configuration-driven with `op.<name>.method`) → read-only only for `GET`/`HEAD`.
- otherwise (JDBC, SOAP, GraphQL, Salesforce, SQL-family statement APIs, EBX...) → `Kind.READ`/`SEARCH`
  is read-only, since these connectors' `Kind` already means "cannot change the source".

`ConnectorsTools.describe()` (the body of `connectors_describe`, shared by the HTTP endpoint and the
in-JVM bridge — both go through `McpEndpoint.dispatch` → the same `ConnectorsTools` instance) now puts
`"readOnly": op.isReadOnly()` on every entry of `operations[]`. No other JSON-building site exists for
this tool, so both transports get it automatically.

`GenericRestConnector.getOperations()` now passes its own `method` into the new constructor. This also
fixes a latent classification gap: an unconfigured `DELETE` operation defaults to `Kind.READ` there
(nothing in that heuristic accounts for `DELETE`), which would have read as `readOnly: true` without
the method signal. With the method known, `isReadOnly()` correctly says `false`.

Tests: `spi/OperationReadOnlyTest` (the derivation itself, every combination),
`connectors/GenericRestConnectorReadOnlyTest` (the DELETE gap, a POST-transported SEARCH, a plain GET).
Both registered in `build-test.sh` next to `CatalogOperationsTest`.

### 2. `readRecipe`/`scanOrigin`/`scanState` on every official catalog operation

`CatalogOperations.readRecipe(connectorId, operation, inputs, itemsPath)` (new, in `core/CatalogOperations.java`)
builds the JSON text once, shared by every writer below, so the shape never drifts:
`{"connector","operation","inputs":{...},"itemsPath"}`.

| Family | Writer | Recipe |
|---|---|---|
| JDBC (+ SparkSql, which extends it) | `JdbcConnector.catalog()` | `query` op, `sql = SELECT <quoted cols> FROM <quoted qualified name>` (driver's own `quote()`), `itemsPath = rows` |
| Snowflake, Databricks | `CatalogOperations.catalog(..., connectorId)` (new 5-arg overload; the old 4-arg one delegates with `null` — no recipe, used internally by `profile()`/`sample()` for the table list only) | same shape, `Dialect.quote()` | 
| Salesforce | `SalesforceCatalog.catalog()` (constructor now takes the connector id) | `query` op, `soql = SELECT <fields> FROM <sObject>`, compound `address`/`location` fields excluded (they fail a SOQL SELECT with their parts present), `itemsPath = records` |
| File connectors (`AbstractFileConnector` + `SampleFileConnector`) | `catalog()` | `read` op, `inputs = {path}`, `itemsPath = rows` |
| EBX | `EbxCatalog.describe()` (constructor now takes the connector id) | `search` op, `inputs = {dataspace, dataset, table}` (bare dataspace/dataset — `search`'s own `tablePath()` adds the `B`/`V` prefix itself), `itemsPath = rows` |

Every writer also sets `scanOrigin = OFFICIAL`, `scanState = CONFIRMED` on the same table document.
A connector whose catalog cannot build a recipe (SharePoint/OneDrive/GoogleDrive via
`SharedDriveCatalog`, Power BI, Kafka, Alation — not named in the contract's enumerated list of
official families) leaves the field empty, on purpose: the Factory then reports "no recipe" rather
than guessing.

Tests updated/added: `core/CatalogOperationsTest` (Snowflake/Databricks, both the no-id/no-recipe path
and the id/recipe path), `connectors/SalesforceCatalogTest` (recipe shape, compound field excluded),
`connectors/EbxCatalogTest` (recipe shape, bare dataspace/dataset). `JdbcConnector`/`AbstractFileConnector`
have no dedicated offline catalog test in this repo (their catalog methods talk to a real
`Connection`/filesystem); not exercised beyond code review — see "Not verified" below.

### 3. Salesforce `query` follows `nextRecordsUrl`

`SalesforceConnector.execute()`'s `"query"` branch used to hand back only the first page (Salesforce
caps a SOQL result at 2000 records with `done=false` + `nextRecordsUrl`). Since a table's read recipe
is nothing more than this same operation with a full-field SOQL, every caller of `query` — not only
future landing feeds — now reads every row: new `followQuery()` loops on `nextRecordsUrl` (stripping
the redundant `/services/data/{version}` prefix, since `baseUrl()` already adds it) and merges every
page's `records[]`. `SalesforceCatalog`'s own internal `latest()` (used for `profile`/`sample`) is
unaffected — it always caps its `LIMIT` at ≤2000, so it never needed pagination.

## Verified how

- `python3 tools/test_scaffold_connector.py`, `python3 tools/generate_connector_parity.py --check`,
  `python3 tools/generate_operational_readiness.py --check`, `python3 tools/verify_guide_index.py`:
  all pass (offline, no EBX jars needed) — confirms nothing in this lot broke the doc/coverage
  generators.
- **Not run**: `build-test.sh`'s Java compile/test/WAR steps. This sandbox has no
  `EBX_LIB_DIR` (`ebx-lib.jar`/`ebx-addons.jar`) available, and the task explicitly forbids touching
  the Eclipse workspace or any real server project. Every Java edit was instead verified by full
  manual re-read against the surrounding code's existing patterns (method signatures, exception
  contracts, `Json`/`CatalogOperations` helper usage) rather than by compiling. **Run
  `./build-test.sh` (or `EBX_LIB_DIR=... ./ebx-connectors/build-test.sh`) before merging** — this is
  the single biggest gap in this lot's verification.
- Not run live against a real EBX instance, Salesforce org, Snowflake/Databricks account, or file
  drive: no write tools were used per the task's constraints (read-only `data-access`,
  `connectors_describe`, `connectors_mappings_list`, `connectors_mapping_get` only).

## Guesses / judgment calls worth a second look

- `Operation.isReadOnly()` treats a `Kind.SEARCH` operation transported over a known non-GET/HEAD
  method (e.g. a POST-based search endpoint) as **not** read-only, even though it may well be
  non-mutating in practice (GraphQL-style query-over-POST). This follows the contract's literal
  wording ("an HTTP GET/HEAD REST operation") over a looser reading — but only `GenericRestConnector`
  is affected today (it is the only connector with a declared HTTP method per operation); GraphQL and
  SOAP declare their write/read intent directly via config, not via `Kind` defaulted from a method, so
  they are unaffected by this choice.
- The Salesforce recipe excludes only `address`/`location` fields (the contract's literal list), not
  `base64`/`complexvalue` (excluded from *profiling* by `SalesforceCatalog.UNPROFILED` but not
  mentioned in the recipe instruction). A `base64`/`complexvalue` field, if present, stays in the
  recipe's SOQL — unverified whether that fails at query time on a real org.
