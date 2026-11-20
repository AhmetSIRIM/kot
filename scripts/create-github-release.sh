#!/bin/bash
#
# Creates the GitHub Release of a tag with a prepared notes file as its body, or does nothing
# when the release exists already, so a re-run of the release workflow does not fail here.
#
# Usage: create-github-release.sh <tag> <notes file>
# Needs: gh, authenticated through GH_TOKEN.

set -euo pipefail

main() {
  local tag="$1"
  local notes_file="$2"

  # Any failure of the lookup, not only "not found", falls through to the create below, which
  # then fails in the open.
  if gh release view "${tag}" >/dev/null 2>&1; then
    echo "Release ${tag} already exists; skipping."
    exit 0
  fi

  gh release create "${tag}" --title "${tag}" --notes-file "${notes_file}"
}

main "$@"
