#!/usr/bin/env python3
"""Localize the steps of a workflow model (label + description per locale).

The workflow MCP tools take one plain string per step; EBX itself stores a documentation per locale.
This script writes it directly through Data Services, in the workflow definitions dataspace.

Usage: localize_steps.py <modelName> <steps.json> [http://host:port] [user:password]
steps.json: {"<stepId>": {"fr-FR": ["label", "description"], "en-US": ["label", "description"]}, ...}
Republish the workflow afterwards (a publication is a snapshot of the model).
"""
import json, sys, base64, urllib.request

def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    model, path = sys.argv[1], sys.argv[2]
    base = (sys.argv[3] if len(sys.argv) > 3 else "http://localhost:8080").rstrip("/")
    auth = base64.b64encode((sys.argv[4] if len(sys.argv) > 4 else "admin:admin").encode()).decode()
    steps = json.load(open(path, encoding="utf-8"))
    for sid, locs in steps.items():
        docs = [{"content": {"locale": {"content": loc}, "label": {"content": lab}, "description": {"content": desc}}}
                for loc, (lab, desc) in locs.items()]
        body = {"content": {"documentation": {"content": {"localizedDocumentations": {"content": docs}}}}}
        url = f"{base}/ebx-dataservices/rest/data/v1/Bebx-workflow-definitions/{model}/root/definition/pnode/{sid}"
        req = urllib.request.Request(url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"), method="PUT",
                                     headers={"Content-Type": "application/json", "Authorization": "Basic " + auth})
        with urllib.request.urlopen(req) as r:
            print(f"step {sid}: HTTP {r.status} ({', '.join(locs)})")

if __name__ == "__main__":
    main()
