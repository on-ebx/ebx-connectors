#!/usr/bin/env bash
# Installs this repository's module into an Eclipse/FastTrack workspace: synchronises ebx-connectors/ into
# <workspace>/ebx-connectors/, rebuilds the deployed target/classes (the overlay the Tomcat context mounts on
# /WEB-INF/classes) and stamps the version. Nothing is started or restarted.
#
#   tools/install.sh [--dry-run]
#
#   --dry-run   print what would be done and change nothing (the Tomcat check, the version and the file
#               synchronisation preview are still computed, read only)
#   EBX_WORKSPACE        the ebx-ps-fasttrack workspace folder
#                        (default: $HOME/Fasttrack/Test 6.3.0/FT 6.3 RC/Workspace/ebx-ps-fasttrack)
#   EBX_INSTALL_LOG      log file (default: ${TMPDIR:-/tmp}/ebx-connectors-install-<time>.log)
#
# RUN ONLY WHILE TOMCAT IS STOPPED: a class replaced under a running Tomcat gives NoSuchMethodError on the classes
# already loaded (field notes §10). The script refuses when the Tomcat of THIS workspace runs; another instance
# on the machine (a 6.2.3 demo, a second RC) may keep running.
#
# Why the version is computed here: the deployed copy is not a git repository, so tools/version.sh run there answers
# 0.0.0-unknown. The version and the commit are read from THIS repository, before the synchronisation, and handed to
# the build as EBX_BUILD_VERSION / EBX_BUILD_COMMIT (they end in version.properties and the WAR manifest, so
# GET /ebx-connectors/rest/v1/status reports them).
#
# What is preserved in the copy: target/ is rebuilt by the build itself, src/main/webapp/WEB-INF/lib holds runtime jars that
# are not in git (kafka, lz4, snappy, zstd...) and is neither copied over nor deleted, dist/ is generated.
# Never pipe this script into head: the pipe closes and the next echo kills it half way (field notes §15).
set -euo pipefail

dry_run=false
case "${1:-}" in
	"") ;;
	--dry-run) dry_run=true ;;
	-h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
	*) echo "usage: $0 [--dry-run]" >&2; exit 2 ;;
esac

ws="${EBX_WORKSPACE:-$HOME/Fasttrack/Test 6.3.0/FT 6.3 RC/Workspace/ebx-ps-fasttrack}"
ws="${ws%/}"
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
module_src="$repo_dir/ebx-connectors"
copy="$ws/ebx-connectors"
server_lib="$ws/ebx-ps-fasttrack-server/lib"
log="${EBX_INSTALL_LOG:-${TMPDIR:-/tmp}/ebx-connectors-install-$(date +%Y%m%d-%H%M%S).log}"

say() { printf '%s\n' "$*"; }
fail() { printf 'install: %s\n' "$*" >&2; exit 1; }

[[ -x "$module_src/tools/version.sh" ]] || fail "not the repository of the module: $repo_dir"
[[ -d "$server_lib" && ( -f "$server_lib/ebx-lib.jar" || -f "$server_lib/ebx.jar" ) ]] || { echo "not an ebx-ps-fasttrack workspace (neither $server_lib/ebx-lib.jar nor $server_lib/ebx.jar): $ws" >&2; exit 2; }

# Only the Tomcat of THIS workspace matters (its -Dcatalina.base is <workspace>/ebx-ps-fasttrack-server).
running=""
for pid in $(pgrep -f "org.apache.catalina.startup.Bootstrap" 2>/dev/null || true); do
	cmd="$(ps -o command= -p "$pid" 2>/dev/null || true)"
	if [[ "$cmd" == *"-Dcatalina.base=$ws/"* ]]; then
		running="$pid"
		break
	fi
done
if [[ -n "$running" ]]; then
	echo "The Tomcat of $ws is running (pid $running): stop it first." >&2
	exit 1
fi

# The version and the commit of the repository (audit C-08), decided before the copy loses its .git.
version="$("$module_src/tools/version.sh" version)"
commit="$("$module_src/tools/version.sh" commit)"
[[ "$commit" != unknown ]] || fail "$repo_dir is not a git checkout: no version to stamp (set EBX_BUILD_VERSION and EBX_BUILD_COMMIT to force one)"
say "workspace   $ws"
say "version     $version (commit $commit)"
say "copy        $copy"

rsync_args=(-a --delete
	--exclude /target
	--exclude /dist
	--exclude /src/main/webapp/WEB-INF/lib
	--exclude .DS_Store
	--exclude __pycache__)

if [[ "$dry_run" == true ]]; then
	say "DRY RUN: nothing is written."
	say "Tomcat     not running for this workspace (checked)"
	say "1. rsync ${rsync_args[*]} \"$module_src/\" \"$copy/\""
	if [[ -d "$copy" ]]; then
		# -c compares contents: a fresh clone or worktree has new modification times on every file, which is no change.
		changes="$(rsync "${rsync_args[@]}" -c --dry-run --itemize-changes "$module_src/" "$copy/" | grep '^[<>c*]' || true)"
		say "   $(printf '%s' "$changes" | grep -c . || true) file(s) differ in content (created, updated or deleted)"
		printf '%s\n' "$changes" | grep . | sed 's/^/     /' | sed -n '1,15p' || true
	else
		say "   the copy does not exist yet: it would be created"
	fi
	say "2. cd \"$copy\" && EBX_LIB_DIR=\"$server_lib\" EBX_BUILD_DIR=\"$copy/target\" EBX_BUILD_VERSION=\"$version\" EBX_BUILD_COMMIT=\"$commit\" ./build-test.sh   (log: $log)"
	say "   it rebuilds $copy/target (classes, test-classes, ebx-connectors.war) and runs the offline contract tests"
	say "3. check $copy/target/classes/com/on/ebx/connect/version.properties = $version / $commit, and that WEB-INF/lib kept its jars"
	exit 0
fi

mkdir -p "$copy"
jars_before="$(find "$copy/src/main/webapp/WEB-INF/lib" -maxdepth 1 -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')"
say "log         $log"
{
	echo "== rsync $(date)"
	rsync "${rsync_args[@]}" "$module_src/" "$copy/"
} >"$log" 2>&1 || { tail -20 "$log" >&2; fail "rsync failed, see $log"; }

say "building (about 90 s; the offline contract tests run as part of it)..."
if ! ( cd "$copy" && echo "== build $(date)" && EBX_LIB_DIR="$server_lib" EBX_BUILD_DIR="$copy/target" \
		EBX_BUILD_VERSION="$version" EBX_BUILD_COMMIT="$commit" ./build-test.sh ) >>"$log" 2>&1; then
	tail -30 "$log" >&2
	fail "the build failed, see $log (the copy is synchronised, target/ is incomplete: do not start Tomcat)"
fi

# Verification: what the Tomcat context will mount must carry the version of this repository.
props="$copy/target/classes/com/on/ebx/connect/version.properties"
[[ -f "$props" ]] || fail "$props is missing after the build"
got_version="$(sed -n 's/^version=//p' "$props")"
got_commit="$(sed -n 's/^commit=//p' "$props")"
[[ "$got_version" == "$version" && "$got_commit" == "$commit" ]] \
	|| fail "version.properties says $got_version / $got_commit, expected $version / $commit ($props)"
classes="$(find "$copy/target/classes" -name '*.class' | wc -l | tr -d ' ')"
[[ "$classes" -gt 0 ]] || fail "no class under $copy/target/classes"
jars_after="$(find "$copy/src/main/webapp/WEB-INF/lib" -maxdepth 1 -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')"
[[ "$jars_after" -ge "$jars_before" ]] || fail "WEB-INF/lib lost jars ($jars_before before, $jars_after after)"

say "installed ebx-connectors $version (commit ${commit:0:8}): $classes classes, $jars_after runtime jars in WEB-INF/lib, copy $copy"
say "Start Tomcat (ebx.sh start); install and restart are two separate decisions."
