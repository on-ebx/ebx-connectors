# Upgrade and rollback notes

## Values under EBX permissions (audit C2-03)

- `ebx.connectors.log.storeChanges` now defaults to `false`: new `SyncLog.changes` lines hold no field values or outbound documents.
  Set `-Debx.connectors.log.storeChanges=true` to get the previous behaviour (read at each log line, no restart).
- Values already stored (`SyncLog.changes`, `FieldProvenance.value`, conflicts, outbound states) are NOT removed by the upgrade. They are
  now hidden from any session that cannot read the record they came from. To erase them, purge by retention
  (`ebx.connectors.log.retentionDays`, `ebx.connectors.retention.*`) or delete the rows; `FieldProvenance` and `OutboundState` are kept
  by default (section 6 of OPERATIONS.md).
- The field rules are declared in the schema extensions: they apply after the module's context is reloaded (a restart on the RC).
- Rollback: deploy the previous build; nothing is written to the model, so no data migration is needed. Hidden values become visible
  again to readers of the Connectors tables.
