#!/usr/bin/env python3
"""tools/deps_check.py (lot 1.4.1, audit X3-08): a jar OSV was never asked about is a finding; the Kafka client's version is read; an advisory is refused unless accepted. OSV is replaced by a stub: no network."""

import contextlib
import importlib.util
import io
import tempfile
from pathlib import Path
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[3] / "tools/deps_check.py"
SPEC = importlib.util.spec_from_file_location("deps_check", SCRIPT)
DEPS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPS)


def jar(folder, name, entries=None):
    with zipfile.ZipFile(folder / name, "w") as z:
        for entry, text in (entries or {"META-INF/x": "x"}).items():
            z.writestr(entry, text)


def pom(group, artifact, version):
    return {"META-INF/maven/%s/%s/pom.properties" % (group, artifact): "groupId=%s\nartifactId=%s\nversion=%s\n" % (group, artifact, version)}


def run(folder, advisories, *flags):
    """(exit code, output) of deps_check on a folder; advisories maps 'group:artifact:version' to a list of advisory ids."""
    def stub(items):
        return [{"vulns": [{"id": i} for i in advisories.get("%s:%s:%s" % item, [])]} for item in items]
    DEPS.osv_query = stub
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = DEPS.main([str(folder)] + list(flags))
    return code, out.getvalue()


class DepsCheckTest(unittest.TestCase):
    def test_kafka_version_is_read_from_the_client(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            jar(folder, "kafka-clients.jar", {"kafka/kafka-version.properties": "commitId=abc\nversion=3.9.2\n"})
            self.assertEqual(DEPS.coordinates(folder / "kafka-clients.jar"), ("org.apache.kafka", "kafka-clients", "3.9.2"))

    def test_a_jar_osv_was_never_asked_about_is_a_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            jar(folder, "mystery-1.0.jar")
            code, text = run(folder, {}, "--require-osv")
            self.assertEqual(code, 1, text)
            self.assertIn("FINDING osv-unqueryable:mystery-1.0.jar", text)
            code, text = run(folder, {}, "--offline")
            self.assertEqual(code, 0, text)

    def test_an_advisory_fails_unless_it_is_accepted_with_a_reason(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            jar(folder, "lib-1.0.jar", pom("org.example", "lib", "1.0"))
            code, text = run(folder, {"org.example:lib:1.0": ["GHSA-test"]}, "--require-osv")
            self.assertEqual(code, 1, text)
            self.assertIn("FINDING osv:org.example:lib:1.0", text)
            (folder / "DEPS-KNOWN.txt").write_text("osv:org.example:lib:1.0 | GHSA-test on OSV 2026-11-12: accepted for the test\n", encoding="utf-8")
            code, text = run(folder, {"org.example:lib:1.0": ["GHSA-test"]}, "--require-osv")
            self.assertEqual(code, 0, text)
            self.assertIn("KNOWN   osv:org.example:lib:1.0", text)

    def test_an_unreachable_osv_fails_with_require_osv(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            jar(folder, "lib-1.0.jar", pom("org.example", "lib", "1.0"))

            def down(items):
                raise OSError("no network")
            DEPS.osv_query = down
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = DEPS.main([str(folder), "--require-osv"])
            self.assertEqual(code, 1, out.getvalue())
            self.assertIn("osv-unreachable", out.getvalue())


if __name__ == "__main__":
    unittest.main()
