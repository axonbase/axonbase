#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/release-lib.sh"
release_root
require_release axonbase-sdk-nodejs

pushd axonbase-sdk-nodejs >/dev/null
npm ci
npm test
npm run build
npm publish --access public
popd >/dev/null
