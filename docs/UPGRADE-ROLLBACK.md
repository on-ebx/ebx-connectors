# EBX-Connectors upgrade, compatibility, and rollback

This runbook says what the code really does when a new build replaces an old one, and what to do before and
after. It does not replace a repository backup, and nothing in it has been rehearsed on a customer repository: the
two upgrade paths it describes (a new table or field, a changed primary key) were read in the model history and in
EBX's own documentation, not exercised end to end. Where that matters it says "not verified".

## From 1.3.1 to 1.4.0

This section is the one to read first for 1.4.0. Each statement says how it is known: **measured** (a command run on
the source tree, quoted), **read** (in the code of the two versions), or **not tried** (inferred, never run). **Nothing
in it was rehearsed**: no upgrade from 1.3.1 and no rollback to it has been run on a copy of a repository, and 1.4.0
has been installed nowhere but on the owner's test instance. Section "What has never been repeated" at the end of this
one lists what is missing; do the rehearsal (section "Before an upgrade", step 6) before trusting the rest.

### What changes

**The data model** (measured, `tools/schema_diff.py --git v1.3.1 HEAD`, exit 0): `field added: /root/FieldProvenance/source`,
`field added: /root/OutboundQueue/publish`, "2 additive change(s), 0 data-affecting change(s)". Both fields are optional
strings. No table is added or removed (27 tables in both versions) and no key changes. By the rule of the table above,
EBX creates the fields at the first start and leaves the existing rows with an empty value; no data is lost. The model and
`module.xml` are read at start: **restart Tomcat** after replacing the war. `FieldProvenance.source` holds where a value
was read (document feeds only); `OutboundQueue.publish` holds `golden` when a trigger asked for golden records.

**JVM properties** (read; all are `-D` options, none is read from `ebx.properties`):

| Property | Default in 1.4.0 | Meaning |
|---|---|---|
| `ebx.connectors.preview` | unset: everything closed | Comma-separated list of `documents`, `cdcMatching`, `redshift`; opens the three preview functions. Exact spelling; a misspelt word opens nothing and is logged once. |
| `ebx.connectors.mcp.maxBodyBytes` | `4194304` (4 MiB) | Largest body of `POST /rest/mcp` and of the in-JVM bridges. |
| `ebx.connectors.mcp.maxBatch` | `20` | Messages in one JSON-RPC batch. |
| `ebx.connectors.mcp.maxJsonDepth` | `64` | Nesting depth of a message. |
| `ebx.connectors.rest.maxBodyBytes` | `33554432` (32 MiB) | Largest body of the REST routes that take a document (webhook, `/config`, `/publish`, `/connectors/openapi`, `/templates/import`). |
| `ebx.connectors.rest.maxJsonDepth` | `64` | Nesting depth of a body on those routes. |
| `ebx.connectors.documents.roots` | unset: no server folder is read | Folders a `documents` connection may read. Only relevant when `documents` is open. |
| `ebx.connectors.secrets.dir` (existing, new meaning) | unset | Also the only directory a Google `serviceAccountFile` may name. Unset: `serviceAccountFile` is refused. |

A value that is not a positive number reads as the default. A property unknown to 1.3.1 is ignored by it.

**Behaviour that an existing instance can notice** (read, in the code of both versions):

| What | 1.3.1 | 1.4.0 | Check before the first start |
|---|---|---|---|
| `documents`, `cdcMatching`, `redshift` | do not exist | exist and are CLOSED: a run ends with an error starting `[CN-PREVIEW-CLOSED]` | Does the instance use one? Then set `ebx.connectors.preview` first (below). |
| A body over a bound | read whole, parsed | refused, `413 CN_BODY_TOO_LARGE`, `400 CN_BATCH_TOO_LONG` or `400 CN_JSON_TOO_DEEP`, naming the property | Largest legitimate MCP call (a large `connectors_config_import`) and REST import against 4 MiB and 32 MiB. |
| A session with no Connectors right on `POST /rest/mcp` | answered, tools hidden or refused one by one | `403` before the body is read | The accounts that MCP clients use: each needs the READ right (table `AccessRight`). |
| Google `serviceAccountFile` | any path of the server | only inside `ebx.connectors.secrets.dir` | List the `ConnectorProperty` rows whose key is `serviceAccountFile`. |
| Run-time inputs of a `cdc` feed | replaced the mapping's inputs | only `maxEvents` and `maxBytes`, only downwards; others dropped and named in the run message | Scheduled tasks and workflow steps that pass `deleteMode`, `deleteFlagField`, `matching`... |
| `privateExceptions` / `allowedHosts` | read silently | read the same way, and the kernel log and `/v1/status` say what was read | A value without `file:` before a path is read as ONE entry that matches nothing: add the prefix. |
| Services of the module in the Actions menu | side by side | one submenu | Nothing. |
| Jackson and the SLF4J API | taken from the server `lib/` (a FastTrack `lib/` has them; a standard ON EBX `lib/` does not, and the war was dead there) | carried by the war: `jackson-core`, `-databind`, `-annotations`, `-datatype-jsr310` 2.14.2 and `slf4j-api` 1.7.32 (no binding) in `WEB-INF/lib` | On a server whose `lib/` also holds Jackson or SLF4J, nothing: the web application loader reads `WEB-INF/lib` first (read, not rehearsed). An SLF4J notice on the standard error at the first use, and no driver log, unless the server has a 1.7 binding. See `ebx-connectors/docs/OPERATIONS.md` section 1.1. |

### Before the first start of 1.4.0

1. **Decide each preview function for this instance** and put the words in the JVM options of every launcher that starts
   it (`ebx.sh` AND an Eclipse launch configuration, if both exist: a start from a launcher without the option refuses
   the function):
   - a `documents` connection exists or documents are read from DAMA: `documents`. The existing row keeps its
     `llm.connector`; a new instance seeds the connection without a language model. Know that the PDF reader of this
     function is not hardened (release notes, known issue): open it only where nobody untrusted can run a documents
     flow. To accept uploads of up to 50 MB through MCP, `mcp.maxBodyBytes` must be raised to 70 MiB (`73400320`) or more.
   - a `cdc` mapping carries `matching=afterApply` or `matching=off`, or a `ConnectorTableTrigger` carries `publish=golden`:
     `cdcMatching`. Without it such a mapping is refused when saved and when run, and queued `publish=golden` rows stay in
     `OutboundQueue` (they are set aside after their attempts and sent again once the function is open and the row is retried).
   - a `redshift` connection exists: `redshift`, and the Amazon driver (`redshift-jdbc42`) must be in the module's
     `WEB-INF/lib`. A stored cluster-identifier URL (`jdbc:redshift:iam://my-cluster:eu-west-1/db`) fails at its next use whatever
     is open: rewrite it as the endpoint form.
   Example: `-Debx.connectors.preview=documents,cdcMatching,redshift` (only the words actually in use).
2. **Google connections.** `serviceAccountFile` needs `-Debx.connectors.secrets.dir=<folder>` and the key file inside it
   (a relative name is resolved there); otherwise switch the connection to `serviceAccountJson` or a secret reference.
3. **MCP clients.** Each account an MCP client uses holds at least the READ right; an account with none gets 403 at connection.
4. **Bounds.** Keep the defaults unless a legitimate call exceeds them; every caller with a Connectors right can make the server
   buffer up to the bound per request.
5. **Egress lists.** If `privateExceptions` or `allowedHosts` names a file, the value starts with `file:`. A local source (H2,
   Kafka, Ollama, EBX itself) works only through that list.
6. **Capture feeds.** Note which `cdc` mappings exist (table `Mapping`, operation `cdc`) and their `SynchronizationCheckpoint`
   rows (the resume positions): they are the state a rollback must not lose.
7. **FastTrack workspace.** `tools/install.sh` keeps the workspace's own `src/main/webapp/WEB-INF/lib` and does not copy the new Jackson and SLF4J jars into it: that
   instance keeps using the Jackson of its server `lib/`, as before. Nothing to do; the new jars matter for a standard ON EBX server, where the war is deployed as built.
8. The usual steps of "Before an upgrade" below: backup 1, snapshots, schedules stopped, rehearsal on a copy.

### After the install: what to check

`tools/smoke.sh` (with `EXPECT_VERSION=1.4.0`) does not read the new blocks. Read `GET /ebx-connectors/rest/v1/status` as
an administrator:

| Field | Expected |
|---|---|
| `version.version` | `1.4.0` |
| `preview.open`, `preview.closed`, `preview.unknownWords` | `open` is exactly the words you set; `unknownWords` is empty (otherwise a word is misspelt and opened nothing) |
| `requestLimits` | the five bounds in force (`mcpMaxBodyBytes`, `mcpMaxBatch`, `mcpMaxJsonDepth`, `restMaxBodyBytes`, `restMaxJsonDepth`) |
| `egress.privateExceptionsList`, `egress.allowListSource` | `source` is `file` or `value` as intended, `entries` as counted, `ignored` 0, no `warning`, `fileReadable` true for a `file:` list |
| `outboundQueue` | `known` true; `dead` 0 (a row set aside needs an administrator: delete it, or set it back to `PENDING` with `attempts` 0); `oldestPendingAgeSeconds` small |
| `cdc` | one entry in `flows` per `cdc` mapping; `checkpointSaved` true and `savedAtAgeSeconds` plausible for a flow that ran before the upgrade; the checkpoint rows were not lost |

In the kernel log, at the start: one `[connectors] ebx.connectors.egress.privateExceptions: N entries read ...` line per list
that is set, and a WARN `names [...], which is not a preview function` for a misspelt preview word. `outboundQueue` counts by
predicate; its age is the first waiting row in key order. `cdc` does not give the last failure of a flow (that needs a walk of
`ExecutionRun`): read it with `connectors_run_get`.

### Rolling back from 1.4.0 to 1.3.1

The rollback is the one of section "Rollback" below: stop the schedules and Tomcat, restore the pre-upgrade repository backup,
put the 1.3.1 war back whole with its libraries, delete the exploded directory. Nothing else is needed. **Putting the 1.3.1 war
over a repository that 1.4.0 has opened is not a rollback.** What 1.3.1 then does with what 1.4.0 left (read in the code of
1.3.1; none of this was tried):

| What 1.4.0 left | What 1.3.1 does | Do first, on 1.4.0 |
|---|---|---|
| Values of `FieldProvenance.source` and `OutboundQueue.publish` | the model of 1.3.1 has no such fields; by the EBX rule above a value is dropped at the next update of each record. The code of 1.3.1 does not read them. | Nothing needed. |
| `OutboundQueue` rows with `publish` = `golden` | 1.3.1 ignores `publish` and sends the record itself: a MERGED record would be sent in place of its golden record | Let them drain, or delete them |
| A customer model whose `ConnectorTableTrigger` declares `<publish>` or `<skipOrigin>` | the trigger class of 1.3.1 has no such properties; EBX may refuse the trigger and so the model (not tried) | Remove both parameters from the trigger before the rollback |
| `cdc` mappings and their `SynchronizationCheckpoint` rows (strategy `VENDOR_CURSOR`) | the kafka connector of 1.3.1 has no `cdc` operation: the mapping fails at each run with "has no operation 'cdc'"; the rows are not touched | Deactivate the `cdc` mappings and their schedules. Reinstalling 1.4.0 resumes at the saved positions. |
| Conflicts whose field path starts with `dama:` (proposals of the `annotate` operation) | read as a record field; accepting one is refused or fails | Decide or reject them in the conflict manager |
| Connections of type `documents` or `redshift` (seeded rows included) | the types are not registered: the rows stay, unusable, and are expected to be reported by the dataset validation (not tried) | Nothing; note them |
| A Google connection moved to a relative `serviceAccountFile` | 1.3.1 reads the name as a path of the server | Put back the absolute path, or use `serviceAccountJson` |
| New JVM properties | ignored | Nothing |
| Rows of the `Release*` tables for 1.4.0 | stay (packaged content is additive) | Nothing |

### What has never been repeated

- **The upgrade itself.** 1.3.1 to 1.4.0 and back on a copy of a repository: never. The only measure is the offline schema
  comparison above.
- **An install outside the owner's workstation.** The package of `tools/package.sh` was not built for 1.4.0 (no tag yet). The war as built before the
  X3-07 change was put by the audit on an instance built from the standard distribution only (6.3.0-gp, `lib/` = `ebx.jar`, a driver, `jakarta.mail`): dead, for want of Jackson. With
  Jackson added by hand it started clean in 27 s (measured by the audit, 2026-11-12). The war that carries Jackson and SLF4J itself was checked offline (the self-sufficiency check of
  `OPERATIONS.md` section 1.1; a JVM with only `ebx.jar` and the servlet API registers 96 connector types) but **not yet deployed there**, nor with the Match and Merge or DAMA add-on absent
  on such an instance (the module is designed to start without them: it catches the missing classes; not tried).
- **The preview functions on an instance.** `documents`: scenarios A1 to A3 passed on the test instance (36 checks, real language
  model); the DAMA write-back and the vision scenarios (A4, A5) are only partly confirmed (vision with one OpenAI model).
  `cdcMatching`: scenario B8 was never run on an instance. `redshift`, SAP OData, Workday and BigQuery: never run against a tenant.
  The capture feed itself ran against a live Kafka broker with Debezium-format events (17 checks), never against a real
  Debezium connector.
- **`tools/upgrade_snapshot.py`** covers 17 of the 27 tables. It does not cover `AccessRight`, `OutboundQueue`, `SeedMarker`,
  `Publication`, `Favorite` and the five `Release*` tables: count at least `AccessRight` and `OutboundQueue` by hand before and
  after (Data Services `GET` on the table).
- **A reload of the module's context on a live instance** has not been measured for this version (threads of the module and the
  class loader they carry). Replace the war with Tomcat stopped; do not touch a context file while EBX boots.

## What a "version" is here

- The build stamps a version (`tools/version.sh`: the nearest git tag without its `v`, `1.4.0`, or `1.3.1-29-g26e3a76` between tags,
  `0.0.0-<short hash>` in a repository with no tag, with `-dirty` when it was built from uncommitted changes). It sits in the war's `META-INF/MANIFEST.MF`
  (`Implementation-Version`, `Build-Commit`, `Built-At`), in the classes
  (`com/on/ebx/connect/version.properties`), in the MCP `initialize` answer (`serverInfo.version`) and in
  `GET /ebx-connectors/rest/v1/status` (`version.version`, `.commit`, `.builtAt`). A class tree compiled outside
  `build-test.sh` (Eclipse) reports `unknown`. Builds made before this stamp existed report nothing: for those,
  the only record is the WAR file and its checksum, which you keep yourself.
- **The data model has no version.** `Connectors.xsd` carries no schema version and the repository records none. The
  installer does not read one either; the one place it adapts to the model's age is a probe for a field
  (`Mapping/parent`, "rev 3"). What changes between two builds is the XSD, and EBX decides what that does to the data
  (next section). The module ships no migration framework: it has one table that remembers what the installer did
  (`SeedMarker`, below) and two start-time routines.

## What EBX does when the XSD changes

The module's data model is compiled from the XSD inside the WAR at every start and applied to the existing dataset
(dataspace `Connectors`, dataset `Connectors`). EBX's own rules (onHelp, `references/datamodel_evolutions.html`,
6.3 RC), applied to this model:

| Change in `Connectors.xsd` | What EBX does | Data |
|---|---|---|
| New table, new field | Created at the next start; existing rows get an empty value | Kept |
| Facet relaxed or modified (outside the key and foreign-key rules below) | Accepted | Kept |
| Field removed | Accepted; the value is dropped at the next update of each record | Lost for that field |
| Field renamed, field type changed | Accepted, but it is a deletion plus a creation | Lost for that field unless migrated by hand (export then re-import) |
| Table removed or renamed | Accepted; a rename is a deletion plus a creation | Lost for that table |
| **Primary key of a table changed** | The table is reset to empty in every dataset and dataspace; if that same key was used in the past, the table returns to the data it had then; type conversion of a key field is never done; the change is refused if the table has, or ever had, history | **Table emptied** |
| Foreign key declaration added or changed, or primary key of its target changed | The existing values restart from empty | Lost for that field |

A change is therefore additive only when it adds. The previous wording of this runbook ("the schema evolves
additively") was wrong for any build that changed a key.

### What changed in the model history

Read from `git log` of `Connectors.xsd` (50 commits, 2026-09-06 to 2026-10-06; keys, field names and declared types
compared commit by commit with the logic of `tools/schema_diff.py`; facets and constraints were not compared):

- **One primary key changed: `FieldProvenance`**, on 2026-09-28 (commit `9fd8d90`, "Volume lot 5"), from the
  auto-increment `id` to `(dataspace, dataset, tablePath, recordKey, fieldPath)`; the `id` field was removed with it.
  By the rule above, the first start of a build at or after that commit **empties `FieldProvenance`** on a repository
  that held the old key. The installer's own comment expects "no repair at all" because the five fields were already
  populated, and says in the same place that this was "NOT verified against a live repository upgrade". Do not rely on
  the rows surviving. The table is a rebuildable audit and cache: the engine writes a field's row again at its next
  write, and until then the TRUSTED write policy sees "no known provenance" for that field.
- Every other table kept its key since it was introduced (tables added later: `OutboundState`, `ConnectorCategory`,
  `SchemaRefreshState`, `SynchronizationCheckpoint`, `ConnectorTemplate`, `ExecutionRun`, `ConnectorGovernance`,
  `ConnectorGovernanceAudit`, `ConnectorOperationalMetrics`, the five `Release*` tables, `Publication`, `Favorite`,
  `AccessRight`, `SeedMarker`).
- Two field type changes, both on 2026-09-08, before most tables existed: `SynchronizationCheckpoint/version`
  `xs:long` to `xs:int`, and `Mapping/dataspace` and `Mapping/dataset` changed to EBX key types and back to `xs:string`
  (net: none).
- No field was renamed. `FieldProvenance/id` is the only field removed.

**Check every new build before deploying it**, against the build you run:

    ebx-connectors/tools/schema_diff.py --git <old-tag-or-commit> <new-tag-or-commit>

(from a checkout of this repository). Exit 0: only additions. Exit 1: it lists each key change, removed field or
table and type change; each one is a loss for that table or field, so export it first (backup section) and plan its
re-import. Without a checkout, extract `WEB-INF/ebx/schemas/Connectors.xsd` from the two WARs and run
`tools/schema_diff.py <old.xsd> <new.xsd>` (the release package carries the script).

## What the installer does at every start

`ConnectorsInstaller.install` runs at startup, and again, cheaply, from entry points until it has completed once.
It never deletes configuration and refuses to touch a dataset `Connectors` that belongs to another module's model
(`installation stopped: dataset Connectors uses the data model of another module`). In order:

1. Creates the dataspace and the dataset when absent.
2. **Seeds** the connectors, demonstration mappings and catalogue cards. Categories, packaged templates and release
   notes are product content and are installed even with `-Debx.connectors.seed=none`, which seeds no connector and no
   mapping. (The "Data connector" perspective is installed separately at start, when absent, and never modified.) Each connector and each mapping group is created
   **once**: the table `SeedMarker` (key `item`: `connector:<id>`, `mappings:<group>`, `migration:<name>`, `baseline`)
   records it, so one you deleted stays deleted. At the first start of a build that has the table, every connector
   already present is recorded (`baseline`; log line `seed markers recorded for N existing item(s)`); a connector
   deleted before that start, while the server ran, comes back once. Delete the marker row to have an item created
   again. A model that does not have the table yet (not reloaded) behaves as before: absent items are created at every
   start.
3. **Migrates** (`migrate`, after every seed, **at every start, not recorded in `SeedMarker`**): it fills values nobody
   has set: placeholders `A_RENSEIGNER` become `TO_BE_SET`; a blank trust level, logo or category, or a pricing that
   is blank or `UNKNOWN`, gets its seeded value (the generated EBX logo is replaced by the packaged one); and a
   mapping's target dataspace is normalised to its resolved key. That last one rewrites a stored value; the others do
   not overwrite what a person chose. It logs `migration: ...` when it changed something. **The dataspace is never
   guessed** (wave 13): a BLANK dataspace stays blank (it is a generic mapping, see below); a leading `B` is stripped only when the
   stripped key resolves; a stored dataspace that no longer resolves (deleted) is left as stored with a WARN naming the mapping
   (`dataspace '…' no longer resolves dataset …: left as stored`). Before wave 13 a blank, `B`-prefixed or unresolvable value was
   replaced by the first dataset of that name found anywhere in the repository, which could point a mapping at another project's data.
4. **`FieldProvenance` check** (`dropIncompleteFieldProvenanceRows`, once, recorded as `migration:fieldProvenanceKey`):
   deletes the rows that have a blank part of the new five-field key (log `FieldProvenance: removed N ...`). It is a
   safety net, not a conversion; it does not recreate what EBX emptied.

Nothing else is versioned or converted. Customer-owned connectors, mappings, templates, credentials, checkpoints,
run history and governance records are not replaced by packaged seed data.

## What to back up, and how

EBX's own documentation (onHelp, `engine/repository_administration.html`) is explicit: **a global backup of the
repository must be delegated to the underlying RDBMS**, using its standard backup procedure. That is the backup that
supports a rollback. What the module keeps outside the dataspace is listed at the end of this section.

1. **Repository backup (the one that counts)**: the database administrator's backup of the EBX repository database,
   taken with the application stopped or by the RDBMS's consistent method. Verify that it restores on an isolated
   target.
2. **Dataspace archive (a module-level copy, to inspect or to restore one table)**: in EBX, select the dataspace
   `Connectors`, Actions > Export, export policy "The whole content of the dataspace", dataset `Connectors`, with data
   values, permissions and information. The archive is written to the `archives` sub-directory of
   `ebx.repository.directory` on the server, is retrievable only by an administrator, is never cleaned by EBX, and
   must be protected like a backup (it holds credential values in their stored form: encrypted, or clear where
   `-Debx.connectors.secrets.allowClear` let one be typed). Importing it into that dataspace replaces the
   dataspace's current state with the archive's (Actions > Import). Whether an archive taken this way restores this
   module's dataset exactly, encrypted fields included, has not been verified.
3. **EBX Staging**: a Staging domain holding the dataspace `BConnectors` and its dataset can be exported as a zip
   (`/ebx-dataservices/rest/staging/v1`), and EBX's documentation advises a database backup before importing any
   Staging archive. Not exercised for this module.
4. `tools/upgrade_snapshot.py capture <folder>` (repository root; credentials in `EBX_USER` and `EBX_PASSWORD`;
   read-only Data Services `GET`) records 17 tables as JSON, secrets masked. It is a count-and-identity check for a
   before and after comparison, not a backup.
5. A configuration export (`GET /ebx-connectors/rest/v1/config`) is a logical copy of connectors and mappings. It
   runs the installer's ensure step, so take it before the upgrade, not on a half-upgraded repository. It does not
   replace 1.

**Outside the dataspace, to keep separately**: the JVM options of `docs/OPERATIONS.md` section 3 (Tomcat `setenv` or
the launcher) and the WAR with its checksum. Covered by backup 1 but not by 2 to 5: the schedules registered for
`ConnectorScheduledTask` and `RestSchemaRefreshScheduledTask` (EBX's scheduler dataset), the "Data connector"
perspective (the `ebx-manager` dataspace) and the workflow models that use the module's beans.

## Behaviour changes from 1.0.0

Two protections that 1.0.0 shipped off are ON by default in the next build (audit decision 1, 2026-10-06), and the
Connectors rights now apply to the tables too (audit C-01, below). The data model does not change; what changes is what
a running instance accepts. Read this before upgrading an instance that
works today, and decide each point before the restart:

| What | 1.0.0 | Next build | Check before upgrading | Keep the old behaviour (not recommended) |
|---|---|---|---|---|
| Private addresses for outbound calls (HTTP, JDBC, Kafka, SFTP, FTP, JMS...) | allowed | Loopback, private (10/8, 172.16/12, 192.168/16, fc00::/7), carrier-grade NAT, reserved, multicast and unresolvable hosts are refused, after DNS resolution and on every redirect hop. Link-local and cloud-metadata addresses were already refused. | List the hosts the connectors, the vault, the LLM and the catalog scans call that are not public (`connectors_list`, the Connector `baseUrl` column, `oauth.tokenUrl`, `instanceUrl`, `vault:` addresses). | Add them to `-Debx.connectors.egress.privateExceptions` (`host`, `host:port`, CIDR or `file:<path>`; it does not turn the host allow-list on). A local demo server: `-Debx.connectors.egress.privateExceptions=localhost:9092,localhost:9095,localhost:9082,localhost:8080,localhost:11434,127.0.0.1:9092,127.0.0.1:9095,127.0.0.1:9082,127.0.0.1:8080,127.0.0.1:11434`. Whole instance: `-Debx.connectors.egress.blockPrivate=false`. |
| Numeric hosts | any spelling | A host like `2130706433`, `0x7f.1` or `0177.0.0.1` is refused (only four decimal parts are a literal address). | Look for such URLs. | Rewrite them as `a.b.c.d`. |
| Secrets stored in clear | accepted (one WARN) | A `Connector` password or API key that EBX did not encrypt, and a `ConnectorProperty` whose key names a credential (`oauth.clientSecret`, `oauth.refreshToken`, `oauth.privateKey`, `tls.*Password`, `serviceAccountJson`, `sasl.jaas.config`...) and that is not a `vault:`/`env:`/`file:` reference, are refused at use with a message telling how to fix it. `GET /v1/status` (`secrets.clearSecrets`) and one WARN at each start list the connections. | On the 1.0.0 instance list the rows written through Data Services or an import: their `password`/`apiKey` read as plain text, and the secret properties are all plain text. | Re-enter the passwords and API keys in the Connector form (EBX encrypts them), move secret properties to references (an OAuth client secret can go in the API key field); stopgap `-Debx.connectors.secrets.allowClear=true`. |
| Host allow-list (`ebx.connectors.allowedHosts`) | optional | Unchanged: still optional (owner decision); public hosts are not restricted unless it is set. | - | - |

All of these are JVM `-D` options (the module reads nothing from `ebx.properties`) and need a restart. Rollback to 1.0.0
needs nothing: the old build ignores the new properties.

### The Connectors rights apply to the tables (audit C-01, tables)

In 1.0.0 the rights (`AccessRight`, the three default roles) closed the MCP tools, the REST routes and the user services,
but not the tables: whoever EBX let write the Connectors dataset could edit a connection (its base URL, its credentials)
through the EBX forms or Data Services with READ or no Connectors right at all. The next build puts an access rule on
every table of the dataset (`ConnectorsTableAccessRule`, docs/OPERATIONS.md section 4 "Rights on the tables"): no right =
rows hidden; below the right that writes the table = read only, no creation, no deletion. There is no property to switch
it off. The XSD does not change; the new classes need a Tomcat restart (as any new build).

| What | 1.0.0 | Next build | Check before upgrading |
|---|---|---|---|
| Editing configuration tables by hand (`Connector`, `ConnectorProperty`, `Mapping`, `MappingRule`, `MappingInput`, categories, templates, `SchemaRefreshState`, governance) | EBX permissions decide; MANAGE only when `ebx.connectors.role.designer` was set | MANAGE (designers, administrators, the system user) only; READ and RUN read only; no right: hidden | Give MANAGE (a row in "Access rights") to every role that edits connections or mappings in EBX directly. |
| Log, runs, publications, metrics, release notes, seed markers | EBX permissions decide | Read with READ; written by hand by administrators only (the engine still writes them) | Nothing, unless someone corrected log rows by hand. |
| Conflicts and outbound states | EBX permissions decide | Visible from RUN (they carry record values), hidden to READ; decided by RUN through the conflict manager / tool / route only | Readers who browsed `Conflict` in EBX need RUN. |
| What a RUN holder runs | the feed checkpoint and a conflict decision needed EBX write permission on the dataspace | Both written with all privileges after the module's RUN check (like the log, runs and provenance already were) | Nothing. |
| `RestSchemaRefreshScheduledTask` | its profile needed EBX write on `SchemaRefreshState` | its profile needs MANAGE (an administrator does) | Look at the profile of the scheduled task. |
| Other modules writing Connectors tables with a person's session (MDM Factory sample-file import: a `Connector` row) | EBX permissions decided | that person needs MANAGE | Give MANAGE to the Factory designers who import sample files, or let an administrator do it. |

Rollback to 1.0.0 removes the rule (the old classes do not declare it); nothing is stored by it.

### The in-JVM contract 1.1.0 and the setting "Default language-model connector" (wave 12)

The XSD gains one group of dataset-level fields, `/root/Settings`, with one field, `defaultLlmConnector` (a foreign key to
the `llm` connections, empty by default). It is an addition: EBX creates it at the next start, nothing is reset.
`tools/schema_diff.py` compares table fields only and reports nothing for it. The contract for sibling modules moves to
1.1.0 (`llm.chat`, see ebx-connectors/docs/CONTRACT.md); a module written for 1.0.0 keeps working.

| What | Before | This build | Check before upgrading |
|---|---|---|---|
| Answer size of a contract model call (`llm.ask`) | the `maxTokens` the calling module asked, up to 65 536 | never more than the connection's own `maxTokens` property (1024 unless set); the answer says `maxTokensApplied` and `capped` | Raise `maxTokens` on the `llm` connections the sibling modules use when they need long answers. No module called the contract before this build. |
| Default `llm` connection of the contract | `-Debx.connectors.llm.default`, else the only usable one | the setting first, then the property, then the only usable one | Nothing: the setting is empty after the upgrade, so the property still decides until an administrator fills it. |

Rollback: the previous build does not know the group; EBX drops the field and its value at its start (re-enter it after
upgrading again). Callers of `llm.chat` are refused `UNKNOWN_OPERATION` by the older build: they check the contract
version at startup (`ContractLlmChat.incompatibility()`).

### A mapping's dataspace is checked, not guessed (wave 13)

A mapping (a flow) stored with an empty `dataspace` and only a `dataset` name used to run a table-scoped job on the FIRST open
dataspace holding a dataset of that name (depth-first from the reference dataspace). On 2026-10-12 a flow of a project ran on
the orphan dataspace `Personnes_Master` of a deleted project instead of `Vol100k_Master`, and 300 rows of the wrong data were
published. No data model change; behaviour only. A mapping may leave the dataspace empty: it then applies to a record wherever
the dataset exists, workflow child dataspaces included. A TABLE-scoped run (feed import, scope send, catalog scan, enrich of a
whole table) needs exactly one dataspace serving the dataset; copies in child dataspaces of that dataspace do not count; several
distinct dataspaces → the run fails with `Dataset 'X' … is served by N dataspaces (…): name the dataspace on the mapping`, and the
upsert refuses it.

| What | Before | This build | Check before upgrading |
|---|---|---|---|
| Table-scoped run of a mapping with no dataspace (feed, outbound scope, enrich or sync of a table) | the first dataspace found | runs only when exactly one dataspace serves the dataset and table (children of it do not count); with several distinct ones it fails with the message above | List the mappings with an empty `dataspace` (`connectors_mappings_list`, or the Mapping table filtered on `osd:is-null(./dataspace)`) that run table-scoped, and name the dataspace on each when other projects hold a dataset of the same name. |
| `flows.put` / `connectors_mapping_upsert` with no dataspace | stored as given | stored as given (still generic); a note says which dataspace table-scoped runs will use; several distinct dataspaces = refused (dry run included); none = a note | Callers (MDM Factory, scripts) that build table-scoped flows should send `dataspace`. |
| `flows.put` / `connectors_mapping_upsert` with a dataspace that does not exist | stored | refused: `Dataspace 'X' does not exist` | - |
| Start (`migrate`) | a blank, `B`-prefixed or no-longer-resolving dataspace was replaced by the first dataset of that name found anywhere | a blank value stays blank; a `B` prefix is stripped only when the stripped key resolves; a stored dataspace that no longer resolves is left as stored with a WARN naming the mapping | Read the WARN lines `mapping <id>: …` after the first start. Mappings that were filled at an earlier start stay specific; clear their dataspace to make them generic again. |

Rollback: nothing is stored by the change; the previous build reads the same rows.

### An outbound scope send can run in the background, with a state and a stop (wave 13)

`connectors_sync(scope=…, async=true)` (and `POST /sync/{mapping}?scope=…&async=true`, and `flows.run` with `async: true`) now
accepts an OUTBOUND mapping sent on a scope, as it has accepted an inbound feed import since volume lot 7a. Measured on
2026-10-12: a distribution send of 88,991 rows took 9 min 36 inside one synchronous call, with no run id before the end and no
stop. The run id is now answered at once (`{runId, status: "RUNNING", async: true, direction: "OUTBOUND", scope}`); the send runs on
the executor of the asynchronous imports (same slots, `ebx.connectors.import.async.maxConcurrent` / `maxQueued`; a second
asynchronous start of the same mapping is refused, REST 409), writes its `ExecutionRun` row every batch (`min(batchSize, 250)`
records) and ends DONE, PARTIAL, ERROR, STOPPED (`connectors_run_stop`) or INTERRUPTED (a restart). `connectors_run_get` answers the
same `execution{}` block as for a feed, and now adds `direction` and `mapping` (also for a feed import: INBOUND). The contract for
sibling modules moves to 1.2.0 (`run.get`, `run.stop`, see ebx-connectors/docs/CONTRACT.md); a module written for 1.1.0 or 1.0.0 keeps
working. No data model change; the `ExecutionRun` table is the one feeds already use.

| What | Before | This build | Check before upgrading |
|---|---|---|---|
| `connectors_sync` / `POST /sync` / `flows.run` without `async` | synchronous, answer with `results[]` | unchanged, to the field | Nothing. |
| The same with `async=true` on an outbound mapping and a scope | refused: `async only applies to an inbound feed with no key, predicate or scope` | accepted, answers at once; refused still with a key, without a scope, with `dryRun`, or on an inbound mapping, the refusal sentence now ending `…or to an outbound mapping sent on a scope (…)` | A caller that treats the old refusal as "go synchronous" (the MDM Factory does, by the words "async only applies") keeps working; it takes the asynchronous route as soon as this build is installed. |
| `connectors_run_get` / `GET /runs/{runId}` | `execution{}` for a feed import | also for an asynchronous outbound send; `direction` and `mapping` added at the top of the answer and `direction` in `execution{}` (only when the run's mapping still exists) | Parsers that reject unknown fields. |
| How a send counts in `execution{}` | - | sent = `updated`, unchanged since the last send = `noChange`, failures = `errorCount` (+ `firstErrors`), records waiting for their retry delay = `skipped`; `created` and `rejected` stay 0; `nextIndex` = records handled | A caller that adds `created + updated + noChange` to know how many records the send reached gets exactly that. |
| `connectors_run_stop` on a send | no effect on a scope send | ends it `STOPPED` at the next boundary; the records already sent keep their outbound state (a later `changed` send skips them) | - |

Rollback: the previous build refuses `async` for a scope send again (callers fall back to the synchronous call), reads the same
`ExecutionRun` rows, and answers `UNKNOWN_OPERATION` for `run.get` / `run.stop` (callers check the contract version at startup). A send
running in the background when the module stops is marked INTERRUPTED at the next start; the records it had sent keep their outbound
state.

### Table triggers queue their sends (audit C-07, lot L8)

A new table, `OutboundQueue`, is added to the Connectors model (additive: no key or type of an existing table changes). **It needs a
Tomcat restart**: EBX reads the model at start, and the sender is a background task started at repository startup. Behaviour change:
a `ConnectorTableTrigger` without `<blocking>true</blocking>` now sends after the user's commit, by the module's background sender,
at least once (the target must tolerate a repeated message), instead of inside the user's transaction; the sync log, provenance
and outbound state of such sends are now written (origin `trigger`). With `blocking` true nothing changes.

**Limit, in plain words:** the queue row is NOT validated with the user's transaction (it is written in the Connectors dataspace,
a different one from the user's); a brutal stop of the JVM in the few milliseconds between the trigger and the row's write loses
the send. `blocking=true` remains the only strong guarantee. `DEAD` rows are purged by the retention task; the retry delay of a
trigger send is `-Debx.connectors.trigger.retryDelayMinutes` (default 2, capped by the mapping's delay).

Rollback: the previous build does not know the table. EBX keeps its rows (an unknown table of a model is not read) but nothing
sends them: the previous build's triggers call the remote system in the transaction again, and rows still `PENDING` at the moment
of the rollback are never sent by it. Before rolling back, let the queue drain (the table holds no `PENDING` row) or note the
rows and replay those records by hand (`connectors_sync` on the record); rows reappear if the newer build is put back.

### Browser storage of the custom screens is keyed by the EBX user (wave 13)

The catalog, the connector form, the mapping form, the designer and the run screen kept a few values in the browser:
the page a connector card or a flow card was clicked on (so the native Close comes back there), the connector chosen for a
mapping about to be created, and two display preferences of the catalog (facet rail folded, advanced filters opened). They sat in
`sessionStorage` / `localStorage` under bare names (`ftccRecordOrigin`, `ftcnFlowReturn`, `ftcnCreateConnector`, `ftServiceReturn`,
`ftcxRail`, `ftcxMoreOpen`). `sessionStorage` belongs to the browser TAB and survives an EBX logout, `localStorage` survives closing
the browser: the next user who logged in in the same browser inherited those values (the "back to" address of another user's page, a
preset connector, the other user's display choices). Found 2026-10-13 in the ebx-tiers explorer (its search and filters), then looked
for in every module.

Every key is now `<name>:<encodeURIComponent(EBX login)>` (the form of the Data Catalog module's `ftdcStore`, which writes the same
`ftServiceReturn`, `ftcxRail` and `ftcxMoreOpen` entries on the same origin); the login is the one of the session that renders the screen. Each screen
removes, when it opens, for each of the six names, the bare legacy key and the keys of another user, from both stores. Hand-over values are read and
removed at once (`ftcnCreateConnector`) or consumed by the click that uses them (the return addresses). No data model change, nothing
stored on the server.

| What | Before | This build | Check before upgrading |
|---|---|---|---|
| Keys in `sessionStorage` / `localStorage` | `ftccRecordOrigin`, `ftcnFlowReturn`, `ftcnCreateConnector`, `ftServiceReturn`, `ftcxRail`, `ftcxMoreOpen` | `<same name>:<encodeURIComponent(user)>`; the bare names are deleted at the first opening | Nothing. |
| A return address left by a card before the upgrade | honoured | ignored (its key is gone): Close goes to EBX's default target once | Tell users to expect it on tabs open during the upgrade. |
| Facet rail and advanced-filter preferences | per browser | per user and browser: each user sees the default (rail shown, advanced filters folded) once | Nothing. |
| A user logs in after another in the same tab | sees the previous user's values | sees none: the other user's keys are removed at opening | - |

Rollback: the previous build reads the bare names again and ignores the `<name>:<user>` keys, which stay in the browser until it is cleared (they
hold only a return address, a connector id and two display flags).

## Before an upgrade

1. Record what is installed: `GET /ebx-connectors/rest/v1/status` (`version`), or for an older build the WAR file's
   SHA-256 and the commit it came from. Keep that WAR and its checksum: it is the rollback artefact.
2. Run `schema_diff.py` (above). If anything is data-affecting, decide per table whether it can be rebuilt
   (`FieldProvenance`) or must be exported.
3. Stop new synchronization schedules and let active runs finish or stop them through their governed operation.
   Record resumable run identifiers.
4. Take backup 1 and verify it restores. Take 2 and 4 as well.
5. Use a release package: `dist/ebx-connectors-<version>.zip` from `tools/package.sh` holds the WAR, the
   third-party notices, `OPERATIONS.md`, this file, `RELEASE-NOTES.md`, `SHA256SUMS`, and the scripts
   `tools/smoke.sh` and `tools/schema_diff.py`. Verify the sums
   (`shasum -a 256 -c SHA256SUMS`). Connector-owned JDBC drivers and SDKs travel inside the WAR (`WEB-INF/lib`); never
   satisfy them from another module, Tomcat's global `lib` or a developer machine, and never mix an old WAR's classes
   with new libraries. EBX, Servlet/Tomcat, Jackson and SLF4J are container-provided and must not be in the WAR.
6. Rehearse on a disposable copy of the repository with the same EBX, JDK and Tomcat versions as production.

## Upgrade and verification

1. Stop Tomcat, replace the WAR (and delete the exploded `webapps/ebx-connectors/` directory, or Tomcat keeps serving
   the old one), start. `module.xml` and the module's classes are read only at start.
2. Read the kernel log (`[connectors]` lines): no `installation failed`, no `installation stopped`, no
   `migration failed`; note `seed markers recorded`, `migration:` and `FieldProvenance: removed`
   (`docs/OPERATIONS.md` section 7.2).
3. Run the smoke test: `EBX_AUTH=<user>:<password> tools/smoke.sh http://<host>:<port>` (`EXPECT_VERSION=<version>`
   makes a wrong build fail; `EBX_AUTH_NOROLE` adds an account without Connectors rights). It reads only.
4. `tools/upgrade_snapshot.py capture <folder>` again, then `compare <before> <after>`: a table that the schema diff
   said would be emptied is expected to differ; every other difference is to be explained. Compare counts and
   identifiers for connectors, categories, properties, mappings, templates, checkpoints, runs, dead letters,
   metrics and governance records. Re-running the installation must add no duplicate.
5. Validate permissions, an encrypted field, catalog navigation, a read-only connector diagnostic and one resumable
   synchronization in the test environment before production schedules are enabled.

## Rollback

There is no in-place schema downgrade and no down-migration. Putting the old WAR back over a repository that a newer
model has opened is not a rollback: an added field stays in the data, and EBX applies the older model's keys and
types to it by the rules above (a key restored to a previous definition brings back what the table held when that
key was last used, which EBX documents and this module has not verified).

1. Stop synchronization schedules and Tomcat. Preserve the logs and the failed deployment.
2. Restore the pre-upgrade repository backup (backup 1) with the RDBMS's procedure.
3. Restore the exact previous WAR, whole, with its own `runtime-libs`; delete the exploded directory and, only if the
   local runbook says they are disposable, the Tomcat work caches. Never delete the EBX repository to clear a module
   error.
4. Start the database, then Tomcat; check that the data model compiles (`status`: `installation.modelPresent`).
5. Compare with the snapshot taken before; validate permissions, credentials and checkpoints before schedules
   resume. A run started after the backup has already written to external systems: reconcile it before any replay,
   to avoid duplicate writes.

If no verified repository backup exists, stop. Escalate to the EBX administrator and preserve the repository; do not
attempt an in-place schema downgrade. A dataspace archive (backup 2) can restore one table's data into a repository
whose model still has that table with that key; it is not a substitute for step 2.

## Evidence to retain

The old and new WAR checksums and versions, the `schema_diff` output, the runtime-library manifest
(`runtime-libs/SHA256SUMS`), the offline suite output, the data-model validation report, the repository backup
identifier, the snapshots before and after, the smoke output, the kernel-log lines of the first start, and the
deployment or rollback decision. A fresh install and an upgrade on a real repository remain separate manual
acceptance gates and are not inferred from offline tests.


## Connectors: templates without injection (audit C2-04, C2-05)

What changes: a value substituted into a feed's `keyPredicate` is quoted as a whole XPath literal; every `${...}` of a JDBC `sql`
input is a bound parameter; saving a mapping whose `keyPredicate` holds a placeholder outside apostrophes (`./id=${id}`) is refused,
in the upsert, the config import and the flow contract (an error on the Mapping record in the UI).

What happens to mappings already stored: they keep running unchanged. `./f='${x}'` (the shape of every example) gives the same
record: an apostrophe in a value is now written as `"it's"` instead of `'it''s'`, which EBX reads the same. A placeholder outside
apostrophes keeps a number or a boolean as it is and quotes anything else as a literal (so `./id=${id}` with `42` still matches,
and with `1 or 1=1` matches nothing). An `sql` input that wrote a value in quotes (`values ('${./name}')`) now binds it; a
`${...}` after `FROM`, `JOIN`, `INTO`, `UPDATE`, `TABLE`, `ON`, `TO`, `VIEW`, `SCHEMA`, `DATABASE`, `USER`, `ROLE` or glued to a name
is still written as text but only when it is a plain identifier (`A.B_C`); other values stop the statement with
`SQL_PLACEHOLDER_NOT_BINDABLE`. What a mapping can no longer do: build SQL structure from a value. The Data Catalog's access
statement (`GRANT ... ON ${asset} TO ${dbUser}`) keeps working without a text change as long as asset names are plain
identifiers; an asset name with a space, quote or `;` is now refused instead of executed.

Before upgrading, list the mappings concerned (read only). With the EBX SQL of the `Connectors` dataset:

    SELECT m."$pk", m.label, m.keyPredicate FROM "Mapping" m
    WHERE m.keyPredicate LIKE '%${%' AND REGEXP_LIKE(m.keyPredicate, '=\s*\$\{')

(if `REGEXP_LIKE` is refused, list `keyPredicate LIKE '%${%'` and read them: a placeholder not inside apostrophes is the case), and

    SELECT i.mapping, i.name, i.expression FROM "MappingInput" i
    WHERE i.name = 'sql' AND i.expression LIKE '%${%'

or `connectors_mappings_list` and `connectors_mapping_get` on each. Rewrite a refused `keyPredicate` as `./id='${id}'`; a
`sql` input needs nothing, except one whose `${...}` stands in a name and whose values are not plain identifiers.
Rollback: the previous build writes values as text again; nothing is stored differently.

## Connectors: screens (audit C2-09, C2-17, C2-18, C2-19)

- **Connection identifier pattern (C2-09).** `Connector/id` now carries the XSD pattern `[A-Za-z0-9_.-]{1,40}` (letters, digits, `_`, `.`,
  `-`; it was only limited to 40 characters). An XSD change is read at the next Tomcat start (restart: ask the product owner). A connection
  whose identifier does not match is NOT renamed and not deleted: the model reports it as a validation error and the connection keeps
  running, but its record cannot be saved again until the identifier is fixed (an identifier is referenced by mappings, workflow beans,
  scheduled tasks and triggers: change it with the references, never alone). **Before the upgrade, list the identifiers that do not
  match** (read only, EBX SQL of the `Connectors` dataset, `data-access execute_query`):

      SELECT c."$pk", c.id FROM "Connector" c WHERE NOT REGEXP_LIKE(c.id, '^[A-Za-z0-9_.-]{1,40}$')

  (if `REGEXP_LIKE` is refused, list every `id` and read them: a space, quote, `<`, `/`, accent or line end is the case). Nothing to
  do when the list is empty. Rollback: the previous build has no pattern; nothing is stored differently.
- **Languages (C2-17).** The refusal "Access denied: ... requires the Connectors right ..." is a bundle text, English or French by
  the locale of the session, or of the `Accept-Language` header for REST (`ConnectorsAccess.refusal(what, right, locale)`,
  `ConnectorsAccess.localeOf(header)`); the English text stays the machine-readable one. Nothing stored changes.
- **Theme (C2-18).** The sync log, metrics, read-only, operations and OpenAPI import screens take their borders, surfaces and
  accents from the perspective's theme tokens; the status colours (`--ftc-ok`, `--ftc-warn`, `--ftc-bad` and their `-bg`/`-line`)
  keep today's colours unless a perspective sets them.
- **Synchronise action (C2-19).** "Synchronise with an external application" is offered on a table only when an active top-level
  mapping applies to that dataspace, dataset and table (mappings re-read every 15 s). A table whose mapping is created after the
  toolbar was drawn shows the action within 15 s.

## Connectors: bounded listings (audit C2-07)

`connectors_outbound_states`, `GET /ebx-connectors/rest/v1/outbound` and `connectors_log_search` no longer return every row. They take `max`
(default 200, ceiling 2 000: a larger value is lowered to 2 000 and the answer says so in `note`, it is never refused) and `from` (0-based
offset, default 0). The answers keep their fields (`records` / `rows`, `count`) and add `max`, `from` and, when more rows exist, `cut: true`,
`nextFrom` and `note`. **`count` stays the total number of matching rows** (for the outbound states: those the caller may read), but `records` /
`rows` now hold one page: a client that read the whole list from one call must follow `nextFrom` until `cut` is absent. The default of
`connectors_log_search` `max` moves from 50 to 200. No data, table or contract field changes; the in-JVM contract exposes neither listing, so
its version is unchanged. Rollback: none needed (answer shape only; older callers still read `records` / `rows` / `count`).
