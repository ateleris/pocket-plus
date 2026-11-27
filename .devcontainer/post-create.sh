#!/usr/bin/env bash
# Dev-container bootstrap: the one-time steps from README "Build / test
# (macOS / Linux)" — create the Python venv. (Stainless comes from Maven Central, through sbt.)
set -euo pipefail
cd "$(dirname "$0")/../pocketplus"

# Python venv + test dependencies (python/env is git-ignored).
if [ ! -d python/env ]; then
    python3 -m venv python/env
fi
python/env/bin/pip install --quiet -r python/requirements.txt

echo
echo "Dev container ready. Typical workflow:"
echo "  cd pocketplus"
echo "  ./native/build.sh                           # GenC -> native/build/libpocketplus.so"
echo "  cd python"
echo "  env/bin/python -m pytest -m 'not verify'     # interop suite"
echo "  env/bin/python -m pytest -m verify           # Stainless verification gate (slow)"
