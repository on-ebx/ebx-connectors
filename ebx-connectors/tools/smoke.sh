#!/usr/bin/env bash
# Post-install smoke test of the ebx-connectors module (audit C-09, part): read requests only, one PASS/FAIL line per
# check, exit 0 only when every check passed.
#
#   EBX_AUTH=user:password [EBX_AUTH_NOROLE=user:password] tools/smoke.sh <base-url>     e.g. http://localhost:8080
#
# Checks: EBX answers at /ebx/; the status route refuses a caller without credentials (401); with EBX_AUTH, the status
# route answers 200 with the expected fields and a version that is not "unknown" (and equal to EXPECT_VERSION when
# that is set), the MCP endpoint answers initialize with the same version and tools/list with a non-empty list. With
# EBX_AUTH_NOROLE (an account that holds no Connectors right), the same two calls are refused (403) or list no tool.
# Nothing is written and no connector is called: GET /v1/status and the MCP methods initialize and tools/list only.
#
# Credentials come from the environment ONLY, never from an argument. curl reads them from a private (mode 600)
# temporary file that is removed when the script ends; a secret is never printed. Without EBX_AUTH the public checks
# run, the authenticated ones are reported as NOT RUN and the exit code is 2 (a smoke test that did not run
# everything has not passed). Other environment: SMOKE_CACERT (a CA file for an https URL; certificates are never
# switched off), SMOKE_ALLOW_PLAIN_HTTP=1 (send credentials over http to a host that is not this machine).
set -euo pipefail

base="${1:-}"
if [[ -z "$base" || "$base" == -* || $# -ne 1 ]]; then
	sed -n 2,5p "$0" >&2
	exit 2
fi
base="${base%/}"
case "$base" in
http://* | https://*) ;;
*) printf 'The base URL must start with http:// or https://\n' >&2; exit 2 ;;
esac
command -v curl >/dev/null 2>&1 || { printf 'curl is required\n' >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { printf 'python3 is required\n' >&2; exit 2; }

auth_main="${EBX_AUTH:-}"
auth_norole="${EBX_AUTH_NOROLE:-}"
for credential in "$auth_main" "$auth_norole"; do
	if [[ -n "$credential" && "$credential" != ?*:* ]]; then
		printf 'Credentials must look like user:password (the value is not shown)\n' >&2
		exit 2
	fi
done
if [[ -n "$auth_main$auth_norole" && "$base" == http://* && "${SMOKE_ALLOW_PLAIN_HTTP:-}" != 1 ]]; then
	host="${base#http://}"; host="${host%%/*}"; host="${host%%:*}"
	case "$host" in
	localhost | 127.0.0.1 | '[::1]') ;;
	*) printf 'Refusing to send credentials in clear over http to %s (use https, or SMOKE_ALLOW_PLAIN_HTTP=1)\n' "$host" >&2; exit 2 ;;
	esac
fi

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# a curl config file per account: the value sits between double quotes with backslash and quote escaped, so nothing in
# a password is read as syntax; "basic" makes curl send it on the first request, as the cross-site rule of the module needs
make_curlrc() {
	local escaped="${1//\\/\\\\}"
	escaped="${escaped//\"/\\\"}"
	printf 'user = "%s"\nbasic\n' "$escaped" > "$2"
}
if [[ -n "$auth_main" ]]; then make_curlrc "$auth_main" "$work/main.rc"; fi
if [[ -n "$auth_norole" ]]; then make_curlrc "$auth_norole" "$work/norole.rc"; fi
unset auth_main auth_norole credential

failures=0
notrun=0
pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1"; failures=$((failures + 1)); }
skip() { printf 'NOT RUN: %s\n' "$1"; notrun=$((notrun + 1)); }

# call <who: none|main|norole> <GET|POST> <path> [json body] -> sets $code, body in $work/body
code=""
call() {
	local who="$1" method="$2" path="$3" body="${4:-}"
	local args=(-sS --max-time 30 -o "$work/body" -w '%{http_code}' -X "$method" -H 'Accept: application/json')
	if [[ "$who" != none ]]; then args+=(-K "$work/$who.rc"); fi
	if [[ -n "${SMOKE_CACERT:-}" ]]; then args+=(--cacert "$SMOKE_CACERT"); fi
	if [[ -n "$body" ]]; then args+=(-H 'Content-Type: application/json' --data-binary "$body"); fi
	: > "$work/body"
	code="$(curl "${args[@]}" "$base$path" 2>"$work/curl.err")" || code="000"
}

# json <python expression over j> -> prints the value; empty when the body is not JSON or the expression fails
json() {
	python3 - "$work/body" "$1" <<'PY' 2>/dev/null || true
import json, sys
try:
    j = json.load(open(sys.argv[1], encoding="utf-8"))
    value = eval(sys.argv[2], {"j": j})
    print("" if value is None else value)
except Exception:
    pass
PY
}

status_path=/ebx-connectors/rest/v1/status
mcp_path=/ebx-connectors/rest/mcp
mcp_list='{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
mcp_init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"ebx-connectors-smoke","version":"1"}}}'

# 1. EBX itself answers (a login redirect counts)
call none GET /ebx/
if [[ "$code" =~ ^[23][0-9][0-9]$ ]]; then pass "/ebx/ answers (HTTP $code)"; else fail "/ebx/ does not answer (HTTP $code)"; fi

# 2. the status route is not public
call none GET "$status_path"
if [[ "$code" == 401 ]]; then pass "status route refuses a caller without credentials (HTTP 401)"; else fail "status route without credentials answered HTTP $code, expected 401"; fi

if [[ ! -f "$work/main.rc" ]]; then
	skip "authenticated checks: EBX_AUTH is not set in the environment"
else
	# 3. status
	call main GET "$status_path"
	if [[ "$code" != 200 ]]; then
		fail "status route answered HTTP $code, expected 200"
	else
		missing=""
		for field in "j['module']" "j['version']['version']" "j['version']['commit']" "j['installation']" "j['egress']" "j['retention']" "j['runs']" "j['asyncPool']" "j['offUntilConfigured'] is not None or ''"; do
			if [[ -z "$(json "$field")" ]]; then missing="$missing ${field%%]*}]"; fi
		done
		if [[ -n "$missing" ]]; then fail "status answer lacks expected fields:$missing"; else pass "status answer carries the expected fields"; fi
		module="$(json "j['module']")"
		if [[ "$module" == ebx-connectors ]]; then pass "status says module ebx-connectors"; else fail "status says module '$module'"; fi
		if [[ "$(json "j['installation']['datasetPresent'] and j['installation']['modelPresent'] and j['installation']['installerCompleted']")" == True ]]; then
			pass "dataset and model present, installer completed"
		else
			fail "installation incomplete: $(json "j['installation']")"
		fi
		installed="$(json "j['version']['version']")"
		if [[ -z "$installed" || "$installed" == unknown ]]; then
			fail "installed version is '${installed:-absent}': not a packaged build (build with build-test.sh / tools/package.sh)"
		elif [[ -n "${EXPECT_VERSION:-}" && "$installed" != "$EXPECT_VERSION" ]]; then
			fail "installed version is $installed, expected $EXPECT_VERSION"
		else
			pass "installed version $installed (commit $(json "j['version']['commit']"), built $(json "j['version']['builtAt']"))"
		fi
	fi

	# 4. MCP
	call main POST "$mcp_path" "$mcp_init"
	served="$(json "j['result']['serverInfo']['version']")"
	if [[ "$code" != 200 ]]; then
		fail "MCP initialize answered HTTP $code"
	elif [[ -n "${installed:-}" && "$served" == "$installed" ]]; then
		pass "MCP serverInfo.version is $served, the status version"
	else
		fail "MCP serverInfo.version is '${served:-absent}', status says '${installed:-absent}'"
	fi
	call main POST "$mcp_path" "$mcp_list"
	tools="$(json "len(j['result']['tools'])")"
	if [[ "$code" == 200 && "${tools:-0}" -gt 0 ]]; then pass "MCP tools/list lists $tools tool(s) for this account"; else fail "MCP tools/list: HTTP $code, ${tools:-0} tool(s)"; fi
fi

# 5. an account without Connectors rights
if [[ -f "$work/norole.rc" ]]; then
	call norole GET "$status_path"
	if [[ "$code" == 403 ]]; then pass "account without rights: status refused (HTTP 403)"; else fail "account without rights: status answered HTTP $code, expected 403"; fi
	call norole POST "$mcp_path" "$mcp_list"
	tools="$(json "len(j['result']['tools'])")"
	if [[ "$code" == 403 || ( "$code" == 200 && "${tools:-x}" == 0 ) ]]; then
		pass "account without rights: no tool is listed (HTTP $code)"
	else
		fail "account without rights: tools/list answered HTTP $code with '${tools:-no list}' tool(s)"
	fi
else
	skip "account without rights: EBX_AUTH_NOROLE is not set"
fi

printf '%d failed, %d not run\n' "$failures" "$notrun"
if (( failures > 0 )); then exit 1; fi
if [[ ! -f "$work/main.rc" ]]; then exit 2; fi
exit 0
