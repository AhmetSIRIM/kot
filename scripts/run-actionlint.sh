#!/bin/bash
#
# Downloads a pinned actionlint into the working directory and lints the workflows with it.
#
# Usage: run-actionlint.sh

set -euo pipefail

readonly ACTIONLINT_VERSION='1.7.12'
readonly DOWNLOAD_SCRIPT_URL="https://raw.githubusercontent.com/rhysd/actionlint/v${ACTIONLINT_VERSION}/scripts/download-actionlint.bash"

main() {
  bash <(curl -sSfL "${DOWNLOAD_SCRIPT_URL}") "${ACTIONLINT_VERSION}"
  ./actionlint -color
}

main "$@"
