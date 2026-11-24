#!/usr/bin/env bash
# Builds the release package of the module: dist/ebx-connectors-<version>.zip (audit C-08).
#
#   tools/package.sh [--since <commit-or-tag>]
#
# It runs build-test.sh (the whole offline suite, which also builds and stamps the war and runs the self-sufficiency check
# of the war, audit X3-07), then packs, under one folder ebx-connectors-<version>/ : ebx-connectors.war, THIRD-PARTY-NOTICES.md
# and licenses/ (the licence and notice texts of the jars the war ships; the war carries them too, in META-INF/licenses),
# OPERATIONS.md, UPGRADE-ROLLBACK.md, RELEASE-NOTES.md (the first-parent git log since the previous tag, or since --since),
# DEPENDENCY-CHECK.txt (the output of the advisory check below), tools/smoke.sh and tools/schema_diff.py (what an operator runs around
# an upgrade), jars/ (since 1.4.2, audit F3-100: on-ebx-agent.jar, on-ebx-mapping.jar and ebx-connectors-spi.jar, built here from the packed
# commit by build-agent.sh, build-mapping.sh and build-spi.sh, which other repositories embed or compile against: the MDM Factory pins their
# SHA-256, so its package can be rebuilt on any machine from this package), and SHA256SUMS (of everything else, the three jars included).
#
# The advisory gate (lot 1.4.1, audit X3-08): BEFORE the build, tools/deps_check.sh --require-osv asks OSV (https://osv.dev) for the
# advisories of every jar of runtime-libs/ and REFUSES the package (CONN-PACKAGE-3) on any advisory that runtime-libs/DEPS-KNOWN.txt
# does not accept with its reason, and also when OSV cannot be reached. The one way past an unreachable OSV is the named variable
# CONN_PACKAGE_OSV_WAIVER=<who decided, why, date>: the check then runs offline (versions and library families only) and the waiver
# text is written in RELEASE-NOTES.md and DEPENDENCY-CHECK.txt of the package. There is no switch for an advisory that OSV reports.
# The zip's own checksum is written beside it as ebx-connectors-<version>.zip.sha256. dist/ is git-ignored. NOTHING IS DEPLOYED:
# copy the war to a server by hand (docs/OPERATIONS.md section 2).
#
# Environment: EBX_LIB_DIR and EBX_BUILD_DIR as build-test.sh reads them; EBX_STANDARD_JAR, the ebx.jar of a standard ON EBX
# distribution (<distribution>/ebx.software/lib/ebx.jar), REQUIRED here so that the self-sufficiency check is the exact one;
# TOMCAT_LIB_DIR (Tomcat's lib/), recommended. A package is built ONLY from a clean commit: a modified or untracked file is
# refused (CONN-PACKAGE-1), and there is no switch to override that. No tag is created or pushed: a formal release is a tag
# the owner creates first (docs/RELEASES.md).
set -euo pipefail

module_dir="$(cd "$(dirname "$0")/.." && pwd)"
repo_dir="$(git -C "$module_dir" rev-parse --show-toplevel)"
since=""
while (( $# )); do
	case "$1" in
	--since) since="${2:?--since needs a commit or a tag}"; shift 2 ;;
	--allow-dirty) printf 'CONN-PACKAGE-1: --allow-dirty is no longer accepted: a package is built only from a clean commit.\n' >&2; exit 2 ;;
	*) sed -n 2,26p "$0"; exit 2 ;;
	esac
done

for tool in zip git; do
	command -v "$tool" >/dev/null 2>&1 || { printf 'Required tool not found: %s\n' "$tool" >&2; exit 2; }
done
if command -v sha256sum >/dev/null 2>&1; then
	sha256() { sha256sum "$@"; }
else
	sha256() { shasum -a 256 "$@"; }
fi

# a modified tracked file, or an untracked one that is not git-ignored: the package could not be rebuilt from a commit
if [[ "$("$module_dir/tools/version.sh" dirty)" == true || -n "$(git -C "$repo_dir" status --porcelain --untracked-files=normal -- "$module_dir")" ]]; then
	printf 'CONN-PACKAGE-1: the tree is not clean (a modified or untracked file). Commit or remove it, then run again; there is no switch to package a dirty tree.\n' >&2
	exit 2
fi
if [[ -z "${EBX_STANDARD_JAR:-}" || ! -f "${EBX_STANDARD_JAR:-}" ]]; then
	printf 'CONN-PACKAGE-2: set EBX_STANDARD_JAR to the ebx.jar of a standard ON EBX distribution (<distribution>/ebx.software/lib/ebx.jar): the package gate checks that the war runs on a standard lib/ against the real jar, not against a list of prefixes.\n' >&2
	exit 2
fi

# the advisory gate (audit X3-08): before the long build, so that a vulnerable jar costs seconds, not two minutes
dep_report="$(mktemp "${TMPDIR:-/tmp}/ebx-connectors-deps.XXXXXX")"
waiver=""
if [[ -n "${CONN_PACKAGE_OSV_WAIVER:-}" ]]; then
	waiver="$CONN_PACKAGE_OSV_WAIVER"
	if ! "$module_dir/tools/deps_check.sh" --offline > "$dep_report" 2>&1; then
		tail -20 "$dep_report" >&2
		printf 'CONN-PACKAGE-3: the offline dependency check failed (library families, or a line of DEPS-KNOWN.txt). A waiver only covers an unreachable OSV.\n' >&2
		exit 2
	fi
	printf '\nOSV: NOT CHECKED. Waiver (CONN_PACKAGE_OSV_WAIVER): %s\n' "$waiver" >> "$dep_report"
else
	if ! "$module_dir/tools/deps_check.sh" --require-osv > "$dep_report" 2>&1; then
		tail -30 "$dep_report" >&2
		printf 'CONN-PACKAGE-3: a jar of runtime-libs/ has an advisory that runtime-libs/DEPS-KNOWN.txt does not accept (with its reason and date), or OSV could not be reached. Move the jar, or add the accepted line; with OSV unreachable, the owner may set CONN_PACKAGE_OSV_WAIVER="<who, why, date>" (written into the package).\n' >&2
		exit 2
	fi
fi

build_dir="${EBX_BUILD_DIR:-$module_dir/target/baseline}"
export EBX_BUILD_DIR="$build_dir"
"$module_dir/build-test.sh"

# the version the war itself carries, read back from it, so the package name can never disagree with the stamp
war="$build_dir/ebx-connectors.war"
[[ -f "$war" ]] || { printf 'build-test.sh produced no war at %s\n' "$war" >&2; exit 1; }
stamp="$(unzip -p "$war" WEB-INF/classes/com/on/ebx/connect/version.properties)"
version="$(printf '%s\n' "$stamp" | sed -n 's/^version=//p')"
commit="$(printf '%s\n' "$stamp" | sed -n 's/^commit=//p')"
built_at="$(printf '%s\n' "$stamp" | sed -n 's/^builtAt=//p')"
[[ -n "$version" && "$version" != unknown ]] || { printf 'The war carries no version stamp.\n' >&2; exit 1; }

dist_dir="$module_dir/dist"
name="ebx-connectors-$version"
mkdir -p "$dist_dir"
stage="$(mktemp -d "$dist_dir/.stage.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
root="$stage/$name"
mkdir -p "$root"

cp "$war" "$root/ebx-connectors.war"
# the three jars that other repositories consume, from the packed commit (the tree is clean: HEAD is what is built). The jar scripts take the
# server lib from EBX_LIB_DIR like build-test.sh; their entries and manifest are reproducible (tools/jar_repro.sh): the same commit and JDK give the same bytes.
mkdir -p "$root/jars"
for jar_script in build-agent.sh build-mapping.sh build-spi.sh; do
	if ! EBX_JAR_OUT_DIR="$root/jars" "$module_dir/$jar_script" >> "$stage/jars.log" 2>&1; then
		tail -20 "$stage/jars.log" >&2
		printf 'CONN-PACKAGE-4: %s failed (log above): the package would not carry the jar that other modules consume.\n' "$jar_script" >&2
		exit 1
	fi
done
rm -f "$root/jars/ebx-connectors-spi-sources.jar"
for jar in on-ebx-agent.jar on-ebx-mapping.jar ebx-connectors-spi.jar; do
	[[ -f "$root/jars/$jar" ]] || { printf 'CONN-PACKAGE-4: jars/%s was not produced.\n' "$jar" >&2; exit 1; }
done
cp "$module_dir/runtime-libs/THIRD-PARTY-NOTICES.md" "$root/THIRD-PARTY-NOTICES.md"
mkdir -p "$root/licenses"
cp "$module_dir/runtime-libs/licenses/"* "$root/licenses/"
for legal in "$module_dir"/runtime-libs/*-LICENSE.txt "$module_dir"/runtime-libs/*-NOTICE* "$module_dir"/runtime-libs/MONGODB-THIRD-PARTY-NOTICES; do
	# THIRD-PARTY-NOTICES.md matches the *-NOTICE* pattern and already sits at the top of the package
	if [[ -f "$legal" && "$(basename "$legal")" != THIRD-PARTY-NOTICES.md ]]; then cp "$legal" "$root/licenses/"; fi
done
{
	printf 'Dependency check of the package (tools/deps_check.sh --require-osv), run on %s (UTC) at commit %s\n\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$commit"
	cat "$dep_report"
} > "$root/DEPENDENCY-CHECK.txt"
rm -f "$dep_report"
cp "$module_dir/docs/OPERATIONS.md" "$root/OPERATIONS.md"
cp "$repo_dir/docs/UPGRADE-ROLLBACK.md" "$root/UPGRADE-ROLLBACK.md"
# the two scripts an operator runs around an upgrade, without needing a checkout
mkdir -p "$root/tools"
cp "$module_dir/tools/smoke.sh" "$module_dir/tools/schema_diff.py" "$root/tools/"

# --- release notes: the git log since the previous tag (or since --since) ---------------------------------------
range_note=""
if [[ -z "$since" ]]; then
	if git -C "$repo_dir" describe --tags --exact-match HEAD >/dev/null 2>&1; then
		# HEAD is the tag being released: the previous one is the one before it
		head_tag="$(git -C "$repo_dir" describe --tags --exact-match HEAD)"
		since="$(git -C "$repo_dir" describe --tags --abbrev=0 "$head_tag^" 2>/dev/null || true)"
	else
		since="$(git -C "$repo_dir" describe --tags --abbrev=0 2>/dev/null || true)"
	fi
fi
{
	printf '# ebx-connectors %s\n\n' "$version"
	printf -- '- Commit: `%s`\n- Built: %s (UTC)\n' "$commit" "$built_at"
	printf -- '- war SHA-256: `%s`\n' "$(sha256 "$war" | awk '{print $1}')"
	for jar in on-ebx-agent.jar on-ebx-mapping.jar ebx-connectors-spi.jar; do
		printf -- '- jars/%s SHA-256: `%s`\n' "$jar" "$(sha256 "$root/jars/$jar" | awk '{print $1}')"
	done
	if [[ -n "$waiver" ]]; then
		printf -- '- **OSV advisory check NOT RUN, waived**: %s\n\n' "$waiver"
	else
		printf -- '- OSV advisory check: passed (see DEPENDENCY-CHECK.txt)\n\n'
	fi
	if [[ -n "$since" ]]; then
		printf 'Changes since `%s`:\n\n' "$since"
		range="$since..HEAD"
	else
		printf 'The repository has no tag and no starting point was given (--since): this lists the whole first-parent history (merges of branches, and commits made directly).\n\n'
		range="HEAD"
	fi
	count=0
	while IFS= read -r line; do
		printf -- '- %s\n' "$line"
		count=$((count + 1))
	done < <(git -C "$repo_dir" log --first-parent --format='%h %ad %s' --date=short "$range")
	if (( count == 0 )); then
		printf 'No commit in that range.\n'
	fi
	printf '\nThe schema is not versioned: read UPGRADE-ROLLBACK.md before replacing a running installation.\n'
} > "$root/RELEASE-NOTES.md"

# --- checksums of everything in the folder, then the zip ---------------------------------------------------------
( cd "$root" && { sha256 ebx-connectors.war THIRD-PARTY-NOTICES.md OPERATIONS.md UPGRADE-ROLLBACK.md RELEASE-NOTES.md DEPENDENCY-CHECK.txt tools/smoke.sh tools/schema_diff.py jars/on-ebx-agent.jar jars/on-ebx-mapping.jar jars/ebx-connectors-spi.jar; find licenses -type f | LC_ALL=C sort | while IFS= read -r f; do sha256 "$f"; done; } > SHA256SUMS )

zip_path="$dist_dir/$name.zip"
rm -f "$zip_path"
( cd "$stage" && zip -X -q -r "$zip_path" "$name" )
( cd "$dist_dir" && sha256 "$name.zip" > "$name.zip.sha256" )

printf 'PASS: %s\n' "$zip_path"
printf 'war inside the package: %s (copy it to a server by hand; nothing was deployed)\n' "$build_dir/ebx-connectors.war"
unzip -l "$zip_path"
