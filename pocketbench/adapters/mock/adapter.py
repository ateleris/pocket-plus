#!/usr/bin/env python3
"""Mock identity-codec adapter: the worked example of the pocketbench adapter contract, and the
test double the pocketbench unit tests drive so the suite needs no C/Rust build.

It is also the Python copy of the shared plumbing (strict flags, the record container, the timed
loop, the summary JSON), held to the same behaviour as adapters/common/c and adapters/common/rust
by tests/test_plumbing.py. Imports nothing from pocketbench: an adapter is an independent
executable that only speaks the contract over argv + stdout.
"""

from __future__ import annotations

import json
import struct
import sys
import time
from pathlib import Path

CAPABILITIES = {"build_profile": "", "limitations": "identity test double: every packet is its own field"}

DECODED, FAILED, LOST = 0x00, 0x01, 0x02
NOT_REPRESENTABLE = "input not representable by this codec's API"


class UsageError(Exception):
    """A contract violation: exits 2."""


class HookError(Exception):
    """A hook could not take its input; the message goes into the summary."""


# --- the identity codec: the same behaviour as tests/fixtures/adapters/{c,rust}-identity ---------


def enc_create(f, mask):
    if f is None:
        raise HookError(NOT_REPRESENTABLE)
    return {}


def enc_packet(state, flag, field):
    if flag & 0x80:
        raise HookError("codec error 7")
    return field, 8 * len(field)


def dec_create(f, records):
    return {"f": f}


def dec_packet(state, data, declared_bits, avail_bits):
    if declared_bits == 13:
        raise HookError("codec error -9")
    if state["f"] == 0:
        return FAILED, b"", 0
    return DECODED, data, 8 * len(data)


def dec_lost(state):
    pass


def dec_trailer_f(state):
    return state["f"]


# --- plumbing ------------------------------------------------------------------------------------


def _args(sub: str, argv: list[str]) -> dict[str, str]:
    seen: dict[str, str] = {}
    for arg in argv:
        if not arg.startswith("--"):
            raise UsageError(
                f"{sub}: unexpected positional argument {arg!r}; the contract passes keyed flags"
            )
        key, sep, value = arg[2:].partition("=")
        if not sep:
            raise UsageError(f"{sub}: flag {arg!r} needs a value, written --key=value")
        if key not in ("in", "out", "warmup", "iterations"):
            raise UsageError(
                f"{sub}: unknown flag --{key}; this adapter does not implement it, so the harness "
                "and the adapter disagree about the contract"
            )
        if key in seen:
            raise UsageError(f"{sub}: flag --{key} given more than once")
        if key in ("in", "out") and not value:
            raise UsageError(f"{sub}: flag --{key} needs a non-empty value")
        if key in ("warmup", "iterations") and not value.isdigit():
            raise UsageError(f"{sub}: flag --{key} must be a non-negative integer, got {value!r}")
        seen[key] = value
    for key in ("in", "out"):
        if key not in seen:
            raise UsageError(f"{sub}: missing required flag --{key}")
    return seen


def _container(sub: str, buf: bytes):
    if len(buf) < 4:
        return None, []
    (f,) = struct.unpack_from(">I", buf)
    records, pos = [], 4
    while pos < len(buf):
        if len(buf) - pos < 5:
            raise UsageError(f"{sub}: malformed record container: record header cut off at byte {pos}")
        kind, bits = struct.unpack_from(">BI", buf, pos)
        pos += 5
        data = buf[pos:pos + (bits + 7) // 8]
        pos += len(data)
        records.append((kind, bits, data))
    return f, records


def _compress_pass(f, records):
    chunks = [struct.pack(">I", f or 0)]
    try:
        state = enc_create(f, records[0] if records else None)
    except HookError as e:
        return chunks, 0, 0, str(e)
    done = 0
    for i, (kind, _bits, data) in enumerate(records[1:], 1):
        try:
            packet, bits = enc_packet(state, kind, data)
        except HookError as e:
            return chunks, done, i, str(e)
        chunks.append(struct.pack(">BI", 0, bits) + packet)
        done += 1
    return chunks, done, None, None


def _decompress_pass(f, records):
    try:
        state = dec_create(f or 0, records)
    except HookError as e:
        return [struct.pack(">I", 0)], 0, 0, str(e)
    body = []
    for i, (kind, bits, data) in enumerate(records):
        if kind & 1:
            dec_lost(state)
            body.append(struct.pack(">BI", LOST, 0))
            continue
        try:
            status, field, field_bits = dec_packet(state, data, bits, min(bits, 8 * len(data)))
        except HookError as e:
            return [struct.pack(">I", dec_trailer_f(state)), *body], len(body), i, str(e)
        if status != DECODED:
            status, field, field_bits = FAILED, b"", 0
        body.append(struct.pack(">BI", status, field_bits) + field)
    return [struct.pack(">I", dec_trailer_f(state)), *body], len(body), None, None


def _verb(sub: str, argv: list[str]) -> int:
    flags = _args(sub, argv)
    f, records = _container(sub, Path(flags["in"]).read_bytes())
    one_pass = _compress_pass if sub == "compress" else _decompress_pass
    for _ in range(int(flags.get("warmup", "0"))):
        one_pass(f, records)
    nanos: list[int] = []
    iterations = int(flags.get("iterations", "0"))
    if iterations > 0:
        for _ in range(iterations):
            t0 = time.perf_counter_ns()
            result = one_pass(f, records)
            nanos.append(time.perf_counter_ns() - t0)
            if result[2] is not None:
                break
    else:
        result = one_pass(f, records)
    chunks, done, stopped_at, error = result
    Path(flags["out"]).write_bytes(b"".join(chunks))
    summary = {"records_in": len(records), "records_out": done, "stopped_at": stopped_at, "error": error}
    if "iterations" in flags:
        summary["nanos"] = nanos
    print(json.dumps(summary))
    return 0


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: adapter.py <capabilities|compress|decompress> [--key=value ...]", file=sys.stderr)
        return 2
    cmd = argv[0]
    try:
        if cmd == "capabilities":
            print(json.dumps(CAPABILITIES))
            return 0
        if cmd in ("compress", "decompress"):
            return _verb(cmd, argv[1:])
    except UsageError as e:
        print(e, file=sys.stderr)
        return 2
    except OSError as e:
        print(e, file=sys.stderr)
        return 1
    print(f"unknown subcommand: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
