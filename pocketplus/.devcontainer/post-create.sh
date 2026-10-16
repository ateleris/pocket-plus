#!/usr/bin/env bash
# Dev-container bootstrap: the one-time steps from README "Build / test
# (macOS / Linux)" — fetch the Stainless toolchain and create the Python venv.
set -euo pipefail
cd "$(dirname "$0")/.."

# Stainless jar + z3/cvc5 solvers (git-ignored; version pinned in install.sh).
# The "linux" build's solver binaries are x86_64; on an Apple Silicon host
# Docker Desktop's Rosetta emulation covers them.
if ! ls tools/stainless/lib/stainless-dotty-standalone-*.jar >/dev/null 2>&1; then
    ./install.sh linux
fi

# Python venv + test dependencies (python/env is git-ignored).
if [ ! -d python/env ]; then
    python3 -m venv python/env
fi
python/env/bin/pip install --quiet -r python/requirements.txt

echo
echo "Dev container ready. Typical workflow:"
echo "  ./native/build.sh                            # GenC -> native/build/libpocketplus.so"
echo "  cd python"
echo "  env/bin/python -m pytest -m 'not verify'     # interop suite"
echo "  env/bin/python -m pytest -m verify           # Stainless verification gate (slow)"
