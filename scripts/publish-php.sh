#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root_dir"

docker build --target php-test --file Dockerfile.sdk-tests .
printf 'Packagist requires axonbase/sdk to live at the root of a dedicated public repository.\n' >&2
printf 'Create that repository, submit it to Packagist, then push version tags from that repository.\n' >&2
exit 1
