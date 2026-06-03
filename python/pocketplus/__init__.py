"""POCKET+ / CCSDS 124.0-B-1 codec — Python interop over the GenC-generated C (via the pp_* DLL).

High-level helpers encode a sequence of fixed-length bit vectors into one byte-aligned stream and
decode it back, exactly as the reference driver does: the first ``robustness+1`` packets are sent
self-initialising (send-mask + uncompressed), each packet is zero-padded to a byte boundary.
"""
from __future__ import annotations

from ._native import Compressor, Decompressor, output_capacity_bits

BITS_PER_BYTE = 8


def compress_sequence(vectors, n: int, robustness: int, initial_mask=None) -> list[bool]:
    """Compress a list of n-bit vectors into one byte-aligned MSB-first bit stream."""
    comp = Compressor(n)
    try:
        if initial_mask is not None:
            comp.set_initial_mask(initial_mask)
        stream: list[bool] = []
        for t, vec in enumerate(vectors):
            init = t <= robustness          # first packets must self-initialise
            packet = comp.compress(vec, robustness, False, init, init)
            stream.extend(packet)
            while len(stream) % BITS_PER_BYTE != 0:
                stream.append(False)
        return stream
    finally:
        comp.close()


def decompress_sequence(stream, n: int, count: int) -> list[list[bool]]:
    """Decode ``count`` n-bit vectors from a stream produced by :func:`compress_sequence`."""
    dec = Decompressor(n)
    try:
        out, offset = [], 0
        for _ in range(count):
            vec, offset = dec.decompress(stream, offset)
            out.append(vec)
        return out
    finally:
        dec.close()


def round_trip(vectors, n: int, robustness: int, initial_mask=None) -> list[list[bool]]:
    """Compress then decompress a sequence; the result should equal ``vectors`` (lossless)."""
    stream = compress_sequence(vectors, n, robustness, initial_mask)
    return decompress_sequence(stream, n, len(vectors))


__all__ = [
    "Compressor", "Decompressor", "output_capacity_bits",
    "compress_sequence", "decompress_sequence", "round_trip", "BITS_PER_BYTE",
]
