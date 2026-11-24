"""Scenarios of the ebx-connectors integration suite (audit C-09): they run real requests against a running EBX.

Everything the suite creates carries the run prefix (itest-<timestamp>-) and is registered in a Cleanup the moment it
exists; the clean-up runs even after a failure and never touches anything that does not carry the prefix or that the
suite did not create itself. Standard library only.
"""
import base64
import json
import re

from client import MCP_PATH, REST, Client, DataServices, xq

READ, RUN, MANAGE = "READ", "RUN", "MANAGE"
SAMPLE_ROWS = 5
DS = "Connectors"          # dataspace (without the B prefix) and dataset of the module
TARGET_TABLE = "ConnectorCategory"
TARGET_PATH = "/root/ConnectorCategory"

# What EBX itself answers when the session has no permission on the dataspace or dataset (not a module refusal).
EBX_BLOCK = re.compile(r"(?is)(access to .{0,120}(dataspace|dataset).{0,200}(not allowed|denied|forbidden)|(dataspace|dataset)[^\n]{0,160}not allowed)")
MODULE_REFUSAL = "Connectors right"


class Report(object):
    """One PASS / FAIL / SKIPPED line per check."""

    def __init__(self, write):
        self.write = write
        self.passes = 0
        self.fails = 0
        self.skips = 0

    def passed(self, name, detail=""):
        self.passes += 1
        self.write("PASS: %s%s" % (name, " (%s)" % detail if detail else ""))

    def failed(self, name, detail=""):
        self.fails += 1
        self.write("FAIL: %s%s" % (name, " - %s" % detail if detail else ""))

    def skipped(self, name, reason):
        self.skips += 1
        self.write("SKIPPED: %s - %s" % (name, reason))

    def check(self, name, ok, detail_ok="", detail_ko=""):
        if ok:
            self.passed(name, detail_ok)
        else:
            self.failed(name, detail_ko)
        return bool(ok)

    def exit_code(self):
        """1 when a check failed, else 2 when something did not run (a run that did not run everything has not passed), else 0."""
        if self.fails:
            return 1
        return 2 if self.skips else 0


class Cleanup(object):
    """What the run created, removed in reverse order, whatever happened; an action that failed stays pending and is retried."""

    def __init__(self):
        self.items = []

    def add(self, description, action):
        item = {"description": description, "action": action, "done": False, "error": None}
        self.items.append(item)
        return item

    def run_item(self, item):
        """True when the item is (now) done."""
        if item["done"]:
            return True
        try:
            item["action"]()
            item["done"] = True
            item["error"] = None
        except Exception as ex:  # a failure of one removal never stops the others
            item["error"] = "%s: %s" % (type(ex).__name__, ex)
        return item["done"]

    def run(self):
        """Runs every pending action, newest first; returns the (description, error) of those that failed."""
        failures = []
        for item in reversed(self.items):
            if not self.run_item(item):
                failures.append((item["description"], item["error"]))
        return failures


class Ctx(object):
    def __init__(self, base, admin, norole, report, prefix, rights_wait, sleep):
        self.base = base
        self.admin = admin
        self.norole = norole
        self.report = report
        self.prefix = prefix
        self.rights_wait = rights_wait
        self.sleep = sleep
        self.ds = DataServices(admin)
        self.ds_no = DataServices(norole) if norole else None
        self.cleanup = Cleanup()
        self.target_ds = None
        self.scratch_dataspace = False
        self.ids = {}                # fixture name -> connector id or mapping id
        self.mapping_ids = []        # every mapping the run created, for the final verification
        self.run_ids = []
        self.rights_item = None
        self.initial_rights = None
        self.status_json = None
        self.real_run_id = None
        self.abort = False

    def pid(self, suffix):
        return self.prefix + suffix


# ----------------------------------------------------------------------------------------------------------------- helpers

def mcp(client, method, params=None):
    response = client.post(MCP_PATH, {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}})
    return response, (response.json if isinstance(response.json, dict) else {})


def tool_names(client):
    """(Response, names) - names is None when tools/list was refused or unreadable."""
    response, js = mcp(client, "tools/list")
    result = js.get("result")
    if response.status == 200 and isinstance(result, dict) and isinstance(result.get("tools"), list):
        return response, [t.get("name") for t in result["tools"] if isinstance(t, dict)]
    return response, None


def tool_call(client, name, arguments):
    """(Response, data) - data is the structured answer of the tool; {'error': text} when refused or unreachable."""
    response, js = mcp(client, "tools/call", {"name": name, "arguments": arguments})
    result = js.get("result")
    if isinstance(result, dict):
        data = result.get("structuredContent")
        if not isinstance(data, dict):
            try:
                data = json.loads(result["content"][0]["text"])
            except Exception:
                data = {}
        return response, data
    error = js.get("error")
    if isinstance(error, dict):
        return response, {"error": str(error.get("message", ""))}
    return response, {"error": "HTTP %s %s" % (response.status, response.brief(160))}


def refused_by_module(response, data=None):
    """True when the module itself refused for lack of a Connectors right (not EBX, not a missing route)."""
    if response.status == 403 and MODULE_REFUSAL in response.text:
        return True
    return bool(data) and MODULE_REFUSAL in str(data.get("error", ""))


def write_refused(response):
    """A write that was refused or hidden: a 4xx other than 401 (401 would mean the login itself is wrong)."""
    return 400 <= response.status < 500 and response.status != 401


def ebx_blocked(response):
    """True when EBX (not the module) refused the session on the dataspace or dataset."""
    return response.status >= 400 and MODULE_REFUSAL not in response.text and bool(EBX_BLOCK.search(response.text))


def sample_rows(prefix, count=SAMPLE_ROWS):
    return [{"code": "%sc%d" % (prefix, i), "label": "itest label %d" % i} for i in range(1, count + 1)]


def data_url(payload):
    raw = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
    return "data:application/json;base64," + base64.b64encode(raw).decode("ascii")


def feed_document(ctx, label, connector, description="Scratch feed of the integration suite"):
    """A mapping document for connectors_mapping_upsert: reads the connection's JSON file, writes ConnectorCategory rows."""
    return {
        "connector": connector, "operation": "read", "direction": "INBOUND", "label": label, "description": description,
        "dataspace": ctx.target_ds, "dataset": DS, "tablePath": TARGET_PATH,
        "itemsPath": "rows", "keyPredicate": "./code='${code}'", "createIfMissing": True, "provenance": False,
        "inputs": {"path": "sample.json"},
        "rules": [
            {"sourcePath": "code", "targetPath": "./code", "policy": "IF_EMPTY"},
            {"sourcePath": "label", "targetPath": "./label", "policy": "ALWAYS"},
        ],
    }


def delete_ok(response, what):
    """A removal that is done, or that found nothing to remove, passes; anything else is an error."""
    if response.status not in (200, 204, 404):
        raise RuntimeError("%s -> HTTP %s %s" % (what, response.status, response.brief(160)))


def delete_row(ctx, path, what):
    delete_ok(ctx.admin.delete(path), what)


def rows_with_prefix(ctx, dataspace, table, field, prefix=None, client_ds=None):
    """(Response, rows) of the rows whose field starts with the run prefix, checked again on the client side."""
    wanted = prefix or ctx.prefix
    response, rows = (client_ds or ctx.ds).list(dataspace, table, "starts-with(./%s,%s)" % (field, xq(wanted)))
    if rows is None:
        return response, None
    return response, [r for r in rows if str(r.get(field, "")).startswith(wanted)]


def purge_run(ctx, run_id):
    """Removes the log lines and the run row of a run this suite started; nothing else."""
    response, rows = ctx.ds.list(DS, "SyncLog", "./runId=" + xq(run_id))
    if rows is None:
        raise RuntimeError("cannot list the log lines of run %s: HTTP %s %s" % (run_id, response.status, response.brief(120)))
    for row in rows:
        if row.get("runId") != run_id:
            continue
        path = row.path or (ctx.ds.row_path(DS, "SyncLog", row.get("id")) if row.get("id") is not None else None)
        if path:
            delete_row(ctx, path, "delete log line of run " + run_id)
    delete_row(ctx, ctx.ds.row_path(DS, "ExecutionRun", run_id), "delete run " + run_id)


def track_run(ctx, run_id):
    if run_id and run_id not in ctx.run_ids:
        ctx.run_ids.append(run_id)
        ctx.cleanup.add("log lines and row of run " + run_id, lambda: purge_run(ctx, run_id))


def purge_prefixed_logs(ctx):
    """Log lines that name the scratch dataspace (a run whose id the response never gave)."""
    response, rows = rows_with_prefix(ctx, DS, "SyncLog", "dataspace")
    if rows is None:
        raise RuntimeError("cannot list the log lines of the scratch dataspace: HTTP %s %s" % (response.status, response.brief(120)))
    for row in rows:
        path = row.path or (ctx.ds.row_path(DS, "SyncLog", row.get("id")) if row.get("id") is not None else None)
        if path:
            delete_row(ctx, path, "delete log line of the scratch dataspace")


def purge_category_rows(ctx, dataspace):
    response, rows = rows_with_prefix(ctx, dataspace, TARGET_TABLE, "code")
    if rows is None:
        raise RuntimeError("cannot list the scratch category rows: HTTP %s %s" % (response.status, response.brief(120)))
    for row in rows:
        path = row.path or ctx.ds.row_path(dataspace, TARGET_TABLE, row.get("code"))
        delete_row(ctx, path, "delete scratch row " + str(row.get("code")))


def close_dataspace(ctx, key):
    response = ctx.admin.post("/ebx-dataservices/rest/data/v1/B%s:close" % key, form={"deleteHistoryOnClose": "true", "deleteDataOnClose": "true"})
    if response.status not in (200, 204, 404):
        raise RuntimeError("close dataspace %s -> HTTP %s %s" % (key, response.status, response.brief(160)))


def delete_mapping_cleanup(ctx, mapping_id):
    response = ctx.admin.delete("%s/mappings/%s" % (REST, mapping_id))
    if response.status not in (200, 404):
        raise RuntimeError("delete mapping %s -> HTTP %s %s" % (mapping_id, response.status, response.brief(160)))


def create_connection(ctx, suffix, ctype, base_url, extra=None):
    """Creates a scratch connection through Data Services; returns (Response, id)."""
    cid = ctx.pid(suffix)
    fields = {"id": cid, "label": cid, "description": "Created by the integration suite; removed at the end of the run.",
              "type": ctype, "baseUrl": base_url, "active": True}
    fields.update(extra or {})
    response = ctx.ds.create(DS, "Connector", fields)
    if response.status == 201:
        ctx.cleanup.add("connection " + cid, lambda: delete_row(ctx, ctx.ds.row_path(DS, "Connector", cid), "delete connection " + cid))
    return response, cid


def upsert_mapping(client, document):
    """(Response, data) of connectors_mapping_upsert."""
    return tool_call(client, "connectors_mapping_upsert", {"mapping": document})


def target_rows(ctx):
    """The ConnectorCategory rows of the run in the target dataspace: (Response, {code: label}) or (Response, None)."""
    response, rows = rows_with_prefix(ctx, ctx.target_ds, TARGET_TABLE, "code")
    if rows is None:
        return response, None
    return response, dict((r.get("code"), r.get("label")) for r in rows)


def sync(client, mapping_id, dry_run=False, max_items=100):
    return client.post("%s/sync/%s?max=%d%s" % (REST, mapping_id, max_items, "&dryRun=true" if dry_run else ""))


def short(text, limit=160):
    text = re.sub(r"\s+", " ", str(text)).strip()
    return text if len(text) <= limit else text[:limit] + "..."


# ----------------------------------------------------------------------------------------------------------------- scenarios

def scenario_1_status(ctx):
    """S1: the status route and the MCP endpoint answer, with a version."""
    rep = ctx.report
    status = ctx.admin.get(REST + "/status")
    if status.status == 401:
        rep.failed("S1 status answers", "HTTP 401: EBX_AUTH is refused (wrong login or password); nothing else is run")
        ctx.abort = True
        return
    if status.status == 0:
        rep.failed("S1 status answers", "the server does not answer (%s); nothing else is run" % status.brief(120))
        ctx.abort = True
        return
    js = status.json if isinstance(status.json, dict) else {}
    ctx.status_json = js
    missing = [k for k in ("module", "version", "installation", "egress", "retention", "runs", "asyncPool") if k not in js]
    rep.check("S1.1 status route answers 200 with the expected blocks", status.status == 200 and not missing,
              "HTTP 200", "HTTP %s, missing %s: %s" % (status.status, missing, status.brief(120)))
    rep.check("S1.2 status names the module and an installed model", js.get("module") == "ebx-connectors"
              and bool((js.get("installation") or {}).get("datasetPresent")) and bool((js.get("installation") or {}).get("modelPresent")),
              "module ebx-connectors, dataset and model present", "module=%r installation=%r" % (js.get("module"), js.get("installation")))
    version = (js.get("version") or {}).get("version")
    rep.check("S1.3 a version is reported", bool(version) and isinstance(version, str),
              "%s%s" % (version, " - not a packaged build" if version == "unknown" else ""), "version block: %r" % js.get("version"))
    response, info = mcp(ctx.admin, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "ebx-connectors-itest", "version": "1"}})
    served = ((info.get("result") or {}).get("serverInfo") or {}).get("version")
    rep.check("S1.4 MCP initialize answers the status version", response.status == 200 and served == version and bool(served),
              "serverInfo.version %s" % served, "HTTP %s, serverInfo.version=%r, status says %r" % (response.status, served, version))
    response, names = tool_names(ctx.admin)
    wanted = ("connectors_help", "connectors_list", "connectors_sync", "connectors_mapping_upsert", "connectors_mapping_delete")
    rep.check("S1.5 MCP tools/list lists the tools of an administrator", names is not None and all(w in names for w in wanted),
              "%d tools" % len(names or []), "HTTP %s, tools %s" % (response.status, "none" if names is None else "lack " + str([w for w in wanted if w not in names])))


def scenario_setup(ctx):
    """S0: the scratch objects the other scenarios use; each one is registered for removal when it exists."""
    rep = ctx.report
    rows = sample_rows(ctx.prefix)
    # the target of the feed: a scratch dataspace (child of Connectors) holds the written rows and is closed at the end
    key = ctx.pid("ds")
    body = {"content": {"key": "B" + key, "owner": "Badministrator",
                        "documentation": [{"locale": "en-US", "label": ctx.pid("scratch"), "description": "Created by the ebx-connectors integration suite; closed at the end of the run."}],
                        "dataspaceKeyToCopyPermissionsFrom": "B" + DS}}
    response = ctx.admin.post("/ebx-dataservices/rest/data/v1/B%s:createDataspace" % DS, body)
    if response.status in (200, 201, 204):
        ctx.cleanup.add("scratch dataspace " + key, lambda: close_dataspace(ctx, key))
        ctx.cleanup.add("log lines that name the scratch dataspace", lambda: purge_prefixed_logs(ctx))
        ctx.target_ds = key
        ctx.scratch_dataspace = True
        rep.passed("S0.1 scratch dataspace created under Connectors", key)
    else:
        ctx.target_ds = DS
        ctx.cleanup.add("scratch rows of %s in %s" % (TARGET_TABLE, DS), lambda: purge_category_rows(ctx, DS))
        rep.passed("S0.1 scratch dataspace not available (HTTP %s %s): the feed writes %s rows with the run prefix in %s instead"
                   % (response.status, short(response.text, 80), TARGET_TABLE, DS))
    # a scratch connection that serves a small JSON file from its own configuration: no network
    response, cid = create_connection(ctx, "src", "sample-file", "https://%ssrc.sample-file.local/data" % ctx.prefix, {"sampleData": data_url(rows)})
    if rep.check("S0.2 scratch sample-file connection created", response.status == 201, cid, "HTTP %s %s" % (response.status, response.brief(200))):
        ctx.ids["src"] = cid
    # a scratch connection whose stored file cannot be read: a source read failure
    response, bid = create_connection(ctx, "bad", "sample-file", "https://%sbad.sample-file.local/data" % ctx.prefix,
                                      {"sampleData": "data:application/json;base64,@@@not-base64@@@"})
    if rep.check("S0.3 scratch connection with an unreadable file created", response.status == 201, bid, "HTTP %s %s" % (response.status, response.brief(200))):
        ctx.ids["bad"] = bid
    for name, suffix, connector in (("feed", "feed", ctx.ids.get("src")), ("badfeed", "badfeed", ctx.ids.get("bad"))):
        if not connector:
            rep.skipped("S0.%s scratch mapping" % name, "its connection was not created")
            continue
        label = ctx.pid(suffix)
        response, data = upsert_mapping(ctx.admin, feed_document(ctx, label, connector))
        mapping_id = data.get("mapping")
        if mapping_id:
            ctx.ids[name] = str(mapping_id)
            ctx.mapping_ids.append(str(mapping_id))
            ctx.cleanup.add("mapping " + label, lambda m=str(mapping_id): delete_mapping_cleanup(ctx, m))
        rep.check("S0.%s scratch mapping %s created" % ("4" if name == "feed" else "5", label), bool(mapping_id) and "error" not in data,
                  "id %s" % mapping_id, "HTTP %s %s" % (response.status, short(data.get("error") or response.brief(200))))


def scenario_2_no_role(ctx):
    """S2: an account with no Connectors right reaches nothing."""
    rep = ctx.report
    if not ctx.norole:
        rep.skipped("S2 account without a Connectors role", "EBX_AUTH_NOROLE is not set")
        return
    if ctx.initial_rights is not None and "Beveryone" in ctx.initial_rights:
        rep.skipped("S2 account without a Connectors role", "an AccessRight row gives every account a right (Beveryone), so no account is without one on this instance")
        return
    no = ctx.norole
    response = no.get(REST + "/status")
    rep.check("S2.1 status refused", response.status == 403, "HTTP 403", "HTTP %s %s" % (response.status, response.brief(120)))
    response, names = tool_names(no)
    rep.check("S2.2 tools/list is empty or refused", response.status == 403 or names == [],
              "HTTP %s, %s" % (response.status, "refused" if names is None else "no tool"),
              "HTTP %s with %s" % (response.status, "tools " + str(names[:5]) if names else response.brief(120)))
    response, data = tool_call(no, "connectors_sync", {"mapping": ctx.ids.get("feed", "0"), "dryRun": True})
    rep.check("S2.3 a tool call is refused", response.status == 403 or refused_by_module(response, data),
              "refused", "HTTP %s %s" % (response.status, short(data.get("error") or data)))
    cid = ctx.ids.get("src")
    if not cid:
        rep.skipped("S2.4 no-op PUT on a connection", "no scratch connection (setup failed)")
        return
    response = ctx.ds_no.update(DS, "Connector", cid, {"description": "itest: refused edit"})
    rep.check("S2.4 PUT on a connection is refused or hidden", write_refused(response),
              "HTTP %s" % response.status, "HTTP %s %s" % (response.status, response.brief(160)))


def _set_right(ctx, level):
    """Gives (first call) or changes the temporary right of everyone; waits for the module's 5 s cache."""
    ds = ctx.ds
    if ctx.rights_item is None:
        response = ds.create(DS, "AccessRight", {"profile": "Beveryone", "right": level, "comment": ctx.pid("temporary right of the integration suite")})
        if response.status != 201:
            raise RuntimeError("cannot add the AccessRight row: HTTP %s %s" % (response.status, response.brief(160)))
        ctx.rights_item = ctx.cleanup.add("temporary AccessRight row for Beveryone",
                                          lambda: delete_row(ctx, ds.row_path(DS, "AccessRight", "Beveryone"), "delete the temporary AccessRight row"))
    else:
        response = ds.update(DS, "AccessRight", "Beveryone", {"right": level})
        if response.status not in (200, 204):
            raise RuntimeError("cannot change the AccessRight row to %s: HTTP %s %s" % (level, response.status, response.brief(160)))
    ctx.sleep(ctx.rights_wait)


def _remove_rights(ctx):
    if ctx.rights_item is not None:
        if not ctx.cleanup.run_item(ctx.rights_item):
            ctx.report.failed("S3.9 temporary AccessRight row removed", ctx.rights_item["error"])
        else:
            ctx.report.passed("S3.9 temporary AccessRight row removed")


def scenario_3_rights(ctx):
    """S3: the right a Beveryone row gives decides what a no-role account may do (READ < RUN < MANAGE)."""
    rep = ctx.report
    if not ctx.norole:
        rep.skipped("S3 rights by level", "EBX_AUTH_NOROLE is not set")
        return
    if not ctx.ids.get("feed") or not ctx.ids.get("src"):
        rep.skipped("S3 rights by level", "the scratch connection or mapping was not created (setup failed)")
        return
    if ctx.ds.exists(DS, "AccessRight", "Beveryone"):
        rep.skipped("S3 rights by level", "an AccessRight row for Beveryone exists already; the suite never changes a row it did not create")
        return
    no = ctx.norole
    try:
        _set_right(ctx, READ)
        listing = no.get(REST + "/connectors")
        if ebx_blocked(listing):
            rep.skipped("S3 rights by level", "the account has no EBX permission on the Connectors dataspace or dataset (%s); the module right alone is not enough: %s"
                        % ("grant it read access to run this scenario", short(listing.text, 140)))
            return
        # ---- READ
        js = listing.json if isinstance(listing.json, dict) else {}
        ids = [c.get("id") for c in js.get("connectors", []) if isinstance(c, dict)]
        rep.check("S3.1 READ: connections are listed", listing.status == 200 and ctx.ids["src"] in ids,
                  "HTTP 200, %d connections" % len(ids), "HTTP %s, scratch connection %s" % (listing.status, "present" if ctx.ids["src"] in ids else "absent"))
        response, names = tool_names(no)
        rep.check("S3.2 READ: tools/list has the read tools only", names is not None and "connectors_list" in names
                  and "connectors_sync" not in names and "connectors_mapping_upsert" not in names, "%d tools" % len(names or []),
                  "HTTP %s, %s" % (response.status, "refused" if names is None else "tools %s" % sorted(names)))
        response = sync(no, ctx.ids["feed"], dry_run=True)
        rep.check("S3.3 READ: a synchronisation is refused", response.status == 403 and MODULE_REFUSAL in response.text and RUN in response.text,
                  "HTTP 403, needs RUN", "HTTP %s %s" % (response.status, response.brief(160)))
        response, data = tool_call(no, "connectors_sync", {"mapping": ctx.ids["feed"], "dryRun": True})
        rep.check("S3.4 READ: connectors_sync is refused", refused_by_module(response, data), "refused", "HTTP %s %s" % (response.status, short(data.get("error") or data)))
        # ---- RUN
        _set_right(ctx, RUN)
        response, names = tool_names(no)
        rep.check("S3.5 RUN: tools/list adds the run tools, not the configuration tools", names is not None and "connectors_sync" in names
                  and "connectors_mapping_upsert" not in names, "%d tools" % len(names or []), "HTTP %s, %s" % (response.status, "refused" if names is None else sorted(names)))
        response = sync(no, ctx.ids["feed"], dry_run=True)
        if ebx_blocked(response):
            rep.skipped("S3.6 RUN: a dry-run feed", "EBX refuses the session on the dataset: " + short(response.text, 120))
        else:
            js = response.json if isinstance(response.json, dict) else {}
            track_run(ctx, js.get("runId"))
            rep.check("S3.6 RUN: a dry-run feed runs", response.status == 200 and js.get("dryRun") is True and js.get("count") == SAMPLE_ROWS,
                      "HTTP 200, %s items read, nothing written" % js.get("count"), "HTTP %s %s" % (response.status, response.brief(200)))
        response = ctx.ds_no.update(DS, "Connector", ctx.ids["src"], {"description": "itest: edit with RUN"})
        rep.check("S3.7 RUN: editing a connection is refused", write_refused(response), "HTTP %s" % response.status,
                  "HTTP %s %s" % (response.status, response.brief(160)))
        response, data = upsert_mapping(no, feed_document(ctx, ctx.pid("rights-run"), ctx.ids["src"]))
        if data.get("mapping"):                       # it must not have been created: clean up and fail
            ctx.mapping_ids.append(str(data["mapping"]))
            ctx.cleanup.add("mapping created by a RUN account", lambda m=str(data["mapping"]): delete_mapping_cleanup(ctx, m))
        rep.check("S3.8 RUN: connectors_mapping_upsert is refused", refused_by_module(response, data) and not data.get("mapping"), "refused",
                  "HTTP %s %s" % (response.status, short(data.get("error") or data)))
        # ---- MANAGE
        _set_right(ctx, MANAGE)
        response, names = tool_names(no)
        rep.check("S3.10 MANAGE: tools/list adds the configuration tools", names is not None and "connectors_mapping_upsert" in names
                  and "connectors_mapping_delete" in names, "%d tools" % len(names or []), "HTTP %s, %s" % (response.status, "refused" if names is None else sorted(names)))
        label = ctx.pid("rights-manage")
        response, data = upsert_mapping(no, feed_document(ctx, label, ctx.ids["src"]))
        created = data.get("mapping")
        if created:
            ctx.mapping_ids.append(str(created))
            ctx.cleanup.add("mapping " + label, lambda m=str(created): delete_mapping_cleanup(ctx, m))
        if not created and MODULE_REFUSAL not in str(data.get("error", "")):
            rep.skipped("S3.11 MANAGE: create, edit and delete a scratch mapping",
                        "the module accepted the right but EBX refused the write (the account needs EBX write permission on the Connectors dataspace): %s" % short(data.get("error") or data))
        else:
            rep.check("S3.11 MANAGE: a scratch mapping is created", bool(created) and data.get("created") is True, "id %s" % created,
                      "HTTP %s %s" % (response.status, short(data.get("error") or data)))
            if created:
                changed = feed_document(ctx, label, ctx.ids["src"], description="edited by the integration suite")
                response, data = upsert_mapping(no, changed)
                rep.check("S3.12 MANAGE: the mapping is edited", str(data.get("mapping")) == str(created) and data.get("created") is False and "error" not in data,
                          "same id, updated", "HTTP %s %s" % (response.status, short(data.get("error") or data)))
                response = no.delete("%s/mappings/%s" % (REST, created))
                gone = ctx.admin.get(REST + "/mappings")
                still = [m for m in (gone.json or {}).get("mappings", []) if str(m.get("id")) == str(created)] if isinstance(gone.json, dict) else None
                rep.check("S3.13 MANAGE: the mapping is deleted", response.status == 200 and still == [], "HTTP 200, gone",
                          "HTTP %s %s, still listed: %s" % (response.status, response.brief(120), bool(still)))
        response = ctx.ds_no.update(DS, "Connector", ctx.ids["src"], {"description": "itest: edit with MANAGE"})
        if response.status in (200, 204):
            rep.passed("S3.14 MANAGE: a connection is edited", "HTTP %s" % response.status)
        elif response.status == 403:
            rep.skipped("S3.14 MANAGE: a connection is edited", "HTTP 403: the module allows it but EBX may still deny the write (no EBX write permission on the dataspace); cannot tell the two apart")
        else:
            rep.failed("S3.14 MANAGE: a connection is edited", "HTTP %s %s" % (response.status, response.brief(160)))
    finally:
        _remove_rights(ctx)


def scenario_4_feed(ctx):
    """S4: a feed end to end on the scratch mapping: dry run, real run, idempotence, source failure."""
    rep = ctx.report
    mapping = ctx.ids.get("feed")
    if not mapping:
        rep.skipped("S4 feed end to end", "the scratch mapping was not created (setup failed)")
        return
    admin = ctx.admin
    n = SAMPLE_ROWS
    expected = dict((r["code"], r["label"]) for r in sample_rows(ctx.prefix))
    where = "scratch dataspace" if ctx.scratch_dataspace else "%s (prefixed rows)" % DS
    # dry run
    response = sync(admin, mapping, dry_run=True)
    js = response.json if isinstance(response.json, dict) else {}
    track_run(ctx, js.get("runId"))
    _, rows = target_rows(ctx)
    rep.check("S4.1 dry run counts %d items and writes nothing" % n, response.status == 200 and js.get("dryRun") is True and js.get("count") == n
              and js.get("errors") == 0 and rows == {}, "HTTP 200, count %s, would create %s, rows written 0" % (js.get("count"), js.get("created")),
              "HTTP %s %s; rows in target: %s" % (response.status, response.brief(200), "unreadable" if rows is None else len(rows)))
    # real run
    response = sync(admin, mapping)
    js = response.json if isinstance(response.json, dict) else {}
    track_run(ctx, js.get("runId"))
    ctx.real_run_id = js.get("runId")
    rep.check("S4.2 real run creates %d rows" % n, response.status == 200 and js.get("created") == n and js.get("errors") == 0 and js.get("status") in ("OK", "DONE"),
              "HTTP 200, status %s, created %s" % (js.get("status"), js.get("created")), "HTTP %s %s" % (response.status, response.brief(240)))
    _, rows = target_rows(ctx)
    rep.check("S4.3 the rows are in the %s with the right values" % where, rows == expected, "%d rows read back through Data Services" % n,
              "read back %s, expected %d rows" % ("nothing (unreadable)" if rows is None else "%d rows %s" % (len(rows), "with other values" if len(rows) == n else ""), n))
    # second run
    response = sync(admin, mapping)
    js = response.json if isinstance(response.json, dict) else {}
    track_run(ctx, js.get("runId"))
    _, rows = target_rows(ctx)
    rep.check("S4.4 second run is idempotent (0 created)", response.status == 200 and js.get("created") == 0 and js.get("errors") == 0 and rows == expected,
              "HTTP 200, created 0, updated %s, noChange %s" % (js.get("updated"), js.get("noChange")),
              "HTTP %s %s; %s rows in target" % (response.status, response.brief(240), "?" if rows is None else len(rows)))
    # source failure
    bad = ctx.ids.get("badfeed")
    if not bad:
        rep.skipped("S4.5 a source read failure ends ERROR", "the failing scratch mapping was not created")
        return
    response = sync(admin, bad)
    js = response.json if isinstance(response.json, dict) else {}
    track_run(ctx, js.get("runId"))
    _, rows = target_rows(ctx)
    rep.check("S4.5 a source read failure ends ERROR with HTTP 422", response.status == 422 and js.get("status") == "ERROR" and rows == expected,
              "HTTP 422, status ERROR, target untouched", "HTTP %s %s; target %s" % (response.status, response.brief(240), "?" if rows is None else "%d rows" % len(rows)))


def scenario_5_posture(ctx):
    """S5: retention and run status fields are present; a private address is refused."""
    rep = ctx.report
    js = ctx.status_json or {}
    retention = js.get("retention") or {}
    egress = js.get("egress") or {}
    rep.check("S5.1 status carries the retention fields", all(k in retention for k in ("enabled", "days", "onByDefault")),
              "enabled=%s days=%s" % (retention.get("enabled"), retention.get("days")), "retention block: %r" % retention)
    rep.check("S5.2 status carries the outbound posture", all(k in egress for k in ("allowListSet", "blockPrivateHosts", "privateExceptions")),
              "blockPrivateHosts=%s privateExceptions=%s" % (egress.get("blockPrivateHosts"), egress.get("privateExceptions")), "egress block: %r" % egress)
    run_id = ctx.real_run_id
    if run_id:
        response = ctx.admin.get("%s/runs/%s?callsMax=0" % (REST, run_id))
        execution = (response.json or {}).get("execution") if isinstance(response.json, dict) else None
        execution = execution if isinstance(execution, dict) else {}
        rep.check("S5.3 a run reports its status and counters", response.status == 200 and execution.get("status") == "DONE" and execution.get("created") == SAMPLE_ROWS
                  and all(k in execution for k in ("startedAt", "updatedAt", "errorCount", "noChange")),
                  "status DONE, created %s" % execution.get("created"), "HTTP %s %s" % (response.status, response.brief(240)))
    else:
        rep.skipped("S5.3 a run reports its status and counters", "no real run was made (S4 did not run)")
    if egress.get("blockPrivateHosts") is False:
        rep.skipped("S5.4 a private address is refused", "status says blockPrivateHosts=false on this instance")
        return
    response, cid = create_connection(ctx, "priv", "rest", "http://127.0.0.2:1/")
    if response.status != 201:
        text = response.text
        rep.check("S5.4 a connection to 127.0.0.2:1 is refused", 400 <= response.status < 500 and re.search(r"(?i)refused|loopback|private", text),
                  "refused when saved: HTTP %s %s" % (response.status, short(text, 120)), "HTTP %s %s" % (response.status, response.brief(200)))
        return
    response, data = tool_call(ctx.admin, "connectors_call", {"connector": cid, "operation": "ping"})
    error = str(data.get("error", ""))
    if re.search(r"(?i)refused", error) and re.search(r"(?i)loopback or private", error):
        rep.passed("S5.4 a connection to 127.0.0.2:1 is refused with the documented message", short(error, 200))
    elif error:
        rep.skipped("S5.4 a private address is refused", "127.0.0.2 may be listed in ebx.connectors.egress.privateExceptions; the answer was: " + short(error, 160))
    else:
        rep.failed("S5.4 a connection to 127.0.0.2:1 is refused", "the call went through: %s" % short(data, 160))


def scenario_7_boundaries(ctx):
    """S7: the security boundaries (audit C2-21): a refused right on every entry, the webhook, the egress and clear-secret guards."""
    rep = ctx.report
    anonymous = Client(ctx.base)
    feed = ctx.ids.get("feed", "0")
    # the webhook has one mode, an EBX session with the RUN right (C2-10): nothing gets in without credentials
    response = anonymous.post("%s/webhook/%s?dryRun=true" % (REST, feed), {"items": []})
    rep.check("S7.1 a webhook post without credentials is refused", response.status == 401, "HTTP 401", "HTTP %s %s" % (response.status, response.brief(160)))
    response = anonymous.post("%s/webhook/%s?dryRun=true" % (REST, feed), {"items": []}, headers={"X-Webhook-Secret": "guess", "X-Webhook-Signature": "sha256=00",
                                                                                                  "X-Webhook-Timestamp": "1"})
    rep.check("S7.2 the removed secret and signature modes open nothing", response.status == 401, "HTTP 401", "HTTP %s %s" % (response.status, response.brief(160)))
    response = anonymous.get("%s/webhook/%s" % (REST, feed))
    rep.check("S7.3 the webhook health check needs credentials too", response.status == 401, "HTTP 401", "HTTP %s %s" % (response.status, response.brief(160)))
    no_role = bool(ctx.norole) and not (ctx.initial_rights is not None and "Beveryone" in ctx.initial_rights)
    if not no_role:
        rep.skipped("S7.4 a right refused on every entry", "no account without a Connectors right (EBX_AUTH_NOROLE unset, or a Beveryone row exists)")
    else:
        no = ctx.norole
        response = no.post("%s/webhook/%s?dryRun=true" % (REST, feed), {"items": []})
        rep.check("S7.4 the webhook refuses an account without the RUN right", response.status == 403, "HTTP 403", "HTTP %s %s" % (response.status, response.brief(160)))
        response = no.get("%s/webhook/%s" % (REST, feed))
        rep.check("S7.4b GET /webhook/<feed> refuses an account without the RUN right (C2-16)", response.status == 403, "HTTP 403", "HTTP %s %s" % (response.status, response.brief(160)))
        response = no.get("%s/webhook/%s" % (REST, feed), headers={"Accept-Language": "fr"})
        rep.check("S7.4c the refusal reads French with Accept-Language: fr (C2-17)", response.status == 403 and re.search(r"(?i)refus", response.brief(400)) is not None,
                  "HTTP 403, French text", "HTTP %s %s" % (response.status, response.brief(160)))
        entries = (("GET", "/connectors"), ("GET", "/mappings"), ("POST", "/sync/" + feed), ("DELETE", "/mappings/" + feed), ("GET", "/runs/none0"))
        for method, path in entries:
            response = no.request(method, REST + path)
            rep.check("S7.5 %s %s is refused without a right" % (method, path.split("/" + feed)[0] or path), response.status == 403, "HTTP 403",
                      "HTTP %s %s" % (response.status, response.brief(160)))
        for tool, arguments in (("connectors_sync", {"mapping": feed}), ("connectors_mapping_delete", {"mapping": feed}), ("connectors_call", {"connector": "none", "operation": "ping"})):
            response, data = tool_call(no, tool, arguments)
            rep.check("S7.6 tool %s is refused without a right" % tool, response.status == 403 or refused_by_module(response, data), "refused",
                      "HTTP %s %s" % (response.status, short(data.get("error") or data)))
    # a secret stored in clear is refused at use, unless the instance opted out (C-31 and the secrets default)
    secrets = (ctx.status_json or {}).get("secrets") or {}
    if secrets.get("clearValuesAllowed") is not False:
        rep.skipped("S7.7 a clear secret is refused", "status does not say clearValuesAllowed=false on this instance")
        return
    response, cid = create_connection(ctx, "clear", "rest", "https://%sclear.example.invalid/" % ctx.prefix, {"password": "plain-text-of-the-suite"})
    if response.status != 201:
        rep.check("S7.7 a clear secret is refused", 400 <= response.status < 500, "refused when saved: HTTP %s" % response.status,
                  "HTTP %s %s" % (response.status, response.brief(160)))
        return
    response, data = tool_call(ctx.admin, "connectors_call", {"connector": cid, "operation": "ping"})
    error = str(data.get("error", ""))
    rep.check("S7.7 a connection holding a clear secret is refused at use", bool(re.search(r"(?i)clear text|in clear|stored in clear", error)),
              short(error, 160), "the answer was: %s" % short(data.get("error") or data, 160))


def scenario_6_verify(ctx):
    """S6: after the clean-up, nothing the run created remains."""
    rep = ctx.report
    ds = ctx.ds

    def none_left(name, response_rows, what):
        response, rows = response_rows
        if rows is None:
            rep.skipped(name, "cannot read %s: HTTP %s %s" % (what, response.status, response.brief(120)))
        else:
            rep.check(name, len(rows) == 0, "no %s left" % what, "%d %s left: %s" % (len(rows), what, [r.get("id") or r.get("code") or r.get("label") for r in rows][:5]))

    none_left("S6.1 no scratch connection", rows_with_prefix(ctx, DS, "Connector", "id"), "connection")
    none_left("S6.2 no scratch connection property", rows_with_prefix(ctx, DS, "ConnectorProperty", "connector"), "connection property")
    none_left("S6.3 no scratch mapping", rows_with_prefix(ctx, DS, "Mapping", "label"), "mapping")
    leftover = [m for m in ctx.mapping_ids if ds.exists(DS, "Mapping", m)]
    rep.check("S6.4 none of the mappings the run created exists", not leftover, "%d checked" % len(ctx.mapping_ids), "still present: %s" % leftover)
    none_left("S6.5 no scratch category row in the Connectors dataspace", rows_with_prefix(ctx, DS, TARGET_TABLE, "code"), "category row")
    none_left("S6.6 no log line of the scratch dataspace", rows_with_prefix(ctx, DS, "SyncLog", "dataspace"), "log line")
    none_left("S6.7 no field provenance of the scratch dataspace", rows_with_prefix(ctx, DS, "FieldProvenance", "dataspace"), "provenance row")
    runs = [r for r in ctx.run_ids if ds.exists(DS, "ExecutionRun", r)]
    rep.check("S6.8 none of the runs the run started remains", not runs, "%d run(s) removed" % len(ctx.run_ids), "still present: %s" % runs)
    response, rights = ds.list(DS, "AccessRight")
    if rights is None:
        rep.skipped("S6.9 the AccessRight table is as it was", "cannot read it: HTTP %s" % response.status)
    else:
        now = sorted(str(r.get("profile")) for r in rights)
        rep.check("S6.9 the AccessRight table is as it was", ctx.initial_rights is not None and now == ctx.initial_rights,
                  "%d rows, unchanged" % len(now), "before %s, now %s" % (ctx.initial_rights, now))
    if ctx.scratch_dataspace:
        response = ctx.admin.get("/ebx-dataservices/rest/data/v1/B%s" % ctx.target_ds)
        gone = response.status >= 400 or (response.status == 200 and re.search(r"(?i)closed", response.text) is not None)
        rep.check("S6.10 the scratch dataspace is closed", gone, "HTTP %s" % response.status, "HTTP %s: still reachable" % response.status)


def run_suite(ctx):
    """Runs every scenario, then the clean-up (always), then the verification. Returns the exit code."""
    rep = ctx.report
    try:
        scenario_1_status(ctx)
        if not ctx.abort:
            response, rights = ctx.ds.list(DS, "AccessRight")
            ctx.initial_rights = sorted(str(r.get("profile")) for r in rights) if rights is not None else None
            for name, fn in (("S0", scenario_setup), ("S2", scenario_2_no_role), ("S3", scenario_3_rights), ("S4", scenario_4_feed), ("S5", scenario_5_posture), ("S7", scenario_7_boundaries)):
                try:
                    fn(ctx)
                except Exception as ex:   # one broken scenario never stops the others or the clean-up
                    rep.failed("%s unexpected error" % name, "%s: %s" % (type(ex).__name__, short(ex, 200)))
    finally:
        failures = ctx.cleanup.run()
        total = len(ctx.cleanup.items)
        if failures:
            for description, error in failures:
                rep.failed("clean-up of %s" % description, error)
        else:
            rep.passed("clean-up removed what the run created", "%d item(s)" % total)
    if not ctx.abort:
        try:
            scenario_6_verify(ctx)
        except Exception as ex:
            rep.failed("S6 unexpected error", "%s: %s" % (type(ex).__name__, short(ex, 200)))
    return rep.exit_code()
