#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/release-lib.sh"
release_root
require_release axonbase-sdk-go

docker build --target go-test --file Dockerfile.sdk-tests .
git push origin "axonbase-sdk-go/v${VERSION}"
