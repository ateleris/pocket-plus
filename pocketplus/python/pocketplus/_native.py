"""ctypes bindings to the POCKET+ DLL (the pp_* shim API in native/src/pp_shim.c).

Exposes Pythonic ``Compressor`` / ``Decompressor`` wrappers so callers work with lists of bools
and never touch the C structs. The codec is MSB-first: index 0 is the first transmitted bit, and
vectors cross the C boundary packed MSB first into bytes.
"""
from __future__ import annotations

import ctypes
import glob
import os
import sys
from ctypes import c_bool, c_int32, c_uint8, c_void_p, POINTER

_BYTE_P = POINTER(c_uint8)

# Error codes of pp_shim.h.
ERR_CAPACITY = -1
ERR_MALFORMED = -2
ERR_ARGUMENT = -3


if sys.platform == "win32":
    _LIB_NAME = "pocketplus.dll"
elif sys.platform == "darwin":
    _LIB_NAME = "libpocketplus.dylib"
else:
    _LIB_NAME = "libpocketplus.so"


def _find_dll() -> str:
    env = os.environ.get("POCKETPLUS_DLL_DIR")
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    candidates = []
    if env:
        candidates.append(os.path.join(env, _LIB_NAME))
    # MSBuild output (Windows): prefer Debug, then Release, then any.
    for cfg in ("Debug", "Release"):
        candidates.append(os.path.join(repo, "native", "x64", cfg, _LIB_NAME))
    candidates += glob.glob(os.path.join(repo, "native", "x64", "*", _LIB_NAME))
    # native/build.sh output (macOS/Linux).
    candidates.append(os.path.join(repo, "native", "build", _LIB_NAME))
    for c in candidates:
        if c and os.path.isfile(c):
            return c
    raise FileNotFoundError(
        f"{_LIB_NAME} not found. Build the native library first (Visual Studio on Windows, "
        "native/build.sh on macOS/Linux), or set POCKETPLUS_DLL_DIR. Searched:\n  "
        + "\n  ".join(candidates)
    )


def _load():
    lib = ctypes.CDLL(_find_dll())
    lib.pp_bytes_per_vector.argtypes = [c_int32]
    lib.pp_bytes_per_vector.restype = c_int32
    lib.pp_compress_output_capacity_bits.argtypes = [c_int32]
    lib.pp_compress_output_capacity_bits.restype = c_int32
    lib.pp_compressor_create.argtypes = [c_int32, _BYTE_P]
    lib.pp_compressor_create.restype = c_void_p
    lib.pp_compressor_free.argtypes = [c_void_p]
    lib.pp_compressor_free.restype = None
    lib.pp_compress.argtypes = [c_void_p, _BYTE_P, c_int32, c_bool, c_bool, c_bool,
                                _BYTE_P, c_int32]
    lib.pp_compress.restype = c_int32
    lib.pp_decompressor_create.argtypes = [c_int32]
    lib.pp_decompressor_create.restype = c_void_p
    lib.pp_decompressor_free.argtypes = [c_void_p]
    lib.pp_decompressor_free.restype = None
    lib.pp_decompress.argtypes = [c_void_p, _BYTE_P, c_int32, _BYTE_P]
    lib.pp_decompress.restype = c_int32
    return lib


_lib = _load()


def pack_bits(bits) -> bytes:
    """Packs bits MSB first into bytes, zero-padding the last byte."""
    out = bytearray((len(bits) + 7) // 8)
    for i, b in enumerate(bits):
        if b:
            out[i >> 3] |= 0x80 >> (i & 7)
    return bytes(out)


def unpack_bits(data, nbits: int) -> list[bool]:
    """The first nbits bits of data, MSB first."""
    return [bool(data[i >> 3] & (0x80 >> (i & 7))) for i in range(nbits)]


def _buf(data: bytes, size: int) -> "ctypes.Array":
    """A C buffer of size bytes (at least one) holding data."""
    arr = (c_uint8 * max(size, 1))()
    ctypes.memmove(arr, data, len(data))
    return arr


def bytes_per_vector(n: int) -> int:
    nb = int(_lib.pp_bytes_per_vector(n))
    if nb < 0:
        raise ValueError(f"vector length out of range: n={n}")
    return nb


def output_capacity_bits(n: int) -> int:
    cap = int(_lib.pp_compress_output_capacity_bits(n))
    if cap < 0:
        raise ValueError(f"vector length out of range: n={n}")
    return cap


class Compressor:
    def __init__(self, n: int, initial_mask=None):
        """Starts a stream of n-bit vectors with initial mask M_0 (all zero if None)."""
        self.n = n
        self._nb = bytes_per_vector(n)
        mask = None
        if initial_mask is not None:
            if len(initial_mask) != n:
                raise ValueError("mask length must equal n")
            mask = _buf(pack_bits(initial_mask), self._nb)
        self._h = _lib.pp_compressor_create(n, mask)
        if not self._h:
            raise ValueError(f"pp_compressor_create failed for n={n}")
        self._cap = output_capacity_bits(n)

    def compress(self, vector, robustness: int, new_mask_flag: bool,
                 send_mask_flag: bool, uncompressed_flag: bool):
        if len(vector) != self.n:
            raise ValueError("vector length must equal n")
        out = (c_uint8 * ((self._cap + 7) // 8))()
        bitlen = int(_lib.pp_compress(self._h, _buf(pack_bits(vector), self._nb), robustness,
                                      new_mask_flag, send_mask_flag, uncompressed_flag,
                                      out, self._cap))
        if bitlen < 0:
            raise RuntimeError(f"pp_compress failed with error {bitlen}")
        return unpack_bits(out, bitlen)

    def close(self):
        if getattr(self, "_h", None):
            _lib.pp_compressor_free(self._h)
            self._h = None

    def __del__(self):
        self.close()


class Decompressor:
    def __init__(self, n: int):
        self.n = n
        self._nb = bytes_per_vector(n)
        self._h = _lib.pp_decompressor_create(n)
        if not self._h:
            raise ValueError(f"pp_decompressor_create failed for n={n}")

    def decompress(self, packet: bytes, packet_bits: int):
        """Decodes the output vector at the front of the first packet_bits bits of packet (packed
        MSB first). Returns (reconstructed_bits, bits_consumed)."""
        out = (c_uint8 * self._nb)()
        used = int(_lib.pp_decompress(self._h, _buf(packet, (packet_bits + 7) // 8), packet_bits,
                                      out))
        if used < 0:
            raise ValueError(f"pp_decompress failed with error {used}")
        return unpack_bits(out, self.n), used

    def close(self):
        if getattr(self, "_h", None):
            _lib.pp_decompressor_free(self._h)
            self._h = None

    def __del__(self):
        self.close()
