"""The yellow-book encoder input rules, checked only to explain a failing conformance vector.

The suite's encoder vectors deliberately break these rules, and each expected output holds the
packets before the first one that does (README_crossvalidation, "Input vectors"). Nothing is filtered
on them: every vector still reaches the codec unchanged, and a failure is only annotated with the
rule its input breaks, so the reader can tell a codec that skips a check from one that encodes
wrongly.

Checked in the order the ccsds124 C crossvalidation encoder checks them, per packet: R, then the
init phase (CCSDS 124.0-B-1 3.3.2) from the flag byte, then, once the field is complete, its padding.
A truncated input breaks no rule: the suite only expects nothing for the incomplete packet.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

MAX_F = 65535
MAX_R = 7

RULE_F = "F outside 1..65535"
RULE_MASK_PADDING = "nonzero M_0 padding"
RULE_R = "R > 7"
RULE_INIT = "init phase flags (3.3.2)"
RULE_PADDING = "nonzero padding"
NO_RULE = "no input rule"

_F_FLAG = 0x40
_R_FLAG = 0x10


@dataclass(frozen=True)
class Violation:
    t: int     # the first packet the expected output omits; 0 for F and M_0
    rule: str

    def __str__(self) -> str:
        return f"input invalid at t={self.t} ({self.rule})"


def _padding_set(field: bytes, f: int) -> bool:
    pad = -f % 8
    return pad > 0 and field[-1] & ((1 << pad) - 1) != 0


def first_violation(data: bytes) -> Violation | None:
    """The first rule a yellow-book encoder input (``.raw+config``) breaks, or None."""
    if len(data) < 4:
        return None
    (f,) = struct.unpack_from(">I", data)
    if f == 0 or f > MAX_F:
        return Violation(0, RULE_F)
    stride = (f + 7) // 8
    pos = 4
    if len(data) - pos < stride:
        return None
    if _padding_set(data[pos:pos + stride], f):
        return Violation(0, RULE_MASK_PADDING)
    pos += stride
    t = 0
    while pos < len(data):
        flag = data[pos]
        pos += 1
        r = flag & 0x0F
        if r > MAX_R:
            return Violation(t, RULE_R)
        if t <= r and (not flag & _R_FLAG or (r > 0 and not flag & _F_FLAG)):
            return Violation(t, RULE_INIT)
        if len(data) - pos < stride:
            return None
        if _padding_set(data[pos:pos + stride], f):
            return Violation(t, RULE_PADDING)
        pos += stride
        t += 1
    return None


def outcome(v: Violation, packets_out: int | None) -> str:
    """What the codec did at the invalid packet, judged by how many packets it emitted."""
    if packets_out is None:
        return ""
    if packets_out > v.t:
        return "codec encoded past it"
    if packets_out == v.t:
        return "codec stopped there"
    return f"codec stopped at t={packets_out}, before it"
