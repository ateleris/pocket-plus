"""Interop round-trip tests: compress then decompress a sequence of slowly-changing vectors
through the GenC C codec and assert lossless reconstruction."""
import itertools

import pytest

import pocketplus


def _gen_vectors(n, count, seed):
    """Deterministic slowly-changing vectors (0..2 bit flips per step), no system randomness."""
    state = (0xC0FFEE + seed * 131) & 0xFFFFFFFF

    def nxt():
        nonlocal state
        state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
        return state

    vectors = []
    cur = [(nxt() & 0x10000) != 0 for _ in range(n)]
    vectors.append(list(cur))
    for _ in range(1, count):
        flips = nxt() % 3
        for _ in range(flips):
            pos = nxt() % n
            cur[pos] = not cur[pos]
        vectors.append(list(cur))
    return vectors


SIZES = [1, 8, 17, 64, 200]
ROBUSTNESS = [0, 1, 3, 7]


@pytest.mark.parametrize("n,rob", list(itertools.product(SIZES, ROBUSTNESS)))
def test_roundtrip(n, rob):
    vectors = _gen_vectors(n, count=40, seed=n * 100 + rob)
    decoded = pocketplus.round_trip(vectors, n, rob)
    assert decoded == vectors


def test_output_capacity_formula():
    assert pocketplus.output_capacity_bits(64) == 4 + 64 * (64 + 1)


def test_constant_signal_roundtrips():
    n, rob = 32, 2
    vectors = [[True] * n for _ in range(10)]
    assert pocketplus.round_trip(vectors, n, rob) == vectors


def test_single_vector():
    n, rob = 16, 0
    vectors = [[i % 2 == 0 for i in range(n)]]
    assert pocketplus.round_trip(vectors, n, rob) == vectors
