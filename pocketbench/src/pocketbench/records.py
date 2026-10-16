"""The record container that both adapter verbs read and write, and its re-framing.

One format carries all four streams (compress in and out, decompress in and out):

    header:  F        u32 big-endian (decompress input: 0 = unknown)
    record:  kind     u8
             bits     u32 big-endian, the declared length in bits
             payload  ceil(bits / 8) bytes, or fewer when the data ends first

A compress input's first record is the initial mask M_0; its packet records carry the yellow-book
flag byte as their kind. Re-framing copies byte ranges and never inspects values: whether an input
is valid is for the codec to decide, not pocketbench.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

# Status kinds of a decompress output record, numbered as in the yellow book.
STATUS_DECODED = 0x00
STATUS_UNDECODABLE = 0x01
STATUS_LOST = 0x02

_U32 = struct.Struct(">I")
_RECORD = struct.Struct(">BI")


@dataclass(frozen=True)
class Record:
    kind: int
    bits: int
    payload: bytes


@dataclass(frozen=True)
class Container:
    f: int | None  # None: the stream is shorter than its 4-byte header
    records: tuple[Record, ...] = ()


def payload_bytes(bits: int) -> int:
    return (bits + 7) // 8


def encode(c: Container) -> bytes:
    """Serialize ``c``. A short payload is read to the end of the stream, so only the last record
    may carry one."""
    if c.f is None:
        if c.records:
            raise ValueError("a container without a header holds no records")
        return b""
    out = bytearray(_U32.pack(c.f))
    last = len(c.records) - 1
    for i, r in enumerate(c.records):
        full = payload_bytes(r.bits)
        if len(r.payload) > full or (i < last and len(r.payload) != full):
            raise ValueError(f"record {i}: {len(r.payload)} payload bytes for {r.bits} bits")
        out += _RECORD.pack(r.kind, r.bits)
        out += r.payload
    return bytes(out)


def decode(data: bytes) -> Container:
    """Parse a container; the last record's payload may be short."""
    if len(data) < _U32.size:
        return Container(None)
    (f,) = _U32.unpack_from(data)
    out: list[Record] = []
    pos = _U32.size
    while pos < len(data):
        if len(data) - pos < _RECORD.size:
            raise ValueError(f"truncated record header at byte {pos}")
        kind, bits = _RECORD.unpack_from(data, pos)
        pos += _RECORD.size
        end = min(pos + payload_bytes(bits), len(data))
        out.append(Record(kind, bits, data[pos:end]))
        pos = end
    return Container(f, tuple(out))


def from_fields(f: int, fields: bytes, flags: list[int]) -> Container:
    """A compress input for raw packets: an all-zero initial mask, then one record per
    ``ceil(f / 8)``-byte field with its flag byte. A trailing partial field is carried as it is."""
    stride = payload_bytes(f)
    out = [Record(0, f, bytes(stride))]
    out += [Record(flag, f, fields[i * stride:(i + 1) * stride]) for i, flag in enumerate(flags)]
    return Container(f, tuple(out))


def from_raw_config(data: bytes) -> Container:
    """Re-frame a yellow-book encoder input (``.raw+config``): BE32 F, the initial mask, then per
    packet a flag byte and the field."""
    if len(data) < _U32.size:
        return Container(None)
    (f,) = _U32.unpack_from(data)
    stride = payload_bytes(f)
    pos = _U32.size
    out = [Record(0, f, data[pos:pos + stride])]
    pos += stride
    while pos < len(data):
        out.append(Record(data[pos], f, data[pos + 1:pos + 1 + stride]))
        pos += 1 + stride
    return Container(f, tuple(out))


def from_124_config(data: bytes) -> Container:
    """Re-frame a yellow-book decoder input (``.124+config``): per element a reception byte (bit 0
    set: lost), then for a received element a BE32 bit length and the packet. The header F is 0,
    so the codec discovers it. An element whose length field is cut off becomes an empty packet."""
    out: list[Record] = []
    pos = 0
    while pos < len(data):
        kind = data[pos]
        pos += 1
        if kind & 1:
            out.append(Record(kind, 0, b""))
            continue
        if len(data) - pos < _U32.size:
            out.append(Record(kind, 0, b""))
            break
        (bits,) = _U32.unpack_from(data, pos)
        pos += _U32.size
        payload = data[pos:pos + payload_bytes(bits)]
        out.append(Record(kind, bits, payload))
        pos += len(payload)
    return Container(0, tuple(out))


def to_124(c: Container) -> bytes:
    """A compress output as the yellow-book ``.124`` (and reference ``.pkt``): the packets back to
    back."""
    return b"".join(r.payload for r in c.records)


def to_raw_large_f(c: Container) -> bytes:
    """A decompress output as the yellow-book ``.raw+large_f``: per element its status byte and,
    after a 0x00, the decoded field; then the BE32 trailer F."""
    out = bytearray()
    for r in c.records:
        out.append(r.kind)
        if r.kind == STATUS_DECODED:
            out += r.payload
    out += _U32.pack(c.f or 0)
    return bytes(out)
