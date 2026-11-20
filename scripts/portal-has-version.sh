#!/bin/bash
#
# Prints "published=true" when the Gradle Plugin Portal already serves a version of the plugin,
# and "published=false" otherwise: the form a step output takes, so the caller appends it to
# GITHUB_OUTPUT and a failure of this script fails the step instead of leaving the output empty.
#
# The release run uses the answer to skip publishing on a re-run: the Portal rejects a version
# that is already live, so without the check a failure in any later step would leave a run that
# can never go green for its tag. Known edge: a version waiting for the Portal's manual approval
# of a first publish is not served yet and reads as "false".
#
# Usage: portal-has-version.sh <version>

set -euo pipefail

readonly MARKER_BASE_URL='https://plugins.gradle.org/m2/io/github/ahmetsirim/kot/io.github.ahmetsirim.kot.gradle.plugin'

main() {
  local version="$1"

  local status
  status="$(curl -s -o /dev/null -w '%{http_code}' "${MARKER_BASE_URL}/${version}/")"
  echo "Portal marker returned HTTP ${status} for ${version}." >&2

  if [[ "${status}" == "200" ]]; then
    echo "published=true"
  else
    echo "published=false"
  fi
}

main "$@"
