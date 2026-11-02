"""POCKET+ / CCSDS 124.0-B-1 codec — Python interop over the GenC-generated C (via the pp_* DLL).

High-level helpers encode a sequence of fixed-length bit vectors into one byte-aligned stream and
decode it back, exactly as the reference driver does: the first ``robustness+1`` packets are sent
self-initialising (send-mask + uncompressed), each packet is zero-padded to a byte boundary.
"""
from __future__ import annotations

from ._native import (Compressor, Decompressor, bytes_per_vector, output_capacity_bits,
                      pack_bits, unpack_bits)

BITS_PER_BYTE = 8


def compress_sequence(vectors, n: int, robustness: int, initial_mask=None) -> list[bool]:
    """Compress a list of n-bit vectors into one byte-aligned MSB-first bit stream."""
    comp = Compressor(n, initial_mask)
    try:
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
    """Decode ``count`` n-bit vectors from a stream produced by :func:`compress_sequence`.

    Each packet starts on a byte boundary: the decoder reports the bits it consumed, and the
    padding up to the next byte is skipped."""
    data = pack_bits(stream)
    dec = Decompressor(n)
    try:
        out, offset = [], 0
        for _ in range(count):
            vec, used = dec.decompress(data[offset // BITS_PER_BYTE:], len(stream) - offset)
            out.append(vec)
            offset += used
            offset += -offset % BITS_PER_BYTE
        return out
    finally:
        dec.close()


def round_trip(vectors, n: int, robustness: int, initial_mask=None) -> list[list[bool]]:
    """Compress then decompress a sequence; the result should equal ``vectors`` (lossless)."""
    stream = compress_sequence(vectors, n, robustness, initial_mask)
    return decompress_sequence(stream, n, len(vectors))


__all__ = [
    "Compressor", "Decompressor", "bytes_per_vector", "output_capacity_bits",
    "pack_bits", "unpack_bits",
    "compress_sequence", "decompress_sequence", "round_trip", "BITS_PER_BYTE",
]
