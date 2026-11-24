# ebx-connectors user guide

This guide explains how to configure and operate the module, with the emphasis on **mappings**. It is
written to be read by a person or by an AI agent: every concept is defined once, every mechanism comes
with a runnable example, and the last sections give the recipes and the exact calls needed to build a
configuration without the user interface. French version: `GUIDE.fr.md`.

Contents: 1 Vocabulary · 2 Where the configuration lives · 3 Connectors · 4 Mappings · 5 Inputs and
expressions · 6 Rules and paths · 7 Transformations · 8 Sub-objects · 9 Import feeds · 10 Outbound
direction and response mapping · 11 Graphical designer · 12 Running (service, REST, workflow, scheduler,
trigger, webhook) · 13 Log and statuses · 14 Complete recipes · 15 Cheat sheet for an AI agent ·
16 Troubleshooting · 17 Connector catalogue.

---

## 1. Vocabulary

| Term | Definition |
|---|---|
| **Connector** | A *configured* external application or service: one row of the `Connector` table (id, type, URL, authentication, properties). Examples: `GLEIF`, `Melissa`, `Wikidata`. The screens call it a **connection** (menu *Connection catalog*, *Test connection*), and keep *connector type* for the Java code behind it. |
| **Connector type** | The Java code that knows how to talk to a family of services: `gleif`, `salesforce`, `jdbc`, `rest`… A type offers **operations**. Several connectors may share a type (two JDBC databases). |
| **Operation** | What a connector can do, with its **inputs**. Three kinds: **read** (fetch a document for a record, e.g. GLEIF `lei`), **search** (fetch a list, e.g. GLEIF `searchByName`, SAP `query`), **write** (send a document, e.g. Salesforce `upsert`, Kafka `publish`). |
| **Document** | The JSON exchanged with the service: what it returns (inbound) or what is sent to it (outbound). |
| **Mapping** | The translation between a document and an EBX table: a connector, an operation, a direction, a target table, inputs, rules. One row of the `Mapping` table. |
| **Direction** | **Inbound** (`INBOUND`): the document returned by the operation is written into the EBX record. **Outbound** (`OUTBOUND`): a document is built from the record and sent. |
| **Input** (`MappingInput`) | A parameter of the operation, computed from the record by an expression: `siren = ${./LegalInformation/registrationNumber}`. |
| **Rule** (`MappingRule`) | A link between a document path and an EBX field, with a transformation pipeline and a write policy. |
| **Transformation** | A function applied to a value: `trim`, `upper`, `left(80)`, `map(A=B)`, `fk(/root/T)`… Chained with `|`. |
| **Sub-object** | A child mapping translating *one element of a collection* of the document into *one row of a child table* (addresses, contacts, identifiers). |
| **Import feed** | An inbound mapping whose operation returns a **list** (Kafka, SQL query, file, webhook): every item creates or updates a record. |
| **Response mapping** | The inbound twin of an outbound mapping: it stores in EBX what the service answered to the send (an id, a status). |

**Language of the screens.** The screens are localised in English and French (`Messages_en.properties`,
`Messages_fr.properties`, in the language of the user's EBX session) and use one vocabulary: **connection** for
the configured object, **mapping** for its translation between a document and a table, **flow** (French *flux*) for
a mapping shown on a connection's card, **feed** for an inbound mapping that returns a list. Localised: labels,
tiles and statuses; the cause and next action of the frequent failures (§ 12.1); the common sentences the engine
stores in the synchronisation log (created, updated, unchanged, input empty, no data, batch of N, import stopped…,
about 25), which a screen writes in the reader's language while the stored text stays English (it is what
searches, agents, replay and the REST and MCP answers read; the stored sentence is in the tooltip of the screen's row); the results of the
diagnostics (*Validate*, *Test connection*, *Preview*, *Connect*). Still English: the text stored in `SyncLog` and
returned by REST and MCP, what a connection itself answers (a test message from the remote system, the descriptor validation issues listed by *Validate*),
the technical detail folded under an error, the kernel log, the schema labels of the tables of the Connectors
dataset (*Connector*, *Connectors*, *Connector properties*) and the REST and MCP documentation.

## 2. Where the configuration lives

Everything is **EBX data**, in the `Connectors` dataspace, `Connectors` dataset, created and seeded by
the module at startup:

| Table | Content |
|---|---|
| `ConnectorCategory` | reference values used to group connectors in the catalog; missing standard categories are added at startup without replacing customer-defined rows |
| `Connector` | configured connectors (40 seeded, secrets left as `TO_BE_SET`, to be entered) |
| `ConnectorProperty` | `key = value` properties of a connector (options, and the operations of the `rest` type) |
| `Mapping` | the mappings, with their inputs, rules and children as associations |
| `MappingInput` | the inputs of a mapping |
| `MappingRule` | the rules of a mapping |
| `SyncLog` | the log: one call = one row (status, message, duration, origin); a feed run in batches writes one row per batch, plus an own row for each failing or conflicting record |
| `Favorite` | the connectors each user pinned with the star of the connector explorer (own rows only, never edited by hand) |

Access: **Data > Connectors**. On a `Mapping` row, the dedicated form offers the **Design the mapping
graphically** button. The **Synchronise with an external application** service is offered on the table
view of every table that has at least one mapping. The module's REST API and Data Services read and
write the same tables (§ 15).

### 2.1 Seeding and the customer's model

Connectors are always seeded. The **demonstration mappings** (on `/root/Companies`) are seeded only
when they make sense in the environment: system property `ebx.connectors.seedMappings` (`-D` on the
Tomcat JVM):

| Value | Effect |
|---|---|
| `auto` (default) | a mapping is created only when its table exists in a dataset of the given name, and only with the rules whose EBX field exists; a mapping whose input references a missing field, a sub-object whose child table, link field or keys are missing, are skipped (one `[connectors] seed:` line in the log per dropped item) |
| `always` | everything is seeded as written, as before |
| `never` | connectors only |

Then three **validation constraints** (warnings, never blocking) watch the configuration permanently,
in the validation report of the Connectors dataset and on the forms: `Mapping/tablePath` (dataset or
table not found, link field or keys of a sub-object missing), `MappingRule/sourcePath` and `targetPath`
(the EBX side of the rule is not a field of the table), `MappingInput/expression` (a `${./Group/field}`
does not exist in the table).

## 3. Connectors

### 3.1 Creating or configuring a connector

Fields of the `Connector` table:

| Field | Role | Example |
|---|---|---|
| `id` | identifier, referenced everywhere (mappings, workflow, REST) | `GLEIF`, `Melissa`, `MyDatabase` |
| `category` | foreign key to `ConnectorCategory`, used to group the connector in the catalog; inferred only when blank | `CORPORATE_REGISTRIES` |
| `type` | list of the types registered in the module (§ 17) | `gleif`, `rest`, `jdbc` |
| `logo` | image shown with the connector (forms, table, designer): a module resource `ebx-connectors:ext-images:connect/logos/<file>`; the module ships one generated tile per seeded connector under `www/common/images/connect/logos` (`tools/gen_logos.py`), replace any file by an official logo of the same name, or add your own SVG or PNG | `ebx-connectors:ext-images:connect/logos/Salesforce.svg` |
| `logoUrl` | computed: the URL of the logo image, a plain string; use it as the **image field of a tile view** (a resource field is not accepted there) | `/ebx-connectors/www/common/images/connect/logos/Salesforce.svg` |
| `baseUrl` | root of the calls (or JDBC URL, `sftp://host`) | `https://api.gleif.org/api/v1` |
| `authType` | `NONE`, `BASIC` (`username`/`password`), `API_KEY_HEADER` (`apiKeyHeader` + `apiKey`), `BEARER` (`apiKey`), `OAUTH2` (token endpoint and flow in the `oauth.*` properties, § 3.1.1) | Melissa: `API_KEY_HEADER` |
| `username`, `password`, `apiKey`, `apiKeyHeader` | credentials; `password` and `apiKey` are encrypted | |
| `trust` | trust level 0-100 used by the `TRUSTED` policy (§ 4.4); seeded by type, a manual entry counts as 100 | GLEIF 90, Wikidata 40 |
| `connectTimeoutMs`, `readTimeoutMs`, `active` | timeouts and activation | |
| properties (`ConnectorProperty`) | options of the type, documented in the description of the seeded connector | Melissa: `country = FR`, `verified.only = true` |

A secret left as `TO_BE_SET` (or empty) is recognised as **not set**: the connector
refuses to call and says why ("set the API key in apiKey"). Secrets are entered in the EBX user
interface: the two fields declare EBX's reversible encryption (`PasswordTypeEncryption`, repository
key) and the module decrypts them when it loads a connector. The default `osd:password` editor
would only store a SHA-512 hash, which no connector could use. A value that does not decrypt is
used as is, so a key written in clear through Data Services or an import works too, but is then
stored in clear. A change of secret, endpoint or property is taken into account at the next call,
without restart.

**Credential guide.** The *Connection & configuration* tab of a connector shows which credential
fields to fill for the selected authentication mode. The modes the connector type offers appear as
cards, the others are greyed out. Each credential row carries a state (required, optional, derived,
not used) and a contextual label: "API key / token" reads "Client secret" in OAuth 2.0, "Token" in
Bearer, "Connection string" for Azure Service Bus. The side panels say what to enter, where to get
it at the provider, and what the module actually sends. A length meter warns when a pasted secret
exceeds the 255 characters of the field, which is typically a short-lived Entra token pasted in
Bearer mode instead of an OAuth 2.0 service principal. The rules live in `core/CredentialGuide`
(type refinements included) and the texts in the `credentials.*` keys of the `Messages` bundles.
The guide never changes a value: the native EBX widgets keep validation, permissions and encryption.

### 3.1.1 Secrets, vaults, OAuth 2.0 and mutual TLS

**Secret references.** Any secret field or property may hold a reference instead of the value, so
that the secret lives outside the EBX repository: `env:SIRENE_KEY` (environment variable),
`file:/run/secrets/sirene` (Docker and Kubernetes secrets), `vault:secret/data/ebx/sirene#apiKey`
(HashiCorp Vault KV v1 or v2; address `-Debx.connectors.vault.address` or `VAULT_ADDR`, token
`-Debx.connectors.vault.token`, `VAULT_TOKEN` or `~/.vault-token`, namespace `VAULT_NAMESPACE`),
`azurekv:myvault/sirene-key` (Azure Key Vault; service principal `AZURE_TENANT_ID` / `AZURE_CLIENT_ID` /
`AZURE_CLIENT_SECRET`, or the managed identity of the host), or
`awssecrets:eu-west-3/ebx/sirene#apiKey` (AWS Secrets Manager `GetSecretValue`; optional
`versionStage` or `versionId`; static/temporary environment or JVM credentials, a bounded named
shared-credentials profile, ECS task credentials, then EC2 IMDSv2—never IMDSv1). ECS and IMDS calls
are bounded, refuse redirects, and remain subject to the JVM egress allow-list; or
`gcpsecret:projects/my-project/secrets/sirene-key` (Google Secret Manager; an optional
`/versions/<version>` suffix defaults to `latest`; explicit `GOOGLE_OAUTH_ACCESS_TOKEN`, a bounded
`GOOGLE_APPLICATION_CREDENTIALS` service-account document, then Google metadata identity). Google
responses are bounded, redirects are refused, and the payload CRC32C is verified when supplied.
External-account/workload-identity documents support bounded local-file text or JSON subject tokens,
RFC 8693 STS exchange, and optional service-account impersonation. URL, AWS, and executable subject
sources are refused. A reference typed in the UI is encrypted
like any secret, then resolved when the connector is loaded; resolved values are cached
`ebx.connectors.secrets.cacheSeconds` (300), so a secret rotated in the vault is picked up without
restart. A reference that does not resolve makes the connector unusable, with the reason in the
error ("Vault address not set", "key 'x' not found in Vault secret..."). Other vaults (CyberArk...)
are added with the `SecretResolver` SPI (§ 3.3): one class, one scheme, declared
in `META-INF/services/com.on.ebx.connect.spi.SecretResolver`. A secret that is neither encrypted by the UI nor a reference is refused unless
`-Debx.connectors.secrets.allowClear=true` (since 2026-10-06).

`env:` and `file:` read the machine EBX runs on, on behalf of whoever may edit a connector, and the
value then goes to the host that connector names. Their reach is therefore set at JVM level:

| Property | Effect | Default |
|---|---|---|
| `ebx.connectors.secrets.dir` | the directory of the secret files (`/run/secrets`): a `file:` reference is read only when the file is inside it, `..` and symbolic links resolved; a relative reference (`file:sirene`) names a file of that directory; anything else, an absent file included, answers "no such file in the secrets directory". A directory that does not exist refuses every `file:` reference. **Production setting** | unset: any file the EBX process can read, `ebx.properties` included (one warning in the kernel log) |
| `ebx.connectors.secrets.envAllowed` | what `env:` may read, as names or prefixes: `SIRENE_KEY, EBX_SECRET_*` (a lone `*` matches nothing). A listed name is looked up as a system property (`-DSIRENE_KEY=...`), then as an environment variable. **Production setting** | unset: any environment variable, and no system property (one warning in the kernel log) |

A secret file is 1 MiB at most. System properties are out of reach of `env:` unless listed because
they hold the JVM's own secrets (the Vault token of this module, keystore passwords).

**OAuth 2.0** (`authType = OAUTH2`, for the `rest`, `graphql`, `soap` and any REST-based type): the
token endpoint is `oauth.tokenUrl`, the flow `oauth.flow` = `client_credentials` (default), `password`
(username / password of the connector), `refresh_token` (`oauth.refreshToken`) or `jwt_bearer`
(`oauth.privateKey` = PKCS#8 RSA key in PEM, usually a `vault:` or `file:` reference; `oauth.issuer`,
`oauth.subject`, `oauth.jwtAudience`, `oauth.jwtLifetimeSeconds`, `oauth.claim.<name>` extra claims).
The client id is `oauth.clientId` (or `clientId`), the client secret the **API key / token** field (or
`oauth.clientSecret`), sent in the form body or, with `oauth.clientAuth = basic`, as a Basic header;
`oauth.scope`, `oauth.audience`, `oauth.resource` and `oauth.extra.<name>` complete the request.
Tokens are cached per connector and renewed a minute before they expire; a 401 drops the token and
the call is retried once. Salesforce keeps its own login (client credentials) and accepts
`oauth.flow = jwt_bearer` with a certificate. Its `password` flow still works but is **deprecated**: Salesforce
retires the OAuth 2.0 username-password flow on 20 February 2027, after which those logins fail. The connector
diagnostics and `connectors_describe` show a warning for such a connection; switch it to `client_credentials` (or
`jwt_bearer`) before that date.

**Mutual TLS and private CAs**: `tls.keystore` (PKCS#12 or JKS with the client certificate),
`tls.keystorePassword`, `tls.keyPassword`, `tls.truststore` (a private CA), `tls.truststorePassword`;
passwords may be references. Without them the JVM defaults apply (truststore, proxy settings); there
is deliberately no "trust everything" switch. Kafka takes its TLS options through the `kafka.ssl.*`
properties.

**SFTP host key**: an `sftp` connector verifies the key of the server against the property
`knownHosts` (path of a known_hosts file on the EBX server) or `hostKey` (the public key itself, as
`ssh-keyscan <host>` prints it; several keys may be given). With neither, it refuses to connect and
says which property to set: an unverified server can be anyone on the path, and it receives the
password. `strictHostKey = false` connects without verification, with a warning in the kernel log;
it is meant for a test server.

**Webhooks**: the sender authenticates as an EBX user (Basic or token) holding the RUN right, and the import runs under that
technical account. The shared-secret (`X-Webhook-Secret`) and signature (`X-Webhook-Signature`) modes no longer exist, and a
connection of type webhook stores no secret and no account.

**JDBC connections**: a connector row is data, but a JDBC URL, a driver class, a JNDI name and the `jdbc.*`
properties are what the server loads or runs. By default a JNDI name must start with `java:comp/env/`, the
driver class must be the one of the URL's sub-protocol, the options that load a class, run SQL or read or
write a file are refused (H2 `INIT`, `socketFactory`, `sslfactory`, `loggerFile`, MySQL `allowLoadLocalInfile`,
`autoDeserialize`...), an H2 URL must be `tcp:`, `ssl:` or `mem:`, and an in-memory H2 refuses `CREATE ALIAS`
and file functions. Each refusal names the `ebx.connectors.jdbc.*` property of the table above that allows it.

**Who may edit connectors**: a `baseUrl` sends EBX data wherever it points. Reserve the Connectors
dataset to an administration role, and keep the history of the Connector table on.

### 3.1.2 Hardening: outbound allow-list, redaction, retention, administration role

JVM properties, set by the infrastructure and therefore out of reach of the EBX user interface:

| Property | Effect | Default |
|---|---|---|
| `ebx.connectors.allowedHosts` | the hosts the module may call: `api.insee.fr, *.api.gouv.fr, kafka.internal:9082, localhost`; `none` refuses everything. Every HTTP call (connectors, token endpoints, vault) is checked, and so are the base URLs of JDBC, SFTP and Kafka connectors when they are loaded: a connector pointing elsewhere is refused with an explicit message. `file:/etc/ebx/egress.txt` reads the patterns from a file (one per line, `#` comments) re-read when it changes: the operations team adds a host without restart, and the file's permissions decide who may. A missing or unreadable file refuses everything. An IPv6 address is written `[2001:db8::1]` or `[2001:db8::1]:8443` | unset: no restriction (one warning in the kernel log) |
| `ebx.connectors.egress.requireAllowList` | `true`: as long as `ebx.connectors.allowedHosts` is not set, every outbound call is refused with a message naming both properties, instead of all being allowed. **Production setting** | unset: an absent list allows everything |
| `ebx.connectors.egress.blockPrivate` | **on by default** (`false` switches it off): a host that is, or resolves to, a loopback or private address (127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10, `::1`, `fc00::/7`), or that does not resolve, is refused, redirect hops included, unless `ebx.connectors.egress.privateExceptions` lists it (`localhost:9092`, `192.168.1.5`, `10.1.0.0/16`, or `file:<path>`; it does not turn the allow-list on) or an **exact** entry of the allow-list names it | on |
| `ebx.connectors.egress.privateExceptions` | the private targets that stay allowed, see above: for a local demo server `localhost:9092,localhost:9095,localhost:9082,localhost:8080,localhost:11434` (and the `127.0.0.1:` forms if connections use the address) | unset |
| `ebx.connectors.role.designer` (older name: `ebx.connectors.adminRole`) | the name of the designer role (MANAGE). Since audit C-01 (tables) the rights apply to every table of the Connectors dataset whether the property is set or not: only MANAGE (designers, administrators) edits the configuration tables, RUN and READ see them read-only, a session without a right does not see the rows (docs/OPERATIONS.md section 4, "Rights on the tables") | `ConnectorsDesigner` |
| `ebx.connectors.log.retentionDays` | **On by default, 90 days.** Sync log rows, decided conflicts, finished runs (the `ExecutionRun` rows read by `connectors_run_get`) and publication reports older than N days are deleted by a background task of the module, once an hour, 500 rows per transaction. An open conflict and a running run are never deleted; field provenance and outbound states are not purged unless asked (`.outboundStateDays`, `.provenanceDays`: only the states of deleted records and the provenance of records that no longer exist). A run whose rows are purged can no longer be replayed or resumed. Each table can have its own age, or `0` to be kept for ever: `ebx.connectors.retention.syncLogDays`, `.conflictDays`, `.executionRunDays`, `.publicationDays` | 90 (`0`: nothing is ever deleted) |
| `ebx.connectors.log.storeChanges` | `false` keeps the values and outbound documents out of the sync log (personal data); the message and the status remain | `true` |
| `ebx.connectors.nodeId` | the name of this EBX node in the lease of a running import; give every JVM that shares a repository its own (two JVMs on one host included). A node that starts interrupts the runs left RUNNING by its own earlier life and those whose lease ran out, never a run another node still holds | the host name |
| `ebx.connectors.run.leaseSeconds` | how long the lease of a running import lasts after each batch (60 to 3600) | `1800` |
| `ebx.connectors.secrets.hideProperties` | `false` shows the `value` of a credential property (`oauth.clientSecret`, `oauth.refreshToken`, `oauth.privateKey`, a key containing `password`, `token`, `secret`...) to every session that may read the table; by default only the designers, the administrators and the system user see it, in the user interface, Data Services and the `data-access` MCP alike. The connector's own `password` and `apiKey` fields are stored encrypted | `true` |
| `ebx.connectors.jdbc.jndiPrefixes` | JNDI prefixes a JDBC connector may look up besides `java:comp/env/` (`java:/`...). A connector row naming anything else is refused | `java:comp/env/` only |
| `ebx.connectors.jdbc.allowedDrivers` | driver classes allowed besides the built-in table: a driver must serve the sub-protocol of the URL (`org.postgresql.Driver` for `jdbc:postgresql:`...) | the built-in table |
| `ebx.connectors.jdbc.allowedOptions` | option names exempted from the refusal of the URL options and `jdbc.*` properties that load a class, run SQL or touch a file (H2 `INIT`, `socketFactory`, `sslfactory`, `loggerFile`, MySQL `allowLoadLocalInfile`, `autoDeserialize`, `queryInterceptors`...) | none exempted |
| `ebx.connectors.jdbc.allowH2File` | `true` allows file databases (`jdbc:h2:file:`, `~/x`, a bare name); `tcp:`, `ssl:` and `mem:` are always allowed | `false` |
| `ebx.connectors.jdbc.allowH2Code` | `true` allows `CREATE ALIAS`, `CREATE TRIGGER`, `FILE_READ`, `CSVREAD`... on an in-memory H2, which lives in the EBX JVM | `false` |

Two outbound rules apply whatever the settings. **Link-local and cloud metadata addresses**
(169.254.0.0/16, fe80::/10, `fd00:ec2::254`, `metadata.google.internal`, the Alibaba and Oracle
metadata addresses) are refused, by literal address and by the addresses a name resolves to, unless
an exact entry of the allow-list names the host: they hand out the credentials of the machine. (A
call that leaves through the JVM's HTTP proxy is judged on the host written in its URL: the proxy
resolves the name.) The module's own workload-identity lookups (`awssecrets:`, `gcpsecret:`, `azurekv:` with the identity of
the host) are not concerned, but stay subject to the allow-list when there is one. **Redirects** are
never followed blindly: at most 5 hops, each one checked like a first call (allow-list and address
rules); when a hop leaves the host or port that was called, only `Accept`, `Accept-Language` and the
`Content-*` headers travel on, so `Authorization`, API-key headers, cookies and signatures stay with
the server they were meant for; a redirect to another scheme (https to http or the reverse) is
returned as it is. The same rules cover the download of an OpenAPI specification by URL (http or
https only, 16 MiB at most). For production: set `ebx.connectors.allowedHosts`,
`ebx.connectors.egress.requireAllowList=true` and, where it fits,
`ebx.connectors.egress.privateExceptions` for the private hosts the connectors really call (the private-host block itself is on by default).

Whatever the settings, the texts the module keeps or returns (sync log, REST answers, workflow
outputs, kernel log) are **redacted**: the secret values of the loaded connectors, the secret query
parameters of URLs (`key`, `api_key`, `token`, `signature`...) and echoed `Authorization` headers are
replaced by `***`. Since `ebx.connectors.secrets.allowClear` is not true by default (§ 3.1.1) the repository does not serve
a secret in clear. Together with the vault references, OAuth 2.0 and mutual TLS of § 3.1.1, this is
the set an architect expects before a production deployment; what remains is the customer's: the
history of the Connector table, the retention of EBX's own audit, and the governance of what the LLM
connectors are allowed to receive. That includes images and scans when a `documents` connector has
`vision = true` (§ 14.8): photos, signatures and identity documents then reach the model provider.

### 3.1.3 Who may do what: the three Connectors rights

Nothing is open by default. A caller who is neither an EBX administrator nor a member of a role that
holds one of the three rights below is refused every MCP tool (on `/ebx-connectors/rest/mcp`, on the native MCP server and
on the in-JVM bridge alike), every REST route of `/ebx-connectors/rest/v1` (answer `403`) and every
user service of the module (the action is neither shown nor executable). `tools/list` only lists the
tools the caller may call.

| Right | Default role | What it opens |
|---|---|---|
| READ (viewer) | `ConnectorsViewer` | list and describe the connections (secrets masked), list and read the mappings, read a run and the sync log, plan a catalog source; the Connection catalog and Connector operations screens |
| RUN (operator) | `ConnectorsOperator` | READ, plus: `connectors_sync`, `connectors_enrich`, `connectors_replay`, `connectors_run_stop`, `connectors_publish`, `connectors_lineage_declare`; the outbound documents and the conflicts (they carry record values) and the decision on a conflict; the "Synchronise with an external application" action and the conflict manager |
| MANAGE (designer) | `ConnectorsDesigner` | RUN, plus: every change of configuration (`connectors_mapping_upsert`, `connectors_mapping_delete`, `connectors_config_import`, `connectors_openapi_import`, templates, applying a catalog source), `connectors_config_export`, `connectors_call` (any operation of a connection, with its stored credentials), describing the fields of an operation (it samples the remote system); the mapping designer, the diagnostics, the OpenAPI import and the deletion of a mapping |

**Giving the rights: the "Access rights" table.** An administrator maps the rights onto roles that
already exist in the directory, in the user interface: perspective *Data connector* → *Administration* →
*Access rights* (table `AccessRight` of the Connectors dataset). One row = one role of the directory
(picked from the list of existing roles, or "Everyone") and its right; a comment says why. A person
receives the widest right any of their roles brings. A change applies within a few seconds, without a
restart. Only an EBX administrator can edit this table: a designer cannot give rights.

**The default language-model connection: the "Settings" group.** The other ON EBX modules (MDM Factory,
data catalog) ask a language model through Connectors; when they do not name a connection, they get the
one an administrator chose in *Default language-model connector*, in the group *Settings* of the
Connectors dataset (dataspace and dataset `Connectors`, navigation pane). Only a connection of type `llm`
is accepted. Empty: the JVM property `-Debx.connectors.llm.default`, else the only usable `llm`
connection when there is exactly one. Read at each call, no restart. Only an EBX administrator changes
it. Whatever a module asks, an answer is never longer than the `maxTokens` property of the connection
(1024 unless set): raise it on the connection when a module needs longer answers.

Without any row, three role names work out of the box (`ConnectorsViewer`, `ConnectorsOperator`,
`ConnectorsDesigner`): create them in the directory if you prefer dedicated roles. They can be renamed
with `-Debx.connectors.role.viewer`, `-Debx.connectors.role.operator` and `-Debx.connectors.role.designer`
(`-Debx.connectors.adminRole` is still read as the designer role). The table and these roles add up.
A refusal is written to the kernel log (`[connectors] access denied: user ..., tool ...`).

Three things to know:

- **Workflows and scheduled tasks are not concerned.** A workflow script task, a scheduled task and the
  table trigger run what a designer configured, under the session EBX gives them; the control point is
  who may configure the mapping and publish the workflow.
- **A webhook** needs an EBX user with the RUN right; the shared secret and the signature were removed.
- **The rights open the module's entry points, not the tables.** Reading or editing the rows of the
  Connectors dataset through EBX itself (user interface, data services) stays governed by the EBX
  permissions of that dataset, plus the access rule of § 3.1.2 when the designer role is set by property.

**Calls that change something must not come from another site.** A `POST`, `PUT`, `PATCH` or `DELETE`
on `/ebx-connectors/rest/...` (the MCP endpoint included) is accepted only with an `Authorization`
header (what `curl -u`, a script or an MCP client sends), or an `X-Requested-With` header, or from a page
of this EBX (the browser's `Origin` names the same host and port). Anything else is answered `403` and written to the kernel log
(`[connectors] cross-site request refused: ...`): a page of another site cannot ride the session of a
signed-in user. Send the credentials with the request itself: a client that waits for a `401` before
authenticating may get this `403` instead.

**Who ran or changed what.** Every MCP tool call and every REST route that asks RUN or MANAGE leaves one
line in the kernel log, whether it succeeded, failed or was refused:
`[connectors] audit: user=alice tool=connectors_sync mapping=12 dryRun=true outcome=ok ms=532` (a REST route
reads `rest=sync ... status=200`). The line names the user, the tool or route, the connection, mapping,
run or conflict concerned, the outcome and the duration; it never carries a document, an input, a record
key or a secret. Reads are not traced. Actions started from the screens, workflows and scheduled tasks
are found in the sync log and the runs, not in this line.

### 3.2 A REST connector without Java: the `rest` type

The `rest` type is declared entirely through properties, one operation = three properties:

```
op.<name>.method = GET | POST | PUT | DELETE     (GET/DELETE = read, POST/PUT = write)
op.<name>.path   = /w/api.php                    ({placeholders} = mapping inputs, URL-encoded)
op.<name>.query  = action=wbsearchentities&search={name}&language=en&format=json&limit=1
```

A real example, the `Wikidata` connector (`baseUrl = https://www.wikidata.org`, auth `NONE`):

```
op.search.method = GET
op.search.path   = /w/api.php
op.search.query  = action=wbsearchentities&search={name}&language=fr&format=json&limit=1&type=item
op.claims.method = GET
op.claims.path   = /w/api.php
op.claims.query  = action=wbgetclaims&entity={id}&format=json
```

The mapping provides `name` (or `id`) as an input; the returned document is the raw JSON of the API.

### 3.3 A connector in Java

A class implementing `com.on.ebx.connect.spi.Connector` (or extending `AbstractRestConnector`):
`getType()`, `getLabel()`, `getOperations()`, `configure(ConnectorConfig)`, `execute(context, operation,
inputs, outboundDocument)` returning a `JsonNode` (or `null` = no data), and optionally
`describeFields(...)` to feed the designer. Built-in types are registered in `ConnectorRegistry`.

**Without the module sources: an extension jar.** The module also loads every implementation declared
through the standard `java.util.ServiceLoader` mechanism, so a third party adds a type by shipping a jar:

1. compile against **`dist/ebx-connectors-spi.jar`** (built by `build-spi.sh`: the `spi` package plus
   the `AbstractRestConnector`, `HttpJsonClient` and `Json` helpers; a sources jar sits next to it), with
   `ebx.jar` (or `ebx-lib.jar` on FastTrack) and Jackson on the classpath, Java 8 syntax (`--release 8`);
2. add the file `META-INF/services/com.on.ebx.connect.spi.Connector` containing the class name (one per
   line); the class needs a public no-arg constructor;
3. drop the jar into `ebx-connectors/WEB-INF/lib/` (third-party libraries the connector needs go there
   too, or to Tomcat's shared `lib`), restart: the type is in the `Connector/type` list, its operations
   and fields in the designer.

The project **`ebx-connectors-extension-sample`** (next to the module) is a complete, buildable example:
an OpenStreetMap Nominatim geocoder (`nominatim` type, operations `geocode` and `reverse`, a
`user.agent` property turned into a request header by overriding `headers()`), its `build.sh`, its
services file and Eclipse files. Copy it to start your own connector.

The same jar may declare a **secret resolver** (`com.on.ebx.connect.spi.SecretResolver`: one scheme, one
`resolve(reference)` method, listed in `META-INF/services/com.on.ebx.connect.spi.SecretResolver`) to read
secrets from another vault (§ 3.1.1).

### 3.3.1 Publishing a whole product: the `TablePublisher` SPI

A connector able to receive a **whole data product** (§ 10.2) additionally implements
`com.on.ebx.connect.spi.TablePublisher`, EBX-free like the rest of the SPI: `nativeType(column)` (the
native type used for a column, for reports and DDL), `plan(spec, recreate)` (the statements a real run
would execute, nothing run — the dry-run path), `prepare(ctx, spec, recreate)` (schema, control table,
target and staging tables; returns `CREATED`/`EXISTS`/`RECREATED` per table), `load(ctx, spec, table,
documents)` (loads one batch into a table's staging area), `commit(ctx, spec, rowsPerTable)` (the single
atomic step: every target table replaced by its staging content, in one request), and `cleanup(ctx,
spec)` (drops the staging tables, best effort, called after commit and after any failure). Identifiers
(schema parts, table and column names) are validated and upper-cased by the caller
(`[A-Z_][A-Z0-9_]*`) before the SPI ever sees them; the implementation only needs to double-quote them.
`connectors/SnowflakePublicationSql` (package-private, dependency-free, unit-tested directly) is the
reference implementation, consumed by `SnowflakeConnector implements TablePublisher`: type mapping,
`DESCRIBE TABLE`/`CREATE TABLE`/`CREATE OR REPLACE TABLE`, a `FLATTEN`-based batch insert into a
`__STG` staging table, and a single multi-statement `BEGIN … COMMIT` request (Snowflake SQL API
parameter `MULTI_STATEMENT_COUNT`) for the atomic commit. `core.ProductPublisher` (§ 10.2) is the only
caller of this SPI; a connector that does not implement it simply cannot receive a publication
(`connectors_publish` answers a clear `ERROR` naming the connector and its type).

### 3.4 Creating a connector from an OpenAPI specification

The module reads an **OpenAPI 3** specification (JSON, or YAML thanks to `jackson-dataformat-yaml`
and `snakeyaml` in the Tomcat classpath) and turns it into a connector of type `rest`:

| In the specification | In the connector |
|---|---|
| `servers[0].url` (variables replaced by their default) | `baseUrl`; relative → to complete by hand, reported |
| `securitySchemes`: apiKey in header / in query, http basic, http bearer, oauth2 | `authType` + `apiKeyHeader` (or property `auth.apiKeyQuery`); the secret stays to be entered |
| each `paths.{path}.{get\|post\|put\|delete}` | `op.<operationId>.method` / `.path` / `.query` (query parameters as `name={name}`); without `operationId`, the name comes from the path |
| path parameters and `required` parameters | `op.x.required` (list; `-` = none mandatory); the others are optional and not sent when empty |
| `summary` / `description` | `op.x.description`, shown in the operation list |
| schema of the 200 response (JSON) | `op.x.fields`: flattened `path=type`, arrays as `name[0]`, `$ref` resolved, `allOf` merged; feeds the designer's input tree without any call |
| schema of a POST/PUT body | `op.x.bodyFields`: the fields offered on the output side of an outbound mapping |

An operation whose answer is a list (`[0]...` or `results[0]...`) is of kind **search**. Header and
cookie parameters are ignored and reported; `oneOf`/`anyOf` take the first branch; at most 300 fields
and 6 levels.

Two ways to import:

- **User interface**: on the table view of the `Connector` table, action **Import an OpenAPI
  specification**: id, label, URL *or* pasted text, replace an existing connector (its secrets are
  kept), preview without creating.
- **API**: `POST /ebx-connectors/rest/v1/connectors/openapi?id=RechercheEntreprises[&label=][&replace=true][&dryRun=true]`
  with the specification as the body, or `&url=https://recherche-entreprises.api.gouv.fr/openapi.json`.

```bash
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/connectors/openapi?id=RechercheEntreprises&url=https://recherche-entreprises.api.gouv.fr/openapi.json"
```

Answer: the connector created or updated, `baseUrl`, `authType`, the operations with their mandatory
inputs, the number of properties, and the notes (what remains to do by hand). The connector is then
edited like any `rest` connector; importing again with `replace` refreshes operations and fields
without touching the credentials.

### 3.5 Power BI for the data catalog: the `powerbi` type

`PowerBiConnector` reads a Power BI tenant for the data catalog (`ebx-data-catalog`): reports,
dashboards, semantic models with their tables, columns, calculated columns and measures (DAX kept as
the column's `expression`), dataflows and their entities, and the lineage between all of them and
their data sources. It needs no library: Power BI REST API v1.0 over `HttpJsonClient`.

**Connector row**: `baseUrl` = `https://api.powerbi.com/v1.0/myorg`, `authType` = `OAUTH2`,
properties `tenantId` and `clientId` (a Microsoft Entra service principal), the client secret in
`apiKey`; the token endpoint and the scope `https://analysis.windows.net/powerbi/api/.default` are
derived. A user token in `apiKey` with `authType` = `BEARER` also works for a quick try. If the
outbound allow-list is enforced (§3.1.2), allow `api.powerbi.com` and `login.microsoftonline.com`.

**Two scan modes** (property `scanner`, default `auto`):

- `admin` — the Scanner API (`admin/workspaces/getInfo` with `lineage`, `datasourceDetails`,
  `datasetSchema`, `datasetExpressions`, then `scanStatus` polling and `scanResult`; dataflow
  definitions through `admin/dataflows/{id}/export`). Tenant-wide, with table schemas, Power Query M
  and DAX. The service principal must be allowed by three tenant settings: *Allow service principals
  to use read-only admin APIs*, *Enhance admin APIs responses with detailed metadata* and *Enhance
  admin APIs responses with DAX and mashup expressions*.
- `standard` — `groups`, `reports`, `datasets`, `dashboards` (+ tiles), `dataflows` of the workspaces
  the principal is a member of: objects and links only, no schema, no expression.
- `auto` tries `admin` and falls back on `standard` on a 401/403. `testConnection` says which mode
  is available.

**Operations** (all inbound; `source` = the catalog source id, prefix of every asset key;
`schemas` = comma-separated workspace names or ids, empty = every non-personal workspace):

| Operation | Document | Notes |
|---|---|---|
| `catalog` | `{tables:[{assetKey, qualifiedName, schema, name, kind, url, remarks, columns:[{columnKey, name, dataType, nativeType, expression…}]}]}` | kinds `REPORT`, `DASHBOARD`, `SEMANTIC_MODEL`, `DATAFLOW`, `TABLE` (model tables and dataflow entities); measures and calculated columns are columns with `nativeType` `MEASURE` / `CALCULATED …`; `types` selects kinds (a database-style `TABLE,VIEW` selects everything) |
| `lineage` | `{edges:[{edgeKey, fromAssetKey, fromColumnKey, toAssetKey, toColumnKey, transformationType, transformation, confidence, seenAt}]}` | data source table → model table (Power Query, table level + one edge per pass-through column, renames followed), column → calculated column or measure and measure → measure (DAX), model → report, report or model → dashboard (tiles), dataflow → model, data source → dataflow entity, model → model (composite) |
| `usage` | `{items:[{assetKey, queryCount30d}]}` | `ViewReport` / `ViewDashboard` events of `admin/activityevents` over `days` (≤ 30) |
| `refreshes` | `{jobs:[{jobKey, namespace, name, orchestrator, assetKey, lastRunAt, lastRunStatus, runs:[{externalRunId, startedAt, endedAt, status, message}]}]}` | refresh history of the models (`datasets/{id}/refreshes`) and dataflows (`transactions`) the principal can read |
| `workspaces` | `{items:[{id, name, type}]}` | optional input `filter` |
| `query` | `{columns, rows, count}` | `executeQueries` on a semantic model: inputs `dataset` (id or `workspace/name`) and `dax`; `Table[Column]` keys stripped to `Column` |
| `scan` | the raw Scanner API document | diagnosis |

**Asset names**: `<workspace>/reports/<name>`, `<workspace>/dashboards/<name>`,
`<workspace>/models/<model>` and `<workspace>/models/<model>.<table>`, `<workspace>/dataflows/<name>`
and `<workspace>/dataflows/<name>.<entity>`.

**Joining the lineage to the other sources** — property `datasource.map`, one
`server[/database]=SOURCE[:upper|:lower]` per line (or `;`-separated). A Power Query source
matching an entry is keyed `SOURCE:<schema>.<table>` (names upper- or lower-cased as that source
spells them), i.e. exactly the key the scan of that source produces, so the edge lands on the real
table: `xy12345.snowflakecomputing.com=DWH:upper`. An unmapped source becomes a `TABLE` asset of the
Power BI source itself, `datasources/<server>[/<database>]/<schema>.<table>`, with the columns
inferred from the model tables that read it (`external.assets=false` drops these edges instead).

The Power Query reader understands the navigation records of the usual sources (`Sql.Database`,
`Snowflake.Databases`, `Databricks.Catalogs`, `GoogleBigQuery.Database`, `PostgreSQL`, `MySQL`,
`Oracle`, files, `PowerPlatform.Dataflows`, `AnalysisServices`), model parameters and shared
expressions, `[Query=…]` / `Value.NativeQuery` (tables read from `FROM` / `JOIN`),
`Table.RenameColumns`, `Table.AddColumn`, `Table.RemoveColumns`, and types the table edge from the
steps: `Table.SelectRows` → `FILTER`, `Table.Group` → `AGGREGATION`, `Table.NestedJoin` /
`Table.Combine` → `JOIN` (with an edge from the joined query when it is a table of the same model),
other `Table.*` → `TRANSFORMATION`, renames and type changes only → `IDENTITY`. The DAX reader picks
`Table[Column]`, `'Table name'[Column]` and bare `[Measure]` references (strings and comments
ignored); a measure calling an aggregate (`SUM`, `CALCULATE`, `COUNTROWS`…) gives `AGGREGATION`.
Anything not understood yields a table-level edge, never a failure, and a log line per unresolved
table.

Other properties: `include.personal`, `include.hidden`, `dataflow.definitions`, `scan.timeout`
(seconds), `max.workspaces`, `portal.url` (asset URLs), `max.rows` (DAX query). Verified offline
only: `PowerBiScanTest` (a recorded scan result → exact catalog and lineage documents) and
`PowerBiConnectorTest` (a fake tenant: token, Scanner API polling, activity log, refresh history,
DAX query, standard fallback), both in `build-test.sh`. Nothing has been run against a real tenant.

### 3.6 Collibra, both directions: the `collibra` type

`CollibraConnector` talks to a Collibra Data Intelligence Platform through its REST API 2.0
(`https://<instance>.collibra.com/rest/2.0`, the suffix is added to `baseUrl`). `authType` = `BASIC`
(a Collibra user with API access) or `BEARER` / `OAUTH2` (a JWT from the identity provider of the
instance). No library. If the outbound allow-list is enforced, allow the instance host.

Everything Collibra identifies by UUID is addressed **by name**: communities, domains, asset types,
attribute types, statuses, and relation types written `Source type>role>Target type` (an exact
match first, then role + target type, then role alone — the choice is logged; a UUID is accepted
anywhere a name is). Names are resolved once and cached per connector instance. Properties:
`community` (where domains are created on demand and names resolved), `domain.type`
(`Physical Data Dictionary`), `term.type` (`Business Term`), `relation.contains`
(`Table>contains>Column`), `relation.lineage` (`Column>is source for>Column`),
`relation.lineageTable` (`Table>is source for>Table`), `relation.term`
(`Column>represents>Business Term`), `attribute.description` / `definition` / `dataType` /
`note` / `url` (aliases of the attribute type names: `Description`, `Definition`,
`Technical Data Type`, `Note`, `URL`), `max.rows`. Check the names of an instance with
`types` (`kind` = `assetTypes`, `attributeTypes`, `relationTypes`, `statuses`, `communities`,
`domains`, `domainTypes`).

| Operation | Direction | Inputs / payload | Document |
|---|---|---|---|
| `search` | in | `name` (+ `match` = EXACT, START, ANYWHERE), `type`, `domain`, `community`, `status`, `limit`, `attributes=true` | `{items:[{id, name, displayName, type, domain, community, status, url, attributes?}]}` |
| `get` | in | `id`, or `name` + `domain` (+ `community`) | one asset with `attributes` and `outgoing` / `incoming` relations |
| `terms` | in | `type` (default `term.type`), `domain`, `community`, `name`, `limit` | `{items:[{id, code, name, definition, description, note, status, domain, community, url}]}` — a glossary feed |
| `types` | in | `kind`, `name` | `{items:[{id, name, spec?...}]}` |
| `upsert` | out | `name`, `displayName`, `type`, `domain` (created on demand), `community`, `status`, `parent` (+ `parentRelation`), `term` (+ `termRelation`), `attributes.*`, `relations[]` | `{id, name, created, attributesSet, relationsCreated, url}` |
| `relate` | out | `sourceId` / `targetId`, or `source` + `sourceParent` + `sourceDomain` and the same for `target`; `type` | `{created, sourceId, targetId}` or `{created:false, skipped}` |
| `import` | out | payload = an array of Collibra import commands; `wait`, `timeout`, `simulation` | `{jobId, state, message, result?}` |
| `delete` | out | `id` | `{deleted}` |

`upsert` identifies an asset by **name in its domain**; with a `parent`, the name becomes
`<parent> > <name>` and the display name the bare name — the convention of Collibra's own catalog
ingestion, which keeps column names unique in a domain. Attributes are created or patched only when
they differ; relations are created only when missing. Inputs and payload fields share their names
and an input wins, so an outbound mapping can set everything through rules
(`./qualifiedName → name`, `./description → attributes.description`, `./asset → parent` with a
`lookup`...) and a mapping input can add a constant. Verified offline only
(`CollibraConnectorTest`, an in-memory Collibra built from the public API reference): no real
instance has been called — the relation and attribute type names of the customer's operating model
are the first thing to check with `types`.

### 3.7 Lineage of every data flow: the `openlineage` type

Declare **one** connector of type `openlineage` — base URL = the endpoint that accepts
[OpenLineage](https://openlineage.io) `RunEvent`s, with its authentication — and every **outbound run**
of the module describes itself there, nothing else to configure:

| Endpoint | Base URL | Authentication |
|---|---|---|
| ON EBX data catalog (`ebx-data-catalog`) | `http://host/ebx-data-catalog/rest/v1/openlineage` | EBX Basic |
| Marquez | `http://marquez:5000/api/v1/lineage` | none / API key |
| DataHub, Atlan, OpenMetadata... | their OpenLineage ingest endpoint | as documented there |

One event per **mapping and per run** (`ConnectorService.beginRun()` / `endRun()`: a REST call, a
workflow step, a scheduled run, a user-service run; a table trigger or a single call sends its own):

- `job` = the mapping (namespace `job.namespace`, default `ebx-connectors`; name = the mapping's label);
- `inputs` = the EBX table, named as the data catalog names it — namespace `ebx.namespace`
  (**the `lineageNamespace` of the EBX source in the catalog**, default `ebx://localhost:8080`),
  name `<dataspace>/<dataset><tablePath>` (`Companies/Companies/root/Companies`);
- `outputs` = the connector's object: namespace from the connector's base URL
  (`h2://localhost:9092/catalogdemo`, `postgresql://db:5432/mdm`, `snowflake://acme.snowflakecomputing.com`,
  `salesforce://acme.my.salesforce.com`...), name from the mapping's object input (`table`, prefixed
  with the connector's `schema` property when it has no dot; `object`, `sobject`, `topic`, `path`...),
  else the operation name — so that it matches what a scan of the same platform creates. On a broker
  (`kafka`, `rabbitmq`, `jms`, `activemq`, `azure-service-bus`) the object is the `topic` / `queue`
  input, else the connector's `topic` property — never the `table` input, which only labels the
  document there; a base URL without a scheme (`localhost:9082`, `broker1:9092,broker2:9092`) gives
  `kafka://localhost:9082`, the first bootstrap server;
- `columnLineage` on the output: one entry per rule with a source (`Identification/Name` →
  `NAME`, subtype `IDENTITY`, or `TRANSFORMATION` with the pipeline as description; constants left out);
- **sub-object mappings, at any depth, bring their own EBX table** (dataset and dataspace of the root
  when they have none): outbound, the child table is an extra input and the document fields
  `collection[].field` point at it, not at the root table; inbound, the child table is an extra
  output whose fields point at `collection[].field` of the connector's object;
- run facets: `outputStatistics.rowCount` = records sent, `errorMessage` when nothing went through
  (`eventType` `FAIL`), and `ebx_connectors` (run id, mapping, connector, operation, sent / deleted /
  failed / skipped). The OpenLineage `runId` is derived from the Connectors run id and the mapping,
  so a **replay updates the same run**.

Properties of the sink: `emit.outbound` (default `true`), `emit.inbound` (default `false`: imports
and enrichments described too, EBX becoming the output and the rules read the other way),
`exclude.types` (default `chat-webhook,slack,openlineage,llm`), `exclude.connectors` (ids), `producer`.
Nothing is sent for a simulation or for a run where no record went through; a sink that refuses an
event is logged (`WARN`, kernel log) and never fails the synchronisation. The list of sinks is re-read
every thirty seconds (at once when a sink is edited). Operation `emit` posts a hand-built event (or
an array) as is, for an outbound mapping that builds its own events.

#### Declared lineage: before the first run

A flow shows in the catalog **as soon as it is declared**, not only after it has run. The triggers
of `Mapping`, `MappingInput` and `MappingRule` (`MappingCascadeTrigger`, `MappingPartLineageTrigger`)
hand the mapping to `core/DeclaredLineage`, which describes it two seconds after the last change —
outside the transaction, so that a designer save or a configuration import gives one event per
mapping and reads the committed definition:

- an **active** root mapping is sent as an OpenLineage **`JobEvent`** (no `run`, no `eventType`): the
  same job name, datasets and column lineage as a run event, plus the job facet
  `ebx_connectors_declaration` (`state` `DECLARED`, mapping, connector, operation, direction). A
  sub-object mapping is described with its root, its table included, inbound as well as outbound;
- writes that run **without triggers** schedule the same declaration themselves: the MCP tools
  `connectors_mapping_upsert` and `connectors_mapping_delete` (and the REST `DELETE /mappings/{id}`),
  the deletion also describing again the root the sub-object mapping belonged to;
- a **deactivated or deleted** mapping, and the **old name** of a renamed one, is sent as a
  retirement: a `JobEvent` without datasets (`state` `RETIRED`);
- at **repository startup** every mapping is swept once (after 90 s, retried twice two minutes apart
  when a sink was not reachable yet), which also catches up the mappings declared before this existed;
- **on request**: `POST /ebx-connectors/rest/v1/lineage/declare[?mapping=<id or label>]` or the MCP
  tool `connectors_lineage_declare` — after adding a sink or changing a connector's base URL (changes
  to a connector are not watched).

The data catalog takes a job event as the job's **whole current lineage**: edges written with origin
`DECLARED` (confidence 90) unless already observed, the job's edges it no longer lists deactivated,
the job's last run left untouched; the first run then turns the edges into `OPENLINEAGE`. The sink
property `emit.declared` (default `true`) turns declarations off for one sink; the direction and
exclusion properties apply as for runs (an inbound mapping is declared only with `emit.inbound=true`).
System properties: `ebx.connectors.lineage.declared` (default `true`, the whole mechanism),
`ebx.connectors.lineage.declareOnStartup` (`true`), `ebx.connectors.lineage.startupDelaySeconds`
(`90`), `ebx.connectors.lineage.debounceMs` (`2000`). The triggers are declared in the model: they
take effect after the Connectors model is reloaded and Tomcat restarted.

#### Recognising the ON EBX data catalog (`core/CatalogDetection`)

The module never takes a dataset for the data catalog because of its name: a customer may have a
`DataCatalog` dataset of its own, with nothing in common. Two independent checks, and on doubt nothing is
proposed or written:

- **local** — the dataset `-Debx.catalog.dataspace` / `-Debx.catalog.dataset` (default
  `DataCatalog` / `DataCatalog`, the JVM properties the catalog reads too) is the catalog only when its
  data model is `/WEB-INF/ebx/schemas/DataCatalog.xsd` **packaged by module `ebx-data-catalog`**
  (`Adaptation.getSchemaLocation()`). A DMA publication, another module's model or an unreadable one is
  `FOREIGN`: its Sources are never read, and the advice says why;
- **remote** — an `openlineage` connector's endpoint is the catalog only when its new read operation
  `contract` (`GET` on the base URL) answers `module = ebx-data-catalog`, with `contractVersion`,
  `features` (`runEvents`, `jobEvents`, `sources`) and `datasetState` (`VERIFIED`, `ABSENT`, `FOREIGN`).
  Read at most every five minutes per sink and base URL. A catalog that announces no `jobEvents`, or whose
  dataset is `FOREIGN`, receives no declaration; any other answer, or none (Marquez, DataHub, a catalog
  older than the contract), leaves the sink exactly as before.

`connectors_mapping_upsert` and `connectors_mapping_delete` answer a **`lineage`** block, and
`connectors_lineage_declare` (REST `POST /lineage/declare`) the same without the mapping part:
`declaration` (scheduled, retirement scheduled, not scheduled for a dry run, or disabled), the connector's
OpenLineage `namespace`, `catalog` (`state`, `dataspace`, `dataset`, `reason`, and `source` = the catalog
Source whose `lineageNamespace` is that namespace), `sinks` (id, what the endpoint is, contract version
and features, `takesThisMapping`) and `advice`: create an `openlineage` sink when the catalog is deployed
and nothing feeds it; point one at the catalog when none answers as such; create the Source of the system,
or set `lineageNamespace` on the Source that already names this connector, when no Source has the
namespace (otherwise the catalog files the lineage under a separate source created for that namespace);
and when the namespace **is** held, but by a Source without connector — one the catalog created from
lineage events — while another Source names this connector, say that the system is listed twice and which
Source to fix (`catalog.sourceConnector` is then empty). Seen on 2026-09-15 with the Snowflake demo:
`OL_SNOWFLAKE_ACCOUNT_…` held `snowflake://ACCOUNT.snowflakecomputing.com`, `SNOW_DEMO` another namespace.
Offline contract: `src/test/.../core/CatalogDetectionTest`.

**The catalog's own flows are not lineage.** The mappings the catalog writes for itself — `Catalog scan`,
`profile`, `sample`, `usage`, `lineage`, `jobs`, `access grant` / `revoke`, `alert`, the Collibra
publication — have the catalog dataset as their EBX side. Described as lineage (as they were until
2026-09-15 with `emit.inbound=true`), they showed up in the catalog as "tables" named after their
operation (`catalog`, `profile`, `execute`…) under the platform's source. `exclude.catalogFlows` (sink
property, default `true`) leaves them out: no run event, and at each declaration (startup sweep, change)
a retirement instead, which deactivates the edges an earlier version declared. Only a verified catalog
dataset counts (`CatalogDetection.isCatalogFlow`); the flows of a customer's same-named dataset are described as any other.
The assets and columns those declarations created stay: delete them once. `connectors_lineage_declare`
reports such a mapping as `RETIRED_CATALOG_FLOW` (counted in `retired`), not `DECLARED`.

**Naming the connector's side.** The object an input names (`table`, `topic`, `entitySet`…, with the
connector's `schema` in front of a bare table) — else, for a feed that only carries a query (`sql`,
`query`, `statement`), the first table of its `FROM` (quotes, backticks and brackets removed; a templated
table is not guessed) — else the operation name, which then stands for an API endpoint (`search` for
Wikidata). Until 2026-09-15 the Snowflake and Databricks query feeds appeared as a table named `query`.

**The catalog Source of a connector (`connectors_catalog_source`, REST `POST /lineage/catalog-source`).**
Parameters `connector`, optional `source` (id of a Source to create, default from the connector id) and
`apply` (default `false`: the plan only). Only a verified catalog is read or written, with the caller's
permissions, and nothing is ever deleted. From the Source holding the connector's namespace and the
Sources naming the connector: `LINKED` (nothing to do), `CREATE` (no Source: id, label, platform guessed
from the namespace, connector, `lineageNamespace`), `SET_CONNECTOR` (the Source the catalog created from
lineage events for that namespace gets the connector), `SET_NAMESPACE` (the Source naming the connector
gets the namespace), `MANUAL` (the system listed twice, a namespace held by another connector, several
Sources for one connector, an id already taken: explained, nothing written). The catalog's source trigger
then creates or realigns the flows of the Source; run `connectors_lineage_declare` so that the lineage
lands on it. The `lineage` advice of the mapping tools names the tool.

**Connector changes.** `ConnectorLineageTrigger` (Connector table) sends the declared lineage of a
connector's mappings again when its type, base URL or activity changes — the namespace comes from those
fields — and the configuration import, which writes without triggers, does the same for the connectors
whose fields changed and for those whose mappings it brought. Before 2026-09-15 a base URL change needed a
manual `connectors_lineage_declare`. The trigger is declared in the model: model reload and restart.

### 3.8 An EBX instance in the data catalog: the `ebx` type

The `ebx` connector (another instance through Data Services — or **this one** through its own
`http://localhost:8080/ebx-dataservices/rest/data/v1`) carries the four data-catalog operations, in
the documents `JdbcConnector` produces, so the catalog scans an EBX instance like a database:

| Operation | Inputs | Document |
|---|---|---|
| `catalog` | `source`; `schemas` = dataspace keys or `dataspace/dataset`, comma-separated (empty or `*` = every non-technical dataspace, children included); `exclude` (regex on `/qualifiedName`, default leaves out `/TechnicalData/`, `_MDS/` and `/ebx-` datasets); `rowCounts` | `tables[]`: `qualifiedName` = `<dataspace>/<dataset><tablePath>`, `schema` = `<dataspace>/<dataset>`, `name` = the table path, `label`, `remarks` = the model's documentation, `rowCount` (`:count`), `url`; `columns[]` from the metamodel (`?includeMetamodel=true`): `name` = `Group/field`, normalised `dataType`, `nativeType` (`foreignKey -> …`, `list<…>`, enumeration, computed, auto-increment), `nullable` (not mandatory), `primaryKey`, `label`, `remarks`, `foreignKey` |
| `profile` | + `tables`, `sampleSize` (default 1000) | one item per column: exact `rowCount`, `nullRate` / `distinctCount` / min / max / mean / lengths on the first records, `nullCount` scaled to the row count, `sampleSize`, `method` = `SAMPLE` (§3.9) |
| `sample` | + `tables`, `limit` (default 50) | distinct values of the text columns |
| `lineage` | + `fkScope` = `cross` (default: keys pointing at another dataset) or `all` | one edge per foreign key: primary key of the target → the key field, `IDENTITY`, origin `PLATFORM`, `edgeKey` |

The dataspace prefix (`B` / `V`) is the connector's `dataspacePrefix` property. Read-only: nothing
here writes to the instance.

**Speed.** When `tables` lists full qualified names (`Companies/Companies/root/Companies`), the four
operations read those tables directly — the metamodel, the count and, for `profile` / `sample`, the
records: 3 Data Services calls for one table — instead of walking every dataspace first; `schemas` and
`exclude` still apply, and a partial name (`Companies`) falls back on the walk. Before this, re-profiling
one table read the metamodel and the count of all 213 tables of the instance (about 430 calls, 31 s).
The per-table reads of a walk run several at a time, results in the order of the selection:
`-Debx.connectors.ebx.catalogThreads` (default 4, `1` = one at a time, at most 16). Measured on a warm
instance of 639 tables: 12.7 s with one thread, 5.4 to 7.3 s with four, no better with eight.

### 3.9 A Kafka cluster in the data catalog: the `kafka` type

The `kafka` connector carries three data-catalog operations, in the documents of the `ebx` type. A topic
declares no schema and runs no query, so everything is read from its **latest messages**, read-only: the
operations assign the partitions to a consumer without group, seek back from the end offsets and commit
nothing.

| Operation | Inputs | Document |
|---|---|---|
| `catalog` | `source`; `schemas` = topic names or patterns with `*`, comma-separated (empty or `*` = every topic); `exclude` (regex on the topic name, default leaves out `__consumer_offsets`, `_schemas`, `_confluent*` and the Kafka Connect storage topics); `sampleSize` (default 1000); `rowCounts` | `tables[]`: kind `TOPIC`, `qualifiedName` = `name` = the topic, `rowCount` = messages retained (end − beginning offsets), `url` = `kafka://<first bootstrap server>/<topic>`, `remarks` saying how many messages the fields come from; `columns[]` **inferred** from those messages: nested objects flattened (`address/city`), arrays `JSON`, numbers `INTEGER` / `DECIMAL`, ISO texts `DATE` / `TIMESTAMP`, mixed types `STRING`, `nullable` when a message lacks the field; `@key` and `@timestamp` describe the message, `@value` a payload that is not a JSON object |
| `profile` | + `tables`, `sampleSize` | one item per field, computed by ON EBX on the latest messages: `method` = `SAMPLE`, `sampleSize`, `rowCount` = messages retained, `nullCount` scaled to it |
| `sample` | + `tables`, `limit` (default 50) | distinct values of the text fields |

A topic that cannot be read (authorisation, timeout) is listed under `errors` and the others go on; when
none can be read the operation fails. A field none of the messages read carries is not listed, and a topic
without retained messages has no field.

**Full scan or sample.** Profiles of `jdbc`, `snowflake` and `databricks` are computed by the source on every
row (`method` = `FULL_SCAN`); profiles of `ebx`, `kafka` and `salesforce` are computed by ON EBX on the records it read
(`core/RecordProfiler`, `method` = `SAMPLE`): null rate, distinct values, min / max, mean and lengths describe
those records only. The data catalog keeps the method on each snapshot and says so on the asset.

### 3.10 A Salesforce org in the data catalog: the `salesforce` type

The `salesforce` connector carries the same three data-catalog operations (`connectors/SalesforceCatalog`),
with the connector's OAuth login. Salesforce declares its schema, so the inventory reads the describe of each
sObject; SOQL has no aggregate per field, so the profile is computed by ON EBX on the latest records.

| Operation | Inputs | Document |
|---|---|---|
| `catalog` | `source`; `schemas` = sObject API names or patterns with `*`, comma-separated (empty or `*` = the core standard objects — Account, Contact, Lead, Opportunity, OpportunityLineItem, Case, Campaign, CampaignMember, Contract, Order, OrderItem, Product2, Pricebook2, PricebookEntry, Asset, User, Task, Event — and every custom object `__c`); `rowCounts` | `tables[]`: kind `TABLE`, `qualifiedName` = `name` = the API name (the name the lineage of the mappings uses, so scanned and declared assets are the same), `rowCount` from `SELECT COUNT()` (left out when refused), `url` = `salesforce://<login host>/<object>`, `remarks` = label, standard or custom; `columns[]` from the describe: `id` / `reference` / text / picklists `STRING`, `int` `INTEGER`, `double` / `currency` / `percent` `DECIMAL`, `date` `DATE`, `datetime` `TIMESTAMP`, `time` `TIME`, `base64` `BINARY`, `address` / `location` `JSON`; `nativeType` with the length, `nullable` = `nillable`, the `id` field as key, label and referenced objects in `remarks` |
| `profile` | + `tables`, `sampleSize` (default 1000, at most 2000: one SOQL page) | one item per field except compound and binary ones, computed by ON EBX on the most recently modified records (`ORDER BY LastModifiedDate DESC`): `method` = `SAMPLE`, `sampleSize`, `rowCount` from the count |
| `sample` | + `tables`, `limit` (default 50) | distinct values of the text fields (`string`, `textarea`, picklists, `email`, `phone`, `url`); ids and references are not sampled |

Only queryable, visible sObjects are read (not `__mdt`, `__e`, history or share objects unless a pattern names
them). An sObject that cannot be read is listed under `errors` and the others go on; when none can be read the
operation fails. Offline contract: `SalesforceCatalogTest`. Never run against a real org yet.

### 3.11 Slack with a bot token: the `slack` type

The `chat-webhook` type posts to one channel through an incoming-webhook URL, and that URL is the secret: it
sits in the base URL, in clear. The `slack` type calls the Slack Web API (`https://slack.com/api`) with the
**bot token** of a Slack app, stored in `apiKey` (encrypted, masked like any secret). One connector reaches
every channel the app may post to, and a posted message can be edited later.

**Setting it up.** At api.slack.com/apps, open or create the app, then *OAuth & Permissions* > *Bot Token
Scopes*: `chat:write`, `chat:write.public` (post in a public channel without inviting the app), `channels:read`
(channel names, `channels`), `users:read.email` (`dm`), and `groups:read` for private channels. *Install to
Workspace*, copy the *Bot User OAuth Token* (`xoxb-...`) into the seeded connector `Slack`, and set its property
`channel` (default channel, `#name` or id). *Test connection* calls `auth.test`, which changes nothing.

| Operation | Inputs | Document |
|---|---|---|
| `post` (W) | `text` (required), `channel` (else the property), `title` (bold first line), `threadTs` (reply in a thread), `replyBroadcast`, `blocks` (Block Kit JSON array) | `status` = `sent`, `channel` (id), `ts` (the message id), `threadTs` |
| `notify` (W) | same as `post` | same as `post`: a flow written for `chat-webhook` moves over by changing its connector |
| `update` (W) | `ts`, `text` (required), `channel`, `title`, `blocks` | `status` = `updated`, `channel`, `ts` — only a message this app posted |
| `dm` (W) | `text` (required), `email` (or `user`, a member id), `title`, `blocks` | `status`, `user`, `channel` (the direct conversation), `ts` |
| `channels` (S) | `name` (contains, optional), `types` (default the property `channelTypes`, else `public_channel`), `max` | `count`, `items[]`: `id`, `name`, `isPrivate`, `isMember`, `isArchived`, `members`, `topic`, `purpose` |

A channel is given by id (`C0123ABCD`) or name (`#data-alerts`); names are resolved once through
`conversations.list` and cached until the connector is reloaded. `&`, `<` and `>` are escaped unless the
property `escapeText` is `false` (to keep `<@U123>` mentions or `<url|label>` links); link previews stay off
unless `unfurlLinks` is `true`. An outbound mapping may also build the document itself: `text`, `title`,
`channel`, `threadTs` and `blocks` are read from it when the inputs leave them empty. Storing the returned `ts`
on the record (response mapping) is what makes a later `update` possible.

**Refusals.** Slack answers most refusals with HTTP 200 and `{"ok": false, "error": "..."}`. The connector
checks `ok` on every answer, so a refusal fails the run instead of counting as sent, with the Slack error code,
a status (401 token, 403 `missing_scope` / `not_in_channel`, 404 `channel_not_found` / `users_not_found`, 429
rate limit) and what to do: the scope Slack says is missing, `/invite` the app, check the token. Slack accepts
about one message per second per channel. Offline contract: `SlackConnectorTest`.

**Workflow tasks in Slack.** Set `ebx-connectors@com.on.ebx.connect.ebx.workflow.ChatWorkItemTrigger` as the
workflow trigger of a model (field `workflowTrigger`), then republish it. Every new work item is posted with a
link that opens it in the user's EBX inbox (`/ebx/?service=@workflow&workflowView=inbox&xpath=[work item]`), who
it is offered to and the workflow; when the work item ends, the message is edited into "Accepted by Ana" or
"Rejected by Ana". The decision is made in EBX, in the user's own session, so EBX records the right person and
applies their permissions; Slack never calls EBX. The connector is `Slack`
(`-Debx.connectors.workItemNotifier.connector` for another one) and its properties drive it:
`workflow.channel` (default: `channel`), `workflow.channel.<role>` (e.g. `workflow.channel.Steward = #stewards`),
`workflow.baseUrl` (the address users open EBX with, default `http://localhost:8080`) and
`workflow.startWorkItem` (default `true`: the link takes the work item). The channel and `ts` of each message are
kept in the sync log (run id `workitem:<work item key>`); if the message cannot be found, the outcome is posted
as a new one. A notification failure never stops the workflow. A user who opens a link for a work item that is not
offered to them gets "The work item to select has not been found in your 'Inbox' folder". Offline contract:
`WorkItemNoticesTest`.

## 4. Mappings

### 4.1 Fields of the `Mapping` table

| Field | Role |
|---|---|
| `connector`, `operation`, `direction` | which connector, which operation (list computed from the connector), which direction |
| `label` | free name; usable instead of the id in the REST API and the workflow bean |
| `dataspace`, `dataset`, `tablePath` | where it applies; empty `dataspace` = wherever the dataset exists (workflow child dataspaces included) |
| `responseRoot` | JSON path applied to the document **before** the rules, e.g. `data.attributes` (GLEIF), `organization` (D&B) |
| `active` | an inactive mapping is never selected |
| `parent`, `collectionPath`, `parentLinkField`, `childKeyFields`, `childFilter`, `deleteMissing` | sub-objects (§ 8) |
| `itemsPath`, `keyPredicate`, `createIfMissing` | import feeds (§ 9) |
| associations `inputs`, `rules`, `children` | inputs, rules, child mappings |

### 4.2 Life cycle of an inbound mapping on a record

1. The **inputs** are computed from the record (§ 5). If a **required** input of the operation is empty,
   the call is **SKIPPED** ("Input 'lei' is empty on this record").
2. The connector executes the operation; `null` = **NO_DATA**.
3. `responseRoot` is applied to the document.
4. Each **rule** reads its source in the document, applies its pipeline, and writes its target according
   to its **policy**: `IF_EMPTY` writes only when the EBX field is empty, `ALWAYS` overwrites, `TRUSTED` arbitrates by trust (see 4.4).
5. The **sub-objects** are processed (§ 8), everything is saved in one transaction, and a `SyncLog` row
   is written: **OK** (at least one change), **NO_CHANGE**, or **ERROR**.

The same mapping runs in **simulation** (`dryRun`): changes are computed and shown, nothing is written.

### 4.3 Minimal example: GLEIF, record by LEI

Connector `GLEIF`, operation `lei` (read, required input `lei`), inbound, table `/root/Companies`,
`responseRoot = data.attributes`:

| Input | Expression |
|---|---|
| `lei` | `${./Identification/LegalEntityIdentifier}` |

| Source (document) | Target (EBX) | Transformation | Policy |
|---|---|---|---|
| `entity.legalName.name` | `./LegalInformation/legalName` | `trim\|left(255)` | IF_EMPTY |
| `entity.legalForm.id` | `./LegalInformation/legalForm` | `fk(/root/ReferenceData/LegalForm)` | IF_EMPTY |
| `registration.status` | `./Lifecycle/leiStatus` | | IF_EMPTY |
| *(empty)* | `./Governance/enrichmentSource` | `constant(GLEIF)` | IF_EMPTY |

A rule **without a source** is a constant: its value comes from the pipeline (`constant(...)`).

### 4.4 Trust, provenance and conflicts

When two sources disagree, the module decides by **trust**:

- every connector carries a **trust level** from 0 to 100 (`Connector/trust`). Seeded values: GLEIF,
  Sirene, Companies House 90; D&B, Orbis, Handelsregister 85; VIES, Creditsafe, OpenSanctions 80; Melissa,
  Loqate 75; BAN, OpenCorporates, SAP, JDE 70; Google, CRM and ERP 60; Wikidata 40; default 50. A manual
  entry, or a value loaded outside the connectors, counts as **100**;
- every write by a connector is remembered in the **`FieldProvenance`** table (record, field, connector,
  trust, value, date), whatever the rule policy: this is the audit trail;
- the **`TRUSTED`** rule policy writes when the field is empty, or when the source is **at least as
  trusted** as the one that wrote the current value (so a source refreshes itself, and a better one corrects
  a worse one). Otherwise the proposal is logged in the **`Conflict`** table (current value, who wrote it and
  with which trust, proposed value, by whom, when), status `OPEN`, for a steward to decide: apply the value
  by hand and set the conflict to `ACCEPTED`, or set it to `REJECTED`. The same conflict is logged once
  while it stays open.

`IF_EMPTY` and `ALWAYS` remain available and unchanged. The report of a call (log, REST API, workflow bean)
counts the conflicts, and the API details them (`results[].conflicts`).

Example: the legal name of Microsoft France was written by VIES (trust 80). The Recherche d'entreprises
API (85) proposes "MICROSOFT FRANCE": with `TRUSTED` it wins and overwrites. Wikidata (40) proposing
something else would be logged as a conflict without any change. A name corrected by hand (100) is never
overwritten by a connector again.

### 4.5 Managing conflicts

A conflict is a steward decision. Three ways to know there are some:

- **REST API**: every answer of `/enrich`, `/sync/{mapping}` and `/webhook/{feed}` carries `conflicts`
  (count) and `hasConflicts` (boolean), and each result details its own in `results[].conflicts`.
  `GET /ebx-connectors/rest/v1/conflicts?dataset=Companies&table=/root/Companies&key=513` lists the open
  conflicts (`status=ACCEPTED|REJECTED` for the history);
- **workflow script task**: the bean returns `conflictCount` and `hasConflicts` (`true`/`false`), to
  test in a condition (`ConditionIsValueEqualsTo`, `value1=${hasConflicts}`, `value2=true`) that routes to
  the user task below;
- **log**: the message of every call counts the conflicts.

The **"Manage connector conflicts"** screen (a user service of the module) lists the open conflicts with,
for each field, the current value (who wrote it, its trust) and the proposed value (by whom, its trust).
For each row: *Take the proposed value* (the value is written into the record, the proposing source
becomes the provenance of the field, the conflict becomes `ACCEPTED`), *Keep the current value*
(`REJECTED`) or *Decide later*. A common comment is stored on the decided conflicts, with the user and the
date. It is available:

- on a **dataspace** (Actions menu, or perspective action): every conflict of the dataspace;
- on a **table** (Actions menu of the view): those of the table;
- on a **record**: those of the record;
- on the **Conflict table of the Connectors dataset** itself: the whole open backlog, or only the conflicts
  selected in the view (the service on a conflict record opens that single conflict);
- as a **workflow user task** (service `ebx-connectors@ConflictManager`): input parameters `branch`
  (required), `dataset`, `table`, `recordKey` (optional, they narrow the list); outputs `resolvedCount`
  and `remainingCount`. The *Complete the task* button closes the work item.

Through REST, a decision is `POST /conflicts/{id}/accept?comment=...` or `.../reject`.

The `ConnectorEnrichment` workflow chains: enrichment script task, condition on `hasConflicts`, user
task "Resolve the conflicts" offered to the administrators.

### 4.6 Deleting a mapping

A mapping has attachments: rules, inputs, sub-object mappings, outbound states, and provenance and
conflict rows that reference it. Deleting the Mapping row alone would leave them orphaned, so the
Mapping table carries a **cascade trigger** (`MappingCascadeTrigger`): whatever the way a mapping is
deleted (user interface, Data Services, import, service), its rules, inputs, sub-object mappings and
outbound states go with it, and the provenance and conflict rows keep their history but lose the
reference. The synchronisation log is kept as is.

The user service **Delete this mapping and everything attached** (on a Mapping record, or on the
rows selected in the Mapping table) shows what will be deleted and what will only be unlinked, then
asks for confirmation. The same deletion is available as `DELETE /ebx-connectors/rest/v1/mappings/{id}`
and as the MCP tool `connectors_mapping_delete`. Export the configuration first
(`GET /rest/v1/config`) if the mapping may be needed again.

## 5. Inputs and expressions

An input = `name` (the one the operation expects) and `expression`. Expression syntax:

| Form | Result |
|---|---|
| `${./Address/City}` | the value of the record field, in XML Schema format (ISO dates, FK = key) |
| `${./A} ${./B}` | juxtaposition: concatenation with the surrounding text |
| `${param.max}` | a parameter passed at run time (workflow, API) |
| `75008` or `FR` | a constant |
| `${./Address/City\|capitalize\|left(40)}` | a pipeline on **one** placeholder |
| `${./Address/Street} ${./Address/Zipcode} \|> trim\|upper` | a pipeline on the **assembled value** (`\|>`) |

The available transformations are those of the rules (§ 7), `map`, `fk` and `lookup` included. An
unknown transformation fails the call with an explicit message. Examples:

```
vat      = ${./LegalInformation/registrationNumber|vatfr}                 (SIREN -> FR + key + SIREN for VIES)
name     = ${./Identification/Name|regex('^(.*?)(?:[,\s]+(?:Inc\.?|SAS|SA|GmbH|Ltd\.?))?\s*$',1)}   (name without legal form)
address  = ${./Address/Street} ${./Address/Zipcode} ${./Address/City}     (one-line address for BAN)
```

In the designer, the **Service call** panel lists the inputs of the operation (required ones starred),
with an EBX field picker and transformation chips.

Placeholders can be **nested**: `${./LegalInformation/registrationNumber|default(${./Identification/Name|upper})}`
sends the SIREN when present, else the upper-cased name. The inner placeholder is resolved first, then serves as
an argument of the outer transformation.

**Run-time inputs of a feed.** A feed starts from no record, so its inputs are constants — unless an
expression reads a run-time parameter. With `tables = ${param.tables|default(*)}` a run given nothing
reads every table, and a run given `tables=SALES.ORDERS` reads that one only. Run-time inputs are text,
`name=value` pairs separated by `;` (`tables=SALES.ORDERS,SALES.CUSTOMER;limit=50`), and every way of
running a feed accepts them: `inputs=` on `POST /sync/{mapping}`, the `inputs` parameter of the workflow
bean and of the scheduled task, and the `inputs` URL parameter of the user service (§ 12). **Only the
names an input expression of the mapping reads as `${param.name}` are used**; the others are not used,
and every run says so: `ignoredInputs` (and `inputsIgnored`, kept for the first REST callers), `warning:
true` and a sentence in `message` in the MCP and REST answers, whose plain `OK` then reads `WARNING`
(`PARTIAL`, `ERROR`, `NO_DATA` and `RUNNING` are left as they are); a message in the user service, next to
any other message of the run; the `warnings` output of the workflow bean and the first line of its
`report`; an execution line starting with `WARNING:` in the task scheduler. A run on one record, on the
records of a table or on an outbound scope reads no run-time input: every one it is given is reported the
same way. A run-time input narrows what the mapping already allows; it never adds an input the mapping
does not use, so a link or a script cannot slip a `sql` or a `where` into a feed that has none.

**How much one run takes (`max`).** `max` bounds what ONE run takes: the records of a table or of an
outbound scope, or the items a feed imports. Its default depends on how the run is started, and is
defined in one place (`com.on.ebx.connect.core.RunLimits`):

| Started from | Parameter | Default |
|---|---|---|
| Task scheduler (`ConnectorScheduledTask`) | `maxRecords` | 100 |
| MCP `connectors_sync` (and the in-JVM bean), REST `POST /sync/{mapping}`, workflow bean `ConnectorScriptTaskBean` | `max` / `maxRecords` | 200 |
| User service, on records or a scope | *Maximum number of records* | 100 |
| User service, on a feed | *Maximum number of records* | 499 (4,999 at most for a simulation) |
| REST `POST /webhook/{feed}` | `max` | 10,000 (also the ceiling) |
| Publication of a data product (`connectors_publish`, `POST /publish`, `ProductPublishTaskBean`) | `max` / `maxRecords` | 0 = every row |

A run cut at `max` always says so, whatever started it: a "Limit reached" result closes its results
(with the number of items the source returned when the connector knows it), its answer carries
`truncated: true` and the sentence in `message`, its status is `PARTIAL`, never `OK`, the user service
shows the notice under its counters, the workflow bean sets `truncated`, and the scheduled task fails
on it, unless it is draining a backlog (an outbound scope, a table filtered by a predicate), where it says it. The `count` of an answer counts the records processed,
not that notice. A connector's own `max.rows` property (1,000 for a JDBC source) is something else: what
the connector returns when it is called without `max` (`connectors_call`, a preview); the engine always
hands its `max` to the connector when it runs a feed.

## 6. Rules and paths

### 6.1 Source path (document side)

Dotted notation on the JSON, after `responseRoot`:

| Path | Designates |
|---|---|
| `entity.legalName.name` | a nested field |
| `results[0].name` | the first element of an array |
| `addressLines[*]` | **all** elements (a list, to reduce with `join`, `first`, `count`) |
| `claims.P856[0].mainsnak.datavalue.value` | any depth |
| *(empty)* | no source: the rule produces a constant |

### 6.2 Target path (EBX side)

Path relative to the record: `./Identification/Name`, `./Address/latitude`,
`./LegalInformation/legalForm` (a foreign key receives the **key** of the referenced record; for a
composite key, EBX's `a|b` form). Values travel as XML Schema strings: dates `2026-09-05`, date-times
`2026-09-05T18:07:22`, booleans `true`/`false`, decimals with a dot.

### 6.3 Policy and order

`IF_EMPTY` (default) respects existing data; `ALWAYS` overwrites; `TRUSTED` overwrites only when the source is at least as trusted as the one that wrote the value (4.4). Rules apply in `position` order.

`IF_EMPTY` writes a field once: when a feed runs again after its source changed, a field already filled
keeps its value, and the run reports `NO_CHANGE`. A feed that must follow its source needs `ALWAYS` on
the rules of the fields that change, its key fields (what its key predicate reads, the child key fields
and the parent link field of a sub-object mapping) staying `IF_EMPTY`. The policy is therefore a choice
on a feed: the mapping designer asks for the policy of the rules added to a feed or a sub-object mapping
(with this explanation), gives it to every new rule except the key fields, and can apply it to the rules
already there. `connectors_mapping_upsert` and the configuration import keep `IF_EMPTY` for a rule sent
without a policy, and their answer says what was applied: `rulePolicies` (rules per policy),
`policyDefaulted` (rules written `IF_EMPTY` because they had none) and, on an inbound mapping, a note in
`notes` saying what that means.
Outbound, source and target swap: source = EBX field, target = JSON path of the document being built
(`primaryAddress.addressLocality.name`).

## 7. Transformations

A pipeline = steps separated by `|`: `trim|left(80)`. Arguments go between parentheses, separated by
commas; an argument holding a comma, a `|` or leading/trailing spaces goes between quotes:
`join(', ')`, `prefix('GLEIF ')`.

| Transformation | Effect | Example |
|---|---|---|
| `trim`, `upper`, `lower` | surrounding spaces, case | `upper`: `fr` → `FR` |
| `capitalize` | initials upper-cased, rest lower-cased (after space, `-`, `'`) | `PARIS` → `Paris` |
| `left(n)`, `right(n)` | first / last n characters | `left(80)` |
| `substring(start,end)` | substring, 0-based | `substring(1,11)` |
| `replace(a,b)` | replaces `a` by `b` (text, not regex) | `replace(-,)` |
| `regex(pattern,group)` | returns the captured group (default 1), `null` when no match | `regex('^(\d{9})',1)` |
| `digits` | keeps digits only | `327 733 184` → `327733184` |
| `vatfr` | SIREN (or SIRET) → French VAT number `FRkk` + SIREN | `327733184` → `FR95327733184` |
| `prefix(p)`, `suffix(s)` | adds before / after | `prefix(DUNS:)` |
| `default(v)` | `v` when the value is empty | `default(OTHER)` |
| `constant(v)` | always `v` (rule without source) | `constant(GLEIF)` |
| `nullifempty`, `nullif(v)` | `null` when empty / when equal to `v` | `nullif(N/A)` |
| `isodate`, `isodatetime` | truncates to `YYYY-MM-DD` / `YYYY-MM-DDTHH:MM:SS` | `2026-06-29T08:50:16.000Z` → date |
| `date(inFormat,outFormat)` | reformats a date (`SimpleDateFormat`) | `date(dd/MM/yyyy,yyyy-MM-dd)` |
| `map(A=B;C=D;*=default)` | lookup table, `*` = default value | `map(Active=ACTIVE;Out of Business=CLOSED)` |
| `join(sep)` | joins a list into text | `addressLines[*]` + `join(', ')` |
| `first`, `count` | first element / size of a list | |
| `boolean` | `true`/`1`/`yes`/`oui`/`active` → true | |
| `number`, `round(n)` | decimal (comma accepted), rounded to n places | `round(7)` |
| `fk(/root/Table[,dataspace,dataset])` | keeps the value **only if** the referenced record exists (avoids the blocking "unknown foreign key"); the table is looked up in the dataset being synchronised, else by following the foreign key of the model that points to it (e.g. `/root/Currency` in the Currencies dataspace); explicit dataspace and dataset win | `fk(/root/ReferenceData/LegalForm)`, `fk(/root/Currency)` |
| `lookup(/root/Table,./field,./result[,dataspace,dataset])` | finds the row whose `field` equals the value and returns `result` (a lookup table kept as data) | `lookup(/root/Companies,./Identification/LegalEntityIdentifier,./Identification/Id)` |
| `golden(/root/Table[,dataspace,dataset])` | the value is a record's formatted primary key; resolves it to its **golden record**'s key when Match and Merge (§ 10.2) reports the record MERGED — record not found, table not under Match and Merge, or state not MERGED: value unchanged | `golden(/root/Companies)` |
| `mdmState(/root/Table[,dataspace,dataset])` | the Match and Merge state of that record (`GOLDEN`, `MERGED`, `UNSET`, `PENDING_APPROVAL`, `DELETED`, or `UNMANAGED` when the table is not under Match and Merge); `null` when blank or not found | `mdmState(/root/Companies)` |
| `hash` | SHA-256 hex digest of the (text-coerced) value; ignores its pipeline arguments | `concat(...)\|hash` |
| `now([pattern])` | current server time, ignoring the incoming value; default pattern is the same 19-character `yyyy-MM-dd'T'HH:mm:ss` shape `isodatetime` produces (no zone, no fractional seconds — an `xs:dateTime` field's exact expected text); an optional `SimpleDateFormat` pattern overrides it | `now`, `now(yyyy)` |
| `runid` | the id of the current connector run (`ConnectorService.currentRunId()`); a fresh one when called outside a run | `runid` |
| `concat(path1,path2,...)` | joins the value at each **dotted path of the document root** (the same resolution a rule's own source path uses) with a fixed `\|` separator; a missing/null piece contributes `""`. At least one path is required. Meant to pair with `hash` to build a `_sourceId`/`_rowHash` for a feed-based (non-SQL) landing mapping, the same shape a SQL-based landing flow builds with `COALESCE(expr,'') \|\| '\|' \|\| COALESCE(expr,'')` | `concat(id,name)\|hash` |

An additional Java transformation registers with `Transformations.register("name", ...)`.

## 8. Sub-objects (child tables)

A **child** mapping has a `parent` and describes one row of a **child table** linked to the parent
record. Fields:

| Field | Role | Example |
|---|---|---|
| `collectionPath` | JSON path of the **collection** in the parent document. An array → one child per element; **empty** → the document itself is the single element; absent path → nothing | `claims.P946`, `entity.addresses`, *(empty)* |
| `parentLinkField` | foreign key of the child table to the parent | `./company`, `./fromCompany` |
| `childKeyFields` | fields identifying an existing row to update (otherwise creation) | `./type` or `./type,./City` |
| `childFilter` | XPath predicate restricting the child rows concerned | `./type='LEI'` |
| `deleteMissing` | delete child rows absent from the document | `false` |

The child's rules take as source paths **relative to the element** of the collection and as target the
fields of the child table. Three real examples:

- **LEI identifier** under the GLEIF record: empty `collectionPath`, table `/root/CompanyIdentifier`,
  link `./company`, key `./type`, filter `./type='LEI'`; rules `constant(LEI)` → `./type`, `lei` →
  `./value`, `constant(GLEIF)` → `./issuingAuthority`.
- **ISIN from Wikidata**: `collectionPath = claims.P946` (an array, often absent → no row created);
  rule `mainsnak.datavalue.value` → `./value`.
- **GLEIF parent company**: empty `collectionPath` on the `ultimateParent` document, table
  `/root/CompanyRelationship`, link `./fromCompany`, key `./type`; rule `lei` → `./toCompany` with
  `lookup(/root/Companies,./Identification/LegalEntityIdentifier,./Identification/Id)`: the relationship
  points to the parent company **when** it is in the referential.

Outbound, children **build** the collection of the document (addresses and contacts of a company sent
to Kafka or SAP in one document).

In the designer, the **Sub-objects** section creates a child (collection, child table, link field,
keys); the child is then opened to draw its rules.

## 9. Import feeds

When the operation returns a **list** (Kafka `consume`, Snowflake/Databricks/SAP `query`, a file
`read`, the webhook `receive`), the mapping becomes a feed:

| Field | Role | Example |
|---|---|---|
| `itemsPath` | JSON path of the array of items | `messages[*].value`, `rows`, `results`, `items` |
| `keyPredicate` | XPath predicate locating the target record, with `${json.path}` resolved from the item | `./LegalInformation/registrationNumber='${siren}'` |
| `createIfMissing` | create the record when the predicate finds nothing | `true` |

Each item is processed in its own transaction, then its sub-objects. Example, the webhook: a `POST`
with `{"items":[{"siren":"327733184","name":"...","city":"...","country":"FR"}]}` creates or updates a
company by SIREN, rules `siren` → `./LegalInformation/registrationNumber` (`digits`), `name` →
`./Identification/Name`, `country` → `./Address/Country` (`upper`)…

## 10. Outbound direction and response mapping

An **outbound** mapping builds a document from the record (rules source = EBX field, target = JSON path,
sub-objects = collections) and sends it through a write operation (`upsert`, `publish`, `merge`,
`create`). What the service **answers** (a created id, a status) is stored by the **response mapping**:
an *inbound* mapping, same connector, same operation, same table, which the framework applies
automatically to the answer. The designer shows it and the **Create the response mapping** button
creates it. For a **read** operation (Melissa `address`, VIES, GLEIF), one inbound mapping is enough:
its inputs are the send, its rules the answer.

### 10.1 Deltas, deletions and replay

An outbound run takes a **scope** (`scope=` in the API, the *Records* list of the user service, the
`scope` parameter of the workflow bean and of the scheduled task):

| Scope | Records sent |
|---|---|
| `all` (default) | every record of the request, as before |
| `unsent` | records never sent successfully by this mapping |
| `changed` | records whose outbound document differs from the last one sent (fingerprint), failures allowed by the retry policy, and the deletions |
| `failed` | records whose last send failed and that the retry policy allows again |
| `dataspaceDelta` | records created, modified or deleted in the dataspace **since its initial snapshot** (EBX comparison API); the dataspace must be a child dataspace |

The table **`OutboundState`** (one row per mapping and record) remembers each delivery: status
`SENT` (with the fingerprint and the last document), `FAILED` (with the error, the number of attempts
and the last attempt time) or `DELETED`. Only successful sends update the fingerprint, so a failed
record is naturally resent by `changed`, and a modification made by an inbound enrichment is resent too.

**Deletions** are propagated when the mapping declares a **`deleteOperation`**: the connector
operation called for a record that disappeared (`changed`: present in `OutboundState`, absent from
the table; `dataspaceDelta`: present in the snapshot only). The call receives the inputs of the mapping
resolved on the snapshot record when there is one, plus `key` (the primary key) and the scalar fields of
the last document sent. Without `deleteOperation`, deletions are ignored.

**Retry policy**, per mapping: `maxAttempts` (5) and `retryDelayMinutes` (30). A record in `FAILED`
is retried by `changed` and `failed` until the attempts are exhausted or while the delay has not
elapsed it is counted as "waiting"; after that it stays `FAILED` until a manual replay.

**Simulation.** With `dryRun=true` an outbound run builds every document and shows it (`changes`) without calling the service; the outbound state is not touched.

**Runs and replay.** Every execution (a REST call, a workflow step, a scheduled run, a user service
run) gets a **`runId`**, written on each `SyncLog` row and returned by the API and the workflow bean.
`POST /ebx-connectors/rest/v1/replay/{runId}` re-runs the calls of that run that ended in `ERROR`,
inbound or outbound, on the current version of the records (a record that disappeared is skipped, or
deleted remotely when the mapping has a `deleteOperation`). `GET /runs/{runId}` gives the state of a run and a page of its calls (50 newest first; `callsMax` up to 500, `callsFrom` for the next
page) and says whether it is replayable; `GET /outbound?mapping=&status=FAILED` lists the records to
replay of a mapping (`status=SENT|DELETED|ALL` for the rest).

```bash
# send what changed since the last send, deletions included, at most 500 records
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/7?scope=changed&max=500"
# the delta of a child dataspace, in simulation
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/7?scope=dataspaceDelta&dryRun=true"
# replay the failures of a run
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/replay/R20260906-231500-0007"
```

### 10.2 Publishing a data product as a whole

A consumer of a data product must never rebuild it from the member tables one delta at a time. Where
§ 10.1 sends one mapping's records over time, **publishing a product** (`core.ProductPublisher`, MCP
`connectors_publish`, `POST /publish`, workflow bean `ProductPublishTaskBean`) sends **one or more
root outbound mappings, all on the same connector**, as a single unit against a **contract** (the
exposed columns), with one cut-off and **one atomic commit for every table**. v1 scope: **Snowflake
only**, **full reload only** (every target table is entirely replaced at each publication, there is no
incremental/delta mode here — that is what § 10.1 is for), a product = one Snowflake table per root
mapping. A root mapping's active **children** (sub-object mappings) are not published as separate
tables: each becomes a `VARIANT` (JSON) column of its parent's table, holding the array of child
objects exactly as `MappingEngine.buildOutbound` already builds them for a normal outbound send.

**The mapping input `table` is required** (it is not needed for an ordinary outbound send): it names
the target table, upper-cased and sanitized to `[A-Z_][A-Z0-9_]*`. It may be **schema-qualified**
(`DB.SCHEMA.TABLE` or `SCHEMA.TABLE`) — only the last dot-separated segment is used as the (sanitized)
table name, but giving the fully qualified name keeps the mapping's **declared lineage**
(`OpenLineageEmitter`/`connectors_lineage_declare`, § 3.7) pointed at the *same* catalog asset that a
publication run creates, instead of a second, junk asset under the connector's own default schema
(`OpenLineageEmitter.datasetOf` only ever prefixes an *unqualified* `table` input with that default
schema). If the qualified input names a schema different from the publication's own `schema` request
field, the publication fails with an error naming both — there is no silent retargeting to the input's
schema. Every rule and active child of the mapping becomes a column: a scalar rule (target with no `.`
or `[]`) becomes a column named after the target; a rule targeting a nested path becomes one `JSON`
column named after its first path segment (several rules under the same segment merge into that one
column); a child mapping becomes one `JSON` column named after its `collectionPath`. Four technical
columns are appended to every table: `_EBX_KEY` (the record's formatted primary key — the business key
when the table has one, `key=true` when it does not), `_RUN_ID`, `_PUBLISHED_AT`, `_CONTRACT_VERSION`.

**Golden records and crosswalk.** On a table under Match and Merge (`ebx-addon-mame`, configured
independently of this module), the losing side of a merge (state `MERGED`) and logically deleted
records (state `DELETED`) would otherwise be published next to the golden records, with nothing telling
a consumer which transaction key belongs to which golden record. The mapping input **`mergedRecords`**
(`exclude`, the default, or `include`; any other value fails the publication before anything is
written) controls this per table: `exclude` leaves `MERGED`/`DELETED` records out — skipped records
never count towards `max` — `include` keeps everything. Setting the mapping input **`crosswalk`** to
`true` switches the default to `include` (a key/cross-reference table is meant to carry the merged
records, resolved). Two transformations pair with `crosswalk`: **`golden(tablePath[,dataspace,dataset])`**
takes a record's formatted primary key and resolves it to its **golden record**'s key when the record
is `MERGED` (unchanged when the record is not found, the table is not under Match and Merge, or its
state is not `MERGED`); **`mdmState(tablePath[,dataspace,dataset])`** returns that record's consolidation
state. A publication that excludes merged records still counts them in the report:
`tables[].skipped.merged` / `.deleted`, and a successful run's `message` mentions the total when it is
greater than zero. Typical shape — a key table publishing every record (merged included) resolved to
its golden record, and a source-system cross-reference re-pointed the same way:

```
# key table: every record, merged ones included, resolved to its golden record
root mapping on /root/Companies, inputs {table: COMPANY_KEYS, crosswalk: true}
  ./Identification/Id -> ID
  ./Identification/Id -> GOLDEN_ID   golden('/root/Companies')
  ./Identification/Id -> MDM_STATE   mdmState('/root/Companies')
# source-system cross-reference, re-pointed to the golden record
root mapping on /root/CrossRefCompanies, inputs {table: COMPANY_XREF}
  ./source          -> SOURCE_SYSTEM
  ./pkRecordSource  -> SOURCE_KEY
  ./company         -> COMPANY_ID    golden('/root/Companies')
  ./company         -> ORIGINAL_COMPANY_ID
```

A consumer never joins on a merged (dead) key again: `JOIN COMPANY_XREF x ON x.SOURCE_SYSTEM='ERP' AND
x.SOURCE_KEY=t.CUSTOMER_ID JOIN COMPANY_360 c ON c.ID=x.COMPANY_ID`. `COMPANY_KEYS`/`COMPANY_XREF`
legitimately publish the matched table a second time (the key table is not counted as covering it,
port-coverage-wise, by the data catalog's own rules).

The **`Publication`** table (dataspace/dataset `Connectors`, `/root/Publication`) keeps one row per
`connectors_publish` call, dry runs included: `runId` (its primary key), `product`, `contractVersion`,
`connector`, `schema`, `status` (`OK`, `ERROR`, `DRY_RUN`), `message`, `cutoff`, `startedAt`, `endedAt`,
`rowCount`, `mappings` (CSV of the ids published), `tables` (CSV of `TABLE=rows`) and `report` — the
full report below, as JSON text (`osd:text`, cut at 100000 characters). It is the durable record of a
publication; a caller that only got back a `runId` (e.g. the workflow bean, whose `message` output is
cut at 180 characters like every workflow variable, § 12.3) reads the full report back from this row.

The **report** (returned by every surface, and stored verbatim in `Publication.report`):

```json
{"runId":"R20260919-143000-0001","status":"OK|ERROR|DRY_RUN","message":"…","product":"COMPANY_360","contractVersion":"1",
 "connector":"Snowflake","schema":"SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS","cutoff":"2026-09-19T14:30:00","startedAt":"…","endedAt":"…",
 "durationMs":12345,"rowCount":632,"truncated":false,
 "tables":[{"mapping":210,"label":"Company 360 -> Snowflake","ebxTable":"Companies/Companies/root/Companies","table":"COMPANY_360",
            "action":"CREATED|EXISTS|RECREATED|PLANNED","rows":632,"batches":2,
            "skipped":{"merged":5,"deleted":1},
            "columns":[{"name":"ID","kind":"INTEGER","type":"NUMBER(38,0)","source":"./Identification/Id","key":true},
                       {"name":"ADDRESSES","kind":"JSON","type":"VARIANT","source":"addresses[]","key":false}],
            "sample":[{"ID":1,"NAME":"…"}]}],
 "plan":["CREATE SCHEMA IF NOT EXISTS …","…"],
 "errors":[]}
```

`sample` (the first 3 documents per table) and `plan` (the statements that would run) are present only
on a dry run (`dryRun=true`: nothing is executed, everything else in the report — table/column
derivation, row counts, `truncated` — is computed exactly as a real run would). `max` bounds the rows
read per table: by default (`0` or absent) every row is published, read from EBX and loaded into the
staging table in batches, so memory holds one batch whatever the table's size (audit C-17). A positive
`max`, or the operator ceiling `-Debx.connectors.publish.maxRowsPerTable`, cuts each table there: the
cut set still replaces the target tables atomically, but the run ends with status **`PARTIAL`** (never
`OK`), `truncated=true` on the report and on the cut table, and a message saying so; the workflow step
`failOnError=true` fails on `PARTIAL` too. `recreate=true` lets a target table whose columns differ from the
contract be replaced (`CREATE OR REPLACE TABLE`) instead of the publication failing with the missing/
extra column names. A table's `skipped` object is present only when `mergedRecords=exclude` (the
default) actually left records out (`merged` and/or `deleted` greater than zero); the top-level
`message` of a successful run (real or dry) mentions the total across every table when it is positive.

**The atomic commit and its limits.** `prepare` creates the schema, the control table
(`EBX_PUBLICATION`) and, per target table, the table itself and an empty staging table; `load` fills
each staging table in batches (connector property `publish.batchSize`, default 500); **`commit`** is
one single request running `BEGIN` … one `DELETE`+`INSERT … SELECT *` pair per target table (from its
staging table) … an insert into `EBX_PUBLICATION` … `COMMIT` — either every table lands together or
none does. `EBX_PUBLICATION.PUBLISHED_AT` is written with `SYSDATE()`, which is always **UTC**, into a
`TIMESTAMP_NTZ` column — never `CURRENT_TIMESTAMP()`, which stores the Snowflake session's own time
zone with no offset, so it silently reads back as if it were UTC (e.g. 09:06 Los Angeles for an 18:06
CEST cut-off). `cleanup` (staging tables dropped) runs after a successful commit and after any failure,
best effort, never masking the outcome. This is deliberately narrow, and the extension points are
left visible rather than hidden: **full reload only**, no incremental/upsert publication of a product
(§ 10.1's deltas remain the way to keep a single mapping's target continuously in sync); **no snapshot
isolation** of the source read (the records are read table by table, not against one consistent EBX
dataspace snapshot, so a concurrent write during a long publication can be reflected in one table's
cut and not another's); **Snowflake only** (`TablePublisher`, § 3.3.1, is the extension point for a
second platform); no GRANTs and no dataspace-snapshot semantics are managed by this feature.

**Describing and listing the published tables.** After the commit, and best effort (a refusal by
Snowflake is a `warnings` entry in the report, never an error on a committed publication), the publisher
puts a comment on each table and column and sets the tags `EBX_PRODUCT`, `EBX_CONTRACT_VERSION`,
`EBX_SOURCE_TABLE` and, when the request carries them, `EBX_OWNER` and `EBX_DOMAIN` (request fields
`description`, `owner`, `domain`, `title`). With the connector property **`listing.enabled`** set to `true`
it also shares the schema (`EBX_<PRODUCT>_SHARE`) and creates, or updates in place, an organizational
listing `EBX_<PRODUCT>` visible to every account of the organization in the Internal Marketplace. That
needs `CREATE SHARE` and `CREATE ORGANIZATION LISTING` on the account and a database owned by the
connector's role. Properties: `listing.enabled` (default `false`), `listing.supportContact` (an e-mail
address, required when listing is enabled) and `listing.region` (optional, default the account's region).

**What each column means, and where to find it in Snowflake.** When a column carries a meaning (label and
description from the EBX model, business name, glossary term, definition and synonyms from the data catalog),
its comment puts that meaning first (`Customer name: … | Also called: … | Glossary term: … | ON EBX field ./name`)
and the column gets the tags `EBX_GLOSSARY_TERM`, `EBX_SENSITIVITY` and `EBX_CLASSIFICATION`. The publisher also
writes, in the publication schema, a **semantic view** `EBX_<PRODUCT>_SEMANTIC`
(`CREATE OR REPLACE SEMANTIC VIEW`, replaced at each publication): one logical table per published table, one
dimension per business column with its glossary synonyms (`WITH SYNONYMS`) and its meaning as comment (business
name and definition only), the key columns as `PRIMARY KEY`, one `RECORD_COUNT` metric per table, no relationship. It is what Cortex Analyst reads to answer questions in business words.
Property `semanticView.enabled` (default `true`); it needs `CREATE SEMANTIC VIEW` on the schema. In Snowsight:
comments in the table's *Columns* tab, tags via
`TAG_REFERENCES_ALL_COLUMNS('<DB>.<SCHEMA>.<TABLE>', 'table')`, the view under the schema's *Semantic views*
(`SHOW SEMANTIC VIEWS IN SCHEMA`, `DESCRIBE SEMANTIC VIEW`). Tables scanned by the catalog are read only:
nothing is ever written back to them.

```bash
# dry run: plan + a 3-row sample per table, nothing executed
curl -u admin:admin -X POST http://localhost:8080/ebx-connectors/rest/v1/publish -H "Content-Type: application/json" \
     -d '{"mappings":[210],"schema":"SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS","product":"COMPANY_360","dryRun":true}'
# real run, several root mappings of the same connector, replacing a table whose contract changed
curl -u admin:admin -X POST http://localhost:8080/ebx-connectors/rest/v1/publish -H "Content-Type: application/json" \
     -d '{"mappings":["Company 360 -> Snowflake"],"schema":"SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS","product":"COMPANY_360","contractVersion":"1","recreate":true}'
```

## 11. Graphical designer

On a `Mapping` row: the form button, or the **Design the mapping** action of the toolbar.

- **Toolbar**: direction, response root, `+ Constant value`, hide unmapped fields, counter, Diagram /
  Table view, zoom (− +, Ctrl+wheel), full screen (Esc to leave); the area resizes by its handle.
- **Service call**: the inputs, their expression, an EBX field picker, transformation chips. Collapsible.
- **Input** (left) and **Output** (right): hierarchical trees, type badges (STR, NUM, DATE, BOOL, FK,
  LIST, OBJ), sample values in grey, star on the primary key, search, expand / collapse all. Inbound,
  the input is the service answer and the output the EBX table; outbound, the reverse.
- **Links**: drag the port of an input field onto an output field (or click one then the other). A link
  with transformations becomes a **node**; a constant, a node without input. Dropping on an already
  mapped field re-points its link.
- **Inspector** (below the diagram): source → steps (editable chips, add from the list, remove) →
  target; pipeline as text, policy, comment, deletion.
- **EBX buttons**: *Save the rules* (rules + inputs + direction + root), *Test on the sample*
  (simulation on the given record, or the first of the table), *Load the external fields* (really calls
  the service for the sample and fills the tree with values), *Create the reverse mapping*, *Create a
  sub-object mapping*, *Create the response mapping*.

**Keyboard and screen reader.** The designer works without a mouse. The trees are `tree` widgets with one stop in
the tab order: the arrow keys move between fields, Right and Left open and close a group (Left on a field goes to its
group), Home and End jump to the ends. On an input field, **Enter** or **Space** picks it (a polite live region says
so); on an output field the same key makes the link, or selects the link already on it (the *Constant value* button
works the same way). **Delete** on a linked output field asks, in an in-page dialog, whether to remove the link. In the
diagram each link has one stop (its node when it has one): the arrows move between links, Enter selects one, Delete
removes it after confirmation; the Table view rows work the same way. Every action is a real button or field with a
name (no mouse-only anchors), the toggles say whether they are pressed, and what colour used to say alone is also
written: a linked field carries a check mark and "linked" in its spoken name, the selected link a thicker outline, the
active view and the armed constant a check mark. Changing the direction and adding a service parameter use an in-page
dialog (Esc cancels, Tab stays inside it, the focus returns to where it came from) instead of the browser's boxes.
Drag and drop keeps working for the mouse.

## 12. Running

### 12.1 User service (user interface)

On the table view of a table that has mappings: **Synchronise with an external application**.
Options: mapping, scope (selection, view, whole table), maximum, simulation. The report details every
record.

What the screen shows around a run:

- **before**: when it was opened from a link with run-time inputs, a yellow banner says that nothing has
  run yet;
- **during**: a click on **Run** reveals an animated strip with "Running…" (a simulation, and a run on the
  records of a table, are synchronous; the strip is the one of the material federated search, not a modal,
  and a 2-minute timer clears it if EBX refuses the form). A **feed run for real** is different: it starts on
  a background thread (below) and the screen answers at once with the live run panel;
- **after**: a banner at the top, "Run finished at HH:MM:SS" (or "Simulation finished"), with the counts —
  green, red when an item failed, blue for a simulation;
- **Back**: opened on a full ebx-ui page (the catalog's links), EBX renders no Close button; **Back**
  returns to the page the link was clicked on (the data catalog keeps it in `sessionStorage`,
  under a key that includes your EBX login), else one step back in the history. It hides itself wherever EBX draws its own Close.

**Run mode, confirmation and details.** The screen opens on a *Run mode* choice under the flow tiles:
two cards, **Simulate** (blue, nothing is written or sent) and **Run for real** (amber), keyboard-operable
as a radio group; simulation stays the default. The bottom button follows the mode (*Simulate* / *Run and
write*, amber for a real run). The mode travels in the hidden request parameter `ftcsMode`. After a
simulation, a banner gives the number of planned changes and an **Apply these changes** button that reruns
the same options for real. A real run on the **whole table** of a records mapping does not start at once:
a panel gives the record count (and the maximum when lower) with **Confirm**, **Simulate first** and
**Cancel**. The check is made on the server (parameter `ftcsConfirm` = `<mappingId>|ALL`), so another
mapping chosen afterwards asks again. The result tiles count simulated changes (`DRY_RUN`) as *Changes
planned*. The details list shows errors first and only 20 rows. The next 20 come from the server through an
EBX Ajax callback, on **Show 20 more** or when that button scrolls into view. Status filters (errors,
updated, unchanged, no data, skipped) are applied on the server. A page asked by the screen of an older run
is refused ("run again"). The progress strip shows the elapsed time.

**Guided layout.** When the service opens with run-time inputs on an import feed (the catalog's
**Re-profile this table…**), it keeps to what matters: the title names the flow and the inputs
("Catalog profile: EBX_TEST · Companies/Companies/root/Companies"), a card says which connector and
operation run, into which table, for which inputs; only the run mode cards are visible; the mapping and the
maximum are folded under *Advanced options* (the scope, which a feed ignores, is not shown); the maximum
is preset to 499; the item list is folded under *Details (N items)*, open only when an item failed, with
short item names (`Identification/Id` rather than the key predicate) and translated statuses. Opened from a
table's toolbar, the screen keeps its full layout.

**When a run fails.** The screen does not show the raw exception. For the frequent failures it says what
happened and what to do next, in the language of the user's EBX session, then folds the technical detail
(the redacted message, never a stack trace) under *Technical detail*: connection refused, host that does not
resolve, timeout, credentials or rights refused, rate limit, a private or non-allowed address (with the property
an administrator sets: `ebx.connectors.egress.privateExceptions`, `ebx.connectors.allowedHosts`), a secret stored in
clear text (`ebx.connectors.secrets.allowClear`), a mapping already running, too many background runs, an
invalid mapping. Any other failure says "The operation failed" and shows the technical detail open; the full
trace is in the kernel log. The same cause and action appear under a failed row of the details and in the
result of *Test connection* (`core/ErrorCatalog` classifies; the sentences are the `error.*` keys of the UI
bundles, fixed text: a secret or a record value quoted by the remote system never reaches them).

A feed run from the screen is an explicit run, as on `POST /sync`: its lineage leaves in one OpenLineage
event when it ends. Without it every imported item sent its own event, and the catalog imported each one
separately (a 58-column profile took 30 s in the screen against 4.6 s over REST, 2026-09-14).

Three optional URL parameters, declared as input parameters of the service, prefill it: `mapping=<id>`
(the mapping selected), `inputs=<name=value;…>` (run-time inputs of a feed, § 5, shown above the report)
and `dryRun=false` (opens on *Run for real* once; the run still needs a click on **Run**). The data
catalog's **Re-profile this table** button opens the service this way on its profile flow.

**How to pass them.** The ebx-ui routes (`/ebx-ui/ui/advanced/…`, `/ebx-ui/ui-node/…`) ignore plain query
parameters of a service — `…?service=ebx-connectors@ConnectorSync&serviceMode=TABLE_VIEW&mapping=159` opens
the default mapping (measured 2026-09-14). Two forms work:

- a web component URL, which EBX redirects:
  `/ebx/?branch=<dataspace>&instance=<dataset>&xpath=<table path>&service=ebx-connectors@ConnectorSync&mapping=123&inputs=tables%3DSALES.ORDERS&dryRun=false`;
- the ebx-ui route with `serviceParams`, the base64 of a JSON object (what the catalog button builds):
  `/ebx-ui/ui-node/data/B<dataspace>/<dataset><table path>?service=ebx-connectors@ConnectorSync&serviceMode=TABLE_VIEW&serviceParams=<base64 of {"mapping":"123","inputs":"tables=SALES.ORDERS","dryRun":"false"}>`.

The presets are read when the service opens; EBX then rewrites the address keeping only `mapping`, so a
reload of the page loses the run-time inputs — the banner that lists them disappears with them.
The maximum counts imported items (one per column for a catalog profile). It has no upper bound on the
form: a feed run for real starts in the background and has none; a simulation, or a run on records, holds its
items in the HTTP request and is refused above 4,999 with a message that says why. Each feed starts at 499.

**Live run panel (feed runs for real).** **Run and write** on an inbound feed no longer runs inside the HTTP
request. The screen starts the run through the same path as `connectors_sync(async=true)` and
`POST /sync?async=true`, so it has the same admission limits (`-Debx.connectors.import.async.maxConcurrent` and
`maxQueued`, one run per mapping at a time) and returns at once with a **Run in the background** panel
under the result area:

- the state in words (*Running*, *Finished*, *Finished with errors*, *Failed*, *Stopped*, *Interrupted*), an
  animated bar while it runs, and tiles for the items processed, created, updated, unchanged, skipped, the errors
  and the elapsed time (the counters are those of the last saved batch, read from the same run status as
  `connectors_run_get`; the panel polls every two seconds and stops polling when the run ends);
- **Stop** (the RUN right) asks the run to stop at its next batch boundary; it keeps its position;
- **Resume from item N** appears for a stopped, interrupted or failed run that saved a position, when the screen
  started the run itself: it starts a new background run at that item (the source must return its items in the same
  order as before, as for `resumeFrom` over MCP and REST);
- **Refresh**, and links to the log rows of the run and to its failures;
- a failure is told as a cause and a next action (see below), a run cut at its maximum says how many items the source
  had.

You may leave the screen: the run continues. Reopening the screen on a mapping whose feed is already running (started
from any entry point of this node) shows the same panel, with Stop. A reader with the READ right only watches;
**Stop** and **Resume** need RUN, which the screen itself requires. The record form of an `ExecutionRun` row shows the
same panel, with Refresh and Stop but no Resume.

### 12.2 REST API of the module

Base `http://<host>/ebx-connectors/rest/v1`, EBX authentication (Basic, token, session); the caller's
session runs the mappings; `?dryRun=true` everywhere.

```bash
# catalogue and mappings
curl -u admin:admin http://localhost:8080/ebx-connectors/rest/v1/connectors
curl -u admin:admin "http://localhost:8080/ebx-connectors/rest/v1/mappings?table=/root/Companies"

# one mapping on one record (id or label; ?predicate= instead of key)
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/32?key=511"

# a feed (no key): every item it returns; with run-time inputs read by the mapping as ${param.name} (§ 5)
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/123?max=10000"
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/123?max=10000&inputs=tables%3DSALES.ORDERS"

# an ordered sequence (ids/labels), or by connector / connector/operation
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/enrich?dataset=Companies&table=/root/Companies&key=512&mappings=37,38,1,42"
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/enrich?dataset=Companies&table=/root/Companies&key=511&connectors=GLEIF/searchByRegistration,GLEIF/lei,VIES,BAN"

# webhook: import of a document through a feed mapping (JSON, ndjson, csv; GET = health check)
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/webhook/34?dryRun=true" \
     -H "Content-Type: application/json" -d '{"items":[{"siren":"327733184","name":"Microsoft France","city":"Issy-les-Moulineaux","country":"FR"}]}'

# conflicts of the TRUSTED policy: list (status OPEN by default), then a steward decision
curl -u admin:admin "http://localhost:8080/ebx-connectors/rest/v1/conflicts?dataset=Companies&table=/root/Companies"
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/conflicts/2/accept?comment=Official%20INSEE%20name"
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/conflicts/3/reject"

# a connector from an OpenAPI specification (JSON or YAML, body or url)
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/connectors/openapi?id=RechercheEntreprises&url=https://recherche-entreprises.api.gouv.fr/openapi.json"

# publish a data product as a whole (§ 10.2); 200 for OK/DRY_RUN, 422 for ERROR
curl -u admin:admin -X POST http://localhost:8080/ebx-connectors/rest/v1/publish -H "Content-Type: application/json" \
     -d '{"mappings":[210],"schema":"SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS","product":"COMPANY_360","dryRun":true}'
```

Answer (excerpt; `conflicts` and `hasConflicts` are present on every enrichment, sync and webhook answer):

```json
{"record":"512 TIBCO Software Inc.","dryRun":false,"ok":3,"errors":0,"conflicts":0,"hasConflicts":false,
 "results":[{"mapping":37,"connector":"Wikidata","operation":"search","status":"OK","message":"2 change(s), 0 skipped",
             "durationMs":433,"changes":["./Governance/wikidataId:  -> Q513861"]}, ...]}
```

The webhook requires an EBX account (Basic or token) with the RUN right; the import runs as that account.

### 12.3 Workflow

Bean `com.on.ebx.connect.ebx.workflow.ConnectorScriptTaskBean` (module `ebx-connectors`), or the
ready-made model `ConnectorEnrichment`. Inputs: `dataspace`, `dataset`, `xpath` (one record) or
`tablePath` + `predicate` + `maxRecords` (a table), `mappings` (ordered list, recommended form) or
`connectors` or `connector`/`operation`/`direction`, `dryRun`, `failOnError`, `inputs` (run-time inputs of the feeds among the mappings, § 5). Outputs: `report`,
`changedCount`, `okCount`, `errorCount`, `status` (OK, PARTIAL, ERROR, NO_DATA), `conflictCount`, `hasConflicts`, `runId` (input `scope` for outbound deltas, § 10.1)
(`true`/`false`, to route to the "Manage connector conflicts" user task, § 4.5), `truncated`, `problems` and
`warnings` (the run-time inputs no mapping of the step read, § 5; empty when none).
The step does not fail by default (`failOnError=false`): the workflow routes on the outputs. `truncated`
is `true` when a feed or the table held more than `maxRecords`, so the step did not process everything;
`problems` lists what keeps the run from being a plain success (`errors`, `rejected`, `conflicts`,
`truncated`, `stopped`, comma-separated) and is empty for a clean run. A feed whose call to the source
failed counts as one error. Launch example through
MCP: publication `ConnectorEnrichment`, parameters `xpath=/root/Companies[./Identification/Id=512]`,
`mappings=37,38,1,42,44`.

In the workflow editor, declare the step as a **library** script task and pick the bean in the list of
module `ebx-connectors` (key `ebx-connectors@com.on.ebx.connect.ebx.workflow.ConnectorScriptTaskBean`):
this is what shows its input and output parameters in the step form, where each one is mapped to a
data context variable (`mappings` = `${mappings}`, `xpath` = `${xpath}`...); those variables, declared
as `in`, are then asked at launch. A "specific" script task typed with the bare class name runs
just as well, but the editor shows no parameter form for it.

**Publishing a data product** (§ 10.2) is a separate bean, declared the same way: key
`ebx-connectors@com.on.ebx.connect.ebx.workflow.ProductPublishTaskBean`. Inputs: `mappings` (ids or
labels, comma-separated, all on the same connector), `schema`, `product`, `contractVersion` (default
`1`), `dryRun`, `recreate`, `maxRecords` (default 0 = every row), `failOnError` (default **true**, unlike
`ConnectorScriptTaskBean`'s `false`: a publication is meant to succeed as a whole, so the step goes in
error by default when the report is `ERROR`). Outputs: `runId`, `status` (`OK`, `ERROR`, `DRY_RUN`),
`message` (cut at 180 characters like every workflow variable), `rowCount`. The full report is never
passed as a variable — read it back from the `Publication` table by `runId` (§ 10.2).

### 12.4 Scheduled task

Class `com.on.ebx.connect.ebx.scheduler.ConnectorScheduledTask`, parameters `connector`, `operation`,
`direction`, `dataspace`, `dataset`, `tablePath`, `predicate`, `maxRecords` (100), `dryRun`, `scope`,
`inputs` (run-time inputs of a feed, § 5). Example: every night, `GLEIF` / `lei` on `/root/Companies` with
the predicate `./Identification/LegalEntityIdentifier != '' and ./Governance/gleifLastUpdate = ''`.

The mapping is found from (`connector`, `operation`, `direction`, `tablePath`); when two mappings match, the
task refuses to run. For a feed, `maxRecords` is the number of **items** imported, not of records of the
table: a data catalog profile imports one item per column, so a nightly profile of a catalog source takes
`connector=<its connector>`, `operation=profile`, `dataspace=DataCatalog`, `dataset=DataCatalog`,
`tablePath=/root/ProfileSnapshot`, `maxRecords=10000` (catalog guide §3.15 lists the other catalog flows).

**A scheduled execution fails when its run is not a plain success.** Nobody watches a scheduled run, so
the execution is reported failed in the task scheduler (what was written stays written) when the run
ends with items or records in error, a source that could not be read, rejected items, conflicts left
for a steward, a read cut at `maxRecords`, or a run stopped before its end. The message names the cause
and, for a cut read, the parameter to raise: the default `maxRecords` of 100 is a safety bound, not a
recommendation - set it above what the source holds. A record for which the source had no data is not
a failure, and neither is a table enrichment with a predicate that reaches `maxRecords` (a nightly run
over "what is not enriched yet" drains its backlog a hundred records at a time; the execution
information says that more records match). Without a predicate the same situation is a cut read: the
next execution would start again from the same first records. To accept some causes for a given task, set its `tolerate` parameter to a list among
`errors`, `rejected`, `conflicts`, `truncated`, `stopped`, or to `all` (for instance
`tolerate=conflicts` for a feed whose TRUSTED rules routinely leave proposals to a steward); a tolerated
cause is still written in the execution information.

### 12.5 Table trigger

Class `com.on.ebx.connect.ebx.trigger.ConnectorTableTrigger`, declared on a table of the model:
parameters `connector`, `operation`, `direction`, `onCreate` (true), `onModify` (false),
`watchedFields` (list of paths: fires only when one of them changes), `blocking` (false: a service
error does not prevent the save), `publish` (`record`; `golden`: a merged record sends its golden record),
`skipOrigin` (connector ids whose CDC writes are not sent back to them). Example: on company creation, `GLEIF` /
`searchByRegistration`; on a change of `./Identification/LegalEntityIdentifier`, `GLEIF` / `lei`. Real-time mastering
(CDC in, matching, golden records out): [docs/REALTIME-MASTERING.md](docs/REALTIME-MASTERING.md).

### 12.6 From an EBX Script

An EBX Script cannot call Java: it calls the module's REST API through `core.http`, via an external
REST configuration pointing to `http://localhost:8080/ebx-connectors/rest/v1` with a technical account
(password entered in the user interface).

### 12.7 MCP tools (agents)

The module exposes its features as **MCP tools** (Model Context Protocol), so that an agent such as
Claude Code can discover connectors, run enrichments, arbitrate conflicts or move a configuration.
The 23 tools: `connectors_help`, `connectors_list`, `connectors_describe`, `connectors_call`,
`connectors_mappings_list`, `connectors_mapping_get`, `connectors_mapping_upsert`, `connectors_mapping_delete`, `connectors_enrich`, `connectors_sync`,
`connectors_replay`, `connectors_run_get`, `connectors_outbound_states`, `connectors_conflicts_list`,
`connectors_conflict_decide`, `connectors_log_search`, `connectors_config_export`,
`connectors_config_import`, `connectors_openapi_import`, `connectors_lineage_declare`,
`connectors_publish`, `connectors_run_stop` (stop a running import or asynchronous send) and `connectors_catalog_source` (how the data catalog knows the system a connector reaches). They run in the caller's EBX session, so EBX permissions apply; secrets never
transit through them.

`connectors_mapping_upsert` writes a whole mapping, sub-object mappings included: a child that omits
`connector`, `operation`, `direction`, `dataspace` or `dataset` takes them from its parent. An import
feed (§ 9) sets `keyPredicate`, `itemsPath` and `createIfMissing`, flat or under `feed {}`; a `feed`
without `keyPredicate` is reported in `notes`. `connectors_sync` on a feed, without `key`, `predicate`
or `scope`, runs the feed like `POST /sync/{id}` (optional `inputs` as JSON text; `dryRun=true` lists
the values without writing, Kafka messages peeked, not committed). `connectors_publish` sends one or
more root outbound mappings as a whole product, one atomic commit for every target table (§ 10.2);
`mappings` is a comma-separated list of ids or labels, all on the same connector.

Two ways to reach them, both on by default (`-Debx.connectors.mcp=native|endpoint|both|none`):

- **Native MCP runtime of EBX 6.3**: at module registration, the server `ebx-connectors` is declared
  through the public API (`registerMcpServer` and the `@McpServer` / `@McpTool` annotations, the same
  API as the built-in workflow and data-access servers) and served by EBX at
  `/ebx-assistant/mcp/v1/ebx-connectors`, with the activation and permissions of the MCP
  administration. The call is made by reflection: on an older EBX the runtime is simply absent, and
  the kernel log says so. In the 6.3 development builds seen so far EBX only accepts core and add-on
  modules; the log then says "EBX declined the MCP server registration" and the endpoint below serves.
- **The module's own endpoint**, `POST /ebx-connectors/rest/mcp`: MCP over streamable HTTP (JSON-RPC
  2.0, JSON answers, no server-sent events; GET and DELETE answer 405 with `Allow: POST,OPTIONS`, which is what
  makes MCP clients fall back to plain request/response), authenticated like any EBX REST call (HTTP Basic, token,
  session). Works on every EBX version that runs the module. Configure an MCP client with
  `{"type": "http", "url": "http://host:8080/ebx-connectors/rest/mcp", "headers": {"Authorization": "Basic ..."}}`.

`GET /ebx-connectors/rest/v1/config` and `POST /config?dryRun=true` are the REST twins of the
export/import tools: connectors matched by id, mappings by connector, operation, direction, table and
label, rules and inputs replaced, secrets never exported nor overwritten.

## 13. Log and statuses

Every call writes a `SyncLog` row: timestamp, connector, operation, direction, table, record, status,
message, duration, origin (`user service`, `workflow admin`, `REST admin`, `scheduler`, `trigger`,
`webhook …`).

| Status | Meaning |
|---|---|
| `OK` | at least one change written |
| `NO_CHANGE` | call succeeded, nothing to write (already set, IF_EMPTY policy) |
| `NO_DATA` | the service returned nothing for these inputs (no LEI, address out of scope) |
| `SKIPPED` | a required input is empty on the record, or no applicable mapping |
| `ERROR` | the service or the mapping failed (message = the cause: missing key, `MS_MAX_CONCURRENT_REQ`, unknown transformation…) |
| `DRY_RUN` | simulation: changes computed, not written |

**Health metrics.** One row per connection in *Connector health and metrics*, updated after every execution: the
counters are **records**, not log lines. A feed run in batches writes one `SyncLog` line per batch (1,000 items by
default) plus an own line for each failing or conflicting record; the metrics read the tally of the batch line, so a
batch of 1,000 records counts 1,000 records (inbound), its unchanged and rejected ones as records without change and
its conflicts as conflicts, and the own lines of its failing records are not counted a second time. The latency
percentiles take the time per record of a batch. A failure for the *Reliability* rule (one failure in a row: degraded,
three: failed) is a failed call or record, or a batch in which every attempted write failed; a batch with some failed records
is not a failed batch (its failures are in its counts, in the run status and in their own log lines). The
repeated-failure alert follows the same rule.

## 14. Complete recipes

### 14.1 French company: VAT, address, coordinates (no key)

1. Create the company (SIREN, address); for instance through the webhook (§ 12.2).
2. `POST /enrich?...&mappings=30,32`: **VIES** (`vat = ${SIREN|vatfr}`) writes the legal name and a
   `VAT` identifier; **BAN** (`address = street zip city`) writes latitude and longitude.
3. With a Sirene key: add `3,4` (legal unit, head office); with Google: `5`.

### 14.2 Foreign company: find the LEI, then the record

`mappings=37,38,1,42,44`: **Wikidata** searches the entity by its name *without legal form* (input
`name` with `regex`), its claims give the LEI and the ISIN, **GLEIF** loads the record by that LEI
(legal name, form, registration, statuses, addresses, LEI identifier), then the parent companies. Order
matters: each mapping sees what the previous one wrote.

### 14.3 Normalising an address with Melissa (round trip)

Mapping 35: inputs `address = ${./Address/Street}`, `locality = ${./Address/City}`,
`postalCode = ${./Address/Zipcode}`, `country = ${./Address/Country}`; rules `AddressLine1` →
`./Address/Street` (`ALWAYS`), `Locality` → `./Address/City`, `PostalCode` → `./Address/Zipcode`,
`Latitude`/`Longitude` → coordinates. Only verified addresses come back (`verified.only` property),
hence `ALWAYS` is safe. Concatenate two fields before sending: `${./Address/Street} ${./Address/Street2} |> trim`.

### 14.4 Pushing to Salesforce and keeping the id

Mapping 7 (outbound, `upsert`) builds the account; the response mapping (inbound, same connector and
operation) writes `id` → `./Governance/externalId` (or a `SALESFORCE` identifier). Create the twin with
the designer button.

### 14.5 Importing a Kafka feed with its sub-objects

Mapping 11 (`consume`, `itemsPath = messages[*].value`, `keyPredicate =
./Identification/LegalEntityIdentifier='${lei}'`, `createIfMissing`), children 12 and 13
(`collectionPath = addresses` / `contacts`, link `./company`). Run it from the scheduler or the service.

### 14.6 Adding a REST service without Java

Create a connector of type `rest` with its `op.*` properties (§ 3.2), then an inbound mapping: inputs =
placeholders of `path`/`query`, `responseRoot` when the JSON has an envelope, rules from the tree loaded
by *Load the external fields*.

### 14.7 AI-assisted classification with a human in the loop

The `Claude` connector (type `llm`, trust 30) classifies a company from its description:

- input `text` = `${./Identification/description}`, input `categories` = the NACE sections
  (`A=Agriculture;B=Mining;C=Manufacturing;...` or a constant taken from a reference table);
- rules: `category` → `./AdditionalInformation/naceCode` with `fk(/root/ReferenceData/NaceCode)`, policy
  **TRUSTED**; `reason` → a comment field if the model has one.

Because the model is the least trusted source, it fills empty fields only; wherever a register already
wrote a value, its proposal goes to the **Conflict** table and a steward decides in the conflict manager.
The same pattern serves `extract` (a structured address from a free-text field) and `match` (is the
record the same entity as a candidate duplicate?). Set the API key on the connector; `provider`, `model`,
`temperature` and `language` are properties.

Any provider does the same job; one connector row per provider is seeded, all of type `llm`:

| Connector | Provider | Base URL | Authentication | Default `model` |
| --- | --- | --- | --- | --- |
| `Claude` | `anthropic` | `https://api.anthropic.com` | `BEARER` (sent as `x-api-key`) | `claude-sonnet-5` |
| `OpenAI` | `openai` | `https://api.openai.com` | `BEARER` | `gpt-4o-mini` |
| `Gemini` | `google` | `https://generativelanguage.googleapis.com` | `API_KEY_HEADER` `x-goog-api-key` (AI Studio key) | `gemini-2.5-flash` |
| `AzureOpenAI` | `azure` | `https://<resource>.openai.azure.com` | `API_KEY_HEADER` `api-key` | the deployment name |
| `Mistral` | `mistral` | `https://api.mistral.ai` | `BEARER` | `mistral-small-latest` |
| `Ollama` | `ollama` | `http://localhost:11434` | `NONE` | `llama3.2` |

Variants: Vertex AI = provider `google`, base URL `https://<location>-aiplatform.googleapis.com`, `OAUTH2`
(service account) and properties `google.project` / `google.location`; models deployed in Azure AI
Foundry (Phi, Llama, Mistral on Azure) = provider `azure-ai`, base URL
`https://<resource>.services.ai.azure.com`; Azure OpenAI v1 API = `azure.apiVersion = v1`. Without a
`provider` property the base URL decides. `jsonMode` (auto | true | false) asks the API itself for a JSON
answer on `extract` / `classify` / `match` — on by default for Google, Mistral and Ollama; `temperature =
none` leaves the temperature out (reasoning models). `vision = true` declares that the model accepts images:
only then does the `documents` connector send it an image or a scanned PDF (§ 14.8).

### 14.8 Documents, DAMA assets and images

The `documents` connector (category AI) turns unstructured documents into record values. It reads the text
of a PDF (text layer), DOCX, text, Markdown, CSV or HTML document, asks an `llm` connector (property
`llm.connector`) for the attributes listed in `fields`, and returns one item per document. Each value comes
with a confidence and the words of the document that state it; the module checks those words against the
text. A normal inbound mapping imports the item: a value at or above `confidence.threshold` (0.8) is written,
with its source kept in `FieldProvenance.source`; a value below it becomes a proposal in the conflict manager.
A model's confidence never goes above 0.99, so threshold 1 sends every value to a steward.

Where the document comes from:

- an upload on the connector row, a `content` input (base64 with `name`), or a file of the server folder
  `folder` (only under `-Debx.connectors.documents.roots`);
- **a DAMA asset** (EBX Digital Asset Manager): input `record` (the record whose media field holds the
  document) or `asset` (its UUID), with the table and field from the `dama.*` properties. It is read with the
  caller's session: the record must be visible and the field not hidden, and DAMA applies its own rights.

A mapping on `extract` with input `record = ${./id}` fills the record's own fields from the document it holds.
Start it from a workflow (`ConnectorScriptTaskBean`) or from `ConnectorTableTrigger` with
`watchedFields = ./document`: the trigger runs after the save, in the background, by default.

The `annotate` operation (write) puts the extracted metadata on the DAMA asset itself (`metadata =
attribute:CODE`, only metadata types entered by hand, `IF_EMPTY` by default). It chooses tags (`tags = true`
or `max:N`) **only from DAMA's existing tags**, never created, and adds them to a list field of the record
that holds the document, named by `dama.tags.field` (for example `./documentTags`): DAMA's public API cannot
tag an existing asset, and the module does not write DAMA's own tables. The write uses the caller's session
and needs that field read-write. Without `dama.tags.field`, the tags are only returned. Below the threshold,
metadata wait in the conflict manager as `document (DAMA metadata): CODE` proposals, and tags as ordinary
proposals on the tags field; accepting one adds that tag to the list.

**Images and scanned PDFs.** With `vision = true` on the documents connector **and** on the `llm`
connector (whose model must accept images), an image (png, jpeg, gif, webp, up to `max.image.bytes`, 5 MB)
or a PDF without text layer (up to `max.pages`, 20) is sent to the model itself. Nothing can check what the
model reads in an image, so its values are capped at `vision.confidence.max` (0.7, below the threshold): by
default a steward confirms them. PDFs are read by Anthropic, OpenAI, Google and Mistral models; Azure and
Ollama take images only. The image leaves EBX for the provider: see § 3.1.2.

Reference: `DOCUMENTS.md`, `DAMA-DOCUMENTS.md` and `DOCUMENTS-VISION.md` in the module's `docs` folder.

## 15. Cheat sheet for an AI agent

**Decision procedure** to enrich a record:

1. `GET /ebx-connectors/rest/v1/connectors`: which connectors have their secrets (`configured`).
2. `GET /ebx-connectors/rest/v1/mappings?table=<table>`: which inbound mappings exist, active, not
   feeds, not children.
3. Order them: first those that **produce** an identifier (search by SIREN / name), then those that
   **consume** it (record by LEI), then the complements (addresses, sanctions).
4. `POST /enrich?dataset=&table=&key=&mappings=<list>&dryRun=true`, read `results[].status` and
   `changes`, then run again without `dryRun`.
5. `SKIPPED` = empty input (complete the data or pick another mapping); `NO_DATA` = the service does not
   know the entity; `ERROR` = read `message`.
6. `hasConflicts` true: `GET /conflicts?dataset=&table=&key=` shows what a less trusted source proposed;
   never overwrite silently, decide with `POST /conflicts/{id}/accept|reject` (or leave it to a steward).

**Creating a configuration through Data Services** (base
`http://localhost:8080/ebx-dataservices/rest/data/v1/BConnectors/Connectors/root`, envelope
`{"content":{field:{"content":value}}}`, foreign keys as strings):

```bash
# connector + property
curl -u admin:admin -X POST $B/Connector -H "Content-Type: application/json" -d '{"content":{"id":{"content":"Wikidata"},"label":{"content":"Wikidata"},"type":{"content":"rest"},"baseUrl":{"content":"https://www.wikidata.org"},"authType":{"content":"NONE"},"active":{"content":true}}}'
curl -u admin:admin -X POST $B/ConnectorProperty -H "Content-Type: application/json" -d '{"content":{"connector":{"content":"Wikidata"},"key":{"content":"op.search.query"},"value":{"content":"action=wbsearchentities&search={name}&language=fr&format=json&limit=1"}}}'
# mapping, input, rule (the mapping id is assigned by EBX: read it back by its label)
curl -u admin:admin -X POST $B/Mapping -H "Content-Type: application/json" -d '{"content":{"connector":{"content":"Wikidata"},"operation":{"content":"search"},"direction":{"content":"INBOUND"},"label":{"content":"Wikidata search by name -> wikidataId"},"dataset":{"content":"Companies"},"tablePath":{"content":"/root/Companies"},"active":{"content":true}}}'
curl -u admin:admin -X POST $B/MappingInput -H "Content-Type: application/json" -d '{"content":{"mapping":{"content":"37"},"name":{"content":"name"},"expression":{"content":"${./Identification/Name}"}}}'
curl -u admin:admin -X POST $B/MappingRule -H "Content-Type: application/json" -d '{"content":{"mapping":{"content":"37"},"position":{"content":10},"sourcePath":{"content":"search[0].id"},"targetPath":{"content":"./Governance/wikidataId"},"transformation":{"content":"trim"},"policy":{"content":"IF_EMPTY"},"active":{"content":true}}}'
```

Ready-made scripts: `connectors-module/import_config.py` (idempotent re-import of an export),
`connectors-module/config_gleif_wikidata.py` (extra GLEIF/Wikidata mappings, a template for a
configuration script). Ids come back as **strings** through Data Services: compare as text.

**Publishing a data product** (§ 10.2): dry-run first, read `tables[].columns` and `sample` to confirm
the contract, then run for real.

```bash
connectors_publish(mappings="210", schema="SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS", product="COMPANY_360", dryRun=true)
connectors_publish(mappings="210", schema="SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS", product="COMPANY_360")
```

**Invariants**: a child mapping never runs alone, it is chained by its parent; a feed only runs through
an import (service, scheduler, webhook), not through `sync` on a record; `mappings=` is the only
unambiguous selection when a connector has several mappings on a table (GLEIF has two, in the wrong
order by id).

## 16. Troubleshooting

| Symptom | Cause and remedy |
|---|---|
| `SKIPPED — Input 'lei' is empty` | the required input is empty on the record: fill the data, or first run the mapping that produces it |
| `set the API key in apiKey` | secret left as `TO_BE_SET`: enter it in the user interface, on the connector |
| `VIES: MS_MAX_CONCURRENT_REQ` | member state overloaded: retry later |
| `NO_DATA` on BAN for a non-French address, or GLEIF without LEI | normal, out of the service's scope |
| `Unknown transformation 'x'` | typo in a pipeline; list in § 7 |
| `prefix(GLEIF )` gives `GLEIFISSUED` | surrounding spaces of an argument are lost: `prefix('GLEIF ')` |
| no EBX field in the designer | the mapping's dataset is not found or its table cannot be read: check `dataset`, `tablePath`, `dataspace` |
| Wikidata does not find "X Inc." | send the name without its legal form (`regex` on the `name` input) |
| `Dataset Companies not found` | several datasets bear this name: set `dataspace` |
| the workflow bean rejects a property | class or declaration changed: restart Tomcat (EBX loads beans at startup) |

## 17. Connector type catalogue

Kinds: R = read, S = search, W = write. The inputs of every operation are listed by `GET /connectors`
and in the designer (Service call panel).

The catalogue is the runtime contract, not a promise that every connector is configured. A connector is
usable only when it is **active**, its required secret or identity is configured, its optional driver is
available, and its configuration passes validation. Use the catalog or `GET /connectors` to discover
availability, then use **Test connection** or **Preview** before a write. A preview, `dryRun=true`, or a
non-destructive `peek` is the preferred evidence step; `receive`, `consume`, `complete`, and every W
operation can change an external system or message state.

Dedicated database types share the parameterized JDBC engine: use prepared inputs, bounded `max.rows`,
and a JNDI DataSource where operations manage the connection. Dedicated messaging types are deliberately
bounded and distinguish a non-destructive **peek** from consuming/acknowledging a message. Types loaded
through an optional library (Kafka, SFTP, AMQP/JMS providers, JDBC drivers and the Azure Service Bus SDK)
appear only when that library is present; the rest of the catalog remains available.

### Stored procedures through JDBC

The JDBC family exposes the write operation `procedure`. Supply either `procedure`, a safe one-to-three-part
identifier such as `governed.enrich_company`, or `call`, a single JDBC escape such as
`{call governed.enrich_company(?,?)}`. An explicit call accepts only the identifier and `?` placeholders:
literals, comments, semicolons and multiple statements are refused. `parameters` is a JSON array (maximum
64 entries) whose items contain a unique `name`, `mode` (`IN`, `OUT`, `INOUT`, or first-position `RETURN`),
and a bounded JDBC `type`: `VARCHAR`, `INTEGER`, `BIGINT`, `DECIMAL`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`,
or `VARBINARY` (documented aliases are accepted). IN values are read by name from the outbound document,
then operation inputs. Results contain `affectedRows` or one bounded `result`, plus `out`; each OUT value is
limited to 64 KiB. The connector commits a successful call and rolls back an error, but a procedure can have
external or non-transactional effects. Driver-specific named arguments, REF CURSOR, structured/LOB parameters,
multiple result sets and default arguments are not part of this portable contract. Grant EXECUTE only on an
approved allow-list of procedures and treat every invocation as a live write.

| Type | Label | Operations |
|---|---|---|
| `activemq` | Apache ActiveMQ | publish (W), receive (S), peek (R) |
| `azure-blob` | Azure Blob Storage (files) | read (S), write (W), list (S), catalog (S) |
| `ban` | Base Adresse Nationale (France) | geocode (R), reverse (R) |
| `bigquery` | Google BigQuery | query (S), get (R), insert (W), upsert (W), catalog (S), profile (S), sample (S) |
| `azure-service-bus` | Microsoft Azure Service Bus | publish (W), receive (S), peek (R), complete (W), abandon (W) |
| `azure-sql` | Azure SQL Database | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `companies-house` | Companies House (UK) | get (R), search (S), officers (S), pscs (S), filings (S) |
| `creditsafe` | Creditsafe Connect | search (S), report (R), find (R) |
| `databricks` | Databricks (SQL Statement Execution API) | query (S), lookup (R), merge (W), insert (W), execute (W), describe (R) |
| `dnb` | Dun & Bradstreet Direct+ | match (S), bestMatch (R), dataBlocks (R), enrich (R), familyTree (R), monitoringUpdates (S), registrationAdd (W), registrationRemove (W) |
| `dynamics365` | Microsoft Dynamics 365 (Dataverse) | get (R), query (S), create (W), update (W), upsert (W) |
| `dynamics-business-central` | Microsoft Dynamics 365 Business Central | get (R), query (S), create (W), update (W), upsert (W) |
| `gcs` | Google Cloud Storage (files) | read (S), write (W), list (S), catalog (S) |
| `gleif` | GLEIF (LEI) | lei (R), searchByRegistration (S), searchByName (S), directParent (R), ultimateParent (R) |
| `google-geocoding` | Google Geocoding | geocode (R), reverse (R) |
| `handelsregister` | Handelsregister (OffeneRegister mirror) | search (S), get (R) |
| `h2` | H2 Database | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `hubspot` | HubSpot CRM | get (R), query (S), create (W), update (W), upsert (W) |
| `jdbc` | JDBC database (generic) | query (S), get (R), insert (W), update (W), upsert (W), procedure (W), execute (W) |
| `jde-ais` | JD Edwards EnterpriseOne (AIS) | query (S), get (R), orchestration (W), orchestrationQuery (S), form (W) |
| `jms` | JMS messaging | publish (W), receive (S), peek (R) |
| `kafka` | Apache Kafka | publish (W), consume (S), peek (R), cdc (S, Debezium, docs/CDC.md), catalog (S), profile (S), sample (S) |
| `loqate` | Loqate address verification | verify (R), find (S), retrieve (R) |
| `melissa` | Melissa Data | address (R), email (R), phone (R) |
| `mysql` | MySQL Database | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `netsuite` | Oracle NetSuite (SuiteTalk REST/SuiteQL) | get (R), query (S), create (W), update (W), upsert (W) |
| `oracle-database` | Oracle Database | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `opencorporates` | OpenCorporates | get (R), search (S) |
| `opensanctions` | OpenSanctions (OFAC, UN, EU sanctions and PEPs) | screen (R), search (S), entity (R) |
| `oracle-fusion` | Oracle Fusion Cloud (REST) | get (R), query (S), create (W), update (W), upsert (W) |
| `orbis` | Bureau van Dijk Orbis | get (R), search (S), match (R) |
| `postgresql` | PostgreSQL | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `rabbitmq` | RabbitMQ | publish (W), receive (S), peek (R) |
| `rest` | Generic JSON REST |  |
| `s3` | Amazon S3 (files) | read (S), write (W), list (S), catalog (S) |
| `salesforce` | Salesforce (REST, OAuth 2.0) | get (R), getByExternalId (R), query (S), create (W), update (W), upsert (W), describe (R), catalog (S), profile (S), sample (S) |
| `sap-odata` | SAP (OData v2 / v4) | get (R), query (S), create (W), update (W), upsert (W), describe (R) |
| `servicenow` | ServiceNow (Table API) | get (R), query (S), create (W), update (W), upsert (W) |
| `sharepoint` | Microsoft SharePoint Online (Microsoft Graph) | site (R), listItems (S), getItem (R), createItem (W), updateItem (W), listFiles (S), downloadTextFile (R), uploadFile (W) |
| `sql-server` | Microsoft SQL Server | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `sftp` | SFTP (files) | read (S), write (W), list (S), catalog (S) |
| `sap-ariba` | SAP Ariba | getSupplier (R), searchSuppliers (S), upsertSupplier (W), getPurchaseOrder (R), searchPurchaseOrders (S), acknowledgePurchaseOrder (W) |
| `powerbi` | Microsoft Power BI (REST + Scanner API) | catalog (S), lineage (S), usage (S), refreshes (S), workspaces (S), query (S, DAX), scan (R) |
| `collibra` | Collibra Data Intelligence Platform (REST API v2) | search (S), get (R), terms (S), types (R), upsert (W), relate (W), import (W), delete (W) |
| `sirene` | INSEE Sirene | siren (R), siret (R), headOffice (R) |
| `snowflake` | Snowflake (SQL REST API) | query (S), lookup (R), merge (W), insert (W), execute (W), describe (R) |
| `sap-successfactors` | SAP SuccessFactors | getPerson (R), queryPeople (S), queryEmployment (S), queryUsers (S), createPerson (W), updatePerson (W), upsertPerson (W), describePeople (R) |
| `vies` | VIES (EU VAT validation) | check (R) |
| `webhook` | Inbound webhook (HTTP POST) | receive (S) |
| `workday` | Workday (REST and RaaS) | workers (S), worker (R), resource (S), raas (S) |
| `redshift` | Amazon Redshift (JDBC profile, endpoint URL `jdbc:redshift://…`) | the `jdbc` operations |
| `llm` | LLM: Claude, OpenAI, Google Gemini, Azure OpenAI / Foundry, Mistral AI, Ollama (AI enrichment, trust 30) | complete, extract, classify, match (R) |
| `documents` | Documents (PDF, DOCX, text; DAMA assets; images with `vision`) read by an `llm` connector, values with confidence and source | list (S), text (R), extract (R), extractAll (S), annotate (W) |
| `soap` | Generic SOAP web service (properties: op.x.body, action, result) | one per op.x.body |
| `graphql` | Generic GraphQL API (properties: op.x.query, result) | one per op.x.query |
| `ebx` | Another EBX instance (Data Services) | read (R), search (S), write (W) |
| `chat-webhook` | Slack / Microsoft Teams incoming webhook | notify (W) |
| `slack` | Slack Web API with a bot token | post (W), notify (W), update (W), dm (W), channels (S) |
| `inpi` | INPI Registre National des Entreprises (France) | company (R), search (S) |
| `openfda` | openFDA: GUDID devices, NDC drugs | udi (R), deviceSearch (S), drug (R) |
| `openlineage` | OpenLineage (data catalog, Marquez, DataHub...) | emit (W) |

The details of every type (authentication, properties, answer format) are in the description of its
seeded connector, and in the module's README.

## 18. Dependencies: which jar for which connector

The module itself needs only the ON EBX API (`ebx.jar`, supplied by ON EBX) and Jackson 2.14.2 with the SLF4J API,
which the war carries in its `WEB-INF/lib` (a standard ON EBX `lib/` has neither). Every other library is **optional**: a connector type whose
library is absent is simply not registered (the registry loads those types quietly), the others keep
working. Put the jars in Tomcat's shared `lib` (the `common.loader` folder, `ebx-ps-fasttrack-server/lib`
in the FastTrack workspaces) or in `ebx-connectors/WEB-INF/lib`, and add them to the Eclipse
`.classpath` of the server project to compile.

| Connector types | Library | Maven coordinates (tested version) | Notes |
|---|---|---|---|
| OpenAPI import in **YAML** (`rest` created from a YAML specification) | `jackson-dataformat-yaml`, `snakeyaml` | `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.14.2`, `org.yaml:snakeyaml:1.33` | loaded by reflection; without them JSON specifications still work |
| `kafka` | `kafka-clients` (+ `slf4j-api`, carried by the war) | `org.apache.kafka:kafka-clients:3.6.1` | compression codecs (`lz4-java`, `snappy-java`, `zstd-jni`) only if the topics use them; SASL/SSL configured through connector properties |
| `sftp` | `jsch` | `com.github.mwiede:jsch:0.2.16` (or `com.jcraft:jsch:0.1.55`) | password or private-key authentication |
| `redshift` | the Amazon Redshift JDBC driver | `com.amazon.redshift:redshift-jdbc42:2.1.x` | copied by hand into `WEB-INF/lib`; the driver class is loaded from there, never from a connector property |
| `jdbc`, `snowflake`, `databricks`, `bigquery` (JDBC mode) | the JDBC driver of the database | PostgreSQL `org.postgresql:postgresql:42.7.x`, H2 (both shipped with FastTrack); Snowflake `net.snowflake:snowflake-jdbc:3.14.x`; Databricks `com.databricks:databricks-jdbc:2.6.x`; BigQuery: the Simba/Google JDBC driver zip | the driver class is given by the `driver` property, or found by `DriverManager` from the URL |
| `s3`, `azure-blob`, `gcs`, `sharepoint`… | none | | plain HTTPS REST calls with the credentials of the connector (no vendor SDK) |
| every REST-based type (`gleif`, `sirene`, `vies`, `melissa`, `salesforce`, `sap-odata`, `llm`, `graphql`, `openfda`, `inpi`, `ebx`, `chat-webhook`, generic `rest`…) | none | | `java.net.http.HttpClient` when the JVM is Java 11+, `HttpURLConnection` otherwise |
| `soap` | none | | the JDK XML parser (`javax.xml`) |
| `nominatim` (extension sample) | none | | its jar `ebx-connectors-extension-sample.jar` in `WEB-INF/lib` |

To move the module to another EBX workspace: copy `ebx-connectors/` (Eclipse project, with `target/classes`
when Eclipse does not rebuild it) and `ebx-connectors-extension-sample/`, the Tomcat context
`conf/Catalina/localhost/ebx-connectors.xml` (docBase = `src/main/webapp`, `PostResources` mounting
`target/classes` on `/WEB-INF/classes`), the optional jars above into the server `lib`, and the matching
`<classpathentry kind="lib" .../>` lines in the server project's `.classpath`. The first start creates and
seeds the Connectors dataset.
