#!/usr/bin/env python3
"""Export the live Connectors configuration into the `export/` folder read back by import_config.py.

Usage: python3 export_config.py [export dir]   (default: export/, next to this script)

Writes Connector.json, ConnectorProperty.json, Mapping.json, MappingInput.json, MappingRule.json as the
raw Data Services rows of each table. Secrets are never exported: a set apiKey or password is written as
TO_BE_SET, an empty one as null, so the folder can be committed. Run it after any configuration change
worth keeping -- it is this folder that a recovery after the module re-created its dataset starts from.
"""
import json, os, subprocess, sys

BASE = "http://localhost:8080/ebx-dataservices/rest/data/v1/BConnectors/Connectors/root"
AUTH = os.environ.get("EBX_AUTH", "admin:admin")
TABLES = ["Connector", "ConnectorProperty", "Mapping", "MappingInput", "MappingRule"]
SECRETS = ("apiKey", "password")


def rows(table):
    out = subprocess.run(["curl", "-s", "-m", "120", "-u", AUTH, "-H", "Accept: application/json", BASE + "/%s?pageSize=5000" % table],
                         capture_output=True, text=True).stdout
    data = json.loads(out)
    if "rows" not in data:
        raise SystemExit("%s: unexpected answer %s" % (table, out[:300]))
    return data["rows"]


def mask(row):
    content = row.get("content", {})
    for key in SECRETS:
        if key in content and isinstance(content[key], dict):
            value = content[key].get("content")
            content[key]["content"] = None if value in (None, "") else "TO_BE_SET"
    return row


def main(directory):
    os.makedirs(directory, exist_ok=True)
    for table in TABLES:
        items = rows(table)
        if table == "Connector":
            items = [mask(r) for r in items]
        with open(os.path.join(directory, table + ".json"), "w", encoding="utf-8") as f:
            json.dump(items, f, indent=1, ensure_ascii=False)
            f.write("\n")
        print("%s: %d rows" % (table, len(items)))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "export"))
