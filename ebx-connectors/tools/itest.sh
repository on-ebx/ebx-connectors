#!/usr/bin/env bash
# Integration suite of the ebx-connectors module (audit C-09): real requests that run real EBX transactions, one
# PASS/FAIL/SKIPPED line per check. See docs/OPERATIONS.md, section "Integration tests". The checks live in tools/itest/.
#
#   EBX_AUTH=admin:password [EBX_AUTH_NOROLE=user:password] tools/itest.sh <base-url>     e.g. http://localhost:8080
#
# WARNING: it WRITES to the instance (scratch connections, mappings and a dataspace, a temporary Connectors right
# for everyone) and removes it all at the end. Run it against a disposable or staging instance, never against a
# production one. Credentials come from the environment ONLY, never from an argument (python3, no curl, no secret
# on a command line). Exit: 0 all passed, 1 a check failed, 2 something did not run (no EBX_AUTH, no
# EBX_AUTH_NOROLE, a scenario skipped).
set -euo pipefail

if [[ $# -ne 1 || "$1" == -* ]]; then
	sed -n 2,4p "$0" >&2
	exit 2
fi
command -v python3 >/dev/null 2>&1 || { printf 'python3 is required\n' >&2; exit 2; }
here="$(cd "$(dirname "$0")" && pwd)"
exec python3 -B "$here/itest/itest.py" "$1"
