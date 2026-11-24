import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[3] / "tools" / "verify_release_readiness.py"
SPEC = importlib.util.spec_from_file_location("release_readiness", str(SCRIPT))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Args:
    coverage = "connector-coverage.yaml"
    war = "target/baseline/ebx-connectors.war"
    branch = None
    commit = None
    sha256 = None


class ReleaseReadinessTest(unittest.TestCase):
    def fixture(self, include_class=True, include_seed=True, include_asset=True):
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "connector-coverage.yaml").write_text(json.dumps({"catalog": [{
            "connectorType": "sample", "implementation": "example.SampleConnector"
        }]}), encoding="utf-8")
        installer = root / "src/main/java/com/on/ebx/connect/ebx/ConnectorsInstaller.java"
        installer.parent.mkdir(parents=True)
        installer.write_text('add("sample");' if include_seed else "", encoding="utf-8")
        docs = root / "docs"
        docs.mkdir()
        (docs / "SAMPLE-USER-GUIDE.html").write_text("guide", encoding="utf-8")
        if include_asset:
            images = docs / "images/connectors"
            images.mkdir(parents=True)
            (images / "Sample.svg").write_text("<svg/>", encoding="utf-8")
        war = root / "target/baseline/ebx-connectors.war"
        war.parent.mkdir(parents=True)
        with zipfile.ZipFile(str(war), "w") as archive:
            if include_class:
                archive.writestr("WEB-INF/classes/example/SampleConnector.class", b"class")
        return temp, root

    def test_deterministic_pass(self):
        temp, root = self.fixture()
        self.addCleanup(temp.cleanup)
        args = Args()
        args.repo = str(root)
        first = MODULE.verify(args)
        second = MODULE.verify(args)
        self.assertEqual(first, second)
        self.assertEqual("PASS", first["overallStatus"])
        self.assertEqual(1, first["catalogCount"])

    def test_reports_packaging_seed_and_asset_gaps(self):
        temp, root = self.fixture(False, False, False)
        self.addCleanup(temp.cleanup)
        args = Args()
        args.repo = str(root)
        report = MODULE.verify(args)
        self.assertEqual("FAIL", report["overallStatus"])
        failures = {item["check"] for item in report["checks"] if item["status"] == "FAIL"}
        self.assertTrue({"war-catalog-classes", "additive-seeds", "documented-connector-assets"}.issubset(failures))


if __name__ == "__main__":
    unittest.main()
