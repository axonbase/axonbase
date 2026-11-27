#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root_dir"

if [[ -n $(git status --porcelain) ]]; then
  printf 'Refusing to publish: commit or stash all changes first.\n' >&2
  exit 1
fi

version=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
expected_tag="v$version"

if [[ $(git describe --exact-match --tags HEAD) != "$expected_tag" ]]; then
  printf 'Refusing to publish: HEAD must be tagged %s.\n' "$expected_tag" >&2
  exit 1
fi

mvn clean test -pl axonbase-server -am

image="axonbase/axonbase"
platforms=${DOCKER_PLATFORMS:-linux/amd64,linux/arm64}

tags=(--tag "$image:$version")
# latest só acompanha versão final: pré-lançamento (com hífen, como 1.0.0-rc2) publica apenas a própria tag
if [[ $version != *-* ]]; then
  tags+=(--tag "$image:latest")
fi

docker buildx build \
  --platform "$platforms" \
  "${tags[@]}" \
  --push \
  .
