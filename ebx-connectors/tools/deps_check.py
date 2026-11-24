#!/usr/bin/env python3
"""runtime-libs/ inventory, library-family consistency and OSV advisories (audit C2-15). Standard library only."""
import json
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

OSV = "https://api.osv.dev/v1/querybatch"


def coordinates(jar):
    """(groupId, artifactId, version) from the pom.properties inside the jar, else from the file name; group may be None."""
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith("META-INF/maven/") and name.endswith("/pom.properties"):
                props = dict(line.split("=", 1) for line in z.read(name).decode("utf-8", "replace").splitlines() if "=" in line and not line.startswith("#"))
                if {"groupId", "artifactId", "version"} <= set(props):
                    return props["groupId"].strip(), props["artifactId"].strip(), props["version"].strip()
        try:
            manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace").replace("\r\n ", "").replace("\n ", "")
        except KeyError:
            manifest = ""
    stem = jar.stem
    m = re.match(r"^(.*?)-(\d[\w.\-]*)$", stem)
    artifact, version = (m.group(1), m.group(2)) if m else (stem, None)
    if version is None:
        for key in ("Bundle-Version", "Implementation-Version"):
            mm = re.search(r"^%s: (.+)$" % key, manifest, re.M)
            if mm:
                version = mm.group(1).strip()
                break
    return GROUPS.get(artifact), artifact, version or "?"


# jars whose META-INF carries no pom.properties: the Maven group, so that OSV can be asked about them
GROUPS = {"lz4-java": "at.yawk.lz4", "snappy-java": "org.xerial.snappy", "zstd-jni": "com.github.luben", "postgresql": "org.postgresql", "h2": "com.h2database",
          "mssql-jdbc": "com.microsoft.sqlserver", "ojdbc8": "com.oracle.database.jdbc", "kafka-clients": "org.apache.kafka", "jsch": "com.github.mwiede",
          "reactor-core": "io.projectreactor", "reactor-netty-core": "io.projectreactor.netty", "reactor-netty-http": "io.projectreactor.netty",
          "reactive-streams": "org.reactivestreams"}


def netty_line(version):
    m = re.match(r"^(\d+\.\d+)\.", version)
    return m.group(1) if m else version


def load_known(path):
    known = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                key, _, why = line.partition("|")
                known[key.strip()] = why.strip()
    return known


def osv_query(items):
    body = json.dumps({"queries": [{"package": {"name": "%s:%s" % (g, a), "ecosystem": "Maven"}, "version": v} for g, a, v in items]}).encode()
    request = urllib.request.Request(OSV, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode())["results"]
    except (urllib.error.URLError, OSError):
        # a Python without the system's CA certificates (macOS): curl has them
        if shutil.which("curl") is None:
            raise
        done = subprocess.run(["curl", "-sS", "--fail", "--max-time", "30", "-X", "POST", "-H", "Content-Type: application/json", "--data-binary", "@-", OSV],
                              input=body, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if done.returncode != 0:
            raise OSError(done.stderr.decode("utf-8", "replace").strip() or "curl failed")
        return json.loads(done.stdout.decode())["results"]


def main(argv):
    libs = Path(argv[0])
    flags = set(argv[1:])
    known = load_known(libs / "DEPS-KNOWN.txt")
    rows = []
    for jar in sorted(libs.glob("*.jar")):
        group, artifact, version = coordinates(jar)
        rows.append((jar.name, group, artifact, version))
    print("%-62s %-34s %s" % ("jar", "coordinates", "version"))
    for jar, group, artifact, version in rows:
        print("%-62s %-34s %s" % (jar, "%s:%s" % (group or "?", artifact), version))
    findings = []
    # one line per family: netty (tcnative and the native transports carry their own numbering rules, 4.1.x or 4.2.x like the rest)
    lines = {}
    for jar, group, artifact, version in rows:
        if group == "io.netty" and not artifact.startswith("netty-tcnative"):
            lines.setdefault(netty_line(version), []).append(jar)
    if len(lines) > 1:
        findings.append(("netty-mixed-lines", "netty is on %d lines (%s); the Azure SDK of this module expects ONE (see azure-core-http-netty's pom)" % (
            len(lines), ", ".join("%s: %d jars" % (k, len(v)) for k, v in sorted(lines.items())))))
    for jar, group, artifact, version in rows:
        if artifact == "lz4-java" and group in (None, "org.lz4"):
            findings.append(("lz4-java-old", "%s is the abandoned org.lz4:lz4-java line (its last release, 1.8.1, only relocates): take at.yawk.lz4:lz4-java" % jar))
    # advisories
    if "--offline" not in flags:
        queryable = [(g, a, v) for _, g, a, v in rows if g and v != "?"]
        try:
            results = osv_query(queryable)
            advisories = 0
            for (g, a, v), result in zip(queryable, results):
                ids = [vuln["id"] for vuln in result.get("vulns", [])]
                if ids:
                    advisories += len(ids)
                    findings.append(("osv:%s:%s:%s" % (g, a, v), "%s:%s %s: %s" % (g, a, v, ", ".join(ids[:8]) + (" ..." if len(ids) > 8 else ""))))
            print("\nOSV: %d jar(s) checked, %d advisory id(s)" % (len(queryable), advisories))
        except (urllib.error.URLError, OSError, ValueError, KeyError) as ex:
            print("\nOSV: not reached (%s): advisories NOT checked" % ex)
            if "--require-osv" in flags:
                findings.append(("osv-unreachable", "OSV could not be reached and --require-osv was given"))
    open_findings = []
    print("")
    for key, text in findings:
        if key in known:
            print("KNOWN   %s -- %s [%s]" % (key, text, known[key]))
        else:
            open_findings.append((key, text))
            print("FINDING %s -- %s" % (key, text))
    for key in sorted(set(known) - set(k for k, _ in findings)):
        if "--offline" not in flags or not key.startswith("osv:"):
            print("STALE   %s is in DEPS-KNOWN.txt but no longer found: remove the line" % key)
    if open_findings and "--report" not in flags:
        print("\nFAIL: %d finding(s) not accepted in runtime-libs/DEPS-KNOWN.txt" % len(open_findings))
        return 1
    print("\nOK" if not open_findings else "\nreport only")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
