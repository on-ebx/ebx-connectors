#!/usr/bin/env python3
"""Self-sufficiency check of ebx-connectors.war (audit X3-07, 2026-11-12). Standard library and the JDK's jdeps only.

    tools/check_war_selfcontained.py <war> [--pinned FILE] [--allow FILE] [--report]

Why: a standard ON EBX 6.3 server has, in its lib/, only ebx.jar, the database driver and jakarta.mail. It has no Jackson (ON EBX keeps a relocated private
copy), no SLF4J, no commons-*. The jars of a developer's FastTrack lib/ hide that: the war compiled and passed its tests there, and died on a clean server with
NoClassDefFoundError com/fasterxml/jackson/databind/JsonNode. This check asks, for every class of WEB-INF/classes and of every jar of WEB-INF/lib, whether each
class it references is found in the war, in the JDK, in Tomcat's lib/ or in a standard ebx.jar. A reference found nowhere is a gap.

Gaps are accepted only through the allow file (tools/war-optional-refs.txt): one line per accepted reference, with the reason it is optional. A line without a
reason is an error; a line that matches nothing is reported as stale. The check also compares the Jackson and SLF4J jars INSIDE the war, byte for byte, with
runtime-libs/PINNED-WAR-JARS.txt.

Environment:
  EBX_STANDARD_JAR  a standard ON EBX ebx.jar (<distribution>/ebx.software/lib/ebx.jar). Without it the check says so and falls back to the list of package
                    prefixes ON EBX supplies (PROVIDED_PREFIXES below): weaker, because a class that is not really in ebx.jar is then taken as present.
  TOMCAT_LIB_DIR    Tomcat's lib/ folder (servlet-api.jar...). Without it the Jakarta EE prefixes of PROVIDED_PREFIXES stand for it.
  JAVA_HOME         the JDK whose jdeps runs (default: the one on PATH).
Exit codes: 0 self-sufficient; 1 gaps or a pinned jar that differs; 2 the check itself could not run.
"""
import argparse
import collections
import fnmatch
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# What ON EBX / Tomcat supply, used ONLY when no real ebx.jar / Tomcat lib is given. The add-ons (com.orchestranetworks.addon.) are deliberately excluded:
# they are optional jars of the add-on distribution and the module keeps every use of them behind a LinkageError guard (allow file).
# ebx.jar 6.3.0-gp also carries javax.annotation (JSR-305), jakarta.xml.*, jakarta.json.* and javax.json.* (read with unzip -l on 2026-11-12); jakarta.mail is the
# standard distribution's lib-mail/jakarta.mail jar, not ebx.jar.
EBX_PREFIXES = ("com.orchestranetworks.", "com.onwbp.", "jakarta.ws.rs.", "jakarta.mail.", "javax.annotation.", "jakarta.xml.", "jakarta.json.", "javax.json.")
TOMCAT_PREFIXES = ("jakarta.servlet.", "jakarta.websocket.", "jakarta.el.", "jakarta.annotation.")
NOT_PROVIDED = ("com.orchestranetworks.addon.",)


def fail(message, code=1):
    sys.stderr.write(message.rstrip("\n") + "\n")
    sys.exit(code)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def load_pinned(path):
    pinned = collections.OrderedDict()
    for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2 or not re.fullmatch(r"[0-9a-f]{64}", parts[0]):
            fail("CONN-WAR-1: %s line %d is not '<sha256>  <jar>': %s" % (path, number, line), 2)
        pinned[parts[1]] = parts[0]
    return pinned


def check_pinned(lib_dir, pinned):
    """The problems of the pinned jars inside the extracted war, as messages."""
    problems = []
    for jar, wanted in pinned.items():
        found = lib_dir / jar
        if not found.is_file():
            problems.append("CONN-WAR-2: WEB-INF/lib/%s is missing from the war. A standard ON EBX lib/ does not supply it. Add it to ebx-connectors/runtime-libs/ "
                            "(copy from the lib/ of the FastTrack server, see runtime-libs/README.md) and rebuild." % jar)
        elif sha256(found) != wanted:
            problems.append("CONN-WAR-3: WEB-INF/lib/%s has sha256 %s, runtime-libs/PINNED-WAR-JARS.txt pins %s. Restore the pinned jar in runtime-libs/ "
                            "(git checkout -- ebx-connectors/runtime-libs/%s), or, if the version is to change on purpose, update PINNED-WAR-JARS.txt, SHA256SUMS, "
                            "MANIFEST.txt and THIRD-PARTY-NOTICES.md together." % (jar, sha256(found), wanted, jar))
    return problems


def load_allow(path):
    entries = []
    if not path:
        return entries
    for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|", 2)]
        if len(parts) != 3 or not all(parts):
            fail("CONN-WAR-4: %s line %d needs '<referencing jar glob> | <missing class prefix>[, ...] | <reason>', each non-empty: %s" % (path, number, line), 2)
        entries.append({"jar": parts[0], "jars": [j.strip() for j in parts[0].split(",") if j.strip()], "prefixes": [p.strip() for p in parts[1].split(",") if p.strip()], "reason": parts[2], "line": number, "hits": 0})
    return entries


def strip_module_info(source, target):
    """Copy of the jar without module-info.class: at run time the war's jars are on a class path, where jdeps would otherwise require their modules."""
    with zipfile.ZipFile(source) as zin, zipfile.ZipFile(target, "w", zipfile.ZIP_STORED) as zout:
        for item in zin.infolist():
            if item.filename == "module-info.class" or item.filename.endswith("/module-info.class"):
                continue
            zout.writestr(item, zin.read(item.filename))


def jdeps_binary():
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / "jdeps" if home else None
    if candidate and candidate.is_file():
        return str(candidate)
    found = shutil.which("jdeps")
    if not found:
        fail("CONN-WAR-5: jdeps (a JDK tool) was not found. Set JAVA_HOME to a JDK 17 or later.", 2)
    return found


LINE = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+(.*?)\s*$")


def missing_references(war_root, work, extra_classpath):
    """{referencing archive: {missing class: set(referencing classes)}} as jdeps reports them."""
    lib_dir = war_root / "WEB-INF" / "lib"
    stripped = work / "jars"
    stripped.mkdir()
    jars = []
    for jar in sorted(lib_dir.glob("*.jar")):
        target = stripped / jar.name
        try:
            strip_module_info(jar, target)
        except zipfile.BadZipFile:
            fail("CONN-WAR-7: WEB-INF/lib/%s is not a readable jar. Rebuild the war from a clean runtime-libs/ (git status, shasum -a 256 -c SHA256SUMS)." % jar.name)
        jars.append(str(target))
    classpath = os.pathsep.join(jars + extra_classpath)
    classes = war_root / "WEB-INF" / "classes"
    command = [jdeps_binary(), "-verbose:class", "--multi-release", "21", "--no-recursive"] + (["--class-path", classpath] if classpath else []) + [str(classes)] + jars
    done = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, errors="replace")
    if done.returncode != 0:
        fail("CONN-WAR-5: jdeps failed (exit %d):\n%s" % (done.returncode, done.stderr[-2000:]), 2)
    gaps = collections.defaultdict(lambda: collections.defaultdict(set))
    current = None
    for line in done.stdout.splitlines():
        if not line.startswith(" "):
            current = line.split(" -> ")[0].strip()
            continue
        match = LINE.match(line)
        if match and match.group(3) == "not found":
            gaps[current][match.group(2)].add(match.group(1).split("$")[0])
    return gaps


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("war")
    parser.add_argument("--pinned", help="runtime-libs/PINNED-WAR-JARS.txt")
    parser.add_argument("--allow", help="the file of accepted optional references")
    parser.add_argument("--report", action="store_true", help="print every gap, accepted ones included, and exit 0")
    args = parser.parse_args(argv)

    war = Path(args.war)
    if not war.is_file():
        fail("CONN-WAR-5: no war at %s" % war, 2)
    allow = load_allow(args.allow)
    ebx_jar = os.environ.get("EBX_STANDARD_JAR", "")
    tomcat_dir = os.environ.get("TOMCAT_LIB_DIR", "")
    extra = []
    notes = []
    if ebx_jar:
        if not Path(ebx_jar).is_file():
            fail("CONN-WAR-5: EBX_STANDARD_JAR=%s is not a file." % ebx_jar, 2)
        extra.append(ebx_jar)
        notes.append("ebx.jar: %s" % ebx_jar)
    else:
        notes.append("EBX_STANDARD_JAR is not set: ON EBX classes are taken from the list of package prefixes ON EBX supplies (weaker check; set it to the ebx.jar of "
                     "a standard distribution, <distribution>/ebx.software/lib/ebx.jar)")
    if tomcat_dir:
        jars = sorted(str(p) for p in Path(tomcat_dir).glob("*.jar"))
        if not jars:
            fail("CONN-WAR-5: TOMCAT_LIB_DIR=%s holds no jar." % tomcat_dir, 2)
        extra.extend(jars)
        notes.append("Tomcat lib: %s" % tomcat_dir)
    else:
        notes.append("TOMCAT_LIB_DIR is not set: the Jakarta EE APIs are taken from the prefix list")
    for note in notes:
        print("self-check: " + note)

    with tempfile.TemporaryDirectory(prefix="war-selfcheck.") as tmp:
        work = Path(tmp)
        root = work / "war"
        with zipfile.ZipFile(war) as archive:
            archive.extractall(root)
        problems = []
        if args.pinned:
            problems.extend(check_pinned(root / "WEB-INF" / "lib", load_pinned(args.pinned)))
        gaps = missing_references(root, work, extra)

    provided = (() if ebx_jar else EBX_PREFIXES) + (() if tomcat_dir else TOMCAT_PREFIXES)
    unaccepted = collections.defaultdict(lambda: collections.defaultdict(set))
    accepted = 0
    for archive in sorted(gaps):
        for target in sorted(gaps[archive]):
            if provided and target.startswith(provided) and not target.startswith(NOT_PROVIDED):
                continue
            entry = next((e for e in allow if any(fnmatch.fnmatch(archive, g) for g in e["jars"]) and any(target.startswith(p) for p in e["prefixes"])), None)
            if entry is not None:
                entry["hits"] += 1
                accepted += 1
                if args.report:
                    print("  accepted  %-40s %s  (%s)" % (archive, target, entry["reason"]))
                continue
            unaccepted[archive][target] = gaps[archive][target]
    for entry in allow:
        if entry["hits"] == 0:
            print("self-check: stale allow entry (line %d, %s | %s): nothing matches it any more, remove it" % (entry["line"], entry["jar"], ", ".join(entry["prefixes"])))

    total = sum(len(v) for v in unaccepted.values())
    for message in problems:
        sys.stderr.write(message + "\n")
    if total:
        sys.stderr.write("CONN-WAR-6: %d class(es) referenced by the war are found nowhere (not in the war, the JDK, Tomcat or a standard ebx.jar):\n" % total)
        for archive in sorted(unaccepted):
            sys.stderr.write("  %s\n" % archive)
            roots = collections.defaultdict(list)
            for target in sorted(unaccepted[archive]):
                roots[target.rsplit(".", 1)[0] if "." in target else target].append(target)
            for package in sorted(roots):
                example = sorted(unaccepted[archive][roots[package][0]])[0]
                sys.stderr.write("    %s: %d class(es), e.g. %s, used by %s\n" % (package, len(roots[package]), roots[package][0], example))
        sys.stderr.write("Fix: add the library to runtime-libs/ (if its absence breaks start-up or a base function), or, if the reference is optional, add a line with its reason to "
                         "tools/war-optional-refs.txt and document the connector it affects in docs/OPERATIONS.md section 1.1.\n")
    if problems or total:
        return 0 if args.report else 1
    print("self-check: PASS - %d accepted optional reference(s); every other class the war references is in the war, the JDK, Tomcat or ON EBX" % accepted)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
