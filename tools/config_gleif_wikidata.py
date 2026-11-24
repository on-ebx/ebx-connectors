#!/usr/bin/env python3
"""Configuration-only enrichment mappings (no Java): GLEIF identifiers/addresses/parents and Wikidata identifiers.
Idempotent (matched on labels). Usage: python3 config_gleif_wikidata.py"""
import json, os, subprocess

BASE = "http://localhost:8080/ebx-dataservices/rest/data/v1/BConnectors/Connectors/root"
AUTH = os.environ.get("EBX_AUTH", "admin:admin")
STRIP = "regex('^(.*?)(?:[,\\s]+(?:Inc\\.?|Corp\\.?|Corporation|Ltd\\.?|Limited|LLC|L\\.?P\\.?|plc|SAS|SASU|SA|SARL|EURL|GmbH|AG|B\\.?V\\.?|N\\.?V\\.?|S\\.?p\\.?A\\.?|S\\.?r\\.?l\\.?|Oy|AB|A/S))?\\s*$',1)"


def call(method, path, body=None):
    cmd = ["curl", "-s", "-m", "60", "-u", AUTH, "-X", method, BASE + path, "-H", "Content-Type: application/json"]
    if body is not None:
        cmd += ["-d", json.dumps({"content": {k: {"content": v} for k, v in body.items() if v is not None}})]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    try:
        return json.loads(out)
    except Exception:
        return {}


def u(x):
    return u(x["content"]) if isinstance(x, dict) and "content" in x else x


def mappings():
    return {u(r["content"]["label"]): u(r["content"]["id"]) for r in call("GET", "/Mapping?pageSize=5000").get("rows", [])}


def ensure_mapping(label, header, inputs=(), rules=(), parent=None, child=None):
    known = mappings()
    if label in known:
        return known[label]
    body = dict(header, label=label, dataset="Companies", direction="INBOUND", active=True)
    if parent is not None:
        body["parent"] = str(parent)
        body.update(child)
    call("POST", "/Mapping", body)
    mid = mappings()[label]
    for name, expression in inputs:
        call("POST", "/MappingInput", {"mapping": str(mid), "name": name, "expression": expression})
    pos = 10
    for source, target, transformation, policy in rules:
        call("POST", "/MappingRule", {"mapping": str(mid), "position": pos, "sourcePath": source or None, "targetPath": target,
                                      "transformation": transformation or None, "policy": policy, "active": True})
        pos += 10
    print("created:", label, "->", mid)
    return mid


GLEIF_LEI = mappings().get("GLEIF record -> company legal information")
assert GLEIF_LEI, "seeded GLEIF mapping missing"
address_rules = lambda kind, root: [
    ("", "./type", "constant(%s)" % kind, "ALWAYS"),
    (root + ".addressLines", "./Street", "join(', ')|left(80)", "ALWAYS"),
    (root + ".postalCode", "./Zipcode", "left(80)", "ALWAYS"),
    (root + ".city", "./City", "capitalize|left(80)", "ALWAYS"),
    (root + ".region", "./Region", "left(80)", "ALWAYS"),
    (root + ".country", "./Country", "left(2)|upper", "ALWAYS")]
# --- children of the GLEIF record mapping: LEI identifier, legal and headquarters addresses
ensure_mapping("GLEIF record / LEI identifier", {"connector": "GLEIF", "operation": "lei", "tablePath": "/root/CompanyIdentifier"}, parent=GLEIF_LEI,
               child={"parentLinkField": "./company", "childKeyFields": "./type", "childFilter": "./type='LEI'", "deleteMissing": False},
               rules=[("", "./type", "constant(LEI)", "ALWAYS"), ("lei", "./value", "upper", "ALWAYS"), ("", "./issuingAuthority", "constant(GLEIF)", "ALWAYS"),
                      ("", "./isPrimary", "constant(true)|boolean", "ALWAYS"), ("registration.status", "./source", "prefix(GLEIF )", "ALWAYS")])
ensure_mapping("GLEIF record / legal address", {"connector": "GLEIF", "operation": "lei", "tablePath": "/root/CompanyAddress"}, parent=GLEIF_LEI,
               child={"parentLinkField": "./company", "childKeyFields": "./type", "childFilter": "./type='LEGAL'", "deleteMissing": False},
               rules=address_rules("LEGAL", "entity.legalAddress"))
ensure_mapping("GLEIF record / headquarters address", {"connector": "GLEIF", "operation": "lei", "tablePath": "/root/CompanyAddress"}, parent=GLEIF_LEI,
               child={"parentLinkField": "./company", "childKeyFields": "./type", "childFilter": "./type='HEADQUARTERS'", "deleteMissing": False},
               rules=address_rules("HEADQUARTERS", "entity.headquartersAddress"))
# --- parents: one mapping per GLEIF relationship operation, relationship row + link when the parent is in the referential
LOOKUP = "lookup(/root/Companies,./Identification/LegalEntityIdentifier,./Identification/Id)"
for op, kind, label in (("ultimateParent", "ULTIMATE_PARENT", "GLEIF ultimate parent -> relationship"), ("directParent", "DIRECT_PARENT", "GLEIF direct parent -> relationship")):
    parent_id = ensure_mapping(label, {"connector": "GLEIF", "operation": op, "tablePath": "/root/Companies", "responseRoot": "data.attributes"},
                               inputs=[("lei", "${./Identification/LegalEntityIdentifier}")],
                               rules=([("lei", "./AdditionalInformation/ultimateParent", LOOKUP, "IF_EMPTY")] if kind == "ULTIMATE_PARENT" else []))
    ensure_mapping(label + " / relationship row", {"connector": "GLEIF", "operation": op, "tablePath": "/root/CompanyRelationship"}, parent=parent_id,
                   child={"parentLinkField": "./fromCompany", "childKeyFields": "./type", "childFilter": "./type='%s'" % kind, "deleteMissing": False},
                   rules=[("", "./type", "constant(%s)" % kind, "ALWAYS"), ("lei", "./relatedLei", "upper", "ALWAYS"), ("entity.legalName.name", "./relatedName", "left(255)", "ALWAYS"),
                          ("lei", "./toCompany", LOOKUP, "IF_EMPTY"), ("", "./status", "constant(ACTIVE)", "ALWAYS"), ("", "./source", "constant(GLEIF)", "ALWAYS")])
# --- LEI by name for companies without a national registration number (name sent without its legal form)
ensure_mapping("GLEIF search by name -> LEI", {"connector": "GLEIF", "operation": "searchByName", "tablePath": "/root/Companies", "responseRoot": "data.attributes"},
               inputs=[("name", "${./Identification/Name|%s}" % STRIP)],
               rules=[("lei", "./Identification/LegalEntityIdentifier", "upper", "IF_EMPTY"), ("", "./Governance/enrichmentSource", "constant(GLEIF)", "IF_EMPTY")])
# --- Wikidata identifiers (children of the seeded Wikidata mappings)
wk = mappings()
ensure_mapping("Wikidata search / WIKIDATA identifier", {"connector": "Wikidata", "operation": "search", "tablePath": "/root/CompanyIdentifier"}, parent=wk["Wikidata search by name -> wikidataId"],
               child={"parentLinkField": "./company", "childKeyFields": "./type", "childFilter": "./type='WIKIDATA'", "collectionPath": "search", "deleteMissing": False},
               rules=[("", "./type", "constant(WIKIDATA)", "ALWAYS"), ("id", "./value", "trim", "ALWAYS"), ("", "./issuingAuthority", "constant(Wikidata)", "ALWAYS"), ("", "./source", "constant(WIKIDATA)", "ALWAYS")])
ensure_mapping("Wikidata claims / ISIN identifier", {"connector": "Wikidata", "operation": "claims", "tablePath": "/root/CompanyIdentifier"}, parent=wk["Wikidata claims -> company (website, LEI, ISIN, parent)"],
               child={"parentLinkField": "./company", "childKeyFields": "./type", "childFilter": "./type='ISIN'", "collectionPath": "claims.P946", "deleteMissing": False},
               rules=[("", "./type", "constant(ISIN)", "ALWAYS"), ("mainsnak.datavalue.value", "./value", "upper", "ALWAYS"), ("", "./issuingAuthority", "constant(Wikidata)", "ALWAYS"), ("", "./source", "constant(WIKIDATA)", "ALWAYS")])
print("done; mappings now:", len(mappings()))
