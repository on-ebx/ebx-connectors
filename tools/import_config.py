#!/usr/bin/env python3
"""Re-import a customised Connectors configuration after the module re-created and seeded its dataset.

Usage: python3 import_config.py <export dir>   (JSON files written by the export step: Connector.json,
ConnectorProperty.json, Mapping.json, MappingInput.json, MappingRule.json)

Idempotent: a connector is created only when its id is absent, a mapping only when its label is absent
(with its inputs and rules, the mapping id being re-mapped), and for an existing mapping every rule
whose (sourcePath, targetPath) is absent is added. Nothing is deleted.
"""
import json, os, subprocess, sys

BASE = "http://localhost:8080/ebx-dataservices/rest/data/v1/BConnectors/Connectors/root"
AUTH = os.environ.get("EBX_AUTH", "admin:admin")


def call(method, path, body=None):
    cmd = ["curl", "-s", "-m", "60", "-u", AUTH, "-X", method, BASE + path, "-H", "Content-Type: application/json"]
    if body is not None:
        cmd += ["-d", json.dumps({"content": {k: {"content": v} for k, v in body.items() if v is not None}})]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    try:
        return json.loads(out)
    except Exception:
        return {}


def unwrap(x):
    if isinstance(x, dict) and "content" in x:
        return unwrap(x["content"])
    if isinstance(x, list):
        return [unwrap(i) for i in x]
    return x


def local(directory, table):
    """Rows of an export file: either flattened dicts or raw Data Services rows ({"content": {...}})."""
    items = json.load(open(os.path.join(directory, table + ".json")))
    out = []
    for r in items:
        if isinstance(r, dict) and "content" in r and "details" in r:
            out.append({k: unwrap(v) for k, v in r["content"].items()})
        else:
            out.append(r)
    return out

def rows(table):
    return [{k: unwrap(v) for k, v in r["content"].items()} for r in call("GET", "/%s?pageSize=5000" % table).get("rows", [])]


def same(a, b):
    return str(a) == str(b)


def main(export_dir):
    load = lambda t: local(export_dir, t)
    connectors, properties, mappings, inputs, rules = (load(t) for t in ["Connector", "ConnectorProperty", "Mapping", "MappingInput", "MappingRule"])
    existing_connectors = {c["id"] for c in rows("Connector")}
    for c in connectors:
        if c["id"] in existing_connectors:
            continue
        body = {k: c.get(k) for k in ["id", "label", "type", "baseUrl", "authType", "apiKeyHeader", "description", "active", "connectTimeoutMs", "readTimeoutMs"]}
        for extra in ("trust", "logo", "category"):
            if c.get(extra) not in (None, ""):
                body[extra] = c[extra]
        call("POST", "/Connector", body)
        for p in properties:
            if same(p["connector"], c["id"]):
                call("POST", "/ConnectorProperty", {"connector": c["id"], "key": p["key"], "value": p["value"]})
        print("connector created:", c["id"], "(secrets are never exported: enter them in EBX)")
    existing = {m["label"]: m["id"] for m in rows("Mapping")}
    id_map = {}
    for m in sorted(mappings, key=lambda x: (x.get("parent") is not None, x["id"])):
        if m["label"] in existing:
            id_map[str(m["id"])] = str(existing[m["label"]])
            continue
        body = {k: m.get(k) for k in ["connector", "operation", "direction", "label", "dataspace", "dataset", "tablePath", "responseRoot", "active",
                                       "collectionPath", "parentLinkField", "childKeyFields", "childFilter", "deleteMissing", "itemsPath", "keyPredicate", "createIfMissing"]}
        if m.get("parent") is not None:
            body["parent"] = str(id_map.get(str(m["parent"]), m["parent"]))
        call("POST", "/Mapping", body)
        new_id = {x["label"]: x["id"] for x in rows("Mapping")}.get(m["label"])
        id_map[str(m["id"])] = str(new_id)
        for i in inputs:
            if same(i["mapping"], m["id"]):
                call("POST", "/MappingInput", {"mapping": str(new_id), "name": i["name"], "expression": i["expression"]})
        for r in rules:
            if same(r["mapping"], m["id"]):
                call("POST", "/MappingRule", {"mapping": str(new_id), "position": r["position"], "sourcePath": r.get("sourcePath"), "targetPath": r["targetPath"],
                                              "transformation": r.get("transformation"), "policy": r.get("policy"), "active": r.get("active", True), "comment": r.get("comment")})
        print("mapping created:", m["label"], "->", new_id)
    # inputs and rules missing on mappings that already existed (seeded ones, or re-imported ones)
    current_inputs = rows("MappingInput")
    have_inputs = {(str(i["mapping"]), i["name"]) for i in current_inputs}
    added_inputs = 0
    for i in inputs:
        target = id_map.get(str(i["mapping"]))
        if target is not None and (str(target), i["name"]) not in have_inputs:
            call("POST", "/MappingInput", {"mapping": str(target), "name": i["name"], "expression": i["expression"]})
            added_inputs += 1
    print("inputs added to existing mappings:", added_inputs)
    current_rules = rows("MappingRule")
    have = {(str(r["mapping"]), r.get("sourcePath") or "", r["targetPath"]) for r in current_rules}
    added = 0
    for r in rules:
        target = id_map.get(str(r["mapping"]))
        if target is None:
            continue
        if (str(target), r.get("sourcePath") or "", r["targetPath"]) not in have:
            call("POST", "/MappingRule", {"mapping": str(target), "position": r["position"], "sourcePath": r.get("sourcePath"), "targetPath": r["targetPath"],
                                          "transformation": r.get("transformation"), "policy": r.get("policy"), "active": r.get("active", True), "comment": r.get("comment")})
            added += 1
    print("rules added to existing mappings:", added)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "export")
