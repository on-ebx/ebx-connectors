#!/usr/bin/env bash
# Prints the version this source tree builds as, or one of its parts.
#   tools/version.sh            the version, e.g. 1.4.0, 1.4.0-3-g1a2b3c4 or 0.0.0-1a2b3c4 (no tag in the repository)
#   tools/version.sh commit     the full commit hash ("unknown" outside a git checkout)
#   tools/version.sh dirty      "true" when tracked files differ from HEAD, else "false"
# EBX_BUILD_VERSION overrides the version and EBX_BUILD_COMMIT the commit hash: for a build from a copy without .git
# (tools/install.sh at the repository root computes both in the repository and hands them to the copy's build).
# EBX_CONNECTORS_VERSION is the older name of EBX_BUILD_VERSION and is still honoured. When EBX_BUILD_COMMIT is set,
# "dirty" reads false: the caller states what is built (the version it passes already carries any "-dirty").
# Why a script: build-test.sh stamps the war with it and tools/package.sh names the release after it; one rule, one place.
set -euo pipefail

module_dir="$(cd "$(dirname "$0")/.." && pwd)"
git_in() { git -C "$module_dir" "$@" 2>/dev/null; }

commit="$(git_in rev-parse HEAD || true)"
commit="${commit:-unknown}"
dirty=false
if [[ -n "${EBX_BUILD_COMMIT:-}" ]]; then
	commit="$EBX_BUILD_COMMIT"
elif [[ "$commit" != unknown ]] && [[ -n "$(git_in status --porcelain --untracked-files=no || true)" ]]; then
	dirty=true
fi

case "${1:-version}" in
commit) printf '%s\n' "$commit" ;;
dirty) printf '%s\n' "$dirty" ;;
version)
	if [[ -n "${EBX_BUILD_VERSION:-}" ]]; then
		printf '%s\n' "$EBX_BUILD_VERSION"
		exit 0
	fi
	if [[ -n "${EBX_CONNECTORS_VERSION:-}" ]]; then
		printf '%s\n' "$EBX_CONNECTORS_VERSION"
		exit 0
	fi
	if [[ "$commit" == unknown ]]; then
		printf '0.0.0-unknown\n'
		exit 0
	fi
	if git_in describe --tags --abbrev=0 >/dev/null; then
		# a tag exists: v1.2.0 -> 1.2.0, and 1.2.0-<commits since>-g<hash> between tags
		version="$(git_in describe --tags --always)"
		version="${version#v}"
	else
		# no tag yet (today's repository): the short hash alone, prefixed so it still reads as a version
		version="0.0.0-$(git_in rev-parse --short HEAD)"
	fi
	if [[ "$dirty" == true ]]; then
		version="$version-dirty"
	fi
	printf '%s\n' "$version"
	;;
*) printf 'usage: %s [version|commit|dirty]\n' "$0" >&2; exit 2 ;;
esac
