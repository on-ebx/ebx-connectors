#!/usr/bin/env python3
"""tools/schema_diff.py tells an additive model change from one that touches data."""

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[3] / "tools/schema_diff.py"
SPEC = importlib.util.spec_from_file_location("schema_diff", SCRIPT)
DIFF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DIFF)


def schema(tables):
    """tables: {name: (primary keys, [(field, type)])} -> a minimal model document."""
    body = ""
    for name, (keys, fields) in tables.items():
        inner = "".join('<xs:element name="%s" type="%s"/>' % field for field in fields)
        body += (
            '<xs:element name="%s" maxOccurs="unbounded"><xs:annotation><xs:appinfo>'
            "<osd:table><primaryKeys>%s</primaryKeys></osd:table></xs:appinfo></xs:annotation>"
            "<xs:complexType><xs:sequence>%s</xs:sequence></xs:complexType></xs:element>" % (name, keys, inner)
        )
    return (
        '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:osd="urn:ebx-schemas:common_1.0">'
        '<xs:element name="root"><xs:complexType><xs:sequence>%s</xs:sequence></xs:complexType></xs:element></xs:schema>' % body
    )


BASE = {"Connector": ("/id", [("id", "xs:string"), ("url", "xs:string")])}


class SchemaDiffTest(unittest.TestCase):
    def test_same_model_reports_nothing(self):
        self.assertEqual(([], []), DIFF.compare(schema(BASE), schema(BASE)))

    def test_added_field_and_table_are_additive(self):
        new = {"Connector": ("/id", [("id", "xs:string"), ("url", "xs:string"), ("note", "xs:string")]),
               "SeedMarker": ("/item", [("item", "xs:string")])}
        info, affecting = DIFF.compare(schema(BASE), schema(new))
        self.assertEqual([], affecting)
        self.assertTrue(any("field added: /root/Connector/note" in line for line in info), info)
        self.assertTrue(any("table added: SeedMarker" in line for line in info), info)

    def test_primary_key_change_affects_data(self):
        new = {"Connector": ("/id /url", [("id", "xs:string"), ("url", "xs:string")])}
        _, affecting = DIFF.compare(schema(BASE), schema(new))
        self.assertEqual(1, len(affecting), affecting)
        self.assertIn("PRIMARY KEY CHANGED", affecting[0])

    def test_removed_field_and_changed_type_affect_data(self):
        new = {"Connector": ("/id", [("id", "xs:int")])}
        _, affecting = DIFF.compare(schema(BASE), schema(new))
        self.assertEqual(2, len(affecting), affecting)
        self.assertTrue(any("field removed" in line and "url" in line for line in affecting))
        self.assertTrue(any("type changed" in line and "xs:string -> xs:int" in line for line in affecting))

    def test_removed_table_is_reported_once(self):
        _, affecting = DIFF.compare(schema(BASE), schema({}))
        self.assertEqual(1, len(affecting), affecting)
        self.assertIn("table removed", affecting[0])


if __name__ == "__main__":
    unittest.main()
