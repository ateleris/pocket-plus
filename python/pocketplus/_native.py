"""ctypes bindings to the POCKET+ DLL (the pp_* shim API in native/src/pp_shim.c).

Exposes Pythonic ``Compressor`` / ``Decompressor`` wrappers so callers work with lists of bools
and never touch the C structs. The codec is MSB-first: index 0 is the first transmitted bit.
"""
from __future__ import annotations

import ctypes
import glob
import os
import sys
from ctypes import c_bool, c_int32, c_void_p, POINTER

_BOOL_P = POINTER(c_bool)


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
    lib.pp_compress_output_capacity_bits.argtypes = [c_int32]
    lib.pp_compress_output_capacity_bits.restype = c_int32
    lib.pp_compressor_create.argtypes = [c_int32]
    lib.pp_compressor_create.restype = c_void_p
    lib.pp_compressor_free.argtypes = [c_void_p]
    lib.pp_compressor_free.restype = None
    lib.pp_compressor_set_initial_mask.argtypes = [c_void_p, _BOOL_P, c_int32]
    lib.pp_compressor_set_initial_mask.restype = None
    lib.pp_compress.argtypes = [c_void_p, _BOOL_P, c_int32, c_int32, c_bool, c_bool, c_bool,
                                _BOOL_P, c_int32]
    lib.pp_compress.restype = c_int32
    lib.pp_decompressor_create.argtypes = [c_int32]
    lib.pp_decompressor_create.restype = c_void_p
    lib.pp_decompressor_free.argtypes = [c_void_p]
    lib.pp_decompressor_free.restype = None
    lib.pp_decompress.argtypes = [c_void_p, _BOOL_P, c_int32, c_int32, _BOOL_P, c_int32]
    lib.pp_decompress.restype = c_int32
    return lib


_lib = _load()


def _buf(bits) -> "ctypes.Array":
    arr = (c_bool * len(bits))()
    for i, b in enumerate(bits):
        arr[i] = bool(b)
    return arr


def output_capacity_bits(n: int) -> int:
    return int(_lib.pp_compress_output_capacity_bits(n))


class Compressor:
    def __init__(self, n: int):
        self.n = n
        self._h = _lib.pp_compressor_create(n)
        if not self._h:
            raise ValueError(f"pp_compressor_create failed for n={n}")
        self._cap = output_capacity_bits(n)

    def set_initial_mask(self, mask) -> None:
        if len(mask) != self.n:
            raise ValueError("mask length must equal n")
        _lib.pp_compressor_set_initial_mask(self._h, _buf(mask), self.n)

    def compress(self, vector, robustness: int, new_mask_flag: bool,
                 send_mask_flag: bool, uncompressed_flag: bool):
        if len(vector) != self.n:
            raise ValueError("vector length must equal n")
        out = (c_bool * self._cap)()
        bitlen = int(_lib.pp_compress(self._h, _buf(vector), self.n, robustness,
                                      new_mask_flag, send_mask_flag, uncompressed_flag,
                                      out, self._cap))
        if bitlen < 0:
            raise RuntimeError("pp_compress rejected its arguments")
        return [bool(out[i]) for i in range(bitlen)]

    def close(self):
        if getattr(self, "_h", None):
            _lib.pp_compressor_free(self._h)
            self._h = None

    def __del__(self):
        self.close()


class Decompressor:
    def __init__(self, n: int):
        self.n = n
        self._h = _lib.pp_decompressor_create(n)
        if not self._h:
            raise ValueError(f"pp_decompressor_create failed for n={n}")

    def decompress(self, stream, bit_offset: int):
        """Returns (reconstructed_bits, next_bit_offset)."""
        sbuf = _buf(stream)
        out = (c_bool * self.n)()
        new_off = int(_lib.pp_decompress(self._h, sbuf, len(stream), bit_offset, out, self.n))
        return [bool(out[i]) for i in range(self.n)], new_off

    def close(self):
        if getattr(self, "_h", None):
            _lib.pp_decompressor_free(self._h)
            self._h = None

    def __del__(self):
        self.close()
