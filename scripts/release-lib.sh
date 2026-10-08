#!/usr/bin/env bash

release_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/.."
}

require_release() {
  local tag_prefix=$1
  local root_dir
  root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
  local declared_version
  declared_version=$(<"$root_dir/VERSION")
  VERSION=${VERSION:-$declared_version}

  if [[ "$VERSION" != "$declared_version" ]]; then
    printf 'Refusing to publish: VERSION must match VERSION file (%s).\n' "$declared_version" >&2
    exit 1
  fi

  if [[ -n $(git status --porcelain) ]]; then
    printf 'Refusing to publish: commit or stash all changes first.\n' >&2
    exit 1
  fi

  local expected_tag="v${VERSION}"
  if [[ "$tag_prefix" == "axonbase-sdk-go" ]]; then
    expected_tag="${tag_prefix}/v${VERSION}"
  fi
  local tag
  while IFS= read -r tag; do
    [[ "$tag" == "$expected_tag" ]] && return
  done < <(git tag --points-at HEAD)

  printf 'Refusing to publish: HEAD must be tagged %s.\n' "$expected_tag" >&2
  exit 1
}
