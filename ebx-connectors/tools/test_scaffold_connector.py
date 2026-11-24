#!/usr/bin/env python3
"""Deterministic, dependency-free contract tests for scaffold_connector.py."""

import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("scaffold_connector.py")


def run(root, *extra):
    command = [sys.executable, str(SCRIPT), "--root", str(root), "--type", "sample-api",
               "--label", "Sample API", "--category", "GENERIC_INTEGRATION"] + list(extra)
    return subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def digest(root):
    result = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        result.update(str(path.relative_to(root)).encode("utf-8"))
        result.update(path.read_bytes())
    return result.hexdigest()


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    with tempfile.TemporaryDirectory(prefix="connector-scaffold-") as directory:
        root = Path(directory)
        preview = run(root, "--check")
        check(preview.returncode == 0, preview.stderr)
        check(len(preview.stdout.strip().splitlines()) == 6, "check must list six artifacts")
        check(not any(root.iterdir()), "check mode wrote files")
        created = run(root)
        check(created.returncode == 0, created.stderr)
        first = digest(root)
        with tempfile.TemporaryDirectory(prefix="connector-scaffold-repeat-") as repeat_directory:
            repeat_root = Path(repeat_directory)
            repeated = run(repeat_root)
            check(repeated.returncode == 0, repeated.stderr)
            check(first == digest(repeat_root), "identical inputs produced different artifacts")
        check((root / "docs/SAMPLE-API-USER-GUIDE.html").read_text().find('href="USER-GUIDE.html"') >= 0,
              "guide does not link to the original guide")
        source = (root / "src/main/java/com/on/ebx/connect/connectors/SampleAPIConnector.java").read_text()
        check("ConnectorAvailability.UNAVAILABLE" in source, "scaffold is not safely unavailable")
        check("ConnectorRegistry" not in source, "scaffold attempted registration")
        refused = run(root)
        check(refused.returncode != 0 and "refusing to overwrite" in refused.stderr,
              "existing output was overwritten")
        check(first == digest(root), "refused run changed generated files")
        invalid = subprocess.run([sys.executable, str(SCRIPT), "--root", str(root), "--type", "../bad",
                                  "--label", "Bad", "--category", "DATABASE"], text=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        check(invalid.returncode != 0 and "kebab-case" in invalid.stderr, "unsafe type accepted")
    print("PASS: deterministic preview, six artifacts, OG-guide link, unavailable default, validation and no-overwrite safety")
    return 0


if __name__ == "__main__":
    sys.exit(main())
