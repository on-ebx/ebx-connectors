#!/usr/bin/env bash
# Lists the third-party jars of runtime-libs/ with their versions, checks that each library family is on ONE line
# (netty 4.1 and 4.2 side by side is unsupported), and asks OSV (https://osv.dev, the public vulnerability database) for
# the advisories published against each jar's Maven coordinates (audit C2-15).
#
#   tools/deps_check.sh            report + checks; exit 1 on a finding that runtime-libs/DEPS-KNOWN.txt does not accept
#   tools/deps_check.sh --offline  no OSV query (versions and family checks only)
#   tools/deps_check.sh --report   never fails, prints everything
#
# Only the coordinates and versions of the jars are sent to OSV, nothing else. When OSV cannot be reached the advisory part
# says so and the run does not fail (use --require-osv to fail instead, for a release).
set -euo pipefail
module_dir="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 -I "$module_dir/tools/deps_check.py" "$module_dir/runtime-libs" "$@"
