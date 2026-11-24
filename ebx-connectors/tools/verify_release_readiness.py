#!/usr/bin/env python3
"""Deterministic, repository-only EBX Connectors release-readiness verifier."""

import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(repo, *args):
    try:
        return subprocess.check_output(["git", "-C", str(repo)] + list(args), text=True,
                                       stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def normalized(value):
    return re.sub(r"[^a-z0-9]", "", value.lower())


def display_path(path, repo):
    try:
        return str(path.relative_to(repo))
    except ValueError:
        return str(path)


def result(check, status, detail, **evidence):
    item = {"check": check, "detail": detail, "status": status}
    if evidence:
        item["evidence"] = evidence
    return item


def verify(args):
    repo = Path(args.repo).resolve()
    manifest_path = (repo / args.coverage).resolve()
    war_path = (repo / args.war).resolve()
    coverage = json.loads(manifest_path.read_text(encoding="utf-8"))
    catalog = coverage.get("catalog", [])
    connector_types = sorted(item["connectorType"] for item in catalog)
    checks = []

    duplicates = sorted(name for name in set(connector_types) if connector_types.count(name) > 1)
    checks.append(result("catalog-count", "PASS" if not duplicates else "FAIL",
                         "%d unique connector types declared" % len(set(connector_types)),
                         declared=len(connector_types), duplicates=duplicates))

    branch = git(repo, "rev-parse", "--abbrev-ref", "HEAD")
    commit = git(repo, "rev-parse", "HEAD")
    branch_ok = not args.branch or branch == args.branch
    commit_ok = not args.commit or commit == args.commit
    checks.append(result("source-provenance", "PASS" if branch_ok and commit_ok else "FAIL",
                         "repository branch and commit match supplied release inputs",
                         actualBranch=branch, actualCommit=commit,
                         expectedBranch=args.branch or branch, expectedCommit=args.commit or commit))

    war_hash = ""
    war_entries = set()
    if war_path.is_file():
        war_hash = sha256(war_path)
        try:
            with zipfile.ZipFile(str(war_path)) as archive:
                war_entries = set(archive.namelist())
            war_ok = not args.sha256 or war_hash == args.sha256.lower()
            checks.append(result("war-provenance", "PASS" if war_ok else "FAIL",
                                 "WAR is readable and its SHA-256 was calculated",
                                 path=display_path(war_path, repo), sha256=war_hash,
                                 expectedSha256=args.sha256.lower() if args.sha256 else war_hash))
        except zipfile.BadZipFile:
            checks.append(result("war-provenance", "FAIL", "WAR is not a valid ZIP archive",
                                 path=display_path(war_path, repo)))
    else:
        checks.append(result("war-provenance", "FAIL", "built WAR is missing",
                             path=display_path(war_path, repo)))

    missing_classes = []
    for item in catalog:
        entry = "WEB-INF/classes/%s.class" % item["implementation"].replace(".", "/")
        if entry not in war_entries:
            missing_classes.append(item["connectorType"])
    checks.append(result("war-catalog-classes", "PASS" if not missing_classes else "FAIL",
                         "every coverage implementation is packaged in the WAR",
                         missingConnectorTypes=missing_classes))

    installer = repo / "src/main/java/com/on/ebx/connect/ebx/ConnectorsInstaller.java"
    installer_text = installer.read_text(encoding="utf-8") if installer.is_file() else ""
    missing_seeds = [name for name in connector_types if ('"%s"' % name) not in installer_text]
    checks.append(result("additive-seeds", "PASS" if not missing_seeds else "FAIL",
                         "catalog types have an explicit additive installer seed literal",
                         missingConnectorTypes=missing_seeds))

    guide_files = sorted((repo / "docs").glob("*-USER-GUIDE.html"))
    guides = {normalized(path.name.replace("-USER-GUIDE.html", "")): path for path in guide_files
              if path.name != "USER-GUIDE.html"}
    asset_roots = [repo / "docs/images/connectors", repo / "src/main/webapp/www/common/images/connect/logos"]
    assets = {}
    for root in asset_roots:
        if root.is_dir():
            for path in root.iterdir():
                if path.is_file():
                    assets.setdefault(normalized(path.stem), []).append(path)
    guide_aliases = {"postgresql": "postgresql", "ftp": "ftpftps", "hadoopwebhdfs": "hadoopwebhdfs"}
    asset_aliases = {"hadoopwebhdfs": "hadoop", "sparksql": "spark"}
    missing_assets = []
    covered_guides = []
    for connector_type in connector_types:
        key = normalized(connector_type)
        guide_key = guide_aliases.get(key, key)
        if guide_key in guides:
            covered_guides.append(connector_type)
            asset_key = asset_aliases.get(key, key)
            if asset_key not in assets and guide_key not in assets:
                missing_assets.append(connector_type)
    checks.append(result("documented-connector-assets", "PASS" if not missing_assets else "FAIL",
                         "registered connector types with individual guides have connector artwork",
                         documentedConnectorTypes=covered_guides, missingAssetConnectorTypes=missing_assets))

    statuses = [item["status"] for item in checks]
    report = {
        "catalogCount": len(set(connector_types)),
        "checks": checks,
        "formatVersion": 1,
        "overallStatus": "FAIL" if "FAIL" in statuses else "PASS",
        "source": {"branch": branch, "commit": commit},
        "war": {"path": display_path(war_path, repo), "sha256": war_hash},
    }
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--coverage", default="connector-coverage.yaml")
    parser.add_argument("--war", default="target/baseline/ebx-connectors.war")
    parser.add_argument("--branch", help="expected source branch")
    parser.add_argument("--commit", help="expected full source commit")
    parser.add_argument("--sha256", help="expected lowercase WAR SHA-256")
    parser.add_argument("--output", help="write the canonical JSON report to this path")
    args = parser.parse_args(argv)
    report = verify(args)
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        Path(args.output).write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 0 if report["overallStatus"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
