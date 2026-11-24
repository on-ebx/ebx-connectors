#!/usr/bin/env python3
"""Deterministic fixture coverage for the read-only upgrade snapshot comparator."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET


# The script ships WITH the module (ebx-connectors/tools/), so this test reads the file of the module it sits in: the repository's in a
# checkout, the installed one in a workspace copy - never a file left by hand in <workspace>/tools (audit X3-09).
MODULE = Path(__file__).resolve().parents[3]
SCRIPT = MODULE / "tools/upgrade_snapshot.py"
MODEL = MODULE / "src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd"
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


def model_tables():
    """The tables of Connectors.xsd with their primary-key fields, read from the model itself."""
    xs = "{http://www.w3.org/2001/XMLSchema}"
    tables = {}
    for element in ET.parse(MODEL).getroot().iter(xs + "element"):
        if element.get("maxOccurs") != "unbounded":
            continue
        for node in element.iter():
            if node.tag.endswith("primaryKeys"):
                tables[element.get("name")] = tuple(part.strip().lstrip("/") for part in node.text.split() if part.strip())
    return tables


def coverage_problems(tables, covered, excluded, keys):
    """Every table of the model is captured or excluded WITH a reason; keys match the model; nothing stale is listed."""
    problems = []
    for table, primary_key in sorted(tables.items()):
        if table in covered and table in excluded:
            problems.append(table + " is both covered and excluded")
        elif table not in covered and table not in excluded:
            problems.append(table + " is in the model and neither captured nor excluded with a reason")
        elif table in covered and tuple(keys.get(table, ())) != primary_key:
            problems.append("%s: key %s differs from the model's %s" % (table, keys.get(table), primary_key))
        elif table in excluded and not str(excluded[table]).strip():
            problems.append(table + " is excluded without a reason")
    for table in sorted((set(covered) | set(excluded)) - set(tables)):
        problems.append(table + " is listed but is not a table of the model")
    return problems


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

    # Audit C3-10: all 27 tables of the model are accounted for (17 were before 1.4.2)
    tables = model_tables()
    check(len(tables) == 27, "the model has %d tables, this test was written for 27: read the new ones, then update the count" % len(tables))
    problems = coverage_problems(tables, UPGRADE.DEFAULT_TABLES, UPGRADE.EXCLUDED_TABLES, UPGRADE.PRIMARY_KEYS)
    check(not problems, "upgrade snapshot coverage: " + "; ".join(problems))
    check(len(UPGRADE.DEFAULT_TABLES) + len(UPGRADE.EXCLUDED_TABLES) == len(tables), "captured + excluded must be the whole model")
    # the check itself works: a table added to the model without a decision, a wrong key, an excluded table with no reason are all flagged
    extra = dict(tables, NewTable=("id",))
    check(coverage_problems(extra, UPGRADE.DEFAULT_TABLES, UPGRADE.EXCLUDED_TABLES, UPGRADE.PRIMARY_KEYS) ==
          ["NewTable is in the model and neither captured nor excluded with a reason"], "an uncovered table of the model must fail the test")
    check(coverage_problems(tables, UPGRADE.DEFAULT_TABLES, dict(UPGRADE.EXCLUDED_TABLES, OutboundQueue=""), UPGRADE.PRIMARY_KEYS) ==
          ["OutboundQueue is excluded without a reason"], "an exclusion without a reason must fail the test")
    check(coverage_problems(tables, UPGRADE.DEFAULT_TABLES, UPGRADE.EXCLUDED_TABLES, dict(UPGRADE.PRIMARY_KEYS, Favorite=("userLogin",))) ==
          ["Favorite: key ('userLogin',) differs from the model's ('userLogin', 'targetType', 'targetKey')"], "a primary key that differs from the model's must fail the test")

    print("PASS: upgrade snapshot redaction and additive-preservation comparison fixtures")


if __name__ == "__main__":
    main()
