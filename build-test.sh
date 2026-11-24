#!/usr/bin/env bash
# One-command, account-free validation for the module, SPI, and extension sample.
set -euo pipefail

repository_dir="$(cd "$(dirname "$0")" && pwd)"

"$repository_dir/ebx-connectors/build-test.sh"
"$repository_dir/ebx-connectors/build-spi.sh"
"$repository_dir/ebx-connectors-extension-sample/build.sh" \
	"$repository_dir/ebx-connectors/dist/ebx-connectors-spi.jar"

printf 'PASS: module contracts, WAR, SPI, and extension sample\n'
