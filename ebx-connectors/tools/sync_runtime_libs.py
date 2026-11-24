#!/usr/bin/env python3
"""Puts the jars of ebx-connectors/runtime-libs/ into <workspace>/ebx-connectors/src/main/webapp/WEB-INF/lib (lot 1.4.1, audit X3-08). Standard library only.

    ebx-connectors/tools/sync_runtime_libs.py --source <runtime-libs> --target <WEB-INF/lib> [--backup DIR] [--classpath FILE] [--dry-run]

Why: the module compiled and tested on the Jackson of runtime-libs/ (2.18) must not run on another one. The war that tools/package.sh builds carries those jars; the
installation into a FastTrack workspace (tools/install.sh) mounts the workspace's WEB-INF/lib instead, which used to be kept as it was. A jar is identified by its
ARTIFACT: the file name without its version (jackson-core-2.14.2.jar and jackson-core-2.18.12.jar are both "jackson-core"; netty-transport-native-epoll-4.1.137.Final-linux-x86_64.jar
is "netty-transport-native-epoll-linux-x86_64"; a name with no version, snappy-java.jar, is its own artifact).

For every jar of runtime-libs/:
  ADD      the target has no jar of that artifact: it is copied;
  REPLACE  the target has the artifact under another file name (another version) or the same name with other bytes: the old file is removed, the new one copied;
  SAME     the target already has this exact file (same sha256): nothing is done.
A jar of the target whose artifact is not in runtime-libs/ is LEFT as it is and named (ebx-connectors-extension-sample.jar, a customer's driver...): this tool never deletes a jar
it does not know. With --backup DIR, every jar that a REPLACE removes or overwrites is first copied into DIR (a rollback is then a copy back). With --classpath FILE (the .classpath of the installed Eclipse project) a kind="lib" entry for jackson-core, -databind and -annotations of the source is put BEFORE the entry of the project /ebx-ps-fasttrack-server (Eclipse resolves in order, and that project exports the Jackson 2.14.2 of its lib/, which lacks APIs the code uses), lib entries of another version of those artifacts are removed, nothing else is touched; idempotent; the first change leaves FILE.bak-<date>; a missing FILE is not created. Every file touched or left is printed. With --dry-run nothing is written. Exit 0 when the target holds every jar of the source afterwards, 1 when not, 2 on a usage error.
"""
import argparse
import hashlib
import re
import shutil
import sys
from pathlib import Path

NAME = re.compile(r"^(?P<artifact>.+?)-(?P<version>\d[0-9A-Za-z.]*)(?P<classifier>-.*)?\.jar$")


def artifact(file_name):
    """The artifact a jar file name stands for: name and classifier without the version."""
    m = NAME.match(file_name)
    if m is None:
        return file_name[:-4] if file_name.endswith(".jar") else file_name
    return m.group("artifact") + (m.group("classifier") or "")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def plan(source, target):
    """(actions, left): actions are (verb, source jar or None, [target jars to remove]); left are the target jars of no known artifact."""
    wanted = {}
    for jar in sorted(source.glob("*.jar")):
        key = artifact(jar.name)
        if key in wanted:
            raise SystemExit("two jars of runtime-libs/ stand for the artifact %s: %s and %s" % (key, wanted[key].name, jar.name))
        wanted[key] = jar
    present = {}
    if target.is_dir():
        for jar in sorted(target.glob("*.jar")):
            present.setdefault(artifact(jar.name), []).append(jar)
    actions = []
    for key, jar in wanted.items():
        have = present.get(key, [])
        if not have:
            actions.append(("ADD", jar, []))
        elif len(have) == 1 and have[0].name == jar.name and sha256(have[0]) == sha256(jar):
            actions.append(("SAME", jar, []))
        else:
            actions.append(("REPLACE", jar, have))
    left = [jar for key, jars in sorted(present.items()) if key not in wanted for jar in jars]
    return actions, left


# the jars the module's CODE needs at compile time and that a FastTrack server project exports in another version (its lib/ holds Jackson 2.14.2).
# jackson-datatype-jsr310 and jackson-dataformat-yaml are reached at run time only (azure-core, reflection): Eclipse does not need them.
COMPILE_ARTIFACTS = ("jackson-core", "jackson-databind", "jackson-annotations")
LIB_ENTRY = re.compile(r'^\s*<classpathentry\b[^>]*\bkind="lib"[^>]*\bpath="([^"]+)"')
SERVER_ENTRY = re.compile(r'<classpathentry\b[^>]*\bkind="src"[^>]*\bpath="/ebx-ps-fasttrack-server"')


def amend_classpath(classpath, source, lib_dir_rel, dry_run):
    """Puts a kind="lib" entry for each COMPILE_ARTIFACTS jar of the source BEFORE the project entry of the FastTrack server (Eclipse resolves in order),
    removes the lib entries of another version of the same artifact, touches nothing else. Returns (changed, message)."""
    if not classpath.is_file():
        return False, "no .classpath in the workspace project: nothing created, nothing changed (Eclipse builds with its own settings)"
    wanted = ['\t<classpathentry kind="lib" path="%s/%s"/>' % (lib_dir_rel, jar.name) for jar in sorted(source.glob("*.jar")) if artifact(jar.name) in COMPILE_ARTIFACTS]
    if not wanted:
        return False, "runtime-libs holds no Jackson jar: .classpath left as it is"
    names = [re.search(r'path="[^"]*/([^"/]+)"', w).group(1) for w in wanted]
    lines = classpath.read_text(encoding="utf-8").split("\n")
    kept = []
    for line in lines:
        m = LIB_ENTRY.match(line)
        if m and artifact(Path(m.group(1)).name) in COMPILE_ARTIFACTS:
            continue
        kept.append(line)
    at = next((i for i, line in enumerate(kept) if SERVER_ENTRY.search(line)), None)
    if at is None:
        at = next((i for i, line in enumerate(kept) if 'kind="output"' in line), len(kept))
    new = kept[:at] + wanted + kept[at:]
    if new == lines:
        return False, ".classpath already puts %s before the server project (nothing to do)" % ", ".join(names)
    if not dry_run:
        backup = classpath.with_name(classpath.name + ".bak-" + __import__("datetime").date.today().strftime("%Y%m%d"))
        if not backup.exists():
            shutil.copyfile(str(classpath), str(backup))
        classpath.write_text("\n".join(new), encoding="utf-8")
    return True, "%s .classpath: %d Jackson lib entr%s before the server project (%s)" % ("WOULD AMEND" if dry_run else "amended", len(wanted), "y" if len(wanted) == 1 else "ies", ", ".join(names))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--source", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--backup", help="folder that receives a copy of every jar a REPLACE removes or overwrites")
    parser.add_argument("--classpath", help="the Eclipse .classpath of the installed project, to amend (Jackson lib entries before the server project)")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    source, target = Path(args.source), Path(args.target)
    if not source.is_dir() or not list(source.glob("*.jar")):
        print("sync_runtime_libs: %s holds no jar" % source, file=sys.stderr)
        return 2
    actions, left = plan(source, target)
    verbs = {"ADD": [], "REPLACE": [], "SAME": []}
    for verb, jar, old in actions:
        verbs[verb].append((jar, old))
    mode = "WOULD " if args.dry_run else ""
    print("runtime jars -> %s%s" % (target, "  (dry run: nothing is written)" if args.dry_run else ""))
    for jar, _ in verbs["ADD"]:
        print("  %sADD      %s" % (mode, jar.name))
    for jar, old in verbs["REPLACE"]:
        print("  %sREPLACE  %s  <-  %s" % (mode, jar.name, ", ".join(o.name + ("" if o.name != jar.name else " (other bytes)") for o in old)))
    print("  %d jar(s) already identical (SAME)" % len(verbs["SAME"]))
    for jar in left:
        print("  LEFT     %s  (not a jar of runtime-libs/: not touched)" % jar.name)
    if not left:
        print("  LEFT     none")
    if args.classpath:
        _, message = amend_classpath(Path(args.classpath), source, "src/main/webapp/WEB-INF/lib", True) if args.dry_run else (None, None)
        if message:
            print("  " + message)
    if args.dry_run:
        return 0
    target.mkdir(parents=True, exist_ok=True)
    backup = Path(args.backup) if args.backup else None
    for verb, jar, old in actions:
        if verb == "SAME":
            continue
        for o in old:
            if backup is not None:
                backup.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(str(o), str(backup / o.name))
            if o.name != jar.name:
                o.unlink()
        shutil.copyfile(str(jar), str(target / jar.name))
    missing = [jar.name for _, jar, _ in actions if not (target / jar.name).is_file() or sha256(target / jar.name) != sha256(jar)]
    if missing:
        print("sync_runtime_libs: not identical to runtime-libs/ after the copy: %s" % ", ".join(missing), file=sys.stderr)
        return 1
    if args.classpath:
        print("  " + amend_classpath(Path(args.classpath), source, "src/main/webapp/WEB-INF/lib", False)[1])
    if backup is not None and backup.is_dir():
        print("  the jars that were replaced are kept in %s" % backup)
    print("  done: %d added, %d replaced, %d unchanged, %d left; WEB-INF/lib holds every jar of runtime-libs/ byte for byte" % (
        len(verbs["ADD"]), len(verbs["REPLACE"]), len(verbs["SAME"]), len(left)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
