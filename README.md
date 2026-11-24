# ebx-connectors

> This repository is published automatically from ON EBX's development repository at every change of its main
> branch. A change made here is overwritten by the next publication: report issues and propose changes to ON EBX.

A generic, configuration-driven **connectors module for ON EBX 6.x**: connect EBX to external
systems and services (business registers, data providers, address services, CRM/ERP, data platforms,
Kafka, files, any REST API) through **connectors** and **bidirectional mappings** designed graphically,
and run them from the user interface, the REST API, workflows, scheduled tasks and table triggers.
Enrichment is governed by **trust levels**, every write is traced (**provenance**) and disagreements
between sources are kept as **conflicts** for a steward. Outbound flows send **deltas** and **replay**
their failures. Everything is also exposed as **MCP tools**, so an AI agent can operate the module, and
the security model is the one an enterprise architect expects: secrets in a **vault**, **OAuth 2.0**,
**mutual TLS**, webhooks that need an EBX session with the RUN right, three access rights, redaction. The outbound **allow-list** exists but is off until its property is set (see "Enterprise security" below), and the log **retention** is on by default (90 days).

| Folder | Content |
|---|---|
| [`ebx-connectors/`](ebx-connectors/) | the EBX module (Java sources, data model, web resources, guides) |
| [`ebx-connectors/docs/`](ebx-connectors/docs/USER-GUIDE.md) | **User guide** with screenshots (Markdown and self-contained HTML) |
| [`ebx-connectors/docs/OPERATIONS.md`](ebx-connectors/docs/OPERATIONS.md) | **Operator guide**: build and install (including an instance that is not a FastTrack workspace), every JVM property and stored setting, production security settings, preview functions, background work, retention, upgrade, diagnosis, known limits |
| [`docs/UPGRADE-ROLLBACK.md`](docs/UPGRADE-ROLLBACK.md) | **Upgrade and rollback runbook**, with the section "From 1.3.1 to 1.4.0"; the release notes of 1.4.0 are in the module's About page |
| [`ebx-connectors/docs/DOCUMENTS.md`](ebx-connectors/docs/DOCUMENTS.md) / [`CDC.md`](ebx-connectors/docs/CDC.md) | the document feed with AI extraction (confidence, proposals, provenance) and Debezium CDC applied to EBX |
| [`ebx-connectors/docs/CONNECTOR-COVERAGE-ROADMAP.md`](ebx-connectors/docs/CONNECTOR-COVERAGE-ROADMAP.md) | live connector-coverage roadmap and parallel-work tracker |
| [`ebx-connectors/GUIDE.md`](ebx-connectors/GUIDE.md) / [`GUIDE.fr.md`](ebx-connectors/GUIDE.fr.md) | complete reference, English (default) and French: connectors, mappings, expressions, transformations, REST API, workflow, trust and conflicts, catalogue of the connector types |
| [`ebx-connectors-extension-sample/`](ebx-connectors-extension-sample/) | how to add a connector type from a **separate jar**, without the module sources (OpenStreetMap Nominatim example) |
| [`tools/`](tools/) | `build_xsd.py` (historical: it knows 9 of the 27 tables of the data model, which is edited by hand; do not run it over `Connectors.xsd`), `import_config.py` (re-imports an exported configuration), sample configuration scripts, and an export of the seeded configuration |

## Highlights

- **96 connector types** out of the box:
  - *registers and data providers*: GLEIF, INSEE Sirene, INPI, VIES, Companies House, Handelsregister,
    OpenCorporates, D&B, Orbis, Creditsafe, OpenSanctions, openFDA;
  - *addresses*: Melissa, Loqate, BAN, Google Geocoding;
  - *business applications*: Salesforce, Dynamics 365, Dynamics 365 Business Central, SAP OData,
    SAP SuccessFactors, SAP Ariba, JD Edwards AIS, Oracle Fusion, Oracle NetSuite, HubSpot, ServiceNow,
    Workday, SharePoint Online;
  - *databases and data platforms*: PostgreSQL, MySQL, Oracle Database, SQL Server, Azure SQL, H2,
    generic JDBC, Snowflake, Databricks, BigQuery;
  - *messaging*: Kafka, JMS, ActiveMQ, RabbitMQ, Azure Service Bus;
  - *files*: SFTP, S3, Azure Blob, Google Cloud Storage, and a manually uploaded CSV/JSON sample
    (declares a source's shape before a real connection exists);
  - *catalog and lineage*: Power BI (Scanner API), Collibra (both directions), OpenLineage, another EBX
    instance;
  - *notifications and inbound*: Slack / Teams, inbound webhook;
  - generic `rest`, `soap` and `graphql` types driven by properties;
  - an **LLM connector** (Claude, OpenAI, Google Gemini / Vertex AI, Azure OpenAI and AI Foundry,
    Mistral AI, Ollama) used as a low-trust enrichment source whose proposals go through the conflict
    manager.
- **Connector from an OpenAPI specification** (JSON or YAML), in one step.
- **Graphical mapping designer**: service-call inputs with expressions, drag-and-drop links between the
  service answer and the EBX table, transformation pipelines, sub-object mappings (child tables),
  reverse and response mappings, simulation on a sample record.
- **Runs everywhere**: user service on any table, REST API (`/ebx-connectors/rest/v1`), workflow script
  task bean and user task, scheduled task, table trigger, webhook.
- **Trust, provenance, conflicts**: `TRUSTED` write policy, `FieldProvenance` audit trail, `Conflict`
  table, a conflict manager screen (dataspace, table, record, workflow task) and REST endpoints.
- **Outbound deltas and replay**: scopes `unsent`, `changed`, `failed` and `dataspaceDelta` (what a
  child dataspace changed since its snapshot), an outbound state per record, deletions, retry policy,
  run ids and `POST /replay/{runId}`.
- **Lineage for the data catalog**: every run, and every mapping as soon as it is declared, is sent as
  OpenLineage events (with column lineage) to the ON EBX data catalog, Marquez, DataHub or any
  OpenLineage endpoint.
- **MCP tools for AI agents**: 23 tools (list and describe connectors, call an operation, list, create,
  update and delete mappings, enrich, synchronise, replay, stop a run, log, conflicts, configuration export and
  import, OpenAPI import, lineage declaration, publication of a data product, catalog source), served by the module's own MCP endpoint on any EBX
  version and registered with the native MCP runtime of EBX 6.3 through its public API. An agent
  configures a mapping, tests it in simulation and arbitrates conflicts without touching the user
  interface.
- **Enterprise security**: secrets encrypted with EBX's key or referenced from a vault (HashiCorp
  Vault, Azure Key Vault, AWS Secrets Manager, environment, file, or your own `SecretResolver`); generic OAuth 2.0 (client
  credentials, password, refresh token, JWT bearer) with token cache; mutual TLS and private CAs per
  connector; webhooks that take one mode only, an EBX session of a user holding the RUN right (no shared secret, no signature); secrets masked in every message the module keeps; three rights
  (viewer, operator, designer) given to existing roles in the "Access rights" table and closed to
  everyone else; a cross-site request guard on the REST API; request bodies and batches bounded. **Optional, off until its JVM property is set:**
  the outbound host allow-list (`-Debx.connectors.allowedHosts`; until then every public host is reachable). **On by default:** the refusal of private,
  loopback and unresolvable hosts (exceptions in `-Debx.connectors.egress.privateExceptions`, `-Debx.connectors.egress.blockPrivate=false` switches it off),
  the refusal of secrets stored in clear (`-Debx.connectors.secrets.allowClear=true` accepts them again), the log retention purge, 90 days
  (`-Debx.connectors.log.retentionDays=0` turns it off). `GET /ebx-connectors/rest/v1/status` says which are on.
- **Change data capture** (1.4.0): the `kafka` connector's `cdc` operation applies a topic of Debezium events (insert, update, delete) to an EBX table, saving
  its resume position only after EBX applied the batch ([`CDC.md`](ebx-connectors/docs/CDC.md)). Run on a live broker with Debezium-format events; a real Debezium
  connector has not been tried.
- **Shipped closed in 1.4.0 (preview)**: the `documents` connector (PDF, Word and text read by an AI model, DAMA assets, images), Match and Merge around a
  capture batch with golden records sent out, and Amazon Redshift. Each runs only when named in `-Debx.connectors.preview=documents,cdcMatching,redshift`;
  until then it answers `[CN-PREVIEW-CLOSED]`. See `ebx-connectors/docs/OPERATIONS.md` section 4 and the release notes.
- **Extensible**: new connector types and secret resolvers from a jar (`ServiceLoader`), compiled
  against `ebx-connectors-spi.jar` (`ebx-connectors/build-spi.sh`).

## Status

The module is installed on a test instance and its framework (mappings, trust and conflicts,
outbound deltas and replay, OpenLineage, the MCP tools) runs there. Seen running against real
systems: GLEIF and other public registers, Snowflake (key-pair authentication: query, catalog scan,
profile, sample, and publication of a data product as tables and as an organizational listing, with
golden records and crosswalk, the glossary meaning as column comments and tags, and a semantic view
carrying the glossary synonyms), Kafka on a local broker, the EBX connector against the instance
itself, Slack, and the LLM connector with OpenAI and Ollama. The `mcp` connector type and its three
seeded EBX connectors answer a connection test on the four EBX MCP servers.

Most of the 96 connector types are covered by offline contract tests against recorded fixtures
only, because no vendor account or live endpoint is used by the test suite (see below): Power BI,
Collibra, Databricks, and the Gemini / Vertex AI, Azure OpenAI and Mistral providers, among others,
have never been called for real. Treat a type as verified only once it is listed above.

## Requirements and build

- ON EBX 6.x (developed on 6.3) on Tomcat 10. The ON EBX API (`ebx.jar` of a standard installation, `ebx-lib.jar` on FastTrack: the same jar) comes from your EBX
  installation and is **not** part of this repository. A standard ON EBX `lib/` has neither Jackson nor SLF4J, so the war carries them (Jackson 2.14.2, SLF4J API 1.7.32),
  together with the connector libraries (`kafka-clients`, `jsch`, the PostgreSQL, H2, Oracle, SQL Server and MySQL drivers, the messaging clients...):
  `ebx-connectors/runtime-libs/` (71 jars, checksums and licences), packaged into the war and checked for self-sufficiency by the build
  (`ebx-connectors/docs/OPERATIONS.md` section 1.1). The other JDBC drivers, Amazon Redshift's among them, are supplied by the
  customer (section 2.6 of the same guide).
- Java 8 syntax, compiled with `javac --release 8`; no Maven is needed. See
  [`ebx-connectors/README.md`](ebx-connectors/README.md) for the compile command, the Tomcat context
  and the deployment steps.

Run the complete account-free build and contract baseline from this repository root:

```bash
./build-test.sh
```

It compiles and packages the EBX module, runs the connector contracts, builds the public SPI, and
compiles/packages the extension sample against that SPI. By default it finds EBX libraries in the
adjacent FastTrack server; set `EBX_LIB_DIR=/path/to/server/lib` elsewhere. The GitHub workflow uses
the same command on a self-hosted runner labelled `ebx-connectors`, whose `EBX_LIB_DIR` repository
variable points at those licensed libraries. Pull requests from forks are deliberately skipped so
untrusted code does not run on that internal runner. No connector vendor account or live endpoint is
used.

To install the module into a FastTrack workspace (copy, build into the deployed `target/classes`, version stamp), run
`tools/install.sh` with that workspace's Tomcat stopped (`--dry-run` to preview; `EBX_WORKSPACE` to choose the
workspace). Details: [`ebx-connectors/README.md`](ebx-connectors/README.md#installation) and
[`ebx-connectors/docs/OPERATIONS.md`](ebx-connectors/docs/OPERATIONS.md) section 2.2.

Start with the [user guide](ebx-connectors/docs/USER-GUIDE.md), then the
[reference guide](ebx-connectors/GUIDE.md): section 3.1.1 and 3.1.2 for security, 10.1 for deltas and
replay, 3.7 for lineage, 12.7 for the MCP tools.

## Using it from an AI agent

Add the module as an MCP server to your agent, with EBX credentials:

```json
"ebx-connectors": {"type": "http", "url": "http://localhost:8080/ebx-connectors/rest/mcp", "headers": {"Authorization": "Basic ..."}}
```

Then ask, for instance: *"list the Kafka connectors and their mappings"*, *"create a VIES mapping on
Companies and test it on record 740"*, *"which conflicts are open on Exemplar Industries? reject the Wikidata
value"*, *"export the GLEIF configuration"*. The agent starts with `connectors_help`.

## License

Copyright 2026 ON EBX. Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
