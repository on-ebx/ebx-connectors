import json, unittest
from pathlib import Path
import importlib.util

SCRIPT=Path(__file__).resolve().parents[3]/"tools/generate_operational_readiness.py"
SPEC=importlib.util.spec_from_file_location("operational",str(SCRIPT)); MODULE=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)

class OperationalReadinessTest(unittest.TestCase):
 def test_renders_every_connector_and_status(self):
  coverage={"catalog":[{"connectorType":"b"},{"connectorType":"a"}]}; policy={"defaultStatus":"NOT_STARTED","allowedStatuses":["NOT_STARTED","ACCEPTED"],"requiredStages":["catalog","execution"],"overrides":{"a":{"status":"ACCEPTED","evidence":"proof","nextAction":"retain"}}}
  rendered=MODULE.render(coverage,policy); self.assertIn("Catalog total: **2**",rendered); self.assertIn("| a | ACCEPTED | proof | retain |",rendered); self.assertIn("| b | NOT_STARTED |",rendered)
 def test_rejects_unknown_override(self):
  with self.assertRaises(ValueError): MODULE.render({"catalog":[{"connectorType":"a"}]},{"defaultStatus":"NOT_STARTED","allowedStatuses":["NOT_STARTED"],"requiredStages":[],"overrides":{"missing":{"status":"NOT_STARTED"}}})
if __name__=="__main__": unittest.main()
