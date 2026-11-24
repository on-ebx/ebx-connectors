#!/usr/bin/env python3
"""Validate that every externally blocked capability has a complete neutral gate."""

import json
import pathlib
import unittest


MODULE = pathlib.Path(__file__).resolve().parents[3]
GATES = MODULE / "evidence-gates.json"
COVERAGE = MODULE / "connector-coverage.yaml"
GUIDE = MODULE / "docs" / "EVIDENCE-AND-DEPLOYMENT-GATES.md"


class EvidenceGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gates = json.loads(GATES.read_text(encoding="utf-8"))
        cls.coverage = json.loads(COVERAGE.read_text(encoding="utf-8"))

    def test_gate_schema_and_fail_closed_rules(self):
        self.assertEqual(1, self.gates["schemaVersion"])
        self.assertEqual("BLOCKED", self.gates["defaultDecision"])
        rules = self.gates["rules"]
        for rule in (
            "allEvidenceRequired",
            "noInferredEndpoints",
            "noInferredDriverCoordinates",
            "noProductionSecretsInEvidence",
            "approvalDoesNotRegisterAConnector",
        ):
            self.assertIs(rules.get(rule), True, rule)

    def test_all_and_only_blocked_items_have_one_gate(self):
        blocked = {
            item["id"]
            for item in self.coverage["externalTargets"]
            if item["workflowStatus"] == "BLOCKED"
        }
        gated = []
        for group in self.gates["groups"]:
            gated.extend(item["coverageId"] for item in group["items"])
        self.assertEqual(len(gated), len(set(gated)), "coverage IDs must not occur twice")
        self.assertEqual(blocked, set(gated))

    def test_each_group_is_actionable(self):
        for group in self.gates["groups"]:
            self.assertTrue(group["id"])
            self.assertGreaterEqual(len(group["ownerRoles"]), 3, group["id"])
            self.assertGreaterEqual(len(group["sharedEvidence"]), 4, group["id"])
            self.assertGreaterEqual(len(group["promotionCriteria"]), 3, group["id"])
            self.assertGreaterEqual(len(group["rejectionConditions"]), 3, group["id"])
            self.assertTrue(group["items"], group["id"])
            for item in group["items"]:
                self.assertTrue(item["capability"])
                self.assertTrue(item["specificEvidence"])

    def test_gate_does_not_claim_implementation_details(self):
        text = GATES.read_text(encoding="utf-8").lower()
        forbidden = (
            "http://",
            "https://",
            "jdbc:",
            "client_secret=",
            "access_token=",
            "password=",
        )
        for token in forbidden:
            self.assertNotIn(token, text)

    def test_human_checklist_exists_and_covers_each_group(self):
        guide = GUIDE.read_text(encoding="utf-8")
        for group in self.gates["groups"]:
            for item in group["items"]:
                self.assertIn(item["coverageId"], guide)
        self.assertIn("Do not place secrets", guide)


if __name__ == "__main__":
    unittest.main()
