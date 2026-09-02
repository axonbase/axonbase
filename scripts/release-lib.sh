#!/usr/bin/env bash

release_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/.."
}

require_release() {
  local tag_prefix=$1
  : "${VERSION:?Set VERSION to the package version being released.}"

  if [[ -n $(git status --porcelain) ]]; then
    printf 'Refusing to publish: commit or stash all changes first.\n' >&2
    exit 1
  fi

  local expected_tag="${tag_prefix}/v${VERSION}"
  if [[ $(git describe --exact-match --tags HEAD) != "$expected_tag" ]]; then
    printf 'Refusing to publish: HEAD must be tagged %s.\n' "$expected_tag" >&2
    exit 1
  fi
}
