import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "tools" / "generate_connector_parity.py"
SPEC = importlib.util.spec_from_file_location("generate_connector_parity", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ConnectorParityReportTest(unittest.TestCase):
    def fixture(self):
        return {
            "baselineDate": "2026-09-16",
            "catalog": [
                {"id": "CAT-2", "connectorType": "zeta", "implementation": "Z", "registrationMode": "BUILT_IN", "workflowStatus": "REVIEW"},
                {"id": "CAT-1", "connectorType": "alpha", "implementation": "A", "registrationMode": "EXTENSION", "workflowStatus": "DONE"},
                {"id": "CAT-3", "connectorType": "orphan", "implementation": "O", "registrationMode": "BUILT_IN", "workflowStatus": "REVIEW"},
            ],
            "externalTargets": [
                {"id": "BASE-2", "sourceSection": "CORE", "sourceName": "Open item", "coverageStatus": "PARTIAL", "workflowStatus": "IN_PROGRESS", "coveredBy": "zeta"},
                {"id": "BASE-1", "sourceSection": "CLOUD", "sourceName": "Resolved item", "coverageStatus": "COVERED", "workflowStatus": "DONE", "coveredBy": "alpha"},
            ],
        }

    def test_render_is_deterministic_and_sorted(self):
        fixture = self.fixture()
        raw = json.dumps(fixture, separators=(",", ":")).encode()
        first = MODULE.render(fixture, raw)
        second = MODULE.render(fixture, raw)
        self.assertEqual(first, second)
        self.assertIn("Catalog connector types: **3**", first)
        self.assertIn("Capability coverage: **1 resolved**, **1 open**", first)
        self.assertLess(first.index("| CLOUD |"), first.index("| CORE |"))
        self.assertIn("| BASE-2 | CORE | Open item | PARTIAL | IN_PROGRESS | zeta |", first)
        self.assertIn("| orphan | BUILT_IN | REVIEW | O |", first)

    def test_check_detects_stale_and_accepts_current_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "coverage.json"
            output = root / "report.md"
            manifest.write_text(json.dumps(self.fixture()), encoding="utf-8")
            command = [sys.executable, str(SCRIPT), "--manifest", str(manifest), "--output", str(output)]
            self.assertEqual(0, subprocess.run(command, check=False).returncode)
            self.assertEqual(0, subprocess.run(command + ["--check"], check=False).returncode)
            output.write_text("stale", encoding="utf-8")
            self.assertEqual(1, subprocess.run(command + ["--check"], check=False).returncode)


if __name__ == "__main__":
    unittest.main()
