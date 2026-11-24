# ebx-connectors — operator guide

For the person who installs, configures, upgrades and runs the `ebx-connectors` module on an ON EBX 6.3 server and
did not write it. Everything below was read in the sources, scripts and existing guides of this repository and revised on
2026-11-12 for release 1.4.0 (base commit `15ea90c`); the changes of 1.4.2 are marked "1.4.2" where they occur (preview word `cdc`, section 4 and 10; origin columns, section 4; upgrade snapshot, section 7.1; webhook and jars in `docs/UPGRADE-ROLLBACK.md`), and those of 1.5.0 "1.5.0" (start-up lines in the kernel log, section 7.2; the Cloudera connections and the timestamps are in `docs/UPGRADE-ROLLBACK.md` and `CLOUDERA-USER-GUIDE.html`). Where something could not be established by reading, it says "not established";
where it is described but was never run on an instance, it says "not rehearsed". Where the code and
a guide disagree, the code is followed and the disagreement is stated in one line. "The owner's field notes" means the
`CLAUDE.md` knowledge base of the module's owner; it is not shipped with the module.

What the module does, connector by connector, is in the [user guide](USER-GUIDE.md) and the reference
[GUIDE.md](../GUIDE.md); the engine internals and a list of known gaps are in [REFERENCE.md](REFERENCE.md). This file is
only about running it. It does not repeat them: it links to the section.

## 1. What the module is and what it needs

`ebx-connectors` (Java package `com.on.ebx.connect`, module name `ebx-connectors`, web context `/ebx-connectors`) lets
EBX call external systems through configured **connectors** and **mappings**: inbound enrichment, outbound deltas and
replay, feeds, webhooks, a data-product publication to Snowflake, OpenLineage events to the data catalog, and MCP tools
for agents. Its configuration and its traces are stored in the dataspace and dataset `Connectors` (27 tables, model
`Connectors.xsd`).

| Item | What was read |
|---|---|
| EBX version it was run on | ON EBX 6.3: 6.3.0-gp on the owner's test instance (the offline suite compiles against that instance's `lib/`), after 6.3.0-dev and 6.3.0-rc2 to rc4; no other instance has been tried. `web.xml` is Jakarta EE 5.0 and the REST layer uses `jakarta.ws.rs`: it needs an EBX 6 build on Tomcat 10 or later. The module detects older builds only for the native MCP registration (it then serves `/ebx-connectors/rest/mcp` alone). |
| Java level | Sources are compiled with `javac --release 8` (`build-test.sh`, `build-agent.sh`), so class files are major 52; the JDK that builds and the JVM that runs may be newer. The CI workflow asks for "Java 8+". Collibra's PATCH assumes `java.net.http` (JDK 11+) (REFERENCE.md §6.2.2). |
| EBX jars | Compile time: `ebx.jar` (a standard ON EBX installation) or `ebx-lib.jar` (FastTrack; the same bytes), `ebx-addons.jar` (the Match and Merge public API, needed to compile `GoldenRecords`/`MameGoldenRecords`; the build stops if it is missing) and the servlet API, taken from `EBX_LIB_DIR` (default: `ebx-ps-fasttrack-server/lib` two folders above the module); Jackson and SLF4J come from `runtime-libs/`, the copies the war ships. Run time: ON EBX supplies its own API (`com.orchestranetworks.*`, `com.onwbp.*`, `jakarta.ws.rs.*`) and Tomcat the servlet API; **the war carries everything else**, Jackson and the SLF4J API included (section 1.1). |
| Libraries owned by the module | `ebx-connectors/runtime-libs/*.jar` (JDBC drivers, Kafka, JSch, AMQP, ActiveMQ, Mongo, Cassandra, Couchbase, Azure Service Bus, codecs...), listed with licences in `THIRD-PARTY-NOTICES.md` and checksums in `SHA256SUMS`. The build packages them into `WEB-INF/lib` of the WAR, together with Jackson 2.18.12 (since 1.4.1; 2.14.2 before) and the SLF4J API (section 1.1). A connector type whose class cannot be loaded is not registered; REFERENCE.md §6.2.1 notes that only `kafka` and `sftp` are skipped quietly. GUIDE §18 still says to put some jars in Tomcat's shared `lib` (and quotes older versions: Kafka 3.6.1, JSch 0.2.16): **the build and `runtime-libs/README.md` win** (they package them; a second copy in the shared lib must not exist). |
| Other modules | `ebx-data-catalog`: optional, detected at run time (`CatalogDetection`: dataset `DataCatalog`/`DataCatalog` of the catalog's own model, else `ABSENT`/`FOREIGN`); without it lineage events are simply not delivered to a local catalog. `ebx-addon-mame` (Match and Merge): only the golden-record helpers use it and keep the dependency in one class. `ebx-assistant` web application: needed only for the native MCP registration. Other modules depend on this one: `ebx-mdm-factory` embeds `on-ebx-agent.jar` built here (`ebx-connectors/build-agent.sh`) and calls the in-JVM bridge `com.on.ebx.connectors:type=InJvmMcp`. Since wave 11 the module also offers the sibling modules a versioned contract, `com.on.ebx.connectors:type=Contract` (`ebx-connectors.sibling` 1.1.0 since wave 12: ask a language-model connector one prompt or one tool-use turn of a conversation, declare, read, delete and run flows; 1.2.0 since wave 13: follow (`run.get`) and stop (`run.stop`) a run started with `async`; [CONTRACT.md](CONTRACT.md)); the Factory and the catalog do not call it yet. The agent library (`on-ebx-agent.jar`) ships `ContractLlmChat`, the client of its `llm.chat`. |
| Connector types | 96 types: `ConnectorRegistry` registers them (`kafka` and `sftp` only when their library is present), and the generated operational-readiness catalogue (`docs/CONNECTOR-OPERATIONAL-READINESS.md`) lists the same 96. Two of them, `documents` and `redshift`, are known but closed by default (section 4, "Preview functions"). The seeded connector ids are another count: at least 61 (section 2.3). Which were run for real: section 9. |

### 1.1 What the war carries, what the server provides, and what stays absent (audit X3-07)

**What a standard ON EBX 6.3.0-gp `lib/` holds** (measured 2026-11-12 on an instance built from the standard distribution only): `ebx.jar` (the
same jar that FastTrack calls `ebx-lib.jar`), the database driver and `jakarta.mail`. `ebx.jar` supplies `com.orchestranetworks.*`, `com.onwbp.*`
and `jakarta.ws.rs.*` (and, read with `unzip -l`, `javax.annotation`, `jakarta.xml.*`, `jakarta.json.*`); it does **not** supply Jackson (ON EBX keeps a relocated
private copy, `com.onwbp.com.fasterxml.jackson`), SLF4J, commons-* or the JMS API. A FastTrack `lib/` holds about a hundred more jars, among them
`jackson-*.jar` and `slf4j-api.jar`: that is why a war that was only ever built and run there looked complete. On the standard instance, the war as
built before this change started with ON EBX and was dead at once: `NoClassDefFoundError: com/fasterxml/jackson/databind/JsonNode` at the
initialisation of the REST servlet and in `handleRepositoryStart` (measured by the audit, pass 1 of its README; the audit's document is not shipped).
With the three Jackson jars added by hand to `WEB-INF/lib`, a fresh install of the module started cleanly (measured by the audit, passes 2 and 3).

**What the war carries now** (`runtime-libs/`, all of it copied to `WEB-INF/lib`): the connector libraries of section 1, and, new in 1.4.0:

| Jar | Why | Pinned |
|---|---|---|
| `jackson-core-2.18.12.jar`, `jackson-databind-2.18.12.jar`, `jackson-annotations-2.18.12.jar` | the JSON layer of the whole module; Apache-2.0. **Since 1.4.1 (audit X3-08) Jackson 2.18.12**, no longer the 2.14.2 of the FastTrack `lib/` (11 advisories on 2.14.2): downloaded from Maven Central, SHA-256 in `PINNED-WAR-JARS.txt`. Jackson 2.15 and later reads with limits: section 3.1, "Jackson read limits" | `runtime-libs/PINNED-WAR-JARS.txt` (SHA-256) |
| `jackson-datatype-jsr310-2.18.12.jar` | azure-core (it was already there) | same |
| `jackson-dataformat-yaml-2.18.12.jar`, `snakeyaml-2.3.jar` | the OpenAPI import in YAML. New in 1.4.1: until then they were read from the server `lib/` if present, and a YAML factory of a `lib/` that holds another Jackson cannot be used by the module's Jackson | same |
| `snappy-java.jar` (1.1.10.12), `proton-j-0.35.0.jar` | Kafka compression; Azure Service Bus (azure-core-amqp 2.12.1 declares proton-j 0.34.1: 0.35.0 closes an advisory). Pinned so that a build cannot ship the versions the advisories name | same |
| `slf4j-api-1.7.32.jar` | Kafka, ActiveMQ, RabbitMQ, MSAL4J and the Couchbase / Qpid transports log through SLF4J and do not start without its classes; MIT; **API only, no binding**: SLF4J 1.7 prints one notice (`Failed to load class "org.slf4j.impl.StaticLoggerBinder"`, `Defaulting to no-operation`) on the standard error at its first use and then logs nowhere. A server that wants these logs adds a 1.7 binding to its own `lib/` | same |

If the server `lib/` also holds Jackson or SLF4J (every FastTrack workspace does), the war's copy is the one the module uses: Tomcat's web application
loader reads `WEB-INF/lib` before the shared `lib/`. This has been **read**, not rehearsed on FastTrack's Tomcat with these jars. Since 1.4.1 `tools/install.sh` into a FastTrack
workspace puts the jars of `runtime-libs/` into that workspace's `WEB-INF/lib` (section 2.2): the code compiled and tested on Jackson 2.18.12 runs on it, while the server `lib/` of the demonstration
instance keeps its own Jackson 2.14.2 for the other modules. **To confirm after a start**: `GET /ebx-connectors/rest/v1/status`, `requestLimits.jackson.version` (the Jackson the module loaded).

**The check that keeps it so.** `build-test.sh` (hence `tools/package.sh`) runs `tools/check_war_selfcontained.py` on the built war: `jdeps` lists, for every class of
`WEB-INF/classes` and of every jar of `WEB-INF/lib`, each class it references; one found in none of the war, the JDK, Tomcat's `lib/` and a standard `ebx.jar` fails the build,
and the SHA-256 of the five jars above, read INSIDE the war, must be the pinned ones. Set `EBX_STANDARD_JAR` (the `ebx.jar` of a standard distribution) and
`TOMCAT_LIB_DIR` for the exact check; without them the tool says so and falls back to a list of package prefixes that ON EBX and Tomcat supply (weaker: a class that is
not really in `ebx.jar` is taken as present). `tools/package.sh` refuses to run without `EBX_STANDARD_JAR`. Before this change the check failed on the war with 1,265 classes
found nowhere (187 of the module's own classes name Jackson); after, 0 outside the list of accepted references.

**What stays absent on purpose.** The 929 references that remain are in `tools/war-optional-refs.txt`, one line each with its reason. They are optional features of the drivers.
Without the library a connection that does not use the feature is not affected; one that does fails when it is used, with the driver's own error (`NoClassDefFoundError` or "driver not found").
Read from the class names `jdeps` printed and from the drivers' documentation, **not run against each product**:

| Connection | Jar of the war | Absent library, and what is lost |
|---|---|---|
| Kafka (`kafka`, capture `cdc`) | `kafka-clients` | `io.grpc` (client telemetry) and `org.jose4j` (SASL/OAUTHBEARER with an OIDC provider): only those two. PLAINTEXT, SSL, SASL PLAIN and SCRAM and the lz4, snappy and zstd codecs (shipped) work |
| SFTP | `jsch` | BouncyCastle (extra key-exchange algorithms) and junixsocket (ssh-agent socket) |
| PostgreSQL, Greenplum | `postgresql` | Waffle (SSPI single sign-on from Windows); OSGi and annotation classes |
| H2 | `h2` | servlet (web console), Lucene (full-text), JTS (geometry) |
| Oracle | `ojdbc8` | `orai18n` (national character sets beyond the JDK's), UCP, RAC fast-application-notification, JTA/JCA/WebLogic transactions, DMS, XMLType, wallets |
| SQL Server, Azure SQL | `mssql-jdbc` | Azure Key Vault (Always Encrypted), Gson (enclave attestation), ANTLR (client-side FMTONLY parser), BouncyCastle (PEM client certificates) |
| MySQL, SingleStore | `mysql-connector-j` | protobuf (X DevAPI), c3p0, Oracle Cloud authentication, OpenTelemetry |
| MongoDB | `mongodb-driver-*` | AWS SDK (MONGODB-AWS authentication), `mongodb-crypt` (client-side encryption), JNR (Unix sockets), Micrometer, Kotlin and record codecs |
| Cassandra | `cassandra-driver-core` (shaded) | JNR, HdrHistogram, Brotli / LZF / LZMA / zlib codecs, Conscrypt, BouncyCastle, a `FutureFallback` Guava removed in 20 (the driver falls back) |
| Couchbase | `couchbase-*` | the same optional netty codecs and TLS providers, Android, Hadoop |
| RabbitMQ (AMQP) | `amqp-client` | Micrometer, OpenTelemetry, Dropwizard metrics (no metrics), netty 4.2 transport (plain sockets are used) |
| ActiveMQ, JMS | `activemq-client` | XStream (XML message transformation), JmDNS (ZeroConf discovery) |
| Azure Service Bus | `azure-*`, `reactor-*`, `msal4j-*` | Micrometer and OpenTelemetry hooks, HTTP/3 and io_uring of Reactor Netty, nimbus-jose-jwt for the on-disk token cache of the MSAL4J persistence extension |
| Avro files | `avro` | `commons-compress`: an Avro file compressed with bzip2 or xz is refused; null, deflate and snappy work (the Avro tests of the suite run without it) |
| All of the above | | logging back ends (log4j, commons-logging; SLF4J has no binding, see above), GraalVM, BlockHound, OSGi and annotation-only classes: no effect on a JVM |

The module's own use of the Match and Merge add-on (`com.orchestranetworks.addon.mame`, optional distribution `ebx-addons.jar`) is also on that list: it is reached only through
`GoldenRecords` / `MameGoldenRecords`, which catch the `LinkageError` (section 2.6).

## 2. Installing on a server

**There is a version, a release package and, for a FastTrack workspace, an install script (`tools/install.sh`, section
2.2).** A build is versioned from the nearest Git tag (`v1.2.0` gives `1.2.0`, `1.2.0-17-ga9888e4` between tags; `0.0.0-<short
hash>` when there is no tag; `-dirty` when built from uncommitted changes).
The build stamps the version into the WAR (`META-INF/MANIFEST.MF`, `Implementation-Version`), into the classes, into
the MCP `serverInfo.version` and into `GET /ebx-connectors/rest/v1/status`. `tools/package.sh` turns the WAR that the
test script builds into a release package (section 2.1).

### 2.1 Building the WAR

`./build-test.sh` at the repository root (or `ebx-connectors/build-test.sh`) does, in this order: Python self-tests of
the helper tools; checks that `EBX_LIB_DIR` holds `ebx.jar` or `ebx-lib.jar`, and `ebx-addons.jar` (exit 2 otherwise); compiles main
and test sources (`javac --release 8`); runs the offline contract tests (plain `main` programs, no EBX repository, no
vendor account); runs three Python upgrade-safety checks; copies `src/main/webapp` and the compiled classes into
`WEB-INF/classes`, the `runtime-libs` jars into `WEB-INF/lib` and the licences into `META-INF/licenses`; **fails if a
jar has no line in `THIRD-PARTY-NOTICES.md`**; writes `<build dir>/ebx-connectors.war` (build dir: `target/baseline`,
or `EBX_BUILD_DIR`); runs `tools/check_war_selfcontained.py` on it (section 1.1: every referenced class is found, the pinned jars are intact), then `tools/verify_release_readiness.py` (WAR readable, SHA-256 computed, expected entries).
The root `build-test.sh` also builds the SPI jar and the extension sample. Duration of the suite: about 120 s on the
build host (measured 2026-11-12: 263 lines containing `PASS`, none `FAIL` on 1.4.0; 1.4.1 adds tests, see its upgrade guide); the WAR is about 96 MB.

**Version and package (audit C-08).** `tools/version.sh` derives the version from git; `build-test.sh` writes it, the
commit and the build time to `com/on/ebx/connect/version.properties` in the classes and to the WAR's manifest
(`Implementation-Version`, `Build-Commit`, `Built-At`; read it with `unzip -p ebx-connectors.war META-INF/MANIFEST.MF`).
A class tree compiled by Eclipse has no such file: the module then reports `unknown`. `tools/package.sh` (from a clean
commit only: a modified or untracked file is refused, there is no switch; `EBX_STANDARD_JAR` must be set, section 1.1; `--since <commit-or-tag>` for the release notes) runs the whole suite, then writes
`ebx-connectors/dist/ebx-connectors-<version>.zip` holding the WAR, the third-party notices, this guide,
`UPGRADE-ROLLBACK.md`, `licenses/` (licence and notice texts of the bundled jars), `RELEASE-NOTES.md` (git log since the previous tag), `tools/smoke.sh`, `tools/schema_diff.py`
`DEPENDENCY-CHECK.txt` and `SHA256SUMS`; nothing is deployed; the zip's own checksum is written beside it.
**The advisory gate (since 1.4.1, audit X3-08).** BEFORE the build, `package.sh` runs `tools/deps_check.sh --require-osv`: every jar of `runtime-libs/` (73 of 73; a jar whose Maven group or version cannot be read is itself a finding) is asked to OSV, and an
advisory that `runtime-libs/DEPS-KNOWN.txt` does not accept, with its reason and date, refuses the package (`CONN-PACKAGE-3`). An OSV that cannot be reached refuses it too. The one way past is the named variable `CONN_PACKAGE_OSV_WAIVER="<who decided, why, date>"`:
the check then runs offline (library families only) and the text is written in `RELEASE-NOTES.md` and `DEPENDENCY-CHECK.txt` of the package. There is no switch for an advisory OSV reports. The ordinary suite (`build-test.sh`) stays offline. `dist/` is git-ignored: a package is rebuilt from a
commit, never committed. Verify a received package with `shasum -a 256 -c SHA256SUMS` inside its folder. The WAR's
SHA-256 is also printed in `RELEASE-NOTES.md`. `runtime-libs/SHA256SUMS` remains the checksum list of the bundled
libraries; `docs/UPGRADE-ROLLBACK.md` asks you to verify it before packaging, but no script of the repository does (the self-check of section 1.1 does verify, inside the built war, the five jars that a standard server lacks).

The GitHub workflow (`.github/workflows/offline-contracts.yml`) has two jobs: the same command on a self-hosted runner labelled
`ebx-connectors` with `EBX_LIB_DIR` set (forks are skipped), and the dependency check (`tools/deps_check.sh --require-osv`, with OSV) on a
GitHub runner. No acceptance run on a real EBX is part of it. **State, 2026-11-12:** that runner was never registered, so the first job has
never run on GitHub, and the workflow file was an invalid YAML from 2026-10-24 until the wave of 2026-11-12, so the second one had never run
either. The second should run at the next push; the first needs the runner to be registered. Read the first execution of each before relying on it.

### 2.2 Deploying

1. Deploy as a Tomcat web application with context path `/ebx-connectors`: the WAR, or (what the owner's workstation
   does) a context file pointing at `src/main/webapp` with `target/classes` mounted at `/WEB-INF/classes`
   (`ebx-ps-fasttrack-server/conf/Catalina/localhost/ebx-connectors.xml`). On the workstation the module is a copy in the
   FastTrack workspace, kept in step with this repository by `tools/install.sh` (below).
   The first start of a freshly rebuilt Eclipse project may skip the module listener: wait for Eclipse to finish.
   **`tools/install.sh [--dry-run]`** (repository root; `EBX_WORKSPACE` = the `ebx-ps-fasttrack` workspace folder, default the
   FT 6.3 RC one) installs this repository's module into that workspace. Run it from a clone or a worktree of `main`,
   never from a feature branch you do not mean to deploy, and **with the workspace's Tomcat stopped**: it refuses when a
   Tomcat whose `-Dcatalina.base` is `<workspace>/ebx-ps-fasttrack-server` runs (a class replaced under a running Tomcat
   gives `NoSuchMethodError`). Steps: (a) reads the version (`tools/version.sh version`) and the commit from the repository,
   because the copy has no `.git` and would be stamped `0.0.0-unknown`; (b) `rsync -a --delete` of `ebx-connectors/` into
   `<workspace>/ebx-connectors/`, excluding `/target` (rebuilt next), `/dist`, `/.runtime-libs-replaced` and `/src/main/webapp/WEB-INF/lib` (neither copied nor deleted by rsync);
   (b2) **since 1.4.1** `tools/sync_runtime_libs.py` puts the jars of `runtime-libs/` into `<workspace>/ebx-connectors/src/main/webapp/WEB-INF/lib`: a missing jar is ADDED, another version of the same library
   (same file name without its version: `jackson-core-2.14.2.jar` becomes `jackson-core-2.18.12.jar`; `snappy-java.jar` with other bytes) is REPLACED after a copy to `<workspace>/ebx-connectors/.runtime-libs-replaced/<time>/`, and a jar of no known library
   (such as `ebx-connectors-extension-sample.jar`, or a customer's driver) is LEFT untouched; the three lists are printed, `--dry-run` prints them without writing. A stale library of a family the module ships (a second netty line) is left too: read the `LEFT` list. The script also amends the project's Eclipse `.classpath` (which the rsync no longer overwrites): a `kind="lib"` entry for `jackson-core`, `-databind` and `-annotations` (the only jars the code needs to compile; `jsr310` and the YAML jars are reached at run time only) is put BEFORE the `/ebx-ps-fasttrack-server` project entry, because Eclipse resolves in order and that project exports the Jackson 2.14.2 of its `lib/`; entries of another version of those jars are removed, nothing else is touched, a second run changes nothing, the first change leaves `.classpath.bak-<date>`. With no `.classpath` in the workspace project nothing is created and the output says so. **Refresh the project in Eclipse (F5) after the installation.** If Eclipse is opened on a workspace whose `.classpath` was not amended, its builder recompiles `target/classes` against Jackson 2.14.2 and writes classes with "Unresolved compilation problem" (symptom: the module fails at start or on its first call with `java.lang.Error: Unresolved compilation problem` naming `StreamReadConstraints`): stop Tomcat, run `tools/install.sh` again, refresh (F5) and clean the project; (c) in the copy,
   `EBX_LIB_DIR=<workspace>/ebx-ps-fasttrack-server/lib EBX_BUILD_DIR=<copy>/target EBX_BUILD_VERSION=... EBX_BUILD_COMMIT=...
   ./build-test.sh` (full offline suite, about 90 s; the main classes land in `<copy>/target/classes`, the folder the Tomcat
   context mounts on `/WEB-INF/classes`); (d) checks that `target/classes/com/on/ebx/connect/version.properties` carries the
   repository's version and commit, that there are classes, and that `WEB-INF/lib` holds every jar of `runtime-libs/` byte for byte; (e) prints
   `installed ebx-connectors <version> (commit ...): N classes, M runtime jars ..., copy <path>`. It starts nothing. A failed
   build leaves the copy synchronised and `target/` incomplete: do not start Tomcat. `--dry-run` runs the Tomcat check and the
   version read, then prints the rsync command, the files whose content differs and the build command, and writes nothing.
   The log is `${TMPDIR:-/tmp}/ebx-connectors-install-<time>.log` (`EBX_INSTALL_LOG`). Version overrides, for a tree without
   `.git`: `EBX_BUILD_VERSION`, `EBX_BUILD_COMMIT` (read by `tools/version.sh` and so by `build-test.sh`;
   `EBX_CONNECTORS_VERSION` is the older name of the first). `build-test.sh` finds the server `lib` at
   `<module>/../ebx-ps-fasttrack-server/lib` (the module in `<workspace>/ebx-connectors`); from a clone elsewhere, set
   `EBX_LIB_DIR`. Never pipe the script into `head`.
2. `WEB-INF/ebx/module.xml` declares the module (`ebx-connectors`) and three workflow beans
   (`ConnectorScriptTaskBean`, `ProductPublishTaskBean`, `ChatWorkItemTrigger`). It is read **only at startup**: a
   change needs a restart. `web.xml` registers the REST application `/ebx-connectors/rest` (`/v1/...` and `/mcp`).
3. JVM options of section 3, notably the security ones of section 4, in the Tomcat `setenv` or launcher.
4. Restart. At startup the module creates the dataspace and dataset `Connectors` (owner `administrator`), seeds them
   (section 2.3), installs the perspective, registers the MCP server, starts its background work (section 5) and marks
   runs left RUNNING as INTERRUPTED.

### 2.3 What the first start puts in the repository, and the manual steps after

- **Seeded content** (`ConnectorsInstaller.install`, additive; it also runs lazily from entry points, cheap after the
  first complete run): about 25 seed groups, at least 61 connector ids (GLEIF, Sirene, VIES, Salesforce, SAP, Kafka at
  `localhost:9092`, EBX at `http://localhost:8080`, H2, Snowflake, Databricks, OpenAI, Gemini, Ollama, Slack, the EBX MCP
  connectors...), categories, packaged templates, release notes, and demonstration mappings on `/root/Companies` when
  that table exists. Secrets are left as `TO_BE_SET`. The "Documents" connection is seeded only while the `documents` preview function is open, and with no language
  model (section 4, "Preview functions"); the "Redshift" connection is seeded whatever is open, and refuses to run while `redshift` is closed. **Each connector, and each group of demonstration mappings added
  apart from its connector, is created once** (audit C-26): the installer records it in the table `SeedMarker`
  (`connector:<id>`, `mappings:<group>`, `migration:<name>`, `baseline`) and never creates it again, so one you delete
  stays deleted. At the first start of the version that has the table, every connector already in the repository is
  recorded (`seed markers recorded for N existing item(s)` in the kernel log); one you deleted *before* that start, while
  the server ran, comes back once at it. To have the installer create an item again, delete its `SeedMarker` row.
  **`-Debx.connectors.seed=none` creates no connector, no mapping and no catalogue card at all** (default `all`): set
  it on a production installation before the first start, so that it holds only what its owners made. Categories
  and release notes are product content, not demonstration, and are still installed. The packaged templates are NOT: each
  needs a seeded connection, so with `none` the table `ConnectorTemplate` stays empty and the installer logs one
  `packaged profile product.<x> skipped: connector <X> is not installed` warning per template at each start (measured
  2026-11-21 on a blank instance: 7 templates by default, 0 with `none`). A model that
  does not have the table yet (not reloaded after the upgrade) behaves as before: an absent seeded connector is created at every
  start. `-Debx.connectors.seedMappings=never` turns off only the demonstration **mappings**.
- If a dataset named `Connectors` exists on another module's model (an older FastTrack installation), startup stops and
  logs `installation stopped: dataset Connectors uses the data model of another module`; nothing is deleted. Export
  (`GET /rest/v1/config`), migrate explicitly, import.
- **Enter credentials**: Data > Connectors in the "Data connector" perspective, or references (`vault:`, `azurekv:`,
  `awssecrets:`, `gcpsecret:`, `env:`, `file:`; GUIDE §3.1.1).
- **Give rights**: the "Access rights" table (section 4). Until you do, only EBX administrators can use the module.
- **Workflow models**: `tools/export/workflows/` holds `CompanyCreationEnriched.json` and `DemoEnrichmentConflicts.json`;
  nothing imports them automatically. The `ConnectorEnrichment` model that the README and GUIDE name is **not** in that
  folder (REFERENCE.md §6.3 says so too). The beans must be declared as **library** tasks of module `ebx-connectors`.
- **Scheduled tasks** (EBX Administration > Task scheduler, or the Scheduler REST API): nothing is registered
  automatically. Two task classes: `com.on.ebx.connect.ebx.scheduler.ConnectorScheduledTask` (parameters `connector`,
  `operation`, `direction`, `dataspace`, `dataset`, `tablePath`, `predicate`, `maxRecords` default 100, `dryRun`,
  `scope`, `inputs`, `tolerate`) and `RestSchemaRefreshScheduledTask` (`connector`, `schemaUrl`,
  `minimumIntervalMinutes` default 60). A scheduled run now **fails** unless it is a plain success (errors, rejected,
  conflicts, a read cut at `maxRecords`, a stop); `tolerate` accepts causes (`errors`, `rejected`, `conflicts`,
  `truncated`, `stopped`, `all`). Field notes §2: through the Scheduler REST API the body is a bare JSON array, never
  set `userReference`, a schedule's `start` is read as UTC, and a "pause all" marker pauses groups created afterwards.
- **Table triggers** on customer tables (`ConnectorTableTrigger`) and workflow steps are configured in the customer's
  model or workflow; see section 5 for what a trigger does to a transaction.
- **Perspective**: "Data connector", created when absent and never modified afterwards; disable with
  `-Debx.connectors.perspective=false`. Who may select it is a per-profile permission set in Administration.
- **Test the module**: `tools/smoke.sh` (section 2.4), the integration suite `tools/itest.sh` on a disposable instance (section 2.5), then `POST /ebx-connectors/rest/mcp` with `connectors_help`, or
  the Connection catalog screen.

### 2.4 Checking an installation (audit C-09, part)

`tools/smoke.sh <base-url>` is the check to run after every install or upgrade, from any machine that can reach EBX. It
reads only (`GET /ebx/`, `GET /ebx-connectors/rest/v1/status`, MCP `initialize` and `tools/list`), writes nothing and
calls no connector. Credentials come from the environment, never from an argument: `EBX_AUTH=user:password` for an
account that holds a Connectors right (an administrator does), optionally `EBX_AUTH_NOROLE=user:password` for an account
that holds none. They reach curl through a mode-600 temporary file that is removed when the script ends. It prints
one `PASS`/`FAIL` line per check and exits non-zero on any `FAIL`:

| Check | Passes when |
|---|---|
| EBX answers | `/ebx/` answers 2xx or 3xx |
| Status is not public | `GET /v1/status` without credentials answers 401 |
| Status fields | with `EBX_AUTH`: 200, `module` is `ebx-connectors`, `version`, `installation`, `egress`, `retention`, `runs`, `asyncPool`, `offUntilConfigured` present |
| Installed | `installation.datasetPresent`, `modelPresent` and `installerCompleted` are all true |
| Version | `version.version` is not `unknown` (an Eclipse build fails here, on purpose), and equals `EXPECT_VERSION` when that is set |
| MCP | `initialize` answers the same version in `serverInfo.version`; `tools/list` lists at least one tool for the account |
| No rights | with `EBX_AUTH_NOROLE`: status answers 403 and `tools/list` answers 403 or an empty list |

Without `EBX_AUTH` the first two run, the others print `NOT RUN` and the exit code is 2: a smoke test that did not run
everything has not passed. `SMOKE_CACERT` names a CA file for an `https` URL (certificates are never switched off);
credentials in clear over `http` are refused unless the host is this machine or `SMOKE_ALLOW_PLAIN_HTTP=1`. The script
and its checks were exercised against a stand-in server and, for the two public checks, against the owner's running
instance (2026-10-06); the authenticated checks have not yet been run against a real EBX.

### 2.5 Integration tests (audit C-09)

Every other test of this repository is an offline `main()`: none opens an EBX transaction. `tools/itest.sh` is the
suite that does, against a running instance. **It writes to the instance. Run it on a disposable or staging server,
never on a production one**, and not while other people rely on the Connectors rights (see "Temporary right" below).

```
EBX_AUTH=admin:password EBX_AUTH_NOROLE=user:password tools/itest.sh http://localhost:8080
```

`EBX_AUTH` is an EBX administrator. `EBX_AUTH_NOROLE` is an account that holds **no** Connectors role and no
`AccessRight` row (for example `user:user`); without it scenarios 2 and 3 are skipped. Both come from the environment
only, never from an argument; they stay in memory, travel in an `Authorization` header and are never printed. Needs
`python3` (standard library only, no curl). `ITEST_CACERT` names a CA file for an `https` URL (certificates are never
switched off); credentials in clear over `http` are refused unless the host is this machine or `ITEST_ALLOW_PLAIN_HTTP=1`;
`ITEST_RIGHTS_WAIT` (default 6.5 s) is the pause after each change of a right, the module caching `AccessRight` for 5 s;
`ITEST_VERBOSE=1` prints one line per request on stderr. It should take under a minute, most of it waiting for that cache (not measured on a real instance).

Each check prints `PASS`, `FAIL` or `SKIPPED` with the reason. Exit code: **0** every check passed, **1** a check
failed, **2** something did not run (no `EBX_AUTH`, no `EBX_AUTH_NOROLE`, or a scenario skipped for the reason printed:
the same rule as `smoke.sh`, a run that did not run everything has not passed).

| Scenario | What it does | Passes when |
|---|---|---|
| S1 status | `GET /v1/status`, MCP `initialize` and `tools/list` as the administrator | status carries its blocks, names the module, reports a version; MCP answers the same version and lists the tools |
| S0 setup | Creates the scratch objects below | each one is created |
| S2 no role | As `EBX_AUTH_NOROLE`: status, `tools/list`, a tool call, a `PUT` on a scratch connection | status 403; no tool listed; the call refused; the `PUT` refused (4xx) |
| S3 rights by level | Adds a temporary `Beveryone` row to `AccessRight` (READ, then RUN, then MANAGE, changed with `PUT`, removed at the end) and repeats the no-role account's calls | READ: connections listed, `tools/list` without `connectors_sync`, a synchronisation refused (403 and MCP). RUN: `connectors_sync` listed, a dry-run feed runs, editing a connection and `connectors_mapping_upsert` are refused. MANAGE: the configuration tools are listed, a scratch mapping is created, edited and deleted, a connection is edited |
| S4 feed | On the scratch mapping (below): a dry run, a real run, a second run, a run whose source cannot be read | dry run counts 5 items and writes none; real run creates 5 rows, read back through Data Services with the right values; second run creates 0 and fails on none; the unreadable source ends `ERROR` with HTTP 422 and writes nothing |
| S5 posture | Status fields, the status of a run, a connection to `127.0.0.2:1` | `retention` and `egress` blocks present; `GET /runs/{id}` gives `DONE` with its counters; calling the private-address connection is refused with "refused ... loopback or private address" |
| S7 boundaries (C2-21) | Anonymous calls and the no-role account on the webhook and the other entries; a connection holding a clear secret (the private-address guard is S5.4) | webhook without credentials 401, with the removed secret/signature headers 401, health check 401; the no-role account gets 403 on the webhook, `/connectors`, `/mappings`, `/sync`, `DELETE /mappings`, `/runs` and on three tools; the clear secret is refused at use (skipped when the instance allows clear secrets) |
| S6 verification | After the clean-up, as the administrator | no connection, property, mapping, category row, log line, provenance row or run with the run's prefix or ids remains; the `AccessRight` table is as it was; the scratch dataspace is closed |

**What it creates**, every name carrying the prefix `itest-<yyyymmddhhmmss>-` printed on the first line, all registered
for removal the moment each exists and removed at the end **even after a failure** (a `try/finally`; a removal that
fails is retried once and reported as `FAIL: clean-up of ...`):

- a scratch **dataspace** `itest-...-ds`, child of `Connectors`, created with `:createDataspace` and deleted with
  `:close` (`deleteHistoryOnClose` and `deleteDataOnClose`); the feed writes its 5 `ConnectorCategory` rows there, so
  the `Connectors` dataspace itself never receives them. A dataset cannot be created over REST, but a child dataspace
  inherits its parent's datasets. If `:createDataspace` is refused, the feed writes the same rows with the run prefix
  into `Connectors` and the clean-up deletes them (the line says so);
- two scratch **connections** of type `sample-file` (`...-src` serves a 5-row JSON file from its own configuration, no
  network; `...-bad` stores a file that is not valid base64), one of type `rest` pointing at `127.0.0.2:1` (`...-priv`,
  only ever refused before any connection is attempted);
- scratch **mappings** `...-feed` and `...-badfeed`, plus `...-rights-manage` (and `...-rights-run`, only if a RUN
  account were wrongly allowed to create one);
- the **runs** it starts: their `ExecutionRun` row and `SyncLog` lines (matched by run id, or by the scratch dataspace
  key), deleted through Data Services; the feed has `provenance` off, so no `FieldProvenance` row is written;
- one `AccessRight` row for `Beveryone`, only when none exists.

It never changes or deletes anything it did not create: if an `AccessRight` row for `Beveryone` already exists, S2 and
S3 are skipped (every account would hold a right) and the row is not touched. It does not sweep objects of an earlier
run that was killed (`kill -9`, power loss): find them by the prefix (`starts-with(./id,'itest-')` on `Connector`,
`./label` on `Mapping`, `./comment` on `AccessRight`) and delete them by hand. Residual: creating a child dataspace makes
EBX take a snapshot of the parent; whether `:close` also removes it was not established, check the snapshots of the
`Connectors` dataspace after the first run.

**Temporary right.** To test the levels with an account that holds no role, the suite gives *everybody* a right through
a `Beveryone` row, for the roughly 25 seconds of S3, up to MANAGE. During that time any authenticated user of the
instance holds that right on the module. That is the reason for a disposable or staging server.

**What S3 needs from EBX.** The module's right is not the only gate: a person also needs EBX permission on the
`Connectors` dataspace and dataset (section 4). When EBX itself answers "Access to dataspace/dataset ... is not
allowed" for the no-role account, S3 is reported `SKIPPED` with that reason (the suite never grants EBX permissions;
give the account read permission on the dataspace to run the READ and RUN levels). At MANAGE, creating a mapping also
needs EBX write permission on the dataspace; when EBX refuses it (the answer is not the module's "requires the
Connectors right"), S3.11 is `SKIPPED`, and a `403` on the `PUT` of a connection (S3.14) is `SKIPPED` too, because
the module's refusal and EBX's cannot be told apart on that route.

**What it does not cover**: any connector type other than `sample-file` and the private-address refusal of `rest`
(none calls an external service), asynchronous runs, outbound deltas, replay, conflicts, webhooks, data-product
publication, scheduled tasks, workflow beans, the user interface, or a cluster (the rights cache is per JVM). It was
written against the code and the owner's field notes and exercised only against a stand-in server
(`src/test/python/test_itest_suite.py`, run by `build-test.sh`: parsing of answers, exit codes, clean-up after a failure,
refusal of credentials in clear); **the first run on a real instance is the real test of the suite itself**, and a
`FAIL` there may be a wrong expectation of the suite as much as a fault of the module: read the line before concluding.

### 2.6 Installing on an instance that is not a FastTrack workspace (audit C3-20)

> **What was measured, and what was not (2026-11-12).** *Measured by the audit, on a 6.3.0-gp instance built from the standard distribution only* (passes 1 to 3 of the
> README of the audit, not shipped with the module): the war as it was built before this change started with ON EBX and was dead
> (`NoClassDefFoundError` on Jackson); with the three Jackson jars added by hand, a clean install of the module started in 27 s with 0 ERROR, with and without Match and Merge and DAMA.
> *Measured offline for this change:* the war built with Jackson and SLF4J inside passes the self-check of section 1.1; a JVM whose class path is the war plus `ebx.jar` plus Tomcat's
> `servlet-api.jar` (no FastTrack jar) registers the 96 connector types, which the same JVM on the war as built before this change cannot (`NoClassDefFoundError` on `JsonNode`
> in `ConnectorRegistry`). *Measured afterwards, same day, with the war of the package as built (no jar added by hand):* a clean install on that standard instance, with the two sibling
> modules, started in 28 s with 0 ERROR and `/rest/v1/status` answered 200; 37 s and 0 ERROR with Match and Merge and DAMA added. *Not done:* the war with Jackson inside on a
> FastTrack Tomcat; no connection to a real product was run, so no driver has logged through the SLF4J API shipped without a binding on a server. `tools/install.sh` (section 2.2) is for a FastTrack workspace only: it stops with "neither ebx-lib.jar nor ebx.jar" on any other folder.
>
> **Prerequisite of other modules.** The MDM Factory, if installed beside this module, needs the ON EBX MCP servers to be switched on: they are closed by default on a new instance
> (a button of Administration > MCP servers); that is the Factory's guide to detail, nothing in this module was changed for it.

1. **Get the war.** From a clean checkout of the release tag, `ebx-connectors/tools/package.sh` (section 2.1) writes
   `ebx-connectors/dist/ebx-connectors-<version>.zip`; unzip it and check `shasum -a 256 -c SHA256SUMS`. The war is about 96 MB and carries its own
   libraries in `WEB-INF/lib` (section 1). Building needs `EBX_LIB_DIR` pointing at the `lib/` folder of an ON EBX server (`ebx.jar` or `ebx-lib.jar`, and `ebx-addons.jar`).
2. **What the server must provide** (the war does not carry them; Jackson and SLF4J it carries itself, section 1.1):
   - ON EBX 6.3 on Tomcat 10 or later (Jakarta EE 5, `jakarta.ws.rs`). The classes are Java 8 (major 52): any JVM that runs the server; the Collibra
     PATCH call needs Java 11.
   - In the server `lib/` (Tomcat `common.loader`): `ebx.jar` (the EBX API; `ebx-lib.jar` on FastTrack) with its `jakarta.ws.rs`, and Tomcat's servlet API. That is all a standard
     installation has (plus a database driver and `jakarta.mail`): Jackson 2.18.12 and the SLF4J API are in the war (section 1.1), no longer taken from the server.
   - **Optional, at run time:** `ebx-addons.jar` and the add-ons it belongs to. Match and Merge (`com.orchestranetworks.addon.mame`) is reached only by
     `GoldenRecords` / `MameGoldenRecords`, which catch the missing classes: without it the `golden` and `mameState` transformations, `matching=afterApply`
     and `off`, and `publish=golden` answer with a refusal. DAMA (`com.orchestranetworks.addon.dama`) is reached only by `ReflectiveDamaAssets` and only
     when `documents` is open: without it a DAMA read or `annotate` answers with a refusal. The catalog (`ebx-data-catalog`) and the `ebx-assistant` web
     application are optional too (section 1). **Rehearsed 2026-11-12:** the war of the package starts on an instance without these add-ons (28 s, 0 ERROR) and with Match and Merge and DAMA (37 s, 0 ERROR).
3. **Deploy** with context path `/ebx-connectors`: the war as `webapps/ebx-connectors.war`, or a context file
   `conf/Catalina/localhost/ebx-connectors.xml`:
   ```xml
   <Context docBase="/opt/ebx/modules/ebx-connectors.war" reloadable="false"/>
   ```
   (an example, not rehearsed). `module.xml` and `web.xml` are read at start only. To replace the war: stop Tomcat, delete the exploded
   `webapps/ebx-connectors/` folder and the Tomcat work copy, put the new war, start. **Do not redeploy the context or touch the context file of a running
   instance, and never while it boots**: what the module's threads do to EBX when their context is reloaded has not been measured for this version.
4. **Libraries the customer provides.** In the war: PostgreSQL (also used by Greenplum), H2, Oracle (`ojdbc8`), SQL Server (`mssql-jdbc`), MySQL Connector/J
   (also SingleStore; its licence is an open point, section 9), Kafka, JSch, the messaging clients, MongoDB, Cassandra, Couchbase, Azure Service Bus (the list is
   `runtime-libs/THIRD-PARTY-NOTICES.md`). Not in the war, to be supplied for the connection types that need them (read from the connector descriptors, 2026-11-12;
   a type whose driver is missing is not registered, the others keep working): **Amazon Redshift** (`com.amazon.redshift:redshift-jdbc42` 2.1.x, class
   `com.amazon.redshift.jdbc42.Driver`), Snowflake, Databricks, IBM Db2 LUW, Teradata, SAP HANA, SAP ASE (jConnect), Vertica, InterSystems IRIS, MonetDB,
   Firebird (Jaybird) and Actian Ingres / Vector. Put a driver in the module's own `WEB-INF/lib` (the exploded folder: it is lost when the war is replaced, so
   repeat it at each upgrade) or in the server `lib/`; the driver class is never taken from a connector property. A Redshift connection also needs the `redshift` word of
   section 4, "Preview functions".
5. **The JVM properties that matter on a first installation** (Tomcat `setenv`; the module reads nothing from `ebx.properties`; every other property has a
   default, section 3.1):

   | Property | Why |
   |---|---|
   | `-Debx.connectors.seed=none` | production: no demonstration connector, mapping or catalogue card (section 2.3). Before the first start. |
   | `-Debx.connectors.egress.privateExceptions=file:/etc/ebx/connectors-private.txt` | only if a source is on a private address (H2, Kafka, Ollama, the EBX server itself). **The `file:` prefix is mandatory**: without it the path is read as one entry that matches nothing, and every private target stays refused (the kernel log and `/v1/status` say so since 1.4.0). |
   | `-Debx.connectors.allowedHosts=file:/etc/ebx/connectors-hosts.txt` and `-Debx.connectors.egress.requireAllowList=true` | recommended where the connectors must only call named hosts (same `file:` rule). |
   | `-Debx.connectors.secrets.dir=<folder>`, `-Debx.connectors.secrets.envAllowed=<names>` | only if secrets are read from files or the environment, or a Google `serviceAccountFile` is used: unset, `file:` and `env:` references and `serviceAccountFile` are refused. |
   | `-Debx.connectors.preview=<words>` | only for the preview functions you open (section 4). |
   | `-Debx.connectors.nodeId=<name>` | when several JVMs share one repository (a cluster, or two JVMs on one host). |
   | `-Debx.connectors.log.retentionDays=<n>` | the default purge is ON at 90 days; `0` turns it off. |
   | `-Debx.connectors.documents.roots=<folders>` | only with `documents` open and folders to read. |

6. **First start and checks.** Read the kernel log (`[connectors]` lines, section 7.2). `GET /ebx-connectors/rest/v1/status` answers 401 without credentials and,
   as an administrator, `installation.installerCompleted` true, `version.version` as installed, `preview.open` as set. Run `tools/smoke.sh` (section 2.4). Then give
   the rights (section 4): until a row of "Access rights" or a default role gives one, only EBX administrators use the module.

## 3. Configuration reference

### 3.1 JVM properties

Searched in `src/main/java`: `System.getProperty` (47 call sites), `System.getenv`, `Boolean.getBoolean`,
`Integer.getInteger`, `Long.getLong`. The module reads 68 property names of its own (`ebx.connectors.*` and
`ebx.catalog.*`), five standard AWS property names, and 20 fixed environment variable names; `env:NAME` secret
references read any other. All are JVM `-D` options; the module reads nothing from `ebx.properties`. "At use" means
read at each call, so a change needs a new JVM anyway (a property cannot change at run time) but not a module reload.

**Outbound traffic**

| Property | Default | Effect | Read |
|---|---|---|---|
| `ebx.connectors.allowedHosts` | unset = no restriction on public hosts (optional) | Hosts the module may call (`api.insee.fr, *.api.gouv.fr, kafka.internal:9082`); `none` = nothing; `file:<path>` = patterns from a file re-read when it changes (checked at most every 30 s; an unreadable file refuses everything). Checked on every HTTP call, redirect hops included, and on JDBC/SFTP/Kafka base URLs when the connector loads. A kernel WARN says when it is unset. | At use |
| `ebx.connectors.documents.roots` | unset = no server folder is read | Folders (`,` or `;` separated) a `documents` connector may read through its `folder` property; a folder must be inside one of them (real path, symbolic links resolved). Uploads are read without it. See docs/DOCUMENTS.md. | At use |
| `ebx.connectors.egress.requireAllowList` | `false` | `true`: while no allow-list is set, every outbound call is refused. | At use |
| `ebx.connectors.egress.blockPrivate` | **on** (`false` switches it off) | Since 2026-10-06 a host that is, or resolves to (every address of the answer, on every redirect hop), a loopback, private (10/8, 172.16/12, 192.168/16, fc00::/7), carrier-grade NAT, reserved, multicast or unspecified address, or that does not resolve, is refused, with or without an allow-list. IPv4 inside IPv6 (mapped, compatible, NAT64, 6to4) is judged as the IPv4 address; a numeric host not written as four decimal parts (`2130706433`, `0x7f.1`, `0177.0.0.1`) is refused. Link-local and cloud-metadata addresses are refused whatever the settings. `false` (any case) lifts the private rule for the whole instance; prefer the exceptions below. | At use |
| `ebx.connectors.egress.privateExceptions` | unset | The private targets that stay allowed: `host`, `host:port`, an IP address (`192.168.1.5`, `[::1]:11434`) or a CIDR block (`10.1.0.0/16`, `fd00::/8`), comma-separated, or `file:<path>` (one entry per line, `#` comments, re-read when the file changes, checked at most every 30 s; an unreadable file allows nothing). A name entry covers whatever the name resolves to; an address or CIDR entry covers the resolved addresses inside it, so `localhost` (127.0.0.1 **and** ::1) needs the name. An entry never lifts link-local or metadata addresses. It does **not** turn the allow-list on: public hosts stay unrestricted. An **exact** `allowedHosts` entry also re-allows a private host, but then the allow-list restricts every other host too. The port is the one written in the URL (none = no match for an entry that names one). | At use |

**Local demo server**

Private hosts are refused by default, so a demo instance whose sources are local needs its exceptions. The module reads
only JVM `-D` options (never `ebx.properties`): add this one line to the JVM arguments of the Tomcat that runs EBX
(the VM arguments of the launch configuration, or the `java` command line of the start script), then restart:

```
-Debx.connectors.egress.privateExceptions=localhost:9092,localhost:9095,localhost:9082,localhost:8080,localhost:11434,127.0.0.1:9092,127.0.0.1:9095,127.0.0.1:9082,127.0.0.1:8080,127.0.0.1:11434
```

It covers H2 on `localhost:9092` and `:9095`, Kafka on `localhost:9082`, the EBX server itself (connectors of type EBX
and the MCP loopback) on `localhost:8080` and Ollama on `localhost:11434`, whether the connection names the host
`localhost` or `127.0.0.1` (a name entry and an address entry are different things). To edit the list without a restart
use `-Debx.connectors.egress.privateExceptions=file:/etc/ebx/connectors-private.txt` with one entry per line. A
vault (HashiCorp, Azure, AWS, Google) or LLM endpoint on a private address needs its own entry. Where the exceptions are
not wanted at all, `-Debx.connectors.egress.blockPrivate=false` is the blunt switch (link-local and metadata stay refused).
The check judges the bootstrap, broker or database address of the connection; what a Kafka broker, a Cassandra or MongoDB
cluster or a JMS failover then advertises is not re-checked (section 4).

**Finding the connections that hold a clear secret**

Three places, none shows a value: the kernel log line written at each start (`[connectors] N secret(s) are stored in clear
text: Connector1 (password), Connector2 (property oauth.clientSecret) ...`), `GET /ebx-connectors/rest/v1/status`
(`secrets.clearSecretCount` and `secrets.clearSecrets[]` = `{connector, where}`, 50 at most, refreshed once a minute),
and the refusal itself, which a mapping run or `connectors_describe` reports for the connector. `-Debx.connectors.secrets.allowClear=true`
is the stopgap that accepts them again while they are re-entered.

**Secrets** (GUIDE §3.1.1)

| Property | Default | Effect | Read |
|---|---|---|---|
| `ebx.connectors.secrets.allowClear` | **`false`** (`true` accepts clear secrets) | A password or API key that is neither EBX-encrypted, a reference nor a placeholder is refused at use, with a message saying to re-enter it in the Connector form or use a `vault:`/`env:`/`file:` reference; the same holds for a `ConnectorProperty` whose key names a credential (`oauth.clientSecret`, `oauth.refreshToken`, `oauth.privateKey`, `tls.*Password`, `serviceAccountJson`, `sasl.jaas.config`...) when it is not a reference, checked when the connector is built. `true`: accepted, one kernel WARN per JVM. The connections concerned are listed at startup (kernel log) and by `GET /v1/status` (`secrets.clearSecrets`). | At use |
| `ebx.connectors.secrets.dir` | unset | The one directory a `file:` reference may point into (real path). **Also the only directory a Google `serviceAccountFile` property may name (audit C2-08, since this release; relative names resolve inside it); unset: `serviceAccountFile` is refused, use `serviceAccountJson` or a secret reference.** **Unset: every `file:` reference is REFUSED** (message names this property; `GET /v1/status` → `secrets.refusedReferences` lists the connections concerned; C2-08, 2026-10-24). | At use |
| `ebx.connectors.secrets.envAllowed` | unset | Names (or `PREFIX_*`) an `env:` reference may read, from the environment or a system property of that name. **Unset: every `env:` reference is REFUSED** (message names this property; listed in `secrets.refusedReferences`; C2-08); system properties are then not read. | At use |
| `ebx.connectors.secrets.hideProperties` | `true` | `false` shows the value of a credential `ConnectorProperty` row (audit C-05) to every session that may read the table; by default only MANAGE (designers, administrators, the system user) sees it. | At each access check |
| `ebx.connectors.secrets.cacheSeconds` | `300` | Lifetime of a resolved reference in the in-memory cache. | At each resolution |

**Request bounds** (audit C2-06; `requestLimits` of `GET /v1/status` shows the values in force)

The right is checked first (no session: 401; a session with no Connectors right: 403), then the body is read at most one byte past its bound. A refusal is coded and names the property: `413 CN_BODY_TOO_LARGE`, `400 CN_BATCH_TOO_LONG`, `400 CN_JSON_TOO_DEEP` (JSON-RPC error `-32600` on the MCP endpoint, `{code, error, property, limit}` on the REST routes). The in-JVM bridges (`InJvmMcp`, the contract) apply the same MCP bounds to the message they are given. A value that is not a positive number reads as the default.

| Property | Default | Effect | Read |
|---|---|---|---|
| `ebx.connectors.mcp.maxBodyBytes` | `4194304` (4 MiB) | Largest message or batch accepted by `POST /rest/mcp` and by the in-JVM bridges. **Documents:** the `documents` operations carry a file as base64 in this body (a 50 MB file is about 67 MB of text): to allow it, raise this to `73400320` (70 MiB) or more, knowing every caller with a Connectors right can then make the server buffer that much per request. | At each request |
| `ebx.connectors.mcp.maxBatch` | `20` | Messages in one JSON-RPC batch. | At each request |
| `ebx.connectors.mcp.maxJsonDepth` | `64` | Nesting depth of a message. | At each request |
| `ebx.connectors.rest.maxBodyBytes` | `33554432` (32 MiB) | Largest body of the REST routes that take a document: webhook, `POST /config`, `/publish`, `/connectors/openapi`, `/templates/import`. | At each request |
| `ebx.connectors.rest.maxJsonDepth` | `64` | Nesting depth of a JSON body on those routes. | At each request |
| `ebx.connectors.json.maxStringLength` | unset: the largest of 20,000,000, `rest.maxBodyBytes`, `mcp.maxBodyBytes` and `ebx.connectors.http.maxResponseBytes` (134,217,728 with the defaults; unbounded when the HTTP ceiling is 0) | Longest JSON string, in characters, that any Jackson reader of the module accepts. See "Jackson read limits" below. | At start of the module |

**Jackson read limits (lot 1.4.1, audit X3-08).** Jackson 2.15 and later refuses a JSON document nested deeper than 1000 levels, a number of more than 1000 characters, a name of more than 50,000 characters and a string of more than 20,000,000 characters (2.14 had no limit). What applies to this module:

| Limit | Value | Choice |
|---|---|---|
| Depth of what a caller sends | 64 (`mcp.maxJsonDepth`, `rest.maxJsonDepth`) | checked before any parse, `400 CN_JSON_TOO_DEEP`; unchanged |
| Depth of the answer of a called service (REST connector, language model, Kafka...) | 1000 (Jackson's) | kept; a deeper answer is not parsed into a tree (the raw text is kept; the caller sees a non-JSON answer), never a stack overflow |
| Number length, name length | 1000, 50,000 (Jackson's) | kept; a body that passes the byte bound and holds a longer one is answered `400` with a JSON-RPC parse error `-32700` on the MCP entry |
| String length | follows the byte bounds (134,217,728 by default), `ebx.connectors.json.maxStringLength` to set another | a document in base64 inside a JSON string (the `documents` operations) can exceed 20,000,000 characters on the REST routes (32 MiB) and in an answer (128 MiB); the byte bound of each entry stays the limit that governs |

The string bound is set ONCE, at start, by the first statement of the registration listener, as the default of every Jackson reader created afterwards: the module's mapper, the agent's, the OpenAPI importer's (YAML included), the validators', the factory that counts depth, and the libraries
of the war that use Jackson (Avro). Raising a body bound later without restarting the JVM does not raise it. `requestLimits.jackson` in `GET /v1/status` gives `version`, the four limits **the module's own reader carries**, `maxStringLengthSetting` (what the properties ask for)
and `readersCarryTheSetting` (false: a reader was created before the bound was applied, or a body bound changed after the start). `JsonReadLimitsTest` (in the suite, twice: defaults and `-Debx.connectors.json.maxStringLength=1000`) prints the Jackson jar and version the tests loaded and lists every source that creates a reader; a new one fails the suite until it is covered.

**Reading of the two lists `ebx.connectors.egress.privateExceptions` and `ebx.connectors.allowedHosts`** (audit X3-01). `file:` is required to read a file: `-D...privateExceptions=/etc/ebx/private.txt` (no prefix) is read as a plain list holding one entry that matches nothing, so no private target is allowed. The module does not guess: it says so. At each start the kernel log has one line per list that is set (`[connectors] ebx.connectors.egress.privateExceptions: N entries read from file … , M ignored`), a WARN when an entry looks like a path (starts with `/`, `~`, a drive letter, a backslash, or names an existing file: "the prefix file: is expected"), when entries can never match, or when the file is absent. `GET /v1/status` shows the same in `egress.privateExceptionsList` and `egress.allowListSource` (`source` = `unset`, `value` or `file`, `entries`, `ignored`, `looksLikePath`, `warning`).
**Secret managers** (the Secrets table above, continued after the request bounds)

| Property | Default | Effect | Read |
|---|---|---|---|
| `ebx.connectors.vault.address`, `.token`, `.namespace` | unset | HashiCorp Vault. Fallbacks: `VAULT_ADDR`, `VAULT_TOKEN` (then `~/.vault-token`), `VAULT_NAMESPACE`. | At use |
| `ebx.connectors.azure.tenantId`, `.clientId`, `.clientSecret`, `.identityEndpoint`, `.identityHeader` | unset | Azure Key Vault identity. Fallbacks: `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `IDENTITY_ENDPOINT`, `IDENTITY_HEADER`. | At use |
| `ebx.connectors.aws.accessKeyId`, `.secretAccessKey`, `.sessionToken`, `.credentialsFile`, `.profile` | unset | AWS Secrets Manager credentials. Order: these, then the standard `aws.accessKeyId`, `aws.secretAccessKey`, `aws.sessionToken`, `aws.sharedCredentialsFile`, `aws.profile`, then `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `AWS_SHARED_CREDENTIALS_FILE`, `AWS_PROFILE`, then `~/.aws/credentials`, then container and EC2 metadata. | At use |
| `ebx.connectors.aws.containerCredentialsRelativeUri`, `.containerCredentialsFullUri`, `.containerAuthorizationToken`, `.containerAuthorizationTokenFile`, `.disableEc2Metadata` | unset | ECS/EC2 credential lookup; fallbacks `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI`, `AWS_CONTAINER_CREDENTIALS_FULL_URI`, `AWS_CONTAINER_AUTHORIZATION_TOKEN`, `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE`, `AWS_EC2_METADATA_DISABLED`. | At use |
| `ebx.connectors.gcp.accessToken`, `.credentialsFile` | unset | Google Secret Manager. Fallbacks `GOOGLE_OAUTH_ACCESS_TOKEN`, `GOOGLE_APPLICATION_CREDENTIALS`. | At use |
| `ebx.connectors.aws.secrets.endpoint`, `.imdsEndpoint`, `.containerMetadataEndpoint`; `ebx.connectors.gcp.secretManagerEndpoint`, `.tokenEndpoint`, `.metadataEndpoint`, `.stsEndpoint`, `.impersonationEndpoint` | unset | Endpoint overrides that exist for tests: setting one lets that call go to `http://127.0.0.1` or `localhost`. **Never set in production.** | At use |

None of the `aws`, `azure`, `gcp` properties is documented in GUIDE, REFERENCE or README (searched).

**Rights and tables**

| Property | Default | Effect | Read |
|---|---|---|---|
| `ebx.connectors.role.viewer` / `.operator` / `.designer` | `ConnectorsViewer` / `ConnectorsOperator` / `ConnectorsDesigner` | Names of the three default roles. `ebx.connectors.adminRole` is the older name of the designer role and is still read. | At use; the designer name also at model load |
| (always, since audit C-01 tables) | — | The rights also apply to the **tables** of the Connectors dataset, whatever the EBX permissions say (section 4, "Rights on the tables"). The designer role property no longer switches anything on: it only names the role. | Model load |

**JDBC connections (audit C-04)**

A connector row is data a designer edits, but a JDBC URL, a driver class, a JNDI name and the `jdbc.*` driver
properties are what the server loads or runs. Each of these is refused by default; the property lists the
exception. Nothing the module seeds or the demonstration uses is refused (H2 over `tcp:`, `ssl:` and `mem:`, every
seeded URL with its default driver).

| Property | Default | Effect | Read |
|---|---|---|---|
| `ebx.connectors.jdbc.jndiPrefixes` | unset | Comma-separated JNDI prefixes allowed besides `java:comp/env/` (for example `java:/` on a container that publishes DataSources there). Any other name, or a name with `..`, is refused. | At each connection |
| `ebx.connectors.jdbc.allowedDrivers` | unset | Driver class names allowed besides the built-in table (sub-protocol to driver: `h2`, `postgresql`, `mysql`, `singlestore`, `mariadb`, `sqlserver`, `oracle`, `db2`, `iris`, `sybase`, `sap`, `teradata`, `vertica`, `ingres`, `monetdb`, `firebirdsql`, `hive2`, `snowflake`, `databricks`). A driver of a known sub-protocol must be one of its own; under an unknown sub-protocol only a driver of the table or of this list is accepted. | At each connection |
| `ebx.connectors.jdbc.allowedOptions` | unset | Option names exempted from the deny list: `INIT`, `DATABASE_EVENT_LISTENER`, `JAVA_OBJECT_SERIALIZER` (H2), `socketFactory`, `socketFactoryClass`, `sslfactory`, `sslhostnameverifier`, `sslpasswordcallback`, `authenticationPluginClassName`, `accessTokenCallbackClass`, `trustManagerClass`, the MySQL interceptors, plugins and loggers (`queryInterceptors`...), `loggerFile`, `traceFile`, `traceDirectory`, `allowLoadLocalInfile`, `allowUrlInLocalInfile`, `autoDeserialize` and their `...Arg` companions. Since audit C2-20 the deny list is completed by a name rule: any option whose name holds `keystore`, `truststore`, `wallet`, `privatekey` or `rootcert`, or ends in `file`, `path`, `dir`, `directory` or `location` (pgjdbc `sslrootcert`/`sslcert`/`sslkey`, MySQL `trustCertificateKeyStoreUrl`, SQL Server `trustStore`, Snowflake `private_key_file`, Oracle `oracle.net.wallet_location`, MariaDB `localSocket`, H2 `TRACE_LEVEL_FILE`/`RUNSCRIPT`...) is refused too; `httpPath` and names ending `profile` are not. Case-insensitive, `_` and `-` ignored, percent-encoding decoded; checked in the URL and in the `jdbc.*` connector properties. | At each connection |
| `ebx.connectors.jdbc.allowH2File` | `false` | `true` allows `jdbc:h2:file:`, `zip:`, `split:`, `~/...` and bare-path H2 URLs (a database in the server's own filesystem). `tcp:`, `ssl:` and `mem:` are always allowed. | At each connection |
| `ebx.connectors.jdbc.allowH2Code` | `false` | `true` allows, on an in-memory H2 database (which lives in this JVM), `CREATE ALIAS`, `CREATE TRIGGER`, `CREATE AGGREGATE`, `FILE_READ`, `FILE_WRITE`, `CSVREAD`, `CSVWRITE`, `LINK_SCHEMA`, `SCRIPT`, `RUNSCRIPT` in the `query` and `execute` operations. | At each statement |
| `ebx.connectors.kafka.extraAllowedKeys` | unset | Comma-separated Kafka client settings allowed besides the built-in list (audit C2-01). Only these `kafka.*` connector properties reach the client: `acks`, `linger.ms`, `batch.size`, `compression.type`, `max.request.size`, `request.timeout.ms`, `delivery.timeout.ms`, `enable.idempotence`, `client.id`, `group.id`, `auto.offset.reset`, `max.poll.records`, `session.timeout.ms`, `security.protocol`, `sasl.mechanism`, `ssl.protocol`, `ssl.enabled.protocols`, `ssl.endpoint.identification.algorithm`, retries, buffers, fetch sizes, heartbeat/poll intervals, `isolation.level`, and the `ssl.keystore.*` / `ssl.truststore.*` / `ssl.key.password` family. Never accepted, whatever is set: `bootstrap.servers` (the host is the connection's base URL, checked by the address rules), `sasl.jaas.config`, `sasl.oauthbearer.token.endpoint.url`, `sasl.oauthbearer.jwks.endpoint.url` and any key ending `.class` or `.classes`. | At each client creation |
| `ebx.connectors.kafka.keystoreDir` | unset | The one folder `kafka.ssl.truststore.location` / `kafka.ssl.keystore.location` may point into (canonical path, no `..`); unset = both refused. | At each client creation |
| `ebx.connectors.jms.allowedContextFactories` | unset | JNDI context factory classes allowed besides ActiveMQ Classic, ActiveMQ Artemis, IBM MQ, WebLogic and TIBCO EMS. JNDI names (connection factory, destination) must match `[A-Za-z0-9/._-]+` (no `:`, so no `ldap://`/`rmi://`; no `..`); no property relaxes that. Every host of the provider URL, `failover:(...)` lists included, goes through the address rules. | At each connection |

**History, retention, imports**

| Property | Default | Effect | Read |
|---|---|---|---|
| `ebx.connectors.log.storeChanges` | `false` (audit C2-03) | `true` copies changed values and outbound documents into `SyncLog.changes` (personal data); by default they are not stored. Values already stored stay (see "Values and EBX permissions" below and `UPGRADE-ROLLBACK.md`). | At each log line |
| `ebx.connectors.log.retentionDays` | unset = **purge ON, 90 days**; `0` = off | Age, in days, of the rows the purge deletes (section 6). `0` (or negative) turns the whole purge off; a value that is not a number turns it off too and the start-up log says so. | Module start |
| `ebx.connectors.retention.syncLogDays`, `.conflictDays`, `.executionRunDays`, `.publicationDays` | the general value (90) | Per-table ages; `0` keeps that table. Alone they do not turn a purge that is off (`retentionDays=0`) back on. An unreadable value keeps the table and is said in the start-up log. | Each pass |
| `ebx.connectors.retention.outboundStateDays` | unset = **kept** | N > 0 deletes the outbound states in status `DELETED` (the trace of a remote deletion that was done) older than N days. States `SENT` and `FAILED`, those of live records, are never deleted. Does not follow the general age. | Each pass |
| `ebx.connectors.retention.provenanceDays` | unset = **kept** | N > 0 deletes the `FieldProvenance` rows older than N days whose record no longer exists (dataspace gone, or record absent from its table; dataspace closed, dataset or table not served: kept). Rows of existing records are never deleted. Does not follow the general age. | Each pass |
| `ebx.connectors.retention.intervalMinutes` / `.batchSize` / `.maxRowsPerPass` | `60` / `500` (max 5000) / `50000` | Pass interval, rows per transaction, rows per table per pass. | Each pass |
| `ebx.connectors.conflicts.pageSize` | `200` (1 to 2000) | Conflicts one screen of the conflict manager shows, and one `GET /conflicts` or `connectors_conflicts_list` returns (newest first; `count` is the real total, `truncated` says more exist). The "take all" / "keep all" buttons decide the page shown; decided conflicts leave the open ones, so the next Apply takes the next page. | At each request |
| `ebx.connectors.alert.connector` | unset = **no alert** | The id of ONE existing connection through which a repeated scheduled-run failure is told (audit C-18). Unset: only a kernel log line `no alert was sent because no alert connection is chosen`, once per streak. The credentials stay on the connection; nothing is stored. | At each failure |
| `ebx.connectors.alert.operation` | `notify` | Its operation, which must take a `text` input: Slack `post`/`notify`, the Slack/Teams `chat-webhook` `notify`, or any connector's. A `title` is added when the operation accepts one. | At each failure |
| `ebx.connectors.alert.threshold` / `.reminderEvery` | `3` (min 2) / `0` | Consecutive failed scheduled runs of one mapping that trigger the message; then one more every N failures (`0` = one message per streak; a success starts a new streak). The streak lives in the JVM's memory: a restart, or another node, counts again from 0. | At each failure |
| `ebx.connectors.alert.input.<name>` | none | Fixed inputs of the operation, e.g. `ebx.connectors.alert.input.channel=#alerts`. An operation with another required input nobody gives is refused (WARN, nothing sent). | At each failure |
| `ebx.connectors.record.bookkeepingBatch` | `100` (1 to 1000) | Record-mode runs (enrichment, outbound scopes, a scheduled run that is not a feed, "whole table" from the screen, a replay, a workflow step) write the sync log lines, provenance, outbound states and metrics of their records in groups of this many records instead of one transaction per record and kind. The final rows are the same, in the order of the records. A crash in the middle of a run loses at most this many records' bookkeeping (an outbound record whose state was lost is sent again by the next `unsent`/`changed` scope). `1` writes after each record, as before (the mappings and connector configurations are still read once per run). A group that cannot be written is retried row by row. | At each run |
| `ebx.connectors.import.resultsMax` / `.errorsMax` | `1000` / `50` | Individual results and failing results kept in an import answer. | At use |
| `ebx.connectors.feed.keyIndex.maxRows` | `2000000` (`0` = off) | An import feed whose `keyPredicate` is a business key (`./sourceId='${customerId}'` on a table keyed otherwise) reads the target table once and finds its records in memory, instead of scanning the table for every row. Memory: roughly 150 to 250 bytes per record. A table with more records than this is looked up row by row (one WARN in `kernel.log`). `0` restores the scan per row. | Each run |
| `ebx.connectors.feed.childPreload` | `true` | A batched import feed with sub-object mappings reads the existing child rows of the batch's parents once and matches the child elements in memory, instead of one XPath scan of the child table per element. `false` restores the scan per element. | Each run |
| `ebx.connectors.import.async.maxConcurrent` / `.maxQueued` | `4` (min 1) / `16` (min 0) | Asynchronous imports running at once, and waiting for a thread; beyond, a start is refused (HTTP 429). | Class load: restart |
| `ebx.connectors.shutdown.waitSeconds` | `10` | Total time a module stop waits for its background threads. | At stop |
| `ebx.connectors.nodeId` | the host name | Name of this EBX node in the lease of a running import (`ExecutionRun.leaseOwner` = `<node>#<JVM start time>`). Give each JVM that shares a repository a different value, in particular two JVMs on one host. | At use |
| `ebx.connectors.run.leaseSeconds` | `1800` (60 to 3600) | How long a running import's lease lasts after each renewal (written at the start and after every batch, cleared when the run ends). A node that starts leaves RUNNING a run whose lease another node holds and has not run out. | At each batch |

**Lineage, MCP, UI, seeds**

| Property | Default | Effect | Read |
|---|---|---|---|
| `ebx.connectors.lineage.declared` | `true` | `false` stops the declared-lineage events altogether. | At use |
| `ebx.connectors.lineage.declareOnStartup` / `.startupDelaySeconds` / `.debounceMs` | `true` / `90` / `2000` | Startup sweep of every mapping, its delay, and the quiet period before a changed mapping is re-sent. | Startup / each change |
| `ebx.connectors.mcp` | `both` | `native`, `endpoint`, `both` or `none`: which MCP surface is served. | Native: at registration; endpoint: at each call |
| `ebx.connectors.contract` | `true` | `false` (any case): the in-JVM contract for sibling modules (`type=Contract`, [CONTRACT.md](CONTRACT.md)) answers every call `DISABLED`. The MBean stays registered. | At each call |
| `ebx.connectors.llm.default` | unset | Id of the `llm` connector the contract's `llm.ask` and `llm.chat` use when the caller names none **and the setting "Default language-model connector" of the Connectors dataset is empty** (section 3.2; since wave 12 the setting is the place to choose it, this property stays for an operator who sets it per JVM, e.g. before anyone has opened the dataset). Neither set: the only usable `llm` connector (active, configured, its key set) when exactly one is; otherwise the call is refused `NO_DEFAULT_LLM`. A value naming no `llm` connector is refused the same way, never followed nor skipped. | At each call |
| `ebx.connectors.perspective` | `true` | `false` skips the perspective installation. | Startup |
| `ebx.connectors.seed` | `all` | `none`: the installer creates no connector, no mapping and no catalogue card (production); any other value reads as `all`. An item the installer created once is never created again, whatever the value (`SeedMarker` table, section 2.3). | At each install |
| `ebx.connectors.seedMappings` | `auto` | `auto` (seed demonstration mappings only where their table and fields exist), `always`, `never`; any other value reads as `auto`. | At each install |
| `ebx.connectors.workItemNotifier.connector` | `Slack` | Id of the connector the work-item notification trigger posts through. | At each notification |
| `ebx.connectors.http.maxResponseBytes` | `134217728` (128 MiB; `0` = no ceiling) | Ceiling on one HTTP answer read by the shared client (`HttpJsonClient.send`, the main path of the REST connectors) when the caller gave no limit of its own. An answer above it fails the call with "HTTP response exceeds byte limit N (default ceiling; ...)". A caller with its own limit keeps it. A PATCH answer (read through `java.net.http`) is not covered. Audit C-16. | At each call |
| `ebx.connectors.publish.maxRowsPerTable` | unset / `0` = no ceiling | Operator ceiling on the rows a product publication reads per table (audit C-17). Without it, and without a `max` on the request, every row is published, in batches (connector property `publish.batchSize`, default 500). The smaller of the ceiling and the request's `max` applies; a table cut there makes the publication `PARTIAL`, never `OK`. | At each publication |
| `ebx.connectors.ebx.catalogThreads` | `4` (1 to 16) | Parallel Data Services reads of an EBX catalog walk. | At each walk |
| `ebx.catalog.dataspace` / `ebx.catalog.dataset` | `DataCatalog` | Where to detect the data catalog (same names as in the Factory). | At use |

### 3.2 Settings stored in EBX

Dataspace and dataset `Connectors`. Table keys: see section 7.

| Table | Settings that matter to an operator |
|---|---|
| `AccessRight` (label "Access rights", key `profile`) | `profile` (a directory role, or "Everyone"), `right` (`READ`, `RUN`, `MANAGE`; each includes the previous), `comment`. Only an EBX administrator may edit it. Applies within seconds, no restart. |
| `Connector` (key `id`) | `type`, `baseUrl`, `authType`, `username`, `password`, `apiKeyHeader`, `apiKey` (up to 4000 characters, EBX encryption; the UI meter wrongly warns above 255, REFERENCE §6.3), `connectTimeoutMs`, `readTimeoutMs`, `active`, `trust` (the TRUSTED write policy), `category`, `connectionStatus` (+ verified-at, fingerprint, message), `sampleData` (a file connector's uploaded sample), logo fields. |
| `ConnectorProperty` (key `connector`, `key`) | Type-specific properties as `key = value` (a plain string: `oauth.clientSecret`, `oauth.refreshToken` and similar live here **unencrypted** in the repository unless written as a vault reference; since audit C-05 an access rule hides the `value` of such a row, a key containing `secret`, `password`, `token`, `privatekey`, `apikey`, `passphrase`, `jaas`, `accountkey`, `serviceaccountjson`, `credential`..., from every session without the MANAGE right, in the user interface, Data Services and the `data-access` MCP; stored encrypted: `Connector/password` and `Connector/apiKey` only; `Connector/baseUrl` and `username` are plain). Per type: GUIDE §17 and the `docs/*-USER-GUIDE.html` files. |
| `Mapping`, `MappingInput`, `MappingRule` | Flows. Operational fields: `active`, direction, `keyPredicate`, `createIfMissing`, batch size, outbound retry (`maxAttempts`, `retryDelayMinutes`) and `deleteOperation` (not shown in the form, REFERENCE §6.3), load mode. GUIDE §2 and §10. |
| `ConnectorCategory`, `ConnectorTemplate`, `Release*` | Catalogue content, seeded; customer edits to templates are not overwritten by packaged data (UPGRADE-ROLLBACK). |
| `SchemaRefreshState`, `SynchronizationCheckpoint`, `ConnectorGovernance*` | Runtime state. REFERENCE §2.20: the checkpoint manager and governance services have no production caller. |
| `Favorite` | Per user. |
| `Settings` (a group of dataset-level fields, not a table; wave 12) | `defaultLlmConnector` ("Default language-model connector"): the `llm` connection the in-JVM contract uses when the calling module (MDM Factory, data catalog) names none. A foreign key to the connections of type `llm` (another type is flagged in the validation report and refused by the contract). Empty by default: then `-Debx.connectors.llm.default`, else the only usable `llm` connection. Hidden without a Connectors right, read only with one, changed by an EBX administrator only. Read at each call: no restart. Not part of the configuration export. |

### 3.3 What a profile run may spend (audit C-15)

The `profile` operation of Snowflake, Databricks and the JDBC family runs one aggregate statement per table, over
up to 200 columns. On a warehouse that is billed compute, and a feed's `max` does not bound it (it bounds what is
imported afterwards). Each setting below is read from the operation's input (a mapping input or a `connectors_call`
input) first, then from the connector property `profile.<name>` (table `ConnectorProperty`), then the default.

| Setting | Default | Effect |
|---|---|---|
| `maxTables` | `200` (`0` = no limit) | Tables profiled per run. The tables left out are named in the answer, `budget.skipped`, with `budget.stoppedBy = maxTables`. |
| `maxSeconds` | `900` (`0` = no limit) | Elapsed time after which no new table is started (a statement already running is not interrupted: the connector's own `timeout` bounds it). `stoppedBy = maxSeconds`. |
| `largeTableRows` | `10000000` | Snowflake and Databricks: a table whose catalog row count is at or above this is profiled on a block sample. A table of unknown size (a view) is scanned in full. |
| `largeTables` | `sample` | What happens above the threshold: `sample` (default; owner decision 2026-10-06: exact below the threshold, sampled above), `exact` (scan everything in full), `skip` (name the table in `budget.skipped`, run nothing). |
| `sampleRows` | `1000000` | Size aimed at by the sample: Snowflake `SAMPLE SYSTEM (p)`, Databricks `TABLESAMPLE (p PERCENT)`, `p = sampleRows / rowCount`. |
| `approxDistinct` | `false` | `true`: distinct counts by `APPROX_COUNT_DISTINCT` (Snowflake, Databricks). |
| `dryRun` | `false` | `true`: returns the statements and the estimated work (`plan.tables[].sql`, `plan.statements`, `plan.estimatedRowsScanned`) and runs no profile statement; the catalogue statements (information schema, cheap) still run. A feed run with `dryRun` on the mapping (not the screen's dry-run button, which does not pass it to a profile read) writes nothing. |

Every column of the answer says how its figures were obtained: `method` is `FULL_SCAN` (exact) or `SAMPLE`
(`sampleSize` rows, `samplePercent`; `rowCount` is the catalog's, `nullCount` the sampled rate scaled to it, distinct
count, min and max those of the sample), `distinctMethod` is `EXACT` or `APPROXIMATE`, and `exact` is true only for
a full scan with exact distinct counts. The answer carries `exact`, `partial` and the `budget` block. JDBC sources
get the budget, the dry run and the markers, not sampling (a JDBC source does not know its row counts without
counting them). The data catalog's `method` field only knows `FULL_SCAN` and `SAMPLE`; `distinctMethod`, `exact`
and `samplePercent` stay in the answer (a run report, `connectors_call`) until that module stores them.

## 4. Security settings for production

Nothing is open by default for **callers**; several **outbound and secret** settings are permissive by default and
log one warning at the first use. The module's own guide has the long version: GUIDE §3.1.1 to §3.1.3.

| Setting | Default | What to set, and why |
|---|---|---|
| Who may call the module | Only EBX administrators, `ConnectorsViewer`/`Operator`/`Designer` role members (if those roles exist) and rows of "Access rights". The right gates every MCP tool (tools not listed to the caller are hidden), every REST route (403) and every user service. The system user (workflow script task, scheduled task, module installers) passes. | Create rows in `AccessRight` for real roles, with the narrowest right: READ sees connections (secrets masked), mappings, runs, log; RUN also runs, replays, publishes and reads outbound documents and conflicts (they carry record values); MANAGE changes configuration and calls any operation of a connection with its stored credentials. A tool the module does not know is closed (needs MANAGE). Do not give MANAGE to a role that is not trusted with the credentials. |
| The three role names | See above | Rename them only to reuse roles that already exist; the rights apply to the tables whether they are renamed or not. |
| Permissions of the `Connectors` dataspace in EBX | Owner `administrator`. Since audit C-01 (tables) the Connectors rights **also apply to the tables, whatever EBX permissions say**: an access rule on every table of the dataset hides its rows from a session without a Connectors right, shows them read only below the right that writes the table, and closes creation and deletion the same way, in the EBX screens, Data Services and the `data-access` MCP server. | Keep the dataspace permissions as they are: an access rule only restricts, so a person still needs EBX write permission on the dataspace to edit what their right allows. See "Rights on the tables" below. |
| Private hosts | **Refused by default** (2026-10-06): loopback, private, reserved and unresolvable hosts, after DNS resolution and on every redirect hop. | List the private targets the connectors really need in `ebx.connectors.egress.privateExceptions` (never switch the rule off in production). A local demo server needs the line shown under "Local demo server" in section 3.1. |
| Which outbound paths the address rules cover | Every `HttpJsonClient` request and redirect hop (REST, SOAP, OAuth token endpoints, vault and secret managers, LLM, OpenAPI import, webhooks, catalog scans), the `McpConnector` call, and on connector load (`Egress.checkBaseUrl`) the `baseUrl`, `oauth.tokenUrl` and `instanceUrl` of every connector, which is what guards JDBC and H2 URLs, Kafka bootstrap servers, SFTP, FTP/FTPS, JMS/ActiveMQ/RabbitMQ/AMQP provider URLs, MongoDB, Cassandra and Couchbase endpoints, Azure Service Bus. FTP data connections use the control host, not the address the server announces. The two types that make no outbound call are not checked at load (`Egress.callsOut`): `sample-file` (an uploaded file; its base URL such as `https://persons-sap.sample-file.local/data` is a placeholder that is never called) and `documents` (an upload, its sample document or a folder under `ebx.connectors.documents.roots`; its model is reached through the `llm` connector named in `llm.connector`, which is checked when it loads). No other type is exempt. | **Not covered**: what a driver discovers after the first connection (Kafka advertised listeners, Cassandra peers, MongoDB replica-set and `mongodb+srv` members, a JMS failover list, JDBC URL options that name another host), a second host named only in a `jdbc.*` property, and `com.on.ebx.agent.LlmClient` / `McpClient` used directly by another module (`McpConnector` checks before it calls). The check and the connection are two DNS lookups served by the JVM address cache: an answer that changes between them (DNS rebinding) is not caught. For a hard guarantee, restrict egress at the network firewall as well. |
| Outbound allow-list | Off (optional): public hosts are not restricted. | Set `ebx.connectors.allowedHosts` (a `file:` list is easiest to maintain) and `ebx.connectors.egress.requireAllowList=true` when the connectors must only call named hosts. The allow-list cannot be changed from the EBX UI. Redirects are re-checked per hop (5 at most) and credentials do not follow a redirect to another host or port. A call through the JVM's HTTP proxy is judged on its URL host only. |
| Secrets stored in clear | **Refused at use by default** (2026-10-06); the startup log and `GET /v1/status` list the connections concerned. The `value` of a credential `ConnectorProperty` row is hidden from sessions without MANAGE (audit C-05) | Re-enter each password / API key in the Connector form (EBX encrypts it) and use `vault:`/`env:`/`file:` references for secret properties; keep `ebx.connectors.secrets.hideProperties` unset. |
| `file:` and `env:` references | **Refused until declared** (2026-10-24, C2-08): no `secrets.dir` = no `file:`, no `secrets.envAllowed` = no `env:`; an unconfined WARN-once mode no longer exists | `ebx.connectors.secrets.dir=<one folder>` and `ebx.connectors.secrets.envAllowed=<names or PREFIX_*>` (restart); `GET /v1/status` → `secrets.refusedReferences` lists connections that would fail. |
| Chat webhook URL (`chat-webhook`) | Treated as a secret (C2-02, 2026-10-24): `connectors_list`, `connectors_describe`, `GET /v1/connectors` and `toString()` show only scheme and host (`https://hooks.slack.com/***`); the full URL and its path are masked by `Redaction` in logs, `SyncLog.message` and errors. `user:password@` and JDBC `;password=` are masked for every URL. | Existing connections need nothing; rotate a webhook that was readable or logged before this version. `connectors_config_export` and `GET /v1/config/export` write the URL of a `chat-webhook` as `TO_BE_SET`, like `apiKey`/`password`: after an import, re-enter the webhook URL in the Connector form (an import into an existing connection keeps its URL). |
| Log redaction cost | `Redaction.redact` reads a copy-on-write index without any lock (C2-14); 10,000 texts of 4 KB against 4,096 secrets on 8 threads: 35.8 s before, 0.8 s after. | Nothing to set. |
| Retention | **On, 90 days** | `ebx.connectors.log.retentionDays` (`0` turns it off; section 6). `SyncLog` stores no changed values or outbound documents by default (audit C2-03); `ebx.connectors.log.storeChanges=true` turns that on, and then the values are personal data to protect. |
| Cross-site protection | Always on. A POST, PUT, PATCH or DELETE on `/ebx-connectors/rest/...` (MCP included) needs an `Authorization` header, or `X-Requested-With`, or an `Origin`/`Referer` naming the same host and port; a webhook has no exemption any more (C2-10). Otherwise 403 and `[connectors] cross-site request refused`. | A client that waits for a 401 before authenticating can get this 403: send credentials with the first request. |
| Audit trail | Always on, kernel log | One INFO line `[connectors] audit: user=... tool=... mapping=... outcome=ok|error|refused ms=...` per MCP tool call or REST route asking RUN or MANAGE (never the data); refusals also `[connectors] access denied: user ...`. Actions started from screens, workflows and scheduled tasks are found in `SyncLog` and the runs. There is no history on the configuration tables (audit C-30 treated by the audit line only). |
| Webhook | `POST /v1/webhook/{feed}` takes **one mode**: an EBX session (Basic, token, SSO) of a user holding the RUN right; the import runs under that user (audit C2-10, 2026-10-24). The shared-secret (`X-Webhook-Secret`) and HMAC-signature modes are **removed**: the EBX REST Toolkit refuses a request without EBX credentials before the module's code runs, so a third party without an account never reached them, and when they did work they ran under the EBX login and password stored on the connection. The sender is a technical EBX account given the RUN right (Access rights table). A connection of type `webhook` keeps its `apiKey`, `username` and `password` fields, which are no longer read: clear them. The connector properties `webhook.signature`, `webhook.toleranceSeconds` and `webhook.replayProtection` are ignored. | 401 without credentials, 403 without the RUN right; `GET /webhook/{feed}` and `POST /catalog/preview` go through the common right check and audit line (C2-16: RUN and MANAGE). |
| SFTP, FTP | SFTP refuses to connect without `knownHosts` or `hostKey` unless `strictHostKey=false`; clear FTP refused unless `allowCleartext=true`. | Never set those two to relax. |
| JDBC and H2 (audit C-04) | Refused by default: a JNDI name outside `java:comp/env/`, a driver class that does not serve the URL's sub-protocol, URL options and `jdbc.*` properties that load a class, run SQL or read or write a file (H2 `INIT`, pgjdbc `socketFactory`/`sslfactory`..., MySQL `allowLoadLocalInfile`/`autoDeserialize`/interceptors, `loggerFile`...), an H2 URL that is not `tcp:`, `ssl:` or `mem:`, and Java-creating or file-reading statements on an in-memory H2. Properties in section 3.1 (JDBC connections) open each exception. `runtime-libs/h2.jar` is still in the WAR (an in-memory H2 runs in the server's JVM, its `mem:` URL has no host for the allow-list) `postgresql.jar` is 42.7.13 since audit wave 10 (C-27). | Only designers (MANAGE) should edit connectors; remove `h2.jar` from the WAR in production if the H2 connector is not used (not tried). Kafka and JMS (audit C2-01): refused by default, see the three `ebx.connectors.kafka.*` / `jms.*` properties in section 3.1. **Open before closing the list:** inventory the `kafka.*` keys the production instances really use (RC, Vol100k) and put the missing ones in `extraAllowedKeys`. Leave the five `ebx.connectors.jdbc.*` properties unset unless a refusal message names one you need. |
| Screens (audit C2-09, C2-17, C2-18, C2-19) | `Connector/id` accepts only `[A-Za-z0-9_.-]{1,40}` (XSD pattern, read at the next Tomcat start); a stored identifier outside it is flagged invalid, never renamed. Page scripts receive identifiers and paths as fully escaped literals. The access refusal is English or French by session locale or `Accept-Language`. The five read-only and diagnostic screens follow the perspective theme. "Synchronise" is offered only on tables with an active mapping (cached 15 s). | List identifiers outside the pattern before the upgrade: docs/UPGRADE-ROLLBACK.md, section "Connectors: screens". |
| Values in templates (audit C2-04, C2-05) | A value substituted into a feed's `keyPredicate` is quoted as a whole XPath string literal (EBX's `encodeLiteralStringWithDelimiters`): an apostrophe, quotation marks or an expression in a source value stay data. A placeholder outside apostrophes (`./id=${id}`) is refused when the mapping is saved (upsert, config import, flow contract; an error on the Mapping record in the UI) with an EN + FR message showing `./id='${id}'`; a mapping stored earlier keeps running, its placeholder now giving a literal (a number or boolean unchanged). In the `sql` input of a JDBC operation every `${...}` is a bound parameter (`:__bN`), `'${x}'` included; a placeholder where SQL takes no parameter (`GRANT ... ON ${asset} TO ${user}`) is written only when its value is a plain, dotted identifier, else the statement is not run (`SQL_PLACEHOLDER_NOT_BINDABLE`). | List the mappings concerned before the upgrade: docs/UPGRADE-ROLLBACK.md, section "Templates without injection". |
| LLM connectors | A mapping author decides what goes into the prompt; there is no per-field policy (audit C-12). | Restrict MANAGE; point an LLM connector only at hosts in the allow-list. |
| Documents and images | A `documents` connector sends the document's text to its LLM connector. With `vision=true` on both connectors, it sends the image or scanned PDF itself (faces, signatures, identity numbers may reach the provider); both are `false` by default. Values read from an image are capped at `vision.confidence.max` (0.7), below the threshold, so a steward confirms them. DAMA assets are read and annotated with the caller's session and DAMA's own rights. | Keep `vision` off unless the provider is approved for that data (or use a local Ollama model); restrict MANAGE; leave `vision.confidence.max` below `confidence.threshold`. |
| Library versions | Upgraded in audit wave 10 (C-27): PostgreSQL JDBC 42.7.13, Kafka clients 3.9.2 (last Java 8 line), JSch 2.28.7 (`com.github.mwiede` fork), Guava 33.7.2 + failureaccess 1.0.3. The Cassandra driver stays on 3.11.5, the last 3.x (4.x is another API). On an instance whose server `lib/` also holds `kafka-clients.jar`, `jsch.jar` or `postgresql.jar`, the copies in the WAR win for Connectors only (webapp class loader first). | `tools/deps_check.sh` lists versions, checks that netty is on one line and asks OSV (CI job `dependencies`, and `build-test.sh` offline); findings accepted for now are in `runtime-libs/DEPS-KNOWN.txt` (audit C2-15, lot L10: lz4 is now `at.yawk.lz4:lz4-java` 1.12.1 and netty is one 4.1.137.Final family; section 9 gives the update procedure). |
| Shared checkout / copies | — | Whatever is deployed must be compared with the repository (section 7). |

### Rights on the tables (audit C-01, tables)

The rights now also apply to the tables, whatever EBX permissions say (`ConnectorsTableAccessRule`, registered by the
schema extension of the model; a new build of the module needs a Tomcat restart, the XSD does not change). Each table has
the right that reads it and the right that writes it by hand. Below the read right the rows are hidden, between the two
they are read only (no edit, no delete, no creation: creation is closed field by field), from the write right up they are
read-write as far as the EBX permissions also allow. The EBX administrator and the system user (installers, workflow
steps, the metrics, retention and run-recovery threads) always write. A table added to the model without a decision is
read with READ and written with MANAGE.

| Tables | Read | Written by hand | Why |
|---|---|---|---|
| `Connector`, `ConnectorProperty`, `ConnectorCategory`, `ConnectorTemplate`, `Mapping`, `MappingInput`, `MappingRule`, `ConnectorGovernance`, `ConnectorGovernanceAudit` | READ | MANAGE | Configuration. The value of a credential property stays hidden below MANAGE (audit C-05). |
| `SchemaRefreshState` | READ | MANAGE | Written by `RestSchemaRefreshScheduledTask` with the scheduled task's own session: run it as a profile that holds MANAGE (an administrator does). |
| `SynchronizationCheckpoint`, `FieldProvenance` | READ | MANAGE | Run time. The engine writes them with all privileges; deleting a mapping cleans them in the designer's own transaction. |
| `Conflict`, `OutboundState` | RUN | MANAGE | Run time, they carry record values (the tools that read them ask RUN). A run writes them with all privileges; a conflict decision (RUN) goes through the conflict manager, `connectors_conflict_decide` or `POST /conflicts/{id}/{decision}`, which check RUN and then write the conflict row with all privileges, so a RUN holder decides a conflict but cannot rewrite one by hand. |
| `SyncLog`, `ExecutionRun`, `Publication`, `ConnectorOperationalMetrics` | READ | administrator | Written only by the engine (all privileges or the system user). Nobody else rewrites a log line by hand. |
| `Release`, `ReleaseItem`, `ReleaseMetric`, `ReleasePassStatus`, `ReleasePassStep`, `SeedMarker` | READ | administrator | Packaged content written by the installer. |
| `AccessRight` | everybody (unchanged) | administrator | Its own rule, unchanged; creation closed to everybody else. |
| `Favorite` | own rows (unchanged) | nobody by hand | Its own rule, unchanged; the star writes with all privileges; creation by hand closed to everybody but an administrator. |

What a RUN holder starts (a synchronisation, an enrichment, a replay, a stop, a publication, a conflict decision, a
webhook) writes its log lines, runs, provenance, outbound states, conflicts, checkpoint and publication report with all
privileges, so a RUN holder needs no write permission on these tables. A module that writes a Connectors table with a
person's session (the MDM Factory's sample-file import creates a `Connector` row) now needs that person to hold MANAGE.
Residual: on a table whose key is generated and with no mandatory field, an EMPTY row created by hand may still be
accepted (no field can be set); to be checked live.

### Preview functions (closed by default, decision of 2026-11-12; audit C3-03 to C3-06)

Four functions that arrived after the October release stay in the code but are **closed** until the server administrator opens
them (the fourth, `cdc`, since 1.4.2). One JVM property, `-Debx.connectors.preview=<words>`, a comma-separated list; the module reads it at each use, so a change
is taken at the next Tomcat start (it is a JVM option). It is not a setting stored in EBX: a designer cannot
open anything from a screen.

| Word | Opens | Closed means |
|---|---|---|
| `documents` | the connection type `documents`: reading PDF, DOCX and text, extraction, DAMA annotation, vision | no run of any operation of a `documents` connection, no seeded "Documents" connection |
| `cdc` | Debezium change data capture through Kafka: the `cdc` operation of the `kafka` connector, as a feed (REST, MCP, screen, scheduled task, workflow step, in-JVM contract) and as a direct call (`connectors_call` previewing the next batch) | nothing of it runs: no `runCdc`, no feed on operation `cdc`, no direct preview, no read of the topic for CDC; the refusal comes before the broker is contacted. **Not concerned:** the ordinary Kafka operations `consume`, `peek`, `publish`, `catalog`, `profile`, `sample` and a `consume` feed (older than November): they stay open with every word closed |
| `cdcMatching` | `matching=afterApply` and `matching=off` on a `cdc` feed (Match and Merge around a batch), and `publish=golden` on an outbound trigger. **It opens nothing without `cdc`**: matching works on capture batches and on the golden records of what they merged, so naming `cdcMatching` alone leaves it closed, and says so (below) | a `cdc` mapping that carries `matching=afterApply` or `matching=off` is refused when saved and when run; a trigger or setting with `publish=golden` is refused when it is registered (the model does not compile) and when it fires (the user's save is refused with the coded text, nothing is queued); a queued `publish=golden` row is not resolved |
| `redshift` | the connection type `redshift` | no run of any operation of a `redshift` connection |

Absent, empty or misspelt means everything closed. The spelling is exact (`cdcMatching`, not `cdcmatching`); a word that is none of
the four opens nothing and is logged once as a WARN (`names [...], which is not a preview function`). `cdcMatching` without `cdc` opens nothing: the start-up log says
`names cdcMatching without cdc: cdcMatching opens nothing`, and `GET /ebx-connectors/rest/v1/status` shows `preview.cdcMatchingWithoutCdc: true`. The same block shows
`preview.open`, `preview.closed`, `preview.unknownWords` and the property name; the start-up log has one line `preview functions open: ...`.

What a user sees when a function is closed: the run, the call (`connectors_call`, `connectors_sync`, a scheduled task, a workflow
task, a trigger send) ends with an error whose text starts with `[CN-PREVIEW-CLOSED]`, says in English then in French that the function is closed on
this server and which word to add to which property. It is a refusal, not a 500. The connection type stays known: a connection row of that
type is still listed, shown and editable, and the type still appears in the choice list; only running it is refused. Saving a `cdc`
mapping with `matching=afterApply|off` through `connectors_mapping_upsert`, the configuration import or the flow contract answers the same text
(and the Mapping input record shows it as an error in the UI). A queued `publish=golden` send stays in the outbound queue and is set aside with that text after
its attempts; it is sent again once the function is open and the row is retried.

The seeded "Documents" connection is created only when `documents` is open, and (audit C3-03) without a language model: `llm.connector`
is empty, so a person chooses the model that may receive documents (property `llm.connector` of the connection) and an `extract` without
it answers "property llm.connector must name an LLM connector". An instance that already has a "Documents" connection keeps its row and its value.

Two related changes of the same lot. **C3-04:** on a `cdc` feed, a run-time input (`inputs=` of `connectors_sync`, a scheduled task, a workflow task) can no longer replace what
the mapping configures (`deleteMode`, `deleteFlagField`, `matching`, `matchingPolicy`, the topic...): only `maxEvents` and `maxBytes`, and only to a smaller
value; every other one is dropped and named in the run's message and in a WARN (`Run-time input(s) ignored...`). **C3-06:** the JDBC connection path now refuses a Redshift URL in the
cluster-identifier form (`jdbc:redshift:iam://my-cluster:eu-west-1/db`) and the options `plugin_name` and `CredentialsProvider`, in the URL or as `jdbc.*`
properties, with a `JDBC: ... is refused` error before any driver is loaded (the Redshift check used to live in `validateConfiguration`, which nothing called). A Redshift
connection stored with such a URL, which could not have worked as designed, fails at its next use with that text.

**To keep these functions on an instance that already uses them** (the test instance does), add before the first start of this version:
`-Debx.connectors.preview=documents,cdc,cdcMatching,redshift` (only the words actually in use; `cdc` as soon as a capture feed runs, before 1.4.2 it was open), in `ebx.sh` AND in the Eclipse launcher
(`Start EBX Server.launch`), like `-Debx.connectors.egress.privateExceptions`. Check `GET /v1/status` → `preview.open` after the start.

## 5. What runs in the background

All threads are daemon threads declared in `BackgroundWork` (executors created on first use, never by a class
initialiser; a late caller is refused after stop). `handleRepositoryShutdown` and `destroyBeforeUnregisterModule` both
call the same stop: unregister the in-JVM MCP and contract beans, ask running asynchronous imports to stop at their next batch,
stop the executors and wait up to `ebx.connectors.shutdown.waitSeconds` (10) in total, drop the cached connector
instances (closing e.g. a Kafka producer), close the metrics observer. A thread still running is logged
(`[connectors] still running after the stop of the module: [...]`) and its run is marked INTERRUPTED at the next start.

| Thread / hook | Started | What it does | Frequency / bound |
|---|---|---|---|
| `ebx-connector-import-async-<n>` | first asynchronous import | Runs feed imports started with `async` (REST `?async`, MCP). | 4 at once, 16 waiting (properties above), idle threads released after 60 s |
| `ebx-connectors-retention` | start, unless `retentionDays` is 0 | The purge of section 6. | first pass after min(5, interval) minutes, then every 60 min |
| `ebx-connectors-declared-lineage` | first declaration | One thread: sends OpenLineage JobEvents for mappings after a change (debounce 2 s) and sweeps every mapping at start (after 90 s, 3 attempts, 120 s apart). A refused event never fails anything. | on change; once at start |
| `ebx-connector-inspection` | first inspection | Bounded read-only inspection (diagnostics, catalog metadata), cached pool. | on demand |
| `ebx-connectors-catalog-read` | each EBX catalog walk | Fixed pool of `catalogThreads` for Data Services reads; created and closed per call. | per walk |
| `ebx-connectors-perspective` | start, if the first attempt fails | Retries the perspective installation (EBX Manager not ready). | 10 attempts, 30 s apart, then stops by itself |
| Connector client threads | when a connector is first used | A cached Kafka producer or consumer has its own network thread; closed at invalidate or stop. A configuration change does not close the old Kafka producer (audit C-45). | per instance |
| In-JVM MCP bean | start | `com.on.ebx.connectors:type=InJvmMcp` lets other modules of the same JVM call the tools as the caller's session; unregistered at stop. Not usable from remote JMX. | — |
| In-JVM contract bean | start | `com.on.ebx.connectors:type=Contract` (contract `ebx-connectors.sibling`, version in the attribute `ContractVersion`): the sibling modules ask a language-model connector (right RUN) and declare, read, delete (MANAGE) and run (RUN) flows as the caller's session, on the caller's thread; unregistered at stop. A failed registration is a WARN `[connectors] in-JVM contract not registered` and the module starts anyway. Not usable from remote JMX nor over HTTP. Each `llm.ask` leaves an audit line `contract=llm.ask connector=<id>` (never the prompt), each `llm.chat` (1.1.0) `contract=llm.chat connector=<id> messages=<n> tools=<n> stop=<reason>` (counts only, never a message, a tool name or an argument); both add `capped=<n>` when the caller asked more than the connection's `maxTokens`. The flow operations leave the line of the tool they call. Since wave 12 the answer size of a model call is never more than the connection's own `maxTokens` property (1024 unless set): raise it on the connection (Connector form, properties) when a module needs longer answers. | per call; a model call waits at most its `timeoutMs` (≤ 600 s) |
| Startup hooks | each start | Installer and seeds (section 2.3), packaged product profiles, release notes, `FieldProvenance` clean-up, MCP registration, metrics observer, lineage sweep, then runs left RUNNING are marked INTERRUPTED (`[connectors] marked N run(s) INTERRUPTED at startup`). | once; interrupts only runs nobody holds, whose lease ran out, or that an earlier incarnation of this node held (audit C-44) |
| Model triggers | on the module's own tables | Mapping cascade delete, declared lineage on mapping/connector change. | per write |
| `ConnectorTableTrigger` on a customer table | per customer model | Does **not** call the remote system in the user's transaction (audit C-07, lot L8): it queues the record in `OutboundQueue` and the thread below sends it after the commit, with sync log, provenance, conflicts and lineage. Only `<blocking>true</blocking>` keeps the former call inside the transaction (a failure refuses the save, the dataspace stays locked for the remote delays, no sync log). See section 9, "Table triggers queue their sends". | per write |
| `ebx-connectors-outbound-queue` | first queued send | Writes the `OutboundQueue` rows the triggers hand over, waits `ebx.connectors.outboundQueue.settleMs` (2000) so the transaction is committed, re-reads the record and sends it (`publish=golden`: the golden of a MERGED record, only when `cdcMatching` is open); a failure keeps the row `PENDING` for the retry delay, then `DEAD`. Its backlog is `outboundQueue` in `GET /v1/status`. | every `ebx.connectors.outboundQueue.intervalMs` (3000), `batch` rows (200) |
| Change data capture and documents | the call | Run on the thread of the call (a scheduled task, a workflow step, `connectors_sync`): no thread of their own. A capture run holds its mapping (a second run is refused) and, with `matching=afterApply` or `matching=off`, pauses Match and Merge on the table for the length of the batch. | per call |

Scheduled tasks and workflow steps run on EBX's own threads (section 2.3). The metrics observer writes on the calling
thread.

## 6. Tables that grow, and retention

**The purge is ON by default, at 90 days** (decision of 2026-10-06). With `ebx.connectors.log.retentionDays` unset
the start-up log says `[connectors] retention is ON (default 90 days; ...=0 turns it off): ...`, `GET /v1/status`
shows `retention.enabled: true, onByDefault: true` and lists it in `onByDefault`; `-Debx.connectors.log.retentionDays=0`
turns it off (start-up log `retention is off`, and `offUntilConfigured` says so). One background task deletes, on its own
thread, in transactions of 500 rows with 100 ms pauses and at most 50,000 rows per table per pass, every hour (first pass
5 minutes after start). The first passes after an upgrade may delete a large backlog: they take it 50,000 rows at a time.
Each table has its own age (`ebx.connectors.retention.<table>Days`, `0` keeps that table):

| Table | Purged | Never purged |
|---|---|---|
| `SyncLog` | older than the age | — |
| `Conflict` | decided ones | `OPEN` ones, and `APPLYING` ones |
| `ExecutionRun` | `DONE`, `PARTIAL`, `ERROR`, `STOPPED`, `INTERRUPTED` | `RUNNING`, rows without a status |
| `Publication` | older than the age | — |
| `OutboundState` | only if `outboundStateDays` is set: status `DELETED` | `SENT` and `FAILED` (they describe the current state of a live record: deleting them would re-send it) |
| `FieldProvenance` | only if `provenanceDays` is set: rows whose record no longer exists | rows of existing records, however old (deleting one changes the next TRUSTED decision on the field); rows whose record cannot be looked up |

The deletes are by selected rows, in bounded transactions, never on a request thread; reading the old rows is a scan of
the table once per pass (EBX indexes only the primary key). A pass looks at most 50,000 old provenance rows and resumes
at the next pass. Nothing here stores values as digests: `SyncLog.changes` keeps no changed values unless
`ebx.connectors.log.storeChanges=true`.

**Values and EBX permissions (audit C2-03).** Values the module keeps about a record (`SyncLog.changes` and `recordLabel`,
`FieldProvenance.value`, a conflict's current and proposed values and label, an outbound state's last document and label) are
shown only to a session that can read the record they came from, under the EBX permissions of its own table: field rules on
those nodes (`OriginValueAccessRule`) hide them in the screens, Data Services and the `data-access` MCP, and
`ConnectorRepository.outboundStates(session, ...)` / `ConflictResolver.page(session, ...)` leave out the rows of unreadable records.
A value whose origin cannot be established (record deleted, dataset gone) is hidden; an administrator and the system user see all.
A log line names dataspace, table and key but no dataset: every dataset of the dataspace holding that key must be readable.
Since 1.4.2 the rule also covers `FieldProvenance.source` (where a value was read: document, page, quoted words, up to 2,000 characters) and `Conflict.comment` (the proposal's comment quotes that source, up to 500 characters;
the steward's decision comment shares the column): a reader who cannot read the origin record no longer sees the words of a document there (audit C3-02, C2-03).
`storeChanges` is off by default; values already stored are not removed (migration: `UPGRADE-ROLLBACK.md`).

**What 1.4.2 closes with the preview words** (bilan 1.4.1, gap 1). Accepting a conflict whose field starts with `dama:` writes into DAMA: it is part of `documents` and answers `[CN-PREVIEW-CLOSED]` while that word is
absent (the conflict stays open and acceptable once the word is added; rejecting it is not a DAMA write and is not refused). A trigger or setting with `publish=golden` is part of `cdcMatching` (which itself needs `cdc`): refused when
registered and when it fires. The confidence envelope (`_minConfidence`, `_confidence`, `_sources` in a document a mapping reads) belongs to `documents`: while it is closed a document that carries those names is
treated as it was before November (every rule writes, no proposal, no source kept); open, the three names are reserved for that function (CONTRACT.md, "Reserved names").

Tables that grow with activity: `SyncLog` (one line per record call; 2,000-character message, 4,000-character
`changes`), `FieldProvenance` (one row per field a connector wrote, key `dataspace, dataset, tablePath, recordKey,
fieldPath`; the Factory's landing loads write here and it grows with every load; the owner measured it at about two
thirds of a load's cost), `OutboundState` (last document sent, up to 8,000 characters, per record and mapping),
`ExecutionRun`, `Publication` (report up to 100,000 characters), `Conflict`, `ConnectorOperationalMetrics` (one row per
connector), `SynchronizationCheckpoint` (per mapping). What to watch: row counts of these tables; `RUNNING` runs that do
not end; OPEN conflicts; the kernel lines `retention: deleted ...` and `retention: EBX refused the age predicate (...)`.
Because retention is on by default, `ExecutionRun` and `SyncLog` purges mean a purged run can no longer be replayed or read by
`connectors_run_get`.

## 7. Upgrading

### 7.1 What an upgrade does to the data model today

- **No schema version and no migration framework.** `Connectors.xsd` has no version; the installer infers "rev 3" by
  testing whether a field exists (`Mapping/parent`). The *module* has a version since audit C-08 (section 2.1), the
  *model* has none: what a new build does to the data is what EBX does with the XSD difference (new table or field
  added, key change empties the table, rename or retype is a deletion plus a creation: `docs/UPGRADE-ROLLBACK.md`).
  The installer adds seed rows it never created (each once, `SeedMarker`), and its `migrate` step, which runs at every
  start, fills blank or placeholder values and normalises a mapping's target dataspace; it does not overwrite a value a
  person chose. The table `SeedMarker` (key `item`) is additive: empty after the restart that loads it, filled by the
  installer at that start (section 2.3).
- **Primary keys.** In EBX, changing a table's primary key empties that table (field notes §5). The XSD's current keys:
  `ConnectorCategory(code)`, `Connector(id)`, `ConnectorProperty(connector,key)`, `ConnectorTemplate(id)`,
  `Release(version)`, `ReleaseItem(release,id)`, `ReleaseMetric(release,code)`, `ReleasePassStatus(release,code)`,
  `ReleasePassStep(release,passCode,code)`, `SchemaRefreshState(connector,key)`, `SynchronizationCheckpoint(mapping)`,
  `Mapping(id)`, `MappingInput(mapping,name)`, `MappingRule(id)`,
  `FieldProvenance(dataspace,dataset,tablePath,recordKey,fieldPath)`, `Conflict(id)`,
  `ConnectorOperationalMetrics(connector)`, `ConnectorGovernance(resourceId)`, `ConnectorGovernanceAudit(id)`,
  `ExecutionRun(runId)`, `OutboundState(mapping,recordKey)`, `SyncLog(id)`, `Publication(runId)`,
  `Favorite(userLogin,targetType,targetKey)`, `SeedMarker(item)`, `OutboundQueue(id)`, `AccessRight(profile)`. `FieldProvenance`'s key changed in volume lot 5
  (commit `9fd8d90`, 2026-09-28, from an auto-increment id; it is the only key change in the history of the XSD, read
  commit by commit); the installer then removes incomplete rows (`FieldProvenance: removed N ...`) and its own comment
  says the migration was **never verified against a live repository upgrade**. A key change is not additive: EBX
  empties the table, which for `FieldProvenance` (a rebuildable audit and cache) costs the history of who wrote which
  field. `docs/UPGRADE-ROLLBACK.md` now says so. Before deploying a build, run
  `tools/schema_diff.py --git <installed> <new>` (or on the two extracted XSD): it exits 1 and lists every key change,
  removed field, removed table and type change.
- `tools/upgrade_snapshot.py` takes a read-only snapshot through Data Services GET (credentials from `EBX_USER` and `EBX_PASSWORD`) and compares two snapshots. Since 1.4.2 the script
  lives in the module (`ebx-connectors/tools/upgrade_snapshot.py`, shipped by `tools/install.sh` with the rest of the module; the file at the repository root only launches it) and covers **21 of the 27 tables**;
  the 6 it leaves out are listed WITH their reason in the script (`EXCLUDED_TABLES`): `OutboundQueue` (the sender consumes its rows: count them by hand and let the queue drain before the upgrade) and the five
  `Release*` tables (rewritten from `release-notes.xml` at every start). `src/test/python/verify_upgrade_snapshot.py` reads the model (`Connectors.xsd`) and FAILS when a table is neither captured nor excluded
  with a reason, or when a primary key differs; it reads the script of the module it sits in, so a replay in a workspace never reads a copy left in `<workspace>/tools/`. It was not run on a real upgrade: not established.
- Libraries: replace the WAR as a unit with its `runtime-libs`. Do not mix.
- Other modules: `ebx-mdm-factory` embeds `on-ebx-agent.jar` from this repository; upgrade both together.

### 7.2 Before, rollback, after

Follow [`docs/UPGRADE-ROLLBACK.md`](../../docs/UPGRADE-ROLLBACK.md) (in a release package, the same folder as this
file): schema diff, stop schedules, repository backup by the RDBMS verified on an isolated target, dataspace archive
and snapshot as secondary artefacts, test on a copy; rollback = restore the backup and the previous WAR as a unit,
**never** downgrade the schema in place. For 1.3.1 to 1.4.0 read its section "From 1.3.1 to 1.4.0" first: the new properties, the decisions to take
before the first start, the checks of `/v1/status` and what the old build does with what the new one leaves. Its "record what is installed" step reads `GET /v1/status` (`version`); a
build made before the stamp existed reports nothing, so keep the WAR file, its SHA-256 and the commit hash yourself.
A run started after the backup must be reconciled with the external system before replay.

After the start, check in the kernel log (`[connectors]` lines): `dataspace Connectors created` (first start),
`seeded ...` lines, `seed markers recorded for N existing item(s)`, `migration: ...` and `FieldProvenance: removed ...`, `retention is ON`/`retention is off`/`retention: ...`,
`MCP server 'ebx-connectors' registered with the EBX MCP runtime (N tools)` or one of the "served by
/ebx-connectors/rest/mcp" lines, `OpenLineage sink ...`, `marked N run(s) INTERRUPTED at startup`. Error lines:
`installation failed`, `installation stopped: dataset Connectors uses the data model of another module`,
`migration failed`, `stop of the background work incomplete`.
**Up to 1.4.2 the installer's lines are NOT in the kernel log of a standard instance (corrected in 1.5.0)** (`dataspace Connectors created`, `seeded ...`, `seed markers recorded`,
`-Debx.connectors.seed=none: ...`, `migration: ...`, `release notes seeded`, `packaged product profiles ready`, `perspective DataConnector installed`,
`marked N run(s) INTERRUPTED`, and the errors `installation failed`, `installation stopped`, `dataset creation failed`, `migration failed`): they were
written to the module's own category (`log.wbp.ebx-connectors.main`), which the standard `ebx.properties` sets to `FATAL, Console`, so a failed
installation logged nothing (measured 2026-11-21 on a blank ON EBX 6.3.0-gp of the standard distribution with the 1.4.2 package: 27 `[connectors]`
lines at the first start, 51 with the category opened; the 24 others were all INFO). On 1.4.2 and before, add
`ebx.log4j.category.log.wbp= INFO, Console, ebxFile:kernel` to `ebx.properties` to see them, and check `installation.installerCompleted` in
`GET /v1/status` in any case. Since 1.5.0 every start-up line goes to the kernel category (`LogCategoryTest` reads the sources). Then run `tools/smoke.sh` (section 2.4; `EXPECT_VERSION` set to the version you installed), and
`POST /ebx-connectors/rest/mcp` `tools/list` as an
administrator (23 tools: `ConnectorsTools` defines 23 and every guide and README now says 23),
`GET /ebx-connectors/rest/v1/connectors`, and the "Connection catalog" screen. Compare row counts with the snapshot.

## 8. Diagnosing a failure

Where failures are recorded: the EBX `kernel.log` (every `[connectors]` line, audit lines included); the tables
`SyncLog` (one line per record call; filter by `runId`, connector, origin), `ExecutionRun` (status of a run and its
resume position), `Conflict`, `OutboundState` (SENT/FAILED/DELETED per record), `Publication` (report of each
publication), `ConnectorOperationalMetrics`; the screens "Synchronisation log", "Connection diagnostics", the run form;
the MCP tools `connectors_log_search`, `connectors_run_get`, `connectors_outbound_states`, `connectors_conflicts_list`;
`connectors_log_search` and `connectors_outbound_states` (and `GET /rest/v1/outbound`) answer one page: `max` (default 200, ceiling 2 000, a larger value is lowered, never refused) and `from` (0-based); `count` stays the total, `cut`, `nextFrom` and `note` say when more exist (audit C2-07);
`ebxLog/workflow.log` for a workflow step; the scheduler's execution report for a scheduled task. Secret values are
redacted (`***`) in everything the module keeps or returns.

| Message | Meaning |
|---|---|
| `Access denied: ... requires the Connectors right X ...` (REST 403, MCP error) | The caller has no row/role for that right (section 4). |
| `outbound call to <host> refused: not in ebx.connectors.allowedHosts (...)` | The allow-list does not name the host; add it (an exact entry for private or metadata addresses). |
| `outbound call ... refused: ebx.connectors.egress.requireAllowList=true and ...is not set` | Set the allow-list. |
| `outbound call to <host> refused: it resolves to a loopback or private address (...) and ebx.connectors.egress.blockPrivate is on` | Section 3.1: add the host, `host:port` or CIDR to `ebx.connectors.egress.privateExceptions`. |
| `a secret must be entered in the EBX user interface ... or referenced from a vault:, env: or file: reference: a value stored in clear is refused ...` (prefixed `password:` / `apiKey:`), or `property <key> holds a secret stored in clear text ...` | A secret is stored in clear and `allowClear` is not true. Find the connections in `GET /v1/status` (`secrets.clearSecrets`) or the startup WARN; re-enter the secret in the UI or use a reference. |
| `'NAME' is not in ebx.connectors.secrets.envAllowed` / file outside `secrets.dir` | The reference is not allowed. |
| `Cross-site request protection: ...` (403) | Section 4. |
| `JDBC: the JNDI name is refused ...`, `JDBC: the driver class is refused ...`, `JDBC: the option <name> is refused ...`, `JDBC: an H2 URL must use tcp:, ssl: or mem: ...`, `JDBC: the statement is refused ...` | The configuration asks for something section 3.1 (JDBC connections) closes by default; the message names the property that allows it. |
| `Too many asynchronous runs: 4 may run and 16 may wait ...` (HTTP 429) | The slots of section 3.1 are all taken; start again later or raise the two properties. |
| `Mapping N (...) is already running (run R...)` (REST 409 on `async=true`, 422 on a synchronous start, MCP error, a failed scheduled task or workflow step) | A real run of that mapping is still going on this node (audit C-20). Nothing was read or written for the refused start. Wait, or stop the run named (`connectors_run_stop`); a scheduled task whose previous execution is still running fails with this message. |
| `Checkpoint was advanced concurrently` | Two executions of one mapping on different nodes: the in-JVM lock of the line above does not cover them. |
| `[CN-PREVIEW-CLOSED] ...` (run, call, scheduled task, workflow step, trigger send) | The function is a preview and closed: add the word it names (`documents`, `cdc`, `cdcMatching`, `redshift`; `cdcMatching` needs `cdc` too) to `-Debx.connectors.preview`, restart (section 4, "Preview functions"). |
| `CN_BODY_TOO_LARGE` (413), `CN_BATCH_TOO_LONG` (400), `CN_JSON_TOO_DEEP` (400); MCP error `-32600` | A request over a bound of section 3.1, "Request bounds"; the message names the property and the limit. |
| 403 on `POST /rest/mcp` for an account that logs in | The account holds no Connectors right at all (READ is enough): section 4, "Who may call the module". |
| `Google: serviceAccountFile is refused: ...` | The key file is outside `-Debx.connectors.secrets.dir`, or the property is unset: put the file there and name it relative to it, or use `serviceAccountJson`. |
| `Run-time input(s) ignored...` (run message and WARN of a `cdc` feed) | A run-time input tried to replace what the mapping configures; only `maxEvents` and `maxBytes`, downwards, are taken. Change the mapping. |
| `JDBC: the Redshift URL is refused: ...`, `JDBC: the option plugin_name is refused: ...` (or `CredentialsProvider`) | A Redshift connection holds the cluster-identifier form or a class-loading option: use the endpoint form (`jdbc:redshift://<endpoint>:5439/<db>`). |

| Symptom | Where | Usual cause |
|---|---|---|
| Every MCP tool list is empty, no error | Administration > MCP servers; the native server is `ebx-connectors` | No MCP server is enabled by default in EBX 6.3; a disabled server answers an empty list (field notes §1). The module's own `/ebx-connectors/rest/mcp` is not affected. |
| A client silently drops the connection during MCP handshake | `GET`/`DELETE` on `/rest/mcp` | They must answer 405 with `Allow: POST, OPTIONS`; `McpEndpoint` does. A proxy that rewrites this breaks clients. |
| "MCP server not found" (400) | path segment | The registered name is `ebx-connectors` on `/ebx-assistant/mcp/v1/`. |
| Custom module cannot register an MCP server | `[connectors] EBX declined the MCP server registration (...)` | Field notes §1: only core and add-on modules may; the endpoint stays available. |
| `/ebx-connectors/...` answers 404 or the module is missing after a start | `kernel.log`, Eclipse console | The listener was skipped while Eclipse rewrote `target/classes`; restart once builds finish. |
| `NoClassDefFoundError ... web application instance has been stopped already` after a context reload | `kernel.log` | A reload that is not a JVM restart: restart Tomcat (field notes §10). |
| `connectors_run_get` / `GET /runs/{id}` answer a page of calls, `callsCut: true` | the answer | Since audit C-33 the log rows come 50 at a time, newest first (`callsMax` up to 500, `callsFrom` for the next page); the state of the run is in `execution{}` and does not depend on the page. `callsMax=0` polls the state without reading the log. |
| A run stays `RUNNING` | `ExecutionRun`, `connectors_run_get` | After a restart it becomes INTERRUPTED; resume from its position. An asynchronous import could in the past stay RUNNING after an EBX upgrade (audit C-25, treated). |
| A scheduled task is red in the scheduler | execution report | Since 2026-10-06 any error, rejection, conflict, truncation or stop fails the task: fix the cause or set `tolerate`. A feed larger than `maxRecords` (100) fails with the parameter to raise. An alert goes out only if `ebx.connectors.alert.connector` is set (audit C-18): after `ebx.connectors.alert.threshold` (3) failed scheduled runs of one mapping in a row, one message names the mapping, the number of failures, the run and the last error (redacted). |
| `connectors_sync` ignores an input, or says `WARNING`; `max` seems to cap the load only | answer `ignoredInputs`, `message`, `warnings` | An input the mapping does not read as `${param.name}` (or any input of a run on records or a scope) is not used: since audit C-35 the answer lists it, says so in `message` and a plain `OK` reads `WARNING`. `max` bounds what is imported, not what the source is asked to compute (field notes §14): use `connectors_call` to scope a catalog operation. Defaults of `max` per entry point: GUIDE § 5 (`RunLimits`). |
| A run says `truncated: true` / "Limit reached" | the answer's `message`, the last result | Since audit C-35 a run on records or an outbound scope stopped at `max` says so like a feed (it used to stop in silence): raise `max`, or let a draining scope (unsent, changed, failed) be taken by the next run. |
| A feed run again after its source changed updates nothing (`NO_CHANGE`) | the rules' `policy` | `IF_EMPTY` (the default of a rule sent without a policy) never rewrites a filled field: set `ALWAYS` on the rules that must follow the source, key fields kept `IF_EMPTY` (the mapping designer asks for it on a feed; `connectors_mapping_upsert` reports `policyDefaulted`). |
| A catalog scan that starts in the first second of a minute reports field errors on a dateTime (`2026-09-17T22:13.000`) | `SyncLog` | Seconds omitted by `LocalDateTime.toString()`; the engine now uses `CatalogOperations.timestamp()` (field notes §14). Any custom code with the same idiom still fails. |
| A deleted seeded connector reappears | start | Only a connector deleted before the first start of the version that records `SeedMarker` rows, or on a model without that table (section 2.3); `-Debx.connectors.seed=none` seeds nothing. |
| A Kafka feed keeps reading the same messages, or a partition does not advance | the run's results: `Partition p of t stays at offset o` | The message at that offset failed in EBX or is not valid JSON. Offsets are committed only up to the messages EBX wrote (audit C-06): fix the data or the mapping, or set the connector property `dead.letter.topic` to move such messages aside. |
| SFTP refuses to connect | message | No known host key (section 4). |
| Snowflake publication "OK" on a truncated full replace | `Publication` | Audit C-17, untreated: check the report. |
| Webhook 401 / 403 | response | 401: no EBX credentials (the secret and signature modes were removed). 403: the account lacks the RUN right. |

## 9. Known limits

- **Embedded libraries (audit C2-15, lot L10, 2026-10-24).** `lz4-java-1.12.1.jar` (`at.yawk.lz4`: the `org.lz4` line stops at 1.8.1, a relocation POM with no jar; kafka-clients 3.9.2 itself declares `at.yawk.lz4` 1.10.1, and 1.10.x to 1.11.3 still carry advisories, OSV is clean from 1.11.4), netty 4.1.137.Final only (what `azure-core-http-netty` 1.16.6 declares; the 4.2-only `netty-codec-base`, `-compression`, `-marshalling`, `-protobuf` are gone, `reactor-netty` finds the compression codecs in the 4.1 `netty-codec`), tcnative 2.0.81.Final. Accepted advisories, in `runtime-libs/DEPS-KNOWN.txt`: `amqp-client` 5.35.0 (not looked at yet) and `h2` 2.1.212 (follows the server's H2). `couchbase-core-io` shades its own netty 4.1.108 under `com.couchbase.client.core.deps`: it does not meet the family above and OSV reports nothing on it. `jackson-datatype-jsr310-2.14.2.jar` is added because azure-core needs it to build a message and the server does not ship it; keep it at the same version as the other Jackson jars (2.18.12 since 1.4.1, checked against the .sha1 of Maven Central by the owner on 2026-11-12). **To update a jar:** download it and its `.sha1` / `.sha256` from `https://repo1.maven.org/maven2/` into an EMPTY folder, compare the digests, only then copy it here; replace the whole netty family at once; refresh `SHA256SUMS` and `MANIFEST.txt`, `THIRD-PARTY-NOTICES.md`; run `tools/deps_check.sh` (online: no unaccepted advisory) and `./build-test.sh` (its `RuntimeLibsLoadTest` loads the Azure Service Bus entry classes, one netty family and lz4 from `runtime-libs/` alone, offline); restart Tomcat.

- **Lot 1.4.1 (audit X3-08, 2026-11-12).** `jackson-*` 2.14.2 became 2.18.12, `snappy-java` 1.1.10.5 became 1.1.10.12, `proton-j` 0.34.1 became 0.35.0; `jackson-dataformat-yaml` 2.18.12 and `snakeyaml` 2.3 joined the war. Measured: `tools/deps_check.sh --require-osv` listed four unaccepted findings on 1.4.0 (jackson-core 3 advisories, jackson-databind 8 or more, snappy-java 3, proton-j 1) and none on 1.4.1 (73 jars asked; only the two accepted lines remain). OSV reports no advisory on the new versions at that date; that proves nothing about tomorrow, and the package gate asks again at every package.
- **Open decisions on the bundled libraries (audit C3-11, state of 2026-11-12; no jar was changed, nothing was downloaded, the online check was not run).**
  1. *MySQL Connector/J 26.7.0* (`mysql-connector-j-26.7.0.jar`, also used by SingleStore): GPL-2.0 with the Oracle Universal FOSS Exception 1.0, or Oracle's commercial licence.
     `runtime-libs/THIRD-PARTY-NOTICES.md` itself says redistribution inside a non-GPL product "must be cleared before release", and nothing in the repository records that
     it was. **Open.** To decide: either obtain the opinion (or the commercial licence) and record it in the notices, or take the jar out of the war and have the customer supply
     the driver like Redshift (the `mysql` and `singlestore` types are then not registered until it is supplied; update the notices, `MANIFEST.txt` and `SHA256SUMS`).
  2. *Seven jars never submitted to OSV. **Closed by lot 1.4.1**: `deps_check.py` now knows the groups and reads the Kafka version, and a jar it cannot query is a finding; none of the seven has an advisory (OSV, 2026-11-12). Text of 2026-11-12 morning follows.* `tools/deps_check.py` asks OSV only about a jar whose Maven group it knows and whose version it can read; it skips the others without a message:
     `bson-5.11.0`, `mongodb-driver-core-5.11.0`, `mongodb-driver-sync-5.11.0` (no group), `jna-5.13.0`, `jna-platform-5.17.0` (no group), `mysql-connector-j-26.7.0` (no group) and
     `kafka-clients.jar` (group known, version not read from its manifest; 3.9.2 per the notices). An advisory published on any of them, `kafka-clients` above all (it reads messages from a
     broker), cannot fail the dependency job. To decide: add the groups (`org.mongodb`, `net.java.dev.jna`, `com.mysql`), read the Kafka version, and make the check fail on a jar it cannot query.
  3. *Two accepted advisories have no date.* `DEPS-KNOWN.txt` asks for "why, until when": `amqp-client` 5.35.0 (GHSA-7822-rcf6-97fx, GHSA-cqgh-8p3p-mx4m; "not looked at yet") and
     `h2` 2.1.212 (GHSA-22wj-vf5f-wrvj; follows the H2 of the repository server) are accepted since 2026-10-24 without a limit. To decide: a date for each.
  The offline mode of `build-test.sh` looks at no advisory on purpose; only the `dependencies` job of the workflow does, and it has not run yet (section 2.1).

From the READMEs, `PLAN.md` (grepped, not read in full), `docs/CONNECTOR-OPERATIONAL-READINESS.md`, REFERENCE.md §6 and the owner's production
audits of 2026-10-05, 2026-10-23 and 2026-11-12 (kept by the owner, not shipped with the module).

- The audits of 2026-10-05 and 2026-11-12 both concluded **not ready for production** (a third, of 2026-10-23, found 96 points). The last one found the October fixes holding and
  the November code (documents, capture with matching, Redshift) below that level, which is why 1.4.0 ships those three closed (section 4). Treated since the first: C-01, C-02 (rights and the "Access
  rights" table), C-03 (private, loopback and metadata addresses refused by default since 2026-10-06; the host allow-list stays optional by decision; section 4 lists the paths not covered), C-06 (Kafka offsets), C-12 (purge on by default since 2026-10-06: 90 days for the log, decided conflicts, finished runs and publication reports), C-18
  (scheduled verdict; alert through one chosen connection, off until chosen), C-20 (repeated key in a batch, one run per mapping), C-21, C-22, C-23, C-24, C-25, C-26 (seed once, `ebx.connectors.seed=none`), C-33 (run status: a page of log rows, only the status read), C-28, C-29, C-30, C-43.
- **Most connector types were never run against the real system.** The operational-readiness catalogue lists 96 types:
  1 `ACCEPTED` (Kafka), 21 `EBX_LIVE_PENDING`, 9 `CHARTERED`, 1 `CREDENTIAL_BLOCKED`, 1 `FIXTURE_READY`, 63 `NOT_STARTED`.
  Seen running for real per the README: GLEIF and other public registers, Snowflake (key pair), Kafka on a local broker,
  the EBX connector on the instance itself, Slack, the LLM connector with OpenAI and Ollama. Power BI, Collibra,
  Databricks, Gemini/Vertex, Azure OpenAI and Mistral were never called for real. A type is verified only when listed
  there.
- A version is stamped since C-08 and a release package, a schema diff and a smoke test exist (sections 2.1, 2.4, 7) and `tools/install.sh` installs into a FastTrack workspace (section 2.2), but no upgrade or rollback has been rehearsed on a repository; `Connectors.xsd` is not generated any more by
  `tools/build_xsd.py` (it knows 9 tables, the XSD has 27; `extend_xsd.py` patches a hard-coded FastTrack path).
- No test runs inside EBX (C-09): the 271 Java test sources are `main` programs (plus 9 Python test files and the integration suite `tools/itest.sh`, which writes to an instance); the live acceptance gates are manual
  (`docs/EVIDENCE-AND-DEPLOYMENT-GATES.md`).
- A table trigger queues its send and the sender calls the remote system after the commit, at least once (C-07, lot L8; `<blocking>true</blocking>` keeps the call inside the transaction); Kafka inbound commits an offset only after EBX wrote the message, so it is at-least-once: a replayed message is an upsert, and a message that cannot be written holds its partition unless `dead.letter.topic` is set (C-06, untested against a real broker); a flux
  looks up each row by XPath unless the key predicate is the whole string primary key (C-10); sub-object mappings query
  per child (C-11); record-mode sync is still one record at a time with one remote call each, but reads the mappings once and writes its bookkeeping in groups (C-13); profiling is exact below 10,000,000 rows and sampled above on Snowflake and Databricks, with a table and time budget (C-15, section 3.3; JDBC: budget only, no sampling); only four
  families stream, the rest hold the whole answer in memory (C-16, partly: a default 128 MiB response ceiling on the shared HTTP client, Salesforce `query` honours `max` and fails on a failed page; the 92 connectors are not rewritten; SAP OData keeps its 10,000-row cap); the Snowflake publication streams its rows in batches since C-17 (no
  default cap; a cut is `PARTIAL`; the time of a very large table is not measured); two simultaneous runs of one mapping are refused on one node only (C-20; a cluster is not covered); conflict acceptance is two
  transactions (C-32). A flux whose source read fails (or whose connector, operation or dataset is not found) ends `ERROR`
  (`PARTIAL` after items were written) with `errors` >= 1, the run recorded `ERROR` with the reason as its message, a failed
  scheduled execution and, on `POST /sync/{mapping}`, HTTP 422 instead of 200 (audit decision 4, 2026-10-06; `count` does not include it).
- Single-node assumptions: in-memory cancel flags (a stop request reaches only the node that runs the import),
  conflict locks, secret and connector caches, and `DriverManager.setLoginTimeout`, which
  is JVM-wide. A starting node no longer interrupts every RUNNING run: it leaves alone one whose lease is held by
  another node and has not run out (C-44, `ebx.connectors.nodeId`, `ebx.connectors.run.leaseSeconds`); a run that
  was started before this change carries no lease and is interrupted as before. The lease is renewed at each batch
  only: a run that never reports a batch (a non-batched import) can outlast its lease. A crashed node's runs read
  RUNNING until their lease runs out and the next start of any node, there is no periodic sweep. Behaviour on a
  real cluster: not run.
- The mapping designer: keyboard use was fixed in wave 6 (C-42); screens follow the colours of the perspective that displays them
  (C-40, REFERENCE §4.3; not yet looked at in two live perspectives; logos and menu icons keep their colours); languages
  are mixed (C-37); the conflict manager shows one page of the newest open conflicts (C-39: `ebx.connectors.conflicts.pageSize`, default 200; the open conflicts are counted by EBX, not loaded; the screen's "all" buttons act on the page shown, and a second Apply takes the next page).
- Governance, recoverable-execution and checkpoint services exist without any production caller (REFERENCE §2.20);
  `docs/GOVERNANCE.md` describes an API no adapter uses (C-47). `GET /v1/status` is the health route (C-47, section 2.4).
- Not established by the audit and not here: default EBX permissions of the `Connectors` dataspace for a
  non-administrator; session-cookie acceptance on the module's REST routes; `SessionImpl.createSession` under an SSO
  directory; real throughput and memory of the batch path; whether the EBX scheduler prevents overlapping runs of one
  schedule.
- Statements in `PLAN.md` that passes 2, 6, 8 and 9 are "In progress" are stale (C-49); the README says the module
  runs "an administration role" and an allow-list: both are off until configured.
- **Undocumented EBX and add-on APIs the module still calls (audit C2-11, C3-14)**, three classes, each isolated in ONE class and caught at the call, with a
  fallback or a coded refusal; `build-test.sh` fails on any other use of an `.internal.` package, `SessionImpl`, `ProfileListContextBridge` or a
  non-public package of the DAMA add-on (`addon.dama.common`, `addon.dama.paths`):
  `core/WorkerSessions` (`com.onwbp.adaptation.SessionImpl.createSession(UserReference, Repository)` by reflection: a
  session for the caller's user in a background worker; fallback: the caller's own session; no public API exists, the
  public factories want credentials); `ebx/ConnectorsRoleEnumeration` (`ProfileListContextBridge.getForDefiningPermission()`
  by reflection to list the directory's roles for the Access rights screen; fallback: the three default role names, and what is
  accepted never depends on the list: `Profile.parse` + `DirectoryHandler.isSpecificRoleDefined`). Replaced by the public
  API on 2026-10-24: the REST application is registered by `ModuleInitializedContext.registerRESTApplication` in
  `ConnectorsModuleRegistration.handleContextInitialized` (the servlet of `web.xml` named
  `com.orchestranetworks.rest.internal.RESTRegistrationServlet`; **a Tomcat restart is needed**, `web.xml` is read at start),
  and the diagnostics screen closes with `UserServiceNext.nextClose()` (javap: the same call as the internal class).
  **Third class (C3-14, added 2026-11-12):** `core/ReflectiveDamaAssets`, reached only by the `documents` function (preview, closed by default). The
  asset itself is read and written through the add-on's `ext` package, which is public API; but the tag vocabulary and the metadata types have
  none and are read from the add-on's own dataset by reflection: `com.orchestranetworks.addon.dama.common.APIConstants$CONFIGURATION`
  (`DAM_HOME`, `DAM_DATASET_NAME`) and the table classes `com.orchestranetworks.addon.dama.paths.DrivePaths$…`, which the add-on's Javadoc does
  not publish. The design note records it as an accepted exception (the owner's decision); a DAMA version that moves these classes makes
  `annotate` answer "DAMA: cannot read the tag vocabulary" (or "…the metadata types"), nothing else. **Open:** the Match and Merge calls of
  `MameGoldenRecords` (`OperationsFactory`, `ProcessStatus`) were found public by `javap`; they have not been checked against the add-on's published
  Javadoc of 6.3.0 GA, nor the four classes above, for the same release.
- **Installation and threads (C2-12, C2-13).** An incomplete installation is retried by one run at a time, under the
  module's own system session (created at startup), never under the session of the request that noticed it and never behind a
  lock: the other callers return at once, and after a failure the next attempt is at least a minute later (kernel log:
  `[connectors] module not installed: ...`, at most once a minute). Every thread the module creates (`BackgroundWork.daemonThreads`,
  the catalog's parallel reads included, now tracked by `BackgroundWork`) carries the module's own context class loader, not
  its creator's (CLAUDE.md section 10: a thread created from another module's thread kept that module's loader).


L9 wiring (2026-10-24): REST, MCP and the Conflict screen pass the caller's session to `outboundStates`, `page` and `pageOfIds` (C2-03: rows whose origin record the caller cannot read are left out); REST refusals follow `Accept-Language` (fr/en) and MCP refusals the session locale (C2-17; the engine's stored `SyncLog` messages are still English text in `ConnectorService`, rendered localized by `SyncLogForm`); `GET /v1/webhook/{feed}` needs RUN and `POST /v1/catalog/preview` MANAGE (C2-16); the CSRF guard has no webhook exemption and the seeded `Webhook` connection stores no secret (C2-10).
- **Table triggers queue their sends (C-07, lot L8).** `ConnectorTableTrigger` no longer calls the remote system in the user's
  transaction (the message used to leave before the commit, the dataspace stayed locked for the remote delays, and a failure was
  one WARN line). It hands the record's location (connector, operation, direction, dataspace, dataset, table, key) to
  `core.OutboundQueue`; a background task of the module (`BackgroundWork`, thread `ebx-connectors-outbound-queue`, stopped with
  the module, its unwritten hand-overs flushed first) writes a row of the new table `OutboundQueue` (Connectors dataset), waits
  `-Debx.connectors.outboundQueue.settleMs` (default 2000) so that the user's transaction is committed, re-reads the record as it
  is then, chooses the mapping like the trigger did and syncs it with `ConnectorService.syncRecord` (origin `trigger`: sync log,
  provenance, conflicts, outbound state and lineage are now written, which the in-transaction path never did), then deletes the
  row. **Delivery is at least once: the target must tolerate a repeated message** (idempotent by the record's key); rows of the
  same record due together are sent once. A failure keeps the row `PENDING` for the mapping's `retryDelayMinutes` (default 30)
  and sets it `DEAD` after the mapping's `maxAttempts` (default 5, the counter the outbound scopes use); `lastError` and
  `attempts` are on the row. A `DEAD` row stays until it is deleted or set back to `PENDING` with `attempts` 0 (EBX administrator).
  A record whose creation was rolled back, a deleted record, a vanished dataset or a table without mapping is dropped with an INFO
  line, not retried. Rows left `PENDING` by a stop or a crash are sent by the first passes after the next start. Other settings:
  `intervalMs` (3000) and `batch` (200, rows per pass). Written by the module only (policy: READ reads, an EBX administrator writes).
  **Limits:** the row is written by the module's thread right after the trigger, in its own transaction (a procedure cannot write
  into the Connectors dataspace from the user's dataspace), so a JVM crash in that few-millisecond window loses the queued send;
  the in-memory buffer holds at most 100,000 waiting sends (beyond, the trigger logs `NOT queued`). The trigger no longer reads
  the mapping tables on each write (an unmapped table queues rows that the sender drops). **`<blocking>true</blocking>` keeps the
  former synchronous behaviour, as the documented exception:** remote call in the user's transaction, dataspace locked meanwhile,
  a failure refuses the user's save, no sync log. Needs a Tomcat restart (new table in the model, new background task).
  **Retention (L8):** the purge that deletes SyncLog at 90 days also deletes `OutboundQueue` rows that are `DEAD` and older than
  the retention (`ebx.connectors.retention.outboundQueueDays`, default the general age, 0 keeps them); a `PENDING` row is never
  purged, and sent rows do not exist (the sender deletes them). **Retry delay of a trigger send:**
  `-Debx.connectors.trigger.retryDelayMinutes` (default 2), capped by the mapping's own `retryDelayMinutes` when that is smaller
  (this replaces the 30-minute mapping default for trigger sends). **The limit, in plain words:** the queue row is NOT validated
  with the user's transaction (it is written in the Connectors dataspace, a different one); a brutal stop of the JVM in the few
  milliseconds between the trigger and the row's write loses the send. `blocking=true` is the only strong guarantee (and costs the
  in-transaction call).

## 10. Capture, matching around a batch, golden records out, documents and Redshift

What 1.4.0 adds to run, and how far each piece has been proved. Detail: [CDC.md](CDC.md), [REALTIME-MASTERING.md](REALTIME-MASTERING.md),
[DOCUMENTS.md](DOCUMENTS.md), [DAMA-DOCUMENTS.md](DAMA-DOCUMENTS.md), [DOCUMENTS-VISION.md](DOCUMENTS-VISION.md).

| Function | State in 1.4.0 | Run on an instance? |
|---|---|---|
| Debezium capture into a table (`kafka` / `cdc`, deletes, flags, resume position) | **preview, word `cdc` (closed by default since 1.4.2; open in 1.4.0 and 1.4.1)** | 17 checks on the test instance, Kafka broker real, Debezium-format events published by the script; a real Debezium connector never |
| Match and Merge around a batch (`matching=afterApply` / `off`), golden records out (`publish=golden`), no echo to the origin (`skipOrigin`) | preview, words `cdc` AND `cdcMatching` | never (scenario B8) |
| `documents` (PDF, DOCX, text, DAMA, `annotate`, vision) | preview, word `documents` | scenarios A1 to A3 (36 checks, real model); DAMA write-back and vision partly |
| `redshift` | preview, word `redshift` | never |

**Running a capture feed.** One run applies one batch (`maxEvents` 500 by default, 10000 at most; `maxBytes` 8 MB, 16 MB at most): schedule it
(section 2.3, scheduled tasks) at the pace the source needs. The resume position is the `SynchronizationCheckpoint` row of the mapping (key = mapping id,
strategy `VENDOR_CURSOR`, value = a JSON object `{"<topic>:<partition>": <last applied offset>}`), saved only after EBX applied the whole batch, so a failed
batch is read again (at least once). The consumer never commits to the broker (group `<group.id>-cdc-no-commit`). **Restarting a feed from the beginning** = deleting
that row. Two runs of one mapping are refused (section 8). A run-time input cannot change the mapping's settings (section 4). Watch `cdc.flows[]` in
`GET /v1/status` (the flows are listed even while the function is closed; a closed flow does not run) (running now, saved position, age of the last applied batch) and the run message (`CDC: 9 event(s) -> 2 upsert(s), 2 delete(s)`); an old
`savedAtAgeSeconds` on a flow that has events is the sign of a flow that cannot apply.

**Known limits of capture, not fixed (audit C3-13 and C3-15, read in the code). They are limits of a function that is CLOSED by default since 1.4.2 (word `cdc`):
they decide whether to open it, they are not corrected by this version. A real Debezium connector with a stop longer than the retention has never been tried.**

- *A retention gap is skipped without a word.* If the saved position is older than the first message the topic still holds, the read starts at that first
  message; the lost events (deletes included) are neither counted nor reported and the run is green. Keep the topic's retention longer than the longest stop of the flow;
  after a stop that may have exceeded it, compare the table with its source or reload it.
- *An unreadable message blocks the flow.* A message whose JSON is invalid, or a Debezium operation the module does not know, fails the batch before any write; the position does
  not move and the next run reads the same message again, until someone moves the position past it by editing the `SynchronizationCheckpoint` row (or removes the message
  from the topic). Nothing in `/v1/status` says it except the age of `savedAt`; the run history says why.
- *The Kafka client of capture is created without the module's class loader* (C3-15): every other Kafka path of the connector sets it before creating a client, `executeCdc` and the
  direct preview do not. Called from a scheduled task or a workflow step, the client resolves its classes with the loader of the calling thread, so the `kafka-clients` of the server `lib/`
  (3.7.1) may answer instead of the module's (3.9.2). Not observed to fail; not measured.
- *Not covered:* Avro or Protobuf values, Debezium transaction metadata, schema-change events, child tables (CDC.md, "Not covered").

**Matching around a batch** (only with `cdc` AND `cdcMatching`, **never run on an instance**; audit C3-05). With `matching=afterApply` or `off` the module pauses the Match and Merge trigger of
the whole table (a persistent setting of the add-on, `pauseMatching`), writes the batch, resumes it, then matches the keys the batch wrote. Consequences to know: a record
created or changed by someone else in that window is not matched by the trigger (paused) nor by the module; **if the JVM stops during the writes the table stays paused** and nothing
resumes it at the next start (the module has no recovery for it): after any stop of the server during a capture run, check `get_matching_config` / the table settings of the
`match-merge` server for `pauseMatching` true and set it false; a record read by the outbound queue before the matching ended is sent as it is, not as its golden. The run
message gives `matching: N record(s), ...` and the checkpoint moves only when that matching ended `DONE`.

**Documents and Redshift** (previews). Documents: the text of a document, and with `vision=true` on both connections the image itself, leave EBX for the language-model
connector named in `llm.connector`; nothing records what was sent (section 4). Bounds (connection properties): `max.bytes` 20 MB (at most 50), `max.chars` 60,000 (at most 400,000), `max.image.bytes` 5 MB (at most 20), `max.pages` 20 for a PDF
without text (at most 100), which is nevertheless sent whole. The PDF reader is the module's own and not hardened (release notes, known issue): open `documents` only where nobody
untrusted can run a documents flow. Redshift: endpoint URL form only, vendor driver supplied (section 2.6), same egress and clear-secret rules as the other JDBC types.
