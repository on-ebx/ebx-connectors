# Connector governance API

**Status (read against the code, 2026-10-06): not connected.** No REST route, MCP tool or screen builds `ConnectorGovernanceApplicationService`, and no `ConnectorGovernanceAccess` backed by EBX permissions exists; the classes and `EbxConnectorGovernanceStore` are exercised by the tests only. What the module enforces today is the three rights of `ebx/ConnectorsAccess` (`READ`, `RUN`, `MANAGE`), which are not these four permissions. The text below describes the contract an adapter has to meet.

`ConnectorGovernanceApplicationService` is the transport-neutral API for connector and mapping
governance. REST, MCP and EBX UI adapters must supply an authenticated actor and an
`ConnectorGovernanceAccess` implementation backed by EBX permissions.

The boundary separates four permissions: `READ`, `MANAGE`, `APPROVE` and `AUDIT`. Impact results
are filtered again per affected resource, so permission to inspect one connector cannot reveal the
existence of a restricted mapping. Approval and publication require the stronger `APPROVE`
permission; owners with only `MANAGE` may submit a draft.

The API exposes the count and provider schemes of secret references for operational auditing. It
never returns the reference locator, vault path, key, environment variable, file name, or resolved
secret. Audit events follow the same rule. Transport adapters must derive the actor from the EBX
session rather than accepting an actor supplied in a request body or query parameter.

Production persistence is supplied through `ConnectorGovernanceStore`. The in-memory adapter is
intended for deterministic tests and embedded demonstrations, not as the production system of
record.
