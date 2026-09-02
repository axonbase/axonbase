#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/release-lib.sh"
release_root
require_release axonbase-sdk-rust

pushd axonbase-sdk-rust >/dev/null
cargo test
cargo publish
popd >/dev/null
