#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/release-lib.sh"
release_root
require_release axonbase-sdk-ruby

pushd axonbase-sdk-ruby >/dev/null
ruby -Ilib test/client_test.rb
gem build axonbase-sdk.gemspec
gem push "axonbase-sdk-${VERSION}.gem"
popd >/dev/null
