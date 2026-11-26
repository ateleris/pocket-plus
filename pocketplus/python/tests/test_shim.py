"""Interop tests of the pp_* shim itself: bit packing, sizes, and the argument checks that return an
error code instead of tripping an assert in the generated code (R-PERF-07)."""
import ctypes

import pytest

import pocketplus
from pocketplus import _native


def test_pack_unpack():
    bits = [True, False, True, True, False, False, False, True, True]
    data = pocketplus.pack_bits(bits)
    assert data == bytes([0b10110001, 0b10000000])
    assert pocketplus.unpack_bits(data, len(bits)) == bits


def test_bytes_per_vector():
    assert [pocketplus.bytes_per_vector(n) for n in (1, 8, 9, 65535)] == [1, 1, 2, 8192]


@pytest.mark.parametrize("n", [-1, 0, 65536])
def test_length_out_of_range(n):
    with pytest.raises(ValueError):
        pocketplus.Compressor(n)
    with pytest.raises(ValueError):
        pocketplus.Decompressor(n)


@pytest.mark.parametrize("rob", [-1, 8])
def test_robustness_out_of_range(rob):
    comp = pocketplus.Compressor(8)
    with pytest.raises(RuntimeError, match=f"error {_native.ERR_ARGUMENT}"):
        comp.compress([False] * 8, rob, False, True, True)


@pytest.mark.parametrize("send,uncompressed", [(False, True), (True, False), (False, False)])
def test_first_vectors_must_be_self_contained(send, uncompressed):
    # blue book 3.3.2 c) and d): f_t = r_t = 1 while t <= R_t
    comp = pocketplus.Compressor(8)
    with pytest.raises(RuntimeError, match=f"error {_native.ERR_ARGUMENT}"):
        comp.compress([False] * 8, 0, False, send, uncompressed)


def test_bad_raw_arguments():
    lib = _native._lib
    buf = (ctypes.c_uint8 * 1)()
    comp = pocketplus.Compressor(8)
    dec = pocketplus.Decompressor(8)
    assert lib.pp_compress(None, buf, 0, False, True, True, buf, 8) == _native.ERR_ARGUMENT
    assert lib.pp_compress(comp._h, None, 0, False, True, True, buf, 8) == _native.ERR_ARGUMENT
    assert lib.pp_compress(comp._h, buf, 0, False, True, True, buf, -1) == _native.ERR_ARGUMENT
    assert lib.pp_decompress(None, buf, 8, buf) == _native.ERR_ARGUMENT
    assert lib.pp_decompress(dec._h, buf, -1, buf) == _native.ERR_ARGUMENT
    assert lib.pp_bytes_per_vector(0) == _native.ERR_ARGUMENT
    assert lib.pp_compress_output_capacity_bits(65536) == _native.ERR_ARGUMENT
