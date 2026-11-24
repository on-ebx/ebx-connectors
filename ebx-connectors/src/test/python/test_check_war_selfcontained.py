#!/usr/bin/env python3
"""tools/check_war_selfcontained.py: a war that references a class found nowhere fails (audit X3-07); a pinned jar that is missing or differs fails."""

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / "tools/check_war_selfcontained.py"


def javac_tool(name):
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / name).is_file():
        return str(Path(home) / "bin" / name)
    return shutil.which(name)


def compile_class(workdir, package, name, body, classpath=""):
    source = workdir / ("%s.java" % name)
    source.write_text("package %s;\npublic class %s { %s }\n" % (package, name, body), encoding="utf-8")
    out = workdir / "out"
    out.mkdir(exist_ok=True)
    command = [javac_tool("javac"), "--release", "8", "-nowarn", "-d", str(out)]
    if classpath:
        command += ["-cp", classpath]
    subprocess.run(command + [str(source)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out / package.replace(".", "/") / ("%s.class" % name)


def jar_of(path, entries):
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)


class SelfContainedTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="selfcheck-test."))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        work = self.tmp / "src"
        work.mkdir()
        # lib.Dep is the class that a driver needs; app.Uses calls it, as a driver calls its logging or JSON library
        self.dep = compile_class(work, "lib", "Dep", "public static int one() { return 1; }").read_bytes()
        self.uses = compile_class(work, "app", "Uses", "public int f() { return lib.Dep.one(); }", str(work / "out")).read_bytes()

    def war(self, name, with_dep, extra_jars=None):
        path = self.tmp / name
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("WEB-INF/classes/app/Uses.class", self.uses)
            if with_dep:
                jar = self.tmp / ("dep-%s.jar" % name)
                jar_of(jar, {"lib/Dep.class": self.dep})
                z.write(jar, "WEB-INF/lib/dep.jar")
            for jar_name, data in (extra_jars or {}).items():
                z.writestr("WEB-INF/lib/" + jar_name, data)
        return path

    def run_check(self, war, *args, env=None):
        base = dict(os.environ)
        base.pop("EBX_STANDARD_JAR", None)
        base.pop("TOMCAT_LIB_DIR", None)
        base.update(env or {})
        return subprocess.run([sys.executable, "-I", str(SCRIPT), str(war)] + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, env=base)

    def test_a_referenced_class_found_nowhere_fails_and_is_named(self):
        done = self.run_check(self.war("without.war", with_dep=False))
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("CONN-WAR-6", done.stderr)
        self.assertIn("lib.Dep", done.stderr)
        self.assertIn("app.Uses", done.stderr)

    def test_the_same_war_with_the_class_present_passes(self):
        done = self.run_check(self.war("with.war", with_dep=True))
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("PASS", done.stdout)

    def test_an_accepted_optional_reference_passes_only_with_its_reason(self):
        war = self.war("optional.war", with_dep=False)
        allow = self.tmp / "allow.txt"
        allow.write_text("classes | lib. | the dependency is optional in this test\n", encoding="utf-8")
        done = self.run_check(war, "--allow", str(allow))
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        allow.write_text("classes | lib. |\n", encoding="utf-8")
        done = self.run_check(war, "--allow", str(allow))
        self.assertEqual(done.returncode, 2, done.stdout + done.stderr)
        self.assertIn("CONN-WAR-4", done.stderr)

    def test_a_stale_allow_entry_is_reported(self):
        allow = self.tmp / "allow.txt"
        allow.write_text("classes | nothing.here. | no longer used\n", encoding="utf-8")
        done = self.run_check(self.war("stale.war", with_dep=True), "--allow", str(allow))
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("stale allow entry", done.stdout)

    def test_the_servlet_api_counts_as_supplied_but_the_add_on_does_not(self):
        work = self.tmp / "src2"
        work.mkdir()
        # the classes are not compiled against the real APIs: bytecode naming them is enough for jdeps
        servlet = self._stub_user(work, "app2", "UsesServlet", "jakarta/servlet/http/HttpServlet")
        addon = self._stub_user(work, "app2", "UsesAddon", "com/orchestranetworks/addon/mame/service/OperationsFactory")
        for label, data, expected in (("servlet", servlet, 0), ("addon", addon, 1)):
            path = self.tmp / (label + ".war")
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("WEB-INF/classes/app2/%s.class" % ("UsesServlet" if label == "servlet" else "UsesAddon"), data)
            done = self.run_check(path)
            self.assertEqual(done.returncode, expected, label + done.stdout + done.stderr)
            self.assertIn("EBX_STANDARD_JAR is not set", done.stdout)

    def _stub_user(self, work, package, name, referenced):
        """A class whose constant pool names `referenced` as a superclass: compiled against a stub of it, then the stub is left out of the war."""
        stub_package, stub_name = referenced.rsplit("/", 1)
        stub_dir = work / ("stub-" + name)
        (stub_dir / stub_package).mkdir(parents=True)
        (stub_dir / stub_package / (stub_name + ".java")).write_text("package %s;\npublic class %s { }\n" % (stub_package.replace("/", "."), stub_name), encoding="utf-8")
        subprocess.run([javac_tool("javac"), "--release", "8", "-nowarn", "-d", str(stub_dir), str(stub_dir / stub_package / (stub_name + ".java"))],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        compile_class(work, package, name, "", str(stub_dir)).read_bytes()
        source = work / (name + ".java")
        source.write_text("package %s;\npublic class %s extends %s { }\n" % (package, name, referenced.replace("/", ".")), encoding="utf-8")
        out = work / ("out-" + name)
        out.mkdir()
        subprocess.run([javac_tool("javac"), "--release", "8", "-nowarn", "-cp", str(stub_dir), "-d", str(out), str(source)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return (out / package / (name + ".class")).read_bytes()

    def test_a_pinned_jar_must_be_in_the_war_with_the_pinned_bytes(self):
        good = b"pretend jar bytes"
        pinned = self.tmp / "PINNED.txt"
        pinned.write_text("# comment\n%s  json.jar\n" % hashlib.sha256(good).hexdigest(), encoding="utf-8")
        missing = self.run_check(self.war("nopin.war", with_dep=True), "--pinned", str(pinned))
        self.assertEqual(missing.returncode, 1, missing.stdout + missing.stderr)
        self.assertIn("CONN-WAR-2", missing.stderr)
        other = self.tmp / "other.jar"
        jar_of(other, {"META-INF/other": b"other bytes"})
        different = self.run_check(self.war("badpin.war", with_dep=True, extra_jars={"json.jar": other.read_bytes()}), "--pinned", str(pinned))
        self.assertEqual(different.returncode, 1, different.stdout + different.stderr)
        self.assertIn("CONN-WAR-3", different.stderr)
        self.assertIn("PINNED-WAR-JARS.txt", different.stderr)
        # a jar that is not a real archive is enough for the byte comparison, but jdeps must still be able to read the war: use a real empty one
        empty = self.tmp / "empty.jar"
        jar_of(empty, {"META-INF/x": b""})
        same = self.run_check(self.war("goodpin.war", with_dep=True, extra_jars={"json.jar": empty.read_bytes()}), "--pinned",
                              str(self._pin(empty)))
        self.assertEqual(same.returncode, 0, same.stdout + same.stderr)

    def _pin(self, jar):
        pinned = self.tmp / "PINNED-2.txt"
        pinned.write_text("%s  json.jar\n" % hashlib.sha256(jar.read_bytes()).hexdigest(), encoding="utf-8")
        return pinned


if __name__ == "__main__":
    unittest.main()
