# Connector scaffold (GOV-003)

`tools/scaffold_connector.py` creates a reviewable starting point without changing the
connector registry, installer, catalog seed, roadmap, or main user guide. Generated connectors
are deliberately `UNAVAILABLE`, expose no operations, and throw if executed.

Preview the exact six output paths first:

```sh
python3 tools/scaffold_connector.py --check \
  --type example-api --label "Example API" --category GENERIC_INTEGRATION
```

Remove `--check` to create the connector class, staging profile, descriptor contract test,
synthetic-fixture README, neutral SVG placeholder, and connector-specific HTML guide. The guide
links back to `USER-GUIDE.html` and follows its Data Connector perspective terminology. Existing
files are never overwritten.

## Completion checklist

- Replace TODO text only with primary-source or acceptance-lab evidence.
- Define a bounded operation contract, typed descriptor metadata, supported versions, and explicit exclusions.
- Implement configuration validation, TLS/egress policy, least-privilege authentication, secret redaction, pagination, and timeouts.
- Add synthetic unit fixtures and live acceptance evidence; never commit secrets or customer data.
- Replace the neutral logo only with an approved, licensed asset and record its source.
- Complete the connector guide: setup, mapping, operations, safety, monitoring, recovery, and troubleshooting.
- Run the descriptor test and the full build before separately updating shared registration/catalog files.
- Obtain review before changing availability from `UNAVAILABLE` or exposing the connector in the catalog.

Run the generator contract test with:

```sh
python3 tools/test_scaffold_connector.py
```
