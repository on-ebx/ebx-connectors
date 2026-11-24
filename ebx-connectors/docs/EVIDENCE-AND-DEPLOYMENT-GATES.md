# Evidence and deployment gates

This document turns the remaining external blockers into actionable intake packages. It does **not** claim a connector, infer an endpoint, select an unapproved dependency, or authorize deployment. The canonical machine-readable package is [`../evidence-gates.json`](../evidence-gates.json).

## How to use a gate

1. Create an evidence folder named for the coverage ID; do not store credentials or unredacted customer data in it.
2. Collect every shared and item-specific requirement from `evidence-gates.json`.
3. Record exact versions, checksums, owners, review dates, and accept/reject decisions. A link without a captured version is not evidence.
4. Run the relevant acceptance checks in a disposable environment using least privilege and documented bounds.
5. Obtain all owner-role approvals. Evidence collection alone does not change the status.
6. Promote the capability in a separate reviewed change only after every promotion criterion passes. Registration and catalog seeding remain separate implementation decisions.

## Gate A — legacy and proprietary JDBC drivers

Applies to `BASE-026`, `BASE-030`, `BASE-033`, `BASE-034`, `BASE-038`, `BASE-043`, `BASE-045`, `BASE-048`, `BASE-049`, and `BASE-050`.

Required review packet:

- Exact database/server, driver, and Java 8 support matrix from an authoritative source.
- Legally obtained driver archive; license decision; SHA-256 inventory; bytecode and vulnerability results.
- Driver class, URL grammar, TLS properties, authentication modes, and secret-handling decision.
- Disposable endpoint and redacted fixtures for catalog discovery, bounded reads, timeout, cancellation, and vendor errors.
- If writes are requested: separate authorization, prepared-statement coverage, transaction rollback, affected-row ceilings, and idempotency decision.

The packet fails if it relies on a bridge, an unpinned “latest” archive, a newer Java runtime, or a driver whose deployment rights are unresolved. A file-reader requirement must not be silently converted into a JDBC profile.

## Gate B — Parquet dependency and schema closure

Applies to `BASE-059`.

Required review packet:

- Named producer/consumer platforms and versions, intended read/write direction, storage transport, and volume envelope.
- Complete transitive dependency lock with artifact coordinates, versions, licenses, checksums, bytecode levels, and vulnerability disposition.
- Schema policy covering nesting, nulls, logical types, decimals, timestamps, binary values, and evolution.
- Hard limits for bytes, rows, row groups, columns, nesting, values, memory, and execution time.
- Golden platform-produced files plus malformed, truncated, oversized, deeply nested, and schema-conflict fixtures.

The packet fails if it supplies only top-level dependencies, leaves schema semantics open, or lacks fixtures from the target platforms. Until approval, the provider must remain absent and requests must fail explicitly.

## Gate C — licensed REST integrations and social search

Applies to `BASE-PKG-004`, `BASE-PKG-005`, and `BASE-067`.

Required review packet:

- Exact entitled product/API tier and versioned specification.
- Sandbox tenant, approved secret references, authentication renewal, and least-privilege scopes.
- Operation allowlist; entity schemas; matching rules; pagination; quotas or credits; retry and error contracts.
- Redacted fixtures for all required success and failure paths.
- Legal and governance decisions for purpose, provenance, retention, deletion, storage, enrichment, and redistribution.
- Bounded sandbox acceptance evidence including audit records and secret/error redaction.

The packet fails when implementation would require guessed endpoints or fields, when only marketing/UI evidence exists, when terms conflict with the use case, or when testing would expose production credentials or unredacted personal data.

## Evidence folder checklist

Use this neutral structure when a packet is collected:

```text
evidence/<coverage-id>/
  README.md                 # scope, owner, versions, dates, decisions
  artifact-inventory.json  # filenames, versions, SHA-256, license, bytecode
  contract/                # versioned specifications permitted for storage
  fixtures/                # redacted request/response or data files
  acceptance/              # commands, bounds, sanitized results
  approvals/               # security, legal/license, service and product decisions
```

Do not place secrets, access tokens, private keys, production URLs, or unredacted regulated data in repository evidence.
