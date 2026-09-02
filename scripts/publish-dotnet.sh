#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/release-lib.sh"
release_root
require_release axonbase-sdk-dotnet
: "${NUGET_API_KEY:?Set NUGET_API_KEY before publishing to NuGet.}"

docker build --target dotnet-test --file Dockerfile.sdk-tests .
package_dir=$(mktemp -d)
trap 'rm -rf "$package_dir"' EXIT

docker buildx build \
  --target dotnet-pack \
  --output "type=local,dest=$package_dir" \
  --file Dockerfile.sdk-tests \
  .

docker run --rm \
  -e NUGET_API_KEY \
  -v "$package_dir:/packages" \
  mcr.microsoft.com/dotnet/sdk:9.0 \
  sh -c "dotnet nuget push /packages/AxonBase.Sdk.${VERSION}.nupkg --api-key \"\$NUGET_API_KEY\" --source https://api.nuget.org/v3/index.json"
