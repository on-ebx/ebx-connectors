#!/usr/bin/env python3
"""Offline structural checks for safe EBX-Connectors installation and upgrades."""

from pathlib import Path
import re
import sys


MODULE = Path(__file__).resolve().parents[3]
SOURCE = MODULE / "src/main/java/com/on/ebx/connect/ebx/ConnectorsInstaller.java"
CATALOG_SOURCE = MODULE / "src/main/java/com/on/ebx/connect/ebx/ui/ConnectorCatalogUserService.java"
RESOURCES = MODULE / "src/main/resources/com/on/ebx/connect/ebx/ui"


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def properties(path):
    values = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        match = re.match(r"([^:=\s]+)\s*[:=]", line)
        check(match is not None, f"{path.name}:{number}: malformed property")
        key = match.group(1)
        check(key not in values, f"{path.name}:{number}: duplicate key {key}")
        values[key] = number
    return values


def block(text, start_marker, end_marker):
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return text[start:end]


def main():
    installer = SOURCE.read_text(encoding="utf-8")

    # Category reference data uses stable, unique primary keys and deterministic ordering.
    migration = block(installer, "static void migrate(", "private static List<Adaptation> rows")
    category_array = block(migration, "final String[][] categories", "final AdaptationTable categoryTable")
    rows = re.findall(r'\{\s*"([A-Z_]+)"\s*,\s*"[^"]+"\s*,\s*"[^"]+"\s*,\s*"(\d+)"\s*\}', category_array)
    expected = {
        "AI", "BUSINESS_APPS", "CLOUD_STORAGE", "DATA_PLATFORMS", "DATABASE", "MESSAGING",
        "REFERENCE_DATA", "CORPORATE_REGISTRIES", "RISK_COMPLIANCE", "GENERIC_INTEGRATION",
    }
    codes = [code for code, _ in rows]
    orders = [int(order) for _, order in rows]
    check(set(codes) == expected, f"category seed mismatch: {sorted(set(codes) ^ expected)}")
    check(len(codes) == len(set(codes)), "category seed contains duplicate primary keys")
    check(orders == sorted(orders) and len(orders) == len(set(orders)),
          "category display order is not unique and ascending")
    check("lookupAdaptationByPrimaryKey" in migration and "PrimaryKey.parseString(category[0])" in migration,
          "category seed must skip records that already exist")

    # A dataset owned by another module must stop installation before any seed/migration call.
    install = block(installer, "private static void runInstall(", "/** Default trust")
    foreign = block(install, "if (dataset != null && !ConnectorsInstaller.belongsTo", "if (home == null)")
    check("return;" in foreign, "foreign-module guard must stop installation")
    check("Existing data was preserved" in foreign, "foreign-module diagnostic must state preservation")
    check(not re.search(r"doDelete|deleteHome|closeHome|doDeleteOccurrence", foreign),
          "foreign-module guard contains a destructive repository call")
    check(install.index("!ConnectorsInstaller.belongsTo") < install.index("ConnectorsInstaller.seed(pSession"),
          "foreign-module ownership check must run before seeding")
    ownership = block(installer, "static boolean belongsTo(", "private static boolean isEmpty")
    catch_body = ownership[ownership.index("catch (final RuntimeException ex)"):]
    check("return false;" in catch_body,
          "an unreadable schema location must fail closed to protect possibly foreign data")

    # Existing records and customer-owned values are protected by presence/null checks.
    connector_missing = block(installer, "private static boolean connectorIfMissing", "The catalogue added")
    check("lookupAdaptationByPrimaryKey" in connector_missing and "return false;" in connector_missing,
          "connector seed helper must skip existing connector records")
    check("row.get(ConnectorsPaths.Connector.TRUST) == null" in migration,
          "trust migration must only initialize a blank value")
    check("currentLogo == null || replaceGeneratedEbxLogo" in migration,
          "logo migration must preserve customer logos except the known generated EBX placeholder")
    check("row.get(ConnectorsPaths.Connector.CATEGORY) == null" in migration,
          "category migration must only initialize a blank value")
    check("A_RENSEIGNER" in migration and "TO_BE_SET" in migration,
          "legacy placeholder migration is missing")

    # Bundles must be duplicate-free and expose exactly matching English/French keys.
    english = properties(RESOURCES / "Messages_en.properties")
    french = properties(RESOURCES / "Messages_fr.properties")
    check(set(english) == set(french),
          f"localization mismatch: EN-only={sorted(set(english)-set(french))}; FR-only={sorted(set(french)-set(english))}")

    # Both installation guides must describe additive upgrades and foreign-data preservation.
    readme_en = " ".join((MODULE / "README.md").read_text(encoding="utf-8").lower().split())
    readme_fr = " ".join((MODULE / "README.fr.md").read_text(encoding="utf-8").lower().split())
    check("additive and idempotent" in readme_en and "without deleting anything" in readme_en,
          "English README does not document safe additive upgrades")
    check("sans modifier ni" in readme_fr and "migration destructive automatiquement" in readme_fr,
          "French README does not document preservation of foreign-module data")

    # Production remains a standalone module rather than importing FastTrack implementation classes.
    forbidden = ("com.on.ebx.fasttrack", "com.tibco.ebx.fasttrack", "ebx.ps.fasttrack")
    offenders = []
    for path in (MODULE / "src/main").rglob("*"):
        if path.is_file() and path.suffix in {".java", ".xml", ".xsd"}:
            text = path.read_text(encoding="utf-8", errors="replace").lower()
            if any(token in text for token in forbidden):
                offenders.append(str(path.relative_to(MODULE)))
    check(not offenders, f"production module depends on FastTrack implementation: {offenders}")

    module_xml = (MODULE / "src/main/webapp/WEB-INF/ebx/module.xml").read_text(encoding="utf-8")
    check("<name>ebx-connectors</name>" in module_xml, "module.xml does not declare ebx-connectors")

    # Catalog-origin records must stay inside the modern workspace. Crossing through a legacy
    # /ebx/ manager component logs the user out when the native record Close action runs.
    catalog = CATALOG_SOURCE.read_text(encoding="utf-8")
    check('final String recordURI = appendQuery(nativeView,' in catalog,
          "catalog records must open from the native /ebx-ui table URL")
    check(r'target=\"_top\"' in catalog,
          "catalog cards must escape the legacy service iframe")
    check("catalogComponent.getURIWithParameters()" not in catalog,
          "catalog record return target must not use a legacy /ebx/ component URL")
    check('"workspaceDisplay=" + queryValue("record:./id=\'"' in catalog,
          "catalog cards must open records with the modern workspaceDisplay route")
    check("recordComponent.getURIWithParameters()" not in catalog,
          "catalog cards must not cross into a legacy /ebx/ record component")
    check("tableView=" not in catalog,
          "catalog cards must not assert a saved table view exists (\"The view [ConnectorView] was not found\", 2026-09-11)")

    print(f"PASS: upgrade safety, {len(codes)} unique category seeds, and {len(english)} EN/FR message keys verified")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
