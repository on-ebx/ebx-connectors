"""HTTP and Data Services helpers of the ebx-connectors integration suite (audit C-09).

Standard library only. Credentials live in the Authorization header of a Client and nowhere else: they are never put
on a command line, in a URL, in a message or in an exception text.
"""
import base64
import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request

DS_ROOT = "/ebx-dataservices/rest/data/v1"
REST = "/ebx-connectors/rest/v1"
MCP_PATH = "/ebx-connectors/rest/mcp"


class Response(object):
    """One HTTP answer. status 0 means the server could not be reached (text holds the reason)."""

    def __init__(self, status, headers, text):
        self.status = status
        self.headers = headers or {}
        self.text = text or ""
        self._json = None
        self._parsed = False

    @property
    def json(self):
        """The body as JSON, None when it is not."""
        if not self._parsed:
            self._parsed = True
            try:
                self._json = json.loads(self.text) if self.text.strip() else None
            except ValueError:
                self._json = None
        return self._json

    def brief(self, limit=240):
        """A short, single-line excerpt of the body for a report line."""
        text = re.sub(r"\s+", " ", self.text).strip()
        return text if len(text) <= limit else text[:limit] + "..."


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """A redirect is an answer, never followed: credentials must not travel to another place."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client(object):
    """A caller of one EBX instance with one set of credentials (none for a public call)."""

    def __init__(self, base, auth=None, cacert=None, timeout=180, trace=None):
        self.base = base.rstrip("/")
        self._authorization = None
        if auth:
            self._authorization = "Basic " + base64.b64encode(auth.encode("utf-8")).decode("ascii")
        self.timeout = timeout
        self.trace = trace
        handlers = [_NoRedirect()]
        host = urllib.parse.urlparse(self.base).hostname or ""
        if host in ("localhost", "127.0.0.1", "::1"):
            handlers.append(urllib.request.ProxyHandler({}))   # this machine is never reached through a proxy
        if self.base.startswith("https://"):
            context = ssl.create_default_context(cafile=cacert) if cacert else ssl.create_default_context()
            handlers.append(urllib.request.HTTPSHandler(context=context))
        self._opener = urllib.request.build_opener(*handlers)

    def __repr__(self):
        return "Client(%s)" % self.base

    def request(self, method, path, json_body=None, form=None, headers=None, timeout=None):
        data = None
        hdrs = {"Accept": "application/json"}
        if json_body is not None:
            data = json.dumps(json_body).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
        elif form is not None:
            data = urllib.parse.urlencode(form).encode("utf-8")
            hdrs["Content-Type"] = "application/x-www-form-urlencoded"
        elif method in ("POST", "PUT", "PATCH"):
            data = b""
        if self._authorization:
            hdrs["Authorization"] = self._authorization
        if headers:
            hdrs.update(headers)
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=hdrs)
        try:
            raw = self._opener.open(request, timeout=timeout or self.timeout)
            response = Response(raw.status, {k.lower(): v for k, v in raw.headers.items()}, raw.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as err:
            try:
                body = err.read().decode("utf-8", "replace")
            except Exception:
                body = ""
            finally:
                err.close()
            response = Response(err.code, {k.lower(): v for k, v in err.headers.items()}, body)
        except (urllib.error.URLError, OSError, ValueError) as err:
            reason = getattr(err, "reason", err)
            response = Response(0, {}, "no answer: %s" % reason)
        if self.trace:
            self.trace("%s %s -> %s" % (method, path.split("?")[0], response.status))
        return response

    def get(self, path, **kw):
        return self.request("GET", path, **kw)

    def post(self, path, json_body=None, **kw):
        return self.request("POST", path, json_body=json_body, **kw)

    def put(self, path, json_body=None, **kw):
        return self.request("PUT", path, json_body=json_body, **kw)

    def delete(self, path, **kw):
        return self.request("DELETE", path, **kw)


def unwrap(value):
    """Data Services wraps every value in {"content": ...}: this removes the wrapping at every depth."""
    if isinstance(value, dict):
        if "content" in value and set(value.keys()) <= {"content", "label", "details", "meta"}:
            return unwrap(value["content"])
        return dict((k, unwrap(v)) for k, v in value.items())
    if isinstance(value, list):
        return [unwrap(v) for v in value]
    return value


def wrap(fields):
    """The Data Services envelope of a flat dict of field values."""
    return dict((k, {"content": v}) for k, v in fields.items())


def quote_pk(pk):
    return urllib.parse.quote(str(pk), safe="")


def xq(text):
    """An XPath string literal."""
    text = str(text)
    if "'" not in text:
        return "'" + text + "'"
    if '"' not in text:
        return '"' + text + '"'
    raise ValueError("a value with both quote kinds cannot be used in a filter")


class Row(object):
    """One row read through Data Services: its flat values, the path to address it and its label."""

    def __init__(self, content, path, label):
        self.content = content
        self.path = path
        self.label = label

    def get(self, name, default=None):
        value = self.content.get(name, default)
        return default if value is None else value


def _path_of(details):
    if not details:
        return None
    parsed = urllib.parse.urlparse(details)
    return parsed.path or None


def _next_page(js):
    meta = js.get("meta") if isinstance(js, dict) else None
    pagination = meta.get("pagination") if isinstance(meta, dict) else None
    nxt = pagination.get("nextPage") if isinstance(pagination, dict) else None
    if isinstance(nxt, dict):
        nxt = nxt.get("href")
    if isinstance(nxt, str) and nxt:
        parsed = urllib.parse.urlparse(nxt)
        return parsed.path + ("?" + parsed.query if parsed.query else "")
    return None


class DataServices(object):
    """The Data Services REST calls the suite needs, for one caller. Dataspace keys are given WITHOUT the B prefix."""

    def __init__(self, client, dataset="Connectors"):
        self.client = client
        self.dataset = dataset

    def table(self, dataspace, table):
        return "%s/B%s/%s/root/%s" % (DS_ROOT, urllib.parse.quote(dataspace, safe=""), self.dataset, table)

    def row_path(self, dataspace, table, pk):
        return "%s/%s" % (self.table(dataspace, table), quote_pk(pk))

    def list(self, dataspace, table, xpath=None, page_size=1000, max_pages=50):
        """(Response, [Row]) - the rows is None when the first page was refused. Follows the pagination links."""
        query = {"pageSize": str(page_size)}
        if xpath:
            query["filter"] = xpath
        path = self.table(dataspace, table) + "?" + urllib.parse.urlencode(query)
        rows = []
        first = None
        for _ in range(max_pages):
            response = self.client.get(path)
            if first is None:
                first = response
            js = response.json
            if response.status != 200 or not isinstance(js, dict):
                return response, (rows if rows else None)
            for item in js.get("rows") or []:
                content = unwrap(item.get("content")) if isinstance(item, dict) and "content" in item else unwrap(item)
                details = item.get("details") if isinstance(item, dict) else None
                rows.append(Row(content if isinstance(content, dict) else {}, _path_of(details), item.get("label") if isinstance(item, dict) else None))
            path = _next_page(js)
            if not path:
                break
        return first, rows

    def create(self, dataspace, table, fields):
        """POST one row. Returns the Response (201 when created)."""
        return self.client.post(self.table(dataspace, table), {"rows": [{"content": wrap(fields)}]})

    def update(self, dataspace, table, pk, fields):
        return self.client.put(self.row_path(dataspace, table, pk), {"content": wrap(fields)})

    def delete_path(self, path):
        return self.client.delete(path)

    def exists(self, dataspace, table, pk):
        return self.client.get(self.row_path(dataspace, table, pk)).status == 200
