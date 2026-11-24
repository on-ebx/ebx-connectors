#!/usr/bin/env python3
"""What changes in the Connectors data model between two builds, and what of it touches data (audit C-08).

    tools/schema_diff.py <old> <new>               two Connectors.xsd files
    tools/schema_diff.py --git <old-ref> <new-ref> two git refs (tag, commit), read from the repository this file is in

Compares tables, fields, field types and primary keys. EBX applies an added table or field by itself at the next start;
it resets a table whose primary key changed to empty, and treats a renamed or retyped field as a deletion plus a
creation (onHelp, references/datamodel_evolutions.html, 6.3 RC). Exit 0: nothing in the report is data-affecting.
Exit 1: at least one primary-key change, removed table or field, or type change: export the data first (docs/UPGRADE-ROLLBACK.md).
Only declared names, types and primary keys are read; facets, constraints and extensions are not compared.
"""
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

XS = "{http://www.w3.org/2001/XMLSchema}"
XSD_PATH = "ebx-connectors/src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd"


def describe(xml_text):
    """-> (fields: {path: type}, keys: {table: primaryKeys text}) of one schema document."""
    root = ET.fromstring(xml_text)
    types = {c.get("name"): c for c in root if c.tag == XS + "complexType" and c.get("name")}
    fields, keys = {}, {}

    def walk(node, path, seen):
        for child in node:
            if child.tag == XS + "element" and child.get("name"):
                here = path + "/" + child.get("name")
                fields[here] = child.get("type") or "(inline)"
                declared = child.get("type")
                primary = [e for e in child.iter() if e.tag.endswith("}primaryKeys") or e.tag == "primaryKeys"]
                if primary and child.get("maxOccurs"):
                    keys[child.get("name")] = " ".join((primary[0].text or "").split())
                if declared in types and declared not in seen:
                    walk(types[declared], here, seen + (declared,))
                walk(child, here, seen)
            else:
                walk(child, path, seen)

    walk(root, "", ())
    return fields, keys


def compare(old_xml, new_xml):
    """-> (informational lines, data-affecting lines)."""
    old_fields, old_keys = describe(old_xml)
    new_fields, new_keys = describe(new_xml)
    info, affecting = [], []
    for table in sorted(set(old_keys) | set(new_keys)):
        if table not in old_keys:
            info.append("table added: %s (key %s)" % (table, new_keys[table]))
        elif table not in new_keys:
            affecting.append("table removed or no longer a table: %s" % table)
        elif old_keys[table] != new_keys[table]:
            affecting.append("PRIMARY KEY CHANGED, the table is emptied by EBX: %s: %s -> %s" % (table, old_keys[table], new_keys[table]))
    for path in sorted(set(old_fields) | set(new_fields)):
        table = path.split("/")[2] if path.count("/") >= 2 else ""
        if path in old_fields and path not in new_fields:
            if table not in old_keys or table in new_keys:  # a removed table is already reported
                affecting.append("field removed (data dropped at the next update of each record): %s" % path)
        elif path not in old_fields and path in new_fields:
            if table in old_keys:
                info.append("field added: %s" % path)
        elif old_fields[path] != new_fields[path]:
            affecting.append("type changed (deletion plus creation): %s: %s -> %s" % (path, old_fields[path], new_fields[path]))
    return info, affecting


def from_git(ref):
    repo = Path(__file__).resolve().parents[2]
    return subprocess.check_output(["git", "-C", str(repo), "show", "%s:%s" % (ref, XSD_PATH)], text=True)


def main(argv):
    if len(argv) == 4 and argv[1] == "--git":
        old_xml, new_xml = from_git(argv[2]), from_git(argv[3])
    elif len(argv) == 3 and not argv[1].startswith("-"):
        old_xml, new_xml = Path(argv[1]).read_text(encoding="utf-8"), Path(argv[2]).read_text(encoding="utf-8")
    else:
        sys.stderr.write(__doc__)
        return 2
    info, affecting = compare(old_xml, new_xml)
    for line in info:
        print("INFO    " + line)
    for line in affecting:
        print("AFFECTS " + line)
    print("%d additive change(s), %d data-affecting change(s)" % (len(info), len(affecting)))
    return 1 if affecting else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
