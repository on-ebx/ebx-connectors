#!/usr/bin/env python3
"""Deterministic fixture coverage for the read-only upgrade snapshot comparator."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile


ROOT = Path(__file__).resolve().parents[4]
SCRIPT = ROOT / "tools/upgrade_snapshot.py"
SPEC = importlib.util.spec_from_file_location("upgrade_snapshot", SCRIPT)
UPGRADE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(UPGRADE)


class Args:
    pass


def write_snapshot(directory, tables):
    directory.mkdir()
    manifest = {"base": "fixture", "tables": {}}
    for table, records in tables.items():
        (directory / (table + ".json")).write_text(json.dumps(records), encoding="utf-8")
        manifest["tables"][table] = {"available": True, "count": len(records)}
    (directory / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


def compare(before, after):
    args = Args()
    args.before, args.after = str(before), str(after)
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return UPGRADE.compare(args)


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    # Data Services envelopes are normalized without selector noise or secret disclosure.
    row = UPGRADE.content({"content": {
        "id": {"content": "customer"}, "password": {"content": "private"},
        "apiKey": "vault-reference", "optional": None, "selector": "ignored",
    }})
    check(row == {"id": "customer", "password": "__PRESENT__", "apiKey": "__PRESENT__"},
          "snapshot normalization exposed secrets or retained Data Services metadata")

    with tempfile.TemporaryDirectory(prefix="connectors-upgrade-fixture-") as temp:
        root = Path(temp)
        before, additive, missing, changed = (root / name for name in ("before", "additive", "missing", "changed"))
        original = [{"id": "customer", "label": "Customer value", "password": "__PRESENT__"}]
        write_snapshot(before, {"Connector": original})
        write_snapshot(additive, {"Connector": [dict(original[0], category="DATABASE"),
                                                  {"id": "packaged", "label": "New seed"}]})
        write_snapshot(missing, {"Connector": []})
        write_snapshot(changed, {"Connector": [{"id": "customer", "label": "Overwritten",
                                                  "password": "__PRESENT__"}]})

        check(compare(before, additive) == 0,
              "additive fields and packaged rows must preserve the upgrade baseline")
        check(compare(before, missing) == 1, "a deleted pre-upgrade record was not detected")
        check(compare(before, changed) == 1, "an overwritten customer value was not detected")

    print("PASS: upgrade snapshot redaction and additive-preservation comparison fixtures")


if __name__ == "__main__":
    main()
