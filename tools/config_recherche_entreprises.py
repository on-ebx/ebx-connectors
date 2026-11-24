#!/usr/bin/env python3
"""Mapping for the 'API Recherche d'entreprises' connector imported from its OpenAPI specification:
search a company by name (or SIREN) and store SIREN, NAF, head office, creation date, status.
Idempotent (matched on labels). Usage: python3 config_recherche_entreprises.py"""
import json, os, subprocess

BASE = "http://localhost:8080/ebx-dataservices/rest/data/v1/BConnectors/Connectors/root"
AUTH = os.environ.get("EBX_AUTH", "admin:admin")
STRIP = "regex('^(.*?)(?:[,\\s]+(?:Inc\\.?|Corp\\.?|Ltd\\.?|LLC|SAS|SASU|SA|SARL|EURL|GmbH|AG|B\\.?V\\.?|N\\.?V\\.?|S\\.?p\\.?A\\.?))?\\s*$',1)"


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
        print("exists:", label, "->", known[label])
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


connectors = {u(r["content"]["id"]) for r in call("GET", "/Connector?pageSize=500").get("rows", [])}
assert "RechercheEntreprises" in connectors, "import the connector first: POST /ebx-connectors/rest/v1/connectors/openapi?id=RechercheEntreprises&url=https://recherche-entreprises.api.gouv.fr/openapi.json"
# q = the SIREN when known (exact), else the name without its legal form
main = ensure_mapping("Recherche d'entreprises (data.gouv) -> company", {"connector": "RechercheEntreprises", "operation": "search", "tablePath": "/root/Companies"},
    inputs=[("q", "${./LegalInformation/registrationNumber|default(${./Identification/Name|%s})}" % STRIP), ("per_page", "1"), ("minimal", "false")],
    rules=[("results[0].siren", "./LegalInformation/registrationNumber", "digits", "IF_EMPTY"),
           ("results[0].nom_complet", "./LegalInformation/legalName", "trim|left(255)", "IF_EMPTY"),
           ("results[0].nom_raison_sociale", "./Identification/Name", "trim|left(80)", "IF_EMPTY"),
           ("results[0].activite_principale", "./AdditionalInformation/naceCode", "left(2)|fk(/root/ReferenceData/NaceCode)", "IF_EMPTY"),
           ("results[0].date_creation", "./LegalInformation/incorporationDate", "isodate", "IF_EMPTY"),
           ("results[0].etat_administratif", "./Lifecycle/status", "map(A=ACTIVE;C=CLOSED)", "IF_EMPTY"),
           ("results[0].siege.adresse", "./Address/Street", "left(80)", "IF_EMPTY"),
           ("results[0].siege.code_postal", "./Address/Zipcode", "trim", "IF_EMPTY"),
           ("results[0].siege.libelle_commune", "./Address/City", "capitalize|left(80)", "IF_EMPTY"),
           ("", "./Address/Country", "constant(FR)", "IF_EMPTY"),
           ("results[0].siege.latitude", "./Address/latitude", "round(7)", "IF_EMPTY"),
           ("results[0].siege.longitude", "./Address/longitude", "round(7)", "IF_EMPTY"),
           ("", "./Governance/enrichmentSource", "constant(RECHERCHE_ENTREPRISES)", "IF_EMPTY")])
ensure_mapping("Recherche d'entreprises / SIREN identifier", {"connector": "RechercheEntreprises", "operation": "search", "tablePath": "/root/CompanyIdentifier"}, parent=main,
    child={"parentLinkField": "./company", "childKeyFields": "./type", "childFilter": "./type='SIREN'", "collectionPath": "results", "deleteMissing": False},
    rules=[("", "./type", "constant(SIREN)", "ALWAYS"), ("siren", "./value", "digits", "ALWAYS"), ("", "./issuingAuthority", "constant(INSEE)", "ALWAYS"),
           ("", "./source", "constant(RECHERCHE_ENTREPRISES)", "ALWAYS")])
print("done")
