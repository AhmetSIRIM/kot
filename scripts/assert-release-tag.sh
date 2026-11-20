#!/bin/bash
#
# Fails unless a tag names a release that may be published: a canonical version that is
# strictly greater than every release tag the repository already has.
#
# Usage: assert-release-tag.sh <tag> <owner/repository>
# Needs: gh, authenticated through GH_TOKEN.

set -euo pipefail

# vMAJOR.MINOR.PATCH with canonical numbers: no suffix, no leading zeros. A suffixed tag
# (-rc.1, -SNAPSHOT) is almost always a mistake here: the scheme ships no pre-releases and the
# Portal rejects snapshots. A leading zero (v0.01.0) would neither publish verbatim nor sort
# correctly below.
readonly RELEASE_TAG_PATTERN='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

main() {
  local tag="$1"
  local repository="$2"

  if ! grep -Eq "${RELEASE_TAG_PATTERN}" <<<"${tag}"; then
    echo "Tag '${tag}' is not a valid release version vMAJOR.MINOR.PATCH (canonical numbers, no suffix)." >&2
    exit 1
  fi

  # Git tags are the source of truth: published tags are never re-pointed or deleted, so they
  # are a superset of what is published. The fetch stands in its own statement so that a
  # failure stops the script instead of reading as "no tags".
  local tags
  tags="$(gh api --paginate "repos/${repository}/tags" --jq '.[].name')"

  # The tag itself is left out, so a re-run for the same tag passes. sort -V compares the
  # numbers numerically (1.10.0 above 1.9.0). grep exits non-zero when nothing matches, which
  # is the first release and not a failure.
  local new="${tag#v}"
  local latest
  latest="$(
    printf '%s\n' "${tags}" \
      | grep -E "${RELEASE_TAG_PATTERN}" \
      | grep -vxF -- "${tag}" \
      | sed 's/^v//' \
      | sort -V \
      | tail -n1 || true
  )"

  if [[ -z "${latest}" ]]; then
    echo "No prior release tag; treating ${tag} as the initial release."
    exit 0
  fi

  if [[ "${new}" == "${latest}" ]] \
    || [[ "$(printf '%s\n%s\n' "${new}" "${latest}" | sort -V | tail -n1)" != "${new}" ]]; then
    echo "Tag ${tag} is not strictly greater than the latest released version (v${latest}); refusing a downgrade or re-release." >&2
    exit 1
  fi

  echo "${tag} is a canonical release version, greater than v${latest}."
}

main "$@"
