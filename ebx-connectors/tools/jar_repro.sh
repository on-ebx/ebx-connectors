#!/usr/bin/env bash
# Sourced by build-agent.sh, build-mapping.sh and build-spi.sh: makes the jars they write reproducible (audit F3-100, 1.4.2).
#
# Why: the three jars (on-ebx-agent.jar, on-ebx-mapping.jar, ebx-connectors-spi.jar) are embedded by other repositories (the MDM Factory),
# which pin their SHA-256. Two builds of the same commit must give the same bytes. Three things made them differ: the manifest version was
# the day of the build (`date +%Y.%m.%d`), every entry carried the build time, and the entries came in the order of the file system.
# Now: the version is the date of the commit (or SOURCE_DATE_EPOCH), every entry is stamped with that instant (`jar --date`, JDK 17 or later),
# the entries are added in sorted order, and the manifest carries a fixed Created-By. The bytecode itself comes from javac --release 8, which
# is deterministic for one JDK; another JDK build can produce other bytes (the SHA-256 of a release is therefore recorded in its SHA256SUMS).
#
# Functions: repro_epoch <dir>      the instant of the build: SOURCE_DATE_EPOCH, else the commit time of HEAD in <dir>, else now (not reproducible)
#            repro_version <epoch>  yyyy.mm.dd of that instant, UTC
#            repro_jar <jar bin> <out jar> <manifest> <epoch> <classes dir>   writes the jar from <classes dir>/com in sorted order

repro_epoch() {
	if [[ -n "${SOURCE_DATE_EPOCH:-}" ]]; then printf '%s' "$SOURCE_DATE_EPOCH"; return; fi
	local commit_time
	commit_time="$(git -C "$1" log -1 --format=%ct 2>/dev/null || true)"
	if [[ -n "$commit_time" ]]; then printf '%s' "$commit_time"; else date +%s; fi
}

repro_version() {
	date -u -r "$1" +%Y.%m.%d 2>/dev/null || date -u -d "@$1" +%Y.%m.%d
}

repro_jar() {
	local jar_bin="$1" out="$2" manifest="$3" epoch="$4" dir="$5" stamp args
	stamp="$(date -u -r "$epoch" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "@$epoch" +%Y-%m-%dT%H:%M:%SZ)"
	rm -f "$out"
	if "$jar_bin" --help 2>&1 | grep -q -- '--date'; then
		args="$(mktemp)"
		( cd "$dir" && find com -type f | LC_ALL=C sort ) | while IFS= read -r entry; do printf -- '-C\n%s\n%s\n' "$dir" "$entry"; done > "$args"
		"$jar_bin" --create --file "$out" --manifest "$manifest" --date "$stamp" "@$args"
		rm -f "$args"
	else
		echo "NOTE: this jar tool has no --date option (JDK 17 or later needed): $out is not reproducible" >&2
		"$jar_bin" cfm "$out" "$manifest" -C "$dir" com
	fi
}
