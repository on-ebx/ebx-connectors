#!/usr/bin/env python3
"""Read-only Connectors dataset snapshot and additive-upgrade comparison.

Authentication is read from EBX_USER and EBX_PASSWORD. Capture uses only HTTP GET against EBX
Data Services; it never invokes the connector configuration export endpoint (which runs installer
ensure) and never writes to the repository.
"""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

DEFAULT_TABLES = (
    "ConnectorCategory", "Connector", "ConnectorProperty", "Mapping", "MappingInput", "MappingRule",
    "ConnectorTemplate", "SchemaRefreshState", "SynchronizationCheckpoint", "FieldProvenance", "Conflict",
    "ConnectorOperationalMetrics", "ConnectorGovernance", "ConnectorGovernanceAudit", "ExecutionRun",
    "OutboundState", "SyncLog",
)
PRIMARY_KEYS = {
    "ConnectorCategory": ("code",), "Connector": ("id",),
    "ConnectorProperty": ("connector", "key"), "Mapping": ("id",),
    "MappingInput": ("mapping", "name"), "MappingRule": ("id",),
    "ConnectorTemplate": ("id",), "SchemaRefreshState": ("connector", "key"),
    "SynchronizationCheckpoint": ("mapping",),
    # volume lot 5 (2026-09-28): FieldProvenance's primary key changed from the auto-increment id to
    # this natural key - see tools/build_xsd.py and docs/REFERENCE.md.
    "FieldProvenance": ("dataspace", "dataset", "tablePath", "recordKey", "fieldPath"), "Conflict": ("id",),
    "ConnectorOperationalMetrics": ("connector",), "ConnectorGovernance": ("resourceId",),
    "ConnectorGovernanceAudit": ("id",), "ExecutionRun": ("runId",),
    "OutboundState": ("mapping", "recordKey"), "SyncLog": ("id",),
}
SECRET_FIELDS = {"password", "apiKey"}


def scalar(value):
    while isinstance(value, dict) and "content" in value:
        value = value["content"]
    return value


def content(row):
    value = row.get("content", row) if isinstance(row, dict) else row
    if not isinstance(value, dict):
        raise ValueError("Unexpected Data Services row")
    normalized = {}
    for key, raw in value.items():
        if key in ("details", "selector"):
            continue
        item = scalar(raw)
        if item is None:
            continue  # additive initialization of a formerly blank field is allowed
        normalized[key] = "__PRESENT__" if key in SECRET_FIELDS and str(item) else item
    return normalized


def rows(document):
    if isinstance(document, list):
        return document
    if isinstance(document, dict):
        for key in ("rows", "content"):
            if isinstance(document.get(key), list):
                return document[key]
    raise ValueError("Unexpected Data Services collection envelope")


def key_for(table, row):
    fields = PRIMARY_KEYS.get(table)
    if not fields:
        raise ValueError("No primary-key definition for " + table)
    return "|".join(str(row.get(field, "")) for field in fields)


def request_json(url, user, password):
    request = urllib.request.Request(url, method="GET")
    token = base64.b64encode((user + ":" + password).encode("utf-8")).decode("ascii")
    request.add_header("Authorization", "Basic " + token)
    request.add_header("Accept", "application/json")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def capture(args):
    user, password = os.environ.get("EBX_USER"), os.environ.get("EBX_PASSWORD")
    if not user or password is None:
        raise SystemExit("Set EBX_USER and EBX_PASSWORD; credentials are never written to the snapshot")
    destination = pathlib.Path(args.output)
    destination.mkdir(parents=True, exist_ok=False)
    manifest = {"base": args.base, "tables": {}}
    for table in args.tables:
        url = args.base.rstrip("/") + "/" + table + "?pageSize=" + str(args.page_size)
        try:
            normalized = [content(row) for row in rows(request_json(url, user, password))]
        except urllib.error.HTTPError as error:
            if error.code == 404:
                manifest["tables"][table] = {"available": False, "count": 0}
                continue
            raise
        normalized.sort(key=lambda row: key_for(table, row))
        encoded = json.dumps(normalized, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        (destination / (table + ".json")).write_bytes(encoded + b"\n")
        manifest["tables"][table] = {"available": True, "count": len(normalized), "sha256": hashlib.sha256(encoded).hexdigest()}
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def load_table(directory, table):
    path = pathlib.Path(directory) / (table + ".json")
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else []


def compare(args):
    failures = []
    before_manifest = json.loads((pathlib.Path(args.before) / "manifest.json").read_text(encoding="utf-8"))
    after_manifest = json.loads((pathlib.Path(args.after) / "manifest.json").read_text(encoding="utf-8"))
    for table, metadata in before_manifest["tables"].items():
        if not metadata.get("available"):
            continue
        before = {key_for(table, row): row for row in load_table(args.before, table)}
        after = {key_for(table, row): row for row in load_table(args.after, table)}
        missing = sorted(set(before) - set(after))
        if missing:
            failures.append("%s missing keys: %s" % (table, ", ".join(missing)))
        for key in sorted(set(before) & set(after)):
            # New fields are additive; every pre-upgrade non-null field must retain its value.
            changed = [field for field, value in before[key].items() if after[key].get(field) != value]
            if changed:
                failures.append("%s[%s] changed fields: %s" % (table, key, ", ".join(changed)))
        print("%-30s before=%-6d after=%-6d preserved=%d" %
              (table, len(before), len(after), len(set(before) & set(after))))
    if failures:
        print("UPGRADE COMPARISON FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1
    print("PASS: every pre-upgrade key and non-null, non-secret value is preserved")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    snapshot = commands.add_parser("capture", help="capture a read-only Data Services baseline")
    snapshot.add_argument("output")
    snapshot.add_argument("--base", default="http://localhost:8080/ebx-dataservices/rest/data/v1/BConnectors/Connectors/root")
    snapshot.add_argument("--page-size", type=int, default=10000)
    snapshot.add_argument("--tables", nargs="+", default=list(DEFAULT_TABLES))
    comparison = commands.add_parser("compare", help="verify additive preservation between snapshots")
    comparison.add_argument("before")
    comparison.add_argument("after")
    args = parser.parse_args()
    return capture(args) if args.command == "capture" else compare(args)


if __name__ == "__main__":
    sys.exit(main() or 0)
