#!/bin/bash
#
# Downloads a pinned actionlint release, checks the archive against its pinned SHA-256 and lints
# the workflows with it. The checksums come from the release's own checksums file; a new version
# needs its four checksums copied in.
#
# Usage: run-actionlint.sh

set -euo pipefail

readonly ACTIONLINT_VERSION='1.7.12'

#######################################
# Prints the release platform of this machine and the SHA-256 of its archive.
# Outputs:
#   "<platform> <sha256>" to stdout; a message to stderr for a machine with no pinned archive.
# Returns:
#   1 when this machine has no pinned archive.
#######################################
pinned_archive() {
  case "$(uname -sm)" in
    'Linux x86_64')
      echo 'linux_amd64 8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8'
      ;;
    'Linux aarch64')
      echo 'linux_arm64 325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6'
      ;;
    'Darwin x86_64')
      echo 'darwin_amd64 5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644'
      ;;
    'Darwin arm64')
      echo 'darwin_arm64 aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f'
      ;;
    *)
      echo "run-actionlint: no pinned actionlint archive for $(uname -sm)" >&2
      return 1
      ;;
  esac
}

main() {
  local pinned platform checksum archive
  pinned="$(pinned_archive)"
  platform="${pinned%% *}"
  checksum="${pinned##* }"
  archive="actionlint_${ACTIONLINT_VERSION}_${platform}.tar.gz"

  # Not a local: the exit trap runs after main has returned.
  DOWNLOAD_DIR="$(mktemp -d)"
  readonly DOWNLOAD_DIR
  trap 'rm -rf "${DOWNLOAD_DIR}"' EXIT

  curl -sSfL -o "${DOWNLOAD_DIR}/${archive}" \
    "https://github.com/rhysd/actionlint/releases/download/v${ACTIONLINT_VERSION}/${archive}"
  # A mismatch stops the script here, before anything from the archive runs.
  echo "${checksum}  ${DOWNLOAD_DIR}/${archive}" | shasum -a 256 -c -
  tar -xzf "${DOWNLOAD_DIR}/${archive}" -C "${DOWNLOAD_DIR}" actionlint

  "${DOWNLOAD_DIR}/actionlint" -color
}

main "$@"
