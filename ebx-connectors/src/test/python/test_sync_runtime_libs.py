#!/usr/bin/env python3
"""tools/sync_runtime_libs.py (lot 1.4.1, audit X3-08): the installation puts the jars of runtime-libs/ into the workspace's WEB-INF/lib, replaces another version of the same artifact, never touches a jar it does not know."""

import contextlib
import importlib.util
import io
import tempfile
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[3] / "tools/sync_runtime_libs.py"
SPEC = importlib.util.spec_from_file_location("sync_runtime_libs", SCRIPT)
SYNC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SYNC)


def put(folder, name, content):
    (folder / name).write_bytes(content)


def run(source, target, *flags):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = SYNC.main(["--source", str(source), "--target", str(target)] + list(flags))
    return code, out.getvalue()


class SyncRuntimeLibsTest(unittest.TestCase):
    def test_artifact_names(self):
        for name, key in [("jackson-core-2.14.2.jar", "jackson-core"), ("jackson-core-2.18.12.jar", "jackson-core"),
                          ("snappy-java.jar", "snappy-java"), ("snappy-java-1.1.10.5.jar", "snappy-java"),
                          ("netty-transport-native-epoll-4.1.137.Final-linux-x86_64.jar", "netty-transport-native-epoll-linux-x86_64"),
                          ("netty-tcnative-boringssl-static-2.0.81.Final.jar", "netty-tcnative-boringssl-static"),
                          ("netty-tcnative-boringssl-static-2.0.81.Final-osx-aarch_64.jar", "netty-tcnative-boringssl-static-osx-aarch_64"),
                          ("guava-33.7.2-jre.jar", "guava-jre"), ("cassandra-driver-core-3.11.5-shaded.jar", "cassandra-driver-core-shaded"),
                          ("azure-core-1.59.0.jar", "azure-core"), ("azure-core-amqp-2.12.1.jar", "azure-core-amqp"), ("ojdbc8.jar", "ojdbc8"),
                          ("jakarta.jms-api-2.0.3.jar", "jakarta.jms-api"), ("proton-j-0.35.0.jar", "proton-j")]:
            self.assertEqual(SYNC.artifact(name), key, name)

    def test_add_replace_same_and_left(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, target = Path(tmp) / "runtime-libs", Path(tmp) / "WEB-INF-lib"
            source.mkdir()
            target.mkdir()
            put(source, "jackson-core-2.18.12.jar", b"new core")
            put(source, "jackson-dataformat-yaml-2.18.12.jar", b"yaml")
            put(source, "snappy-java.jar", b"new snappy")
            put(source, "kafka-clients.jar", b"kafka")
            put(source, "azure-core-1.59.0.jar", b"azure")
            put(target, "jackson-core-2.14.2.jar", b"old core")
            put(target, "snappy-java.jar", b"old snappy")
            put(target, "kafka-clients.jar", b"kafka")
            put(target, "azure-core-1.59.0.jar", b"azure")
            put(target, "ebx-connectors-extension-sample.jar", b"mine")
            put(target, "customer-driver-9.9.jar", b"theirs")
            before = sorted(p.name for p in target.iterdir())
            code, text = run(source, target, "--dry-run")
            self.assertEqual(code, 0, text)
            self.assertEqual(before, sorted(p.name for p in target.iterdir()), "a dry run writes nothing")
            self.assertEqual((target / "snappy-java.jar").read_bytes(), b"old snappy")
            for line in ("WOULD ADD      jackson-dataformat-yaml-2.18.12.jar", "WOULD REPLACE  jackson-core-2.18.12.jar  <-  jackson-core-2.14.2.jar",
                         "WOULD REPLACE  snappy-java.jar  <-  snappy-java.jar (other bytes)", "2 jar(s) already identical", "LEFT     customer-driver-9.9.jar",
                         "LEFT     ebx-connectors-extension-sample.jar"):
                self.assertIn(line, text)
            backup = Path(tmp) / "kept"
            code, text = run(source, target, "--backup", str(backup))
            self.assertEqual(code, 0, text)
            self.assertEqual(sorted(p.name for p in backup.iterdir()), ["jackson-core-2.14.2.jar", "snappy-java.jar"])
            self.assertEqual((backup / "snappy-java.jar").read_bytes(), b"old snappy")
            names = sorted(p.name for p in target.iterdir())
            self.assertEqual(names, sorted(["jackson-core-2.18.12.jar", "jackson-dataformat-yaml-2.18.12.jar", "snappy-java.jar", "kafka-clients.jar",
                                            "azure-core-1.59.0.jar", "ebx-connectors-extension-sample.jar", "customer-driver-9.9.jar"]))
            self.assertEqual((target / "snappy-java.jar").read_bytes(), b"new snappy")
            self.assertEqual((target / "ebx-connectors-extension-sample.jar").read_bytes(), b"mine")
            self.assertEqual((target / "customer-driver-9.9.jar").read_bytes(), b"theirs")
            code, text = run(source, target)
            self.assertIn("0 added, 0 replaced, 5 unchanged, 2 left", text)

    def test_a_missing_target_is_created(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "runtime-libs"
            source.mkdir()
            put(source, "h2.jar", b"h2")
            code, text = run(source, Path(tmp) / "a" / "b")
            self.assertEqual(code, 0, text)
            self.assertEqual((Path(tmp) / "a" / "b" / "h2.jar").read_bytes(), b"h2")

    CLASSPATH = ('<?xml version="1.0" encoding="UTF-8"?>\n<classpath>\n\t<classpathentry kind="src" output="target/classes" path="src/main/java"/>\n'
                 '\t<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>\n'
                 '\t<classpathentry kind="lib" path="src/main/webapp/WEB-INF/lib/jackson-core-2.14.2.jar"/>\n'
                 '\t<classpathentry kind="lib" path="src/main/webapp/WEB-INF/lib/other-1.0.jar"/>\n'
                 '\t<classpathentry combineaccessrules="false" kind="src" path="/ebx-ps-fasttrack-server"/>\n'
                 '\t<classpathentry kind="output" path="target/classes"/>\n</classpath>\n')

    def test_classpath_is_amended_in_order_idempotently(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, target = Path(tmp) / "runtime-libs", Path(tmp) / "lib"
            source.mkdir()
            for n in ("jackson-core-2.18.12.jar", "jackson-databind-2.18.12.jar", "jackson-annotations-2.18.12.jar", "jackson-datatype-jsr310-2.18.12.jar"):
                put(source, n, n.encode())
            cp = Path(tmp) / ".classpath"
            cp.write_text(self.CLASSPATH, encoding="utf-8")
            code, text = run(source, target, "--classpath", str(cp), "--dry-run")
            self.assertIn("WOULD AMEND", text)
            self.assertEqual(cp.read_text(encoding="utf-8"), self.CLASSPATH, "dry run writes nothing")
            code, text = run(source, target, "--classpath", str(cp))
            self.assertEqual(code, 0, text)
            self.assertIn("amended .classpath", text)
            body = cp.read_text(encoding="utf-8")
            self.assertNotIn("jackson-core-2.14.2", body)
            self.assertIn("other-1.0.jar", body)
            self.assertNotIn("jsr310", body)
            server = body.index("/ebx-ps-fasttrack-server")
            for n in ("jackson-core-2.18.12.jar", "jackson-databind-2.18.12.jar", "jackson-annotations-2.18.12.jar"):
                self.assertTrue(body.index(n) < server, n)
            baks = list(Path(tmp).glob(".classpath.bak-*"))
            self.assertEqual(len(baks), 1)
            self.assertEqual(baks[0].read_text(encoding="utf-8"), self.CLASSPATH)
            code, text = run(source, target, "--classpath", str(cp))
            self.assertIn("nothing to do", text)
            self.assertEqual(cp.read_text(encoding="utf-8"), body, "idempotent")
            self.assertEqual(len(list(Path(tmp).glob(".classpath.bak-*"))), 1)

    def test_without_classpath_nothing_is_created(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "runtime-libs"
            source.mkdir()
            put(source, "jackson-core-2.18.12.jar", b"x")
            cp = Path(tmp) / ".classpath"
            code, text = run(source, Path(tmp) / "lib", "--classpath", str(cp))
            self.assertEqual(code, 0, text)
            self.assertIn("no .classpath", text)
            self.assertFalse(cp.exists())


if __name__ == "__main__":
    unittest.main()
