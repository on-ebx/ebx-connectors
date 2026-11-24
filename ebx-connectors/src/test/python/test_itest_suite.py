#!/usr/bin/env python3
"""tools/itest: the integration suite, run against a stand-in server that imitates the module and Data Services.

The stand-in is not EBX: it only lets the suite's own logic be tested offline (response parsing, exit codes, the
clean-up that must run after a failure and must leave nothing behind, the refusal to send credentials in clear).
Whether the real module behaves as the suite expects is what the suite itself, run on a real instance, tells.
"""
import base64
import io
import json
import re
import sys
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ITEST_DIR = Path(__file__).resolve().parents[3] / "tools" / "itest"
sys.path.insert(0, str(ITEST_DIR))
sys.dont_write_bytecode = True

import client  # noqa: E402
import itest  # noqa: E402
import suite  # noqa: E402

ADMIN = "admin:pw"
NOROLE = "user:user"
ORDER = {"READ": 1, "RUN": 2, "MANAGE": 3}
DS_ROOT = "/ebx-dataservices/rest/data/v1"
PKS = {"Connector": "id", "Mapping": "id", "AccessRight": "profile", "ConnectorCategory": "code", "SyncLog": "id", "ExecutionRun": "runId",
       "ConnectorProperty": "connector", "FieldProvenance": "dataspace"}


class StandIn(object):
    """The state of the stand-in instance and the switches that make it misbehave."""

    def __init__(self):
        self.lock = threading.RLock()
        self.tables = {}                  # (dataspace, table) -> {pk: fields}
        self.dataspaces = {"Connectors"}
        self.sequence = 0
        self.requests = []
        self.fail_sync = False            # the sync route answers 500
        self.ebx_denies_norole = False    # EBX refuses the no-role account on the dataset
        self.create_dataspace_fails = False
        self.auth = {"Basic " + base64.b64encode(ADMIN.encode()).decode(): "admin", "Basic " + base64.b64encode(NOROLE.encode()).decode(): "user"}

    def table(self, dataspace, name):
        return self.tables.setdefault((dataspace, name), {})

    def next_id(self):
        self.sequence += 1
        return str(self.sequence)

    def right_of(self, user):
        if user == "admin":
            return "MANAGE"
        row = self.table("Connectors", "AccessRight").get("Beveryone")
        return row.get("right") if row else None

    def count(self, name, field, prefix):
        return sum(1 for (ds, t), rows in self.tables.items() if t == name for r in rows.values() if str(r.get(field, "")).startswith(prefix))


def unwrap(v):
    return client.unwrap(v)


def matches(row, xpath):
    if not xpath:
        return True
    m = re.match(r"^starts-with\(\./(\w+),'(.*)'\)$", xpath)
    if m:
        return str(row.get(m.group(1), "")).startswith(m.group(2))
    m = re.match(r"^\./(\w+)='(.*)'$", xpath)
    if m:
        return str(row.get(m.group(1), "")) == m.group(2)
    raise ValueError("filter not supported by the stand-in: " + xpath)


def make_handler(state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def answer(self, status, body=None, raw=None):
            data = raw if raw is not None else (json.dumps(body).encode() if body is not None else b"")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def handle_any(self, method):
            with state.lock:
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b""
                state.requests.append((method, self.path.split("?")[0]))
                parsed = urllib.parse.urlparse(self.path)
                path = parsed.path
                query = dict(urllib.parse.parse_qsl(parsed.query))
                user = state.auth.get(self.headers.get("Authorization", ""))
                if user is None:
                    return self.answer(401, {"error": "authentication required"})
                body = None
                if raw and "json" in (self.headers.get("Content-Type") or ""):
                    body = json.loads(raw.decode())
                if path.startswith(DS_ROOT):
                    return self.data_services(method, path[len(DS_ROOT):], query, body, user, raw)
                if path.startswith("/ebx-connectors/rest/"):
                    return self.module(method, path[len("/ebx-connectors/rest"):], query, body, user)
                return self.answer(404, {"error": "no route"})

        # ------------------------------------------------------------------------------------------ module routes
        def need(self, user, right, what="this route"):
            have = state.right_of(user)
            if have is None or ORDER[have] < ORDER[right]:
                if (self.headers.get("Accept-Language") or "").lower().startswith("fr"):
                    self.answer(403, {"error": "Acc\u00e8s refus\u00e9 : %s exige le droit Connectors %s." % (what, right)})
                else:
                    self.answer(403, {"error": "Access denied: %s requires the Connectors right %s. An EBX administrator gives it." % (what, right)})
                return False
            if user != "admin" and state.ebx_denies_norole:
                self.answer(403, {"error": "Access to dataset 'Connectors' of dataspace 'Connectors' is not allowed."})
                return False
            return True

        def module(self, method, path, query, body, user):
            if path == "/v1/status" and method == "GET":
                if not self.need(user, "READ"):
                    return
                return self.answer(200, {"module": "ebx-connectors", "version": {"version": "0.0.0-test", "commit": "x", "builtAt": "now"},
                                         "installation": {"datasetPresent": True, "modelPresent": True, "installerCompleted": True},
                                         "egress": {"allowListSet": False, "requireAllowList": False, "blockPrivateHosts": True, "privateExceptions": 0},
                                         "retention": {"enabled": True, "days": 90, "onByDefault": True}, "secrets": {"clearValuesAllowed": False}, "runs": {"running": 0}, "asyncPool": {}})
            m = re.match(r"^/v1/webhook/(\w+)$", path)
            if m:
                # one mode: an EBX session with the RUN right (the stand-in answers 401 itself when there is no session)
                if self.need(user, "RUN", "the webhook"):
                    return self.answer(200, {"ready": True, "imported": 0})
                return
            if path == "/mcp":
                if method != "POST":
                    return self.answer(405, {})
                return self.mcp(body, user)
            if path == "/v1/connectors" and method == "GET":
                if not self.need(user, "READ"):
                    return
                return self.answer(200, {"types": [], "connectors": [{"id": k} for k in state.table("Connectors", "Connector")]})
            if path == "/v1/mappings" and method == "GET":
                if not self.need(user, "READ"):
                    return
                return self.answer(200, {"mappings": [{"id": int(k), "label": v.get("label")} for k, v in state.table("Connectors", "Mapping").items()]})
            m = re.match(r"^/v1/mappings/(\w+)$", path)
            if m and method == "DELETE":
                if not self.need(user, "MANAGE"):
                    return
                if m.group(1) not in state.table("Connectors", "Mapping"):
                    return self.answer(404, {"error": "No mapping"})
                del state.table("Connectors", "Mapping")[m.group(1)]
                return self.answer(200, {"deletedRows": 3})
            m = re.match(r"^/v1/sync/(\w+)$", path)
            if m and method == "POST":
                if not self.need(user, "RUN"):
                    return
                if state.fail_sync:
                    return self.answer(500, {"error": "boom"})
                return self.sync(m.group(1), query.get("dryRun") == "true")
            m = re.match(r"^/v1/runs/(\w+)$", path)
            if m and method == "GET":
                if not self.need(user, "READ"):
                    return
                row = state.table("Connectors", "ExecutionRun").get(m.group(1))
                if not row:
                    return self.answer(200, {"runId": m.group(1), "calls": []})
                return self.answer(200, {"runId": m.group(1), "execution": dict(row)})
            return self.answer(404, {"error": "no route " + path})

        def mcp(self, body, user):
            method = body.get("method")
            rid = body.get("id")
            if method == "initialize":
                return self.answer(200, {"jsonrpc": "2.0", "id": rid, "result": {"serverInfo": {"version": "0.0.0-test"}}})
            have = state.right_of(user)
            if method == "tools/list":
                tools = []
                catalog = [("connectors_help", "READ"), ("connectors_list", "READ"), ("connectors_sync", "RUN"), ("connectors_mapping_upsert", "MANAGE"),
                           ("connectors_mapping_delete", "MANAGE"), ("connectors_call", "MANAGE")]
                for name, right in catalog:
                    if have and ORDER[have] >= ORDER[right]:
                        tools.append({"name": name})
                return self.answer(200, {"jsonrpc": "2.0", "id": rid, "result": {"tools": tools}})
            if method == "tools/call":
                name = body["params"]["name"]
                args = body["params"].get("arguments") or {}
                needed = {"connectors_sync": "RUN", "connectors_mapping_upsert": "MANAGE", "connectors_call": "MANAGE"}.get(name, "MANAGE")
                if have is None or ORDER[have] < ORDER[needed]:
                    data = {"error": "Access denied: '%s' requires the Connectors right %s." % (name, needed)}
                elif user != "admin" and state.ebx_denies_norole:
                    data = {"error": "Access to dataspace 'Connectors' is not allowed."}
                elif name == "connectors_mapping_upsert":
                    doc = args["mapping"]
                    mappings = state.table("Connectors", "Mapping")
                    found = [k for k, v in mappings.items() if v.get("label") == doc["label"]]
                    if found:
                        mappings[found[0]] = doc
                        data = {"mapping": found[0], "created": False}
                    elif doc["connector"] not in state.table("Connectors", "Connector"):
                        data = {"error": "no connector '%s'" % doc["connector"]}
                    else:
                        key = state.next_id()
                        doc = dict(doc)
                        doc["id"] = key
                        mappings[key] = doc
                        data = {"mapping": key, "created": True, "rulesWritten": 2}
                elif name == "connectors_sync":
                    data = {"status": "OK"}
                elif name == "connectors_call":
                    conn = state.table("Connectors", "Connector").get(args["connector"])
                    if conn and conn.get("password"):
                        data = {"error": "Connector '%s': password holds a secret stored in clear text, which is refused because ebx.connectors.secrets.allowClear is off" % args["connector"]}
                    elif conn and "127.0.0.2" in str(conn.get("baseUrl")):
                        data = {"error": "Connector '%s': outbound call to %s refused: it resolves to a loopback or private address (127.0.0.2) and ebx.connectors.egress.blockPrivate is on" % (args["connector"], conn.get("baseUrl"))}
                    else:
                        data = {"error": "no such connector"}
                else:
                    data = {}
                result = {"content": [{"type": "text", "text": json.dumps(data)}], "structuredContent": data, "isError": "error" in data}
                return self.answer(200, {"jsonrpc": "2.0", "id": rid, "result": result})
            return self.answer(200, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}})

        def sync(self, ident, dry):
            mapping = state.table("Connectors", "Mapping").get(ident)
            if not mapping:
                return self.answer(404, {"error": "No mapping"})
            run_id = "R%04d" % int(state.next_id())
            connector = state.table("Connectors", "Connector").get(mapping["connector"])
            counts = {"created": 0, "updated": 0, "noChange": 0, "errors": 0}
            try:
                payload = connector["sampleData"].split(",", 1)[1]
                items = json.loads(base64.b64decode(payload, validate=True).decode())
            except Exception:
                items = None
            if items is None:
                counts["errors"] = 1
                status = "ERROR"
            else:
                target = state.table(mapping["dataspace"], "ConnectorCategory")
                for item in items:
                    existing = target.get(item["code"])
                    if existing is None:
                        counts["created"] += 1
                        if not dry:
                            target[item["code"]] = {"code": item["code"], "label": item["label"]}
                    elif existing["label"] == item["label"]:
                        counts["noChange"] += 1
                    else:
                        counts["updated"] += 1
                        existing["label"] = item["label"]
                status = "OK"
            if not dry:
                state.table("Connectors", "ExecutionRun")[run_id] = {"runId": run_id, "status": "ERROR" if status == "ERROR" else "DONE", "created": counts["created"],
                                                                     "startedAt": "t", "updatedAt": "t", "errorCount": counts["errors"], "noChange": counts["noChange"]}
                logs = state.table("Connectors", "SyncLog")
                key = state.next_id()
                logs[key] = {"id": key, "runId": run_id, "dataspace": mapping["dataspace"]}
            out = dict(counts)
            out.update({"runId": run_id, "dryRun": dry, "status": status, "count": len(items or [])})
            return self.answer(422 if status == "ERROR" else 200, out)

        # ----------------------------------------------------------------------------------------- Data Services
        def data_services(self, method, path, query, body, user, raw):
            if user != "admin" and (state.ebx_denies_norole or ORDER.get(state.right_of(user) or "", 0) < 3 or method not in ("PUT",)):
                if method == "PUT" and state.right_of(user) != "MANAGE":
                    return self.answer(403, {"error": "Forbidden to [UPDATE] the record"})
                if method != "PUT":
                    return self.answer(403, {"error": "forbidden"})
            m = re.match(r"^/B([\w.-]+):createDataspace$", path)
            if m and method == "POST":
                if state.create_dataspace_fails:
                    return self.answer(400, {"error": "cannot create"})
                key = body["content"]["key"][1:]
                state.dataspaces.add(key)
                return self.answer(201, {})
            m = re.match(r"^/B([\w.-]+):close$", path)
            if m and method == "POST":
                state.dataspaces.discard(m.group(1))
                for k in [k for k in state.tables if k[0] == m.group(1)]:
                    del state.tables[k]
                return self.answer(204)
            m = re.match(r"^/B([\w.-]+)$", path)
            if m and method == "GET":
                return self.answer(200 if m.group(1) in state.dataspaces else 404, {})
            m = re.match(r"^/B([\w.-]+)/Connectors/root/(\w+)(?:/(.+))?$", path)
            if not m:
                return self.answer(404, {"error": "no route"})
            dataspace, table, pk = m.group(1), m.group(2), m.group(3)
            if dataspace not in state.dataspaces:
                return self.answer(404, {"error": "no dataspace"})
            rows = state.table(dataspace, table)
            pk = urllib.parse.unquote(pk) if pk else None
            key = PKS[table]
            if pk is None and method == "GET":
                found = [r for r in rows.values() if matches(r, query.get("filter"))]
                out = [{"content": client.wrap(r), "label": str(r.get(key)),
                        "details": "http://internal:8080%s/B%s/Connectors/root/%s/%s" % (DS_ROOT, dataspace, table, urllib.parse.quote(str(r.get(key)), safe=""))} for r in found]
                return self.answer(200, {"rows": out})
            if pk is None and method == "POST":
                for item in body["rows"]:
                    fields = unwrap(item["content"])
                    if table in ("SyncLog",) and key not in fields:
                        fields[key] = state.next_id()
                    rows[str(fields[key])] = fields
                return self.answer(201, {"rows": []})
            if pk is not None and method == "GET":
                return self.answer(200 if pk in rows else 404, {"content": client.wrap(rows[pk])} if pk in rows else {})
            if pk is not None and method == "PUT":
                if pk not in rows:
                    return self.answer(404, {})
                rows[pk].update(unwrap(body["content"]))
                return self.answer(204)
            if pk is not None and method == "DELETE":
                if pk not in rows:
                    return self.answer(404, {})
                del rows[pk]
                return self.answer(200, {"deletedCount": 1})
            return self.answer(405, {})

        def do_GET(self):
            self.handle_any("GET")

        def do_POST(self):
            self.handle_any("POST")

        def do_PUT(self):
            self.handle_any("PUT")

        def do_DELETE(self):
            self.handle_any("DELETE")

    return Handler


class Server(object):
    def __init__(self):
        self.state = StandIn()
        self.http = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.state))
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.http.shutdown()
        self.http.server_close()

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.http.server_address[1]


def run(url, env):
    out = io.StringIO()
    environment = {"ITEST_RIGHTS_WAIT": "0"}
    environment.update(env)
    code = itest.main([url], env=environment, out=out, sleep=lambda s: None)
    return code, out.getvalue()


def residue(state):
    """Everything the run left that carries its prefix, or that changed the AccessRight table."""
    left = []
    for (ds, table), rows in state.tables.items():
        for pk, row in rows.items():
            if table in ("AccessRight",) or any(str(v).startswith("itest-") for v in row.values()) or str(pk).startswith("itest-"):
                left.append((ds, table, pk))
            elif table in ("Mapping", "ExecutionRun", "SyncLog"):
                left.append((ds, table, pk))
    return left


class SuiteAgainstStandIn(unittest.TestCase):
    def test_every_scenario_passes_and_nothing_is_left(self):
        with Server() as server:
            code, text = run(server.url, {"EBX_AUTH": ADMIN, "EBX_AUTH_NOROLE": NOROLE})
            self.assertEqual(0, code, text)
            self.assertNotIn("FAIL", text)
            self.assertNotIn("SKIPPED", text)
            self.assertEqual([], residue(server.state))
            self.assertEqual({"Connectors"}, server.state.dataspaces)
            self.assertNotIn(":pw", text)
            self.assertNotIn(base64.b64encode(ADMIN.encode()).decode(), text)
            for scenario in ("S1.1", "S2.1", "S3.1", "S3.14", "S4.2", "S4.5", "S5.4", "S6.10", "S7.1", "S7.4", "S7.7"):
                self.assertIn(scenario, text)

    def test_a_failure_still_cleans_up_and_exits_1(self):
        with Server() as server:
            server.state.fail_sync = True
            code, text = run(server.url, {"EBX_AUTH": ADMIN, "EBX_AUTH_NOROLE": NOROLE})
            self.assertEqual(1, code, text)
            self.assertIn("FAIL: S4.2", text)
            self.assertEqual([], residue(server.state))
            self.assertEqual({"Connectors"}, server.state.dataspaces)

    def test_an_exception_in_a_scenario_is_a_failure_and_the_cleanup_runs(self):
        with Server() as server:
            original = suite.scenario_4_feed

            def broken(ctx):
                raise RuntimeError("boom")

            suite.scenario_4_feed = broken
            try:
                code, text = run(server.url, {"EBX_AUTH": ADMIN, "EBX_AUTH_NOROLE": NOROLE})
            finally:
                suite.scenario_4_feed = original
            self.assertEqual(1, code, text)
            self.assertIn("FAIL: S4 unexpected error", text)
            self.assertEqual([], residue(server.state))

    def test_without_the_no_role_account_two_scenarios_are_skipped(self):
        with Server() as server:
            code, text = run(server.url, {"EBX_AUTH": ADMIN})
            self.assertEqual(2, code, text)
            self.assertIn("SKIPPED: S2", text)
            self.assertIn("SKIPPED: S3", text)
            self.assertNotIn("FAIL", text)
            self.assertEqual([], residue(server.state))

    def test_an_existing_everyone_row_is_never_touched(self):
        with Server() as server:
            server.state.table("Connectors", "AccessRight")["Beveryone"] = {"profile": "Beveryone", "right": "READ", "comment": "somebody else's"}
            code, text = run(server.url, {"EBX_AUTH": ADMIN, "EBX_AUTH_NOROLE": NOROLE})
            self.assertEqual(2, code, text)
            self.assertIn("SKIPPED: S3", text)
            self.assertEqual({"profile": "Beveryone", "right": "READ", "comment": "somebody else's"}, server.state.table("Connectors", "AccessRight")["Beveryone"])

    def test_ebx_permissions_missing_skip_the_rights_scenario(self):
        with Server() as server:
            server.state.ebx_denies_norole = True
            code, text = run(server.url, {"EBX_AUTH": ADMIN, "EBX_AUTH_NOROLE": NOROLE})
            self.assertEqual(2, code, text)
            self.assertRegex(text, r"SKIPPED: S3 rights by level - the account has no EBX permission")
            self.assertEqual([], residue(server.state))

    def test_no_scratch_dataspace_falls_back_to_prefixed_rows(self):
        with Server() as server:
            server.state.create_dataspace_fails = True
            code, text = run(server.url, {"EBX_AUTH": ADMIN, "EBX_AUTH_NOROLE": NOROLE})
            self.assertEqual(0, code, text)
            self.assertIn("scratch dataspace not available", text)
            self.assertEqual([], residue(server.state))

    def test_wrong_credentials_stop_at_once(self):
        with Server() as server:
            code, text = run(server.url, {"EBX_AUTH": "admin:wrong"})
            self.assertEqual(1, code, text)
            self.assertIn("FAIL: S1 status answers", text)
            self.assertEqual(1, len(server.state.requests))


class Arguments(unittest.TestCase):
    def test_no_credentials_runs_nothing(self):
        with Server() as server:
            code, text = run(server.url, {})
            self.assertEqual(2, code)
            self.assertEqual([], server.state.requests)
            self.assertIn("SKIPPED: S1", text)

    def test_clear_credentials_to_a_remote_host_are_refused(self):
        code, text = run("http://example.invalid:8080", {"EBX_AUTH": ADMIN})
        self.assertEqual(2, code)
        self.assertEqual("", text)

    def test_credentials_must_have_a_login_and_a_password(self):
        for bad in ("nocolon", ":pw", "user:"):
            self.assertEqual(2, run("http://localhost:1", {"EBX_AUTH": bad})[0], bad)

    def test_an_argument_that_looks_like_an_option_is_refused(self):
        self.assertEqual(2, itest.main(["--help"], env={}, out=io.StringIO()))
        self.assertEqual(2, itest.main([], env={}, out=io.StringIO()))


class Helpers(unittest.TestCase):
    def test_unwrap_removes_the_envelope_at_every_depth(self):
        self.assertEqual({"a": 1, "g": {"b": [1, 2]}}, client.unwrap({"content": {"a": {"content": 1}, "g": {"content": {"b": [{"content": 1}, {"content": 2}]}}}}))

    def test_wrap_and_unwrap_are_inverse(self):
        fields = {"id": "x", "n": 3, "t": True}
        self.assertEqual(fields, client.unwrap(client.wrap(fields)))

    def test_xpath_literal(self):
        self.assertEqual("'a'", client.xq("a"))
        self.assertEqual('"it\'s"', client.xq("it's"))

    def test_exit_codes(self):
        report = suite.Report(lambda line: None)
        self.assertEqual(0, report.exit_code())
        report.skipped("x", "why")
        self.assertEqual(2, report.exit_code())
        report.failed("y")
        self.assertEqual(1, report.exit_code())

    def test_cleanup_runs_newest_first_and_retries_a_failed_item(self):
        order = []
        attempts = {"n": 0}
        cleanup = suite.Cleanup()
        cleanup.add("first", lambda: order.append("first"))

        def flaky():
            attempts["n"] += 1
            if attempts["n"] == 1:
                raise RuntimeError("busy")
            order.append("flaky")

        cleanup.add("flaky", flaky)
        cleanup.add("last", lambda: order.append("last"))
        failures = cleanup.run()
        self.assertEqual(["last", "first"], order)
        self.assertEqual(1, len(failures))
        self.assertEqual([], cleanup.run())
        self.assertEqual(["last", "first", "flaky"], order)

    def test_ebx_permission_detection_never_hides_a_module_refusal(self):
        blocked = client.Response(403, {}, '{"error":"Access to dataset \'Connectors\' is not allowed."}')
        module = client.Response(403, {}, '{"error":"Access denied: this route requires the Connectors right RUN."}')
        self.assertTrue(suite.ebx_blocked(blocked))
        self.assertFalse(suite.ebx_blocked(module))
        self.assertTrue(suite.refused_by_module(module))
        self.assertFalse(suite.refused_by_module(blocked))

    def test_a_row_path_comes_from_the_details_link_not_its_host(self):
        self.assertEqual("/ebx-dataservices/rest/data/v1/BConnectors/Connectors/root/SyncLog/7",
                         client._path_of("http://internal.example:8080/ebx-dataservices/rest/data/v1/BConnectors/Connectors/root/SyncLog/7"))

    def test_the_sample_is_a_base64_json_data_url(self):
        url = suite.data_url(suite.sample_rows("itest-1-"))
        payload = json.loads(base64.b64decode(url.split(",", 1)[1]).decode())
        self.assertEqual(suite.SAMPLE_ROWS, len(payload))
        self.assertTrue(url.startswith("data:application/json;base64,"))


if __name__ == "__main__":
    unittest.main()
