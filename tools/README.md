# tools

Helper scripts used while developing the module. They talk to a local EBX through Data Services
(`BASE` at the top of each script, default `http://localhost:8080`, account `admin:admin`): adjust
before use.

| Script | Purpose |
|---|---|
| `build_xsd.py` (+ `extend_xsd.py`) | regenerates `ebx-connectors/src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd` from the Python description of the tables; edit the description, never the XSD |
| `gen_logos.py` | regenerates the connector logo tiles (`ebx-connectors/src/main/webapp/www/common/images/connect/logos`) and `logos.txt`; edit the table at the top to add a connector or change a colour |
| `import_config.py` | re-imports the `export/` folder (connectors, properties, mappings, inputs, rules) into a Connectors dataset, idempotently |
| `config_gleif_wikidata.py`, `config_recherche_entreprises.py` | examples of configuration scripts creating mappings through Data Services |
| `export_config.py` | writes the `export/` folder from the live dataset (raw Data Services rows of the 5 tables); a set `apiKey`/`password` is written as `TO_BE_SET`, so the folder can be committed |
| `perspective/perspective.py` | development helper for the **DataConnector** perspective. The module installs that perspective itself at startup when it is absent (`PerspectiveInstaller`, no other module needed; `-Debx.connectors.perspective=false` to disable) from `ebx-connectors/src/main/resources/com/on/ebx/connect/ebx/DataConnector.perspective.json` (4 sections, 13 items: workflow views, connectivity management (connector catalog, mappings), conflicts, runs and monitoring), and never modifies an existing one. The script reads the same file: it adds missing items to an existing perspective, or exports a menu edited by hand back into the file (`--dry-run`, `--export [file]`, `--key`); creating from the script needs `ebx-perspective-admin`. Icons are served by the module from `www/common/images/connect/perspective/`. Not covered: per-profile permissions and the default item |
| `export/` | export of the live configuration taken on 2026-09-12 (58 connectors, 52 mappings), after the dataset had been wiped and rebuilt from the seed plus the 2026-09-06 export; secrets are `TO_BE_SET` |

## Example workflows (`export/workflows/`)

Canonical JSON models to import with the EBX workflow MCP tool `workflow_model_import`, then publish:

- `DemoEnrichmentConflicts.json` - on-demand demo: a company (input `companyId`, default 739) is enriched by
  the mappings listed in `mappings` (default `140,139`: Recherche d'entreprises, trust 85, then Wikidata claims, trust 40; the company needs a `wikidataId`);
  the conflicts logged by the TRUSTED policy route to the "Manage connector conflicts" user task, then the
  enriched record is reviewed. The script task is declared in library mode
  (`ebx-connectors@com.on.ebx.connect.ebx.workflow.ConnectorScriptTaskBean`), which is what shows its parameters
  in the editor. Mapping ids are those of the seeded configuration; check them in `GET /ebx-connectors/rest/v1/mappings`.
- `DemoEnrichmentConflicts.steps.json` - English and French label/description of each step, applied with
  `localize_steps.py DemoEnrichmentConflicts export/workflows/DemoEnrichmentConflicts.steps.json` after the import
  (the MCP import takes one string per step; the script writes the per-locale documentation through Data Services);
  republish afterwards.
- `CompanyCreationEnriched.json` - creation of a company in a child dataspace, enrichment by an EBX script, steward validation, merge.

## Example mappings (`export/examples/`)

- `kafka-crm-partner.json` - outbound mapping *Company -> Kafka / CRM partner*: publishes a company on topic
  `crm.partner.upsert` as a nested "partner" JSON that differs from the EBX model, one transformation per rule
  (`prefix`, `trim|upper`, `capitalize|left`, `map`, `digits|vatfr`, `left(4)|number`, `regex`, `number|round`,
  `boolean`, `isodate`, `constant`) and three **sub-object mappings** (`children`): `identifiers[]` from CompanyIdentifier
  (childFilter excludes the Wikidata ids, `lookup` translates the type through the IdentifierType reference table),
  `sites[]` from CompanyAddress (usage label by `lookup`, flag by `map|boolean`), `people[]` from Contact
  (active contacts only, names normalised, phone to E.164, role label by `lookup`). Inputs: `key` = `${./Identification/Id}` (Kafka message key), `topic`, `table`.
  Create the mapping, its rules and inputs through Data Services (see `import_config.py` for the row format) or
  in the designer; then `POST /ebx-connectors/rest/v1/sync/<id>?key=<companyId>` and read the topic with
  `kafka-console-consumer.sh --property print.key=true`.
