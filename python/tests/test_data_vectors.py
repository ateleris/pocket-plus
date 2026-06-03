"""Round-trip any private test vectors dropped in the repo's data/ directory.

Vector file format (see data/README.md): plain text, first non-blank line is F (the bit width),
each subsequent non-blank line is exactly F characters of '0'/'1' (one input vector). Blank lines
and lines starting with '#' are ignored. Files are matched by data/*.vectors.

If no vector files are present, the tests are skipped (the data/ contents are private and gitignored).
"""
import glob
import os

import pytest

import pocketplus

_DATA_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "data")
_FILES = sorted(glob.glob(os.path.join(_DATA_DIR, "*.vectors")))


def _parse(path):
    f = None
    vectors = []
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if f is None:
                f = int(line)
                continue
            if len(line) != f or any(c not in "01" for c in line):
                raise ValueError(f"{path}: expected {f} chars of 0/1, got {line!r}")
            vectors.append([c == "1" for c in line])
    if f is None or not vectors:
        raise ValueError(f"{path}: no F header and/or no vectors")
    return f, vectors


@pytest.mark.skipif(not _FILES, reason="no data/*.vectors files present (private)")
@pytest.mark.parametrize("path", _FILES, ids=[os.path.basename(p) for p in _FILES])
@pytest.mark.parametrize("rob", [0, 3, 7])
def test_data_vectors_roundtrip(path, rob):
    n, vectors = _parse(path)
    assert pocketplus.round_trip(vectors, n, rob) == vectors
