#!/usr/bin/env python3
"""The DataConnector perspective: complete it on an instance, or capture it from one.

The module creates the perspective itself at startup when it is absent (PerspectiveInstaller, from the
packaged ebx-connectors/src/main/resources/com/on/ebx/connect/ebx/DataConnector.perspective.json, the
single definition this script also reads). This script remains for development: adding items defined in
the file to an existing perspective (the module never modifies one), and exporting a menu edited by hand.
Creating an absent perspective from here needs the `ebx-perspective-admin` module.

  perspective.py                    # add what is missing (create when absent, through ebx-perspective-admin)
  perspective.py --dry-run          # say what would be created or added, write nothing
  perspective.py --export [file]    # rewrite the definition from the live perspective
  perspective.py --key OtherKey     # install the same menu under another dataset key (tests)

Installation is idempotent. A missing perspective is created whole (`perspective_create`, activated).
An existing one is completed: a section is matched on its en-US label, an action on its service key and
parameters (dataspace, dataset, table or workflow view), and only what is missing is added
(`perspective_add_items`, placed after its previous sibling). Nothing is deleted, moved or relabelled:
items added by hand stay. Not covered, as in ebx-perspective-admin: per-profile permissions
(Administration > DataConnector > Actions > Permissions) and the default item.

The menu expects the Connectors dataset in the Connectors dataspace (the module's default) and the icons
served by the module under /ebx-connectors/www/common/images/connect/perspective/.

Environment: EBX_URL (default http://localhost:8080), EBX_AUTH (default admin:admin).
"""
import base64, json, os, sys, urllib.error, urllib.request

EBX = os.environ.get("EBX_URL", "http://localhost:8080")
AUTH = "Basic " + base64.b64encode(os.environ.get("EBX_AUTH", "admin:admin").encode()).decode()
HERE = os.path.dirname(os.path.abspath(__file__))
DEFINITION = os.path.join(HERE, "..", "..", "ebx-connectors", "src", "main", "resources", "com", "on", "ebx",
                          "connect", "ebx", "DataConnector.perspective.json")
ACCESS_DATA = "ebx-root-1.0@default"


def http(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": AUTH, "Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, None


def tool(name, arguments):
    status, answer = http("POST", EBX + "/ebx-perspective-admin/rest/mcp",
                          {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                           "params": {"name": name, "arguments": arguments}})
    if status == 404:
        sys.exit("ebx-perspective-admin is not deployed on %s: it is needed to create the perspective "
                 "dataset (no REST API for that)." % EBX)
    result = (answer or {}).get("result") or {}
    if status != 200 or result.get("isError"):
        sys.exit("%s failed (HTTP %s): %s" % (name, status, result.get("structuredContent") or answer))
    return result.get("structuredContent")


def value(node):
    return node.get("content") if isinstance(node, dict) else node


def live_items(key):
    """The perspective's menu rows, or None when the perspective does not exist."""
    status, answer = http("GET", "%s/ebx-dataservices/rest/data/v1/Bebx-manager/%s/domain/menuItem?pageSize=10000"
                          % (EBX, key))
    if status == 404:
        return None
    if status != 200:
        sys.exit("Reading the menu of %s failed (HTTP %s)" % (key, status))
    items = []
    for row in answer["rows"]:
        c = row["content"]
        docs = value(value(c["label"])["localizedDocumentations"]) or []
        if isinstance(docs, dict):
            docs = [{"content": docs}]
        labels = {value(d["content"]["locale"]): value(d["content"]["label"]) for d in docs}
        icon, action = value(c["icon"]), value(c["action"])
        params = value(action["serviceParameters"])
        params = [(value(p["content"]["name"]), value(p["content"]["value"]))
                  for p in (params if isinstance(params, list) else [])]
        parent = value(c["parent"])  # a foreign key: the id as a string
        items.append({
            "id": int(value(c["id"])), "type": value(c["type"]), "parent": int(parent) if parent else None,
            "order": value(c["order"]) or 0, "labelEn": labels.get("en-US"), "labelFr": labels.get("fr-FR"),
            "separator": bool(value(c["hasTopSeparator"])),
            "iconType": value(icon["type"]), "icon": value(icon["reference"]),
            "serviceKey": value(action["serviceKey"]),
            "params": [(n, v) for n, v in params if n and v is not None]})
    return items


def signature(node):
    """What identifies an action, whatever its form (table shorthand, generic action or live row)."""
    if "params" in node:  # live row
        key, params = node["serviceKey"], dict(node["params"])
    elif node.get("serviceKey"):
        key, params = node["serviceKey"], {p["name"]: p["value"] for p in node.get("parameters", [])}
    else:
        key = ACCESS_DATA
        params = {"branch": node["dataspace"], "instance": node["dataset"], "xpath": node["xpath"]}
    params.pop("service", None)
    return key, tuple(sorted(params.items()))


def to_definition(items):
    """Live rows -> perspective_create items, in menu order."""
    def node(item):
        n = {"type": item["type"]}
        params = dict(item["params"])
        shorthand = (item["type"] == "action" and item["serviceKey"] == ACCESS_DATA and not item["labelEn"]
                     and set(params) <= {"service", "branch", "instance", "xpath"})
        if shorthand:
            n.update(dataspace=params["branch"], dataset=params["instance"], xpath=params["xpath"])
        else:
            n["labelEn"] = item["labelEn"]
            if item["labelFr"]:
                n["labelFr"] = item["labelFr"]
            if item["type"] == "action":
                n["serviceKey"] = item["serviceKey"]
                n["parameters"] = [{"name": k, "value": v} for k, v in item["params"]]
        if item["separator"]:
            n["hasTopSeparator"] = True
        if item["icon"]:
            n["iconCss" if item["iconType"] == "css" else "iconUrl"] = item["icon"]
        children = [node(c) for c in sorted((c for c in items if c["parent"] == item["id"]), key=lambda c: c["order"])]
        if children:
            n["children"] = children
        return n
    return [node(i) for i in sorted((i for i in items if i["parent"] is None), key=lambda i: i["order"])]


def match(wanted, candidates):
    for c in candidates:
        if wanted["type"] != c["type"]:
            continue
        if wanted["type"] == "action":
            if signature(wanted) == signature(c):
                return c
        elif wanted.get("labelEn") == c["labelEn"]:
            return c
    return None


def complete(key, wanted_nodes, items, parent_id, dry_run, path=""):
    """Adds the missing nodes under parent_id; returns how many top-most nodes were added."""
    siblings = sorted((i for i in items if i["parent"] == parent_id), key=lambda i: i["order"])
    added, previous = 0, None
    for wanted in wanted_nodes:
        label = wanted.get("labelEn") or wanted.get("xpath") or wanted.get("serviceKey")
        found = match(wanted, siblings)
        if found:
            added += complete(key, wanted.get("children", []), items, found["id"], dry_run, path + label + " / ")
            previous = found
            continue
        print("  + %s%s" % (path, label))
        added += 1
        if dry_run:
            continue
        arguments = {"perspectiveKey": key, "items": [wanted]}
        if parent_id is not None:
            arguments["parentId"] = parent_id
        if previous:
            arguments["afterItemId"] = previous["id"]
        elif siblings:
            arguments["beforeItemId"] = siblings[0]["id"]
        tool("perspective_add_items", arguments)
        items[:] = live_items(key)  # ids and orders of the siblings have moved
        siblings = sorted((i for i in items if i["parent"] == parent_id), key=lambda i: i["order"])
        previous = next(i for i in siblings if match(wanted, [i]))
    return added


def main(args):
    definition = json.load(open(DEFINITION, encoding="utf-8"))
    key = args[args.index("--key") + 1] if "--key" in args else definition["key"]

    if "--export" in args:
        items = live_items(key)
        if items is None:
            sys.exit("No perspective %s on %s" % (key, EBX))
        rest = args[args.index("--export") + 1:]
        target = rest[0] if rest and not rest[0].startswith("--") else DEFINITION
        definition["items"] = to_definition(items)
        with open(target, "w", encoding="utf-8") as f:
            json.dump(definition, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print("%d items of %s written to %s" % (len(items), key, target))
        return

    dry_run = "--dry-run" in args
    items = live_items(key)
    if items is None:
        print("Perspective %s absent: created with its whole menu%s" % (key, " (dry run)" if dry_run else ""))
        if not dry_run:
            created = dict(definition, key=key)
            tool("perspective_create", created)
            print("  %d items" % len(live_items(key)))
        return
    added = complete(key, definition["items"], items, None, dry_run)
    print("Perspective %s: %s" % (key, "%d item(s) %s" % (added, "to add" if dry_run else "added") if added
                                  else "nothing missing"))


if __name__ == "__main__":
    main(sys.argv[1:])
