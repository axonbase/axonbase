#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/release-lib.sh"
release_root
require_release axonbase-sdk-python

python3 -m pytest axonbase-sdk-python/tests
rm -rf axonbase-sdk-python/dist
python3 -m build axonbase-sdk-python
twine check axonbase-sdk-python/dist/*
twine upload axonbase-sdk-python/dist/*
