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

mvn clean install \
  -pl axonbase-server \
  -am \
  -Dmaven.test.skip=true \
  -Dmaven.javadoc.skip=true

mvn -B install -pl axonbase-jdbc -am -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true -q

mvn -Pcentral-publishing deploy \
  -pl axonbase-jdbc \
  -DskipTests -Dmaven.javadoc.skip=true

printf 'Bundle uploaded. Review and publish it at https://central.sonatype.com/publishing/deployments\n'
