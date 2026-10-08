#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
version=$(<"$root_dir/VERSION")

require_version() {
  local file=$1
  local pattern=$2
  if ! rg -q -- "$pattern" "$root_dir/$file"; then
    printf 'Version mismatch in %s: expected %s\n' "$file" "$version" >&2
    exit 1
  fi
}

for pom in "$root_dir"/pom.xml "$root_dir"/axonbase-*/pom.xml; do
  require_version "${pom#"$root_dir"/}" "<version>${version}</version>"
done
require_version axonbase-sdk-nodejs/package.json "\"version\": \"${version}\""
require_version axonbase-sdk-nodejs/package-lock.json "\"version\": \"${version}\""
require_version axonbase-sdk-python/pyproject.toml "version = \"${version}\""
require_version axonbase-sdk-dotnet/AxonBaseSdk/AxonBaseSdk.csproj "<Version>${version}</Version>"
require_version axonbase-sdk-ruby/axonbase-sdk.gemspec "spec.version = \"${version}\""
require_version axonbase-sdk-rust/Cargo.toml "version = \"${version}\""

printf 'All publishable connector versions match %s.\n' "$version"
