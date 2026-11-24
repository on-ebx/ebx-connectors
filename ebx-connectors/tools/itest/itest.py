#!/usr/bin/env python3
"""Integration suite of the ebx-connectors module (audit C-09): real requests, real EBX transactions.

    EBX_AUTH=admin:password [EBX_AUTH_NOROLE=user:password] tools/itest.sh <base-url>      e.g. http://localhost:8080

It WRITES to the instance: it creates scratch connections, mappings and a scratch dataspace, runs a feed, and gives
everybody a temporary Connectors right for a few seconds. Use a disposable or staging instance. Everything it creates
carries the prefix itest-<timestamp>- and is removed at the end, also after a failure. See docs/OPERATIONS.md.

Credentials come from the environment ONLY, never from an argument; they are kept in memory, sent in an Authorization
header and never printed. Without EBX_AUTH nothing runs (exit 2). Other environment: ITEST_CACERT (a CA file for an
https URL; certificates are never switched off), ITEST_ALLOW_PLAIN_HTTP=1 (send credentials over http to a host that
is not this machine), ITEST_RIGHTS_WAIT (seconds to wait after a right changes, default 6.5: the module caches the
table for 5 s), ITEST_VERBOSE=1 (one line per request on stderr).

Exit code: 0 every check passed; 1 a check failed; 2 something did not run (missing credentials, or a scenario skipped).
"""
import os
import sys
import time
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from client import Client  # noqa: E402
from suite import Ctx, Report, run_suite  # noqa: E402

LOCAL_HOSTS = ("localhost", "127.0.0.1", "::1")


def _valid_credentials(value):
    return not value or (":" in value and not value.startswith(":") and not value.endswith(":"))


def main(argv, env=None, out=None, sleep=time.sleep, now=None):
    env = os.environ if env is None else env
    out = out or sys.stdout

    def say(line):
        out.write(line + "\n")
        out.flush()

    args = list(argv)
    if len(args) != 1 or args[0].startswith("-"):
        sys.stderr.write((__doc__ or "").split("\n\n")[1] + "\n")
        return 2
    base = args[0].rstrip("/")
    if not (base.startswith("http://") or base.startswith("https://")):
        sys.stderr.write("The base URL must start with http:// or https://\n")
        return 2
    auth = env.get("EBX_AUTH", "")
    norole = env.get("EBX_AUTH_NOROLE", "")
    for credential in (auth, norole):
        if not _valid_credentials(credential):
            sys.stderr.write("Credentials must look like user:password (the value is not shown)\n")
            return 2
    if (auth or norole) and base.startswith("http://") and not (env.get("ITEST_ALLOW_PLAIN_HTTP") == "1" or env.get("SMOKE_ALLOW_PLAIN_HTTP") == "1"):
        host = urllib.parse.urlparse(base).hostname or ""
        if host not in LOCAL_HOSTS:
            sys.stderr.write("Refusing to send credentials in clear over http to %s (use https, or ITEST_ALLOW_PLAIN_HTTP=1)\n" % host)
            return 2
    report = Report(say)
    if not auth:
        for name in ("S1 status and MCP", "S2 account without a role", "S3 rights by level", "S4 feed end to end", "S5 retention, status, private address", "S6 clean-up verification"):
            report.skipped(name, "EBX_AUTH is not set in the environment")
        say("%d passed, %d failed, %d not run" % (report.passes, report.fails, report.skips))
        return 2
    stamp = (now or time.strftime)("%Y%m%d%H%M%S")
    prefix = "itest-%s-" % stamp
    verbose = env.get("ITEST_VERBOSE") == "1"
    trace = (lambda line: sys.stderr.write(line + "\n")) if verbose else None
    cacert = env.get("ITEST_CACERT") or env.get("SMOKE_CACERT") or None
    try:
        wait = float(env.get("ITEST_RIGHTS_WAIT", "6.5"))
    except ValueError:
        sys.stderr.write("ITEST_RIGHTS_WAIT must be a number of seconds\n")
        return 2
    admin = Client(base, auth, cacert=cacert, trace=trace)
    norole_client = Client(base, norole, cacert=cacert, trace=trace) if norole else None
    say("ebx-connectors integration suite: %s, prefix %s" % (base, prefix))
    say("This run writes to the instance (scratch objects, a temporary Connectors right for everyone) and removes it at the end.")
    ctx = Ctx(base, admin, norole_client, report, prefix, wait, sleep)
    code = run_suite(ctx)
    say("%d passed, %d failed, %d not run" % (report.passes, report.fails, report.skips))
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
